package top.hsyscn.opedrgent.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.ui.SproutingState
import top.hsyscn.opedrgent.ui.theme.ElevationTokens
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import top.hsyscn.opedrgent.ui.theme.SizeTokens
import top.hsyscn.opedrgent.ui.theme.SpacingTokens
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

@Composable
private fun phaseLabel(state: SproutingState): Pair<String, String> = when (state) {
    SproutingState.PHASE1 -> stringResource(R.string.sprout_phase_seed) to stringResource(R.string.sprout_phase_seed_progress)
    SproutingState.PHASE2 -> stringResource(R.string.sprout_phase_connection) to stringResource(R.string.sprout_phase_connection_progress)
    SproutingState.PHASE3 -> stringResource(R.string.sprout_phase_insight) to stringResource(R.string.sprout_phase_insight_progress)
    SproutingState.PHASE4 -> stringResource(R.string.sprout_phase_quote) to stringResource(R.string.sprout_phase_quote_progress)
    else -> "" to ""
}

@Composable
fun SproutResultView(
    markdownReport: String,
    sproutingState: SproutingState,
    qualityScore: Int? = null,
    processingTimeMs: Long? = null,
    onCopy: () -> Unit,
    onContinueChat: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    if (markdownReport.isEmpty() && sproutingState == SproutingState.IDLE) return

    Card(
        shape = ShapeTokens.largeShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = ElevationTokens.md),
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = context.getString(R.string.sprout_cd_report_view) }
            .padding(horizontal = SpacingTokens.md, vertical = SpacingTokens.xs),
    ) {
        Column(modifier = Modifier.padding(SpacingTokens.lg)) {
            SproutTitleBar(sproutingState = sproutingState, qualityScore = qualityScore, onDismiss = onDismiss)

            Spacer(Modifier.height(SpacingTokens.md))

            AnimatedContent(
                targetState = sproutingState,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "sproutingState",
            ) { state ->
                when (state) {
                    SproutingState.IDLE -> {}
                    SproutingState.PHASE1, SproutingState.PHASE2, SproutingState.PHASE3, SproutingState.PHASE4 ->
                        ProcessingPhase(currentPhase = state, onCancel = onDismiss)
                    SproutingState.DONE ->
                        DonePhase(
                            report = markdownReport,
                            qualityScore = qualityScore,
                            processingTimeMs = processingTimeMs,
                        )
                    SproutingState.ERROR -> ErrorPhase(onRetry = onContinueChat)
                    SproutingState.CANCELLED -> CancelledPhase(onRestart = onContinueChat)
                }
            }

            if (sproutingState == SproutingState.DONE && markdownReport.isNotEmpty()) {
                Spacer(Modifier.height(SpacingTokens.md))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(Modifier.height(SpacingTokens.md))
                DoneActionButtons(
                    onCopy = onCopy,
                    onContinueChat = onContinueChat,
                )
            }
        }
    }
}

@Composable
private fun SproutTitleBar(sproutingState: SproutingState, qualityScore: Int?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = when (sproutingState) {
                SproutingState.IDLE, SproutingState.DONE -> stringResource(R.string.sprout_title)
                SproutingState.PHASE1, SproutingState.PHASE2, SproutingState.PHASE3, SproutingState.PHASE4 -> phaseLabel(sproutingState).first.ifBlank { stringResource(R.string.sprout_title) }
                SproutingState.ERROR -> stringResource(R.string.sprout_title_error)
                SproutingState.CANCELLED -> stringResource(R.string.sprout_title_cancelled)
            },
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.width(SpacingTokens.sm))
        Text(
            text = when (sproutingState) {
                SproutingState.IDLE -> stringResource(R.string.sprout_report_title)
                SproutingState.PHASE1, SproutingState.PHASE2, SproutingState.PHASE3, SproutingState.PHASE4 -> phaseLabel(sproutingState).second.ifBlank { stringResource(R.string.sprout_status_processing) }
                SproutingState.DONE -> stringResource(R.string.sprout_status_done)
                SproutingState.ERROR -> stringResource(R.string.sprout_status_failed)
                SproutingState.CANCELLED -> stringResource(R.string.sprout_title_cancelled)
            },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))

        if (sproutingState == SproutingState.DONE && qualityScore != null) {
            QualityScoreBadge(score = qualityScore)
            Spacer(Modifier.width(SpacingTokens.sm))
        }

        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .size(SizeTokens.iconButtonSize)
            .semantics { contentDescription = context.getString(R.string.sprout_cd_close_report) },
        ) {
            // 装饰性关闭图标，外层 IconButton 已提供“关闭发芽报告”语义
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(SizeTokens.iconLg),
            )
        }
    }
}

