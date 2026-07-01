package top.hsyscn.opedrgent.tools

import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.model.ToolStateType
import top.hsyscn.opedrgent.network.ToolResult
import top.hsyscn.opedrgent.network.emptyResult
import top.hsyscn.opedrgent.settings.ApiConfig
import top.hsyscn.opedrgent.settings.ApiSettings
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MimoTtsTool(
    private val apiSettings: ApiSettings,
) : ToolSet {

    override fun getTools(): Map<String, ToolBinding> {
        return mapOf(
            "mimo_tts" to ToolBinding(
                name = "mimo_tts",
                description = "使用MiMo引擎生成高质量语音。支持预置音色(8种)、音色设计(文本描述)、音色克隆(音频样本)三种模式。参数: text(必填), voice(可选,默认冰糖), model(可选: mimo-v2.5-tts/mimo-v2.5-tts-voicedesign/mimo-v2.5-tts-voiceclone), style_instruction(自然语言风格), overall_style(整体标签), singing(唱歌模式), voice_file_base64(仅voiceclone模式，音频样本base64)。",
                invoker = { tp, config, sp, ups -> executeMimoTts(tp, config, sp, ups) },
            ),
        )
    }

    @Tool("mimo_tts")
    @ToolDescription("使用MiMo引擎生成高质量语音。支持预置音色、音色设计、音色克隆三种模式。")
    suspend fun executeMimoTts(
        tp: ToolPart,
        config: ApiConfig,
        systemPrompt: String,
        useProviderSearch: Boolean,
    ): ToolResult {
        val text = tp.state.input["text"]?.trim().orEmpty()
        if (text.isBlank()) {
            return emptyResult(tp, "mimo_tts: 缺少必填参数 text")
        }

        val apiKey = apiSettings.getApiKey()
        if (apiKey.isNullOrBlank()) {
            return emptyResult(tp, "mimo_tts: 未配置API Key（MiMo与主模型共用同一Key）")
        }

        val voiceId = tp.state.input["voice"]?.trim() ?: apiSettings.getTtsMimoVoice()
        val rawModelId = tp.state.input["model"]?.trim() ?: "mimo-v2.5-tts"
        val modelId = when {
            rawModelId.contains("voiceclone") -> "mimo-v2.5-tts-voiceclone"
            rawModelId.contains("voicedesign") -> "mimo-v2.5-tts-voicedesign"
            else -> "mimo-v2.5-tts"
        }
        val isVoiceClone = modelId == "mimo-v2.5-tts-voiceclone"

        var styleInstruction: String? = null
        var overallStyle: String? = null
        var isSinging = false
        var voiceFileBase64: String? = null

        tp.state.input["style_instruction"]?.trim()?.takeIf { it.isNotBlank() }?.let { styleInstruction = it }
        tp.state.input["overall_style"]?.trim()?.takeIf { it.isNotBlank() }?.let { overallStyle = it }
        tp.state.input["singing"]?.toBooleanStrictOrNull()?.let { isSinging = it }
        if (isVoiceClone) {
            tp.state.input["voice_file_base64"]?.trim()?.takeIf { it.isNotBlank() && it.length > 100 }?.let { voiceFileBase64 = it }
        }

        DebugLog.i("mimo_tts: text=${text.take(50)}..., voice=$voiceId, model=$modelId, clone=${voiceFileBase64 != null}")

        try {
            val request = top.hsyscn.opedrgent.tts.MimoTtsClient.SynthesizeRequest(
                text = text,
                voiceId = voiceId,
                model = top.hsyscn.opedrgent.tts.MimoTtsClient.Model.fromId(modelId),
                style = if (styleInstruction != null || overallStyle != null || isSinging) {
                    top.hsyscn.opedrgent.tts.MimoTtsClient.StyleControl(
                        naturalLanguage = styleInstruction,
                        overallStyle = overallStyle,
                        isSinging = isSinging,
                    )
                } else null,
                voiceFileBase64 = voiceFileBase64,
            )

            val result = withContext(Dispatchers.IO) {
                top.hsyscn.opedrgent.tts.MimoTtsClient.synthesizeAdvanced(apiKey, request)
            }

            if (!result.success || result.audioData == null) {
                return emptyResult(tp, "mimo_tts: ${result.errorMessage ?: "音频数据为空"}")
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val suffix = when (modelId) {
                "mimo-v2.5-tts-voiceclone" -> "voiceclone"
                "mimo-v2.5-tts-voicedesign" -> "voicedesign"
                else -> "tts"
            }
            val outputFile = File(downloadsDir, "mimo_${suffix}_$timestamp.wav")
            outputFile.parentFile?.mkdirs()
            outputFile.writeBytes(result.audioData)

            val durationSec = result.audioData.size.toDouble() / (24000 * 2)

            DebugLog.i("mimo_tts: success! file=${outputFile.absolutePath}, size=${result.audioData.size} bytes")

            val outputText = buildString {
                appendLine("[成功] MiMo TTS语音合成成功")
                appendLine("- 模式：${when(modelId) {"mimo-v2.5-tts" -> "预置音色" "mimo-v2.5-tts-voicedesign" -> "音色设计" else -> "音色克隆"}}")
                appendLine("- 文件：${outputFile.name} (${String.format("%.1f", result.audioData.size / 1024.0 / 1024.0)} MB)")
                appendLine("- 时长：约${durationSec}秒 | 模型：${result.modelUsed} | 音色：${result.voiceUsed}")
                if (isVoiceClone && voiceFileBase64 != null) appendLine("- 使用了音频样本进行声音复刻")
                if (overallStyle != null) appendLine("- 风格：$overallStyle")
                if (isSinging) appendLine("- 模式：唱歌")
            }

            return ToolResult(
                toolPart = tp.copy(state = tp.state.copy(
                    status = ToolStateType.COMPLETED,
                    output = outputText,
                    endTime = System.currentTimeMillis(),
                )),
                openUrl = outputFile.absolutePath,
            )
        } catch (e: Exception) {
            DebugLog.e("mimo_tts exception: ${e.message}", e)
            return emptyResult(tp, "mimo_tts: 异常 - ${e.message}")
        }
    }
}