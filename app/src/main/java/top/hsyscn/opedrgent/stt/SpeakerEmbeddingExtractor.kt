package top.hsyscn.opedrgent.stt

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 说话人嵌入向量提取器 — 基于 Sherpa-ONNX 的 OfflineSpeakerEmbeddingExtractor。
 *
 * 使用 3D-Speaker 的 ERes2Net 模型从音频中提取高维说话人嵌入向量 (192 维)，
 * 用于声纹注册和说话人识别。
 *
 * ## 实现策略
 * 由于当前 sherpa-onnx AAR 为 stub（仅含 ASR 相关类），
 * 本类通过 **反射** 动态加载 speaker embedding API：
 * - 运行时检测 OfflineSpeakerEmbeddingExtractor 等类是否存在
 * - 存在则使用 Sherpa-ONNX 提取真实声纹嵌入
 * - 不存在则优雅降级（isAvailable = false，由 VoiceprintManager 使用统计特征）
 *
 * ## 模型依赖
 * 需要 3D-Speaker 声纹模型文件 (与 SpeakerDiarizer 共享):
 * - `3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx`
 * - `tokens.txt`
 *
 * 下载地址:
 * https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/
 */
class SpeakerEmbeddingExtractor(private val context: Context) {

    companion object {
        private const val TAG = "SpeakerEmbeddingExtractor"
        private const val SAMPLE_RATE = 16000

        /** 声纹嵌入向量维度 (ERes2Net 模型输出 192 维) */
        const val EMBEDDING_DIM = 192

        /** 最少需要的音频时长（秒），低于此值嵌入质量不佳 */
        const val MIN_AUDIO_DURATION_SEC = 1.5f

        /** 推荐的音频时长（秒），高于此值嵌入质量稳定 */
        const val RECOMMENDED_AUDIO_DURATION_SEC = 3.0f
    }

    /** 反射持有的 Extractor 实例 */
    private var extractorInstance: Any? = null
    private var _isAvailable = AtomicBoolean(false)
    private var hasCheckedApi = false

    /** 是否可用（API 存在 + 模型已加载） */
    val isAvailable: Boolean get() = _isAvailable.get()

    /**
     * 检测运行时是否具备 speaker embedding 提取 API。
     */
    fun checkApiAvailability(): Boolean {
        if (hasCheckedApi) return _isAvailable.get()
        hasCheckedApi = true

        return try {
            Class.forName("com.k2fsa.sherpa.onnx.OfflineSpeakerEmbeddingExtractor")
            Class.forName("com.k2fsa.sherpa.onnx.OfflineSpeakerEmbeddingExtractorConfig")
            Class.forName("com.k2fsa.sherpa.onnx.OfflineSpeakerEmbeddingStream")
            DebugLog.i(TAG, "sherpa-onnx speaker embedding API 可用")
            true
        } catch (_: ClassNotFoundException) {
            DebugLog.w(TAG, "sherpa-onnx AAR 不包含 speaker embedding API (stub/版本过低)")
            false
        }
    }

    /**
     * 初始化提取器。
     *
     * @param modelDir 声纹模型目录（包含模型 .onnx 文件和 tokens.txt）
     * @return 是否初始化成功
     */
    fun initialize(modelDir: File): Boolean {
        if (_isAvailable.get()) return true

        if (!checkApiAvailability()) {
            return false
        }

        return try {
            val modelFile = findModelFile(modelDir)
                ?: run {
                    DebugLog.w(TAG, "声纹模型文件不存在: ${modelDir.absolutePath}")
                    return false
                }
            val tokensFile = findTokensFile(modelDir)
                ?: run {
                    DebugLog.w(TAG, "tokens 文件不存在: ${modelDir.absolutePath}")
                    return false
                }

            extractorInstance = createExtractorViaReflection(modelFile, tokensFile)
            _isAvailable.set(extractorInstance != null)

            if (_isAvailable.get()) {
                DebugLog.i(TAG, "说话人嵌入提取器初始化成功 (model=$modelFile)")
            } else {
                DebugLog.e(TAG, "反射创建 Extractor 失败")
            }
            _isAvailable.get()
        } catch (e: Exception) {
            DebugLog.e(TAG, "初始化失败: ${e.message}", e)
            _isAvailable.set(false)
            false
        }
    }

