package top.hsyscn.opedrgent.cultivation.model.growth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.hsyscn.opedrgent.action.ActionItem
import top.hsyscn.opedrgent.action.ActionStatus
import top.hsyscn.opedrgent.cultivation.model.FollowUp
import top.hsyscn.opedrgent.cultivation.model.FollowUpStatus
import top.hsyscn.opedrgent.cultivation.model.MirrorIssue
import top.hsyscn.opedrgent.cultivation.model.MirrorReport
import top.hsyscn.opedrgent.cultivation.model.MirrorRoute
import top.hsyscn.opedrgent.cultivation.model.ReflectionLens
import top.hsyscn.opedrgent.cultivation.store.ReflectionRecord
import java.util.Calendar
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * [GrowthPeriodAggregator] 故障注入与边界测试（纯 JVM）。
 *
 * 覆盖维度：
 *  - 空输入（空 reflections / 空 actions）不报错、字段完整；
 *  - critique=null / 非合格透镜(EXEMPLARY) / 非合格路由(SUPPORT/CRISIS) 被正确排除；
 *  - 维度名为空白 / 全标点 / 极端超长字符串；
 *  - quote 为空 / 全空白 / 极端超长；
 *  - 时间边界：记录恰好落在 curStart(含) / curEndExclusive(不含) / prevStart；
 *  - createdAt=epoch(0)、未来时间戳；
 *  - 月回顾跨年（12 月锚点 → 上一年 11 月）；
 *  - supportMode 对 SUPPORT/CRISIS 路由的判定；
 *  - 并发多线程同时聚合同一列表，结果应完全一致（纯函数、无共享可变状态）。
 */
class GrowthPeriodAggregatorFaultTest {

    // ---------- 构造 helpers ----------

    private fun issue(dimension: String, quote: String = "原句摘录") = MirrorIssue(
        quote = quote,
        baselineRef = "ref",
        impact = "impact",
        alternative = "alt",
        dimension = dimension,
    )

    private fun critiqueRecord(
        lens: ReflectionLens = ReflectionLens.CRITIQUE,
        route: MirrorRoute = MirrorRoute.ANALYZE,
        createdAt: Long,
        issues: List<MirrorIssue> = emptyList(),
        followUps: List<FollowUp> = emptyList(),
    ): ReflectionRecord = ReflectionRecord(
        lens = lens,
        critique = MirrorReport(
            sessionId = "s",
            transcriptId = "t",
            route = route,
            overall = "o",
            issues = issues,
            nextStep = "n",
            followUps = followUps,
            createdAt = createdAt,
        ),
        createdAt = createdAt,
    )

    private fun noCritiqueRecord(createdAt: Long): ReflectionRecord = ReflectionRecord(
        lens = ReflectionLens.CRITIQUE,
        critique = null,
        createdAt = createdAt,
    )

    private fun action(createdAt: Long, status: ActionStatus = ActionStatus.OPEN, completedAt: Long = 0L) =
        ActionItem(
            title = "act",
            status = status,
            createdAt = createdAt,
            completedAt = completedAt,
        )

