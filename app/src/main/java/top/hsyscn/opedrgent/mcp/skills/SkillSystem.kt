package top.hsyscn.opedrgent.mcp.skills

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class ChildAbility(
    val name: String,
    val description: String,
    val parameters: Map<String, String> = emptyMap(),
)

@Serializable
data class Skill(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val keywords: List<String>,
    val children: List<ChildAbility> = emptyList(),
)

@Serializable
data class SkillDefinition(
    val id: String,
    val name: String,
    val description: String,
    val version: String = "1.0.0",
    val author: String? = null,
    val tags: List<String> = emptyList(),
    val category: SkillCategory = SkillCategory.GENERAL,
    val inputSchema: JsonObject = buildJsonObject {},
    val promptTemplate: String? = null,
    val requiredTools: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    val body: String = "", // Markdown body content (after YAML frontmatter)
) {
    /** 是否为 pinned（固定）skill，pinned 跳过所有自动转换 */
    fun isPinned(): Boolean = tags.contains("pinned") || metadata["pinned"] == "true"

    /** 获取最后使用时间，默认返回注册时间 */
    fun lastUsedAt(): Long = metadata["lastUsedAt"]?.toLongOrNull() ?: metadata["registeredAt"]?.toLongOrNull() ?: 0L

    /** 返回带更新字段的副本 */
    fun withMetadata(key: String, value: String): SkillDefinition =
        copy(metadata = this.metadata + (key to value))

    /** 返回移除指定 metadata key 的副本 */
    fun withoutMetadata(key: String): SkillDefinition =
        copy(metadata = this.metadata - key)
}

enum class SkillCategory {
    RESEARCH,
    CODING,
    AUTOMATION,
    ANALYSIS,
    COMMUNICATION,
    GENERAL,
}

@Serializable
data class SkillIndex(
    val skills: List<SkillDefinition>,
    val lastUpdated: Long = System.currentTimeMillis(),
)

@Serializable
data class SkillSnapshotEntry(
    val skillId: String,
    val skillName: String,
    val description: String,
    val category: String,
)

@Serializable
data class SkillPromptSnapshot(
    val version: Int = 1,
    val manifest: Map<String, List<Long>> = emptyMap(),
    val skills: List<SkillSnapshotEntry> = emptyList(),
    val categoryDescriptions: Map<String, String> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis(),
)

@Serializable
data class SkillExecutionRequest(
    val skillId: String,
    val inputs: Map<String, String> = emptyMap(),
    val context: String? = null,
)

@Serializable
data class SkillExecutionResult(
    val success: Boolean,
    val output: String,
    val executionTimeMs: Long = 0,
    val usedTools: List<String> = emptyList(),
)

class SkillRegistry {

    private val skills = ConcurrentHashMap<String, SkillDefinition>()
    private val categories = ConcurrentHashMap<SkillCategory, MutableList<SkillDefinition>>()
    private var indexFile: File? = null

    fun setIndexFile(file: File) {
        indexFile = file
        loadIndex()
    }

    fun registerSkill(skill: SkillDefinition) {
        skills[skill.id] = skill

        val categoryList = categories.getOrPut(skill.category) { mutableListOf() }
        synchronized(categoryList) {
            if (!categoryList.any { it.id == skill.id }) {
                categoryList.add(skill)
            }
        }

        DebugLog.i("SkillRegistry: registered skill ${skill.name} (${skill.id})")
        saveIndex()
    }

    fun unregisterSkill(skillId: String) {
        val skill = skills.remove(skillId) ?: return

        val categoryList = categories[skill.category]
        if (categoryList != null) {
            synchronized(categoryList) {
                categoryList.removeAll { it.id == skillId }
            }
        }

        DebugLog.i("SkillRegistry: unregistered skill $skillId")
        saveIndex()
    }

    fun getSkill(skillId: String): SkillDefinition? {
        return skills[skillId]
    }

    fun searchSkills(query: String): List<SkillDefinition> {
        val lowerQuery = query.lowercase()

        return skills.values.filter { skill ->
            skill.name.lowercase().contains(lowerQuery) ||
            skill.description.lowercase().contains(lowerQuery) ||
            skill.tags.any { it.lowercase().contains(lowerQuery) } ||
            skill.id.lowercase().contains(lowerQuery)
        }
    }

