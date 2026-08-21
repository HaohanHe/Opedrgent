package top.hsyscn.opedrgent.ui

import top.hsyscn.opedrgent.ui.theme.AccentBlue
import top.hsyscn.opedrgent.ui.theme.InterviewPurple
import top.hsyscn.opedrgent.ui.theme.InterviewDarkBg
import top.hsyscn.opedrgent.ui.theme.WarningColor
import top.hsyscn.opedrgent.ui.theme.SuccessGreen
import top.hsyscn.opedrgent.ui.theme.AccentOrange
import top.hsyscn.opedrgent.ui.theme.customColors
import top.hsyscn.opedrgent.ui.theme.ElevationTokens
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import top.hsyscn.opedrgent.ui.theme.SizeTokens
import top.hsyscn.opedrgent.ui.theme.SpacingTokens
import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

import top.hsyscn.opedrgent.R
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.stt.AsrPostProcessor
import top.hsyscn.opedrgent.stt.MeetingSegment
import top.hsyscn.opedrgent.stt.MeetingTranscriptResult
import top.hsyscn.opedrgent.stt.SpeakerColorPalette
import top.hsyscn.opedrgent.stt.TranscriptTimeFormatter
import top.hsyscn.opedrgent.ui.components.RecordingCard
import top.hsyscn.opedrgent.ui.components.RecordingState
import top.hsyscn.opedrgent.ui.theme.AccentPurple
import top.hsyscn.opedrgent.ui.theme.TextPrimary
import top.hsyscn.opedrgent.ui.theme.TextSecondary
import top.hsyscn.opedrgent.ui.theme.TextTertiary
import top.hsyscn.opedrgent.ui.theme.DisabledColor
import top.hsyscn.opedrgent.ui.theme.DangerRed
import top.hsyscn.opedrgent.ui.theme.ErrorBackground
import top.hsyscn.opedrgent.ui.theme.ErrorBorder
import top.hsyscn.opedrgent.ui.theme.DeleteConfirmRed
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File
import java.io.FileOutputStream
import top.hsyscn.opedrgent.ui.theme.themeBgGray
import top.hsyscn.opedrgent.ui.theme.themeBorderLight
import top.hsyscn.opedrgent.ui.theme.themeCardBackground
import top.hsyscn.opedrgent.ui.theme.themeSurfaceElevated
import top.hsyscn.opedrgent.ui.theme.themeSurfaceLight
import top.hsyscn.opedrgent.ui.theme.themeTextDark
import top.hsyscn.opedrgent.ui.theme.themeTextGrey
import android.content.Context

/** Tab 类型定义（参考opedrgent 4-Tab 系统） */
enum class TranscriptTab(val displayNameRes: Int) {
    SMART_SUMMARY(R.string.meeting_zhi_neng_zong_jie),
    TEXT_RECORD(R.string.meeting_wen_zi_ji_lu),
    ADDITIONAL_NOTES(R.string.meeting_zhui_jia_bi_ji_biao_qian),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeetingRecordScreen(
    vm: MainViewModel,
    onBack: () -> Unit,
    onSendToChat: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
    }

    var recordingState by remember { mutableStateOf(RecordingState.RECORDING) }
    var elapsedSeconds by remember { mutableIntStateOf(0) }
    var amplitude by remember { mutableFloatStateOf(0f) }
    var transcriptResult by remember { mutableStateOf<MeetingTranscriptResult?>(null) }
    var isProcessing by remember { mutableStateOf(false) }

    // 当前选中的 Tab（参考opedrgent tab-bar）
    var activeTab by remember { mutableStateOf(TranscriptTab.TEXT_RECORD) }

    // 音频播放器状态
    var isPlaying by remember { mutableStateOf(false) }
    var playbackPosition by remember { mutableFloatStateOf(0f) }
    var playbackSpeed by remember { mutableFloatStateOf(1f) }
    var currentPlayingSegmentIndex by remember { mutableIntStateOf(-1) } // 当前高亮的句子索引

    // 追加笔记状态
    var additionalNotes by remember { mutableStateOf(listOf<AdditionalNote>()) }
    var isAddingNote by remember { mutableStateOf(false) }
    var noteInputText by remember { mutableStateOf("") }
    var editingNoteId by remember { mutableStateOf<String?>(null) }

