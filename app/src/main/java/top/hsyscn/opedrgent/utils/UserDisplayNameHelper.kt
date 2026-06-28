package top.hsyscn.opedrgent.utils

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings

/**
 * 用户显示名称辅助类。
 *
 * 优先读取用户在应用内设置的昵称，其次尝试获取系统设备名称。
 * 所有获取操作均为一次性同步读取，不阻塞 UI。
 */
object UserDisplayNameHelper {
    private const val PREFS_NAME = "opedrgent_user"
    private const val KEY_NICKNAME = "user_nickname"

    /**
     * 获取用于首页问候的显示名称。
     *
     * @param context Context
     * @return 用户昵称或系统名称，获取失败时返回 null
     */
    @SuppressLint("MissingPermission", "HardwareIds")
    fun getDisplayName(context: Context): String? {
        val prefs = getPrefs(context)
        prefs.getString(KEY_NICKNAME, null)?.takeIf { it.isNotBlank() }?.let { return it }

        return getSystemName(context)?.takeIf { it.isNotBlank() && it != "Android" }
    }

    /**
     * 设置用户自定义昵称。
     */
    fun setNickname(context: Context, nickname: String) {
        getPrefs(context).edit().putString(KEY_NICKNAME, nickname.trim()).apply()
    }

    private fun getSystemName(context: Context): String? {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1 -> {
                Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
                    ?: Settings.System.getString(context.contentResolver, "device_name")
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 -> {
                @Suppress("DEPRECATION")
                Settings.System.getString(context.contentResolver, Settings.System.NAME)
            }
            else -> null
        } ?: runCatching { BluetoothAdapter.getDefaultAdapter()?.name }.getOrNull()
        ?: Build.MODEL
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
