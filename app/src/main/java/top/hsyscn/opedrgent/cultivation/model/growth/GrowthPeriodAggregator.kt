package top.hsyscn.opedrgent.cultivation.model.growth

import top.hsyscn.opedrgent.action.ActionItem
import top.hsyscn.opedrgent.action.ActionStatus
import top.hsyscn.opedrgent.cultivation.model.DimensionTrend
import top.hsyscn.opedrgent.cultivation.model.MirrorRoute
import top.hsyscn.opedrgent.cultivation.model.ReflectionLens
import top.hsyscn.opedrgent.cultivation.model.TrendInsights
import top.hsyscn.opedrgent.cultivation.store.ReflectionRecord
import java.util.Calendar

/**
 * 周期类型：周（锚点所在周，周一对齐）/ 月（锚点所在月，1 日对齐）。
 */
enum class GrowthPeriodType {
    /** 周回顾：周一 00:00 起共 7 天。 */
    WEEK,

    /** 月回顾：当月 1 日 00:00 起整月。 */
    MONTH;

    companion object {
        /** 容错解析：month/m 等写法回退 MONTH，其余回退 WEEK。 */
        fun fromName(raw: String?): GrowthPeriodType {
            val r = raw?.trim()?.lowercase().orEmpty()
            return when {
                r.startsWith("month") || r == "m" -> MONTH
                else -> WEEK
            }
        }
    }
}

/**
 * 一个周期切片：本期 [curStart, curEndExclusive)；上一相邻等长区间为 [prevStart, curStart)。
 */
data class PeriodSpan(
    val curStart: Long,
    val curEndExclusive: Long,
    val prevStart: Long,
)

/**
 * 某维度在本期收集到的逐字证据。[quotes] 全部来自存储记录里 issues.quote 的逐字原句，
 * 按原句去重（LinkedHashSet 保序），工程不改写、不拼接。
 */
data class DimensionEvidence(
    val dimension: String,
    val quotes: List<String>,
)

/**
 * 周期成长聚合结果（纯本地、确定性计数）。
 *
 * 立场与 [TrendInsights] 一致：只对模型已产出的结构化字段做字符串级计数——
 * 不做关键词命中、不做语义聚类、不贴标签。无数据周期返回字段完整的空结构，不报错。
 *
 * @param lensBreakdown 按透镜分言行(CRITIQUE)/认知(COGNITIVE)/榜样(EXEMPLAR) 的复盘次数
 * @param trends 本期切片的维度趋势，口径完全复用 [TrendInsights.from]
 * @param dimensionTotals 展示维度名 -> 本期出现次数（归一化合并后）
 * @param dimensionDeltas 展示维度名 -> 本期减上一同长区间的真实差值（上一期没有时差值即本期数）
 * @param actionCreatedOpen 本期新建、当前仍 OPEN 的行动项数
 * @param actionCompletedInPeriod completedAt 落在本期的行动项完成数
 * @param reflectionCountDelta 本期复盘总数减上一同长区间
 * @param actionCompletedDelta 本期完成数减上一同长区间完成数
 * @param supportMode 本期内是否出现过 SUPPORT/CRISIS 路由的记录（直接读模型此前已判定的 route 字段，
 *        工程不做任何情绪关键词判定）；为 true 时提示模型以支持性框架解读
 */
data class GrowthPeriodData(
    val periodType: GrowthPeriodType,
    val periodStart: Long,
    val periodEndExclusive: Long,
    val reflectionCount: Int,
    val lensBreakdown: Map<String, Int>,
    val trends: List<DimensionTrend>,
    val dimensionTotals: Map<String, Int>,
    val dimensionDeltas: Map<String, Int>,
    val actionCreatedOpen: Int,
    val actionCreatedDone: Int,
    val actionCreatedDeferred: Int,
    val actionCompletedInPeriod: Int,
    val actionCompletedDelta: Int,
    val reflectionCountDelta: Int,
    val evidence: List<DimensionEvidence>,
    val supportMode: Boolean,
) {
    val periodLabel: String
        get() = if (periodType == GrowthPeriodType.WEEK) "周回顾" else "月回顾"
}

/**
 * 周期聚合器：给定锚点时间对齐本周/本月区间，再与上一同长区间对比。
 *
 * 聚合口径接线：
 * - 复盘记录：由调用方从 ReflectionStore 取好传入（用其现有历史列表方法，不在此重造查询）；
 * - 维度桶与归一化：直接 [TrendInsights.from] 对本期切片调用，归一化函数 [normalize] 与其私有实现逐字符一致；
 * - 行动项：由调用方从 ActionStore.listAll() 取好，按 createdAt / completedAt 落区间计数。
 */
object GrowthPeriodAggregator {

