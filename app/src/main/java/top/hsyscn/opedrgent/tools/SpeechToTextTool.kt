package top.hsyscn.opedrgent.tools

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.model.ToolStateType
import top.hsyscn.opedrgent.network.ToolResult
import top.hsyscn.opedrgent.network.emptyResult
import top.hsyscn.opedrgent.settings.ApiConfig
import top.hsyscn.opedrgent.stt.AudioMetadata
import top.hsyscn.opedrgent.stt.AudioProcessor
import top.hsyscn.opedrgent.stt.SttConfig
import top.hsyscn.opedrgent.stt.SttLanguage
import top.hsyscn.opedrgent.stt.SttResult
import top.hsyscn.opedrgent.utils.DebugLog
import java.util.Locale

enum class MediaType { AUDIO, VIDEO, UNKNOWN }

enum class ProcessingPhase(val progressRange: IntRange) {
    DECODING(0..30),
    SEGMENTING(30..50),
    RECOGNIZING(50..100),
    IDLE(100..100),
}

data class ProcessingProgress(
    val phase: ProcessingPhase,
    val percent: Int,
    val message: String,
)

class SpeechToTextTool(
    private val context: Context,
    private val asrManager: top.hsyscn.opedrgent.stt.AsrManager,
) : ToolSet {

    companion object {
        private const val TAG = "SpeechToTextTool"
        private const val MAX_DURATION_MS = 1800_000L
        private const val MAX_FILE_SIZE_MB = 100

        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "m4a", "wav", "aac", "ogg", "flac", "amr", "wma", "opus", "aiff", "pcm"
        )
        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "m4v", "3gp", "ts"
        )
        private val AUDIO_MIME_PREFIXES = arrayOf("audio/")
        private val VIDEO_MIME_PREFIXES = arrayOf("video/")
    }

    private val _progressFlow = MutableStateFlow(ProcessingProgress(ProcessingPhase.IDLE, 0, "就绪"))
    val progressFlow: StateFlow<ProcessingProgress> = _progressFlow.asStateFlow()

    override fun getTools(): Map<String, ToolBinding> {
        return mapOf(
            "speech_to_text" to ToolBinding(
                name = "speech_to_text",
                description = "语音转文字工具：将音频或视频文件中的语音内容识别为文字。支持常见音频格式(mp3,wav,m4a,aac,ogg,flac等)和视频格式(mp4,mkv,avi,mov,webm等)，可自动从视频中提取音频轨道。参数 uri(必填): 文件URI路径; language(可选,默认auto): zh/en/auto; enable_punctuation(可选,默认true): 是否添加标点。",
                invoker = { tp, config, sp, ups -> executeSpeechToText(tp, config, sp, ups) },
            ),
        )
    }

    private fun updateProgress(phase: ProcessingPhase, percent: Int, message: String) {
        _progressFlow.value = ProcessingProgress(phase, percent.coerceIn(phase.progressRange), message)
    }

    private fun resetProgress() {
        _progressFlow.value = ProcessingProgress(ProcessingPhase.IDLE, 0, "就绪")
    }

    private fun successResult(tp: ToolPart, output: String): ToolResult {
        return ToolResult(toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.COMPLETED, output = output, endTime = System.currentTimeMillis())))
    }

    private fun detectMediaType(context: Context, uri: Uri): MediaType {
        return try {
            val contentType = context.contentResolver.getType(uri)
            if (!contentType.isNullOrBlank()) {
                when {
                    VIDEO_MIME_PREFIXES.any { contentType.startsWith(it) } -> MediaType.VIDEO
                    AUDIO_MIME_PREFIXES.any { contentType.startsWith(it) } -> MediaType.AUDIO
                    else -> detectByExtension(uri)
                }
            } else {
                detectByExtension(uri)
            }
        } catch (e: Exception) {
            DebugLog.w(TAG, "检测媒体类型失败，回退到扩展名判断: ${e.message}")
            detectByExtension(uri)
        }
    }

    private fun detectByExtension(uri: Uri): MediaType {
        val uriString = uri.toString().lowercase(Locale.getDefault())
        val path = uri.path?.lowercase(Locale.getDefault()) ?: uriString

        for (ext in VIDEO_EXTENSIONS) {
            if (path.endsWith(".$ext") || uriString.contains(".$ext")) return MediaType.VIDEO
        }
        for (ext in AUDIO_EXTENSIONS) {
            if (path.endsWith(".$ext") || uriString.contains(".$ext")) return MediaType.AUDIO
        }

        if (uriString.contains("video") || path.contains("video")) return MediaType.VIDEO
        if (uriString.contains("audio") || path.contains("audio")) return MediaType.AUDIO

        return MediaType.UNKNOWN
    }

    @Tool("speech_to_text")
    @ToolDescription("将音频或视频文件中的语音内容识别为文字。支持多种音视频格式，可自动从视频中提取音频轨道进行识别。")
    suspend fun executeSpeechToText(
        tp: ToolPart,
        config: ApiConfig,
        systemPrompt: String,
        useProviderSearch: Boolean,
    ): ToolResult {
        resetProgress()

        val rawUri = tp.state.input["uri"]?.trim()
            ?: tp.state.input["file_uri"]?.trim()
            ?: tp.state.input["path"]?.trim()
            ?: return emptyResult(tp, buildMissingUriError())

        val languageStr = (tp.state.input["language"]?.trim() ?: "auto").lowercase()
        val enablePunctuation = tp.state.input["enable_punctuation"]?.toBooleanStrictOrNull() ?: true

        val language = parseLanguage(languageStr)

        DebugLog.i(TAG, "executeSpeechToText: uri=${rawUri.take(80)}, language=$language, punctuation=$enablePunctuation")

        val uri = runCatching { Uri.parse(rawUri) }.getOrElse {
            return emptyResult(tp, buildInvalidUriError(rawUri))
        }

        val sttConfig = SttConfig(
            language = language,
            enablePunctuation = enablePunctuation,
        )

        return withContext(Dispatchers.IO) {
            runCatching {
                val startTime = System.currentTimeMillis()

                updateProgress(ProcessingPhase.DECODING, 5, "检测媒体类型...")

                // Detect media type FIRST, before validation.
                // validateAudioFile() rejects files that MediaExtractor can't parse as audio,
                // which includes video files. Video files contain valid audio tracks and should
                // be processed via the video extraction path, not rejected.
                val mediaType = detectMediaType(context, uri)
                DebugLog.i(TAG, "媒体类型: $mediaType")

                // Only run audio-specific validation for AUDIO type files.
                // Video files will be validated during audio track extraction.
                if (mediaType == MediaType.AUDIO) {
                    updateProgress(ProcessingPhase.DECODING, 8, "验证音频文件...")

                    val validation = AudioProcessor.validateAudioFile(context, uri)
                    if (!validation.isValid) {
                        return@withContext emptyResult(tp, buildValidationError(validation.errorCode, validation.errorMessage))
                    }
                }

                updateProgress(ProcessingPhase.DECODING, 12, "读取元数据...")

                val metadata = AudioProcessor.getAudioMetadata(context, uri)
                if (metadata != null) {
                    DebugLog.i(TAG, "元数据: duration=${AudioProcessor.formatDuration(metadata.durationMs)} sampleRate=${metadata.sampleRate} channels=${metadata.channels} format=${metadata.format}")
                    validateFileSize(metadata)
                }

                when (mediaType) {
                    MediaType.VIDEO -> processVideoFile(uri, tp, metadata)
                    MediaType.AUDIO -> processAudioFile(uri, tp, metadata)
                    MediaType.UNKNOWN -> processUnknownMedia(uri, tp, metadata)
                }.let { preprocessResult ->
                    if (preprocessResult != null) {
                        return@withContext preprocessResult
                    }
                }

                updateProgress(ProcessingPhase.DECODING, 28, "预处理完成")

                val engine = asrManager.getCachedEngine()
                if (engine == null || !engine.isAvailable) {
                    // 尝试初始化引擎
                    try {
                        asrManager.getEngine()
                    } catch (e: Exception) {
                        DebugLog.e(TAG, "引擎初始化失败: ${e.message}")
                        return@withContext emptyResult(tp, buildEngineUnavailableError())
                    }
                }

                updateProgress(ProcessingPhase.RECOGNIZING, 50, "开始识别...")

                DebugLog.i(TAG, "开始识别...")
                val result: SttResult = asrManager.transcribeFile(uri)

                updateProgress(ProcessingPhase.RECOGNIZING, 95, "格式化结果...")

                val processingTime = System.currentTimeMillis() - startTime

                if (result.text.isBlank()) {
                    DebugLog.w(TAG, "识别结果为空 engine=${result.engineType} model=${result.modelUsed}")
                    return@withContext emptyResult(tp, buildEmptyResultError(result))
                }

                val outputText = formatSuccessResult(result, processingTime, metadata, mediaType)

                DebugLog.i(TAG, "成功! text_len=${result.text.length} time=${processingTime}ms engine=${result.engineType}")

                updateProgress(ProcessingPhase.IDLE, 100, "完成")
                successResult(tp, outputText)
            }.getOrElse { e ->
                updateProgress(ProcessingPhase.IDLE, -1, "错误: ${e.message}")
                handleError(e, tp)
            }
        }
    }

    private suspend fun processVideoFile(uri: Uri, tp: ToolPart, metadata: AudioMetadata?): ToolResult? {
        updateProgress(ProcessingPhase.DECODING, 18, "检测到视频文件，提取音频轨道...")
        DebugLog.i(TAG, "处理视频文件: 提取音频轨道")

        return try {
            val processed = AudioProcessor.extractAudioFromVideo(context, uri)
            if (processed == null) {
                emptyResult(tp, buildVideoExtractionError())
            } else {
                DebugLog.i(TAG, "视频音频提取成功 duration=${AudioProcessor.formatDuration(processed.metadata.durationMs)}")
                null
            }
        } catch (e: SecurityException) {
            emptyResult(tp, buildPermissionError(e))
        } catch (e: OutOfMemoryError) {
            System.gc()
            emptyResult(tp, buildMemoryError())
        } catch (e: Exception) {
            emptyResult(tp, buildVideoExtractionError(e.message))
        }
    }

    private suspend fun processAudioFile(uri: Uri, tp: ToolPart, metadata: AudioMetadata?): ToolResult? {
        updateProgress(ProcessingPhase.DECODING, 18, "解码音频文件...")
        DebugLog.i(TAG, "处理音频文件")
        return null
    }

    private suspend fun processUnknownMedia(uri: Uri, tp: ToolPart, metadata: AudioMetadata?): ToolResult? {
        updateProgress(ProcessingPhase.DECODING, 18, "尝试自动识别媒体格式...")
        DebugLog.w(TAG, "未知媒体格式，尝试作为音频处理")
        return null
    }

    private fun parseLanguage(langStr: String): SttLanguage {
        return when (langStr) {
            "zh", "chinese", "中文" -> SttLanguage.CHINESE
            "en", "english", "英文" -> SttLanguage.ENGLISH
            else -> SttLanguage.AUTO
        }
    }

    private fun validateFileSize(metadata: AudioMetadata?) {
        if (metadata != null && metadata.fileSizeBytes > 0) {
            val sizeMB = metadata.fileSizeBytes / (1024.0 * 1024.0)
            if (sizeMB > MAX_FILE_SIZE_MB) {
                DebugLog.w(TAG, "文件较大: ${String.format("%.1f", sizeMB)} MB (限制 ${MAX_FILE_SIZE_MB} MB)")
            }
        }
    }

    private fun formatSuccessResult(result: SttResult, processingTimeMs: Long, metadata: AudioMetadata?, mediaType: MediaType): String {
        val charCount = result.text.length
        val confidencePercent = if (result.confidence > 0) String.format("%.1f", result.confidence * 100) else "N/A"
        val processingTimeSec = String.format("%.1f", processingTimeMs / 1000.0)
        val durationStr = if (result.durationMs > 0) AudioProcessor.formatDuration(result.durationMs)
            else if (metadata?.durationMs ?: 0 > 0) AudioProcessor.formatDuration(metadata?.durationMs ?: 0)
            else "未知"

        val mediaLabel = when (mediaType) {
            MediaType.VIDEO -> "视频"
            MediaType.AUDIO -> "音频"
            MediaType.UNKNOWN -> "媒体"
        }

        return buildString {
            appendLine("[完成] 转录完成")
            appendLine()

            appendLine("**转录文本**:")
            appendLine(result.text)
            appendLine()

            appendLine("**统计信息**:")
            appendLine("- 时长: $durationStr")
            appendLine("- 字数: ${String.format("%,d", charCount)} 字符")
            appendLine("- 引擎: ${formatEngineName(result.engineType)}${if (result.modelUsed.isNotBlank()) " (${result.modelUsed})" else ""}")
            appendLine("- 置信度: ${confidencePercent}%")
            appendLine("- 处理时间: ${processingTimeSec} 秒")
            appendLine("- 媒体类型: $mediaLabel")

            if (result.segments.size > 1) {
                appendLine()
                appendLine("**分段详情**（共 ${result.segments.size} 段）:")
                result.segments.forEachIndexed { idx, seg ->
                    val startFmt = AudioProcessor.formatDuration(seg.startTimeMs)
                    val endFmt = AudioProcessor.formatDuration(seg.endTimeMs)
                    val confStr = if (seg.confidence > 0) String.format("%.0f", seg.confidence * 100) else "-"
                    appendLine("  [${idx + 1}] `$startFmt` → `$endFmt` ($confStr%): ${seg.text.take(80)}${if (seg.text.length > 80) "..." else ""}")
                }
            }

            appendLine()
            appendLine("**操作建议**:")
            appendLine("- 点击「复制」可复制全文")
            appendLine("- 点击「发送给 AI 分析」可让 LLM 总结要点、提取关键信息")
            appendLine("- 如需导出，可请求 AI 将内容整理为 Markdown 或其他格式")
        }
    }

    private fun formatEngineName(engineType: top.hsyscn.opedrgent.stt.EngineType): String {
        return when (engineType) {
            top.hsyscn.opedrgent.stt.EngineType.SHERPA_ONNX -> "Sherpa-ONNX (Paraformer)"
            top.hsyscn.opedrgent.stt.EngineType.ANDROID_SPEECH_RECOGNIZER -> "Android SpeechRecognizer"
            top.hsyscn.opedrgent.stt.EngineType.MIMO_ASR -> "MiMO ASR (在线)"
            top.hsyscn.opedrgent.stt.EngineType.STEP_AUDIO_ASR -> "StepAudio 2.5 ASR (阶跃云端)"
        }
    }

    private fun handleError(e: Throwable, tp: ToolPart): ToolResult {
        return when (e) {
            is SecurityException -> {
                DebugLog.e(TAG, "权限异常: ${e.message}", e)
                emptyResult(tp, buildPermissionError(e))
            }
            is OutOfMemoryError -> {
                System.gc()
                DebugLog.e(TAG, "内存不足", e as Throwable?)
                emptyResult(tp, buildMemoryError())
            }
            is IllegalStateException -> {
                DebugLog.e(TAG, "状态异常: ${e.message}", e)
                if (e.message?.contains("未初始化") == true ||
                    e.message?.contains("not initialized", ignoreCase = true) == true ||
                    e.message?.contains("初期化されていません") == true ||
                    e.message?.contains("initialize") == true) {
                    emptyResult(tp, buildModelNotDownloadedError())
                } else {
                    emptyResult(tp, buildRecognitionError(e.message))
                }
            }
            is IllegalArgumentException -> {
                DebugLog.e(TAG, "参数异常: ${e.message}", e)
                emptyResult(tp, buildFormatNotSupportedError(e.message))
            }
            else -> {
                DebugLog.e(TAG, "未知异常: ${e.message}", e)
                emptyResult(tp, buildRecognitionError(e.message))
            }
        }
    }

    private fun buildMissingUriError(): String {
        return """❌ 缺少必填参数

请提供文件的 URI 路径，例如：
- `content://media/external/audio/media/12345`
- `file:///sdcard/Download/recording.mp3`
- `/sdcard/Download/meeting.mp4`

支持通过文件选择器获取 URI 后传入。"""
    }

    private fun buildInvalidUriError(rawUri: String): String {
        return """❌ 无效的 URI 格式

提供的路径无法解析: `${rawUri.take(60)}`

**支持的 URI 格式：**
- `content://` — Android 内容提供者（推荐）
- `file://` 或绝对路径 — 本地文件
- `android.resource://` — 应用内资源

请确认路径正确且文件存在。"""
    }

    private fun buildValidationError(errorCode: Int, validationError: String?): String {
        val detail = validationError ?: "未知验证错误"
        return when (errorCode) {
            AudioProcessor.ValidationErrorCode.TOO_LONG ->
                """⏰ 文件时长超限

$detail

**建议操作：**
- 使用音频编辑软件截取前 30 分钟
- 分段处理后合并结果
- 对于会议录音，建议按议题拆分"""
            AudioProcessor.ValidationErrorCode.PERMISSION_DENIED ->
                buildPermissionError(null)
            AudioProcessor.ValidationErrorCode.ZERO_DURATION,
            AudioProcessor.ValidationErrorCode.BAD_SAMPLE_RATE ->
                """🔧 文件可能损坏

$detail

**建议操作：**
- 确认文件可以正常播放
- 尝试使用其他播放器打开
- 重新录制或转换格式"""
            AudioProcessor.ValidationErrorCode.UNSUPPORTED_FORMAT ->
                """🔍 文件格式异常

$detail

**建议操作：**
- 尝试将文件转换为 WAV (PCM 16-bit 16kHz) 格式
- 检查文件是否完整下载
- 使用专业音频工具修复"""
            else ->
                """❌ 文件验证失败

$detail

**建议操作：**
- 确认文件路径正确且存在
- 检查文件是否受保护或加密
- 尝试转换为支持的格式（MP3/WAV/M4A）"""
        }
    }

    private fun buildVideoExtractionError(detail: String? = null): String {
        val extra = if (detail != null) "\n原因: $detail" else ""
        return """🎬 视频音频提取失败

无法从视频中提取音频轨道。$extra

**可能的原因：**
1. 视频文件不包含音频轨道（纯画面/静音）
2. 视频编码格式不受支持
3. 文件已损坏或不完整

**建议操作：**
- 先用播放器确认视频是否有声音
- 尝试使用 FFmpeg 提取音频: `ffmpeg -i input.mp4 -vn -acodec pcm_s16le output.wav`
- 将视频转换为 MP4 (H.264 + AAC) 格式后重试"""
    }

    private fun buildEngineUnavailableError(): String {
        return """🔇 语音识别引擎不可用

STT 引擎未就绪，无法执行识别。

**解决步骤：**
1. 请先下载语音识别模型（约 20~220MB，取决于模型大小）
2. 在设置页面选择合适的模型:
   - **FunASR-Nano** (~20MB) — 低端设备首选
   - **SenseVoice-Small** (~40MB) — 平衡精度与速度
   - **Paraformer** (~220MB) — 最高精度
3. 下载完成后自动初始化引擎

💡 首次使用建议从 SenseVoice-Small 开始。"""
    }

    private fun buildEmptyResultError(result: SttResult): String {
        return """🔕 未检测到有效语音内容

识别过程已完成，但未产生有效文本。

**可能原因：**
1. 文件中无语音内容或仅有背景音乐/噪音
2. 语音过短（建议至少 2 秒以上）
3. 音频质量过低（信噪比太低）
4. 语言与设置不匹配（如中文音频设为英文模式）
5. 文件使用了不常见的编码格式

**建议操作：**
- 用播放器确认文件有清晰的语音内容
- 尝试指定正确的 language 参数（zh/en）
- 如果是带背景音的录音，尝试降噪后重试
- 转换为 WAV (16kHz PCM) 格式后再次尝试"""
    }

    private fun buildModelNotDownloadedError(): String {
        return """📦 模型未下载或未初始化

语音识别模型尚未准备就绪。

**请先下载模型：**
点击此处开始下载 STT 模型（根据设备性能选择）：

| 模型 | 大小 | 适用设备 | 特点 |
|------|------|----------|------|
| FunASR-Nano | ~20MB | <4GB RAM | 最轻量，速度快 |
| SenseVoice | ~40MB | 4-6GB RAM | 推荐，平衡 |
| Paraformer | ~220MB | ≥6GB RAM | 最高精度 |

下载完成后系统会自动初始化引擎。"""
    }

    private fun buildFormatNotSupportedError(detail: String? = null): String {
        val extra = if (detail != null) "\n详情: $detail" else ""
        return """🚫 文件格式不支持

当前文件格式无法处理。$extra

**支持的音频格式：**
MP3, WAV, M4A, AAC, OGG, FLAC, AMR, WMA, OPUS, PCM

**支持的视频格式（自动提取音频）：**
MP4, MKV, AVI, MOV, WebM, FLV, WMV, 3GP

**建议转换方式：**
- 使用 FFmpeg: `ffmpeg -i input.xxx -acodec pcm_s16le -ar 16000 -ac 1 output.wav`
- 在线转换工具（如 convertio.co）
- Audacity 导出为 WAV (PCM) 格式"""
    }

    private fun buildRecognitionError(detail: String? = null): String {
        val extra = if (detail != null) "\n原因: $detail" else ""
        return """⚠️ 识别过程中出现错误

语音识别未能正常完成。$extra

**排查步骤：**
1. 检查音频文件是否可以正常播放
2. 确认文件大小在合理范围内（< 100MB）
3. 尝试将文件转换为 WAV 格式
4. 重启应用后重试

如果问题持续，请检查：
- 设备存储空间是否充足
- 模型文件是否完整
- 是否有足够的运行内存"""
    }

    private fun buildPermissionError(e: SecurityException?): String {
        val extra = if (e != null) "\n技术详情: ${e.message}" else ""
        return """🔐 权限不足

无法读取该文件，缺少必要的存储访问权限。$extra

**解决方法：**
1. 前往「设置」→「应用权限」→ 授予「存储」或「文件访问」权限
2. 如果是 Android 13+，还需授予「读取媒体文件」权限
3. 重新选择文件并重试

💡 部分文件（如 Downloads 目录）可能需要「所有文件访问」特殊权限。"""
    }

    private fun buildMemoryError(): String {
        return """💾 内存不足

设备可用内存不足以处理此文件。

**建议方案：**
1. **使用更小的模型** — 切换到 FunASR-Nano (~20MB)
2. **缩短音频长度** — 截取前 10-15 分钟处理
3. **释放内存** — 关闭后台应用后重试
4. **降低音质** — 将音频转为 8kHz 单声道以减少内存占用

当前设备可能需要释放更多内存才能继续。"""
    }
}
