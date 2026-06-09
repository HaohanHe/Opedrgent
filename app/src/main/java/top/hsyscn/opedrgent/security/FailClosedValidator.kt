package top.hsyscn.opedrgent.security

import top.hsyscn.opedrgent.utils.DebugLog

/**
 * FAIL-CLOSED 参数校验原则（来自 Claude Code Shell 工具设计）：
 *
 * - 不理解的输入 → 拒绝执行而非猜测
 * - 危险操作 → 需要显式确认
 * - 参数缺失必要字段 → 报错而非用默认值填充
 *
 * 本校验器在 PermissionEngine 之前执行，作为第一道防线。
 */
object FailClosedValidator {

    /** 危险命令关键词（正则模式） */
    private val DANGEROUS_PATTERNS = listOf(
        "rm -rf",
        "sudo",
        "format",
        "drop table",
        "delete from",
        "> /dev/",
        "mkfs",
        ":(){",
        "curl.*\\|.*sh",
    )

    /**
     * 校验工具调用参数是否安全。
     *
     * @param toolName 工具名称
     * @param params   工具参数 Map<String, Any>
     * @return Pair(是否通过, 错误信息)
     */
    fun validateToolInput(toolName: String, params: Map<String, Any>): Pair<Boolean, String> {
        // 1. 检查危险模式
        for (param in params.values) {
            val str = param.toString().lowercase()
            for (pattern in DANGEROUS_PATTERNS) {
                if (Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(str)) {
                    DebugLog.w("FailClosedValidator: 拒绝危险操作 — tool=$toolName, pattern='$pattern', value='$str'")
                    return false to "检测到危险操作模式，已拒绝执行"
                }
            }
        }

        // 2. 必要字段检查（根据工具类型）
        when (toolName) {
            "send_note_to_chat", "sendNoteWithSkill" -> {
                if (!params.containsKey("noteId")) {
                    return false to "缺少必要参数: noteId"
                }
            }
            "web_search" -> {
                val query = params["query"] ?: params["keyword"]
                if (query == null || query.toString().isBlank()) {
                    return false to "缺少必要参数: query 或 keyword"
                }
            }
            "read_url" -> {
                if (!params.containsKey("url") || params["url"].toString().isBlank()) {
                    return false to "缺少必要参数: url"
                }
            }
            "write_file" -> {
                if (!params.containsKey("path")) {
                    return false to "缺少必要参数: path"
                }
                if (!params.containsKey("content")) {
                    return false to "缺少必要参数: content"
                }
            }
        }

        return true to ""
    }

    /**
     * 校验工具调用参数（String 参数版本），兼容现有代码中 Map<String, String> 的场景。
     */
    fun validateToolInputStringParams(toolName: String, params: Map<String, String>): Pair<Boolean, String> {
        return validateToolInput(toolName, params.mapValues { it.value as Any })
    }
}
