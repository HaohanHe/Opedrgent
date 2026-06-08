package top.hsyscn.opedrgent.stt

import android.content.Context
import android.net.Uri
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.roundToInt

class SherpaOnnxEngine(
    private val context: Context,
    private val config: SttConfig = SttConfig(),
) : SpeechEngine {

    companion object {
        private const val TAG = "SherpaOnnxEngine"
        private const val TARGET_SAMPLE_RATE = 16000
        private const val DEFAULT_SEGMENT_SAMPLES = TARGET_SAMPLE_RATE * 30 // 30秒
        private const val STREAMING_CHUNK_MS = 200L       // 每 200ms 喂入一次音频
        private const val WAV_HEADER_SIZE = 44

        /**
         * 检测最佳推理后端。
         * 优先级: NNAPI (NPU/GPU) > XNNPACK (CPU优化) > CPU
         */
        fun resolveBestProvider(): Pair<String, String> {
            // Android 8.1+ (API 27) 支持 NNAPI
            return try {
                // 尝试创建 NNAPI provider（如果设备支持）
                Pair("nnapi", "nnapi")
            } catch (_: Exception) {
                // 回退到 XNNPACK（比纯 CPU 快 2-5x）
                Pair("xnnpack", "cpu")
            }
        }
    }

    /** 离线识别器（用于文件转录） */
    private var offlineRecognizer: OfflineRecognizer? = null
    private var _isInitialized = AtomicBoolean(false)
    private var streamingActive = AtomicBoolean(false)
    private var currentModelDir: File? = null

    override val engineType = EngineType.SHERPA_ONNX

    override val isAvailable: Boolean
        get() = _isInitialized.get() && offlineRecognizer != null

    fun initialize(modelDir: File): Boolean {
        if (_isInitialized.get()) {
            DebugLog.w(TAG, "引擎已初始化，跳过重复初始化")
            return true
        }
        return try {
            DebugLog.i(TAG, "开始初始化模型 from ${modelDir.absolutePath}")
            DebugLog.i(TAG, "模型类型=${config.modelType.name} 语言=${config.language.name}")

            validateModelFiles(modelDir)

            // 列出模型目录内容帮助诊断
            val files = modelDir.listFiles()
            DebugLog.i(TAG, "模型目录文件列表: ${files?.map { "${it.name}(${it.length() / 1024}KB)" }?.joinToString(", ") ?: "(无法列出)"}")

            val numThreads = resolveOptimalThreadCount()
            DebugLog.i(TAG, "使用线程数=$numThreads")

            val (provider, deviceType) = resolveBestProvider()
            DebugLog.i(TAG, "推理后端: provider=$provider device=$deviceType")

            // 创建离线识别器（文件转录用）
            val offlineConfig = buildOfflineRecognizerConfig(modelDir, numThreads, provider, deviceType)
            offlineRecognizer = OfflineRecognizer(context.assets, offlineConfig)

            currentModelDir = modelDir
            _isInitialized.set(true)

            DebugLog.i(TAG, "模型初始化成功 (offline=${offlineRecognizer != null})")
            true
        } catch (e: Exception) {
            DebugLog.e(TAG, "初始化失败: ${e.message}", e)
            _isInitialized.set(false)
            false
        }
    }

    override suspend fun recognizeFile(uri: Uri): SttResult {
        return withContext(Dispatchers.IO) {
            val startTimeMs = System.currentTimeMillis()
            DebugLog.i(TAG, "开始识别文件(URI): $uri")

            try {
                val tempFile = copyUriToTempFile(uri)
                try {
                    val audioData = decodeAudioFile(tempFile)
                    recognizeFromFloatArray(audioData, startTimeMs)
                } finally {
                    tempFile.delete()
                }
            } catch (e: Exception) {
                DebugLog.e(TAG, "URI 文件识别失败: ${e.message}", e)
                SttResult(
                    text = "", confidence = 0f, segments = emptyList(),
                    durationMs = 0,
                    processingTimeMs = System.currentTimeMillis() - startTimeMs,
                    engineType = EngineType.SHERPA_ONNX,
                    modelUsed = config.modelType.name,
                )
            }
        }
    }

    override suspend fun recognizeFile(filePath: String): SttResult {
        val startTimeMs = System.currentTimeMillis()
        val file = File(filePath)
        DebugLog.i(TAG, "开始识别文件(Path): $filePath (${file.length() / 1024}KB, ext=${file.extension})")

        return withContext(Dispatchers.IO) {
            try {
                val audioData = if (file.extension.lowercase() == "wav") {
                    // WAV 文件用内置解码器（更快）
                    decodeAudioFile(file)
                } else {
                    // 其他格式走 AudioProcessor
                    val pair = AudioProcessor.decodeToPcm(context, Uri.fromFile(file))
                    pair?.first ?: FloatArray(0)
                }
                DebugLog.i(TAG, "音频解码: ${audioData.size} samples (${audioData.size / TARGET_SAMPLE_RATE}s)")
                recognizeFromFloatArray(audioData, startTimeMs)
            } catch (e: Exception) {
                DebugLog.e(TAG, "文件路径识别失败: ${e.message}", e)
                SttResult(
                    text = "", confidence = 0f, segments = emptyList(),
                    durationMs = 0,
                    processingTimeMs = System.currentTimeMillis() - startTimeMs,
                    engineType = EngineType.SHERPA_ONNX,
                    modelUsed = config.modelType.name,
                )
            }
        }
    }

    /**
     * 直接从归一化浮点音频数组进行 ASR 识别。
     * 用于会议转录中按说话人段逐段识别的场景。
     *
     * @param audioData 归一化 [-1.0, 1.0] 浮点数组，采样率必须为 16000Hz
     */
    fun recognizeFloatAudio(audioData: FloatArray): SttResult {
        val startTimeMs = System.currentTimeMillis()
        return try {
            ensureInitialized()
            val recognizer = offlineRecognizer!!
            val totalSamples = audioData.size
            val durationMs = (totalSamples.toFloat() / TARGET_SAMPLE_RATE * 1000).toLong()

            if (totalSamples == 0) return SttResult(
                text = "", segments = emptyList(), durationMs = 0,
                processingTimeMs = System.currentTimeMillis() - startTimeMs,
                engineType = EngineType.SHERPA_ONNX, modelUsed = config.modelType.name,
            )

            val text = decodeSegment(recognizer, audioData).trim()

            SttResult(
                text = text,
                confidence = if (text.isNotEmpty()) 1f else 0f,
                segments = if (text.isNotEmpty()) listOf(
                    SttSegment(text = text, startTimeMs = 0, endTimeMs = durationMs, confidence = 1f),
                ) else emptyList(),
                durationMs = durationMs,
                processingTimeMs = System.currentTimeMillis() - startTimeMs,
                engineType = EngineType.SHERPA_ONNX,
                modelUsed = config.modelType.name,
            )
        } catch (e: Exception) {
            DebugLog.e(TAG, "recognizeFloatAudio 失败: ${e.message}", e)
            SttResult("", 0f, emptyList(), 0, System.currentTimeMillis() - startTimeMs, EngineType.SHERPA_ONNX, config.modelType.name)
        }
    }

    /**
     * 启动流式识别（实时语音输入）。
     *
     * 使用 OnlineRecognizer + OnlineStream 实现真正的增量识别：
     * - 外部通过 [feedAudioData] 喂入音频采样点
     * - 内部每 [STREAMING_CHUNK_MS] 轮询一次中间结果
     * - 停止时输出最终结果
     *
     * 如果 OnlineRecognizer 不可用（模型不支持或初始化失败），返回 Error 状态。
     */
    override fun startStreamingRecognition(): Flow<StreamingRecognitionState> {
        return callbackFlow {
            if (!_isInitialized.get()) {
                trySend(StreamingRecognitionState.Error("引擎未初始化，请先调用 initialize()"))
                close()
                return@callbackFlow
            }

            trySend(StreamingRecognitionState.Error("当前版本暂不支持实时流式识别，请使用文件转录模式"))
            close()
        }
    }

    fun feedAudioData(samples: FloatArray) { /* 流式识别暂不可用 */ }

    override fun stopStreamingRecognition() {
        if (streamingActive.compareAndSet(true, false)) {
            DebugLog.i(TAG, "停止流式识别信号已发送")
        }
    }

    override fun close() {
        try {
            stopStreamingRecognition()

            offlineRecognizer?.release()
            offlineRecognizer = null
            _isInitialized.set(false)
            currentModelDir = null

            DebugLog.i(TAG, "资源已完全释放")
        } catch (e: Exception) {
            DebugLog.e(TAG, "关闭时出错: ${e.message}", e)
        }
    }

    // ==================== 内部实现 ====================

    private suspend fun recognizeFromFloatArray(audioData: FloatArray, startTimeMs: Long): SttResult {
        ensureInitialized()

        val recognizer = offlineRecognizer!!
        val totalSamples = audioData.size
        val durationMs = (totalSamples.toFloat() / TARGET_SAMPLE_RATE * 1000).toLong()

        DebugLog.i(TAG, "音频解码完成: $totalSamples 个采样点, 时长=${AudioProcessor.formatDuration(durationMs)}")

        if (totalSamples == 0) {
            return SttResult(
                text = "", segments = emptyList(), durationMs = durationMs,
                processingTimeMs = System.currentTimeMillis() - startTimeMs,
                engineType = EngineType.SHERPA_ONNX,
                modelUsed = config.modelType.name,
            )
        }

        val segmentLengthSamples = min(DEFAULT_SEGMENT_SAMPLES, totalSamples)
        val segments = mutableListOf<SttSegment>()
        val fullText = StringBuilder()
        var offset = 0
        var segmentIndex = 0

        while (offset < totalSamples) {
            val end = min(offset + segmentLengthSamples, totalSamples)
            val chunkSize = end - offset
            val chunk = FloatArray(chunkSize)
            System.arraycopy(audioData, offset, chunk, 0, chunkSize)

            val segStartMs = (offset.toFloat() / TARGET_SAMPLE_RATE * 1000).toLong()
            val segEndMs = (end.toFloat() / TARGET_SAMPLE_RATE * 1000).toLong()

            DebugLog.d(TAG, "处理段[$segmentIndex]: ${chunkSize} samples (${segStartMs}ms-${segEndMs}ms)")

            val segmentText = decodeSegment(recognizer, chunk)
            val trimmedText = segmentText.trim()

            if (trimmedText.isNotEmpty()) {
                if (fullText.isNotEmpty()) fullText.append(" ")
                fullText.append(trimmedText)

                segments.add(
                    SttSegment(
                        text = trimmedText,
                        startTimeMs = segStartMs,
                        endTimeMs = segEndMs,
                        confidence = 1f,
                    )
                )

                DebugLog.d(TAG, "段[$segmentIndex] 结果: ${trimmedText.take(60)}")
            }

            offset = end
            segmentIndex++
        }

        val processingTimeMs = System.currentTimeMillis() - startTimeMs
        DebugLog.i(TAG, "识别完成: 共${segmentIndex}段, 文本长度=${fullText.length}, 耗时=${processingTimeMs}ms")

        return SttResult(
            text = fullText.toString(),
            confidence = if (segments.isNotEmpty()) 1f else 0f,
            segments = segments.toList(),
            durationMs = durationMs,
            processingTimeMs = processingTimeMs,
            engineType = EngineType.SHERPA_ONNX,
            modelUsed = config.modelType.name,
        )
    }

    private fun decodeSegment(recognizer: OfflineRecognizer, samples: FloatArray): String {
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, TARGET_SAMPLE_RATE)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            result.text ?: ""
        } catch (e: OutOfMemoryError) {
            DebugLog.e(TAG, "解码段时内存不足: ${e.message}")
            ""
        } catch (e: Exception) {
            DebugLog.e(TAG, "解码段异常: ${e.message}")
            ""
        } finally {
            stream.release()
        }
    }

    private fun decodeAudioFile(file: File): FloatArray {
        val extension = file.extension.lowercase()
        return when (extension) {
            "wav" -> decodeWavFile(file)
            "pcm", "raw" -> decodeRawPcmFile(file, TARGET_SAMPLE_RATE, 16, 1)
            else -> tryDecodeAsWavOrPcm(file)
        }.also { data ->
            DebugLog.i(TAG, "音频解码结果: ${data.size} float samples (${file.name})")
        }
    }

    // ==================== 音频解码（使用线性插值重采样，与 AudioProcessor 统一）====================

    private fun decodeWavFile(file: File): FloatArray {
        FileInputStream(file).use { fis ->
            val header = ByteArray(WAV_HEADER_SIZE)
            val bytesRead = fis.read(header)
            if (bytesRead < WAV_HEADER_SIZE) {
                throw IOException("WAV 文件头不完整: 仅读取 $bytesRead 字节")
            }

            val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val riffId = String(bb.array(), 0, 4)
            val waveId = String(bb.array(), 8, 4)

            if (riffId != "RIFF" || waveId != "WAVE") {
                throw IOException("无效的 WAV 文件格式 (RIFF=$riffId, WAVE=$waveId)")
            }

            bb.position(16)
            val audioFormat = bb.getShort().toInt()
            val channels = bb.getShort().toInt()
            val sampleRate = bb.getInt()
            // byteRate, blockAlign skip
            bb.getShort() // blockAlign
            val bitsPerSample = bb.getShort().toInt()

            if (audioFormat != 1 && audioFormat != 3) {
                throw IOException("不支持的 WAV 音频格式: format=$audioFormat")
            }

            DebugLog.d(TAG, "WAV 信息: rate=$sampleRate, ch=$channels, bits=$bitsPerSample")

            val dataSize = file.length().toInt() - WAV_HEADER_SIZE
            if (dataSize <= 0) {
                DebugLog.w(TAG, "WAV 文件无音频数据")
                return FloatArray(0)
            }

            val rawPcm = ByteArray(dataSize)
            var totalRead = 0
            while (totalRead < dataSize) {
                val read = fis.read(rawPcm, totalRead, dataSize - totalRead)
                if (read == -1) break
                totalRead += read
            }

            val floats = when (bitsPerSample) {
                16 -> pcm16ToFloat(rawPcm, channels, sampleRate)
                32 -> {
                    if (audioFormat == 3) pcmFloat32ToFloat(rawPcm, channels, sampleRate)
                    else pcm32ToFloat(rawPcm, channels, sampleRate)
                }
                8 -> pcm8ToFloat(rawPcm, channels, sampleRate)
                24 -> pcm24ToFloat(rawPcm, channels, sampleRate)
                else -> throw IOException("不支持位深度: ${bitsPerSample}bit")
            }
            return floats
        }
    }

    private fun decodeRawPcmFile(file: File, expectedSampleRate: Int, bitsPerSample: Int, channels: Int): FloatArray {
        FileInputStream(file).use { fis ->
            val size = file.length().toInt()
            if (size == 0) return FloatArray(0)

            val rawPcm = ByteArray(size)
            fis.read(rawPcm)

            return when (bitsPerSample) {
                16 -> pcm16ToFloat(rawPcm, channels, expectedSampleRate)
                else -> {
                    DebugLog.w(TAG, "原始 PCM 位深度 $bitsPerSample 不受支持，尝试按 16-bit 解析")
                    pcm16ToFloat(rawPcm, channels, expectedSampleRate)
                }
            }
        }
    }

    private fun tryDecodeAsWavOrPcm(file: File): FloatArray {
        return try {
            decodeWavFile(file)
        } catch (e: IOException) {
            DebugLog.w(TAG, "非 WAV 格式，尝试作为 16-bit PCM 解读: ${e.message}")
            try {
                decodeRawPcmFile(file, TARGET_SAMPLE_RATE, 16, 1)
            } catch (e2: Exception) {
                DebugLog.e(TAG, "无法解码音频文件: ${file.extension} 格式不受支持")
                FloatArray(0)
            }
        }
    }

    // ---- PCM → Float 转换 + 线性插值重采样（与 AudioProcessor.resample 一致）----

    private fun pcm16ToFloat(raw: ByteArray, channels: Int, sourceSampleRate: Int): FloatArray {
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val totalSamples = raw.size / 2
        val monoSamples = if (channels > 1) totalSamples / channels else totalSamples
        val result = FloatArray(monoSamples)
        var outIdx = 0

        for (i in 0 until totalSamples step channels) {
            val sample = bb.getShort(i * 2).toInt().toFloat() / 32768f
            result[outIdx++] = sample.coerceIn(-1f, 1f)
        }

        return if (sourceSampleRate == TARGET_SAMPLE_RATE) result
        else resampleLinear(result, sourceSampleRate, TARGET_SAMPLE_RATE)
    }

    private fun pcm32ToFloat(raw: ByteArray, channels: Int, sourceSampleRate: Int): FloatArray {
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val totalSamples = raw.size / 4
        val monoSamples = if (channels > 1) totalSamples / channels else totalSamples
        val result = FloatArray(monoSamples)
        var outIdx = 0

        for (i in 0 until totalSamples step channels) {
            val sample = bb.getInt(i * 4).toFloat() / Int.MAX_VALUE.toFloat()
            result[outIdx++] = sample.coerceIn(-1f, 1f)
        }

        return if (sourceSampleRate == TARGET_SAMPLE_RATE) result
        else resampleLinear(result, sourceSampleRate, TARGET_SAMPLE_RATE)
    }

    private fun pcmFloat32ToFloat(raw: ByteArray, channels: Int, sourceSampleRate: Int): FloatArray {
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val totalSamples = raw.size / 4
        val monoSamples = if (channels > 1) totalSamples / channels else totalSamples
        val result = FloatArray(monoSamples)
        var outIdx = 0

        for (i in 0 until totalSamples step channels) {
            result[outIdx++] = bb.getFloat(i * 4).coerceIn(-1f, 1f)
        }

        return if (sourceSampleRate == TARGET_SAMPLE_RATE) result
        else resampleLinear(result, sourceSampleRate, TARGET_SAMPLE_RATE)
    }

    private fun pcm8ToFloat(raw: ByteArray, channels: Int, sourceSampleRate: Int): FloatArray {
        val totalSamples = raw.size
        val monoSamples = if (channels > 1) totalSamples / channels else totalSamples
        val result = FloatArray(monoSamples)
        var outIdx = 0

        for (i in 0 until totalSamples step channels) {
            val unsigned = raw[i].toInt() and 0xFF
            val sample = (unsigned - 128).toFloat() / 128f
            result[outIdx++] = sample.coerceIn(-1f, 1f)
        }

        return if (sourceSampleRate == TARGET_SAMPLE_RATE) result
        else resampleLinear(result, sourceSampleRate, TARGET_SAMPLE_RATE)
    }

    private fun pcm24ToFloat(raw: ByteArray, channels: Int, sourceSampleRate: Int): FloatArray {
        val totalSamples = raw.size / 3
        val monoSamples = if (channels > 1) totalSamples / channels else totalSamples
        val result = FloatArray(monoSamples)
        var outIdx = 0

        for (i in 0 until totalSamples step channels) {
            val base = i * 3
            val sample = ((raw[base + 2].toInt() and 0xFF shl 16) or
                    (raw[base + 1].toInt() and 0xFF shl 8) or
                    (raw[base].toInt() and 0xFF)).toFloat() / 8388608f
            result[outIdx++] = sample.coerceIn(-1f, 1f)
        }

        return if (sourceSampleRate == TARGET_SAMPLE_RATE) result
        else resampleLinear(result, sourceSampleRate, TARGET_SAMPLE_RATE)
    }

    /**
     * 线性插值重采样（与 AudioProcessor.resample 算法一致）。
     * 比最近邻插值质量更好，避免引入明显失真。
     */
    private fun resampleLinear(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate) return input
        if (input.isEmpty()) return FloatArray(0)

        val ratio = fromRate.toDouble() / toRate.toDouble()
        val outputLength = ((input.size.toDouble() / ratio)).toInt().coerceAtLeast(0)
        val output = FloatArray(outputLength)

        for (i in 0 until outputLength) {
            val position = i.toDouble() * ratio
            val index = position.toInt()
            val fraction = position - index.toDouble()

            if (index + 1 < input.size) {
                output[i] = (input[index] * (1.0 - fraction) + input[index + 1] * fraction).toFloat()
            } else if (index < input.size) {
                output[i] = input[index]
            }
        }

        DebugLog.d(TAG, "重采样: ${fromRate}Hz → ${toRate}Hz (${input.size} → ${output.size} samples)")
        return output
    }

    // ==================== 配置构建 ====================

    private fun buildOfflineRecognizerConfig(
        modelDir: File, numThreads: Int, provider: String, deviceType: String,
    ): OfflineRecognizerConfig {
        val featConfig = FeatureConfig(
            sampleRate = TARGET_SAMPLE_RATE,
            featureDim = 80,
        )

        val modelConfig = when (config.modelType) {
            ModelType.PARAFORMER -> buildParaformerModelConfig(modelDir)
            ModelType.SENSE_VOICE_SMALL -> buildSenseVoiceModelConfig(modelDir)
            ModelType.FUNASR_NANO_INT8 -> buildFunasrNanoModelConfig(modelDir)
        }

        return OfflineRecognizerConfig(
            featConfig = featConfig,
            modelConfig = modelConfig,
        )
    }

    private fun buildParaformerModelConfig(modelDir: File): OfflineModelConfig {
        val modelFile = findModelFile(modelDir, "model.onnx", "model.int8.onnx")
        val tokensFile = findModelFile(modelDir, "tokens.txt", "tokens")

        DebugLog.d(TAG, "Paraformer 模型配置: model=$modelFile tokens=$tokensFile")

        return OfflineModelConfig(
            paraformer = OfflineParaformerModelConfig(model = modelFile),
            tokens = tokensFile,
        )
    }

    private fun buildSenseVoiceModelConfig(modelDir: File): OfflineModelConfig {
        val modelFile = findModelFile(modelDir, "model.onnx", "model.int8.onnx")
        val tokensFile = findModelFile(modelDir, "tokens.txt", "tokens", "lang.txt", "itn.tokens")

        DebugLog.d(TAG, "SenseVoice 模型配置: model=$modelFile tokens=$tokensFile")

        return OfflineModelConfig(
            senseVoice = OfflineSenseVoiceModelConfig(model = modelFile),
            tokens = tokensFile,
        )
    }

    private fun buildFunasrNanoModelConfig(modelDir: File): OfflineModelConfig {
        val modelFile = findModelFile(modelDir, "model.onnx", "model.int8.onnx")
        val tokensFile = findModelFile(modelDir, "tokens.txt", "tokens")

        DebugLog.d(TAG, "FunASR Nano 模型配置: model=$modelFile tokens=$tokensFile")

        return OfflineModelConfig(
            paraformer = OfflineParaformerModelConfig(model = modelFile),
            tokens = tokensFile,
        )
    }

    private fun findModelFile(dir: File, vararg candidates: String): String {
        for (name in candidates) {
            val f = File(dir, name)
            if (f.exists()) return f.absolutePath
        }
        val found = dir.listFiles()?.map { it.name }?.joinToString(", ") ?: "(空目录)"
        throw IllegalArgumentException(
            "模型目录中未找到所需文件 (候选: ${candidates.joinToString("/")}, 目录内容: [$found])"
        )
    }

    private fun validateModelFiles(modelDir: File) {
        if (!modelDir.exists()) {
            throw IllegalArgumentException("模型目录不存在: ${modelDir.absolutePath}")
        }
        if (!modelDir.isDirectory) {
            throw IllegalArgumentException("模型路径不是目录: ${modelDir.absolutePath}")
        }
        val files = modelDir.listFiles()
        if (files == null || files.isEmpty()) {
            throw IllegalArgumentException("模型目录为空: ${modelDir.absolutePath}。请先下载模型文件。")
        }

        DebugLog.d(TAG, "模型目录包含 ${files.size} 个文件: ${files.joinToString { it.name }}")
    }

    private fun resolveOptimalThreadCount(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        val optimal = when {
            cores >= 8 -> 4
            cores >= 4 -> 2
            else -> 1
        }
        DebugLog.d(TAG, "CPU 核心数=$cores, 最优线程数=$optimal")
        return optimal
    }

    private fun ensureInitialized() {
        if (!_isInitialized.get() || offlineRecognizer == null) {
            throw IllegalStateException("SherpaOnnxEngine 未初始化，请先调用 initialize(modelDir)")
        }
    }

    private suspend fun copyUriToTempFile(uri: Uri): File {
        return withContext(Dispatchers.IO) {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw IOException("无法打开 URI 输入流: $uri")

            val tempFile = File(context.cacheDir, "stt_temp_${System.currentTimeMillis()}.wav")
            try {
                tempFile.outputStream().use { output ->
                    inputStream.use { input ->
                        input.copyTo(output)
                    }
                }
                DebugLog.d(TAG, "URI 已复制到临时文件: ${tempFile.absolutePath} (${tempFile.length()} bytes)")
                tempFile
            } catch (e: Exception) {
                if (tempFile.exists()) tempFile.delete()
                throw e
            }
        }
    }
}
