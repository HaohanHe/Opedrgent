package top.hsyscn.opedrgent.stt

enum class ModelType {
    PARAFORMER,           // 高端设备 (≥6GB RAM), ~220MB, 精度最高
    SENSE_VOICE_SMALL,   // 中端设备 (4-6GB RAM), ~240MB, 多语言
    FUNASR_NANO_INT8,     // 低端设备 (<4GB RAM), ~20MB, 轻量
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

data class SttConfig(
    val modelType: ModelType = ModelType.SENSE_VOICE_SMALL,
    val language: SttLanguage = SttLanguage.CHINESE,
    val mode: RecognitionMode = RecognitionMode.FILE,
    val enablePunctuation: Boolean = true,
    val maxDurationSeconds: Int = 1800, // 30 分钟
    val segmentLengthSeconds: Int = 30, // 分段长度
)
