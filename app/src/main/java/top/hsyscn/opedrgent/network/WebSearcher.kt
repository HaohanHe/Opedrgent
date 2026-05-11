package top.hsyscn.opedrgent.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import top.hsyscn.opedrgent.utils.DebugLog
import java.net.URLEncoder
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String?,
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
        private const val UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        private val CN_PATTERN = Regex("[\\u4e00-\\u9fa5]")
        private const val SEARCH_TIMEOUT_SEC = 8
        private const val SEARCH_CACHE_TTL_MS = 30_000L

        fun containsChinese(s: String): Boolean = CN_PATTERN.containsMatchIn(s)
    }

    private val searchCache = ConcurrentHashMap<String, Pair<Long, List<SearchResult>>>()

    private fun buildClient(timeoutSec: Int = SEARCH_TIMEOUT_SEC): OkHttpClient {
        return http.newBuilder()
            .connectTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .writeTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .build()
    }

    fun searchBingCn(query: String, limit: Int = 5): List<SearchResult> {
        val q = URLEncoder.encode(query, Charsets.UTF_8.name())
        val url = "https://cn.bing.com/search?q=$q&setlang=zh-CN"
        DebugLog.i("WebSearcher Bing CN: $query")

        val client = buildClient()
        val req = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .build()

        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                DebugLog.w("WebSearcher Bing CN: HTTP ${resp.code}")
                return emptyList()
            }
            val body = resp.body?.string().orEmpty()
            val doc = Jsoup.parse(body)

            val items = doc.select("li.b_algo")
            val out = ArrayList<SearchResult>()
            DebugLog.d("WebSearcher Bing CN: ${items.size} b_algo items")

            for (item in items) {
                if (out.size >= limit) break

                val h2 = item.selectFirst("h2")
                val title = h2?.text()?.trim() ?: continue
                val href = item.selectFirst("a")?.attr("href") ?: continue

                if (href.isNullOrBlank() || title.isBlank()) continue
                if (href.contains("baidu.com") && !href.startsWith("http")) continue

                val snippet = item.selectFirst("p.b_lineclamp3, p.b_snippet, div.b_caption p")?.text()?.trim()?.ifBlank { null }
                    ?: h2?.text()?.substringAfter(title)?.take(150)?.ifEmpty { null }

                out.add(SearchResult(title = title, url = href, snippet = snippet))
            }

            if (out.isEmpty()) {
                DebugLog.w("WebSearcher Bing CN: no results")
            } else {
                DebugLog.i("WebSearcher Bing CN: ${out.size} results")
            }
            out
        }
    }

    fun searchSearxng(query: String, limit: Int = 5): List<SearchResult> {
        if (SEARXNG_BASE_URL.isBlank()) return emptyList()

        val q = URLEncoder.encode(query, "UTF-8")
        val url = "$SEARXNG_BASE_URL/search?q=$q&format=json&pageno=1&safesearch=1&language=all&theme=simple"
        DebugLog.i("WebSearcher SearXNG: $url")

        val req = Request.Builder().url(url).get()
            .header("User-Agent", "Mozilla/5.0 Opedrgent/1.0")
            .build()

        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                DebugLog.w("WebSearcher SearXNG failed: HTTP ${resp.code}")
                return emptyList()
            }
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) return emptyList()

            try {
                val json = org.json.JSONObject(body)
                val results = json.optJSONArray("results") ?: return emptyList()
                val out = ArrayList<SearchResult>()
                for (i in 0 until results.length()) {
                    if (out.size >= limit) break
                    val item = results.getJSONObject(i)
                    val title = item.optString("title", "").trim()
                    val href = item.optString("url", "").trim()
                    val content = item.optString("content", "").trim()
                    if (href.isBlank() || title.isBlank()) continue
                    out.add(SearchResult(title = title, url = href, snippet = content.ifBlank { null }))
                }
                DebugLog.i("WebSearcher SearXNG: ${out.size} results")
                out
            } catch (e: Exception) {
                DebugLog.w("WebSearcher SearXNG parse error: ${e.message}")
                emptyList()
            }
        }
    }

    fun searchBaidu(query: String, limit: Int = 5): List<SearchResult> {
        val q = URLEncoder.encode(query, Charsets.UTF_8.name())
        val url = "https://www.baidu.com/s?wd=$q&rn=$limit"
        DebugLog.i("WebSearcher Baidu: $query")

        val client = buildClient()
        val req = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .build()

        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                DebugLog.w("WebSearcher Baidu: HTTP ${resp.code}")
                return emptyList()
            }
            val body = resp.body?.string().orEmpty()
            val doc = Jsoup.parse(body)

            val items = doc.select("div.result, div.c-container")
            val out = ArrayList<SearchResult>()
            DebugLog.d("WebSearcher Baidu: ${items.size} result items")

            for (item in items) {
                if (out.size >= limit) break

                val a = item.selectFirst("h3 a") ?: item.selectFirst("a")
                val title = a?.text()?.trim() ?: continue
                val href = a?.attr("href") ?: continue

                if (href.isBlank() || title.isBlank()) continue
                if (href.contains("baidu.com") && !href.startsWith("http")) continue

                val snippetEl = item.selectFirst(".c-abstract, .content-right_8Zs40, .content_3Zs, p")
                val snippet = snippetEl?.text()?.trim()?.ifBlank { null }
                    ?: item.text().take(200).let { it.substringAfter(title).take(150).ifEmpty { null } }

                out.add(SearchResult(title = title, url = href, snippet = snippet))
            }

            if (out.isEmpty()) {
                DebugLog.w("WebSearcher Baidu: no results")
            } else {
                DebugLog.i("WebSearcher Baidu: ${out.size} results")
            }
            out
        }
    }

    fun search(query: String, limit: Int = 5): List<SearchResult> {
        val key = "${query.lowercase().trim()}|$limit"
        val cached = searchCache[key]
        if (cached != null && System.currentTimeMillis() - cached.first < SEARCH_CACHE_TTL_MS) {
            DebugLog.i("WebSearcher: cache hit for '${query.take(50)}'")
            return cached.second
        }

        val isZh = containsChinese(query)
        val q = if (isZh) query else "$query site:zh.wikipedia.org OR site:en.wikipedia.org"

        val searxng = runCatching { searchSearxng(query, limit) }.getOrNull()
        if (!searxng.isNullOrEmpty()) {
            DebugLog.i("WebSearcher: SearXNG returned ${searxng.size} results")
            searchCache[key] = Pair(System.currentTimeMillis(), searxng)
            return searxng
        }

        val bing = runCatching { searchBingCn(query, limit) }.getOrNull()
        if (!bing.isNullOrEmpty()) {
            DebugLog.i("WebSearcher: Bing CN returned ${bing.size} results")
            searchCache[key] = Pair(System.currentTimeMillis(), bing)
            return bing
        }

        val baidu = runCatching { searchBaidu(q, limit) }.getOrNull()
        if (!baidu.isNullOrEmpty()) {
            DebugLog.i("WebSearcher: Baidu returned ${baidu.size} results")
            searchCache[key] = Pair(System.currentTimeMillis(), baidu)
            return baidu
        }

        val jina = runCatching { searchJina(query, limit) }.getOrNull()
        if (!jina.isNullOrEmpty()) {
            DebugLog.i("WebSearcher: Jina Search returned ${jina.size} results")
            searchCache[key] = Pair(System.currentTimeMillis(), jina)
            return jina
        }

        DebugLog.w("WebSearcher: all engines failed, returning empty")
        return emptyList()
    }

    fun searchJina(query: String, limit: Int = 5): List<SearchResult> {
        val url = "https://s.jina.ai/search"
        val jsonBody = """{"q":"$query","count":$limit}"""
        DebugLog.i("WebSearcher Jina Search: $query")

        val req = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", "Opedrgent/1.0")
            .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                DebugLog.w("WebSearcher Jina Search failed: HTTP ${resp.code}")
                return emptyList()
            }
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) return emptyList()

            try {
                val json = org.json.JSONObject(body)
                val data = json.optJSONArray("data") ?: return emptyList()
                val out = ArrayList<SearchResult>()
                for (i in 0 until data.length()) {
                    if (out.size >= limit) break
                    val item = data.getJSONObject(i)
                    val title = item.optString("title", "").trim()
                    val href = item.optString("url", "").trim()
                    val snippet = item.optString("description", "").trim()
                        .ifBlank { item.optString("content", "").trim().take(200).ifBlank { null } }
                    if (href.isBlank() || title.isBlank()) continue
                    out.add(SearchResult(title = title, url = href, snippet = snippet))
                }
                DebugLog.i("WebSearcher Jina Search: ${out.size} results")
                out
            } catch (e: Exception) {
                DebugLog.w("WebSearcher Jina Search parse error: ${e.message}")
                emptyList()
            }
        }
    }

    fun fetchViaJina(url: String): JinaResult? {
        DebugLog.i("WebSearcher Jina: $url")
        val jinaUrl = "https://r.jina.ai/$url"
        val req = Request.Builder()
            .url(jinaUrl)
            .get()
            .header("User-Agent", "Opedrgent/1.0")
            .header("Accept", "text/plain")
            .header("X-No-Cache", "true")
            .build()

        return http.newCall(req).execute().use { resp ->
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
}
