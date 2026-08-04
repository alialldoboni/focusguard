package com.focusguard.worker

import android.accessibilityservice.AccessibilityManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.focusguard.FocusGuardApplication
import com.focusguard.MainActivity
import com.focusguard.R
import com.focusguard.service.FocusAccessibilityService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Periodically verifies that FocusGuard protection is actually alive.
 *
 * OEM skins silently disable accessibility services after a force-stop (Xiaomi MIUI/
 * HyperOS) or kill background processes even when a foreground service is running.
 * This worker detects both states and either restarts the service or tells the user
 * how to recover.
 */
class ProtectionWatchdogWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        val enabled = FocusGuardApplication.database.preferencesDao()
            .getEnabled() ?: false
        if (!enabled) return@withContext Result.success()

        val accessibilityConnected = isAccessibilityConnected(context)
        if (!accessibilityConnected) {
            postRecoveryNotification(context, REASON_ACCESSIBILITY_REVOKED)
            return@withContext Result.success()
        }

        try {
            context.startForegroundService(
                Intent(context, FocusAccessibilityService::class.java)
                    .setAction(FocusAccessibilityService.ACTION_RESTART)
            )
        } catch (_: Exception) {
            postRecoveryNotification(context, REASON_START_FAILED)
        }
        Result.success()
    }

    private fun isAccessibilityConnected(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
            as? AccessibilityManager ?: return false
        val expected = ComponentName(context, FocusAccessibilityService::class.java)
        return manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.id == expected.flattenToShortString() }
    }

    private fun postRecoveryNotification(context: Context, reason: String) {
        ensureChannel(context)
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val restartNow = PendingIntent.getService(
            context,
            1,
            Intent(context, FocusAccessibilityService::class.java)
                .setAction(FocusAccessibilityService.ACTION_RESTART),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, "warn")
            .setSmallIcon(R.drawable.ic_focus_guard_icon)
            .setContentTitle("FocusGuard protection stopped")
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setContentIntent(openApp)
            .addAction(0, "Restart now", restartNow)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager
        manager.notify(WATCHDOG_NOTIFICATION_ID, notification)
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager
        if (manager.getNotificationChannel("warn") == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    "warn",
                    "Warnings",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 250, 250, 250)
                }
            )
        }
    }

    companion object {
        private const val WATCHDOG_NOTIFICATION_ID = 3001
        private const val REASON_ACCESSIBILITY_REVOKED =
            "FocusGuard's accessibility access was turned off. Open FocusGuard and " +
                "re-enable accessibility to resume protection."
        private const val REASON_START_FAILED =
            "FocusGuard could not restart by itself. Open the app and re-enable protection. " +
                "On Xiaomi/HyperOS, Samsung, Oppo or Huawei, also allow autostart and " +
                "disable battery restrictions (see PowerGuard setup)."

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ProtectionWatchdogWorker>(
                15, TimeUnit.MINUTES
            ).setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "focusguard-watchdog",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
