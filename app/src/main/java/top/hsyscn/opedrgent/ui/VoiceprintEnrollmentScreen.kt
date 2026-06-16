@file:OptIn(ExperimentalMaterial3Api::class)

package top.hsyscn.opedrgent.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import top.hsyscn.opedrgent.stt.VoiceprintManager
import top.hsyscn.opedrgent.ui.theme.AccentBlue
import top.hsyscn.opedrgent.ui.theme.BgGray
import top.hsyscn.opedrgent.ui.theme.TextDark
import top.hsyscn.opedrgent.ui.theme.TextGrey
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File
import java.io.FileOutputStream
import kotlin.math.sqrt

private const val TOTAL_SAMPLES = 5
private const val SAMPLE_RATE = 16000
private val CoralRed = Color(0xFFFF5A5A)
private val CoralLight = Color(0xFFFFEAEA)

@Composable
fun VoiceprintEnrollmentScreen(
    onBack: () -> Unit,
    onEnrollmentComplete: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val voiceprintManager = remember { VoiceprintManager(context) }

    var speakerName by remember { mutableStateOf("") }
    var currentStep by remember { mutableIntStateOf(0) } // 0 = 输入姓名, 1~5 = 录音阶段, 6 = 完成
    var isRecording by remember { mutableStateOf(false) }
    var amplitude by remember { mutableFloatStateOf(0f) }
    var waveformBars by remember { mutableStateOf(List(24) { 0.2f }) }
    val savedSamplePaths = remember { mutableStateListOf<String>() }
    var showNameError by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }

    val audioRecord = remember { mutableStateOf<AudioRecord?>(null) }
    var tempPcmFile by remember { mutableStateOf<File?>(null) }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        if (!granted) {
            scope.launch { snackbar.showSnackbar("需要录音权限才能录制声纹") }
        }
    }

    // 波形动画
    LaunchedEffect(isRecording) {
        while (isActive && isRecording) {
            waveformBars = List(24) { index ->
                val base = if (amplitude > 0.05f) amplitude else 0.05f
                val wave = kotlin.math.sin((System.currentTimeMillis() / 250.0) + index * 0.6).toFloat() * 0.4f
                val noise = kotlin.random.Random.nextFloat() * 0.2f
                ((base * 0.8f + 0.2f) + wave * base + noise * base).coerceIn(0.05f, 1f)
            }
            delay(80)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                audioRecord.value?.stop()
                audioRecord.value?.release()
            } catch (_: Exception) {}
        }
    }

    fun startRecordingSample() {
        if (!hasPermission) {
            permLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        isRecording = true
        amplitude = 0f

        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, audioFormat)

        val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, channelConfig, audioFormat, bufferSize)
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            scope.launch { snackbar.showSnackbar("无法初始化录音设备") }
            isRecording = false
            return
        }

        val tempFile = File(context.cacheDir, "voiceprint_sample_${System.currentTimeMillis()}.pcm")
        tempPcmFile = tempFile
        audioRecord.value = recorder

        recorder.startRecording()

        scope.launch {
            withContext(Dispatchers.IO) {
                val buffer = ShortArray(bufferSize / 2)
                FileOutputStream(tempFile).use { fos ->
                    while (isActive && isRecording) {
                        val read = recorder.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            fos.write(buffer.toByteArray(), 0, read * 2)
                            var sum = 0L
                            for (i in 0 until read) {
                                sum += buffer[i].toLong() * buffer[i].toLong()
                            }
                            val rms = sqrt(sum.toDouble() / read).toFloat()
                            amplitude = (rms / Short.MAX_VALUE).coerceIn(0f, 1f)
                        }
                    }
                }
            }
        }
    }

    fun stopRecordingSample() {
        isRecording = false
        try {
            audioRecord.value?.stop()
            audioRecord.value?.release()
            audioRecord.value = null
        } catch (_: Exception) {}
    }

    fun saveSampleAndAdvance() {
        val pcmFile = tempPcmFile ?: return
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val wavFile = File(
                        voiceprintManager.getVoiceprintDir(),
                        "${speakerName}_${System.currentTimeMillis()}.wav",
                    )
                    pcmToWav(pcmFile, wavFile, SAMPLE_RATE, 1, 16)
                    savedSamplePaths.add(wavFile.absolutePath)
                    pcmFile.delete()
                    tempPcmFile = null
                } catch (e: Exception) {
                    DebugLog.e("VoiceprintEnrollment", "保存样本失败: ${e.message}", e)
                }
            }
            currentStep++
        }
    }

    fun completeEnrollment() {
        if (speakerName.isBlank() || savedSamplePaths.isEmpty()) return
        voiceprintManager.enrollSpeaker(speakerName, savedSamplePaths.toList())
        scope.launch { snackbar.showSnackbar("声纹注册完成: $speakerName") }
        onEnrollmentComplete()
    }

    BackHandler {
        if (isRecording) {
            showDiscardDialog = true
        } else if (currentStep in 1..5) {
            showExitDialog = true
        } else {
            onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("添加声纹", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isRecording) {
                            showDiscardDialog = true
                        } else if (currentStep in 1..5) {
                            showExitDialog = true
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = BgGray,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))

            when {
                currentStep == 0 -> {
                    NameInputStep(
                        name = speakerName,
                        onNameChange = {
                            speakerName = it
                            showNameError = false
                        },
                        showError = showNameError,
                        onNext = {
                            if (speakerName.isBlank()) {
                                showNameError = true
                            } else {
                                currentStep = 1
                            }
                        },
                    )
                }
                currentStep in 1..TOTAL_SAMPLES -> {
                    RecordingStep(
                        step = currentStep,
                        total = TOTAL_SAMPLES,
                        speakerName = speakerName,
                        isRecording = isRecording,
                        waveformBars = waveformBars,
                        onStartRecording = { startRecordingSample() },
                        onStopRecording = {
                            stopRecordingSample()
                            saveSampleAndAdvance()
                        },
                    )
                }
                else -> {
                    CompletionStep(
                        speakerName = speakerName,
                        sampleCount = savedSamplePaths.size,
                        onDone = {
                            completeEnrollment()
                            onBack()
                        },
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("放弃当前录音") },
            text = { Text("当前录音尚未保存，确定要放弃吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        stopRecordingSample()
                        tempPcmFile?.delete()
                        tempPcmFile = null
                    },
                ) {
                    Text("放弃", color = Color(0xFFE53935))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text("继续录音")
                }
            },
        )
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("退出声纹注册") },
            text = { Text("已录制的 ${savedSamplePaths.size} 个样本将不会保存，确定退出吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExitDialog = false
                        savedSamplePaths.forEach { path ->
                            try { File(path).delete() } catch (_: Exception) {}
                        }
                        onBack()
                    },
                ) {
                    Text("退出", color = Color(0xFFE53935))
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) {
                    Text("继续")
                }
            },
        )
    }
}

