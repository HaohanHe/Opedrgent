package top.hsyscn.opedrgent.utils

import top.hsyscn.opedrgent.model.ChatMessage
import top.hsyscn.opedrgent.model.MessagePart
import top.hsyscn.opedrgent.model.Role

/**
 * 压缩结果。包含预处理后的消息列表（已注入摘要、已剪枝）和摘要文本。
 *
 * @param messages 预处理后的消息列表，可直接传给 LLM
 * @param summary 生成的摘要文本（null 表示无需压缩）
 * @param tokenCount 估算的总 token 数
 * @param usageRatio token 使用率
 */
data class CompressResult(
    val messages: List<ChatMessage>,
    val summary: String?,
    val tokenCount: Int = 0,
    val usageRatio: Float = 0f,
) {
    val needsCompression: Boolean get() = usageRatio >= 0.90f
    val isCritical: Boolean get() = usageRatio >= 0.95f
}

/** @deprecated 使用 [CompressResult] */
typealias CompressedMessages = CompressResult

// ==================== TLDR 压缩策略（对标 Koog） ====================

/**
 * 压缩策略枚举（对标 Koog HistoryCompressionStrategy）
 */
enum class CompressionStrategy {
    /** 压缩全部历史（默认） */
    WHOLE_HISTORY,
    /** 仅压缩超过 N 轮的旧历史 */
    OLD_TURNS_ONLY,
    /** 仅压缩工具输出 */
    TOOL_OUTPUTS_ONLY,
    /** 仅保留最近 N 轮，其余丢弃 */
    RECENT_ONLY,
}

/**
 * TLDR 结构化摘要（对标 Koog TLDR 格式）
 */
