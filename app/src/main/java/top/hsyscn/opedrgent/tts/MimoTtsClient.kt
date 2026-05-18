package top.hsyscn.opedrgent.tts

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import top.hsyscn.opedrgent.settings.ApiConfig
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.concurrent.TimeUnit

object MimoTtsClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private const val MIMO_TTS_URL = "https://api.xiaomimimo.com/v1/chat/completions"
    private const val MAX_TEXT_LENGTH = 2500

    enum class Model(val id: String) {
        PRESET("mimo-v2.5-tts"),
        VOICE_DESIGN("mimo-v2.5-tts-voicedesign"),
        VOICE_CLONE("mimo-v2.5-tts-voiceclone");
        
        companion object {
            fun fromId(id: String): Model = entries.firstOrNull { it.id == id } ?: PRESET
        }
    }

    data class Voice(
        val id: String,
        val name: String,
        val language: String,
        val gender: String,
        val style: String = "",
    )

    data class StyleControl(
        val naturalLanguage: String? = null,
        val overallStyle: String? = null,
        val isSinging: Boolean = false,
        val audioTags: List<String> = emptyList(),
        val isDirectorMode: Boolean = false,
        val directorCharacter: String? = null,
        val directorScene: String? = null,
        val directorGuidance: String? = null,
    )

    data class SynthesizeRequest(
        val text: String,
        val voiceId: String = "冰糖",
        val model: Model = Model.PRESET,
        val style: StyleControl? = null,
        val format: String = "wav",
    )

    data class SynthesizeResult(
        val success: Boolean,
        val audioData: ByteArray?,
        val errorMessage: String? = null,
        val modelUsed: String,
        val voiceUsed: String,
    )

    val PRESET_VOICES = listOf(
        Voice("冰糖", "冰糖", "中文", "女性", "活泼少女"),
        Voice("茉莉", "茉莉", "中文", "女性", "知性女声"),
        Voice("苏打", "苏打", "中文", "男性", "阳光少年"),
        Voice("白桦", "白桦", "中文", "男性", "成熟男声"),
        Voice("Mia", "Mia", "English", "Female", "Lively girl"),
        Voice("Chloe", "Chloe", "English", "Female", "Sweet Dreamy"),
        Voice("Milo", "Milo", "English", "Male", "Sunny boy"),
        Voice("Dean", "Dean", "English", "Male", "Steady Gentle"),
    )

    suspend fun synthesize(
        apiKey: String,
        text: String,
        voiceId: String = "冰糖",
        format: String = "wav",
    ): ByteArray? = withContext(Dispatchers.IO) {
        val request = SynthesizeRequest(text = text, voiceId = voiceId, format = format)
        val result = synthesizeAdvanced(apiKey, request)
        result.audioData
    }

    suspend fun synthesizeAdvanced(
        apiKey: String,
        request: SynthesizeRequest,
    ): SynthesizeResult = withContext(Dispatchers.IO) {
        try {
            DebugLog.i("MimoTts: advanced synthesis model=${request.model.id} voice=${request.voiceId} style=${request.style != null}")

            if (request.text.isBlank()) {
                return@withContext SynthesizeResult(
                    success = false,
                    audioData = null,
                    errorMessage = "Text is empty",
                    modelUsed = request.model.id,
                    voiceUsed = request.voiceId,
                )
            }

            if (request.text.length > MAX_TEXT_LENGTH) {
                DebugLog.w("MimoTts: text exceeds ${MAX_TEXT_LENGTH} chars, truncating")
            }

            val messages = buildMessages(request)
            val audioConfig = buildAudioConfig(request)

            val json = JSONObject()
            json.put("model", request.model.id)
            json.put("messages", messages)
            json.put("audio", audioConfig)

            val requestBody = json.toString().toRequestBody("application/json".toMediaType())

            val httpRequest = Request.Builder()
                .url(MIMO_TTS_URL)
                .post(requestBody)
                .header("Content-Type", "application/json")
                .header("api-key", apiKey)
                .build()

            val response = client.newCall(httpRequest).execute()

            if (!response.isSuccessful) {
                val raw = response.body?.string().orEmpty()
                DebugLog.e("MimoTts: HTTP ${response.code} body=${raw.take(200)}")
                return@withContext SynthesizeResult(
                    success = false,
                    audioData = null,
                    errorMessage = "HTTP ${response.code}: $raw",
                    modelUsed = request.model.id,
                    voiceUsed = request.voiceId,
                )
            }

            val responseBody = response.body?.string().orEmpty()
            if (responseBody.isEmpty()) {
                DebugLog.e("MimoTts: empty response body")
                return@withContext SynthesizeResult(
                    success = false,
                    audioData = null,
                    errorMessage = "Empty response",
                    modelUsed = request.model.id,
                    voiceUsed = request.voiceId,
                )
            }

            val root = JSONObject(responseBody)
            val messageObj = root.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")

            val audioData = messageObj?.optJSONObject("audio")?.optString("data", null)?.trim()

            if (audioData.isNullOrBlank()) {
                DebugLog.e("MimoTts: no audio data in response")
                return@withContext SynthesizeResult(
                    success = false,
                    audioData = null,
                    errorMessage = "No audio data in API response",
                    modelUsed = request.model.id,
                    voiceUsed = request.voiceId,
                )
            }

            val audioBytes = Base64.decode(audioData, Base64.NO_WRAP)
            if (audioBytes.isEmpty()) {
                DebugLog.e("MimoTts: decoded audio is empty")
                return@withContext SynthesizeResult(
                    success = false,
                    audioData = null,
                    errorMessage = "Decoded audio is empty",
                    modelUsed = request.model.id,
                    voiceUsed = request.voiceId,
                )
            }

            DebugLog.i("MimoTts: success! ${audioBytes.size} bytes, model=${request.model.id}, voice=${request.voiceId}")
            
            SynthesizeResult(
                success = true,
                audioData = audioBytes,
                modelUsed = request.model.id,
                voiceUsed = request.voiceId,
            )
        } catch (e: Exception) {
            DebugLog.e("MimoTts error: ${e.message}", e)
            SynthesizeResult(
                success = false,
                audioData = null,
                errorMessage = e.message ?: "Unknown exception",
                modelUsed = request.model.id,
                voiceUsed = request.voiceId,
            )
        }
    }

    private fun buildMessages(request: SynthesizeRequest): JSONArray {
        val messages = JSONArray()

        val style = request.style
        if (style != null) {
            val userContent = buildUserContent(style)
            if (userContent.isNotBlank()) {
                val userMsg = JSONObject()
                userMsg.put("role", "user")
                userMsg.put("content", userContent)
                messages.put(userMsg)
            }
        } else {
            val userMsg = JSONObject()
            userMsg.put("role", "user")
            userMsg.put("content", "")
            messages.put(userMsg)
        }

        val assistantContent = buildAssistantContent(request.text, request.style)
        val assistantMsg = JSONObject()
        assistantMsg.put("role", "assistant")
        assistantMsg.put("content", assistantContent)
        messages.put(assistantMsg)

        return messages
    }

    private fun buildUserContent(style: StyleControl): String {
        if (style.isDirectorMode && (style.directorCharacter != null || style.directorGuidance != null)) {
            return buildDirectorPrompt(style)
        }
        
        return style.naturalLanguage ?: ""
    }

    private fun buildDirectorPrompt(style: StyleControl): String {
        val sb = StringBuilder()

        if (style.directorCharacter != null) {
            sb.appendLine("角色：${style.directorCharacter}")
        }
        if (style.directorScene != null) {
            sb.appendLine("场景：${style.directorScene}")
        }
        if (style.directorGuidance != null) {
            sb.appendLine("指导：\n${style.directorGuidance}")
        }

        return sb.toString().trim()
    }

    private fun buildAssistantContent(text: String, style: StyleControl?): String {
        val parts = mutableListOf<String>()

        if (style != null) {
            if (style.isSinging) {
                parts.add("(唱歌)")
            }
            
            if (style.overallStyle != null) {
                parts.add("(${style.overallStyle})")
            }

            if (style.audioTags.isNotEmpty()) {
                parts.addAll(style.audioTags.map { "[$it]" })
            }
        }

        parts.add(text)
        
        return parts.joinToString("")
    }

    private fun buildAudioConfig(request: SynthesizeRequest): JSONObject {
        val audioConfig = JSONObject()
        audioConfig.put("format", request.format)

        if (request.model == Model.PRESET) {
            audioConfig.put("voice", request.voiceId)
        }

        return audioConfig
    }

    fun suggestVoiceForText(text: String): Voice? {
        val hasChinese = text.any { it.code in 0x4E00..0x9FFF }

        return if (hasChinese) {
            when {
                text.contains("可爱") || text.contains("活泼") -> PRESET_VOICES.firstOrNull { it.id == "冰糖" }
                text.contains("温柔") || text.contains("知性") -> PRESET_VOICES.firstOrNull { it.id == "茉莉" }
                text.contains("阳光") || text.contains("少年") -> PRESET_VOICES.firstOrNull { it.id == "苏打" }
                else -> PRESET_VOICES.firstOrNull { it.id == "白桦" }
            }
        } else {
            when {
                text.any { it.isUpperCase() && it.isLetter() } -> PRESET_VOICES.firstOrNull { it.id == "Chloe" }
                else -> PRESET_VOICES.firstOrNull { it.id == "Mia" }
            }
        }
    }

    fun validateApiKey(apiKey: String): Boolean {
        return apiKey.isNotBlank() && apiKey.length > 10
    }
}