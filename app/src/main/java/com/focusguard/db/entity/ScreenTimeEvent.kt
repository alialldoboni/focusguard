package com.focusguard.db.entity

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.PrimaryKey

@Entity(tableName = "screen_time_events")
data class ScreenTimeEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val appPackage: String,
    val appLabel: String,
    val timestamp: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    val durationMs: Long = 0
)
