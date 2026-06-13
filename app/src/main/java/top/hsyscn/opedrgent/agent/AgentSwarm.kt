package top.hsyscn.opedrgent.agent

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import top.hsyscn.opedrgent.model.ChatMessage
import top.hsyscn.opedrgent.model.Role
import top.hsyscn.opedrgent.network.LlmClient
import top.hsyscn.opedrgent.network.ToolExecutor
import top.hsyscn.opedrgent.settings.ApiConfig
import top.hsyscn.opedrgent.utils.DebugLog

/**
 * AgentSwarm — LLM 完全自主调度的多 Agent 系统
 *
 * 核心原则：
 * 1. LLM 是大脑 — 自主决定创建多少个 Agent、什么角色、串行还是并行
 * 2. 串行可传递上下文 — 下一个 Agent 能看到上一个的完整输出（链式思考）
 * 3. 并行可同时执行 — 无依赖的 Agent 同时跑，节省时间
 * 4. 每个 Agent 有独立 system prompt，LLM 定义其人格和任务
 * 5. 每个 Agent 可调用工具（搜索、抓网页等）
 */
class AgentSwarm(
    private val llmClient: LlmClient,
    private val toolExecutor: ToolExecutor,
    private val maxAgents: Int = 8,
) {
    companion object {
        private const val TAG = "AgentSwarm"
    }

    /**
     * 执行多 Agent 协作。返回最终整合结果。
     * 如果 LLM 判断不需要多 Agent，直接返回单 Agent 响应。
     */
    suspend fun execute(
        request: String,
        context: String = "",
        apiConfig: ApiConfig,
        onProgress: (String) -> Unit = {},
    ): SwarmResult {
        val startTimeMs = System.currentTimeMillis()

        try {
            // Phase 1: LLM 规划
            onProgress("正在分析任务...")
            val plan = planSwarm(request, context, apiConfig)
            DebugLog.i(TAG, "规划完成: needSwarm=${plan.needSwarm}, agents=${plan.agents.size}, mode=${plan.executionMode}")

            if (!plan.needSwarm || plan.agents.isEmpty()) {
                return SwarmResult(
                    success = true,
                    finalAnswer = plan.directAnswer,
                    agentOutputs = emptyList(),
                    processingTimeMs = System.currentTimeMillis() - startTimeMs,
                )
            }

            // Phase 2: 执行 Agent 集群
            onProgress("正在执行 ${plan.agents.size} 个 Agent...")
            val agentOutputs = when (plan.executionMode) {
                ExecutionMode.PARALLEL -> executeParallel(plan.agents, apiConfig, onProgress)
                ExecutionMode.SERIAL -> executeSerial(plan.agents, apiConfig, onProgress)
            }

            // Phase 3: 整合结果
            onProgress("正在整合结果...")
            val finalAnswer = synthesize(request, agentOutputs, apiConfig)

            return SwarmResult(
                success = true,
                finalAnswer = finalAnswer,
                agentOutputs = agentOutputs,
                processingTimeMs = System.currentTimeMillis() - startTimeMs,
            )
        } catch (e: Exception) {
            DebugLog.e(TAG, "AgentSwarm 异常: ${e.message}", e)
            return SwarmResult(
                success = false,
                finalAnswer = "多Agent执行失败: ${e.message}",
                agentOutputs = emptyList(),
                processingTimeMs = System.currentTimeMillis() - startTimeMs,
            )
        }
    }

    // ==================== Phase 1: LLM 规划 ====================

    private suspend fun planSwarm(request: String, context: String, apiConfig: ApiConfig): ExecutionPlan {
        val ctxBlock = if (context.isNotBlank()) "\n\n【上下文】\n${context.take(3000)}" else ""
        val toolDesc = buildToolDescription()

        val prompt = """你是一个任务调度大脑。分析用户请求，决定如何分配子 Agent。

【用户请求】
$request$ctxBlock

【每个 Agent 可使用的工具】
$toolDesc

【决策规则】
1. 简单问题（问答、闲聊、简单计算）→ needSwarm=false, 直接回答
2. 需要多角度分析或多个步骤 → needSwarm=true
3. 串行(serial): 后续 Agent 需要前面 Agent 的输出作为输入（链式加工）
4. 并行(parallel): 各 Agent 独立工作，互不依赖
5. 你可以定义最多 $maxAgents 个 Agent
6. 每个 Agent 需要明确的 system_prompt（定义人格和任务）和 instruction（具体指令）

用JSON回复：
{
  "needSwarm": true/false,
  "directAnswer": "needSwarm=false时的直接回答",
  "executionMode": "serial" 或 "parallel",
  "agents": [
    {
      "name": "Agent名称",
      "systemPrompt": "你是一个...的人，你的任务是...",
      "instruction": "具体要做什么",
      "dependsOn": []
    }
  ]
}"""

        val result = llmClient.chatCompletionsWithTools(
            config = apiConfig,
            system = "你是任务调度大脑，擅长分析任务并分配子Agent。只输出JSON，不要解释。",
            messages = listOf(ChatMessage(role = Role.USER, content = prompt, createdAt = System.currentTimeMillis())),
        )

        return parsePlan(result.content, request)
    }

    // ==================== Phase 2: 执行 ====================

    /**
     * 并行执行所有 Agent（无依赖关系）
     */
    private suspend fun executeParallel(
        agents: List<AgentDef>,
        apiConfig: ApiConfig,
        onProgress: (String) -> Unit,
    ): List<AgentOutput> = coroutineScope {
        agents.map { agent ->
            async {
                onProgress("Agent [${agent.name}] 执行中...")
                val output = runSingleAgent(agent, emptyList(), apiConfig)
                DebugLog.i(TAG, "Agent [${agent.name}] 完成, output=${output.content.take(100)}")
                output
            }
        }.awaitAll()
    }

    /**
     * 串行执行，每个 Agent 能看到前面所有 Agent 的输出（链式传递）
     */
    private suspend fun executeSerial(
        agents: List<AgentDef>,
        apiConfig: ApiConfig,
        onProgress: (String) -> Unit,
    ): List<AgentOutput> {
        val results = mutableListOf<AgentOutput>()

        for (agent in agents) {
            // 收集依赖的 Agent 输出
            val depOutputs = if (agent.dependsOn.isNotEmpty()) {
                results.filter { it.agentName in agent.dependsOn }
            } else {
                results.toList() // 串行模式下默认传递所有前面的结果
            }

            onProgress("Agent [${agent.name}] 执行中（串行第${results.size + 1}/${agents.size}步）...")
            val output = runSingleAgent(agent, depOutputs, apiConfig)
            results.add(output)
            DebugLog.i(TAG, "Agent [${agent.name}] 完成, output=${output.content.take(100)}")
        }

        return results
    }

    /**
     * 执行单个 Agent：发送 LLM 请求，支持工具调用循环
     */
    private suspend fun runSingleAgent(
        agent: AgentDef,
        previousOutputs: List<AgentOutput>,
        apiConfig: ApiConfig,
    ): AgentOutput {
        val messages = mutableListOf<ChatMessage>()

        // 构建输入：instruction + 前面 Agent 的输出
        val inputBuilder = StringBuilder()
        inputBuilder.appendLine(agent.instruction)

        if (previousOutputs.isNotEmpty()) {
            inputBuilder.appendLine()
            inputBuilder.appendLine("【前面 Agent 的工作成果】")
            for (prev in previousOutputs) {
                inputBuilder.appendLine("── ${prev.agentName} 的输出 ──")
                inputBuilder.appendLine(prev.content.take(3000))
                inputBuilder.appendLine()
            }
        }

        messages.add(ChatMessage(
            role = Role.USER,
            content = inputBuilder.toString(),
            createdAt = System.currentTimeMillis(),
        ))

        // 工具调用循环（最多 5 轮）
        val toolDefs = toolExecutor.getResearchToolDefinitions()
        val collectedOutput = StringBuilder()
        var rounds = 0

        while (rounds < 5) {
            rounds++
            val result = llmClient.chatCompletionsWithTools(
                config = apiConfig,
                system = agent.systemPrompt,
                messages = messages,
                tools = toolDefs,
            )

            if (result.toolCalls.isEmpty()) {
                // 没有工具调用，Agent 输出最终结果
                collectedOutput.append(result.content)
                break
            }

            // 有工具调用：执行工具，然后继续
            collectedOutput.append(result.content)

            // 把 assistant 消息加入对话
            messages.add(ChatMessage(
                role = Role.ASSISTANT,
                content = result.content,
                createdAt = System.currentTimeMillis(),
            ))

            // 执行每个工具调用
            for (tc in result.toolCalls) {
                DebugLog.d(TAG, "Agent [${agent.name}] 调用工具: ${tc.name}")
                val toolResult = try {
                    val argsMap = parseArgsToMap(tc.arguments)
                    toolExecutor.executeToolByName(tc.name, argsMap, apiConfig)
                } catch (e: Exception) {
                    "工具执行失败: ${e.message}"
                }
                messages.add(ChatMessage(
                    role = Role.USER,
                    content = "工具 ${tc.name} 的结果:\n$toolResult",
                    createdAt = System.currentTimeMillis(),
                ))
            }
        }

        return AgentOutput(
            agentName = agent.name,
            content = collectedOutput.toString().trim(),
        )
    }

    // ==================== Phase 3: 整合结果 ====================

    private suspend fun synthesize(request: String, outputs: List<AgentOutput>, apiConfig: ApiConfig): String {
        if (outputs.size == 1) return outputs.first().content

        val synthesis = buildString {
            appendLine("以下多个 Agent 完成了各自的任务，请将它们的输出整合为一个高质量的回答。")
            appendLine()
            appendLine("【原始问题】")
            appendLine(request)
            appendLine()
            for (output in outputs) {
                appendLine("── ${output.agentName} ──")
                appendLine(output.content.take(3000))
                appendLine()
            }
            appendLine("请整合以上内容，输出最终回答。去掉重复部分，保留最有价值的信息，结构化组织。")
        }

        return try {
            llmClient.chatCompletions(
                config = apiConfig,
                system = "你是结果整合专家。将多个Agent的输出整合为一个高质量、结构化的最终回答。",
                messages = listOf(ChatMessage(role = Role.USER, content = synthesis, createdAt = System.currentTimeMillis())),
            )
        } catch (e: Exception) {
            DebugLog.e(TAG, "结果整合失败: ${e.message}")
            outputs.joinToString("\n\n---\n\n") { "[${it.agentName}]\n${it.content}" }
        }
    }

    // ==================== 解析 ====================

    private fun parsePlan(response: String, originalRequest: String): ExecutionPlan {
        val json = extractJson(response) ?: return ExecutionPlan(
            needSwarm = false, directAnswer = response, agents = emptyList(),
        )

        return try {
            val needSwarm = json.optBoolean("needSwarm", false)
            if (!needSwarm) {
                return ExecutionPlan(
                    needSwarm = false,
                    directAnswer = json.optString("directAnswer", response),
                    agents = emptyList(),
                )
            }

            val mode = when (json.optString("executionMode", "serial")) {
                "parallel" -> ExecutionMode.PARALLEL
                else -> ExecutionMode.SERIAL
            }

            val agentsArray = json.optJSONArray("agents") ?: JSONArray()
            val agents = (0 until agentsArray.length()).take(maxAgents).mapNotNull { i ->
                val agentJson = agentsArray.optJSONObject(i) ?: return@mapNotNull null
                AgentDef(
                    name = agentJson.optString("name", "Agent-${i + 1}"),
                    systemPrompt = agentJson.optString("systemPrompt", "你是一个有帮助的助手。"),
                    instruction = agentJson.optString("instruction", ""),
                    dependsOn = agentJson.optJSONArray("dependsOn")?.let { arr ->
                        (0 until arr.length()).mapNotNull { arr.optString(it) }
                    } ?: emptyList(),
                )
            }

            ExecutionPlan(needSwarm = true, agents = agents, executionMode = mode)
        } catch (e: Exception) {
            DebugLog.e(TAG, "解析规划JSON失败: ${e.message}")
            ExecutionPlan(needSwarm = false, directAnswer = response, agents = emptyList())
        }
    }

    private fun extractJson(text: String): JSONObject? {
        // 尝试从 markdown code block 提取
        val codeBlockPattern = Regex("""```(?:json)?\s*([\s\S]*?)```""")
        val match = codeBlockPattern.find(text)
        val jsonStr = match?.groupValues?.get(1)?.trim() ?: run {
            // 尝试直接找 JSON 对象
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start >= 0 && end > start) text.substring(start, end + 1) else null
        }
        return try {
            jsonStr?.let { JSONObject(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun buildToolDescription(): String {
        val tools = toolExecutor.getResearchToolDefinitions()
        return if (tools.isEmpty()) {
            "当前无可用工具"
        } else {
            tools.joinToString("\n") { "- ${it.name}: ${it.description}" }
        }
    }

    private fun parseArgsToMap(argsJson: String): Map<String, String> {
        return try {
            val json = JSONObject(argsJson)
            json.keys().asSequence().associateWith { json.optString(it, "") }
        } catch (_: Exception) {
            emptyMap()
        }
    }
}

// ==================== 数据类型 ====================

enum class ExecutionMode { SERIAL, PARALLEL }

data class AgentDef(
    val name: String,
    val systemPrompt: String,
    val instruction: String,
    val dependsOn: List<String> = emptyList(),
)

data class AgentOutput(
    val agentName: String,
    val content: String,
)

data class ExecutionPlan(
    val needSwarm: Boolean,
    val directAnswer: String = "",
    val agents: List<AgentDef> = emptyList(),
    val executionMode: ExecutionMode = ExecutionMode.SERIAL,
)

data class SwarmResult(
    val success: Boolean,
    val finalAnswer: String,
    val agentOutputs: List<AgentOutput>,
    val processingTimeMs: Long,
)
