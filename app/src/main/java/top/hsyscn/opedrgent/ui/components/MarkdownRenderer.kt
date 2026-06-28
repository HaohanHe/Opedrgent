package top.hsyscn.opedrgent.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import top.hsyscn.opedrgent.ui.theme.SpacingTokens
import top.hsyscn.opedrgent.ui.theme.customColors

private val TABLE_LINE_PATTERN = Regex("""^\s*\|.+\|""")

private val SNAPSHOT_PATTERN = Regex("""[\s.,!?;:)\]]""")

private val ORDERED_BULLET_PATTERN = Regex("""^\d+\.\s""")
private val TABLE_SEPARATOR_PATTERN = Regex("""\|[\s\-:\|]+\|""")

private val MD_BOLD_PATTERN = Regex("""\*\*(.+?)\*\*""")
private val MD_ITALIC_PATTERN = Regex("""\*(.+?)\*""")
private val MD_STRIKETHROUGH_PATTERN = Regex("""~~(.+?)~~""")
private val MD_CODE_PATTERN = Regex("""`(.+?)`""")
private val MD_LINK_PATTERN = Regex("""\[([^\]]+)\]\(([^)]+)\)""")
private val MD_CITATION_PATTERN = Regex("""\[S\d+]""")
private val MD_ORDERED_LIST_PATTERN = Regex("""^(\d+)\.\s(.+)""")

fun healPartialMarkdown(text: String): String {
    val sb = StringBuilder(text)
    var inFencedCode = false
    var fenceChar = '`'
    var fenceLen = 0
    val lines = text.split("\n")
    for (line in lines) {
        val trimmed = line.trimStart()
        if (!inFencedCode && trimmed.length >= 3) {
            val firstNonSpace = trimmed.first()
            if (firstNonSpace == '`' || firstNonSpace == '~') {
                val fence = trimmed.takeWhile { it == firstNonSpace }
                if (fence.length >= 3) {
                    inFencedCode = true
                    fenceChar = firstNonSpace
                    fenceLen = fence.length
                    continue
                }
            }
        }
        if (inFencedCode && trimmed.startsWith(fenceChar.toString().repeat(fenceLen)) && trimmed.count { it == fenceChar } >= fenceLen) {
            inFencedCode = false
        }
    }
    if (inFencedCode) {
        sb.append("\n$fenceChar".repeat(fenceLen) + "\n")
    }

    val doubleStarCount = sb.count { it == '*' } - sb.count { it == '*' }
    val openDoubleStar = (sb.toString().count { it == '*' } / 2) * 2
    val unmatchedDoubleStar = sb.toString().split("**").size - 1
    if (unmatchedDoubleStar % 2 != 0) {
        sb.append("**")
    }

    val singleBacktickCount = sb.toString().count { it == '`' }
    if (singleBacktickCount % 2 != 0 && !sb.toString().endsWith("`")) {
        sb.append("`")
    }

    val openBrackets = sb.toString().count { it == '[' }
    val closeBrackets = sb.toString().count { it == ']' }
    if (openBrackets > closeBrackets) {
        val diff = openBrackets - closeBrackets
        sb.append("]".repeat(diff))
    }

    return sb.toString()
}

data class ParsedMarkdownContent(
    val lines: List<String>,
    val isExpanded: Boolean,
)

