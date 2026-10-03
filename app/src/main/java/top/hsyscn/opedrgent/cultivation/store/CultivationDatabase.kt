package top.hsyscn.opedrgent.cultivation.store

import android.content.Context
import android.database.sqlite.SQLiteOpenHelper
import top.hsyscn.opedrgent.utils.SqliteMigrations

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
        // 增量迁移：按版本顺序执行，绝不 DROP 当前用户数据表（reflection_session / virtue_baseline）。
        SqliteMigrations.runUpgrade(db, oldVersion, newVersion, mapOf(
            // v1 -> v2：批判镜旧表 mirror_report 由统一的 reflection_session 取代，并补建 virtue_baseline。
            1 to { d -> migrateV1ToV2(d) },
        ))
    }

    /**
     * v1 -> v2 迁移（幂等，可重复执行）：
     *  1) CREATE TABLE IF NOT EXISTS reflection_session / virtue_baseline（保留任何已存在行，绝不 DROP）；
     *  2) best-effort 迁移旧 mirror_report：仅当其列能与 reflection_session 列对应时，
     *     INSERT OR IGNORE 到 reflection_session；列无法对应则跳过、不臆造数据；
     *  3) 仅在 best-effort 之后 DROP 真正废弃的 mirror_report（非当前用户数据表）。
     */
    private fun migrateV1ToV2(db: android.database.sqlite.SQLiteDatabase) {
        // 1) 确保两张当前表存在（幂等，不影响已有数据），并补齐索引。
        SqliteMigrations.ensureTable(
            db,
            TABLE_BASELINE,
            """
            CREATE TABLE IF NOT EXISTS $TABLE_BASELINE (
                $BL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $BL_VERSION INTEGER NOT NULL DEFAULT 1,
                $BL_DIMENSIONS_JSON TEXT NOT NULL DEFAULT '[]',
                $BL_COMPLETE INTEGER NOT NULL DEFAULT 0,
                $BL_ACTIVE INTEGER NOT NULL DEFAULT 0,
                $BL_CREATED_AT INTEGER NOT NULL,
                $BL_UPDATED_AT INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_baseline_active ON $TABLE_BASELINE($BL_ACTIVE)")

        SqliteMigrations.ensureTable(
            db,
            TABLE_REFLECTION,
            """
            CREATE TABLE IF NOT EXISTS $TABLE_REFLECTION (
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
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_reflection_lens ON $TABLE_REFLECTION($RS_LENS)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_reflection_created ON $TABLE_REFLECTION($RS_CREATED_AT DESC)")

        // 2) best-effort：旧 mirror_report 仅在其列与 reflection_session 列同名可对应时才迁移。
        if (SqliteMigrations.tableExists(db, "mirror_report")) {
            // 可安全写入 reflection_session 的列（排除自增主键 id，由目标表自动生成）。
            val targetColumns = listOf(
                RS_LENS, RS_ROUTE, RS_MODE, RS_EXEMPLAR, RS_PAYLOAD_JSON,
                RS_MARKS_JSON, RS_REFLECTION, RS_RETAINED, RS_CREATED_AT,
            )
            // 取 mirror_report 实际存在且与目标列同名的交集。
            val shared = ArrayList<String>()
            db.rawQuery("PRAGMA table_info(\"mirror_report\")", null).use { c ->
                val nameIdx = c.getColumnIndexOrThrow("name")
                while (c.moveToNext()) {
                    val col = c.getString(nameIdx)
                    if (col != null && targetColumns.contains(col)) shared.add(col)
                }
            }
            // 至少要有内容/时间/透镜列可对应，否则视为不可对应、跳过迁移（不臆造数据）。
            val hasMeaningfulMapping = shared.any {
                it == RS_PAYLOAD_JSON || it == RS_REFLECTION || it == RS_CREATED_AT || it == RS_LENS
            }
            if (hasMeaningfulMapping) {
                val cols = shared.joinToString(", ") { "\"$it\"" }
                db.execSQL(
                    "INSERT OR IGNORE INTO \"$TABLE_REFLECTION\" ($cols) SELECT $cols FROM \"mirror_report\"",
                )
            }
            // 3) 迁移尝试完成后，DROP 这张被取代的废弃表（非当前用户数据表）。
            db.execSQL("DROP TABLE IF EXISTS \"mirror_report\"")
        }
    }
}
