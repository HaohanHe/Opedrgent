package top.hsyscn.opedrgent.intelligence

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import top.hsyscn.opedrgent.utils.DebugLog

/**
 * SQLite 持久化实现。
 *
 * 使用 Android 原生 SQLiteDatabase 实现向量记忆的持久化存储。
 * 适用于生产环境，App 重启后数据不丢失。
 *
 * ## 数据库表结构
 * ```sql
 * CREATE TABLE vector_memories (
 *     id TEXT PRIMARY KEY,
 *     content TEXT NOT NULL,
 *     embedding TEXT NOT NULL,   -- JSON 数组格式 [0.1, 0.2, ...]
 *     metadata TEXT,             -- JSON 对象
 *     created_at INTEGER,
 *     collection TEXT DEFAULT 'default'
 * );
 * CREATE INDEX idx_collection ON vector_memories(collection);
 * ```
 *
 * ## 特点
 * - 轻量级：无需 Room 依赖，仅使用原生 SQLite API
 * - 持久化：数据存储在本地数据库文件中
 * - 线程安全：通过 SQLiteDatabase 的线程安全机制保证
 * - 自动升级：支持数据库版本迁移
 */
class SqlitePersistence(
    private val context: Context,
    private val dbName: String = "vector_memory.db",
    private val dbVersion: Int = 1,
) : PersistenceLayer {

    /** 数据库帮助类，管理创建和版本管理 */
    private lateinit var dbHelper: SQLiteOpenHelper

    /** 可读数据库实例（懒加载） */
    private val readableDb: SQLiteDatabase by lazy { dbHelper.readableDatabase }

    /** 可写数据库实例（懒加载） */
    private val writableDb: SQLiteDatabase by lazy { dbHelper.writableDatabase }

    override fun initialize() {
        // 初始化数据库帮助类
        dbHelper = object : SQLiteOpenHelper(context, dbName, null, dbVersion) {
            override fun onCreate(db: SQLiteDatabase) {
                // 创建向量记忆表
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS vector_memories (
                        id TEXT PRIMARY KEY,
                        content TEXT NOT NULL,
                        embedding TEXT NOT NULL,
                        metadata TEXT,
                        created_at INTEGER,
                        collection TEXT DEFAULT 'default'
                    )
                """.trimIndent())

                // 创建集合索引，加速按集合查询
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS idx_collection 
                    ON vector_memories(collection)
                """.trimIndent())

                DebugLog.i("SqlitePersistence", "数据库表创建成功")
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                // 版本升级逻辑（预留）
                DebugLog.w("SqlitePersistence", "数据库从版本 $oldVersion 升级到 $newVersion")
            }
        }

        // 触发数据库初始化
        DebugLog.i("SqlitePersistence", "SQLite 持久化层初始化完成 (db=$dbName)")
    }

    override fun close() {
        if (::dbHelper.isInitialized) {
            dbHelper.close()
            DebugLog.i("SqlitePersistence", "数据库连接已关闭")
        }
    }

    override fun save(vector: MemoryVector): Boolean {
        return try {
            val values = vectorToContentValues(vector)
            // 使用 REPLACE 实现 upsert（存在则更新，不存在则插入）
            val rowId = writableDb.insertWithOnConflict(
                "vector_memories",
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE
            )
            rowId != -1L
        } catch (e: Exception) {
            DebugLog.e("SqlitePersistence", "保存向量失败: ${e.message}", e)
            false
        }
    }

    override fun saveBatch(vectors: List<MemoryVector>): Int {
        return try {
            writableDb.beginTransaction()
            var count = 0
            for (vector in vectors) {
                val values = vectorToContentValues(vector)
                val rowId = writableDb.insertWithOnConflict(
                    "vector_memories",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE
                )
                if (rowId != -1L) count++
            }
            writableDb.setTransactionSuccessful()
            count
        } catch (e: Exception) {
            DebugLog.e("SqlitePersistence", "批量保存失败: ${e.message}", e)
            0
        } finally {
            writableDb.endTransaction()
        }
    }

    override fun getById(id: String): MemoryVector? {
        return try {
            val cursor = readableDb.query(
                "vector_memories",
                null,  // 所有列
                "id = ?",
                arrayOf(id),
                null, null, null
            )

            cursor.use {
                if (it.moveToFirst()) cursorToVector(it) else null
            }
        } catch (e: Exception) {
            DebugLog.e("SqlitePersistence", "查询向量失败: ${e.message}", e)
            null
        }
    }

    override fun getAll(): List<MemoryVector> {
        return try {
            val cursor = readableDb.query(
                "vector_memories",
                null, null, null, null, null,
                "created_at ASC"  // 按创建时间升序排列
            )

            cursor.use { c ->
                val vectors = mutableListOf<MemoryVector>()
                while (c.moveToNext()) {
                    cursorToVector(c)?.let { vectors.add(it) }
                }
                vectors
            }
        } catch (e: Exception) {
            DebugLog.e("SqlitePersistence", "获取所有向量失败: ${e.message}", e)
            emptyList()
        }
    }

    override fun deleteById(id: String): Boolean {
        return try {
            val rowsDeleted = writableDb.delete(
                "vector_memories",
                "id = ?",
                arrayOf(id)
            )
            rowsDeleted > 0
        } catch (e: Exception) {
            DebugLog.e("SqlitePersistence", "删除向量失败: ${e.message}", e)
            false
        }
    }

    override fun deleteByCollection(collection: String): Int {
        return try {
            writableDb.delete(
                "vector_memories",
                "collection = ?",
                arrayOf(collection)
            )
        } catch (e: Exception) {
            DebugLog.e("SqlitePersistence", "删除集合失败: ${e.message}", e)
            0
        }
    }

    override fun clearAll(): Boolean {
        return try {
            writableDb.delete("vector_memories", null, null) >= 0
        } catch (e: Exception) {
            DebugLog.e("SqlitePersistence", "清空数据失败: ${e.message}", e)
            false
        }
    }

    override fun count(): Int {
        return try {
            val cursor = readableDb.rawQuery(
                "SELECT COUNT(*) FROM vector_memories",
                null
            )
            cursor.use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
        } catch (e: Exception) {
            DebugLog.e("SqlitePersistence", "统计数量失败: ${e.message}", e)
            0
        }
    }

    override fun getCollections(): Set<String> {
        return try {
            val cursor = readableDb.rawQuery(
                "SELECT DISTINCT collection FROM vector_memories",
                null
            )
            cursor.use { c ->
                val collections = mutableSetOf<String>()
                while (c.moveToNext()) {
                    collections.add(c.getString(0))
                }
                collections
            }
        } catch (e: Exception) {
            DebugLog.e("SqlitePersistence", "获取集合列表失败: ${e.message}", e)
            emptySet()
        }
    }

    // ==================== 内部工具方法 ====================

    /**
     * 将 MemoryVector 转换为 ContentValues（用于写入数据库）。
     */
    private fun vectorToContentValues(vector: MemoryVector): ContentValues {
        return ContentValues().apply {
            put("id", vector.id)
            put("content", vector.payload.content)
            // 将 FloatArray 转换为 JSON 数组字符串
            put("embedding", floatArrayToJson(vector.vector))
            // 将 payload 元信息转换为 JSON 对象字符串
            put("metadata", payloadToMetadataJson(vector.payload))
            put("created_at", vector.createdAt)
            put("collection", vector.collection)
        }
    }

    /**
     * 将数据库游标转换为 MemoryVector。
     */
    private fun cursorToVector(android.database.Cursor cursor): MemoryVector? {
        return try {
            val id = cursor.getString(cursor.getColumnIndexOrThrow("id"))
            val content = cursor.getString(cursor.getColumnIndexOrThrow("content"))
            val embeddingStr = cursor.getString(cursor.getColumnIndexOrThrow("embedding"))
            val metadataStr = cursor.getString(cursor.getColumnIndexOrThrow("metadata"))
            val createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at"))
            val collection = cursor.getString(cursor.getColumnIndexOrThrow("collection"))

            // 解析 JSON 数组为 FloatArray
            val embedding = jsonToFloatArray(embeddingStr)

            // 解析 JSON 对象为 MemoryPayload
            val payload = metadataJsonToPayload(metadataStr, content)

            MemoryVector(
                id = id,
                vector = embedding,
                payload = payload,
                collection = collection ?: "default",
                createdAt = createdAt,
            )
        } catch (e: Exception) {
            DebugLog.e("SqlitePersistence", "解析向量数据失败: ${e.message}", e)
            null
        }
    }

    /**
     * 将 FloatArray 转换为 JSON 数组字符串。
     *
     * 示例：[0.1f, 0.2f, 0.3f] → "[0.1,0.2,0.3]"
     */
    private fun floatArrayToJson(array: FloatArray): String {
        return JSONArray(array.toList()).toString()
    }

    /**
     * 将 JSON 数组字符串转换为 FloatArray。
     */
    private fun jsonToFloatArray(json: String): FloatArray {
        val jsonArray = JSONArray(json)
        val result = FloatArray(jsonArray.length())
        for (i in 0 until jsonArray.length()) {
            result[i] = jsonArray.getDouble(i).toFloat()
        }
        return result
    }

    /**
     * 将 MemoryPayload 转换为 JSON 元数据字符串。
     *
     * 仅保存关键字段，避免存储冗余数据。
     */
    private fun payloadToMetadataJson(payload: MemoryPayload): String {
        val json = JSONObject().apply {
            put("title", payload.title)
            put("tags", JSONArray(payload.tags.toList()))
            put("source", payload.source)
            put("importance", payload.importance)
            // 将扩展元数据也存入
            val metaObj = JSONObject()
            for ((key, value) in payload.metadata) {
                metaObj.put(key, value.toString())
            }
            put("extended_metadata", metaObj)
            put("embeddingModel", payload.embeddingModel)
        }
        return json.toString()
    }

    /**
     * 将 JSON 元数据字符串还原为 MemoryPayload。
     *
     * content 参数来自独立的 content 字段（非 metadata）。
     */
    private fun metadataJsonToPayload(metadataJson: String?, content: String): MemoryPayload {
        if (metadataJson.isNullOrBlank()) {
            return MemoryPayload(content = content)
        }

        return try {
            val json = JSONObject(metadataJson)

            // 解析 tags 集合
            val tagsSet = mutableSetOf<String>()
            val tagsArray = json.optJSONArray("tags")
            if (tagsArray != null) {
                for (i in 0 until tagsArray.length()) {
                    tagsSet.add(tagsArray.getString(i))
                }
            }

            // 解析扩展元数据
            val extendedMeta = mutableMapOf<String, Any>()
            val metaObj = json.optJSONObject("extended_metadata")
            if (metaObj != null) {
                val keys = metaObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    extendedMeta[key] = metaObj.get(key)
                }
            }

            MemoryPayload(
                title = json.optString("title", ""),
                content = content,
                tags = tagsSet,
                source = json.optString("source", ""),
                importance = json.optDouble("importance", 0.5).toFloat(),
                metadata = extendedMeta,
                embeddingModel = json.optString("embeddingModel", "tfidf-hash"),
            )
        } catch (e: Exception) {
            // JSON 解析失败时返回基础 payload
            DebugLog.w("SqlitePersistence", "元数据 JSON 解析失败，使用默认值: ${e.message}")
            MemoryPayload(content = content)
        }
    }
}
