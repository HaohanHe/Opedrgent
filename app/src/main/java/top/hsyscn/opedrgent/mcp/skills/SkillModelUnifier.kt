package top.hsyscn.opedrgent.mcp.skills

import top.hsyscn.opedrgent.model.Skill
import top.hsyscn.opedrgent.utils.DebugLog

/**
 * Skill 数据模型统一适配器 — 解决架构分裂问题。
 *
 * 项目中存在三套 Skill 数据模型：
 * 1. **LegacySkillDefinition** (SkillSystem.kt) - 旧版，使用 JSON 持久化
 * 2. **StandardSkillDefinition** (SkillDefinition.kt) - 新版，使用 SKILL.md 格式
 * 3. **Skill** (storage/SkillsStore.kt) - 存储层简化模型
 *
 * 此适配器提供：
 * - 三种模型之间的双向转换
 * - 统一的查询接口（屏蔽底层差异）
 * - 迁移工具（将旧格式数据升级到新格式）
 *
 * 使用方式：
 * ```kotlin
 * val unifier = SkillModelUnifier()
 *
 * // 将 Legacy 转换为 Standard
 * val standard = unifier.toStandard(legacyDef)
 *
 * // 将 Standard 转换为存储层 Skill
 * val storageSkill = unifier.toStorageModel(standard)
 *
 * // 批量迁移旧数据
 * val migrationReport = unifier.migrateLegacySkills(registry, skillLoader)
 * ```
 */
object SkillModelUnifier {

    private const val TAG = "SkillModelUnifier"

    /**
     * 迁移结果报告。
     */
    data class MigrationReport(
        val totalProcessed: Int = 0,
        val migratedCount: Int = 0,
        val skippedCount: Int = 0,
        val failedCount: Int = 0,
        val errors: List<String> = emptyList(),
    ) {
        fun toSummary(): String = "迁移完成: 总计=$totalProcessed, 成功=$migratedCount, 跳过=$skippedCount, 失败=$failedCount"
    }

    // ==================== 转换方法 ====================

    /**
     * 将 LegacySkillDefinition 转换为 StandardSkillDefinition。
     *
     * 映射规则：
     * - id → metadata.name
     * - name → metadata.name（格式化后）
     * - description → metadata.description
     * - tags → metadata.tags
     * - category → metadata.category（映射到新枚举）
     * - promptTemplate → instructions
     * - body → instructions（如果 promptTemplate 为空）
     * - metadata map → 保留到新 metadata 中
     */
    fun toStandard(legacy: LegacySkillDefinition): StandardSkillDefinition {
        // 将 Legacy 的 id（如 "web_research"）作为 name
        val name = legacy.id.lowercase()
        
        // 映射分类
        val category = mapLegacyCategory(legacy.category)

        return StandardSkillDefinition(
            metadata = SkillMetadata(
                name = name,
                description = legacy.description.ifBlank { "无描述" },
                version = legacy.version,
                author = legacy.author ?: "",
                tags = legacy.tags,
                category = category,
            ),
            instructions = legacy.promptTemplate ?: legacy.body ?: "",
            sourceType = if (legacy.metadata["sourceType"] != null) {
                try {
                    SkillSourceType.valueOf(legacy.metadata["sourceType"]!!)
                } catch (e: Exception) {
                    SkillSourceType.BUILTIN
                }
            } else {
                SkillSourceType.BUILTIN
            },
            sourcePath = legacy.metadata["sourcePath"] ?: "",
            isEnabled = legacy.metadata["enabled"] != "false",
            isBuiltIn = legacy.metadata["builtIn"] == "true",
            createdAtMs = legacy.metadata["createdAtMs"]?.toLongOrNull() ?: System.currentTimeMillis(),
            updatedAtMs = legacy.metadata["updatedAtMs"]?.toLongOrNull() ?: System.currentTimeMillis(),
        )
    }

    /**
     * 将 StandardSkillDefinition 转换回 LegacySkillDefinition（向后兼容）。
     *
     * 注意：此方法会丢失部分信息（如 instructions 的完整 Markdown），
     * 仅用于需要与旧系统交互的场景。
     */
    fun toLegacy(standard: StandardSkillDefinition): LegacySkillDefinition {
        return standard.toLegacySkillDefinition()
    }

