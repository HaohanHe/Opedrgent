package top.hsyscn.opedrgent.interview

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import top.hsyscn.opedrgent.settings.ApiSettings
import top.hsyscn.opedrgent.utils.DebugLog
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 阶跃星辰 Realtime API 客户端 — WebSocket 双向实时语音。
 *
 * ## 与现有面试模式语音引擎的对比
 *
 * 现有流水线（VoiceConversationEngine v3）：
 *   AudioRecord → 本地VAD → Sherpa/MiMo ASR → LLM API → MiMo TTS → AudioTrack
 *
 * StepRealtimeClient（本类）：
 *   AudioRecord → PCM16编码 → WebSocket发送 → 服务端ASR+LLM+TTS一体化 → 接收音频播放
 *
 * ## 优势
 * - **端到端更低延迟**：服务端 VAD + 流式 TTS，省去本地 STT→LLM→TTS 三次网络往返
 * - **内置服务端 VAD**：无需本地 VAD 实现，服务器自动检测说话结束
 * - **思考过程可见**：response.thinking.delta 事件可展示 AI 推理过程
 * - **多模态统一**：文本+音频同时返回，支持边想边说
 *
 * ## API 参考
 * 文档: https://platform.stepfun.com/docs/realtime
 * 端点: wss://api.stepfun.com/v1/realtime
 */
