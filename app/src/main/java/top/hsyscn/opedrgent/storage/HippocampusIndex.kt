package top.hsyscn.opedrgent.storage

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.utils.DebugLog
import java.util.UUID

// ==================== 记忆作用域 ====================

/** 记忆作用域：决定索引项的可见范围和生命周期 */
enum class MemoryScope(val label: String) {
    GLOBAL("全局"),       // 用户偏好、跨内容知识
    PROJECT("项目"),      // 笔记 = 项目级记忆
    SESSION("会话"),      // 对话 = 会话级记忆
}

// ==================== 来源类型（按作用域分组） ====================

enum class SourceType(val label: String) {
    // -- 全局级 --
    USER_PREFERENCES("用户偏好"),
    USER_MEMORY("用户记忆"),

    // -- 项目级 --
    NOTE("笔记"),
    SPROUT("发芽"),

    // -- 会话级 --
    CONVERSATION("对话"),
    RECORDING("录音"),
    INTERVIEW("面试"),
}

data class IndexedItem(
    val id: String = UUID.randomUUID().toString(),
    val sourceType: SourceType,
    val sourceId: String,
    val title: String,
    val summary: String,
    val keywords: String = "",
    val scope: MemoryScope = MemoryScope.PROJECT,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    /** 条目年龄（天），从创建时间计算 */
    val ageDays: Int
        get() = ((System.currentTimeMillis() - createdAt) / 86400000).toInt()
}

class HippocampusIndex(context: Context) {

    companion object {
        private const val TAG = "HippocampusIndex"
    }

    private val db = HippocampusDatabase.getInstance(context).writableDatabase

    suspend fun upsert(item: IndexedItem) = withContext(Dispatchers.IO) {
        val existing = findBySource(item.sourceType, item.sourceId)
        if (existing != null) {
            update(item.copy(id = existing.id, createdAt = existing.createdAt))
        } else {
            insert(item)
        }
    }

    private suspend fun insert(item: IndexedItem) = withContext(Dispatchers.IO) {
        db.insert(HippocampusDatabase.TABLE, null, item.toContentValues())
    }

    /**
     * 批量插入索引条目。
     *
     * 使用 SQLite 事务包裹所有插入，避免逐条写入时的多次 fsync，
     * 适用于笔记批量导入、同步等一次性写入大量条目的场景。
     */
    suspend fun insertBatch(entries: List<IndexedItem>) = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext
        db.beginTransaction()
        try {
            for (item in entries) {
                db.insert(HippocampusDatabase.TABLE, null, item.toContentValues())
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun IndexedItem.toContentValues(): ContentValues = ContentValues().apply {
        put(HippocampusDatabase.COL_ID, id)
        put(HippocampusDatabase.COL_SOURCE_TYPE, sourceType.name)
        put(HippocampusDatabase.COL_SOURCE_ID, sourceId)
        put(HippocampusDatabase.COL_SCOPE, scope.name)
        put(HippocampusDatabase.COL_TITLE, title)
        put(HippocampusDatabase.COL_SUMMARY, summary)
        put(HippocampusDatabase.COL_KEYWORDS, keywords)
        put(HippocampusDatabase.COL_CREATED_AT, createdAt)
        put(HippocampusDatabase.COL_UPDATED_AT, updatedAt)
    }

    private suspend fun update(item: IndexedItem) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put(HippocampusDatabase.COL_SCOPE, item.scope.name)
            put(HippocampusDatabase.COL_TITLE, item.title)
            put(HippocampusDatabase.COL_SUMMARY, item.summary)
            put(HippocampusDatabase.COL_KEYWORDS, item.keywords)
            put(HippocampusDatabase.COL_UPDATED_AT, item.updatedAt)
        }
        db.update(HippocampusDatabase.TABLE, cv, "${HippocampusDatabase.COL_ID}=?", arrayOf(item.id))
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        db.delete(HippocampusDatabase.TABLE, "${HippocampusDatabase.COL_ID}=?", arrayOf(id))
    }

