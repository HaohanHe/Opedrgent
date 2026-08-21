package top.hsyscn.opedrgent.network

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import top.hsyscn.opedrgent.R
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
import top.hsyscn.opedrgent.tools.ToolConfirmation
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
import top.hsyscn.opedrgent.tools.SatellitePassTool
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

fun emptyResult(tp: ToolPart, msg: String): ToolResult {
    return ToolResult(toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.ERROR, error = msg, endTime = System.currentTimeMillis())))
}

enum class ToolExecutionStatus {
    SUCCESS,
    PARTIAL_TIMEOUT,
    TIMEOUT,
    RATE_LIMIT,
    FATAL_ERROR
}

data class ToolExecutionResult(
    val status: ToolExecutionStatus,
    val content: String,
    val partialData: String? = null,
    val errorDetail: String? = null,
)

object ToolConfig {
    /** 单个工具调用默认超时（毫秒）。可在调用点覆盖。 */
    const val DEFAULT_TOOL_TIMEOUT_MS: Long = 30_000L

    /** 结构化错误前缀，便于 LLM/调用方识别错误类型。 */
    const val ERROR_PREFIX_TIMEOUT = "[PARTIAL_TIMEOUT]"
    const val ERROR_PREFIX_TOOL_ERROR = "[TOOL_ERROR]"
}

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
    private val requestConfirmation: suspend (ToolConfirmation) -> Boolean = { false }, // 高危操作用户确认回调（默认拒绝，必须由调用方显式接入确认流程）
) {

    private var webViewAgent: WebViewAgent? = null

    private val toolRegistry = ToolRegistry().apply {
        register(WebSearchTool(context, searcher, fetcher, llm, apiSettings))
        register(OpenBrowserTool(requestConfirmation))
        register(DeepResearchTool(context, searcher, fetcher, llm))
        register(ReadUrlTool(context, fetcher))
        register(GenerateReportTool(llm))
        register(MimoTtsTool(apiSettings))
        register(ReverseGeocodeTool(context, searcher))
        // 注册 SpeechToTextTool（通过 AsrManager 使用统一 ASR 引擎）
        if (asrManager != null) {
            register(SpeechToTextTool(context, asrManager))
        }
        // ★ Gallery 核心工具：run_js（JS Skill 沙箱执行）和 run_intent（原生 Intent）
        if (skillLoader != null) {
            register(RunJsTool(context, skillLoader, requestConfirmation))
        }
        register(RunIntentTool(context, requestConfirmation))
        // 日历直接读写工具（通过 ContentProvider 操作系统日历）
        register(RunCalendarTool(context, requestConfirmation))
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
        // ★ Ham 模式：始终注册 SatellitePassTool 实现。
        // 工具定义的暴露（是否让模型看到）由 MainViewModel.hamModeTools() 动态控制，
        // 这样用户中途开启 Ham 模式后，模型能看到工具定义且调用不会失败（避免重启 App 才生效）。
        register(SatellitePassTool(context, apiSettings))
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

        try {
            withTimeout(ToolConfig.DEFAULT_TOOL_TIMEOUT_MS) {
                executeBody(started, config, systemPrompt, useProviderSearch)
            }
        } catch (e: TimeoutCancellationException) {
            DebugLog.w("Tool '${started.tool}' timed out after ${ToolConfig.DEFAULT_TOOL_TIMEOUT_MS}ms")
            buildTimeoutResult(started)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e("ToolExecutor error for ${started.tool}: ${e.message}", e)
            buildToolErrorResult(started, e)
        }
    }

    /**
     * 并发执行多个 tool_call。每个工具在独立的 [Dispatchers.IO] 协程中运行，
     * 并受 [ToolConfig.DEFAULT_TOOL_TIMEOUT_MS] 独立超时保护。
     * 单个工具失败不会影响其他工具，失败结果以结构化错误形式返回。
     */
    suspend fun executeAll(
        toolParts: List<ToolPart>,
        config: ApiConfig,
        systemPrompt: String,
        useProviderSearch: Boolean = true,
    ): List<ToolResult> = coroutineScope {
        toolParts.map { part ->
            async(Dispatchers.IO) {
                runCatching {
                    execute(part, config, systemPrompt, useProviderSearch)
                }.getOrElse { e ->
                    if (e is CancellationException) throw e
                    buildToolErrorResult(part, e)
                }
            }
        }.awaitAll()
    }

    private suspend fun executeBody(
        started: ToolPart,
        config: ApiConfig,
        systemPrompt: String,
        useProviderSearch: Boolean,
    ): ToolResult {
        // 联网查询总开关守卫：关闭后所有搜索类工具直接返回
        if (!apiSettings.isWebSearchEnabled() && (started.tool == "web_search" || started.tool == "deep_research")) {
            DebugLog.i("ToolExecutor: ${started.tool} blocked by webSearchEnabled=false")
            return ToolResult(
                toolPart = started.copy(
                    state = started.state.copy(
                        status = ToolStateType.COMPLETED,
                        output = context.getString(R.string.error_web_search_disabled),
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

        // ★ MCP 工具路由
        if (mcpManager.isMcpTool(started.tool)) {
            val args = org.json.JSONObject()
            started.state.input.forEach { (k, v) -> args.put(k, v) }
            val mcpResult = mcpManager.callTool(started.tool, args)
            return ToolResult(
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
            val loadResult = executeLoadSkill(skillName)
            val isError = loadResult.isError
            val resultText = loadResult.message
            return ToolResult(
                toolPart = started.copy(
                    state = started.state.copy(
                        status = if (isError) ToolStateType.ERROR else ToolStateType.COMPLETED,
                        output = resultText,
                        error = if (isError) resultText else null,
                        endTime = System.currentTimeMillis(),
                    ),
                ),
            )
        }

        // ★ @Tool注解驱动的注册表路由（替代巨型when分支）
        val result = toolRegistry.invoke(started.tool, started, config, systemPrompt, effectiveUseProvider)
        if (result != null) {
            return result
        }

        // Fallback: 未注册的工具
        return unknownTool(started)
    }

    private fun buildTimeoutResult(started: ToolPart): ToolResult {
        return ToolResult(
            toolPart = started.copy(
                state = started.state.copy(
                    status = ToolStateType.PARTIAL_TIMEOUT,
                    error = "${ToolConfig.ERROR_PREFIX_TIMEOUT} " + context.getString(R.string.error_tool_execution_timeout, started.tool, ToolConfig.DEFAULT_TOOL_TIMEOUT_MS / 1000),
                    endTime = System.currentTimeMillis(),
                ),
            ),
        )
    }

    private fun buildToolErrorResult(started: ToolPart, e: Throwable): ToolResult {
        return ToolResult(
            toolPart = started.copy(
                state = started.state.copy(
                    status = ToolStateType.ERROR,
                    error = "${ToolConfig.ERROR_PREFIX_TOOL_ERROR} " + context.getString(R.string.error_tool_execution_failed, started.tool, e.message ?: context.getString(R.string.error_unknown_error)),
                    endTime = System.currentTimeMillis(),
                ),
            ),
        )
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
            description = "搜索互联网获取最新信息。默认 scan 阶段返回 30-50 条标题/摘要/URL 列表；deep 阶段深入抓取指定 URL 正文。",
            parameters = org.json.JSONObject().apply {
                put("type", "object")
                put("properties", org.json.JSONObject().apply {
                    put("query", org.json.JSONObject().apply {
                        put("type", "string")
                        put("description", "搜索关键词")
                    })
                    put("method", org.json.JSONObject().apply {
                        put("type", "string")
                        put("enum", org.json.JSONArray().apply { put("ddg"); put("webview"); put("provider_native"); put("mcp"); put("multimodal") })
                        put("description", "搜索方法，默认 ddg。特殊模式保留")
                    })
                    put("phase", org.json.JSONObject().apply {
                        put("type", "string")
                        put("enum", org.json.JSONArray().apply { put("scan"); put("deep") })
                        put("description", "搜索阶段：scan（默认）仅返回列表，deep 抓取 urls 指定网页正文")
                    })
                    put("urls", org.json.JSONObject().apply {
                        put("type", "string")
                        put("description", "deep 阶段要抓取的 URL，多个用 | 分隔")
                    })
                    put("max_fetch", org.json.JSONObject().apply {
                        put("type", "integer")
                        put("description", "deep 阶段最多抓取的 URL 数量，默认 5，最大 10")
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
    private data class LoadSkillResult(val isError: Boolean, val message: String)

    private suspend fun executeLoadSkill(skillName: String): LoadSkillResult {
        if (skillName.isBlank()) return LoadSkillResult(true, context.getString(R.string.error_no_skill_name))
        val loader = skillLoader ?: return LoadSkillResult(true, context.getString(R.string.error_skill_system_not_init))

        val skill = loader.getEnabledSkills().find { it.metadata.name == skillName }
            ?: return LoadSkillResult(true, context.getString(R.string.error_skill_not_found_or_disabled, skillName))

        val sb = StringBuilder()
        sb.appendLine("<skill_content name=\"$skillName\">")
        sb.appendLine()
        sb.appendLine(skill.instructions)
        sb.appendLine()
        if (skill.localScriptsPath != null) {
            sb.appendLine("脚本路径: ${skill.localScriptsPath}")
        }
        sb.appendLine("</skill_content>")
        return LoadSkillResult(false, sb.toString().trim())
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
            ?: context.getString(R.string.error_tool_no_output)
    }

    /**
     * 按工具名执行，返回结构化结果。供 AgentService 调用。
     *
     * 将底层 ToolResult 的异常/状态语义映射为 LLM 可理解的执行状态，
     * 使 Agent 循环能够区分暂时性失败（超时/限流/部分结果）与致命错误。
     */
    suspend fun executeToolByNameStructured(
        toolName: String,
        args: Map<String, String>,
        config: ApiConfig,
        systemPrompt: String = "",
    ): ToolExecutionResult {
        val invocationPart = ToolPart(
            tool = toolName,
            state = ToolState(
                status = ToolStateType.RUNNING,
                input = args,
                startTime = System.currentTimeMillis(),
            ),
        )

        return try {
            val result = execute(invocationPart, config, systemPrompt)
            mapToolResultToStructured(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            ToolExecutionResult(
                status = ToolExecutionStatus.FATAL_ERROR,
                content = context.getString(R.string.error_tool_security_error, e.message ?: context.getString(R.string.error_unknown_error)),
                errorDetail = e.message,
            )
        } catch (e: Exception) {
            ToolExecutionResult(
                status = ToolExecutionStatus.FATAL_ERROR,
                content = context.getString(R.string.error_tool_generic_failed, e.message ?: context.getString(R.string.error_unknown_error)),
                errorDetail = e.message,
            )
        }
    }

    private fun mapToolResultToStructured(result: ToolResult): ToolExecutionResult {
        val state = result.toolPart.state
        val output = state.output ?: ""
        val error = state.error

        return when (state.status) {
            ToolStateType.COMPLETED, ToolStateType.SOURCE_ADDED -> {
                ToolExecutionResult(
                    status = ToolExecutionStatus.SUCCESS,
                    content = output,
                )
            }
            ToolStateType.PARTIAL_TIMEOUT -> {
                val content = buildString {
                    if (output.isNotBlank()) append(output)
                    if (error != null) {
                        if (output.isNotBlank()) append("\n\n")
                        append("[部分超时: $error]")
                    }
                }.ifBlank { context.getString(R.string.error_tool_partial_timeout) }
                ToolExecutionResult(
                    status = ToolExecutionStatus.PARTIAL_TIMEOUT,
                    content = content,
                    partialData = output,
                    errorDetail = error,
                )
            }
            ToolStateType.RUNNING, ToolStateType.PENDING -> {
                ToolExecutionResult(
                    status = ToolExecutionStatus.FATAL_ERROR,
                    content = context.getString(R.string.error_tool_abnormal_end),
                    errorDetail = error,
                )
            }
            ToolStateType.ERROR -> classifyToolError(error, output)
        }
    }

    private fun classifyToolError(error: String?, output: String?): ToolExecutionResult {
        val errorText = error ?: ""
        val lower = errorText.lowercase()
        val httpCode = extractHttpCode(errorText)

        // 404 / 资源确实不存在 -> 以 SUCCESS 返回，让 LLM 知道不可用后继续
        if (httpCode == 404 || lower.contains("not found") || lower.contains("找不到")) {
            return ToolExecutionResult(
                status = ToolExecutionStatus.SUCCESS,
                content = context.getString(R.string.error_resource_unavailable),
                errorDetail = errorText,
            )
        }

        // 业务逻辑错误（工具自身返回 [ERROR] 前缀的 output 但 error 字段为空）——
        // 视为 SUCCESS 让 LLM 看到错误说明并转告用户，不触发 Guardrail FATAL_ERROR 拦截。
        if (errorText.isBlank() && output != null && output.startsWith("[ERROR]")) {
            return ToolExecutionResult(
                status = ToolExecutionStatus.SUCCESS,
                content = output,
                errorDetail = null,
            )
        }

        // 安全/权限/SSL 类错误视为致命
        if (lower.contains("securityexception") || lower.contains("security exception") ||
            lower.contains("权限") || lower.contains("permission denied") ||
            lower.contains("ssl") || lower.contains("certificate") || lower.contains("handshake")
        ) {
            return ToolExecutionResult(
                status = ToolExecutionStatus.FATAL_ERROR,
                content = context.getString(R.string.error_tool_generic_failed, errorText),
                errorDetail = errorText,
            )
        }

        val classified = try {
            ErrorClassifier.classify(Exception(errorText), httpCode, errorText)
        } catch (_: Exception) {
            null
        }

        return when (classified?.type) {
            ClassifiedErrorType.TIMEOUT,
            ClassifiedErrorType.NETWORK_ERROR,
            ClassifiedErrorType.SERVER_ERROR,
            ClassifiedErrorType.DNS_ERROR -> ToolExecutionResult(
                status = ToolExecutionStatus.TIMEOUT,
                content = context.getString(R.string.error_tool_timeout_or_network, errorText),
                errorDetail = errorText,
            )
            ClassifiedErrorType.RATE_LIMIT,
            ClassifiedErrorType.CAPTCHA,
            ClassifiedErrorType.FORBIDDEN,
            ClassifiedErrorType.AUTH_ERROR -> ToolExecutionResult(
                status = ToolExecutionStatus.RATE_LIMIT,
                content = context.getString(R.string.error_tool_rate_limited, errorText),
                errorDetail = errorText,
            )
            else -> ToolExecutionResult(
                status = ToolExecutionStatus.FATAL_ERROR,
                content = context.getString(R.string.error_tool_generic_failed, errorText),
                errorDetail = errorText,
            )
        }
    }

    private fun extractHttpCode(message: String?): Int? {
        if (message.isNullOrBlank()) return null
        val regex = Regex("""\b([1-5]\d{2})\b""")
        return regex.find(message)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** Fallback：未注册工具的错误响应。仍在 [executeBody] 末尾作为兜底使用，非废弃方法。 */
    private fun unknownTool(tp: ToolPart): ToolResult {
        DebugLog.w("Unknown tool: ${tp.tool}")
        return ToolResult(toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.ERROR, error = context.getString(R.string.error_unknown_tool, tp.tool), endTime = System.currentTimeMillis())))
    }
}
