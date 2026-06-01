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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.hsyscn.opedrgent.ui.SttProgressState

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
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.92f),
            shadowElevation = 8.dp,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp).width(300.dp),
            ) {
                AnimatedContent(
                    targetState = progressState,
                    transitionSpec = { fadeIn(togetherWith(fadeOut())) },
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

                Spacer(Modifier.height(24.dp))

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
                            else -> "取消"
                        },
                        fontWeight = FontWeight.Medium,
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
        contentDescription = "下载模型图标",
        tint = MaterialTheme.colorScheme.primary.copy(alpha = cloudAlpha),
        modifier = Modifier.size(56.dp),
    )

    Spacer(Modifier.height(16.dp))

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
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    Spacer(Modifier.height(12.dp))

    Text(
        text = "正在下载语音识别模型... (${(animatedProgress * 100).toInt()}%)",
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface,
    )

    Spacer(Modifier.height(4.dp))

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
        contentDescription = "音频提取图标",
        tint = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.size(48.dp),
    )

    Spacer(Modifier.height(20.dp))

    CircularProgressIndicator(
        modifier = Modifier.size(52.dp),
        strokeWidth = 4.dp,
        color = MaterialTheme.colorScheme.secondary,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    )

    Spacer(Modifier.height(16.dp))

    Text(
        text = "正在提取音频轨道...",
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface,
    )

    Spacer(Modifier.height(6.dp))

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
        contentDescription = "语音识别图标",
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size((48 * micScale).dp),
    )

    Spacer(Modifier.height(16.dp))

    if (currentPhase != null) {
        Text(
            text = currentPhase,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
    }

    LinearProgressIndicator(
        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    )

    Spacer(Modifier.height(14.dp))

    Text(
        text = "正在进行语音识别...",
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun ErrorPhase(onRetry: () -> Unit) {
    Icon(
        imageVector = Icons.Default.Error,
        contentDescription = "错误图标",
        tint = MaterialTheme.colorScheme.error,
        modifier = Modifier.size(48.dp),
    )

    Spacer(Modifier.height(16.dp))

    Text(
        text = "处理过程中出现错误",
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.error,
    )

    Spacer(Modifier.height(4.dp))

    Text(
        text = "请检查文件格式或网络连接后重试",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(16.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onRetry,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        ) {
            Text("重试", fontWeight = FontWeight.Medium)
        }
        TextButton(onClick = {}) {
            Text("查看帮助", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AudioWaveformIndicator(waveOffset: Float) {
    Canvas(modifier = Modifier.fillMaxWidth().height(24.dp)) {
        val barCount = 24
        val barWidth = size.width / (barCount * 2f)
        for (i in 0 until barCount) {
            val phase = ((i.toFloat() / barCount + waveOffset) % 1f)
            val height = (size.height * (0.25f + 0.75f * kotlin.math.sin(phase * Math.PI.toFloat())).coerceAtLeast(size.height * 0.15f))
            drawRoundRect(
                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f + phase * 0.5f),
                topLeft = Offset(x = i * barWidth * 2f, y = (size.height - height) / 2),
                size = Size(width = barWidth * 1.4f, height = height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2),
            )
        }
    }
}
