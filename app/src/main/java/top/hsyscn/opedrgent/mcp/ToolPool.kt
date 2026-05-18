package top.hsyscn.opedrgent.mcp

import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.hsyscn.opedrgent.utils.DebugLog
import java.util.concurrent.ConcurrentHashMap

data class ToolResult(
    val output: String,
    val success: Boolean = true,
    val error: String? = null,
)

interface ToolProvider {
    val name: String
    val description: String
    suspend fun listTools(): List<PoolTool>
    suspend fun executeTool(name: String, arguments: JsonObject): ToolResult
}

data class PoolTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val provider: String,
    val category: ToolCategory = ToolCategory.GENERAL,
)

enum class ToolCategory {
    SEARCH,
    WEB_AUTOMATION,
    FILE_SYSTEM,
    CODE_EXECUTION,
    COMMUNICATION,
    GENERAL,
    AI_ASSISTANT,
}

class ToolPool {

    private val providers = ConcurrentHashMap<String, ToolProvider>()
    private val tools = ConcurrentHashMap<String, PoolTool>()
    private val categories = ConcurrentHashMap<ToolCategory, MutableList<PoolTool>>()

    fun registerProvider(provider: ToolProvider) {
        providers[provider.name] = provider
        DebugLog.i("ToolPool: registered provider ${provider.name}")

        CoroutineScope(Dispatchers.Default).launch {
            try {
                val providerTools = provider.listTools()
                for (tool in providerTools) {
                    registerTool(tool)
                }
            } catch (e: Exception) {
                DebugLog.e("ToolPool: failed to load tools from ${provider.name} - ${e.message}")
            }
        }
    }

    fun unregisterProvider(providerName: String) {
        providers.remove(providerName)

        val toolsToRemove = tools.values.filter { it.provider == providerName }
        for (tool in toolsToRemove) {
            unregisterTool(tool.name)
        }

        DebugLog.i("ToolPool: unregistered provider $providerName")
    }

    private fun registerTool(tool: PoolTool) {
        tools[tool.name] = tool

        val categoryList = categories.getOrPut(tool.category) { mutableListOf() }
        synchronized(categoryList) {
            if (!categoryList.any { it.name == tool.name }) {
                categoryList.add(tool)
            }
        }

        DebugLog.i("ToolPool: registered tool ${tool.name} from ${tool.provider}")
    }

    private fun unregisterTool(name: String) {
        val tool = tools.remove(name) ?: return

        val categoryList = categories[tool.category]
        if (categoryList != null) {
            synchronized(categoryList) {
                categoryList.removeAll { it.name == name }
            }
        }

        DebugLog.i("ToolPool: unregistered tool $name")
    }

    fun getTool(name: String): PoolTool? {
        return tools[name]
    }

    fun listAllTools(): List<PoolTool> {
        return tools.values.toList()
    }

    fun listByCategory(category: ToolCategory): List<PoolTool> {
        return categories[category]?.toList() ?: emptyList()
    }

    fun searchTools(query: String): List<PoolTool> {
        val lowerQuery = query.lowercase()

        return tools.values.filter { tool ->
            tool.name.lowercase().contains(lowerQuery) ||
            tool.description.lowercase().contains(lowerQuery)
        }
    }

    suspend fun execute(name: String, arguments: JsonObject = buildJsonObject {}): ToolResult {
        val tool = tools[name] ?: run {
            DebugLog.e("ToolPool.execute: tool not found - $name")
            return ToolResult(
                output = "Tool not found: $name",
                success = false,
            )
        }

        val provider = providers[tool.provider] ?: run {
            DebugLog.e("ToolPool.execute: provider not found - ${tool.provider}")
            return ToolResult(
                output = "Provider not found: ${tool.provider}",
                success = false,
            )
        }

        DebugLog.i("ToolPool.execute: executing $name via ${tool.provider}")

        return try {
            withTimeout(60_000L) {
                provider.executeTool(name, arguments)
            }
        } catch (e: TimeoutCancellationException) {
            DebugLog.e("ToolPool.execute: timeout for $name")
            ToolResult(
                output = "Execution timeout: $name",
                success = false,
            )
        } catch (e: Exception) {
            DebugLog.e("ToolPool.execute: error for $name - ${e.message}")
            ToolResult(
                output = "Execution error: ${e.message}",
                success = false,
            )
        }
    }

    fun listProviders(): List<ToolProvider> {
        return providers.values.toList()
    }

    fun listCategories(): Set<ToolCategory> {
        return categories.keys.toSet()
    }

    fun getToolCount(): Int {
        return tools.size
    }

    fun getProviderCount(): Int {
        return providers.size
    }

