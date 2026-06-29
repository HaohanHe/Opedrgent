package top.hsyscn.opedrgent.utils

import top.hsyscn.opedrgent.model.ChatMessage
import top.hsyscn.opedrgent.model.MessagePart

/**
 * 消息剪枝器。
 *
 * 负责工具输出修剪和消息列表的独立剪枝，不生成摘要。
 * 提取自原 ContextCompressor，以单一职责的方式管理所有剪枝逻辑。
 */
object MessagePruner {

    private const val TOOL_OUTPUT_HARD_LIMIT = 40_000
    private const val TOOL_OUTPUT_PRUNE_THRESHOLD = 500
    private const val TOOL_OUTPUT_MAX_CHARS = 2_000
    private const val TEXT_HARD_LIMIT = 1000
    private const val TOOL_OUTPUT_KEEP_CHARS = 200
    private const val TEXT_KEEP_CHARS = 500

    /**
     * 独立的工具输出修剪（Kilo 风格 Prune，与 Compaction 分离）。
     * 不生成摘要，只修剪旧工具输出，释放空间。
     * 适用于不想触发 full compaction 但需要释放 token 的场景。
     *
     * @param pruneAfterTurns 跳过最近 N 轮不修剪（保护近期上下文）
     * @return 修剪后的消息列表 + 释放的 token 数
     */
    fun prune(
        messages: List<ChatMessage>,
        pruneAfterTurns: Int = 2,
        reason: String? = null,
    ): Pair<List<ChatMessage>, Int> {
        val turns = MessageHistoryManager.splitIntoTurns(messages)
        if (turns.size <= pruneAfterTurns) return messages to 0

        val protectedTurns = turns.takeLast(pruneAfterTurns)
        val prunableTurns = turns.dropLast(pruneAfterTurns)

        var tokensFreed = 0
        val prunedMessages = prunableTurns.flatMap { turn ->
            turn.map { msg ->
                val before = TokenEstimator.estimateTokens(msg.textContent)
                val pruned = pruneToolOutput(msg, maxChars = TOOL_OUTPUT_MAX_CHARS)
                val after = TokenEstimator.estimateTokens(pruned.textContent)
                tokensFreed += (before - after).coerceAtLeast(0)
                pruned
            }
        } + protectedTurns.flatten()

        DebugLog.d("MessagePruner.prune[${reason ?: "normal"}]: freed ~$tokensFreed tokens, protected $pruneAfterTurns turns")
        return prunedMessages to tokensFreed
    }

    /** 工具输出剪枝：移除旧消息中的工具输出文本 */
    fun pruneToolOutput(message: ChatMessage, maxChars: Int = TOOL_OUTPUT_KEEP_CHARS): ChatMessage {
        if (message.parts.isEmpty()) return message
        // 跳过已压缩的 persisted-output，避免破坏 ContentReplacement 的预览
        if (ContentReplacement.isPersistedOutput(message.content)) return message

        val prunedParts = message.parts.map { part ->
            when (part) {
                is MessagePart.ToolCall -> {
                    if (part.output != null && part.output.length > TOOL_OUTPUT_PRUNE_THRESHOLD) {
                        part.copy(output = part.output.take(maxChars) + "...[已剪枝]")
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
