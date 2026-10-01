package top.hsyscn.opedrgent.cultivation.mirror

import top.hsyscn.opedrgent.cultivation.model.ExemplarReport
import top.hsyscn.opedrgent.cultivation.model.MirrorRoute

/**
 * 榜样镜的确定性质量门（纯字符串级、无语义、无词表），纪律同 AntiSycophancyGuard：
 * - 亮点引用原句必须真实出现在转写中（防止无证据的讨好）；
 * - “榜样会怎么做”不能为空，且每条要有具体做法；
 * - 每条优化都要给出可执行建议；
 * - 必须有一句可带走的心法。
 *
 * 代码侧不判断“这算不算亮点 / 优化得对不对”，语义与价值判断全部归模型。
 */
class ExemplarGuard {

    data class GuardResult(
        val passed: Boolean,
        val violations: List<String>,
        val checkedQuotes: Int,
        val matchedQuotes: Int,
    )

    fun verify(report: ExemplarReport, transcript: String): GuardResult {
        val violations = mutableListOf<String>()

        // 统一状态路由：SUPPORT/CRISIS 时榜样镜也必须先托住人，不再做冷冰冰的对照。
        when (report.route) {
            MirrorRoute.SUPPORT -> {
                if (report.support.isBlank()) violations += "SUPPORT 路由缺少支持性回应"
                if (report.actions.isNotEmpty() || report.highlights.isNotEmpty()) {
                    violations += "SUPPORT 路由不应继续输出榜样对照条目"
                }
                return GuardResult(violations.isEmpty(), violations, 0, 0)
            }

            MirrorRoute.CRISIS -> {
                if (report.support.isBlank()) violations += "CRISIS 路由缺少陪伴/支持性回应"
                if (report.helpResources.isBlank()) violations += "CRISIS 路由缺少求助资源引导"
                if (report.actions.isNotEmpty()) violations += "CRISIS 路由不得继续输出榜样对照条目"
                return GuardResult(violations.isEmpty(), violations, 0, 0)
            }

            MirrorRoute.ANALYZE -> Unit
        }

        val normalizedTranscript = normalizeForMatch(transcript)

        if (report.actions.isEmpty()) {
            violations += "缺少“榜样会怎么做”的具体做法"
        }
        report.actions.forEachIndexed { i, a ->
            if (a.action.isBlank()) violations += "第${i + 1}条做法为空"
        }

        var matched = 0
        report.highlights.forEachIndexed { i, h ->
            if (h.quote.isBlank()) {
                violations += "第${i + 1}条亮点缺少逐字引用原句"
            } else if (!containsQuote(normalizedTranscript, h.quote)) {
                violations += "第${i + 1}条亮点引用未在转写中逐字出现：${h.quote.take(20)}"
            } else {
                matched++
            }
            if (h.point.isBlank()) violations += "第${i + 1}条亮点未说明亮点是什么"
        }

        report.improvements.forEachIndexed { i, p ->
            if (p.suggestion.isBlank()) violations += "第${i + 1}条优化缺少可执行建议"
        }

        if (report.takeaway.isBlank()) violations += "缺少一句可带走的榜样心法"

        return GuardResult(
            passed = violations.isEmpty(),
            violations = violations,
            checkedQuotes = report.highlights.size,
            matchedQuotes = matched,
        )
    }

    /** 与 AntiSycophancyGuard 同口径：去空白与常见中英文标点，降低 ASR/标点差异导致的误判。 */
    private fun normalizeForMatch(text: String): String {
        val punct = "，。、；：？！,.;:?!“”\"'‘’（）()【】\\[\\]\\s…—-~·".toRegex()
        return punct.replace(text, "").lowercase()
    }

    private fun containsQuote(normalizedTranscript: String, quote: String): Boolean {
        val nq = normalizeForMatch(quote)
        return nq.isNotEmpty() && normalizedTranscript.contains(nq)
    }
}
