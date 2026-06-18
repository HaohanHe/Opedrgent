package top.hsyscn.opedrgent.stt

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import top.hsyscn.opedrgent.settings.ApiSettings
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * MiMO 在线语音识别引擎 — 支持文件转录和实时流式识别。
 *
 * ## 流式模式（Stream 模式）
 *
 * 使用 `recorder_data_source_type=Stream` + SEED 协议实现边录边传。
 * MiMO 的 chat/completions API 不支持原生音频流式输入，因此采用 **伪流式方案**：
 *
 * 1. 外部通过 [feedAudioData] 持续喂入 PCM 浮点采样点
 * 2. 内部缓冲区每 [CHUNK_INTERVAL_MS]（默认 3000ms）或检测到静音时自动发送一个 chunk 到 API
 * 3. 每个 chunk 返回该段的转录文本，通过 Flow emit [StreamingRecognitionState.Recognizing]
 * 4. 调用 [stopStreamingRecognition] 时发送剩余缓冲区，返回 [FinalResult]
 *
 * 这种方式在用户体验上接近真正的流式 ASR：用户说话过程中就能看到文字逐步出现。
 *
 * ## 文件模式
 *
 * 对完整音频文件进行一次性转录，带 VAD 预处理（裁剪首尾静音）。
 */
