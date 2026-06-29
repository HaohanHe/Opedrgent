package top.hsyscn.opedrgent.utils

import top.hsyscn.opedrgent.model.ChatMessage
import top.hsyscn.opedrgent.model.Role

/**
 * 消息历史管理器。
 *
 * 负责将消息列表切分为 turn、检测续作场景、剥离尾部工具调用、
 * 组装压缩后的消息历史等与消息结构相关的操作。
 * 提取自原 ContextCompressor，以单一职责的方式管理消息历史结构。
 */
object MessageHistoryManager {

    /** 按 user message 切分为 turn（一轮对话 = user + assistant + 工具结果） */
    fun splitIntoTurns(messages: List<ChatMessage>): List<List<ChatMessage>> {
        val turns = mutableListOf<List<ChatMessage>>()
        var currentTurn = mutableListOf<ChatMessage>()

        for (msg in messages) {
            val isToolResult = msg.toolCallId != null
            if (msg.role == Role.USER && !isToolResult && currentTurn.isNotEmpty()) {
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

    /**
     * 续作场景检测（对标 KiloCode continuation 检测）。
     * 若最后一条真实 user 消息之后还存在 tool 消息，说明工具流未完成，此时不应触发压缩。
     */
    fun isContinuation(messages: List<ChatMessage>): Boolean {
        if (messages.isEmpty()) return false

        var lastUserIdx = -1
        for (i in messages.indices.reversed()) {
            val msg = messages[i]
            if (msg.role == Role.USER && msg.toolCallId == null) {
                lastUserIdx = i
                break
            }
        }
        if (lastUserIdx < 0 || lastUserIdx == messages.size - 1) return false

        for (i in (lastUserIdx + 1) until messages.size) {
            val msg = messages[i]
            if (msg.toolCallId != null || msg.apiToolCallsJson != null) return true
        }
        return false
    }

    /**
     * 剥离末尾连续的 Tool.Call 消息。
     * LLM 不允许 Tool.Call 后接 User 消息，生成 TLDR 摘要前必须剥离。
     * 返回 (剥离后的列表, 被剥离的尾部列表)。
     */
    fun dropTrailingToolCalls(messages: List<ChatMessage>): Pair<List<ChatMessage>, List<ChatMessage>> {
        if (messages.isEmpty()) return messages to emptyList()

        var splitIndex = messages.size
        for (i in messages.indices.reversed()) {
            val msg = messages[i]
            val isToolCall = msg.apiToolCallsJson != null
            val isToolResult = msg.toolCallId != null
            if (isToolCall || isToolResult) {
                splitIndex = i
            } else {
                break
            }
        }
        if (splitIndex == messages.size) return messages to emptyList()
        return messages.subList(0, splitIndex).toList() to messages.subList(splitIndex, messages.size).toList()
    }

    /**
     * 检测末尾未完成的 tool_call 对：有 apiToolCallsJson 但无对应 toolCallId 结果。
     * 返回需要原样保留的末尾消息列表（空列表表示无悬空 tool_call）。
     */
    fun findTrailingUnpairedToolCalls(messages: List<ChatMessage>): List<ChatMessage> {
        if (messages.isEmpty()) return emptyList()

        val last = messages.last()
        if (last.apiToolCallsJson != null) {
            var startIdx = messages.size - 1
            while (startIdx > 0) {
                val prev = messages[startIdx - 1]
                if (prev.apiToolCallsJson != null || prev.toolCallId != null) {
                    startIdx--
                } else {
                    break
                }
            }
            return messages.subList(startIdx, messages.size).toList()
        }
        return emptyList()
    }

    /**
     * 按 Koog composeMessageHistory 顺序组装压缩后的消息列表。
     * 顺序：[所有 System 消息] + [首条 User 消息] + [Memory 消息] + [TLDR 摘要消息] + [尾部 Tool.Call 消息]
     */
    fun composeMessageHistory(
        systemMsgs: List<ChatMessage>,
        firstUser: ChatMessage?,
        memoryMsgs: List<ChatMessage> = emptyList(),
        tldrMsg: ChatMessage?,
        trailingToolCalls: List<ChatMessage> = emptyList(),
    ): List<ChatMessage> {
        val result = mutableListOf<ChatMessage>()
        result.addAll(systemMsgs)
        if (firstUser != null) result.add(firstUser)
        result.addAll(memoryMsgs)
        if (tldrMsg != null) result.add(tldrMsg)
        result.addAll(trailingToolCalls)
        return result
    }

    /**
     * 单轮内按消息切分（对标 KiloCode splitTurn）。
     * 当单轮消息超出 preserveRecentBudget 时，在轮内按消息切分找到合适切点。
     * 返回 (切点之前的消息列表, 切点之后的消息列表)。
     */
    fun splitTurn(turn: List<ChatMessage>, budgetTokens: Int): Pair<List<ChatMessage>, List<ChatMessage>> {
        if (turn.isEmpty()) return emptyList<ChatMessage>() to emptyList()
        var cumulative = 0
        var splitIdx = turn.size
        for (i in turn.indices) {
            val msgTokens = TokenEstimator.estimateMessageTokens(turn[i])
            if (cumulative + msgTokens > budgetTokens) {
                splitIdx = i
                break
            }
            cumulative += msgTokens
        }
        if (splitIdx == turn.size) return turn to emptyList()
        return turn.subList(0, splitIdx).toList() to turn.subList(splitIdx, turn.size).toList()
    }

    /**
     * tail 保留预算（对标 KiloCode preserveRecentBudget）。
     * min(8K, max(2K, usable*0.25))
     */
    fun preserveRecentBudget(maxTokens: Int): Int {
        val usable = maxTokens
        return minOf(8_000, maxOf(2_000, (usable * 0.25).toInt()))
    }

    /**
     * 按 token 比例将消息列表切分为最多 n 块。
     * 每块目标 token = 总 token / n，按累计 token 数均分，遇到单条超大消息则单独成块。
     */
    fun splitMessagesByTokenRatio(
        messages: List<ChatMessage>,
        n: Int,
    ): List<List<ChatMessage>> {
        if (messages.isEmpty()) return emptyList()
        val totalTokens = messages.sumOf { TokenEstimator.estimateMessageTokens(it) }
        val perChunkTarget = (totalTokens / n).coerceAtLeast(1)
        val chunks = mutableListOf<MutableList<ChatMessage>>()
        var current = mutableListOf<ChatMessage>()
        var currentTokens = 0

        for (msg in messages) {
            val msgTokens = TokenEstimator.estimateMessageTokens(msg)
            if (currentTokens >= perChunkTarget && chunks.size < n - 1) {
                chunks.add(current)
                current = mutableListOf()
                currentTokens = 0
            }
            current.add(msg)
            currentTokens += msgTokens
        }
        if (current.isNotEmpty()) {
            chunks.add(current)
        }
        return chunks
    }
}
