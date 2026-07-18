package top.hsyscn.opedrgent.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.stt.AudioProcessor
import top.hsyscn.opedrgent.stt.SttResult
import top.hsyscn.opedrgent.stt.SttSegment
import top.hsyscn.opedrgent.ui.theme.ElevationTokens
import top.hsyscn.opedrgent.ui.theme.OpedrgentTheme
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import top.hsyscn.opedrgent.ui.theme.SpacingTokens
import androidx.compose.ui.tooling.preview.Preview

@Composable
fun SttResultCard(
    result: SttResult?,
    error: String?,
    onCopy: () -> Unit,
    onSendToLlm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (result == null && error == null) return

    val isSuccess = error == null && result != null
    val cardCd = stringResource(R.string.stt_result_cd_card)

    AnimatedVisibility(
        visible = true,
        enter = slideInVertically(
            initialOffsetY = { it / 3 },
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        ) + fadeIn(),
    ) {
        Card(
            shape = ShapeTokens.largeShape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = ElevationTokens.md),
            modifier = modifier
                .fillMaxWidth()
                .semantics { contentDescription = cardCd }
                .padding(horizontal = SpacingTokens.md, vertical = SpacingTokens.xs),
        ) {
            Column(modifier = Modifier.padding(SpacingTokens.lg)) {
                ResultTitleBar(isSuccess = isSuccess, onDismiss = onDismiss)

                Spacer(Modifier.height(SpacingTokens.md))

                if (error != null) {
                    ErrorContent(error = error)
                } else if (result != null) {
                    SuccessContent(result = result)
                }

                Spacer(Modifier.height(SpacingTokens.md))

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Spacer(Modifier.height(SpacingTokens.md))

                ActionButtons(
                    isSuccess = isSuccess,
                    isLoading = remember { mutableStateOf(false) },
                    onCopy = onCopy,
                    onSendToLlm = onSendToLlm,
                )
            }
        }
    }
}

