package com.focusguard.db.dao

import androidx.room.*
import com.focusguard.db.entity.ScreenTimeEvent
import kotlinx.coroutines.flow.Flow

@Dao
interface ScreenTimeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: ScreenTimeEvent): Long

    @Query("SELECT * FROM screen_time_events ORDER BY timestamp DESC")
    fun getAllEvents(): Flow<List<ScreenTimeEvent>>

    @Query("SELECT * FROM screen_time_events ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentEvents(limit: Int): List<ScreenTimeEvent>

    @Query("SELECT appPackage, appLabel, SUM(durationMs) as durationMs FROM screen_time_events WHERE timestamp >= :weekStart GROUP BY appPackage ORDER BY durationMs DESC")
    suspend fun getWeeklyTopApps(weekStart: Long): List<AppUsageCount>

    @Query("SELECT COALESCE(SUM(durationMs), 0) FROM screen_time_events WHERE timestamp >= :dayStart")
    suspend fun getTodayDurationMs(dayStart: Long): Long

    @Query("DELETE FROM screen_time_events WHERE timestamp < :cutoffTimestamp")
    suspend fun deleteOldEvents(cutoffTimestamp: Long): Int
}

data class AppUsageCount(
    @ColumnInfo(name = "appPackage") val appPackage: String,
    @ColumnInfo(name = "appLabel") val appLabel: String,
    @ColumnInfo(name = "durationMs") val durationMs: Long
)
