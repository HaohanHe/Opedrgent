package top.hsyscn.opedrgent.note

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * 笔记仓库：统一数据访问层。
 *
 * 对外提供简洁的 CRUD API + Flow 响应式更新通知。
 * 内部通过 [NoteDao] 操作原生 SQLite。
 */
class NoteRepository(context: Context) {

    private val database = NoteDatabase.getInstance(context)
    private val dao = NoteDao(database)

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

    /** 创建或更新笔记（自动计算字数和摘要） */
    suspend fun saveNote(note: Note): Long {
        val id = dao.insertOrUpdate(note)
        _changeTrigger.value = System.currentTimeMillis()
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
    suspend fun deleteNote(id: Long) { dao.softDelete(id); _changeTrigger.value = System.currentTimeMillis() }

    /** 置顶切换 */
    suspend fun togglePin(id: Long): Boolean {
        val note = dao.getById(id) ?: return false
        val newPinned = !note.isPinned
        dao.setPinned(id, newPinned)
        _changeTrigger.value = System.currentTimeMillis()
        return newPinned
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
}
