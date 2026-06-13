package top.hsyscn.opedrgent

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import top.hsyscn.opedrgent.ui.AppRoot
import top.hsyscn.opedrgent.ui.theme.OpedrgentTheme

class MainActivity : ComponentActivity() {
    private val pendingShareText = mutableStateOf<String?>(null)
    private val pendingAction = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingShareText.value = extractSharedText(intent)
        pendingAction.value = extractAction(intent)
        setContent {
            OpedrgentTheme {
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
        return when (intent?.action) {
            "top.hsyscn.opedrgent.ACTION_MEETING_RECORD" -> "meeting"
            "top.hsyscn.opedrgent.ACTION_NEW_CHAT" -> "new_chat"
            else -> null
        }
    }
}
