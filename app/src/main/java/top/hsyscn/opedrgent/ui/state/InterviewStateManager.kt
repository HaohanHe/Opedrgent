package top.hsyscn.opedrgent.ui.state

import android.app.Application
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.interview.AnalysisResult
import top.hsyscn.opedrgent.interview.CoachFeedback
import top.hsyscn.opedrgent.interview.DialogueTurn
import top.hsyscn.opedrgent.interview.FullDuplexAudioEngine
import top.hsyscn.opedrgent.interview.InterviewAgent
import top.hsyscn.opedrgent.interview.InterviewConfig
import top.hsyscn.opedrgent.interview.InterviewPhase
import top.hsyscn.opedrgent.interview.InterviewReport
import top.hsyscn.opedrgent.interview.NextAction
import top.hsyscn.opedrgent.interview.VoiceConversationEngine
import top.hsyscn.opedrgent.network.LlmClient
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.note.NoteRepository
import top.hsyscn.opedrgent.note.NoteType
import top.hsyscn.opedrgent.settings.ApiConfig
import top.hsyscn.opedrgent.settings.ApiSettings
import top.hsyscn.opedrgent.tts.TtsPlayer
import top.hsyscn.opedrgent.utils.DebugLog

/**
 * 面试状态管理器。
 *
 * 将面试相关的状态、语音引擎与业务逻辑从 [top.hsyscn.opedrgent.ui.MainViewModel]
 * 中抽离，降低主 ViewModel 的复杂度，同时保持 UI 层调用接口不变。
 */
