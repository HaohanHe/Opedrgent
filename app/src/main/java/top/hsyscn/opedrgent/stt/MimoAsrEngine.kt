package top.hsyscn.opedrgent.stt

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import top.hsyscn.opedrgent.settings.ApiSettings
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * MiMO 在线语音识别引擎。
 *
 * 通过 MiMo 开放平台的 OpenAI 兼容 API 调用 mimo-v2.5-asr 模型进行语音识别。
 * 与本地 Sherpa-ONNX 引擎共享 [SpeechEngine] 接口，可无缝切换。
 *
 * 特点：
 * - 无需下载模型（在线推理）
 * - 支持中英双语、方言、歌词、多人说话
 * - 自动添加标点符号
 * - 需要联网 + MiMo API Key
 *
 * API 文档：https://platform.xiaomimimo.com/docs/zh-CN/usage-guide/multimodal-understanding/audio-understanding
 * 模型文档：https://mimo.xiaomi.com/mimo-v2-5-asr
 */
class MimoAsrEngine(
    private val context: Context,
    private val apiSettings: ApiSettings,
) : SpeechEngine {

    companion object {
        private const val TAG = "MimoAsrEngine"
        const val MODEL_ID = "mimo-v2.5-asr"
        /** Base64 编码后最大 50MB（官方限制） */
        private const val MAX_BASE64_BYTES = 50 * 1024 * 1024
        /** 原始文件最大 100MB（URL 模式限制，Base64 更严格） */
        private const val MAX_FILE_BYTES = 100 * 1024 * 1024
    }

    private var _isInitialized = false
    override val engineType = EngineType.SHERPA_ONNX  // 复用枚举，实际为在线引擎

    override val isAvailable: Boolean get() = _isInitialized && apiSettings.hasApiKey()

    fun initialize(): Boolean {
        if (_isInitialized) return true
        return try {
            val key = apiSettings.getApiKey()
            if (key.isNullOrBlank()) {
                DebugLog.w(TAG, "MiMo ASR: API Key 未设置")
                return false
            }
            _isInitialized = true
            DebugLog.i(TAG, "MiMo ASR 引擎初始化成功 (model=$MODEL_ID)")
            true
        } catch (e: Exception) {
            DebugLog.e(TAG, "初始化失败: ${e.message}", e)
            false
        }
    }

    override suspend fun recognizeFile(uri: Uri): SttResult = withContext(Dispatchers.IO) {
        ensureInitialized()
        val startTimeMs = System.currentTimeMillis()
        try {
            val tempFile = copyUriToTempFile(uri)
            try { recognizeFileInternal(tempFile, startTimeMs) }
            finally { tempFile.delete() }
        } catch (e: Exception) {
            DebugLog.e(TAG, "URI 识别失败: ${e.message}", e)
            SttResult("", 0f, emptyList(), 0, System.currentTimeMillis() - startTimeMs, EngineType.SHERPA_ONNX, MODEL_ID)
        }
    }

    override suspend fun recognizeFile(filePath: String): SttResult = withContext(Dispatchers.IO) {
        ensureInitialized()
        val startTimeMs = System.currentTimeMillis()
        try { recognizeFileInternal(File(filePath), startTimeMs) }
        catch (e: Exception) {
            DebugLog.e(TAG, "文件路径识别失败: ${e.message}", e)
            SttResult("", 0f, emptyList(), 0, System.currentTimeMillis() - startTimeMs, EngineType.SHERPA_ONNX, MODEL_ID)
        }
    }

    /**
     * MiMO ASR 不支持真正的流式识别（每次请求都是完整的音频）。
     * 返回错误提示用户切换到本地引擎。
     */
    override fun startStreamingRecognition(): Flow<StreamingRecognitionState> =
        flowOf(StreamingRecognitionState.Error("MiMO ASR 不支持流式实时识别，请切换到本地 Sherpa-ONNX 引擎"))

    fun feedAudioData(samples: FloatArray) { /* 不适用 */ }

    override fun stopStreamingRecognition() { /* 不适用 */ }

    override fun close() {
        _isInitialized = false
    }

    // ==================== 内部实现 ====================

    private suspend fun recognizeFileInternal(file: File, startTimeMs: Long): SttResult {
        val fileSize = file.length()
        DebugLog.i(TAG, "开始 MiMO ASR 识别: ${file.name} (${fileSize / 1024}KB)")

        if (fileSize > MAX_FILE_BYTES) {
            DebugLog.w(TAG, "文件过大(${fileSize / (1024*1024)}MB > 100MB)，可能失败")
        }

        // 编码为 Base64
        val base64Audio = encodeToBase64(file)

        val apiKey = apiSettings.getApiKey()!!

        // 构造请求体：OpenAI 兼容格式 + input_audio 多模态内容
        val jsonBody = JSONObject().apply {
            put("model", MODEL_ID)
            put("max_tokens", 2048)
            put("temperature", 0.1f)  // ASR 用低温度保证确定性输出
            put("messages", JSONArray().apply {
                // 系统提示：纯转录模式
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "你是一个专业的语音转文字助手。请将用户提供的音频内容准确逐字转录为纯文本。保留原始语言和标点符号。只输出转录文本，不要添加任何解释、标题或额外内容。")
                })
                // 用户消息：音频 + 转录指令
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "input_audio")
                            put("input_audio", JSONObject().put("data", "data:audio/wav;base64,$base64Audio"))
                        })
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", "请转录这段音频的内容。")
                        })
                    })
                })
            })
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)   // 长音频需要更多时间
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url("https://api.xiaomimimo.com/v1/chat/completions")
            .header("api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        var text = ""
        var errorMsg: String? = null

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                errorMsg = "HTTP ${response.code}: $body"
                DebugLog.e(TAG, "MiMO ASR 请求失败: $errorMsg")
                return@use
            }

            try {
                val json = org.json.JSONObject(body)
                val choices = json.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    text = choices.getJSONObject(0)
                        .getJSONObject("message")
                        .optString("content", "")
                        .trim()
                } else {
                    // 可能是错误响应
                    errorMsg = json.optString("error", json.optString("message", body))
                    DebugLog.w(TAG, "MiMO ASR 异常响应: $errorMsg")
                }
            } catch (e: Exception) {
                errorMsg = "解析响应失败: ${e.message}"
                DebugLog.e(TAG, "$errorMsg\n原始响应: ${body.take(500)}", e)
            }
        }

        val durationMs = System.currentTimeMillis() - startTimeMs

        if (text.isNotEmpty()) {
            DebugLog.i(TAG, "MiMO ASR 完成: ${text.take(100)}... (${text.length}字, ${durationMs}ms)")
        } else {
            DebugLog.w(TAG, "MiMO ASR 返回空结果: $errorMsg")
        }

        return SttResult(
            text = text,
            confidence = if (text.isNotEmpty()) 1f else 0f,
            segments = if (text.isNotEmpty()) listOf(
                SttSegment(text = text, startTimeMs = 0, endTimeMs = durationMs, confidence = 1f),
            ) else emptyList(),
            durationMs = durationMs,
            processingTimeMs = durationMs,
            engineType = EngineType.SHERPA_ONNX,
            modelUsed = MODEL_ID,
        )
    }

    private fun encodeToBase64(file: File): String {
        val bytes = file.readBytes()
        val dataToEncode = if (bytes.size <= MAX_BASE64_BYTES) bytes
        else bytes.copyOfRange(0, MAX_BASE64_BYTES)
        return Base64.getEncoder().encodeToString(dataToEncode)
    }

    private suspend fun copyUriToTempFile(uri: Uri): File = withContext(Dispatchers.IO) {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("无法打开 URI: $uri")
        val tempFile = File(context.cacheDir, "mimo_asr_${System.currentTimeMillis()}.wav")
        try {
            tempFile.outputStream().use { output -> inputStream.use { input -> input.copyTo(output) } }
            tempFile
        } catch (e: Exception) {
            if (tempFile.exists()) tempFile.delete()
            throw e
        }
    }

    private fun ensureInitialized() {
        if (!_isInitialized) throw IllegalStateException("MimoAsrEngine 未初始化，请先调用 initialize()")
    }
}
