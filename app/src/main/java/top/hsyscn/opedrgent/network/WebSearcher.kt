package top.hsyscn.opedrgent.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import top.hsyscn.opedrgent.utils.DebugLog
import top.hsyscn.opedrgent.utils.StringUtils
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URLEncoder
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.Dns

data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String?,
    val sourceEngines: Set<String> = emptySet(),
    var score: Double = 0.0,
)

data class SearchConfig(
    val providerOrder: String = "baidu,bing,360,sogou,yandex,ddg,jina",
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

class WebSearcher(private val http: OkHttpClient = HttpClients.default) {

    companion object {
        private val CN_PATTERN = Regex("[\\u4e00-\\u9fa5]")
        private const val SEARCH_TIMEOUT_SEC = 8
        private const val SEARCH_CACHE_TTL_MS = 30_000L
        private const val VQD_CACHE_TTL_MS = 300_000L
        
        private const val MAX_CACHE_SIZE = 100
        private const val MAX_VQD_CACHE_SIZE = 50
        private const val CACHE_CLEAN_INTERVAL_MS = 60_000L

        fun containsChinese(s: String): Boolean = CN_PATTERN.containsMatchIn(s)
    }
    
    private val cacheManager: MultiLevelCacheManager by lazy { MultiLevelCacheManager() }
    private val circuitBreakerManager: CircuitBreakerManager get() = CircuitBreakerManager
    private val concurrencyController: AdaptiveConcurrencyController by lazy { AdaptiveConcurrencyController() }
    private val errorClassifier: ErrorClassifier get() = ErrorClassifier
    private val cacheKeyGenerator: CacheKeyGenerator get() = CacheKeyGenerator
    private val hybridRankingEngine: HybridRankingEngine by lazy { HybridRankingEngine() }
    
    // 智能缓存数据结构
    private data class CacheEntry<T>(
        val data: T,
        val timestamp: Long,
        var accessCount: Int = 0,
        var lastAccessTime: Long = timestamp
    )
    
    private val searchCache = LinkedHashMap<String, CacheEntry<List<SearchResult>>>(MAX_CACHE_SIZE, 0.75f, true)  // access-order
    private val vqdCache = LinkedHashMap<String, CacheEntry<String>>(MAX_VQD_CACHE_SIZE, 0.75f, true)

    // IPv4优先的DNS策略（解决Jina等CDN在Android设备上IPv6超时问题）
    private val ipv4PreferredDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = Dns.SYSTEM.lookup(hostname)
            // 优先返回IPv4地址，避免IPv6连接超时
            return addresses.sortedBy { if (it is Inet6Address) 1 else 0 }
        }
    }

    // Jina专用客户端：IPv4优先 + 较短超时（避免IPv6超时拖慢整体搜索）
    private val jinaClient by lazy {
        http.newBuilder()
            .dns(ipv4PreferredDns)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val geocodingClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    private val geocodeCache = mutableMapOf<String, Pair<String?, Long>>()
    private val GEOCACHE_TTL_MS = 10 * 60 * 1000L
    
    // 缓存统计
    private var cacheHits = 0
    private var cacheMisses = 0
    private var lastCleanTime = 0L
    
    @Synchronized
    private fun getFromCache(key: String): List<SearchResult>? {
        val entry = searchCache[key] ?: run {
            cacheMisses++
            return null
        }
        
        val now = System.currentTimeMillis()
        
        // 检查是否过期
        if (now - entry.timestamp > SEARCH_CACHE_TTL_MS) {
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
    private fun putToCache(key: String, results: List<SearchResult>) {
        if (results.isEmpty()) return
        
        // 如果超过最大容量，移除最老的条目（LRU）
        while (searchCache.size >= MAX_CACHE_SIZE) {
            val oldestKey = searchCache.keys.iterator().next()
            searchCache.remove(oldestKey)
        }
        
        searchCache[key] = CacheEntry(
            data = results,
            timestamp = System.currentTimeMillis(),
            accessCount = 1,
            lastAccessTime = System.currentTimeMillis()
        )
        
        // 定期清理过期条目
        periodicCleanUp()
    }
    
    @Synchronized
    private fun periodicCleanUp() {
        val now = System.currentTimeMillis()
        
        // 每隔指定时间执行一次全面清理
        if (now - lastCleanTime < CACHE_CLEAN_INTERVAL_MS) return
        lastCleanTime = now
        
        // 清理过期的搜索结果缓存
        val expiredKeys = searchCache.filter { 
            now - it.value.timestamp > SEARCH_CACHE_TTL_MS 
        }.keys
        
        expiredKeys.forEach { searchCache.remove(it) }
        
        // 清理过期的vqd缓存
        val expiredVqdKeys = vqdCache.filter { 
            now - it.value.timestamp > VQD_CACHE_TTL_MS 
        }.keys
        
        expiredVqdKeys.forEach { vqdCache.remove(it) }
        
        if (expiredKeys.isNotEmpty() || expiredVqdKeys.isNotEmpty()) {
            DebugLog.d(
                "WebSearcher cache cleanup: removed ${expiredKeys.size} search entries, " +
                "${expiredVqdKeys.size} vqd entries. Current size: ${searchCache.size}/${MAX_CACHE_SIZE}"
            )
        }
    }

    private fun buildClient(timeoutSec: Int = SEARCH_TIMEOUT_SEC): OkHttpClient {
        return http.newBuilder()
            .connectTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .writeTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .build()
    }

    /**
     * 清理过期的vqd缓存条目
     */
    private fun cleanExpiredVqdCache() {
        val now = System.currentTimeMillis()
        vqdCache.entries.removeAll { (_, entry) ->
            now - entry.timestamp > VQD_CACHE_TTL_MS
        }
    }

    fun searchDdg(query: String, limit: Int = 5): List<SearchResult> {
        if (query.length >= 500) return emptyList()

        // 引擎状态检查
        if (!EngineStatusManager.isAvailable("ddg")) {
            DebugLog.w("WebSearcher DDG: engine not available")
            return emptyList()
        }

        // 频率限制检查
        if (!RateLimiter.allowRequest("duckduckgo.com")) {
            DebugLog.w("WebSearcher DDG: rate limited")
            return emptyList()
        }

        DebugLog.i("WebSearcher DDG: $query")
        val client = buildClient(6)  // DDG在中国易超时，6秒快速失败

        // 清理过期vqd缓存
        cleanExpiredVqdCache()

        val formData = buildString {
            append("q=").append(URLEncoder.encode(query, Charsets.UTF_8.name()))
            append("&b=")
            append("&kl=wt-wt")
        }

        val req = Request.Builder()
            .url("https://html.duckduckgo.com/html/")
            .post(formData.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .header("User-Agent", UserAgentPool.getFixedUa())
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,en-US;q=0.7")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Referer", "https://html.duckduckgo.com/")
            .header("Origin", "https://html.duckduckgo.com")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "same-origin")
            .header("Sec-Fetch-User", "?1")
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errorBody = resp.body?.string().orEmpty().take(200)
                    DebugLog.w("WebSearcher DDG: HTTP ${resp.code} - $errorBody")
                    when (resp.code) {
                        429 -> EngineStatusManager.handleError("ddg", Exception("Rate limited (429)"))
                        403 -> EngineStatusManager.handleError("ddg", Exception("Forbidden (403)"))
                        else -> {}
                    }
                    return@use emptyList()
                }

                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) {
                    DebugLog.w("WebSearcher DDG: empty body")
                    return@use emptyList()
                }

                val doc = Jsoup.parse(body)

                val captchaForm = doc.selectFirst("#challenge-form")
                if (captchaForm != null) {
                    DebugLog.w("WebSearcher DDG: CAPTCHA detected!")
                    EngineStatusManager.handleError("ddg", Exception("CAPTCHA detected"))
                    return@use emptyList()
                }

                val vqdInput = doc.selectFirst("input[name=vqd]")
                if (vqdInput != null) {
                    val vqdValue = vqdInput.attr("value").trim()
                    if (vqdValue.isNotEmpty()) {
                        val cacheKey = sha256("$query//${UserAgentPool.getFixedUa()}")
                        vqdCache[cacheKey] = CacheEntry(
                            data = vqdValue,
                            timestamp = System.currentTimeMillis()
                        )
                        DebugLog.d("WebSearcher DDG: cached vqd=$vqdValue")
                    }
                }

                val items = doc.select("#links div.web-result")
                val out = ArrayList<SearchResult>()
                DebugLog.d("WebSearcher DDG: ${items.size} web-result items")

                for (item in items) {
                    if (out.size >= limit) break

                    val h2 = item.selectFirst("h2 a")
                    val title = h2?.text()?.trim() ?: continue
                    var href = h2?.attr("href") ?: continue

                    if (title.isBlank() || href.isBlank() || href.contains("duckduckgo.com")) continue

                    if (href.startsWith("//")) href = "https:$href"
                    else if (href.startsWith("/")) href = "https://duckduckgo.com$href"

                    val snippetEl = item.selectFirst(".result__snippet, .snippet, .result__extract")
                    val snippet = snippetEl?.text()?.trim()?.ifBlank { null }
                        ?: item.text().substringAfter(title).take(200).trim().ifBlank { null }

                    out.add(SearchResult(
                        title = StringUtils.sanitizeJsonNull(title),
                        url = href,
                        snippet = snippet?.let { StringUtils.sanitizeJsonNull(it) },
                        sourceEngines = setOf("ddg")
                    ))
                }

                if (out.isEmpty()) {
                    val fallbackItems = doc.select("#links .result__body, #links .result, .web-result")
                    DebugLog.w("WebSearcher DDG: no web-result, trying fallback (${fallbackItems.size} items)")
                    for (item in fallbackItems) {
                        if (out.size >= limit) break
                        val a = item.selectFirst("a.result__a, a[href]:has(h2), h2 a, a[href]")
                        val t = a?.text()?.trim().orEmpty()
                        var u = a?.attr("href") ?: continue
                        if (t.isBlank() || u.isBlank() || u.contains("duckduckgo.com")) continue
                        if (u.startsWith("//")) u = "https:$u"
                        val s = item.selectFirst(".result__snippet, .snippet")?.text()?.trim()?.ifBlank { null }
                        out.add(SearchResult(
                            title = StringUtils.sanitizeJsonNull(t),
                            url = u,
                            snippet = s?.let { StringUtils.sanitizeJsonNull(it) },
                            sourceEngines = setOf("ddg")
                        ))
                    }
                    
                    if (out.isEmpty()) {
                        DebugLog.w("WebSearcher DDG: completely failed to parse results. Body length: ${body.length}")
                    }
                } else {
                    DebugLog.i("WebSearcher DDG: ${out.size} results")
                    EngineStatusManager.recordSuccess("ddg")
                }
                out
            }
        } catch (e: java.net.SocketTimeoutException) {
            DebugLog.e("WebSearcher DDG timeout: ${e.message}")
            EngineStatusManager.handleError("ddg", e)
            val classifiedError = errorClassifier.classify(e)
            if (classifiedError.shouldTriggerCircuitBreaker) {
                CircuitBreakerManager.getOrCreate("ddg").recordFailure(e)
            }
            DebugLog.w("WebSearcher ddg: ${errorClassifier.formatForLog(classifiedError)}")
            emptyList()
        } catch (e: javax.net.ssl.SSLException) {
            DebugLog.e("WebSearcher DDG SSL error: ${e.message}")
            EngineStatusManager.handleError("ddg", e)
            val classifiedError = errorClassifier.classify(e)
            if (classifiedError.shouldTriggerCircuitBreaker) {
                CircuitBreakerManager.getOrCreate("ddg").recordFailure(e)
            }
            DebugLog.w("WebSearcher ddg: ${errorClassifier.formatForLog(classifiedError)}")
            emptyList()
        } catch (e: java.net.UnknownHostException) {
            DebugLog.e("WebSearcher DDG DNS error: ${e.message}")
            EngineStatusManager.handleError("ddg", e)
            val classifiedError = errorClassifier.classify(e)
            if (classifiedError.shouldTriggerCircuitBreaker) {
                CircuitBreakerManager.getOrCreate("ddg").recordFailure(e)
            }
            DebugLog.w("WebSearcher ddg: ${errorClassifier.formatForLog(classifiedError)}")
            emptyList()
        } catch (e: Exception) {
            DebugLog.e("WebSearcher DDG error: ${e.javaClass.simpleName} - ${e.message}")
            EngineStatusManager.handleError("ddg", e)
            val classifiedError = errorClassifier.classify(e)
            if (classifiedError.shouldTriggerCircuitBreaker) {
                CircuitBreakerManager.getOrCreate("ddg").recordFailure(e)
            }
            DebugLog.w("WebSearcher ddg: ${errorClassifier.formatForLog(classifiedError)}")
            emptyList()
        }
    }

    fun searchBingCn(query: String, limit: Int = 5): List<SearchResult> {
        // 引擎状态检查
        if (!EngineStatusManager.isAvailable("bing")) {
            DebugLog.w("WebSearcher Bing: engine not available")
            return emptyList()
        }

        // 频率限制检查
        if (!RateLimiter.allowRequest("bing.com")) {
            DebugLog.w("WebSearcher Bing: rate limited")
            return emptyList()
        }

        val q = URLEncoder.encode(query, Charsets.UTF_8.name())
        val url = "https://www.bing.com/search?q=$q&setlang=zh-CN&mkt=zh-CN&cc=cn&FORM=PERE"
        DebugLog.i("WebSearcher Bing: $query")

        val client = buildClient(10)
        val req = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", UserAgentPool.generate())
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh-Hans;q=0.9,zh;q=0.8,en;q=0.7")
            .header("Cookie", "_EDGE_CD=m=zh-CN%26u%3dzh-Hans; _EDGE_S=mkt%3Dzh-CN%26ui%3Dzh-Hans")
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errorBody = resp.body?.string().orEmpty().take(200)
                    DebugLog.w("WebSearcher Bing: HTTP ${resp.code} - $errorBody")
                    when (resp.code) {
                        429 -> EngineStatusManager.handleError("bing", Exception("Rate limited (429)"))
                        403 -> EngineStatusManager.handleError("bing", Exception("Forbidden (403)"))
                        else -> {}
                    }
                    return emptyList()
                }
                val body = resp.body?.string().orEmpty()
                
                if (body.length < 100) {
                    DebugLog.w("WebSearcher Bing: response too short (${body.length} chars)")
                    return emptyList()
                }
                
                val doc = Jsoup.parse(body)

                val items = doc.select("ol#b_results li.b_algo")
                val out = ArrayList<SearchResult>()
                DebugLog.d("WebSearcher Bing: ${items.size} b_algo items")

                for (item in items) {
                    if (out.size >= limit) break

                    val h2 = item.selectFirst("h2")
                    val title = h2?.text()?.trim() ?: continue
                    val link = h2?.selectFirst("a") ?: item.selectFirst("a")
                    val rawHref = link?.attr("href") ?: continue

                    if (title.isBlank() || rawHref.isBlank()) continue

                    val href = extractBingUrl(rawHref)
                    if (href.isBlank()) continue

                    val captionDiv = item.selectFirst("div.b_caption")
                    val snippet = captionDiv?.selectFirst("p.b_lineclamp3, p.b_snippet")?.text()?.trim()?.ifBlank { null }
                        ?: captionDiv?.text()?.trim()?.let { it.substringAfter(title).take(200).trim().ifBlank { null } }

                    out.add(SearchResult(
                        title = StringUtils.sanitizeJsonNull(title),
                        url = href,
                        snippet = snippet?.let { StringUtils.sanitizeJsonNull(it) },
                        sourceEngines = setOf("bing")
                    ))
                }

                if (out.isEmpty()) {
                    DebugLog.w("WebSearcher Bing: no results parsed. Body length: ${body.length}, contains b_results: ${doc.select("#b_results").isNotEmpty()}")
                } else {
                    DebugLog.i("WebSearcher Bing: ${out.size} results")
                    EngineStatusManager.recordSuccess("bing")
                }
                out
            }
        } catch (e: java.net.SocketTimeoutException) {
            DebugLog.e("WebSearcher Bing timeout: ${e.message}")
            EngineStatusManager.handleError("bing", e)
            val classifiedError = errorClassifier.classify(e)
            if (classifiedError.shouldTriggerCircuitBreaker) {
                CircuitBreakerManager.getOrCreate("bing").recordFailure(e)
            }
            DebugLog.w("WebSearcher bing: ${errorClassifier.formatForLog(classifiedError)}")
            emptyList()
        } catch (e: javax.net.ssl.SSLException) {
            DebugLog.e("WebSearcher Bing SSL error: ${e.message}")
            EngineStatusManager.handleError("bing", e)
            val classifiedError = errorClassifier.classify(e)
            if (classifiedError.shouldTriggerCircuitBreaker) {
                CircuitBreakerManager.getOrCreate("bing").recordFailure(e)
            }
            DebugLog.w("WebSearcher bing: ${errorClassifier.formatForLog(classifiedError)}")
            emptyList()
        } catch (e: java.net.UnknownHostException) {
            DebugLog.e("WebSearcher Bing DNS error: ${e.message}")
            EngineStatusManager.handleError("bing", e)
            val classifiedError = errorClassifier.classify(e)
            if (classifiedError.shouldTriggerCircuitBreaker) {
                CircuitBreakerManager.getOrCreate("bing").recordFailure(e)
            }
            DebugLog.w("WebSearcher bing: ${errorClassifier.formatForLog(classifiedError)}")
            emptyList()
        } catch (e: Exception) {
            DebugLog.e("WebSearcher Bing error: ${e.javaClass.simpleName} - ${e.message}")
            EngineStatusManager.handleError("bing", e)
            val classifiedError = errorClassifier.classify(e)
            if (classifiedError.shouldTriggerCircuitBreaker) {
                CircuitBreakerManager.getOrCreate("bing").recordFailure(e)
            }
            DebugLog.w("WebSearcher bing: ${errorClassifier.formatForLog(classifiedError)}")
            emptyList()
        }
    }

    private fun extractBingUrl(rawHref: String): String {
        // 格式1：传统的 ?q= 编码URL
        if (rawHref.contains("bing.com/url?q=")) {
            try {
                val startIdx = rawHref.indexOf("?q=") + 3
                val endIdx = rawHref.indexOf("&", startIdx).let { if (it > 0) it else rawHref.length }
                return URLDecoder.decode(rawHref.substring(startIdx, endIdx), Charsets.UTF_8.name())
            } catch (_: Exception) { return rawHref }
        }

        // 格式2：新的 base64url 编码格式 bing.com/ck/a?u=a1<base64url>
        if (rawHref.contains("bing.com/ck/a") && rawHref.contains("u=a1")) {
            try {
                val startIdx = rawHref.indexOf("u=a1") + 4
                val endIdx = rawHref.indexOf("&", startIdx).let { if (it > 0) it else rawHref.length }
                val base64Part = rawHref.substring(startIdx, endIdx)

                // Base64 URL解码（处理padding）
                var padded = base64Part
                when (padded.length % 4) {
                    2 -> padded += "=="
                    3 -> padded += "="
                }

                val decodedBytes = Base64.getUrlDecoder().decode(padded)
                return String(decodedBytes, Charsets.UTF_8)
            } catch (e: Exception) {
                DebugLog.w("WebSearcher Bing: failed to decode base64url: ${e.message}")
                return rawHref
            }
        }

        return rawHref
    }

    fun searchBaidu(query: String, limit: Int = 5): List<SearchResult> {
        // 引擎状态检查
        if (!EngineStatusManager.isAvailable("baidu")) {
            DebugLog.w("WebSearcher Baidu: engine not available")
            return emptyList()
        }

        // 频率限制检查
        if (!RateLimiter.allowRequest("baidu.com")) {
            DebugLog.w("WebSearcher Baidu: rate limited")
            return emptyList()
        }

        // 优先尝试JSON API，失败时fallback到HTML版本
        val jsonResults = runCatching { searchBaiduJson(query, limit) }.getOrNull()
        if (!jsonResults.isNullOrEmpty()) {
            return jsonResults
        }

        DebugLog.d("WebSearcher Baidu: JSON API failed, falling back to HTML")
        return searchBaiduHtml(query, limit)
    }

    /**
     * Baidu JSON API模式
     */
    private fun searchBaiduJson(query: String, limit: Int = 5): List<SearchResult> {
        val q = URLEncoder.encode(query, Charsets.UTF_8.name())
        val url = "https://www.baidu.com/s?wd=$q&rn=${limit * 2}&ie=utf-8&tn=json"
        DebugLog.i("WebSearcher Baidu JSON: $query")

        val client = buildClient(10)
        val req = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", UserAgentPool.generate())
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errorBody = resp.body?.string().orEmpty().take(200)
                    DebugLog.w("WebSearcher Baidu JSON: HTTP ${resp.code} - $errorBody")
                    when (resp.code) {
                        429 -> EngineStatusManager.handleError("baidu", Exception("Rate limited (429)"))
                        403 -> EngineStatusManager.handleError("baidu", Exception("Forbidden (403)"))
                        else -> {}
                    }
                    return@use emptyList()
                }

                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) {
                    DebugLog.w("WebSearcher Baidu JSON: empty body")
                    return@use emptyList()
                }

                // 百度安全验证检测：返回HTML而非JSON时，标记引擎为CAPTCHA不可用
                val trimmed = body.trimStart()
                if (trimmed.startsWith("<") || body.contains("百度安全验证") ||
                    body.contains("passport.baidu.com") || body.contains("verify/wappass.baidu.com")) {
                    DebugLog.e("WebSearcher Baidu JSON: security verification detected, " +
                        "body preview=${body.take(200)}")
                    EngineStatusManager.handleError("baidu", Exception("CAPTCHA: security verification"))
                    return@use emptyList()
                }

                try {
                    val json = org.json.JSONObject(body)
                    
                    if (json.has("error") || json.has("errorMsg")) {
                        val errorMsg = json.optString("error", "") 
                            ?: json.optString("errorMsg", "Unknown error")
                        DebugLog.w("WebSearcher Baidu JSON API error: $errorMsg")
                        return@use emptyList()
                    }
                    
                    val feed = json.optJSONObject("feed")
                    val entryArray = feed?.optJSONArray("entry") ?: run {
                        DebugLog.w("WebSearcher Baidu JSON: no 'feed.entry' array in response")
                        return@use emptyList()
                    }

                    val out = ArrayList<SearchResult>()
                    for (i in 0 until entryArray.length()) {
                        if (out.size >= limit) break
                        try {
                            val item = entryArray.getJSONObject(i)

                            val title = item.optString("title", "").trim()
                                .replace("&amp;", "&")
                                .replace("&lt;", "<")
                                .replace("&gt;", ">")
                                .replace("&quot;", "\"")
                                .replace("&#39;", "'")

                            val url = item.optString("url", "").trim()
                            val abs = item.optString("abs", "").trim()
                                .replace("&amp;", "&")
                                .replace("&lt;", "<")
                                .replace("&gt;", ">")
                                .replace("&quot;", "\"")
                                .replace("&#39", "'")

                            if (url.isBlank() || title.isBlank()) continue
                            if ((url.contains("baidu.com") || url.contains("baiducontent.com")) && !url.startsWith("http")) continue

                            out.add(SearchResult(
                                title = StringUtils.sanitizeJsonNull(title),
                                url = url,
                                snippet = abs.ifBlank { null }?.let { StringUtils.sanitizeJsonNull(it) },
                                sourceEngines = setOf("baidu")
                            ))
                        } catch (e: Exception) {
                            DebugLog.w("WebSearcher Baidu JSON: failed to parse result[$i]: ${e.message}")
                            continue
                        }
                    }

                    if (out.isNotEmpty()) {
                        DebugLog.i("WebSearcher Baidu JSON: ${out.size} results")
                        EngineStatusManager.recordSuccess("baidu")
                    } else {
                        DebugLog.w("WebSearcher Baidu JSON: parsed 0 results from ${entryArray.length()} items")
                    }
                    out
                } catch (e: Exception) {
                    DebugLog.e("WebSearcher Baidu JSON parse error: ${e.message}\nResponse: ${body.take(500)}")
                    emptyList()
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            DebugLog.e("WebSearcher Baidu JSON timeout: ${e.message}")
            EngineStatusManager.handleError("baidu", e)
            val classifiedError = errorClassifier.classify(e)
            if (classifiedError.shouldTriggerCircuitBreaker) {
                CircuitBreakerManager.getOrCreate("baidu").recordFailure(e)
            }
            DebugLog.w("WebSearcher baidu: ${errorClassifier.formatForLog(classifiedError)}")
            emptyList()
        } catch (e: javax.net.ssl.SSLException) {
            DebugLog.e("WebSearcher Baidu JSON SSL error: ${e.message}")
            EngineStatusManager.handleError("baidu", e)
            val classifiedError = errorClassifier.classify(e)
            if (classifiedError.shouldTriggerCircuitBreaker) {
                CircuitBreakerManager.getOrCreate("baidu").recordFailure(e)
            }
            DebugLog.w("WebSearcher baidu: ${errorClassifier.formatForLog(classifiedError)}")
            emptyList()
        } catch (e: java.net.UnknownHostException) {
            DebugLog.e("WebSearcher Baidu JSON DNS error: ${e.message}")
            EngineStatusManager.handleError("baidu", e)
            val classifiedError = errorClassifier.classify(e)
            if (classifiedError.shouldTriggerCircuitBreaker) {
                CircuitBreakerManager.getOrCreate("baidu").recordFailure(e)
            }
            DebugLog.w("WebSearcher baidu: ${errorClassifier.formatForLog(classifiedError)}")
            emptyList()
        } catch (e: Exception) {
            DebugLog.e("WebSearcher Baidu JSON error: ${e.javaClass.simpleName} - ${e.message}")
            EngineStatusManager.handleError("baidu", e)
            val classifiedError = errorClassifier.classify(e)
            if (classifiedError.shouldTriggerCircuitBreaker) {
                CircuitBreakerManager.getOrCreate("baidu").recordFailure(e)
            }
            DebugLog.w("WebSearcher baidu: ${errorClassifier.formatForLog(classifiedError)}")
            emptyList()
        }
    }

    private fun searchBaiduHtml(query: String, limit: Int = 5): List<SearchResult> {
        val q = URLEncoder.encode(query, Charsets.UTF_8.name())
        val url = "https://www.baidu.com/s?wd=$q&rn=${limit * 2}&ie=utf-8"
        DebugLog.i("WebSearcher Baidu HTML: $query")

        val client = buildClient(10)
        val req = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", UserAgentPool.generate())
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    DebugLog.w("WebSearcher Baidu HTML: HTTP ${resp.code}")
                    return@use emptyList()
                }
                val body = resp.body?.string().orEmpty()
                // 百度安全验证检测：HTML模式下同样可能遇到
                if (body.contains("百度安全验证") || body.contains("passport.baidu.com") ||
                    body.contains("verify/wappass.baidu.com")) {
                    DebugLog.e("WebSearcher Baidu HTML: security verification detected")
                    EngineStatusManager.handleError("baidu", Exception("CAPTCHA: security verification"))
                    return@use emptyList()
                }
                val doc = Jsoup.parse(body)

                val items = doc.select("div.result.c-container, div[tpl], div.c-result")
                val out = ArrayList<SearchResult>()
                DebugLog.d("WebSearcher Baidu HTML: ${items.size} result items")

                for (item in items) {
                    if (out.size >= limit) break

                    val h3 = item.selectFirst("h3 a, h3.t > a")
                    val title = h3?.text()?.trim() ?: continue
                    var href = h3?.attr("href") ?: continue

                    if (title.isBlank() || href.isBlank()) continue
                    if ((href.contains("baidu.com") || href.contains("baiducontent.com")) && !href.startsWith("http")) continue

                    val abstractEl = item.selectFirst("div.c-abstract, div[class*=abstract], p.c-font-normal")
                    val snippet = abstractEl?.text()?.trim()?.ifBlank { null }
                        ?: item.text().substringAfter(title).take(150).trim().ifBlank { null }

                    out.add(SearchResult(
                        title = StringUtils.sanitizeJsonNull(title),
                        url = href,
                        snippet = snippet?.let { StringUtils.sanitizeJsonNull(it) },
                        sourceEngines = setOf("baidu")
                    ))
                }

                if (out.isEmpty()) {
                    DebugLog.w("WebSearcher Baidu HTML: no results")
                } else {
                    DebugLog.i("WebSearcher Baidu HTML: ${out.size} results")
                    EngineStatusManager.recordSuccess("baidu")
                }
                out
            }
        } catch (e: Exception) {
            DebugLog.e("WebSearcher Baidu HTML error: ${e.message}")
            EngineStatusManager.handleError("baidu", e)
            val classifiedError = errorClassifier.classify(e)
            if (classifiedError.shouldTriggerCircuitBreaker) {
                CircuitBreakerManager.getOrCreate("baidu").recordFailure(e)
            }
            DebugLog.w("WebSearcher baidu: ${errorClassifier.formatForLog(classifiedError)}")
            emptyList()
        }
    }

    data class SearXNGConfig(
        val categories: String = "general",      // general, images, videos, news, science, it, files, music
        val engines: String? = null,             // 逗号分隔的引擎列表，null=使用默认
        val language: String = "auto",           // auto, all, zh-CN, en-US等
        val timeRange: String? = null,           // null=不限, day, week, month, year
        val safeSearch: Int = 1,                 // 0=关闭, 1=中等, 2=严格
        val pageNumber: Int = 1,
        val resultsPerPage: Int = 10,
        val theme: String = "simple"              // simple, oscar, bootstrap5等
    )

    fun searchSearxng(query: String, limit: Int = 5, config: SearXNGConfig? = null): List<SearchResult> {
        if (SEARXNG_BASE_URL.isBlank()) return emptyList()

        // 引擎状态检查
        if (!EngineStatusManager.isAvailable("searxng")) {
            DebugLog.w("WebSearcher SearXNG: engine not available")
            return emptyList()
        }

        // 频率限制检查
        if (!RateLimiter.allowRequest(extractDomain(SEARXNG_BASE_URL))) {
            DebugLog.w("WebSearcher SearXNG: rate limited")
            return emptyList()
        }

        val cfg = config ?: SearXNGConfig(resultsPerPage = limit * 2)  // 多取一些以便筛选
        
        val q = URLEncoder.encode(query, "UTF-8")
        
        // 构建完整URL参数
        val urlBuilder = StringBuilder("$SEARXNG_BASE_URL/search?")
            .append("q=$q")
            .append("&format=json")
            .append("&pageno=${cfg.pageNumber}")
            .append("&safesearch=${cfg.safeSearch}")
            .append("&language=${cfg.language}")
            .append("&theme=${cfg.theme}")
        
        // 可选参数
        cfg.categories.takeIf { it != "general" }?.let { urlBuilder.append("&categories=$it") }
        cfg.engines?.takeIf { it.isNotBlank() }?.let { urlBuilder.append("&engines=${URLEncoder.encode(it, "UTF-8")}") }
        cfg.timeRange?.takeIf { it.isNotBlank() }?.let { urlBuilder.append("&time_range=$it") }
        
        val url = urlBuilder.toString()
        DebugLog.i("WebSearcher SearXNG: $url")

        val startTime = System.currentTimeMillis()
        
        val req = Request.Builder().url(url).get()
            .header("User-Agent", UserAgentPool.generate())
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .build()

        return try {
            http.newCall(req).execute().use { resp ->
                val responseTime = System.currentTimeMillis() - startTime
                
                if (!resp.isSuccessful) {
                    val errorBody = resp.body?.string().orEmpty().take(500)
                    DebugLog.w(
                        "WebSearcher SearXNG failed: HTTP ${resp.code} in ${responseTime}ms - $errorBody"
                    )
                    
                    when (resp.code) {
                        429 -> EngineStatusManager.handleError("searxng", Exception("Rate limited (429)"))
                        403 -> EngineStatusManager.handleError("searxng", Exception("Forbidden (403)"))
                        500, 502, 503 -> EngineStatusManager.handleError("searxng", Exception("Server error (${resp.code})"))
                        else -> {}
                    }
                    
                    return@use emptyList()
                }
                
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) {
                    DebugLog.w("WebSearcher SearXNG: empty response in ${responseTime}ms")
                    return@use emptyList()
                }

                try {
                    val json = org.json.JSONObject(body)
                    
                    // 检查API错误
                    if (json.has("error")) {
                        val errorObj = json.getJSONObject("error")
                        val errorMessage = errorObj.optString("message", "Unknown SearXNG error")
                        DebugLog.e("WebSearcher SearXNG API error: $errorMessage")
                        EngineStatusManager.handleError("searxng", Exception(errorMessage))
                        return@use emptyList()
                    }
                    
                    // 解析搜索结果数量信息
                    val numberOfResults = json.optInt("number_of_results", -1)
                    val resultsOnThisPage = json.optInt("results_count", -1)
                    DebugLog.d(
                        "WebSearcher SearXNG: total=$numberOfResults, page=$resultsOnThisPage"
                    )
                    
                    // 处理查询建议/纠正
                    if (json.has("corrections")) {
                        val corrections = json.getJSONArray("corrections")
                        for (i in 0 until corrections.length()) {
                            val correction = corrections.getString(i)
                            DebugLog.i("WebSearcher SearXNG suggestion: $correction")
                        }
                    }
                    
                    // 处理直接答案（infobox/answer）
                    if (json.has("answers")) {
                        val answers = json.getJSONArray("answers")
                        for (i in 0 until answers.length()) {
                            val answer = answers.getJSONObject(i)
                            DebugLog.i(
                                "WebSearcher SearXNG direct answer: ${answer.optString("answer", "")}"
                            )
                        }
                    }
                    
                    // 解析主要结果
                    val resultsArray = json.optJSONArray("results")
                    
                    if (resultsArray == null) {
                        DebugLog.w("WebSearcher SearXNG: no 'results' array in response")
                        
                        // 尝试解析infobox作为备选
                        if (json.has("infoboxes")) {
                            return@use parseInfoboxes(json.getJSONArray("infoboxes"), limit)
                        } else {
                            return@use emptyList()
                        }
                    }
                    
                    val out = ArrayList<SearchResult>()
                    for (i in 0 until resultsArray.length()) {
                        if (out.size >= limit) break
                        
                        try {
                            val item = resultsArray.getJSONObject(i)
                            
                            val title = item.optString("title", "").trim()
                                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                                .replace("&quot;", "\"").replace("&#39;", "'")
                            
                            val href = item.optString("url", "").trim()
                            var content = item.optString("content", "").trim()
                                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                                .replace("&quot;", "\"").replace("&#39;", "'")
                            
                            if (href.isBlank() || title.isBlank()) continue
                            
                            // 提取引擎来源
                            val engine = item.optString("engine", "searxng")
                            val score = item.optDouble("score", 0.0)
                            
                            // 内容长度优化
                            if (content.isBlank()) {
                                val snippet = item.optString("snippet", "").trim()
                                content = snippet.ifBlank { null } ?: content
                            }
                            
                            out.add(SearchResult(
                                title = StringUtils.sanitizeJsonNull(title),
                                url = href,
                                snippet = content?.let { StringUtils.sanitizeJsonNull(it) },
                                sourceEngines = setOf(engine),
                                score = score
                            ))
                        } catch (e: Exception) {
                            DebugLog.w("WebSearcher SearXNG: failed to parse result[$i]: ${e.message}")
                            continue
                        }
                    }
                    
                    if (out.isNotEmpty()) {
                        DebugLog.i(
                            "WebSearcher SearXNG: ${out.size}/${resultsArray.length()} results in ${responseTime}ms"
                        )
                        EngineStatusManager.recordSuccess("searxng", responseTime)
                    } else {
                        DebugLog.w("WebSearcher SearXNG: parsed 0 results from ${resultsArray.length()} items")
                    }
                    
                    out
                } catch (e: org.json.JSONException) {
                    DebugLog.e(
                        "WebSearcher SearXNG JSON parse error: ${e.message}\nResponse preview: ${
                            body.take(300)
                        }"
                    )
                    emptyList()
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            DebugLog.e("WebSearcher SearXNG timeout: ${e.message}")
            EngineStatusManager.handleError("searxng", e)
            val classifiedError = errorClassifier.classify(e)
            if (classifiedError.shouldTriggerCircuitBreaker) {
                CircuitBreakerManager.getOrCreate("searxng").recordFailure(e)
            }
            DebugLog.w("WebSearcher searxng: ${errorClassifier.formatForLog(classifiedError)}")
            emptyList()
        } catch (e: javax.net.ssl.SSLException) {
            DebugLog.e("WebSearcher SearXNG SSL error: ${e.message}")
            EngineStatusManager.handleError("searxng", e)
            val classifiedError = errorClassifier.classify(e)
            if (classifiedError.shouldTriggerCircuitBreaker) {
                CircuitBreakerManager.getOrCreate("searxng").recordFailure(e)
            }
            DebugLog.w("WebSearcher searxng: ${errorClassifier.formatForLog(classifiedError)}")
            emptyList()
        } catch (e: java.net.UnknownHostException) {
            DebugLog.e("WebSearcher SearXNG DNS error: ${e.message}")
            EngineStatusManager.handleError("searxng", e)
            val classifiedError = errorClassifier.classify(e)
            if (classifiedError.shouldTriggerCircuitBreaker) {
                CircuitBreakerManager.getOrCreate("searxng").recordFailure(e)
            }
            DebugLog.w("WebSearcher searxng: ${errorClassifier.formatForLog(classifiedError)}")
            emptyList()
        } catch (e: Exception) {
            DebugLog.e("WebSearcher SearXNG error: ${e.javaClass.simpleName} - ${e.message}")
            EngineStatusManager.handleError("searxng", e)
            val classifiedError = errorClassifier.classify(e)
            if (classifiedError.shouldTriggerCircuitBreaker) {
                CircuitBreakerManager.getOrCreate("searxng").recordFailure(e)
            }
            DebugLog.w("WebSearcher searxng: ${errorClassifier.formatForLog(classifiedError)}")
            emptyList()
        }
    }

    private fun parseInfoboxes(infoboxes: org.json.JSONArray, limit: Int): List<SearchResult> {
        val out = ArrayList<SearchResult>()
        
        for (i in 0 until infoboxes.length()) {
            if (out.size >= limit) break
            
            try {
                val infobox = infoboxes.getJSONObject(i)
                val title = infobox.optString("title", "").trim()
                val urls = infobox.optJSONArray("urls")
                
                if (urls != null && urls.length() > 0) {
                    val firstUrl = urls.getJSONObject(0)
                    val href = firstUrl.optString("url", "")
                    val content = infobox.optString("content", "").trim()
                    
                    if (href.isNotBlank() && title.isNotBlank()) {
                        out.add(SearchResult(
                            title = StringUtils.sanitizeJsonNull(title),
                            url = href,
                            snippet = content.ifBlank { null }?.let { StringUtils.sanitizeJsonNull(it) },
                            sourceEngines = setOf("searxng-infobox")
                        ))
                    }
                }
            } catch (e: Exception) {
                continue
            }
        }
        
        return out
    }
    
    /**
     * 从URL中提取域名用于速率限制
     */
    private fun extractDomain(url: String): String {
        return try {
            java.net.URL(url).host
        } catch (e: Exception) {
            "searxng.unknown"
        }
    }

    fun searchJina(query: String, limit: Int = 5, apiKey: String? = null): List<SearchResult> {
        val url = "https://s.jina.ai/search"

        val jsonBody = org.json.JSONObject()
            .put("q", query)
            .put("count", limit)
            .toString()

        DebugLog.i("WebSearcher Jina Search: $query")

        val reqBuilder = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", UserAgentPool.generate())
        if (!apiKey.isNullOrBlank()) {
            reqBuilder.header("Authorization", "Bearer $apiKey")
        }
        val req = reqBuilder.post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())).build()

        // 使用IPv4优先的专用客户端，避免Android设备IPv6连接CDN超时
        return try {
            jinaClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errorBody = resp.body?.string().orEmpty()
                    DebugLog.w("WebSearcher Jina Search failed: HTTP ${resp.code} - $errorBody")
                    return@use emptyList()
                }
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) {
                    DebugLog.w("WebSearcher Jina Search: empty response body")
                    return@use emptyList()
                }

                try {
                    val json = org.json.JSONObject(body)
                    
                    if (json.has("error")) {
                        val errorMsg = json.optString("error", "Unknown error")
                        DebugLog.w("WebSearcher Jina Search API error: $errorMsg")
                        return@use emptyList()
                    }
                    
                    val data = json.optJSONArray("data") ?: run {
                        DebugLog.w("WebSearcher Jina Search: no 'data' array in response")
                        return@use emptyList()
                    }
                    
                    val out = ArrayList<SearchResult>()
                    for (i in 0 until data.length()) {
                        if (out.size >= limit) break
                        try {
                            val item = data.getJSONObject(i)
                            val title = item.optString("title", "").trim()
                                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                            val href = item.optString("url", "").trim()
                            
                            var snippet = item.optString("description", "").trim()
                                .ifBlank { item.optString("content", "").trim().take(200).ifBlank { null } }
                            
                            if (href.isBlank() || title.isBlank()) continue
                            
                            snippet = snippet?.let { 
                                it.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                                    .replace("&quot;", "\"").replace("&#39;", "'")
                            }
                            
                            out.add(SearchResult(
                                title = StringUtils.sanitizeJsonNull(title),
                                url = href,
                                snippet = snippet?.let { StringUtils.sanitizeJsonNull(it) },
                                sourceEngines = setOf("jina")
                            ))
                        } catch (e: Exception) {
                            DebugLog.w("WebSearcher Jina Search: failed to parse result[$i]: ${e.message}")
                            continue
                        }
                    }
                    
                    if (out.isNotEmpty()) {
                        DebugLog.i("WebSearcher Jina Search: ${out.size} results")
                    } else {
                        DebugLog.w("WebSearcher Jina Search: parsed 0 results from ${data.length()} items")
                    }
                    out
                } catch (e: Exception) {
                    DebugLog.e("WebSearcher Jina Search JSON parse error: ${e.message}\nResponse: ${body.take(500)}")
                    emptyList()
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            DebugLog.e("WebSearcher Jina Search timeout: ${e.message}")
            emptyList()
        } catch (e: javax.net.ssl.SSLException) {
            DebugLog.e("WebSearcher Jina Search SSL error: ${e.message}")
            emptyList()
        } catch (e: java.net.UnknownHostException) {
            DebugLog.e("WebSearcher Jina Search DNS error: ${e.message}")
            emptyList()
        } catch (e: Exception) {
            DebugLog.e("WebSearcher Jina Search error: ${e.javaClass.simpleName} - ${e.message}")
            emptyList()
        }
    }

    fun searchBrave(query: String, limit: Int = 5, apiKey: String): List<SearchResult> {
        if (apiKey.isBlank()) return emptyList()
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "https://api.search.brave.com/res/v1/web/search?q=$q&count=$limit"
        DebugLog.i("WebSearcher Brave: $query")

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UserAgentPool.generate())
            .header("Accept", "application/json")
            .header("X-Subscription-Token", apiKey)
            .build()

        return try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    DebugLog.w("WebSearcher Brave failed: HTTP ${resp.code}")
                    return@use emptyList()
                }
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) return@use emptyList()

                try {
                    val json = org.json.JSONObject(body)
                    val results = json.optJSONObject("web")?.optJSONArray("results") ?: return@use emptyList()
                    val out = ArrayList<SearchResult>()
                    for (i in 0 until results.length()) {
                        if (out.size >= limit) break
                        val item = results.getJSONObject(i)
                        val title = item.optString("title", "").trim()
                        val href = item.optString("url", "").trim()
                        val snippet = item.optString("description", "").trim().ifBlank { null }
                        if (href.isBlank() || title.isBlank()) continue
                        out.add(SearchResult(
                            title = StringUtils.sanitizeJsonNull(title),
                            url = href,
                            snippet = snippet?.let { StringUtils.sanitizeJsonNull(it) },
                            sourceEngines = setOf("brave")
                        ))
                    }
                    DebugLog.i("WebSearcher Brave: ${out.size} results")
                    out
                } catch (e: Exception) {
                    DebugLog.w("WebSearcher Brave parse error: ${e.message}")
                    emptyList()
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            DebugLog.e("WebSearcher Brave timeout: ${e.message}")
            emptyList()
        } catch (e: javax.net.ssl.SSLException) {
            DebugLog.e("WebSearcher Brave SSL error: ${e.message}")
            emptyList()
        } catch (e: java.net.UnknownHostException) {
            DebugLog.e("WebSearcher Brave DNS error: ${e.message}")
            emptyList()
        } catch (e: Exception) {
            DebugLog.e("WebSearcher Brave error: ${e.javaClass.simpleName} - ${e.message}")
            emptyList()
        }
    }

    fun searchTavily(query: String, limit: Int = 5, apiKey: String): List<SearchResult> {
        if (apiKey.isBlank()) return emptyList()
        val url = "https://api.tavily.com/search"
        
        val jsonBody = org.json.JSONObject()
            .put("api_key", apiKey)
            .put("query", query)
            .put("max_results", limit)
            .put("search_depth", "basic")
            .put("include_answer", false)
            .toString()
        
        DebugLog.i("WebSearcher Tavily: $query")

        val req = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("User-Agent", UserAgentPool.generate())
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errorBody = resp.body?.string().orEmpty()
                    DebugLog.w("WebSearcher Tavily failed: HTTP ${resp.code} - $errorBody")
                    return@use emptyList()
                }
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) {
                    DebugLog.w("WebSearcher Tavily: empty response body")
                    return@use emptyList()
                }

                try {
                    val json = org.json.JSONObject(body)
                    
                    if (json.has("error")) {
                        val errorMsg = json.optString("error", "Unknown error")
                        DebugLog.w("WebSearcher Tavily API error: $errorMsg")
                        return@use emptyList()
                    }
                    
                    val results = json.optJSONArray("results") ?: run {
                        DebugLog.w("WebSearcher Tavily: no 'results' array in response")
                        return@use emptyList()
                    }
                    
                    val out = ArrayList<SearchResult>()
                    for (i in 0 until results.length()) {
                        if (out.size >= limit) break
                        try {
                            val item = results.getJSONObject(i)
                            val title = item.optString("title", "").trim()
                                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                            val href = item.optString("url", "").trim()
                            val snippet = item.optString("content", "").trim().take(200).ifBlank { null }
                            
                            if (href.isBlank() || title.isBlank()) continue
                            
                            out.add(SearchResult(
                                title = StringUtils.sanitizeJsonNull(title),
                                url = href,
                                snippet = snippet?.let { StringUtils.sanitizeJsonNull(it) },
                                sourceEngines = setOf("tavily")
                            ))
                        } catch (e: Exception) {
                            DebugLog.w("WebSearcher Tavily: failed to parse result[$i]: ${e.message}")
                            continue
                        }
                    }
                    
                    if (out.isNotEmpty()) {
                        DebugLog.i("WebSearcher Tavily: ${out.size} results")
                    } else {
                        DebugLog.w("WebSearcher Tavily: parsed 0 results from ${results.length()} items")
                    }
                    out
                } catch (e: Exception) {
                    DebugLog.e("WebSearcher Tavily JSON parse error: ${e.message}\nResponse: ${body.take(500)}")
                    emptyList()
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            DebugLog.e("WebSearcher Tavily timeout: ${e.message}")
            emptyList()
        } catch (e: javax.net.ssl.SSLException) {
            DebugLog.e("WebSearcher Tavily SSL error: ${e.message}")
            emptyList()
        } catch (e: java.net.UnknownHostException) {
            DebugLog.e("WebSearcher Tavily DNS error: ${e.message}")
            emptyList()
        } catch (e: Exception) {
            DebugLog.e("WebSearcher Tavily error: ${e.javaClass.simpleName} - ${e.message}")
            emptyList()
        }
    }

    // ==================== Yandex 搜索 ====================
    fun searchYandex(query: String, limit: Int = 5): List<SearchResult> {
        if (query.length >= 500) return emptyList()

        if (!EngineStatusManager.isAvailable("yandex")) {
            DebugLog.w("WebSearcher Yandex: engine not available")
            return emptyList()
        }
        if (!RateLimiter.allowRequest("yandex.com")) {
            DebugLog.w("WebSearcher Yandex: rate limited")
            return emptyList()
        }

        val q = URLEncoder.encode(query, "UTF-8")
        val isZh = containsChinese(query)
        
        // Yandex搜索URL：支持中文(lr=lang_zh)
        val url = if (isZh) {
            "https://yandex.com/search/?text=$q&lr=lang_zh&l10n=zh-CN&p=0&numdoc=$limit"
        } else {
            "https://yandex.com/search/?text=$q&p=0&numdoc=$limit"
        }

        DebugLog.i("WebSearcher Yandex: $query")

        val req = Request.Builder().url(url).get()
            .header("User-Agent", UserAgentPool.generate())
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7,ru;q=0.6")
            .header("Cookie", "yandexuid=test; yandex_login=no")
            .build()

        val client = buildClient(6)

        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    DebugLog.w("WebSearcher Yandex: HTTP ${resp.code}")
                    when (resp.code) {
                        429 -> EngineStatusManager.handleError("yandex", Exception("Rate limited (429)"))
                        403 -> EngineStatusManager.handleError("yandex", Exception("Forbidden (403)"))
                        else -> {}
                    }
                    return@use emptyList()
                }

                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) {
                    DebugLog.w("WebSearcher Yandex: empty body")
                    return@use emptyList()
                }

                try {
                    val doc = Jsoup.parse(body)
                    val items = doc.select("li.serp-item, div.serp-item, organic__url, [data-cid]")
                    val out = ArrayList<SearchResult>()
                    DebugLog.d("WebSearcher Yandex: ${items.size} serp-items")

                    for (item in items) {
                        if (out.size >= limit) break

                        val titleEl = item.selectFirst("h3.serp-title, h3.organic__title, a.serp-url__title, h2 > a")
                        val title = titleEl?.text()?.trim() ?: continue
                        var href = titleEl?.attr("href") ?: 
                            item.selectFirst("a[href]")?.attr("href") ?: continue

                        if (title.isBlank() || href.isBlank()) continue

                        // 清理Yandex重定向链接
                        href = cleanYandexUrl(href)

                        val snippetEl = item.selectFirst(
                            "div.serp-item__text, p.text-container__text, " +
                            "div.organic__content-wrapper, span.snippet"
                        )
                        val snippet = snippetEl?.text()?.trim()?.ifBlank { null }

                        out.add(SearchResult(
                            title = StringUtils.sanitizeJsonNull(title),
                            url = href,
                            snippet = snippet?.let { StringUtils.sanitizeJsonNull(it) },
                            sourceEngines = setOf("yandex")
                        ))
                    }

                    if (out.isEmpty()) {
                        // Fallback: 尝试解析所有包含链接的元素
                        val fallbackLinks = doc.select("a[href]:not([href^=/]):not([href*=yandex])")
                        DebugLog.w(
                            "WebSearcher Yandex: no serp-items, trying fallback (${fallbackLinks.size} links)"
                        )
                        for (link in fallbackLinks) {
                            if (out.size >= limit) break
                            val t = link.text().trim()
                            var u = link.attr("href").trim()
                            if (t.length < 5 || u.isBlank() || u.contains("yandex")) continue
                            u = cleanYandexUrl(u)
                            out.add(SearchResult(
                                title = StringUtils.sanitizeJsonNull(t),
                                url = u,
                                snippet = null,
                                sourceEngines = setOf("yandex")
                            ))
                        }
                    }

                    if (out.isNotEmpty()) {
                        DebugLog.i("WebSearcher Yandex: ${out.size} results")
                        EngineStatusManager.recordSuccess("yandex")
                    } else {
                        DebugLog.w("WebSearcher Yandex: no results parsed")
                    }
                    out
                } catch (e: Exception) {
                    DebugLog.e("WebSearcher Yandex parse error: ${e.message}")
                    emptyList()
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            DebugLog.e("WebSearcher Yandex timeout: ${e.message}")
            EngineStatusManager.handleError("yandex", e)
            val classifiedError = errorClassifier.classify(e)
            if (classifiedError.shouldTriggerCircuitBreaker) {
                CircuitBreakerManager.getOrCreate("yandex").recordFailure(e)
            }
            DebugLog.w("WebSearcher yandex: ${errorClassifier.formatForLog(classifiedError)}")
            emptyList()
        } catch (e: javax.net.ssl.SSLException) {
            DebugLog.e("WebSearcher Yandex SSL error: ${e.message}")
            EngineStatusManager.handleError("yandex", e)
            val classifiedError = errorClassifier.classify(e)
            if (classifiedError.shouldTriggerCircuitBreaker) {
                CircuitBreakerManager.getOrCreate("yandex").recordFailure(e)
            }
            DebugLog.w("WebSearcher yandex: ${errorClassifier.formatForLog(classifiedError)}")
            emptyList()
        } catch (e: java.net.UnknownHostException) {
            DebugLog.e("WebSearcher Yandex DNS error: ${e.message}")
            EngineStatusManager.handleError("yandex", e)
            val classifiedError = errorClassifier.classify(e)
            if (classifiedError.shouldTriggerCircuitBreaker) {
                CircuitBreakerManager.getOrCreate("yandex").recordFailure(e)
            }
            DebugLog.w("WebSearcher yandex: ${errorClassifier.formatForLog(classifiedError)}")
            emptyList()
        } catch (e: Exception) {
            DebugLog.e("WebSearcher Yandex error: ${e.javaClass.simpleName} - ${e.message}")
            EngineStatusManager.handleError("yandex", e)
            val classifiedError = errorClassifier.classify(e)
            if (classifiedError.shouldTriggerCircuitBreaker) {
                CircuitBreakerManager.getOrCreate("yandex").recordFailure(e)
            }
            DebugLog.w("WebSearcher yandex: ${errorClassifier.formatForLog(classifiedError)}")
            emptyList()
        }
    }

    private fun cleanYandexUrl(url: String): String {
        var cleaned = url
        if (cleaned.startsWith("//")) cleaned = "https:$cleaned"
        // 移除Yandex跟踪参数
        if (cleaned.contains("yandex") && cleaned.contains("&")) {
            cleaned = cleaned.substringBefore("?").let { base ->
                val params = cleaned.substringAfter("?").split("&")
                    .filter { !it.startsWith("utm_") && it != "lr" && it != "l10n" }
                if (params.isEmpty()) base else "$base?${params.joinToString("&")}"
            }
        }
        return cleaned
    }

    // ==================== Sogou 搜索 ====================
    fun searchSogou(query: String, limit: Int = 5): List<SearchResult> {
        if (query.length >= 500) return emptyList()

        if (!EngineStatusManager.isAvailable("sogou")) {
            DebugLog.w("WebSearcher Sogou: engine not available")
            return emptyList()
        }

        val q = URLEncoder.encode(query, Charsets.UTF_8.name())
        val url = "https://www.sogou.com/web?query=$q"

        DebugLog.i("WebSearcher Sogou: $query")

        val req = Request.Builder().url(url).get()
            .header("User-Agent", UserAgentPool.generate())
            .header("Accept", "text/html,application/xhtml+xml,*/*")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("Referer", "https://www.sogou.com/")
            .build()

        val client = buildClient(6)

        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    DebugLog.w("WebSearcher Sogou: HTTP ${resp.code}")
                    return@use emptyList()
                }

                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) return@use emptyList()

                try {
                    val doc = Jsoup.parse(body)

                    // Sogou结果选择器
                    val items = doc.select("div.vrwrap, div.rb, div.results > div.vr-result")
                    val out = ArrayList<SearchResult>()

                    for (item in items) {
                        if (out.size >= limit) break

                        val titleEl = item.selectFirst("h3.vrTitle, h3 > a, a.vrTitle")
                        val title = titleEl?.text()?.trim() ?: continue
                        var href = titleEl?.attr("href") ?: continue

                        if (title.isBlank() || href.isBlank()) continue

                        // 处理Sogou重定向
                        if (href.startsWith("/link?url=")) {
                            try {
                                val realUrl = java.net.URLDecoder.decode(
                                    href.substringAfter("url="), "UTF-8"
                                ).substringBefore("&")
                                href = realUrl
                            } catch (e: Exception) { continue }
                        }

                        val snippetEl = item.selectFirst("p.str-text-info, p.fs, div.str_info")
                        val snippet = snippetEl?.text()?.trim()?.ifBlank { null }

                        out.add(SearchResult(
                            title = StringUtils.sanitizeJsonNull(title),
                            url = href,
                            snippet = snippet?.let { StringUtils.sanitizeJsonNull(it) },
                            sourceEngines = setOf("sogou")
                        ))
                    }

                    if (out.isNotEmpty()) {
                        DebugLog.i("WebSearcher Sogou: ${out.size} results")
                        EngineStatusManager.recordSuccess("sogou")
                    } else {
                        DebugLog.w("WebSearcher Sogou: no results parsed")
                    }
                    out
                } catch (e: Exception) {
                    DebugLog.e("WebSearcher Sogou parse error: ${e.message}")
                    emptyList()
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            DebugLog.e("WebSearcher Sogou timeout: ${e.message}")
            EngineStatusManager.handleError("sogou", e)
            val classifiedError = errorClassifier.classify(e)
            if (classifiedError.shouldTriggerCircuitBreaker) {
                CircuitBreakerManager.getOrCreate("sogou").recordFailure(e)
            }
            DebugLog.w("WebSearcher sogou: ${errorClassifier.formatForLog(classifiedError)}")
            emptyList()
        } catch (e: javax.net.ssl.SSLException) {
            DebugLog.e("WebSearcher Sogou SSL error: ${e.message}")
            EngineStatusManager.handleError("sogou", e)
            val classifiedError = errorClassifier.classify(e)
            if (classifiedError.shouldTriggerCircuitBreaker) {
                CircuitBreakerManager.getOrCreate("sogou").recordFailure(e)
            }
            DebugLog.w("WebSearcher sogou: ${errorClassifier.formatForLog(classifiedError)}")
            emptyList()
        } catch (e: Exception) {
            DebugLog.e("WebSearcher Sogou error: ${e.javaClass.simpleName} - ${e.message}")
            EngineStatusManager.handleError("sogou", e)
            val classifiedError = errorClassifier.classify(e)
            if (classifiedError.shouldTriggerCircuitBreaker) {
                CircuitBreakerManager.getOrCreate("sogou").recordFailure(e)
            }
            DebugLog.w("WebSearcher sogou: ${errorClassifier.formatForLog(classifiedError)}")
            emptyList()
        }
    }

    fun search360(query: String, limit: Int = 5): List<SearchResult> {
        if (query.length >= 500) return emptyList()

        if (!EngineStatusManager.isAvailable("360")) {
            DebugLog.w("WebSearcher 360: engine not available")
            return emptyList()
        }

        val q = URLEncoder.encode(query, Charsets.UTF_8.name())
        val url = "https://www.so.com/s?q=$q"

        DebugLog.i("WebSearcher 360: $query")

        val req = Request.Builder().url(url).get()
            .header("User-Agent", UserAgentPool.generate())
            .header("Accept", "text/html,application/xhtml+xml,*/*")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("Referer", "https://www.so.com/")
            .build()

        val client = buildClient(6)

        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    DebugLog.w("WebSearcher 360: HTTP ${resp.code}")
                    return@use emptyList()
                }

                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) return@use emptyList()

                try {
                    val doc = Jsoup.parse(body)

                    // 360搜索结果选择器
                    val items = doc.select("div.res-list, li.res-list, div.result, div[data-res]")
                    val out = ArrayList<SearchResult>()

                    for (item in items) {
                        if (out.size >= limit) break

                        val titleEl = item.selectFirst("h3 > a, a.title, h3.res-title")
                        val title = titleEl?.text()?.trim() ?: continue
                        var href = titleEl?.attr("href") ?: continue

                        if (title.isBlank() || href.isBlank()) continue

                        // 处理360重定向
                        if (href.contains("so.com/link") || href.contains("url=")) {
                            try {
                                val decoded = java.net.URLDecoder.decode(href.substringAfter("url="), "UTF-8")
                                href = decoded.substringBefore("&").substringBefore(";")
                            } catch (e: Exception) { /* keep original */ }
                        }

                        val snippetEl = item.selectFirst("p.res-desc, p.desc, div.c-abstract")
                        val snippet = snippetEl?.text()?.trim()?.ifBlank { null }

                        out.add(SearchResult(
                            title = StringUtils.sanitizeJsonNull(title),
                            url = href,
                            snippet = snippet?.let { StringUtils.sanitizeJsonNull(it) },
                            sourceEngines = setOf("360")
                        ))
                    }

                    if (out.isNotEmpty()) {
                        DebugLog.i("WebSearcher 360: ${out.size} results")
                        EngineStatusManager.recordSuccess("360")
                    } else {
                        DebugLog.w("WebSearcher 360: no results parsed")
                    }
                    out
                } catch (e: Exception) {
                    DebugLog.e("WebSearcher 360 parse error: ${e.message}")
                    emptyList()
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            DebugLog.e("WebSearcher 360 timeout: ${e.message}")
            EngineStatusManager.handleError("360", e)
            val classifiedError = errorClassifier.classify(e)
            if (classifiedError.shouldTriggerCircuitBreaker) {
                CircuitBreakerManager.getOrCreate("360").recordFailure(e)
            }
            DebugLog.w("WebSearcher 360: ${errorClassifier.formatForLog(classifiedError)}")
            emptyList()
        } catch (e: javax.net.ssl.SSLException) {
            DebugLog.e("WebSearcher 360 SSL error: ${e.message}")
            EngineStatusManager.handleError("360", e)
            val classifiedError = errorClassifier.classify(e)
            if (classifiedError.shouldTriggerCircuitBreaker) {
                CircuitBreakerManager.getOrCreate("360").recordFailure(e)
            }
            DebugLog.w("WebSearcher 360: ${errorClassifier.formatForLog(classifiedError)}")
            emptyList()
        } catch (e: Exception) {
            DebugLog.e("WebSearcher 360 error: ${e.javaClass.simpleName} - ${e.message}")
            EngineStatusManager.handleError("360", e)
            val classifiedError = errorClassifier.classify(e)
            if (classifiedError.shouldTriggerCircuitBreaker) {
                CircuitBreakerManager.getOrCreate("360").recordFailure(e)
            }
            DebugLog.w("WebSearcher 360: ${errorClassifier.formatForLog(classifiedError)}")
            emptyList()
        }
    }

    fun fetchViaJina(url: String): JinaResult? {
        DebugLog.i("WebSearcher Jina: $url")
        val jinaUrl = "https://r.jina.ai/$url"
        val req = Request.Builder()
            .url(jinaUrl)
            .get()
            .header("User-Agent", UserAgentPool.generate())
            .header("Accept", "text/plain")
            .header("X-No-Cache", "true")
            .build()

        // 使用IPv4优先的专用客户端，避免Android设备IPv6连接CDN超时
        return jinaClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                DebugLog.w("WebSearcher Jina failed: HTTP ${resp.code}")
                return null
            }
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) {
                DebugLog.w("WebSearcher Jina: empty body")
                return null
            }

            val titleMatch = Regex("^Title:\\s*(.+)$", RegexOption.MULTILINE).find(body)
            val title = titleMatch?.groupValues?.get(1)?.trim() ?: ""

            val textContent = body
                .replace(Regex("^URL:\\s*.+$", RegexOption.MULTILINE), "")
                .replace(Regex("^Published Time:\\s*.+$", RegexOption.MULTILINE), "")
                .trim()

            DebugLog.i("WebSearcher Jina: ${textContent.length} chars, title=$title")
            JinaResult(title = title, url = url, text = textContent)
        }
    }

    fun reverseGeocode(lat: Double, lon: Double): String? {
        val cacheKey = "${lat.toFloat()},${lon.toFloat()}"

        geocodeCache[cacheKey]?.let { (result, timestamp) ->
            if (System.currentTimeMillis() - timestamp < GEOCACHE_TTL_MS) {
                return result
            }
        }

        val url = "https://nominatim.openstreetmap.org/reverse?format=json&lat=$lat&lon=$lon"
        DebugLog.i("WebSearcher Nominatim: $lat, $lon")

        val req = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", "Opedrgent/1.0 (AI Research Assistant)")
            .header("Accept", "application/json")
            .build()

        return try {
            geocodingClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    DebugLog.w("WebSearcher Nominatim failed: HTTP ${resp.code}")
                    null
                } else {
                    val body = resp.body?.string().orEmpty()
                    if (body.isBlank()) null
                    else {
                        try {
                            val json = org.json.JSONObject(body)
                            val displayName = json.optString("display_name", "").trim()
                            val result = if (displayName.isNotEmpty()) displayName else null
                            geocodeCache[cacheKey] = Pair(result, System.currentTimeMillis())
                            result
                        } catch (e: Exception) {
                            DebugLog.w("WebSearcher Nominatim parse error: ${e.message}")
                            null
                        }
                    }
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            DebugLog.w("WebSearcher Nominatim timeout: ${e.message}")
            null
        } catch (e: Exception) {
            DebugLog.e("WebSearcher Nominatim error: ${e.javaClass.simpleName} - ${e.message}")
            null
        }
    }

    /**
     * 异步并发搜索方法
     */
    suspend fun searchWithResilience(
        query: String,
        config: SearchConfig,
        limit: Int = 5,
        priority: Priority = Priority.NORMAL
    ): List<SearchResult> {
        val cacheKey = cacheKeyGenerator.generate(
            query = query,
            providerOrder = config.providerOrder
        )

        val cached = cacheManager.get(cacheKey)
        if (cached != null) {
            DebugLog.i("WebSearcher: multi-level cache HIT for '${query.take(50)}'")
            return cached.results
        }

        hybridRankingEngine.initialize(query)

        val isZh = containsChinese(query)
        val q = query

        val container = SearchResultContainer()
        container.setQueryKeywords(query)

        val providers = config.providerOrder.split(",").map { it.trim().lowercase() }

        coroutineScope {
            val deferredResults = providers.map { provider ->
                async(Dispatchers.IO) {
                    concurrencyController.withEngineAccess(provider, priority) {
                        val breaker = circuitBreakerManager.getOrCreate(provider)
                        if (!breaker.allowRequest()) {
                            DebugLog.w("WebSearcher: circuit OPEN for $provider, skipping")
                            return@withEngineAccess null
                        }

                        try {
                            val results = when (provider) {
                                "searxng" -> {
                                    val url = config.searxngUrl ?: SEARXNG_BASE_URL
                                    if (url.isBlank()) null
                                    else {
                                        val prev = SEARXNG_BASE_URL
                                        SEARXNG_BASE_URL = url
                                        val r = runCatching { searchSearxng(q, limit) }.getOrNull()
                                        SEARXNG_BASE_URL = prev
                                        r
                                    }
                                }
                                "ddg", "duckduckgo" -> runCatching { searchDdg(q, limit) }.getOrNull()
                                "baidu" -> runCatching { searchBaidu(q, limit) }.getOrNull()
                                "bing" -> runCatching { searchBingCn(q, limit) }.getOrNull()
                                "jina" -> runCatching { searchJina(query, limit, config.jinaApiKey) }.getOrNull()
                                "brave" -> runCatching { searchBrave(query, limit, config.braveApiKey ?: "") }.getOrNull()
                                "tavily" -> runCatching { searchTavily(query, limit, config.tavilyApiKey ?: "") }.getOrNull()
                                "yandex" -> runCatching { searchYandex(query, limit) }.getOrNull()
                                "sogou" -> runCatching { searchSogou(query, limit) }.getOrNull()
                                "360", "so", "so.com" -> runCatching { search360(query, limit) }.getOrNull()
                                else -> null
                            }

                            if (!results.isNullOrEmpty()) {
                                breaker.recordSuccess()
                                results
                            } else {
                                null
                            }
                        } catch (e: Exception) {
                            val classifiedError = errorClassifier.classify(e)
                            if (classifiedError.shouldTriggerCircuitBreaker) {
                                breaker.recordFailure(e)
                            }
                            DebugLog.w("WebSearcher $provider: ${errorClassifier.formatForLog(classifiedError)}")
                            null
                        }
                    }
                }
            }
            val allResults = deferredResults.awaitAll().filterNotNull()
            allResults.forEach { providerResults ->
                if (providerResults.isNotEmpty()) {
                    val providerName = providerResults.firstOrNull()?.sourceEngines?.firstOrNull() ?: "unknown"
                    container.addResults(providerName, providerResults)
                    DebugLog.i("WebSearcher resilience: $providerName returned ${providerResults.size} results")
                }
            }
        }

        if (container.getSortedResults(limit).isEmpty()) {
            DebugLog.w("WebSearcher resilience: all main engines failed, trying Jina fallback")
            val jinaFallback = runCatching { searchJina(query, limit, null) }.getOrNull()
            if (!jinaFallback.isNullOrEmpty()) {
                container.addResults("jina-fallback", jinaFallback)
            }
        }

        val filterResult = container.filterRelevantResults(
            minKeywordMatch = 1,
            minBm25Score = 0.1,   // 放宽BM25阈值，避免中文短查询被过度过滤
            minResults = 2         // 至少保留2条结果，安全网
        )

        var finalResults = container.getSortedResults(limit.coerceAtMost(30))

        if (finalResults.isNotEmpty()) {
            val beforeDedup = finalResults.size
            finalResults = ResultDeduplicator().deduplicate(finalResults)
            DebugLog.i("WebSearcher dedup: $beforeDedup -> ${finalResults.size} (${beforeDedup - finalResults.size} removed)")
        }

        if (finalResults.isNotEmpty()) {
            val ranked = hybridRankingEngine.rank(finalResults, limit)
            finalResults = ranked.map { it.result }
        }

        if (finalResults.isNotEmpty()) {
            val resultSet = SearchResultSet(
                results = finalResults,
                timestamp = System.currentTimeMillis(),
                query = query,
                providerOrder = config.providerOrder
            )
            cacheManager.put(cacheKey, resultSet)

            putToCache("${query.lowercase().trim()}|$limit|${config.providerOrder}", finalResults)
        } else {
            DebugLog.w("WebSearcher resilience: all engines failed, returning empty")
        }

        return finalResults
    }

    suspend fun searchAsync(query: String, config: SearchConfig, limit: Int = 5): List<SearchResult> {
        return searchWithResilience(query, config, limit)
    }

    fun getDiagnostics(): Map<String, Any> {
        return mapOf(
            "cache" to cacheManager.getStats(),
            "circuitBreakers" to circuitBreakerManager.getAllStatus(),
            "concurrency" to concurrencyController.getStats(),
            "legacyCache" to getCacheStats()
        )
    }

    @Synchronized
    fun getCacheStats(): Map<String, Any> {
        val total = cacheHits + cacheMisses
        return mapOf(
            "size" to searchCache.size,
            "maxSize" to MAX_CACHE_SIZE,
            "hits" to cacheHits,
            "misses" to cacheMisses,
            "hitRate" to if (total > 0) "%.1f".format(cacheHits.toDouble() / total * 100) + "%" else "N/A",
            "vqdCacheSize" to vqdCache.size
        )
    }

    /**
     * 同步搜索方法（兼容包装，内部使用异步实现）
     */
    fun search(query: String, limit: Int = 5): List<SearchResult> {
        return search(query, SearchConfig(), limit)
    }

    /**
     * 同步搜索方法（兼容包装，内部使用异步实现）
     *
     * ⚠️ **已弃用 - 性能警告**
     * 此方法内部使用 `runBlocking` 会阻塞调用线程，可能导致：
     * - 主线程卡顿（ANR 风险）
     * - 线程死锁
     * - 内存占用增加
     *
     * 请使用 [searchAsync] 替代，它返回协程安全的 suspend 函数。
     *
     * @deprecated 使用 [searchAsync] 以获得更好的性能和线程安全性
     * @see searchAsync
     */
    @Deprecated(
        message = "使用 runBlocking 阻塞线程，存在性能问题。请使用 searchAsync() 替代。",
        replaceWith = ReplaceWith("searchAsync(query, config, limit)", imports = ["kotlinx.coroutines.runBlocking"])
    )
    fun search(query: String, config: SearchConfig, limit: Int = 5): List<SearchResult> {
        return runBlocking {
            searchAsync(query, config, limit)
        }
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