    // ExoPlayer 实例
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = false
        }
    }

    // 说话人调色板（每次新录音重置）
    LaunchedEffect(transcriptResult) {
        if (transcriptResult != null) {
            SpeakerColorPalette.reset()
        }
    }

    val audioRecord = remember { mutableStateOf<AudioRecord?>(null) }
    val tempFilePath = remember { mutableStateOf<String?>(null) }
    val startTime = remember { mutableStateOf(System.currentTimeMillis()) }

    // Start recording
    LaunchedEffect(hasPermission) {
        if (!hasPermission) {
            permLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return@LaunchedEffect
        }

        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize)
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            snackbar.showSnackbar(context.getString(R.string.msg_record_init_failed))
            return@LaunchedEffect
        }

        val tempFile = File(context.cacheDir, "meeting_${System.currentTimeMillis()}.pcm")
        tempFilePath.value = tempFile.absolutePath
        audioRecord.value = recorder

        recorder.startRecording()
        startTime.value = System.currentTimeMillis()

        // Record audio data and track amplitude
        withContext(Dispatchers.IO) {
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
                    } else if (read < 0) {
                        DebugLog.w("MeetingRecord", "AudioRecord.read() 错误码: $read")
                    }
                }
            }
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

    // 播放器位置同步更新
    LaunchedEffect(exoPlayer.isPlaying) {
        if (exoPlayer.isPlaying) {
            while (isActive) {
                delay(250)
                val duration = exoPlayer.duration.coerceAtLeast(1L)
                playbackPosition = (exoPlayer.currentPosition.toFloat() / duration).coerceIn(0f, 1f)
                // 更新当前高亮句子
                transcriptResult?.let { result ->
                    val currentMs = exoPlayer.currentPosition
                    val idx = result.segments.indexOfFirst { seg ->
                        currentMs >= seg.startTimeMs && currentMs <= seg.endTimeMs
                    }
                    if (idx >= 0) currentPlayingSegmentIndex = idx
                }
            }
        }
    }

    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            try {
                exoPlayer.release()
                audioRecord.value?.stop()
                audioRecord.value?.release()
                // 清理播放用 WAV 文件
                transcriptResult?.audioFilePath?.let { File(it).delete() }
            } catch (_: Exception) {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_meeting_recording), style = MaterialTheme.typography.headlineLarge) },
                navigationIcon = {
                    IconButton(onClick = {
                        recordingState = RecordingState.DONE
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    // 分享按钮
                    IconButton(
                        onClick = {
                            transcriptResult?.let { result ->
                                val audioPath = result.audioFilePath
                                if (audioPath != null && File(audioPath).exists()) {
                                    shareAudioFile(context, File(audioPath), result.fullText)
                                } else if (result.fullText.isNotEmpty()) {
                                    shareTextOnly(context, result.fullText)
                                }
                            }
                        },
                        enabled = transcriptResult != null,
                    ) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.action_share))
                    }
                    // 更多操作（下载等）
                    var showMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_more))
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            // 下载音频
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.meeting_xia_zai_lu_yin_wen_jian)) },
                                onClick = {
                                    showMenu = false
                                    transcriptResult?.audioFilePath?.let { path ->
                                        val srcFile = File(path)
                                        if (srcFile.exists()) {
                                            scope.launch {
                                                downloadToDownloads(context, srcFile, snackbar)
                                            }
                                        }
                                    }
                                },
                                leadingIcon = { Icon(Icons.Default.Download, contentDescription = null /* 装饰性图标，文本已说明 */) },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbar) },
        containerColor = themeBgGray(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(SpacingTokens.lg),
        ) {
            // Recording card
            RecordingCard(
                recordingState = recordingState,
                elapsedSeconds = elapsedSeconds,
                fileName = stringResource(R.string.meeting_hui_yi_lu_yin),
                amplitude = amplitude,
                onStop = {
                    recordingState = RecordingState.PROCESSING
                    isProcessing = true
                    try {
                        audioRecord.value?.stop()
                        audioRecord.value?.release()
                        audioRecord.value = null
                    } catch (_: Exception) {}
                    scope.launch {
                        try {
                            val pcmPath = tempFilePath.value
                            if (pcmPath == null) {
                                snackbar.showSnackbar(context.getString(R.string.msg_recording_file_not_found))
                                isProcessing = false
                                recordingState = RecordingState.DONE
                                return@launch
                            }

                            val pcmFile = File(pcmPath)
                            DebugLog.i("MeetingRecord", "PCM 文件: ${pcmFile.length() / 1024}KB, 存在=${pcmFile.exists()}")
                            val wavFile = File(context.cacheDir, "meeting_${System.currentTimeMillis()}.wav")
                            pcmToWav(pcmFile, wavFile, 16000, 1, 16)
                            DebugLog.i("MeetingRecord", "WAV 文件: ${wavFile.length() / 1024}KB, 存在=${wavFile.exists()}")

                            // 使用 AsrPostProcessor 管线转录（含说话人分离 + 智能总结）
                            DebugLog.i("MeetingRecord", "使用 AsrPostProcessor 管线转录")
                            transcriptResult = transcribeWithPostProcessor(
                                context,
                                wavFile, wavFile.absolutePath,
                                vm.asrManager, vm.asrPostProcessor,
                                summaryGenerator = vm.smartSummaryGenerator,
                                apiConfig = vm.apiSettings.getApiConfig(),
                            )

                            // PCM 文件可以安全删除（WAV 仍需用于播放）
                            File(pcmPath).delete()

                            // 设置音频播放器（WAV 文件在 DisposableEffect 中统一释放时才删除）
                            transcriptResult?.audioFilePath?.let { path ->
                                if (File(path).exists()) {
                                    val mediaItem = MediaItem.fromUri(android.net.Uri.fromFile(File(path)))
                                    exoPlayer.setMediaItem(mediaItem)
                                    exoPlayer.prepare()
                                }
                            }
                        } catch (e: Exception) {
                            DebugLog.e("MeetingRecord", "处理失败: ${e.message}", e)
                            snackbar.showSnackbar(context.getString(R.string.recording_processing_failed_generic, e.message))
                        } finally {
                            isProcessing = false
                            recordingState = RecordingState.DONE
                        }
                    }
                },
                onPause = { recordingState = RecordingState.PAUSED },
                onResume = { recordingState = RecordingState.RECORDING },
                onCancel = {
                    recordingState = RecordingState.DONE
                    try {
                        audioRecord.value?.stop()
                        audioRecord.value?.release()
                        audioRecord.value = null
                    } catch (_: Exception) {}
                    tempFilePath.value?.let { File(it).delete() }
                    onBack()
                },
            )

            Spacer(Modifier.height(SpacingTokens.lg))

            // 转录结果区域（带 Tab 系统 + Audio Player）
            AnimatedVisibility(
                visible = transcriptResult != null,
                enter = slideInVertically(initialOffsetY = { it / 3 }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it / 3 }) + fadeOut(),
                modifier = Modifier.weight(1f),
            ) {
                transcriptResult?.let { result ->
                    Card(
                        shape = ShapeTokens.largeShape,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = ElevationTokens.md),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(SpacingTokens.lg)) {
                            // === 头部信息栏 ===
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null, // 装饰性图标，文本已说明
                                    tint = AccentPurple,
                                    modifier = Modifier.size(SizeTokens.iconLg),
                                )
                                Spacer(Modifier.width(SpacingTokens.sm))
                                Text(
                                    text = stringResource(R.string.meeting_zhuan_lu_wan_cheng),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary,
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    text = stringResource(R.string.meeting_1_duan_2_ren_3, result.segments.size, result.speakers.size, TranscriptTimeFormatter.formatDuration(result.durationMs)),
                                    color = themeTextGrey(),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }

                            Spacer(Modifier.height(SpacingTokens.md))
                            HorizontalDivider()
                            Spacer(Modifier.height(SpacingTokens.md))

                            // === Sticky Audio Player（参考opedrgent note-header-audio-player）===
                            val audioPath = result.audioFilePath
                            if (audioPath != null && File(audioPath).exists()) {
                                AudioPlayerBar(
                                    exoPlayer = exoPlayer,
                                    durationMs = result.durationMs,
                                    isPlaying = isPlaying,
                                    playbackPosition = playbackPosition,
                                    playbackSpeed = playbackSpeed,
                                    onPlayPause = {
                                        if (exoPlayer.isPlaying) {
                                            exoPlayer.pause()
                                            isPlaying = false
                                        } else {
                                            exoPlayer.play()
                                            isPlaying = true
                                        }
                                    },
                                    onSeekForward15s = {
                                        exoPlayer.seekTo((exoPlayer.currentPosition + 15000).coerceAtLeast(0L).coerceAtMost(exoPlayer.duration.coerceAtLeast(1L)))
                                    },
                                    onSeekBackward15s = {
                                        exoPlayer.seekTo((exoPlayer.currentPosition - 15000).coerceAtLeast(0L))
                                    },
                                    onSeek = { fraction ->
                                        exoPlayer.seekTo((fraction * exoPlayer.duration).toLong())
                                    },
                                    onSpeedChange = { speed -> playbackSpeed = speed },
                                    onSegmentClick = { index ->
                                        val segment = result.segments.getOrNull(index) ?: return@AudioPlayerBar
                                        exoPlayer.seekTo(segment.startTimeMs.coerceAtLeast(0L))
                                        if (!exoPlayer.isPlaying) {
                                            exoPlayer.play()
                                            isPlaying = true
                                        }
                                    },
                                )
                                Spacer(Modifier.height(SpacingTokens.md))
                                HorizontalDivider()
                                Spacer(Modifier.height(SpacingTokens.md))
                            }

                            // === Tab Bar（参考opedrgent note-tabs）===
                            TranscriptTabBar(
                                activeTab = activeTab,
                                onTabChange = { activeTab = it },
                                hasSmartSummary = result.smartSummary != null,
                            )

                            Spacer(Modifier.height(SpacingTokens.xs))

                            // === Tab 内容区域 ===
                            when (activeTab) {
                                TranscriptTab.SMART_SUMMARY -> SmartSummaryContent(result)
                                TranscriptTab.TEXT_RECORD -> TranscriptTextContent(
                                    segments = result.segments,
                                    currentPlayingIndex = currentPlayingSegmentIndex,
                                    onSegmentClick = { index ->
                                        val segment = result.segments.getOrNull(index) ?: return@TranscriptTextContent
                                        exoPlayer.seekTo(segment.startTimeMs.coerceAtLeast(0L))
                                        if (!exoPlayer.isPlaying) {
                                            exoPlayer.play()
                                            isPlaying = true
                                        }
                                    },
                                )
                                TranscriptTab.ADDITIONAL_NOTES -> AdditionalNotesContent(
                                    notes = additionalNotes,
                                    segments = result.segments,
                                    isAddingNote = isAddingNote,
                                    noteInputText = noteInputText,
                                    editingNoteId = editingNoteId,
                                    onAddNote = { text, linkedSegmentIndex ->
                                        val newNote = AdditionalNote(
                                            id = System.nanoTime().toString(),
                                            content = text.trim(),
                                            linkedSegmentIndex = linkedSegmentIndex,
                                            linkedTimestampMs = linkedSegmentIndex?.let { idx ->
                                                result.segments.getOrNull(idx)?.startTimeMs
                                            } ?: 0L,
                                            createdAtMs = System.currentTimeMillis(),
                                        )
                                        additionalNotes = additionalNotes + newNote
                                        isAddingNote = false
                                        noteInputText = ""
                                    },
                                    onUpdateNote = { noteId, newText ->
                                        additionalNotes = additionalNotes.map {
                                            if (it.id == noteId) it.copy(content = newText.trim()) else it
                                        }
                                        editingNoteId = null
                                        noteInputText = ""
                                    },
                                    onDeleteNote = { noteId ->
                                        additionalNotes = additionalNotes.filter { it.id != noteId }
                                    },
                                    onSeekToTimestamp = { ms ->
                                        exoPlayer.seekTo(ms.coerceAtLeast(0L))
                                        if (!exoPlayer.isPlaying) {
                                            exoPlayer.play()
                                            isPlaying = true
                                        }
                                    },
                                    onToggleAdding = { adding ->
                                        isAddingNote = adding
                                        if (!adding) { noteInputText = ""; editingNoteId = null }
                                    },
                                    onInputChange = { noteInputText = it },
                                    onStartEdit = { note ->
                                        editingNoteId = note.id
                                        noteInputText = note.content
                                        isAddingNote = true
                                    },
                                )
                            }

                            Spacer(Modifier.height(SpacingTokens.md))
                            HorizontalDivider()
                            Spacer(Modifier.height(SpacingTokens.md))

                            // === 底部操作按钮 ===
                            Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.md)) {
                                // 下载录音文件
                                OutlinedButton(
                                    onClick = {
                                        result.audioFilePath?.let { path ->
                                            val srcFile = File(path)
                                            if (srcFile.exists()) {
                                                scope.launch { downloadToDownloads(context, srcFile, snackbar) }
                                            }
                                        }
                                    },
                                    shape = ShapeTokens.mediumShape,
                                    modifier = Modifier.height(SizeTokens.quickActionIcon),
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null, /* 装饰性图标，文本已说明 */ modifier = Modifier.size(SizeTokens.iconMd))
                                    Spacer(Modifier.width(SpacingTokens.sm))
                                    Text(stringResource(R.string.action_download), style = MaterialTheme.typography.titleMedium)
                                }
                                // 复制全文
                                OutlinedButton(
                                    onClick = {
                                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(result.fullText))
                                    },
                                    shape = ShapeTokens.mediumShape,
                                    modifier = Modifier.weight(1f).height(SizeTokens.quickActionIcon),
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null, /* 装饰性图标，文本已说明 */ modifier = Modifier.size(SizeTokens.iconMd))
                                    Spacer(Modifier.width(SpacingTokens.sm))
                                    Text(stringResource(R.string.action_copy), style = MaterialTheme.typography.titleMedium)
                                }
                                Button(
                                    onClick = { onSendToChat(result.fullText) },
                                    shape = ShapeTokens.mediumShape,
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentPurple),
                                    modifier = Modifier.weight(1f).height(SizeTokens.quickActionIcon),
                                ) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = null, /* 装饰性图标，文本已说明 */ modifier = Modifier.size(SizeTokens.iconMd))
                                    Spacer(Modifier.width(SpacingTokens.sm))
                                    Text(stringResource(R.string.recording_ai_summary), style = MaterialTheme.typography.titleMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ================================================================
// Tab Bar（参考opedrgent note-tab-pill 样式）
// ================================================================

@Composable
private fun TranscriptTabBar(
    activeTab: TranscriptTab,
    onTabChange: (TranscriptTab) -> Unit,
    hasSmartSummary: Boolean,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.xl),
        modifier = Modifier.fillMaxWidth(),
    ) {
        val tabs = listOf(
            TranscriptTab.SMART_SUMMARY,
            TranscriptTab.TEXT_RECORD,
            TranscriptTab.ADDITIONAL_NOTES,
        )
        tabs.forEach { tab ->
            // 智能总结 Tab 在没有数据时禁用或隐藏
            if (tab == TranscriptTab.SMART_SUMMARY && !hasSmartSummary) return@forEach

            Surface(
                shape = ShapeTokens.largeShape,
                color = if (activeTab == tab) TextPrimary else Color.Transparent,
                onClick = { onTabChange(tab) },
            ) {
                Text(
                    text = stringResource(tab.displayNameRes),
                    color = if (activeTab == tab) MaterialTheme.colorScheme.onPrimary else TextSecondary,
                    style = if (activeTab == tab) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = SpacingTokens.lg, vertical = SpacingTokens.md),
                )
            }
        }
    }
}

