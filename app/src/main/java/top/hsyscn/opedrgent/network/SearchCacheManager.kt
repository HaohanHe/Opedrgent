package top.hsyscn.opedrgent.network

import top.hsyscn.opedrgent.utils.DebugLog
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

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
 *
 * Concurrency design:
 *  - [ConcurrentHashMap] stores the actual entries; reads/writes are fine-grained.
 *  - A small [LinkedHashMap] (access-order) per cache tracks LRU ordering and is
 *    protected by a [ReentrantReadWriteLock]. Only order mutations take the write
 *    lock; capacity eviction is serialized with ordering changes.
 *  - Statistics use lock-free [AtomicLong]/[AtomicInteger] counters.
 */
class SearchCacheManager {

    private data class CacheEntry<T>(
        val data: T,
        val timestamp: Long,
        val accessCount: AtomicInteger = AtomicInteger(0),
        val lastAccessTime: AtomicLong = AtomicLong(timestamp)
    )

    private val searchCache =
        ConcurrentHashMap<String, CacheEntry<List<SearchResult>>>()
    private val vqdCache =
        ConcurrentHashMap<String, CacheEntry<String>>()

    // LRU ordering indices. The maps are only used for their key ordering;
    // values are dummies. Mutations are guarded by [lruLock].
    private val searchLru =
        LinkedHashMap<String, Boolean>(
            SearchConstants.MAX_CACHE_SIZE, 0.75f, true
        )
    private val vqdLru =
        LinkedHashMap<String, Boolean>(
            SearchConstants.MAX_VQD_CACHE_SIZE, 0.75f, true
        )
    private val lruLock = ReentrantReadWriteLock()

    // 缓存统计 (lock-free)
    private val cacheHits = AtomicLong(0)
    private val cacheMisses = AtomicLong(0)
    private val lastCleanTime = AtomicLong(0L)

    fun getFromCache(key: String): List<SearchResult>? {
        val entry = searchCache[key] ?: run {
            cacheMisses.incrementAndGet()
            return null
        }

        val now = System.currentTimeMillis()

        // 检查是否过期
        if (now - entry.timestamp > SearchConstants.SEARCH_CACHE_TTL_MS) {
            // Only remove the entry we actually inspected to avoid racing a fresh put.
            searchCache.remove(key, entry)
            lruLock.writeLock().withLock { searchLru.remove(key) }
            cacheMisses.incrementAndGet()
            return null
        }

        // 更新访问信息 (lock-free)
        entry.accessCount.incrementAndGet()
        entry.lastAccessTime.set(now)
        cacheHits.incrementAndGet()

        // 更新 LRU 顺序
        lruLock.writeLock().withLock {
            searchLru.remove(key)
            searchLru[key] = true
        }

        return entry.data
    }

    fun putToCache(key: String, results: List<SearchResult>) {
        if (results.isEmpty()) return

        val now = System.currentTimeMillis()
        val newEntry = CacheEntry(
            data = results,
            timestamp = now,
            accessCount = AtomicInteger(1),
            lastAccessTime = AtomicLong(now)
        )

        // 如果超过最大容量，移除最老的条目（LRU）
        lruLock.writeLock().withLock {
            while (searchLru.size >= SearchConstants.MAX_CACHE_SIZE && searchLru.isNotEmpty()) {
                val oldestKey = searchLru.keys.iterator().next()
                searchLru.remove(oldestKey)
                searchCache.remove(oldestKey)
            }
            searchLru.remove(key)
            searchLru[key] = true
        }

        searchCache[key] = newEntry

        // 定期清理过期条目
        periodicCleanUp()
    }

    fun periodicCleanUp() {
        val now = System.currentTimeMillis()

        // 每隔指定时间执行一次全面清理 (CAS 保证只有一个线程触发)
        val lastClean = lastCleanTime.get()
        if (now - lastClean < SearchConstants.CACHE_CLEAN_INTERVAL_MS) return
        if (!lastCleanTime.compareAndSet(lastClean, now)) return

        // 清理过期的搜索结果缓存
        val expiredKeys = mutableListOf<String>()
        searchCache.forEach { (k, v) ->
            if (now - v.timestamp > SearchConstants.SEARCH_CACHE_TTL_MS) {
                expiredKeys.add(k)
            }
        }
        if (expiredKeys.isNotEmpty()) {
            lruLock.writeLock().withLock {
                expiredKeys.forEach { key ->
                    searchLru.remove(key)
                    searchCache.remove(key)
                }
            }
        }

        // 清理过期的 vqd 缓存
        val expiredVqdKeys = mutableListOf<String>()
        vqdCache.forEach { (k, v) ->
            if (now - v.timestamp > SearchConstants.VQD_CACHE_TTL_MS) {
                expiredVqdKeys.add(k)
            }
        }
        if (expiredVqdKeys.isNotEmpty()) {
            lruLock.writeLock().withLock {
                expiredVqdKeys.forEach { key ->
                    vqdLru.remove(key)
                    vqdCache.remove(key)
                }
            }
        }

        if (expiredKeys.isNotEmpty() || expiredVqdKeys.isNotEmpty()) {
            DebugLog.d(
                "WebSearcher cache cleanup: removed ${expiredKeys.size} search entries, " +
                    "${expiredVqdKeys.size} vqd entries. Current size: " +
                    "${searchCache.size}/${SearchConstants.MAX_CACHE_SIZE}"
            )
        }
    }

    /**
     * 清理过期的 vqd 缓存条目
     */
    fun cleanExpiredVqdCache() {
        val now = System.currentTimeMillis()
        val expiredKeys = mutableListOf<String>()
        vqdCache.forEach { (k, v) ->
            if (now - v.timestamp > SearchConstants.VQD_CACHE_TTL_MS) {
                expiredKeys.add(k)
            }
        }
        if (expiredKeys.isNotEmpty()) {
            lruLock.writeLock().withLock {
                expiredKeys.forEach { key ->
                    vqdLru.remove(key)
                    vqdCache.remove(key)
                }
            }
        }
    }

    /**
     * 缓存 DDG 返回的 vqd 令牌，键由 query + UA 派生。
     */
    fun putVqd(query: String, userAgent: String, vqd: String) {
        if (vqd.isBlank()) return
        val cacheKey = sha256("$query//$userAgent")
        val now = System.currentTimeMillis()

        lruLock.writeLock().withLock {
            while (vqdLru.size >= SearchConstants.MAX_VQD_CACHE_SIZE && vqdLru.isNotEmpty()) {
                val oldestKey = vqdLru.keys.iterator().next()
                vqdLru.remove(oldestKey)
                vqdCache.remove(oldestKey)
            }
            vqdLru.remove(cacheKey)
            vqdLru[cacheKey] = true
        }

        vqdCache[cacheKey] = CacheEntry(data = vqd, timestamp = now)
        DebugLog.d("WebSearcher DDG: cached vqd=$vqd")
    }

    fun getCacheStats(): Map<String, Any> {
        val hits = cacheHits.get()
        val misses = cacheMisses.get()
        val total = hits + misses
        return lruLock.readLock().withLock {
            mapOf(
                "size" to searchLru.size,
                "maxSize" to SearchConstants.MAX_CACHE_SIZE,
                "hits" to hits,
                "misses" to misses,
                "hitRate" to if (total > 0) "%.1f".format(hits.toDouble() / total * 100) + "%" else "N/A",
                "vqdCacheSize" to vqdLru.size
            )
        }
    }

    /**
     * SHA-256 十六进制摘要，用于派生稳定缓存键。
     */
    fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
