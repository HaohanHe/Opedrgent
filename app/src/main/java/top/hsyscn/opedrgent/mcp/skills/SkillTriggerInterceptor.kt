package top.hsyscn.opedrgent.mcp.skills

/**
 * Pre-LLM 拦截层：检查用户输入是否命中声明式 trigger。
 *
 * 命中时直接注入 skill instructions，跳过 load_skill round-trip，
 * 实现 Hermes 风格的零 token、零延迟 skill 激活。
 *
 * 提供两个入口：
 * - [check]：suspend，从 [SkillLoader] 实时加载已启用 skill（适合冷启动或低频路径）
 * - [checkAgainst]：非 suspend，使用调用方预缓存的 skill 列表（适合主对话热路径，零延迟）
 */
class SkillTriggerInterceptor(private val skillLoader: SkillLoader) {

    /**
     * 拦截结果。
     *
     * @param matched 是否命中 trigger
     * @param skillName 命中的 skill 名称（metadata.name）
     * @param skillInstructions skill 的完整指令内容（markdown body）
     * @param strippedInput 剥离 trigger 前缀后的用户输入
     * @param localScriptsPath JS skill 的本地脚本路径（用于 run_js 调用），无则为 null
     */
    data class TriggerResult(
        val matched: Boolean,
        val skillName: String = "",
        val skillInstructions: String = "",
        val strippedInput: String = "",
        val localScriptsPath: String? = null,
    )

    /**
     * 检查用户输入是否命中 trigger（suspend 版本，实时加载已启用 skill）。
     */
    suspend fun check(userInput: String): TriggerResult {
        val enabled = runCatching { skillLoader.getEnabledSkills() }.getOrDefault(emptyList())
        return checkAgainst(userInput, enabled)
    }

    /**
     * 检查用户输入是否命中 trigger（非 suspend 版本，使用预缓存 skill 列表，零延迟）。
     *
     * 适用于主对话热路径：调用方在 [top.hsyscn.opedrgent.ui.MainViewModel.refreshGallerySkills]
     * 中预先缓存已启用且带 triggers 的 skill 列表，传入此方法避免每条消息都做 I/O。
     *
     * @param userInput 用户原始输入文本
     * @param skills 已启用且带 triggers 的 skill 定义列表
     */
    fun checkAgainst(userInput: String, skills: List<StandardSkillDefinition>): TriggerResult {
        if (userInput.isBlank() || skills.isEmpty()) return TriggerResult(matched = false)

        val metadataList = skills.map { it.metadata }
        val matchedMetadata = TriggerMatcher.match(userInput, metadataList)
            ?: return TriggerResult(matched = false)

        val skillDef = skills.firstOrNull { it.metadata.name == matchedMetadata.name }
            ?: return TriggerResult(matched = false)

        val trigger = TriggerMatcher.matchedTrigger(userInput, matchedMetadata)
        val strippedInput = if (trigger != null) {
            TriggerMatcher.stripTrigger(userInput, trigger)
        } else {
            userInput.trim()
        }

        return TriggerResult(
            matched = true,
            skillName = matchedMetadata.name,
            skillInstructions = skillDef.instructions,
            strippedInput = strippedInput,
            localScriptsPath = skillDef.localScriptsPath,
        )
    }
}
