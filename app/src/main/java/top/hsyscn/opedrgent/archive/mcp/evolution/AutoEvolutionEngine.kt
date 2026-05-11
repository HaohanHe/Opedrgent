package top.hsyscn.opedrgent.mcp.evolution

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class ConversationRecord(
    val id: String,
    val timestamp: Long = System.currentTimeMillis(),
    val userQuery: String,
    val systemPrompt: String,
    val toolsUsed: List<String> = emptyList(),
    val responseQuality: Double = 0.0,
    val executionTimeMs: Long = 0L,
    val tokensUsed: Int = 0,
    val success: Boolean = true,
    val feedback: String? = null,
    val tags: List<String> = emptyList(),
    val category: String = "general",
)

@Serializable
data class PatternInsight(
    val id: String,
    val pattern: String,
    val frequency: Int = 1,
    val avgSuccessRate: Double = 0.0,
    val avgExecutionTime: Long = 0L,
    val recommendedTools: List<String> = emptyList(),
    val recommendedPromptTemplate: String? = null,
    val lastSeen: Long = System.currentTimeMillis(),
    val confidence: Double = 0.0,
)

@Serializable
data class EvolutionConfig(
    val enabled: Boolean = true,
    val minConversationsForPattern: Int = 5,
    val patternConfidenceThreshold: Double = 0.7,
    val autoOptimizePrompts: Boolean = true,
    val maxPatternsTracked: Int = 1000,
    val learningRate: Double = 0.1,
    val enableFeedbackLearning: Boolean = true,
)

