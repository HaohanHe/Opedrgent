package top.hsyscn.opedrgent.mcp.skills

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import top.hsyscn.opedrgent.utils.DebugLog
import java.util.concurrent.TimeUnit

/**
 * SKILL.md 格式解析器 — 对标 Google Gallery 的 Skill 定义格式
 *
 * 支持的格式：
 * ```
 * ---
 * name: skill-name              # 必须，kebab-case
 * description: 一句话描述        # 必须
 * version: 1.0.0               # 可选，默认 "1.0.0"
 * author: 作者名                # 可选
 * homepage: https://...         # 可选
 * require-secret: false         # 可选，默认 false
 * require-secret-description:   # 可选
 * tags: [tag1, tag2]            # 可选
 * category: general             # 可选，默认 GENERAL
 * ---
 *
 * # Skill 标题
 *
 * ## Instructions
 * 具体指令内容...
 * ```
 */
object SkillParser {

    private const val TAG = "SkillParser"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * 从 SKILL.md 内容解析出 StandardSkillDefinition
     *
     * @param content SKILL.md 文件的完整文本内容
     * @param sourceType 来源类型
     * @param sourcePath 来源路径（本地文件路径或远程 URL）
     * @return 解析成功返回 Result.success，失败返回 Result.failure（含错误信息）
     */
    fun parseSkillMd(
        content: String,
        sourceType: SkillSourceType,
        sourcePath: String = "",
    ): Result<StandardSkillDefinition> {
        return runCatching {
            val trimmedContent = content.trim()
            if (trimmedContent.isEmpty()) {
                throw IllegalArgumentException("SKILL.md 内容为空")
            }

            // 使用 FrontmatterParser 解析 frontmatter 和 body
            val parsed = FrontmatterParser.parse(trimmedContent)

            // ── 提取必须字段 ──
            val name = parsed.metadata["name"]?.trim()
                ?: throw IllegalArgumentException("缺少必填字段：name")

            val description = parsed.metadata["description"]?.trim()
                ?: throw IllegalArgumentException("缺少必填字段：description")

            // ── 提取可选字段 ──
            val version = parsed.metadata["version"]?.trim() ?: "1.0.0"
            val author = parsed.metadata["author"]?.trim() ?: ""
            val homepage = parsed.metadata["homepage"]?.trim() ?: ""
            val requireSecret = parsed.metadata["require-secret"]
                ?.trim()?.equals("true", ignoreCase = true) ?: false
            val requireSecretDescription = parsed.metadata["require-secret-description"]?.trim() ?: ""

            // 解析 tags（支持逗号分隔或 YAML 数组格式）
            val rawTags = parsed.metadata["tags"]?.trim() ?: ""
            val tags = parseTags(rawTags)

            // 解析 category
            val rawCategory = parsed.metadata["category"]?.trim()?.uppercase() ?: "GENERAL"
            val category = parseCategory(rawCategory)

            val metadata = SkillMetadata(
                name = name,
                description = description,
                version = version,
                author = author,
                homepage = homepage,
                requireSecret = requireSecret,
                requireSecretDescription = requireSecretDescription,
                tags = tags,
                category = category,
            )

            val isBuiltIn = sourceType == SkillSourceType.BUILTIN

            StandardSkillDefinition(
                metadata = metadata,
                instructions = parsed.body,
                sourceType = sourceType,
                sourcePath = sourcePath,
                isBuiltIn = isBuiltIn,
            )
        }.onFailure { e ->
            DebugLog.w("$TAG: 解析失败 — ${e.message}")
        }
    }