// ================================================================
// Sticky Audio Player（参考opedrgent note-header-audio-player 样式）
// ================================================================

@Composable
private fun AudioPlayerBar(
    exoPlayer: ExoPlayer?,
    durationMs: Long,
    isPlaying: Boolean,
    playbackPosition: Float,
    playbackSpeed: Float,
    onPlayPause: () -> Unit,
    onSeekForward15s: () -> Unit,
    onSeekBackward15s: () -> Unit,
    onSeek: (Float) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onSegmentClick: (Int) -> Unit,
) {
    val durationFormatted = TranscriptTimeFormatter.formatMsToHMS(durationMs)
    val currentPositionMs = (playbackPosition * durationMs).toLong()
    val currentPositionFormatted = TranscriptTimeFormatter.formatMsToHMS(currentPositionMs)
    val statePlayingLabel = stringResource(R.string.state_playing)
    val statePausedLabel = stringResource(R.string.state_paused)

    Column {
        // 控制行: -15s | ▶/⏸ | +15s | 时间 | 进度条 | 时长 | 倍速
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
            modifier = Modifier
                .fillMaxWidth()
                .clip(ShapeTokens.mediumShape)
                .background(themeSurfaceLight())
                .padding(horizontal = SpacingTokens.lg, vertical = SpacingTokens.md),
        ) {
            // -15s 按钮
            Surface(
                shape = ShapeTokens.smallShape,
                color = Color.Transparent,
                onClick = onSeekBackward15s,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(SpacingTokens.xs)) {
                    Text(text = "\u21BA", style = MaterialTheme.typography.bodyLarge, color = TextTertiary) // ↶
                    Text(text = "15", style = MaterialTheme.typography.labelMedium, color = TextTertiary)
                }
            }

            // 播放/暂停按钮（圆形深色背景，参考opedrgent）
            Surface(
                shape = CircleShape,
                color = TextPrimary,
                onClick = onPlayPause,
                modifier = Modifier.semantics(mergeDescendants = true) {
                    stateDescription = if (isPlaying) statePlayingLabel else statePausedLabel
                    liveRegion = LiveRegionMode.Polite
                },
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(SizeTokens.quickActionIcon),
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) stringResource(R.string.action_pause) else stringResource(R.string.cd_play),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(SizeTokens.iconMd),
                    )
                }
            }

            // +15s 按钮
            Surface(
                shape = ShapeTokens.smallShape,
                color = Color.Transparent,
                onClick = onSeekForward15s,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(SpacingTokens.xs)) {
                    Text(text = "15", style = MaterialTheme.typography.labelMedium, color = TextTertiary)
                    Text(text = "\u21BB", style = MaterialTheme.typography.bodyLarge, color = TextTertiary) // ↷
                }
            }

            // 当前时间
            Text(
                text = currentPositionFormatted,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )

            // 进度条
            Surface(
                shape = ShapeTokens.extraSmallShape,
                color = TextSecondary.copy(alpha = 0.08f),
                onClick = { /* 点击进度条可扩展 */ },
                modifier = Modifier
                    .weight(1f)
                    .height(SpacingTokens.xl)
                    .padding(vertical = SpacingTokens.sm),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    LinearProgressIndicator(
                        progress = { playbackPosition },
                        color = TextPrimary,
                        trackColor = TextPrimary.copy(alpha = 0.03f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(SizeTokens.progressTrackHeight)
                            .clip(ShapeTokens.extraSmallShape),
                    )
                }
            }

            // 总时长
            Text(
                text = durationFormatted,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )

            // 倍速选择
            var showSpeedMenu by remember { mutableStateOf(false) }
            val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
            Box {
                Surface(
                    shape = ShapeTokens.smallShape,
                    color = Color.Transparent,
                    onClick = { showSpeedMenu = true },
                ) {
                    Text(
                        text = "${playbackSpeed}x",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextTertiary,
                        modifier = Modifier.padding(SpacingTokens.sm),
                    )
                }
                DropdownMenu(
                    expanded = showSpeedMenu,
                    onDismissRequest = { showSpeedMenu = false },
                ) {
                    speeds.forEach { speed ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "${speed}x",
                                    color = if (speed == playbackSpeed) AccentPurple else TextPrimary,
                                )
                            },
                            onClick = {
                                onSpeedChange(speed)
                                exoPlayer?.setPlaybackParameters(
                                    androidx.media3.common.PlaybackParameters(speed, 1f)
                                )
                                showSpeedMenu = false
                            },
                        )
                    }
                }
            }
        }
    }
}

