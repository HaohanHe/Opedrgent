package top.hsyscn.opedrgent.stt

import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.HomophoneReplacerConfig
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File

/**
 * Wrapper around sherpa-onnx's OnlineRecognizer for streaming ASR.
 *
 * Uses the official Kotlin API (data classes + JNI) directly.
 * The native library (libsherpa-onnx-jni.so) is loaded from the AAR.
 */
class StreamingRecognizer {

    companion object {
        private const val TAG = "StreamingRecognizer"

        @Volatile
        private var isAvailableChecked = false

        @Volatile
        private var isAvailableResult = false

        fun isAvailable(): Boolean {
            if (isAvailableChecked) return isAvailableResult
            synchronized(this) {
                if (isAvailableChecked) return isAvailableResult
                isAvailableResult = try {
                    Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizer")
                    DebugLog.i(TAG, "sherpa-onnx online/streaming API available")
                    true
                } catch (_: ClassNotFoundException) {
                    DebugLog.w(TAG, "sherpa-onnx AAR lacks online/streaming API")
                    false
                }
                isAvailableChecked = true
                return isAvailableResult
            }
        }
    }

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var _isActive = false

    val isActive: Boolean get() = _isActive

    /**
     * Create a new OnlineRecognizer with Paraformer streaming config.
     */
    fun create(
        @Suppress("UNUSED_PARAMETER") assetManager: Any?,
        modelDir: File,
        numThreads: Int = 1,
        provider: String = "cpu",
        enableHr: Boolean = true,
    ): Boolean {
        if (!isAvailable()) {
            DebugLog.e(TAG, "OnlineRecognizer API not available")
            return false
        }
        if (_isActive) {
            DebugLog.w(TAG, "Recognizer already active, releasing first")
            release()
        }

        return try {
            val encoderPath = findModelFile(modelDir, "encoder.int8.onnx", "encoder.onnx")
            val decoderPath = findModelFile(modelDir, "decoder.int8.onnx", "decoder.onnx")
            val tokensPath = findModelFile(modelDir, "tokens.txt", "tokens")

            DebugLog.i(TAG, "构建流式配置: encoder=$encoderPath, decoder=$decoderPath, tokens=$tokensPath")

            // ---- 后处理资源自动检测 ----
            // sherpa-onnx OnlineRecognizer 的 GetResult() 内置两个后处理步骤：
            //   1. ApplyInverseTextNormalization (ITN) — 需要 ruleFsts 或 ruleFars
            //   2. ApplyHomophoneReplacer (同音字替换) — 需要 hr.lexicon + hr.ruleFsts
            // 参考: csrc/online-recognizer-paraformer-impl.h GetResult()
            //       csrc/online-recognizer-impl.cc 构造函数 (line 230-277)
            val postProcessing = if (enableHr) detectPostProcessingFiles(modelDir) else PostProcessingConfig()

            val config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    paraformer = OnlineParaformerModelConfig(
                        encoder = encoderPath,
                        decoder = decoderPath,
                    ),
                    tokens = tokensPath,
                    numThreads = numThreads,
                    debug = false,
                    provider = provider,
                ),
                enableEndpoint = true,
                // ITN: 将 "一百二十三" → "123"，"百分之五十" → "50%" 等
                ruleFsts = postProcessing.ruleFsts,
                ruleFars = postProcessing.ruleFars,
                // 同音字替换: 根据上下文和词表矫正同音错字
                hr = HomophoneReplacerConfig(
                    dictDir = postProcessing.hrDictDir,
                    lexicon = postProcessing.hrLexicon,
                    ruleFsts = postProcessing.hrRuleFsts,
                ),
            )

            if (postProcessing.ruleFsts.isNotEmpty()) {
                DebugLog.i(TAG, "ITN 后处理已启用: ruleFsts=${postProcessing.ruleFsts}")
            }
            if (postProcessing.ruleFars.isNotEmpty()) {
                DebugLog.i(TAG, "ITN 后处理已启用: ruleFars=${postProcessing.ruleFars}")
            }
            if (postProcessing.hrLexicon.isNotEmpty()) {
                DebugLog.i(TAG, "同音字替换已启用: lexicon=${postProcessing.hrLexicon}, ruleFsts=${postProcessing.hrRuleFsts}")
            }

