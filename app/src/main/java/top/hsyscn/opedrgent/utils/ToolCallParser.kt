package top.hsyscn.opedrgent.utils

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import top.hsyscn.opedrgent.model.QuestionOption
import top.hsyscn.opedrgent.model.QuestionPart
import top.hsyscn.opedrgent.model.ReasoningPart
import top.hsyscn.opedrgent.model.ToolState
import top.hsyscn.opedrgent.model.ToolStateType
import top.hsyscn.opedrgent.model.ToolPart
import java.io.StringReader

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

    private val REASONING_TAGS = listOf(
        "thinking", "reasoning", "thought", "scratchpad", "plan",
    )

    fun cleanReasoningTags(text: String): String {
        var cleaned = text
        for (tag in REASONING_TAGS) {
            cleaned = Regex("<${tag}[^>]*>.*?</${tag}\\s*>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).replace(cleaned, "")
            cleaned = Regex("<${tag}[^>]*>.*$", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).replace(cleaned, "")
            cleaned = Regex("</${tag}\\s*>", RegexOption.IGNORE_CASE).replace(cleaned, "")
        }
        return cleaned.trim()
    }

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
        """<parameter\s+name\s*=\s*"([^"]*)"(?:\s*/|\s*)>\s*(.*?)\s*</parameter\s*>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    private val TAG_VALUE_REGEX = Regex("<(\\w+)>\\s*(.*?)\\s*</\\1>", RegexOption.DOT_MATCHES_ALL)

    /**
     * HTML实体解码（处理模型输出的 &lt; &gt; &amp; 等）
     */
    fun decodeHtmlEntities(text: String): String {
        return text
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
    }

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
        val decodedText = decodeHtmlEntities(text)
        val cleanedText = cleanReasoningTags(decodedText)

        val regexResult = parseToolCallRegex(cleanedText)
        if (regexResult != null) {
            DebugLog.d("ToolCallParser: regex parsing success")
            return regexResult
        }

        // 策略2: XmlPullParser fallback（容错路径，处理复杂/畸形XML）
        val xmlResult = parseToolCallXml(cleanedText)
        if (xmlResult != null) {
            DebugLog.i("ToolCallParser: XML parser fallback success")
            return xmlResult
        }

        DebugLog.w("ToolCallParser: all parsing strategies failed, text preview: ${decodedText.take(100)}")
        return null
    }

    /**
     * 策略1: 正则解析（高性能，适用于标准格式）
     */
    private fun parseToolCallRegex(text: String): ToolCallBlock? {
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

    /**
     * 策略2: XmlPullParser解析（高容错，Android原生支持）
     *
     * 优势：
     * - 标准XML解析，能正确处理嵌套标签、自闭合、实体编码
     * - 对格式偏差容忍度高（多空格、换行、乱序属性等）
     *
     * 适用场景：
     * - 模型输出格式不规范时
     * - 参数值包含特殊字符（< > & "）时
     * - 正则解析失败时的fallback
     */
    private fun parseToolCallXml(text: String): ToolCallBlock? {
        return try {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(text))

            var toolName = ""
            val params = mutableMapOf<String, String>()
            var currentParamName = ""
            var currentParamValue = StringBuilder()
            var inParameter = false
            var eventType = parser.eventType

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val tagName = parser.name.lowercase()
                        when (tagName) {
                            "tool_call" -> {
                                toolName = parser.getAttributeValue(null, "name")?.trim().orEmpty()
                            }
                            "parameter" -> {
                                inParameter = true
                                currentParamName = parser.getAttributeValue(null, "name")?.trim().orEmpty()
                                currentParamValue.clear()
                            }
                            "name" -> {
                                if (!inParameter) {
                                    currentParamValue.clear()
                                }
                            }
                            else -> {
                                if (!inParameter && toolName.isEmpty() && tagName == "tool_name") {
                                    currentParamValue.clear()
                                }
                            }
                        }
                    }

                    XmlPullParser.TEXT -> {
                        if (inParameter) {
                            currentParamValue.append(parser.text?.trim().orEmpty())
                        } else {
                            val textContent = parser.text?.trim().orEmpty()
                            when {
                                !toolName.isEmpty() && currentParamName.isNotEmpty() -> {
                                    params[currentParamName] = textContent
                                    currentParamName = ""
                                }
                                toolName.isEmpty() && parser.name?.lowercase() == "name" -> {
                                    toolName = textContent
                                }
                            }
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        val tagName = parser.name.lowercase()
                        when (tagName) {
                            "parameter" -> {
                                if (currentParamName.isNotEmpty() && currentParamValue.isNotEmpty()) {
                                    params[currentParamName] = currentParamValue.toString().trim()
                                }
                                inParameter = false
                                currentParamName = ""
                            }
                            "name" -> {
                                if (toolName.isEmpty()) {
                                    toolName = currentParamValue.toString().trim()
                                }
                            }
                        }
                    }
                }
                eventType = parser.next()
            }

            if (toolName.isEmpty()) return null

            val questionPart = if (toolName == "question" || toolName == "ask_user") {
                parseQuestionPartFromXml(text)
            } else null

            ToolCallBlock(
                toolName = toolName,
                params = params.toMap(),
                questionPart = questionPart,
            )
        } catch (e: Exception) {
            DebugLog.w("ToolCallParser XML parsing error: ${e.message}")
            null
        }
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

    /**
     * 使用XmlPullParser解析QuestionPart（容错版本）
     */
    private fun parseQuestionPartFromXml(text: String): QuestionPart? {
        return try {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(text))

            var prompt = ""
            var multiSelect = false
            val options = mutableListOf<QuestionOption>()
            var currentValue = ""
            var currentLabel = ""
            var inValue = false
            var inLabel = false
            var eventType = parser.eventType

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name.lowercase()) {
                            "prompt" -> currentValue = ""
                            "multi_select" -> currentValue = ""
                            "value" -> { inValue = true; currentValue = "" }
                            "label" -> { inLabel = true; currentValue = "" }
                        }
                    }

                    XmlPullParser.TEXT -> {
                        currentValue += parser.text.orEmpty().trim()
                    }

                    XmlPullParser.END_TAG -> {
                        when (parser.name.lowercase()) {
                            "prompt" -> prompt = currentValue.trim()
                            "multi_select" -> multiSelect = currentValue.trim().lowercase() == "true"
                            "value" -> { inValue = false }
                            "label" -> {
                                currentLabel = currentValue.trim()
                                if (currentValue.isNotEmpty() && currentLabel.isNotEmpty()) {
                                    options.add(QuestionOption(value = currentValue.trim(), label = currentLabel))
                                }
                                inLabel = false
                            }
                        }
                    }
                }
                eventType = parser.next()
            }

            if (prompt.isEmpty() && options.isEmpty()) return null

            QuestionPart(prompt = prompt, multiSelect = multiSelect, options = options)
        } catch (e: Exception) {
            DebugLog.w("ToolCallParser question XML parsing error: ${e.message}")
            null
        }
    }

    fun parseToolCallFromComplete(text: String): List<ToolPart> {
        val parts = mutableListOf<ToolPart>()
        // Decode HTML entities first — some LLMs encode < > & in tool calls
        val decoded = decodeHtmlEntities(text)
        TOOL_CALL_REGEX.findAll(decoded).forEach { match ->
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