// ================================================================
// 文字记录内容 — Sentence-Item 列表（参考opedrgent sentence-item 样式）
// ================================================================

@Composable
private fun TranscriptTextContent(
    segments: List<MeetingSegment>,
    currentPlayingIndex: Int,
    onSegmentClick: (Int) -> Unit,
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.none),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(segments, key = { _, seg -> seg.startTimeMs }) { index, segment ->
            SentenceItem(
                segment = segment,
                isPlaying = (index == currentPlayingIndex),
                onClick = { onSegmentClick(index) },
            )
            // 句子间距 12dp（参考opedrgent sentence-item:not(:last-child) margin-bottom: 12px）
            if (index < segments.lastIndex) {
                Spacer(Modifier.height(SpacingTokens.md))
            }
        }
        // 底部留白（参考opedrgent note-padding 80px）
        item {
            Spacer(Modifier.height(SpacingTokens.xxl * 2 + SpacingTokens.xl))
        }
    }
}

/**
 * 单个句子条目 — 完全对齐opedrgent的 DOM 结构:
 *
 * <div class="sentence-item sentence-item--clickable">
 *   <div class="flex items-center gap-[10px]">
 *     <span class="sentence-index" style="background-color: rgb(R,G,B);">N</span>
 *     <span class="sentence-speaker">说话人X</span>
 *     <span class="sentence-starttime">HH:MM:SS</span>
 *   </div>
 *   <div class="sentence-content">text...</div>
 * </div>
 *
 * CSS 关键属性映射:
 * - sentence-index: 22x22 圆形, font-size 12px, border-radius 50%
 * - sentence-speaker: padding 3px 6px, bg #f5f7fa, rounded 8px, font-size 14px
 * - sentence-starttime: font-size 14px, color #8a8f99
 * - sentence-content: padding-left calc(22px+10px), font-size 17px, line-height 26px
 * - hover/playing: color -> #766af6
 */