@Composable
private fun NameInputStep(
    name: String,
    onNameChange: (String) -> Unit,
    showError: Boolean,
    onNext: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.Person,
            contentDescription = null,
            tint = AccentBlue,
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "请输入说话人姓名",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = TextDark,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "注册后，系统将在会议录音中自动识别此说话人",
            fontSize = 13.sp,
            color = TextGrey,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text("姓名") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = showError,
            supportingText = {
                if (showError) {
                    Text("请输入姓名", color = Color(0xFFE53935))
                }
            },
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(11.dp),
        ) {
            Text("下一步", fontSize = 16.sp)
        }
    }
}

@Composable
private fun RecordingStep(
    step: Int,
    total: Int,
    speakerName: String,
    isRecording: Boolean,
    waveformBars: List<Float>,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 进度指示
        Text(
            text = "第 $step / $total 段",
            fontSize = 14.sp,
            color = TextGrey,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(8.dp))

        // 提示语
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = AccentBlue.copy(alpha = 0.08f),
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "请清晰朗读以下句子",
                    fontSize = 13.sp,
                    color = TextGrey,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "你好，我是$speakerName",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextDark,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(32.dp))

        // 波形可视化
        if (!isRecording) {
            Text(
                text = "点击按钮开始录音",
                fontSize = 14.sp,
                color = TextGrey,
            )
        }
        Spacer(Modifier.height(16.dp))
        WaveformBars(
            bars = waveformBars,
            color = if (isRecording) CoralRed else TextGrey.copy(alpha = 0.4f),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (isRecording) "正在录音..." else "等待开始",
            fontSize = 13.sp,
            color = if (isRecording) CoralRed else TextGrey,
        )

        Spacer(Modifier.height(32.dp))

        // 大圆录音按钮
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(if (isRecording) CoralRed else AccentBlue)
                .clickable {
                    if (isRecording) onStopRecording() else onStartRecording()
                },
            contentAlignment = Alignment.Center,
        ) {
            if (isRecording) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = "停止录音",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp),
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "开始录音",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (isRecording) "点击停止" else "点击录音",
            fontSize = 14.sp,
            color = TextGrey,
        )
    }
}

@Composable
private fun CompletionStep(
    speakerName: String,
    sampleCount: Int,
    onDone: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(Color(0xFF4CAF50)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(36.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = "声纹注册完成",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = TextDark,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "说话人: $speakerName",
            fontSize = 15.sp,
            color = TextDark,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "已录制 $sampleCount 个样本",
            fontSize = 13.sp,
            color = TextGrey,
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(11.dp),
        ) {
            Text("完成", fontSize = 16.sp)
        }
    }
}

@Composable
private fun WaveformBars(
    bars: List<Float>,
    color: Color,
    barWidth: androidx.compose.ui.unit.Dp = 4.dp,
    barSpacing: androidx.compose.ui.unit.Dp = 3.dp,
    maxHeight: androidx.compose.ui.unit.Dp = 64.dp,
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
