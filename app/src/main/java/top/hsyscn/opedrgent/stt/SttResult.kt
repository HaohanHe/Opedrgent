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
    MIMO_ASR,
}

// ==================== 会议转录结果（UI 层使用）====================

/**
 * 会议转录的完整结果，供 MeetingRecordScreen UI 渲染。
 */
data class MeetingTranscriptResult(
    val segments: List<MeetingSegment> = emptyList(),
    val fullText: String = "",
    val durationMs: Long = 0,
    val hasDiarization: Boolean = false,
    val speakers: Set<String> = emptySet(),
    val error: String? = null,
)

/**
 * 单个转录段落，带说话人标签。
 */
data class MeetingSegment(
    val text: String,
    val startTimeMs: Long = 0,
    val endTimeMs: Long = 0,
    val speakerLabel: String = "Speaker_0",
)
