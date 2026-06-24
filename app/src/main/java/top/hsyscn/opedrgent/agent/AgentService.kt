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
    )

    private data class LoopResult(
        val finalContent: String,
        val finalReasoning: String,
        val wasCancelled: Boolean,
    )

    private sealed class LoopOutcome {
        object Continue : LoopOutcome()
        object Break : LoopOutcome()
        data class Retry(val delay: Long) : LoopOutcome()
        data class Error(val message: String) : LoopOutcome()
    }

    private suspend fun runLoop(ctx: LoopContext): LoopResult? {
        loopState = LoopState.RUNNING
        var retryCount = 0
        var currentRound = 0
        val guardrail = top.hsyscn.opedrgent.utils.ToolCallGuardrail()

        try {
            while (currentRound < MAX_ROUNDS) {
                if (cancelled.get()) {
                    DebugLog.i("runLoop cancelled at round $currentRound")
                    return null
                }

                _state.value = _state.value.copy(currentRound = currentRound)

                val outcome = try {
                    executeOneRound(ctx, currentRound, guardrail)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DebugLog.e("executeOneRound error: ${e.message}", e)
                    LoopOutcome.Error(e.message ?: "未知错误")
                }

                when (outcome) {
                    is LoopOutcome.Continue -> {
                        retryCount = 0
                        currentRound++
                    }
                    is LoopOutcome.Break -> break
                    is LoopOutcome.Retry -> {
                        loopState = LoopState.RETRYING
                        retryCount++
                        if (retryCount > MAX_RETRIES) {
                            _state.value = _state.value.copy(error = "重试次数过多")
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
                        break
                    }
                }
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

    private suspend fun executeOneRound(ctx: LoopContext, round: Int, guardrail: top.hsyscn.opedrgent.utils.ToolCallGuardrail): LoopOutcome {
        val session = store.getSession(ctx.sessionId)
            ?: throw IllegalStateException("会话不存在: ${ctx.sessionId}")

        val system = ctx.systemPromptBuilder(session)

        val allMessages = session.messages + ctx.toolMessages
        val compressed = ContextCompressor.compress(allMessages, system, ctx.maxContextTokens)
        val compressedSystem = if (compressed.summary != null) {
            "$system\n\n${compressed.summary}"
        } else system

        val messages = compressed.messages

        DebugLog.d("executeOneRound: round=$round, messages=${messages.size}, tokens=${compressed.tokenCount}")

        _state.value = _state.value.copy(
            streamingPhase = if (round == 0) "思考中" else "继续思考",
        )

        // 流式 LLM 调用
        val streamResult = streamLlm(
            config = ctx.config,
            system = compressedSystem,
            messages = messages,
            tools = ctx.agentTools,
        )

        if (cancelled.get()) return LoopOutcome.Break

        // 处理 LLM 错误
        if (streamResult.error != null) {
            if (streamResult.content.isNotBlank()) {
                ctx.accumulatedText.append(
                    if (ctx.accumulatedText.isNotEmpty()) "\n\n" else ""
                ).append(streamResult.content)
            }
            return LoopOutcome.Error(streamResult.error ?: "未知错误")
        }

        // 累积文本
        if (streamResult.content.isNotBlank()) {
            ctx.accumulatedText.append(
                if (ctx.accumulatedText.isNotEmpty()) "\n\n" else ""
            ).append(streamResult.content)
        }

        // 没有工具调用 → 结束
        if (streamResult.toolCalls.isEmpty()) {
            return LoopOutcome.Break
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
                toolExecutor.executeToolByName(
                    tc.name,
                    parseToolArgs(tc.arguments),
                    ctx.config,
                )
            } catch (e: Exception) {
                "工具执行失败: ${e.message}"
            }

            val toolResultMsg = ChatMessage(
                role = Role.USER,
                content = result,
                createdAt = System.currentTimeMillis(),
                toolCallId = tc.id,
            )
            ctx.toolMessages.add(toolResultMsg)

            // Guardrail: 检测doom loop和重复失败
            val action = guardrail.record(
                toolName = tc.name,
                args = tc.arguments,
                result = result,
                success = !result.startsWith("工具执行失败"),
            )
            when (action) {
                top.hsyscn.opedrgent.utils.ToolCallGuardrail.Action.HALT,
                top.hsyscn.opedrgent.utils.ToolCallGuardrail.Action.BLOCK -> {
                    DebugLog.w("AgentService: guardrail ${action.name}, tool=${tc.name}")
                    _state.value = _state.value.copy(
                        streamingPhase = "[工具调用保护] 检测到重复模式，已自动停止",
                    )
                    return LoopOutcome.Break
                }
                top.hsyscn.opedrgent.utils.ToolCallGuardrail.Action.WARN -> {
                    DebugLog.w("AgentService: guardrail WARN, tool=${tc.name}")
                }
                top.hsyscn.opedrgent.utils.ToolCallGuardrail.Action.ALLOW -> {}
            }
        }

        return LoopOutcome.Continue
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
    ): InternalStreamResult {
        val contentBuf = StringBuilder()
        val reasoningBuf = StringBuilder()
        val deferred = CompletableDeferred<InternalStreamResult>()

        val call = llmClient.streamChatCompletions(
            config = config,
            system = system,
            messages = messages,
            tools = tools,
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
