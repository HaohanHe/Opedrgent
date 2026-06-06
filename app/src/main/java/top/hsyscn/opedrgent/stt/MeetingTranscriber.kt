package top.hsyscn.opedrgent.stt

import android.content.Context
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 会议转录器：将录音文件转写为带说话人标签的文本段落。
 *
 * 使用 Sherpa-ONNX 的 SpeakerDiarization 实现说话人分离。
 * 如果说话人分离模型不可用或初始化失败，自动降级为单说话人模式。
 */
class MeetingTranscriber(
    private val context: Context,
    private val config: SttConfig = SttConfig(),
) {

    companion object {
        private const val TAG = "MeetingTranscriber"
        /** 说话人分离的分块大小（秒），每次喂入这么多音频给 diarizer */
        private const val DIARIZATION_CHUNK_SEC = 5f
    }

    // ASR 引擎（复用 SherpaOnnxEngine）
    private var asrEngine: SherpaOnnxEngine? = null
    // 说话人分离器
    private var diarizerInstance: SpeakerDiarization? = null
    private var _isInitialized = AtomicBoolean(false)
    private var _isDiarizationReady = AtomicBoolean(false)

    /**
     * 初始化 ASR 引擎 + 可选的说话人分离器。
     *
     * @param modelDir 模型目录路径
     * @param enableDiarization 是否启用说话人分离。如果为 true 但模型不支持，会自动降级。
     * @return true 表示至少 ASR 可用
     */
    suspend fun initialize(modelDir: File, enableDiarization: Boolean = true): Boolean =
        withContext(Dispatchers.IO) {
            if (_isInitialized.get()) return@withContext true

            try {
                DebugLog.i(TAG, "初始化会议转录器 (diarization=$enableDiarization)")

                // 1. 初始化 ASR 引擎
                asrEngine = SherpaOnnxEngine(context, config).also { engine ->
                    if (!engine.initialize(modelDir)) {
                        throw Exception("ASR 引擎初始化失败")
                    }
                }

                // 2. 尝试初始化说话人分离器
                if (enableDiarization) {
                    tryInitDiarizer(modelDir)
                }

                _isInitialized.set(true)

                DebugLog.i(
                    TAG,
                    "初始化完成 (ASR=${asrEngine != null}, Diarization=${_isDiarizationReady.get()})"
                )
                true
            } catch (e: Exception) {
                DebugLog.e(TAG, "初始化失败: ${e.message}", e)
                cleanup()
                false
            }
        }

    /**
     * 转录音频文件。
     *
     * 如果说话人分离可用，返回带 speakerLabel 的段落；
     * 否则所有段落标记为 "Speaker_0"。
     *
     * @param audioFile WAV/PCM 音频文件
     * @return 转录结果列表
     */
    suspend fun transcribe(audioFile: File): TranscriptionResult =
        withContext(Dispatchers.IO) {
            ensureInitialized()

            DebugLog.i(TAG, "开始转录: ${audioFile.name} (${audioFile.length() / 1024}KB)")

            try {
                if (_isDiarizationReady.get()) {
                    transcribeWithDiarization(audioFile)
                } else {
                    transcribeAsrOnly(audioFile)
                }
            } catch (e: Exception) {
                DebugLog.e(TAG, "转录异常: ${e.message}", e)
                TranscriptionResult(
                    segments = emptyList(),
                    fullText = "",
                    durationMs = 0,
                    hasDiarization = false,
                    error = "转录失败: ${e.message}",
                )
            }
        }

    fun close() {
        cleanup()
    }

    // ==================== 内部实现 ====================

    /**
     * 尝试初始化说话人分离器。
     * 失败时静默降级（不抛异常），设置 _isDiarizationReady=false。
     */
    private fun tryInitDiarizer(modelDir: File) {
        try {
            DebugLog.i(TAG, "跳过说话人分离器初始化（需要单独下载说话人分离模型）")
            _isDiarizationReady.set(false)
            diarizerInstance = null
        } catch (e: Exception) {
            DebugLog.w(TAG, "说话人分离器初始化失败: ${e.message}")
            _isDiarizationReady.set(false)
            diarizerInstance = null
        }
    }

    /**
     * 带说话人分离的转录。
     *
     * 分块喂入音频到 diarizer（每 [DIARIZATION_CHUNK_SEC] 秒一块），
     * 对每个检测到的语音段用 ASR 转录文本。
     */
    private suspend fun transcribeWithDiarization(audioFile: File): TranscriptionResult {
        // 说话人分离功能需要单独下载模型，暂未实现
        DebugLog.w(TAG, "说话人分离不可用，降级为纯 ASR 模式")
        return transcribeAsrOnly(audioFile)
    }

    /**
     * 纯 ASR 转录（无说话人分离）。
     * 将音频分段后逐段识别，所有段落标记为 "Speaker_0"。
     */
    private suspend fun transcribeAsrOnly(audioFile: File): TranscriptionResult {
        val asr = asrEngine!!
        val result = asr.recognizeFile(audioFile.absolutePath)

        val segments = result.segments.mapIndexed { index, seg ->
            TranscriptSegment(
                text = seg.text,
                startTimeMs = seg.startTimeMs,
                endTimeMs = seg.endTimeMs,
                speakerLabel = "Speaker_0",
                confidence = seg.confidence,
            )
        }.toMutableList()

        DebugLog.i(TAG, "纯 ASR 转录完成: ${segments.size} 个段")

        return TranscriptionResult(
            segments = segments,
            fullText = result.text,
            durationMs = result.durationMs,
            hasDiarization = false,
        )
    }

    private fun findDiarizationModel(dir: File, vararg candidates: String): String {
        for (name in candidates) {
            val f = File(dir, name)
            if (f.exists()) return f.absolutePath
        }
        throw IllegalArgumentException("未找到说话人分离模型文件: ${candidates.joinToString("/")}")
    }

    private fun ensureInitialized() {
        if (!_isInitialized.get()) {
            throw IllegalStateException("MeetingTranscriber 未初始化")
        }
    }

    private fun cleanup() {
        try {
            diarizerInstance?.release()
            diarizerInstance = null
            asrEngine?.close()
            asrEngine = null
            _isInitialized.set(false)
            _isDiarizationReady.set(false)
        } catch (e: Exception) {
            DebugLog.w(TAG, "cleanup 异常: ${e.message}")
        }
    }

    data class TranscriptSegment(
        val text: String,
        val startTimeMs: Long,
        val endTimeMs: Long,
        val speakerLabel: String = "Speaker_0",
        val confidence: Float = 1f,
    )

    data class TranscriptionResult(
        val segments: List<TranscriptSegment>,
        val fullText: String,
        val durationMs: Long,
        val hasDiarization: Boolean,
        val error: String? = null,
    )
}
