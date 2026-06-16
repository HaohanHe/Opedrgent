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

/**
 * 录音模式 — 借鉴得到大脑的四种场景化录音。
 *
 * 不同模式下 ASR 后处理策略不同：
 * - NORMAL: 通用模式，去除口头禅、润色为书面语
 * - MEETING: 会议模式，启用说话人分离、自动总结待办
 * - INTERNAL: 内录模式，系统内录音频（如通话录音、视频音频）
 * - CLASSROOM: 课堂模式，长段落连续识别、自动分段
 */
enum class RecordingMode {
    NORMAL,     // 普通录音 — 日常语音记录
    MEETING,    // 会议录音 — 说话人分离 + 自动纪要
    INTERNAL,   // 内部录音 — 系统内录音频
    CLASSROOM,  // 课堂录音 — 长时间连续识别
}

data class SttConfig(
    val modelType: ModelType = ModelType.SENSE_VOICE_SMALL,
    val language: SttLanguage = SttLanguage.CHINESE,
    val mode: RecognitionMode = RecognitionMode.FILE,
    val recordingMode: RecordingMode = RecordingMode.NORMAL,
    val enablePunctuation: Boolean = true,
    val maxDurationSeconds: Int = 1800, // 30 分钟
    val segmentLengthSeconds: Int = 30, // 分段长度
)
