package top.hsyscn.opedrgent.note

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * 知识图谱自动关联引擎 — 参考 Wisen/Sinapsus 的语义嵌入方案。
 *
 * 核心思路：
 * 1. 将每条笔记转换为 TF-IDF 向量（轻量级，无需神经网络）
 * 2. 使用余弦相似度计算笔记间的语义相关性
 * 3. 自动建立双向关联（类似 Obsidian 的 backlink）
 * 4. 相似度超过阈值时自动推荐关联
 *
 * 设计原则：
 * - 本地优先：所有计算在设备端完成，无需联网
 * - 零配置：不需要用户手动设置
 * - 增量计算：新笔记只与已有笔记比较，不重复计算
 */
class KnowledgeGraph(private val context: Context) {

    companion object {
        private const val TAG = "KnowledgeGraph"
        private const val SIMILARITY_THRESHOLD = 0.15  // 自动关联阈值
        private const val MAX_LINKS_PER_NOTE = 10       // 每条笔记最多关联数
        private const val MIN_COMMON_WORDS = 2          // 最少共同词数
        private const val GRAPH_FILE = "knowledge_graph.json"
    }

    // 笔记ID → TF-IDF 向量
    private val embeddings = ConcurrentHashMap<String, FloatArray>()
    // 笔记ID → 关联的笔记ID列表
    private val links = ConcurrentHashMap<String, MutableSet<String>>()
    // 词汇表：word → index
    private val vocabulary = ConcurrentHashMap<String, Int>()
    // IDF 权重
    private var idf = floatArrayOf()
    // 词 → 包含该词的文档数（用于计算 IDF）
    private val termDocFrequency = ConcurrentHashMap<String, Int>()
    // 笔记ID → 该笔记包含的词集合（用于增量更新文档频率）
    private val noteWordSets = ConcurrentHashMap<String, Set<String>>()

    private val graphFile = File(context.filesDir, GRAPH_FILE)

    init {
        loadGraph()
    }

    // ==================== 核心 API ====================

    /**
     * 为笔记建立关联。新笔记保存或编辑后调用。
     * @return 新建立的关联笔记ID列表
     */
    fun linkNote(noteId: String, content: String): List<String> {
        if (content.isBlank()) return emptyList()

        // 提取关键词并计算 TF 向量
        val words = extractWords(content)
        if (words.isEmpty()) return emptyList()

        val tf = computeTf(words)

        synchronized(this) {
            // 更新词汇表和 IDF
            updateVocabulary(noteId, words)

            // 计算 TF-IDF 向量
            val embedding = computeTfIdf(tf)
            embeddings[noteId] = embedding

            // 与所有已有笔记计算相似度
            val newLinks = mutableListOf<String>()
            for ((otherId, otherEmbedding) in embeddings) {
                if (otherId == noteId) continue
                if (otherId in links.getOrDefault(noteId, emptySet())) continue

                val similarity = cosineSimilarity(embedding, otherEmbedding)
                if (similarity >= SIMILARITY_THRESHOLD) {
                    addLink(noteId, otherId)
                    newLinks.add(otherId)
                    DebugLog.d(TAG, "自动关联: $noteId ↔ $otherId (相似度=${String.format("%.3f", similarity)})")
                }
            }

            // 限制每个笔记的关联数
            trimLinks(noteId)

            // 持久化
            saveGraph()

            return newLinks
        }
    }

    /**
     * 获取笔记的所有关联笔记ID（按相关性排序）
     */
    fun getLinkedNotes(noteId: String): List<String> {
        return links[noteId]?.toList() ?: emptyList()
    }

    /**
     * 获取笔记的关联数
     */
    fun getLinkCount(noteId: String): Int {
        return links[noteId]?.size ?: 0
    }

