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
}
