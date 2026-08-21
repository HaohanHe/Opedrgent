package top.hsyscn.opedrgent.utils

import top.hsyscn.opedrgent.model.ChatMessage
import top.hsyscn.opedrgent.model.MessagePart
import top.hsyscn.opedrgent.model.Role
import top.hsyscn.opedrgent.network.PromptCacheBreakDetection

/**
 * 上下文压缩器。
 *
 * 作为压缩流程的协调者，将具体的职责委托给：
 * - [TokenEstimator] 负责 token 估算
 * - [MessageHistoryManager] 负责消息历史结构管理
 * - [MessagePruner] 负责工具输出剪枝
 * - [TldrGenerator] 负责 TLDR 摘要生成
 * - [CompressionExecutor] 负责具体压缩策略执行
 *
 * 公共 API 保持与原实现一致，确保调用方无需修改。
 */
object ContextCompressor {

    // Chunked Compaction 分块降级参数（对标 KiloCode chunked-compaction）
    private const val CHUNKED_CONCURRENCY = 3
    private const val CHUNKED_DEPTH = 3
    private const val CHUNKED_MIN_BLOCK_TOKENS = 1000

    // ==================== 兼容转发 API ====================

    fun estimateTokens(text: String): Int = TokenEstimator.estimateTokens(text)

    fun estimateMessageTokens(msg: ChatMessage): Int = TokenEstimator.estimateMessageTokens(msg)

    fun estimateTotalTokens(messages: List<ChatMessage>, systemPrompt: String): Int =
        TokenEstimator.estimateTotalTokens(messages, systemPrompt)

    fun needsCompression(messages: List<ChatMessage>, systemPrompt: String, maxTokens: Int): Boolean {
        if (MessageHistoryManager.isContinuation(messages)) return false
        val estimated = TokenEstimator.estimateTotalTokens(messages, systemPrompt)
        return estimated.toFloat() / maxTokens.coerceAtLeast(1) >= 0.85f
    }

    fun prune(
        messages: List<ChatMessage>,
        pruneAfterTurns: Int = 2,
        reason: String? = null,
    ): Pair<List<ChatMessage>, Int> = MessagePruner.prune(messages, pruneAfterTurns, reason)

    fun buildAnchoredSummaryText(
        previousSummary: String?,
        newTurnsSummary: String,
    ): String = TldrGenerator().buildAnchoredSummaryText(previousSummary, newTurnsSummary)

    fun findPreviousSummary(messages: List<ChatMessage>): String? =
        TldrGenerator().findPreviousSummary(messages)

    suspend fun generateTldr(
        turns: List<List<ChatMessage>>,
        generateFn: suspend (String, List<ChatMessage>) -> String,
    ): TldrSummary = TldrGenerator().generateTldr(turns, generateFn)

    // ==================== 核心压缩 API ====================

