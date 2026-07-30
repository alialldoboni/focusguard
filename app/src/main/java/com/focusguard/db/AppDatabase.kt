package com.focusguard.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.focusguard.db.dao.PreferencesDao
import com.focusguard.db.dao.RelapseDao
import com.focusguard.db.dao.ScreenTimeDao
import com.focusguard.db.entity.Preferences
import com.focusguard.db.entity.RelapseEvent
import com.focusguard.db.entity.ScreenTimeEvent
import com.focusguard.db.entity.ScrollSession

@Database(
    entities = [Preferences::class, RelapseEvent::class, ScreenTimeEvent::class, ScrollSession::class],
    version = 4,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun preferencesDao(): PreferencesDao
    abstract fun relapseDao(): RelapseDao
    abstract fun screenTimeDao(): ScreenTimeDao
}
