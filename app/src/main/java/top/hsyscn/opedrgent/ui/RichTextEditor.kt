package top.hsyscn.opedrgent.ui

import top.hsyscn.opedrgent.ui.theme.AccentBlue
import top.hsyscn.opedrgent.ui.theme.InterviewPurple
import top.hsyscn.opedrgent.ui.theme.InterviewDarkBg
import top.hsyscn.opedrgent.ui.theme.WarningColor
import top.hsyscn.opedrgent.ui.theme.SuccessGreen
import top.hsyscn.opedrgent.ui.theme.AccentOrange
import top.hsyscn.opedrgent.ui.theme.customColors
import top.hsyscn.opedrgent.ui.theme.SpacingTokens
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import android.graphics.Typeface
import android.text.Spannable
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.res.stringResource
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.note.SpanRepresentation

/**
 * Rich text editor composable wrapping Android EditText with Spannable formatting.
 *
 * Provides Notally-style formatting: select text, then apply bold/italic/etc.
 * Returns both plain text and span representations for persistence.
 */
@Composable
fun RichTextEditor(
    initialText: String,
    initialSpans: String,
    onTextChange: (text: String) -> Unit,
    onSpansChange: (spansJson: String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = stringResource(R.string.note_editor_start_writing),
    enabled: Boolean = true,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    var lastAppliedSpansJson by remember { mutableStateOf(initialSpans) }
    var selectionStart by remember { mutableIntStateOf(0) }
    var selectionEnd by remember { mutableIntStateOf(0) }
    var showToolbar by remember { mutableStateOf(false) }
    var isProgrammaticChange by remember { mutableStateOf(false) }

    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()

    val editText = remember {
        SelectionAwareEditText(context).apply {
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            gravity = Gravity.TOP or Gravity.START
            setPadding(0, 0, 0, 0)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setTextColor(textColor)
            textSize = 16f
            setLineSpacing(0f, 1.3f)
            setHorizontallyScrolling(false)
            maxLines = Int.MAX_VALUE
            isCursorVisible = true
            setTextIsSelectable(true)

            // Add text watcher for change tracking and span extraction
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    if (!isProgrammaticChange && s != null) {
                        onTextChange(s.toString())
                        val spans = extractSpans(s)
                        val json = SpanRepresentation.toJson(spans)
                        if (json != lastAppliedSpansJson) {
                            lastAppliedSpansJson = json
                            onSpansChange(json)
                        }
                    }
                }
            })

            // 事件驱动：选择变化时直接回调，替代 100ms 轮询，降低 CPU 占用。
            onSelectionChange = { start, end ->
                if (start != selectionStart || end != selectionEnd) {
                    selectionStart = start
                    selectionEnd = end
                    showToolbar = start != end && start >= 0 && end > start
                }
            }
        }
    }

    // Load initial text and spans
    LaunchedEffect(initialText, initialSpans) {
        isProgrammaticChange = true
        if (initialText.isNotEmpty()) {
            if (initialSpans.isNotBlank()) {
                val spans = SpanRepresentation.fromJson(initialSpans)
                applySpansToEditText(editText, initialText, spans)
            } else {
                editText.setText(initialText)
                editText.setSelection(initialText.length)
            }
        } else {
            editText.text?.clear()
        }
        lastAppliedSpansJson = initialSpans
        isProgrammaticChange = false
    }

    DisposableEffect(Unit) {
        onDispose {
            focusManager.clearFocus()
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { editText },
            modifier = Modifier.fillMaxWidth(),
            update = { et ->
                et.isEnabled = enabled
            },
        )

        // Placeholder when empty
        if (editText.text.isNullOrEmpty()) {
            Box(
                modifier = Modifier
                    .padding(horizontal = SpacingTokens.xs, vertical = SpacingTokens.sm)
                    .align(Alignment.TopStart),
            ) {
                androidx.compose.material3.Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
            }
        }

        // Selection formatting toolbar
        AnimatedVisibility(
            visible = showToolbar,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = SpacingTokens.sm),
        ) {
            SelectionFormatToolbar(
                onBold = { toggleBold(editText, selectionStart, selectionEnd) },
                onItalic = { toggleItalic(editText, selectionStart, selectionEnd) },
                onMonospace = { toggleMonospace(editText, selectionStart, selectionEnd) },
                onStrikethrough = { toggleStrikethrough(editText, selectionStart, selectionEnd) },
                onLink = { /* link handled via dialog in parent */ },
            )
        }
    }
}

// ==================== Span Manipulation ====================

