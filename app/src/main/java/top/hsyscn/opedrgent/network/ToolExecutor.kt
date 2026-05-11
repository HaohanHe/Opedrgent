package top.hsyscn.opedrgent.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.model.ChatMessage
import top.hsyscn.opedrgent.model.Role
import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.model.ToolState
import top.hsyscn.opedrgent.model.ToolStateType
import top.hsyscn.opedrgent.settings.ApiConfig
import top.hsyscn.opedrgent.utils.DebugLog
import top.hsyscn.opedrgent.utils.PromptSafety
import java.util.concurrent.ConcurrentHashMap

data class ToolResult(
    val toolPart: ToolPart,
    val openUrl: String? = null,
    val openBrowserUrl: String? = null,
    val addedSources: List<String> = emptyList(),
)

class ToolExecutor(
    private val context: Context,
    private val searcher: WebSearcher,
    private val fetcher: SourceFetcher,
    private val llm: LlmClient,
) {

    private var webViewAgent: WebViewAgent? = null
    private val translationCache = ConcurrentHashMap<String, String>()

    private suspend fun getWebViewAgent(): WebViewAgent {
        if (webViewAgent == null) {
            webViewAgent = WebViewAgent(context)
        }
        return webViewAgent!!
    }

    suspend fun execute(
        toolPart: ToolPart,
        config: ApiConfig,
        systemPrompt: String,
        useProviderSearch: Boolean = true,
    ): ToolResult = withContext(Dispatchers.IO) {
        val started = toolPart.copy(
            state = toolPart.state.copy(
                status = ToolStateType.RUNNING,
                startTime = System.currentTimeMillis(),
            ),
        )
        DebugLog.i("ToolExecutor.execute: ${started.tool} with ${started.state.input}")

        try {
            when (started.tool) {
                "web_search" -> executeWebSearch(started, config, systemPrompt, useProviderSearch)
                "open_browser" -> executeOpenBrowser(started)
                "deep_research" -> executeDeepResearch(started, config, systemPrompt, useProviderSearch)
                "read_url" -> executeReadUrl(started)
                "question" -> executeQuestion(started)
                "generate_report", "generate_summary" -> executeGenerate(started)
                else -> unknownTool(started)
            }
        } catch (e: Exception) {
            DebugLog.e("ToolExecutor error for ${started.tool}: ${e.message}", e)
            ToolResult(
                toolPart = started.copy(
                    state = started.state.copy(
                        status = ToolStateType.ERROR,
                        error = e.message ?: "Tool execution failed",
                        endTime = System.currentTimeMillis(),
                    ),
                ),
            )
        }
    }

    fun destroy() {
        webViewAgent?.destroy()
        webViewAgent = null
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

    private suspend fun executeWebSearch(
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
        }

        if (!useProviderSearch) {
            DebugLog.i("web_search: using WebView builtin search (provider disabled)")
            return webviewSearch(tp, query)
        }

        val hasChinese = WebSearcher.containsChinese(query)
        val searchResults = if (hasChinese) {
            val queryEn = translateQueryToEnglish(query, config)
            DebugLog.i("web_search: dual search zh='$query' en='$queryEn'")
            searcher.searchDual(query, queryEn, limit = 5)
        } else {
            searcher.search(query, limit = 5)
        }

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
                appendLine("${idx + 1}. [${r.title}](${r.url})")
                appendLine("   ${r.snippet}")
                appendLine()
            }
        }

        val maxFetch = (tp.state.input["max_fetch"]?.toIntOrNull() ?: 3).coerceIn(1, 5)
        val fetchedResults = mutableListOf<String>()
        val fetchedSources = mutableListOf<String>()
        val toFetch = searchResults.take(maxFetch)

        toFetch.forEach { result ->
            val jinaResult = runCatching { searcher.fetchViaJina(result.url) }.getOrNull()
            if (jinaResult != null && jinaResult.text.length > 100) {
                val sanitized = PromptSafety.sanitizeForPrompt(jinaResult.text, sourceLabel = result.url)
                val content = sanitized.content.take(4000)
                fetchedResults.add("\n--- 来源：${jinaResult.title.takeIf { it.isNotBlank() } ?: result.title} (${result.url}) ---\n$content\n")
                fetchedSources.add(result.url)
                return@forEach
            }

            runCatching { fetcher.fetchUrl(result.url) }.onSuccess { fetched ->
                val sanitized = PromptSafety.sanitizeForPrompt(fetched.text, sourceLabel = result.url)
                val content = sanitized.content.take(4000)
                fetchedResults.add("\n--- 来源：${fetched.title?.takeIf { it.isNotBlank() } ?: result.url} ---\n$content\n")
                fetchedSources.add(result.url)
            }.onFailure { e ->
                DebugLog.w("web_search fetch failed: ${result.url} - ${e.message}, trying WebView")
                runCatching { getWebViewAgent().fetchUrl(result.url) }.onSuccess { wvFetched ->
                    if (wvFetched != null) {
                        val wvContent = PromptSafety.sanitizeForPrompt(wvFetched.text, sourceLabel = result.url).content.take(4000)
                        fetchedResults.add("\n--- 来源（WebView降级）：${wvFetched.title} ---\n$wvContent\n")
                        fetchedSources.add(result.url)
                    }
                }
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
                appendLine("   ${r.snippet}")
                appendLine()
            }
        }

        val maxFetch = maxResults.coerceAtMost(results.size)
        val fetchedResults = mutableListOf<String>()
        val fetchedSources = mutableListOf<String>()
        results.take(maxFetch).forEach { r ->
            if (r.url.isNotBlank()) {
                runCatching { getWebViewAgent().fetchUrl(r.url) }.onSuccess { wvFetched ->
                    if (wvFetched != null) {
                        val content = PromptSafety.sanitizeForPrompt(wvFetched.text, sourceLabel = r.url).content.take(4000)
                        fetchedResults.add("\n--- 来源：${wvFetched.title} (${r.url}) ---\n$content\n")
                        fetchedSources.add(r.url)
                    }
                }
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

    private fun executeOpenBrowser(tp: ToolPart): ToolResult {
        val url = tp.state.input["url"] ?: return emptyResult(tp, "缺少 URL")
        DebugLog.i("open_browser: $url")
        return ToolResult(
            toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.COMPLETED, output = "已在浏览器中打开：$url", endTime = System.currentTimeMillis())),
            openBrowserUrl = url,
        )
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
                        appendLine("   ${r.snippet}")
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

    private suspend fun executeDeepResearch(
        tp: ToolPart,
        config: ApiConfig,
        systemPrompt: String,
        useProviderSearch: Boolean,
    ): ToolResult {
        val query = tp.state.input["query"] ?: tp.state.input["topic"] ?: return emptyResult(tp, "缺少研究主题")
        DebugLog.i("deep_research: $query")

        var results: List<top.hsyscn.opedrgent.network.SearchResult>? = null
        var usedWv = false

        if (useProviderSearch) {
            val hasChinese = WebSearcher.containsChinese(query)
            results = if (hasChinese) {
                val queryEn = translateQueryToEnglish(query, config)
                runCatching { searcher.searchDual(query, queryEn, limit = 8) }.getOrNull()
            } else {
                runCatching { searcher.searchDuckDuckGo(query, limit = 8) }.getOrNull()
            }
        }

        if (results.isNullOrEmpty()) {
            DebugLog.i("deep_research: using WebView builtin search")
            val wvResults = runCatching { getWebViewAgent().searchQuery(query, maxResults = 8) }.getOrNull()
            results = wvResults?.map { r -> top.hsyscn.opedrgent.network.SearchResult(title = r.title, url = r.url, snippet = r.snippet) }
            usedWv = true
        }

        if (results.isNullOrEmpty()) {
            return ToolResult(toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.COMPLETED, output = "深度研究完成，但未找到相关结果。", endTime = System.currentTimeMillis())))
        }

        val maxFetch = (tp.state.input["max_fetch"]?.toIntOrNull() ?: 5).coerceIn(1, 8)
        val fetchedTexts = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        results.take(maxFetch).forEach { result ->
            if (usedWv) {
                runCatching { getWebViewAgent().fetchUrl(result.url) }.onSuccess { wvFetched ->
                    if (wvFetched != null) {
                        val sanitized = PromptSafety.sanitizeForPrompt(wvFetched.text, sourceLabel = result.url)
                        fetchedTexts.add("\n来源：${wvFetched.title} (${result.url})\n${sanitized.content.take(5000)}\n")
                    }
                }.onFailure { e -> warnings.add("跳过：${result.url}（${e.message}）") }
            } else {
                val jinaResult = runCatching { searcher.fetchViaJina(result.url) }.getOrNull()
                if (jinaResult != null && jinaResult.text.length > 100) {
                    val sanitized = PromptSafety.sanitizeForPrompt(jinaResult.text, sourceLabel = result.url)
                    val title = jinaResult.title.takeIf { it.isNotBlank() } ?: result.title
                    fetchedTexts.add("\n来源：$title (${result.url})\n${sanitized.content.take(5000)}\n")
                    return@forEach
                }

                runCatching { fetcher.fetchUrl(result.url) }.onSuccess { fetched ->
                    val sanitized = PromptSafety.sanitizeForPrompt(fetched.text, sourceLabel = result.url)
                    val title = fetched.title?.takeIf { it.isNotBlank() } ?: result.url
                    fetchedTexts.add("\n来源：$title (${result.url})\n${sanitized.content.take(5000)}\n")
                }.onFailure { e ->
                    DebugLog.w("deep_research fetch failed: ${result.url}, trying WebView")
                    runCatching { getWebViewAgent().fetchUrl(result.url) }.onSuccess { wvFetched ->
                        if (wvFetched != null) {
                            fetchedTexts.add("\n来源（WebView降级）：${wvFetched.title} (${result.url})\n${PromptSafety.sanitizeForPrompt(wvFetched.text, sourceLabel = result.url).content.take(5000)}\n")
                        }
                    }.onFailure { e2 -> warnings.add("跳过：${result.url}（fetch:${e.message} wv:${e2.message}）") }
                }
            }
        }

        val combinedSource = fetchedTexts.joinToString("\n---\n")
        val summaryPrompt = buildString {
            appendLine("请基于以下来源进行深度研究，生成结构化的研究报告。")
            appendLine("研究主题：$query")
            appendLine()
            appendLine("要求：")
            appendLine("- 包含执行摘要、关键发现、详细分析、结论")
            appendLine("- 标注来源引用")
            appendLine("- 标注可信度评估")
            appendLine()
            appendLine("=== 来源材料 ===")
            appendLine(combinedSource.take(15000))
        }

        val summary = try {
            llm.chatCompletions(config = config, system = systemPrompt, messages = listOf(ChatMessage(role = Role.USER, content = summaryPrompt, createdAt = System.currentTimeMillis())))
        } catch (e: Exception) {
            "深度研究摘要生成失败：${e.message}\n\n=== 原始材料 ===\n${combinedSource.take(3000)}"
        }

        val warningsText = if (warnings.isNotEmpty()) "\n\n⚠ 以下来源抓取失败已跳过：\n${warnings.joinToString("\n")}" else ""

        return ToolResult(toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.COMPLETED, output = summary + warningsText, endTime = System.currentTimeMillis())))
    }

    private suspend fun executeReadUrl(tp: ToolPart): ToolResult {
        val url = tp.state.input["url"] ?: return emptyResult(tp, "缺少 URL")
        DebugLog.i("read_url: $url")

        val fetched = runCatching { fetcher.fetchUrl(url) }.getOrNull()
        if (fetched != null) {
            val sanitized = PromptSafety.sanitizeForPrompt(fetched.text, sourceLabel = url)
            val title = fetched.title?.takeIf { it.isNotBlank() } ?: "无标题"
            val output = buildString {
                appendLine("页面标题：$title")
                appendLine("URL：${fetched.url}")
                appendLine()
                appendLine(sanitized.content.take(6000))
            }
            return ToolResult(toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.COMPLETED, output = output, endTime = System.currentTimeMillis())))
        }

        DebugLog.w("read_url: SourceFetcher failed, trying WebView fallback")
        val wvFetched = runCatching { getWebViewAgent().fetchUrl(url) }.getOrNull()
        if (wvFetched != null) {
            val sanitized = PromptSafety.sanitizeForPrompt(wvFetched.text, sourceLabel = url)
            val output = buildString {
                appendLine("页面标题：${wvFetched.title}")
                appendLine("URL：$url")
                appendLine("（通过内置浏览器获取）")
                appendLine()
                appendLine(sanitized.content.take(6000))
            }
            return ToolResult(toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.COMPLETED, output = output, endTime = System.currentTimeMillis())))
        }

        return ToolResult(toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.COMPLETED, output = "读取失败：$url（已跳过，请尝试其他来源）", endTime = System.currentTimeMillis())))
    }

    private fun executeQuestion(tp: ToolPart): ToolResult {
        return ToolResult(toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.COMPLETED, output = "等待用户选择", endTime = System.currentTimeMillis())))
    }

    private fun executeGenerate(tp: ToolPart): ToolResult {
        return ToolResult(toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.COMPLETED, output = "generate 操作由系统在工具循环完成后处理", endTime = System.currentTimeMillis())))
    }

    private fun unknownTool(tp: ToolPart): ToolResult {
        DebugLog.w("Unknown tool: ${tp.tool}")
        return ToolResult(toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.ERROR, error = "未知工具：${tp.tool}", endTime = System.currentTimeMillis())))
    }

    private fun emptyResult(tp: ToolPart, msg: String): ToolResult {
        return ToolResult(toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.ERROR, error = msg, endTime = System.currentTimeMillis())))
    }
}