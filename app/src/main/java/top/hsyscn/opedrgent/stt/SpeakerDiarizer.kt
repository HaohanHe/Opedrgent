package top.hsyscn.opedrgent.stt

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File

/**
 * 说话人分离器 — 基于 sherpa-onnx 的本地声纹识别。
 *
 * 使用阿里 3D-Speaker 的 ERes2Net 模型提取说话人嵌入向量，
 * 结合聚类算法自动区分不同说话人，实现完全离线的说话人分离。
 *
 * ## 工作流程
 * ```
 * 音频输入 → VAD(语音活动检测) → 分段 → 提取声纹嵌入 → 聚类 → 输出说话人标签
 * ```
 *
 * ## 实现策略
 * 由于当前 sherpa-onnx AAR 为 stub（仅含 ASR 相关类），
 * 本类通过 **反射** 动态加载 diarization API：
 * - 运行时检测 `SherpaOnnxDiarizer` 等类是否存在
 * - 存在则使用完整 sherpa-onnx diarization 管线
 * - 不存在则优雅降级（initialize() 返回 false，回退到启发式）
 *
 * 升级到完整版 sherpa-onnx AAR 后无需改代码，自动启用。
 *
 * ## 模型依赖
 * 需要从 sherpa-onnx releases 下载模型文件到 assets/speaker/ 目录:
 * - `silero_vad.onnx` (VAD 检测语音片段边界)
 * - `3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx` (声纹提取)
 * - `tokens.txt` (声纹模型的 token 表)
 *
 * 下载地址:
 * https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/
 */
class SpeakerDiarizer(private val context: Context) {

    companion object {
        private const val TAG = "SpeakerDiarizer"

        /** 默认采样率 */
        const val SAMPLE_RATE = 16000

        /** assets 中存放声纹模型的子目录 */
        const val MODEL_ASSET_DIR = "speaker"

        /** VAD 模型文件名 */
        const val VAD_MODEL_FILE = "silero_vad.onnx"

        /** 声纹提取模型文件名 */
        const val SPEAKER_MODEL_FILE = "3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"

        /** tokens 文件名 */
        const val TOKENS_FILE = "tokens.txt"

        /** 反射加载的 diarization 类缓存 */
        private var diarizerClassRef: Class<*>? = null
        private var hasCheckedDiarizationApi = false
    }

    /** 通过反射持有的 Diarizer 实例 */
    private var diarizerInstance: Any? = null
    private var _isInitialized = false

    /** 是否已初始化 */
    val isInitialized: Boolean get() = _isInitialized && diarizerInstance != null

    /**
     * 检测运行时是否具备完整的 diarization API。
     *
     * 通过 Class.forName 检测 sherpa-onnx AAR 是否包含说话人分离相关类。
     * 当前 stub AAR 不包含这些类，升级后自动可用。
     */
    fun isDiarizationApiAvailable(): Boolean {
        if (hasCheckedDiarizationApi) return diarizerClassRef != null
        hasCheckedDiarizationApi = true

        return try {
            // 核心类: SherpaOnnxDiarizer
            diarizerClassRef = Class.forName("com.k2fsa.sherpa.onnx.SherpaOnnxDiarizer")
            // 验证配套类也存在
            listOf(
                "com.k2fsa.sherpa.onnx.SherpaOnnxDiarizationConfig",
                "com.k2fsa.sherpa.onnx.VadConfig",
                "com.k2fsa.sherpa.onnx.OfflineSpeakerEmbeddingExtractorConfig",
                "com.k2fsa.sherpa.onnx.ClusteringConfig",
            ).forEach { cls ->
                Class.forName(cls)
            }
            DebugLog.i(TAG, "sherpa-onnx diarization API 可用")
            true
        } catch (_: ClassNotFoundException) {
            DebugLog.w(TAG, "sherpa-onnx AAR 不包含 diarization API (stub/版本过低)，说话人分离将使用启发式回退")
            diarizerClassRef = null
            false
        }
    }

