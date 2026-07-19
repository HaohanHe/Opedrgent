package top.hsyscn.opedrgent.utils

import top.hsyscn.opedrgent.network.ToolExecutionStatus
import java.security.MessageDigest

/**
 * 工具调用保护器 — 防止 Agent 陷入工具调用死循环
 *
 * 三层决策模型：
 * - ALLOW:      继续执行
 * - TOOL_BLOCK: 仅阻止当前工具本轮调用，LLM 可换用其他工具
 * - AGENT_HALT: Agent 陷入循环，触发一次反思轮（reflection round）
 * - SESSION_HALT: 硬停止整个会话
 *
 * 失败分类：
 * - 致命失败（FATAL_ERROR）参与工具级阻止与会话级停止判定
 * - 瞬态失败（TIMEOUT / RATE_LIMIT / PARTIAL_TIMEOUT）不视为“连续失败”，
 *   但大量连续瞬态失败且无进展时触发 AGENT_HALT
 */
class ToolCallGuardrail(
    private val maxConsecutiveFatalFailures: Int = 3,
    private val maxSameToolFailures: Int = 5,
    private val maxNoProgressCalls: Int = 4,
    private val doomLoopThreshold: Int = 3,
    private val repeatedSearchThreshold: Int = 3,
    private val transientWithoutProgressThreshold: Int = 5,
    private val fatalMultiToolThreshold: Int = 3,
) {
    enum class GuardrailAction {
        ALLOW,
        TOOL_BLOCK,
        AGENT_HALT,
        SESSION_HALT,
        /** 部分工具失败，允许返回已成功结果 + 失败工具结构化错误，不终止会话。 */
        PARTIAL_ERROR,
    }

    data class ToolCallRecord(
        val toolName: String,
        val normalizedArgs: String,
        val argsHash: String,
        val resultHash: String,
        val status: ToolExecutionStatus,
        val timestampMs: Long = System.currentTimeMillis(),
    )

    private val history = mutableListOf<ToolCallRecord>()

    /**
     * 兼容旧签名。
     * success=true  映射为 SUCCESS
     * success=false 映射为 FATAL_ERROR（旧调用方无法区分瞬态失败）
     */
    fun record(toolName: String, args: String, result: String, success: Boolean): GuardrailAction {
        val status = if (success) ToolExecutionStatus.SUCCESS else ToolExecutionStatus.FATAL_ERROR
        return record(toolName, args, result, status)
    }

    /**
     * 记录一次工具调用结果，返回分层决策。
     */
    fun record(
        toolName: String,
        args: String,
        result: String,
        status: ToolExecutionStatus,
    ): GuardrailAction {
        val normalized = normalizeArgs(args)
        val record = ToolCallRecord(
            toolName = toolName,
            normalizedArgs = normalized,
            argsHash = sha256(normalized),
            resultHash = sha256(result),
            status = status,
        )

        synchronized(history) {
            history.add(record)

            // 1. 部分错误：多个不同工具连续致命失败时，不再直接终止整个会话，
            //    允许返回已成功工具结果 + 失败工具的结构化错误。
            val recentFatalAcrossTools = history.takeLast(maxSameToolFailures)
                .filter { isFatal(it.status) }
            if (recentFatalAcrossTools.map { it.toolName }.distinct().size >= fatalMultiToolThreshold) {
                DebugLog.w("ToolCallGuardrail: PARTIAL_ERROR — fatal errors from ${recentFatalAcrossTools.map { it.toolName }.distinct()} tools")
                return GuardrailAction.PARTIAL_ERROR
            }

            // 2. 工具级阻止：当前调用本身就是致命错误
            if (isFatal(record.status)) {
                DebugLog.w("ToolCallGuardrail: TOOL_BLOCK — '$toolName' returned FATAL_ERROR")
                return GuardrailAction.TOOL_BLOCK
            }

            // 3. Agent 级暂停：同一工具+相同参数重复调用（doom loop）
            //     3+ 次重复 = 强制反思轮；反思后仍重复则升级为 SESSION_HALT
            val sameSignatureCount = history.count {
                it.toolName == record.toolName && it.argsHash == record.argsHash
            }
            if (sameSignatureCount >= doomLoopThreshold) {
                DebugLog.w("ToolCallGuardrail: AGENT_HALT — '$toolName' called $sameSignatureCount times with identical args")
                return GuardrailAction.AGENT_HALT
            }

            // 4. 工具级阻止：同一工具连续致命失败（兜底，即使当前不是 fatal）
            if (history.size >= maxConsecutiveFatalFailures) {
                val recent = history.takeLast(maxConsecutiveFatalFailures)
                if (recent.all { it.toolName == toolName && isFatal(it.status) }) {
                    DebugLog.w("ToolCallGuardrail: TOOL_BLOCK — '$toolName' $maxConsecutiveFatalFailures consecutive fatal failures")
                    return GuardrailAction.TOOL_BLOCK
                }
            }

            // 5. 工具级阻止：幂等无进展（连续结果完全相同）
            if (history.size >= maxNoProgressCalls) {
                val recent = history.takeLast(maxNoProgressCalls)
                if (recent.map { it.resultHash }.distinct().size == 1) {
                    DebugLog.w("ToolCallGuardrail: TOOL_BLOCK — identical results $maxNoProgressCalls times")
                    return GuardrailAction.TOOL_BLOCK
                }
            }

            // 6. Agent 级暂停：搜索循环（重叠关键词 / 重复 URL / 重复 domain）
            if (detectSearchLoop(record)) {
                DebugLog.w("ToolCallGuardrail: AGENT_HALT — search loop detected")
                return GuardrailAction.AGENT_HALT
            }

            // 7. Agent 级暂停：大量暂态失败且无进展
            if (countTransientWithoutProgress() >= transientWithoutProgressThreshold) {
                DebugLog.w("ToolCallGuardrail: AGENT_HALT — $transientWithoutProgressThreshold+ transient failures without progress")
                return GuardrailAction.AGENT_HALT
            }

            return GuardrailAction.ALLOW
        }
    }

    /**
     * 预检查：在不记录的情况下，判断即将发起的调用是否会被阻止。
     * 用于在真正执行前拦截已知的 doom loop（返回 AGENT_HALT 以触发反思）。
     */
    fun peek(toolName: String, args: String): GuardrailAction {
        val normalized = normalizeArgs(args)
        val argsHash = sha256(normalized)
        synchronized(history) {
            val sameSignatureCount = history.count {
                it.toolName == toolName && it.argsHash == argsHash
            } + 1
            if (sameSignatureCount >= doomLoopThreshold) {
                return GuardrailAction.AGENT_HALT
            }
            return GuardrailAction.ALLOW
        }
    }

    fun reset() {
        synchronized(history) { history.clear() }
    }

    fun getHistory(): List<ToolCallRecord> = synchronized(history) { history.toList() }

    /**
     * 返回最近 N 轮中调用次数 >= threshold 的工具名列表（这些工具可能陷入循环）。
     * 用于在 agent 循环中只禁用循环的工具，而不影响其他工具。
     */
    fun getLoopingTools(recentWindow: Int = 8, threshold: Int = 3): List<String> {
        synchronized(history) {
            val recent = history.takeLast(recentWindow)
            if (recent.isEmpty()) return emptyList()
            return recent.groupingBy { it.toolName }
                .eachCount()
                .filter { it.value >= threshold }
                .keys
                .toList()
        }
    }

    /**
     * 仅检测是否连续重复调用 web_search 达到 threshold 次。
     *
     * HAM 模式需要保留 satellite_pass 等可连续调用的工具，因此只对 web_search 做循环禁用，
     * 避免模型在查询卫星过境时被误伤。
     */
    fun getConsecutiveWebSearchLoop(threshold: Int = 3): List<String> {
        synchronized(history) {
            if (history.isEmpty()) return emptyList()
            val lastName = history.last().toolName
            if (lastName != "web_search") return emptyList()
            var consecutive = 0
            for (i in history.size - 1 downTo 0) {
                if (history[i].toolName == lastName) consecutive++ else break
            }
            return if (consecutive >= threshold) listOf(lastName) else emptyList()
        }
    }

    /**
     * 导出当前 guardrail 状态快照，供 Agent 循环中断后恢复。
     */
    fun exportSnapshot(): top.hsyscn.opedrgent.model.GuardrailSnapshot {
        val recent = synchronized(history) { history.toList() }
        val failureCounts = recent
            .filter { isFatal(it.status) }
            .groupingBy { it.toolName }
            .eachCount()
        val consecutiveFailures = run {
            var count = 0
            for (i in recent.size - 1 downTo 0) {
                if (isFatal(recent[i].status)) count++ else break
            }
            count
        }
        return top.hsyscn.opedrgent.model.GuardrailSnapshot(
            consecutiveFailures = consecutiveFailures,
            toolFailureCounts = failureCounts,
            recentToolCalls = recent.map {
                top.hsyscn.opedrgent.model.ToolCallRecord(
                    toolName = it.toolName,
                    normalizedArgs = it.normalizedArgs,
                    argsHash = it.argsHash,
                    resultHash = it.resultHash,
                    status = it.status,
                    timestampMs = it.timestampMs,
                )
            },
        )
    }

    /**
     * 从快照恢复 guardrail 历史记录。
     */
    fun importSnapshot(snapshot: top.hsyscn.opedrgent.model.GuardrailSnapshot) {
        synchronized(history) {
            history.clear()
            history.addAll(snapshot.recentToolCalls.map {
                ToolCallRecord(
                    toolName = it.toolName,
                    normalizedArgs = it.normalizedArgs,
                    argsHash = it.argsHash,
                    resultHash = it.resultHash,
                    status = it.status,
                    timestampMs = it.timestampMs,
                )
            })
        }
    }

    private fun isFatal(status: ToolExecutionStatus): Boolean = status == ToolExecutionStatus.FATAL_ERROR

    private fun isTransient(status: ToolExecutionStatus): Boolean {
        return status == ToolExecutionStatus.TIMEOUT ||
                status == ToolExecutionStatus.RATE_LIMIT ||
                status == ToolExecutionStatus.PARTIAL_TIMEOUT
    }

    private fun countTransientWithoutProgress(): Int {
        var count = 0
        for (i in history.size - 1 downTo 0) {
            val rec = history[i]
            when {
                rec.status == ToolExecutionStatus.SUCCESS -> return count
                isTransient(rec.status) -> count++
                isFatal(rec.status) -> return count // 致命错误会走其他分支，也中断瞬态计数
            }
        }
        return count
    }

    private fun detectSearchLoop(record: ToolCallRecord): Boolean {
        if (record.toolName != "web_search" && record.toolName != "read_url") return false

        val recentSearches = history
            .filter { it.toolName == "web_search" || it.toolName == "read_url" }
            .takeLast(repeatedSearchThreshold + 2)

        if (recentSearches.size < repeatedSearchThreshold) return false

        val currentTokens = extractQueryTokens(record.normalizedArgs)
        val currentUrls = extractUrls(record.normalizedArgs)
        val currentDomains = extractDomains(record.normalizedArgs)

        val window = recentSearches.takeLast(repeatedSearchThreshold)
        var overlapCount = 0
        for (prev in window) {
            if (prev === record) continue

            val prevTokens = extractQueryTokens(prev.normalizedArgs)
            val prevUrls = extractUrls(prev.normalizedArgs)
            val prevDomains = extractDomains(prev.normalizedArgs)

            val tokenOverlap = computeJaccard(currentTokens, prevTokens)
            val urlOverlap = computeOverlapRatio(currentUrls, prevUrls)
            val domainOverlap = computeOverlapRatio(currentDomains, prevDomains)

            // 关键词重叠 > 60%，或 URL 完全重复，或 domain 完全重复
            if (tokenOverlap > 0.6 || urlOverlap >= 1.0 || domainOverlap >= 1.0) {
                overlapCount++
            }
        }

        return overlapCount >= repeatedSearchThreshold - 1
    }

    private fun extractQueryTokens(normalizedArgs: String): Set<String> {
        return try {
            val json = org.json.JSONObject(normalizedArgs)
            val query = json.optString("query", "")
            tokenize(query)
        } catch (_: Exception) {
            tokenize(normalizedArgs)
        }
    }

    private fun extractUrls(normalizedArgs: String): Set<String> {
        return try {
            val json = org.json.JSONObject(normalizedArgs)
            val url = json.optString("url", "")
            if (url.isNotBlank()) setOf(normalizeUrl(url)) else emptySet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun extractDomains(normalizedArgs: String): Set<String> {
        return extractUrls(normalizedArgs).map { url ->
            url.split("/").firstOrNull()?.takeIf { it.isNotBlank() } ?: url
        }.toSet()
    }

    private fun tokenize(text: String): Set<String> {
        if (text.isBlank()) return emptySet()
        val stopWords = setOf(
            "的", "了", "和", "是", "在", "有", "我", "他", "她", "它", "们", "个", "之", "与", "及", "等",
            "the", "a", "an", "is", "are", "was", "were", "and", "or", "of", "to", "in", "on", "at", "for", "with", "from"
        )
        return text
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 1 && it !in stopWords }
            .toSet()
    }

    private fun normalizeUrl(url: String): String {
        return url.trim().lowercase()
            .removePrefix("http://")
            .removePrefix("https://")
            .removePrefix("www.")
            .trimEnd('/')
    }

    private fun normalizeArgs(args: String): String {
        return try {
            val json = org.json.JSONObject(args)
            val sorted = json.keys().asSequence().sorted().associate { key ->
                key to json.optString(key, "")
            }
            sorted.entries.joinToString(",") { "${it.key}=${it.value.trim().lowercase()}" }
        } catch (_: Exception) {
            args.trim().lowercase()
        }
    }

    private fun computeJaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersection = a.intersect(b).size
        val union = a.union(b).size
        return if (union == 0) 0.0 else intersection.toDouble() / union
    }

    private fun computeOverlapRatio(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersection = a.intersect(b).size
        val maxSize = maxOf(a.size, b.size)
        return if (maxSize == 0) 0.0 else intersection.toDouble() / maxSize
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }
}
