package top.hsyscn.opedrgent.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import top.hsyscn.opedrgent.utils.DebugLog

/**
 * 从 OpenAI 兼容的 /v1/models 端点实时获取可用模型列表。
 * 绝大多数 LLM API 提供商（OpenAI、SiliconFlow、DeepSeek、通义千问等）都支持此接口。
 */
object ModelFetcher {

    /**
     * 获取模型列表。
     *
     * 支持 OpenAI 兼容的 /v1/models 端点。
     * 对 SiliconFlow 特殊处理：添加 type=text&sub_type=chat 过滤，避免返回大量图片/音频模型。
     *
     * @param baseUrl API 基础 URL，例如 "https://api.siliconflow.com/v1"
     * @param apiKey  API Key
     * @return 模型 ID 列表（已排序），失败时返回 null
     */
    suspend fun fetchModels(baseUrl: String, apiKey: String): List<String>? = withContext(Dispatchers.IO) {
        val base = baseUrl.trimEnd('/')

        // SiliconFlow 支持 type/sub_type 过滤，只获取文本对话模型
        val url = if (base.contains("siliconflow")) {
            "$base/models?type=text&sub_type=chat"
        } else {
            "$base/models"
        }

        DebugLog.i("ModelFetcher: fetching from $url")

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "application/json")
            .get()
            .build()

        try {
            HttpClients.default.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errBody = response.body?.string()?.take(500).orEmpty()
                    DebugLog.w("ModelFetcher: HTTP ${response.code} from $url — $errBody")
                    return@use null
                }

                val body = response.body?.string() ?: run {
                    DebugLog.w("ModelFetcher: empty response body from $url")
                    return@use null
                }

                val json = JSONObject(body)
                val data = json.optJSONArray("data") ?: run {
                    DebugLog.w("ModelFetcher: no 'data' array in response: ${body.take(200)}")
                    return@use null
                }

                val models = mutableListOf<String>()
                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    val id = item.optString("id", "")
                    if (id.isNotBlank()) {
                        models.add(id)
                    }
                }

                models.sorted().also {
                    DebugLog.i("ModelFetcher: fetched ${it.size} models from $url")
                }
            }
        } catch (e: Exception) {
            DebugLog.e("ModelFetcher: failed to fetch from $url: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }
}
