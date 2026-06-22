package top.hsyscn.opedrgent.stt

import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File

/**
 * Wrapper around sherpa-onnx's OnlineRecognizer for streaming ASR.
 *
 * Uses **reflection** to interact with the real OnlineRecognizer at runtime.
 * The real AAR (v1.13.2) uses Builder pattern for configs and requires
 * an OnlineStream for all decode/result operations.
 *
 * ## Real API structure (v1.13.2)
 * ```
 * OnlineRecognizer(OnlineRecognizerConfig config)
 * recognizer.createStream() → OnlineStream
 * stream.acceptWaveform(float[], sampleRate)
 * recognizer.isReady(stream) → boolean
 * recognizer.decode(stream)
 * recognizer.getResult(stream) → OnlineRecognizerResult.getText()
 * recognizer.isEndpoint(stream) → boolean
 * recognizer.reset(stream)
 * stream.release()
 * recognizer.release()
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
        private const val ONLINE_STREAM_CLASS =
            "com.k2fsa.sherpa.onnx.OnlineStream"

        @Volatile
        private var isAvailableChecked = false

        @Volatile
        private var isAvailableResult = false

        fun isAvailable(): Boolean {
            if (isAvailableChecked) return isAvailableResult
            synchronized(this) {
                if (isAvailableChecked) return isAvailableResult
                isAvailableResult = try {
                    Class.forName(ONLINE_RECOGNIZER_CLASS)
                    Class.forName(ONLINE_RECOGNIZER_CONFIG_CLASS)
                    Class.forName(ONLINE_MODEL_CONFIG_CLASS)
                    Class.forName(ONLINE_STREAM_CLASS)
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
    private var streamInstance: Any? = null
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
            val config = buildConfig(modelDir, numThreads, provider)

            // OnlineRecognizer(config) — single-arg constructor
            val recognizerCls = Class.forName(ONLINE_RECOGNIZER_CLASS)
            val configCls = Class.forName(ONLINE_RECOGNIZER_CONFIG_CLASS)
            val ctor = recognizerCls.getConstructor(configCls)
            recognizerInstance = ctor.newInstance(config)

            // createStream()
            streamInstance = recognizerCls.getMethod("createStream").invoke(recognizerInstance)

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
     *
     * Audio goes to OnlineStream.acceptWaveform().
     */
    fun feedAudio(samples: FloatArray, sampleRate: Int = 16000) {
        val stream = streamInstance ?: return
        if (!_isActive || samples.isEmpty()) return

        try {
            Class.forName(ONLINE_STREAM_CLASS)
                .getMethod("acceptWaveform", FloatArray::class.java, Int::class.javaPrimitiveType)
                .invoke(stream, samples, sampleRate)
        } catch (e: Exception) {
            DebugLog.e(TAG, "feedAudio failed: ${e.message}", e)
        }
    }

    /**
     * Feed entire audio and decode all at once (for file recognition).
     *
     * Feeds all samples, then decodes until no more data, returns final text.
     */
    fun recognize(samples: FloatArray, sampleRate: Int = 16000): String {
        val inst = recognizerInstance ?: return ""
        val stream = streamInstance ?: return ""
        if (samples.isEmpty()) return ""

        return try {
            // Feed all audio to stream
            Class.forName(ONLINE_STREAM_CLASS)
                .getMethod("acceptWaveform", FloatArray::class.java, Int::class.javaPrimitiveType)
                .invoke(stream, samples, sampleRate)

            // Signal end of input
            Class.forName(ONLINE_STREAM_CLASS)
                .getMethod("inputFinished")
                .invoke(stream)

            // Decode loop
            val recognizerCls = Class.forName(ONLINE_RECOGNIZER_CLASS)
            val streamCls = Class.forName(ONLINE_STREAM_CLASS)
            val isReadyMethod = recognizerCls.getMethod("isReady", streamCls)
            val decodeMethod = recognizerCls.getMethod("decode", streamCls)

            while (isReadyMethod.invoke(inst, stream) as Boolean) {
                decodeMethod.invoke(inst, stream)
            }

            // Get final result
            val result = recognizerCls.getMethod("getResult", streamCls).invoke(inst, stream)
            val text = result?.javaClass?.getMethod("getText")?.invoke(result) as? String ?: ""
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
        val inst = recognizerInstance ?: return false
        val stream = streamInstance ?: return false
        return try {
            Class.forName(ONLINE_RECOGNIZER_CLASS)
                .getMethod("isReady", Class.forName(ONLINE_STREAM_CLASS))
                .invoke(inst, stream) as Boolean
        } catch (e: Exception) {
            DebugLog.e(TAG, "isReady failed: ${e.message}", e)
            false
        }
    }

    /**
     * Decode one step, advancing the recognizer's internal state.
     */
    fun decode() {
        val inst = recognizerInstance ?: return
        val stream = streamInstance ?: return
        try {
            Class.forName(ONLINE_RECOGNIZER_CLASS)
                .getMethod("decode", Class.forName(ONLINE_STREAM_CLASS))
                .invoke(inst, stream)
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
        val inst = recognizerInstance ?: return ""
        val stream = streamInstance ?: return ""
        return try {
            val result = Class.forName(ONLINE_RECOGNIZER_CLASS)
                .getMethod("getResult", Class.forName(ONLINE_STREAM_CLASS))
                .invoke(inst, stream)
            result?.javaClass?.getMethod("getText")?.invoke(result) as? String ?: ""
        } catch (e: Exception) {
            DebugLog.e(TAG, "getResult failed: ${e.message}", e)
            ""
        }
    }

    /**
     * Check if an endpoint (pause/break) has been detected.
     */
    fun isEndpoint(): Boolean {
        val inst = recognizerInstance ?: return false
        val stream = streamInstance ?: return false
        return try {
            Class.forName(ONLINE_RECOGNIZER_CLASS)
                .getMethod("isEndpoint", Class.forName(ONLINE_STREAM_CLASS))
                .invoke(inst, stream) as Boolean
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Reset the recognizer for a new utterance.
     */
    fun reset() {
        val inst = recognizerInstance ?: return
        val stream = streamInstance ?: return
        try {
            Class.forName(ONLINE_RECOGNIZER_CLASS)
                .getMethod("reset", Class.forName(ONLINE_STREAM_CLASS))
                .invoke(inst, stream)
        } catch (e: Exception) {
            DebugLog.e(TAG, "reset failed: ${e.message}", e)
        }
    }

    /**
     * Release all resources (stream + recognizer).
     */
    fun release() {
        streamInstance?.let { s ->
            try {
                Class.forName(ONLINE_STREAM_CLASS).getMethod("release").invoke(s)
            } catch (_: Exception) { }
        }
        streamInstance = null

        recognizerInstance?.let { inst ->
            try {
                Class.forName(ONLINE_RECOGNIZER_CLASS).getMethod("release").invoke(inst)
            } catch (_: Exception) { }
        }
        recognizerInstance = null
        _isActive = false
        DebugLog.i(TAG, "OnlineRecognizer + OnlineStream released")
    }

    // ==================== Config building via reflection (Builder pattern) ====================

    private fun buildConfig(modelDir: File, numThreads: Int, provider: String): Any {
        val encoderPath = findModelFile(modelDir, "encoder.int8.onnx", "encoder.onnx")
        val decoderPath = findModelFile(modelDir, "decoder.int8.onnx", "decoder.onnx")
        val tokensPath = findModelFile(modelDir, "tokens.txt", "tokens")

        DebugLog.i(TAG, "构建流式配置: encoder=$encoderPath, decoder=$decoderPath, tokens=$tokensPath")

        // OnlineParaformerModelConfig.builder().setEncoder().setDecoder().build()
        val paraformerCls = Class.forName(ONLINE_PARAFORMER_MODEL_CONFIG_CLASS)
        val paraformerBuilder = paraformerCls.getMethod("builder").invoke(null)
        paraformerBuilder.javaClass.getMethod("setEncoder", String::class.java)
            .invoke(paraformerBuilder, encoderPath)
        paraformerBuilder.javaClass.getMethod("setDecoder", String::class.java)
            .invoke(paraformerBuilder, decoderPath)
        val paraformerConfig = paraformerBuilder.javaClass.getMethod("build").invoke(paraformerBuilder)

        // OnlineModelConfig.builder().setParaformer().setTokens().setNumThreads().setProvider().setDebug().build()
        val modelCls = Class.forName(ONLINE_MODEL_CONFIG_CLASS)
        val modelBuilder = modelCls.getMethod("builder").invoke(null)
        modelBuilder.javaClass.getMethod("setParaformer", paraformerCls)
            .invoke(modelBuilder, paraformerConfig)
        modelBuilder.javaClass.getMethod("setTokens", String::class.java)
            .invoke(modelBuilder, tokensPath)
        modelBuilder.javaClass.getMethod("setNumThreads", Int::class.javaPrimitiveType)
            .invoke(modelBuilder, numThreads)
        modelBuilder.javaClass.getMethod("setProvider", String::class.java)
            .invoke(modelBuilder, provider)
        modelBuilder.javaClass.getMethod("setDebug", Boolean::class.javaPrimitiveType)
            .invoke(modelBuilder, false)
        val modelConfig = modelBuilder.javaClass.getMethod("build").invoke(modelBuilder)

        // OnlineFeatureConfig via Builder or constructor
        val featureConfig = try {
            val featureCls = Class.forName(ONLINE_FEATURE_CONFIG_CLASS)
            val featureBuilder = featureCls.getMethod("builder").invoke(null)
            featureBuilder.javaClass.getMethod("setSampleRate", Int::class.javaPrimitiveType)
                .invoke(featureBuilder, 16000)
            featureBuilder.javaClass.getMethod("setFeatureDim", Int::class.javaPrimitiveType)
                .invoke(featureBuilder, 80)
            featureBuilder.javaClass.getMethod("build").invoke(featureBuilder)
        } catch (_: Exception) {
            val featureCls = Class.forName(ONLINE_FEATURE_CONFIG_CLASS)
            featureCls.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .newInstance(16000, 80)
        }

        // OnlineRecognizerConfig.builder().setModelConfig().setFeatureConfig().setEnableEndpoint().build()
        val configCls = Class.forName(ONLINE_RECOGNIZER_CONFIG_CLASS)
        val configBuilder = try {
            configCls.getMethod("builder").invoke(null)
        } catch (_: Exception) {
            return configCls.getConstructor(
                Class.forName(ONLINE_FEATURE_CONFIG_CLASS), modelCls,
            ).newInstance(featureConfig, modelConfig)
        }
        configBuilder.javaClass.getMethod("setModelConfig", modelCls)
            .invoke(configBuilder, modelConfig)
        configBuilder.javaClass.getMethod("setFeatureConfig", Class.forName(ONLINE_FEATURE_CONFIG_CLASS))
            .invoke(configBuilder, featureConfig)
        configBuilder.javaClass.getMethod("setEnableEndpoint", Boolean::class.javaPrimitiveType)
            .invoke(configBuilder, true)
        return configBuilder.javaClass.getMethod("build").invoke(configBuilder)!!
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
