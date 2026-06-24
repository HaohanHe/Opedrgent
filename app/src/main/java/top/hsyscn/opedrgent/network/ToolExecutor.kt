package top.hsyscn.opedrgent.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.model.ToolState
import top.hsyscn.opedrgent.model.ToolStateType
import top.hsyscn.opedrgent.settings.ApiConfig
import top.hsyscn.opedrgent.settings.ApiSettings
import top.hsyscn.opedrgent.tools.DeepResearchTool
import top.hsyscn.opedrgent.tools.GenerateReportTool
import top.hsyscn.opedrgent.tools.InsightSproutTool
import top.hsyscn.opedrgent.tools.MimoTtsTool
import top.hsyscn.opedrgent.tools.OpenBrowserTool
import top.hsyscn.opedrgent.tools.ReadUrlTool
import top.hsyscn.opedrgent.tools.ReverseGeocodeTool
import top.hsyscn.opedrgent.tools.RunIntentTool
import top.hsyscn.opedrgent.tools.RunJsTool
import top.hsyscn.opedrgent.tools.RunCalendarTool
import top.hsyscn.opedrgent.tools.SpeechToTextTool
import top.hsyscn.opedrgent.tools.ToolRegistry
import top.hsyscn.opedrgent.tools.WebSearchTool
import top.hsyscn.opedrgent.tools.TodoWriteTool
import top.hsyscn.opedrgent.tools.RecallTool
import top.hsyscn.opedrgent.tools.StepRagTool
import top.hsyscn.opedrgent.tools.StepSearchTool
import top.hsyscn.opedrgent.tools.StepMobileAgentTool
import top.hsyscn.opedrgent.tools.StepVisionTool
import top.hsyscn.opedrgent.tools.StepImageEditTool
import top.hsyscn.opedrgent.tools.StepImageGenTool
import top.hsyscn.opedrgent.tools.StepVideoSummaryTool
import top.hsyscn.opedrgent.tools.HealthTool
import top.hsyscn.opedrgent.storage.KnowledgeBase
import top.hsyscn.opedrgent.utils.DebugLog
import top.hsyscn.opedrgent.mcp.skills.SkillLoader
import top.hsyscn.opedrgent.agent.McpManager

data class ToolResult(
    val toolPart: ToolPart,
    val openUrl: String? = null,
    val openBrowserUrl: String? = null,
    val addedSources: List<String> = emptyList(),
)

