@file:OptIn(ExperimentalMaterial3Api::class)

package top.hsyscn.opedrgent.ui

import top.hsyscn.opedrgent.ui.theme.InterviewPurple
import top.hsyscn.opedrgent.ui.theme.InterviewDarkBg
import top.hsyscn.opedrgent.ui.theme.WarningColor
import top.hsyscn.opedrgent.ui.theme.SuccessGreen
import top.hsyscn.opedrgent.ui.theme.AccentOrange
import top.hsyscn.opedrgent.ui.theme.customColors
import top.hsyscn.opedrgent.ui.theme.SpacingTokens
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import top.hsyscn.opedrgent.ui.theme.SizeTokens
import top.hsyscn.opedrgent.R
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.stt.SpeakerDiarizer
import top.hsyscn.opedrgent.stt.SpeakerEmbeddingExtractor
import top.hsyscn.opedrgent.stt.VoiceprintManager
import top.hsyscn.opedrgent.ui.theme.AccentBlue
import top.hsyscn.opedrgent.ui.theme.CoralRed
import top.hsyscn.opedrgent.ui.theme.CoralLight
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File
import java.io.FileOutputStream
import kotlin.math.sqrt
import top.hsyscn.opedrgent.ui.theme.themeBgGray
import top.hsyscn.opedrgent.ui.theme.themeTextDark
import top.hsyscn.opedrgent.ui.theme.themeTextGrey

private const val TOTAL_SAMPLES = 5
private const val SAMPLE_RATE = 16000

