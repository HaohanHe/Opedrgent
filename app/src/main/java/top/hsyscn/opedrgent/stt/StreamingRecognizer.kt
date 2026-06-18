package top.hsyscn.opedrgent.stt

import android.content.res.AssetManager
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File

/**
 * Wrapper around sherpa-onnx's OnlineRecognizer for streaming ASR.
 *
 * The current stub AAR (v1.13.2) does not include OnlineRecognizer classes.
 * This wrapper uses **reflection** to interact with the real OnlineRecognizer
 * at runtime, following the same pattern as [SpeakerDiarizer].
 *
 * When upgrading to a sherpa-onnx AAR that includes the online/streaming API,
 * this wrapper can be replaced with direct API calls.
 *
 * ## Usage
 * ```
 * val recognizer = StreamingRecognizer()
 * if (recognizer.isAvailable()) {
 *     recognizer.create(modelDir, numThreads, provider)
 *     recognizer.feedAudio(samples, sampleRate)
 *     while (recognizer.isReady()) recognizer.decode()
 *     val text = recognizer.getResult()
 *     recognizer.release()
 * }
 * ```
 */
class StreamingRecognizer {

    companion object {
        private const val TAG = "StreamingRecognizer"

        private const val ONLINE_RECOGNIZER_CLASS =
            "com.k2fsa.sherpa.onnx.OnlineRecognizer"
        private const val ONLINE_RECOGNIZER_CONFIG_CLASS =
            "com.k2fsa.sherpa.onnx.OnlineRecognizerConfig"
        private const val ONLINE_FEATURE_CONFIG_CLASS =
            "com.k2fsa.sherpa.onnx.OnlineFeatureConfig"
        private const val ONLINE_MODEL_CONFIG_CLASS =
            "com.k2fsa.sherpa.onnx.OnlineModelConfig"
        private const val ONLINE_PARAFORMER_MODEL_CONFIG_CLASS =
            "com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig"

        @Volatile
        private var isAvailableChecked = false

        @Volatile
        private var isAvailableResult = false

        /**
         * Check if the real OnlineRecognizer API is available in the runtime AAR.
         *
         * Returns true only if the full sherpa-onnx AAR with online/streaming support
         * is present. The stub AAR does not include these classes.
         */
        fun isAvailable(): Boolean {
            if (isAvailableChecked) return isAvailableResult
            synchronized(this) {
                if (isAvailableChecked) return isAvailableResult
                isAvailableResult = try {
                    Class.forName(ONLINE_RECOGNIZER_CLASS)
                    Class.forName(ONLINE_RECOGNIZER_CONFIG_CLASS)
                    Class.forName(ONLINE_MODEL_CONFIG_CLASS)
                    DebugLog.i(TAG, "sherpa-onnx online/streaming API available")
                    true
                } catch (_: ClassNotFoundException) {
                    DebugLog.w(TAG, "sherpa-onnx AAR lacks online/streaming API (stub or outdated)")
                    false
                }
                isAvailableChecked = true
                return isAvailableResult
            }
        }
    }

    private var recognizerInstance: Any? = null
    private var _isActive = false

    val isActive: Boolean get() = _isActive

