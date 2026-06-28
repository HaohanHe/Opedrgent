package top.hsyscn.opedrgent.network

/**
 * Search subsystem data models, configuration, and tuning constants.
 *
 * Extracted from WebSearcher.kt so the facade can focus on orchestration
 * while callers continue to reference the same top-level types.
 */

data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String?,
    val sourceEngines: Set<String> = emptySet(),
    var score: Double = 0.0,
    val rawContent: String? = null,  // Tavily Extract 返回的完整内容
)

/** Tavily Search API 响应 */
data class TavilySearchResponse(
    val results: List<SearchResult>,
    val answer: String?,  // Tavily 生成的 LLM 摘要答案
)

/** Tavily Extract API 响应 */
data class TavilyExtractResult(
    val url: String,
    val rawContent: String,
)

data class SearchConfig(
    // ★ Bugfix: 默认引擎顺序改为 Bing 优先（国内唯一稳定可用的免费引擎）
    // 百度放第二（易触发 CAPTCHA 但中文结果质量高），DDG 第三，Jina 最后（需 API Key）
    val providerOrder: String = "bing,baidu,ddg,jina",
    val searxngUrl: String? = null,
    val jinaApiKey: String? = null,
    val braveApiKey: String? = null,
    val tavilyApiKey: String? = null,
)

data class EngineConfig(
    val id: String,
    val enabled: Boolean = true,
    val weight: Double = 1.0,
    val timeoutSec: Long = 8L,
    val maxResults: Int = 10
)

val DEFAULT_ENGINE_CONFIGS: Map<String, EngineConfig> = mapOf(
    "baidu" to EngineConfig("baidu", weight = 1.2, timeoutSec = 6),
    "bing" to EngineConfig("bing", weight = 1.3, timeoutSec = 8),
    "ddg" to EngineConfig("ddg", weight = 1.0, timeoutSec = 5),
    "sogou" to EngineConfig("sogou", weight = 0.9, timeoutSec = 6),
    "360" to EngineConfig("360", weight = 0.8, timeoutSec = 5),
    "yandex" to EngineConfig("yandex", weight = 0.7, timeoutSec = 8),
    "jina" to EngineConfig("jina", weight = 1.1, timeoutSec = 8),
    "brave" to EngineConfig("brave", weight = 1.1, timeoutSec = 10),
    "tavily" to EngineConfig("tavily", weight = 1.0, timeoutSec = 10),
    "searxng" to EngineConfig("searxng", weight = 1.4, timeoutSec = 12),
)

var SEARXNG_BASE_URL: String = ""
    set(value) {
        field = value.trimEnd('/')
    }

data class JinaResult(
    val title: String,
    val url: String,
    val text: String,
)

/**
 * Hardcoded tuning constants used throughout the search subsystem.
 *
 * Centralised from WebSearcher's companion object so that timeouts, cache
 * sizes, and scoring thresholds are discoverable in one place.
 */
object SearchConstants {
    /** 默认搜索超时（秒），可被 buildClient(timeoutSec) 覆盖 */
    const val SEARCH_TIMEOUT_SEC = 8

    /** 搜索结果缓存 TTL */
    const val SEARCH_CACHE_TTL_MS = 30_000L

    /** DDG vqd 令牌缓存 TTL */
    const val VQD_CACHE_TTL_MS = 300_000L

    /** LRU 搜索结果缓存最大条目数 */
    const val MAX_CACHE_SIZE = 100

    /** LRU vqd 缓存最大条目数 */
    const val MAX_VQD_CACHE_SIZE = 50

    /** 缓存周期性清理间隔 */
    const val CACHE_CLEAN_INTERVAL_MS = 60_000L

    /** 反向地理编码缓存 TTL */
    const val GEOCACHE_TTL_MS = 10 * 60 * 1000L

    /** DDG 在中国易超时，6 秒快速失败 */
    const val DDG_TIMEOUT_SEC = 6

    /** Bing/Baidu 需要较长超时以稳定解析 */
    const val BING_BAIDU_TIMEOUT_SEC = 10

    /** Yandex / Sogou / 360 等使用短超时快速失败 */
    const val FAST_TIMEOUT_SEC = 6

    /** Jina 专用客户端超时（IPv4 优先） */
    const val JINA_CONNECT_TIMEOUT_SEC = 8L
    const val JINA_CALL_TIMEOUT_SEC = 10L

    /** 反向地理编码客户端超时 */
    const val GEOCODE_CONNECT_TIMEOUT_SEC = 5L
    const val GEOCODE_READ_TIMEOUT_SEC = 8L

    /** 默认结果数 */
    const val DEFAULT_LIMIT = 5

    /** 安全上限：单次搜索返回结果数上限 */
    const val MAX_LIMIT_CAP = 30

    /** 查询字符串长度上限（DDG/Yandex/Sogou/360 用） */
    const val MAX_QUERY_LENGTH = 500

    /** BM25 阈值（放宽以避免中文短查询被过度过滤） */
    const val FILTER_MIN_BM25_SCORE = 0.1

    /** 至少保留多少条结果（安全网） */
    const val FILTER_MIN_RESULTS = 2

    /** 至少匹配多少个关键词 */
    const val FILTER_MIN_KEYWORD_MATCH = 1

    private val CN_PATTERN = Regex("[\\u4e00-\\u9fa5]")

    fun containsChinese(s: String): Boolean = CN_PATTERN.containsMatchIn(s)
}
