package top.hsyscn.opedrgent.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class HippocampusDatabase(context: Context) : SQLiteOpenHelper(
    context, DATABASE_NAME, null, DATABASE_VERSION,
) {
    companion object {
        private const val DATABASE_NAME = "hippocampus_index.db"
        private const val DATABASE_VERSION = 3
        const val TABLE = "indexed_items"
        const val COL_ID = "id"
        const val COL_SOURCE_TYPE = "source_type"
        const val COL_SOURCE_ID = "source_id"
        const val COL_SCOPE = "scope"
        const val COL_TITLE = "title"
        const val COL_SUMMARY = "summary"
        const val COL_KEYWORDS = "keywords"
        const val COL_CREATED_AT = "created_at"
        const val COL_UPDATED_AT = "updated_at"

        // ===== 面试会话表 (v3 新增) =====
        const val TABLE_SESSIONS = "hippocampus_sessions"
        const val COL_SESSION_ID = "session_id"
        const val COL_INTERVIEW_TYPE = "interview_type"
        const val COL_POSITION = "position"
        const val COL_COMPANY = "company"
        const val COL_PRIMARY_GOAL = "primary_goal"
        const val COL_KEY_TOPICS = "key_topics"
        const val COL_TOTAL_TURNS = "total_turns"
        const val COL_DRIFT_COUNT = "drift_count"
        const val COL_DRIFT_RATE = "drift_rate"
        const val COL_MAX_DRIFT_LEVEL = "max_drift_level"
        const val COL_AVG_RELEVANCE = "avg_relevance"
        const val COL_TOPICS_COVERED = "topics_covered"
        const val COL_TOPICS_MISSED = "topics_missed"
        const val COL_INTERVENTION_COUNT = "intervention_count"
        const val COL_SESSION_SUMMARY = "session_summary"
        const val COL_TURN_RECORDS = "turn_records"
        const val COL_STARTED_AT = "started_at"
        const val COL_ENDED_AT = "ended_at"

        @Volatile
        private var INSTANCE: HippocampusDatabase? = null

        fun getInstance(ctx: Context): HippocampusDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: HippocampusDatabase(ctx.applicationContext).also { INSTANCE = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE (
                $COL_ID TEXT PRIMARY KEY,
                $COL_SOURCE_TYPE TEXT NOT NULL,
                $COL_SOURCE_ID TEXT NOT NULL,
                $COL_SCOPE TEXT NOT NULL DEFAULT 'project',
                $COL_TITLE TEXT NOT NULL,
                $COL_SUMMARY TEXT NOT NULL,
                $COL_KEYWORDS TEXT DEFAULT '',
                $COL_CREATED_AT INTEGER NOT NULL,
                $COL_UPDATED_AT INTEGER NOT NULL
            )
        """)
        createItemIndexes(db)
        createSessionsTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // v1 -> v2: 新增 scope 列，旧数据默认为 project
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_SCOPE TEXT NOT NULL DEFAULT 'project'")
            createItemIndexes(db)
        }
        if (oldVersion < 3) {
            // v2 -> v3: 新增面试会话表，持久化 HippocampusMemory 数据
            createSessionsTable(db)
        }
    }

    /** 创建 indexed_items 表的高频查询索引（source_type/source_id/scope/title） */
    private fun createItemIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_source ON $TABLE($COL_SOURCE_TYPE, $COL_SOURCE_ID)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_title ON $TABLE($COL_TITLE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_scope ON $TABLE($COL_SCOPE)")
    }

    private fun createSessionsTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_SESSIONS (
                $COL_SESSION_ID TEXT PRIMARY KEY,
                $COL_INTERVIEW_TYPE TEXT NOT NULL,
                $COL_POSITION TEXT NOT NULL DEFAULT '',
                $COL_COMPANY TEXT NOT NULL DEFAULT '',
                $COL_PRIMARY_GOAL TEXT NOT NULL,
                $COL_KEY_TOPICS TEXT NOT NULL DEFAULT '',
                $COL_TOTAL_TURNS INTEGER NOT NULL DEFAULT 0,
                $COL_DRIFT_COUNT INTEGER NOT NULL DEFAULT 0,
                $COL_DRIFT_RATE REAL NOT NULL DEFAULT 0,
                $COL_MAX_DRIFT_LEVEL TEXT NOT NULL DEFAULT 'NONE',
                $COL_AVG_RELEVANCE REAL NOT NULL DEFAULT 1.0,
                $COL_TOPICS_COVERED TEXT NOT NULL DEFAULT '',
                $COL_TOPICS_MISSED TEXT NOT NULL DEFAULT '',
                $COL_INTERVENTION_COUNT INTEGER NOT NULL DEFAULT 0,
                $COL_SESSION_SUMMARY TEXT NOT NULL DEFAULT '',
                $COL_TURN_RECORDS TEXT NOT NULL DEFAULT '[]',
                $COL_STARTED_AT INTEGER NOT NULL,
                $COL_ENDED_AT INTEGER NOT NULL
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sessions_ended ON $TABLE_SESSIONS($COL_ENDED_AT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sessions_type ON $TABLE_SESSIONS($COL_INTERVIEW_TYPE)")
    }
}
