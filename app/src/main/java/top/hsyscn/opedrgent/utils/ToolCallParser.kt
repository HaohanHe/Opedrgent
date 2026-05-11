package top.hsyscn.opedrgent.utils

import top.hsyscn.opedrgent.model.QuestionOption
import top.hsyscn.opedrgent.model.QuestionPart
import top.hsyscn.opedrgent.model.ReasoningPart
import top.hsyscn.opedrgent.model.ToolState
import top.hsyscn.opedrgent.model.ToolStateType
import top.hsyscn.opedrgent.model.ToolPart

data class ParsedStreamChunk(
    val textDelta: String = "",
    val reasoningDelta: String = "",
    val toolCall: ToolCallBlock? = null,
)

data class ToolCallBlock(
    val toolName: String,
    val params: Map<String, String>,
    val questionPart: QuestionPart? = null,
)

object ToolCallParser {

    private val TOOL_CALL_REGEX = Regex(
        "<tool_call[^>]*>\\s*(.*?)\\s*</tool_call\\s*>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    private val TOOL_CALL_OPEN_TAG = Regex(
        "<tool_call[^>]*>",
        RegexOption.IGNORE_CASE,
    )

    private val THINKING_REGEX = Regex(
        "<thinking>\\s*(.*?)\\s*</thinking>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    private val NAME_ATTR_REGEX = Regex("""name\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)

