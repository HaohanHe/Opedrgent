package top.hsyscn.opedrgent.intelligence

import top.hsyscn.opedrgent.utils.DebugLog

/**
 * Token 预算监控器 —— 在 Agent Loop 中检测 token 递减（diminishing returns）。
 *
 * 对标 Claude Code 的 token budget 管理策略：
 * - 追踪每轮的 token 增量
 * - 当连续多轮增量低于阈值时判定为"递减"，主动终止循环
 * - 当使用量接近预算上限时发出继续/停止决策
 */
object TokenBudgetMonitor {

    const val COMPLETION_THRESHOLD = 0.9f   // 90% 预算还可继续
    const val DIMINISHING_THRESHOLD = 500    // 连续增量 < 500 token 视为递减
    const val MAX_CONTINUATIONS = 3           // 最大连续继续次数

    data class BudgetState(
        val continuationCount: Int = 0,
        val lastDeltaTokens: Int = 0,
        val lastGlobalTurnTokens: Int = 0,
        val startedAt: Long = System.currentTimeMillis(),
    )

    sealed class BudgetDecision {
        data class Continue(val nudgeMessage: String, val pct: Int, val turnTokens: Int) : BudgetDecision()
        data class Stop(val diminishingReturns: Boolean, val durationMs: Long) : BudgetDecision()
    }

    fun createTracker() = BudgetState()

    /**
     * 检查当前 token 使用状态，返回继续或停止决策。
     *
     * @param state 当前预算追踪状态
     * @param budget 总 token 预算上限（即 maxContextTokens）
     * @param currentTokens 当前已使用的 token 数量
     */
    fun checkBudget(
        state: BudgetState,
        budget: Int,
        currentTokens: Int,
    ): BudgetDecision {
        val pct = ((currentTokens.toFloat() / budget) * 100).toInt()
        val delta = currentTokens - state.lastGlobalTurnTokens

        val isDiminishing = state.continuationCount >= MAX_CONTINUATIONS &&
                delta < DIMINISHING_THRESHOLD &&
                state.lastDeltaTokens < DIMINISHING_THRESHOLD

        return if (!isDiminishing && currentTokens < (budget * COMPLETION_THRESHOLD).toInt()) {
            BudgetDecision.Continue(
                nudgeMessage = "继续生成中... ($pct%/$budget tokens)。继续干活，不要总结。",
                pct = pct,
                turnTokens = currentTokens,
            )
        } else {
            DebugLog.i(
                "TokenBudgetMonitor: Stop decision — diminishing=$isDiminishing, " +
                        "pct=$pct%, delta=$delta, continuations=${state.continuationCount}"
            )
            BudgetDecision.Stop(
                diminishingReturns = isDiminishing,
                durationMs = System.currentTimeMillis() - state.startedAt,
            )
        }
    }

    /**
     * 根据本轮结果更新预算状态，供下一轮 checkBudget 使用。
     */
    fun advanceState(state: BudgetState, currentTokens: Int): BudgetState {
        val delta = currentTokens - state.lastGlobalTurnTokens
        return state.copy(
            continuationCount = state.continuationCount + 1,
            lastDeltaTokens = delta,
            lastGlobalTurnTokens = currentTokens,
        )
    }

    // ==================== 自然语言预算解析（对标 Claude Code parseTokenBudget） ====================

    // 简写 +500k 锚定字符串开头
    private val SHORTHAND_START_RE = Regex("""^\s*\+(\d+(?:\.\d+)?)\s*(k|m|b)\b""", RegexOption.IGNORE_CASE)

    // 简写 +500k 锚定字符串结尾（兼容句末标点）
    private val SHORTHAND_END_RE = Regex("""\s\+(\d+(?:\.\d+)?)\s*(k|m|b)\s*[.!?]?\s*$""", RegexOption.IGNORE_CASE)

    // 完整短语 use 2M tokens / spend 500k tokens
    private val VERBOSE_RE = Regex("""\b(?:use|spend)\s+(\d+(?:\.\d+)?)\s*(k|m|b)\s*tokens?\b""", RegexOption.IGNORE_CASE)

    private val MULTIPLIERS = mapOf(
        "k" to 1_000L,
        "m" to 1_000_000L,
        "b" to 1_000_000_000L,
    )

    /**
     * 解析自然语言 token 预算声明。
     * 支持格式：
     *   - "+500k" / "+2m" / "+1b"（开头或结尾）
     *   - "use 2M tokens" / "spend 500k tokens"
     * 返回 null 表示无预算声明。
     */
    fun parseTokenBudget(input: String): Long? {
        SHORTHAND_START_RE.find(input)?.let { m ->
            val num = m.groupValues[1].toDouble()
            val unit = m.groupValues[2].lowercase()
            return (num * (MULTIPLIERS[unit] ?: 1_000L)).toLong()
        }
        SHORTHAND_END_RE.find(input)?.let { m ->
            val num = m.groupValues[1].toDouble()
            val unit = m.groupValues[2].lowercase()
            return (num * (MULTIPLIERS[unit] ?: 1_000L)).toLong()
        }
        VERBOSE_RE.find(input)?.let { m ->
            val num = m.groupValues[1].toDouble()
            val unit = m.groupValues[2].lowercase()
            return (num * (MULTIPLIERS[unit] ?: 1_000L)).toLong()
        }
        return null
    }
}
