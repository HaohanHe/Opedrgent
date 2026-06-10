package top.hsyscn.opedrgent.intelligence

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.hsyscn.opedrgent.utils.DebugLog
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 向量记忆系统（Vector Memory）— 对标 Qdrant RAG。
 *
 * ## 设计理念（来自 Qdrant）
 *
 * Qdrant 是一个向量数据库，核心能力：
 * - **向量存储**：高维向量的持久化存储
 * - **相似度搜索**：基于余弦距离的最近邻查询
 * - **Payload 过滤**：在向量搜索基础上做结构化过滤
 * - **分片(Shard)**：数据按集合分区
 *
 * 本实现是 Android 嵌入式版本：
 * - 使用纯内存存储（可后续扩展为 SQLite 持久化）
 * - TF-IDF 简化的词向量（无需外部模型依赖）
 * - 余弦相似度作为距离度量
 * - Payload 过滤器支持精确匹配和范围查询
 *
 * ## 使用场景
 * - 笔记/文档的语义搜索
 * - 对话历史的相似度检索
 * - 知识库条目的智能匹配
 * - 编辑团角色的上下文查找
 */

/** 向量维度（TF-IDF 特征空间大小，使用哈希技巧固定维度） */
private const val VECTOR_DIM = 256

/**
 * 记忆向量条目。
 *
 * @param id 唯一标识
 * @param vector 向量（归一化后的浮点数组）
 * @param payload 结构化元数据（对标 Qdrant Payload）
 * @param collection 所属分片/集合名
 * @param createdAt 创建时间戳
 */
