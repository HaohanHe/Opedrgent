package top.hsyscn.opedrgent.utils

/**
 * 快捷指令 (Slash Commands) — 定义、解析与注册表。
 *
 * ## 设计理念
 *
 * 用户在聊天输入框输入 `/` 开头的指令时，直接触发对应功能，
 * 无需等待 LLM 决策。适用于高频操作（搜索/检索/导出/模式切换）。
 *
 * ## 指令格式
 *
 * ```
 * /command arg1 arg2 ...
 * /search Kotlin 协程原理
 * /rag 向量检索原理
 * /export
 * ```
 *
 * 指令名不区分大小写，参数为指令后到行尾的全部内容。
 *
 * ## 与工具调用的关系
 *
 * - 工具调用 (Tool Call): LLM 自主决策调用，走 `<tool_call>` 协议
 * - 快捷指令: 用户主动触发，直接路由到工具或 VM 方法，跳过 LLM 决策环节
 *
 * 两者共享底层工具实现（ToolExecutor），但触发路径不同。
 */
object SlashCommands {

    /** 指令分类 */
    enum class Category(val label: String) {
        SEARCH("搜索"),
        KNOWLEDGE("知识库"),
        MODE("模式切换"),
        EXPORT("导出"),
        AUDIO("语音"),
        HELP("帮助"),
    }

    /** 指令定义 */
    data class Command(
        val name: String,           // 不含 /，如 "search"
        val aliases: List<String> = emptyList(),
        val description: String,
        val usage: String,          // 如 "/search <query>"
        val example: String = "",   // 如 "/search Kotlin 协程"
        val category: Category,
        /** 参数是否必填 */
        val requiresArgs: Boolean = false,
    )

    /** 解析结果 */
    data class ParsedCommand(
        val command: Command,
        val args: String,           // 原始参数字符串（已 trim）
    )

    /** 所有已注册指令 */
    val COMMANDS: List<Command> = listOf(
        Command(
            name = "search",
            aliases = listOf("s", "web"),
            description = "网络搜索（直接调用 web_search 工具，跳过 LLM 决策）",
            usage = "/search <query>",
            example = "/search Kotlin 协程原理",
            category = Category.SEARCH,
            requiresArgs = true,
        ),
        Command(
            name = "rag",
            aliases = listOf("kb", "knowledge"),
            description = "知识库检索（从本地知识库检索相关文档）",
            usage = "/rag <query>",
            example = "/rag 向量检索原理",
            category = Category.KNOWLEDGE,
            requiresArgs = true,
        ),
        Command(
            name = "deep",
            aliases = listOf("research"),
            description = "切换深度研究模式（多 Agent 协作，适合复杂问题）",
            usage = "/deep",
            example = "/deep",
            category = Category.MODE,
            requiresArgs = false,
        ),
        Command(
            name = "export",
            aliases = listOf("exp"),
            description = "导出当前会话为 Markdown 文件",
            usage = "/export",
            example = "/export",
            category = Category.EXPORT,
            requiresArgs = false,
        ),
        Command(
            name = "tts",
            aliases = listOf("say"),
            description = "文字转语音（将后续文字朗读出来）",
            usage = "/tts <text>",
            example = "/tts 你好，世界",
            category = Category.AUDIO,
            requiresArgs = true,
        ),
        Command(
            name = "interview",
            aliases = listOf("iv"),
            description = "进入面试模式（需在设置中配置面试参数）",
            usage = "/interview",
            example = "/interview",
            category = Category.MODE,
            requiresArgs = false,
        ),
        Command(
            name = "help",
            aliases = listOf("?", "commands"),
            description = "显示所有可用快捷指令",
            usage = "/help",
            example = "/help",
            category = Category.HELP,
            requiresArgs = false,
        ),
    )

    /** 指令名 → Command 映射（含别名） */
    private val nameMap: Map<String, Command> = buildMap {
        COMMANDS.forEach { cmd ->
            put(cmd.name.lowercase(), cmd)
            cmd.aliases.forEach { put(it.lowercase(), cmd) }
        }
    }

    /**
     * 尝试解析输入文本为快捷指令。
     *
     * @param text 用户输入文本
     * @return 解析结果；如果不是指令（不以 / 开头）返回 null
     */
    fun parse(text: String): ParsedCommand? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("/")) return null

        // 提取指令名（/ 后到第一个空格或行尾）
        val withoutSlash = trimmed.substring(1)
        val spaceIdx = withoutSlash.indexOfFirst { it.isWhitespace() }
        val cmdName = if (spaceIdx >= 0) withoutSlash.substring(0, spaceIdx) else withoutSlash
        val args = if (spaceIdx >= 0) withoutSlash.substring(spaceIdx + 1).trim() else ""

        val command = nameMap[cmdName.lowercase()] ?: return null
        return ParsedCommand(command = command, args = args)
    }

    /**
     * 判断文本是否为快捷指令（以 / 开头且能匹配到已注册指令）。
     */
    fun isSlashCommand(text: String): Boolean = parse(text) != null

    /**
     * 根据前缀过滤指令（用于输入补全 UI）。
     *
     * @param prefix 用户已输入的内容（如 "/se"），可为空（返回全部）
     * @return 匹配的指令列表
     */
    fun filterByPrefix(prefix: String): List<Command> {
        if (!prefix.startsWith("/")) return emptyList()
        val query = prefix.substring(1).lowercase()
        if (query.isEmpty()) return COMMANDS
        return COMMANDS.filter { cmd ->
            cmd.name.startsWith(query) || cmd.aliases.any { it.startsWith(query) }
        }
    }

    /**
     * 生成帮助文本（/help 指令的输出）。
     */
    fun helpText(): String = buildString {
        appendLine("可用快捷指令：")
        appendLine()
        Category.values().forEach { cat ->
            val cmds = COMMANDS.filter { it.category == cat }
            if (cmds.isEmpty()) return@forEach
            appendLine("【${cat.label}】")
            cmds.forEach { cmd ->
                appendLine("  ${cmd.usage}")
                appendLine("    ${cmd.description}")
                if (cmd.aliases.isNotEmpty()) {
                    appendLine("    别名: ${cmd.aliases.joinToString(", ") { "/$it" }}")
                }
            }
            appendLine()
        }
        appendLine("提示：输入 / 后会自动显示指令列表，可直接选择。")
    }
}
