package top.hsyscn.opedrgent.cultivation.model.growth

/**
 * 成长报告里的一条逐字证据。[quote] 必须是聚合数据中真实出现的原句
 * （由 GrowthReviewAnalyzer 做包含核验，拒绝模型编造/改写）。
 */
data class GrowthEvidence(
    val dimension: String,
    val quote: String,
)

/**
 * 一份周期成长回顾（模型对本期聚合数据的解读结果）。
 *
 * 字段纪律：
 * - [overall]：基于真实数据的总体观察；
 * - [changes]：只陈述确有 delta 的项；无变化时模型应如实说明，不硬凑；
 * - [strengths]：本期有数据支撑的具体进步；无则为空；
 * - [focus]：下周期 1~3 个可聚焦点（解析时截断/过滤到 1~3）；
 * - [evidence]：逐字原句，不改写不拼接。
 *
 * @param periodEnd 区间上界（不含），与 [GrowthPeriodData.periodEndExclusive] 对齐
 */
data class GrowthReview(
    val id: Long = 0,
    val periodType: GrowthPeriodType,
    val periodStart: Long,
    val periodEnd: Long,
    val overall: String = "",
    val changes: List<String> = emptyList(),
    val strengths: List<String> = emptyList(),
    val focus: List<String> = emptyList(),
    val evidence: List<GrowthEvidence> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
)
