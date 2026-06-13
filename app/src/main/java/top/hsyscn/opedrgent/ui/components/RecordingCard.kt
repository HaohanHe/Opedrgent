package top.hsyscn.opedrgent.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.hsyscn.opedrgent.ui.theme.AccentBlue
import top.hsyscn.opedrgent.ui.theme.TextDark
import top.hsyscn.opedrgent.ui.theme.TextGrey

enum class RecordingState {
    RECORDING,
    PAUSED,
    PROCESSING,
    DONE,
}

@Composable
fun RecordingCard(
    recordingState: RecordingState,
    elapsedSeconds: Int,
    fileName: String,
    amplitude: Float,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            // Header with waveform
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Pulsing mic icon
                val pulseScale = if (recordingState == RecordingState.RECORDING) {
                    val infiniteTransition = rememberInfiniteTransition(label = "mic")
                    val scale by infiniteTransition.animateFloat(
                        initialValue = 0.9f,
                        targetValue = 1.1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(800, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "scale",
                    )
                    scale
                } else {
                    1f
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    AccentBlue.copy(alpha = 0.15f),
                                    AccentBlue.copy(alpha = 0.05f),
                                ),
                            ),
                            shape = CircleShape,
                        ),
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = null,
                        tint = AccentBlue,
                        modifier = Modifier.size((24 * pulseScale).dp),
                    )
                }

                Spacer(Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = formatElapsed(elapsedSeconds),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextDark,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                    // 录音状态指示文字
                    Text(
                        text = when (recordingState) {
                            RecordingState.RECORDING -> "● 正在录音..."
                            RecordingState.PAUSED -> "⏸ 已暂停"
                            else -> fileName
                        },
                        fontSize = if (recordingState == RecordingState.RECORDING) 13.sp else 12.sp,
                        color = if (recordingState == RecordingState.RECORDING) Color(0xFFE53935) else TextGrey,
                        fontWeight = if (recordingState == RecordingState.RECORDING) FontWeight.Medium else FontWeight.Normal,
                    )
                    if (recordingState != RecordingState.RECORDING) {
                        Text(
                            text = fileName,
                            fontSize = 11.sp,
                            color = TextGrey.copy(alpha = 0.7f),
                            maxLines = 1,
                        )
                    }
                }

                // Close button
                IconButton(onClick = onCancel) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "取消录音",
                        tint = TextGrey,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // Waveform visualization
            WaveformVisualizer(
                amplitude = amplitude,
                isRecording = recordingState == RecordingState.RECORDING,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
            )

            Spacer(Modifier.height(20.dp))

            // Control buttons
            Row(
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (recordingState == RecordingState.RECORDING) {
                    // Pause button
                    Surface(
                        onClick = onPause,
                        shape = CircleShape,
                        color = Color(0xFFF5F5F5),
                        modifier = Modifier.size(56.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Pause,
                                contentDescription = "暂停",
                                tint = TextDark,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }

                    Spacer(Modifier.width(24.dp))

                    // Stop button — 使用方块图标，红色背景突出显示
                    Surface(
                        onClick = onStop,
                        shape = CircleShape,
                        color = Color(0xFFE53935),  // 红色：醒目的停止按钮
                        modifier = Modifier.size(56.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            // 方块停止图标（比 MusicNote 更语义化）
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .background(Color.White, RoundedCornerShape(3.dp)),
                            )
                        }
                    }
                } else if (recordingState == RecordingState.PAUSED) {
                    // Resume button
                    Surface(
                        onClick = onResume,
                        shape = CircleShape,
                        color = AccentBlue.copy(alpha = 0.1f),
                        modifier = Modifier.size(56.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "继续",
                                tint = AccentBlue,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }

                    Spacer(Modifier.width(24.dp))

                    // Stop button (红色，与录音中一致)
                    Surface(
                        onClick = onStop,
                        shape = CircleShape,
                        color = Color(0xFFE53935),
                        modifier = Modifier.size(56.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .background(Color.White, RoundedCornerShape(3.dp)),
                            )
                        }
                    }
                } else if (recordingState == RecordingState.PROCESSING) {
                    // Processing indicator
                    LinearProgressIndicator(
                        modifier = Modifier
                            .width(200.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = AccentBlue,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("处理中...", color = TextGrey, fontSize = 13.sp)
                }
            }

            if (recordingState == RecordingState.PAUSED) {
                Spacer(Modifier.height(8.dp))
                Text("已暂停", color = TextGrey, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun WaveformVisualizer(
    amplitude: Float,
    isRecording: Boolean,
    modifier: Modifier = Modifier,
) {
    val accentBlue = AccentBlue
    val greyLight = Color(0xFFE0E0E0)

    Canvas(modifier = modifier.clip(RoundedCornerShape(12.dp))) {
        val barCount = 48
        val spacing = size.width / barCount
        val barWidth = spacing * 0.45f
        val centerY = size.height / 2
        val maxHeight = size.height * 0.85f

        for (i in 0 until barCount) {
            val normalizedPos = i.toFloat() / barCount

            val height = if (isRecording) {
                // Dynamic waveform: mix of base wave + amplitude modulation
                val baseWave = kotlin.math.sin(normalizedPos.toDouble() * 6.28 * 3 + System.currentTimeMillis() * 0.003).toFloat() * 0.3f + 0.5f
                val ampMod = amplitude * (0.5f + 0.5f * kotlin.math.sin(normalizedPos.toDouble() * 3.14).toFloat())
                (baseWave * maxHeight * 0.4f + ampMod * maxHeight * 0.6f).coerceAtLeast(maxHeight * 0.08f)
            } else {
                // Idle: subtle flat line
                maxHeight * 0.06f
            }

            val color = if (isRecording) {
                val alpha = 0.3f + 0.7f * (1f - normalizedPos * 0.5f)
                accentBlue.copy(alpha = alpha)
            } else {
                greyLight
            }

            drawRoundRect(
                color = color,
                topLeft = Offset(
                    x = i * spacing + (spacing - barWidth) / 2,
                    y = centerY - height / 2,
                ),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2),
            )
        }
    }
}

private fun formatElapsed(seconds: Int): String {
    val min = seconds / 60
    val sec = seconds % 60
    return "%02d:%02d".format(min, sec)
}