class AutoEvolutionEngine(
    private val config: EvolutionConfig = EvolutionConfig(),
    private val storageDir: File? = null,
) {

    private val conversations = ConcurrentHashMap<String, ConversationRecord>()
    private val patterns = ConcurrentHashMap<String, PatternInsight>()
    private var isInitialized = false

    init {
        if (storageDir != null) {
            loadState()
            isInitialized = true
        }
    }

    fun recordConversation(record: ConversationRecord) {
        if (!config.enabled) return

        conversations[record.id] = record
        
        extractAndLearnPattern(record)
        
        DebugLog.i("AutoEvolutionEngine: recorded conversation ${record.id} (total: ${conversations.size})")
        
        if (storageDir != null) {
            saveConversationAsync(record)
        }
    }

    fun analyzePerformance(): EvolutionReport {
        if (conversations.isEmpty()) {
            return EvolutionReport.empty()
        }

        val total = conversations.size.toDouble()
        val successful = conversations.values.count { it.success }.toDouble()
        
        val byCategory = conversations.values
            .groupBy { it.category }
            .mapValues { (_, records) ->
                CategoryStats(
                    count = records.size,
                    successRate = records.count { it.success }.toDouble() / records.size.toDouble(),
                    avgExecutionTime = records.map { it.executionTimeMs }.average().toLong(),
                    avgTokensUsed = records.map { it.tokensUsed }.average().toInt(),
                )
            }

        val toolUsage = mutableMapOf<String, Int>()
        conversations.values.flatMap { it.toolsUsed }.forEach { tool ->
            toolUsage[tool] = toolUsage.getOrDefault(tool, 0) + 1
        }

        val topPatterns = patterns.values
            .sortedByDescending { it.confidence }
            .take(10)

        return EvolutionReport(
            totalConversations = conversations.size,
            overallSuccessRate = successful / total,
            avgExecutionTime = conversations.values.map { it.executionTimeMs }.average().toLong(),
            avgTokensUsed = conversations.values.map { it.tokensUsed }.average().toInt(),
            categoryBreakdown = byCategory,
            topToolsUsed = toolUsage.toList()
                .sortedByDescending { it.second }
                .take(10),
            discoveredPatterns = topPatterns,
            timestamp = System.currentTimeMillis(),
        )
    }

    fun suggestOptimization(query: String): OptimizationSuggestion? {
        if (!config.enabled || patterns.isEmpty()) return null

        val queryLower = query.lowercase()
        val words = queryLower.split(Regex("\\s+")).filter { it.length > 2 }

        val matchedPatterns = patterns.values.filter { pattern ->
            val patternWords = pattern.pattern.lowercase().split(Regex("\\s+"))
            words.any { word -> patternWords.any { pw -> pw.contains(word) || word.contains(pw) } } ||
            pattern.pattern.lowercase().contains(queryLower) ||
            queryLower.contains(pattern.pattern.lowercase())
        }.sortedByDescending { it.confidence }

        val bestMatch = matchedPatterns.firstOrNull() ?: return null

        if (bestMatch.confidence < config.patternConfidenceThreshold) return null

        return OptimizationSuggestion(
            patternId = bestMatch.id,
            confidence = bestMatch.confidence,
            recommendedTools = bestMatch.recommendedTools,
            suggestedPromptTemplate = bestMatch.recommendedPromptTemplate,
            expectedSuccessRate = bestMatch.avgSuccessRate,
            basedOnSamples = bestMatch.frequency,
            reasoning = "Based on ${bestMatch.frequency} similar conversations with ${"%.1f".format(bestMatch.avgSuccessRate * 100)}% success rate",
        )
    }

    fun learnFromFeedback(conversationId: String, quality: Double, feedback: String? = null) {
        if (!config.enableFeedbackLearning) return

        val conversation = conversations[conversationId] ?: return

        val updated = conversation.copy(
            responseQuality = quality,
            feedback = feedback,
        )

        conversations[conversationId] = updated

        updatePatternFromFeedback(updated)

        DebugLog.i("AutoEvolutionEngine: learned from feedback for $conversationId (quality: $quality)")
    }

    fun getTopPatterns(limit: Int = 20): List<PatternInsight> {
        return patterns.values
            .sortedByDescending { it.confidence }
            .take(limit)
    }

    fun getStats(): EngineStats {
        return EngineStats(
            conversationsAnalyzed = conversations.size,
            patternsDiscovered = patterns.size,
            avgSuccessRate = if (conversations.isNotEmpty()) 
                conversations.values.count { it.success }.toDouble() / conversations.size else 0.0,
            mostCommonCategory = conversations.values
                .groupBy { it.category }
                .maxByOrNull { it.value.size }?.key ?: "none",
            lastAnalysisTime = System.currentTimeMillis(),
        )
    }

    @Serializable
    data class EvolutionReport(
        val totalConversations: Int,
        val overallSuccessRate: Double,
        val avgExecutionTime: Long,
        val avgTokensUsed: Int,
        val categoryBreakdown: Map<String, CategoryStats>,
        val topToolsUsed: List<Pair<String, Int>>,
        val discoveredPatterns: List<PatternInsight>,
        val timestamp: Long,
    ) {
        companion object {
            fun empty() = EvolutionReport(
                totalConversations = 0,
                overallSuccessRate = 0.0,
                avgExecutionTime = 0L,
                avgTokensUsed = 0,
                categoryBreakdown = emptyMap(),
                topToolsUsed = emptyList(),
                discoveredPatterns = emptyList(),
                timestamp = System.currentTimeMillis(),
            )
        }
    }

    @Serializable
    data class CategoryStats(
        val count: Int,
        val successRate: Double,
        val avgExecutionTime: Long,
        val avgTokensUsed: Int,
    )

    @Serializable
    data class OptimizationSuggestion(
        val patternId: String,
        val confidence: Double,
        val recommendedTools: List<String>,
        val suggestedPromptTemplate: String?,
        val expectedSuccessRate: Double,
        val basedOnSamples: Int,
        val reasoning: String,
    )

    @Serializable
    data class EngineStats(
        val conversationsAnalyzed: Int,
        val patternsDiscovered: Int,
        val avgSuccessRate: Double,
        val mostCommonCategory: String,
        val lastAnalysisTime: Long,
    )

    private fun extractAndLearnPattern(record: ConversationRecord) {
        val patternKey = generatePatternKey(record.userQuery)

        val existing = patterns[patternKey]

        if (existing != null) {
            val updatedFreq = existing.frequency + 1
            val updatedSuccessRate = existing.avgSuccessRate + 
                (if (record.success) 1.0 else 0.0 - existing.avgSuccessRate) / updatedFreq
            
            val updatedExecTime = existing.avgExecutionTime +
                (record.executionTimeMs - existing.avgExecutionTime) / updatedFreq

            val newConfidence = calculateConfidence(updatedFreq, updatedSuccessRate)

            val mergedTools = (existing.recommendedTools + record.toolsUsed).distinct()

            patterns[patternKey] = existing.copy(
                frequency = updatedFreq,
                avgSuccessRate = updatedSuccessRate,
                avgExecutionTime = updatedExecTime,
                recommendedTools = mergedTools.take(10),
                lastSeen = System.currentTimeMillis(),
                confidence = newConfidence,
            )
        } else {
            if (patterns.size >= config.maxPatternsTracked) {
                evictWeakestPattern()
            }

            patterns[patternKey] = PatternInsight(
                id = patternKey,
                pattern = record.userQuery,
                frequency = 1,
                avgSuccessRate = if (record.success) 1.0 else 0.0,
                avgExecutionTime = record.executionTimeMs,
                recommendedTools = record.toolsUsed,
                lastSeen = System.currentTimeMillis(),
                confidence = if (record.success) 0.3 else 0.1,
            )
        }
    }

    private fun updatePatternFromFeedback(record: ConversationRecord) {
        val patternKey = generatePatternKey(record.userQuery)
        val pattern = patterns[patternKey] ?: return

        val adjustedSuccessRate = pattern.avgSuccessRate +
            (record.responseQuality - pattern.avgSuccessRate) * config.learningRate

        patterns[patternKey] = pattern.copy(
            avgSuccessRate = adjustedSuccessRate.coerceIn(0.0, 1.0),
            confidence = calculateConfidence(pattern.frequency, adjustedSuccessRate),
        )
    }

    private fun generatePatternKey(query: String): String {
        val normalized = query.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .split(Regex("\\s+"))
            .filter { it.length > 2 }
            .sorted()
            .joinToString("_")
            .take(100)

        return normalized.ifEmpty { "generic_${System.currentTimeMillis()}" }
    }

    private fun calculateConfidence(frequency: Int, successRate: Double): Double {
        val freqScore = (frequency.toDouble() / config.minConversationsForPattern.toDouble()).coerceAtMost(1.0)
        val successScore = successRate
        return (freqScore * 0.4 + successScore * 0.6).coerceIn(0.0, 1.0)
    }

    private fun evictWeakestPattern() {
        val weakest = patterns.minByOrNull { it.value.confidence }
        if (weakest != null && weakest.value.confidence < 0.2) {
            patterns.remove(weakest.key)
            DebugLog.d("AutoEvolutionEngine: evicted weak pattern ${weakest.key}")
        }
    }

    private fun saveConversationAsync(record: ConversationRecord) {
        Thread {
            try {
                if (storageDir == null) return@Thread
                
                storageDir.mkdirs()
                
                val file = File(storageDir, "conv_${record.id}.json")
                val json = kotlinx.serialization.json.Json {
                    encodeDefaults = true
                    prettyPrint = false
                }.encodeToString(ConversationRecord.serializer(), record)
                
                file.writeText(json)
            } catch (e: Exception) {
                DebugLog.e("AutoEvolutionEngine.saveConversation: ${e.message}")
            }
        }.start()
    }

    private fun loadState() {
        if (storageDir == null || !storageDir.exists()) return

        try {
            storageDir.listFiles()?.filter { it.name.startsWith("conv_") }?.forEach { file ->
                try {
                    val json = file.readText()
                    val record = kotlinx.serialization.json.Json {
                        ignoreUnknownKeys = true
                    }.decodeFromString(ConversationRecord.serializer(), json)
                    
                    conversations[record.id] = record
                    extractAndLearnPattern(record)
                } catch (e: Exception) {
                    DebugLog.w("AutoEvolutionEngine: failed to load ${file.name}")
                }
            }

            DebugLog.i("AutoEvolutionEngine: loaded ${conversations.size} conversations and ${patterns.size} patterns")
        } catch (e: Exception) {
            DebugLog.e("AutoEvolutionEngine.loadState: ${e.message}")
        }
    }

    companion object {
        private var instance: AutoEvolutionEngine? = null

        fun getInstance(config: EvolutionConfig = EvolutionConfig()): AutoEvolutionEngine {
            if (instance == null) {
                instance = AutoEvolutionEngine(config)
            }
            return instance!!
        }

        fun createGlobal(config: EvolutionConfig = EvolutionConfig(), storageDir: File? = null): AutoEvolutionEngine {
            val engine = AutoEvolutionEngine(config, storageDir)
            instance = engine
            return engine
        }
    }
}