    /**
     * 智能压缩对话历史。
     *
     * 策略：
     * 1. 按 user message 切分为 turn（一轮对话 = user + assistant）
     * 2. 保留最近 keepTurns 轮完整对话
     * 3. 旧 turn 生成结构化摘要
     * 4. 工具输出剪枝：从旧到新，移除工具输出文本
     * 5. 返回压缩后的消息列表 + 摘要
     */
    suspend fun compress(
        messages: List<ChatMessage>,
        systemPrompt: String,
        maxTokens: Int,
        keepRecent: Int = 4,
        keepTurns: Int = 3,
        generateFn: (suspend (String, List<ChatMessage>) -> String)? = null,
        sessionId: String? = null,
    ): CompressResult {
        val sysTokens = TokenEstimator.estimateTokens(systemPrompt)

        if (messages.isEmpty()) {
            return CompressResult(
                messages = emptyList(),
                summary = null,
                tokenCount = sysTokens,
                usageRatio = sysTokens.toFloat() / maxTokens.coerceAtLeast(1),
            )
        }

        // 续作场景不压缩，避免破坏进行中的工具流
        if (MessageHistoryManager.isContinuation(messages)) {
            DebugLog.d("ContextCompressor: continuation 场景，跳过压缩")
            val contTokens = sysTokens + messages.sumOf { TokenEstimator.estimateMessageTokens(it) }
            return CompressResult(
                messages = messages,
                summary = null,
                tokenCount = contTokens,
                usageRatio = contTokens.toFloat() / maxTokens.coerceAtLeast(1),
            )
        }

        // 末尾未完成 tool_call 检测
        val trailingToolCalls = MessageHistoryManager.findTrailingUnpairedToolCalls(messages)
        val toCompress = if (trailingToolCalls.isNotEmpty()) {
            messages.dropLast(trailingToolCalls.size)
        } else messages

        // 提取首条 user 消息（作为锚点保留）
        val firstUserMsg = toCompress.firstOrNull { it.role == Role.USER && it.toolCallId == null }

        val totalTokens = sysTokens + messages.sumOf { TokenEstimator.estimateMessageTokens(it) }
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

        // hidden 集合过滤历史压缩点
        val hiddenIndexes = mutableSetOf<Int>()
        for (i in toCompress.indices) {
            val msg = toCompress[i]
            if (msg.parts.any { it is MessagePart.Compaction }) {
                hiddenIndexes.add(i)
            }
        }
        val firstUserIndex = toCompress.indexOfFirst { it.role == Role.USER && it.toolCallId == null }
        if (firstUserIndex >= 0) {
            hiddenIndexes.add(firstUserIndex)
        }
        val tldrContext = toCompress.filterIndexed { idx, _ -> idx !in hiddenIndexes }

        // TLDR 请求前调用 dropTrailingToolCalls
        val (cleanContext, _) = MessageHistoryManager.dropTrailingToolCalls(tldrContext)

        // 1. 分 turn
        val turns = MessageHistoryManager.splitIntoTurns(cleanContext)

        // 2. 分离保留轮和压缩轮
        val keepCount = keepTurns.coerceAtMost(turns.size)
        val keepTurnsList = turns.takeLast(keepCount)
        val compactTurns = turns.dropLast(keepCount)

        // 对 keepTurnsList 应用 splitTurn
        val recentBudget = MessageHistoryManager.preserveRecentBudget(maxTokens)
        val adjustedKeepTurnsList = keepTurnsList.map { turn ->
            val turnTokens = turn.sumOf { TokenEstimator.estimateMessageTokens(it) }
            if (turnTokens > recentBudget) {
                val (kept, _) = MessageHistoryManager.splitTurn(turn, recentBudget)
                kept
            } else {
                turn
            }
        }

        // 3. 为压缩轮生成摘要（支持锚定摘要增量更新）
        val tldrGenerator = TldrGenerator()
        val previousSummary = tldrGenerator.findPreviousSummary(toCompress)
        val summary = if (compactTurns.isNotEmpty()) {
            val newSummary = if (generateFn != null) {
                try {
                    val tldr = tldrGenerator.generateTldr(compactTurns, generateFn)
                    tldr.toDisplayText()
                } catch (e: Exception) {
                    DebugLog.w("ContextCompressor: LLM TLDR failed, fallback to text summary: ${e.message}")
                    tldrGenerator.generateSummary(compactTurns)
                }
            } else {
                tldrGenerator.generateSummary(compactTurns)
            }
            tldrGenerator.buildAnchoredSummaryText(previousSummary, newSummary)
        } else null

        // 4. 工具输出剪枝（对保留轮中的消息进行剪枝）
        var prunedKeep = adjustedKeepTurnsList.flatMap { turn ->
            turn.map { msg -> MessagePruner.pruneToolOutput(msg) }
        }

        // 4b. 单条消息长度限制：防止一条超长消息撑爆上下文
        val perMessageLimit = (maxTokens / 4).coerceAtLeast(1000)
        prunedKeep = prunedKeep.map { msg ->
            val msgTokens = TokenEstimator.estimateMessageTokens(msg)
            if (msgTokens > perMessageLimit) {
                val maxChars = perMessageLimit * if (Regex("[\\u4e00-\\u9fa5]").containsMatchIn(msg.textContent)) 2 else 4
                val nonTextParts = msg.parts.filter { it !is MessagePart.Text }
                val textParts = msg.parts.filterIsInstance<MessagePart.Text>()
                val truncatedText = if (textParts.isNotEmpty()) {
                    val combined = textParts.joinToString("") { it.content }
                    combined.take(maxChars) + "\n\n[内容过长，已截断]"
                } else msg.textContent.take(maxChars)
                val newParts = listOf(MessagePart.Text(content = truncatedText)) + nonTextParts
                msg.copy(content = truncatedText, parts = newParts)
            } else msg
        }

        // 5. 构建 Compaction 消息
        val compactedMessage = ChatMessage(
            role = Role.SYSTEM,
            parts = listOf(MessagePart.Compaction(
                summary = summary ?: "",
                auto = true,
                tailStartId = firstUserMsg?.id,
            )),
            content = summary ?: "",
        )

        // 6. 用 composeMessageHistory 组装
        val systemMsgs = toCompress.filter {
            it.role == Role.SYSTEM && it.parts.all { p -> p !is MessagePart.Compaction }
        }
        val resultMessages = MessageHistoryManager.composeMessageHistory(
            systemMsgs = systemMsgs,
            firstUser = firstUserMsg,
            memoryMsgs = emptyList(),
            tldrMsg = compactedMessage,
            trailingToolCalls = prunedKeep + trailingToolCalls,
        )

        // 7. 压缩成功后强制 prune
        val (prunedFinalMessages, _) = MessagePruner.prune(resultMessages, pruneAfterTurns = keepTurns, reason = "post-compaction")

        val resultTokens = sysTokens + prunedFinalMessages.sumOf { TokenEstimator.estimateMessageTokens(it) }

        DebugLog.d(
            "ContextCompressor: $resultTokens/$maxTokens (${String.format("%.0f%%", resultTokens.toFloat() / maxTokens.coerceAtLeast(1) * 100)}) " +
            "(turns=${turns.size}, kept=$keepCount, compacted=${compactTurns.size}, msgs=${prunedFinalMessages.size})"
        )

        // Prompt Cache Break Detection
        if (sessionId != null && summary != null) {
            PromptCacheBreakDetection.notifyCompaction(sessionId)
        }

        return CompressResult(
            messages = prunedFinalMessages,
            summary = summary,
            tokenCount = resultTokens,
            usageRatio = resultTokens.toFloat() / maxTokens.coerceAtLeast(1),
        )
    }

