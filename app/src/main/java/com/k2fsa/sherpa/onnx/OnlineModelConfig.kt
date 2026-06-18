package com.k2fsa.sherpa.onnx

/**
 * Stub class for sherpa-onnx OnlineModelConfig.
 *
 * This stub exists for compilation against the stub AAR (v1.13.2).
 * At runtime, the real class from the full sherpa-onnx AAR is loaded instead.
 */
class OnlineModelConfig(
    var paraformer: OnlineParaformerModelConfig = OnlineParaformerModelConfig(),
    var tokens: String = "",
    var numThreads: Int = 1,
    var provider: String = "cpu",
    var debug: Boolean = false,
)
