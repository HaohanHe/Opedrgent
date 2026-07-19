package top.hsyscn.opedrgent.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import java.util.Base64
import java.util.concurrent.TimeUnit
import okhttp3.Dns

/**
 * WebSearcher — facade over the multi-engine search subsystem.
 *
 * Public API and per-engine entry points are preserved here. Internal
 * responsibilities are delegated to focused components:
 *  - [SearchCacheManager]      — LRU caches (results + DDG vqd) and stats
 *  - [SearchResultRanker]      — relevance filter + hybrid ranking fusion
 *  - [SearchDeduplicator]      — URL/content dedup with diagnostics
 *  - [SearchConstants]         — timeouts, cache sizes, scoring thresholds
 *  - [MultiLevelCacheManager]  — L1 memory + L2 disk cache for resilient path
 *
 * Data models ([SearchResult], [SearchConfig], [EngineConfig], etc.) and the
 * [SEARXNG_BASE_URL] global live in SearchConfig.kt within the same package.
 */
class WebSearcher(private val http: OkHttpClient = HttpClients.default) {

    companion object {
        /** Backwards-compatible alias for [SearchConstants.containsChinese]. */
        fun containsChinese(s: String): Boolean = SearchConstants.containsChinese(s)
    }

    // ---- Delegated components ----
    private val cacheManager: MultiLevelCacheManager by lazy { MultiLevelCacheManager() }
    private val legacyCache: SearchCacheManager by lazy { SearchCacheManager() }
    private val ranker: SearchResultRanker by lazy { SearchResultRanker() }
    private val deduplicator: SearchDeduplicator by lazy { SearchDeduplicator() }

