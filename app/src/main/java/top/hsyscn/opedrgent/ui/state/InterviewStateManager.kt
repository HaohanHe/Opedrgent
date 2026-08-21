package top.hsyscn.opedrgent.ui.state

import android.app.Application
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    )

    private val _interviewState = MutableStateFlow(InterviewUiState())

    /** 面试状态暴露给 UI 层 */
    val interviewState: StateFlow<InterviewUiState> = _interviewState.asStateFlow()

    /** 面试开始时间戳 */
    private var interviewStartTime: Long = 0L

    /** 面试对话历史 */
    private val interviewTranscript: MutableList<DialogueTurn> = Collections.synchronizedList(mutableListOf())

    /** 当前问题索引 */
    private var currentQuestionIdx = 0

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
            val apiConfig = apiSettings.getApiConfig() ?: return@launch
            try {
                // 重置状态
                interviewTranscript.clear()
                currentQuestionIdx = 0
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
                    _interviewState.value = _interviewState.value.copy(
                        analysisResult = analysisResult,
                    )
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
                        currentQuestionIdx++
                        _interviewState.value = _interviewState.value.copy(
                            messages = interviewTranscript.toList(),
                            questionCount = currentQuestionIdx,
                            isSpeaking = true,
                        )
                    },
                    onUserSpeak = { text ->
                        val turn = DialogueTurn(role = "candidate", content = text)
                        interviewTranscript.add(turn)
                        _interviewState.value = _interviewState.value.copy(
                            messages = interviewTranscript.toList(),
                            isListening = false,
                        )
                    },
                    onPartialUserText = { partial ->
                        _interviewState.value = _interviewState.value.copy(isListening = true)
                    },
                    onStateChange = { duplexState ->
                        _interviewState.value = _interviewState.value.copy(
                            duplexState = duplexState,
                            isSpeaking = duplexState == FullDuplexAudioEngine.DuplexState.AI_SPEAKING,
                            isListening = duplexState == FullDuplexAudioEngine.DuplexState.LISTENING,
                        )
                    },
                    onBargeIn = {
                        _interviewState.value = _interviewState.value.copy(isSpeaking = false)
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
                                    currentQuestionIndex = currentQuestionIdx,
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
                _interviewState.value = _interviewState.value.copy(
                    error = app.getString(R.string.error_interview_start_failed, e.message ?: app.getString(R.string.error_unknown_error)),
                )
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
                currentQuestionIdx++

                // 更新状态为思考中
                _interviewState.value = currentState.copy(
                    messages = interviewTranscript.toList(),
                    questionCount = currentQuestionIdx,
                    phase = InterviewPhase.EVALUATING, // 复用 EVALUATING 表示 AI 思考中
                )

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
                        currentQuestionIndex = currentQuestionIdx - 1,
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
                    _interviewState.value = _interviewState.value.copy(coachFeedback = coachFb)
                }

                // 恢复正常状态
                _interviewState.value = _interviewState.value.copy(
                    phase = InterviewPhase.IN_PROGRESS,
                    config = currentState.config,
                    messages = interviewTranscript.toList(),
                    questionCount = interviewTranscript.count { it.role == "interviewer" },
                    elapsedSeconds = ((System.currentTimeMillis() - interviewStartTime) / 1000).toInt(),
                )

            } catch (e: Exception) {
                DebugLog.e("Interview", "处理回答失败: ${e.message}", e)
                _interviewState.value = _interviewState.value.copy(
                    phase = InterviewPhase.IN_PROGRESS,
                    error = app.getString(R.string.error_interview_processing_failed, e.message ?: app.getString(R.string.error_unknown_error)),
                )
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
        _interviewState.value = _interviewState.value.copy(phase = InterviewPhase.EVALUATING)

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
            _interviewState.value = _interviewState.value.copy(
                phase = InterviewPhase.COMPLETED,
                error = app.getString(R.string.error_interview_report_failed, e.message ?: app.getString(R.string.error_unknown_error)),
            )
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
        currentQuestionIdx = 0
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

        _interviewState.value = _interviewState.value.copy(isListening = true)

        voiceEngine?.startListening { partialText ->
            // 可以在这里实时显示识别结果（可选）
        }
    }

    /**
     * 停止语音监听。
     */
    fun stopInterviewListening() {
        voiceEngine?.stopListening()
        _interviewState.value = _interviewState.value.copy(isListening = false)
    }

    /**
     * 停止 TTS 播放。
     */
    fun stopInterviewSpeaking() {
        tts.stop()
        _interviewState.value = _interviewState.value.copy(isSpeaking = false)
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
        _interviewState.value = _interviewState.value.copy(duplexState = state)
    }

    /**
     * 标记插话事件（BargeIn）发生/消失。
     */
    fun setBargeInDetected(detected: Boolean) {
        _interviewState.value = _interviewState.value.copy(bargeInDetected = detected)
    }

    /**
     * 让面试官说话（TTS）。
     */
    suspend fun speakAsInterviewer(text: String) {
        _interviewState.value = _interviewState.value.copy(isSpeaking = true)
        voiceEngine?.aiSpeak(text)
        _interviewState.value = _interviewState.value.copy(isSpeaking = false)
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
            while (_interviewState.value.phase == InterviewPhase.IN_PROGRESS) {
                delay(1000L)
                val elapsed = ((System.currentTimeMillis() - interviewStartTime) / 1000).toInt()
                _interviewState.value = _interviewState.value.copy(elapsedSeconds = elapsed)
            }
        }
    }
}
