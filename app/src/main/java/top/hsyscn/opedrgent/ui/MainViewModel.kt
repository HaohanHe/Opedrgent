package top.hsyscn.opedrgent.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import top.hsyscn.opedrgent.agent.ResearchPhase
import top.hsyscn.opedrgent.agent.ResearchState
import top.hsyscn.opedrgent.note.Note
import top.hsyscn.opedrgent.note.NoteRepository
import top.hsyscn.opedrgent.note.NoteType
import top.hsyscn.opedrgent.note.FolderRepository
import top.hsyscn.opedrgent.model.ArtifactKind
import top.hsyscn.opedrgent.model.ChatMessage
import top.hsyscn.opedrgent.model.MemoryEntry
import top.hsyscn.opedrgent.model.MemoryType
import top.hsyscn.opedrgent.model.MessagePart
import top.hsyscn.opedrgent.model.QuestionPart
import top.hsyscn.opedrgent.model.ReasoningPart
import top.hsyscn.opedrgent.model.ResearchSession
import top.hsyscn.opedrgent.model.Role
import top.hsyscn.opedrgent.model.SessionSummary
import top.hsyscn.opedrgent.model.Skill
import top.hsyscn.opedrgent.model.SourceType
import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.model.ToolState
import top.hsyscn.opedrgent.model.ToolStateType
import top.hsyscn.opedrgent.llm.LocalLlmEngine
import top.hsyscn.opedrgent.llm.AvailableLocalModels
import top.hsyscn.opedrgent.llm.LocalLlmState
import top.hsyscn.opedrgent.network.LlmClient
import top.hsyscn.opedrgent.network.SourceFetcher
import top.hsyscn.opedrgent.network.StreamDelta
import top.hsyscn.opedrgent.network.ToolExecutor
import top.hsyscn.opedrgent.network.ToolResult
import top.hsyscn.opedrgent.network.WebSearcher
import top.hsyscn.opedrgent.network.HttpClients
import top.hsyscn.opedrgent.network.WebResearchMode
import top.hsyscn.opedrgent.network.WebResearchRequest
import top.hsyscn.opedrgent.network.WebResearchRouter
import top.hsyscn.opedrgent.network.MapTileFetcher
import top.hsyscn.opedrgent.env.EnvironmentProvider
import top.hsyscn.opedrgent.automation.AutomationKind
import top.hsyscn.opedrgent.automation.AutomationStore
import top.hsyscn.opedrgent.calendar.CalendarEventDraft
import top.hsyscn.opedrgent.calendar.IcsWriter
import top.hsyscn.opedrgent.settings.ApiSettings
import top.hsyscn.opedrgent.storage.MemoryStore
import top.hsyscn.opedrgent.storage.ResearchStore
import top.hsyscn.opedrgent.storage.SkillsStore
import top.hsyscn.opedrgent.pdf.PdfProcessor
import top.hsyscn.opedrgent.docx.DocxProcessor
import top.hsyscn.opedrgent.utils.PromptSafety
import top.hsyscn.opedrgent.utils.PromptBlocks
import top.hsyscn.opedrgent.utils.PromptBuilder
import top.hsyscn.opedrgent.utils.ModelInfo
import top.hsyscn.opedrgent.utils.PlatformContext
import top.hsyscn.opedrgent.utils.Platform
import top.hsyscn.opedrgent.utils.ContextCompressor
import top.hsyscn.opedrgent.utils.DebugLog
import top.hsyscn.opedrgent.intelligence.TokenBudgetMonitor
import top.hsyscn.opedrgent.security.FailClosedValidator
import top.hsyscn.opedrgent.ui.components.QuestionOption
import top.hsyscn.opedrgent.ui.components.QuestionInfo
import top.hsyscn.opedrgent.ui.components.QuestionRequest
import top.hsyscn.opedrgent.ui.components.ConfirmationOption
import top.hsyscn.opedrgent.ui.components.ConfirmationRequest
import top.hsyscn.opedrgent.tts.TtsPlayer
import top.hsyscn.opedrgent.interview.InterviewAgent
import top.hsyscn.opedrgent.interview.InterviewConfig
import top.hsyscn.opedrgent.interview.InterviewPhase
import top.hsyscn.opedrgent.interview.InterviewReport
import top.hsyscn.opedrgent.interview.InterviewType
import top.hsyscn.opedrgent.interview.DialogueTurn
import top.hsyscn.opedrgent.interview.CoachFeedback
import top.hsyscn.opedrgent.interview.VoiceConversationEngine
import top.hsyscn.opedrgent.interview.FullDuplexAudioEngine
import top.hsyscn.opedrgent.interview.AnalysisResult
import top.hsyscn.opedrgent.interview.NextAction
import top.hsyscn.opedrgent.interview.MaterialEntry
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.json.JSONArray
import android.net.Uri
import android.provider.OpenableColumns
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import android.content.ClipData
import android.content.ClipboardManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.asStateFlow
import top.hsyscn.opedrgent.stt.SttResult
import top.hsyscn.opedrgent.stt.EngineType
import top.hsyscn.opedrgent.stt.ModelType
import top.hsyscn.opedrgent.stt.ModelManager
import top.hsyscn.opedrgent.stt.SpeechEngine
import top.hsyscn.opedrgent.stt.SherpaOnnxEngine
import top.hsyscn.opedrgent.stt.MimoAsrEngine
import top.hsyscn.opedrgent.stt.AndroidSpeechRecognizer
import kotlin.math.pow
import top.hsyscn.opedrgent.stt.AudioProcessor
import top.hsyscn.opedrgent.stt.SttConfig
import top.hsyscn.opedrgent.mcp.skills.CuratorService
import top.hsyscn.opedrgent.mcp.skills.SkillLoader
import top.hsyscn.opedrgent.stt.RecognitionMode
import top.hsyscn.opedrgent.stt.StreamingRecognitionState
import top.hsyscn.opedrgent.insight.InsightSproutEngine
import top.hsyscn.opedrgent.insight.SproutConfig
import top.hsyscn.opedrgent.intelligence.BehaviorEvent
import top.hsyscn.opedrgent.intelligence.DailyReview
import top.hsyscn.opedrgent.intelligence.PushNotificationHelper
import top.hsyscn.opedrgent.intelligence.Recommendation
import top.hsyscn.opedrgent.intelligence.RecommendationEngine
import top.hsyscn.opedrgent.intelligence.UserBehaviorTracker

data class UiState(
    val sessions: List<SessionSummary> = emptyList(),
    val current: ResearchSession? = null,
    val skills: List<Skill> = emptyList(),
    val memories: List<MemoryEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val navigateToSessionId: String? = null,
    val openWebUrl: String? = null,
    val openBrowserUrl: String? = null,
    val evolutionSuggestion: EvolutionSuggestion? = null,
    val automationSuggestion: AutomationSuggestion? = null,
    val calendarSuggestion: CalendarSuggestion? = null,
    val sessionSearchQuery: String = "",
    val streamingText: String = "",
    val streamingReasoning: String = "",
    val streamingToolParts: List<ToolPart> = emptyList(),
    val streamingPhase: String = "",
    val streamingSessionId: String? = null,
    val activeQuestion: QuestionPart? = null,
    val isStreaming: Boolean = false,
    val deepThinkingEnabled: Boolean = false,
    val deepResearchEnabled: Boolean = false,
    val isSpeaking: Boolean = false,
    val contextTokenCount: Int = 0,
    val contextCompressionEnabled: Boolean = true,
    val debugModeEnabled: Boolean = false,
)

data class EvolutionSuggestion(
    val memory: String,
    val skillName: String,
    val skillPrompt: String,
    val raw: String,
)

data class AutomationSuggestion(
    val name: String,
    val intervalMinutes: Long,
    val kind: AutomationKind,
    val prompt: String?,
    val raw: String,
)

data class CalendarSuggestion(
    val events: List<CalendarEventDraft>,
    val raw: String,
)

data class ToolPermissionRequest(
    val toolName: String,
    val toolDescription: String,
    val paramsJson: String,
    val requestId: String = java.util.UUID.randomUUID().toString(),
)

data class ToolPermissionResponse(
    val requestId: String,
    val allowed: Boolean,
)

private data class StreamResult(
    val content: String = "",
    val reasoning: String = "",
    val toolCalls: List<top.hsyscn.opedrgent.network.CompletedToolCall> = emptyList(),
    val error: String? = null,
)

sealed class SttUiState {
    object Idle : SttUiState()
    data class SelectingSource(val showPicker: Boolean = true) : SttUiState()
    data class Validating(val uri: String) : SttUiState()
    data class DownloadingModel(val progress: Float, val modelSizeMb: Int) : SttUiState()
    data class DecodingAudio(val progress: Float, val fileName: String) : SttUiState()
    data class Recognizing(val progress: Float, val currentSegment: Int, val totalSegments: Int) : SttUiState()
    data class Done(val result: SttResult) : SttUiState()
    data class Error(val message: String, val errorCode: String = "UNKNOWN", val suggestion: String = "") : SttUiState()
}

enum class SttProgressState {
    IDLE,
    DOWNLOADING_MODEL,
    EXTRACTING_AUDIO,
    RECOGNIZING,
    DONE,
    ERROR,
}

sealed class SproutUiState {
    object Idle : SproutUiState()
    data class AnalyzingInput(val textPreview: String) : SproutUiState()
    data class PhaseInProgress(val phase: top.hsyscn.opedrgent.insight.SproutPhase, val elapsedSeconds: Long) : SproutUiState()
    data class GeneratingReport(val phasesCompleted: Int, val totalPhases: Int) : SproutUiState()
    data class Done(val report: String, val qualityScore: Int) : SproutUiState()
    data class Error(val message: String, val failedPhase: top.hsyscn.opedrgent.insight.SproutPhase? = null) : SproutUiState()
    data class Cancelled(val phasesCompleted: Int) : SproutUiState()
}

enum class SproutingState {
    IDLE,
    PHASE1,
    PHASE2,
    PHASE3,
    PHASE4,
    DONE,
    ERROR,
    CANCELLED,
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val http = HttpClients.default
    private val store = ResearchStore(app)
    val apiSettings = ApiSettings(app)
    private val localEngine = LocalLlmEngine.getInstance(app)
    private val skillsStore = SkillsStore(app)
    private val memoryStore = MemoryStore(app)
    private val sourceFetcher = SourceFetcher(http)
    private val llm = LlmClient(http)
    private val webSearcher = WebSearcher(http)
    private val webResearchRouter = WebResearchRouter(webSearcher, sourceFetcher)
    val asrManager = top.hsyscn.opedrgent.stt.AsrManager(app, apiSettings)
    // Gallery Skill 系统加载器（用于 run_js 工具执行 JS Skill）
    private val skillLoader = top.hsyscn.opedrgent.mcp.skills.SkillLoader(app)
    private val toolExecutor = ToolExecutor(app, webSearcher, sourceFetcher, llm, apiSettings, asrManager, skillLoader)
    private val tts = TtsPlayer(app, apiSettings)
    private val automationStore = AutomationStore(app)
    val noteRepository = NoteRepository(app, memoryStore)
    val folderRepository = FolderRepository(app)

    // ==================== 主动推送引擎 ====================
    val behaviorTracker = UserBehaviorTracker(app)
    private val recommendationEngine = RecommendationEngine(behaviorTracker, noteRepository)
    val pushNotificationHelper = PushNotificationHelper(app)

    private val _recommendations = MutableStateFlow<List<Recommendation>>(emptyList())
    /** 当前推荐列表（首页展示） */
    val recommendations: StateFlow<List<Recommendation>> = _recommendations.asStateFlow()

    private val _dailyReview = MutableStateFlow<DailyReview?>(null)
    /** 每日回顾数据 */
    val dailyReview: StateFlow<DailyReview?> = _dailyReview.asStateFlow()
    
    // Skills registry and prompt cache
    private val skillRegistry = top.hsyscn.opedrgent.mcp.skills.SkillRegistry.getInstance().apply {
        setIndexFile(java.io.File(app.filesDir, "skills_index.json"))
    }

    // Curator: 空闲触发的 Skill 自动维护（归档/恢复，不删除）
    private val curatorService = CuratorService(skillRegistry, app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    /** AI 对话会话总数（供首页统计卡片使用） */
    val sessionCount: Int get() = _state.value.sessions.size

    private val _toolPermissionRequest = MutableStateFlow<ToolPermissionRequest?>(null)
    val toolPermissionRequest: StateFlow<ToolPermissionRequest?> = _toolPermissionRequest

    private val _toolPermissionResponse = MutableSharedFlow<ToolPermissionResponse>(replay = 1)

    fun respondToToolPermission(requestId: String, allowed: Boolean) {
        _toolPermissionResponse.tryEmit(ToolPermissionResponse(requestId, allowed))
    }

    private val _questionRequest = MutableStateFlow<QuestionRequest?>(null)
    val questionRequest: StateFlow<QuestionRequest?> = _questionRequest

    private val _questionResponse = MutableSharedFlow<List<List<String>>>(replay = 0)

    fun respondToQuestion(answers: List<List<String>>) {
        _questionRequest.value = null
        _questionResponse.tryEmit(answers)
    }

    private val _confirmationRequest = MutableStateFlow<ConfirmationRequest?>(null)
    val confirmationRequest: StateFlow<ConfirmationRequest?> = _confirmationRequest

    private val _confirmationResponse = MutableSharedFlow<String?>(replay = 0)

    fun respondToConfirmation(selectedOption: String?) {
        _confirmationRequest.value = null
        _confirmationResponse.tryEmit(selectedOption)
    }

    val _sttProgress = MutableStateFlow<SttProgressState>(SttProgressState.IDLE)
    val sttProgress: StateFlow<SttProgressState> = _sttProgress.asStateFlow()

    private val _sttUiState = MutableStateFlow<SttUiState>(SttUiState.Idle)
    val sttUiState: StateFlow<SttUiState> = _sttUiState.asStateFlow()

    val _sttResult = MutableStateFlow<SttResult?>(null)
    val sttResult: StateFlow<SttResult?> = _sttResult.asStateFlow()

    private val _sttHistory = MutableStateFlow<List<SttResult>>(emptyList())
    val sttHistory: StateFlow<List<SttResult>> = _sttHistory.asStateFlow()

    val _sttError = MutableStateFlow<String?>(null)
    val sttError: StateFlow<String?> = _sttError.asStateFlow()

    private val _sttEventBus = MutableSharedFlow<String>(extraBufferCapacity = 1)

    private var lastFailedUri: Uri? = null
    private var sherpaOnnxEngine: SherpaOnnxEngine? = null
    private var androidSpeechRecognizer: AndroidSpeechRecognizer? = null

    val _sproutingState = MutableStateFlow<SproutingState>(SproutingState.IDLE)
    val sproutingState: StateFlow<SproutingState> = _sproutingState.asStateFlow()

    private val _sproutUiState = MutableStateFlow<SproutUiState>(SproutUiState.Idle)
    val sproutUiState: StateFlow<SproutUiState> = _sproutUiState.asStateFlow()

    val _sproutResult = MutableStateFlow<String?>(null)
    val sproutResult: StateFlow<String?> = _sproutResult.asStateFlow()

    private val _sproutHistory = MutableStateFlow<List<top.hsyscn.opedrgent.insight.SproutResult>>(emptyList())
    val sproutHistory: StateFlow<List<top.hsyscn.opedrgent.insight.SproutResult>> = _sproutHistory.asStateFlow()

    private val sproutCache = mutableMapOf<String, top.hsyscn.opedrgent.insight.SproutResult>()

    private var sttEngine: SpeechEngine? = null
    private var sttJob: Job? = null
    private var sproutJob: Job? = null

    // ==================== AI 风格转换 ====================
    private val _aiConvertedContent = MutableStateFlow<String?>(null)
    val aiConvertedContent: StateFlow<String?> = _aiConvertedContent.asStateFlow()

    private val _isConverting = MutableStateFlow(false)
    val isConverting: StateFlow<Boolean> = _isConverting.asStateFlow()

    private var currentCall: Call? = null
    private var currentRunJob: Job? = null
    private val cancelled = AtomicBoolean(false)
    private val sessionCache = mutableMapOf<String, ResearchSession>()
    // Tool executor serialized via limitedParallelism(1) dispatcher to prevent concurrent duplicate searches
    private val searchDispatcher = Dispatchers.IO.limitedParallelism(1)

    // ==================== Agent 循环状态机 ====================

    /** Agent 循环状态 */
    private enum class LoopState {
        IDLE,       // 空闲
        RUNNING,    // 运行中
        RETRYING,   // 重试中
        COMPACTING, // 压缩中
    }

    /** 循环轮次结果 */
    private sealed class LoopOutcome {
        object Continue : LoopOutcome()
        object Break : LoopOutcome()
        data class Retry(val delay: Long) : LoopOutcome()
        data class Error(val message: String) : LoopOutcome()
    }

    /** 循环上下文 —— 跨轮次共享的可变状态 */
    private class LoopContext(
        val sessionId: String,
        val config: top.hsyscn.opedrgent.settings.ApiConfig,
        val maxContextTokens: Int,
        val toolMessages: MutableList<ChatMessage> = mutableListOf(),
        val allToolParts: MutableList<ToolPart> = mutableListOf(),
        val usedUrls: HashSet<String> = hashSetOf(),
        var sourceTagIdx: Int = 0,
        var accumulatedText: String = "",
        var accumulatedReasoning: String = "",
        var finalContent: String = "",
        var finalReasoning: String = "",
    )

    /** runLoop() 返回值 —— 供 runModel() 后处理使用 */
    private data class LoopResult(
        val finalContent: String,
        val finalReasoning: String,
        val accumulatedText: String,
        val accumulatedReasoning: String,
        val allToolParts: List<ToolPart>,
        val wasCancelled: Boolean,
    )

    private var loopState = LoopState.IDLE
    private var retryCount = 0
    private var lastError: String? = null

    private object RetryPolicy {
        const val INITIAL_DELAY_MS = 2000L
        const val BACKOFF_FACTOR = 2.0
        const val MAX_DELAY_MS = 30_000L
        const val MAX_RETRIES = 3

        fun delay(attempt: Int, error: Exception): Long {
            return minOf(
                INITIAL_DELAY_MS * BACKOFF_FACTOR.pow(attempt),
                MAX_DELAY_MS.toDouble(),
            ).toLong()
        }

        fun isRetryable(error: Exception): Boolean {
            val msg = error.message?.lowercase() ?: return false
            if (msg.contains("overflow") || msg.contains("token")) return false
            if (msg.contains("429") || msg.contains("rate limit")) return true
            if (msg.contains("500") || msg.contains("502") || msg.contains("503")) return true
            if (msg.contains("network") || msg.contains("timeout")) return true
            return false
        }

        fun isRetryableErrorType(classifiedType: top.hsyscn.opedrgent.network.ClassifiedErrorType): Boolean {
            return when (classifiedType) {
                top.hsyscn.opedrgent.network.ClassifiedErrorType.RATE_LIMIT,
                top.hsyscn.opedrgent.network.ClassifiedErrorType.TIMEOUT,
                top.hsyscn.opedrgent.network.ClassifiedErrorType.SERVER_ERROR,
                top.hsyscn.opedrgent.network.ClassifiedErrorType.NETWORK_ERROR -> true
                else -> false
            }
        }
    }

    init {
        DebugLog.enabled = apiSettings.isDebugMode()
        DebugLog.i("MainViewModel init")

        // 主动推送引擎：记录应用打开事件
        behaviorTracker.track(BehaviorEvent.APP_OPENED)

        // Load built-in skills
        top.hsyscn.opedrgent.mcp.skills.BuiltinSkillLoader.loadBuiltinSkills(skillRegistry)
        // Initialize skill prompt cache
        top.hsyscn.opedrgent.mcp.skills.SkillPromptCache.initialize(app.cacheDir)

        // Curator: 启动时非阻塞检查是否需要运行维护（空闲触发）
        viewModelScope.launch(Dispatchers.IO) {
            val result = curatorService.maybeRunCurator()
            if (result.ran) {
                DebugLog.i("Curator: maintenance completed — ${result.summary}")
            }
            // 首次生成推荐
            refreshActivePushInternal()
        }

        _state.value = _state.value.copy(
            deepThinkingEnabled = apiSettings.isDeepThinking(),
            deepResearchEnabled = apiSettings.isDeepResearch(),
            debugModeEnabled = apiSettings.isDebugMode(),
        )
        refreshSessions()
        refreshSkills()
        refreshMemories()
        automationStore.scheduleAllEnabled()
        val last = apiSettings.getLastSessionId()
        if (!last.isNullOrBlank()) {
            _state.value = _state.value.copy(current = store.getSession(last))
        }
    }

    fun refreshSessions() {
        val sessions = store.listSessions()
        sessionCache.clear()
        sessions.forEach { sessionCache[it.id] = store.getSession(it.id)!! }
        _state.value = _state.value.copy(sessions = sessions)
    }

    fun setSessionSearchQuery(q: String) {
        val query = q.trim()
        val allSessions = sessionCache.values.toList()
        if (query.isEmpty()) {
            _state.value = _state.value.copy(
                sessions = allSessions.map { SessionSummary(it.id, it.title, it.updatedAt) },
                sessionSearchQuery = "",
            )
            return
        }
        val lowered = query.lowercase()
        val filtered = allSessions.filter { s ->
            val hay = buildString {
                append(s.title)
                append("\n")
                append(s.notes)
                append("\n")
                s.sources.forEach { append(it.title ?: ""); append(" "); append(it.url ?: ""); append(" "); append(it.content); append("\n") }
                s.messages.forEach { append(it.content); append("\n") }
                s.artifacts.forEach { append(it.content); append("\n") }
            }.lowercase()
            hay.contains(lowered)
        }
        _state.value = _state.value.copy(
            sessions = filtered.map { SessionSummary(it.id, it.title, it.updatedAt) },
            sessionSearchQuery = query,
        )
    }

    fun refreshSkills() {
        _state.value = _state.value.copy(skills = skillsStore.list())
    }

    fun refreshMemories() {
        _state.value = _state.value.copy(memories = memoryStore.list())
    }

    // ==================== 主动推送引擎 API ====================

    /**
     * 刷新主动推荐列表（公开方法，供 UI 调用）。
     * 在后台线程异步执行，完成后更新 StateFlow。
     */
    fun refreshActivePush() {
        viewModelScope.launch(Dispatchers.IO) { refreshActivePushInternal() }
    }

    /**
     * 内部推荐刷新实现 — 同步调用推荐引擎并更新 StateFlow。
     */
    private suspend fun refreshActivePushInternal() {
        try {
            val recs = recommendationEngine.generateRecommendations()
            _recommendations.value = recs
            val review = recommendationEngine.generateDailyReview()
            _dailyReview.value = review
        } catch (e: Exception) {
            DebugLog.e("推荐引擎刷新失败: ${e.message}")
        }
    }

    fun addMemory(title: String, content: String, type: MemoryType = MemoryType.USER) {
        memoryStore.add(title, content, type)
        refreshMemories()
    }

    fun updateMemory(id: String, title: String, content: String, type: MemoryType = MemoryType.USER) {
        memoryStore.update(id, title, content, type)
        refreshMemories()
    }

    fun deleteMemory(id: String) {
        memoryStore.delete(id)
        refreshMemories()
    }

    fun openSession(id: String) {
        // 切换会话时，如果正在生成回复，先取消并保存部分内容
        if (_state.value.isStreaming) {
            cancelled.set(true)
            currentCall?.cancel()
            currentRunJob?.cancel()
            currentCall = null
            currentRunJob = null
            savePartialStreamingContent()
            _state.value = _state.value.copy(
                isStreaming = false,
                streamingText = "",
                streamingReasoning = "",
                streamingToolParts = emptyList(),
                streamingPhase = "",
                streamingSessionId = null,
                loading = false,
            )
        }
        _state.value = _state.value.copy(current = store.getSession(id), error = null)
        apiSettings.setLastSessionId(id)
    }

    fun closeSession() {
        _state.value = _state.value.copy(current = null, error = null)
        refreshSessions()
    }

    fun createSession(title: String) {
        behaviorTracker.track(BehaviorEvent.AI_CHAT_CREATED, mapOf("title" to title))
        val session = store.createSession(title)
        refreshSessions()
        openSession(session.id)
    }

    fun createSessionAndNavigate(title: String) {
        val session = store.createSession(title)
        refreshSessions()
        _state.value = _state.value.copy(navigateToSessionId = session.id)
        openSession(session.id)
    }

    fun addUrlSource(url: String) {
        val sessionId = _state.value.current?.id ?: return
        viewModelScope.launch {
            setLoading(true)
            try {
                val fetched = withContext(Dispatchers.IO) { sourceFetcher.fetchUrl(url) }
                val raw = fetched.text.takeIf { it.isNotBlank() } ?: "抓取失败：正文为空"
                val sanitized = PromptSafety.sanitizeForPrompt(raw, sourceLabel = url)
                val content = sanitized.content
                val next = store.addSource(
                    sessionId = sessionId,
                    type = SourceType.URL,
                    title = fetched.title,
                    url = url,
                    content = content,
                )
                _state.value = _state.value.copy(current = next)
                refreshSessions()
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "抓取失败", openWebUrl = url)
            } finally {
                setLoading(false)
            }
        }
    }

