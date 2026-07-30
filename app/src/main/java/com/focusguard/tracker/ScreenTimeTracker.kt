package com.focusguard.tracker

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

class ScreenTimeTracker {

    fun getAppLabel(packageName: String, context: Context): String? {
        return try {
            val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    fun getWeeklyTopApps(events: List<com.focusguard.db.entity.ScreenTimeEvent>): List<AppUsage> {
        val now = System.currentTimeMillis()
        val weekAgo = now - (7 * 24 * 60 * 60 * 1000L)

        val weeklyEvents = events.filter { it.timestamp >= weekAgo }

        val appDuration = mutableMapOf<String, Long>()
        val appLabel = mutableMapOf<String, String>()

        for (event in weeklyEvents) {
            appDuration[event.appPackage] =
                appDuration.getOrDefault(event.appPackage, 0L) + event.durationMs
            appLabel[event.appPackage] = event.appLabel
        }

        return appDuration.entries
            .sortedByDescending { it.value }
            .take(10)
            .map { (pkg, durationMs) ->
                AppUsage(
                    packageName = pkg,
                    appLabel = appLabel[pkg] ?: pkg,
                    usageDurationMs = durationMs
                )
            }
    }

    fun getTotalScreenTimeToday(events: List<com.focusguard.db.entity.ScreenTimeEvent>): Long {
        val startOfDay = java.time.LocalDate.now()
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant()
            .epochSecond * 1000

        return events
            .filter { it.timestamp >= startOfDay }
            .sumOf { it.durationMs }
    }

    data class AppUsage(
        val packageName: String,
        val appLabel: String,
        val usageDurationMs: Long
    )
}
