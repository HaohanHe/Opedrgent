package top.hsyscn.opedrgent.mcp.editors

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.mcp.skills.SkillLoader
import top.hsyscn.opedrgent.mcp.skills.StandardSkillDefinition
import top.hsyscn.opedrgent.utils.DebugLog

/**
 * 编辑团与技能系统的适配器。
 *
 * 职责：
 * - 将 V2 Skill 系统的技能映射为编辑角色（EditorRole）
 * - 支持从已加载的 Skill 动态创建 DynamicRole
 * - 管理技能与编辑团之间的生命周期同步
 *
 * 使用方式：
 * ```kotlin
 * val adapter = EditorTeamSkillAdapter(context)
 * val skillRoles = adapter.getSkillBasedRoles()
 * ```
 */
class EditorTeamSkillAdapter(
    private val context: Context,
) {

    private var skillLoader: SkillLoader? = null

    /**
     * 设置 SkillLoader 实例（延迟注入，避免循环依赖）。
     */
    fun setSkillLoader(loader: SkillLoader) {
        skillLoader = loader
    }

    /**
     * 获取或创建 SkillLoader 实例。
     */
    fun getOrCreateSkillLoader(): SkillLoader {
        return skillLoader ?: SkillLoader(context).also {
            skillLoader = it
        }
    }

    // ==================== 核心适配方法 ====================

    /**
     * 从已启用的 Skill 列表生成编辑角色列表。
     *
     * 每个 Skill 可以映射为一个 DynamicRole，
     * 用于在群聊讨论或流水线中作为参与者。
     */
    suspend fun getSkillBasedRoles(): List<DynamicRole> = withContext(Dispatchers.IO) {
        val loader = getOrCreateSkillLoader()
        try {
            val enabledSkills = loader.getEnabledSkills()
            enabledSkills.mapNotNull { skill ->
                skillToDynamicRole(skill)
            }
        } catch (e: Exception) {
            DebugLog.w("EditorTeamSkillAdapter: 获取技能角色失败: ${e.message}")
            emptyList()
        }
    }

    /**
     * 根据 skillId 查找并转换为动态角色。
     */
    suspend fun getRoleBySkillId(skillId: String): DynamicRole? = withContext(Dispatchers.IO) {
        val loader = getOrCreateSkillLoader()
        try {
            val skill = loader.getSkillById(skillId) ?: return@withContext null
            skillToDynamicRole(skill)
        } catch (e: Exception) {
            DebugLog.w("EditorTeamSkillAdapter: 按 ID 查找技能失败 ($skillId): ${e.message}")
            null
        }
    }

    /**
     * 检查指定 Skill 是否已启用。
     */
    suspend fun isSkillEnabled(skillId: String): Boolean = withContext(Dispatchers.IO) {
        val loader = getOrCreateSkillLoader()
        try {
            val skill = loader.getSkillById(skillId)
            skill?.isEnabled == true
        } catch (e: Exception) {
            DebugLog.w("EditorTeamSkillAdapter: 检查技能启用状态失败: ${e.message}")
            false
        }
    }

    /**
     * 获取所有可用技能的摘要信息（供 LLM 规划参考）。
     */
    suspend fun getSkillsSummary(): String = withContext(Dispatchers.IO) {
        val loader = getOrCreateSkillLoader()
        try {
            val skills = loader.getEnabledSkills()
            if (skills.isEmpty()) return@withContext "（无已启用的技能）"

            buildString {
                appendLine("## 可用技能")
                for (skill in skills) {
                    val meta = skill.metadata
                    appendLine("- **${meta.name}** (${meta.category.displayName}): ${meta.description}")
                    if (meta.tags.isNotEmpty()) {
                        appendLine("  标签: ${meta.tags.joinToString(", ")}")
                    }
                }
            }
        } catch (e: Exception) {
            DebugLog.w("EditorTeamSkillAdapter: 获取技能摘要失败: ${e.message}")
            "（技能加载异常）"
        }
    }

    // ==================== 内部转换方法 ====================

    /**
     * 将 StandardSkillDefinition 转换为 DynamicRole。
     */
    private fun skillToDynamicRole(skill: StandardSkillDefinition): DynamicRole? {
        val meta = skill.metadata
        return try {
            DynamicRole(
                name = meta.name.replace("-", " ").replaceFirstChar { it.uppercase() },
                alias = meta.name.take(3).uppercase(),
                icon = pickIconForCategory(meta.category),
                description = meta.description,
                systemPrompt = buildSkillSystemPrompt(skill),
                inputHint = "${meta.name}: ${meta.description}",
            )
        } catch (e: Exception) {
            DebugLog.w("EditorTeamSkillAdapter: 技能转换失败 (${meta.name}): ${e.message}")
            null
        }
    }

    /**
     * 为 Skill 构建系统提示词（包含指令 + 元信息）。
     */
    private fun buildSkillSystemPrompt(skill: StandardSkillDefinition): String {
        val meta = skill.metadata
        return buildString {
            appendLine("# ${meta.name}")
            appendLine(meta.description)
            appendLine()
            appendLine("## 分类: ${meta.category.displayName}")
            if (meta.tags.isNotEmpty()) {
                appendLine("## 标签: ${meta.tags.joinToString(", ")}")
            }
            appendLine()
            appendLine("## 执行指令")
            appendLine(skill.instructions)
        }
    }

    /**
     * 根据技能分类选择图标字符。
     */
    private fun pickIconForCategory(category: top.hsyscn.opedrgent.mcp.skills.SkillCategory): String {
        return when (category) {
            top.hsyscn.opedrgent.mcp.skills.SkillCategory.WRITING -> "W"
            top.hsyscn.opedrgent.mcp.skills.SkillCategory.ANALYSIS -> "A"
            top.hsyscn.opedrgent.mcp.skills.SkillCategory.PRODUCTIVITY -> "P"
            top.hsyscn.opedrgent.mcp.skills.SkillCategory.CREATIVE -> "C"
            top.hsyscn.opedrgent.mcp.skills.SkillCategory.DEVELOPMENT -> "D"
            else -> "S"
        }
    }

    companion object {
        private const val TAG = "EditorTeamSkillAdapter"
    }
}
