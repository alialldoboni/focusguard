package com.focusguard.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "preferences")
data class Preferences(
    @PrimaryKey
    val key: String = "enabled",
    val value: Boolean = false,
    val warningThresholdMinutes: Int = 10,
    val overlayThresholdMinutes: Int = 15,
    val homeThresholdMinutes: Int = 20,
    val autoStart: Boolean = false
)
