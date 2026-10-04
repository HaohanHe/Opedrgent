package top.hsyscn.opedrgent.tools

import android.content.Context
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import top.hsyscn.opedrgent.settings.ApiConfig

/**
 * 端侧离线工具目录：把纯本地、无网络依赖的工具装配为 LiteRT-LM [ToolProvider]，
 * 供 [top.hsyscn.opedrgent.llm.LocalLlmEngine] 在创建会话时注入。
 *
 * 进程内单例缓存（工具集与 native 描述只需构建一次）。工具是否被调用由模型在
 * 受约束解码下自主决定，目录本身不做关键词或意图判定。
 */
object LocalToolCatalog {

    @Volatile
    private var cached: List<ToolProvider>? = null

    fun get(context: Context): List<ToolProvider> {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: build(context.applicationContext).also { cached = it }
        }
    }

    private fun build(ctx: Context): List<ToolProvider> {
        // 离线工具不使用云端配置，传空 ApiConfig（各 invoker 均以 _ 忽略该参数）。
        val apiConfig = ApiConfig(baseUrl = "", apiKey = "", model = "")

        // 仅注册已确认纯本地、无网络、无云依赖的工具；数字参数由适配器
        // 序列化为字符串后由各工具自行解析。是否调用由模型在受约束解码下决定。
        val offlineSets: List<ToolSet> = listOf(
            TodoWriteTool(ctx),
            LocalModelTool(ctx),
            RecallTool(ctx),
            ActionItemTool(ctx),
        )
        // 真机验证稳定后可按需纳入：BackupTool、RunJsTool、RunIntentTool、
        // RunCalendarTool、SatellitePassTool、ReverseGeocodeTool 等。

        return offlineSets
            .flatMap { it.getTools().values }
            .map { tool(LocalOpenApiToolAdapter(it, apiConfig)) }
    }
}