data class MemoryVector(
    val id: String,
    val vector: FloatArray,
    val payload: MemoryPayload = MemoryPayload(),
    val collection: String = "default",
    val createdAt: Long = System.currentTimeMillis(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MemoryVector) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

/**
 * 结构化载荷（对标 Qdrant Payload）。
 *
 * 支持多种类型的键值对，用于过滤和展示。
 */
data class MemoryPayload(
    val title: String = "",
    val content: String = "",       // 原始文本内容
    val tags: Set<String> = emptySet(),
    val source: String = "",         // 来源（note/kb/conversation/skill）
    val importance: Float = 0.5f,   // 重要性权重 (0-1)
    val metadata: Map<String, Any> = emptyMap(),
    val embeddingModel: String = "tfidf-hash", // 嵌入方法标记
)

/**
 * 向量搜索结果（带分数）。
 */
data class SearchResult(
    val vector: MemoryVector,
    val score: Float,               // 相似度分数 (0-1)，越高越相似
    val rank: Int,                  // 排名
)

/**
 * 搜索过滤器（对标 Qdrant Filter）。
 *
 * 支持组合条件：
 * ```kotlin
 * Filter.and(
 *     Filter.eq("source", "note"),
 *     Filter.inSet("tags", setOf("important", "starred")),
 *     Filter.range("importance", 0.5f, 1.0f),
 * )
 * ```
 */
sealed class Filter {
    /** 精确匹配 */
    data class Eq(val key: String, val value: Any) : Filter()
    /** 值在集合中 */
    data class InSet(val key: String, val values: Set<Any>) : Filter()
    /** 范围查询 */
    data class Range(val key: String, val min: Float, val max: Float) : Filter()
    /** 文本包含 */
    data class Contains(val key: String, val substring: String) : Filter()
    /** 且条件（所有子条件必须满足） */
    data class And(val filters: List<Filter>) : Filter()
    /** 或条件（任一子条件满足即可） */
    data class Or(val filters: List<Filter>) : Filter()
    /** 非条件 */
    data class Not(val filter: Filter) : Filter()
    /** 全部通过 */
    object All : Filter()

    companion object {
        /** 构造 And 条件的便捷方法 */
        fun and(vararg filters: Filter): Filter = And(filters.toList())
        /** 构造 Or 条件的便捷方法 */
        fun or(vararg filters: Filter): Filter = Or(filters.toList())
    }

    /** 测试一个 payload 是否满足此过滤器 */
    fun matches(payload: MemoryPayload): Boolean = when (this) {
        is All -> true
        is Eq -> when (key) {
            "source" -> payload.source == value.toString()
            "title" -> payload.title == value.toString()
            "importance" -> payload.importance == (value as? Number)?.toFloat()
            else -> payload.metadata[key] == value
        }
        is InSet -> when (key) {
            "tags" -> payload.tags.any { it in values.map { it.toString() } }
            "source" -> payload.source in values.map { it.toString() }
            else -> payload.metadata[key] in values
        }
        is Range -> when (key) {
            "importance" -> payload.importance in min..max
            else -> (payload.metadata[key] as? Number)?.toFloat()?.let { it in min..max } ?: false
        }
        is Contains -> when (key) {
            "title" -> payload.title.contains(substring)
            "content" -> payload.content.contains(substring)
            else -> payload.metadata[key].toString().contains(substring)
        }
        is And -> filters.all { it.matches(payload) }
        is Or -> filters.any { it.matches(payload) }
        is Not -> !filter.matches(payload)
    }
}

/**
 * 向量记忆引擎 — 核心类。
 *
 * 提供：
 * - 向量化（文本 → 固定维向量）
 * - 存储（内存 + 可选持久化）
 * - 搜索（相似度 + 过滤）
 * - 管理（集合 CRUD）
 *
 * ## 持久化支持
 * 通过 [persistence] 参数可注入不同的持久化后端：
 * - 不传或传 null：使用内存存储（原有行为，App 重启后数据丢失）
 * - 传入 [SqlitePersistence]：SQLite 持久化（生产环境推荐）
 * - 传入 [InMemoryPersistence]：显式内存模式（用于测试）
 */
class VectorMemory(
    private val defaultDim: Int = VECTOR_DIM,
    private val persistence: PersistenceLayer? = null,  // 可选的持久化层
) {

    private val store = mutableListOf<MemoryVector>()
    private val mutex = Mutex()

    // 集合（分片）管理
    private val collections = mutableMapOf<String, MutableSet<String>>()

    /** 标记是否已启用持久化 */
    private val isPersistent: Boolean get() = persistence != null

    init {
        // 初始化持久化层并恢复数据
        if (isPersistent) {
            persistence!!.initialize()
            restoreFromPersistence()
        }
    }

    /**
     * 从持久化层恢复数据到内存。
     *
     * 在 VectorMemory 构造时调用，将数据库中已有的向量加载到内存中，
     * 确保重启后数据不丢失。
     */
    private fun restoreFromPersistence() {
        if (!isPersistent) return

        try {
            val vectors = persistence!!.getAll()
            if (vectors.isNotEmpty()) {
                store.clear()
                collections.clear()
                store.addAll(vectors)
                for (mv in vectors) {
                    collections.getOrPut(mv.collection) { mutableSetOf() }.add(mv.id)
                }
                DebugLog.i("VectorMemory", "从持久化层恢复了 ${vectors.size} 条向量记忆")
            }
        } catch (e: Exception) {
            DebugLog.e("VectorMemory", "从持久化层恢复数据失败: ${e.message}", e)
        }
    }

    // ==================== 向量化 ====================

    /**
     * 将文本转换为向量（简化的 TF-IDF Hashing）。
     *
     * 使用特征哈希（Hashing Trick）将文本映射到固定维度空间，
     * 无需构建词汇表，适合嵌入式场景。
     *
     * 算法：
     * 1. 分词（按空格、标点分割）
     * 2. 每个 token 哈希到 [0, dim) 的索引
     * 3. 在对应位置累加 TF 权重
     * 4. L2 归一化
     */
    fun vectorize(text: String, dim: Int = defaultDim): FloatArray {
        val vec = FloatArray(dim) { 0f }
        if (text.isBlank()) return normalize(vec)

        // 简单分词：中文按字符，英文按单词
        val tokens = tokenize(text)
        val tf = mutableMapOf<String, Int>()
        for (token in tokens) {
            tf[token] = (tf[token] ?: 0) + 1
        }

        // 特征哈希
        for ((token, count) in tf) {
            val idx = hashToken(token, dim)
            // 使用符号哈希减少碰撞偏差
            val sign = if (hashSign(token)) 1f else -1f
            vec[idx] += sign * (count.toFloat() / tokens.size.toFloat())
        }

        // IDF 简化：短词降权
        for (i in vec.indices) {
            // 轻微的 IDF 调整
            vec[i] *= 1.0f + 0.01f * (1.0f - vec[i].coerceAtLeast(0f))
        }

        return normalize(vec)
    }

    /**
     * 批量向量化。
     */
    fun vectorizeBatch(texts: List<String>): List<FloatArray> =
        texts.map { vectorize(it) }

    // ==================== 存储 API ====================

    /**
     * 存入一条记忆向量。
     *
     * @return 生成的 ID
     */
    suspend fun put(
        text: String,
        payload: MemoryPayload = MemoryPayload(content = text),
        collection: String = "default",
        id: String = generateId(),
    ): String = mutex.withLock {
        val vector = vectorize(text)
        val mv = MemoryVector(
            id = id,
            vector = vector,
            payload = payload.copy(content = if (payload.content.isEmpty()) text else payload.content),
            collection = collection,
        )
        store.add(mv)
        collections.getOrPut(collection) { mutableSetOf() }.add(id)

        DebugLog.d("VectorMemory", "put[$id] collection=$collection (${text.take(30)}...)")
        id
    }

    /**
     * 批量存入。
     */
    suspend fun putBatch(items: List<Pair<String, MemoryPayload>>, collection: String = "default"): Int {
        var count = 0
        for ((text, payload) in items) {
            put(text, payload, collection)
            count++
        }
        return count
    }

    /**
     * 按 ID 获取。
     */
    suspend fun get(id: String): MemoryVector? = mutex.withLock {
        store.find { it.id == id }
    }

    /**
     * 按 ID 删除。
     */
    suspend fun delete(id: String): Boolean = mutex.withLock {
        val idx = store.indexOfFirst { it.id == id }
        if (idx < 0) return@withLock false
        val removed = store.removeAt(idx)
        collections[removed.collection]?.remove(id)

        // 同步从持久化层删除（如果启用）
        if (isPersistent) {
            persistence!!.deleteById(id)
        }

        DebugLog.d("VectorMemory", "deleted [$id]")
        true
    }

    /**
     * 删除整个集合。
     */
    suspend fun deleteCollection(collection: String): Int = mutex.withLock {
        val ids = collections[collection]?.toSet() ?: emptySet()
        val beforeSize = store.size
        store.removeAll { it.id in ids || it.collection == collection }
        val count = beforeSize - store.size
        collections.remove(collection)

        // 同步从持久化层删除集合（如果启用）
        if (isPersistent) {
            persistence!!.deleteByCollection(collection)
        }

        count
    }

    // ==================== 搜索 API ====================

    /**
     * 相似度搜索（核心方法）。
     *
     * @param query 查询文本
     * @param limit 最大返回数量
     * @param minScore 最小相似度阈值
     * @param filter 过滤器（可选）
     * @param collection 搜索范围（null 表示全部集合）
     * @return 按相似度降序排列的结果列表
     */
    suspend fun search(
        query: String,
        limit: Int = 10,
        minScore: Float = 0.1f,
        filter: Filter = Filter.All,
        collection: String? = null,
    ): List<SearchResult> = mutex.withLock {
        if (store.isEmpty() || query.isBlank()) return@withLock emptyList()

        val queryVec = vectorize(query)
        val candidates = if (collection != null) {
            store.filter { it.collection == collection && filter.matches(it.payload) }
        } else {
            store.filter { filter.matches(it.payload) }
        }

        val scored = candidates.mapNotNull { mv ->
            val score = cosineSimilarity(queryVec, mv.vector)
            if (score >= minScore) SearchResult(mv, score, 0) else null
        }.sortedByDescending { it.score }

        scored.take(limit).mapIndexed { idx, result -> result.copy(rank = idx + 1) }
    }

    /**
     * 基于向量的搜索（已知查询向量时使用，避免重复计算）。
     */
    suspend fun searchByVector(
        queryVector: FloatArray,
        limit: Int = 10,
        minScore: Float = 0.1f,
        filter: Filter = Filter.All,
        collection: String? = null,
    ): List<SearchResult> = mutex.withLock {
        val candidates = if (collection != null) {
            store.filter { it.collection == collection && filter.matches(it.payload) }
        } else {
            store.filter { filter.matches(it.payload) }
        }

        candidates.mapNotNull { mv ->
            val score = cosineSimilarity(queryVector, mv.vector)
            if (score >= minScore) SearchResult(mv, score, 0) else null
        }.sortedByDescending { it.score }
            .take(limit)
            .mapIndexed { idx, result -> result.copy(rank = idx + 1) }
    }

    /**
     * 混合搜索：结合关键词匹配和向量相似度。
     *
     * 对标 Qdrant 的 hybrid search。
     */
    suspend fun hybridSearch(
        query: String,
        limit: Int = 10,
        keywordWeight: Float = 0.3f,
        vectorWeight: Float = 0.7f,
        filter: Filter = Filter.All,
    ): List<SearchResult> = mutex.withLock {
        if (store.isEmpty()) return@withLock emptyList()

        val queryVec = vectorize(query)
        val lowerQuery = query.lowercase()

        store.filter { filter.matches(it.payload) }.mapNotNull { mv ->
            // 向量分数
            val vecScore = cosineSimilarity(queryVec, mv.vector)

            // 关键词分数（简单的 BM25 近似）
            val kwTokens = tokenize(lowerQuery)
            val contentTokens = tokenize(mv.payload.content.lowercase())
            val kwHits = kwTokens.count { t -> contentTokens.any { it.contains(t) || it.contains(t) } }
            val kwScore = if (kwTokens.isNotEmpty()) kwHits.toFloat() / kwTokens.size.toFloat() else 0f

            // 加权混合
            val combinedScore = vecScore * vectorWeight + kwScore * keywordWeight

            if (combinedScore >= 0.05f) SearchResult(mv, combinedScore, 0) else null
        }.sortedByDescending { it.score }
            .take(limit)
            .mapIndexed { idx, r -> r.copy(rank = idx + 1) }
    }

    // ==================== 管理 API ====================

    /** 总向量数 */
    fun size(): Int = store.size

    /** 集合列表 */
    fun listCollections(): Set<String> = collections.keys.toSet()

    /** 某个集合的大小 */
    fun collectionSize(collection: String): Int = collections[collection]?.size ?: 0

    /**
     * 统计信息。
     */
    suspend fun stats(): VectorMemoryStats = mutex.withLock {
        VectorMemoryStats(
            totalVectors = store.size,
            totalCollections = collections.size,
            dimension = defaultDim,
            byCollection = collections.mapValues { it.value.size },
            bySource = store.groupingBy { it.payload.source }.eachCount(),
            avgPayloadSize = if (store.isNotEmpty()) store.sumOf { it.payload.content.length } / store.size else 0,
        )
    }

    /**
     * 导出所有数据（用于持久化）。
     */
    suspend fun exportAll(): List<MemoryVector> = mutex.withLock { store.toList() }

    /**
     * 导入数据。
     */
    suspend fun importAll(data: List<MemoryVector>) = mutex.withLock {
        store.clear()
        collections.clear()
        store.addAll(data)
        for (mv in data) {
            collections.getOrPut(mv.collection) { mutableSetOf() }.add(mv.id)
        }

        // 批量写入持久化层（如果启用）
        if (isPersistent && data.isNotEmpty()) {
            persistence!!.saveBatch(data)
        }

        DebugLog.i("VectorMemory", "imported ${data.size} vectors")
    }

    // ==================== 内部方法 ====================

    /** L2 归一化 */
    private fun normalize(vec: FloatArray): FloatArray {
        var norm = 0f
        for (v in vec) norm += v * v
        norm = sqrt(norm.toDouble()).toFloat().coerceAtLeast(1e-10f)
        for (i in vec.indices) vec[i] = vec[i] / norm
        return vec
    }

    /** 余弦相似度（输入应为已归一化向量） */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Vector dimension mismatch" }
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        // 已归一化，dot product 即 cosine similarity
        return ((dot + 1f) / 2f).coerceIn(0f, 1f)  // 映射到 [0,1]
    }

    /** 简单分词 */
    private fun tokenize(text: String): List<String> {
        return text.lowercase()
            .split(Regex("""[\p{P}\s]+"""))
            .filter { it.length >= 2 }  // 忽略单字符
            .take(200)                  // 截断超长文本
    }

    /** MurmurHash-style 简化哈希 */
    private fun hashToken(token: String, mod: Int): Int {
        var h = token.hashCode()
        h = h xor (h ushr 16)
        h = h * 0x85ebca6b.toInt()
        h = h xor (h ushr 13)
        h = ((h and 0x7fffffff) % mod)
        return h
    }

    /** 哈希符号（用于双射哈希减少偏差） */
    private fun hashSign(token: String): Boolean {
        var h = token.hashCode() + 1
        h = h xor (h ushr 8)
        return h % 2 == 0
    }

    private fun generateId(): String = "vec_${System.currentTimeMillis()}_${Random.nextInt(10000)}"
}

/** 统计信息 */
data class VectorMemoryStats(
    val totalVectors: Int,
    val totalCollections: Int,
    val dimension: Int,
    val byCollection: Map<String, Int>,
    val bySource: Map<String, Int>,
    val avgPayloadSize: Int,
) {
    fun toDisplayText(): String = """VectorMemory:
  | Vectors: $totalVectors (dim=$dimension)
  | Collections: $totalCollections ${byCollection.entries.joinToString { "${it.key}(${it.value})" }}
  | By source: ${bySource.entries.joinToString { "${it.key}=${it.value}" }}
  | Avg payload: ${avgPayloadSize} chars""".trimMargin()
}
