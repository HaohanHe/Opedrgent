package top.hsyscn.opedrgent

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.isSystemInDarkTheme
import top.hsyscn.opedrgent.settings.ApiSettings
import top.hsyscn.opedrgent.ui.AppRoot
import top.hsyscn.opedrgent.ui.theme.OpedrgentTheme
import top.hsyscn.opedrgent.utils.LocaleHelper

class MainActivity : ComponentActivity() {
    private val pendingShareText = mutableStateOf<String?>(null)
    private val pendingAction = mutableStateOf<String?>(null)

    override fun attachBaseContext(newBase: Context?) {
        newBase?.let { LocaleHelper.captureSystemLocale(it) }
        super.attachBaseContext(newBase?.let { LocaleHelper.onAttach(it) })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 导航栏颜色跟随“最终解析的暗黑状态”，而非仅设置项。
        // 跟随系统时读取当前 uiMode 配置，确保导航栏与 Compose 主题一致（修复暗黑模式下导航栏仍为浅色）。
        val themeMode = ApiSettings(this).getThemeMode()
        val nightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val systemDark = nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val darkTheme = when (themeMode) {
            "light" -> false
            "dark" -> true
            else -> systemDark
        }
        window.navigationBarColor = if (darkTheme) {
            android.graphics.Color.parseColor("#FF121212")
        } else {
            android.graphics.Color.parseColor("#FFF5F5F6")
        }
        pendingShareText.value = extractSharedText(intent)
        pendingAction.value = extractAction(intent)
        setContent {
            val dynamicColor = remember { ApiSettings(this).isDynamicColorEnabled() }
            OpedrgentTheme(darkTheme = darkTheme, dynamicColor = dynamicColor) {
                AppRoot(
                    initialShareText = pendingShareText.value,
                    initialAction = pendingAction.value,
                    onShareConsumed = { pendingShareText.value = null },
                    onActionConsumed = { pendingAction.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingShareText.value = extractSharedText(intent)
        pendingAction.value = extractAction(intent)
    }

    private fun extractSharedText(intent: Intent?): String? {
        if (intent == null) return null
        val action = intent.action
        if (action != Intent.ACTION_SEND) return null
        val type = intent.type ?: return null
        if (!type.startsWith("text/")) return null
        return intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
    }

    private fun extractAction(intent: Intent?): String? {
        return when {
            intent?.action == "top.hsyscn.opedrgent.ACTION_RECORD" -> "recording"
            intent?.action == "top.hsyscn.opedrgent.ACTION_NEW_CHAT" -> "new_chat"
            intent?.getBooleanExtra("start_recording", false) == true -> "recording"
            else -> null
        }
    }
}
