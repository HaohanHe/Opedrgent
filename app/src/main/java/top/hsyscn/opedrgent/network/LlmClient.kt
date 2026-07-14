package top.hsyscn.opedrgent.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.IOException
import org.json.JSONArray
import org.json.JSONObject
import top.hsyscn.opedrgent.model.ChatMessage
import top.hsyscn.opedrgent.model.MultimodalContent
import top.hsyscn.opedrgent.model.MultimodalMessage
import top.hsyscn.opedrgent.model.Role
import top.hsyscn.opedrgent.settings.ApiConfig
import top.hsyscn.opedrgent.utils.DebugLog
import kotlin.coroutines.resume

sealed class StreamDelta {
    data class ReasoningDelta(val text: String) : StreamDelta()
    data class TextDelta(val text: String) : StreamDelta()
}

data class ToolCallDelta(
    val id: String,
    val index: Int = 0,
    val nameDelta: String = "",
    val argsDelta: String = "",
)

data class StreamResult(
    val content: String,
    val reasoning: String = "",
    val toolCalls: List<CompletedToolCall> = emptyList(),
    val finishReason: String? = null,
    val isSafetyFiltered: Boolean = false,
    // Prompt cache usage (供 PromptCacheBreakDetection Phase 2 使用)
    // DeepSeek: prompt_cache_hit_tokens / prompt_cache_creation_tokens
    // Anthropic: cache_read_input_tokens / cache_creation_input_tokens
    val cacheReadTokens: Int = 0,
    val cacheCreationTokens: Int = 0,
)

data class CompletedToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: JSONObject,
)

class LlmClient(private val http: OkHttpClient = HttpClients.streaming) {

    companion object {
        private val MULTIMODAL_MODELS = setOf(
            "mimo-v2.5",
            // 阶跃星辰视觉理解模型 — 支持图像/视频多模态输入
            "step-1o-turbo-vision",
        )
        private val WEB_SEARCH_MODELS = setOf(
            "mimo-v2.5-pro", "mimo-v2.5",
        )
        private val TTS_MODELS = setOf(
            "mimo-v2.5-tts", "mimo-v2.5-tts-voicedesign", "mimo-v2.5-tts-voiceclone",
        )
        private val THINKING_MODELS = setOf(
            "mimo-v2.5-pro", "mimo-v2.5", "mimo-v2-flash", "mimo-v2",
            "gemini-2.5",
            "deepseek-reasoner", "deepseek-v4-flash", "deepseek-v4-pro",
            // 阶跃星辰 Step Plan — 支持三档推理强度 low/medium/high
            "step-3.7-flash", "step-3.5-flash", "step-3.5-flash-2603",
            // Qwen3.5 系列 — SiliconFlow enable_thinking
            "qwen3.5",
        )
        private val DEEPSEEK_MODELS = setOf(
            "deepseek-v4-pro", "deepseek-v4-flash", "deepseek-v4",
            "deepseek-chat", "deepseek-reasoner",
        )
        // Step Plan 智能路由模型（自动在 deepseek-v4-pro / step-3.5-flash 间切换）
        private val JSON_MODE_MODELS = setOf(
            // 阶跃星辰全系列模型支持 JSON Mode
            "step-3.7-flash", "step-3.5-flash", "step-3.5-flash-2603",
            "step-1o-turbo-vision", "step-1x-medium", "step-2x-large",
            "step-image-edit-2", "step-1x-edit", "stepaudio-2.5-realtime",
        )
        private val STEP_PLAN_MODELS = setOf(
            "step-3.7-flash", "step-3.5-flash", "step-3.5-flash-2603", "step-router-v1",
        )
        private const val DEEPSEEK_MAX_CONTEXT = 1_000_000
        private const val MESSAGES_API_MAX_OUTPUT_TOKENS = 16384
        private const val NATIVE_SEARCH_TEMPERATURE = 0.3
        private val MIMO_THINKING_MODELS = setOf(
            "mimo-v2.5-pro", "mimo-v2.5", "mimo-v2-flash", "mimo-v2",
        )

        fun isMultimodalModel(model: String): Boolean {
            return MULTIMODAL_MODELS.any { model.contains(it) }
        }

        fun isWebSearchModel(model: String): Boolean {
            return WEB_SEARCH_MODELS.any { model.contains(it) }
        }

        fun isTtsModel(model: String): Boolean {
            return TTS_MODELS.any { model.contains(it) }
        }

        fun isThinkingModel(model: String): Boolean {
            return THINKING_MODELS.any { model.contains(it, ignoreCase = true) }
        }

        /**
         * Thinking 模型思维链 token 预算（SiliconFlow enable_thinking 的 thinking_budget）。
         * 默认 4096，可由外部覆盖（如 MainViewModel 从 ApiSettings 读取用户配置）。
         * 0 表示不限制（部分模型支持）。
         */
        @Volatile
        var thinkingBudget: Int = 4096

        fun isDeepSeek(model: String): Boolean {
            return DEEPSEEK_MODELS.any { model.contains(it, ignoreCase = true) }
        }

        fun isStepPlan(model: String): Boolean {
            return STEP_PLAN_MODELS.any { model.contains(it, ignoreCase = true) }
        }

        /** Qwen3.5 系列使用 enable_thinking 而非 thinking: {"type": "enabled"} */
        fun isQwenThinkingModel(model: String): Boolean {
            return model.contains("qwen3.5", ignoreCase = true)
        }

        /**
         * SiliconFlow 的 Qwen 模型不支持 role:"tool" 和 tool_calls，
         * 需要把工具结果转为 user 消息，去掉 assistant 的 tool_calls。
         */
        fun supportsNativeToolRole(model: String, baseUrl: String): Boolean {
            if (baseUrl.contains("siliconflow", ignoreCase = true)
                && model.contains("qwen", ignoreCase = true)) {
                return false
            }
            return true
        }

        fun getDeepSeekMaxContext(): Int = DEEPSEEK_MAX_CONTEXT

        fun isMiMoThinking(model: String): Boolean {
            return MIMO_THINKING_MODELS.any { model.contains(it, ignoreCase = true) }
        }

        fun requiresDefaultReasoning(model: String): Boolean {
            return isDeepSeek(model) || isMiMoThinking(model) || isStepPlan(model)
        }

        /** 模型是否支持 JSON Mode (response_format: json_object) */
        fun supportsJsonMode(model: String): Boolean {
            return JSON_MODE_MODELS.any { model.contains(it, ignoreCase = true) }
                || isStepPlan(model)  // Step Plan 全系列支持
        }
    }

    private fun buildUrl(baseUrl: String, path: String): String {
        val base = baseUrl.trimEnd('/')
        return if (base.endsWith("/v1") || base.endsWith("/v2") || base.endsWith("/v3")
            || base.endsWith("/openai")) {
            "$base$path"
        } else {
            "$base/v1$path"
        }
    }

    private fun buildRequest(url: String, body: String, apiKey: String): Request {
        val reqBuilder = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
        when {
            apiKey.startsWith("tp-") -> reqBuilder.header("api-key", apiKey)
            apiKey.startsWith("AIza") -> reqBuilder.header("x-goog-api-key", apiKey)
            else -> reqBuilder.header("Authorization", "Bearer $apiKey")
        }
        return reqBuilder.build()
    }

    private fun roleToApi(role: Role): String {
        return when (role) {
            Role.SYSTEM -> "system"
            Role.USER -> "user"
            Role.ASSISTANT -> "assistant"
        }
    }

