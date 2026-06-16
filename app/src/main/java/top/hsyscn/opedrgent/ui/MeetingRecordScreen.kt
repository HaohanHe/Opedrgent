package top.hsyscn.opedrgent.ui

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
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import top.hsyscn.opedrgent.ui.theme.BgGray
import top.hsyscn.opedrgent.ui.theme.TextDark
import top.hsyscn.opedrgent.ui.theme.TextGrey
import top.hsyscn.opedrgent.ui.theme.AccentPurple
import top.hsyscn.opedrgent.ui.theme.TextPrimary
import top.hsyscn.opedrgent.ui.theme.TextSecondary
import top.hsyscn.opedrgent.ui.theme.TextTertiary
import top.hsyscn.opedrgent.ui.theme.SurfaceLight
import top.hsyscn.opedrgent.ui.theme.CardBackground
import top.hsyscn.opedrgent.ui.theme.SurfaceElevated
import top.hsyscn.opedrgent.ui.theme.BorderLight
import top.hsyscn.opedrgent.ui.theme.DisabledColor
import top.hsyscn.opedrgent.ui.theme.DangerRed
import top.hsyscn.opedrgent.ui.theme.ErrorBackground
import top.hsyscn.opedrgent.ui.theme.ErrorBorder
import top.hsyscn.opedrgent.ui.theme.DeleteConfirmRed
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File
import java.io.FileOutputStream

/** Tab 类型定义（参考得到大脑 4-Tab 系统） */
enum class TranscriptTab(val displayName: String) {
    SMART_SUMMARY("智能总结"),
    TEXT_RECORD("文字记录"),
    ADDITIONAL_NOTES("追加笔记"),
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

    // 当前选中的 Tab（参考得到大脑 tab-bar）
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
            snackbar.showSnackbar("无法初始化录音设备")
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
                title = { Text("会议录音", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = {
                        recordingState = RecordingState.DONE
                        onBack()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbar) },
        containerColor = BgGray,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
        ) {
            // Recording card
            RecordingCard(
                recordingState = recordingState,
                elapsedSeconds = elapsedSeconds,
                fileName = "会议录音",
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
                                snackbar.showSnackbar("录音文件不存在")
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
                            snackbar.showSnackbar("处理失败: ${e.message}")
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

            Spacer(Modifier.height(16.dp))

            // 转录结果区域（带 Tab 系统 + Audio Player）
            AnimatedVisibility(
                visible = transcriptResult != null,
                enter = slideInVertically(initialOffsetY = { it / 3 }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it / 3 }) + fadeOut(),
            ) {
                transcriptResult?.let { result ->
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            // === 头部信息栏 ===
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = AccentPurple,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "转录完成",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = TextPrimary,
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    text = "${result.segments.size} 段 · ${result.speakers.size} 人 · ${TranscriptTimeFormatter.formatDuration(result.durationMs)}",
                                    color = TextGrey,
                                    fontSize = 12.sp,
                                )
                            }

                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(12.dp))

                            // === Sticky Audio Player（参考得到大脑 note-header-audio-player）===
                            if (result.audioFilePath != null && File(result.audioFilePath!!).exists()) {
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
                                Spacer(Modifier.height(12.dp))
                                HorizontalDivider()
                                Spacer(Modifier.height(12.dp))
                            }

                            // === Tab Bar（参考得到大脑 note-tabs）===
                            TranscriptTabBar(
                                activeTab = activeTab,
                                onTabChange = { activeTab = it },
                                hasSmartSummary = result.smartSummary != null,
                            )

                            Spacer(Modifier.height(4.dp))

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

                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(12.dp))

                            // === 底部操作按钮 ===
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(result.fullText))
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f).height(44.dp),
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("复制", fontWeight = FontWeight.Medium)
                                }
                                Button(
                                    onClick = { onSendToChat(result.fullText) },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentPurple),
                                    modifier = Modifier.weight(1f).height(44.dp),
                                ) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("AI 总结", fontWeight = FontWeight.Medium)
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
// Tab Bar（参考得到大脑 note-tab-pill 样式）
// ================================================================

