package top.hsyscn.opedrgent.cultivation.store

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import top.hsyscn.opedrgent.cultivation.model.growth.GrowthEvidence
import top.hsyscn.opedrgent.cultivation.model.growth.GrowthPeriodType
import top.hsyscn.opedrgent.cultivation.model.growth.GrowthReview

/**
 * 周期成长回顾持久化：全本地，不引入 Room，风格对齐 CultivationDatabase——
 * 原生 SQLiteOpenHelper + 单例。
 *
 * 表 [GrowthReviewDatabase.TABLE_GROWTH_REVIEW]：(period_type, period_start) 唯一，
 * 同一周期重复生成时覆盖旧 payload，返回行 id。
 */
class GrowthReviewStore(context: Context) {

    private val db = GrowthReviewDatabase.getInstance(context).writableDatabase

    /** 插入（同 periodType+periodStart 覆盖旧记录），返回行 id。 */
    suspend fun insert(review: GrowthReview): Long = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put(GrowthReviewDatabase.GR_PERIOD_TYPE, review.periodType.name)
            put(GrowthReviewDatabase.GR_PERIOD_START, review.periodStart)
            put(GrowthReviewDatabase.GR_PERIOD_END, review.periodEnd)
            put(GrowthReviewDatabase.GR_PAYLOAD_JSON, encode(review).toString())
            put(GrowthReviewDatabase.GR_CREATED_AT, review.createdAt)
        }
        db.insertWithOnConflict(
            GrowthReviewDatabase.TABLE_GROWTH_REVIEW, null, cv,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /** 全部历史回顾，周期起点新的在前。 */
    suspend fun listAll(): List<GrowthReview> = withContext(Dispatchers.IO) {
        db.query(
            GrowthReviewDatabase.TABLE_GROWTH_REVIEW, null, null, null, null, null,
            "${GrowthReviewDatabase.GR_PERIOD_START} DESC",
        ).use { cursorToList(it) }
    }

    suspend fun getById(id: Long): GrowthReview? = withContext(Dispatchers.IO) {
        db.query(
            GrowthReviewDatabase.TABLE_GROWTH_REVIEW, null,
            "${GrowthReviewDatabase.GR_ID}=?", arrayOf(id.toString()),
            null, null, null, "1",
        ).use { cursorToList(it).firstOrNull() }
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        db.delete(
            GrowthReviewDatabase.TABLE_GROWTH_REVIEW,
            "${GrowthReviewDatabase.GR_ID}=?", arrayOf(id.toString()),
        )
    }

    private fun encode(r: GrowthReview): JSONObject = JSONObject()
        .put("id", r.id)
        .put("periodType", r.periodType.name)
        .put("periodStart", r.periodStart)
        .put("periodEnd", r.periodEnd)
        .put("overall", r.overall)
        .put("changes", JSONArray(r.changes))
        .put("strengths", JSONArray(r.strengths))
        .put("focus", JSONArray(r.focus))
        .put("evidence", JSONArray().apply {
            r.evidence.forEach { e ->
                put(JSONObject().put("dimension", e.dimension).put("quote", e.quote))
            }
        })
        .put("createdAt", r.createdAt)

    private fun decode(raw: String?, id: Long, periodType: GrowthPeriodType, periodStart: Long,
                        periodEnd: Long, createdAt: Long): GrowthReview {
        val o = runCatching { JSONObject(raw ?: "{}") }.getOrElse { JSONObject() }
        return GrowthReview(
            id = id,
            periodType = periodType,
            periodStart = periodStart,
            periodEnd = periodEnd,
            overall = o.optString("overall", ""),
            changes = o.optJSONArray("changes").toStringList(),
            strengths = o.optJSONArray("strengths").toStringList(),
            focus = o.optJSONArray("focus").toStringList(),
            evidence = o.optJSONArray("evidence").let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val jo = arr.optJSONObject(i) ?: return@mapNotNull null
                    val dim = jo.optString("dimension", "").trim()
                    val quote = jo.optString("quote", "").trim()
                    if (dim.isBlank() || quote.isBlank()) null else GrowthEvidence(dim, quote)
                }
            },
            createdAt = createdAt,
        )
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i -> opt(i)?.toString()?.trim()?.takeIf { it.isNotBlank() } }
    }

    private fun cursorToList(cursor: Cursor): List<GrowthReview> {
        val items = mutableListOf<GrowthReview>()
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getLong(it.getColumnIndexOrThrow(GrowthReviewDatabase.GR_ID))
                val periodType = GrowthPeriodType.fromName(
                    it.getString(it.getColumnIndexOrThrow(GrowthReviewDatabase.GR_PERIOD_TYPE)),
                )
                val periodStart = it.getLong(it.getColumnIndexOrThrow(GrowthReviewDatabase.GR_PERIOD_START))
                val periodEnd = it.getLong(it.getColumnIndexOrThrow(GrowthReviewDatabase.GR_PERIOD_END))
                val createdAt = it.getLong(it.getColumnIndexOrThrow(GrowthReviewDatabase.GR_CREATED_AT))
                val payload = it.getString(it.getColumnIndexOrThrow(GrowthReviewDatabase.GR_PAYLOAD_JSON))
                items += decode(payload, id, periodType, periodStart, periodEnd, createdAt)
            }
        }
        return items
    }
}

/**
 * 成长回顾库：独立文件 growth_review.db，与 cultivation.db 解耦（后续清除/迁移互不影响）。
 */
private class GrowthReviewDatabase private constructor(context: Context) : SQLiteOpenHelper(
    context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION,
) {
    companion object {
        private const val DATABASE_NAME = "growth_review.db"
        private const val DATABASE_VERSION = 1

        const val TABLE_GROWTH_REVIEW = "growth_review"
        const val GR_ID = "id"
        const val GR_PERIOD_TYPE = "period_type"
        const val GR_PERIOD_START = "period_start"
        const val GR_PERIOD_END = "period_end"
        const val GR_PAYLOAD_JSON = "payload_json"
        const val GR_CREATED_AT = "created_at"

        @Volatile
        private var instance: GrowthReviewDatabase? = null

        fun getInstance(context: Context): GrowthReviewDatabase =
            instance ?: synchronized(this) {
                instance ?: GrowthReviewDatabase(context).also { instance = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_GROWTH_REVIEW (
                $GR_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $GR_PERIOD_TYPE TEXT NOT NULL DEFAULT 'WEEK',
                $GR_PERIOD_START INTEGER NOT NULL,
                $GR_PERIOD_END INTEGER NOT NULL,
                $GR_PAYLOAD_JSON TEXT NOT NULL DEFAULT '{}',
                $GR_CREATED_AT INTEGER NOT NULL,
                UNIQUE($GR_PERIOD_TYPE, $GR_PERIOD_START)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_growth_review_start ON $TABLE_GROWTH_REVIEW($GR_PERIOD_START DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 实验阶段：本地回顾数据可重建，升级时重建表；正式版改为增量迁移。
        db.execSQL("DROP TABLE IF EXISTS $TABLE_GROWTH_REVIEW")
        onCreate(db)
    }
}
