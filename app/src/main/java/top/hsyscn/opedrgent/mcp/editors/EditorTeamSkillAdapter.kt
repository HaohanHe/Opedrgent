package top.hsyscn.opedrgent.mcp.editors

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.mcp.skills.CuratorService
import top.hsyscn.opedrgent.mcp.skills.SkillDefinition
import top.hsyscn.opedrgent.mcp.skills.SkillLoader
import top.hsyscn.opedrgent.mcp.skills.SkillPromptCache
import top.hsyscn.opedrgent.mcp.skills.StandardSkillDefinition
import top.hsyscn.opedrgent.utils.DebugLog

/**
 * 编辑团与 Skill 系统集成适配器 — 解决 EditorTeam 与 Skill 系统隔离问题。
 *
 * 核心功能：
 * 1. **技能感知规划**：在编辑团规划阶段注入可用技能列表，让 LLM 选择合适的技能
 * 2. **技能链式执行**：编辑团输出可通过 Skill 链进行后处理（如内容润色、亮点识别）
 * 3. **使用追踪**：记录编辑团对 Skill 的使用情况，供 Curator 进行生命周期管理
 *
 * 使用方式：
 * ```kotlin
 * val adapter = EditorTeamSkillAdapter(skillLoader, curatorService)
 *
 * // 在编辑团规划前，获取增强的系统 Prompt（包含可用技能）
 * val enhancedPrompt = adapter.buildEditorSystemPrompt(basePrompt, targetPlatform)
 *
 * // 编辑团完成后，应用后处理技能链
 * val finalOutput = adapter.applyPostProcessingSkills(output, listOf("text_refine", "insight_review"))
 * ```
 *
 * @param skillLoader 技能加载器（提供可用技能列表）
 * @param curatorService 技能管家服务（可选，用于使用追踪）
 */
