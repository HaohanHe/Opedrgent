package com.k2fsa.sherpa.onnx

/**
 * Stub class for sherpa-onnx OnlineModelConfig.
 * Matches real AAR v1.13.2 API with Builder pattern.
 */
class OnlineModelConfig private constructor(
    val paraformer: OnlineParaformerModelConfig = OnlineParaformerModelConfig.builder().build(),
    val tokens: String = "",
    val numThreads: Int = 1,
    val provider: String = "cpu",
    val debug: Boolean = true,
) {
    class Builder {
        private var paraformer = OnlineParaformerModelConfig.builder().build()
        private var tokens = ""
        private var numThreads = 1
        private var provider = "cpu"
        private var debug = true

        fun setParaformer(v: OnlineParaformerModelConfig): Builder { paraformer = v; return this }
        fun setTokens(v: String): Builder { tokens = v; return this }
        fun setNumThreads(v: Int): Builder { numThreads = v; return this }
        fun setProvider(v: String): Builder { provider = v; return this }
        fun setDebug(v: Boolean): Builder { debug = v; return this }
        fun build() = OnlineModelConfig(paraformer, tokens, numThreads, provider, debug)
    }

    companion object {
        @JvmStatic fun builder() = Builder()
    }
}
