package top.hsyscn.opedrgent.network

import java.net.URI

enum class WebResearchMode { AUTO, NATIVE, PROVIDER, BROWSER }

data class WebResearchRequest(
    val query: String,
    val mode: WebResearchMode = WebResearchMode.AUTO,
    val maxResults: Int = 5,
    val maxFetch: Int = 3,
    val allowBrowser: Boolean = true,
    val unattended: Boolean = true,
    val allowedDomains: List<String> = emptyList(),
    val blockedDomains: List<String> = emptyList(),
)

data class WebResearchHit(
    val title: String,
    val url: String,
    val snippet: String?,
)

data class WebResearchOutcome(
    val modeUsed: WebResearchMode,
    val hits: List<WebResearchHit>,
    val fetched: List<FetchedSource>,
    val warnings: List<String> = emptyList(),
)

class WebResearchRouter(
    private val searcher: WebSearcher,
    private val fetcher: SourceFetcher,
) {
    fun run(req: WebResearchRequest): WebResearchOutcome {
        val query = req.query.trim()
        if (query.length < 2) {
            return WebResearchOutcome(
                modeUsed = WebResearchMode.PROVIDER,
                hits = emptyList(),
                fetched = emptyList(),
                warnings = listOf("查询词太短"),
            )
        }

        val warnings = ArrayList<String>()
        val effectiveAllowBrowser = req.allowBrowser && !req.unattended
        if (req.unattended && req.allowBrowser) {
            warnings += "无人值守模式已禁用浏览器通道"
        }

        val mode = when (req.mode) {
            WebResearchMode.BROWSER -> if (effectiveAllowBrowser) WebResearchMode.BROWSER else WebResearchMode.PROVIDER
            WebResearchMode.NATIVE -> WebResearchMode.NATIVE
            WebResearchMode.PROVIDER -> WebResearchMode.PROVIDER
            WebResearchMode.AUTO -> WebResearchMode.AUTO
        }

        return when (mode) {
            WebResearchMode.NATIVE -> {
                WebResearchOutcome(
                    modeUsed = WebResearchMode.NATIVE,
                    hits = emptyList(),
                    fetched = emptyList(),
                    warnings = warnings + "当前模型通道未提供原生 web_search 能力，已回退到 PROVIDER",
                ).let { fallback ->
                    val next = req.copy(mode = WebResearchMode.PROVIDER, allowBrowser = effectiveAllowBrowser)
                    val out = runProvider(next)
                    out.copy(warnings = fallback.warnings + out.warnings)
                }
            }
            WebResearchMode.BROWSER -> {
                WebResearchOutcome(
                    modeUsed = WebResearchMode.BROWSER,
                    hits = emptyList(),
                    fetched = emptyList(),
                    warnings = warnings + "浏览器通道仅支持交互模式：请在内嵌浏览器中打开并“保存来源”",
                )
            }
            WebResearchMode.PROVIDER -> runProvider(req.copy(allowBrowser = effectiveAllowBrowser)).copy(warnings = warnings)
            WebResearchMode.AUTO -> {
                runProvider(req.copy(mode = WebResearchMode.PROVIDER, allowBrowser = effectiveAllowBrowser)).copy(warnings = warnings)
            }
        }
    }

    private fun runProvider(req: WebResearchRequest): WebResearchOutcome {
        val maxResults = req.maxResults.coerceIn(1, 10)
        val maxFetch = req.maxFetch.coerceIn(1, 5)
        val results = searcher.search(req.query, limit = maxResults)
            .map { WebResearchHit(title = it.title, url = it.url, snippet = it.snippet) }

        val filtered = results.filter { hit ->
            val host = hostOf(hit.url) ?: return@filter false
            if (req.blockedDomains.any { hostMatches(host, it) }) return@filter false
            if (req.allowedDomains.isNotEmpty() && req.allowedDomains.none { hostMatches(host, it) }) return@filter false
            true
        }

        val fetched = ArrayList<FetchedSource>()
        val warnings = ArrayList<String>()
        val toFetch = filtered.take(maxFetch)
        toFetch.forEach { hit ->
            val got = runCatching { fetcher.fetchUrl(hit.url) }.getOrNull()
            if (got != null) fetched.add(got) else warnings.add("抓取失败：${hit.url}")
        }

        return WebResearchOutcome(
            modeUsed = WebResearchMode.PROVIDER,
            hits = filtered,
            fetched = fetched,
            warnings = warnings,
        )
    }

    private fun hostOf(url: String): String? {
        val u = runCatching { URI(url) }.getOrNull() ?: return null
        val host = u.host?.lowercase()?.trim().orEmpty()
        return host.takeIf { it.isNotBlank() }
    }

    private fun hostMatches(host: String, rule: String): Boolean {
        val r = rule.lowercase().trim().trimStart('.')
        if (r.isBlank()) return false
        if (host == r) return true
        return host.endsWith(".$r")
    }
}
