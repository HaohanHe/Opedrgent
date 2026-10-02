package top.hsyscn.opedrgent.cultivation.model

import top.hsyscn.opedrgent.cultivation.store.ReflectionRecord
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 长期趋势聚合（冻结契约：UI 层按此处签名写图，不得改名）。
 *
 * 立场与 [ReflectionInsights] 一致：这里只对模型已经产出的结构化字段做确定性、字符串级计数，
 * 不对用户语义做任何新判断——不做关键词命中、不做语义聚类、不贴标签。
 *
 * 统计范围：言行批判镜（CRITIQUE）与认知修炼镜（COGNITIVE）的 ANALYZE 记录，二者共用同一存储信封。
 * 趋势维度只来自模型在每条 issue 上自行归纳的 [MirrorIssue.dimension]：
 * - 它不是任何内置词表，也与“起始模板”[BaselineTemplates] 毫无关系（模板只是用户可改的脚手架）；
 *   认知镜下它是模型自归纳的“认知维度名”，同样不是固定词表；
 * - 维度名只有在归一化（trim / 去句末标点 / lowercase）后文本完全一致才合并，措辞不同就分别呈现，
 *   把“是否同一种行为”的判断留给模型在下次复盘中结合长期上下文完成。
 */

/** 趋势时间桶粒度：跨度不足 90 天按周（周一对齐），否则按月（1 日对齐）。 */
enum class TrendGranularity { WEEK, MONTH }

/** 某维度最近一桶相对上一桶的走向；只有一个非空桶视为新出现。 */
enum class TrendDirection { UP, DOWN, FLAT, NEW }

/** 一个时间桶上的计数点。[bucketStart] 为桶起点毫秒，[bucketLabel] 为对外展示标签。 */
data class TrendPoint(
    val bucketStart: Long,
    val bucketLabel: String,
    val count: Int,
)

/** 单个行为维度在整条时间轴上的分布与走向。 */
data class DimensionTrend(
    val dimension: String,
    val points: List<TrendPoint>,
    val total: Int,
    val direction: TrendDirection,
)

/** 跟进完成量在整条时间轴上的分布。 */
data class FollowUpTrend(
    val points: List<TrendPoint>,
    val totalDone: Int,
)

