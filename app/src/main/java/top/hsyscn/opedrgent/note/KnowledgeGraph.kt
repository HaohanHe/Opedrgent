package top.hsyscn.opedrgent.note

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import top.hsyscn.opedrgent.note.graph.GraphEdgeEntity
import top.hsyscn.opedrgent.note.graph.GraphEmbeddingEntity
import top.hsyscn.opedrgent.note.graph.GraphEntity
import top.hsyscn.opedrgent.note.graph.GraphNodeEntity
import top.hsyscn.opedrgent.note.graph.GraphNodeEntityRelation
import top.hsyscn.opedrgent.utils.DebugLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

class KnowledgeGraph(
    private val context: Context,
    private val store: KnowledgeGraphStore,
    private val provider: EmbeddingProvider,
) {

    companion object {
        private const val TAG = "KnowledgeGraph"

        // 防止多个协程并发修改图谱（link/rebuild/remove 串行执行）。
        // 提升为 companion 级单例：Worker 每次 doWork 都会新建 KnowledgeGraph 实例，
        // 实例字段锁会导致各实例持不同锁，跨实例互斥失效。
        private val graphMutex = Mutex()

        // 迁移只在进程内实际执行一次；后续构造的实例跳过 wasMigrated 查询。
        @Volatile
        private var migrationChecked = false

        const val REL_SEMANTIC_SIMILAR = "SEMANTIC_SIMILAR"
        const val REL_SHARED_KEYWORD = "SHARED_KEYWORD"
        const val REL_SHARED_ENTITY = "SHARED_ENTITY"
        const val REL_TEMPORAL_CLOSE = "TEMPORAL_CLOSE"
        const val REL_CITES = "CITES"

        const val SIMILARITY_THRESHOLD = 0.15f
        const val LOCAL_SIMILARITY_THRESHOLD = 0.08f
        const val MAX_LINKS_PER_NOTE = 10

        private const val ONE_WEEK_MS = 7L * 24 * 60 * 60 * 1000
    }

    /**
     * 对敏感实体名称进行掩码。
     *
     * - PERSON / LOCATION / ORGANIZATION：保留首字，其余替换为 *
     * - TIME / CONCEPT 等其他类型：不掩码
     */
    private fun maskSensitiveEntity(name: String, type: LocalEntityExtractor.EntityType): String {
        return when (type) {
            LocalEntityExtractor.EntityType.PERSON,
            LocalEntityExtractor.EntityType.LOCATION,
            LocalEntityExtractor.EntityType.ORGANIZATION -> {
                if (name.length <= 1) name else "${name.first()}${"*".repeat(name.length - 1)}"
            }
            else -> name
        }
    }

    init {
        if (!migrationChecked) {
            try {
                if (KnowledgeGraphMigrator.migrateIfNeeded(context, store)) {
                    migrationChecked = true
                }
            } catch (e: Exception) {
                DebugLog.e(TAG, "migration failed: ${e.message}", e)
            }
        }
    }

    suspend fun linkNote(noteId: String, content: String): List<String> {
        if (content.isBlank()) return emptyList()
        return graphMutex.withLock {
            try {
                doLinkNote(noteId, content)
            } catch (e: Exception) {
                DebugLog.e(TAG, "linkNote failed: ${e.message}", e)
                emptyList()
            }
        }
    }

    private suspend fun doLinkNote(noteId: String, content: String): List<String> {
        val (embedding, effectiveProvider) = embedWithFallback(content)
        // 阈值必须与实际生成向量的 provider 一致：远端失败回退本地后不得再用远端阈值。
        val threshold = if (effectiveProvider.providerName().startsWith("local")) {
            LOCAL_SIMILARITY_THRESHOLD
        } else {
            SIMILARITY_THRESHOLD
        }
        val title = content.take(100)
        val summary = content.take(300)
        val keywords = LocalEntityExtractor.extractKeywords(title = title, content = content)
        val entities = LocalEntityExtractor.extractEntities(content)
        val keywordSet = keywords.toSet()
        val currentTime = System.currentTimeMillis()

        val entityNameToId = mutableMapOf<String, Long>()
        // 节点、embedding、实体关系在同一事务中写入，保证基础数据一致
        store.runInTransaction {
            store.upsertNode(
                GraphNodeEntity(
                    id = noteId,
                    title = title,
                    summary = summary,
                    keywords = keywords.joinToString(","),
                    updatedAt = currentTime,
                )
            )
            store.saveEmbedding(
                GraphEmbeddingEntity(
                    nodeId = noteId,
                    provider = effectiveProvider.providerName(),
                    model = modelNameFor(effectiveProvider),
                    dimension = embedding.size,
                    vector = embedding.toByteArray(),
                )
            )
            for (entity in entities) {
                val entityId = store.upsertEntity(
                    GraphEntity(
                        name = entity.name,
                        entityType = entity.type.name,
                        frequency = 1,
                    )
                )
                entityNameToId[entity.name] = entityId
                store.upsertNodeEntityRelation(
                    GraphNodeEntityRelation(
                        nodeId = noteId,
                        entityId = entityId,
                        weight = 1f,
                    )
                )
            }
        }

        val existingLinkedIds = store.getEdgesForNode(noteId)
            .map { if (it.sourceId == noteId) it.targetId else it.sourceId }
            .toSet()

        // 批量预加载：embedding 与 节点-实体 映射各一条 SQL，消除逐节点 N+1 查询。
        // 历史向量仅在 provider+维度与当前提供器一致时才参与比对（跨向量空间视为缺失）。
        val embeddingByNode = store.getAllEmbeddings()
            .filter { isEmbeddingCompatible(it) }
            .associateBy { it.nodeId }
        val entitiesByNode = store.getEntitiesByNode()
            .mapValues { (_, list) -> list.map { it.name }.toSet() }
        val entityTypeByName = store.getAllEntities().associate { it.name to it.entityType }

        val newLinkedIds = mutableListOf<String>()
        val edgesToUpsert = mutableListOf<GraphEdgeEntity>()
        val allNodes = store.getAllNodes()
        for (other in allNodes) {
            val otherId = other.id
            if (otherId == noteId) continue

            val otherEmbedding = embeddingByNode[otherId]?.vector?.toFloatArray()
            val similarity = if (otherEmbedding != null) cosineSimilarity(embedding, otherEmbedding) else 0f

            val otherKeywords = other.keywords.split(',')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toSet()
            val sharedKeywords = keywordSet.intersect(otherKeywords)

            val otherEntityNames = entitiesByNode[otherId] ?: emptySet()
            val sharedEntities = entityNameToId.keys.intersect(otherEntityNames)

            val relationType: String
            val weight: Float
            val reason: String
            when {
                sharedEntities.isNotEmpty() -> {
                    relationType = REL_SHARED_ENTITY
                    weight = max(similarity, 0.9f)
                    val entityTypes = entities.associate { it.name to it.type }
                    reason = "共同实体：" + sharedEntities.take(3).map { name ->
                        val type = entityTypes[name]
                            ?: entityTypeByName[name]?.let {
                                runCatching { LocalEntityExtractor.EntityType.valueOf(it) }.getOrNull()
                            }
                            ?: LocalEntityExtractor.EntityType.CONCEPT
                        maskSensitiveEntity(name, type)
                    }.joinToString("、")
                }

                sharedKeywords.size >= 2 -> {
                    relationType = REL_SHARED_KEYWORD
                    weight = max(similarity, 0.8f)
                    val entityTypes = entities.associate { it.name to it.type }
                    reason = "共同关键词：" + sharedKeywords.take(3).map { name ->
                        val type = entityTypes[name]
                        if (type != null) maskSensitiveEntity(name, type) else name
                    }.joinToString("、")
                }

                similarity >= threshold -> {
                    relationType = REL_SEMANTIC_SIMILAR
                    weight = similarity
                    reason = "语义相似度：${String.format("%.2f", similarity)}"
                }

                abs(other.updatedAt - currentTime) < ONE_WEEK_MS -> {
                    relationType = REL_TEMPORAL_CLOSE
                    weight = max(similarity, 0.3f)
                    reason = "时间接近"
                }

                else -> continue
            }

            edgesToUpsert.add(canonicalEdge(noteId, otherId, relationType, weight, reason, currentTime))
            if (otherId !in existingLinkedIds) {
                newLinkedIds.add(otherId)
            }
        }

        // 边写入与裁剪在同一事务内完成，避免中间状态被其他读操作看到
        store.runInTransaction {
            for (edge in edgesToUpsert) {
                store.upsertEdge(edge)
            }
            trimLinks(noteId)
        }
        return newLinkedIds
    }

    private fun trimLinks(noteId: String) {
        val edges = store.getEdgesForNode(noteId)
        if (edges.size <= MAX_LINKS_PER_NOTE) return
        val sorted = edges.sortedByDescending { it.weight }
        val keep = sorted.take(MAX_LINKS_PER_NOTE).map { it.id }.toSet()
        val toRemove = sorted.filter { it.id !in keep }
        // 删除失败直接抛出，使外层写事务回滚；不在事务内静默吞异常后照常提交。
        for (edge in toRemove) {
            store.deleteEdge(edge.id)
        }
    }

    fun getLinkedNotes(noteId: String): List<String> {
        return try {
            store.getEdgesForNode(noteId)
                .map { if (it.sourceId == noteId) it.targetId else it.sourceId }
                .distinct()
        } catch (e: Exception) {
            DebugLog.e(TAG, "getLinkedNotes failed: ${e.message}", e)
            emptyList()
        }
    }

    fun getLinkCount(noteId: String): Int {
        return try {
            store.getEdgesForNode(noteId).size
        } catch (e: Exception) {
            DebugLog.e(TAG, "getLinkCount failed: ${e.message}", e)
            0
        }
    }

    fun getStats(): GraphStats {
        return try {
            val nodes = store.getAllNodes()
            val links = store.getAllEdges()
                .map {
                    val a = it.sourceId
                    val b = it.targetId
                    if (a < b) GraphEdge(a, b) else GraphEdge(b, a)
                }
                .distinct()
            val coveredNodeIds = links.flatMap { listOf(it.sourceId, it.targetId) }.toSet()
            val totalNotes = nodes.size
            val totalLinks = links.size
            val isolatedNotes = nodes.count { it.id !in coveredNodeIds }
            GraphStats(
                totalNotes = totalNotes,
                totalLinks = totalLinks,
                isolatedNotes = isolatedNotes,
                avgLinksPerNote = if (totalNotes > 0) totalLinks.toFloat() / totalNotes else 0f,
            )
        } catch (e: Exception) {
            DebugLog.e(TAG, "getStats failed: ${e.message}", e)
            GraphStats(0, 0, 0, 0f)
        }
    }

    fun getAllLinks(): List<GraphEdge> {
        return try {
            store.getAllEdges()
                .map {
                    val a = it.sourceId
                    val b = it.targetId
                    if (a < b) GraphEdge(a, b) else GraphEdge(b, a)
                }
                .distinct()
        } catch (e: Exception) {
            DebugLog.e(TAG, "getAllLinks failed: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun searchByRelevance(query: String, maxResults: Int = 5): List<Pair<String, Float>> {
        if (query.isBlank()) return emptyList()
        return graphMutex.withLock {
            try {
                val queryVector = withTimeoutOrNull(30_000L) {
                    provider.embed(query)
                } ?: return@withLock emptyList()
                val embeddingByNode = store.getAllEmbeddings()
                    .filter { isEmbeddingCompatible(it) }
                    .associateBy { it.nodeId }
                store.getAllNodes()
                    .mapNotNull { node ->
                        val embedding = embeddingByNode[node.id]?.vector?.toFloatArray()
                            ?: return@mapNotNull null
                        val similarity = cosineSimilarity(queryVector, embedding)
                        if (similarity > 0.05f) node.id to similarity else null
                    }
                    .sortedByDescending { it.second }
                    .take(maxResults)
            } catch (e: Exception) {
                DebugLog.e(TAG, "searchByRelevance failed: ${e.message}", e)
                emptyList()
            }
        }
    }

    suspend fun removeNote(noteId: String) {
        graphMutex.withLock {
            try {
                store.runInTransaction {
                    val entities = store.getEntitiesForNode(noteId)
                    for (entity in entities) {
                        val updatedFrequency = entity.frequency - 1
                        if (updatedFrequency <= 0) {
                            store.deleteEntity(entity.id)
                        } else {
                            store.updateEntityFrequency(entity.id, updatedFrequency)
                        }
                    }
                    store.deleteNode(noteId)
                    store.deleteEmbedding(noteId)
                    store.deleteNodeEntityRelations(noteId)
                    val edges = store.getEdgesForNode(noteId)
                    for (edge in edges) {
                        store.deleteEdge(edge.id)
                    }
                }
            } catch (e: Exception) {
                DebugLog.e(TAG, "removeNote failed: ${e.message}", e)
            }
        }
    }

    suspend fun clear() {
        graphMutex.withLock {
            try {
                store.clearAll()
            } catch (e: Exception) {
                DebugLog.e(TAG, "clear failed: ${e.message}", e)
            }
        }
    }

    suspend fun rebuildFromNotes(notes: List<Pair<String, String>>) {
        graphMutex.withLock {
            try {
                if (notes.isEmpty()) {
                    store.clearAll()
                    return@withLock
                }

                val currentTime = System.currentTimeMillis()
                val nodes = mutableListOf<GraphNodeEntity>()
                val contents = mutableListOf<String>()
                val noteIds = mutableListOf<String>()
                val allEntities = mutableListOf<GraphEntity>()
                val noteKeywords = mutableMapOf<String, Set<String>>()
                val noteEntities = mutableMapOf<String, Set<String>>()
                val noteEntityTypes = mutableMapOf<String, Map<String, LocalEntityExtractor.EntityType>>()
                val noteRawEntities = mutableMapOf<String, List<LocalEntityExtractor.Entity>>()

                for ((noteId, content) in notes) {
                    if (content.isBlank()) continue
                    val title = content.take(100)
                    val summary = content.take(300)
                    val keywords = LocalEntityExtractor.extractKeywords(title = title, content = content)
                    val entities = LocalEntityExtractor.extractEntities(content)
                    val keywordSet = keywords.toSet()
                    noteKeywords[noteId] = keywordSet
                    noteEntities[noteId] = entities.map { it.name }.toSet()
                    noteEntityTypes[noteId] = entities.associate { it.name to it.type }
                    noteRawEntities[noteId] = entities

                    nodes.add(
                        GraphNodeEntity(
                            id = noteId,
                            title = title,
                            summary = summary,
                            keywords = keywords.joinToString(","),
                            updatedAt = currentTime,
                        )
                    )
                    contents.add(content)
                    noteIds.add(noteId)

                    for (entity in entities) {
                        allEntities.add(
                            GraphEntity(
                                name = entity.name,
                                entityType = entity.type.name,
                                frequency = 1,
                            )
                        )
                    }
                }

                if (nodes.isEmpty()) {
                    store.clearAll()
                    return@withLock
                }

                val (batchVectors, effectiveProvider) = try {
                    provider.embedBatch(contents) to provider
                } catch (e: Exception) {
                    DebugLog.w(TAG, "batch embedding failed, falling back to local: ${e.message}")
                    val local = LocalEmbeddingProvider(store)
                    local.embedBatch(contents) to local
                }
                val effectiveThreshold = if (effectiveProvider.providerName().startsWith("local")) {
                    LOCAL_SIMILARITY_THRESHOLD
                } else {
                    SIMILARITY_THRESHOLD
                }
                val embeddings = batchVectors.mapIndexed { index, vector ->
                    GraphEmbeddingEntity(
                        nodeId = noteIds[index],
                        provider = effectiveProvider.providerName(),
                        model = modelNameFor(effectiveProvider),
                        dimension = vector.size,
                        vector = vector.toByteArray(),
                    )
                }

                val nodeIds = nodes.map { it.id }
                val edges = mutableListOf<GraphEdgeEntity>()
                for (i in nodeIds.indices) {
                    val aId = nodeIds[i]
                    val embeddingA = embeddings.find { it.nodeId == aId }?.vector?.toFloatArray() ?: continue
                    val keywordsA = noteKeywords[aId] ?: emptySet()
                    val entitiesA = noteEntities[aId] ?: emptySet()
                    for (j in i + 1 until nodeIds.size) {
                        val bId = nodeIds[j]
                        val embeddingB = embeddings.find { it.nodeId == bId }?.vector?.toFloatArray() ?: continue
                        val similarity = cosineSimilarity(embeddingA, embeddingB)
                        val keywordsB = noteKeywords[bId] ?: emptySet()
                        val entitiesB = noteEntities[bId] ?: emptySet()
                        val sharedEntities = entitiesA.intersect(entitiesB)
                        val sharedKeywords = keywordsA.intersect(keywordsB)

                        val relationType: String
                        val weight: Float
                        val reason: String
                        val threshold = effectiveThreshold
                        when {
                            sharedEntities.isNotEmpty() -> {
                                relationType = REL_SHARED_ENTITY
                                weight = max(similarity, 0.9f)
                                reason = "共同实体：" + sharedEntities.take(3).map { name ->
                                    val type = noteEntityTypes[aId]?.get(name)
                                        ?: noteEntityTypes[bId]?.get(name)
                                        ?: LocalEntityExtractor.EntityType.CONCEPT
                                    maskSensitiveEntity(name, type)
                                }.joinToString("、")
                            }

                            sharedKeywords.size >= 2 -> {
                                relationType = REL_SHARED_KEYWORD
                                weight = max(similarity, 0.8f)
                                reason = "共同关键词：" + sharedKeywords.take(3).map { name ->
                                    val type = noteEntityTypes[aId]?.get(name)
                                        ?: noteEntityTypes[bId]?.get(name)
                                    if (type != null) maskSensitiveEntity(name, type) else name
                                }.joinToString("、")
                            }

                            similarity >= threshold -> {
                                relationType = REL_SEMANTIC_SIMILAR
                                weight = similarity
                                reason = "语义相似度：${String.format("%.2f", similarity)}"
                            }

                            else -> continue
                        }

                        edges.add(canonicalEdge(aId, bId, relationType, weight, reason, currentTime))
                    }
                }

                // 所有数据准备完毕后，在一个事务内清空旧数据并写入新数据，保证重建原子性
                store.runInTransaction {
                    store.clearAll(useTransaction = false)
                    store.upsertEntities(allEntities, useTransaction = false)
                    // 父表(nodes/entities)先于子表(node_entities)写入，满足外键约束：
                    // 关联行引用的 node_id / entity_id 必须先存在，否则启用 foreign_keys 后抛约束异常。
                    store.upsertNodes(nodes, useTransaction = false)
                    // 通过已持久化的实体名反查 ID，避免重复 upsert 导致 frequency 被多次累加
                    for ((noteId, entities) in noteRawEntities) {
                        for (entity in entities) {
                            val entityId = store.getEntityByName(entity.name)?.id ?: continue
                            store.upsertNodeEntityRelation(
                                GraphNodeEntityRelation(
                                    nodeId = noteId,
                                    entityId = entityId,
                                    weight = 1f,
                                )
                            )
                        }
                    }
                    store.saveEmbeddings(embeddings, useTransaction = false)
                    store.upsertEdges(edges, useTransaction = false)
                    for (node in nodes) {
                        trimLinks(node.id)
                    }
                }

                DebugLog.i(TAG, "rebuild done: ${nodes.size} nodes, ${edges.size} edges")
            } catch (e: Exception) {
                DebugLog.e(TAG, "rebuildFromNotes failed: ${e.message}", e)
                throw e
            }
        }
    }

    fun needsRebuild(noteCount: Long): Boolean = try {
        val nodes = store.getAllNodes()
        val edges = store.getAllEdges()
        (noteCount > 0 && nodes.isEmpty()) || (nodes.isEmpty() && edges.isNotEmpty())
    } catch (e: Exception) {
        DebugLog.e(TAG, "needsRebuild failed: ${e.message}", e)
        false
    }

    private suspend fun embedWithFallback(content: String): Pair<FloatArray, EmbeddingProvider> {
        return try {
            provider.embed(content) to provider
        } catch (e: Exception) {
            DebugLog.w(TAG, "embedding failed, falling back to local: ${e.message}")
            val local = LocalEmbeddingProvider(store)
            local.embed(content) to local
        }
    }

    /** 历史向量仅在 provider 与维度都与当前提供器一致时才参与比对，否则视为缺失。 */
    private fun isEmbeddingCompatible(embedding: GraphEmbeddingEntity): Boolean {
        return embedding.provider == provider.providerName() &&
            embedding.dimension == provider.dimension() &&
            embedding.vector != null
    }

    /** 落库写入真实 model 标识；云端模型名不暴露在 EmbeddingProvider 接口上，留空待 provider 侧补充。 */
    private fun modelNameFor(p: EmbeddingProvider): String {
        return if (p.providerName().startsWith("local")) "tfidf-512" else ""
    }

    /** 边按无向规范化存储：落库前保证 sourceId <= targetId，唯一约束作用于规范化有序对。 */
    private fun canonicalEdge(
        sourceId: String,
        targetId: String,
        relationType: String,
        weight: Float,
        reason: String,
        createdAt: Long,
    ): GraphEdgeEntity {
        // GraphEdgeEntity 首参为 id: Long，位置参数会把 String 误射到 id；统一用具名参数。
        return if (sourceId <= targetId) {
            GraphEdgeEntity(
                sourceId = sourceId,
                targetId = targetId,
                relationType = relationType,
                weight = weight,
                reason = reason,
                createdAt = createdAt,
            )
        } else {
            GraphEdgeEntity(
                sourceId = targetId,
                targetId = sourceId,
                relationType = relationType,
                weight = weight,
                reason = reason,
                createdAt = createdAt,
            )
        }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            val av = a[i].toDouble()
            val bv = b[i].toDouble()
            dot += av * bv
            normA += av * av
            normB += bv * bv
        }
        if (normA <= 0.0 || normB <= 0.0) return 0f
        return (dot / (sqrt(normA) * sqrt(normB))).toFloat().coerceIn(0f, 1f)
    }

    private fun FloatArray.toByteArray(): ByteArray {
        val buffer = ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (value in this) {
            buffer.putFloat(value)
        }
        return buffer.array()
    }

    private fun ByteArray.toFloatArray(): FloatArray {
        val buffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(size / 4) { buffer.getFloat() }
    }

    data class GraphStats(
        val totalNotes: Int,
        val totalLinks: Int,
        val isolatedNotes: Int,
        val avgLinksPerNote: Float,
    )

    data class GraphEdge(
        val sourceId: String,
        val targetId: String,
    )

    /**
     * 带关系类型、原因和权重的完整边信息，用于可视化增强。
     */
    data class GraphEdgeDetail(
        val sourceId: String,
        val targetId: String,
        val relationType: String,
        val reason: String,
        val weight: Float,
    )

    /** 获取所有边的完整详情（含关系类型与原因）。 */
    fun getAllEdgeDetails(): List<GraphEdgeDetail> = try {
        store.getAllEdges().map {
            GraphEdgeDetail(
                sourceId = it.sourceId,
                targetId = it.targetId,
                relationType = it.relationType,
                reason = it.reason,
                weight = it.weight,
            )
        }
    } catch (e: Exception) {
        DebugLog.e(TAG, "getAllEdgeDetails failed: ${e.message}", e)
        emptyList()
    }

    /** 获取指定节点对之间的边详情（无向查找）。 */
    fun getEdgeDetails(sourceId: String, targetId: String): GraphEdgeDetail? = try {
        store.findEdgeByPair(sourceId, targetId)
            ?.let {
                GraphEdgeDetail(
                    sourceId = it.sourceId,
                    targetId = it.targetId,
                    relationType = it.relationType,
                    reason = it.reason,
                    weight = it.weight,
                )
            }
    } catch (e: Exception) {
        DebugLog.e(TAG, "getEdgeDetails failed: ${e.message}", e)
        null
    }
}
