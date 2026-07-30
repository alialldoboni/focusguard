package com.focusguard.db.dao

import androidx.room.*
import com.focusguard.db.entity.RelapseEvent
import com.focusguard.db.entity.ScrollSession
import kotlinx.coroutines.flow.Flow

@Dao
interface RelapseDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRelapseEvent(event: RelapseEvent): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScrollSession(session: ScrollSession): Long

    @Query("SELECT * FROM relapse_events ORDER BY timestamp DESC")
    fun getAllRelapses(): Flow<List<RelapseEvent>>

    @Query("SELECT * FROM scroll_sessions ORDER BY startTime DESC")
    fun getAllScrollSessions(): Flow<List<ScrollSession>>

    @Query("SELECT * FROM relapse_events ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentRelapses(limit: Int): List<RelapseEvent>

    @Query("SELECT * FROM scroll_sessions ORDER BY startTime DESC LIMIT :limit")
    suspend fun getRecentScrollSessions(limit: Int): List<ScrollSession>

    @Query("DELETE FROM relapse_events WHERE timestamp < :cutoffTimestamp")
    suspend fun deleteOldRelapses(cutoffTimestamp: Long): Int

    @Query("SELECT COUNT(*) FROM relapse_events WHERE timestamp BETWEEN :start AND :end")
    suspend fun getRelapseCountInRange(start: Long, end: Long): Int

    @Query("""
        SELECT strftime('%H', timestamp / 1000, 'unixepoch', 'localtime') AS hour, COUNT(*) AS count
        FROM relapse_events
        WHERE timestamp >= :weekStart
        GROUP BY hour
        ORDER BY hour
    """)
    suspend fun getHourlyDistribution(weekStart: Long): List<HourlyRelapseCount>
}

data class HourlyRelapseCount(
    @ColumnInfo(name = "hour") val hour: String,
    @ColumnInfo(name = "count") val count: Int
)