@Composable
private fun TranscriptTabBar(
    activeTab: TranscriptTab,
    onTabChange: (TranscriptTab) -> Unit,
    hasSmartSummary: Boolean,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(24.dp),
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
                shape = RoundedCornerShape(20.dp),
                color = if (activeTab == tab) TextPrimary else Color.Transparent,
                onClick = { onTabChange(tab) },
            ) {
                Text(
                    text = tab.displayName,
                    color = if (activeTab == tab) Color.White else TextSecondary,
                    fontWeight = if (activeTab == tab) FontWeight.Medium else FontWeight.Normal,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

// ================================================================
// Sticky Audio Player（参考得到大脑 note-header-audio-player 样式）
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

    Column {
        // 控制行: -15s | ▶/⏸ | +15s | 时间 | 进度条 | 时长 | 倍速
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceLight)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            // -15s 按钮
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color.Transparent,
                onClick = onSeekBackward15s,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                    Text(text = "\u21BA", fontSize = 14.sp, color = TextTertiary) // ↶
                    Text(text = "15", fontSize = 10.sp, color = TextTertiary, fontWeight = FontWeight.Medium)
                }
            }

            // 播放/暂停按钮（圆形深色背景，参考得到大脑）
            Surface(
                shape = CircleShape,
                color = TextPrimary,
                onClick = onPlayPause,
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            // +15s 按钮
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color.Transparent,
                onClick = onSeekForward15s,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                    Text(text = "15", fontSize = 10.sp, color = TextTertiary, fontWeight = FontWeight.Medium)
                    Text(text = "\u21BB", fontSize = 14.sp, color = TextTertiary) // ↷
                }
            }

            // 当前时间
            Text(
                text = currentPositionFormatted,
                fontSize = 12.sp,
                color = TextSecondary,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )

            // 进度条
            Surface(
                shape = RoundedCornerShape(2.dp),
                color = TextSecondary.copy(alpha = 0.08f),
                onClick = { /* 点击进度条可扩展 */ },
                modifier = Modifier
                    .weight(1f)
                    .height(24.dp)
                    .padding(vertical = 8.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    LinearProgressIndicator(
                        progress = { playbackPosition },
                        color = TextPrimary,
                        trackColor = TextPrimary.copy(alpha = 0.03f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                    )
                }
            }

            // 总时长
            Text(
                text = durationFormatted,
                fontSize = 12.sp,
                color = TextSecondary,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )

            // 倍速选择
            var showSpeedMenu by remember { mutableStateOf(false) }
            val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
            Box {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color.Transparent,
                    onClick = { showSpeedMenu = true },
                ) {
                    Text(
                        text = "${playbackSpeed}x",
                        fontSize = 12.sp,
                        color = TextTertiary,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(6.dp),
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
// 文字记录内容 — Sentence-Item 列表（参考得到大脑 sentence-item 样式）
// ================================================================

@Composable
private fun TranscriptTextContent(
    segments: List<MeetingSegment>,
    currentPlayingIndex: Int,
    onSegmentClick: (Int) -> Unit,
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(0.dp),
        modifier = Modifier.heightIn(max = 500.dp),
    ) {
        itemsIndexed(segments) { index, segment ->
            SentenceItem(
                segment = segment,
                isPlaying = (index == currentPlayingIndex),
                onClick = { onSegmentClick(index) },
            )
            // 句子间距 12dp（参考得到大脑 sentence-item:not(:last-child) margin-bottom: 12px）
            if (index < segments.lastIndex) {
                Spacer(Modifier.height(12.dp))
            }
        }
        // 底部留白（参考得到大脑 note-padding 80px）
        item {
            Spacer(Modifier.height(80.dp))
        }
    }
}

/**
 * 单个句子条目 — 完全对齐得到大脑的 DOM 结构:
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
    val playingColor = AccentPurple // 得到大脑高亮色
    val normalTextColor = TextPrimary
    val normalMetaColor = TextSecondary

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        // 第一行: [圆形索引] [说话人标签] [时间戳]
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // sentence-index: 圆形彩色 badge（参考得到大脑 22x22 circle）
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color(segment.speakerColor)),
            ) {
                Text(
                    text = "${segment.speakerIndex}",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            // sentence-speaker: 圆角 pill badge（参考得到大脑 rounded 8px, bg #f5f7fa）
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = CardBackground,
            ) {
                Text(
                    text = SpeakerColorPalette.formatSpeakerName(segment.speakerLabel),
                    color = normalMetaColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }

            // sentence-starttime: HH:MM:SS 格式（参考得到大脑）
            Text(
                text = TranscriptTimeFormatter.formatMsToHMS(segment.startTimeMs),
                color = if (isPlaying) playingColor else normalMetaColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        // sentence-content: 左缩进对齐到内容区（参考得到大脑 padding-left: calc(22px+10px)）
        Text(
            text = segment.text,
            color = if (isPlaying) playingColor else normalTextColor,
            fontSize = 17.sp,
            lineHeight = 26.sp,
            modifier = Modifier.padding(start = 32.dp).fillMaxWidth(),
        )
    }
}

// ================================================================
// 智能总结内容（参考得到大脑 智能总结 Tab 结构）
// ================================================================

@Composable
private fun SmartSummaryContent(result: MeetingTranscriptResult) {
    val summary = result.smartSummary
    if (summary == null) {
        // 无智能总结数据时显示提示
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 40.dp),
        ) {
            Text(
                text = "暂无智能总结",
                color = TextGrey,
                fontSize = 14.sp,
            )
            Text(
                text = "切换到「文字记录」查看完整转录文本",
                color = TextGrey.copy(alpha = 0.6f),
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        return
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.heightIn(max = 500.dp),
    ) {
        // 📑 录音信息
        item {
            Text(text = "\uD83D\uDCD1 智能总结", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        item {
            Text(text = "录音信息", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                summaryInfoLine("时长", summary.metaInfo.duration)
                summaryInfoLine("参与人数", "${summary.metaInfo.participantCount} 人")
                summaryInfoLine("内容类型", summary.metaInfo.contentType)
            }
        }

        // 录音总结段落
        item {
            Spacer(Modifier.height(8.dp))
            Text(text = "录音总结", fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        items(summary.summarySections) { section ->
            Spacer(Modifier.height(8.dp))
            Text(text = section.title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            section.content.forEach { para ->
                Text(text = para, fontSize = 14.sp, color = TextDark, lineHeight = 22.sp)
            }
        }

        // 📅 章节概要
        if (summary.chapters.isNotEmpty()) {
            item {
                Spacer(Modifier.height(12.dp))
                Text(text = "\uD83D\uDCC5 章节概要", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            items(summary.chapters) { chapter ->
                Spacer(Modifier.height(8.dp))
                // 可点击的时间戳链接（模拟得到大脑 getnotes.seek 协议）
                Surface(shape = RoundedCornerShape(6.dp), color = Color.Transparent) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { }) {
                        Text(
                            text = chapter.timestampFormatted,
                            color = AccentPurple,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = chapter.title,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                        )
                    }
                }
                Text(
                    text = chapter.summary,
                    fontSize = 14.sp,
                    color = TextDark,
                    lineHeight = 22.sp,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }

        // ✨ 金句精选
        if (summary.quotes.isNotEmpty()) {
            item {
                Spacer(Modifier.height(12.dp))
                Text(text = "\u2728 金句精选", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            items(summary.quotes) { quote ->
                Spacer(Modifier.height(6.dp))
                Row {
                    Text(text = "\u201C ", color = Color.Gray)
                    Text(
                        text = quote.text,
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(text = " \u201D (${quote.category})", color = TextGrey, fontSize = 12.sp)
                }
            }
        }

        // 📋 待办事项
        if (summary.actionItems.isNotEmpty()) {
            item {
                Spacer(Modifier.height(12.dp))
                Text(text = "\uD83D\uDCCB 待办事项", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            items(summary.actionItems) { item ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "\u2022 ${item.assignee}: ${item.task}",
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                )
            }
        }
    }
}

@Composable
private fun summaryInfoLine(label: String, value: String) {
    Text(
        text = "$label: $value",
        fontSize = 14.sp,
        color = TextDark,
    )
}

// ================================================================
// 追加笔记 — 数据模型 + 完整 UI（参考得到大脑追加笔记 Tab）
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
                    .padding(vertical = 40.dp),
            ) {
                Text(text = "暂无追加的笔记", color = TextGrey, fontSize = 14.sp)
                Text(
                    text = "在此处添加您对录音内容的补充笔记和想法",
                    color = TextGrey.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(16.dp))
                // 添加按钮（空状态时突出显示）
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = SurfaceLight,
                    onClick = { onToggleAdding(true) },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = AccentPurple, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("添加笔记", color = AccentPurple, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    }
                }
            }
        } else {
            // 笔记列表 + 输入区
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.heightIn(max = 500.dp),
            ) {
                // 已有笔记条目
                itemsIndexed(notes) { index, note ->
                    NoteItemCard(
                        note = note,
                        index = index + 1,
                        onEdit = { onStartEdit(note) },
                        onDelete = { onDeleteNote(note.id) },
                        onSeekToTimestamp = onSeekToTimestamp,
                    )
                }

                // 底部留白
                item { Spacer(Modifier.height(80.dp)) }
            }
        }

        // 输入区域（始终固定在底部）
        if (isAddingNote) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            NoteInputArea(
                inputText = noteInputText,
                isEditing = editingNoteId != null,
                segments = segments,
                onSubmit = { text, segmentIdx ->
                    if (editingNoteId != null) {
                        onUpdateNote(editingNoteId!!, text)
                    } else {
                        onAddNote(text, segmentIdx)
                    }
                },
                onCancel = { onToggleAdding(false) },
                onInputChange = onInputChange,
            )
        } else if (notes.isNotEmpty()) {
            // 非编辑状态下的浮动添加按钮
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, BorderLight),
                onClick = { onToggleAdding(true) },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = AccentPurple, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("追加笔记...", color = TextGrey, fontSize = 14.sp)
                }
            }
        }
    }
}

/**
 * 单条笔记卡片。
 *
 * 显示：序号 | 内容 | 时间戳链接（可点击跳转） | 编辑/删除按钮
 * 样式参考得到大脑 sentence-item 的视觉语言。
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
        shape = RoundedCornerShape(12.dp),
        color = SurfaceElevated,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // 头部：序号 + 时间戳 + 操作按钮
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 序号圆圈（复用 sentence-index 样式）
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(AccentPurple),
                ) {
                    Text(text = "$index", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.width(10.dp))

                // 时间戳链接（如果关联了特定段落）
                if (note.linkedSegmentIndex != null && note.linkedTimestampMs > 0) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color.Transparent,
                        onClick = { onSeekToTimestamp(note.linkedTimestampMs) },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
                            Text(
                                text = TranscriptTimeFormatter.formatMsToHMS(note.linkedTimestampMs),
                                color = AccentPurple,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "跳转到该时刻",
                                tint = AccentPurple,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                }

                // 创建时间
                Text(
                    text = formatNoteTimestamp(note.createdAtMs),
                    color = TextGrey.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                )

                Spacer(Modifier.weight(1f))

                // 编辑按钮
                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = "编辑", tint = TextGrey, modifier = Modifier.size(16.dp))
                }
                // 删除按钮
                IconButton(onClick = { showDeleteConfirm = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "删除", tint = DangerRed, modifier = Modifier.size(16.dp))
                }
            }

            // 笔记内容
            Spacer(Modifier.height(8.dp))
            Text(
                text = note.content,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                color = TextPrimary,
            )

            // 删除确认弹窗
            if (showDeleteConfirm) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = ErrorBackground,
                    border = BorderStroke(1.dp, ErrorBorder),
                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text("确定删除这条笔记？", color = DeleteConfirmRed, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Surface(shape = RoundedCornerShape(6.dp), color = Color.Transparent, onClick = { showDeleteConfirm = false }) {
                            Text("取消", color = TextGrey, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                        Surface(shape = RoundedCornerShape(6.dp), color = DeleteConfirmRed, onClick = { onDelete(); showDeleteConfirm = false }) {
                            Text("删除", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
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
            text = if (isEditing) "编辑笔记" else "新建笔记",
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            color = TextPrimary,
        )
        Spacer(Modifier.height(8.dp))

        // 文本输入框
        OutlinedTextField(
            value = inputText,
            onValueChange = onInputChange,
            placeholder = { Text("记录你的想法、要点或待办事项...", color = TextGrey.copy(alpha = 0.5f), fontSize = 14.sp) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 80.dp, max = 160.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentPurple,
                unfocusedBorderColor = BorderLight,
                cursorColor = AccentPurple,
            ),
            shape = RoundedCornerShape(10.dp),
        )

        // 关联段落选择（可选）
        if (segments.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "关联到录音位置（可选，点击选择）",
                fontSize = 12.sp,
                color = TextGrey,
            )
            Spacer(Modifier.height(4.dp))
            // 简化的段落选择：显示前 6 个段落供快速选择
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(segments.take(6)) { idx, seg ->
                    val isSelected = selectedSegmentIndex == idx
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) AccentPurple else CardBackground,
                        onClick = { selectedSegmentIndex = if (isSelected) null else idx },
                    ) {
                        Text(
                            text = "${TranscriptTimeFormatter.formatMsToHMS(seg.startTimeMs)} ${seg.text.take(12)}...",
                            color = if (isSelected) Color.White else TextGrey,
                            fontSize = 11.sp,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        )
                    }
                }
            }
        }

        // 操作按钮行
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.End) {
            // 取消
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color.Transparent,
                onClick = onCancel,
            ) {
                Text(
                    text = "取消",
                    color = TextGrey,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            // 提交
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (inputText.isNotBlank()) AccentPurple else DisabledColor,
                onClick = {
                    if (inputText.isNotBlank()) {
                        onSubmit(inputText, selectedSegmentIndex)
                    }
                },
            ) {
                Text(
                    text = if (isEditing) "保存修改" else "保存笔记",
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** 格式化笔记创建时间为 "HH:mm" */
private fun formatNoteTimestamp(timestampMs: Long): String {
    val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
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
        MeetingTranscriptResult(error = "转录失败: ${e.message}")
    }
}
