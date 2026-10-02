package top.hsyscn.opedrgent.cultivation.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.hsyscn.opedrgent.cultivation.store.ReflectionRecord
import java.util.Calendar

/**
 * TrendInsights.from 的纯 JVM 单元测试。
 * 覆盖：空结果、透镜/路由过滤、维度归一合并与分隔、周/月粒度切换、UP/DOWN 走向、
 * 跟进完成量统计、TOP_N 截断。
 */
class TrendInsightsTest {

    private val dayMs = 86_400_000L

    /** 把任意日期快照到当周周一 00:00，避免硬编码星期。 */
    private fun mondayOf(year: Int, month0: Int, day: Int): Long {
        val c = Calendar.getInstance()
        c.clear()
        c.set(year, month0, day, 0, 0, 0)
        val diff = (c.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
        c.add(Calendar.DAY_OF_MONTH, -diff)
        return c.timeInMillis
    }

    private fun record(
        ts: Long,
        dimension: String = "",
        fuStatus: FollowUpStatus = FollowUpStatus.DONE,
        fuText: String = "做X",
        route: MirrorRoute = MirrorRoute.ANALYZE,
        lens: ReflectionLens = ReflectionLens.CRITIQUE,
    ): ReflectionRecord {
        val issue = MirrorIssue(
            quote = "q", baselineRef = "r", impact = "i",
            alternative = "a", dimension = dimension,
        )
        val fu = FollowUp(text = fuText, status = fuStatus)
        val report = MirrorReport(
            sessionId = "s",
            transcriptId = "t",
            route = route,
            overall = "o",
            issues = if (dimension.isBlank()) emptyList() else listOf(issue),
            nextStep = "n",
            followUps = listOf(fu),
            createdAt = ts,
        )
        return ReflectionRecord(lens = lens, critique = report, createdAt = ts)
    }

    @Test
    fun emptyRecordsGiveWeekEmptyNull() {
        val r = TrendInsights.from(emptyList())
        assertEquals(TrendGranularity.WEEK, r.granularity)
        assertTrue(r.trends.isEmpty())
        assertNull(r.followUpTrend)
    }

    @Test
    fun exemplarAndNonAnalyzeRoutesAreExcluded() {
        val exemplar = ReflectionRecord(
            lens = ReflectionLens.EXEMPLAR,
            critique = null,
            createdAt = mondayOf(2026, 0, 5),
        )
        val support = record(mondayOf(2026, 0, 5), dimension = "打断", route = MirrorRoute.SUPPORT)
        val r = TrendInsights.from(listOf(exemplar, support))
        assertEquals(TrendGranularity.WEEK, r.granularity)
        assertTrue(r.trends.isEmpty())
        assertNull(r.followUpTrend)
    }

    @Test
    fun singleDimensionDoneFollowUpYieldsNewTrend() {
        val ts = mondayOf(2026, 0, 5)
        val r = TrendInsights.from(listOf(record(ts, "打断")))
        assertEquals(TrendGranularity.WEEK, r.granularity)
        assertEquals(1, r.trends.size)
        val t = r.trends[0]
        assertEquals("打断", t.dimension)
        assertEquals(1, t.total)
        assertEquals(TrendDirection.NEW, t.direction)
        assertNotNull(r.followUpTrend)
        assertEquals(1, r.followUpTrend!!.totalDone)
    }

    @Test
    fun dimensionVariantsMergeAndDivergentSeparate() {
        val ts = mondayOf(2026, 0, 5)
        val r = TrendInsights.from(
            listOf(
                record(ts, "打断。"),
                record(ts, "打断"),
                record(ts, " 打断 "),
                record(ts, "拖延"),
            ),
        )
        assertEquals(2, r.trends.size)
        val top = r.trends[0]
        assertEquals("打断。", top.dimension)
        assertEquals(3, top.total)
        assertEquals(1, r.trends[1].total)
    }

    @Test
    fun spanOverNinetyDaysUsesMonthGranularity() {
        val first = mondayOf(2026, 0, 5)
        val later = first + 100L * dayMs
        val r = TrendInsights.from(listOf(record(first, "打断"), record(later, "打断")))
        assertEquals(TrendGranularity.MONTH, r.granularity)
    }

    @Test
    fun risingCountGivesUpDirection() {
        val w1 = mondayOf(2026, 0, 5)
        val w2 = w1 + 7L * dayMs
        val r = TrendInsights.from(
            listOf(
                record(w1, "打断"),
                record(w2, "打断"),
                record(w2, "打断"),
                record(w2, "打断"),
            ),
        )
        assertEquals(1, r.trends.size)
        assertEquals(TrendDirection.UP, r.trends[0].direction)
    }

    @Test
    fun fallingCountGivesDownDirection() {
        val w1 = mondayOf(2026, 0, 5)
        val w2 = w1 + 7L * dayMs
        val r = TrendInsights.from(
            listOf(
                record(w1, "打断"),
                record(w1, "打断"),
                record(w1, "打断"),
                record(w2, "打断"),
            ),
        )
        assertEquals(1, r.trends.size)
        assertEquals(TrendDirection.DOWN, r.trends[0].direction)
    }

    @Test
    fun nonDoneOrBlankFollowUpNotCounted() {
        val ts = mondayOf(2026, 0, 5)
        val r = TrendInsights.from(
            listOf(
                record(ts, "打断", fuStatus = FollowUpStatus.OPEN),
                record(ts, "拖延", fuStatus = FollowUpStatus.DONE, fuText = ""),
            ),
        )
        assertEquals(2, r.trends.size)
        // 无任何完成且非空的跟进 -> followUpTrend 为 null
        assertNull(r.followUpTrend)
    }

    @Test
    fun moreThanSixDimensionsTruncatedToTopSix() {
        val ts = mondayOf(2026, 0, 5)
        val recs = (1..7).map { record(ts, "维度$it") }
        val r = TrendInsights.from(recs)
        assertEquals(6, r.trends.size)
    }
}
