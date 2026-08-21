package top.hsyscn.opedrgent.network

import top.hsyscn.opedrgent.utils.DebugLog
import java.security.MessageDigest
import java.util.Locale

object CacheKeyGenerator {

    fun generate(
        query: String,
        providerOrder: String = "baidu,bing,ddg",
        language: String = "auto",
        timeRange: String? = null,
        region: String? = null,
        timeBucketMinutes: Int = 5
    ): String {
        val normalized = normalizeQuery(query)
        val timeBucket = generateTimeBucket(timeBucketMinutes)
        val configHash = hashConfig(providerOrder, language, timeRange, region)

        val rawKey = "$normalized|$timeBucket|$configHash"
        val finalKey = sha256(rawKey)

        DebugLog.d("CacheKeyGenerator", "generate key for query=$query => $finalKey")
        return finalKey
    }

    private fun normalizeQuery(query: String): String {
        var result = query.trim()
            .replace(Regex("\\s+"), " ")
            .lowercase(Locale.US)

        result = Regex("[^a-z0-9\\u4e00-\\u9fff\\s.,!?;:'\"()\\[\\]{}]").replace(result, "")

        if (result.length > 500) {
            result = result.take(500)
            DebugLog.w("CacheKeyGenerator", "query truncated to 500 chars")
        }

        return result.trim()
    }

    private fun generateTimeBucket(minutes: Int): String {
        val now = System.currentTimeMillis() / 1000L
        return (now / (minutes * 60L)).toString()
    }

    private fun hashConfig(
        providerOrder: String,
        language: String,
        timeRange: String?,
        region: String?
    ): String {
        val raw = "${providerOrder}|${language}|${timeRange ?: ""}|${region ?: ""}"
        return sha256(raw)
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
