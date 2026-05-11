package top.hsyscn.opedrgent.network

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import top.hsyscn.opedrgent.utils.DebugLog
import java.net.URLEncoder
import java.net.URLDecoder

data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String?,
)

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

        fun containsChinese(s: String): Boolean = CN_PATTERN.containsMatchIn(s)
    }

    fun searchDuckDuckGo(query: String, limit: Int = 5): List<SearchResult> {
        val q = URLEncoder.encode(query, Charsets.UTF_8.name())
        val url = "https://lite.duckduckgo.com/lite/?q=$q"
        DebugLog.i("WebSearcher DDG Lite: $query")

        val req = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("搜索失败: HTTP ${resp.code}")
            }
            val body = resp.body?.string().orEmpty()
            val doc = Jsoup.parse(body)

            val rows = doc.select("tr.result-snippet")
            val out = ArrayList<SearchResult>()
            DebugLog.d("WebSearcher DDG Lite: ${rows.size} rows")

            for (row in rows) {
                if (out.size >= limit) break

                val link = row.selectFirst("a.result-link")
                    ?: row.selectFirst("a[rel=nofollow]")
                    ?: row.selectFirst("a")
                val rawHref = link?.attr("href").orEmpty()
                val title = link?.text()?.trim().orEmpty()

                if (rawHref.isBlank() || title.isBlank()) continue

                val href = resolveDdgUrl(rawHref)
                if (href.startsWith("https://duckduckgo.com")) continue

                val td = row.selectFirst("td")
                val fullText = td?.text().orEmpty()
                val snippet = fullText
                    .replace(title, "")
                    .trim()
                    .replace(Regex("\\s+"), " ")
                    .ifBlank { null }

                out.add(SearchResult(title = title, url = href, snippet = snippet))
            }

            if (out.isEmpty()) {
                DebugLog.w("WebSearcher DDG Lite: no results parsed")
            } else {
                DebugLog.i("WebSearcher DDG Lite: ${out.size} results")
            }
            out
        }
    }

    fun searchBingHtml(query: String, limit: Int = 5): List<SearchResult> {
        val q = URLEncoder.encode(query, Charsets.UTF_8.name())
        val url = "https://www.bing.com/search?q=$q&setlang=zh-CN"
        DebugLog.i("WebSearcher Bing HTML: $query")

        val req = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .build()

        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Bing 搜索失败: HTTP ${resp.code}")
            }
            val body = resp.body?.string().orEmpty()
            val doc = Jsoup.parse(body)

            val items = doc.select("li.b_algo")
            val out = ArrayList<SearchResult>()
            DebugLog.d("WebSearcher Bing HTML: ${items.size} b_algo items")

            for (item in items) {
                if (out.size >= limit) break

                val h2 = item.selectFirst("h2") ?: continue
                val a = h2.selectFirst("a") ?: continue
                val title = a.text().trim()
                var href = a.attr("href")

                if (href.isBlank() || title.isBlank()) continue

                if (href.startsWith("/")) {
                    href = "https://www.bing.com$href"
                }

                if (href.startsWith("https://www.bing.com/search")) continue

                val snippetEl = item.selectFirst(".b_caption p")
                    ?: item.selectFirst(".b_snippet")
                    ?: item.selectFirst("p")
                val snippet = snippetEl?.text()?.trim()?.ifBlank { null }

                out.add(SearchResult(title = title, url = href, snippet = snippet))
            }

            if (out.isEmpty()) {
                DebugLog.w("WebSearcher Bing HTML: no results")
            } else {
                DebugLog.i("WebSearcher Bing HTML: ${out.size} results")
            }
            out
        }
    }

    fun searchDual(queryZh: String, queryEn: String, limit: Int = 5): List<SearchResult> {
        val merged = LinkedHashMap<String, SearchResult>()

        runCatching {
            val ddgEn = searchDuckDuckGo(queryEn, limit)
            DebugLog.i("WebSearcher searchDual DDG(en): ${ddgEn.size} results for '$queryEn'")
            ddgEn.forEach { merged[it.url] = it }
        }.onFailure { DebugLog.w("WebSearcher searchDual DDG(en) failed: ${it.message}") }

        if (merged.size < limit) {
            runCatching {
                val ddgZh = searchDuckDuckGo(queryZh, limit)
                DebugLog.i("WebSearcher searchDual DDG(zh): ${ddgZh.size} results for '$queryZh'")
                ddgZh.forEach { merged[it.url] = it }
            }.onFailure { DebugLog.w("WebSearcher searchDual DDG(zh) failed: ${it.message}") }
        }

        if (merged.size < limit) {
            runCatching {
                val bingZh = searchBingHtml(queryZh, limit)
                DebugLog.i("WebSearcher searchDual Bing(zh): ${bingZh.size} results for '$queryZh'")
                bingZh.forEach { merged[it.url] = it }
            }.onFailure { DebugLog.w("WebSearcher searchDual Bing(zh) failed: ${it.message}") }
        }

        val results = merged.values.take(limit)
        DebugLog.i("WebSearcher searchDual total: ${results.size} deduplicated results")
        return results
    }

    fun search(query: String, limit: Int = 5): List<SearchResult> {
        return try {
            val ddg = searchDuckDuckGo(query, limit)
            if (ddg.isNotEmpty()) return ddg
            DebugLog.w("WebSearcher: DDG empty, trying Bing HTML")
            searchBingHtml(query, limit)
        } catch (e: Exception) {
            DebugLog.w("WebSearcher: DDG failed (${e.message}), trying Bing HTML")
            runCatching { searchBingHtml(query, limit) }.getOrDefault(emptyList())
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

    private fun resolveDdgUrl(rawHref: String): String {
        return when {
            rawHref.contains("uddg=") -> {
                runCatching {
                    val uddg = rawHref
                        .substringAfter("uddg=")
                        .substringBefore("&rut=")
                        .substringBefore("&")
                    URLDecoder.decode(uddg, "UTF-8")
                }.getOrDefault(rawHref)
            }
            rawHref.startsWith("//") -> "https:$rawHref"
            else -> rawHref
        }
    }
}