    /**
     * 从音频文件中提取说话人嵌入向量。
     *
     * @param audioFile WAV 音频文件 (16kHz, mono, 16-bit PCM)
     * @return 嵌入向量 (192 维)，提取失败返回 null
     */
    suspend fun extractFromFile(audioFile: File): FloatArray? = withContext(Dispatchers.IO) {
        if (!_isAvailable.get() || extractorInstance == null) {
            return@withContext null
        }

        try {
            val samples = decodeWavToFloat(audioFile)
            if (samples.isEmpty()) {
                DebugLog.w(TAG, "音频解码为空: ${audioFile.name}")
                return@withContext null
            }

            val durationSec = samples.size.toFloat() / SAMPLE_RATE
            if (durationSec < MIN_AUDIO_DURATION_SEC) {
                DebugLog.w(TAG, "音频时长不足: ${durationSec}s < ${MIN_AUDIO_DURATION_SEC}s")
                return@withContext null
            }

            extractEmbedding(samples)
        } catch (e: Exception) {
            DebugLog.e(TAG, "从文件提取嵌入失败: ${e.message}", e)
            null
        }
    }

    /**
     * 从 PCM 浮点数组中提取说话人嵌入向量。
     *
     * @param samples 归一化浮点音频 [-1.0, 1.0]，16kHz mono
     * @return 嵌入向量 (192 维)，提取失败返回 null
     */
    suspend fun extractFromSamples(samples: FloatArray): FloatArray? = withContext(Dispatchers.Default) {
        if (!_isAvailable.get() || extractorInstance == null) {
            return@withContext null
        }

        if (samples.isEmpty()) return@withContext null

        val durationSec = samples.size.toFloat() / SAMPLE_RATE
        if (durationSec < MIN_AUDIO_DURATION_SEC) {
            DebugLog.w(TAG, "音频时长不足: ${durationSec}s")
            return@withContext null
        }

        try {
            extractEmbedding(samples)
        } catch (e: Exception) {
            DebugLog.e(TAG, "提取嵌入失败: ${e.message}", e)
            null
        }
    }

    /** 释放资源 */
    fun release() {
        try {
            extractorInstance?.let { inst ->
                try {
                    inst.javaClass.getMethod("release").invoke(inst)
                } catch (_: Exception) {}
            }
            extractorInstance = null
        } catch (_: Exception) {}
        _isAvailable.set(false)
        hasCheckedApi = false
        DebugLog.i(TAG, "资源已释放")
    }

    // ==================== 内部实现 ====================

    /**
     * 通过反射执行嵌入提取。
     *
     * sherpa-onnx API:
     *   extractor = new OfflineSpeakerEmbeddingExtractor(assets, config)
     *   stream = extractor.createStream()
     *   stream.acceptWaveform(samples, sampleRate)
     *   extractor.isReady(stream)  // 需要多次 feed 后才 ready
     *   extractor.inputFinished(stream)
     *   result = extractor.compute(stream)
     *   embedding = result.getEmbedding()
     */
    private fun extractEmbedding(samples: FloatArray): FloatArray? {
        val inst = extractorInstance ?: return null

        try {
            // 创建 stream
            val createStreamMethod = inst.javaClass.getMethod("createStream")
            val stream = createStreamMethod.invoke(inst) ?: return null

            // 喂入音频
            val acceptMethod = stream.javaClass.getMethod(
                "acceptWaveform",
                FloatArray::class.java,
                Int::class.javaPrimitiveType,
            )
            acceptMethod.invoke(stream, samples, SAMPLE_RATE)

            // 标记输入完成
            val inputFinishedMethod = stream.javaClass.getMethod("inputFinished")
            inputFinishedMethod.invoke(stream)

            // 检查是否就绪
            val isReadyMethod = inst.javaClass.getMethod(
                "isReady",
                stream.javaClass,
            )
            val ready = isReadyMethod.invoke(inst, stream) as? Boolean ?: false
            if (!ready) {
                DebugLog.w(TAG, "extractor.isReady() = false")
                // 尝试直接 compute
            }

            // 计算嵌入
            val computeMethod = inst.javaClass.getMethod(
                "compute",
                stream.javaClass,
            )
            val result = computeMethod.invoke(inst, stream) ?: return null

            // 获取嵌入向量
            val getEmbeddingMethod = result.javaClass.getMethod("getEmbedding")
            val embedding = getEmbeddingMethod.invoke(result) as? FloatArray

            // 释放 stream
            try {
                val releaseMethod = stream.javaClass.getMethod("release")
                releaseMethod.invoke(stream)
            } catch (_: Exception) {}

            if (embedding != null && embedding.size == EMBEDDING_DIM) {
                DebugLog.i(TAG, "嵌入提取成功: dim=${embedding.size}, norm=${String.format("%.4f", embeddingNorm(embedding))}")
                return embedding
            } else {
                DebugLog.w(TAG, "嵌入维度异常: ${embedding?.size ?: "null"} (expected $EMBEDDING_DIM)")
                return null
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "反射提取嵌入失败: ${e.message}", e)
            return null
        }
    }