@Composable
fun MarkdownText(text: String, maxChars: Int, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    val t = text.trim()
    val show = if (!expanded && t.length > maxChars) t.take(maxChars) + "…" else t

    val parsedContent by produceState<ParsedMarkdownContent?>(initialValue = null, key1 = show) {
        value = withContext(Dispatchers.Default) {
            ParsedMarkdownContent(
                lines = show.split("\n"),
                isExpanded = expanded,
            )
        }
    }

    if (parsedContent == null) {
        Text(
            text = show,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        return
    }

    val accentColor = MaterialTheme.customColors.accentBlue

    val isCodeBlock = { line: String -> line.startsWith("```") }
    val isHeading = { line: String -> line.startsWith("#") }
    val isBullet = { line: String -> line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") }
    val isOrderedBullet = { line: String -> ORDERED_BULLET_PATTERN.matches(line.trimStart()) }
    val isTableRow = { line: String -> TABLE_LINE_PATTERN.matches(line.trim()) }
    val isBlockquote = { line: String -> line.trimStart().startsWith("> ") }

    Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm), modifier = modifier) {
        val lines = parsedContent!!.lines
        var inCodeBlock = false
        var codeBlockLang = ""
        val codeBlockLines = mutableListOf<String>()
        val maxLines = 1000  // 大幅放宽渲染行数上限（ContextCompressor 已在上游控制内容大小）
        val effectiveLines = if (lines.size > maxLines) lines.subList(0, maxLines) else lines
        val truncated = lines.size > maxLines
        var i = 0

        while (i < effectiveLines.size) {
            val line = effectiveLines[i]

            if (isCodeBlock(line)) {
                if (inCodeBlock) {
                    val code = codeBlockLines.joinToString("\n")
                    CodeBlock(code = code, language = codeBlockLang)
                    codeBlockLines.clear()
                    codeBlockLang = ""
                    inCodeBlock = false
                } else {
                    inCodeBlock = true
                    codeBlockLang = line.removePrefix("```").trim()
                }
                i++
                continue
            }

            if (inCodeBlock) {
                codeBlockLines.add(line)
                i++
                continue
            }

            if (isTableRow(line)) {
                val tableLines = mutableListOf<String>()
                while (i < lines.size && isTableRow(lines[i])) {
                    tableLines.add(lines[i])
                    i++
                }
                MarkdownTable(tableLines, accentColor)
                continue
            }

            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                Spacer(Modifier.height(SpacingTokens.sm))
                i++
                continue
            }

            when {
                isHeading(trimmed) -> {
                    val level = trimmed.takeWhile { it == '#' }.length
                    val headingText = trimmed.removePrefix("#".repeat(level)).trim()
                    val style = when (level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }
                    Spacer(modifier = Modifier.height(SpacingTokens.sm))
                    Text(text = headingText, fontWeight = FontWeight.Bold, style = style, color = MaterialTheme.colorScheme.onSurface)
                }
                isOrderedBullet(trimmed) -> {
                    Text(buildAnnotatedString {
                        appendMarkdownInline(trimmed, MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceContainerHigh, accentColor, MaterialTheme.typography.bodySmall.fontSize)
                    })
                }
                isBullet(trimmed) -> {
                    val bulletText = trimmed.removePrefix("- ").removePrefix("* ").trim()
                    Text(buildAnnotatedString {
                        append("• ")
                        appendMarkdownInline(bulletText, MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceContainerHigh, accentColor, MaterialTheme.typography.bodySmall.fontSize)
                    })
                }
                isBlockquote(trimmed) -> {
                    val quoteText = trimmed.removePrefix("> ").trim()
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Spacer(
                            modifier = Modifier
                                .width(SpacingTokens.xxs)
                                .height(20.dp)
                                .clip(ShapeTokens.extraSmallShape)
                                .background(accentColor.copy(alpha = 0.4f))
                        )
                        Spacer(modifier = Modifier.width(SpacingTokens.sm))
                        Text(
                            text = buildAnnotatedString { appendMarkdownInline(quoteText, MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceContainerHigh, accentColor, MaterialTheme.typography.bodySmall.fontSize) },
                            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                trimmed.startsWith("[S") && trimmed.length < 10 -> {
                    CitationPill(text = trimmed)
                }
                else -> {
                    Text(buildAnnotatedString { appendMarkdownInline(trimmed, MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceContainerHigh, accentColor, MaterialTheme.typography.bodySmall.fontSize) })
                }
            }
            i++
        }
        if (inCodeBlock && codeBlockLines.isNotEmpty()) {
            val code = codeBlockLines.joinToString("\n")
            CodeBlock(code = code, language = codeBlockLang)
        }
        if (truncated) {
            Text(
                text = "... (内容过长，已截断显示)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (t.length > maxChars) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起" else "展开", color = accentColor)
            }
        }
    }
}

@Composable
fun MarkdownTable(tableLines: List<String>, accentColor: Color) {
    val cleanLines = tableLines.filter { it.trim().startsWith("|") }.map { it.trim() }

    if (cleanLines.isEmpty()) return

    if (cleanLines.size == 1) {
        val cells = parseTableRow(cleanLines[0])
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = SpacingTokens.xs)) {
            cells.forEach { cell ->
                Card(
                    shape = ShapeTokens.extraSmallShape,
                    colors = CardDefaults.cardColors(containerColor = accentColor.copy(alpha = 0.08f))
                ) {
                    Text(
                        text = cell.trim(),
                        modifier = Modifier.padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xs),
                        style = MaterialTheme.typography.bodySmall,
                        color = accentColor,
                    )
                }
                if (cell != cells.last()) Spacer(Modifier.width(SpacingTokens.xs))
            }
        }
        return
    }

    var sepIdx = cleanLines.indexOfFirst { line ->
        TABLE_SEPARATOR_PATTERN.matches(line)
    }

    if (sepIdx < 1) {
        val colCounts = cleanLines.map { parseTableRow(it).size }.distinct()
        if (colCounts.size == 1 && colCounts.first() >= 2) {
            sepIdx = 0
        } else {
            sepIdx = 0
        }
    }

    val headerLine = if (sepIdx > 0) cleanLines[sepIdx - 1] else cleanLines[0]
    val headers = parseTableRow(headerLine)
    val aligns = if (sepIdx > 0 && sepIdx < cleanLines.size) {
        parseAlignments(cleanLines[sepIdx])
    } else {
        List(headers.size) { "left" }
    }
    val dataRows = if (sepIdx > 0) {
        cleanLines.drop(sepIdx + 1).filter { it.isNotEmpty() }
    } else {
        cleanLines.drop(1)
    }.map { parseTableRow(it) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = SpacingTokens.xs),
        shape = ShapeTokens.mediumShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(SpacingTokens.sm)) {
            MarkdownTableRow(headers, aligns, isHeader = true, accentColor = accentColor)
            if (dataRows.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = SpacingTokens.xs), color = MaterialTheme.colorScheme.outlineVariant)
            }
            dataRows.forEach { cells ->
                MarkdownTableRow(cells, aligns, isHeader = false, accentColor = accentColor)
            }
        }
    }
}

