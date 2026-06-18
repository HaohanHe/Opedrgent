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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import top.hsyscn.opedrgent.ui.SproutingState

private val PHASE_LABELS = mapOf(
    SproutingState.PHASE1 to ("提取种子" to "正在提取关键信息..."),
    SproutingState.PHASE2 to ("跨领域关联" to "正在建立知识连接..."),
    SproutingState.PHASE3 to ("生成洞察" to "正在生成洞察..."),
    SproutingState.PHASE4 to ("金句回响" to "正在提炼金句..."),
)

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
    if (markdownReport.isEmpty() && sproutingState == SproutingState.IDLE) return

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "发芽报告视图" }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SproutTitleBar(sproutingState = sproutingState, qualityScore = qualityScore, onDismiss = onDismiss)

            Spacer(Modifier.height(12.dp))

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
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(Modifier.height(12.dp))
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
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = when (sproutingState) {
                SproutingState.IDLE, SproutingState.DONE -> "知识发芽"
                SproutingState.PHASE1, SproutingState.PHASE2, SproutingState.PHASE3, SproutingState.PHASE4 -> PHASE_LABELS[sproutingState]?.first ?: "知识发芽"
                SproutingState.ERROR -> "错误"
                SproutingState.CANCELLED -> "已取消"
            },
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = when (sproutingState) {
                SproutingState.IDLE -> "发芽报告"
                SproutingState.PHASE1, SproutingState.PHASE2, SproutingState.PHASE3, SproutingState.PHASE4 -> PHASE_LABELS[sproutingState]?.second ?: "处理中..."
                SproutingState.DONE -> "发芽完成"
                SproutingState.ERROR -> "发芽失败"
                SproutingState.CANCELLED -> "已取消"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))

        if (sproutingState == SproutingState.DONE && qualityScore != null) {
            QualityScoreBadge(score = qualityScore)
            Spacer(Modifier.width(10.dp))
        }

        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .size(36.dp)
                .semantics { contentDescription = "关闭发芽报告" },
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun QualityScoreBadge(score: Int) {
    val (badgeColor, label) = when {
        score >= 85 -> Pair(MaterialTheme.colorScheme.primary, "优秀")
        score >= 70 -> Pair(MaterialTheme.colorScheme.tertiary, "良好")
        score >= 50 -> Pair(MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f), "一般")
        else -> Pair(MaterialTheme.colorScheme.error, "需改进")
    }

    Surface(
        shape = CircleShape,
        color = badgeColor.copy(alpha = 0.15f),
        modifier = Modifier.semantics { contentDescription = "质量评分 $score 分，$label" },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(
                text = "$score",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = badgeColor,
            )
            Spacer(Modifier.width(4.dp))
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
        modifier = Modifier.fillMaxWidth().height(180.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            SproutGrowingAnimation(progress = growProgress)

            Spacer(Modifier.height(16.dp))

            PhaseIndicatorDots(currentPhase = currentPhase)

            Spacer(Modifier.height(12.dp))

            Text(
                text = formatElapsedTime(elapsedSeconds),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))

            TextButton(
                onClick = onCancel,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text("取消", fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun SproutGrowingAnimation(progress: Float) {
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary

    Canvas(modifier = Modifier.size(80.dp)) {
        val stemHeight = size.height * 0.5f * progress
        val leafSize = size.width * 0.25f

        drawCircle(
            color = tertiaryColor.copy(alpha = 0.2f + 0.3f * progress),
            radius = size.width * 0.18f,
            center = Offset(size.width / 2, size.height * 0.78f),
        )

        drawRoundRect(
            color = primaryColor.copy(alpha = 0.7f + 0.3f * progress),
            topLeft = Offset(x = size.width / 2 - 2.dp.toPx(), y = size.height * 0.78f - stemHeight),
            size = Size(width = 4.dp.toPx(), height = stemHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
        )

        if (progress > 0.3f) {
            val leftLeafScale = ((progress - 0.3f) / 0.7f).coerceIn(0f, 1f)
            drawOval(
                color = tertiaryColor.copy(alpha = 0.6f * leftLeafScale),
                topLeft = Offset(
                    x = size.width / 2 - leafSize - 4.dp.toPx(),
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
                    x = size.width / 2 + 4.dp.toPx(),
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
        horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                    modifier = Modifier.size((12 * pulseScale).dp),
                ) {
                    if (isCompleted) {
                        Text("[OK]", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    } else {
                        Canvas(modifier = Modifier.size((10 * pulseScale).dp)) {
                            drawCircle(color = dotColor)
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = listOf("1", "2", "3", "4")[index],
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun DonePhase(report: String, qualityScore: Int?, processingTimeMs: Long?) {
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
                        contentDescription = if (expanded) "收起报告" else "展开报告"
                    },
                ) {
                    Text(
                        text = if (expanded) "收起" else "展开",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (processingTimeMs != null) {
                Text(
                    text = "耗时 ${formatProcessingTime(processingTimeMs)}",
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
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
                    .semantics { contentDescription = "发芽报告内容，共 ${report.length} 个字符" },
            )
        }
    }
}

@Composable
private fun ErrorPhase(onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp)
            .semantics { contentDescription = "发芽处理出错" },
    ) {
        Text(text = "错误", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Text(
            text = "发芽过程中出现错误",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "请检查网络连接或稍后重试",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onRetry,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        ) {
            Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("重试", fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun CancelledPhase(onRestart: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp)
            .semantics { contentDescription = "发芽已取消" },
    ) {
        Text(text = "已取消", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(12.dp))
        Text(
            text = "已取消",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = onRestart,
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("重新开始", fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun DoneActionButtons(onCopy: () -> Unit, onContinueChat: () -> Unit) {
    var copyFeedbackShown by remember { mutableStateOf(false) }

    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedButton(
            onClick = {
                onCopy()
                copyFeedbackShown = true
            },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .semantics { contentDescription = "复制全文" },
        ) {
            if (copyFeedbackShown) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("已复制", fontWeight = FontWeight.Medium)
            } else {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("复制全文", fontWeight = FontWeight.Medium)
            }
        }

        Button(
            onClick = onContinueChat,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .semantics { contentDescription = "继续追问" },
        ) {
            Icon(
                imageVector = Icons.Default.Chat,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("继续追问", fontWeight = FontWeight.Medium)
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