    fun addTextSource(title: String?, text: String) {
        val sessionId = _state.value.current?.id ?: return
        val sanitized = PromptSafety.sanitizeForPrompt(text, sourceLabel = title ?: "TEXT")
        val next = store.addSource(
            sessionId = sessionId,
            type = SourceType.TEXT,
            title = title,
            url = null,
            content = sanitized.content,
        )
        _state.value = _state.value.copy(current = next)
        refreshSessions()
    }

    fun sendUserMessage(text: String) {
        // 主动推送引擎：追踪 AI 消息发送行为
        behaviorTracker.track(BehaviorEvent.AI_MESSAGE_SENT, mapOf("length" to text.length.toString()))

        var sessionId = _state.value.current?.id
        if (sessionId == null) {
            // 从外部入口进入时没有活跃 session，自动创建
            val session = store.createSession("新对话")
            refreshSessions()
            openSession(session.id)
            sessionId = session.id
            // 触发导航到 AI Tab
            _state.value = _state.value.copy(navigateToSessionId = session.id)
            DebugLog.i("sendUserMessage: 自动创建新 session $sessionId, 导航到 AI Tab")
        }
        if (text.isBlank()) return
        DebugLog.i("sendUserMessage: ${text.take(100)}")

        val finalText = if (_state.value.deepResearchEnabled) {
            buildString {
                appendLine("请先将以下研究主题拆解为 3-5 个关键词/子问题，分别联网检索，最后综合回答。")
                appendLine()
                appendLine("用户问题：$text")
            }
        } else {
            text.trim()
        }

        store.addMessage(sessionId, Role.USER, finalText)
        _state.value = _state.value.copy(current = store.getSession(sessionId))
        refreshSessions()
        runModel(sessionId)
    }

    /**
     * 将录音文件作为音频消息发送到当前会话。
     * 对标 Gallery ChatHistory AudioMessageProto 的多模态音频消息。
     */
    fun sendAudioMessage(filePath: String, durationMs: Long, transcript: String? = null) {
        var sessionId = _state.value.current?.id
        if (sessionId == null) {
            val session = store.createSession("新对话")
            refreshSessions()
            openSession(session.id)
            sessionId = session.id
            _state.value = _state.value.copy(navigateToSessionId = session.id)
        }

        val audioPart = MessagePart.AudioClip(
            filePath = filePath,
            sampleRate = 16000,
            durationMs = durationMs,
            transcript = transcript ?: "",
        )

        // 构造带转录文本的消息内容（如果有）
        val contentText = if (!transcript.isNullOrBlank()) {
            "[语音消息] $transcript"
        } else "[语音消息]"

        store.addMessage(
            sessionId = sessionId,
            role = Role.USER,
            content = contentText,
            parts = listOf(audioPart),
        )
        _state.value = _state.value.copy(current = store.getSession(sessionId))
        refreshSessions()
        runModel(sessionId)

        DebugLog.i("sendAudioMessage: 已发送音频消息, 文件=$filePath, 时长=${durationMs}ms")
    }

    // ==================== 知识图谱 API ====================

    /** 将笔记内容发送到聊天，让 AI 分析 */
    fun sendNoteToChat(noteId: Long) {
        behaviorTracker.track(BehaviorEvent.NOTE_SENT_TO_AI, mapOf("noteId" to noteId.toString()))
        viewModelScope.launch(Dispatchers.IO) {
            val note = noteRepository.getNoteById(noteId) ?: return@launch
            val linkedIds = noteRepository.getLinkedNotes(noteId)
            val prompt = buildString {
                appendLine("请帮我分析以下笔记，并与已有关联笔记进行对比：")
                appendLine()
                appendLine("【标题】${note.title}")
                appendLine("【内容】")
                append(note.content.take(2000))
                if (linkedIds.isNotEmpty()) {
                    appendLine()
                    appendLine()
                    appendLine("已知关联笔记：")
                    for (linkedId in linkedIds.take(5)) {
                        val linkedNote = noteRepository.getNoteById(linkedId.toLongOrNull() ?: continue)
                        if (linkedNote != null) {
                            appendLine("- ${linkedNote.title}: ${linkedNote.content.take(200)}")
                        }
                    }
                }
            }
            withContext(Dispatchers.Main) {
                sendUserMessage(prompt)
            }
        }
    }

    /**
     * 将笔记内容通过指定 Skill 发送到聊天让 AI 处理。
     * 自动切换到 AI Tab 并发送消息。
     */
    fun sendNoteWithSkill(noteId: Long, skillId: String) {
        behaviorTracker.track(BehaviorEvent.SKILL_USED, mapOf("noteId" to noteId.toString(), "skillId" to skillId))
        viewModelScope.launch(Dispatchers.IO) {
            val note = noteRepository.getNoteById(noteId) ?: return@launch

            // 内置 Skill prompt（SkillSystem 中定义的三个笔记 AI 操作）
            val builtInPrompts = mapOf(
                "insight_review" to "你是一位善于发现亮点的分析师。请对以下文本进行深入分析，识别其中的闪光点和高光时刻，给出正向强化的评价，帮助用户看到自己做得好的地方。",
                "critical_inquiry" to "你是一位严谨的思想挑战师。请对以下观点进行苏格拉底式追问，帮助用户把想法想透，发现逻辑漏洞、隐含假设和潜在反驳。",
                "text_refine" to "你是一位专业的内容编辑。请对以下文本进行润色和优化，把口语化表达打磨成流畅的成品内容，保留用户的真实声音和核心观点。",
            )

            val promptText = builtInPrompts[skillId]
                ?: _state.value.skills.firstOrNull { it.id == skillId }?.prompt
                ?: "请分析以下内容："

            val prompt = buildString {
                appendLine(promptText)
                appendLine()
                appendLine("---")
                appendLine("标题: ${note.title}")
                appendLine("内容:")
                appendLine(note.content)
                appendLine("---")
            }

            withContext(Dispatchers.Main) {
                sendUserMessage(prompt)
            }
        }
    }

    /** 将 AI 回复保存为笔记 */
    fun saveAiReplyAsNote(messageIndex: Int) {
        val session = _state.value.current ?: return
        val message = session.messages.getOrNull(messageIndex) ?: return
        if (message.role != Role.ASSISTANT) return

        val note = Note(
            title = "AI 回复 - ${session.title}",
            content = message.content,
            type = NoteType.AI_CHAT,
            sourceType = top.hsyscn.opedrgent.note.SourceType.AI_GENERATED,
        )
        viewModelScope.launch {
            noteRepository.saveNote(note)
        }
    }

    /** 从文本创建笔记（录音转写 / AI 回复等场景） */
    fun createNoteFromText(title: String, content: String, type: NoteType = NoteType.TEXT) {
        val note = Note(
            title = title,
            content = content,
            type = type,
            sourceType = when (type) {
                NoteType.MEETING -> top.hsyscn.opedrgent.note.SourceType.MEETING_TRANSCRIPT
                NoteType.ASR -> top.hsyscn.opedrgent.note.SourceType.ASR
                NoteType.AI_CHAT -> top.hsyscn.opedrgent.note.SourceType.AI_GENERATED
                NoteType.LINK -> top.hsyscn.opedrgent.note.SourceType.LINK_EXTRACT
                NoteType.IMAGE -> top.hsyscn.opedrgent.note.SourceType.DOCUMENT_IMPORT
                NoteType.PDF -> top.hsyscn.opedrgent.note.SourceType.DOCUMENT_IMPORT
                else -> top.hsyscn.opedrgent.note.SourceType.MANUAL
            },
            wordCount = content.length,
        )
        viewModelScope.launch {
            noteRepository.saveNote(note)
        }
    }

    /** 获取笔记的上下文（用于 AI 对话时引用） */
    fun getNoteContext(noteId: Long): String? {
        val note = runCatching {
            kotlinx.coroutines.runBlocking { noteRepository.getNoteById(noteId) }
        }.getOrNull() ?: return null
        return "笔记「${note.title}」：${note.content.take(500)}"
    }

    /** 获取知识图谱统计 */
    fun getKnowledgeStats(): top.hsyscn.opedrgent.note.KnowledgeGraph.GraphStats {
        return noteRepository.getKnowledgeStats()
    }

    /** 语义搜索笔记 */
    fun searchNotesByRelevance(query: String, maxResults: Int = 5): List<Pair<String, Float>> {
        return noteRepository.searchByRelevance(query, maxResults)
    }

    /** 获取笔记的关联 */
    fun getLinkedNotes(noteId: Long): List<String> {
        return noteRepository.getLinkedNotes(noteId)
    }

    // ==================== 笔记推荐 ====================

    private val _recommendedNotes = MutableStateFlow<List<Note>>(emptyList())
    val recommendedNotes: StateFlow<List<Note>> = _recommendedNotes.asStateFlow()

    fun refreshRecommendations() {
        viewModelScope.launch {
            val notes = noteRepository.getRecentNotes(1)
            if (notes.isNotEmpty()) {
                val latestNote = notes.first()
                val recommended = noteRepository.getLinkedNotesWithTitles(latestNote.id)
                _recommendedNotes.value = recommended
            }
        }
    }

    fun runSkill(skillId: String) {
        val sessionId = _state.value.current?.id ?: return
        val skill = _state.value.skills.firstOrNull { it.id == skillId } ?: return
        store.addMessage(sessionId, Role.USER, skill.prompt.trim())
        _state.value = _state.value.copy(current = store.getSession(sessionId))
        refreshSessions()
        runModel(sessionId)
    }

    fun runSkillByName(name: String) {
        val sessionId = _state.value.current?.id ?: return
        val skill = skillsStore.findByName(name) ?: run {
            _state.value = _state.value.copy(error = "未找到技能：$name")
            return
        }
        store.addMessage(sessionId, Role.USER, skill.prompt.trim())
        _state.value = _state.value.copy(current = store.getSession(sessionId))
        refreshSessions()
        runModel(sessionId)
    }

    fun addOrUpdateSkill(id: String?, name: String, prompt: String) {
        val now = System.currentTimeMillis()
        val normalizedName = name.trim()
        val normalizedPrompt = prompt.trim()
        if (normalizedName.isEmpty() || normalizedPrompt.isEmpty()) {
            _state.value = _state.value.copy(error = "技能名称和内容不能为空")
            return
        }
        val skill = if (id == null) {
            Skill(name = normalizedName, prompt = normalizedPrompt, createdAt = now, updatedAt = now)
        } else {
            val existing = _state.value.skills.firstOrNull { it.id == id }
            Skill(
                id = id,
                name = normalizedName,
                prompt = normalizedPrompt,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )
        }
        skillsStore.upsert(skill)
        refreshSkills()
    }

    fun deleteSkill(skillId: String) {
        skillsStore.delete(skillId)
        refreshSkills()
    }

    // ════════════════════════════════════════════════
    // Gallery 标准技能管理（V2 SkillLoader 集成）
    // ════════════════════════════════════════════════

    /** Gallery 标准技能列表（来自 SkillLoader） */
    var gallerySkills: List<top.hsyscn.opedrgent.mcp.skills.StandardSkillDefinition> = emptyList()
        private set

    /**
     * 刷新 Gallery 技能列表（从 SkillLoader 重新加载）
     * 在 UI 层通过 LaunchedEffect 调用
     */
    suspend fun refreshGallerySkills() {
        gallerySkills = skillLoader.loadAllSkills()
    }

    /**
     * 从远程 URL 导入 Skill（Gallery 标准）
     * @param url SKILL.md 文件的远程 URL
     * @return 导入结果
     */
    suspend fun importSkillFromUrl(url: String): Result<top.hsyscn.opedrgent.mcp.skills.StandardSkillDefinition> {
        return skillLoader.importFromUrl(url)
    }

    /**
     * 从本地文件导入 Skill（Gallery 标准）
     * @param context Android Context（用于 ContentResolver）
     * @param uri 文件 URI
     * @return 导入结果
     */
    suspend fun importSkillFromFile(
        context: android.content.Context,
        uri: android.net.Uri,
    ): Result<top.hsyscn.opedrgent.mcp.skills.StandardSkillDefinition> {
        return skillLoader.importFromFile(uri)
    }

    /**
     * 运行 Gallery 标准技能：将技能指令注入当前会话并触发 LLM 执行
     *
     * 对于 JS/Native 类型的 Skill，LLM 会通过 run_js / run_intent 工具自动调用；
     * 对于 Text-Only 类型，指令直接作为系统上下文增强。
     */
    fun runGallerySkill(skill: top.hsyscn.opedrgent.mcp.skills.StandardSkillDefinition) {
        val sessionId = _state.value.current?.id ?: run {
            _state.value = _state.value.copy(error = "请先打开或创建一个 AI 会话")
            return
        }
        // 构建包含完整指令的用户消息，让 LLM 知道要使用该技能
        val instructionMessage = buildString {
            append("[使用技能: ${skill.metadata.name}]\n")
            append("${skill.metadata.description}\n\n")
            if (skill.needsSecret) {
                append("[注意] 此技能需要 API Key。如果尚未配置，请提示用户输入。\n\n")
            }
            // 如果有 JS 脚本路径，提示 LLM 使用 run_js 工具
            if (skill.localScriptsPath != null) {
                append("请使用 run_js 工具执行此技能的 JavaScript 脚本。\n")
                append("脚本名称: ${skill.skillName}\n")
            } else {
                // Text-Only Skill：将指令作为用户请求发送
                append(skill.instructions)
            }
        }
        store.addMessage(sessionId, Role.USER, instructionMessage.trim())
        _state.value = _state.value.copy(current = store.getSession(sessionId))
        refreshSessions()
        runModel(sessionId)
    }

    /**
     * 切换 Gallery 技能的启用/禁用状态
     */
    fun toggleGallerySkill(skillName: String, enabled: Boolean) {
        skillLoader.setSkillEnabled(skillName, enabled)
    }

    /**
     * 删除用户导入的 Gallery 技能（内置技能不可删除）
     */
    fun deleteGallerySkill(skillName: String) {
        val success = skillLoader.deleteSkill(skillName)
        if (!success) {
            _state.value = _state.value.copy(error = "无法删除技能 '$skillName'（可能为内置技能）")
        }
    }

    fun listSkillsAsMessage() {
        val sessionId = _state.value.current?.id ?: return
        val skills = _state.value.skills
        val text = if (skills.isEmpty()) {
            "暂无技能。"
        } else {
            skills.joinToString(separator = "\n") { "- ${it.name}" }
        }
        store.addMessage(sessionId, Role.ASSISTANT, text)
        _state.value = _state.value.copy(current = store.getSession(sessionId))
        refreshSessions()
    }

    fun generateSummary() {
        val sessionId = _state.value.current?.id ?: return
        store.addMessage(sessionId, Role.USER, "基于当前来源与对话，生成一份简洁摘要，并用 [S1]/[S2] 形式标注引用。")
        _state.value = _state.value.copy(current = store.getSession(sessionId))
        refreshSessions()
        runModel(sessionId, artifactKind = ArtifactKind.SUMMARY)
    }

    fun generateReport() {
        val sessionId = _state.value.current?.id ?: return
        store.addMessage(sessionId, Role.USER, "基于当前来源与对话，生成一份 Markdown 研究报告（含要点、结论、引用），并用 [S1]/[S2] 形式标注引用。")
        _state.value = _state.value.copy(current = store.getSession(sessionId))
        refreshSessions()
        runModel(sessionId, artifactKind = ArtifactKind.REPORT)
    }

    private suspend fun autoGenerateArtifact(sessionId: String, kind: ArtifactKind) {
        val config = apiSettings.getApiConfig() ?: return
        val session = store.getSession(sessionId) ?: return
        val system = buildSystemPrompt(session)
        val systemForArtifact = if (session.notes.isNotBlank()) {
            "$system\n\n## Session Notes\n${session.notes}"
        } else system

        val prompt = when (kind) {
            ArtifactKind.SUMMARY -> "基于当前来源与对话，生成一份简洁摘要，并用 [S1]/[S2] 形式标注引用。仅输出摘要内容。"
            ArtifactKind.REPORT -> "基于当前来源与对话，生成一份 Markdown 研究报告（含要点、结论、引用），并用 [S1]/[S2] 形式标注引用。仅输出报告内容。"
            else -> return
        }

        val response = llm.chatCompletions(
            config = config,
            system = systemForArtifact,
            messages = listOf(ChatMessage(role = Role.USER, content = prompt, createdAt = System.currentTimeMillis())),
        )
        val clean = response.trim()
        if (clean.isNotEmpty()) {
            store.addArtifact(sessionId, kind, clean)
            val updated = store.getSession(sessionId)
            if (updated != null) {
                _state.value = _state.value.copy(current = updated)
            }
        }
    }