class StepRealtimeClient(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    companion object {
        private const val TAG = "StepRealtime"
        private const val WS_URL = "wss://api.stepfun.com/v1/realtime"

        /** 支持的模型列表 */
        val SUPPORTED_MODELS = listOf(
            "stepaudio-2.5-realtime", // 推荐：专门为实时语音设计，副语言感知+音色复刻
            "step-3.7-flash",         // 多模态推理模型（通用 Agent 场景）
            "stepaudio-2-realtime",   // step-audio-2 实时版
            "step-1o-audio",          // 多模态音频
            "step-audio-r1.1",        // 推理增强版
        )

        /** 默认推荐模型 — 使用专门的语音大模型 */
        const val RECOMMENDED_MODEL = "stepaudio-2.5-realtime"

        /** 支持的音色列表 */
        val SUPPORTED_VOICES = listOf(
            "linjiajiejie" to "林佳洁",
            "wenrounansheng" to "温柔男声",
            "qingchunshaonv" to "青春少女",
            "elegantgentle-female" to "高雅女声",
            "livelybreezy-female" to "活力女声",
        )
    }

    // ==================== 内部状态 ====================

    private var webSocket: WebSocket? = null
    private var connectionJob: Job? = null
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MINUTES) // WebSocket 长连接，不设读超时
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var currentApiKey = ""
    private var currentModel = RECOMMENDED_MODEL
    private var currentVoice = "linjiajiejie"
    private var currentInstructions = ""
    /** 音色复刻参考音频（Base64 编码的 PCM16 数据，用于 Zero-shot 音色克隆） */
    private var referenceAudioBase64: String? = null
    /** 副语言感知开关 — 仅 stepaudio-2.5-realtime 支持 */
    private var enableParalinguistic: Boolean = true

    // ==================== 事件流 ====================

    private val _textDelta = MutableSharedFlow<String>(extraBufferCapacity = 64)
    private val _audioDelta = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private val _thinkingDelta = MutableSharedFlow<String>(extraBufferCapacity = 64)
    private val _transcriptDone = MutableSharedFlow<String>(extraBufferCapacity = 16)
    private val _textDone = MutableSharedFlow<String>(extraBufferCapacity = 16)
    private val _stateEvent = MutableSharedFlow<StepState>(replay = 1)
    private val _errorEvent = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** AI 文本增量输出 */
    val textDelta: SharedFlow<String> get() = _textDelta.asSharedFlow()

    /** AI 音频增量数据 (PCM16) */
    val audioDelta: SharedFlow<ByteArray> get() = _audioDelta.asSharedFlow()

    /** AI 思考过程增量 */
    val thinkingDelta: SharedFlow<String> get() = _thinkingDelta.asSharedFlow()

    /** 用户语音识别完成（完整转录文本） */
    val transcriptDone: SharedFlow<String> get() = _transcriptDone.asSharedFlow()

    /** AI 回复完成（完整文本） */
    val textDone: SharedFlow<String> get() = _textDone.asSharedFlow()

    /** 连接状态变化 */
    val stateEvent: SharedFlow<StepState> get() = _stateEvent.asSharedFlow()

    /** 错误事件 */
    val errorEvent: SharedFlow<String> get() = _errorEvent.asSharedFlow()

    // ==================== 公开接口 ====================

    /**
     * 连接并初始化会话。
     *
     * @param apiKey 阶跃星辰 API Key（从 ApiSettings 获取或用户手动输入）
     * @param model 模型名称（默认 stepaudio-2.5-realtime，专门的语音大模型）
     * @param voice 音色 ID（默认 linjiajiejie），或传入参考音频做 Zero-shot 音色复刻
     * @param instructions 系统 prompt / 面试官人设指令
     * @param referenceAudio 参考音频 PCM16 字节数组（用于音色克隆，3秒即可）
     * @param paralinguistic 是否启用副语言感知（仅 stepaudio-2.5-realtime 有效）
     */
    fun connect(
        apiKey: String,
        model: String = RECOMMENDED_MODEL,
        voice: String = "linjiajiejie",
        instructions: String = "",
        referenceAudio: ByteArray? = null,
        paralinguistic: Boolean = true,
    ) {
        if (apiKey.isBlank()) {
            _errorEvent.tryEmit("API Key 不能为空")
            return
        }

        disconnect() // 先断开已有连接

        currentApiKey = apiKey
        currentModel = model
        currentVoice = voice
        currentInstructions = instructions
        enableParalinguistic = paralinguistic
        // 保存参考音频 Base64（用于音色复刻）
        referenceAudioBase64 = referenceAudio?.let { Base64.getEncoder().encodeToString(it) }

        DebugLog.i("$TAG: 正在连接 $WS_URL (model=$model, voice=$voice, clone=${referenceAudio != null})")
        _stateEvent.tryEmit(StepState.CONNECTING)

        connectionJob = scope.launch(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(WS_URL)
                    .header("Authorization", "Bearer $apiKey")
                    .build()

                val listener = object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        DebugLog.i("$TAG: WebSocket 已连接")
                        _stateEvent.tryEmit(StepState.CONNECTED)

                        // 连接成功后立即发送 session 配置
                        sendSessionConfig(webSocket)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        handleServerEvent(text)
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        // 二进制消息暂不处理（当前 API 只用 JSON 文本事件）
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        DebugLog.e("$TAG: WebSocket 失败: ${t.message}", t)
                        _stateEvent.tryEmit(StepState.DISCONNECTED)
                        _errorEvent.tryEmit("连接失败: ${t.message}")
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        DebugLog.i("$TAG: WebSocket 已关闭: $code $reason")
                        _stateEvent.tryEmit(StepState.DISCONNECTED)
                    }
                }

                webSocket = httpClient.newWebSocket(request, listener)

            } catch (e: Exception) {
                if (e is CancellationException) return@launch
                DebugLog.e("$TAG: 连接异常: ${e.message}", e)
                _stateEvent.tryEmit(StepState.DISCONNECTED)
                _errorEvent.tryEmit("连接异常: ${e.message}")
            }
        }
    }

    /**
     * 断开连接。
     */
    fun disconnect() {
        connectionJob?.cancel()
        connectionJob = null
        webSocket?.close(1000, "用户断开")
        webSocket = null
        _stateEvent.tryEmit(StepState.DISCONNECTED)
    }

    /**
     * 发送 PCM16 音频数据（从麦克风采集的原始音频）。
     *
     * @param pcmData PCM16 格式的音频字节数组（16bit, 单声道, 16kHz 或 采样率匹配模型要求）
     */
    fun sendAudio(pcmData: ByteArray) {
        val ws = webSocket ?: run {
            DebugLog.w("$TAG: sendAudio 时 WebSocket 未连接")
            return
        }

        val base64Audio = Base64.getEncoder().encodeToString(pcmData)
        val eventId = generateEventId()

        val json = JSONObject().apply {
            put("event_id", eventId)
            put("type", "input_audio_buffer.append")
            put("audio", base64Audio)
        }

        ws.send(json.toString())
    }

    /**
     * 提交当前音频缓冲区（触发服务端 ASR + LLM 推理）。
     *
     * 通常在服务端 VAD 检测到静音后自动触发，
     * 但也可以手动调用以强制提交。
     */
    fun commitAudio() {
        val ws = webSocket ?: return
        val json = JSONObject().apply {
            put("event_id", generateEventId())
            put("type", "input_audio_buffer.commit")
        }
        ws.send(json.toString())
        DebugLog.d("$TAG: 手动 commit audio buffer")
    }

    /**
     * 清空音频缓冲区。
     */
    fun clearAudio() {
        val ws = webSocket ?: return
        val json = JSONObject().apply {
            put("event_id", generateEventId())
            put("type", "input_audio_buffer.clear")
        }
        ws.send(json.toString())
    }

    /**
     * 发送文本消息（作为用户输入）。
     *
     * 用于非语音输入场景，或在音频通道外补充文字信息。
     */
    fun sendTextMessage(text: String) {
        val ws = webSocket ?: return

        // 先创建会话消息项
        val createJson = JSONObject().apply {
            put("event_id", generateEventId())
            put("type", "conversation.item.create")
            put("item", JSONObject().apply {
                put("id", "msg_${System.currentTimeMillis()}")
                put("type", "message")
                put("role", "user")
                put("content", listOf(
                    JSONObject().apply { put("type", "input_text"); put("text", text) }
                ))
            })
        }
        ws.send(createJson.toString())

        // 触发推理
        val responseJson = JSONObject().apply {
            put("event_id", generateEventId())
            put("type", "response.create")
        }
        ws.send(responseJson.toString())
    }

    /**
     * 动态更新系统指令（如切换面试阶段、调整语气等）。
     */
    fun updateInstructions(newInstructions: String) {
        val ws = webSocket ?: return
        currentInstructions = newInstructions

        val json = JSONObject().apply {
            put("event_id", generateEventId())
            put("type", "session.update")
            put("session", buildSessionConfig(newInstructions))
        }
        ws.send(json.toString())
    }

    /**
     * 当前是否已连接。
     */
    val isConnected: Boolean get() = webSocket != null && _stateEvent.replayCache.lastOrNull() == StepState.CONNECTED

    // ==================== 内部方法 ====================

    /**
     * 发送 session.update 初始配置。
     */
    private fun sendSessionConfig(ws: WebSocket) {
        val json = JSONObject().apply {
            put("event_id", generateEventId())
            put("type", "session.update")
            put("session", buildSessionConfig(currentInstructions))
        }

        val sent = ws.send(json.toString())
        DebugLog.i("$TAG: session.update 发送${if (sent) "成功" else "失败"} (model=$currentModel, voice=$currentVoice)")
    }

    /**
     * 构建 session 配置 JSON 对象。
     *
     * stepaudio-2.5-realtime 专用特性：
     * - voice: 可使用预设音色 ID 或 reference_audio 做 Zero-shot 克隆
     * - paralinguistic: 副语言感知（读懂迟疑、轻笑、叹息等非语义信息）
     */
    private fun buildSessionConfig(instructions: String): JSONObject {
        return JSONObject().apply {
            put("modalities", listOf("text", "audio"))
            put("model", currentModel)
            put("instructions", instructions.ifBlank {
                "你是一位专业的AI面试官。请用中文与候选人进行自然流畅的对话。" +
                "根据候选人的回答进行追问或切换话题。每次回复控制在2-3句话以内。"
            })

            // 音色配置：支持预设音色 / Zero-shot 音色复刻
            if (referenceAudioBase64 != null) {
                // 音色复刻模式：上传参考音频获取自定义音色
                put("voice", JSONObject().apply {
                    put("type", "reference_audio")
                    put("format", "pcm16")
                    put("reference_audio", referenceAudioBase64!!)
                })
                DebugLog.i("$TAG: 使用 Zero-shot 音色复刻（参考音频已嵌入）")
            } else {
                // 预设音色模式
                put("voice", currentVoice)
            }

            put("input_audio_format", "pcm16")
            put("output_audio_format", "pcm16")

            // 开启服务端 VAD（核心！让服务器自动检测说话结束）
            put("turn_detection", JSONObject().apply {
                put("type", "server_vad")
                put("prefix_padding_ms", 500)
                put("silence_duration_ms", 800)  // 800ms 静音后判定为说完
                put("energy_awakeness_threshold", 2500)
            })

            // 副语言感知 — 仅 stepaudio-2.5-realtime 支持
            if (currentModel == "stepaudio-2.5-realtime" && enableParalinguistic) {
                put("paralinguistic_awareness", true)
                DebugLog.i("$TAG: 已启用副语言感知")
            }
        }
    }

    /**
     * 处理服务端事件分发。
     */
    private fun handleServerEvent(text: String) {
        try {
            val json = JSONObject(text)
            val eventType = json.optString("type", "")

            when (eventType) {
                // === 会话生命周期 ===
                "session.created" -> {
                    val session = json.optJSONObject("session")
                    val model = session?.optString("model", "unknown")
                    DebugLog.i("$TAG: Session 创建成功 (model=$model)")
                }

                "session.updated" -> {
                    DebugLog.d("$TAG: Session 更新确认")
                }

                // === VAD 事件 ===
                "input_audio_buffer.speech_started" -> {
                    DebugLog.d("$TAG: [VAD] 用户开始说话")
                    _stateEvent.tryEmit(StepState.USER_SPEAKING)
                }

                "input_audio_buffer.speech_stopped" -> {
                    DebugLog.d("$TAG: [VAD] 用户停止说话")
                    _stateEvent.tryEmit(StepState.AI_THINKING)
                }

                "input_audio_buffer.committed" -> {
                    DebugLog.d("$TAG: [VAD] 音频已提交，等待 AI 回复")
                }

                // === AI 输出：文本增量 ===
                "response.text.delta" -> {
                    val delta = json.optString("delta", "")
                    if (delta.isNotEmpty()) {
                        _textDelta.tryEmit(delta)
                    }
                }

                "response.text.done" -> {
                    val fullText = json.optString("text", "")
                    DebugLog.d("$TAG: [Text] 回复完成 (${fullText.length} 字符)")
                    _textDone.tryEmit(fullText)
                }

                // === AI 输出：音频增量 ===
                "response.audio.delta" -> {
                    val deltaBase64 = json.optString("delta", "")
                    if (deltaBase64.isNotEmpty()) {
                        try {
                            val audioBytes = Base64.getDecoder().decode(deltaBase64)
                            _audioDelta.tryEmit(audioBytes)
                        } catch (e: IllegalArgumentException) {
                            DebugLog.w("$TAG: 音频 Base64 解码失败")
                        }
                    }
                }

                "response.audio.done" -> {
                    DebugLog.d("$TAG: [Audio] 音频流结束")
                }

                // === AI 输出：思考过程 ===
                "response.thinking.delta" -> {
                    val delta = json.optString("content", "")
                    if (delta.isNotEmpty()) {
                        _thinkingDelta.tryEmit(delta)
                    }
                }

                "response.thinking.done" -> {
                    val thinking = json.optString("thinking", "")
                    DebugLog.d("$TAG: [Thinking] 思考完成 (${thinking.length} 字符)")
                }

                // === ASR 转录结果 ===
                "conversation.item.input_audio_transcription.completed" -> {
                    val transcript = json.optString("transcript", "")
                    DebugLog.i("$TAG: [ASR] 用户语音识别: \"$transcript\"")
                    _transcriptDone.tryEmit(transcript)
                }

                // === 响应生命周期 ===
                "response.created" -> {
                    _stateEvent.tryEmit(StepState.AI_SPEAKING)
                }

                "response.done" -> {
                    val status = json.optJSONObject("response")?.optString("status", "unknown")
                    DebugLog.d("$TAG: [Response] 完成 ($status)")
                    _stateEvent.tryEmit(StepState.LISTENING)
                }

                // === 错误 ===
                "error" -> {
                    val errMsg = json.optJSONObject("error")?.optString("message", "未知错误")
                    val errCode = json.optJSONObject("error")?.optString("code", "")
                    DebugLog.e("$TAG: [Error] $errCode: $errMsg")
                    _errorEvent.tryEmit("[$errCode] $errMsg")
                }

                else -> {
                    // 忽略其他事件类型（conversation.item.created/deleted 等）
                    DebugLog.d("$TAG: 未处理事件: $eventType")
                }
            }
        } catch (e: Exception) {
            DebugLog.w("$TAG: 事件解析异常: ${e.message}")
        }
    }

    /**
     * 生成唯一事件 ID。
     */
    private fun generateEventId(): String {
        return "evt_${UUID.randomUUID().toString().replace("-", "").substring(0, 12)}"
    }

    // ==================== 状态枚举 ====================

    /**
     * StepRealtime 连接/交互状态。
     */
    enum class StepState {
        /** 未连接 */
        DISCONNECTED,
        /** 正在连接中 */
        CONNECTING,
        /** 已连接，等待输入 */
        CONNECTED,
        /** 用户正在说话（VAD 检测到） */
        USER_SPEAKING,
        /** AI 正在思考/处理 */
        AI_THINKING,
        /** AI 正在说话（TTS 输出中） */
        AI_SPEAKING,
        /** 监听中（等待用户输入） */
        LISTENING,
    }
}
