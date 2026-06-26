package top.hsyscn.opedrgent.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import kotlin.math.roundToLong
import top.hsyscn.opedrgent.ui.theme.SpacingTokens

/**
 * 全功能音频播放器组件。
 *
 * @param audioUri 音频文件 URI 或路径
 * @param modifier 修饰符
 */
@Composable
fun AudioPlayer(
    audioUri: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(audioUri))
            prepare()
        }
    }

    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var showSpeedSheet by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableLongStateOf(0L) }

    DisposableEffect(audioUri) {
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
        exoPlayer.setMediaItem(MediaItem.fromUri(audioUri))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = false

        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(isPlaying, isDragging) {
        while (isPlaying && !isDragging) {
            currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L)
            delay(200L)
        }
    }

    val displayPosition = if (isDragging) dragPosition else currentPosition
    val progress = if (duration > 0) displayPosition.toFloat() / duration.toFloat() else 0f

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = SpacingTokens.lg, vertical = SpacingTokens.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 进度条 + 时间
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatTime(displayPosition),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(40.dp),
            )

            Spacer(Modifier.width(SpacingTokens.sm))

            // 自定义进度条
            CustomProgressBar(
                progress = progress,
                onProgressChange = { newProgress ->
                    dragPosition = (newProgress * duration).roundToLong()
                },
                onDragStart = {
                    isDragging = true
                    dragPosition = currentPosition
                },
                onDragEnd = {
                    isDragging = false
                    exoPlayer.seekTo(dragPosition)
                    currentPosition = dragPosition
                },
                modifier = Modifier.weight(1f),
            )

            Spacer(Modifier.width(SpacingTokens.sm))

            Text(
                text = formatTime(duration),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(40.dp),
            )
        }

        Spacer(Modifier.height(SpacingTokens.md))

        // 控制按钮行
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.lg),
        ) {
            // 后退 15 秒
            SkipButton(
                label = "15",
                isForward = false,
                onClick = {
                    val newPos = (exoPlayer.currentPosition - 15000L).coerceAtLeast(0L)
                    exoPlayer.seekTo(newPos)
                    currentPosition = newPos
                },
            )

            // 播放/暂停
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .clickable {
                        if (isPlaying) {
                            exoPlayer.pause()
                        } else {
                            exoPlayer.play()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(28.dp),
                )
            }

            // 前进 15 秒
            SkipButton(
                label = "15",
                isForward = true,
                onClick = {
                    val newPos = (exoPlayer.currentPosition + 15000L).coerceAtMost(duration)
                    exoPlayer.seekTo(newPos)
                    currentPosition = newPos
                },
            )

            // 倍速按钮
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), CircleShape)
                    .clickable { showSpeedSheet = true },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "${playbackSpeed.formatSpeed()}x",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }

    if (showSpeedSheet) {
        PlaybackSpeedSheet(
            currentSpeed = playbackSpeed,
            onSpeedSelected = { speed ->
                playbackSpeed = speed
                exoPlayer.setPlaybackSpeed(speed)
                showSpeedSheet = false
            },
            onDismiss = { showSpeedSheet = false },
        )
    }
}

@Composable
private fun CustomProgressBar(
    progress: Float,
    onProgressChange: (Float) -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val trackHeight = 4.dp
    val thumbRadius = 10.dp
    val thumbRadiusPx = with(density) { thumbRadius.toPx() }

    var dragOffset by remember { mutableFloatStateOf(0f) }
    var barWidth by remember { mutableFloatStateOf(0f) }
    val clampedProgress = progress.coerceIn(0f, 1f)
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val progressColor = MaterialTheme.colorScheme.onSurface

    Box(
        modifier = modifier
            .height(32.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        onDragStart()
                        dragOffset = offset.x
                        val newProgress = (offset.x / barWidth).coerceIn(0f, 1f)
                        onProgressChange(newProgress)
                    },
                    onDragEnd = { onDragEnd() },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        dragOffset = change.position.x
                        val newProgress = (change.position.x / barWidth).coerceIn(0f, 1f)
                        onProgressChange(newProgress)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
        ) {
            barWidth = size.width
            val centerY = size.height / 2
            val progressWidth = size.width * clampedProgress

            // 背景轨道
            drawLine(
                color = trackColor,
                start = Offset(0f, centerY),
                end = Offset(size.width, centerY),
                strokeWidth = size.height,
                cap = StrokeCap.Round,
            )

            // 已播放部分
            drawLine(
                color = progressColor,
                start = Offset(0f, centerY),
                end = Offset(progressWidth, centerY),
                strokeWidth = size.height,
                cap = StrokeCap.Round,
            )
        }

        // 自定义滑块
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(thumbRadius * 2),
        ) {
            Box(
                modifier = Modifier
                    .offset(
                        x = with(density) {
                            ((barWidth * clampedProgress) - thumbRadius.toPx()).toDp()
                        },
                    )
                    .size(thumbRadius * 2)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface)
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
            )
        }
    }
}

@Composable
private fun SkipButton(
    label: String,
    isForward: Boolean,
    onClick: () -> Unit,
) {
    val iconColor = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(28.dp)) {
            val strokeWidth = 2.dp.toPx()
            val radius = size.minDimension / 2 - strokeWidth / 2
            val center = Offset(size.width / 2, size.height / 2)

            // 圆弧箭头
            val arcStartAngle = if (isForward) 210f else 120f
            val arcSweepAngle = 240f
            drawArc(
                color = iconColor,
                startAngle = arcStartAngle,
                sweepAngle = arcSweepAngle,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )

            // 箭头尖端
            val arrowAngle = Math.toRadians(if (isForward) 30.0 else 150.0)
            val arrowTipX = center.x + radius * kotlin.math.cos(arrowAngle).toFloat()
            val arrowTipY = center.y + radius * kotlin.math.sin(arrowAngle).toFloat()
            val arrowLen = 5.dp.toPx()
            val arrowAngle1 = arrowAngle + Math.toRadians(30.0 * if (isForward) 1 else -1)
            val arrowAngle2 = arrowAngle - Math.toRadians(30.0 * if (isForward) 1 else -1)

            drawLine(
                color = iconColor,
                start = Offset(arrowTipX, arrowTipY),
                end = Offset(
                    arrowTipX - arrowLen * kotlin.math.cos(arrowAngle1).toFloat(),
                    arrowTipY - arrowLen * kotlin.math.sin(arrowAngle1).toFloat(),
                ),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = iconColor,
                start = Offset(arrowTipX, arrowTipY),
                end = Offset(
                    arrowTipX - arrowLen * kotlin.math.cos(arrowAngle2).toFloat(),
                    arrowTipY - arrowLen * kotlin.math.sin(arrowAngle2).toFloat(),
                ),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }

        // 数字标签
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}

private fun Float.formatSpeed(): String {
    return if (this == this.toInt().toFloat()) {
        this.toInt().toString()
    } else {
        this.toString()
    }
}