class ToolExecutor(
    private val context: Context,
    private val searcher: WebSearcher,
    private val fetcher: SourceFetcher,
    private val llm: LlmClient,
    private val apiSettings: ApiSettings,
    private val asrManager: top.hsyscn.opedrgent.stt.AsrManager? = null,
    private val skillLoader: SkillLoader? = null, // Gallery Skill 系统加载器（可选，用于 run_js 工具）
    private val insightSproutEngine: top.hsyscn.opedrgent.insight.InsightSproutEngine? = null,
    private val knowledgeBase: KnowledgeBase? = null, // 知识库（用于 RAG 检索工具）
    val mcpManager: McpManager = McpManager(), // MCP 多服务器管理器
) {

    private var webViewAgent: WebViewAgent? = null

    private val toolRegistry = ToolRegistry().apply {
        register(WebSearchTool(context, searcher, fetcher, llm, apiSettings))
        register(OpenBrowserTool())
        register(DeepResearchTool(context, searcher, fetcher, llm))
        register(ReadUrlTool(context, fetcher))
        register(GenerateReportTool(llm))
        register(MimoTtsTool(apiSettings))
        register(ReverseGeocodeTool(searcher))
        // 注册 SpeechToTextTool（通过 AsrManager 使用统一 ASR 引擎）
        if (asrManager != null) {
            register(SpeechToTextTool(context, asrManager))
        }
        // ★ Gallery 核心工具：run_js（JS Skill 沙箱执行）和 run_intent（原生 Intent）
        if (skillLoader != null) {
            register(RunJsTool(context, skillLoader))
        }
        register(RunIntentTool(context))
        // 日历直接读写工具（通过 ContentProvider 操作系统日历）
        register(RunCalendarTool(context))
        if (insightSproutEngine != null) {
            register(InsightSproutTool(insightSproutEngine))
        }
        // ★ TodoWrite + Recall 工具：结构化任务跟踪 + 跨会话记忆
        register(TodoWriteTool(context))
        register(RecallTool(context))
        // ★ 阶跃星辰扩展工具集
        if (knowledgeBase != null) {
            register(StepRagTool(context, knowledgeBase))
        }
        register(StepSearchTool())
        register(StepMobileAgentTool(context))
        register(StepVisionTool(context))
        register(StepImageEditTool(context))
        register(StepImageGenTool())
        register(StepVideoSummaryTool(context, llm, apiSettings))
        // Health Connect 运动健康数据读取工具
        register(HealthTool(context))
    }

    suspend fun execute(
        toolPart: ToolPart,
        config: ApiConfig,
        systemPrompt: String,
        useProviderSearch: Boolean = true,
    ): ToolResult = withContext(Dispatchers.IO) {
        val started = toolPart.copy(
            state = toolPart.state.copy(
                status = ToolStateType.RUNNING,
                startTime = System.currentTimeMillis(),
            ),
        )
        DebugLog.i("ToolExecutor.execute: ${started.tool} with ${started.state.input}")

        // 联网查询总开关守卫：关闭后所有搜索类工具直接返回
        if (!apiSettings.isWebSearchEnabled() && (started.tool == "web_search" || started.tool == "deep_research")) {
            DebugLog.i("ToolExecutor: ${started.tool} blocked by webSearchEnabled=false")
            return@withContext ToolResult(
                toolPart = started.copy(
                    state = started.state.copy(
                        status = ToolStateType.COMPLETED,
                        output = "联网查询功能已关闭。当前仅基于本地知识和已有笔记回答问题。",
                        endTime = System.currentTimeMillis(),
                    ),
                ),
            )
        }

        // 来源选择：根据设置决定使用自有引擎还是厂商内置搜索
        val effectiveUseProvider = if (started.tool == "web_search") {
            when (apiSettings.getWebSearchSource()) {
                "own" -> true       // 自有引擎：ddg/bing/baidu/searxng/jina
                "provider" -> false // 厂商内置：走 provider_native 或 LLM 原生搜索
                else -> useProviderSearch
            }
        } else {
            useProviderSearch
        }

        try {
            // ★ MCP 工具路由
            if (mcpManager.isMcpTool(started.tool)) {
                val args = org.json.JSONObject()
                started.state.input.forEach { (k, v) -> args.put(k, v) }
                val mcpResult = mcpManager.callTool(started.tool, args)
                return@withContext ToolResult(
                    toolPart = started.copy(
                        state = started.state.copy(
                            status = if (mcpResult.isError) ToolStateType.ERROR else ToolStateType.COMPLETED,
                            output = mcpResult.content,
                            error = if (mcpResult.isError) mcpResult.content else null,
                            endTime = System.currentTimeMillis(),
                        ),
                    ),
                )
            }

            // ★ load_skill 工具：按需加载 Skill 完整内容
            if (started.tool == "load_skill") {
                val skillName = started.state.input["name"] ?: ""
                val result = executeLoadSkill(skillName)
                return@withContext ToolResult(
                    toolPart = started.copy(
                        state = started.state.copy(
                            status = if (result.startsWith("错误")) ToolStateType.ERROR else ToolStateType.COMPLETED,
                            output = result,
                            error = if (result.startsWith("错误")) result else null,
                            endTime = System.currentTimeMillis(),
                        ),
                    ),
                )
            }

            // ★ @Tool注解驱动的注册表路由（替代巨型when分支）
            val result = toolRegistry.invoke(started.tool, started, config, systemPrompt, effectiveUseProvider)
            if (result != null) {
                return@withContext result
            }

            // Fallback: 未注册的工具
            unknownTool(started)
        } catch (e: Exception) {
            DebugLog.e("ToolExecutor error for ${started.tool}: ${e.message}", e)
            ToolResult(
                toolPart = started.copy(
                    state = started.state.copy(
                        status = ToolStateType.ERROR,
                        error = e.message ?: "Tool execution failed",
                        endTime = System.currentTimeMillis(),
                    ),
                ),
            )
        }
    }

    fun destroy() {
        webViewAgent?.destroy()
        webViewAgent = null
        // Destroy all tools that hold WebViewAgent instances
        toolRegistry.getAll().filterIsInstance<WebSearchTool>().forEach { it.destroy() }
        toolRegistry.getAll().filterIsInstance<ReadUrlTool>().forEach { it.destroy() }
        toolRegistry.getAll().filterIsInstance<DeepResearchTool>().forEach { it.destroy() }
    }

    /**
     * 返回供 MultiAgentOrchestrator 使用的工具定义列表。
     * 只暴露联网查询相关的工具，不暴露 TTS/Intent 等。
     */
    fun getResearchToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "web_search",
            description = "搜索互联网获取最新信息。输入查询关键词，返回搜索结果列表（标题、摘要、URL）。",
            parameters = org.json.JSONObject().apply {
                put("type", "object")
                put("properties", org.json.JSONObject().apply {
                    put("query", org.json.JSONObject().apply {
                        put("type", "string")
                        put("description", "搜索关键词")
                    })
                })
                put("required", org.json.JSONArray().apply { put("query") })
            },
        ),
        ToolDefinition(
            name = "read_url",
            description = "读取指定URL的网页正文内容。用于深入阅读搜索结果中的网页。",
            parameters = org.json.JSONObject().apply {
                put("type", "object")
                put("properties", org.json.JSONObject().apply {
                    put("url", org.json.JSONObject().apply {
                        put("type", "string")
                        put("description", "要读取的网页URL")
                    })
                })
                put("required", org.json.JSONArray().apply { put("url") })
            },
        ),
        ToolDefinition(
            name = "generate_report",
            description = "将研究素材整理为结构化报告。输入原始素材文本，输出格式化报告。",
            parameters = org.json.JSONObject().apply {
                put("type", "object")
                put("properties", org.json.JSONObject().apply {
                    put("material", org.json.JSONObject().apply {
                        put("type", "string")
                        put("description", "待整理的原始素材文本")
                    })
                    put("title", org.json.JSONObject().apply {
                        put("type", "string")
                        put("description", "报告标题（可选）")
                    })
                })
                put("required", org.json.JSONArray().apply { put("material") })
            },
        ),
    )

    /**
     * 统一工具定义：本地注册工具 + MCP 远程工具。
     * 合并 ToolRegistry 中的本地工具和 McpManager 中的远程 MCP 工具。
     */
    suspend fun getToolDefinitions(): List<ToolDefinition> {
        val local = toolRegistry.getToolDefinitions()
        val mcp = mcpManager.refreshAllTools()
        val loadSkill = listOf(ToolDefinition(
            name = "load_skill",
            description = "按需加载技能的完整指令。仅在任务匹配某个技能描述时调用，不要预加载。",
            parameters = org.json.JSONObject().apply {
                put("type", "object")
                put("properties", org.json.JSONObject().apply {
                    put("name", org.json.JSONObject().apply {
                        put("type", "string")
                        put("description", "要加载的技能名称")
                    })
                })
                put("required", org.json.JSONArray().apply { put("name") })
            },
        ))
        return loadSkill + local + mcp
    }

    /**
     * 执行 load_skill 工具：查找并返回 Skill 的完整指令内容
     */
    private suspend fun executeLoadSkill(skillName: String): String {
        if (skillName.isBlank()) return "错误: 未指定技能名称"
        val loader = skillLoader ?: return "错误: 技能系统未初始化"

        val skill = loader.getEnabledSkills().find { it.metadata.name == skillName }
            ?: return "错误: 未找到名为 '$skillName' 的技能，或该技能已禁用"

        val sb = StringBuilder()
        sb.appendLine("<skill_content name=\"$skillName\">")
        sb.appendLine()
        sb.appendLine(skill.instructions)
        sb.appendLine()
        if (skill.localScriptsPath != null) {
            sb.appendLine("脚本路径: ${skill.localScriptsPath}")
        }
        sb.appendLine("</skill_content>")
        return sb.toString().trim()
    }

    /**
     * 按工具名执行，返回结果文本。供 MultiAgentOrchestrator 调用。
     *
     * 通过构造临时的 ToolPart 包装参数来复用 execute() 管线，
     * 这是内部调度模式，不经过用户交互流程。
     */
    suspend fun executeToolByName(
        toolName: String,
        args: Map<String, String>,
        config: ApiConfig,
        systemPrompt: String = "",
    ): String {
        val invocationPart = ToolPart(
            tool = toolName,
            state = ToolState(
                status = ToolStateType.RUNNING,
                input = args,
                startTime = System.currentTimeMillis(),
            ),
        )
        val result = execute(invocationPart, config, systemPrompt)
        return result.toolPart.state.output
            ?: result.toolPart.state.error
            ?: "工具执行完成但无输出"
    }

    private fun executeOpenBrowser(tp: ToolPart): ToolResult {
        val url = tp.state.input["url"] ?: return emptyResult(tp, "缺少 URL")
        DebugLog.i("open_browser: $url")
        return ToolResult(
            toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.COMPLETED, output = "已在浏览器中打开：$url", endTime = System.currentTimeMillis())),
            openBrowserUrl = url,
        )
    }

    private fun executeQuestion(tp: ToolPart): ToolResult {
        return ToolResult(toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.COMPLETED, output = "等待用户选择", endTime = System.currentTimeMillis())))
    }

    private fun executeReverseGeocode(tp: ToolPart): ToolResult {
        val lat = tp.state.input["lat"]?.toDoubleOrNull()
        val lon = tp.state.input["lon"]?.toDoubleOrNull()
        if (lat == null || lon == null) {
            return ToolResult(toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.ERROR, error = "缺少经纬度参数 lat, lon", endTime = System.currentTimeMillis())))
        }

        DebugLog.i("reverse_geocode: $lat, $lon")
        val result = searcher.reverseGeocode(lat, lon)
        if (result != null) {
            return ToolResult(toolPart = tp.copy(state = tp.state.copy(
                status = ToolStateType.COMPLETED,
                output = result,
                endTime = System.currentTimeMillis(),
            )))
        }

        val envGeo = top.hsyscn.opedrgent.env.EnvironmentProvider.reverseGeocode(lat, lon)
        if (envGeo != null) {
            return ToolResult(toolPart = tp.copy(state = tp.state.copy(
                status = ToolStateType.COMPLETED,
                output = envGeo.displayName,
                endTime = System.currentTimeMillis(),
            )))
        }

        return ToolResult(toolPart = tp.copy(state = tp.state.copy(
            status = ToolStateType.COMPLETED,
            output = "${lat}, ${lon}（反向地理编码失败，这是 GPS 原始坐标。此坐标大致位于中国陕西省北部区域。）",
            endTime = System.currentTimeMillis(),
        )))
    }

    private fun executeGenerate(tp: ToolPart): ToolResult {
        return ToolResult(toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.COMPLETED, output = "generate 操作由系统在工具循环完成后处理", endTime = System.currentTimeMillis())))
    }

    @Deprecated("内部辅助方法，已迁移到ToolSet")
    private fun unknownTool(tp: ToolPart): ToolResult {
        DebugLog.w("Unknown tool: ${tp.tool}")
        return ToolResult(toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.ERROR, error = "未知工具：${tp.tool}", endTime = System.currentTimeMillis())))
    }

    @Deprecated("内部辅助方法，已迁移到ToolSet")
    private fun emptyResult(tp: ToolPart, msg: String): ToolResult {
        return ToolResult(toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.ERROR, error = msg, endTime = System.currentTimeMillis())))
    }
}
