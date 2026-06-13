package top.hsyscn.opedrgent.interview

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.Manifest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.hsyscn.opedrgent.tts.TtsPlayer
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 全双工音频引擎 — 模拟真实电话通话的音频管线。
 *
 * 核心能力：
 * 1. AudioRecord 持续采集麦克风音频（不按住，一直采）
 * 2. AudioTrack 同时播放 TTS 音频（并行，非串行）
 * 3. 硬件级 AEC 回声消除（VOICE_COMMUNICATION 音源）
 * 4. 软件 VAD 语音活动检测（能量阈值）
 * 5. 插话检测（Barge-in）：用户在 AI 说话时开口 → 立即中断
 *
 * 架构：
 * ┌─────────────┐         ┌──────────────┐
 * │  AudioRecord │──PCM──→│   VAD 检测    │──→ onUserSpeechDetected(pcmData)
 * │ (持续采集)   │         │ (能量+静音超时)│
 * └─────────────┘         └──────────────┘
 *
 * ┌─────────────┐         ┌──────────────┐
 * │  AudioTrack  │←──PCM──│   TTS 缓冲区   │←── aiSpeak(pcmData)
 * │ (实时播放)   │         │ (流式写入)     │
 * └─────────────┘         └──────────────┘
 *
 * 两者完全并行，互不阻塞。
 *
 * ## 使用方式
 *
 * ```kotlin
 * val engine = FullDuplexAudioEngine(context)
 *
 * // 连接音频通道
 * engine.connect()
 *
 * // 注册回调
 * engine.onSpeechDetected { pcmData ->
 *     // 用户说完了一句话，提交给 ASR 识别
 *     asrManager.recognize(pcmData)
 * }
 * engine.onBargeIn {
 *     // 用户打断了 AI，停止当前 TTS
 *     ttsPlayer.stop()
 * }
 *
 * // 开始全双工对话
 * engine.start()
 *
 * // AI 要说话时
 * engine.aiSpeakText("你好", ttsPlayer)
 *
 * // 结束对话
 * engine.stop()
 * engine.disconnect()
 * ```
 */
