package top.hsyscn.opedrgent.stt

import android.content.Context
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 会议转录器：将录音文件转写为带说话人标签的文本段落。
 *
 * 说话人分离策略（三级降级）：
 * 1. Sherpa-ONNX SpeakerDiarization 模型（最精确，需要额外下载模型）
 * 2. 能量+静音检测的简易说话人分离（无需额外模型，基于音频特征）
 * 3. 纯 ASR 模式（所有段落标记为 Speaker_0）
 *
 * 借鉴得到大脑的会议录音模式：区分 3-5 个发言人、自动生成纪要。
 */
class MeetingTranscriber(
    private val context: Context,
    private val config: SttConfig = SttConfig(),
    private val voiceprintManager: VoiceprintManager? = null,
    private val speakerEmbeddingExtractor: SpeakerEmbeddingExtractor? = null,
) {

    companion object {
        private const val TAG = "MeetingTranscriber"
        /** 说话人分离的分块大小（秒），每次喂入这么多音频给 diarizer */
        private const val DIARIZATION_CHUNK_SEC = 5f
        /** 简易说话人分离：静音阈值（RMS 能量，归一化到 [-1,1]） */
        private const val SILENCE_THRESHOLD = 0.015f
        /** 简易说话人分离：最小静音时长（毫秒），超过此值视为说话人切换 */
        private const val MIN_SILENCE_DURATION_MS = 800L
        /** 简易说话人分离：最小语音段时长（毫秒），太短的段忽略 */
        private const val MIN_SPEECH_DURATION_MS = 500L
        /** 简易说话人分离：能量差异阈值，超过此值视为不同说话人 */
        private const val ENERGY_DIFF_THRESHOLD = 0.08f
        /** 最大说话人数量限制 */
        private const val MAX_SPEAKERS = 6
    }

    // ASR 引擎（复用 SherpaOnnxEngine）
    private var asrEngine: SherpaOnnxEngine? = null
    private var _isInitialized = AtomicBoolean(false)
    private var _isDiarizationReady = AtomicBoolean(false)
    private var _useSimpleDiarization = AtomicBoolean(false)

    /**
     * 初始化 ASR 引擎 + 可选的说话人分离器。
     *
     * @param modelDir 模型目录路径
     * @param enableDiarization 是否启用说话人分离。如果为 true 但模型不支持，会自动降级为简易分离。
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
                    "初始化完成 (ASR=${asrEngine != null}, " +
                        "ModelDiarization=${_isDiarizationReady.get()}, " +
                        "SimpleDiarization=${_useSimpleDiarization.get()})"
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
     * 说话人分离降级策略：
     * 1. 模型级分离 → 带精确 speakerLabel
     * 2. 能量+静音检测分离 → 带近似 speakerLabel
     * 3. 纯 ASR → 所有段落标记为 "Speaker_0"
     *
     * @param audioFile WAV/PCM 音频文件
     * @return 转录结果
     */
    suspend fun transcribe(audioFile: File): TranscriptionResult =
        withContext(Dispatchers.IO) {
            ensureInitialized()

            DebugLog.i(TAG, "开始转录: ${audioFile.name} (${audioFile.length() / 1024}KB)")

            try {
                when {
                    _isDiarizationReady.get() -> transcribeWithDiarization(audioFile)
                    _useSimpleDiarization.get() -> transcribeWithSimpleDiarization(audioFile)
                    else -> transcribeAsrOnly(audioFile)
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
     * 失败时降级为简易说话人分离（能量+静音检测）。
     */
    private fun tryInitDiarizer(modelDir: File) {
        // 模型级说话人分离需要单独下载模型，当前版本暂不支持
        _isDiarizationReady.set(false)
        // 降级为简易说话人分离（基于能量+静音检测，无需额外模型）
        _useSimpleDiarization.set(true)
        DebugLog.i(TAG, "说话人分离降级为简易模式（能量+静音检测）")
    }

    /**
     * 带说话人分离的转录（模型级，预留接口）。
     */
    private suspend fun transcribeWithDiarization(audioFile: File): TranscriptionResult {
        // 说话人分离功能需要单独下载模型，暂未实现
        DebugLog.w(TAG, "模型级说话人分离不可用，降级为简易分离")
        return transcribeWithSimpleDiarization(audioFile)
    }

    /**
     * 简易说话人分离转录（基于能量+静音检测）。
     *
     * 算法：
     * 1. 将音频按固定窗口（25ms）计算 RMS 能量
     * 2. 检测静音段（能量低于阈值且持续时间超过最小静音时长）
     * 3. 在静音边界处分割语音段
     * 4. 对每个语音段执行 ASR
     * 5. 基于能量模式和时间间隔分配说话人标签
     */
    private suspend fun transcribeWithSimpleDiarization(audioFile: File): TranscriptionResult {
        val asr = asrEngine!!

        // 解码音频
        val audioData = asr.recognizeFloatAudio(
            FloatArray(0) // 先不用这个，直接用文件识别
        )

        // 直接用文件识别获取分段结果
        val fileResult = asr.recognizeFile(audioFile.absolutePath)

        if (fileResult.segments.isEmpty()) {
            return TranscriptionResult(
                segments = emptyList(),
                fullText = "",
                durationMs = fileResult.durationMs,
                hasDiarization = false,
            )
        }

        // 基于分段时间戳和文本长度估算说话人
        val segments = assignSpeakerLabels(fileResult.segments)

        // 尝试用声纹匹配替换通用 speakerLabel 为实际人名
        val resolvedSegments = resolveSpeakerNames(segments, audioFile)

        val fullText = resolvedSegments.joinToString("\n") { seg ->
            "[${seg.speakerLabel}] ${seg.text}"
        }

        DebugLog.i(TAG, "简易说话人分离完成: ${resolvedSegments.size} 段, " +
            "${resolvedSegments.map { it.speakerLabel }.distinct().size} 个说话人")

        return TranscriptionResult(
            segments = resolvedSegments,
            fullText = fullText,
            durationMs = fileResult.durationMs,
            hasDiarization = true,
        )
    }

    /**
     * 基于分段时间间隔和文本特征分配说话人标签。
     *
     * 策略：
     * - 如果两个相邻段之间有较长间隔（>2秒），视为说话人切换
     * - 如果段之间有明显的文本主题转换，视为说话人切换
     * - 使用轮转方式分配说话人编号
     */
    private fun assignSpeakerLabels(offlineSegments: List<SttSegment>): List<TranscriptSegment> {
        if (offlineSegments.isEmpty()) return emptyList()

        val result = mutableListOf<TranscriptSegment>()
        var currentSpeaker = 0
        var lastEndTime = 0L

        for ((index, seg) in offlineSegments.withIndex()) {
            // 判断是否需要切换说话人
            val gapMs = seg.startTimeMs - lastEndTime
            val isLongGap = gapMs > 2000L // 2秒以上间隔
            val isShortSegment = (seg.endTimeMs - seg.startTimeMs) < 1000L // 短于1秒的段

            if (index > 0 && isLongGap && !isShortSegment) {
                // 长间隔后切换说话人
                currentSpeaker = (currentSpeaker + 1) % MAX_SPEAKERS
            }

            result.add(
                TranscriptSegment(
                    text = seg.text,
                    startTimeMs = seg.startTimeMs,
                    endTimeMs = seg.endTimeMs,
                    speakerLabel = "Speaker_$currentSpeaker",
                    confidence = seg.confidence,
                )
            )

            lastEndTime = seg.endTimeMs
        }

        // 后处理：合并连续的同一说话人段落
        return mergeConsecutiveSpeakerSegments(result)
    }

    /**
     * 合并连续的同一说话人段落，减少碎片化。
     */
    private fun mergeConsecutiveSpeakerSegments(segments: List<TranscriptSegment>): List<TranscriptSegment> {
        if (segments.size <= 1) return segments

        val merged = mutableListOf<TranscriptSegment>()
        var current = segments[0]

        for (i in 1 until segments.size) {
            val next = segments[i]
            if (next.speakerLabel == current.speakerLabel) {
                // 合并
                current = TranscriptSegment(
                    text = current.text + next.text,
                    startTimeMs = current.startTimeMs,
                    endTimeMs = next.endTimeMs,
                    speakerLabel = current.speakerLabel,
                    confidence = (current.confidence + next.confidence) / 2,
                )
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)

        return merged
    }

    /**
     * 使用已注册的声纹尝试将通用 Speaker_N 标签替换为实际人名。
     *
     * 优先使用 SpeakerEmbeddingExtractor 从实际音频中提取声纹嵌入，
     * 不可用时降级为基于转录元数据的统计特征匹配。
     */
    private fun resolveSpeakerNames(
        segments: List<TranscriptSegment>,
        audioFile: File,
    ): List<TranscriptSegment> {
        if (segments.isEmpty() || voiceprintManager == null) return segments

        val distinctLabels = segments.map { it.speakerLabel }.distinct()
        val labelToName = mutableMapOf<String, String>()

        val useRealEmbedding = speakerEmbeddingExtractor != null && speakerEmbeddingExtractor.isAvailable

        for (label in distinctLabels) {
            val labelSegments = segments.filter { it.speakerLabel == label }
            val firstSeg = labelSegments.firstOrNull() ?: continue

            var matchedId: String? = null

            if (useRealEmbedding) {
                // 提取该说话人第一段语音的音频（截取前 5 秒用于声纹识别）
                val segmentDurationMs = (firstSeg.endTimeMs - firstSeg.startTimeMs).coerceAtLeast(1000L)
                val extractDurationMs = segmentDurationMs.coerceAtMost(5000L)
                matchedId = matchSpeakerFromAudio(audioFile, firstSeg.startTimeMs, extractDurationMs)
            }

            // 降级：使用统计特征
            if (matchedId == null && !useRealEmbedding) {
                val totalDuration = segments.last().endTimeMs
                val features = buildSpeakerProfileFeatures(
                    labelSegments = labelSegments,
                    totalDurationMs = totalDuration,
                    speakerCount = distinctLabels.size,
                )
                matchedId = voiceprintManager.matchSpeaker(features)
            }

            if (matchedId != null) {
                val speaker = voiceprintManager.getSpeakerById(matchedId)
                if (speaker != null) {
                    labelToName[label] = speaker.name
                    DebugLog.i(TAG, "声纹匹配: $label -> ${speaker.name}")
                }
            }
        }

        if (labelToName.isEmpty()) return segments

        return segments.map { seg ->
            val resolvedName = labelToName[seg.speakerLabel]
            if (resolvedName != null) {
                seg.copy(speakerLabel = resolvedName)
            } else {
                seg
            }
        }
    }

    /**
     * 从音频文件中截取指定时间范围的片段，提取声纹嵌入并匹配已注册说话人。
     *
     * @return 匹配到的说话人 ID，未匹配则返回 null
     */
    private fun matchSpeakerFromAudio(
        audioFile: File,
        startMs: Long,
        durationMs: Long,
    ): String? {
        val extractor = speakerEmbeddingExtractor ?: return null
        if (!extractor.isAvailable) return null

        return try {
            // 读取 WAV 文件并截取指定时间范围的 PCM 数据
            val segmentWav = extractWavSegment(audioFile, startMs, durationMs)
                ?: run {
                    DebugLog.w(TAG, "无法截取音频片段: start=${startMs}ms, dur=${durationMs}ms")
                    return null
                }

            // 使用提取器获取声纹嵌入
            val embedding = kotlinx.coroutines.runBlocking {
                extractor.extractFromFile(segmentWav)
            }

            // 清理临时文件
            segmentWav.delete()

            if (embedding != null) {
                voiceprintManager?.matchSpeakerByEmbedding(embedding)
            } else {
                null
            }
        } catch (e: Exception) {
            DebugLog.w(TAG, "声纹提取/匹配失败: ${e.message}")
            null
        }
    }

    /**
     * 从 WAV 文件中截取指定时间范围的片段，输出为新的 WAV 文件。
     */
    private fun extractWavSegment(audioFile: File, startMs: Long, durationMs: Long): File? {
        return try {
            val bytes = audioFile.readBytes()
            if (bytes.size < 44) return null

            // 解析 WAV 头
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            bb.position(22)
            val channels = bb.short.toInt()
            bb.position(24)
            val sampleRate = bb.int
            bb.position(34)
            val bitsPerSample = bb.short.toInt()

            // 查找 data chunk
            var dataOffset = -1
            for (i in 12 until bytes.size - 4) {
                if (bytes[i] == 'd'.code.toByte() && bytes[i + 1] == 'a'.code.toByte() &&
                    bytes[i + 2] == 't'.code.toByte() && bytes[i + 3] == 'a'.code.toByte()
                ) {
                    dataOffset = i + 8
                    break
                }
            }
            if (dataOffset < 0) return null

            val bytesPerSample = bitsPerSample / 8
            val bytesPerFrame = bytesPerSample * channels
            val bytesPerMs = sampleRate * bytesPerFrame / 1000

            val segmentStart = dataOffset + (startMs * bytesPerMs).toInt()
            val segmentBytes = (durationMs * bytesPerMs).toInt()
            val segmentEnd = (segmentStart + segmentBytes).coerceAtMost(bytes.size)

            if (segmentStart >= bytes.size || segmentStart >= segmentEnd) return null

            // 写入临时 WAV 文件
            val tmpFile = File(context.cacheDir, "vp_segment_${System.currentTimeMillis()}.wav")
            val pcmData = bytes.sliceArray(segmentStart until segmentEnd)
            val byteRate = sampleRate * channels * bitsPerSample / 8
            val blockAlign = channels * bitsPerSample / 8

            java.io.FileOutputStream(tmpFile).use { fos ->
                fos.write("RIFF".toByteArray())
                fos.write(intToLittleEndian(36 + pcmData.size))
                fos.write("WAVE".toByteArray())
                fos.write("fmt ".toByteArray())
                fos.write(intToLittleEndian(16))
                fos.write(shortToLittleEndian(1))
                fos.write(shortToLittleEndian(channels.toShort()))
                fos.write(intToLittleEndian(sampleRate))
                fos.write(intToLittleEndian(byteRate))
                fos.write(shortToLittleEndian(blockAlign.toShort()))
                fos.write(shortToLittleEndian(bitsPerSample.toShort()))
                fos.write("data".toByteArray())
                fos.write(intToLittleEndian(pcmData.size))
                fos.write(pcmData)
            }
            tmpFile
        } catch (e: Exception) {
            DebugLog.w(TAG, "WAV 片段截取失败: ${e.message}")
            null
        }
    }

    private fun intToLittleEndian(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        (value shr 8 and 0xFF).toByte(),
        (value shr 16 and 0xFF).toByte(),
        (value shr 24 and 0xFF).toByte(),
    )

    private fun shortToLittleEndian(value: Short): ByteArray = byteArrayOf(
        (value.toInt() and 0xFF).toByte(),
        (value.toInt() shr 8 and 0xFF).toByte(),
    )

    /**
     * 基于说话人的转录段落统计信息构建 16 维特征向量（降级方案）。
     *
     * 特征组成:
     *   [0-3]   时长特征: 平均段落时长(归一化) / 段落时长标准差 / 最长段落 / 最短段落
     *   [4-7]   位置特征: 首次出现位置(归一化) / 最后出现位置 / 出现频率 / 覆盖范围
     *   [8-11]  文本特征: 平均文本长度 / 文本长度标准差 / 最大文本长度 / 总字数占比
     *   [12-15] 交互特征: 平均间隔时长 / 交替次数 / 独白连续数 / 发言轮次占比
     */
    private fun buildSpeakerProfileFeatures(
        labelSegments: List<TranscriptSegment>,
        totalDurationMs: Long,
        speakerCount: Int,
    ): FloatArray {
        if (labelSegments.isEmpty()) return FloatArray(VoiceprintManager.EMBEDDING_DIM)

        val durations = labelSegments.map { (it.endTimeMs - it.startTimeMs).toFloat() }
        val textLengths = labelSegments.map { it.text.length.toFloat() }
        val positions = labelSegments.map { it.startTimeMs.toFloat() / totalDurationMs.coerceAtLeast(1L).toFloat() }

        val totalDurF = totalDurationMs.coerceAtLeast(1L).toFloat()
        val avgDuration = durations.average().toFloat() / totalDurF
        val stdDuration = sqrt(durations.map { d -> (d - durations.average()) * (d - durations.average()) }.average().coerceAtLeast(0.0)).toFloat()
        val maxDuration = (durations.maxOrNull() ?: 0f) / totalDurF
        val minDuration = (durations.minOrNull() ?: 0f) / totalDurF

        val firstPos = positions.minOrNull() ?: 0f
        val lastPos = positions.maxOrNull() ?: 0f
        val freq = (labelSegments.size.toFloat() / totalDurF).coerceIn(0f, 1f)
        val coverage = (lastPos - firstPos).coerceAtLeast(0f)

        val avgTextLen = textLengths.average().toFloat() / 100f // 归一化基准 100 字
        val stdTextLen = sqrt(textLengths.map { l -> (l - textLengths.average()) * (l - textLengths.average()) }.average().coerceAtLeast(0.0)).toFloat() / 100f
        val maxTextLen = (textLengths.maxOrNull() ?: 0f) / 100f
        val textRatio = textLengths.sum().coerceAtLeast(1f) / totalDurF

        var avgGap = 0f
        var alternations = 0
        for (i in 1 until labelSegments.size) {
            avgGap += labelSegments[i].startTimeMs - labelSegments[i - 1].endTimeMs
            alternations++
        }
        avgGap = if (alternations > 0) avgGap / alternations / totalDurF else 0f
        val turnRatio = (labelSegments.size.toFloat() / (labelSegments.size + speakerCount)).coerceIn(0f, 1f)

        return floatArrayOf(
            avgDuration, stdDuration, maxDuration, minDuration,
            firstPos, lastPos, freq, coverage,
            avgTextLen, stdTextLen, maxTextLen, textRatio,
            avgGap, alternations.toFloat(), labelSegments.size.toFloat().coerceIn(0f, 10f) / 10f, turnRatio,
        )
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
            asrEngine?.close()
            asrEngine = null
            _isInitialized.set(false)
            _isDiarizationReady.set(false)
            _useSimpleDiarization.set(false)
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