    /**
     * 获取知识图谱的全局统计
     */
    fun getStats(): GraphStats {
        val totalNotes = embeddings.size
        val totalLinks = links.values.sumOf { it.size } / 2  // 双向链接除2
        val isolatedNotes = embeddings.keys.count { (links[it]?.size ?: 0) == 0 }

        return GraphStats(
            totalNotes = totalNotes,
            totalLinks = totalLinks,
            isolatedNotes = isolatedNotes,
            avgLinksPerNote = if (totalNotes > 0) totalLinks.toFloat() / totalNotes else 0f,
        )
    }

    /**
     * 获取所有关联关系（用于可视化）
     */
    fun getAllLinks(): List<GraphEdge> {
        val edges = mutableSetOf<GraphEdge>()
        for ((noteId, linkedIds) in links) {
            for (linkedId in linkedIds) {
                val edge = if (noteId < linkedId) {
                    GraphEdge(noteId, linkedId)
                } else {
                    GraphEdge(linkedId, noteId)
                }
                edges.add(edge)
            }
        }
        return edges.toList()
    }

    /**
     * 搜索与查询文本最相关的笔记
     */
    fun searchByRelevance(query: String, maxResults: Int = 5): List<Pair<String, Float>> {
        val queryWords = extractWords(query)
        if (queryWords.isEmpty()) return emptyList()

        val queryTf = computeTf(queryWords)
        synchronized(this) {
            updateVocabulary("_query_", queryWords)
            val queryEmbedding = computeTfIdf(queryTf)

            return embeddings.map { (noteId, embedding) ->
                noteId to cosineSimilarity(queryEmbedding, embedding)
            }
                .filter { it.second > 0.05 }
                .sortedByDescending { it.second }
                .take(maxResults)
        }
    }

    /**
     * 删除笔记的所有关联
     */
    fun removeNote(noteId: String) {
        synchronized(this) {
            val linkedIds = links.remove(noteId) ?: emptySet()
            for (linkedId in linkedIds) {
                links[linkedId]?.remove(noteId)
            }
            embeddings.remove(noteId)
            val wordSet = noteWordSets.remove(noteId)
            if (wordSet != null) {
                for (word in wordSet) {
                    termDocFrequency[word] = ((termDocFrequency[word] ?: 1) - 1).coerceAtLeast(0)
                }
            }
            saveGraph()
        }
    }

    // ==================== 内部实现 ====================

    private fun extractWords(text: String): List<String> {
        // 简单分词：按标点和空格分割，转小写，过滤停用词
        val stopWords = setOf(
            "的", "了", "在", "是", "我", "有", "和", "就", "不", "人", "都", "一", "一个",
            "上", "也", "很", "到", "说", "要", "去", "你", "会", "着", "没有", "看", "好",
            "自己", "这", "他", "她", "它", "们", "那", "这", "什么", "怎么", "如何", "可以",
            "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would", "could",
            "should", "may", "might", "can", "shall", "to", "of", "in", "for",
            "on", "with", "at", "by", "from", "as", "into", "through", "during",
            "before", "after", "above", "below", "between", "out", "off", "over",
            "under", "again", "further", "then", "once", "here", "there", "when",
            "where", "why", "how", "all", "each", "every", "both", "few", "more",
            "most", "other", "some", "such", "no", "nor", "not", "only", "own",
            "same", "so", "than", "too", "very", "just", "don", "now",
        )

        return text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")  // 保留字母和数字
            .split(Regex("\\s+"))
            .filter { it.length >= 2 && it !in stopWords }
            .distinct()
    }

    private fun computeTf(words: List<String>): Map<String, Int> {
        return words.groupingBy { it }.eachCount()
    }

