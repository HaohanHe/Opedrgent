package top.hsyscn.opedrgent.mcp.skills

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File
import java.io.InputStream

/**
 * 技能加载器 — 管理所有技能的生命周期
 *
 * 职责：
 * - 加载内置技能（assets/skills/*.md）
 * - 加载用户导入的技能（本地 / 远程 URL）
 * - 启用/禁用/删除技能
 * - 管理 API Key 等 Secret
 * - 构建系统 Prompt 片段
 *
 * 存储方案：SharedPreferences（用户导入的技能 + 配置）+ assets（内置技能）
 */
class SkillLoader(private val context: Context) {

    companion object {
        private const val TAG = "SkillLoader"
        private const val PREFS_NAME = "opedrgent_skill_loader"
        private const val KEY_IMPORTED_SKILLS = "imported_skills"
        private const val KEY_DISABLED_SKILLS = "disabled_skills"
        private const val ASSETS_SKILLS_DIR = "skills"

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = false
        }
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val importedSkillsDir: File by lazy {
        File(context.filesDir, "imported_skills").also { it.mkdirs() }
    }

    // ── 核心加载方法 ──

    /**
     * 获取所有可用技能（内置 + 用户导入的）
     */
    suspend fun loadAllSkills(): List<StandardSkillDefinition> = withContext(Dispatchers.IO) {
        val builtin = loadBuiltinSkills()
        val imported = loadImportedSkills()
        return@withContext builtin + imported
    }

    /**
     * 获取已启用的技能
     */
    suspend fun getEnabledSkills(): List<StandardSkillDefinition> {
        val all = loadAllSkills()
        val disabledSet = getDisabledSkillNames().toSet()
        return all.filter { it.isEnabled && it.skillName !in disabledSet }
    }

    // ── 内置技能加载 ──

    /**
     * 从 assets/skills/ 目录加载所有 .md 技能文件
     */
    private fun loadBuiltinSkills(): List<StandardSkillDefinition> {
        return try {
            val fileList = context.assets.list(ASSETS_SKILLS_DIR)
                ?.filter { it.endsWith(".md") }
                ?: emptyList()

            DebugLog.i("$TAG: 发现 ${fileList.size} 个内置技能文件")

            fileList.mapNotNull { fileName ->
                loadBuiltinSkill(fileName)
            }.also {
                DebugLog.i("$TAG: 成功加载 ${it.size} 个内置技能")
            }
        } catch (e: Exception) {
            DebugLog.e("$TAG: 加载内置技能失败 — ${e.message}")
            emptyList()
        }
    }

    /**
     * 从 assets 加载单个内置技能
     */
    private fun loadBuiltinSkill(fileName: String): StandardSkillDefinition? {
        return try {
            val content = context.assets.open("$ASSETS_SKILLS_DIR/$fileName")
                .use(InputStream::readText)

            SkillParser.parseSkillMd(
                content = content,
                sourceType = SkillSourceType.BUILTIN,
                sourcePath = "assets://$ASSETS_SKILLS_DIR/$fileName",
            ).getOrThrow().copy(isBuiltIn = true)
        } catch (e: Exception) {
            DebugLog.w("$TAG: 加载内置技能 '$fileName' 失败 — ${e.message}")
            null
        }
    }

    // ── 用户导入技能 ──

    /**
     * 从远程 URL 导入技能
     */
    suspend fun importFromUrl(url: String): Result<StandardSkillDefinition> =
        withContext(Dispatchers.IO) {
            runCatching {
                val definition = SkillParser.loadFromUrl(url).getOrThrow()

                // 验证
                val errors = SkillParser.validate(definition)
                if (errors.isNotEmpty()) {
                    throw IllegalArgumentException(
                        "技能验证失败：\n${errors.joinToString("\n")}"
                    )
                }

                // 保存到本地
                saveImportedSkill(definition)

                DebugLog.i("$TAG: 从 URL 导入成功 — ${definition.skillName}")
                definition
            }.onFailure { e ->
                DebugLog.e("$TAG: 从 URL 导入失败 ($url) — ${e.message}")
            }
        }

