package top.hsyscn.opedrgent.tts

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import top.hsyscn.opedrgent.utils.DebugLog
import java.util.concurrent.TimeUnit

/**
 * 阶跃星辰 StepAudio 2.5 TTS 云端语音合成客户端。
 *
 * ## 核心差异化能力（MiMo TTS 完全没有的）
 *
 * ### 1. Global Context（全局语境控制）
 * 通过 `instruction` 参数定义整段语音的情感基调：
 * - 例: "声音极度紧绷，像在拼命压住快要失控的狂喜"
 * - 例: "温柔知性的播报风格，语速适中"
 *
 * ### 2. Inline Context（句内语境控制）
 * 在文本中用括号 `()` 插入指令，**括号内内容不会被朗读**，
 * 只用于控制情绪/停顿/气息：
 * - 例: "(压低声音)喂……你看我手机。(短促吸气)是不是我眼花了？"
 * - 例: "(轻笑)这个嘛……(停顿)我确实不太清楚。"
 *
 * ### 3. Zero-shot 音色复刻
 * 上传 3 秒参考音频即可克隆任意音色。
 *
 * ## API 参考
 * 文档: https://platform.stepfun.com/docs/zh/guides/models/stepaudio-2.5-tts
 * 端点: POST https://api.stepfun.com/v1/audio/speech
 * 定价: 5.8元/万字符
 */
object StepAudioTtsClient {

