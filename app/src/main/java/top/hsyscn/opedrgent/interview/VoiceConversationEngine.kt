package top.hsyscn.opedrgent.interview

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.settings.ApiSettings
import top.hsyscn.opedrgent.stt.AsrManager
import top.hsyscn.opedrgent.stt.StreamingRecognitionState
import top.hsyscn.opedrgent.tts.MimoTtsClient.StyleControl
import top.hsyscn.opedrgent.tts.TtsPlayer
import top.hsyscn.opedrgent.utils.DebugLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 语音对话状态枚举（兼容旧接口）。
 *
 * @see FullDuplexAudioEngine.DuplexState 新的全双工状态枚举
 */
enum class ConversationState {
    /** 空闲 */
    IDLE,

    /** AI 正在说话（TTS 播放中） */
    AI_SPEAKING,

    /** 正在监听用户说话（ASR 采集） */
    LISTENING,

    /** 处理中（LLM 调用） */
    PROCESSING,

    /** 错误状态 */
    ERROR,
}

/**
 * 语音对话引擎 v3.0 — 全双工电话模式。
 *
 * ## 架构对比
 *
 * v2.0（半双工对讲机）：              v3.0（全双工电话）：
 * ┌──────────────┐                  ┌──────────────────┐
 * 用户按住录音     │                  │ AudioRecord(持续)  │
 * ↓               │                  ↓  并行             │
 * ASR 识别         │                  AudioTrack(同时播放) │
 * ↓               │                  ↓                   │
 * LLM 处理         │   ──升级──→       FullDuplexAudioEngine│
 * ↓               │                  ├→ VAD(自动断句)      │
 * TTS 播放         │                  ├→ AEC(回声消除)      │
 * ↓               │                  └→ BargeIn(插话检测)  │
 * 等待下次按住     │                                   │
 * └──────────────┘                  └──────────────────┘
 *
 * ## 核心特性
 * 1. **全双工**：录音和播放同时进行，无需手动切换
 * 2. **自动断句**：VAD 检测用户自然停顿后自动提交
 * 3. **插话打断**：用户可在 AI 说话时随时开口，立即响应
 * 4. **硬件 AEC**：使用 VOICE_COMMUNICATION 音源的回声消除
 * 5. **低延迟**：目标端到端 < 500ms（VAD→ASR→LLM→TTS 流水线）
 *
 * ## 使用方式
 * ```kotlin
 * val engine = VoiceConversationEngine(context, ttsPlayer, apiSettings)
 * engine.startFullDuplex(
 *     scenario = TtsScenario.INTERVIEW,
 *     onAiSpeak = { text -> ui.showAiMessage(text) },
 *     onUserSpeak = { text -> ui.showMessage(text) },
 *     onStateChange = { state -> ui.updateCallUI(state) },
 *     getAiResponse = { input -> llm.generate(input) },
 * )
 * ```
 *
 * ## 兼容性
 * - 保留旧的 [startConversationLoop] 接口（内部委托给 [startFullDuplex]）
 * - 新代码建议直接使用 [startFullDuplex] API
 */
