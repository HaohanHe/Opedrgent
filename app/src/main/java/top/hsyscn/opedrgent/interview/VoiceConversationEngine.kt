package top.hsyscn.opedrgent.interview

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.settings.ApiSettings
import top.hsyscn.opedrgent.stt.AsrManager
import top.hsyscn.opedrgent.stt.StreamingRecognitionState
import top.hsyscn.opedrgent.tts.MimoTtsClient
import top.hsyscn.opedrgent.tts.MimoTtsClient.StyleControl
import top.hsyscn.opedrgent.tts.TtsPlayer
import top.hsyscn.opedrgent.utils.DebugLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 语音对话状态枚举
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
 * 语音对话引擎 — 串联 STT → LLM → TTS 的完整管线。
 *
 * 核心职责：
 * 1. 管理 TTS 播放（面试官说话）
 * 2. 管理 ASR 监听（收集候选人回答）
 * 3. 协调对话状态流转
 * 4. 提供回调接口供 UI 层更新
 *
 * 使用方式：
 * ```kotlin
 * val engine = VoiceConversationEngine(context, ttsPlayer, apiSettings)
 * engine.startConversationLoop(
 *     onAiSpeak = { text -> ui.updateAiMessage(text) },
 *     onUserSpeak = { text -> ui.updateUserMessage(text) },
 *     onStateChange = { state -> ui.updateState(state) },
 *     getAiResponse = { userInput -> llm.generateResponse(userInput) }
 * )
 * ```
 */