class EditorTeamSkillAdapter(
    private val skillLoader: SkillLoader,
    private val curatorService: CuratorService? = null,
) {

    companion object {
        private const val TAG = "EditorTeamSkillAdapter"

        /** 后处理技能黑名单（这些技能不适合自动后处理） */
        private val POST_PROCESS_BLACKLIST = setOf(
            "web_research",       // 需要网络搜索，不适合后处理
            "code_analysis",     // 需要代码输入
            "task_automation",   // 需要工具调用
            "mimo_tts",          // TTS 不适合文本后处理
            "multi_agent_collaboration", // 递归风险
        )

        /** 编辑团适用的技能类别 */
        private val EDITOR_RELEVANT_CATEGORIES = setOf(
            "WRITING",    // 写作相关
            "ANALYSIS",   // 分析相关
            "GENERAL",    // 通用
        )
    }

    /**
     * 构建增强的编辑团系统 Prompt。
     *
     * 在基础系统 Prompt 后追加可用技能列表，
     * 让 LLM 总编在规划时就能选择合适的技能。
     *
     * @param basePrompt 原始系统 Prompt
     * @param targetPlatform 目标平台（用于过滤相关技能）
     * @return 增强后的系统 Prompt
     */
    suspend fun buildEditorSystemPrompt(
        basePrompt: String,
        targetPlatform: String? = null,
    ): String = withContext(Dispatchers.IO) {
        try {
            // 1. 获取启用的技能列表
            val enabledSkills = skillLoader.getEnabledSkills()

            if (enabledSkills.isEmpty()) {
                DebugLog.d(TAG, "无可用技能，返回原始 Prompt")
                return@withContext basePrompt
            }

            // 2. 过滤出适合编辑团的技能
            val relevantSkills = filterEditorRelevantSkills(enabledSkills, targetPlatform)

            if (relevantSkills.isEmpty()) {
                DebugLog.d(TAG, "无编辑团相关技能，返回原始 Prompt")
                return@withContext basePrompt
            }

            // 3. 构建技能指令片段
            val skillsSection = buildSkillsInstructionSection(relevantSkills)

            // 4. 拼接到基础 Prompt
            """
            |$basePrompt
            |
            |---
            |
            |## 🎯 可用技能工具箱
            |
            |你在规划和执行编辑任务时，可以使用以下专业技能来提升输出质量。
            |当任务匹配某个技能时，**必须**在相应步骤中激活该技能。
            |
            |$skillsSection
            |
            |### 技能使用规范
            |
            |1. **规划阶段**：分析任务需求，标记需要使用的技能（如 `[SKILL:text_refine]`）
            |2. **执行阶段**：在对应角色的输出中应用技能的指令模板
            |3. **质量检查**：确认技能是否正确应用，输出是否符合技能要求
            |4. **链式处理**：可组合多个技能（如先 `insight_review` 再 `text_refine`）
            """.trimMargin()

        } catch (e: Exception) {
            DebugLog.w(TAG, "构建增强 Prompt 失败，回退到原始版本: ${e.message}")
            basePrompt
        }
    }

    /**
     * 对编辑团输出应用后处理技能链。
     *
     * 按顺序应用多个技能对文本进行处理，
     * 每个技能的输出作为下一个技能的输入。
     *
     * @param output 编辑团原始输出
     * @param skillIds 要应用的技能 ID 列表（按顺序执行）
     * @param context 额外上下文信息（传递给技能）
     * @return 最终处理后的文本；如果所有技能都失败则返回原始文本
     */
    suspend fun applyPostProcessingSkills(
        output: String,
        skillIds: List<String>,
        context: String = "",
    ): String = withContext(Dispatchers.IO) {
        var currentText = output
        val appliedSkills = mutableListOf<String>()

        for (skillId in skillIds) {
            // 跳过黑名单中的技能
            if (skillId in POST_PROCESS_BLACKLIST) {
                DebugLog.d(TAG, "跳过黑名单技能: $skillId")
                continue
            }

            try {
                // 加载技能定义
                val skill = skillLoader.getSkillById(skillId)
                if (skill == null) {
                    DebugLog.w(TAG, "技能不存在: $skillId")
                    continue
                }

                // 检查技能是否启用
                if (!skill.isEnabled) {
                    DebugLog.d(TAG, "技能已禁用: $skillId")
                    continue
                }

                DebugLog.i(TAG, "应用后处理技能: $skillId (输入长度=${currentText.length})")

                // 应用技能处理（这里简化为包装成 prompt 让外部 LLM 处理）
                // 实际实现中可能需要调用 LLM 来执行技能指令
                val processedText = applySkillToText(currentText, skill, context)

                if (processedText != null && processedText.isNotEmpty()) {
                    currentText = processedText
                    appliedSkills.add(skillId)

                    // 记录使用情况
                    recordSkillUsage(skillId)

                    DebugLog.i(TAG, "技能 $skillId 处理完成 (输出长度=${currentText.length})")
                } else {
                    DebugLog.w(TAG, "技能 $skillId 返回空结果，跳过")
                }
            } catch (e: Exception) {
                DebugLog.e(TAG, "应用技能 $skillId 失败: ${e.message}")
                // 单个技能失败不阻断整个链
            }
        }

        DebugLog.i(
            TAG,
            "后处理完成: 应用了 ${appliedSkills.size} 个技能 [${appliedSkills.joinToString(", ")}], " +
            "最终长度=${currentText.length}"
        )

        currentText
    }

    /**
     * 根据任务描述推荐最适合的技能组合。
     *
     * @param taskDescription 任务描述
     * @param maxSkills 最大推荐数量（默认 3）
     * @return 推荐的技能 ID 列表（按相关性排序）
     */
    suspend fun recommendSkillsForTask(
        taskDescription: String,
        maxSkills: Int = 3,
    ): List<StandardSkillDefinition> = withContext(Dispatchers.IO) {
        try {
            val allSkills = skillLoader.getEnabledSkills()
            val query = taskDescription.lowercase()

            val scoredSkills = allSkills.map { skill ->
                var score = 0

                // 名称匹配（权重高）
                if (skill.metadata.name.lowercase().contains(query)) score += 5

                // 描述匹配
                if (skill.metadata.description.lowercase().contains(query)) score += 3

                // 标签匹配
                val matchedTags = skill.metadata.tags.count { tag ->
                    query.contains(tag.lowercase()) || tag.lowercase().contains(query)
                }
                score += matchedTags * 2

                // 分类匹配
                if (EDITOR_RELEVANT_CATEGORIES.contains(skill.metadata.category.name)) score += 1

                Pair(skill, score)
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(maxSkills)
            .map { it.first }

            DebugLog.i(TAG, "为任务推荐 ${scoredSkills.size} 个技能: ${scoredSkills.joinToString { it.skillName }}")

            scoredSkills
        } catch (e: Exception) {
            DebugLog.e(TAG, "推荐技能失败: ${e.message}")
            emptyList()
        }
    }

    // ==================== 内部实现 ====================

    /**
     * 过滤出适合编辑团场景的技能。
     *
     * 规则：
     * - 排除需要特殊权限的技能（requireSecret=true）
     * - 优先保留写作、分析、通用类别的技能
     * - 根据目标平台进一步筛选
     */
    private suspend fun filterEditorRelevantSkills(
        skills: List<StandardSkillDefinition>,
        targetPlatform: String?,
    ): List<StandardSkillDefinition> {
        return skills.filter { skill ->
            // 排除需要 Secret 的技能（编辑团无法交互式输入 API Key）
            if (skill.needsSecret) return@filter false

            // 保留相关类别的技能
            if (skill.metadata.category.name in EDITOR_RELEVANT_CATEGORIES) {
                return@filter true
            }

            // 根据目标平台特殊处理
            if (targetPlatform != null) {
                when (targetPlatform.lowercase()) {
                    "wechat", "公众号" -> {
                        // 公众号适合长文写作、深度分析类技能
                        return@filter skill.metadata.tags.any { 
                            it in setOf("写作", "润色", "深化", "analysis") 
                        }
                    }
                    "xiaohongshu", "小红书" -> {
                        // 小红书适合短文案、创意类技能
                        return@filter skill.metadata.tags.any { 
                            it in setOf("润色", "创意", "亮点", "review") 
                        }
                    }
                }
            }

            false
        }
    }

    /**
     * 构建技能指令片段（Markdown 格式）。
     */
    private fun buildSkillsInstructionSection(skills: List<StandardSkillDefinition>): String {
        val lines = mutableListOf<String>()

        // 按分类分组
        val groupedByCategory = skills.groupBy { it.metadata.category.displayName }

        for ((categoryName, categorySkills) in groupedByCategory.toList()
            .sortedBy { it.first }) {
            lines.add("### $categoryName")
            lines.add("")

            for (skill in categorySkills.sortedBy { it.metadata.name }) {
                lines.add("- **${skill.metadata.name}**: ${skill.metadata.description}")
                
                // 如果有标签，显示关键标签
                if (skill.metadata.tags.isNotEmpty()) {
                    val tagsPreview = skill.metadata.tags.take(3).joinToString(", ") { "`$it`" }
                    lines.add("  - 标签: $tagsPreview")
                }
            }
            lines.add("")
        }

        return lines.joinToString("\n")
    }

    /**
     * 将技能应用到文本（生成带技能指令的 Prompt）。
     *
     * 注意：此方法只生成处理指令，实际执行需要外部 LLM 配合。
     * 返回格式化的 prompt 片段，供 EditorTeam 的某个角色使用。
     */
    private fun applySkillToText(
        text: String,
        skill: StandardSkillDefinition,
        context: String,
    ): String? {
        if (text.isBlank()) return null

        // 构建技能执行的完整 Prompt
        val instructions = skill.instructions.ifBlank {
            // 如果 instructions 为空，使用 description 作为后备
            "请根据以下技能要求处理文本：\n${skill.metadata.description}"
        }

        return """
            |## 技能指令：${skill.metadata.name}
            |
            |### 技能说明
            |${skill.metadata.description}
            |
            |### 处理要求
            |$instructions
            |
            |### 待处理文本
            |
            |```
            |$text
            |```
            |
            |${if (context.isNotBlank()) "### 额外上下文\n\ncontext\n" else ""}
            |
            |请严格按照技能指令处理上述文本，直接输出处理结果，不要添加额外解释。
        """.trimMargin()
    }

    /**
     * 记录技能使用情况（委托给 CuratorService）。
     */
    private fun recordSkillUsage(skillId: String) {
        try {
            curatorService?.touchSkill(skillId)
        } catch (e: Exception) {
            DebugLog.w(TAG, "记录技能使用失败（非致命）: ${e.message}")
        }
    }
}