    /**
     * 带分块降级的压缩包装函数（对标 KiloCode Chunked Compaction）。
     * 先调用 compress()，若结果仍超阈值则递归分块降级。
     */
    suspend fun compressWithChunkedFallback(
        messages: List<ChatMessage>,
        systemPrompt: String,
        maxTokens: Int,
        keepRecent: Int = 4,
        keepTurns: Int = 3,
        depth: Int = 0,
        generateFn: (suspend (String, List<ChatMessage>) -> String)? = null,
        sessionId: String? = null,
    ): CompressResult {
        val firstPass = compress(messages, systemPrompt, maxTokens, keepRecent, keepTurns, generateFn, sessionId)

        if (firstPass.tokenCount <= maxTokens * 0.85) {
            return firstPass
        }

        if (depth >= CHUNKED_DEPTH) {
            DebugLog.w("ContextCompressor: Chunked compaction reached DEPTH limit (depth=$depth), tokenCount=${firstPass.tokenCount}/$maxTokens")
            return firstPass
        }

        val totalMsgTokens = messages.sumOf { TokenEstimator.estimateMessageTokens(it) }
        if (totalMsgTokens < CHUNKED_MIN_BLOCK_TOKENS) {
            DebugLog.w("ContextCompressor: Chunked compaction skipped, messages too small ($totalMsgTokens tokens < $CHUNKED_MIN_BLOCK_TOKENS)")
            return firstPass
        }

        val chunks = MessageHistoryManager.splitMessagesByTokenRatio(messages, CHUNKED_CONCURRENCY)
        if (chunks.size <= 1) {
            DebugLog.w("ContextCompressor: Chunked compaction cannot split further (chunks=${chunks.size})")
            return firstPass
        }

        DebugLog.d("ContextCompressor: Chunked compaction depth=${depth + 1}, chunks=${chunks.size}, prevTokens=${firstPass.tokenCount}")

        val tldrGenerator = TldrGenerator()
        val chunkSummaries = chunks.map { chunk ->
            if (chunk.sumOf { TokenEstimator.estimateMessageTokens(it) } < CHUNKED_MIN_BLOCK_TOKENS) {
                tldrGenerator.generateSummary(MessageHistoryManager.splitIntoTurns(chunk))
            } else {
                val chunkResult = compressWithChunkedFallback(
                    messages = chunk,
                    systemPrompt = systemPrompt,
                    maxTokens = maxTokens,
                    keepRecent = keepRecent.coerceAtMost(2),
                    keepTurns = keepTurns.coerceAtMost(2),
                    depth = depth + 1,
                    generateFn = null,
                    sessionId = null,
                )
                chunkResult.summary ?: tldrGenerator.generateSummary(MessageHistoryManager.splitIntoTurns(chunk))
            }
        }

        val combinedSummary = buildString {
            appendLine("[分块压缩摘要（depth=${depth + 1}，共 ${chunkSummaries.size} 块）]")
            chunkSummaries.forEachIndexed { idx, s ->
                appendLine("── 块 ${idx + 1} ──")
                appendLine(s)
                appendLine()
            }
        }

        val mergedCompactedMessage = ChatMessage(
            role = Role.SYSTEM,
            parts = listOf(MessagePart.Compaction(
                summary = combinedSummary,
                auto = true,
                tailStartId = null,
            )),
            content = combinedSummary,
        )

        val finalMessages = firstPass.messages.map { msg ->
            if (msg.role == Role.SYSTEM && msg.parts.any { it is MessagePart.Compaction }) {
                mergedCompactedMessage
            } else {
                msg
            }
        }

        val finalTokens = TokenEstimator.estimateTokens(systemPrompt) + finalMessages.sumOf { TokenEstimator.estimateMessageTokens(it) }
        DebugLog.d("ContextCompressor: Chunked compaction done, finalTokens=$finalTokens/$maxTokens (depth=${depth + 1})")

        return CompressResult(
            messages = finalMessages,
            summary = combinedSummary,
            tokenCount = finalTokens,
            usageRatio = finalTokens.toFloat() / maxTokens.coerceAtLeast(1),
        )
    }

