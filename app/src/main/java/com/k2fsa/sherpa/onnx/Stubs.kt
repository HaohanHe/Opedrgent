package com.k2fsa.sherpa.onnx

/**
 * Sherpa-ONNX stub classes for compilation without the AAR.
 * When the real sherpa-onnx AAR is available, delete this file
 * and restore the Maven/Local dependency.
 */

class FeatureConfig(
    val sampleRate: Int = 16000,
    val featureDim: Int = 80,
)

class OfflineParaformerModelConfig(val model: String)

class OfflineSenseVoiceModelConfig(val model: String)

class OfflineModelConfig(
    val paraformer: OfflineParaformerModelConfig? = null,
    val senseVoice: OfflineSenseVoiceModelConfig? = null,
    val tokens: String = "",
    val useItn: Boolean = false,
    val numThreads: Int = 1,
    val debug: Boolean = false,
    val provider: String = "cpu",
    val deviceType: String = "cpu",
)

class OfflineRecognizerConfig(
    val featConfig: FeatureConfig = FeatureConfig(),
    val modelConfig: OfflineModelConfig = OfflineModelConfig(),
    val numThreads: Int = 1,
    val debug: Boolean = false,
    val provider: String = "cpu",
    val deviceType: String = "cpu",
)

class OfflineStreamResult(val text: String? = null)

class OfflineStream {
    fun acceptWaveform(samples: FloatArray) {}
    fun release() {}
}

class OfflineRecognizer(config: OfflineRecognizerConfig) {
    fun createStream(): OfflineStream = OfflineStream()
    fun decode(stream: OfflineStream) {}
    fun getResult(stream: OfflineStream): OfflineStreamResult = OfflineStreamResult()
    fun release() {}
}

class EmbeddingModelConfig(val model: String)

class SegmenterModelConfig(val model: String)

class ClusteringConfig(val threshold: Float = 0.5f)

class SpeakerDiarizationConfig(
    val embedding: EmbeddingModelConfig = EmbeddingModelConfig(""),
    val segmenter: SegmenterModelConfig = SegmenterModelConfig(""),
    val clustering: ClusteringConfig = ClusteringConfig(),
)

class DiarizationStreamResult(
    val start: Float = 0f,
    val end: Float = 0f,
    val speaker: Int = 0,
)

class DiarizationStream {
    fun acceptWaveform(samples: FloatArray) {}
    fun release() {}
}

class SpeakerDiarization(config: SpeakerDiarizationConfig) {
    fun createStream(): DiarizationStream = DiarizationStream()
    fun isReady(stream: DiarizationStream): Boolean = false
    fun decode(stream: DiarizationStream) {}
    fun getResult(stream: DiarizationStream): DiarizationStreamResult? = null
    fun release() {}
}