    fun toolToJson(tool: ToolDefinition): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", tool.name)
                put("description", tool.description)
                put("parameters", tool.parameters)
            })
        }
    }

    suspend fun streamChatCompletions(
        config: ApiConfig,
        system: String,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition> = emptyList(),
        toolChoice: String? = null,
        thinkingEnabled: Boolean = false,
        jsonMode: Boolean = false,  // JSON Mode: 强制模型返回合法 JSON
        maxOutputTokens: Int = 0,
        sessionId: String? = null,
        requestParams: Map<String, Any> = emptyMap(),
        onDelta: (StreamDelta) -> Unit,
        onToolCallDelta: ((ToolCallDelta) -> Unit)? = null,
        onDone: (StreamResult) -> Unit,
        onError: (String) -> Unit,
    ): Call = withContext(Dispatchers.IO) {
        val url = buildUrl(config.baseUrl, "/chat/completions")
        val msgCount = messages.size
        val toolsCount = tools.size
        DebugLog.i("streamChatCompletions → $url model=${config.model} msgs=$msgCount tools=$toolsCount thinking=$thinkingEnabled")
        DebugLog.d("streamChatCompletions: system=${system.length} chars")

        val json = JSONObject().apply {
            put("model", config.model)
            if (maxOutputTokens > 0) {
                put("max_tokens", maxOutputTokens)
            }
            put("stream", true)
            // ★ Phase 3 P2: DeepSeek cache 轻量检测 — 请求末尾 usage chunk
            // DeepSeek 在 stream 末尾发送一个含 usage 字段的 chunk，可用于观测 prompt cache 命中率
            if (isDeepSeek(config.model)) {
                put("stream_options", JSONObject().apply {
                    put("include_usage", true)
                })
            }
            val nativeToolRole = supportsNativeToolRole(config.model, config.baseUrl)
            put(
                "messages",
                JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", system))
                    messages.forEach { m ->
                        if (m.toolCallId != null) {
                            if (nativeToolRole) {
                                put(JSONObject().apply {
                                    put("role", "tool")
                                    put("tool_call_id", m.toolCallId)
                                    put("content", m.content)
                                })
                            } else {
                                // SiliconFlow Qwen: tool 结果转为 user 消息
                                put(JSONObject().apply {
                                    put("role", "user")
                                    put("content", "[工具结果 ${m.toolCallId}]\n${m.content}")
                                })
                            }
                        } else if (m.apiToolCallsJson != null) {
                            if (nativeToolRole) {
                                val assistantMsg = JSONObject().apply {
                                    put("role", "assistant")
                                    put("content", m.content.ifEmpty { "" })
                                    put("tool_calls", JSONArray(m.apiToolCallsJson))
                                }
                                val reasoningText = m.reasoningParts.joinToString("\n") { it.text }
                                if (reasoningText.isNotEmpty()) assistantMsg.put("reasoning_content", reasoningText)
                                put(assistantMsg)
                            } else {
                                // SiliconFlow Qwen: 去掉 tool_calls，只保留 content
                                val assistantMsg = JSONObject().apply {
                                    put("role", "assistant")
                                    put("content", m.content.ifEmpty { "" })
                                }
                                val reasoningText = m.reasoningParts.joinToString("\n") { it.text }
                                if (reasoningText.isNotEmpty()) assistantMsg.put("reasoning_content", reasoningText)
                                put(assistantMsg)
                            }
                        } else {
                            put(JSONObject().put("role", roleToApi(m.role)).put("content", m.content))
                        }
                    }
                },
            )
            val isDS = isDeepSeek(config.model)
            val isSP = isStepPlan(config.model)
            val isQwen = isQwenThinkingModel(config.model)
            val defaultReasoning = requiresDefaultReasoning(config.model)
            val isSiliconFlow = config.baseUrl.contains("siliconflow", ignoreCase = true)
            if (isThinkingModel(config.model)) {
                if (thinkingEnabled || defaultReasoning) {
                    if (isSiliconFlow) {
                        // SiliconFlow: enable_thinking + thinking_budget（官方文档格式）
                        // thinking_budget 限制思维链长度，防止模型无限思考导致超时
                        put("enable_thinking", true)
                        put("thinking_budget", thinkingBudget)
                    } else {
                        // 其他平台（DeepSeek 官方、StepFun 等）
                        if (isDS) {
                            put("reasoning_effort", if (tools.isNotEmpty()) "max" else "high")
                        } else if (isSP) {
                            put("reasoning_effort", "medium")
                            put("reasoning_format", "deepseek-style")
                        }
                        if (isQwen) {
                            put("enable_thinking", true)
                        } else {
                            put("thinking", JSONObject().apply { put("type", "enabled") })
                        }
                    }
                } else {
                    if (isSiliconFlow) {
                        put("enable_thinking", false)
                    } else {
                        if (isDS) {
                            put("thinking_mode", "non-thinking")
                        } else if (isQwen) {
                            put("enable_thinking", false)
                        } else {
                            put("thinking", JSONObject().apply { put("type", "disabled") })
                        }
                    }
                }
            }
            if (tools.isNotEmpty()) {
                val isReflection = toolChoice == "none"
                if (isReflection && isDS) {
                    // DeepSeek 等模型不支持 tool_choice，通过不提供 tools 强制无工具调用
                    DebugLog.d("streamChatCompletions: reflection mode for DeepSeek/unsupported model, omitting tools")
                } else {
                    put("tools", JSONArray().apply {
                        tools.forEach { put(toolToJson(it)) }
                    })
                    if (toolChoice != null) {
                        put("tool_choice", toolChoice)
                    } else if (!isDS) {
                        put("tool_choice", "auto")
                    }
                }
            }
            // JSON Mode: 强制模型返回合法 JSON（适用于结构化数据提取场景）
            // 注意: JSON Mode 与 tools 不能同时使用（会冲突），优先保证 tool calling
            if (jsonMode && tools.isEmpty() && supportsJsonMode(config.model)) {
                put("response_format", JSONObject().apply { put("type", "json_object") })
            }
        }

        // ★ Prompt Cache Break Detection Phase 1: pre-call 记录 prompt 指纹
        var cacheState: PromptCacheState? = null
        // P1-b 修复：仅对支持 cache 统计的模型记录指纹，避免不支持模型（cacheReadTokens 恒为 0）浪费内存且 Phase 2 沦为死代码
        if (sessionId != null && PromptCacheBreakDetection.isModelSupported(config.model)) {
            try {
                val params = buildMap<String, Any> {
                    if (maxOutputTokens > 0) put("max_tokens", maxOutputTokens)
                    put("thinking_enabled", thinkingEnabled)
                    put("tools_count", tools.size)
                    if (toolChoice != null) put("tool_choice", toolChoice)
                    put("json_mode", jsonMode)
                    putAll(requestParams)
                }
                cacheState = PromptCacheBreakDetection.recordPromptState(
                    sessionId = sessionId,
                    messages = messages,
                    systemPrompt = system,
                    model = config.model,
                    apiEndpoint = url,
                    requestParams = params,
                )
            } catch (e: Exception) {
                DebugLog.w("PromptCacheBreakDetection", "Phase1 recordPromptState failed: ${e.message}")
            }
        } else if (sessionId != null) {
            DebugLog.d("PromptCacheBreakDetection", "模型 ${config.model} 不支持 cache 统计，检测跳过")
        }

        val req = buildRequest(url, json.toString(), config.apiKey)
        val maskedBody = json.toString().replace(config.apiKey, "***")
        DebugLog.d("LlmClient REQUEST: model=${config.model} thinking=$thinkingEnabled body=$maskedBody")
        val call = http.newCall(req)

        // ★ Prompt Cache Break Detection Phase 2: wrap onDone to verify cacheReadTokens after response
        val phase1CacheState = cacheState
        val phase1SessionId = sessionId
        val wrappedOnDone: (StreamResult) -> Unit = { result ->
            if (phase1SessionId != null && phase1CacheState != null && result.cacheReadTokens > 0) {
                try {
                    PromptCacheBreakDetection.checkResponseForCacheBreak(
                        sessionId = phase1SessionId,
                        currentState = phase1CacheState,
                        cacheReadTokens = result.cacheReadTokens,
                    )
                } catch (e: Exception) {
                    DebugLog.w("PromptCacheBreakDetection", "Phase2 checkResponseForCacheBreak failed: ${e.message}")
                }
            }
            onDone(result)
        }

        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: IOException) {
                DebugLog.e("streamChatCompletions FAILED: ${e.message}", e)
                onError(e.message ?: "连接失败")
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                try {
                    if (!response.isSuccessful) {
                        val raw = response.body?.string().orEmpty()
                        val msg = runCatching {
                            val json = JSONObject(raw)
                            // OpenAI 格式: {"error": {"message": "..."}}
                            // SiliconFlow 格式: {"code": xxx, "message": "..."}
                            json.optJSONObject("error")?.optString("message")
                                ?: json.optString("message").takeIf { it.isNotBlank() }
                        }.getOrNull()
                        DebugLog.e("streamChatCompletions HTTP ${response.code}: ${raw.take(500)}")
                        onError(msg?.takeIf { it.isNotBlank() } ?: "请求失败: HTTP ${response.code}")
                        return
                    }

                    parseSseStream(response, onDelta, onToolCallDelta, wrappedOnDone, onError, config.model)
                } catch (e: Exception) {
                    DebugLog.e("streamChatCompletions stream error: ${e.message}", e)
                    onError(e.message ?: "流读取失败")
                } finally {
                    response.close()
                }
            }
        })
        call
    }

    private fun parseSseStream(
        response: okhttp3.Response,
        onDelta: (StreamDelta) -> Unit,
        onToolCallDelta: ((ToolCallDelta) -> Unit)? = null,
        onDone: (StreamResult) -> Unit,
        onError: (String) -> Unit,
        modelName: String = "",
    ) {
        val source = response.body?.source() ?: run {
            onError("响应体为空")
            return
        }

        val fullContent = StringBuilder()
        val fullReasoning = StringBuilder()
        val toolCallMap = mutableMapOf<Int, StringBuilder>()
        val toolArgMap = mutableMapOf<Int, StringBuilder>()
        val toolIdMap = mutableMapOf<Int, String>()
        var inThinkingTag = false
        var lastFinishReason: String? = null
        var totalChunks = 0
        var pendingBuffer = ""
        // ★ Prompt Cache Break Detection: 捕获 usage chunk 中的 cache token 计数
        var capturedCacheReadTokens = 0
        var capturedCacheCreationTokens = 0

        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: continue
            if (line.isEmpty()) continue
            if (!line.startsWith("data: ")) continue

            val data = line.removePrefix("data: ").trim()
            if (data == "[DONE]") {
                DebugLog.i("parseSse ← DONE, text=${fullContent.length} chars, reasoning=${fullReasoning.length} chars, tools=${toolCallMap.size}")
                break
            }

            runCatching {
                val root = JSONObject(data)
                // ★ Phase 3 P2: DeepSeek cache 轻量检测 — 解析末尾 usage chunk
                // DeepSeek 在 stream 末尾发送含 usage 字段的 chunk（通常无 choices），用于观测 prompt cache 命中率
                val usage = root.optJSONObject("usage")
                if (usage != null) {
                    val promptTokens = usage.optInt("prompt_tokens", 0)
                    val cacheHitTokens = usage.optInt("prompt_cache_hit_tokens", 0)
                    val cacheCreationTokens = usage.optInt("prompt_cache_creation_tokens", 0)
                    val completionTokens = usage.optInt("completion_tokens", 0)
                    // 捕获到本地变量，供 StreamResult 携带（Phase 2 比对用）
                    capturedCacheReadTokens = cacheHitTokens
                    capturedCacheCreationTokens = cacheCreationTokens
                    if (promptTokens > 0 || cacheHitTokens > 0) {
                        val hitRate = if (promptTokens + cacheHitTokens > 0) {
                            cacheHitTokens.toDouble() / (promptTokens + cacheHitTokens) * 100
                        } else 0.0
                        DebugLog.i("LlmClient.cache: model=$modelName prompt=$promptTokens cacheHit=$cacheHitTokens cacheCreation=$cacheCreationTokens completion=$completionTokens hitRate=${"%.1f".format(hitRate)}%")
                    }
                }
                val choices = root.optJSONArray("choices") ?: return@runCatching
                if (choices.length() == 0) return@runCatching
                val choice = choices.getJSONObject(0)
                val delta = choice.optJSONObject("delta") ?: return@runCatching

                val finishReason = choice.optString("finish_reason").takeIf { it.isNotEmpty() }
                if (finishReason != null && finishReason != "null") {
                    lastFinishReason = finishReason
                }

                val content = delta.optString("content", "")
                    .let { if (it == "null") "" else it }
                val reasoning = delta.optString("reasoning_content", "")
                    .let { if (it == "null") "" else it }
                    .ifEmpty { delta.optString("reasoning", "").let { if (it == "null") "" else it } }

                if (content.isNotEmpty() || reasoning.isNotEmpty()) {
                    DebugLog.d("SSE chunk: content=${content.take(40)}... reasoning=${reasoning.take(40)}... finish=$finishReason")
                }
                totalChunks++

                val combined = pendingBuffer + content
                val tags = listOf("<thinking>", "</thinking>")
                var prefix = ""
                for (tag in tags) {
                    for (i in 1 until tag.length) {
                        if (combined.endsWith(tag.substring(0, i)) && i > prefix.length) {
                            prefix = tag.substring(0, i)
                        }
                    }
                }
                val effectiveContent = if (prefix.isNotEmpty()) {
                    pendingBuffer = prefix
                    combined.dropLast(prefix.length)
                } else {
                    pendingBuffer = ""
                    combined
                }
                var remainingContent = effectiveContent

                if (inThinkingTag) {
                    val endTagIdx = remainingContent.indexOf("</thinking>")
                    if (endTagIdx >= 0) {
                        val thinkChunk = remainingContent.substring(0, endTagIdx)
                        fullReasoning.append(thinkChunk)
                        onDelta(StreamDelta.ReasoningDelta(thinkChunk))
                        inThinkingTag = false
                        remainingContent = remainingContent.substring(endTagIdx + "</thinking>".length)
                    } else {
                        fullReasoning.append(remainingContent)
                        onDelta(StreamDelta.ReasoningDelta(remainingContent))
                        remainingContent = ""
                    }
                }

                while (remainingContent.isNotEmpty()) {
                    val startTagIdx = remainingContent.indexOf("<thinking>")
                    val endTagIdx = remainingContent.indexOf("</thinking>")

                    when {
                        startTagIdx >= 0 && (endTagIdx < 0 || startTagIdx < endTagIdx) -> {
                            val beforeThink = remainingContent.substring(0, startTagIdx)
                            if (beforeThink.isNotEmpty()) {
                                fullContent.append(beforeThink)
                                onDelta(StreamDelta.TextDelta(beforeThink))
                            }
                            remainingContent = remainingContent.substring(startTagIdx + "<thinking>".length)
                            inThinkingTag = true
                        }
                        endTagIdx >= 0 -> {
                            val thinkChunk = remainingContent.substring(0, endTagIdx)
                            fullReasoning.append(thinkChunk)
                            onDelta(StreamDelta.ReasoningDelta(thinkChunk))
                            inThinkingTag = false
                            remainingContent = remainingContent.substring(endTagIdx + "</thinking>".length)
                        }
                        else -> {
                            fullContent.append(remainingContent)
                            onDelta(StreamDelta.TextDelta(remainingContent))
                            remainingContent = ""
                        }
                    }
                }

                if (reasoning.isNotEmpty()) {
                    fullReasoning.append(reasoning)
                    onDelta(StreamDelta.ReasoningDelta(reasoning))
                }

                val tcArray = delta.optJSONArray("tool_calls")
                if (tcArray != null) {
                    for (i in 0 until tcArray.length()) {
                        val tc = tcArray.getJSONObject(i)
                        val idx = tc.optInt("index", 0)
                        val tcId = tc.optString("id", "")
                        if (tcId.isNotEmpty() && !toolIdMap.containsKey(idx)) {
                            toolIdMap[idx] = tcId
                        }
                        val fn = tc.optJSONObject("function") ?: continue
                        val nameDelta = fn.optString("name", "")
                            .let { if (it == "null") "" else it }
                        val argsDelta = fn.optString("arguments", "")
                            .let { if (it == "null") "" else it }

                        if (nameDelta.isNotEmpty()) {
                            toolCallMap.getOrPut(idx) { StringBuilder() }.append(nameDelta)
                        }
                        if (argsDelta.isNotEmpty()) {
                            toolArgMap.getOrPut(idx) { StringBuilder() }.append(argsDelta)
                        }

                        onToolCallDelta?.invoke(
                            ToolCallDelta(
                                id = toolIdMap[idx]?.ifEmpty { "call_${idx}" } ?: "call_${idx}",
                                index = idx,
                                nameDelta = nameDelta,
                                argsDelta = argsDelta,
                            )
                        )
                    }
                }
            }.onFailure { e ->
                DebugLog.w("parseSse parse error: ${e.message}")
            }
        }

        if (pendingBuffer.isNotEmpty()) {
            if (inThinkingTag) {
                fullReasoning.append(pendingBuffer)
                onDelta(StreamDelta.ReasoningDelta(pendingBuffer))
            } else {
                fullContent.append(pendingBuffer)
                onDelta(StreamDelta.TextDelta(pendingBuffer))
            }
            pendingBuffer = ""
        }

        if (totalChunks == 0) {
            DebugLog.w("parseSse: ZERO chunks received! Stream was empty or all parse failures")
        }

        val toolCalls = toolCallMap.map { (idx, nameBuilder) ->
            CompletedToolCall(
                id = toolIdMap[idx]?.ifEmpty { "call_${idx}_${System.currentTimeMillis()}" } ?: "call_${idx}_${System.currentTimeMillis()}",
                name = nameBuilder.toString().trim(),
                arguments = toolArgMap[idx]?.toString().orEmpty().trim(),
            )
        }

        val finalContent = fullContent.toString()
        val finalReasoning = fullReasoning.toString()
        if (finalContent.isBlank() && finalReasoning.isNotBlank()) {
            DebugLog.w("LlmClient: content empty but reasoning=${finalReasoning.take(100)}... (NOT leaking reasoning as content)")
        }
        if (finalContent.isBlank() && finalReasoning.isBlank() && toolCalls.isEmpty()) {
            DebugLog.w("LlmClient: completely empty response!")
        }

        onDone(StreamResult(
            content = finalContent,
            reasoning = finalReasoning,
            toolCalls = toolCalls,
            finishReason = lastFinishReason,
            isSafetyFiltered = lastFinishReason?.equals("SAFETY", ignoreCase = true) == true,
            cacheReadTokens = capturedCacheReadTokens,
            cacheCreationTokens = capturedCacheCreationTokens,
        ))
    }

    suspend fun streamMultimodalChatCompletions(
        config: ApiConfig,
        system: String,
        messages: List<ChatMessage>,
        extraImages: List<String> = emptyList(),
        tools: List<ToolDefinition> = emptyList(),
        thinkingEnabled: Boolean = false,
        sessionId: String? = null,
        requestParams: Map<String, Any> = emptyMap(),
        onDelta: (StreamDelta) -> Unit,
        onToolCallDelta: ((ToolCallDelta) -> Unit)? = null,
        onDone: (StreamResult) -> Unit,
        onError: (String) -> Unit,
    ): Call = withContext(Dispatchers.IO) {
        val url = buildUrl(config.baseUrl, "/chat/completions")
        DebugLog.i("streamMultimodalChatCompletions → $url model=${config.model} msgs=${messages.size} images=${extraImages.size} tools=${tools.size}")

        val json = JSONObject().apply {
            put("model", config.model)
            put("stream", true)
            put(
                "messages",
                JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", system))
                    messages.forEachIndexed { idx, m ->
                        if (m.toolCallId != null) {
                            put(JSONObject().apply {
                                put("role", "tool")
                                put("tool_call_id", m.toolCallId)
                                put("content", m.content)
                            })
                        } else if (m.apiToolCallsJson != null) {
                            put(JSONObject().apply {
                                put("role", "assistant")
                                put("content", m.content.ifEmpty { "" })
                                put("tool_calls", JSONArray(m.apiToolCallsJson))
                                val reasoningText = m.reasoningParts.joinToString("\n") { it.text }
                                if (reasoningText.isNotEmpty()) put("reasoning_content", reasoningText)
                            })
                        } else {
                            val isLastUser = (idx == messages.lastIndex && m.role == Role.USER)
                            if (isLastUser && extraImages.isNotEmpty()) {
                                val contentArr = JSONArray()
                                contentArr.put(JSONObject().put("type", "text").put("text", m.content))
                                extraImages.forEach { b64 ->
                                    contentArr.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", b64)))
                                }
                                put(JSONObject().put("role", roleToApi(m.role)).put("content", contentArr))
                            } else {
                                put(JSONObject().put("role", roleToApi(m.role)).put("content", m.content))
                            }
                        }
                    }
                },
            )
            val isDS = isDeepSeek(config.model)
            val isSP = isStepPlan(config.model)
            val defaultReasoning = requiresDefaultReasoning(config.model)
            val isSiliconFlow = config.baseUrl.contains("siliconflow", ignoreCase = true)
            if (isThinkingModel(config.model)) {
                if (thinkingEnabled || defaultReasoning) {
                    if (isSiliconFlow) {
                        put("enable_thinking", true)
                        put("thinking_budget", thinkingBudget)
                    } else {
                        if (isDS) {
                            put("reasoning_effort", if (tools.isNotEmpty()) "max" else "high")
                        } else if (isSP) {
                            put("reasoning_effort", "medium")
                            put("reasoning_format", "deepseek-style")
                        }
                        put("thinking", JSONObject().apply { put("type", "enabled") })
                    }
                } else {
                    if (isSiliconFlow) {
                        put("enable_thinking", false)
                    } else {
                        if (isDS) {
                            put("thinking_mode", "non-thinking")
                        } else {
                            put("thinking", JSONObject().apply { put("type", "disabled") })
                        }
                    }
                }
            }
            if (tools.isNotEmpty()) {
                put("tools", JSONArray().apply { tools.forEach { put(toolToJson(it)) } })
                if (!isDS) {
                    put("tool_choice", "auto")
                }
            }
        }

        // ★ Prompt Cache Break Detection Phase 1: pre-call 记录 prompt 指纹
        var cacheState: PromptCacheState? = null
        // P1-b 修复：仅对支持 cache 统计的模型记录指纹，避免不支持模型（cacheReadTokens 恒为 0）浪费内存且 Phase 2 沦为死代码
        if (sessionId != null && PromptCacheBreakDetection.isModelSupported(config.model)) {
            try {
                val params = buildMap<String, Any> {
                    put("thinking_enabled", thinkingEnabled)
                    put("tools_count", tools.size)
                    put("images_count", extraImages.size)
                    putAll(requestParams)
                }
                cacheState = PromptCacheBreakDetection.recordPromptState(
                    sessionId = sessionId,
                    messages = messages,
                    systemPrompt = system,
                    model = config.model,
                    apiEndpoint = url,
                    requestParams = params,
                )
            } catch (e: Exception) {
                DebugLog.w("PromptCacheBreakDetection", "Phase1 recordPromptState (multimodal) failed: ${e.message}")
            }
        } else if (sessionId != null) {
            DebugLog.d("PromptCacheBreakDetection", "模型 ${config.model} 不支持 cache 统计，检测跳过")
        }

        val req = buildRequest(url, json.toString(), config.apiKey)
        DebugLog.d("LlmClient streamMultimodal: model=${config.model} url=$url thinking=$thinkingEnabled images=${extraImages.size} tools=${tools.size}")
        val call = http.newCall(req)

        // ★ Prompt Cache Break Detection Phase 2: wrap onDone to verify cacheReadTokens after response
        val phase1CacheState = cacheState
        val phase1SessionId = sessionId
        val wrappedOnDone: (StreamResult) -> Unit = { result ->
            if (phase1SessionId != null && phase1CacheState != null && result.cacheReadTokens > 0) {
                try {
                    PromptCacheBreakDetection.checkResponseForCacheBreak(
                        sessionId = phase1SessionId,
                        currentState = phase1CacheState,
                        cacheReadTokens = result.cacheReadTokens,
                    )
                } catch (e: Exception) {
                    DebugLog.w("PromptCacheBreakDetection", "Phase2 checkResponseForCacheBreak (multimodal) failed: ${e.message}")
                }
            }
            onDone(result)
        }

        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: IOException) {
                DebugLog.e("streamMultimodal FAILED: ${e.message}", e)
                onError(e.message ?: "连接失败")
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                try {
                    if (!response.isSuccessful) {
                        val raw = response.body?.string().orEmpty()
                        val msg = runCatching { org.json.JSONObject(raw).optString("error", raw) }.getOrDefault(raw)
                        onError(msg)
                        return
                    }

                    parseSseStream(response, onDelta, onToolCallDelta, wrappedOnDone, onError, config.model)
                } catch (e: Exception) {
                    DebugLog.e("streamMultimodal parse error: ${e.message}", e)
                    onError(e.message ?: "解析失败")
                } finally {
                    response.close()
                }
            }
        })
        call
    }

    fun chatCompletions(
        config: ApiConfig,
        system: String,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition> = emptyList(),
    ): String {
        val url = buildUrl(config.baseUrl, "/chat/completions")
        DebugLog.i("chatCompletions → $url model=${config.model} msgs=${messages.size} tools=${tools.size}")

        val json = JSONObject()
        json.put("model", config.model)
        json.put(
            "messages",
            JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", system))
                messages.forEach { m ->
                    if (m.toolCallId != null) {
                        put(JSONObject().apply {
                            put("role", "tool")
                            put("tool_call_id", m.toolCallId)
                            put("content", m.content)
                        })
                    } else if (m.apiToolCallsJson != null) {
                        put(JSONObject().apply {
                            put("role", "assistant")
                            put("content", m.content.ifEmpty { "" })
                            put("tool_calls", JSONArray(m.apiToolCallsJson))
                            val reasoningText = m.reasoningParts.joinToString("\n") { it.text }
                            if (reasoningText.isNotEmpty()) put("reasoning_content", reasoningText)
                        })
                    } else {
                        put(JSONObject().put("role", roleToApi(m.role)).put("content", m.content))
                    }
                }
            },
        )
        if (tools.isNotEmpty()) {
            json.put("tools", JSONArray().apply {
                tools.forEach { put(toolToJson(it)) }
            })
            if (!isDeepSeek(config.model)) {
                json.put("tool_choice", "auto")
            }
        }

        val req = buildRequest(url, json.toString(), config.apiKey)
        http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching {
                    JSONObject(raw).optJSONObject("error")?.optString("message")
                }.getOrNull()
                throw IllegalStateException(msg?.takeIf { it.isNotBlank() } ?: "请求失败: HTTP ${resp.code}")
            }
            val content = runCatching {
                val root = JSONObject(raw)
                val choice = root.getJSONArray("choices").getJSONObject(0)
                val message = choice.getJSONObject("message")
                message.optString("content", "")
            }.getOrNull()
            DebugLog.i("chatCompletions ← ${(content ?: "").length} chars")
            return content?.trim().orEmpty()
        }
    }

    /**
     * 非流式对话，返回完整结果（含 tool_calls）。
     * 供 MultiAgentOrchestrator 等需要工具调用循环的场景使用。
     *
     * P2-3 修复：增加 [sessionId] 参数，对支持 cache 统计的模型（DeepSeek/Claude）启用
     * Phase 1/2 cache 破坏检测，使 AgentSwarm 等子 agent 调用也纳入监控（与流式路径一致）。
     */
    fun chatCompletionsWithTools(
        config: ApiConfig,
        system: String,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition> = emptyList(),
        sessionId: String? = null,
    ): StreamResult {
        val url = buildUrl(config.baseUrl, "/chat/completions")
        DebugLog.i("chatCompletionsWithTools → $url model=${config.model} msgs=${messages.size} tools=${tools.size}")

        val json = JSONObject().apply {
            put("model", config.model)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", system))
                messages.forEach { m ->
                    if (m.toolCallId != null) {
                        put(JSONObject().apply {
                            put("role", "tool")
                            put("tool_call_id", m.toolCallId)
                            put("content", m.content)
                        })
                    } else if (m.apiToolCallsJson != null) {
                        put(JSONObject().apply {
                            put("role", "assistant")
                            put("content", m.content.ifEmpty { "" })
                            put("tool_calls", JSONArray(m.apiToolCallsJson))
                            // 回传上一轮思维链，保证 thinking 模型多轮工具调用上下文完整
                            val reasoningText = m.reasoningParts.joinToString("\n") { it.text }
                            if (reasoningText.isNotEmpty()) put("reasoning_content", reasoningText)
                        })
                    } else {
                        put(JSONObject().put("role", roleToApi(m.role)).put("content", m.content))
                    }
                }
            })
            if (tools.isNotEmpty()) {
                put("tools", JSONArray().apply {
                    tools.forEach { put(toolToJson(it)) }
                })
                if (!isDeepSeek(config.model)) {
                    put("tool_choice", "auto")
                }
            }
        }

        // ★ Prompt Cache Break Detection Phase 1: pre-call 记录 prompt 指纹
        var cacheState: PromptCacheState? = null
        if (sessionId != null && PromptCacheBreakDetection.isModelSupported(config.model)) {
            try {
                cacheState = PromptCacheBreakDetection.recordPromptState(
                    sessionId = sessionId,
                    messages = messages,
                    systemPrompt = system,
                    model = config.model,
                    apiEndpoint = url,
                    requestParams = mapOf(
                        "tools_count" to tools.size,
                    ),
                )
            } catch (e: Exception) {
                DebugLog.w("PromptCacheBreakDetection", "Phase1 recordPromptState (withTools) failed: ${e.message}")
            }
        }

        val req = buildRequest(url, json.toString(), config.apiKey)
        http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching {
                    JSONObject(raw).optJSONObject("error")?.optString("message")
                }.getOrNull()
                throw IllegalStateException(msg?.takeIf { it.isNotBlank() } ?: "请求失败: HTTP ${resp.code}")
            }
            val root = JSONObject(raw)
            val choice = root.getJSONArray("choices").getJSONObject(0)
            val message = choice.getJSONObject("message")
            val content = message.optString("content", "")
            // 提取思维链（SiliconFlow / DeepSeek / Qwen3 thinking 模型在非流式响应中通过 reasoning_content 字段返回）
            val reasoning = message.optString("reasoning_content", "")
            val toolCalls = mutableListOf<CompletedToolCall>()
            if (message.has("tool_calls")) {
                val arr = message.getJSONArray("tool_calls")
                for (i in 0 until arr.length()) {
                    val tc = arr.getJSONObject(i)
                    val fn = tc.getJSONObject("function")
                    toolCalls.add(CompletedToolCall(
                        id = tc.optString("id", "call_$i"),
                        name = fn.optString("name", ""),
                        arguments = fn.optString("arguments", "{}"),
                    ))
                }
            }
            // ★ Prompt Cache Break Detection: 解析 usage 中的 cache token 计数
            // DeepSeek: prompt_cache_hit_tokens / prompt_cache_creation_tokens
            // Anthropic (OpenAI-compatible 端点): cache_read_input_tokens / cache_creation_input_tokens
            var cacheReadTokens = 0
            var cacheCreationTokens = 0
            val usage = root.optJSONObject("usage")
            if (usage != null) {
                cacheReadTokens = usage.optInt("prompt_cache_hit_tokens", 0)
                    .let { if (it > 0) it else usage.optInt("cache_read_input_tokens", 0) }
                cacheCreationTokens = usage.optInt("prompt_cache_creation_tokens", 0)
                    .let { if (it > 0) it else usage.optInt("cache_creation_input_tokens", 0) }
            }
            val result = StreamResult(
                content = content.trim(),
                reasoning = reasoning,
                toolCalls = toolCalls,
                finishReason = choice.optString("finish_reason", "").ifEmpty { null },
                cacheReadTokens = cacheReadTokens,
                cacheCreationTokens = cacheCreationTokens,
            )
            // ★ Prompt Cache Break Detection Phase 2: post-call 比对 cacheReadTokens
            val phase1CacheState = cacheState
            if (sessionId != null && phase1CacheState != null && cacheReadTokens > 0) {
                try {
                    PromptCacheBreakDetection.checkResponseForCacheBreak(
                        sessionId = sessionId,
                        currentState = phase1CacheState,
                        cacheReadTokens = cacheReadTokens,
                    )
                } catch (e: Exception) {
                    DebugLog.w("PromptCacheBreakDetection", "Phase2 checkResponseForCacheBreak (withTools) failed: ${e.message}")
                }
            }
            DebugLog.i("chatCompletionsWithTools ← content=${content.length} chars reasoning=${reasoning.length} chars toolCalls=${toolCalls.size}")
            return result
        }
    }

    fun visionChat(
        config: ApiConfig,
        system: String,
        prompt: String,
        pngBase64Pages: List<String>,
    ): String {
        val multimodalMessages = listOf(
            MultimodalMessage(
                role = Role.USER,
                content = buildList {
                    add(MultimodalContent.Text(prompt))
                    pngBase64Pages.forEach { b64 ->
                        add(MultimodalContent.ImageBase64(b64, "image/png"))
                    }
                },
            ),
        )
        return multimodalChat(config, system, multimodalMessages)
    }

    fun multimodalChat(
        config: ApiConfig,
        system: String,
        messages: List<MultimodalMessage>,
    ): String {
        val url = buildUrl(config.baseUrl, "/chat/completions")
        val json = JSONObject()
        json.put("model", config.model)
        json.put(
            "messages",
            JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", system))
                messages.forEach { m ->
                    val apiRole = roleToApi(m.role)
                    if (m.content.size == 1 && m.content[0] is MultimodalContent.Text) {
                        put(JSONObject().put("role", apiRole).put("content", (m.content[0] as MultimodalContent.Text).text))
                    } else {
                        val contentArr = JSONArray()
                        m.content.forEach { c ->
                            when (c) {
                                is MultimodalContent.Text ->
                                    contentArr.put(JSONObject().put("type", "text").put("text", c.text))
                                is MultimodalContent.ImageUrl ->
                                    contentArr.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", c.url)))
                                is MultimodalContent.ImageBase64 ->
                                    contentArr.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:${c.mimeType};base64,${c.base64}")))
                                is MultimodalContent.AudioUrl ->
                                    contentArr.put(JSONObject().put("type", "audio_url").put("audio_url", JSONObject().put("url", c.url)))
                                is MultimodalContent.AudioBase64 ->
                                    contentArr.put(JSONObject().put("type", "audio_url").put("audio_url", JSONObject().put("url", "data:${c.mimeType};base64,${c.base64}")))
                                is MultimodalContent.VideoUrl ->
                                    contentArr.put(JSONObject().put("type", "video_url").put("video_url", JSONObject().apply {
                                        put("url", c.url)
                                        put("detail", "high")
                                        put("max_frames", 16)
                                        put("fps", c.fps)
                                    }))
                                is MultimodalContent.VideoBase64 ->
                                    contentArr.put(JSONObject().put("type", "video_url").put("video_url", JSONObject().apply {
                                        put("url", "data:${c.mimeType};base64,${c.base64}")
                                        put("detail", "high")
                                        put("max_frames", 16)
                                        put("fps", c.fps)
                                    }))
                            }
                        }
                        put(JSONObject().put("role", apiRole).put("content", contentArr))
                    }
                }
            },
        )
        val req = buildRequest(url, json.toString(), config.apiKey)
        http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching {
                    JSONObject(raw).optJSONObject("error")?.optString("message")
                }.getOrNull()
                throw IllegalStateException(msg?.takeIf { it.isNotBlank() } ?: "请求失败: HTTP ${resp.code}")
            }
            val content = runCatching {
                val root = JSONObject(raw)
                val choice = root.getJSONArray("choices").getJSONObject(0)
                val message = choice.getJSONObject("message")
                message.optString("content", "")
            }.getOrNull()
            return content?.trim().orEmpty()
        }
    }

    fun chatCompletionsNativeSearch(
        config: ApiConfig,
        query: String,
    ): NativeSearchResult {
        val url = buildUrl(config.baseUrl, "/chat/completions")
        DebugLog.i("chatCompletionsNativeSearch → $url model=${config.model} query=$query")

        val json = JSONObject().apply {
            put("model", config.model)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "user").put("content", query))
            })
            put("tools", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "web_search")
                })
            })
            put("temperature", NATIVE_SEARCH_TEMPERATURE)
        }

        val req = buildRequest(url, json.toString(), config.apiKey)
        http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching {
                    JSONObject(raw).optJSONObject("error")?.optString("message")
                }.getOrNull()
                throw IllegalStateException(msg?.takeIf { it.isNotBlank() } ?: "供应商联网搜索失败: HTTP ${resp.code}")
            }
            val root = JSONObject(raw)
            val choice = root.getJSONArray("choices").getJSONObject(0)
            val message = choice.getJSONObject("message")
            val content = message.optString("content", "")
            val annotations = mutableListOf<NativeSearchCitation>()
            message.optJSONArray("annotations")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val ann = arr.optJSONObject(i) ?: continue
                    annotations.add(NativeSearchCitation(
                        title = ann.optString("title", ""),
                        url = ann.optString("url", ""),
                        snippet = ann.optString("snippet", ""),
                    ))
                }
            }
            DebugLog.i("chatCompletionsNativeSearch ← ${content.length} chars, ${annotations.size} citations")
            return NativeSearchResult(content = content, citations = annotations)
        }
    }

    // ==================== Anthropic Messages API (阶跃星辰兼容) ====================

    /**
     * 通过 Anthropic Messages API 格式流式调用。
     *
     * 阶跃星辰同时支持 OpenAI Chat Completions 和 Anthropic Messages 两种协议。
     * 本方法使用 Messages 协议（/messages 端点），适用于：
     * - 阶跃星辰 Step Plan 通道的 Anthropic 模式
     * - 需要 output_config.effort 推理强度控制的场景
     * - 工具调用格式为 tool_use/tool_result 的场景
     *
     * @param config API 配置（baseUrl 应指向 messages 端点或自动追加 /messages）
     * @param system 系统提示词（Messages API 的独立 system 参数）
     * @param messages 对话消息列表
     * @param tools 工具定义列表
     * @param thinkingEnabled 是否启用推理模式
     * @param onDelta 文本/推理增量回调
     * @param onToolCallDelta 工具调用增量回调
     * @param onDone 完成回调
     * @param onError 错误回调
     */
    suspend fun streamMessages(
        config: ApiConfig,
        system: String,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition> = emptyList(),
        thinkingEnabled: Boolean = false,
        sessionId: String? = null,
        requestParams: Map<String, Any> = emptyMap(),
        onDelta: (StreamDelta) -> Unit,
        onToolCallDelta: ((ToolCallDelta) -> Unit)? = null,
        onDone: (StreamResult) -> Unit,
        onError: (String) -> Unit,
    ): Call = withContext(Dispatchers.IO) {
        // 自动检测端点：如果 baseUrl 不含 /messages 则追加
        val rawUrl = config.baseUrl.trimEnd('/')
        val url = if (rawUrl.endsWith("/messages")) rawUrl else "$rawUrl/messages"

        DebugLog.i("streamMessages → $url model=${config.model} msgs=${messages.size} tools=${tools.size}")

        // 构建 Anthropic Messages 格式请求体
        val json = JSONObject().apply {
            put("model", config.model)
            put("max_tokens", MESSAGES_API_MAX_OUTPUT_TOKENS)
            put("stream", true)

            // system 参数（独立于 messages，这是 Messages API 的特点）
            if (system.isNotBlank()) {
                put("system", system)
            }

            // messages — 使用 Content Block 数组格式
            put("messages", buildAnthropicMessages(messages))

            // tools — Anthropic 格式：name/description/input_schema
            if (tools.isNotEmpty()) {
                put("tools", JSONArray().apply { tools.forEach { put(toolToAnthropicJson(it)) } })
            }

            // 推理强度控制（output_config.effort，对应 Chat Completions 的 reasoning_effort）
            val isSP = isStepPlan(config.model)
            if (isThinkingModel(config.model) && (thinkingEnabled || requiresDefaultReasoning(config.model))) {
                if (isSP) {
                    put("output_config", JSONObject().put("effort", "medium"))
                }
            }
        }

        // ★ Prompt Cache Break Detection Phase 1: pre-call 记录 prompt 指纹
        var cacheState: PromptCacheState? = null
        // P1-b 修复：仅对支持 cache 统计的模型记录指纹，避免不支持模型（cacheReadTokens 恒为 0）浪费内存且 Phase 2 沦为死代码
        if (sessionId != null && PromptCacheBreakDetection.isModelSupported(config.model)) {
            try {
                val params = buildMap<String, Any> {
                    put("thinking_enabled", thinkingEnabled)
                    put("tools_count", tools.size)
                    put("max_tokens", MESSAGES_API_MAX_OUTPUT_TOKENS)
                    putAll(requestParams)
                }
                cacheState = PromptCacheBreakDetection.recordPromptState(
                    sessionId = sessionId,
                    messages = messages,
                    systemPrompt = system,
                    model = config.model,
                    apiEndpoint = url,
                    requestParams = params,
                )
            } catch (e: Exception) {
                DebugLog.w("PromptCacheBreakDetection", "Phase1 recordPromptState (messages) failed: ${e.message}")
            }
        } else if (sessionId != null) {
            DebugLog.d("PromptCacheBreakDetection", "模型 ${config.model} 不支持 cache 统计，检测跳过")
        }

        val req = buildRequest(url, json.toString(), config.apiKey)
        val maskedBody = json.toString().replace(config.apiKey, "***")
        DebugLog.d("streamMessages REQUEST: model=${config.model} body=$maskedBody")

        val call = http.newCall(req)

        // ★ Prompt Cache Break Detection Phase 2: wrap onDone to verify cacheReadTokens after response
        val phase1CacheState = cacheState
        val phase1SessionId = sessionId
        val wrappedOnDone: (StreamResult) -> Unit = { result ->
            if (phase1SessionId != null && phase1CacheState != null && result.cacheReadTokens > 0) {
                try {
                    PromptCacheBreakDetection.checkResponseForCacheBreak(
                        sessionId = phase1SessionId,
                        currentState = phase1CacheState,
                        cacheReadTokens = result.cacheReadTokens,
                    )
                } catch (e: Exception) {
                    DebugLog.w("PromptCacheBreakDetection", "Phase2 checkResponseForCacheBreak (messages) failed: ${e.message}")
                }
            }
            onDone(result)
        }

        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: IOException) {
                DebugLog.e("streamMessages FAILED: ${e.message}", e)
                onError(e.message ?: "连接失败")
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    if (!response.isSuccessful) {
                        val errBody = response.body?.string().orEmpty()
                        val errMsg = runCatching {
                            JSONObject(errBody).optJSONObject("error")?.optString("message")
                        }.getOrNull() ?: "HTTP ${response.code}"
                        DebugLog.e("streamMessages HTTP ${response.code}: $errMsg")
                        onError(errMsg)
                        return
                    }

                    parseMessagesSseStream(response, onDelta, onToolCallDelta, wrappedOnDone, onError)
                } catch (e: Exception) {
                    DebugLog.e("streamMessages parse error: ${e.message}", e)
                    onError(e.message ?: "解析失败")
                } finally {
                    response.close()
                }
            }
        })

        call
    }

    /**
     * 将内部 ChatMessage 列表转换为 Anthropic Content Block 格式的 messages 数组。
     */
    private fun buildAnthropicMessages(messages: List<ChatMessage>): JSONArray {
        return JSONArray().apply {
            messages.forEach { m ->
                when {
                    m.toolCallId != null -> {
                        // tool 结果回传 → role=user, type=tool_result
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("type", "tool_result")
                                    put("tool_use_id", m.toolCallId)
                                    put("content", m.content)
                                })
                            })
                        })
                    }
                    m.apiToolCallsJson != null -> {
                        // assistant 带工具调用 → role=assistant, content=[text?, tool_use]
                        val blocks = JSONArray()
                        if (m.content.isNotBlank()) {
                            blocks.put(JSONObject().put("type", "text").put("text", m.content))
                        }
                        // 解析 tool_calls JSON 并转为 tool_use block
                        runCatching {
                            val calls = JSONArray(m.apiToolCallsJson)
                            for (i in 0 until calls.length()) {
                                val tc = calls.getJSONObject(i)
                                val fn = tc.optJSONObject("function") ?: tc
                                blocks.put(JSONObject().apply {
                                    put("type", "tool_use")
                                    put("id", tc.optString("id", "call_${System.currentTimeMillis()}_$i"))
                                    put("name", fn.optString("name", ""))
                                    put("input", runCatching { JSONObject(fn.optString("arguments", "{}")) }.getOrNull() ?: JSONObject())
                                })
                            }
                        }
                        if (blocks.length() > 0) {
                            put(JSONObject().put("role", "assistant").put("content", blocks))
                        }
                    }
                    else -> {
                        // 普通 user/assistant 消息 → Content Block 数组
                        put(JSONObject().apply {
                            put("role", roleToApi(m.role))
                            put("content", JSONArray().apply {
                                put(JSONObject().put("type", "text").put("text", m.content))
                            })
                        })
                    }
                }
            }
        }
    }

    /**
     * 将 ToolDefinition 转换为 Anthropic 工具格式（name/description/input_schema）。
     */
    private fun toolToAnthropicJson(tool: ToolDefinition): JSONObject {
        return JSONObject().apply {
            put("name", tool.name)
            put("description", tool.description)
            put("input_schema", tool.parameters)
        }
    }

    /**
     * 解析 Messages API 的 SSE 流。
     *
     * Messages SSE 事件格式与 Chat Completions 不同：
     * - event: message_start → 会话开始
     * - event: content_block_start → 内容块开始（text / tool_use）
     * - event: content_block_delta → 增量数据（delta.type=text_delta / input_json_delta）
     * - event: content_block_stop → 内容块结束
     * - event: message_delta → 消息级元数据（stop_reason, usage）
     * - event: message_stop → 完全结束
     */
    private fun parseMessagesSseStream(
        response: Response,
        onDelta: (StreamDelta) -> Unit,
        onToolCallDelta: ((ToolCallDelta) -> Unit)?,
        onDone: (StreamResult) -> Unit,
        onError: (String) -> Unit,
    ) {
        response.body?.use { body ->
            val source = body.source()
            try {
                var fullContent = StringBuilder()
                var fullReasoning = StringBuilder()
                var currentToolName: String? = null
                var currentToolId: String? = null
                var currentToolInput = StringBuilder()
                val toolCalls = mutableListOf<CompletedToolCall>()
                var lastStopReason: String? = null
                // ★ Prompt Cache Break Detection: 捕获 Anthropic message_start 中的 cache token 计数
                var capturedCacheReadTokens = 0
                var capturedCacheCreationTokens = 0

                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break

                    // 解析 event: 行
                    var eventType = ""
                    var dataLine = line

                    if (line.startsWith("event: ")) {
                        eventType = line.removePrefix("event: ").trim()
                        dataLine = source.readUtf8Line() ?: break
                    }

                    // 解析 data: 行
                    if (!dataLine.startsWith("data: ")) continue
                    val data = dataLine.removePrefix("data: ").trim()
                    if (data.isEmpty()) continue

                    when (eventType) {
                        "message_start" -> {
                            val obj = runCatching { JSONObject(data) }.getOrNull() ?: continue
                            val msg = obj.optJSONObject("message") ?: continue
                            val usage = msg.optJSONObject("usage")
                            if (usage != null) {
                                // Anthropic 格式: cache_read_input_tokens / cache_creation_input_tokens
                                capturedCacheReadTokens = usage.optInt("cache_read_input_tokens", 0)
                                capturedCacheCreationTokens = usage.optInt("cache_creation_input_tokens", 0)
                                if (capturedCacheReadTokens > 0 || capturedCacheCreationTokens > 0) {
                                    DebugLog.i("LlmClient.cache: model=messages cacheRead=$capturedCacheReadTokens cacheCreation=$capturedCacheCreationTokens")
                                }
                            }
                        }

                        "content_block_delta" -> {
                            val obj = runCatching { JSONObject(data) }.getOrNull() ?: continue
                            val delta = obj.optJSONObject("delta") ?: continue
                            val deltaType = delta.optString("type", "")

                            when (deltaType) {
                                "text_delta" -> {
                                    val text = delta.optString("text", "")
                                    if (text.isNotEmpty()) {
                                        fullContent.append(text)
                                        onDelta(StreamDelta.TextDelta(text))
                                    }
                                }
                                "thinking_delta" -> {
                                    val thinking = delta.optString("thinking", "")
                                    if (thinking.isNotEmpty()) {
                                        fullReasoning.append(thinking)
                                        onDelta(StreamDelta.ReasoningDelta(thinking))
                                    }
                                }
                                "input_json_delta" -> {
                                    // 工具调用的参数增量
                                    val partialJson = delta.optString("partial_json", "")
                                    if (partialJson.isNotEmpty()) {
                                        currentToolInput.append(partialJson)
                                    }
                                }
                            }
                        }

                        "content_block_start" -> {
                            val obj = runCatching { JSONObject(data) }.getOrNull() ?: continue
                            val block = obj.optJSONObject("content_block") ?: continue
                            if (block.optString("type") == "tool_use") {
                                currentToolName = block.optString("name", "")
                                currentToolId = block.optString("id", "")
                                currentToolInput = StringBuilder()
                            }
                        }

                        "content_block_stop" -> {
                            // 当前内容块结束，如果是 tool_use 则收集完整工具调用
                            if (currentToolName != null && currentToolId != null) {
                                val argsStr = currentToolInput.toString()
                                val toolId = currentToolId
                                val toolName = currentToolName
                                toolCalls.add(CompletedToolCall(
                                    id = toolId,
                                    name = toolName,
                                    arguments = argsStr.ifBlank { "{}" },
                                ))
                                onToolCallDelta?.invoke(ToolCallDelta(
                                    id = toolId,
                                    nameDelta = toolName,
                                    argsDelta = argsStr,
                                ))
                                currentToolName = null
                                currentToolId = null
                            }
                        }

                        "message_delta" -> {
                            val obj = runCatching { JSONObject(data) }.getOrNull() ?: continue
                            lastStopReason = obj.optString("stop_reason").takeIf { it.isNotEmpty() }
                        }
                    }
                }

                val finalContent = fullContent.toString()
                val finalReasoning = fullReasoning.toString()

                if (finalContent.isBlank() && finalReasoning.isNotBlank()) {
                    DebugLog.w("streamMessages: content empty but reasoning=${finalReasoning.take(100)}...")
                }

                onDone(StreamResult(
                    content = finalContent,
                    reasoning = finalReasoning,
                    toolCalls = toolCalls,
                    finishReason = lastStopReason,
                    cacheReadTokens = capturedCacheReadTokens,
                    cacheCreationTokens = capturedCacheCreationTokens,
                ))
                DebugLog.i("parseMessagesSse ← DONE, text=${finalContent.length} chars, reasoning=${finalReasoning.length} chars, tools=${toolCalls.size}")

            } catch (e: Exception) {
                DebugLog.e("parseMessagesSse 异常: ${e.message}", e)
                onError("SSE 解析异常: ${e.message}")
            }
        }
    }
}

data class NativeSearchResult(
    val content: String,
    val citations: List<NativeSearchCitation>,
)

data class NativeSearchCitation(
    val title: String,
    val url: String,
    val snippet: String,
)