class MimoAsrEngine(
    private val context: Context,
    private val apiSettings: ApiSettings,
) : SpeechEngine {

    private val vocabularyStore = VocabularyStore(context)

    companion object {
        private const val TAG = "MimoAsrEngine"
        const val MODEL_ID = "mimo-v2.5-asr"

        /** Base64 编码后最大 50MB（官方限制） */
        private const val MAX_BASE64_BYTES = 50 * 1024 * 1024
        /** 原始文件最大 100MB */
        private const val MAX_FILE_BYTES = 100 * 1024 * 1024

        // ---- 流式参数 ----
        /** 每次 API 请求的音频窗口大小（ms），3 秒一 chunk 平衡延迟与准确率 */
        private const val CHUNK_INTERVAL_MS = 3000L
        /** 最小发送阈值（ms），太短的片段不发送避免浪费 API 调用 */
        private const val MIN_CHUNK_MS = 500L
        /** 静音检测触发立即发送的持续时间（ms），连续 800ms 静音视为说话停顿 */
        private const val SILENCE_TRIGGER_MS = 800L
        /** VAD 能量阈值（dB），低于此值视为静音 */
        private const val VAD_THRESHOLD_DB = -40.0
        /** VAD 检测窗口（ms） */
        private const val VAD_WINDOW_MS = 20L
        /** 目标采样率（所有内部处理统一为 16kHz mono） */
        private const val TARGET_SAMPLE_RATE = 16000
    }

    private var _isInitialized = false
    override val engineType = EngineType.MIMO_ASR

    override val isAvailable: Boolean get() = _isInitialized && apiSettings.hasApiKey()

    // ==================== 流式状态 ====================

    /** 音频采样点缓冲区（16kHz mono float） */
    private val audioBuffer = mutableListOf<Float>()
    /** 流式会话是否活跃 */
    private val streamingActive = AtomicBoolean(false)
    /** 上次发送 chunk 的时间戳（用于定时发送） */
    private var lastChunkTimeMs = 0L
    /** 流式会话开始时间 */
    private var sessionStartTimeMs = 0L
    /** 已确认的最终文本（stop 时合并用） */
    private val confirmedText = StringBuilder()
    /** OkHttp 客户端（流式复用，超时更短） */
    private val streamingClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    // ==================== 初始化 ====================

    fun initialize(): Boolean {
        if (_isInitialized) return true
        return try {
            val key = apiSettings.getApiKey()
            if (key.isNullOrBlank()) {
                DebugLog.w(TAG, "MiMO ASR: API Key 未设置")
                return false
            }
            _isInitialized = true
            DebugLog.i(TAG, "MiMO ASR 引擎初始化成功 (model=$MODEL_ID)")
            true
        } catch (e: Exception) {
            DebugLog.e(TAG, "初始化失败: ${e.message}", e)
            false
        }
    }

    // ==================== 文件转录 ====================

    override suspend fun recognizeFile(uri: Uri): SttResult = withContext(Dispatchers.IO) {
        ensureInitialized()
        val startTimeMs = System.currentTimeMillis()
        try {
            val tempFile = copyUriToTempFile(uri)
            try {
                // 检查是否为 WAV 文件；非 WAV（MP3/AAC/FLAC 等）先转为临时 WAV
                val wavFile = if (isWavFile(tempFile)) {
                    tempFile
                } else {
                    DebugLog.i(TAG, "非 WAV 格式，通过 MediaCodec 解码: ${tempFile.name}")
                    val pair = AudioProcessor.decodeToPcm(context, Uri.fromFile(tempFile))
                    if (pair != null && pair.first.isNotEmpty()) {
                        val outFile = java.io.File(context.cacheDir, "mimo_decode_${System.currentTimeMillis()}.wav")
                        AudioProcessor.saveAsWav(pair.first, pair.second, outFile.absolutePath)
                        outFile
                    } else {
                        DebugLog.e(TAG, "MediaCodec 解码失败，返回空结果")
                        return@withContext SttResult("", 0f, emptyList(), 0, System.currentTimeMillis() - startTimeMs, EngineType.MIMO_ASR, MODEL_ID, error = "音频解码失败")
                    }
                }
                try {
                    recognizeFileInternal(wavFile, startTimeMs)
                } finally {
                    if (wavFile != tempFile) wavFile.delete()
                }
            } finally {
                tempFile.delete()
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "URI 识别失败: ${e.message}", e)
            SttResult("", 0f, emptyList(), 0, System.currentTimeMillis() - startTimeMs, EngineType.MIMO_ASR, MODEL_ID, error = e.message)
        }
    }

    override suspend fun recognizeFile(filePath: String): SttResult = withContext(Dispatchers.IO) {
        ensureInitialized()
        val startTimeMs = System.currentTimeMillis()
        try {
            val file = File(filePath)
            // Check format: non-WAV files need MediaCodec conversion before recognition.
            // Previously this passed non-WAV files directly to recognizeFileInternal()
            // which assumes WAV input, causing silent failures (empty results).
            val wavFile = if (isWavFile(file)) {
                file
            } else {
                DebugLog.i(TAG, "非 WAV 格式 (filePath), 通过 MediaCodec 解码: ${file.name}")
                val pair = AudioProcessor.decodeToPcm(context, Uri.fromFile(file))
                if (pair != null && pair.first.isNotEmpty()) {
                    val outFile = java.io.File(context.cacheDir, "mimo_decode_path_${System.currentTimeMillis()}.wav")
                    AudioProcessor.saveAsWav(pair.first, pair.second, outFile.absolutePath)
                    outFile
                } else {
                    DebugLog.e(TAG, "MediaCodec 解码失败 (filePath), 返回空结果")
                    return@withContext SttResult("", 0f, emptyList(), 0, System.currentTimeMillis() - startTimeMs, EngineType.MIMO_ASR, MODEL_ID, error = "音频解码失败")
                }
            }
            try {
                recognizeFileInternal(wavFile, startTimeMs)
            } finally {
                if (wavFile != file) wavFile.delete()
            }
        }
        catch (e: Exception) {
            DebugLog.e(TAG, "文件路径识别失败: ${e.message}", e)
            SttResult("", 0f, emptyList(), 0, System.currentTimeMillis() - startTimeMs, EngineType.MIMO_ASR, MODEL_ID, error = e.message)
        }
    }

    // ==================== 流式识别（核心改进）====================

    /**
     * 启动 MiMO 伪流式语音识别。
     *
     * 工作流程：
     * 1. 发送 Listening 状态 → UI 显示"正在聆听..."
     * 2. 进入主循环：
     *    a. 等待缓冲区积累足够数据（[MIN_CHUNK_MS] 或 [CHUNK_INTERVAL_MS]）
     *    b. 检测到连续静音（[SILENCE_TRIGGER_MS]）→ 触发提前发送
     *    c. 取出缓冲区数据 → 编码为 WAV → Base64 → 调用 API
     *    d. 收到结果 → emit Recognizing(partialText)
     * 3. 外部调用 stopStreamingRecognition() → 循环退出
     * 4. 发送剩余缓冲区 → emit FinalResult(fullText)
     *
     * 参考：SEED 协议 + Stream 数据源实现真流式，
     *       我们用 chunked HTTP 请求模拟类似效果。
     */
    override fun startStreamingRecognition(): Flow<StreamingRecognitionState> {
        return callbackFlow {
            ensureInitialized()

            if (!streamingActive.compareAndSet(false, true)) {
                trySend(StreamingRecognitionState.Error("已有流式会话在进行中"))
                close()
                return@callbackFlow
            }

            sessionStartTimeMs = System.currentTimeMillis()
            lastChunkTimeMs = sessionStartTimeMs
            audioBuffer.clear()
            confirmedText.clear()

            DebugLog.i(TAG, "流式识别会话已启动")

            trySend(StreamingRecognitionState.Listening)

            try {
                while (isActive && streamingActive.get()) {
                    delay(100L)  // 100ms 轮询间隔

                    val bufferSizeMs = if (audioBuffer.isEmpty()) 0L
                    else (audioBuffer.size.toLong() * 1000L / TARGET_SAMPLE_RATE)

                    // 条件 A：缓冲区达到时间窗口 → 定时发送
                    val timeSinceLastChunk = System.currentTimeMillis() - lastChunkTimeMs
                    val shouldSendByTime = timeSinceLastChunk >= CHUNK_INTERVAL_MS

                    // 条件 B：VAD 检测到足够长的尾部静音 → 提前发送
                    val shouldSendBySilence = bufferSizeMs >= MIN_CHUNK_MS &&
                            detectTrailingSilence(SILENCE_TRIGGER_MS)

                    if (bufferSizeMs >= MIN_CHUNK_MS && (shouldSendByTime || shouldSendBySilence)) {
                        val result = sendBufferChunk()
                        if (result != null) {
                            confirmedText.append(vocabularyStore.applyVocabulary(result))
                            val currentFull = confirmedText.toString()
                            trySend(StreamingRecognitionState.Recognizing(currentFull))
                        }
                    }
                }

                // 会话结束：发送剩余缓冲区
                if (audioBuffer.isNotEmpty()) {
                    val finalChunk = sendBufferChunk()
                    if (finalChunk != null) {
                        confirmedText.append(vocabularyStore.applyVocabulary(finalChunk))
                    }
                }

                val fullText = vocabularyStore.applyVocabulary(confirmedText.toString().trim())
                val durationMs = System.currentTimeMillis() - sessionStartTimeMs

                DebugLog.i(TAG, "流式识别完成: ${fullText.length}字, ${durationMs}ms")

                trySend(StreamingRecognitionState.FinalResult(fullText))
            } catch (e: CancellationException) {
                DebugLog.i(TAG, "流式识别被取消")
                // 取消时也尝试返回已有文本
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
                audioBuffer.clear()
            }

            close()
        }
    }

    /**
     * 喂入音频采样点（由录音器调用）。
     *
     * @param samples 归一化 [-1.0, 1.0] 浮点数组，采样率必须为 16000Hz
     */
    fun feedAudioData(samples: FloatArray) {
        if (!streamingActive.get()) return
        synchronized(audioBuffer) {
            for (s in samples) audioBuffer.add(s)
        }
    }

    override fun stopStreamingRecognition() {
        if (streamingActive.compareAndSet(true, false)) {
            DebugLog.i(TAG, "停止流式识别信号已发送")
        }
    }

    override fun close() {
        stopStreamingRecognition()
        _isInitialized = false
    }

    // ==================== 内部实现 ====================

    /**
     * 将当前缓冲区内容作为一个 chunk 发送到 MiMO API 进行转录。
     *
     * **修复说明**：原实现在 synchronized 块内立即清空缓冲区，导致失败时数据丢失。
     * 新实现采用"事务性"策略：先取出数据，成功后再清空；失败时回填数据到缓冲区头部。
     *
     * @return 转录文本；失败返回 null（缓冲区数据保留，下次可重试）
     */
    private suspend fun sendBufferChunk(): String? = withContext(Dispatchers.IO) {
        // 步骤1：从缓冲区取出数据（此时不清空）
        val chunkData: FloatArray
        synchronized(audioBuffer) {
            if (audioBuffer.isEmpty()) return@withContext null
            chunkData = audioBuffer.toFloatArray()
            // 注意：这里暂不 clear()，等成功后再清空
        }

        try {
            // 步骤2：噪声抑制 + 自动增益预处理
            val processed = AudioProcessor.applyNoiseSuppression(chunkData)

            // 编码为 WAV → Base64
            val wavFile = File(context.cacheDir, "mimo_stream_${System.currentTimeMillis()}.wav")
            AudioProcessor.saveAsWav(
                processed,
                AudioMetadata(sampleRate = TARGET_SAMPLE_RATE, channels = 1, bitDepth = 16),
                wavFile.absolutePath,
            )
            val base64Audio = encodeToBase64(wavFile)
            wavFile.delete()

            val apiKey = apiSettings.getApiKey()!!
            val jsonBody = buildStreamingRequestBody(base64Audio)

            val request = buildAuthHeader(
                Request.Builder()
                    .url("https://api.xiaomimimo.com/v1/chat/completions")
                    .header("Content-Type", "application/json")
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaType())),
                apiKey,
            ).build()

            var text = ""
            streamingClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    DebugLog.e(TAG, "流式 chunk 请求失败: HTTP ${response.code}, body=${body.take(200)}")
                    // 失败：回填数据到缓冲区
                    restoreChunkToBuffer(chunkData)
                    return@withContext null
                }
                try {
                    val json = JSONObject(body)
                    val choices = json.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        text = choices.getJSONObject(0)
                            .getJSONObject("message")
                            .optString("content", "")
                            .trim()
                    }
                } catch (e: Exception) {
                    DebugLog.w(TAG, "解析 chunk 响应失败: ${e.message}")
                    // 解析失败也回填数据（保守策略）
                    restoreChunkToBuffer(chunkData)
                    return@withContext null
                }
            }

            // 步骤3：成功 - 清空缓冲区并更新时间戳
            synchronized(audioBuffer) {
                // 移除已发送的数据（从头部移除 chunkData.size 个元素）
                if (audioBuffer.size >= chunkData.size) {
                    val remaining = audioBuffer.drop(chunkData.size)
                    audioBuffer.clear()
                    audioBuffer.addAll(remaining)
                } else {
                    // 异常情况：缓冲区被外部修改过，直接清空
                    audioBuffer.clear()
                }
                lastChunkTimeMs = System.currentTimeMillis()
            }

            if (text.isNotEmpty()) {
                DebugLog.d(TAG, "chunk 结果: '${text.take(60)}' (${chunkData.size} samples)")
            }
            text.ifEmpty { null }
        } catch (e: Exception) {
            DebugLog.w(TAG, "chunk 发送失败（数据已回填到缓冲区等待重试）: ${e.message}")
            // 失败时回填数据到缓冲区头部，保证数据不丢失
            restoreChunkToBuffer(chunkData)
            null
        }
    }

    /**
     * 将未成功发送的 chunk 数据回填到缓冲区头部。
     *
     * 确保在网络故障、API 错误等场景下音频数据不会丢失，
     * 下次定时发送或静音检测触发时会重新发送。
     */
    private fun restoreChunkToBuffer(chunkData: FloatArray) {
        synchronized(audioBuffer) {
            // 将 chunk 数据插回缓冲区头部（保持时间顺序）
            val tempList = chunkData.toMutableList()
            tempList.addAll(audioBuffer)
            audioBuffer.clear()
            audioBuffer.addAll(tempList)
            
            DebugLog.d(TAG, "已回填 ${chunkData.size} 个采样点到缓冲区（当前缓冲区大小=${audioBuffer.size}）")
        }
    }

    /**
     * 检测缓冲区尾部是否存在足够长的静音段。
     * 用于在用户说话停顿时提前发送 chunk，减少感知延迟。
     */
    private fun detectTrailingSilence(silenceDurationMs: Long): Boolean {
        val data: FloatArray
        synchronized(audioBuffer) { data = audioBuffer.toFloatArray() }
        if (data.size < TARGET_SAMPLE_RATE / 2) return false  // 至少半秒数据才检测

        val windowSize = (TARGET_SAMPLE_RATE * VAD_WINDOW_MS / 1000).coerceAtLeast(32).toInt()
        val hopSize = windowSize / 2
        val requiredFrames = ((silenceDurationMs / VAD_WINDOW_MS).toInt()).coerceAtLeast(3)

        var consecutiveSilentFrames = 0
        val startFrame = (data.size - windowSize) / hopSize - requiredFrames

        for (i in maxOf(0, startFrame) until (data.size - windowSize) / hopSize) {
            val offset = i * hopSize
            var sumSq = 0.0
            val end = minOf(offset + windowSize, data.size)
            for (j in offset until end) {
                sumSq += data[j] * data[j]
            }
            val rms = if (end > offset) kotlin.math.sqrt(sumSq / (end - offset)) else 0.0
            val db = if (rms > 1e-8) 20.0 * kotlin.math.log10(rms) else -96.0

            if (db < VAD_THRESHOLD_DB) {
                consecutiveSilentFrames++
                if (consecutiveSilentFrames >= requiredFrames) return true
            } else {
                consecutiveSilentFrames = 0
            }
        }
        return false
    }

    // ==================== 文件模式实现 ====================

    private suspend fun recognizeFileInternal(file: File, startTimeMs: Long): SttResult {
        val fileSize = file.length()
        DebugLog.i(TAG, "开始 MiMO ASR 识别: ${file.name} (${fileSize / 1024}KB, ${fileSize}B)")
        DebugLog.i(TAG, "文件完整路径: ${file.absolutePath}")

        if (fileSize <= 0) {
            DebugLog.e(TAG, "音频文件为空 (0 bytes)，无法转写")
            return SttResult("", 0f, emptyList(), 0, System.currentTimeMillis() - startTimeMs, EngineType.MIMO_ASR, MODEL_ID)
        }

        if (fileSize > MAX_FILE_BYTES) {
            DebugLog.w(TAG, "文件过大(${fileSize / (1024*1024)}MB > 100MB)，可能失败")
        }

        DebugLog.i(TAG, "API 端点: https://api.xiaomimimo.com/v1/chat/completions")
        DebugLog.i(TAG, "API Key 前缀: ${apiSettings.getApiKey()?.take(6)}..., sttEngine=${apiSettings.getSttEngine()}")

        // VAD 预处理：解码 WAV → 检测静音 → 裁剪前后静音 → 重新编码
        val processedFile = preprocessAudio(file)
        DebugLog.d(TAG, "VAD 后文件: ${processedFile.name}, 大小=${processedFile.length() / 1024}KB")

        // 噪声抑制预处理
        val rawFloats = AudioProcessor.decodeWavToFloat(processedFile)
        DebugLog.d(TAG, "解码 WAV: ${rawFloats.size} samples, ${rawFloats.size / TARGET_SAMPLE_RATE}s")

        val suppressed = if (rawFloats.isNotEmpty()) {
            AudioProcessor.applyNoiseSuppression(rawFloats)
        } else rawFloats

        val finalFile = if (suppressed !== rawFloats && suppressed.isNotEmpty()) {
            val outFile = File(context.cacheDir, "mimo_ns_${System.currentTimeMillis()}.wav")
            AudioProcessor.saveAsWav(
                suppressed,
                AudioMetadata(sampleRate = 16000, channels = 1, bitDepth = 16),
                outFile.absolutePath,
            )
            if (processedFile != file) processedFile.delete()  // 清理 VAD 中间文件
            DebugLog.d(TAG, "降噪后文件: ${outFile.name}, 大小=${outFile.length() / 1024}KB")
            outFile
        } else {
            if (processedFile != file) processedFile.delete()
            processedFile
        }

        // 编码为 Base64
        val base64Audio = encodeToBase64(finalFile)
        DebugLog.d(TAG, "Base64 编码后: ${base64Audio.length} 字符")
        if (finalFile != file) finalFile.delete()

        if (base64Audio.isEmpty()) {
            DebugLog.e(TAG, "Base64 编码结果为空，音频数据可能已被全部清除")
            return SttResult("", 0f, emptyList(), 0, System.currentTimeMillis() - startTimeMs, EngineType.MIMO_ASR, MODEL_ID)
        }

        val apiKey = apiSettings.getApiKey()!!

        // 构造请求体：纯音频 ASR 请求（MiMO ASR 网关要求不含 text prompt）
        val jsonBody = JSONObject().apply {
            put("model", MODEL_ID)
            put("max_tokens", 2048)
            put("temperature", 0.1)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "input_audio")
                            put("input_audio", JSONObject().put("data", "data:audio/wav;base64,$base64Audio"))
                        })
                    })
                })
            })
        }

        val requestBodyStr = jsonBody.toString()
        DebugLog.d(TAG, "API 请求体大小: ${requestBodyStr.length} 字符, 前200字符: ${requestBodyStr.take(200)}")

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        val request = buildAuthHeader(
            Request.Builder()
                .url("https://api.xiaomimimo.com/v1/chat/completions")
                .header("Content-Type", "application/json")
                .post(requestBodyStr.toRequestBody("application/json".toMediaType())),
            apiKey,
        ).build()

        var text = ""
        var errorMsg: String? = null

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            DebugLog.i(TAG, "API 响应: HTTP ${response.code}, body=${body.length}字符")
            if (body.isNotEmpty()) {
                DebugLog.d(TAG, "响应前500字符: ${body.take(500)}")
            } else {
                DebugLog.w(TAG, "API 响应 body 为空")
            }

            if (!response.isSuccessful) {
                errorMsg = "HTTP ${response.code}: $body"
                DebugLog.e(TAG, "MiMO ASR 请求失败: $errorMsg")
                return@use
            }

            try {
                val json = org.json.JSONObject(body)
                val choices = json.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val messageObj = choices.getJSONObject(0).optJSONObject("message")
                    if (messageObj != null) {
                        text = messageObj.optString("content", "").trim()
                        DebugLog.d(TAG, "解析到 content: '${text.take(100)}'")
                    } else {
                        DebugLog.w(TAG, "choices[0] 中没有 message 对象")
                    }
                } else {
                    errorMsg = json.optString("error", json.optString("message", "choices为空"))
                    DebugLog.w(TAG, "MiMO ASR 异常响应: $errorMsg, 完整响应: ${body.take(500)}")
                }
            } catch (e: Exception) {
                errorMsg = "解析响应失败: ${e.message}"
                DebugLog.e(TAG, "$errorMsg\n原始响应: ${body.take(1000)}", e)
            }
        }

        val durationMs = System.currentTimeMillis() - startTimeMs

        if (text.isNotEmpty()) {
            DebugLog.i(TAG, "MiMO ASR 完成: ${text.take(100)}... (${text.length}字, ${durationMs}ms)")
        } else {
            DebugLog.w(TAG, "MiMO ASR 返回空结果: errorMsg=$errorMsg")
        }

        val resultText = vocabularyStore.applyVocabulary(text)

        return SttResult(
            text = resultText,
            confidence = if (resultText.isNotEmpty()) 1f else 0f,
            segments = if (resultText.isNotEmpty()) listOf(
                SttSegment(text = resultText, startTimeMs = 0, endTimeMs = durationMs, confidence = 1f),
            ) else emptyList(),
            durationMs = durationMs,
            processingTimeMs = durationMs,
            engineType = EngineType.MIMO_ASR,
            modelUsed = MODEL_ID,
            error = if (resultText.isEmpty() && errorMsg != null) errorMsg else null,
        )
    }

    /**
     * VAD 预处理：解码 WAV → 能量检测 → 裁剪首尾静音 → 返回处理后的文件。
     *
     * 阈值设置：-50dB 比之前的 -40dB 更宽松，避免在安静环境下把有效语音误判为静音。
     * 保护逻辑：保留至少 50% 音频（之前 30% 太激进），短音频 (<3s) 跳过 VAD 直接返回原文件。
     */
    private fun preprocessAudio(file: File): File {
        return try {
            val audioData = AudioProcessor.decodeWavToFloat(file)
            DebugLog.d(TAG, "VAD 输入: ${audioData.size} samples, ${file.length() / 1024}KB, ${audioData.size / TARGET_SAMPLE_RATE}s")
            if (audioData.isEmpty()) {
                DebugLog.w(TAG, "VAD: 解码后音频为空")
                return file
            }

            // 短音频 (<3秒) 跳过 VAD，避免过度裁剪
            val durationSec = audioData.size.toFloat() / TARGET_SAMPLE_RATE
            if (durationSec < 3.0f) {
                DebugLog.d(TAG, "VAD: 音频仅 ${durationSec}s (<3s)，跳过 VAD 预处理")
                return file
            }

            val windowSize = (TARGET_SAMPLE_RATE * 0.02).toInt()  // 20ms 窗口
            val hopSize = windowSize / 2
            val thresholdDb = -50.0  // 更宽松的阈值（原-40dB太严格）
            val silenceFrames = mutableListOf<Int>()
            val totalFrames = (audioData.size - windowSize) / hopSize

            for (i in 0 until totalFrames) {
                val start = i * hopSize
                var sumSq = 0.0
                for (j in start until start + windowSize) {
                    sumSq += audioData[j] * audioData[j]
                }
                val rms = if (windowSize > 0) kotlin.math.sqrt(sumSq / windowSize) else 0.0
                val db = if (rms > 1e-10) 20.0 * kotlin.math.log10(rms) else -96.0
                if (db < thresholdDb) silenceFrames.add(i)
            }

            DebugLog.d(TAG, "VAD: 总帧数=$totalFrames, 静音帧=${silenceFrames.size}, 阈值=${thresholdDb}dB")

            if (silenceFrames.size < 5) {
                DebugLog.d(TAG, "VAD: 静音帧太少(${silenceFrames.size}<5)，跳过裁剪")
                return file
            }

            val allFrames = (0 until totalFrames).toSet()
            val voiceFrames = allFrames - silenceFrames.toSet()
            if (voiceFrames.isEmpty()) {
                DebugLog.w(TAG, "VAD: 整段音频都是静音，返回原文件")
                return file
            }

            val firstVoice = voiceFrames.minOf { it } * hopSize
            val lastVoice = voiceFrames.maxOf { it } * hopSize + windowSize
            val startSample = firstVoice.coerceAtLeast(0).coerceAtMost(audioData.size)
            val endSample = lastVoice.coerceAtMost(audioData.size).coerceAtLeast(0)

            // 保护：保留至少 50% 音频（原 30% 太激进）
            val keepRatio = (endSample - startSample).toFloat() / audioData.size
            if (endSample <= startSample || keepRatio < 0.5f) {
                DebugLog.w(TAG, "VAD: 裁剪后仅剩 ${(keepRatio * 100).toInt()}%(<50%)，跳过裁剪")
                return file
            }

            val trimmed = audioData.copyOfRange(startSample, endSample)
            val outFile = File(context.cacheDir, "mimo_vad_${System.currentTimeMillis()}.wav")
            AudioProcessor.saveAsWav(
                trimmed,
                AudioMetadata(sampleRate = 16000, channels = 1, bitDepth = 16),
                outFile.absolutePath,
            )
            DebugLog.i(TAG, "VAD 裁剪: ${audioData.size} → ${trimmed.size} samples (${file.length() / 1024}KB → ${outFile.length() / 1024}KB), 保留 ${(keepRatio * 100).toInt()}%")
            outFile
        } catch (e: Exception) {
            DebugLog.w(TAG, "VAD 预处理失败，使用原始音频: ${e.message}")
            file
        }
    }

    // ==================== 工具方法 ====================

    private fun buildStreamingRequestBody(base64Audio: String): JSONObject {
        return JSONObject().apply {
            put("model", MODEL_ID)
            put("max_tokens", 1024)
            put("temperature", 0.1f)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "input_audio")
                            put("input_audio", JSONObject().put("data", "data:audio/wav;base64,$base64Audio"))
                        })
                    })
                })
            })
        }
    }

    private fun encodeToBase64(file: File): String {
        val bytes = file.readBytes()
        val dataToEncode = if (bytes.size <= MAX_BASE64_BYTES) bytes
        else bytes.copyOfRange(0, MAX_BASE64_BYTES)
        DebugLog.d(TAG, "Base64 编码: 原始=${bytes.size}B, 编码后=${dataToEncode.size}B")
        return Base64.getEncoder().encodeToString(dataToEncode)
    }

    /**
     * 构建认证请求头，与 LlmClient.buildRequest 保持一致。
     * - tp- 前缀的 Key 使用 api-key 头（Token Plan）
     * - AIza 前缀的 Key 使用 x-goog-api-key 头（Google AI Studio）
     * - 其他 Key 使用 Authorization: Bearer 头（标准 OpenAI 格式）
     */
    private fun buildAuthHeader(requestBuilder: Request.Builder, apiKey: String): Request.Builder {
        return when {
            apiKey.startsWith("tp-") -> requestBuilder.header("api-key", apiKey)
            apiKey.startsWith("AIza") -> requestBuilder.header("x-goog-api-key", apiKey)
            else -> requestBuilder.header("Authorization", "Bearer $apiKey")
        }
    }

    /** 检查文件是否为 WAV 格式（通过读取 RIFF 头） */
    private fun isWavFile(file: java.io.File): Boolean {
        if (file.length() < 12) return false
        return try {
            file.inputStream().use { fis ->
                val header = ByteArray(4)
                fis.read(header)
                val riff = String(header, Charsets.US_ASCII) == "RIFF"
                fis.skip(4)
                val wave = String(ByteArray(4).also { fis.read(it) }, Charsets.US_ASCII) == "WAVE"
                riff && wave
            }
        } catch (_: Exception) { false }
    }

    private suspend fun copyUriToTempFile(uri: Uri): File = withContext(Dispatchers.IO) {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("无法打开 URI: $uri")
        // Infer the correct file extension from the URI's MIME type
        // so that isWavFile() and downstream format detection work correctly.
        val ext = inferExtensionFromUri(uri)
        val tempFile = File(context.cacheDir, "mimo_asr_${System.currentTimeMillis()}.$ext")
        try {
            tempFile.outputStream().use { output -> inputStream.use { input -> input.copyTo(output) } }
            DebugLog.d(TAG, "URI 已复制到临时文件: ${tempFile.absolutePath} (ext=$ext, ${tempFile.length()} bytes)")
            tempFile
        } catch (e: Exception) {
            if (tempFile.exists()) tempFile.delete()
            throw e
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
                val subtype = mimeType.substringAfter("/", "")
                if (subtype.isNotEmpty() && subtype.length <= 5) subtype else "wav"
            }
        }
    }

    private fun ensureInitialized() {
        if (!_isInitialized) throw IllegalStateException("MimoAsrEngine 未初始化，请先调用 initialize()")
    }
}
