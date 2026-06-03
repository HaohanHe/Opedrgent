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
        private const val STREAMING_BUFFER_MS = 500L
        private const val STREAMING_SILENCE_THRESHOLD = 0.01f
        private const val WAV_HEADER_SIZE = 44
    }

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

            val numThreads = resolveOptimalThreadCount()
            DebugLog.i(TAG, "使用线程数=$numThreads")

            val recognizerConfig = buildRecognizerConfig(modelDir, numThreads)

            DebugLog.d(TAG, "创建 OfflineRecognizer...")
            val recognizer = OfflineRecognizer(recognizerConfig)

            offlineRecognizer = recognizer
            currentModelDir = modelDir
            _isInitialized.set(true)

            DebugLog.i(TAG, "模型初始化成功")
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
                    recognizeFileInternal(tempFile, startTimeMs)
                } finally {
                    tempFile.delete()
                }
            } catch (e: Exception) {
                DebugLog.e(TAG, "URI 文件识别失败: ${e.message}", e)
                SttResult(
                    text = "",
                    confidence = 0f,
                    segments = emptyList(),
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
        DebugLog.i(TAG, "开始识别文件(Path): $filePath")

        return withContext(Dispatchers.IO) {
            try {
                recognizeFileInternal(File(filePath), startTimeMs)
            } catch (e: Exception) {
                DebugLog.e(TAG, "文件路径识别失败: ${e.message}", e)
                SttResult(
                    text = "",
                    confidence = 0f,
                    segments = emptyList(),
                    durationMs = 0,
                    processingTimeMs = System.currentTimeMillis() - startTimeMs,
                    engineType = EngineType.SHERPA_ONNX,
                    modelUsed = config.modelType.name,
                )
            }
        }
    }

    override fun startStreamingRecognition(): Flow<StreamingRecognitionState> {
        return callbackFlow {
            if (!_isInitialized.get()) {
                trySend(StreamingRecognitionState.Error("引擎未初始化，请先调用 initialize()"))
                close()
                return@callbackFlow
            }

            val recognizer = offlineRecognizer ?: run {
                trySend(StreamingRecognitionState.Error("识别器实例不可用"))
                close()
                return@callbackFlow
            }

            streamingActive.set(true)
            trySend(StreamingRecognitionState.Listening)

            DebugLog.i(TAG, "流式识别已启动")

            val stream = recognizer.createStream()
            val partialBuffer = StringBuilder()

            try {
                while (streamingActive.get() && isActive) {
                    delay(STREAMING_BUFFER_MS)

                    if (!isActive || !streamingActive.get()) break

                    val result = recognizer.getResult(stream)
                    if (!result.text.isNullOrEmpty()) {
                        partialBuffer.clear()
                        partialBuffer.append(result.text)
                        trySend(StreamingRecognitionState.Recognizing(result.text))
                        DebugLog.d(TAG, "流式中间结果: ${result.text.take(50)}")
                    }
                }

                if (partialBuffer.isNotEmpty()) {
                    val finalText = partialBuffer.toString().trim()
                    if (finalText.isNotEmpty()) {
                        trySend(StreamingRecognitionState.FinalResult(finalText))
                        DebugLog.i(TAG, "流式最终结果: ${finalText.take(100)}")
                    }
                }

                trySend(StreamingRecognitionState.Stopped)
            } catch (e: CancellationException) {
                DebugLog.i(TAG, "流式识别被取消")
                throw e
            } catch (e: Exception) {
                DebugLog.e(TAG, "流式识别异常: ${e.message}", e)
                trySend(StreamingRecognitionState.Error("流式识别错误: ${e.message}"))
            } finally {
                stream.release()
                streamingActive.set(false)
                DebugLog.i(TAG, "流式识别资源已释放")
            }

            awaitClose {
                streamingActive.set(false)
                DebugLog.d(TAG, "流式识别 Flow 已关闭")
            }
        }
    }

    fun feedAudioData(samples: FloatArray) {
        if (!streamingActive.get()) return
        val recognizer = offlineRecognizer ?: return
        // 流式模式下通过内部缓冲区喂入数据
        // 完整实现需要维护一个 OnlineStream 或在 startStreamingRecognition 中暴露写入接口
        DebugLog.d(TAG, "feedAudioData: 接收 ${samples.size} 个采样点")
    }

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

    private suspend fun recognizeFileInternal(file: File, startTimeMs: Long): SttResult {
        ensureInitialized()

        val recognizer = offlineRecognizer!!
        val audioData = decodeAudioFile(file)
        val totalSamples = audioData.size
        val durationMs = (totalSamples.toFloat() / TARGET_SAMPLE_RATE * 1000).toLong()

        DebugLog.i(TAG, "音频解码完成: $totalSamples 个采样点, 时长=${AudioProcessor.formatDuration(durationMs)}")

        if (totalSamples == 0) {
            return SttResult(
                text = "",
                segments = emptyList(),
                durationMs = durationMs,
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
            stream.acceptWaveform(samples)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            result.text ?: ""
        } catch (e: OutOfMemoryError) {
            DebugLog.e(TAG, "解码段时内存不足: ${e.message}")
            ""
        } catch (e: Exception) {
            DebugLog.e(TAG, "解码段异常: ${e.message}", e)
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
            val byteRate = bb.getInt()
            val blockAlign = bb.getShort().toInt()
            val bitsPerSample = bb.getShort().toInt()

            if (audioFormat != 1 && audioFormat != 3) {
                throw IOException("不支持的 WAV 音频格式: format=$audioFormat (仅支持 PCM=1 或 IEEE_FLOAT=3)")
            }

            DebugLog.d(TAG, "WAV 信息: rate=$sampleRate, ch=$channels, bits=$bitsPerSample, fmt=$audioFormat")

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

            return when (bitsPerSample) {
                16 -> pcm16ToFloat(rawPcm, channels, sampleRate)
                32 -> {
                    if (audioFormat == 3) pcmFloat32ToFloat(rawPcm, channels, sampleRate)
                    else pcm32ToFloat(rawPcm, channels, sampleRate)
                }
                8 -> pcm8ToFloat(rawPcm, channels, sampleRate)
                24 -> pcm24ToFloat(rawPcm, channels, sampleRate)
                else -> throw IOException("不支持位深度: ${bitsPerSample}bit")
            }
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
        else resampleFloatArray(result, sourceSampleRate, TARGET_SAMPLE_RATE)
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
        else resampleFloatArray(result, sourceSampleRate, TARGET_SAMPLE_RATE)
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
        else resampleFloatArray(result, sourceSampleRate, TARGET_SAMPLE_RATE)
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
        else resampleFloatArray(result, sourceSampleRate, TARGET_SAMPLE_RATE)
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
        else resampleFloatArray(result, sourceSampleRate, TARGET_SAMPLE_RATE)
    }

    private fun resampleFloatArray(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate) return input

        val ratio = fromRate.toDouble() / toRate.toDouble()
        val outputLength = (input.size / ratio).roundToInt().coerceAtLeast(1)
        val result = FloatArray(outputLength)

        for (i in 0 until outputLength) {
            val srcIdx = (i * ratio).toInt().coerceIn(0, input.size - 1)
            result[i] = input[srcIdx]
        }

        DebugLog.d(TAG, "重采样: ${fromRate}Hz → ${toRate}Hz (${input.size} → ${result.size} samples)")
        return result
    }

    private fun buildRecognizerConfig(modelDir: File, numThreads: Int): OfflineRecognizerConfig {
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
            numThreads = numThreads,
            debug = DebugLog.isEnabled(),
            provider = "cpu",
            deviceType = "cpu",
        )
    }

    private fun buildParaformerModelConfig(modelDir: File): OfflineModelConfig {
        val modelFile = findModelFile(modelDir, "model.onnx", "model.int8.onnx")
        val tokensFile = findModelFile(modelDir, "tokens.txt", "tokens")

        DebugLog.d(TAG, "Paraformer 模型配置: model=$modelFile tokens=$tokensFile")

        return OfflineModelConfig(
            paraformer = OfflineParaformerModelConfig(model = modelFile),
            tokens = tokensFile,
            numThreads = 0,
            debug = DebugLog.isEnabled(),
            provider = "cpu",
            deviceType = "cpu",
        )
    }

    private fun buildSenseVoiceModelConfig(modelDir: File): OfflineModelConfig {
        val modelFile = findModelFile(modelDir, "model.onnx", "model.int8.onnx")
        val tokensFile = findModelFile(modelDir, "tokens.txt", "tokens", "lang.txt", "itn.tokens")

        DebugLog.d(TAG, "SenseVoice 模型配置: model=$modelFile tokens=$tokensFile")

        return OfflineModelConfig(
            senseVoice = OfflineSenseVoiceModelConfig(model = modelFile),
            tokens = tokensFile,
            useItn = true,
            numThreads = 0,
            debug = DebugLog.isEnabled(),
            provider = "cpu",
            deviceType = "cpu",
        )
    }

    private fun buildFunasrNanoModelConfig(modelDir: File): OfflineModelConfig {
        val modelFile = findModelFile(modelDir, "model.onnx", "model.int8.onnx")
        val tokensFile = findModelFile(modelDir, "tokens.txt", "tokens")

        DebugLog.d(TAG, "FunASR Nano 模型配置: model=$modelFile tokens=$tokensFile")

        return OfflineModelConfig(
            paraformer = OfflineParaformerModelConfig(model = modelFile),
            tokens = tokensFile,
            numThreads = 0,
            debug = DebugLog.isEnabled(),
            provider = "cpu",
            deviceType = "cpu",
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
