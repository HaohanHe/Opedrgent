package top.hsyscn.opedrgent.intelligence

/**
 * Task ID 前缀分类系统。
 *
 * 每种任务类型拥有唯一的前缀字符，便于日志追踪、调试和统计分析。
 * Task ID 格式：`{prefix}{6位随机字符}`，例如 `c3kx9mz`、`nA7b2pQ`
 */
enum class TaskType(val prefix: Char, val displayName: String) {
    CHAT('c', "AI 对话"),
    ASR('a', "语音识别"),
    NOTE('n', "笔记操作"),
    SKILL('s', "技能调用"),
    EDITOR('e', "编辑团"),
    PIPELINE('p', "流水线"),
    SEARCH('r', "搜索"),
    COMPRESS('x', "上下文压缩"),
}

object TaskIdGenerator {

    private const val ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz"

    /**
     * 生成指定类型的 Task ID。
     *
     * @param type 任务类型
     * @return 格式为 `{prefix}{6位随机字符}` 的字符串
     */
    fun generate(type: TaskType): String {
        val randomPart = (1..6).map { ALPHABET.random() }.joinToString("")
        return "${type.prefix}$randomPart"
    }

    /**
     * 从 Task ID 解析出任务类型。
     *
     * @param taskId 任务 ID 字符串
     * @return 对应的 TaskType，如果前缀无法识别则返回 null
     */
    fun parseType(taskId: String): TaskType? {
        val prefix = taskId.firstOrNull() ?: return null
        return TaskType.entries.find { it.prefix == prefix }
    }

    /**
     * 判断一个 Task ID 是否属于给定类型。
     */
    fun isOfType(taskId: String, type: TaskType): Boolean {
        return parseType(taskId) == type
    }
}
