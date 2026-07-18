package top.hsyscn.opedrgent.ui.state

import android.media.AudioRecord
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import top.hsyscn.opedrgent.stt.MeetingTranscriptResult
import top.hsyscn.opedrgent.stt.SystemAudioRecorder
import top.hsyscn.opedrgent.ui.RecordingMode
import top.hsyscn.opedrgent.ui.components.RecordingState

/**
 * 录音状态管理器。
 *
 * 负责持有录音相关可变状态，使其跨页面（跨 Composable 生命周期）存活。
 * 录音控制逻辑仍位于 [top.hsyscn.opedrgent.ui.RecordingTab]，此处仅做状态容器，
 * 避免 [top.hsyscn.opedrgent.ui.MainViewModel] 继续膨胀。
 */
class RecorderStateManager {
    /** 当前录音状态，null=空闲/未录音，切页面不丢失 */
    var recordingState by mutableStateOf<RecordingState?>(null)

    /** 录音模式（常规/内录） */
    var recordingMode by mutableStateOf(RecordingMode.RECORDING)

    /** 转写结果 */
    var transcriptResult by mutableStateOf<MeetingTranscriptResult?>(null)

    /** 回放音频 URI */
    var playbackAudioUri by mutableStateOf<String?>(null)

    /** 录音已过秒数 */
    var recordingElapsedSeconds by mutableIntStateOf(0)

    /** AudioRecord 引用（不序列化，ViewModel 存活则有效） */
    @Volatile
    var audioRecordRef: AudioRecord? = null

    /** SystemAudioRecorder 引用 */
    @Volatile
    var systemAudioRecorderRef: SystemAudioRecorder? = null

    /** 当前录音临时 PCM 文件路径 */
    var recordingTempFilePath by mutableStateOf<String?>(null)

    /** 实时流式转写文本（录音期间跨页面保留） */
    var recordingStreamingText by mutableStateOf("")

    /** 是否正在流式识别中 */
    var recordingIsStreamingActive by mutableStateOf(false)

    /** 当前录音振幅（0~1） */
    var recordingAmplitude by mutableFloatStateOf(0f)

    /** 录音自动保存的笔记 ID */
    var autoSavedNoteId by mutableLongStateOf(0L)

    /** 是否已保存到笔记 */
    var savedToNote by mutableStateOf(false)

    /** 防空转门锁：防止 LAUNCHER 重复启动录音 */
    @Volatile
    var recordingLaunched: Boolean = false

    /** 重置为初始空闲状态 */
    fun reset() {
        recordingState = null
        recordingMode = RecordingMode.RECORDING
        transcriptResult = null
        playbackAudioUri = null
        recordingElapsedSeconds = 0
        audioRecordRef = null
        systemAudioRecorderRef = null
        recordingTempFilePath = null
        recordingStreamingText = ""
        recordingIsStreamingActive = false
        recordingAmplitude = 0f
        autoSavedNoteId = 0L
        savedToNote = false
        recordingLaunched = false
    }
}
