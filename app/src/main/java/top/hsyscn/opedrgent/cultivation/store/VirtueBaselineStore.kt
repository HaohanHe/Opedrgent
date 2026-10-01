package top.hsyscn.opedrgent.cultivation.store

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import top.hsyscn.opedrgent.cultivation.model.VirtueBaseline
import top.hsyscn.opedrgent.cultivation.model.VirtueDimension
import top.hsyscn.opedrgent.utils.DebugLog

/**
 * 理想人格基准存储（需求卡 N1）。
 *
 * 只做持久化与版本管理，不做任何语义判断；基准是否空泛、是否需要追问由模型决定。
 * 同一时刻只有一条基准处于活跃状态；每次校准则整体存为新版本。
 */
class VirtueBaselineStore(context: Context) {

    private val db = CultivationDatabase.getInstance(context).writableDatabase

    /** 保存一份校准后的基准并将其置为唯一活跃版本，返回新行 id。 */
    suspend fun saveAsActive(baseline: VirtueBaseline): Long = withContext(Dispatchers.IO) {
        db.beginTransaction()
        try {
            // 解除旧活跃标记，保留历史版本
            val clear = ContentValues().apply { put(CultivationDatabase.BL_ACTIVE, 0) }
            db.update(CultivationDatabase.TABLE_BASELINE, clear, "${CultivationDatabase.BL_ACTIVE}=1", null)

            val nextVersion = currentVersionLocked() + 1
            val now = System.currentTimeMillis()
            val cv = ContentValues().apply {
                put(CultivationDatabase.BL_VERSION, nextVersion)
                put(CultivationDatabase.BL_DIMENSIONS_JSON, encodeDimensions(baseline.dimensions))
                put(CultivationDatabase.BL_COMPLETE, if (baseline.complete) 1 else 0)
                put(CultivationDatabase.BL_ACTIVE, 1)
                put(CultivationDatabase.BL_CREATED_AT, baseline.createdAt.takeIf { it > 0 } ?: now)
                put(CultivationDatabase.BL_UPDATED_AT, now)
            }
            val id = db.insert(CultivationDatabase.TABLE_BASELINE, null, cv)
            db.setTransactionSuccessful()
            id
        } finally {
            db.endTransaction()
        }
    }

    /** 读取当前活跃基准；不存在返回 null。 */
    suspend fun getActive(): VirtueBaseline? = withContext(Dispatchers.IO) {
        db.query(
            CultivationDatabase.TABLE_BASELINE, null,
            "${CultivationDatabase.BL_ACTIVE}=1", null, null, null,
            "${CultivationDatabase.BL_VERSION} DESC", "1",
        ).use { cursor -> cursorToList(cursor).firstOrNull() }
    }

    /** 历史版本列表（新版本在前）。 */
    suspend fun listVersions(limit: Int = 20): List<VirtueBaseline> = withContext(Dispatchers.IO) {
        db.query(
            CultivationDatabase.TABLE_BASELINE, null, null, null, null, null,
            "${CultivationDatabase.BL_VERSION} DESC", limit.toString(),
        ).use { cursor -> cursorToList(cursor) }
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        db.delete(CultivationDatabase.TABLE_BASELINE, null, null)
    }

    private fun currentVersionLocked(): Int {
        db.rawQuery(
            "SELECT MAX(${CultivationDatabase.BL_VERSION}) FROM ${CultivationDatabase.TABLE_BASELINE}",
            null,
        ).use { cursor ->
            return if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getInt(0) else 0
        }
    }

    private fun cursorToList(cursor: Cursor): List<VirtueBaseline> {
        val items = mutableListOf<VirtueBaseline>()
        cursor.use {
            while (it.moveToNext()) {
                items.add(
                    VirtueBaseline(
                        id = it.getLong(it.getColumnIndexOrThrow(CultivationDatabase.BL_ID)),
                        version = it.getInt(it.getColumnIndexOrThrow(CultivationDatabase.BL_VERSION)),
                        dimensions = decodeDimensions(
                            it.getString(it.getColumnIndexOrThrow(CultivationDatabase.BL_DIMENSIONS_JSON))
                        ),
                        complete = it.getInt(it.getColumnIndexOrThrow(CultivationDatabase.BL_COMPLETE)) == 1,
                        createdAt = it.getLong(it.getColumnIndexOrThrow(CultivationDatabase.BL_CREATED_AT)),
                        updatedAt = it.getLong(it.getColumnIndexOrThrow(CultivationDatabase.BL_UPDATED_AT)),
                    )
                )
            }
        }
        return items
    }

    private fun encodeDimensions(dims: List<VirtueDimension>): String {
        val arr = JSONArray()
        dims.forEach { d ->
            arr.put(
                JSONObject()
                    .put("name", d.name)
                    .put("do", JSONArray(d.doBehaviors))
                    .put("dont", JSONArray(d.dontBehaviors))
            )
        }
        return arr.toString()
    }

    private fun decodeDimensions(raw: String?): List<VirtueDimension> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                fun strArr(key: String) = o.optJSONArray(key)?.let { a ->
                    (0 until a.length()).map { a.getString(it) }
                } ?: emptyList()
                VirtueDimension(
                    name = o.optString("name", ""),
                    doBehaviors = strArr("do"),
                    dontBehaviors = strArr("dont"),
                )
            }
        }.getOrElse {
            DebugLog.w(TAG, "基准维度解析失败: ${it.message}")
            emptyList()
        }
    }

    companion object {
        private const val TAG = "VirtueBaselineStore"
    }
}
