package top.hsyscn.opedrgent.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import top.hsyscn.opedrgent.model.Artifact
import top.hsyscn.opedrgent.model.ArtifactKind
import top.hsyscn.opedrgent.model.ChatMessage
import top.hsyscn.opedrgent.model.QuestionOption
import top.hsyscn.opedrgent.model.QuestionPart
import top.hsyscn.opedrgent.model.ReasoningPart
import top.hsyscn.opedrgent.model.ResearchSession
import top.hsyscn.opedrgent.model.Role
import top.hsyscn.opedrgent.model.SessionSummary
import top.hsyscn.opedrgent.model.Source
import top.hsyscn.opedrgent.model.SourceType
import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.model.ToolState
import top.hsyscn.opedrgent.model.ToolStateType
import java.io.File

class ResearchStore(context: Context) {
    private val file = File(context.filesDir, "research_store.json")
    private val lock = Any()

    fun listSessions(): List<SessionSummary> {
        val sessions = loadAll().map { s ->
            SessionSummary(id = s.id, title = s.title, updatedAt = s.updatedAt)
        }
        return sessions.sortedByDescending { it.updatedAt }
    }

    fun getSession(sessionId: String): ResearchSession? {
        return loadAll().firstOrNull { it.id == sessionId }
    }

    fun createSession(title: String): ResearchSession {
        val now = System.currentTimeMillis()
        val session = ResearchSession(
            title = title.trim().ifEmpty { "未命名研究" },
            createdAt = now,
            updatedAt = now,
            sources = emptyList(),
            messages = emptyList(),
            artifacts = emptyList(),
            notes = "",
        )
        updateSession(session)
        return session
    }

    fun addSource(
        sessionId: String,
        type: SourceType,
        title: String?,
        url: String?,
        content: String,
    ): ResearchSession? {
        val session = getSession(sessionId) ?: return null
        val now = System.currentTimeMillis()
        val next = session.copy(
            updatedAt = now,
            sources = session.sources + Source(
                type = type,
                title = title?.takeIf { it.isNotBlank() },
                url = url?.takeIf { it.isNotBlank() },
                content = content,
                includeInContext = true,
                createdAt = now,
            ),
        )
        updateSession(next)
        return next
    }

    fun addMessage(
        sessionId: String,
        role: Role,
        content: String,
        toolParts: List<top.hsyscn.opedrgent.model.ToolPart> = emptyList(),
        reasoningParts: List<top.hsyscn.opedrgent.model.ReasoningPart> = emptyList(),
        questionPart: top.hsyscn.opedrgent.model.QuestionPart? = null,
    ): ResearchSession? {
        val session = getSession(sessionId) ?: return null
        val now = System.currentTimeMillis()
        val next = session.copy(
            updatedAt = now,
            messages = session.messages + ChatMessage(
                role = role,
                content = content,
                createdAt = now,
                toolParts = toolParts,
                reasoningParts = reasoningParts,
                questionPart = questionPart,
            ),
        )
        updateSession(next)
        return next
    }

    fun addArtifact(sessionId: String, kind: ArtifactKind, content: String): ResearchSession? {
        val session = getSession(sessionId) ?: return null
        val now = System.currentTimeMillis()
        val next = session.copy(
            updatedAt = now,
            artifacts = session.artifacts + Artifact(kind = kind, content = content, createdAt = now),
        )
        updateSession(next)
        return next
    }

    fun setNotes(sessionId: String, notes: String): ResearchSession? {
        val session = getSession(sessionId) ?: return null
        val now = System.currentTimeMillis()
        val next = session.copy(updatedAt = now, notes = notes)
        updateSession(next)
        return next
    }

    fun setSourceIncluded(sessionId: String, sourceId: String, included: Boolean): ResearchSession? {
        val session = getSession(sessionId) ?: return null
        val idx = session.sources.indexOfFirst { it.id == sourceId }
        if (idx < 0) return session
        val now = System.currentTimeMillis()
        val nextSources = session.sources.toMutableList()
        nextSources[idx] = nextSources[idx].copy(includeInContext = included)
        val next = session.copy(updatedAt = now, sources = nextSources)
        updateSession(next)
        return next
    }

    fun removeSource(sessionId: String, sourceId: String): ResearchSession? {
        val session = getSession(sessionId) ?: return null
        val now = System.currentTimeMillis()
        val nextSources = session.sources.filter { it.id != sourceId }
        val next = session.copy(updatedAt = now, sources = nextSources)
        updateSession(next)
        return next
    }

    fun updateSession(session: ResearchSession) {
        synchronized(lock) {
            val all = loadAllInternal().toMutableList()
            val idx = all.indexOfFirst { it.id == session.id }
            if (idx >= 0) {
                all[idx] = session
            } else {
                all.add(session)
            }
            saveAllInternal(all)
        }
    }

    private fun loadAll(): List<ResearchSession> {
        synchronized(lock) {
            return loadAllInternal()
        }
    }

