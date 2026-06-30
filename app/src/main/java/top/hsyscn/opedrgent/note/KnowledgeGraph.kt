package top.hsyscn.opedrgent.note

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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

    private fun isLocalProvider(): Boolean = provider.providerName().startsWith("local")

    init {
        try {
            KnowledgeGraphMigrator.migrateIfNeeded(context, store)
        } catch (e: Exception) {
            DebugLog.e(TAG, "migration failed: ${e.message}", e)
        }
    }

    fun linkNote(noteId: String, content: String): List<String> {
        if (content.isBlank()) return emptyList()
        return try {
            doLinkNote(noteId, content)
        } catch (e: Exception) {
            DebugLog.e(TAG, "linkNote failed: ${e.message}", e)
            emptyList()
        }
    }

    private fun doLinkNote(noteId: String, content: String): List<String> {
        val embedding = try {
            runBlocking(Dispatchers.Default) { provider.embed(content) }
        } catch (e: Exception) {
            DebugLog.w(TAG, "embedding failed, falling back to local: ${e.message}")
            runBlocking(Dispatchers.Default) { LocalEmbeddingProvider(store).embed(content) }
        }
        val title = content.take(100)
        val summary = content.take(300)
        val keywords = LocalEntityExtractor.extractKeywords(title = title, content = content)
        val entities = LocalEntityExtractor.extractEntities(content)
        val keywordSet = keywords.toSet()
        val currentTime = System.currentTimeMillis()

        store.upsertNode(
            GraphNodeEntity(
                id = noteId,
                title = title,
                summary = summary,
                keywords = keywords.joinToString(","),
                updatedAt = currentTime,
                contentHash = content.hashCode().toString(),
            )
        )
        store.saveEmbedding(
            GraphEmbeddingEntity(
                nodeId = noteId,
                provider = provider.providerName(),
                model = "",
                dimension = provider.dimension(),
                vector = embedding.toByteArray(),
            )
        )

        val entityNameToId = mutableMapOf<String, Long>()
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

        val existingLinkedIds = store.getEdgesForNode(noteId)
            .map { if (it.sourceId == noteId) it.targetId else it.sourceId }
            .toSet()

        val newLinkedIds = mutableListOf<String>()
        val allNodes = store.getAllNodes()
        for (other in allNodes) {
            val otherId = other.id
            if (otherId == noteId) continue

            val otherEmbedding = store.getEmbedding(otherId)?.vector?.toFloatArray()
            val similarity = if (otherEmbedding != null) cosineSimilarity(embedding, otherEmbedding) else 0f

            val otherKeywords = other.keywords.split(',')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toSet()
            val sharedKeywords = keywordSet.intersect(otherKeywords)

            val otherEntityNames = store.getEntitiesForNode(otherId).map { it.name }.toSet()
            val sharedEntities = entityNameToId.keys.intersect(otherEntityNames)

            val relationType: String
            val weight: Float
            val reason: String
            val threshold = if (isLocalProvider()) LOCAL_SIMILARITY_THRESHOLD else SIMILARITY_THRESHOLD
            when {
                sharedEntities.isNotEmpty() -> {
                    relationType = REL_SHARED_ENTITY
                    weight = max(similarity, 0.9f)
                    reason = "共同实体：" + sharedEntities.take(3).joinToString("、")
                }

                sharedKeywords.size >= 2 -> {
                    relationType = REL_SHARED_KEYWORD
                    weight = max(similarity, 0.8f)
                    reason = "共同关键词：" + sharedKeywords.take(3).joinToString("、")
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

            store.upsertEdge(
                GraphEdgeEntity(
                    sourceId = noteId,
                    targetId = otherId,
                    relationType = relationType,
                    weight = weight,
                    reason = reason,
                    createdAt = currentTime,
                )
            )
            if (otherId !in existingLinkedIds) {
                newLinkedIds.add(otherId)
            }
        }

        trimLinks(noteId)
        return newLinkedIds
    }

    private fun trimLinks(noteId: String) {
        val edges = store.getEdgesForNode(noteId)
        if (edges.size <= MAX_LINKS_PER_NOTE) return
        val sorted = edges.sortedByDescending { it.weight }
        val keep = sorted.take(MAX_LINKS_PER_NOTE).map { it.id }.toSet()
        val toRemove = sorted.filter { it.id !in keep }
        for (edge in toRemove) {
            try {
                store.deleteEdge(edge.id)
            } catch (e: Exception) {
                DebugLog.e(TAG, "trimLinks deleteEdge failed: ${e.message}", e)
            }
        }
    }

    fun getLinkedNotes(noteId: String): List<String> = try {
        store.getEdgesForNode(noteId)
            .map { if (it.sourceId == noteId) it.targetId else it.sourceId }
            .distinct()
    } catch (e: Exception) {
        DebugLog.e(TAG, "getLinkedNotes failed: ${e.message}", e)
        emptyList()
    }

    fun getLinkCount(noteId: String): Int = try {
        store.getEdgesForNode(noteId).size
    } catch (e: Exception) {
        DebugLog.e(TAG, "getLinkCount failed: ${e.message}", e)
        0
    }

    fun getStats(): GraphStats = try {
        val nodes = store.getAllNodes()
        val links = getAllLinks()
        val totalNotes = nodes.size
        val totalLinks = links.size
        val isolatedNotes = nodes.count { store.getEdgesForNode(it.id).isEmpty() }
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

    fun getAllLinks(): List<GraphEdge> = try {
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

    fun searchByRelevance(query: String, maxResults: Int = 5): List<Pair<String, Float>> {
        if (query.isBlank()) return emptyList()
        return try {
            val queryVector = runBlocking(Dispatchers.Default) { provider.embed(query) }
            store.getAllNodes()
                .mapNotNull { node ->
                    val embedding = store.getEmbedding(node.id)?.vector?.toFloatArray()
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

    fun removeNote(noteId: String) {
        try {
            store.deleteNode(noteId)
            store.deleteEmbedding(noteId)
            store.deleteNodeEntityRelations(noteId)
            val edges = store.getEdgesForNode(noteId)
            for (edge in edges) {
                store.deleteEdge(edge.id)
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "removeNote failed: ${e.message}", e)
        }
    }

    fun clear() {
        try {
            store.clearAll()
        } catch (e: Exception) {
            DebugLog.e(TAG, "clear failed: ${e.message}", e)
        }
    }

    fun rebuildFromNotes(notes: List<Pair<String, String>>) {
        try {
            clear()
            if (notes.isEmpty()) return

            val currentTime = System.currentTimeMillis()
            val nodes = mutableListOf<GraphNodeEntity>()
            val contents = mutableListOf<String>()
            val noteIds = mutableListOf<String>()
            val entityRelations = mutableListOf<GraphNodeEntityRelation>()
            val noteKeywords = mutableMapOf<String, Set<String>>()
            val noteEntities = mutableMapOf<String, Set<String>>()

            for ((noteId, content) in notes) {
                if (content.isBlank()) continue
                val title = content.take(100)
                val summary = content.take(300)
                val keywords = LocalEntityExtractor.extractKeywords(title = title, content = content)
                val entities = LocalEntityExtractor.extractEntities(content)
                val keywordSet = keywords.toSet()
                noteKeywords[noteId] = keywordSet
                noteEntities[noteId] = entities.map { it.name }.toSet()

                nodes.add(
                    GraphNodeEntity(
                        id = noteId,
                        title = title,
                        summary = summary,
                        keywords = keywords.joinToString(","),
                        updatedAt = currentTime,
                        contentHash = content.hashCode().toString(),
                    )
                )
                contents.add(content)
                noteIds.add(noteId)

                for (entity in entities) {
                    val entityId = store.upsertEntity(
                        GraphEntity(
                            name = entity.name,
                            entityType = entity.type.name,
                            frequency = 1,
                        )
                    )
                    entityRelations.add(
                        GraphNodeEntityRelation(
                            nodeId = noteId,
                            entityId = entityId,
                            weight = 1f,
                        )
                    )
                }
            }

            if (nodes.isEmpty()) return

            val embeddings = try {
                val batch = runBlocking(Dispatchers.Default) { provider.embedBatch(contents) }
                batch.mapIndexed { index, vector ->
                    GraphEmbeddingEntity(
                        nodeId = noteIds[index],
                        provider = provider.providerName(),
                        model = "",
                        dimension = provider.dimension(),
                        vector = vector.toByteArray(),
                    )
                }
            } catch (e: Exception) {
                DebugLog.w(TAG, "batch embedding failed, falling back to local: ${e.message}")
                val batch = runBlocking(Dispatchers.Default) { LocalEmbeddingProvider(store).embedBatch(contents) }
                batch.mapIndexed { index, vector ->
                    GraphEmbeddingEntity(
                        nodeId = noteIds[index],
                        provider = provider.providerName(),
                        model = "",
                        dimension = provider.dimension(),
                        vector = vector.toByteArray(),
                    )
                }
            }

            store.upsertNodes(nodes)
            store.saveEmbeddings(embeddings)
            store.upsertNodeEntityRelations(entityRelations)

            val nodeIds = nodes.map { it.id }
            val edges = mutableListOf<GraphEdgeEntity>()
            for (i in nodeIds.indices) {
                val aId = nodeIds[i]
                val embeddingA = store.getEmbedding(aId)?.vector?.toFloatArray() ?: continue
                val keywordsA = noteKeywords[aId] ?: emptySet()
                val entitiesA = noteEntities[aId] ?: emptySet()
                for (j in i + 1 until nodeIds.size) {
                    val bId = nodeIds[j]
                    val embeddingB = store.getEmbedding(bId)?.vector?.toFloatArray() ?: continue
                    val similarity = cosineSimilarity(embeddingA, embeddingB)
                    val keywordsB = noteKeywords[bId] ?: emptySet()
                    val entitiesB = noteEntities[bId] ?: emptySet()
                    val sharedEntities = entitiesA.intersect(entitiesB)
                    val sharedKeywords = keywordsA.intersect(keywordsB)

                    val relationType: String
                    val weight: Float
                    val reason: String
                    val threshold = if (isLocalProvider()) LOCAL_SIMILARITY_THRESHOLD else SIMILARITY_THRESHOLD
                    when {
                        sharedEntities.isNotEmpty() -> {
                            relationType = REL_SHARED_ENTITY
                            weight = max(similarity, 0.9f)
                            reason = "共同实体：" + sharedEntities.take(3).joinToString("、")
                        }

                        sharedKeywords.size >= 2 -> {
                            relationType = REL_SHARED_KEYWORD
                            weight = max(similarity, 0.8f)
                            reason = "共同关键词：" + sharedKeywords.take(3).joinToString("、")
                        }

                        similarity >= threshold -> {
                            relationType = REL_SEMANTIC_SIMILAR
                            weight = similarity
                            reason = "语义相似度：${String.format("%.2f", similarity)}"
                        }

                        else -> continue
                    }

                    edges.add(
                        GraphEdgeEntity(
                            sourceId = aId,
                            targetId = bId,
                            relationType = relationType,
                            weight = weight,
                            reason = reason,
                            createdAt = currentTime,
                        )
                    )
                }
            }
            store.upsertEdges(edges)

            for (node in nodes) {
                trimLinks(node.id)
            }

            DebugLog.i(TAG, "rebuild done: ${nodes.size} nodes, ${edges.size} edges")
        } catch (e: Exception) {
            DebugLog.e(TAG, "rebuildFromNotes failed: ${e.message}", e)
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
        store.getAllEdges()
            .find {
                (it.sourceId == sourceId && it.targetId == targetId) ||
                    (it.sourceId == targetId && it.targetId == sourceId)
            }
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
