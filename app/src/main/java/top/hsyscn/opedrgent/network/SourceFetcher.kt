package top.hsyscn.opedrgent.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

data class FetchedSource(
    val title: String?,
    val url: String,
    val text: String,
    /** 网页描述（meta description） */
    val description: String? = null,
    /** 作者 */
    val author: String? = null,
    /** 发布时间 */
    val publishTime: String? = null,
)

class SourceFetcher(private val http: OkHttpClient = HttpClients.default) {

    companion object {
        private const val MAX_BODY_BYTES = 5 * 1024 * 1024 // 5MB 限制
        private const val RETRY_DELAY_MS = 1000L
        private const val CACHE_TTL_MS = 10 * 60 * 1000L // 10 分钟缓存
        private const val MAX_CACHE_SIZE = 50

        /** 更真实的 Chrome User-Agent，降低被反爬拦截的概率 */
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        /** 应该被移除的噪声标签 */
        private val NOISE_TAGS = arrayOf(
            "nav", "footer", "header", "aside", "script", "style",
            "noscript", "iframe", "form", "button", "input",
            "advertisement", "sidebar", "menu", "breadcrumb",
        )

        /** 内容密度评分的标签权重 */
        private val CONTENT_WEIGHTS = mapOf(
            "article" to 30,
            "main" to 25,
            "section" to 10,
            "p" to 3,
            "pre" to 5,
            "blockquote" to 5,
            "h1" to 5, "h2" to 5, "h3" to 3,
            "li" to 1,
            "td" to 1,
            "div" to 1,
        )
    }

    /** 内容缓存：url -> (timestamp, fetchedSource) */
    private val cache = LinkedHashMap<String, Pair<Long, FetchedSource>>(16, 0.75f, true)

    suspend fun fetchUrl(url: String): FetchedSource {
        // 入口安全校验（U35-04）：仅允许 http/https，拒绝回环/私网/链路本地地址，防 SSRF
        assertPublicHttpUrl(url)

        // 检查缓存
        synchronized(cache) {
            cache[url]?.let { (ts, cached) ->
                if (System.currentTimeMillis() - ts < CACHE_TTL_MS) {
                    return cached
                }
                cache.remove(url)
            }
        }

        return withContext(Dispatchers.IO) {
            var lastException: Exception? = null

            for (attempt in 0..NetworkConfig.RETRY_COUNT) {
                try {
                    val result = fetchUrlInternal(url)
                    // 写入缓存
                    synchronized(cache) {
                        if (cache.size >= MAX_CACHE_SIZE) {
                            val oldest = cache.entries.first()
                            cache.remove(oldest.key)
                        }
                        cache[url] = System.currentTimeMillis() to result
                    }
                    return@withContext result
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastException = e
                    if (attempt < NetworkConfig.RETRY_COUNT && isRetryable(e)) {
                        delay(RETRY_DELAY_MS * (attempt + 1))
                    }
                }
            }
            throw lastException ?: IllegalStateException("抓取失败: 未知错误")
        }
    }

    /**
     * 端侧 SSRF 防护（U35-04）：
     * - scheme 白名单：仅 http/https，拒绝 file://、jar:、gopher: 等
     * - 解析 host，拒绝回环/私网/链路本地/任意本地地址（含 169.254.169.254 云元数据）
     */
    private fun assertPublicHttpUrl(url: String) {
        val u = runCatching { java.net.URL(url) }.getOrNull()
            ?: throw IllegalArgumentException("非法 URL: $url")
        val scheme = u.protocol?.lowercase()?.trim().orEmpty()
        if (scheme != "http" && scheme != "https") {
            throw IllegalArgumentException("仅支持 http/https，拒绝 scheme: $scheme")
        }
        val host = u.host?.lowercase()?.trim().orEmpty()
        if (host.isEmpty()) {
            throw IllegalArgumentException("URL 缺少 host: $url")
        }
        if (host == "localhost" || host.endsWith(".localhost")) {
            throw IllegalArgumentException("拒绝回环主机: $host")
        }
        val addr = runCatching { java.net.InetAddress.getByName(host) }.getOrNull()
        if (addr != null && (addr.isLoopbackAddress || addr.isLinkLocalAddress
                || addr.isSiteLocalAddress || addr.isAnyLocalAddress)
        ) {
            throw IllegalArgumentException("拒绝内网/链路本地/回环地址: $host")
        }
    }

    private fun fetchUrlInternal(url: String): FetchedSource {
        val req = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .build()

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("抓取失败: HTTP ${resp.code}")
            }