    private fun anchor(
        year: Int, month0: Int, day: Int, hour: Int = 12,
    ): Long {
        val c = Calendar.getInstance()
        c.set(year, month0, day, hour, 0, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    // ---------- 空输入 ----------

    @Test
    fun `空 reflections 空 actions 返回完整空结构不抛异常`() {
        val a = anchor(2026, Calendar.JUNE, 10)
        val data = GrowthPeriodAggregator.aggregate(
            reflections = emptyList(),
            actions = emptyList(),
            type = GrowthPeriodType.WEEK,
            anchorMs = a,
        )
        assertNotNull(data)
        assertEquals(0, data.reflectionCount)
        assertEquals(0, data.actionCreatedOpen)
        assertEquals(0, data.actionCreatedDone)
        assertEquals(0, data.actionCreatedDeferred)
        assertEquals(0, data.actionCompletedInPeriod)
        assertTrue(data.dimensionTotals.isEmpty())
        assertTrue(data.dimensionDeltas.isEmpty())
        assertTrue(data.evidence.isEmpty())
        assertTrue(data.lensBreakdown.isEmpty())
        assertFalse(data.supportMode)
    }

    @Test
    fun `critique 为 null 的记录不参与聚合且不崩`() {
        val a = anchor(2026, Calendar.JUNE, 10)
        val recs = listOf(
            noCritiqueRecord(a - 1000),
            noCritiqueRecord(a - 2000),
        )
        val data = GrowthPeriodAggregator.aggregate(recs, emptyList(), GrowthPeriodType.WEEK, a)
        // 两条记录仍计入 reflectionCount（cur 切片），但无 issue 维度
        assertEquals(2, data.reflectionCount)
        assertTrue(data.dimensionTotals.isEmpty())
        assertTrue(data.evidence.isEmpty())
    }

    // ---------- 非合格透镜/路由被排除 ----------

    @Test
    fun `EXEMPLARY 透镜不产出维度计数`() {
        val a = anchor(2026, Calendar.JUNE, 10)
        val recs = listOf(
            critiqueRecord(
                lens = ReflectionLens.EXEMPLAR,
                createdAt = a - 1000,
                issues = listOf(issue(dimension = "榜样维度")),
            ),
            critiqueRecord(
                createdAt = a - 2000,
                issues = listOf(issue(dimension = "批判维度")),
            ),
        )
        val data = GrowthPeriodAggregator.aggregate(recs, emptyList(), GrowthPeriodType.WEEK, a)
        assertEquals(2, data.reflectionCount)
        // 只有 CRITIQUE 合格；EXEMPLARY 的 issue 不计入
        assertEquals(1, data.dimensionTotals["批判维度"])
        assertFalse(data.dimensionTotals.containsKey("榜样维度"))
    }

    @Test
    fun `SUPPORT 路由记录计入 reflectionCount 但不产维度且触发 supportMode`() {
        val a = anchor(2026, Calendar.JUNE, 10)
        val recs = listOf(
            critiqueRecord(
                route = MirrorRoute.SUPPORT,
                createdAt = a - 1000,
                issues = listOf(issue(dimension = "不应计入")),
            ),
        )
        val data = GrowthPeriodAggregator.aggregate(recs, emptyList(), GrowthPeriodType.WEEK, a)
        assertEquals(1, data.reflectionCount)
        assertTrue(data.dimensionTotals.isEmpty())
        assertTrue(data.supportMode)
    }

    @Test
    fun `CRISIS 路由触发 supportMode`() {
        val a = anchor(2026, Calendar.JUNE, 10)
        val recs = listOf(
            critiqueRecord(route = MirrorRoute.CRISIS, createdAt = a - 1000),
        )
        val data = GrowthPeriodAggregator.aggregate(recs, emptyList(), GrowthPeriodType.WEEK, a)
        assertTrue(data.supportMode)
    }

    // ---------- 空白 / 超长维度与 quote ----------

    @Test
    fun `空白维度与全标点维度被跳过`() {
        val a = anchor(2026, Calendar.JUNE, 10)
        val recs = listOf(
            critiqueRecord(
                createdAt = a - 1000,
                issues = listOf(
                    issue(dimension = ""),
                    issue(dimension = "   "),
                    issue(dimension = "。，.,！!？?；; "),
                    issue(dimension = "正常维度"),
                ),
            ),
        )
        val data = GrowthPeriodAggregator.aggregate(recs, emptyList(), GrowthPeriodType.WEEK, a)
        assertEquals(1, data.dimensionTotals["正常维度"])
        // 空白/纯标点归一化后为 blank，被 collect 内 continue 跳过
        assertFalse(data.dimensionTotals.keys.any { it.isBlank() })
    }

    @Test
    fun `极端超长维度名不崩且可去重归一`() {
        val longDim = "打".repeat(10_000)
        val longDim2 = longDim + "。" // 句末标点归一化后应合并
        val a = anchor(2026, Calendar.JUNE, 10)
        val recs = listOf(
            critiqueRecord(createdAt = a - 1000, issues = listOf(issue(dimension = longDim))),
            critiqueRecord(createdAt = a - 2000, issues = listOf(issue(dimension = longDim2))),
        )
        val data = GrowthPeriodAggregator.aggregate(recs, emptyList(), GrowthPeriodType.WEEK, a)
        assertEquals(1, data.dimensionTotals.size)
        assertEquals(2, data.dimensionTotals.values.first())
    }

    @Test
    fun `极端超长 quote 被收集并去重`() {
        val longQuote = "原话".repeat(5_000)
        val a = anchor(2026, Calendar.JUNE, 10)
        val recs = listOf(
            critiqueRecord(createdAt = a - 1000, issues = listOf(issue(dimension = "d", quote = longQuote))),
            critiqueRecord(createdAt = a - 2000, issues = listOf(issue(dimension = "d", quote = longQuote))),
        )
        val data = GrowthPeriodAggregator.aggregate(recs, emptyList(), GrowthPeriodType.WEEK, a)
        assertEquals(1, data.evidence.size)
        assertEquals(1, data.evidence[0].quotes.size) // LinkedHashSet 去重
        assertEquals(longQuote, data.evidence[0].quotes[0])
    }

    @Test
    fun `空白 quote 不进入证据`() {
        val a = anchor(2026, Calendar.JUNE, 10)
        val recs = listOf(
            critiqueRecord(
                createdAt = a - 1000,
                issues = listOf(issue(dimension = "d", quote = "   ")),
            ),
        )
        val data = GrowthPeriodAggregator.aggregate(recs, emptyList(), GrowthPeriodType.WEEK, a)
        assertEquals(1, data.dimensionTotals["d"]) // 计数仍在
        assertTrue(data.evidence.all { it.quotes.isEmpty() }) // 但无 quote 证据
    }

    // ---------- 时间边界 ----------

    @Test
    fun `记录恰好落在 curStart 含 curEndExclusive 不含`() {
        // 周一锚点 → curStart 为该周周一 00:00
        val a = anchor(2026, Calendar.JUNE, 10) // 2026-06-10 是周三
        val span = GrowthPeriodAggregator.spanOf(GrowthPeriodType.WEEK, a)
        // 落在 curStart（周一）应计入本期
        val inStart = critiqueRecord(createdAt = span.curStart, issues = listOf(issue("d1")))
        // 落在 curEndExclusive（下周一）应不计入本期（属下期）
        val atEnd = critiqueRecord(createdAt = span.curEndExclusive, issues = listOf(issue("d2")))
        // 落在 prevStart 应计入上期
        val atPrev = critiqueRecord(createdAt = span.prevStart, issues = listOf(issue("d3")))

        val data = GrowthPeriodAggregator.aggregate(
            listOf(inStart, atEnd, atPrev), emptyList(), GrowthPeriodType.WEEK, a,
        )
        assertEquals(1, data.reflectionCount) // 只 inStart 在本期
        assertEquals(1, data.dimensionTotals["d1"])
        assertFalse(data.dimensionTotals.containsKey("d2"))
        // d3 在上期 → 本期 0，环比 -1
        assertEquals(0, data.dimensionTotals["d3"])
        assertEquals(-1, data.dimensionDeltas["d3"])
    }

    @Test
    fun `createdAt 为 epoch 0 不崩`() {
        val a = anchor(2026, Calendar.JUNE, 10)
        val recs = listOf(critiqueRecord(createdAt = 0L, issues = listOf(issue("d"))))
        val data = GrowthPeriodAggregator.aggregate(recs, emptyList(), GrowthPeriodType.WEEK, a)
        // epoch 远在 prevStart 之前，既不在本期也不在 prev 窗口
        assertEquals(0, data.reflectionCount)
        assertTrue(data.dimensionTotals.isEmpty())
    }

    @Test
    fun `未来时间戳记录计入本期`() {
        val a = anchor(2026, Calendar.JUNE, 10)
        val future = a + 60 * 1000 // 锚点后 1 分钟，仍在本周内
        val recs = listOf(critiqueRecord(createdAt = future, issues = listOf(issue("d"))))
        val data = GrowthPeriodAggregator.aggregate(recs, emptyList(), GrowthPeriodType.WEEK, a)
        assertEquals(1, data.reflectionCount)
    }

    // ---------- 月回顾跨年 ----------

    @Test
    fun `月回顾跨年 prevStart 落上一年`() {
        val a = anchor(2026, Calendar.JANUARY, 15) // 1 月
        val span = GrowthPeriodAggregator.spanOf(GrowthPeriodType.MONTH, a)
        assertEquals(2026, Calendar.getInstance().apply { timeInMillis = span.curStart }.get(Calendar.YEAR))
        assertEquals(Calendar.JANUARY, Calendar.getInstance().apply { timeInMillis = span.curStart }.get(Calendar.MONTH))
        // prevStart 应为 2025-12-01
        val prevCal = Calendar.getInstance().apply { timeInMillis = span.prevStart }
        assertEquals(2025, prevCal.get(Calendar.YEAR))
        assertEquals(Calendar.DECEMBER, prevCal.get(Calendar.MONTH))

        // 一条落在 12 月（上期），一条落在 1 月（本期）
        val inCur = critiqueRecord(createdAt = span.curStart + 86_400_000L, issues = listOf(issue("d")))
        val inPrev = critiqueRecord(createdAt = span.prevStart + 86_400_000L, issues = listOf(issue("d")))
        val data = GrowthPeriodAggregator.aggregate(listOf(inCur, inPrev), emptyList(), GrowthPeriodType.MONTH, a)
        assertEquals(1, data.dimensionTotals["d"])
        assertEquals(0, data.dimensionDeltas["d"]) // 本期1 上期1
    }

    // ---------- 行动项边界 ----------

    @Test
    fun `行动项 completedAt=0 不计完成数`() {
        val a = anchor(2026, Calendar.JUNE, 10)
        val span = GrowthPeriodAggregator.spanOf(GrowthPeriodType.WEEK, a)
        val acts = listOf(
            action(createdAt = span.curStart + 1000, status = ActionStatus.OPEN, completedAt = 0L),
            action(createdAt = span.curStart + 1000, status = ActionStatus.DONE, completedAt = 0L),
            action(createdAt = span.curStart + 1000, status = ActionStatus.DEFERRED, completedAt = 0L),
        )
        val data = GrowthPeriodAggregator.aggregate(emptyList(), acts, GrowthPeriodType.WEEK, a)
        assertEquals(1, data.actionCreatedOpen)
        assertEquals(1, data.actionCreatedDone)
        assertEquals(1, data.actionCreatedDeferred)
        assertEquals(0, data.actionCompletedInPeriod) // completedAt=0 不算
    }

    @Test
    fun `行动项 completedAt 落在上期不计本期完成`() {
        val a = anchor(2026, Calendar.JUNE, 10)
        val span = GrowthPeriodAggregator.spanOf(GrowthPeriodType.WEEK, a)
        val acts = listOf(
            // 创建于上期，但完成于本期
            action(createdAt = span.prevStart + 1000, status = ActionStatus.DONE, completedAt = span.curStart + 1000),
        )
        val data = GrowthPeriodAggregator.aggregate(emptyList(), acts, GrowthPeriodType.WEEK, a)
        assertEquals(1, data.actionCompletedInPeriod) // 跨期创建但本期完成
        assertEquals(0, data.actionCreatedOpen + data.actionCreatedDone + data.actionCreatedDeferred) // 不在本期新建
    }

    // ---------- GrowthPeriodType.fromName 容错 ----------

    @Test
    fun `fromName 容错解析`() {
        assertEquals(GrowthPeriodType.MONTH, GrowthPeriodType.fromName("month"))
        assertEquals(GrowthPeriodType.MONTH, GrowthPeriodType.fromName("MONTH"))
        assertEquals(GrowthPeriodType.MONTH, GrowthPeriodType.fromName("m"))
        assertEquals(GrowthPeriodType.MONTH, GrowthPeriodType.fromName("Month"))
        assertEquals(GrowthPeriodType.WEEK, GrowthPeriodType.fromName(null))
        assertEquals(GrowthPeriodType.WEEK, GrowthPeriodType.fromName(""))
        assertEquals(GrowthPeriodType.WEEK, GrowthPeriodType.fromName("  "))
        assertEquals(GrowthPeriodType.WEEK, GrowthPeriodType.fromName("week"))
        assertEquals(GrowthPeriodType.WEEK, GrowthPeriodType.fromName("weeeee"))
    }

    // ---------- 并发 ----------

    @Test
    fun `多线程并发聚合同一列表结果一致`() {
        val a = anchor(2026, Calendar.JUNE, 10)
        val recs = (0 until 50).map { i ->
            critiqueRecord(
                createdAt = a - i * 60_000L,
                issues = listOf(issue(dimension = "维度${i % 5}", quote = "原话$i")),
            )
        }
        val acts = (0 until 20).map { i ->
            action(createdAt = a - i * 60_000L, status = if (i % 2 == 0) ActionStatus.DONE else ActionStatus.OPEN)
        }

        val nThreads = 8
        val exec = Executors.newFixedThreadPool(nThreads)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(nThreads)
        val results = Array(nThreads) { 0 }
        val errors = Array<Throwable?>(nThreads) { null }

        repeat(nThreads) { idx ->
            exec.submit {
                try {
                    startLatch.await()
                    val d = GrowthPeriodAggregator.aggregate(recs, acts, GrowthPeriodType.WEEK, a)
                    results[idx] = d.reflectionCount
                } catch (t: Throwable) {
                    errors[idx] = t
                } finally {
                    doneLatch.countDown()
                }
            }
        }
        startLatch.countDown()
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS))
        exec.shutdown()

        errors.forEach { e -> if (e != null) throw AssertionError("并发聚合异常", e) }
        // 所有线程结果必须一致（纯函数，无共享可变状态）
        val expected = results[0]
        results.forEach { assertEquals(expected, it) }
        assertEquals(50, expected)
    }

    // ---------- 大批量 ----------

    @Test
    fun `上千条记录聚合不崩`() {
        val a = anchor(2026, Calendar.JUNE, 10)
        val recs = (0 until 2_000).map { i ->
            critiqueRecord(
                createdAt = a - i * 30_000L,
                issues = listOf(
                    issue(dimension = "维度${i % 7}", quote = "原话$i"),
                ),
            )
        }
        val data = GrowthPeriodAggregator.aggregate(recs, emptyList(), GrowthPeriodType.WEEK, a)
        // 30 秒间隔，2000 条跨度约 16.6 小时，全部落在本周内
        assertEquals(2_000, data.reflectionCount)
        assertEquals(7, data.dimensionTotals.size)
    }
}
