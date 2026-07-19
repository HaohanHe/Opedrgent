package top.hsyscn.opedrgent.ui

import top.hsyscn.opedrgent.ui.theme.SpacingTokens
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import top.hsyscn.opedrgent.ui.theme.SizeTokens
import top.hsyscn.opedrgent.ui.theme.ElevationTokens
import top.hsyscn.opedrgent.ui.theme.themePrimary
import top.hsyscn.opedrgent.ui.theme.themeSuccess
import top.hsyscn.opedrgent.ui.theme.themeWarning
import top.hsyscn.opedrgent.ui.theme.themeError
import top.hsyscn.opedrgent.ui.theme.themeBgGray
import top.hsyscn.opedrgent.ui.theme.themeCardWhite
import top.hsyscn.opedrgent.ui.theme.themeTextDark
import top.hsyscn.opedrgent.ui.theme.themeTextGrey
import top.hsyscn.opedrgent.ui.theme.themeCoralLight
import top.hsyscn.opedrgent.ui.theme.themeSurfaceLight
import top.hsyscn.opedrgent.ui.theme.themeQuoteBg
import top.hsyscn.opedrgent.ui.theme.themeActionItemBg
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.SettingsVoice
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Satellite
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.note.NoteType
import top.hsyscn.opedrgent.model.HamContactLog
import top.hsyscn.opedrgent.stt.EngineType
import top.hsyscn.opedrgent.stt.MeetingSegment
import top.hsyscn.opedrgent.stt.MeetingTranscriptResult
import top.hsyscn.opedrgent.stt.ModelType
import top.hsyscn.opedrgent.stt.StreamingRecognitionState
import top.hsyscn.opedrgent.stt.SystemAudioRecorder
import top.hsyscn.opedrgent.ui.components.AudioPlayer
import top.hsyscn.opedrgent.ui.components.RecordingState
import top.hsyscn.opedrgent.storage.NotificationHelper
import top.hsyscn.opedrgent.service.FloatingWindowService
import top.hsyscn.opedrgent.service.MediaProjectionService
import top.hsyscn.opedrgent.service.RecordingForegroundService
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File
import java.io.FileOutputStream
import java.util.Date
import java.util.Locale
import top.hsyscn.opedrgent.ui.components.SttProgressDialog
import top.hsyscn.opedrgent.ui.state.SttProgressState
import top.hsyscn.opedrgent.ui.state.SttUiState
import top.hsyscn.opedrgent.ui.components.isAtLeastMediumWidth
import top.hsyscn.opedrgent.ui.components.isExpandedWidth
import top.hsyscn.opedrgent.ui.components.isLandscape



enum class RecordingMode(val maxHours: Int, val icon: ImageVector) {
    RECORDING(5, Icons.Default.Mic),
    INTERNAL(5, Icons.Default.SettingsVoice),
}

private fun RecordingMode.label(context: Context): String = context.getString(
    when (this) {
        RecordingMode.RECORDING -> R.string.recording_lu_yin
        RecordingMode.INTERNAL -> R.string.recording_shou_ji_nei_lu
    }
)

@Composable
private fun RecordingMode.label(): String = label(LocalContext.current)

data class CapturedPhoto(
    val filePath: String,
    val recordingTimeMs: Long,
    val bitmap: android.graphics.Bitmap? = null,
)