    private val agentTools: List<top.hsyscn.opedrgent.network.ToolDefinition> by lazy {
        listOf(
            top.hsyscn.opedrgent.network.ToolDefinition(
                name = "ask_question",
                description = """向用户提出一个选择题或多个选择题，等待用户选择后根据选择结果继续回答。

【使用场景】：
- 当你需要了解用户偏好才能给出更好的答案时
- 当一个问题有多种可能的解决方案时
- 当你不确定用户想要哪个方向时
- 当需要用户在多个选项中做决定时

【重要规则】：
- 一次可以问1个或多个相关问题
- 每个问题提供2-5个选项
- 选项要具体、互斥、覆盖主要可能性
- 问题要简洁明确""",
                parameters = org.json.JSONObject().apply {
                    put("type", "object")
                    put("properties", org.json.JSONObject().apply {
                        put("questions", org.json.JSONObject().apply {
                            put("type", "array")
                            put("description", "要问用户的问题列表")
                            put("items", org.json.JSONObject().apply {
                                put("type", "object")
                                put("properties", org.json.JSONObject().apply {
                                    put("question", org.json.JSONObject().apply {
                                        put("type", "string")
                                        put("description", "问题的文本内容")
                                    })
                                    put("header", org.json.JSONObject().apply {
                                        put("type", "string")
                                        put("description", "问题组的标题，如'请选择'")
                                    })
                                    put("options", org.json.JSONObject().apply {
                                        put("type", "array")
                                        put("description", "可选的答案选项")
                                        put("items", org.json.JSONObject().apply {
                                            put("type", "object")
                                            put("properties", org.json.JSONObject().apply {
                                                put("label", org.json.JSONObject().apply {
                                                    put("type", "string")
                                                    put("description", "选项显示的文字")
                                                })
                                                put("description", org.json.JSONObject().apply {
                                                    put("type", "string")
                                                    put("description", "选项的详细描述（可选）")
                                                })
                                            })
                                            put("required", org.json.JSONArray().apply { put("label") })
                                        })
                                    })
                                    put("multiple", org.json.JSONObject().apply {
                                        put("type", "boolean")
                                        put("description", "是否允许多选，默认false")
                                    })
                                    put("allowCustom", org.json.JSONObject().apply {
                                        put("type", "boolean")
                                        put("description", "是否允许用户输入自定义答案，默认false")
                                    })
                                })
                                put("required", org.json.JSONArray().apply { put("question"); put("options") })
                            })
                        })
                    })
                    put("required", org.json.JSONArray().apply { put("questions") })
                }
            ),
            top.hsyscn.opedrgent.network.ToolDefinition(
                name = "ask_confirmation",
                description = """请求用户确认或选择操作。当模型需要用户授权才能继续时使用。

【使用场景】：
- 需要用户确认才能执行的操作（如"我来帮你接管浏览器"）
- 需要用户在多个操作中选择（如"我帮你解验证码 or 你自己来？"）
- 耗时操作需要用户确认（如"搜索需要30秒，确定继续吗？"）
- 用户提问但需要澄清（如"你要我搜索英文还是中文结果？"）

【重要规则】：
- message：简要说明需要确认的内容
- detail：可选的详细说明
- options：可选的操作选项列表
- timeoutSeconds：超时秒数，默认30秒，超过后模型自动继续
- 如果用户超时未响应，模型会收到 timeout=true 并自动决定下一步

【调用示例】：
{"message": "我来帮你接管浏览器完成验证码", "detail": "我将打开浏览器，请在验证码页面完成后点击确认", "options": [{"label": "我来输入", "description": "我自己输入验证码"}, {"label": "AI接管", "description": "让AI自动识别并填写"}], "timeoutSeconds": 30}
""",
                parameters = org.json.JSONObject().apply {
                    put("type", "object")
                    put("properties", org.json.JSONObject().apply {
                        put("message", org.json.JSONObject().apply {
                            put("type", "string")
                            put("description", "需要用户确认的简要说明")
                        })
                        put("detail", org.json.JSONObject().apply {
                            put("type", "string")
                            put("description", "可选的详细说明")
                        })
                        put("options", org.json.JSONObject().apply {
                            put("type", "array")
                            put("description", "可选的操作选项")
                            put("items", org.json.JSONObject().apply {
                                put("type", "object")
                                put("properties", org.json.JSONObject().apply {
                                    put("label", org.json.JSONObject().apply {
                                        put("type", "string")
                                        put("description", "选项显示的文字")
                                    })
                                    put("description", org.json.JSONObject().apply {
                                        put("type", "string")
                                        put("description", "选项的详细说明")
                                    })
                                })
                            })
                        })
                        put("timeoutSeconds", org.json.JSONObject().apply {
                            put("type", "integer")
                            put("description", "超时秒数，默认30秒，超过后自动继续")
                        })
                    })
                    put("required", org.json.JSONArray().apply { put("message") })
                }
            ),
            top.hsyscn.opedrgent.network.ToolDefinition(
                name = "web_search",
                description = """搜索互联网获取最新信息。当用户询问需要网络查询才能回答的问题时必须使用此工具。

【重要】：如果用户已经提供了具体的 URL（http:// 或 https:// 开头），请使用 read_url 工具直接访问，不要用此工具搜索。
此工具仅用于：用户提出问题但未给出具体网址时，需要你主动搜索相关信息。""",
                parameters = org.json.JSONObject().apply {
                    put("type", "object")
                    put("properties", org.json.JSONObject().apply {
                        put("query", org.json.JSONObject().apply {
                            put("type", "string")
                            put("description", "搜索关键词")
                        })
                        put("method", org.json.JSONObject().apply {
                            put("type", "string")
                            put("enum", org.json.JSONArray().apply { put("ddg"); put("webview") })
                            put("description", "搜索方法，默认 ddg")
                        })
                    })
                    put("required", org.json.JSONArray().apply { put("query") })
                }
            ),
            top.hsyscn.opedrgent.network.ToolDefinition(
                name = "read_url",
                description = """读取并提取指定URL网页的文字内容。

【重要使用规则】：
- 当用户消息中包含任何 URL（http:// 或 https:// 开头）时，必须优先使用此工具直接访问
- 当用户说"打开这个链接"、"访问这个网址"、"看看这个页面"时，使用此工具
- 不要对用户提供的具体 URL 使用 web_search，直接用此工具读取内容
- 此工具用于获取已知 URL 的完整页面内容，而非搜索新信息""",
                parameters = org.json.JSONObject().apply {
                    put("type", "object")
                    put("properties", org.json.JSONObject().apply {
                        put("url", org.json.JSONObject().apply {
                            put("type", "string")
                            put("description", "要读取的网页URL（必须是完整的 http:// 或 https:// 开头的地址）")
                        })
                    })
                    put("required", org.json.JSONArray().apply { put("url") })
                }
            ),
            top.hsyscn.opedrgent.network.ToolDefinition(
                name = "reverse_geocode",
                description = "将经纬度坐标转换为具体地名地址。调用后返回该坐标对应的城市、街道等详细信息。",
                parameters = org.json.JSONObject().apply {
                    put("type", "object")
                    put("properties", org.json.JSONObject().apply {
                        put("lat", org.json.JSONObject().apply {
                            put("type", "number")
                            put("description", "纬度")
                        })
                        put("lon", org.json.JSONObject().apply {
                            put("type", "number")
                            put("description", "经度")
                        })
                    })
                    put("required", org.json.JSONArray().apply { put("lat"); put("lon") })
                }
            ),
            top.hsyscn.opedrgent.network.ToolDefinition(
                name = "mimo_tts",
                description = """使用MiMo引擎生成高质量语音合成。

【使用场景】：
- 当用户需要文字转语音时
- 当需要生成音频内容时
- 当用户要求朗读文本时

【重要说明】：
- text参数为必填，包含要合成的文本内容
- 可选参数：voice（音色名称）、model（模型ID）、style_instruction（风格指令）、overall_style（整体风格）、singing（是否唱歌模式）
- 合成成功后会保存到设备下载目录并自动打开""",
                parameters = org.json.JSONObject().apply {
                    put("type", "object")
                    put("properties", org.json.JSONObject().apply {
                        put("text", org.json.JSONObject().apply {
                            put("type", "string")
                            put("description", "要合成语音的文本内容（必填）")
                        })
                        put("voice", org.json.JSONObject().apply {
                            put("type", "string")
                            put("description", "音色名称，如'冰糖'、'小夏'等（可选）")
                        })
                        put("model", org.json.JSONObject().apply {
                            put("type", "string")
                            put("description", "模型ID，如'mimo-v2.5-tts'（可选）")
                        })
                        put("style_instruction", org.json.JSONObject().apply {
                            put("type", "string")
                            put("description", "自然语言风格指令（可选）")
                        })
                        put("overall_style", org.json.JSONObject().apply {
                            put("type", "string")
                            put("description", "整体风格描述（可选）")
                        })
                        put("singing", org.json.JSONObject().apply {
                            put("type", "boolean")
                            put("description", "是否启用唱歌模式（可选，默认false）")
                        })
                    })
                    put("required", org.json.JSONArray().apply { put("text") })
                }
            ),
        )
    }