    /**
     * 将 StandardSkillDefinition 序列化回 SKILL.md 格式
     */
    fun serializeToMd(definition: StandardSkillDefinition): String {
        val meta = definition.metadata
        val frontmatterLines = buildList {
            add("name: ${meta.name}")
            add("description: ${meta.description}")
            if (meta.version != "1.0.0") add("version: ${meta.version}")
            if (meta.author.isNotEmpty()) add("author: ${meta.author}")
            if (meta.homepage.isNotEmpty()) add("homepage: ${meta.homepage}")
            if (meta.requireSecret) {
                add("require-secret: true")
                if (meta.requireSecretDescription.isNotEmpty()) {
                    add("require-secret-description: ${meta.requireSecretDescription}")
                }
            }
            if (meta.tags.isNotEmpty()) {
                add("tags: [${meta.tags.joinToString(", ")}]")
            }
            if (meta.category != SkillCategory.GENERAL) {
                add("category: ${meta.category.name.lowercase()}")
            }
        }

        return buildString {
            append("---\n")
            append(frontmatterLines.joinToString("\n"))
            append("\n---\n\n")
            append(definition.instructions.trim())
            append("\n")
        }
    }

    /**
     * 从远程 URL 加载 SKILL.md 并解析
     *
     * @param url SKILL.md 的远程 URL（支持 GitHub raw、Gist 等）
     * @return 解析结果
     */
    suspend fun loadFromUrl(url: String): Result<StandardSkillDefinition> = withContext(Dispatchers.IO) {
        runCatching {
            DebugLog.i("$TAG: 正在从 URL 加载技能 — $url")

            val request = Request.Builder()
                .url(url)
                .header("Accept", "text/markdown, text/plain, */*")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                throw IllegalArgumentException(
                    "HTTP 请求失败：${response.code} ${response.message}"
                )
            }

            val body = response.body?.string()
                ?: throw IllegalArgumentException("响应体为空")

            parseSkillMd(body, SkillSourceType.REMOTE_URL, url).getOrThrow()
        }.onFailure { e ->
            DebugLog.e("$TAG: 从 URL 加载失败 ($url) — ${e.message}")
        }
    }

    /**
     * 验证 Skill 定义是否合法
     *
     * @return 错误列表，空列表表示验证通过
     */
    fun validate(definition: StandardSkillDefinition): List<String> {
        val errors = mutableListOf<String>()
        val meta = definition.metadata

        // name 必须是 kebab-case
        if (!meta.name.matches(Regex("^[a-z0-9]+(-[a-z0-9]+)*$"))) {
            errors.add("name 必须为 kebab-case 格式（小写字母、数字、连字符），当前值：'${meta.name}'")
        }

        // description 不能为空
        if (meta.description.isBlank()) {
            errors.add("description 不能为空")
        }

        // instructions 不能为空
        if (definition.instructions.isBlank()) {
            errors.add("instructions（指令内容）不能为空")
        }

        // 如果需要 secret 但没有描述
        if (meta.requireSecret && meta.requireSecretDescription.isBlank()) {
            errors.add("requireSecret=true 时必须提供 requireSecretDescription")
        }

        // version 格式检查
        if (!meta.version.matches(Regex("^\\d+(\\.\\d+){0,2}(-[a-zA-Z0-9.]+)?$"))) {
            errors.add("version 格式不合法，应为语义化版本号（如 1.0.0），当前值：'${meta.version}'")
        }

        return errors
    }

    // ── 内部辅助方法 ──

    /**
     * 解析标签字符串
     * 支持格式：
     * - YAML 数组：[tag1, tag2, tag3]
     * - 逗号分隔：tag1, tag2, tag3
     */
    private fun parseTags(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()

        return when {
            raw.startsWith("[") && raw.endsWith("]") -> {
                raw.removeSurrounding("[", "]")
                    .split(",")
                    .map { it.trim().removeSurrounding("\"", "'") }
                    .filter { it.isNotEmpty() }
            }
            else -> {
                raw.split(",")
                    .map { it.trim().removeSurrounding("\"", "'") }
                    .filter { it.isNotEmpty() }
            }
        }
    }

    /**
     * 解析分类字符串
     */
    private fun parseCategory(raw: String): SkillCategory {
        return try {
            SkillCategory.valueOf(raw)
        } catch (e: IllegalArgumentException) {
            DebugLog.w("$TAG: 未知的分类 '$raw'，使用默认值 GENERAL")
            SkillCategory.GENERAL
        }
    }
}
