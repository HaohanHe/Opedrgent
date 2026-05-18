package top.hsyscn.opedrgent.network

import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import okhttp3.OkHttpClient
import okhttp3.Request
import top.hsyscn.opedrgent.utils.DebugLog
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class FallbackSource {
    JINA_AI,
    TEXTISE_DOT_ITY,
    READABLE_API,
    MOZ_READABILITY,
    DIRECT_FETCH
}

data class FallbackResult(
    val source: FallbackSource,
    val title: String,
    val content: String,
    val url: String,
    val fetchTimeMs: Long,
    val qualityScore: Double
)

data class ContentQualityMetrics(
    val contentLength: Int,
    val hasTitle: Boolean,
    val hasContent: Boolean,
    val estimatedNoiseRatio: Double,
    val isErrorPage: Boolean,
    val isTooShort: Boolean,
    val score: Double
)

data class FallbackConfig(
    val maxParallelSources: Int = 2,
    val totalTimeoutMs: Long = 15_000L,
    val perSourceTimeoutMs: Long = 8_000L,
    val minContentLength: Int = 50,
    val maxNoiseRatio: Double = 0.8,
    val qualityThreshold: Double = 0.3
)

class FallbackChainManager(
    private val httpClient: OkHttpClient = HttpClients.default,
    private val config: FallbackConfig = FallbackConfig()
) {

    private val failureCounts = ConcurrentHashMap<FallbackSource, AtomicInteger>()
    private val successCounts = ConcurrentHashMap<FallbackSource, AtomicInteger>()

    init {
        FallbackSource.values().forEach { source ->
            failureCounts[source] = AtomicInteger(0)
            successCounts[source] = AtomicInteger(0)
        }
    }

    suspend fun fetch(url: String): FallbackResult? {
        DebugLog.i("FallbackChainManager.fetch start: $url")
        val startTime = System.currentTimeMillis()
        val sources = selectSourcesForParallelExecution()

        if (sources.isEmpty()) {
            DebugLog.w("FallbackChainManager.fetch: no available sources for $url")
            return null
        }

        return coroutineScope {
            val deferredResults = mutableMapOf<FallbackSource, Deferred<FallbackResult?>>()

            for (source in sources) {
                val deferred = async(Dispatchers.IO) {
                    withTimeoutOrNull(config.perSourceTimeoutMs) {
                        when (source) {
                            FallbackSource.JINA_AI -> fetchFromJina(url)
                            FallbackSource.TEXTISE_DOT_ITY -> fetchFromTextise(url)
                            FallbackSource.READABLE_API -> fetchFromReadableApi(url)
                            FallbackSource.MOZ_READABILITY -> fetchFromMozReadability(url)
                            FallbackSource.DIRECT_FETCH -> fetchDirect(url)
                        }
                    }
                }
                deferredResults[source] = deferred
            }

            try {
                select<FallbackResult?> {
                    for ((source, deferred) in deferredResults) {
                        deferred.onAwait { result ->
                            if (result != null && result.qualityScore >= config.qualityThreshold) {
                                recordResult(source, true)
                                DebugLog.i(
                                    "FallbackChainManager.fetch success: source=${source.name}, " +
                                    "score=${result.qualityScore}, time=${result.fetchTimeMs}ms"
                                )
                                result
                            } else {
                                if (result == null) {
                                    recordResult(source, false)
                                    DebugLog.w(
                                        "FallbackChainManager.fetch failed/timeout: source=${source.name}"
                                    )
                                } else {
                                    recordResult(source, false)
                                    DebugLog.w(
                                        "FallbackChainManager.fetch low quality: source=${source.name}, " +
                                        "score=${result.qualityScore} < threshold=${config.qualityThreshold}"
                                    )
                                }
                                null
                            }
                        }
                    }
                }.also { _ ->
                    for ((source, deferred) in deferredResults) {
                        if (!deferred.isCompleted) {
                            deferred.cancel("Already got a valid result from another source")
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.e("FallbackChainManager.fetch error: ${e.message}", e)
                null
            }
        }.also {
            val totalTime = System.currentTimeMillis() - startTime
            DebugLog.i("FallbackChainManager.fetch completed in ${totalTime}ms, result=${if (it != null) it.source.name else "null"}")
        }
    }

    fun getRecommendedOrder(): List<FallbackSource> {
        val defaultOrder = listOf(
            FallbackSource.JINA_AI,
            FallbackSource.TEXTISE_DOT_ITY,
            FallbackSource.READABLE_API,
            FallbackSource.MOZ_READABILITY,
            FallbackSource.DIRECT_FETCH
        )

        return defaultOrder.sortedWith(compareByDescending<FallbackSource> { source ->
            val successes = successCounts[source]?.get() ?: 0
            val failures = failureCounts[source]?.get() ?: 0
            successes - failures
        }.thenBy { source ->
            failureCounts[source]?.get() ?: 0
        })
    }

    fun evaluateQuality(title: String, content: String): ContentQualityMetrics {
        val contentLength = content.length
        val hasTitle = title.isNotBlank() && title.length > 2
        val hasContent = contentLength >= config.minContentLength
        val isTooShort = contentLength < config.minContentLength

        val errorKeywords = listOf("404", "not found", "error", "access denied", "forbidden")
        val isErrorPage = errorKeywords.any {
            content.contains(it, ignoreCase = true) ||
                    title.contains(it, ignoreCase = true)
        }

        val htmlTagPattern = Regex("<[^>]+>")
        val scriptPattern = Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL)
        val stylePattern = Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL)

        val htmlTags = htmlTagPattern.findAll(content).count()
        val scripts = scriptPattern.find(content)?.value?.length ?: 0
        val styles = stylePattern.find(content)?.value?.length ?: 0
        val noiseChars = (htmlTags * 20) + scripts + styles

        val estimatedNoiseRatio = if (contentLength > 0) {
            (noiseChars.toDouble() / contentLength).coerceIn(0.0, 1.0)
        } else 1.0

        val score = when {
            isErrorPage -> 0.0
            isTooShort -> 0.1
            !hasContent -> 0.2
            !hasTitle -> 0.5
            estimatedNoiseRatio > config.maxNoiseRatio -> 0.4
            else -> ((1.0 - estimatedNoiseRatio) * 0.6 +
                    (contentLength.coerceAtMost(2000) / 2000.0) * 0.4).coerceIn(0.0, 1.0)
        }

        return ContentQualityMetrics(
            contentLength = contentLength,
            hasTitle = hasTitle,
            hasContent = hasContent,
            estimatedNoiseRatio = estimatedNoiseRatio,
            isErrorPage = isErrorPage,
            isTooShort = isTooShort,
            score = score
        )
    }

    fun getStats(): Map<String, Any> {
        val sourcesMap = FallbackSource.values().associate { source ->
            val successes = successCounts[source]?.get() ?: 0
            val failures = failureCounts[source]?.get() ?: 0
            val total = successes + failures
            val successRate = if (total > 0) successes.toDouble() / total else 0.0

            source.name to mapOf(
                "successes" to successes,
                "failures" to failures,
                "successRate" to successRate
            )
        }

        return mapOf(
            "sources" to sourcesMap,
            "config" to mapOf(
                "maxParallelSources" to config.maxParallelSources,
                "totalTimeoutMs" to config.totalTimeoutMs,
                "perSourceTimeoutMs" to config.perSourceTimeoutMs,
                "minContentLength" to config.minContentLength,
                "maxNoiseRatio" to config.maxNoiseRatio,
                "qualityThreshold" to config.qualityThreshold
            )
        )
    }

    private suspend fun fetchFromJina(url: String): FallbackResult? {
        val startTime = System.currentTimeMillis()
        val jinaUrl = "https://r.jina.ai/$url"

        return runCatching {
            val request = Request.Builder()
                .url(jinaUrl)
                .get()
                .header("Accept", "text/plain")
                .header("User-Agent", UserAgentPool.generate())
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    DebugLog.w("fetchFromJina HTTP ${response.code}: $url")
                    return@runCatching null
                }

                val rawBody = response.body?.string().orEmpty()
                parseJinaResponse(rawBody, url, startTime)
            }
        }.onFailure { e ->
            DebugLog.e("fetchFromJina exception: ${e.message}", e)
        }.getOrNull()
    }

    private suspend fun fetchFromTextise(url: String): FallbackResult? {
        val startTime = System.currentTimeMillis()
        val encodedUrl = URLEncoder.encode(url, "UTF-8")
        val textiseUrl = "https://r.jina.ai/http://textise.it/show?url=$encodedUrl"

        return runCatching {
            val request = Request.Builder()
                .url(textiseUrl)
                .get()
                .header("Accept", "text/plain")
                .header("User-Agent", UserAgentPool.generate())
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    DebugLog.w("fetchFromTextise HTTP ${response.code}: $url")
                    return@runCatching null
                }

                val rawBody = response.body?.string().orEmpty()
                parseTextiseResponse(rawBody, url, startTime)
            }
        }.onFailure { e ->
            DebugLog.e("fetchFromTextise exception: ${e.message}", e)
        }.getOrNull()
    }

    private suspend fun fetchDirect(url: String): FallbackResult? {
        val startTime = System.currentTimeMillis()

        return runCatching {
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Accept", "text/html,text/plain,*/*")
                .header("User-Agent", UserAgentPool.generate())
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    DebugLog.w("fetchDirect HTTP ${response.code}: $url")
                    return@runCatching null
                }

                val body = response.body?.string().orEmpty()
                val metrics = evaluateQuality("", body)

                FallbackResult(
                    source = FallbackSource.DIRECT_FETCH,
                    title = "",
                    content = body,
                    url = url,
                    fetchTimeMs = System.currentTimeMillis() - startTime,
                    qualityScore = metrics.score
                )
            }
        }.onFailure { e ->
            DebugLog.e("fetchDirect exception: ${e.message}", e)
        }.getOrNull()
    }

    private suspend fun fetchFromReadableApi(url: String): FallbackResult? {
        val startTime = System.currentTimeMillis()

        return runCatching {
            val request = Request.Builder()
                .url("https://r.jina.ai/$url")
                .get()
                .header("Accept", "text/plain")
                .header("User-Agent", UserAgentPool.generate())
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    DebugLog.w("fetchFromReadableApi HTTP ${response.code}: $url")
                    return@runCatching null
                }

                val rawBody = response.body?.string().orEmpty()
                parseJinaResponse(rawBody, url, startTime)?.copy(
                    source = FallbackSource.READABLE_API
                )
            }
        }.onFailure { e ->
            DebugLog.e("fetchFromReadableApi exception: ${e.message}", e)
        }.getOrNull()
    }

    private suspend fun fetchFromMozReadability(url: String): FallbackResult? {
        val startTime = System.currentTimeMillis()

        return runCatching {
            val request = Request.Builder()
                .url("https://r.jina.ai/$url")
                .get()
                .header("Accept", "text/plain")
                .header("User-Agent", UserAgentPool.generate())
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    DebugLog.w("fetchFromMozReadability HTTP ${response.code}: $url")
                    return@runCatching null
                }

                val rawBody = response.body?.string().orEmpty()
                parseJinaResponse(rawBody, url, startTime)?.copy(
                    source = FallbackSource.MOZ_READABILITY
                )
            }
        }.onFailure { e ->
            DebugLog.e("fetchFromMozReadability exception: ${e.message}", e)
        }.getOrNull()
    }

    private fun parseJinaResponse(rawBody: String, originalUrl: String, startTime: Long): FallbackResult? {
        val lines = rawBody.lines()
        var title = ""
        var contentLines = mutableListOf<String>()

        val titleRegex = Regex("^Title:\\s*(.+)$")

        for (line in lines) {
            val trimmedLine = line.trim()
            when {
                titleRegex.matches(trimmedLine) -> {
                    title = titleRegex.find(trimmedLine)?.groupValues?.get(1) ?: ""
                }
                trimmedLine.startsWith("URL:", ignoreCase = true) ||
                        trimmedLine.startsWith("Published Time:", ignoreCase = true) -> {
                }
                else -> {
                    contentLines.add(line)
                }
            }
        }

        val content = contentLines.joinToString("\n").trim()
        val metrics = evaluateQuality(title, content)

        return FallbackResult(
            source = FallbackSource.JINA_AI,
            title = title,
            content = content,
            url = originalUrl,
            fetchTimeMs = System.currentTimeMillis() - startTime,
            qualityScore = metrics.score
        )
    }

    private fun parseTextiseResponse(rawBody: String, originalUrl: String, startTime: Long): FallbackResult? {
        val decodedBody = rawBody
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")

        val lines = decodedBody.lines()
        var title = ""
        var contentLines = mutableListOf<String>()

        val titleRegex = Regex("^Title:\\s*(.+)$", RegexOption.IGNORE_CASE)

        for (line in lines) {
            val trimmedLine = line.trim()
            when {
                titleRegex.matches(trimmedLine) -> {
                    title = titleRegex.find(trimmedLine)?.groupValues?.get(1) ?: ""
                }
                trimmedLine.startsWith("URL:", ignoreCase = true) ||
                        trimmedLine.startsWith("Published Time:", ignoreCase = true) ||
                        trimmedLine.startsWith("Source:", ignoreCase = true) -> {
                }
                else -> {
                    contentLines.add(line)
                }
            }
        }

        val content = contentLines.joinToString("\n").trim()
        val metrics = evaluateQuality(title, content)

        return FallbackResult(
            source = FallbackSource.TEXTISE_DOT_ITY,
            title = title,
            content = content,
            url = originalUrl,
            fetchTimeMs = System.currentTimeMillis() - startTime,
            qualityScore = metrics.score
        )
    }

    private fun recordResult(source: FallbackSource, success: Boolean) {
        if (success) {
            successCounts[source]?.incrementAndGet()
        } else {
            failureCounts[source]?.incrementAndGet()
        }
    }

    private fun selectSourcesForParallelExecution(): List<FallbackSource> {
        return getRecommendedOrder().take(config.maxParallelSources)
    }
}
