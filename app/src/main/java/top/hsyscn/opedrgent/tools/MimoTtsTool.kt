package top.hsyscn.opedrgent.tools

import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.model.ToolStateType
import top.hsyscn.opedrgent.network.ToolResult
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
                description = "使用MiMo引擎生成高质量语音。参数中 text 为必填，可选参数: voice(音色)、model(模型)、style_instruction(风格指令)、overall_style(整体风格)、singing(是否唱歌)。",
                invoker = { tp, config, sp, ups -> executeMimoTts(tp, config, sp, ups) },
            ),
        )
    }

    private fun emptyResult(tp: ToolPart, msg: String): ToolResult {
        return ToolResult(toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.ERROR, error = msg, endTime = System.currentTimeMillis())))
    }

    @Tool("mimo_tts")
    @ToolDescription("使用MiMo引擎生成高质量语音。参数中 text 为必填，可选参数: voice(音色)、model(模型)、style_instruction(风格指令)、overall_style(整体风格)、singing(是否唱歌)。")
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

        val voiceId = tp.state.input["voice"]?.trim() ?: apiSettings.getTtsMimoVoice() ?: "冰糖"
        val modelId = tp.state.input["model"]?.trim() ?: "mimo-v2.5-tts"

        var styleInstruction: String? = null
        var overallStyle: String? = null
        var isSinging = false

        tp.state.input["style_instruction"]?.trim()?.takeIf { it.isNotBlank() }?.let { styleInstruction = it }
        tp.state.input["overall_style"]?.trim()?.takeIf { it.isNotBlank() }?.let { overallStyle = it }
        tp.state.input["singing"]?.toBooleanStrictOrNull()?.let { isSinging = it }

        DebugLog.i("mimo_tts: text=${text.take(50)}..., voice=$voiceId, model=$modelId")

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
            )

            val result = withContext(Dispatchers.IO) {
                top.hsyscn.opedrgent.tts.MimoTtsClient.synthesizeAdvanced(apiKey, request)
            }

            if (!result.success || result.audioData == null) {
                return emptyResult(tp, "mimo_tts: ${result.errorMessage ?: "音频数据为空"}")
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val outputFile = File(downloadsDir, "mimo_tts_$timestamp.wav")
            outputFile.parentFile?.mkdirs()
            outputFile.writeBytes(result.audioData)

            val durationSec = result.audioData.size / (24000 * 2)

            DebugLog.i("mimo_tts: success! file=${outputFile.absolutePath}, size=${result.audioData.size} bytes")

            val outputText = buildString {
                appendLine("✅ MiMo TTS语音合成成功")
                appendLine("- 文件：${outputFile.name} (${String.format("%.1f", result.audioData.size / 1024.0 / 1024.0)} MB)")
                appendLine("- 时长：约${durationSec}秒 | 模型：${result.modelUsed} | 音色：${result.voiceUsed}")
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