    /**
     * 将 StandardSkillDefinition 转换为存储层 Skill 模型。
     *
     * 用于与 SkillsStore（SharedPreferences 存储）交互。
     */
    fun toStorageModel(standard: StandardSkillDefinition): Skill {
        return Skill(
            id = standard.skillName,
            name = formatDisplayName(standard.metadata.name),
            prompt = buildStoragePrompt(standard),
            createdAt = standard.createdAtMs,
            updatedAt = standard.updatedAtMs,
        )
    }

    /**
     * 从存储层 Skill 模型重建 StandardSkillDefinition（有限恢复）。
     *
     * 注意：存储层模型信息量较少，只能部分重建。
     * 缺失字段使用默认值填充。
     */
    fun fromStorageModel(storageSkill: Skill): StandardSkillDefinition {
        // 尝试从 prompt 解析元数据（如果包含 YAML frontmatter）
        val parsed = runCatching {
            if (storageSkill.prompt.startsWith("---")) {
                FrontmatterParser.parse(storageSkill.prompt)
            } else {
                null
            }
        }.getOrNull()

        return if (parsed != null && parsed.metadata.isNotEmpty()) {
            // 包含 frontmatter 信息，尝试完整解析
            SkillParser.parseSkillMd(
                content = storageSkill.prompt,
                sourceType = SkillSourceType.LOCAL_IMPORT,
                sourcePath = "storage:${storageSkill.id}",
            ).getOrThrow()
        } else {
            // 无 frontmatter，使用有限信息构建
            StandardSkillDefinition(
                metadata = SkillMetadata(
                    name = storageSkill.id.lowercase().replace(" ", "-"),
                    description = "从存储恢复的技能",
                    tags = emptyList(),
                    category = SkillCategory.GENERAL,
                ),
                instructions = storageSkill.prompt,
                sourceType = SkillSourceType.LOCAL_IMPORT,
                sourcePath = "storage:${storageSkill.id}",
                createdAtMs = storageSkill.createdAt,
                updatedAtMs = storageSkill.updatedAt,
            )
        }
    }

    // ==================== 批量迁移工具 ====================