class InterviewStateManager(
    private val app: Application,
    private val apiSettings: ApiSettings,
    private val llm: LlmClient,
    private val tts: TtsPlayer,
    private val noteRepository: NoteRepository,
    private val scope: CoroutineScope,
) {

    /** 面试 UI 状态数据类 */
    data class InterviewUiState(
        val phase: InterviewPhase = InterviewPhase.SETUP,
        val config: InterviewConfig? = null,
        val messages: List<DialogueTurn> = emptyList(),
        val currentQuestionIndex: Int = 0,
        val questionCount: Int = 0,
        val elapsedSeconds: Int = 0,
        val isListening: Boolean = false,
        val isSpeaking: Boolean = false,
        val report: InterviewReport? = null,
        val coachFeedback: CoachFeedback? = null,
        val analysisResult: AnalysisResult? = null,
        val error: String? = null,
        // 全双工通话状态
        val duplexState: FullDuplexAudioEngine.DuplexState? = null,
        val isMuted: Boolean = false,
        val bargeInDetected: Boolean = false,
        // 编排层的克制状态提示（断网/管线异常/长静音引导等，非对话气泡）
        val statusHint: String? = null,
    )

    private val _interviewState = MutableStateFlow(InterviewUiState())

    /** 面试状态暴露给 UI 层 */
    val interviewState: StateFlow<InterviewUiState> = _interviewState.asStateFlow()

    /** 面试开始时间戳 */
    private var interviewStartTime: Long = 0L

    /** 面试对话历史 */
    private val interviewTranscript: MutableList<DialogueTurn> = Collections.synchronizedList(mutableListOf())

    /** 当前问题索引 */
    private val currentQuestionIdx = AtomicInteger(0)

    /** 语音对话引擎 */
    private var voiceEngine: VoiceConversationEngine? = null

    /**
     * 开始面试 — 从设置页调用。
     *
     * 流程：
     * 1. 保存配置，切换到 PREPARING 阶段
     * 2. 分析用户提交的材料（如果有）
     * 3. 切换到 IN_PROGRESS 阶段
     * 4. 调用 LLM 生成开场白 + 第一题
     */
    fun startInterview(config: InterviewConfig) {
        scope.launch {
            // 重入保护：先彻底停掉上一轮可能仍在运行的全双工引擎（含 native ASR），
            // 避免旧采集/播放线程泄漏、旧回调继续向已 clear 的 transcript 追加 turn 污染新会话（U29-1）
            voiceEngine?.release()
            voiceEngine = null

            val apiConfig = apiSettings.getApiConfig() ?: return@launch
            try {
                // 重置状态
                interviewTranscript.clear()
                currentQuestionIdx.set(0)
                interviewStartTime = System.currentTimeMillis()

                // 更新状态：进入准备阶段
                _interviewState.value = InterviewUiState(
                    phase = InterviewPhase.PREPARING,
                    config = config,
                )

                // 如果有材料，先分析
                if (config.materials.isNotEmpty()) {
                    val analysisResult = withContext(Dispatchers.IO) {
                        InterviewAgent.analyzeMaterials(
                            llmClient = llm,
                            config = apiConfig,
                            materials = config.getMaterialsText(),
                            interviewType = config.type,
                        )
                    }
                    _interviewState.update {
                        it.copy(
                            analysisResult = analysisResult,
                        )
                    }
                }

                // 短暂展示分析结果后进入面试
                delay(1500)

                // 更新状态：进入进行中阶段
                _interviewState.value = InterviewUiState(
                    phase = InterviewPhase.IN_PROGRESS,
                    config = config,
                    messages = interviewTranscript.toList(),
                    questionCount = 0,
                    elapsedSeconds = 0,
                )

                // 启动计时器
                launchInterviewTimer()

                // 启动全双工语音引擎
                val engine = VoiceConversationEngine(app, tts, apiSettings)
                voiceEngine = engine

                engine.startFullDuplex(
                    onAiSpeak = { text ->
                        val turn = DialogueTurn(role = "interviewer", content = text, questionCategory = app.getString(R.string.interview_category_followup))
                        interviewTranscript.add(turn)
                        currentQuestionIdx.incrementAndGet()
                        _interviewState.update {
                            it.copy(
                                messages = interviewTranscript.toList(),
                                questionCount = currentQuestionIdx.get(),
                                isSpeaking = true,
                            )
                        }
                    },
                    onUserSpeak = { text ->
                        val turn = DialogueTurn(role = "candidate", content = text)
                        interviewTranscript.add(turn)
                        _interviewState.update {
                            it.copy(
                                messages = interviewTranscript.toList(),
                                isListening = false,
                            )
                        }
                    },
                    onPartialUserText = { partial ->
                        _interviewState.update { it.copy(isListening = true) }
                    },
                    onStateChange = { duplexState ->
                        _interviewState.update {
                            it.copy(
                                duplexState = duplexState,
                                isSpeaking = duplexState == FullDuplexAudioEngine.DuplexState.AI_SPEAKING,
                                isListening = duplexState == FullDuplexAudioEngine.DuplexState.LISTENING,
                            )
                        }
                    },
                    onBargeIn = {
                        _interviewState.update { it.copy(isSpeaking = false) }
                    },
                    onStatusHint = { hint ->
                        // 克制的状态提示（断网/管线重启/失败恢复等），非对话气泡
                        _interviewState.update { it.copy(statusHint = hint) }
                    },
                    onIdleNudge = {
                        // 长静音：由模型生成一句温和引导，不机械硬塞固定话术
                        withContext(Dispatchers.IO) {
                            InterviewAgent.generateIdleNudge(
                                llmClient = llm, apiConfig = apiConfig, config = config,
                            )
                        }
                    },
                    getAiResponse = { userInput ->
                        withContext(Dispatchers.IO) {
                            if (userInput == null) {
                                // 开场白：生成第一个问题
                                val firstQuestion = InterviewAgent.generateFirstQuestion(
                                    llmClient = llm, apiConfig = apiConfig, config = config,
                                )
                                firstQuestion
                            } else {
                                // 处理用户回答，获取下一个问题
                                val lastQuestion = interviewTranscript.lastOrNull { it.role == "interviewer" }
                                val nextAction = InterviewAgent.processAnswer(
                                    llmClient = llm, apiConfig = apiConfig, config = config,
                                    answer = userInput,
                                    currentQuestion = lastQuestion ?: DialogueTurn(role = "interviewer", content = ""),
                                    history = interviewTranscript.toList(),
                                    currentQuestionIndex = currentQuestionIdx.get(),
                                )
                                when (nextAction) {
                                    is NextAction.FollowUp -> nextAction.question
                                    is NextAction.NextQuestion -> nextAction.question
                                    is NextAction.EndInterview -> {
                                        scope.launch { endInterview() }
                                        nextAction.reason
                                    }
                                }
                            }
                        }
                    },
                    interviewConfig = config,
                )

            } catch (e: Exception) {
                DebugLog.e("Interview", "启动面试失败: ${e.message}", e)
                _interviewState.update {
                    it.copy(
                        error = app.getString(R.string.error_interview_start_failed, e.message ?: app.getString(R.string.error_unknown_error)),
                    )
                }
            }
        }
    }

    /**
     * 发送候选人回答 — 文字输入模式。
     */
    fun sendInterviewAnswer(answer: String) {
        scope.launch {
            val currentState = _interviewState.value
            if (currentState.phase != InterviewPhase.IN_PROGRESS || currentState.config == null) return@launch
            val apiConfig = apiSettings.getApiConfig() ?: return@launch

            try {
                // 记录候选人回答
                val answerTurn = DialogueTurn(
                    role = "candidate",
                    content = answer,
                )
                interviewTranscript.add(answerTurn)
                currentQuestionIdx.incrementAndGet()

                // 更新状态为思考中。用 update{it.copy} 原子合并，只改本方法负责的字段，
                // 不读取旧快照整态赋值，避免回滚引擎回调并发更新的 duplexState/isListening/isSpeaking（U29-3）
                _interviewState.update {
                    it.copy(
                        messages = interviewTranscript.toList(),
                        questionCount = currentQuestionIdx.get(),
                        phase = InterviewPhase.EVALUATING, // 复用 EVALUATING 表示 AI 思考中
                    )
                }

                // 获取当前问题（最后一个面试官问题）
                val lastInterviewerMessage = interviewTranscript.lastOrNull { it.role == "interviewer" }
                    ?: return@launch

                // 调用 LLM 处理回答
                val nextAction = withContext(Dispatchers.IO) {
                    InterviewAgent.processAnswer(
                        llmClient = llm,
                        apiConfig = apiConfig,
                        config = currentState.config,
                        answer = answer,
                        currentQuestion = lastInterviewerMessage,
                        history = interviewTranscript.toList(),
                        currentQuestionIndex = currentQuestionIdx.get() - 1,
                    )
                }

                // 处理下一步动作
                when (nextAction) {
                    is NextAction.FollowUp -> {
                        val followUpTurn = DialogueTurn(
                            role = "interviewer",
                            content = nextAction.question,
                            questionCategory = app.getString(R.string.interview_category_followup),
                            followUpDepth = lastInterviewerMessage.followUpDepth + 1,
                        )
                        interviewTranscript.add(followUpTurn)
                    }
                    is NextAction.NextQuestion -> {
                        val nextQTurn = DialogueTurn(
                            role = "interviewer",
                            content = nextAction.question,
                            questionCategory = nextAction.category,
                            followUpDepth = 0,
                        )
                        interviewTranscript.add(nextQTurn)
                    }
                    is NextAction.EndInterview -> {
                        // 结束面试，生成报告
                        val endTurn = DialogueTurn(
                            role = "interviewer",
                            content = nextAction.reason,
                            questionCategory = app.getString(R.string.interview_category_end),
                        )
                        interviewTranscript.add(endTurn)

                        generateFinalReport(currentState.config, apiConfig)
                        return@launch
                    }
                }

                // 可选：生成教练反馈（如果启用）
                if (currentState.config.enableCoach || currentState.config.enableRealtimeFeedback) {
                    val coachFb = withContext(Dispatchers.IO) {
                        InterviewAgent.generateCoachFeedback(
                            llmClient = llm,
                            apiConfig = apiConfig,
                            question = lastInterviewerMessage,
                            answer = answerTurn,
                        )
                    }
                    _interviewState.update { it.copy(coachFeedback = coachFb) }
                }

                // 恢复正常状态
                _interviewState.update {
                    it.copy(
                        phase = InterviewPhase.IN_PROGRESS,
                        config = currentState.config,
                        messages = interviewTranscript.toList(),
                        questionCount = interviewTranscript.count { it.role == "interviewer" },
                        elapsedSeconds = ((System.currentTimeMillis() - interviewStartTime) / 1000).toInt(),
                    )
                }

            } catch (e: Exception) {
                DebugLog.e("Interview", "处理回答失败: ${e.message}", e)
                _interviewState.update {
                    it.copy(
                        phase = InterviewPhase.IN_PROGRESS,
                        error = app.getString(R.string.error_interview_processing_failed, e.message ?: app.getString(R.string.error_unknown_error)),
                    )
                }
            }
        }
    }

    /**
     * 结束面试并生成报告。
     */
    fun endInterview() {
        scope.launch {
            val currentState = _interviewState.value
            if (currentState.config == null) return@launch
            val apiConfig = apiSettings.getApiConfig() ?: return@launch

            generateFinalReport(currentState.config, apiConfig)
        }
    }

    /**
     * 生成最终评估报告。
     */
    private suspend fun generateFinalReport(config: InterviewConfig, apiConfig: ApiConfig) {
        _interviewState.update { it.copy(phase = InterviewPhase.EVALUATING) }

        try {
            val report = withContext(Dispatchers.IO) {
                InterviewAgent.generateReport(
                    llmClient = llm,
                    apiConfig = apiConfig,
                    config = config,
                    fullTranscript = interviewTranscript.toList(),
                )
            }

            _interviewState.value = InterviewUiState(
                phase = InterviewPhase.COMPLETED,
                config = config,
                messages = interviewTranscript.toList(),
                questionCount = interviewTranscript.count { it.role == "interviewer" },
                elapsedSeconds = ((System.currentTimeMillis() - interviewStartTime) / 1000).toInt(),
                report = report,
            )
        } catch (e: Exception) {
            DebugLog.e("Interview", "生成报告失败: ${e.message}", e)
            _interviewState.update {
                it.copy(
                    phase = InterviewPhase.COMPLETED,
                    error = app.getString(R.string.error_interview_report_failed, e.message ?: app.getString(R.string.error_unknown_error)),
                )
            }
        } finally {
            voiceEngine?.stopFullDuplex()
        }
    }

    /**
     * 重置面试状态。
     */
    fun resetInterview() {
        voiceEngine?.stopFullDuplex()
        voiceEngine = null
        interviewTranscript.clear()
        currentQuestionIdx.set(0)
        interviewStartTime = 0L
        _interviewState.value = InterviewUiState()
    }

    /**
     * 开始语音监听（ASR）。
     */
    fun startInterviewListening() {
        // 延迟初始化语音引擎
        if (voiceEngine == null) {
            voiceEngine = VoiceConversationEngine(app, tts, apiSettings)
        }

        _interviewState.update { it.copy(isListening = true) }

        voiceEngine?.startListening { partialText ->
            // 可以在这里实时显示识别结果（可选）
        }
    }

    /**
     * 停止语音监听。
     */
    fun stopInterviewListening() {
        voiceEngine?.stopListening()
        _interviewState.update { it.copy(isListening = false) }
    }

    /**
     * 停止 TTS 播放。
     */
    fun stopInterviewSpeaking() {
        tts.stop()
        _interviewState.update { it.copy(isSpeaking = false) }
    }

    /**
     * 重试通话连接（供通话界面在 PIPELINE_FAILED / 重连失败后调用）。
     *
     * 仅当会话已开始、且管线处于失败/暂停态（引擎回落到 CONNECTED）时生效；
     * 正常进行中（LISTENING/AI_SPEAKING）或忙时忽略。复用引擎既有 start 能力重新拉起采集/播放，
     * 通过 statusHint 反馈「正在重新连接…」，成功后清空，失败再给克制提示。不做关键词判定。
     */
    fun retryInterviewConnection() {
        val currentState = _interviewState.value
        if (currentState.phase != InterviewPhase.IN_PROGRESS) return
        val engine = voiceEngine ?: return

        val duplex = engine.getCurrentDuplexState()
        // 正常进行中不重试
        if (duplex == FullDuplexAudioEngine.DuplexState.LISTENING ||
            duplex == FullDuplexAudioEngine.DuplexState.AI_SPEAKING ||
            duplex == FullDuplexAudioEngine.DuplexState.MUTED
        ) {
            return
        }

        scope.launch {
            _interviewState.update { it.copy(statusHint = "正在重新连接…") }
            val ok = engine.retryPipeline()
            _interviewState.update {
                if (ok) {
                    // 成功：清掉失败提示；后续 LISTENING 由引擎 onStateChanged 驱动
                    it.copy(statusHint = null)
                } else {
                    it.copy(statusHint = "重新连接未成功，请检查网络后再试")
                }
            }
        }
    }

    /**
     * 切换面试模式静音状态（全双工通话控制）。
     *
     * 调用 FullDuplexAudioEngine.muteUser() / unmuteUser()
     * 同时更新 UI 状态中的 isMuted 字段
     */
    fun toggleInterviewMute() {
        val currentState = _interviewState.value
        val newMuted = !currentState.isMuted

        // 通过语音引擎切换静音
        voiceEngine?.let { engine ->
            // VoiceConversationEngine 内部应封装对 FullDuplexAudioEngine 的静音调用
            runCatching {
                if (newMuted) {
                    engine.muteUser()
                } else {
                    engine.unmuteUser()
                }
            }
        }

        // 更新 UI 状态
        _interviewState.value = currentState.copy(
            isMuted = newMuted,
            duplexState = if (newMuted) FullDuplexAudioEngine.DuplexState.MUTED else currentState.duplexState,
        )
    }

    /**
     * 更新全双工通话状态（由语音引擎回调触发）。
     */
    fun updateDuplexState(state: FullDuplexAudioEngine.DuplexState) {
        _interviewState.update { it.copy(duplexState = state) }
    }

    /**
     * 标记插话事件（BargeIn）发生/消失。
     */
    fun setBargeInDetected(detected: Boolean) {
        _interviewState.update { it.copy(bargeInDetected = detected) }
    }

    /**
     * 让面试官说话（TTS）。
     */
    suspend fun speakAsInterviewer(text: String) {
        _interviewState.update { it.copy(isSpeaking = true) }
        voiceEngine?.aiSpeak(text)
        _interviewState.update { it.copy(isSpeaking = false) }
    }

    /**
     * 保存面试报告到笔记。
     */
    fun saveInterviewReportToNote() {
        scope.launch {
            val report = _interviewState.value.report ?: return@launch

            try {
                val noteContent = buildString {
                    appendLine("# ${app.getString(R.string.interview_report_title)}")
                    appendLine()
                    appendLine("**${app.getString(R.string.interview_report_type_label)}**: ${report.type.label}")
                    appendLine("**${app.getString(R.string.interview_report_total_score_label)}**: ${report.overallScore} ${app.getString(R.string.interview_report_total_score_unit)} (${report.verdict.label})")
                    appendLine("**${app.getString(R.string.interview_report_duration_label)}**: ${report.durationSeconds} ${app.getString(R.string.interview_report_duration_seconds)}")
                    appendLine("**${app.getString(R.string.interview_report_question_count_label)}**: ${report.questionCount}")
                    appendLine()
                    appendLine("## ${app.getString(R.string.interview_report_summary_title)}")
                    appendLine(report.summary)
                    appendLine()
                    if (report.strengths.isNotEmpty()) {
                        appendLine("## [${app.getString(R.string.interview_report_strengths_title)}]")
                        report.strengths.forEach { appendLine("- $it") }
                        appendLine()
                    }
                    if (report.weaknesses.isNotEmpty()) {
                        appendLine("## [${app.getString(R.string.interview_report_weaknesses_title)}]")
                        report.weaknesses.forEach { appendLine("- $it") }
                        appendLine()
                    }
                    if (report.recommendations.isNotEmpty()) {
                        appendLine("## [${app.getString(R.string.interview_report_recommendations_title)}]")
                        report.recommendations.forEach { appendLine("- $it") }
                        appendLine()
                    }
                    if (report.dimensions.isNotEmpty()) {
                        appendLine("## [${app.getString(R.string.interview_report_dimensions_title)}]")
                        report.dimensions.forEach { dim ->
                            appendLine(
                                app.getString(
                                    R.string.interview_report_dimension_item,
                                    dim.name,
                                    dim.score.toInt(),
                                    dim.maxScore.toInt(),
                                    dim.feedback,
                                )
                            )
                        }
                    }
                }

                noteRepository.quickCreate(
                    content = noteContent,
                    type = NoteType.TEXT,
                )

                DebugLog.i("Interview", "面试报告已保存到笔记")
            } catch (e: Exception) {
                DebugLog.e("Interview", "保存报告失败: ${e.message}", e)
            }
        }
    }

    /**
     * 启动面试计时器（每秒更新一次）。
     */
    private fun launchInterviewTimer() {
        scope.launch {
            // 常驻计时协程：不随 phase 离开 IN_PROGRESS 而结束。
            // - IN_PROGRESS / EVALUATING：每秒按面试开始时间戳累加 elapsedSeconds（墙钟连续，多轮文字问答不冻结）。
            //   文字回答 sendInterviewAnswer 会先置 EVALUATING、评估完再恢复 IN_PROGRESS；
            //   旧实现的 while(phase==IN_PROGRESS) 会在 EVALUATING 时退出协程，恢复后无人重启 -> 计时冻结。
            //   现改为挂起等待下一秒轮询，phase 离开 IN_PROGRESS 时不退出协程。
            // - 终态 COMPLETED（面试结束）或 SETUP（已重置）：退出协程正确停止、不泄漏。
            while (true) {
                delay(1000L)
                when (_interviewState.value.phase) {
                    InterviewPhase.COMPLETED,
                    InterviewPhase.SETUP -> break
                    else -> {
                        val elapsed = ((System.currentTimeMillis() - interviewStartTime) / 1000).toInt()
                        _interviewState.update { it.copy(elapsedSeconds = elapsed) }
                    }
                }
            }
        }
    }
}
