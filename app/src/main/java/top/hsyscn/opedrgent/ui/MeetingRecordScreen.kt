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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
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
import top.hsyscn.opedrgent.stt.MeetingSegment
import top.hsyscn.opedrgent.stt.MeetingTranscriptResult
import top.hsyscn.opedrgent.ui.components.RecordingCard
import top.hsyscn.opedrgent.ui.components.RecordingState
import top.hsyscn.opedrgent.ui.theme.AccentBlue
import top.hsyscn.opedrgent.ui.theme.BgGray
import top.hsyscn.opedrgent.ui.theme.TextDark
import top.hsyscn.opedrgent.ui.theme.TextGrey
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File
import java.io.FileOutputStream

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
                        // Calculate RMS amplitude for visualization
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

    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            try {
                audioRecord.value?.stop()
                audioRecord.value?.release()
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
                    // Stop recorder
                    try {
                        audioRecord.value?.stop()
                        audioRecord.value?.release()
                        audioRecord.value = null
                    } catch (_: Exception) {}

                    // Process recording
                    scope.launch {
                        try {
                            val pcmPath = tempFilePath.value
                            if (pcmPath == null) {
                                snackbar.showSnackbar("录音文件不存在")
                                isProcessing = false
                                recordingState = RecordingState.DONE
                                return@launch
                            }

                            // Convert PCM to WAV first
                            val pcmFile = File(pcmPath)
                            DebugLog.i("MeetingRecord", "PCM 文件: ${pcmFile.length() / 1024}KB, 存在=${pcmFile.exists()}")
                            val wavFile = File(context.cacheDir, "meeting_${System.currentTimeMillis()}.wav")
                            pcmToWav(pcmFile, wavFile, 16000, 1, 16)
                            DebugLog.i("MeetingRecord", "WAV 文件: ${wavFile.length() / 1024}KB, 存在=${wavFile.exists()}")

                            // 使用 AsrManager 统一引擎转录
                            DebugLog.i("MeetingRecord", "使用 AsrManager 统一引擎转录")
                            transcriptResult = transcribeWithAsrManager(wavFile, vm.asrManager)

                            wavFile.delete()
                            File(pcmPath).delete()
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

            // Transcript result
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
                            // Title bar
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = AccentBlue,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "转录结果",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    text = "${result.segments.size} 段 · ${result.speakers.size} 人",
                                    color = TextGrey,
                                    fontSize = 12.sp,
                                )
                            }

                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(12.dp))

                            // Speaker-labeled segments
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.heightIn(max = 400.dp),
                            ) {
                                items(result.segments) { segment ->
                                    MeetingSegmentItem(segment)
                                }
                            }

                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(12.dp))

                            // Action buttons
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        clipboard.setText(AnnotatedString(result.fullText))
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
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
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

@Composable
private fun MeetingSegmentItem(segment: MeetingSegment) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // Speaker label
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = AccentBlue.copy(alpha = 0.1f),
                modifier = Modifier.padding(end = 10.dp),
            ) {
                Text(
                    text = segment.speakerLabel,
                    color = AccentBlue,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = segment.text,
                    fontSize = 14.sp,
                    color = TextDark,
                    lineHeight = 20.sp,
                )
                Text(
                    text = "${segment.startTimeMs / 1000}s - ${segment.endTimeMs / 1000}s",
                    fontSize = 11.sp,
                    color = TextGrey,
                )
            }
        }
    }
}

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
        (value.toInt() shr 8 and 0xFF).toByte()
    )
}

// ==================== 统一转录辅助函数 ====================

/**
 * 使用 AsrManager 统一引擎转录音频文件。
 * 引擎选择由 AsrManager 根据用户设置自动决定。
 */
private suspend fun transcribeWithAsrManager(
    wavFile: File,
    asrManager: top.hsyscn.opedrgent.stt.AsrManager,
): MeetingTranscriptResult {
    return try {
        val result = asrManager.transcribeFile(wavFile.absolutePath)

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
        )
    } catch (e: Exception) {
        DebugLog.e("MeetingRecord", "转录失败: ${e.message}", e)
        MeetingTranscriptResult(error = "转录失败: ${e.message}")
    }
}
