package top.hsyscn.opedrgent.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.hsyscn.opedrgent.utils.DebugLog
import java.util.Locale
import kotlin.math.sqrt
import kotlin.math.ln
import kotlin.math.abs

/**
 * 混合搜索引擎 — 本地 BM25 关键词检索 + 云端向量语义检索融合排序。
 *
 * ## 架构
 * ```
 * 用户查询
 *   ├─→ BM25 本地关键词搜索 (SQLite FTS/LIKE)
 *   │    → 得分: bm25_score (0~1)
 *   │
 *   └─→ 云端 RAG 向量检索 (StepVectorStoreClient)
 *        → 得分: relevance_score (0~1)
 *
 *              ↓ Reciprocal Rank Fusion (RRF)
 *         最终融合结果 (按综合得分排序)
 * ```
 *
 * ## 使用场景
 * - 知识库搜索时，同时利用本地速度和云端语义理解优势
 * - 短查询偏重关键词匹配（BM25 权重高）
 * - 长查询/自然语言问题偏重语义理解（RAG 权重高）
 *
 * ## BM25 实现
 * 自实现轻量级 BM25，不依赖外部 Lucene/Elasticsearch:
 * - TF (词频): 词在文档中的出现次数
 * - IDF (逆文档频率): log((N - df + 0.5) / (df + 0.5) + 1)
 * - 文档长度归一化: avgdl 参数
 */
