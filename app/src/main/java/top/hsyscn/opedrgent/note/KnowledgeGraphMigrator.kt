package top.hsyscn.opedrgent.note

import android.content.Context
import org.json.JSONObject
import top.hsyscn.opedrgent.note.graph.GraphEdgeEntity
import top.hsyscn.opedrgent.note.graph.GraphEmbeddingEntity
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File

/**
 * 知识图谱旧版 JSON 数据迁移器。
 *
 * 读取 filesDir/knowledge_graph.json，将其中的 links 和 embeddings 写入 SQLite，
 * 迁移完成后将原文件重命名为 knowledge_graph.json.bak。
 */
class KnowledgeGraphMigrator(context: Context) {

    companion object {
        private const val TAG = "KnowledgeGraphMigrator"
        private const val GRAPH_FILE = "knowledge_graph.json"
        private const val BACKUP_SUFFIX = ".bak"
        private const val DEFAULT_RELATION_TYPE = "SEMANTIC_SIMILAR"
        private const val LEGACY_TFIDF_PROVIDER = "legacy-tfidf"
        private const val LEGACY_TFIDF_MODEL = "sparse-tfidf"

        /**
         * 如果存在旧版 JSON 知识图谱数据，则迁移到 SQLite。
         *
         * @return 是否成功完成迁移（无需迁移也返回 true）。
         */
        fun migrateIfNeeded(context: Context, store: KnowledgeGraphStore): Boolean {
            return KnowledgeGraphMigrator(context).migrate()
        }
    }

    private val contextRef = context.applicationContext
    private val store = KnowledgeGraphStore(contextRef)

    /**
     * 执行迁移。如果原文件不存在或已迁移过，则直接返回成功。
     *
     * @return 是否成功完成迁移（无需迁移也返回 true）。
     */
    fun migrate(): Boolean {
        val graphFile = File(contextRef.filesDir, GRAPH_FILE)
        if (!graphFile.exists()) {
            DebugLog.i(TAG, "旧版知识图谱文件不存在，跳过迁移")
            return true
        }

        if (store.wasMigrated(version = 1, source = graphFile.absolutePath)) {
            DebugLog.i(TAG, "旧版知识图谱已迁移过，跳过")
            return true
        }

        return try {
            val json = JSONObject(graphFile.readText())
            val version = json.optInt("version", 1)

            migrateLinks(json)
            if (version >= 2) {
                migrateEmbeddings(json)
            }

            val backupFile = File(contextRef.filesDir, "$GRAPH_FILE$BACKUP_SUFFIX")
            if (backupFile.exists()) backupFile.delete()
            val renamed = graphFile.renameTo(backupFile)

            store.recordMigration(version = 1, source = graphFile.absolutePath)
            DebugLog.i(TAG, "知识图谱迁移完成: v$version, 备份=$renamed")
            true
        } catch (e: Exception) {
            DebugLog.e(TAG, "知识图谱迁移失败: ${e.message}", e)
            false
        }
    }

    private fun migrateLinks(json: JSONObject) {
        val linksObj = json.optJSONObject("links") ?: return
        val edges = mutableListOf<GraphEdgeEntity>()
        val seen = mutableSetOf<Pair<String, String>>()
        val keys = linksObj.keys()
        while (keys.hasNext()) {
            val sourceId = keys.next()
            val arr = linksObj.optJSONArray(sourceId) ?: continue
            for (i in 0 until arr.length()) {
                val targetId = arr.optString(i, null) ?: continue
                val (a, b) = if (sourceId < targetId) sourceId to targetId else targetId to sourceId
                val key = a to b
                if (key in seen) continue
                seen.add(key)
                edges.add(
                    GraphEdgeEntity(
                        sourceId = a,
                        targetId = b,
                        relationType = DEFAULT_RELATION_TYPE,
                        weight = 0.5f,
                        createdAt = System.currentTimeMillis(),
                    )
                )
            }
        }
        store.upsertEdges(edges)
        DebugLog.i(TAG, "迁移链接数: ${edges.size}")
    }

    private fun migrateEmbeddings(json: JSONObject) {
        val embeddingsObj = json.optJSONObject("embeddings") ?: return
        val embeddings = mutableListOf<GraphEmbeddingEntity>()
        val keys = embeddingsObj.keys()
        while (keys.hasNext()) {
            val nodeId = keys.next()
            val vecObj = embeddingsObj.optJSONObject(nodeId) ?: continue
            val map = mutableMapOf<String, Float>()
            val vecKeys = vecObj.keys()
            while (vecKeys.hasNext()) {
                val key = vecKeys.next()
                map[key] = vecObj.optDouble(key, 0.0).toFloat()
            }
            if (map.isEmpty()) continue
            val jsonBytes = JSONObject(map.mapValues { it.value.toDouble() }).toString().toByteArray(Charsets.UTF_8)
            embeddings.add(
                GraphEmbeddingEntity(
                    nodeId = nodeId,
                    provider = LEGACY_TFIDF_PROVIDER,
                    model = LEGACY_TFIDF_MODEL,
                    dimension = map.size,
                    vector = jsonBytes,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
        store.saveEmbeddings(embeddings)
        DebugLog.i(TAG, "迁移 embedding 数: ${embeddings.size}")
    }
}
