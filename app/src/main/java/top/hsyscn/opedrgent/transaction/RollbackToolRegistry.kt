package top.hsyscn.opedrgent.transaction

import java.util.concurrent.ConcurrentHashMap
import top.hsyscn.opedrgent.utils.DebugLog

/**
 * 工具 -> 补偿工具 映射（Saga 补偿模式）。
 *
 * @param toolName 原工具名
 * @param rollbackToolName 补偿工具名（可与原工具同名，如 run_calendar 既 create 又 delete）
 * @param inputExtractor 补偿入参提取器。接收原调用的 (input, output)，
 *   返回补偿工具所需的入参；返回 null 表示该调用不可补偿（跳过）。
 *   <p>注意：签名扩展为同时接收 output，因为 create 的副作用产物 ID（如 event_id）
 *   只能从 output 中解析，仅靠 input 无法构造 delete 入参。
 */
data class RollbackMapping(
    val toolName: String,
    val rollbackToolName: String,
    val inputExtractor: (input: Map<String, Any>, output: String?) -> Map<String, Any>?,
)

/**
 * 补偿映射注册表（线程安全单例）。
 *
 * 内置 [registerDefaults] 提供常见副作用工具的补偿映射：
 * - run_calendar(create) -> run_calendar(delete)：从 output 解析 event_id
 * - run_intent -> 不可补偿（提取器返回 null），仅作显式标注
 *
 * 无副作用的只读工具（web_search / read_url / generate_report 等）不注册，
 * [lookup] 返回 null 即视为无需补偿。
 */
object RollbackToolRegistry {

    private const val TAG = "RollbackToolRegistry"

    private val mappings = ConcurrentHashMap<String, RollbackMapping>()

    @Volatile
    private var defaultsRegistered = false

    /** 注册一条补偿映射（同 key 覆盖）。 */
    fun register(mapping: RollbackMapping) {
        mappings[mapping.toolName] = mapping
        DebugLog.d(TAG, "registered: ${mapping.toolName} -> ${mapping.rollbackToolName}")
    }

    /** 查询某工具的补偿映射，未注册返回 null。 */
    fun lookup(toolName: String): RollbackMapping? = mappings[toolName]

    /** 注销某工具的补偿映射。 */
    fun unregister(toolName: String) {
        mappings.remove(toolName)
    }

    /** 是否已注册（调试/测试用）。 */
    fun isRegistered(toolName: String): Boolean = mappings.containsKey(toolName)

    /** 已注册的全部工具名（调试用）。 */
    fun registeredTools(): Set<String> = mappings.keys.toSet()

    /**
     * 注册内置默认补偿映射。幂等，多次调用安全。
     *
     * - run_calendar：仅 action=create 可补偿，从 output 解析 "(ID: 123)" 得到 event_id，
     *   构造 delete 调用 {action=delete, event_id=...}；其余 action（query/update/delete）返回 null。
     * - run_intent：所有 intent 类型均不可逆（邮件/短信已交由系统应用，拨号仅打开面板），
     *   显式注册返回 null，表明"已知不可补偿"而非"未知工具"。
     * - run_js：JS Skill 可执行任意写操作（localStorage/网络请求等），副作用不可枚举故不可补偿；
     *   但注册 mapping 使 hasSideEffect=true，触发 checkpoint 创建，仍可回滚消息历史。
     * - open_browser：会打开外部浏览器（系统侧状态变更），无法自动撤回；同理注册为不可补偿但触发 checkpoint。
     *
     * 无持久副作用的只读工具（web_search / read_url / deep_research / speech_to_text / mimo_tts /
     * generate_report 等）不注册，[lookup] 返回 null 即视为无需补偿。
     */
    fun registerDefaults() {
        if (defaultsRegistered) return
        synchronized(this) {
            if (defaultsRegistered) return
            defaultsRegistered = true

            register(
                RollbackMapping(
                    toolName = "run_calendar",
                    rollbackToolName = "run_calendar",
                    inputExtractor = { input, output ->
                        val action = input["action"]?.toString()?.lowercase()?.trim()
                        if (action != "create") {
                            // query/update/delete 不做自动补偿（update 需原始数据，delete 已删除）
                            return@RollbackMapping null
                        }
                        val eventId = extractEventIdFromOutput(output) ?: return@RollbackMapping null
                        mapOf(
                            "action" to "delete",
                            "event_id" to eventId,
                        )
                    },
                ),
            )

            register(
                RollbackMapping(
                    toolName = "run_intent",
                    rollbackToolName = "run_intent",
                    inputExtractor = { _, _ ->
                        // email/sms 等已交由系统应用编辑器或已发出，无法自动撤回
                        null
                    },
                ),
            )

            // P2-4 修复：run_js 可执行写操作（localStorage/网络请求/文件等），副作用不可枚举故不可补偿；
            // 但注册 mapping 使 lookup != null → hasSideEffect=true，仍会创建 checkpoint 以回滚消息历史。
            register(
                RollbackMapping(
                    toolName = "run_js",
                    rollbackToolName = "run_js",
                    inputExtractor = { _, _ -> null },
                ),
            )

            // P2-4 修复：open_browser 打开外部浏览器（系统侧状态变更），无法自动撤回；
            // 同理注册为不可补偿，但触发 checkpoint 创建以支持消息历史回滚。
            register(
                RollbackMapping(
                    toolName = "open_browser",
                    rollbackToolName = "open_browser",
                    inputExtractor = { _, _ -> null },
                ),
            )
        }
    }

    /**
     * 从 run_calendar create 的输出文本中解析事件 ID。
     * 输出形如："[成功] 已创建日历事件「标题」(ID: 12345)\n时间: ..."
     */
    private val eventIdPattern = Regex("""ID:\s*(\d+)""")

    internal fun extractEventIdFromOutput(output: String?): String? {
        if (output.isNullOrBlank()) return null
        val match = eventIdPattern.find(output) ?: return null
        return match.groupValues.getOrNull(1)
    }
}
