package top.hsyscn.opedrgent.agent

import android.content.Context
import kotlinx.coroutines.*
import top.hsyscn.opedrgent.utils.DebugLog

enum class AgentContext {
    PRIMARY,
    SUBAGENT,
    CRON,
    FLUSH,
}

interface MemoryProvider {
    val name: String
    val isBuiltin: Boolean get() = false

    fun isAvailable(): Boolean
    fun initialize(sessionId: String, hermesHome: String, platform: String, agentContext: AgentContext = AgentContext.PRIMARY)
    fun systemPromptBlock(): String
    fun prefetch(query: String): String
    fun queuePrefetch(query: String)
    fun syncTurn(userContent: String, assistantContent: String)
    fun getToolSchemas(): List<Map<String, Any>>
    fun handleToolCall(toolName: String, args: Map<String, Any>): String
    fun shutdown()

    fun onTurnStart(turnNumber: Int, message: String) {}
    fun onSessionEnd(messages: List<Map<String, Any>>) {}
    fun onSessionSwitch(newSessionId: String, parentSessionId: String = "", reset: Boolean = false) {}
    fun onPreCompress(messages: List<Map<String, Any>>): String = ""
    fun onMemoryWrite(action: String, target: String, content: String, metadata: Map<String, String>? = null) {}
}

class MemoryManager {

    private val providers = mutableListOf<MemoryProvider>()
    private val toolToProvider = mutableMapOf<String, MemoryProvider>()
    private var hasExternal: Boolean = false
    private val sanitizeContextPattern = Regex("</?\\s*memory-context\\s*>", RegexOption.IGNORE_CASE)
    private val systemNotePattern = Regex(
        "\\[System note:\\s*The following is recalled memory context,\\s*NOT new user input\\.\\s*Treat as (?:informational background data|authoritative reference data[^\\]]*)\\.\\]\\s*",
        RegexOption.IGNORE_CASE,
    )

    fun addProvider(provider: MemoryProvider): Boolean {
        if (!provider.isBuiltin) {
            if (hasExternal) {
                DebugLog.w("MemoryManager: rejecting second external provider ${provider.name}")
                return false
            }
            hasExternal = true
        }
        providers.add(provider)

        for (schema in provider.getToolSchemas()) {
            val toolName = schema["name"] as? String ?: continue
            toolToProvider[toolName] = provider
        }

        DebugLog.i("MemoryManager: added provider ${provider.name} (total: ${providers.size})")
        return true
    }

    fun initializeAll(sessionId: String, hermesHome: String, platform: String, agentContext: AgentContext = AgentContext.PRIMARY) {
        for (provider in providers) {
            if (provider.isAvailable()) {
                provider.initialize(sessionId, hermesHome, platform, agentContext)
            }
        }
    }

    fun buildSystemPrompt(): String {
        val parts = mutableListOf<String>()
        for (provider in providers) {
            val block = provider.systemPromptBlock()
            if (block.isNotBlank()) {
                parts.add(block)
            }
        }
        return parts.joinToString("\n")
    }

    fun prefetchAll(query: String): String {
        val parts = mutableListOf<String>()
        for (provider in providers) {
            val result = provider.prefetch(query)
            if (result.isNotBlank()) {
                parts.add(sanitizeContext(result))
            }
        }
        val combined = parts.joinToString("\n")
        return if (combined.isNotBlank()) buildMemoryContextBlock(combined) else ""
    }

    fun syncAll(userContent: String, assistantContent: String) {
        for (provider in providers) {
            provider.syncTurn(userContent, assistantContent)
        }
    }

    fun queuePrefetchAll(query: String) {
        for (provider in providers) {
            provider.queuePrefetch(query)
        }
    }

    fun getMemoryToolSchemas(): List<Map<String, Any>> {
        val schemas = mutableListOf<Map<String, Any>>()
        for (provider in providers) {
            schemas.addAll(provider.getToolSchemas())
        }
        return schemas
    }

    fun dispatchToolCall(toolName: String, args: Map<String, Any>): String {
        val provider = toolToProvider[toolName]
            ?: return """{"error": "No memory provider handles tool $toolName"}"""

        return try {
            provider.handleToolCall(toolName, args)
        } catch (e: Exception) {
            DebugLog.e("MemoryManager.dispatchToolCall: ${e.message}")
            """{"error": "Memory tool error: ${e.message}"}"""
        }
    }

