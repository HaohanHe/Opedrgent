package top.hsyscn.opedrgent.utils

import top.hsyscn.opedrgent.env.EnvironmentInfo
import top.hsyscn.opedrgent.model.ResearchSession
import top.hsyscn.opedrgent.settings.ApiSettings
import top.hsyscn.opedrgent.storage.MemoryStore
import java.util.concurrent.ConcurrentHashMap

/**
 * LLM 响应缓存系统 — 支持精确匹配 + 相似 Query 模糊复用。
 *
 * ## 缓存层级
 *
 * 1. **精确匹配**: 完全相同的 query 直接命中缓存（O(1) 查找）
 * 2. **相似匹配**: 基于编辑距离的模糊匹配，相似度 >= 0.85 时复用缓存结果
 * 3. **全局缓存**: 静态 prompt 等长期有效的缓存条目
 * 4. **会话缓存**: 带 TTL 的临时缓存条目
 *
 * ## 相似度算法
 *
 * 使用归一化编辑距离（Levenshtein Distance），计算公式：
 * ```
 * similarity = 1.0 - editDistance(a, b) / max(a.length, b.length)
 * ```
 *
 * 对中文和英文都有效。阈值默认 0.85，可通过 [setSimilarityThreshold] 调整。
 *
 * ## 适用场景
 *
 * - 用户反复问类似问题（如 "Kotlin 协程怎么用" vs "Kotlin 协程使用方法"）
 * - 同一话题的不同表述方式
 * - 减少重复 API 调用，节省 token 和延迟
 *
 * ## 不适用场景
 *
 * - 需要最新实时信息的查询（如股价、天气）
 * - 需要精确数值计算的查询
 * - 对话上下文敏感的查询（缓存不考虑上下文）
 */
object PromptCache {
    private val globalCache = ConcurrentHashMap<String, String>()
    private val sessionCache = ConcurrentHashMap<String, CachedEntry?>()

    // ==================== 相似 Query 缓存 ====================

    /** 相似 Query 缓存: query 文本 -> 缓存结果 */
    private val similarCache = ConcurrentHashMap<String, CachedEntry>()

    /** 相似度阈值 (0.0-1.0)，默认 0.85 */
    private var similarityThreshold = 0.85

    /** 最大相似缓存条目数，防止内存膨胀 */
    private const val MAX_SIMILAR_CACHE_SIZE = 200

    private const val DEFAULT_TTL_MS = 5 * 60 * 1000L
    private const val STATIC_TTL_MS = 60 * 60 * 1000L
    private const val SIMILAR_TTL_MS = 10 * 60 * 1000L // 相似缓存 TTL: 10 分钟

    private const val STATIC_PROMPT_KEY = "system_static_prompt"

    data class CachedEntry(
        val value: String,
        val computedAt: Long = System.currentTimeMillis(),
        val ttlMs: Long = DEFAULT_TTL_MS,
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() - computedAt > ttlMs
    }

    // ==================== 相似度匹配 API ====================

    /**
     * 设置相似度阈值。
     *
     * @param threshold 0.0-1.0 之间的值。越高要求越严格（越相似才命中）。
     *                  默认 0.85，推荐范围 0.80-0.90。
     */
    fun setSimilarityThreshold(threshold: Double) {
        similarityThreshold = threshold.coerceIn(0.5, 0.99)
    }

    /**
     * 相似 Query 缓存查找。
     *
     * 遍历已缓存的 query，找相似度最高且超过阈值的条目。
     * 如果找到未过期的匹配，返回缓存结果；否则返回 null。
     *
     * @param query 用户查询文本
     * @return 缓存的结果文本；无匹配或已过期则返回 null
     */
    fun findSimilar(query: String): String? {
        val normalizedQuery = normalizeQuery(query)
        if (normalizedQuery.isBlank()) return null

        var bestMatch: String? = null
        var bestSimilarity = 0.0

        for ((cachedQuery, entry) in similarCache) {
            if (entry.isExpired()) continue

            val normalizedCached = normalizeQuery(cachedQuery)
            val similarity = computeSimilarity(normalizedQuery, normalizedCached)

            if (similarity >= similarityThreshold && similarity > bestSimilarity) {
                bestSimilarity = similarity
                bestMatch = entry.value
            }
        }

        if (bestMatch != null) {
            similarHits++
            DebugLog.i(TAG, "相似缓存命中: similarity=${"%.2f".format(bestSimilarity)}, threshold=$similarityThreshold")
        }

        return bestMatch
    }

