package top.hsyscn.opedrgent.network

import top.hsyscn.opedrgent.utils.DebugLog
import java.security.MessageDigest

/**
 * In-memory LRU caches for search results and DDG vqd tokens.
 *
 * Extracted from WebSearcher.kt. Owns:
 *  - the access-ordered LRU maps
 *  - hit/miss statistics
 *  - periodic cleanup of expired entries
 *  - sha256 helper used to build vqd cache keys
 *
 * Note: the higher-level [MultiLevelCacheManager] (L1 memory + L2 disk) is
 * still invoked by WebSearcher for the resilient search path; this class
 * holds the legacy hot cache that lives next to it.
 */
class SearchCacheManager {

    private data class CacheEntry<T>(
        val data: T,
        val timestamp: Long,
        var accessCount: Int = 0,
        var lastAccessTime: Long = timestamp
    )

    private val searchCache =
        LinkedHashMap<String, CacheEntry<List<SearchResult>>>(
            SearchConstants.MAX_CACHE_SIZE, 0.75f, true  // access-order
        )
    private val vqdCache =
        LinkedHashMap<String, CacheEntry<String>>(
            SearchConstants.MAX_VQD_CACHE_SIZE, 0.75f, true
        )

    // 缓存统计
    private var cacheHits = 0
    private var cacheMisses = 0
    private var lastCleanTime = 0L

    @Synchronized
    fun getFromCache(key: String): List<SearchResult>? {
        val entry = searchCache[key] ?: run {
            cacheMisses++
            return null
        }

        val now = System.currentTimeMillis()

        // 检查是否过期
        if (now - entry.timestamp > SearchConstants.SEARCH_CACHE_TTL_MS) {
            searchCache.remove(key)
            cacheMisses++
            return null
        }

        // 更新访问信息
        entry.accessCount++
        entry.lastAccessTime = now
        cacheHits++

        return entry.data
    }

    @Synchronized
    fun putToCache(key: String, results: List<SearchResult>) {
        if (results.isEmpty()) return

        // 如果超过最大容量，移除最老的条目（LRU）
        while (searchCache.size >= SearchConstants.MAX_CACHE_SIZE) {
            val oldestKey = searchCache.keys.iterator().next()
            searchCache.remove(oldestKey)
        }

        val now = System.currentTimeMillis()
        searchCache[key] = CacheEntry(
            data = results,
            timestamp = now,
            accessCount = 1,
            lastAccessTime = now
        )

        // 定期清理过期条目
        periodicCleanUp()
    }

    @Synchronized
    fun periodicCleanUp() {
        val now = System.currentTimeMillis()

        // 每隔指定时间执行一次全面清理
        if (now - lastCleanTime < SearchConstants.CACHE_CLEAN_INTERVAL_MS) return
        lastCleanTime = now

        // 清理过期的搜索结果缓存
        val expiredKeys = searchCache.filter {
            now - it.value.timestamp > SearchConstants.SEARCH_CACHE_TTL_MS
        }.keys

        expiredKeys.forEach { searchCache.remove(it) }

        // 清理过期的vqd缓存
        val expiredVqdKeys = vqdCache.filter {
            now - it.value.timestamp > SearchConstants.VQD_CACHE_TTL_MS
        }.keys

        expiredVqdKeys.forEach { vqdCache.remove(it) }

        if (expiredKeys.isNotEmpty() || expiredVqdKeys.isNotEmpty()) {
            DebugLog.d(
                "WebSearcher cache cleanup: removed ${expiredKeys.size} search entries, " +
                    "${expiredVqdKeys.size} vqd entries. Current size: " +
                    "${searchCache.size}/${SearchConstants.MAX_CACHE_SIZE}"
            )
        }
    }

    /**
     * 清理过期的vqd缓存条目
     */
    fun cleanExpiredVqdCache() {
        val now = System.currentTimeMillis()
        vqdCache.entries.removeAll { (_, entry) ->
            now - entry.timestamp > SearchConstants.VQD_CACHE_TTL_MS
        }
    }

    /**
     * 缓存 DDG 返回的 vqd 令牌，键由 query + UA 派生。
     */
    @Synchronized
    fun putVqd(query: String, userAgent: String, vqd: String) {
        if (vqd.isBlank()) return
        val cacheKey = sha256("$query//$userAgent")
        vqdCache[cacheKey] = CacheEntry(
            data = vqd,
            timestamp = System.currentTimeMillis()
        )
        DebugLog.d("WebSearcher DDG: cached vqd=$vqd")
    }

    @Synchronized
    fun getCacheStats(): Map<String, Any> {
        val total = cacheHits + cacheMisses
        return mapOf(
            "size" to searchCache.size,
            "maxSize" to SearchConstants.MAX_CACHE_SIZE,
            "hits" to cacheHits,
            "misses" to cacheMisses,
            "hitRate" to if (total > 0) "%.1f".format(cacheHits.toDouble() / total * 100) + "%" else "N/A",
            "vqdCacheSize" to vqdCache.size
        )
    }

    /**
     * SHA-256 十六进制摘要，用于派生稳定缓存键。
     */
    fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
