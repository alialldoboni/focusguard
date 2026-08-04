package com.focusguard

import android.app.Application
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.focusguard.db.AppDatabase
import com.focusguard.worker.ProtectionWatchdogWorker

class FocusGuardApplication : Application() {

    private val _appDatabase: AppDatabase by lazy {
        Room.databaseBuilder(
            this,
            AppDatabase::class.java,
            "focusguard_database"
        )
            .addMigrations(MIGRATION_3_4)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        ProtectionWatchdogWorker.schedule(this)
    }

    companion object {
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE screen_time_events " +
                        "ADD COLUMN durationMs INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        lateinit var instance: FocusGuardApplication
            private set
        val database: AppDatabase
            get() = instance._appDatabase
    }
}
