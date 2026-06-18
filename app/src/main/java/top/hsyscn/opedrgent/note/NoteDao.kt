package top.hsyscn.opedrgent.note

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 笔记数据访问层（原生 SQLite 实现）。
 *
 * 所有操作通过 [NoteDatabase] 的 writableDatabase/readableDatabase 执行。
 */
class NoteDao(private val db: NoteDatabase) {

    private val _changeNotifier = MutableStateFlow(System.currentTimeMillis())
    val changeNotifier: Flow<Long> = _changeNotifier

    private fun notifyChange() { _changeNotifier.value = System.currentTimeMillis() }

    /** 查询所有未删除笔记（置顶优先 + 更新时间倒序） */
    fun getAllNotes(): List<Note> {
        val cursor = db.readableDatabase.query(
            NoteDatabase.TABLE_NOTES,
            null,
            "${NoteDatabase.COL_IS_DELETED} = 0",
            null, null, null,
            "CASE WHEN ${NoteDatabase.COL_IS_PINNED}=1 THEN 0 ELSE 1 END, ${NoteDatabase.COL_UPDATED_AT} DESC",
        )
        return cursor.use { c -> c.mapToList(db) }
    }

    /** 按类型筛选 */
    fun getByType(type: NoteType): List<Note> {
        val cursor = db.readableDatabase.query(
            NoteDatabase.TABLE_NOTES,
            null,
            "${NoteDatabase.COL_IS_DELETED} = 0 AND ${NoteDatabase.COL_TYPE} = ?",
            arrayOf(type.name), null, null,
            "CASE WHEN ${NoteDatabase.COL_IS_PINNED}=1 THEN 0 ELSE 1 END, ${NoteDatabase.COL_UPDATED_AT} DESC",
        )
        return cursor.use { c -> c.mapToList(db) }
    }

    /** 按文件夹筛选（null=根目录） */
    fun getByFolder(folderId: Long?): List<Note> {
        val where = if (folderId == null)
            "${NoteDatabase.COL_IS_DELETED} = 0 AND ${NoteDatabase.COL_FOLDER_ID} IS NULL"
        else
            "${NoteDatabase.COL_IS_DELETED} = 0 AND ${NoteDatabase.COL_FOLDER_ID} = ?"
        val args = folderId?.let { arrayOf(it.toString()) } ?: emptyArray()

        val cursor = db.readableDatabase.query(
            NoteDatabase.TABLE_NOTES, null, where, args,
            null, null,
            "CASE WHEN ${NoteDatabase.COL_IS_PINNED}=1 THEN 0 ELSE 1 END, ${NoteDatabase.COL_UPDATED_AT} DESC",
        )
        return cursor.use { c -> c.mapToList(db) }
    }

    /** 搜索笔记（标题/内容/摘要模糊匹配） */
    fun searchNotes(query: String): List<Note> {
        val likePattern = "%$query%"
        val cursor = db.readableDatabase.query(
            NoteDatabase.TABLE_NOTES, null,
            """${NoteDatabase.COL_IS_DELETED} = 0 AND (
                ${NoteDatabase.COL_TITLE} LIKE ? OR
                ${NoteDatabase.COL_CONTENT} LIKE ? OR
                ${NoteDatabase.COL_SUMMARY} LIKE ?
            )""",
            arrayOf(likePattern, likePattern, likePattern),
            null, null, "${NoteDatabase.COL_UPDATED_AT} DESC",
        )
        return cursor.use { c -> c.mapToList(db) }
    }

    /** 按标签筛选 */
    fun getByTag(tag: String): List<Note> {
        val cursor = db.readableDatabase.query(
            NoteDatabase.TABLE_NOTES, null,
            "${NoteDatabase.COL_IS_DELETED} = 0 AND ${NoteDatabase.COL_TAGS_JSON} LIKE ?",
            arrayOf("%\"$tag\"%"),
            null, null,
            "CASE WHEN ${NoteDatabase.COL_IS_PINNED}=1 THEN 0 ELSE 1 END, ${NoteDatabase.COL_UPDATED_AT} DESC",
        )
        return cursor.use { c -> c.mapToList(db) }
    }

    /** 获取所有唯一标签 */
    fun getAllTags(): List<String> {
        val tags = mutableSetOf<String>()
        val cursor = db.readableDatabase.query(
            NoteDatabase.TABLE_NOTES,
            arrayOf(NoteDatabase.COL_TAGS_JSON),
            "${NoteDatabase.COL_IS_DELETED} = 0",
            null, null, null, null,
        )
        cursor.use { c ->
            while (c.moveToNext()) {
                val tagsJson = c.getString(0) ?: "[]"
                try {
                    val arr = org.json.JSONArray(tagsJson)
                    for (i in 0 until arr.length()) {
                        tags.add(arr.getString(i))
                    }
                } catch (_: Exception) {}
            }
        }
        return tags.sorted()
    }

    /** 获取单条笔记 */
    fun getById(id: Long): Note? {
        val cursor = db.readableDatabase.query(
            NoteDatabase.TABLE_NOTES, null,
            "${NoteDatabase.COL_ID} = ? AND ${NoteDatabase.COL_IS_DELETED} = 0",
            arrayOf(id.toString()), null, null, null,
        )
        return cursor.use { c -> if (c.moveToFirst()) db.cursorToNote(c) else null }
    }

