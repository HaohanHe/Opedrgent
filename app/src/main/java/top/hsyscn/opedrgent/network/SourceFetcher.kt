package top.hsyscn.opedrgent.network

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

data class FetchedSource(
    val title: String?,
    val url: String,
    val text: String,
)

class SourceFetcher(private val http: OkHttpClient = HttpClients.default) {
    fun fetchUrl(url: String): FetchedSource {
        val req = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", "opedrgent/1.0")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("抓取失败: HTTP ${resp.code}")
            }
            val body = resp.body?.string().orEmpty()
            val doc = Jsoup.parse(body)
            val title = doc.title().takeIf { it.isNotBlank() }
            val text = doc.body()?.text()?.trim().orEmpty()
            return FetchedSource(title = title, url = url, text = text)
        }
    }
}
