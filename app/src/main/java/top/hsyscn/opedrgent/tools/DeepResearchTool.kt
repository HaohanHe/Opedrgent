package top.hsyscn.opedrgent.tools

import android.content.Context
import top.hsyscn.opedrgent.model.ChatMessage
import top.hsyscn.opedrgent.model.Role
import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.model.ToolStateType
import top.hsyscn.opedrgent.network.LlmClient
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
            results = runCatching { searcher.search(query, limit = 8) }.getOrNull()
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

        val warningsText = if (warnings.isNotEmpty()) "\n\n[警告] 以下来源抓取失败已跳过：\n${warnings.joinToString("\n")}" else ""

        return ToolResult(toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.COMPLETED, output = summary + warningsText, endTime = System.currentTimeMillis())))
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