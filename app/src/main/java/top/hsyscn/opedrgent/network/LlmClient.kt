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

data class StreamDelta(
    val content: String = "",
    val reasoning: String = "",
)

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

class LlmClient(private val http: OkHttpClient = HttpClients.default) {

    companion object {
        private val MULTIMODAL_MODELS = setOf(
            "mimo-v2.5", "mimo-v2-omni", "mimo-v2.5-pro",
        )
        private val WEB_SEARCH_MODELS = setOf(
            "mimo-v2.5-pro", "mimo-v2.5", "mimo-v2-pro", "mimo-v2-omni", "mimo-v2-flash",
        )
        private val TTS_MODELS = setOf(
            "mimo-v2.5-tts", "mimo-v2.5-tts-voicedesign", "mimo-v2.5-tts-voiceclone",
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
    }

    private fun buildUrl(baseUrl: String, path: String): String {
        val base = baseUrl.trimEnd('/')
        return if (base.endsWith("/v1") || base.endsWith("/v2") || base.endsWith("/v3")) {
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
        if (apiKey.startsWith("tp-")) {
            reqBuilder.header("api-key", apiKey)
        } else {
            reqBuilder.header("Authorization", "Bearer $apiKey")
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
        onDelta: (StreamDelta) -> Unit,
        onToolCallDelta: ((ToolCallDelta) -> Unit)? = null,
        onDone: (StreamResult) -> Unit,
        onError: (String) -> Unit,
    ): Call = withContext(Dispatchers.IO) {
        val url = buildUrl(config.baseUrl, "/chat/completions")
        val msgCount = messages.size
        val toolsCount = tools.size
        DebugLog.i("streamChatCompletions → $url model=${config.model} msgs=$msgCount tools=$toolsCount")
        DebugLog.json("system_first_300", system.take(300))

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
                            put(JSONObject().apply {
                                put("role", "assistant")
                                if (m.content.isNotEmpty()) put("content", m.content)
                                else put("content", JSONObject.NULL)
                                put("tool_calls", JSONArray(m.apiToolCallsJson))
                            })
                        } else {
                            put(JSONObject().put("role", roleToApi(m.role)).put("content", m.content))
                        }
                    }
                },
            )
            if (tools.isNotEmpty()) {
                put("tools", JSONArray().apply {
                    tools.forEach { put(toolToJson(it)) }
                })
                put("tool_choice", "auto")
            }
        }

        val req = buildRequest(url, json.toString(), config.apiKey)
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

                    val source = response.body?.source() ?: run {
                        onError("响应体为空")
                        return
                    }

                    val fullContent = StringBuilder()
                    val fullReasoning = StringBuilder()
                    val toolCallMap = mutableMapOf<Int, StringBuilder>() // index -> name
                    val toolArgMap = mutableMapOf<Int, StringBuilder>() // index -> args
                    val toolIdMap = mutableMapOf<Int, String>() // index -> id

                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: continue
                        if (line.isEmpty()) continue
                        if (!line.startsWith("data: ")) continue

                        val data = line.removePrefix("data: ").trim()
                        if (data == "[DONE]") {
                            DebugLog.i("streamChatCompletions ← DONE, text=${fullContent.length} chars, tools=${toolCallMap.size}")
                            break
                        }

                        runCatching {
                            val root = JSONObject(data)
                            val choices = root.optJSONArray("choices") ?: return@runCatching
                            if (choices.length() == 0) return@runCatching
                            val choice = choices.getJSONObject(0)
                            val delta = choice.optJSONObject("delta") ?: return@runCatching

                            val content = delta.optString("content", "")
                            val reasoning = delta.optString("reasoning_content", "")
                                .ifEmpty { delta.optString("reasoning", "") }

                            if (content.isNotEmpty()) fullContent.append(content)
                            if (reasoning.isNotEmpty()) fullReasoning.append(reasoning)

                            if (content.isNotEmpty() || reasoning.isNotEmpty()) {
                                onDelta(StreamDelta(content = content, reasoning = reasoning))
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
                            DebugLog.w("streamChatCompletions parse: ${e.message} data=${data.take(100)}")
                        }
                    }

                    val toolCalls = toolCallMap.map { (idx, nameBuilder) ->
                        CompletedToolCall(
                            id = toolIdMap[idx].orEmpty(),
                            name = nameBuilder.toString().trim(),
                            arguments = toolArgMap[idx]?.toString().orEmpty().trim(),
                        )
                    }

                    onDone(StreamResult(
                        content = fullContent.toString(),
                        reasoning = fullReasoning.toString(),
                        toolCalls = toolCalls,
                    ))
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
                            if (m.content.isNotEmpty()) put("content", m.content)
                            else put("content", JSONObject.NULL)
                            put("tool_calls", JSONArray(m.apiToolCallsJson))
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
            json.put("tool_choice", "auto")
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