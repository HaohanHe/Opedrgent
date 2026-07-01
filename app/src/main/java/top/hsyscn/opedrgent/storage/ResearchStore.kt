package top.hsyscn.opedrgent.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import top.hsyscn.opedrgent.model.Artifact
import top.hsyscn.opedrgent.model.ArtifactKind
import top.hsyscn.opedrgent.model.ChatMessage
import top.hsyscn.opedrgent.model.GuardrailSnapshot
import top.hsyscn.opedrgent.model.MessagePart
import top.hsyscn.opedrgent.model.QuestionOption
import top.hsyscn.opedrgent.model.QuestionPart
import top.hsyscn.opedrgent.model.ReasoningPart
import top.hsyscn.opedrgent.model.ResearchCheckpoint
import top.hsyscn.opedrgent.model.ResearchSession
import top.hsyscn.opedrgent.model.Role
import top.hsyscn.opedrgent.model.SessionSummary
import top.hsyscn.opedrgent.model.Source
import top.hsyscn.opedrgent.model.SourceType
import top.hsyscn.opedrgent.model.ToolCallRecord
import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.model.ToolState
import top.hsyscn.opedrgent.model.ToolStateType
import top.hsyscn.opedrgent.network.ToolExecutionStatus
import java.io.File

class ResearchStore(context: Context) {
    private val file = File(context.filesDir, "research_store.json")
    private val checkpointDir = File(context.filesDir, "research_checkpoints").apply { mkdirs() }
    private val lock = Any()
    private var sessions: List<ResearchSession>? = null

    private fun checkpointFile(sessionId: String): File = File(checkpointDir, "$sessionId.json")

