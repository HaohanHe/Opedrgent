@file:Suppress("DEPRECATION")

package top.hsyscn.opedrgent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import top.hsyscn.opedrgent.model.MessagePart
import top.hsyscn.opedrgent.ui.theme.OpedrgentTheme
import top.hsyscn.opedrgent.ui.theme.SpacingTokens

/**
 * 用户消息气泡（右对齐）。
 *
 * 宽度自适应内容，但上限为父容器宽度的 75%（适配手机/平板/折叠屏），
 * 替代原先固定的 280dp 上限。用户消息不显示头像/名称头部。
 */
@Suppress("DEPRECATION")
@Composable
fun UserBubble(
    text: String,
    clipboard: ClipboardManager? = null,
    onUndo: (() -> Unit)? = null,
    audioClips: List<MessagePart.AudioClip> = emptyList(),
) {
    var showMenu by remember { mutableStateOf(false) }
    val feedback = LocalFeedbackController.current

    // 根据窗口宽度动态调整气泡宽度比例：大屏更窄，避免文字过长
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val widthFraction = when (rememberWindowSizeInfo().widthSizeClass) {
            androidx.compose.material3.windowsizeclass.WindowWidthSizeClass.Expanded -> 0.55f
            androidx.compose.material3.windowsizeclass.WindowWidthSizeClass.Medium -> 0.65f
            else -> 0.75f
        }
        val maxBubbleWidth = maxWidth * widthFraction

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.End,
        ) {
            Column(horizontalAlignment = Alignment.End) {
                // 音频卡片（用户发送的音频消息）
                audioClips.forEach { audioClip ->
                    AudioClipPlayerCard(
                        audioClip = audioClip,
                        modifier = Modifier.widthIn(max = maxBubbleWidth),
                    )
                    Spacer(Modifier.height(SpacingTokens.sm))
                }

                Box(
                    modifier = Modifier
                        .widthIn(max = maxBubbleWidth) // 自适应宽度：内容自适应，上限为父容器 75%
                        .wrapContentWidth(Alignment.End),
                ) {
                    val bubbleShape = RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 8.dp,
                        bottomStart = 16.dp,
                        bottomEnd = 16.dp,
                    )
                    Box(
                        modifier = Modifier
                            .clip(bubbleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .combinedClickable(
                                onClick = {},
                                onLongClick = { showMenu = true },
                            )
                            .padding(horizontal = SpacingTokens.md, vertical = SpacingTokens.sm),
                    ) {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    if (clipboard != null) {
                        DropdownMenuItem(
                            text = { Text("复制") },
                            onClick = {
                                clipboard.setText(AnnotatedString(text))
                                feedback.showFeedback("已复制")
                                showMenu = false
                            },
                        )
                    }
                    if (onUndo != null) {
                        DropdownMenuItem(
                            text = { Text("撤回") },
                            onClick = {
                                onUndo()
                                showMenu = false
                            },
                        )
                    }
                }
            } // end Column
        } // end Row
    } // end BoxWithConstraints
}

@Preview(showBackground = true, name = "Light")
@Composable
private fun UserBubblePreview() {
    OpedrgentTheme(darkTheme = false) {
        Box(modifier = Modifier.padding(SpacingTokens.lg)) {
            UserBubble(text = "你好，这是一条用户消息预览。")
        }
    }
}

@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES, name = "Dark")
@Composable
private fun UserBubbleDarkPreview() {
    OpedrgentTheme(darkTheme = true) {
        Box(modifier = Modifier.padding(SpacingTokens.lg)) {
            UserBubble(text = "你好，这是一条用户消息预览。")
        }
    }
}
