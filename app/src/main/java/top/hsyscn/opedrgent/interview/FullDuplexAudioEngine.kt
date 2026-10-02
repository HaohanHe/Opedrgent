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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.tts.TtsPlayer
import top.hsyscn.opedrgent.utils.CrashReporter
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
/**
 * 引擎事件（冻结契约，供 Agent 编排层与 UI 监听）。
 *
 * - [Kind.PIPELINE_RESTARTED]：采集/播放管线发生一次自恢复重建。
 * - [Kind.PIPELINE_FAILED]：管线自恢复次数耗尽或权限缺失，已回落到可恢复状态，等待上层重试。
 * - [Kind.IDLE_TIMEOUT]：LISTENING 状态连续无有效语音超过阈值，仅上抛，由上层决定后续动作。
 */
data class EngineEvent(
    val kind: Kind,
    val message: String = "",
    val silenceMs: Long = 0L,
) {
    enum class Kind {
        PIPELINE_RESTARTED,
        PIPELINE_FAILED,
        IDLE_TIMEOUT,
    }
}

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

        /**
         * Barge-in 连续确认帧数：AI 说话期间需连续这么多帧能量超阈值才判定为用户插话。
         * 按 20ms/帧，12 帧约 240ms（落在 200–300ms 区间），真开口仍可即时打断。
         */
        const val BARGE_IN_CONFIRM_FRAMES = 12

        /**
         * Barge-in 播放期能量阈值：高于普通 [VAD_ENERGY_THRESHOLD]，
         * 用于压制 AEC 残余回声/瞬态噪声造成的误打断。
         */
        const val BARGE_IN_PLAYBACK_ENERGY_THRESHOLD = 500f

        /** Barge-in 冷却时长：触发后短时间内不重复触发。 */
        const val BARGE_IN_COOLDOWN_MS = 1500L

        /** 采集/播放管线异常后允许的最大自恢复重建次数。 */
        const val MAX_RESTART_ATTEMPTS = 3

        /** LISTENING 状态连续无有效语音超过该时长则上抛一次 IDLE_TIMEOUT。 */
        const val IDLE_TIMEOUT_MS = 25_000L
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
    @Volatile
    private var vadSilenceStartMs = 0L       // 静音开始时间
    @Volatile
    private var vadIsSpeechActive = false     // 当前是否在说话
    private var currentSpeechBuffer = ByteArrayOutputStream()  // 当前话语的 PCM 数据
    @Volatile
    private var leadingSilenceFrameCount = 0  // 前导静音帧计数器
    private val speechListeners = mutableListOf<(ByteArray) -> Unit>()  // 话语回调

    // ==================== 播放（下行）====================

    private var audioTrack: AudioTrack? = null
    private val isPlaying = AtomicBoolean(false)
    private val playQueue = ConcurrentLinkedQueue<ByteArray>()  // TTS 音频队列
    private var playJob: Job? = null

    // 插话检测
    private val bargeInListeners = mutableListOf<() -> Unit>()
    @Volatile
    private var bargeInDetected = false

    // Barge-in 连续确认与冷却
    private var bargeInConfirmFrames = 0
    @Volatile
    private var lastBargeInTriggerMs = 0L

    // 管线自恢复计数
    private var recordRestartAttempts = 0
    private var playRestartAttempts = 0

    // 长静音看门狗
    @Volatile
    private var lastSpeechSeenMs = 0L
    @Volatile
    private var idleTimeoutFired = false

    // 引擎事件监听
    private val engineEventListeners = mutableListOf<(EngineEvent) -> Unit>()

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
            audioRecord = createAudioRecord()
            audioTrack = createAudioTrack()

            changeState(DuplexState.CONNECTED)
            DebugLog.i(TAG, "音频通道已连接")

        } catch (e: IllegalStateException) {
            releaseResources()
            throw e
        } catch (e: SecurityException) {
            releaseResources()
            throw e
        }
    }

    /**
     * 按 connect 同口径构建 AudioRecord（VOICE_COMMUNICATION 音源，硬件 AEC）。
     * 采集管线自恢复重建时复用。
     *
     * @throws SecurityException 录音权限缺失
     * @throws IllegalStateException 设备不支持指定音频参数
     */
    private fun createAudioRecord(): AudioRecord {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("缺少 RECORD_AUDIO 权限")
        }

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            throw IllegalStateException("设备不支持指定的音频参数 (sampleRate=$SAMPLE_RATE)")
        }

        val rec = AudioRecord(
            AUDIO_SOURCE,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize.coerceAtLeast(SAMPLE_RATE * BUFFER_SIZE_MS / 1000 * 2)
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("AudioRecord 初始化失败")
        }
        return rec
    }

    /**
     * 按 connect 同口径构建 AudioTrack（USAGE_VOICE_COMMUNICATION + SPEECH）。
     * 播放管线自恢复重建时复用。
     */
    private fun createAudioTrack(): AudioTrack {
        val playBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AUDIO_FORMAT
        )
        if (playBufferSize == AudioTrack.ERROR || playBufferSize == AudioTrack.ERROR_BAD_VALUE) {
            throw IllegalStateException("设备不支持播放参数")
        }

        val track = AudioTrack.Builder()
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

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw IllegalStateException("AudioTrack 初始化失败")
        }
        return track
    }

    /**
     * 断开连接（释放所有资源）。
     *
     * 会自动先调用 [stop] 停止所有活动。
     *
     * 注意：本引擎不持有 [TtsPlayer]，不会自动恢复其播放路由。会话结束时
     * 上层需对曾传入 [aiSpeakText] 的 TtsPlayer 调用 `setCallRoute(false)`，
     * 将播放从 USAGE_VOICE_COMMUNICATION 切回默认 USAGE_MEDIA。
     */
    fun disconnect() {
        DebugLog.i(TAG, "断开音频通道")

        stop()
        releaseResources()
        engineScope.cancel()
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
        bargeInConfirmFrames = 0
        lastBargeInTriggerMs = 0L
        recordRestartAttempts = 0
        playRestartAttempts = 0
        idleTimeoutFired = false
        lastSpeechSeenMs = System.currentTimeMillis()

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

        // 复位 barge-in / 看门狗 / 自恢复计数，保证 stop 后 start 干净重启
        bargeInConfirmFrames = 0
        lastBargeInTriggerMs = 0L
        recordRestartAttempts = 0
        playRestartAttempts = 0
        idleTimeoutFired = false

        // 重置 VAD 状态
        resetVadState()

        // 停在"已连接"态，等待 start 重新进入 LISTENING
        changeState(DuplexState.CONNECTED)
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
     * 使用 [TtsPlayer] 将文本合成为 PCM 音频并由其播放。
     *
     * ## TTS 通话路由约定
     *
     * 本方法进入时会调用 [TtsPlayer.setCallRoute](true)，使 TTS 播放走
     * USAGE_VOICE_COMMUNICATION，与本引擎 VOICE_COMMUNICATION 采集的硬件 AEC 参考通路对齐，
     * 避免 TTS 被自身 ASR 拾取造成回声自激。引擎不持有 TtsPlayer，因此 **不会在 finally 关闭路由**：
     * 上层（Agent 编排层）必须在整段面试会话结束、调用 [disconnect] 之前或之后，
     * 对同一个 [TtsPlayer] 调用 `setCallRoute(false)` 以恢复非通话的 MEDIA 播放路径。
     * 重复进入本方法时该调用幂等，无需额外判断。
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

        // 确保 TTS 播放进入通话路由（与采集 AEC 参考对齐）；幂等，会话结束由上层统一关闭。
        ttsPlayer.setCallRoute(true)

        DebugLog.i(TAG, "AI 说话: '${text.take(50)}...'")

        changeState(DuplexState.AI_SPEAKING)

        try {
            // 注意：这里使用同步方式等待 TTS 合成完成
            // 实际项目中可以使用流式 TTS API 实现更低的延迟
            withContext(Dispatchers.Main) {
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
     *
     * 同时复位连续确认计数与冷却时间，供下一轮 AI 说话重新检测。
     */
    fun resetBargeIn() {
        bargeInDetected = false
        bargeInConfirmFrames = 0
        lastBargeInTriggerMs = 0L
    }

    /**
     * 注册引擎事件监听器（管线自恢复 / 失败 / 长静音看门狗）。
     *
     * 回调在采集/播放协程线程触发，监听方如需更新 UI 请自行切主线程。
     */
    fun onEngineEvent(listener: (EngineEvent) -> Unit) {
        synchronized(engineEventListeners) {
            engineEventListeners.add(listener)
        }
    }

    private fun notifyEvent(kind: EngineEvent.Kind, message: String = "", silenceMs: Long = 0L) {
        val event = EngineEvent(kind, message, silenceMs)
        DebugLog.i(TAG, "引擎事件: kind=$kind, msg=$message, silenceMs=$silenceMs")
        synchronized(engineEventListeners) {
            engineEventListeners.forEach { listener ->
                try {
                    listener.invoke(event)
                } catch (e: Exception) {
                    DebugLog.e(TAG, "引擎事件监听器异常: ${e.message}", e)
                }
            }
        }
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

        // AI 说话期间：barge-in 走独立的连续确认逻辑（不进入普通 VAD 状态机），
        // 连续多帧强能量才判定用户插话，压制 AEC 残余回声/瞬态噪声误触发。
        if (_state == DuplexState.AI_SPEAKING) {
            handleBargeInConfirmation(energy, audioData, size)
            return
        }

        when {
            // 从静音切换到语音
            !vadIsSpeechActive && energy > VAD_ENERGY_THRESHOLD -> {
                // 检查前导静音帧数是否足够（过滤噪音触发）
                if (leadingSilenceFrameCount < VAD_LEADING_SILENCE_FRAMES) {
                    leadingSilenceFrameCount++
                    return
                }

                vadIsSpeechActive = true
                synchronized(this) {
                    currentSpeechBuffer.reset()
                    currentSpeechBuffer.write(audioData, 0, size)
                }
                vadSilenceStartMs = 0

                // 检测到有效语音：复位长静音看门狗
                lastSpeechSeenMs = System.currentTimeMillis()
                idleTimeoutFired = false

                DebugLog.d(TAG, "VAD: 开始检测到语音 (energy=$energy)")

                if (_state == DuplexState.CONNECTED) {
                    changeState(DuplexState.LISTENING)
                }
            }

            // 语音继续
            vadIsSpeechActive && energy > VAD_ENERGY_THRESHOLD -> {
                synchronized(this) {
                    currentSpeechBuffer.write(audioData, 0, size)
                }
                // 语音持续期间刷新看门狗
                lastSpeechSeenMs = System.currentTimeMillis()
            }

            // 从语音切换到静音候选
            vadIsSpeechActive && energy <= VAD_ENERGY_THRESHOLD -> {
                if (vadSilenceStartMs == 0L) {
                    vadSilenceStartMs = System.currentTimeMillis()
                }

                // 还没超时，继续积累（可能只是短暂停顿）
                if (System.currentTimeMillis() - vadSilenceStartMs < VAD_SILENCE_TIMEOUT_MS) {
                    synchronized(this) {
                        currentSpeechBuffer.write(audioData, 0, size)
                    }
                } else {
                    // 超时了！判定为一句话结束
                    vadIsSpeechActive = false
                    val speechData = synchronized(this) {
                        val data = currentSpeechBuffer.toByteArray()
                        currentSpeechBuffer.reset()
                        data
                    }
                    vadSilenceStartMs = 0
                    // 一句话结束，刷新看门狗起点
                    lastSpeechSeenMs = System.currentTimeMillis()

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
     * AI 说话期间的 barge-in 连续确认。
     *
     * 需连续 [BARGE_IN_CONFIRM_FRAMES] 帧能量高于 [BARGE_IN_PLAYBACK_ENERGY_THRESHOLD]
     * 才判定用户插话；中途任一帧不达标即清零。触发后进入 [BARGE_IN_COOLDOWN_MS] 冷却，
     * 冷却期内不重复触发。确认通过后转入 LISTENING 并把当前帧作为用户语音首帧缓冲。
     */
    private fun handleBargeInConfirmation(energy: Float, audioData: ByteArray, size: Int) {
        val now = System.currentTimeMillis()
        val inCooldown = lastBargeInTriggerMs != 0L && (now - lastBargeInTriggerMs < BARGE_IN_COOLDOWN_MS)

        if (energy > BARGE_IN_PLAYBACK_ENERGY_THRESHOLD && !inCooldown) {
            bargeInConfirmFrames++
            if (bargeInConfirmFrames >= BARGE_IN_CONFIRM_FRAMES) {
                bargeInConfirmFrames = 0
                lastBargeInTriggerMs = now

                handleBargeIn()

                // 转入听用户说话，并以当前帧开启用户语音缓冲
                changeState(DuplexState.LISTENING)
                vadIsSpeechActive = true
                synchronized(this) {
                    currentSpeechBuffer.reset()
                    currentSpeechBuffer.write(audioData, 0, size)
                }
                vadSilenceStartMs = 0L
                lastSpeechSeenMs = now
                idleTimeoutFired = false
            }
        } else {
            // 任一帧不达标（或冷却中）即清零，避免残余回声累积误触发
            bargeInConfirmFrames = 0
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
        var record = audioRecord
        if (record == null) {
            // 管线曾彻底失败并释放了 AudioRecord：按 connect 同口径重建，支持上层重试
            record = try {
                createAudioRecord().also { audioRecord = it }
            } catch (e: Exception) {
                CrashReporter.logError(TAG, "AudioRecord 重建失败", e)
                DebugLog.e(TAG, "AudioRecord 重建失败: ${e.message}", e)
                notifyEvent(EngineEvent.Kind.PIPELINE_FAILED, "采集管线重建失败: ${e.message}")
                return
            }
        }

        resetVadState()
        record.startRecording()

        recordJob = engineScope.launch {
            DebugLog.d(TAG, "录音线程启动")

            val bufferSize = SAMPLE_RATE * BUFFER_SIZE_MS / 1000 * 2  // 20ms 帧，16bit = 2 bytes/sample
            val buffer = ByteArray(bufferSize)

            try {
                loop@ while (isActive && isRecording.get()) {
                    val readSize: Int
                    try {
                        readSize = record!!.read(buffer, 0, buffer.size)
                    } catch (e: SecurityException) {
                        // 重建 AudioRecord 所需录音权限缺失
                        failRecordingPipeline("录音权限缺失: ${e.message}")
                        break
                    } catch (e: Exception) {
                        CrashReporter.logError(TAG, "录音读取异常", e)
                        DebugLog.e(TAG, "录音读取异常: ${e.message}", e)
                        if (!tryRestartRecording(record!!)) {
                            failRecordingPipeline("采集管线失败: ${e.message}")
                            break
                        }
                        record = audioRecord ?: break
                        runCatching { record!!.startRecording() }
                        continue@loop
                    }

                    when {
                        readSize > 0 -> {
                            // 成功读到数据即复位重启计数
                            recordRestartAttempts = 0
                            processVadFrame(buffer, readSize)
                            checkIdleTimeout()
                        }

                        readSize == AudioRecord.ERROR_INVALID_OPERATION -> {
                            DebugLog.w(TAG, "AudioRecord 操作无效，尝试重启采集管线")
                            if (!tryRestartRecording(record!!)) {
                                failRecordingPipeline("采集管线操作无效")
                                break
                            }
                            record = audioRecord ?: break
                            runCatching { record.startRecording() }
                        }

                        else -> {
                            // ERROR / ERROR_BAD_VALUE 等，短暂让出 CPU 后继续
                            delay(10L)
                        }
                    }
                }
            } catch (e: CancellationException) {
                DebugLog.i(TAG, "录音线程被取消")
            } finally {
                runCatching { record!!.stop() }
                DebugLog.d(TAG, "录音线程结束")
            }
        }
    }

    /**
     * 采集管线有限次自恢复：stop/release 当前 AudioRecord → 按 connect 同口径重建 → 返回是否成功。
     * 成功上抛 [EngineEvent.Kind.PIPELINE_RESTARTED]；次数耗尽返回 false（由调用方上抛 PIPELINE_FAILED）。
     */
    private fun tryRestartRecording(old: AudioRecord): Boolean {
        recordRestartAttempts++
        if (recordRestartAttempts > MAX_RESTART_ATTEMPTS) {
            DebugLog.e(TAG, "采集管线重启次数耗尽 ($recordRestartAttempts)")
            return false
        }
        DebugLog.w(TAG, "采集管线重启 attempt=$recordRestartAttempts/$MAX_RESTART_ATTEMPTS")
        runCatching { old.stop() }
        runCatching { old.release() }
        if (old === audioRecord) audioRecord = null
        return try {
            val rec = createAudioRecord()
            audioRecord = rec
            notifyEvent(EngineEvent.Kind.PIPELINE_RESTARTED, "采集管线已重建 (attempt=$recordRestartAttempts)")
            true
        } catch (e: Exception) {
            DebugLog.e(TAG, "采集管线重建失败: ${e.message}", e)
            false
        }
    }

    /**
     * 采集管线彻底失败：上抛 PIPELINE_FAILED，停止采集并回落到可恢复的 CONNECTED，等待上层重试。
     */
    private fun failRecordingPipeline(message: String) {
        notifyEvent(EngineEvent.Kind.PIPELINE_FAILED, message)
        isRecording.set(false)
        if (_state == DuplexState.LISTENING || _state == DuplexState.AI_SPEAKING) {
            changeState(DuplexState.CONNECTED)
        }
    }

    /**
     * 长静音看门狗：LISTENING 状态连续无有效语音达到 [IDLE_TIMEOUT_MS] 时上抛一次 IDLE_TIMEOUT。
     * 检测到语音后复位，可再次触发；引擎本身不据此说话。
     */
    private fun checkIdleTimeout() {
        if (_state != DuplexState.LISTENING) return
        if (vadIsSpeechActive) return
        if (idleTimeoutFired) return
        val silentFor = System.currentTimeMillis() - lastSpeechSeenMs
        if (silentFor >= IDLE_TIMEOUT_MS) {
            idleTimeoutFired = true
            notifyEvent(EngineEvent.Kind.IDLE_TIMEOUT, "长静音超时", silentFor)
        }
    }

    /**
     * 启动播放线程（从队列取数据写入 AudioTrack）。
     */
    private fun startPlayback() {
        var track = audioTrack
        if (track == null) {
            // 播放管线曾彻底失败并释放了 AudioTrack：按 connect 同口径重建，支持上层重试
            track = try {
                createAudioTrack().also { audioTrack = it }
            } catch (e: Exception) {
                DebugLog.e(TAG, "AudioTrack 重建失败: ${e.message}", e)
                notifyEvent(EngineEvent.Kind.PIPELINE_FAILED, "播放管线重建失败: ${e.message}")
                return
            }
        }

        track.play()

        playJob = engineScope.launch {
            DebugLog.d(TAG, "播放线程启动")

            try {
                loop@ while (isActive && isRecording.get()) {
                    val data = playQueue.poll()

                    if (data == null || data.isEmpty()) {
                        // 队列空了，短暂休眠避免忙等
                        delay(20L)
                        continue@loop
                    }

                    isPlaying.set(true)

                    // 分块写入 AudioTrack（避免一次性写入过多导致延迟）
                    var offset = 0
                    try {
                        while (offset < data.size && isActive && isRecording.get() && !bargeInDetected) {
                            val writeSize = kotlin.math.min(data.size - offset, 3200)  // 每次 100ms
                            track!!.write(data, offset, writeSize)
                            offset += writeSize
                            playRestartAttempts = 0

                            // 小延迟让出 CPU
                            if (offset < data.size) {
                                delay(10L)
                            }
                        }
                    } catch (e: CancellationException) {
                        // 协程取消必须向上抛，不能被异常恢复逻辑吞掉
                        throw e
                    } catch (e: Exception) {
                        DebugLog.e(TAG, "播放写入异常: ${e.message}", e)
                        if (!tryRestartPlayback(track!!)) {
                            notifyEvent(EngineEvent.Kind.PIPELINE_FAILED, "播放管线失败: ${e.message}")
                            playQueue.clear()
                            isPlaying.set(false)
                            // 无法恢复时不卡在 AI_SPEAKING，回落到 LISTENING
                            if (_state == DuplexState.AI_SPEAKING) {
                                changeState(DuplexState.LISTENING)
                            }
                            break
                        }
                        track = audioTrack ?: break
                        runCatching { track.play() }
                        // 丢弃写入到一半的分片，继续消费队列后续数据
                    }

                    isPlaying.set(false)

                    // 检查队列是否空了（AI 说完了）
                    if (playQueue.isEmpty() && _state == DuplexState.AI_SPEAKING && !bargeInDetected) {
                        changeState(DuplexState.LISTENING)
                    }
                }
            } catch (e: CancellationException) {
                DebugLog.i(TAG, "播放线程被取消")
            } finally {
                runCatching { track!!.stop() }
                isPlaying.set(false)
                DebugLog.d(TAG, "播放线程结束")
            }
        }
    }

    /**
     * 播放管线有限次自恢复：stop/release 当前 AudioTrack → 按 connect 同口径重建。
     * 成功上抛 [EngineEvent.Kind.PIPELINE_RESTARTED]；次数耗尽返回 false。
     */
    private fun tryRestartPlayback(old: AudioTrack): Boolean {
        playRestartAttempts++
        if (playRestartAttempts > MAX_RESTART_ATTEMPTS) {
            DebugLog.e(TAG, "播放管线重启次数耗尽 ($playRestartAttempts)")
            return false
        }
        DebugLog.w(TAG, "播放管线重启 attempt=$playRestartAttempts/$MAX_RESTART_ATTEMPTS")
        runCatching { old.stop() }
        runCatching { old.release() }
        if (old === audioTrack) audioTrack = null
        return try {
            val t = createAudioTrack()
            audioTrack = t
            notifyEvent(EngineEvent.Kind.PIPELINE_RESTARTED, "播放管线已重建 (attempt=$playRestartAttempts)")
            true
        } catch (e: Exception) {
            DebugLog.e(TAG, "播放管线重建失败: ${e.message}", e)
            false
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
        synchronized(this) {
            currentSpeechBuffer.reset()
        }
        bargeInDetected = false
        bargeInConfirmFrames = 0
        idleTimeoutFired = false
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
}
