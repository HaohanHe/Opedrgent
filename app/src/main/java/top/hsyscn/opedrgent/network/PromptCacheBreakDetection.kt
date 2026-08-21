package top.hsyscn.opedrgent.network

import org.json.JSONArray
import top.hsyscn.opedrgent.model.ChatMessage
import top.hsyscn.opedrgent.model.MessagePart
import top.hsyscn.opedrgent.model.Role
import top.hsyscn.opedrgent.utils.ContextCompressor
import top.hsyscn.opedrgent.utils.DebugLog
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 单次会话的 prompt cache 状态快照（Claude Code 风格两阶段检测的基础数据）。
 *
 * 记录发送 LLM 请求前的 prompt 指纹（system / 首条 user / 末条消息 / tool_use_id 集合等），
 * 配合 Phase 2 观测到的 cacheReadTokens，用于推断 cache 是否被破坏及破坏原因。
 */
data class PromptCacheState(
    val sessionId: String,
    val timestamp: Long,
    val messageCount: Int,
    val totalInputTokens: Int,
    val systemPromptHash: String,
    val firstUserMessageHash: String,
    val lastMessageHash: String,
    val toolUseIds: Set<String>,
    val toolResultIds: Set<String>,
    val messageHashes: List<String>,
    val model: String,
    val apiEndpoint: String,
    val requestParams: Map<String, Any>,
    /**
     * 上一次响应观测到的 cacheReadTokens。0 表示尚未观测过（首次调用或刚重置基线）。
     * 由 Phase 2 在比对完成后回填到 sessionStates 中，供下一次 Phase 2 作为 prevCacheReadTokens 使用。
     */
    val lastCacheReadTokens: Int = 0,
)

/**
 * Phase 2 比对结果。
 */
data class CacheBreakResult(
    val isBreak: Boolean,
    val prevCacheReadTokens: Int,
    val currentCacheReadTokens: Int,
    val tokenDrop: Int,
    val relativeDrop: Float,
    val reason: String,
    val ttlInference: String?,
    val diffSummary: String?,
)

/**
 * Claude Code 风格两阶段 prompt cache 破坏检测。
 *
 * Phase 1（pre-call）：调用 [recordPromptState] 在发送 LLM 请求前记录当前 prompt 指纹。
 * Phase 2（post-call）：响应返回后调用 [checkResponseForCacheBreak]，用观测到的
 *   cacheReadTokens 与前次状态比对，按双阈值（5% 相对下降 AND 2000 绝对下降）判定是否 break。
 *
 * 设计要点：
 * - 所有方法均为纯内存操作（ConcurrentHashMap + MessageDigest），无 IO，因此不标记 suspend。
 *   这样 Phase 2 可以直接在 OkHttp 回调线程中调用，无需 launch 协程，避免 runBlocking。
 * - [sessionStates] 始终保存「上一次 Phase 2 完成后的状态」（含观测到的 lastCacheReadTokens）。
 *   Phase 1 不写入 sessionStates（避免覆盖前次基线），由 Phase 2 在比对后写入。
 * - [notifyCompaction] / [notifyCacheDeletion] 为重置钩子，从 sessionStates 移除前次状态，
 *   使下一次 Phase 2 因找不到前次状态而返回 isBreak=false（避免误报）。
 */
object PromptCacheBreakDetection {
    private const val TAG = "PromptCacheBreakDetection"

    /** 相对下降阈值：cacheReadTokens 下降比例 >= 5% 才视为可疑。 */
    const val MIN_RELATIVE_DROP = 0.05f

    /** 绝对下降阈值：cacheReadTokens 下降绝对量 >= 2000 才视为可疑。 */
    const val MIN_ABSOLUTE_DROP = 2000

    /** 5 分钟 TTL 推断阈值。 */
    const val TTL_5MIN_MS = 5 * 60 * 1000L

    /** 1 小时 TTL 推断阈值。 */
    const val TTL_1H_MS = 60 * 60 * 1000L