/** 一次趋势聚合的完整结果。无任何维度与跟进时 trends 为空、followUpTrend 为 null。 */
data class TrendInsights(
    val granularity: TrendGranularity,
    val trends: List<DimensionTrend>,
    val followUpTrend: FollowUpTrend?,
) {
    companion object {
        private const val DAY_MS = 86_400_000L
        private const val SPAN_THRESHOLD_MS = 90L * DAY_MS
        private const val TOP_N = 6

        fun from(records: List<ReflectionRecord>): TrendInsights {
            // 合格记录：言行批判镜或认知修炼镜，且正常分析路由（ANALYZE）。两面透镜都走同一
            // 存储信封 ReflectionRecord.critique，issue.dimension 同口径聚合；SUPPORT/CRISIS 不产出 issue。
            val qualifying = records.filter {
                (it.lens == ReflectionLens.CRITIQUE || it.lens == ReflectionLens.COGNITIVE) &&
                    it.route == MirrorRoute.ANALYZE
            }
            if (qualifying.isEmpty()) {
                return TrendInsights(TrendGranularity.WEEK, emptyList(), null)
            }

            val first = qualifying.minOf { it.createdAt }
            val last = qualifying.maxOf { it.createdAt }
            val granularity =
                if (last - first < SPAN_THRESHOLD_MS) TrendGranularity.WEEK else TrendGranularity.MONTH

            // 统一时间轴：从首条记录所在桶铺到末条记录所在桶，空桶补 0。
            val firstBucket = bucketStart(first, granularity)
            val lastBucket = bucketStart(last, granularity)
            val bucketStarts = bucketSequence(firstBucket, lastBucket, granularity)
            val bucketIndex = HashMap<Long, Int>(bucketStarts.size * 2)
            bucketStarts.forEachIndexed { i, b -> bucketIndex[b] = i }
            val n = bucketStarts.size

            // 归一化维度名 -> 每桶计数；同时保留首个原始写法作展示名。
            val dimCounts = LinkedHashMap<String, IntArray>()
            val dimDisplay = HashMap<String, String>()
            val fuDone = IntArray(n)

            for (r in qualifying) {
                val idx = bucketIndex[bucketStart(r.createdAt, granularity)] ?: continue
                r.critique?.issues?.forEach { issue ->
                    val key = normalize(issue.dimension)
                    if (key.isNotBlank()) {
                        val arr = dimCounts.getOrPut(key) { IntArray(n) }
                        arr[idx] += 1
                        dimDisplay.putIfAbsent(key, issue.dimension.trim())
                    }
                }
                r.critique?.followUps?.forEach { fu ->
                    if (fu.status == FollowUpStatus.DONE && fu.text.isNotBlank()) fuDone[idx] += 1
                }
            }

            val trends = dimCounts.entries
                .map { (key, arr) ->
                    val points = bucketStarts.mapIndexed { i, b ->
                        TrendPoint(b, bucketLabel(b, granularity), arr[i])
                    }
                    DimensionTrend(
                        dimension = dimDisplay[key] ?: key,
                        points = points,
                        total = arr.sum(),
                        direction = directionOf(arr),
                    )
                }
                .sortedWith(compareByDescending<DimensionTrend> { it.total }.thenBy { it.dimension })
                .take(TOP_N)

            val followUpTrend = if (fuDone.sum() > 0) {
                FollowUpTrend(
                    points = bucketStarts.mapIndexed { i, b ->
                        TrendPoint(b, bucketLabel(b, granularity), fuDone[i])
                    },
                    totalDone = fuDone.sum(),
                )
            } else null

            // 无任何维度与跟进时返回空结果。
            if (trends.isEmpty() && followUpTrend == null) {
                return TrendInsights(granularity, emptyList(), null)
            }
            return TrendInsights(granularity, trends, followUpTrend)
        }

        /** 走向：最后一桶与上一桶比较；仅一个非空桶视为新出现。 */
        private fun directionOf(arr: IntArray): TrendDirection {
            val nonEmpty = arr.count { it > 0 }
            if (nonEmpty <= 1) return TrendDirection.NEW
            val last = arr[arr.size - 1]
            val prev = arr[arr.size - 2]
            return when {
                last > prev -> TrendDirection.UP
                last < prev -> TrendDirection.DOWN
                else -> TrendDirection.FLAT
            }
        }

        /** 仅做空白与句末标点级归一化，不做任何语义归并（口径同 ReflectionInsights.normalize）。 */
        private fun normalize(s: String): String =
            s.trim().trimEnd('。', '，', '.', '！', '!', '？', '?', '；', ';', ' ').lowercase()

        /** 把任意时刻对齐到桶起点：周按周一 00:00，月按 1 日 00:00。 */
        private fun bucketStart(ts: Long, granularity: TrendGranularity): Long {
            val cal = Calendar.getInstance()
            cal.timeInMillis = ts
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            if (granularity == TrendGranularity.WEEK) {
                // DAY_OF_WEEK: 周日=1 .. 周六=7；回退到本周一。
                val diff = (cal.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
                cal.add(Calendar.DAY_OF_MONTH, -diff)
            } else {
                cal.set(Calendar.DAY_OF_MONTH, 1)
            }
            return cal.timeInMillis
        }

        /** 从首桶到末桶逐桶铺开（周 +7 天，月 +1 个月），保证空桶也在序列中。 */
        private fun bucketSequence(first: Long, last: Long, granularity: TrendGranularity): List<Long> {
            val out = ArrayList<Long>()
            val cal = Calendar.getInstance()
            var cur = first
            while (cur <= last) {
                out.add(cur)
                cal.timeInMillis = cur
                if (granularity == TrendGranularity.WEEK) cal.add(Calendar.WEEK_OF_YEAR, 1)
                else cal.add(Calendar.MONTH, 1)
                cur = cal.timeInMillis
            }
            return out
        }

        private fun bucketLabel(ts: Long, granularity: TrendGranularity): String {
            val pattern = if (granularity == TrendGranularity.WEEK) "MM-dd" else "yyyy-MM"
            return SimpleDateFormat(pattern, Locale.US).format(Date(ts))
        }
    }
}
