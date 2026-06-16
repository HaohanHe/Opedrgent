package top.hsyscn.opedrgent.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.model.ToolStateType
import top.hsyscn.opedrgent.network.ToolResult
import top.hsyscn.opedrgent.settings.ApiConfig
import top.hsyscn.opedrgent.utils.DebugLog
import java.util.concurrent.TimeUnit

/**
 * step_search 工具 — 阶跃星辰原生搜索 API。
 *
 * 基于 StepFun Search API:
 * - 端点: POST https://api.stepfun.com/v1/search
 *
 * ## 与 WebSearchTool 的区别
 * - WebSearchTool: 走外部搜索引擎 (Bing/DDG/Baidu/SearXNG)，通用联网搜索
 * - StepSearchTool: 走阶跃自有搜索管道，针对中文优化，支持 category 分类
 *
 * ## Category 分类 (来自官方文档)
 * - `research`: 深度研究模式（更全面、更深度的搜索结果）
 * - `general`: 通用搜索（快速返回摘要结果）
 *
 * ## 使用场景
 * - 当需要高质量中文搜索结果时
 * - 当外部搜索引擎被限制或结果质量不佳时
 * - 作为 WebSearchTool 的增强/备选
 */
class StepSearchTool : ToolSet {

    companion object {
        private const val TAG = "StepSearch"
        private const val BASE_URL = "https://api.stepfun.com/v1"

        /** 支持的搜索分类 */
        const val CATEGORY_RESEARCH = "research"
        const val CATEGORY_GENERAL = "general"

        /** 默认返回结果数 */
        const val DEFAULT_N = 5

        /** 最大返回结果数 */
        const val MAX_N = 20
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    override fun getTools(): Map<String, ToolBinding> = mapOf(
        "step_search" to ToolBinding(
            name = "step_search",
            description = "使用阶跃星辰原生搜索API进行搜索。针对中文优化，支持深度研究模式和通用搜索。当需要高质量的中文搜索结果，或作为外部搜索引擎的补充时使用。",
            parameters = JSONObject("""{
                "type": "object",
                "properties": {
                    "query": {
                        "type": "string",
                        "description": "搜索查询词"
                    },
                    "category": {
                        "type": "string",
                        "enum": ["research", "general"],
                        "description": "搜索类别: research=深度研究(更全面深入), general=通用搜索(快速摘要)"
                    },
                    "n": {
                        "type": "integer",
                        "description": "返回结果数量 (1-20)，默认 5"
                    }
                },
                "required": ["query"]
            }"""),
            invoker = { toolPart, config, _, _ -> execute(toolPart, config) },
        ),
    )

    /**
     * 执行搜索。
     */
    private suspend fun execute(toolPart: ToolPart, config: ApiConfig): ToolResult {
        val input = toolPart.state.input
        DebugLog.i("StepSearchTool: 执行搜索 — input=${input.toString().take(200)}")

        return try {
            val args = JSONObject(input)
            val query = args.getString("query")
            if (query.isBlank()) return emptyResult(toolPart, "搜索查询不能为空")

            val category = args.optString("category", CATEGORY_RESEARCH)
                .ifBlank { CATEGORY_RESEARCH }
            val n = args.optInt("n", DEFAULT_N).coerceIn(1, MAX_N)

            // 调用 Search API
            val result = doSearch(config.apiKey, query, category, n)

            if (result.success) {
                successResult(toolPart, buildString {
                    appendLine("[阶跃搜索结果]")
                    appendLine("查询: $query")
                    appendLine("类别: $category | 结果数: ${result.items.size}")
                    appendLine()
                    result.items.forEachIndexed { index, item ->
                        appendLine("${index + 1}. ${item.title}")
                        if (item.url.isNotBlank()) appendLine("   URL: ${item.url}")
                        if (item.snippet.isNotBlank()) appendLine("   ${item.snippet}")
                        appendLine()
                    }
                    if (result.items.isEmpty()) {
                        appendLine("(无匹配结果)")
                    }
                })
            } else {
                emptyResult(toolPart, "搜索失败: ${result.errorMessage}")
            }
        } catch (e: Exception) {
            DebugLog.e("StepSearchTool 异常: ${e.message}", e)
            emptyResult(toolPart, "搜索异常: ${e.message}")
        }
    }

    /**
     * 调用阶跃搜索 API。
     */
    private suspend fun doSearch(
        apiKey: String,
        query: String,
        category: String,
        n: Int,
    ): SearchResult = withContext(Dispatchers.IO) {
        try {
            val jsonBody = JSONObject().apply {
                put("query", query)
                put("category", category)
                put("n", n)
            }

            val request = Request.Builder()
                .url("$BASE_URL/search")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json; charset=utf-8")
                .build()

            DebugLog.i(TAG, "调用搜索 API: query=$query, category=$category, n=$n")

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                DebugLog.e(TAG, "搜索失败 (${response.code}): $body")
                return@withContext SearchResult(false, errorMessage = "HTTP ${response.code}: ${extractError(body)}")
            }

            val json = JSONObject(body)
            val dataArr = json.optJSONArray("data") ?: return@withContext SearchResult(true, emptyList())

            val items = mutableListOf<SearchItem>()
            for (i in 0 until dataArr.length()) {
                val item = dataArr.getJSONObject(i)
                items.add(SearchItem(
                    title = item.optString("title", ""),
                    url = item.optString("url", ""),
                    snippet = item.optString("snippet", "").ifBlank { item.optString("content", "") },
                ))
            }

            SearchResult(success = true, items = items)
        } catch (e: Exception) {
            DebugLog.e(TAG, "搜索异常: ${e.message}", e)
            SearchResult(false, errorMessage = e.message ?: "未知错误")
        }
    }

    // ---- 数据类 ----

    data class SearchItem(
        val title: String,
        val url: String,
        val snippet: String,
    )

    data class SearchResult(
        val success: Boolean,
        val items: List<SearchItem> = emptyList(),
        val errorMessage: String? = null,
    )

    // ---- 辅助方法 ----

    private fun extractError(body: String): String {
        return try {
            val json = JSONObject(body)
            json.optJSONObject("error")?.optString("message")
                ?: json.optString("error", body.take(200))
        } catch (_: Exception) { body.take(200) }
    }

    private fun successResult(tp: ToolPart, text: String): ToolResult {
        return ToolResult(
            toolPart = tp.copy(
                state = tp.state.copy(
                    status = ToolStateType.COMPLETED,
                    output = text,
                    endTime = System.currentTimeMillis(),
                ),
            ),
        )
    }

    private fun emptyResult(tp: ToolPart, msg: String): ToolResult {
        return ToolResult(
            toolPart = tp.copy(
                state = tp.state.copy(
                    status = ToolStateType.ERROR,
                    error = msg,
                    endTime = System.currentTimeMillis(),
                ),
            ),
        )
    }

    /**
     * 验证 API Key 是否可用。
     */
    suspend fun validateApiKey(apiKey: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("query", "test")
                    put("category", "general")
                    put("n", 1)
                }
                val request = Request.Builder()
                    .url("$BASE_URL/search")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .header("Authorization", "Bearer $apiKey")
                    .build()

                client.newCall(request).execute().isSuccessful
            } catch (_: Exception) { false }
        }
}
