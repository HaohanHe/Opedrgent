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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
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
import top.hsyscn.opedrgent.utils.ContextCompressor
import top.hsyscn.opedrgent.utils.DebugLog
import top.hsyscn.opedrgent.tts.TtsPlayer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.json.JSONArray
import android.net.Uri
import android.provider.OpenableColumns
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

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
    val activeQuestion: QuestionPart? = null,
    val isStreaming: Boolean = false,
    val deepThinkingEnabled: Boolean = false,
    val deepResearchEnabled: Boolean = false,
    val isSpeaking: Boolean = false,
    val contextTokenCount: Int = 0,
    val contextCompressionEnabled: Boolean = true,
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

private data class StreamResult(
    val content: String = "",
    val reasoning: String = "",
    val toolCalls: List<top.hsyscn.opedrgent.network.CompletedToolCall> = emptyList(),
    val error: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val http = HttpClients.default
    private val store = ResearchStore(app)
    private val apiSettings = ApiSettings(app)
    private val skillsStore = SkillsStore(app)
    private val memoryStore = MemoryStore(app)
    private val sourceFetcher = SourceFetcher(http)
    private val llm = LlmClient(http)
    private val webSearcher = WebSearcher(http)
    private val webResearchRouter = WebResearchRouter(webSearcher, sourceFetcher)
    private val toolExecutor = ToolExecutor(app, webSearcher, sourceFetcher, llm)
    private val tts = TtsPlayer(app)
    private val automationStore = AutomationStore(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var currentCall: Call? = null
    private var currentRunJob: Job? = null
    private val cancelled = AtomicBoolean(false)

    init {
        DebugLog.enabled = apiSettings.isDebugMode()
        DebugLog.i("MainViewModel init")
        _state.value = _state.value.copy(
            deepThinkingEnabled = apiSettings.isDeepThinking(),
            deepResearchEnabled = apiSettings.isDeepResearch(),
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
        _state.value = _state.value.copy(sessions = store.listSessions())
    }

    fun setSessionSearchQuery(q: String) {
        val query = q.trim()
        val all = store.listSessions()
        if (query.isEmpty()) {
            _state.value = _state.value.copy(sessions = all, sessionSearchQuery = "")
            return
        }
        val lowered = query.lowercase()
        val matchedIds = all.map { it.id }.filter { id ->
            val s = store.getSession(id) ?: return@filter false
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
        }.toSet()
        val filtered = all.filter { matchedIds.contains(it.id) }
        _state.value = _state.value.copy(sessions = filtered, sessionSearchQuery = query)
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
                name = "web_search",
                description = "搜索互联网获取最新信息。当用户询问需要网络查询才能回答的问题时必须使用此工具。参数中 query 为必填，method 可选值: ddg(默认)/webview/provider_native。",
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
                description = "读取并提取指定URL网页的文字内容。",
                parameters = org.json.JSONObject().apply {
                    put("type", "object")
                    put("properties", org.json.JSONObject().apply {
                        put("url", org.json.JSONObject().apply {
                            put("type", "string")
                            put("description", "要读取的网页URL")
                        })
                    })
                    put("required", org.json.JSONArray().apply { put("url") })
                }
            ),
        )
    }

    private fun runModel(sessionId: String, artifactKind: ArtifactKind? = null) {
        cancelled.set(false)
        currentRunJob = viewModelScope.launch {
            setLoading(true)
            try {
                val config = apiSettings.getApiConfig() ?: throw IllegalStateException("请先在设置里填写 API Key")
                val toolMessages = mutableListOf<ChatMessage>()
                val allToolParts = mutableListOf<ToolPart>()
                var finalContent = ""
                var finalReasoning = ""
                val usedUrls = HashSet<String>()
                var sourceTagIdx = 0
                val maxContextTokens = 16000

                for (round in 0 until 10) {
                    if (cancelled.get()) {
                        DebugLog.i("runModel cancelled at round $round")
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

                    DebugLog.d("runModel: round $round, messages=${messages.size}, tokens=${compressed.tokenCount}")

                    val result = withContext(Dispatchers.IO) {
                        streamLlm(config, compressedSystem, messages, tools = agentTools)
                    }

                    if (cancelled.get()) {
                        DebugLog.i("runModel cancelled after streaming round $round")
                        return@launch
                    }

                    if (result.error != null) {
                        throw IllegalStateException(result.error)
                    }

                    finalContent = result.content
                    finalReasoning = result.reasoning

                    if (result.toolCalls.isEmpty()) {
                        DebugLog.i("runModel: no tool_call in response, model is done at round $round")
                        break
                    }

                    val parallelResults = coroutineScope {
                        result.toolCalls.map { tc ->
                            async(Dispatchers.IO) {
                                if (cancelled.get()) return@async null

                                val parsedArgs: Map<String, String> = runCatching {
                                    org.json.JSONObject(tc.arguments).let { json ->
                                        json.keys().asSequence().associateWith { json.opt(it).toString() }
                                    }
                                }.getOrDefault(emptyMap())

                                val tp = ToolPart(
                                    tool = tc.name,
                                    state = ToolState(
                                        status = ToolStateType.PENDING,
                                        input = parsedArgs,
                                        startTime = System.currentTimeMillis(),
                                    ),
                                )

                                val execResult = toolExecutor.execute(tp, config, system, useProviderSearch = isProviderWebSearchEnabled())

                                val newSources = execResult.addedSources.filter { usedUrls.add(it) }
                                if (newSources.isNotEmpty()) {
                                    val s = store.getSession(sessionId)
                                    newSources.forEach { url ->
                                        if (s != null && s.sources.none { it.url == url }) {
                                            store.addSource(sessionId, SourceType.URL, title = url, url = url, content = "")
                                        }
                                    }
                                }

                                Triple(tc.id, execResult, tp)
                            }
                        }.mapNotNull { it.await() }
                    }

                    val orderedResults = parallelResults.sortedBy { triple ->
                         result.toolCalls.indexOfFirst { it.id == triple.first }
                     }

                    for ((tcId, execResult, tp) in orderedResults) {
                        if (cancelled.get()) return@launch

                        val runningTp = tp.copy(state = tp.state.copy(status = ToolStateType.RUNNING))
                        allToolParts.add(runningTp)
                        _state.value = _state.value.copy(streamingToolParts = allToolParts.toList())

                        val doneTp = execResult.toolPart
                        val idx = allToolParts.lastIndex
                        allToolParts[idx] = doneTp
                        _state.value = _state.value.copy(streamingToolParts = allToolParts.toList())
                        refreshSessions()

                        if (execResult.openBrowserUrl != null) {
                            _state.value = _state.value.copy(openBrowserUrl = execResult.openBrowserUrl)
                        }

                        val newSources = execResult.addedSources.filter { it.isNotBlank() && !usedUrls.contains(it) }
                        if (newSources.isNotEmpty()) {
                            val s = store.getSession(sessionId)
                            newSources.forEach { url ->
                                if (s != null && s.sources.none { it.url == url }) {
                                    store.addSource(sessionId, SourceType.URL, title = url, url = url, content = "")
                                }
                            }
                            refreshSessions()
                        }

                        val taggedSources = execResult.addedSources.mapNotNull { url ->
                            if (url.isBlank()) return@mapNotNull null
                            sourceTagIdx++
                            "S$sourceTagIdx"
                        }
                        val sourceTags = if (taggedSources.isNotEmpty()) {
                            "\n[来源: ${taggedSources.joinToString(" ")}]"
                        } else ""

                        val toolOutput = execResult.toolPart.state.output
                            ?: execResult.toolPart.state.error
                            ?: "工具执行完成"
                        toolMessages.add(ChatMessage(
                            role = Role.USER,
                            content = "$toolOutput$sourceTags",
                            createdAt = System.currentTimeMillis(),
                            toolCallId = tcId,
                        ))
                    }

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
                        toolMessages.add(ChatMessage(
                            role = Role.ASSISTANT,
                            content = result.content,
                            createdAt = System.currentTimeMillis(),
                            apiToolCallsJson = tcJsonArr.toString(),
                        ))
                    }

                    if (cancelled.get()) return@launch
                }

                val cleanFinal = top.hsyscn.opedrgent.utils.ToolCallParser.stripAllTags(finalContent).trim()
                val displayContent = cleanFinal.ifEmpty { finalContent.trim() }

                val reasoningParts = if (finalReasoning.isNotEmpty()) {
                    listOf(ReasoningPart(text = finalReasoning, endTime = System.currentTimeMillis()))
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

    private suspend fun streamLlm(
        config: top.hsyscn.opedrgent.settings.ApiConfig,
        system: String,
        messages: List<ChatMessage>,
        tools: List<top.hsyscn.opedrgent.network.ToolDefinition> = emptyList(),
    ): StreamResult = withContext(Dispatchers.IO) {
        val contentBuilder = StringBuilder()
        val reasoningBuilder = StringBuilder()
        val ctx = currentCoroutineContext()

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
                            onDelta = { delta ->
                                if (delta.content.isNotEmpty()) {
                                    contentBuilder.append(delta.content)
                                    launch(Dispatchers.Main) {
                                        _state.value = _state.value.copy(
                                            streamingText = contentBuilder.toString(),
                                            isStreaming = true,
                                        )
                                    }
                                }
                                if (delta.reasoning.isNotEmpty()) {
                                    reasoningBuilder.append(delta.reasoning)
                                    launch(Dispatchers.Main) {
                                        _state.value = _state.value.copy(
                                            streamingReasoning = reasoningBuilder.toString(),
                                        )
                                    }
                                }
                            },
                            onToolCallDelta = { tc ->
                                launch(Dispatchers.Main) {
                                    _state.value = _state.value.copy(
                                        streamingText = contentBuilder.toString() +
                                            "\n[调用工具: ${tc.nameDelta.ifEmpty { tc.argsDelta.take(20) }}...]",
                                    )
                                }
                            },
                            onDone = { result ->
                                if (!completed) {
                                    completed = true
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
                                    val partial = contentBuilder.toString().trim()
                                    if (partial.isNotEmpty()) {
                                        continuation.resumeWith(Result.success(StreamResult(
                                            content = partial,
                                            reasoning = reasoningBuilder.toString(),
                                        )))
                                    } else {
                                        continuation.resumeWith(Result.success(StreamResult(error = err)))
                                    }
                                }
                            },
                        )
                        currentCall = call
                    } catch (e: Exception) {
                        if (!completed) {
                            completed = true
                            continuation.resumeWith(Result.success(StreamResult(error = e.message ?: "连接失败")))
                        }
                    }
                }
                continuation.invokeOnCancellation {
                    job.cancel()
                    currentCall?.cancel()
                }
            } catch (e: Exception) {
                if (!completed) {
                    completed = true
                    continuation.resumeWith(Result.success(StreamResult(error = e.message ?: "连接失败")))
                }
            }
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
    fun hasApiKey(): Boolean = apiSettings.hasApiKey()
    fun getMemory(): String = apiSettings.getMemory()
    fun saveMemory(memory: String) {
        apiSettings.saveMemory(memory)
    }

    fun isTtsEnabled(): Boolean = apiSettings.isTtsEnabled()
    fun isTtsAutoSpeak(): Boolean = apiSettings.isTtsAutoSpeak()
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

    fun saveTts(enabled: Boolean, autoSpeak: Boolean, rate: Float, pitch: Float, localeTag: String) {
        apiSettings.saveTts(enabled = enabled, autoSpeak = autoSpeak, rate = rate, pitch = pitch, localeTag = localeTag)
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

    fun saveDebugMode(enabled: Boolean) {
        apiSettings.saveDebugMode(enabled)
    }

    fun saveDeepThinking(enabled: Boolean) {
        apiSettings.saveDeepThinking(enabled)
    }

    fun isProviderWebSearchEnabled(): Boolean = apiSettings.isProviderWebSearchEnabled()

    fun saveProviderWebSearchEnabled(enabled: Boolean) {
        apiSettings.saveProviderWebSearchEnabled(enabled)
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
        return PromptBuilder.buildSystemPrompt(apiSettings, session, memoryStore, envInfo)
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

    fun getPackageNameForShare(context: Context): String = context.packageName
}