@Composable
private fun SentenceItem(
    segment: MeetingSegment,
    isPlaying: Boolean,
    onClick: () -> Unit,
) {
    val playingColor = AccentPurple // opedrgent高亮色
    val normalTextColor = TextPrimary
    val normalMetaColor = TextSecondary

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.cd_enter), onClick = onClick)
            .padding(vertical = SpacingTokens.xs),
    ) {
        // 第一行: [圆形索引] [说话人标签] [时间戳]
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.md),
        ) {
            // sentence-index: 圆形彩色 badge（参考opedrgent 22x22 circle）
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(SizeTokens.iconLg)
                    .clip(CircleShape)
                    .background(Color(segment.speakerColor)),
            ) {
                Text(
                    text = "${segment.speakerIndex}",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            // sentence-speaker: 圆角 pill badge（参考opedrgent rounded 8px, bg #f5f7fa）
            Surface(
                shape = ShapeTokens.smallShape,
                color = themeCardBackground(),
            ) {
                Text(
                    text = SpeakerColorPalette.formatSpeakerName(segment.speakerLabel),
                    color = normalMetaColor,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xs),
                )
            }

            // sentence-starttime + 播放跳转按钮（参考opedrgent：时间码右侧有 ▶ 可点击跳转）
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SpacingTokens.xs)) {
                Text(
                    text = TranscriptTimeFormatter.formatMsToHMS(segment.startTimeMs),
                    color = if (isPlaying) playingColor else normalMetaColor,
                    style = MaterialTheme.typography.titleSmall,
                )
                // ▶ 播放跳转按钮 — 点击跳转到该段起始位置并播放
                Surface(
                    shape = CircleShape,
                    color = if (isPlaying) AccentPurple.copy(alpha = 0.12f) else Color.Transparent,
                    onClick = onClick,
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.meeting_tiao_zhuan_dao_1, TranscriptTimeFormatter.formatMsToHMS(segment.startTimeMs)),
                        tint = if (isPlaying) playingColor else normalMetaColor.copy(alpha = 0.6f),
                        modifier = Modifier.size(SizeTokens.iconLg).padding(SpacingTokens.xxs),
                    )
                }
            }
        }

        // sentence-content: 左缩进对齐到内容区（参考opedrgent padding-left: calc(22px+10px)）
        Text(
            text = segment.text,
            color = if (isPlaying) playingColor else normalTextColor,
            style = MaterialTheme.typography.titleSmall,
            
            modifier = Modifier.padding(start = SpacingTokens.xxl).fillMaxWidth(),
        )
    }
}

// ================================================================
// 智能总结内容（参考opedrgent 智能总结 Tab 结构）
// ================================================================