    /** sessionStates：保存上一次 Phase 2 完成后的状态（含 lastCacheReadTokens）。 */
    private val sessionStates = ConcurrentHashMap<String, PromptCacheState>()

    /** sessionBaselines：压缩后重置的基线快照（供未来漂移分析，当前不参与比对）。 */
    private val sessionBaselines = ConcurrentHashMap<String, PromptCacheState>()

    /**
     * 判断指定模型是否支持返回 prompt cache 统计（cacheReadTokens）。
     *
     * P1-b 修复：Phase 1 仅对支持 cache 统计的模型记录 prompt 指纹，避免对 OpenAI/SiliconFlow 等
     * 标准模型（cacheReadTokens 永远为 0）白白记录状态却永不比对，浪费内存且 Phase 2 形同死代码。
     *
     * 当前支持：DeepSeek（model 名含 "deepseek"）、Anthropic Claude（model 名含 "claude"）。
     * 其余模型返回 false，Phase 1 跳过记录；Phase 2 因无 phase1CacheState 自然短路（守卫
     * `cacheReadTokens > 0` 保持不变 —— 模型不返回 cache 统计时本就无法比对）。
     */
    fun isModelSupported(model: String): Boolean {
        val lower = model.lowercase()
        return lower.contains("deepseek") || lower.contains("claude")
    }

    /**
     * Phase 1：pre-call 记录状态。
     *
     * 构建当前请求的 prompt 快照并返回给调用方。调用方需在 Phase 2 把同一个对象传回
     * [checkResponseForCacheBreak]。本方法不写入 [sessionStates]，避免覆盖前次基线。
     *
     * @param sessionId 会话唯一标识（通常用 ResearchStore 中的 session.id）
     * @param messages 即将发送给 LLM 的消息列表（不含 system prompt，system 单独传）
     * @param systemPrompt system prompt 文本
     * @param model 模型名
     * @param apiEndpoint API 端点 URL
     * @param requestParams 关键请求参数（max_tokens / temperature / thinking_enabled 等）
     */
    fun recordPromptState(
        sessionId: String,
        messages: List<ChatMessage>,
        systemPrompt: String,
        model: String,
        apiEndpoint: String,
        requestParams: Map<String, Any>,
    ): PromptCacheState {
        val messageHashes = messages.map { hashMessage(it) }
        val systemPromptHash = hashString(systemPrompt)
        val firstUserMsg = messages.firstOrNull { it.role == Role.USER && it.toolCallId == null }
        val firstUserMessageHash = firstUserMsg?.let { hashMessage(it) } ?: ""
        val lastMessageHash = messages.lastOrNull()?.let { hashMessage(it) } ?: ""
        val toolUseIds = extractToolUseIds(messages)
        val toolResultIds = extractToolResultIds(messages)
        val totalInputTokens = runCatching {
            ContextCompressor.estimateTotalTokens(messages, systemPrompt)
        }.getOrDefault(0)

        val state = PromptCacheState(
            sessionId = sessionId,
            timestamp = System.currentTimeMillis(),
            messageCount = messages.size,
            totalInputTokens = totalInputTokens,
            systemPromptHash = systemPromptHash,
            firstUserMessageHash = firstUserMessageHash,
            lastMessageHash = lastMessageHash,
            toolUseIds = toolUseIds,
            toolResultIds = toolResultIds,
            messageHashes = messageHashes,
            model = model,
            apiEndpoint = apiEndpoint,
            requestParams = requestParams,
            lastCacheReadTokens = 0,
        )
        DebugLog.i(TAG, "Phase1 record session=$sessionId msgs=${messages.size} tokens=$totalInputTokens model=$model")
        return state
    }