    suspend fun deleteBySource(sourceType: SourceType, sourceId: String) = withContext(Dispatchers.IO) {
        db.delete(HippocampusDatabase.TABLE,
            "${HippocampusDatabase.COL_SOURCE_TYPE}=? AND ${HippocampusDatabase.COL_SOURCE_ID}=?",
            arrayOf(sourceType.name, sourceId))
    }

    suspend fun query(keyword: String, limit: Int = 10): List<IndexedItem> = withContext(Dispatchers.IO) {
        val sql = """SELECT * FROM ${HippocampusDatabase.TABLE}
            WHERE ${HippocampusDatabase.COL_TITLE} LIKE ? OR ${HippocampusDatabase.COL_SUMMARY} LIKE ? OR ${HippocampusDatabase.COL_KEYWORDS} LIKE ?
            ORDER BY ${HippocampusDatabase.COL_CREATED_AT} DESC LIMIT ?"""
        val pattern = "%$keyword%"
        val cursor = db.rawQuery(sql, arrayOf(pattern, pattern, pattern, limit.toString()))
        cursorToList(cursor)
    }

    suspend fun getAll(limit: Int = 100): List<IndexedItem> = withContext(Dispatchers.IO) {
        val cursor = db.query(HippocampusDatabase.TABLE, null, null, null, null, null,
            "${HippocampusDatabase.COL_UPDATED_AT} DESC", limit.toString())
        cursorToList(cursor)
    }

    suspend fun getAllByType(sourceType: SourceType, limit: Int = 100): List<IndexedItem> = withContext(Dispatchers.IO) {
        val cursor = db.query(HippocampusDatabase.TABLE, null,
            "${HippocampusDatabase.COL_SOURCE_TYPE}=?", arrayOf(sourceType.name), null, null,
            "${HippocampusDatabase.COL_UPDATED_AT} DESC", limit.toString())
        cursorToList(cursor)
    }

