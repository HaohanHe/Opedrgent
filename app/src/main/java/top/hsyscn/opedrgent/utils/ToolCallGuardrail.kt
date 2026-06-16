package top.hsyscn.opedrgent.utils

import java.security.MessageDigest

/**
 * 工具调用保护器 — 防止 Agent 陷入工具调用死循环
 *
 * 学自 Hermes Agent 的 ToolCallGuardrailController：
 * 1. 精确失败检测 — 相同 ToolCallSignature 连续失败 N 次
 * 2. 幂等无进展检测 — 连续调用无变化
 * 3. 同工具失败检测 — 同一工具反复失败
 *
 * 渐进策略: warn -> block -> halt
 */
class ToolCallGuardrail(
    private val maxConsecutiveFailures: Int = 3,
    private val maxSameToolFailures: Int = 5,
    private val maxNoProgressCalls: Int = 4,
) {
    enum class Action { ALLOW, WARN, BLOCK, HALT }

    data class ToolCallRecord(
        val toolName: String,
        val argsHash: String,
        val resultHash: String,
        val success: Boolean,
        val timestampMs: Long = System.currentTimeMillis(),
    )

    private val history = mutableListOf<ToolCallRecord>()

    /**
     * 记录一次工具调用结果，返回是否允许继续调用
     * ★ BUG-12 修复：synchronized 保证线程安全
     */
    fun record(toolName: String, args: String, result: String, success: Boolean): Action {
        val record = ToolCallRecord(
            toolName = toolName,
            argsHash = sha256(args),
            resultHash = sha256(result),
            success = success,
        )

        synchronized(history) {
            history.add(record)

            // 1. 精确失败检测：相同 (tool + args + result) 连续失败
            if (!success && history.size >= maxConsecutiveFailures) {
                val recent = history.takeLast(maxConsecutiveFailures)
                if (recent.all { !it.success && it.argsHash == record.argsHash && it.resultHash == record.resultHash }) {
                    return Action.HALT
                }
            }

            // 2. 同工具失败检测
            val sameToolFailures = history.takeLast(maxSameToolFailures)
                .filter { it.toolName == toolName && !it.success }
            if (sameToolFailures.size >= maxSameToolFailures) {
                return Action.HALT
            }

            // 3. 幂等无进展检测：连续调用结果完全相同
            if (history.size >= maxNoProgressCalls) {
                val recent = history.takeLast(maxNoProgressCalls)
                if (recent.map { it.resultHash }.distinct().size == 1) {
                    return Action.BLOCK
                }
            }

            // 4. 连续失败警告
            if (!success && history.size >= 2) {
                val recent = history.takeLast(2)
                if (recent.all { !it.success }) {
                    return Action.WARN
                }
            }

            return Action.ALLOW
        }
    }

    fun reset() {
        synchronized(history) { history.clear() }
    }

    fun getHistory(): List<ToolCallRecord> = synchronized(history) { history.toList() }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }
}
