package top.hsyscn.opedrgent.network

import android.util.LruCache
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import top.hsyscn.opedrgent.utils.DebugLog
import java.util.concurrent.atomic.AtomicLong

data class SearchResultSet(
    val results: List<SearchResult>,
    val timestamp: Long,
    val query: String,
    val providerOrder: String
) {
    fun toJson(): String {
        val arr = JSONArray()
        for (r in results) {
            val obj = JSONObject()
            obj.put("title", r.title)
            obj.put("url", r.url)
            obj.put("snippet", r.snippet ?: "")
            val enginesArr = JSONArray()
            for (eng in r.sourceEngines) enginesArr.put(eng)
            obj.put("sourceEngines", enginesArr)
            obj.put("score", r.score)
            arr.put(obj)
        }
        val root = JSONObject()
        root.put("results", arr)
        root.put("timestamp", timestamp)
        root.put("query", query)
        root.put("providerOrder", providerOrder)
        return root.toString()
    }

    companion object {
        fun fromJson(json: String): SearchResultSet? {
            return try {
                val root = JSONObject(json)
                val resultsArr = root.optJSONArray("results") ?: return null
                val results = mutableListOf<SearchResult>()
                for (i in 0 until resultsArr.length()) {
                    val item = resultsArr.getJSONObject(i)
                    val sourceEngines = mutableSetOf<String>()
                    val enginesArr = item.optJSONArray("sourceEngines")
                    if (enginesArr != null) {
                        for (j in 0 until enginesArr.length()) {
                            sourceEngines.add(enginesArr.getString(j))
                        }
                    }
                    results.add(
                        SearchResult(
                            title = item.getString("title"),
                            url = item.getString("url"),
                            snippet = item.optString("snippet").takeIf { it.isNotEmpty() },
                            sourceEngines = sourceEngines,
                            score = item.optDouble("score", 0.0)
                        )
                    )
                }
                SearchResultSet(
                    results = results,
                    timestamp = root.getLong("timestamp"),
                    query = root.getString("query"),
                    providerOrder = root.optString("providerOrder")
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

data class CacheConfig(
    val l1MaxSize: Int = 10 * 1024 * 1024,
    val l1TtlMs: Long = 5 * 60_000L,
    val l2MaxSize: Int = 10000,
    val l2TtlMs: Long = 24 * 3600_000L
)

private data class CacheEntry<T>(
    val data: T,
    val timestamp: Long,
    val ttlMs: Long,
    var accessCount: Int = 0,
    var lastAccessTime: Long = System.currentTimeMillis()
) {
    val isExpired: Boolean get() = System.currentTimeMillis() - timestamp > ttlMs
}

class MultiLevelCacheManager(
    private val config: CacheConfig = CacheConfig()
) {
    private val l1Cache: LruCache<String, CacheEntry<SearchResultSet>> = object : LruCache<String, CacheEntry<SearchResultSet>>(config.l1MaxSize) {
        override fun sizeOf(key: String, value: CacheEntry<SearchResultSet>): Int {
            var size = key.toByteArray(Charsets.UTF_8).size
            for (r in value.data.results) {
                size += (r.title.length + r.url.length + (r.snippet?.length ?: 0)) * 2 + 64
            }
            return size.coerceAtLeast(1)
        }
    }

    // L2 用访问顺序 LinkedHashMap 做 LRU：迭代器首个 entry 即最久未访问者，淘汰 O(1)，
    // 替换原先每次 put 满容时对 1 万条全量排序的 O(n log n) 做法。
    // 所有读写均在 mutex.withLock 内串行化；getStats 仅读 size，最坏略陈旧，不影响正确性。
    private val l2Cache: LinkedHashMap<String, CacheEntry<SearchResultSet>> =
        LinkedHashMap(config.l2MaxSize + 1, 0.75f, true)

    private val mutex = Mutex()

    private val l1Hits = AtomicLong(0)
    private val l1Misses = AtomicLong(0)
    private val l2Hits = AtomicLong(0)
    private val l2Misses = AtomicLong(0)

    private val writeCount = AtomicLong(0)

    suspend fun get(key: String): SearchResultSet? {
        return mutex.withLock {
            val l1Result = getFromL1(key)
            if (l1Result != null) {
                l1Hits.incrementAndGet()
                DebugLog.d("MultiLevelCacheManager: L1 HIT key=${key.take(16)}...")
                return@withLock l1Result.data
            }

            val l2Result = getFromL2(key)
            if (l2Result != null) {
                l2Hits.incrementAndGet()
                DebugLog.d("MultiLevelCacheManager: L2 HIT key=${key.take(16)}..., promoting to L1")
                putToL1(key, CacheEntry(
                    data = l2Result.data,
                    timestamp = System.currentTimeMillis(),
                    ttlMs = config.l1TtlMs,
                    accessCount = l2Result.accessCount + 1,
                    lastAccessTime = System.currentTimeMillis()
                ))
                return@withLock l2Result.data
            }

            l1Misses.incrementAndGet()
            l2Misses.incrementAndGet()
            DebugLog.d("MultiLevelCacheManager: MISS key=${key.take(16)}...")
            null
        }
    }

    suspend fun put(key: String, resultSet: SearchResultSet) {
        mutex.withLock {
            val now = System.currentTimeMillis()

            evictL2IfNeeded()

            putToL1(key, CacheEntry(resultSet, now, config.l1TtlMs))
            putToL2(key, CacheEntry(resultSet, now, config.l2TtlMs))

            writeCount.incrementAndGet()
            if (writeCount.get() % 100 == 0L) {
                cleanExpiredEntries()
            }

            DebugLog.d("MultiLevelCacheManager: PUT key=${key.take(16)}..., L1=${l1Cache.size()}, L2=${l2Cache.size}")
        }
    }

    suspend fun invalidate(key: String) {
        mutex.withLock {
            l1Cache.remove(key)
            l2Cache.remove(key)
            DebugLog.i("MultiLevelCacheManager: INVALIDATE key=${key.take(16)}...")
        }
    }

    suspend fun clear() {
        mutex.withLock {
            l1Cache.evictAll()
            l2Cache.clear()
            l1Hits.set(0L)
            l1Misses.set(0L)
            l2Hits.set(0L)
            l2Misses.set(0L)
            writeCount.set(0L)
            DebugLog.i("MultiLevelCacheManager: CLEARED all caches and stats reset")
        }
    }

    fun getStats(): Map<String, Any> {
        // 计数器为 AtomicLong，无锁快照，避免在 mutex 外读到跨 epoch 的不一致组合
        val l1h = l1Hits.get()
        val l1m = l1Misses.get()
        val l2h = l2Hits.get()
        val l2m = l2Misses.get()
        return mapOf(
            "l1_size" to l1Cache.size(),
            "l1_maxSize" to l1Cache.maxSize(),
            "l1_hits" to l1h,
            "l1_misses" to l1m,
            "l1_hitRate" to hitRate(l1h, l1m),
            "l2_size" to l2Cache.size,
            "l2_maxSize" to config.l2MaxSize,
            "l2_hits" to l2h,
            "l2_misses" to l2m,
            "l2_hitRate" to hitRate(l2h, l2m),
            "total_hitRate" to hitRate(l1h + l2h, l1m + l2m)
        )
    }

    private fun hitRate(hits: Long, misses: Long): Double {
        val total = hits + misses
        return if (total == 0L) 0.0 else hits.toDouble() / total.toDouble()
    }

    private suspend fun getFromL1(key: String): CacheEntry<SearchResultSet>? {
        val entry = l1Cache.get(key) ?: return null
        entry.accessCount++
        entry.lastAccessTime = System.currentTimeMillis()
        return if (entry.isExpired) {
            l1Cache.remove(key)
            DebugLog.d("MultiLevelCacheManager: L1 expired key=${key.take(16)}...")
            null
        } else {
            entry
        }
    }

    private suspend fun getFromL2(key: String): CacheEntry<SearchResultSet>? {
        val entry = l2Cache[key] ?: return null
        entry.accessCount++
        entry.lastAccessTime = System.currentTimeMillis()
        return if (entry.isExpired) {
            l2Cache.remove(key)
            DebugLog.d("MultiLevelCacheManager: L2 expired key=${key.take(16)}...")
            null
        } else {
            entry
        }
    }

    private suspend fun putToL1(key: String, entry: CacheEntry<SearchResultSet>) {
        l1Cache.put(key, entry)
    }

    private suspend fun putToL2(key: String, entry: CacheEntry<SearchResultSet>) {
        l2Cache[key] = entry
    }

    private suspend fun evictL2IfNeeded() {
        // 访问顺序 LinkedHashMap 的首个 entry 即最久未访问者；满容时淘汰一个即可，O(1)
        if (l2Cache.size < config.l2MaxSize) return
        val eldest = l2Cache.keys.iterator().next()
        l2Cache.remove(eldest)
        DebugLog.d("MultiLevelCacheManager: L2 evicted key=${eldest.take(16)}...")
    }

    private suspend fun cleanExpiredEntries() {
        var l1Cleaned = 0
        val l1Snapshot = l1Cache.snapshot()
        val l1ExpiredKeys = l1Snapshot.keys.filter { l1Snapshot[it]?.isExpired == true }
        for (key in l1ExpiredKeys) {
            l1Cache.remove(key)
            l1Cleaned++
        }

        var l2Cleaned = 0
        val l2ExpiredKeys = l2Cache.keys.filter { l2Cache[it]?.isExpired == true }
        for (key in l2ExpiredKeys) {
            l2Cache.remove(key)
            l2Cleaned++
        }

        if (l1Cleaned > 0 || l2Cleaned > 0) {
            DebugLog.i("MultiLevelCacheManager: cleaned expired entries L1=$l1Cleaned L2=$l2Cleaned")
        }
    }
}
