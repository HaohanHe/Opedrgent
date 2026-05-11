package top.hsyscn.opedrgent.agent

data class AgentDefinition(
    val name: String,
    val description: String = "",
    val systemPrompt: String = "",
    val requiredTools: List<String> = emptyList(),
    val memory: String? = null,
    val appendToDefault: Boolean = true,
)

enum class SystemPromptPriority {
    OVERRIDE,
    AGENT,
    CUSTOM,
    DEFAULT,
}

class SystemPromptBuilder {

    private var overridePrompt: String? = null
    private var agentDefinition: AgentDefinition? = null
    private var customPrompt: String? = null
    private var defaultPrompts: List<String> = emptyList()
    private var appendPrompt: String? = null
    private var memoryBlock: String = ""
    private var skillsBlock: String = ""
    private var toolsBlock: String = ""
    private var envBlock: String = ""

    fun setOverride(prompt: String): SystemPromptBuilder = apply { overridePrompt = prompt }
    fun setAgent(def: AgentDefinition?): SystemPromptBuilder = apply { agentDefinition = def }
    fun setCustom(prompt: String?): SystemPromptBuilder = apply { customPrompt = prompt }
    fun setDefaults(prompts: List<String>): SystemPromptBuilder = apply { defaultPrompts = prompts }
    fun setAppend(prompt: String?): SystemPromptBuilder = apply { appendPrompt = prompt }
    fun setMemory(block: String): SystemPromptBuilder = apply { memoryBlock = block }
    fun setSkills(block: String): SystemPromptBuilder = apply { skillsBlock = block }
    fun setTools(block: String): SystemPromptBuilder = apply { toolsBlock = block }
    fun setEnvironment(block: String): SystemPromptBuilder = apply { envBlock = block }

    fun build(): List<String> {
        val parts = mutableListOf<String>()

        if (overridePrompt != null) {
            parts.add(overridePrompt!!)
            return parts
        }

        val agentPrompt = agentDefinition?.systemPrompt

        if (agentPrompt != null && !agentDefinition!!.appendToDefault) {
            parts.add(agentPrompt)
        } else if (agentPrompt != null) {
            parts.addAll(defaultPrompts)
            parts.add("\n# Agent Instructions\n$agentPrompt")
        } else if (customPrompt != null) {
            parts.add(customPrompt!!)
        } else {
            parts.addAll(defaultPrompts)
        }

        if (memoryBlock.isNotBlank()) {
            parts.add("\n# Persistent Memory\n$memoryBlock")
        }

        if (skillsBlock.isNotBlank()) {
            parts.add("\n# Available Skills\n$skillsBlock")
        }

        if (toolsBlock.isNotBlank()) {
            parts.add("\n# Tool Usage\n$toolsBlock")
        }

        if (envBlock.isNotBlank()) {
            parts.add("\n# Environment\n$envBlock")
        }

        if (appendPrompt != null) {
            parts.add(appendPrompt!!)
        }

        return parts
    }

    fun buildString(): String = build().joinToString("\n")

    companion object {
        fun create(): SystemPromptBuilder = SystemPromptBuilder()

        fun defaultSystemPrompts(): List<String> = listOf(
            "You are Opedrgent, an intelligent AI research assistant for Android. " +
            "You help users with research, coding, analysis, and task automation.",
            "Be thorough and helpful. Think through problems step by step. " +
            "Use tools when needed to gather information or execute actions.",
            "When uncertain, ask clarifying questions. " +
            "Always cite sources when providing factual information.",
        )

        fun memoryGuidance(): String = """
            You have persistent memory across sessions. Save durable facts using the memory 
            tool: user preferences, environment details, tool quirks, and stable conventions. 
            Memory is injected into every turn, so keep it compact and focused on facts that 
            will still matter later.
            Prioritize what reduces future user steering — the most valuable memory is one 
            that prevents the user from having to correct or remind you again. 
            Do NOT save task progress, session outcomes, completed-work logs, or temporary 
            state to memory.
            Write memories as declarative facts, not instructions to yourself.
        """.trimIndent()

        fun skillsGuidance(): String = """
            After completing a complex task (5+ tool calls), fixing a tricky error, 
            or discovering a non-trivial workflow, save the approach as a 
            skill so you can reuse it next time.
            When using a skill and finding it outdated, incomplete, or wrong, 
            patch it immediately — don't wait to be asked. 
            Skills that aren't maintained become liabilities.
        """.trimIndent()

        fun toolsGuidance(toolNames: List<String>): String = buildString {
            appendLine("You have access to the following tools:")
            for (name in toolNames) {
                appendLine("  - $name")
            }
            appendLine()
            appendLine("Use them automatically when they help complete the user's request. " +
                "Always verify tool results before acting on them.")
        }
    }
}