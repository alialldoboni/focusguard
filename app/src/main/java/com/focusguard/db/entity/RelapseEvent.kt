package com.focusguard.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "relapse_events")
data class RelapseEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val appPackage: String,
    val appLabel: String,
    val screenTextSummary: String,
    val classification: String = "SLOP",
    val sessionId: String = java.util.UUID.randomUUID().toString()
)
