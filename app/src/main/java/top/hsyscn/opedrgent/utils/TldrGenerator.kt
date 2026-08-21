package top.hsyscn.opedrgent.utils

import top.hsyscn.opedrgent.model.ChatMessage
import top.hsyscn.opedrgent.model.MessagePart
import top.hsyscn.opedrgent.model.Role

/**
 * TLDR 结构化摘要（对标 Koog TLDR 格式）
 */
data class TldrSummary(
    val overview: String,
    val keyFacts: List<String>,
    val decisions: List<String>,
    val actionItems: List<String>,
    val topicsDiscussed: List<String>,
    val turnCount: Int,
    val compressedAt: Long = System.currentTimeMillis(),
) {
    fun toDisplayText(): String = buildString {
        appendLine("## 对话摘要 (TLDR)")
        appendLine(overview)
        if (keyFacts.isNotEmpty()) {
            appendLine("\n### 关键事实")
            keyFacts.forEach { appendLine("- $it") }
        }
        if (decisions.isNotEmpty()) {
            appendLine("\n### 重要决策")
            decisions.forEach { appendLine("- $it") }
        }
        if (actionItems.isNotEmpty()) {
            appendLine("\n### 待办行动")
            actionItems.forEach { appendLine("- [ ] $it") }
        }
        if (topicsDiscussed.isNotEmpty()) {
            appendLine("\n### 讨论主题")
            appendLine(topicsDiscussed.joinToString(", "))
        }
        appendLine("\n---")
        appendLine("*共压缩 $turnCount 轮对话*")
    }
}

/**
 * TLDR 生成器。
 *
 * 负责使用 LLM 生成结构化对话摘要，并在 LLM 不可用时提供纯文本回退方案。
 * 提取自原 ContextCompressor，以单一职责的方式管理所有 TLDR 相关逻辑。
 */
class TldrGenerator {

    /**
     * 使用 LLM 生成 TLDR 结构化摘要。
     *
     * @param turns 被压缩的 turn 列表
     * @return TldrSummary 结构化摘要
     */
    suspend fun generateTldr(
        turns: List<List<ChatMessage>>,
        generateFn: suspend (String, List<ChatMessage>) -> String,
    ): TldrSummary {
        if (turns.isEmpty()) {
            return TldrSummary(
                overview = "无对话内容",
                keyFacts = emptyList(),
                decisions = emptyList(),
                actionItems = emptyList(),
                topicsDiscussed = emptyList(),
                turnCount = 0,
            )
        }

        val turnsSummary = turns.mapIndexed { idx, turn ->
            val userMsg = turn.firstOrNull { it.role == Role.USER }
            val assistantMsg = turn.firstOrNull { it.role == Role.ASSISTANT }
            """
            ### 第${idx + 1}轮
            **用户**: ${userMsg?.textContent?.take(150) ?: "(无)"}
            **AI**: ${assistantMsg?.textContent?.take(150) ?: "(无)"}
            """.trimIndent()
        }.joinToString("\n\n")

        val tldrPrompt = """你是一个专业的对话摘要生成器。请将以下对话历史转换为结构化的 TLDR (Too Long; Didn't Read) 格式。

## 对话历史 (${turns.size} 轮)
$turnsSummary

## 输出要求
你必须严格按以下 JSON 格式输出（不要输出任何其他文字）：

```json
{
  "overview": "一句话概括整个对话的核心内容",
  "key_facts": ["事实1", "事实2", ...],
  "decisions": ["决策1", "决策2", ...],
  "action_items": ["待办1", "待办2", ...],
  "topics_discussed": ["主题1", "主题2", ...]
}
```

## 摘要原则
1. overview 控制在 50 字以内
2. key_facts 只保留客观事实（数据、名称、结论）
3. decisions 记录明确的决定或选择
4. action_items 记录需要后续执行的事项
5. topics_discussed 提取 3-5 个关键词
6. 如果某个列表为空，输出空数组 []
7. 不要编造对话中没有的内容"""

        return try {
            val response = generateFn(tldrPrompt, emptyList())
            parseTldrResponse(response, turns.size)
        } catch (e: Exception) {
            DebugLog.w("TldrGenerator: TLDR generation failed, fallback to simple summary: ${e.message}")
            createFallbackTldr(turns)
        }
    }

