package top.hsyscn.opedrgent.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Link
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import top.hsyscn.opedrgent.model.ReasoningPart
import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.model.ToolStateType
import top.hsyscn.opedrgent.ui.theme.AccentBlue
import top.hsyscn.opedrgent.ui.theme.CardWhite
import top.hsyscn.opedrgent.ui.theme.TextDark
import top.hsyscn.opedrgent.ui.theme.TextGrey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val STREAMING_PACE_MS = 64L
private const val MAX_STEP = 24
private val WORD_SNAP = Regex("""[\s.,!?;:)\]]""")

private fun adaptiveStep(totalLen: Int): Int = when {
    totalLen <= 12 -> 2
    totalLen <= 48 -> 4
    totalLen <= 96 -> 8
    else -> minOf(MAX_STEP, (totalLen / 8).coerceAtLeast(1))
}

private fun snapToWord(text: String, start: Int, end: Int): Int {
    val max = minOf(text.length, end + 8)
    for (i in end until max) {
        if (i < text.length && WORD_SNAP.containsMatchIn(text[i].toString())) {
            return i + 1
        }
    }
    return end.coerceAtMost(text.length)
}

@Composable
fun StreamingCard(
    text: String,
    reasoning: String,
    toolParts: List<ToolPart>,
    phase: String = "",
) {
    var displayText by remember { mutableStateOf("") }
    var isComplete by remember { mutableStateOf(false) }

    LaunchedEffect(text) {
        if (text.length > displayText.length) {
            val newPart = text.substring(displayText.length)
            val totalLen = text.length
            var idx = 0
            while (idx < newPart.length) {
                val baseStep = adaptiveStep(totalLen)
                val end = (idx + baseStep).coerceAtMost(newPart.length)
                val snapped = snapToWord(newPart, idx, end)
                val chunk = newPart.substring(idx, snapped)
                displayText += chunk
                idx = snapped
                delay(STREAMING_PACE_MS)
            }
            isComplete = true
        } else if (text.length < displayText.length) {
            displayText = text
        } else if (text.length == displayText.length && text.isNotEmpty()) {
            isComplete = true
        }
    }

    val hasText = displayText.trim().isNotEmpty()
    val hasReasoning = reasoning.isNotEmpty()
    val hasTools = toolParts.isNotEmpty()
    val isToolRunning = toolParts.any { it.state.status == ToolStateType.RUNNING }

    val showThinkingIndicator = !hasText && !hasReasoning && !hasTools

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(11.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("助手", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), color = TextDark)
                if (isToolRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(16.dp).width(16.dp),
                        strokeWidth = 2.dp,
                        color = AccentBlue,
                    )
                }
            }

            if (showThinkingIndicator && phase.isNotEmpty()) {
                ThinkingIndicator(phase = phase)
            }

            if (hasReasoning) {
                ThinkingSection(parts = listOf(ReasoningPart(text = reasoning)))
            }

            if (hasTools) {
                toolParts.forEach { tp ->
                    ToolStatusRow(toolPart = tp)
                }
            }

            if (hasText) {
                if (isComplete) {
                    StreamingMarkdownText(text = displayText, maxChars = 900)
                } else {
                    Text(
                        text = displayText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextDark,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
fun ThinkingIndicator(phase: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "thinking")
    val shimmerProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = AccentBlue,
        )
        val alpha = 0.4f + 0.6f * shimmerProgress
        Text(
            text = phase,
            style = MaterialTheme.typography.bodySmall,
            color = AccentBlue.copy(alpha = alpha),
        )
    }
}

@Composable
fun ThinkingSection(parts: List<ReasoningPart>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val combined = parts.joinToString("\n") { it.text }
    Column {
        TextButton(onClick = { expanded = !expanded }) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = "thinking",
                modifier = Modifier.height(16.dp),
                tint = AccentBlue,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                if (expanded) "收起思考过程" else "查看思考过程",
                style = MaterialTheme.typography.bodySmall,
                color = AccentBlue,
            )
        }
        if (expanded) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(11.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5)),
            ) {
                Text(
                    text = combined,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextDark,
                )
            }
        }
    }
}

