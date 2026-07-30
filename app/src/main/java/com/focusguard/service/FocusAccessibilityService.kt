package com.focusguard.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.focusguard.FocusGuardApplication
import com.focusguard.MainActivity
import com.focusguard.R
import com.focusguard.ai.OnDeviceClassifier
import com.focusguard.db.entity.Preferences
import com.focusguard.db.entity.RelapseEvent
import com.focusguard.db.entity.ScreenTimeEvent
import com.focusguard.db.entity.ScrollSession
import com.focusguard.tracker.ScreenTimeTracker
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FocusAccessibilityService : AccessibilityService() {

    private val serviceJob = Job()
    private val scope = CoroutineScope(Dispatchers.Main + serviceJob)
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val classifier = OnDeviceClassifier()
    private val tracker = ScreenTimeTracker()
    private val ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val screenshotExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var scanRunnable: Runnable? = null
    private var lastPackage = ""
    private var lastEventText = ""
    private var lastClassifiedPackage = ""
    private var lastContent = emptyList<String>()
    private var cachedDecision = OnDeviceClassifier.Decision(
        OnDeviceClassifier.Classification.ALLOWED,
        "No blocked content detected."
    )
    private var ocrPendingPackage = ""
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
            lastContent = emptyList()
            exitBlockedApp(blockedPackage)
        }
    }

    companion object {
        const val ACTION_STOP = "com.focusguard.STOP"
        const val ACTION_TOGGLE = "com.focusguard.TOGGLE"
        const val ACTION_OVERLAY_FINISHED = "com.focusguard.OVERLAY_FINISHED"
        private const val SCAN_INTERVAL_MS = 5_000L

        fun isEnabled(context: Context): Boolean =
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )?.contains(context.packageName) == true
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
        startForeground(
            1001,
            buildForegroundNotification()
        )
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
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            event.text
                ?.mapNotNull { it?.toString() }
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString(" ")
                ?.let { lastEventText = it }
        }
    }

    override fun onInterrupt() {
        stopScanning()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopScanning()
        finishSlopSession()
        serviceJob.cancel()
        screenshotExecutor.shutdownNow()
        ocr.close()
        try {
            unregisterReceiver(stopReceiver)
            unregisterReceiver(toggleReceiver)
            unregisterReceiver(overlayFinishedReceiver)
        } catch (_: Exception) {
        }
        return super.onUnbind(intent)
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
        scanRunnable = object : Runnable {
            override fun run() {
                scope.launch { scanOnce() }
                handler.postDelayed(this, SCAN_INTERVAL_MS)
            }
        }
        handler.post(scanRunnable!!)
    }

    private fun stopScanning() {
        scanRunnable?.let(handler::removeCallbacks)
        scanRunnable = null
    }

    private suspend fun scanOnce() {
        val preferences = FocusGuardApplication.database.preferencesDao().getPreferences()
            ?: Preferences()
        if (!preferences.value) {
            resetMonitoringState()
            return
        }
        if (exitInProgress) return

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

        val nodes = root?.let { extractText(it, 0, 3) }.orEmpty()
        val content = if (nodes.isEmpty() && lastEventText.isNotEmpty()) {
            listOf(lastEventText)
        } else {
            nodes
        }

        if (packageName == lastClassifiedPackage && content == lastContent) {
            applyIntervention(packageName, cachedDecision, content)
            return
        }

        lastClassifiedPackage = packageName
        lastContent = content
        if (classifier.isAlwaysBlockedPackage(packageName)) {
            classifyAndApply(packageName, content)
        } else if (content.isNotEmpty() && content.joinToString(" ") != "Device locked") {
            classifyAndApply(packageName, content)
        } else {
            requestOcrClassification(packageName, content)
        }
    }

    private suspend fun classifyAndApply(
        packageName: String,
        content: List<String>,
        fromOcr: Boolean = false
    ) {
        val rawDecision = withContext(Dispatchers.Default) {
            classifier.decide(packageName, content)
        }
        android.util.Log.d(
            "FocusGuardDecision",
            "$packageName -> ${rawDecision.classification}: ${rawDecision.reason}"
        )
        if (rawDecision.needsMoreText && !fromOcr) {
            requestOcrClassification(packageName, content)
            return
        }
        val decision = stabilizeYouTubeDecision(packageName, rawDecision)
        cachedDecision = decision
        applyIntervention(packageName, decision, content)
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
                "The previously verified useful YouTube video remains allowed while " +
                    "YouTube temporarily hides its title."
            )
        }
        if (decision.classification == OnDeviceClassifier.Classification.SLOP) {
            youtubeSessionWasProductive = false
        }
        return decision
    }

    private fun requestOcrClassification(
        packageName: String,
        fallbackContent: List<String>
    ) {
        if (ocrPendingPackage == packageName) return
        ocrPendingPackage = packageName
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                screenshotExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        var bufferClosed = false
                        try {
                            val hardwareBitmap = Bitmap.wrapHardwareBuffer(
                                result.hardwareBuffer,
                                result.colorSpace
                            )
                            val bitmap = hardwareBitmap?.copy(
                                Bitmap.Config.ARGB_8888,
                                false
                            )
                            hardwareBitmap?.recycle()
                            result.hardwareBuffer.close()
                            bufferClosed = true
                            if (bitmap != null) {
                                ocr.process(InputImage.fromBitmap(bitmap, 0))
                                    .addOnSuccessListener { recognized ->
                                        bitmap.recycle()
                                        completeOcrClassification(
                                            packageName,
                                            fallbackContent,
                                            recognized.text
                                        )
                                    }
                                    .addOnFailureListener {
                                        bitmap.recycle()
                                        completeOcrClassification(
                                            packageName,
                                            fallbackContent,
                                            ""
                                        )
                                    }
                            } else {
                                completeOcrClassification(
                                    packageName,
                                    fallbackContent,
                                    ""
                                )
                            }
                        } catch (exception: Exception) {
                            if (!bufferClosed) result.hardwareBuffer.close()
                            android.util.Log.w("FocusGuard", "OCR setup failed", exception)
                            completeOcrClassification(packageName, fallbackContent, "")
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        android.util.Log.w("FocusGuard", "Screenshot failed: $errorCode")
                        completeOcrClassification(packageName, fallbackContent, "")
                    }
                }
            )
        } catch (exception: Exception) {
            android.util.Log.w("FocusGuard", "Screenshot failed", exception)
            completeOcrClassification(packageName, fallbackContent, "")
        }
    }

    private fun completeOcrClassification(
        packageName: String,
        fallbackContent: List<String>,
        recognizedText: String
    ) {
        scope.launch {
            ocrPendingPackage = ""
            val enabled = FocusGuardApplication.database.preferencesDao()
                .getEnabled() ?: false
            if (!enabled || packageName != lastPackage) return@launch
            val content = recognizedText
                .takeIf { it.isNotBlank() && it != "Device locked" }
                ?.let(::listOf)
                ?: fallbackContent
            lastContent = content
            classifyAndApply(packageName, content, fromOcr = true)
        }
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
            android.util.Log.e("FocusGuard", "Could not show block overlay", exception)
            overlayInFlight = false
            if (!performGlobalAction(GLOBAL_ACTION_HOME)) {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
            finishSlopSession()
        }
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
            lastContent = emptyList()
        }, 1_800L)
    }

    private fun activePackageName(): String =
        rootInActiveWindow?.packageName?.toString().orEmpty()

    private fun resetMonitoringState() {
        finishSlopSession()
        lastPackage = ""
        lastEventText = ""
        lastClassifiedPackage = ""
        lastContent = emptyList()
        cachedDecision = OnDeviceClassifier.Decision(
            OnDeviceClassifier.Classification.ALLOWED,
            "No blocked content detected."
        )
        ocrPendingPackage = ""
        overlayInFlight = false
        youtubeSessionWasProductive = false
        lastUsagePackage = ""
        lastUsageTickElapsed = 0L
    }

    private fun extractText(
        node: AccessibilityNodeInfo,
        depth: Int,
        maxDepth: Int
    ): List<String> {
        if (depth > maxDepth) return emptyList()
        val text = mutableListOf<String>()
        try {
            fun addNodeText(value: CharSequence?) {
                value?.toString()
                    ?.takeIf { it.length in 1..200 }
                    ?.let {
                        text.add(if (node.isSelected) "selected:$it" else it)
                    }
            }
            addNodeText(node.text)
            addNodeText(node.contentDescription)
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let {
                    text.addAll(extractText(it, depth + 1, maxDepth))
                }
            }
        } catch (_: Exception) {
        }
        return text.distinct().take(30)
    }

    private fun shouldIgnorePackage(packageName: String): Boolean =
        packageName.startsWith("com.android.") ||
            packageName == "com.sec.android.app.launcher" ||
            packageName == "com.sec.android.app.desktoplauncher" ||
            packageName == "com.google.android.gms" ||
            packageName == "com.coloros" ||
            packageName == "com.oppo" ||
            packageName == "com.oneplus"

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
    }
}
