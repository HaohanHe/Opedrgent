package top.hsyscn.opedrgent.utils

import top.hsyscn.opedrgent.model.ChatMessage
import top.hsyscn.opedrgent.model.MessagePart

/**
 * Token 估算器。
 *
 * 负责文本、单条消息以及消息列表的 token 数量估算。
 * 提取自原 ContextCompressor，以单一职责的方式管理所有估算逻辑。
 */
object TokenEstimator {

    private const val CHARS_PER_TOKEN_ZH = 2.0
    private const val CHARS_PER_TOKEN_EN = 4.0
    private const val CHARS_PER_TOKEN_MIXED = 3.2
    private const val ESTIMATE_CORRECTION = 1.3

    /**
     * 估算文本 token 数。
     * 根据中英文比例选择不同字符/token 比值，并乘以校正因子。
     */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        val hasChinese = Regex("[\\u4e00-\\u9fa5]").containsMatchIn(text)
        val ratio = if (hasChinese) {
            val zhCount = Regex("[\\u4e00-\\u9fa5]").findAll(text).count()
            if (zhCount.toDouble() / text.length > 0.3) CHARS_PER_TOKEN_ZH else CHARS_PER_TOKEN_MIXED
        } else {
            CHARS_PER_TOKEN_EN
        }
        return ((text.length / ratio) * ESTIMATE_CORRECTION).toInt().coerceAtLeast(1)
    }

    /**
     * 估算单条消息的完整 token（包括 ToolCall、Reasoning 等所有 parts）。
     * 避免 textContent 只统计 Text 部分导致的低估。
     */
    fun estimateMessageTokens(msg: ChatMessage): Int {
        return if (msg.parts.isNotEmpty()) {
            msg.parts.sumOf { part ->
                when (part) {
                    is MessagePart.Text -> estimateTokens(part.content)
                    is MessagePart.ToolCall -> {
                        estimateTokens(part.input.values.joinToString()) +
                            (part.output?.let { estimateTokens(it) } ?: 0)
                    }
                    is MessagePart.Reasoning -> estimateTokens(part.content)
                    else -> 0
                }
            }
        } else {
            estimateTokens(msg.content) +
                msg.toolParts.sumOf { tp ->
                    estimateTokens(tp.state.input.values.joinToString()) +
                        (tp.state.output?.let { estimateTokens(it) } ?: 0)
                } +
                msg.reasoningParts.sumOf { estimateTokens(it.text) }
        }
    }

    /**
     * 快速估算消息列表的 token 总量。
     *
     * @return 估算的 token 总量（system prompt + 所有消息）
     */
    fun estimateTotalTokens(messages: List<ChatMessage>, systemPrompt: String): Int {
        var total = estimateTokens(systemPrompt)
        for (msg in messages) {
            total += estimateMessageTokens(msg)
        }
        return total
    }
}
