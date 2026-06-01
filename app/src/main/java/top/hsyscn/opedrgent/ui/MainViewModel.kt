package top.hsyscn.opedrgent.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
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
import top.hsyscn.opedrgent.model.ArtifactKind
import top.hsyscn.opedrgent.model.ChatMessage
import top.hsyscn.opedrgent.model.MemoryEntry
import top.hsyscn.opedrgent.model.MemoryType
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
import top.hsyscn.opedrgent.ui.components.QuestionOption
import top.hsyscn.opedrgent.ui.components.QuestionInfo
import top.hsyscn.opedrgent.ui.components.QuestionRequest
import top.hsyscn.opedrgent.ui.components.ConfirmationOption
import top.hsyscn.opedrgent.ui.components.ConfirmationRequest
import top.hsyscn.opedrgent.tts.TtsPlayer
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
import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import top.hsyscn.opedrgent.stt.SttResult
import top.hsyscn.opedrgent.stt.ModelType
import top.hsyscn.opedrgent.stt.ModelManager
import top.hsyscn.opedrgent.stt.SherpaOnnxEngine
import top.hsyscn.opedrgent.stt.AndroidSpeechRecognizer
import top.hsyscn.opedrgent.stt.AudioProcessor
import top.hsyscn.opedrgent.stt.SttConfig
import top.hsyscn.opedrgent.insight.InsightSproutEngine
import top.hsyscn.opedrgent.insight.SproutConfig

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

enum class SttProgressState {
    IDLE,
    DOWNLOADING_MODEL,
    EXTRACTING_AUDIO,
    RECOGNIZING,
    DONE,
    ERROR,
}

enum class SproutingState {
    IDLE,
    PHASE1,
    PHASE2,
    PHASE3,
    PHASE4,
    DONE,
    ERROR,
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val http = HttpClients.default
    private val store = ResearchStore(app)
    private val apiSettings = ApiSettings(app)
    private val localEngine = LocalLlmEngine.getInstance(app)
    private val skillsStore = SkillsStore(app)
    private val memoryStore = MemoryStore(app)
    private val sourceFetcher = SourceFetcher(http)
    private val llm = LlmClient(http)
    private val webSearcher = WebSearcher(http)
    private val webResearchRouter = WebResearchRouter(webSearcher, sourceFetcher)
    private val toolExecutor = ToolExecutor(app, webSearcher, sourceFetcher, llm, apiSettings)
    private val tts = TtsPlayer(app, apiSettings)
    private val automationStore = AutomationStore(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

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
        _questionResponse.tryEmit(answers)
    }

    private val _confirmationRequest = MutableStateFlow<ConfirmationRequest?>(null)
    val confirmationRequest: StateFlow<ConfirmationRequest?> = _confirmationRequest

    private val _confirmationResponse = MutableSharedFlow<String?>(replay = 0)

    fun respondToConfirmation(selectedOption: String?) {
        _confirmationResponse.tryEmit(selectedOption)
    }

    val _sttProgress = MutableStateFlow<SttProgressState>(SttProgressState.IDLE)
    val sttProgress: StateFlow<SttProgressState> = _sttProgress.asStateFlow()

    val _sttResult = MutableStateFlow<SttResult?>(null)
    val sttResult: StateFlow<SttResult?> = _sttResult.asStateFlow()

    val _sttError = MutableStateFlow<String?>(null)
    val sttError: StateFlow<String?> = _sttError.asStateFlow()

    val _sproutingState = MutableStateFlow<SproutingState>(SproutingState.IDLE)
    val sproutingState: StateFlow<SproutingState> = _sproutingState.asStateFlow()

    val _sproutResult = MutableStateFlow<String?>(null)
    val sproutResult: StateFlow<String?> = _sproutResult.asStateFlow()

    private var sttEngine: SpeechEngine? = null
    private var sttJob: Job? = null
    private var sproutJob: Job? = null

    private var currentCall: Call? = null
    private var currentRunJob: Job? = null
    private val cancelled = AtomicBoolean(false)
    private val sessionCache = mutableMapOf<String, ResearchSession>()
    // Tool executor serialized via limitedParallelism(1) dispatcher to prevent concurrent duplicate searches
    private val searchDispatcher = Dispatchers.IO.limitedParallelism(1)

