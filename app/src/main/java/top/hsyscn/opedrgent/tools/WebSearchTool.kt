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

    /**
     * 搜索查询词预处理。
     *
     * LLM 生成的搜索词常存在以下问题：
     * 1. 多个关键词用空格/逗号/顿号拼接成一长串（如 "跨时代 人才 跃迁 计划 官方 文件"）
     * 2. 夹杂无意义的停用词和语气词
     * 3. 过长导致搜索引擎逐字匹配返回字典解释而非主题内容
     *
     * 本函数负责：
     * - 去除首尾空白，折叠连续空格
     * - 去除中文停用词（的/了/是/在/等无检索价值的字）
     * - 截断过长的中文 query（保留前 N 个有意义的片段）
     * - 去除重复关键词
     */
    private fun sanitizeQuery(raw: String): String {
        var q = raw.trim()

        if (q.isBlank()) return q

        val original = q

        // 1. 折叠连续空白（空格、全角空格、换行、制表符）
        q = q.replace(Regex("[\\s\\u3000]+"), " ").trim()

        // 2. 去除常见中文停用词
        //    单字停用词（独立出现时无检索价值，不影响词语内部如"计划"）
        val singleCharStopWords = setOf(
            '的', '了', '是', '在', '我', '你', '他', '她', '它',
            '这', '那', '有', '和', '与', '或', '等', '及', '中',
            '上', '下', '以', '对', '为', '从', '到', '把', '被',
            '让', '给', '向', '往', '比', '最', '更', '很', '太',
            '也', '都', '就', '又', '再', '还', '会', '能', '可',
            '要', '不', '没',
        )
        // 多字停用词（整个片段匹配时丢弃）
        val multiWordStopWords = setOf("什么", "怎么", "如何", "为何")
        // 按空格分词后，去掉纯停用词片段
        val segments = q.split(" ").filter { it.isNotBlank() }
        val cleanedSegments = segments.map { seg ->
            if (seg in multiWordStopWords) return@map ""
            if (seg.length == 1 && seg[0] in singleCharStopWords) return@map ""
            seg
        }.filter { it.isNotBlank() }

        q = cleanedSegments.joinToString(" ")

        // 3. 去重：如果相同关键词出现多次，只保留第一次
        val seen = mutableSetOf<String>()
        val deduped = mutableListOf<String>()
        for (seg in q.split(" ")) {
            val key = seg.lowercase()
            if (key !in seen) {
                seen.add(key)
                deduped.add(seg)
            }
        }
        q = deduped.joinToString(" ")

        // 4. 中文 query 长度控制：
        //    统计中文字符数，超过阈值时截取前几个最有意义的关键词片段
        val chineseCharCount = q.count { it in '\u4e00'..'\u9fff' }
        val maxChineseChars = 20 // 搜索引擎对中文的最佳输入长度
        if (chineseCharCount > maxChineseChars) {
            val parts = q.split(" ").toMutableList()
            var accumulated = 0
            val keep = mutableListOf<String>()
            for (part in parts) {
                val partCnCount = part.count { it in '\u4e00'..'\u9fff' }
                if (accumulated + partCnCount > maxChineseChars && keep.isNotEmpty()) break
                keep.add(part)
                accumulated += partCnCount
            }
            val truncated = keep.joinToString(" ")
            DebugLog.i("sanitizeQuery: 截断 '$q' -> '$truncated' (中文 ${chineseCharCount}->${accumulated} 字)")
            q = truncated
        }

        // 5. 最终清理：确保没有残留的多余空格
        q = q.replace(Regex("\\s+"), " ").trim()

        if (q != original) {
            DebugLog.i("sanitizeQuery: '$original' -> '$q'")
        }

        return q.ifBlank { original }
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
        var query = tp.state.input["query"] ?: tp.state.input["keyword"] ?: return emptyResult(tp, "缺少搜索关键词")

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

        // ★ 搜索查询词预处理：清洗空格、去除停用词、截断过长query、提取核心关键词
        query = sanitizeQuery(query)

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
            DebugLog.i("web_search: using provider native search (厂商内置模式)")
            return providerNativeSearch(tp, query, config)
        }

        DebugLog.i("web_search: query='$query'")
        // ★ 修复：搜索结果从3条增加到5条，提供更多参考来源
        val searchResults = searcher.searchAsync(query, buildSearchConfig(), limit = 5)

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

        // ★ 修复：max_fetch 默认值从1增加到2，获取更多网页正文
        val maxFetch = (tp.state.input["max_fetch"]?.toIntOrNull() ?: 2).coerceIn(1, 3)
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
            // ★ BUG-03 修复：无结果时使用 ERROR 状态
            return ToolResult(toolPart = tp.copy(state = tp.state.copy(
                status = ToolStateType.ERROR,
                error = "WebView 搜索未找到相关结果，请尝试其他关键词或搜索方式。",
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