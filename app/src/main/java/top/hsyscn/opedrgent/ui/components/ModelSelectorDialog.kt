package top.hsyscn.opedrgent.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.hsyscn.opedrgent.llm.*
import top.hsyscn.opedrgent.service.ModelDownloadService
import top.hsyscn.opedrgent.ui.theme.AccentBlue
import top.hsyscn.opedrgent.ui.theme.BubbleBlue
import top.hsyscn.opedrgent.ui.theme.themeTextDark
import top.hsyscn.opedrgent.ui.theme.themeTextGrey

@Composable
fun ModelSelectorDialog(
    onDismiss: () -> Unit,
    onSelectModel: (LocalModelInfo) -> Unit,
    downloadManager: ModelDownloadManager,
    localEngine: LocalLlmEngine,
    currentModelId: String? = null,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var showFullDownloadDialog by remember { mutableStateOf(false) }
    var downloadingModel by remember { mutableStateOf<LocalModelInfo?>(null) }
    var isDownloadPaused by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        tint = BubbleBlue,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "本地模型管理",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = themeTextDark(),
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "关闭", tint = themeTextGrey())
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "选择要下载和使用的 Gemma 4 本地模型。模型将在设备上完全离线运行。",
                    color = themeTextGrey(),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )

                Spacer(modifier = Modifier.height(16.dp))

                val listState = rememberLazyListState()
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    items(
                        items = AvailableLocalModels.MODELS,
                        key = { it.id },
                    ) { modelInfo ->
                        ModelCard(
                            modelInfo = modelInfo,
                            downloadManager = downloadManager,
                            localEngine = localEngine,
                            isSelected = currentModelId == modelInfo.id,
                            onLoadAndSelect = {
                                scope.launch {
                                    onSelectModel(modelInfo)
                                    onDismiss()
                                }
                            },
                            onShowDownloadDialog = { model ->
                                showFullDownloadDialog = true
                                downloadingModel = model
                            },
                        )

                        if (modelInfo != AvailableLocalModels.MODELS.last()) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 8.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                val usedSpace = remember { downloadManager.getTotalUsedSpaceMb() }
                Text(
                    text = "已使用空间: ${usedSpace} MB | 数据存储于应用私有目录",
                    color = themeTextGrey().copy(alpha = 0.7f),
                    fontSize = 11.sp,
                )

                if (showFullDownloadDialog && downloadingModel != null) {
                    val model = downloadingModel!!
                    var dlProgress by remember(model.id) { mutableStateOf<DownloadProgress?>(null) }

                    LaunchedEffect(model.id) {
                        downloadManager.observeProgress(model.id).collect { progress ->
                            dlProgress = progress
                        }
                    }

                    ModelDownloadDialog(
                        modelInfo = model,
                        progress = dlProgress,
                        isPaused = isDownloadPaused,
                        onDismiss = {
                            showFullDownloadDialog = false
                        },
                        onPause = {
                            isDownloadPaused = true
                            downloadManager.cancelDownload(model.id)
                            ModelDownloadService.setPaused(true)
                        },
                        onResume = {
                            isDownloadPaused = false
                            downloadManager.startDownload(model)
                            ModelDownloadService.setPaused(false)
                        },
                        onCancel = {},
                        onConfirmCancel = {
                            downloadManager.cancelDownload(downloadingModel!!.id)
                            ModelDownloadService.stop(context)
                            showFullDownloadDialog = false
                            downloadingModel = null
                            isDownloadPaused = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelCard(
    modelInfo: LocalModelInfo,
    downloadManager: ModelDownloadManager,
    localEngine: LocalLlmEngine,
    isSelected: Boolean,
    onLoadAndSelect: () -> Unit,
    onShowDownloadDialog: (LocalModelInfo) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var downloadProgress by remember { mutableStateOf<DownloadProgress?>(null) }
    var throttledProgress by remember { mutableStateOf<DownloadProgress?>(null) }

    LaunchedEffect(modelInfo.id) {
        downloadManager.observeProgress(modelInfo.id).collect { progress ->
            downloadProgress = progress
            if (progress.status == DownloadStatus.DOWNLOADING) {
                delay(300L)
                if (downloadProgress == progress) {
                    throttledProgress = progress
                }
            } else {
                throttledProgress = progress
            }
        }
    }

    val isDownloaded = remember(modelInfo.id) {
        derivedStateOf { localEngine.isModelDownloaded(modelInfo) }
    }

    val isLoaded = remember(modelInfo.id) {
        derivedStateOf { localEngine.currentModelId == modelInfo.id && localEngine.isReady }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isSelected) Modifier.border(2.dp, BubbleBlue, RoundedCornerShape(12.dp))
                else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = modelInfo.displayName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = themeTextDark(),
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = modelInfo.description,
                        fontSize = 12.sp,
                        color = themeTextGrey(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                StatusBadge(isDownloaded.value, isLoaded.value, throttledProgress?.status)
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Storage,
                    contentDescription = null,
                    tint = themeTextGrey(),
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "${modelInfo.sizeMb} MB",
                    fontSize = 12.sp,
                    color = themeTextGrey(),
                )

                Spacer(modifier = Modifier.width(12.dp))

                if (modelInfo.supportsFunctionCalling) {
                    Surface(
                        shape = CircleShape,
                        color = BubbleBlue.copy(alpha = 0.1f),
                    ) {
                        Text(
                            text = "FC",
                            fontSize = 10.sp,
                            color = BubbleBlue,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                if (modelInfo.preferGpu) {
                    CapabilityBadge("GPU", MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(6.dp))
                }
                if (modelInfo.supportsImage) {
                    CapabilityBadge("IMG", MaterialTheme.colorScheme.tertiary)
                    Spacer(modifier = Modifier.width(6.dp))
                }
                if (modelInfo.supportsAudio) {
                    CapabilityBadge("AUD", MaterialTheme.colorScheme.secondary)
                    Spacer(modifier = Modifier.width(6.dp))
                }
                if (modelInfo.supportsThinking) {
                    CapabilityBadge("THK", MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.width(6.dp))
                }
                if (modelInfo.supportsSpecDec) {
                    CapabilityBadge("SD", AccentBlue)
                    Spacer(modifier = Modifier.width(6.dp))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                ActionButton(
                    isDownloaded = isDownloaded.value,
                    isLoaded = isLoaded.value,
                    progress = throttledProgress,
                    modelInfo = modelInfo,
                    downloadManager = downloadManager,
                    localEngine = localEngine,
                    onLoadAndSelect = onLoadAndSelect,
                    onShowDownloadDialog = onShowDownloadDialog,
                )
            }

            if (throttledProgress?.status == DownloadStatus.DOWNLOADING) {
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { throttledProgress!!.progressPercent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = BubbleBlue,
                    trackColor = BubbleBlue.copy(alpha = 0.15f),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "${throttledProgress!!.downloadedMb.toInt()} MB / ${throttledProgress!!.totalMb.toInt()} MB",
                        fontSize = 10.sp,
                        color = themeTextGrey(),
                    )
                    Text(
                        text = formatSpeed(throttledProgress!!.speedBytesPerSec),
                        fontSize = 10.sp,
                        color = themeTextGrey(),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(
    isDownloaded: Boolean,
    isLoaded: Boolean,
    status: DownloadStatus?
) {
    val (text, containerColor, contentColor) = when {
        isLoaded -> Triple("运行中", BubbleBlue, Color.White)
        isDownloaded -> Triple("已下载", MaterialTheme.colorScheme.primary, Color.White)
        status == DownloadStatus.DOWNLOADING -> Triple("下载中", MaterialTheme.colorScheme.tertiary, Color.White)
        status == DownloadStatus.QUEUED -> Triple("排队中", themeTextGrey().copy(alpha = 0.8f), Color.White)
        status == DownloadStatus.PAUSED -> Triple("已暂停", themeTextGrey(), Color.White)
        status == DownloadStatus.CANCELLED -> Triple("已取消", themeTextGrey().copy(alpha = 0.6f), Color.White)
        status == DownloadStatus.FAILED -> Triple("失败", MaterialTheme.colorScheme.error, Color.White)
        else -> Triple(null, Color.Transparent, themeTextGrey())
    }

    if (text != null) {
        Surface(shape = RoundedCornerShape(6.dp), color = containerColor) {
            Text(
                text = text,
                fontSize = 10.sp,
                color = contentColor,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun CapabilityBadge(label: String, color: Color) {
    Surface(shape = RoundedCornerShape(4.dp), color = color.copy(alpha = 0.1f)) {
        Text(
            text = label,
            fontSize = 9.sp,
            color = color,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun ActionButton(
    isDownloaded: Boolean,
    isLoaded: Boolean,
    progress: DownloadProgress?,
    modelInfo: LocalModelInfo,
    downloadManager: ModelDownloadManager,
    localEngine: LocalLlmEngine,
    onLoadAndSelect: () -> Unit,
    onShowDownloadDialog: (LocalModelInfo) -> Unit,
) {
    val scope = rememberCoroutineScope()

    when {
        isLoaded -> {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = BubbleBlue,
            ) {
                Row(
                    modifier = Modifier
                        .clickable { /* 可选: 停止 */ }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "使用中",
                        fontSize = 12.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        isDownloaded -> {
            OutlinedButton(
                onClick = onLoadAndSelect,
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = BubbleBlue,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = "加载", fontSize = 12.sp, color = BubbleBlue)
            }
        }

        progress?.status == DownloadStatus.DOWNLOADING -> {
            TextButton(
                onClick = { scope.launch { downloadManager.cancelDownload(modelInfo.id) } },
            ) {
                Text(text = "取消", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
        }

        progress?.status == DownloadStatus.FAILED -> {
            TextButton(
                onClick = { downloadManager.startDownload(modelInfo) },
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = "重试", fontSize = 12.sp, color = BubbleBlue)
            }
        }

        else -> {
            Button(
                onClick = {
                    downloadManager.startDownload(modelInfo)
                    onShowDownloadDialog(modelInfo)
                },
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BubbleBlue),
            ) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = "下载", fontSize = 12.sp, color = Color.White)
            }
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
