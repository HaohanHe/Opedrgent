package top.hsyscn.opedrgent.stt

enum class ModelType {
    PARAFORMER,           // 高端设备 (≥6GB RAM), ~220MB, 精度最高
    SENSE_VOICE_SMALL,   // 中端设备 (4-6GB RAM), ~240MB, 多语言
    FUNASR_NANO_INT8,     // 低端设备 (<4GB RAM), ~20MB, 轻量
    STREAMING_PARAFORMER, // 真流式模型, ~237MB (INT8), 需≥6GB RAM
}

enum class SttLanguage {
    CHINESE,
    ENGLISH,
    AUTO,
}

enum class RecognitionMode {
    FILE,       // 文件模式（音频/视频文件转文字）
    STREAMING,  // 流式模式（实时录音）
}

/**
 * 录音模式
 *
 * 不同模式下 ASR 后处理策略不同：
 * - RECORDING: 通用录音模式，自动转文字、智能总结
 * - INTERNAL: 内录模式，系统内录音频（如通话录音、视频音频）
 */
enum class RecordingMode {
    RECORDING,  // 通用录音 — 自动转文字 + 智能总结
    INTERNAL,   // 内部录音 — 系统内录音频
}

data class SttConfig(
    val modelType: ModelType = ModelType.SENSE_VOICE_SMALL,
    val language: SttLanguage = SttLanguage.CHINESE,
    val mode: RecognitionMode = RecognitionMode.FILE,
    val recordingMode: RecordingMode = RecordingMode.RECORDING,
    val enablePunctuation: Boolean = true,
    val maxDurationSeconds: Int = 1800, // 30 分钟
    val segmentLengthSeconds: Int = 30, // 分段长度
)
