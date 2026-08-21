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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.llm.*
import top.hsyscn.opedrgent.service.ModelDownloadService
import top.hsyscn.opedrgent.ui.theme.ElevationTokens
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import top.hsyscn.opedrgent.ui.theme.SizeTokens
import top.hsyscn.opedrgent.ui.theme.SpacingTokens

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
                .padding(horizontal = SpacingTokens.xl),
            shape = ShapeTokens.extraLargeShape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = ElevationTokens.xl,
        ) {
            Column(
                modifier = Modifier.padding(SpacingTokens.xl),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = stringResource(R.string.model_selector_cd_download_model),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(SpacingTokens.xl),
                    )
                    Spacer(modifier = Modifier.width(SpacingTokens.md))
                    Text(
                        text = stringResource(R.string.model_selector_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_close), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Spacer(modifier = Modifier.height(SpacingTokens.lg))

                Text(
                    text = stringResource(R.string.model_selector_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(SpacingTokens.lg))

                val listState = rememberLazyListState()
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = SizeTokens.expandedContentMaxHeight),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(SpacingTokens.none),
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
                                modifier = Modifier.padding(vertical = SpacingTokens.sm),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(SpacingTokens.md))

                val usedSpace = remember { downloadManager.getTotalUsedSpaceMb() }
                Text(
                    text = stringResource(R.string.model_selector_storage_info, usedSpace),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
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
                            downloadManager.pauseDownload(model.id)
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
                if (isSelected) Modifier.border(SizeTokens.borderWidthLg, MaterialTheme.colorScheme.primary, ShapeTokens.mediumShape)
                else Modifier.border(SizeTokens.borderWidth, MaterialTheme.colorScheme.outlineVariant, ShapeTokens.mediumShape)
            ),
        shape = ShapeTokens.mediumShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.padding(SpacingTokens.md),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = modelInfo.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(SpacingTokens.xxs))
                    Text(
                        text = modelInfo.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(modifier = Modifier.width(SpacingTokens.sm))

                StatusBadge(isDownloaded.value, isLoaded.value, throttledProgress?.status)
            }

            Spacer(modifier = Modifier.height(SpacingTokens.md))

            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                        Icons.Default.Storage,
                        contentDescription = stringResource(R.string.model_selector_cd_model_size),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(SpacingTokens.md),
                    )
                Text(
                    text = "${modelInfo.sizeMb} MB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.width(SpacingTokens.md))

                if (modelInfo.supportsFunctionCalling) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            text = "FC",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = SpacingTokens.xs, vertical = SpacingTokens.xxs),
                        )
                    }
                    Spacer(modifier = Modifier.width(SpacingTokens.sm))
                }

                if (modelInfo.preferGpu) {
                    CapabilityBadge("GPU", MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(SpacingTokens.xs))
                }
                if (modelInfo.supportsImage) {
                    CapabilityBadge("IMG", MaterialTheme.colorScheme.tertiary)
                    Spacer(modifier = Modifier.width(SpacingTokens.xs))
                }
                if (modelInfo.supportsAudio) {
                    CapabilityBadge("AUD", MaterialTheme.colorScheme.secondary)
                    Spacer(modifier = Modifier.width(SpacingTokens.xs))
                }
                if (modelInfo.supportsThinking) {
                    CapabilityBadge("THK", MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.width(SpacingTokens.xs))
                }
                if (modelInfo.supportsSpecDec) {
                    CapabilityBadge("SD", MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(SpacingTokens.xs))
                }
            }

            Spacer(modifier = Modifier.height(SpacingTokens.sm))

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
                Spacer(modifier = Modifier.height(SpacingTokens.md))
                LinearProgressIndicator(
                    progress = { throttledProgress!!.progressPercent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(SpacingTokens.xxs)
                        .clip(ShapeTokens.extraSmallShape),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                )
                Spacer(modifier = Modifier.height(SpacingTokens.xs))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "${throttledProgress!!.downloadedMb.toInt()} MB / ${throttledProgress!!.totalMb.toInt()} MB",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = formatSpeed(throttledProgress!!.speedBytesPerSec),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        isLoaded -> Triple(stringResource(R.string.model_status_running), MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
        isDownloaded -> Triple(stringResource(R.string.model_status_downloaded), MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
        status == DownloadStatus.DOWNLOADING -> Triple(stringResource(R.string.model_status_downloading), MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.onTertiary)
        status == DownloadStatus.QUEUED -> Triple(stringResource(R.string.model_status_queued), MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
        status == DownloadStatus.PAUSED -> Triple(stringResource(R.string.model_status_paused), MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
        status == DownloadStatus.CANCELLED -> Triple(stringResource(R.string.model_status_cancelled), MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
        status == DownloadStatus.FAILED -> Triple(stringResource(R.string.model_status_failed), MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.onError)
        else -> Triple(null, MaterialTheme.colorScheme.surface.copy(alpha = 0f), MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (text != null) {
        Surface(shape = ShapeTokens.smallShape, color = containerColor) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                modifier = Modifier.padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xxs),
            )
        }
    }
}

@Composable
private fun CapabilityBadge(label: String, color: androidx.compose.ui.graphics.Color) {
    Surface(shape = ShapeTokens.extraSmallShape, color = color.copy(alpha = 0.1f)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = SpacingTokens.xs, vertical = SpacingTokens.xxs),
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
                shape = ShapeTokens.smallShape,
                color = MaterialTheme.colorScheme.primary,
            ) {
                Row(
                    modifier = Modifier
                        .clickable { /* 可选: 停止 */ }
                        .padding(horizontal = SpacingTokens.md, vertical = SpacingTokens.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = stringResource(R.string.model_status_cd_in_use),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(SpacingTokens.md),
                    )
                    Spacer(modifier = Modifier.width(SpacingTokens.xs))
                    Text(
                        text = stringResource(R.string.model_status_in_use),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }

        isDownloaded -> {
            OutlinedButton(
                onClick = onLoadAndSelect,
                shape = ShapeTokens.smallShape,
                contentPadding = PaddingValues(horizontal = SpacingTokens.md, vertical = SpacingTokens.xs),
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.action_load),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(SpacingTokens.md),
                )
                Spacer(modifier = Modifier.width(SpacingTokens.xs))
                Text(text = stringResource(R.string.action_load), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }

        progress?.status == DownloadStatus.DOWNLOADING -> {
            TextButton(
                onClick = { scope.launch { downloadManager.cancelDownload(modelInfo.id) } },
            ) {
                Text(text = stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            }
        }

        progress?.status == DownloadStatus.FAILED -> {
            TextButton(
                onClick = { downloadManager.startDownload(modelInfo) },
            ) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.model_selector_cd_retry), modifier = Modifier.size(SpacingTokens.md))
                Spacer(modifier = Modifier.width(SpacingTokens.xs))
                Text(text = stringResource(R.string.action_retry), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }

        else -> {
            Button(
                onClick = {
                    downloadManager.startDownload(modelInfo)
                    onShowDownloadDialog(modelInfo)
                },
                shape = ShapeTokens.smallShape,
                contentPadding = PaddingValues(horizontal = SpacingTokens.lg, vertical = SpacingTokens.xs),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = stringResource(R.string.cd_download),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(SpacingTokens.md),
                )
                Spacer(modifier = Modifier.width(SpacingTokens.xs))
                Text(text = stringResource(R.string.action_download), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

private fun formatSpeed(bytesPerSec: Long): String {
    return when {
        bytesPerSec < 1024 -> "$bytesPerSec B/s"
        bytesPerSec < 1024 * 1024 -> "${bytesPerSec / 1024} KB/s"
        else -> String.format(java.util.Locale.US, "%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
    }
}
