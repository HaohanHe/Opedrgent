package top.hsyscn.opedrgent.mcp.skills

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import top.hsyscn.opedrgent.utils.DebugLog
import okhttp3.OkHttpClient
import okhttp3.Request
import top.hsyscn.opedrgent.network.NetworkConfig
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * 技能加载器 — 管理所有技能的生命周期
 *
 * 职责：
 * - 加载内置技能（assets/skills 下的 .md 文件）
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

        /** 技能文件最大大小（512KB） */
        private const val MAX_SKILL_FILE_SIZE = 512 * 1024L

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
            val allEntries = context.assets.list(ASSETS_SKILLS_DIR)?.toList() ?: emptyList()
            val mdFiles = allEntries.filter { it.endsWith(".md") }
            val subDirs = allEntries.filter { !it.endsWith(".md") }

            val results = mutableListOf<StandardSkillDefinition>()

            for (fileName in mdFiles) {
                loadBuiltinSkill(fileName)?.let { results.add(it) }
            }

            for (dirName in subDirs) {
                try {
                    val subEntries = context.assets.list("$ASSETS_SKILLS_DIR/$dirName")?.toList() ?: emptyList()
                    val skillMd = subEntries.firstOrNull { it.equals("SKILL.md", ignoreCase = true) }
                    if (skillMd != null) {
                        loadBuiltinSkillFromSubdir(dirName, skillMd)?.let { results.add(it) }
                    }
                } catch (e: Exception) {
                    DebugLog.w("$TAG: 加载子目录技能失败 '$dirName' — ${e.message}")
                }
            }

            DebugLog.i("$TAG: 成功加载 ${results.size} 个内置技能")
            results
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
                .use { it.bufferedReader(Charsets.UTF_8).readText() }

            parseSkillMd(
                content = content,
                sourceType = SkillSourceType.BUILTIN,
                sourcePath = "assets://$ASSETS_SKILLS_DIR/$fileName",
            )?.copy(isBuiltIn = true)
        } catch (e: Exception) {
            DebugLog.w("$TAG: 加载内置技能 '$fileName' 失败 — ${e.message}")
            null
        }
    }

    private fun loadBuiltinSkillFromSubdir(dirName: String, skillMd: String): StandardSkillDefinition? {
        return try {
            val content = context.assets.open("$ASSETS_SKILLS_DIR/$dirName/$skillMd")
                .use { it.bufferedReader(Charsets.UTF_8).readText() }

            val scriptsPath = "skills/$dirName/scripts"
            parseSkillMd(
                content = content,
                sourceType = SkillSourceType.BUILTIN,
                sourcePath = "assets://$ASSETS_SKILLS_DIR/$dirName/$skillMd",
            )?.copy(
                isBuiltIn = true,
                localScriptsPath = scriptsPath,
            )
        } catch (e: Exception) {
            DebugLog.w("$TAG: 加载子目录内置技能 '$dirName/$skillMd' 失败 — ${e.message}")
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
                // URL 安全校验
                require(url.startsWith("https://") || url.startsWith("http://localhost") || url.startsWith("http://127.0.0.1")) {
                    "仅支持 HTTPS URL（localhost 除外）"
                }
                require(url.length <= 2048) { "URL 过长" }

                val client = OkHttpClient.Builder()
                    .connectTimeout(NetworkConfig.SKILL_LOAD_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .readTimeout(NetworkConfig.SKILL_LOAD_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .build()
                val request = Request.Builder().url(url)
                    .header("Accept", "text/markdown, text/plain, */*")
                    .build()
                val response = client.newCall(request).execute()
                require(response.isSuccessful) { "HTTP ${response.code}: ${response.message}" }
                val contentLength = response.header("Content-Length")?.toLongOrNull() ?: 0L
                require(contentLength <= MAX_SKILL_FILE_SIZE || contentLength == 0L) { "文件过大 (${contentLength / 1024}KB > ${MAX_SKILL_FILE_SIZE / 1024}KB)" }

                val content = response.body?.string()
                    ?: throw IllegalArgumentException("响应体为空")
                require(content.length <= MAX_SKILL_FILE_SIZE) { "文件内容过大" }

                val definition = parseSkillMd(content, SkillSourceType.REMOTE_URL, url)
                    ?: throw IllegalArgumentException("无法解析技能文件")

                saveImportedSkill(definition)
                DebugLog.i("$TAG: 从 URL 导入成功 — ${definition.skillName} v${definition.metadata.version}")
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

                val definition = parseSkillMd(content, SkillSourceType.LOCAL_IMPORT, uri.toString())
                    ?: throw IllegalArgumentException("无法解析技能文件")

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
            // 版本比较：同名技能仅在新版本更高时覆盖
            val index = getImportedSkillsIndex().toMutableList()
            val existing = index.find { it.skillName == definition.skillName }
            if (existing != null) {
                val cmp = compareVersions(definition.metadata.version, existing.version)
                if (cmp <= 0) {
                    DebugLog.i("$TAG: 技能 '${definition.skillName}' 当前版本 ${existing.version} >= 新版本 ${definition.metadata.version}，跳过更新")
                    return
                }
                DebugLog.i("$TAG: 技能 '${definition.skillName}' 从 ${existing.version} 升级到 ${definition.metadata.version}")
            }

            // 保存 SKILL.md 原文
            val mdContent = serializeToMd(definition)
            val file = File(importedSkillsDir, "${definition.skillName}.md")
            file.writeText(mdContent, Charsets.UTF_8)

            // 更新索引
            index.removeAll { it.skillName == definition.skillName }
            index.add(ImportedSkillIndexEntry(
                skillName = definition.skillName,
                sourcePath = definition.sourcePath,
                sourceType = definition.sourceType,
                importedAtMs = System.currentTimeMillis(),
                version = definition.metadata.version,
            ))
            saveImportedSkillsIndex(index)

            DebugLog.d("$TAG: 已保存导入技能 '${definition.skillName}' v${definition.metadata.version} 到本地")
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
                parseSkillMd(content, entry.sourceType, entry.sourcePath)
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
    suspend fun deleteSkill(skillName: String): Boolean = withContext(Dispatchers.IO) {
        val allSkills = runCatching {
            loadAllSkills()
        }.getOrNull() ?: return@withContext false

        val target = allSkills.find { it.skillName == skillName } ?: return@withContext false

        if (target.isBuiltIn) {
            DebugLog.w("$TAG: 不能删除内置技能 '$skillName'")
            return@withContext false
        }

        return@withContext try {
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
    suspend fun buildSkillsSystemPrompt(): String = withContext(Dispatchers.IO) {
        val skills = runCatching {
            getEnabledSkills()
        }.getOrNull() ?: return@withContext ""

        if (skills.isEmpty()) return@withContext ""

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

        return@withContext lines.joinToString("\n").trim()
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
        val version: String = "1.0.0",
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

    /**
     * 语义化版本比较。返回正数表示 v1 > v2，负数表示 v1 < v2，0 表示相等。
     */
    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split(".").mapNotNull { it.toIntOrNull() }
        val parts2 = v2.split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(parts1.size, parts2.size, 3)
        for (i in 0 until maxLen) {
            val a = parts1.getOrElse(i) { 0 }
            val b = parts2.getOrElse(i) { 0 }
            if (a != b) return a - b
        }
        return 0
    }

    /**
     * 检查指定技能是否有远程更新可用。
     * @return 更新信息三元组 (当前版本, 远程版本, 是否有更新)，若无法检查返回 null
     */
    suspend fun checkForUpdate(skillName: String): Triple<String, String, Boolean>? =
        withContext(Dispatchers.IO) {
            val index = getImportedSkillsIndex()
            val entry = index.find { it.skillName == skillName } ?: return@withContext null
            if (entry.sourceType != SkillSourceType.REMOTE_URL || entry.sourcePath.isBlank()) {
                return@withContext null
            }
            runCatching {
                val client = OkHttpClient.Builder()
                    .connectTimeout(NetworkConfig.SKILL_UPDATE_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .readTimeout(NetworkConfig.SKILL_UPDATE_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder().url(entry.sourcePath).build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@withContext null
                val content = response.body?.string() ?: return@withContext null
                val remoteDef = parseSkillMd(content, SkillSourceType.REMOTE_URL, entry.sourcePath)
                    ?: return@withContext null
                val remoteVersion = remoteDef.metadata.version
                val hasUpdate = compareVersions(remoteVersion, entry.version) > 0
                Triple(entry.version, remoteVersion, hasUpdate)
            }.getOrNull()
        }

    /**
     * 获取所有可更新的远程技能列表。
     */
    suspend fun checkAllForUpdates(): List<Triple<String, String, String>> =
        withContext(Dispatchers.IO) {
            val index = getImportedSkillsIndex()
            val client = OkHttpClient.Builder()
                .connectTimeout(NetworkConfig.SKILL_UPDATE_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(NetworkConfig.SKILL_UPDATE_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
            index.filter { it.sourceType == SkillSourceType.REMOTE_URL && it.sourcePath.isNotBlank() }
                .mapNotNull { entry ->
                    runCatching {
                        val request = Request.Builder().url(entry.sourcePath).build()
                        val response = client.newCall(request).execute()
                        if (!response.isSuccessful) return@mapNotNull null
                        val content = response.body?.string() ?: return@mapNotNull null
                        val remoteDef = parseSkillMd(content, SkillSourceType.REMOTE_URL, entry.sourcePath)
                            ?: return@mapNotNull null
                        val remoteVersion = remoteDef.metadata.version
                        if (compareVersions(remoteVersion, entry.version) > 0) {
                            Triple(entry.skillName, entry.version, remoteVersion)
                        } else null
                    }.getOrNull()
                }
        }

    /**
     * 获取已导入技能的当前版本。
     */
    fun getImportedSkillVersion(skillName: String): String? {
        return getImportedSkillsIndex().find { it.skillName == skillName }?.version
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
     * 解析 frontmatter 中的列表字段。
     * 支持两种格式：
     * - 内联格式：`triggers: [/hash, 计算哈希, sha256]`
     * - 逗号分隔：`triggers: /hash, 计算哈希, sha256`
     * 每个元素去除首尾空白与成对引号，空元素被丢弃。
     */
    private fun parseFrontmatterList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val cleaned = raw.trim().removeSurrounding("[").removeSurrounding("]")
        return cleaned.split(",")
            .map { it.trim().removeSurrounding("\"").removeSurrounding("'").trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * 解析 SKILL.md 格式的技能定义
     */
    private fun parseSkillMd(content: String, sourceType: SkillSourceType, sourcePath: String): StandardSkillDefinition? {
        return try {
            val frontmatter = mutableMapOf<String, String>()
            val body: String
            val lines = content.lines()

            if (lines.firstOrNull()?.trim() == "---") {
                val endIndex = lines.drop(1).indexOfFirst { it.trim() == "---" }
                if (endIndex >= 0) {
                    lines.drop(1).take(endIndex).forEach { line ->
                        val colonIdx = line.indexOf(':')
                        if (colonIdx > 0) {
                            frontmatter[line.substring(0, colonIdx).trim()] =
                                line.substring(colonIdx + 1).trim().removeSurrounding("\"").removeSurrounding("'")
                        }
                    }
                    body = lines.drop(endIndex + 2).joinToString("\n").trim()
                } else {
                    body = content
                }
            } else {
                body = content
            }

            val name = frontmatter["name"] ?: return null
            val description = frontmatter["description"] ?: ""
            val tags = frontmatter["tags"]?.split(",")?.map { it.trim() } ?: emptyList()
            val category = frontmatter["category"]?.let { catName ->
                SkillCategory.entries.find { it.name.equals(catName, ignoreCase = true) }
            } ?: SkillCategory.GENERAL
            val triggers = parseFrontmatterList(frontmatter["triggers"])
            val platforms = parseFrontmatterList(frontmatter["platforms"]).ifEmpty { listOf("all") }

            StandardSkillDefinition(
                metadata = SkillMetadata(
                    name = name,
                    description = description,
                    version = frontmatter["version"] ?: "1.0.0",
                    author = frontmatter["author"] ?: "",
                    tags = tags,
                    category = category,
                    requireSecret = frontmatter["require-secret"]?.toBooleanStrictOrNull() ?: false,
                    homepage = frontmatter["homepage"] ?: "",
                    triggers = triggers,
                    platforms = platforms,
                ),
                instructions = body,
                sourceType = sourceType,
                sourcePath = sourcePath,
            )
        } catch (e: Exception) {
            DebugLog.w("$TAG: parseSkillMd 失败 — ${e.message}")
            null
        }
    }

    /**
     * 序列化技能定义为 SKILL.md 格式
     */
    private fun serializeToMd(definition: StandardSkillDefinition): String {
        return buildString {
            appendLine("---")
            appendLine("name: ${definition.metadata.name}")
            appendLine("description: ${definition.metadata.description}")
            appendLine("version: ${definition.metadata.version}")
            if (definition.metadata.author.isNotEmpty()) appendLine("author: ${definition.metadata.author}")
            if (definition.metadata.tags.isNotEmpty()) appendLine("tags: ${definition.metadata.tags.joinToString(", ")}")
            appendLine("category: ${definition.metadata.category.name.lowercase()}")
            if (definition.metadata.requireSecret) appendLine("require-secret: true")
            if (definition.metadata.homepage.isNotEmpty()) appendLine("homepage: ${definition.metadata.homepage}")
            if (definition.metadata.triggers.isNotEmpty()) {
                appendLine("triggers: [${definition.metadata.triggers.joinToString(", ")}]")
            }
            if (definition.metadata.platforms.isNotEmpty() && definition.metadata.platforms != listOf("all")) {
                appendLine("platforms: [${definition.metadata.platforms.joinToString(", ")}]")
            }
            appendLine("---")
            appendLine()
            append(definition.instructions)
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
