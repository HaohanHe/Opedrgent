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
        timeBucketMinutes: Int = 5,
        limit: Int = 5
    ): String {
        val normalized = normalizeQuery(query)
        val timeBucket = generateTimeBucket(timeBucketMinutes)
        // limit 参与 configHash：不同条数的请求必须得到不同缓存键（U30-03）
        val configHash = hashConfig(providerOrder, language, timeRange, region, limit)

        val rawKey = "$normalized|$timeBucket|$configHash"
        val finalKey = sha256(rawKey)

        DebugLog.d("CacheKeyGenerator", "generate key for query=$query => $finalKey")
        return finalKey
    }

    private fun normalizeQuery(query: String): String {
        var result = query.trim()
            .replace(Regex("\\s+"), " ")
            .lowercase(Locale.US)

        // 保留全部 Unicode 字母/数字（\\p{L}\\p{N}）与常见空白标点；仅折叠空白与大小写。
        // 旧白名单只保留 a-z0-9 与中文，会把西里尔/阿拉伯/希腊等整段删除成空串，
        // 导致不同语种查询归一化为同一空串而缓存键碰撞（U32-03）。
        result = Regex("[^\\p{L}\\p{N}\\s.,!?;:'\"()\\[\\]{}]").replace(result, "")

        if (result.length > 500) {
            result = result.take(500)
            DebugLog.w("CacheKeyGenerator", "query truncated to 500 chars")
        }

        return result.trim()
    }

    private fun generateTimeBucket(minutes: Int): String {
        // 守卫：<=0 会除零/产生负桶，>1440 失去 TTL 意义；钳制到 [1, 1440]（U32-02）
        val clamped = minutes.coerceIn(1, 1440)
        val now = System.currentTimeMillis() / 1000L
        return (now / (clamped * 60L)).toString()
    }

    private fun hashConfig(
        providerOrder: String,
        language: String,
        timeRange: String?,
        region: String?,
        limit: Int
    ): String {
        val raw = "$providerOrder|$language|${timeRange ?: ""}|${region ?: ""}|$limit"
        return sha256(raw)
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
