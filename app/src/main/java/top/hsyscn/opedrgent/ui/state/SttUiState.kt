package top.hsyscn.opedrgent.ui.state

import top.hsyscn.opedrgent.stt.SttResult

/**
 * STT 流程的 UI 状态机。
 *
 * 从 [top.hsyscn.opedrgent.ui.MainViewModel] 抽取，便于在 [SttStateManager] 中集中管理。
 */
sealed class SttUiState {
    data object Idle : SttUiState()
    data class SelectingSource(val showPicker: Boolean = true) : SttUiState()
    data class Validating(val uri: String) : SttUiState()
    data class DownloadingModel(val progress: Float, val modelSizeMb: Int) : SttUiState()
    data class DecodingAudio(val progress: Float, val fileName: String) : SttUiState()
    data class Recognizing(val progress: Float, val currentSegment: Int, val totalSegments: Int) : SttUiState()
    data class Done(val result: SttResult) : SttUiState()
    data class Error(val message: String, val errorCode: String = "UNKNOWN", val suggestion: String = "") : SttUiState()
}

/**
 * STT 处理的高阶进度状态，用于驱动进度对话框与取消逻辑。
 */
enum class SttProgressState {
    IDLE,
    DOWNLOADING_MODEL,
    EXTRACTING_AUDIO,
    RECOGNIZING,
    DONE,
    ERROR,
}

/**
 * 统一流式 ASR 向 UI 层推送的事件。
 */
sealed interface AsrUiEvent {
    data class FinalText(val text: String) : AsrUiEvent
    data class Error(val message: String) : AsrUiEvent
    data object EmptyResult : AsrUiEvent
}