    private fun runModel(sessionId: String, artifactKind: ArtifactKind? = null) {
        // 防止并发：如果已有运行中的任务，先取消并保存部分内容
        if (currentRunJob?.isActive == true) {
            cancelled.set(true)
            currentCall?.cancel()
            currentRunJob?.cancel()
            currentCall = null
            currentRunJob = null
            savePartialStreamingContent()
            _state.value = _state.value.copy(
                isStreaming = false,
                streamingText = "",
                streamingReasoning = "",
                streamingToolParts = emptyList(),
                streamingPhase = "",
                streamingSessionId = null,
                loading = false,
            )
        }
        cancelled.set(false)
        currentRunJob = viewModelScope.launch {
            setLoading(true)
            _state.value = _state.value.copy(
                streamingSessionId = sessionId,
                isStreaming = true,
                streamingText = "",
                streamingReasoning = "",
            )
            try {
                val useLocalModel = apiSettings.isLocalModelEnabled() && localEngine.isReady

                if (useLocalModel) {
                    DebugLog.i("runModel: Using local model ${localEngine.currentModelId}")
                    runLocalModel(sessionId)
                    return@launch
                }

                if (apiSettings.isLocalModelEnabled()) {
                    val engineState = localEngine.state
                    val errorDetail = when (engineState) {
                        is LocalLlmState.Error -> ": ${engineState.message}"
                        is LocalLlmState.Uninitialized -> "，请先在设置中选择并加载模型"
                        is LocalLlmState.Loading -> "，模型正在加载中，请稍候"
                        else -> ""
                    }
                    _state.value = _state.value.copy(
                        streamingText = "[错误] 本地模型未就绪$errorDetail",
                        streamingPhase = "错误",
                    )
                    setLoading(false)
                    return@launch
                }

                val config = apiSettings.getApiConfig()
                    ?: throw IllegalStateException("请先在设置里填写 API Key 或加载本地模型")
                val maxContextTokens = if (top.hsyscn.opedrgent.network.LlmClient.isDeepSeekV4(config.model)) {
                    top.hsyscn.opedrgent.network.LlmClient.getDeepSeekMaxContext()
                } else 16000

                val ctx = LoopContext(
                    sessionId = sessionId,
                    config = config,
                    maxContextTokens = maxContextTokens,
                )

                val loopResult = runLoop(ctx)

                if (loopResult == null || loopResult.wasCancelled) {
                    savePartialStreamingContent()
                    return@launch
                }

                val rawContent = top.hsyscn.opedrgent.utils.ToolCallParser.stripAllTags(loopResult.finalContent).trim()
                val cleanFinal = top.hsyscn.opedrgent.utils.StringUtils.sanitizeJsonNull(rawContent).let { if (it.isEmpty()) loopResult.finalContent.trim() else it }
                val displayContent = cleanFinal.ifEmpty { loopResult.finalContent.trim() }

                val cleanReasoning = top.hsyscn.opedrgent.utils.StringUtils.sanitizeJsonNull(loopResult.finalReasoning)
                val reasoningParts = if (cleanReasoning.isNotEmpty()) {
                    listOf(ReasoningPart(text = cleanReasoning, endTime = System.currentTimeMillis()))
                } else emptyList()

                // 构建结构化 parts 列表（新模型）
                val messageParts = buildList {
                    if (loopResult.accumulatedReasoning.isNotBlank()) {
                        add(MessagePart.Reasoning(content = loopResult.accumulatedReasoning))
                    }
                    loopResult.allToolParts.forEach { tp ->
                        add(MessagePart.ToolCall(
                            toolName = tp.tool,
                            callId = tp.id,
                            state = tp.state,
                            input = tp.state.input,
                            output = tp.state.output,
                        ))
                    }
                    if (displayContent.isNotBlank()) {
                        add(MessagePart.Text(content = displayContent))
                    }
                }

                store.addMessage(
                    sessionId, Role.ASSISTANT, displayContent,
                    toolParts = loopResult.allToolParts,
                    reasoningParts = reasoningParts,
                    parts = messageParts,
                )

                if (artifactKind != null) {
                    store.addArtifact(sessionId, artifactKind, displayContent)
                }

                _state.value = _state.value.copy(
                    current = store.getSession(sessionId),
                    error = null,
                    isStreaming = false,
                    streamingText = "",
                    streamingReasoning = "",
                    streamingToolParts = emptyList(),
                    streamingPhase = "",
                    streamingSessionId = null,
                )
                refreshSessions()

                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        autoGenerateArtifact(sessionId, ArtifactKind.SUMMARY)
                        autoGenerateArtifact(sessionId, ArtifactKind.REPORT)
                    } catch (_: Exception) { }
                }

                if (apiSettings.isTtsEnabled() && apiSettings.isTtsAutoSpeak()) {
                    val downloadOnly = apiSettings.isTtsDownloadOnly()
                    tts.speak(
                        text = displayContent,
                        localeTag = apiSettings.getTtsLocaleTag(),
                        rate = apiSettings.getTtsRate(),
                        pitch = apiSettings.getTtsPitch(),
                        mimoVoice = apiSettings.getTtsMimoVoice(),
                        downloadOnly = downloadOnly,
                    )
                    if (!downloadOnly) {
                        _state.value = _state.value.copy(isSpeaking = true)
                    }
                }

                DebugLog.i("runModel: complete, final=${displayContent.length} chars, tools=${loopResult.allToolParts.size}")
            } catch (e: Exception) {
                if (!cancelled.get()) {
                    DebugLog.e("runModel error: ${e.message}", e)
                    _state.value = _state.value.copy(
                        error = e.message ?: "请求失败",
                        isStreaming = false,
                        streamingText = "",
                        streamingReasoning = "",
                        streamingToolParts = emptyList(),
                        streamingPhase = "",
                        streamingSessionId = null,
                    )
                }
            } finally {
                if (!cancelled.get()) {
                    setLoading(false)
                }
                currentCall = null
                currentRunJob = null
            }
        }
    }


    // ==================== Agent 循环状态机方法 ====================

    /** runLoop: 状态机驱动的 Agent 循环 */
    private suspend fun runLoop(ctx: LoopContext): LoopResult? {
        loopState = LoopState.RUNNING
        retryCount = 0
        lastError = null

        val state = ResearchState(maxRounds = 10)
        val budgetTracker = TokenBudgetMonitor.createTracker()

        try {
            while (state.shouldContinue()) {
                // 1. 取消检查
                if (cancelled.get()) {
                    DebugLog.i("runLoop cancelled at round ${state.roundsUsed}")
                    return null
                }

                // 2. 执行单轮 LLM 调用
                val outcome = try {
                    executeOneRound(ctx, state)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    handleRoundError(e)
                }

                // 3. Token 预算检查（递减检测）
                val currentTokens = ctx.toolMessages.sumOf { top.hsyscn.opedrgent.utils.ContextCompressor.estimateTokens(it.textContent) }
                val budgetDecision = TokenBudgetMonitor.checkBudget(budgetTracker, ctx.maxContextTokens, currentTokens)

                when (budgetDecision) {
                    is TokenBudgetMonitor.BudgetDecision.Continue -> {
                        _state.value = _state.value.copy(
                            streamingPhase = budgetDecision.nudgeMessage,
                        )
                    }
                    is TokenBudgetMonitor.BudgetDecision.Stop -> {
                        DebugLog.i("runLoop: TokenBudgetMonitor 决定停止 — diminishing=${budgetDecision.diminishingReturns}, duration=${budgetDecision.durationMs}ms")
                        if (budgetDecision.diminishingReturns) {
                            _state.value = _state.value.copy(streamingPhase = "检测到 token 递减，主动结束生成")
                        }
                        // 推进状态以便记录最终结果，然后跳出循环
                        budgetTracker.let { TokenBudgetMonitor.advanceState(it, currentTokens) }
                        break
                    }
                }

                // 更新预算追踪状态
                budgetTracker.let { TokenBudgetMonitor.advanceState(it, currentTokens) }

                // 4. 根据结果决定下一步
                when (outcome) {
                    is LoopOutcome.Continue -> {
                        retryCount = 0  // 成功，重置重试计数
                    }
                    is LoopOutcome.Break -> break
                    is LoopOutcome.Retry -> {
                        loopState = LoopState.RETRYING
                        retryCount++
                        _state.value = _state.value.copy(
                            streamingPhase = "请求失败，${outcome.delay / 1000}秒后重试…(第${retryCount}次)",
                        )
                        delay(outcome.delay)
                        loopState = LoopState.RUNNING
                    }
                    is LoopOutcome.Error -> {
                        lastError = outcome.message
                        break
                    }
                }
            }
        } finally {
            loopState = LoopState.IDLE
        }

        return LoopResult(
            finalContent = ctx.finalContent,
            finalReasoning = ctx.finalReasoning,
            accumulatedText = ctx.accumulatedText,
            accumulatedReasoning = ctx.accumulatedReasoning,
            allToolParts = ctx.allToolParts.toList(),
            wasCancelled = cancelled.get(),
        )
    }

    /** 执行单轮 LLM 调用 + 工具执行 */
    private suspend fun executeOneRound(ctx: LoopContext, state: ResearchState): LoopOutcome {
        val session = store.getSession(ctx.sessionId) ?: throw IllegalStateException("会话不存在")
        val system = buildSystemPrompt(session)

        val allMessages = session.messages + ctx.toolMessages
        val compressed = ContextCompressor.compress(allMessages, system, ctx.maxContextTokens)
        val compressedSystem = if (compressed.summary != null) {
            "$system\n\n${compressed.summary}"
        } else system
        val messages = compressed.messages

        DebugLog.d("executeOneRound: round ${state.roundsUsed}, messages=${messages.size}, tokens=${compressed.tokenCount}")

        state.advanceTo(ResearchPhase(
            name = if (state.roundsUsed == 0) "思考中" else "继续思考",
        ))
        _state.value = _state.value.copy(streamingPhase = state.nextPhaseLabel())

        val mapImages = if (state.roundsUsed == 0) {
            withContext(Dispatchers.IO) { tryFetchLocationMap(messages) }
        } else emptyList()

        val result = if (mapImages.isNotEmpty()) {
            _state.value = _state.value.copy(streamingPhase = "正在分析地图…")
            withContext(Dispatchers.IO) {
                streamMultimodalLlm(ctx.config, compressedSystem, messages, mapImages, tools = agentTools, deepThinkingEnabled = _state.value.deepThinkingEnabled)
            }
        } else {
            withContext(Dispatchers.IO) {
                streamLlm(ctx.config, compressedSystem, messages, tools = agentTools, deepThinkingEnabled = _state.value.deepThinkingEnabled)
            }
        }

        // 取消检查（流式传输后）
        if (cancelled.get()) {
            DebugLog.i("executeOneRound cancelled after streaming round ${state.roundsUsed}")
            return LoopOutcome.Break
        }

        // 处理 LLM 返回的错误
        if (result.error != null) {
            val classified = top.hsyscn.opedrgent.network.ErrorClassifier.classify(
                java.lang.Exception(result.error), null, null
            )
            DebugLog.e("executeOneRound: LLM returned error: ${result.error}, roundsUsed=${state.roundsUsed}")
            DebugLog.e("executeOneRound: error classified: ${top.hsyscn.opedrgent.network.ErrorClassifier.formatForLog(classified)}")
            if (result.content.isNotBlank()) {
                ctx.accumulatedText += (if (ctx.accumulatedText.isNotBlank()) "\n\n" else "") + result.content
            }
            val enhancedErrorMsg = when (classified.type) {
                top.hsyscn.opedrgent.network.ClassifiedErrorType.RATE_LIMIT -> "${result.error} (请求过于频繁，请稍后重试)"
                top.hsyscn.opedrgent.network.ClassifiedErrorType.TIMEOUT -> "${result.error} (请求超时，请检查网络)"
                top.hsyscn.opedrgent.network.ClassifiedErrorType.CAPTCHA -> "${result.error} (触发了人机验证，可能需要更换API Key或节点)"
                top.hsyscn.opedrgent.network.ClassifiedErrorType.SSL_ERROR -> "${result.error} (SSL证书错误)"
                top.hsyscn.opedrgent.network.ClassifiedErrorType.FORBIDDEN -> "${result.error} (访问被拒绝，请检查API Key是否有效)"
                else -> result.error
            }
            state.recordNoToolCalls(result.content.ifEmpty { "执行失败: $enhancedErrorMsg" })
            _state.value = _state.value.copy(
                streamingText = ctx.accumulatedText,
                streamingPhase = "生成回答…",
            )
            // 可重试错误：返回 Retry 让 runLoop 处理
            if (RetryPolicy.isRetryableErrorType(classified.type)) {
                val retryDelay = top.hsyscn.opedrgent.network.ErrorClassifier.getRetryDelayMs(classified)
                return LoopOutcome.Retry(retryDelay)
            }
            return LoopOutcome.Error(enhancedErrorMsg)
        }

        ctx.finalContent = result.content
        ctx.finalReasoning = result.reasoning

        if (result.content.isNotBlank()) {
            ctx.accumulatedText += (if (ctx.accumulatedText.isNotBlank()) "\n\n" else "") + result.content
        }
        if (result.reasoning.isNotBlank()) {
            ctx.accumulatedReasoning += (if (ctx.accumulatedReasoning.isNotBlank()) "\n" else "") + result.reasoning
        }

        _state.value = _state.value.copy(
            streamingText = ctx.accumulatedText,
            streamingReasoning = ctx.accumulatedReasoning,
            streamingPhase = "生成回答…",
        )

        if (result.toolCalls.isEmpty()) {
            DebugLog.i("executeOneRound: no tool_call in response, model is done at round ${state.roundsUsed}")
            state.recordNoToolCalls(result.content)
            _state.value = _state.value.copy(streamingPhase = "生成回答…")
            return LoopOutcome.Break
        }

        val pendingToolParts = result.toolCalls.mapIndexed { idx, tc ->
            val parsedArgs: Map<String, String> = runCatching {
                org.json.JSONObject(tc.arguments).let { json ->
                    json.keys().asSequence().associateWith { json.opt(it).toString() }
                }
            }.getOrDefault(emptyMap())
            ToolPart(
                tool = tc.name,
                state = ToolState(
                    status = ToolStateType.PENDING,
                    input = parsedArgs,
                    startTime = System.currentTimeMillis(),
                ),
            )
        }
        ctx.allToolParts.addAll(pendingToolParts)
        _state.value = _state.value.copy(streamingToolParts = ctx.allToolParts.toList())

        // FAIL-CLOSED 参数校验：在执行前检查所有工具调用的安全性
        val rejectedToolIndices = mutableSetOf<Int>()
        for ((idx, tc) in result.toolCalls.withIndex()) {
            val parsedArgs: Map<String, String> = runCatching {
                org.json.JSONObject(tc.arguments).let { json ->
                    json.keys().asSequence().associateWith { json.opt(it).toString() }
                }
            }.getOrDefault(emptyMap())
            val (passed, errorMsg) = FailClosedValidator.validateToolInputStringParams(tc.name, parsedArgs)
            if (!passed) {
                DebugLog.w("executeOneRound: FailClosedValidator 拒绝 tool=${tc.name} — $errorMsg")
                val errorTp = pendingToolParts[idx].copy(state = pendingToolParts[idx].state.copy(
                    status = ToolStateType.ERROR,
                    error = "安全校验失败: $errorMsg",
                ))
                synchronized(ctx.allToolParts) {
                    val pos = ctx.allToolParts.indexOfFirst { it.id == pendingToolParts[idx].id }
                    if (pos >= 0) ctx.allToolParts[pos] = errorTp
                }
                ctx.toolMessages.add(ChatMessage(
                    role = Role.USER,
                    content = "[安全校验拒绝] $errorMsg",
                    createdAt = System.currentTimeMillis(),
                    toolCallId = tc.id,
                ))
                rejectedToolIndices.add(idx)
            }
        }

        coroutineScope {
            result.toolCalls.forEachIndexed { idx, tc ->
                // 跳过被 FAIL-CLOSED 校验拒绝的工具调用
                if (idx in rejectedToolIndices) return@forEachIndexed

                async(Dispatchers.IO) {
                    if (cancelled.get()) return@async

                    val tp = pendingToolParts[idx]
                    val runningTp = tp.copy(state = tp.state.copy(status = ToolStateType.RUNNING))

                    val phaseText = when {
                        tc.name == "web_search" -> {
                            val q = tp.state.input["query"] ?: tp.state.input["keyword"] ?: ""
                            if (q.isNotBlank() && q != "{{query}}") "正在搜索: $q" else "正在搜索…"
                        }
                        tc.name == "read_url" -> {
                            val u = tp.state.input["url"] ?: ""
                            if (u.isNotBlank()) {
                                val host = runCatching { java.net.URL(u).host }.getOrDefault(u.take(30))
                                "正在读取: $host"
                            } else "正在读取网页…"
                        }
                        else -> "正在执行: ${tc.name}"
                    }
                    _state.value = _state.value.copy(streamingPhase = phaseText)

                    synchronized(ctx.allToolParts) {
                        val pos = ctx.allToolParts.indexOfFirst { it.id == tp.id }
                        if (pos >= 0) ctx.allToolParts[pos] = runningTp
                    }
                    _state.value = _state.value.copy(streamingToolParts = ctx.allToolParts.toList())

                    val toolDef = agentTools.firstOrNull { it.name == tc.name }
                    val toolDesc = toolDef?.description ?: tc.name
                    val paramsJson = tc.arguments

                    if (tc.name == "ask_question") {
                        try {
                            val params = org.json.JSONObject(tc.arguments ?: "{}")
                            val questionsArray = params.getJSONArray("questions")
                            val questions = (0 until questionsArray.length()).map { i ->
                                val q = questionsArray.getJSONObject(i)
                                val optionsArray = q.getJSONArray("options")
                                val options = (0 until optionsArray.length()).map { j ->
                                    val opt = optionsArray.getJSONObject(j)
                                    QuestionOption(
                                        label = opt.getString("label"),
                                        description = if (opt.has("description")) opt.getString("description") else "",
                                    )
                                }
                                QuestionInfo(
                                    question = q.getString("question"),
                                    header = if (q.has("header")) q.getString("header") else "请选择",
                                    options = options,
                                    multiple = q.optBoolean("multiple", false),
                                    allowCustom = q.optBoolean("allowCustom", false),
                                )
                            }

                            _questionRequest.emit(QuestionRequest(questions = questions))

                            _state.value = _state.value.copy(streamingPhase = "等待用户选择…")

                            val answers = _questionResponse.first()
                            _questionRequest.value = null

                            val resultAnswers = answers.mapIndexed { idx, ans ->
                                mapOf("question" to questions[idx].question, "answers" to ans)
                            }
                            val resultTp = tp.copy(state = tp.state.copy(
                                status = ToolStateType.COMPLETED,
                                output = org.json.JSONObject(mapOf("answers" to resultAnswers)).toString(),
                            ))
                            synchronized(ctx.allToolParts) {
                                val pos = ctx.allToolParts.indexOfFirst { it.id == tp.id }
                                if (pos >= 0) ctx.allToolParts[pos] = resultTp
                            }
                            _state.value = _state.value.copy(streamingToolParts = ctx.allToolParts.toList())

                            ctx.toolMessages.add(ChatMessage(
                                role = Role.USER,
                                content = org.json.JSONObject(mapOf("answers" to resultAnswers)).toString(),
                                createdAt = System.currentTimeMillis(),
                                toolCallId = tc.id,
                            ))
                        } catch (e: Exception) {
                            DebugLog.e("ask_question error: ${e.message}", e)
                            _questionRequest.value = null
                            val errorTp = tp.copy(state = tp.state.copy(
                                status = ToolStateType.ERROR,
                                error = "ask_question 处理失败: ${e.message}",
                            ))
                            synchronized(ctx.allToolParts) {
                                val pos = ctx.allToolParts.indexOfFirst { it.id == tp.id }
                                if (pos >= 0) ctx.allToolParts[pos] = errorTp
                            }
                            _state.value = _state.value.copy(streamingToolParts = ctx.allToolParts.toList())
                        }
                        return@async
                    }

                    if (tc.name == "ask_confirmation") {
                        try {
                            val params = org.json.JSONObject(tc.arguments ?: "{}")
                            val message = params.optString("message", "请确认")
                            val detail = params.optString("detail", "")
                            val timeoutSeconds = params.optInt("timeoutSeconds", 30)
                            val optionsArray = params.optJSONArray("options") ?: org.json.JSONArray()
                            val options = (0 until optionsArray.length()).map { j ->
                                val opt = optionsArray.getJSONObject(j)
                                ConfirmationOption(
                                    label = opt.optString("label", ""),
                                    description = opt.optString("description", ""),
                                )
                            }

                            _confirmationRequest.emit(ConfirmationRequest(
                                message = message,
                                detail = detail,
                                options = options,
                                timeoutSeconds = timeoutSeconds,
                            ))

                            _state.value = _state.value.copy(streamingPhase = "等待确认…(${timeoutSeconds}s超时)")

                            val selectedOption = _confirmationResponse.first()
                            _confirmationRequest.value = null
                            val confirmed = selectedOption != null
                            val actualOption = if (selectedOption == "__confirmed__") null else selectedOption

                            val resultMap = buildString {
                                append("{")
                                append("\"confirmed\": $confirmed")
                                if (actualOption != null) {
                                    append(", \"selectedOption\": \"${actualOption.replace("\"", "\\\"")}\"")
                                }
                                append(", \"timeout\": false")
                                append("}")
                            }
                            val resultTp = tp.copy(state = tp.state.copy(
                                status = ToolStateType.COMPLETED,
                                output = resultMap,
                            ))
                            synchronized(ctx.allToolParts) {
                                val pos = ctx.allToolParts.indexOfFirst { it.id == tp.id }
                                if (pos >= 0) ctx.allToolParts[pos] = resultTp
                            }
                            _state.value = _state.value.copy(streamingToolParts = ctx.allToolParts.toList())

                            ctx.toolMessages.add(ChatMessage(
                                role = Role.USER,
                                content = resultMap,
                                createdAt = System.currentTimeMillis(),
                                toolCallId = tc.id,
                            ))
                        } catch (e: Exception) {
                            DebugLog.e("ask_confirmation error: ${e.message}", e)
                            _confirmationRequest.value = null
                            val errorTp = tp.copy(state = tp.state.copy(
                                status = ToolStateType.ERROR,
                                error = "ask_confirmation 处理失败: ${e.message}",
                            ))
                            synchronized(ctx.allToolParts) {
                                val pos = ctx.allToolParts.indexOfFirst { it.id == tp.id }
                                if (pos >= 0) ctx.allToolParts[pos] = errorTp
                            }
                            _state.value = _state.value.copy(streamingToolParts = ctx.allToolParts.toList())
                        }
                        return@async
                    }

                    val execResult = withContext(Dispatchers.IO) {
                        toolExecutor.execute(tp, ctx.config, system, useProviderSearch = isProviderWebSearchEnabled())
                    }

                    val doneTp = execResult.toolPart
                    synchronized(ctx.allToolParts) {
                        val pos = ctx.allToolParts.indexOfFirst { it.id == tp.id }
                        if (pos >= 0) ctx.allToolParts[pos] = doneTp
                    }
                    _state.value = _state.value.copy(streamingToolParts = ctx.allToolParts.toList())

                    val newSources = execResult.addedSources.filter { ctx.usedUrls.add(it) }
                    if (newSources.isNotEmpty()) {
                        val s = store.getSession(ctx.sessionId)
                        newSources.forEach { url ->
                            if (s != null && s.sources.none { it.url == url }) {
                                store.addSource(ctx.sessionId, SourceType.URL, title = url, url = url, content = "")
                            }
                        }
                    }

                    if (execResult.openBrowserUrl != null) {
                        _state.value = _state.value.copy(openBrowserUrl = execResult.openBrowserUrl)
                    }

                    val newSources2 = execResult.addedSources.filter { it.isNotBlank() && !ctx.usedUrls.contains(it) }
                    if (newSources2.isNotEmpty()) {
                        val s = store.getSession(ctx.sessionId)
                        newSources2.forEach { url ->
                            if (s != null && s.sources.none { it.url == url }) {
                                store.addSource(ctx.sessionId, SourceType.URL, title = url, url = url, content = "")
                            }
                        }
                    }

                    val taggedSources = execResult.addedSources.mapNotNull { url ->
                        if (url.isBlank()) return@mapNotNull null
                        ctx.sourceTagIdx++
                        "S${ctx.sourceTagIdx}"
                    }
                    val sourceTags = if (taggedSources.isNotEmpty()) {
                        "\n[来源: ${taggedSources.joinToString(" ")}]"
                    } else ""

                    val toolOutput = when {
                        execResult.toolPart.state.status == ToolStateType.ERROR -> {
                            "[工具执行失败: ${execResult.toolPart.state.error?.take(100)}]"
                        }
                        execResult.toolPart.state.output != null -> execResult.toolPart.state.output
                        else -> "工具执行完成"
                    }
                    ctx.toolMessages.add(ChatMessage(
                            role = Role.USER,
                            content = "$toolOutput$sourceTags",
                            createdAt = System.currentTimeMillis(),
                            toolCallId = tc.id,
                        ))
                }
            }
        }

        refreshSessions()

        val searchCount = result.toolCalls.count { it.name == "web_search" }
        val fetchCount = result.toolCalls.count { it.name == "read_url" }
        if (searchCount > 0) {
            state.advanceTo(ResearchPhase(
                name = "搜索完成",
                searchesCompleted = state.completedSearches.size + searchCount,
                lastToolName = "web_search",
            ))
        } else if (fetchCount > 0) {
            state.advanceTo(ResearchPhase(
                name = "读取完成",
                pagesFetched = state.fetchedUrls.size + fetchCount,
                lastToolName = "read_url",
            ))
        }
        _state.value = _state.value.copy(streamingPhase = state.nextPhaseLabel())

        if (result.content.isNotEmpty() || result.toolCalls.isNotEmpty()) {
            val tcJsonArr = org.json.JSONArray()
            result.toolCalls.forEach { tc ->
                tcJsonArr.put(org.json.JSONObject().apply {
                    put("id", tc.id)
                    put("type", "function")
                    put("function", org.json.JSONObject().apply {
                        put("name", tc.name)
                        put("arguments", tc.arguments)
                    })
                })
            }
            val cleanReasoning = top.hsyscn.opedrgent.utils.StringUtils.sanitizeJsonNull(result.reasoning)
            ctx.toolMessages.add(ChatMessage(
                role = Role.ASSISTANT,
                content = result.content,
                createdAt = System.currentTimeMillis(),
                apiToolCallsJson = tcJsonArr.toString(),
                reasoningParts = if (cleanReasoning.isNotEmpty()) {
                    listOf(ReasoningPart(text = cleanReasoning, endTime = System.currentTimeMillis()))
                } else emptyList(),
            ))
        }

        return LoopOutcome.Continue
    }

    /** 处理轮次异常，返回 Retry 或 Error */
    private fun handleRoundError(e: Exception): LoopOutcome {
        if (e is CancellationException) throw e

        val retryDelay = RetryPolicy.delay(retryCount, e)
        return if (RetryPolicy.isRetryable(e) && retryCount < RetryPolicy.MAX_RETRIES) {
            DebugLog.e("handleRoundError: retryable error, attempt ${retryCount + 1}, delay=${retryDelay}ms: ${e.message}")
            LoopOutcome.Retry(retryDelay)
        } else {
            DebugLog.e("handleRoundError: non-retryable error: ${e.message}", e)
            LoopOutcome.Error(e.message ?: "未知错误")
        }
    }

    private class StreamingBuffer(
        private val flushIntervalMs: Long = 30L,
    ) {
        private val contentBuf = StringBuilder()
        private val reasoningBuf = StringBuilder()
        private val toolCallBuf = StringBuilder()
        private var lastToolCall: String? = null
        private var flushJob: Job? = null

        fun appendDelta(content: String, reasoning: String) {
            synchronized(this) {
                if (content.isNotEmpty()) contentBuf.append(content)
                if (reasoning.isNotEmpty()) reasoningBuf.append(reasoning)
            }
        }

        fun appendToolCall(nameDelta: String, argsDelta: String) {
            synchronized(this) {
                if (nameDelta.isNotEmpty()) toolCallBuf.append(nameDelta)
                else if (argsDelta.isNotEmpty()) lastToolCall = argsDelta.take(20)
            }
        }

        suspend fun startFlushing(scope: CoroutineScope, onFlush: (content: String, reasoning: String, toolCallSnippet: String?) -> Unit) {
            flushJob = scope.launch(Dispatchers.Main) {
                while (true) {
                    delay(flushIntervalMs)
                    val flush = synchronized(this@StreamingBuffer) {
                        if (contentBuf.isEmpty() && reasoningBuf.isEmpty() && toolCallBuf.isEmpty() && lastToolCall == null) {
                            null
                        } else {
                            val content = contentBuf.toString()
                            val reasoning = reasoningBuf.toString()
                            val toolCall = if (toolCallBuf.isNotEmpty()) toolCallBuf.toString() else lastToolCall?.let { "[调用工具: $it...]" }
                            contentBuf.clear()
                            reasoningBuf.clear()
                            toolCallBuf.clear()
                            lastToolCall = null
                            Triple(content, reasoning, toolCall)
                        }
                    }
                    if (flush != null) {
                        onFlush(flush.first, flush.second, flush.third)
                    }
                }
            }
        }

        fun flushFinal(onFlush: (content: String, reasoning: String) -> Unit) {
            flushJob?.cancel()
            val flush = synchronized(this@StreamingBuffer) {
                val content = contentBuf.toString()
                val reasoning = reasoningBuf.toString()
                contentBuf.clear()
                reasoningBuf.clear()
                toolCallBuf.clear()
                lastToolCall = null
                Pair(content, reasoning)
            }
            if (flush.first.isNotEmpty() || flush.second.isNotEmpty()) {
                onFlush(flush.first, flush.second)
            }
        }

        fun cancel() {
            flushJob?.cancel()
            synchronized(this) {
                contentBuf.clear()
                reasoningBuf.clear()
                toolCallBuf.clear()
                lastToolCall = null
            }
        }
    }

    private suspend fun streamLlm(
        config: top.hsyscn.opedrgent.settings.ApiConfig,
        system: String,
        messages: List<ChatMessage>,
        tools: List<top.hsyscn.opedrgent.network.ToolDefinition> = emptyList(),
        deepThinkingEnabled: Boolean = false,
    ): StreamResult = withContext(Dispatchers.IO) {
        val contentBuilder = StringBuilder()
        val reasoningBuilder = StringBuilder()
        val ctx = currentCoroutineContext()
        val buffer = StreamingBuffer(flushIntervalMs = 30L)

        val tempToolParts = mutableListOf<ToolPart>()
        val seenToolIdx = mutableSetOf<Int>()

        var lastFlushTime = 0L
        val throttleIntervalMs = 30L

        kotlinx.coroutines.suspendCancellableCoroutine<StreamResult> { continuation ->
            var completed = false
            try {
                val job = CoroutineScope(ctx).launch(Dispatchers.IO) {
                    try {
                        val call = llm.streamChatCompletions(
                            config = config,
                            system = system,
                            messages = messages,
                            tools = tools,
                            thinkingEnabled = deepThinkingEnabled,
                            onDelta = { delta ->
                                when (delta) {
                                    is top.hsyscn.opedrgent.network.StreamDelta.TextDelta -> {
                                        val t = top.hsyscn.opedrgent.utils.StringUtils.sanitizeJsonNull(delta.text)
                                        if (t.isNotEmpty()) {
                                            contentBuilder.append(t)
                                            val now = System.currentTimeMillis()
                                            if (now - lastFlushTime >= throttleIntervalMs) {
                                                lastFlushTime = now
                                                val textSnapshot = contentBuilder.toString()
                                                val reasonSnapshot = reasoningBuilder.toString()
                                                _state.value = _state.value.copy(
                                                    streamingText = textSnapshot,
                                                    streamingReasoning = reasonSnapshot,
                                                    isStreaming = true,
                                                )
                                            }
                                        }
                                    }
                                    is top.hsyscn.opedrgent.network.StreamDelta.ReasoningDelta -> {
                                        val t = top.hsyscn.opedrgent.utils.StringUtils.sanitizeJsonNull(delta.text)
                                        if (t.isNotEmpty()) {
                                            reasoningBuilder.append(t)
                                            val now = System.currentTimeMillis()
                                            if (now - lastFlushTime >= throttleIntervalMs) {
                                                lastFlushTime = now
                                                val textSnapshot = contentBuilder.toString()
                                                val reasonSnapshot = reasoningBuilder.toString()
                                                _state.value = _state.value.copy(
                                                    streamingText = textSnapshot,
                                                    streamingReasoning = reasonSnapshot,
                                                    isStreaming = true,
                                                )
                                            }
                                        }
                                    }
                                }
                            },
                            onToolCallDelta = { tc ->
                                buffer.appendToolCall(tc.nameDelta, tc.argsDelta)
                                if (tc.nameDelta.isNotEmpty() && tc.index !in seenToolIdx) {
                                    seenToolIdx.add(tc.index)
                                    val tempTp = ToolPart(
                                        tool = tc.nameDelta.trim(),
                                        state = ToolState(
                                            status = ToolStateType.PENDING,
                                            input = emptyMap(),
                                            startTime = System.currentTimeMillis(),
                                        ),
                                    )
                                    tempToolParts.add(tempTp)
                                    _state.value = _state.value.copy(
                                        streamingToolParts = tempToolParts.toList(),
                                    )
                                }
                            },
                            onDone = { result ->
                                if (!completed) {
                                    completed = true
                                    buffer.flushFinal { content, reasoning ->
                                        if (content.isNotEmpty()) contentBuilder.append(content)
                                        if (reasoning.isNotEmpty()) reasoningBuilder.append(reasoning)
                                    }
                                    continuation.resumeWith(Result.success(StreamResult(
                                        content = result.content,
                                        reasoning = result.reasoning,
                                        toolCalls = result.toolCalls,
                                    )))
                                }
                            },
                            onError = { err ->
                                if (!completed) {
                                    completed = true
                                    buffer.cancel()
                                    val partial = contentBuilder.toString().trim()
                                    if (partial.isNotEmpty()) {
                                        continuation.resumeWith(Result.success(StreamResult(
                                            content = partial,
                                            reasoning = reasoningBuilder.toString(),
                                        )))
                                    } else {
                                        val classified = top.hsyscn.opedrgent.network.ErrorClassifier.classify(
                                            java.lang.Exception(err), null, null
                                        )
                                        DebugLog.e("streamLlm error classified: ${top.hsyscn.opedrgent.network.ErrorClassifier.formatForLog(classified)}")
                                        val enhancedError = when (classified.type) {
                                            top.hsyscn.opedrgent.network.ClassifiedErrorType.RATE_LIMIT -> "$err (请求过于频繁，请稍后重试)"
                                            top.hsyscn.opedrgent.network.ClassifiedErrorType.TIMEOUT -> "$err (请求超时，请检查网络)"
                                            top.hsyscn.opedrgent.network.ClassifiedErrorType.CAPTCHA -> "$err (触发了人机验证，可能需要更换API Key或节点)"
                                            top.hsyscn.opedrgent.network.ClassifiedErrorType.SSL_ERROR -> "$err (SSL证书错误)"
                                            top.hsyscn.opedrgent.network.ClassifiedErrorType.FORBIDDEN -> "$err (访问被拒绝，请检查API Key是否有效)"
                                            else -> err
                                        }
                                        continuation.resumeWith(Result.success(StreamResult(error = enhancedError)))
                                    }
                                }
                            },
                        )
                        currentCall = call
                    } catch (e: Exception) {
                        if (!completed) {
                            completed = true
                            buffer.cancel()
                            continuation.resumeWith(Result.success(StreamResult(error = e.message ?: "连接失败")))
                        }
                    }
                }
                continuation.invokeOnCancellation {
                    job.cancel()
                    currentCall?.cancel()
                    buffer.cancel()
                }
            } catch (e: Exception) {
                if (!completed) {
                    completed = true
                    buffer.cancel()
                    continuation.resumeWith(Result.success(StreamResult(error = e.message ?: "连接失败")))
                }
            }
        }
    }

    private suspend fun streamMultimodalLlm(
        config: top.hsyscn.opedrgent.settings.ApiConfig,
        system: String,
        messages: List<top.hsyscn.opedrgent.model.ChatMessage>,
        mapImages: List<String>,
        tools: List<top.hsyscn.opedrgent.network.ToolDefinition> = emptyList(),
        deepThinkingEnabled: Boolean = false,
    ): StreamResult = withContext(Dispatchers.IO) {
        val contentBuilder = StringBuilder()
        val reasoningBuilder = StringBuilder()
        val ctx = currentCoroutineContext()
        val buffer = StreamingBuffer(flushIntervalMs = 30L)

        val tempToolParts = mutableListOf<ToolPart>()
        val seenToolIdx = mutableSetOf<Int>()

        var lastFlushTime = 0L
        val throttleIntervalMs = 30L

        kotlinx.coroutines.suspendCancellableCoroutine<StreamResult> { continuation ->
            var completed = false
            try {
                val job = CoroutineScope(ctx).launch(Dispatchers.IO) {
                    try {
                        val call = llm.streamMultimodalChatCompletions(
                            config = config,
                            system = system,
                            messages = messages,
                            extraImages = mapImages,
                            tools = tools,
                            thinkingEnabled = deepThinkingEnabled,
                            onDelta = { delta ->
                                when (delta) {
                                    is top.hsyscn.opedrgent.network.StreamDelta.TextDelta -> {
                                        val t = top.hsyscn.opedrgent.utils.StringUtils.sanitizeJsonNull(delta.text)
                                        if (t.isNotEmpty()) {
                                            contentBuilder.append(t)
                                            val now = System.currentTimeMillis()
                                            if (now - lastFlushTime >= throttleIntervalMs) {
                                                lastFlushTime = now
                                                val textSnapshot = contentBuilder.toString()
                                                val reasonSnapshot = reasoningBuilder.toString()
                                                _state.value = _state.value.copy(
                                                    streamingText = textSnapshot,
                                                    streamingReasoning = reasonSnapshot,
                                                    isStreaming = true,
                                                )
                                            }
                                        }
                                    }
                                    is top.hsyscn.opedrgent.network.StreamDelta.ReasoningDelta -> {
                                        val t = top.hsyscn.opedrgent.utils.StringUtils.sanitizeJsonNull(delta.text)
                                        if (t.isNotEmpty()) {
                                            reasoningBuilder.append(t)
                                            val now = System.currentTimeMillis()
                                            if (now - lastFlushTime >= throttleIntervalMs) {
                                                lastFlushTime = now
                                                val textSnapshot = contentBuilder.toString()
                                                val reasonSnapshot = reasoningBuilder.toString()
                                                _state.value = _state.value.copy(
                                                    streamingText = textSnapshot,
                                                    streamingReasoning = reasonSnapshot,
                                                    isStreaming = true,
                                                )
                                            }
                                        }
                                    }
                                }
                            },
                            onToolCallDelta = { tc ->
                                buffer.appendToolCall(tc.nameDelta, tc.argsDelta)
                                if (tc.nameDelta.isNotEmpty() && tc.index !in seenToolIdx) {
                                    seenToolIdx.add(tc.index)
                                    val tempTp = ToolPart(
                                        tool = tc.nameDelta.trim(),
                                        state = ToolState(status = ToolStateType.PENDING, input = emptyMap(), startTime = System.currentTimeMillis()),
                                    )
                                    tempToolParts.add(tempTp)
                                    _state.value = _state.value.copy(streamingToolParts = tempToolParts.toList())
                                }
                            },
                            onDone = { result ->
                                if (!completed) {
                                    completed = true
                                    buffer.flushFinal { content, reasoning ->
                                        if (content.isNotEmpty()) contentBuilder.append(content)
                                        if (reasoning.isNotEmpty()) reasoningBuilder.append(reasoning)
                                    }
                                    continuation.resumeWith(Result.success(StreamResult(content = result.content, reasoning = result.reasoning, toolCalls = result.toolCalls)))
                                }
                            },
                            onError = { err ->
                                if (!completed) {
                                    completed = true
                                    buffer.cancel()
                                    val partial = contentBuilder.toString().trim()
                                    if (partial.isNotEmpty()) {
                                        continuation.resumeWith(Result.success(StreamResult(content = partial, reasoning = reasoningBuilder.toString())))
                                    } else {
                                        val classified = top.hsyscn.opedrgent.network.ErrorClassifier.classify(
                                            java.lang.Exception(err), null, null
                                        )
                                        DebugLog.e("streamMultimodalLlm error classified: ${top.hsyscn.opedrgent.network.ErrorClassifier.formatForLog(classified)}")
                                        val enhancedError = when (classified.type) {
                                            top.hsyscn.opedrgent.network.ClassifiedErrorType.RATE_LIMIT -> "$err (请求过于频繁，请稍后重试)"
                                            top.hsyscn.opedrgent.network.ClassifiedErrorType.TIMEOUT -> "$err (请求超时，请检查网络)"
                                            top.hsyscn.opedrgent.network.ClassifiedErrorType.CAPTCHA -> "$err (触发了人机验证，可能需要更换API Key或节点)"
                                            top.hsyscn.opedrgent.network.ClassifiedErrorType.SSL_ERROR -> "$err (SSL证书错误)"
                                            top.hsyscn.opedrgent.network.ClassifiedErrorType.FORBIDDEN -> "$err (访问被拒绝，请检查API Key是否有效)"
                                            else -> err
                                        }
                                        continuation.resumeWith(Result.success(StreamResult(error = enhancedError)))
                                    }
                                }
                            },
                        )
                        currentCall = call
                    } catch (e: Exception) {
                        if (!completed) {
                            completed = true
                            buffer.cancel()
                            continuation.resumeWith(Result.success(StreamResult(error = e.message ?: "连接失败")))
                        }
                    }
                }
                continuation.invokeOnCancellation {
                    job.cancel()
                    currentCall?.cancel()
                    buffer.cancel()
                }
            } catch (e: Exception) {
                if (!completed) {
                    completed = true
                    buffer.cancel()
                    continuation.resumeWith(Result.success(StreamResult(error = e.message ?: "连接失败")))
                }
            }
        }
    }

    private suspend fun tryFetchLocationMap(messages: List<top.hsyscn.opedrgent.model.ChatMessage>): List<String> {
        val lastUserMsg = messages.lastOrNull { it.role == top.hsyscn.opedrgent.model.Role.USER }?.content.orEmpty().lowercase()
        val locationKeywords = listOf("我在哪", "在哪里", "位置", "定位", "附近", "周围", "当前位置", "我在哪里")
        val isLocationQuery = locationKeywords.any { lastUserMsg.contains(it) }

        if (!isLocationQuery) return emptyList()

        val envInfo = top.hsyscn.opedrgent.env.EnvironmentProvider.getEnvironmentInfo(getApplication())
        val locStr = envInfo.location ?: return emptyList()

        val coordMatch = Regex("(-?\\d+\\.\\d+)\\s*[,，]\\s*(-?\\d+\\.\\d+)").find(locStr) ?: return emptyList()
        val lat = coordMatch.groupValues[1].toDoubleOrNull() ?: return emptyList()
        val lon = coordMatch.groupValues[2].toDoubleOrNull() ?: return emptyList()

        DebugLog.i("tryFetchLocationMap: location query detected, fetching map for $lat, $lon")

        val mapResult = MapTileFetcher.fetchMapImage(lat = lat, lon = lon, zoom = 16)
        return if (mapResult != null) {
            DebugLog.i("tryFetchLocationMap: map fetched ${mapResult.widthPx}x${mapResult.heightPx}px")
            listOf(mapResult.base64Png)
        } else {
            DebugLog.w("tryFetchLocationMap: map fetch failed")
            emptyList()
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Curator: 会话结束时非阻塞触发维护检查
        viewModelScope.launch(Dispatchers.IO) {
            curatorService.maybeRunCurator()
        }
        sttJob?.cancel()
        sttJob = null
        sproutJob?.cancel()
        sproutJob = null
        sherpaOnnxEngine?.close()
        sherpaOnnxEngine = null
        androidSpeechRecognizer?.close()
        androidSpeechRecognizer = null
        sttEngine?.close()
        sttEngine = null
        asrManager.close()
        sproutCache.clear()
        tts.shutdown()
        DebugLog.i("MainViewModel: onCleared - STT/发芽资源已释放")
    }

    fun saveSettings(baseUrl: String, apiKey: String, model: String): Boolean {
        return try {
            val key = apiKey.trim().takeIf { it.isNotEmpty() }
            apiSettings.save(baseUrl = baseUrl, apiKey = key, model = model)
            true
        } catch (e: Exception) {
            _state.value = _state.value.copy(error = e.message ?: "保存失败")
            false
        }
    }

    fun getBaseUrl(): String = apiSettings.getBaseUrl()
    fun getModel(): String = apiSettings.getModel()
    fun getApiKey(): String? = apiSettings.getApiKey()
    fun hasApiKey(): Boolean = apiSettings.hasApiKey()
    fun getMemory(): String = apiSettings.getMemory()
    fun saveMemory(memory: String) {
        apiSettings.saveMemory(memory)
    }

    fun isTtsEnabled(): Boolean = apiSettings.isTtsEnabled()
    fun isTtsAutoSpeak(): Boolean = apiSettings.isTtsAutoSpeak()
    fun isTtsMimoEnabled(): Boolean = apiSettings.isTtsMimoEnabled()
    fun getTtsMimoVoice(): String = apiSettings.getTtsMimoVoice()
    fun getTtsRate(): Float = apiSettings.getTtsRate()
    fun getTtsPitch(): Float = apiSettings.getTtsPitch()
    fun getTtsLocaleTag(): String = apiSettings.getTtsLocaleTag()
    fun isSttEnabled(): Boolean = apiSettings.isSttEnabled()
    fun getSttEngine(): String = apiSettings.getSttEngine()
    fun isTtsDownloadOnly(): Boolean = apiSettings.isTtsDownloadOnly()
    fun isBackgroundRunning(): Boolean = apiSettings.isBackgroundRunning()
    fun isLocationEnabled(): Boolean = apiSettings.isLocationEnabled()
    fun isDebugMode(): Boolean = apiSettings.isDebugMode()
    fun isDeepThinking(): Boolean = apiSettings.isDeepThinking()

    fun toggleDeepThinking(): Boolean {
        val next = !isDeepThinking()
        apiSettings.saveDeepThinking(next)
        _state.value = _state.value.copy(deepThinkingEnabled = next)
        return next
    }

    fun isDeepResearch(): Boolean = apiSettings.isDeepResearch()

    fun saveDeepResearch(enabled: Boolean) {
        apiSettings.saveDeepResearch(enabled)
        _state.value = _state.value.copy(deepResearchEnabled = enabled)
    }

    private suspend fun runLocalModel(sessionId: String) {
        val session = store.getSession(sessionId) ?: throw IllegalStateException("会话不存在")
        val system = buildSystemPrompt(session)
        val config = localEngine.currentConfig
        val modelInfo = localEngine.currentModelId?.let { AvailableLocalModels.findById(it) }
        val maxCtx = modelInfo?.maxContextLength ?: config?.maxContextLength ?: 4096

        val allMessages = session.messages

        val preCheck = top.hsyscn.opedrgent.utils.ContextCompressor.compress(allMessages, system, maxCtx)

        if (preCheck.isCritical) {
            DebugLog.w("runLocalModel: 上下文使用 ${String.format("%.0f%%", preCheck.usageRatio * 100)} ≥ 95%，强制压缩")
            _state.value = _state.value.copy(streamingPhase = "压缩上下文中…")
        }

        val compressed = if (preCheck.isCritical || preCheck.needsCompression) {
            top.hsyscn.opedrgent.utils.ContextCompressor.compress(allMessages, system, maxCtx, keepRecent = 3)
        } else {
            preCheck
        }

        val compressedSystem = if (compressed.summary != null) {
            "$system\n\n[历史摘要]\n${compressed.summary}"
        } else system
        val recentMessages = compressed.messages

        val prompt = buildString {
            appendLine(compressedSystem)
            appendLine()
            appendLine("--- 对话历史 ---")
            recentMessages.forEach { msg ->
                val roleLabel = when (msg.role) {
                    Role.USER -> "用户"
                    Role.ASSISTANT -> "助手"
                    else -> msg.role.name
                }
                appendLine("$roleLabel: ${msg.content}")
            }
            appendLine()
            appendLine("--- 请回复 ---")
        }

        _state.value = _state.value.copy(
            streamingPhase = "本地模型推理中…",
            contextTokenCount = compressed.tokenCount,
        )

        val mapImages = tryFetchLocationMap(recentMessages)
        val bitmaps = mutableListOf<android.graphics.Bitmap>()

        if (mapImages.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                for (b64 in mapImages) {
                    try {
                        val bytes = android.util.Base64.decode(b64.substringAfter(","), android.util.Base64.DEFAULT)
                        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bitmap != null) bitmaps.add(bitmap)
                    } catch (_: Exception) {}
                }
            }
        }

        try {

        var accumulatedText = ""
        var accumulatedReasoning = ""
        val enableThinking = config?.enableThinking == true

        localEngine.generateStream(
            sessionId = sessionId,
            prompt = prompt,
            images = bitmaps,
            enableThinking = enableThinking,
            onDelta = { chunk ->
                accumulatedText += chunk
                _state.value = _state.value.copy(
                    streamingText = accumulatedText,
                    streamingReasoning = accumulatedReasoning,
                    streamingPhase = "生成回答…",
                )
            },
            onThinkingDelta = if (enableThinking) {{ thinking ->
                accumulatedReasoning += thinking
                _state.value = _state.value.copy(
                    streamingText = accumulatedText,
                    streamingReasoning = accumulatedReasoning,
                    streamingPhase = "思考中…",
                )
            }} else null,
            onComplete = {
                DebugLog.i("runLocalModel: completed, text=${accumulatedText.length}, reasoning=${accumulatedReasoning.length}, ctx=${String.format("%.0f%%", compressed.usageRatio * 100)}")

                val localParts = buildList {
                    if (accumulatedReasoning.isNotBlank()) {
                        add(MessagePart.Reasoning(content = accumulatedReasoning))
                    }
                    if (accumulatedText.isNotBlank()) {
                        add(MessagePart.Text(content = accumulatedText))
                    }
                }
                store.addMessage(sessionId, Role.ASSISTANT, accumulatedText, parts = localParts)

                if (compressed.needsCompression && !preCheck.isCritical) {
                    DebugLog.i("runLocalModel: 上下文使用 ${String.format("%.0f%%", compressed.usageRatio * 100)} ≥ 90%，标记需压缩")
                    _state.value = _state.value.copy(contextCompressionEnabled = true)
                }

                setLoading(false)
            },
            onError = { error ->
                DebugLog.e("runLocalModel: error=$error")
                _state.value = _state.value.copy(
                    streamingText = if (accumulatedText.isNotBlank()) accumulatedText else "[本地模型错误] $error",
                    streamingPhase = "错误",
                )
                if (accumulatedText.isNotBlank()) {
                    val errorParts = buildList {
                        if (accumulatedReasoning.isNotBlank()) {
                            add(MessagePart.Reasoning(content = accumulatedReasoning))
                        }
                        add(MessagePart.Error(message = error))
                        add(MessagePart.Text(content = accumulatedText))
                    }
                    store.addMessage(sessionId, Role.ASSISTANT, accumulatedText, parts = errorParts)
                }
                setLoading(false)
            },
        )
        } finally {
            bitmaps.forEach { if (!it.isRecycled) it.recycle() }
        }
    }

    fun isLocalModelEnabled(): Boolean = apiSettings.isLocalModelEnabled()

    fun getLocalModelId(): String? = apiSettings.getLocalModelId()

    fun saveLocalModelEnabled(enabled: Boolean) {
        apiSettings.saveLocalModelEnabled(enabled)
    }

    fun saveLocalModelId(modelId: String?) {
        apiSettings.saveLocalModelId(modelId)
    }

    fun getLocalTemperature(): Float = apiSettings.getLocalTemperature()
    fun getLocalTopK(): Int = apiSettings.getLocalTopK()
    fun getLocalTopP(): Float = apiSettings.getLocalTopP()
    fun getMaxOutputTokens(): Int = apiSettings.getMaxOutputTokens()

    fun saveLocalParams(temperature: Float, topK: Int, topP: Float, maxTokens: Int) {
        apiSettings.saveLocalParams(temperature, topK, topP, maxTokens)
    }

    fun removeSource(sourceId: String) {
        val sessionId = _state.value.current?.id ?: return
        val next = store.removeSource(sessionId, sourceId) ?: return
        _state.value = _state.value.copy(current = next)
        refreshSessions()
    }

    fun refreshContextTokenCount() {
        val session = _state.value.current ?: return
        val system = buildSystemPrompt(session)
        val allMessages = session.messages
        val compressed = ContextCompressor.compress(allMessages, system, 16000)
        _state.value = _state.value.copy(contextTokenCount = compressed.tokenCount)
    }

    fun getDebugDump(): String {
        val session = _state.value.current ?: return "（无当前会话）"
        val app = getApplication<Application>()
        val includeLoc = apiSettings.isLocationEnabled()
        val cachedLoc = apiSettings.getLastLocation()
        val cachedDetail = apiSettings.getLastLocationDetail()
        val envInfo = EnvironmentProvider.getEnvironmentInfo(app, includeLocation = includeLoc, cachedLocation = cachedLoc, cachedLocationDetail = cachedDetail)
        val systemPrompt = PromptBuilder.buildSystemPrompt(apiSettings, session, memoryStore, envInfo)
        val state = _state.value

        return buildString {
            appendLine("═══════════════════════════════════════════")
            appendLine("  Opedrgent 调试转储")
            appendLine("  时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
            appendLine("═══════════════════════════════════════════")
            appendLine()
            appendLine("【UI状态 (UiState)】")
            appendLine("─".repeat(40))
            appendLine("loading: ${state.loading}")
            appendLine("isStreaming: ${state.isStreaming}")
            appendLine("streamingPhase: ${state.streamingPhase}")
            appendLine("streamingText长度: ${state.streamingText.length}")
            appendLine("streamingText: ${state.streamingText.take(200)}")
            appendLine("streamingReasoning长度: ${state.streamingReasoning.length}")
            appendLine("streamingReasoning: ${state.streamingReasoning.take(300)}")
            appendLine("streamingToolParts数量: ${state.streamingToolParts.size}")
            appendLine("contextTokenCount: ${state.contextTokenCount}")
            appendLine("contextCompressionEnabled: ${state.contextCompressionEnabled}")
            appendLine("activeQuestion: ${state.activeQuestion?.prompt?.take(100)}")
            appendLine()
            appendLine("【API配置】")
            appendLine("─".repeat(40))
            appendLine("BaseURL: ${apiSettings.getBaseUrl()}")
            appendLine("Model: ${apiSettings.getModel()}")
            appendLine("API密钥已配置: ${apiSettings.hasApiKey()}")
            appendLine("TTS启用: ${apiSettings.isTtsEnabled()}")
            appendLine("TTS自动播放: ${apiSettings.isTtsAutoSpeak()}")
            appendLine("TTS MiMo启用: ${apiSettings.isTtsMimoEnabled()}")
            appendLine("TTS MiMo音色: ${apiSettings.getTtsMimoVoice()}")
            appendLine("TTS Rate: ${apiSettings.getTtsRate()}")
            appendLine("TTS Pitch: ${apiSettings.getTtsPitch()}")
            appendLine("深度思考: ${apiSettings.isDeepThinking()}")
            appendLine("深度研究: ${apiSettings.isDeepResearch()}")
            appendLine()
            appendLine("【系统提示词 (System Prompt)】")
            appendLine("─".repeat(40))
            appendLine(systemPrompt)
            appendLine()
            appendLine("【会话元信息】")
            appendLine("─".repeat(40))
            appendLine("会话ID: ${session.id}")
            appendLine("标题: ${session.title}")
            appendLine("消息数: ${session.messages.size}")
            appendLine("来源数: ${session.sources.size}")
            appendLine()
            appendLine("【环境信息】")
            appendLine("─".repeat(40))
            appendLine("日期时间: ${envInfo.dateTime}")
            appendLine("星期: ${envInfo.dayOfWeek}")
            appendLine("时区: ${envInfo.timeZone}")
            appendLine("语言: ${envInfo.language}")
            appendLine("平台: ${envInfo.platform}")
            appendLine("系统版本: ${envInfo.osVersion}")
            appendLine("位置: ${envInfo.location ?: "未获取"}")
            appendLine("位置详情: ${envInfo.locationDetail ?: "无"}")
            appendLine()
            appendLine("【对话历史 (共 ${session.messages.size} 条)】")
            appendLine("─".repeat(40))
            session.messages.forEachIndexed { idx, msg ->
                appendLine()
                appendLine("【$idx】${msg.role.name} | tools=${msg.toolParts.size} reasoning=${msg.reasoningParts.size}")
                if (msg.reasoningParts.isNotEmpty()) {
                    appendLine("  [思考]: ${msg.reasoningParts.joinToString("\n         ") { it.text.take(300) }}")
                }
                if (msg.toolParts.isNotEmpty()) {
                    msg.toolParts.forEach { tp ->
                        val output = tp.state.output?.take(500) ?: "(null)"
                        val inputStr = if (tp.state.input.isEmpty()) "(空)" else tp.state.input.entries.joinToString(", ") { "${it.key}=${it.value.take(100)}" }
                        appendLine("  [工具调用] ${tp.tool} → ${tp.state.status.name}")
                        appendLine("    输入: $inputStr")
                        appendLine("    输出: $output")
                    }
                }
                appendLine("  [内容]: ${msg.content.take(800)}")
            }
            appendLine()
            if (state.streamingToolParts.isNotEmpty()) {
                appendLine("【当前流式工具调用 (${state.streamingToolParts.size} 个)】")
                appendLine("─".repeat(40))
                state.streamingToolParts.forEachIndexed { idx, tp ->
                    val inputStr = if (tp.state.input.isEmpty()) "(空)" else tp.state.input.entries.joinToString(", ") { "${it.key}=${it.value.take(100)}" }
                    appendLine("  [$idx] ${tp.tool} → ${tp.state.status.name}")
                    appendLine("      输入: $inputStr")
                }
            }
            appendLine()
            appendLine("【来源 (共 ${session.sources.size} 条)】")
            appendLine("─".repeat(40))
            session.sources.forEachIndexed { idx, s ->
                appendLine("  $idx. ${s.title ?: s.url ?: "无标题"} | ${s.url ?: ""}")
                appendLine("     内容长度: ${s.content.length} chars")
            }
            appendLine()
            appendLine("【工具定义 (${agentTools.size} 个)】")
            appendLine("─".repeat(40))
            agentTools.forEach { t ->
                appendLine("  - ${t.name}: ${t.description}")
            }
            appendLine()
            appendLine("═══════════════════════════════════════════")
            appendLine("  调试转储结束")
            appendLine("═══════════════════════════════════════════")
        }
    }

    fun toggleContextCompression() {
        val next = !_state.value.contextCompressionEnabled
        _state.value = _state.value.copy(contextCompressionEnabled = next)
    }

    fun requestLocationPermission(onMissing: () -> Unit) {
        val app = getApplication<Application>()
        val hasFine = androidx.core.content.ContextCompat.checkSelfPermission(
            app, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasCoarse = androidx.core.content.ContextCompat.checkSelfPermission(
            app, android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (hasFine || hasCoarse) {
            saveLocationEnabled(true)
            refreshLocation()
        } else {
            onMissing()
        }
    }

    fun stopGeneration() {
        cancelled.set(true)
        currentCall?.cancel()
        currentRunJob?.cancel()
        currentCall = null
        currentRunJob = null
        // 保存已生成的部分文本到会话中，避免内容丢失
        savePartialStreamingContent()
        _state.value = _state.value.copy(
            isStreaming = false,
            streamingText = "",
            streamingReasoning = "",
            streamingToolParts = emptyList(),
            activeQuestion = null,
            loading = false,
            streamingSessionId = null,
        )
    }

    /**
     * 将当前正在流式生成的部分内容保存为一条助手消息，
     * 避免用户取消后已输出的文本丢失。
     */
    private fun savePartialStreamingContent() {
        val s = _state.value
        val text = s.streamingText
        val reasoning = s.streamingReasoning
        val toolParts = s.streamingToolParts
        val sessionId = s.streamingSessionId ?: s.current?.id ?: return
        if (text.isBlank() && reasoning.isBlank()) return
        val reasoningParts = if (reasoning.isNotBlank()) {
            listOf(ReasoningPart(text = reasoning, endTime = System.currentTimeMillis()))
        } else emptyList()
        // 构建结构化 parts（取消时的部分内容保存）
        val messageParts = buildList {
            if (reasoning.isNotBlank()) {
                add(MessagePart.Reasoning(content = reasoning))
            }
            toolParts.forEach { tp ->
                add(MessagePart.ToolCall(
                    toolName = tp.tool,
                    callId = tp.id,
                    state = tp.state,
                    input = tp.state.input,
                    output = tp.state.output,
                ))
            }
            if (text.isNotBlank()) {
                add(MessagePart.Text(content = text))
            }
        }
        store.addMessage(
            sessionId, Role.ASSISTANT, text.ifBlank { "（已取消，无内容）" },
            toolParts = toolParts,
            reasoningParts = reasoningParts,
            parts = messageParts,
        )
        _state.value = _state.value.copy(current = store.getSession(sessionId))
        refreshSessions()
    }

    /** 撤回/删除指定消息 */
    fun deleteMessage(messageId: String) {
        val sessionId = _state.value.current?.id ?: return
        val session = store.getSession(sessionId) ?: return
        val updatedMessages = session.messages.filter { it.id != messageId }
        val updatedSession = session.copy(messages = updatedMessages, updatedAt = System.currentTimeMillis())
        store.updateSession(updatedSession)
        _state.value = _state.value.copy(current = updatedSession)
        refreshSessions()
    }

    fun answerQuestion(answer: String) {
        val q = _state.value.activeQuestion ?: return
        if (answer.isBlank()) return
        _state.value = _state.value.copy(activeQuestion = null)
        val sessionId = _state.value.current?.id ?: return
        val qContent = buildString {
            appendLine("用户回答了问题：${q.prompt}")
            appendLine("回答：$answer")
        }
        store.addMessage(sessionId, Role.USER, qContent)
        _state.value = _state.value.copy(current = store.getSession(sessionId))
        runModel(sessionId)
    }

    fun dismissQuestion() {
        _state.value = _state.value.copy(activeQuestion = null)
    }

    fun saveTts(enabled: Boolean, autoSpeak: Boolean, rate: Float, pitch: Float, localeTag: String, mimoEnabled: Boolean, mimoVoice: String, downloadOnly: Boolean = false) {
        apiSettings.saveTts(enabled = enabled, autoSpeak = autoSpeak, rate = rate, pitch = pitch, localeTag = localeTag, mimoEnabled = mimoEnabled, mimoVoice = mimoVoice, downloadOnly = downloadOnly)
    }

    fun saveSttEnabled(enabled: Boolean) {
        apiSettings.saveSttEnabled(enabled)
    }

    fun saveSttEngine(engine: String) {
        apiSettings.saveSttEngine(engine)
    }

    fun saveBackgroundRunning(enabled: Boolean) {
        apiSettings.saveBackgroundRunning(enabled)
        if (enabled) {
            top.hsyscn.opedrgent.service.KeepAliveService.start(getApplication())
        } else {
            top.hsyscn.opedrgent.service.KeepAliveService.stop(getApplication())
        }
    }

    fun saveLocationEnabled(enabled: Boolean) {
        apiSettings.saveLocationEnabled(enabled)
        if (enabled) {
            refreshLocation()
        }
    }

    fun clearLocationCache() {
        apiSettings.clearLocationCache()
        DebugLog.i("MainViewModel: location cache cleared")
    }

    fun saveDebugMode(enabled: Boolean) {
        apiSettings.saveDebugMode(enabled)
        DebugLog.enabled = enabled
        _state.value = _state.value.copy(debugModeEnabled = enabled)
    }

    fun saveDeepThinking(enabled: Boolean) {
        apiSettings.saveDeepThinking(enabled)
    }

    fun isProviderWebSearchEnabled(): Boolean = apiSettings.isProviderWebSearchEnabled()

    fun saveProviderWebSearchEnabled(enabled: Boolean) {
        apiSettings.saveProviderWebSearchEnabled(enabled)
    }

    fun getSearchProviderOrder(): String = apiSettings.getSearchProviderOrder()
    fun getJinaApiKey(): String? = apiSettings.getJinaApiKey()
    fun getSearxngBaseUrl(): String? = apiSettings.getSearxngBaseUrl()
    fun getBraveApiKey(): String? = apiSettings.getBraveApiKey()
    fun getTavilyApiKey(): String? = apiSettings.getTavilyApiKey()
    fun getFirecrawlApiKey(): String? = apiSettings.getFirecrawlApiKey()

    fun saveSearchProviderOrder(order: String) {
        apiSettings.saveSearchProviderOrder(order)
    }

    fun saveJinaApiKey(key: String?) {
        apiSettings.saveJinaApiKey(key)
    }

    fun saveSearxngBaseUrl(url: String?) {
        apiSettings.saveSearxngBaseUrl(url)
    }

    fun saveBraveApiKey(key: String?) {
        apiSettings.saveBraveApiKey(key)
    }

    fun saveTavilyApiKey(key: String?) {
        apiSettings.saveTavilyApiKey(key)
    }

    fun saveFirecrawlApiKey(key: String?) {
        apiSettings.saveFirecrawlApiKey(key)
    }

    fun refreshLocation() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                val loc = withContext(Dispatchers.IO) { EnvironmentProvider.getCurrentLocation(app) }
                if (loc != null) {
                    val geo = withContext(Dispatchers.IO) {
                        EnvironmentProvider.reverseGeocode(loc.first, loc.second)
                    }
                    val display = geo?.displayName ?: "${loc.first}, ${loc.second}"
                    apiSettings.saveLastLocation(display)
                    geo?.detail?.let { apiSettings.saveLastLocationDetail(it) }
                }
            } catch (_: Exception) { }
        }
    }

    fun logDebug(msg: String) {
        if (apiSettings.isDebugMode()) {
            android.util.Log.d("Opedrgent", msg)
        }
    }

    fun clearApiKey() {
        apiSettings.clearApiKey()
    }

    fun toggleSpeak(text: String) {
        if (!apiSettings.isTtsEnabled()) return
        if (text.isBlank()) return
        when {
            tts.isCurrentlySpeaking() && !tts.isCurrentlyPaused() -> {
                tts.pause()
                _state.value = _state.value.copy(isSpeaking = false)
            }
            tts.isCurrentlyPaused() -> {
                tts.resume()
                _state.value = _state.value.copy(isSpeaking = true)
            }
            else -> {
                tts.speak(
                    text = text,
                    localeTag = apiSettings.getTtsLocaleTag(),
                    rate = apiSettings.getTtsRate(),
                    pitch = apiSettings.getTtsPitch(),
                    mimoVoice = apiSettings.getTtsMimoVoice(),
                )
                _state.value = _state.value.copy(isSpeaking = true)
            }
        }
    }

    fun stopSpeak() {
        tts.stop()
        _state.value = _state.value.copy(isSpeaking = false)
    }

    fun suggestEvolution() {
        val sessionId = _state.value.current?.id ?: return
        viewModelScope.launch {
            setLoading(true)
            try {
                val config = apiSettings.getApiConfig() ?: throw IllegalStateException("请先在设置里填写 API Key")
                val session = store.getSession(sessionId) ?: throw IllegalStateException("会话不存在")
                val system = buildSystemPrompt(session)
                val instruction = """
请基于当前对话进行自我反思，输出 Markdown 格式。写出1-3条值得存入长期记忆的信息和可复用技能建议。
""".trimIndent()
                val assistant = withContext(Dispatchers.IO) {
                    llm.chatCompletions(
                        config = config,
                        system = system,
                        messages = listOf(ChatMessage(role = Role.USER, content = instruction, createdAt = System.currentTimeMillis())),
                    )
                }
                val parsed = parseEvolutionSuggestion(assistant)
                _state.value = _state.value.copy(evolutionSuggestion = parsed, error = null)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "进化失败")
            } finally {
                setLoading(false)
            }
        }
    }

    fun generateSessionNotes() {
        val sessionId = _state.value.current?.id ?: return
        viewModelScope.launch {
            setLoading(true)
            try {
                val config = apiSettings.getApiConfig() ?: throw IllegalStateException("请先在设置里填写 API Key")
                val session = store.getSession(sessionId) ?: throw IllegalStateException("会话不存在")
                val system = buildSystemPrompt(session)
                val prompt = """
输出一份面向人类的阅读笔记，请基于当前对话生成：研究主题、关键发现（标注[S1]/[S2]）、待解决问题、下一步建议、来源索引。
""".trimIndent()
                val assistant = withContext(Dispatchers.IO) {
                    llm.chatCompletions(
                        config = config,
                        system = system,
                        messages = listOf(ChatMessage(role = Role.USER, content = prompt, createdAt = System.currentTimeMillis())),
                    )
                }.trim()
                store.setNotes(sessionId, assistant)
                store.addArtifact(sessionId, ArtifactKind.NOTES, assistant)
                _state.value = _state.value.copy(current = store.getSession(sessionId), error = null)
                refreshSessions()
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "整理失败")
            } finally {
                setLoading(false)
            }
        }
    }

    fun setSourceIncluded(sourceId: String, included: Boolean) {
        val sessionId = _state.value.current?.id ?: return
        val next = store.setSourceIncluded(sessionId, sourceId, included) ?: return
        _state.value = _state.value.copy(current = next)
        refreshSessions()
    }

    fun updateNotesManually(notes: String) {
        val sessionId = _state.value.current?.id ?: return
        val next = store.setNotes(sessionId, notes) ?: return
        _state.value = _state.value.copy(current = next)
        refreshSessions()
    }

    fun dismissEvolution() {
        _state.value = _state.value.copy(evolutionSuggestion = null)
    }

    fun acceptEvolutionMemory() {
        val s = _state.value.evolutionSuggestion ?: return
        if (s.memory.isNotBlank()) {
            addMemory(title = "进化记忆", content = s.memory, type = MemoryType.FEEDBACK)
        }
        dismissEvolution()
    }

    fun acceptEvolutionSkill() {
        val s = _state.value.evolutionSuggestion ?: return
        addOrUpdateSkill(id = null, name = s.skillName, prompt = s.skillPrompt)
        dismissEvolution()
    }

    fun suggestAutomation() {
        val sessionId = _state.value.current?.id ?: return
        viewModelScope.launch {
            setLoading(true)
            try {
                val config = apiSettings.getApiConfig() ?: throw IllegalStateException("请先在设置里填写 API Key")
                val session = store.getSession(sessionId) ?: throw IllegalStateException("会话不存在")
                val system = buildSystemPrompt(session)
                val instruction = """
请基于当前研究进度，建议一个可定时执行的任务。输出格式：
- 名称：（短，<=15字）
- 周期：（分钟数，>=15）
- 类型：HEARTBEAT_NOTES 或 RUN_PROMPT
- Prompt：（若是 RUN_PROMPT，写出定时执行的提示词；HEARTBEAT_NOTES 可省略）
""".trimIndent()
                val assistant = withContext(Dispatchers.IO) {
                    llm.chatCompletions(
                        config = config,
                        system = system,
                        messages = listOf(ChatMessage(role = Role.USER, content = instruction, createdAt = System.currentTimeMillis())),
                    )
                }
                val parsed = parseAutomationSuggestion(assistant)
                _state.value = _state.value.copy(automationSuggestion = parsed, error = null)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "自动化建议失败")
            } finally {
                setLoading(false)
            }
        }
    }

    fun dismissAutomationSuggestion() {
        _state.value = _state.value.copy(automationSuggestion = null)
    }

    fun acceptAutomationSuggestion() {
        val s = _state.value.automationSuggestion ?: return
        val sessionId = _state.value.current?.id
        when (s.kind) {
            AutomationKind.HEARTBEAT_NOTES -> {
                automationStore.createHeartbeat(
                    name = s.name,
                    intervalMinutes = s.intervalMinutes,
                    targetSessionId = sessionId,
                )
            }
            AutomationKind.RUN_PROMPT -> {
                val p = s.prompt?.trim().orEmpty()
                if (p.isBlank()) {
                    _state.value = _state.value.copy(error = "自动化提示词为空")
                    return
                }
                automationStore.createPrompt(
                    name = s.name,
                    intervalMinutes = s.intervalMinutes,
                    targetSessionId = sessionId,
                    prompt = p,
                )
            }
        }
        dismissAutomationSuggestion()
    }

    private fun parseAutomationSuggestion(raw: String): AutomationSuggestion {
        val t = raw.trim()
        val jsonStart = t.indexOf('{')
        val jsonEnd = t.lastIndexOf('}')
        val jsonText = if (jsonStart >= 0 && jsonEnd > jsonStart) t.substring(jsonStart, jsonEnd + 1) else null
        val obj = jsonText?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (obj != null) {
            val name = obj.optString("name").trim().ifEmpty { "自动化" }
            val interval = obj.optLong("interval_minutes", 360L).coerceAtLeast(15L)
            val kind = runCatching { AutomationKind.valueOf(obj.optString("kind").trim()) }.getOrNull() ?: AutomationKind.HEARTBEAT_NOTES
            val prompt = obj.optString("prompt").trim().takeIf { it.isNotBlank() }
            return AutomationSuggestion(name = name, intervalMinutes = interval, kind = kind, prompt = prompt, raw = raw)
        }
        val name = Regex("名称[：:]\\s*(.+)").find(t)?.groupValues?.get(1)?.trim()?.take(15) ?: "自动化"
        val interval = Regex("周期[：:]\\s*(\\d+)").find(t)?.groupValues?.get(1)?.toLongOrNull()?.coerceAtLeast(15) ?: 360L
        val kindStr = Regex("类型[：:]\\s*(\\w+)").find(t)?.groupValues?.get(1)?.trim() ?: "HEARTBEAT_NOTES"
        val kind = runCatching { AutomationKind.valueOf(kindStr) }.getOrNull() ?: AutomationKind.HEARTBEAT_NOTES
        val prompt = if (kind == AutomationKind.RUN_PROMPT) {
            Regex("Prompt[：:]\\s*([\\s\\S]+?)(?=\\n-|\\n$|$)").find(t)?.groupValues?.get(1)?.trim()
        } else null
        return AutomationSuggestion(name = name, intervalMinutes = interval, kind = kind, prompt = prompt, raw = raw)
    }

    fun suggestCalendar() {
        val sessionId = _state.value.current?.id ?: return
        viewModelScope.launch {
            setLoading(true)
            try {
                val config = apiSettings.getApiConfig() ?: throw IllegalStateException("请先在设置里填写 API Key")
                val session = store.getSession(sessionId) ?: throw IllegalStateException("会话不存在")
                val system = buildSystemPrompt(session)
                val instruction = """
输出 JSON 数组，不要包含多余文本。
每个元素字段：
- title: 标题
- start: ISO8601 时间（带时区，例如 2026-05-01T09:00:00+08:00）
- end: ISO8601 时间（带时区）
- description: 可选
- location: 可选

从当前对话里提炼用户需要记录的待办/会议/截止时间，最多 5 条；如果没有明确时间，就不要输出。
""".trimIndent()
                val assistant = withContext(Dispatchers.IO) {
                    llm.chatCompletions(
                        config = config,
                        system = system,
                        messages = listOf(ChatMessage(role = Role.USER, content = instruction, createdAt = System.currentTimeMillis())),
                    )
                }
                val events = parseCalendarEvents(assistant)
                if (events.isEmpty()) {
                    _state.value = _state.value.copy(error = "未提取到明确时间的日程", calendarSuggestion = null)
                } else {
                    _state.value = _state.value.copy(calendarSuggestion = CalendarSuggestion(events = events, raw = assistant), error = null)
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "日程建议失败")
            } finally {
                setLoading(false)
            }
        }
    }

    fun dismissCalendarSuggestion() {
        _state.value = _state.value.copy(calendarSuggestion = null)
    }

    fun exportCalendarIcs(events: List<CalendarEventDraft>): File {
        val app = getApplication<Application>()
        val exportsDir = File(app.filesDir, "exports").apply { mkdirs() }
        val file = File(exportsDir, "Calendar_${System.currentTimeMillis()}.ics")
        file.writeText(IcsWriter.toIcs(events), Charsets.UTF_8)
        return file
    }

    private fun parseCalendarEvents(raw: String): List<CalendarEventDraft> {
        val t = raw.trim()
        val jsonStart = t.indexOf('[')
        val jsonEnd = t.lastIndexOf(']')
        val jsonText = if (jsonStart >= 0 && jsonEnd > jsonStart) t.substring(jsonStart, jsonEnd + 1) else t
        val arr = runCatching { JSONArray(jsonText) }.getOrNull() ?: return emptyList()
        val out = ArrayList<CalendarEventDraft>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title").trim()
            val startIso = o.optString("start").trim()
            val endIso = o.optString("end").trim()
            if (title.isBlank() || startIso.isBlank() || endIso.isBlank()) continue
            val start = runCatching { ZonedDateTime.parse(startIso, DateTimeFormatter.ISO_OFFSET_DATE_TIME) }.getOrNull() ?: continue
            val end = runCatching { ZonedDateTime.parse(endIso, DateTimeFormatter.ISO_OFFSET_DATE_TIME) }.getOrNull() ?: continue
            val startMs = start.toInstant().toEpochMilli()
            val endMs = end.toInstant().toEpochMilli()
            if (endMs <= startMs) continue
            out.add(
                CalendarEventDraft(
                    title = title,
                    startEpochMs = startMs,
                    endEpochMs = endMs,
                    description = o.optString("description").trim().takeIf { it.isNotBlank() },
                    location = o.optString("location").trim().takeIf { it.isNotBlank() },
                ),
            )
        }
        return out.take(5)
    }

    private fun parseEvolutionSuggestion(raw: String): EvolutionSuggestion {
        val t = raw.trim()
        val jsonStart = t.indexOf('{')
        val jsonEnd = t.lastIndexOf('}')
        val jsonText = if (jsonStart >= 0 && jsonEnd > jsonStart) t.substring(jsonStart, jsonEnd + 1) else null
        val obj = jsonText?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (obj != null) {
            val memory = obj.optString("memory").trim()
            val name = obj.optString("skill_name").trim().ifEmpty { "新技能" }
            val prompt = obj.optString("skill_prompt").trim()
            return EvolutionSuggestion(memory = memory, skillName = name.take(10), skillPrompt = prompt, raw = raw)
        }
        val memory = Regex("## 值得记住的信息[\\s\\S]*?(?=## |$)").find(t)?.value
            ?.removePrefix("## 值得记住的信息")?.trim() ?: ""
        val skillName = Regex("技能名[：:]\\s*(.+)").find(t)?.groupValues?.get(1)?.trim()?.take(10) ?: "新技能"
        val skillPrompt = Regex("Prompt[：:]\\s*([\\s\\S]+?)(?=\\n-|\\n##|$)").find(t)?.groupValues?.get(1)?.trim() ?: ""
        if (memory.isNotBlank() || skillPrompt.isNotBlank()) {
            return EvolutionSuggestion(memory = memory, skillName = skillName, skillPrompt = skillPrompt, raw = raw)
        }
        return EvolutionSuggestion(memory = "", skillName = "新技能", skillPrompt = "", raw = raw)
    }

    suspend fun exportMarkdown(): File? = withContext(Dispatchers.IO) {
        val session = _state.value.current ?: return@withContext null
        val app = getApplication<Application>()
        val exportsDir = File(app.filesDir, "exports").apply { mkdirs() }
        val safeTitle = session.title.replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fa5._-]+"), "_").take(60)
        val file = File(exportsDir, "${safeTitle}_${System.currentTimeMillis()}.md")
        file.writeText(buildMarkdown(session), Charsets.UTF_8)
        file
    }

    suspend fun exportMemoryMarkdown(): File = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val exportsDir = File(app.filesDir, "exports").apply { mkdirs() }
        val file = File(exportsDir, "Memory_${System.currentTimeMillis()}.md")
        val entries = memoryStore.list()
        val md = buildString {
            appendLine("# Memory")
            appendLine()
            if (entries.isEmpty()) {
                appendLine("（空）")
            } else {
                entries.forEach { e ->
                    if (e.title.isNotBlank()) appendLine("## ${e.title}")
                    appendLine(e.content)
                    appendLine()
                }
            }
        }
        file.writeText(md, Charsets.UTF_8)
        file
    }

    suspend fun exportChatMarkdown(): File? = withContext(Dispatchers.IO) {
        val session = _state.value.current ?: return@withContext null
        val app = getApplication<Application>()
        val exportsDir = File(app.filesDir, "exports").apply { mkdirs() }
        val safeTitle = session.title.replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fa5._-]+"), "_").take(60)
        val file = File(exportsDir, "${safeTitle}_chat_${System.currentTimeMillis()}.md")
        val md = buildString {
            appendLine("# ${session.title}")
            appendLine()
            appendLine("## Conversation")
            appendLine()
            session.messages.forEach { m ->
                val who = when (m.role) {
                    Role.USER -> "User"
                    Role.ASSISTANT -> "Assistant"
                    Role.SYSTEM -> "System"
                }
                appendLine("### $who")
                appendLine()
                appendLine(m.content.trim())
                appendLine()
            }
        }
        file.writeText(md.trim() + "\n", Charsets.UTF_8)
        file
    }

    suspend fun exportContextMarkdown(): File? = withContext(Dispatchers.IO) {
        val session = _state.value.current ?: return@withContext null
        val app = getApplication<Application>()
        val exportsDir = File(app.filesDir, "exports").apply { mkdirs() }
        val safeTitle = session.title.replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fa5._-]+"), "_").take(60)
        val file = File(exportsDir, "${safeTitle}_context_${System.currentTimeMillis()}.md")
        val md = buildString {
            appendLine("# Context Pack")
            appendLine()
            appendLine("## Memory")
            appendLine()
            val memBlock = memoryStore.getMemoryBlock()
            appendLine(memBlock.ifBlank { "（空）" })
            appendLine()
            appendLine("## Session Notes")
            appendLine()
            appendLine(session.notes.ifBlank { "（空）" })
            appendLine()
            appendLine("## Sources")
            appendLine()
            session.sources.forEachIndexed { idx, s ->
                val tag = "S${idx + 1}"
                val title = s.title ?: s.url ?: "Source"
                val line = if (!s.url.isNullOrBlank()) "- [$tag] $title - ${s.url}" else "- [$tag] $title"
                appendLine(line)
            }
            appendLine()
            appendLine("## Recent Conversation (last 20)")
            appendLine()
            session.messages.takeLast(20).forEach { m ->
                val who = when (m.role) {
                    Role.USER -> "User"
                    Role.ASSISTANT -> "Assistant"
                    Role.SYSTEM -> "System"
                }
                appendLine("### $who")
                appendLine()
                appendLine(m.content.trim())
                appendLine()
            }
            appendLine("## System Prompt (generated)")
            appendLine()
            appendLine("```")
            appendLine(buildSystemPrompt(session))
            appendLine("```")
        }
        file.writeText(md.trim() + "\n", Charsets.UTF_8)
        file
    }

    private fun buildSystemPrompt(session: ResearchSession): String {
        val app = getApplication<Application>()
        val includeLoc = apiSettings.isLocationEnabled()
        val cachedLoc = apiSettings.getLastLocation()
        val cachedDetail = apiSettings.getLastLocationDetail()
        val envInfo = EnvironmentProvider.getEnvironmentInfo(app, includeLocation = includeLoc, cachedLocation = cachedLoc, cachedLocationDetail = cachedDetail)

        val modelInfo = ModelInfo(
            modelId = apiSettings.getModel(),
            provider = inferProviderFromUrl(apiSettings.getBaseUrl())
        )

        val platformCtx = PlatformContext(
            platform = Platform.ANDROID,
            hasTTS = apiSettings.isTtsEnabled(),
            hasVoiceInput = apiSettings.isSttEnabled(),
            hasLocation = apiSettings.isLocationEnabled(),
            hasBrowser = true,
            hasCalendar = true
        )

        return PromptBuilder.buildSystemPrompt(apiSettings, session, memoryStore, envInfo, modelInfo, platformCtx)
    }

    private fun inferProviderFromUrl(baseUrl: String): String {
        return when {
            baseUrl.contains("openai.com", ignoreCase = true) -> "openai"
            baseUrl.contains("anthropic.com", ignoreCase = true) -> "anthropic"
            baseUrl.contains("dashscope", ignoreCase = true) || baseUrl.contains("aliyun", ignoreCase = true) -> "alibaba"
            baseUrl.contains("deepseek", ignoreCase = true) -> "deepseek"
            baseUrl.contains("zhipu", ignoreCase = true) || baseUrl.contains("bigmodel", ignoreCase = true) -> "zhipu"
            baseUrl.contains("localhost", ignoreCase = true) || baseUrl.contains("127.0.0.1") -> "local"
            else -> "custom"
        }
    }

    private fun buildMarkdown(session: ResearchSession): String {
        val sb = StringBuilder()
        sb.appendLine("# ${session.title}")
        sb.appendLine()
        if (session.notes.isNotBlank()) {
            sb.appendLine("## Notes")
            sb.appendLine()
            sb.appendLine(session.notes.trim())
            sb.appendLine()
        }
        if (session.sources.isNotEmpty()) {
            sb.appendLine("## Sources")
            session.sources.forEachIndexed { idx, s ->
                val tag = "S${idx + 1}"
                val title = s.title ?: s.url ?: "Source"
                val line = if (!s.url.isNullOrBlank()) "- [$tag] $title - ${s.url}" else "- [$tag] $title"
                sb.appendLine(line)
            }
            sb.appendLine()
        }
        if (session.artifacts.isNotEmpty()) {
            sb.appendLine("## Artifacts")
            session.artifacts.forEach { a ->
                val title = when (a.kind) {
                    ArtifactKind.SUMMARY -> "Summary"
                    ArtifactKind.REPORT -> "Report"
                    ArtifactKind.NOTES -> "Notes"
                }
                sb.appendLine("### $title")
                sb.appendLine()
                sb.appendLine(a.content.trim())
                sb.appendLine()
            }
        }
        if (session.messages.isNotEmpty()) {
            sb.appendLine("## Conversation")
            session.messages.forEach { m ->
                val who = when (m.role) {
                    Role.USER -> "User"
                    Role.ASSISTANT -> "Assistant"
                    Role.SYSTEM -> "System"
                }
                sb.appendLine("### $who")
                sb.appendLine()
                sb.appendLine(m.content.trim())
                sb.appendLine()
            }
        }
        return sb.toString().trim() + "\n"
    }

    private fun truncate(s: String, max: Int): String {
        val t = s.trim()
        if (t.length <= max) return t
        return t.take(max) + "…"
    }

    private suspend fun executeWithPermission(
        toolName: String,
        desc: String,
        params: String,
        actualExecute: suspend () -> ToolResult,
    ): ToolResult? {
        val request = ToolPermissionRequest(
            toolName = toolName,
            toolDescription = desc,
            paramsJson = params,
        )
        _toolPermissionRequest.emit(request)

        try {
            val response = _toolPermissionResponse.first { it.requestId == request.requestId }
            return if (response.allowed) {
                actualExecute()
            } else {
                null
            }
        } finally {
            _toolPermissionRequest.emit(null)
        }
    }

    private fun setLoading(v: Boolean) {
        _state.value = _state.value.copy(loading = v)
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    fun consumeNavigation() {
        _state.value = _state.value.copy(navigateToSessionId = null)
    }

    fun consumeOpenWebUrl() {
        _state.value = _state.value.copy(openWebUrl = null)
    }

    fun openWeb(url: String) {
        val u = url.trim()
        if (u.isNotEmpty()) {
            _state.value = _state.value.copy(openWebUrl = u)
        }
    }

    fun consumeOpenBrowserUrl() {
        _state.value = _state.value.copy(openBrowserUrl = null)
    }

    fun openBrowser(url: String) {
        val u = url.trim()
        if (u.isNotEmpty()) {
            _state.value = _state.value.copy(openBrowserUrl = u)
        }
    }

    fun handleIncomingShare(text: String) {
        behaviorTracker.track(BehaviorEvent.FILE_IMPORTED, mapOf("source" to "share"))
        val raw = text.trim()
        if (raw.isEmpty()) return
        val url = raw.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        val existingSessionId = _state.value.current?.id ?: apiSettings.getLastSessionId()
        val sessionId = existingSessionId ?: store.createSession("剪藏").id
        openSession(sessionId)
        _state.value = _state.value.copy(navigateToSessionId = sessionId)
        if (url != null) {
            addUrlSource(url)
        } else {
            addTextSource(title = "剪藏", text = raw)
        }
    }

    fun webResearch(
        query: String,
        mode: WebResearchMode = WebResearchMode.AUTO,
        llmDecides: Boolean = false,
        unattended: Boolean = true,
        allowBrowser: Boolean = false,
    ) {
        val sessionId = _state.value.current?.id ?: return
        val q = query.trim()
        if (q.isEmpty()) return
        viewModelScope.launch {
            setLoading(true)
            try {
                val config = apiSettings.getApiConfig() ?: throw IllegalStateException("请先在设置里填写 API Key")
                val resolvedMode = if (llmDecides) decideWebResearchMode(config, q) else mode
                val outcome = withContext(Dispatchers.IO) {
                    webResearchRouter.run(
                        WebResearchRequest(
                            query = q,
                            mode = resolvedMode,
                            unattended = unattended,
                            allowBrowser = allowBrowser,
                            maxResults = 5,
                            maxFetch = 3,
                        ),
                    )
                }
                if (outcome.fetched.isEmpty()) {
                    _state.value = _state.value.copy(error = (outcome.warnings + "没有抓取到内容").joinToString("\n"))
                    return@launch
                }
                outcome.fetched.forEach { fetched ->
                    val raw = fetched.text
                    val sanitized = PromptSafety.sanitizeForPrompt(raw, sourceLabel = fetched.url)
                    store.addSource(
                        sessionId = sessionId,
                        type = SourceType.URL,
                        title = fetched.title,
                        url = fetched.url,
                        content = sanitized.content,
                    )
                }
                store.addMessage(sessionId, Role.USER, "请基于新增来源回答：$q。结论尽量标注引用 [S1]/[S2]。")
                _state.value = _state.value.copy(current = store.getSession(sessionId))
                refreshSessions()
                val session = store.getSession(sessionId) ?: throw IllegalStateException("会话不存在")
                val system = buildSystemPrompt(session)
                val window = session.messages.takeLast(20)
                val assistant = withContext(Dispatchers.IO) {
                    llm.chatCompletions(
                        config = config,
                        system = system,
                        messages = window,
                    )
                }.ifEmpty { "（空响应）" }
                store.addMessage(sessionId, Role.ASSISTANT, assistant)
                _state.value = _state.value.copy(current = store.getSession(sessionId), error = null)
                refreshSessions()
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "联网查询失败")
            } finally {
                setLoading(false)
            }
        }
    }

    private suspend fun decideWebResearchMode(config: top.hsyscn.opedrgent.settings.ApiConfig, query: String): WebResearchMode {
        val session = _state.value.current ?: return WebResearchMode.AUTO
        val system = buildSystemPrompt(session)
        val instruction = """
输出 JSON，不要包含多余文本。
字段：
- mode: "AUTO" | "NATIVE" | "PROVIDER" | "BROWSER"

约束：
- 当前为无人值守模式，除非你非常确定必须交互，否则不要选 BROWSER
- 如果不确定，选 AUTO
""".trimIndent()
        val raw = withContext(Dispatchers.IO) {
            llm.chatCompletions(
                config = config,
                system = system,
                messages = listOf(ChatMessage(role = Role.USER, content = "查询：$query\n$instruction", createdAt = System.currentTimeMillis())),
            )
        }
        val t = raw.trim()
        val s = t.substring(t.indexOf('{').takeIf { it >= 0 } ?: 0, t.lastIndexOf('}').takeIf { it >= 0 }?.plus(1) ?: t.length)
        val obj = runCatching { JSONObject(s) }.getOrNull()
        val mode = obj?.optString("mode")?.trim().orEmpty()
        return runCatching { WebResearchMode.valueOf(mode) }.getOrNull() ?: WebResearchMode.AUTO
    }

    fun importPdfOcr(uri: Uri) {
        val sessionId = _state.value.current?.id ?: return
        viewModelScope.launch {
            setLoading(true)
            try {
                val name = resolveDisplayName(uri) ?: "PDF"
                val bitmaps = PdfProcessor.renderPages(getApplication(), uri, maxPages = 6, scale = 2f)
                val text = PdfProcessor.ocr(bitmaps)
                if (text.isBlank()) throw IllegalStateException("OCR 结果为空")
                addTextSource(title = name, text = text)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "PDF OCR 失败")
            } finally {
                setLoading(false)
            }
        }
    }

    fun importPdfVision(uri: Uri) {
        val sessionId = _state.value.current?.id ?: return
        viewModelScope.launch {
            setLoading(true)
            try {
                val config = apiSettings.getApiConfig() ?: throw IllegalStateException("请先在设置里填写 API Key")
                val name = resolveDisplayName(uri) ?: "PDF"
                val bitmaps = PdfProcessor.renderPages(getApplication(), uri, maxPages = 6, scale = 2f)
                if (bitmaps.isEmpty()) throw IllegalStateException("PDF 无页")
                val pages = bitmaps.map { PdfProcessor.toBase64Png(it) }
                val session = store.getSession(sessionId) ?: throw IllegalStateException("会话不存在")
                val system = buildSystemPrompt(session)
                val prompt = "请阅读这份 PDF（图片形式，最多前 6 页），提取要点摘要，并在结论中标注引用页码（例如 P1、P2）。文件：$name"
                val assistant = withContext(Dispatchers.IO) {
                    llm.visionChat(config = config, system = system, prompt = prompt, pngBase64Pages = pages)
                }.ifEmpty { "（空响应）" }
                store.addMessage(sessionId, Role.USER, "解析 PDF：$name")
                store.addMessage(sessionId, Role.ASSISTANT, assistant)
                store.addArtifact(sessionId, ArtifactKind.REPORT, assistant)
                _state.value = _state.value.copy(current = store.getSession(sessionId), error = null)
                refreshSessions()
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "PDF 多模态失败")
            } finally {
                setLoading(false)
            }
        }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        val ctx = getApplication<Application>()
        val c = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null) ?: return null
        c.use {
            if (!it.moveToFirst()) return null
            val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx < 0) return null
            return it.getString(idx)
        }
    }

    fun importDocx(uri: Uri) {
        val sessionId = _state.value.current?.id ?: return
        viewModelScope.launch {
            setLoading(true)
            try {
                val name = resolveDisplayName(uri)?.removeSuffix(".docx") ?: "Word 文档"
                val text = withContext(Dispatchers.IO) {
                    DocxProcessor.extractText(getApplication(), uri)
                }
                if (text.isBlank()) throw IllegalStateException("Word 文档内容为空")
                addTextSource(title = name, text = text)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "Word 文档读取失败")
            } finally {
                setLoading(false)
            }
        }
    }

    fun importFile(uri: Uri) {
        behaviorTracker.track(BehaviorEvent.FILE_IMPORTED, mapOf("source" to "file_picker"))
        val sessionId = _state.value.current?.id ?: return
        viewModelScope.launch {
            setLoading(true)
            try {
                val name = resolveDisplayName(uri) ?: "文件"
                val text = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: throw IllegalStateException("无法读取文件")
                }
                if (text.isBlank()) throw IllegalStateException("文件内容为空")
                addTextSource(title = name, text = text)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "文件读取失败")
            } finally {
                setLoading(false)
            }
        }
    }

    suspend fun exportContextZip(): File? = withContext(Dispatchers.IO) {
        val session = _state.value.current ?: return@withContext null
        try {
            val cacheDir = getApplication<Application>().cacheDir
            val timestamp = System.currentTimeMillis()
            val zipFile = File(cacheDir, "context_${session.id}_$timestamp.zip")
            
            val tempDir = File(cacheDir, "export_${session.id}_$timestamp")
            tempDir.mkdirs()
            
            val markdownFile = File(tempDir, "session.md")
            val sessionText = buildString {
                appendLine("# ${session.title}")
                appendLine()
                session.messages.forEach { msg ->
                    appendLine("## ${msg.role.name}")
                    appendLine(msg.content)
                    appendLine()
                }
            }
            markdownFile.writeText(sessionText)
            
            val sourcesDir = File(tempDir, "sources")
            sourcesDir.mkdirs()
            session.sources.forEachIndexed { idx, source ->
                val sourceFile = File(sourcesDir, "source_${idx + 1}.txt")
                sourceFile.writeText(source.content)
            }
            
            java.util.zip.ZipOutputStream(java.io.FileOutputStream(zipFile)).use { zip ->
                tempDir.walkTopDown().forEach { file ->
                    if (file.isFile) {
                        val entryName = file.relativeTo(tempDir).path.replace("\\", "/")
                        zip.putNextEntry(java.util.zip.ZipEntry(entryName))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
            
            tempDir.deleteRecursively()
            zipFile
        } catch (e: Exception) {
            DebugLog.e("导出ZIP失败: ${e.message}")
            null
        }
    }

    fun getPackageNameForShare(context: Context): String = context.packageName

    fun startSpeechToText(uri: Uri) {
        sttJob?.cancel()
        lastFailedUri = null
        _sttProgress.value = SttProgressState.IDLE
        _sttUiState.value = SttUiState.Idle
        _sttResult.value = null
        _sttError.value = null

        sttJob = viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                val fileName = getFileNameFromUri(context, uri) ?: "unknown"

                _sttUiState.value = SttUiState.Validating(uri.toString())
                _sttProgress.value = SttProgressState.IDLE

                val (isValid, errorMsg) = withContext(Dispatchers.IO) { AudioProcessor.validateAudioFile(context, uri) }
                if (!isValid) {
                    val errorCode = when {
                        errorMsg?.contains("不支持", ignoreCase = true) == true -> "UNSUPPORTED_FORMAT"
                        errorMsg?.contains("文件", ignoreCase = true) == true -> "FILE_NOT_FOUND"
                        errorMsg?.contains("权限", ignoreCase = true) == true -> "PERMISSION_DENIED"
                        else -> "VALIDATION_FAILED"
                    }
                    val suggestion = when (errorCode) {
                        "UNSUPPORTED_FORMAT" -> "请选择 MP3、WAV、M4A、FLAC 等常见音频格式"
                        "PERMISSION_DENIED" -> "请在设置中授予文件读取权限"
                        "FILE_NOT_FOUND" -> "请确认文件是否存在或重新选择"
                        else -> "请检查音频文件是否损坏后重试"
                    }
                    _sttUiState.value = SttUiState.Error(errorMsg ?: "音频文件验证失败", errorCode, suggestion)
                    _sttProgress.value = SttProgressState.ERROR
                    _sttError.value = errorMsg ?: "音频文件验证失败"
                    lastFailedUri = uri
                    _sttEventBus.emit(errorMsg ?: "音频验证失败")
                    return@launch
                }

                // 检查是否需要下载本地模型（MiMo 在线引擎不需要下载）
                val useMiMoAsr = apiSettings.getSttEngine() == "mimo" && apiSettings.hasApiKey()
                if (!useMiMoAsr) {
                    val recommendedModel = ModelManager.getRecommendedModel(context)
                    if (!ModelManager.isModelDownloaded(context, recommendedModel)) {
                        val modelInfo = ModelManager.AVAILABLE_MODELS.find { it.type == recommendedModel }
                        val modelSizeMb = ((modelInfo?.sizeBytes ?: 0L) / (1024 * 1024)).toInt()
                        _sttUiState.value = SttUiState.DownloadingModel(0f, modelSizeMb)
                        _sttProgress.value = SttProgressState.DOWNLOADING_MODEL

                        ModelManager.downloadModel(context, recommendedModel).collect { progress ->
                            when (progress) {
                                is ModelManager.DownloadProgress.Downloading -> {
                                    _sttUiState.value = SttUiState.DownloadingModel(progress.progress, modelSizeMb)
                                    DebugLog.d("MainViewModel: 模型下载进度 ${(progress.progress * 100).toInt()}%")
                                }
                                is ModelManager.DownloadProgress.Extracting -> {
                                    _sttUiState.value = SttUiState.DownloadingModel(progress.progress, modelSizeMb)
                                }
                                is ModelManager.DownloadProgress.Complete -> { /* done */ }
                                is ModelManager.DownloadProgress.Error -> {
                                    _sttUiState.value = SttUiState.Error(
                                        "模型下载失败，请检查网络连接",
                                        "DOWNLOAD_FAILED",
                                        "请确保网络畅通后点击重试",
                                    )
                                    _sttProgress.value = SttProgressState.ERROR
                                    _sttError.value = "模型下载失败"
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
                val audioMeta = withContext(Dispatchers.IO) { AudioProcessor.getAudioMetadata(context, uri) }
                DebugLog.i("STT: 音频元数据 duration=${audioMeta?.durationMs}ms file=$fileName")

                _sttUiState.value = SttUiState.Recognizing(0f, 0, audioMeta?.let { Math.ceil(it.durationMs / 30000.0).toInt() } ?: 1)
                _sttProgress.value = SttProgressState.RECOGNIZING

                // 使用 AsrManager 统一引擎转录
                val result = withContext(Dispatchers.IO) {
                    DebugLog.i("STT: 使用 AsrManager 统一引擎转录")
                    asrManager.transcribeFile(uri)
                }
                val enrichedResult = result

                _sttResult.value = enrichedResult
                _sttUiState.value = SttUiState.Done(enrichedResult)
                _sttProgress.value = SttProgressState.DONE

                _sttHistory.value = listOf(enrichedResult) + _sttHistory.value.take(49)
                _sttEventBus.emit("转录完成，共 ${result.text.length} 字")

                DebugLog.i("STT: 转录完成 text=${result.text.take(50)}... confidence=${result.confidence} duration=${result.durationMs}ms")
            } catch (e: kotlinx.coroutines.CancellationException) {
                DebugLog.i("STT: 用户取消转录")
                _sttUiState.value = SttUiState.Idle
                _sttProgress.value = SttProgressState.IDLE
            } catch (e: Exception) {
                val errorCode = when (e) {
                    is java.io.IOException -> "IO_ERROR"
                    is java.lang.OutOfMemoryError -> "OUT_OF_MEMORY"
                    is java.lang.IllegalStateException -> "ENGINE_ERROR"
                    else -> "UNKNOWN_ERROR"
                }
                val suggestion = when (errorCode) {
                    "IO_ERROR" -> "请检查存储空间是否充足，或尝试更小的音频文件"
                    "OUT_OF_MEMORY" -> "设备内存不足，请关闭其他应用后重试"
                    "ENGINE_ERROR" -> "STT 引擎初始化异常，请尝试重新下载模型"
                    else -> "如问题持续出现，请联系开发者反馈"
                }
                DebugLog.e("STT: 转录异常 [${errorCode}] ${e.message}", e)
                _sttUiState.value = SttUiState.Error(e.message ?: "转录过程发生未知错误", errorCode, suggestion)
                _sttProgress.value = SttProgressState.ERROR
                _sttError.value = e.message ?: "转录过程发生未知错误"
                lastFailedUri = uri
                _sttEventBus.tryEmit("转录失败: ${e.message}")
            } finally {
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

    fun cancelStt() {
        sttJob?.cancel()
        sttJob = null
        _sttProgress.value = SttProgressState.IDLE
        _sttUiState.value = SttUiState.Idle
        sttEngine?.close()
        sttEngine = null
    }

    fun retryLastStt() {
        val uri = lastFailedUri ?: run {
            _sttError.value = "没有可重试的失败记录"
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

        sttJob = viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                _sttUiState.value = SttUiState.Recognizing(0f, 0, 1)
                _sttProgress.value = SttProgressState.RECOGNIZING

                val recognizer = AndroidSpeechRecognizer(context, SttConfig(mode = RecognitionMode.STREAMING))
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
                            _sttUiState.value = SttUiState.Error(state.message, "RECOGNITION_ERROR", "请检查麦克风权限或重试")
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                DebugLog.i("STT: 用户取消实时录音")
                _sttUiState.value = SttUiState.Idle
                _sttProgress.value = SttProgressState.IDLE
            } catch (e: Exception) {
                DebugLog.e("STT: 实时录音异常 ${e.message}", e)
                _sttUiState.value = SttUiState.Error(e.message ?: "实时语音识别发生未知错误", "REALTIME_ERROR", "请检查麦克风权限后重试")
                _sttProgress.value = SttProgressState.ERROR
                _sttError.value = e.message ?: "实时语音识别发生未知错误"
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
     * 返回 Flow<StreamingRecognitionState>，调用方 collect 获取识别结果。
     */
    suspend fun startUnifiedStreamingAsr(): kotlinx.coroutines.flow.Flow<top.hsyscn.opedrgent.stt.StreamingRecognitionState> {
        asrManager.ensureInitialized()
        return asrManager.startStreaming()
    }

    /**
     * 停止统一 ASR 流式识别。
     */
    fun stopUnifiedStreamingAsr() {
        asrManager.stopStreaming()
    }

    fun copyToClipboard(text: String, showToast: Boolean = true) {
        try {
            val context = getApplication<Application>()
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("opedrgent_stt", text))
            DebugLog.i("STT: 已复制到剪贴板 length=${text.length}")
            if (showToast) {
                _sttEventBus.tryEmit("已复制 ${text.length} 个字符到剪贴板")
            }
        } catch (e: Exception) {
            DebugLog.e("STT: 复制到剪贴板失败 ${e.message}", e)
            _sttError.value = "复制失败: ${e.message}"
        }
    }

    fun pasteFromClipboard(): String? {
        return try {
            val context = getApplication<Application>()
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).coerceToText(context).toString().takeIf { it.isNotBlank() }
            } else {
                null
            }
        } catch (e: SecurityException) {
            DebugLog.w("STT: 剪贴板读取被拒绝（可能需要 READ_CLIPBOARD 权限）")
            _sttError.value = "无法读取剪贴板，请在设置中授予剪贴板读取权限"
            null
        } catch (e: Exception) {
            DebugLog.e("STT: 读取剪贴板失败 ${e.message}", e)
            null
        }
    }

    fun sendSttResultToLlm(customPrompt: String? = null) {
        val result = _sttResult.value ?: run {
            _sttError.value = "没有可发送的转录结果"
            return
        }
        if (result.text.isBlank()) {
            _sttError.value = "转录结果为空，无法发送"
            return
        }

        val sessionId = _state.value.current?.id
        if (sessionId == null) {
            createSessionAndNavigate("语音转文字 - ${java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))}")
        }

        val currentSessionId = _state.value.current?.id ?: return
        val prompt = customPrompt ?: buildString {
            appendLine("以下是语音转录的结果，请帮我：\n\n")
            appendLine("--- 转录文本 ---")
            appendLine(result.text)
            appendLine("--- 统计信息 ---")
            appendLine("时长: ${AudioProcessor.formatDuration(result.durationMs)}")
            appendLine("字数: ${result.text.length}")
            if (result.confidence > 0f) {
                appendLine("置信度: ${"%.1f".format(result.confidence * 100)}%")
            }
            if (result.modelUsed.isNotBlank()) {
                appendLine("模型: ${result.modelUsed}")
            }
            appendLine("\n请对以上内容进行总结、提取要点，或根据我的需求进行分析。")
        }

        store.addMessage(currentSessionId, Role.USER, prompt.trim())
        _state.value = _state.value.copy(current = store.getSession(currentSessionId))
        refreshSessions()
        runModel(currentSessionId)

        DebugLog.i("STT: 已将转录结果发送给 LLM sessionId=$currentSessionId length=${result.text.length}")
    }

    fun checkModelDownloaded(): Boolean {
        val context = getApplication<Application>()
        val model = ModelManager.getRecommendedModel(context)
        return ModelManager.isModelDownloaded(context, model)
    }

    fun downloadModel(modelType: ModelType): Job {
        val modelInfo = ModelManager.AVAILABLE_MODELS.find { it.type == modelType }
        val modelSizeMb = ((modelInfo?.sizeBytes ?: 0L) / (1024 * 1024)).toInt()
        _sttUiState.value = SttUiState.DownloadingModel(0f, modelSizeMb)
        _sttProgress.value = SttProgressState.DOWNLOADING_MODEL

        return viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                ModelManager.downloadModel(context, modelType).collect { progress ->
                    when (progress) {
                        is ModelManager.DownloadProgress.Downloading -> {
                            withContext(Dispatchers.Main) {
                                _sttUiState.value = SttUiState.DownloadingModel(progress.progress, modelSizeMb)
                                DebugLog.d("MainViewModel: 模型下载进度 ${(progress.progress * 100).toInt()}%")
                            }
                        }
                        is ModelManager.DownloadProgress.Extracting -> {
                            withContext(Dispatchers.Main) {
                                _sttUiState.value = SttUiState.DownloadingModel(progress.progress, modelSizeMb)
                            }
                        }
                        is ModelManager.DownloadProgress.Complete -> { /* done */ }
                        is ModelManager.DownloadProgress.Error -> {
                            withContext(Dispatchers.Main) {
                                _sttUiState.value = SttUiState.Error(
                                    "模型下载失败，请检查网络连接",
                                    "DOWNLOAD_FAILED",
                                    "请确保网络畅通后重试",
                                )
                                _sttProgress.value = SttProgressState.ERROR
                                _sttError.value = "模型下载失败"
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
                    DebugLog.e("MainViewModel: 模型下载异常 ${e.message}", e)
                    _sttUiState.value = SttUiState.Error(
                        "模型下载异常: ${e.message}",
                        "DOWNLOAD_EXCEPTION",
                        "请检查网络后重试",
                    )
                    _sttProgress.value = SttProgressState.ERROR
                    _sttError.value = "模型下载异常: ${e.message}"
                }
            }
        }
    }

    private fun initializeSttEngine(modelType: ModelType) {
        val context = getApplication<Application>()
        val modelDir = ModelManager.getModelPath(context, modelType)
        if (modelDir != null && modelDir.exists()) {
            try {
                val engine = SherpaOnnxEngine(context, SttConfig(modelType = modelType))
                engine.initialize(modelDir)
                sherpaOnnxEngine = engine
                _sttUiState.value = SttUiState.Idle
                _sttProgress.value = SttProgressState.IDLE
                _sttEventBus.tryEmit("模型 ${modelType.name} 初始化完成")
                DebugLog.i("MainViewModel: STT 引擎初始化成功 model=$modelType")
            } catch (e: Exception) {
                DebugLog.e("MainViewModel: STT 引擎初始化失败 ${e.message}", e)
                _sttUiState.value = SttUiState.Error(
                    "STT 引擎初始化失败",
                    "ENGINE_INIT_FAILED",
                    "尝试删除模型缓存后重新下载",
                )
                _sttProgress.value = SttProgressState.ERROR
                _sttError.value = "STT 引擎初始化失败"
            }
        } else {
            _sttUiState.value = SttUiState.Error(
                "模型文件不存在",
                "MODEL_NOT_FOUND",
                "请先下载模型",
            )
            _sttProgress.value = SttProgressState.ERROR
            _sttError.value = "模型文件不存在"
        }
    }

    fun getRecommendedModel(): ModelType {
        val context = getApplication<Application>()
        return ModelManager.getRecommendedModel(context)
    }

    fun triggerInsightSprout(text: String, config: SproutConfig? = null) {
        val trimmedText = text.trim()
        if (trimmedText.isBlank()) {
            _sproutUiState.value = SproutUiState.Error("输入文本不能为空")
            _sproutingState.value = SproutingState.ERROR
            _sproutResult.value = "输入文本不能为空，请提供需要发芽的内容"
            return
        }

        if (trimmedText.length < 10) {
            _sproutUiState.value = SproutUiState.Error("输入文本过短（至少10个字符），请提供更丰富的内容以获得更好的发芽效果")
            _sproutingState.value = SproutingState.ERROR
            return
        }

        val cacheKey = trimmedText.hashCode().toString()
        sproutCache[cacheKey]?.let { cached ->
            DebugLog.i("Sprout: 命中缓存，直接返回历史结果")
            _sproutResult.value = cached.markdownReport
            _sproutUiState.value = SproutUiState.Done(cached.markdownReport, computeSproutQualityScore(cached))
            _sproutingState.value = SproutingState.DONE
            return
        }

        sproutJob?.cancel()
        _sproutingState.value = SproutingState.IDLE
        _sproutResult.value = null
        _sproutUiState.value = SproutUiState.Idle

        val keywordPreview = trimmedText.take(80).replace("\n", " ") + if (trimmedText.length > 80) "..." else ""
        DebugLog.i("Sprout: 开始发芽 inputLength=${trimmedText.length} preview=$keywordPreview")

        sproutJob = viewModelScope.launch {
            try {
                val effectiveConfig = config ?: SproutConfig()
                val sessionId = _state.value.current?.id

                _sproutUiState.value = SproutUiState.AnalyzingInput(keywordPreview)
                delay(200)

                val phaseOrder = listOf(
                    top.hsyscn.opedrgent.insight.SproutPhase.SEED_EXTRACTION,
                    top.hsyscn.opedrgent.insight.SproutPhase.CROSS_DOMAIN,
                    top.hsyscn.opedrgent.insight.SproutPhase.AHA_INSIGHT,
                    top.hsyscn.opedrgent.insight.SproutPhase.QUOTE_RESONANCE,
                )
                var currentPhaseIndex = 0
                val startTime = System.currentTimeMillis()

                val engine = InsightSproutEngine { prompt ->
                    val apiConfig = apiSettings.getApiConfig() ?: throw IllegalStateException("请先在设置里填写 API Key")
                    LlmClient().chatCompletions(
                        config = apiConfig,
                        system = "你是一个知识分析助手，请根据用户输入进行深度分析。",
                        messages = listOf(ChatMessage(role = Role.USER, content = prompt, createdAt = System.currentTimeMillis())),
                    )
                }

                _sproutUiState.value = SproutUiState.GeneratingReport(0, 4)

                val result = engine.sprout(trimmedText, effectiveConfig)

                for ((i, phase) in result.completedPhases.withIndex()) {
                    _sproutUiState.value = SproutUiState.GeneratingReport(i + 1, result.completedPhases.size)
                    when (phase) {
                        top.hsyscn.opedrgent.insight.SproutPhase.SEED_EXTRACTION -> _sproutingState.value = SproutingState.PHASE1
                        top.hsyscn.opedrgent.insight.SproutPhase.CROSS_DOMAIN -> _sproutingState.value = SproutingState.PHASE2
                        top.hsyscn.opedrgent.insight.SproutPhase.AHA_INSIGHT -> _sproutingState.value = SproutingState.PHASE3
                        top.hsyscn.opedrgent.insight.SproutPhase.QUOTE_RESONANCE -> _sproutingState.value = SproutingState.PHASE4
                    }
                }

                val qualityScore = computeSproutQualityScore(result)
                _sproutResult.value = result.markdownReport
                _sproutUiState.value = SproutUiState.Done(result.markdownReport, qualityScore)
                _sproutingState.value = SproutingState.DONE

                sproutCache[cacheKey] = result
                _sproutHistory.value = listOf(result) + _sproutHistory.value.take(49)

                DebugLog.i("Sprout: 发芽完成 phases=${result.completedPhases.size}/4 quality=$qualityScore time=${result.processingTimeMs}ms seeds=${result.seeds.size} insights=${result.insights.size}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                val completedPhases = _sproutUiState.value.let { (it as? SproutUiState.GeneratingReport)?.phasesCompleted ?: 0 }
                DebugLog.i("Sprout: 用户取消发芽 completedPhases=$completedPhases")
                _sproutUiState.value = SproutUiState.Cancelled(completedPhases)
                _sproutingState.value = SproutingState.IDLE
            } catch (e: Exception) {
                val failedPhase = _sproutUiState.value.let { (it as? SproutUiState.PhaseInProgress)?.phase }
                DebugLog.e("Sprout: 发芽异常 [${failedPhase?.name ?: "UNKNOWN"}] ${e.message}", e)
                _sproutUiState.value = SproutUiState.Error(
                    "发芽处理失败: ${e.message}",
                    failedPhase,
                )
                _sproutingState.value = SproutingState.ERROR
                _sproutResult.value = "发芽处理失败: ${e.message}"
            }
        }
    }

    private fun computeSproutQualityScore(result: top.hsyscn.opedrgent.insight.SproutResult): Int {
        var score = 50
        score += (result.completedPhases.size * 10).coerceAtMost(40)
        score += (result.seeds.size * 3).coerceAtMost(15)
        score += (result.insights.size * 5).coerceAtMost(15)
        score += (result.quotes.size * 2).coerceAtMost(10)
        if (result.markdownReport.length > 500) score += 5
        if (result.connections.isNotEmpty()) score += 5
        return score.coerceIn(0, 100)
    }

    fun sproutCurrentContext() {
        val currentSession = _state.value.current ?: run {
            _sproutUiState.value = SproutUiState.Error("没有当前会话，请先开始对话")
            _sproutingState.value = SproutingState.ERROR
            return
        }
        val session = store.getSession(currentSession.id)
        val recentMessages = session?.messages?.takeLast(10) ?: emptyList()
        val contextText = buildString {
            for (msg in recentMessages) {
                appendLine("[${msg.role.name}] ${msg.content}")
            }
        }.trim()

        if (contextText.isBlank()) {
            _sproutUiState.value = SproutUiState.Error("当前对话为空，没有可发芽的内容")
            _sproutingState.value = SproutingState.ERROR
            return
        }

        triggerInsightSprout(contextText)
    }

    fun cancelSprouting() {
        sproutJob?.cancel()
        sproutJob = null
        val currentState = _sproutUiState.value
        if (currentState !is SproutUiState.Done && currentState !is SproutUiState.Error && currentState !is SproutUiState.Cancelled) {
            _sproutingState.value = SproutingState.IDLE
            _sproutUiState.value = SproutUiState.Idle
        }
    }

    // ==================== AI 风格转换 ====================

    fun convertNoteStyle(noteId: Long, style: String) {
        viewModelScope.launch {
            _isConverting.value = true
            try {
                val note = noteRepository.getNoteById(noteId) ?: return@launch
                val config = apiSettings.getApiConfig()
                    ?: throw IllegalStateException("请先在设置里填写 API Key")
                val stylePrompt = when (style) {
                    "xiaohongshu" -> "请将以下笔记内容改写为小红书风格：使用emoji、分段清晰、有吸引力的标题、口语化表达、适当使用话题标签。"
                    "wechat" -> "请将以下笔记内容改写为公众号文章风格：专业、有深度、逻辑清晰、适当使用小标题、结尾有总结。"
                    "moments" -> "请将以下笔记内容精简为朋友圈风格：简短有力（150字以内）、有感悟、有金句、适合配图发布。"
                    else -> "请优化以下内容："
                }

                val prompt = "$stylePrompt\n\n---\n${note.content}\n---"
                val response = withContext(Dispatchers.IO) {
                    llm.chatCompletions(
                        config = config,
                        system = "你是一位专业的内容创作者，擅长不同平台的内容风格转换。",
                        messages = listOf(
                            ChatMessage(role = Role.USER, content = prompt, createdAt = System.currentTimeMillis()),
                        ),
                    )
                }
                _aiConvertedContent.value = response.trim()
            } catch (e: Exception) {
                _aiConvertedContent.value = "转换失败: ${e.message}"
            } finally {
                _isConverting.value = false
            }
        }
    }

    fun clearConvertedContent() {
        _aiConvertedContent.value = null
    }

    // ==================== 面试模式 ====================

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
    private val interviewTranscript = mutableListOf<DialogueTurn>()

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
        viewModelScope.launch {
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

                // 生成第一个问题（开场白 + 第一题）
                val firstQuestion = withContext(Dispatchers.IO) {
                    InterviewAgent.generateFirstQuestion(
                        llmClient = llm,
                        config = config,
                    )
                }

                // 记录面试官消息
                val introTurn = DialogueTurn(
                    role = "interviewer",
                    content = firstQuestion,
                    questionCategory = "开场",
                )
                interviewTranscript.add(introTurn)

                // 更新状态：进入进行中阶段
                _interviewState.value = InterviewUiState(
                    phase = InterviewPhase.IN_PROGRESS,
                    config = config,
                    messages = interviewTranscript.toList(),
                    questionCount = 1,
                    elapsedSeconds = 0,
                )

                // 启动计时器
                launchInterviewTimer()

            } catch (e: Exception) {
                DebugLog.e("Interview", "启动面试失败: ${e.message}", e)
                _interviewState.value = _interviewState.value.copy(
                    error = "启动面试失败: ${e.message}",
                )
            }
        }
    }

    /**
     * 发送候选人回答 — 文字输入模式。
     */
    fun sendInterviewAnswer(answer: String) {
        viewModelScope.launch {
            val currentState = _interviewState.value
            if (currentState.phase != InterviewPhase.IN_PROGRESS || currentState.config == null) return@launch

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
                            questionCategory = "追问",
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
                            questionCategory = "结束",
                        )
                        interviewTranscript.add(endTurn)

                        generateFinalReport(currentState.config)
                        return@launch
                    }
                }

                // 可选：生成教练反馈（如果启用）
                if (currentState.config.enableCoach || currentState.config.enableRealtimeFeedback) {
                    val coachFb = withContext(Dispatchers.IO) {
                        InterviewAgent.generateCoachFeedback(
                            llmClient = llm,
                            question = lastInterviewerMessage,
                            answer = answerTurn,
                        )
                    }
                    _interviewState.value = _interviewState.value.copy(coachFeedback = coachFb)
                }

                // 恢复正常状态
                _interviewState.value = InterviewUiState(
                    phase = InterviewPhase.IN_PROGRESS,
                    config = currentState.config,
                    messages = interviewTranscript.toList(),
                    questionCount = interviewTranscript.count { it.role == "interviewer" },
                    elapsedSeconds = ((System.currentTimeMillis() - interviewStartTime) / 1000).toInt(),
                    coachFeedback = _interviewState.value.coachFeedback,
                )

            } catch (e: Exception) {
                DebugLog.e("Interview", "处理回答失败: ${e.message}", e)
                _interviewState.value = _interviewState.value.copy(
                    phase = InterviewPhase.IN_PROGRESS,
                    error = "处理失败: ${e.message}",
                )
            }
        }
    }

    /**
     * 结束面试并生成报告。
     */
    fun endInterview() {
        viewModelScope.launch {
            val currentState = _interviewState.value
            if (currentState.config == null) return@launch

            generateFinalReport(currentState.config)
        }
    }

    /**
     * 生成最终评估报告。
     */
    private suspend fun generateFinalReport(config: InterviewConfig) {
        _interviewState.value = _interviewState.value.copy(phase = InterviewPhase.EVALUATING)

        try {
            val report = withContext(Dispatchers.IO) {
                InterviewAgent.generateReport(
                    llmClient = llm,
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

            // 停止语音引擎
            voiceEngine?.stopConversation()
        } catch (e: Exception) {
            DebugLog.e("Interview", "生成报告失败: ${e.message}", e)
            _interviewState.value = _interviewState.value.copy(
                phase = InterviewPhase.COMPLETED,
                error = "报告生成失败: ${e.message}",
            )
        }
    }

    /**
     * 重置面试状态。
     */
    fun resetInterview() {
        voiceEngine?.stopConversation()
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
        val context = getApplication<Application>()

        // 延迟初始化语音引擎
        if (voiceEngine == null) {
            voiceEngine = VoiceConversationEngine(context, tts, apiSettings)
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
        viewModelScope.launch {
            val report = _interviewState.value.report ?: return@launch

            try {
                val noteContent = buildString {
                    appendLine("# 面试评估报告")
                    appendLine()
                    appendLine("**类型**: ${report.type.label}")
                    appendLine("**总分**: ${report.overallScore} 分 (${report.verdict.label})")
                    appendLine("**时长**: ${report.durationSeconds} 秒")
                    appendLine("**问题数**: ${report.questionCount}")
                    appendLine()
                    appendLine("## 总体评价")
                    appendLine(report.summary)
                    appendLine()
                    if (report.strengths.isNotEmpty()) {
                        appendLine("## [优势]")
                        report.strengths.forEach { appendLine("- $it") }
                        appendLine()
                    }
                    if (report.weaknesses.isNotEmpty()) {
                        appendLine("## [不足]")
                        report.weaknesses.forEach { appendLine("- $it") }
                        appendLine()
                    }
                    if (report.recommendations.isNotEmpty()) {
                        appendLine("## [改进建议]")
                        report.recommendations.forEach { appendLine("- $it") }
                        appendLine()
                    }
                    if (report.dimensions.isNotEmpty()) {
                        appendLine("## [各维度评分]")
                        report.dimensions.forEach { dim ->
                            appendLine("- **${dim.name}**: ${dim.score}/${dim.maxScore.toInt()} - ${dim.feedback}")
                        }
                    }
                }

                noteRepository.quickCreate(
                    content = noteContent,
                    type = top.hsyscn.opedrgent.note.NoteType.TEXT,
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
        viewModelScope.launch {
            while (_interviewState.value.phase == InterviewPhase.IN_PROGRESS) {
                delay(1000L)
                val elapsed = ((System.currentTimeMillis() - interviewStartTime) / 1000).toInt()
                _interviewState.value = _interviewState.value.copy(elapsedSeconds = elapsed)
            }
        }
    }
}
