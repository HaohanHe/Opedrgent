package top.hsyscn.opedrgent.tools

import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.network.ToolResult
import top.hsyscn.opedrgent.settings.ApiConfig

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Tool(
    val name: String = "",
)

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class ToolDescription(
    val description: String,
)

data class ToolBinding(
    val name: String,
    val description: String,
    val invoker: suspend (ToolPart, ApiConfig, String, Boolean) -> ToolResult,
)

interface ToolSet {
    val toolSetName: String
        get() = this::class.simpleName ?: "unknown"

    fun getTools(): Map<String, ToolBinding>
}