    private fun updateVocabulary(noteId: String, words: List<String>) {
        synchronized(this) {
            val wordSet = words.toSet()
            val oldWordSet = noteWordSets[noteId]

            if (oldWordSet != null) {
                for (word in oldWordSet) {
                    if (word !in wordSet) {
                        termDocFrequency[word] = ((termDocFrequency[word] ?: 1) - 1).coerceAtLeast(0)
                    }
                }
            }
            for (word in wordSet) {
                if (!vocabulary.containsKey(word)) {
                    vocabulary[word] = vocabulary.size
                }
                if (oldWordSet == null || word !in oldWordSet) {
                    termDocFrequency[word] = (termDocFrequency[word] ?: 0) + 1
                }
            }
            noteWordSets[noteId] = wordSet

            val totalDocs = noteWordSets.size.coerceAtLeast(1)
            idf = FloatArray(vocabulary.size) { idx ->
                val word = vocabulary.entries.find { it.value == idx }?.key
                val df = word?.let { termDocFrequency[it] } ?: 1
                kotlin.math.ln(totalDocs.toDouble() / df.coerceAtLeast(1)).toFloat().coerceAtLeast(0f)
            }
        }
    }

    private fun computeTfIdf(tf: Map<String, Int>): FloatArray {
        val vec = FloatArray(vocabulary.size)
        val maxTf = tf.values.maxOrNull() ?: 1
        for ((word, count) in tf) {
            val idx = vocabulary[word] ?: continue
            val tfNorm = count.toFloat() / maxTf
            val idfWeight = if (idx < idf.size) idf[idx] else 1f
            vec[idx] = tfNorm * idfWeight
        }
        val norm = sqrt(vec.sumOf { (it * it).toDouble() }).toFloat()
        if (norm > 0f) {
            for (i in vec.indices) vec[i] /= norm
        }
        return vec
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        val size = minOf(a.size, b.size)
        if (size == 0) return 0f
        var dot = 0f
        for (i in 0 until size) dot += a[i] * b[i]
        return dot  // 已归一化，dot = cosine
    }

    private fun addLink(noteId: String, linkedId: String) {
        links.getOrPut(noteId) { mutableSetOf() }.add(linkedId)
        links.getOrPut(linkedId) { mutableSetOf() }.add(noteId)
    }

    private fun trimLinks(noteId: String) {
        val linked = links[noteId] ?: return
        if (linked.size <= MAX_LINKS_PER_NOTE) return

        val embedding = embeddings[noteId] ?: return
        val ranked = linked.map { otherId ->
            otherId to (embeddings[otherId]?.let { cosineSimilarity(embedding, it) } ?: 0f)
        }.sortedByDescending { it.second }

        linked.clear()
        linked.addAll(ranked.take(MAX_LINKS_PER_NOTE).map { it.first })
    }

    // ==================== 持久化 ====================

    private fun saveGraph() {
        try {
            val json = JSONObject()
            // 保存关联
            val linksArr = JSONObject()
            for ((noteId, linkedIds) in links) {
                linksArr.put(noteId, JSONArray(linkedIds.toList()))
            }
            json.put("links", linksArr)
            // 保存词汇表大小（嵌入向量太大，不保存）
            json.put("vocabSize", vocabulary.size)

            graphFile.writeText(json.toString())
            DebugLog.d(TAG, "知识图谱已保存: ${links.size} 个节点")
        } catch (e: Exception) {
            DebugLog.e(TAG, "保存知识图谱失败: ${e.message}")
        }
    }

    private fun loadGraph() {
        try {
            if (!graphFile.exists()) return
            val json = JSONObject(graphFile.readText())

            // 加载关联
            val linksArr = json.optJSONObject("links") ?: return
            for (noteId in linksArr.keys()) {
                val arr = linksArr.getJSONArray(noteId)
                val set = mutableSetOf<String>()
                for (i in 0 until arr.length()) set.add(arr.getString(i))
                links[noteId] = set
            }

            DebugLog.i(TAG, "知识图谱已加载: ${links.size} 个节点")
        } catch (e: Exception) {
            DebugLog.e(TAG, "加载知识图谱失败: ${e.message}")
        }
    }

    // ==================== 数据类 ====================

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
}
