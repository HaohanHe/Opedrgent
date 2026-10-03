package top.hsyscn.opedrgent.action

import android.content.Context
import android.database.sqlite.SQLiteOpenHelper
import top.hsyscn.opedrgent.utils.SqliteMigrations

/**
 * 行动项本地数据库（全本地、不上传）。
 *
 * 风格对齐 cultivation/store/CultivationDatabase：原生 SQLiteOpenHelper + 单例，不引入额外 ORM
 * （本工程未配置 Room 注解处理器，沿用既有原生 SQL 模式以保证可编译、可测试）。
 *
 * 一张表 [TABLE_ACTION]：行动项主表，来源情境字段随行冗余存储，便于回看时不回查复盘记录。
 */
class ActionDatabase private constructor(context: Context) : SQLiteOpenHelper(
    context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION,
) {
    companion object {
        private const val DATABASE_NAME = "action.db"
        private const val DATABASE_VERSION = 1

        const val TABLE_ACTION = "action_item"
        const val AC_ID = "id"
        const val AC_TITLE = "title"
        const val AC_KIND = "kind"
        const val AC_STATUS = "status"
        const val AC_SOURCE_TYPE_LABEL = "source_type_label"
        const val AC_SOURCE_TITLE = "source_title"
        const val AC_SOURCE_SNIPPET = "source_snippet"
        const val AC_SOURCE_REFLECTION_ID = "source_reflection_id"
        const val AC_CREATED_AT = "created_at"
        const val AC_COMPLETED_AT = "completed_at"

        @Volatile
        private var instance: ActionDatabase? = null

        fun getInstance(context: Context): ActionDatabase =
            instance ?: synchronized(this) {
                instance ?: ActionDatabase(context).also { instance = it }
            }

        /** 备份/恢复钩子：关闭句柄并释放单例，使下次 getInstance 重建。 */
        @Synchronized
        fun closeAndReset() {
            runCatching { instance?.close() }
            instance = null
        }
    }

    override fun onCreate(db: android.database.sqlite.SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_ACTION (
                $AC_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $AC_TITLE TEXT NOT NULL,
                $AC_KIND TEXT NOT NULL DEFAULT 'GENERAL',
                $AC_STATUS TEXT NOT NULL DEFAULT 'OPEN',
                $AC_SOURCE_TYPE_LABEL TEXT NOT NULL DEFAULT '',
                $AC_SOURCE_TITLE TEXT NOT NULL DEFAULT '',
                $AC_SOURCE_SNIPPET TEXT NOT NULL DEFAULT '',
                $AC_SOURCE_REFLECTION_ID INTEGER NOT NULL DEFAULT 0,
                $AC_CREATED_AT INTEGER NOT NULL,
                $AC_COMPLETED_AT INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        // 幂等导入的 DB 层兜底：同一复盘记录 + 同一标题只允许一条。
        db.execSQL(
            "CREATE UNIQUE INDEX idx_action_reflection_title ON $TABLE_ACTION(" +
                "$AC_SOURCE_REFLECTION_ID, $AC_TITLE)"
        )
        db.execSQL("CREATE INDEX idx_action_status ON $TABLE_ACTION($AC_STATUS)")
        db.execSQL("CREATE INDEX idx_action_created ON $TABLE_ACTION($AC_CREATED_AT DESC)")
    }

    override fun onUpgrade(db: android.database.sqlite.SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 增量迁移：按版本顺序执行，绝不 DROP 用户数据表。当前 DATABASE_VERSION=1、无 schema 差异；
        // 未来 bump 版本时在此追加步骤（示例见下），例如为 action_item 加列：
        //   1 to { d -> SqliteMigrations.addColumnIfMissing(d, TABLE_ACTION, "new_col", "TEXT NOT NULL DEFAULT ''") }
        SqliteMigrations.runUpgrade(db, oldVersion, newVersion, mapOf(
            // 未来：1 -> { d -> SqliteMigrations.addColumnIfMissing(d, TABLE_ACTION, ...) }
        ))
        // 安全兜底：确保当前表存在（CREATE TABLE IF NOT EXISTS，不影响已有数据）。
        SqliteMigrations.ensureTable(
            db,
            TABLE_ACTION,
            """
            CREATE TABLE IF NOT EXISTS $TABLE_ACTION (
                $AC_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $AC_TITLE TEXT NOT NULL,
                $AC_KIND TEXT NOT NULL DEFAULT 'GENERAL',
                $AC_STATUS TEXT NOT NULL DEFAULT 'OPEN',
                $AC_SOURCE_TYPE_LABEL TEXT NOT NULL DEFAULT '',
                $AC_SOURCE_TITLE TEXT NOT NULL DEFAULT '',
                $AC_SOURCE_SNIPPET TEXT NOT NULL DEFAULT '',
                $AC_SOURCE_REFLECTION_ID INTEGER NOT NULL DEFAULT 0,
                $AC_CREATED_AT INTEGER NOT NULL,
                $AC_COMPLETED_AT INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
    }
}
