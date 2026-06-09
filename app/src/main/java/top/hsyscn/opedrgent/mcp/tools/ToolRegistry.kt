package top.hsyscn.opedrgent.mcp.tools

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.hsyscn.opedrgent.utils.DebugLog
import java.util.concurrent.ConcurrentHashMap

/**
 * 类型安全工具描述符（对标 Koog ToolDescriptor）。
 *
 * 定义工具的元信息，不包含执行逻辑。
 * 用于工具发现、权限检查、UI 展示等场景。
 */
data class ToolDescriptor(
    val name: String,                          // 唯一标识（如 "search_web"）
    val displayName: String,                    // 展示名称（如 "网络搜索"）
    val description: String,                    // 功能描述（传给 LLM 的工具说明）
    val category: ToolCategory = ToolCategory.GENERAL,
    val argsSchema: List<ToolArg> = emptyList(), // 参数定义
    val isDangerous: Boolean = false,           // 是否为危险操作
    val requiresPermission: Boolean = false,    // 是否需要用户授权
    val version: String = "1.0",
)

/**
 * 工具参数定义。
 */
data class ToolArg(
    val name: String,
    val type: ArgType = ArgType.STRING,
    val required: Boolean = false,
    val description: String = "",
    val defaultValue: String? = null,
)

/** 参数类型 */
enum class ArgType { STRING, NUMBER, BOOLEAN, OBJECT, ARRAY, FILE }

/** 工具分类 */
enum class ToolCategory(val displayName: String) {
    GENERAL("通用"),
    SEARCH("搜索"),
    FILE("文件操作"),
    NOTE("笔记"),
    COMMUNICATION("通讯"),
    SYSTEM("系统"),
    AI("AI能力"),
    SKILL("技能"),
}

/**
 * 工具执行结果（对标 Koog SafeTool.Result）。
 *
 * 统一的成功/失败包装，避免异常泄漏到上层。
 */
sealed class ToolResult<out T> {
    data class Success<T>(val data: T) : ToolResult<T>()
    data class Error(val message: String, val code: String? = null) : ToolResult<Nothing>()

    val isSuccess: Boolean get() = this is Success

    inline fun <R> map(transform: (T) -> R): ToolResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Error -> this
    }

    inline fun onSuccess(action: (T) -> Unit): ToolResult<T> {
        if (this is Success) action(data)
        return this
    }

    inline fun onError(action: (String, String?) -> Unit): ToolResult<T> {
        if (this is Error) action(message, code)
        return this
    }
}

/**
 * 工具接口定义（对标 Koog Tool<TArgs, TResult>）。
 *
 * 泛型参数提供编译时类型安全：
 * - TArgs: 输入参数类型（从 JSON 反序列化）
 * - TResult: 返回结果类型
 *
 * 使用示例：
 * ```kotlin
 * object SearchTool : Tool<SearchArgs, SearchResults>("search_web") {
 *     override suspend fun execute(args: SearchArgs): ToolResult<SearchResults> { ... }
 * }
 * ```
 */
abstract class Tool<TArgs, TResult>(
    val descriptor: ToolDescriptor,
) {
    /** 工具名称（取自 descriptor） */
    val name: String get() = descriptor.name

    /**
     * 执行工具。
     *
     * @param args 类型安全的参数
     * @return 包装在 ToolResult 中的结果
     */
    abstract suspend fun execute(args: TArgs): ToolResult<TResult>

    /**
     * 从 JSON Map 构建参数对象（子类可实现自定义反序列化）。
     */
    open fun parseArgs(jsonMap: Map<String, Any?>): TArgs {
        throw NotImplementedError("Tool $name must implement parseArgs() or use a concrete Tool implementation")
    }

    /**
     * 快速创建成功结果。
     */
    protected fun success(data: T): ToolResult<TResult> = ToolResult.Success(data)

    /**
     * 快速创建错误结果。
     */
    protected fun error(message: String, code: String? = null): ToolResult<TResult> = ToolResult.Error(message, code)
}

