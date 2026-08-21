package top.hsyscn.opedrgent.note

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import top.hsyscn.opedrgent.note.graph.GraphEdgeEntity
import top.hsyscn.opedrgent.note.graph.GraphEmbeddingEntity
import top.hsyscn.opedrgent.note.graph.GraphEntity
import top.hsyscn.opedrgent.note.graph.GraphNodeEntity
import top.hsyscn.opedrgent.note.graph.GraphNodeEntityRelation

/**
 * 知识图谱数据存储层（基于原生 SQLite）。
 *
 * 提供节点、边、embedding、实体及节点-实体关联的完整 CRUD，
 * 所有批量写操作均包裹事务。
 */
class KnowledgeGraphStore(context: Context) {

    companion object {
        private const val TAG = "KnowledgeGraphStore"
        private const val DEFAULT_RELATION_TYPE = "SEMANTIC_SIMILAR"
        private const val LEGACY_TFIDF_PROVIDER = "legacy-tfidf"
    }

    private val db: SQLiteDatabase by lazy { KnowledgeGraphDatabase(context).writableDatabase }

    // ============================================================
    // 节点 CRUD
    // ============================================================

    fun upsertNode(node: GraphNodeEntity) {
        val values = ContentValues().apply {
            put(KnowledgeGraphDatabase.COL_NODE_ID, node.id)
            put(KnowledgeGraphDatabase.COL_NODE_TITLE, node.title)
            put(KnowledgeGraphDatabase.COL_NODE_SUMMARY, node.summary)
            put(KnowledgeGraphDatabase.COL_NODE_KEYWORDS, node.keywords)
            put(KnowledgeGraphDatabase.COL_NODE_UPDATED_AT, node.updatedAt)
            put(KnowledgeGraphDatabase.COL_NODE_CONTENT_HASH, node.contentHash)
        }
        db.insertWithOnConflict(
            KnowledgeGraphDatabase.TABLE_NODES,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun upsertNodes(nodes: List<GraphNodeEntity>, useTransaction: Boolean = true) {
        if (nodes.isEmpty()) return
        if (!useTransaction) {
            for (node in nodes) upsertNode(node)
            return
        }
        db.beginTransaction()
        try {
            for (node in nodes) upsertNode(node)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun deleteNode(nodeId: String): Boolean {
        val affected = db.delete(
            KnowledgeGraphDatabase.TABLE_NODES,
            "${KnowledgeGraphDatabase.COL_NODE_ID}=?",
            arrayOf(nodeId),
        )
        return affected > 0
    }

    fun getNode(nodeId: String): GraphNodeEntity? {
        db.query(
            KnowledgeGraphDatabase.TABLE_NODES,
            null,
            "${KnowledgeGraphDatabase.COL_NODE_ID}=?",
            arrayOf(nodeId),
            null,
            null,
            null,
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursorToNode(cursor) else null
        }
    }

    fun getAllNodes(): List<GraphNodeEntity> {
        val list = mutableListOf<GraphNodeEntity>()
        db.query(
            KnowledgeGraphDatabase.TABLE_NODES,
            null,
            null,
            null,
            null,
            null,
            "${KnowledgeGraphDatabase.COL_NODE_UPDATED_AT} DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursorToNode(cursor))
            }
        }
        return list
    }

    // ============================================================
    // 边 CRUD
    // ============================================================

    fun upsertEdge(edge: GraphEdgeEntity): Long {
        val existing = findEdge(edge.sourceId, edge.targetId, edge.relationType)
        return if (existing != null) {
            val values = ContentValues().apply {
                put(KnowledgeGraphDatabase.COL_EDGE_WEIGHT, edge.weight)
                put(KnowledgeGraphDatabase.COL_EDGE_REASON, edge.reason)
                put(KnowledgeGraphDatabase.COL_EDGE_CREATED_AT, edge.createdAt)
            }
            db.update(
                KnowledgeGraphDatabase.TABLE_EDGES,
                values,
                "${KnowledgeGraphDatabase.COL_EDGE_ID}=?",
                arrayOf(existing.id.toString()),
            )
            existing.id
        } else {
            val values = ContentValues().apply {
                put(KnowledgeGraphDatabase.COL_EDGE_SOURCE_ID, edge.sourceId)
                put(KnowledgeGraphDatabase.COL_EDGE_TARGET_ID, edge.targetId)
                put(KnowledgeGraphDatabase.COL_EDGE_RELATION_TYPE, edge.relationType)
                put(KnowledgeGraphDatabase.COL_EDGE_WEIGHT, edge.weight)
                put(KnowledgeGraphDatabase.COL_EDGE_REASON, edge.reason)
                put(KnowledgeGraphDatabase.COL_EDGE_CREATED_AT, edge.createdAt)
            }
            db.insertOrThrow(KnowledgeGraphDatabase.TABLE_EDGES, null, values)
        }
    }

    fun upsertEdges(edges: List<GraphEdgeEntity>, useTransaction: Boolean = true) {
        if (edges.isEmpty()) return
        if (!useTransaction) {
            for (edge in edges) upsertEdge(edge)
            return
        }
        db.beginTransaction()
        try {
            for (edge in edges) upsertEdge(edge)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun deleteEdge(edgeId: Long): Boolean {
        val affected = db.delete(
            KnowledgeGraphDatabase.TABLE_EDGES,
            "${KnowledgeGraphDatabase.COL_EDGE_ID}=?",
            arrayOf(edgeId.toString()),
        )
        return affected > 0
    }

    fun getEdgesForNode(nodeId: String): List<GraphEdgeEntity> {
        val list = mutableListOf<GraphEdgeEntity>()
        db.query(
            KnowledgeGraphDatabase.TABLE_EDGES,
            null,
            "${KnowledgeGraphDatabase.COL_EDGE_SOURCE_ID}=? OR ${KnowledgeGraphDatabase.COL_EDGE_TARGET_ID}=?",
            arrayOf(nodeId, nodeId),
            null,
            null,
            "${KnowledgeGraphDatabase.COL_EDGE_WEIGHT} DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursorToEdge(cursor))
            }
        }
        return list
    }

    fun getAllEdges(): List<GraphEdgeEntity> {
        val list = mutableListOf<GraphEdgeEntity>()
        db.query(
            KnowledgeGraphDatabase.TABLE_EDGES,
            null,
            null,
            null,
            null,
            null,
            "${KnowledgeGraphDatabase.COL_EDGE_CREATED_AT} DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursorToEdge(cursor))
            }
        }
        return list
    }

    fun edgeExists(sourceId: String, targetId: String, relationType: String = DEFAULT_RELATION_TYPE): Boolean {
        return findEdge(sourceId, targetId, relationType) != null
    }

    private fun findEdge(sourceId: String, targetId: String, relationType: String): GraphEdgeEntity? {
        db.query(
            KnowledgeGraphDatabase.TABLE_EDGES,
            null,
            "${KnowledgeGraphDatabase.COL_EDGE_SOURCE_ID}=? AND ${KnowledgeGraphDatabase.COL_EDGE_TARGET_ID}=? AND ${KnowledgeGraphDatabase.COL_EDGE_RELATION_TYPE}=?",
            arrayOf(sourceId, targetId, relationType),
            null,
            null,
            null,
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursorToEdge(cursor) else null
        }
    }

    // ============================================================
    // Embedding 读写
    // ============================================================

    fun saveEmbedding(embedding: GraphEmbeddingEntity) {
        val values = ContentValues().apply {
            put(KnowledgeGraphDatabase.COL_EMB_NODE_ID, embedding.nodeId)
            put(KnowledgeGraphDatabase.COL_EMB_PROVIDER, embedding.provider)
            put(KnowledgeGraphDatabase.COL_EMB_MODEL, embedding.model)
            put(KnowledgeGraphDatabase.COL_EMB_DIMENSION, embedding.dimension)
            put(KnowledgeGraphDatabase.COL_EMB_VECTOR, embedding.vector)
            put(KnowledgeGraphDatabase.COL_EMB_UPDATED_AT, embedding.updatedAt)
        }
        db.insertWithOnConflict(
            KnowledgeGraphDatabase.TABLE_EMBEDDINGS,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun saveEmbeddings(embeddings: List<GraphEmbeddingEntity>, useTransaction: Boolean = true) {
        if (embeddings.isEmpty()) return
        if (!useTransaction) {
            for (embedding in embeddings) saveEmbedding(embedding)
            return
        }
        db.beginTransaction()
        try {
            for (embedding in embeddings) saveEmbedding(embedding)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getEmbedding(nodeId: String): GraphEmbeddingEntity? {
        db.query(
            KnowledgeGraphDatabase.TABLE_EMBEDDINGS,
            null,
            "${KnowledgeGraphDatabase.COL_EMB_NODE_ID}=?",
            arrayOf(nodeId),
            null,
            null,
            null,
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursorToEmbedding(cursor) else null
        }
    }

    fun deleteEmbedding(nodeId: String): Boolean {
        val affected = db.delete(
            KnowledgeGraphDatabase.TABLE_EMBEDDINGS,
            "${KnowledgeGraphDatabase.COL_EMB_NODE_ID}=?",
            arrayOf(nodeId),
        )
        return affected > 0
    }

    // ============================================================
    // 实体 CRUD
    // ============================================================

    fun upsertEntity(entity: GraphEntity): Long {
        val existing = getEntityByName(entity.name)
        return if (existing != null) {
            val values = ContentValues().apply {
                put(KnowledgeGraphDatabase.COL_ENTITY_TYPE, entity.entityType)
                put(KnowledgeGraphDatabase.COL_ENTITY_FREQUENCY, existing.frequency + 1)
            }
            db.update(
                KnowledgeGraphDatabase.TABLE_ENTITIES,
                values,
                "${KnowledgeGraphDatabase.COL_ENTITY_ID}=?",
                arrayOf(existing.id.toString()),
            )
            existing.id
        } else {
            val values = ContentValues().apply {
                put(KnowledgeGraphDatabase.COL_ENTITY_NAME, entity.name)
                put(KnowledgeGraphDatabase.COL_ENTITY_TYPE, entity.entityType)
                put(KnowledgeGraphDatabase.COL_ENTITY_FREQUENCY, entity.frequency)
            }
            db.insertOrThrow(KnowledgeGraphDatabase.TABLE_ENTITIES, null, values)
        }
    }

    fun upsertEntities(entities: List<GraphEntity>, useTransaction: Boolean = true) {
        if (entities.isEmpty()) return
        if (!useTransaction) {
            for (entity in entities) upsertEntity(entity)
            return
        }
        db.beginTransaction()
        try {
            for (entity in entities) upsertEntity(entity)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getEntityByName(name: String): GraphEntity? {
        db.query(
            KnowledgeGraphDatabase.TABLE_ENTITIES,
            null,
            "${KnowledgeGraphDatabase.COL_ENTITY_NAME}=?",
            arrayOf(name),
            null,
            null,
            null,
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursorToEntity(cursor) else null
        }
    }

    fun getAllEntities(): List<GraphEntity> {
        val list = mutableListOf<GraphEntity>()
        db.query(
            KnowledgeGraphDatabase.TABLE_ENTITIES,
            null,
            null,
            null,
            null,
            null,
            "${KnowledgeGraphDatabase.COL_ENTITY_FREQUENCY} DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursorToEntity(cursor))
            }
        }
        return list
    }

    fun updateEntityFrequency(entityId: Long, frequency: Int) {
        val values = ContentValues().apply {
            put(KnowledgeGraphDatabase.COL_ENTITY_FREQUENCY, frequency)
        }
        db.update(
            KnowledgeGraphDatabase.TABLE_ENTITIES,
            values,
            "${KnowledgeGraphDatabase.COL_ENTITY_ID}=?",
            arrayOf(entityId.toString()),
        )
    }

    fun deleteEntity(entityId: Long) {
        db.delete(
            KnowledgeGraphDatabase.TABLE_ENTITIES,
            "${KnowledgeGraphDatabase.COL_ENTITY_ID}=?",
            arrayOf(entityId.toString()),
        )
    }

    // ============================================================
    // 节点-实体关联
    // ============================================================

    fun upsertNodeEntityRelation(relation: GraphNodeEntityRelation) {
        val values = ContentValues().apply {
            put(KnowledgeGraphDatabase.COL_NE_NODE_ID, relation.nodeId)
            put(KnowledgeGraphDatabase.COL_NE_ENTITY_ID, relation.entityId)
            put(KnowledgeGraphDatabase.COL_NE_WEIGHT, relation.weight)
        }
        db.insertWithOnConflict(
            KnowledgeGraphDatabase.TABLE_NODE_ENTITIES,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun upsertNodeEntityRelations(relations: List<GraphNodeEntityRelation>, useTransaction: Boolean = true) {
        if (relations.isEmpty()) return
        if (!useTransaction) {
            for (relation in relations) upsertNodeEntityRelation(relation)
            return
        }
        db.beginTransaction()
        try {
            for (relation in relations) upsertNodeEntityRelation(relation)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getEntitiesForNode(nodeId: String): List<GraphEntity> {
        val list = mutableListOf<GraphEntity>()
        val sql = """
            SELECT e.* FROM ${KnowledgeGraphDatabase.TABLE_ENTITIES} e
            INNER JOIN ${KnowledgeGraphDatabase.TABLE_NODE_ENTITIES} ne
            ON e.${KnowledgeGraphDatabase.COL_ENTITY_ID} = ne.${KnowledgeGraphDatabase.COL_NE_ENTITY_ID}
            WHERE ne.${KnowledgeGraphDatabase.COL_NE_NODE_ID} = ?
            ORDER BY ne.${KnowledgeGraphDatabase.COL_NE_WEIGHT} DESC
        """.trimIndent()
        db.rawQuery(sql, arrayOf(nodeId)).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursorToEntity(cursor))
            }
        }
        return list
    }

    fun getNodesForEntity(entityId: Long): List<GraphNodeEntity> {
        val list = mutableListOf<GraphNodeEntity>()
        val sql = """
            SELECT n.* FROM ${KnowledgeGraphDatabase.TABLE_NODES} n
            INNER JOIN ${KnowledgeGraphDatabase.TABLE_NODE_ENTITIES} ne
            ON n.${KnowledgeGraphDatabase.COL_NODE_ID} = ne.${KnowledgeGraphDatabase.COL_NE_NODE_ID}
            WHERE ne.${KnowledgeGraphDatabase.COL_NE_ENTITY_ID} = ?
            ORDER BY ne.${KnowledgeGraphDatabase.COL_NE_WEIGHT} DESC
        """.trimIndent()
        db.rawQuery(sql, arrayOf(entityId.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursorToNode(cursor))
            }
        }
        return list
    }

    fun deleteNodeEntityRelations(nodeId: String): Boolean {
        val affected = db.delete(
            KnowledgeGraphDatabase.TABLE_NODE_ENTITIES,
            "${KnowledgeGraphDatabase.COL_NE_NODE_ID}=?",
            arrayOf(nodeId),
        )
        return affected > 0
    }

    // ============================================================
    // 清空
    // ============================================================

    fun clearAll(useTransaction: Boolean = true) {
        val doClear = {
            db.delete(KnowledgeGraphDatabase.TABLE_NODE_ENTITIES, null, null)
            db.delete(KnowledgeGraphDatabase.TABLE_EDGES, null, null)
            db.delete(KnowledgeGraphDatabase.TABLE_EMBEDDINGS, null, null)
            db.delete(KnowledgeGraphDatabase.TABLE_NODES, null, null)
            db.delete(KnowledgeGraphDatabase.TABLE_ENTITIES, null, null)
            db.delete(KnowledgeGraphDatabase.TABLE_MIGRATION_LOG, null, null)
        }
        if (!useTransaction) {
            doClear()
            return
        }
        db.beginTransaction()
        try {
            doClear()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ============================================================
    // 迁移记录
    // ============================================================

    fun recordMigration(version: Int, source: String = "") {
        val values = ContentValues().apply {
            put(KnowledgeGraphDatabase.COL_MIG_VERSION, version)
            put(KnowledgeGraphDatabase.COL_MIG_MIGRATED_AT, System.currentTimeMillis())
            put(KnowledgeGraphDatabase.COL_MIG_SOURCE, source)
        }
        db.insertOrThrow(KnowledgeGraphDatabase.TABLE_MIGRATION_LOG, null, values)
    }

    fun wasMigrated(version: Int, source: String = ""): Boolean {
        val selection = "${KnowledgeGraphDatabase.COL_MIG_VERSION}=? AND ${KnowledgeGraphDatabase.COL_MIG_SOURCE}=?"
        db.query(
            KnowledgeGraphDatabase.TABLE_MIGRATION_LOG,
            arrayOf(KnowledgeGraphDatabase.COL_MIG_ID),
            selection,
            arrayOf(version.toString(), source),
            null,
            null,
            null,
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    // ============================================================
    // 事务
    // ============================================================

    /**
     * 在数据库事务中执行块。调用方负责不嵌套调用其他事务方法。
     */
    fun runInTransaction(block: () -> Unit) {
        db.beginTransaction()
        try {
            block()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ============================================================
    // Cursor 映射
    // ============================================================

    private fun cursorToNode(cursor: Cursor): GraphNodeEntity {
        return GraphNodeEntity(
            id = cursor.getString(cursor.getColumnIndexOrThrow(KnowledgeGraphDatabase.COL_NODE_ID)) ?: "",
            title = cursor.getString(cursor.getColumnIndexOrThrow(KnowledgeGraphDatabase.COL_NODE_TITLE)) ?: "",
            summary = cursor.getString(cursor.getColumnIndexOrThrow(KnowledgeGraphDatabase.COL_NODE_SUMMARY)) ?: "",
            keywords = cursor.getString(cursor.getColumnIndexOrThrow(KnowledgeGraphDatabase.COL_NODE_KEYWORDS)) ?: "",
            updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(KnowledgeGraphDatabase.COL_NODE_UPDATED_AT)),
            contentHash = cursor.getString(cursor.getColumnIndexOrThrow(KnowledgeGraphDatabase.COL_NODE_CONTENT_HASH)) ?: "",
        )
    }

    private fun cursorToEdge(cursor: Cursor): GraphEdgeEntity {
        return GraphEdgeEntity(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(KnowledgeGraphDatabase.COL_EDGE_ID)),
            sourceId = cursor.getString(cursor.getColumnIndexOrThrow(KnowledgeGraphDatabase.COL_EDGE_SOURCE_ID)) ?: "",
            targetId = cursor.getString(cursor.getColumnIndexOrThrow(KnowledgeGraphDatabase.COL_EDGE_TARGET_ID)) ?: "",
            relationType = cursor.getString(cursor.getColumnIndexOrThrow(KnowledgeGraphDatabase.COL_EDGE_RELATION_TYPE)) ?: DEFAULT_RELATION_TYPE,
            weight = cursor.getFloat(cursor.getColumnIndexOrThrow(KnowledgeGraphDatabase.COL_EDGE_WEIGHT)),
            reason = cursor.getString(cursor.getColumnIndexOrThrow(KnowledgeGraphDatabase.COL_EDGE_REASON)) ?: "",
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(KnowledgeGraphDatabase.COL_EDGE_CREATED_AT)),
        )
    }

    private fun cursorToEmbedding(cursor: Cursor): GraphEmbeddingEntity {
        return GraphEmbeddingEntity(
            nodeId = cursor.getString(cursor.getColumnIndexOrThrow(KnowledgeGraphDatabase.COL_EMB_NODE_ID)) ?: "",
            provider = cursor.getString(cursor.getColumnIndexOrThrow(KnowledgeGraphDatabase.COL_EMB_PROVIDER)) ?: "",
            model = cursor.getString(cursor.getColumnIndexOrThrow(KnowledgeGraphDatabase.COL_EMB_MODEL)) ?: "",
            dimension = cursor.getInt(cursor.getColumnIndexOrThrow(KnowledgeGraphDatabase.COL_EMB_DIMENSION)),
            vector = cursor.getBlob(cursor.getColumnIndexOrThrow(KnowledgeGraphDatabase.COL_EMB_VECTOR)),
            updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(KnowledgeGraphDatabase.COL_EMB_UPDATED_AT)),
        )
    }

    private fun cursorToEntity(cursor: Cursor): GraphEntity {
        return GraphEntity(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(KnowledgeGraphDatabase.COL_ENTITY_ID)),
            name = cursor.getString(cursor.getColumnIndexOrThrow(KnowledgeGraphDatabase.COL_ENTITY_NAME)) ?: "",
            entityType = cursor.getString(cursor.getColumnIndexOrThrow(KnowledgeGraphDatabase.COL_ENTITY_TYPE)) ?: "",
            frequency = cursor.getInt(cursor.getColumnIndexOrThrow(KnowledgeGraphDatabase.COL_ENTITY_FREQUENCY)),
        )
    }
}
