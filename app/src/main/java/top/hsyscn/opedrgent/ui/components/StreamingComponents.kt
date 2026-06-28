package top.hsyscn.opedrgent.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.InlineTextContent
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Link
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
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
import kotlinx.coroutines.flow.debounce
import top.hsyscn.opedrgent.model.MessagePart
import top.hsyscn.opedrgent.model.ReasoningPart
import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.model.ToolStateType
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import top.hsyscn.opedrgent.ui.theme.SpacingTokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.hsyscn.opedrgent.ui.theme.themeTextDark
import top.hsyscn.opedrgent.ui.theme.themeTextGrey

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
    var displayText by remember(text.isNotEmpty()) { mutableStateOf("") }
    var isComplete by remember(text.isNotEmpty()) { mutableStateOf(false) }
    // Track previous text length to detect genuinely new messages (text reset) vs character increments
    var lastTextLen by remember { mutableStateOf(0) }

    // Use Unit key to avoid restarting coroutine on every character update.
    // Manually check for new message (text length decreased) inside.
    LaunchedEffect(Unit) {
        snapshotFlow { text }
            .collect { currentText ->
                when {
                    currentText.length < lastTextLen -> {
                        displayText = currentText
                        isComplete = false
                        lastTextLen = currentText.length
                    }
                    currentText.length > lastTextLen -> {
                        isComplete = false
                        val newPart = currentText.substring(lastTextLen.coerceAtMost(displayText.length))
                        val totalLen = currentText.length
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
                        lastTextLen = currentText.length
                    }
                }
            }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { text to lastTextLen }
            .debounce(100)
            .collect { (currentText, currentLastLen) ->
                if (currentText.isNotEmpty() && !isComplete && currentLastLen >= currentText.length) {
                    isComplete = true
                }
            }
    }

    val hasText = displayText.trim().isNotEmpty()
    val hasReasoning = reasoning.isNotEmpty()
    val hasTools = toolParts.isNotEmpty()
    val isToolRunning = toolParts.any { it.state.status == ToolStateType.RUNNING }
    val hasCompletedSources = toolParts.any { it.tool == "web_search" && it.state.status == ToolStateType.COMPLETED }

    val showLoading = !hasText && !hasReasoning && !hasTools

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = ShapeTokens.largeShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(SpacingTokens.md),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("助手", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), color = themeTextDark())
                if (isToolRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(16.dp).width(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (showLoading) {
                DoubaoThinkingIndicator(phase = phase)
            }

            if (hasReasoning) {
                MessageBodyThinking(
                    thinkingText = reasoning,
                    isComplete = hasText || isComplete,
                )
            }

            if (hasTools) {
                ToolStatusGroup(toolParts = toolParts)
            }

            if (hasCompletedSources) {
                SourceLinksSection(toolParts = toolParts)
            }

            if (hasText) {
                if (isComplete) {
                    // UI 层不做截断 —— ContextCompressor 在上游已按模型上下文窗口控制大小
                    StreamingMarkdownText(text = displayText, maxChars = Int.MAX_VALUE)
                } else {
                    Text(
                        text = displayText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = themeTextDark(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * 基于 MessagePart.StreamingState 的流式卡片（新模型）。
 * 从 StreamingState 中提取 text / reasoning / phase，委托给原 StreamingCard。
 */
@Composable
fun StreamingCard(state: MessagePart.StreamingState, toolParts: List<ToolPart> = emptyList()) {
    StreamingCard(
        text = state.text,
        reasoning = state.reasoning,
        toolParts = toolParts,
        phase = state.phase,
    )
}

/** 流式文本 + 末尾脉动光标，使用 animateContentSize 让新字符平滑出现 */
@Composable
private fun StreamingTextWithCursor(
    text: String,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "stream_cursor")
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "cursor_alpha",
    )

    val cursorMarker = "\uFFFC"
    val annotated = buildAnnotatedString {
        append(text)
        append(cursorMarker)
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val inlineContent = mapOf(
        cursorMarker to InlineTextContent(
            placeholder = Placeholder(
                width = 2.sp,
                height = 16.sp,
                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
            )
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(primaryColor.copy(alpha = cursorAlpha))
            )
        }
    )

    Text(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium,
        color = themeTextDark(),
        inlineContent = inlineContent,
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
    )
}

/** 豆包风格加载指示器：两个呼吸圆点 + 状态文案 */
@Composable
fun DoubaoThinkingIndicator(phase: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "db_thinking")
    val dot1Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "db_dot1",
    )
    val dot2Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = LinearEasing, delayMillis = 200),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "db_dot2",
    )

    val primaryColor = MaterialTheme.colorScheme.primary

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
    ) {
        Canvas(modifier = Modifier.size(18.dp, 8.dp)) {
            val radius = 2.5f.dp.toPx()
            val spacing = 7f.dp.toPx()
            drawCircle(
                color = primaryColor.copy(alpha = dot1Alpha),
                radius = radius,
                center = Offset(radius, size.height / 2),
            )
            drawCircle(
                color = primaryColor.copy(alpha = dot2Alpha),
                radius = radius,
                center = Offset(spacing + radius, size.height / 2),
            )
        }
        Text(
            text = phase.ifBlank { "正在思考" },
            style = MaterialTheme.typography.bodySmall,
            color = themeTextGrey(),
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
              imageVector = Icons.Default.AutoAwesome,
                contentDescription = "思考",
                modifier = Modifier.height(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(SpacingTokens.xs))
            Text(
                if (expanded) "收起思考过程" else "查看思考过程",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (expanded) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = ShapeTokens.largeShape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Text(
                    text = combined,
                    modifier = Modifier.padding(SpacingTokens.md),
                    style = MaterialTheme.typography.bodySmall,
                    color = themeTextDark(),
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
        ToolStateType.PARTIAL_TIMEOUT -> Icons.Default.Error
    }
    val statusText = when (toolPart.state.status) {
        ToolStateType.PENDING -> "等待执行..."
        ToolStateType.RUNNING -> {
            val q = extractCleanQuery(toolPart.state.input)
            val u = toolPart.state.input["url"]
            when {
                q.isNotBlank() -> "正在搜索: $q"
                !u.isNullOrBlank() -> "正在读取: ${runCatching { java.net.URL(u).host }.getOrDefault(u.take(30))}"
                else -> "执行中..."
            }
        }
        ToolStateType.COMPLETED -> {
            when (toolPart.tool) {
                "web_search" -> {
                    val q = extractCleanQuery(toolPart.state.input)
                    if (q.isNotBlank()) "搜索: $q" else "搜索完成"
                }
                "read_url" -> "读取完成"
                else -> "完成"
            }
        }
        ToolStateType.ERROR -> "错误: ${toolPart.state.error?.take(30) ?: "未知"}"
        ToolStateType.SOURCE_ADDED -> "已添加来源"
        ToolStateType.PARTIAL_TIMEOUT -> "部分超时: ${toolPart.state.error?.take(30) ?: "获取超时"}"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.xs),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = "工具状态",
            modifier = Modifier.size(16.dp),
            tint = when (toolPart.state.status) {
                ToolStateType.COMPLETED -> MaterialTheme.colorScheme.primary
                ToolStateType.ERROR -> MaterialTheme.colorScheme.error
                ToolStateType.SOURCE_ADDED -> MaterialTheme.colorScheme.primary
                ToolStateType.PARTIAL_TIMEOUT -> MaterialTheme.colorScheme.error
                else -> themeTextGrey()
            },
        )

        if (toolPart.state.status == ToolStateType.PENDING) {
            ShimmerText(text = toolPart.tool, color = themeTextDark())
        } else {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                        append(toolDisplayName(toolPart.tool))
                    }
                    append(" ")
                    append(statusText)
                },
                style = MaterialTheme.typography.labelMedium,
                color = themeTextDark(),
            )
        }

        if (toolPart.state.status == ToolStateType.RUNNING) {
            CircularProgressIndicator(
                modifier = Modifier.height(12.dp).width(12.dp),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * 合并展示同类工具调用（如多次 web_search 合并为一行）
 */
@Composable
fun ToolStatusGroup(toolParts: List<ToolPart>) {
    val grouped = toolParts.groupBy { it.tool }
    Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.xs)) {
        grouped.forEach { (toolName, parts) ->
            if (parts.size == 1) {
                ToolStatusRow(toolPart = parts[0])
            } else {
                ToolStatusCollapsedGroup(toolName = toolName, parts = parts)
            }
        }
    }
}

