package top.hsyscn.opedrgent.cultivation.mirror

import org.json.JSONArray
import org.json.JSONObject
import top.hsyscn.opedrgent.cultivation.model.growth.GrowthEvidence
import top.hsyscn.opedrgent.cultivation.model.growth.GrowthPeriodData
import top.hsyscn.opedrgent.cultivation.model.growth.GrowthReview

/**
 * 成长报告最小解析器：把模型返回的 JSON 解析为 [GrowthReview]。
 *
 * 容错纪律：
 * - 整体不是有效 JSON 时返回骨架（overall 为空、其余空列表），不编造内容；
 * - 数组里的坏元素（非对象 / 缺字段）逐个跳过，不影响其他字段；
 * - evidence 逐条做“包含核验”：模型给的 quote 必须与【真实数据】里的某条原句逐字一致，
 *   或是其真子串（容忍模型裁掉空格/末尾标点）；否则丢弃该条，杜绝编造；
 * - focus 过滤空白后截断到前 3 条（契约要求 1~3）。
 */
class GrowthReviewAnalyzer {

    fun parse(raw: String, data: GrowthPeriodData): GrowthReview {
        val periodEnd = data.periodEndExclusive
        val jsonText = GrowthReviewPromptBuilder.extractJsonObject(raw)
        val root = runCatching { JSONObject(jsonText) }.getOrElse {
            return GrowthReview(
                periodType = data.periodType,
                periodStart = data.periodStart,
                periodEnd = periodEnd,
            )
        }

        val allowedQuotes = data.evidence.flatMap { it.quotes }
        val evidence = parseEvidence(root.optJSONArray("evidence"), allowedQuotes)

        return GrowthReview(
            periodType = data.periodType,
            periodStart = data.periodStart,
            periodEnd = periodEnd,
            overall = root.optString("overall", "").trim(),
            changes = parseStrings(root.optJSONArray("changes")),
            strengths = parseStrings(root.optJSONArray("strengths")),
            focus = parseStrings(root.optJSONArray("focus")).take(3),
            evidence = evidence,
        )
    }

    private fun parseStrings(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length())
            .mapNotNull { i -> arr.opt(i)?.toString()?.trim()?.takeIf { it.isNotBlank() } }
    }

    private fun parseEvidence(arr: JSONArray?, allowedQuotes: List<String>): List<GrowthEvidence> {
        if (arr == null) return emptyList()
        if (allowedQuotes.isEmpty()) return emptyList()
        val normalized = allowedQuotes.map { norm(it) }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val quote = o.optString("quote", "").trim()
            val dim = o.optString("dimension", "").trim()
            if (quote.isBlank() || dim.isBlank()) return@mapNotNull null
            // 包含核验：quote 必须与某条真实原句一致，或是其真子串；否则视为编造，丢弃。
            val q = norm(quote)
            val matched = normalized.any { allowed -> allowed == q || allowed.contains(q) }
            if (!matched) return@mapNotNull null
            GrowthEvidence(dimension = dim, quote = quote)
        }
    }

    /** 仅做空白折叠用于比对；落库的 quote 保留模型返回的原样（已 trim）。 */
    private fun norm(s: String): String = s.trim().replace(Regex("\\s+"), " ")
}