            // 快速路径：Content-Length 已知且超限直接拒绝
            val contentLength = resp.header("Content-Length")?.toLongOrNull()
            if (contentLength != null && contentLength > MAX_BODY_BYTES) {
                throw IllegalStateException("页面过大: ${contentLength / 1024}KB")
            }

            // 流式读取并按字节截断（U35-03）：chunked 编码无 Content-Length 时同样在超限时中止，
            // 避免恶意/异常服务端推送超大 body 撑爆内存。
            val source = resp.body?.source()
                ?: throw IllegalStateException("空响应体")
            val bodyBytes = source.use { it.readByteArray(MAX_BODY_BYTES.toLong() + 1) }
            if (bodyBytes.size > MAX_BODY_BYTES) {
                throw IllegalStateException("页面过大: 超过 ${MAX_BODY_BYTES / 1024}KB（chunked）")
            }
            val body = bodyBytes.decodeToString()
            val doc = Jsoup.parse(body)
            val title = doc.title().takeIf { it.isNotBlank() }

            // 提取元数据
            val description = extractMeta(doc, "description") ?: extractMeta(doc, "og:description")
            val author = extractMeta(doc, "author") ?: extractMeta(doc, "article:author")
            val publishTime = extractMeta(doc, "article:published_time")
                ?: extractMeta(doc, "date")
                ?: extractMeta(doc, "publish_date")

            // Readability 风格正文提取
            val text = extractMainContent(doc)

            return FetchedSource(
                title = title,
                url = url,
                text = text,
                description = description,
                author = author,
                publishTime = publishTime,
            )
        }
    }

    /**
     * Readability 风格正文提取。
     * 优先级：article > main > 最高密度 div > body.text() 兜底
     */
    private fun extractMainContent(doc: Document): String {
        // 1. 移除噪声标签
        for (tag in NOISE_TAGS) {
            doc.select(tag).remove()
        }
        // 移除隐藏元素
        doc.select("[style*=display:none],[style*=display: none],[hidden]").remove()

        // 2. 尝试 <article> 标签
        doc.select("article").firstOrNull()?.let { article ->
            val text = article.text().trim()
            if (text.length > 200) return text
        }

        // 3. 尝试 <main> 标签
        doc.select("main").firstOrNull()?.let { main ->
            val text = main.text().trim()
            if (text.length > 200) return text
        }

        // 4. 内容密度评分：找到最高密度的容器
        val bestDiv = findContentRoot(doc.body())
        if (bestDiv != null) {
            val text = bestDiv.text().trim()
            if (text.length > 200) return text
        }

        // 5. 兜底：body.text()
        return doc.body().text().trim().orEmpty()
    }

    /**
     * 内容密度评分算法。
     * 遍历所有 div/section，计算内容密度分数，返回最高分的容器。
     */
    private fun findContentRoot(body: Element?): Element? {
        if (body == null) return null

        var bestElement: Element? = null
        var bestScore = 0

        body.select("div, section").forEach { el ->
            var score = 0
            // 标签权重
            for ((tag, weight) in CONTENT_WEIGHTS) {
                score += el.select(tag).size * weight
            }
            // 文本长度加分
            val textLen = el.text().length
            score += textLen / 100

            // 链接密度惩罚（导航栏等链接密集区域扣分）
            val linkLen = el.select("a").sumOf { it.text().length }
            val linkDensity = if (textLen > 0) linkLen.toDouble() / textLen else 1.0
            if (linkDensity > 0.5) score = (score * 0.3).toInt()

            if (score > bestScore) {
                bestScore = score
                bestElement = el
            }
        }

        return bestElement
    }

    /** 提取 meta 标签内容 */
    private fun extractMeta(doc: Document, name: String): String? {
        return doc.selectFirst("meta[name=$name]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=$name]")?.attr("content")?.takeIf { it.isNotBlank() }
    }

    /** 判断异常是否可重试。仅对明确的暂时性网络错误重试（U35-10）：不再用 IOException 泛化兜底，
     *  否则会把读文件/编码等非网络错误也判为可重试；也不重试 SSL 握手等确定性失败。 */
    private fun isRetryable(e: Exception): Boolean {
        if (e is java.net.SocketTimeoutException) return true
        if (e is java.net.ConnectException) return true
        if (e is java.net.UnknownHostException) return true
        if (e is java.net.SocketException) return true
        val msg = e.message?.lowercase() ?: return false
        return msg.contains("timeout") || msg.contains("reset") ||
            msg.contains("broken pipe") || msg.contains("connection reset")
    }
}
