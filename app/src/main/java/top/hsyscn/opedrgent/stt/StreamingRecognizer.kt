package top.hsyscn.opedrgent.stt

import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.FeatureConfig
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
            )

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
}