    /**
     * Phase 2：post-call 验证。
     *
     * 比对当前响应的 cacheReadTokens 与 sessionStates 中前次状态记录的 lastCacheReadTokens，
     * 按 5% 相对下降 AND 2000 绝对下降的双阈值判定是否 break。
     * 无论是否 break，都将当前状态（含观测到的 cacheReadTokens）写入 [sessionStates]。
     *
     * @param sessionId 会话唯一标识
     * @param currentState Phase 1 返回的状态快照（lastCacheReadTokens 尚为 0）
     * @param cacheReadTokens 本次响应观测到的 cache 命中 token 数
     */
    fun checkResponseForCacheBreak(
        sessionId: String,
        currentState: PromptCacheState,
        cacheReadTokens: Int,
    ): CacheBreakResult {
        val prev = sessionStates[sessionId]
        val enrichedState = currentState.copy(lastCacheReadTokens = cacheReadTokens)

        // 前次状态不存在：首次调用或刚被 notifyCacheDeletion / notifyCompaction 清空
        if (prev == null) {
            sessionStates[sessionId] = enrichedState
            DebugLog.i(TAG, "Phase2 check session=$sessionId curRead=$cacheReadTokens (no baseline, recording)")
            return CacheBreakResult(
                isBreak = false,
                prevCacheReadTokens = 0,
                currentCacheReadTokens = cacheReadTokens,
                tokenDrop = 0,
                relativeDrop = 0f,
                reason = "first call or post-deletion, no baseline",
                ttlInference = null,
                diffSummary = null,
            )
        }

        val prevCacheReadTokens = prev.lastCacheReadTokens

        // 前次未观测到 cacheReadTokens（=0），无法计算下降比例
        if (prevCacheReadTokens == 0) {
            sessionStates[sessionId] = enrichedState
            DebugLog.i(TAG, "Phase2 check session=$sessionId curRead=$cacheReadTokens (prev=0, no baseline)")
            return CacheBreakResult(
                isBreak = false,
                prevCacheReadTokens = 0,
                currentCacheReadTokens = cacheReadTokens,
                tokenDrop = 0,
                relativeDrop = 0f,
                reason = "previous cacheReadTokens=0, no baseline",
                ttlInference = null,
                diffSummary = null,
            )
        }

        // cache 未下降：不算 break
        if (cacheReadTokens >= prevCacheReadTokens) {
            sessionStates[sessionId] = enrichedState
            DebugLog.i(TAG, "Phase2 check session=$sessionId prevRead=$prevCacheReadTokens curRead=$cacheReadTokens (not decreased)")
            return CacheBreakResult(
                isBreak = false,
                prevCacheReadTokens = prevCacheReadTokens,
                currentCacheReadTokens = cacheReadTokens,
                tokenDrop = 0,
                relativeDrop = 0f,
                reason = "cache read tokens not decreased",
                ttlInference = null,
                diffSummary = null,
            )
        }

        val tokenDrop = prevCacheReadTokens - cacheReadTokens
        val relativeDrop = tokenDrop.toFloat() / prevCacheReadTokens.coerceAtLeast(1)
        val isBreak = relativeDrop >= MIN_RELATIVE_DROP && tokenDrop >= MIN_ABSOLUTE_DROP
        val gapMs = currentState.timestamp - prev.timestamp
        val ttlInference = inferTtl(gapMs)
        val diffSummary = generateDiffSummary(prev, currentState)

        if (isBreak) {
            DebugLog.w(
                TAG,
                "Cache BREAK session=$sessionId: prevRead=$prevCacheReadTokens curRead=$cacheReadTokens " +
                    "drop=$tokenDrop (${(relativeDrop * 100).toInt()}%) gap=${gapMs / 1000}s " +
                    "ttl=$ttlInference diff=$diffSummary"
            )
        } else {
            DebugLog.i(
                TAG,
                "Phase2 check session=$sessionId: prevRead=$prevCacheReadTokens curRead=$cacheReadTokens " +
                    "drop=$tokenDrop (${(relativeDrop * 100).toInt()}%) - below threshold"
            )
        }

        sessionStates[sessionId] = enrichedState
        return CacheBreakResult(
            isBreak = isBreak,
            prevCacheReadTokens = prevCacheReadTokens,
            currentCacheReadTokens = cacheReadTokens,
            tokenDrop = tokenDrop,
            relativeDrop = relativeDrop,
            reason = if (isBreak) {
                "cache break detected (relativeDrop=${(relativeDrop * 100).toInt()}% >= ${(MIN_RELATIVE_DROP * 100).toInt()}% AND tokenDrop=$tokenDrop >= $MIN_ABSOLUTE_DROP)"
            } else {
                "below threshold (relativeDrop=${(relativeDrop * 100).toInt()}% or tokenDrop=$tokenDrop < $MIN_ABSOLUTE_DROP)"
            },
            ttlInference = ttlInference,
            diffSummary = diffSummary,
        )
    }

