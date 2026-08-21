package top.hsyscn.opedrgent.mcp.skills

/**
 * 声明式前缀触发器匹配（Hermes 风格，零 token、零延迟）。
 *
 * 用户输入以某个 trigger 开头时（大小写不敏感），直接激活对应 skill，
 * 跳过 LLM 决策与 load_skill round-trip。匹配逻辑为纯函数，无 I/O、无协程。
 */
object TriggerMatcher {

    /**
     * 在已启用 skill 元数据列表中查找匹配用户输入的 trigger。
     *
     * @param userInput 用户原始输入文本
     * @param skills 已启用 skill 的元数据列表
     * @return 匹配的 [SkillMetadata]（取第一个匹配），null 表示无匹配
     */
    fun match(userInput: String, skills: List<SkillMetadata>): SkillMetadata? {
        val trimmedInput = userInput.trim()
        if (trimmedInput.isEmpty()) return null
        val lowerInput = trimmedInput.lowercase()
        for (skill in skills) {
            for (trigger in skill.triggers) {
                val lowerTrigger = trigger.trim().lowercase()
                if (lowerTrigger.isNotEmpty() && lowerInput.startsWith(lowerTrigger)) {
                    return skill
                }
            }
        }
        return null
    }

    /**
     * 返回命中用户输入的具体 trigger 字符串（原始大小写），未命中返回 null。
     */
    fun matchedTrigger(userInput: String, skill: SkillMetadata): String? {
        val lowerInput = userInput.trim().lowercase()
        if (lowerInput.isEmpty()) return null
        return skill.triggers.firstOrNull { trigger ->
            val lowerTrigger = trigger.trim().lowercase()
            lowerTrigger.isNotEmpty() && lowerInput.startsWith(lowerTrigger)
        }
    }

    /**
     * 从用户输入中剥离已匹配的 trigger 前缀，返回剩余内容。
     * 例如 trigger="/hash", input="/hash hello.txt" → "hello.txt"
     */
    fun stripTrigger(userInput: String, trigger: String): String {
        val trimmed = userInput.trim()
        val lowerInput = trimmed.lowercase()
        val lowerTrigger = trigger.trim().lowercase()
        return if (lowerTrigger.isNotEmpty() && lowerInput.startsWith(lowerTrigger)) {
            trimmed.substring(lowerTrigger.length).trim()
        } else {
            trimmed
        }
    }
}
