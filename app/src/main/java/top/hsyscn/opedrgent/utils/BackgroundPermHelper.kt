package top.hsyscn.opedrgent.utils

import android.app.Activity
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

object BackgroundPermHelper {

    data class PermStatus(
        val batteryOptOk: Boolean,
        val autostartAvailable: Boolean,
        val autostartGranted: Boolean,
        val overlayAvailable: Boolean,
        val overlayGranted: Boolean,
    )

    fun checkPermissions(context: Context): PermStatus {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryOptOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } else true

        val autostartAvailable = isAutostartAvailable()
        val autostartGranted = if (autostartAvailable) isAutostartGranted(context) else true

        val overlayAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
        val overlayGranted = if (overlayAvailable) Settings.canDrawOverlays(context) else true

        return PermStatus(
            batteryOptOk = batteryOptOk,
            autostartAvailable = autostartAvailable,
            autostartGranted = autostartGranted,
            overlayAvailable = overlayAvailable,
            overlayGranted = overlayGranted,
        )
    }

    fun requestBatteryOptimization(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivityForResult(intent, 1001)
        }
    }

    fun requestAutostart(activity: Activity) {
        val intents = listOf(
            Intent().apply {
                component = ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            },
            Intent().apply {
                component = ComponentName("com.oppo.coloros.safecenter", "com.oppo.coloros.safecenter.startupapp.StartupAppListActivity")
            },
            Intent().apply {
                component = ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
            },
            Intent().apply {
                component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
            },
            Intent().apply {
                component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
            },
            Intent().apply {
                component = ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")
            },
            Intent().apply {
                component = ComponentName("com.oneplus.security", "com.oneplus.chainlink.main.LauncherActivity")
            },
        )
        for (intent in intents) {
            if (intent.resolveActivity(activity.packageManager) != null) {
                try {
                    activity.startActivity(intent)
                    return
                } catch (_: Exception) { }
            }
        }
        try {
            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${activity.packageName}")
            })
        } catch (_: Exception) { }
    }

    fun requestOverlayPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${activity.packageName}"),
            )
            activity.startActivityForResult(intent, 1002)
        }
    }

    private fun isAutostartAvailable(): Boolean {
        val brands = listOf("xiaomi", "oppo", "vivo", "huawei", "samsung", "oneplus", "realme", "meizu")
        return brands.any { Build.MANUFACTURER.lowercase().contains(it) }
    }

    private fun isAutostartGranted(context: Context): Boolean {
        return try {
            val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = ops.checkOpNoThrow(
                "android:auto_start",
                android.os.Process.myUid(),
                context.packageName,
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            true
        }
    }
}
