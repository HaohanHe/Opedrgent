package top.hsyscn.opedrgent.tools

import org.json.JSONObject
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

/**
 * 工具参数的 JSON Schema 定义注解。
 * 用于声明工具接受的参数结构，遵循 OpenAI function calling 的 parameters 规范。
 * 如果未提供，ToolRegistry 将尝试从函数签名推断。
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ToolParameters(
    /** JSON Schema 字符串，格式为 {"type":"object","properties":{...},"required":[...]} */
    val schema: String = "",
)

data class ToolBinding(
    val name: String,
    val description: String,
    val parameters: JSONObject? = null,
    val invoker: suspend (ToolPart, ApiConfig, String, Boolean) -> ToolResult,
)

interface ToolSet {
    val toolSetName: String
        get() = this::class.simpleName ?: "unknown"

    fun getTools(): Map<String, ToolBinding>
}