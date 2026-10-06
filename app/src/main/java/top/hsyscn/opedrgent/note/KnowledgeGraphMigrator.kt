package top.hsyscn.opedrgent.note

import android.content.Context
import org.json.JSONObject
import top.hsyscn.opedrgent.note.graph.GraphEdgeEntity
import top.hsyscn.opedrgent.note.graph.GraphNodeEntity
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File

/**
 * 知识图谱旧版 JSON 数据迁移器。
 *
 * 读取 filesDir/knowledge_graph.json，将其中的 links 写入 SQLite，
 * 迁移完成后将原文件重命名为 knowledge_graph.json.bak。
 *
 * 说明：
 * - 复用调用方传入的 [KnowledgeGraphStore]，不再自行包装第二个连接。
 * - legacy-tfidf 的 embedding 以 JSON 文本字节落盘，与运行时小端 float 解码格式不一致，
 *   迁移过来不可用，故不再迁移 embedding（重建流程会重新计算）。
 */
class KnowledgeGraphMigrator(
    context: Context,
    private val store: KnowledgeGraphStore,
) {

    companion object {
        private const val TAG = "KnowledgeGraphMigrator"
        private const val GRAPH_FILE = "knowledge_graph.json"
        private const val BACKUP_SUFFIX = ".bak"
        private const val DEFAULT_RELATION_TYPE = "SEMANTIC_SIMILAR"

        /**
         * 如果存在旧版 JSON 知识图谱数据，则迁移到 SQLite。
         *
         * @return 是否成功完成迁移（无需迁移也返回 true）。
         */
        fun migrateIfNeeded(context: Context, store: KnowledgeGraphStore): Boolean {
            return KnowledgeGraphMigrator(context, store).migrate()
        }
    }

    private val contextRef = context.applicationContext

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

            migrateLinks(json)

            val backupFile = File(contextRef.filesDir, "$GRAPH_FILE$BACKUP_SUFFIX")
            if (backupFile.exists()) backupFile.delete()
            val renamed = graphFile.renameTo(backupFile)

            store.recordMigration(version = 1, source = graphFile.absolutePath)
            DebugLog.i(TAG, "知识图谱迁移完成: 备份=$renamed")
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
        if (edges.isEmpty()) return
        // 边引用的节点此时没有对应 kg_nodes 行，会留下悬空边（可视化/统计均会出现幻影节点）。
        // 先补建最小节点行，等后续 rebuildFromNotes 再补全标题/关键词等信息。
        val endpointIds = edges.flatMap { listOf(it.sourceId, it.targetId) }.toSet()
        val now = System.currentTimeMillis()
        store.upsertNodes(
            endpointIds.map { GraphNodeEntity(id = it, updatedAt = now) },
            useTransaction = false,
        )
        store.upsertEdges(edges)
        DebugLog.i(TAG, "迁移链接数: ${edges.size}, 补建节点数: ${endpointIds.size}")
    }
}
