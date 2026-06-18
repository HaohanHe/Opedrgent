package top.hsyscn.opedrgent.utils

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.LocaleList
import java.util.Locale

object LocaleHelper {
    private const val PREFS_NAME = "opedrgent_locale"
    private const val KEY_LOCALE = "app_locale"
    private const val KEY_SYSTEM_LOCALE = "system_locale"

    /**
     * 在 Application.onCreate 或 MainActivity 最早期调用一次，
     * 保存真正的系统 locale（仅首次保存，后续不再覆盖）
     */
    fun captureSystemLocale(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_SYSTEM_LOCALE)) {
            val sysLocale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                LocaleList.getDefault().get(0)
            } else {
                Locale.getDefault()
            }
            prefs.edit().putString(KEY_SYSTEM_LOCALE, sysLocale.toLanguageTag()).apply()
        }
    }

    fun setLocale(context: Context, localeTag: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LOCALE, localeTag).apply()
    }

    fun onAttach(base: Context): Context {
        val prefs = base.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val localeTag = prefs.getString(KEY_LOCALE, "system") ?: "system"
        return updateResources(base, localeTag)
    }

    fun getCurrentLocale(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LOCALE, "system") ?: "system"
    }

    private fun updateResources(context: Context, localeTag: String): Context {
        val locale = if (localeTag == "system") {
            // 使用启动时保存的真正系统 locale，而非被 app 污染的 LocaleList.getDefault()
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedSysLocale = prefs.getString(KEY_SYSTEM_LOCALE, null)
            if (savedSysLocale != null) {
                Locale.forLanguageTag(savedSysLocale)
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    LocaleList.getDefault().get(0)
                } else {
                    Locale.getDefault()
                }
            }
        } else {
            Locale(localeTag)
        }

        Locale.setDefault(locale)
        val config = context.resources.configuration
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            config.setLocale(locale)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(locale))
        }
        @Suppress("DEPRECATION")
        return context.createConfigurationContext(config)
    }
}
