package top.hsyscn.opedrgent.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.ui.components.EmptyStateView
import top.hsyscn.opedrgent.ui.theme.themeDividerColor

private val SessionListWidth = 320.dp

/**
 * AI 对话 Tab。
 * 竖屏：会话列表 ↔ 聊天界面 切换。
 * 横屏：左侧会话列表 + 右侧聊天界面 主-从布局。
 */
@Composable
fun ChatTab(
    vm: MainViewModel,
    selectedSessionId: String?,
    onSessionSelected: (String) -> Unit,
    onSessionDeselected: () -> Unit,
    onOpenSubScreen: (String) -> Unit,
    isLandscape: Boolean = false,
) {
    LaunchedEffect(selectedSessionId) {
        selectedSessionId?.let { vm.openSession(it) }
    }

    if (isLandscape) {
        // 横屏：主-从布局
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .width(SessionListWidth)
                    .fillMaxHeight(),
            ) {
                SessionsScreen(
                    vm = vm,
                    onSelectSession = { id -> onSessionSelected(id) },
                    onSearch = { },
                )
            }
            VerticalDivider(
                thickness = 1.dp,
                color = themeDividerColor(),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                if (selectedSessionId != null) {
                    SessionScreen(
                        vm = vm,
                        sessionId = selectedSessionId,
                        onOpenSettings = { },
                        onOpenSubScreen = onOpenSubScreen,
                        onBack = { onSessionDeselected() },
                    )
                } else {
                    val newSessionTitle = stringResource(R.string.chat_new_session_title)
                    EmptyStateView(
                        icon = {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(80.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                            )
                        },
                        title = stringResource(R.string.chat_empty_state_title),
                        subtitle = stringResource(R.string.chat_empty_state_subtitle),
                        actionLabel = stringResource(R.string.chat_empty_state_action),
                        onAction = { vm.createSessionAndNavigate(newSessionTitle) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    } else {
        // 竖屏：切换模式
        if (selectedSessionId == null) {
            SessionsScreen(
                vm = vm,
                onSelectSession = { id -> onSessionSelected(id) },
                onSearch = { },
            )
        } else {
            SessionScreen(
                vm = vm,
                sessionId = selectedSessionId,
                onOpenSettings = { },
                onOpenSubScreen = onOpenSubScreen,
                onBack = { onSessionDeselected() },
            )
        }
    }
}