    fun listByCategory(category: SkillCategory): List<SkillDefinition> {
        return categories[category]?.toList() ?: emptyList()
    }

    fun listAllSkills(): List<SkillDefinition> {
        return skills.values.toList()
    }

    fun findSkillsForTask(taskDescription: String): List<SkillDefinition> {
        val keywords = extractKeywords(taskDescription)
        
        return skills.values.map { skill ->
            var score = 0
            
            for (keyword in keywords) {
                if (skill.name.lowercase().contains(keyword)) score += 3
                if (skill.description.lowercase().contains(keyword)) score += 2
                if (skill.tags.any { it.lowercase().contains(keyword) }) score += 1
                if (skill.category.name.lowercase().contains(keyword)) score += 1
            }

            Pair(skill, score)
        }
        .filter { it.second > 0 }
        .sortedByDescending { it.second }
        .map { it.first }
    }

    fun getCategories(): Set<SkillCategory> {
        return categories.keys.toSet()
    }

    fun getSkillCount(): Int {
        return skills.size
    }

    fun getAllSkills(): List<SkillDefinition> {
        return skills.values.toList()
    }

    // ── Curator 支持：使用追踪 / 归档 / 恢复 ──

    private val lastUsedMap = ConcurrentHashMap<String, Long>()

    /** 记录 skill 被使用，更新 lastUsedAt */
    fun touchSkill(skillId: String) {
        val now = System.currentTimeMillis()
        lastUsedMap[skillId] = now
        skills[skillId]?.let { skill ->
            skills[skillId] = skill.withMetadata("lastUsedAt", now.toString())
            DebugLog.d("SkillRegistry: touched skill $skillId at $now")
        }
    }

    /** 标记 skill 为已归档（不删除，可恢复） */
    fun archiveSkill(skillId: String) {
        val skill = skills[skillId] ?: return
        val now = System.currentTimeMillis()
        skills[skillId] = skill.withMetadata("archivedAt", now.toString()).withMetadata("state", "archived")
        DebugLog.i("SkillRegistry: archived skill $skillId (recoverable)")
        saveIndex()
    }

    /** 恢复已归档的 skill */
    fun restoreSkill(skillId: String): Boolean {
        val skill = skills[skillId] ?: return false
        if (skill.metadata["state"] != "archived") return false
        skills[skillId] = skill.withMetadata("restoredAt", System.currentTimeMillis().toString())
            .withoutMetadata("archivedAt").withoutMetadata("state")
        DebugLog.i("SkillRegistry: restored skill $skillId")
        saveIndex()
        return true
    }

    /** 标记 skill 为 stale（不活跃） */
    fun markSkillStale(skillId: String) {
        val skill = skills[skillId] ?: return
        skills[skillId] = skill.withMetadata("staleAt", System.currentTimeMillis().toString())
            .withMetadata("state", "stale")
        DebugLog.d("SkillRegistry: marked skill $skillId as stale")
        saveIndex()
    }

    /** 获取所有不活跃（stale 或 archived）的 non-pinned skills */
    fun getStaleSkills(thresholdMs: Long = 30L * 24 * 60 * 60 * 1000): List<SkillDefinition> {
        val now = System.currentTimeMillis()
        return skills.values.filter { skill ->
            !skill.isPinned() && (now - skill.lastUsedAt()) > thresholdMs
        }
    }

    /** 获取所有已归档的 skills */
    fun getArchivedSkills(): List<SkillDefinition> {
        return skills.values.filter { it.metadata["state"] == "archived" }
    }

    /** 设置/取消 pinned 状态 */
    fun setPinned(skillId: String, pinned: Boolean): Boolean {
        val skill = skills[skillId] ?: return false
        skills[skillId] = if (pinned) {
            skill.copy(tags = if ("pinned" in skill.tags) skill.tags else skill.tags + "pinned")
                .withMetadata("pinned", "true")
        } else {
            skill.copy(tags = skill.tags - "pinned").withoutMetadata("pinned")
        }
        saveIndex()
        DebugLog.i("SkillRegistry: skill $skillId pinned=$pinned")
        return true
    }