@Composable
private fun SmartSummaryContent(result: MeetingTranscriptResult) {
    val context = LocalContext.current
    val summary = result.smartSummary
    if (summary == null) {
        // 无智能总结数据时显示提示
        Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = SpacingTokens.xxl + SpacingTokens.sm),
            ) {
                Text(
                    text = stringResource(R.string.meeting_zan_wu_zhi_neng_zong_jie),
                    color = themeTextGrey(),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.meeting_qie_huan_dao_wen_zi_ji_lu_cha),
                    color = themeTextGrey().copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = SpacingTokens.xs),
                )
            }
        return
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.lg),
        modifier = Modifier.fillMaxSize(),
    ) {
        // 📑 录音信息
        item {
            Text(text = stringResource(R.string.meeting_ud83d_udcd1_zhi_neng_zong_jie), style = MaterialTheme.typography.displaySmall)
        }
        item {
            Text(text = stringResource(R.string.meeting_lu_yin_xin_xi), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(SpacingTokens.sm))
            Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.xs)) {
                summaryInfoLine(stringResource(R.string.stt_result_duration_label), summary.metaInfo.duration)
                summaryInfoLine(stringResource(R.string.meeting_can_yu_ren_shu), context.getString(R.string.meeting_1_ren, summary.metaInfo.participantCount))
                summaryInfoLine(stringResource(R.string.meeting_nei_rong_lei_xing), summary.metaInfo.contentType)
            }
        }

        // 录音总结段落
        item {
            Spacer(Modifier.height(SpacingTokens.sm))
            Text(text = stringResource(R.string.meeting_lu_yin_zong_jie), style = MaterialTheme.typography.titleSmall)
        }
        items(summary.summarySections, key = { it.title + "_" + it.content.hashCode() }) { section ->
            Spacer(Modifier.height(SpacingTokens.sm))
            Text(text = section.title, style = MaterialTheme.typography.titleMedium)
            section.content.forEach { para ->
                Text(text = para, style = MaterialTheme.typography.bodyLarge, color = themeTextDark())
            }
        }

        // 📅 章节概要
        if (summary.chapters.isNotEmpty()) {
            item {
                Spacer(Modifier.height(SpacingTokens.md))
                Text(text = stringResource(R.string.meeting_ud83d_udcc5_zhang_jie_gai_yao), style = MaterialTheme.typography.headlineLarge)
            }
            items(summary.chapters, key = { it.title + "_" + it.summary.hashCode() }) { chapter ->
                Spacer(Modifier.height(SpacingTokens.sm))
                // 可点击的时间戳链接（模拟opedrgent getnotes.seek 协议）
                Surface(shape = ShapeTokens.smallShape, color = Color.Transparent) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { }) {
                        Text(
                            text = chapter.timestampFormatted,
                            color = AccentPurple,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.width(SpacingTokens.sm))
                        Text(
                            text = chapter.title,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
                Text(
                    text = chapter.summary,
                    style = MaterialTheme.typography.bodyLarge,
                    color = themeTextDark(),
                    
                    modifier = Modifier.padding(start = SpacingTokens.xs),
                )
            }
        }

        // ✨ 金句精选
        if (summary.quotes.isNotEmpty()) {
            item {
                Spacer(Modifier.height(SpacingTokens.md))
                Text(text = stringResource(R.string.meeting_u2728_jin_ju_jing_xuan), style = MaterialTheme.typography.headlineLarge)
            }
            items(summary.quotes, key = { it.text + "_" + it.category }) { quote ->
                Spacer(Modifier.height(SpacingTokens.sm))
                Row {
                    Text(text = "\u201C ", color = themeTextGrey())
                    Text(
                        text = quote.text,
                        style = MaterialTheme.typography.bodyLarge,
                        
                        modifier = Modifier.weight(1f),
                    )
                    Text(text = " \u201D (${quote.category})", color = themeTextGrey(), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // 📋 待办事项
        if (summary.actionItems.isNotEmpty()) {
            item {
                Spacer(Modifier.height(SpacingTokens.md))
                Text(text = stringResource(R.string.meeting_ud83d_udccb_dai_ban_shi_xiang), style = MaterialTheme.typography.headlineLarge)
            }
            items(summary.actionItems, key = { it.assignee + "_" + it.task.hashCode() }) { item ->
                Spacer(Modifier.height(SpacingTokens.sm))
                Text(
                    text = "\u2022 ${item.assignee}: ${item.task}",
                    style = MaterialTheme.typography.bodyLarge,
                    
                )
            }
        }
    }
}

@Composable
private fun summaryInfoLine(label: String, value: String) {
    Text(
        text = "$label: $value",
        style = MaterialTheme.typography.bodyLarge,
        color = themeTextDark(),
    )
}

// ================================================================
// 追加笔记 — 数据模型 + 完整 UI（参考opedrgent追加笔记 Tab）
// ================================================================

/**
 * 单条追加笔记。
 *
 * 用户在转录文本基础上添加的个人笔记/批注，
 * 可关联到特定句子位置（点击时间戳跳转播放）。
 */
data class AdditionalNote(
    val id: String,
    val content: String,
    /** 关联的转录段落索引（null 表示全局笔记） */
    val linkedSegmentIndex: Int? = null,
    /** 关联的时间戳（毫秒），用于播放器跳转 */
    val linkedTimestampMs: Long = 0L,
    val createdAtMs: Long = System.currentTimeMillis(),
)

@Composable
private fun AdditionalNotesContent(
    notes: List<AdditionalNote>,
    segments: List<MeetingSegment>,
    isAddingNote: Boolean,
    noteInputText: String,
    editingNoteId: String?,
    onAddNote: (String, Int?) -> Unit,
    onUpdateNote: (String, String) -> Unit,
    onDeleteNote: (String) -> Unit,
    onSeekToTimestamp: (Long) -> Unit,
    onToggleAdding: (Boolean) -> Unit,
    onInputChange: (String) -> Unit,
    onStartEdit: (AdditionalNote) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // 笔记列表
        if (notes.isEmpty() && !isAddingNote) {
            // 空状态
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = SpacingTokens.xxl + SpacingTokens.sm),
            ) {
                Text(text = stringResource(R.string.meeting_zan_wu_zhui_jia_de_bi_ji), color = themeTextGrey(), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(R.string.meeting_zai_ci_chu_tian_jia_nin_dui),
                    color = themeTextGrey().copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = SpacingTokens.xs),
                )
                Spacer(Modifier.height(SpacingTokens.lg))
                // 添加按钮（空状态时突出显示）
                Surface(
                    shape = ShapeTokens.mediumShape,
                    color = themeSurfaceLight(),
                    onClick = { onToggleAdding(true) },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = SpacingTokens.xl, vertical = SpacingTokens.md),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, /* 装饰性图标，文本已说明 */ tint = AccentPurple, modifier = Modifier.size(SizeTokens.iconMd))
                        Spacer(Modifier.width(SpacingTokens.sm))
                        Text(stringResource(R.string.note_editor_add_note), color = AccentPurple, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        } else {
            // 笔记列表 + 输入区
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(SpacingTokens.md),
                modifier = Modifier.fillMaxSize(),
            ) {
                // 已有笔记条目
                itemsIndexed(notes, key = { _, note -> note.id }) { index, note ->
                    NoteItemCard(
                        note = note,
                        index = index + 1,
                        onEdit = { onStartEdit(note) },
                        onDelete = { onDeleteNote(note.id) },
                        onSeekToTimestamp = onSeekToTimestamp,
                    )
                }

                // 底部留白
                item { Spacer(Modifier.height(SpacingTokens.xxl * 2 + SpacingTokens.xl)) }
            }
        }

        // 输入区域（始终固定在底部）
        if (isAddingNote) {
            Spacer(Modifier.height(SpacingTokens.md))
            HorizontalDivider()
            Spacer(Modifier.height(SpacingTokens.md))
            NoteInputArea(
                inputText = noteInputText,
                isEditing = editingNoteId != null,
                segments = segments,
                onSubmit = { text, segmentIdx ->
                    if (editingNoteId != null) {
                        onUpdateNote(editingNoteId, text)
                    } else {
                        onAddNote(text, segmentIdx)
                    }
                },
                onCancel = { onToggleAdding(false) },
                onInputChange = onInputChange,
            )
        } else if (notes.isNotEmpty()) {
            // 非编辑状态下的浮动添加按钮
            Spacer(Modifier.height(SpacingTokens.sm))
            Surface(
                shape = ShapeTokens.mediumShape,
                color = Color.Transparent,
                border = BorderStroke(SizeTokens.borderWidth, themeBorderLight()),
                onClick = { onToggleAdding(true) },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = SpacingTokens.lg, vertical = SpacingTokens.md).fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, /* 装饰性图标，文本已说明 */ tint = AccentPurple, modifier = Modifier.size(SizeTokens.iconSm))
                    Spacer(Modifier.width(SpacingTokens.sm))
                    Text(stringResource(R.string.meeting_zhui_jia_bi_ji), color = themeTextGrey(), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/**
 * 单条笔记卡片。
 *
 * 显示：序号 | 内容 | 时间戳链接（可点击跳转） | 编辑/删除按钮
 * 样式参考opedrgent sentence-item 的视觉语言。
 */
@Composable
private fun NoteItemCard(
    note: AdditionalNote,
    index: Int,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSeekToTimestamp: (Long) -> Unit,
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Surface(
        shape = ShapeTokens.mediumShape,
        color = themeSurfaceElevated(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(SpacingTokens.md)) {
            // 头部：序号 + 时间戳 + 操作按钮
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 序号圆圈（复用 sentence-index 样式）
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(SizeTokens.iconLg)
                        .clip(CircleShape)
                        .background(AccentPurple),
                ) {
                    Text(text = "$index", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.width(SpacingTokens.md))

                // 时间戳链接（如果关联了特定段落）
                if (note.linkedSegmentIndex != null && note.linkedTimestampMs > 0) {
                    Surface(
                        shape = ShapeTokens.smallShape,
                        color = Color.Transparent,
                        onClick = { onSeekToTimestamp(note.linkedTimestampMs) },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = SpacingTokens.xs)) {
                            Text(
                                text = TranscriptTimeFormatter.formatMsToHMS(note.linkedTimestampMs),
                                color = AccentPurple,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = stringResource(R.string.meeting_tiao_zhuan_dao_gai_shi_ke),
                                tint = AccentPurple,
                                modifier = Modifier.size(SizeTokens.iconSm),
                            )
                        }
                    }
                    Spacer(Modifier.width(SpacingTokens.sm))
                }

                // 创建时间
                Text(
                    text = formatNoteTimestamp(note.createdAtMs),
                    color = themeTextGrey().copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                )

                Spacer(Modifier.weight(1f))

                // 编辑按钮
                IconButton(onClick = onEdit, modifier = Modifier.size(SizeTokens.quickActionIcon)) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.action_edit), tint = themeTextGrey(), modifier = Modifier.size(SizeTokens.iconSm))
                }
                // 删除按钮
                IconButton(onClick = { showDeleteConfirm = true }, modifier = Modifier.size(SizeTokens.quickActionIcon)) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.cd_delete), tint = DangerRed, modifier = Modifier.size(SizeTokens.iconSm))
                }
            }

            // 笔记内容
            Spacer(Modifier.height(SpacingTokens.sm))
            Text(
                text = note.content,
                style = MaterialTheme.typography.titleSmall,
                
                color = TextPrimary,
            )

            // 删除确认弹窗
            if (showDeleteConfirm) {
                Surface(
                    shape = ShapeTokens.smallShape,
                    color = ErrorBackground,
                    border = BorderStroke(SizeTokens.borderWidth, ErrorBorder),
                    modifier = Modifier.padding(top = SpacingTokens.sm).fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = SpacingTokens.md, vertical = SpacingTokens.sm),
                    ) {
                        Text(stringResource(R.string.meeting_que_ding_shan_chu_zhe_tiao_bi), color = DeleteConfirmRed, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Surface(shape = ShapeTokens.smallShape, color = Color.Transparent, onClick = { showDeleteConfirm = false }) {
                            Text(stringResource(R.string.action_cancel), color = themeTextGrey(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xs))
                        }
                        Surface(shape = ShapeTokens.smallShape, color = DeleteConfirmRed, onClick = { onDelete(); showDeleteConfirm = false }) {
                            Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xs))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 笔记输入区域 — 文本框 + 关联段落选择 + 提交/取消按钮。
 */
@Composable
private fun NoteInputArea(
    inputText: String,
    isEditing: Boolean,
    segments: List<MeetingSegment>,
    onSubmit: (String, Int?) -> Unit,
    onCancel: () -> Unit,
    onInputChange: (String) -> Unit,
) {
    var selectedSegmentIndex by remember { mutableStateOf<Int?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        // 提示文字
        Text(
            text = if (isEditing) stringResource(R.string.note_editor_edit) else stringResource(R.string.note_editor_new),
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
        )
        Spacer(Modifier.height(SpacingTokens.sm))

        // 文本输入框
        OutlinedTextField(
            value = inputText,
            onValueChange = onInputChange,
            placeholder = { Text(stringResource(R.string.meeting_ji_lu_ni_de_xiang_fa_yao_dian), color = themeTextGrey().copy(alpha = 0.5f), style = MaterialTheme.typography.bodyLarge) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = SizeTokens.meetingTranscriptMinHeight, max = SizeTokens.meetingTranscriptMaxHeight),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentPurple,
                unfocusedBorderColor = themeBorderLight(),
                cursorColor = AccentPurple,
            ),
            shape = ShapeTokens.smallShape,
        )

        // 关联段落选择（可选）
        if (segments.isNotEmpty()) {
            Spacer(Modifier.height(SpacingTokens.sm))
            Text(
                text = stringResource(R.string.meeting_guan_lian_dao_lu_yin_wei_zhi),
                style = MaterialTheme.typography.bodySmall,
                color = themeTextGrey(),
            )
            Spacer(Modifier.height(SpacingTokens.xs))
            // 简化的段落选择：显示前 6 个段落供快速选择
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(SizeTokens.compactSpacing),
            ) {
                itemsIndexed(segments.take(6), key = { _, seg -> seg.startTimeMs }) { idx, seg ->
                    val isSelected = selectedSegmentIndex == idx
                    Surface(
                        shape = ShapeTokens.smallShape,
                        color = if (isSelected) AccentPurple else themeCardBackground(),
                        onClick = { selectedSegmentIndex = if (isSelected) null else idx },
                    ) {
                        Text(
                            text = "${TranscriptTimeFormatter.formatMsToHMS(seg.startTimeMs)} ${seg.text.take(12)}...",
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else themeTextGrey(),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xs),
                        )
                    }
                }
            }
        }

        // 操作按钮行
        Spacer(Modifier.height(SpacingTokens.md))
        Row(horizontalArrangement = Arrangement.End) {
            // 取消
            Surface(
                shape = ShapeTokens.smallShape,
                color = Color.Transparent,
                onClick = onCancel,
            ) {
                Text(
                    text = stringResource(R.string.action_cancel),
                    color = themeTextGrey(),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = SpacingTokens.md, vertical = SpacingTokens.sm),
                )
            }
            Spacer(Modifier.width(SpacingTokens.sm))
            // 提交
            Surface(
                shape = ShapeTokens.smallShape,
                color = if (inputText.isNotBlank()) AccentPurple else DisabledColor,
                onClick = {
                    if (inputText.isNotBlank()) {
                        onSubmit(inputText, selectedSegmentIndex)
                    }
                },
            ) {
                Text(
                    text = if (isEditing) stringResource(R.string.meeting_bao_cun_xiu_gai) else stringResource(R.string.recording_save_note),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = SpacingTokens.lg, vertical = SpacingTokens.md),
                )
            }
        }
    }
}

