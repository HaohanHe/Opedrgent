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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.model.MessagePart
import top.hsyscn.opedrgent.model.ReasoningPart
import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.model.ToolStateType
import top.hsyscn.opedrgent.ui.theme.ElevationTokens
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import top.hsyscn.opedrgent.ui.theme.SizeTokens
import top.hsyscn.opedrgent.ui.theme.SpacingTokens
import top.hsyscn.opedrgent.ui.theme.themeTextDark
import top.hsyscn.opedrgent.ui.theme.themeTextGrey

@Composable
fun StreamingCard(
    text: String,
    reasoning: String,
    toolParts: List<ToolPart>,
    phase: String = "",
) {
    // 直接显示流式文本，不再做额外的打字机效果，确保正文与上游 state 同步实时展示。
    val displayText = text

    val hasText = displayText.trim().isNotEmpty()
    val hasReasoning = reasoning.isNotEmpty()
    val hasTools = toolParts.isNotEmpty()
    val isToolRunning = toolParts.any { it.state.status == ToolStateType.RUNNING }
    val hasCompletedSources = toolParts.any { it.tool == "web_search" && it.state.status == ToolStateType.COMPLETED }

    val showLoading = !hasText && !hasReasoning && !hasTools

    val aiReplyingLabel = stringResource(R.string.cd_ai_replying)
    val thinkingLabel = stringResource(R.string.cd_thinking)
    val processingLabel = stringResource(R.string.state_processing)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = ShapeTokens.largeShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = ElevationTokens.sm),
    ) {
        Column(
            modifier = Modifier.padding(SpacingTokens.md),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.streaming_label_assistant), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), color = themeTextDark())
                if (isToolRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(SizeTokens.iconSm)
                            .semantics {
                                contentDescription = thinkingLabel
                                stateDescription = processingLabel
                            },
                        strokeWidth = SizeTokens.borderWidth,
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
                    isComplete = hasText,
                )
            }

            if (hasTools) {
                ToolStatusGroup(toolParts = toolParts)
            }

            if (hasCompletedSources) {
                SourceLinksSection(toolParts = toolParts)
            }

            if (hasText) {
                // 流式过程中也使用 Markdown 渲染，确保表格、加粗、链接等格式实时正确展示。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            stateDescription = aiReplyingLabel
                            liveRegion = LiveRegionMode.Polite
                        }
                ) {
                    StreamingMarkdownText(text = displayText, maxChars = Int.MAX_VALUE)
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
    val cursorWidth = with(LocalDensity.current) { SizeTokens.borderWidth.toSp() }
    val inlineContent = mapOf(
        cursorMarker to InlineTextContent(
            placeholder = Placeholder(
                width = cursorWidth,
                height = MaterialTheme.typography.bodyLarge.fontSize,
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
        Canvas(modifier = Modifier.size(SizeTokens.iconMd, SpacingTokens.sm)) {
            val radius = SizeTokens.progressTrackHeight.toPx()
            val spacing = SpacingTokens.sm.toPx()
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
            text = phase.ifBlank { stringResource(R.string.streaming_thinking_default) },
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
                contentDescription = stringResource(R.string.streaming_cd_thinking),
                modifier = Modifier.height(SizeTokens.iconSm),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(SpacingTokens.xs))
            Text(
                stringResource(if (expanded) R.string.streaming_action_hide_thinking else R.string.streaming_action_show_thinking),
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
        ToolStateType.PENDING -> stringResource(R.string.tool_state_pending)
        ToolStateType.RUNNING -> {
            val q = extractCleanQuery(toolPart.state.input)
            val u = toolPart.state.input["url"]
            when {
                q.isNotBlank() -> stringResource(R.string.tool_state_searching_with_query, q)
                !u.isNullOrBlank() -> stringResource(
                    R.string.tool_state_reading_url,
                    runCatching { java.net.URL(u).host }.getOrDefault(u.take(30))
                )
                else -> stringResource(R.string.tool_state_running)
            }
        }
        ToolStateType.COMPLETED -> {
            when (toolPart.tool) {
                "web_search" -> {
                    val q = extractCleanQuery(toolPart.state.input)
                    if (q.isNotBlank()) stringResource(R.string.tool_state_searched_query, q) else stringResource(R.string.tool_state_search_completed)
                }
                "read_url" -> stringResource(R.string.tool_state_read_completed)
                else -> stringResource(R.string.tool_state_completed)
            }
        }
        ToolStateType.ERROR -> stringResource(
            R.string.tool_state_error_with_message,
            toolPart.state.error?.take(30) ?: stringResource(R.string.tool_state_unknown)
        )
        ToolStateType.SOURCE_ADDED -> stringResource(R.string.tool_state_source_added)
        ToolStateType.PARTIAL_TIMEOUT -> stringResource(
            R.string.tool_state_partial_timeout_with_message,
            toolPart.state.error?.take(30) ?: stringResource(R.string.tool_state_timeout)
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.xs),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(R.string.tool_state_cd),
            modifier = Modifier.size(SizeTokens.iconSm),
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
                    withStyle(SpanStyle(fontWeight = MaterialTheme.typography.titleSmall.fontWeight)) {
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
                modifier = Modifier.size(SizeTokens.iconXs),
                strokeWidth = SizeTokens.borderWidth,
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
                contentDescription = stringResource(R.string.tool_state_cd),
                modifier = Modifier.size(SizeTokens.iconSm),
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
                contentDescription = stringResource(if (expanded) R.string.cd_collapse else R.string.cd_expand),
                modifier = Modifier.size(SizeTokens.iconSm),
                tint = themeTextGrey(),
            )
            if (hasRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(SizeTokens.iconXs),
                    strokeWidth = SizeTokens.borderWidth,
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
                verticalArrangement = Arrangement.spacedBy(SpacingTokens.xs),
            ) {
                parts.forEach { tp ->
                    ToolStatusRowWithResults(toolPart = tp)
                }
            }
        }
    }
}

/**
 * 单个工具调用行 + 该调用的搜索结果详情（展开组时使用）。
 *
 * 解决用户反馈："2/2 点进去后只能看见搜索词，看不到具体搜了哪些内容"
 * 之前 ToolStatusRow 仅显示 "搜索: <query>"，未展示该次调用实际找到的结果。
 * 现在在查询行下方追加本次调用返回的来源卡片（标题/摘要/域名），与查询视图一致。
 */
@Composable
private fun ToolStatusRowWithResults(toolPart: ToolPart) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val sources = remember(toolPart) {
        if (toolPart.tool == "web_search" && toolPart.state.status == ToolStateType.COMPLETED) {
            extractSourcesFromToolParts(listOf(toolPart))
        } else {
            emptyList()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.xxs)) {
        ToolStatusRow(toolPart = toolPart)
        if (sources.isNotEmpty()) {
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .heightIn(max = SizeTokens.citationListMaxHeight)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(SpacingTokens.xxs),
            ) {
                sources.forEachIndexed { idx, source ->
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
                contentDescription = stringResource(R.string.streaming_cd_sources),
                modifier = Modifier.size(SizeTokens.iconSm),
                tint = themeTextGrey(),
            )
            Text(
                text = stringResource(R.string.streaming_sources_count, sources.size),
                style = MaterialTheme.typography.bodySmall,
                color = themeTextDark(),
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = stringResource(if (expanded) R.string.cd_collapse else R.string.cd_expand),
                modifier = Modifier.size(SizeTokens.iconMd),
                tint = themeTextGrey(),
            )
        }
        if (expanded) {
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .heightIn(max = SizeTokens.citationListMaxHeight)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
            ) {
                sources.forEachIndexed { idx, source ->
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
@Composable
private fun toolDisplayName(tool: String): String = when (tool) {
    "web_search" -> stringResource(R.string.tool_name_web_search)
    "read_url" -> stringResource(R.string.tool_name_read_url)
    "step_search" -> stringResource(R.string.tool_name_web_search)
    "step_rag" -> stringResource(R.string.tool_name_knowledge_base)
    "speech_to_text" -> stringResource(R.string.tool_name_speech_to_text)
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
                    contentDescription = stringResource(R.string.tool_state_cd),
                    modifier = Modifier.size(SizeTokens.iconSm),
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
                        modifier = Modifier.size(SizeTokens.iconXs),
                        strokeWidth = SizeTokens.borderWidth,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (expanded) {
                Spacer(Modifier.height(SpacingTokens.sm))
                if (toolPart.state.input.isNotEmpty()) {
                    Text(
                        stringResource(
                            R.string.tool_state_parameters_prefix,
                            toolPart.state.input.entries.joinToString(", ") { "${it.key}=${it.value}" }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = themeTextDark(),
                    )
                }
                if (!toolPart.state.output.isNullOrBlank()) {
                    Spacer(Modifier.height(SpacingTokens.xs))
                    Text(
                        text = toolPart.state.output.take(500) + if (toolPart.state.output.length > 500) "…" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = themeTextDark(),
                    )
                }
                if (!toolPart.state.error.isNullOrBlank()) {
                    Spacer(Modifier.height(SpacingTokens.xs))
                    Text(
                        text = stringResource(R.string.tool_state_error_prefix, toolPart.state.error),
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
                text = question.prompt.ifEmpty { stringResource(R.string.question_tool_default_prompt) },
                style = MaterialTheme.typography.titleSmall,
                color = themeTextDark(),
            )
            if (question.multiSelect) {
                Text(stringResource(R.string.question_tool_multi_select_hint), style = MaterialTheme.typography.bodySmall, color = themeTextGrey())
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
                    ) { Text(stringResource(R.string.action_confirm)) }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_skip)) }
                }
            }
            if (readonly && question.answer != null) {
                Text(stringResource(R.string.question_tool_answer_prefix, question.answer), style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}
