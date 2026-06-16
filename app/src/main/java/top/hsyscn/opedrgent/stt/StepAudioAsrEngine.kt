package top.hsyscn.opedrgent.stt

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import top.hsyscn.opedrgent.settings.ApiSettings
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * 阶跃星辰 StepAudio 2.5 ASR 云端语音识别引擎。
 *
 * ## 核心优势（对比本地 Sherpa-ONNX）
 * - **4B MTP 技术**：RTF≈0.0053，1小时音频约19秒转完
 * - **无需下载模型**：纯云端调用，零存储开销
 * - **SSE 流式返回**：边传边识别，实时看到结果
 * - **数字归一化**：enable_itn 自动将 "一二三" → "123"
 * - **时间戳**：enable_timestamp 返回每个字的时间偏移
 *
 * ## API 参考
 * 文档: https://platform.stepfun.com/docs/zh/guides/models/stepaudio-2.5-asr
 * 端点: POST https://api.stepfun.com/v1/audio/asr/sse
 * 定价: 0.15元/小时
 */
class StepAudioAsrEngine(
    private val context: Context,
    private val apiSettings: ApiSettings,
) : SpeechEngine {

    companion object {
        private const val TAG = "StepAudioASR"
        private const val ASR_URL = "https://api.stepfun.com/v1/audio/asr/sse"
    }

    override val engineType: EngineType = EngineType.STEP_AUDIO_ASR
    private var initialized = false
    private var apiKey: String? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS) // 长音频需要更长超时
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 流式识别的增量文本流 */
    private val _partialText = MutableSharedFlow<String>(extraBufferCapacity = 64)

    /**
     * 初始化引擎 — 检查 API Key 是否可用。
     */
    fun initialize(): Boolean {
        try {
            apiKey = apiSettings.getApiKey()
            if (apiKey.isNullOrBlank()) {
                DebugLog.w(TAG, "初始化失败: API Key 未设置")
                return false
            }
            initialized = true
            DebugLog.i(TAG, "初始化成功 (key=${apiKey?.take(6)}...)")
            return true
        } catch (e: Exception) {
            DebugLog.e(TAG, "初始化异常: ${e.message}", e)
            return false
        }
    }

    override val isAvailable: Boolean get() = initialized && !apiKey.isNullOrBlank()

    /**
     * 转录文件（通过 URI）。
     * 将文件转为 Base64 后发送到 StepAudio ASR API。
     */
    override suspend fun recognizeFile(uri: Uri): SttResult = withContext(Dispatchers.IO) {
        if (!isAvailable) {
            return@withContext SttResult(
                text = "", error = "StepAudio ASR 未初始化", engineType = engineType
            )
        }

        try {
            val startTimeMs = System.currentTimeMillis()
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val audioBytes = inputStream.readBytes()
                if (audioBytes.isEmpty()) {
                    return@withContext SttResult(
                        text = "", error = "文件为空或无法读取", engineType = engineType
                    )
                }

                val base64Audio = Base64.getEncoder().encodeToString(audioBytes)
                val result = callAsrApi(base64Audio, getMimeType(uri))
                val processingTimeMs = System.currentTimeMillis() - startTimeMs
                result.copy(
                    processingTimeMs = processingTimeMs,
                    modelUsed = "stepaudio-2.5-asr",
                )
            } ?: SttResult(text = "", error = "无法打开 URI: $uri", engineType = engineType)
        } catch (e: Exception) {
            DebugLog.e(TAG, "recognizeFile(uri) 异常: ${e.message}", e)
            SttResult(text = "", error = "转录异常: ${e.message}", engineType = engineType)
        }
    }

    /**
     * 转录文件（通过文件路径）。
     */
    override suspend fun recognizeFile(filePath: String): SttResult = withContext(Dispatchers.IO) {
        if (!isAvailable) {
            return@withContext SttResult(
                text = "", error = "StepAudio ASR 未初始化", engineType = engineType
            )
        }

        try {
            val startTimeMs = System.currentTimeMillis()
            val file = java.io.File(filePath)
            if (!file.exists()) {
                return@withContext SttResult(text = "", error = "文件不存在: $filePath", engineType = engineType)
            }

            val audioBytes = file.readBytes()
            val base64Audio = Base64.getEncoder().encodeToString(audioBytes)
            val mimeType = when (filePath.substringAfterLast('.', "").lowercase()) {
                "mp3" -> "audio/mpeg"
                "ogg" -> "audio/ogg"
                "wav" -> "audio/wav"
                else -> "audio/*"
            }
            val result = callAsrApi(base64Audio, mimeType)
            val processingTimeMs = System.currentTimeMillis() - startTimeMs
            result.copy(
                processingTimeMs = processingTimeMs,
                durationMs = file.length().toLong(),
                modelUsed = "stepaudio-2.5-asr",
            )
        } catch (e: Exception) {
            DebugLog.e(TAG, "recognizeFile(path) 异常: ${e.message}", e)
            SttResult(text = "", error = "转录异常: ${e.message}", engineType = engineType)
        }
    }

    /**
     * 启动流式识别。
     * 注意：StepAudio ASR 的流式模式是通过 SSE 接收增量文本实现的，
     * 实际音频输入仍需通过 HTTP POST 发送完整数据。
     * 对于实时麦克风场景，建议使用 StepRealtimeClient 的 WebSocket 模式。
     */
    override fun startStreamingRecognition(): Flow<StreamingRecognitionState> = flow {
        emit(StreamingRecognitionState.Listening)

        // StepAudio ASR 不支持真正的实时麦克风流式，
        // 这里返回空流，实际录音场景应使用 StepRealtimeClient
        DebugLog.w(TAG, "startStreaming: StepAudio ASR 不支持实时麦克风流式，请使用 Realtime WebSocket")
        emit(StreamingRecognitionState.Error(
            "StepAudio ASR 仅支持文件转录。实时语音请使用「面试模式」的 StepRealtimeClient。"
        ))
    }

    override fun stopStreamingRecognition() {
        // 无状态操作
    }

    override fun close() {
        initialized = false
        DebugLog.i(TAG, "引擎已关闭")
    }

    // ==================== 内部实现 ====================

    /**
     * 调用 StepAudio ASR SSE API。
     *
     * 请求格式：
     * ```json
     * {
     *   "model": "stepaudio-2.5-asr",
     *   "audio": "<base64>",
     *   "audio_format": "mp3",
     *   "language": "zh",
     *   "enable_itn": true,
     *   "enable_timestamp": true
     * }
     * ```
     *
     * 响应格式（SSE 流）：
     * ```
     * event: transcript.text.delta
     * data: {"text": "..."}
     *
     * event: transcript.text.done
     * data: {"text": "完整转录文本"}
     * ```
     */
    private suspend fun callAsrApi(base64Audio: String, mimeType: String): SttResult =
        withContext(Dispatchers.IO) {
            try {
                val json = JSONObject().apply {
                    put("model", "stepaudio-2.5-asr")
                    put("audio", base64Audio)
                    put("audio_format", mapMimeTypeToFormat(mimeType))
                    put("language", "zh")           // 中文优先
                    put("enable_itn", true)         // 数字归一化："一百二十三" → "123"
                    put("enable_timestamp", true)   // 启用时间戳
                }

                val requestBody = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(ASR_URL)
                    .post(requestBody)
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .build()

                DebugLog.i(TAG, "发送 ASR 请求 (${base64Audio.length / 1024}KB base64)")

                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    val errBody = response.body?.string().orEmpty()
                    DebugLog.e(TAG, "ASR HTTP ${response.code}: $errBody")
                    return@withContext SttResult(
                        text = "",
                        error = "HTTP ${response.code}: $errBody",
                        engineType = engineType,
                    )
                }

                // 解析 SSE 流响应
                parseSseResponse(response)

            } catch (e: Exception) {
                DebugLog.e(TAG, "callAsrApi 异常: ${e.message}", e)
                SttResult(text = "", error = "API 调用异常: ${e.message}", engineType = engineType)
            }
        }

    /**
     * 解析 SSE 流式响应，收集完整的转录文本和分段信息。
     */
    private fun parseSseResponse(response: okhttp3.Response): SttResult {
        var fullText = StringBuilder()
        val segments = mutableListOf<SttSegment>()

        response.body?.use { body ->
            val reader = BufferedReader(InputStreamReader(body.byteStream()))
            var currentEventType = ""

            try {
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val ln = line ?: ""
                    when {
                        ln.startsWith("event:") -> {
                            currentEventType = ln.removePrefix("event:").trim()
                        }
                        ln.startsWith("data:") -> {
                            val data = ln.removePrefix("data:").trim()
                            if (data.isBlank()) continue

                            runCatching {
                                val obj = JSONObject(data)
                                when (currentEventType) {
                                    "transcript.text.delta" -> {
                                        val delta = obj.optString("text", "")
                                        if (delta.isNotEmpty()) {
                                            fullText.append(delta)
                                            _partialText.tryEmit(fullText.toString())
                                        }
                                    }
                                    "transcript.text.done" -> {
                                        val doneText = obj.optString("text", "")
                                        if (doneText.isNotEmpty()) {
                                            fullText.clear()
                                            fullText.append(doneText)
                                        }
                                        // 尝试解析分段信息
                                        val segArray = obj.optJSONArray("segments")
                                        if (segArray != null) {
                                            for (i in 0 until segArray.length()) {
                                                val seg = segArray.getJSONObject(i)
                                                segments.add(SttSegment(
                                                    text = seg.optString("text", ""),
                                                    startTimeMs = seg.optLong("start", 0),
                                                    endTimeMs = seg.optLong("end", 0),
                                                    confidence = seg.optDouble("confidence", 1.0).toFloat(),
                                                ))
                                            }
                                        }
                                    }
                                }
                            }.onFailure { DebugLog.w(TAG, "SSE 数据解析失败: ${it.message}") }
                        }
                    }
                }
            } catch (e: Exception) {
                DebugLog.w(TAG, "SSE 流读取中断: ${e.message}")
            }
        }

        val finalText = fullText.toString().trim()
        DebugLog.i(TAG, "ASR 完成: ${finalText.length} 字符, ${segments.size} 分段")

        return SttResult(
            text = finalText,
            confidence = if (finalText.isNotBlank()) 0.95f else 0f,
            segments = segments.ifEmpty {
                if (finalText.isNotBlank()) listOf(SttSegment(text = finalText))
                else emptyList()
            },
            engineType = engineType,
            modelUsed = "stepaudio-2.5-asr",
        )
    }

    /**
     * MIME 类型映射为 StepAudio 支持的格式标识。
     */
    private fun mapMimeTypeToFormat(mimeType: String): String {
        return when (mimeType.lowercase()) {
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/ogg" -> "ogg"
            "audio/wav", "audio/x-wav" -> "wav"
            "audio/mp4", "audio/aac", "audio/m4a" -> "mp4"
            "audio/flac" -> "flac"
            "audio/webm" -> "webm"
            else -> "wav" // 默认尝试 wav
        }
    }

    /**
     * 从 URI 推断 MIME 类型。
     */
    private fun getMimeType(uri: Uri): String {
        val cr = context.contentResolver
        return cr.getType(uri) ?: "audio/*"
    }
}
