package com.focusguard.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.focusguard.FocusGuardApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ServiceLifecycleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val valid = action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == FocusAccessibilityService.ACTION_RESTART
        if (!valid) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val enabled = try {
                    FocusGuardApplication.database.preferencesDao()
                        .getEnabled() ?: false
                } catch (_: Exception) {
                    // Credential-encrypted storage is not available before the
                    // first unlock after boot; retry on the next boot event.
                    false
                }
                if (!enabled) return@launch
                startProtection(context)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun startProtection(context: Context) {
        try {
            context.startForegroundService(
                Intent(context, FocusAccessibilityService::class.java)
                    .setAction(FocusAccessibilityService.ACTION_RESTART)
            )
        } catch (_: Exception) {
        }
    }
}