    private fun loadAllInternal(): List<ResearchSession> {
        if (!file.exists()) return emptyList()
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return emptyList()
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val sessions = root.optJSONArray("sessions") ?: return emptyList()
        return (0 until sessions.length()).mapNotNull { i ->
            parseSession(sessions.optJSONObject(i) ?: return@mapNotNull null)
        }
    }

    private fun saveAllInternal(sessions: List<ResearchSession>) {
        val root = JSONObject()
        val arr = JSONArray()
        sessions.forEach { arr.put(serializeSession(it)) }
        root.put("sessions", arr)
        file.writeText(root.toString(), Charsets.UTF_8)
    }

    private fun parseSession(obj: JSONObject): ResearchSession? {
        val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return null
        val title = obj.optString("title").ifEmpty { "未命名研究" }
        val createdAt = obj.optLong("createdAt", 0L)
        val updatedAt = obj.optLong("updatedAt", createdAt)
        val sources = parseSources(obj.optJSONArray("sources"))
        val messages = parseMessages(obj.optJSONArray("messages"))
        val artifacts = parseArtifacts(obj.optJSONArray("artifacts"))
        val notes = obj.optString("notes", "")
        return ResearchSession(
            id = id,
            title = title,
            createdAt = createdAt,
            updatedAt = updatedAt,
            sources = sources,
            messages = messages,
            artifacts = artifacts,
            notes = notes,
        )
    }

