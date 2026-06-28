package top.hsyscn.opedrgent.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.ui.SttProgressState
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import top.hsyscn.opedrgent.ui.theme.SpacingTokens

@Composable
fun SttProgressDialog(
    progressState: SttProgressState,
    downloadProgress: Float? = null,
    currentPhase: String? = null,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (progressState == SttProgressState.IDLE || progressState == SttProgressState.DONE) return

    val isDismissible = progressState == SttProgressState.ERROR

    Box(
        modifier = modifier
            .semantics { contentDescription = "语音处理进度对话框" }
            .fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = ShapeTokens.extraLargeShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.92f),
            shadowElevation = 8.dp,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(SpacingTokens.xxl).width(300.dp),
            ) {
                AnimatedContent(
                    targetState = progressState,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "progressState",
                ) { state ->
                    when (state) {
                        SttProgressState.DOWNLOADING_MODEL -> DownloadingPhase(
                            downloadProgress = downloadProgress ?: 0f,
                        )
                        SttProgressState.EXTRACTING_AUDIO -> ExtractingPhase()
                        SttProgressState.RECOGNIZING -> RecognizingPhase(currentPhase = currentPhase)
                        SttProgressState.ERROR -> ErrorPhase(onRetry = onCancel)
                        else -> {}
                    }
                }

                Spacer(Modifier.height(SpacingTokens.xl))

                TextButton(
                    onClick = onCancel,
                    enabled = isDismissible || stateAllowsCancel(progressState),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (isDismissible)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    modifier = Modifier.semantics {
                        contentDescription = if (isDismissible) "关闭错误提示" else "取消处理"
                    },
                ) {
                    Text(
                        text = when (progressState) {
                            SttProgressState.ERROR -> "关闭"
                            else -> stringResource(R.string.action_cancel)
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun stateAllowsCancel(state: SttProgressState): Boolean =
    state == SttProgressState.DOWNLOADING_MODEL ||
    state == SttProgressState.RECOGNIZING

@Composable
private fun DownloadingPhase(downloadProgress: Float) {
    val animatedProgress by animateFloatAsState(
        targetValue = downloadProgress,
        animationSpec = tween(durationMillis = 400, easing = LinearEasing),
        label = "downloadProgress",
    )

    val infiniteTransition = rememberInfiniteTransition(label = "cloudPulse")
    val cloudAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "cloudAlpha",
    )

    Icon(
        imageVector = Icons.Default.CloudDownload,
        contentDescription = "下载模型",
        tint = MaterialTheme.colorScheme.primary.copy(alpha = cloudAlpha),
        modifier = Modifier.size(56.dp),
    )

    Spacer(Modifier.height(SpacingTokens.lg))

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(72.dp)) {
        CircularProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier.size(72.dp),
            strokeWidth = 5.dp,
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        Text(
            text = "${(animatedProgress * 100).toInt()}%",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    Spacer(Modifier.height(SpacingTokens.md))

    Text(
        text = "正在下载语音识别模型... (${(animatedProgress * 100).toInt()}%)",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )

    Spacer(Modifier.height(SpacingTokens.xs))

    Text(
        text = "模型大小约 200MB，首次使用需下载",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ExtractingPhase() {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")
    val waveOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing)),
        label = "waveOffset",
    )

    Icon(
        imageVector = Icons.Default.GraphicEq,
        contentDescription = "提取音频",
        tint = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.size(48.dp),
    )

    Spacer(Modifier.height(SpacingTokens.xl))

    CircularProgressIndicator(
        modifier = Modifier.size(52.dp),
        strokeWidth = 4.dp,
        color = MaterialTheme.colorScheme.secondary,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    )

    Spacer(Modifier.height(SpacingTokens.lg))

    Text(
        text = "正在提取音频轨道...",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )

    Spacer(Modifier.height(SpacingTokens.sm))

    AudioWaveformIndicator(waveOffset = waveOffset)
}

@Composable
private fun RecognizingPhase(currentPhase: String?) {
    val infiniteTransition = rememberInfiniteTransition(label = "micPulse")
    val micScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "micScale",
    )

    Icon(
        imageVector = Icons.Default.Mic,
        contentDescription = "语音识别",
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size((48 * micScale).dp),
    )

    Spacer(Modifier.height(SpacingTokens.lg))

    if (currentPhase != null) {
        Text(
            text = currentPhase,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(SpacingTokens.sm))
    }

    LinearProgressIndicator(
        modifier = Modifier.fillMaxWidth().height(6.dp).clip(ShapeTokens.smallShape),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    )

    Spacer(Modifier.height(SpacingTokens.md))

    Text(
        text = "正在进行语音识别...",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun ErrorPhase(onRetry: () -> Unit) {
    var showHelpDialog by remember { mutableStateOf(false) }

    Icon(
        imageVector = Icons.Default.Error,
        contentDescription = "错误",
        tint = MaterialTheme.colorScheme.error,
        modifier = Modifier.size(48.dp),
    )

    Spacer(Modifier.height(SpacingTokens.lg))

    Text(
        text = "处理过程中出现错误",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.error,
    )

    Spacer(Modifier.height(SpacingTokens.xs))

    Text(
        text = "请检查文件格式或网络连接后重试",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(SpacingTokens.lg))

    Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
        Button(
            onClick = onRetry,
            shape = ShapeTokens.mediumShape,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        ) {
            Text("重试", style = MaterialTheme.typography.labelLarge)
        }
        TextButton(onClick = { showHelpDialog = true }) {
            Text("查看帮助", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = { Text(stringResource(R.string.stt_tips_title)) },
            text = {
                Text(
                    text = stringResource(R.string.stt_tips_content),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { showHelpDialog = false }) {
                    Text("确定")
                }
            },
        )
    }
}

@Composable
private fun AudioWaveformIndicator(waveOffset: Float) {
    val secondaryColor = MaterialTheme.colorScheme.secondary

    Canvas(modifier = Modifier.fillMaxWidth().height(24.dp)) {
        val barCount = 24
        val barWidth = size.width / (barCount * 2f)
        for (i in 0 until barCount) {
            val phase = ((i.toFloat() / barCount + waveOffset) % 1f)
            val height = (size.height * (0.25f + 0.75f * kotlin.math.sin(phase * Math.PI.toFloat())).coerceAtLeast(size.height * 0.15f))
            drawRoundRect(
                color = secondaryColor.copy(alpha = 0.5f + phase * 0.5f),
                topLeft = Offset(x = i * barWidth * 2f, y = (size.height - height) / 2),
                size = Size(width = barWidth * 1.4f, height = height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2),
            )
        }
    }
}