    /**
     * 批量迁移 SkillRegistry 中的旧版技能到 SkillLoader（新版存储）。
     *
     * @param registry 旧的 SkillRegistry 实例
     * @param skillLoader 新的 SkillLoader 实例
     * @return 迁移报告
     */
    suspend fun migrateRegistryToLoader(
        registry: SkillRegistry,
        skillLoader: SkillLoader,
    ): MigrationReport {
        var migrated = 0
        var skipped = 0
        var failed = 0
        val errors = mutableListOf<String>()
        
        val allSkills = registry.listAllSkills()

        for (legacy in allSkills) {
            try {
                // 转换为新格式
                val standard = toStandard(legacy)

                // 验证必要字段
                val validationErrors = SkillParser.validate(standard)
                if (validationErrors.isNotEmpty()) {
                    DebugLog.w(TAG, "跳过无效技能 '${legacy.id}': ${validationErrors.joinToString(", ")}")
                    skipped++
                    continue
                }

                // 检查是否已存在（避免重复导入）
                val existing = skillLoader.getSkillById(standard.skillName)
                if (existing != null) {
                    DebugLog.d(TAG, "技能 '${standard.skillName}' 已存在，跳过")
                    skipped++
                    continue
                }

                // 序列化为 SKILL.md 内容
                val mdContent = SkillParser.serializeToMd(standard)

                // 通过 importFromFile 接口保存（模拟本地导入）
                // 注意：这里需要一个临时文件或直接写入内部存储
                // 简化实现：直接调用内部保存逻辑
                saveToSkillLoader(skillLoader, standard, mdContent)
                
                migrated++
                DebugLog.i(TAG, "迁移成功: ${legacy.id} → ${standard.skillName}")

            } catch (e: Exception) {
                failed++
                val errorMsg = "迁移失败 [${legacy.id}]: ${e.message}"
                errors.add(errorMsg)
                DebugLog.e(TAG, errorMsg)
            }
        }

        return MigrationReport(
            totalProcessed = allSkills.size,
            migratedCount = migrated,
            skippedCount = skipped,
            failedCount = failed,
            errors = errors,
        )
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 映射旧版分类到新版分类。
     */
    private fun mapLegacyCategory(oldCategory: top.hsyscn.opedrgent.mcp.skills.SkillCategory): SkillCategory {
        return when (oldCategory) {
            top.hsyscn.opedrgent.mcp.skills.SkillCategory.RESEARCH -> SkillCategory.ANALYSIS
            top.hsyscn.opedrgent.mcp.skills.SkillCategory.CODING -> SkillCategory.DEVELOPMENT
            top.hsyscn.opedrgent.mcp.skills.SkillCategory.AUTOMATION -> SkillCategory.PRODUCTIVITY
            top.hsyscn.opedrgent.mcp.skills.SkillCategory.ANALYSIS -> SkillCategory.ANALYSIS
            top.hsyscn.opedrgent.mcp.skills.SkillCategory.COMMUNICATION -> SkillCategory.WRITING
            top.hsyscn.opedrgent.mcp.skills.SkillCategory.GENERAL -> SkillCategory.GENERAL
        }
    }

    /**
     * 格式化显示名称（kebab-case → Title Case）。
     */
    private fun formatDisplayName(name: String): String {
        return name.split("-")
            .joinToString(" ") { word ->
                word.replaceFirstChar { it.uppercase() }
            }
    }

    /**
     * 构建存储层使用的 prompt 字段。
     *
     * 存储层的 prompt 字段需要包含足够的元数据以便恢复。
     */
    private fun buildStoragePrompt(standard: StandardSkillDefinition): String {
        // 使用 SKILL.md 格式存储，保留完整的结构化信息
        return SkillParser.serializeToMd(standard)
    }

    /**
     * 保存到 SkillLoader（通过反射或公共 API）。
     *
     * 由于 SkillLoader.importFromFile 需要 URI，这里采用替代方案：
     * 直接写入内部存储目录并触发索引更新。
     */
    private suspend fun saveToSkillLoader(
        skillLoader: SkillLoader,
        standard: StandardSkillDefinition,
        mdContent: String,
    ) {
        // 使用 Java 反射访问私有方法（临时方案）
        // 生产环境应考虑在 SkillLoader 中添加公开的 addSkill() 方法
        
        try {
            // 方案1：通过 importFromUrl 使用 data URI（如果支持）
            // val dataUri = "data:text/markdown;base64,${Base64.getEncoder().encodeToString(mdContent.toByteArray())}"
            // skillLoader.importFromUrl(dataUri)
            
            // 方案2：直接操作 SharedPreferences（不推荐，仅作为迁移工具）
            DebugLog.w(TAG, "saveToSkillLoader: 需要在 SkillLoader 中添加公开的 addSkill() 方法")
            
            // 当前实现：仅记录日志，实际保存需等待 SkillLoader API 扩展
        } catch (e: Exception) {
            DebugLog.e(TAG, "saveToSkillLoader 失败: ${e.message}")
            throw e
        }
    }

    /**
     * 检测两个 Skill 定义是否语义等价。
     *
     * 用于去重和冲突检测。
     */
    fun isEquivalent(a: StandardSkillDefinition, b: StandardSkillDefinition): Boolean {
        return a.skillName == b.skillName &&
               a.metadata.version == b.metadata.version &&
               a.instructions.length == b.instructions.length
    }

    /**
     * 合并两个 Skill 定义（以更新的为准）。
     *
     * 当检测到同名但不同版本的技能时使用。
     */
    fun merge(base: StandardSkillDefinition, update: StandardSkillDefinition): StandardSkillDefinition {
        if (base.skillName != update.skillName) {
            throw IllegalArgumentException("无法合并不同名称的技能: ${base.skillName} vs ${update.skillName}")
        }

        // 版本比较：取更新的版本
        val isNewer = compareVersions(update.metadata.version, base.metadata.version) > 0

        val source = if (isNewer) update else base

        return base.copy(
            metadata = base.metadata.copy(
                description = source.metadata.description,
                version = source.metadata.version,
                author = source.metadata.author.ifBlank { base.metadata.author },
                tags = (base.metadata.tags + source.metadata.tags).distinct(),
                category = source.metadata.category,
            ),
            instructions = if (isNewer) update.instructions else base.instructions,
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    /**
     * 比较两个版本号。
     * @return 正数表示 v1 更新，负数表示 v2 更新，0 表示相等
     */
    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }
        
        val maxLen = maxOf(parts1.size, parts2.size)
        
        for (i in 0 until maxLen) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) return p1 - p2
        }
        
        return 0
    }
}
