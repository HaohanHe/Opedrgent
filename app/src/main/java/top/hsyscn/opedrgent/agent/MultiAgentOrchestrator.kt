package top.hsyscn.opedrgent.agent

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.hsyscn.opedrgent.utils.DebugLog
import java.util.UUID

/**
 * 多智能体编排器（Multi-Agent Orchestrator）
 *
 * 基于业界最佳实践设计的生产级多Agent系统：
 * - Orchestrator模式：中央调度员分解任务、分配专家、整合结果
 * - ReAct循环：每个专家独立使用"思考-行动-观察"闭环
 * - 动态角色：LLM决定派什么专家，不是硬编码的固定角色
 * - 共享记忆：所有Agent共享上下文和中间结果
 *
 * 参考架构：
 *   Microsoft AutoGen (多Agent对话)
 *   CrewAI (角色扮演+任务委派)
 *   LangGraph (状态图编排)
 *   REDEREF (概率路由+信用分配)
 *   得到大脑 (小龙虾Skill系统)
 */
class MultiAgentOrchestrator(
    private val llmCall: suspend (prompt: String) -> String,
    private val maxAgents: Int = 5,
    private val maxRounds: Int = 3,
) {

    companion object {
        private const val TAG = "MultiAgentOrchestrator"
    }

    // 共享工作空间：所有Agent可见的上下文和结果
    private val workspace = MutableWorkspace()
    private val mutex = Mutex()

    /**
     * 执行多Agent协作任务
     * 这是主入口：用户请求 -> 编排 -> 多Agent协作 -> 最终结果
     */
    suspend fun execute(request: String): OrchestrationResult {
        DebugLog.i(TAG, "=== 开始多Agent协作 ===")
        DebugLog.i(TAG, "用户请求: ${request.take(100)}")

        val startTimeMs = System.currentTimeMillis()

        return try {
            // Phase 1: 任务分解与团队组建
            mutex.withLock {
                workspace.clear()
                workspace.addMessage("user", request)
            }

            val plan = planAndAssembleTeam(request)
            DebugLog.i(TAG, "Phase1完成: 组建${plan.agents.size}人团队: ${plan.agents.joinToString { it.role }}")

            if (plan.agents.isEmpty()) {
                return@execute OrchestrationResult.singleAgentResponse(plan.fallbackResponse)
            }

            // Phase 2: 多轮协作执行
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
                workspaceSnapshot = workspace.toSnapshot(),
            )
        } catch (e: Exception) {
            DebugLog.e(TAG, "协作执行异常: ${e.message}", e)
            OrchestrationResult.error(e.message ?: "未知错误")
        }
    }

    // ==================== Phase 1: 规划与组队 ====================

    /**
     * 让LLM分析任务并动态组建专家团队
     * 关键：LLM自己决定派什么专家、派几个，不硬编码
     */
    private suspend fun planAndAssembleTeam(request: String): TeamPlan {
        val planningPrompt = buildString {
            appendLine("你是一个任务规划师。请分析以下用户请求，决定是否需要组建多专家团队来完成。")
            appendLine()
            appendLine("【用户请求】")
            appendLine(request)
            appendLine()
            appendLine("【可用能力池】（你可以从中选择需要的专家）：")
            appendLine("- 思考者(Thinker): 深度分析、逻辑推理、跨领域关联、概念拆解")
            appendLine("- 编辑者(Editor): 内容结构化、表达优化、去口语化、风格适配")
            appendLine("- 验证者(Reviewer): 事实核查、逻辑检验、挑战假设、找漏洞")
            appendLine("- 创作者(Creator): 成品生成、创意发散、方案设计、内容创作")
            appendLine("- 研究员(Researcher): 信息搜集、数据查找、资料整理、背景调研")
            appendLine("- 总结者(Summarizer): 提炼精华、归纳要点、生成摘要、压缩信息")
            appendLine()
            appendLine("【规则】")
            appendLine("1. 如果这个任务一个人就能做好，不需要团队，直接回答并设置 needTeam=false")
            appendLine("2. 如果需要团队，列出需要的专家角色（从上面的能力池中选择）")
            appendLine("3. 每个专家说明：为什么需要他/她要做什么")
            appendLine("4. 最多选${maxAgents}个专家")
            appendLine("5. 专家之间有依赖关系的说明执行顺序")
            appendLine()
            appendLine("请用JSON格式回复：")
            appendLine("{")
            appendLine("  \"needTeam\": true/false,")
            appendLine("  \"reason\": \"为什么需要/不需要团队\",")
            appendLine("  \"agents\": [")
            appendLine("    { \"role\": \"角色名\", \"name\": \"显示名称\", \"goal\": \"具体目标\", \"dependsOn\": [\"依赖的其他角色\"] }")
            appendLine("  ],")
            appendLine("  \"fallbackResponse\": \"如果不需要团队的直接回答\"")
            appendLine("}")
        }

        val response = llmCall(planningPrompt)
        val parsed = parsePlanningJson(response)

        return TeamPlan(
            originalRequest = request,
            agents = parsed.agents.take(maxAgents),
            fallbackResponse = parsed.fallbackResponse,
        )
    }

    // ==================== Phase 2: 协作执行循环 ====================

    /**
     * 执行多轮协作循环
     * 每轮：每个Agent观察工作区 -> 思考 -> 行动 -> 更新工作区
     */
    private suspend fun executeCollaborationLoop(plan: TeamPlan): ExecutionResult {
        val agentResults = mutableListOf<AgentRoundResult>()
        var round = 0

        while (round < maxRounds) {
            round++
            DebugLog.d(TAG, "--- 第${round}轮协作 ---")

            var anyAgentActed = false

            for (agent in plan.agents) {
                // 检查前置依赖是否完成
                if (!areDependenciesMet(agent, agentResults)) {
                    DebugLog.d(TAG, "${agent.role}: 依赖未满足，跳过本轮")
                    continue
                }

                // Agent执行ReAct循环
                val result = executeSingleAgentRound(agent, round, workspace.toContext())
                
                if (result.tookAction) {
                    anyAgentActed = true
                    mutex.withLock {
                        workspace.addMessage(agent.role, result.output)
                        workspace.addArtifact(agent.role, result.artifact)
                    }
                    agentResults.add(result)
                } else {
                    DebugLog.d(TAG, "${agent.role}: 本轮无需行动")
                }
            }

            // 终止条件：所有Agent都认为完成了
            if (!anyAgentActed || round >= maxRounds) break
        }

        return ExecutionResult(agentResults = agentResults.toList(), totalRounds = round)
    }

    /**
     * 单个Agent的一轮ReAct执行
     * 观察 -> 思考 -> 行动 -> 输出
     */
    private suspend fun executeSingleAgentRound(
        agent: AgentDefinition,
        round: Int,
        context: String,
    ): AgentRoundResult {
        val prompt = buildString {
            appendLine("你是${agent.name}(${agent.role})。你的目标是：${agent.goal}")
            appendLine()
            appendLine("【当前任务】")
            appendLine(workspace.getUserMessage())
            appendLine()
            if (context.isNotEmpty()) {
                appendLine("【已有工作成果（其他Agent的输出）】")
                appendLine(context)
                appendLine()
            }
            appendLine("【你的职责】")
            appendLine("基于以上信息，完成你的专业工作。只输出你的专业产出，不要重复别人的工作。")
            appendLine()
            appendLine("【重要】")
            appendLine("- 如果你认为你的部分已经完成或没有更多可贡献的内容，回复 [DONE]")
            appendLine("- 否则直接输出你的工作成果")
        }

        val output = llmCall(prompt).trim()
        
        val isDone = output.contains("[DONE]") || output.length < 20
        val cleanOutput = output.replace("\\[DONE\\]".toRegex(), "").trim()

        return AgentRoundResult(
            agentName = agent.role,
            round = round,
            output = cleanOutput,
            artifact = extractArtifact(cleanOutput),
            tookAction = !isDone && cleanOutput.isNotEmpty(),
        )
    }

    // ==================== Phase 3: 结果整合 ====================

    /**
     * 整合所有Agent的工作成果为最终答案
     */
    private suspend fun synthesizeFinalAnswer(executionResult: ExecutionResult): String {
        if (executionResult.agentResults.isEmpty()) {
            return workspace.getUserMessage() + "\n\n（无法获取有效结果）"
        }

        if (executionResult.agentResults.size == 1) {
            return executionResult.agentResults.first().output
        }

        val synthesisPrompt = buildString {
            appendLine("你是最终整合者。以下是多个专家协作完成的成果，请你整合为一个清晰、完整的最终答案。")
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
            appendLine("3. 用自然流畅的语言组织，不要机械拼接")
            appendLine("4. 直接输出最终答案，不要解释整合过程")
        }

        return llmCall(synthesisPrompt).trim()
    }

    // ==================== 内部工具方法 ====================

    private fun areDependenciesMet(agent: AgentDefinition, results: List<AgentRoundResult>): Boolean {
        if (agent.dependsOn.isEmpty()) return true
        val completedRoles = results.map { it.agentName }.toSet()
        return agent.dependsOn.all { it in completedRoles }
    }

    private fun parsePlanningJson(response: String): ParsedPlan {
        // 尝试提取JSON
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
        
        // 回退：单Agent处理
        return ParsedPlan(
            needTeam = false,
            agents = emptyList(),
            fallbackResponse = response,
        )
    }

    private fun extractArtifact(output: String): String? {
        // 提取结构化产物（如果有标记的话）
        val artifactMatch = Regex("""\[ARTIFACT:\s*(.+?)\s*\]""").find(output)
        return artifactMatch?.groupValues?.get(1)
    }

    // ==================== 数据类 ====================

    data class TeamPlan(
        val originalRequest: String,
        val agents: List<AgentDefinition>,
        val fallbackResponse: String,
    )

    data class AgentDefinition(
        val role: String,       // 内部标识: Thinker, Editor, Reviewer...
        val name: String,       // 显示名: 深度分析师
        val goal: String,       // 具体目标
        val dependsOn: List<String> = emptyList(), // 前置依赖的角色列表
    )

    data class ExecutionResult(
        val agentResults: List<AgentRoundResult>,
        val totalRounds: Int,
    )

    data class AgentRoundResult(
        val agentName: String,
        val round: Int,
        val output: String,
        val artifact: String?,
        val tookAction: Boolean,
    )

    data class OrchestrationResult(
        val success: Boolean,
        val answer: String = "",
        val agentsUsed: List<String> = emptyList(),
        val rounds: Int = 0,
        val processingTimeMs: Long = 0,
        val error: String? = null,
        val workspaceSnapshot: WorkspaceSnapshot? = null,
    ) {
        companion object {
            fun success(answer: String, agentsUsed: List<String>, rounds: Int, processingTimeMs: Long, workspaceSnapshot: WorkspaceSnapshot?) =
                OrchestrationResult(success = true, answer = answer, agentsUsed = agentsUsed, rounds = rounds, processingTimeMs = processingTimeMs, workspaceSnapshot = workspaceSnapshot)
            
            fun singleAgentResponse(answer: String) =
                OrchestrationResult(success = true, answer = answer, agentsUsed = listOf("single"), rounds = 1)
            
            fun error(msg: String) = OrchestrationResult(success = false, error = msg)
        }
    }

    data class WorkspaceSnapshot(
        val messages: List<WorkspaceMessage>,
        val artifacts: List<WorkspaceArtifact>,
    )

    data class WorkspaceMessage(val role: String, val content: String)
    data class WorkspaceArtifact(val source: String, val content: String)

    /**
     * 可变工作区 - 所有Agent共享的上下文空间
     */
    inner class MutableWorkspace {
        private val messages = mutableListOf<WorkspaceMessage>()
        private val artifacts = mutableListOf<WorkspaceArtifact>()

        fun clear() {
            messages.clear()
            artifacts.clear()
        }

        fun addMessage(role: String, content: String) {
            messages.add(WorkspaceMessage(role, content))
        }

        fun addArtifact(source: String, content: String?) {
            if (content != null) {
                artifacts.add(WorkspaceArtifact(source, content))
            }
        }

        fun getUserMessage(): String {
            return messages.firstOrNull { it.role == "user" }?.content ?: ""
        }

        fun toContext(): String {
            val sb = StringBuilder()
            for (msg in messages.filter { it.role != "user" }) {
                sb.append("[${msg.role}] ${msg.content.take(500)}\n\n")
            }
            for (art in artifacts) {
                sb.append("[${art.source}的产物] ${art.content.take(300)}\n\n")
            }
            return sb.toString().trim()
        }

        fun toSnapshot(): WorkspaceSnapshot = WorkspaceSnapshot(messages.toList(), artifacts.toList())
    }

    /**
     * 简易JSON解析器（避免引入额外依赖）
     */
    internal object SimpleJsonParser {
        fun parsePlan(json: String): ParsedPlan {
            val needTeam = extractBool(json, "needTeam") ?: true
            val reason = extractString(json, "reason") ?: ""
            val fallback = extractString(json, "fallbackResponse") ?: ""
            
            val agents = extractArray(json, "agents").mapNotNull { obj ->
                val role = extractString(obj, "role") ?: return@mapNotNull null
                val name = extractString(obj, "name") ?: role
                val goal = extractString(obj, "goal") ?: ""
                val dependsOn = extractArray(obj, "dependsOn").map { 
                    extractString(it, "") ?: "" 
                }.filter { it.isNotEmpty() }
                
                AgentDefinition(role = role, name = name, goal = goal, dependsOn = dependsOn)
            }

            return ParsedPlan(needTeam = needTeam, agents = agents, fallbackResponse = fallback.ifEmpty { reason })
        }

        private fun extractBool(json: String, key: String): Boolean? {
            val pattern = Regex("\"$key\"\\s*:\\s*(true|false)")
            return pattern.find(json)?.groupValues?.get(1)?.toBooleanStrictOrNull()
        }

        private fun extractString(json: String, key: String): String? {
            val pattern = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"")
            return pattern.find(json)?.groupValues?.get(1)
        }

        private fun extractArray(json: String, key: String): List<String> {
            val results = mutableListOf<String>()
            val pattern = Regex("\"$key\"\\s*:\\s*\\[")
            val match = pattern.find(json) ?: return emptyList()
            val arrayStart = match.range.first
            
            val start = json.indexOf('[', arrayStart)
            if (start < 0) return emptyList()
            
            var depth = 0
            var current = StringBuilder()
            var inString = false
            
            for (i in start until json.length) {
                val c = json[i]
                when {
                    c == '"' && (i == 0 || json[i-1] != '\\') -> inString = !inString
                    !inString && c == '[' -> depth++
                    !inString && c == ']' -> {
                        depth--
                        if (depth == 0) {
                            if (current.isNotBlank()) results.add(current.toString().trim())
                            break
                        }
                    }
                    !inString && c == ',' && depth == 1 -> {
                        if (current.isNotBlank()) results.add(current.toString().trim())
                        current = StringBuilder()
                    }
                    else -> current.append(c)
                }
            }
            return results
        }
    }

    internal data class ParsedPlan(
        val needTeam: Boolean,
        val agents: List<AgentDefinition>,
        val fallbackResponse: String,
    )
}