    /**
     * 钩子：显式删除缓存内容（如清空对话、切换会话）。
     *
     * 移除 sessionStates 中的前次状态，下次 Phase 2 会因找不到前次状态而返回 isBreak=false
     * （跳过单次比对，避免在已知缓存被显式清空的情况下误报）。
     */
    fun notifyCacheDeletion(sessionId: String) {
        sessionStates.remove(sessionId)
        sessionBaselines.remove(sessionId)
        DebugLog.i(TAG, "notifyCacheDeletion: session=$sessionId state cleared")
    }

    /**
     * 钩子：压缩完成。
     *
     * 压缩必然导致 prompt 内容变化、cache 失效，将当前状态移入 [sessionBaselines]（供未来漂移分析）
     * 并从 [sessionStates] 移除。下次 Phase 2 会因找不到前次状态而返回 isBreak=false，
     * 避免压缩后的正常 cache 重建被误报为 break。
     *
     * 多次调用是幂等的（第二次调用时 sessionStates 已无对应项，仅做无害的 remove）。
     */
    fun notifyCompaction(sessionId: String) {
        val current = sessionStates.remove(sessionId)
        if (current != null) {
            sessionBaselines[sessionId] = current
        }
        DebugLog.i(TAG, "notifyCompaction: session=$sessionId baseline reset (hadPrev=${current != null})")
    }

    /** 仅供调试/观测：获取当前 session 的前次状态快照。 */
    fun peekSessionState(sessionId: String): PromptCacheState? = sessionStates[sessionId]

    /** 仅供调试/观测：获取当前 session 的压缩基线快照。 */
    fun peekSessionBaseline(sessionId: String): PromptCacheState? = sessionBaselines[sessionId]

    /** 仅供调试/观测：当前正在追踪的 session 数量。 */
    fun trackedSessionCount(): Int = sessionStates.size

    /**
     * TTL 推理：根据两次调用间的时间间隔推断 cache 失效的可能原因。
     *
     * - gap > 1h：可能是 1h TTL 过期
     * - gap > 5min：可能是 5min TTL 过期
     * - 0 < gap <= 5min：可能是服务端问题（TTL 未到却失效）
     * - gap <= 0：时钟回退或同一 tick，无法推断
     */
    private fun inferTtl(gapMs: Long): String? {
        return when {
            gapMs > TTL_1H_MS -> "possible 1h TTL expiry (gap=${gapMs / 1000}s)"
            gapMs > TTL_5MIN_MS -> "possible 5min TTL expiry (gap=${gapMs / 1000}s)"
            gapMs in 1..TTL_5MIN_MS -> "likely server-side (gap=${gapMs / 1000}s)"
            else -> null
        }
    }

