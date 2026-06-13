package top.hsyscn.opedrgent.tools

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
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
import top.hsyscn.opedrgent.settings.ApiConfig
import top.hsyscn.opedrgent.settings.ApiSettings
import top.hsyscn.opedrgent.utils.DebugLog
import top.hsyscn.opedrgent.utils.PromptSafety
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
        if (webViewAgent == null) {
            webViewAgent = WebViewAgent(context)
        }
        return webViewAgent!!
    }

    private fun emptyResult(tp: ToolPart, msg: String): ToolResult {
        return ToolResult(toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.ERROR, error = msg, endTime = System.currentTimeMillis())))
    }

    /**
     * 智能截取：按段落边界截取，保留完整句子
     */
    private fun smartTruncate(text: String, maxLen: Int): String {
        if (text.length <= maxLen) return text
        val truncated = text.take(maxLen)
        // 尝试在段落边界截断
        val lastParagraph = truncated.lastIndexOf("\n\n")
        if (lastParagraph > maxLen * 0.6) return truncated.substring(0, lastParagraph)
        // 尝试在句子边界截断
        val lastSentence = maxOf(truncated.lastIndexOf("。"), truncated.lastIndexOf(". "), truncated.lastIndexOf("！"), truncated.lastIndexOf("？"))
        if (lastSentence > maxLen * 0.5) return truncated.substring(0, lastSentence + 1)
        // 最后在空格处截断
        val lastSpace = truncated.lastIndexOf(' ')
        if (lastSpace > maxLen * 0.7) return truncated.substring(0, lastSpace)
        return truncated
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
        val query = tp.state.input["query"] ?: tp.state.input["keyword"] ?: return emptyResult(tp, "缺少搜索关键词")
        val method = (tp.state.input["method"] ?: "ddg").lowercase().trim()
        DebugLog.i("web_search: query=$query method=$method useProvider=$useProviderSearch")

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
            DebugLog.i("web_search: using WebView builtin search (provider disabled)")
            return webviewSearch(tp, query)
        }

        DebugLog.i("web_search: query='$query'")
        val searchResults = searcher.searchAsync(query, buildSearchConfig(), limit = 3)

        val sourceLabel = if (searchResults.isNotEmpty()) {
            val first = searchResults.first()
            when {
                first.url.contains("bing.com") -> "Bing"
                first.url.contains("duckduckgo") -> "DuckDuckGo"
                else -> "Web"
            }
        } else ""

        if (searchResults.isEmpty()) {
            DebugLog.w("web_search: all HTTP searches failed, falling back to WebView")
            return webviewSearch(tp, query)
        }

        val formatted = buildString {
            appendLine("搜索结果（共 ${searchResults.size} 条，来源：$sourceLabel）：")
            searchResults.forEachIndexed { idx, r ->
                val safeTitle = top.hsyscn.opedrgent.utils.StringUtils.sanitizeJsonNull(r.title).let { if (it.isBlank()) r.url else it }
                appendLine("${idx + 1}. [${safeTitle}](${r.url})")
                val snip = top.hsyscn.opedrgent.utils.StringUtils.sanitizeJsonNull(r.snippet)
                if (snip.isNotEmpty()) appendLine("   $snip")
                appendLine()
            }
        }

        val maxFetch = (tp.state.input["max_fetch"]?.toIntOrNull() ?: 1).coerceIn(1, 3)
        val fetchedResults = mutableListOf<String>()
        val fetchedSources = mutableListOf<String>()
        val toFetch = searchResults.take(maxFetch)

        coroutineScope {
            val deferreds = toFetch.map { result ->
                async(Dispatchers.IO) {
                    // 优先用 Jina Reader 抓取正文，失败则用摘要
                    val jinaResult = runCatching { searcher.fetchViaJina(result.url) }.getOrNull()
                    if (jinaResult != null && jinaResult.text.length > 100) {
                        val sanitized = PromptSafety.sanitizeForPrompt(jinaResult.text, sourceLabel = result.url)
                        val content = smartTruncate(sanitized.content, 4000)
                        val safeTitle = top.hsyscn.opedrgent.utils.StringUtils.sanitizeJsonNull(jinaResult.title).let { if (it.isBlank()) result.title else it }
                        return@async Pair("\n--- 来源：${safeTitle} (${result.url}) ---\n$content\n", result.url)
                    }

                    // Jina 失败，用搜索摘要
                    if (result.snippet != null && result.snippet.isNotBlank()) {
                        val effectiveSnippet = smartTruncate(result.snippet, 1500)
                        return@async Pair("\n--- 来源：${result.title} (${result.url}) ---\n${effectiveSnippet}\n", result.url)
                    }

                    null
                }
            }
            deferreds.awaitAll().filterNotNull().forEach { (content, url) ->
                fetchedResults.add(content)
                fetchedSources.add(url)
            }
        }

        if (fetchedResults.isEmpty() && toFetch.isNotEmpty()) {
            DebugLog.w("web_search: all fetch methods failed for ${toFetch.size} urls, returning snippet-only fallback")
            val snippetOnly = buildString {
                appendLine("搜索结果（共 ${searchResults.size} 条，来源：$sourceLabel）：")
                searchResults.forEachIndexed { idx, r ->
                    appendLine("${idx + 1}. [${r.title}](${r.url})")
                    appendLine("   ${r.snippet ?: "无摘要"}")
                    appendLine()
                }
                appendLine("※ 注意：网页内容抓取失败，仅能搜索到标题和摘要。建议尝试点击链接打开外部浏览器查看。")
            }
            return ToolResult(
                toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.COMPLETED, output = snippetOnly, endTime = System.currentTimeMillis())),
                addedSources = searchResults.map { it.url },
            )
        }

        val output = top.hsyscn.opedrgent.utils.StringUtils.sanitizeJsonNull(buildString {
            append(formatted)
            if (fetchedResults.isNotEmpty()) {
                appendLine("=== 已抓取网页正文 ===")
                fetchedResults.forEach { append(it) }
            }
        })

        return ToolResult(
            toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.COMPLETED, output = output, endTime = System.currentTimeMillis())),
            addedSources = fetchedSources,
        )
    }

    private suspend fun webviewSearch(tp: ToolPart, query: String): ToolResult {
        val maxResults = (tp.state.input["max_fetch"]?.toIntOrNull() ?: 3).coerceIn(1, 8)
        val results = runCatching { getWebViewAgent().searchQuery(query, maxResults = maxResults) }.getOrNull()

        if (results.isNullOrEmpty()) {
            return ToolResult(toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.COMPLETED, output = "WebView 搜索完成，但未找到相关结果。", endTime = System.currentTimeMillis())))
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
                            val content = PromptSafety.sanitizeForPrompt(wvFetched.text, sourceLabel = r.url).content.take(4000)
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
            ?: "多模态虚拟点击执行失败"

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
                        output = output.ifBlank { "供应商联网查询返回为空" },
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

    override fun getTools(): Map<String, ToolBinding> {
        return mapOf(
            "web_search" to ToolBinding(
                name = "web_search",
                description = """搜索互联网获取最新信息。

⚠️ 仅在用户未提供具体 URL 时使用此工具。若用户已给出 URL，请使用 read_url 直接访问。""",
                invoker = { tp, config, sp, ups -> executeWebSearch(tp, config, sp, ups) },
            ),
        )
    }
}