@Composable
fun VoiceprintEnrollmentScreen(
    onBack: () -> Unit,
    onEnrollmentComplete: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val voiceprintManager = remember { VoiceprintManager(context) }

    // 初始化 Sherpa-ONNX 声纹嵌入提取器（尝试加载模型）
    val embeddingExtractor = remember {
        SpeakerEmbeddingExtractor(context).also { extractor ->
            if (extractor.checkApiAvailability()) {
                // 尝试从 SpeakerDiarizer 的模型目录初始化
                val modelDir = File(context.filesDir, SpeakerDiarizer.MODEL_ASSET_DIR)
                if (modelDir.exists()) {
                    extractor.initialize(modelDir)
                }
            }
        }
    }
    var useSherpaEmbedding by remember { mutableStateOf(embeddingExtractor.isAvailable) }
    var isProcessingEmbedding by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { embeddingExtractor.release() }
    }

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
            scope.launch { snackbar.showSnackbar(context.getString(R.string.voiceprint_enroll_xu_yao_lu_yin_quan_xian_cai)) }
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
            scope.launch { snackbar.showSnackbar(context.getString(R.string.msg_record_init_failed)) }
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
        isProcessingEmbedding = true

        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    // 尝试使用 Sherpa-ONNX 提取真实声纹嵌入
                    if (useSherpaEmbedding && embeddingExtractor.isAvailable) {
                        DebugLog.i("VoiceprintEnrollment", "尝试使用 Sherpa-ONNX 提取声纹嵌入")
                        val allEmbeddings = mutableListOf<FloatArray>()
                        for (path in savedSamplePaths) {
                            val embedding = embeddingExtractor.extractFromFile(File(path))
                            if (embedding != null) {
                                allEmbeddings.add(embedding)
                                DebugLog.d("VoiceprintEnrollment", "嵌入提取成功: ${File(path).name} (dim=${embedding.size})")
                            } else {
                                DebugLog.w("VoiceprintEnrollment", "嵌入提取失败: ${File(path).name}")
                            }
                        }

                        if (allEmbeddings.isNotEmpty()) {
                            // 对多个样本的嵌入取平均
                            val avgEmbedding = FloatArray(allEmbeddings[0].size)
                            for (emb in allEmbeddings) {
                                for (i in avgEmbedding.indices) {
                                    avgEmbedding[i] += emb[i]
                                }
                            }
                            for (i in avgEmbedding.indices) {
                                avgEmbedding[i] /= allEmbeddings.size
                            }
                            // 归一化
                            var norm = 0f
                            for (v in avgEmbedding) norm += v * v
                            norm = sqrt(norm.toDouble()).toFloat().coerceAtLeast(1e-8f)
                            for (i in avgEmbedding.indices) avgEmbedding[i] /= norm

                            voiceprintManager.enrollWithSherpaEmbedding(
                                speakerName,
                                savedSamplePaths.toList(),
                                avgEmbedding,
                            )
                            DebugLog.i("VoiceprintEnrollment", "Sherpa-ONNX 声纹注册完成: $speakerName, 有效嵌入: ${allEmbeddings.size}/${savedSamplePaths.size}")
                        } else {
                            // Sherpa-ONNX 全部失败，降级到统计特征
                            DebugLog.w("VoiceprintEnrollment", "Sherpa-ONNX 嵌入提取全部失败，降级到统计特征")
                            voiceprintManager.enrollSpeaker(speakerName, savedSamplePaths.toList())
                        }
                    } else {
                        // Sherpa-ONNX 不可用，使用统计特征
                        DebugLog.i("VoiceprintEnrollment", "Sherpa-ONNX 不可用，使用统计特征注册")
                        voiceprintManager.enrollSpeaker(speakerName, savedSamplePaths.toList())
                    }
                } catch (e: Exception) {
                    DebugLog.e("VoiceprintEnrollment", "声纹注册异常: ${e.message}", e)
                    // 最终 fallback
                    voiceprintManager.enrollSpeaker(speakerName, savedSamplePaths.toList())
                }
            }
            isProcessingEmbedding = false
            val method = if (useSherpaEmbedding && embeddingExtractor.isAvailable) "Sherpa-ONNX" else context.getString(R.string.voiceprint_enroll_tong_ji_te_zheng)
            snackbar.showSnackbar(context.getString(R.string.voiceprint_enroll_sheng_wen_zhu_ce_wan_cheng_1_2, method, speakerName))
            onEnrollmentComplete()
        }
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
                title = { Text(stringResource(R.string.voiceprint_enroll_tian_jia_sheng_wen), style = MaterialTheme.typography.headlineLarge) },
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = themeBgGray(),
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = SizeTokens.screenHorizontalPadding)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(SpacingTokens.xl))

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
                        isProcessing = isProcessingEmbedding,
                        isSherpaAvailable = useSherpaEmbedding && embeddingExtractor.isAvailable,
                        onDone = {
                            if (!isProcessingEmbedding) {
                                completeEnrollment()
                                onBack()
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(SpacingTokens.xxl))
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(stringResource(R.string.voiceprint_enroll_fang_qi_dang_qian_lu_yin)) },
            text = { Text(stringResource(R.string.voiceprint_enroll_dang_qian_lu_yin_shang_wei)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        stopRecordingSample()
                        tempPcmFile?.delete()
                        tempPcmFile = null
                    },
                ) {
                    Text(stringResource(R.string.voiceprint_enroll_fang_qi), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text(stringResource(R.string.recording_continue))
                }
            },
        )
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text(stringResource(R.string.voiceprint_enroll_tui_chu_sheng_wen_zhu_ce)) },
            text = { Text(stringResource(R.string.voiceprint_enroll_yi_lu_zhi_de_1_ge_yang_ben, savedSamplePaths.size)) },
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
                    Text(stringResource(R.string.voiceprint_enroll_tui_chu), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) {
                    Text(stringResource(R.string.action_resume))
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
            modifier = Modifier.size(SizeTokens.emptyStateIcon),
        )
        Spacer(Modifier.height(SpacingTokens.lg))
        Text(
            text = stringResource(R.string.voiceprint_enroll_qing_shu_ru_shuo_hua_ren_xing),
            style = MaterialTheme.typography.headlineLarge,
            color = themeTextDark(),
        )
        Spacer(Modifier.height(SpacingTokens.sm))
        Text(
            text = stringResource(R.string.voiceprint_enroll_zhu_ce_hou_xi_tong_jiang_zai),
            style = MaterialTheme.typography.bodyMedium,
            color = themeTextGrey(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(SpacingTokens.xl))
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text(stringResource(R.string.voiceprint_enroll_xing_ming)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = showError,
            supportingText = {
                if (showError) {
                    Text(
                        text = stringResource(R.string.voiceprint_enroll_qing_shu_ru_xing_ming),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                    )
                }
            },
        )
        Spacer(Modifier.height(SpacingTokens.xl))
        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth().height(SizeTokens.buttonHeightLg),
            shape = ShapeTokens.smallShape,
        ) {
            Text(stringResource(R.string.onboarding_next), style = MaterialTheme.typography.titleMedium)
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
            text = stringResource(R.string.voiceprint_enroll_di_1_2_duan, step, total),
            style = MaterialTheme.typography.titleMedium,
            color = themeTextGrey(),
        )
        Spacer(Modifier.height(SpacingTokens.sm))

        // 提示语
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = ShapeTokens.mediumShape,
            colors = CardDefaults.cardColors(
                containerColor = AccentBlue.copy(alpha = 0.08f),
            ),
        ) {
            Column(
                modifier = Modifier.padding(SpacingTokens.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.voiceprint_enroll_qing_qing_xi_lang_du_yi_xia),
                    style = MaterialTheme.typography.bodyMedium,
                    color = themeTextGrey(),
                )
                Spacer(Modifier.height(SpacingTokens.sm))
                Text(
                    text = stringResource(R.string.voiceprint_enroll_ni_hao_wo_shi_1, speakerName),
                    style = MaterialTheme.typography.headlineLarge,
                    color = themeTextDark(),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(SpacingTokens.xxl))

        // 波形可视化
        if (!isRecording) {
            Text(
                text = stringResource(R.string.voiceprint_enroll_dian_ji_an_niu_kai_shi_lu_yin),
                style = MaterialTheme.typography.bodyLarge,
                color = themeTextGrey(),
            )
        }
        Spacer(Modifier.height(SpacingTokens.lg))
        WaveformBars(
            bars = waveformBars,
            color = if (isRecording) CoralRed else themeTextGrey().copy(alpha = 0.4f),
        )
        Spacer(Modifier.height(SpacingTokens.sm))
        Text(
            text = if (isRecording) stringResource(R.string.voiceprint_enroll_zheng_zai_lu_yin) else stringResource(R.string.voiceprint_enroll_deng_dai_kai_shi),
            style = MaterialTheme.typography.bodyMedium,
            color = if (isRecording) CoralRed else themeTextGrey(),
        )

        Spacer(Modifier.height(SpacingTokens.xxl))

        // 大圆录音按钮
        Box(
            modifier = Modifier
                .size(SizeTokens.recordButtonSize)
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
                    contentDescription = stringResource(R.string.voiceprint_enroll_ting_zhi_lu_yin),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(SizeTokens.icon2xl),
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = stringResource(R.string.cd_start_recording),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(SizeTokens.icon2xl),
                )
            }
        }
        Spacer(Modifier.height(SpacingTokens.sm))
        Text(
            text = if (isRecording) stringResource(R.string.voiceprint_enroll_dian_ji_ting_zhi) else stringResource(R.string.voiceprint_enroll_dian_ji_lu_yin),
            style = MaterialTheme.typography.bodyLarge,
            color = themeTextGrey(),
        )
    }
}

