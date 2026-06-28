package top.hsyscn.opedrgent.tools

import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.model.ToolStateType
import top.hsyscn.opedrgent.network.ToolResult
import top.hsyscn.opedrgent.network.emptyResult
import top.hsyscn.opedrgent.settings.ApiConfig
import top.hsyscn.opedrgent.utils.DebugLog

class OpenBrowserTool : ToolSet {

    @Tool("open_browser")
    @ToolDescription("在浏览器中打开指定的URL。参数中 url 为必填。")
    fun executeOpenBrowser(
        tp: ToolPart,
        config: ApiConfig,
        systemPrompt: String,
        useProviderSearch: Boolean,
    ): ToolResult {
        val url = tp.state.input["url"] ?: return emptyResult(tp, "缺少 URL")
        DebugLog.i("open_browser: $url")
        return ToolResult(
            toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.COMPLETED, output = "已在浏览器中打开：$url", endTime = System.currentTimeMillis())),
            openBrowserUrl = url,
        )
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