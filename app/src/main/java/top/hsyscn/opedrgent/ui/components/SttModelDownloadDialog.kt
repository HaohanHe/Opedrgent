package top.hsyscn.opedrgent.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import top.hsyscn.opedrgent.ui.theme.SpacingTokens

/**
 * STT 语音模型下载弹窗 — 对齐 Gemma 4 ModelDownloadDialog 的视觉风格，
 * 展示下载进度、速度、已下载/总大小，以及切换源提示。
 */
@Composable
fun SttModelDownloadDialog(
    modelName: String,
    modelDescription: String,
    percent: Int,
    downloadedMb: Int,
    totalMb: Int,
    speedText: String,
    /** downloading / extracting / sourceSwitch / error / complete */
    status: String,
    statusDetail: String,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
) {
    var shuffledIndices by remember { mutableStateOf(DownloadQuotes.ALL_QUOTES.indices.shuffled()) }
    var quotePointer by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000L)
            quotePointer++
            if (quotePointer >= shuffledIndices.size) {
                shuffledIndices = DownloadQuotes.ALL_QUOTES.indices.shuffled()
                quotePointer = 0
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SpacingTokens.xl),
            shape = ShapeTokens.extraLargeShape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(SpacingTokens.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(SpacingTokens.lg))

                androidx.compose.material3.Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = "下载语音模型",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(SpacingTokens.xxl),
                )

                Spacer(modifier = Modifier.height(SpacingTokens.lg))

                Text(
                    text = "正在下载 $modelName",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(SpacingTokens.sm))

                Text(
                    text = modelDescription,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(SpacingTokens.xl))

                // 进度区
                when (status) {
                    "downloading" -> {
                        SttProgressSection(percent = percent, downloadedMb = downloadedMb, totalMb = totalMb, speedText = speedText)
                    }
                    "extracting" -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(SpacingTokens.xs)
                                    .clip(ShapeTokens.smallShape),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            )
                            Spacer(modifier = Modifier.height(SpacingTokens.md))
                            Text(
                                text = statusDetail.ifEmpty { "解压中..." },
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    "sourceSwitch" -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(SpacingTokens.xs)
                                    .clip(ShapeTokens.smallShape),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            )
                            Spacer(modifier = Modifier.height(SpacingTokens.md))
                            Text(
                                text = statusDetail.ifEmpty { "切换下载源..." },
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    "error" -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "下载失败",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(SpacingTokens.xxl),
                            )
                            Spacer(modifier = Modifier.height(SpacingTokens.sm))
                            Text(text = "下载失败", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.height(SpacingTokens.xs))
                            Text(
                                text = statusDetail.ifEmpty { "未知错误" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                            )
                        }
                    }
                    else -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(SpacingTokens.xs)
                                    .clip(ShapeTokens.smallShape),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            )
                            Spacer(modifier = Modifier.height(SpacingTokens.md))
                            Text(
                                text = if (totalMb > 0) "准备下载 ($totalMb MB)" else "准备中...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(SpacingTokens.xl))

                // 古诗轮播卡片
                SttQuoteCard(currentQuoteIndex = shuffledIndices[quotePointer])

                Spacer(modifier = Modifier.height(SpacingTokens.lg))

                Text(
                    text = "好东西，就要来了...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(SpacingTokens.xl))

                // 取消按钮
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = SpacingTokens.lg),
                    shape = ShapeTokens.mediumShape,
                ) {
                    Text(
                        text = "取消下载",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(modifier = Modifier.height(SpacingTokens.md))
            }
        }
    }
}

@Composable
private fun SttQuoteCard(currentQuoteIndex: Int) {
    val quote = DownloadQuotes.ALL_QUOTES[currentQuoteIndex]

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = ShapeTokens.mediumShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Crossfade(
            targetState = quote,
            animationSpec = tween(durationMillis = 500),
            label = "stt_quote_crossfade",
        ) { currentQuote ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(SpacingTokens.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(SpacingTokens.sm))

                Text(
                    text = currentQuote.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(SpacingTokens.md))

                Text(
                    text = "-- ${currentQuote.author}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(SpacingTokens.sm))
            }
        }
    }
}

@Composable
private fun SttProgressSection(
    percent: Int,
    downloadedMb: Int,
    totalMb: Int,
    speedText: String,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                LinearProgressIndicator(
                    progress = { percent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(SpacingTokens.xs)
                        .clip(ShapeTokens.smallShape),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                )
            }
            Spacer(modifier = Modifier.width(SpacingTokens.md))
            Text(
                text = "$percent%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(modifier = Modifier.height(SpacingTokens.md))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (totalMb > 0) "${downloadedMb} MB / ${totalMb} MB" else "${downloadedMb} MB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (speedText.isNotEmpty()) {
                Text(
                    text = speedText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
