package top.hsyscn.opedrgent.agent

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP 服务器配置持久化
 *
 * 使用 SharedPreferences 存储 MCP 服务器列表。
 * 配置格式与 Kilo Code 的 opencode.json 中 mcp 字段兼容。
 */
class McpConfigStore(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "mcp_servers", Context.MODE_PRIVATE
    )

    companion object {
        private const val KEY_SERVERS = "servers_json"
    }

    /**
     * 保存所有 MCP 服务器配置
     */
    fun saveServers(servers: List<McpManager.ServerConfig>) {
        val arr = JSONArray()
        for (s in servers) {
            arr.put(JSONObject().apply {
                put("name", s.name)
                put("url", s.url)
                put("enabled", s.enabled)
                put("timeoutSeconds", s.timeoutSeconds)
                if (s.headers.isNotEmpty()) {
                    val headersObj = JSONObject()
                    s.headers.forEach { (k, v) -> headersObj.put(k, v) }
                    put("headers", headersObj)
                }
            })
        }
        prefs.edit().putString(KEY_SERVERS, arr.toString()).apply()
    }

    /**
     * 加载所有 MCP 服务器配置
     */
    fun loadServers(): List<McpManager.ServerConfig> {
        val json = prefs.getString(KEY_SERVERS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                val headers = mutableMapOf<String, String>()
                obj.optJSONObject("headers")?.let { h ->
                    h.keys().forEach { k -> headers[k] = h.optString(k) }
                }
                McpManager.ServerConfig(
                    name = obj.optString("name", "mcp_$i"),
                    url = obj.optString("url", ""),
                    headers = headers,
                    enabled = obj.optBoolean("enabled", true),
                    timeoutSeconds = obj.optLong("timeoutSeconds", 30),
                )
            }.filter { it.url.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 添加一个服务器配置
     */
    fun addServer(config: McpManager.ServerConfig) {
        val current = loadServers().toMutableList()
        current.removeAll { it.name == config.name }
        current.add(config)
        saveServers(current)
    }

    /**
     * 删除一个服务器配置
     */
    fun removeServer(name: String) {
        val current = loadServers().toMutableList()
        current.removeAll { it.name == name }
        saveServers(current)
    }

    /**
     * 更新服务器启用状态
     */
    fun setEnabled(name: String, enabled: Boolean) {
        val current = loadServers().toMutableList()
        val idx = current.indexOfFirst { it.name == name }
        if (idx >= 0) {
            current[idx] = current[idx].copy(enabled = enabled)
            saveServers(current)
        }
    }
}