@Composable
private fun ToolStatusCollapsedGroup(toolName: String, parts: List<ToolPart>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val completedCount = parts.count { it.state.status == ToolStateType.COMPLETED }
    val hasRunning = parts.any { it.state.status == ToolStateType.RUNNING }
    val hasError = parts.any { it.state.status == ToolStateType.ERROR }

    val statusIcon = when {
        hasRunning -> Icons.Default.HourglassEmpty
        hasError -> Icons.Default.Cancel
        else -> Icons.Default.CheckCircle
    }
    val statusColor = when {
        hasRunning -> MaterialTheme.colorScheme.primary
        hasError -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }

    Column {
        // Row 1: icon + tool name + count | expand icon + progress (always single line)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ShapeTokens.smallShape)
                .clickable { expanded = !expanded }
                .padding(vertical = SpacingTokens.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.xs),
        ) {
            Icon(
                imageVector = statusIcon,
                contentDescription = "工具状态",
                modifier = Modifier.size(16.dp),
                tint = statusColor,
            )
            Text(
                text = "${toolDisplayName(toolName)} ${completedCount}/${parts.size}",
                style = MaterialTheme.typography.labelMedium,
                color = themeTextDark(),
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "折叠" else "展开",
                modifier = Modifier.size(16.dp),
                tint = themeTextGrey(),
            )
            if (hasRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.height(12.dp).width(12.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        // Row 2: query summary (collapsible, single line with ellipsis)
        val queries = parts.mapNotNull { extractCleanQuery(it.state.input).ifBlank { null } }
        if (queries.isNotEmpty() && !expanded) {
            Text(
                text = queries.joinToString(", ").take(60) + if (queries.joinToString(", ").length > 60) "..." else "",
                style = MaterialTheme.typography.labelSmall,
                color = themeTextGrey(),
                maxLines = 1,
                modifier = Modifier.padding(start = SpacingTokens.xl),
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = SpacingTokens.xl),
                verticalArrangement = Arrangement.spacedBy(SpacingTokens.xxs),
            ) {
                parts.forEach { tp ->
                    ToolStatusRow(toolPart = tp)
                }
            }
        }
    }
}

data class SourceLink(val title: String, val url: String, val snippet: String?)

/** 从已完成的 web_search 工具输出中提取参考来源 */
private fun extractSourcesFromToolParts(toolParts: List<ToolPart>): List<SourceLink> {
    val results = mutableListOf<SourceLink>()
    val seen = mutableSetOf<String>()
    val linkRegex = Regex("""\[(.*?)\]\((https?://[^\\s)]+)\)""")
    for (tp in toolParts.filter { it.tool == "web_search" && it.state.status == ToolStateType.COMPLETED }) {
        val output = tp.state.output ?: continue
        for (match in linkRegex.findAll(output)) {
            val title = match.groupValues[1].trim()
            val url = match.groupValues[2].trim()
            if (title.isBlank() || url.isBlank() || url in seen) continue
            seen.add(url)
            val snippet = extractSnippetAfter(output, match.range.last)
            results.add(SourceLink(title, url, snippet))
        }
    }
    return results
}

private fun extractSnippetAfter(text: String, linkEnd: Int): String? {
    val after = text.substring(linkEnd, minOf(linkEnd + 300, text.length))
    return after.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("[") && !it.startsWith("(") }
        .firstOrNull()
        ?.take(120)
}

