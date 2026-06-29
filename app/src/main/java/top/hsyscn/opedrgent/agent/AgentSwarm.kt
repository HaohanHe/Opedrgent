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
import top.hsyscn.opedrgent.transaction.CheckpointManager
import top.hsyscn.opedrgent.transaction.RollbackExecutor
import top.hsyscn.opedrgent.transaction.RollbackStrategy
import top.hsyscn.opedrgent.transaction.RollbackToolRegistry
import top.hsyscn.opedrgent.transaction.ToolCallRecord
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
    private val checkpointManager: CheckpointManager? = null,
    private val rollbackStrategy: RollbackStrategy? = null,
) {
    companion object {
        private const val TAG = "AgentSwarm"
    }

    /**
     * 事务回滚执行器（懒加载）。仅当 [checkpointManager] 非空时启用。
     * 启用时自动注册内置补偿映射（run_calendar create->delete 等）。
     */
    private val rollbackExecutor: RollbackExecutor? by lazy {
        checkpointManager?.let { mgr ->
            RollbackToolRegistry.registerDefaults()
            RollbackExecutor(mgr, RollbackToolRegistry, toolExecutor)
        }
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

            // 共享存储：Agent 间通过 storage 共享中间状态
            val sharedStorage = AgentStorage()
            val requestKey = StorageKey<String>("original_request")
            sharedStorage.set(requestKey, request)

            // Phase 2: 执行 Agent 集群
            onProgress("正在执行 ${plan.agents.size} 个 Agent...")
            val agentOutputs = when (plan.executionMode) {
                ExecutionMode.PARALLEL -> executeParallel(plan.agents, apiConfig, onProgress, sharedStorage)
                ExecutionMode.SERIAL -> executeSerial(plan.agents, apiConfig, onProgress, sharedStorage)
            }

            // Phase 3: 整合结果
            onProgress("正在整合结果...")
            val mergeStrategy = plan.agents.firstOrNull()?.mergeStrategy ?: MergeStrategy.MERGE_LLM
            val finalAnswer = synthesize(request, agentOutputs, apiConfig, mergeStrategy)

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
7. mergeStrategy 决定多 Agent 输出的合并方式：
   - CONCAT: 保留所有 agent 完整输出（适合需要完整记录的场景）
   - MERGE_LLM: 调用 LLM 整合为结构化回答（默认，适合大多数场景）
   - VOTE: 取最长的输出作为代表（适合多 agent 探索同一问题的场景）
   - REDUCE: 按 length+lineCount 打分取最高（适合择优场景）

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
      "dependsOn": [],
      "mergeStrategy": "MERGE_LLM"  // 可选：CONCAT(顺序拼接) | MERGE_LLM(LLM整合,默认) | VOTE(取最长) | REDUCE(打分择优)
    }
  ]
}"""

        val result = llmClient.chatCompletionsWithTools(
            config = apiConfig,
            system = "你是任务调度大脑，擅长分析任务并分配子Agent。只输出JSON，不要解释。",
            messages = listOf(ChatMessage(role = Role.USER, content = prompt, createdAt = System.currentTimeMillis())),
            sessionId = "swarm_plan",
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
        sharedStorage: AgentStorage,
    ): List<AgentOutput> = coroutineScope {
        // DAG 拓扑排序：按依赖关系分层，同层可并行
        val waves = topologicalWaves(agents)
        val allResults = mutableListOf<AgentOutput>()

        for ((waveIdx, wave) in waves.withIndex()) {
            onProgress("执行第 ${waveIdx + 1}/${waves.size} 波（${wave.joinToString { it.name }}）...")

            // ★ Phase 3 P1: 每个 agent 使用 sharedStorage.copy() 作为 fork 副本，避免并行写竞争
            val forkedStorages = mutableListOf<AgentStorage>()
            val waveResults = wave.map { agent ->
                async {
                    val depOutputs = if (agent.dependsOn.isNotEmpty()) {
                        allResults.filter { it.agentName in agent.dependsOn }
                    } else {
                        emptyList()
                    }
                    // fork: 每个 agent 拿到 storage 副本，写操作互不干扰
                    val forkedStorage = sharedStorage.copy()
                    synchronized(forkedStorages) {
                        forkedStorages.add(forkedStorage)
                    }
                    val output = runSingleAgent(agent, depOutputs, apiConfig, forkedStorage)
                    DebugLog.i(TAG, "Agent [${agent.name}] 完成, output=${output.content.take(100)}")
                    output
                }
            }.awaitAll()

            // ★ Phase 3 P1: wave 结束后将各 fork 副本的关键输出 merge 回主 storage
            // mergeFrom 不覆盖已存在的 key，但 agent_output:* 是每个 agent 独有的 key，不会冲突
            for (forked in forkedStorages) {
                sharedStorage.mergeFrom(forked)
            }

            allResults.addAll(waveResults)
        }

        allResults
    }

    /**
     * 拓扑排序：将 Agent 按依赖关系分成多个 wave，同一 wave 内无依赖可并行执行。
     */
    private fun topologicalWaves(agents: List<AgentDef>): List<List<AgentDef>> {
        val agentMap = agents.associateBy { it.name }
        val remaining = agents.toMutableSet()
        val completed = mutableSetOf<String>()
        val waves = mutableListOf<List<AgentDef>>()

        while (remaining.isNotEmpty()) {
            // 找出所有依赖已满足的 Agent
            val ready = remaining.filter { agent ->
                agent.dependsOn.all { it in completed }
            }
            if (ready.isEmpty()) {
                // 循环依赖 → 强制按原始顺序执行剩余
                DebugLog.w(TAG, "检测到循环依赖，强制串行执行剩余 Agent")
                waves.add(remaining.toList())
                break
            }
            waves.add(ready)
            ready.forEach { remaining.remove(it) }
            completed.addAll(ready.map { it.name })
        }
        return waves
    }

    /**
     * 串行执行，每个 Agent 能看到前面所有 Agent 的输出（链式传递）
     */
    private suspend fun executeSerial(
        agents: List<AgentDef>,
        apiConfig: ApiConfig,
        onProgress: (String) -> Unit,
        sharedStorage: AgentStorage,
    ): List<AgentOutput> {
        val results = mutableListOf<AgentOutput>()

        for (agent in agents) {
            val depOutputs = if (agent.dependsOn.isNotEmpty()) {
                results.filter { it.agentName in agent.dependsOn }
            } else {
                results.toList()
            }

            onProgress("Agent [${agent.name}] 执行中（串行第${results.size + 1}/${agents.size}步）...")
            val output = runSingleAgent(agent, depOutputs, apiConfig, sharedStorage)
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
        sharedStorage: AgentStorage,
    ): AgentOutput {
        val messages = mutableListOf<ChatMessage>()

        // 事务检查点：快照消息历史与共享存储，失败时据此回滚（Koog 风格）
        val checkpointId = checkpointManager?.createCheckpoint(agent.name, messages, sharedStorage)

        try {
        // 构建输入：instruction + 前面 Agent 的输出 + storage 摘要
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

        // 注入 storage 中的关键数据（避免传递全部，只传与当前 Agent 相关的）
        if (sharedStorage.size() > 1) { // >1 因为 original_request 总是存在
            inputBuilder.appendLine("【共享上下文数据】")
            for (key in sharedStorage.keys()) {
                if (key == "original_request") continue
                val value = sharedStorage.get(StorageKey<Any>(key))
                val valueStr = value?.toString()?.take(500) ?: "null"
                inputBuilder.appendLine("- $key: $valueStr")
            }
            inputBuilder.appendLine()
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
        val guardrail = top.hsyscn.opedrgent.utils.ToolCallGuardrail()

        while (rounds < 5) {
            rounds++
            val result = llmClient.chatCompletionsWithTools(
                config = apiConfig,
                system = agent.systemPrompt,
                messages = messages,
                tools = toolDefs,
                sessionId = "swarm_${agent.name}",
            )

            if (result.toolCalls.isEmpty()) {
                // 没有工具调用，Agent 输出最终结果
                collectedOutput.append(result.content)
                break
            }

            // 有工具调用：执行工具，然后继续
            collectedOutput.append(result.content)

            // 把 assistant 消息加入对话（含 tool_calls 协议，P0-5 修复）
            val apiCallsJson = JSONArray().apply {
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
                DebugLog.d(TAG, "Agent [${agent.name}] 调用工具: ${tc.name}")
                val argsMap = parseArgsToMap(tc.arguments)
                val toolResult = try {
                    toolExecutor.executeToolByName(tc.name, argsMap, apiConfig)
                } catch (e: Exception) {
                    "工具执行失败: ${e.message}"
                }
                // 事务记录：累积补偿依据（仅成功调用有副作用需撤销）
                checkpointId?.let { cpId ->
                    checkpointManager.appendToolCall(cpId, ToolCallRecord(
                        toolName = tc.name,
                        input = argsMap,
                        output = toolResult,
                        toolUseId = tc.id,
                        succeeded = !toolResult.startsWith("工具执行失败"),
                    ))
                }
                messages.add(ChatMessage(
                    role = Role.USER,
                    content = "工具 ${tc.name} 的结果:\n$toolResult",
                    createdAt = System.currentTimeMillis(),
                    toolCallId = tc.id,
                ))

                // Guardrail: 检测doom loop和重复失败
                val action = guardrail.record(
                    toolName = tc.name,
                    args = tc.arguments,
                    result = toolResult,
                    success = !toolResult.startsWith("工具执行失败"),
                )
                when (action) {
                    top.hsyscn.opedrgent.utils.ToolCallGuardrail.GuardrailAction.SESSION_HALT,
                    top.hsyscn.opedrgent.utils.ToolCallGuardrail.GuardrailAction.AGENT_HALT,
                    top.hsyscn.opedrgent.utils.ToolCallGuardrail.GuardrailAction.TOOL_BLOCK -> {
                        DebugLog.w("AgentSwarm: guardrail ${action.name} for Agent [${agent.name}], tool=${tc.name}")
                        guardrailBlocked = true
                    }
                    top.hsyscn.opedrgent.utils.ToolCallGuardrail.GuardrailAction.PARTIAL_ERROR -> {
                        DebugLog.w("AgentSwarm: guardrail PARTIAL_ERROR for Agent [${agent.name}], tool=${tc.name}")
                    }
                    top.hsyscn.opedrgent.utils.ToolCallGuardrail.GuardrailAction.ALLOW -> {}
                }
            }
            if (guardrailBlocked) {
                DebugLog.w("AgentSwarm: Agent [${agent.name}] 工具调用被guardrail终止")
                break
            }
        }

        val output = AgentOutput(
            agentName = agent.name,
            content = collectedOutput.toString().trim(),
        )

        // 将 Agent 输出写入共享存储，供后续 Agent 参考
        sharedStorage.set(StorageKey("agent_output:${agent.name}"), output.content)
        DebugLog.d(TAG, "Storage 写入: agent_output:${agent.name} (${output.content.length} chars)")

        // 事务成功：标记墓碑，禁止后续回滚
        checkpointId?.let { checkpointManager.markTombstone(it) }
        return output
        } catch (e: Exception) {
            // 协程取消不触发回滚（结构化并发：取消应立即传播）
            if (e is kotlinx.coroutines.CancellationException) throw e
            // 事务失败：执行补偿回滚（尽力而为，不掩盖原始异常）
            rollbackOnFailure(checkpointId, agent.name, messages, apiConfig)
            throw e
        }
    }

    /**
     * 失败时执行事务回滚。仅当 checkpointManager 与 rollbackStrategy 均配置时启用。
     * 回滚自身的异常被吞掉并记录，确保不掩盖导致失败的原始异常。
     */
    private suspend fun rollbackOnFailure(
        checkpointId: String?,
        agentName: String,
        messages: List<ChatMessage>,
        apiConfig: ApiConfig,
    ) {
        val cpId = checkpointId ?: return
        val mgr = checkpointManager ?: return
        val exec = rollbackExecutor ?: return
        val strategy = rollbackStrategy ?: return
        try {
            val result = exec.rollback(cpId, messages.toList(), strategy, apiConfig)
            DebugLog.w(TAG, "Agent [$agentName] 事务回滚完成: ${result.reason}, compensations=${result.compensationResults.size}")
        } catch (re: Exception) {
            DebugLog.e(TAG, "Agent [$agentName] 事务回滚异常: ${re.message}", re)
        }
    }

    // ==================== Phase 3: 整合结果 ====================

    private suspend fun synthesize(
        request: String,
        outputs: List<AgentOutput>,
        apiConfig: ApiConfig,
        mergeStrategy: MergeStrategy = MergeStrategy.MERGE_LLM,
    ): String {
        if (outputs.size == 1) return outputs.first().content

        // ★ Phase 3 P1: 按 mergeStrategy 分支处理
        return when (mergeStrategy) {
            MergeStrategy.CONCAT -> {
                // 顺序拼接，不调用 LLM
                DebugLog.d(TAG, "synthesize: CONCAT strategy, ${outputs.size} outputs")
                outputs.joinToString("\n\n---\n\n") { "[${it.agentName}]\n${it.content}" }
            }

            MergeStrategy.VOTE -> {
                // 多数表决：取最长的 agent 输出作为代表
                DebugLog.d(TAG, "synthesize: VOTE strategy, ${outputs.size} outputs")
                val selected = outputs.maxWithOrNull(
                    compareBy<AgentOutput> { it.content.length }
                        .thenBy { it.agentName }
                ) ?: outputs.first()
                "[经多数表决选取: ${selected.agentName}]\n${selected.content}"
            }

            MergeStrategy.REDUCE -> {
                // 按 length*0.5 + lineCount*10 打分取最高
                DebugLog.d(TAG, "synthesize: REDUCE strategy, ${outputs.size} outputs")
                val scored = outputs.map { output ->
                    val lineCount = output.content.count { it == '\n' }
                    val score = output.content.length * 0.5 + lineCount * 10
                    output to score
                }
                val (selected, score) = scored.maxByOrNull { it.second } ?: (outputs.first() to 0.0)
                DebugLog.d(TAG, "synthesize: REDUCE selected=${selected.agentName}, score=$score")
                "[经打分择优: ${selected.agentName} (score=${"%.1f".format(score)})]\n${selected.content}"
            }

            MergeStrategy.MERGE_LLM -> {
                // 默认：调用 LLM 整合（保持现有逻辑，向后兼容）
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

                try {
                    llmClient.chatCompletionsWithTools(
                        config = apiConfig,
                        system = "你是结果整合专家。将多个Agent的输出整合为一个高质量、结构化的最终回答。",
                        messages = listOf(ChatMessage(role = Role.USER, content = synthesis, createdAt = System.currentTimeMillis())),
                        sessionId = "swarm_synthesize",
                    ).content
                } catch (e: Exception) {
                    DebugLog.e(TAG, "结果整合失败: ${e.message}")
                    outputs.joinToString("\n\n---\n\n") { "[${it.agentName}]\n${it.content}" }
                }
            }
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
                val strategy = when (agentJson.optString("mergeStrategy", "MERGE_LLM").uppercase()) {
                    "CONCAT" -> MergeStrategy.CONCAT
                    "VOTE" -> MergeStrategy.VOTE
                    "REDUCE" -> MergeStrategy.REDUCE
                    else -> MergeStrategy.MERGE_LLM
                }
                AgentDef(
                    name = agentJson.optString("name", "Agent-${i + 1}"),
                    systemPrompt = agentJson.optString("systemPrompt", "你是一个有帮助的助手。"),
                    instruction = agentJson.optString("instruction", ""),
                    dependsOn = agentJson.optJSONArray("dependsOn")?.let { arr ->
                        (0 until arr.length()).mapNotNull { arr.optString(it) }
                    } ?: emptyList(),
                    mergeStrategy = strategy,
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

/**
 * 多 Agent 输出合并策略（对标 Koog MergeStrategy）。
 * - CONCAT: 各 agent 输出按顺序拼接，不调用 LLM（适合需要完整记录的场景）
 * - MERGE_LLM: 调用 LLM 整合为结构化最终回答（默认，向后兼容）
 * - VOTE: 多数表决，取最长的 agent 输出作为代表（避免短答案淹没）
 * - REDUCE: 按 length*0.5 + lineCount*10 打分取最高（择优）
 */
enum class MergeStrategy { CONCAT, MERGE_LLM, VOTE, REDUCE }

data class AgentDef(
    val name: String,
    val systemPrompt: String,
    val instruction: String,
    val dependsOn: List<String> = emptyList(),
    val mergeStrategy: MergeStrategy = MergeStrategy.MERGE_LLM,
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