    /**
     * 初始化说话人分离器。
     *
     * 前置条件:
     * 1. 完整版 sherpa-onnx AAR（包含 diarization 类）
     * 2. assets/speaker/ 下有模型文件
     *
     * @return 是否初始化成功
     */
    fun initialize(): Boolean {
        if (_isInitialized) return true

        // 前置检查: API 可用性
        if (!isDiarizationApiAvailable()) {
            return false
        }

        return try {
            // 1. 将模型从 assets 复制到内部存储
            val modelDir = ensureModelFiles()
                ?: run {
                    DebugLog.w(TAG, "声纹模型文件不完整")
                    return false
                }

            // 2. 通过反射构建完整的 Diarization 管线
            diarizerInstance = createDiarizerViaReflection(context, modelDir)
            _isInitialized = diarizerInstance != null

            if (_isInitialized) {
                DebugLog.i(TAG, "说话人分离器初始化成功")
            } else {
                DebugLog.e(TAG, "反射创建 Diarizer 失败")
            }
            _isInitialized
        } catch (e: Exception) {
            DebugLog.e(TAG, "初始化失败: ${e.message}", e)
            _isInitialized = false
            false
        }
    }

    /**
     * 对音频执行说话人分离。
     *
     * @param samples 归一化浮点音频数组，16kHz 单声道，范围 [-1.0, 1.0]
     * @param sampleRate 采样率（默认 16000）
     * @return 分离结果
     */
    suspend fun diarize(
        samples: FloatArray,
        sampleRate: Int = SAMPLE_RATE,
    ): DiarizationResult = withContext(Dispatchers.Default) {
        if (!_isInitialized || diarizerInstance == null) {
            return@withContext DiarizationResult(emptyList(), error = "未初始化或API不可用")
        }
        if (samples.isEmpty()) {
            return@withContext DiarizationResult(emptyList(), error = "空音频数据")
        }

        try {
            // 反射调用 diarizer.process(samples, sampleRate)
            val resultObj = invokeDiarize(diarizerInstance!!, samples, sampleRate)
                ?: return@withContext DiarizationResult(emptyList(), error = "diarize 返回 null")

            parseDiarizationResult(resultObj)
        } catch (e: Exception) {
            DebugLog.e(TAG, "说话人分离异常: ${e.message}", e)
            DiarizationResult(emptyList(), error = e.message)
        }
    }

    /**
     * 对音频文件执行说话人分离。
     */
    suspend fun diarizeFile(filePath: String): DiarizationResult =
        withContext(Dispatchers.IO) {
            try {
                val file = File(filePath)
                if (!file.exists()) {
                    return@withContext DiarizationResult(emptyList(), error = "文件不存在: $filePath")
                }
                val samples = decodeWavToFloat(file)
                if (samples.isEmpty()) {
                    return@withContext DiarizationResult(emptyList(), error = "音频解码为空")
                }
                diarize(samples)
            } catch (e: Exception) {
                DebugLog.e(TAG, "文件分离失败: ${e.message}", e)
                DiarizationResult(emptyList(), error = e.message)
            }
        }

    /**
     * 将分离结果转换为 AsrPostProcessor.SpeakerTurn 列表。
     */
    fun toSpeakerTurns(result: DiarizationResult): List<AsrPostProcessor.SpeakerTurn> {
        if (result.segments.isEmpty()) return emptyList()
        val totalDuration = result.segments.maxOfOrNull { it.end } ?: 1f
        return result.segments.map { seg ->
            AsrPostProcessor.SpeakerTurn(
                speakerId = seg.speaker,
                startTime = if (totalDuration > 0) seg.start / totalDuration else 0f,
                endTime = if (totalDuration > 0) seg.end / totalDuration else 1f,
            )
        }
    }

    /** 释放资源 */
    fun release() {
        try {
            diarizerInstance?.let { inst ->
                // 尝试调用 release()
                try {
                    inst.javaClass.getMethod("release").invoke(inst)
                } catch (_: Exception) {}
            }
            diarizerInstance = null
        } catch (_: Exception) {}
        _isInitialized = false
        DebugLog.i(TAG, "资源已释放")
    }

    // ==================== 反射: 创建 Diarizer ====================

