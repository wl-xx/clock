package com.example.pinkschedule.reminder

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/** 各系统设置页跳转（权限授权、通知渠道、电池优化、各厂商自启动管理）。 */
object SystemSettingsNavigator {
    private const val TAG = "SystemSettingsNavigator"

    fun openExactAlarmPermissionSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return false
        }
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val canHandle = intent.resolveActivity(context.packageManager) != null
        if (canHandle) {
            context.startActivity(intent)
        }
        return canHandle
    }

    fun openAlarmNotificationChannelSettings(context: Context): Boolean {
        SystemAlarmScheduler.ensureAlarmNotificationChannel(context)
        return openAppNotificationSettings(context)
    }

    fun openAppNotificationSettings(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val canHandle = intent.resolveActivity(context.packageManager) != null
        if (canHandle) {
            val launched = runCatching {
                context.startActivity(intent)
            }.onFailure {
                Log.d(TAG, "app notification settings intent failed", it)
            }.isSuccess
            if (launched) return true
        }
        return openAppDetailsSettings(context)
    }

    fun openBatteryOptimizationSettings(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val canHandle = intent.resolveActivity(context.packageManager) != null
        if (canHandle) {
            context.startActivity(intent)
        }
        return canHandle
    }

    /** 打开各主流 ROM 的自启动/后台启动管理页，失败时回退到本应用详情页。 */
    fun openAutoStartSettings(context: Context): Boolean {
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        val candidates = buildList {
            when {
                manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> {
                    add(componentIntent("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
                    add(componentIntent("com.miui.securitycenter", "com.miui.powercenter.PowerSettings"))
                }
                manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus") -> {
                    add(componentIntent("com.oplus.battery", "com.oplus.startupapp.view.StartupAppListActivity"))
                    add(componentIntent("com.oplus.battery", "com.oplus.startupapp.view.OptimizationAutoStartActivity"))
                    add(componentIntent("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"))
                    add(componentIntent("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"))
                    add(componentIntent("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"))
                    add(componentIntent("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"))
                    add(componentIntent("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"))
                    add(componentIntent("com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"))
                }
                manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> {
                    add(componentIntent("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"))
                    add(componentIntent("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity"))
                    add(componentIntent("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"))
                    add(componentIntent("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"))
                }
                manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
                    add(componentIntent("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))
                    add(componentIntent("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"))
                    add(componentIntent("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"))
                }
                manufacturer.contains("meizu") -> {
                    add(componentIntent("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"))
                    add(componentIntent("com.meizu.safe", "com.meizu.safe.security.HomeActivity"))
                }
                manufacturer.contains("asus") -> {
                    add(componentIntent("com.asus.mobilemanager", "com.asus.mobilemanager.entry.FunctionActivity"))
                    add(componentIntent("com.asus.mobilemanager", "com.asus.mobilemanager.MainActivity"))
                }
                manufacturer.contains("samsung") -> {
                    add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
            addAll(commonAutoStartIntents())
        }
        if (launchFirstAvailable(context, candidates)) return true
        return openAppDetailsSettings(context)
    }

    /** 打开本应用的系统“应用详情”页（自启动、后台运行、耗电管理等入口通常在此页内）。 */
    fun openAppDetailsSettings(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val canHandle = intent.resolveActivity(context.packageManager) != null
        if (canHandle) {
            context.startActivity(intent)
        }
        return canHandle
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true
        }
    }

    private fun componentIntent(packageName: String, className: String): Intent {
        return Intent().apply {
            setClassName(packageName, className)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun commonAutoStartIntents(): List<Intent> {
        return listOf(
            componentIntent("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            componentIntent("com.oplus.battery", "com.oplus.startupapp.view.StartupAppListActivity"),
            componentIntent("com.oplus.battery", "com.oplus.startupapp.view.OptimizationAutoStartActivity"),
            componentIntent("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
            componentIntent("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            componentIntent("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            componentIntent("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            componentIntent("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity")
        )
    }

    private fun launchFirstAvailable(context: Context, intents: List<Intent>): Boolean {
        intents.forEach { intent ->
            val launched = runCatching {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }.onFailure {
                Log.d(TAG, "auto-start settings intent failed: ${intent.component ?: intent.action}", it)
            }.isSuccess
            if (launched) return true
        }
        return false
    }
}