    /**
     * Create a new OnlineRecognizer with Paraformer streaming config.
     *
     * @param assetManager Android asset manager (null since models are on disk)
     * @param modelDir Directory containing encoder.int8.onnx, decoder.int8.onnx, tokens.txt
     * @param numThreads Number of inference threads
     * @param provider Inference provider ("cpu", "xnnpack", etc.)
     * @return true if creation succeeded
     */
    fun create(
        assetManager: AssetManager?,
        modelDir: File,
        numThreads: Int = 1,
        provider: String = "cpu",
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
            // Build config via reflection
            val config = buildConfig(modelDir, numThreads, provider)

            // Create OnlineRecognizer(assetManager, config)
            val recognizerCls = Class.forName(ONLINE_RECOGNIZER_CLASS)
            val ctor = recognizerCls.getConstructor(
                AssetManager::class.java,
                Class.forName(ONLINE_RECOGNIZER_CONFIG_CLASS),
            )
            recognizerInstance = ctor.newInstance(assetManager, config)
            _isActive = true

            DebugLog.i(TAG, "OnlineRecognizer created (provider=$provider, threads=$numThreads)")
            true
        } catch (e: Exception) {
            DebugLog.e(TAG, "Failed to create OnlineRecognizer: ${e.message}", e)
            recognizerInstance = null
            _isActive = false
            false
        }
    }

    /**
     * Feed audio samples and decode completely (for file recognition).
     *
     * This feeds the entire audio as one continuous stream and decodes
     * until the recognizer has consumed all data. Suitable for processing
     * audio files with the streaming model.
     *
     * @param samples Normalized float audio samples [-1.0, 1.0]
     * @param sampleRate Sample rate (must be 16000)
     * @return Recognized text, or empty string on error
     */
    fun recognize(samples: FloatArray, sampleRate: Int = 16000): String {
        val inst = recognizerInstance
        if (inst == null || !_isActive) {
            DebugLog.e(TAG, "recognize: recognizer not initialized")
            return ""
        }
        if (samples.isEmpty()) return ""

        return try {
            val recognizerCls = Class.forName(ONLINE_RECOGNIZER_CLASS)

            // acceptWaveform(samples, sampleRate)
            val acceptMethod = recognizerCls.getMethod(
                "acceptWaveform", FloatArray::class.java, Int::class.javaPrimitiveType,
            )
            acceptMethod.invoke(inst, samples, sampleRate)

            // Decode loop
            val isReadyMethod = recognizerCls.getMethod("isReady")
            val decodeMethod = recognizerCls.getMethod("decode")

            while (isReadyMethod.invoke(inst) as Boolean) {
                decodeMethod.invoke(inst)
            }

            // Get result
            val getResultMethod = recognizerCls.getMethod("getResult")
            val result = getResultMethod.invoke(inst)
            val text = result?.javaClass?.getMethod("getText")?.invoke(result) as? String ?: ""

            DebugLog.d(TAG, "recognize: ${samples.size} samples -> ${text.length} chars")
            text
        } catch (e: Exception) {
            DebugLog.e(TAG, "recognize failed: ${e.message}", e)
            ""
        }
    }

    /**
     * Feed audio samples for streaming recognition (real-time input).
     *
     * Each call feeds a chunk of audio into the recognizer. The recognizer
     * maintains internal state across calls. Use [isReady] + [decode] to
     * process, then [getResult] to get partial/final text.
     *
     * @param samples Normalized float audio samples [-1.0, 1.0]
     * @param sampleRate Sample rate (must be 16000)
     */
    fun feedAudio(samples: FloatArray, sampleRate: Int = 16000) {
        val inst = recognizerInstance ?: return
        if (!_isActive || samples.isEmpty()) return

        try {
            val acceptMethod = Class.forName(ONLINE_RECOGNIZER_CLASS).getMethod(
                "acceptWaveform", FloatArray::class.java, Int::class.javaPrimitiveType,
            )
            acceptMethod.invoke(inst, samples, sampleRate)
        } catch (e: Exception) {
            DebugLog.e(TAG, "feedAudio failed: ${e.message}", e)
        }
    }

    /**
     * Check if the recognizer has enough audio to produce a decode result.
     *
     * @return true if [decode] should be called
     */
    fun isReady(): Boolean {
        val inst = recognizerInstance ?: return false
        return try {
            Class.forName(ONLINE_RECOGNIZER_CLASS)
                .getMethod("isReady")
                .invoke(inst) as Boolean
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Decode one step, advancing the recognizer's internal state.
     *
     * Call [isReady] first; only call this when isReady returns true.
     */
    fun decode() {
        val inst = recognizerInstance ?: return
        try {
            Class.forName(ONLINE_RECOGNIZER_CLASS)
                .getMethod("decode")
                .invoke(inst)
        } catch (e: Exception) {
            DebugLog.e(TAG, "decode failed: ${e.message}", e)
        }
    }

    /**
     * Get the current recognition result.
     *
     * For streaming, this returns partial text as audio is being processed.
     * For [recognize], this is called internally and not needed externally.
     *
     * @return Recognized text so far
     */
    fun getResult(): String {
        val inst = recognizerInstance ?: return ""
        return try {
            val result = Class.forName(ONLINE_RECOGNIZER_CLASS)
                .getMethod("getResult")
                .invoke(inst)
            result?.javaClass?.getMethod("getText")?.invoke(result) as? String ?: ""
        } catch (e: Exception) {
            DebugLog.e(TAG, "getResult failed: ${e.message}", e)
            ""
        }
    }

    /**
     * Reset the recognizer's internal state for a new utterance.
     *
     * After calling this, the recognizer is ready to process a new
     * audio segment from the beginning.
     */
    fun reset() {
        val inst = recognizerInstance ?: return
        try {
            Class.forName(ONLINE_RECOGNIZER_CLASS)
                .getMethod("reset")
                .invoke(inst)
        } catch (e: Exception) {
            DebugLog.e(TAG, "reset failed: ${e.message}", e)
        }
    }

    /**
     * Release the recognizer and free native resources.
     */
    fun release() {
        recognizerInstance?.let { inst ->
            try {
                Class.forName(ONLINE_RECOGNIZER_CLASS)
                    .getMethod("release")
                    .invoke(inst)
            } catch (_: Exception) { }
        }
        recognizerInstance = null
        _isActive = false
        DebugLog.i(TAG, "OnlineRecognizer released")
    }

    // ==================== Config building via reflection ====================

    /**
     * Build OnlineRecognizerConfig via reflection for Paraformer streaming.
     *
     * Config chain:
     * OnlineParaformerModelConfig(encoder, decoder)
     *   -> OnlineModelConfig(paraformer, tokens, numThreads, provider)
     *     -> OnlineFeatureConfig(sampleRate=16000, featureDim=80)
     *       -> OnlineRecognizerConfig(featConfig, modelConfig)
     */
    private fun buildConfig(modelDir: File, numThreads: Int, provider: String): Any {
        val encoderPath = findModelFile(modelDir, "encoder.int8.onnx", "encoder.onnx")
        val decoderPath = findModelFile(modelDir, "decoder.int8.onnx", "decoder.onnx")
        val tokensPath = findModelFile(modelDir, "tokens.txt", "tokens")

        // OnlineParaformerModelConfig(encoder, decoder)
        val paraformerCls = Class.forName(ONLINE_PARAFORMER_MODEL_CONFIG_CLASS)
        val paraformerConfig = paraformerCls.getConstructor(
            String::class.java, String::class.java,
        ).newInstance(encoderPath, decoderPath)

        // OnlineModelConfig(paraformer, tokens, numThreads, provider)
        val modelCls = Class.forName(ONLINE_MODEL_CONFIG_CLASS)
        val modelConfig = modelCls.getConstructor(
            paraformerCls, String::class.java,
            Int::class.javaPrimitiveType, String::class.java,
        ).newInstance(paraformerConfig, tokensPath, numThreads, provider)

        // OnlineFeatureConfig(sampleRate=16000, featureDim=80)
        val featureCls = Class.forName(ONLINE_FEATURE_CONFIG_CLASS)
        val featureConfig = featureCls.getConstructor(
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        ).newInstance(16000, 80)

        // OnlineRecognizerConfig(featConfig, modelConfig)
        val configCls = Class.forName(ONLINE_RECOGNIZER_CONFIG_CLASS)
        return configCls.getConstructor(featureCls, modelCls)
            .newInstance(featureConfig, modelConfig)
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
}