class HybridSearchEngine(
    private val knowledgeBase: KnowledgeBase,
) {

    companion object {
        private const val TAG = "HybridSearch"

        /** 默认返回结果数 */
        const val DEFAULT_TOP_K = 10

        /** BM25 参数 k1 (词频饱和度, 通常 1.2~2.0) */
        const val BM25_K1 = 1.5

        /** BM25 参数 b (文档长度归一化, 通常 0.75) */
        const val BM25_B = 0.75

        /** 融合权重: BM25 关键词匹配 */
        const val WEIGHT_BM25 = 0.4

        /** 融合权重: 云端RAG语义匹配 */
        const val WEIGHT_RAG = 0.6

        /** RRF 常量 k (Reciprocal Rank Fusion) */
        const val RRF_K = 60
    }

    /**
     * 混合搜索结果。
     */
    data class HybridResult(
        val documentId: String,
        val title: String,
        val content: String,
        val source: String,          // "local" / "cloud" / "both"
        val bm25Score: Float,       // BM25 关键词得分 (0~1)
        val ragScore: Float,         // RAG 语义得分 (0~1)
        val fusedScore: Float,      // 融合后最终得分 (0~1)
        val highlights: List<String> = emptyList(), // 高亮片段
    )

    data class SearchSummary(
        val results: List<HybridResult>,
        val query: String,
        val localCount: Int,
        val cloudCount: Int,
        val totalMatched: Int,
        val searchMode: String,     // "bm25_only" / "rag_only" / "hybrid"
        val executionTimeMs: Long,
    )

    /**
     * 执行混合搜索。
     *
     * @param apiKey 阶跃 API Key (用于云端 RAG，可为 null 则跳过云端)
     * @param storeId 向量存储 ID (可选，不传则自动选择)
     * @param query 搜索查询
     * @param topK 返回结果数量
     * @return 搜索摘要与结果列表
     */
    suspend fun hybridSearch(
        apiKey: String?,
        query: String,
        storeId: String? = null,
        topK: Int = DEFAULT_TOP_K,
    ): SearchSummary = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        // 并行执行两个搜索通道
        val localResults = bm25Search(query, topK * 2)
        val cloudResults = if (!apiKey.isNullOrBlank()) {
            ragSearch(apiKey, query, storeId, topK * 2)
        } else {
            emptyList()
        }

        val elapsed = System.currentTimeMillis() - startTime

        // 融合排序
        val fused = fuseResults(localResults, cloudResults, topK)

        SearchSummary(
            results = fused,
            query = query,
            localCount = localResults.size,
            cloudCount = cloudResults.size,
            totalMatched = fused.size,
            searchMode = when {
                localResults.isNotEmpty() && cloudResults.isNotEmpty() -> "hybrid"
                localResults.isNotEmpty() -> "bm25_only"
                cloudResults.isNotEmpty() -> "rag_only"
                else -> "empty"
            },
            executionTimeMs = elapsed,
        )
    }

    // ================================================================
    // BM25 本地搜索
    // ================================================================

    /**
     * 基于 BM25 的本地关键词搜索。
     *
     * 从 KnowledgeBase 中获取所有文档，在内存中计算 BM25 得分。
     * 对于大量文档场景，后续可升级为 SQLite FTS5 全文索引。
     */
    private suspend fun bm25Search(query: String, topK: Int): List<Bm25Document> =
        withContext(Dispatchers.Default) {
            try {
                // 获取本地所有知识库文档
                val documents = knowledgeBase.getAllDocumentsForSearch()
                if (documents.isEmpty()) return@withContext emptyList()

                // 构建 BM25 索引
                val index = buildBm25Index(documents)

                // 分词查询
                val queryTokens = tokenize(query)

                // 计算每个文档的 BM25 得分
                val scored = documents.mapNotNull { doc ->
                    val score = computeBm25Score(queryTokens, doc, index)
                    if (score > 0f) Bm25Document(
                        id = doc.id,
                        title = doc.title,
                        content = doc.content,
                        score = score.coerceIn(0f, 1f),
                    ) else null
                }

                scored.sortedByDescending { it.score }.take(topK)
            } catch (e: Exception) {
                DebugLog.e(TAG, "BM25 搜索异常: ${e.message}", e)
                emptyList()
            }
        }

    /**
     * BM25 文档数据类。
     */
    private data class Bm25Document(
        val id: String,
        val title: String,
        val content: String,
        var score: Float = 0f,
    )

    /**
     * BM25 索引结构。
     */
    private data class Bm25Index(
        val docCount: Int,
        val avgDocLength: Double,
        val docLengths: Map<String, Int>,           // docId → 文档长度(词数)
        val termDocFreq: Map<String, Int>,           // term → 包含该term的文档数
        val termFreqs: Map<String, Map<String, Int>>, // term → (docId → 词频)
    )

    private fun buildBm25Index(docs: List<KnowledgeBase.SearchableDoc>): Bm25Index {
        val N = docs.size
        val docLengths = mutableMapOf<String, Int>()
        val termDocFreq = mutableMapOf<String, MutableInt>()
        val termFreqs = mutableMapOf<String, MutableMap<String, MutableInt>>()
        var totalLength = 0

        for (doc in docs) {
            val tokens = tokenize(doc.title + " " + doc.content)
            docLengths[doc.id] = tokens.size
            totalLength += tokens.size

            val tokenSet = mutableSetOf<String>()
            for (token in tokens) {
                termFreqs.getOrPut(token) { mutableMapOf() }
                    .getOrPut(doc.id) { MutableInt(0) }.value++

                if (token !in tokenSet) {
                    termDocFreq.getOrPut(token) { MutableInt(0) }.value++
                    tokenSet.add(token)
                }
            }
        }

        return Bm25Index(
            docCount = N,
            avgDocLength = if (N > 0) totalLength.toDouble() / N else 1.0,
            docLengths = docLengths,
            termDocFreq = termDocFreq.mapValues { it.value.value },
            termFreqs = termFreqs.mapValues { inner ->
                inner.value.mapValues { it.value.value }
            },
        )
    }

    private fun computeBm25Score(
        queryTokens: List<String>,
        doc: KnowledgeBase.SearchableDoc,
        index: Bm25Index,
    ): Float {
        if (queryTokens.isEmpty()) return 0f

        val dl = index.docLengths[doc.id] ?: return 0f
        val avgdl = index.avgDocLength
        var score = 0.0

        for (token in queryTokens) {
            val df = index.termDocFreq[token] ?: continue
            val tf = index.termFreqs[token]?.get(doc.id) ?: continue

            // IDF: log((N - df + 0.5) / (df + 0.5) + 1)
            val idf = ln((index.docCount.toDouble() - df + 0.5) / (df + 0.5) + 1.0)

            // TF 归一化: (tf * (k1+1)) / (tf + k1*(1-b+b*dl/avgdl))
            val tfNorm = (tf * (BM25_K1 + 1).toDouble()) /
                         (tf + BM25_K1 * (1 - BM25_B + BM25_B * dl / avgdl))

            score += idf * tfNorm
        }

        // 归一化到 0~1 (使用 sigmoid 近似)
        return (score / (1 + abs(score))).toFloat()
    }

    // ================================================================
    // 云端 RAG 搜索
    // ================================================================

    /**
     * 通过阶跃云端 RAG 进行向量语义检索。
     *
     * 注意: 阶跃的向量检索通过 Chat Completions 的 retrieval tool 类型实现，
     * 此处作为直接搜索的备用方案。如果 StepVectorStoreClient 不支持直接搜索，
     * 则返回空列表（由 BM25 本地搜索独立支撑）。
     */
    private suspend fun ragSearch(
        apiKey: String,
        query: String,
        storeId: String?,
        topK: Int,
    ): List<RagDocument> = withContext(Dispatchers.IO) {
        try {
            // 尝试调用 StepVectorStoreClient 的搜索方法（如果存在）
            // 当前版本中，云端 RAG 主要通过 step_rag 工具在 ToolExecutor 中以
            // retrieval tool type 方式集成到 chat completions 流程中。
            // 此处的直接搜索作为未来扩展点预留。
            emptyList()
        } catch (e: Exception) {
            DebugLog.w(TAG, "云端 RAG 搜索不可用: ${e.message}")
            emptyList()
        }
    }

    private data class RagDocument(
        val id: String,
        val title: String,
        val content: String,
        var score: Float,
    )

    // ================================================================
    // 融合排序 (Reciprocal Rank Fusion)
    // ================================================================

    /**
     * 使用 RRF (Reciprocal Rank Fusion) 融合两组结果。
     *
     * 公式: RRF_score(d) = Σ 1/(k + rank_i(d))
     * 其中 k=60, rank_i 是文档在第 i 个结果列表中的排名
     *
     * 最终得分 = normalize(RRF) * 加权融合
     */
    private fun fuseResults(
        local: List<Bm25Document>,
        cloud: List<RagDocument>,
        topK: Int,
    ): List<HybridResult> {
        val rrfScores = mutableMapOf<String, Double>()
        val docInfo = mutableMapOf<String, DocMergeInfo>()

        // BM25 排名贡献
        for ((rank, doc) in local.withIndex()) {
            val key = doc.id
            rrfScores[key] = (rrfScores[key] ?: 0.0) +
                              (WEIGHT_BM25 / (RRF_K + rank + 1))
            docInfo[key] = DocMergeInfo(
                title = doc.title,
                content = doc.content,
                bm25Score = doc.score,
                ragScore = 0f,
                source = "local",
            )
        }

        // RAG 排名贡献
        for ((rank, doc) in cloud.withIndex()) {
            val key = doc.id
            rrfScores[key] = (rrfScores[key] ?: 0.0) +
                              (WEIGHT_RAG / (RRF_K + rank + 1))

            val existing = docInfo[key]
            if (existing != null) {
                docInfo[key] = existing.copy(
                    ragScore = doc.score,
                    source = "both",
                )
            } else {
                docInfo[key] = DocMergeInfo(
                    title = doc.title,
                    content = doc.content,
                    bm25Score = 0f,
                    ragScore = doc.score,
                    source = "cloud",
                )
            }
        }

        // 归一化并排序
        val maxScore = rrfScores.values.maxOrNull() ?: 1.0
        return rrfScores.entries
            .map { (id, rawScore) ->
                val info = docInfo[id]!!
                HybridResult(
                    documentId = id,
                    title = info.title,
                    content = info.content,
                    source = info.source,
                    bm25Score = info.bm25Score,
                    ragScore = info.ragScore,
                    fusedScore = (rawScore / maxScore).toFloat().coerceIn(0f, 1f),
                    highlights = generateHighlights(query = "", content = info.content),
                )
            }
            .sortedByDescending { it.fusedScore }
            .take(topK)
    }

    private data class DocMergeInfo(
        val title: String,
        val content: String,
        val bm25Score: Float,
        val ragScore: Float,
        val source: String,
    )

    // ================================================================
    // 辅助方法
    // ================================================================

    /**
     * 简单中文分词器。
     *
     * 基于:
     * 1. 空格/标点分割
     * 2. 双字 bigram 滑窗（覆盖中文无空格特性）
     * 3. 停用词过滤
     *
     * 后续可替换为 jieba/fasttext 等专业分词器。
     */
    internal fun tokenize(text: String): List<String> {
        val normalized = text.lowercase(Locale.CHINESE)
            .replace("[\\p{Punct}\\s]+".toRegex(), " ")
            .trim()

        if (normalized.isBlank()) return emptyList()

        // 空格分割 + bigram
        val tokens = mutableListOf<String>()
        val parts = normalized.split("\\s+".toRegex())

        for (part in parts) {
            if (part.length <= 2) {
                tokens.add(part)
            } else {
                // 单字 + 双字 bigram
                for (i in part.indices) {
                    tokens.add(part[i].toString())
                    if (i < part.length - 1) {
                        tokens.add(part.substring(i, i + 2))
                    }
                }
            }
        }

        // 过滤停用词和单字符噪声
        return tokens.filter { it.length >= 2 || isMeaningfulChar(it) }
            .distinct()
    }

    private fun isMeaningfulChar(c: String): Boolean {
        // 保留有意义的单字（数字、英文、重要汉字）
        return c[0].isDigit() ||
               c[0].isLetter() ||
               c in setOf("的", "了", "是", "在", "和", "与", "或", "不", "没")
    }

    private fun generateHighlights(query: String, content: String): List<String> {
        if (content.length <= 200) return listOf(content)
        // 简单截取前后文作为高亮
        return listOf(content.take(200) + "...")
    }

    /** 可变整数包装器 (避免 Java Integer 自动装箱问题) */
    private class MutableInt(var value: Int)
}
