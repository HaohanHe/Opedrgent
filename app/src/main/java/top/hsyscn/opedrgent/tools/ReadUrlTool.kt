package top.hsyscn.opedrgent.tools

import android.content.Context
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.model.ToolStateType
import top.hsyscn.opedrgent.network.SourceFetcher
import top.hsyscn.opedrgent.network.ToolResult
import top.hsyscn.opedrgent.network.WebViewAgent
import top.hsyscn.opedrgent.network.emptyResult
import top.hsyscn.opedrgent.settings.ApiConfig
import top.hsyscn.opedrgent.utils.DebugLog
import top.hsyscn.opedrgent.utils.PromptSafety

class ReadUrlTool(
    private val context: Context,
    private val fetcher: SourceFetcher,
) : ToolSet {

    private var webViewAgent: WebViewAgent? = null

    private suspend fun getWebViewAgent(): WebViewAgent {
        return webViewAgent ?: WebViewAgent(context).also { webViewAgent = it }
    }

    private fun buildPartialTimeoutResult(
        tp: ToolPart,
        url: String,
        maxChars: Int,
        reason: String,
    ): ToolResult {
        val output = buildString {
            appendLine("[PARTIAL_TIMEOUT: read_url 超过 15 秒，仅获取到部分内容]")
            appendLine("页面标题：无标题")
            appendLine("URL：$url")
            appendLine("已获取内容：")
            appendLine("(未获取到片段)")
        }
        return ToolResult(toolPart = tp.copy(state = tp.state.copy(
            status = ToolStateType.PARTIAL_TIMEOUT,
            output = output.take(maxChars),
            error = reason,
            endTime = System.currentTimeMillis())))
    }

    @Tool("read_url")
    @ToolDescription("""读取并提取指定URL网页的文字内容。参数中 url 为必填。

⚠️ 使用场景：
- 用户消息中包含 URL（http/https）时，必须使用此工具
- 用户要求"打开链接"、"访问网址"时使用此工具
- 不要对具体 URL 使用 web_search""")
    suspend fun executeReadUrl(
        tp: ToolPart,
        config: ApiConfig,
        systemPrompt: String,
        useProviderSearch: Boolean,
    ): ToolResult {
        val url = tp.state.input["url"] ?: return emptyResult(tp, "缺少 URL")
        DebugLog.i("read_url: $url")
        // 弹性截断：根据模型上下文窗口按比例计算
        val maxChars = top.hsyscn.opedrgent.utils.ModelLimits.toolOutputMaxChars(
            top.hsyscn.opedrgent.utils.ModelLimits.inferMaxContextTokens(config.model)
        )

        var sourceError: String? = null
        val fetched = try {
            withTimeout(15_000) { fetcher.fetchUrl(url) }
        } catch (e: TimeoutCancellationException) {
            DebugLog.w("read_url: SourceFetcher timeout for $url")
            return buildPartialTimeoutResult(tp, url, maxChars, "SourceFetcher 超过 15 秒")
        } catch (e: Exception) {
            DebugLog.w("read_url: SourceFetcher failed: ${e.message}")
            sourceError = e.message
            null
        }
        if (fetched != null) {
            val sanitized = PromptSafety.sanitizeForPrompt(fetched.text, sourceLabel = url)
            val title = fetched.title?.takeIf { it.isNotBlank() } ?: "无标题"
            val output = buildString {
                appendLine("页面标题：$title")
                appendLine("URL：${fetched.url}")
                appendLine()
                appendLine(sanitized.content.take(maxChars))
            }
            return ToolResult(toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.COMPLETED, output = output, endTime = System.currentTimeMillis())))
        }

        DebugLog.w("read_url: SourceFetcher failed, trying WebView fallback")
        val wvFetched = try {
            withTimeout(15_000) { getWebViewAgent().fetchUrl(url) }
        } catch (e: TimeoutCancellationException) {
            DebugLog.w("read_url: WebView timeout for $url")
            return buildPartialTimeoutResult(tp, url, maxChars, "WebView 超过 15 秒")
        } catch (e: Exception) {
            DebugLog.w("read_url: WebView failed: ${e.message}")
            null
        }
        if (wvFetched != null) {
            val sanitized = PromptSafety.sanitizeForPrompt(wvFetched.text, sourceLabel = url)
            val output = buildString {
                appendLine("页面标题：${wvFetched.title}")
                appendLine("URL：$url")
                appendLine("（通过内置浏览器获取）")
                appendLine()
                appendLine(sanitized.content.take(maxChars))
            }
            return ToolResult(toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.COMPLETED, output = output, endTime = System.currentTimeMillis())))
        }

        // ★ BUG-03 修复：失败时使用 ERROR 状态，让 Guardrail 能检测到
        val reason = sourceError ?: "SourceFetcher 和 WebView 均失败"
        return ToolResult(toolPart = tp.copy(state = tp.state.copy(
            status = ToolStateType.ERROR,
            error = "读取失败：$url（$reason，请尝试其他来源或搜索关键词）",
            endTime = System.currentTimeMillis())))
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
            "read_url" to ToolBinding(
                name = "read_url",
                description = """读取并提取指定URL网页的文字内容。

⚠️ 当用户提供了具体 URL 时必须使用此工具，不要使用 web_search。""",
                invoker = { tp, config, sp, ups -> executeReadUrl(tp, config, sp, ups) },
            ),
        )
    }
}