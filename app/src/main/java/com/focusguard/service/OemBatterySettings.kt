package com.focusguard.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.net.Uri

/**
 * Cross-OEM battery-saver and autostart settings helper.
 *
 * Aggressive battery management on Samsung One UI, Xiaomi MIUI/HyperOS, Oppo/Realme
 * ColorOS, OnePlus OxygenOS and Huawei EMUI/HarmonyOS kills background services even
 * when a foreground service is running. These intents deep-link the user into the
 * OEM-specific settings that keep FocusGuard alive. Every intent is resolve-guarded
 * and falls back to the standard Android battery-optimization screen.
 */
object OemBatterySettings {

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Standard whitelist screen for the current app (fastest user path). */
    fun requestIgnoreBatteryOptimizations(context: Context): Boolean {
        return launch(
            context,
            listOf(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            ),
            fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        )
    }

    /** OEM-specific screens (battery / autostart / background activity). */
    fun oemBatteryIntents(): List<Intent> {
        val m = Build.MANUFACTURER.lowercase()
        return when {
            m.contains("samsung") -> listOf(
                component("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity"),
                component("com.samsung.android.sm", "com.samsung.android.sm.battery.ui.BatteryActivity")
            )
            "xiaomi" in m || "redmi" in m || "poco" in m -> listOf(
                component("com.miui.powerkeeper", "com.miui.powerkeeper.ui.PowerKeeperActivity"),
                component("com.miui.securitycenter", "com.miui.permcenter.powercenter.ui.PowerSettingsActivity")
            )
            m.contains("huawei") || m.contains("honor") -> listOf(
                component("com.huawei.systemmanager", "com.huawei.systemmanager.power.ui.HwPowerManagerActivity"),
                component("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
            )
            m.contains("vivo") || m.contains("iqoo") -> listOf(
                component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
            )
            else -> emptyList()
        }
    }

    fun oemAutoStartIntents(): List<Intent> {
        val m = Build.MANUFACTURER.lowercase()
        return when {
            "xiaomi" in m || "redmi" in m || "poco" in m -> listOf(
                Intent("miui.intent.action.OP_AUTO_START").apply {
                    setComponent(
                        ComponentName(
                            "com.miui.securitycenter",
                            "com.miui.permcenter.autostart.AutoStartManagementActivity"
                        )
                    )
                },
                Intent("miui.intent.action.AUTO_START_MANAGER")
            )
            m.contains("oppo") || m.contains("realme") || m.contains("oneplus") -> listOf(
                component("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
                component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                component("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")
            )
            m.contains("huawei") || m.contains("honor") -> listOf(
                component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                component("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity")
            )
            m.contains("vivo") || m.contains("iqoo") -> listOf(
                component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
            )
            else -> emptyList()
        }
    }

    fun oemBackgroundActivityIntents(): List<Intent> {
        val m = Build.MANUFACTURER.lowercase()
        return when {
            m.contains("samsung") -> listOf(
                component("com.samsung.android.sm", "com.samsung.android.sm.ui.usage.UsageActivity"),
                component("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.AppDetailBatteryActivity")
            )
            "xiaomi" in m || "redmi" in m || "poco" in m -> listOf(
                Intent("miui.intent.action.OP_BACKGROUND_BLOCK").apply {
                    setComponent(
                        ComponentName(
                            "com.miui.securitycenter",
                            "com.miui.powercenter.ui.MiuiPowerSettingsActivity"
                        )
                    )
                }
            )
            m.contains("huawei") || m.contains("honor") -> listOf(
                component("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
            )
            else -> emptyList()
        }
    }

    private fun component(pkg: String, cls: String): Intent =
        Intent().setComponent(ComponentName(pkg, cls))

    fun firstResolvable(
        intents: List<Intent>,
        canResolve: (Intent) -> Boolean
    ): Intent? = intents.firstOrNull { canResolve(it) }

    /**
     * Launches the first resolvable OEM intent, otherwise the [fallback].
     * Returns true if an activity was started.
     */
    fun launch(
        context: Context,
        intents: List<Intent>,
        fallback: Intent
    ): Boolean {
        val target = firstResolvable(intents) { it.resolveActivity(context.packageManager) != null }
            ?: fallback
        return try {
            context.startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Generic launcher used by the PowerGuard setup screen. */
    fun launchSettings(context: Context, category: OemCategory): Boolean {
        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        return when (category) {
            OemCategory.BATTERY -> launch(context, oemBatteryIntents(), fallback)
            OemCategory.AUTOSTART -> launch(context, oemAutoStartIntents(), fallback)
            OemCategory.BACKGROUND -> launch(context, oemBackgroundActivityIntents(), fallback)
        }
    }

    enum class OemCategory { BATTERY, AUTOSTART, BACKGROUND }
}