    private fun extractKeywords(text: String): List<String> {
        val stopWords = setOf("the", "a", "an", "is", "are", "was", "were", "be", "been",
            "being", "have", "has", "had", "do", "does", "did", "will", "would", "could",
            "should", "may", "might", "shall", "can", "need", "dare", "ought", "used",
            "to", "of", "in", "for", "on", "with", "at", "by", "from", "as", "into",
            "through", "during", "before", "after", "above", "below", "between", "out",
            "off", "over", "under", "again", "further", "then", "once", "here", "there",
            "when", "where", "why", "how", "all", "each", "few", "more", "most", "other",
            "some", "such", "no", "nor", "not", "only", "own", "same", "so", "than",
            "too", "very", "just", "because", "but", "and", "or", "if", "while", "this",
            "that", "these", "those", "it", "its")

        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 && !stopWords.contains(it) }
            .distinct()
    }

    private fun saveIndex() {
        val file = indexFile ?: return

        try {
            val index = SkillIndex(
                skills = skills.values.toList(),
                lastUpdated = System.currentTimeMillis(),
            )

            val json = kotlinx.serialization.json.Json {
                encodeDefaults = true
                prettyPrint = true
            }.encodeToString(SkillIndex.serializer(), index)

            file.writeText(json)
            
            DebugLog.i("SkillRegistry: saved index with ${skills.size} skills")
        } catch (e: Exception) {
            DebugLog.e("SkillRegistry.saveIndex: ${e.message}")
        }
    }

    private fun loadIndex() {
        val file = indexFile ?: return

        if (!file.exists()) {
            DebugLog.i("SkillRegistry: no existing index file")
            return
        }

        try {
            val json = file.readText()
            val index = kotlinx.serialization.json.Json {
                ignoreUnknownKeys = true
            }.decodeFromString(SkillIndex.serializer(), json)

            for (skill in index.skills) {
                skills[skill.id] = skill
                
                val categoryList = categories.getOrPut(skill.category) { mutableListOf() }
                synchronized(categoryList) {
                    if (!categoryList.any { it.id == skill.id }) {
                        categoryList.add(skill)
                    }
                }
            }

            DebugLog.i("SkillRegistry: loaded ${skills.size} skills from index")
        } catch (e: Exception) {
            DebugLog.e("SkillRegistry.loadIndex: ${e.message}")
        }
    }

    companion object {
        private var instance: SkillRegistry? = null

        fun getInstance(): SkillRegistry {
            if (instance == null) {
                instance = SkillRegistry()
            }
            return instance!!
        }

        fun createGlobal(indexFile: File? = null): SkillRegistry {
            val registry = SkillRegistry()
            indexFile?.let { registry.setIndexFile(it) }
            instance = registry
            return registry
        }
    }
}

class BuiltinSkillLoader {

