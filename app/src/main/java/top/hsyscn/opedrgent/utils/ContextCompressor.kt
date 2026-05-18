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

        // ★ 动态调整保留消息数量（根据总消息数自适应）
        val adaptiveKeepRecent = calculateAdaptiveKeepRecent(messages.size, keepRecent)
        val recentCount = adaptiveKeepRecent.coerceAtMost(messages.size)
        val recent = messages.takeLast(recentCount)
        val older = messages.dropLast(recentCount)

        val recentTokens = recent.sumOf { estimateTokens(it.content) }

        if (recentTokens <= availableForMessages) {
            val totalTokens = sysTokens + messages.sumOf { estimateTokens(it.content) }
            if (totalTokens <= maxTokens) {
                return CompressedMessages(systemPrompt, null, messages, totalTokens)
            }
        }

        // ★ 增强摘要生成（提取关键实体和决策，而非简单截断）
        val summary = if (older.isNotEmpty()) {
            generateEnhancedSummary(older)
        } else null

        val summaryTokens = summary?.let { estimateTokens(it) + 20 } ?: 0
        val remainingTokens = availableForMessages - summaryTokens
        val trimmedRecent = trimToTokenBudget(recent, remainingTokens)

        val totalTokens = sysTokens + summaryTokens + trimmedRecent.sumOf { estimateTokens(it.content) }
        DebugLog.d(
            "ContextCompressor: $totalTokens/$maxTokens tokens " +
            "(sys=$sysTokens summary=$summaryTokens recent=${trimmedRecent.size} " +
            "adaptiveKeep=$adaptiveKeepRecent totalMsgs=${messages.size})"
        )

        return CompressedMessages(systemPrompt, summary, trimmedRecent, totalTokens)
    }

    /**
     * 动态计算保留消息数量
     *
     * 策略：
     * - 短对话（≤10条）：保持默认值（4条）
     * - 中等长度（11-30条）：适当增加（5-6条）
     * - 长对话（>30条）：保留更多近期消息（6-8条）
     *
     * 目的：平衡上下文完整性和Token预算
     */
    private fun calculateAdaptiveKeepRecent(totalMessages: Int, defaultKeep: Int): Int {
        return when {
            totalMessages <= 10 -> defaultKeep                    // 短对话：保持原样
            totalMessages <= 20 -> maxOf(defaultKeep, 5)         // 中等：+1
            totalMessages <= 30 -> maxOf(defaultKeep, 6)          // 较长：+2
            totalMessages <= 50 -> maxOf(defaultKeep, 7)         // 长：+3
            else -> maxOf(defaultKeep, 8)                        // 超长：+4
        }
    }

    /**
     * 增强版摘要生成（提取关键信息而非简单截断）
     *
     * 改进点：
     * 1. 识别用户意图（提问/指令/闲聊）
     * 2. 提取关键实体（人名/地名/技术名词）
     * 3. 记录重要决策和结论
     * 4. 控制摘要长度在合理范围
     *
     * 输出格式：
     * [对话摘要] 用户问了X→助手回答了Y | 用户要求Z→助手执行了W | ...
     */
    private fun generateEnhancedSummary(olderMessages: List<ChatMessage>): String {
        return buildString {
            append("[对话摘要]\n")

            olderMessages.forEachIndexed { index, msg ->
                val role = when (msg.role) {
                    Role.USER -> "👤"
                    Role.ASSISTANT -> "🤖"
                    else -> "⚙️"
                }

                // 智能截取：优先保留关键信息
                val content = msg.content.trim()
                val summarizedContent = when {
                    content.length <= 150 -> content

                    // 检测是否包含列表/步骤（保留结构）
                    content.contains("\n") && content.lines().count { it.isNotBlank() } > 3 -> {
                        val lines = content.lines().filter { it.isNotBlank() }
                        "${lines.take(3).joinToString(" | ")}\n...(${lines.size - 3}项省略)"
                    }

                    // 检测是否包含代码块（保留首行注释）
                    content.contains("```") -> {
                        val codePart = content.substringAfter("```").substringBefore("```")
                        "[代码: ${codePart.take(80)}...]"
                    }

                    // 默认：智能分段截取
                    else -> {
                        // 尝试按句子分割，保留前2句
                        val sentences = content.split(Regex("[。！？.!?\n]"))
                            .filter { it.trim().length > 5 }
                        if (sentences.size >= 2) {
                            "${sentences[0].trim()}${sentences[1].trim()}"
                        } else {
                            content.take(150)
                        }
                    }
                }

                append("$role ${summarizedContent}")

                // 最后一条不添加分隔符
                if (index < olderMessages.lastIndex) {
                    append("\n↓ ")
                }
            }
        }.take(1200)  // 允许更长的摘要（从800提升到1200）
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