data class TldrSummary(
    val overview: String,              // 一句话总览
    val keyFacts: List<String>,         // 关键事实
    val decisions: List<String>,        // 重要决策
    val actionItems: List<String>,      // 待办行动
    val topicsDiscussed: List<String>,  // 讨论的主题
    val turnCount: Int,                 // 原始轮数
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

object ContextCompressor {

    private const val CHARS_PER_TOKEN_ZH = 1.5
    private const val CHARS_PER_TOKEN_EN = 4.0
    private const val CHARS_PER_TOKEN_MIXED = 3.5

    // 工具输出剪枝阈值
    private const val TOOL_OUTPUT_HARD_LIMIT = 40_000  // 最多保留约 40K tokens 的工具输出
    private const val TOOL_OUTPUT_PRUNE_THRESHOLD = 500   // 单个工具输出超过此字符数时剪枝
    private const val TEXT_HARD_LIMIT = 1000               // 单段文本超过此字符数时剪枝
    private const val TOOL_OUTPUT_KEEP_CHARS = 200         // 工具输出剪枝后保留的前缀字符数
    private const val TEXT_KEEP_CHARS = 500                // 文本剪枝后保留的前缀字符数

    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        val hasChinese = Regex("[\\u4e00-\\u9fa5]").containsMatchIn(text)
        val ratio = if (hasChinese) {
            val zhCount = Regex("[\\u4e00-\\u9fa5]").findAll(text).count()
            if (zhCount.toDouble() / text.length > 0.3) CHARS_PER_TOKEN_ZH else CHARS_PER_TOKEN_MIXED
        } else {
            CHARS_PER_TOKEN_EN
        }
        return (text.length / ratio).toInt().coerceAtLeast(1)
    }

    /**
     * 智能压缩对话历史。
     * 策略：
     * 1. 按 user message 切分为 turn（一轮对话 = user + assistant）
     * 2. 保留最近 keepTurns 轮完整对话
     * 3. 旧 turn 生成结构化摘要
     * 4. 工具输出剪枝：从旧到新，移除工具输出文本
     * 5. 返回压缩后的消息列表 + 摘要
     */
    fun compress(
        messages: List<ChatMessage>,
        systemPrompt: String,
        maxTokens: Int,
        keepRecent: Int = 4,
        keepTurns: Int = 3,  // 保留最近 3 轮（用于 turn 级别压缩）
    ): CompressResult {
        val sysTokens = estimateTokens(systemPrompt)

        if (messages.isEmpty()) {
            return CompressResult(
                messages = emptyList(),
                summary = null,
                tokenCount = sysTokens,
                usageRatio = sysTokens.toFloat() / maxTokens.coerceAtLeast(1),
            )
        }

        val totalTokens = sysTokens + messages.sumOf { estimateTokens(it.textContent) }
        val ratio = totalTokens.toFloat() / maxTokens.coerceAtLeast(1)

        // 未超过阈值，不压缩
        if (totalTokens <= maxTokens * 0.85) {
            return CompressResult(
                messages = messages,
                summary = null,
                tokenCount = totalTokens,
                usageRatio = ratio,
            )
        }

        // 1. 分 turn
        val turns = splitIntoTurns(messages)

        // 2. 分离保留轮和压缩轮
        val keepCount = keepTurns.coerceAtMost(turns.size)
        val keepTurnsList = turns.takeLast(keepCount)
        val compactTurns = turns.dropLast(keepCount)

        // 3. 为压缩轮生成摘要
        val summary = if (compactTurns.isNotEmpty()) {
            generateSummary(compactTurns)
        } else null

        // 4. 工具输出剪枝（对保留轮中的消息进行剪枝）
        val prunedKeep = keepTurnsList.flatMap { turn ->
            turn.map { msg -> pruneToolOutput(msg) }
        }

        // 5. 构建结果
        val compactedMessage = ChatMessage(
            role = Role.SYSTEM,
            parts = listOf(MessagePart.Compaction(summary ?: "", auto = true)),
            content = summary ?: "",
        )

        val resultMessages = listOf(compactedMessage) + prunedKeep
        val resultTokens = sysTokens + resultMessages.sumOf { estimateTokens(it.textContent) }

        DebugLog.d(
            "ContextCompressor: $resultTokens/$maxTokens (${String.format("%.0f%%", resultTokens.toFloat() / maxTokens.coerceAtLeast(1) * 100)}) " +
            "(turns=${turns.size}, kept=$keepCount, compacted=${compactTurns.size}, msgs=${resultMessages.size})"
        )

        return CompressResult(
            messages = resultMessages,
            summary = summary,
            tokenCount = resultTokens,
            usageRatio = resultTokens.toFloat() / maxTokens.coerceAtLeast(1),
        )
    }

    /** 按 user message 切分为 turn */
    private fun splitIntoTurns(messages: List<ChatMessage>): List<List<ChatMessage>> {
        val turns = mutableListOf<List<ChatMessage>>()
        var currentTurn = mutableListOf<ChatMessage>()

        for (msg in messages) {
            if (msg.role == Role.USER && currentTurn.isNotEmpty()) {
                turns.add(currentTurn.toList())
                currentTurn = mutableListOf()
            }
            currentTurn.add(msg)
        }
        if (currentTurn.isNotEmpty()) {
            turns.add(currentTurn.toList())
        }

        return turns
    }

    /** 为一组 turn 生成结构化摘要 */
    private fun generateSummary(turns: List<List<ChatMessage>>): String {
        val summaries = turns.map { turn ->
            val userMsg = turn.firstOrNull { it.role == Role.USER }
            val assistantMsg = turn.firstOrNull { it.role == Role.ASSISTANT }

            buildString {
                appendLine("用户: ${userMsg?.textContent?.take(100) ?: "无"}")
                appendLine("AI: ${assistantMsg?.textContent?.take(100) ?: "无"}")
                // 列出工具调用
                val toolCalls = turn.flatMap { msg ->
                    msg.parts.filterIsInstance<MessagePart.ToolCall>()
                }
                if (toolCalls.isNotEmpty()) {
                    appendLine("工具: ${toolCalls.joinToString { it.toolName }}")
                }
            }
        }

        return "[对话摘要]\n${summaries.joinToString("\n")}"
    }

    /** 工具输出剪枝：移除旧消息中的工具输出文本 */
    private fun pruneToolOutput(message: ChatMessage): ChatMessage {
        if (message.parts.isEmpty()) return message

        val prunedParts = message.parts.map { part ->
            when (part) {
                is MessagePart.ToolCall -> {
                    if (part.output != null && part.output.length > TOOL_OUTPUT_PRUNE_THRESHOLD) {
                        part.copy(output = part.output.take(TOOL_OUTPUT_KEEP_CHARS) + "...[已剪枝]")
                    } else part
                }
                is MessagePart.Text -> {
                    if (part.content.length > TEXT_HARD_LIMIT) {
                        part.copy(content = part.content.take(TEXT_KEEP_CHARS) + "...[已剪枝]")
                    } else part
                }
                else -> part
            }
        }

        return message.copy(
            parts = prunedParts,
            content = prunedParts.filterIsInstance<MessagePart.Text>()
                .joinToString("") { it.content },
        )
    }

    // ==================== TLDR 智能压缩（对标 Koog replaceHistoryWithTLDR） ====================

    /**
     * 使用 LLM 生成 TLDR 结构化摘要。
     *
     * 这是对标 Koog `replaceHistoryWithTLDR` 的核心方法。
     * 与简单文本截断不同，TLDR 会：
     * - 保留关键事实和决策
     * - 提取待办行动项
     * - 识别讨论主题
     * - 生成适合 LLM 继续理解的紧凑表示
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

        // 构建压缩请求的输入
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

## 对话历史 (${\turns.size} 轮)
$turnsSummary

## 输出要求
你必须严格按以下 JSON 格式输出（不要输出任何其他文字）：

\```json
{
  "overview": "一句话概括整个对话的核心内容",
  "key_facts": ["事实1", "事实2", ...],
  "decisions": ["决策1", "决策2", ...],
  "action_items": ["待办1", "待办2", ...],
  "topics_discussed": ["主题1", "主题2", ...]
}
\```

## 摘要原则
1. overview 控制在 50 字以内
2. key_facts 只保留客观事实（数据、名称、结论）
3. decisions 记录明确的决定或选择
4. action_items 记录需要后续执行的事项
5. topics_discussed 提取 3-5 个关键词
6. 如果某个列表为空，输出空数组 []
7. 不要编造对话中没有的内容"""

        try {
            val response = generateFn(tldrPrompt, emptyList())
            return parseTldrResponse(response, turns.size)
        } catch (e: Exception) {
            DebugLog.w("ContextCompressor: TLDR generation failed, fallback to simple summary: ${e.message}")
            return createFallbackTldr(turns)
        }
    }

    /**
     * 带 TLDR 的智能压缩（完整版）。
     *
     * 结合了传统剪枝和 LLM TLDR 生成的优势：
     * 1. 分离保留轮和压缩轮
     * 2. 对压缩轮调用 LLM 生成 TLDR
     * 3. 对保留轮进行工具输出剪枝
     * 4. 组合为最终结果
     */
    suspend fun compressWithTldr(
        messages: List<ChatMessage>,
        systemPrompt: String,
        maxTokens: Int,
        strategy: CompressionStrategy = CompressionStrategy.WHOLE_HISTORY,
        keepTurns: Int = 3,
        generateFn: suspend (String, List<ChatMessage>) -> String,
    ): CompressResult {
        val sysTokens = estimateTokens(systemPrompt)

        if (messages.isEmpty()) {
            return CompressResult(
                messages = emptyList(),
                summary = null,
                tokenCount = sysTokens,
                usageRatio = sysTokens.toFloat() / maxTokens.coerceAtLeast(1),
            )
        }

        val totalTokens = sysTokens + messages.sumOf { estimateTokens(it.textContent) }
        val ratio = totalTokens.toFloat() / maxTokens.coerceAtLeast(1)

        // 未超过阈值，不压缩
        if (totalTokens <= maxTokens * 0.85) {
            return CompressResult(
                messages = messages,
                summary = null,
                tokenCount = totalTokens,
                usageRatio = ratio,
            )
        }

        val turns = splitIntoTurns(messages)

        return when (strategy) {
            CompressionStrategy.WHOLE_HISTORY -> {
                compressWholeHistoryWithTldr(turns, messages, systemPrompt, maxTokens, keepTurns, sysTokens, generateFn)
            }
            CompressionStrategy.OLD_TURNS_ONLY -> {
                compressOldTurnsOnly(turns, messages, systemPrompt, maxTokens, keepTurns, sysTokens, generateFn)
            }
            CompressionStrategy.TOOL_OUTPUTS_ONLY -> {
                compressToolOutputsOnly(messages, systemPrompt, maxTokens, sysTokens)
            }
            CompressionStrategy.RECENT_ONLY -> {
                compressRecentOnly(turns, systemPrompt, maxTokens, keepTurns, sysTokens)
            }
        }
    }

    /**
     * 全历史 TLDR 压缩（默认策略）
     */
    private suspend fun compressWholeHistoryWithTldr(
        turns: List<List<ChatMessage>>,
        originalMessages: List<ChatMessage>,
        systemPrompt: String,
        maxTokens: Int,
        keepTurns: Int,
        sysTokens: Int,
        generateFn: suspend (String, List<ChatMessage>) -> String,
    ): CompressResult {
        val keepCount = keepTurns.coerceAtMost(turns.size)
        val keepTurnsList = turns.takeLast(keepCount)
        val compactTurns = turns.dropLast(keepCount)

        // 使用 LLM 生成 TLDR
        val tldr = if (compactTurns.isNotEmpty()) {
            generateTldr(compactTurns, generateFn)
        } else null

        val tldrText = tldr?.toDisplayText()

        // 保留轮剪枝
        val prunedKeep = keepTurnsList.flatMap { turn ->
            turn.map { msg -> pruneToolOutput(msg) }
        }

        // 构建 TLDR Compaction 消息
        val compactedMessage = if (tldrText != null) {
            ChatMessage(
                role = Role.SYSTEM,
                parts = listOf(MessagePart.Compaction(tldrText, auto = true)),
                content = tldrText,
            )
        } else null

        val resultMessages = listOfNotNull(compactedMessage) + prunedKeep
        val resultTokens = sysTokens + resultMessages.sumOf { estimateTokens(it.textContent) }

        DebugLog.d(
            "ContextCompressor.TLDR: $resultTokens/$maxTokens (${String.format("%.0f%%", resultTokens.toFloat() / maxTokens.coerceAtLeast(1) * 100)}) " +
            "(turns=${turns.size}, kept=$keepCount, tldr=${tldr?.keyFacts?.size ?: 0} facts)"
        )

        return CompressResult(
            messages = resultMessages,
            summary = tldrText,
            tokenCount = resultTokens,
            usageRatio = resultTokens.toFloat() / maxTokens.coerceAtLeast(1),
        )
    }

    /** 仅压缩旧轮（新生成的 TLDR + 保留最近轮完整） */
    private suspend fun compressOldTurnsOnly(
        turns: List<List<ChatMessage>>,
        originalMessages: List<ChatMessage>,
        systemPrompt: String,
        maxTokens: Int,
        keepTurns: Int,
        sysTokens: Int,
        generateFn: suspend (String, List<ChatMessage>) -> String,
    ): CompressResult {
        // 与 WHOLE_HISTORY 类似，但对保留轮不做剪枝
        val keepCount = keepTurns.coerceAtMost(turns.size)
        val compactTurns = turns.dropLast(keepCount)
        val keepTurnsList = turns.takeLast(keepCount)

        val tldr = if (compactTurns.isNotEmpty()) {
            generateTldr(compactTurns, generateFn)
        } else null

        val compactedMessage = tldr?.toDisplayText()?.let { text ->
            ChatMessage(role = Role.SYSTEM, parts = listOf(MessagePart.Compaction(text, auto = true)), content = text)
        }

        val resultMessages = listOfNotNull(compactedMessage) + keepTurnsList.flatten()
        val resultTokens = sysTokens + resultMessages.sumOf { estimateTokens(it.textContent) }

        return CompressResult(messages = resultMessages, summary = tldr?.toDisplayText(), tokenCount = resultTokens, usageRatio = resultTokens.toFloat() / maxTokens.coerceAtLeast(1))
    }

    /** 仅压缩工具输出（保留对话文本完整） */
    private fun compressToolOutputsOnly(
        messages: List<ChatMessage>,
        systemPrompt: String,
        maxTokens: Int,
        sysTokens: Int,
    ): CompressResult {
        val pruned = messages.map { pruneToolOutput(it) }
        val tokens = sysTokens + pruned.sumOf { estimateTokens(it.textContent) }
        return CompressResult(messages = pruned, summary = null, tokenCount = tokens, usageRatio = tokens.toFloat() / maxTokens.coerceAtLeast(1))
    }

    /** 仅保留最近 N 轮（其余完全丢弃） */
    private fun compressRecentOnly(
        turns: List<List<ChatMessage>>,
        systemPrompt: String,
        maxTokens: Int,
        keepTurns: Int,
        sysTokens: Int,
    ): CompressResult {
        val kept = turns.takeLast(keepTurns.coerceAtMost(turns.size)).flatten()
        val tokens = sysTokens + kept.sumOf { estimateTokens(it.textContent) }
        val droppedCount = turns.size - minOf(keepTurns, turns.size)
        val summary = if (droppedCount > 0) "[已丢弃 $droppedCount 轮旧对话]" else null
        return CompressResult(messages = kept, summary = summary, tokenCount = tokens, usageRatio = tokens.toFloat() / maxTokens.coerceAtLeast(1))
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
            DebugLog.w("ContextCompressor: failed to parse TLDR JSON: ${e.message}")
            TldrSummary(overview = response.take(200), keyFacts = emptyList(), decisions = emptyList(), actionItems = emptyList(), topicsDiscussed = emptyList(), turnCount = turnCount)
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

            // 简单关键词提取
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
