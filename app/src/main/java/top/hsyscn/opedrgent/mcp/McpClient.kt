package top.hsyscn.opedrgent.mcp

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import top.hsyscn.opedrgent.utils.DebugLog
import java.util.concurrent.ConcurrentHashMap

class McpAuthError(val serverName: String, message: String) : Exception(message)

class McpSessionExpiredError(serverName: String) :
    Exception("MCP server \"$serverName\" session expired")

class McpToolCallError(
    message: String,
    val mcpMeta: Map<String, String>? = null,
) : Exception(message)

class McpClient(
    private val transport: McpTransport,
    private val clientName: String = "Opedrgent",
    private val clientVersion: String = "1.0.0",
    private val toolTimeoutMs: Long = 100_000,
    private val maxDescriptionLength: Int = 2048,
) {

    private var isInitialized = false
    private val pendingRequests = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()
    private val toolsCache = mutableMapOf<String, ToolDefinition>()
    private val mutex = Mutex()
    private var receiveJob: Job? = null
    private var authProvider: ((McpClient) -> Map<String, String>)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun connect(): Boolean {
        return mutex.withLock {
            try {
                transport.connect()

                receiveJob = scope.launch {
                    startReceiveLoop()
                }

                val initResult = initialize()

                if (initResult != null) {
                    isInitialized = true
                    DebugLog.i("McpClient: connected and initialized — ${initResult.serverInfo.name} v${initResult.serverInfo.version}")
                    loadTools()
                    true
                } else {
                    DebugLog.e("McpClient: initialization failed")
                    false
                }
            } catch (e: McpAuthError) {
                DebugLog.e("McpClient: auth error — ${e.message}")
                false
            } catch (e: Exception) {
                DebugLog.e("McpClient.connect: ${e.message}")
                false
            }
        }
    }

    fun setAuthProvider(provider: (McpClient) -> Map<String, String>) {
        authProvider = provider
    }

    private suspend fun initialize(): InitializeResult? {
        val params = InitializeParams(
            protocolVersion = McpProtocol.PROTOCOL_VERSION,
            capabilities = ClientCapabilities(),
            clientInfo = ServerInfo(name = clientName, version = clientVersion),
        )

        val request = McpProtocol.createInitializeRequest(params)
        val response = sendRequestAndWait(request) ?: return null

        return try {
            McpProtocol.JSON.decodeFromString(
                InitializeResult.serializer(),
                response.toString(),
            )
        } catch (e: Exception) {
            DebugLog.e("McpClient.initialize: decode error - ${e.message}")
            null
        }
    }

    private suspend fun loadTools() {
        val request = McpProtocol.createToolsListRequest()
        val response = sendRequestAndWait(request) ?: return

        try {
            val jsonObj = response as? JsonObject ?: return
            val toolsArray = jsonObj["tools"] as? JsonArray ?: return

            synchronized(toolsCache) {
                toolsCache.clear()
                for (toolElem in toolsArray) {
                    val toolObj = toolElem as? JsonObject ?: continue
                    val rawName = toolObj["name"]?.toString()?.trim('"') ?: ""
                    val rawDesc = toolObj["description"]?.toString()?.trim('"') ?: ""
                    val truncatedDesc = if (rawDesc.length > maxDescriptionLength) {
                        rawDesc.take(maxDescriptionLength) + "..."
                    } else {
                        rawDesc
                    }
                    val tool = ToolDefinition(
                        name = rawName,
                        description = truncatedDesc,
                        inputSchema = toolObj["inputSchema"] as? JsonObject ?: buildJsonObject {},
                    )
                    if (tool.name.isNotEmpty()) {
                        toolsCache[tool.name] = tool
                    }
                }
            }

            DebugLog.i("McpClient: loaded ${toolsCache.size} tools")
        } catch (e: Exception) {
            DebugLog.e("McpClient.loadTools: ${e.message}")
        }
    }

    suspend fun listTools(): List<ToolDefinition> {
        ensureInitialized()
        return synchronized(toolsCache) { toolsCache.values.toList() }
    }

    suspend fun getTool(name: String): ToolDefinition? {
        ensureInitialized()
        return synchronized(toolsCache) { toolsCache[name] }
    }

    suspend fun callTool(
        name: String,
        arguments: JsonObject = buildJsonObject {},
        timeoutMs: Long = toolTimeoutMs,
    ): JsonElement? {
        ensureInitialized()

        val callRequest = ToolCallRequest(name = name, arguments = arguments)
        val request = McpProtocol.createToolCallRequest(callRequest)

        DebugLog.i("McpClient.callTool: → $name")

        return try {
            val response = sendRequestAndWait(request, timeoutMs)
            if (response == null) {
                DebugLog.e("McpClient.callTool: $name — no response")
                return null
            }

            val resultObj = response as? JsonObject
            val content = resultObj?.get("content") as? JsonArray
            val isError = resultObj?.get("isError")?.toString() == "true"

            if (isError) {
                DebugLog.w("McpClient.callTool: $name returned isError=true")
            }

            if (content != null) {
                DebugLog.i("McpClient.callTool: $name ← ${content.size} content items")
            }

            response
        } catch (e: McpSessionExpiredError) {
            DebugLog.w("McpClient.callTool: $name session expired, reconnecting...")
            reconnect()
            callTool(name, arguments, timeoutMs)
        } catch (e: McpAuthError) {
            DebugLog.e("McpClient.callTool: $name auth error — ${e.message}")
            throw e
        } catch (e: TimeoutCancellationException) {
            DebugLog.e("McpClient.callTool: $name timed out after ${timeoutMs}ms")
            null
        } catch (e: Exception) {
            DebugLog.e("McpClient.callTool: $name — ${e.message}")
            null
        }
    }

    private suspend fun reconnect(): Boolean {
        disconnect()
        delay(500)
        return connect()
    }

    private suspend fun sendRequestAndWait(
        request: McpRequest,
        timeoutMs: Long = 30_000L,
    ): JsonElement? {
        val deferred = CompletableDeferred<JsonElement>()

        request.id?.let { id ->
            pendingRequests[id] = deferred
        }

        return try {
            val serialized = McpProtocol.serializeRequest(request)
            transport.send(serialized)

            withTimeout(timeoutMs) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            DebugLog.w("McpClient: timeout for ${request.method}")
            pendingRequests.remove(request.id)
            null
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (isSessionExpiredError(msg)) {
                pendingRequests.remove(request.id)
                throw McpSessionExpiredError(clientName)
            }
            if (isAuthError(msg)) {
                pendingRequests.remove(request.id)
                throw McpAuthError(clientName, msg)
            }
            DebugLog.e("McpClient.sendRequestAndWait: $msg")
            pendingRequests.remove(request.id)
            null
        }
    }

    private fun isSessionExpiredError(errorMsg: String): Boolean {
        return errorMsg.contains("\"code\":-32001", ignoreCase = true) ||
            errorMsg.contains("\"code\": -32001", ignoreCase = true) ||
            errorMsg.contains("Session not found", ignoreCase = true)
    }

    private fun isAuthError(errorMsg: String): Boolean {
        return errorMsg.contains("401", ignoreCase = true) ||
            errorMsg.contains("Unauthorized", ignoreCase = true) ||
            errorMsg.contains("Authentication failed", ignoreCase = true)
    }

    private suspend fun startReceiveLoop() {
        while (transport.isConnected) {
            try {
                val raw = transport.receive() ?: continue

                val response = try {
                    McpProtocol.parseResponse(raw)
                } catch (e: Exception) {
                    DebugLog.w("McpClient.receive: invalid JSON — ${e.message}")
                    continue
                }

                response.id?.let { id ->
                    val deferred = pendingRequests.remove(id)
                    if (deferred != null) {
                        if (response.error != null) {
                            val errMsg = response.error.message
                            DebugLog.e("McpClient.receive: error ${response.error.code} — $errMsg")

                            if (response.error.code == -32001) {
                                deferred.completeExceptionally(McpSessionExpiredError(clientName))
                            } else {
                                deferred.completeExceptionally(McpToolCallError(errMsg))
                            }
                        } else if (response.result != null) {
                            deferred.complete(response.result)
                        }
                    }
                }
            } catch (e: Exception) {
                if (transport.isConnected) {
                    DebugLog.e("McpClient.receiveLoop: ${e.message}")
                    delay(100)
                }
            }
        }
    }

    private fun ensureInitialized() {
        if (!isInitialized) {
            throw IllegalStateException("MCP client not initialized — call connect() first")
        }
    }

    val connected: Boolean get() = isInitialized && transport.isConnected

    suspend fun disconnect() {
        mutex.withLock {
            receiveJob?.cancel()
            receiveJob = null

            transport.disconnect()

            pendingRequests.values.forEach { it.cancel() }
            pendingRequests.clear()

            synchronized(toolsCache) {
                toolsCache.clear()
            }

            isInitialized = false

            DebugLog.i("McpClient: disconnected")
        }
    }

    companion object {
        suspend fun createStdio(command: List<String>, name: String = "Opedrgent"): McpClient {
            val transport = StdioTransport(command)
            return McpClient(transport, name)
        }

        suspend fun createSse(url: String, headers: Map<String, String> = emptyMap(), name: String = "Opedrgent"): McpClient {
            val transport = SseTransport(url, headers)
            return McpClient(transport, name)
        }

        suspend fun createHttp(baseUrl: String, headers: Map<String, String> = emptyMap(), name: String = "Opedrgent"): McpClient {
            val transport = HttpTransport(baseUrl, headers)
            return McpClient(transport, name)
        }
    }
}