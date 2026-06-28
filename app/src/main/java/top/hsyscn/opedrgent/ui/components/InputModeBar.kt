package top.hsyscn.opedrgent.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import top.hsyscn.opedrgent.ui.theme.SpacingTokens
import top.hsyscn.opedrgent.ui.theme.themeTextGrey

enum class InputMode(val label: String, val icon: ImageVector) {
    CHAT("对话", Icons.Default.ChatBubble),
    DEEP_RESEARCH("深度研究", Icons.Default.Search),
    VOICE("语音", Icons.Default.Mic),
}

/** 搜索范围：控制 LLM 对话时的知识来源 */
enum class SearchScope(val label: String) {
    ALL("全部"),           // 我的笔记 + 全网搜索
    MY_NOTES("我的笔记"),  // 仅海马体索引（笔记/发芽/对话/录音）
    WEB_ONLY("全网"),       // 仅搜索引擎，不注入本地记忆
}

@Composable
fun SearchScopeChips(
    currentScope: SearchScope,
    onScopeChange: (SearchScope) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.xs),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(horizontal = SpacingTokens.xs),
    ) {
        SearchScope.entries.forEach { scope ->
            val isSelected = scope == currentScope
            val bgColor by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                animationSpec = tween(200, easing = LinearEasing),
                label = "bg",
            )
            val contentColor by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.primary else themeTextGrey(),
                animationSpec = tween(200, easing = LinearEasing),
                label = "content",
            )

            Surface(
                onClick = { onScopeChange(scope) },
                shape = ShapeTokens.largeShape,
                color = bgColor,
                modifier = Modifier,
            ) {
                Text(
                    text = scope.label,
                    color = contentColor,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xs),
                )
            }
        }
    }
}

@Composable
fun InputModeBar(
    currentMode: InputMode,
    onModeChange: (InputMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.xs),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(horizontal = SpacingTokens.xs),
    ) {
        InputMode.entries.forEach { mode ->
            val isSelected = mode == currentMode
            val bgColor by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                animationSpec = tween(200, easing = LinearEasing),
                label = "bg",
            )
            val contentColor by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.primary else themeTextGrey(),
                animationSpec = tween(200, easing = LinearEasing),
                label = "content",
            )

            Surface(
                onClick = { onModeChange(mode) },
                shape = ShapeTokens.largeShape,
                color = bgColor,
                modifier = Modifier,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = SpacingTokens.md, vertical = SpacingTokens.xs),
                ) {
                    Icon(
                        imageVector = mode.icon,
                        contentDescription = mode.label,
                        tint = contentColor,
                        modifier = Modifier.size(SpacingTokens.lg),
                    )
                    Spacer(Modifier.width(SpacingTokens.xs))
                    Text(
                        text = mode.label,
                        color = contentColor,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}
