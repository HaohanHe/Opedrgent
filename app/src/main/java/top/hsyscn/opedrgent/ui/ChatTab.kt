package top.hsyscn.opedrgent.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState

/**
 * AI 对话 Tab。
 * 封装会话列表 + 会话聊天界面，管理 selectedSessionId 状态。
 */
@Composable
fun ChatTab(
    vm: MainViewModel,
    selectedSessionId: String?,
    onSessionSelected: (String) -> Unit,
    onSessionDeselected: () -> Unit,
    onOpenSubScreen: (String) -> Unit,
) {
    val state by vm.state.collectAsStateCompat()

    LaunchedEffect(selectedSessionId) {
        selectedSessionId?.let { vm.openSession(it) }
    }

    if (selectedSessionId == null) {
        SessionsScreen(
            vm = vm,
            onSelectSession = { id -> onSessionSelected(id) },
            onSearch = { /* 搜索已在搜索框内 */ },
        )
    } else {
        SessionScreen(
            vm = vm,
            sessionId = selectedSessionId,
            onOpenSettings = { /* 设置通过 Tab Bar 切换 */ },
            onOpenSubScreen = onOpenSubScreen,
            onBack = { onSessionDeselected() },
        )
    }
}
