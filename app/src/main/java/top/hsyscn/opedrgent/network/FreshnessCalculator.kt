package top.hsyscn.opedrgent.network

import top.hsyscn.opedrgent.utils.DebugLog
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

enum class ContentType(
    val halfLifeHours: Double,
    val label: String
) {
    NEWS(6.0, "新闻资讯"),
    SOCIAL(24.0, "社交媒体"),
    TECHNICAL(720.0, "技术文档"),
    REFERENCE(8760.0, "参考资料"),
    PRODUCT(4320.0, "产品信息"),
    GENERAL(720.0, "通用内容")
}

data class FreshnessScore(
    val contentType: ContentType,
    val estimatedAgeDays: Double,
    val rawScore: Double,
    val adjustedScore: Double,
    val confidence: Double
)

class FreshnessCalculator {

    companion object {
        val NEWS_DOMAIN_PATTERNS = listOf(
            Regex("^(www\\.)?(news|xinhua|people|cctv|thepaper|bbc|cnn|reuters)\\."),
            Regex(".*\\.news\\.com$"),
            Regex(".*\\.com\\.cn$")
        )

        val SOCIAL_DOMAIN_PATTERNS = listOf(
            Regex("^(www\\.)?(weibo|twitter|facebook|instagram|tiktok|douyin|xiaohongshu)\\."),
            Regex(".*\\.(weibo|twitter|fb|ig)\\.com$")
        )

        val TECHNICAL_URL_PATTERNS = listOf(
            Regex(".*/docs/.*"),
            Regex(".*/wiki/.*"),
            Regex(".*/(api|developer|dev)\\."),
            Regex(".*(github|stackoverflow|juejin|csdn|segmentfault).*")
        )

        val PRODUCT_URL_PATTERNS = listOf(
            Regex(".*/(product|item|shop|store|buy|price)/.*"),
            Regex(".*(amazon|taobao|jd|tmall|pinduoduo).*")
        )

        val DATE_PATTERNS = listOf(
            Regex("(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})"),
            Regex("(\\d{4})年(\\d{1,2})月(\\d{1,2})[日号]"),
            Regex("(?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:tember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)\\s+(\\d{1,2}),?\\s+(\\d{4})", RegexOption.IGNORE_CASE),
            Regex("(\\d+)\\s*(天|小时|分钟|秒)前"),
            Regex("昨天|今天")
        )

        private val ENGLISH_MONTH_MAP = mapOf(
            "jan" to 1, "january" to 1,
            "feb" to 2, "february" to 2,
            "mar" to 3, "march" to 3,
            "apr" to 4, "april" to 4,
            "may" to 5,
            "jun" to 6, "june" to 6,
            "jul" to 7, "july" to 7,
            "aug" to 8, "august" to 8,
            "sep" to 9, "september" to 9,
            "oct" to 10, "october" to 10,
            "nov" to 11, "november" to 11,
            "dec" to 12, "december" to 12
        )
    }

    private var currentQueryIntent: QueryIntent = QueryIntent.INFORMATIONAL

    fun initWithQuery(intent: QueryIntent = QueryIntent.INFORMATIONAL) {
        currentQueryIntent = intent
        DebugLog.d("FreshnessCalculator: query intent set to ${intent.name}")
    }

    fun calculate(
        url: String,
        snippet: String? = null,
        publishDate: Long? = null
    ): FreshnessScore {
        val contentType = detectContentType(url, snippet)
        val (ageDays, confidence) = estimateAge(url, snippet, publishDate)
        val ageHours = ageDays * 24.0
        val rawScore = exponentialDecay(ageHours, contentType.halfLifeHours)
        val adjustedScore = adjustForQueryType(rawScore, contentType)

        DebugLog.d("FreshnessCalculator: url=$url | type=${contentType.label} | age=${String.format("%.1f", ageDays)}d | raw=${String.format("%.3f", rawScore)} | adj=${String.format("%.3f", adjustedScore)}")

        return FreshnessScore(
            contentType = contentType,
            estimatedAgeDays = ageDays,
            rawScore = rawScore,
            adjustedScore = adjustedScore,
            confidence = confidence
        )
    }

    fun detectContentType(url: String, snippet: String? = null): ContentType {
        val host = extractHost(url)

        if (NEWS_DOMAIN_PATTERNS.any { it.matches(host) }) {
            return ContentType.NEWS
        }

        if (SOCIAL_DOMAIN_PATTERNS.any { it.matches(host) }) {
            return ContentType.SOCIAL
        }

        if (PRODUCT_URL_PATTERNS.any { it.containsMatchIn(url) }) {
            return ContentType.PRODUCT
        }

        if (TECHNICAL_URL_PATTERNS.any { it.containsMatchIn(url) }) {
            return ContentType.TECHNICAL
        }

        if (snippet != null && isReferenceContent(snippet)) {
            return ContentType.REFERENCE
        }

        return ContentType.GENERAL
    }