    /**
     * 带 TLDR 的智能压缩（完整版）。
     *
     * 通过策略枚举选择具体压缩策略，便于调用方按需选择。
     */
    suspend fun compressWithTldr(
        messages: List<ChatMessage>,
        systemPrompt: String,
        maxTokens: Int,
        strategy: CompressionStrategy = CompressionStrategy.WHOLE_HISTORY,
        keepTurns: Int = 3,
        generateFn: suspend (String, List<ChatMessage>) -> String,
    ): CompressResult {
        val sysTokens = TokenEstimator.estimateTokens(systemPrompt)

        if (messages.isEmpty()) {
            return CompressResult(
                messages = emptyList(),
                summary = null,
                tokenCount = sysTokens,
                usageRatio = sysTokens.toFloat() / maxTokens.coerceAtLeast(1),
            )
        }

        val totalTokens = sysTokens + messages.sumOf { TokenEstimator.estimateMessageTokens(it) }
        val ratio = totalTokens.toFloat() / maxTokens.coerceAtLeast(1)

        if (totalTokens <= maxTokens * 0.85) {
            return CompressResult(
                messages = messages,
                summary = null,
                tokenCount = totalTokens,
                usageRatio = ratio,
            )
        }

        val executor = CompressionExecutorFactory.create(strategy)
        return executor.compress(messages, systemPrompt, maxTokens, keepTurns, generateFn)
    }
}
