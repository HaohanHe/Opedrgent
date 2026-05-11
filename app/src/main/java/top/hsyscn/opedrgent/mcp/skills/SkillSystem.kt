package top.hsyscn.opedrgent.mcp.skills

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap

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
)

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

            DebugLog.i("BuiltinSkillLoader: loaded 5 builtin skills")
        }
    }
}