    init {
        DebugLog.enabled = apiSettings.isDebugMode()
        DebugLog.i("MainViewModel init")
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
        _state.value = _state.value.copy(current = store.getSession(id), error = null)
        apiSettings.setLastSessionId(id)
    }

    fun closeSession() {
        _state.value = _state.value.copy(current = null, error = null)
        refreshSessions()
    }

    fun createSession(title: String) {
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
        val sessionId = _state.value.current?.id ?: return
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
        cancelled.set(false)
        currentRunJob = viewModelScope.launch {
            setLoading(true)
            _state.value = _state.value.copy(streamingSessionId = sessionId)
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
                val toolMessages = mutableListOf<ChatMessage>()
                val allToolParts = mutableListOf<ToolPart>()
                var accumulatedText = ""
                var accumulatedReasoning = ""
                var finalContent = ""
                var finalReasoning = ""
                val usedUrls = HashSet<String>()
                var sourceTagIdx = 0
                val maxContextTokens = if (top.hsyscn.opedrgent.network.LlmClient.isDeepSeekV4(config.model)) {
                    top.hsyscn.opedrgent.network.LlmClient.getDeepSeekMaxContext()
                } else 16000

                val state = ResearchState(maxRounds = 10)

                while (state.shouldContinue()) {
                    if (cancelled.get()) {
                        DebugLog.i("runModel cancelled at round ${state.roundsUsed}")
                        return@launch
                    }

                    val session = store.getSession(sessionId) ?: throw IllegalStateException("会话不存在")
                    val system = buildSystemPrompt(session)

                    val allMessages = session.messages + toolMessages
                    val compressed = ContextCompressor.compress(allMessages, system, maxContextTokens)
                    val compressedSystem = if (compressed.summary != null) {
                        "$system\n\n${compressed.summary}"
                    } else system
                    val messages = compressed.recentMessages

                    DebugLog.d("runModel: round ${state.roundsUsed}, messages=${messages.size}, tokens=${compressed.tokenCount}")

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
                            streamMultimodalLlm(config, compressedSystem, messages, mapImages, tools = agentTools, deepThinkingEnabled = _state.value.deepThinkingEnabled)
                        }
                    } else {
                        withContext(Dispatchers.IO) {
                            streamLlm(config, compressedSystem, messages, tools = agentTools, deepThinkingEnabled = _state.value.deepThinkingEnabled)
                        }
                    }

                    if (cancelled.get()) {
                        DebugLog.i("runModel cancelled after streaming round ${state.roundsUsed}")
                        return@launch
                    }

                    if (result.error != null) {
                        val classified = top.hsyscn.opedrgent.network.ErrorClassifier.classify(
                            java.lang.Exception(result.error), null, null
                        )
                        DebugLog.e("runModel: LLM returned error: ${result.error}, roundsUsed=${state.roundsUsed}")
                        DebugLog.e("runModel: error classified: ${top.hsyscn.opedrgent.network.ErrorClassifier.formatForLog(classified)}")
                        if (result.content.isNotBlank()) {
                            accumulatedText += (if (accumulatedText.isNotBlank()) "\n\n" else "") + result.content
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
                            streamingText = accumulatedText,
                            streamingPhase = "生成回答…",
                        )
                        break
                    }

                    finalContent = result.content
                    finalReasoning = result.reasoning

                    if (result.content.isNotBlank()) {
                        accumulatedText += (if (accumulatedText.isNotBlank()) "\n\n" else "") + result.content
                    }
                    if (result.reasoning.isNotBlank()) {
                        accumulatedReasoning += (if (accumulatedReasoning.isNotBlank()) "\n" else "") + result.reasoning
                    }

                    _state.value = _state.value.copy(
                        streamingText = accumulatedText,
                        streamingReasoning = accumulatedReasoning,
                        streamingPhase = "生成回答…",
                    )

                    if (result.toolCalls.isEmpty()) {
                        DebugLog.i("runModel: no tool_call in response, model is done at round ${state.roundsUsed}")
                        state.recordNoToolCalls(result.content)
                        _state.value = _state.value.copy(streamingPhase = "生成回答…")
                        break
                    }

                    val toolCallIds = result.toolCalls.map { it.id }
                    val toolCallMap = result.toolCalls.associateBy { it.id }

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
                    allToolParts.addAll(pendingToolParts)
                    _state.value = _state.value.copy(streamingToolParts = allToolParts.toList())

                    coroutineScope {
                        result.toolCalls.forEachIndexed { idx, tc ->
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

                                synchronized(allToolParts) {
                                    val pos = allToolParts.indexOfFirst { it.id == tp.id }
                                    if (pos >= 0) allToolParts[pos] = runningTp
                                }
                                _state.value = _state.value.copy(streamingToolParts = allToolParts.toList())

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

                                        val resultAnswers = answers.mapIndexed { idx, ans ->
                                            mapOf("question" to questions[idx].question, "answers" to ans)
                                        }
                                        val resultTp = tp.copy(state = tp.state.copy(
                                            status = ToolStateType.COMPLETED,
                                            output = org.json.JSONObject(mapOf("answers" to resultAnswers)).toString(),
                                        ))
                                        synchronized(allToolParts) {
                                            val pos = allToolParts.indexOfFirst { it.id == tp.id }
                                            if (pos >= 0) allToolParts[pos] = resultTp
                                        }
                                        _state.value = _state.value.copy(streamingToolParts = allToolParts.toList())

                                        toolMessages.add(ChatMessage(
                                            role = Role.USER,
                                            content = org.json.JSONObject(mapOf("answers" to resultAnswers)).toString(),
                                            createdAt = System.currentTimeMillis(),
                                            toolCallId = tc.id,
                                        ))
                                    } catch (e: Exception) {
                                        DebugLog.e("ask_question error: ${e.message}", e)
                                        val errorTp = tp.copy(state = tp.state.copy(
                                            status = ToolStateType.ERROR,
                                            error = "ask_question 处理失败: ${e.message}",
                                        ))
                                        synchronized(allToolParts) {
                                            val pos = allToolParts.indexOfFirst { it.id == tp.id }
                                            if (pos >= 0) allToolParts[pos] = errorTp
                                        }
                                        _state.value = _state.value.copy(streamingToolParts = allToolParts.toList())
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

                                        val resultMap = buildString {
                                            append("{")
                                            append("\"confirmed\": ${selectedOption != null}")
                                            if (selectedOption != null) {
                                                append(", \"selectedOption\": \"${selectedOption.replace("\"", "\\\"")}\"")
                                            }
                                            append(", \"timeout\": false")
                                            append("}")
                                        }
                                        val resultTp = tp.copy(state = tp.state.copy(
                                            status = ToolStateType.COMPLETED,
                                            output = resultMap,
                                        ))
                                        synchronized(allToolParts) {
                                            val pos = allToolParts.indexOfFirst { it.id == tp.id }
                                            if (pos >= 0) allToolParts[pos] = resultTp
                                        }
                                        _state.value = _state.value.copy(streamingToolParts = allToolParts.toList())

                                        toolMessages.add(ChatMessage(
                                            role = Role.USER,
                                            content = resultMap,
                                            createdAt = System.currentTimeMillis(),
                                            toolCallId = tc.id,
                                        ))
                                    } catch (e: Exception) {
                                        DebugLog.e("ask_confirmation error: ${e.message}", e)
                                        val errorTp = tp.copy(state = tp.state.copy(
                                            status = ToolStateType.ERROR,
                                            error = "ask_confirmation 处理失败: ${e.message}",
                                        ))
                                        synchronized(allToolParts) {
                                            val pos = allToolParts.indexOfFirst { it.id == tp.id }
                                            if (pos >= 0) allToolParts[pos] = errorTp
                                        }
                                        _state.value = _state.value.copy(streamingToolParts = allToolParts.toList())
                                    }
                                    return@async
                                }

                                val execResult = withContext(Dispatchers.IO) {
                                    toolExecutor.execute(tp, config, system, useProviderSearch = isProviderWebSearchEnabled())
                                }

                                val doneTp = execResult.toolPart
                                synchronized(allToolParts) {
                                    val pos = allToolParts.indexOfFirst { it.id == tp.id }
                                    if (pos >= 0) allToolParts[pos] = doneTp
                                }
                                _state.value = _state.value.copy(streamingToolParts = allToolParts.toList())

                                val newSources = execResult.addedSources.filter { usedUrls.add(it) }
                                if (newSources.isNotEmpty()) {
                                    val s = store.getSession(sessionId)
                                    newSources.forEach { url ->
                                        if (s != null && s.sources.none { it.url == url }) {
                                            store.addSource(sessionId, SourceType.URL, title = url, url = url, content = "")
                                        }
                                    }
                                }

                                if (execResult.openBrowserUrl != null) {
                                    _state.value = _state.value.copy(openBrowserUrl = execResult.openBrowserUrl)
                                }

                                val newSources2 = execResult.addedSources.filter { it.isNotBlank() && !usedUrls.contains(it) }
                                if (newSources2.isNotEmpty()) {
                                    val s = store.getSession(sessionId)
                                    newSources2.forEach { url ->
                                        if (s != null && s.sources.none { it.url == url }) {
                                            store.addSource(sessionId, SourceType.URL, title = url, url = url, content = "")
                                        }
                                    }
                                }

                                val taggedSources = execResult.addedSources.mapNotNull { url ->
                                    if (url.isBlank()) return@mapNotNull null
                                    sourceTagIdx++
                                    "S$sourceTagIdx"
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
                                toolMessages.add(ChatMessage(
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
                        toolMessages.add(ChatMessage(
                            role = Role.ASSISTANT,
                            content = result.content,
                            createdAt = System.currentTimeMillis(),
                            apiToolCallsJson = tcJsonArr.toString(),
                            reasoningParts = if (cleanReasoning.isNotEmpty()) {
                                listOf(ReasoningPart(text = cleanReasoning, endTime = System.currentTimeMillis()))
                            } else emptyList(),
                        ))
                    }

                    if (cancelled.get()) return@launch
                }

                val rawContent = top.hsyscn.opedrgent.utils.ToolCallParser.stripAllTags(finalContent).trim()
                val cleanFinal = top.hsyscn.opedrgent.utils.StringUtils.sanitizeJsonNull(rawContent).let { if (it.isEmpty()) finalContent.trim() else it }
                val displayContent = cleanFinal.ifEmpty { finalContent.trim() }

                val cleanReasoning = top.hsyscn.opedrgent.utils.StringUtils.sanitizeJsonNull(finalReasoning)
                val reasoningParts = if (cleanReasoning.isNotEmpty()) {
                    listOf(ReasoningPart(text = cleanReasoning, endTime = System.currentTimeMillis()))
                } else emptyList()

                store.addMessage(
                    sessionId, Role.ASSISTANT, displayContent,
                    toolParts = allToolParts,
                    reasoningParts = reasoningParts,
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
                    tts.speak(
                        text = displayContent,
                        localeTag = apiSettings.getTtsLocaleTag(),
                        rate = apiSettings.getTtsRate(),
                        pitch = apiSettings.getTtsPitch(),
                        mimoVoice = apiSettings.getTtsMimoVoice(),
                    )
                    _state.value = _state.value.copy(isSpeaking = true)
                }

                DebugLog.i("runModel: complete, final=${displayContent.length} chars, tools=${allToolParts.size}")
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
        tts.shutdown()
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
        val recentMessages = compressed.recentMessages

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

                store.addMessage(sessionId, Role.ASSISTANT, accumulatedText)

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
                    store.addMessage(sessionId, Role.ASSISTANT, accumulatedText)
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

    fun saveTts(enabled: Boolean, autoSpeak: Boolean, rate: Float, pitch: Float, localeTag: String, mimoEnabled: Boolean, mimoVoice: String) {
        apiSettings.saveTts(enabled = enabled, autoSpeak = autoSpeak, rate = rate, pitch = pitch, localeTag = localeTag, mimoEnabled = mimoEnabled, mimoVoice = mimoVoice)
    }

    fun saveSttEnabled(enabled: Boolean) {
        apiSettings.saveSttEnabled(enabled)
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
        _sttProgress.value = SttProgressState.IDLE
        _sttResult.value = null
        _sttError.value = null

        sttJob = viewModelScope.launch {
            try {
                val context = getApplication<Application>()

                val (isValid, errorMsg) = AudioProcessor.validateAudioFile(context, uri)
                if (!isValid) {
                    _sttProgress.value = SttProgressState.ERROR
                    _sttError.value = errorMsg ?: "音频文件验证失败"
                    return@launch
                }

                val recommendedModel = ModelManager.getRecommendedModel(context)
                if (!ModelManager.isModelDownloaded(context, recommendedModel)) {
                    _sttProgress.value = SttProgressState.DOWNLOADING_MODEL
                    ModelManager.downloadModel(context, recommendedModel).collect { progress ->
                        if (progress < 0f) {
                            _sttProgress.value = SttProgressState.ERROR
                            _sttError.value = "模型下载失败"
                            return@collect
                        }
                    }
                    if (_sttProgress.value == SttProgressState.ERROR) return@launch
                }

                _sttProgress.value = SttProgressState.EXTRACTING_AUDIO
                val audioMeta = AudioProcessor.getAudioMetadata(context, uri)
                DebugLog.i("STT: 音频元数据 duration=${audioMeta?.durationMs}ms")

                _sttProgress.value = SttProgressState.RECOGNIZING
                val modelDir = ModelManager.getModelPath(context, recommendedModel)
                val engine = SherpaOnnxEngine(context, SttConfig(modelType = recommendedModel))
                sttEngine = engine

                if (modelDir != null) {
                    engine.initialize(modelDir)
                }

                val result = withContext(Dispatchers.IO) { engine.recognizeFile(uri) }
                _sttResult.value = result.copy(modelUsed = recommendedModel.name)
                _sttProgress.value = SttProgressState.DONE
                DebugLog.i("STT: 转录完成 text=${result.text.take(50)}... confidence=${result.confidence}")
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    DebugLog.e("STT: 转录异常 ${e.message}", e)
                    _sttProgress.value = SttProgressState.ERROR
                    _sttError.value = e.message ?: "转录过程发生未知错误"
                }
            } finally {
                sttEngine?.close()
                sttEngine = null
            }
        }
    }

    fun startRealtimeSpeechRecognition() {
        sttJob?.cancel()
        _sttProgress.value = SttProgressState.IDLE
        _sttResult.value = null
        _sttError.value = null

        sttJob = viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                _sttProgress.value = SttProgressState.RECOGNIZING

                val recognizer = AndroidSpeechRecognizer(context, SttConfig(mode = RecognitionMode.STREAMING))
                sttEngine = recognizer

                recognizer.startStreamingRecognition().collect { state ->
                    when (state) {
                        is StreamingRecognitionState.Recognizing -> {
                            _sttResult.value = SttResult(
                                text = state.partialText,
                                engineType = EngineType.ANDROID_SPEECH_RECOGNIZER,
                            )
                        }
                        is StreamingRecognitionState.FinalResult -> {
                            _sttResult.value = SttResult(
                                text = state.text,
                                engineType = EngineType.ANDROID_SPEECH_RECOGNIZER,
                            )
                            _sttProgress.value = SttProgressState.DONE
                        }
                        is StreamingRecognitionState.Error -> {
                            _sttProgress.value = SttProgressState.ERROR
                            _sttError.value = state.message
                        }
                        is StreamingRecognitionState.Listening -> {
                            _sttProgress.value = SttProgressState.RECOGNIZING
                        }
                        is StreamingRecognitionState.Stopped -> {
                            _sttProgress.value = SttProgressState.DONE
                        }
                    }
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    DebugLog.e("STT: 实时录音异常 ${e.message}", e)
                    _sttProgress.value = SttProgressState.ERROR
                    _sttError.value = e.message ?: "实时语音识别发生未知错误"
                }
            } finally {
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
        if (_sttProgress.value != SttProgressState.DONE && _sttProgress.value != SttProgressState.ERROR) {
            _sttProgress.value = SttProgressState.IDLE
        }
    }

    fun copyToClipboard(text: String) {
        try {
            val context = getApplication<Application>()
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("stt_result", text))
            DebugLog.i("STT: 已复制到剪贴板 length=${text.length}")
        } catch (e: Exception) {
            DebugLog.e("STT: 复制到剪贴板失败 ${e.message}", e)
            _sttError.value = "复制失败: ${e.message}"
        }
    }

    fun sendSttResultToLlm() {
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
        val prompt = buildString {
            appendLine("以下是语音转录的结果，请帮我整理并回复：")
            appendLine()
            appendLine("--- 语音转录 ---")
            appendLine(result.text)
            appendLine("--- 结束 ---")
            if (result.durationMs > 0) {
                appendLine()
                appendLine("(音频时长: ${AudioProcessor.formatDuration(result.durationMs)}, 置信度: ${"%.1f".format(result.confidence * 100)}%)")
            }
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

    fun downloadModel(modelType: ModelType): Flow<Float> {
        val context = getApplication<Application>()
        return ModelManager.downloadModel(context, modelType)
    }

    fun getRecommendedModel(): ModelType {
        val context = getApplication<Application>()
        return ModelManager.getRecommendedModel(context)
    }

    fun triggerInsightSprout(text: String, config: SproutConfig?) {
        sproutJob?.cancel()
        _sproutingState.value = SproutingState.IDLE
        _sproutResult.value = null

        sproutJob = viewModelScope.launch {
            try {
                val effectiveConfig = config ?: SproutConfig()
                val sessionId = _state.value.current?.id

                _sproutingState.value = SproutingState.PHASE1

                val engine = InsightSproutEngine { prompt ->
                    val targetSessionId = sessionId ?: run {
                        val newSession = store.createSession("知识发芽 - ${java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))}")
                        refreshSessions()
                        _state.value = _state.value.copy(current = newSession)
                        newSession.id
                    }

                    val tempId = java.util.UUID.randomUUID().toString().take(8)
                    store.addMessage(targetSessionId, Role.USER, "[发芽-$tempId] $prompt")

                    val chatRequest = LlmClient.ChatRequest(
                        messages = listOf(LlmClient.ChatMessage(Role.USER.name.lowercase(), prompt)),
                        temperature = 0.7,
                        maxTokens = when (effectiveConfig.outputLength) {
                            top.hsyscn.opedrgent.insight.SproutOutputLength.SHORT -> 1024
                            top.hsyscn.opedrgent.insight.SproutOutputLength.LONG -> 4096
                            else -> 2048
                        },
                    )

                    var fullResponse = ""
                    llm.chatStream(chatRequest).collect { delta ->
                        when (delta) {
                            is StreamDelta.Content -> fullResponse += delta.text
                            is StreamDelta.Error -> throw RuntimeException(delta.error)
                            else -> {}
                        }
                    }

                    store.addMessage(targetSessionId, Role.ASSISTANT, fullResponse)
                    _state.value = _state.value.copy(current = store.getSession(targetSessionId))
                    refreshSessions()

                    fullResponse
                }

                val result = engine.sprout(text, effectiveConfig)

                for (phase in result.completedPhases) {
                    when (phase) {
                        top.hsyscn.opedrgent.insight.SproutPhase.SEED_EXTRACTION -> _sproutingState.value = SproutingState.PHASE1
                        top.hsyscn.opedrgent.insight.SproutPhase.CROSS_DOMAIN -> _sproutingState.value = SproutingState.PHASE2
                        top.hsyscn.opedrgent.insight.SproutPhase.AHA_INSIGHT -> _sproutingState.value = SproutingState.PHASE3
                        top.hsyscn.opedrgent.insight.SproutPhase.QUOTE_RESONANCE -> _sproutingState.value = SproutingState.PHASE4
                    }
                }

                _sproutResult.value = result.markdownReport
                _sproutingState.value = SproutingState.DONE
                DebugLog.i("Sprout: 发芽完成 phases=${result.completedPhases.size}/4 time=${result.processingTimeMs}ms")
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    DebugLog.e("Sprout: 发芽异常 ${e.message}", e)
                    _sproutingState.value = SproutingState.ERROR
                    _sproutResult.value = "发芽处理失败: ${e.message}"
                }
            }
        }
    }

    fun cancelSprouting() {
        sproutJob?.cancel()
        sproutJob = null
        if (_sproutingState.value != SproutingState.DONE && _sproutingState.value != SproutingState.ERROR) {
            _sproutingState.value = SproutingState.IDLE
        }
    }
}