    /**
     * 通过反射创建 SherpaOnnxDiarizer 实例。
     *
     * 构建链:
     * SileroVadModelConfig → VadConfig
     * OfflineSpeakerEmbeddingExtractorConfig
     * ClusteringConfig
     * → SherpaOnnxDiarizationConfig(vad, embedding, clustering)
     * → new SherpaOnnxDiarizer(assets, config)
     */
    private fun createDiarizerViaReflection(ctx: Context, modelDir: File): Any? {
        val clz = diarizerClassRef ?: return null

        // --- Vad 配置 ---
        val sileroVadCls = Class.forName("com.k2fsa.sherpa.onnx.SileroVadModelConfig")
        val sileroVadCtor = sileroVadCls.getConstructor(String::class.java)
        val sileroVad = sileroVadCtor.newInstance(File(modelDir, VAD_MODEL_FILE).absolutePath)

        val vadThresholdsCls = Class.forName("com.k2fsa.sherpa.onnx.VadThresholds")
        val vadThresholdsCtor = vadThresholdsCls.getConstructor(
            Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
        )
        val thresholds = vadThresholdsCtor.newInstance(0.5f, 250f, 300f)

        val vadCfgCls = Class.forName("com.k2fsa.sherpa.onnx.VadConfig")
        val vadCfgCtor = vadCfgCls.getConstructor(
            sileroVadCls, vadThresholdsCls, Int::class.javaPrimitiveType, Float::class.javaPrimitiveType,
        )
        val vadConfig = vadCfgCtor.newInstance(sileroVad, thresholds, 512, 60f)

        // --- Embedding 提取配置 ---
        val embedCfgCls = Class.forName("com.k2fsa.sherpa.onnx.OfflineSpeakerEmbeddingExtractorConfig")
        val embedCfgCtor = embedCfgCls.getConstructor(
            String::class.java, String::class.java,
            Int::class.javaPrimitiveType, String::class.java, Boolean::class.javaPrimitiveType,
        )
        val embedConfig = embedCfgCtor.newInstance(
            File(modelDir, SPEAKER_MODEL_FILE).absolutePath,
            File(modelDir, TOKENS_FILE).absolutePath,
            resolveOptimalThreads(),
            "cpu",
            false,
        )

        // --- 聚类配置 ---
        val clusterCfgCls = Class.forName("com.k2fsa.sherpa.onnx.ClusteringConfig")
        val clusterCfgCtor = clusterCfgCls.getConstructor(
            Int::class.javaPrimitiveType, Float::class.javaPrimitiveType,
        )
        val clusterConfig = clusterCfgCtor.newInstance(0, 0.6f)

        // --- Diarization 总配置 ---
        val diaCfgCls = Class.forName("com.k2fsa.sherpa.onnx.SherpaOnnxDiarizationConfig")
        val diaCfgCtor = diaCfgCls.getConstructor(vadCfgCls, embedCfgCls, clusterCfgCls)
        val diaConfig = diaCfgCtor.newInstance(vadConfig, embedConfig, clusterConfig)

        // --- 创建 Diarizer 实例 ---
        val ctor = clz.getConstructor(
            android.content.res.AssetManager::class.java, diaCfgCls,
        )
        return ctor.newInstance(ctx.assets, diaConfig)
    }

    // ==================== 反射: 调用 diarize + 解析结果 ====================

    /**
     * 反射调用 diarizer.process(float[], int) 并返回结果对象。
     */
    private fun invokeDiarize(instance: Any, samples: FloatArray, sampleRate: Int): Any? {
        return try {
            val method = instance.javaClass.getMethod("process", FloatArray::class.java, Int::class.javaPrimitiveType)
            method.invoke(instance, samples, sampleRate)
        } catch (e: Exception) {
            DebugLog.e(TAG, "反射调用 process 失败: ${e.message}", e)
            null
        }
    }