    /**
     * 反射创建 OfflineSpeakerEmbeddingExtractor。
     */
    private fun createExtractorViaReflection(modelPath: String, tokensPath: String): Any? {
        return try {
            // OfflineSpeakerEmbeddingExtractorConfig(model, tokens, numThreads, provider, debug)
            val configCls = Class.forName("com.k2fsa.sherpa.onnx.OfflineSpeakerEmbeddingExtractorConfig")
            val configCtor = configCls.getConstructor(
                String::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,
                String::class.java,
                Boolean::class.javaPrimitiveType,
            )
            val numThreads = resolveOptimalThreads()
            val config = configCtor.newInstance(
                modelPath,
                tokensPath,
                numThreads,
                "cpu",
                false,
            )

            // OfflineSpeakerEmbeddingExtractor(assetManager, config)
            val extractorCls = Class.forName("com.k2fsa.sherpa.onnx.OfflineSpeakerEmbeddingExtractor")
            val extractorCtor = extractorCls.getConstructor(
                android.content.res.AssetManager::class.java,
                configCls,
            )
            // 传 null for assetManager (models are on disk, not in assets)
            extractorCtor.newInstance(null, config)
        } catch (e: Exception) {
            DebugLog.e(TAG, "反射创建 Extractor 失败: ${e.message}", e)
            null
        }
    }

    private fun findModelFile(dir: File): String? {
        val candidates = listOf(
            SpeakerDiarizer.SPEAKER_MODEL_FILE,
            "speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx",
            "nnetEres2net.onnx",
        )
        for (name in candidates) {
            val f = File(dir, name)
            if (f.exists()) return f.absolutePath
        }
        // 列出目录内容帮助诊断
        val files = dir.listFiles()?.map { it.name }?.joinToString(", ") ?: "(空目录)"
        DebugLog.w(TAG, "声纹模型文件未找到 (候选: ${candidates.joinToString("/")}, 目录: $files)")
        return null
    }

    private fun findTokensFile(dir: File): String? {
        val candidates = listOf("tokens.txt", "tokens")
        for (name in candidates) {
            val f = File(dir, name)
            if (f.exists()) return f.absolutePath
        }
        return null
    }

    /**
     * 解码 WAV 文件为归一化浮点数组 (16-bit PCM → [-1.0, 1.0])。
     */
    private fun decodeWavToFloat(file: File): FloatArray {
        return try {
            java.io.FileInputStream(file).use { fis ->
                val header = ByteArray(44)
                if (fis.read(header) < 44) return@use FloatArray(0)

                val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                bb.position(22)
                val channels = bb.short.toInt()
                bb.position(24)
                val sampleRate = bb.int
                bb.position(34)
                val bitsPerSample = bb.short.toInt()

                val dataSize = file.length().toInt() - 44
                if (dataSize <= 0) return@use FloatArray(0)

                val rawPcm = ByteArray(dataSize)
                var totalRead = 0
                while (totalRead < dataSize) {
                    val read = fis.read(rawPcm, totalRead, dataSize - totalRead)
                    if (read == -1) break
                    totalRead += read
                }

                val totalSamples = rawPcm.size / 2
                val monoSamples = if (channels > 1) totalSamples / channels else totalSamples
                val result = FloatArray(monoSamples)
                val pcmBuffer = ByteBuffer.wrap(rawPcm).order(ByteOrder.LITTLE_ENDIAN)

                for (i in 0 until monoSamples) {
                    result[i] = pcmBuffer.short.toFloat() / 32768f
                }

                if (sampleRate != SAMPLE_RATE) resampleLinear(result, sampleRate, SAMPLE_RATE) else result
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "WAV 解码失败: ${e.message}")
            FloatArray(0)
        }
    }

    private fun resampleLinear(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate || input.isEmpty()) return input
        val ratio = fromRate.toDouble() / toRate.toDouble()
        val outLen = (input.size.toDouble() / ratio).toInt().coerceAtLeast(0)
        val output = FloatArray(outLen)
        for (i in 0 until outLen) {
            val pos = i.toDouble() * ratio
            val idx = pos.toInt()
            val frac = pos - idx
            output[i] = if (idx + 1 < input.size) {
                (input[idx] * (1.0 - frac) + input[idx + 1] * frac).toFloat()
            } else if (idx < input.size) input[idx] else 0f
        }
        return output
    }

    private fun resolveOptimalThreads(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return when {
            cores >= 8 -> 4
            cores >= 4 -> 2
            else -> 1
        }
    }

    private fun embeddingNorm(embedding: FloatArray): Float {
        var sum = 0f
        for (v in embedding) sum += v * v
        return kotlin.math.sqrt(sum.toDouble()).toFloat()
    }
}
