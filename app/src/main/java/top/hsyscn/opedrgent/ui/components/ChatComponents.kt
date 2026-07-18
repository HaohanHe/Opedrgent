package top.hsyscn.opedrgent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.model.MessagePart
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import top.hsyscn.opedrgent.ui.theme.SpacingTokens
import top.hsyscn.opedrgent.ui.theme.customColors
import top.hsyscn.opedrgent.ui.theme.themeTextGrey

/**
 * 聊天相关共享组件与工具函数。
 *
 * 主气泡组件已拆分到独立文件：
 * - [UserBubble]：用户消息气泡（右对齐，宽度自适应上限 75%）→ `UserBubble.kt`
 * - [AIMessageCard]：AI 消息气泡（左对齐，含头像/名称头部，宽度上限 75%）→ `AiBubble.kt`
 * - [MessageHeader]：头像 + 名称 + 时间戳头部 → `MessageHeader.kt`
 *
 * 本文件仅保留被上述气泡复用的音频卡片、引用来源等共享helper。
 */

@Composable
fun AudioClipPlayerCard(
    audioClip: MessagePart.AudioClip,
    modifier: Modifier = Modifier,
) {
    Card(
        shape = ShapeTokens.largeShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(SpacingTokens.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { },
                modifier = Modifier
                    .size(44.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.cd_play),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Spacer(Modifier.width(SpacingTokens.md))
            Column(modifier = Modifier.weight(1f)) {
                LinearProgressIndicator(
                    progress = { 0f },
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(SpacingTokens.xs))
                Text(
                    text = chatFormatDuration(audioClip.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = themeTextGrey(),
                )
            }
        }
    }
}

fun chatFormatDuration(ms: Long): String {
    val seconds = ms / 1000
    return "${seconds}s"
}

fun extractSources(content: String): List<Pair<String, String>> {
    val pattern = Regex("""\[(\d+)\]\s*(https?://\S+)""")
    return pattern.findAll(content).map { it.groupValues[1] to it.groupValues[2] }.toList()
}

@Composable
fun SourceCitations(sources: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.xs)) {
        sources.forEach { (index, url) ->
            Card(
                shape = ShapeTokens.extraSmallShape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = SolidColor(MaterialTheme.customColors.citationBg),
                    width = 1.dp,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = SpacingTokens.xs, vertical = SpacingTokens.xxs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SpacingTokens.xs),
                ) {
                    Box(
                        modifier = Modifier
                            .background(MaterialTheme.customColors.citationBg, ShapeTokens.extraSmallShape)
                            .padding(horizontal = SpacingTokens.xs, vertical = SpacingTokens.xxs),
                    ) {
                        Text(index, style = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.primary))
                    }
                    Text(
                        text = runCatching { java.net.URL(url).host }.getOrDefault(url.take(30)),
                        style = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
    }
}
