package top.hsyscn.opedrgent.ui.state

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.settings.ApiSettings
import top.hsyscn.opedrgent.stt.AndroidSpeechRecognizer
import top.hsyscn.opedrgent.stt.AudioProcessor
import top.hsyscn.opedrgent.stt.AsrManager
import top.hsyscn.opedrgent.stt.EngineType
import top.hsyscn.opedrgent.stt.ModelManager
import top.hsyscn.opedrgent.stt.ModelType
import top.hsyscn.opedrgent.stt.RecognitionMode
import top.hsyscn.opedrgent.stt.SherpaOnnxEngine
import top.hsyscn.opedrgent.stt.SpeechEngine
import top.hsyscn.opedrgent.stt.SttConfig
import top.hsyscn.opedrgent.stt.SttResult
import top.hsyscn.opedrgent.stt.StreamingRecognitionState
import top.hsyscn.opedrgent.utils.DebugLog

/**
 * STT（语音转文字）状态管理器。
 *
 * 负责封装文件转录、实时识别、模型下载、流式 ASR 等状态与业务逻辑，
 * 使 [top.hsyscn.opedrgent.ui.MainViewModel] 不再直接持有 STT 引擎与任务协程。
 */
class SttStateManager(
    private val app: Application,
    private val apiSettings: ApiSettings,
    private val coroutineScope: CoroutineScope,
    private val asrManager: AsrManager,
) {

    private val _sttProgress = MutableStateFlow(SttProgressState.IDLE)
    val sttProgress: StateFlow<SttProgressState> = _sttProgress.asStateFlow()

    private val _sttUiState = MutableStateFlow<SttUiState>(SttUiState.Idle)
    val sttUiState: StateFlow<SttUiState> = _sttUiState.asStateFlow()

    private val _sttResult = MutableStateFlow<SttResult?>(null)
    val sttResult: StateFlow<SttResult?> = _sttResult.asStateFlow()

    private val _sttHistory = MutableStateFlow<List<SttResult>>(emptyList())
    val sttHistory: StateFlow<List<SttResult>> = _sttHistory.asStateFlow()

    private val _sttError = MutableStateFlow<String?>(null)
    val sttError: StateFlow<String?> = _sttError.asStateFlow()

    private val _sttEventBus = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val sttEventBus: Flow<String> = _sttEventBus

    private var lastFailedUri: Uri? = null
    private var sherpaOnnxEngine: SherpaOnnxEngine? = null
    private var androidSpeechRecognizer: AndroidSpeechRecognizer? = null

    private var sttEngine: SpeechEngine? = null
    private var sttJob: Job? = null

    // ==================== 统一流式 ASR 状态（跨页面保持） ====================

    private val _asrListening = MutableStateFlow(false)
    val asrListening: StateFlow<Boolean> = _asrListening.asStateFlow()

    private val _asrEvent = Channel<AsrUiEvent>(Channel.BUFFERED)
    val asrEvent: Flow<AsrUiEvent> = _asrEvent.receiveAsFlow()

    private var asrStreamingJob: Job? = null

    /**
     * 启动文件/视频语音转文字。
     */
    fun startSpeechToText(uri: Uri) {
        sttJob?.cancel()
        lastFailedUri = null
        _sttProgress.value = SttProgressState.IDLE
        _sttUiState.value = SttUiState.Idle
        _sttResult.value = null
        _sttError.value = null

        sttJob = coroutineScope.launch {
            var tempWavFile: java.io.File? = null
            try {
                val fileName = getFileNameFromUri(app, uri) ?: "unknown"

                _sttUiState.value = SttUiState.Validating(uri.toString())
                _sttProgress.value = SttProgressState.IDLE

                val (isValid, errorMsg) = withContext(Dispatchers.IO) {
                    AudioProcessor.validateAudioFile(app, uri)
                }
                if (!isValid) {
                    val errorCode = when {
                        errorMsg?.contains("不支持", ignoreCase = true) == true -> ERROR_UNSUPPORTED_FORMAT
                        errorMsg?.contains("文件", ignoreCase = true) == true -> ERROR_FILE_NOT_FOUND
                        errorMsg?.contains("权限", ignoreCase = true) == true -> ERROR_PERMISSION_DENIED
                        else -> ERROR_VALIDATION_FAILED
                    }
                    val suggestion = when (errorCode) {
                        ERROR_UNSUPPORTED_FORMAT -> app.getString(R.string.stt_error_suggestion_unsupported_format)
                        ERROR_PERMISSION_DENIED -> app.getString(R.string.stt_error_suggestion_permission_denied)
                        ERROR_FILE_NOT_FOUND -> app.getString(R.string.stt_error_suggestion_file_not_found)
                        else -> app.getString(R.string.stt_error_suggestion_validation_failed)
                    }
                    val message = errorMsg ?: app.getString(R.string.stt_error_validation_failed)
                    _sttUiState.value = SttUiState.Error(message, errorCode, suggestion)
                    _sttProgress.value = SttProgressState.ERROR
                    _sttError.value = message
                    lastFailedUri = uri
                    _sttEventBus.emit(errorMsg ?: app.getString(R.string.stt_event_validation_failed))
                    return@launch
                }

                // 检查是否需要下载本地模型（在线引擎不需要下载）
                val useOnlineAsr = (apiSettings.getSttEngine() == "mimo" || apiSettings.getSttEngine() == "stepaudio") && apiSettings.hasApiKey()
                if (!useOnlineAsr) {
                    val availableModel = ModelManager.getAnyDownloadedModel(app)
                    if (availableModel == null) {
                        val recommendedModel = ModelManager.getRecommendedModel(app)
                        val modelInfo = ModelManager.AVAILABLE_MODELS.find { it.type == recommendedModel }
                        val modelSizeMb = ((modelInfo?.sizeBytes ?: 0L) / (1024 * 1024)).toInt()
                        _sttUiState.value = SttUiState.DownloadingModel(0f, modelSizeMb)
                        _sttProgress.value = SttProgressState.DOWNLOADING_MODEL

                        ModelManager.downloadModel(app, recommendedModel).collect { progress ->
                            when (progress) {
                                is ModelManager.DownloadProgress.Downloading -> {
                                    _sttUiState.value = SttUiState.DownloadingModel(progress.progress, modelSizeMb)
                                    DebugLog.d("SttStateManager: 模型下载进度 ${(progress.progress * 100).toInt()}%")
                                }
                                is ModelManager.DownloadProgress.SourceSwitch -> {
                                    DebugLog.d("SttStateManager: 切换下载源 ${progress.sourceName} (${progress.current}/${progress.total})")
                                }
                                is ModelManager.DownloadProgress.Complete -> { /* done */ }
                                is ModelManager.DownloadProgress.Error -> {
                                    _sttUiState.value = SttUiState.Error(
                                        app.getString(R.string.stt_error_download_failed),
                                        ERROR_DOWNLOAD_FAILED,
                                        app.getString(R.string.stt_error_download_failed_suggestion),
                                    )
                                    _sttProgress.value = SttProgressState.ERROR
                                    _sttError.value = app.getString(R.string.stt_status_download_failed)
                                    lastFailedUri = uri
                                    return@collect
                                }
                            }
                        }
                        if (_sttProgress.value == SttProgressState.ERROR) return@launch
                    }
                }

                _sttUiState.value = SttUiState.DecodingAudio(0f, fileName)
                _sttProgress.value = SttProgressState.EXTRACTING_AUDIO

                // 检测是否为视频文件，如果是则先提取音频轨
                val mimeType = app.contentResolver.getType(uri) ?: ""
                val isVideo = mimeType.startsWith("video/")
                val audioMeta = withContext(Dispatchers.IO) { AudioProcessor.getAudioMetadata(app, uri) }
                DebugLog.i("STT: 音频元数据 duration=${audioMeta?.durationMs}ms file=$fileName mimeType=$mimeType isVideo=$isVideo")

                // 如果是视频文件，先解码音频轨为 PCM 并保存为临时 WAV
                val effectiveUri: Uri
                if (isVideo) {
                    DebugLog.i("STT: 检测到视频文件，正在提取音频轨...")
                    _sttUiState.value = SttUiState.DecodingAudio(0.3f, app.getString(R.string.stt_status_extracting_video_audio))
                    val pcmData = withContext(Dispatchers.IO) {
                        AudioProcessor.decodeVideoAudioToPcm(app, uri)
                    }
                    if (pcmData == null || pcmData.first.isEmpty()) {
                        _sttUiState.value = SttUiState.Error(
                            app.getString(R.string.stt_error_video_audio_extract_failed),
                            ERROR_VIDEO_AUDIO_EXTRACT_FAILED,
                            app.getString(R.string.stt_error_video_audio_extract_suggestion),
                        )
                        _sttProgress.value = SttProgressState.ERROR
                        _sttError.value = app.getString(R.string.stt_error_video_audio_extract_failed)
                        lastFailedUri = uri
                        _sttEventBus.emit(app.getString(R.string.stt_event_video_audio_extract_failed))
                        return@launch
                    }
                    tempWavFile = java.io.File(app.cacheDir, "video_audio_${System.currentTimeMillis()}.wav")
                    AudioProcessor.saveAsWav(pcmData.first, pcmData.second, tempWavFile.absolutePath)
                    effectiveUri = Uri.fromFile(tempWavFile)
                    DebugLog.i("STT: 视频音频提取完成 ${tempWavFile.length() / 1024}KB")
                } else {
                    tempWavFile = null
                    effectiveUri = uri
                }

                _sttUiState.value = SttUiState.Recognizing(0f, 0, audioMeta?.let { Math.ceil(it.durationMs / 30000.0).toInt() } ?: 1)
                _sttProgress.value = SttProgressState.RECOGNIZING

                // 使用 AsrManager 统一引擎转录
                val result = withContext(Dispatchers.IO) {
                    DebugLog.i("STT: 使用 AsrManager 统一引擎转录")
                    if (tempWavFile != null) {
                        // 视频文件：用文件路径方式转录（已转为 WAV）
                        asrManager.transcribeFile(tempWavFile.absolutePath)
                    } else {
                        asrManager.transcribeFile(effectiveUri)
                    }
                }
                val enrichedResult = result

                _sttResult.value = enrichedResult
                _sttUiState.value = SttUiState.Done(enrichedResult)
                _sttProgress.value = SttProgressState.DONE

                _sttHistory.value = listOf(enrichedResult) + _sttHistory.value.take(49)
                _sttEventBus.emit(app.getString(R.string.stt_success_transcription, result.text.length))

                DebugLog.i("STT: 转录完成 text=${result.text.take(50)}... confidence=${result.confidence} duration=${result.durationMs}ms")
            } catch (e: CancellationException) {
                DebugLog.i("STT: 用户取消转录")
                _sttUiState.value = SttUiState.Idle
                _sttProgress.value = SttProgressState.IDLE
            } catch (e: OutOfMemoryError) {
                DebugLog.e("STT: 内存不足 [OOM] ${e.message}", e)
                _sttUiState.value = SttUiState.Error(
                    app.getString(R.string.stt_error_oom),
                    ERROR_OUT_OF_MEMORY,
                    app.getString(R.string.stt_error_oom_suggestion),
                )
                _sttProgress.value = SttProgressState.ERROR
                _sttError.value = app.getString(R.string.stt_error_oom)
                lastFailedUri = uri
                _sttEventBus.tryEmit(app.getString(R.string.stt_event_oom_failed))
            } catch (e: Exception) {
                val errorCode = when (e) {
                    is java.io.IOException -> ERROR_IO_ERROR
                    is java.lang.IllegalStateException -> ERROR_ENGINE_ERROR
                    else -> ERROR_UNKNOWN_ERROR
                }
                val suggestion = when (errorCode) {
                    ERROR_IO_ERROR -> app.getString(R.string.stt_error_io_suggestion)
                    ERROR_OUT_OF_MEMORY -> app.getString(R.string.stt_error_oom_suggestion)
                    ERROR_ENGINE_ERROR -> app.getString(R.string.stt_error_engine_suggestion)
                    else -> app.getString(R.string.stt_error_unknown_suggestion)
                }
                DebugLog.e("STT: 转录异常 [${errorCode}] ${e.message}", e)
                _sttUiState.value = SttUiState.Error(
                    e.message ?: app.getString(R.string.stt_error_unknown),
                    errorCode,
                    suggestion,
                )
                _sttProgress.value = SttProgressState.ERROR
                _sttError.value = e.message ?: app.getString(R.string.stt_error_unknown)
                lastFailedUri = uri
                _sttEventBus.tryEmit(app.getString(R.string.stt_event_unknown_failed, e.message ?: ""))
            } finally {
                // 清理临时文件（无论成功或失败）
                tempWavFile?.delete()
                // 引擎生命周期由 AsrManager 管理，此处无需手动关闭
            }
        }
    }

    private fun getFileNameFromUri(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) { null }
    }

    fun clearSttResult() {
        _sttResult.value = null
        _sttError.value = null
        _sttProgress.value = SttProgressState.IDLE
        _sttUiState.value = SttUiState.Idle
    }

    /**
     * 设置错误文本，供外部（如 MainViewModel.sendSttResultToLlm）报告业务层错误。
     */
    fun setError(message: String) {
        _sttError.value = message
    }

    fun cancelStt() {
        sttJob?.cancel()
        sttJob = null
        _sttProgress.value = SttProgressState.IDLE
        _sttUiState.value = SttUiState.Idle
        sttEngine?.close()
        sttEngine = null
        // 也停止 asrManager 中正在运行的转录任务
        asrManager.stopStreaming()
    }

    fun retryLastStt() {
        val uri = lastFailedUri ?: run {
            _sttError.value = app.getString(R.string.stt_error_no_failed_record)
            return
        }
        startSpeechToText(uri)
    }

    fun startRealtimeSpeechRecognition() {
        sttJob?.cancel()
        _sttProgress.value = SttProgressState.IDLE
        _sttUiState.value = SttUiState.Idle
        _sttResult.value = null
        _sttError.value = null

        sttJob = coroutineScope.launch {
            try {
                _sttUiState.value = SttUiState.Recognizing(0f, 0, 1)
                _sttProgress.value = SttProgressState.RECOGNIZING

                val recognizer = AndroidSpeechRecognizer(app, SttConfig(mode = RecognitionMode.STREAMING))
                sttEngine = recognizer
                androidSpeechRecognizer = recognizer

                recognizer.startStreamingRecognition().collect { state ->
                    when (state) {
                        is StreamingRecognitionState.Recognizing -> {
                            _sttResult.value = SttResult(
                                text = state.partialText,
                                engineType = EngineType.ANDROID_SPEECH_RECOGNIZER,
                            )
                        }
                        is StreamingRecognitionState.FinalResult -> {
                            val finalResult = SttResult(
                                text = state.text,
                                engineType = EngineType.ANDROID_SPEECH_RECOGNIZER,
                            )
                            _sttResult.value = finalResult
                            _sttUiState.value = SttUiState.Done(finalResult)
                            _sttProgress.value = SttProgressState.DONE
                            _sttHistory.value = listOf(finalResult) + _sttHistory.value.take(49)
                        }
                        is StreamingRecognitionState.Error -> {
                            _sttUiState.value = SttUiState.Error(
                                state.message,
                                ERROR_RECOGNITION_ERROR,
                                app.getString(R.string.stt_error_realtime_suggestion),
                            )
                            _sttProgress.value = SttProgressState.ERROR
                            _sttError.value = state.message
                        }
                        is StreamingRecognitionState.Listening -> {
                            _sttProgress.value = SttProgressState.RECOGNIZING
                        }
                        is StreamingRecognitionState.Stopped -> {
                            val currentResult = _sttResult.value
                            if (currentResult != null && currentResult.text.isNotBlank()) {
                                _sttUiState.value = SttUiState.Done(currentResult)
                            }
                            _sttProgress.value = SttProgressState.DONE
                        }
                    }
                }
            } catch (e: CancellationException) {
                DebugLog.i("STT: 用户取消实时录音")
                _sttUiState.value = SttUiState.Idle
                _sttProgress.value = SttProgressState.IDLE
            } catch (e: Exception) {
                DebugLog.e("STT: 实时录音异常 ${e.message}", e)
                _sttUiState.value = SttUiState.Error(
                    e.message ?: app.getString(R.string.stt_error_realtime),
                    ERROR_REALTIME_ERROR,
                    app.getString(R.string.stt_error_realtime_suggestion),
                )
                _sttProgress.value = SttProgressState.ERROR
                _sttError.value = e.message ?: app.getString(R.string.stt_error_realtime)
            } finally {
                androidSpeechRecognizer = null
                sttEngine?.close()
                sttEngine = null
            }
        }
    }

    fun stopSttRecognition() {
        try {
            sttEngine?.stopStreamingRecognition()
        } catch (_: Exception) {}
        sttJob?.cancel()
        sttJob = null
        androidSpeechRecognizer = null
        if (_sttProgress.value != SttProgressState.DONE && _sttProgress.value != SttProgressState.ERROR) {
            _sttProgress.value = SttProgressState.IDLE
            _sttUiState.value = SttUiState.Idle
        }
    }

    /**
     * 启动统一 ASR 流式识别（用于输入栏麦克风按钮）。
     * 使用 AsrManager 统一引擎，根据用户设置自动选择 MiMo/Sherpa。
     */
    suspend fun startUnifiedStreamingAsr(): Flow<StreamingRecognitionState> {
        asrManager.ensureInitialized()
        return asrManager.startStreaming()
    }

    /**
     * 停止统一 ASR 流式识别。
     */
    fun stopUnifiedStreamingAsr() {
        asrManager.stopStreaming()
    }

    /** 切换流式 ASR：未在听则开始，正在听则停止。 */
    fun toggleStreamingAsr() {
        if (_asrListening.value) stopStreamingAsr() else startStreamingAsrCollection()
    }

    /**
     * 启动流式 ASR 识别。job 绑定传入的 coroutineScope，跨页面导航不中断。
     * 识别结果通过 [asrEvent] 推送，UI 层观察 [asrListening] 更新麦克风按钮状态。
     */
    fun startStreamingAsrCollection() {
        if (_asrListening.value) return
        asrStreamingJob?.cancel()
        _asrListening.value = true
        asrStreamingJob = coroutineScope.launch {
            try {
                val flow = startUnifiedStreamingAsr()
                flow.collect { state ->
                    when (state) {
                        is StreamingRecognitionState.FinalResult -> {
                            if (state.text.isNotBlank()) {
                                _asrEvent.trySend(AsrUiEvent.FinalText(state.text))
                            } else {
                                _asrEvent.trySend(AsrUiEvent.EmptyResult)
                            }
                            _asrListening.value = false
                        }
                        is StreamingRecognitionState.Error -> {
                            _asrEvent.trySend(AsrUiEvent.Error(state.message))
                            _asrListening.value = false
                        }
                        else -> {}
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _asrEvent.trySend(AsrUiEvent.Error("语音识别失败: ${e.message}"))
                _asrListening.value = false
            } finally {
                _asrListening.value = false
            }
        }
    }

    /** 停止流式 ASR 识别并释放底层引擎。 */
    fun stopStreamingAsr() {
        asrStreamingJob?.cancel()
        asrStreamingJob = null
        stopUnifiedStreamingAsr()
        _asrListening.value = false
    }

    fun copyToClipboard(text: String, showToast: Boolean = true) {
        try {
            val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("opedrgent_stt", text))
            DebugLog.i("STT: 已复制到剪贴板 length=${text.length}")
            if (showToast) {
                _sttEventBus.tryEmit(app.getString(R.string.stt_success_copied, text.length))
            }
        } catch (e: Exception) {
            DebugLog.e("STT: 复制到剪贴板失败 ${e.message}", e)
            _sttError.value = app.getString(R.string.stt_error_clipboard_copy, e.message ?: "")
        }
    }

    fun pasteFromClipboard(): String? {
        return try {
            val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).coerceToText(app).toString().takeIf { it.isNotBlank() }
            } else {
                null
            }
        } catch (e: SecurityException) {
            DebugLog.w("STT: 剪贴板读取被拒绝（可能需要 READ_CLIPBOARD 权限）")
            _sttError.value = app.getString(R.string.stt_error_clipboard_read)
            null
        } catch (e: Exception) {
            DebugLog.e("STT: 读取剪贴板失败 ${e.message}", e)
            null
        }
    }

    fun checkModelDownloaded(): Boolean {
        val model = ModelManager.getRecommendedModel(app)
        return ModelManager.isModelDownloaded(app, model)
    }

    fun downloadModel(modelType: ModelType): Job {
        val modelInfo = ModelManager.AVAILABLE_MODELS.find { it.type == modelType }
        val modelSizeMb = ((modelInfo?.sizeBytes ?: 0L) / (1024 * 1024)).toInt()
        _sttUiState.value = SttUiState.DownloadingModel(0f, modelSizeMb)
        _sttProgress.value = SttProgressState.DOWNLOADING_MODEL

        return coroutineScope.launch(Dispatchers.IO) {
            try {
                ModelManager.downloadModel(app, modelType).collect { progress ->
                    when (progress) {
                        is ModelManager.DownloadProgress.Downloading -> {
                            withContext(Dispatchers.Main) {
                                _sttUiState.value = SttUiState.DownloadingModel(progress.progress, modelSizeMb)
                                DebugLog.d("SttStateManager: 模型下载进度 ${(progress.progress * 100).toInt()}%")
                            }
                        }
                        is ModelManager.DownloadProgress.SourceSwitch -> {
                            DebugLog.d("SttStateManager: 切换下载源 ${progress.sourceName} (${progress.current}/${progress.total})")
                        }
                        is ModelManager.DownloadProgress.Complete -> { /* done */ }
                        is ModelManager.DownloadProgress.Error -> {
                            withContext(Dispatchers.Main) {
                                _sttUiState.value = SttUiState.Error(
                                    app.getString(R.string.stt_error_download_failed),
                                    ERROR_DOWNLOAD_FAILED,
                                    app.getString(R.string.stt_error_download_failed_suggestion),
                                )
                                _sttProgress.value = SttProgressState.ERROR
                                _sttError.value = app.getString(R.string.stt_status_download_failed)
                            }
                            return@collect
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    initializeSttEngine(modelType)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    DebugLog.e("SttStateManager: 模型下载异常 ${e.message}", e)
                    val exceptionMessage = app.getString(R.string.stt_error_download_exception, e.message ?: "")
                    _sttUiState.value = SttUiState.Error(
                        exceptionMessage,
                        ERROR_DOWNLOAD_FAILED,
                        app.getString(R.string.stt_error_download_failed_suggestion),
                    )
                    _sttProgress.value = SttProgressState.ERROR
                    _sttError.value = exceptionMessage
                }
            }
        }
    }

    private fun initializeSttEngine(modelType: ModelType) {
        val modelDir = ModelManager.getModelPath(app, modelType)
        if (modelDir != null && modelDir.exists()) {
            try {
                val engine = SherpaOnnxEngine(app, SttConfig(modelType = modelType))
                engine.initialize(modelDir)
                sherpaOnnxEngine = engine
                _sttUiState.value = SttUiState.Idle
                _sttProgress.value = SttProgressState.IDLE
                _sttEventBus.tryEmit(app.getString(R.string.stt_success_model_initialized, modelType.name))
                DebugLog.i("SttStateManager: STT 引擎初始化成功 model=$modelType")
            } catch (e: Exception) {
                DebugLog.e("SttStateManager: STT 引擎初始化失败 ${e.message}", e)
                _sttUiState.value = SttUiState.Error(
                    app.getString(R.string.stt_status_engine_init_failed),
                    ERROR_ENGINE_INIT_FAILED,
                    app.getString(R.string.stt_status_suggestion_delete_cache),
                )
                _sttProgress.value = SttProgressState.ERROR
                _sttError.value = app.getString(R.string.stt_status_engine_init_failed)
            }
        } else {
            _sttUiState.value = SttUiState.Error(
                app.getString(R.string.stt_status_model_not_found),
                ERROR_MODEL_NOT_FOUND,
                app.getString(R.string.stt_status_suggestion_download_first),
            )
            _sttProgress.value = SttProgressState.ERROR
            _sttError.value = app.getString(R.string.stt_status_model_not_found)
        }
    }

    fun getRecommendedModel(): ModelType {
        return ModelManager.getRecommendedModel(app)
    }

    companion object {
        const val ERROR_UNSUPPORTED_FORMAT = "UNSUPPORTED_FORMAT"
        const val ERROR_FILE_NOT_FOUND = "FILE_NOT_FOUND"
        const val ERROR_PERMISSION_DENIED = "PERMISSION_DENIED"
        const val ERROR_VALIDATION_FAILED = "VALIDATION_FAILED"
        const val ERROR_DOWNLOAD_FAILED = "DOWNLOAD_FAILED"
        const val ERROR_VIDEO_AUDIO_EXTRACT_FAILED = "VIDEO_AUDIO_EXTRACT_FAILED"
        const val ERROR_OUT_OF_MEMORY = "OUT_OF_MEMORY"
        const val ERROR_IO_ERROR = "IO_ERROR"
        const val ERROR_ENGINE_ERROR = "ENGINE_ERROR"
        const val ERROR_UNKNOWN_ERROR = "UNKNOWN_ERROR"
        const val ERROR_RECOGNITION_ERROR = "RECOGNITION_ERROR"
        const val ERROR_REALTIME_ERROR = "REALTIME_ERROR"
        const val ERROR_ENGINE_INIT_FAILED = "ENGINE_INIT_FAILED"
        const val ERROR_MODEL_NOT_FOUND = "MODEL_NOT_FOUND"
    }

    /**
     * 释放资源：取消任务、关闭引擎。应在宿主 ViewModel 清理时调用。
     */
    fun close() {
        sttJob?.cancel()
        sttJob = null
        asrStreamingJob?.cancel()
        asrStreamingJob = null
        _asrEvent.close()

        sttEngine?.close()
        sttEngine = null
        asrManager.close()
    }
}