class FullDuplexAudioEngine(
    private val context: Context,
) {

    companion object {
        private const val TAG = "FullDuplexAudioEngine"

        /** 采样率：16kHz（语音通话标准，匹配 Whisper/ASR 输入） */
        const val SAMPLE_RATE = 16000

        /** 单声道 */
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO

        /** 16bit PCM */
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        /** 音频源：VOICE_COMMUNICATION（启用硬件 AEC + AGC） */
        val AUDIO_SOURCE = MediaRecorder.AudioSource.VOICE_COMMUNICATION

        /** VAD 静音超时（毫秒）：连续多少毫秒没声音判定为"说完了一句话" */
        const val VAD_SILENCE_TIMEOUT_MS = 800L

        /** VAD 能量阈值（低于此值视为静音） */
        const val VAD_ENERGY_THRESHOLD = 200f

        /** VAD 前导静音帧数：开头忽略多少帧静音 */
        const val VAD_LEADING_SILENCE_FRAMES = 10

        /** 音频缓冲区大小（毫秒） */
        const val BUFFER_SIZE_MS = 20  // 20ms 一帧
    }

    // ==================== 状态管理 ====================

    /**
     * 全双工状态枚举。
     *
     * 可直接绑定 Compose UI 状态展示。
     */
    enum class DuplexState {
        /** 空闲（未连接） */
        IDLE,

        /** 已连接（音频通道打开，但未开始采集/播放） */
        CONNECTED,

        /** AI 正在说话（TTS 播放中） */
        AI_SPEAKING,

        /** 正在听用户说（VAD 监控中） */
        LISTENING,

        /** 用户静音（麦克风关闭但 TTS 仍可播放） */
        MUTED,
    }

    @Volatile
    private var _state = DuplexState.IDLE

    /**
     * 当前全双工状态。
     */
    val state: DuplexState get() = _state

    private val stateListeners = mutableListOf<(DuplexState) -> Unit>()

    // ==================== 录音（上行）====================

    private var audioRecord: AudioRecord? = null
    private var recordJob: Job? = null
    private val isRecording = AtomicBoolean(false)

    // VAD 相关状态
    private var vadSilenceStartMs = 0L       // 静音开始时间
    private var vadIsSpeechActive = false     // 当前是否在说话
    private var currentSpeechBuffer = ByteArrayOutputStream()  // 当前话语的 PCM 数据
    private var leadingSilenceFrameCount = 0  // 前导静音帧计数器
    private val speechListeners = mutableListOf<(ByteArray) -> Unit>()  // 话语回调
    private val partialTextListeners = mutableListOf<(String) -> Unit>() // ASR partial 文本回调

    // ==================== 播放（下行）====================

    private var audioTrack: AudioTrack? = null
    private val isPlaying = AtomicBoolean(false)
    private val playQueue = ConcurrentLinkedQueue<ByteArray>()  // TTS 音频队列
    private var playJob: Job? = null

    // 插话检测
    private val bargeInListeners = mutableListOf<() -> Unit>()
    @Volatile
    private var bargeInDetected = false

    // 协程作用域
    private val engineScope = CoroutineScope(Dispatchers.IO)

    // ==================== 公开 API ====================

    /**
     * 连接（打开音频通道）。
     *
     * 初始化 AudioRecord + AudioTrack，
     * 但不开始采集/播放，等待 [start] 调用。
     *
     * @throws SecurityException 如果没有录音权限
     * @throws IllegalStateException 如果设备不支持指定音频参数
     */
    fun connect() {
        if (_state != DuplexState.IDLE) {
            DebugLog.w(TAG, "已在连接状态: $_state")
            return
        }

        DebugLog.i(TAG, "正在连接音频通道...")

        // 检查权限
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("缺少 RECORD_AUDIO 权限")
        }

        try {
            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)

            if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
                throw IllegalStateException("设备不支持指定的音频参数 (sampleRate=$SAMPLE_RATE)")
            }

            // 录音：使用 VOICE_COMMUNICATION（硬件 AEC + AGC）
            audioRecord = AudioRecord(
                AUDIO_SOURCE,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize.coerceAtLeast(SAMPLE_RATE * BUFFER_SIZE_MS / 1000 * 2)
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("AudioRecord 初始化失败")
            }

            // 播放：Music 流类型（确保和录音不冲突）
            val playBufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AUDIO_FORMAT
            )

            if (playBufferSize == AudioTrack.ERROR || playBufferSize == AudioTrack.ERROR_BAD_VALUE) {
                throw IllegalStateException("设备不支持播放参数")
            }

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(playBufferSize.coerceAtLeast(SAMPLE_RATE * BUFFER_SIZE_MS / 1000 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                throw IllegalStateException("AudioTrack 初始化失败")
            }

            changeState(DuplexState.CONNECTED)
            DebugLog.i(TAG, "音频通道已连接 (recordBuf=${bufferSize}, playBuf=${playBufferSize})")

        } catch (e: IllegalStateException) {
            releaseResources()
            throw e
        } catch (e: SecurityException) {
            releaseResources()
            throw e
        }
    }

    /**
     * 断开连接（释放所有资源）。
     *
     * 会自动先调用 [stop] 停止所有活动。
     */
    fun disconnect() {
        DebugLog.i(TAG, "断开音频通道")

        stop()
        releaseResources()
        changeState(DuplexState.IDLE)
    }

    /**
     * 开始全双工对话。
     *
     * 同时启动：
     * - AudioRecord 持续采集
     * - AudioTrack 待命播放
     * - VAD 监控
     *
     * 必须先调用 [connect] 成功。
     */
    fun start() {
        if (_state == DuplexState.IDLE) {
            DebugLog.e(TAG, "未连接，请先调用 connect()")
            return
        }

        if (isRecording.get()) {
            DebugLog.w(TAG, "已在运行中")
            return
        }

        DebugLog.i(TAG, "开始全双工对话")

        isRecording.set(true)
        bargeInDetected = false

        // 启动录音线程
        startRecording()

        // 启动播放线程
        startPlayback()

        changeState(DuplexState.LISTENING)
    }

    /**
     * 停止对话（保留连接，可重新 [start]）。
     */
    fun stop() {
        DebugLog.i(TAG, "停止对话")

        isRecording.set(false)
        isPlaying.set(false)

        // 停止录音协程
        runCatching { recordJob?.cancel() }
        recordJob = null

        // 停止播放协程
        runCatching { playJob?.cancel() }
        playJob = null

        // 停止 AudioRecord
        runCatching { audioRecord?.stop() }

        // 停止 AudioTrack 并清空队列
        runCatching { audioTrack?.stop() }
        playQueue.clear()

        // 重置 VAD 状态
        resetVadState()
    }

    /**
     * AI 说话（写入 TTS 音频数据到播放队列）。
     *
     * @param pcmData PCM 音频数据（16kHz, 16bit, mono）
     * @return 是否成功入队
     */
    fun aiSpeak(pcmData: ByteArray): Boolean {
        if (_state == DuplexState.IDLE || _state == DuplexState.MUTED) {
            DebugLog.w(TAG, "aiSpeak 被拒绝: state=$_state")
            return false
        }

        if (pcmData.isEmpty()) return false

        playQueue.offer(pcmData)

        if (_state != DuplexState.AI_SPEAKING) {
            changeState(DuplexState.AI_SPEAKING)
        }

        DebugLog.d(TAG, "AI speak: 入队 ${pcmData.size} bytes, 队列长度=${playQueue.size}")
        return true
    }

    /**
     * AI 说话（文本版本，内部调用 TTS 合成后播放）。
     *
     * 使用 [TtsPlayer] 将文本合成为 PCM 音频，
     * 然后通过 [aiSpeak] 写入播放队列。
     *
     * @param text 要合成的文本
     * @param ttsPlayer TTS 播放器实例
     * @param scenario TTS 场景（控制语音风格）
     * @param voiceId 音色 ID
     * @param onComplete 播放完成回调
     */
    suspend fun aiSpeakText(
        text: String,
        ttsPlayer: TtsPlayer,
        scenario: TtsScenario = TtsScenario.INTERVIEW,
        voiceId: String = "白桦",
        onComplete: (() -> Unit)? = null,
    ) {
        if (text.isBlank()) {
            onComplete?.invoke()
            return
        }

        DebugLog.i(TAG, "AI 说话: '${text.take(50)}...'")

        changeState(DuplexState.AI_SPEAKING)

        try {
            // 注意：这里使用同步方式等待 TTS 合成完成
            // 实际项目中可以使用流式 TTS API 实现更低的延迟
            withContextCompat(Dispatchers.Main) {
                ttsPlayer.speak(
                    text = text,
                    localeTag = "zh-CN",
                    rate = scenario.defaultRate,
                    pitch = scenario.defaultPitch,
                    mimoVoice = voiceId,
                    forceLocal = true,
                )
            }

            // 等待播放完成或被插话中断
            while ((ttsPlayer.isCurrentlySpeaking() || !playQueue.isEmpty()) && isRecording.get() && !bargeInDetected) {
                delay(50L)
            }

            if (bargeInDetected) {
                DebugLog.i(TAG, "AI 说话被用户打断")
            } else {
                DebugLog.d(TAG, "AI 说话完成")
            }

        } catch (e: CancellationException) {
            DebugLog.i(TAG, "AI 说话被取消")
        } catch (e: Exception) {
            DebugLog.e(TAG, "AI 说话异常: ${e.message}", e)
        } finally {
            if (_state == DuplexState.AI_SPEAKING && !bargeInDetected) {
                changeState(DuplexState.LISTENING)
            }
            onComplete?.invoke()
        }
    }

    /**
     * 停止 AI 说话（用于插话中断）。
     *
     * 清空播放队列 + 停止当前 AudioTrack 播放。
     */
    fun stopAiSpeaking() {
        DebugLog.i(TAG, "停止 AI 说话")

        bargeInDetected = true
        playQueue.clear()

        runCatching {
            audioTrack?.pause()
            audioTrack?.flush()
        }

        if (_state == DuplexState.AI_SPEAKING) {
            changeState(DuplexState.LISTENING)
        }
    }

    /**
     * 切换用户静音。
     *
     * @param muted true = 关闭麦克风（用户声音不再被采集），false = 打开麦克风
     */
    fun muteUser(muted: Boolean) {
        if (muted) {
            if (_state != DuplexState.MUTED) {
                DebugLog.i(TAG, "用户已静音")
                changeState(DuplexState.MUTED)
                // 不停止录音线程，只是忽略采集的数据
            }
        } else {
            if (_state == DuplexState.MUTED) {
                DebugLog.i(TAG, "用户取消静音")
                changeState(if (isPlaying.get()) DuplexState.AI_SPEAKING else DuplexState.LISTENING)
            }
        }
    }

    /**
     * 检测是否有插话发生（用户在 AI 说话时开口）。
     *
     * @return true 表示检测到插话
     */
    fun checkBargeIn(): Boolean = bargeInDetected

    /**
     * 重置插话标志（在处理完插事后调用）。
     */
    fun resetBargeIn() {
        bargeInDetected = false
    }

    // ==================== 回调注册 ====================

    /**
     * 注册状态变化监听器。
     *
     * @param listener 状态变化回调
     */
    fun onStateChanged(listener: (DuplexState) -> Unit) {
        synchronized(stateListeners) {
            stateListeners.add(listener)
        }
    }

    /**
     * 注册语音检测监听器（用户说完一句话时触发）。
     *
     * @param listener 回调，参数为该段语音的 PCM 数据（16kHz, 16bit, mono）
     */
    fun onSpeechDetected(listener: (ByteArray) -> Unit) {
        synchronized(speechListeners) {
            speechListeners.add(listener)
        }
    }

    /**
     * 注册 ASR 实时文本监听器（partial 结果）。
     *
     * @param listener 回调，参数为实时识别的文字片段
     */
    fun onPartialAsrText(listener: (String) -> Unit) {
        synchronized(partialTextListeners) {
            partialTextListeners.add(listener)
        }
    }

    /**
     * 注册插话事件监听器。
     *
     * 当用户在 AI 说话时开口触发。
     *
     * @param listener 插话事件回调
     */
    fun onBargeIn(listener: () -> Unit) {
        synchronized(bargeInListeners) {
            bargeInListeners.add(listener)
        }
    }

    // ==================== 内部实现 ====================

    /**
     * VAD 处理：分析一帧音频的能量，判断是否为语音/静音。
     *
     * 状态机：
     * SILENCE → SPEECH（能量超过阈值）→ 积累 PCM 到 buffer
     * SPEECH → SILENCE（能量低于阈值且持续 > SILENCE_TIMEOUT_MS）→ 触发 onSpeechDetected
     *
     * @param audioData 一帧 PCM 数据
     * @param size 有效数据长度（字节）
     */
    private fun processVadFrame(audioData: ByteArray, size: Int) {
        // 静音模式下跳过 VAD
        if (_state == DuplexState.MUTED) return

        val energy = calculateRmsEnergy(audioData, size)

        when {
            // 从静音切换到语音
            !vadIsSpeechActive && energy > VAD_ENERGY_THRESHOLD -> {
                // 检查前导静音帧数是否足够（过滤噪音触发）
                if (leadingSilenceFrameCount < VAD_LEADING_SILENCE_FRAMES) {
                    leadingSilenceFrameCount++
                    return
                }

                vadIsSpeechActive = true
                currentSpeechBuffer.reset()
                currentSpeechBuffer.write(audioData, 0, size)
                vadSilenceStartMs = 0

                DebugLog.d(TAG, "VAD: 开始检测到语音 (energy=$energy)")

                // 如果 AI 正在说话，这是插话！
                if (_state == DuplexState.AI_SPEAKING) {
                    handleBargeIn()
                } else if (_state == DuplexState.CONNECTED) {
                    changeState(DuplexState.LISTENING)
                }
            }

            // 语音继续
            vadIsSpeechActive && energy > VAD_ENERGY_THRESHOLD -> {
                currentSpeechBuffer.write(audioData, 0, size)
            }

            // 从语音切换到静音候选
            vadIsSpeechActive && energy <= VAD_ENERGY_THRESHOLD -> {
                if (vadSilenceStartMs == 0L) {
                    vadSilenceStartMs = System.currentTimeMillis()
                }

                // 还没超时，继续积累（可能只是短暂停顿）
                if (System.currentTimeMillis() - vadSilenceStartMs < VAD_SILENCE_TIMEOUT_MS) {
                    currentSpeechBuffer.write(audioData, 0, size)
                } else {
                    // 超时了！判定为一句话结束
                    vadIsSpeechActive = false
                    val speechData = currentSpeechBuffer.toByteArray()
                    currentSpeechBuffer.reset()
                    vadSilenceStartMs = 0

                    DebugLog.i(TAG, "VAD: 检测到一段语音结束 (${speechData.size} bytes)")

                    // 通知上层：收集到一段语音
                    notifySpeechDetected(speechData)
                }
            }

            // 一直在静音
            !vadIsSpeechActive && energy <= VAD_ENERGY_THRESHOLD -> {
                // 计数前导静音帧
                if (leadingSilenceFrameCount < VAD_LEADING_SILENCE_FRAMES) {
                    leadingSilenceFrameCount++
                }
            }
        }
    }

    /**
     * 计算音频帧的 RMS 能量值。
     *
     * RMS (Root Mean Square) 是衡量音频信号幅度的标准方法。
     *
     * @param data PCM 数据（16bit 有符号）
     * @param length 有效数据长度（字节）
     * @return RMS 能量值（0-32767 范围）
     */
    private fun calculateRmsEnergy(data: ByteArray, length: Int): Float {
        if (length < 2) return 0f

        var sum = 0L
        val byteBuffer = ByteBuffer.wrap(data, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        val sampleCount = length / 2

        for (i in 0 until sampleCount) {
            val sample = byteBuffer.short.toInt().toLong()
            sum += sample * sample
        }

        return kotlin.math.sqrt(sum.toFloat() / sampleCount)
    }

    /**
     * 启动录音线程（持续采集）。
     */
    private fun startRecording() {
        val record = audioRecord ?: run {
            DebugLog.e(TAG, "AudioRecord 未初始化")
            return
        }

        resetVadState()

        record.startRecording()

        recordJob = engineScope.launch {
            DebugLog.d(TAG, "录音线程启动")

            val bufferSize = SAMPLE_RATE * BUFFER_SIZE_MS / 1000 * 2  // 20ms 帧，16bit = 2 bytes/sample
            val buffer = ByteArray(bufferSize)

            try {
                while (isActive && isRecording.get()) {
                    val readSize = record.read(buffer, 0, buffer.size)

                    if (readSize > 0) {
                        // VAD 处理
                        processVadFrame(buffer, readSize)
                    } else if (readSize == AudioRecord.ERROR_INVALID_OPERATION) {
                        DebugLog.w(TAG, "AudioRecord 操作无效")
                        break
                    }
                }
            } catch (e: CancellationException) {
                DebugLog.i(TAG, "录音线程被取消")
            } catch (e: Exception) {
                DebugLog.e(TAG, "录音线程异常: ${e.message}", e)
            } finally {
                runCatching { record.stop() }
                DebugLog.d(TAG, "录音线程结束")
            }
        }
    }

    /**
     * 启动播放线程（从队列取数据写入 AudioTrack）。
     */
    private fun startPlayback() {
        val track = audioTrack ?: run {
            DebugLog.e(TAG, "AudioTrack 未初始化")
            return
        }

        track.play()

        playJob = engineScope.launch {
            DebugLog.d(TAG, "播放线程启动")

            try {
                while (isActive && isRecording.get()) {
                    val data = playQueue.poll()

                    if (data != null && data.isNotEmpty()) {
                        isPlaying.set(true)

                        // 分块写入 AudioTrack（避免一次性写入过多导致延迟）
                        var offset = 0
                        while (offset < data.size && isActive && isRecording.get() && !bargeInDetected) {
                            val writeSize = kotlin.math.min(data.size - offset, 3200)  // 每次 100ms
                            track.write(data, offset, writeSize)
                            offset += writeSize

                            // 小延迟让出 CPU
                            if (offset < data.size) {
                                delay(10L)
                            }
                        }

                        isPlaying.set(false)

                        // 检查队列是否空了（AI 说完了）
                        if (playQueue.isEmpty() && _state == DuplexState.AI_SPEAKING && !bargeInDetected) {
                            changeState(DuplexState.LISTENING)
                        }
                    } else {
                        // 队列空了，短暂休眠避免忙等
                        delay(20L)
                    }
                }
            } catch (e: CancellationException) {
                DebugLog.i(TAG, "播放线程被取消")
            } catch (e: Exception) {
                DebugLog.e(TAG, "播放线程异常: ${e.message}", e)
            } finally {
                runCatching { track.stop() }
                isPlaying.set(false)
                DebugLog.d(TAG, "播放线程结束")
            }
        }
    }

    /**
     * 处理插话事件。
     */
    private fun handleBargeIn() {
        DebugLog.i(TAG, "检测到插话！(用户打断了 AI)")

        bargeInDetected = true

        // 通知所有插话监听器
        synchronized(bargeInListeners) {
            bargeInListeners.forEach { listener ->
                try {
                    listener.invoke()
                } catch (e: Exception) {
                    DebugLog.e(TAG, "插话监听器异常: ${e.message}", e)
                }
            }
        }
    }

    /**
     * 通知上层：检测到一段完整的语音。
     */
    private fun notifySpeechDetected(speechData: ByteArray) {
        if (speechData.size < 160) {  // 少于 10ms 的数据视为无效
            DebugLog.d(TAG, "忽略过短的语音片段 (${speechData.size} bytes)")
            return
        }

        synchronized(speechListeners) {
            speechListeners.forEach { listener ->
                try {
                    listener.invoke(speechData)
                } catch (e: Exception) {
                    DebugLog.e(TAG, "语音监听器异常: ${e.message}", e)
                }
            }
        }
    }

    /**
     * 切换状态并通知监听器。
     */
    private fun changeState(newState: DuplexState) {
        if (_state == newState) return

        val oldState = _state
        _state = newState

        DebugLog.d(TAG, "状态转换: $oldState → $newState")

        synchronized(stateListeners) {
            stateListeners.forEach { listener ->
                try {
                    listener.invoke(newState)
                } catch (e: Exception) {
                    DebugLog.e(TAG, "状态监听器异常: ${e.message}", e)
                }
            }
        }
    }

    /**
     * 重置 VAD 状态机。
     */
    private fun resetVadState() {
        vadIsSpeechActive = false
        vadSilenceStartMs = 0
        leadingSilenceFrameCount = 0
        currentSpeechBuffer.reset()
        bargeInDetected = false
    }

    /**
     * 释放所有原生资源。
     */
    private fun releaseResources() {
        runCatching { audioRecord?.release() }
        audioRecord = null

        runCatching { audioTrack?.release() }
        audioTrack = null

        playQueue.clear()
    }

    /**
     * 兼容不同 Kotlin 版本的 withContext。
     */
    private suspend fun <T> withContextCompat(
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        block: suspend kotlinx.coroutines.CoroutineScope.() -> T,
    ): T = kotlinx.coroutines.withContext(dispatcher, block)
}
