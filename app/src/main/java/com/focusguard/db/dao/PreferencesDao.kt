package com.focusguard.db.dao

import androidx.room.*
import com.focusguard.db.entity.Preferences

@Dao
interface PreferencesDao {

    @Query("SELECT * FROM preferences WHERE key = 'enabled' LIMIT 1")
    suspend fun getPreferences(): Preferences?

    @Query("SELECT value FROM preferences WHERE key = 'enabled' LIMIT 1")
    suspend fun getEnabled(): Boolean?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setPreferences(preferences: Preferences)

    @Query("UPDATE preferences SET value = :enabled WHERE key = 'enabled'")
    suspend fun setEnabled(enabled: Boolean)

    @Query("DELETE FROM preferences WHERE key = 'enabled'")
    suspend fun resetPreferences()
}