    /**
     * 解析 diarize 返回的结果对象为统一的 [DiarizationResult]。
     *
     * sherpa-onnx 的 DiarizationResult 结构:
     * - isValid: Boolean
     * - numSegments: Int
     * - getSegment(index): Segment { speaker: Int, start: Float, end: Float }
     */
    private fun parseDiarizationResult(resultObj: Any): DiarizationResult {
        return try {
            val isValid = resultObj.javaClass.getMethod("isValid").invoke(resultObj) as? Boolean ?: false
            if (!isValid) {
                return DiarizationResult(emptyList())
            }

            val numSegments = resultObj.javaClass.getMethod("getNumSegments").invoke(resultObj) as? Int ?: 0
            val segments = mutableListOf<DiarizedSegment>()

            for (i in 0 until numSegments) {
                val segment = resultObj.javaClass.getMethod("getSegment", Int::class.javaPrimitiveType).invoke(resultObj, i)
                    ?: continue
                val segCls = segment.javaClass

                val speakerIdx = segCls.getMethod("getSpeaker").invoke(segment) as? Int ?: 0
                val start = segCls.getMethod("getStart").invoke(segment) as? Float ?: 0f
                val end = segCls.getMethod("getEnd").invoke(segment) as? Float ?: 0f

                segments.add(DiarizedSegment(
                    speaker = "speaker_$speakerIdx",
                    start = start,
                    end = end,
                ))
            }

            DiarizationResult(segments)
        } catch (e: Exception) {
            DebugLog.e(TAG, "解析 diarization 结果失败: ${e.message}", e)
            DiarizationResult(emptyList(), error = e.message)
        }
    }

    // ==================== 内部工具方法 ====================

    /**
     * 确保 assets 中的模型文件复制到了可访问的文件系统路径。
     */
    private fun ensureModelFiles(): File? {
        val targetDir = File(context.filesDir, MODEL_ASSET_DIR)
        if (!targetDir.exists()) targetDir.mkdirs()

        val requiredFiles = listOf(VAD_MODEL_FILE, SPEAKER_MODEL_FILE, TOKENS_FILE)
        val missingFiles = mutableListOf<String>()

        for (fileName in requiredFiles) {
            val targetFile = File(targetDir, fileName)
            if (!targetFile.exists()) {
                try {
                    context.assets.open("$MODEL_ASSET_DIR/$fileName").use { input ->
                        targetFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    DebugLog.d(TAG, "已复制模型: $fileName (${targetFile.length() / 1024}KB)")
                } catch (_: Exception) {
                    missingFiles.add(fileName)
                }
            }
        }

        if (missingFiles.isNotEmpty()) {
            DebugLog.w(TAG, "以下声纹模型文件缺失: ${missingFiles.joinToString(", ")}")
            DebugLog.i(TAG, "请从 https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/ 下载")
            DebugLog.i(TAG, "并将文件放入 app/src/main/assets/$MODEL_ASSET_DIR/ 目录")
            return null
        }
        return targetDir
    }

    /** 解码 WAV 文件为归一化浮点数组 (16-bit PCM) */
    private fun decodeWavToFloat(file: File): FloatArray {
        return try {
            java.io.FileInputStream(file).use { fis ->
                val header = ByteArray(44)
                if (fis.read(header) < 44) return@use FloatArray(0)

                val bb = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                bb.position(22)
                val channels = bb.short.toInt()
                bb.position(24)
                val sampleRate = bb.int
                bb.position(34)
                val bitsPerSample = bb.short.toInt()

                val dataSize = file.length().toInt() - 44
                if (dataSize <= 0) return@use FloatArray(0)

                val rawPcm = ByteArray(dataSize)
                fis.read(rawPcm)

                val totalSamples = rawPcm.size / 2
                val monoSamples = if (channels > 1) totalSamples / channels else totalSamples
                val result = FloatArray(monoSamples)
                val pcmBuffer = java.nio.ByteBuffer.wrap(rawPcm).order(java.nio.ByteOrder.LITTLE_ENDIAN)

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

    // ==================== 数据类 ====================

    data class DiarizationResult(
        val segments: List<DiarizedSegment>,
        val error: String? = null,
    ) {
        val numSpeakers: Int get() = segments.map { it.speaker }.distinct().size
        val duration: Float get() = segments.maxOfOrNull { it.end } ?: 0f
    }

    data class DiarizedSegment(
        val speaker: String,
        val start: Float,
        val end: Float,
        val text: String = "",
    )
}
