package top.hsyscn.opedrgent.cultivation.store

import android.content.Context
import android.database.sqlite.SQLiteOpenHelper

/**
 * 修炼模式本地数据库（全本地、不上传）。
 *
 * 风格对齐 storage/SproutReportStore：原生 SQLiteOpenHelper + 单例，不引入额外 ORM。
 * 两张表：
 * - [TABLE_BASELINE]：理想人格基准（带版本，仅一条处于活跃状态）。
 * - [TABLE_REFLECTION]：修炼会话统一记录表（P1）。批判镜与榜样镜都写入同一张表，
 *   用 [RS_LENS] 区分透镜，完整报告以 JSON 存于 [RS_PAYLOAD_JSON]，从而两镜都能落库与回看。
 */
class CultivationDatabase private constructor(context: Context) : SQLiteOpenHelper(
    context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION,
) {
    companion object {
        private const val DATABASE_NAME = "cultivation.db"
        private const val DATABASE_VERSION = 2

        const val TABLE_BASELINE = "virtue_baseline"
        const val BL_ID = "id"
        const val BL_VERSION = "version"
        const val BL_DIMENSIONS_JSON = "dimensions_json"
        const val BL_COMPLETE = "complete"
        const val BL_ACTIVE = "is_active"
        const val BL_CREATED_AT = "created_at"
        const val BL_UPDATED_AT = "updated_at"

        const val TABLE_REFLECTION = "reflection_session"
        const val RS_ID = "id"
        const val RS_LENS = "lens"
        const val RS_ROUTE = "route"
        const val RS_MODE = "mode"
        const val RS_EXEMPLAR = "exemplar"
        const val RS_PAYLOAD_JSON = "payload_json"
        const val RS_MARKS_JSON = "marks_json"
        const val RS_REFLECTION = "reflection"
        const val RS_RETAINED = "retained"
        const val RS_CREATED_AT = "created_at"

        @Volatile
        private var instance: CultivationDatabase? = null

        fun getInstance(context: Context): CultivationDatabase =
            instance ?: synchronized(this) {
                instance ?: CultivationDatabase(context).also { instance = it }
            }

        /** 备份/恢复钩子：关闭句柄并释放单例。 */
        @Synchronized
        fun closeAndReset() {
            runCatching { instance?.close() }
            instance = null
        }
    }

    override fun onCreate(db: android.database.sqlite.SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_BASELINE (
                $BL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $BL_VERSION INTEGER NOT NULL DEFAULT 1,
                $BL_DIMENSIONS_JSON TEXT NOT NULL DEFAULT '[]',
                $BL_COMPLETE INTEGER NOT NULL DEFAULT 0,
                $BL_ACTIVE INTEGER NOT NULL DEFAULT 0,
                $BL_CREATED_AT INTEGER NOT NULL,
                $BL_UPDATED_AT INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_baseline_active ON $TABLE_BASELINE($BL_ACTIVE)")

        db.execSQL(
            """
            CREATE TABLE $TABLE_REFLECTION (
                $RS_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $RS_LENS TEXT NOT NULL DEFAULT 'CRITIQUE',
                $RS_ROUTE TEXT NOT NULL DEFAULT 'ANALYZE',
                $RS_MODE TEXT NOT NULL DEFAULT 'STANDARD',
                $RS_EXEMPLAR TEXT NOT NULL DEFAULT '',
                $RS_PAYLOAD_JSON TEXT NOT NULL DEFAULT '{}',
                $RS_MARKS_JSON TEXT NOT NULL DEFAULT '{}',
                $RS_REFLECTION TEXT NOT NULL DEFAULT '',
                $RS_RETAINED INTEGER NOT NULL DEFAULT 1,
                $RS_CREATED_AT INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_reflection_lens ON $TABLE_REFLECTION($RS_LENS)")
        db.execSQL("CREATE INDEX idx_reflection_created ON $TABLE_REFLECTION($RS_CREATED_AT DESC)")
    }

    override fun onUpgrade(db: android.database.sqlite.SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 实验阶段：本地修炼数据可重建，升级时重建表；正式版需改为增量迁移。
        // v1 的 mirror_report 在 v2 由统一的 reflection_session 取代。
        db.execSQL("DROP TABLE IF EXISTS mirror_report")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_REFLECTION")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_BASELINE")
        onCreate(db)
    }
}
