package top.hsyscn.opedrgent.agent

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import top.hsyscn.opedrgent.model.ChatMessage
import top.hsyscn.opedrgent.model.Role
import top.hsyscn.opedrgent.network.LlmClient
import top.hsyscn.opedrgent.network.ToolDefinition
import top.hsyscn.opedrgent.network.ToolExecutor
import top.hsyscn.opedrgent.settings.ApiConfig
import top.hsyscn.opedrgent.utils.DebugLog
import top.hsyscn.opedrgent.utils.ToolCallGuardrail

/**
 * 多智能体编排器（Multi-Agent Orchestrator）
 *
 * Orchestrator模式：中央调度员分解任务、分配专家、整合结果
 * ReAct循环：每个专家独立使用"思考-行动-观察"闭环，可调用联网搜索等工具
 * 动态角色：LLM决定派什么专家，不硬编码
 * 共享记忆：所有Agent共享上下文和中间结果
 */
class MultiAgentOrchestrator(
    private val llmClient: LlmClient,
    private val apiConfig: ApiConfig,
    private val toolExecutor: ToolExecutor,
    private val maxAgents: Int = 3,
    private val maxRounds: Int = 3,
    private val maxToolRoundsPerAgent: Int = 5,
) {

    companion object {
        private const val TAG = "MultiAgentOrchestrator"
    }

    private val workspace = MutableWorkspace()
    private val mutex = Mutex()
    private val toolDefinitions = toolExecutor.getResearchToolDefinitions()

    /**
     * 执行多Agent协作任务
     */
    suspend fun execute(request: String): OrchestrationResult {
        DebugLog.i(TAG, "=== 开始多Agent协作 ===")
        DebugLog.i(TAG, "用户请求: ${request.take(100)}")

        val startTimeMs = System.currentTimeMillis()

        return try {
            mutex.withLock {
                workspace.clear()
                workspace.addMessage("user", request)
            }

            // Phase 1: 任务分解与团队组建
            val plan = planAndAssembleTeam(request)
            DebugLog.i(TAG, "Phase1完成: 组建${plan.agents.size}人团队: ${plan.agents.joinToString { it.role }}")

            if (plan.agents.isEmpty()) {
                return@execute OrchestrationResult.singleAgentResponse(plan.fallbackResponse)
            }

            // Phase 2: 多轮协作执行（每个Agent可调用工具）
            val executionResult = executeCollaborationLoop(plan)

            // Phase 3: 结果整合
            val finalAnswer = synthesizeFinalAnswer(executionResult)

            val processingTimeMs = System.currentTimeMillis() - startTimeMs
            DebugLog.i(TAG, "=== 协作完成 耗时=${processingTimeMs}ms Agent数=${plan.agents.size} 轮次=${executionResult.totalRounds} ===")

            OrchestrationResult.success(
                answer = finalAnswer,
                agentsUsed = executionResult.agentResults.map { it.agentName },
                rounds = executionResult.totalRounds,
                processingTimeMs = processingTimeMs,
            )
        } catch (e: Exception) {
            DebugLog.e(TAG, "协作执行异常: ${e.message}", e)
            OrchestrationResult.error(e.message ?: "未知错误")
        }
    }

    // ==================== Phase 1: 规划与组队 ====================

    private suspend fun planAndAssembleTeam(request: String): TeamPlan {
        val planningPrompt = buildString {
            appendLine("你是一个任务规划师。请分析以下用户请求，决定是否需要组建多专家团队来完成。")
            appendLine()
            appendLine("【用户请求】")
            appendLine(request)
            appendLine()
            appendLine("【可用能力池】（你可以从中选择需要的专家）：")
            appendLine("- 研究员(Researcher): 信息搜集、联网搜索、数据查找、资料整理")
            appendLine("- 分析师(Analyst): 深度分析、逻辑推理、跨领域关联、数据解读")
            appendLine("- 编辑者(Editor): 内容结构化、表达优化、报告撰写")
            appendLine()
            appendLine("【规则】")
            appendLine("1. 如果这个任务一个人就能做好，不需要团队，直接回答并设置 needTeam=false")
            appendLine("2. 如果需要团队，列出需要的专家角色（从上面的能力池中选择）")
            appendLine("3. 每个专家说明：为什么需要他/她要做什么")
            appendLine("4. 最多选${maxAgents}个专家")
            appendLine()
            appendLine("请用JSON格式回复：")
            appendLine("""{"needTeam":true/false,"reason":"原因","agents":[{"role":"角色名","name":"显示名称","goal":"具体目标"}],"fallbackResponse":"不需要团队时的直接回答"}""")
        }

        val result = llmClient.chatCompletionsWithTools(
            config = apiConfig,
            system = "你是一个任务规划师，擅长分析任务并组建专家团队。",
            messages = listOf(ChatMessage(role = Role.USER, content = planningPrompt, createdAt = System.currentTimeMillis())),
        )

        val response = result.content
        val parsed = parsePlanningJson(response)

        return TeamPlan(
            originalRequest = request,
            agents = parsed.agents.take(maxAgents),
            fallbackResponse = parsed.fallbackResponse,
        )
    }

    // ==================== Phase 2: 协作执行循环 ====================

    private suspend fun executeCollaborationLoop(plan: TeamPlan): ExecutionResult {
        val agentResults = mutableListOf<AgentRoundResult>()
        var round = 0

        while (round < maxRounds) {
            round++
            DebugLog.d(TAG, "--- 第${round}轮协作 ---")

            var anyAgentActed = false

            for (agent in plan.agents) {
                if (!areDependenciesMet(agent, agentResults)) {
                    DebugLog.d(TAG, "${agent.role}: 依赖未满足，跳过本轮")
                    continue
                }

                val result = executeAgentWithTools(agent, round, workspace.toContext())

                if (result.tookAction) {
                    anyAgentActed = true
                    mutex.withLock {
                        workspace.addMessage(agent.role, result.output)
                    }
                    agentResults.add(result)
                } else {
                    DebugLog.d(TAG, "${agent.role}: 本轮无需行动")
                }
            }

            if (!anyAgentActed || round >= maxRounds) break
        }

        return ExecutionResult(agentResults = agentResults.toList(), totalRounds = round)
    }

    /**
     * 单Agent执行，支持工具调用循环：
     * LLM思考 -> 如果需要搜索/抓取 -> 执行工具 -> 结果反馈给LLM -> 重复
     */
    private suspend fun executeAgentWithTools(
        agent: AgentDefinition,
        round: Int,
        context: String,
    ): AgentRoundResult {
        val systemPrompt = buildString {
            appendLine("你是${agent.name}(${agent.role})。你的目标是：${agent.goal}")
            appendLine()
            appendLine("你可以使用以下工具来完成任务：")
            appendLine("- web_search: 搜索互联网获取最新信息")
            appendLine("- read_url: 读取指定URL的网页正文")
            appendLine("- generate_report: 将素材整理为结构化报告")
            appendLine()
            appendLine("如果你认为你的部分已经完成或没有更多可贡献的内容，直接输出最终成果即可。")
        }

        val messages = mutableListOf<ChatMessage>()
        val userMsg = buildString {
            appendLine("【当前任务】")
            appendLine(workspace.getUserMessage())
            appendLine()
            if (context.isNotEmpty()) {
                appendLine("【已有工作成果（其他Agent的输出）】")
                appendLine(context)
                appendLine()
            }
            appendLine("【你的职责】")
            appendLine("基于以上信息，完成你的专业工作。如果需要搜索信息，请使用web_search工具。")
        }
        messages.add(ChatMessage(role = Role.USER, content = userMsg, createdAt = System.currentTimeMillis()))

        // 工具调用循环：LLM -> 工具 -> 反馈 -> 重复
        var toolRound = 0
        var finalContent = ""
        val guardrail = ToolCallGuardrail()

        while (toolRound < maxToolRoundsPerAgent) {
            toolRound++
            val result = llmClient.chatCompletionsWithTools(
                config = apiConfig,
                system = systemPrompt,
                messages = messages,
                tools = toolDefinitions,
            )

            // 如果没有工具调用，说明Agent完成了
            if (result.toolCalls.isEmpty()) {
                finalContent = result.content
                break
            }

            // 有工具调用：执行工具并将结果反馈给LLM
            // 先把assistant的回复（含tool_calls）加入消息历史
            val apiCallsJson = org.json.JSONArray().apply {
                result.toolCalls.forEach { tc ->
                    put(JSONObject().apply {
                        put("id", tc.id)
                        put("type", "function")
                        put("function", JSONObject().apply {
                            put("name", tc.name)
                            put("arguments", tc.arguments)
                        })
                    })
                }
            }.toString()

            messages.add(ChatMessage(
                role = Role.ASSISTANT,
                content = result.content,
                createdAt = System.currentTimeMillis(),
                apiToolCallsJson = apiCallsJson,
            ))

            // 执行每个工具调用
            var guardrailBlocked = false
            for (tc in result.toolCalls) {
                DebugLog.d(TAG, "${agent.role} 调用工具: ${tc.name}")
                val args = try {
                    val json = JSONObject(tc.arguments)
                    json.keys().asSequence().associateWith { json.optString(it, "") }
                } catch (_: Exception) {
                    emptyMap()
                }

                val toolResult = try {
                    toolExecutor.executeToolByName(tc.name, args, apiConfig)
                } catch (e: Exception) {
                    "工具执行失败: ${e.message}"
                }

                // Record result with guardrail and check for halt/block
                val action = guardrail.record(tc.name, tc.arguments, toolResult, !toolResult.startsWith("工具执行失败"))
                when (action) {
                    ToolCallGuardrail.Action.HALT, ToolCallGuardrail.Action.BLOCK -> {
                        DebugLog.w(TAG, "Guardrail $action for ${tc.name}: tool loop halted")
                        guardrailBlocked = true
                    }
                    ToolCallGuardrail.Action.WARN -> {
                        DebugLog.w(TAG, "Guardrail WARN for ${tc.name}")
                    }
                    ToolCallGuardrail.Action.ALLOW -> { /* continue */ }
                }

                // 将工具结果加入消息历史
                messages.add(ChatMessage(
                    role = Role.USER,
                    content = toolResult,
                    createdAt = System.currentTimeMillis(),
                    toolCallId = tc.id,
                ))
                if (guardrailBlocked) break
            }
            if (guardrailBlocked) {
                DebugLog.w(TAG, "${agent.role} tool loop halted by guardrail")
                finalContent = messages.lastOrNull { it.role == Role.ASSISTANT }?.content ?: ""
                break
            }
        }

        val isDone = finalContent.contains("[DONE]")
        val cleanOutput = finalContent.replace("[DONE]", "").trim()

        return AgentRoundResult(
            agentName = agent.role,
            round = round,
            output = cleanOutput,
            tookAction = !isDone && cleanOutput.isNotEmpty(),
        )
    }

    // ==================== Phase 3: 结果整合 ====================

    private suspend fun synthesizeFinalAnswer(executionResult: ExecutionResult): String {
        if (executionResult.agentResults.isEmpty()) {
            return workspace.getUserMessage() + "\n\n（无法获取有效结果）"
        }

        if (executionResult.agentResults.size == 1) {
            return executionResult.agentResults.first().output
        }

        val synthesisPrompt = buildString {
            appendLine("你是最终整合者。以下是多个专家协作完成的成果，请整合为一个清晰、完整的最终答案。")
            appendLine()
            appendLine("【原始问题】")
            appendLine(workspace.getUserMessage())
            appendLine()
            appendLine("【各专家成果】")
            for (result in executionResult.agentResults) {
                appendLine("--- ${result.agentName} ---")
                appendLine(result.output)
                appendLine()
            }
            appendLine()
            appendLine("【整合要求】")
            appendLine("1. 保持各专家的核心观点和结论")
            appendLine("2. 去除重复内容")
            appendLine("3. 用自然流畅的语言组织")
            appendLine("4. 直接输出最终答案")
        }

        val result = llmClient.chatCompletionsWithTools(
            config = apiConfig,
            system = "你是一个内容整合专家。",
            messages = listOf(ChatMessage(role = Role.USER, content = synthesisPrompt, createdAt = System.currentTimeMillis())),
        )
        return result.content.trim()
    }

    // ==================== 内部工具方法 ====================

    private fun areDependenciesMet(agent: AgentDefinition, results: List<AgentRoundResult>): Boolean {
        if (agent.dependsOn.isEmpty()) return true
        val completedRoles = results.map { it.agentName }.toSet()
        return agent.dependsOn.all { it in completedRoles }
    }

    private fun parsePlanningJson(response: String): ParsedPlan {
        val jsonStart = response.indexOf('{')
        val jsonEnd = response.lastIndexOf('}')

        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            val jsonStr = response.substring(jsonStart, jsonEnd + 1)
            try {
                return SimpleJsonParser.parsePlan(jsonStr)
            } catch (e: Exception) {
                DebugLog.w(TAG, "JSON解析失败，回退到单Agent: ${e.message}")
            }
        }

        return ParsedPlan(
            needTeam = false,
            agents = emptyList(),
            fallbackResponse = response,
        )
    }

    // ==================== 数据类 ====================

    data class TeamPlan(
        val originalRequest: String,
        val agents: List<AgentDefinition>,
        val fallbackResponse: String,
    )

    data class AgentDefinition(
        val role: String,
        val name: String,
        val goal: String,
        val dependsOn: List<String> = emptyList(),
    )

    data class ExecutionResult(
        val agentResults: List<AgentRoundResult>,
        val totalRounds: Int,
    )

    data class AgentRoundResult(
        val agentName: String,
        val round: Int,
        val output: String,
        val tookAction: Boolean,
    )

    data class OrchestrationResult(
        val success: Boolean,
        val answer: String = "",
        val agentsUsed: List<String> = emptyList(),
        val rounds: Int = 0,
        val processingTimeMs: Long = 0,
        val error: String? = null,
    ) {
        companion object {
            fun success(answer: String, agentsUsed: List<String>, rounds: Int, processingTimeMs: Long) =
                OrchestrationResult(success = true, answer = answer, agentsUsed = agentsUsed, rounds = rounds, processingTimeMs = processingTimeMs)

            fun singleAgentResponse(answer: String) =
                OrchestrationResult(success = true, answer = answer, agentsUsed = listOf("single"), rounds = 1)

            fun error(msg: String) = OrchestrationResult(success = false, error = msg)
        }
    }

    /**
     * 可变工作区 - 所有Agent共享的上下文空间
     */
    inner class MutableWorkspace {
        private val messages = mutableListOf<Pair<String, String>>()

        fun clear() { messages.clear() }
        fun addMessage(role: String, content: String) { messages.add(role to content) }
        fun getUserMessage(): String = messages.firstOrNull { it.first == "user" }?.second ?: ""
        fun toContext(): String = messages.filter { it.first != "user" }
            .joinToString("\n\n") { "[${it.first}] ${it.second.take(500)}" }
    }

    internal object SimpleJsonParser {
        fun parsePlan(json: String): ParsedPlan {
            val root = JSONObject(json)
            val needTeam = root.optBoolean("needTeam", false)
            val fallback = root.optString("fallbackResponse", "")

            val agents = mutableListOf<AgentDefinition>()
            if (root.has("agents")) {
                val arr = root.getJSONArray("agents")
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    agents.add(AgentDefinition(
                        role = obj.optString("role", ""),
                        name = obj.optString("name", obj.optString("role", "")),
                        goal = obj.optString("goal", ""),
                    ))
                }
            }

            return ParsedPlan(needTeam = needTeam, agents = agents, fallbackResponse = fallback)
        }
    }

    internal data class ParsedPlan(
        val needTeam: Boolean,
        val agents: List<AgentDefinition>,
        val fallbackResponse: String,
    )
}