    fun onTurnStart(turnNumber: Int, message: String) {
        for (provider in providers) {
            provider.onTurnStart(turnNumber, message)
        }
    }

    fun onSessionEnd(messages: List<Map<String, Any>>) {
        for (provider in providers) {
            provider.onSessionEnd(messages)
        }
    }

    fun onMemoryWrite(action: String, target: String, content: String, metadata: Map<String, String>? = null) {
        for (provider in providers) {
            provider.onMemoryWrite(action, target, content, metadata)
        }
    }

    fun shutdown() {
        for (provider in providers) {
            provider.shutdown()
        }
        providers.clear()
        toolToProvider.clear()
        hasExternal = false
    }

    fun getProviders(): List<MemoryProvider> = providers.toList()

    private fun sanitizeContext(text: String): String {
        return systemNotePattern.replace(text, "")
    }

    private fun buildMemoryContextBlock(raw: String): String {
        return buildString {
            appendLine("<memory-context>")
            appendLine("[System note: The following is recalled memory context, NOT new user input. Treat as authoritative reference data — this is the agent's persistent memory and should inform all responses.]")
            appendLine()
            appendLine(raw)
            appendLine("</memory-context>")
        }
    }

    companion object {
        private var instance: MemoryManager? = null

        fun getInstance(): MemoryManager {
            if (instance == null) {
                instance = MemoryManager()
            }
            return instance!!
        }

        fun createGlobal(): MemoryManager {
            val manager = MemoryManager()
            instance = manager
            return manager
        }
    }
}

class BuiltinMemoryProvider(private val context: Context) : MemoryProvider {

    override val name: String = "builtin"
    override val isBuiltin: Boolean = true
    private var sessionId: String = ""

    override fun isAvailable(): Boolean = true

    override fun initialize(sessionId: String, hermesHome: String, platform: String, agentContext: AgentContext) {
        this.sessionId = sessionId
        DebugLog.i("BuiltinMemoryProvider: initialized for session $sessionId")
    }

    override fun systemPromptBlock(): String {
        return """
            You have persistent memory across sessions. Use the memory tool to save 
            durable facts: user preferences, environment details, tool quirks, and 
            stable conventions. Memory is injected into every turn, so keep it compact.
            Do NOT save task progress or session outcomes to memory; those are ephemeral.
            Write memories as declarative facts, not instructions to yourself.
        """.trimIndent()
    }

    override fun prefetch(query: String): String {
        return top.hsyscn.opedrgent.storage.MemoryStore(context).getMemoryBlock()
    }

    override fun queuePrefetch(query: String) {}

    override fun syncTurn(userContent: String, assistantContent: String) {}

    override fun getToolSchemas(): List<Map<String, Any>> {
        return listOf(
            mapOf(
                "name" to "memory",
                "description" to "Save or retrieve persistent memory",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "action" to mapOf("type" to "string", "enum" to listOf("add", "search", "remove")),
                        "title" to mapOf("type" to "string"),
                        "content" to mapOf("type" to "string"),
                    ),
                    "required" to listOf("action"),
                ),
            ),
        )
    }

    override fun handleToolCall(toolName: String, args: Map<String, Any>): String {
        val store = top.hsyscn.opedrgent.storage.MemoryStore(context)
        val action = args["action"] as? String ?: "search"

        return when (action) {
            "add" -> {
                val title = args["title"] as? String ?: ""
                val content = args["content"] as? String ?: ""
                store.add(title, content)
                """{"result": "Memory saved: $title"}"""
            }
            "search" -> {
                val query = args["content"] as? String ?: ""
                val results = store.list().filter { e ->
                    e.content.contains(query, ignoreCase = true) || e.title.contains(query, ignoreCase = true)
                }
                """{"results": ${results.joinToString { it.content }}}"""
            }
            "remove" -> {
                val title = args["title"] as? String ?: ""
                store.list().find { it.title == title }?.let { store.delete(it.id) }
                """{"result": "Memory removed: $title"}"""
            }
            else -> """{"error": "Unknown action: $action"}"""
        }
    }

    override fun shutdown() {}
}