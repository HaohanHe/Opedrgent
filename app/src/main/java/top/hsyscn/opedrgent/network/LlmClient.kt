package top.hsyscn.opedrgent.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
        )
        private val DEEPSEEK_MODELS = setOf(
            "deepseek-v4-pro", "deepseek-v4-flash", "deepseek-v4",
            "deepseek-chat", "deepseek-reasoner",
        )
        private const val DEEPSEEK_MAX_CONTEXT = 1_000_000
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
            return THINKING_MODELS.any { model.contains(it) }
        }

        fun isDeepSeek(model: String): Boolean {
            return DEEPSEEK_MODELS.any { model.contains(it, ignoreCase = true) }
        }

        fun isDeepSeekV4(model: String): Boolean {
            return DEEPSEEK_MODELS.any { model.contains(it, ignoreCase = true) }
        }

        fun getDeepSeekMaxContext(): Int = DEEPSEEK_MAX_CONTEXT

        fun isMiMoThinking(model: String): Boolean {
            return MIMO_THINKING_MODELS.any { model.contains(it, ignoreCase = true) }
        }

        fun requiresDefaultReasoning(model: String): Boolean {
            return isDeepSeek(model) || isMiMoThinking(model)
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
        thinkingEnabled: Boolean = false,
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
            put("stream", true)
            put(
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
                            val assistantMsg = JSONObject().apply {
                                put("role", "assistant")
                                put("content", m.content.ifEmpty { "" })
                                put("tool_calls", JSONArray(m.apiToolCallsJson))
                            }
                            val reasoningText = m.reasoningParts.joinToString("\n") { it.text }
                            if (reasoningText.isNotEmpty()) assistantMsg.put("reasoning_content", reasoningText)
                            put(assistantMsg)
                        } else {
                            put(JSONObject().put("role", roleToApi(m.role)).put("content", m.content))
                        }
                    }
                },
            )
            val isDS = isDeepSeek(config.model)
            val defaultReasoning = requiresDefaultReasoning(config.model)
            if (isThinkingModel(config.model)) {
                if (thinkingEnabled || defaultReasoning) {
                    if (isDS) {
                        put("reasoning_effort", if (tools.isNotEmpty()) "max" else "high")
                    }
                    put("thinking", JSONObject().apply { put("type", "enabled") })
                } else {
                    if (isDS) {
                        put("thinking_mode", "non-thinking")
                    } else {
                        put("thinking", JSONObject().apply { put("type", "disabled") })
                    }
                }
            }
            if (tools.isNotEmpty()) {
                put("tools", JSONArray().apply {
                    tools.forEach { put(toolToJson(it)) }
                })
                if (!isDS) {
                    put("tool_choice", "auto")
                }
            }
        }

        val req = buildRequest(url, json.toString(), config.apiKey)
        val maskedBody = json.toString().replace(config.apiKey, "***")
        DebugLog.d("LlmClient REQUEST: model=${config.model} thinking=$thinkingEnabled body=$maskedBody")
        val call = http.newCall(req)
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
                            JSONObject(raw).optJSONObject("error")?.optString("message")
                        }.getOrNull()
                        onError(msg?.takeIf { it.isNotBlank() } ?: "请求失败: HTTP ${response.code}")
                        return
                    }

                    parseSseStream(response, onDelta, onToolCallDelta, onDone, onError)
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
                val choices = root.optJSONArray("choices") ?: return@runCatching
                if (choices.length() == 0) return@runCatching
                val choice = choices.getJSONObject(0)
                val delta = choice.optJSONObject("delta") ?: return@runCatching

                val finishReason = choice.optString("finish_reason", null)
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

                var remainingContent = content

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
                        val argsDelta = fn.optString("arguments", "")

                        if (nameDelta.isNotEmpty()) {
                            toolCallMap.getOrPut(idx) { StringBuilder() }.append(nameDelta)
                        }
                        if (argsDelta.isNotEmpty()) {
                            toolArgMap.getOrPut(idx) { StringBuilder() }.append(argsDelta)
                        }

                        onToolCallDelta?.invoke(
                            ToolCallDelta(
                                id = toolIdMap[idx].orEmpty(),
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

        if (totalChunks == 0) {
            DebugLog.w("parseSse: ZERO chunks received! Stream was empty or all parse failures")
        }

        val toolCalls = toolCallMap.map { (idx, nameBuilder) ->
            CompletedToolCall(
                id = toolIdMap[idx].orEmpty(),
                name = nameBuilder.toString().trim(),
                arguments = toolArgMap[idx]?.toString().orEmpty().trim(),
            )
        }

        val finalContent = fullContent.toString()
        val finalReasoning = fullReasoning.toString()
        if (finalContent.isBlank() && finalReasoning.isNotBlank()) {
            DebugLog.w("LlmClient: content empty but reasoning=${finalReasoning.take(100)}... using reasoning as fallback")
        }
        if (finalContent.isBlank() && finalReasoning.isBlank() && toolCalls.isEmpty()) {
            DebugLog.w("LlmClient: completely empty response!")
        }

        onDone(StreamResult(
            content = if (finalContent.isNotBlank()) finalContent else finalReasoning,
            reasoning = finalReasoning,
            toolCalls = toolCalls,
            finishReason = lastFinishReason,
            isSafetyFiltered = lastFinishReason?.equals("SAFETY", ignoreCase = true) == true,
        ))
    }

    suspend fun streamMultimodalChatCompletions(
        config: ApiConfig,
        system: String,
        messages: List<ChatMessage>,
        extraImages: List<String> = emptyList(),
        tools: List<ToolDefinition> = emptyList(),
        thinkingEnabled: Boolean = false,
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
            val defaultReasoning = requiresDefaultReasoning(config.model)
            if (isThinkingModel(config.model)) {
                if (thinkingEnabled || defaultReasoning) {
                    if (isDS) {
                        put("reasoning_effort", if (tools.isNotEmpty()) "max" else "high")
                    }
                    put("thinking", JSONObject().apply { put("type", "enabled") })
                } else {
                    if (isDS) {
                        put("thinking_mode", "non-thinking")
                    } else {
                        put("thinking", JSONObject().apply { put("type", "disabled") })
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

        val req = buildRequest(url, json.toString(), config.apiKey)
        DebugLog.d("LlmClient streamMultimodal: model=${config.model} url=$url thinking=$thinkingEnabled images=${extraImages.size} tools=${tools.size}")
        val call = http.newCall(req)
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

                    parseSseStream(response, onDelta, onToolCallDelta, onDone, onError)
                } catch (e: Exception) {
                    DebugLog.e("streamMultimodal parse error: ${e.message}", e)
                    onError(e.message ?: "解析失败")
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
     */
    fun chatCompletionsWithTools(
        config: ApiConfig,
        system: String,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition> = emptyList(),
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
            val result = StreamResult(
                content = content.trim(),
                toolCalls = toolCalls,
                finishReason = choice.optString("finish_reason", null),
            )
            DebugLog.i("chatCompletionsWithTools ← content=${content.length} chars toolCalls=${toolCalls.size}")
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
                                    contentArr.put(JSONObject().put("type", "input_audio").put("input_audio", JSONObject().put("data", c.url)))
                                is MultimodalContent.AudioBase64 ->
                                    contentArr.put(JSONObject().put("type", "input_audio").put("input_audio", JSONObject().put("data", "data:${c.mimeType};base64,${c.base64}")))
                                is MultimodalContent.VideoUrl ->
                                    contentArr.put(JSONObject().put("type", "video_url").put("video_url", JSONObject().put("url", c.url)).put("fps", c.fps).put("media_resolution", c.mediaResolution))
                                is MultimodalContent.VideoBase64 ->
                                    contentArr.put(JSONObject().put("type", "video_url").put("video_url", JSONObject().put("url", "data:${c.mimeType};base64,${c.base64}")).put("fps", c.fps).put("media_resolution", c.mediaResolution))
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
            put("temperature", 0.3)
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