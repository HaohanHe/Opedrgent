package top.hsyscn.opedrgent.mcp.skills

import kotlinx.serialization.Serializable

/**
 * 技能元数据 — 对标 Google Gallery SKILL.md frontmatter 格式
 *
 * SKILL.md 示例：
 * ```
 * ---
 * name: insight-review
 * description: 识别文本中的闪光点和高光时刻
 * version: 1.0.0
 * author: Opedrgent
 * require-secret: false
 * tags: [点评, 亮点, 正向, review]
 * category: analysis
 * ---
 *
 * # 亮点识别
 *
 * ## Instructions
 * 你是一位善于发现亮点的分析师...
 * ```
 */
@Serializable
data class SkillMetadata(
    val name: String,                           // kebab-case 名称，如 "insight-review"
    val description: String,                    // 一句话描述（LLM 用此判断是否触发）
    val version: String = "1.0.0",
    val author: String = "",
    val homepage: String = "",                  // GitHub / 文档链接
    val requireSecret: Boolean = false,         // 是否需要 API Key
    val requireSecretDescription: String = "",  // Secret 用途说明，如 "需要 OpenAI API Key"
    val tags: List<String> = emptyList(),       // 分类标签
    val category: SkillCategory = SkillCategory.GENERAL,
)

/**
 * 技能分类 — 对标 Google Gallery 的分类体系
 */
enum class SkillCategory(val displayName: String) {
    GENERAL("通用"),
    WRITING("写作"),
    ANALYSIS("分析"),
    PRODUCTIVITY("效率"),
    CREATIVE("创意"),
    DEVELOPMENT("开发"),

    // ── 向后兼容旧分类 ──
    @Deprecated("使用 WRITING 替代", ReplaceWith("WRITING"))
    COMMUNICATION("写作"),

    @Deprecated("使用 DEVELOPMENT 替代", ReplaceWith("DEVELOPMENT"))
    CODING("开发"),

    @Deprecated("使用 PRODUCTIVITY 替代", ReplaceWith("PRODUCTIVITY"))
    AUTOMATION("效率"),

    @Deprecated("使用 ANALYSIS 替代", ReplaceWith("ANALYSIS"))
    RESEARCH("分析"),
}

/**
 * 标准化技能定义 — 完整的 Skill 数据模型
 */
@Serializable
data class StandardSkillDefinition(
    val metadata: SkillMetadata,
    val instructions: String,                   // Markdown 格式的指令内容（frontmatter 之后的部分）
    val sourceType: SkillSourceType = SkillSourceType.BUILTIN,
    val sourcePath: String = "",                // 来源路径（本地文件/URL）
    val isEnabled: Boolean = true,
    val isBuiltIn: Boolean = false,
    val localScriptsPath: String? = null,       // JS Skill 的本地资源路径（assets/skills/{name}/scripts/）
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
) {
    /** 便捷属性：skill 唯一标识（即 metadata.name） */
    val skillName: String get() = metadata.name

    /** 便捷属性：是否需要 Secret */
    val needsSecret: Boolean get() = metadata.requireSecret

    /**
     * 转换为兼容旧 SkillSystem.SkillDefinition 的格式
     * 用于向后兼容现有 CuratorService 等组件
     */
    fun toLegacySkillDefinition(): LegacySkillDefinition {
        return LegacySkillDefinition(
            id = metadata.name,
            name = metadata.name.split("-").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } },
            description = metadata.description,
            version = metadata.version,
            author = metadata.author.takeIf { it.isNotEmpty() },
            tags = metadata.tags,
            category = mapCategory(metadata.category),
            promptTemplate = metadata.description,
            body = instructions,
            metadata = buildMap {
                put("sourceType", sourceType.name)
                put("sourcePath", sourcePath)
                if (isBuiltIn) put("builtIn", "true")
                if (!isEnabled) put("enabled", "false")
                put("createdAtMs", createdAtMs.toString())
                put("updatedAtMs", updatedAtMs.toString())
                if (metadata.requireSecret) put("requireSecret", "true")
                if (metadata.homepage.isNotEmpty()) put("homepage", metadata.homepage)
            },
        )
    }

    private fun mapCategory(cat: SkillCategory): top.hsyscn.opedrgent.mcp.skills.SkillCategory {
        return when (cat) {
            SkillCategory.GENERAL -> top.hsyscn.opedrgent.mcp.skills.SkillCategory.GENERAL
            SkillCategory.WRITING, SkillCategory.COMMUNICATION -> top.hsyscn.opedrgent.mcp.skills.SkillCategory.COMMUNICATION
            SkillCategory.ANALYSIS, SkillCategory.RESEARCH -> top.hsyscn.opedrgent.mcp.skills.SkillCategory.ANALYSIS
            SkillCategory.PRODUCTIVITY, SkillCategory.AUTOMATION -> top.hsyscn.opedrgent.mcp.skills.SkillCategory.AUTOMATION
            SkillCategory.CREATIVE -> top.hsyscn.opedrgent.mcp.skills.SkillCategory.GENERAL
            SkillCategory.DEVELOPMENT, SkillCategory.CODING -> top.hsyscn.opedrgent.mcp.skills.SkillCategory.CODING
        }
    }
}

/**
 * 来源类型 — 技能从哪里加载的
 */
enum class SkillSourceType {
    BUILTIN,       // 内置（assets/skills/）
    LOCAL_IMPORT,  // 本地导入（用户文件）
    REMOTE_URL,    // 远程 URL 加载
    COMMUNITY,     // 社区精选
}
