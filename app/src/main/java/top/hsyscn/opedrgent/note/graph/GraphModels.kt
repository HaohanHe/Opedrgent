package top.hsyscn.opedrgent.note.graph

/**
 * 知识图谱节点。
 */
data class GraphNodeEntity(
    val id: String,
    val title: String = "",
    val summary: String = "",
    val keywords: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    val contentHash: String = "",
)

/**
 * 知识图谱边。
 */
data class GraphEdgeEntity(
    val id: Long = 0,
    val sourceId: String,
    val targetId: String,
    val relationType: String,
    val weight: Float = 0f,
    val reason: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * 节点 embedding。
 *
 * @param vector 原始字节，具体序列化格式由 provider 决定（如 legacy-tfidf 使用 JSON 字节）。
 */
data class GraphEmbeddingEntity(
    val nodeId: String,
    val provider: String = "",
    val model: String = "",
    val dimension: Int = 0,
    val vector: ByteArray? = null,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GraphEmbeddingEntity) return false
        return nodeId == other.nodeId &&
                provider == other.provider &&
                model == other.model &&
                dimension == other.dimension &&
                vector.contentEquals(other.vector) &&
                updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = nodeId.hashCode()
        result = 31 * result + provider.hashCode()
        result = 31 * result + model.hashCode()
        result = 31 * result + dimension
        result = 31 * result + (vector?.contentHashCode() ?: 0)
        result = 31 * result + updatedAt.hashCode()
        return result
    }
}

/**
 * 命名实体。
 */
data class GraphEntity(
    val id: Long = 0,
    val name: String,
    val entityType: String = "",
    val frequency: Int = 0,
)

/**
 * 节点与实体的关联。
 */
data class GraphNodeEntityRelation(
    val nodeId: String,
    val entityId: Long,
    val weight: Float = 0f,
)
