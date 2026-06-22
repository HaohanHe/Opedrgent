package com.k2fsa.sherpa.onnx

/**
 * Stub class for sherpa-onnx OnlineRecognizerConfig.
 * Matches real AAR v1.13.2 API with Builder pattern.
 */
class OnlineRecognizerConfig private constructor(
    val featConfig: OnlineFeatureConfig = OnlineFeatureConfig.builder().build(),
    val modelConfig: OnlineModelConfig = OnlineModelConfig.builder().build(),
    val enableEndpoint: Boolean = true,
) {
    class Builder {
        private var featConfig = OnlineFeatureConfig.builder().build()
        private var modelConfig = OnlineModelConfig.builder().build()
        private var enableEndpoint = true

        fun setFeatureConfig(v: OnlineFeatureConfig): Builder { featConfig = v; return this }
        fun setModelConfig(v: OnlineModelConfig): Builder { modelConfig = v; return this }
        fun setOnlineModelConfig(v: OnlineModelConfig): Builder { modelConfig = v; return this }
        fun setEnableEndpoint(v: Boolean): Builder { enableEndpoint = v; return this }
        fun build() = OnlineRecognizerConfig(featConfig, modelConfig, enableEndpoint)
    }

    companion object {
        @JvmStatic fun builder() = Builder()
    }
}