    /** 笔记总数 */
    fun countAll(): Long {
        var count = 0L
        db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM ${NoteDatabase.TABLE_NOTES} WHERE ${NoteDatabase.COL_IS_DELETED} = 0", null
        ).use { if (it.moveToFirst()) count = it.getLong(0) }
        return count
    }

    /** 插入或更新 */
    fun insertOrUpdate(note: Note): Long {
        val values = noteToContentValues(note)
        return if (note.id == 0L) {
            val id = db.writableDatabase.insert(NoteDatabase.TABLE_NOTES, null, values)
            notifyChange()
            id
        } else {
            db.writableDatabase.update(
                NoteDatabase.TABLE_NOTES, values,
                "${NoteDatabase.COL_ID} = ?", arrayOf(note.id.toString()),
            )
            notifyChange()
            note.id
        }
    }

    /** 软删除 */
    fun softDelete(id: Long) {
        val values = ContentValues().apply {
            put(NoteDatabase.COL_IS_DELETED, 1)
            put(NoteDatabase.COL_UPDATED_AT, System.currentTimeMillis())
        }
        db.writableDatabase.update(NoteDatabase.TABLE_NOTES, values,
            "${NoteDatabase.COL_ID} = ?", arrayOf(id.toString()))
        notifyChange()
    }

    /** 置顶切换 */
    fun setPinned(id: Long, pinned: Boolean) {
        val values = ContentValues().apply {
            put(NoteDatabase.COL_IS_PINNED, if (pinned) 1 else 0)
            put(NoteDatabase.COL_UPDATED_AT, System.currentTimeMillis())
        }
        db.writableDatabase.update(NoteDatabase.TABLE_NOTES, values,
            "${NoteDatabase.COL_ID} = ?", arrayOf(id.toString()))
        notifyChange()
    }

    /** 获取最近 N 条 */
    fun getRecentNotes(limit: Int = 10): List<Note> {
        val cursor = db.readableDatabase.query(
            NoteDatabase.TABLE_NOTES, null,
            "${NoteDatabase.COL_IS_DELETED} = 0",
            null, null, null,
            "${NoteDatabase.COL_UPDATED_AT} DESC LIMIT $limit",
        )
        return cursor.use { c -> c.mapToList(db) }
    }

    // ==================== 内部工具 ====================

    private fun noteToContentValues(note: Note): ContentValues = ContentValues().apply {
        if (note.id > 0) put(NoteDatabase.COL_ID, note.id)
        put(NoteDatabase.COL_TITLE, note.title.ifEmpty { extractTitle(note.content) })
        put(NoteDatabase.COL_CONTENT, note.content)
        put(NoteDatabase.COL_SUMMARY, if (note.summary.isEmpty()) extractSummary(note.content) else note.summary)
        put(NoteDatabase.COL_TYPE, note.type.name)
        note.folderId?.let { put(NoteDatabase.COL_FOLDER_ID, it) } ?: putNull(NoteDatabase.COL_FOLDER_ID)
        put(NoteDatabase.COL_TAGS_JSON, note.tagsJson)
        put(NoteDatabase.COL_IS_PINNED, if (note.isPinned) 1 else 0)
        put(NoteDatabase.COL_IS_DELETED, if (note.isDeleted) 1 else 0)
        put(NoteDatabase.COL_SOURCE_URI, note.sourceUri)
        put(NoteDatabase.COL_CREATED_AT, note.createdAt)
        put(NoteDatabase.COL_UPDATED_AT, System.currentTimeMillis())
        put(NoteDatabase.COL_WORD_COUNT, note.content.length)
        put(NoteDatabase.COL_SPROUT_REPORT_JSON, note.sproutReportJson)
        put(NoteDatabase.COL_ORIGINAL_CONTENT, note.originalContent)
        put(NoteDatabase.COL_SOURCE_URL, note.sourceUrl)
        put(NoteDatabase.COL_SOURCE_TYPE, note.sourceType.name)
        put(NoteDatabase.COL_SPANS, note.spans)
    }

    private fun extractTitle(content: String): String {
        val firstLine = content.lines().firstOrNull { it.isNotBlank() }?.trim() ?: ""
        return firstLine.removePrefix("#").removePrefix("##").removePrefix("###").trim().takeIf { it.isNotEmpty() } ?: "无标题"
    }

    private fun extractSummary(content: String): String {
        val plainText = content
            .replace(Regex("#+\\s*"), "")
            .replace(Regex("\\*{1,2}[^*]*\\*{1,2}"), "")
            .replace(Regex("`[^`]*`"), "")
            .replace(Regex("!?\\[[^]]*]\\([^)]*\\)"), "")
            .replace(Regex(">\\s?"), "")
            .lines().filter { it.isNotBlank() }.joinToString(" ").trim()
        return plainText.take(200)
    }

    private fun Cursor.mapToList(database: NoteDatabase): List<Note> {
        val result = mutableListOf<Note>()
        while (moveToNext()) result.add(database.cursorToNote(this))
        return result
    }
}
