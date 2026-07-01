package top.hsyscn.opedrgent.stt

import android.content.Context
import android.net.Uri
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.utils.DebugLog
import top.hsyscn.opedrgent.settings.ApiSettings
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.roundToInt

class SherpaOnnxEngine(
    private val context: Context,
    private val config: SttConfig = SttConfig(),
) : SpeechEngine {

    private val vocabularyStore = VocabularyStore(context)
    private val apiSettings = ApiSettings(context)

    companion object {
        private const val TAG = "SherpaOnnxEngine"
        private const val TARGET_SAMPLE_RATE = 16000
        private const val DEFAULT_SEGMENT_SAMPLES = TARGET_SAMPLE_RATE * 30 // 30秒
        private const val STREAMING_CHUNK_MS = 200L       // 后台轮询间隔
        private const val WAV_HEADER_SIZE = 44

        // 伪流式分段参数
        private const val MIN_CHUNK_MS = 1500L            // 最少累积 1.5 秒才识别
        private const val MAX_CHUNK_MS = 4000L            // 超过 4 秒强制提交
        private const val CHUNK_OVERLAP_MS = 200L         // 段间重叠 200ms 避免断词
        private const val MAX_PENDING_BUFFER_SECONDS = 60 // 缓冲区上限 60 秒
        private val MAX_PENDING_BUFFER_SAMPLES = TARGET_SAMPLE_RATE * MAX_PENDING_BUFFER_SECONDS

        /**
         * 检测最佳推理后端。
         * sherpa-onnx 的 CPU (XNNPACK) 后端在大多数 Android 设备上是最稳定且快速的选项。
         * NNAPI 在很多设备上有兼容性问题或反而更慢，因此默认使用 CPU。
         */
        fun resolveBestProvider(): Pair<String, String> {
            return Pair("cpu", "cpu")
        }
    }

    /** 离线识别器（用于文件转录和伪流式识别） */
    private var offlineRecognizer: OfflineRecognizer? = null
    /** 流式识别器（用于 STREAMING_PARAFORMER 模型的真流式识别） */
    private var streamingRecognizer: StreamingRecognizer? = null
    /** 当前是否为流式模型 */
    private var isStreamingModel = false
    /** 是否使用真流式识别器（OnlineRecognizer），供外部查询 */
    val isStreamingEngine: Boolean get() = isStreamingModel && streamingRecognizer?.isActive == true
    private var _isInitialized = AtomicBoolean(false)
    private var streamingActive = AtomicBoolean(false)
    private var currentModelDir: File? = null

    // ==================== 伪流式识别状态 ====================
    /** pendingBuffer 访问锁 */
    private val bufferLock = Any()
    /** 待识别的音频缓冲区（固定大小，通过 bufferLock 同步访问） */
    private val pendingBuffer = FloatArray(MAX_PENDING_BUFFER_SAMPLES)
    /** 缓冲区中已写入的采样点数量 */
    @Volatile
    private var pendingBufferSize = 0
    /** 已确认的文本（分段提交后追加，不再变化） */
    @Volatile
    private var confirmedText = StringBuilder()
    /** 流式识别结果通道（由 startStreamingRecognition 创建） */
    @Volatile
    private var streamingChannel: kotlinx.coroutines.channels.SendChannel<StreamingRecognitionState>? = null
    /** 后台识别协程 */
    @Volatile
    private var streamingJob: Job? = null

    override val engineType = EngineType.SHERPA_ONNX

    override val isAvailable: Boolean
        get() = _isInitialized.get() && (offlineRecognizer != null || streamingRecognizer?.isActive == true)

    fun initialize(modelDir: File): Boolean {
        if (_isInitialized.get()) {
            DebugLog.w(TAG, "引擎已初始化，跳过重复初始化")
            return true
        }
        return try {
            DebugLog.i(TAG, "开始初始化模型 from ${modelDir.absolutePath}")
            DebugLog.i(TAG, "模型类型=${config.modelType.name} 语言=${config.language.name}")

            validateModelFiles(modelDir)

            // 列出模型目录内容帮助诊断
            val files = modelDir.listFiles()
            DebugLog.i(TAG, "模型目录文件列表: ${files?.map { "${it.name}(${it.length() / 1024}KB)" }?.joinToString(", ") ?: "(无法列出)"}")

            val numThreads = resolveOptimalThreadCount()
            DebugLog.i(TAG, "使用线程数=$numThreads")

            val (provider, deviceType) = resolveBestProvider()
            DebugLog.i(TAG, "推理后端: provider=$provider device=$deviceType")

            if (config.modelType == ModelType.STREAMING_PARAFORMER) {
                // 流式 Paraformer 模型：使用 OnlineRecognizer (真流式)
                initializeStreamingRecognizer(modelDir, numThreads, provider)
            } else {
                // 非流式模型：使用 OfflineRecognizer
                initializeOfflineRecognizer(modelDir, numThreads, provider, deviceType)
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "初始化失败: ${e.message}", e)
            _isInitialized.set(false)
            false
        }
    }

    /**
     * 初始化流式 Paraformer 识别器。
     *
     * 使用 OnlineRecognizer 进行真流式识别，支持 acceptWaveform() + decode() 模式。
     * 如果运行时 AAR 不包含 OnlineRecognizer API（stub AAR），则优雅降级到 OfflineRecognizer。
     */
    private fun initializeStreamingRecognizer(
        modelDir: File, numThreads: Int, provider: String,
    ): Boolean {
        val sr = StreamingRecognizer()
        val hrEnabled = ApiSettings(context).isHrEnabled()
        if (StreamingRecognizer.isAvailable() && sr.create(null, modelDir, numThreads, provider, hrEnabled)) {
            streamingRecognizer = sr
            isStreamingModel = true
            currentModelDir = modelDir
            _isInitialized.set(true)
            DebugLog.i(TAG, "流式 Paraformer 识别器初始化成功 (online=true)")
            return true
        }

        // 降级: 运行时不支持 OnlineRecognizer，回退到 OfflineRecognizer 伪流式
        DebugLog.w(TAG, "OnlineRecognizer API 不可用或创建失败，降级到 OfflineRecognizer 伪流式")
        sr.release()
        isStreamingModel = false
        initializeOfflineRecognizer(modelDir, numThreads, provider, provider)
        return _isInitialized.get()
    }

    /**
     * 初始化离线识别器（用于非流式模型和流式模型的降级路径）。
     */
    private fun initializeOfflineRecognizer(
        modelDir: File, numThreads: Int, provider: String, deviceType: String,
    ): Boolean {
        // 注意：模型文件在磁盘绝对路径上，必须传 null 给 assetManager
        // 否则 sherpa-onnx 会尝试用 AAssetManager 打开磁盘文件 -> 崩溃
        // 参考: https://github.com/k2-fsa/sherpa-onnx/issues/2562
        val offlineConfig = buildOfflineRecognizerConfig(modelDir, numThreads, provider, deviceType)
        offlineRecognizer = OfflineRecognizer(null, offlineConfig)

        currentModelDir = modelDir
        _isInitialized.set(true)

        DebugLog.i(TAG, "离线识别器初始化成功 (offline=${offlineRecognizer != null}, streamingFallback=$isStreamingModel)")
        return true
    }

    override suspend fun recognizeFile(uri: Uri): SttResult {
        return withContext(Dispatchers.IO) {
            val startTimeMs = System.currentTimeMillis()
            DebugLog.i(TAG, "开始识别文件(URI): $uri")

            try {
                val tempFile = copyUriToTempFile(uri)
                try {
                    val audioData = if (isWavFile(tempFile)) {
                        decodeAudioFile(tempFile)
                    } else {
                        // 非 WAV 格式（MP3/AAC/FLAC 等）走 MediaCodec 解码
                        val pair = AudioProcessor.decodeToPcm(context, Uri.fromFile(tempFile))
                        pair?.first ?: FloatArray(0)
                    }
                    recognizeFromFloatArray(audioData, startTimeMs)
                } finally {
                    tempFile.delete()
                }
            } catch (e: Exception) {
                DebugLog.e(TAG, "URI 文件识别失败: ${e.message}", e)
                SttResult(
                    text = "", confidence = 0f, segments = emptyList(),
                    durationMs = 0,
                    processingTimeMs = System.currentTimeMillis() - startTimeMs,
                    engineType = EngineType.SHERPA_ONNX,
                    modelUsed = config.modelType.name,
                )
            }
        }
    }

    override suspend fun recognizeFile(filePath: String): SttResult {
        val startTimeMs = System.currentTimeMillis()
        val file = File(filePath)
        DebugLog.i(TAG, "开始识别文件(Path): $filePath (${file.length() / 1024}KB, ext=${file.extension})")

        return withContext(Dispatchers.IO) {
            try {
                val audioData = if (file.extension.lowercase() == "wav") {
                    // WAV 文件用内置解码器（更快）
                    decodeAudioFile(file)
                } else {
                    // 其他格式走 AudioProcessor
                    val pair = AudioProcessor.decodeToPcm(context, Uri.fromFile(file))
                    pair?.first ?: FloatArray(0)
                }
                DebugLog.i(TAG, "音频解码: ${audioData.size} samples (${audioData.size / TARGET_SAMPLE_RATE}s)")
                recognizeFromFloatArray(audioData, startTimeMs)
            } catch (e: Exception) {
                DebugLog.e(TAG, "文件路径识别失败: ${e.message}", e)
                SttResult(
                    text = "", confidence = 0f, segments = emptyList(),
                    durationMs = 0,
                    processingTimeMs = System.currentTimeMillis() - startTimeMs,
                    engineType = EngineType.SHERPA_ONNX,
                    modelUsed = config.modelType.name,
                )
            }
        }
    }

    /**
     * 直接从归一化浮点音频数组进行 ASR 识别。
     * 用于会议转录中按说话人段逐段识别的场景。
     *
     * @param audioData 归一化 [-1.0, 1.0] 浮点数组，采样率必须为 16000Hz
     */
    fun recognizeFloatAudio(audioData: FloatArray): SttResult {
        val startTimeMs = System.currentTimeMillis()
        return try {
            ensureInitialized()
            val totalSamples = audioData.size
            val durationMs = (totalSamples.toFloat() / TARGET_SAMPLE_RATE * 1000).toLong()

            if (totalSamples == 0) return SttResult(
                text = "", segments = emptyList(), durationMs = 0,
                processingTimeMs = System.currentTimeMillis() - startTimeMs,
                engineType = EngineType.SHERPA_ONNX, modelUsed = config.modelType.name,
            )

            val text = if (isStreamingModel && streamingRecognizer?.isActive == true) {
                // 流式模型: 用 OnlineRecognizer 一次性解码整段音频
                streamingRecognizer?.recognize(audioData)?.trim().orEmpty()
            } else {
                // 离线模型: 用 OfflineRecognizer
                val offline = offlineRecognizer ?: return SttResult("", 0f, emptyList(), 0, 0, EngineType.SHERPA_ONNX, config.modelType.name)
                decodeSegment(offline, audioData).trim()
            }

            SttResult(
                text = text,
                confidence = if (text.isNotEmpty()) 1f else 0f,
                segments = if (text.isNotEmpty()) listOf(
                    SttSegment(text = text, startTimeMs = 0, endTimeMs = durationMs, confidence = 1f),
                ) else emptyList(),
                durationMs = durationMs,
                processingTimeMs = System.currentTimeMillis() - startTimeMs,
                engineType = EngineType.SHERPA_ONNX,
                modelUsed = config.modelType.name,
            )
        } catch (e: Exception) {
            DebugLog.e(TAG, "recognizeFloatAudio 失败: ${e.message}", e)
            SttResult("", 0f, emptyList(), 0, System.currentTimeMillis() - startTimeMs, EngineType.SHERPA_ONNX, config.modelType.name)
        }
    }

    /**
     * 启动流式识别（实时语音输入）。
     *
     * - **流式模型 (STREAMING_PARAFORMER)**: 使用 OnlineRecognizer 真流式识别。
     *   音频通过 acceptWaveform() 喂入，后台协程不断调用 decode() 并获取 partial text。
     *   文本随音频实时更新（可回退/修正）。
     *
     * - **非流式模型**: 使用伪流式分段提交策略。
     *   外部通过 [feedAudioData] 持续喂入音频采样点（非阻塞，仅追加到缓冲区），
     *   后台协程定期检查缓冲区，满足条件时取一段音频做离线识别。
     *   识别结果追加到 confirmedText，文本只增长不回退（不闪烁）。
     */
    override fun startStreamingRecognition(): Flow<StreamingRecognitionState> {
        // 流式模型: 使用 OnlineRecognizer 真流式
        if (isStreamingModel && streamingRecognizer?.isActive == true) {
            return startTrueStreamingRecognition()
        }
        // 非流式模型: 使用 OfflineRecognizer 伪流式
        return startPseudoStreamingRecognition()
    }

    /**
     * 真流式识别 — 使用 OnlineRecognizer 的 acceptWaveform + decode 模式。
     *
     * 音频通过 [feedAudioData] 持续喂入，后台协程不断调用 decode() 并获取 partial/final text。
     *
     * **核心特性：模型自我修正（可回退）**
     * OnlineRecognizer 的 getResult() 返回当前段的完整 partial 结果。
     * Paraformer 模型会根据新音频修正之前的预测（如听到后面发现前面是"会议"而非"hui yi"），
     * 这是流式识别的核心优势 —— 文本可以回退/修正，不是只增不减。
     *
     * **Endpoint 分段**：
     * 利用 sherpa-onnx 内置的 endpoint detection 检测自然语音边界（静音后触发），
     * 到达 endpoint 时 reset 流开始新段落。endpoint 之前的文本作为"已确认段"，
     * 但整体输出仍然是模型驱动的完整结果 —— 允许模型在段落内自由修正。
     *
     * **后处理**（StreamingRecognizer.create() 中配置）：
     * - ITN (Inverse Text Normalization): "一百二十三" → "123"（需 itn_zh_number.fst）
     * - 同音字替换: 根据词表矫正同音错字（需 lexicon.txt + replace.fst）
     */
    private fun startTrueStreamingRecognition(): Flow<StreamingRecognitionState> {
        return callbackFlow {
            val sr = streamingRecognizer ?: run {
                close(); return@callbackFlow }
            streamingActive.set(true)
            trySend(StreamingRecognitionState.Listening)
            DebugLog.i(TAG, "真流式识别已启动 (OnlineRecognizer, 允许模型自我修正+回退)")

            // 已结束的段落文本（endpoint 触发后累积）
            val finishedSegments = mutableListOf<String>()
            var lastSentText = ""

            streamingJob = launch(Dispatchers.IO) {
                try {
                    while (isActive && streamingActive.get()) {
                        delay(STREAMING_CHUNK_MS)

                        // 持续解码已喂入的音频
                        while (sr.isReady()) {
                            sr.decode()
                        }

                        // 获取当前段的 partial 结果（模型可能根据新音频修正之前的内容）
                        var pendingText = vocabularyStore.applyVocabulary(sr.getResult().trim())

                        // 检测 endpoint（自然语音边界：足够长的静音后触发）
                        if (sr.isEndpoint() && pendingText.isNotEmpty()) {
                            // 当前段结束 → 保存为已完成段落，reset 流开始新段
                            DebugLog.d(TAG, "Endpoint: 段落完成 [${pendingText.length}字]")
                            finishedSegments.add(pendingText)
                            sr.reset()
                            // reset 后当前段已归档，pendingText 必须清空，
                            // 否则会和 finishedSegments 里的内容重复显示
                            pendingText = ""
                        }

                        // 构建完整显示文本 = 已完成段落 + 当前段（允许当前段自由变化/回退）
                        val displayText = buildString {
                            for ((i, seg) in finishedSegments.withIndex()) {
                                if (i > 0) append(' ')
                                append(seg)
                            }
                            if (pendingText.isNotEmpty()) {
                                if (isNotEmpty()) append(' ')
                                append(pendingText)
                            }
                        }

                        // 有变化才发送（避免无意义的重复渲染）
                        if (displayText != lastSentText && displayText.isNotEmpty()) {
                            trySend(StreamingRecognitionState.Recognizing(displayText))
                            lastSentText = displayText
                        }
                    }

                    // 停止信号：排空剩余音频获取最终结果
                    while (sr.isReady()) { sr.decode() }
                    val finalPending = vocabularyStore.applyVocabulary(sr.getResult().trim())
                    if (finalPending.isNotEmpty()) {
                        finishedSegments.add(finalPending)
                    }
                    val finalText = finishedSegments.joinToString(" ")
                    if (finalText.isNotEmpty()) {
                        trySend(StreamingRecognitionState.FinalResult(finalText))
                        DebugLog.i(TAG, "真流式最终结果: ${finalText.length}字 (${finishedSegments.size}个段落)")
                    } else {
                        trySend(StreamingRecognitionState.Stopped)
                    }
                } catch (e: CancellationException) {
                    DebugLog.i(TAG, "真流式识别被取消")
                    try {
                        while (sr.isReady()) sr.decode()
                        val partial = vocabularyStore.applyVocabulary(sr.getResult().trim())
                        if (partial.isNotEmpty()) finishedSegments.add(partial)
                        val finalText = finishedSegments.joinToString(" ")
                        if (finalText.isNotEmpty()) {
                            trySend(StreamingRecognitionState.FinalResult(finalText))
                        } else {
                            trySend(StreamingRecognitionState.Stopped)
                        }
                    } catch (_: Exception) {
                        trySend(StreamingRecognitionState.Stopped)
                    }
                } catch (e: Exception) {
                    DebugLog.e(TAG, "真流式识别异常: ${e.message}", e)
                    trySend(StreamingRecognitionState.Error("流式识别错误: ${e.message}"))
                } finally {
                    streamingActive.set(false)
                }
            }

            awaitClose {
                streamingActive.set(false)
                streamingJob?.cancel()
                streamingChannel = null
                DebugLog.i(TAG, "真流式识别 Flow 已关闭")
            }
        }
    }

    /**
     * 伪流式识别 — 使用 OfflineRecognizer 分段提交策略。
     *
     * - 外部通过 [feedAudioData] 持续喂入音频采样点（非阻塞，仅追加到缓冲区）
     * - 后台协程定期检查缓冲区，满足条件时取一段音频做离线识别
     * - 识别结果追加到 confirmedText，文本只增长不回退（不闪烁）
     * - 超过 MAX_CHUNK_MS 时强制提交当前段，避免缓冲区无限增长
     * - 停止时识别剩余音频，输出最终结果
     */
    private fun startPseudoStreamingRecognition(): Flow<StreamingRecognitionState> {
        return callbackFlow {
            if (!_isInitialized.get() || offlineRecognizer == null) {
                trySend(StreamingRecognitionState.Error("引擎未初始化，请先调用 initialize()"))
                close()
                return@callbackFlow
            }
            val recognizer = offlineRecognizer!!

            // 重置流式状态
            synchronized(bufferLock) { pendingBufferSize = 0 }
            confirmedText = StringBuilder()
            streamingActive.set(true)
            streamingChannel = this

            trySend(StreamingRecognitionState.Listening)
            DebugLog.i(TAG, "伪流式识别已启动 (minChunk=${MIN_CHUNK_MS}ms, maxChunk=${MAX_CHUNK_MS}ms)")

            // 启动后台识别协程
            streamingJob = launch(Dispatchers.IO) {
                try {
                    while (isActive && streamingActive.get()) {
                        delay(STREAMING_CHUNK_MS)

                        val chunkToRecognize: FloatArray
                        val bufferMs: Long
                        synchronized(bufferLock) {
                            bufferMs = pendingBufferSize.toLong() * 1000L / TARGET_SAMPLE_RATE

                            if (bufferMs < MIN_CHUNK_MS) continue

                            // 取出缓冲区进行识别
                            chunkToRecognize = FloatArray(pendingBufferSize)
                            System.arraycopy(pendingBuffer, 0, chunkToRecognize, 0, pendingBufferSize)
                            pendingBufferSize = 0
                        }

                        // 识别当前段
                        val chunkText = decodeSegment(recognizer, chunkToRecognize).trim()

                        if (chunkText.isNotEmpty()) {
                            if (confirmedText.isNotEmpty()) {
                                confirmedText.append(" ")
                            }
                            confirmedText.append(chunkText)
                            val displayText = vocabularyStore.applyVocabulary(confirmedText.toString())
                            trySend(StreamingRecognitionState.Recognizing(displayText))
                            DebugLog.d(TAG, "分段识别(${bufferMs}ms): ${chunkText.take(40)} → 累计${confirmedText.length}字")
                        }
                    }

                    // 会话结束：识别剩余缓冲区
                    val remaining = synchronized(bufferLock) {
                        val r = FloatArray(pendingBufferSize)
                        System.arraycopy(pendingBuffer, 0, r, 0, pendingBufferSize)
                        pendingBufferSize = 0
                        r
                    }
                    if (remaining.isNotEmpty()) {
                        val remainingText = decodeSegment(recognizer, remaining).trim()
                        if (remainingText.isNotEmpty()) {
                            if (confirmedText.isNotEmpty()) {
                                confirmedText.append(" ")
                            }
                            confirmedText.append(remainingText)
                        }
                    }

                    val finalText = vocabularyStore.applyVocabulary(confirmedText.toString().trim())
                    if (finalText.isNotEmpty()) {
                        trySend(StreamingRecognitionState.FinalResult(finalText))
                        DebugLog.i(TAG, "伪流式最终结果: ${finalText.length}字")
                    } else {
                        trySend(StreamingRecognitionState.Stopped)
                    }
                } catch (e: CancellationException) {
                    DebugLog.i(TAG, "流式识别被取消")
                    val partial = vocabularyStore.applyVocabulary(confirmedText.toString().trim())
                    if (partial.isNotEmpty()) {
                        trySend(StreamingRecognitionState.FinalResult(partial))
                    } else {
                        trySend(StreamingRecognitionState.Stopped)
                    }
                } catch (e: Exception) {
                    DebugLog.e(TAG, "流式识别异常: ${e.message}", e)
                    trySend(StreamingRecognitionState.Error("流式识别错误: ${e.message}"))
                } finally {
                    streamingActive.set(false)
                }
            }

            awaitClose {
                streamingActive.set(false)
                streamingJob?.cancel()
                streamingChannel = null
                DebugLog.i(TAG, "伪流式识别 Flow 已关闭")
            }
        }
    }

    /**
     * 喂入音频数据到流式识别缓冲区。
     *
     * 非阻塞：仅将音频追加到缓冲区（伪流式），或直接喂入 OnlineRecognizer（真流式）。
     * 音频格式要求：16kHz, mono, float32 [-1.0, 1.0]
     */
    fun feedAudioData(samples: FloatArray) {
        if (!streamingActive.get() || samples.isEmpty()) return

        if (isStreamingModel && streamingRecognizer?.isActive == true) {
            // 真流式: 直接喂入 OnlineRecognizer
            streamingRecognizer?.feedAudio(samples, TARGET_SAMPLE_RATE) ?: return
        } else {
            // 伪流式: 追加到固定大小缓冲区（超过上限时丢弃最旧的数据）
            synchronized(bufferLock) {
                val available = MAX_PENDING_BUFFER_SAMPLES - pendingBufferSize
                if (samples.size > available) {
                    // 缓冲区将满：丢弃最旧的数据，腾出空间
                    val discard = samples.size - available
                    val remaining = pendingBufferSize - discard
                    if (remaining > 0) {
                        System.arraycopy(pendingBuffer, discard, pendingBuffer, 0, remaining)
                    }
                    pendingBufferSize = remaining
                    DebugLog.w(TAG, "缓冲区已满(${MAX_PENDING_BUFFER_SECONDS}s)，丢弃最旧 ${discard / TARGET_SAMPLE_RATE}s 音频")
                }
                System.arraycopy(samples, 0, pendingBuffer, pendingBufferSize, samples.size)
                pendingBufferSize += samples.size
            }
        }
    }

    override fun stopStreamingRecognition() {
        DebugLog.i(TAG, "停止流式识别信号已发送")
        // 设置标志位，后台协程会在下一次轮询时退出并输出最终结果
        streamingActive.set(false)
    }

    override fun close() {
        try {
            stopStreamingRecognition()

            offlineRecognizer?.release()
            offlineRecognizer = null

            streamingRecognizer?.release()
            streamingRecognizer = null
            isStreamingModel = false

            _isInitialized.set(false)
            currentModelDir = null

            DebugLog.i(TAG, "资源已完全释放")
        } catch (e: Exception) {
            DebugLog.e(TAG, "关闭时出错: ${e.message}", e)
        }
    }

    // ==================== 内部实现 ====================

    private suspend fun recognizeFromFloatArray(audioData: FloatArray, startTimeMs: Long): SttResult {
        ensureInitialized()

        val totalSamples = audioData.size
        val durationMs = (totalSamples.toFloat() / TARGET_SAMPLE_RATE * 1000).toLong()

        DebugLog.i(TAG, "音频解码完成: $totalSamples 个采样点, 时长=${AudioProcessor.formatDuration(durationMs)}")

        if (totalSamples == 0) {
            return SttResult(
                text = "", segments = emptyList(), durationMs = durationMs,
                processingTimeMs = System.currentTimeMillis() - startTimeMs,
                engineType = EngineType.SHERPA_ONNX,
                modelUsed = config.modelType.name,
            )
        }

        // 流式模型: 用 OnlineRecognizer 将整段音频作为连续流处理
        if (isStreamingModel && streamingRecognizer?.isActive == true) {
            return recognizeWithStreamingModel(audioData, totalSamples, durationMs, startTimeMs)
        }

        // 离线模型: 分段识别
        return recognizeWithOfflineModel(audioData, totalSamples, durationMs, startTimeMs)
    }

    /**
     * 使用流式模型 (OnlineRecognizer) 识别音频。
     * 将整段音频作为连续流喂入 OnlineRecognizer，不进行分段。
     */
    private fun recognizeWithStreamingModel(
        audioData: FloatArray, totalSamples: Int, durationMs: Long, startTimeMs: Long,
    ): SttResult {
        val sr = streamingRecognizer ?: return SttResult("", 0f, emptyList(), 0, 0, EngineType.SHERPA_ONNX, "")

        // 重置识别器状态（确保不受之前调用的影响）
        sr.reset()

        // 一次性喂入所有音频并解码
        val fullText = sr.recognize(audioData, TARGET_SAMPLE_RATE).trim()
        val processingTimeMs = System.currentTimeMillis() - startTimeMs

        DebugLog.i(TAG, "流式模型识别完成: 文本长度=${fullText.length}, 耗时=${processingTimeMs}ms")

        val resultText = vocabularyStore.applyVocabulary(fullText)
        return SttResult(
            text = resultText,
            confidence = if (resultText.isNotEmpty()) 1f else 0f,
            segments = if (resultText.isNotEmpty()) listOf(
                SttSegment(text = resultText, startTimeMs = 0, endTimeMs = durationMs, confidence = 1f),
            ) else emptyList(),
            durationMs = durationMs,
            processingTimeMs = processingTimeMs,
            engineType = EngineType.SHERPA_ONNX,
            modelUsed = config.modelType.name,
        )
    }

    /**
     * 使用离线模型 (OfflineRecognizer) 识别音频。
     *
     * 分段策略：
     * - 短音频 (<=30秒)：整段一次性识别，避免分段开销
     * - 长音频 (>30秒)：基于静音检测智能分段，每段独立解码后拼接
     *   分段将 attention 复杂度从 O(n²) 降为 O(k×(n/k)²)，显著加速长音频识别
     */
    private fun recognizeWithOfflineModel(
        audioData: FloatArray, totalSamples: Int, durationMs: Long, startTimeMs: Long,
    ): SttResult {
        val recognizer = offlineRecognizer ?: return SttResult("", 0f, emptyList(), 0, 0, EngineType.SHERPA_ONNX, "")

        // 短音频或未启用分段：整段识别
        val segmentEnabled = apiSettings.isSegmentEnabled()
        if (!segmentEnabled || durationMs <= 30_000L || totalSamples <= DEFAULT_SEGMENT_SAMPLES) {
            if (!segmentEnabled && durationMs > 30_000L) {
                DebugLog.i(TAG, "分段识别已关闭，整段识别: ${durationMs}ms (${totalSamples} samples)")
            } else {
                DebugLog.d(TAG, "离线一次性识别: $totalSamples samples (${durationMs}ms)")
            }
            val resultText = decodeSegment(recognizer, audioData).trim()
            val processingTimeMs = System.currentTimeMillis() - startTimeMs
            DebugLog.i(TAG, "离线识别完成: ${resultText.length}字, 耗时=${processingTimeMs}ms")
            return SttResult(
                text = resultText,
                confidence = if (resultText.isNotEmpty()) 1f else 0f,
                segments = if (resultText.isNotEmpty()) listOf(
                    SttSegment(text = resultText, startTimeMs = 0, endTimeMs = durationMs, confidence = 1f),
                ) else emptyList(),
                durationMs = durationMs,
                processingTimeMs = processingTimeMs,
                engineType = EngineType.SHERPA_ONNX,
                modelUsed = config.modelType.name,
            )
        }

        // 长音频：智能分段识别
        DebugLog.i(TAG, "长音频分段识别: ${durationMs}ms (${totalSamples} samples), 开始智能分段")
        val rawSegments = AudioProcessor.segmentAudio(durationMs, AudioProcessor.DEFAULT_SEGMENT_LENGTH_MS, audioData, TARGET_SAMPLE_RATE)

        // 强制最大段长：音乐文件静音少，智能分段可能产生超大段（如273秒）
        // attention 复杂度 O(n²)，必须限制每段不超过 30 秒
        val MAX_SEG_MS = 30_000L
        val segments = mutableListOf<AudioSegment>()
        for (seg in rawSegments) {
            if (seg.endTimeMs - seg.startTimeMs <= MAX_SEG_MS) {
                segments.add(seg.copy(index = segments.size))
            } else {
                var start = seg.startTimeMs
                while (start < seg.endTimeMs) {
                    val end = minOf(start + MAX_SEG_MS, seg.endTimeMs)
                    segments.add(AudioSegment("", start, end, segments.size))
                    start = end
                }
            }
        }
        DebugLog.i(TAG, "分段完成: ${rawSegments.size}段 → 强制切分后${segments.size}段 (每段<=30s)")

        val segmentResults = mutableListOf<SttSegment>()
        val textBuilder = StringBuilder()
        var segDecodeStart = System.currentTimeMillis()

        for ((index, seg) in segments.withIndex()) {
            val startSample = (seg.startTimeMs * TARGET_SAMPLE_RATE / 1000).toInt()
            val endSample = minOf((seg.endTimeMs * TARGET_SAMPLE_RATE / 1000).toInt(), totalSamples)
            if (startSample >= endSample) continue

            val segSamples = audioData.copyOfRange(startSample, endSample)
            val segText = decodeSegment(recognizer, segSamples).trim()
            val segCost = System.currentTimeMillis() - segDecodeStart
            DebugLog.d(TAG, "段${index + 1}/${segments.size}: ${seg.startTimeMs}-${seg.endTimeMs}ms, ${segSamples.size}samples, 耗时${segCost}ms, '${segText.take(40)}'")
            segDecodeStart = System.currentTimeMillis()

            if (segText.isNotEmpty()) {
                if (textBuilder.isNotEmpty()) textBuilder.append(' ')
                textBuilder.append(segText)
                segmentResults.add(SttSegment(text = segText, startTimeMs = seg.startTimeMs, endTimeMs = seg.endTimeMs, confidence = 1f))
            }
        }

        val resultText = textBuilder.toString().trim()
        val processingTimeMs = System.currentTimeMillis() - startTimeMs
        DebugLog.i(TAG, "分段识别完成: ${resultText.length}字, ${segments.size}段, 总耗时=${processingTimeMs}ms (音频${durationMs}ms, 实时比=${String.format("%.2f", durationMs.toDouble() / processingTimeMs)}x)")

        return SttResult(
            text = resultText,
            confidence = if (resultText.isNotEmpty()) 1f else 0f,
            segments = segmentResults,
            durationMs = durationMs,
            processingTimeMs = processingTimeMs,
            engineType = EngineType.SHERPA_ONNX,
            modelUsed = config.modelType.name,
        )
    }

    private fun decodeSegment(recognizer: OfflineRecognizer, samples: FloatArray): String {
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, TARGET_SAMPLE_RATE)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            result.text
        } catch (e: OutOfMemoryError) {
            DebugLog.e(TAG, "解码段时内存不足: ${e.message}")
            ""
        } catch (e: Exception) {
            DebugLog.e(TAG, "解码段异常: ${e.message}")
            ""
        } finally {
            stream.release()
        }
    }

    private fun decodeAudioFile(file: File): FloatArray {
        val extension = file.extension.lowercase()
        return try {
            // 统一使用 AudioProcessor 解码，支持 WAV/PCM/MP3/AAC/FLAC 等所有格式
            val pair = AudioProcessor.decodeToPcm(context, Uri.fromFile(file))
            pair?.first ?: FloatArray(0)
        } catch (e: Exception) {
            DebugLog.e(TAG, "音频解码失败 (${file.name}): ${e.message}", e)
            FloatArray(0)
        }.also { data ->
            DebugLog.i(TAG, "音频解码结果: ${data.size} float samples (${file.name})")
        }
    }

    // ==================== 配置构建 ====================

    private fun buildOfflineRecognizerConfig(
        modelDir: File, numThreads: Int, provider: String, deviceType: String,
    ): OfflineRecognizerConfig {
        val featConfig = FeatureConfig(
            sampleRate = TARGET_SAMPLE_RATE,
            featureDim = 80,
        )

        val modelConfig = when (config.modelType) {
            ModelType.PARAFORMER -> buildParaformerModelConfig(modelDir)
            ModelType.SENSE_VOICE_SMALL -> buildSenseVoiceModelConfig(modelDir)
            ModelType.FUNASR_NANO_INT8 -> buildFunasrNanoModelConfig(modelDir)
            ModelType.STREAMING_PARAFORMER -> buildParaformerModelConfig(modelDir) // 降级到离线模式时使用
        }
        // 将线程数和推理后端传入配置（之前漏掉了，导致始终用默认值 1 线程 + cpu）
        modelConfig.numThreads = numThreads
        modelConfig.provider = provider

        return OfflineRecognizerConfig(
            featConfig = featConfig,
            modelConfig = modelConfig,
        )
    }

    private fun buildParaformerModelConfig(modelDir: File): OfflineModelConfig {
        val modelFile = findModelFile(modelDir, "model.onnx", "model.int8.onnx")
        val tokensFile = findModelFile(modelDir, "tokens.txt", "tokens")

        DebugLog.d(TAG, "Paraformer 模型配置: model=$modelFile tokens=$tokensFile")

        return OfflineModelConfig(
            paraformer = OfflineParaformerModelConfig(model = modelFile),
            tokens = tokensFile,
        )
    }

    private fun buildSenseVoiceModelConfig(modelDir: File): OfflineModelConfig {
        val modelFile = findModelFile(modelDir, "model.onnx", "model.int8.onnx")
        val tokensFile = findModelFile(modelDir, "tokens.txt", "tokens.json", "tokens")

        DebugLog.d(TAG, "SenseVoice 模型配置: model=$modelFile tokens=$tokensFile")

        return OfflineModelConfig(
            senseVoice = OfflineSenseVoiceModelConfig(model = modelFile),
            tokens = tokensFile,
        )
    }

    private fun buildFunasrNanoModelConfig(modelDir: File): OfflineModelConfig {
        val modelFile = findModelFile(modelDir, "model.onnx", "model.int8.onnx")
        val tokensFile = findModelFile(modelDir, "tokens.txt", "tokens")

        DebugLog.d(TAG, "FunASR Nano 模型配置: model=$modelFile tokens=$tokensFile")

        return OfflineModelConfig(
            paraformer = OfflineParaformerModelConfig(model = modelFile),
            tokens = tokensFile,
        )
    }

    private fun findModelFile(dir: File, vararg candidates: String): String {
        for (name in candidates) {
            val f = File(dir, name)
            if (f.exists()) return f.absolutePath
        }
        val found = dir.listFiles()?.map { it.name }?.joinToString(", ") ?: "(空目录)"
        throw IllegalArgumentException(
            "模型目录中未找到所需文件 (候选: ${candidates.joinToString("/")}, 目录内容: [$found])"
        )
    }

    private fun validateModelFiles(modelDir: File) {
        if (!modelDir.exists()) {
            throw IllegalArgumentException("模型目录不存在: ${modelDir.absolutePath}")
        }
        if (!modelDir.isDirectory) {
            throw IllegalArgumentException("模型路径不是目录: ${modelDir.absolutePath}")
        }
        val files = modelDir.listFiles()
        if (files == null || files.isEmpty()) {
            throw IllegalArgumentException("模型目录为空: ${modelDir.absolutePath}。请先下载模型文件。")
        }

        DebugLog.d(TAG, "模型目录包含 ${files.size} 个文件: ${files.joinToString { it.name }}")
    }

    private fun resolveOptimalThreadCount(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        // 流式模型保持保守线程数（避免抢UI资源）；文件转录模式可使用更多线程加速
        val isStreaming = config.modelType == ModelType.STREAMING_PARAFORMER
        val optimal = when {
            cores >= 8 -> if (isStreaming) 4 else 6  // 8核：流式4线程，文件模式6线程（保留1-2核给系统）
            cores >= 4 -> if (isStreaming) 2 else 4  // 4核：流式2线程，文件模式4线程
            else -> 1
        }
        DebugLog.d(TAG, "CPU 核心数=$cores, 模型=${config.modelType.name}, 最优线程数=$optimal (streaming=$isStreaming)")
        return optimal
    }

    private fun ensureInitialized() {
        if (!_isInitialized.get() || (offlineRecognizer == null && streamingRecognizer == null)) {
            throw IllegalStateException("SherpaOnnxEngine 未初始化，请先调用 initialize(modelDir)")
        }
    }

    /** 检查文件是否为 WAV 格式（通过读取 RIFF 头） */
    private fun isWavFile(file: File): Boolean {
        if (file.length() < 12) return false
        return try {
            file.inputStream().use { fis ->
                val header = ByteArray(4)
                fis.read(header)
                val riff = String(header, Charsets.US_ASCII) == "RIFF"
                fis.skip(4) // file size
                val wave = String(ByteArray(4).also { fis.read(it) }, Charsets.US_ASCII) == "WAVE"
                riff && wave
            }
        } catch (_: Exception) { false }
    }

    private suspend fun copyUriToTempFile(uri: Uri): File {
        return withContext(Dispatchers.IO) {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw IOException("无法打开 URI 输入流: $uri")

            // Infer the correct file extension from the URI's MIME type
            // so that downstream format detection works correctly.
            // Previously this always used ".wav", which caused non-WAV files
            // (MP3/FLAC/AAC etc) to be misidentified as WAV by extension checks.
            val ext = inferExtensionFromUri(uri)
            val tempFile = File(context.cacheDir, "stt_temp_${System.currentTimeMillis()}.$ext")
            try {
                tempFile.outputStream().use { output ->
                    inputStream.use { input ->
                        input.copyTo(output)
                    }
                }
                DebugLog.d(TAG, "URI 已复制到临时文件: ${tempFile.absolutePath} (ext=$ext, ${tempFile.length()} bytes)")
                tempFile
            } catch (e: Exception) {
                if (tempFile.exists()) tempFile.delete()
                throw e
            }
        }
    }

    /**
     * Infer the file extension from a content URI's MIME type.
     * Falls back to "wav" if the MIME type cannot be resolved.
     */
    private fun inferExtensionFromUri(uri: Uri): String {
        val mimeType = context.contentResolver.getType(uri) ?: return "wav"
        return when (mimeType) {
            "audio/mpeg" -> "mp3"
            "audio/mp3" -> "mp3"
            "audio/flac" -> "flac"
            "audio/aac", "audio/mp4", "audio/x-m4a" -> "m4a"
            "audio/ogg", "audio/opus" -> "ogg"
            "audio/amr", "audio/amr-wb" -> "amr"
            "audio/wav", "audio/wave", "audio/x-wav" -> "wav"
            "audio/x-raw" -> "pcm"
            "video/mp4" -> "mp4"
            "video/x-matroska", "video/mkv" -> "mkv"
            "video/avi", "video/x-msvideo" -> "avi"
            "video/quicktime" -> "mov"
            "video/webm" -> "webm"
            "video/3gpp" -> "3gp"
            else -> {
                // Try to extract extension from MIME type (e.g., "audio/foo" -> "foo")
                val subtype = mimeType.substringAfter("/", "")
                if (subtype.isNotEmpty() && subtype.length <= 5) subtype else "wav"
            }
        }
    }
}