    /**
     * 生成简要 diff 描述：对比前后状态维度，输出哪些发生了变化。
     * 格式如："messageCount: 10->12, toolUseIds added: [tool_abc], lastMessage changed"
     */
    private fun generateDiffSummary(prev: PromptCacheState, current: PromptCacheState): String? {
        val parts = mutableListOf<String>()
        if (prev.messageCount != current.messageCount) {
            parts.add("messageCount: ${prev.messageCount}->${current.messageCount}")
        }
        if (prev.systemPromptHash != current.systemPromptHash) {
            parts.add("systemPrompt changed")
        }
        if (prev.firstUserMessageHash != current.firstUserMessageHash) {
            parts.add("firstUserMessage changed")
        }
        if (prev.lastMessageHash != current.lastMessageHash) {
            parts.add("lastMessage changed")
        }
        val addedToolUseIds = current.toolUseIds - prev.toolUseIds
        val removedToolUseIds = prev.toolUseIds - current.toolUseIds
        if (addedToolUseIds.isNotEmpty()) parts.add("toolUseIds added: ${addedToolUseIds.take(5)}")
        if (removedToolUseIds.isNotEmpty()) parts.add("toolUseIds removed: ${removedToolUseIds.take(5)}")
        val addedToolResultIds = current.toolResultIds - prev.toolResultIds
        val removedToolResultIds = prev.toolResultIds - current.toolResultIds
        if (addedToolResultIds.isNotEmpty()) parts.add("toolResultIds added: ${addedToolResultIds.take(5)}")
        if (removedToolResultIds.isNotEmpty()) parts.add("toolResultIds removed: ${removedToolResultIds.take(5)}")
        if (prev.model != current.model) parts.add("model: ${prev.model}->${current.model}")
        if (prev.apiEndpoint != current.apiEndpoint) parts.add("apiEndpoint changed")
        if (prev.totalInputTokens != current.totalInputTokens) {
            parts.add("totalInputTokens: ${prev.totalInputTokens}->${current.totalInputTokens}")
        }
        if (parts.isEmpty()) return null
        return parts.joinToString(", ")
    }

    /**
     * 计算单条消息的 hash（SHA-256 前 8 位 hex）。
     *
     * hash 输入覆盖：role + textContent + toolCallId + apiToolCallsJson + reasoningParts + toolParts
     * + parts 中的 ToolCall（callId + toolName）。
     * 这些字段共同决定了消息在 API 请求中的实际序列化内容，足以检测 cache-relevant 的变化。
     */
    private fun hashMessage(msg: ChatMessage): String {
        val sb = StringBuilder()
        sb.append(msg.role.name)
        sb.append('|')
        sb.append(msg.textContent)
        sb.append('|')
        sb.append(msg.toolCallId ?: "")
        sb.append('|')
        sb.append(msg.apiToolCallsJson ?: "")
        msg.reasoningParts.forEach { sb.append('|').append(it.text) }
        msg.toolParts.forEach { tp ->
            sb.append('|').append(tp.tool).append(':').append(tp.state.status.name)
        }
        msg.parts.filterIsInstance<MessagePart.ToolCall>().forEach { tc ->
            sb.append('|').append(tc.callId).append(':').append(tc.toolName)
        }
        return hashString(sb.toString())
    }

    /**
     * 计算任意字符串的 SHA-256 hash，取前 8 位 hex（32 bit 指纹）。
     * 8 位 hex 对 prompt cache 检测场景碰撞概率足够低（4G 取值空间）。
     */
    private fun hashString(text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }.take(8)
    }

    /**
     * 提取所有 tool_use_id 集合。
     * 来源：消息的 apiToolCallsJson（legacy 格式）+ parts 中的 MessagePart.ToolCall.callId。
     */
    private fun extractToolUseIds(messages: List<ChatMessage>): Set<String> {
        val ids = mutableSetOf<String>()
        for (msg in messages) {
            msg.apiToolCallsJson?.let { json ->
                runCatching {
                    val arr = JSONArray(json)
                    for (i in 0 until arr.length()) {
                        val tc = arr.optJSONObject(i) ?: continue
                        val id = tc.optString("id", "")
                        if (id.isNotEmpty()) ids.add(id)
                    }
                }
            }
            msg.parts.filterIsInstance<MessagePart.ToolCall>().forEach { tc ->
                if (tc.callId.isNotEmpty()) ids.add(tc.callId)
            }
        }
        return ids
    }

    /**
     * 提取所有 tool_result 的 tool_use_id 集合。
     * 来源：消息的 toolCallId 字段（即 tool 结果回传消息所引用的 tool_use_id）。
     */
    private fun extractToolResultIds(messages: List<ChatMessage>): Set<String> {
        val ids = mutableSetOf<String>()
        for (msg in messages) {
            msg.toolCallId?.let { if (it.isNotEmpty()) ids.add(it) }
        }
        return ids
    }
}