/** 格式化笔记创建时间为 "HH:mm" */
private val noteTimeFormat = ThreadLocal<java.text.SimpleDateFormat>()

private fun formatNoteTimestamp(timestampMs: Long): String {
    val fmt = noteTimeFormat.get() ?: java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).also { noteTimeFormat.set(it) }
    return fmt.format(java.util.Date(timestampMs))
}

// ================================================================
// PCM/WAV 工具函数
// ================================================================

private fun ShortArray.toByteArray(): ByteArray {
    val bytes = ByteArray(size * 2)
    for (i in indices) {
        bytes[i * 2] = (this[i].toInt() and 0xFF).toByte()
        bytes[i * 2 + 1] = (this[i].toInt() shr 8 and 0xFF).toByte()
    }
    return bytes
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
       fos.write(shortToLittleEndian(1.toShort())) // PCM
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
        (value.toInt() shr 8 and 0xFF).toByte()
    )
}

// ================================================================
// 统一转录辅助函数 — 接入 AsrPostProcessor 管线
// ================================================================

/**
 * 使用 AsrPostProcessor 完整管线转录音频文件。
 *
 * 流程:
 * 1. AsrManager.transcribeFile() -> 原始 SttResult
 * 2. AsrPostProcessor.postProcess() -> 标点恢复 + 分段 + 说话人分离
 * 3. SpeakerColorPalette 分配颜色 + 格式化名称
 * 4. 构建 MeetingTranscriptResult（含 speakerIndex/speakerColor）
 * 5. SmartSummaryGenerator.generate() -> LLM 智能总结（可选，失败不影响转录）
 *
 * 修复了之前硬编码 speakerLabel="Speaker_0" 的问题。
 */