@Composable
private fun QualityScoreBadge(score: Int) {
    val context = LocalContext.current
    val (badgeColor, label) = when {
        score >= 85 -> Pair(MaterialTheme.colorScheme.primary, stringResource(R.string.quality_score_excellent))
        score >= 70 -> Pair(MaterialTheme.colorScheme.tertiary, stringResource(R.string.quality_score_good))
        score >= 50 -> Pair(MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f), stringResource(R.string.quality_score_average))
        else -> Pair(MaterialTheme.colorScheme.error, stringResource(R.string.quality_score_needs_improvement))
    }

    Surface(
        shape = CircleShape,
        color = badgeColor.copy(alpha = 0.15f),
        modifier = Modifier.semantics { contentDescription = context.getString(R.string.quality_score_description, score, label) },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xs),
        ) {
            Text(
                text = "$score",
                style = MaterialTheme.typography.labelLarge,
                color = badgeColor,
            )
            Spacer(Modifier.width(SpacingTokens.xs))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = badgeColor,
            )
        }
    }
}

@Composable
private fun ProcessingPhase(currentPhase: SproutingState, onCancel: () -> Unit) {
    var elapsedSeconds by remember { mutableIntStateOf(0) }

    LaunchedEffect(currentPhase) {
        elapsedSeconds = 0
        while (isActive) {
            delay(1000L)
            elapsedSeconds++
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "sproutGrow")
    val growProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "growProgress",
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxWidth().height(SizeTokens.sproutBannerHeight),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            SproutGrowingAnimation(progress = growProgress)

            Spacer(Modifier.height(SpacingTokens.lg))

            PhaseIndicatorDots(currentPhase = currentPhase)

            Spacer(Modifier.height(SpacingTokens.md))

            Text(
                text = formatElapsedTime(elapsedSeconds),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(SpacingTokens.sm))

            TextButton(
                onClick = onCancel,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
}

@Composable
private fun SproutGrowingAnimation(progress: Float) {
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary

    Canvas(modifier = Modifier.size(SizeTokens.sproutPlantCanvasSize)) {
        val stemHeight = size.height * 0.5f * progress
        val leafSize = size.width * 0.25f

        drawCircle(
            color = tertiaryColor.copy(alpha = 0.2f + 0.3f * progress),
            radius = size.width * 0.18f,
            center = Offset(size.width / 2, size.height * 0.78f),
        )

        val stemWidthPx = SizeTokens.sproutStemWidth.toPx()
        val stemHalfWidthPx = stemWidthPx / 2
        val leafOffsetPx = SizeTokens.sproutLeafOffset.toPx()
        val stemCornerRadiusPx = SizeTokens.sproutStemCornerRadius.toPx()

        drawRoundRect(
            color = primaryColor.copy(alpha = 0.7f + 0.3f * progress),
            topLeft = Offset(x = size.width / 2 - stemHalfWidthPx, y = size.height * 0.78f - stemHeight),
            size = Size(width = stemWidthPx, height = stemHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(stemCornerRadiusPx),
        )

        if (progress > 0.3f) {
            val leftLeafScale = ((progress - 0.3f) / 0.7f).coerceIn(0f, 1f)
            drawOval(
                color = tertiaryColor.copy(alpha = 0.6f * leftLeafScale),
                topLeft = Offset(
                    x = size.width / 2 - leafSize - leafOffsetPx,
                    y = size.height * 0.78f - stemHeight - leafSize * 0.3f,
                ),
                size = Size(width = leafSize, height = leafSize * 0.7f),
            )
        }

        if (progress > 0.5f) {
            val rightLeafScale = ((progress - 0.5f) / 0.5f).coerceIn(0f, 1f)
            drawOval(
                color = primaryColor.copy(alpha = 0.5f * rightLeafScale),
                topLeft = Offset(
                    x = size.width / 2 + leafOffsetPx,
                    y = size.height * 0.78f - stemHeight * 0.85f - leafSize * 0.2f,
                ),
                size = Size(width = leafSize * 0.9f, height = leafSize * 0.55f),
            )
        }

        if (progress > 0.75f) {
            val topLeafScale = ((progress - 0.75f) / 0.25f).coerceIn(0f, 1f)
            drawOval(
                color = secondaryColor.copy(alpha = 0.7f * topLeafScale),
                topLeft = Offset(
                    x = size.width / 2 - leafSize * 0.35f,
                    y = size.height * 0.78f - stemHeight - leafSize * 0.6f,
                ),
                size = Size(width = leafSize * 0.7f, height = leafSize * 0.45f),
            )
        }
    }
}

@Composable
private fun PhaseIndicatorDots(currentPhase: SproutingState) {
    val phases = listOf(SproutingState.PHASE1, SproutingState.PHASE2, SproutingState.PHASE3, SproutingState.PHASE4)
    val currentIndex = phases.indexOf(currentPhase).coerceAtLeast(0)

    Row(
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        phases.forEachIndexed { index, phase ->
            val isActive = index == currentIndex
            val isCompleted = index < currentIndex

            val dotColor = when {
                isCompleted -> MaterialTheme.colorScheme.primary
                isActive -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.surfaceContainerHighest
            }

            val infiniteTransition = rememberInfiniteTransition(label = "phasePulse$index")
            val pulseScale by if (isActive) {
                infiniteTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = 1.3f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(800, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "pulse$index",
                )
            } else {
                remember { mutableStateOf(1f) }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size((SizeTokens.statusDotSize.value * pulseScale).dp),
                ) {
                    if (isCompleted) {
                        Text("[OK]", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Canvas(modifier = Modifier.size((SizeTokens.statusDotSmallSize.value * pulseScale).dp)) {
                            drawCircle(color = dotColor)
                        }
                    }
                }
                Spacer(Modifier.height(SpacingTokens.xxs))
                Text(
                    text = listOf("1", "2", "3", "4")[index],
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun DonePhase(report: String, qualityScore: Int?, processingTimeMs: Long?) {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(true) }
    val isLongReport = remember(report) { report.length > 500 }

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isLongReport) {
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.semantics {
                        contentDescription = context.getString(if (expanded) R.string.cd_collapse else R.string.cd_expand)
                    },
                ) {
                    Text(
                        text = stringResource(if (expanded) R.string.action_collapse else R.string.action_expand),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (processingTimeMs != null) {
                Text(
                    text = stringResource(R.string.sprout_time_cost, formatProcessingTime(processingTimeMs)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            MarkdownText(
                text = report,
                maxChars = Int.MAX_VALUE,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = SizeTokens.expandedContentMaxHeight)
                .verticalScroll(rememberScrollState())
                    .semantics { contentDescription = context.getString(R.string.sprout_cd_report_content, report.length) },
            )
        }
    }
}

@Composable
private fun ErrorPhase(onRetry: () -> Unit) {
    val context = LocalContext.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = SpacingTokens.xl)
            .semantics { contentDescription = context.getString(R.string.sprout_cd_error) },
    ) {
        Text(text = stringResource(R.string.sprout_error_title), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(SpacingTokens.md))
        Text(
            text = stringResource(R.string.sprout_error_desc),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(SpacingTokens.xs))
        Text(
            text = stringResource(R.string.sprout_error_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(SpacingTokens.lg))
        Button(
            onClick = onRetry,
            shape = ShapeTokens.mediumShape,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        ) {
            Icon(imageVector = Icons.Default.Refresh, contentDescription = stringResource(R.string.action_retry), modifier = Modifier.size(SizeTokens.iconMd))
            Spacer(Modifier.width(SpacingTokens.xs))
            Text(stringResource(R.string.action_retry))
        }
    }
}

@Composable
private fun CancelledPhase(onRestart: () -> Unit) {
    val context = LocalContext.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = SpacingTokens.xl)
            .semantics { contentDescription = context.getString(R.string.sprout_cd_cancelled) },
    ) {
        Text(text = stringResource(R.string.sprout_title_cancelled), style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(SpacingTokens.md))
        Text(
            text = stringResource(R.string.sprout_title_cancelled),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(SpacingTokens.lg))
        OutlinedButton(
            onClick = onRestart,
            shape = ShapeTokens.mediumShape,
        ) {
            Icon(imageVector = Icons.Default.Refresh, contentDescription = stringResource(R.string.sprout_action_restart), modifier = Modifier.size(SizeTokens.iconMd))
            Spacer(Modifier.width(SpacingTokens.xs))
            Text(stringResource(R.string.sprout_action_restart))
        }
    }
}

@Composable
private fun DoneActionButtons(onCopy: () -> Unit, onContinueChat: () -> Unit) {
    val context = LocalContext.current
    var copyFeedbackShown by remember { mutableStateOf(false) }

    Row(
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedButton(
            onClick = {
                onCopy()
                copyFeedbackShown = true
            },
            shape = ShapeTokens.mediumShape,
            modifier = Modifier
                .weight(1f)
                .height(SizeTokens.buttonHeightMd)
                .semantics { contentDescription = context.getString(R.string.sprout_action_copy_full) },
        ) {
            if (copyFeedbackShown) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.sprout_action_copied),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(SizeTokens.iconMd),
                )
                Spacer(Modifier.width(SpacingTokens.xs))
                Text(stringResource(R.string.sprout_action_copied))
            } else {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.sprout_action_copy_full),
                    modifier = Modifier.size(SizeTokens.iconMd),
                )
                Spacer(Modifier.width(SpacingTokens.xs))
                Text(stringResource(R.string.sprout_action_copy_full))
            }
        }

        Button(
            onClick = onContinueChat,
            shape = ShapeTokens.mediumShape,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .weight(1f)
                .height(SizeTokens.buttonHeightMd)
                .semantics { contentDescription = context.getString(R.string.sprout_action_continue_chat) },
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Chat,
                contentDescription = stringResource(R.string.sprout_action_continue_chat),
                modifier = Modifier.size(SizeTokens.iconMd),
            )
            Spacer(Modifier.width(SpacingTokens.xs))
            Text(stringResource(R.string.sprout_action_continue_chat))
        }
    }
}

private fun formatElapsedTime(seconds: Int): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return if (mins > 0) "%02d:%02d".format(mins, secs) else "00:%02d".format(secs)
}

private fun formatProcessingTime(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    ms < 60_000 -> "%.1fs".format(ms / 1000.0)
    else -> "%.1fm".format(ms / 60_000.0)
}
