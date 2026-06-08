package top.hsyscn.opedrgent.tools

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.reflect.KFunction
import kotlin.reflect.full.callSuspendBy
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.isSubtypeOf
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.createType
import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.network.ToolResult
import top.hsyscn.opedrgent.settings.ApiConfig

class ToolRegistry {
    private val tools = mutableMapOf<String, ToolBinding>()

    /**
     * 注册工具集：同时支持手动 getTools() 和反射自动发现 @Tool 注解方法。
     * 如果 toolSet 实现了 ToolSet 接口且 getTools() 返回非空，优先使用手动注册；
     * 否则通过反射扫描 @Tool 注解的 suspend 函数自动生成 ToolBinding。
     */
    fun register(toolSet: Any) {
        if (toolSet is ToolSet) {
            val manualTools = toolSet.getTools()
            if (manualTools.isNotEmpty()) {
                manualTools.forEach { (name, binding) ->
                    tools[name] = binding
                }
                return
            }
        }

        // 反射自动发现
        val reflectedTools = reflectAsTools(toolSet)
        reflectedTools.forEach { binding ->
            tools[binding.name] = binding
        }
    }

    /**
     * 通过 Kotlin 反射自动扫描 toolSet 对象上所有带 @Tool 注解的 suspend 函数，
     * 生成 ToolBinding 列表。每个 ToolSet 类无需手动实现 getTools()。
     *
     * 扫描规则：
     * - 仅处理标记了 @Tool 注解的成员函数
     * - 函数必须是 suspend 函数
     * - 工具名取自 @Tool.name，若为空则使用函数名
     * - 描述取自 @ToolDescription.description，若无注解则为空
     * - 将 suspend 函数包装为 ToolBinding.invoker lambda
     *
     * @param toolSet 待扫描的工具集对象（可以是任意对象，不限于 ToolSet 接口）
     * @return 自动发现的 ToolBinding 列表
     */
    fun reflectAsTools(toolSet: Any): List<ToolBinding> {
        val kClass = toolSet::class
        val bindings = mutableListOf<ToolBinding>()

        // 获取所有成员函数
        val memberFunctions = kClass.memberFunctions

        for (func in memberFunctions) {
            // 检查是否有 @Tool 注解
            val toolAnnotation = func.findAnnotation<Tool>() ?: continue

            // 检查是否为 suspend 函数
            if (!func.isSuspend) continue

            // 确定工具名称：@Tool.name 非空时使用注解值，否则使用函数名
            val toolName = toolAnnotation.name.ifBlank { func.name }

            // 获取描述：从 @ToolDescription 注解中提取
            val descriptionAnnotation = func.findAnnotation<ToolDescription>()
            val description = descriptionAnnotation?.description ?: ""

            // 将 suspend 成员函数包装为 invoker lambda
            // 使用反射调用：将参数映射到函数签名
            val invoker = buildInvoker(toolSet, func)

            bindings.add(
                ToolBinding(
                    name = toolName,
                    description = description,
                    invoker = invoker
                )
            )
        }

        return bindings
    }

    /**
     * 将一个 suspend KFunction 包装为 ToolBinding.invoker 类型的 lambda。
     * 使用 callSuspendBy 进行反射调用，支持任意参数签名的适配。
     */
    private fun buildInvoker(target: Any, func: KFunction<*>): suspend (ToolPart, ApiConfig, String, Boolean) -> ToolResult {
        return { tp: ToolPart, config: ApiConfig, systemPrompt: String, useProviderSearch: Boolean ->
            try {
                // 构建参数映射：根据函数参数类型匹配传入的标准参数
                val params = mutableMapOf<kotlin.reflect.KParameter, Any?>()

                for (param in func.parameters) {
                    when {
                        param.kind == kotlin.reflect.KParameter.Kind.INSTANCE -> params[param] = target
                        param.type.isSubtypeOf(ToolPart::class.createType(nullable = false)) -> params[param] = tp
                        param.type.isSubtypeOf(ApiConfig::class.createType(nullable = false)) -> params[param] = config
                        param.type.classifier == String::class && param.index != null && param.index!! > 0 -> params[param] = systemPrompt
                        param.type.classifier == Boolean::class -> params[param] = useProviderSearch
                        else -> params[param] = null
                    }
                }

                val result = func.callSuspendBy(params)
                if (result is ToolResult) result
                else ToolResult(toolPart = top.hsyscn.opedrgent.model.ToolPart(tool = "error", state = top.hsyscn.opedrgent.model.ToolState(status = top.hsyscn.opedrgent.model.ToolStateType.COMPLETED)), openUrl = null)
            } catch (e: Exception) {
                ToolResult(toolPart = top.hsyscn.opedrgent.model.ToolPart(tool = "error", state = top.hsyscn.opedrgent.model.ToolState(status = top.hsyscn.opedrgent.model.ToolStateType.ERROR)), openUrl = null)
            } catch (e: Throwable) {
                throw e
            }
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