package top.hsyscn.opedrgent.tools

import android.net.Uri
import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.model.ToolStateType
import top.hsyscn.opedrgent.network.ToolResult
import top.hsyscn.opedrgent.network.emptyResult
import top.hsyscn.opedrgent.settings.ApiConfig
import top.hsyscn.opedrgent.utils.DebugLog

class OpenBrowserTool(
    private val requestConfirmation: suspend (ToolConfirmation) -> Boolean = { true },
) : ToolSet {

    @Tool("open_browser")
    @ToolDescription("在浏览器中打开指定的URL。参数中 url 为必填。")
    suspend fun executeOpenBrowser(
        tp: ToolPart,
        config: ApiConfig,
        systemPrompt: String,
        useProviderSearch: Boolean,
    ): ToolResult {
        val url = tp.state.input["url"] ?: return emptyResult(tp, "缺少 URL")
        if (!isUrlSafe(url)) {
            return emptyResult(tp, "URL 不合法或协议不受支持：$url")
        }
        val confirmed = requestConfirmation(
            ToolConfirmation(
                toolName = "open_browser",
                action = "在浏览器中打开链接",
                detail = "AI 请求打开外部链接：\n$url",
            )
        )
        if (!confirmed) {
            return emptyResult(tp, "用户拒绝了打开链接操作")
        }
        DebugLog.i("open_browser: $url")
        return ToolResult(
            toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.COMPLETED, output = "已在浏览器中打开：$url", endTime = System.currentTimeMillis())),
            openBrowserUrl = url,
        )
    }

    private fun isUrlSafe(url: String): Boolean {
        return try {
            val uri = Uri.parse(url)
            val scheme = uri.scheme?.lowercase()
            scheme == "http" || scheme == "https"
        } catch (_: Exception) {
            false
        }
    }

    override fun getTools(): Map<String, ToolBinding> {
        return mapOf(
            "open_browser" to ToolBinding(
                name = "open_browser",
                description = "在浏览器中打开指定的URL。参数中 url 为必填。",
                invoker = { tp, config, sp, ups -> executeOpenBrowser(tp, config, sp, ups) },
            ),
        )
    }
}