class VoiceConversationEngine(
    private val context: Context,
    private val ttsPlayer: TtsPlayer,
    private val apiSettings: ApiSettings,
) {

    companion object {
        private const val TAG = "VoiceConversationEngine"

        /** 面试官默认声音：白桦（成熟男声） */
        const val DEFAULT_INTERVIEWER_VOICE = "白桦"
    }

    // 状态标志
    private val isListening = AtomicBoolean(false)
    private val conversationActive = AtomicBoolean(false)

    // ASR 管理器
    private var asrManager: AsrManager? = null

    // 当前 ASR 流式识别 Job
    private var asrJob: Job? = null

    // 当前 TTS 播放 Job
    private var ttsJob: Job? = null

    /**
     * 开始一轮完整的对话循环。
     *
     * 工作流程：
     * 1. AI 说话（TTS）→ 回调通知 UI
     * 2. 开始监听用户回答（ASR）
     * 3. 收集到用户输入后，调用 LLM 生成回复
     * 4. 循环步骤 1-3，直到 [stopConversation] 被调用
     *
     * @param onAiSpeak AI 说话时的回调（用于UI更新文字）
     * @param onUserSpeak 用户说话完成后的回调
     * @param onStateChange 对话状态变化回调
     * @param getAiResponse LLM 调用接口，传入用户输入返回 AI 回复
     */
    suspend fun startConversationLoop(
        onAiSpeak: (String) -> Unit,
        onUserSpeak: (String) -> Unit,
        onStateChange: (ConversationState) -> Unit,
        getAiResponse: suspend (userInput: String?) -> String,
    ) {
        if (!conversationActive.compareAndSet(false, true)) {
            DebugLog.w(TAG, "对话已在进行中")
            return
        }

        DebugLog.i(TAG, "开始语音对话循环")

        try {
            // 初始化 ASR 管理器
            asrManager = AsrManager(context, apiSettings)
            asrManager?.ensureInitialized()

            while (conversationActive.get()) {
                // 步骤1：获取 AI 回复并播放
                val aiResponse = withContext(Dispatchers.IO) {
                    getAiResponse(null)
                }

                if (!conversationActive.get()) break

                // AI 说话
                onStateChange(ConversationState.AI_SPEAKING)
                onAiSpeak(aiResponse)

                aiSpeak(aiResponse)

                if (!conversationActive.get()) break

                // 步骤2：开始监听用户回答
                onStateChange(ConversationState.LISTENING)

                val userText = collectUserInput(onUserSpeak, onStateChange)

                if (!conversationActive.get()) break

                if (userText.isBlank()) {
                    DebugLog.d(TAG, "用户输入为空，继续等待...")
                    continue
                }

                // 步骤3：处理用户输入
                onUserSpeak(userText)
                onStateChange(ConversationState.PROCESSING)
            }
        } catch (e: CancellationException) {
            DebugLog.i(TAG, "对话循环被取消")
        } catch (e: Exception) {
            DebugLog.e(TAG, "对话循环异常: ${e.message}", e)
            onStateChange(ConversationState.ERROR)
        } finally {
            stopListening()
            conversationActive.set(false)
            onStateChange(ConversationState.IDLE)
            DebugLog.i(TAG, "语音对话循环结束")
        }
    }

    /**
     * 让 AI 说话（使用 TTS 合成并播放）。
     *
     * 使用导演模式控制语气为专业面试官风格，
     * 默认使用"白桦"音色（成熟男声）。
     *
     * @param text 要合成的文本
     * @param voiceId 音色ID，默认"白桦"
     */
    suspend fun aiSpeak(
        text: String,
        voiceId: String = DEFAULT_INTERVIEWER_VOICE,
    ) {
        if (text.isBlank()) return

        ttsJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                DebugLog.i(TAG, "AI 说话: '${text.take(50)}...' (voice=$voiceId)")

                // 使用导演模式：严肃但不失礼貌的专业面试官语调
                val style = StyleControl(
                    isDirectorMode = true,
                    directorCharacter = "资深面试官",
                    directorScene = "正式面试场景",
                    directorGuidance = """
                        语气要求：
                        - 专业、沉稳、有权威感但不失礼貌
                        - 语速适中偏慢（0.9倍速），给候选人思考时间
                        - 停顿自然，重要问题后稍作停顿
                        - 不带个人情绪色彩，保持客观中立
                        - 像真实的资深HR或技术负责人一样说话
                    """.trimIndent(),
                )

                // 调用 TTSPlayer 播放（内部会处理 MiMo/Android TTS 选择）
                ttsPlayer.speak(
                    text = text,
                    localeTag = "zh-CN",
                    rate = 0.9f,  // 稍慢的语速，符合面试官形象
                    pitch = 1.0f,
                    mimoVoice = voiceId,
                )

                DebugLog.d(TAG, "AI 说话完成")
            } catch (e: Exception) {
                DebugLog.e(TAG, "TTS 播放失败: ${e.message}", e)
            }
        }

        // 等待播放完成
        ttsJob?.join()
    }

    /**
     * 开始监听用户说话（启动 ASR 流式识别）。
     *
     * 调用后会持续采集音频直到 [stopListening] 被调用，
     * 或检测到用户停止说话（静音超时）。
     *
     * @param onResult 识别结果回调（实时 partial 文本）
     */
    fun startListening(onResult: (String) -> Unit) {
        if (!isListening.compareAndSet(false, true)) {
            DebugLog.w(TAG, "已在监听中")
            return
        }

        DebugLog.i(TAG, "开始监听用户说话")

        val manager = asrManager ?: run {
            DebugLog.e(TAG, "ASR 管理器未初始化")
            isListening.set(false)
            return
        }

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
                                // 实时识别结果（partial）
                                onResult(state.text)
                            }
                            is StreamingRecognitionState.FinalResult -> {
                                // 最终识别结果
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
                                // 开始监听，无需特殊处理
                                DebugLog.d(TAG, "ASR 进入监听状态")
                            }
                        }
                    }
            } catch (e: CancellationException) {
                DebugLog.i(TAG, "ASR 监听被取消")
            } catch (e: Exception) {
                DebugLog.e(TAG, "ASR 启动失败: ${e.message}", e)
                isListening.set(false)
            }
        }
    }

    /**
     * 停止监听用户说话。
     *
     * 会触发 ASR 引擎返回最终识别结果。
     */
    fun stopListening() {
        if (isListening.compareAndSet(true, false)) {
            DebugLog.i(TAG, "停止监听")

            asrJob?.cancel()
            asrJob = null

            asrManager?.stopStreaming()
        }
    }

    /**
     * 停止整个对话循环。
     *
     * 会中断当前正在进行的 TTS 播放和 ASR 监听。
     */
    fun stopConversation() {
        DebugLog.i(TAG, "停止对话")

        conversationActive.set(false)
        stopListening()

        // 停止 TTS 播放
        ttsJob?.cancel()
        ttsJob?.let { job ->
            CoroutineScope(Dispatchers.Main).launch {
                job.join()
            }
        }
        ttsJob = null

        ttsPlayer.stop()
    }

    /**
     * 检查是否正在监听
     */
    fun isCurrentlyListening(): Boolean = isListening.get()

    /**
     * 检查对话是否活跃
     */
    fun isConversationActive(): Boolean = conversationActive.get()

    /**
     * 收集用户输入（阻塞等待 ASR 结果）。
     *
     * @return 用户输入文本，如果被中断则返回空字符串
     */
    private suspend fun collectUserInput(
        onPartialResult: (String) -> Unit,
        onStateChange: (ConversationState) -> Unit,
    ): String {
        var finalText = ""

        // 使用回调方式收集结果
        startListening { text ->
            if (text.isNotBlank()) {
                onPartialResult(text)
                finalText = text
            }
        }

        // 等待监听结束
        while (isListening.get() && conversationActive.get()) {
            delay(100L)
        }

        return finalText.trim()
    }

    /**
     * 释放资源
     */
    fun release() {
        stopConversation()
        asrManager?.close()
        asrManager = null
    }
}
