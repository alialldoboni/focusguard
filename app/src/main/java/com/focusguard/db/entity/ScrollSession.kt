package com.focusguard.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scroll_sessions")
data class ScrollSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val startTime: Long = System.currentTimeMillis(),
    val endTime: Long = 0,
    val appPackage: String,
    val appLabel: String,
    val screenTextSummary: String,
    val classification: String = "SLOP"
)