    private fun parseSources(arr: JSONArray?): List<Source> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val type = runCatching { SourceType.valueOf(o.optString("type")) }.getOrNull() ?: return@mapNotNull null
            val content = o.optString("content")
            Source(
                id = id,
                type = type,
                title = o.optString("title").takeIf { it.isNotBlank() },
                url = o.optString("url").takeIf { it.isNotBlank() },
                content = content,
                includeInContext = o.optBoolean("includeInContext", true),
                createdAt = o.optLong("createdAt", 0L),
            )
        }
    }

    private fun parseMessages(arr: JSONArray?): List<ChatMessage> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val role = runCatching { Role.valueOf(o.optString("role")) }.getOrNull() ?: return@mapNotNull null
            val content = o.optString("content")

            val reasoningParts = o.optJSONArray("reasoningParts")?.let { arr ->
                (0 until arr.length()).mapNotNull { j ->
                    val rp = arr.optJSONObject(j) ?: return@mapNotNull null
                    ReasoningPart(
                        id = rp.optString("id"),
                        text = rp.optString("text"),
                        startTime = rp.optLong("startTime", 0L),
                        endTime = rp.optLong("endTime", 0L),
                    )
                }
            } ?: emptyList()

            val toolParts = o.optJSONArray("toolParts")?.let { arr ->
                (0 until arr.length()).mapNotNull { j ->
                    val tp = arr.optJSONObject(j) ?: return@mapNotNull null
                    val stateObj = tp.optJSONObject("state") ?: return@mapNotNull null
                    val tpStateType = runCatching { ToolStateType.valueOf(stateObj.optString("status")) }.getOrNull() ?: return@mapNotNull null
                    val inputMap = mutableMapOf<String, String>()
                    stateObj.optJSONObject("input")?.let { inp ->
                        inp.keys().forEach { key -> inputMap[key] = inp.optString(key) }
                    }
                    val tpQp = tp.optJSONObject("questionPart")?.let { qp ->
                        val qpOptsArray = qp.optJSONArray("options")
                        val qpOpts = if (qpOptsArray != null) {
                            (0 until qpOptsArray.length()).mapNotNull { k ->
                                qpOptsArray.optJSONObject(k)?.let { opt ->
                                    QuestionOption(value = opt.optString("value"), label = opt.optString("label"))
                                }
                            }
                        } else { emptyList() }
                        QuestionPart(
                            id = qp.optString("id"),
                            prompt = qp.optString("prompt"),
                            multiSelect = qp.optBoolean("multiSelect", false),
                            options = qpOpts,
                            answer = qp.optString("answer").ifBlank { null },
                        )
                    }
                    ToolPart(
                        id = tp.optString("id"),
                        tool = tp.optString("tool"),
                        state = ToolState(
                            status = tpStateType,
                            input = inputMap,
                            output = stateObj.optString("output").ifBlank { null },
                            error = stateObj.optString("error").ifBlank { null },
                            startTime = stateObj.optLong("startTime", 0L),
                            endTime = stateObj.optLong("endTime", 0L),
                        ),
                        questionPart = tpQp,
                    )
                }
            } ?: emptyList()

            val questionPartOptionsArray = o.optJSONObject("questionPart")?.optJSONArray("options")
            val questionPartOptions = if (questionPartOptionsArray != null) {
                (0 until questionPartOptionsArray.length()).mapNotNull { k ->
                    questionPartOptionsArray.optJSONObject(k)?.let { opt ->
                        QuestionOption(value = opt.optString("value"), label = opt.optString("label"))
                    }
                }
            } else {
                emptyList()
            }
            val questionPart = o.optJSONObject("questionPart")?.let { qp ->
                QuestionPart(
                    id = qp.optString("id"),
                    prompt = qp.optString("prompt"),
                    multiSelect = qp.optBoolean("multiSelect", false),
                    options = questionPartOptions,
                    answer = qp.optString("answer").ifBlank { null },
                )
            }

            ChatMessage(
                id = id,
                role = role,
                content = content,
                createdAt = o.optLong("createdAt", 0L),
                toolParts = toolParts,
                reasoningParts = reasoningParts,
                questionPart = questionPart,
                toolCallId = o.optString("toolCallId").ifBlank { null },
                apiToolCallsJson = o.optString("apiToolCallsJson").ifBlank { null },
            )
        }
    }

    private fun parseArtifacts(arr: JSONArray?): List<Artifact> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val kind = runCatching { ArtifactKind.valueOf(o.optString("kind")) }.getOrNull() ?: return@mapNotNull null
            val content = o.optString("content")
            Artifact(
                id = id,
                kind = kind,
                content = content,
                createdAt = o.optLong("createdAt", 0L),
            )
        }
    }

    private fun serializeSession(session: ResearchSession): JSONObject {
        val obj = JSONObject()
        obj.put("id", session.id)
        obj.put("title", session.title)
        obj.put("createdAt", session.createdAt)
        obj.put("updatedAt", session.updatedAt)
        obj.put("sources", JSONArray().apply { session.sources.forEach { put(serializeSource(it)) } })
        obj.put("messages", JSONArray().apply { session.messages.forEach { put(serializeMessage(it)) } })
        obj.put("artifacts", JSONArray().apply { session.artifacts.forEach { put(serializeArtifact(it)) } })
        obj.put("notes", session.notes)
        return obj
    }

    private fun serializeSource(source: Source): JSONObject {
        val obj = JSONObject()
        obj.put("id", source.id)
        obj.put("type", source.type.name)
        obj.put("title", source.title)
        obj.put("url", source.url)
        obj.put("content", source.content)
        obj.put("includeInContext", source.includeInContext)
        obj.put("createdAt", source.createdAt)
        return obj
    }

    private fun serializeMessage(msg: ChatMessage): JSONObject {
        val obj = JSONObject()
        obj.put("id", msg.id)
        obj.put("role", msg.role.name)
        obj.put("content", msg.content)
        obj.put("createdAt", msg.createdAt)
        obj.put("toolCallId", msg.toolCallId ?: JSONObject.NULL)
        obj.put("apiToolCallsJson", msg.apiToolCallsJson ?: JSONObject.NULL)
        if (msg.reasoningParts.isNotEmpty()) {
            obj.put("reasoningParts", JSONArray().apply {
                msg.reasoningParts.forEach { rp ->
                    put(JSONObject().apply {
                        put("id", rp.id)
                        put("text", rp.text)
                        put("startTime", rp.startTime)
                        put("endTime", rp.endTime)
                    })
                }
            })
        }
        if (msg.toolParts.isNotEmpty()) {
            obj.put("toolParts", JSONArray().apply {
                msg.toolParts.forEach { tp ->
                    put(JSONObject().apply {
                        put("id", tp.id)
                        put("tool", tp.tool)
                        put("state", JSONObject().apply {
                            put("status", tp.state.status.name)
                            put("input", JSONObject(tp.state.input))
                            put("output", tp.state.output ?: JSONObject.NULL)
                            put("error", tp.state.error ?: JSONObject.NULL)
                            put("startTime", tp.state.startTime)
                            put("endTime", tp.state.endTime)
                        })
                        tp.questionPart?.let { qp ->
                            put("questionPart", JSONObject().apply {
                                put("id", qp.id)
                                put("prompt", qp.prompt)
                                put("multiSelect", qp.multiSelect)
                                put("options", JSONArray().apply {
                                    qp.options.forEach { opt ->
                                        put(JSONObject().apply {
                                            put("value", opt.value)
                                            put("label", opt.label)
                                        })
                                    }
                                })
                                put("answer", qp.answer ?: JSONObject.NULL)
                            })
                        }
                    })
                }
            })
        }
        msg.questionPart?.let { qp ->
            obj.put("questionPart", JSONObject().apply {
                put("id", qp.id)
                put("prompt", qp.prompt)
                put("multiSelect", qp.multiSelect)
                put("options", JSONArray().apply {
                    qp.options.forEach { opt ->
                        put(JSONObject().apply {
                            put("value", opt.value)
                            put("label", opt.label)
                        })
                    }
                })
                put("answer", qp.answer ?: JSONObject.NULL)
            })
        }
        return obj
    }

    private fun serializeArtifact(artifact: Artifact): JSONObject {
        val obj = JSONObject()
        obj.put("id", artifact.id)
        obj.put("kind", artifact.kind.name)
        obj.put("content", artifact.content)
        obj.put("createdAt", artifact.createdAt)
        return obj
    }
}
