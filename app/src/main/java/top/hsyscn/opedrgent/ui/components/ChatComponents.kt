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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.model.MessagePart
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import top.hsyscn.opedrgent.ui.theme.SizeTokens
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
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(audioClip.durationMs.coerceAtLeast(0L)) }

    val exoPlayer = remember(audioClip.filePath) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(java.io.File(audioClip.filePath))))
            prepare()
        }
    }

    DisposableEffect(audioClip.filePath) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    duration = exoPlayer.duration.coerceAtLeast(0L)
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        exoPlayer.addListener(listener)
        exoPlayer.playWhenReady = false

        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L)
            delay(200L)
        }
    }

    val progress = if (duration > 0) currentPosition.toFloat() / duration.toFloat() else 0f
    val durationText = if (duration > 0) chatFormatDuration(duration) else chatFormatDuration(audioClip.durationMs)

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
                onClick = {
                    if (isPlaying) {
                        exoPlayer.pause()
                    } else {
                        if (currentPosition >= duration && duration > 0) {
                            exoPlayer.seekTo(0L)
                        }
                        exoPlayer.play()
                    }
                },
                modifier = Modifier
                    .size(SizeTokens.quickActionIcon)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = stringResource(if (isPlaying) R.string.cd_pause else R.string.cd_play),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Spacer(Modifier.width(SpacingTokens.md))
            Column(modifier = Modifier.weight(1f)) {
                LinearProgressIndicator(
                    progress = { progress },
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(SpacingTokens.xs))
                Text(
                    text = durationText,
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
                    width = SizeTokens.borderWidth,
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
