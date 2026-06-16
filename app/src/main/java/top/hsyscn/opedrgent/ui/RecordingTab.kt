package top.hsyscn.opedrgent.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjectionManager
import android.os.Build
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.note.NoteType
import top.hsyscn.opedrgent.stt.EngineType
import top.hsyscn.opedrgent.stt.MeetingSegment
import top.hsyscn.opedrgent.stt.MeetingTranscriptResult
import top.hsyscn.opedrgent.stt.StreamingRecognitionState
import top.hsyscn.opedrgent.stt.SystemAudioRecorder
import top.hsyscn.opedrgent.ui.components.AudioPlayer
import top.hsyscn.opedrgent.ui.components.RecordingState
import top.hsyscn.opedrgent.storage.NotificationHelper
import top.hsyscn.opedrgent.ui.theme.AccentBlue
import top.hsyscn.opedrgent.ui.theme.BgGray
import top.hsyscn.opedrgent.ui.theme.TextDark
import top.hsyscn.opedrgent.ui.theme.TextGrey
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File
import java.io.FileOutputStream
import java.util.Date
import java.util.Locale

private val CoralRed = Color(0xFFFF5A5A)
private val CoralLight = Color(0xFFFFEAEA)
private val SelectedBorder = CoralRed

enum class RecordingMode(val label: String, val maxHours: Int, val icon: ImageVector) {
    VOICE_MEMO("语音速记", 2, Icons.Default.Mic),
    MEETING("多人会议", 5, Icons.Default.Group),
    INTERNAL("手机内录", 5, Icons.Default.SettingsVoice),
    CLASSROOM("课堂录音", 5, Icons.Default.School),
}

data class CapturedPhoto(
    val filePath: String,
    val recordingTimeMs: Long,
    val bitmap: android.graphics.Bitmap? = null,
)

