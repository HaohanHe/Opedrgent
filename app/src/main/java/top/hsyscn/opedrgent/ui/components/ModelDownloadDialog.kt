package top.hsyscn.opedrgent.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import kotlinx.coroutines.delay
import top.hsyscn.opedrgent.llm.DownloadProgress
import top.hsyscn.opedrgent.llm.DownloadStatus
import top.hsyscn.opedrgent.llm.LocalModelInfo
import top.hsyscn.opedrgent.ui.theme.BubbleBlue
import top.hsyscn.opedrgent.ui.theme.themeTextDark
import top.hsyscn.opedrgent.ui.theme.themeTextGrey

@Composable
fun ModelDownloadDialog(
    modelInfo: LocalModelInfo,
    progress: DownloadProgress?,
    isPaused: Boolean,
    onDismiss: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onConfirmCancel: () -> Unit,
) {
    var showCancelConfirm by remember { mutableStateOf(false) }
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

    if (showCancelConfirm) {
        AlertDialog(
            onDismissRequest = { showCancelConfirm = false },
            title = { Text("确定要取消下载吗？") },
            text = { Text("模型文件较大，重新下载需要较长时间。\n已下载的部分将保留，下次可断点续传。") },
            confirmButton = {
                TextButton(onClick = onConfirmCancel) {
                    Text("确定取消", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelConfirm = false }) {
                    Text("继续下载")
                }
            }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    tint = BubbleBlue,
                    modifier = Modifier.size(48.dp),
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "正在下载 ${modelInfo.displayName}",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = themeTextDark(),
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = modelInfo.description,
                    fontSize = 14.sp,
                    color = themeTextGrey(),
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(24.dp))

                when (progress?.status) {
                    DownloadStatus.DOWNLOADING -> {
                        ProgressSection(progress = progress!!)
                    }
                    DownloadStatus.PAUSED -> {
                        PausedSection(progress = progress)
                    }
                    DownloadStatus.QUEUED -> {
                        QueuedSection()
                    }
                    DownloadStatus.FAILED -> {
                        FailedSection(error = progress?.error ?: "未知错误")
                    }
                    else -> {
                        IdleSection(totalMb = modelInfo.sizeMb)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                QuoteCard(currentQuoteIndex = shuffledIndices[quotePointer])

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "好东西，就要来了...",
                    fontSize = 13.sp,
                    color = themeTextGrey(),
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(24.dp))

                ActionButtons(
                    isPaused = isPaused,
                    status = progress?.status,
                    onPause = onPause,
                    onResume = onResume,
                    onCancel = { showCancelConfirm = true },
                )

                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun ProgressSection(progress: DownloadProgress) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                LinearProgressIndicator(
                    progress = { progress.progressPercent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = BubbleBlue,
                    trackColor = BubbleBlue.copy(alpha = 0.15f),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "${progress.progressPercent.toInt()}%",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = BubbleBlue,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${progress.downloadedMb.toInt()} MB / ${progress.totalMb.toInt()} MB",
                fontSize = 13.sp,
                color = themeTextGrey(),
            )
            Text(
                text = formatSpeed(progress.speedBytesPerSec),
                fontSize = 13.sp,
                color = themeTextGrey(),
            )
        }
    }
}

@Composable
private fun PausedSection(progress: DownloadProgress?) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (progress != null && progress.totalBytes > 0) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    LinearProgressIndicator(
                        progress = { progress.progressPercent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = themeTextGrey(),
                        trackColor = BubbleBlue.copy(alpha = 0.15f),
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "${progress.progressPercent.toInt()}%",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = themeTextGrey(),
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "${progress.downloadedMb.toInt()} MB / ${progress.totalMb.toInt()} MB",
                fontSize = 13.sp,
                color = themeTextGrey(),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "已暂停",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = themeTextGrey(),
        )
    }
}

@Composable
private fun QueuedSection() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = themeTextGrey().copy(alpha = 0.5f),
            trackColor = BubbleBlue.copy(alpha = 0.15f),
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "排队中...",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = themeTextGrey(),
        )
    }
}

@Composable
private fun FailedSection(error: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(32.dp),
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "下载失败",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.error,
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = error,
            fontSize = 13.sp,
            color = themeTextGrey(),
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun IdleSection(totalMb: Long) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = Color.Transparent,
            trackColor = BubbleBlue.copy(alpha = 0.15f),
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "准备下载 ($totalMb MB)",
            fontSize = 14.sp,
            color = themeTextGrey(),
        )
    }
}

@Composable
private fun QuoteCard(currentQuoteIndex: Int) {
    val quote = DownloadQuotes.ALL_QUOTES[currentQuoteIndex]

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Crossfade(
            targetState = quote,
            animationSpec = tween(durationMillis = 500),
            label = "quote_crossfade",
        ) { currentQuote ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = currentQuote.text,
                    fontSize = 16.sp,
                    color = themeTextDark(),
                    textAlign = TextAlign.Center,
                    lineHeight = 26.sp,
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "-- ${currentQuote.author}",
                    fontSize = 13.sp,
                    color = themeTextGrey(),
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun ActionButtons(
    isPaused: Boolean,
    status: DownloadStatus?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            status == DownloadStatus.DOWNLOADING || status == DownloadStatus.QUEUED -> {
                OutlinedButton(
                    onClick = onPause,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(vertical = 12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = BubbleBlue),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, BubbleBlue),
                ) {
                    Icon(
                        imageVector = Icons.Default.Pause,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "暂停", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }

            isPaused || status == DownloadStatus.PAUSED -> {
                OutlinedButton(
                    onClick = onResume,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(vertical = 12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = BubbleBlue),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, BubbleBlue),
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "恢复", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }

            else -> {
                OutlinedButton(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(vertical = 12.dp),
                ) {
                    Text(text = "--", fontSize = 14.sp, color = themeTextGrey())
                }
            }
        }

        TextButton(
            onClick = onCancel,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            Text(
                text = "取消下载",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun formatSpeed(bytesPerSec: Long): String {
    return when {
        bytesPerSec < 1024 -> "$bytesPerSec B/s"
        bytesPerSec < 1024 * 1024 -> "${bytesPerSec / 1024} KB/s"
        else -> String.format("%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
    }
}
