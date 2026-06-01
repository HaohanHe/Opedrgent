package top.hsyscn.opedrgent.stt

data class SttResult(
    val text: String,
    val confidence: Float = 0f,
    val segments: List<SttSegment> = emptyList(),
    val durationMs: Long = 0,
    val processingTimeMs: Long = 0,
    val engineType: EngineType = EngineType.SHERPA_ONNX,
    val modelUsed: String = "",
)

data class SttSegment(
    val text: String,
    val startTimeMs: Long = 0,
    val endTimeMs: Long = 0,
    val confidence: Float = 0f,
)

enum class EngineType {
    SHERPA_ONNX,
    ANDROID_SPEECH_RECOGNIZER,
}
