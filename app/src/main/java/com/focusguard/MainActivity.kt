package com.focusguard

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.focusguard.db.entity.ScreenTimeEvent
import com.focusguard.service.FocusAccessibilityService
import com.focusguard.service.OemBatterySettings
import com.focusguard.settings.UserSettings
import com.focusguard.ui.theme.*
import com.focusguard.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var accessibilityEnabled by mutableStateOf(false)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        setContent {
            FocusGuardTheme {
                FocusGuardApp(
                    accessibilityEnabled = accessibilityEnabled,
                    onOpenAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    onOpenNotifications = ::openNotificationSettingsOrRequest
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        accessibilityEnabled = com.focusguard.service.FocusAccessibilityService.isEnabled(this)
    }

    private fun openNotificationSettingsOrRequest() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
        )
    }
}

@Composable
fun FocusGuardApp(
    accessibilityEnabled: Boolean,
    onOpenAccessibility: () -> Unit,
    onOpenNotifications: () -> Unit
) {
    val vm: MainViewModel = viewModel()
    var currentTab by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    var serviceEnabled by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        serviceEnabled = com.focusguard.FocusGuardApplication.database.preferencesDao().getEnabled() ?: false
    }

    Scaffold(
        containerColor = DarkBg,
        bottomBar = {
            Column {
                HorizontalDivider(color = ForestBorder)
                NavigationBar(containerColor = DarkBg, tonalElevation = 0.dp) {
                    NavigationBarItem(
                        icon = { Icon(if (currentTab == 0) Icons.Filled.Home else Icons.Outlined.Home, null) },
                        label = { Text("Today") },
                        selected = currentTab == 0,
                        onClick = { currentTab = 0 },
                        colors = editorialNavigationColors()
                    )
                    NavigationBarItem(
                        icon = { Icon(if (currentTab == 1) Icons.Filled.DateRange else Icons.Outlined.DateRange, null) },
                        label = { Text("Insights") },
                        selected = currentTab == 1,
                        onClick = { currentTab = 1 },
                        colors = editorialNavigationColors()
                    )
                    NavigationBarItem(
                        icon = { Icon(if (currentTab == 2) Icons.Filled.Settings else Icons.Outlined.Settings, null) },
                        label = { Text("Settings") },
                        selected = currentTab == 2,
                        onClick = { currentTab = 2 },
                        colors = editorialNavigationColors()
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (currentTab) {
                0 -> TodayTab(
                    serviceEnabled = serviceEnabled,
                    accessibilityEnabled = accessibilityEnabled,
                    vm = vm,
                    onToggle = {
                        when {
                            !accessibilityEnabled -> {
                                if (!serviceEnabled) {
                                    serviceEnabled = true
                                    scope.launch {
                                        FocusGuardApplication.database.preferencesDao()
                                            .setEnabled(true)
                                    }
                                }
                                onOpenAccessibility()
                            }
                            else -> {
                                serviceEnabled = !serviceEnabled
                                scope.launch {
                                    FocusGuardApplication.database.preferencesDao()
                                        .setEnabled(serviceEnabled)
                                }
                            }
                        }
                    }
                )
                1 -> InsightsTab(vm)
                2 -> SettingsTab(
                    onOpenAccessibility = onOpenAccessibility,
                    onOpenNotifications = onOpenNotifications
                )
            }
        }
    }
}

@Composable
fun TodayTab(
    serviceEnabled: Boolean,
    accessibilityEnabled: Boolean,
    vm: MainViewModel,
    onToggle: () -> Unit
) {
    val guardActive = serviceEnabled && accessibilityEnabled
    val todayMs by vm.todayScreenTimeMs.collectAsState()
    val weeklyRelapses by vm.weeklyRelapseCount.collectAsState()
    val scrollSessions by vm.scrollSessionCount.collectAsState()
    val topApps by vm.weeklyTopApps.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .padding(horizontal = 22.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(Modifier.height(28.dp))
        BrandHeader()
        Spacer(Modifier.height(30.dp))
        Eyebrow("TODAY")
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (guardActive) "Your attention,\nprotected." else "Put your focus\nback in control.",
            style = MaterialTheme.typography.headlineLarge,
            color = TextPrimary
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Here's what FocusGuard caught for you today.",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary
        )
        Spacer(Modifier.height(28.dp))

        // Hero: status ring + stats
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, if (guardActive) MintDeep else ForestBorder),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent)
        ) {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(listOf(MintDeep.copy(alpha = 0.35f), CardBg)),
                            RoundedCornerShape(20.dp)
                        )
                ) {
                    Row(
                        modifier = Modifier.padding(22.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusRing(active = guardActive, todayMs = todayMs)
                        Spacer(Modifier.width(20.dp))
                        Column {
                            Text(
                                text = when {
                                    guardActive -> "GUARD ACTIVE"
                                    serviceEnabled -> "SETUP REQUIRED"
                                    else -> "GUARD PAUSED"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (guardActive) Mint else WarmSand
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = when {
                                    guardActive -> "Protection is running"
                                    serviceEnabled -> "Finish accessibility setup"
                                    else -> "Protection is paused"
                                },
                                style = MaterialTheme.typography.titleLarge,
                                color = TextPrimary
                            )
                        }
                    }
                }
                HorizontalDivider(color = ForestBorder)
                Row(modifier = Modifier.padding(vertical = 20.dp)) {
                    StatTile(
                        label = "SCREEN TIME",
                        value = formatCompact(todayMs),
                        valueColor = if (guardActive) Mint else TextSecondary,
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        label = "BLOCKED",
                        value = weeklyRelapses.toString(),
                        valueColor = Coral,
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        label = "SCROLL",
                        value = scrollSessions.toString(),
                        valueColor = WarmSand,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onToggle,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (guardActive) ForestRaised else Mint,
                contentColor = if (guardActive) MintSoft else DeepForest
            ),
            border = if (guardActive) BorderStroke(1.dp, ForestBorder) else null,
            shape = RoundedCornerShape(9.dp),
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Text(
                when {
                    guardActive -> "Pause protection"
                    serviceEnabled -> "Open accessibility"
                    else -> "Enable protection"
                },
                style = MaterialTheme.typography.labelLarge
            )
        }

        Spacer(Modifier.height(34.dp))
        Eyebrow("TOP APPS THIS WEEK")
        Spacer(Modifier.height(14.dp))
        Card(
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, ForestBorder),
            colors = CardDefaults.cardColors(containerColor = CardBg)
        ) {
            Column(modifier = Modifier.padding(22.dp)) {
                if (topApps.isEmpty()) {
                    Text(
                        "No usage recorded this week yet.",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    val maxMs = topApps.maxOfOrNull { it.usageDurationMs }?.coerceAtLeast(1L) ?: 1L
                    topApps.take(5).forEach { app ->
                        AppUsageBar(app.appLabel, app.usageDurationMs, maxMs)
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun StatusRing(active: Boolean, todayMs: Long) {
    val goalMs = 90L * 60L * 1000L
    val fraction = (todayMs.toFloat() / goalMs).coerceIn(0f, 1f)
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(96.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 8.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = ForestRaised,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            drawArc(
                color = if (active) Mint else WarmSand,
                startAngle = -90f,
                sweepAngle = 360f * fraction,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(formatCompact(todayMs), color = TextPrimary, style = MaterialTheme.typography.titleLarge)
            Text("today", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, valueColor: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = valueColor, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(label, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun AppUsageBar(label: String, durationMs: Long, maxMs: Long) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = TextPrimary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(formatDuration(durationMs), color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(6.dp))
        val fraction = (durationMs.toFloat() / maxMs).coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(ForestRaised, CircleShape)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .background(Mint, CircleShape)
            )
        }
    }
}

@Composable
fun InsightsTab(vm: MainViewModel) {
    val events by vm.allScreenTimeEvents.collectAsState(initial = emptyList())
    val relapses by vm.recentRelapses.collectAsState(initial = emptyList())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .padding(horizontal = 22.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(Modifier.height(28.dp))
        BrandHeader()
        Spacer(Modifier.height(30.dp))
        Eyebrow("INSIGHTS")
        Spacer(Modifier.height(12.dp))
        Text(
            "Your focus history",
            style = MaterialTheme.typography.headlineMedium,
            color = TextPrimary
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "See where your time went and what FocusGuard intercepted.",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary
        )
        Spacer(Modifier.height(30.dp))

        Eyebrow("WEEKLY TREND")
        Spacer(Modifier.height(14.dp))
        Card(
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, ForestBorder),
            colors = CardDefaults.cardColors(containerColor = CardBg)
        ) {
            Column(modifier = Modifier.padding(22.dp)) {
                WeeklyTrendBars(events)
            }
        }

        Spacer(Modifier.height(30.dp))
        Eyebrow("INTERCEPTED SESSIONS")
        Spacer(Modifier.height(14.dp))
        Card(
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, ForestBorder),
            colors = CardDefaults.cardColors(containerColor = CardBg)
        ) {
            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
                if (relapses.isEmpty()) {
                    Text(
                        "No intercepted sessions yet. Distracting content will appear here.",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 18.dp)
                    )
                } else {
                    relapses.take(20).forEach { relapse ->
                        Row(
                            modifier = Modifier.padding(vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(Coral, CircleShape)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(relapse.appLabel, color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    relapse.screenTextSummary.take(80),
                                    color = TextSecondary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(formatDate(relapse.timestamp), color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                        }
                        HorizontalDivider(color = ForestBorder)
                    }
                }
            }
        }
        Spacer(Modifier.height(30.dp))
    }
}

private data class DayBucket(val label: String, val ms: Long)

@Composable
private fun WeeklyTrendBars(events: List<ScreenTimeEvent>) {
    val todayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().epochSecond * 1000
    val dayMs = 86_400_000L
    val buckets = (6 downTo 0).map { off ->
        val start = todayStart - off * dayMs
        DayBucket(
            label = SimpleDateFormat("EEE", Locale.US).format(Date(start)),
            ms = events.filter { it.timestamp in start until start + dayMs }.sumOf { it.durationMs }
        )
    }
    val maxMs = buckets.maxOfOrNull { it.ms }?.coerceAtLeast(1L) ?: 1L
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        buckets.forEach { bucket ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(formatCompact(bucket.ms), color = TextSecondary, fontSize = 10.sp)
                Spacer(Modifier.height(6.dp))
                val h = ((bucket.ms.toFloat() / maxMs) * 120f).coerceAtLeast(4f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(h.dp)
                        .background(Mint, RoundedCornerShape(4.dp))
                )
                Spacer(Modifier.height(6.dp))
                Text(bucket.label, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val minutes = ms / 60_000L
    if (minutes < 60L) return "${minutes}m"
    return "${minutes / 60}h ${minutes % 60}m"
}

private fun formatCompact(ms: Long): String {
    val minutes = ms / 60_000L
    if (minutes < 60L) return "${minutes}m"
    if (minutes % 60L == 0L) return "${minutes / 60}h"
    return "${minutes / 60}h${minutes % 60}m"
}

private fun formatDate(timestamp: Long): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(timestamp))

@Composable
fun SettingsTab(
    onOpenAccessibility: () -> Unit, onOpenNotifications: () -> Unit
) {
    val settings = FocusGuardApplication.userSettings
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .padding(horizontal = 22.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(Modifier.height(28.dp))
        BrandHeader()
        Spacer(Modifier.height(38.dp))
        Eyebrow("CONTROL PANEL")
        Spacer(Modifier.height(12.dp))
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineMedium,
            color = TextPrimary
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Review the filtering policy and manage the access FocusGuard needs.",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary
        )
        Spacer(Modifier.height(30.dp))

        Eyebrow("BLOCKING POLICY")
        Spacer(Modifier.height(14.dp))
        Card(
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, ForestBorder),
            colors = CardDefaults.cardColors(containerColor = CardBg)
        ) {
            Column(modifier = Modifier.padding(22.dp)) {
                Text(
                    "Useful long-form content only.",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Social-media apps and short-form feeds are always blocked. " +
                        "Normal YouTube videos are allowed only when their visible title " +
                        "or description contains a clear educational or useful signal. " +
                        "If the title cannot be verified, strict mode blocks the video " +
                        "and explains why.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(Modifier.height(30.dp))

        Eyebrow("PERMISSIONS")
        Spacer(Modifier.height(14.dp))
        Card(
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, ForestBorder),
            colors = CardDefaults.cardColors(containerColor = DeepForest)
        ) {
            Column {
                PermButton("Accessibility", "Read screen content", Icons.Filled.Settings, onOpenAccessibility)
                HorizontalDivider(color = ForestBorder)
                PermButton("Notifications", "Send alerts", Icons.Filled.Notifications, onOpenNotifications)
            }
        }
        Spacer(Modifier.height(30.dp))

        ProtectionSettingsSection(settings)
        AppBlockListSection(settings)
        PowerGuardSection()
        StopYouTubeTipSection()
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun StopYouTubeTipSection() {
    Eyebrow("STOP YOUTUBE ENTIRELY")
    Spacer(Modifier.height(14.dp))
    Card(
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, ForestBorder),
        colors = CardDefaults.cardColors(containerColor = DeepForest)
    ) {
        Column(modifier = Modifier.padding(22.dp)) {
            Text(
                "When FocusGuard blocks a video, it kills the YouTube app so it " +
                    "can't keep playing in the background.",
                color = TextPrimary,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "To make sure the floating mini-window never reappears, turn off " +
                    "Picture-in-picture for YouTube:\n\n" +
                    "Settings → Apps → YouTube → Picture-in-picture → Off\n\n" +
                    "(On ReVanced, also turn off \"Background playback\" and " +
                    "\"Floating window\" in its settings.)",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
    Spacer(Modifier.height(30.dp))
}

@Composable
private fun ProtectionSettingsSection(settings: UserSettings) {
    val state by settings.state.collectAsState()

    Eyebrow("CONTENT FILTERS")
    Spacer(Modifier.height(14.dp))
    Card(
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, ForestBorder),
        colors = CardDefaults.cardColors(containerColor = CardBg)
    ) {
        Column(modifier = Modifier.padding(22.dp)) {
            ToggleRow(
                title = "NSFW & Adult Content Protection",
                subtitle = "Blocks adult websites and explicit keywords",
                checked = state.nsfwProtectionEnabled,
                onCheckedChange = settings::setNsfwProtectionEnabled
            )
            HorizontalDivider(color = ForestBorder)
            ToggleRow(
                title = "Short-Form Content Blockage",
                subtitle = "Blocks YouTube Shorts, Instagram Reels and TikTok",
                checked = state.shortFormBlockingEnabled,
                onCheckedChange = settings::setShortFormBlockingEnabled
            )
            HorizontalDivider(color = ForestBorder)
            ToggleRow(
                title = "Long-Form Non-Productive Blocking",
                subtitle = "Blocks non-useful YouTube videos while they play",
                checked = state.longFormBlockingEnabled,
                onCheckedChange = settings::setLongFormBlockingEnabled
            )
        }
    }
    Spacer(Modifier.height(30.dp))
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextPrimary, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Mint,
                checkedThumbColor = DeepForest,
                uncheckedTrackColor = ForestBorder,
                uncheckedThumbColor = TextSecondary
            )
        )
    }
}

private data class AppEntry(val packageName: String, val label: String)

private fun loadLaunchableApps(context: Context): List<AppEntry> {
    val packageManager = context.packageManager
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return packageManager.queryIntentActivities(launcherIntent, 0)
        .map { info ->
            val label = try {
                info.activityInfo.loadLabel(packageManager)?.toString()
                    ?: info.activityInfo.packageName
            } catch (_: Exception) {
                info.activityInfo.packageName
            }
            AppEntry(info.activityInfo.packageName, label)
        }
        .filter { it.packageName != context.packageName }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

@Composable
private fun AppBlockListSection(settings: UserSettings) {
    val context = LocalContext.current
    val state by settings.state.collectAsState()
    val apps = remember { loadLaunchableApps(context) }
    var query by remember { mutableStateOf("") }

    Eyebrow("APP & GAME BLOCKER")
    Spacer(Modifier.height(14.dp))
    Card(
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, ForestBorder),
        colors = CardDefaults.cardColors(containerColor = CardBg)
    ) {
        Column(modifier = Modifier.padding(22.dp)) {
            Text(
                "Blocked apps & games",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Select the apps and games FocusGuard should block. Social-media apps " +
                    "are always blocked and don't appear here.",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Search apps & games", color = TextSecondary) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Mint,
                    unfocusedBorderColor = ForestBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = Mint
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            val filtered = if (query.isBlank()) {
                apps
            } else {
                apps.filter {
                    it.label.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (filtered.isEmpty()) {
                    Text(
                        "No apps found.",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    filtered.forEach { app ->
                        AppCheckRow(
                            app = app,
                            checked = state.blockedApps.contains(app.packageName),
                            onCheckedChange = { settings.setBlockedApp(app.packageName, it) }
                        )
                        HorizontalDivider(color = ForestBorder)
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(30.dp))
}

@Composable
private fun AppCheckRow(
    app: AppEntry,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(app.label, color = TextPrimary, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                app.packageName,
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = Mint,
                uncheckedColor = ForestBorder,
                checkmarkColor = DeepForest
            )
        )
    }
}

@Composable
private fun PowerGuardSection() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var batteryIgnored by remember { mutableStateOf(false) }
    val autoStartIntents = remember { OemBatterySettings.oemAutoStartIntents() }
    val backgroundIntents = remember { OemBatterySettings.oemBackgroundActivityIntents() }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryIgnored = OemBatterySettings.isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        batteryIgnored = OemBatterySettings.isIgnoringBatteryOptimizations(context)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Eyebrow("POWERGUARD SETUP")
    Spacer(Modifier.height(14.dp))
    Card(
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, ForestBorder),
        colors = CardDefaults.cardColors(containerColor = CardBg)
    ) {
        Column(modifier = Modifier.padding(22.dp)) {
            Text(
                "Keep FocusGuard alive in the background",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Samsung, Xiaomi, Oppo and Huawei can stop background services to save battery. " +
                    "Adjust these settings so protection keeps running.",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(18.dp))

            PowerRow(
                title = "Battery optimization",
                subtitle = if (batteryIgnored) "FocusGuard is exempt" else "Restricted — tap to exempt",
                statusColor = if (batteryIgnored) Mint else WarmSand,
                onClick = { OemBatterySettings.requestIgnoreBatteryOptimizations(context) }
            )
            if (autoStartIntents.isNotEmpty()) {
                HorizontalDivider(color = ForestBorder)
                PowerRow(
                    title = "Autostart",
                    subtitle = "Allow FocusGuard to launch with your device",
                    onClick = {
                        OemBatterySettings.launchSettings(
                            context,
                            OemBatterySettings.OemCategory.AUTOSTART
                        )
                    }
                )
            }
            if (backgroundIntents.isNotEmpty()) {
                HorizontalDivider(color = ForestBorder)
                PowerRow(
                    title = "Background activity",
                    subtitle = "Prevent battery saver from stopping FocusGuard",
                    onClick = {
                        OemBatterySettings.launchSettings(
                            context,
                            OemBatterySettings.OemCategory.BACKGROUND
                        )
                    }
                )
            }
            HorizontalDivider(color = ForestBorder)
            PowerRow(
                title = "Restart protection",
                subtitle = "Restart the background monitoring service now",
                onClick = { restartProtection(context) }
            )
        }
    }
}

@Composable
private fun PowerRow(
    title: String,
    subtitle: String,
    statusColor: Color = TextSecondary,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextPrimary, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = statusColor, style = MaterialTheme.typography.bodyMedium)
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = TextSecondary)
    }
}

private fun restartProtection(context: Context) {
    try {
        context.startForegroundService(
            Intent(context, FocusAccessibilityService::class.java)
                .setAction(FocusAccessibilityService.ACTION_RESTART)
        )
    } catch (_: Exception) {
    }
}

@Composable
fun PermButton(title: String, desc: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Mint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextPrimary, style = MaterialTheme.typography.titleMedium)
            Text(desc, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = TextSecondary)
    }
}

@Composable
private fun BrandHeader() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.size(42.dp),
            shape = RoundedCornerShape(11.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, Mint)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = Mint,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                "FocusGuard",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary
            )
            Text(
                "PRIVATE • ON-DEVICE",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun Eyebrow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = Mint
    )
}

@Composable
private fun editorialNavigationColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = Mint,
    selectedTextColor = Mint,
    indicatorColor = ForestRaised,
    unselectedIconColor = TextSecondary,
    unselectedTextColor = TextSecondary
)
