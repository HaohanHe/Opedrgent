package com.k2fsa.sherpa.onnx

/**
 * Stub class for sherpa-onnx FeatureConfig (note: real AAR uses "FeatureConfig", not "OnlineFeatureConfig").
 * Matches real AAR v1.13.2 API with Builder pattern.
 */
class OnlineFeatureConfig private constructor(
    val sampleRate: Int = 16000,
    val featureDim: Int = 80,
) {
    class Builder {
        private var sampleRate = 16000
        private var featureDim = 80

        fun setSampleRate(v: Int): Builder { sampleRate = v; return this }
        fun setFeatureDim(v: Int): Builder { featureDim = v; return this }
        fun build() = OnlineFeatureConfig(sampleRate, featureDim)
    }

    companion object {
        @JvmStatic fun builder() = Builder()
    }
}
