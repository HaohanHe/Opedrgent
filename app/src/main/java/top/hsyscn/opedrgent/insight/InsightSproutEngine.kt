package top.hsyscn.opedrgent.insight

import kotlinx.coroutines.withTimeoutOrNull
import top.hsyscn.opedrgent.utils.DebugLog
import kotlin.time.Duration
import kotlin.time.measureTime

class InsightSproutEngine(
    private val llmCall: suspend (prompt: String) -> String,
) {
    
    suspend fun sprout(inputText: String, config: SproutConfig = SproutConfig()): SproutResult {
        val startTime = System.currentTimeMillis()
        
        DebugLog.i("InsightSproutEngine: 开始发芽处理 inputLength=${inputText.length}")
        
        val completedPhases = mutableSetOf<SproutPhase>()
        var allContext = ""
        
        // Phase 1: 种子提取
        val phase1Result = runWithTimeout(config.maxPhaseTimeoutSeconds.toLong()) {
            val prompt = SproutPromptBuilder.buildPhase1Prompt(
                inputText = inputText,
                context = if (config.useContext) "[用户上下文待注入]" else null,
            )
            val response = llmCall(prompt)
            allContext += "\n=== Phase 1: 种子提取 ===\n$response\n"
            response
        }
        
        if (phase1Result != null) {
            completedPhases.add(SproutPhase.SEED_EXTRACTION)
            DebugLog.i("InsightSproutEngine: Phase 1 完成")
        }
        
        // Phase 2: 跨领域关联
        val phase2Result = runWithTimeout(config.maxPhaseTimeoutSeconds.toLong()) {
            val prompt = SproutPromptBuilder.buildPhase2Prompt(
                seedsJson = phase1Result ?: "[]",
                previousContext = allContext,
            )
            val response = llmCall(prompt)
            allContext += "\n=== Phase 2: 跨领域关联 ===\n$response\n"
            response
        }
        
        if (phase2Result != null) {
            completedPhases.add(SproutPhase.CROSS_DOMAIN)
            DebugLog.i("InsightSproutEngine: Phase 2 完成")
        }
        
        // Phase 3: Aha 洞察生成
        val phase3Result = runWithTimeout(config.maxPhaseTimeoutSeconds.toLong()) {
            val prompt = SproutPromptBuilder.buildPhase3Prompt(
                seedsAndConnections = "${phase1Result ?: ""}\n${phase2Result ?: ""}",
                previousContext = allContext,
            )
            val response = llmCall(prompt)
            allContext += "\n=== Phase 3: Aha 洞察 ===\n$response\n"
            response
        }
        
        if (phase3Result != null) {
            completedPhases.add(SproutPhase.AHA_INSIGHT)
            DebugLog.i("InsightSproutEngine: Phase 3 完成")
        }
        
        // Phase 4: 金句回响
        val phase4Result = runWithTimeout(config.maxPhaseTimeoutSeconds.toLong()) {
            val prompt = SproutPromptBuilder.buildPhase4Prompt(
                allPreviousContext = allContext,
                preferredDomains = config.preferredDomains.ifEmpty { null },
            )
            val response = llmCall(prompt)
            allContext += "\n=== Phase 4: 金句回响 ===\n$response\n"
            response
        }
        
        if (phase4Result != null) {
            completedPhases.add(SproutPhase.QUOTE_RESONANCE)
            DebugLog.i("InsightSproutEngine: Phase 4 完成")
        }
        
        val processingTimeMs = System.currentTimeMillis() - startTime
        
        DebugLog.i("InsightSproutEngine: 发芽完成 phases=${completedPhases.size}/4 time=${processingTimeMs}ms")
        
        return SproutResult(
            seeds = parseSeeds(phase1Result),
            connections = parseConnections(phase2Result),
            insights = parseInsights(phase3Result),
            quotes = parseQuotes(phase4Result),
            markdownReport = generateMarkdownReport(phase1Result, phase2Result, phase3Result, phase4Result),
            completedPhases = completedPhases.toSet(),
            inputText = inputText,
            processingTimeMs = processingTimeMs,
        )
    }
    
    private suspend fun <T> runWithTimeout(timeoutMs: Long, block: suspend () -> T): T? {
        return try {
            withTimeoutOrNull(java.time.Duration.ofMillis(timeoutMs)) { block() }
        } catch (e: Exception) {
            DebugLog.w("InsightSproutEngine: 阶段执行超时或异常: ${e.message}")
            null
        }
    }
    
    private fun parseSeeds(jsonStr: String?): List<SproutSeed> {
        if (jsonStr.isNullOrBlank()) return emptyList()
        return try {
            // TODO: 解析 JSON 为 SproutSeed 列表
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    private fun parseConnections(jsonStr: String?): List<SproutConnection> {
        if (jsonStr.isNullOrBlank()) return emptyList()
        return try {
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    private fun parseInsights(jsonStr: String?): List<SproutInsight> {
        if (jsonStr.isNullOrBlank()) return emptyList()
        return try {
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    private fun parseQuotes(jsonStr: String?): List<SproutQuote> {
        if (jsonStr.isNullOrBlank()) return emptyList()
        return try {
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    private fun generateMarkdownReport(vararg results: String?): String {
        val sb = StringBuilder()
        sb.appendLine("# 🌱 知识发芽报告\n")
        results.forEachIndexed { index, result ->
            if (!result.isNullOrBlank()) {
                sb.appendLine("## 阶段 ${index + 1}\n")
                sb.appendLine(result)
                sb.appendLine()
            }
        }
        return sb.toString()
    }
}