    private val circuitBreakerManager: CircuitBreakerManager get() = CircuitBreakerManager
    private val concurrencyController: AdaptiveConcurrencyController by lazy { AdaptiveConcurrencyController() }
    private val errorClassifier: ErrorClassifier get() = ErrorClassifier
    private val cacheKeyGenerator: CacheKeyGenerator get() = CacheKeyGenerator

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
            .connectTimeout(SearchConstants.JINA_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(SearchConstants.JINA_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .writeTimeout(SearchConstants.JINA_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .callTimeout(SearchConstants.JINA_CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()
    }

    private val geocodingClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(SearchConstants.GEOCODE_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(SearchConstants.GEOCODE_READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .writeTimeout(SearchConstants.GEOCODE_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()
    }

    private val geocodeCache = mutableMapOf<String, Pair<String?, Long>>()

    private fun buildClient(timeoutSec: Int = SearchConstants.SEARCH_TIMEOUT_SEC): OkHttpClient {
        return http.newBuilder()
            .connectTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .writeTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .build()
    }

    fun searchDdg(query: String, limit: Int = 5): List<SearchResult> {
        if (query.length >= 500) return emptyList()

        // 引擎状态检查
        if (!CircuitBreakerManager.getOrCreate("ddg").allowRequest()) {
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
        legacyCache.cleanExpiredVqdCache()

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
                        429 -> CircuitBreakerManager.getOrCreate("ddg").recordFailure(Exception("Rate limited (429)"))
                        403 -> CircuitBreakerManager.getOrCreate("ddg").recordFailure(Exception("Forbidden (403)"))
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
                    CircuitBreakerManager.getOrCreate("ddg").recordFailure(Exception("CAPTCHA detected"))
                    return@use emptyList()
                }

                val vqdInput = doc.selectFirst("input[name=vqd]")
                if (vqdInput != null) {
                    val vqdValue = vqdInput.attr("value").trim()
                    if (vqdValue.isNotEmpty()) {
                        legacyCache.putVqd(query, UserAgentPool.getFixedUa(), vqdValue)
                    }
                }

                val items = doc.select("#links div.web-result")
                val out = ArrayList<SearchResult>()
                DebugLog.d("WebSearcher DDG: ${items.size} web-result items")

                for (item in items) {
                    if (out.size >= limit) break

                    val h2 = item.selectFirst("h2 a")
                    val title = h2?.text()?.trim() ?: continue
                    var href = h2.attr("href") ?: continue

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
            val classifiedError = errorClassifier.classify(e)
            if (classifiedError.shouldTriggerCircuitBreaker) {
                CircuitBreakerManager.getOrCreate("ddg").recordFailure(e)
            }
            DebugLog.w("WebSearcher ddg: ${errorClassifier.formatForLog(classifiedError)}")
            emptyList()
        } catch (e: javax.net.ssl.SSLException) {
            DebugLog.e("WebSearcher DDG SSL error: ${e.message}")
            val classifiedError = errorClassifier.classify(e)
            if (classifiedError.shouldTriggerCircuitBreaker) {
                CircuitBreakerManager.getOrCreate("ddg").recordFailure(e)
            }
            DebugLog.w("WebSearcher ddg: ${errorClassifier.formatForLog(classifiedError)}")
            emptyList()
        } catch (e: java.net.UnknownHostException) {
            DebugLog.e("WebSearcher DDG DNS error: ${e.message}")
            val classifiedError = errorClassifier.classify(e)
            if (classifiedError.shouldTriggerCircuitBreaker) {
                CircuitBreakerManager.getOrCreate("ddg").recordFailure(e)
            }
            DebugLog.w("WebSearcher ddg: ${errorClassifier.formatForLog(classifiedError)}")
            emptyList()
        } catch (e: Exception) {
            DebugLog.e("WebSearcher DDG error: ${e.javaClass.simpleName} - ${e.message}")
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
        if (!CircuitBreakerManager.getOrCreate("bing").allowRequest()) {
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
                        429 -> CircuitBreakerManager.getOrCreate("bing").recordFailure(Exception("Rate limited (429)"))
                        403 -> CircuitBreakerManager.getOrCreate("bing").recordFailure(Exception("Forbidden (403)"))
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
                    val link = h2.selectFirst("a") ?: item.selectFirst("a")
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
            val classifiedError = errorClassifier.classify(e)
            if (classifiedError.shouldTriggerCircuitBreaker) {
                CircuitBreakerManager.getOrCreate("bing").recordFailure(e)
            }
            DebugLog.w("WebSearcher bing: ${errorClassifier.formatForLog(classifiedError)}")
            emptyList()
        } catch (e: javax.net.ssl.SSLException) {
            DebugLog.e("WebSearcher Bing SSL error: ${e.message}")
            val classifiedError = errorClassifier.classify(e)
            if (classifiedError.shouldTriggerCircuitBreaker) {
                CircuitBreakerManager.getOrCreate("bing").recordFailure(e)
            }
            DebugLog.w("WebSearcher bing: ${errorClassifier.formatForLog(classifiedError)}")
            emptyList()
        } catch (e: java.net.UnknownHostException) {
            DebugLog.e("WebSearcher Bing DNS error: ${e.message}")
            val classifiedError = errorClassifier.classify(e)
            if (classifiedError.shouldTriggerCircuitBreaker) {
                CircuitBreakerManager.getOrCreate("bing").recordFailure(e)
            }
            DebugLog.w("WebSearcher bing: ${errorClassifier.formatForLog(classifiedError)}")
            emptyList()
        } catch (e: Exception) {
            DebugLog.e("WebSearcher Bing error: ${e.javaClass.simpleName} - ${e.message}")
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
        if (!CircuitBreakerManager.getOrCreate("baidu").allowRequest()) {
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
                        429 -> CircuitBreakerManager.getOrCreate("baidu").recordFailure(Exception("Rate limited (429)"))
                        403 -> CircuitBreakerManager.getOrCreate("baidu").recordFailure(Exception("Forbidden (403)"))
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
                    CircuitBreakerManager.getOrCreate("baidu").recordFailure(Exception("CAPTCHA: security verification"))
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
            val classifiedError = errorClassifier.classify(e)
            if (classifiedError.shouldTriggerCircuitBreaker) {
                CircuitBreakerManager.getOrCreate("baidu").recordFailure(e)
            }
            DebugLog.w("WebSearcher baidu: ${errorClassifier.formatForLog(classifiedError)}")
            emptyList()
        } catch (e: javax.net.ssl.SSLException) {
            DebugLog.e("WebSearcher Baidu JSON SSL error: ${e.message}")
            val classifiedError = errorClassifier.classify(e)
            if (classifiedError.shouldTriggerCircuitBreaker) {
                CircuitBreakerManager.getOrCreate("baidu").recordFailure(e)
            }
            DebugLog.w("WebSearcher baidu: ${errorClassifier.formatForLog(classifiedError)}")
            emptyList()
        } catch (e: java.net.UnknownHostException) {
            DebugLog.e("WebSearcher Baidu JSON DNS error: ${e.message}")
            val classifiedError = errorClassifier.classify(e)
            if (classifiedError.shouldTriggerCircuitBreaker) {
                CircuitBreakerManager.getOrCreate("baidu").recordFailure(e)
            }
            DebugLog.w("WebSearcher baidu: ${errorClassifier.formatForLog(classifiedError)}")
            emptyList()
        } catch (e: Exception) {
            DebugLog.e("WebSearcher Baidu JSON error: ${e.javaClass.simpleName} - ${e.message}")
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
                    CircuitBreakerManager.getOrCreate("baidu").recordFailure(Exception("CAPTCHA: security verification"))
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
                    var href = h3.attr("href") ?: continue

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
        if (!CircuitBreakerManager.getOrCreate("searxng").allowRequest()) {
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
                        429 -> CircuitBreakerManager.getOrCreate("searxng").recordFailure(Exception("Rate limited (429)"))
                        403 -> CircuitBreakerManager.getOrCreate("searxng").recordFailure(Exception("Forbidden (403)"))
                        500, 502, 503 -> CircuitBreakerManager.getOrCreate("searxng").recordFailure(Exception("Server error (${resp.code})"))
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
                        CircuitBreakerManager.getOrCreate("searxng").recordFailure(Exception(errorMessage))
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
                                snippet = content.let { StringUtils.sanitizeJsonNull(it) },
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
            val classifiedError = errorClassifier.classify(e)
            if (classifiedError.shouldTriggerCircuitBreaker) {
                CircuitBreakerManager.getOrCreate("searxng").recordFailure(e)
            }
            DebugLog.w("WebSearcher searxng: ${errorClassifier.formatForLog(classifiedError)}")
            emptyList()
        } catch (e: javax.net.ssl.SSLException) {
            DebugLog.e("WebSearcher SearXNG SSL error: ${e.message}")
            val classifiedError = errorClassifier.classify(e)
            if (classifiedError.shouldTriggerCircuitBreaker) {
                CircuitBreakerManager.getOrCreate("searxng").recordFailure(e)
            }
            DebugLog.w("WebSearcher searxng: ${errorClassifier.formatForLog(classifiedError)}")
            emptyList()
        } catch (e: java.net.UnknownHostException) {
            DebugLog.e("WebSearcher SearXNG DNS error: ${e.message}")
            val classifiedError = errorClassifier.classify(e)
            if (classifiedError.shouldTriggerCircuitBreaker) {
                CircuitBreakerManager.getOrCreate("searxng").recordFailure(e)
            }
            DebugLog.w("WebSearcher searxng: ${errorClassifier.formatForLog(classifiedError)}")
            emptyList()
        } catch (e: Exception) {
            DebugLog.e("WebSearcher SearXNG error: ${e.javaClass.simpleName} - ${e.message}")
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

    /**
     * Tavily Search API (2026年6月最新文档)
     *
     * 支持参数：search_depth, topic, time_range, country,
     * include_answer, include_raw_content, include_images,
     * include_domains, exclude_domains, exact_match, auto_parameters
     */
    fun searchTavily(
        query: String,
        limit: Int = 5,
        apiKey: String,
        topic: String = "general",        // general / news / finance
        timeRange: String? = null,         // day / week / month / year
        country: String? = null,           // china / united_states / ...
        includeAnswer: Boolean = true,
        includeRawContent: Boolean = false,
        searchDepth: String = "basic",     // basic / advanced / fast / ultra-fast
        includeDomains: List<String>? = null,
        excludeDomains: List<String>? = null,
    ): TavilySearchResponse {
        val emptyResponse = TavilySearchResponse(emptyList(), null)
        if (apiKey.isBlank()) return emptyResponse
        val url = "https://api.tavily.com/search"

        val jsonBody = org.json.JSONObject().apply {
            put("query", query)
            put("max_results", limit)
            put("search_depth", searchDepth)
            put("topic", topic)
            put("include_answer", includeAnswer)
            put("include_raw_content", includeRawContent)
            put("include_images", false)
            put("auto_parameters", false)
            if (timeRange != null) put("time_range", timeRange)
            if (country != null) put("country", country)
            if (!includeDomains.isNullOrEmpty()) put("include_domains", org.json.JSONArray(includeDomains))
            if (!excludeDomains.isNullOrEmpty()) put("exclude_domains", org.json.JSONArray(excludeDomains))
        }

        DebugLog.i("WebSearcher Tavily: query='$query', depth=$searchDepth, topic=$topic, time=$timeRange, country=$country")

        val req = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .header("User-Agent", UserAgentPool.generate())
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errorBody = resp.body?.string().orEmpty()
                    DebugLog.w("WebSearcher Tavily failed: HTTP ${resp.code} - $errorBody")
                    return@use emptyResponse
                }
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) {
                    DebugLog.w("WebSearcher Tavily: empty response body")
                    return@use emptyResponse
                }

                try {
                    val json = org.json.JSONObject(body)

                    if (json.has("error")) {
                        val errorMsg = json.optString("error", "Unknown error")
                        DebugLog.w("WebSearcher Tavily API error: $errorMsg")
                        return@use emptyResponse
                    }

                    // 提取 Tavily 生成的摘要答案
                    val answer = json.optString("answer", "").ifBlank { null }
                    if (answer != null) {
                        DebugLog.i("WebSearcher Tavily: got answer (${answer.length} chars)")
                    }

                    // 提取自动参数（如果启用了 auto_parameters）
                    val autoParams = json.optJSONObject("auto_parameters")
                    if (autoParams != null) {
                        DebugLog.d("WebSearcher Tavily: auto_params=$autoParams")
                    }

                    val results = json.optJSONArray("results") ?: run {
                        DebugLog.w("WebSearcher Tavily: no 'results' array in response")
                        return@use emptyResponse.copy(answer = answer)
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
                            val rawContent = item.optString("raw_content", "").ifBlank { null }
                            val score = item.optDouble("score", 0.0)

                            if (href.isBlank() || title.isBlank()) continue

                            out.add(SearchResult(
                                title = StringUtils.sanitizeJsonNull(title),
                                url = href,
                                snippet = snippet?.let { StringUtils.sanitizeJsonNull(it) },
                                sourceEngines = setOf("tavily"),
                                score = score,
                                rawContent = rawContent,
                            ))
                        } catch (e: Exception) {
                            DebugLog.w("WebSearcher Tavily: failed to parse result[$i]: ${e.message}")
                            continue
                        }
                    }

                    if (out.isNotEmpty()) {
                        DebugLog.i("WebSearcher Tavily: ${out.size} results, answer=${answer != null}")
                    } else {
                        DebugLog.w("WebSearcher Tavily: parsed 0 results from ${results.length()} items")
                    }
                    TavilySearchResponse(out, answer)
                } catch (e: Exception) {
                    DebugLog.e("WebSearcher Tavily JSON parse error: ${e.message}\nResponse: ${body.take(500)}")
                    emptyResponse
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            DebugLog.e("WebSearcher Tavily timeout: ${e.message}")
            emptyResponse
        } catch (e: javax.net.ssl.SSLException) {
            DebugLog.e("WebSearcher Tavily SSL error: ${e.message}")
            emptyResponse
        } catch (e: java.net.UnknownHostException) {
            DebugLog.e("WebSearcher Tavily DNS error: ${e.message}")
            emptyResponse
        } catch (e: Exception) {
            DebugLog.e("WebSearcher Tavily error: ${e.javaClass.simpleName} - ${e.message}")
            emptyResponse
        }
    }

    /**
     * Tavily Extract API — 从指定 URL 提取干净的网页内容
     *
     * 比 read_url 更干净，支持 chunks、markdown/text 格式
     */
    fun extractTavily(
        urls: List<String>,
        apiKey: String,
        extractDepth: String = "basic",   // basic / advanced
        query: String? = null,
    ): List<TavilyExtractResult> {
        if (apiKey.isBlank() || urls.isEmpty()) return emptyList()
        val url = "https://api.tavily.com/extract"

        val jsonBody = org.json.JSONObject().apply {
            put("urls", org.json.JSONArray(urls))
            put("extract_depth", extractDepth)
            put("format", "markdown")
            put("include_images", false)
            if (query != null) put("query", query)
        }

        DebugLog.i("WebSearcher TavilyExtract: ${urls.size} urls, depth=$extractDepth")

        val req = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .header("User-Agent", UserAgentPool.generate())
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errorBody = resp.body?.string().orEmpty()
                    DebugLog.w("WebSearcher TavilyExtract failed: HTTP ${resp.code} - $errorBody")
                    return@use emptyList()
                }
                val body = resp.body?.string().orEmpty()
                val json = org.json.JSONObject(body)
                val results = json.optJSONArray("results") ?: return@use emptyList()

                val out = ArrayList<TavilyExtractResult>()
                for (i in 0 until results.length()) {
                    val item = results.getJSONObject(i)
                    out.add(TavilyExtractResult(
                        url = item.optString("url", ""),
                        rawContent = item.optString("raw_content", ""),
                    ))
                }
                DebugLog.i("WebSearcher TavilyExtract: ${out.size} results")
                out
            }
        } catch (e: Exception) {
            DebugLog.e("WebSearcher TavilyExtract error: ${e.message}")
            emptyList()
        }
    }

    // ==================== Yandex 搜索 ====================
    fun searchYandex(query: String, limit: Int = 5): List<SearchResult> {
        if (query.length >= 500) return emptyList()

        if (!CircuitBreakerManager.getOrCreate("yandex").allowRequest()) {
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
                        429 -> CircuitBreakerManager.getOrCreate("yandex").recordFailure(Exception("Rate limited (429)"))
                        403 -> CircuitBreakerManager.getOrCreate("yandex").recordFailure(Exception("Forbidden (403)"))
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
                        var href = titleEl.attr("href") ?: 
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
            val classifiedError = errorClassifier.classify(e)
            if (classifiedError.shouldTriggerCircuitBreaker) {
                CircuitBreakerManager.getOrCreate("yandex").recordFailure(e)
            }
            DebugLog.w("WebSearcher yandex: ${errorClassifier.formatForLog(classifiedError)}")
            emptyList()
        } catch (e: javax.net.ssl.SSLException) {
            DebugLog.e("WebSearcher Yandex SSL error: ${e.message}")
            val classifiedError = errorClassifier.classify(e)
            if (classifiedError.shouldTriggerCircuitBreaker) {
                CircuitBreakerManager.getOrCreate("yandex").recordFailure(e)
            }
            DebugLog.w("WebSearcher yandex: ${errorClassifier.formatForLog(classifiedError)}")
            emptyList()
        } catch (e: java.net.UnknownHostException) {
            DebugLog.e("WebSearcher Yandex DNS error: ${e.message}")
            val classifiedError = errorClassifier.classify(e)
            if (classifiedError.shouldTriggerCircuitBreaker) {
                CircuitBreakerManager.getOrCreate("yandex").recordFailure(e)
            }
            DebugLog.w("WebSearcher yandex: ${errorClassifier.formatForLog(classifiedError)}")
            emptyList()
        } catch (e: Exception) {
            DebugLog.e("WebSearcher Yandex error: ${e.javaClass.simpleName} - ${e.message}")
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

        if (!CircuitBreakerManager.getOrCreate("sogou").allowRequest()) {
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
                        var href = titleEl.attr("href") ?: continue

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
            val classifiedError = errorClassifier.classify(e)
            if (classifiedError.shouldTriggerCircuitBreaker) {
                CircuitBreakerManager.getOrCreate("sogou").recordFailure(e)
            }
            DebugLog.w("WebSearcher sogou: ${errorClassifier.formatForLog(classifiedError)}")
            emptyList()
        } catch (e: javax.net.ssl.SSLException) {
            DebugLog.e("WebSearcher Sogou SSL error: ${e.message}")
            val classifiedError = errorClassifier.classify(e)
            if (classifiedError.shouldTriggerCircuitBreaker) {
                CircuitBreakerManager.getOrCreate("sogou").recordFailure(e)
            }
            DebugLog.w("WebSearcher sogou: ${errorClassifier.formatForLog(classifiedError)}")
            emptyList()
        } catch (e: Exception) {
            DebugLog.e("WebSearcher Sogou error: ${e.javaClass.simpleName} - ${e.message}")
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

        if (!CircuitBreakerManager.getOrCreate("360").allowRequest()) {
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
                        var href = titleEl.attr("href") ?: continue

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
                        val classifiedError = errorClassifier.classify(e)
            if (classifiedError.shouldTriggerCircuitBreaker) {
                CircuitBreakerManager.getOrCreate("360").recordFailure(e)
            }
            DebugLog.w("WebSearcher 360: ${errorClassifier.formatForLog(classifiedError)}")
            emptyList()
        } catch (e: javax.net.ssl.SSLException) {
            DebugLog.e("WebSearcher 360 SSL error: ${e.message}")
                        val classifiedError = errorClassifier.classify(e)
            if (classifiedError.shouldTriggerCircuitBreaker) {
                CircuitBreakerManager.getOrCreate("360").recordFailure(e)
            }
            DebugLog.w("WebSearcher 360: ${errorClassifier.formatForLog(classifiedError)}")
            emptyList()
        } catch (e: Exception) {
            DebugLog.e("WebSearcher 360 error: ${e.javaClass.simpleName} - ${e.message}")
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
            if (System.currentTimeMillis() - timestamp < SearchConstants.GEOCACHE_TTL_MS) {
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

        ranker.initialize(query)

        val isZh = containsChinese(query)
        val q = query

        val container = SearchResultContainer()
        container.setQueryKeywords(query)

        val providers = config.providerOrder.split(",").map { it.trim().lowercase() }

        // ★ Bugfix: 记录本次搜索尝试的引擎列表，方便排查静默失败
        DebugLog.i("WebSearcher resilience: 启动 ${providers.size} 个引擎并行搜索: ${providers.joinToString(",")} (query=${q.take(60)})")

        val engineStatus = mutableMapOf<String, String>()  // engine -> status

        coroutineScope {
            val deferredResults = providers.map { provider ->
                async(Dispatchers.IO) {
                    concurrencyController.withEngineAccess(provider, priority) {
                        val breaker = circuitBreakerManager.getOrCreate(provider)
                        if (!breaker.allowRequest()) {
                            engineStatus[provider] = "SKIPPED(circuit-open)"
                            DebugLog.w("WebSearcher: circuit OPEN for $provider, skipping")
                            return@withEngineAccess null
                        }

                        // ★ 每个引擎启动时记录，排查 DDG 等静默跳过问题
                        DebugLog.d("WebSearcher: 开始执行 $provider 引擎")

                        try {
                            val results = when (provider) {
                                "searxng" -> {
                                    val url = config.searxngUrl ?: SEARXNG_BASE_URL
                                    if (url.isBlank()) { engineStatus[provider] = "SKIP(no-url)"; null }
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
                                "jina" -> {
                                    // ★ Bugfix: 无 API Key 时跳过 Jina（必然超时）
                                    val apiKey = config.jinaApiKey
                                    if (apiKey.isNullOrBlank()) {
                                        engineStatus[provider] = "SKIP(no-api-key)"
                                        DebugLog.i("WebSearcher: Jina 跳过（未配置 API Key）")
                                        null
                                    } else {
                                        runCatching { searchJina(query, limit, apiKey) }.getOrNull()
                                    }
                                }
                                "brave" -> {
                                    val key = config.braveApiKey ?: ""
                                    if (key.isBlank()) { engineStatus[provider] = "SKIP(no-api-key)"; null }
                                    else runCatching { searchBrave(query, limit, key) }.getOrNull()
                                }
                                "tavily" -> {
                                    val key = config.tavilyApiKey ?: ""
                                    if (key.isBlank()) { engineStatus[provider] = "SKIP(no-api-key)"; null }
                                    else runCatching { searchTavily(query, limit, key, country = "china").results }.getOrNull()
                                }
                                "yandex" -> runCatching { searchYandex(query, limit) }.getOrNull()
                                "sogou" -> runCatching { searchSogou(query, limit) }.getOrNull()
                                "360", "so", "so.com" -> runCatching { search360(query, limit) }.getOrNull()
                                else -> { engineStatus[provider] = "SKIP(unknown)"; null }
                            }

                            if (!results.isNullOrEmpty()) {
                                breaker.recordSuccess()
                                engineStatus[provider] = "OK(${results.size})"
                                results
                            } else {
                                engineStatus[provider] = "EMPTY"
                                null
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            val classifiedError = errorClassifier.classify(e)
                            if (classifiedError.shouldTriggerCircuitBreaker) {
                                breaker.recordFailure(e)
                            }
                            engineStatus[provider] = "ERR(${classifiedError.type.name})"
                            DebugLog.w("WebSearcher $provider: ${errorClassifier.formatForLog(classifiedError)}")
                            null
                        }
                    }
                }
            }
            val allResults = deferredResults.awaitAll().filterNotNull()

            // ★ 结果汇总日志：每个引擎的执行状态一目了然
            DebugLog.i("WebSearcher resilience: 引擎状态汇总: ${engineStatus.entries.joinToString(",") { "${it.key}=${it.value}" }}")

            allResults.forEach { providerResults ->
                if (providerResults.isNotEmpty()) {
                    val providerName = providerResults.firstOrNull()?.sourceEngines?.firstOrNull() ?: "unknown"
                    container.addResults(providerName, providerResults)
                    DebugLog.i("WebSearcher resilience: $providerName returned ${providerResults.size} results")
                }
            }
        }

        if (container.getSortedResults(limit).isEmpty()) {
            // ★ Bugfix: Jina 无 Key 时不做无效的 fallback 超时尝试
            if (!config.jinaApiKey.isNullOrBlank()) {
                DebugLog.w("WebSearcher resilience: all main engines failed, trying Jina fallback")
                val jinaFallback = runCatching { searchJina(query, limit, config.jinaApiKey) }.getOrNull()
                if (!jinaFallback.isNullOrEmpty()) {
                    container.addResults("jina-fallback", jinaFallback)
                }
            } else {
                DebugLog.w("WebSearcher resilience: 所有引擎均未返回结果，且无可用 fallback")
            }
        }

        // 后处理管线：过滤 + 排序 -> 去重 -> 混合排名
        var finalResults = ranker.filterAndSort(
            container = container,
            limit = limit.coerceAtMost(SearchConstants.MAX_LIMIT_CAP)
        )

        if (finalResults.isNotEmpty()) {
            finalResults = deduplicator.deduplicate(finalResults)
        }

        if (finalResults.isNotEmpty()) {
            finalResults = ranker.rank(finalResults, limit)
        }

        if (finalResults.isNotEmpty()) {
            val resultSet = SearchResultSet(
                results = finalResults,
                timestamp = System.currentTimeMillis(),
                query = query,
                providerOrder = config.providerOrder
            )
            cacheManager.put(cacheKey, resultSet)

            legacyCache.putToCache("${query.lowercase().trim()}|$limit|${config.providerOrder}", finalResults)
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
    fun getCacheStats(): Map<String, Any> = legacyCache.getCacheStats()

    /**
     * 同步搜索方法（兼容包装，内部使用异步实现）
     */
    suspend fun search(query: String, limit: Int = 5): List<SearchResult> {
        return searchAsync(query, SearchConfig(), limit)
    }
}
