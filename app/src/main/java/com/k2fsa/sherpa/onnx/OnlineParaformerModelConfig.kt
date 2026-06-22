package com.k2fsa.sherpa.onnx

/**
 * Stub class for sherpa-onnx OnlineParaformerModelConfig.
 * Matches real AAR v1.13.2 API with Builder pattern.
 */
class OnlineParaformerModelConfig private constructor(
    val encoder: String = "",
    val decoder: String = "",
) {
    class Builder {
        private var encoder = ""
        private var decoder = ""

        fun setEncoder(encoder: String): Builder { this.encoder = encoder; return this }
        fun setDecoder(decoder: String): Builder { this.decoder = decoder; return this }
        fun build() = OnlineParaformerModelConfig(encoder, decoder)
    }

    companion object {
        @JvmStatic fun builder() = Builder()
    }
}
