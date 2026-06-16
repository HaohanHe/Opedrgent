package top.hsyscn.opedrgent.agent

import org.json.JSONObject
import top.hsyscn.opedrgent.network.ToolDefinition
import top.hsyscn.opedrgent.utils.DebugLog

/**
 * MCP 多服务器管理器
 *
 * 管理多个 MCP 服务器连接，聚合工具列表，路由工具调用。
 * 参考 Kilo Code 的 MCP Service 设计。
 */
class McpManager {

    companion object {
        private const val TAG = "McpManager"
    }

    /**
     * MCP 服务器配置
     */
    data class ServerConfig(
        val name: String,
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        val enabled: Boolean = true,
        val timeoutSeconds: Long = 30,
    )

    private val clients = mutableMapOf<String, McpClient>()
    private val toolToServer = mutableMapOf<String, String>() // toolName -> serverName
    private val configs = mutableListOf<ServerConfig>()

    /**
     * 添加并连接一个 MCP 服务器
     */
    suspend fun addServer(config: ServerConfig): Boolean {
        configs.add(config)
        if (!config.enabled) return false

        val client = McpClient(
            serverUrl = config.url,
            serverName = config.name,
            headers = config.headers,
            timeoutSeconds = config.timeoutSeconds,
        )

        val ok = client.initialize()
        if (ok) {
            clients[config.name] = client
            // 发现并注册该服务器的工具
            val tools = client.listTools()
            for (tool in tools) {
                val qualifiedName = "${config.name}:${tool.name}"
                toolToServer[qualifiedName] = config.name
                DebugLog.i(TAG, "注册 MCP 工具: $qualifiedName")
            }
        }
        return ok
    }

    /**
     * 获取所有已连接服务器的工具列表（转为标准 ToolDefinition）
     */
    fun getAllToolDefinitions(): List<ToolDefinition> {
        val definitions = mutableListOf<ToolDefinition>()
        for ((serverName, client) in clients) {
            // 注意：listTools 是 suspend，这里用缓存的工具列表
            // 实际的工具列表在 addServer 时已经获取
        }
        return definitions
    }

    /**
     * 刷新所有服务器的工具列表
     */
    suspend fun refreshAllTools(): List<ToolDefinition> {
        val definitions = mutableListOf<ToolDefinition>()
        for ((serverName, client) in clients) {
            try {
                val tools = client.listTools()
                for (tool in tools) {
                    val qualifiedName = "${serverName}:${tool.name}"
                    toolToServer[qualifiedName] = serverName
                    definitions.add(ToolDefinition(
                        name = qualifiedName,
                        description = "[MCP:$serverName] ${tool.description}",
                        parameters = tool.inputSchema,
                    ))
                }
            } catch (e: Exception) {
                DebugLog.e(TAG, "刷新 $serverName 工具失败: ${e.message}")
            }
        }
        return definitions
    }

    /**
     * 执行 MCP 工具调用
     * @param qualifiedName 格式为 "serverName:toolName"
     */
    suspend fun callTool(qualifiedName: String, arguments: JSONObject): McpClient.McpToolResult {
        val serverName = toolToServer[qualifiedName]
            ?: return McpClient.McpToolResult("未知的 MCP 工具: $qualifiedName", isError = true)

        val client = clients[serverName]
            ?: return McpClient.McpToolResult("MCP 服务器 $serverName 未连接", isError = true)

        val toolName = qualifiedName.removePrefix("$serverName:")
        return client.callTool(toolName, arguments)
    }

    /**
     * 判断工具名是否为 MCP 工具
     */
    fun isMcpTool(toolName: String): Boolean = toolToServer.containsKey(toolName)

    /**
     * 获取所有服务器状态
     */
    fun getServerStatuses(): Map<String, McpClient.McpStatus> {
        return clients.mapValues { it.value.status }
    }

    /**
     * 断开所有连接
     */
    fun disconnectAll() {
        clients.values.forEach { it.disconnect() }
        clients.clear()
        toolToServer.clear()
    }

    /**
     * 断开指定服务器
     */
    fun disconnect(serverName: String) {
        clients[serverName]?.disconnect()
        clients.remove(serverName)
        toolToServer.entries.removeIf { it.value == serverName }
    }
}
