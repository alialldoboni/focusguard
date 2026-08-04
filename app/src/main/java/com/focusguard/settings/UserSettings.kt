package com.focusguard.settings

import android.content.Context
import android.content.SharedPreferences
import com.focusguard.ai.BlockingPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Feature toggles and the app/domain block lists, persisted to SharedPreferences.
 *
 * The accessibility service reads [currentPolicy] on every scan (synchronous and
 * always fresh), so UI changes take effect immediately without restarting the
 * service. The Compose UI observes [state] as a [StateFlow].
 */
data class UserSettingsState(
    val nsfwProtectionEnabled: Boolean = false,
    val shortFormBlockingEnabled: Boolean = true,
    val longFormBlockingEnabled: Boolean = true,
    val blockedApps: Set<String> = emptySet(),
    val blockedDomains: Set<String> = UserSettings.defaultBlockedDomains
) {
    fun toPolicy(): BlockingPolicy = BlockingPolicy(
        nsfwProtectionEnabled = nsfwProtectionEnabled,
        shortFormBlockingEnabled = shortFormBlockingEnabled,
        longFormBlockingEnabled = longFormBlockingEnabled,
        blockedApps = blockedApps,
        blockedDomains = blockedDomains
    )
}

class UserSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(readState())
    val state: StateFlow<UserSettingsState> = _state.asStateFlow()

    private val changeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            _state.value = readState()
        }

    init {
        prefs.registerOnSharedPreferenceChangeListener(changeListener)
    }

    fun currentPolicy(): BlockingPolicy = readState().toPolicy()

    fun currentState(): UserSettingsState = readState()

    fun setNsfwProtectionEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NSFW, enabled).apply()
    }

    fun setShortFormBlockingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHORT_FORM, enabled).apply()
    }

    fun setLongFormBlockingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LONG_FORM, enabled).apply()
    }

    fun setBlockedApp(packageName: String, blocked: Boolean) {
        val updated = currentBlockedApps().toMutableSet()
        if (blocked) updated.add(packageName) else updated.remove(packageName)
        prefs.edit().putStringSet(KEY_BLOCKED_APPS, updated).apply()
    }

    fun setBlockedDomain(domain: String, blocked: Boolean) {
        val updated = readState().blockedDomains.toMutableSet()
        if (blocked) updated.add(domain) else updated.remove(domain)
        prefs.edit().putStringSet(KEY_BLOCKED_DOMAINS, updated).apply()
    }

    private fun currentBlockedApps(): Set<String> =
        prefs.getStringSet(KEY_BLOCKED_APPS, emptySet()) ?: emptySet()

    private fun readState(): UserSettingsState = UserSettingsState(
        nsfwProtectionEnabled = prefs.getBoolean(KEY_NSFW, false),
        shortFormBlockingEnabled = prefs.getBoolean(KEY_SHORT_FORM, true),
        longFormBlockingEnabled = prefs.getBoolean(KEY_LONG_FORM, true),
        blockedApps = prefs.getStringSet(KEY_BLOCKED_APPS, emptySet()) ?: emptySet(),
        blockedDomains = prefs.getStringSet(KEY_BLOCKED_DOMAINS, defaultBlockedDomains)
            ?: defaultBlockedDomains
    )

    companion object {
        private const val PREFS_NAME = "focusguard_settings"
        private const val KEY_NSFW = "nsfw_protection"
        private const val KEY_SHORT_FORM = "short_form_blocking"
        private const val KEY_LONG_FORM = "long_form_blocking"
        private const val KEY_BLOCKED_APPS = "blocked_apps"
        private const val KEY_BLOCKED_DOMAINS = "blocked_domains"

        val defaultBlockedDomains: Set<String> = setOf(
            "pornhub.com", "xvideos.com", "xnxx.com", "xhamster.com", "redtube.com",
            "youporn.com", "onlyfans.com", "chaturbate.com", "stripchat.com",
            "hentaihaven.org", "brazzers.com", "bangbros.com"
        )
    }
}