private fun applySpan(
    editable: android.text.Editable,
    spanType: SpanType,
    start: Int,
    end: Int,
) {
    if (start < 0 || end > editable.length || start >= end) return
    when (spanType) {
        SpanType.BOLD -> editable.setSpan(
            StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        SpanType.ITALIC -> editable.setSpan(
            StyleSpan(Typeface.ITALIC), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        SpanType.MONOSPACE -> editable.setSpan(
            TypefaceSpan("monospace"), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        SpanType.STRIKETHROUGH -> editable.setSpan(
            StrikethroughSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        SpanType.LINK -> editable.setSpan(
            URLSpan(""), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }
}

private fun removeSpan(
    editable: android.text.Editable,
    spanType: SpanType,
    start: Int,
    end: Int,
) {
    if (start < 0 || end > editable.length || start >= end) return
    val spans = when (spanType) {
        SpanType.BOLD -> editable.getSpans(start, end, StyleSpan::class.java)
            .filter { it.style == Typeface.BOLD }
        SpanType.ITALIC -> editable.getSpans(start, end, StyleSpan::class.java)
            .filter { it.style == Typeface.ITALIC }
        SpanType.MONOSPACE -> editable.getSpans(start, end, TypefaceSpan::class.java).toList()
        SpanType.STRIKETHROUGH -> editable.getSpans(start, end, StrikethroughSpan::class.java).toList()
        SpanType.LINK -> editable.getSpans(start, end, URLSpan::class.java).toList()
    }
    spans.forEach { editable.removeSpan(it) }
}

private fun hasSpan(
    editable: android.text.Editable,
    spanType: SpanType,
    start: Int,
    end: Int,
): Boolean {
    if (start < 0 || end > editable.length || start >= end) return false
    return when (spanType) {
        SpanType.BOLD -> editable.getSpans(start, end, StyleSpan::class.java)
            .any { it.style == Typeface.BOLD }
        SpanType.ITALIC -> editable.getSpans(start, end, StyleSpan::class.java)
            .any { it.style == Typeface.ITALIC }
        SpanType.MONOSPACE -> editable.getSpans(start, end, TypefaceSpan::class.java).isNotEmpty()
        SpanType.STRIKETHROUGH -> editable.getSpans(start, end, StrikethroughSpan::class.java).isNotEmpty()
        SpanType.LINK -> editable.getSpans(start, end, URLSpan::class.java).isNotEmpty()
    }
}

private fun toggleSpan(
    editText: EditText,
    spanType: SpanType,
    start: Int,
    end: Int,
) {
    val editable = editText.text ?: return
    if (hasSpan(editable, spanType, start, end)) {
        removeSpan(editable, spanType, start, end)
    } else {
        applySpan(editable, spanType, start, end)
    }
}

private fun toggleBold(editText: EditText, start: Int, end: Int) =
    toggleSpan(editText, SpanType.BOLD, start, end)

private fun toggleItalic(editText: EditText, start: Int, end: Int) =
    toggleSpan(editText, SpanType.ITALIC, start, end)

private fun toggleMonospace(editText: EditText, start: Int, end: Int) =
    toggleSpan(editText, SpanType.MONOSPACE, start, end)

private fun toggleStrikethrough(editText: EditText, start: Int, end: Int) =
    toggleSpan(editText, SpanType.STRIKETHROUGH, start, end)

// ==================== Span Extraction ====================

private enum class SpanType {
    BOLD, ITALIC, MONOSPACE, STRIKETHROUGH, LINK
}

/**
 * Extract SpanRepresentations from an Editable.
 * Handles overlapping spans by splitting into non-overlapping segments.
 */
fun extractSpans(editable: android.text.Editable): List<SpanRepresentation> {
    val length = editable.length
    if (length == 0) return emptyList()

    val result = mutableListOf<SpanRepresentation>()

    // Collect all span boundaries
    val boundaries = sortedSetOf(0, length)
    val allSpans = mutableListOf<Triple<Int, Int, SpanType>>()

    // Bold
    editable.getSpans(0, length, StyleSpan::class.java).forEach { span ->
        if (span.style == Typeface.BOLD) {
            val s = editable.getSpanStart(span)
            val e = editable.getSpanEnd(span)
            boundaries.addAll(listOf(s, e))
            allSpans.add(Triple(s, e, SpanType.BOLD))
        }
    }
    // Italic
    editable.getSpans(0, length, StyleSpan::class.java).forEach { span ->
        if (span.style == Typeface.ITALIC) {
            val s = editable.getSpanStart(span)
            val e = editable.getSpanEnd(span)
            boundaries.addAll(listOf(s, e))
            allSpans.add(Triple(s, e, SpanType.ITALIC))
        }
    }
    // Monospace
    editable.getSpans(0, length, TypefaceSpan::class.java).forEach { span ->
        val s = editable.getSpanStart(span)
        val e = editable.getSpanEnd(span)
        boundaries.addAll(listOf(s, e))
        allSpans.add(Triple(s, e, SpanType.MONOSPACE))
    }
    // Strikethrough
    editable.getSpans(0, length, StrikethroughSpan::class.java).forEach { span ->
        val s = editable.getSpanStart(span)
        val e = editable.getSpanEnd(span)
        boundaries.addAll(listOf(s, e))
        allSpans.add(Triple(s, e, SpanType.STRIKETHROUGH))
    }
    // Link
    editable.getSpans(0, length, URLSpan::class.java).forEach { span ->
        val s = editable.getSpanStart(span)
        val e = editable.getSpanEnd(span)
        boundaries.addAll(listOf(s, e))
        allSpans.add(Triple(s, e, SpanType.LINK))
    }

    if (allSpans.isEmpty()) return emptyList()

    // Build non-overlapping segments
    val sortedBounds = boundaries.toList()
    for (i in 0 until sortedBounds.size - 1) {
        val segStart = sortedBounds[i]
        val segEnd = sortedBounds[i + 1]
        if (segStart >= segEnd) continue

        val sp = SpanRepresentation(start = segStart, end = segEnd)
        for ((s, e, type) in allSpans) {
            if (s <= segStart && e >= segEnd) {
                when (type) {
                    SpanType.BOLD -> sp.bold = true
                    SpanType.ITALIC -> sp.italic = true
                    SpanType.MONOSPACE -> sp.monospace = true
                    SpanType.STRIKETHROUGH -> sp.strikethrough = true
                    SpanType.LINK -> sp.link = true
                }
            }
        }
        if (sp.isNotUseless()) {
            result.add(sp)
        }
    }

    return result
}

/**
 * Apply a list of SpanRepresentations to an EditText.
 */
private fun applySpansToEditText(
    editText: EditText,
    text: String,
    spans: List<SpanRepresentation>,
) {
    editText.setText(text, android.widget.TextView.BufferType.SPANNABLE)
    val editable = editText.text ?: return
    for (span in spans) {
        if (!span.isNotUseless()) continue
        val start = span.start.coerceIn(0, editable.length)
        val end = span.end.coerceIn(0, editable.length)
        if (start >= end) continue
        if (span.bold) applySpan(editable, SpanType.BOLD, start, end)
        if (span.italic) applySpan(editable, SpanType.ITALIC, start, end)
        if (span.monospace) applySpan(editable, SpanType.MONOSPACE, start, end)
        if (span.strikethrough) applySpan(editable, SpanType.STRIKETHROUGH, start, end)
        if (span.link) applySpan(editable, SpanType.LINK, start, end)
    }
    editText.setSelection(text.length)
}

// ==================== Selection Format Toolbar ====================

@Composable
private fun SelectionFormatToolbar(
    onBold: () -> Unit,
    onItalic: () -> Unit,
    onMonospace: () -> Unit,
    onStrikethrough: () -> Unit,
    onLink: () -> Unit,
) {
    Surface(
        shape = ShapeTokens.extraLargeShape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
        tonalElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xs),
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FormatToolbarButton(
                icon = Icons.Default.FormatBold,
                description = "Bold",
                onClick = onBold,
            )
            FormatToolbarButton(
                icon = Icons.Default.FormatItalic,
                description = "Italic",
                onClick = onItalic,
            )
            FormatToolbarButton(
                icon = Icons.Default.Code,
                description = "Monospace",
                onClick = onMonospace,
            )
            FormatToolbarButton(
                icon = Icons.Default.FormatStrikethrough,
                description = "Strikethrough",
                onClick = onStrikethrough,
            )
            FormatToolbarButton(
                icon = Icons.Default.Link,
                description = "Link",
                onClick = onLink,
            )
        }
    }
}

@Composable
private fun FormatToolbarButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(36.dp)
            .background(Color.Transparent, CircleShape),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 选择感知 EditText：当光标/选区变化时通过回调通知调用方。
 *
 * 替代 100ms 轮询，避免后台持续占用 CPU 与触发协程调度。
 */
private class SelectionAwareEditText(context: android.content.Context) : EditText(context) {
    var onSelectionChange: ((Int, Int) -> Unit)? = null

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        onSelectionChange?.invoke(selStart, selEnd)
    }
}