    companion object {
        private const val ASSETS_SKILLS_DIR = "skills"
        private val json = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun loadBuiltinSkills(registry: SkillRegistry) {
            registry.registerSkill(SkillDefinition(
                id = "web_research",
                name = "Web Research",
                description = "Deep web research and information gathering from multiple sources",
                category = SkillCategory.RESEARCH,
                tags = listOf("research", "web", "search", "information"),
                promptTemplate = """You are a research assistant. Use the following tools to gather comprehensive information about the user's query.
                    
Task: {{task}}

Requirements:
- Search multiple sources
- Cross-reference information
- Provide citations where possible
- Summarize key findings""",
                requiredTools = listOf("web_search", "fetch_url"),
            ))

            registry.registerSkill(SkillDefinition(
                id = "code_analysis",
                name = "Code Analysis",
                description = "Analyze code structure, patterns, and potential improvements",
                category = SkillCategory.CODING,
                tags = listOf("code", "analysis", "programming", "review"),
                promptTemplate = """You are a code analysis expert. Analyze the provided code for:
- Code quality and best practices
- Potential bugs or issues
- Performance optimizations
- Security concerns
- Suggested improvements

Code:
{{code}}""",
                requiredTools = listOf("read_file"),
            ))

            registry.registerSkill(SkillDefinition(
                id = "document_summary",
                name = "Document Summary",
                description = "Create comprehensive summaries of documents or articles",
                category = SkillCategory.ANALYSIS,
                tags = listOf("summary", "document", "analysis"),
                promptTemplate = """Create a detailed summary of the following document:

{{content}}

Include:
- Main points and arguments
- Key evidence or data
- Conclusions and implications
- Relevant context""",
                requiredTools = listOf("fetch_url"),
            ))

            registry.registerSkill(SkillDefinition(
                id = "task_automation",
                name = "Task Automation",
                description = "Automate repetitive tasks using available tools",
                category = SkillCategory.AUTOMATION,
                tags = listOf("automation", "task", "workflow"),
                promptTemplate = """You are a task automation assistant. Break down the user's request into executable steps and use the appropriate tools to complete each step.

Task: {{task}}

Execute steps systematically and report progress.""",
                requiredTools = listOf("shell_command", "write_file"),
            ))

            registry.registerSkill(SkillDefinition(
                id = "comparison_analysis",
                name = "Comparison Analysis",
                description = "Compare multiple items, options, or approaches",
                category = SkillCategory.ANALYSIS,
                tags = listOf("compare", "analysis", "decision"),
                promptTemplate = """Provide a thorough comparison of the following items:

{{items_to_compare}}

Structure your analysis with:
- Overview of each option
- Pros and cons comparison
- Key differences
- Recommendations based on use cases""",
                requiredTools = listOf("web_search"),
            ))

            registry.registerSkill(SkillDefinition(
                id = "mimo_tts",
                name = "MiMo TTS",
                description = "使用MiMo V2.5引擎进行高质量语音合成",
                category = SkillCategory.GENERAL,
                tags = listOf("tts", "语音", "朗读", "配音", "唱歌", "念出"),
                metadata = mapOf(
                    "children" to """[{"name":"synthesize","description":"基础语音合成，支持8个预置音色和风格控制"},{"name":"voicedesign","description":"音色设计：通过文本描述生成自定义音色"},{"name":"voiceclone","description":"音色克隆：通过音频样本复刻声音"}]"""
                ),
            ))

            registry.registerSkill(SkillDefinition(
                id = "insight_sprout",
                name = "知识发芽",
                description = "对输入文本进行深度多维度分析，通过四阶段过程发芽衍生出结构化的洞察报告：种子提取、跨领域关联、Aha洞察生成、金句回响",
                category = SkillCategory.ANALYSIS,
                tags = listOf("发芽", "生发", "深化", "联想", "insight", "sprout", "分析", "洞察"),
                promptTemplate = """你是一位深度思考助手。对用户分享的文本进行知识发芽分析，通过以下四阶段过程生成结构化洞察：

## 任务

{{task}}

## 四阶段分析过程

### 1. 种子提取
从文本中提取：
- 核心概念或主张
- 情感倾向
- 潜在主题

### 2. 跨领域关联
将种子映射到：
- 历史事件或人物
- 哲学思想
- 科学发现
- 艺术作品
- 其他意想不到的领域

### 3. Aha洞察
生成反直觉的、有启发性的洞察：
- 从新角度看待原观点
- 发现隐藏的联系
- 提出有趣的假设

### 4. 金句回响
将洞察与经典名言或著作建立桥梁：
- 引用相关的经典语句
- 连接到著名的思想
- 升华洞察的深度

## 输出格式

用结构化的 Markdown 呈现发芽结果，确保语言自然、有深度且易于理解。""",
            ))

            registry.registerSkill(SkillDefinition(
                id = "insight_review",
                name = "亮点识别",
                description = "识别文本中的闪光点和高光时刻，找到做得好的地方并给予正向强化",
                category = SkillCategory.ANALYSIS,
                tags = listOf("点评", "亮点", "正向", "识别", "review", "highlight"),
                promptTemplate = """你是一位善于发现亮点的分析师。对用户分享的文本进行深入分析，识别其中的闪光点和高光时刻：

## 任务

{{task}}

## 分析维度

### 1. 亮点提取
识别文本中：
- 原创性强的观点
- 表达精准的金句
- 逻辑严密的论述
- 富有感染力的语言
- 新颖独特的视角

### 2. 模式识别
如果有上下文或历史记录，识别：
- 重复性的闪光点
- 逐步成长的轨迹
- 跨场景的一致性

### 3. 正向强化
以鼓励和肯定的语气总结亮点：
- 具体指出哪里好
- 为什么好
- 如何发扬光大

## 输出格式

用自然、鼓励的语言呈现分析结果，避免生硬罗列，让用户感受到真诚的肯定。""",
            ))

            registry.registerSkill(SkillDefinition(
                id = "text_refine",
                name = "内容润色",
                description = "把口语化表达打磨成流畅的成品内容，保留用户的真实声音",
                category = SkillCategory.COMMUNICATION,
                tags = listOf("润色", "打磨", "优化", "表达", "refine", "polish"),
                promptTemplate = """你是一位专业的内容编辑。对用户分享的文本进行润色和优化，把口语化表达打磨成流畅的成品内容：

## 任务

{{task}}

## 润色流程

### 1. 去口语化
- 删除口头禅（那个、嗯、就是、其实）
- 补充省略的主语谓语
- 修正语法问题
- 保留核心意思

### 2. 结构优化
- 提炼核心论点
- 重排段落逻辑
- 添加自然的过渡
- 突出重点信息

### 3. 风格适配
根据内容性质选择合适的风格：
- 清晰简洁（适合报告/纪要）
- 生动有力（适合演讲/公众号）
- 亲切自然（适合朋友圈/小红书）
- 专业严谨（适合学术/工作）

## 核心原则

⚠️ 重要：保留"用户的声音"，不是用AI腔替代，而是优化表达！

## 输出格式

同时呈现：
1. 润色后的最终版本
2. 简要说明改动了什么，为什么这么改""",
            ))

            registry.registerSkill(SkillDefinition(
                id = "critical_inquiry",
                name = "深度追问",
                description = "以苏格拉底式提问挑战观点，帮助用户把想法想透，发现逻辑漏洞",
                category = SkillCategory.ANALYSIS,
                tags = listOf("拷问", "追问", "质疑", "挑战", "苏格拉底", "inquiry", "critical"),
                promptTemplate = """你是一位严谨的思想挑战师。对用户分享的观点进行苏格拉底式追问，帮助用户把想法想透：

## 任务

{{task}}

## 追问维度

### 1. 事实核查
- 这个数据从哪来？
- 有什么证据支持？
- 前提假设是什么？

### 2. 假设挑战
- 如果反过来会怎样？
- 这个观点在什么情况下不成立？
- 有没有遗漏的可能性？

### 3. 边界探索
- 适用范围是什么？
- 有什么限制条件？
- 推广到更大范围如何？

### 4. 逻辑检验
- 论证链条完整吗？
- 有没有逻辑跳跃？
- 有没有概念偷换？

## 追问原则

⚠️ 不是批评，是建设性挑战！
- 问题要精准，不模糊
- 层层递进，逐步深入
- 引导用户自己思考，不是直接给答案

## 输出格式

生成3-5个递进式的追问，每个追问单独成段，语言温和但有深度。""",
            ))

            registry.registerSkill(SkillDefinition(
                id = "multi_agent_collaboration",
                name = "多智能体协作",
                description = "动态组建AI专家团队协作完成复杂任务，让LLM决定派什么专家",
                category = SkillCategory.GENERAL,
                tags = listOf("协作", "团队", "多智能体", "agent", "collaboration", "team"),
                promptTemplate = """你是一位项目协调员。面对复杂任务，你需要：

## 任务

{{task}}

## 你的职责

### 1. 任务分析
- 分析任务需要哪些专业能力
- 确定是否需要多专家协作
- 评估任务复杂度

### 2. 专家调度（动态决定）
根据任务需要，你可以组建以下类型的专家团队（你可以自定义专家角色，不限于以下类型）：
- **思考者**：负责深度分析、跨领域关联、创意发散
- **编辑者**：负责内容结构、逻辑优化、表达润色
- **验证者**：负责事实核查、逻辑检验、挑战假设
- **创作者**：负责成品生成、风格适配、平台优化
- **总结者**：负责整合结果、提炼精华、最终呈现

### 3. 协作流程
你需要：
1. 先说明需要哪些专家，为什么
2. 安排专家工作顺序
3. 整合各专家成果
4. 输出最终结果

## 核心原则

- **灵活调度**：根据任务需要决定派几个专家、派什么专家
- **无缝协作**：专家之间互相参考彼此的成果
- **用户至上**：最终输出要符合用户需求

## 输出格式

先说明专家团队组建方案，然后呈现协作结果。""",
            ))

            DebugLog.i("BuiltinSkillLoader: loaded 10 builtin skills")
        }

        /**
         * 从 Android assets/skills/{skillId}.json 加载单个 SkillDefinition
         * @param context Android Context
         * @param skillId 技能 ID（不含 .json 后缀）
         * @return 解析成功返回 SkillDefinition，失败返回 null
         */
        fun loadSkillFromAssets(context: Context, skillId: String): SkillDefinition? {
            val fileName = "$skillId.json"
            return try {
                context.assets.open("$ASSETS_SKILLS_DIR/$fileName").use { inputStream ->
                    val jsonStr = inputStream.bufferedReader(Charsets.UTF_8).readText()
                    json.decodeFromString<SkillDefinition>(jsonStr).also {
                        DebugLog.i("BuiltinSkillLoader: loaded skill '$skillId' from assets")
                    }
                }
            } catch (e: Exception) {
                DebugLog.w("BuiltinSkillLoader: failed to load skill '$skillId' from assets: ${e.message}")
                null
            }
        }

        /**
         * 从 Android assets/skills/ 目录加载所有 .json 技能文件
         * @param context Android Context
         * @return 成功解析的 SkillDefinition 列表
         */
        fun loadAllSkillsFromAssets(context: Context): List<SkillDefinition> {
            return try {
                val skillFiles = context.assets.list(ASSETS_SKILLS_DIR)
                    ?.filter { it.endsWith(".json") }
                    ?: emptyList()

                skillFiles.mapNotNull { fileName ->
                    val skillId = fileName.removeSuffix(".json")
                    loadSkillFromAssets(context, skillId)
                }.also {
                    DebugLog.i("BuiltinSkillLoader: loaded ${it.size} skills from assets")
                }
            } catch (e: Exception) {
                DebugLog.w("BuiltinSkillLoader: failed to load skills from assets: ${e.message}")
                emptyList()
            }
        }
    }
}

