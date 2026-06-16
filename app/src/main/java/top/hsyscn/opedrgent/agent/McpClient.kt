package top.hsyscn.opedrgent.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import top.hsyscn.opedrgent.utils.DebugLog
import java.util.concurrent.TimeUnit

/**
 * MCP (Model Context Protocol) 客户端
 *
 * 实现 JSON-RPC 2.0 over HTTP，支持：
 * - initialize: 握手
 * - tools/list: 动态发现工具
 * - tools/call: 执行远程工具
 *
 * 参考: https://modelcontextprotocol.io/specification/2025-03-26
 */
class McpClient(
    private val serverUrl: String,
    private val serverName: String = "mcp",
    private val headers: Map<String, String> = emptyMap(),
    private val timeoutSeconds: Long = 30,
) {
    companion object {
        private const val TAG = "McpClient"
        private const val JSON_RPC_VERSION = "2.0"
        private const val PROTOCOL_VERSION = "2025-03-26"
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .build()
    }

    private var initialized = false
    private var serverCapabilities: JSONObject? = null
    private var requestId = 0

    /**
     * MCP 工具定义（从 tools/list 返回）
     */
    data class McpTool(
        val name: String,
        val description: String,
        val inputSchema: JSONObject,
        val serverName: String,
    )

    /**
     * MCP 工具执行结果
     */
    data class McpToolResult(
        val content: String,
        val isError: Boolean = false,
    )

    /**
     * MCP 连接状态
     */
    sealed class McpStatus {
        object Disconnected : McpStatus()
        object Connecting : McpStatus()
        object Connected : McpStatus()
        data class Error(val message: String) : McpStatus()
    }

    @Volatile
    var status: McpStatus = McpStatus.Disconnected
        private set

    /**
     * 握手：发送 initialize 请求
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            status = McpStatus.Connecting
            val params = JSONObject().apply {
                put("protocolVersion", PROTOCOL_VERSION)
                put("capabilities", JSONObject())
                put("clientInfo", JSONObject().apply {
                    put("name", "Opedrgent")
                    put("version", "1.0.0")
                })
            }
            val result = sendRequest("initialize", params)
            if (result == null) {
                status = McpStatus.Error("initialize 返回空")
                return@withContext false
            }

            serverCapabilities = result.optJSONObject("capabilities")
            initialized = true
            status = McpStatus.Connected

            // 发送 initialized 通知
            sendNotification("notifications/initialized", null)

            DebugLog.i(TAG, "初始化成功: $serverUrl capabilities=$serverCapabilities")
            true
        } catch (e: Exception) {
            DebugLog.e(TAG, "初始化失败: ${e.message}", e)
            status = McpStatus.Error(e.message ?: "初始化失败")
            false
        }
    }

    /**
     * 发现工具：tools/list
     */
    suspend fun listTools(): List<McpTool> = withContext(Dispatchers.IO) {
        if (!initialized) {
            val ok = initialize()
            if (!ok) return@withContext emptyList()
        }

        val tools = mutableListOf<McpTool>()
        var cursor: String? = null

        do {
            val params = JSONObject().apply {
                if (cursor != null) put("cursor", cursor)
            }
            val result = sendRequest("tools/list", params) ?: break

            val toolsArray = result.optJSONArray("tools") ?: break
            for (i in 0 until toolsArray.length()) {
                val toolObj = toolsArray.getJSONObject(i)
                tools.add(McpTool(
                    name = toolObj.optString("name"),
                    description = toolObj.optString("description", ""),
                    inputSchema = toolObj.optJSONObject("inputSchema") ?: JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject())
                    },
                    serverName = serverName,
                ))
            }
            cursor = result.optString("nextCursor", null)
        } while (cursor != null)

        DebugLog.i(TAG, "发现 ${tools.size} 个工具: ${tools.joinToString { it.name }}")
        tools
    }

    /**
     * 执行工具：tools/call
     */
    suspend fun callTool(name: String, arguments: JSONObject): McpToolResult = withContext(Dispatchers.IO) {
        if (!initialized) {
            val ok = initialize()
            if (!ok) return@withContext McpToolResult("MCP 未连接", isError = true)
        }

        val params = JSONObject().apply {
            put("name", name)
            put("arguments", arguments)
        }

        val result = sendRequest("tools/call", params)
            ?: return@withContext McpToolResult("工具调用无响应", isError = true)

        val contentArray = result.optJSONArray("content") ?: JSONArray()
        val isError = result.optBoolean("isError", false)

        // 拼接所有 content blocks
        val text = buildString {
            for (i in 0 until contentArray.length()) {
                val block = contentArray.getJSONObject(i)
                when (block.optString("type")) {
                    "text" -> append(block.optString("text", ""))
                    "image" -> append("[图片: ${block.optString("mimeType", "unknown")}]")
                    "resource" -> append("[资源: ${block.optJSONObject("resource")?.optString("uri", "")}]")
                }
                if (i < contentArray.length() - 1) append("\n")
            }
        }

        McpToolResult(content = text.trim(), isError = isError)
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        initialized = false
        serverCapabilities = null
        status = McpStatus.Disconnected
        DebugLog.i(TAG, "已断开: $serverUrl")
    }

    // ==================== JSON-RPC 2.0 通信 ====================

    @Synchronized
    private fun nextRequestId(): Int = ++requestId

    /**
     * 发送 JSON-RPC 2.0 请求（同步等待响应）
     */
    private fun sendRequest(method: String, params: JSONObject?): JSONObject? {
        val id = nextRequestId()
        val request = JSONObject().apply {
            put("jsonrpc", JSON_RPC_VERSION)
            put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }

        DebugLog.d(TAG, "→ $method (id=$id)")

        val body = request.toString().toRequestBody("application/json".toMediaType())
        val reqBuilder = Request.Builder()
            .url(serverUrl)
            .post(body)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")

        headers.forEach { (k, v) -> reqBuilder.header(k, v) }

        val response = try {
            httpClient.newCall(reqBuilder.build()).execute()
        } catch (e: Exception) {
            DebugLog.e(TAG, "请求失败: ${e.message}", e)
            return null
        }

        response.use { resp ->
            if (!resp.isSuccessful) {
                DebugLog.e(TAG, "HTTP ${resp.code}: ${resp.message}")
                return null
            }

            val raw = resp.body?.string().orEmpty()
            if (raw.isBlank()) return null

            // 处理 SSE 响应
            val contentType = resp.header("Content-Type") ?: ""
            if (contentType.contains("text/event-stream")) {
                return parseSseResponse(raw, id)
            }

            // 普通 JSON 响应
            return parseJsonResponse(raw, id)
        }
    }

    /**
     * 发送 JSON-RPC 2.0 通知（无 id，不等待响应）
     */
    private fun sendNotification(method: String, params: JSONObject?) {
        val notification = JSONObject().apply {
            put("jsonrpc", JSON_RPC_VERSION)
            put("method", method)
            if (params != null) put("params", params)
        }

        DebugLog.d(TAG, "→ $method (notification)")

        val body = notification.toString().toRequestBody("application/json".toMediaType())
        val reqBuilder = Request.Builder()
            .url(serverUrl)
            .post(body)
            .header("Content-Type", "application/json")

        headers.forEach { (k, v) -> reqBuilder.header(k, v) }

        try {
            httpClient.newCall(reqBuilder.build()).execute().close()
        } catch (e: Exception) {
            DebugLog.w(TAG, "通知发送失败: ${e.message}")
        }
    }

    private fun parseJsonResponse(raw: String, expectedId: Int): JSONObject? {
        return try {
            val json = JSONObject(raw)
            handleResponse(json, expectedId)
        } catch (e: Exception) {
            DebugLog.e(TAG, "JSON 解析失败: ${e.message}")
            null
        }
    }

    private fun parseSseResponse(raw: String, expectedId: Int): JSONObject? {
        // SSE 格式: data: {...}\n\n
        val lines = raw.lines()
        for (line in lines) {
            if (!line.startsWith("data: ")) continue
            val data = line.removePrefix("data: ").trim()
            if (data == "[DONE]") continue
            try {
                val json = JSONObject(data)
                val result = handleResponse(json, expectedId)
                if (result != null) return result
            } catch (_: Exception) {
                // 忽略非 JSON 行
            }
        }
        return null
    }

    private fun handleResponse(json: JSONObject, expectedId: Int): JSONObject? {
        // 检查错误
        if (json.has("error")) {
            val error = json.getJSONObject("error")
            DebugLog.e(TAG, "JSON-RPC 错误: ${error.optInt("code")} ${error.optString("message")}")
            return null
        }

        // 检查 id 匹配
        val responseId = json.optInt("id", -1)
        if (responseId != expectedId) {
            DebugLog.w(TAG, "ID 不匹配: expected=$expectedId got=$responseId")
            return null
        }

        return json.optJSONObject("result")
    }
}
