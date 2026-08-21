package top.hsyscn.opedrgent.tools

import top.hsyscn.opedrgent.R

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import top.hsyscn.opedrgent.model.ChatMessage
import top.hsyscn.opedrgent.model.Role
import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.model.ToolStateType
import top.hsyscn.opedrgent.network.LlmClient
import top.hsyscn.opedrgent.network.SearchConfig
import top.hsyscn.opedrgent.network.SearchResult
import top.hsyscn.opedrgent.network.SourceFetcher
import top.hsyscn.opedrgent.network.ToolResult
import top.hsyscn.opedrgent.network.WebSearcher
import top.hsyscn.opedrgent.network.WebViewAgent
import top.hsyscn.opedrgent.network.emptyResult
import top.hsyscn.opedrgent.settings.ApiConfig
import top.hsyscn.opedrgent.settings.ApiSettings
import top.hsyscn.opedrgent.utils.DebugLog
import top.hsyscn.opedrgent.utils.PromptSafety
import top.hsyscn.opedrgent.utils.smartTruncate
import java.util.concurrent.ConcurrentHashMap

class WebSearchTool(
    private val context: Context,
    private val searcher: WebSearcher,
    private val fetcher: SourceFetcher,
    private val llm: LlmClient,
    private val apiSettings: ApiSettings,
) : ToolSet {

    private var webViewAgent: WebViewAgent? = null
    private val translationCache = ConcurrentHashMap<String, String>()

    private fun buildSearchConfig(): SearchConfig = SearchConfig(
        providerOrder = apiSettings.getSearchProviderOrder(),
        searxngUrl = apiSettings.getSearxngBaseUrl(),
        jinaApiKey = apiSettings.getJinaApiKey(),
        braveApiKey = apiSettings.getBraveApiKey(),
        tavilyApiKey = apiSettings.getTavilyApiKey(),
    )

    private suspend fun getWebViewAgent(): WebViewAgent {
        return webViewAgent ?: WebViewAgent(context).also { webViewAgent = it }
    }

    private suspend fun translateQueryToEnglish(query: String, config: ApiConfig): String {
        val cached = translationCache[query]
        if (cached != null) {
            DebugLog.i("translateQuery: cache hit for '$query' → '$cached'")
            return cached
        }
        val prompt = buildString {
            appendLine("Translate this Chinese search query into English search keywords.")
            appendLine("Output ONLY the English translation, no explanation, no quotes.")
            appendLine("Keep it concise: 3-6 keywords separated by spaces.")
            appendLine()
            appendLine("Examples:")
            appendLine("吉利 跨时代 人才 跃迁 计划 官方 → Geely cross-era talent leap plan official")
            appendLine("中国 新能源 汽车 出口 数据 → China NEV export data")
            appendLine("小米 SU7 评测 → Xiaomi SU7 review")
            appendLine()
            appendLine("Query: $query")
        }
        return try {
            val result = withContext(Dispatchers.IO) {
                llm.chatCompletions(
                    config = config,
                    system = "You are a search query translator. Translate Chinese queries to English for web search.",
                    messages = listOf(
                        ChatMessage(role = Role.USER, content = prompt, createdAt = System.currentTimeMillis()),
                    ),
                )
            }.trim().trim('"').trim('\'')
            DebugLog.i("translateQuery: '$query' → '$result'")
            if (result.isNotBlank() && result.length > 3) {
                translationCache[query] = result
                result
            } else {
                DebugLog.w("translateQuery: result too short, fallback to original")
                query
            }
        } catch (e: Exception) {
            DebugLog.w("translateQuery failed: ${e.message}")
            query
        }
    }

    @Tool("web_search")
    @ToolDescription("""搜索互联网获取最新信息。当用户询问需要网络查询才能回答的问题时必须使用此工具。参数中 query 为必填，method 可选值: ddg(默认)/webview/provider_native/mcp/multimodal。

⚠️ 重要：如果用户已提供具体 URL，请使用 read_url 工具而非此工具。""")
    suspend fun executeWebSearch(
        tp: ToolPart,
        config: ApiConfig,
        systemPrompt: String,
        useProviderSearch: Boolean,
    ): ToolResult {
        // 弹性截断：根据模型上下文窗口按比例计算
        val maxContentChars = top.hsyscn.opedrgent.utils.ModelLimits.toolOutputMaxChars(
            top.hsyscn.opedrgent.utils.ModelLimits.inferMaxContextTokens(config.model)
        )
        val maxSnippetChars = (maxContentChars * 0.4).toInt() // 摘要取正文的 40%
        var query = tp.state.input["query"] ?: tp.state.input["keyword"] ?: return emptyResult(tp, context.getString(R.string.error_missing_search_keyword))

        // ★ Bugfix: LLM 有时将查询词包装为 JSON 字符串 {"query": "..."}，需要解包提取真实内容
        val trimmed = query.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            runCatching {
                val json = org.json.JSONObject(trimmed)
                if (json.has("query")) {
                    val extracted = json.getString("query")
                    if (extracted.isNotBlank()) {
                        DebugLog.i("web_search: 解包 JSON 查询词: '$query' -> '$extracted'")
                        query = extracted
                    }
                }
            }.onFailure { DebugLog.w("web_search: JSON 解包失败，使用原始查询词") }
        }

        // 二次解包：有些模型会双重嵌套
        val trimmed2 = query.trim()
        if (trimmed2.startsWith("{") && trimmed2.endsWith("}")) {
            runCatching {
                val json = org.json.JSONObject(trimmed2)
                if (json.has("query")) {
                    val extracted = json.getString("query")
                    if (extracted.isNotBlank()) {
                        DebugLog.i("web_search: 二次解包: '$query' -> '$extracted'")
                        query = extracted
                    }
                }
            }
        }

        // ★ 搜索查询词预处理：仅 trim 与折叠空白，保留原始词组结构
        query = sanitizeQuery(query)

        val method = (tp.state.input["method"] ?: "ddg").lowercase().trim()
        val phase = tp.state.input["phase"]?.lowercase()?.trim() ?: "scan"
        DebugLog.i("web_search: query=$query method=$method phase=$phase useProvider=$useProviderSearch")

        when (method) {
            "webview", "bing", "builtin" -> return webviewSearch(tp, query)
            "mcp", "js" -> return mcpSearch(tp, query)
            "screenshot", "multimodal" -> return multimodalSearch(tp, query, config, systemPrompt)
            "provider_native" -> return providerNativeSearch(tp, query, config)
            "ddg", "duckduckgo" -> {
                DebugLog.i("web_search: routing ddg method to provider search")
            }
        }

        if (!useProviderSearch) {
            DebugLog.i("web_search: using provider native search (厂商内置模式)")
            return providerNativeSearch(tp, query, config)
        }

        DebugLog.i("web_search: query='$query' phase=$phase")
        return when (phase) {
            "deep" -> executeDeepPhase(tp, query, config)
            else -> executeScanPhase(tp, query)
        }
    }

    private suspend fun webviewSearch(tp: ToolPart, query: String): ToolResult {
        // 弹性截断：使用默认上下文窗口计算
        val maxContentChars = top.hsyscn.opedrgent.utils.ModelLimits.toolOutputMaxChars(
            top.hsyscn.opedrgent.utils.ModelLimits.DEFAULT_MAX_TOKENS
        )
        val maxResults = (tp.state.input["max_fetch"]?.toIntOrNull() ?: 3).coerceIn(1, 8)
        val results = runCatching { getWebViewAgent().searchQuery(query, maxResults = maxResults) }.getOrNull()

        if (results.isNullOrEmpty()) {
            // ★ BUG-03 修复：无结果时使用 ERROR 状态
            return ToolResult(toolPart = tp.copy(state = tp.state.copy(
                status = ToolStateType.ERROR,
                error = context.getString(R.string.error_webview_no_results),
                endTime = System.currentTimeMillis())))
        }

        val formatted = buildString {
            appendLine("搜索结果（共 ${results.size} 条，来源：内置浏览器 Bing）：")
            results.forEachIndexed { idx, r ->
                appendLine("${idx + 1}. [${r.title}](${r.url})")
                val snip = r.snippet?.trim()
                if (!snip.isNullOrBlank()) appendLine("   $snip")
                appendLine()
            }
        }

        val maxFetch = maxResults.coerceAtMost(results.size)
        val fetchedResults = mutableListOf<String>()
        val fetchedSources = mutableListOf<String>()
        coroutineScope {
            val deferreds = results.take(maxFetch).map { r ->
                if (r.url.isNotBlank()) {
                    async(Dispatchers.IO) {
                        runCatching { getWebViewAgent().fetchUrl(r.url) }.getOrNull()?.let { wvFetched ->
                            val content = PromptSafety.sanitizeForPrompt(wvFetched.text, sourceLabel = r.url).content.take(maxContentChars)
                            Pair("\n--- 来源：${wvFetched.title} (${r.url}) ---\n$content\n", r.url)
                        }
                    }
                } else null
            }
            deferreds.filterNotNull().awaitAll().filterNotNull().forEach { (content, url) ->
                fetchedResults.add(content)
                fetchedSources.add(url)
            }
        }

        val output = buildString {
            append(formatted)
            if (fetchedResults.isNotEmpty()) {
                appendLine("=== 已抓取网页正文 ===")
                fetchedResults.forEach { append(it) }
            }
        }

        return ToolResult(
            toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.COMPLETED, output = output, endTime = System.currentTimeMillis())),
            addedSources = fetchedSources,
        )
    }

    private suspend fun mcpSearch(tp: ToolPart, query: String): ToolResult {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "https://www.bing.com/search?q=$encodedQuery"
        val script = """
            (function(){
                var items = [];
                document.querySelectorAll('.b_algo').forEach(function(el){
                    var h2 = el.querySelector('h2');
                    var p = el.querySelector('.b_caption p');
                    items.push({
                        title: h2 ? h2.textContent.trim() : '',
                        url: h2 && h2.href ? h2.href : '',
                        snippet: p ? p.textContent.trim() : ''
                    });
                });
                OpedrgentBridge.postResult(JSON.stringify(items));
            })()
        """.trimIndent()

        val raw = runCatching { getWebViewAgent().executeMcpScript(url, script) }.getOrNull()
        if (raw.isNullOrBlank()) {
            DebugLog.w("mcpSearch: no result, falling back to webviewSearch")
            return webviewSearch(tp, query)
        }

        val parsed = runCatching { org.json.JSONArray(raw) }.getOrNull()
        if (parsed == null || parsed.length() == 0) {
            return webviewSearch(tp, query)
        }

        val list = buildString {
            appendLine("MCP JS 注入搜索结果（共 ${parsed.length()} 条）：")
            for (i in 0 until parsed.length()) {
                val obj = parsed.getJSONObject(i)
                appendLine("${i + 1}. [${obj.optString("title")}](${obj.optString("url")})")
                appendLine("   ${obj.optString("snippet")}")
                appendLine()
            }
        }

        return ToolResult(toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.COMPLETED, output = list, endTime = System.currentTimeMillis())))
    }

    private suspend fun multimodalSearch(tp: ToolPart, query: String, config: ApiConfig, systemPrompt: String): ToolResult {
        val url = tp.state.input["url"] ?: "https://www.bing.com"
        val log = runCatching { getWebViewAgent().multimodalClick(query, url, llm, config, systemPrompt, maxRounds = 3) }.getOrNull()
            ?: context.getString(R.string.error_multimodal_click_failed)

        return ToolResult(toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.COMPLETED, output = log, endTime = System.currentTimeMillis())))
    }

    private suspend fun providerNativeSearch(tp: ToolPart, query: String, config: ApiConfig): ToolResult {
        DebugLog.i("web_search: provider_native search for '$query' via ${config.model}")

        return withContext(Dispatchers.IO) {
            runCatching {
                val result = llm.chatCompletionsNativeSearch(config, query)
                val citations = result.citations.filter { it.url.isNotBlank() }
                val output = buildString {
                    append(result.content)
                    if (citations.isNotEmpty()) {
                        appendLine("\n\n来源：")
                        citations.forEach { c ->
                            val label = c.title.takeIf { it.isNotBlank() } ?: c.url
                            appendLine("- [$label](${c.url})")
                        }
                    }
                }
                ToolResult(
                    toolPart = tp.copy(state = tp.state.copy(
                        status = ToolStateType.COMPLETED,
                        output = output.ifBlank { context.getString(R.string.error_provider_search_empty) },
                        endTime = System.currentTimeMillis(),
                    )),
                    addedSources = citations.map { it.url },
                )
            }.getOrElse { e ->
                DebugLog.w("provider_native failed: ${e.message}, falling back to DDG")
                val searchResults = searcher.search(query, limit = 5)
                val formatted = buildString {
                    appendLine("搜索结果（${searchResults.size} 条）：")
                    searchResults.forEachIndexed { idx, r ->
                        appendLine("${idx + 1}. [${r.title}](${r.url})")
                        val snip = r.snippet?.trim()
                        if (!snip.isNullOrBlank()) appendLine("   $snip")
                    }
                }
                ToolResult(
                    toolPart = tp.copy(state = tp.state.copy(
                        status = ToolStateType.COMPLETED,
                        output = formatted,
                        endTime = System.currentTimeMillis(),
                    )),
                    addedSources = searchResults.map { it.url },
                )
            }
        }
    }

    private fun parsePhase(tp: ToolPart): String =
        tp.state.input["phase"]?.lowercase()?.trim() ?: "scan"

    private fun parseUrls(tp: ToolPart): List<String> =
        tp.state.input["urls"]?.split(Regex("[|\\n]+"))?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()

    private fun normalizeSearchUrl(url: String): String =
        url.trim().lowercase().removeSuffix("/").substringBefore("#")

    private fun formatSourceLabel(result: SearchResult): String {
        val engine = result.sourceEngines.firstOrNull()
        if (!engine.isNullOrBlank()) return engine
        return try {
            java.net.URL(result.url).host.removePrefix("www.")
        } catch (_: Exception) { result.url }
    }

    private suspend fun executeScanPhase(tp: ToolPart, query: String): ToolResult {
        DebugLog.i("web_search scan: query='$query'")
        var searchResults = searcher.searchAsync(query, buildSearchConfig(), limit = 30)

        if (searchResults.size < 30) {
            val needed = (50 - searchResults.size).coerceIn(20, 50)
            DebugLog.i("web_search scan: HTTP engines returned ${searchResults.size}, enriching via WebView up to $needed")
            val wvResults = runCatching {
                getWebViewAgent().searchQuery(query, maxResults = needed)
            }.getOrNull()?.map {
                SearchResult(
                    title = it.title,
                    url = it.url,
                    snippet = it.snippet,
                    sourceEngines = setOf("webview"),
                )
            } ?: emptyList()
            searchResults = (searchResults + wvResults)
                .distinctBy { normalizeSearchUrl(it.url) }
                .take(50)
        }

        if (searchResults.isEmpty()) {
            return ToolResult(
                toolPart = tp.copy(state = tp.state.copy(
                    status = ToolStateType.COMPLETED,
                    output = context.getString(R.string.error_no_search_results, query),
                    endTime = System.currentTimeMillis(),
                )),
            )
        }

        val output = formatScanResults(searchResults)
        return ToolResult(
            toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.COMPLETED, output = output, endTime = System.currentTimeMillis())),
            addedSources = searchResults.map { it.url },
        )
    }

    private fun formatScanResults(results: List<SearchResult>): String = buildString {
        appendLine("共找到 ${results.size} 条相关结果，请从中选择 5-10 个你想深入阅读的条目，回复格式：")
        appendLine("选择: 1,3,7,12,15")
        appendLine("或调用 web_search?phase=deep&urls=<url1>|<url2>|...")
        appendLine()
        results.forEachIndexed { idx, r ->
            val safeTitle = top.hsyscn.opedrgent.utils.StringUtils.sanitizeJsonNull(r.title).let { if (it.isBlank()) r.url else it }
            appendLine("${idx + 1}. [${safeTitle}](${r.url})")
            val snip = top.hsyscn.opedrgent.utils.StringUtils.sanitizeJsonNull(r.snippet)
            if (snip.isNotEmpty()) appendLine("   摘要：$snip")
            appendLine("   来源：${formatSourceLabel(r)}")
            appendLine()
        }
    }

    private suspend fun executeDeepPhase(tp: ToolPart, query: String, config: ApiConfig): ToolResult {
        val urls = parseUrls(tp)
        if (urls.isEmpty()) {
            DebugLog.w("web_search deep: urls empty, falling back to scan")
            return executeScanPhase(tp, query)
        }

        val maxContentChars = top.hsyscn.opedrgent.utils.ModelLimits.toolOutputMaxChars(
            top.hsyscn.opedrgent.utils.ModelLimits.inferMaxContextTokens(config.model)
        )
        val maxFetch = (tp.state.input["max_fetch"]?.toIntOrNull() ?: 5).coerceIn(1, 10)
        val toFetch = urls.take(maxFetch)

        val fetchedResults = mutableListOf<String>()
        val fetchedSources = mutableListOf<String>()

        coroutineScope {
            val deferreds = toFetch.map { url ->
                async(Dispatchers.IO) {
                    deepFetchUrl(url, maxContentChars)
                }
            }.toMutableList()
            // 按完成顺序收集结果，单个 URL 的超时不会阻塞其他 URL
            while (deferreds.isNotEmpty()) {
                val (index, result) = select<Pair<Int, Pair<String, String>?>> {
                    deferreds.forEachIndexed { idx, deferred ->
                        deferred.onAwait { idx to it }
                    }
                }
                deferreds.removeAt(index)
                result?.let { (content, sourceUrl) ->
                    fetchedResults.add(content)
                    fetchedSources.add(sourceUrl)
                }
            }
        }

        if (fetchedResults.isEmpty()) {
            return ToolResult(
                toolPart = tp.copy(state = tp.state.copy(
                    status = ToolStateType.ERROR,
                    error = context.getString(R.string.error_cannot_fetch_url),
                    endTime = System.currentTimeMillis(),
                )),
            )
        }

        val output = buildString {
            appendLine("已深入抓取 ${fetchedResults.size} 个网页：")
            fetchedResults.forEach { append(it) }
        }

        return ToolResult(
            toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.COMPLETED, output = output, endTime = System.currentTimeMillis())),
            addedSources = fetchedSources,
        )
    }

    private suspend fun deepFetchUrl(url: String, maxContentChars: Int): Pair<String, String>? {
        var jinaTimedOut = false
        val jinaResult = try {
            withContext(Dispatchers.IO) {
                withTimeout(10_000) { searcher.fetchViaJina(url) }
            }
        } catch (e: TimeoutCancellationException) {
            DebugLog.w("deepFetchUrl: Jina timeout for $url")
            jinaTimedOut = true
            null
        } catch (e: Exception) {
            DebugLog.w("deepFetchUrl: Jina failed: ${e.message}")
            null
        }
        if (jinaResult != null && jinaResult.text.length > 100) {
            val sanitized = PromptSafety.sanitizeForPrompt(jinaResult.text, sourceLabel = url)
            val content = smartTruncate(sanitized.content, maxContentChars)
            val safeTitle = top.hsyscn.opedrgent.utils.StringUtils.sanitizeJsonNull(jinaResult.title).let { if (it.isBlank()) url else it }
            return Pair("\n--- 来源：${safeTitle} (${url}) ---\n${content}\n", url)
        }

        var wvTimedOut = false
        val wvResult = try {
            withTimeout(15_000) { getWebViewAgent().fetchUrl(url) }
        } catch (e: TimeoutCancellationException) {
            DebugLog.w("deepFetchUrl: WebView timeout for $url")
            wvTimedOut = true
            null
        } catch (e: Exception) {
            DebugLog.w("deepFetchUrl: WebView failed: ${e.message}")
            null
        }
        if (wvResult != null && wvResult.text.length > 100) {
            val sanitized = PromptSafety.sanitizeForPrompt(wvResult.text, sourceLabel = url)
            val content = smartTruncate(sanitized.content, maxContentChars)
            val safeTitle = top.hsyscn.opedrgent.utils.StringUtils.sanitizeJsonNull(wvResult.title).let { if (it.isBlank()) url else it }
            return Pair("\n--- 来源：${safeTitle} (${url}) ---\n${content}\n", url)
        }

        // 任一阶段发生过超时：记录为部分结果，而不是完全丢弃该 URL
        if (jinaTimedOut || wvTimedOut) {
            return Pair(
                "\n--- 来源：$url (读取超时，仅部分获取) ---\n[PARTIAL_TIMEOUT]\n已获取片段：（无）\n",
                url,
            )
        }

        return null
    }

    fun destroy() {
        webViewAgent?.destroy()
        webViewAgent = null
        translationCache.clear()
    }

    override fun getTools(): Map<String, ToolBinding> {
        return mapOf(
            "web_search" to ToolBinding(
                name = "web_search",
                description = """搜索互联网获取最新信息。默认进入 scan 阶段，仅返回标题、摘要、URL 列表；需要深入阅读时请进入 deep 阶段并指定 URLs。

⚠️ 仅在用户未提供具体 URL 时使用此工具。若用户已给出 URL，请使用 read_url 直接访问。""",
                parameters = org.json.JSONObject().apply {
                    put("type", "object")
                    put("properties", org.json.JSONObject().apply {
                        put("query", org.json.JSONObject().apply {
                            put("type", "string")
                            put("description", "搜索关键词")
                        })
                        put("method", org.json.JSONObject().apply {
                            put("type", "string")
                            put("enum", org.json.JSONArray().apply { put("ddg"); put("webview"); put("provider_native"); put("mcp"); put("multimodal") })
                            put("description", "搜索方法，默认 ddg（多引擎聚合）。特殊模式：webview 内置浏览器、provider_native 厂商原生联网、mcp JS 注入、multimodal 多模态点击")
                        })
                        put("phase", org.json.JSONObject().apply {
                            put("type", "string")
                            put("enum", org.json.JSONArray().apply { put("scan"); put("deep") })
                            put("description", "搜索阶段：scan 仅扫描列表（默认），deep 深入抓取指定 URL 正文")
                        })
                        put("urls", org.json.JSONObject().apply {
                            put("type", "string")
                            put("description", "deep 阶段要抓取的 URL，多个用 | 分隔")
                        })
                        put("max_fetch", org.json.JSONObject().apply {
                            put("type", "integer")
                            put("description", "deep 阶段最多抓取的 URL 数量，默认 5，最大 10")
                        })
                    })
                    put("required", org.json.JSONArray().apply { put("query") })
                },
                invoker = { tp, config, sp, ups -> executeWebSearch(tp, config, sp, ups) },
            ),
        )
    }
}