    private val PARAM_TAG_REGEX = Regex(
        """<parameter\s+name\s*=\s*"([^"]*)"\s*>\s*(.*?)\s*</parameter>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    private val TAG_VALUE_REGEX = Regex("<(\\w+)>\\s*(.*?)\\s*</\\1>", RegexOption.DOT_MATCHES_ALL)

    fun extractTextOutsideTags(raw: String): String {
        var text = raw
        text = TOOL_CALL_REGEX.replace(text, "")
        text = THINKING_REGEX.replace(text, "")
        return text.trim()
    }

    fun parseChunk(chunk: String): ParsedStreamChunk {
        val reasoningDelta = THINKING_REGEX.find(chunk)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val textDelta = extractTextOutsideTags(chunk)
        val toolCall = parseToolCall(chunk)
        return ParsedStreamChunk(
            textDelta = textDelta,
            reasoningDelta = reasoningDelta,
            toolCall = toolCall,
        )
    }

    fun parseToolCall(text: String): ToolCallBlock? {
        val match = TOOL_CALL_REGEX.find(text) ?: return null
        val body = match.groupValues[1].trim()
        val openTag = TOOL_CALL_OPEN_TAG.find(text)?.value.orEmpty()

        val attrName = NAME_ATTR_REGEX.find(openTag)?.groupValues?.getOrNull(1)?.trim().orEmpty()

        val params = mutableMapOf<String, String>()
        var toolName = ""

        PARAM_TAG_REGEX.findAll(body).forEach { pm ->
            val key = pm.groupValues[1].trim()
            val value = pm.groupValues[2].trim()
            if (key.isNotEmpty()) {
                if (key == "name") {
                    toolName = value
                } else {
                    params[key] = value
                }
            }
        }

        if (toolName.isEmpty()) {
            TAG_VALUE_REGEX.findAll(body).forEach { tagMatch ->
                val tag = tagMatch.groupValues[1].lowercase()
                val value = tagMatch.groupValues[2].trim()
                when (tag) {
                    "tool_name" -> toolName = value
                    "parameter" -> { /* skip, already handled */ }
                    else -> if (!params.containsKey(tag)) params[tag] = value
                }
            }
        }

        if (toolName.isEmpty()) toolName = attrName
        if (toolName.isEmpty()) return null

        val questionPart = if (toolName == "question" || toolName == "ask_user") {
            parseQuestionPart(body)
        } else null

        return ToolCallBlock(
            toolName = toolName,
            params = params.toMap(),
            questionPart = questionPart,
        )
    }

    private fun parseQuestionPart(body: String): QuestionPart? {
        val prompt = extractTagValue(body, "prompt").orEmpty()
        val multiSelect = extractTagValue(body, "multi_select")?.lowercase() == "true"
        val optionsText = extractTagValue(body, "options").orEmpty()

        val options = mutableListOf<QuestionOption>()
        val optionRegex = Regex(
            "<option>\\s*<value>\\s*(.*?)\\s*</value>\\s*<label>\\s*(.*?)\\s*</label>\\s*</option>",
            RegexOption.DOT_MATCHES_ALL,
        )
        optionRegex.findAll(optionsText).forEach { optMatch ->
            val value = optMatch.groupValues[1].trim()
            val label = optMatch.groupValues[2].trim()
            if (value.isNotEmpty() && label.isNotEmpty()) {
                options.add(QuestionOption(value = value, label = label))
            }
        }

        if (prompt.isEmpty() && options.isEmpty()) return null

        return QuestionPart(
            prompt = prompt,
            multiSelect = multiSelect,
            options = options,
        )
    }

    fun parseToolCallFromComplete(text: String): List<ToolPart> {
        val parts = mutableListOf<ToolPart>()
        TOOL_CALL_REGEX.findAll(text).forEach { match ->
            val block = parseToolCallBlock(match.value, match.groupValues[1].trim())
            if (block != null) {
                parts.add(block)
            }
        }
        return parts
    }

    private fun parseToolCallBlock(fullTag: String, body: String): ToolPart? {
        val openTag = TOOL_CALL_OPEN_TAG.find(fullTag)?.value.orEmpty()
        val attrName = NAME_ATTR_REGEX.find(openTag)?.groupValues?.getOrNull(1)?.trim().orEmpty()

        var toolName = attrName
        val params = mutableMapOf<String, String>()

        PARAM_TAG_REGEX.findAll(body).forEach { pm ->
            val key = pm.groupValues[1].trim()
            val value = pm.groupValues[2].trim()
            if (key.isNotEmpty()) {
                if (key == "name") toolName = value
                else params[key] = value
            }
        }

        if (toolName.isEmpty()) {
            TAG_VALUE_REGEX.findAll(body).forEach { tagMatch ->
                val tag = tagMatch.groupValues[1].lowercase()
                val value = tagMatch.groupValues[2].trim()
                when (tag) {
                    "tool_name" -> toolName = value
                    "parameter" -> { /* skip */ }
                    else -> params[tag] = value
                }
            }
        }

        if (toolName.isEmpty()) return null

        return ToolPart(
            tool = toolName,
            state = ToolState(
                status = ToolStateType.PENDING,
                input = params,
                startTime = System.currentTimeMillis(),
            ),
        )
    }

    fun extractThinkingParts(text: String): List<ReasoningPart> {
        val parts = mutableListOf<ReasoningPart>()
        THINKING_REGEX.findAll(text).forEach { match ->
            val content = match.groupValues[1].trim()
            if (content.isNotEmpty()) {
                parts.add(ReasoningPart(text = content))
            }
        }
        return parts
    }

    private fun extractTagValue(text: String, tagName: String): String? {
        val regex = Regex("<$tagName>\\s*(.*?)\\s*</$tagName>", RegexOption.DOT_MATCHES_ALL)
        return regex.find(text)?.groupValues?.getOrNull(1)?.trim()
    }

    fun hasIncompleteToolCall(text: String): Boolean {
        val openCount = Regex("<tool_call[^>]*>", RegexOption.IGNORE_CASE).findAll(text).count()
        val closeCount = Regex("</tool_call\\s*>", RegexOption.IGNORE_CASE).findAll(text).count()
        return openCount > closeCount
    }

    fun hasIncompleteThinking(text: String): Boolean {
        val openCount = Regex("<thinking>", RegexOption.IGNORE_CASE).findAll(text).count()
        val closeCount = Regex("</thinking>", RegexOption.IGNORE_CASE).findAll(text).count()
        return openCount > closeCount
    }

    fun stripToolCalls(text: String): String {
        return TOOL_CALL_REGEX.replace(text, "").trim()
    }

    fun stripThinking(text: String): String {
        return THINKING_REGEX.replace(text, "").trim()
    }

    fun stripAllTags(text: String): String {
        return stripThinking(stripToolCalls(text))
    }
}