package top.hsyscn.opedrgent.tools

import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.network.ToolResult
import top.hsyscn.opedrgent.settings.ApiConfig

class ToolRegistry {
    private val tools = mutableMapOf<String, ToolBinding>()

    fun register(toolSet: ToolSet) {
        toolSet.getTools().forEach { (name, binding) ->
            tools[name] = binding
        }
    }

    suspend fun invoke(toolName: String, tp: ToolPart, config: ApiConfig, systemPrompt: String, useProviderSearch: Boolean): ToolResult? {
        val binding = tools[toolName] ?: return null
        return binding.invoker(tp, config, systemPrompt, useProviderSearch)
    }

    fun getToolDescriptions(): Map<String, String> {
        return tools.mapValues { it.value.description }
    }
}