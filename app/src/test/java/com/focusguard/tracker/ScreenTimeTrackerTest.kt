package com.focusguard.tracker

import com.focusguard.db.entity.ScreenTimeEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenTimeTrackerTest {
    private val tracker = ScreenTimeTracker()

    @Test
    fun totalScreenTimeSumsDurationsFromToday() {
        val now = System.currentTimeMillis()
        val events = listOf(
            event("app.one", now, 2_000),
            event("app.two", now, 3_000),
            event("app.old", now - 2 * 24 * 60 * 60 * 1_000L, 9_000)
        )

        assertEquals(5_000L, tracker.getTotalScreenTimeToday(events))
    }

    @Test
    fun weeklyTopAppsAreSortedByAccumulatedDuration() {
        val now = System.currentTimeMillis()
        val events = listOf(
            event("app.one", now, 1_000, "One"),
            event("app.two", now, 4_000, "Two"),
            event("app.one", now, 5_000, "One"),
            event("app.old", now - 8 * 24 * 60 * 60 * 1_000L, 20_000, "Old")
        )

        val result = tracker.getWeeklyTopApps(events)

        assertEquals(listOf("app.one", "app.two"), result.map { it.packageName })
        assertEquals(listOf(6_000L, 4_000L), result.map { it.usageDurationMs })
    }

    private fun event(
        packageName: String,
        timestamp: Long,
        durationMs: Long,
        label: String = packageName
    ) = ScreenTimeEvent(
        appPackage = packageName,
        appLabel = label,
        timestamp = timestamp,
        durationMs = durationMs
    )
}
