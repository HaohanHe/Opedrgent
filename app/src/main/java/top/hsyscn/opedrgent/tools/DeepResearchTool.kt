package top.hsyscn.opedrgent.tools

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.hsyscn.opedrgent.model.ChatMessage
import top.hsyscn.opedrgent.model.Role
import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.model.ToolStateType
import top.hsyscn.opedrgent.network.HybridRankingEngine
import top.hsyscn.opedrgent.network.LlmClient
import top.hsyscn.opedrgent.network.SearchConfig
import top.hsyscn.opedrgent.network.SearchResult
import top.hsyscn.opedrgent.network.SourceFetcher
import top.hsyscn.opedrgent.network.ToolResult
import top.hsyscn.opedrgent.network.WebSearcher
import top.hsyscn.opedrgent.network.WebViewAgent
import top.hsyscn.opedrgent.network.emptyResult
import top.hsyscn.opedrgent.settings.ApiConfig
import top.hsyscn.opedrgent.utils.DebugLog
import top.hsyscn.opedrgent.utils.PromptSafety
import top.hsyscn.opedrgent.utils.smartTruncate

class DeepResearchTool(
    private val context: Context,
    private val searcher: WebSearcher,
    private val fetcher: SourceFetcher,
    private val llm: LlmClient,
) : ToolSet {

    private val webViewMutex = Mutex()
    private var webViewAgent: WebViewAgent? = null

    private suspend fun getWebViewAgent(): WebViewAgent {
        // check-then-act 加锁：executeAll 在 Dispatchers.IO 并发跑多个 deep_research，
        // 无锁会 new 出多个 WebViewAgent（单个 50-100MB），后写覆盖导致前一实例无法 destroy 而泄漏。
        webViewAgent?.let { return it }
        return webViewMutex.withLock {
            webViewAgent ?: WebViewAgent(context).also { webViewAgent = it }
        }
    }

    @Tool("deep_research")
    @ToolDescription("进行深度研究：多轮搜索并整合结果，生成结构化的研究报告。参数中 query 或 topic 为必填。")
    suspend fun executeDeepResearch(
        tp: ToolPart,
        config: ApiConfig,
        systemPrompt: String,
        useProviderSearch: Boolean,
    ): ToolResult {
        val query = tp.state.input["query"] ?: tp.state.input["topic"] ?: return emptyResult(tp, "缺少研究主题")
        DebugLog.i("deep_research: $query")

        var results: List<SearchResult>? = null
        var usedWv = false

        if (useProviderSearch) {
            results = runCatching { searcher.searchAsync(query, config = SearchConfig(), limit = 8) }.getOrNull()
        }

        if (results.isNullOrEmpty()) {
            DebugLog.i("deep_research: using WebView builtin search")
            val wvResults = runCatching { getWebViewAgent().searchQuery(query, maxResults = 8) }.getOrNull()
            results = wvResults?.map { r -> SearchResult(title = r.title, url = r.url, snippet = r.snippet) }
            usedWv = true
        }

        if (results.isNullOrEmpty()) {
            return ToolResult(toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.COMPLETED, output = "深度研究完成，但未找到相关结果。", endTime = System.currentTimeMillis())))
        }

        // 使用 HybridRankingEngine 对搜索结果进行质量排序
        val rankingEngine = HybridRankingEngine()
        rankingEngine.initialize(query)
        val rankedResults = rankingEngine.rank(results, limit = results.size)
        val sortedResults = rankedResults.map { it.result }

        val maxFetch = (tp.state.input["max_fetch"]?.toIntOrNull() ?: 3).coerceIn(1, 5)
        val fetchedTexts = mutableListOf<String>()

        sortedResults.take(maxFetch).forEach { result ->
            // 用 Jina Reader 抓取正文，失败则用摘要
            val jinaResult = runCatching { searcher.fetchViaJina(result.url) }.getOrNull()
            if (jinaResult != null && jinaResult.text.length > 100) {
                val sanitized = PromptSafety.sanitizeForPrompt(jinaResult.text, sourceLabel = result.url)
                val title = jinaResult.title.takeIf { it.isNotBlank() } ?: result.title
                fetchedTexts.add("\n来源：$title (${result.url})\n${smartTruncate(sanitized.content, 5000)}\n")
            } else if (result.snippet != null && result.snippet.isNotBlank()) {
                fetchedTexts.add("\n来源：${result.title} (${result.url})\n${smartTruncate(result.snippet, 2000)}\n")
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
            appendLine(smartTruncate(combinedSource, 20000))
        }

        val summary = try {
            llm.chatCompletions(config = config, system = systemPrompt, messages = listOf(ChatMessage(role = Role.USER, content = summaryPrompt, createdAt = System.currentTimeMillis())))
        } catch (e: Exception) {
            "深度研究摘要生成失败：${e.message}\n\n=== 原始材料 ===\n${smartTruncate(combinedSource, 5000)}"
        }

        return ToolResult(toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.COMPLETED, output = summary, endTime = System.currentTimeMillis())))
    }

    /**
     * 释放 WebViewAgent 资源，不使用时必须调用以避免内存泄漏。
     * WebView 单个实例占用 50-100MB 内存。
     */
    fun destroy() {
        webViewAgent?.destroy()
        webViewAgent = null
    }

    override fun getTools(): Map<String, ToolBinding> {
        return mapOf(
            "deep_research" to ToolBinding(
                name = "deep_research",
                description = "进行深度研究：多轮搜索并整合结果，生成结构化的研究报告。参数中 query 或 topic 为必填。",
                parameters = org.json.JSONObject("""{
                    "type": "object",
                    "properties": {
                        "query": {"type": "string", "description": "研究主题/查询词（与 topic 二选一必填）"},
                        "topic": {"type": "string", "description": "研究主题（与 query 二选一必填）"},
                        "max_fetch": {"type": "integer", "description": "最多抓取的来源数量，1-5，默认 3"}
                    },
                    "required": ["query"]
                }"""),
                invoker = { tp, config, sp, ups -> executeDeepResearch(tp, config, sp, ups) },
            ),
        )
    }
}