package top.hsyscn.opedrgent.tools

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.model.ToolStateType
import top.hsyscn.opedrgent.network.ToolResult
import top.hsyscn.opedrgent.settings.ApiConfig
import top.hsyscn.opedrgent.stt.AudioProcessor
import top.hsyscn.opedrgent.stt.SttConfig
import top.hsyscn.opedrgent.stt.SttLanguage
import top.hsyscn.opedrgent.stt.SttResult
import top.hsyscn.opedrgent.stt.SpeechEngine
import top.hsyscn.opedrgent.utils.DebugLog

class SpeechToTextTool(
    private val context: Context,
    private val speechEngine: SpeechEngine,
) : ToolSet {

    override fun getTools(): Map<String, ToolBinding> {
        return mapOf(
            "speech_to_text" to ToolBinding(
                name = "speech_to_text",
                description = "语音转文字工具：将音频或视频文件中的语音内容识别为文字。支持常见音频格式(mp3, wav, m4a, aac等)和视频格式(mp4, avi, mkv等)。参数 uri(必填): 文件URI路径; language(可选, 默认auto自动检测): zh/en/auto; enable_punctuation(可选, 默认true): 是否添加标点。",
                invoker = { tp, config, sp, ups -> executeSpeechToText(tp, config, sp, ups) },
            ),
        )
    }

    private fun emptyResult(tp: ToolPart, msg: String): ToolResult {
        return ToolResult(toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.ERROR, error = msg, endTime = System.currentTimeMillis())))
    }

    @Tool("speech_to_text")
    @ToolDescription("将音频或视频文件中的语音内容识别为文字。支持多种音视频格式，可自动从视频中提取音频轨道。")
    suspend fun executeSpeechToText(
        tp: ToolPart,
        config: ApiConfig,
        systemPrompt: String,
        useProviderSearch: Boolean,
    ): ToolResult {
        val rawUri = tp.state.input["uri"]?.trim()
            ?: tp.state.input["file_uri"]?.trim()
            ?: tp.state.input["path"]?.trim()
            ?: return emptyResult(tp, "缺少必填参数 uri（文件URI路径）")

        val languageStr = (tp.state.input["language"]?.trim() ?: "auto").lowercase()
        val enablePunctuation = tp.state.input["enable_punctuation"]?.toBooleanStrictOrNull() ?: true

        val language = when (languageStr) {
            "zh", "chinese", "中文" -> SttLanguage.CHINESE
            "en", "english", "英文" -> SttLanguage.ENGLISH
            else -> SttLanguage.AUTO
        }

        DebugLog.i("speech_to_text: uri=${rawUri.take(80)}, language=$language, punctuation=$enablePunctuation")

        val uri = runCatching { Uri.parse(rawUri) }.getOrElse {
            return emptyResult(tp, "无效的 URI 格式: $rawUri")
        }

        val sttConfig = SttConfig(
            language = language,
            enablePunctuation = enablePunctuation,
        )

        return withContext(Dispatchers.IO) {
            runCatching {
                val startTime = System.currentTimeMillis()

                val (isValid, validationError) = AudioProcessor.validateAudioFile(context, uri)
                if (!isValid) {
                    return@withContext emptyResult(tp, "文件验证失败: $validationError")
                }

                val metadata = AudioProcessor.getAudioMetadata(context, uri)
                if (metadata != null) {
                    DebugLog.i("speech_to_text: 元数据 duration=${AudioProcessor.formatDuration(metadata.durationMs)} sampleRate=${metadata.sampleRate} channels=${metadata.channels}")
                }

                val isVideo = runCatching {
                    val contentType = context.contentResolver.getType(uri) ?: ""
                    contentType.startsWith("video/") || rawUri.contains(".mp4") || rawUri.contains(".avi") || rawUri.contains(".mkv")
                }.getOrElse { false }

                if (isVideo) {
                    DebugLog.i("speech_to_text: 检测到视频文件，尝试提取音频轨道")
                    val processed = AudioProcessor.extractAudioFromVideo(context, uri)
                    if (processed == null) {
                        return@withContext emptyResult(tp, "无法从视频中提取音频轨道，请确认视频包含音频")
                    }
                    DebugLog.i("speech_to_text: 视频音频提取成功 duration=${AudioProcessor.formatDuration(processed.metadata.durationMs)}")
                }

                if (!speechEngine.isAvailable) {
                    return@withContext emptyResult(tp, "语音识别引擎不可用，请检查STT模型是否已下载并初始化")
                }

                DebugLog.i("speech_to_text: 开始识别...")
                val result: SttResult = speechEngine.recognizeFile(uri)

                val processingTime = System.currentTimeMillis() - startTime

                if (result.text.isBlank()) {
                    DebugLog.w("speech_to_text: 识别结果为空 engine=${result.engineType} model=${result.modelUsed}")
                    return@withContext emptyResult(tp, "语音识别完成但未检测到有效语音内容，可能原因：\n1. 文件中无语音或语音过短\n2. 音频质量过低（噪音过大）\n3. 语言与设置不匹配")
                }

                val outputText = buildString {
                    appendLine("✅ 语音转文字完成")
                    appendLine("- 识别文字：")
                    appendLine(result.text)
                    appendLine()
                    append("- 置信度：${String.format("%.1f", result.confidence * 100)}%")
                    appendLine()
                    append("- 引擎：${result.engineType}")
                    if (result.modelUsed.isNotBlank()) appendLine("- 模型：${result.modelUsed}")
                    appendLine("- 耗时：${processingTime}ms")
                    if (result.durationMs > 0) appendLine("- 音频时长：${AudioProcessor.formatDuration(result.durationMs)}")

                    if (result.segments.size > 1) {
                        appendLine()
                        appendLine("- 分段详情（共${result.segments.size}段）：")
                        result.segments.forEachIndexed { idx, seg ->
                            val startFmt = AudioProcessor.formatDuration(seg.startTimeMs)
                            val endFmt = AudioProcessor.formatDuration(seg.endTimeMs)
                            appendLine("  [${idx + 1}] ${startFmt}-${endFmt} (${String.format("%.0f", seg.confidence * 100)}%): ${seg.text}")
                        }
                    }
                }

                DebugLog.i("speech_to_text: 成功! text_len=${result.text.length} time=${processingTime}ms engine=${result.engineType}")

                ToolResult(
                    toolPart = tp.copy(state = tp.state.copy(
                        status = ToolStateType.COMPLETED,
                        output = outputText,
                        endTime = System.currentTimeMillis(),
                    )),
                )
            }.getOrElse { e ->
                DebugLog.e("speech_to_text 异常: ${e.message}", e)
                emptyResult(tp, "语音识别异常: ${e.message}")
            }
        }
    }
}