object SkillPromptCache {

    private val lruCache = linkedMapOf<String, CachedSkillPrompt>()
    private const val LRU_MAX_SIZE = 8
    private const val SNAPSHOT_VERSION = 1
    private var snapshotFile: File? = null

    data class CachedSkillPrompt(
        val prompt: String,
        val computedAt: Long,
        val skillHash: String,
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() - computedAt > 10 * 60 * 1000L
    }

    fun initialize(cacheDir: File) {
        snapshotFile = File(cacheDir, "skills_prompt_snapshot.json")
        DebugLog.d("SkillPromptCache: initialized with snapshot at ${snapshotFile?.absolutePath}")
    }

    fun buildSkillsPrompt(
        registry: SkillRegistry,
        availableTools: Set<String>? = null,
    ): String {
        val cacheKey = buildCacheKey(availableTools)

        synchronized(lruCache) {
            lruCache[cacheKey]?.let { cached ->
                if (!cached.isExpired()) {
                    lruCache.remove(cacheKey)
                    lruCache[cacheKey] = cached
                    return cached.prompt
                }
                lruCache.remove(cacheKey)
            }
        }

        val snapshot = loadSnapshot()
        if (snapshot != null && isValidSnapshot(snapshot)) {
            val prompt = buildFromSnapshot(snapshot)
            putLru(cacheKey, prompt, computeSnapshotHash(snapshot))
            return prompt
        }

        val skills = registry.getAllSkills()
        val prompt = formatSkillsIndex(skills)

        writeSnapshotAsync(skills)

        putLru(cacheKey, prompt, computeSkillsHash(skills))

        return prompt
    }