/**
 * 基于 Lambda 的简易工具（无需定义子类）。
 *
 * 适用于简单工具的快速注册：
 * ```kotlin
 * ToolRegistry {
 *     tool("greet", "问候", "向用户问好") { name: String ->
 *         success("你好, $name!")
 *     }
 * }
 * ```
 */
class LambdaTool<TResult : Any>(
    descriptor: ToolDescriptor,
    private val executor: suspend (Map<String, Any?>) -> ToolResult<TResult>,
) : Tool<Map<String, Any?>, TResult>(descriptor) {
    override suspend fun execute(args: Map<String, Any?>): ToolResult<TResult> = executor(args)
    override fun parseArgs(jsonMap: Map<String, Any?>): Map<String, Any?> = jsonMap
}

/**
 * 工具注册表（对标 Koog ToolRegistry）。
 *
 * 线程安全的工具注册中心，支持：
 * - 按名称查找工具
 * - 按分类浏览工具
 * - 类型安全的工具调用
 * - 工具启用/禁用
 */
class ToolRegistry private constructor(
    private val tools: MutableMap<String, Tool<*, *>> = ConcurrentHashMap(),
    private val enabledSet: MutableSet<String> = ConcurrentHashMap.newKeySet(),
) {
    private val mutex = Mutex()

    companion object {
        /** 创建空的 Registry */
        fun create(): ToolRegistry = ToolRegistry()

        /**
         * Builder 风格创建（对标 Koog ToolRegistryBuilder）。
         *
         * 用法：
         * ```kotlin
         * val registry = ToolRegistry {
         *     register(mySearchTool)
         *     register(myWriteTool)
         *     disable("dangerous_tool")
         * }
         * ```
         */
        operator fun invoke(block: ToolRegistryBuilder.() -> Unit): ToolRegistry {
            val registry = ToolRegistry()
            val builder = ToolRegistryBuilder(registry)
            builder.block()
            return registry
        }
    }

    // ==================== 注册 API ====================

    /**
     * 注册一个工具。
     */
    suspend fun <TArgs, TResult> register(tool: Tool<TArgs, TResult>): Unit = mutex.withLock {
        val existing = tools.put(tool.name, tool)
        if (existing == null) {
            enabledSet.add(tool.name)
            DebugLog.i("ToolRegistry: registered [${tool.name}] ${tool.descriptor.displayName}")
        } else {
            DebugLog.w("ToolRegistry: replaced [${tool.name}]")
        }
    }

    /**
     * 注销一个工具。
     */
    suspend fun unregister(name: String): Boolean = mutex.withLock {
        tools.remove(name)?.also {
            enabledSet.remove(name)
            DebugLog.i("ToolRegistry: unregistered [$name]")
        } != null
    }

    /**
     * 启用一个工具。
     */
    fun enable(name: String) { enabledSet.add(name) }

    /**
     * 禁用一个工具。
     */
    fun disable(name: String) { enabledSet.remove(name) }

    // ==================== 查询 API ====================

    /** 获取所有已注册工具的描述符 */
    suspend fun getAllDescriptors(): List<ToolDescriptor> = mutex.withLock {
        tools.values.map { it.descriptor }
    }

    /** 按分类获取工具描述符 */
    suspend fun getByCategory(category: ToolCategory): List<ToolDescriptor> = mutex.withLock {
        tools.values.filter { it.descriptor.category == category }.map { it.descriptor }
    }

    /** 获取启用的工具描述符（用于 LLM 工具选择） */
    suspend fun getEnabledDescriptors(): List<ToolDescriptor> = mutex.withLock {
        tools.values.filter { it.name in enabledSet }.map { it.descriptor }
    }

    /** 检查工具是否存在 */
    fun hasTool(name: String): Boolean = tools.containsKey(name)

    /** 检查工具是否启用 */
    fun isEnabled(name: String): Boolean = name in enabledSet

    /** 获取工具总数 */
    fun size(): Int = tools.size

    /** 获取所有工具名 */
    fun toolNames(): Set<String> = tools.keys.toSet()

    // ==================== 执行 API ====================

    /**
     * 类型安全地执行工具。
     *
     * @param name 工具名
     * @param args 参数 JSON Map
     * @return 执行结果
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun execute(name: String, args: Map<String, Any?> = emptyMap()): ToolResult<*> = mutex.withLock {
        val tool = tools[name]
        if (tool == null) {
            return@withLock ToolResult.Error("工具不存在: $name", "TOOL_NOT_FOUND")
        }
        if (name !in enabledSet) {
            return@withLock ToolResult.Error("工具已禁用: $name", "TOOL_DISABLED")
        }

        try {
            val parsedArgs = tool.parseArgs(args)
            DebugLog.d("ToolRegistry: executing [$name] with ${args.keys}")
            tool.execute(parsedArgs)
        } catch (e: Exception) {
            DebugLog.e("ToolRegistry: error executing [$name]: ${e.message}", e)
            ToolResult.Error("工具执行失败: ${e.message}", "EXECUTION_ERROR")
        }
    }

    /**
     * 仅获取工具（不执行），用于特殊调用场景。
     */
    fun <TArgs, TResult> getTool(name: String): Tool<TArgs, TResult>? {
        @Suppress("UNCHECKED_CAST")
        return tools[name] as? Tool<TArgs, TResult>
    }

    // ==================== 合并 ====================

    /**
     * 合并另一个 Registry 的工具（对标 Koog 的 + 操作符）。
     * 冲突时，本 Registry 的工具优先。
     */
    suspend fun merge(other: ToolRegistry) = mutex.withLock {
        for ((name, tool) in other.tools) {
            if (name !in this.tools) {
                this.tools[name] = tool
                if (name in other.enabledSet) enabledSet.add(name)
            }
        }
    }

    /**
     * 统计信息。
     */
    suspend fun stats(): RegistryStats = mutex.withLock {
        RegistryStats(
            total = tools.size,
            enabled = enabledSet.size,
            disabled = tools.size - enabledSet.size,
            byCategory = tools.values.groupingBy { it.descriptor.category }.eachCount(),
            dangerous = tools.values.count { it.descriptor.isDangerous },
        )
    }
}

