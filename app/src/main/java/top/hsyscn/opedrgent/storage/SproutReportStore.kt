package top.hsyscn.opedrgent.storage

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 发芽报告数据库 — 独立持久化每份发芽报告（参照得到大脑设计）
 *
 * 每次发芽生成一份报告，永久保存。
 * 与笔记通过 source_note_id 关联，支持一份笔记多次发芽（保留历史版本）。
 */
class SproutReportDatabase(context: Context) : SQLiteOpenHelper(
    context, "sprout_reports.db", null, 1,
) {
    companion object {
        private const val DATABASE_NAME = "sprout_reports.db"
        private const val DATABASE_VERSION = 1

        const val TABLE = "sprout_reports"
        const val COL_ID = "id"
        const val COL_SOURCE_NOTE_ID = "source_note_id"       // 关联的笔记 ID（0=独立发芽）
        const val COL_SOURCE_TITLE = "source_title"           // 来源标题/内容预览
        const val COL_MARKDOWN_REPORT = "markdown_report"     // 完整 markdown 报告
        const val COL_SUMMARY = "summary"                     // 一句话摘要
        const val COL_MODEL_USED = "model_used"               // 使用的模型
        const val COL_CREATED_AT = "created_at"
        const val COL_WORD_COUNT = "word_count"

        @Volatile
        private var INSTANCE: SproutReportDatabase? = null

        fun getInstance(ctx: Context): SproutReportDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SproutReportDatabase(ctx.applicationContext).also { INSTANCE = it }
            }
    }

    override fun onCreate(db: android.database.sqlite.SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_SOURCE_NOTE_ID INTEGER NOT NULL DEFAULT 0,
                $COL_SOURCE_TITLE TEXT NOT NULL DEFAULT '',
                $COL_MARKDOWN_REPORT TEXT NOT NULL DEFAULT '',
                $COL_SUMMARY TEXT NOT NULL DEFAULT '',
                $COL_MODEL_USED TEXT NOT NULL DEFAULT '',
                $COL_CREATED_AT INTEGER NOT NULL,
                $COL_WORD_COUNT INTEGER NOT NULL DEFAULT 0
            )
        """)
        db.execSQL("CREATE INDEX idx_note ON $TABLE($COL_SOURCE_NOTE_ID)")
        db.execSQL("CREATE INDEX idx_created ON $TABLE($COL_CREATED_AT DESC)")
    }

    override fun onUpgrade(db: android.database.sqlite.SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }
}

/**
 * 发芽报告实体（轻量级，用于列表展示和详情查看）
 */
data class SproutReportRecord(
    val id: Long = 0,
    val sourceNoteId: Long,          // 关联笔记 ID，0 表示独立发芽
    val sourceTitle: String,         // 来源内容标题/预览
    val markdownReport: String,      // 完整 markdown 报告
    val summary: String,             // 一句话摘要
    val modelUsed: String,           // 模型
    val createdAt: Long,             // 生成时间
    val wordCount: Int,              // 报告字数
)

/**
 * 发芽报告存储 — CRUD 操作
 */
class SproutReportStore(context: Context) {

    private val db = SproutReportDatabase.getInstance(context).writableDatabase

    /** 保存一份新发芽报告 */
    fun insert(report: SproutReportRecord): Long = runBlocking(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put(SproutReportDatabase.COL_SOURCE_NOTE_ID, report.sourceNoteId)
            put(SproutReportDatabase.COL_SOURCE_TITLE, report.sourceTitle.take(200))
            put(SproutReportDatabase.COL_MARKDOWN_REPORT, report.markdownReport)
            put(SproutReportDatabase.COL_SUMMARY, report.summary.take(500))
            put(SproutReportDatabase.COL_MODEL_USED, report.modelUsed)
            put(SproutReportDatabase.COL_CREATED_AT, report.createdAt)
            put(SproutReportDatabase.COL_WORD_COUNT, report.wordCount)
        }
        db.insert(SproutReportDatabase.TABLE, null, cv)
    }

    /** 按 ID 删除 */
    fun delete(id: Long) = runBlocking(Dispatchers.IO) {
        db.delete(SproutReportDatabase.TABLE,
            "${SproutReportDatabase.COL_ID}=?", arrayOf(id.toString()))
    }

    /** 删除某笔记的所有发芽报告 */
    fun deleteByNoteId(noteId: Long) = runBlocking(Dispatchers.IO) {
        db.delete(SproutReportDatabase.TABLE,
            "${SproutReportDatabase.COL_SOURCE_NOTE_ID}=?",
            arrayOf(noteId.toString()))
    }

    /** 获取全部报告（按时间倒序） */
    fun getAll(limit: Int = 50): List<SproutReportRecord> = runBlocking(Dispatchers.IO) {
        val cursor = db.query(
            SproutReportDatabase.TABLE, null, null, null, null, null,
            "${SproutReportDatabase.COL_CREATED_AT} DESC",
            limit.toString()
        )
        cursorToList(cursor)
    }

    /** 获取某笔记的所有发芽报告 */
    fun getByNoteId(noteId: Long, limit: Int = 10): List<SproutReportRecord> = runBlocking(Dispatchers.IO) {
        val cursor = db.query(
            SproutReportDatabase.TABLE, null,
            "${SproutReportDatabase.COL_SOURCE_NOTE_ID}=?",
            arrayOf(noteId.toString()), null, null,
            "${SproutReportDatabase.COL_CREATED_AT} DESC",
            limit.toString()
        )
        cursorToList(cursor)
    }

    /** 获取单条报告详情 */
    fun getById(id: Long): SproutReportRecord? = runBlocking(Dispatchers.IO) {
        val cursor = db.query(
            SproutReportDatabase.TABLE, null,
            "${SproutReportDatabase.COL_ID}=?",
            arrayOf(id.toString()), null, null, null, "1"
        )
        cursorToList(cursor).firstOrNull()
    }

    /** 搜索报告（标题/摘要/内容） */
    fun query(keyword: String, limit: Int = 20): List<SproutReportRecord> = runBlocking(Dispatchers.IO) {
        val pattern = "%$keyword%"
        val cursor = db.rawQuery("""
            SELECT * FROM ${SproutReportDatabase.TABLE}
            WHERE ${SproutReportDatabase.COL_SOURCE_TITLE} LIKE ?
               OR ${SproutReportDatabase.COL_SUMMARY} LIKE ?
               OR ${SproutReportDatabase.COL_MARKDOWN_REPORT} LIKE ?
            ORDER BY ${SproutReportDatabase.COL_CREATED_AT} DESC LIMIT ?
        """, arrayOf(pattern, pattern, pattern, limit.toString()))
        cursorToList(cursor)
    }

    /** 总数 */
    fun count(): Int = runBlocking(Dispatchers.IO) {
        db.rawQuery("SELECT COUNT(*) FROM ${SproutReportDatabase.TABLE}", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    private fun cursorToList(cursor: Cursor): List<SproutReportRecord> {
        val items = mutableListOf<SproutReportRecord>()
        cursor.use {
            while (it.moveToNext()) {
                items.add(SproutReportRecord(
                    id = it.getLong(it.getColumnIndexOrThrow(SproutReportDatabase.COL_ID)),
                    sourceNoteId = it.getLong(it.getColumnIndexOrThrow(SproutReportDatabase.COL_SOURCE_NOTE_ID)),
                    sourceTitle = it.getString(it.getColumnIndexOrThrow(SproutReportDatabase.COL_SOURCE_TITLE)) ?: "",
                    markdownReport = it.getString(it.getColumnIndexOrThrow(SproutReportDatabase.COL_MARKDOWN_REPORT)) ?: "",
                    summary = it.getString(it.getColumnIndexOrThrow(SproutReportDatabase.COL_SUMMARY)) ?: "",
                    modelUsed = it.getString(it.getColumnIndexOrThrow(SproutReportDatabase.COL_MODEL_USED)) ?: "",
                    createdAt = it.getLong(it.getColumnIndexOrThrow(SproutReportDatabase.COL_CREATED_AT)),
                    wordCount = it.getInt(it.getColumnIndexOrThrow(SproutReportDatabase.COL_WORD_COUNT)),
                ))
            }
        }
        return items
    }
}
