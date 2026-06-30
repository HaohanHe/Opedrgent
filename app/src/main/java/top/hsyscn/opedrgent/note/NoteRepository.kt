package top.hsyscn.opedrgent.note

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.network.LlmClient
import top.hsyscn.opedrgent.settings.ApiSettings
import top.hsyscn.opedrgent.storage.HippocampusIndex
import top.hsyscn.opedrgent.storage.IndexedItem
import top.hsyscn.opedrgent.storage.MemoryScope
import top.hsyscn.opedrgent.storage.MemoryStore
import top.hsyscn.opedrgent.storage.SourceType as HippoSourceType
import top.hsyscn.opedrgent.utils.DebugLog

/**
 * 笔记仓库：统一数据访问层。
 *
 * 对外提供简洁的 CRUD API + Flow 响应式更新通知。
 * 内部通过 [NoteDao] 操作原生 SQLite。
 * 集成 [KnowledgeGraph] 实现笔记自动关联。
 * 集成 [MemoryStore] 实现笔记记忆自动同步。
 */
class NoteRepository(
    private val context: Context,
    private val memoryStore: MemoryStore? = null,
    private val apiSettings: ApiSettings = ApiSettings(context),
    private val llmClient: LlmClient = LlmClient(),
) {

    private val database = NoteDatabase.getInstance(context)
    private val dao = NoteDao(database)

    /** 海马体索引（延迟注入，由 MainViewModel 设置） */
    var hippocampus: HippocampusIndex? = null

    /** 知识图谱引擎（懒加载，首次访问时初始化） */
    val knowledgeGraph: KnowledgeGraph by lazy {
        val graphStore = KnowledgeGraphStore(context)
        KnowledgeGraph(
            context,
            graphStore,
            EmbeddingProviderFactory.create(context, apiSettings, graphStore),
        )
    }

    // 响应式变更通知
    private val _changeTrigger = MutableStateFlow(0L)

    /** 所有笔记（按置顶+更新时间排序） */
    fun getAllNotes(): Flow<List<Note>> = _changeTrigger
        .map { dao.getAllNotes() }
        .flowOn(Dispatchers.IO)
        .conflate()

    /** 获取所有笔记（一次性，用于同步） */
    suspend fun getAllNotesOnce(): List<Note> = dao.getAllNotes()

    /** 从同步更新笔记（触发知识图谱建边，但不触发记忆同步） */
    suspend fun updateFromSync(note: Note) {
        val id = dao.insertOrUpdate(note)
        _changeTrigger.value = System.currentTimeMillis()
        val content = buildString {
            if (note.title.isNotBlank()) append(note.title).append(" ")
            append(note.content)
        }
        GraphLinkWorker.enqueue(context, id, content)
    }

    /** 按类型筛选 */
    fun getByType(type: NoteType): Flow<List<Note>> = _changeTrigger
        .map { dao.getByType(type) }
        .flowOn(Dispatchers.IO)
        .conflate()

    /** 按文件夹筛选 */
    fun getByFolder(folderId: Long? = null): Flow<List<Note>> = _changeTrigger
        .map { dao.getByFolder(folderId) }
        .flowOn(Dispatchers.IO)
        .conflate()

    /** 搜索笔记（标题/内容/摘要模糊匹配） */
    fun searchNotes(query: String): Flow<List<Note>> = _changeTrigger
        .map { dao.searchNotes(query) }
        .flowOn(Dispatchers.IO)
        .conflate()

    /**
     * 智能搜索：合并数据库 LIKE 匹配结果与知识图谱语义召回结果。
     *
     * 文本匹配结果优先展示，语义召回结果按相似度排序并去重补充。
     */
    fun searchNotesSmart(query: String): Flow<List<Note>> = _changeTrigger
        .map {
            val textMatches = dao.searchNotes(query)
            val semanticResults = try {
                knowledgeGraph.searchByRelevance(query, maxResults = 20)
            } catch (e: Exception) {
                DebugLog.e("NoteRepository", "semantic search failed: ${e.message}", e)
                emptyList()
            }

            val combined = mutableListOf<Note>()
            val seenIds = mutableSetOf<Long>()

            // 1. 文本匹配结果优先
            for (note in textMatches) {
                if (seenIds.add(note.id)) {
                    combined.add(note)
                }
            }

            // 2. 语义召回结果按分数排序并去重补充
            val semanticNotes = semanticResults
                .mapNotNull { (noteIdStr, _) ->
                    val noteId = noteIdStr.toLongOrNull() ?: return@mapNotNull null
                    dao.getById(noteId)
                }
            for (note in semanticNotes) {
                if (seenIds.add(note.id)) {
                    combined.add(note)
                }
            }

            combined
        }
        .flowOn(Dispatchers.IO)
        .conflate()

    /** 按标签筛选 */
    fun getByTag(tag: String): Flow<List<Note>> = _changeTrigger
        .map { dao.getByTag(tag) }
        .flowOn(Dispatchers.IO)
        .conflate()

    /** 获取所有唯一标签 */
    fun getAllTags(): Flow<List<String>> = _changeTrigger
        .map { dao.getAllTags() }
        .flowOn(Dispatchers.IO)
        .conflate()

    /** 笔记总数 */
    fun countAll(): Flow<Long> = _changeTrigger
        .map { dao.countAll() }
        .flowOn(Dispatchers.IO)
        .conflate()

    /** 今日新增笔记数 */
    fun countToday(): Flow<Long> = _changeTrigger
        .map { dao.countCreatedAfter(getTodayStart()) }
        .flowOn(Dispatchers.IO)
        .conflate()

    private fun getTodayStart(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** 获取单条笔记 */
    suspend fun getNoteById(id: Long): Note? = dao.getById(id)

    /** 创建或更新笔记（自动计算字数和摘要，触发知识图谱关联，同步笔记记忆） */
    suspend fun saveNote(note: Note): Long {
        val noteToSave = if (note.title.isBlank() && note.content.isNotBlank()) {
            val generated = NoteTitleGenerator.generate(note.content, apiSettings, llmClient)
            note.copy(title = generated)
        } else {
            note
        }
        val id = dao.insertOrUpdate(noteToSave)
        _changeTrigger.value = System.currentTimeMillis()
        val content = buildString {
            if (noteToSave.title.isNotBlank()) append(noteToSave.title).append(" ")
            append(noteToSave.content)
        }
        GraphLinkWorker.enqueue(context, id, content)
        syncNoteMemory(id, noteToSave)
        return id
    }

    /**
     * 批量导入笔记（用于同步/导入场景）。
     *
     * 逐条调用 [saveNote] 保留记忆同步、知识图谱关联等行为，
     * 最后将所有新笔记批量写入海马体索引，避免多次逐条 upsert 的开销。
     */
    suspend fun importNotes(notes: List<Note>): List<Long> = withContext(Dispatchers.IO) {
        if (notes.isEmpty()) return@withContext emptyList()
        val ids = notes.map { saveNote(it) }

        val hip = hippocampus
        if (hip != null) {
            val items = notes.zip(ids).map { (note, id) ->
                IndexedItem(
                    sourceType = HippoSourceType.NOTE,
                    sourceId = id.toString(),
                    title = note.title,
                    summary = note.content.take(500),
                    keywords = hip.extractKeywords(note.title, note.content),
                    scope = MemoryScope.PROJECT,
                )
            }
            if (items.isNotEmpty()) {
                hip.insertBatch(items)
            }
        }
        ids
    }

    /** 快速创建笔记（只需内容） */
    suspend fun quickCreate(content: String, type: NoteType = NoteType.QUICK): Long {
        val note = Note(title = "", content = content, type = type)
        return saveNote(note)
    }

    /** 从 AI 对话创建笔记 */
    suspend fun saveFromChat(title: String, content: String, sourceUri: String? = null): Long {
        val note = Note(title = title, content = content, type = NoteType.AI_CHAT, sourceUri = sourceUri)
        return saveNote(note)
    }

    /** 从会议转录创建笔记 */
    suspend fun saveFromMeeting(content: String, audioPath: String?): Long {
        val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date())
        val note = Note(
            title = "会议记录 $ts",
            content = content,
            type = NoteType.MEETING,
            sourceUri = audioPath,
        )
        return saveNote(note)
    }

    /** 软删除 */
    suspend fun deleteNote(id: Long) {
        dao.softDelete(id)
        knowledgeGraph.removeNote(id.toString())
        // 同步清理笔记记忆
        memoryStore?.removeNoteMemory(id)
        // 同步清理海马体索引
        hippocampus?.deleteBySource(HippoSourceType.NOTE, id.toString())
        _changeTrigger.value = System.currentTimeMillis()
    }

    /** 置顶切换 */
    suspend fun togglePin(id: Long): Boolean {
        val note = dao.getById(id) ?: return false
        val newPinned = !note.isPinned
        dao.setPinned(id, newPinned)
        _changeTrigger.value = System.currentTimeMillis()
        return newPinned
    }

    /** 移动笔记到文件夹 */
    suspend fun moveToFolder(noteId: Long, folderId: Long?) {
        val note = dao.getById(noteId) ?: return
        note.folderId = folderId
        dao.insertOrUpdate(note)
        _changeTrigger.value = System.currentTimeMillis()
    }

    /** 获取最近 N 条（用于 AI 上下文注入） */
    suspend fun getRecentNotes(limit: Int = 10): List<Note> = dao.getRecentNotes(limit)

    /** 获取最近笔记的摘要文本（用于注入 LLM 上下文） */
    suspend fun getRecentNotesContext(limit: Int = 5): String {
        val notes = getRecentNotes(limit)
        if (notes.isEmpty()) return ""
        return buildString {
            appendLine("=== 用户最近的笔记 ===")
            notes.forEach { note ->
                appendLine("【${note.title}】(${note.type.name})")
                appendLine(note.content.take(500))
                appendLine()
            }
        }
    }

    /** 获取最近笔记的轻量索引（标题 + 类型 + 一句话概要，用于发芽上下文的第二层） */
    suspend fun getRecentNotesIndex(limit: Int = 5): String {
        val notes = getRecentNotes(limit)
        if (notes.isEmpty()) return ""
        return notes.joinToString("\n") { note ->
            val snippet = note.content.replace("\n", " ").take(60)
            "- 【${note.title}】(${note.type.displayName()}) $snippet"
        }
    }

    // ==================== 知识图谱代理方法 ====================

    /** 获取笔记的所有关联笔记ID（按相关性排序） */
    fun getLinkedNotes(noteId: Long): List<String> {
        return knowledgeGraph.getLinkedNotes(noteId.toString())
    }

    /** 获取笔记的关联笔记列表（带标题）。 */
    suspend fun getLinkedNotesWithTitles(noteId: Long): List<Note> {
        val linkedIds = knowledgeGraph.getLinkedNotes(noteId.toString())
        val noteIds = linkedIds.mapNotNull { it.toLongOrNull() }
        return dao.getByIds(noteIds)
    }

    /** 获取笔记的关联数 */
    fun getLinkCount(noteId: Long): Int {
        return knowledgeGraph.getLinkCount(noteId.toString())
    }

    /** 获取知识图谱全局统计 */
    fun getKnowledgeStats(): KnowledgeGraph.GraphStats {
        return knowledgeGraph.getStats()
    }

    /** 获取所有关联关系（用于可视化） */
    fun getAllGraphEdges(): List<KnowledgeGraph.GraphEdge> {
        return knowledgeGraph.getAllLinks()
    }

    /** 获取所有带类型/权重的关联关系（用于增强可视化） */
    fun getAllGraphEdgeDetails(): List<KnowledgeGraph.GraphEdgeDetail> {
        return knowledgeGraph.getAllEdgeDetails()
    }

    /** 语义搜索笔记 */
    fun searchByRelevance(query: String, maxResults: Int = 5): List<Pair<String, Float>> {
        return knowledgeGraph.searchByRelevance(query, maxResults)
    }

    /** 重建知识图谱（从所有笔记重新计算，批量高效） */
    suspend fun rebuildKnowledgeGraph() {
        val allNotes = dao.getAllNotes()
        val notes = allNotes.map { note ->
            val content = buildString {
                if (note.title.isNotBlank()) append(note.title).append(" ")
                append(note.content)
            }
            note.id.toString() to content
        }
        knowledgeGraph.rebuildFromNotes(notes)
    }

    /** 启动时一致性校验：检测 v1 格式或数据损坏，自动重建 */
    suspend fun checkAndRebuildGraphIfNeeded() {
        val noteCount = dao.countAll()
        if (knowledgeGraph.needsRebuild(noteCount)) {
            DebugLog.w("NoteRepository", "知识图谱需要重建（v1格式或数据损坏），开始自动重建...")
            rebuildKnowledgeGraph()
        }
    }

    // ==================== 笔记记忆同步 ====================

    /** 将笔记摘要同步到 MemoryStore */
    private fun syncNoteMemory(noteId: Long, note: Note) {
        val store = memoryStore ?: return
        val summary = note.summary.ifBlank {
            note.content.take(200).replace("\n", " ")
        }
        val title = note.title.ifBlank { "笔记 #${noteId}" }
        store.addNoteMemory(noteId, title, summary)
    }
}