@Composable
private fun CompletionStep(
    speakerName: String,
    sampleCount: Int,
    isProcessing: Boolean = false,
    isSherpaAvailable: Boolean = false,
    onDone: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(SizeTokens.recordButtonSize)
                .clip(CircleShape)
                .background(if (isProcessing) AccentBlue else SuccessGreen),
            contentAlignment = Alignment.Center,
        ) {
            if (isProcessing) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(SizeTokens.iconButtonSize),
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(SizeTokens.iconButtonSize),
                )
            }
        }
        Spacer(Modifier.height(SpacingTokens.xl))
        Text(
            text = if (isProcessing) stringResource(R.string.voiceprint_enroll_zheng_zai_ti_qu_sheng_wen_te) else stringResource(R.string.voiceprint_enroll_sheng_wen_zhu_ce_wan_cheng),
            style = MaterialTheme.typography.headlineLarge,
            color = themeTextDark(),
        )
        Spacer(Modifier.height(SpacingTokens.sm))
        Text(
            text = stringResource(R.string.recording_speaker, speakerName),
            style = MaterialTheme.typography.titleSmall,
            color = themeTextDark(),
        )
        Spacer(Modifier.height(SpacingTokens.xs))
        Text(
            text = stringResource(R.string.voiceprint_enroll_yi_lu_zhi_1_ge_yang_ben, sampleCount),
            style = MaterialTheme.typography.bodyMedium,
            color = themeTextGrey(),
        )
        Spacer(Modifier.height(SpacingTokens.xs))
        Text(
            text = if (isProcessing) {
                stringResource(R.string.voiceprint_enroll_zheng_zai_shi_yong_sherpa)
            } else if (isSherpaAvailable) {
                stringResource(R.string.voiceprint_enroll_te_zheng_ti_qu_sherpa_onnx)
            } else {
                stringResource(R.string.voiceprint_enroll_te_zheng_ti_qu_tong_ji_te)
            },
            style = MaterialTheme.typography.bodySmall,
            color = themeTextGrey().copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(SpacingTokens.xxl))
        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth().height(SizeTokens.buttonHeightLg),
            shape = ShapeTokens.smallShape,
            enabled = !isProcessing,
        ) {
            Text(stringResource(R.string.action_done), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun WaveformBars(
    bars: List<Float>,
    color: Color,
    barWidth: androidx.compose.ui.unit.Dp = SizeTokens.waveformBarWidth,
    barSpacing: androidx.compose.ui.unit.Dp = SizeTokens.waveformBarSpacing,
    maxHeight: androidx.compose.ui.unit.Dp = SizeTokens.voiceprintWaveformMaxHeight,
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
                    .clip(ShapeTokens.extraSmallShape)
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
