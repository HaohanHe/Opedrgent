package top.hsyscn.opedrgent.note

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import top.hsyscn.opedrgent.storage.MemoryStore

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
) {

    private val database = NoteDatabase.getInstance(context)
    private val dao = NoteDao(database)

    /** 知识图谱引擎（懒加载，首次访问时初始化） */
    val knowledgeGraph: KnowledgeGraph by lazy { KnowledgeGraph(context) }

    // 响应式变更通知
    private val _changeTrigger = MutableStateFlow(0L)

    /** 所有笔记（按置顶+更新时间排序） */
    fun getAllNotes(): Flow<List<Note>> = _changeTrigger.map { dao.getAllNotes() }

    /** 按类型筛选 */
    fun getByType(type: NoteType): Flow<List<Note>> = _changeTrigger.map { dao.getByType(type) }

    /** 按文件夹筛选 */
    fun getByFolder(folderId: Long? = null): Flow<List<Note>> = _changeTrigger.map { dao.getByFolder(folderId) }

    /** 搜索笔记（标题/内容/摘要模糊匹配） */
    fun searchNotes(query: String): Flow<List<Note>> = _changeTrigger.map { dao.searchNotes(query) }

    /** 按标签筛选 */
    fun getByTag(tag: String): Flow<List<Note>> = _changeTrigger.map { dao.getByTag(tag) }

    /** 获取所有唯一标签 */
    fun getAllTags(): Flow<List<String>> = _changeTrigger.map { dao.getAllTags() }

    /** 笔记总数 */
    fun countAll(): Flow<Long> = _changeTrigger.map { dao.countAll() }

    /** 获取单条笔记 */
    suspend fun getNoteById(id: Long): Note? = dao.getById(id)

    /** 创建或更新笔记（自动计算字数和摘要，触发知识图谱关联，同步笔记记忆） */
    suspend fun saveNote(note: Note): Long {
        val id = dao.insertOrUpdate(note)
        _changeTrigger.value = System.currentTimeMillis()
        // 保存后自动触发知识图谱更新
        val content = buildString {
            if (note.title.isNotBlank()) append(note.title).append(" ")
            append(note.content)
        }
        val noteIdStr = id.toString()
        knowledgeGraph.linkNote(noteIdStr, content)
        // 自动同步笔记记忆
        syncNoteMemory(id, note)
        return id
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

    // ==================== 知识图谱代理方法 ====================

    /** 获取笔记的所有关联笔记ID（按相关性排序） */
    fun getLinkedNotes(noteId: Long): List<String> {
        return knowledgeGraph.getLinkedNotes(noteId.toString())
    }

    /** 获取笔记的关联笔记列表（带标题）。 */
    suspend fun getLinkedNotesWithTitles(noteId: Long): List<Note> {
        val linkedIds = knowledgeGraph.getLinkedNotes(noteId.toString())
        return linkedIds.mapNotNull { id -> getNoteById(id.toLongOrNull() ?: 0) }
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

    /** 语义搜索笔记 */
    fun searchByRelevance(query: String, maxResults: Int = 5): List<Pair<String, Float>> {
        return knowledgeGraph.searchByRelevance(query, maxResults)
    }

    /** 重建知识图谱（从所有笔记重新计算） */
    suspend fun rebuildKnowledgeGraph() {
        val allNotes = dao.getAllNotes()
        for (note in allNotes) {
            val content = buildString {
                if (note.title.isNotBlank()) append(note.title).append(" ")
                append(note.content)
            }
            knowledgeGraph.linkNote(note.id.toString(), content)
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
