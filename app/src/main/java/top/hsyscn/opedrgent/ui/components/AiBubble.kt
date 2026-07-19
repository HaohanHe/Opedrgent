@file:Suppress("DEPRECATION")

package top.hsyscn.opedrgent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.model.ChatMessage
import top.hsyscn.opedrgent.model.Role
import top.hsyscn.opedrgent.ui.theme.ElevationTokens
import top.hsyscn.opedrgent.ui.theme.OpedrgentTheme
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import top.hsyscn.opedrgent.ui.theme.SizeTokens
import top.hsyscn.opedrgent.ui.theme.SpacingTokens

/**
 * AI 消息气泡（左对齐）。
 *
 * - 宽度上限为父容器 75%（替代原先占满整行的行为），适配手机/平板/折叠屏。
 * - 顶部显示 AI 头像与名称（[MessageHeader]），便于在长对话中区分说话人。
 *
 * 保留原有功能：音频片段、思考过程、工具状态、选择题、Markdown 渲染、
 * 引用来源、点赞/踩反馈、复制与撤回。
 */
@Suppress("DEPRECATION")
@Composable
fun AIMessageCard(
    message: ChatMessage,
    onSpeak: (() -> Unit)?,
    isSpeaking: Boolean,
    clipboard: ClipboardManager,
    onUndo: (() -> Unit)? = null,
    aiName: String = "",
) {
    var showMenu by remember { mutableStateOf(false) }
    var userReaction by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val feedback = LocalFeedbackController.current
    val timeText = remember(message.createdAt) { formatMessageTime(message.createdAt) }
    val displayAiName = aiName.takeIf { it.isNotBlank() } ?: stringResource(R.string.app_name)

    // 根据窗口宽度动态调整 AI 消息块宽度：大屏更窄，避免文字过长
    val widthFraction = when (rememberWindowSizeInfo().widthSizeClass) {
        androidx.compose.material3.windowsizeclass.WindowWidthSizeClass.Expanded -> 0.55f
        androidx.compose.material3.windowsizeclass.WindowWidthSizeClass.Medium -> 0.65f
        else -> 0.75f
    }
    Column(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .semantics(mergeDescendants = true) {},
    ) {
        MessageHeader(
            avatar = Icons.Default.AutoAwesome,
            name = displayAiName,
            timestamp = timeText,
        )

        Box {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { showMenu = true },
                    ),
                shape = ShapeTokens.largeShape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = ElevationTokens.md),
            ) {
                Column(
                    modifier = Modifier.padding(SpacingTokens.md),
                    verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
                ) {
                    // 音频消息卡片（如果有）
                    if (message.hasAudio) {
                        message.audioClips.forEach { audioClip ->
                            AudioClipPlayerCard(audioClip = audioClip)
                        }
                    }

                    if (message.reasoningParts.isNotEmpty()) {
                        val reasoningText = message.reasoningParts.joinToString("\n") { it.text }
                        MessageBodyThinking(
                            thinkingText = reasoningText,
                            isComplete = true,
                        )
                    }

                    if (message.toolParts.isNotEmpty()) {
                        ToolStatusGroup(toolParts = message.toolParts)
                    }

                    if (message.questionPart != null) {
                        QuestionCard(
                            question = message.questionPart,
                            onAnswer = {},
                            onDismiss = {},
                            readonly = true,
                        )
                    }

                    if (message.textContent.isNotBlank()) {
                        // UI 层不做截断 —— ContextCompressor 在上游已按模型上下文窗口控制大小
                        // 使用 foreground-secondary 作为消息正文色
                        MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(onSurface = MaterialTheme.colorScheme.secondary)) {
                            MarkdownText(text = message.textContent, maxChars = Int.MAX_VALUE)
                        }
                    }

                    val sources = extractSources(message.textContent)
                    if (sources.isNotEmpty()) {
                        SourceCitations(sources = sources)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Card(
                            shape = ShapeTokens.smallShape,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = SpacingTokens.xs, vertical = SpacingTokens.xxs),
                                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.xs),
                            ) {
                                IconButton(onClick = {
                                    userReaction = "up"
                                    feedback.showFeedback(context.getString(R.string.msg_thanks_feedback))
                                }, modifier = Modifier.size(SizeTokens.iconXl)) {
                                    Box(
                                        modifier = Modifier
                                            .size(SizeTokens.iconXl)
                                            .then(
                                                if (userReaction == "up")
                                                    Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), ShapeTokens.extraSmallShape)
                                                else Modifier
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ThumbUp,
                                            contentDescription = stringResource(R.string.ai_bubble_cd_reaction_up),
                                            tint = if (userReaction == "up") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(SizeTokens.iconMd),
                                        )
                                    }
                                }
                                IconButton(onClick = {
                                    userReaction = "down"
                                    feedback.showFeedback(context.getString(R.string.msg_thanks_feedback))
                                }, modifier = Modifier.size(SizeTokens.iconXl)) {
                                    Box(
                                        modifier = Modifier
                                            .size(SizeTokens.iconXl)
                                            .then(
                                                if (userReaction == "down")
                                                    Modifier.background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f), ShapeTokens.extraSmallShape)
                                                else Modifier
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ThumbDown,
                                            contentDescription = stringResource(R.string.ai_bubble_cd_reaction_down),
                                            tint = if (userReaction == "down") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(SizeTokens.iconMd),
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        clipboard.setText(AnnotatedString(message.textContent))
                                        feedback.showFeedback(context.getString(R.string.msg_copied))
                                    },
                                    modifier = Modifier.size(SizeTokens.iconXl),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = stringResource(R.string.ai_bubble_cd_copy),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(SizeTokens.iconMd),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 长按菜单：复制 / 撤回
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_copy)) },
                    onClick = {
                        clipboard.setText(AnnotatedString(message.textContent))
                        feedback.showFeedback(context.getString(R.string.msg_copied))
                        showMenu = false
                    },
                )
                if (onUndo != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.bubble_action_undo)) },
                        onClick = {
                            onUndo()
                            showMenu = false
                        },
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "Light")
@Composable
private fun AIMessageCardPreview() {
    OpedrgentTheme(darkTheme = false) {
        @Suppress("DEPRECATION")
        val clipboard = LocalClipboardManager.current
        val message = ChatMessage(
            role = Role.ASSISTANT,
            content = stringResource(R.string.ai_bubble_preview_message),
        )
        Column(modifier = Modifier.padding(SpacingTokens.lg)) {
            AIMessageCard(
                message = message,
                onSpeak = {},
                isSpeaking = false,
                clipboard = clipboard,
            )
        }
    }
}

@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES, name = "Dark")
@Composable
private fun AIMessageCardDarkPreview() {
    OpedrgentTheme(darkTheme = true) {
        @Suppress("DEPRECATION")
        val clipboard = LocalClipboardManager.current
        val message = ChatMessage(
            role = Role.ASSISTANT,
            content = stringResource(R.string.ai_bubble_preview_message),
        )
        Column(modifier = Modifier.padding(SpacingTokens.lg)) {
            AIMessageCard(
                message = message,
                onSpeak = {},
                isSpeaking = false,
                clipboard = clipboard,
            )
        }
    }
}
