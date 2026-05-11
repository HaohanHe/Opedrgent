package top.hsyscn.opedrgent.utils

import top.hsyscn.opedrgent.model.ChatMessage
import top.hsyscn.opedrgent.model.Role

data class CompressedMessages(
    val systemPrompt: String,
    val summary: String?,
    val recentMessages: List<ChatMessage>,
    val tokenCount: Int,
)

object ContextCompressor {

    private const val CHARS_PER_TOKEN_ZH = 1.5
    private const val CHARS_PER_TOKEN_EN = 4.0
    private const val CHARS_PER_TOKEN_MIXED = 3.5

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

    fun compress(
        messages: List<ChatMessage>,
        systemPrompt: String,
        maxTokens: Int,
        keepRecent: Int = 4,
    ): CompressedMessages {
        val sysTokens = estimateTokens(systemPrompt)
        val availableForMessages = (maxTokens * 0.85).toInt() - sysTokens

        if (messages.isEmpty()) {
            return CompressedMessages(systemPrompt, null, emptyList(), sysTokens)
        }

        val recentCount = keepRecent.coerceAtMost(messages.size)
        val recent = messages.takeLast(recentCount)
        val older = messages.dropLast(recentCount)

        val recentTokens = recent.sumOf { estimateTokens(it.content) }

        if (recentTokens <= availableForMessages) {
            val totalTokens = sysTokens + messages.sumOf { estimateTokens(it.content) }
            if (totalTokens <= maxTokens) {
                return CompressedMessages(systemPrompt, null, messages, totalTokens)
            }
        }

        val summary = if (older.isNotEmpty()) {
            buildString {
                append("[对话摘要] ")
                older.forEach { msg ->
                    val role = when (msg.role) {
                        Role.USER -> "用户"
                        Role.ASSISTANT -> "助手"
                        else -> "系统"
                    }
                    append("$role: ${msg.content.take(120)}")
                    if (msg.content.length > 120) append("...")
                    append(" | ")
                }
            }.take(800)
        } else null

        val summaryTokens = summary?.let { estimateTokens(it) + 20 } ?: 0
        val remainingTokens = availableForMessages - summaryTokens
        val trimmedRecent = trimToTokenBudget(recent, remainingTokens)

        val totalTokens = sysTokens + summaryTokens + trimmedRecent.sumOf { estimateTokens(it.content) }
        DebugLog.d("ContextCompressor: $totalTokens/$maxTokens tokens (sys=$sysTokens summary=$summaryTokens recent=${trimmedRecent.size})")

        return CompressedMessages(systemPrompt, summary, trimmedRecent, totalTokens)
    }

    private fun trimToTokenBudget(messages: List<ChatMessage>, maxTokens: Int): List<ChatMessage> {
        if (messages.isEmpty()) return emptyList()
        val result = messages.toMutableList()
        var currentTokens = result.sumOf { estimateTokens(it.content) }
        while (currentTokens > maxTokens && result.size > 1) {
            val removed = result.removeAt(0)
            currentTokens -= estimateTokens(removed.content)
        }
        if (result.size == 1 && currentTokens > maxTokens) {
            val msg = result[0]
            val truncated = msg.content.take((maxTokens * CHARS_PER_TOKEN_ZH).toInt())
            result[0] = msg.copy(content = "$truncated...")
        }
        return result
    }
}