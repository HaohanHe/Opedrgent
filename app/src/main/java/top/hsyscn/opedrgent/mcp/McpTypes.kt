package top.hsyscn.opedrgent.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.atomic.AtomicLong

@Serializable
data class McpRequest(
    val jsonrpc: String = "2.0",
    val id: Long? = null,
    val method: String,
    val params: JsonElement? = null,
)

@Serializable
data class McpResponse(
    val jsonrpc: String = "2.0",
    val id: Long? = null,
    val result: JsonElement? = null,
    val error: McpError? = null,
)

@Serializable
data class McpError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)

@Serializable
data class McpNotification(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: JsonElement? = null,
)

@Serializable
data class ToolDefinition(
    val name: String,
    val description: String? = null,
    val inputSchema: JsonObject,
)

@Serializable
data class ToolInfo(
    val name: String,
    val description: String? = null,
    val inputSchema: JsonObject,
)

@Serializable
data class ToolCallRequest(
    val name: String,
    val arguments: JsonObject? = null,
)

@Serializable
data class TextContent(
    val type: String = "text",
    val text: String,
)

@Serializable
data class ImageContent(
    val type: String = "image",
    val data: String,
    val mimeType: String = "image/png",
)

@Serializable
data class McpToolResult(
    val content: List<JsonElement>,
    val isError: Boolean = false,
)

@Serializable
data class ServerInfo(
    val name: String,
    val version: String,
)

@Serializable
data class InitializeParams(
    val protocolVersion: String,
    val capabilities: ClientCapabilities,
    val clientInfo: ServerInfo,
)

@Serializable
data class ClientCapabilities(
    val sampling: SamplingCapability? = null,
    val roots: RootsCapability? = null,
)

@Serializable
data class SamplingCapability(
    val placeholder: Boolean = false,
)

@Serializable
data class RootsCapability(
    val listChanged: Boolean? = null,
)

@Serializable
data class InitializeResult(
    val protocolVersion: String,
    val capabilities: ServerCapabilities,
    val serverInfo: ServerInfo,
)

@Serializable
data class ServerCapabilities(
    val tools: ToolsCapability? = null,
    val logging: LoggingCapability? = null,
    val prompts: PromptsCapability? = null,
    val resources: ResourcesCapability? = null,
)

@Serializable
data class ToolsCapability(
    val listChanged: Boolean? = null,
)

@Serializable
data class LoggingCapability(
    val placeholder: Boolean = false,
)

@Serializable
data class PromptsCapability(
    val listChanged: Boolean? = null,
)

@Serializable
data class ResourcesCapability(
    val subscribe: Boolean? = null,
    val listChanged: Boolean? = null,
)

object McpProtocol {
    const val PROTOCOL_VERSION = "2024-11-05"

    private val idCounter = AtomicLong(0)

    fun nextId(): Long = idCounter.incrementAndGet()

    val JSON = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    fun parseRequest(json: String): McpRequest {
        return JSON.decodeFromString(McpRequest.serializer(), json)
    }

    fun parseResponse(json: String): McpResponse {
        return JSON.decodeFromString(McpResponse.serializer(), json)
    }

    fun serializeRequest(request: McpRequest): String {
        return JSON.encodeToString(McpRequest.serializer(), request)
    }

    fun serializeResponse(response: McpResponse): String {
        return JSON.encodeToString(McpResponse.serializer(), response)
    }

    fun createInitializeRequest(params: InitializeParams): McpRequest {
        return McpRequest(
            id = nextId(),
            method = "initialize",
            params = JSON.encodeToJsonElement(InitializeParams.serializer(), params),
        )
    }

    fun createInitializedNotification(): McpNotification {
        return McpNotification(method = "notifications/initialized")
    }

    fun createToolsListRequest(): McpRequest {
        return McpRequest(id = nextId(), method = "tools/list")
    }

    fun createToolCallRequest(call: ToolCallRequest): McpRequest {
        return McpRequest(
            id = nextId(),
            method = "tools/call",
            params = JSON.encodeToJsonElement(ToolCallRequest.serializer(), call),
        )
    }
}
