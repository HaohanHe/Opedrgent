package top.hsyscn.opedrgent.tools

import android.content.Context
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
import top.hsyscn.opedrgent.utils.DebugLog
import top.hsyscn.opedrgent.utils.PromptSafety

class DeepResearchTool(
    private val context: Context,
    private val searcher: WebSearcher,
    private val fetcher: SourceFetcher,
    private val llm: LlmClient,
) : ToolSet {

    private var webViewAgent: WebViewAgent? = null

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
     * 智能截取：按段落/句子边界截取，保留完整信息
     */
    private fun smartTruncate(text: String, maxLen: Int): String {
        if (text.length <= maxLen) return text
        val truncated = text.take(maxLen)
        val lastParagraph = truncated.lastIndexOf("\n\n")
        if (lastParagraph > maxLen * 0.6) return truncated.substring(0, lastParagraph)
        val lastSentence = maxOf(truncated.lastIndexOf("。"), truncated.lastIndexOf(". "), truncated.lastIndexOf("！"), truncated.lastIndexOf("？"))
        if (lastSentence > maxLen * 0.5) return truncated.substring(0, lastSentence + 1)
        val lastSpace = truncated.lastIndexOf(' ')
        if (lastSpace > maxLen * 0.7) return truncated.substring(0, lastSpace)
        return truncated
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

        val maxFetch = (tp.state.input["max_fetch"]?.toIntOrNull() ?: 3).coerceIn(1, 5)
        val fetchedTexts = mutableListOf<String>()

        results.take(maxFetch).forEach { result ->
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

    override fun getTools(): Map<String, ToolBinding> {
        return mapOf(
            "deep_research" to ToolBinding(
                name = "deep_research",
                description = "进行深度研究：多轮搜索并整合结果，生成结构化的研究报告。参数中 query 或 topic 为必填。",
                invoker = { tp, config, sp, ups -> executeDeepResearch(tp, config, sp, ups) },
            ),
        )
    }
}