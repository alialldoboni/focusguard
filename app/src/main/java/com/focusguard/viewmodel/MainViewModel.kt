package com.focusguard.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.focusguard.FocusGuardApplication
import com.focusguard.db.entity.RelapseEvent
import com.focusguard.db.entity.ScreenTimeEvent
import com.focusguard.tracker.ScreenTimeTracker
import kotlinx.coroutines.flow.*

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val screenTimeTracker = ScreenTimeTracker()

    val allRelapses: Flow<List<RelapseEvent>> =
        FocusGuardApplication.database.relapseDao().getAllRelapses()

    val allScreenTimeEvents: Flow<List<ScreenTimeEvent>> =
        FocusGuardApplication.database.screenTimeDao().getAllEvents()

    val weeklyTopApps: StateFlow<List<com.focusguard.tracker.ScreenTimeTracker.AppUsage>> =
        allScreenTimeEvents.map { events ->
            screenTimeTracker.getWeeklyTopApps(events)
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val recentRelapses: StateFlow<List<RelapseEvent>> =
        allRelapses.map { relapses ->
            relapses.take(50)
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val scrollSessions: StateFlow<List<com.focusguard.db.entity.ScrollSession>> =
        FocusGuardApplication.database.relapseDao().getAllScrollSessions()
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val todayScreenTimeMs: StateFlow<Long> =
        allScreenTimeEvents.map(screenTimeTracker::getTotalScreenTimeToday)
            .stateIn(viewModelScope, SharingStarted.Lazily, 0L)

    val weeklyRelapseCount: StateFlow<Int> =
        allRelapses.map { relapses ->
            val weekStart = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
            relapses.count { it.timestamp >= weekStart }
        }.stateIn(viewModelScope, SharingStarted.Lazily, 0)

    val scrollSessionCount: StateFlow<Int> =
        scrollSessions.map { it.size }
            .stateIn(viewModelScope, SharingStarted.Lazily, 0)
}