    /** 由锚点时间对齐本期（含上一相邻等长区间）。 */
    fun spanOf(type: GrowthPeriodType, anchorMs: Long): PeriodSpan {
        val cal = Calendar.getInstance()
        cal.timeInMillis = anchorMs
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (type == GrowthPeriodType.WEEK) {
            // 与 TrendInsights.bucketStart 同一对齐：DAY_OF_WEEK 周日=1..周六=7，回退到本周一。
            val diff = (cal.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
            cal.add(Calendar.DAY_OF_MONTH, -diff)
        } else {
            cal.set(Calendar.DAY_OF_MONTH, 1)
        }
        val curStart = cal.timeInMillis

        val prevCal = Calendar.getInstance()
        prevCal.timeInMillis = curStart
        if (type == GrowthPeriodType.WEEK) prevCal.add(Calendar.DAY_OF_MONTH, -7)
        else prevCal.add(Calendar.MONTH, -1)
        val prevStart = prevCal.timeInMillis

        val endCal = Calendar.getInstance()
        endCal.timeInMillis = curStart
        if (type == GrowthPeriodType.WEEK) endCal.add(Calendar.DAY_OF_MONTH, 7)
        else endCal.add(Calendar.MONTH, 1)
        return PeriodSpan(curStart, endCal.timeInMillis, prevStart)
    }

    /**
     * 聚合本期与上一同长区间。[reflections] / [actions] 由调用方从本地存储取全量历史传入。
     */
    fun aggregate(
        reflections: List<ReflectionRecord>,
        actions: List<ActionItem>,
        type: GrowthPeriodType,
        anchorMs: Long,
    ): GrowthPeriodData {
        val span = spanOf(type, anchorMs)
        val cur = reflections.filter { it.createdAt >= span.curStart && it.createdAt < span.curEndExclusive }
        val prev = reflections.filter { it.createdAt >= span.prevStart && it.createdAt < span.curStart }

        val lensBreakdown = cur.groupingBy { it.lens.name }.eachCount()
        val reflectionCountDelta = cur.size - prev.size

        // 维度趋势：直接复用 TrendInsights 对本期切片聚合（桶/方向/截断口径全量沿用）。
        val trends = TrendInsights.from(cur).trends

        // 维度计数 + 逐字证据（本期 / 上一期各算一遍，归一化口径一致）。
        val curCount = HashMap<String, Int>()
        val curDisplay = HashMap<String, String>()
        val quoteGroups = HashMap<String, LinkedHashSet<String>>()
        collect(cur, curCount, curDisplay, quoteGroups)

        val prevCount = HashMap<String, Int>()
        val prevDisplay = HashMap<String, String>()
        collect(prev, prevCount, prevDisplay, null)

        val dimensionTotals = LinkedHashMap<String, Int>()
        val dimensionDeltas = LinkedHashMap<String, Int>()
        for (key in (curCount.keys + prevCount.keys).toSortedSet()) {
            val display = curDisplay[key] ?: prevDisplay[key] ?: key
            val c = curCount[key] ?: 0
            val p = prevCount[key] ?: 0
            dimensionTotals[display] = c
            dimensionDeltas[display] = c - p
        }

        val evidence = quoteGroups.entries
            .map { (key, quotes) -> DimensionEvidence(curDisplay[key] ?: key, quotes.toList()) }
            .sortedByDescending { it.quotes.size }

        // 行动项：本期新建计数（按当前状态）；completedAt 落本期的完成数（含跨期创建）。
        val curCreated = actions.filter { it.createdAt >= span.curStart && it.createdAt < span.curEndExclusive }
        val actionCreatedOpen = curCreated.count { it.status == ActionStatus.OPEN }
        val actionCreatedDone = curCreated.count { it.status == ActionStatus.DONE }
        val actionCreatedDeferred = curCreated.count { it.status == ActionStatus.DEFERRED }
        val curCompleted = actions.count {
            it.completedAt > 0 && it.completedAt >= span.curStart && it.completedAt < span.curEndExclusive
        }
        val prevCompleted = actions.count {
            it.completedAt > 0 && it.completedAt >= span.prevStart && it.completedAt < span.curStart
        }

        val supportMode = cur.any { it.route == MirrorRoute.SUPPORT || it.route == MirrorRoute.CRISIS }

        return GrowthPeriodData(
            periodType = type,
            periodStart = span.curStart,
            periodEndExclusive = span.curEndExclusive,
            reflectionCount = cur.size,
            lensBreakdown = lensBreakdown,
            trends = trends,
            dimensionTotals = dimensionTotals,
            dimensionDeltas = dimensionDeltas,
            actionCreatedOpen = actionCreatedOpen,
            actionCreatedDone = actionCreatedDone,
            actionCreatedDeferred = actionCreatedDeferred,
            actionCompletedInPeriod = curCompleted,
            actionCompletedDelta = curCompleted - prevCompleted,
            reflectionCountDelta = reflectionCountDelta,
            evidence = evidence,
            supportMode = supportMode,
        )
    }

    /**
     * 收集一组记录里合格复盘（CRITIQUE/COGNITIVE 且 ANALYZE 路由——与 TrendInsights 同一合格口径）
     * 的维度计数与逐字证据。[outQuotes] 为 null 时只计数（上一期只需要计数算环比）。
     */
    private fun collect(
        records: List<ReflectionRecord>,
        outCount: HashMap<String, Int>,
        outDisplay: HashMap<String, String>,
        outQuotes: HashMap<String, LinkedHashSet<String>>?,
    ) {
        for (r in records) {
            val qualifying = (r.lens == ReflectionLens.CRITIQUE || r.lens == ReflectionLens.COGNITIVE) &&
                r.route == MirrorRoute.ANALYZE
            if (!qualifying) continue
            r.critique?.issues?.forEach { issue ->
                val key = normalize(issue.dimension)
                if (key.isBlank()) return@forEach
                outCount[key] = (outCount[key] ?: 0) + 1
                outDisplay.putIfAbsent(key, issue.dimension.trim())
                val q = issue.quote.trim()
                if (q.isNotBlank() && outQuotes != null) {
                    outQuotes.getOrPut(key) { LinkedHashSet() }.add(q)
                }
            }
        }
    }

    /**
     * 归一化：与 TrendInsights 私有 normalize 逐字符一致——仅做空白与句末标点级归一化，
     * 不做任何语义归并；措辞不同就分别呈现，把“是否同一种行为”的判断留给模型。
     */
    private fun normalize(s: String): String =
        s.trim().trimEnd('。', '，', '.', '！', '!', '？', '?', '；', ';', ' ').lowercase()
}