    private fun buildCacheKey(availableTools: Set<String>?): String {
        return (availableTools?.sorted()?.joinToString(",") ?: "all")
    }

    private fun putLru(key: String, prompt: String, hash: String) {
        synchronized(lruCache) {
            if (lruCache.size >= LRU_MAX_SIZE) {
                val oldest = lruCache.keys.first()
                lruCache.remove(oldest)
            }
            lruCache[key] = CachedSkillPrompt(prompt, System.currentTimeMillis(), hash)
        }
    }

    private fun moveToEnd(key: String) {
        synchronized(lruCache) {
            val entry = lruCache.remove(key) ?: return
            lruCache[key] = entry
        }
    }

    private fun loadSnapshot(): SkillPromptSnapshot? {
        val file = snapshotFile ?: return null
        if (!file.exists()) return null

        try {
            val json = file.readText(Charsets.UTF_8)
            val snapshot = kotlinx.serialization.json.Json {
                ignoreUnknownKeys = true
            }.decodeFromString(SkillPromptSnapshot.serializer(), json)

            if (snapshot.version != SNAPSHOT_VERSION) return null

            return snapshot
        } catch (e: Exception) {
            DebugLog.w("SkillPromptCache: failed to load snapshot: ${e.message}")
            return null
        }
    }