    companion object {
        private var instance: ToolPool? = null

        fun getInstance(): ToolPool {
            if (instance == null) {
                instance = ToolPool()
            }
            return instance!!
        }

        fun createGlobal(): ToolPool {
            val pool = ToolPool()
            instance = pool
            return pool
        }
    }
}

class BuiltInToolsProvider : ToolProvider {

    override val name: String = "builtin"
    override val description: String = "Built-in Opedrgent tools"

    override suspend fun listTools(): List<PoolTool> {
        return listOf(
            PoolTool(
                name = "web_search",
                description = "Search the web using DuckDuckGo or WebView",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("query", buildJsonObject {
                            put("type", "string")
                            put("description", "Search query")
                        })
                        put("method", buildJsonObject {
                            put("type", "string")
                            put("enum", JsonArray(listOf(JsonPrimitive("ddg"), JsonPrimitive("webview"))))
                        })
                    })
                    put("required", JsonArray(listOf(JsonPrimitive("query"))))
                },
                provider = name,
                category = ToolCategory.SEARCH,
            ),
            PoolTool(
                name = "fetch_url",
                description = "Fetch and extract content from a URL",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("url", buildJsonObject {
                            put("type", "string")
                            put("description", "URL to fetch")
                        })
                    })
                    put("required", JsonArray(listOf(JsonPrimitive("url"))))
                },
                provider = name,
                category = ToolCategory.WEB_AUTOMATION,
            ),
            PoolTool(
                name = "read_file",
                description = "Read file contents",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "File path")
                        })
                    })
                    put("required", JsonArray(listOf(JsonPrimitive("path"))))
                },
                provider = name,
                category = ToolCategory.FILE_SYSTEM,
            ),
            PoolTool(
                name = "write_file",
                description = "Write content to a file",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "File path")
                        })
                        put("content", buildJsonObject {
                            put("type", "string")
                            put("description", "Content to write")
                        })
                    })
                    put("required", JsonArray(listOf(JsonPrimitive("path"), JsonPrimitive("content"))))
                },
                provider = name,
                category = ToolCategory.FILE_SYSTEM,
            ),
            PoolTool(
                name = "shell_command",
                description = "Execute shell command",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("command", buildJsonObject {
                            put("type", "string")
                            put("description", "Shell command to execute")
                        })
                    })
                    put("required", JsonArray(listOf(JsonPrimitive("command"))))
                },
                provider = name,
                category = ToolCategory.CODE_EXECUTION,
            ),
        )
    }

    override suspend fun executeTool(name: String, arguments: JsonObject): ToolResult {
    throw UnsupportedOperationException(
        "BuiltinToolProvider.executeTool: Builtin tool execution not yet implemented via ToolExecutor"
    )
}
}

class McpToolsProvider(
    private val mcpClient: McpClient,
) : ToolProvider {

    override val name: String = "mcp-${System.currentTimeMillis()}"
    override val description: String = "MCP server tools"

    private var cachedTools: List<PoolTool>? = null

    override suspend fun listTools(): List<PoolTool> {
        cachedTools?.let { return it }

        val mcpTools = mcpClient.listTools()

        val poolTools = mcpTools.map { mcpTool ->
            PoolTool(
                name = mcpTool.name,
                description = mcpTool.description ?: "",
                inputSchema = mcpTool.inputSchema,
                provider = name,
                category = categorizeMcpTool(mcpTool.name, mcpTool.description),
            )
        }

        cachedTools = poolTools
        return poolTools
    }

    override suspend fun executeTool(name: String, arguments: JsonObject): ToolResult {
        val result = mcpClient.callTool(name, arguments)

        return if (result != null) {
            ToolResult(
                output = result.toString(),
                success = true,
            )
        } else {
            ToolResult(
                output = "MCP tool execution failed: $name",
                success = false,
            )
        }
    }

    private fun categorizeMcpTool(name: String, description: String?): ToolCategory {
        val lowerName = name.lowercase()
        val lowerDesc = (description ?: "").lowercase()

        return when {
            lowerName.contains("search") || lowerDesc.contains("search") -> ToolCategory.SEARCH
            lowerName.contains("browser") || lowerName.contains("click") ||
            lowerName.contains("navigate") || lowerDesc.contains("browser") -> ToolCategory.WEB_AUTOMATION
            lowerName.contains("file") || lowerName.contains("read") ||
            lowerName.contains("write") || lowerDesc.contains("file") -> ToolCategory.FILE_SYSTEM
            lowerName.contains("exec") || lowerName.contains("run") ||
            lowerName.contains("shell") || lowerDesc.contains("execut") -> ToolCategory.CODE_EXECUTION
            else -> ToolCategory.GENERAL
        }
    }
}