class VoiceConversationEngine(
    private val context: Context,
    private val ttsPlayer: TtsPlayer,
    private val apiSettings: ApiSettings,
) {

    companion object {
        private const val TAG = "VoiceConvEngineV3"

        /** 面试官默认声音：白桦（成熟男声） */
        const val DEFAULT_INTERVIEWER_VOICE = "白桦"

        /** 默认 TTS 场景 */
        val DEFAULT_TTS_SCENARIO = TtsScenario.INTERVIEW
    }

    // ==================== 全双工引擎 ====================

    /**
     * 内部持有的全双工音频引擎实例。
     */
    private val duplexEngine = FullDuplexAudioEngine(context)

    // ==================== 状态管理 ====================

    /**
     * 对话是否活跃（用于控制主循环）。
     */
    private val conversationActive = AtomicBoolean(false)

    /**
     * 是否正在处理中（LLM 调用期间）。
     */
    private val isProcessing = AtomicBoolean(false)

    // ==================== ASR 相关 ====================

    /**
     * ASR 管理器（用于将 PCM 转为文字）。
     */
    private var asrManager: AsrManager? = null

    /**
     * 当前 ASR 流式识别 Job。
     */
    private var asrJob: Job? = null
    private var asrProcessingJob: Job? = null

    /**
     * 当前 TTS 播放 Job。
     */
    private var ttsJob: Job? = null

    // ==================== 当前轮次状态 ====================

    /**
     * 用户最新输入文本（由 ASR 填充）。
     */
    @Volatile
    private var latestUserText: String = ""

    /**
     * 等待用户输入完成的信号。
     */
    private var waitingForUserInput = false

    /**
     * 当前对话轮次计数器（用于海马体漂移检测）。
     */
    @Volatile
    private var turnCounter: Int = 0

    /**
     * AI 上一轮回复（用于海马体漂移检测）。
     */
    @Volatile
    private var lastAiResponse: String = ""

    /**
     * 海马体记忆系统实例（可选，用于注意力管理）。
     */
    @Volatile
    private var hippo: HippocampusMemory? = null

    // ==================== 公开 API：全双工模式（推荐）====================

    /**
     * 开始全双工语音对话（推荐使用此方法）。
     *
     * 工作流程：
     * 1. 连接并启动全双工音频引擎
     * 2. VAD 自动检测用户说话 → ASR 实时识别
     * 3. 用户说完一句话后自动提交给 LLM
     * 4. LLM 回复通过 TTS 播放（支持插话打断）
     * 5. 循环步骤 2-4，直到 [stopFullDuplex] 被调用
     *
     * @param scenario TTS 语音场景（控制语音风格、语速、音调等）
     * @param onAiSpeak AI 说话时的回调（用于 UI 更新文字）
     * @param onUserSpeak 用户说话完成后的回调
     * @param onPartialUserText ASR 实时识别文字回调（显示打字机效果）
     * @param onStateChange 全双工状态变化回调（可绑定 Compose UI）
     * @param onBargeIn 用户打断 AI 时的回调
     * @param getAiResponse LLM 调用接口，传入用户输入返回 AI 回复
     * @param interviewConfig 可选的面试配置（用于自动创建海马体实例）
     * @param hippo 可选的海马体实例（如果提供，将启用注意力管理）
     */
    suspend fun startFullDuplex(
        scenario: TtsScenario = DEFAULT_TTS_SCENARIO,
        onAiSpeak: (String) -> Unit,
        onUserSpeak: (String) -> Unit,
        onPartialUserText: (String) -> Unit = {},
        onStateChange: (FullDuplexAudioEngine.DuplexState) -> Unit,
        onBargeIn: () -> Unit = {},
        getAiResponse: suspend (userInput: String?) -> String,
        interviewConfig: InterviewConfig? = null,
        hippo: HippocampusMemory? = null,
    ) {
        if (!conversationActive.compareAndSet(false, true)) {
            DebugLog.w(TAG, "对话已在进行中")
            return
        }

        DebugLog.i(TAG, "开始全双工语音对话 [场景：${scenario.label}]")

        // 初始化海马体（如果提供了配置或实例）
        this@VoiceConversationEngine.hippo = hippo
        if (this@VoiceConversationEngine.hippo == null && interviewConfig != null) {
            DebugLog.i(TAG, "自动创建海马体实例")
            this@VoiceConversationEngine.hippo = InterviewAgent.createSession(
                sessionId = "voice_${System.currentTimeMillis()}",
                config = interviewConfig,
            )
        }

        // 重置轮次计数器
        turnCounter = 0
        lastAiResponse = ""

        try {
            // 步骤1：初始化 ASR 管理器
            asrManager = AsrManager(context, apiSettings)
            asrManager?.ensureInitialized()

            // 步骤2：连接全双工音频引擎
            duplexEngine.connect()

            // 注册全双工引擎回调
            setupDuplexCallbacks(
                scenario = scenario,
                onAiSpeak = onAiSpeak,
                onUserSpeak = onUserSpeak,
                onPartialUserText = onPartialUserText,
                onLegacyStateChange = { legacyState ->
                    // 将旧状态映射到新状态通知 UI
                    when (legacyState) {
                        ConversationState.IDLE -> onStateChange(FullDuplexAudioEngine.DuplexState.IDLE)
                        ConversationState.AI_SPEAKING -> onStateChange(FullDuplexAudioEngine.DuplexState.AI_SPEAKING)
                        ConversationState.LISTENING -> onStateChange(FullDuplexAudioEngine.DuplexState.LISTENING)
                        ConversationState.PROCESSING -> {}  // PROCESSING 无对应状态
                        ConversationState.ERROR -> {}
                    }
                },
                onBargeIn = onBargeIn,
                getAiResponse = getAiResponse,
            )

            // 同步初始状态
            onStateChange(duplexEngine.state)

            // 步骤3：启动全双工音频管线
            duplexEngine.start()

            // 步骤4：获取第一句话的 AI 回复（开场白）
            if (conversationActive.get()) {
                val openingLine = withContext(Dispatchers.IO) {
                    getAiResponse(null)
                }

                if (conversationActive.get() && openingLine.isNotBlank()) {
                    onAiSpeak(openingLine)

                    duplexEngine.aiSpeakText(
                        text = openingLine,
                        ttsPlayer = ttsPlayer,
                        scenario = scenario,
                        voiceId = DEFAULT_INTERVIEWER_VOICE,
                    )
                }
            }

            // 步骤5：保持运行直到停止信号
            // 全双工模式下，事件驱动（VAD 触发），不需要主动循环
            while (conversationActive.get() && duplexEngine.state != FullDuplexAudioEngine.DuplexState.IDLE) {
                delay(100L)
            }

        } catch (e: CancellationException) {
            DebugLog.i(TAG, "全双工对话被取消")
        } catch (e: Exception) {
            DebugLog.e(TAG, "全双工对话异常: ${e.message}", e)
        } finally {
            stopFullDuplex()
        }
    }

    /**
     * 停止全双工对话。
     *
     * 会中断当前正在进行的 TTS 播放和 ASR 监听，
     * 并释放全双工音频引擎资源。
     */
    fun stopFullDuplex() {
        if (!conversationActive.compareAndSet(true, false)) {
            return
        }

        DebugLog.i(TAG, "停止全双工对话")

        // 输出海马体漂移报告（如果有）
        hippo?.let { h ->
            val report = h.getDriftReport()
            DebugLog.i(TAG, "海马体漂移报告:\n${report.summary}")
        }

        // 清理海马体
        hippo = null
        turnCounter = 0
        lastAiResponse = ""

        // 停止 TTS
        ttsJob?.cancel()
        ttsJob = null
        ttsPlayer.stop()

        // 停止 ASR 处理协程
        asrProcessingJob?.cancel()
        asrProcessingJob = null

        // 停止 ASR
        stopListening()

        // 断开全双工引擎
        duplexEngine.disconnect()

        isProcessing.set(false)
        waitingForUserInput = false
    }

    // ==================== 公开 API：兼容旧接口 ====================

    /**
     * 开始一轮完整的对话循环（兼容旧接口）。
     *
     * 内部委托给 [startFullDuplex] 实现，
     * 保持与 v2.0 版本的 API 兼容性。
     *
     * @param scenario TTS 语音场景（控制语音风格、语速、音调等）
     * @param onAiSpeak AI 说话时的回调（用于 UI 更新文字）
     * @param onUserSpeak 用户说话完成后的回调
     * @param onStateChange 对话状态变化回调（旧版 [ConversationState]）
     * @param getAiResponse LLM 调用接口，传入用户输入返回 AI 回复
     *
     * @deprecated 请使用 [startFullDuplex] 获得完整的全双工体验
     */
    @Deprecated("请使用 startFullDuplex() 获得完整的全双工体验", ReplaceWith("startFullDuplex"))
    suspend fun startConversationLoop(
        scenario: TtsScenario = DEFAULT_TTS_SCENARIO,
        onAiSpeak: (String) -> Unit,
        onUserSpeak: (String) -> Unit,
        onStateChange: (ConversationState) -> Unit,
        getAiResponse: suspend (userInput: String?) -> String,
    ) {
        // 将旧的状态回调包装为新的 DuplexState 回调
        startFullDuplex(
            scenario = scenario,
            onAiSpeak = onAiSpeak,
            onUserSpeak = onUserSpeak,
            onStateChange = { duplexState ->
                val legacyState = when (duplexState) {
                    FullDuplexAudioEngine.DuplexState.IDLE -> ConversationState.IDLE
                    FullDuplexAudioEngine.DuplexState.CONNECTED -> ConversationState.LISTENING
                    FullDuplexAudioEngine.DuplexState.AI_SPEAKING -> ConversationState.AI_SPEAKING
                    FullDuplexAudioEngine.DuplexState.LISTENING -> ConversationState.LISTENING
                    FullDuplexAudioEngine.DuplexState.MUTED -> ConversationState.LISTENING
                }
                onStateChange(legacyState)
            },
            getAiResponse = getAiResponse,
        )
    }

    /**
     * 停止整个对话循环（兼容旧接口）。
     *
     * 内部调用 [stopFullDuplex]。
     *
     * @deprecated 请使用 [stopFullDuplex]
     */
    @Deprecated("请使用 stopFullDuplex()", ReplaceWith("stopFullDuplex"))
    fun stopConversation() {
        stopFullDuplex()
    }

    /**
     * 切换用户静音状态（全双工通话控制）。
     *
     * @param muted true = 静音用户（关闭麦克风），false = 取消静音
     */
    fun muteUser(muted: Boolean) {
        duplexEngine.muteUser(muted)
    }

    /**
     * 静音用户。
     */
    fun muteUser() {
        duplexEngine.muteUser(true)
    }

    /**
     * 取消用户静音。
     */
    fun unmuteUser() {
        duplexEngine.muteUser(false)
    }

    // ==================== 公共查询方法 ====================

    /**
     * 检查是否正在监听（兼容旧接口）。
     */
    fun isCurrentlyListening(): Boolean =
        duplexEngine.state == FullDuplexAudioEngine.DuplexState.LISTENING ||
        duplexEngine.state == FullDuplexAudioEngine.DuplexState.CONNECTED

    /**
     * 检查对话是否活跃。
     */
    fun isConversationActive(): Boolean = conversationActive.get()

    /**
     * 获取当前全双工状态（新版 API）。
     */
    fun getCurrentDuplexState(): FullDuplexAudioEngine.DuplexState = duplexEngine.state

    /**
     * 获取当前海马体实例（如果已启用）。
     *
     * @return 海马体实例，如果未启用则返回 null
     */
    fun getHippocampus(): HippocampusMemory? = hippo

    /**
     * 获取海马体漂移报告（对话结束后调用）。
     *
     * @return 漂移报告，如果未启用海马体则返回 null
     */
    fun getDriftReport(): HippocampusMemory.DriftReport? = hippo?.getDriftReport()

    // ==================== 内部实现 ====================

    /**
     * 设置全双工引擎的所有回调。
     */
    private fun setupDuplexCallbacks(
        scenario: TtsScenario,
        onAiSpeak: (String) -> Unit,
        onUserSpeak: (String) -> Unit,
        onPartialUserText: (String) -> Unit,
        onLegacyStateChange: (ConversationState) -> Unit,
        onBargeIn: () -> Unit,
        getAiResponse: suspend (userInput: String?) -> String,
    ) {
        // 1. 语音检测回调（VAD 判定为一句话结束）
        duplexEngine.onSpeechDetected { pcmData ->
            handleUserSpeechDetected(pcmData, scenario, onAiSpeak, onUserSpeak, onPartialUserText, onLegacyStateChange, onBargeIn, getAiResponse)
        }

        // 2. 插话回调
        duplexEngine.onBargeIn {
            DebugLog.i(TAG, "用户打断了 AI！")
            onBargeIn.invoke()

            // 停止当前 TTS 播放
            stopCurrentTts()

            // 重置插话标志以便下次检测
            duplexEngine.resetBargeIn()
        }

        // 3. 状态变化回调（同步到 UI）
        duplexEngine.onStateChanged { duplexState ->
            DebugLog.d(TAG, "全双工状态: $duplexState")

            // 映射到旧状态
            when (duplexState) {
                FullDuplexAudioEngine.DuplexState.AI_SPEAKING -> onLegacyStateChange(ConversationState.AI_SPEAKING)
                FullDuplexAudioEngine.DuplexState.LISTENING -> onLegacyStateChange(ConversationState.LISTENING)
                FullDuplexAudioEngine.DuplexState.IDLE -> onLegacyStateChange(ConversationState.IDLE)
                else -> {}  // CONNECTED/MUTED 不需要特殊处理
            }
        }
    }

    /**
     * 处理用户语音事件（VAD 检测到一段完整话语）。
     *
     * 流程：
     * 1. 提交 PCM 给 ASR 引擎进行识别
     * 2. 等待最终识别结果
     * 3. 通知 UI 显示用户文字
     * 4. 调用 LLM 生成回复
     * 5. 通过 TTS 播放回复
     */
    private fun handleUserSpeechDetected(
        pcmData: ByteArray,
        scenario: TtsScenario,
        onAiSpeak: (String) -> Unit,
        onUserSpeak: (String) -> Unit,
        onPartialUserText: (String) -> Unit,
        onLegacyStateChange: (ConversationState) -> Unit,
        onBargeIn: () -> Unit,
        getAiResponse: suspend (userInput: String?) -> String,
    ) {
        if (!conversationActive.get()) return

        DebugLog.i(TAG, "收集到用户语音 (${pcmData.size} bytes)，开始 ASR 识别...")

        // 标记正在处理用户输入
        isProcessing.set(true)
        onLegacyStateChange(ConversationState.PROCESSING)

        // 启动 ASR 识别协程
        asrProcessingJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                // 使用流式 ASR 识别
                val recognizedText = recognizePcmData(pcmData, onPartialUserText)

                if (!conversationActive.get() || recognizedText.isBlank()) {
                    isProcessing.set(false)
                    if (conversationActive.get()) {
                        onLegacyStateChange(ConversationState.LISTENING)
                    }
                    return@launch
                }

                DebugLog.d(TAG, "ASR 结果: '${recognizedText.take(100)}'")

                // 通知 UI：用户说的话
                onUserSpeak(recognizedText)
                latestUserText = recognizedText
                waitingForUserInput = false

                // 在 LLM 调用前注入海马体注意力上下文
                val h = this@VoiceConversationEngine.hippo
                val aiInputForLlm = if (h != null && recognizedText.isNotBlank()) {
                    val attentionCtx = h.prepareTurnContext(
                        turnIndex = turnCounter,
                        userMessage = recognizedText,
                        lastAiResponse = lastAiResponse,
                    )
                    // 将注意力上下文拼接到用户输入前面（作为系统提示）
                    if (attentionCtx.isNotBlank()) "[系统提醒]\n$attentionCtx\n\n[用户输入]\n$recognizedText" else recognizedText
                } else {
                    recognizedText
                }

                // 调用 LLM 生成回复
                val aiResponse = withContext(Dispatchers.IO) {
                    getAiResponse(aiInputForLlm)
                }

                if (!conversationActive.get() || aiResponse.isBlank()) {
                    isProcessing.set(false)
                    if (conversationActive.get()) {
                        onLegacyStateChange(ConversationState.LISTENING)
                    }
                    return@launch
                }

                DebugLog.i(TAG, "AI 回复: '${aiResponse.take(100)}'")

                // 记录海马体漂移检测
                this@VoiceConversationEngine.hippo?.detectDrift(turnCounter, recognizedText, aiResponse)

                // 更新轮次计数器和上一轮回复
                turnCounter++
                lastAiResponse = aiResponse

                // 定期更新关键信息快照
                if (turnCounter > 0 && turnCounter % HippocampusMemory.SNAPSHOT_INTERVAL == 0) {
                    this@VoiceConversationEngine.hippo?.updateCriticalSnapshot(turnCounter, "第${turnCounter}轮语音对话完成")
                }

                // 通知 UI：AI 的回复文字
                onAiSpeak(aiResponse)

                // 通过全双工引擎播放 TTS
                duplexEngine.aiSpeakText(
                    text = aiResponse,
                    ttsPlayer = ttsPlayer,
                    scenario = scenario,
                    voiceId = DEFAULT_INTERVIEWER_VOICE,
                )

            } catch (e: CancellationException) {
                DebugLog.i(TAG, "用户语音处理被取消")
            } catch (e: Exception) {
                DebugLog.e(TAG, "用户语音处理异常: ${e.message}", e)
            } finally {
                isProcessing.set(false)
                if (conversationActive.get()) {
                    onLegacyStateChange(ConversationState.LISTENING)
                }
            }
        }
    }

    /**
     * 识别 PCM 音频数据。
     *
     * 尝试使用流式 ASR 引擎识别，
     * 如果不可用则返回空字符串。
     *
     * @return 识别出的文本
     */
    private suspend fun recognizePcmData(
        pcmData: ByteArray,
        onPartialText: (String) -> Unit,
    ): String {
        val manager = asrManager ?: run {
            DebugLog.e(TAG, "ASR 管理器未初始化")
            return ""
        }

        return try {
            // 注意：这里简化处理，实际项目中应该将 PCM 数据送入 ASR 引擎
            // 由于 AsrManager.startStreaming() 是基于麦克风流式采集的，
            // 对于离线 PCM 数据，可能需要使用其他接口或扩展 AsrManager

            // 当前实现：启动流式识别等待结果
            // TODO: 集成离线 PCM 识别接口（如 Whisper API 或本地模型）
            var finalResult = ""

            asrJob = CoroutineScope(Dispatchers.IO).launch {
                val flow: Flow<StreamingRecognitionState> = manager.startStreaming()
                flow
                    .catch { e ->
                        DebugLog.e(TAG, "ASR 流异常: ${e.message}", e)
                    }
                    .collect { state ->
                        when (state) {
                            is StreamingRecognitionState.Recognizing -> {
                                // 实时识别结果（partial）
                                onPartialText(state.partialText)
                            }
                            is StreamingRecognitionState.FinalResult -> {
                                // 最终识别结果
                                finalResult = state.text
                                DebugLog.i(TAG, "ASR 最终结果: '${finalResult.take(100)}'")
                            }
                            is StreamingRecognitionState.Error -> {
                                DebugLog.e(TAG, "ASR 错误: ${state.message}")
                            }
                            is StreamingRecognitionState.Stopped -> {
                                DebugLog.d(TAG, "ASR 已停止")
                            }
                            is StreamingRecognitionState.Listening -> {
                                DebugLog.d(TAG, "ASR 进入监听状态")
                            }
                        }
                    }
            }

            // 等待 ASR 返回最终结果（VAD 检测到静默后 ASR 会自动出结果，这里给一个上限超时）
            delay(1500L)

            asrJob?.cancel()
            asrJob = null

            finalResult

        } catch (e: CancellationException) {
            ""
        } catch (e: Exception) {
            DebugLog.e(TAG, "ASR 识别失败: ${e.message}", e)
            ""
        }
    }

    /**
     * 开始监听用户说话（兼容旧接口）。
     *
     * 在全双工模式下，监听是自动进行的（由 VAD 控制），
     * 此方法保留仅为兼容性。
     *
     * @param onResult 识别结果回调（实时 partial 文本）
     */
    fun startListening(onResult: (String) -> Unit) {
        DebugLog.w(TAG, "startListening() 在全双工模式下不需要手动调用（VAD 自动管理）")

        // 如果全双工引擎未启动，回退到旧模式
        if (duplexEngine.state == FullDuplexAudioEngine.DuplexState.IDLE) {
            startLegacyListening(onResult)
        }
    }

    /**
     * 旧版监听模式（降级方案）。
     */
    private fun startLegacyListening(onResult: (String) -> Unit) {
        val manager = asrManager ?: run {
            DebugLog.e(TAG, "ASR 管理器未初始化")
            return
        }

        DebugLog.i(TAG, "开始旧版 ASR 监听模式")

        asrJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val flow: Flow<StreamingRecognitionState> = manager.startStreaming()
                flow
                    .catch { e ->
                        DebugLog.e(TAG, "ASR 流异常: ${e.message}", e)
                    }
                    .collect { state ->
                        when (state) {
                            is StreamingRecognitionState.Recognizing -> {
                                onResult(state.partialText)
                            }
                            is StreamingRecognitionState.FinalResult -> {
                                onResult(state.text)
                                DebugLog.i(TAG, "ASR 最终结果: '${state.text.take(100)}'")
                                stopListening()
                            }
                            is StreamingRecognitionState.Error -> {
                                DebugLog.e(TAG, "ASR 错误: ${state.message}")
                                stopListening()
                            }
                            is StreamingRecognitionState.Stopped -> {
                                DebugLog.i(TAG, "ASR 已停止")
                                stopListening()
                            }
                            is StreamingRecognitionState.Listening -> {
                                DebugLog.d(TAG, "ASR 进入监听状态")
                            }
                        }
                    }
            } catch (e: CancellationException) {
                DebugLog.i(TAG, "ASR 监听被取消")
            } catch (e: Exception) {
                DebugLog.e(TAG, "ASR 启动失败: ${e.message}", e)
            }
        }
    }

    /**
     * 停止监听用户说话（兼容旧接口）。
     */
    fun stopListening() {
        DebugLog.d(TAG, "停止 ASR 监听")

        asrJob?.cancel()
        asrJob = null

        asrManager?.stopStreaming()
    }

    /**
     * 让 AI 说话（兼容旧接口）。
     *
     * 在全双工模式下优先使用 [duplexEngine.aiSpeakText]，
     * 此方法保留仅为兼容性。
     *
     * @param text 要合成的文本
     * @param voiceId 音色 ID，默认"白桦"
     * @param scenario TTS 场景，控制语音风格（默认 INTERVIEW）
     */
    suspend fun aiSpeak(
        text: String,
        voiceId: String = DEFAULT_INTERVIEWER_VOICE,
        scenario: TtsScenario = DEFAULT_TTS_SCENARIO,
    ) {
        if (text.isBlank()) return

        // 如果全双工引擎可用，使用它
        if (duplexEngine.state != FullDuplexAudioEngine.DuplexState.IDLE) {
            duplexEngine.aiSpeakText(
                text = text,
                ttsPlayer = ttsPlayer,
                scenario = scenario,
                voiceId = voiceId,
            )
            return
        }

        // 否则回退到旧模式
        aiSpeakLegacy(text, voiceId, scenario)
    }

    /**
     * 旧版 TTS 播放（降级方案）。
     */
    private suspend fun aiSpeakLegacy(
        text: String,
        voiceId: String,
        scenario: TtsScenario,
    ) {
        ttsJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                DebugLog.i(TAG, "AI 说话 [${scenario.label}]: '${text.take(50)}...' (voice=$voiceId)")

                val style = StyleControl(
                    isDirectorMode = true,
                    directorCharacter = scenario.directorCharacter,
                    directorScene = scenario.directorScene,
                    directorGuidance = buildDirectorGuidance(scenario),
                )

                ttsPlayer.speak(
                    text = text,
                    localeTag = "zh-CN",
                    rate = scenario.defaultRate,
                    pitch = scenario.defaultPitch,
                    mimoVoice = voiceId,
                )

                DebugLog.d(TAG, "AI 说话完成")
            } catch (e: Exception) {
                DebugLog.e(TAG, "TTS 播放失败: ${e.message}", e)
            }
        }

        ttsJob?.join()
    }

    /**
     * 停止当前 TTS 播放。
     */
    private fun stopCurrentTts() {
        DebugLog.d(TAG, "停止当前 TTS 播放")

        ttsJob?.cancel()
        ttsJob = null
        ttsPlayer.stop()

        // 同时停止全双工引擎的播放
        duplexEngine.stopAiSpeaking()
    }

    /**
     * 根据场景生成导演模式引导语。
     *
     * 每个场景有不同的语气要求、语速建议和情感表达指导，
     * 让 TTS 引擎合成出贴合场景氛围的语音。
     */
    private fun buildDirectorGuidance(scenario: TtsScenario): String = when (scenario) {
        TtsScenario.INTERVIEW -> """
            语气要求：
            - 专业、沉稳、有权威感但不失礼貌
            - 语速适中偏慢，给候选人思考时间
            - 停顿自然，重要问题后稍作停顿
            - 不带个人情绪色彩，保持客观中立
            - 像真实的资深HR或技术负责人一样说话
        """.trimIndent()

        TtsScenario.DEBATE -> """
            语气要求：
            - 锋利、有力、富有感染力
            - 语速稍快，体现思辨的敏捷性
            - 关键论点处加强重音和停顿
            - 保持逻辑性和说服力
            - 像专业的辩手一样有理有据地表达观点
        """.trimIndent()

        TtsScenario.PRESENTATION -> """
            语气要求：
            - 自信、清晰、富有感召力
            - 语速适中，重点内容放慢
            - 适当使用停顿制造戏剧效果
            - 情感饱满，能调动听众情绪
            - 像优秀的演讲者一样引人入胜
        """.trimIndent()

        TtsScenario.CASUAL -> """
            语气要求：
            - 轻松、自然、像朋友聊天
            - 语速正常，带有自然的节奏变化
            - 可以适当加入语气词和笑声
            - 温暖亲切，让人感到放松
            - 像在咖啡馆和朋友闲聊一样自然
        """.trimIndent()

        TtsScenario.STORYTELLING -> """
            语气要求：
            - 富有感情、娓娓道来
            - 语速较慢，营造沉浸感
            - 根据情节调整语调和节奏
            - 高潮处加强，平静处柔和
            - 像经验丰富的说书人一样引人入胜
        """.trimIndent()

        TtsScenario.PRESSURE_TEST -> """
            语气要求：
            - 严肃、紧迫、带有压迫感
            - 语速较快，不给对方太多思考时间
            - 质问句式多，反问和追问频繁
            - 冷静但充满挑战性
            - 像压力面试官一样不断施压测试极限
        """.trimIndent()
    }

    /**
     * 释放资源。
     */
    fun release() {
        stopFullDuplex()
        asrManager?.close()
        asrManager = null
    }
}