    fun estimateAge(
        url: String,
        snippet: String? = null,
        publishDate: Long? = null
    ): Pair<Double, Double> {
        if (publishDate != null) {
            val days = elapsedDays(publishDate)
            DebugLog.d("FreshnessCalculator: using publishDate, age=$days days")
            return Pair(days, 0.95)
        }

        val combinedText = buildString {
            append(snippet ?: "")
            append(" ")
            append(url)
        }

        val extractedDate = extractDateFromText(combinedText)
        if (extractedDate != null) {
            val days = elapsedDays(extractedDate)
            DebugLog.d("FreshnessCalculator: extracted date from text, age=$days days")
            return Pair(days, 0.7)
        }

        val relativeTime = parseRelativeTime(combinedText)
        if (relativeTime != null) {
            val days = elapsedDays(relativeTime)
            DebugLog.d("FreshnessCalculator: parsed relative time, age=$days days")
            return Pair(days, 0.6)
        }

        DebugLog.w("FreshnessCalculator: no date found, defaulting to 30 days")
        return Pair(30.0, 0.1)
    }

    private fun exponentialDecay(ageHours: Double, halfLifeHours: Double): Double {
        val lambda = Math.log(2.0) / halfLifeHours
        return Math.exp(-lambda * ageHours).coerceIn(0.0, 1.0)
    }

    private fun adjustForQueryType(rawScore: Double, contentType: ContentType): Double {
        return when (currentQueryIntent) {
            QueryIntent.NAVIGATIONAL -> (rawScore * 0.33).coerceIn(0.0, 1.0)
            QueryIntent.TRANSACTIONAL -> {
                val multiplier = when (contentType) {
                    ContentType.PRODUCT -> 1.33
                    ContentType.NEWS -> 1.2
                    else -> 1.0
                }
                (rawScore * multiplier).coerceIn(0.0, 1.0)
            }
            QueryIntent.INFORMATIONAL -> rawScore
        }
    }

    private fun extractDateFromText(text: String): Long? {
        for (pattern in DATE_PATTERNS) {
            val result = pattern.find(text) ?: continue
            val groups = result.groupValues

            try {
                when {
                    groups.size >= 4 && groups[1].length == 4 && groups[1].toIntOrNull() != null -> {
                        val year = groups[1].toInt()
                        val month = groups[2].toInt()
                        val day = groups[3].toInt()
                        return toTimestamp(year, month, day)
                    }
                }
            } catch (_: Exception) {
                continue
            }
        }

        val englishMonthPattern = Regex(
            "(Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:tember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)\\s+(\\d{1,2}),?\\s+(\\d{4})",
            RegexOption.IGNORE_CASE
        )
        val englishResult = englishMonthPattern.find(text)
        if (englishResult != null) {
            try {
                val monthName = englishResult.groupValues[1].lowercase(Locale.getDefault())
                val day = englishResult.groupValues[2].toInt()
                val year = englishResult.groupValues[3].toInt()
                val month = ENGLISH_MONTH_MAP[monthName] ?: return null
                return toTimestamp(year, month, day)
            } catch (_: Exception) {
            }
        }

        return null
    }

    private fun parseRelativeTime(text: String): Long? {
        val relativePattern = Regex("(\\d+)\\s*(天|小时|分钟|秒)前")
        val match = relativePattern.find(text) ?: return null

        return try {
            val amount = match.groupValues[1].toLong()
            val unit = match.groupValues[2]
            val now = System.currentTimeMillis()

            when (unit) {
                "秒" -> now - amount * 1000L
                "分钟" -> now - amount * 60_000L
                "小时" -> now - amount * 3_600_000L
                "天" -> now - amount * 86_400_000L
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isReferenceContent(snippet: String): Boolean {
        val refKeywords = listOf("维基百科", "wikipedia", "百科", "baike", "encyclopedia", "词条")
        return refKeywords.any { snippet.lowercase(Locale.getDefault()).contains(it.lowercase(Locale.getDefault())) }
    }

    private fun extractHost(url: String): String {
        return try {
            val withoutProtocol = url.replace(Regex("^https?://"), "")
            withoutProtocol.split("/").firstOrNull() ?: url
        } catch (_: Exception) {
            url
        }
    }

    private fun toTimestamp(year: Int, month: Int, day: Int): Long {
        return try {
            val cal = Calendar.getInstance()
            cal.set(Calendar.YEAR, year)
            cal.set(Calendar.MONTH, month - 1)
            cal.set(Calendar.DAY_OF_MONTH, day)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            cal.timeInMillis
        } catch (_: Exception) {
            0L
        }
    }

    private fun elapsedDays(timestamp: Long): Double {
        if (timestamp <= 0) return 30.0
        val diffMs = System.currentTimeMillis() - timestamp
        if (diffMs <= 0) return 0.0
        return diffMs / 86_400_000.0
    }
}