/**
 * Registry Builder（对标 Koog ToolRegistryBuilder）。
 */
class ToolRegistryBuilder(private val registry: ToolRegistry) {

    suspend fun <TArgs, TResult> register(tool: Tool<TArgs, TResult>) { registry.register(tool) }

    fun enable(name: String) { registry.enable(name) }
    fun disable(name: String) { registry.disable(name) }

    /** 创建 Lambda 工具并注册 */
    suspend fun <T : Any> tool(
        name: String,
        displayName: String,
        description: String,
        category: ToolCategory = ToolCategory.GENERAL,
        args: List<ToolArg> = emptyList(),
        executor: suspend (Map<String, Any?>) -> ToolResult<T>,
    ) {
        val tool = LambdaTool(
            descriptor = ToolDescriptor(
                name = name,
                displayName = displayName,
                description = description,
                category = category,
                argsSchema = args,
            ),
            executor = executor,
        )
        registry.register(tool)
    }
}

/** Registry 统计信息 */
data class RegistryStats(
    val total: Int,
    val enabled: Int,
    val disabled: Int,
    val byCategory: Map<ToolCategory, Int>,
    val dangerous: Int,
) {
    fun toDisplayText(): String = """ToolRegistry:
  | Total: $total ($enabled enabled, $disabled disabled)
  | Dangerous: $dangerous
  | By category: ${byCategory.entries.joinToString { "${it.key.displayName}=${it.value}" }}""".trimMargin()
}