private suspend fun transcribeWithPostProcessor(
    context: Context,
    wavFile: File,
    audioFilePath: String,
    asrManager: top.hsyscn.opedrgent.stt.AsrManager,
    postProcessor: AsrPostProcessor,
    summaryGenerator: top.hsyscn.opedrgent.stt.SmartSummaryGenerator? = null,
    apiConfig: top.hsyscn.opedrgent.settings.ApiConfig? = null,
): MeetingTranscriptResult {
    return try {
        // Step 1: ASR 原始识别
        val rawResult = asrManager.transcribeFile(wavFile.absolutePath)
        DebugLog.i("MeetingRecord", "ASR 原始结果: ${rawResult.text.length} 字, ${rawResult.segments.size} 段")

        // Step 2: 后处理管线（标点 + 分段 + 说话人分离）
        val processed = postProcessor.postProcess(
            rawText = rawResult.text,
            enableDiarization = true,
        )
        DebugLog.i("MeetingRecord", "后处理完成: ${processed.segments.size} 个标注段落")

        // Step 3: 构建带颜色编码的 MeetingSegment 列表
        val speakerIdSet = processed.segments
            .mapNotNull { it.speakerId }
            .toSet()
            .ifEmpty { setOf("Speaker_0") }

        // 为每个说话人分配编号和颜色
        val speakerIndexMap = speakerIdSet.withIndex().associate { (idx, id) -> id to (idx + 1) }

        val meetingSegments = processed.segments.mapIndexed { idx, spkSeg ->
            val speakerId = spkSeg.speakerId ?: "Speaker_0"
            val indexNum = speakerIndexMap[speakerId] ?: 1
            // 将相对时间 (0~1) 映射为绝对毫秒数
            val startMs = (spkSeg.startTime * rawResult.durationMs).toLong()
            val endMs = (spkSeg.endTime * rawResult.durationMs).toLong()

            MeetingSegment(
                text = spkSeg.text,
                startTimeMs = startMs.coerceAtLeast(0L),
                endTimeMs = endMs.coerceAtLeast(startMs),
                speakerLabel = speakerId,
                speakerIndex = indexNum,
                speakerColor = SpeakerColorPalette.getColor(speakerId),
            )
        }.sortedBy { it.startTimeMs }

        val result = MeetingTranscriptResult(
            segments = meetingSegments,
            fullText = processed.punctuated,
            durationMs = rawResult.durationMs,
            hasDiarization = speakerIdSet.size > 1 || (speakerIdSet.singleOrNull() != "Speaker_0" || speakerIdSet.singleOrNull() != null),
            speakers = speakerIdSet.map { SpeakerColorPalette.formatSpeakerName(it) }.toSet(),
            audioFilePath = audioFilePath,
        )

        // Step 5: 异步生成智能总结（失败不阻塞转录结果）
        if (summaryGenerator != null && apiConfig != null) {
            try {
                val summary = summaryGenerator.generate(result, apiConfig)
                if (summary != null) {
                    DebugLog.i("MeetingRecord", "智能总结生成成功: ${summary.summarySections.size} 节, ${summary.chapters.size} 章")
                    return result.copy(smartSummary = summary)
                }
            } catch (e: Exception) {
                DebugLog.w("MeetingRecord", "智能总结生成跳过（非致命）: ${e.message}")
            }
        }

        result
    } catch (e: Exception) {
        DebugLog.e("MeetingRecord", "管线转录失败: ${e.message}", e)
        MeetingTranscriptResult(error = context.getString(R.string.meeting_zhuan_lu_shi_bai_1, e.message))
    }
}

// ================================================================
// 下载 / 分享 辅助函数
// ================================================================

/**
 * 将录音文件下载到系统 Downloads 目录。
 *
 * 使用 MediaStore API (API 29+) 确保文件在系统"下载"应用中可见，
 * 兼容 scoped storage 限制。
 */
private suspend fun downloadToDownloads(
    context: android.content.Context,
    sourceFile: File,
    snackbar: SnackbarHostState,
) {
    withContext(Dispatchers.IO) {
        try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) downloadsDir.mkdirs()

            // 文件名：会议录音_YYYYMMDD_HHmmss.wav
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                .format(java.util.Date())
            val destName = "Opedrgent_${timestamp}.wav"
            val destFile = File(downloadsDir, destName)

            sourceFile.copyTo(destFile, overwrite = true)

            // 通过 MediaStore 扫描让文件管理器立即可见（API < 29 兼容）
            @Suppress("DEPRECATION")
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
                val intent = android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                intent.data = android.net.Uri.fromFile(destFile)
                context.sendBroadcast(intent)
            }

            val sizeKB = destFile.length() / 1024
            DebugLog.i("MeetingRecord", "音频已下载到: ${destFile.absolutePath} (${sizeKB}KB)")
            snackbar.showSnackbar(context.getString(R.string.meeting_yi_bao_cun_dao_download_1, destName))
        } catch (e: SecurityException) {
            DebugLog.w("MeetingRecord", "下载失败(权限): ${e.message}")
            snackbar.showSnackbar(context.getString(R.string.meeting_xia_zai_shi_bai_qing_shou_yu))
        } catch (e: Exception) {
            DebugLog.e("MeetingRecord", "下载失败: ${e.message}", e)
            snackbar.showSnackbar(context.getString(R.string.meeting_xia_zai_shi_bai_1, e.message))
        }
    }
}

/**
 * 分享音频文件 + 转录文本。
 *
 * 优先分享 WAV 文件（附带文本作为额外信息），
 * 如果文件不存在则降级为纯文本分享。
 */
private fun shareAudioFile(
    context: android.content.Context,
    audioFile: File,
    transcriptText: String,
) {
    try {
        val shareUri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            audioFile,
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(android.content.Intent.EXTRA_STREAM, shareUri)
            putExtra(android.content.Intent.EXTRA_TEXT, context.getString(R.string.meeting_opedrgent_hui_yi_lu_yin_1, transcriptText))
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            android.content.Intent.createChooser(intent, context.getString(R.string.meeting_fen_xiang_hui_yi_lu_yin))
        )
    } catch (e: Exception) {
        // FileProvider 未配置或 URI 异常，降级为纯文本
        shareTextOnly(context, transcriptText)
    }
}

/**
 * 纯文本分享（无音频文件的降级方案）。
 */
private fun shareTextOnly(context: android.content.Context, text: String) {
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, context.getString(R.string.meeting_opedrgent_hui_yi_zhuan_lu_1, text))
    }
    context.startActivity(
        android.content.Intent.createChooser(intent, context.getString(R.string.meeting_fen_xiang_zhuan_lu_wen_ben))
    )
}
