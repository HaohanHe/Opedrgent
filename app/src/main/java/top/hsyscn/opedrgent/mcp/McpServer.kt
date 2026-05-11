package top.hsyscn.opedrgent.mcp

import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.hsyscn.opedrgent.utils.DebugLog
import java.util.concurrent.ConcurrentHashMap

interface McpToolHandler {
    val name: String
    val description: String?
    val inputSchema: JsonObject
    suspend fun execute(arguments: JsonObject): JsonElement
}

class McpServer(
    private val serverName: String = "Opedrgent-Server",
    private val serverVersion: String = "1.0.0",
) {

    private val tools = ConcurrentHashMap<String, McpToolHandler>()
    private var isRunning = false
    private var scope: CoroutineScope? = null

    fun registerTool(handler: McpToolHandler) {
        tools[handler.name] = handler
        DebugLog.i("McpServer: registered tool ${handler.name}")
    }

    fun unregisterTool(name: String) {
        tools.remove(name)
        DebugLog.i("McpServer: unregistered tool $name")
    }

    fun listRegisteredTools(): List<McpToolHandler> {
        return tools.values.toList()
    }

    suspend fun handleRequest(requestJson: String): String {
        val request = try {
            McpProtocol.parseRequest(requestJson)
        } catch (e: Exception) {
            return createErrorResponse(null, -32700, "Parse error: ${e.message}")
        }

        return when (request.method) {
            "initialize" -> handleInitialize(request)
            "tools/list" -> handleToolsList(request)
            "tools/call" -> handleToolCall(request)
            else -> createErrorResponse(request.id, -32601, "Method not found: ${request.method}")
        }
    }

    private suspend fun handleInitialize(request: McpRequest): String {
        val result = InitializeResult(
            protocolVersion = McpProtocol.PROTOCOL_VERSION,
            capabilities = ServerCapabilities(
                tools = ToolsCapability(),
            ),
            serverInfo = ServerInfo(name = serverName, version = serverVersion),
        )

        val response = McpResponse(
            id = request.id,
            result = McpProtocol.JSON.encodeToJsonElement(InitializeResult.serializer(), result),
        )

        DebugLog.i("McpServer: initialized by client")

        return McpProtocol.serializeResponse(response)
    }

    private suspend fun handleToolsList(request: McpRequest): String {
        val toolInfos = tools.values.map { handler ->
            buildJsonObject {
                put("name", handler.name)
                if (handler.description != null) {
                    put("description", handler.description)
                }
                put("inputSchema", handler.inputSchema)
            }
        }

        val result = buildJsonObject {
            put("tools", JsonArray(toolInfos))
        }

        val response = McpResponse(
            id = request.id,
            result = result,
        )

        return McpProtocol.serializeResponse(response)
    }

    private suspend fun handleToolCall(request: McpRequest): String {
        val params = request.params as? JsonObject

        val toolName = params?.get("name")?.toString()?.trim('"') ?: ""
        val arguments = params?.get("arguments") as? JsonObject ?: buildJsonObject {}

        if (toolName.isEmpty()) {
            return createErrorResponse(request.id, -32602, "Missing tool name")
        }

        val handler = tools[toolName] ?: run {
            return createErrorResponse(request.id, -32601, "Tool not found: $toolName")
        }

        try {
            DebugLog.i("McpServer: executing tool $toolName")

            val result = withTimeout(60_000L) {
                handler.execute(arguments)
            }

            val response = McpResponse(
                id = request.id,
                result = result,
            )

            DebugLog.i("McpServer: tool $toolName completed")

            return McpProtocol.serializeResponse(response)
        } catch (e: TimeoutCancellationException) {
            DebugLog.e("McpServer: tool $toolName timed out")
            return createErrorResponse(request.id, -32603, "Tool execution timeout: $toolName")
        } catch (e: Exception) {
            DebugLog.e("McpServer: tool $toolName error - ${e.message}")
            return createErrorResponse(request.id, -32603, "Tool execution error: ${e.message}")
        }
    }

    private fun createErrorResponse(id: Long?, code: Int, message: String): String {
        val error = McpError(code = code, message = message)
        val response = McpResponse(id = id, error = error)

        return McpProtocol.serializeResponse(response)
    }

    companion object {
        fun create(serverName: String = "Opedrgent-Server", serverVersion: String = "1.0.0"): McpServer {
            return McpServer(serverName, serverVersion)
        }
    }
}