/**
 * 录音 Tab —— opedrgent风格重新设计。
 *
 * 功能：
 * - 4 种录音模式选择（2x2 网格）
 * - 全屏实时转录区域（流式 ASR）
 * - 底部控制：计时器、波形、大圆按钮、取消/完成、工具行
 * - 处理骨架屏 + 结果展示
 * - 保留自动保存、AI 总结、保存为笔记等全部原有功能
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingTab(
    vm: MainViewModel,
    onOpenSubScreen: (String) -> Unit,
    onNavigateToNotes: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current

    // 模式选择
    var recordingMode by remember { mutableStateOf(RecordingMode.RECORDING) }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val cameraPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
    }

    var pendingMediaProjection by remember { mutableStateOf<MediaProjection?>(null) }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // 通过前台服务获取 MediaProjection（Android 14+ 要求）
            MediaProjectionService.onReady = { projection ->
                pendingMediaProjection = projection
                // 启动悬浮窗
                FloatingWindowService.start(context)
            }
            MediaProjectionService.onError = { error ->
                scope.launch { snackbar.showSnackbar(error) }
            }
            val data = result.data
            if (data == null) {
                scope.launch { snackbar.showSnackbar(context.getString(R.string.recording_intent_empty)) }
                return@rememberLauncherForActivityResult
            }
            MediaProjectionService.start(context, result.resultCode, data)
        } else {
            scope.launch { snackbar.showSnackbar(context.getString(R.string.recording_no_permission)) }
        }
    }

    // STT 进度状态（导入音视频转录）
    val sttProgress by vm.sttProgress.collectAsState()
    val sttUiState by vm.sttUiState.collectAsState()
    var isImportingAudio by remember { mutableStateOf(false) }

    // 导入音视频文件选择器
    val importAudioVideoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            isImportingAudio = true
            vm.startSpeechToText(uri)
        }
    }

    // STT 完成/错误反馈
    LaunchedEffect(sttProgress) {
        when (sttProgress) {
            SttProgressState.DONE -> {
                if (isImportingAudio) {
                    isImportingAudio = false
                    val result = vm.sttResult.value
                    val text = result?.text?.trim().orEmpty()
                    if (text.isNotBlank()) {
                        try {
                            val dateStr = java.text.SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
                            val displayTitle = text.take(20).ifBlank {
                                context.getString(R.string.recording_import_transcript_title, dateStr)
                            }
                            val noteId = vm.createNoteFromText("", text, NoteType.ASR, autoFinalizeTitle = true)
                            NotificationHelper.showAutoSaveNote(
                                context = context,
                                noteId = noteId,
                                title = displayTitle,
                                preview = text,
                            )
                            val snackbarResult = snackbar.showSnackbar(
                                message = context.getString(R.string.recording_saved_as_note_word_count, text.length),
                                actionLabel = context.getString(R.string.recording_view),
                            )
                            if (snackbarResult == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                                onNavigateToNotes()
                            }
                        } catch (e: Exception) {
                            DebugLog.e("RecordingTab", "音视频转录自动保存失败: ${e.message}", e)
                            snackbar.showSnackbar(context.getString(R.string.recording_transcript_saved_failed))
                        }
                    } else {
                        snackbar.showSnackbar(context.getString(R.string.recording_transcript_empty))
                    }
                    vm.clearSttResult()
                }
            }
            SttProgressState.ERROR -> {
                if (isImportingAudio) {
                    isImportingAudio = false
                    val errorMsg = vm.sttError.value
                    snackbar.showSnackbar(errorMsg ?: context.getString(R.string.recording_transcript_failed))
                }
            }
            else -> {}
        }
    }

    // 录音期间拍摄的照片
    var capturedPhotos by remember { mutableStateOf<List<CapturedPhoto>>(emptyList()) }
    val startTime = remember { mutableLongStateOf(System.currentTimeMillis()) }

    val takePictureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val file = File(context.cacheDir, "photo_${System.currentTimeMillis()}.jpg")
                    FileOutputStream(file).use { out ->
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
                    }
                    val elapsed = System.currentTimeMillis() - startTime.value
                    val photo = CapturedPhoto(filePath = file.absolutePath, recordingTimeMs = elapsed, bitmap = bitmap)
                    withContext(Dispatchers.Main) {
                        capturedPhotos = capturedPhotos + photo
                    }
                } catch (e: Exception) {
                    DebugLog.e("RecordingTab", "保存照片失败: ${e.message}", e)
                }
            }
        }
    }

    var showOverlayPermissionDialog by remember { mutableStateOf(false) }
    var showBackConfirmDialog by remember { mutableStateOf(false) }
    var recordingState by remember { mutableStateOf<RecordingState?>(vm.recordingState) }
    var elapsedSeconds by remember { mutableIntStateOf(vm.recordingElapsedSeconds) }
    var amplitude by remember { mutableFloatStateOf(vm.recordingAmplitude) }
    var transcriptResult by remember { mutableStateOf<MeetingTranscriptResult?>(vm.transcriptResult) }
    var isProcessing by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var savedToNote by remember { mutableStateOf(vm.savedToNote) }
    var autoSaved by remember { mutableStateOf(false) }
    var autoSavedNoteId by remember { mutableLongStateOf(vm.autoSavedNoteId) }

    // 录音进行中按返回键先弹确认，避免误触直接退出
    BackHandler(enabled = recordingState == RecordingState.RECORDING || recordingState == RecordingState.PAUSED) {
        showBackConfirmDialog = true
    }

    // 录音完成后用于回放的音频文件路径
    var playbackAudioUri by remember { mutableStateOf<String?>(vm.playbackAudioUri) }

    // 声纹识别结果
    var identifiedSpeakerName by remember { mutableStateOf<String?>(null) }

    // 实时流式转录文本（跨页面持久化到 ViewModel，避免切 Tab 丢失）
    var streamingText by remember { mutableStateOf(vm.recordingStreamingText) }
    var isStreamingActive by remember { mutableStateOf(vm.recordingIsStreamingActive) }
    val transcriptScrollState = rememberScrollState()
    // 记录上次的文本长度，只在文本变长时才自动滚动到底部，
    // 避免模型回退修正时文本变短导致滚动位置来回跳（上下抖动）
    var lastTextLength by remember { mutableStateOf(0) }

    // 读取无感伙伴设置中的自动保存开关
    val autoSaveKey = androidx.datastore.preferences.core.booleanPreferencesKey("key_auto_save")
    val partnerPrefs = context.invisiblePartnerDataStore.data.collectAsState(initial = null).value
    val autoSaveEnabled = partnerPrefs?.get(autoSaveKey) ?: true

    // 录音核心对象托管到 ViewModel，切 Tab 不销毁
    val audioRecord = remember { mutableStateOf(vm.audioRecordRef) }
    val tempFilePath = remember { mutableStateOf(vm.recordingTempFilePath) }
    var systemAudioRecorder by remember { mutableStateOf(vm.systemAudioRecorderRef) }

    // 悬浮窗回调接线
    DisposableEffect(Unit) {
        FloatingWindowService.onPauseResume = {
            if (recordingState == RecordingState.RECORDING) {
                recordingState = RecordingState.PAUSED
            } else if (recordingState == RecordingState.PAUSED) {
                recordingState = RecordingState.RECORDING
            }
        }
        FloatingWindowService.onStop = {
            systemAudioRecorder?.stopRecording()
            systemAudioRecorder = null
            recordingState = RecordingState.PROCESSING
            FloatingWindowService.stop(context)
            RecordingForegroundService.stop(context)
        }
        onDispose {
            FloatingWindowService.onPauseResume = null
            FloatingWindowService.onStop = null
        }
    }

    // ==================== ViewModel 双向同步（跨页面存活） ====================
    // 首次组合时从 ViewModel 恢复状态（用户切页面回来后触发）
    LaunchedEffect(Unit) {
        if (vm.recordingState != null) {
            recordingState = vm.recordingState
            recordingMode = vm.recordingMode
            transcriptResult = vm.transcriptResult
            elapsedSeconds = vm.recordingElapsedSeconds
            playbackAudioUri = vm.playbackAudioUri
            autoSavedNoteId = vm.autoSavedNoteId
            savedToNote = vm.savedToNote
            streamingText = vm.recordingStreamingText
            isStreamingActive = vm.recordingIsStreamingActive
            amplitude = vm.recordingAmplitude
            audioRecord.value = vm.audioRecordRef
            tempFilePath.value = vm.recordingTempFilePath
            systemAudioRecorder = vm.systemAudioRecorderRef
        }
    }
    LaunchedEffect(recordingState) { vm.recordingState = recordingState }
    LaunchedEffect(recordingMode) { vm.recordingMode = recordingMode }
    LaunchedEffect(transcriptResult) { vm.transcriptResult = transcriptResult }
    LaunchedEffect(playbackAudioUri) { vm.playbackAudioUri = playbackAudioUri }
    LaunchedEffect(autoSavedNoteId) { vm.autoSavedNoteId = autoSavedNoteId }
    LaunchedEffect(savedToNote) { vm.savedToNote = savedToNote }
    LaunchedEffect(elapsedSeconds) { vm.recordingElapsedSeconds = elapsedSeconds }
    LaunchedEffect(streamingText) { vm.recordingStreamingText = streamingText }
    LaunchedEffect(isStreamingActive) { vm.recordingIsStreamingActive = isStreamingActive }
    LaunchedEffect(amplitude) { vm.recordingAmplitude = amplitude }
    LaunchedEffect(audioRecord.value) { vm.audioRecordRef = audioRecord.value }
    LaunchedEffect(tempFilePath.value) { vm.recordingTempFilePath = tempFilePath.value }
    LaunchedEffect(systemAudioRecorder) { vm.systemAudioRecorderRef = systemAudioRecorder }

    // 反向同步：后台协程更新 ViewModel 后，把最新流式文本同步回本地 UI 状态
    LaunchedEffect(vm.recordingStreamingText) {
        if (streamingText != vm.recordingStreamingText) {
            streamingText = vm.recordingStreamingText
        }
    }
    LaunchedEffect(vm.recordingIsStreamingActive) {
        if (isStreamingActive != vm.recordingIsStreamingActive) {
            isStreamingActive = vm.recordingIsStreamingActive
        }
    }
    LaunchedEffect(vm.recordingAmplitude) {
        if (amplitude != vm.recordingAmplitude) {
            amplitude = vm.recordingAmplitude
        }
    }

    // 波形动画条（独立 Composable，不触发 RecordingTab 重组）

    // 实时转录文本自动滚动（仅在文本增长时滚动到底，回退修正时不滚动避免抖动）
    LaunchedEffect(streamingText) {
        if (streamingText.isNotEmpty()) {
            // 只在文本变长时自动滚动到底部；
            // 模型回退修正时文本会变短，此时不滚动，避免上下抖动
            if (streamingText.length >= lastTextLength) {
                transcriptScrollState.animateScrollTo(transcriptScrollState.maxValue)
            }
            lastTextLength = streamingText.length
        }
    }

    // Start recording
    val startRecordingPipeline: (AudioRecord) -> Unit = { recorder ->
        // 先停止上一次的流式会话，避免多个协程同时运行
        vm.asrManager.stopStreaming()
        recordingState = RecordingState.RECORDING
        elapsedSeconds = 0
        RecordingForegroundService.start(context, recordingMode.label(context))
        amplitude = 0f
        transcriptResult = null
        savedToNote = false
        autoSaved = false
        autoSavedNoteId = 0L
        playbackAudioUri = null
        streamingText = ""
        isStreamingActive = false
        capturedPhotos = emptyList()
        identifiedSpeakerName = null

        val tempFile = File(context.cacheDir, "recording_${System.currentTimeMillis()}.pcm")
        tempFilePath.value = tempFile.absolutePath
        audioRecord.value = recorder
        startTime.value = System.currentTimeMillis()

        // 启动流式识别
        val isStreamingModelSelected = vm.getSelectedLocalModel() == ModelType.STREAMING_PARAFORMER.name
        val isBatchMode = !isStreamingModelSelected && vm.getSttStreamingMode() == "batch"
        if (!isBatchMode) {
            vm.backgroundScope.launch {
                try {
                    // 检查当前缓存的引擎是否与用户选择的模型类型匹配（流式 vs 非流式）
                    // 不匹配时强制重新初始化，避免切换模型后仍使用旧的缓存引擎
                    val currentIsStreaming = vm.asrManager.isCurrentEngineStreaming()
                    if (isStreamingModelSelected != currentIsStreaming) {
                        DebugLog.i("RecordingTab", "模型类型不匹配: 用户选择${if (isStreamingModelSelected) "流式" else "非流式"}, 缓存引擎${if (currentIsStreaming) "是流式" else "非流式"}，强制重新初始化")
                        vm.asrManager.invalidateEngine()
                    }
                    vm.asrManager.ensureInitialized()
                    val engine = vm.asrManager.getCachedEngine()
                    if (engine?.engineType == EngineType.ANDROID_SPEECH_RECOGNIZER) {
                        DebugLog.i("RecordingTab", "AndroidSpeechRecognizer 不支持 feedAudioData，跳过实时流式")
                    } else {
                        val isTrueStreaming = vm.asrManager.isCurrentEngineStreaming()
                        DebugLog.i("RecordingTab", "流式识别启动: isTrueStreaming=$isTrueStreaming, engine=${engine?.engineType}")
                        if (isStreamingModelSelected && !isTrueStreaming) {
                            // 流式模型降级到伪流式，通知用户
                            scope.launch {
                                snackbar.showSnackbar(context.getString(R.string.recording_online_recognizer_unavailable))
                            }
                        }
                        isStreamingActive = true
                        vm.asrManager.startStreaming().collect { state ->
                            when (state) {
                                // 直接写入 ViewModel，确保切 Tab 后新 Composable 仍能观察到最新文本
                                is StreamingRecognitionState.Recognizing -> vm.recordingStreamingText = state.partialText
                                is StreamingRecognitionState.FinalResult -> vm.recordingStreamingText = state.text
                                is StreamingRecognitionState.Error -> DebugLog.e("RecordingTab", "流式错误: ${state.message}")
                                else -> {}
                            }
                        }
                    }
                } catch (e: Exception) {
                    DebugLog.e("RecordingTab", "流式启动失败: ${e.message}", e)
                } finally {
                    isStreamingActive = false
                }
            }
        } else {
            DebugLog.i("RecordingTab", "批量识别模式，跳过实时流式 ASR")
        }

        // 录音读取协程：放到 ViewModel 的 backgroundScope，切 Tab 后继续录音
        vm.backgroundScope.launch {
            withContext(Dispatchers.IO) {
                val bufferSize = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val buffer = ShortArray(bufferSize / 2)
                FileOutputStream(tempFile).use { fos ->
                    // 使用 ViewModel 状态判断，避免 Composable 重建后闭包状态不一致
                    while (isActive && vm.recordingState != RecordingState.DONE && vm.recordingState != RecordingState.PROCESSING) {
                        if (vm.recordingState == RecordingState.PAUSED) {
                            delay(100)
                            continue
                        }
                        val read = recorder.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            fos.write(buffer.toByteArray(), 0, read * 2)
                            var sum = 0L
                            for (i in 0 until read) {
                                sum += buffer[i].toLong() * buffer[i].toLong()
                            }
                            val rms = kotlin.math.sqrt(sum.toDouble() / read).toFloat()
                            vm.recordingAmplitude = (rms / Short.MAX_VALUE).coerceIn(0f, 1f)

                            if (vm.recordingIsStreamingActive) {
                                val floats = FloatArray(read) { i -> buffer[i] / 32768.0f }
                                feedAudioToEngine(vm.asrManager.getCachedEngine(), floats)
                            }
                        }
                    }
                }
            }
        }
    }

    val startRecording: () -> Unit = {
        if (!hasPermission) {
            permLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

            val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize)
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                scope.launch { snackbar.showSnackbar(context.getString(R.string.recording_device_init_failed)) }
                recordingState = null
            } else {
                recorder.startRecording()
                startRecordingPipeline(recorder)
            }
        }
    }

    val startSystemAudioRecording: (android.media.projection.MediaProjection) -> Unit = { mediaProjection ->
        val sysRecorder = SystemAudioRecorder(context)
        val recorder = sysRecorder.startRecording(mediaProjection)
        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            scope.launch { snackbar.showSnackbar(context.getString(R.string.recording_system_audio_init_failed)) }
            recordingState = null
        } else {
            systemAudioRecorder = sysRecorder
            startRecordingPipeline(recorder)
        }
    }

    LaunchedEffect(pendingMediaProjection) {
        pendingMediaProjection?.let {
            startSystemAudioRecording(it)
            pendingMediaProjection = null
        }
    }

    // Timer
    val maxHours = vm.getRecordingMaxHours(recordingMode.name)
    LaunchedEffect(recordingState) {
        if (recordingState == RecordingState.RECORDING) {
            while (isActive) {
                delay(1000)
                elapsedSeconds++
                // 同步到悬浮窗和通知
                val m = elapsedSeconds / 60
                val s = elapsedSeconds % 60
                val timerText = "%02d:%02d".format(m, s)
                FloatingWindowService.updateTimer(timerText)
                RecordingForegroundService.updateTimer(context, timerText)
                // 检查时长限制
                if (maxHours > 0 && elapsedSeconds >= maxHours * 3600) {
                    recordingState = RecordingState.PROCESSING
                    break
                }
            }
        }
    }

    // Auto-stop when timer hits max hours limit
    var autoStopped by remember { mutableStateOf(false) }
    LaunchedEffect(recordingState) {
        if (recordingState == RecordingState.PROCESSING && !isProcessing && !autoStopped) {
            autoStopped = true
            isProcessing = true
            try {
                FloatingWindowService.stop(context)
                MediaProjectionService.stop(context)
                RecordingForegroundService.stop(context)
            } catch (_: Exception) {}
            try {
                audioRecord.value?.stop()
                audioRecord.value?.release()
                audioRecord.value = null
            } catch (_: Exception) {}
            try {
                systemAudioRecorder?.stopRecording()
                systemAudioRecorder = null
            } catch (_: Exception) {}
            vm.asrManager.stopStreaming()

            scope.launch {
                var pcmPath: String? = null
                try {
                    delay(800)
                    pcmPath = tempFilePath.value
                    if (pcmPath == null) {
                        snackbar.showSnackbar(context.getString(R.string.recording_file_not_found))
                        isProcessing = false
                        recordingState = RecordingState.DONE
                        return@launch
                    }
                    val pcmFile = java.io.File(pcmPath)
                    val wavFile = java.io.File(context.cacheDir, "recording_${System.currentTimeMillis()}.wav")
                    pcmToWav(pcmFile, wavFile, 16000, 1, 16)

                    val streamText = streamingText.trim()
                    transcriptResult = if (streamText.isNotBlank()) {
                        MeetingTranscriptResult(
                            segments = listOf(
                                MeetingSegment(
                                    text = streamText,
                                    startTimeMs = 0,
                                    endTimeMs = elapsedSeconds * 1000L,
                                    speakerLabel = "Speaker_0",
                                ),
                            ),
                            fullText = streamText,
                            durationMs = elapsedSeconds * 1000L,
                        )
                    } else {
                        MeetingTranscriptResult(
                            segments = emptyList(),
                            fullText = context.getString(R.string.recording_no_content),
                            durationMs = elapsedSeconds * 1000L,
                        )
                    }
                    playbackAudioUri = wavFile.absolutePath
                    isProcessing = false
                    // 清理临时PCM文件
                    java.io.File(pcmPath).delete()
                } catch (e: Exception) {
                    snackbar.showSnackbar(context.getString(R.string.recording_processing_failed, e.message))
                    // 异常时也清理PCM文件
                    val errPcm = pcmPath
                    if (errPcm != null) java.io.File(errPcm).delete()
                    isProcessing = false
                    recordingState = RecordingState.DONE
                }
            }
        }
    }

    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            try {
                audioRecord.value?.stop()
                audioRecord.value?.release()
            } catch (_: Exception) {}
            try {
                systemAudioRecorder?.stopRecording()
            } catch (_: Exception) {}
            try {
                vm.asrManager.stopStreaming()
            } catch (_: Exception) {}
            try {
                FloatingWindowService.stop(context)
            } catch (_: Exception) {}
            try {
                RecordingForegroundService.stop(context)
            } catch (_: Exception) {}
            MediaProjectionService.stop(context)
        }
    }

    // Save to note dialog
    if (showSaveDialog) {
        var noteTitle by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text(stringResource(R.string.recording_save_as_note)) },
            text = {
                Column {
                    Text(stringResource(R.string.recording_save_as_note_desc), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(SpacingTokens.md))
                    androidx.compose.material3.OutlinedTextField(
                        value = noteTitle,
                        onValueChange = { noteTitle = it },
                        label = { Text(stringResource(R.string.recording_note_title_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val text = transcriptResult?.fullText ?: ""
                    if (text.isNotBlank()) {
                        scope.launch {
                            val contentWithPhotos = text + formatPhotosForNote(capturedPhotos)
                            val autoTitleEnabled = vm.isAutoGenerateNoteTitle()
                            val dateStr = java.text.SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
                            val title = noteTitle.ifBlank {
                                if (autoTitleEnabled) "" else context.getString(R.string.recording_note_title_date, dateStr)
                            }
                            vm.createNoteFromText(
                                title,
                                contentWithPhotos,
                                NoteType.ASR,
                                sourceUri = playbackAudioUri,
                                autoFinalizeTitle = noteTitle.isBlank() && autoTitleEnabled,
                            )
                            savedToNote = true
                            showSaveDialog = false
                            snackbar.showSnackbar(context.getString(R.string.msg_saved_as_note))
                        }
                    }
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    // 悬浮窗权限请求对话框
    if (showOverlayPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showOverlayPermissionDialog = false },
            title = { Text(stringResource(R.string.recording_overlay_permission_title)) },
            text = {
                Text(
                    text = stringResource(R.string.recording_overlay_permission_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(onClick = {
                    showOverlayPermissionDialog = false
                    try {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    } catch (_: Exception) {}
                }) { Text(stringResource(R.string.recording_go_to_settings)) }
            },
            dismissButton = {
                TextButton(onClick = { showOverlayPermissionDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    // 录音中返回确认对话框
    if (showBackConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showBackConfirmDialog = false },
            title = { Text(stringResource(R.string.recording_stop_confirm_title)) },
            text = { Text(stringResource(R.string.recording_stop_confirm_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBackConfirmDialog = false
                        recordingState = null
                        RecordingForegroundService.stop(context)
                        try {
                            audioRecord.value?.stop()
                            audioRecord.value?.release()
                            audioRecord.value = null
                        } catch (_: Exception) {}
                        try {
                            systemAudioRecorder?.stopRecording()
                            systemAudioRecorder = null
                        } catch (_: Exception) {}
                        vm.asrManager.stopStreaming()
                        tempFilePath.value?.let { File(it).delete() }
                        streamingText = ""
                        capturedPhotos.forEach { photo ->
                            try { File(photo.filePath).delete() } catch (_: Exception) {}
                        }
                        capturedPhotos = emptyList()
                    },
                ) { Text(stringResource(R.string.action_stop)) }
            },
            dismissButton = {
                TextButton(onClick = { showBackConfirmDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbar) },
        containerColor = themeBgGray(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        val contentMaxWidth = when {
            isExpandedWidth() -> 980.dp
            isAtLeastMediumWidth() -> 760.dp
            else -> Dp.Unspecified
        }
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                modifier = Modifier.then(
                    if (contentMaxWidth != Dp.Unspecified) {
                        Modifier.widthIn(max = contentMaxWidth)
                    } else {
                        Modifier
                    }
                ),
            ) {
            when {
                // ==================== 录音中 / 暂停 ====================
                recordingState == RecordingState.RECORDING || recordingState == RecordingState.PAUSED -> {
                    RecordingScreen(
                        vm = vm,
                        mode = recordingMode,
                        state = recordingState ?: RecordingState.RECORDING,
                        elapsedSeconds = elapsedSeconds,
                        streamingText = streamingText,
                        amplitude = amplitude,
                        scrollState = transcriptScrollState,
                        onPause = { recordingState = RecordingState.PAUSED },
                        onResume = { recordingState = RecordingState.RECORDING },
                        onDone = {
                            recordingState = RecordingState.PROCESSING
                            isProcessing = true
                            try {
                                FloatingWindowService.stop(context)
                                MediaProjectionService.stop(context)
                                RecordingForegroundService.stop(context)
                            } catch (_: Exception) {}
                            try {
                                audioRecord.value?.stop()
                                audioRecord.value?.release()
                                audioRecord.value = null
                            } catch (_: Exception) {}
                            try {
                                systemAudioRecorder?.stopRecording()
                                systemAudioRecorder = null
                            } catch (_: Exception) {}
                            vm.asrManager.stopStreaming()

                            scope.launch {
                                try {
                                    delay(800) // 等待流式 FinalResult
                                    val pcmPath = tempFilePath.value
                                    if (pcmPath == null) {
                                        snackbar.showSnackbar(context.getString(R.string.recording_file_not_found))
                                        isProcessing = false
                                        recordingState = RecordingState.DONE
                                        return@launch
                                    }

                                    val pcmFile = File(pcmPath)
                                    DebugLog.i("RecordingTab", "PCM 文件: ${pcmFile.length() / 1024}KB")
                                    val wavFile = File(context.cacheDir, "recording_${System.currentTimeMillis()}.wav")
                                    pcmToWav(pcmFile, wavFile, 16000, 1, 16)

                                    // 优先使用流式结果，若为空则回退到文件转写
                                    val streamText = streamingText.trim()
                                    transcriptResult = if (streamText.isNotBlank()) {
                                        DebugLog.i("RecordingTab", "使用流式转写结果: ${streamText.length}字")
                                        MeetingTranscriptResult(
                                            segments = listOf(
                                                MeetingSegment(
                                                    text = streamText,
                                                    startTimeMs = 0,
                                                    endTimeMs = elapsedSeconds * 1000L,
                                                    speakerLabel = "Speaker_0",
                                                ),
                                            ),
                                            fullText = streamText,
                                            durationMs = elapsedSeconds * 1000L,
                                            hasDiarization = false,
                                            speakers = setOf("Speaker_0"),
                                        )
                                    } else {
                                        DebugLog.i("RecordingTab", "流式结果为空，回退到文件转写")
                                        transcribeWithAsrManager(context, wavFile, vm.asrManager)
                                    }

                                    // 转写失败时向用户反馈错误信息
                                    val transcriptText = transcriptResult?.fullText ?: ""
                                    if (transcriptText.isBlank() && transcriptResult?.error != null) {
                                        snackbar.showSnackbar(transcriptResult?.error.orEmpty())
                                    }
                                    if (recordingMode == RecordingMode.RECORDING && transcriptText.isNotBlank() && autoSaveEnabled) {
                                        // 先设置音频路径（createNoteFromText 需要 sourceUri）
                                        playbackAudioUri = wavFile.absolutePath
                                        try {
                                            val autoTitleEnabled = vm.isAutoGenerateNoteTitle()
                                            val dateStr = java.text.SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
                                            val displayTitle = transcriptText.take(20).ifBlank { context.getString(R.string.recording_note_title_date, dateStr) }
                                            val noteTitle = if (autoTitleEnabled) "" else displayTitle
                                            val contentWithPhotos = transcriptText + formatPhotosForNote(capturedPhotos)
                                            val noteId = vm.createNoteFromText(
                                                noteTitle,
                                                contentWithPhotos,
                                                NoteType.ASR,
                                                sourceUri = playbackAudioUri,
                                                autoFinalizeTitle = autoTitleEnabled,
                                            )
                                            autoSaved = true
                                            autoSavedNoteId = noteId
                                            savedToNote = true
                                            NotificationHelper.showAutoSaveNote(
                                                context = context,
                                                noteId = noteId,
                                                title = displayTitle,
                                                preview = transcriptText,
                                            )
                                            DebugLog.i("RecordingTab", "自动保存笔记成功, id=$noteId")
                                        } catch (e: Exception) {
                                            DebugLog.e("RecordingTab", "自动保存失败: ${e.message}", e)
                                        }
                                    }

                                    playbackAudioUri = wavFile.absolutePath
                                    File(pcmPath).delete()

                                    // 声纹识别：提取录音前 5 秒音频匹配已注册说话人
                                    identifiedSpeakerName = null
                                    if (vm.voiceprintManager.listSpeakers().isNotEmpty()) {
                                        try {
                                            val extractor = vm.speakerEmbeddingExtractor
                                            if (extractor.checkApiAvailability()) {
                                                val modelDir = java.io.File(context.filesDir, "speaker_diarizer")
                                                if (modelDir.exists() && !extractor.isAvailable) {
                                                    extractor.initialize(modelDir)
                                                }
                                            }
                                            if (extractor.isAvailable) {
                                                val embedding = extractor.extractFromFile(wavFile)
                                                if (embedding != null) {
                                                    val matchedId = vm.voiceprintManager.matchSpeakerByEmbedding(embedding)
                                                    if (matchedId != null) {
                                                        val speaker = vm.voiceprintManager.getSpeakerById(matchedId)
                                                        if (speaker != null) {
                                                            identifiedSpeakerName = speaker.name
                                                            DebugLog.i("RecordingTab", "声纹识别成功: ${speaker.name}")
                                                        }
                                                    }
                                                }
                                            } else {
                                                // 降级：使用统计特征匹配
                                                val features = vm.voiceprintManager.extractAudioFeatures(wavFile)
                                                if (features != null) {
                                                    val matchedId = vm.voiceprintManager.matchSpeaker(features)
                                                    if (matchedId != null) {
                                                        val speaker = vm.voiceprintManager.getSpeakerById(matchedId)
                                                        if (speaker != null) {
                                                            identifiedSpeakerName = speaker.name
                                                            DebugLog.i("RecordingTab", "声纹识别成功(统计特征): ${speaker.name}")
                                                        }
                                                    }
                                                }
                                            }
                                        } catch (e: Exception) {
                                            DebugLog.w("RecordingTab", "声纹识别失败: ${e.message}")
                                        }
                                    }

                                    // 转录完成后自动生成智能总结
                                    val tr = transcriptResult
                                    if (tr != null && tr.fullText.isNotBlank()) {
                                        scope.launch {
                                            try {
                                                val apiConfig = vm.apiSettings.getApiConfig() ?: return@launch
                                                val summary = vm.smartSummaryGenerator.generate(tr, apiConfig)
                                                if (summary != null) {
                                                    transcriptResult = tr.copy(smartSummary = summary)
                                                }
                                            } catch (e: Exception) {
                                                DebugLog.e("RecordingTab", "智能总结生成失败: ${e.message}", e)
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    DebugLog.e("RecordingTab", "处理失败: ${e.message}", e)
                                    snackbar.showSnackbar(context.getString(R.string.recording_processing_failed_generic, e.message))
                                } finally {
                                    isProcessing = false
                                    recordingState = RecordingState.DONE
                                }
                            }
                        },
                        onTakePhoto = {
                            if (!hasCameraPermission) {
                                cameraPermLauncher.launch(Manifest.permission.CAMERA)
                            } else {
                                takePictureLauncher.launch(null)
                            }
                        },
                        onCancel = {
                            recordingState = null
                            RecordingForegroundService.stop(context)
                            try {
                                audioRecord.value?.stop()
                                audioRecord.value?.release()
                                audioRecord.value = null
                            } catch (_: Exception) {}
                            try {
                                systemAudioRecorder?.stopRecording()
                                systemAudioRecorder = null
                            } catch (_: Exception) {}
                            vm.asrManager.stopStreaming()
                            tempFilePath.value?.let { File(it).delete() }
                            streamingText = ""
                            capturedPhotos.forEach { photo ->
                                try { File(photo.filePath).delete() } catch (_: Exception) {}
                            }
                            capturedPhotos = emptyList()
                        },
                        snackbarHostState = snackbar,
                    )
                }

                // ==================== 处理中骨架屏 ====================
                isProcessing -> {
                    SkeletonLoadingScreen()
                }

                // ==================== 完成，显示结果 ====================
                recordingState == RecordingState.DONE -> {
                    val doneMaxWidth = when {
                        isExpandedWidth() -> 840.dp
                        isAtLeastMediumWidth() -> 720.dp
                        else -> Dp.Unspecified
                    }
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .then(
                                    if (doneMaxWidth != Dp.Unspecified) {
                                        Modifier.widthIn(max = doneMaxWidth)
                                    } else {
                                        Modifier
                                    }
                                ),
                        ) {
                            // 顶部标题
                            Text(
                                text = stringResource(R.string.recording_title),
                                style = MaterialTheme.typography.headlineLarge,
                                modifier = Modifier.padding(start = SpacingTokens.xl, top = SpacingTokens.lg, bottom = SpacingTokens.sm),
                            )

                            // 模式标签
                            Row(
                                modifier = Modifier.padding(horizontal = SpacingTokens.xl),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                            Icon(
                                imageVector = recordingMode.icon,
                                contentDescription = null, // 装饰性图标，文本已说明
                                tint = themePrimary(),
                                modifier = Modifier.size(SizeTokens.iconMd),
                            )
                            Spacer(Modifier.width(SpacingTokens.sm))
                            Text(
                                text = recordingMode.label(),
                                style = MaterialTheme.typography.bodyLarge,
                                color = themeTextGrey(),
                            )
                        }

                        Spacer(Modifier.height(SpacingTokens.md))

                        // 转写结果
                        AnimatedVisibility(
                            visible = transcriptResult != null,
                            enter = slideInVertically(initialOffsetY = { it / 3 }) + fadeIn(),
                            exit = slideOutVertically(targetOffsetY = { it / 3 }) + fadeOut(),
                        ) {
                            transcriptResult?.let { result ->
                                TranscriptResultCard(
                                    result = result,
                                    autoSaved = autoSaved,
                                    selectedMode = recordingMode,
                                    capturedPhotos = capturedPhotos,
                                    identifiedSpeakerName = identifiedSpeakerName,
                                    hamModeEnabled = vm.apiSettings.isHamModeEnabled(),
                                    onCopy = {
                                        clipboard.setText(AnnotatedString(result.fullText))
                                        scope.launch { snackbar.showSnackbar(context.getString(R.string.recording_copied)) }
                                    },
                                    onNavigateToNotes = onNavigateToNotes,
                                    onSave = { showSaveDialog = true },
                                    onAiSummary = {
                                        val structuredPrompt = context.getString(R.string.recording_ai_zong_jie_ti_shi, result.fullText)
                                        vm.sendUserMessage(structuredPrompt)
                                        scope.launch { snackbar.showSnackbar(context.getString(R.string.msg_sent_to_ai)) }
                                    },
                                    onConvertToContactLog = {
                                        if (result.fullText.isBlank()) {
                                            scope.launch { snackbar.showSnackbar(context.getString(R.string.recording_contact_log_empty_transcript)) }
                                        } else {
                                            scope.launch {
                                                val contactLogContext = vm.buildContactLogContext(result.fullText)
                                                vm.requestContactLog(
                                                    transcript = result.fullText,
                                                    conversationContext = contactLogContext,
                                                    recordingStartAtUtcMs = startTime.longValue,
                                                )
                                                snackbar.showSnackbar(context.getString(R.string.recording_generating_contact_log))
                                            }
                                        }
                                    },
                                    onDiscardAutoSave = {
                                        scope.launch {
                                            vm.deleteNote(autoSavedNoteId)
                                            autoSaved = false
                                            autoSavedNoteId = 0L
                                            savedToNote = false
                                            snackbar.showSnackbar(context.getString(R.string.msg_save_undone))
                                        }
                                    },
                                    onContinueRecording = {
                                        transcriptResult = null
                                        savedToNote = false
                                        autoSaved = false
                                        autoSavedNoteId = 0L
                                        startRecording()
                                    },
                                )
                            }
                        }

                        // 音频回放
                        val audioUri = playbackAudioUri
                        if (audioUri != null) {
                            Spacer(Modifier.height(SpacingTokens.md))
                            AudioPlayer(audioUri = audioUri)
                        }

                        Spacer(Modifier.height(SpacingTokens.xl))
                        }
                    }
                }

                // ==================== 空闲状态：模式选择 ====================
                else -> {
                    IdleModeSelection(
                        vm = vm,
                        selectedMode = recordingMode,
                        onModeSelected = { recordingMode = it },
                        onStartRecording = {
                            when (recordingMode) {
                                RecordingMode.INTERNAL -> {
                                    if (Build.VERSION.SDK_INT < 29) {
                                        scope.launch { snackbar.showSnackbar(context.getString(R.string.recording_need_android_10)) }
                                    } else {
                                        // 检查悬浮窗权限
                                        if (!Settings.canDrawOverlays(context)) {
                                            showOverlayPermissionDialog = true
                                        } else {
                                            scope.launch { snackbar.showSnackbar(context.getString(R.string.recording_play_media_hint)) }
                                            val mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                            mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                                        }
                                    }
                                }
                                else -> startRecording()
                            }
                        },
                        onImportAudioVideo = { importAudioVideoLauncher.launch(arrayOf("audio/*", "video/*")) },
                        isSttEnabled = vm.isSttEnabled(),
                        onSttDisabled = { scope.launch { snackbar.showSnackbar(context.getString(R.string.recording_enable_stt_first)) } },
                    )
                }
            }

            // 导入音视频：立即显示的等待对话框（在 STT 进度对话框接管前）
            if (isImportingAudio && sttProgress == SttProgressState.IDLE) {
                AlertDialog(
                    onDismissRequest = {},
                    title = { Text(stringResource(R.string.recording_import_audio_video_title)) },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(SizeTokens.iconXl), strokeWidth = SizeTokens.iconXs)
                            Spacer(Modifier.width(SpacingTokens.md))
                            Text(stringResource(R.string.recording_transcribing_wait))
                        }
                    },
                    confirmButton = {},
                )
            }

            // 导入音视频转录进度对话框（STT 详细进度接管）
            if (isImportingAudio && sttProgress != SttProgressState.IDLE && sttProgress != SttProgressState.DONE) {
                val downloadProg = (sttUiState as? SttUiState.DownloadingModel)?.progress
                val phaseText = when (sttUiState) {
                    is SttUiState.Validating -> stringResource(R.string.recording_validating_file)
                    is SttUiState.DecodingAudio -> stringResource(R.string.recording_decoding_audio)
                    is SttUiState.Recognizing -> {
                        val r = sttUiState as SttUiState.Recognizing
                        if (r.totalSegments > 0) stringResource(R.string.recording_recognizing_speech_progress, r.currentSegment, r.totalSegments)
                        else stringResource(R.string.recording_recognizing_speech)
                    }
                    else -> null
                }
                SttProgressDialog(
                    progressState = sttProgress,
                    downloadProgress = downloadProg,
                    currentPhase = phaseText,
                    onCancel = {
                        vm.cancelStt()
                        isImportingAudio = false
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.Center),
                )
            }
            }
        }
    }

    // ==================== 通联日志对话框 ====================
    var showContactLogDialog by remember { mutableStateOf(false) }
    var currentContactLog by remember { mutableStateOf<top.hsyscn.opedrgent.model.HamContactLog?>(null) }
    // 编辑草稿独立于对话框生命周期：对话框关闭后草稿保留，可重新打开继续编辑（修复 dismiss 后状态丢失问题）
    var editDraft by remember { mutableStateOf<top.hsyscn.opedrgent.model.HamContactLog?>(null) }
    // 导出 ADIF 前若设置中缺少本台呼号/网格，弹出提醒
    var showStationInfoMissingDialog by remember { mutableStateOf(false) }

    LaunchedEffect(vm.contactLog) {
        if (vm.contactLog != null && vm.apiSettings.isHamModeEnabled()) {
            currentContactLog = vm.contactLog
            editDraft = vm.contactLog
            showContactLogDialog = true
        }
    }

    var lastGenRequest by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) { lastGenRequest = System.currentTimeMillis() }
    LaunchedEffect(vm.isGeneratingContactLog) {
        if (vm.isGeneratingContactLog) {
            lastGenRequest = System.currentTimeMillis()
        } else {
            val elapsed = System.currentTimeMillis() - lastGenRequest
            if (elapsed > 1000 && vm.contactLog == null) {
                scope.launch { snackbar.showSnackbar(context.getString(R.string.recording_contact_log_generation_failed)) }
            }
        }
    }

    if (vm.isGeneratingContactLog && !showContactLogDialog) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(SpacingTokens.md),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(SizeTokens.iconLg))
                Spacer(Modifier.width(SpacingTokens.sm))
                Text(stringResource(R.string.recording_generating_contact_log), color = themeTextGrey())
            }
        }
    }

    // 对话框关闭后，若仍有草稿，提供重新打开入口（修复 dismiss 后无法重开问题）
    if (!showContactLogDialog && editDraft != null && vm.apiSettings.isHamModeEnabled()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SpacingTokens.md, vertical = SpacingTokens.sm),
            colors = CardDefaults.cardColors(containerColor = themeCardWhite()),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(SpacingTokens.sm)
                    .clickable { showContactLogDialog = true },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Satellite,
                    contentDescription = null,
                    tint = themeWarning(),
                    modifier = Modifier.size(SizeTokens.iconSm),
                )
                Spacer(Modifier.width(SpacingTokens.sm))
                Text(
                    text = stringResource(
                        R.string.recording_contact_log_draft,
                        editDraft?.satName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.recording_unnamed)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = themeTextDark(),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showContactLogDialog = true }) {
                    Text(stringResource(R.string.recording_view_edit))
                }
            }
        }
    }

    if (showContactLogDialog && editDraft != null) {
        val log = editDraft!!
        val satMissing = log.satName.isBlank()
        val dateMissing = log.date.isBlank()
        val timeOnMissing = log.timeOn.isBlank()
        val requiredMissing = satMissing || dateMissing || timeOnMissing
        // 录音开始时间会预填 timeOn，因此判断 AI 是否识别到关键信息时不再看 timeOn，而是看卫星/呼号/频率
        val aiEmpty = log.satName.isBlank() && log.callsign.isBlank() && log.frequency.isBlank()

        val requiredLabel = stringResource(R.string.recording_required)
        val satelliteLabel = stringResource(R.string.recording_satellite_required)
        val dateLabel = stringResource(R.string.recording_date_required)
        val timeOnLabel = stringResource(R.string.recording_time_on)
        val timeOffLabel = stringResource(R.string.recording_time_off)
        val callsignLabel = stringResource(R.string.recording_callsign)
        val frequencyLabel = stringResource(R.string.recording_frequency_mhz)
        val modeLabel = stringResource(R.string.recording_mode_label)
        val rstSentLabel = stringResource(R.string.recording_rst_sent)
        val rstReceivedLabel = stringResource(R.string.recording_rst_received)
        val maxElevationLabel = stringResource(R.string.recording_max_elevation)
        val resultLabel = stringResource(R.string.recording_result)
        val gridLocatorLabel = stringResource(R.string.recording_grid_locator)
        val notesLabel = stringResource(R.string.recording_notes)

        val contactLogHeader = stringResource(R.string.recording_contact_log_header)
        val contactLogSatellite = stringResource(R.string.recording_contact_log_satellite)
        val contactLogDate = stringResource(R.string.recording_contact_log_date)
        val contactLogTimeOn = stringResource(R.string.recording_contact_log_time_on)
        val contactLogTimeOff = stringResource(R.string.recording_contact_log_time_off)
        val contactLogCallsign = stringResource(R.string.recording_contact_log_callsign)
        val contactLogFrequency = stringResource(R.string.recording_contact_log_frequency)
        val contactLogMode = stringResource(R.string.recording_contact_log_mode)
        val contactLogRstSent = stringResource(R.string.recording_contact_log_rst_sent)
        val contactLogRstReceived = stringResource(R.string.recording_contact_log_rst_received)
        val contactLogMaxElevation = stringResource(R.string.recording_contact_log_max_elevation)
        val contactLogResult = stringResource(R.string.recording_contact_log_result)
        val contactLogGridLocator = stringResource(R.string.recording_contact_log_grid_locator)
        val contactLogNotes = stringResource(R.string.recording_contact_log_notes)
        val unknownError = stringResource(R.string.recording_unknown_error)

        AlertDialog(
            onDismissRequest = { showContactLogDialog = false },
            title = { Text(stringResource(R.string.recording_contact_log_title)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (aiEmpty) {
                        Text(
                            text = stringResource(R.string.recording_contact_log_ai_empty_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = themeWarning(),
                            modifier = Modifier.padding(bottom = SpacingTokens.sm),
                        )
                    }
                    OutlinedTextField(value = log.satName, onValueChange = { editDraft = log.copy(satName = it) }, label = { Text(satelliteLabel) }, isError = satMissing, supportingText = if (satMissing) { { Text(requiredLabel) } } else null, modifier = Modifier.fillMaxWidth().padding(SpacingTokens.xs))
                    OutlinedTextField(value = log.date, onValueChange = { editDraft = log.copy(date = it) }, label = { Text(dateLabel) }, isError = dateMissing, supportingText = if (dateMissing) { { Text(requiredLabel) } } else null, modifier = Modifier.fillMaxWidth().padding(SpacingTokens.xs))
                    OutlinedTextField(value = log.timeOn, onValueChange = { editDraft = log.copy(timeOn = it) }, label = { Text(timeOnLabel) }, isError = timeOnMissing, supportingText = if (timeOnMissing) { { Text(requiredLabel) } } else null, modifier = Modifier.fillMaxWidth().padding(SpacingTokens.xs))
                    OutlinedTextField(value = log.timeOff, onValueChange = { editDraft = log.copy(timeOff = it) }, label = { Text(timeOffLabel) }, modifier = Modifier.fillMaxWidth().padding(SpacingTokens.xs))
                    OutlinedTextField(value = log.callsign, onValueChange = { editDraft = log.copy(callsign = it) }, label = { Text(callsignLabel) }, modifier = Modifier.fillMaxWidth().padding(SpacingTokens.xs))
                    OutlinedTextField(value = log.frequency, onValueChange = { editDraft = log.copy(frequency = it) }, label = { Text(frequencyLabel) }, modifier = Modifier.fillMaxWidth().padding(SpacingTokens.xs))
                    OutlinedTextField(value = log.mode, onValueChange = { editDraft = log.copy(mode = it) }, label = { Text(modeLabel) }, modifier = Modifier.fillMaxWidth().padding(SpacingTokens.xs))
                    OutlinedTextField(value = log.rstSent, onValueChange = { editDraft = log.copy(rstSent = it) }, label = { Text(rstSentLabel) }, modifier = Modifier.fillMaxWidth().padding(SpacingTokens.xs))
                    OutlinedTextField(value = log.rstReceived, onValueChange = { editDraft = log.copy(rstReceived = it) }, label = { Text(rstReceivedLabel) }, modifier = Modifier.fillMaxWidth().padding(SpacingTokens.xs))
                    OutlinedTextField(value = log.maxElevation, onValueChange = { editDraft = log.copy(maxElevation = it) }, label = { Text(maxElevationLabel) }, modifier = Modifier.fillMaxWidth().padding(SpacingTokens.xs))
                    OutlinedTextField(value = log.result, onValueChange = { editDraft = log.copy(result = it) }, label = { Text(resultLabel) }, modifier = Modifier.fillMaxWidth().padding(SpacingTokens.xs))
                    OutlinedTextField(value = log.gridLocator, onValueChange = { editDraft = log.copy(gridLocator = it) }, label = { Text(gridLocatorLabel) }, modifier = Modifier.fillMaxWidth().padding(SpacingTokens.xs))
                    OutlinedTextField(value = log.notes, onValueChange = { editDraft = log.copy(notes = it) }, label = { Text(notesLabel) }, modifier = Modifier.fillMaxWidth().padding(SpacingTokens.xs))
                    OutlinedTextField(value = log.noradId, onValueChange = { editDraft = log.copy(noradId = it) }, label = { Text(stringResource(R.string.recording_norad_id)) }, modifier = Modifier.fillMaxWidth().padding(SpacingTokens.xs))
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
                    OutlinedButton(
                        onClick = {
                            val stationMissing = vm.apiSettings.getStationCallsign().isBlank() ||
                                vm.apiSettings.getMyGridsquare().isBlank()
                            if (stationMissing) {
                                showStationInfoMissingDialog = true
                                return@OutlinedButton
                            }
                            scope.launch {
                                runCatching {
                                    val file = vm.exportContactLog(log)
                                    top.hsyscn.opedrgent.ui.shareFile(
                                        context, vm.getPackageNameForShare(context), file, "text/plain",
                                    )
                                }.fold(
                                    onSuccess = {
                                        showContactLogDialog = false
                                        snackbar.showSnackbar(context.getString(R.string.recording_adif_exported))
                                    },
                                    onFailure = { e ->
                                        DebugLog.e("RecordingTab", "ADIF 导出失败: ${e.message}", e)
                                        snackbar.showSnackbar(context.getString(R.string.recording_adif_export_failed, e.message ?: unknownError))
                                    },
                                )
                            }
                        },
                        enabled = !requiredMissing,
                    ) {
                        Text(stringResource(R.string.recording_export_adif))
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                runCatching {
                                    val file = vm.exportContactLogCsv(log)
                                    top.hsyscn.opedrgent.ui.shareFile(
                                        context, vm.getPackageNameForShare(context), file, "text/csv",
                                    )
                                }.fold(
                                    onSuccess = {
                                        showContactLogDialog = false
                                        snackbar.showSnackbar(context.getString(R.string.recording_csv_exported))
                                    },
                                    onFailure = { e ->
                                        DebugLog.e("RecordingTab", "CSV 导出失败: ${e.message}", e)
                                        snackbar.showSnackbar(context.getString(R.string.recording_csv_export_failed, e.message ?: unknownError))
                                    },
                                )
                            }
                        },
                        enabled = !requiredMissing,
                    ) {
                        Text(stringResource(R.string.recording_export_csv))
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                runCatching {
                                    vm.createNoteFromText(
                                        title = context.getString(R.string.recording_contact_log_note_title, log.satName, log.date),
                                        content = buildString {
                                            appendLine(contactLogHeader)
                                            if (log.satName.isNotBlank()) appendLine(contactLogSatellite.format(log.satName))
                                            if (log.date.isNotBlank()) appendLine(contactLogDate.format(log.date))
                                            if (log.timeOn.isNotBlank()) appendLine(contactLogTimeOn.format(log.timeOn))
                                            if (log.timeOff.isNotBlank()) appendLine(contactLogTimeOff.format(log.timeOff))
                                            if (log.callsign.isNotBlank()) appendLine(contactLogCallsign.format(log.callsign))
                                            if (log.frequency.isNotBlank()) appendLine(contactLogFrequency.format(log.frequency))
                                            if (log.mode.isNotBlank()) appendLine(contactLogMode.format(log.mode))
                                            if (log.rstSent.isNotBlank()) appendLine(contactLogRstSent.format(log.rstSent))
                                            if (log.rstReceived.isNotBlank()) appendLine(contactLogRstReceived.format(log.rstReceived))
                                            if (log.maxElevation.isNotBlank()) appendLine(contactLogMaxElevation.format(log.maxElevation))
                                            if (log.result.isNotBlank()) appendLine(contactLogResult.format(top.hsyscn.opedrgent.model.HamContactLog.normalizeResult(log.result)))
                                            if (log.gridLocator.isNotBlank()) appendLine(contactLogGridLocator.format(log.gridLocator))
                                            if (log.notes.isNotBlank()) appendLine(contactLogNotes.format(log.notes))
                                            if (log.noradId.isNotBlank()) appendLine("- NORAD ID: ${log.noradId}")
                                        }.trimEnd(),
                                    )
                                }.fold(
                                    onSuccess = {
                                        showContactLogDialog = false
                                        snackbar.showSnackbar(context.getString(R.string.msg_saved_as_note))
                                    },
                                    onFailure = { e ->
                                        DebugLog.e("RecordingTab", "保存笔记失败: ${e.message}", e)
                                        snackbar.showSnackbar(context.getString(R.string.recording_save_failed, e.message ?: unknownError))
                                    },
                                )
                            }
                        },
                        enabled = !requiredMissing,
                    ) {
                        Text(stringResource(R.string.action_save))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showContactLogDialog = false }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }

    // 导出 ADIF 前缺少台站信息时的提醒弹窗
    if (showStationInfoMissingDialog) {
        AlertDialog(
            onDismissRequest = { showStationInfoMissingDialog = false },
            title = { Text(stringResource(R.string.recording_station_info_missing_title)) },
            text = { Text(stringResource(R.string.recording_station_info_missing_message)) },
            confirmButton = {
                TextButton(onClick = { showStationInfoMissingDialog = false }) {
                    Text(stringResource(R.string.recording_station_info_missing_confirm))
                }
            },
        )
    }
}

@Composable
private fun LabeledField(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = SpacingTokens.xxs),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = themeTextGrey())
        Text(value, style = MaterialTheme.typography.bodyMedium, color = themeTextDark())
    }
}

// ==================== 子 Composable ====================

@Composable
private fun IdleModeSelection(
    vm: MainViewModel,
    selectedMode: RecordingMode,
    onModeSelected: (RecordingMode) -> Unit,
    onStartRecording: () -> Unit,
    onImportAudioVideo: () -> Unit,
    isSttEnabled: Boolean,
    onSttDisabled: () -> Unit,
) {
    val landscape = isLandscape()
    val isMediumOrExpanded = isAtLeastMediumWidth()

    val titleSection: @Composable () -> Unit = {
        Text(
            text = stringResource(R.string.recording_select_mode),
            style = MaterialTheme.typography.headlineLarge,
            color = themeTextDark(),
        )
        Spacer(Modifier.height(SpacingTokens.xs))
        Text(
            text = stringResource(R.string.recording_select_mode_desc),
            style = MaterialTheme.typography.bodyLarge,
            color = themeTextGrey(),
        )
    }
    val modeCardsSection: @Composable () -> Unit = {
        if (landscape && isMediumOrExpanded) {
            // 横屏大屏：卡片垂直堆叠，避免两个大方块并排成“鼻孔”。
            Column(
                modifier = Modifier.widthIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(SpacingTokens.md),
            ) {
                ModeCard(
                    vm = vm,
                    mode = RecordingMode.RECORDING,
                    isSelected = selectedMode == RecordingMode.RECORDING,
                    onClick = { onModeSelected(RecordingMode.RECORDING) },
                    modifier = Modifier.fillMaxWidth(),
                )
                ModeCard(
                    vm = vm,
                    mode = RecordingMode.INTERNAL,
                    isSelected = selectedMode == RecordingMode.INTERNAL,
                    onClick = { onModeSelected(RecordingMode.INTERNAL) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.md, Alignment.CenterHorizontally),
            ) {
                ModeCard(
                    vm = vm,
                    mode = RecordingMode.RECORDING,
                    isSelected = selectedMode == RecordingMode.RECORDING,
                    onClick = { onModeSelected(RecordingMode.RECORDING) },
                    modifier = Modifier.weight(1f),
                )
                ModeCard(
                    vm = vm,
                    mode = RecordingMode.INTERNAL,
                    isSelected = selectedMode == RecordingMode.INTERNAL,
                    onClick = { onModeSelected(RecordingMode.INTERNAL) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
    val controlsSection: @Composable () -> Unit = {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = run { val h = vm.getRecordingMaxHours(selectedMode.name); if (h == 0) stringResource(R.string.recording_max_unlimited) else stringResource(R.string.recording_max_hours, h) },
                style = MaterialTheme.typography.bodyMedium,
                color = themeTextGrey(),
                modifier = Modifier.padding(bottom = SpacingTokens.md),
            )
            Box(
                modifier = Modifier
                    .size(SizeTokens.emptyStateIcon)
                    .clip(CircleShape)
                    .background(themeError())
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.cd_start_recording)) { onStartRecording() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = stringResource(R.string.cd_start_recording),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(SizeTokens.sectionIcon),
                )
            }
            Spacer(Modifier.height(SpacingTokens.sm))
            Text(
                text = stringResource(R.string.recording_start_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = themeTextGrey(),
                modifier = Modifier.padding(bottom = SpacingTokens.sm),
            )

            OutlinedButton(
                onClick = {
                    if (isSttEnabled) {
                        onImportAudioVideo()
                    } else {
                        onSttDisabled()
                    }
                },
                modifier = Modifier.padding(bottom = SpacingTokens.xxl),
            ) {
                Icon(Icons.AutoMirrored.Filled.NoteAdd, contentDescription = null, modifier = Modifier.size(SizeTokens.iconMd))
                Spacer(Modifier.width(SpacingTokens.sm))
                Text(stringResource(R.string.recording_import_audio_video_title), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }

    if (landscape && isMediumOrExpanded) {
        // 横屏大屏：整体按居中单列排布，卡片垂直堆叠，彻底消灭左右大方块。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SpacingTokens.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.xl, Alignment.CenterVertically),
        ) {
            titleSection()
            modeCardsSection()
            controlsSection()
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SpacingTokens.xl),
        ) {
            Spacer(Modifier.height(SpacingTokens.xl))
            titleSection()
            Spacer(Modifier.height(SpacingTokens.xl))
            modeCardsSection()
            Spacer(Modifier.weight(1f))
            controlsSection()
        }
    }
}

@Composable
private fun ModeCard(
    vm: MainViewModel,
    mode: RecordingMode,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (isSelected) themeError() else Color.Transparent
    val bgColor = if (isSelected) themeCoralLight() else MaterialTheme.colorScheme.surface
    val landscape = isLandscape()
    Card(
        modifier = modifier
            .then(
                if (landscape) {
                    Modifier.heightIn(min = 120.dp, max = 148.dp)
                } else {
                    Modifier.aspectRatio(1.25f)
                },
            )
            .border(SizeTokens.borderWidth, borderColor, ShapeTokens.largeShape)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.action_select), onClick = onClick),
        shape = ShapeTokens.largeShape,
        colors = CardDefaults.cardColors(containerColor = bgColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) ElevationTokens.md else ElevationTokens.sm),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(SpacingTokens.lg),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Icon(
                imageVector = mode.icon,
                contentDescription = null, // 装饰性图标，文本已说明
                tint = if (isSelected) themeError() else themeTextGrey(),
                modifier = Modifier.size(SizeTokens.iconXl),
            )
            Column {
                Text(
                    text = mode.label(),
                    style = MaterialTheme.typography.titleMedium,
                    color = themeTextDark(),
                )
                Spacer(Modifier.height(SpacingTokens.xxs))
                Text(
                    text = run { val h = vm.getRecordingMaxHours(mode.name); if (h == 0) stringResource(R.string.recording_unlimited) else stringResource(R.string.recording_hours_format, h) },
                    style = MaterialTheme.typography.bodySmall,
                    color = themeTextGrey(),
                )
            }
        }
    }
}

@Composable
private fun RecordingScreen(
    vm: MainViewModel,
    mode: RecordingMode,
    state: RecordingState,
    elapsedSeconds: Int,
    streamingText: String,
    amplitude: Float,
    scrollState: androidx.compose.foundation.ScrollState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDone: () -> Unit,
    onTakePhoto: () -> Unit,
    onCancel: () -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    val context = LocalContext.current
    var bookmarks by remember { mutableStateOf<List<Long>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val timeText = remember(elapsedSeconds) {
        val m = elapsedSeconds / 60
        val s = elapsedSeconds % 60
        String.format("%02d:%02d", m, s)
    }

    val landscape = isLandscape()
    val isMediumOrExpanded = isAtLeastMediumWidth()

    val modeInfoBar: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = SpacingTokens.lg, bottom = SpacingTokens.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = mode.icon,
                    contentDescription = null, // 装饰性图标，文本已说明
                    tint = themeError(),
                    modifier = Modifier.size(SizeTokens.iconMd),
                )
                Spacer(Modifier.width(SpacingTokens.sm))
                Text(
                    text = mode.label(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = themeTextDark(),
                )
            }
            Text(
                text = run { val h = vm.getRecordingMaxHours(mode.name); if (h == 0) stringResource(R.string.recording_max_label, stringResource(R.string.recording_unlimited)) else stringResource(R.string.recording_max_label, stringResource(R.string.recording_hours_format, h)) },
                style = MaterialTheme.typography.bodySmall,
                color = themeTextGrey(),
            )
        }
    }

    val transcriptCard: @Composable (Modifier) -> Unit = { cardModifier ->
        Card(
            modifier = cardModifier,
            shape = ShapeTokens.largeShape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = ElevationTokens.md),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(SpacingTokens.xl),
            ) {
                if (streamingText.isBlank()) {
                    val hintText = if (mode == RecordingMode.INTERNAL) {
                        stringResource(R.string.recording_system_audio_hint)
                    } else {
                        stringResource(R.string.recording_listening_hint)
                    }
                    Text(
                        text = hintText,
                        style = MaterialTheme.typography.titleLarge,
                        color = themeTextGrey().copy(alpha = 0.5f),
                        lineHeight = (18 * 1.8).sp,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState),
                    ) {
                        Text(
                            text = if (streamingText.length > 5000) streamingText.takeLast(5000) else streamingText,
                            style = MaterialTheme.typography.titleLarge,
                            color = themeTextDark(),
                            lineHeight = (18 * 1.8).sp,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }

    val timerAndWave: @Composable () -> Unit = {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = timeText,
                style = MaterialTheme.typography.displayMedium,
                color = themeTextDark(),
            )
            Spacer(Modifier.height(SpacingTokens.sm))
            WaveformBars(amplitude = amplitude, isRecording = state == RecordingState.RECORDING, color = themeError())
        }
    }

    val controlButtons: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 取消
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .size(SizeTokens.sectionIcon)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow, CircleShape),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.action_cancel),
                        tint = themeTextGrey(),
                        modifier = Modifier.size(SizeTokens.iconXl),
                    )
                }
                Spacer(Modifier.height(SpacingTokens.xs))
                Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.bodySmall, color = themeTextGrey())
            }

            // 大圆录音/暂停按钮
            Box(
                modifier = Modifier
                    .size(SizeTokens.emptyStateIcon)
                    .clip(CircleShape)
                    .background(themeError())
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.action_pause)) {
                        if (state == RecordingState.RECORDING) onPause() else onResume()
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (state == RecordingState.RECORDING) {
                    Icon(
                        imageVector = Icons.Default.Pause,
                        contentDescription = stringResource(R.string.action_pause),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(SizeTokens.iconXl),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = stringResource(R.string.action_resume),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(SizeTokens.iconXl),
                    )
                }
            }

            // 完成
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(
                    onClick = onDone,
                    modifier = Modifier
                        .size(SizeTokens.sectionIcon)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow, CircleShape),
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource(R.string.action_done),
                        tint = themePrimary(),
                        modifier = Modifier.size(SizeTokens.iconXl),
                    )
                }
                Spacer(Modifier.height(SpacingTokens.xs))
                Text(stringResource(R.string.action_done), style = MaterialTheme.typography.bodySmall, color = themeTextGrey())
            }
        }
    }

    val toolRow: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = SpacingTokens.lg),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolIcon(icon = Icons.Default.PhotoCamera, label = stringResource(R.string.recording_photo_tool), onClick = onTakePhoto)
            ToolIcon(icon = Icons.Default.Bookmark, label = stringResource(R.string.recording_bookmark_tool), onClick = {
                bookmarks = bookmarks + elapsedSeconds.toLong()
                scope.launch {
                    val m = elapsedSeconds / 60
                    val s = elapsedSeconds % 60
                    snackbarHostState.showSnackbar(context.getString(R.string.recording_bookmark_format, "%d:%02d".format(m, s)))
                }
            })
            ToolIcon(icon = Icons.Default.Edit, label = stringResource(R.string.recording_quick_note_tool), onClick = {
                scope.launch {
                    snackbarHostState.showSnackbar(context.getString(R.string.msg_note_point_added))
                }
            })
        }
    }

    if (landscape && isMediumOrExpanded) {
        // 横屏大屏：左侧实时转录，右侧固定宽度控制面板，避免元素被横向拉伸。
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = SpacingTokens.lg),
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.xl, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                modeInfoBar()
                Spacer(Modifier.height(SpacingTokens.md))
                Box(modifier = Modifier.weight(1f)) {
                    transcriptCard(Modifier.fillMaxSize())
                }
            }
            Column(
                modifier = Modifier
                    .widthIn(min = 260.dp, max = 340.dp)
                    .fillMaxHeight()
                    .padding(vertical = SpacingTokens.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceEvenly,
            ) {
                timerAndWave()
                controlButtons()
                toolRow()
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = SpacingTokens.lg),
        ) {
            modeInfoBar()
            transcriptCard(Modifier.weight(1f).fillMaxWidth())
            Spacer(Modifier.height(SpacingTokens.lg))
            timerAndWave()
            Spacer(Modifier.height(SpacingTokens.lg))
            controlButtons()
            Spacer(Modifier.height(SpacingTokens.md))
            toolRow()
        }
    }
}

@Composable
private fun ToolIcon(icon: ImageVector, label: String, onClick: (() -> Unit)? = null) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = label, onClick = onClick) else Modifier,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = themeTextGrey(),
            modifier = Modifier.size(SizeTokens.iconXl),
        )
        Spacer(Modifier.height(SpacingTokens.xxs))
        Text(label, style = MaterialTheme.typography.labelSmall, color = themeTextGrey())
    }
}

@Composable
private fun WaveformBars(
    amplitude: Float,
    isRecording: Boolean,
    color: Color,
    barWidth: androidx.compose.ui.unit.Dp = 4.dp,
    barSpacing: androidx.compose.ui.unit.Dp = 3.dp,
    maxHeight: androidx.compose.ui.unit.Dp = 32.dp,
) {
    // 自管理动画状态，不触发父组件重组
    var bars by remember { mutableStateOf(List(24) { 0.2f }) }
    LaunchedEffect(isRecording) {
        while (isRecording) {
            bars = List(24) { index ->
                val base = if (amplitude > 0.05f) amplitude else 0.05f
                val wave = kotlin.math.sin((System.currentTimeMillis() / 250.0) + index * 0.6).toFloat() * 0.4f
                val noise = kotlin.random.Random.nextFloat() * 0.2f
                ((base * 0.8f + 0.2f) + wave * base + noise * base).coerceIn(0.05f, 1f)
            }
            delay(80)
        }
    }
    Row(
        modifier = Modifier.height(maxHeight),
        horizontalArrangement = Arrangement.spacedBy(barSpacing, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        bars.forEach { heightFraction ->
            Box(
                modifier = Modifier
                    .width(barWidth)
                    .height(maxHeight * heightFraction)
                    .clip(ShapeTokens.extraSmallShape)
                    .background(color.copy(alpha = 0.8f)),
            )
        }
    }
}

@Composable
private fun SkeletonLoadingScreen() {
    val shimmerColors = listOf(
        themeTextGrey().copy(alpha = 0.3f),
        themeTextGrey().copy(alpha = 0.7f),
        themeTextGrey().copy(alpha = 0.3f),
    )
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(tween(1200, easing = androidx.compose.animation.core.LinearEasing)),
        label = "shimmer",
    )
    val brush = Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(translateAnim - 200f, 0f),
        end = Offset(translateAnim, 0f),
    )

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val barWidths = listOf(0.75f, 0.9f, 0.6f, 0.85f, 0.7f, 0.5f)
        barWidths.forEach { widthFraction ->
            Box(
                modifier = Modifier
                    .fillMaxWidth(widthFraction)
                    .padding(horizontal = SpacingTokens.xl, vertical = SpacingTokens.md)
                    .height(14.dp)
                    .background(brush, ShapeTokens.smallShape),
            )
        }
        Spacer(Modifier.height(SpacingTokens.xl))
        Text(
            text = stringResource(R.string.recording_generating_note),
            style = MaterialTheme.typography.bodyLarge,
            color = themeTextGrey(),
        )
    }
}

@Composable
private fun TranscriptResultCard(
    result: MeetingTranscriptResult,
    autoSaved: Boolean,
    selectedMode: RecordingMode,
    capturedPhotos: List<CapturedPhoto>,
    identifiedSpeakerName: String? = null,
    hamModeEnabled: Boolean = false,
    onCopy: () -> Unit,
    onNavigateToNotes: () -> Unit,
    onSave: () -> Unit,
    onAiSummary: () -> Unit,
    onConvertToContactLog: () -> Unit,
    onDiscardAutoSave: () -> Unit,
    onContinueRecording: () -> Unit,
) {
    Card(
        shape = ShapeTokens.largeShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = ElevationTokens.md),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SpacingTokens.xl),
    ) {
        Column(modifier = Modifier.padding(SpacingTokens.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (autoSaved) Icons.Default.CheckCircle else Icons.Default.CheckCircle,
                    contentDescription = null, // 装饰性图标，文本已说明
                    tint = if (autoSaved) themeSuccess() else themePrimary(),
                    modifier = Modifier.size(SizeTokens.iconLg),
                )
                Spacer(Modifier.width(SpacingTokens.sm))
                Text(
                    text = if (autoSaved) stringResource(R.string.recording_auto_saved) else stringResource(R.string.recording_transcript_result),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (autoSaved) themeSuccess() else themeTextDark(),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.recording_segments_count, result.segments.size),
                    color = themeTextGrey(),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // 声纹识别结果
            if (identifiedSpeakerName != null) {
                Spacer(Modifier.height(SpacingTokens.sm))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(themePrimary().copy(alpha = 0.06f), ShapeTokens.smallShape)
                        .padding(horizontal = SpacingTokens.md, vertical = SpacingTokens.xs),
                ) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null, // 装饰性图标，文本已说明
                        tint = themePrimary(),
                        modifier = Modifier.size(SizeTokens.iconSm),
                    )
                    Spacer(Modifier.width(SpacingTokens.sm))
                    Text(
                        text = stringResource(R.string.recording_speaker, identifiedSpeakerName),
                        style = MaterialTheme.typography.labelLarge,
                        color = themePrimary(),
                    )
                }
            }

            if (autoSaved) {
                Spacer(Modifier.height(SpacingTokens.sm))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = SpacingTokens.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null, // 装饰性图标，文本已说明
                        tint = themeSuccess(),
                        modifier = Modifier.size(SizeTokens.iconXs),
                    )
                    Spacer(Modifier.width(SpacingTokens.sm))
                    Text(
                        text = stringResource(R.string.recording_auto_saved_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = themeSuccess(),
                    )
                }
            }

            Spacer(Modifier.height(SpacingTokens.md))
            HorizontalDivider()
            Spacer(Modifier.height(SpacingTokens.md))

            // 智能总结 Tab（有总结数据时显示）
            val summary = result.smartSummary
            if (summary != null) {
                var showSummaryTab by rememberSaveable { mutableStateOf(true) }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = SpacingTokens.md),
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(topStart = ShapeTokens.small, topEnd = ShapeTokens.small),
                        color = if (showSummaryTab) themePrimary().copy(alpha = 0.1f) else Color.Transparent,
                        onClick = { showSummaryTab = true },
                    ) {
                        Text(
                            stringResource(R.string.recording_smart_summary),
                            style = if (showSummaryTab) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                            color = if (showSummaryTab) themePrimary() else themeTextGrey(),
                            modifier = Modifier.padding(horizontal = SpacingTokens.lg, vertical = SpacingTokens.md),
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(topStart = ShapeTokens.small, topEnd = ShapeTokens.small),
                        color = if (!showSummaryTab) themePrimary().copy(alpha = 0.1f) else Color.Transparent,
                        onClick = { showSummaryTab = false },
                    ) {
                        Text(
                            stringResource(R.string.recording_original_text),
                            style = if (!showSummaryTab) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                            color = if (!showSummaryTab) themePrimary() else themeTextGrey(),
                            modifier = Modifier.padding(horizontal = SpacingTokens.lg, vertical = SpacingTokens.md),
                        )
                    }
                }

                if (showSummaryTab) {
                    SmartSummaryContent(summary = summary)
                } else {
                    Text(
                        text = result.fullText.ifBlank { stringResource(R.string.recording_no_result) },
                        style = MaterialTheme.typography.bodyLarge,
                        color = themeTextDark(),

                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = SizeTokens.citationListMaxHeight)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            } else {
                Text(
                    text = result.fullText.ifBlank { stringResource(R.string.recording_no_result) },
                    style = MaterialTheme.typography.bodyLarge,
                    color = themeTextDark(),

                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = SizeTokens.citationListMaxHeight)
                        .verticalScroll(rememberScrollState()),
                )
            }

            if (capturedPhotos.isNotEmpty()) {
                Spacer(Modifier.height(SpacingTokens.md))
                CapturedPhotosRow(photos = capturedPhotos)
            }

            Spacer(Modifier.height(SpacingTokens.md))
            HorizontalDivider()
            Spacer(Modifier.height(SpacingTokens.md))

            Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.md)) {
                OutlinedButton(
                    onClick = onCopy,
                    shape = ShapeTokens.mediumShape,
                    modifier = Modifier.weight(1f).height(SizeTokens.searchBarHeight),
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, /* 装饰性图标，文本已说明 */ modifier = Modifier.size(SizeTokens.iconMd))
                    Spacer(Modifier.width(SpacingTokens.sm))
                    Text(stringResource(R.string.recording_copy), style = MaterialTheme.typography.labelLarge)
                }

                if (autoSaved) {
                    Button(
                        onClick = onNavigateToNotes,
                        shape = ShapeTokens.mediumShape,
                        colors = ButtonDefaults.buttonColors(containerColor = themeSuccess()),
                        modifier = Modifier.weight(1f).height(SizeTokens.searchBarHeight),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.NoteAdd, contentDescription = null, /* 装饰性图标，文本已说明 */ modifier = Modifier.size(SizeTokens.iconMd))
                        Spacer(Modifier.width(SpacingTokens.sm))
                        Text(stringResource(R.string.recording_edit_btn), style = MaterialTheme.typography.labelLarge)
                    }
                } else {
                    Button(
                        onClick = onSave,
                        shape = ShapeTokens.mediumShape,
                        colors = ButtonDefaults.buttonColors(containerColor = themePrimary()),
                        modifier = Modifier.weight(1f).height(SizeTokens.searchBarHeight),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.NoteAdd, contentDescription = null, /* 装饰性图标，文本已说明 */ modifier = Modifier.size(SizeTokens.iconMd))
                        Spacer(Modifier.width(SpacingTokens.sm))
                        Text(stringResource(R.string.recording_save_note), style = MaterialTheme.typography.labelLarge)
                    }
                }
                Button(
                    onClick = onAiSummary,
                    shape = ShapeTokens.mediumShape,
                    colors = ButtonDefaults.buttonColors(containerColor = themePrimary()),
                    modifier = Modifier.weight(1f).height(SizeTokens.searchBarHeight),
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, /* 装饰性图标，文本已说明 */ modifier = Modifier.size(SizeTokens.iconMd))
                    Spacer(Modifier.width(SpacingTokens.sm))
                    Text(stringResource(R.string.recording_ai_summary), style = MaterialTheme.typography.labelLarge)
                }
            }

            // ★ Ham 模式：通联日志按钮
            if (hamModeEnabled) {
                Spacer(Modifier.height(SpacingTokens.md))
                Button(
                    onClick = onConvertToContactLog,
                    shape = ShapeTokens.mediumShape,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                    modifier = Modifier.fillMaxWidth().height(SizeTokens.searchBarHeight),
                ) {
                    Icon(Icons.Default.Satellite, contentDescription = null, modifier = Modifier.size(SizeTokens.iconMd))
                    Spacer(Modifier.width(SpacingTokens.sm))
                    Text(stringResource(R.string.recording_convert_to_contact_log), style = MaterialTheme.typography.labelLarge)
                }
            }

            if (autoSaved) {
                Spacer(Modifier.height(SpacingTokens.md))
                Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.md)) {
                    OutlinedButton(
                        onClick = onDiscardAutoSave,
                        shape = ShapeTokens.mediumShape,
                        modifier = Modifier.weight(1f).height(SizeTokens.searchBarHeight),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text(stringResource(R.string.recording_undo_save), style = MaterialTheme.typography.labelLarge)
                    }
                    OutlinedButton(
                        onClick = onContinueRecording,
                        shape = ShapeTokens.mediumShape,
                        modifier = Modifier.weight(1f).height(SizeTokens.searchBarHeight),
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = null, /* 装饰性图标，文本已说明 */ modifier = Modifier.size(SizeTokens.iconMd))
                        Spacer(Modifier.width(SpacingTokens.sm))
                        Text(stringResource(R.string.recording_continue), style = MaterialTheme.typography.labelLarge)
                    }
                }
            } else {
                Spacer(Modifier.height(SpacingTokens.md))
                OutlinedButton(
                    onClick = onContinueRecording,
                    shape = ShapeTokens.mediumShape,
                    modifier = Modifier.fillMaxWidth().height(SizeTokens.searchBarHeight),
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null, /* 装饰性图标，文本已说明 */ modifier = Modifier.size(SizeTokens.iconMd))
                    Spacer(Modifier.width(SpacingTokens.sm))
                    Text(stringResource(R.string.recording_continue), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/**
 * 智能总结内容展示 — 对齐opedrgent「智能总结」Tab 的 5 层结构。
 */
@Composable
private fun SmartSummaryContent(summary: top.hsyscn.opedrgent.stt.SmartSummary) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.lg),
    ) {
        // 1. 录音信息
        SummaryMetaCard(meta = summary.metaInfo)

        // 2. 录音总结
        if (summary.summarySections.isNotEmpty()) {
            SummarySectionsCard(sections = summary.summarySections)
        }

        // 3. 章节概要（带时间戳）
        if (summary.chapters.isNotEmpty()) {
            ChaptersCard(chapters = summary.chapters)
        }

        // 4. 金句精选
        if (summary.quotes.isNotEmpty()) {
            QuotesCard(quotes = summary.quotes)
        }

        // 5. 待办事项
        if (summary.actionItems.isNotEmpty()) {
            ActionItemsCard(items = summary.actionItems)
        }
    }
}