    suspend fun count(): Int = withContext(Dispatchers.IO) {
        val cursor = db.rawQuery("SELECT COUNT(*) FROM ${HippocampusDatabase.TABLE}", null)
        cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    private suspend fun findBySource(sourceType: SourceType, sourceId: String): IndexedItem? = withContext(Dispatchers.IO) {
        val cursor = db.query(HippocampusDatabase.TABLE, null,
            "${HippocampusDatabase.COL_SOURCE_TYPE}=? AND ${HippocampusDatabase.COL_SOURCE_ID}=?",
            arrayOf(sourceType.name, sourceId), null, null, null, "1")
        cursorToList(cursor).firstOrNull()
    }

    private fun cursorToList(cursor: Cursor): List<IndexedItem> {
        val items = mutableListOf<IndexedItem>()
        cursor.use {
            while (it.moveToNext()) {
                items.add(IndexedItem(
                    id = it.getString(it.getColumnIndexOrThrow(HippocampusDatabase.COL_ID)),
                    sourceType = runCatching { SourceType.valueOf(it.getString(it.getColumnIndexOrThrow(HippocampusDatabase.COL_SOURCE_TYPE))) }.getOrDefault(SourceType.NOTE),
                    sourceId = it.getString(it.getColumnIndexOrThrow(HippocampusDatabase.COL_SOURCE_ID)),
                    title = it.getString(it.getColumnIndexOrThrow(HippocampusDatabase.COL_TITLE)),
                    summary = it.getString(it.getColumnIndexOrThrow(HippocampusDatabase.COL_SUMMARY)),
                    keywords = it.getString(it.getColumnIndexOrThrow(HippocampusDatabase.COL_KEYWORDS)),
                    scope = runCatching { MemoryScope.valueOf(it.getString(it.getColumnIndexOrThrow(HippocampusDatabase.COL_SCOPE))) }.getOrDefault(MemoryScope.PROJECT),
                    createdAt = it.getLong(it.getColumnIndexOrThrow(HippocampusDatabase.COL_CREATED_AT)),
                    updatedAt = it.getLong(it.getColumnIndexOrThrow(HippocampusDatabase.COL_UPDATED_AT)),
                ))
            }
        }
        return items
    }

    // ==================== 便捷方法（按作用域分配） ====================

    suspend fun upsertNote(noteId: Long, title: String, content: String) = withContext(Dispatchers.IO) {
        upsert(IndexedItem(
            sourceType = SourceType.NOTE,
            sourceId = noteId.toString(),
            title = title,
            summary = content.take(500),
            keywords = extractKeywords(title, content),
            scope = MemoryScope.PROJECT,
        ))
    }

    suspend fun upsertConversation(sessionId: String, title: String, lastMessage: String) = withContext(Dispatchers.IO) {
        upsert(IndexedItem(
            sourceType = SourceType.CONVERSATION,
            sourceId = sessionId,
            title = title,
            summary = lastMessage.take(500),
            keywords = extractKeywords(title, lastMessage),
            scope = MemoryScope.SESSION,
        ))
    }

    suspend fun upsertRecording(recordingId: String, title: String, transcript: String) = withContext(Dispatchers.IO) {
        upsert(IndexedItem(
            sourceType = SourceType.RECORDING,
            sourceId = recordingId,
            title = title,
            summary = transcript.take(500),
            keywords = extractKeywords(title, transcript),
            scope = MemoryScope.SESSION,
        ))
    }

    suspend fun upsertSprout(noteId: String, noteTitle: String, reportSummary: String) = withContext(Dispatchers.IO) {
        upsert(IndexedItem(
            sourceType = SourceType.SPROUT,
            sourceId = noteId,
            title = "发芽: $noteTitle",
            summary = reportSummary.take(500),
            keywords = extractKeywords(noteTitle, reportSummary),
            scope = MemoryScope.PROJECT,
        ))
    }

    /**
     * 索引面试会话（会话级记忆，跨会话可回顾）。
     *
     * 完整的轮次记录保存在 [HippocampusSessionStore]，这里只保存轻量索引，
     * 让面试会话出现在海马体主界面的统一列表中。
     *
     * @param sessionId 会话 ID
     * @param title 显示标题（如 "求职面试: 后端工程师@字节"）
     * @param goalSummary 目标摘要
     * @param driftSummary 漂移报告摘要
     */
    suspend fun upsertInterview(
        sessionId: String,
        title: String,
        goalSummary: String,
        driftSummary: String,
    ) = withContext(Dispatchers.IO) {
        upsert(IndexedItem(
            sourceType = SourceType.INTERVIEW,
            sourceId = sessionId,
            title = title,
            summary = driftSummary.take(500),
            keywords = extractKeywords(title, "$goalSummary $driftSummary"),
            scope = MemoryScope.SESSION,
        ))
    }

    /** 索引用户偏好（全局级记忆，跨会话持久） */
    suspend fun upsertPreference(preferenceKey: String, preferenceValue: String, description: String = "") = withContext(Dispatchers.IO) {
        upsert(IndexedItem(
            sourceType = SourceType.USER_PREFERENCES,
            sourceId = preferenceKey,
            title = "偏好: $preferenceKey",
            summary = description.ifBlank { preferenceValue.take(500) },
            keywords = extractKeywords(preferenceKey, preferenceValue),
            scope = MemoryScope.GLOBAL,
        ))
    }

    /** 为索引条目提取关键词（供批量索引复用） */
    internal fun extractKeywords(title: String, content: String): String {
        val text = "$title $content"
        val punctuation = Regex("[\\s,，.。、;；:：!！?？\u201C\u201D\u2018\u2019\\[\\]【】《》（）()\n\r]+")
        // 1) 按标点分割，取长度 >= 2 的词
        val words = text.split(punctuation)
            .filter { it.length >= 2 }
        // 2) 对长度 > 4 的中文片段，提取 2-gram 作为补充关键词
        val ngrams = text.split(punctuation)
            .filter { it.length > 4 && it.any { c -> c.code > 0x4E00 } }
            .flatMap { segment ->
                (0 until segment.length - 1).map { segment.substring(it, it + 2) }
            }
        val allTokens = (words + ngrams)
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(20)
            .map { it.key }
        return allTokens.joinToString(",")
    }
}