@Composable
fun ToolStatusRow(toolPart: ToolPart) {
    val icon: ImageVector = when (toolPart.state.status) {
        ToolStateType.PENDING -> Icons.Default.AccessTime
        ToolStateType.RUNNING -> Icons.Default.HourglassEmpty
        ToolStateType.COMPLETED -> Icons.Default.CheckCircle
        ToolStateType.ERROR -> Icons.Default.Cancel
        ToolStateType.SOURCE_ADDED -> Icons.Default.Bookmark
    }
    val statusText = when (toolPart.state.status) {
        ToolStateType.PENDING -> "等待执行..."
        ToolStateType.RUNNING -> {
            val q = toolPart.state.input["query"]
            val u = toolPart.state.input["url"]
            when {
                !q.isNullOrBlank() -> "查询: $q"
                !u.isNullOrBlank() -> "读取: ${runCatching { java.net.URL(u).host }.getOrDefault(u.take(30))}"
                else -> "执行中..."
            }
        }
        ToolStateType.COMPLETED -> {
            when (toolPart.tool) {
                "web_search" -> "搜索完成"
                "read_url" -> "读取完成"
                else -> "完成"
            }
        }
        ToolStateType.ERROR -> "错误: ${toolPart.state.error?.take(30) ?: "未知"}"
        ToolStateType.SOURCE_ADDED -> "已添加来源"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = when (toolPart.state.status) {
                ToolStateType.COMPLETED -> Color(0xFF4CAF50)
                ToolStateType.ERROR -> Color(0xFFF44336)
                ToolStateType.SOURCE_ADDED -> AccentBlue
                else -> TextGrey
            },
        )

        if (toolPart.state.status == ToolStateType.PENDING) {
            ShimmerText(text = toolPart.tool, color = TextDark)
        } else {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                        append(toolPart.tool)
                    }
                    append(" ")
                    append(statusText)
                },
                fontSize = 12.sp,
                color = TextDark,
            )
        }

        if (toolPart.state.status == ToolStateType.RUNNING) {
            CircularProgressIndicator(
                modifier = Modifier.height(12.dp).width(12.dp),
                strokeWidth = 1.5.dp,
                color = AccentBlue,
            )
        }
    }
}

@Composable
private fun ShimmerText(text: String, color: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer",
    )

    val brush = Brush.horizontalGradient(
        colors = listOf(
            color.copy(alpha = 0.5f),
            color.copy(alpha = 1f),
            color.copy(alpha = 0.5f),
        ),
        startX = -100f + progress * 600f,
        endX = progress * 600f,
    )

    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        style = TextStyle(brush = brush),
    )
}

@Composable
fun ToolCard(toolPart: ToolPart) {
    var expanded by rememberSaveable { mutableStateOf(toolPart.state.status == ToolStateType.RUNNING) }
    val statusIcon: ImageVector = when (toolPart.state.status) {
        ToolStateType.PENDING -> Icons.Default.AccessTime
        ToolStateType.RUNNING -> Icons.Default.HourglassEmpty
        ToolStateType.COMPLETED -> Icons.Default.CheckCircle
        ToolStateType.ERROR -> Icons.Default.Cancel
        ToolStateType.SOURCE_ADDED -> Icons.Default.Link
    }
    val statusColor = when (toolPart.state.status) {
        ToolStateType.PENDING -> TextGrey
        ToolStateType.RUNNING -> AccentBlue
        ToolStateType.COMPLETED -> Color(0xFF4CAF50)
        ToolStateType.ERROR -> MaterialTheme.colorScheme.error
        ToolStateType.SOURCE_ADDED -> Color(0xFF2196F3)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(11.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5)),
        onClick = { expanded = !expanded },
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = statusIcon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = statusColor,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = toolPart.tool,
                    fontWeight = FontWeight.SemiBold,
                    color = statusColor,
                    modifier = Modifier.weight(1f),
                )
                if (toolPart.state.status == ToolStateType.RUNNING) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(14.dp).width(14.dp),
                        strokeWidth = 2.dp,
                        color = AccentBlue,
                    )
                }
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                if (toolPart.state.input.isNotEmpty()) {
                    Text(
                        "参数：${toolPart.state.input.entries.joinToString(", ") { "${it.key}=${it.value}" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextDark,
                    )
                }
                if (!toolPart.state.output.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = toolPart.state.output!!.take(500) + if (toolPart.state.output!!.length > 500) "…" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextDark,
                    )
                }
                if (!toolPart.state.error.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "错误：${toolPart.state.error}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
fun QuestionCard(
    question: top.hsyscn.opedrgent.model.QuestionPart,
    onAnswer: (String) -> Unit,
    onDismiss: () -> Unit,
    readonly: Boolean = false,
) {
    var selected by rememberSaveable { mutableStateOf(setOf<String>()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(11.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = question.prompt.ifEmpty { "请选择：" },
                fontWeight = FontWeight.SemiBold,
                color = TextDark,
            )
            if (question.multiSelect) {
                Text("（多选）", style = MaterialTheme.typography.bodySmall, color = TextGrey)
            }
            question.options.forEach { opt ->
                androidx.compose.material3.FilterChip(
                    selected = opt.value in selected,
                    onClick = {
                        if (readonly) return@FilterChip
                        if (question.multiSelect) {
                            selected = if (opt.value in selected) selected - opt.value else selected + opt.value
                        } else {
                            selected = setOf(opt.value)
                        }
                    },
                    label = { Text(opt.label) },
                )
            }
            if (!readonly) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.Button(
                        onClick = {
                            val answer = if (question.multiSelect) selected.joinToString(",") else selected.firstOrNull() ?: ""
                            if (answer.isNotBlank()) onAnswer(answer)
                        },
                        enabled = selected.isNotEmpty(),
                        shape = RoundedCornerShape(11.dp),
                    ) { Text("确认") }
                    TextButton(onClick = onDismiss) { Text("跳过") }
                }
            }
            if (readonly && question.answer != null) {
                Text("回答：${question.answer}", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