    private const val TAG = "StepAudioTTS"
    private const val TTS_URL = "https://api.stepfun.com/v1/audio/speech"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS) // 长文本合成需要更长时间
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 支持的输出格式 */
    enum class OutputFormat(val value: String, val ext: String) {
        MP3("mp3", "mp3"),
        WAV("wav", "wav"),
        PCM16("pcm16", "pcm"),
        OGG_OPUS("opus", "ogg"),
    }

    /** 预设音色列表 */
    val PRESET_VOICES = listOf(
        Voice("linjiajiejie", "林佳洁", "中文", "女性"),
        Voice("wenrounansheng", "温柔男声", "中文", "男性"),
        Voice("qingchunshaonv", "青春少女", "中文", "女性"),
        Voice("elegantgentle-female", "高雅女声", "中文", "女性"),
        Voice("livelybreezy-female", "活力女声", "中文", "女性"),
    )

    data class Voice(
        val id: String,
        val name: String,
        val language: String,
        val gender: String,
    )

    /**
     * TTS 合成请求参数。
     *
     * @param text 要合成的文本（支持 Inline Context 括号指令）
     * @param voiceId 预设音色 ID，或留空使用 referenceAudio 做克隆
     * @param format 输出音频格式
     * @param globalInstruction 全局语境指令（整段基调）
     * @param referenceAudioBase64 参考音频 Base64（Zero-shot 音色复刻）
     * @param speed 语速 (0.5 ~ 2.0)，默认 1.0
     * @param volume 音量 (0.0 ~ 1.0)，默认 1.0
     */
    data class SynthesizeRequest(
        val text: String,
        val voiceId: String = "linjiajiejie",
        val format: OutputFormat = OutputFormat.MP3,
        val globalInstruction: String? = null,
        val referenceAudioBase64: String? = null,
        val speed: Float = 1.0f,
        val volume: Float = 1.0f,
    )

    data class SynthesizeResult(
        val success: Boolean,
        val audioData: ByteArray?,
        val errorMessage: String? = null,
        val modelUsed: String = "stepaudio-2.5-tts",
        val voiceUsed: String,
        val formatUsed: String,
    )

    /** 最大单次合成字符数 */
    private const val MAX_TEXT_LENGTH = 4096

    /**
     * 简易合成接口 — 使用预设配置。
     *
     * @param apiKey 阶跃星辰 API Key
     * @param text 合成文本
     * @param voiceId 音色 ID
     * @return 音频字节数组（MP3 格式），失败返回 null
     */
    suspend fun synthesize(
        apiKey: String,
        text: String,
        voiceId: String = "linjiajiejie",
    ): ByteArray? = withContext(Dispatchers.IO) {
        val result = synthesizeAdvanced(apiKey, SynthesizeRequest(text = text, voiceId = voiceId))
        result.audioData
    }

    /**
     * 高级合成接口 — 支持全部参数。
     */
    suspend fun synthesizeAdvanced(
        apiKey: String,
        request: SynthesizeRequest,
    ): SynthesizeResult = withContext(Dispatchers.IO) {
        try {
            if (apiKey.isBlank()) {
                return@withContext SynthesizeResult(
                    success = false, audioData = null,
                    errorMessage = "API Key 不能为空",
                    voiceUsed = request.voiceId, formatUsed = request.format.value,
                )
            }
            if (request.text.isBlank()) {
                return@withContext SynthesizeResult(
                    success = false, audioData = null,
                    errorMessage = "合成文本不能为空",
                    voiceUsed = request.voiceId, formatUsed = request.format.value,
                )
            }

            val truncatedText = if (request.text.length > MAX_TEXT_LENGTH) {
                DebugLog.w("$TAG: 文本超长(${request.text.length}字符)，截断到 $MAX_TEXT_LENGTH")
                request.text.take(MAX_TEXT_LENGTH)
            } else {
                request.text
            }

            // 构建 StepAudio TTS 请求体
            val json = JSONObject().apply {
                put("model", "stepaudio-2.5-tts")
                put("input", truncatedText)

                // 音色：预设 / Zero-shot 克隆
                if (!request.referenceAudioBase64.isNullOrBlank()) {
                    put("voice", JSONObject().apply {
                        put("type", "reference_audio")
                        put("format", "pcm16")
                        put("reference_audio", request.referenceAudioBase64!!)
                    })
                } else {
                    put("voice", request.voiceId)
                }

                // 输出格式
                put("response_format", request.format.value)

                // Global Context 全局语境指令
                if (!request.globalInstruction.isNullOrBlank()) {
                    put("instruction", request.globalInstruction)
                }

                // 音速和音量
                put("speed", request.speed.coerceIn(0.5f, 2.0f))
                put("volume", request.volume.coerceIn(0.0f, 1.0f))

                // 流式输出（可选，当前用非流式）
                put("stream", false)
            }

            val requestBody = json.toString().toRequestBody("application/json".toMediaType())
            val httpRequest = Request.Builder()
                .url(TTS_URL)
                .post(requestBody)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .build()

            DebugLog.i("$TAG: 合成请求 voice=${request.voiceId} text=${truncatedText.length} chars" +
                " instruction=${!request.globalInstruction.isNullOrBlank()}")

            val response = client.newCall(httpRequest).execute()

            if (!response.isSuccessful) {
                val errBody = response.body?.string().orEmpty()
                DebugLog.e("$TAG: HTTP ${response.code}: ${errBody.take(200)}")
                return@withContext SynthesizeResult(
                    success = false, audioData = null,
                    errorMessage = "HTTP ${response.code}: $errBody",
                    voiceUsed = request.voiceId, formatUsed = request.format.value,
                )
            }

            // 响应可能是 JSON（含 audio 字段）或直接二进制音频
            val contentType = response.header("Content-Type", "") ?: ""

            val result = when {
                contentType.contains("json") || contentType.contains("application") -> {
                    // JSON 响应：{ "audio": "<base64>" } 或 { "data": "<base64>" }
                    val bodyStr = response.body?.string().orEmpty()
                    parseJsonResponse(bodyStr, request)
                }
                else -> {
                    // 直接二进制响应
                    val audioBytes = response.body?.bytes() ?: ByteArray(0)
                    if (audioBytes.isEmpty()) {
                        SynthesizeResult(
                            success = false, audioData = null,
                            errorMessage = "响应体为空",
                            voiceUsed = request.voiceId, formatUsed = request.format.value,
                        )
                    } else {
                        DebugLog.i("$TAG: 合成成功! ${audioBytes.size} bytes (binary)")
                        SynthesizeResult(
                            success = true, audioData = audioBytes,
                            voiceUsed = request.voiceId, formatUsed = request.format.value,
                        )
                    }
                }
            }

            return@withContext result

        } catch (e: Exception) {
            DebugLog.e("$TAG: 合成异常: ${e.message}", e)
            SynthesizeResult(
                success = false, audioData = null,
                errorMessage = e.message ?: "未知异常",
                voiceUsed = request.voiceId, formatUsed = request.format.value,
            )
        }
    }

    /**
     * 解析 JSON 格式的响应。
     */
    private fun parseJsonResponse(bodyStr: String, request: SynthesizeRequest): SynthesizeResult {
        return try {
            val root = JSONObject(bodyStr)

            // 尝试多种可能的字段名
            val audioBase64 = root.optString("audio", "")
                .ifBlank { root.optString("data", "") }
                .ifBlank { root.optString("audio_data", "") }

            if (audioBase64.isBlank()) {
                // 可能是错误响应
                val errMsg = root.optJSONObject("error")?.optString("message")
                    ?: root.optString("error", "")
                    ?: "无音频数据"
                DebugLog.e("$TAG: 响应中无音频数据: $errMsg")
                SynthesizeResult(
                    success = false, audioData = null,
                    errorMessage = errMsg,
                    voiceUsed = request.voiceId, formatUsed = request.format.value,
                )
            } else {
                val audioBytes = Base64.decode(audioBase64, Base64.NO_WRAP)
                DebugLog.i("$TAG: 合成成功! ${audioBytes.size} bytes (base64)")
                SynthesizeResult(
                    success = true, audioData = audioBytes,
                    voiceUsed = request.voiceId, formatUsed = request.format.value,
                )
            }
        } catch (e: Exception) {
            DebugLog.e("$TAG: JSON 解析失败: ${e.message}")
            SynthesizeResult(
                success = false, audioData = null,
                errorMessage = "响应解析失败: ${e.message}",
                voiceUsed = request.voiceId, formatUsed = request.format.value,
            )
        }
    }

    /**
     * 根据文本特征推荐音色。
     */
    fun suggestVoiceForText(text: String): Voice? {
        val hasChinese = text.any { it.code in 0x4E00..0x9FFF }
        return if (hasChinese) {
            when {
                text.contains("温柔") || text.contains("知性") -> PRESET_VOICES.firstOrNull { it.id == "elegantgentle-female" }
                text.contains("活泼") || text.contains("青春") -> PRESET_VOICES.firstOrNull { it.id == "qingchunshaonv" }
                else -> PRESET_VOICES.firstOrNull() // 默认林佳洁
            }
        } else {
            PRESET_VOICES.firstOrNull()
        }
    }

    /**
     * 验证 API Key 是否有效。
     */
    fun validateApiKey(apiKey: String): Boolean = apiKey.isNotBlank() && apiKey.length > 10
}
