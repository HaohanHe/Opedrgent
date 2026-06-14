package top.hsyscn.opedrgent.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.note.NoteType
import top.hsyscn.opedrgent.stt.MeetingSegment
import top.hsyscn.opedrgent.stt.MeetingTranscriptResult
import top.hsyscn.opedrgent.ui.components.RecordingCard
import top.hsyscn.opedrgent.ui.components.RecordingState
import top.hsyscn.opedrgent.storage.NotificationHelper
import top.hsyscn.opedrgent.ui.theme.AccentBlue
import top.hsyscn.opedrgent.ui.theme.BgGray
import top.hsyscn.opedrgent.ui.theme.TextDark
import top.hsyscn.opedrgent.ui.theme.TextGrey
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File
import java.io.FileOutputStream

/**
 * 录音 Tab。
 *
 * 功能：
 * - 模式选择：快速笔记 / 会议录音
 * - 录音界面（复用 RecordingCard）
 * - 录音完成后自动转写 + 显示结果
 * - 操作：保存为笔记 / 发给AI总结 / 继续录音
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

    // 模式选择：0 = 快速笔记, 1 = 会议录音
    var selectedMode by remember { mutableIntStateOf(0) }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
    }

    var recordingState by remember { mutableStateOf<RecordingState?>(null) }
    var elapsedSeconds by remember { mutableIntStateOf(0) }
    var amplitude by remember { mutableFloatStateOf(0f) }
    var transcriptResult by remember { mutableStateOf<MeetingTranscriptResult?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var savedToNote by remember { mutableStateOf(false) }
    var autoSaved by remember { mutableStateOf(false) }          // 是否已自动保存
    var autoSavedNoteId by remember { mutableStateOf(0L) }      // 自动保存的笔记 ID

    // 读取无感伙伴设置中的自动保存开关
    val autoSaveKey = androidx.datastore.preferences.core.booleanPreferencesKey("key_auto_save")
    val partnerPrefs = context.invisiblePartnerDataStore.data.collectAsState(initial = null).value
    val autoSaveEnabled = partnerPrefs?.get(autoSaveKey) ?: true

    val audioRecord = remember { mutableStateOf<AudioRecord?>(null) }
    val tempFilePath = remember { mutableStateOf<String?>(null) }
    val startTime = remember { mutableStateOf(System.currentTimeMillis()) }

    // Start recording
    fun startRecording() {
        if (!hasPermission) {
            permLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        recordingState = RecordingState.RECORDING
        elapsedSeconds = 0
        amplitude = 0f
        transcriptResult = null
        savedToNote = false
        autoSaved = false
        autoSavedNoteId = 0L

        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize)
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            scope.launch { snackbar.showSnackbar("无法初始化录音设备") }
            recordingState = null
            return
        }

        val tempFile = File(context.cacheDir, "recording_${System.currentTimeMillis()}.pcm")
        tempFilePath.value = tempFile.absolutePath
        audioRecord.value = recorder

        recorder.startRecording()
        startTime.value = System.currentTimeMillis()

        scope.launch {
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
                        }
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

    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            try {
                audioRecord.value?.stop()
                audioRecord.value?.release()
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
                    val title = noteTitle.ifBlank { "录音笔记 ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}" }
                    val text = transcriptResult?.fullText ?: ""
                    if (text.isNotBlank()) {
                        scope.launch {
                            vm.createNoteFromText(title, text, NoteType.MEETING)
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
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
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

            // 模式选择
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FilterChip(
                    selected = selectedMode == 0,
                    onClick = {
                        if (recordingState == null || recordingState == RecordingState.DONE) {
                            selectedMode = 0
                        }
                    },
                    label = { Text("快速笔记", fontSize = 13.sp) },
                    leadingIcon = {
                        Icon(Icons.Default.NoteAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AccentBlue.copy(alpha = 0.12f),
                        selectedLabelColor = AccentBlue,
                    ),
                    shape = RoundedCornerShape(20.dp),
                )
                FilterChip(
                    selected = selectedMode == 1,
                    onClick = {
                        if (recordingState == null || recordingState == RecordingState.DONE) {
                            selectedMode = 1
                        }
                    },
                    label = { Text("会议录音", fontSize = 13.sp) },
                    leadingIcon = {
                        Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AccentBlue.copy(alpha = 0.12f),
                        selectedLabelColor = AccentBlue,
                    ),
                    shape = RoundedCornerShape(20.dp),
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(16.dp))

            // 录音区域
            if (recordingState != null && recordingState != RecordingState.DONE) {
                RecordingCard(
                    recordingState = recordingState!!,
                    elapsedSeconds = elapsedSeconds,
                    fileName = if (selectedMode == 0) "快速笔记" else "会议录音",
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
                                DebugLog.i("RecordingTab", "PCM 文件: ${pcmFile.length() / 1024}KB (${pcmFile.length()}B), path=$pcmPath")
                                val wavFile = File(context.cacheDir, "recording_${System.currentTimeMillis()}.wav")
                                pcmToWav(pcmFile, wavFile, 16000, 1, 16)
                                DebugLog.i("RecordingTab", "WAV 文件: ${wavFile.length() / 1024}KB (${wavFile.length()}B), path=${wavFile.absolutePath}")

                                DebugLog.i("RecordingTab", "开始转写, 引擎类型=${vm.asrManager.getCurrentEngineName()}")
                                transcriptResult = transcribeWithAsrManager(wavFile, vm.asrManager)
                                DebugLog.i("RecordingTab", "转写完成: text=${transcriptResult?.fullText?.length ?: 0}字, error=${transcriptResult?.error}")

                                // 转写失败时向用户反馈错误信息
                                val transcriptText = transcriptResult?.fullText ?: ""
                                if (transcriptText.isBlank() && transcriptResult?.error != null) {
                                    snackbar.showSnackbar(transcriptResult!!.error!!)
                                }
                                if (selectedMode == 0 && transcriptText.isNotBlank() && autoSaveEnabled) {
                                    try {
                                        val autoTitle = transcriptText.take(20).ifBlank { "录音笔记 ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}" }
                                        val noteId = vm.createNoteFromText(autoTitle, transcriptText, NoteType.MEETING)
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

                                wavFile.delete()
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
                    onPause = { recordingState = RecordingState.PAUSED },
                    onResume = { recordingState = RecordingState.RECORDING },
                    onCancel = {
                        recordingState = null
                        try {
                            audioRecord.value?.stop()
                            audioRecord.value?.release()
                            audioRecord.value = null
                        } catch (_: Exception) {}
                        tempFilePath.value?.let { File(it).delete() }
                    },
                )
            } else if (isProcessing) {
                // 处理中
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = AccentBlue,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("正在转写...", fontWeight = FontWeight.Medium, color = TextDark)
                    }
                }
            } else {
                // 空闲状态 - 显示开始录音按钮
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = null,
                            tint = AccentBlue,
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = if (selectedMode == 0) "点击开始快速录音" else "点击开始会议录音",
                            fontWeight = FontWeight.Medium,
                            color = TextDark,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (selectedMode == 0) "录完自动转写并保存为笔记" else "录完自动转写，支持 AI 总结",
                            fontSize = 13.sp,
                            color = TextGrey,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = { startRecording() },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                        ) {
                            Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("开始录音", fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }

            // 转写结果
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            // Title bar
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

                            // 自动保存成功提示
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

                            // 转写文本
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

                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(12.dp))

                            // 操作按钮
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        clipboard.setText(AnnotatedString(result.fullText))
                                        scope.launch { snackbar.showSnackbar("已复制") }
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f).height(44.dp),
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("复制", fontWeight = FontWeight.Medium)
                                }

                                if (selectedMode == 0) {
                                    // 快速笔记模式：自动保存后显示"编辑"，未保存时显示"保存"
                                    if (autoSaved) {
                                        // 已自动保存 -> 编辑按钮，点击跳转到笔记页
                                        Button(
                                            onClick = { onNavigateToNotes() },
                                            shape = RoundedCornerShape(12.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                                            modifier = Modifier.weight(1f).height(44.dp),
                                        ) {
                                            Icon(Icons.Default.NoteAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("编辑", fontWeight = FontWeight.Medium)
                                        }
                                    } else {
                                        // 未自动保存（异常路径）-> 仍显示保存按钮
                                        Button(
                                            onClick = { showSaveDialog = true },
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
                                    // 会议录音模式：AI 总结（不变）
                                    Button(
                                        onClick = {
                                            vm.sendUserMessage("请帮我总结以下会议内容：\n\n${result.fullText}")
                                            scope.launch { snackbar.showSnackbar("已发送给 AI 总结") }
                                        },
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

                            // 自动保存后的操作行：丢弃 + 继续录音
                            if (autoSaved && selectedMode == 0) {
                                Spacer(Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            // 丢弃/撤销自动保存的笔记
                                            scope.launch {
                                                vm.deleteNote(autoSavedNoteId)
                                                autoSaved = false
                                                autoSavedNoteId = 0L
                                                savedToNote = false
                                                snackbar.showSnackbar("已撤销保存")
                                            }
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.weight(1f).height(44.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE57373)),
                                    ) {
                                        Text("撤销保存", fontWeight = FontWeight.Medium)
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            transcriptResult = null
                                            savedToNote = false
                                            autoSaved = false
                                            autoSavedNoteId = 0L
                                            startRecording()
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.weight(1f).height(44.dp),
                                    ) {
                                        Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("继续录音", fontWeight = FontWeight.Medium)
                                    }
                                }
                            } else {
                                // 继续录音按钮（非自动保存状态）
                                Spacer(Modifier.height(10.dp))
                                OutlinedButton(
                                    onClick = {
                                        transcriptResult = null
                                        savedToNote = false
                                        autoSaved = false
                                        autoSavedNoteId = 0L
                                        startRecording()
                                    },
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
            }

            Spacer(Modifier.height(24.dp))
        }
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
        // 转写为空且有底层错误时，将错误信息传递给调用方
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
