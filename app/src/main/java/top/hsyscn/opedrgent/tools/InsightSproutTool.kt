package top.hsyscn.opedrgent.tools

import top.hsyscn.opedrgent.insight.InsightSproutEngine
import top.hsyscn.opedrgent.insight.SproutConfig
import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.model.ToolStateType
import top.hsyscn.opedrgent.network.ToolResult
import top.hsyscn.opedrgent.utils.DebugLog

class InsightSproutTool(
    private val engine: InsightSproutEngine,
) : ToolSet {

    private fun emptyResult(tp: ToolPart, msg: String): ToolResult {
        return ToolResult(toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.ERROR, error = msg, endTime = System.currentTimeMillis())))
    }

    @Tool("insight_sprout")
    @ToolDescription("知识发芽：对输入文本进行深度分析，从多个维度发芽衍生出结构化的洞察报告。参数中 text 为必填。")
    suspend fun executeInsightSprout(
        tp: ToolPart,
        config: ApiConfig,
        systemPrompt: String,
        useProviderSearch: Boolean,
    ): ToolResult {
        val text = tp.state.input["text"]
        if (text.isNullOrBlank()) {
            return emptyResult(tp, "缺少必填参数 text：需要提供待发芽的文本内容")
        }

        val length = tp.state.input["length"]?.toIntOrNull()?.coerceIn(100, 10000) ?: 2000
        val domains = tp.state.input["domains"]?.takeIf { it.isNotBlank() }?.split(",").map { it.trim().filter { c -> c.isLetterOrDigit() || c == '_' } }?.filter { it.isNotEmpty() }
        val useContext = tp.state.input["use_context"]?.toBooleanStrictOrNull() ?: true

        DebugLog.i("insight_sprout: text.length=${text.length}, length=$length, domains=$domains, use_context=$useContext")

        val sproutConfig = SproutConfig(
            maxLength = length,
            domains = domains,
            useContext = useContext,
        )

        val report = try {
            engine.sprout(text, sproutConfig)
        } catch (e: Exception) {
            DebugLog.w("insight_sprout: engine execution failed - ${e.message}")
            return emptyResult(tp, "知识发芽执行失败：${e.message}")
        }

        if (report.isBlank()) {
            return emptyResult(tp, "知识发芽未产生任何输出，请检查输入文本是否有效")
        }

        DebugLog.i("insight_sprout: completed, report.length=${report.length}")

        return ToolResult(toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.COMPLETED, output = report, endTime = System.currentTimeMillis())))
    }

    override fun getTools(): Map<String, ToolBinding> {
        return mapOf(
            "insight_sprout" to ToolBinding(
                name = "insight_sprout",
                description = "知识发芽：对输入文本进行深度分析，从多个维度发芽衍生出结构化的洞察报告。参数中 text 为必填，length/domains/use_context 为可选。",
                invoker = { tp, config, sp, ups -> executeInsightSprout(tp, config, sp, ups) },
            ),
        )
    }
}
