package top.hsyscn.opedrgent.tools

import top.hsyscn.opedrgent.model.ChatMessage
import top.hsyscn.opedrgent.model.Role
import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.model.ToolStateType
import top.hsyscn.opedrgent.network.LlmClient
import top.hsyscn.opedrgent.network.ToolResult
import top.hsyscn.opedrgent.settings.ApiConfig
import top.hsyscn.opedrgent.utils.DebugLog

class GenerateReportTool(
    private val llm: LlmClient,
) : ToolSet {

    private fun emptyResult(tp: ToolPart, msg: String): ToolResult {
        return ToolResult(toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.ERROR, error = msg, endTime = System.currentTimeMillis())))
    }

    @Tool("generate_report")
    @ToolDescription("生成研究报告：整理研究结果，生成结构化的报告。参数中 topic 为必填，data 为可选（包含研究数据）。")
    suspend fun executeGenerateReport(
        tp: ToolPart,
        config: ApiConfig,
        systemPrompt: String,
        useProviderSearch: Boolean,
    ): ToolResult {
        val topic = tp.state.input["topic"] ?: tp.state.input["query"] ?: return emptyResult(tp, "缺少报告主题")
        val data = tp.state.input["data"] ?: tp.state.input["research_data"] ?: ""
        DebugLog.i("generate_report: topic=$topic")

        if (data.isBlank()) {
            return ToolResult(toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.COMPLETED, output = "报告生成完成。\n\n# $topic\n\n（暂无研究数据，请先执行深度研究）", endTime = System.currentTimeMillis())))
        }

        val reportPrompt = buildString {
            appendLine("请基于以下研究数据，生成一份结构化的研究报告。")
            appendLine()
            appendLine("报告主题：$topic")
            appendLine()
            appendLine("要求：")
            appendLine("1. 包含标题、摘要、关键发现、详细分析、结论和建议")
            appendLine("2. 使用 Markdown 格式输出")
            appendLine("3. 适当分段，使用标题层级")
            appendLine("4. 保持专业、简洁的文风")
            appendLine()
            appendLine("=== 研究数据 ===")
            appendLine(data.take(20000))
        }

        val report = try {
            llm.chatCompletions(
                config = config,
                system = systemPrompt,
                messages = listOf(ChatMessage(role = Role.USER, content = reportPrompt, createdAt = System.currentTimeMillis())),
            )
        } catch (e: Exception) {
            "报告生成失败：${e.message}\n\n=== 原始数据 ===\n${data.take(3000)}"
        }

        return ToolResult(toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.COMPLETED, output = report, endTime = System.currentTimeMillis())))
    }

    @Tool("generate_summary")
    @ToolDescription("生成摘要：对长文本或多个来源的内容进行摘要整理。参数中 content 为必填。")
    suspend fun executeGenerateSummary(
        tp: ToolPart,
        config: ApiConfig,
        systemPrompt: String,
        useProviderSearch: Boolean,
    ): ToolResult {
        val content = tp.state.input["content"] ?: tp.state.input["text"] ?: return emptyResult(tp, "缺少需要摘要的内容")
        DebugLog.i("generate_summary: content length=${content.length}")

        val summaryPrompt = buildString {
            appendLine("请对以下内容进行简洁的摘要。")
            appendLine()
            appendLine("要求：")
            appendLine("1. 保留关键信息和核心观点")
            appendLine("2. 长度控制在原文的 20-30%")
            appendLine("3. 使用要点式输出")
            appendLine()
            appendLine("=== 原文内容 ===")
            appendLine(content.take(15000))
        }

        val summary = try {
            llm.chatCompletions(
                config = config,
                system = systemPrompt,
                messages = listOf(ChatMessage(role = Role.USER, content = summaryPrompt, createdAt = System.currentTimeMillis())),
            )
        } catch (e: Exception) {
            "摘要生成失败：${e.message}\n\n=== 原文前500字 ===\n${content.take(500)}"
        }

        return ToolResult(toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.COMPLETED, output = summary, endTime = System.currentTimeMillis())))
    }

    override fun getTools(): Map<String, ToolBinding> {
        return mapOf(
            "generate_report" to ToolBinding(
                name = "generate_report",
                description = "生成研究报告：整理研究结果，生成结构化的报告。参数中 topic 为必填，data 为可选（包含研究数据）。",
                invoker = { tp, config, sp, ups -> executeGenerateReport(tp, config, sp, ups) },
            ),
            "generate_summary" to ToolBinding(
                name = "generate_summary",
                description = "生成摘要：对长文本或多个来源的内容进行摘要整理。参数中 content 为必填。",
                invoker = { tp, config, sp, ups -> executeGenerateSummary(tp, config, sp, ups) },
            ),
        )
    }
}