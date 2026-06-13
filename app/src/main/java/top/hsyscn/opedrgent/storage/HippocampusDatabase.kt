package top.hsyscn.opedrgent.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class HippocampusDatabase(context: Context) : SQLiteOpenHelper(
    context, DATABASE_NAME, null, DATABASE_VERSION,
) {
    companion object {
        private const val DATABASE_NAME = "hippocampus_index.db"
        private const val DATABASE_VERSION = 2
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
        db.execSQL("CREATE INDEX idx_source ON $TABLE($COL_SOURCE_TYPE, $COL_SOURCE_ID)")
        db.execSQL("CREATE INDEX idx_title ON $TABLE($COL_TITLE)")
        db.execSQL("CREATE INDEX idx_scope ON $TABLE($COL_SCOPE)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // v1 -> v2: 新增 scope 列，旧数据默认为 project
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_SCOPE TEXT NOT NULL DEFAULT 'project'")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_scope ON $TABLE($COL_SCOPE)")
        }
    }
}
