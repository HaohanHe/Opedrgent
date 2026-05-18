package top.hsyscn.opedrgent.network

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import top.hsyscn.opedrgent.utils.DebugLog
import java.util.concurrent.ConcurrentHashMap

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
    val l1MaxSize: Int = 500,
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
    private val l1Cache: LinkedHashMap<String, CacheEntry<SearchResultSet>> =
        LinkedHashMap(16, 0.75f, true)

    private val l2Cache: ConcurrentHashMap<String, CacheEntry<SearchResultSet>> =
        ConcurrentHashMap()

    private val mutex = Mutex()

    var l1Hits = 0L; private set
    var l1Misses = 0L; private set
    var l2Hits = 0L; private set
    var l2Misses = 0L; private set

    private var writeCount = 0L

    suspend fun get(key: String): SearchResultSet? {
        return mutex.withLock {
            val l1Result = getFromL1(key)
            if (l1Result != null) {
                l1Hits++
                DebugLog.d("MultiLevelCacheManager: L1 HIT key=${key.take(16)}...")
                return@withLock l1Result.data
            }

            val l2Result = getFromL2(key)
            if (l2Result != null) {
                l2Hits++
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

            l1Misses++
            l2Misses++
            DebugLog.d("MultiLevelCacheManager: MISS key=${key.take(16)}...")
            null
        }
    }

    suspend fun put(key: String, resultSet: SearchResultSet) {
        mutex.withLock {
            val now = System.currentTimeMillis()

            evictL1IfNeeded()
            evictL2IfNeeded()

            putToL1(key, CacheEntry(resultSet, now, config.l1TtlMs))
            putToL2(key, CacheEntry(resultSet, now, config.l2TtlMs))

            writeCount++
            if (writeCount % 100 == 0L) {
                cleanExpiredEntries()
            }

            DebugLog.d("MultiLevelCacheManager: PUT key=${key.take(16)}..., L1=${l1Cache.size}, L2=${l2Cache.size}")
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
            l1Cache.clear()
            l2Cache.clear()
            l1Hits = 0L
            l1Misses = 0L
            l2Hits = 0L
            l2Misses = 0L
            writeCount = 0L
            DebugLog.i("MultiLevelCacheManager: CLEARED all caches and stats reset")
        }
    }

    fun getStats(): Map<String, Any> {
        return mapOf(
            "l1_size" to l1Cache.size,
            "l1_maxSize" to config.l1MaxSize,
            "l1_hits" to l1Hits,
            "l1_misses" to l1Misses,
            "l1_hitRate" to hitRate(l1Hits, l1Misses),
            "l2_size" to l2Cache.size,
            "l2_maxSize" to config.l2MaxSize,
            "l2_hits" to l2Hits,
            "l2_misses" to l2Misses,
            "l2_hitRate" to hitRate(l2Hits, l2Misses),
            "total_hitRate" to hitRate(l1Hits + l2Hits, l1Misses + l2Misses)
        )
    }

    private fun hitRate(hits: Long, misses: Long): Double {
        val total = hits + misses
        return if (total == 0L) 0.0 else hits.toDouble() / total.toDouble()
    }

    private suspend fun getFromL1(key: String): CacheEntry<SearchResultSet>? {
        val entry = l1Cache[key] ?: return null
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
        l1Cache[key] = entry
    }

    private suspend fun putToL2(key: String, entry: CacheEntry<SearchResultSet>) {
        l2Cache[key] = entry
    }

    private suspend fun evictL1IfNeeded() {
        while (l1Cache.size >= config.l1MaxSize) {
            val oldestKey = l1Cache.keys.iterator().next()
            l1Cache.remove(oldestKey)
            DebugLog.d("MultiLevelCacheManager: L1 evicted key=${oldestKey.take(16)}...")
        }
    }

    private suspend fun evictL2IfNeeded() {
        if (l2Cache.size < config.l2MaxSize) return
        val sortedEntries = l2Cache.entries.sortedBy { it.value.lastAccessTime }
        val removeCount = l2Cache.size - config.l2MaxSize + 1
        sortedEntries.take(removeCount).forEach { (k, _) ->
            l2Cache.remove(k)
            DebugLog.d("MultiLevelCacheManager: L2 evicted key=${k.take(16)}...")
        }
    }

    private suspend fun cleanExpiredEntries() {
        var l1Cleaned = 0
        val l1ExpiredKeys = l1Cache.keys.filter { l1Cache[it]?.isExpired == true }
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
