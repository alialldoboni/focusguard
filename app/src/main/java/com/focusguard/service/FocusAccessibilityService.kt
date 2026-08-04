package com.focusguard.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.focusguard.FocusGuardApplication
import com.focusguard.MainActivity
import com.focusguard.R
import com.focusguard.ai.BlockingPolicy
import com.focusguard.ai.OnDeviceClassifier
import com.focusguard.ai.ScreenSignal
import com.focusguard.db.entity.Preferences
import com.focusguard.db.entity.RelapseEvent
import com.focusguard.db.entity.ScreenTimeEvent
import com.focusguard.db.entity.ScrollSession
import com.focusguard.ocr.OcrPipeline
import com.focusguard.ocr.OcrResult
import com.focusguard.ocr.OcrTextRecognizer
import com.focusguard.ocr.ScreenCaptureProvider
import com.focusguard.tracker.ScreenTimeTracker
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FocusAccessibilityService : AccessibilityService() {

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(
        Dispatchers.Main +
            serviceJob +
            CoroutineExceptionHandler { _, throwable ->
                android.util.Log.e(
                    "FocusGuard",
                    "Scan loop crashed; continuing",
                    throwable
                )
            }
    )
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val classifier = OnDeviceClassifier()
    private val tracker = ScreenTimeTracker()
    private val captureProvider = ScreenCaptureProvider(this)
    private val textRecognizer = OcrTextRecognizer()
    private val ocrPipeline = OcrPipeline(captureProvider, textRecognizer)
    private val userSettings = FocusGuardApplication.userSettings

    private val screenWidth: Int by lazy {
        maxOf(1, resources.displayMetrics.widthPixels)
    }
    private val screenHeight: Int by lazy {
        maxOf(1, resources.displayMetrics.heightPixels)
    }

    private var lastPackage = ""
    private var lastEventText = ""
    private var lastClassifiedPackage = ""
    private var lastCacheKey = ""
    private var lastContent = emptyList<String>()
    private var cachedDecision = OnDeviceClassifier.Decision(
        OnDeviceClassifier.Classification.ALLOWED,
        "No blocked content detected."
    )
    private var overlayInFlight = false
    private var exitInProgress = false
    private var youtubeSessionWasProductive = false

    private var slopPackage = ""
    private var slopStartElapsed = 0L
    private var activeSessionStartWall = 0L
    private var activeSessionLabel = ""
    private var activeSessionSummary = ""
    private var lastUsagePackage = ""
    private var lastUsageTickElapsed = 0L

    private var scanning = false
    private var scanScheduled = false
    private var receiversRegistered = false
    private var overlayWindowView: View? = null
    private var fallbackExitRunnable: Runnable? = null

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private val toggleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            scope.launch {
                val dao = FocusGuardApplication.database.preferencesDao()
                val enabled = !(dao.getEnabled() ?: false)
                dao.setEnabled(enabled)
            }
        }
    }

    private val overlayFinishedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            overlayInFlight = false
            val blockedPackage = slopPackage
            finishSlopSession()
            lastClassifiedPackage = ""
            lastCacheKey = ""
            lastContent = emptyList()
            exitBlockedApp(blockedPackage)
        }
    }

    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_ON) requestImmediateScan()
        }
    }

    private val scanRunnable = object : Runnable {
        override fun run() {
            scanScheduled = false
            if (!scanning) return
            scope.launch { scanOnce() }
            scheduleNextScan(
                if (isInteractive()) SCAN_INTERVAL_MS else SCREEN_OFF_SCAN_INTERVAL_MS
            )
        }
    }

    companion object {
        const val ACTION_STOP = "com.focusguard.STOP"
        const val ACTION_TOGGLE = "com.focusguard.TOGGLE"
        const val ACTION_RESTART = "com.focusguard.RESTART"
        const val ACTION_OVERLAY_FINISHED = "com.focusguard.OVERLAY_FINISHED"
        private const val SCAN_INTERVAL_MS = 5_000L
        private const val SCREEN_OFF_SCAN_INTERVAL_MS = 30_000L
        private const val OVERLAY_FALLBACK_DURATION_MS = 6_000L
        private const val FULL_SCREEN_WIDTH_RATIO = 0.6f
        private const val FULL_SCREEN_HEIGHT_RATIO = 0.4f

        fun isEnabled(context: Context): Boolean {
            val component = ComponentName(context, FocusAccessibilityService::class.java)
            return accessibilityServicesContain(
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ),
                component.flattenToString(),
                component.flattenToShortString(),
                context.packageName
            )
        }

        /**
         * Pure string check against `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`
         * (a colon-separated list of flattened ComponentNames). Extracted from
         * [isEnabled] so the parsing rules can be unit-tested.
         */
        internal fun accessibilityServicesContain(
            services: String?,
            flattened: String,
            shortFlattened: String,
            packageName: String
        ): Boolean {
            if (services.isNullOrBlank()) return false
            return services.split(':').any {
                it.equals(flattened, ignoreCase = true) ||
                    it.equals(shortFlattened, ignoreCase = true) ||
                    it.equals(packageName, ignoreCase = true)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Restart path from ServiceLifecycleReceiver / watchdog / notification.
        // Must call startForeground within 5s of startForegroundService().
        if (intent?.action == ACTION_RESTART || intent == null) {
            startForegroundCompat()
            registerReceivers()
            startScanning()
        }
        return START_STICKY
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = SCAN_INTERVAL_MS
        }

        createNotificationChannels()
        startForegroundCompat()
        scope.launch {
            val dao = FocusGuardApplication.database.preferencesDao()
            if (dao.getPreferences() == null) {
                dao.setPreferences(Preferences())
            }
        }
        registerReceivers()
        startScanning()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val packageName = event.packageName?.toString().orEmpty()
        if (packageName.isNotEmpty() &&
            packageName != this.packageName &&
            !shouldIgnorePackage(packageName)
        ) {
            updateCurrentPackage(packageName)
        }
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                event.text
                    ?.mapNotNull { it?.toString() }
                    ?.takeIf { it.isNotEmpty() }
                    ?.joinToString(" ")
                    ?.let { lastEventText = it }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> requestImmediateScan()
        }
    }

    override fun onInterrupt() {
        stopScanning()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopScanning()
        finishSlopSession()
        dismissBlockOverlayFallback()
        fallbackExitRunnable?.let(handler::removeCallbacks)
        fallbackExitRunnable = null
        serviceJob.cancel()
        captureProvider.shutdown()
        textRecognizer.close()
        if (receiversRegistered) {
            receiversRegistered = false
            try {
                unregisterReceiver(stopReceiver)
                unregisterReceiver(toggleReceiver)
                unregisterReceiver(overlayFinishedReceiver)
                unregisterReceiver(screenOnReceiver)
            } catch (_: Exception) {
            }
        }
        return super.onUnbind(intent)
    }

    private fun startForegroundCompat() {
        val notification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                1001,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(1001, notification)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                "fg",
                "Background protection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Permanent FocusGuard background status"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            }
        )
        manager.createNotificationChannel(
            NotificationChannel("warn", "Warnings", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 250, 250)
            }
        )
    }

    private fun startScanning() {
        scanning = true
        scanScheduled = false
        scheduleNextScan(0)
    }

    private fun stopScanning() {
        scanning = false
        handler.removeCallbacks(scanRunnable)
    }

    private fun scheduleNextScan(delayMs: Long) {
        if (!scanning) return
        handler.removeCallbacks(scanRunnable)
        handler.postDelayed(scanRunnable, delayMs)
    }

    private fun requestImmediateScan() {
        if (!scanning || scanScheduled) return
        scanScheduled = true
        handler.removeCallbacks(scanRunnable)
        handler.post(scanRunnable)
    }

    private fun isInteractive(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return true
        return powerManager.isInteractive
    }

    private suspend fun scanOnce() {
        val preferences = FocusGuardApplication.database.preferencesDao().getPreferences()
            ?: Preferences()
        if (!preferences.value) {
            resetMonitoringState()
            return
        }
        if (exitInProgress) return
        if (!isInteractive()) return

        val root = rootInActiveWindow
        val rootPackage = root?.packageName?.toString().orEmpty()
        if (rootPackage == packageName) return
        if (rootPackage.isNotEmpty() && shouldIgnorePackage(rootPackage)) {
            resetMonitoringState()
            return
        }
        if (rootPackage.isNotEmpty()) {
            updateCurrentPackage(rootPackage)
        }
        val packageName = lastPackage
        if (packageName.isEmpty() || shouldIgnorePackage(packageName)) return

        recordScreenTime(packageName)

        val signal = root?.let { extractSignal(it, packageName) }
            ?: ScreenSignal(packageName)
        val policy = userSettings.currentPolicy()
        val cacheKey = signal.signature + "|" + policy.key()
        if (packageName == lastClassifiedPackage && cacheKey == lastCacheKey) {
            applyIntervention(packageName, cachedDecision, lastContent)
            return
        }

        val decision = withContext(Dispatchers.Default) {
            classifier.decide(signal, policy)
        }
        android.util.Log.d(
            "FocusGuardDecision",
            "$packageName -> ${decision.classification}: ${decision.reason}"
        )

        val stable = stabilizeYouTubeDecision(packageName, decision)
        if (stable == decision && decision.needsMoreText) {
            // PATH B: text tree empty/suppressed (Realme/ColorOS/Xiaomi) but a
            // player container is active — read the frame via OCR, then re-decide.
            if (ocrPipeline.isInCaptureFailureCooldown()) {
                // Screenshot capture is on cooldown after a recent failure (e.g. a
                // secure watch page). Gracefully bypass OCR — no capture, no log or
                // CPU spam — until the cooldown elapses.
                return
            }
            if (classifier.isYouTubePackage(packageName)) {
                android.util.Log.d(
                    "FocusGuard",
                    "PATH B: YouTube text tree empty, player container active -> OCR for $packageName"
                )
            }
            runOcrClassification(packageName, signal, policy)
            return
        }
        cachedDecision = stable
        lastClassifiedPackage = packageName
        lastCacheKey = cacheKey
        lastContent = signal.texts
        applyIntervention(packageName, stable, signal.texts)
    }

    private fun stabilizeYouTubeDecision(
        packageName: String,
        decision: OnDeviceClassifier.Decision
    ): OnDeviceClassifier.Decision {
        if (!classifier.isYouTubePackage(packageName)) return decision
        if (decision.classification == OnDeviceClassifier.Classification.PRODUCTIVE) {
            youtubeSessionWasProductive = true
            return decision
        }
        if (decision.needsMoreText && youtubeSessionWasProductive) {
            return OnDeviceClassifier.Decision(
                OnDeviceClassifier.Classification.PRODUCTIVE,
                "The previously verified useful video remains allowed while " +
                    "YouTube temporarily hides its title."
            )
        }
        if (decision.classification == OnDeviceClassifier.Classification.SLOP) {
            youtubeSessionWasProductive = false
        }
        return decision
    }

    private suspend fun runOcrClassification(
        packageName: String,
        signal: ScreenSignal,
        policy: BlockingPolicy
    ) {
        android.util.Log.d("FocusGuard", "Path B: Initiating screenshot capture...")
        var decisionApplied = false
        try {
            val result = ocrPipeline.recognize(packageName)
            val enabled = FocusGuardApplication.database.preferencesDao()
                .getEnabled() ?: false
            if (!enabled || packageName != lastPackage) return
            if (result == OcrResult.Skipped) return

            val ocrText = (result as? OcrResult.Text)?.value.orEmpty()
            val updatedSignal = if (ocrText.isNotBlank() && ocrText != "Device locked") {
                signal.withText(ocrText)
            } else {
                signal
            }
            android.util.Log.d(
                "FocusGuard",
                "Path B: Classifying with OCR text: ${ocrText.take(200)}"
            )
            val raw = withContext(Dispatchers.Default) {
                classifier.decide(updatedSignal, policy)
            }
            val finalDecision = if (raw.needsMoreText) {
                OnDeviceClassifier.Decision(
                    OnDeviceClassifier.Classification.SLOP,
                    "Long-form video could not be verified as useful."
                )
            } else {
                stabilizeYouTubeDecision(packageName, raw)
            }
            android.util.Log.d(
                "FocusGuard",
                "Path B: Final decision ${finalDecision.classification}: ${finalDecision.reason}"
            )
            lastContent = updatedSignal.texts
            cachedDecision = finalDecision
            lastClassifiedPackage = packageName
            lastCacheKey = signal.signature + "|" + policy.key()
            applyIntervention(packageName, finalDecision, updatedSignal.texts)
            decisionApplied = true
        } catch (exception: kotlinx.coroutines.CancellationException) {
            throw exception
        } catch (exception: Exception) {
            android.util.Log.e("FocusGuard", "Path B OCR execution failed", exception)
        } finally {
            // The OcrPipeline unconditionally clears its in-flight marker and
            // cooldown timestamp in its own `finally`, so a failed or cancelled
            // frame can never wedge future scans. On failure we also drop the
            // decision cache so the next scan re-classifies instead of reusing a
            // stale decision; on success the cache must be preserved.
            if (!decisionApplied) resetOcrState()
        }
    }

    private fun resetOcrState() {
        lastCacheKey = ""
        lastClassifiedPackage = ""
    }

    private suspend fun recordScreenTime(packageName: String) {
        val nowElapsed = SystemClock.elapsedRealtime()
        if (lastUsagePackage == packageName && lastUsageTickElapsed > 0L) {
            val duration = (nowElapsed - lastUsageTickElapsed)
                .coerceIn(0L, SCAN_INTERVAL_MS * 2)
            if (duration > 0L) {
                val label = tracker.getAppLabel(packageName, this) ?: packageName
                FocusGuardApplication.database.screenTimeDao().insert(
                    ScreenTimeEvent(
                        appPackage = packageName,
                        appLabel = label,
                        durationMs = duration
                    )
                )
            }
        }
        lastUsagePackage = packageName
        lastUsageTickElapsed = nowElapsed
    }

    private suspend fun applyIntervention(
        packageName: String,
        decision: OnDeviceClassifier.Decision,
        content: List<String>
    ) {
        if (decision.classification != OnDeviceClassifier.Classification.SLOP) {
            finishSlopSession()
            return
        }
        if (overlayInFlight) return

        if (slopPackage != packageName || slopStartElapsed == 0L) {
            finishSlopSession()
            slopPackage = packageName
            slopStartElapsed = SystemClock.elapsedRealtime()
            activeSessionStartWall = System.currentTimeMillis()
            activeSessionLabel = tracker.getAppLabel(packageName, this) ?: packageName
            activeSessionSummary = content.joinToString(" ").take(500)
            FocusGuardApplication.database.relapseDao().insertRelapseEvent(
                RelapseEvent(
                    appPackage = packageName,
                    appLabel = activeSessionLabel,
                    screenTextSummary = activeSessionSummary
                )
            )
        }
        overlayInFlight = true
        sendWarning("Blocked $activeSessionLabel: ${decision.reason}")
        try {
            startActivity(
                Intent(this, OverlayActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(OverlayActivity.EXTRA_APP_LABEL, activeSessionLabel)
                    putExtra(OverlayActivity.EXTRA_REASON, decision.reason)
                }
            )
        } catch (exception: Exception) {
            // MIUI/HyperOS and ColorOS can suppress background activity starts.
            // Fall back to a system accessibility overlay window.
            android.util.Log.e("FocusGuard", "Could not show block overlay", exception)
            showBlockOverlayFallback(activeSessionLabel, decision.reason)
        }
    }

    private fun showBlockOverlayFallback(label: String, reason: String) {
        try {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            dismissBlockOverlayFallback()

            val root = FrameLayout(this).apply {
                setBackgroundColor(Color.argb(250, 7, 29, 25))
            }
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(64, 64, 64, 64)
            }
            content.addView(TextView(this).apply {
                text = "Content blocked"
                textSize = 32f
                gravity = Gravity.CENTER
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                setTextColor(Color.rgb(240, 243, 238))
            })
            content.addView(TextView(this).apply {
                text = label
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(99, 205, 189))
                setPadding(0, 24, 0, 24)
            })
            content.addView(TextView(this).apply {
                text = reason
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(240, 243, 238))
                setPadding(0, 0, 0, 28)
            })
            content.addView(Button(this).apply {
                text = "Go Home now"
                backgroundTintList = ColorStateList.valueOf(Color.rgb(99, 205, 189))
                setTextColor(Color.rgb(7, 29, 25))
                isAllCaps = false
                setOnClickListener { completeFallbackExit() }
            })
            root.addView(content)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            windowManager.addView(root, params)
            overlayWindowView = root

            fallbackExitRunnable?.let(handler::removeCallbacks)
            fallbackExitRunnable = Runnable { completeFallbackExit() }
            handler.postDelayed(fallbackExitRunnable!!, OVERLAY_FALLBACK_DURATION_MS)
        } catch (exception: Exception) {
            android.util.Log.e("FocusGuard", "Could not show block overlay", exception)
            overlayInFlight = false
            if (!performGlobalAction(GLOBAL_ACTION_HOME)) {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
            finishSlopSession()
        }
    }

    private fun dismissBlockOverlayFallback() {
        overlayWindowView?.let { view ->
            try {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view)
            } catch (_: Exception) {
            }
            overlayWindowView = null
        }
    }

    private fun completeFallbackExit() {
        if (!overlayInFlight) return
        overlayInFlight = false
        fallbackExitRunnable?.let(handler::removeCallbacks)
        fallbackExitRunnable = null
        dismissBlockOverlayFallback()
        val blockedPackage = slopPackage
        finishSlopSession()
        lastClassifiedPackage = ""
        lastCacheKey = ""
        lastContent = emptyList()
        exitBlockedApp(blockedPackage)
    }

    private fun finishSlopSession() {
        if (activeSessionStartWall > 0L) {
            val session = ScrollSession(
                startTime = activeSessionStartWall,
                endTime = System.currentTimeMillis(),
                appPackage = slopPackage,
                appLabel = activeSessionLabel,
                screenTextSummary = activeSessionSummary
            )
            scope.launch {
                FocusGuardApplication.database.relapseDao().insertScrollSession(session)
            }
        }
        slopPackage = ""
        slopStartElapsed = 0L
        activeSessionStartWall = 0L
        activeSessionLabel = ""
        activeSessionSummary = ""
    }

    private fun exitBlockedApp(blockedPackage: String) {
        if (blockedPackage.isBlank()) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }
        exitInProgress = true
        val backDelays = longArrayOf(250L, 500L, 750L, 1_000L, 1_250L, 1_500L)
        backDelays.forEach { delay ->
            handler.postDelayed({
                if (activePackageName() == blockedPackage) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
            }, delay)
        }
        handler.postDelayed({
            performGlobalAction(GLOBAL_ACTION_HOME)
            exitInProgress = false
            lastPackage = ""
            lastEventText = ""
            lastClassifiedPackage = ""
            lastCacheKey = ""
            lastContent = emptyList()
        }, 1_800L)
    }

    private fun activePackageName(): String =
        rootInActiveWindow?.packageName?.toString().orEmpty()

    private fun resetMonitoringState() {
        finishSlopSession()
        dismissBlockOverlayFallback()
        fallbackExitRunnable?.let(handler::removeCallbacks)
        fallbackExitRunnable = null
        lastPackage = ""
        lastEventText = ""
        lastClassifiedPackage = ""
        lastCacheKey = ""
        lastContent = emptyList()
        cachedDecision = OnDeviceClassifier.Decision(
            OnDeviceClassifier.Classification.ALLOWED,
            "No blocked content detected."
        )
        overlayInFlight = false
        youtubeSessionWasProductive = false
        lastUsagePackage = ""
        lastUsageTickElapsed = 0L
    }

    private fun extractSignal(
        root: AccessibilityNodeInfo,
        packageName: String
    ): ScreenSignal {
        val texts = mutableListOf<String>()
        val descriptions = mutableListOf<String>()
        val viewIds = mutableSetOf<String>()
        var largestPlayerWidth = 0
        var largestPlayerHeight = 0
        try {
            fun walk(node: AccessibilityNodeInfo, depth: Int) {
                if (depth > 6) return
                try {
                    node.viewIdResourceName
                        ?.takeIf { it.isNotBlank() }
                        ?.substringAfterLast('/')
                        ?.let { id ->
                            viewIds.add(id)
                            if (isPlayerLikeViewId(id)) {
                                val bounds = Rect()
                                node.getBoundsInScreen(bounds)
                                if (bounds.width() * bounds.height() >
                                    largestPlayerWidth * largestPlayerHeight
                                ) {
                                    largestPlayerWidth = bounds.width()
                                    largestPlayerHeight = bounds.height()
                                }
                            }
                        }
                    node.text?.toString()
                        ?.takeIf { it.length in 1..200 }
                        ?.let { texts.add(if (node.isSelected) "selected:$it" else it) }
                    node.contentDescription?.toString()
                        ?.takeIf { it.length in 1..200 }
                        ?.let { descriptions.add(if (node.isSelected) "selected:$it" else it) }
                    for (index in 0 until node.childCount) {
                        node.getChild(index)?.let { walk(it, depth + 1) }
                    }
                } catch (_: Exception) {
                }
            }
            walk(root, 0)
        } catch (_: Exception) {
        }

        val playerFullScreen = isFullScreenPlayer(largestPlayerWidth, largestPlayerHeight)
        if (classifier.isYouTubePackage(packageName)) {
            android.util.Log.d(
                "FocusGuard",
                "YouTube window: texts=$texts descriptions=$descriptions " +
                    "viewIds=$viewIds playerFullScreen=$playerFullScreen"
            )
        }

        val allTexts = (texts + descriptions)
            .distinct()
            .take(40)
        val contentTexts = if (allTexts.isEmpty() && lastEventText.isNotEmpty()) {
            listOf(lastEventText)
        } else {
            allTexts
        }
        return ScreenSignal(packageName, contentTexts, viewIds, playerFullScreen)
    }

    /**
     * Active Shorts containers whose bounds genuinely represent a full-screen
     * Shorts feed/player. Evaluated before the background-chrome exclusion so
     * `reel_recycler` is never filtered out by the generic "recycler" token.
     */
    private val shortsPlayerViewTokens = listOf(
        "reel_recycler", "reel_player", "reel_container", "reel_page", "reel_view",
        "shorts_reel", "shorts_player", "shorts_recycler", "shorts_container",
        "shorts_page", "shorts_feed", "shorts_immersive", "shorts_view"
    )

    /**
     * Background / auxiliary chrome that must NEVER contribute to full-screen
     * geometry. On some OEMs these report inflated bounds (they are full-window
     * containers that render a small bar inside), which would otherwise fake a
     * full-screen watch session from the home feed.
     */
    private val backgroundViewIdTokens = listOf(
        "status_bar", "mini_player", "time_bar", "progress", "shelf", "thumbnail",
        "preview", "tab", "bottom", "feed", "card", "chip", "tile", "drawer",
        "action_bar", "content", "more_drawer", "recycler", "list", "grid",
        "header", "menu"
    )

    /**
     * View ids whose node geometry can represent a REAL player. Only dedicated
     * foreground watch components count: a player/overlay/controls container or
     * an active Shorts container. Background chrome — mini-player bars, Shorts
     * progress bars, shelves, thumbnails, navigation — is never measured, so it
     * can neither set `playerFullScreen` nor be treated as an active session.
     */
    private fun isPlayerLikeViewId(id: String): Boolean {
        if (shortsPlayerViewTokens.any { id.contains(it) }) return true
        if (backgroundViewIdTokens.any { it in id }) return false
        return id.contains("player") || id.contains("overlay") || id.contains("controls")
    }

    /**
     * A player-like node occupying most of the screen proves a full-screen watch
     * session. This is the reliable transition signal: the watch player covers
     * the display while a background mini-player bar is only ~10% tall, so the
     * home feed can never be mistaken for a watch page even when residual home
     * chrome lingers in the tree after tapping a video.
     */
    private fun isFullScreenPlayer(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        val widthRatio = width.toFloat() / screenWidth
        val heightRatio = height.toFloat() / screenHeight
        return widthRatio >= FULL_SCREEN_WIDTH_RATIO &&
            heightRatio >= FULL_SCREEN_HEIGHT_RATIO
    }

    private fun shouldIgnorePackage(packageName: String): Boolean =
        (packageName.startsWith("com.android.") && packageName != "com.android.chrome") ||
            packageName == "com.sec.android.app.launcher" ||
            packageName == "com.sec.android.app.desktoplauncher" ||
            packageName == "com.google.android.gms" ||
            packageName == "com.coloros" ||
            packageName == "com.oplus.launcher" ||
            packageName == "com.oppo.launcher" ||
            packageName == "com.coloros.launcher" ||
            packageName == "com.oneplus.launcher" ||
            packageName == "com.miui.home" ||
            packageName == "com.huawei.android.launcher" ||
            packageName == "com.vivo.launcher" ||
            packageName == "com.flyme.launcher"

    private fun updateCurrentPackage(packageName: String) {
        if (packageName != lastPackage) {
            if (classifier.isYouTubePackage(lastPackage) &&
                !classifier.isYouTubePackage(packageName)
            ) {
                youtubeSessionWasProductive = false
            }
            lastEventText = ""
        }
        lastPackage = packageName
    }

    private fun sendWarning(message: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            2001,
            NotificationCompat.Builder(this, "warn")
                .setContentTitle("FocusGuard")
                .setContentText(message)
                .setSmallIcon(R.drawable.ic_focus_guard_icon)
                .setAutoCancel(true)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .build()
        )
    }

    private fun buildForegroundNotification(): android.app.Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, "fg")
            .setContentTitle("FocusGuard is running")
            .setContentText("Background focus protection service")
            .setSmallIcon(R.drawable.ic_focus_guard_icon)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun registerReceivers() {
        if (receiversRegistered) return
        receiversRegistered = true
        ContextCompat.registerReceiver(
            this,
            stopReceiver,
            IntentFilter(ACTION_STOP),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this,
            toggleReceiver,
            IntentFilter(ACTION_TOGGLE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this,
            overlayFinishedReceiver,
            IntentFilter(ACTION_OVERLAY_FINISHED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this,
            screenOnReceiver,
            IntentFilter(Intent.ACTION_SCREEN_ON),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }
}