/**
 * 录音 Tab —— 得到大脑风格重新设计。
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
    val clipboard = LocalClipboardManager.current

    // 模式选择
    var recordingMode by remember { mutableStateOf(RecordingMode.VOICE_MEMO) }

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

    var pendingMediaProjection by remember { mutableStateOf<android.media.projection.MediaProjection?>(null) }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mediaProjection = mediaProjectionManager.getMediaProjection(result.resultCode, result.data!!)
            if (mediaProjection != null) {
                pendingMediaProjection = mediaProjection
            } else {
                scope.launch { snackbar.showSnackbar("无法获取屏幕录制权限") }
            }
        } else {
            scope.launch { snackbar.showSnackbar("未获得录制权限") }
        }
    }

    // 录音期间拍摄的照片
    var capturedPhotos by remember { mutableStateOf<List<CapturedPhoto>>(emptyList()) }
    val startTime = remember { mutableStateOf(System.currentTimeMillis()) }

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

    var recordingState by remember { mutableStateOf<RecordingState?>(null) }
    var elapsedSeconds by remember { mutableIntStateOf(0) }
    var amplitude by remember { mutableFloatStateOf(0f) }
    var transcriptResult by remember { mutableStateOf<MeetingTranscriptResult?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var savedToNote by remember { mutableStateOf(false) }
    var autoSaved by remember { mutableStateOf(false) }
    var autoSavedNoteId by remember { mutableStateOf(0L) }

    // 录音完成后用于回放的音频文件路径
    var playbackAudioUri by remember { mutableStateOf<String?>(null) }

    // 实时流式转录文本
    var streamingText by remember { mutableStateOf("") }
    var isStreamingActive by remember { mutableStateOf(false) }
    val transcriptScrollState = rememberScrollState()

    // 读取无感伙伴设置中的自动保存开关
    val autoSaveKey = androidx.datastore.preferences.core.booleanPreferencesKey("key_auto_save")
    val partnerPrefs = context.invisiblePartnerDataStore.data.collectAsState(initial = null).value
    val autoSaveEnabled = partnerPrefs?.get(autoSaveKey) ?: true

    val audioRecord = remember { mutableStateOf<AudioRecord?>(null) }
    val tempFilePath = remember { mutableStateOf<String?>(null) }
    var systemAudioRecorder by remember { mutableStateOf<SystemAudioRecorder?>(null) }

    // 波形动画条
    var waveformBars by remember { mutableStateOf(List(24) { 0.2f }) }
    LaunchedEffect(recordingState) {
        while (recordingState == RecordingState.RECORDING || recordingState == RecordingState.PAUSED) {
            waveformBars = List(24) { index ->
                val base = if (amplitude > 0.05f) amplitude else 0.05f
                val wave = kotlin.math.sin((System.currentTimeMillis() / 250.0) + index * 0.6).toFloat() * 0.4f
                val noise = kotlin.random.Random.nextFloat() * 0.2f
                ((base * 0.8f + 0.2f) + wave * base + noise * base).coerceIn(0.05f, 1f)
            }
            delay(80)
        }
    }

    // 实时转录文本自动滚动
    LaunchedEffect(streamingText) {
        if (streamingText.isNotEmpty()) {
            transcriptScrollState.animateScrollTo(transcriptScrollState.maxValue)
        }
    }

    // Start recording
    val startRecordingPipeline: (AudioRecord) -> Unit = { recorder ->
        recordingState = RecordingState.RECORDING
        elapsedSeconds = 0
        amplitude = 0f
        transcriptResult = null
        savedToNote = false
        autoSaved = false
        autoSavedNoteId = 0L
        playbackAudioUri = null
        streamingText = ""
        isStreamingActive = false
        capturedPhotos = emptyList()

        val tempFile = File(context.cacheDir, "recording_${System.currentTimeMillis()}.pcm")
        tempFilePath.value = tempFile.absolutePath
        audioRecord.value = recorder
        startTime.value = System.currentTimeMillis()

        // 启动流式识别（仅对支持 feedAudioData 的引擎）
        scope.launch {
            try {
                vm.asrManager.ensureInitialized()
                val engine = vm.asrManager.getCachedEngine()
                if (engine?.engineType == EngineType.ANDROID_SPEECH_RECOGNIZER) {
                    DebugLog.i("RecordingTab", "AndroidSpeechRecognizer 不支持 feedAudioData，跳过实时流式")
                } else {
                    isStreamingActive = true
                    vm.asrManager.startStreaming().collect { state ->
                        when (state) {
                            is StreamingRecognitionState.Recognizing -> streamingText = state.partialText
                            is StreamingRecognitionState.FinalResult -> streamingText = state.text
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

        // 录音读取协程
        scope.launch {
            withContext(Dispatchers.IO) {
                val bufferSize = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val buffer = ShortArray(bufferSize / 2)
                FileOutputStream(tempFile).use { fos ->
                    while (isActive && recordingState != RecordingState.DONE && recordingState != RecordingState.PROCESSING) {
                        if (recordingState == RecordingState.PAUSED) {
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
                            amplitude = (rms / Short.MAX_VALUE).coerceIn(0f, 1f)

                            if (isStreamingActive) {
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
                scope.launch { snackbar.showSnackbar("无法初始化录音设备") }
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
            scope.launch { snackbar.showSnackbar("无法初始化系统音频录制") }
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
    LaunchedEffect(recordingState) {
        if (recordingState == RecordingState.RECORDING) {
            while (isActive) {
                delay(1000)
                elapsedSeconds++
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
        }
    }

    // Save to note dialog
    if (showSaveDialog) {
        var noteTitle by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("保存为笔记") },
            text = {
                Column {
                    Text("将录音转写结果保存为笔记", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = noteTitle,
                        onValueChange = { noteTitle = it },
                        label = { Text("笔记标题（可选）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val title = noteTitle.ifBlank { "录音笔记 ${java.text.SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())}" }
                    val text = transcriptResult?.fullText ?: ""
                    if (text.isNotBlank()) {
                        scope.launch {
                            val contentWithPhotos = text + formatPhotosForNote(capturedPhotos)
                            vm.createNoteFromText(title, contentWithPhotos, NoteType.MEETING)
                            savedToNote = true
                            showSaveDialog = false
                            snackbar.showSnackbar("已保存为笔记")
                        }
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text("取消") }
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbar) },
        containerColor = BgGray,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            when {
                // ==================== 录音中 / 暂停 ====================
                recordingState == RecordingState.RECORDING || recordingState == RecordingState.PAUSED -> {
                    RecordingScreen(
                        mode = recordingMode,
                        state = recordingState!!,
                        elapsedSeconds = elapsedSeconds,
                        streamingText = streamingText,
                        waveformBars = waveformBars,
                        scrollState = transcriptScrollState,
                        onPause = { recordingState = RecordingState.PAUSED },
                        onResume = { recordingState = RecordingState.RECORDING },
                        onDone = {
                            recordingState = RecordingState.PROCESSING
                            isProcessing = true
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
                                        snackbar.showSnackbar("录音文件不存在")
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
                                        transcribeWithAsrManager(wavFile, vm.asrManager)
                                    }

                                    // 转写失败时向用户反馈错误信息
                                    val transcriptText = transcriptResult?.fullText ?: ""
                                    if (transcriptText.isBlank() && transcriptResult?.error != null) {
                                        snackbar.showSnackbar(transcriptResult!!.error!!)
                                    }
                                    if (recordingMode == RecordingMode.VOICE_MEMO && transcriptText.isNotBlank() && autoSaveEnabled) {
                                        try {
                                            val autoTitle = transcriptText.take(20).ifBlank { "录音笔记 ${java.text.SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())}" }
                                            val contentWithPhotos = transcriptText + formatPhotosForNote(capturedPhotos)
                                            val noteId = vm.createNoteFromText(autoTitle, contentWithPhotos, NoteType.MEETING)
                                            autoSaved = true
                                            autoSavedNoteId = noteId
                                            savedToNote = true
                                            NotificationHelper.showAutoSaveNote(
                                                context = context,
                                                noteId = noteId,
                                                title = autoTitle,
                                                preview = transcriptText,
                                            )
                                            DebugLog.i("RecordingTab", "自动保存笔记成功, id=$noteId")
                                        } catch (e: Exception) {
                                            DebugLog.e("RecordingTab", "自动保存失败: ${e.message}", e)
                                        }
                                    }

                                    playbackAudioUri = wavFile.absolutePath
                                    File(pcmPath).delete()
                                } catch (e: Exception) {
                                    DebugLog.e("RecordingTab", "处理失败: ${e.message}", e)
                                    snackbar.showSnackbar("处理失败: ${e.message}")
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
                    )
                }

                // ==================== 处理中骨架屏 ====================
                isProcessing -> {
                    SkeletonLoadingScreen()
                }

                // ==================== 完成，显示结果 ====================
                recordingState == RecordingState.DONE -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        // 顶部标题
                        Text(
                            text = "录音",
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp,
                            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp),
                        )

                        // 模式标签
                        Row(
                            modifier = Modifier.padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = recordingMode.icon,
                                contentDescription = null,
                                tint = AccentBlue,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = recordingMode.label,
                                fontSize = 14.sp,
                                color = TextGrey,
                            )
                        }

                        Spacer(Modifier.height(12.dp))

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
                                    onCopy = {
                                        clipboard.setText(AnnotatedString(result.fullText))
                                        scope.launch { snackbar.showSnackbar("已复制") }
                                    },
                                    onNavigateToNotes = onNavigateToNotes,
                                    onSave = { showSaveDialog = true },
                                    onAiSummary = {
                                        val structuredPrompt = buildString {
                                            appendLine("请对以下内容进行结构化总结，按以下四个部分输出，每部分用【标记】开头：")
                                            appendLine("【智能总结】用2-3句话概括核心内容")
                                            appendLine("【章节概要】按主题分段，每段配小标题")
                                            appendLine("【金句精选】提取3-5句关键原话")
                                            appendLine("【待办事项】列出所有需要后续跟进的事项")
                                            appendLine()
                                            appendLine("内容如下：")
                                            append(result.fullText)
                                        }
                                        vm.sendUserMessage(structuredPrompt)
                                        scope.launch { snackbar.showSnackbar("已发送给 AI 总结") }
                                    },
                                    onDiscardAutoSave = {
                                        scope.launch {
                                            vm.deleteNote(autoSavedNoteId)
                                            autoSaved = false
                                            autoSavedNoteId = 0L
                                            savedToNote = false
                                            snackbar.showSnackbar("已撤销保存")
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
                            Spacer(Modifier.height(12.dp))
                            AudioPlayer(audioUri = audioUri)
                        }

                        Spacer(Modifier.height(24.dp))
                    }
                }

                // ==================== 空闲状态：模式选择 ====================
                else -> {
                    IdleModeSelection(
                        selectedMode = recordingMode,
                        onModeSelected = { recordingMode = it },
                        onStartRecording = {
                            when (recordingMode) {
                                RecordingMode.INTERNAL -> {
                                    if (Build.VERSION.SDK_INT < 29) {
                                        scope.launch { snackbar.showSnackbar("需要 Android 10+") }
                                    } else {
                                        scope.launch { snackbar.showSnackbar("请播放你要记录的视频或音频，系统将自动录制") }
                                        val mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                        mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                                    }
                                }
                                else -> startRecording()
                            }
                        },
                    )
                }
            }
        }
    }
}

// ==================== 子 Composable ====================

@Composable
private fun IdleModeSelection(
    selectedMode: RecordingMode,
    onModeSelected: (RecordingMode) -> Unit,
    onStartRecording: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text(
            text = "选择录音模式",
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            color = TextDark,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "根据场景选择合适的录音方式",
            fontSize = 14.sp,
            color = TextGrey,
        )
        Spacer(Modifier.height(20.dp))

        // 2x2 模式卡片网格
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ModeCard(
                    mode = RecordingMode.VOICE_MEMO,
                    isSelected = selectedMode == RecordingMode.VOICE_MEMO,
                    onClick = { onModeSelected(RecordingMode.VOICE_MEMO) },
                    modifier = Modifier.weight(1f),
                )
                ModeCard(
                    mode = RecordingMode.MEETING,
                    isSelected = selectedMode == RecordingMode.MEETING,
                    onClick = { onModeSelected(RecordingMode.MEETING) },
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ModeCard(
                    mode = RecordingMode.INTERNAL,
                    isSelected = selectedMode == RecordingMode.INTERNAL,
                    onClick = { onModeSelected(RecordingMode.INTERNAL) },
                    modifier = Modifier.weight(1f),
                )
                ModeCard(
                    mode = RecordingMode.CLASSROOM,
                    isSelected = selectedMode == RecordingMode.CLASSROOM,
                    onClick = { onModeSelected(RecordingMode.CLASSROOM) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.weight(1f))

        // 底部大录音按钮
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "最长可录 ${selectedMode.maxHours} 小时",
                fontSize = 13.sp,
                color = TextGrey,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(CoralRed)
                    .clickable { onStartRecording() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "开始录音",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "点击开始录音",
                fontSize = 14.sp,
                color = TextGrey,
                modifier = Modifier.padding(bottom = 32.dp),
            )
        }
    }
}

@Composable
private fun ModeCard(
    mode: RecordingMode,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (isSelected) SelectedBorder else Color.Transparent
    val bgColor = if (isSelected) CoralLight else Color.White
    Card(
        modifier = modifier
            .aspectRatio(1.25f)
            .border(2.dp, borderColor, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 4.dp else 1.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Icon(
                imageVector = mode.icon,
                contentDescription = null,
                tint = if (isSelected) CoralRed else TextGrey,
                modifier = Modifier.size(28.dp),
            )
            Column {
                Text(
                    text = mode.label,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = TextDark,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${mode.maxHours}h",
                    fontSize = 12.sp,
                    color = TextGrey,
                )
            }
        }
    }
}

@Composable
private fun RecordingScreen(
    mode: RecordingMode,
    state: RecordingState,
    elapsedSeconds: Int,
    streamingText: String,
    waveformBars: List<Float>,
    scrollState: androidx.compose.foundation.ScrollState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDone: () -> Unit,
    onTakePhoto: () -> Unit,
    onCancel: () -> Unit,
) {
    val timeText = remember(elapsedSeconds) {
        val m = elapsedSeconds / 60
        val s = elapsedSeconds % 60
        String.format("%02d:%02d", m, s)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        // 顶部模式标签
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = mode.icon,
                    contentDescription = null,
                    tint = CoralRed,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = mode.label,
                    fontSize = 14.sp,
                    color = TextDark,
                )
            }
            Text(
                text = "最长 ${mode.maxHours} 小时",
                fontSize = 12.sp,
                color = TextGrey,
            )
        }

        // 实时转录区域
        Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
            ) {
                if (streamingText.isBlank()) {
                    val hintText = if (mode == RecordingMode.INTERNAL) {
                        "正在录制系统音频，请播放你要记录的内容..."
                    } else {
                        "正在聆听，请开始说话..."
                    }
                    Text(
                        text = hintText,
                        fontSize = 18.sp,
                        color = TextGrey.copy(alpha = 0.5f),
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
                            text = streamingText,
                            fontSize = 18.sp,
                            color = TextDark,
                            lineHeight = (18 * 1.8).sp,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // 计时器 + 波形
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = timeText,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = TextDark,
            )
            Spacer(Modifier.height(8.dp))
            WaveformBars(bars = waveformBars, color = CoralRed)
        }

        Spacer(Modifier.height(16.dp))

        // 控制按钮行：取消 [大圆按钮] 完成
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
                        .size(48.dp)
                        .background(Color(0xFFF5F5F5), CircleShape),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "取消",
                        tint = TextGrey,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text("取消", fontSize = 12.sp, color = TextGrey)
            }

            // 大圆录音/暂停按钮
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(CoralRed)
                    .clickable {
                        if (state == RecordingState.RECORDING) onPause() else onResume()
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (state == RecordingState.RECORDING) {
                    Icon(
                        imageVector = Icons.Default.Pause,
                        contentDescription = "暂停",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "继续",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }

            // 完成
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(
                    onClick = onDone,
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color(0xFFF5F5F5), CircleShape),
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "完成",
                        tint = AccentBlue,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text("完成", fontSize = 12.sp, color = TextGrey)
            }
        }

        Spacer(Modifier.height(12.dp))

        // 底部工具行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolIcon(icon = Icons.Default.PhotoCamera, label = "拍照", onClick = onTakePhoto)
            ToolIcon(icon = Icons.Default.Bookmark, label = "标记")
            ToolIcon(icon = Icons.Default.Edit, label = "随手记")
        }
    }
}

@Composable
private fun ToolIcon(icon: ImageVector, label: String, onClick: (() -> Unit)? = null) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = TextGrey,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 11.sp, color = TextGrey)
    }
}

@Composable
private fun WaveformBars(
    bars: List<Float>,
    color: Color,
    barWidth: androidx.compose.ui.unit.Dp = 4.dp,
    barSpacing: androidx.compose.ui.unit.Dp = 3.dp,
    maxHeight: androidx.compose.ui.unit.Dp = 32.dp,
) {
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
                    .clip(RoundedCornerShape(2.dp))
                    .background(color.copy(alpha = 0.8f)),
            )
        }
    }
}

@Composable
private fun SkeletonLoadingScreen() {
    val shimmerColors = listOf(
        Color.LightGray.copy(alpha = 0.3f),
        Color.LightGray.copy(alpha = 0.7f),
        Color.LightGray.copy(alpha = 0.3f),
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
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .height(14.dp)
                    .background(brush, RoundedCornerShape(7.dp)),
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = "生成笔记中..",
            fontSize = 14.sp,
            color = TextGrey,
        )
    }
}

@Composable
private fun TranscriptResultCard(
    result: MeetingTranscriptResult,
    autoSaved: Boolean,
    selectedMode: RecordingMode,
    capturedPhotos: List<CapturedPhoto>,
    onCopy: () -> Unit,
    onNavigateToNotes: () -> Unit,
    onSave: () -> Unit,
    onAiSummary: () -> Unit,
    onDiscardAutoSave: () -> Unit,
    onContinueRecording: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (autoSaved) Icons.Default.CheckCircle else Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = if (autoSaved) Color(0xFF4CAF50) else AccentBlue,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (autoSaved) "已自动保存" else "转录结果",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = if (autoSaved) Color(0xFF4CAF50) else TextDark,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${result.segments.size} 段",
                    color = TextGrey,
                    fontSize = 12.sp,
                )
            }

            if (autoSaved) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "笔记已自动保存，可直接编辑或继续录音",
                        fontSize = 12.sp,
                        color = Color(0xFF4CAF50),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            Text(
                text = result.fullText.ifBlank { "（无识别结果）" },
                fontSize = 14.sp,
                color = TextDark,
                lineHeight = 22.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState()),
            )

            if (capturedPhotos.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                CapturedPhotosRow(photos = capturedPhotos)
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onCopy,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).height(44.dp),
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("复制", fontWeight = FontWeight.Medium)
                }

                if (selectedMode == RecordingMode.VOICE_MEMO) {
                    if (autoSaved) {
                        Button(
                            onClick = onNavigateToNotes,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                            modifier = Modifier.weight(1f).height(44.dp),
                        ) {
                            Icon(Icons.Default.NoteAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("编辑", fontWeight = FontWeight.Medium)
                        }
                    } else {
                        Button(
                            onClick = onSave,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                            modifier = Modifier.weight(1f).height(44.dp),
                        ) {
                            Icon(Icons.Default.NoteAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("保存笔记", fontWeight = FontWeight.Medium)
                        }
                    }
                } else {
                    Button(
                        onClick = onAiSummary,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                        modifier = Modifier.weight(1f).height(44.dp),
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("AI 总结", fontWeight = FontWeight.Medium)
                    }
                }
            }

            if (autoSaved && selectedMode == RecordingMode.VOICE_MEMO) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onDiscardAutoSave,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).height(44.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE57373)),
                    ) {
                        Text("撤销保存", fontWeight = FontWeight.Medium)
                    }
                    OutlinedButton(
                        onClick = onContinueRecording,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).height(44.dp),
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("继续录音", fontWeight = FontWeight.Medium)
                    }
                }
            } else {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onContinueRecording,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("继续录音", fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun CapturedPhotosRow(photos: List<CapturedPhoto>) {
    Column {
        Text(
            text = "拍摄的照片 (${photos.size}张)",
            fontSize = 13.sp,
            color = TextGrey,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            photos.forEach { photo ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    photo.bitmap?.let { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "照片",
                            modifier = Modifier
                                .size(80.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    val totalSeconds = photo.recordingTimeMs / 1000
                    val minutes = totalSeconds / 60
                    val seconds = totalSeconds % 60
                    Text(
                        text = "拍摄于 %02d:%02d".format(minutes, seconds),
                        fontSize = 11.sp,
                        color = TextGrey,
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
    val dataSize = pcmFile.length().toInt()
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
    wavFile: File,
    asrManager: top.hsyscn.opedrgent.stt.AsrManager,
): MeetingTranscriptResult {
    return try {
        val result = asrManager.transcribeFile(wavFile.absolutePath)
        val error = if (result.text.isEmpty() && result.error != null) {
            DebugLog.w("RecordingTab", "转写为空且有错误: ${result.error}")
            "转写失败: ${result.error}"
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
        MeetingTranscriptResult(error = "转录失败: ${e.message}")
    }
}