            recognizer = OnlineRecognizer(config = config)
            stream = recognizer!!.createStream()
            _isActive = true
            DebugLog.i(TAG, "OnlineRecognizer + OnlineStream created (provider=$provider, threads=$numThreads)")
            true
        } catch (e: Exception) {
            DebugLog.e(TAG, "Failed to create OnlineRecognizer: ${e.message}", e)
            release()
            false
        }
    }

    /**
     * Feed audio samples for streaming recognition (real-time input).
     */
    fun feedAudio(samples: FloatArray, sampleRate: Int = 16000) {
        val s = stream ?: return
        if (!_isActive || samples.isEmpty()) return

        try {
            s.acceptWaveform(samples, sampleRate)
        } catch (e: Exception) {
            DebugLog.e(TAG, "feedAudio failed: ${e.message}", e)
        }
    }

    /**
     * Feed entire audio and decode all at once (for file recognition).
     */
    fun recognize(samples: FloatArray, sampleRate: Int = 16000): String {
        val r = recognizer ?: return ""
        val s = stream ?: return ""
        if (samples.isEmpty()) return ""

        return try {
            s.acceptWaveform(samples, sampleRate)
            s.inputFinished()

            while (r.isReady(s)) {
                r.decode(s)
            }

            val result = r.getResult(s)
            val text = result.text
            DebugLog.d(TAG, "recognize: ${samples.size} samples -> ${text.length} chars")
            text
        } catch (e: Exception) {
            DebugLog.e(TAG, "recognize failed: ${e.message}", e)
            ""
        }
    }

    /**
     * Check if the recognizer has enough audio to produce a decode result.
     */
    fun isReady(): Boolean {
        val r = recognizer ?: return false
        val s = stream ?: return false
        return try {
            r.isReady(s)
        } catch (e: Exception) {
            DebugLog.e(TAG, "isReady failed: ${e.message}", e)
            false
        }
    }

    /**
     * Decode one step, advancing the recognizer's internal state.
     */
    fun decode() {
        val r = recognizer ?: return
        val s = stream ?: return
        try {
            r.decode(s)
        } catch (e: Exception) {
            DebugLog.e(TAG, "decode failed: ${e.message}", e)
        }
    }

    /**
     * Get the current recognition result text.
     *
     * Returns partial text that can be updated/corrected as more audio arrives.
     */
    fun getResult(): String {
        val r = recognizer ?: return ""
        val s = stream ?: return ""
        return try {
            r.getResult(s).text
        } catch (e: Exception) {
            DebugLog.e(TAG, "getResult failed: ${e.message}", e)
            ""
        }
    }

    /**
     * Check if an endpoint (pause/break) has been detected.
     */
    fun isEndpoint(): Boolean {
        val r = recognizer ?: return false
        val s = stream ?: return false
        return try {
            r.isEndpoint(s)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Reset the recognizer for a new utterance.
     */
    fun reset() {
        val r = recognizer ?: return
        val s = stream ?: return
        try {
            r.reset(s)
        } catch (e: Exception) {
            DebugLog.e(TAG, "reset failed: ${e.message}", e)
        }
    }

    /**
     * Release all resources (stream + recognizer).
     */
    fun release() {
        stream?.let {
            try { it.release() } catch (_: Exception) { }
        }
        stream = null

        recognizer?.let {
            try { it.release() } catch (_: Exception) { }
        }
        recognizer = null
        _isActive = false
        DebugLog.i(TAG, "OnlineRecognizer + OnlineStream released")
    }

    private fun findModelFile(dir: File, vararg candidates: String): String {
        for (name in candidates) {
            val f = File(dir, name)
            if (f.exists()) return f.absolutePath
        }
        val found = dir.listFiles()?.map { it.name }?.joinToString(", ") ?: "(empty)"
        throw IllegalArgumentException(
            "Model file not found (candidates: ${candidates.joinToString("/")}, dir: [$found])"
        )
    }

    /**
     * 后处理资源配置（自动从模型目录检测）。
     *
     * sherpa-onnx OnlineRecognizer 在 GetResult() 中内置两个后处理步骤：
     * - **ITN** (Inverse Text Normalization): "一百二十三" → "123"，需要 .fst/.far 文件
     * - **同音字替换**: 根据词表和 FST 规则矫正同音错字，需要 lexicon.txt + .fst 文件
     *
     * 如果模型目录中没有这些文件，对应后处理不启用（不会报错）。
     */
    private data class PostProcessingConfig(
        val ruleFsts: String = "",       // ITN 用 FST 规则文件（逗号分隔多个）
        val ruleFars: String = "",       // ITN 用 FAR 归档文件（逗号分隔多个）
        val hrDictDir: String = "",      // 同音字替换词典目录
        val hrLexicon: String = "",      // 同音字替换词表文件
        val hrRuleFsts: String = "",     // 同音字替换 FST 规则文件
    )

    /**
     * 从模型目录中自动检测后处理资源文件。
     *
     * 检测规则：
     * 1. **ITN (.fst)**: 目录下所有 *.fst 文件（排除 replace.fst，那个是同音字用的）
     * 2. **ITN (.far)**: 目录下所有 *.far 文件
     * 3. **同音字替换**: 同时存在 lexicon.txt + replace.fst（或含 "replace" 的 .fst）时启用
     */
    private fun detectPostProcessingFiles(modelDir: File): PostProcessingConfig {
        val files = modelDir.listFiles()?.toList().orEmpty()

        // --- ITN: 收集 .fst 文件（排除同音字替换专用的 replace.fst）---
        val itnFstFiles = files
            .filter { it.extension == "fst" && !it.name.contains("replace") }
            .sortedBy { it.name.lowercase() }
            .map { it.absolutePath }

        // --- ITN: 收集 .far 归档文件 ---
        val itnFarFiles = files
            .filter { it.extension == "far" }
            .sortedBy { it.name.lowercase() }
            .map { it.absolutePath }

        // --- 同音字替换: 查找 lexicon.txt + replace*.fst ---
        val lexiconFile = files.find {
            it.name.equals("lexicon.txt", ignoreCase = true)
        }
        val hrFstFile = files.find {
            it.extension == "fst" && (
                it.name.contains("replace", ignoreCase = true) ||
                it.name.contains("hr", ignoreCase = true)
            )
        }
        // 同音字词典目录：查找 dict/ 子目录
        val dictDir = files.find {
            it.isDirectory && it.name.equals("dict", ignoreCase = true)
        }

        val config = PostProcessingConfig(
            ruleFsts = itnFstFiles.joinToString(","),
            ruleFars = itnFarFiles.joinToString(","),
            hrDictDir = dictDir?.absolutePath.orEmpty(),
            hrLexicon = lexiconFile?.absolutePath.orEmpty(),
            hrRuleFsts = hrFstFile?.absolutePath.orEmpty(),
        )

        val hasAnyProcessing = config.ruleFsts.isNotEmpty() ||
                               config.ruleFars.isNotEmpty() ||
                               (config.hrLexicon.isNotEmpty() && config.hrRuleFsts.isNotEmpty())

        if (hasAnyProcessing) {
            DebugLog.i(TAG, "检测到后处理资源: ITN_FSTs=${itnFstFiles.map { File(it).name }}, " +
                         "ITN_FARs=${itnFarFiles.map { File(it).name }}, " +
                         "HR_lexicon=${lexiconFile?.name}, HR_fst=${hrFstFile?.name}, HR_dict=${dictDir?.name}")
        } else {
            DebugLog.d(TAG, "模型目录中未检测到后处理资源文件 (.fst/.far/lexicon.txt)。ITN 和同音字替换将不启用。")
            DebugLog.d(TAG, "提示: 可从 sherpa-onnx releases 下载 itn_zh_number.fst (中文数字ITN) " +
                         "放到模型目录即可启用")
        }

        return config
    }
}
