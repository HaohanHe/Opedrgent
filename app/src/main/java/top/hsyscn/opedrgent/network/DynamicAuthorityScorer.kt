package top.hsyscn.opedrgent.network

import top.hsyscn.opedrgent.utils.DebugLog
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

data class AuthorityScore(
    val baseAuthority: Double,
    val contentQualityBonus: Double,
    val negativePenalty: Double,
    val finalScore: Double
)

data class DomainInfo(
    val domain: String,
    val tld: String,
    val isKnownAuthority: Boolean,
    val authorityCategory: AuthorityCategory?
)

enum class AuthorityCategory {
    GOVERNMENT,
    EDUCATION,
    TECH_COMMUNITY,
    NEWS_MEDIA,
    ACADEMIC,
    ENCYCLOPEDIA,
    COMMERCIAL_MEDIA,
    GENERAL
}

class DynamicAuthorityScorer {

    companion object {
        private const val TAG = "DynamicAuthorityScorer"

        val HIGH_AUTHORITY_DOMAINS = mapOf(
            "gov.cn" to AuthorityCategory.GOVERNMENT,
            "gov" to AuthorityCategory.GOVERNMENT,
            "edu.cn" to AuthorityCategory.EDUCATION,
            "edu" to AuthorityCategory.EDUCATION,
            "github.com" to AuthorityCategory.TECH_COMMUNITY,
            "stackoverflow.com" to AuthorityCategory.TECH_COMMUNITY,
            "juejin.cn" to AuthorityCategory.TECH_COMMUNITY,
            "csdn.net" to AuthorityCategory.TECH_COMMUNITY,
            "zhihu.com" to AuthorityCategory.TECH_COMMUNITY,
            "segmentfault.com" to AuthorityCategory.TECH_COMMUNITY,
            "jianshu.com" to AuthorityCategory.TECH_COMMUNITY,
            "wikipedia.org" to AuthorityCategory.ENCYCLOPEDIA,
            "baike.baidu.com" to AuthorityCategory.ENCYCLOPEDIA,
            "xinhuanet.com" to AuthorityCategory.NEWS_MEDIA,
            "people.com.cn" to AuthorityCategory.NEWS_MEDIA,
            "cctv.com" to AuthorityCategory.NEWS_MEDIA,
            "thepaper.cn" to AuthorityCategory.NEWS_MEDIA,
            "bbc.com" to AuthorityCategory.NEWS_MEDIA,
            "cnn.com" to AuthorityCategory.NEWS_MEDIA,
            "reuters.com" to AuthorityCategory.NEWS_MEDIA,
            "ieee.org" to AuthorityCategory.ACADEMIC,
            "acm.org" to AuthorityCategory.ACADEMIC,
            "springer.com" to AuthorityCategory.ACADEMIC,
            "sciencedirect.com" to AuthorityCategory.ACADEMIC,
            "36kr.com" to AuthorityCategory.COMMERCIAL_MEDIA,
            "ifanr.com" to AuthorityCategory.COMMERCIAL_MEDIA,
            "huxiu.com" to AuthorityCategory.COMMERCIAL_MEDIA,
            "geekpark.net" to AuthorityCategory.COMMERCIAL_MEDIA
        )

        val BLACKLIST_DOMAINS = setOf(
            "ad.com", "click.com", "spam.com"
        )

        val AD_KEYWORDS = listOf(
            "广告", "推广", "赞助", "AD", "sponsored",
            "点击这里", "免费领取", "立即下载", "惊喜优惠"
        )

        val CLICKBAIT_PATTERNS = listOf(
            Regex("(!\\s*){2,}"),
            Regex("震惊|吓人|不敢相信|居然|竟然|必须看"),
            Regex("\\d{1,2}(种|个|款|招|法|秘诀|技巧)"),
            Regex("(你|你绝对|99%的人)无法?(想象|相信|错过)")
        )

        val SEO_STUFFING_INDICATORS = listOf(
            Regex("(\\w{2,})\\s+\\1{2,}"),
            Regex("[a-zA-Z]{30,}")
        )
    }

    private val authorityCache = ConcurrentHashMap<String, AuthorityScore>()
    private val cacheTtlMs = 3600_000L
    private val cacheTimestamps = ConcurrentHashMap<String, Long>()

    fun calculate(url: String, title: String, snippet: String? = null): AuthorityScore {
        val cacheKey = buildCacheKey(url, title, snippet)

        val cached = getCachedResult(cacheKey)
        if (cached != null) {
            DebugLog.d(TAG, "Cache hit for url=$url")
            return cached
        }

        val domainInfo = extractDomainInfo(url)
        val baseAuthority = calculateBaseAuthority(domainInfo)
        val contentQualityBonus = calculateContentQualityBonus(title, snippet)
        val negativePenalty = calculateNegativePenalty(url, title, snippet)

        var finalScore = baseAuthority + contentQualityBonus + negativePenalty
        finalScore = finalScore.coerceIn(0.0, 1.0)

        val score = AuthorityScore(
            baseAuthority = baseAuthority,
            contentQualityBonus = contentQualityBonus,
            negativePenalty = negativePenalty,
            finalScore = finalScore
        )

        authorityCache[cacheKey] = score
        cacheTimestamps[cacheKey] = System.currentTimeMillis()

        DebugLog.d(TAG, "Calculated authority for url=$url domain=${domainInfo.domain} " +
                "base=$baseAuthority bonus=$contentQualityBonus penalty=$negativePenalty final=$finalScore")

        return score
    }