    /**
     * 存入相似 Query 缓存。
     *
     * @param query 用户查询文本（作为缓存 key）
     * @param result 缓存的 LLM 响应结果
     * @param ttlMs 缓存有效期（默认 10 分钟）
     */
    fun putSimilar(query: String, result: String, ttlMs: Long = SIMILAR_TTL_MS) {
        // 容量限制：达到上限时清理过期条目
        if (similarCache.size >= MAX_SIMILAR_CACHE_SIZE) {
            evictExpiredSimilar()
            // 如果清理后仍然满，移除最旧的条目
            if (similarCache.size >= MAX_SIMILAR_CACHE_SIZE) {
                val oldest = similarCache.entries.minByOrNull { it.value.computedAt }
                if (oldest != null) similarCache.remove(oldest.key)
            }
        }

        similarCache[query] = CachedEntry(value = result, ttlMs = ttlMs)
        DebugLog.d(TAG, "相似缓存写入: query=${query.take(50)}, ttl=${ttlMs}ms, size=${similarCache.size}")
    }

    /**
     * 组合查找：先精确匹配，再相似匹配。
     *
     * @param query 查询文本
     * @return 缓存结果；无命中返回 null
     */
    fun findCached(query: String): String? {
        // 1. 精确匹配（session 级）
        val exact = getSessionCached(query)
        if (exact != null) return exact

        // 2. 相似匹配
        return findSimilar(query)
    }

    /**
     * 组合写入：同时写入精确缓存和相似缓存。
     *
     * @param query 查询文本
     * @param result LLM 响应结果
     */
    fun putCached(query: String, result: String) {
        putSession(query, result)
        putSimilar(query, result)
    }

    // ==================== 相似度算法 ====================

    /**
     * 归一化编辑距离相似度（Levenshtein Distance）。
     *
     * 基于动态规划的编辑距离计算，适合短文本（<200 字符）。
     * 对中文字符和英文单词都有效。
     *
     * @return 0.0-1.0 之间的相似度值
     */
    private fun computeSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0

        // 长度差异过大时直接返回低相似度（优化：避免长文本的 O(n*m) 计算）
        val maxLen = maxOf(a.length, b.length)
        val minLen = minOf(a.length, b.length)
        if (minLen.toDouble() / maxLen < similarityThreshold * 0.8) {
            return 0.0
        }

