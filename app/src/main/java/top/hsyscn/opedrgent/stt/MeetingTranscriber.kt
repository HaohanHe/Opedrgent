package top.hsyscn.opedrgent.stt

import android.content.Context
import android.net.Uri
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File

/**
 * 会议转录引擎 - 基于Sherpa-ONNX的说话人分离+语音识别
 * 
 * 核心能力：
 * - 说话人分离（Speaker Diarization）：区分不同说话人
 * - 多人语音转文字：为每个说话人生成独立文本
 * - 支持长音频分段处理
 * - 完全离线运行
 * 
 * 依赖：sherpa-onnx AAR（已集成）
 * 模型需要：
 *   ASR模型: model.onnx, tokens.txt, （paraformer/sensevoice等）
 *   说话人分离模型: speaker-diarization.onnx
 * 
 * 使用示例：
 * ```
 * val engine = MeetingTranscriber(context)
 * engine.initialize(asrModelDir, diarizationModelDir)
 * val result = engine.transcribeMeeting(audioFile)
 * // result.speakers -> ["说话人0", "说话人1", ...]
 * // result.segments -> 每段文字对应哪个说话人、什么时间
 * ```
 */
class MeetingTranscriber(
    private val context: Context,
    private val config: SttConfig = SttConfig(),
) {

    companion object {
        private const val TAG = "MeetingTranscriber"
        private const val TARGET_SAMPLE_RATE = 16000
        private const val DIARIZATION_SEGMENT_MS = 1500 // 说话人分离的最小片段长度(毫秒)
    }

    private var asrRecognizer: OfflineRecognizer? = null
    private var diarizer: SpeakerDiarization? = null
    private var _isInitialized = false
    private var currentAsrModelDir: File? = null
    private var currentDiarModelDir: File? = null

    val isInitialized: Boolean get() = _isInitialized && asrRecognizer != null

    /**
     * 初始化引擎
     * @param asrModelDir ASR模型目录（包含model.onnx和tokens.txt）
     * @param diarizationModelDir 说话人分离模型目录（可选，不传则不启用说话人分离）
     */
    fun initialize(asrModelDir: File, diarizationModelDir: File? = null): Boolean {
        if (_isInitialized) {
            DebugLog.w(TAG, "已初始化，跳过")
            return true
        }
        return try {
            // 1. 初始化ASR识别器
            DebugLog.i(TAG, "初始化ASR from ${asrModelDir.absolutePath}")
            val numThreads = resolveOptimalThreadCount()
            val featConfig = FeatureConfig(
                sampleRate = TARGET_SAMPLE_RATE,
                featureDim = 80,
            )
            val modelConfig = buildAsrModelConfig(asrModelDir)
            val recognizerConfig = OfflineRecognizerConfig(
                featConfig = featConfig,
                modelConfig = modelConfig,
                numThreads = numThreads,
                provider = "cpu",
                deviceType = "cpu",
            )
            asrRecognizer = OfflineRecognizer(recognizerConfig)
            currentAsrModelDir = asrModelDir

            // 2. 初始化说话人分离器（如果提供了模型目录）
            if (diarizationModelDir != null && diarizationModelDir.exists()) {
                DebugLog.i(TAG, "初始化说话人分离 from ${diarizationModelDir.absolutePath}")
                try {
                    val diarizationConfig = SpeakerDiarizationConfig(
                        embedding = EmbeddingModelConfig(model = findFile(diarizationModelDir, "embedding.onnx")),
                        segmenter = SegmenterModelConfig(model = findFile(diarizationModelDir, "segmenter.onnx")),
                        clustering = ClusteringConfig(threshold = 0.6f),
                    )
                    diarizer = SpeakerDiarization(diarizationConfig)
                    currentDiarModelDir = diarizationModelDir
                    DebugLog.i(TAG, "说话人分离器初始化成功")
                } catch (e: Exception) {
                    DebugLog.w(TAG, "说话人分离器初始化失败，将使用单说话人模式: ${e.message}")
                }
            }

            _isInitialized = true
            DebugLog.i(TAG, "会议转录引擎初始化完成 (说话人分离=${diarizer != null})")
            true
        } catch (e: Exception) {
            DebugLog.e(TAG, "初始化失败: ${e.message}", e)
            _isInitialized = false
            false
        }
    }

    /**
     * 转录会议音频文件
     * 返回完整的会议记录，包含每个说话人的文本和时间戳
     */
    suspend fun transcribeMeeting(filePath: String): MeetingTranscriptResult {
        return withContext(Dispatchers.IO) {
            ensureInitialized()
            val startTimeMs = System.currentTimeMillis()
            DebugLog.i(TAG, "开始转录会议: $filePath")

            try {
                val file = File(filePath)
                val audioData = try {
                    val pair = AudioProcessor.decodeToPcm(context, Uri.fromFile(file))
                    pair?.first ?: FloatArray(0)
                } catch (e: Exception) {
                    DebugLog.w(TAG, "MediaCodec解码失败，尝试WAV: ${e.message}")
                    // 回退：尝试直接用WAV解码
                    AudioProcessor.readWavFile(file.absolutePath)?.let { (shorts, _) ->
                        AudioProcessor.shortArrayToFloatArray(shorts)
                    } ?: FloatArray(0)
                }
                val totalSamples = audioData.size
                val durationMs = (totalSamples.toFloat() / TARGET_SAMPLE_RATE * 1000).toLong()

                if (totalSamples == 0) {
                    return@withContext MeetingTranscriptResult.empty(durationMs)
                }

                val segments = if (diarizer != null) {
                    transcribeWithDiarization(audioData, totalSamples, durationMs)
                } else {
                    transcribeSingleSpeaker(audioData, totalSamples, durationMs)
                }

                val processingTimeMs = System.currentTimeMillis() - startTimeMs
                DebugLog.i(TAG, "转录完成: ${segments.size}段, 耗时=${processingTimeMs}ms")

                MeetingTranscriptResult(
                    segments = segments,
                    speakers = segments.map { it.speakerLabel }.distinct().sorted(),
                    durationMs = durationMs,
                    processingTimeMs = processingTimeMs,
                )
            } catch (e: Exception) {
                DebugLog.e(TAG, "转录失败: ${e.message}", e)
                MeetingTranscriptResult.error(e.message ?: "未知错误")
            }
        }
    }

    /**
     * 转录会议音频（Uri版本）
     */
    suspend fun transcribeMeeting(uri: Uri): MeetingTranscriptResult {
        return withContext(Dispatchers.IO) {
            try {
                val tempFile = copyUriToTemp(uri)
                try {
                    transcribeMeeting(tempFile.absolutePath)
                } finally {
                    tempFile.delete()
                }
            } catch (e: Exception) {
                DebugLog.e(TAG, "Uri转录失败: ${e.message}", e)
                MeetingTranscriptResult.error(e.message ?: "未知错误")
            }
        }
    }

    /**
     * 带说话人分离的转录
     */
    private suspend fun transcribeWithDiarization(
        audioData: FloatArray,
        totalSamples: Int,
        durationMs: Long,
    ): List<MeetingSegment> {
        val diarizerInstance = diarizer!!
        val recognizer = asrRecognizer!!

        DebugLog.d(TAG, "开始说话人分离...")
        
        // 使用sherpa-onnx的说话人分离
        val diarizationSegments = mutableListOf<SpeakerSegment>()
        
        try {
            // 将音频数据喂入说话人分离器
            val stream = diarizerInstance.createStream()
            stream.acceptWaveform(audioData)
            
            // 获取说话人分离结果
            // sherpa-onnx的SpeakerDiarization会返回带说话人标签的时间段
            while (diarizerInstance.isReady(stream)) {
                diarizerInstance.decode(stream)
                
                // 获取当前结果中的说话人段
                val result = diarizerInstance.getResult(stream)
                if (result != null && result.start > 0) {
                    diarizationSegments.add(
                        SpeakerSegment(
                            startMs = (result.start * 1000).toLong(),
                            endMs = (result.end * 1000).toLong(),
                            speakerIndex = result.speaker.coerceAtLeast(0),
                        )
                    )
                }
            }
            stream.release()
        } catch (e: Exception) {
            DebugLog.w(TAG, "说话人分离异常，回退到单说话人模式: ${e.message}")
            return transcribeSingleSpeaker(audioData, totalSamples, durationMs)
        }

        // 如果说话人分离没有结果，回退到单说话人模式
        if (diarizationSegments.isEmpty()) {
            DebugLog.w(TAG, "说话人分离无结果，使用单说话人模式")
            return transcribeSingleSpeaker(audioData, totalSamples, durationMs)
        }

        DebugLog.d(TAG, "说话人分离完成: ${diarizationSegments.size}个片段")

        // 对每个说话人片段进行ASR识别
        val meetingSegments = mutableListOf<MeetingSegment>()
        for (seg in diarizationSegments) {
            val startSample = ((seg.startMs / 1000.0) * TARGET_SAMPLE_RATE).toInt().coerceIn(0, totalSamples - 1)
            val endSample = ((seg.endMs / 1000.0) * TARGET_SAMPLE_RATE).toInt().coerceIn(startSample + 1, totalSamples)
            
            if (endSample <= startSample) continue
            
            val chunkSize = endSample - startSample
            val chunk = FloatArray(chunkSize)
            System.arraycopy(audioData, startSample, chunk, 0, chunkSize)

            val text = recognizeChunk(recognizer, chunk)?.trim() ?: ""
            if (text.isNotEmpty()) {
                meetingSegments.add(
                    MeetingSegment(
                        text = text,
                        speakerLabel = "说话人${seg.speakerIndex}",
                        startTimeMs = seg.startMs,
                        endTimeMs = seg.endMs,
                    )
                )
            }
        }

        return mergeAdjacentSegments(meetingSegments)
    }

    /**
     * 单说话人模式转录（无说话人分离时使用）
     * 按30秒分段处理长音频
     */
    private suspend fun transcribeSingleSpeaker(
        audioData: FloatArray,
        totalSamples: Int,
        durationMs: Long,
    ): List<MeetingSegment> {
        val recognizer = asrRecognizer!!
        val segmentLengthSamples = minOf(TARGET_SAMPLE_RATE * 30, totalSamples) // 30秒一段
        val segments = mutableListOf<MeetingSegment>()
        var offset = 0

        while (offset < totalSamples) {
            val end = minOf(offset + segmentLengthSamples, totalSamples)
            val chunk = FloatArray(end - offset)
            System.arraycopy(audioData, offset, chunk, 0, chunk.size)

            val segStartMs = (offset.toFloat() / TARGET_SAMPLE_RATE * 1000).toLong()
            val segEndMs = (end.toFloat() / TARGET_SAMPLE_RATE * 1000).toLong()

            val text = recognizeChunk(recognizer, chunk)?.trim() ?: ""
            if (text.isNotEmpty()) {
                segments.add(
                    MeetingSegment(
                        text = text,
                        speakerLabel = "说话人0",
                        startTimeMs = segStartMs,
                        endTimeMs = segEndMs,
                    )
                )
            }
            offset = end
        }

        return segments
    }

    private fun recognizeChunk(recognizer: OfflineRecognizer, samples: FloatArray): String? {
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples)
            recognizer.decode(stream)
            recognizer.getResult(stream).text?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: OutOfMemoryError) {
            DebugLog.e(TAG, "识别段内存不足")
            null
        } catch (e: Exception) {
            DebugLog.e(TAG, "识别段异常: ${e.message}")
            null
        } finally {
            stream.release()
        }
    }

    /**
     * 合并同一说话人的相邻段落
     */
    private fun mergeAdjacentSegments(segments: List<MeetingSegment>): List<MeetingSegment> {
        if (segments.isEmpty()) return emptyList()

        val merged = mutableListOf<MeetingSegment>()
        var current = segments[0]

        for (i in 1 until segments.size) {
            val next = segments[i]
            // 同一说话人且间隔小于2秒则合并
            if (next.speakerLabel == current.speakerLabel &&
                next.startTimeMs - current.endTimeMs < 2000
            ) {
                current = current.copy(
                    text = current.text + "\n" + next.text,
                    endTimeMs = next.endTimeMs,
                )
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)

        return merged
    }

    private fun buildAsrModelConfig(modelDir: File): OfflineModelConfig {
        val modelFile = findFile(modelDir, "model.onnx", "model.int8.onnx")
        val tokensFile = findFile(modelDir, "tokens.txt", "tokens", "lang.txt", "itn.tokens")

        return when (config.modelType) {
            ModelType.SENSE_VOICE_SMALL -> OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(model = modelFile),
                tokens = tokensFile,
                useItn = true,
                numThreads = 0,
                provider = "cpu",
                deviceType = "cpu",
            )
            else -> OfflineModelConfig(
                paraformer = OfflineParaformerModelConfig(model = modelFile),
                tokens = tokensFile,
                numThreads = 0,
                provider = "cpu",
                deviceType = "cpu",
            )
        }
    }

    private fun findFile(dir: File, vararg candidates: String): String {
        for (name in candidates) {
            val f = File(dir, name)
            if (f.exists()) return f.absolutePath
        }
        throw IllegalArgumentException("未找到所需文件 (${candidates.joinToString("/")}) 在目录: ${dir.absolutePath}")
    }

    private fun resolveOptimalThreadCount(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return when {
            cores >= 8 -> 4
            cores >= 4 -> 2
            else -> 1
        }
    }

    private fun ensureInitialized() {
        check(_isInitialized && asrRecognizer != null) { "MeetingTranscriber 未初始化" }
    }

    private suspend fun copyUriToTemp(uri: Uri): File {
        return withContext(Dispatchers.IO) {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw java.io.IOException("无法打开Uri: $uri")
            val tempFile = File(context.cacheDir, "meeting_temp_${System.currentTimeMillis()}.wav")
            try {
                tempFile.outputStream().use { output ->
                    inputStream.use { input -> input.copyTo(output) }
                }
                tempFile
            } catch (e: Exception) {
                if (tempFile.exists()) tempFile.delete()
                throw e
            }
        }
    }

    fun close() {
        try {
            diarizer?.release()
            diarizer = null
            asrRecognizer?.release()
            asrRecognizer = null
            _isInitialized = false
            DebugLog.i(TAG, "资源已释放")
        } catch (e: Exception) {
            DebugLog.e(TAG, "关闭异常: ${e.message}", e)
        }
    }
}

// ---- 数据类 ----

/**
 * 会议转录完整结果
 */
data class MeetingTranscriptResult(
    val segments: List<MeetingSegment>,
    val speakers: List<String>,
    val durationMs: Long,
    val processingTimeMs: Long,
    val error: String? = null,
) {
    /** 完整文本（按时间顺序拼接） */
    val fullText: String get() = segments.joinToString("\n") { "[${it.speakerLabel}] ${it.text}" }
    
    /** 是否成功 */
    val isSuccess: Boolean get() = error == null && segments.isNotEmpty()

    companion object {
        fun empty(durationMs: Long) = MeetingTranscriptResult(
            segments = emptyList(), speakers = emptyList(),
            durationMs = durationMs, processingTimeMs = 0,
        )
        fun error(msg: String) = MeetingTranscriptResult(
            segments = emptyList(), speakers = emptyList(),
            durationMs = 0, processingTimeMs = 0, error = msg,
        )
    }
}

/**
 * 会议中的一个发言段落
 */
data class MeetingSegment(
    val text: String,
    val speakerLabel: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
)

/**
 * 说话人分离的一个时间段
 */
private data class SpeakerSegment(
    val startMs: Long,
    val endMs: Long,
    val speakerIndex: Int,
)