    fun extractDomainInfo(url: String): DomainInfo {
        val host = try {
            URI(url).host.lowercase()
        } catch (e: Exception) {
            DebugLog.w(TAG, "Failed to parse URL host: $url: ${e.message}")
            return DomainInfo("", "", false, null)
        }

        if (host.isEmpty()) {
            return DomainInfo("", "", false, null)
        }

        val tld = extractTld(host)
        val category = resolveAuthorityCategory(host)
        val isKnownAuthority = category != null && category != AuthorityCategory.GENERAL

        return DomainInfo(domain = host, tld = tld, isKnownAuthority = isKnownAuthority, authorityCategory = category)
    }

    fun clearExpiredCache() {
        val now = System.currentTimeMillis()
        val expiredKeys = cacheTimestamps.filter { now - it.value > cacheTtlMs }.keys

        expiredKeys.forEach { key ->
            authorityCache.remove(key)
            cacheTimestamps.remove(key)
        }

        if (expiredKeys.isNotEmpty()) {
            DebugLog.d(TAG, "Cleared ${expiredKeys.size} expired cache entries")
        }
    }

    private fun calculateBaseAuthority(domainInfo: DomainInfo): Double {
        val category = domainInfo.authorityCategory ?: AuthorityCategory.GENERAL

        var baseScore = when (category) {
            AuthorityCategory.GOVERNMENT -> 0.95
            AuthorityCategory.EDUCATION -> 0.90
            AuthorityCategory.TECH_COMMUNITY -> 0.85
            AuthorityCategory.ENCYCLOPEDIA -> 0.92
            AuthorityCategory.ACADEMIC -> 0.88
            AuthorityCategory.NEWS_MEDIA -> 0.80
            AuthorityCategory.COMMERCIAL_MEDIA -> 0.70
            AuthorityCategory.GENERAL -> 0.50
        }

        if (domainInfo.isKnownAuthority) {
            baseScore += 0.03
        }

        return baseScore.coerceAtMost(1.0)
    }

    private fun calculateContentQualityBonus(title: String, snippet: String?): Double {
        var bonus = 0.0

        when {
            title.length in 10..70 -> bonus += 0.05
            title.length < 5 || title.length > 100 -> bonus -= 0.05
        }

        if (!snippet.isNullOrEmpty() && snippet.length > 50) {
            bonus += 0.05
        }

        if (!snippet.isNullOrEmpty() && snippet.trimEnd().endsWith('。') || snippet?.trimEnd()?.endsWith('.') == true) {
            bonus += 0.03
        }

        return bonus.coerceIn(-0.1, 0.15)
    }

    private fun calculateNegativePenalty(url: String, title: String, snippet: String?): Double {
        var penalty = 0.0

        val domainInfo = extractDomainInfo(url)
        if (isInBlacklist(domainInfo.domain)) {
            penalty -= 0.5
        }

        if (detectAdContent(title, snippet)) {
            penalty -= 0.2
        }

        if (isClickbaitTitle(title)) {
            penalty -= 0.15
        }

        if (hasSeoStuffing(snippet)) {
            penalty -= 0.1
        }

        return penalty.coerceAtLeast(-0.5)
    }

    private fun isInBlacklist(domain: String): Boolean {
        return BLACKLIST_DOMAINS.any { blacklisted ->
            domain == blacklisted || domain.endsWith(".$blacklisted")
        }
    }

    private fun detectAdContent(title: String, snippet: String?): Boolean {
        val combined = "$title ${snippet.orEmpty()}".lowercase()
        return AD_KEYWORDS.any { keyword -> combined.contains(keyword, ignoreCase = true) }
    }

    private fun isClickbaitTitle(title: String): Boolean {
        return CLICKBAIT_PATTERNS.any { pattern -> pattern.containsMatchIn(title) }
    }

    private fun hasSeoStuffing(snippet: String?): Boolean {
        if (snippet.isNullOrEmpty()) return false
        return SEO_STUFFING_INDICATORS.any { pattern -> pattern.containsMatchIn(snippet) }
    }

    private fun extractTld(host: String): String {
        val parts = host.split(".")
        return if (parts.size >= 2) {
            parts.takeLast(2).joinToString(".")
        } else {
            host
        }
    }

    private fun resolveAuthorityCategory(host: String): AuthorityCategory? {
        for ((domain, category) in HIGH_AUTHORITY_DOMAINS) {
            when {
                host == domain -> return category
                host.endsWith(".$domain") -> return category
            }
        }
        return AuthorityCategory.GENERAL
    }

    private fun buildCacheKey(url: String, title: String, snippet: String?): String {
        return "${url}::${title.hashCode()}::${snippet?.hashCode() ?: 0}"
    }

    private fun getCachedResult(cacheKey: String): AuthorityScore? {
        val timestamp = cacheTimestamps[cacheKey] ?: return null
        if (System.currentTimeMillis() - timestamp > cacheTtlMs) {
            authorityCache.remove(cacheKey)
            cacheTimestamps.remove(cacheKey)
            return null
        }
        return authorityCache[cacheKey]
    }
}