    private fun ensureLoaded(): List<ResearchSession> {
        val cached = sessions
        if (cached != null) return cached
        val loaded = loadAllInternal()
        sessions = loaded
        return loaded
    }

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
        synchronized(lock) {
            val all = ensureLoaded().toMutableList()
            all.add(session)
            saveAllInternal(all)
            sessions = all
        }
        return session
    }

    fun deleteSession(sessionId: String): Boolean {
        synchronized(lock) {
            val all = ensureLoaded().toMutableList()
            val idx = all.indexOfFirst { it.id == sessionId }
            if (idx < 0) return false
            all.removeAt(idx)
            saveAllInternal(all)
            sessions = all
            deleteCheckpoint(sessionId)
            return true
        }
    }

    fun renameSession(sessionId: String, newTitle: String): Boolean {
        synchronized(lock) {
            val all = ensureLoaded().toMutableList()
            val idx = all.indexOfFirst { it.id == sessionId }
            if (idx < 0) return false
            val updated = all[idx].copy(
                title = newTitle.trim().ifEmpty { all[idx].title },
                updatedAt = System.currentTimeMillis(),
            )
            all[idx] = updated
            saveAllInternal(all)
            sessions = all
            return true
        }
    }

    fun addSource(
        sessionId: String,
        type: SourceType,
        title: String?,
        url: String?,
        content: String,
    ): ResearchSession? {
        synchronized(lock) {
            val all = ensureLoaded().toMutableList()
            val idx = all.indexOfFirst { it.id == sessionId }
            if (idx < 0) return null
            val session = all[idx]
            val now = System.currentTimeMillis()
            val source = Source(
                type = type,
                title = title?.takeIf { it.isNotBlank() },
                url = url?.takeIf { it.isNotBlank() },
                content = content,
                includeInContext = true,
                createdAt = now,
            )
            val next = session.copy(
                updatedAt = now,
                sources = session.sources + source,
            )
            appendToSession(sessionId, "sources", serializeSource(source), now)
            all[idx] = next
            sessions = all
            return next
        }
    }

    fun addMessage(
        sessionId: String,
        role: Role,
        content: String,
        toolParts: List<top.hsyscn.opedrgent.model.ToolPart> = emptyList(),
        reasoningParts: List<top.hsyscn.opedrgent.model.ReasoningPart> = emptyList(),
        questionPart: top.hsyscn.opedrgent.model.QuestionPart? = null,
        parts: List<MessagePart> = emptyList(),
        isUserAction: Boolean = false,
    ): ResearchSession? {
        synchronized(lock) {
            val all = ensureLoaded().toMutableList()
            val idx = all.indexOfFirst { it.id == sessionId }
            if (idx < 0) return null
            val session = all[idx]
            val now = System.currentTimeMillis()
            val roundIndex = calculateRoundIndex(session, role, toolCallId = null)
            val message = ChatMessage(
                role = role,
                content = content,
                createdAt = now,
                toolParts = toolParts,
                reasoningParts = reasoningParts,
                questionPart = questionPart,
                parts = parts,
                isUserAction = isUserAction,
                roundIndex = roundIndex,
            )
            val next = session.copy(
                updatedAt = now,
                messages = session.messages + message,
            )
            appendToSession(sessionId, "messages", serializeMessage(message), now)
            all[idx] = next
            sessions = all
            return next
        }
    }

    /**
     * 按轮次范围查询会话消息（含边界）。
     *
     * @param sessionId 会话 ID
     * @param startRound 起始轮次（包含）
     * @param endRound 结束轮次（包含）
     */
    fun getMessagesByRounds(
        sessionId: String,
        startRound: Int,
        endRound: Int,
    ): List<ChatMessage> {
        val session = getSession(sessionId) ?: return emptyList()
        return session.messages.filter { it.roundIndex in startRound..endRound }
    }

    /**
     * 计算新消息应写入的 roundIndex。
     * 规则：user 消息（且非工具结果）开始新一轮；其余消息继承最后一个 user 轮的 roundIndex。
     */
    private fun calculateRoundIndex(
        session: ResearchSession,
        role: Role,
        toolCallId: String?,
    ): Int {
        val lastBoundaryRound = session.messages
            .lastOrNull { it.role == Role.USER && it.toolCallId == null }
            ?.roundIndex
        return when {
            role == Role.USER && toolCallId == null -> (lastBoundaryRound ?: -1) + 1
            lastBoundaryRound != null -> lastBoundaryRound
            else -> 0
        }
    }

    fun addArtifact(sessionId: String, kind: ArtifactKind, content: String): ResearchSession? {
        synchronized(lock) {
            val all = ensureLoaded().toMutableList()
            val idx = all.indexOfFirst { it.id == sessionId }
            if (idx < 0) return null
            val session = all[idx]
            val now = System.currentTimeMillis()
            val artifact = Artifact(kind = kind, content = content, createdAt = now)
            val next = session.copy(
                updatedAt = now,
                artifacts = session.artifacts + artifact,
            )
            appendToSession(sessionId, "artifacts", serializeArtifact(artifact), now)
            all[idx] = next
            sessions = all
            return next
        }
    }

    fun setNotes(sessionId: String, notes: String): ResearchSession? {
        synchronized(lock) {
            val all = ensureLoaded().toMutableList()
            val idx = all.indexOfFirst { it.id == sessionId }
            if (idx < 0) return null
            val session = all[idx]
            val next = session.copy(updatedAt = System.currentTimeMillis(), notes = notes)
            all[idx] = next
            saveAllInternal(all)
            sessions = all
            return next
        }
    }

    fun setSourceIncluded(sessionId: String, sourceId: String, included: Boolean): ResearchSession? {
        synchronized(lock) {
            val all = ensureLoaded().toMutableList()
            val idx = all.indexOfFirst { it.id == sessionId }
            if (idx < 0) return null
            val session = all[idx]
            val sourceIdx = session.sources.indexOfFirst { it.id == sourceId }
            if (sourceIdx < 0) return session
            val now = System.currentTimeMillis()
            val nextSources = session.sources.toMutableList()
            nextSources[sourceIdx] = nextSources[sourceIdx].copy(includeInContext = included)
            val next = session.copy(updatedAt = now, sources = nextSources)
            all[idx] = next
            saveAllInternal(all)
            sessions = all
            return next
        }
    }

    fun removeSource(sessionId: String, sourceId: String): ResearchSession? {
        synchronized(lock) {
            val all = ensureLoaded().toMutableList()
            val idx = all.indexOfFirst { it.id == sessionId }
            if (idx < 0) return null
            val session = all[idx]
            val now = System.currentTimeMillis()
            val nextSources = session.sources.filter { it.id != sourceId }
            val next = session.copy(updatedAt = now, sources = nextSources)
            all[idx] = next
            saveAllInternal(all)
            sessions = all
            return next
        }
    }

    private fun updateSessionInternal(session: ResearchSession) {
        val all = ensureLoaded().toMutableList()
        val idx = all.indexOfFirst { it.id == session.id }
        if (idx >= 0) {
            all[idx] = session
        } else {
            all.add(session)
        }
        saveAllInternal(all)
        sessions = all
    }

    fun updateSession(session: ResearchSession) {
        synchronized(lock) {
            updateSessionInternal(session)
        }
    }

    // ==================== Agent 循环检查点 ====================

    fun saveCheckpoint(checkpoint: ResearchCheckpoint) {
        val file = checkpointFile(checkpoint.sessionId)
        synchronized(lock) {
            file.writeText(serializeCheckpoint(checkpoint).toString(), Charsets.UTF_8)
        }
    }

    fun loadCheckpoint(sessionId: String): ResearchCheckpoint? {
        val file = checkpointFile(sessionId)
        synchronized(lock) {
            if (!file.exists()) return null
            val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
            val obj = runCatching { JSONObject(text) }.getOrNull() ?: return null
            return parseCheckpoint(obj)
        }
    }

    fun deleteCheckpoint(sessionId: String): Boolean {
        val file = checkpointFile(sessionId)
        synchronized(lock) {
            return file.exists() && file.delete()
        }
    }

    private fun loadAll(): List<ResearchSession> {
        synchronized(lock) {
            return ensureLoaded()
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

    // ==================== 增量追加写入辅助方法 ====================

    private fun loadRootJson(): JSONObject {
        if (!file.exists()) {
            return JSONObject().apply { put("sessions", JSONArray()) }
        }
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: ""
        if (text.isBlank()) {
            return JSONObject().apply { put("sessions", JSONArray()) }
        }
        return runCatching { JSONObject(text) }.getOrNull()
            ?: JSONObject().apply { put("sessions", JSONArray()) }
    }

    private fun saveRootJson(root: JSONObject) {
        file.writeText(root.toString(), Charsets.UTF_8)
    }

    private fun findSessionIndexInRoot(root: JSONObject, sessionId: String): Int {
        val arr = root.optJSONArray("sessions") ?: return -1
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("id") == sessionId) return i
        }
        return -1
    }

    private fun appendToSession(sessionId: String, arrayKey: String, item: JSONObject, now: Long) {
        val root = loadRootJson()
        val sessionIdx = findSessionIndexInRoot(root, sessionId)
        if (sessionIdx < 0) {
            saveAllInternal(ensureLoaded())
            return
        }
        val sessionObj = root.getJSONArray("sessions").getJSONObject(sessionIdx)
        val arr = sessionObj.optJSONArray(arrayKey) ?: JSONArray()
        arr.put(item)
        sessionObj.put(arrayKey, arr)
        sessionObj.put("updatedAt", now)
        saveRootJson(root)
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
                parts = parseParts(o.optJSONArray("parts")),
                isUserAction = o.optBoolean("isUserAction", false),
                roundIndex = o.optInt("roundIndex", 0),
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
        obj.put("roundIndex", msg.roundIndex)
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
        if (msg.parts.isNotEmpty()) {
            obj.put("parts", serializeParts(msg.parts))
        }
        if (msg.isUserAction) {
            obj.put("isUserAction", true)
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

    // ==================== Agent 检查点序列化 ====================

    private fun serializeCheckpoint(checkpoint: ResearchCheckpoint): JSONObject {
        val obj = JSONObject()
        obj.put("sessionId", checkpoint.sessionId)
        obj.put("round", checkpoint.round)
        obj.put("accumulatedText", checkpoint.accumulatedText)
        obj.put("accumulatedReasoning", checkpoint.accumulatedReasoning)
        obj.put("toolMessages", JSONArray().apply {
            checkpoint.toolMessages.forEach { put(serializeMessage(it)) }
        })
        obj.put("sources", JSONArray().apply {
            checkpoint.sources.forEach { put(serializeSource(it)) }
        })
        obj.put("guardrailSnapshot", serializeGuardrailSnapshot(checkpoint.guardrailSnapshot))
        obj.put("haltReason", checkpoint.haltReason ?: JSONObject.NULL)
        obj.put("timestamp", checkpoint.timestamp)
        return obj
    }

    private fun parseCheckpoint(obj: JSONObject): ResearchCheckpoint? {
        val sessionId = obj.optString("sessionId").takeIf { it.isNotBlank() } ?: return null
        val round = obj.optInt("round", 0)
        val accumulatedText = obj.optString("accumulatedText", "")
        val accumulatedReasoning = obj.optString("accumulatedReasoning", "")
        val toolMessages = parseMessages(obj.optJSONArray("toolMessages"))
        val sources = parseSources(obj.optJSONArray("sources"))
        val guardrailSnapshot = obj.optJSONObject("guardrailSnapshot")?.let { parseGuardrailSnapshot(it) }
            ?: GuardrailSnapshot(0, emptyMap(), emptyList())
        val haltReason = obj.optString("haltReason").takeIf { it.isNotBlank() }
        val timestamp = obj.optLong("timestamp", System.currentTimeMillis())
        return ResearchCheckpoint(
            sessionId = sessionId,
            round = round,
            accumulatedText = accumulatedText,
            accumulatedReasoning = accumulatedReasoning,
            toolMessages = toolMessages,
            sources = sources,
            guardrailSnapshot = guardrailSnapshot,
            haltReason = haltReason,
            timestamp = timestamp,
        )
    }

    private fun serializeGuardrailSnapshot(snapshot: GuardrailSnapshot): JSONObject {
        val obj = JSONObject()
        obj.put("consecutiveFailures", snapshot.consecutiveFailures)
        obj.put("toolFailureCounts", JSONObject().apply {
            snapshot.toolFailureCounts.forEach { (tool, count) -> put(tool, count) }
        })
        obj.put("recentToolCalls", JSONArray().apply {
            snapshot.recentToolCalls.forEach { put(serializeToolCallRecord(it)) }
        })
        return obj
    }

    private fun parseGuardrailSnapshot(obj: JSONObject): GuardrailSnapshot {
        val consecutiveFailures = obj.optInt("consecutiveFailures", 0)
        val toolFailureCounts = mutableMapOf<String, Int>()
        obj.optJSONObject("toolFailureCounts")?.let { counts ->
            counts.keys().forEach { key ->
                toolFailureCounts[key] = counts.optInt(key, 0)
            }
        }
        val recentToolCalls = obj.optJSONArray("recentToolCalls")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { parseToolCallRecord(it) }
            }
        } ?: emptyList()
        return GuardrailSnapshot(
            consecutiveFailures = consecutiveFailures,
            toolFailureCounts = toolFailureCounts,
            recentToolCalls = recentToolCalls,
        )
    }

    private fun serializeToolCallRecord(record: ToolCallRecord): JSONObject {
        val obj = JSONObject()
        obj.put("toolName", record.toolName)
        obj.put("normalizedArgs", record.normalizedArgs)
        obj.put("argsHash", record.argsHash)
        obj.put("resultHash", record.resultHash)
        obj.put("status", record.status.name)
        obj.put("timestampMs", record.timestampMs)
        return obj
    }

    private fun parseToolCallRecord(obj: JSONObject): ToolCallRecord {
        val status = runCatching {
            ToolExecutionStatus.valueOf(obj.optString("status"))
        }.getOrDefault(ToolExecutionStatus.SUCCESS)
        return ToolCallRecord(
            toolName = obj.optString("toolName"),
            normalizedArgs = obj.optString("normalizedArgs"),
            argsHash = obj.optString("argsHash"),
            resultHash = obj.optString("resultHash"),
            status = status,
            timestampMs = obj.optLong("timestampMs", System.currentTimeMillis()),
        )
    }

    // ==================== MessagePart 序列化 ====================

    private fun serializeParts(parts: List<MessagePart>): JSONArray {
        return JSONArray().apply {
            parts.forEach { part ->
                val obj = JSONObject()
                when (part) {
                    is MessagePart.Text -> {
                        obj.put("type", "Text")
                        obj.put("content", part.content)
                        obj.put("ignored", part.ignored)
                    }
                    is MessagePart.ToolCall -> {
                        obj.put("type", "ToolCall")
                        obj.put("toolName", part.toolName)
                        obj.put("callId", part.callId)
                        obj.put("state", JSONObject().apply {
                            put("status", part.state.status.name)
                            put("input", JSONObject(part.state.input))
                            put("output", part.state.output ?: JSONObject.NULL)
                            put("error", part.state.error ?: JSONObject.NULL)
                            put("startTime", part.state.startTime)
                            put("endTime", part.state.endTime)
                        })
                        if (part.input.isNotEmpty()) {
                            obj.put("input", JSONObject(part.input))
                        }
                        obj.put("output", part.output ?: JSONObject.NULL)
                    }
                    is MessagePart.Reasoning -> {
                        obj.put("type", "Reasoning")
                        obj.put("content", part.content)
                    }
                    is MessagePart.StepStart -> {
                        obj.put("type", "StepStart")
                        obj.put("round", part.round)
                    }
                    is MessagePart.StepFinish -> {
                        obj.put("type", "StepFinish")
                        obj.put("tokensUsed", part.tokensUsed)
                        obj.put("cost", part.cost)
                    }
                    is MessagePart.Compaction -> {
                        obj.put("type", "Compaction")
                        obj.put("summary", part.summary)
                        obj.put("auto", part.auto)
                    }
                    is MessagePart.Error -> {
                        obj.put("type", "Error")
                        obj.put("message", part.message)
                        obj.put("recoverable", part.recoverable)
                    }
                    is MessagePart.StreamingState -> {
                        obj.put("type", "StreamingState")
                        obj.put("text", part.text)
                        obj.put("reasoning", part.reasoning)
                        obj.put("phase", part.phase)
                    }
                    is MessagePart.AudioClip -> {
                        obj.put("type", "AudioClip")
                        obj.put("filePath", part.filePath)
                        obj.put("transcript", part.transcript)
                    }
                }
                put(obj)
            }
        }
    }

    private fun parseParts(arr: JSONArray?): List<MessagePart> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val type = o.optString("type")
            when (type) {
                "Text" -> MessagePart.Text(
                    content = o.optString("content"),
                    ignored = o.optBoolean("ignored", false),
                )
                "ToolCall" -> {
                    val stateObj = o.optJSONObject("state")
                    val toolState = if (stateObj != null) {
                        val status = runCatching { ToolStateType.valueOf(stateObj.optString("status")) }
                            .getOrDefault(ToolStateType.PENDING)
                        val inputMap = mutableMapOf<String, String>()
                        stateObj.optJSONObject("input")?.let { inp ->
                            inp.keys().forEach { key -> inputMap[key] = inp.optString(key) }
                        }
                        ToolState(
                            status = status,
                            input = inputMap,
                            output = stateObj.optString("output").ifBlank { null },
                            error = stateObj.optString("error").ifBlank { null },
                            startTime = stateObj.optLong("startTime", 0L),
                            endTime = stateObj.optLong("endTime", 0L),
                        )
                    } else ToolState(status = ToolStateType.PENDING)
                    MessagePart.ToolCall(
                        toolName = o.optString("toolName"),
                        callId = o.optString("callId"),
                        state = toolState,
                        output = o.optString("output").ifBlank { null },
                    )
                }
                "Reasoning" -> MessagePart.Reasoning(
                    content = o.optString("content"),
                )
                "StepStart" -> MessagePart.StepStart(
                    round = o.optInt("round", 0),
                )
                "StepFinish" -> MessagePart.StepFinish(
                    tokensUsed = o.optInt("tokensUsed", 0),
                    cost = o.optDouble("cost", 0.0),
                )
                "Compaction" -> MessagePart.Compaction(
                    summary = o.optString("summary"),
                    auto = o.optBoolean("auto", true),
                )
                "Error" -> MessagePart.Error(
                    message = o.optString("message"),
                    recoverable = o.optBoolean("recoverable", true),
                )
                "StreamingState" -> MessagePart.StreamingState(
                    text = o.optString("text"),
                    reasoning = o.optString("reasoning"),
                    phase = o.optString("phase"),
                )
                else -> null
            }
        }
    }
}
