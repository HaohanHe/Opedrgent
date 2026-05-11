package top.hsyscn.opedrgent.mcp.cache

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class CachedPrompt(
    val id: String,
    val promptHash: String,
    val systemPrompt: String,
    val userMessages: List<String>,
    val response: String,
    val model: String,
    val timestamp: Long = System.currentTimeMillis(),
    val hitCount: Int = 1,
    val lastUsed: Long = System.currentTimeMillis(),
    val tokensEstimate: Int = 0,
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
data class CacheConfig(
    val enabled: Boolean = true,
    val maxSizeMb: Int = 100,
    val ttlHours: Int = 24,
    val minSimilarity: Double = 0.85,
    val maxTokensPerEntry: Int = 8000,
    val enableSemanticCache: Boolean = false,
)

class PromptCache(
    private val config: CacheConfig = CacheConfig(),
    private val cacheDir: File? = null,
) {

    private val cache = ConcurrentHashMap<String, CachedPrompt>()
    private var currentSizeBytes = 0L
    private val maxBytes = config.maxSizeMb * 1024 * 1024

    init {
        if (cacheDir != null) {
            loadFromDisk()
        }
    }

    fun get(promptKey: String): CachedPrompt? {
        if (!config.enabled) return null

        val hash = hashString(promptKey)
        val cached = cache[hash]

        if (cached != null) {
            val isExpired = System.currentTimeMillis() - cached.lastUsed > config.ttlHours * 3600 * 1000L

            if (!isExpired) {
                val updated = cached.copy(
                    hitCount = cached.hitCount + 1,
                    lastUsed = System.currentTimeMillis(),
                )
                cache[hash] = updated

                DebugLog.i("PromptCache: CACHE HIT for key $promptKey (hits: ${updated.hitCount})")

                return updated
            } else {
                remove(hash)
                DebugLog.i("PromptCache: expired entry removed for $promptKey")
            }
        }

        DebugLog.d("PromptCache: CACHE MISS for key $promptKey")
        return null
    }

    fun put(
        promptKey: String,
        systemPrompt: String,
        userMessages: List<String>,
        response: String,
        model: String,
        tokensEstimate: Int = 0,
    ): Boolean {
        if (!config.enabled) return false

        if (tokensEstimate > config.maxTokensPerEntry) {
            DebugLog.w("PromptCache: entry too large ($tokensEstimate tokens), skipping")
            return false
        }

        val hash = hashString(promptKey)
        val entrySize = estimateSize(systemPrompt, userMessages, response)

        if (entrySize > maxBytes) {
            DebugLog.w("PromptCache: single entry too large, skipping")
            return false
        }

        ensureCapacity(entrySize)

        val cached = CachedPrompt(
            id = hash,
            promptHash = hash,
            systemPrompt = systemPrompt,
            userMessages = userMessages,
            response = response,
            model = model,
            timestamp = System.currentTimeMillis(),
            hitCount = 1,
            lastUsed = System.currentTimeMillis(),
            tokensEstimate = tokensEstimate,
        )

        cache[hash] = cached
        currentSizeBytes += entrySize

        DebugLog.i("PromptCache: CACHED entry for $promptKey (${cache.size} entries, ${currentSizeBytes / 1024}KB)")

        if (cacheDir != null) {
            saveToDiskAsync(hash, cached)
        }

        return true
    }

    fun findSimilar(promptKey: String, threshold: Double = config.minSimilarity): CachedPrompt? {
        if (!config.enabled || !config.enableSemanticCache) return null

        val targetWords = extractWords(promptKey).toSet()

        if (targetWords.isEmpty()) return null

        var bestMatch: CachedPrompt? = null
        var bestScore = 0.0

        for ((_, entry) in cache) {
            val combinedText = entry.systemPrompt + " " + entry.userMessages.joinToString(" ")
            val entryWords = extractWords(combinedText).toSet()

            if (entryWords.isEmpty()) continue

            val intersection = targetWords.intersect(entryWords)
            val union = targetWords.union(entryWords)
            val similarity = intersection.size.toDouble() / union.size.toDouble()

            if (similarity > bestScore && similarity >= threshold) {
                bestScore = similarity
                bestMatch = entry
            }
        }

        if (bestMatch != null) {
            val updatedMatch = bestMatch!!.copy(
                hitCount = bestMatch!!.hitCount + 1,
                lastUsed = System.currentTimeMillis(),
            )
            cache[updatedMatch.id] = updatedMatch
            DebugLog.i("PromptCache: SEMANTIC HIT (score: ${"%.2f".format(bestScore)})")
            return updatedMatch
        }

        return null
    }

    fun invalidate(pattern: String? = null): Int {
        var count = 0

        if (pattern != null) {
            val regex = Regex(pattern, RegexOption.IGNORE_CASE)
            val keysToRemove = cache.keys.filter { regex.containsMatchIn(it) }
            
            for (key in keysToRemove) {
                remove(key)
                count++
            }
        } else {
            count = cache.size
            clear()
        }

        DebugLog.i("PromptCache: invalidated $count entries")
        return count
    }

    fun clear() {
        cache.clear()
        currentSizeBytes = 0L
        
        if (cacheDir != null) {
            cacheDir.listFiles()?.forEach { it.delete() }
        }
    }

    fun getStats(): CacheStats {
        val totalHits = cache.values.sumOf { it.hitCount }
        val avgHits = if (cache.isNotEmpty()) totalHits.toDouble() / cache.size else 0.0

        return CacheStats(
            entryCount = cache.size,
            totalSizeBytes = currentSizeBytes,
            totalSizeMb = currentSizeBytes / (1024.0 * 1024.0),
            totalHits = totalHits,
            avgHitRate = avgHits,
            oldestEntry = cache.values.minByOrNull { it.timestamp }?.timestamp,
            newestEntry = cache.values.maxByOrNull { it.timestamp }?.timestamp,
        )
    }

    @Serializable
    data class CacheStats(
        val entryCount: Int,
        val totalSizeBytes: Long,
        val totalSizeMb: Double,
        val totalHits: Int,
        val avgHitRate: Double,
        val oldestEntry: Long?,
        val newestEntry: Long?,
    )

    private fun remove(key: String) {
        cache.remove(key)?.let { entry ->
            currentSizeBytes -= estimateSize(entry.systemPrompt, entry.userMessages, entry.response)
        }
    }

    private fun ensureCapacity(requiredBytes: Int) {
        while (currentSizeBytes + requiredBytes > maxBytes && cache.isNotEmpty()) {
            val lruKey = cache.entries.minByOrNull { it.value.lastUsed }?.key
            if (lruKey != null) {
                remove(lruKey)
                DebugLog.d("PromptCache: evicted LRU entry to make space")
            }
        }
    }

    private fun estimateSize(systemPrompt: String, userMessages: List<String>, response: String): Int {
        return (systemPrompt.length + userMessages.sumOf { it.length } + response.length) * 2
    }

    private fun hashString(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun extractWords(text: String): List<String> {
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 }
            .distinct()
    }

    private fun loadFromDisk() {
        if (cacheDir == null || !cacheDir.exists()) return

        try {
            val files = cacheDir.listFiles()?.sortedByDescending { it.lastModified() }?.take(1000)
                ?: return

            for (file in files) {
                try {
                    val json = file.readText()
                    val cached = kotlinx.serialization.json.Json {
                        ignoreUnknownKeys = true
                    }.decodeFromString(CachedPrompt.serializer(), json)

                    cache[cached.id] = cached
                    currentSizeBytes += estimateSize(cached.systemPrompt, cached.userMessages, cached.response)
                } catch (e: Exception) {
                    DebugLog.w("PromptCache: failed to load ${file.name}")
                }
            }

            DebugLog.i("PromptCache: loaded ${cache.size} entries from disk")
        } catch (e: Exception) {
            DebugLog.e("PromptCache.loadFromDisk: ${e.message}")
        }
    }

    private fun saveToDiskAsync(key: String, cached: CachedPrompt) {
        Thread {
            try {
                if (cacheDir == null) return@Thread

                cacheDir.mkdirs()

                val json = kotlinx.serialization.json.Json {
                    encodeDefaults = true
                    prettyPrint = false
                }.encodeToString(CachedPrompt.serializer(), cached)

                val file = File(cacheDir, "$key.json")
                file.writeText(json)
            } catch (e: Exception) {
                DebugLog.e("PromptCache.saveToDisk: ${e.message}")
            }
        }.start()
    }

    companion object {
        private var instance: PromptCache? = null

        fun getInstance(config: CacheConfig = CacheConfig()): PromptCache {
            if (instance == null) {
                instance = PromptCache(config)
            }
            return instance!!
        }

        fun createGlobal(config: CacheConfig = CacheConfig(), cacheDir: File? = null): PromptCache {
            val cache = PromptCache(config, cacheDir)
            instance = cache
            return cache
        }
    }
}
