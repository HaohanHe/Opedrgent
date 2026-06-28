package top.hsyscn.opedrgent.agent

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import okhttp3.Call
import top.hsyscn.opedrgent.model.*
import top.hsyscn.opedrgent.network.*
import top.hsyscn.opedrgent.settings.ApiConfig
import top.hsyscn.opedrgent.storage.ResearchStore
import top.hsyscn.opedrgent.utils.ContextCompressor
import top.hsyscn.opedrgent.utils.DebugLog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Agent 核心服务
 *
 * 职责：LLM 调用循环 + 工具执行 + 状态管理。
 * 与 UI 层通过 StateFlow/SharedFlow 完全解耦。
 *
 * 设计参考 Kilo Code 的 SessionProcessor + SessionPrompt 模式：
 * - Agent 是配置，Service 是运行时
 * - 状态通过 Flow 单向流动到 UI
 * - UI 通过 SharedFlow 发送用户交互结果
 */
class AgentService(
    private val llmClient: LlmClient,
    private val toolExecutor: ToolExecutor,
    private val store: ResearchStore,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "AgentService"
        private const val MAX_ROUNDS = 10
        private const val MAX_RETRIES = 5
    }

    // ==================== 状态定义 ====================

    /**
     * Agent 运行状态 — ViewModel 观察这个来更新 UI
     */
    data class AgentState(
        val isRunning: Boolean = false,
        val isStreaming: Boolean = false,
        val streamingText: String = "",
        val streamingReasoning: String = "",
        val streamingToolParts: List<ToolPart> = emptyList(),
        val streamingPhase: String = "",
        val error: String? = null,
        val currentRound: Int = 0,
        val maxRounds: Int = MAX_ROUNDS,
    )

    /**
     * 用户交互请求（ask_question / ask_confirmation）
     * UI 层观察这个 Flow 来显示对话框
     */
    data class UserInteraction(
        val toolCallId: String,
        val toolName: String,
        val input: Map<String, String>,
    )

    // ==================== Flow 通道 ====================

    private val _state = MutableStateFlow(AgentState())
    val state: StateFlow<AgentState> = _state.asStateFlow()

    private val _userInteraction = MutableSharedFlow<UserInteraction>(extraBufferCapacity = 1)
    val userInteraction: SharedFlow<UserInteraction> = _userInteraction.asSharedFlow()

    // ==================== 内部状态 ====================

    private val cancelled = AtomicBoolean(false)
    private val currentCall = AtomicReference<Call?>(null)
    private var currentRunJob: Job? = null

    private enum class LoopState { IDLE, RUNNING, RETRYING }
    private var loopState = LoopState.IDLE

    // ==================== 公共 API ====================

    /**
     * 发送用户消息并启动 Agent 循环
     */
    fun sendMessage(
        sessionId: String,
        userContent: String,
        config: ApiConfig,
        agentTools: List<ToolDefinition>,
        memoryContext: String = "",
        systemPromptBuilder: (ResearchSession) -> String,
    ) {
        cancelled.set(false)

        currentRunJob = scope.launch {
            try {
                _state.value = _state.value.copy(
                    isRunning = true,
                    isStreaming = true,
                    streamingText = "",
                    streamingReasoning = "",
                    streamingToolParts = emptyList(),
                    streamingPhase = "思考中",
                    error = null,
                )

                val ctx = LoopContext(
                    sessionId = sessionId,
                    config = config,
                    agentTools = agentTools,
                    memoryContext = memoryContext,
                    systemPromptBuilder = systemPromptBuilder,
                )

                val checkpoint = store.loadCheckpoint(sessionId)
                if (checkpoint != null) {
                    ctx.round = checkpoint.round
                    ctx.accumulatedText.append(checkpoint.accumulatedText)
                    ctx.accumulatedReasoning.append(checkpoint.accumulatedReasoning)
                    ctx.toolMessages.addAll(checkpoint.toolMessages)
                    ctx.sources.addAll(checkpoint.sources)
                    ctx.continuationMessage = ChatMessage(
                        role = Role.SYSTEM,
                        content = buildContinuationPrompt(checkpoint),
                    )
                }

                val result = runLoop(ctx)

                if (result != null && !result.wasCancelled) {
                    _state.value = _state.value.copy(
                        isRunning = false,
                        isStreaming = false,
                        streamingText = "",
                        streamingPhase = "",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.e("AgentService error: ${e.message}", e)
                _state.value = _state.value.copy(
                    error = e.message ?: "请求失败",
                    isRunning = false,
                    isStreaming = false,
                    streamingText = "",
                    streamingPhase = "",
                )
            } finally {
                _state.value = _state.value.copy(isRunning = false)
                currentCall.set(null)
                currentRunJob = null
            }
        }
    }

    /**
     * 取消当前运行
     */
    fun cancel() {
        cancelled.set(true)
        currentRunJob?.cancel()
        currentCall.get()?.cancel()
        _state.value = _state.value.copy(
            isRunning = false,
            isStreaming = false,
            streamingText = "",
            streamingPhase = "",
        )
    }

    /**
     * 显式完成/结束研究会话，清理检查点。
     */
    fun finalizeSession(sessionId: String) {
        store.deleteCheckpoint(sessionId)
    }

    /**
     * 用户交互响应（回答 ask_question / ask_confirmation）
     */
    fun submitUserResponse(toolCallId: String, response: String) {
        // 将响应注入到当前循环的待处理队列中
        _pendingResponses.tryEmit(toolCallId to response)
    }

    private val _pendingResponses = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 16)

    // ==================== Agent 循环核心 ====================

    private data class LoopContext(
        val sessionId: String,
        val config: ApiConfig,
        val agentTools: List<ToolDefinition>,
        val memoryContext: String,
        val systemPromptBuilder: (ResearchSession) -> String,
        val maxContextTokens: Int = 100_000,
        val toolMessages: MutableList<ChatMessage> = mutableListOf(),
        val accumulatedText: StringBuilder = StringBuilder(),
        val accumulatedReasoning: StringBuilder = StringBuilder(),
        val allToolParts: MutableList<ToolPart> = mutableListOf(),
        var round: Int = 0,
        val sources: MutableList<Source> = mutableListOf(),
        var continuationMessage: ChatMessage? = null,
        var reflectionAttempts: Int = 0,
        var reflectionPending: Boolean = false,
    )

    private data class LoopResult(
        val finalContent: String,
        val finalReasoning: String,
        val wasCancelled: Boolean,
    )

    private sealed class LoopOutcome {
        object Continue : LoopOutcome()
        data class Break(val reason: BreakReason) : LoopOutcome()
        data class Retry(val delay: Long) : LoopOutcome()
        data class Error(val message: String) : LoopOutcome()
    }

    private enum class BreakReason { NORMAL, GUARDRAIL, CANCELLED }

    private suspend fun runLoop(ctx: LoopContext): LoopResult? {
        loopState = LoopState.RUNNING
        var retryCount = 0
        var roundsThisRun = 0
        val guardrail = top.hsyscn.opedrgent.utils.ToolCallGuardrail()

        try {
            while (roundsThisRun < MAX_ROUNDS) {
                if (cancelled.get()) {
                    DebugLog.i("runLoop cancelled at round ${ctx.round}")
                    saveCheckpoint(ctx, guardrail, "用户取消")
                    return null
                }

                _state.value = _state.value.copy(currentRound = ctx.round)

                val outcome = try {
                    executeOneRound(ctx, guardrail)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DebugLog.e("executeOneRound error: ${e.message}", e)
                    LoopOutcome.Error(e.message ?: "未知错误")
                }

                when (outcome) {
                    is LoopOutcome.Continue -> {
                        retryCount = 0
                        ctx.round++
                        roundsThisRun++
                        saveCheckpoint(ctx, guardrail)
                    }
                    is LoopOutcome.Break -> {
                        when (outcome.reason) {
                            BreakReason.NORMAL -> store.deleteCheckpoint(ctx.sessionId)
                            BreakReason.GUARDRAIL -> saveCheckpoint(ctx, guardrail, "工具调用保护触发")
                            BreakReason.CANCELLED -> saveCheckpoint(ctx, guardrail, "用户取消")
                        }
                        break
                    }
                    is LoopOutcome.Retry -> {
                        loopState = LoopState.RETRYING
                        retryCount++
                        if (retryCount > MAX_RETRIES) {
                            _state.value = _state.value.copy(error = "重试次数过多")
                            saveCheckpoint(ctx, guardrail, "重试次数过多")
                            break
                        }
                        _state.value = _state.value.copy(
                            streamingPhase = "请求失败，${outcome.delay / 1000}秒后重试…(第${retryCount}次)",
                        )
                        delay(outcome.delay)
                        loopState = LoopState.RUNNING
                    }
                    is LoopOutcome.Error -> {
                        _state.value = _state.value.copy(error = outcome.message)
                        saveCheckpoint(ctx, guardrail, outcome.message)
                        break
                    }
                }
            }

            if (roundsThisRun >= MAX_ROUNDS) {
                saveCheckpoint(ctx, guardrail, "达到最大轮数")
            }
        } finally {
            loopState = LoopState.IDLE
        }

        return LoopResult(
            finalContent = ctx.accumulatedText.toString(),
            finalReasoning = ctx.accumulatedReasoning.toString(),
            wasCancelled = cancelled.get(),
        )
    }

    private suspend fun executeOneRound(ctx: LoopContext, guardrail: top.hsyscn.opedrgent.utils.ToolCallGuardrail): LoopOutcome {
        val session = store.getSession(ctx.sessionId)
            ?: throw IllegalStateException("会话不存在: ${ctx.sessionId}")

        val system = ctx.systemPromptBuilder(session)

        val continuationMsg = ctx.continuationMessage
        ctx.continuationMessage = null
        val sessionMessages = if (continuationMsg != null) {
            session.messages + listOf(continuationMsg)
        } else {
            session.messages
        }
        val allMessages = sessionMessages + ctx.toolMessages
        val compressed = ContextCompressor.compress(allMessages, system, ctx.maxContextTokens)
        val compressedSystem = if (compressed.summary != null) {
            "$system\n\n${compressed.summary}"
        } else system

        val messages = compressed.messages

        DebugLog.d("executeOneRound: round=${ctx.round}, messages=${messages.size}, tokens=${compressed.tokenCount}")

        _state.value = _state.value.copy(
            streamingPhase = if (ctx.round == 0) "思考中" else "继续思考",
        )

        // 流式 LLM 调用
        val streamResult = streamLlm(
            config = ctx.config,
            system = compressedSystem,
            messages = messages,
            tools = ctx.agentTools,
        )

        if (cancelled.get()) return LoopOutcome.Break(BreakReason.CANCELLED)

        // 处理 LLM 错误
        if (streamResult.error != null) {
            if (streamResult.content.isNotBlank()) {
                ctx.accumulatedText.append(
                    if (ctx.accumulatedText.isNotEmpty()) "\n\n" else ""
                ).append(streamResult.content)
            }
            return LoopOutcome.Error(streamResult.error)
        }

        // 累积文本
        if (streamResult.content.isNotBlank()) {
            ctx.accumulatedText.append(
                if (ctx.accumulatedText.isNotEmpty()) "\n\n" else ""
            ).append(streamResult.content)
        }

        // 没有工具调用 → 结束
        if (streamResult.toolCalls.isEmpty()) {
            return LoopOutcome.Break(BreakReason.NORMAL)
        }

        // 有工具调用 → 执行工具
        val assistantMsg = ChatMessage(
            role = Role.ASSISTANT,
            content = streamResult.content,
            createdAt = System.currentTimeMillis(),
            apiToolCallsJson = buildApiToolCallsJson(streamResult.toolCalls),
        )
        ctx.toolMessages.add(assistantMsg)

        for (tc in streamResult.toolCalls) {
            if (cancelled.get()) break

            // Guardrail 预检查：如果再次调用会触发 doom loop，直接走反思轮而不是重复执行
            val preAction = guardrail.peek(tc.name, tc.arguments)
            if (preAction == top.hsyscn.opedrgent.utils.ToolCallGuardrail.GuardrailAction.AGENT_HALT) {
                if (ctx.reflectionAttempts >= 1) {
                    DebugLog.w("AgentService: peek AGENT_HALT after reflection, escalate to SESSION_HALT")
                    _state.value = _state.value.copy(
                        streamingPhase = "[工具调用保护] 反思后仍重复相同参数，已自动停止",
                    )
                    return LoopOutcome.Error("工具调用保护: 反思后仍重复相同参数，会话停止")
                }
                DebugLog.w("AgentService: peek AGENT_HALT, trigger reflection round for ${tc.name}")
                return runReflectionRound(ctx, ctx.round, compressedSystem, messages, guardrail)
            }

            DebugLog.d("执行工具: ${tc.name} args=${tc.arguments}")
            _state.value = _state.value.copy(streamingPhase = "执行: ${tc.name}")

            val toolPart = ToolPart(
                tool = tc.name,
                state = ToolState(
                    status = ToolStateType.RUNNING,
                    input = parseToolArgs(tc.arguments),
                    startTime = System.currentTimeMillis(),
                ),
            )
            ctx.allToolParts.add(toolPart)
            _state.value = _state.value.copy(
                streamingToolParts = ctx.allToolParts.toList(),
            )

            // 特殊工具：ask_question / ask_confirmation 需要用户交互
            if (tc.name == "ask_question" || tc.name == "ask_confirmation") {
                val interaction = UserInteraction(
                    toolCallId = tc.id,
                    toolName = tc.name,
                    input = parseToolArgs(tc.arguments),
                )
                _userInteraction.tryEmit(interaction)

                // 等待用户响应
                val response = withTimeoutOrNull(60_000) {
                    _pendingResponses.first { it.first == tc.id }
                }
                val userAnswer = response?.second ?: "{\"timeout\": true}"

                val toolResultMsg = ChatMessage(
                    role = Role.USER,
                    content = userAnswer,
                    createdAt = System.currentTimeMillis(),
                    toolCallId = tc.id,
                )
                ctx.toolMessages.add(toolResultMsg)
                continue
            }

            // 普通工具执行
            val result = try {
                toolExecutor.executeToolByNameStructured(
                    tc.name,
                    parseToolArgs(tc.arguments),
                    ctx.config,
                )
            } catch (e: Exception) {
                ToolExecutionResult(
                    status = ToolExecutionStatus.FATAL_ERROR,
                    content = "工具执行失败: ${e.message}",
                    errorDetail = e.message,
                )
            }

            val resultContent = when (result.status) {
                ToolExecutionStatus.SUCCESS -> result.content
                ToolExecutionStatus.PARTIAL_TIMEOUT,
                ToolExecutionStatus.TIMEOUT,
                ToolExecutionStatus.RATE_LIMIT -> {
                    val statusLabel = when (result.status) {
                        ToolExecutionStatus.PARTIAL_TIMEOUT -> "部分超时"
                        ToolExecutionStatus.TIMEOUT -> "超时/网络暂不可用"
                        ToolExecutionStatus.RATE_LIMIT -> "限流或访问限制"
                    }
                    buildString {
                        append(result.content)
                        append("\n\n[系统提示：该工具调用出现")
                        append(statusLabel)
                        append("，这是暂时性问题。如果你认为已有信息足够，可以直接给出阶段性结论；如果还需要补充，请尝试其他关键词、其他工具或其他URL，不要重复调用同一个失败参数。]")
                    }
                }
                ToolExecutionStatus.FATAL_ERROR -> result.content
            }

            val toolResultMsg = ChatMessage(
                role = Role.USER,
                content = resultContent,
                createdAt = System.currentTimeMillis(),
                toolCallId = tc.id,
            )
            ctx.toolMessages.add(toolResultMsg)

            // Guardrail: 分层决策，传入实际执行状态以区分瞬态/致命失败
            val action = guardrail.record(
                toolName = tc.name,
                args = tc.arguments,
                result = resultContent,
                status = result.status,
            )
            when (action) {
                top.hsyscn.opedrgent.utils.ToolCallGuardrail.GuardrailAction.SESSION_HALT -> {
                    DebugLog.w("AgentService: guardrail SESSION_HALT, tool=${tc.name}")
                    _state.value = _state.value.copy(
                        streamingPhase = "[工具调用保护] 检测到严重问题，已自动停止",
                    )
                    return LoopOutcome.Error("工具调用保护: 检测到严重问题，会话停止")
                }
                top.hsyscn.opedrgent.utils.ToolCallGuardrail.GuardrailAction.AGENT_HALT -> {
                    if (ctx.reflectionAttempts >= 1) {
                        DebugLog.w("AgentService: guardrail AGENT_HALT after reflection, escalate to SESSION_HALT")
                        _state.value = _state.value.copy(
                            streamingPhase = "[工具调用保护] 反思后策略仍未调整，已自动停止",
                        )
                        return LoopOutcome.Error("工具调用保护: 反思后策略仍未调整，会话停止")
                    }
                    DebugLog.w("AgentService: guardrail AGENT_HALT, trigger reflection round")
                    return runReflectionRound(ctx, ctx.round, compressedSystem, messages, guardrail)
                }
                top.hsyscn.opedrgent.utils.ToolCallGuardrail.GuardrailAction.TOOL_BLOCK -> {
                    DebugLog.w("AgentService: guardrail TOOL_BLOCK, tool=${tc.name}")
                    val blockedMsg = ChatMessage(
                        role = Role.USER,
                        content = "[系统提示：工具 '${tc.name}' 因重复调用或致命错误已被本轮阻止。请换用其他工具、其他关键词或其他 URL，或直接给出阶段性结论。]",
                        createdAt = System.currentTimeMillis(),
                    )
                    ctx.toolMessages.add(blockedMsg)
                    continue
                }
                top.hsyscn.opedrgent.utils.ToolCallGuardrail.GuardrailAction.PARTIAL_ERROR -> {
                    DebugLog.w("AgentService: guardrail PARTIAL_ERROR, tool=${tc.name}")
                }
                top.hsyscn.opedrgent.utils.ToolCallGuardrail.GuardrailAction.ALLOW -> {}
            }
        }

        return LoopOutcome.Continue
    }

    /**
     * 反思轮（reflection round）。
     * 当 guardrail 触发 AGENT_HALT 时，暂停工具调用，要求 LLM 分析已获取信息并说明下一步策略。
     * 若反思后模型仍尝试调用工具（在 tool_choice=none 不应发生），则升级为 SESSION_HALT。
     */
    private suspend fun runReflectionRound(
        ctx: LoopContext,
        round: Int,
        system: String,
        messages: List<ChatMessage>,
        guardrail: top.hsyscn.opedrgent.utils.ToolCallGuardrail,
    ): LoopOutcome {
        ctx.reflectionPending = true
        ctx.reflectionAttempts++

        _state.value = _state.value.copy(streamingPhase = "[工具调用保护] 检测到重复模式，正在反思...")

        val reflectionPrompt = top.hsyscn.opedrgent.utils.PromptBuilder.buildReflectionPrompt()
        val reflectionUserMsg = ChatMessage(
            role = Role.USER,
            content = reflectionPrompt,
            createdAt = System.currentTimeMillis(),
        )

        // 将反思提示加入历史，让后续轮次能看到
        ctx.toolMessages.add(reflectionUserMsg)

        // 通过 toolChoice="none" 并要求模型不调用工具；
        // 对不支持 tool_choice 的模型，传空 tools 列表作为等效兜底。
        val reflectionStreamResult = streamLlm(
            config = ctx.config,
            system = system,
            messages = messages + reflectionUserMsg,
            tools = ctx.agentTools,
            toolChoice = "none",
        )

        ctx.reflectionPending = false

        if (cancelled.get()) return LoopOutcome.Break(BreakReason.CANCELLED)

        if (reflectionStreamResult.error != null) {
            return LoopOutcome.Error("反思轮调用失败: ${reflectionStreamResult.error}")
        }

        if (reflectionStreamResult.toolCalls.isNotEmpty()) {
            DebugLog.w("AgentService: LLM emitted tool calls during reflection despite tool_choice=none")
            return LoopOutcome.Error("工具调用保护: 反思轮中模型仍尝试调用工具，会话停止")
        }

        if (reflectionStreamResult.content.isNotBlank()) {
            ctx.accumulatedText.append(
                if (ctx.accumulatedText.isNotEmpty()) "\n\n" else ""
            ).append(reflectionStreamResult.content)

            val reflectionAssistantMsg = ChatMessage(
                role = Role.ASSISTANT,
                content = reflectionStreamResult.content,
                createdAt = System.currentTimeMillis(),
            )
            ctx.toolMessages.add(reflectionAssistantMsg)
        }

        DebugLog.i("AgentService: reflection round completed, attempts=${ctx.reflectionAttempts}")
        return LoopOutcome.Continue
    }

    private fun saveCheckpoint(
        ctx: LoopContext,
        guardrail: top.hsyscn.opedrgent.utils.ToolCallGuardrail,
        haltReason: String? = null,
    ) {
        val session = store.getSession(ctx.sessionId)
        ctx.sources.clear()
        ctx.sources.addAll(session?.sources ?: emptyList())
        store.saveCheckpoint(
            ResearchCheckpoint(
                sessionId = ctx.sessionId,
                round = ctx.round,
                accumulatedText = ctx.accumulatedText.toString(),
                accumulatedReasoning = ctx.accumulatedReasoning.toString(),
                toolMessages = ctx.toolMessages.toList(),
                sources = ctx.sources.toList(),
                guardrailSnapshot = guardrail.exportSnapshot(),
                haltReason = haltReason,
            )
        )
    }

    private fun buildContinuationPrompt(checkpoint: ResearchCheckpoint): String {
        return buildString {
            append("这是之前研究的续作。已进行 ${checkpoint.round} 轮，已收集 ${checkpoint.sources.size} 个来源。")
            append("\n\n")
            val reason = checkpoint.haltReason ?: "达到最大轮数或异常中断"
            append("上一步因为 $reason 中断。请基于已有信息继续，或判断已有信息足够并输出结论。")
            if (checkpoint.toolMessages.isNotEmpty()) {
                append("\n\n以下是最新工具结果摘要：\n")
                checkpoint.toolMessages.takeLast(4).forEach { msg ->
                    append("- [${msg.role}] ${msg.content.take(200)}\n")
                }
            }
        }
    }

    // ==================== LLM 流式调用 ====================

    private data class InternalStreamResult(
        val content: String,
        val reasoning: String = "",
        val toolCalls: List<CompletedToolCall> = emptyList(),
        val error: String? = null,
    )

    private suspend fun streamLlm(
        config: ApiConfig,
        system: String,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        toolChoice: String? = null,
    ): InternalStreamResult {
        val contentBuf = StringBuilder()
        val reasoningBuf = StringBuilder()
        val deferred = CompletableDeferred<InternalStreamResult>()

        val call = llmClient.streamChatCompletions(
            config = config,
            system = system,
            messages = messages,
            tools = tools,
            toolChoice = toolChoice,
            onDelta = { delta ->
                when (delta) {
                    is StreamDelta.TextDelta -> {
                        contentBuf.append(delta.text)
                        _state.value = _state.value.copy(streamingText = contentBuf.toString())
                    }
                    is StreamDelta.ReasoningDelta -> {
                        reasoningBuf.append(delta.text)
                        _state.value = _state.value.copy(streamingReasoning = reasoningBuf.toString())
                    }
                }
            },
            onToolCallDelta = { /* toolCalls 由 onDone 的 result 处理 */ },
            onDone = { result ->
                deferred.complete(InternalStreamResult(
                    content = result.content,
                    reasoning = result.reasoning,
                    toolCalls = result.toolCalls,
                    error = null,
                ))
            },
            onError = { error ->
                deferred.complete(InternalStreamResult(
                    content = contentBuf.toString(),
                    error = error,
                ))
            },
        )

        currentCall.set(call)

        return withContext(NonCancellable) {
            deferred.await()
        }
    }

    // ==================== 工具方法 ====================

    private fun buildApiToolCallsJson(toolCalls: List<CompletedToolCall>): String {
        val arr = org.json.JSONArray()
        for (tc in toolCalls) {
            arr.put(JSONObject().apply {
                put("id", tc.id)
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", tc.name)
                    put("arguments", tc.arguments)
                })
            })
        }
        return arr.toString()
    }

    private fun parseToolArgs(arguments: String): Map<String, String> {
        return try {
            val json = JSONObject(arguments)
            json.keys().asSequence().associateWith { json.optString(it, "") }
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
