package top.hsyscn.opedrgent.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.llm.DownloadProgress
import top.hsyscn.opedrgent.llm.DownloadStatus
import top.hsyscn.opedrgent.llm.LocalModelInfo
import top.hsyscn.opedrgent.ui.theme.ElevationTokens
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import top.hsyscn.opedrgent.ui.theme.SpacingTokens

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
            title = { Text(stringResource(R.string.download_confirm_cancel_title)) },
            text = { Text(stringResource(R.string.download_confirm_cancel_desc)) },
            confirmButton = {
                TextButton(onClick = onConfirmCancel) {
                    Text(stringResource(R.string.download_action_confirm_cancel), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelConfirm = false }) {
                    Text(stringResource(R.string.download_action_continue))
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
                .padding(horizontal = SpacingTokens.xl),
            shape = ShapeTokens.extraLargeShape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = ElevationTokens.xl,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(SpacingTokens.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(SpacingTokens.lg))

                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = stringResource(R.string.download_cd_download_model),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(SpacingTokens.xxl),
                )

                Spacer(modifier = Modifier.height(SpacingTokens.lg))

                Text(
                    text = stringResource(R.string.download_downloading_title, modelInfo.displayName),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(SpacingTokens.sm))

                Text(
                    text = modelInfo.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(SpacingTokens.xl))

                when (progress?.status) {
                    DownloadStatus.DOWNLOADING -> {
                        ProgressSection(progress = progress)
                    }
                    DownloadStatus.PAUSED -> {
                        PausedSection(progress = progress)
                    }
                    DownloadStatus.QUEUED -> {
                        QueuedSection()
                    }
                    DownloadStatus.FAILED -> {
                        FailedSection(error = progress.error ?: stringResource(R.string.download_unknown_error))
                    }
                    else -> {
                        IdleSection(totalMb = modelInfo.sizeMb)
                    }
                }

                Spacer(modifier = Modifier.height(SpacingTokens.xl))

                QuoteCard(currentQuoteIndex = shuffledIndices[quotePointer])

                Spacer(modifier = Modifier.height(SpacingTokens.xl))

                Text(
                    text = stringResource(R.string.download_encouragement),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(SpacingTokens.xl))

                ActionButtons(
                    isPaused = isPaused,
                    status = progress?.status,
                    onPause = onPause,
                    onResume = onResume,
                    onCancel = { showCancelConfirm = true },
                )

                Spacer(modifier = Modifier.height(SpacingTokens.md))
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
                        .height(SpacingTokens.xs)
                        .clip(ShapeTokens.smallShape),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                )
            }
            Spacer(modifier = Modifier.width(SpacingTokens.md))
            Text(
                text = "${progress.progressPercent.toInt()}%",
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
                text = "${progress.downloadedMb.toInt()} MB / ${progress.totalMb.toInt()} MB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatSpeed(progress.speedBytesPerSec),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            .height(SpacingTokens.xs)
                            .clip(ShapeTokens.smallShape),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    )
                }
                Spacer(modifier = Modifier.width(SpacingTokens.md))
                Text(
                    text = "${progress.progressPercent.toInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(SpacingTokens.md))

            Text(
                text = "${progress.downloadedMb.toInt()} MB / ${progress.totalMb.toInt()} MB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(SpacingTokens.sm))

        Text(
            text = stringResource(R.string.state_paused),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                .height(SpacingTokens.xs)
                .clip(ShapeTokens.smallShape),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
        )

        Spacer(modifier = Modifier.height(SpacingTokens.md))

        Text(
            text = stringResource(R.string.download_status_queued),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            contentDescription = stringResource(R.string.download_cd_failed),
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(SpacingTokens.xxl),
        )

        Spacer(modifier = Modifier.height(SpacingTokens.sm))

        Text(
            text = stringResource(R.string.download_failed),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
        )

        Spacer(modifier = Modifier.height(SpacingTokens.xs))

        Text(
            text = error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                .height(SpacingTokens.xs)
                .clip(ShapeTokens.smallShape),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
        )

        Spacer(modifier = Modifier.height(SpacingTokens.md))

        Text(
            text = stringResource(R.string.download_preparing_with_size, totalMb),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun QuoteCard(currentQuoteIndex: Int) {
    val quote = DownloadQuotes.ALL_QUOTES[currentQuoteIndex]

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = ShapeTokens.mediumShape,
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
private fun ActionButtons(
    isPaused: Boolean,
    status: DownloadStatus?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.md),
    ) {
        when {
            status == DownloadStatus.DOWNLOADING || status == DownloadStatus.QUEUED -> {
                OutlinedButton(
                    onClick = onPause,
                    modifier = Modifier.weight(1f),
                    shape = ShapeTokens.mediumShape,
                    contentPadding = PaddingValues(vertical = SpacingTokens.md),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
                ) {
                    Icon(
                        imageVector = Icons.Default.Pause,
                        contentDescription = stringResource(R.string.cd_pause),
                        modifier = Modifier.size(SpacingTokens.md),
                    )
                    Spacer(modifier = Modifier.width(SpacingTokens.xs))
                    Text(text = stringResource(R.string.action_pause), style = MaterialTheme.typography.labelLarge)
                }
            }

            isPaused || status == DownloadStatus.PAUSED -> {
                OutlinedButton(
                    onClick = onResume,
                    modifier = Modifier.weight(1f),
                    shape = ShapeTokens.mediumShape,
                    contentPadding = PaddingValues(vertical = SpacingTokens.md),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.action_resume),
                        modifier = Modifier.size(SpacingTokens.md),
                    )
                    Spacer(modifier = Modifier.width(SpacingTokens.xs))
                    Text(text = stringResource(R.string.action_resume), style = MaterialTheme.typography.labelLarge)
                }
            }

            else -> {
                OutlinedButton(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.weight(1f),
                    shape = ShapeTokens.mediumShape,
                    contentPadding = PaddingValues(vertical = SpacingTokens.md),
                ) {
                    Text(text = "--", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        TextButton(
            onClick = onCancel,
            modifier = Modifier.weight(1f),
            shape = ShapeTokens.mediumShape,
            contentPadding = PaddingValues(vertical = SpacingTokens.md),
        ) {
            Text(
                text = stringResource(R.string.download_action_cancel),
                style = MaterialTheme.typography.labelLarge,
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