@Composable
private fun SummaryMetaCard(meta: top.hsyscn.opedrgent.stt.SmartSummary.MetaInfo) {
    val durationLabel = stringResource(R.string.recording_duration_label)
    val participantCountFormat = stringResource(R.string.recording_participant_count)
    Surface(
        shape = ShapeTokens.smallShape,
        color = themePrimary().copy(alpha = 0.06f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = SpacingTokens.md, vertical = SpacingTokens.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Info, contentDescription = null, /* 装饰性图标，文本已说明 */ tint = themePrimary(), modifier = Modifier.size(SizeTokens.iconSm))
            Spacer(Modifier.width(SpacingTokens.sm))
            Text(
                text = buildString {
                    if (meta.duration.isNotEmpty()) append(durationLabel.format(meta.duration))
                    if (meta.participantCount > 0) append("  |  ").append(participantCountFormat.format(meta.participantCount))
                    if (meta.contentType.isNotEmpty()) append("  |  ${meta.contentType}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = themeTextDark(),
            )
        }
    }
}

@Composable
private fun SummarySectionsCard(sections: List<top.hsyscn.opedrgent.stt.SmartSummary.SummarySection>) {
    Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.md), modifier = Modifier.fillMaxWidth()) {
        sections.forEach { section ->
            Surface(
                shape = ShapeTokens.smallShape,
                color = themeSurfaceLight(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(SpacingTokens.md)) {
                    Text(
                        text = section.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = themeTextDark(),
                    )
                    Spacer(Modifier.height(SpacingTokens.sm))
                    section.content.forEach { para ->
                        Text(
                            text = para,
                            style = MaterialTheme.typography.bodyMedium,
                            color = themeTextDark(),
                            
                            modifier = Modifier.padding(bottom = SpacingTokens.xs),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChaptersCard(chapters: List<top.hsyscn.opedrgent.stt.SmartSummary.ChapterItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm), modifier = Modifier.fillMaxWidth()) {
        chapters.forEachIndexed { index, chapter ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Surface(
                    shape = CircleShape,
                    color = themePrimary(),
                    modifier = Modifier
                        .size(SizeTokens.iconXl)
                        .align(Alignment.Top),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Text("${index + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
                Spacer(Modifier.width(SpacingTokens.md))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = chapter.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = themeTextDark(),
                        )
                        Spacer(Modifier.width(SpacingTokens.sm))
                        Surface(shape = ShapeTokens.extraSmallShape, color = themePrimary().copy(alpha = 0.1f)) {
                            Text(
                                chapter.timestampFormatted,
                                style = MaterialTheme.typography.labelSmall,
                                color = themePrimary(),
                                modifier = Modifier.padding(horizontal = SpacingTokens.xs, vertical = SpacingTokens.xxs),
                            )
                        }
                    }
                    Text(
                        text = chapter.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = themeTextGrey(),
                        
                        modifier = Modifier.padding(top = SpacingTokens.xxs),
                    )
                }
            }
        }
    }
}

@Composable
private fun QuotesCard(quotes: List<top.hsyscn.opedrgent.stt.SmartSummary.QuoteItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm), modifier = Modifier.fillMaxWidth()) {
        quotes.forEach { quote ->
            Surface(
                shape = ShapeTokens.smallShape,
                color = themeQuoteBg(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(modifier = Modifier.padding(SpacingTokens.md)) {
                    Icon(
                        Icons.Default.FormatQuote,
                        contentDescription = null, // 装饰性图标，文本已说明
                        tint = themeWarning(),
                        modifier = Modifier.size(SizeTokens.iconMd).align(Alignment.Top),
                    )
                    Spacer(Modifier.width(SpacingTokens.sm))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = quote.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = themeTextDark(),
                            
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        )
                        Spacer(Modifier.height(SpacingTokens.xs))
                        Surface(shape = ShapeTokens.smallShape, color = themeWarning().copy(alpha = 0.12f)) {
                            Text(
                                quote.category,
                                style = MaterialTheme.typography.labelSmall,
                                color = themeWarning(),
                                modifier = Modifier.padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xs),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionItemsCard(items: List<top.hsyscn.opedrgent.stt.SmartSummary.ActionItem>) {
    Surface(
        shape = ShapeTokens.smallShape,
        color = themeActionItemBg(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(SpacingTokens.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.TaskAlt, contentDescription = null, /* 装饰性图标，文本已说明 */ tint = themePrimary(), modifier = Modifier.size(SizeTokens.iconSm))
                Spacer(Modifier.width(SpacingTokens.sm))
                Text(stringResource(R.string.recording_todo_items), style = MaterialTheme.typography.titleMedium, color = themeTextDark())
            }
            Spacer(Modifier.height(SpacingTokens.sm))
            items.forEach { item ->
                Row(
                    modifier = Modifier.padding(vertical = SpacingTokens.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = CircleShape,
                        color = themePrimary().copy(alpha = 0.15f),
                        modifier = Modifier.size(SizeTokens.iconXl),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                Icons.Default.Person,
                                contentDescription = null, // 装饰性图标，文本已说明
                                tint = themePrimary(),
                                modifier = Modifier.size(SizeTokens.iconXs),
                            )
                        }
                    }
                    Spacer(Modifier.width(SpacingTokens.sm))
                    Text(text = item.assignee, style = MaterialTheme.typography.labelSmall, color = themePrimary())
                    Spacer(Modifier.width(SpacingTokens.sm))
                    Text(text = item.task, style = MaterialTheme.typography.bodySmall, color = themeTextDark(), modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun CapturedPhotosRow(photos: List<CapturedPhoto>) {
    Column {
        Text(
            text = stringResource(R.string.recording_photos_taken, photos.size),
            style = MaterialTheme.typography.titleSmall,
            color = themeTextGrey(),
        )
        Spacer(Modifier.height(SpacingTokens.sm))
        Row(
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.md),
            modifier = Modifier.fillMaxWidth(),
        ) {
            photos.forEach { photo ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    photo.bitmap?.let { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = stringResource(R.string.recording_photo_content_desc),
                            modifier = Modifier
                                .size(SizeTokens.emptyStateIcon)
                                .clip(ShapeTokens.smallShape),
                        )
                    }
                    Spacer(Modifier.height(SpacingTokens.xs))
                    val totalSeconds = photo.recordingTimeMs / 1000
                    val minutes = totalSeconds / 60
                    val seconds = totalSeconds % 60
                    Text(
                        text = stringResource(R.string.recording_photo_taken_at, minutes, seconds),
                        style = MaterialTheme.typography.labelSmall,
                        color = themeTextGrey(),
                    )
                }
            }
        }
    }
}

private fun formatPhotosForNote(photos: List<CapturedPhoto>): String {
    if (photos.isEmpty()) return ""
    return photos.joinToString(separator = "\n", prefix = "\n\n") { photo ->
        val totalSeconds = photo.recordingTimeMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        "[Photo: ${photo.filePath} at %02d:%02d]".format(minutes, seconds)
    }
}

// ==================== 辅助函数 ====================

private fun ShortArray.toByteArray(): ByteArray {
    val bytes = ByteArray(size * 2)
    for (i in indices) {
        bytes[i * 2] = (this[i].toInt() and 0xFF).toByte()
        bytes[i * 2 + 1] = (this[i].toInt() shr 8 and 0xFF).toByte()
    }
    return bytes
}

private fun feedAudioToEngine(engine: top.hsyscn.opedrgent.stt.SpeechEngine?, samples: FloatArray) {
    try {
        when (engine) {
            is top.hsyscn.opedrgent.stt.SherpaOnnxEngine -> engine.feedAudioData(samples)
            is top.hsyscn.opedrgent.stt.MimoAsrEngine -> engine.feedAudioData(samples)
            else -> {}
        }
    } catch (_: Exception) {}
}

private fun pcmToWav(pcmFile: File, wavFile: File, sampleRate: Int, channels: Int, bitsPerSample: Int) {
    val byteRate = sampleRate * channels * bitsPerSample / 8
    val blockAlign = channels * bitsPerSample / 8
    val dataSizeLong = pcmFile.length()
    // WAV 格式 data chunk size 字段为 32 位，截断到 Int.MAX_VALUE
    val dataSize = dataSizeLong.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val totalSize = 44 + dataSize

    FileOutputStream(wavFile).use { fos ->
        fos.write("RIFF".toByteArray())
        fos.write(intToLittleEndian(totalSize - 8))
        fos.write("WAVE".toByteArray())
        fos.write("fmt ".toByteArray())
        fos.write(intToLittleEndian(16))
        fos.write(shortToLittleEndian(1)) // PCM
        fos.write(shortToLittleEndian(channels.toShort()))
        fos.write(intToLittleEndian(sampleRate))
        fos.write(intToLittleEndian(byteRate))
        fos.write(shortToLittleEndian(blockAlign.toShort()))
        fos.write(shortToLittleEndian(bitsPerSample.toShort()))
        fos.write("data".toByteArray())
        fos.write(intToLittleEndian(dataSize))

        pcmFile.inputStream().use { input ->
            input.copyTo(fos)
        }
    }
}

private fun intToLittleEndian(value: Int): ByteArray {
    return byteArrayOf(
        (value and 0xFF).toByte(),
        (value shr 8 and 0xFF).toByte(),
        (value shr 16 and 0xFF).toByte(),
        (value shr 24 and 0xFF).toByte(),
    )
}

private fun shortToLittleEndian(value: Short): ByteArray {
    return byteArrayOf(
        (value.toInt() and 0xFF).toByte(),
        (value.toInt() shr 8 and 0xFF).toByte(),
    )
}

private suspend fun transcribeWithAsrManager(
    context: Context,
    wavFile: File,
    asrManager: top.hsyscn.opedrgent.stt.AsrManager,
): MeetingTranscriptResult {
    return try {
        val result = asrManager.transcribeFile(wavFile.absolutePath)
        val error = if (result.text.isEmpty() && result.error != null) {
            DebugLog.w("RecordingTab", "转写为空且有错误: ${result.error}")
            context.getString(R.string.recording_transcription_failed, result.error)
        } else null

        MeetingTranscriptResult(
            segments = result.segments.map { seg ->
                MeetingSegment(
                    text = seg.text,
                    startTimeMs = seg.startTimeMs,
                    endTimeMs = seg.endTimeMs,
                    speakerLabel = "Speaker_0",
                )
            },
            fullText = result.text,
            durationMs = result.durationMs,
            hasDiarization = false,
            speakers = setOf("Speaker_0"),
            error = error,
        )
    } catch (e: Exception) {
        DebugLog.e("RecordingTab", "转录失败: ${e.message}", e)
        MeetingTranscriptResult(error = context.getString(R.string.recording_transcription_failed, e.message))
    }
}