@Composable
fun SourceLinksSection(toolParts: List<ToolPart>) {
    val sources = remember(toolParts) { extractSourcesFromToolParts(toolParts) }
    if (sources.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(true) }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ShapeTokens.mediumShape)
                .clickable { expanded = !expanded }
                .padding(vertical = SpacingTokens.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.xs),
        ) {
            Icon(
                imageVector = Icons.Default.Link,
                contentDescription = "参考来源",
                modifier = Modifier.size(16.dp),
                tint = themeTextGrey(),
            )
            Text(
                text = "参考 ${sources.size} 个来源",
                style = MaterialTheme.typography.bodySmall,
                color = themeTextDark(),
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "折叠" else "展开",
                modifier = Modifier.size(18.dp),
                tint = themeTextGrey(),
            )
        }
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
                sources.take(5).forEachIndexed { idx, source ->
                    SourceLinkCard(
                        index = idx + 1,
                        source = source,
                        onClick = { uriHandler.openUri(source.url) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceLinkCard(index: Int, source: SourceLink, onClick: () -> Unit) {
    val host = remember(source.url) {
        runCatching { java.net.URL(source.url).host }.getOrDefault(source.url)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = ShapeTokens.mediumShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        onClick = onClick,
    ) {
        Column(modifier = Modifier.padding(SpacingTokens.md)) {
            Text(
                text = "${index}. ${source.title}",
                style = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.primary),
                maxLines = 1,
            )
            if (source.snippet != null) {
                Spacer(Modifier.height(SpacingTokens.xxs))
                Text(
                    text = source.snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = themeTextGrey(),
                    maxLines = 2,
                )
            }
            Spacer(Modifier.height(SpacingTokens.xxs))
            Text(
                text = host,
                style = MaterialTheme.typography.labelSmall,
                color = themeTextGrey(),
                maxLines = 1,
            )
        }
    }
}

/** 工具名显示映射 */
private fun toolDisplayName(tool: String): String = when (tool) {
    "web_search" -> "搜索"
    "read_url" -> "阅读"
    "step_search" -> "搜索"
    "step_rag" -> "知识库"
    "speech_to_text" -> "语音转文字"
    else -> tool
}

/** 从 input map 中提取干净的查询文本（处理嵌套JSON） */
private fun extractCleanQuery(input: Map<String, String>): String {
    val raw = input["query"] ?: input["keyword"] ?: return ""
    // 如果值本身是 JSON，尝试解析提取 query 字段
    val cleaned = runCatching {
        val json = org.json.JSONObject(raw)
        json.optString("query", json.optString("keyword", raw))
    }.getOrDefault(raw)
    return cleaned.trim().take(80)
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
        style = TextStyle(brush = brush).merge(MaterialTheme.typography.labelMedium),
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
        ToolStateType.PARTIAL_TIMEOUT -> Icons.Default.Error
    }
    val statusColor = when (toolPart.state.status) {
        ToolStateType.PENDING -> themeTextGrey()
        ToolStateType.RUNNING -> MaterialTheme.colorScheme.primary
        ToolStateType.COMPLETED -> MaterialTheme.colorScheme.primary
        ToolStateType.ERROR -> MaterialTheme.colorScheme.error
        ToolStateType.SOURCE_ADDED -> MaterialTheme.colorScheme.primary
        ToolStateType.PARTIAL_TIMEOUT -> MaterialTheme.colorScheme.error
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = ShapeTokens.largeShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        onClick = { expanded = !expanded },
    ) {
        Column(modifier = Modifier.padding(SpacingTokens.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = statusIcon,
                    contentDescription = "工具状态",
                    modifier = Modifier.size(16.dp),
                    tint = statusColor,
                )
                Spacer(Modifier.width(SpacingTokens.xs))
                Text(
                    text = toolPart.tool,
                    style = MaterialTheme.typography.labelMedium.copy(color = statusColor),
                    modifier = Modifier.weight(1f),
                )
                if (toolPart.state.status == ToolStateType.RUNNING) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(14.dp).width(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (expanded) {
                Spacer(Modifier.height(SpacingTokens.sm))
                if (toolPart.state.input.isNotEmpty()) {
                    Text(
                        "参数：${toolPart.state.input.entries.joinToString(", ") { "${it.key}=${it.value}" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = themeTextDark(),
                    )
                }
                if (!toolPart.state.output.isNullOrBlank()) {
                    Spacer(Modifier.height(SpacingTokens.xs))
                    Text(
                        text = toolPart.state.output!!.take(500) + if (toolPart.state.output!!.length > 500) "…" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = themeTextDark(),
                    )
                }
                if (!toolPart.state.error.isNullOrBlank()) {
                    Spacer(Modifier.height(SpacingTokens.xs))
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
        shape = ShapeTokens.largeShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)),
    ) {
        Column(
            modifier = Modifier.padding(SpacingTokens.md),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
        ) {
            Text(
                text = question.prompt.ifEmpty { "请选择：" },
                style = MaterialTheme.typography.titleSmall,
                color = themeTextDark(),
            )
            if (question.multiSelect) {
                Text("（多选）", style = MaterialTheme.typography.bodySmall, color = themeTextGrey())
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
                Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
                    androidx.compose.material3.Button(
                        onClick = {
                            val answer = if (question.multiSelect) selected.joinToString(",") else selected.firstOrNull() ?: ""
                            if (answer.isNotBlank()) onAnswer(answer)
                        },
                        enabled = selected.isNotEmpty(),
                        shape = ShapeTokens.mediumShape,
                    ) { Text("确认") }
                    TextButton(onClick = onDismiss) { Text("跳过") }
                }
            }
            if (readonly && question.answer != null) {
                Text("回答：${question.answer}", style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}