        val distance = levenshteinDistance(a, b)
        return 1.0 - distance.toDouble() / maxLen
    }

    /**
     * Levenshtein 编辑距离（空间优化版，O(min(m,n)) 空间）。
     */
    private fun levenshteinDistance(a: String, b: String): Int {
        val s1 = if (a.length <= b.length) a else b
        val s2 = if (a.length <= b.length) b else a

        var prevRow = IntArray(s1.length + 1) { it }
        var currRow = IntArray(s1.length + 1)

        for (i in 1..s2.length) {
            currRow[0] = i
            for (j in 1..s1.length) {
                val cost = if (s1[j - 1] == s2[i - 1]) 0 else 1
                currRow[j] = minOf(
                    currRow[j - 1] + 1,      // 插入
                    prevRow[j] + 1,           // 删除
                    prevRow[j - 1] + cost,    // 替换
                )
            }
            val temp = prevRow
            prevRow = currRow
            currRow = temp
        }

        return prevRow[s1.length]
    }

    /**
     * 查询文本归一化：去空白、转小写、去标点。
     */
    private val REGEX_MULTI_SPACE = Regex("[\\s]+")
    private val REGEX_CN_PUNCT = Regex("[，。！？、；：\u201C\u201D\u2018\u2019（）【】《》]")
    private val REGEX_EN_PUNCT = Regex("[,.!?;:\"'()\\[\\]<>]")

    private fun normalizeQuery(query: String): String {
        return query.trim()
            .lowercase()
            .replace(REGEX_MULTI_SPACE, " ")       // 多空白合并
            .replace(REGEX_CN_PUNCT, "")            // 去中文标点
            .replace(REGEX_EN_PUNCT, "")            // 去英文标点
    }

    // ==================== 清理 ====================

    /**
     * 清理过期的相似缓存条目。
     */
    private fun evictExpiredSimilar() {
        val iterator = similarCache.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.isExpired()) {
                iterator.remove()
            }
        }
    }

    /**
     * 清理所有过期缓存（相似 + 会话）。
     */
    fun evictExpired() {
        evictExpiredSimilar()
        val sessionIterator = sessionCache.entries.iterator()
        while (sessionIterator.hasNext()) {
            val entry = sessionIterator.next().value
            if (entry == null || entry.isExpired()) {
                sessionIterator.remove()
            }
        }
        DebugLog.d(TAG, "清理过期缓存完成: similar=${similarCache.size}, session=${sessionCache.size}")
    }

    // ==================== 缓存命中统计 ====================

    private var staticHits = 0L
    private var staticMisses = 0L
    private var sessionHits = 0L
    private var sessionMisses = 0L
    private var similarHits = 0L

    // ==================== 原有 API（保持兼容） ====================

    private const val TAG = "PromptCache"

    fun getOrComputeStaticPrompt(compute: () -> String): String {
        globalCache[STATIC_PROMPT_KEY]?.let {
            staticHits++
            return it
        }
        staticMisses++
        val value = compute()
        globalCache[STATIC_PROMPT_KEY] = value
        return value
    }

    fun clearStaticPrompt() {
        globalCache.remove(STATIC_PROMPT_KEY)
    }

    fun getGlobal(key: String): String? = globalCache[key]

    fun getOrComputeGlobal(key: String, compute: () -> String): String {
        globalCache[key]?.let { return it }
        val value = compute()
        globalCache[key] = value
        return value
    }

    fun getSessionCached(key: String): String? {
        val entry = sessionCache[key] ?: return null
        return if (entry.isExpired()) null else {
            sessionHits++
            entry.value
        }
    }

    fun getOrComputeSession(key: String, compute: () -> String?): String? {
        val existing = sessionCache[key]
        if (existing != null && !existing.isExpired()) {
            return existing.value
        }
        val value = compute() ?: return null
        sessionCache[key] = CachedEntry(value)
        return value
    }

    fun putSession(key: String, value: String, ttlMs: Long = DEFAULT_TTL_MS) {
        sessionCache[key] = CachedEntry(value, ttlMs = ttlMs)
    }

    fun clearSession() {
        sessionCache.clear()
    }

    fun clearSimilar() {
        similarCache.clear()
    }

    fun clearAll() {
        globalCache.clear()
        sessionCache.clear()
        similarCache.clear()
    }

    fun getStats(): Map<String, Any> {
        val totalStatic = staticHits + staticMisses
        val totalSession = sessionHits + sessionMisses
        return mapOf(
            "global_cache_size" to globalCache.size,
            "session_cache_size" to sessionCache.size,
            "similar_cache_size" to similarCache.size,
            "similarity_threshold" to similarityThreshold,
            "static_hit_rate" to if (totalStatic > 0) "%.1f%%".format(staticHits * 100.0 / totalStatic) else "N/A",
            "session_hit_rate" to if (totalSession > 0) "%.1f%%".format(sessionHits * 100.0 / totalSession) else "N/A",
            "similar_hits" to similarHits,
            "static_hits" to staticHits,
            "static_misses" to staticMisses,
        )
    }
}