@Composable
private fun ResultTitleBar(isSuccess: Boolean, onDismiss: () -> Unit) {
    val iconCd = if (isSuccess) {
        stringResource(R.string.stt_result_cd_success)
    } else {
        stringResource(R.string.stt_result_cd_failed)
    }
    val closeCd = stringResource(R.string.stt_result_cd_close)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
            contentDescription = iconCd,
            tint = if (isSuccess)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.error,
            modifier = Modifier.size(SpacingTokens.xl),
        )
        Spacer(Modifier.width(SpacingTokens.md))
        Text(
            text = if (isSuccess) stringResource(R.string.stt_result_title) else stringResource(R.string.stt_result_failed_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .size(36.dp)
                .semantics { contentDescription = closeCd },
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.cd_close),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun ErrorContent(error: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = stringResource(R.string.stt_result_cd_error),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(SpacingTokens.xs))
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(SpacingTokens.sm))
        Text(
            text = stringResource(R.string.stt_result_error_possible_causes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SuccessContent(result: SttResult) {
    var statsExpanded by rememberSaveable { mutableStateOf(false) }
    val charCount by remember(result.text) { mutableIntStateOf(result.text.length) }
    val textCd = stringResource(R.string.stt_result_cd_text_word_count, charCount)

    Column {
        Text(
            text = result.text.ifEmpty { stringResource(R.string.stt_result_empty) },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = Int.MAX_VALUE,
            overflow = TextOverflow.Clip,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp)
                .verticalScroll(rememberScrollState())
                .semantics { contentDescription = textCd },
        )

        Spacer(Modifier.height(SpacingTokens.xs))

        Text(
            text = stringResource(R.string.stt_result_word_count, charCount),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.End),
        )

        Spacer(Modifier.height(SpacingTokens.sm))

        StatsBar(
            result = result,
            expanded = statsExpanded,
            onToggleExpand = { statsExpanded = !statsExpanded },
        )

        AnimatedVisibility(visible = statsExpanded) {
            SegmentList(segments = result.segments)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StatsBar(
    result: SttResult,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
) {
    val durationLabel = stringResource(R.string.stt_result_duration_label)
    val charUnit = stringResource(R.string.stt_result_char_count_unit)
    val statsText = remember(result, durationLabel, charUnit) {
        buildString {
            append("$durationLabel ${AudioProcessor.formatDuration(result.durationMs)}")
            append("  \u00B7  ")
            append("${result.text.length} $charUnit")
            append("  \u00B7  ")
            append(result.engineType.name)
            if (result.modelUsed.isNotEmpty()) {
                append(" (${result.modelUsed})")
            }
            if (result.processingTimeMs > 0) {
                append("  \u00B7  ")
                append("${result.processingTimeMs}ms")
            }
        }
    }

    val statsCd = if (expanded) {
        stringResource(R.string.stt_result_cd_stats_expanded, statsText)
    } else {
        stringResource(R.string.stt_result_cd_stats_collapsed, statsText)
    }
    Surface(
        shape = ShapeTokens.smallShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
        onClick = onToggleExpand,
        modifier = Modifier
            .fillMaxWidth()
            .clip(ShapeTokens.smallShape)
            .semantics { contentDescription = statsCd },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = SpacingTokens.md, vertical = SpacingTokens.sm),
        ) {
            Text(
                text = statsText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) stringResource(R.string.cd_collapse) else stringResource(R.string.cd_expand),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun SegmentList(segments: List<SttSegment>) {
    if (segments.isEmpty()) return

    Column(
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.xs),
        modifier = Modifier.padding(top = SpacingTokens.sm).fillMaxWidth(),
    ) {
        segments.forEachIndexed { index, segment ->
            val segmentCd = stringResource(
                R.string.stt_result_cd_segment_confidence,
                index + 1,
                (segment.confidence * 100).toInt(),
            )
            Surface(
                shape = ShapeTokens.smallShape,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = segmentCd },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = SpacingTokens.md, vertical = SpacingTokens.xs),
                ) {
                    Text(
                        text = "${segment.startTimeMs / 1000}s - ${segment.endTimeMs / 1000}s",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(90.dp),
                    )
                    Text(
                        text = segment.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${(segment.confidence * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            segment.confidence >= 0.9f -> MaterialTheme.colorScheme.primary
                            segment.confidence >= 0.7f -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionButtons(
    isSuccess: Boolean,
    isLoading: androidx.compose.runtime.MutableState<Boolean>,
    onCopy: () -> Unit,
    onSendToLlm: () -> Unit,
) {
    var copyFeedbackShown by remember { mutableStateOf(false) }
    val copyCd = stringResource(R.string.stt_result_cd_copy)
    val sendCd = stringResource(R.string.stt_result_cd_send_to_ai)

    Row(
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.md),
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedButton(
            onClick = {
                if (!isLoading.value) {
                    onCopy()
                    copyFeedbackShown = true
                }
            },
            enabled = isSuccess && !isLoading.value,
            shape = ShapeTokens.mediumShape,
            modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .semantics { contentDescription = copyCd },
        ) {
            if (copyFeedbackShown) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = stringResource(R.string.msg_copied),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(SpacingTokens.xs))
                Text(stringResource(R.string.msg_copied), style = MaterialTheme.typography.labelLarge)
            } else {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.cd_copy),
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(SpacingTokens.xs))
                Text(stringResource(R.string.action_copy), style = MaterialTheme.typography.labelLarge)
            }
        }

        Button(
            onClick = {
                if (!isLoading.value) {
                    isLoading.value = true
                    onSendToLlm()
                }
            },
            enabled = isSuccess && !isLoading.value,
            shape = ShapeTokens.mediumShape,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .semantics { contentDescription = sendCd },
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = stringResource(R.string.stt_result_cd_send_ai),
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(SpacingTokens.xs))
            Text(stringResource(R.string.stt_result_send_to_ai), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Preview(showBackground = true, name = "Light")
@Composable
private fun SttResultCardPreview() {
    OpedrgentTheme(darkTheme = false) {
        val sampleResult = SttResult(
            text = "stringResource(R.string.sample_transcript)",
            confidence = 0.92f,
            segments = listOf(
                SttSegment(
                    text = "stringResource(R.string.sample_transcript)",
                    startTimeMs = 0,
                    endTimeMs = 3500,
                    confidence = 0.92f,
                ),
            ),
            durationMs = 3500,
            processingTimeMs = 128,
            modelUsed = "zipformer",
        )
        SttResultCard(
            result = sampleResult,
            error = null,
            onCopy = {},
            onSendToLlm = {},
            onDismiss = {},
        )
    }
}

@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES, name = "Dark")
@Composable
private fun SttResultCardDarkPreview() {
    OpedrgentTheme(darkTheme = true) {
        val sampleResult = SttResult(
            text = "stringResource(R.string.sample_transcript)",
            confidence = 0.92f,
            segments = listOf(
                SttSegment(
                    text = "stringResource(R.string.sample_transcript)",
                    startTimeMs = 0,
                    endTimeMs = 3500,
                    confidence = 0.92f,
                ),
            ),
            durationMs = 3500,
            processingTimeMs = 128,
            modelUsed = "zipformer",
        )
        SttResultCard(
            result = sampleResult,
            error = null,
            onCopy = {},
            onSendToLlm = {},
            onDismiss = {},
        )
    }
}
