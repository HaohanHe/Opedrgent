package top.hsyscn.opedrgent.agent

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP 服务器配置持久化
 *
 * 使用 SharedPreferences 存储 MCP 服务器列表。
 * 配置格式与 Kilo Code 的 opencode.json 中 mcp 字段兼容。
 *
 * 安全：服务器自定义 headers（常含 Authorization: Bearer 等敏感凭据）整体写入
 * 独立加密 prefs opedrgent_mcp_secure，明文 prefs 中不再保留任何头值；
 * name/url/enabled/timeoutSeconds 等非敏感项仍留明文。
 */
class McpConfigStore(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "mcp_servers", Context.MODE_PRIVATE
    )

    // 敏感 headers 写入独立加密 prefs；MasterKey 构建方式对齐 ApiSettings / NoteSyncService。
    // 不读写 opedrgent_secure，使用独立文件 opedrgent_mcp_secure。
    private val securePrefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    init {
        migrateLegacyHeaders()
    }

    companion object {
        private const val KEY_SERVERS = "servers_json"
        private const val SECURE_PREFS_NAME = "opedrgent_mcp_secure"

        /** 加密 prefs 中每服务器 headers 的键前缀；完整键为 "headers:<serverName>"。 */
        private const val HEADER_KEY_PREFIX = "headers:"

        /** 旧明文 headers 一次性幂等迁移标志位（存于明文 prefs，非敏感）。 */
        private const val MCP_HEADERS_MIGRATED = "mcp_secure_migrated"
    }

    private fun headersKey(serverName: String): String = HEADER_KEY_PREFIX + serverName

    /**
     * 保存所有 MCP 服务器配置
     *
     * 明文 prefs 仅写非敏感项；headers 整体写入加密 prefs，并清理已删除服务器遗留的加密键。
     */
    fun saveServers(servers: List<McpManager.ServerConfig>) {
        val arr = JSONArray()
        val secureEdit = securePrefs.edit()
        for (s in servers) {
            arr.put(JSONObject().apply {
                put("name", s.name)
                put("url", s.url)
                put("enabled", s.enabled)
                put("timeoutSeconds", s.timeoutSeconds)
                // headers 不写明文；敏感值改走加密 prefs
            })
            val hk = headersKey(s.name)
            if (s.headers.isNotEmpty()) {
                val headersObj = JSONObject()
                s.headers.forEach { (k, v) -> headersObj.put(k, v) }
                secureEdit.putString(hk, headersObj.toString())
            } else {
                secureEdit.remove(hk)
            }
        }
        // 清理已删除服务器遗留的加密 headers 键
        val currentNames = servers.map { it.name }.toSet()
        for (k in securePrefs.all.keys) {
            if (k.startsWith(HEADER_KEY_PREFIX) &&
                k.removePrefix(HEADER_KEY_PREFIX) !in currentNames
            ) {
                secureEdit.remove(k)
            }
        }
        secureEdit.apply()
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
                val name = obj.optString("name", "mcp_$i")
                McpManager.ServerConfig(
                    name = name,
                    url = obj.optString("url", ""),
                    headers = readHeaders(name),
                    enabled = obj.optBoolean("enabled", true),
                    timeoutSeconds = obj.optLong("timeoutSeconds", 30),
                )
            }.filter { it.url.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 从加密 prefs 还原某服务器的自定义 headers。 */
    private fun readHeaders(serverName: String): Map<String, String> {
        val raw = runCatching { securePrefs.getString(headersKey(serverName), null) }
            .getOrNull() ?: return emptyMap()
        return try {
            val h = JSONObject(raw)
            HashMap<String, String>().apply {
                h.keys().forEach { k -> this[k] = h.optString(k) }
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * 幂等迁移：把旧版本以明文存于 mcp_servers 的每服务器 headers 搬入加密 prefs。
     *
     * 规则：明文仍含 headers 而加密 prefs 为空时才搬迁；成功后从明文 JSON 移除 headers 字段并置标志位。
     * 失败安全：加密/Keystore 不可用导致异常时，不置标志位、也不移除明文，下次启动重试，
     * 避免在无法加密落地时误删明文 headers 而丢失凭据。全程不打印任何凭据内容。
     */
    private fun migrateLegacyHeaders() {
        if (prefs.getBoolean(MCP_HEADERS_MIGRATED, false)) return
        runCatching {
            val raw = prefs.getString(KEY_SERVERS, null)
            if (raw.isNullOrEmpty()) {
                prefs.edit().putBoolean(MCP_HEADERS_MIGRATED, true).apply()
                return@runCatching
            }
            val arr = JSONArray(raw)
            val rewritten = JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val name = obj.optString("name", "mcp_$i")
                // 搬迁：明文含 headers 而加密为空时，整段 headers JSON 写入加密 prefs
                obj.optJSONObject("headers")?.let { h ->
                    if (h.length() > 0) {
                        val hk = headersKey(name)
                        if (securePrefs.getString(hk, null).isNullOrEmpty()) {
                            securePrefs.edit().putString(hk, h.toString()).apply()
                        }
                    }
                }
                // 明文不再保留 headers
                obj.remove("headers")
                rewritten.put(obj)
            }
            prefs.edit()
                .putString(KEY_SERVERS, rewritten.toString())
                .putBoolean(MCP_HEADERS_MIGRATED, true)
                .apply()
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