    private fun isValidSnapshot(snapshot: SkillPromptSnapshot): Boolean {
        return snapshot.skills.isNotEmpty() && 
               (System.currentTimeMillis() - snapshot.timestamp < 24 * 60 * 60 * 1000L)
    }

    private fun buildFromSnapshot(snapshot: SkillPromptSnapshot): String {
        val lines = mutableListOf<String>()

        lines.add("## Skills (mandatory)")
        lines.add("")
        lines.add("Before replying, scan the skills below. If a skill matches or is even partially relevant to your task, you MUST use it.")
        lines.add("")

        val groupedByCategory: Map<String, List<SkillSnapshotEntry>> = snapshot.skills.groupBy { it.category }

        for ((category, entries) in groupedByCategory.toList().sortedBy { it.first }) {
            val desc = snapshot.categoryDescriptions[category]
            if (desc != null) {
                lines.add("### $category: $desc")
            } else {
                lines.add("### $category:")
            }

            for (entry in entries.sortedBy { it.skillName }) {
                lines.add("- **${entry.skillName}**: ${entry.description}")
            }
            lines.add("")
        }

        return lines.joinToString("\n").trim()
    }

    private fun formatSkillsIndex(skills: List<SkillDefinition>): String {
        if (skills.isEmpty()) return ""

        val lines = mutableListOf<String>()

        lines.add("## Skills (mandatory)")
        lines.add("")
        lines.add("Before replying, scan the skills below. If a skill matches or is even partially relevant to your task, you MUST use it.")
        lines.add("")

        val groupedByCategory: Map<String, List<SkillDefinition>> = skills.groupBy { it.category.name }

        for ((category, entries) in groupedByCategory.toList().sortedBy { it.first }) {
            lines.add("### $category:")

            for (skill in entries.sortedBy { it.name }) {
                lines.add("- **${skill.name}**: ${skill.description}")
            }
            lines.add("")
        }

        return lines.joinToString("\n").trim()
    }

    private fun writeSnapshotAsync(skills: List<SkillDefinition>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val file = snapshotFile ?: return@launch

                val manifest = mutableMapOf<String, List<Long>>()
                val snapshotEntries = skills.map { skill ->
                    SkillSnapshotEntry(
                        skillId = skill.id,
                        skillName = skill.name,
                        description = skill.description,
                        category = skill.category.name,
                    )
                }

                val categoryDescriptions = skills
                    .groupBy { it.category.name }
                    .mapValues { "${it.value.size} skills" }

                val snapshot = SkillPromptSnapshot(
                    version = SNAPSHOT_VERSION,
                    manifest = manifest,
                    skills = snapshotEntries,
                    categoryDescriptions = categoryDescriptions,
                    timestamp = System.currentTimeMillis(),
                )

                val json = kotlinx.serialization.json.Json {
                    encodeDefaults = true
                    prettyPrint = true
                }.encodeToString(SkillPromptSnapshot.serializer(), snapshot)

                file.writeText(json, Charsets.UTF_8)

                DebugLog.d("SkillPromptCache: snapshot written with ${skills.size} skills")
            } catch (e: Exception) {
                DebugLog.w("SkillPromptCache: failed to write snapshot: ${e.message}")
            }
        }
    }

    private fun computeSkillsHash(skills: List<SkillDefinition>): String {
        return skills.joinToString("|") { "${it.id}:${it.version}" }.hashCode().toString()
    }

    private fun computeSnapshotHash(snapshot: SkillPromptSnapshot): String {
        return snapshot.skills.joinToString("|") { "${it.skillId}" }.hashCode().toString()
    }

    fun clearCache() {
        synchronized(lruCache) {
            lruCache.clear()
        }
        snapshotFile?.let {
            if (it.exists()) it.delete()
        }
        DebugLog.d("SkillPromptCache: cache cleared")
    }

    fun getStats(): Map<String, Int> = mapOf(
        "lru_cache_size" to synchronized(lruCache) { lruCache.size },
        "snapshot_exists" to (snapshotFile?.exists() == true).let { if (it) 1 else 0 },
    )
}
