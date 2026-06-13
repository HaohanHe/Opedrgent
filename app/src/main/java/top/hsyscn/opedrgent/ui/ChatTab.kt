package top.hsyscn.opedrgent.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

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
                color = Color(0xFFE0E0E0),
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
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("选择一个会话开始对话", color = Color.Gray)
                    }
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