    /**
     * 为一组 turn 生成结构化摘要（改进版：提取关键信息而非简单截断）。
     * 作为无 LLM 函数时的回退方案，也用于 TLDR 生成失败时的降级。
     */
    fun generateSummary(turns: List<List<ChatMessage>>): String {
        val summaries = turns.map { turn ->
            val userMsg = turn.firstOrNull { it.role == Role.USER }
            val assistantMsg = turn.firstOrNull { it.role == Role.ASSISTANT }

            val userIntent = userMsg?.textContent?.let { text ->
                val firstSentence = text.split(Regex("[。？！\n]")).firstOrNull()?.trim()
                if (firstSentence != null && firstSentence.length > 10) {
                    firstSentence.take(150)
                } else {
                    text.take(150)
                }
            } ?: "无"

            val assistantKey = assistantMsg?.textContent?.let { text ->
                val sentences = text.split(Regex("[。？！\n]")).filter { it.trim().length > 5 }
                if (sentences.size >= 2) {
                    val keySentence = sentences[sentences.size - 2].trim()
                    keySentence.take(150)
                } else {
                    text.take(150)
                }
            } ?: "无"

            val toolCalls = turn.flatMap { msg ->
                msg.parts.filterIsInstance<MessagePart.ToolCall>()
            }
            val toolSummary = if (toolCalls.isNotEmpty()) {
                val toolDetails = toolCalls.map { tc ->
                    val outputPreview = tc.output?.take(80)?.replace("\n", " ") ?: "无输出"
                    "${tc.toolName}($outputPreview)"
                }
                "工具: ${toolDetails.joinToString("; ")}"
            } else ""

            buildString {
                appendLine("用户意图: $userIntent")
                appendLine("助手回复要点: $assistantKey")
                if (toolSummary.isNotEmpty()) {
                    appendLine(toolSummary)
                }
            }
        }

        return "[对话摘要 - 共${turns.size}轮]\n${summaries.joinToString("\n")}"
    }

    /**
     * 锚定摘要（Kilo 风格 Anchored Summary）。
     * 如果存在前次摘要，将其作为增量基础，合并新的轮次生成更新摘要。
     */
    fun buildAnchoredSummaryText(
        previousSummary: String?,
        newTurnsSummary: String,
    ): String {
        return if (previousSummary != null) {
            """Update the anchored summary below using the conversation history above.
Preserve still-true details, remove stale details, and merge in the new facts.
<previous-summary>
$previousSummary
</previous-summary>

### 最新进展
$newTurnsSummary"""
        } else {
            "[对话摘要]\n$newTurnsSummary"
        }
    }

    /**
     * 从消息列表中查找前次压缩的锚定摘要。
     */
    fun findPreviousSummary(messages: List<ChatMessage>): String? {
        for (msg in messages.reversed()) {
            val compaction = msg.parts.filterIsInstance<MessagePart.Compaction>().firstOrNull()
            if (compaction != null && compaction.summary.isNotBlank()) {
                return compaction.summary
            }
        }
        return null
    }

    /**
     * 解析 LLM 返回的 TLDR JSON。
     */
    private fun parseTldrResponse(response: String, turnCount: Int): TldrSummary {
        return try {
            val jsonStr = response.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            val json = org.json.JSONObject(jsonStr)
            TldrSummary(
                overview = json.optString("overview", "对话摘要"),
                keyFacts = jsonToArray(json.optJSONArray("key_facts")),
                decisions = jsonToArray(json.optJSONArray("decisions")),
                actionItems = jsonToArray(json.optJSONArray("action_items")),
                topicsDiscussed = jsonToArray(json.optJSONArray("topics_discussed")),
                turnCount = turnCount,
            )
        } catch (e: Exception) {
            DebugLog.w("TldrGenerator: failed to parse TLDR JSON: ${e.message}")
            TldrSummary(
                overview = response.take(200),
                keyFacts = emptyList(),
                decisions = emptyList(),
                actionItems = emptyList(),
                topicsDiscussed = emptyList(),
                turnCount = turnCount,
            )
        }
    }

    /** TLDR 生成失败时的回退方案 */
    private fun createFallbackTldr(turns: List<List<ChatMessage>>): TldrSummary {
        val facts = mutableListOf<String>()
        val topics = mutableSetOf<String>()

        for (turn in turns) {
            val userMsg = turn.firstOrNull { it.role == Role.USER }?.textContent?.take(80) ?: ""
            val assistantMsg = turn.firstOrNull { it.role == Role.ASSISTANT }?.textContent?.take(80) ?: ""
            if (userMsg.isNotEmpty()) facts += "用户提到: $userMsg"
            if (assistantMsg.isNotEmpty()) facts += "AI回复: $assistantMsg"

            Regex("[\\u4e00-\\u9fa5]{2,6}").findAll(userMsg + assistantMsg).forEach {
                if (it.value.length >= 2) topics.add(it.value)
            }
        }

        return TldrSummary(
            overview = "包含 ${turns.size} 轮对话",
            keyFacts = facts.take(5),
            decisions = emptyList(),
            actionItems = emptyList(),
            topicsDiscussed = topics.take(5).toList(),
            turnCount = turns.size,
        )
    }

    /** JSONArray 转 List<String> */
    private fun jsonToArray(arr: org.json.JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }
}
