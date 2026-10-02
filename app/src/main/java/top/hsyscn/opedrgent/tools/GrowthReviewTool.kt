package top.hsyscn.opedrgent.tools

import android.content.Context
import org.json.JSONObject
import top.hsyscn.opedrgent.action.ActionStore
import top.hsyscn.opedrgent.cultivation.mirror.GrowthReviewAnalyzer
import top.hsyscn.opedrgent.cultivation.mirror.GrowthReviewPromptBuilder
import top.hsyscn.opedrgent.cultivation.mirror.MirrorRuntime
import top.hsyscn.opedrgent.cultivation.model.growth.GrowthPeriodAggregator
import top.hsyscn.opedrgent.cultivation.model.growth.GrowthPeriodType
import top.hsyscn.opedrgent.cultivation.store.GrowthReviewStore
import top.hsyscn.opedrgent.cultivation.store.ReflectionStore
import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.model.ToolStateType
import top.hsyscn.opedrgent.network.ToolResult
import top.hsyscn.opedrgent.network.emptyResult
import top.hsyscn.opedrgent.settings.ApiSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 周期成长回顾工具（周报/月报）。
 *
 * 流程纯本地闭环：ReflectionStore + ActionStore 取数 → [GrowthPeriodAggregator] 确定性聚合 →
 * 本地模型解读（[MirrorRuntime] 默认端侧后端）→ [GrowthReviewAnalyzer] 解析（evidence 逐字核验）→
 * [GrowthReviewStore] 落库。是否调用由模型 tool_calls 决定，本工具不做关键词命中 / 意图判定。
 */
class GrowthReviewTool(private val context: Context) : ToolSet {

    private val app get() = context.applicationContext

    private val reflectionStore by lazy { ReflectionStore(app) }
    private val actionStore by lazy { ActionStore.getInstance(app) }
    private val reviewStore by lazy { GrowthReviewStore(app) }
    private val runtime by lazy { MirrorRuntime(app, ApiSettings(app)) }

    override fun getTools(): Map<String, ToolBinding> = mapOf(
        TOOL_CREATE to ToolBinding(
            name = TOOL_CREATE,
            description = """生成本周期（周/月）成长回顾：在本地统计复盘记录与行动项的真实数据（复盘次数、维度趋势与环比、行动项完成数、逐字原句证据），交由模型解读为一段简短回顾并持久化，返回回顾要点。
适用：用户想要"周报 / 月报 / 这阵子成长回顾 / 阶段性复盘"时主动调用。
period：week=本周（默认，周一对齐）；month=本月（1 日对齐）。
anchor：可选 ISO 日期（如 2026-09-28），以该日所在周期为准；缺省取当前时间。""",
            parameters = JSONObject(
                """
                {
                    "type": "object",
                    "properties": {
                        "period": {
                            "type": "string",
                            "enum": ["week", "month"],
                            "description": "周期粒度：week=周回顾（默认）；month=月回顾"
                        },
                        "anchor": {
                            "type": "string",
                            "description": "可选，ISO 日期 yyyy-MM-dd（如 2026-09-28），以该日所在周期为准；缺省为当前时间"
                        }
                    }
                }
                """.trimIndent(),
            ),
            invoker = { tp, _, _, _ -> create(tp) },
        ),
        TOOL_LIST to ToolBinding(
            name = TOOL_LIST,
            description = "查看历史周期成长回顾清单（周期类型、起止日期、一句话概要），用于回看以往周报/月报。",
            parameters = JSONObject("""{"type": "object", "properties": {}}"""),
            invoker = { tp, _, _, _ -> list(tp) },
        ),
    )

    private suspend fun create(tp: ToolPart): ToolResult {
        val input = tp.state.input
        val type = GrowthPeriodType.fromName(input["period"])
        val anchorMs = parseAnchor(input["anchor"]) ?: System.currentTimeMillis()
        val span = GrowthPeriodAggregator.spanOf(type, anchorMs)

        // 聚合：复盘记录用 ReflectionStore 现有历史列表方法取足量窗口，行动项全量。
        val data = GrowthPeriodAggregator.aggregate(
            reflections = reflectionStore.listRecent(500),
            actions = actionStore.listAll(),
            type = type,
            anchorMs = anchorMs,
        )

        // 解读：默认端侧本地模型，隐私纪律与镜鉴一致；未就绪时返回确定性原因，不静默降级云端。
        val backend = when (val resolution = runtime.resolve(useCloud = false)) {
            is MirrorRuntime.Resolution.Ready -> resolution.backend
            is MirrorRuntime.Resolution.Unavailable ->
                return emptyResult(tp, "成长回顾需要本地模型可用：${resolution.reason}")
        }

        val prompt = GrowthReviewPromptBuilder.build(data)
        val raw = backend.complete(prompt.system, prompt.user)
        val review = GrowthReviewAnalyzer().parse(raw, data)

        val id = reviewStore.insert(
            review.copy(
                id = 0,
                periodType = type,
                periodStart = span.curStart,
                periodEnd = span.curEndExclusive,
                createdAt = System.currentTimeMillis(),
            ),
        )

        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val startLabel = dayFmt.format(Date(span.curStart))
        val endLabel = dayFmt.format(Date(span.curEndExclusive - 1))
        val text = buildString {
            appendLine("成长回顾已生成（id=$id，${data.periodLabel} $startLabel ~ $endLabel）")
            if (review.overall.isNotBlank()) appendLine("总体：${review.overall}")
            if (review.changes.isNotEmpty()) {
                appendLine("变化：")
                review.changes.forEach { appendLine("  - $it") }
            }
            if (review.strengths.isNotEmpty()) {
                appendLine("值得肯定：")
                review.strengths.forEach { appendLine("  - $it") }
            }
            if (review.focus.isNotEmpty()) {
                appendLine("下周期可聚焦：")
                review.focus.forEach { appendLine("  - $it") }
            }
            if (review.evidence.isNotEmpty()) appendLine("逐字证据 ${review.evidence.size} 条（均为真实原句）")
        }.trim()
        return success(tp, text)
    }

    private suspend fun list(tp: ToolPart): ToolResult {
        val reviews = reviewStore.listAll()
        if (reviews.isEmpty()) return success(tp, "暂无历史成长回顾。可先调用 growth_review_create 生成本周/本月回顾。")
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val text = buildString {
            appendLine("历史成长回顾共 ${reviews.size} 份：")
            reviews.forEach { r ->
                val label = if (r.periodType == GrowthPeriodType.WEEK) "周回顾" else "月回顾"
                val start = dayFmt.format(Date(r.periodStart))
                val end = dayFmt.format(Date(r.periodEnd - 1))
                appendLine("  [id=${r.id}] $label $start ~ $end：${r.overall.ifBlank { "（无概要）" }}")
            }
        }.trim()
        return success(tp, text)
    }

    /** 容错解析 anchor：yyyy-MM-dd 或 yyyy-MM-dd'T'HH:mm:ss；解析失败返回 null 由调用方回落当前时间。 */
    private fun parseAnchor(raw: String?): Long? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }.parse(trimmed)?.time
        }.getOrNull() ?: runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { isLenient = false }.parse(trimmed)?.time
        }.getOrNull()
    }

    private fun success(tp: ToolPart, text: String): ToolResult = ToolResult(
        toolPart = tp.copy(
            state = tp.state.copy(
                status = ToolStateType.COMPLETED,
                output = text,
                endTime = System.currentTimeMillis(),
            ),
        ),
    )

    companion object {
        const val TOOL_CREATE = "growth_review_create"
        const val TOOL_LIST = "growth_review_list"
    }
}