@Composable
private fun MarkdownTableRow(
    cells: List<String>,
    aligns: List<String>,
    isHeader: Boolean,
    accentColor: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = SpacingTokens.xl),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cells.forEachIndexed { idx, cell ->
            val align = aligns.getOrNull(idx) ?: "left"
            val textAlign = when (align) {
                "center" -> TextAlign.Center
                "right" -> TextAlign.Right
                else -> TextAlign.Start
            }
            Text(
                text = cell.trim(),
                modifier = Modifier.weight(1f).padding(horizontal = SpacingTokens.xs, vertical = SpacingTokens.xxs),
                style = if (isHeader) MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                else MaterialTheme.typography.bodySmall,
                textAlign = textAlign,
                color = if (isHeader) accentColor else MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (idx < cells.lastIndex) {
                Spacer(modifier = Modifier.width(1.dp).height(SpacingTokens.lg).background(MaterialTheme.colorScheme.outlineVariant))
            }
        }
    }
}

private fun parseTableRow(line: String): List<String> {
    return line.split("|").filter { it.isNotEmpty() }.map { it.trim() }
}

private fun parseAlignments(sepLine: String): List<String> {
    return sepLine.split("|").filter { it.isNotEmpty() }.map { cell ->
        val t = cell.trim()
        when {
            t.startsWith(":") && t.endsWith(":") -> "center"
            t.endsWith(":") -> "right"
            else -> "left"
        }
    }
}

fun AnnotatedString.Builder.appendMarkdownInline(
    text: String,
    strikeColor: Color,
    inlineCodeBackground: Color,
    accentColor: Color,
    inlineCodeFontSize: TextUnit,
) {
    val boldPattern = MD_BOLD_PATTERN
    val italicPattern = MD_ITALIC_PATTERN
    val strikethroughPattern = MD_STRIKETHROUGH_PATTERN
    val codePattern = MD_CODE_PATTERN
    val linkPattern = MD_LINK_PATTERN
    val citationPattern = MD_CITATION_PATTERN
    val orderedListPattern = MD_ORDERED_LIST_PATTERN

    var remaining = text
    while (remaining.isNotEmpty()) {
        val firstBold = boldPattern.find(remaining)
        val firstItalic = italicPattern.find(remaining)
        val firstStrike = strikethroughPattern.find(remaining)
        val firstCode = codePattern.find(remaining)
        val firstLink = linkPattern.find(remaining)
        val firstCitation = citationPattern.find(remaining)
        val firstOrdered = if (remaining == text) orderedListPattern.find(remaining) else null

        val first = listOfNotNull(
            firstBold, firstItalic, firstStrike, firstCode, firstLink, firstCitation, firstOrdered
        ).minByOrNull { it.range.first }

        if (first == null) {
            append(remaining)
            break
        }

        if (first.range.first > 0) {
            append(remaining.substring(0, first.range.first))
        }

        when (first) {
            firstBold -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(first.groupValues[1])
                }
                remaining = remaining.substring(first.range.last + 1)
            }
            firstItalic -> {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(first.groupValues[1])
                }
                remaining = remaining.substring(first.range.last + 1)
            }
            firstStrike -> {
                withStyle(SpanStyle(fontSize = inlineCodeFontSize, color = strikeColor)) {
                    append(first.groupValues[1])
                }
                remaining = remaining.substring(first.range.last + 1)
            }
            firstCode -> {
                withStyle(SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = inlineCodeFontSize,
                    color = accentColor,
                    background = inlineCodeBackground,
                )) {
                    append(first.groupValues[1])
                }
                remaining = remaining.substring(first.range.last + 1)
            }
            firstLink -> {
                withStyle(SpanStyle(
                    color = accentColor,
                    fontWeight = FontWeight.Medium,
                )) {
                    append(first.groupValues[1])
                }
                remaining = remaining.substring(first.range.last + 1)
            }
            firstCitation -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = accentColor)) {
                    append(first.value)
                }
                remaining = remaining.substring(first.range.last + 1)
            }
            firstOrdered -> {
                append(first.groupValues[1])
                append(". ")
                withStyle(SpanStyle()) {
                    appendMarkdownInline(first.groupValues[2], strikeColor, inlineCodeBackground, accentColor, inlineCodeFontSize)
                }
                remaining = remaining.substring(first.range.last + 1)
            }
            else -> {
                append(remaining)
                break
            }
        }
    }
}

@Composable
fun StreamingMarkdownText(text: String, maxChars: Int) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    val t = text.trim()
    val show = if (!expanded && t.length > maxChars) t.take(maxChars) + "…" else t

    val healed = remember(show) { healPartialMarkdown(show) }

    MarkdownText(text = healed, maxChars = maxChars)
}