    /**
     * 从本地文件导入技能
     */
    suspend fun importFromFile(uri: Uri): Result<StandardSkillDefinition> =
        withContext(Dispatchers.IO) {
            runCatching {
                val content = context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.bufferedReader(Charsets.UTF_8).readText()
                } ?: throw IllegalArgumentException("无法读取文件")

                val definition = SkillParser.parseSkillMd(
                    content = content,
                    sourceType = SkillSourceType.LOCAL_IMPORT,
                    sourcePath = uri.toString(),
                ).getOrThrow()

                // 验证
                val errors = SkillParser.validate(definition)
                if (errors.isNotEmpty()) {
                    throw IllegalArgumentException(
                        "技能验证失败：\n${errors.joinToString("\n")}"
                    )
                }

                // 保存到本地
                saveImportedSkill(definition)

                DebugLog.i("$TAG: 从文件导入成功 — ${definition.skillName}")
                definition
            }.onFailure { e ->
                DebugLog.e("$TAG: 从文件导入失败 — ${e.message}")
            }
        }

    /**
     * 保存用户导入的技能到本地存储
     */
    private fun saveImportedSkill(definition: StandardSkillDefinition) {
        try {
            // 保存 SKILL.md 原文
            val mdContent = SkillParser.serializeToMd(definition)
            val file = File(importedSkillsDir, "${definition.skillName}.md")
            file.writeText(mdContent, Charsets.UTF_8)

            // 更新索引
            val index = getImportedSkillsIndex().toMutableList()
            index.removeAll { it.skillName == definition.skillName }
            index.add(ImportedSkillIndexEntry(
                skillName = definition.skillName,
                sourcePath = definition.sourcePath,
                sourceType = definition.sourceType,
                importedAtMs = System.currentTimeMillis(),
            ))
            saveImportedSkillsIndex(index)

            DebugLog.d("$TAG: 已保存导入技能 '${definition.skillName}' 到本地")
        } catch (e: Exception) {
            DebugLog.e("$TAG: 保存导入技能失败 — ${e.message}")
            throw e
        }
    }

    /**
     * 加载所有用户导入的技能
     */
    private fun loadImportedSkills(): List<StandardSkillDefinition> {
        val index = getImportedSkillsIndex()
        return index.mapNotNull { entry ->
            val file = File(importedSkillsDir, "${entry.skillName}.md")
            if (!file.exists()) {
                DebugLog.w("$TAG: 导入技能文件不存在 — ${entry.skillName}.md，从索引移除")
                removeImportedSkillFromIndex(entry.skillName)
                return@mapNotNull null
            }

            runCatching {
                val content = file.readText(Charsets.UTF_8)
                SkillParser.parseSkillMd(content, entry.sourceType, entry.sourcePath).getOrThrow()
            }.getOrElse { e ->
                DebugLog.w("$TAG: 解析导入技能 '${entry.skillName}' 失败 — ${e.message}")
                null
            }
        }
    }

    // ── 启用/禁用/删除 ──

    /**
     * 启用/禁用技能
     */
    fun setSkillEnabled(skillName: String, enabled: Boolean) {
        val disabledSet = getDisabledSkillNames().toMutableList()
        if (enabled) {
            disabledSet.remove(skillName)
        } else {
            if (skillName !in disabledSet) disabledSet.add(skillName)
        }
        prefs.edit().putStringSet(KEY_DISABLED_SKILLS, disabledSet.toSet()).apply()
        DebugLog.i("$TAG: 技能 '$skillName' 已${if (enabled) "启用" else "禁用"}")
    }

    /**
     * 删除用户导入的技能（不能删除内置的）
     *
     * @return 是否删除成功
     */
    fun deleteSkill(skillName: String): Boolean {
        val allSkills = runCatching {
            kotlinx.coroutines.runBlocking { loadAllSkills() }
        }.getOrNull ?: return false

        val target = allSkills.find { it.skillName == skillName } ?: return false

        if (target.isBuiltIn) {
            DebugLog.w("$TAG: 不能删除内置技能 '$skillName'")
            return false
        }

        return try {
            // 删除文件
            val file = File(importedSkillsDir, "$skillName.md")
            if (file.exists()) file.delete()

            // 从索引移除
            removeImportedSkillFromIndex(skillName)

            // 如果在禁用列表中，也移除
            setSkillEnabled(skillName, true)

            DebugLog.i("$TAG: 已删除用户导入技能 '$skillName'")
            true
        } catch (e: Exception) {
            DebugLog.e("$TAG: 删除技能 '$skillName' 失败 — ${e.message}")
            false
        }
    }

    // ── Secret 管理（API Key 等） ──

    /**
     * 获取技能的 Secret（API Key 等）
     */
    fun getSecret(skillName: String): String? {
        return prefs.getString("secret_$skillName", null)
    }

    /**
     * 保存技能的 Secret
     */
    fun saveSecret(skillName: String, secret: String) {
        prefs.edit().putString("secret_$skillName", secret).apply()
        DebugLog.d("$TAG: 已保存技能 '$skillName' 的 Secret")
    }

    /**
     * 删除技能的 Secret
     */
    fun deleteSecret(skillName: String) {
        prefs.edit().remove("secret_$skillName").apply()
        DebugLog.d("$TAG: 已删除技能 '$skillName' 的 Secret")
    }

    // ── 系统 Prompt 构建 ──

    /**
     * 构建当前启用技能的系统 Prompt 片段
     *
     * 输出格式对标 Google Gallery：
     * ```
     * ## Available Skills
     *
     * ### 分类名
     * - **skill-name** (description)
     *   使用 `load_skill` 工具加载此技能获取详细指令...
     * ```
     */
    fun buildSkillsSystemPrompt(): String {
        val skills = runCatching {
            kotlinx.coroutines.runBlocking { getEnabledSkills() }
        }.getOrNull ?: return ""

        if (skills.isEmpty()) return ""

        val lines = mutableListOf<String>()
        lines.add("## Available Skills")
        lines.add("")
        lines.add("以下是你可用的技能。当用户的请求与某个技能匹配时，你应该使用该技能。")
        lines.add("使用 `load_skill` 工具加载技能以获取详细指令。")
        lines.add("")

        // 按分类分组
        val groupedByCategory = skills.groupBy { it.metadata.category.displayName }

        for ((categoryDisplayName, categorySkills) in groupedByCategory.toList()
            .sortedBy { it.first }) {
            lines.add("### $categoryDisplayName")
            for (skill in categorySkills.sortedBy { it.metadata.name }) {
                lines.add("- **${skill.metadata.name}**: ${skill.metadata.description}")
                if (skill.needsSecret) {
                    lines.add("  [注意] 此技能需要 API Key（Secret），请先确认已配置。")
                }
            }
            lines.add("")
        }

        return lines.joinToString("\n").trim()
    }

    // ── 内部存储辅助 ──

    private fun getDisabledSkillNames(): Set<String> {
        return prefs.getStringSet(KEY_DISABLED_SKILLS, emptySet()) ?: emptySet()
    }

    /**
     * 导入技能索引入口数据类
     */
    @kotlinx.serialization.Serializable
    private data class ImportedSkillIndexEntry(
        val skillName: String,
        val sourcePath: String,
        val sourceType: SkillSourceType,
        val importedAtMs: Long,
    )

    private fun getImportedSkillsIndex(): List<ImportedSkillIndexEntry> {
        val raw = prefs.getString(KEY_IMPORTED_SKILLS, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<ImportedSkillIndexEntry>>(raw)
        }.getOrElse {
            DebugLog.w("$TAG: 解析导入技能索引失败 — ${it.message}")
            emptyList()
        }
    }

    private fun saveImportedSkillsIndex(index: List<ImportedSkillIndexEntry>) {
        val raw = json.encodeToString(index)
        prefs.edit().putString(KEY_IMPORTED_SKILLS, raw).apply()
    }

    private fun removeImportedSkillFromIndex(skillName: String) {
        val index = getImportedSkillsIndex().filter { it.skillName != skillName }
        saveImportedSkillsIndex(index)
    }

    // ── 公开查询方法 ──

    /**
     * 按 ID 查找技能（用于 Curator 等内部组件）
     */
    suspend fun getSkillById(skillId: String): StandardSkillDefinition? {
        return loadAllSkills().find { it.skillName == skillId }
    }

    /**
     * 按名称查找技能
     */
    suspend fun findSkillByName(name: String): StandardSkillDefinition? {
        return loadAllSkills().find { it.skillName.equals(name, ignoreCase = true) }
    }

    /**
     * 搜索技能（按关键词匹配 name/description/tags）
     */
    suspend fun searchSkills(query: String): List<StandardSkillDefinition> {
        val all = loadAllSkills()
        val lowerQuery = query.lowercase()
        return all.filter { skill ->
            skill.metadata.name.lowercase().contains(lowerQuery) ||
                skill.metadata.description.lowercase().contains(lowerQuery) ||
                skill.metadata.tags.any { it.lowercase().contains(lowerQuery) }
        }
    }

    /**
     * 获取统计信息
     */
    fun getStats(): Map<String, Any> {
        val disabledCount = getDisabledSkillNames().size
        val importedCount = getImportedSkillsIndex().size
        val importedFiles = importedSkillsDir.listFiles()?.count { it.extension == "md" } ?: 0

        return mapOf(
            "disabled_count" to disabledCount,
            "imported_index_count" to importedCount,
            "imported_file_count" to importedFiles,
            "secrets_count" to prefs.all.keys.count { it.startsWith("secret_") },
        )
    }
}