/**
 * 搜索查询词预处理（dumb-pipe 版）。
 *
 * 仅做最小化清洗，保留原始查询的语义和词组边界：
 * - 去除首尾空白
 * - 折叠连续空白（含全角空格）为单个半角空格
 * - 不删除停用词、不过滤单字、不去重、不截断中文
 * - 支持 {"query":"..."} 外层 JSON 解包
 *
 * 如果清洗后为空，则回退到原始输入。
 */
fun sanitizeQuery(raw: String): String {
    val unwrapped = unwrapJsonQuery(raw).trim()

    if (unwrapped.isBlank()) return raw.trim()

    var q = unwrapped.replace(Regex("[\\s\\u3000]+"), " ").trim()

    if (q != unwrapped) {
        DebugLog.i("sanitizeQuery: '$unwrapped' -> '$q'")
    }

    return q.ifBlank { raw.trim() }
}

private fun unwrapJsonQuery(raw: String): String {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return raw

    val key = "\"query\""
    val keyIndex = trimmed.indexOf(key)
    if (keyIndex < 0) return raw

    val colonIndex = trimmed.indexOf(':', startIndex = keyIndex + key.length)
    if (colonIndex < 0) return raw

    val valueStart = trimmed.indexOf('"', startIndex = colonIndex + 1)
    if (valueStart < 0) return raw

    val valueEnd = trimmed.indexOf('"', startIndex = valueStart + 1)
    if (valueEnd < 0) return raw

    return trimmed.substring(valueStart + 1, valueEnd)
}
