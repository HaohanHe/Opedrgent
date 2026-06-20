package top.hsyscn.opedrgent.note

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 笔记数据库（原生 SQLite 实现）。
 *
 * 不依赖 Room/kapt，避免注解处理器兼容性问题。
 */
class NoteDatabase(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {

    companion object {
        private const val DATABASE_NAME = "opedrgent_notes.db"
        private const val DATABASE_VERSION = 3
        const val TABLE_NOTES = "notes"

        // 列名
        const val COL_ID = "id"
        const val COL_TITLE = "title"
        const val COL_CONTENT = "content"
        const val COL_SUMMARY = "summary"
        const val COL_TYPE = "type"           // TEXT: 笔记类型枚举名
        const val COL_FOLDER_ID = "folder_id" // Long?
        const val COL_TAGS_JSON = "tags_json"
        const val COL_IS_PINNED = "is_pinned"
        const val COL_IS_DELETED = "is_deleted"
        const val COL_SOURCE_URI = "source_uri"
        const val COL_CREATED_AT = "created_at"
        const val COL_UPDATED_AT = "updated_at"
        const val COL_WORD_COUNT = "word_count"
        const val COL_SPROUT_REPORT_JSON = "sprout_report_json"
        const val COL_ORIGINAL_CONTENT = "original_content"
        const val COL_SOURCE_URL = "source_url"
        const val COL_SOURCE_TYPE = "source_type"
        const val COL_SPANS = "spans"

        // 单例
        @Volatile
        private var INSTANCE: NoteDatabase? = null

        fun getInstance(ctx: Context): NoteDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: NoteDatabase(ctx.applicationContext).also { INSTANCE = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_NOTES (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TITLE TEXT NOT NULL DEFAULT '',
                $COL_CONTENT TEXT NOT NULL DEFAULT '',
                $COL_SUMMARY TEXT DEFAULT '',
                $COL_TYPE TEXT NOT NULL DEFAULT 'TEXT',
                $COL_FOLDER_ID INTEGER,
                $COL_TAGS_JSON TEXT DEFAULT '[]',
                $COL_IS_PINNED INTEGER NOT NULL DEFAULT 0,
                $COL_IS_DELETED INTEGER NOT NULL DEFAULT 0,
                $COL_SOURCE_URI TEXT,
                $COL_CREATED_AT INTEGER NOT NULL,
                $COL_UPDATED_AT INTEGER NOT NULL,
                $COL_WORD_COUNT INTEGER NOT NULL DEFAULT 0,
                $COL_SPROUT_REPORT_JSON TEXT,
                $COL_ORIGINAL_CONTENT TEXT,
                $COL_SOURCE_URL TEXT DEFAULT '',
                $COL_SOURCE_TYPE TEXT DEFAULT 'MANUAL',
                $COL_SPANS TEXT DEFAULT ''
            )
        """.trimIndent())

        // 索引
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_notes_type ON $TABLE_NOTES($COL_TYPE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_notes_created ON $TABLE_NOTES($COL_CREATED_AT DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_notes_pinned_updated ON $TABLE_NOTES($COL_IS_PINNED, $COL_UPDATED_AT DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_notes_folder ON $TABLE_NOTES($COL_FOLDER_ID)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            safeAddColumn(db, COL_SOURCE_URL, "TEXT DEFAULT ''")
            safeAddColumn(db, COL_SOURCE_TYPE, "TEXT DEFAULT 'MANUAL'")
        }
        if (oldVersion < 3) {
            safeAddColumn(db, COL_SPANS, "TEXT DEFAULT ''")
            // 兜底：如果从 v1 直接升级到 v3，v2 的列可能还没加
            safeAddColumn(db, COL_SOURCE_URL, "TEXT DEFAULT ''")
            safeAddColumn(db, COL_SOURCE_TYPE, "TEXT DEFAULT 'MANUAL'")
        }
    }

    /** 安全添加列，已存在则跳过 */
    private fun safeAddColumn(db: SQLiteDatabase, column: String, type: String) {
        try {
            db.execSQL("ALTER TABLE $TABLE_NOTES ADD COLUMN $column $type")
        } catch (_: Exception) {
            // 列已存在，忽略
        }
    }

    /** 从 Cursor 构建Note对象 */
    internal fun cursorToNote(cursor: Cursor): Note {
        return Note(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
            title = cursor.getString(cursor.getColumnIndexOrThrow(COL_TITLE)) ?: "",
            content = cursor.getString(cursor.getColumnIndexOrThrow(COL_CONTENT)) ?: "",
            summary = cursor.getString(cursor.getColumnIndexOrThrow(COL_SUMMARY)) ?: "",
            type = try { NoteType.valueOf(cursor.getString(cursor.getColumnIndexOrThrow(COL_TYPE))) } catch (_: Exception) { NoteType.TEXT },
            folderId = cursor.getColumnIndexOrThrow(COL_FOLDER_ID).let { idx -> if (cursor.isNull(idx)) null else cursor.getLong(idx) },
            tagsJson = cursor.getString(cursor.getColumnIndexOrThrow(COL_TAGS_JSON)) ?: "[]",
            isPinned = cursor.getInt(cursor.getColumnIndexOrThrow(COL_IS_PINNED)) == 1,
            isDeleted = cursor.getInt(cursor.getColumnIndexOrThrow(COL_IS_DELETED)) == 1,
            sourceUri = cursor.getString(cursor.getColumnIndexOrThrow(COL_SOURCE_URI)),
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_CREATED_AT)),
            updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_UPDATED_AT)),
            wordCount = cursor.getInt(cursor.getColumnIndexOrThrow(COL_WORD_COUNT)),
            sproutReportJson = cursor.getString(cursor.getColumnIndexOrThrow(COL_SPROUT_REPORT_JSON)),
            originalContent = cursor.getString(cursor.getColumnIndexOrThrow(COL_ORIGINAL_CONTENT)),
            sourceUrl = cursor.getString(cursor.getColumnIndexOrThrow(COL_SOURCE_URL)) ?: "",
            sourceType = try { SourceType.valueOf(cursor.getString(cursor.getColumnIndexOrThrow(COL_SOURCE_TYPE))) } catch (_: Exception) { SourceType.MANUAL },
            spans = cursor.getString(cursor.getColumnIndexOrThrow(COL_SPANS)) ?: "",
        )
    }
}
