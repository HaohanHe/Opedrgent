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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import top.hsyscn.opedrgent.ui.theme.AccentBlue
import top.hsyscn.opedrgent.ui.theme.themeTextDark
import top.hsyscn.opedrgent.ui.theme.themeTextGrey

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

                androidx.compose.material3.Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    tint = AccentBlue,
                    modifier = Modifier.size(48.dp),
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "正在下载 $modelName",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = themeTextDark(),
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = modelDescription,
                    fontSize = 14.sp,
                    color = themeTextGrey(),
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(24.dp))

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
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = AccentBlue,
                                trackColor = AccentBlue.copy(alpha = 0.15f),
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = statusDetail.ifEmpty { "解压中..." },
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = themeTextDark(),
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
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = themeTextGrey().copy(alpha = 0.5f),
                                trackColor = AccentBlue.copy(alpha = 0.15f),
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = statusDetail.ifEmpty { "切换下载源..." },
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = themeTextGrey(),
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
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(32.dp),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(text = "下载失败", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = statusDetail.ifEmpty { "未知错误" },
                                fontSize = 13.sp,
                                color = themeTextGrey(),
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
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = Color.Transparent,
                                trackColor = AccentBlue.copy(alpha = 0.15f),
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = if (totalMb > 0) "准备下载 ($totalMb MB)" else "准备中...",
                                fontSize = 14.sp,
                                color = themeTextGrey(),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 古诗轮播卡片
                SttQuoteCard(currentQuoteIndex = shuffledIndices[quotePointer])

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "好东西，就要来了...",
                    fontSize = 13.sp,
                    color = themeTextGrey(),
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(20.dp))

                // 取消按钮
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        text = "取消下载",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun SttQuoteCard(currentQuoteIndex: Int) {
    val quote = DownloadQuotes.ALL_QUOTES[currentQuoteIndex]

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
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
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = AccentBlue,
                    trackColor = AccentBlue.copy(alpha = 0.15f),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "$percent%",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = AccentBlue,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (totalMb > 0) "${downloadedMb} MB / ${totalMb} MB" else "${downloadedMb} MB",
                fontSize = 13.sp,
                color = themeTextGrey(),
            )
            if (speedText.isNotEmpty()) {
                Text(
                    text = speedText,
                    fontSize = 13.sp,
                    color = themeTextGrey(),
                )
            }
        }
    }
}
