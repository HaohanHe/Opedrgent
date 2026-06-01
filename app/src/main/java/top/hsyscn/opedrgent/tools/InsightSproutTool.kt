package top.hsyscn.opedrgent.tools

import top.hsyscn.opedrgent.insight.InsightSproutEngine
import top.hsyscn.opedrgent.insight.KeywordTrigger
import top.hsyscn.opedrgent.insight.SproutConfig
import top.hsyscn.opedrgent.insight.SproutOutputLength
import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.model.ToolStateType
import top.hsyscn.opedrgent.network.ToolResult
import top.hsyscn.opedrgent.settings.ApiConfig
import top.hsyscn.opedrgent.utils.DebugLog

class InsightSproutTool(
    private val engine: InsightSproutEngine,
) : ToolSet {

    companion object {
        private const val MIN_TEXT_LENGTH = 10
        private const val MAX_TEXT_LENGTH = 50000
        private const val LONG_TEXT_THRESHOLD = 2000
        private const val CACHE_MAX_SIZE = 8
    }

    private val sproutCache = LinkedHashMap<String, SproutCacheEntry>(CACHE_MAX_SIZE, 0.75f, true)

    private data class SproutCacheEntry(
        val result: String,
        val qualityScore: Float,
        val timestamp: Long,
    )

    private fun emptyResult(tp: ToolPart, msg: String): ToolResult {
        return ToolResult(toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.ERROR, error = msg, endTime = System.currentTimeMillis())))
    }

    @Tool("insight_sprout")
    @ToolDescription("知识发芽：对输入文本进行深度多维度分析，发芽衍生出结构化的洞察报告。参数中 text 为必填，length/domains/use_context 为可选。")
    suspend fun executeInsightSprout(
        tp: ToolPart,
        config: ApiConfig,
        systemPrompt: String,
        useProviderSearch: Boolean,
    ): ToolResult {
        val rawText = tp.state.input["text"]
        if (rawText.isNullOrBlank()) {
            return emptyResult(tp, "缺少必填参数 text：需要提供待发芽的文本内容（至少 $MIN_TEXT_LENGTH 个字符）")
        }

        val text = rawText.trim()

        if (text.length < MIN_TEXT_LENGTH) {
            return emptyResult(tp, "输入文本过短（当前 ${text.length} 字符，最少需要 $MIN_TEXT_LENGTH 字符），无法进行有效的知识发芽分析。请提供更完整的文本内容。")
        }

        if (text.length > MAX_TEXT_LENGTH) {
            return emptyResult(tp, "输入文本过长（当前 ${text.length} 字符，上限 $MAX_TEXT_LENGTH 字符），请缩短后重试。建议截取核心段落进行分析。")
        }

        val lengthParam = tp.state.input["length"]?.trim()?.lowercase()
        val outputLength = when (lengthParam) {
            "short" -> SproutOutputLength.SHORT
            "long" -> SproutOutputLength.LONG
            else -> SproutOutputLength.MEDIUM
        }

        val domainsParam = tp.state.input["domains"]?.trim()
        val preferredDomains = domainsParam
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        val useContextParam = tp.state.input["use_context"]?.trim()?.lowercase()
        val useContext = when (useContextParam) {
            "false", "0", "no" -> false
            else -> true
        }

        DebugLog.i("insight_sprout: text.length=${text.length}, length=$lengthParam->${outputLength.name}, domains=$preferredDomains, use_context=$useContext")

        val (isTrigger, confidence) = KeywordTrigger.detect(text)
        if (confidence > 0.8) {
            DebugLog.i("InsightSproutTool: 检测到高置信度发芽关键词 trigger=$isTrigger confidence=$confidence")
        }

        val effectiveText = if (text.length > LONG_TEXT_THRESHOLD) {
            extractKeyPassages(text).also { extracted ->
                DebugLog.i("insight_sprout: 长文本截取 ${text.length}->${extracted.length} 字符")
            }
        } else {
            text
        }

        val cacheKey = buildCacheKey(effectiveText, outputLength, preferredDomains)
        val cached = sproutCache[cacheKey]
        if (cached != null) {
            DebugLog.i("insight_sprout: 命中缓存 cacheKey=$cacheKey")
            val cachedReport = formatCachedResult(cached.result, cached.qualityScore, fromCache = true)
            return ToolResult(toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.COMPLETED, output = cachedReport, endTime = System.currentTimeMillis())))
        }

        val sproutConfig = SproutConfig(
            outputLength = outputLength,
            preferredDomains = preferredDomains,
            useContext = useContext,
        )

        val startTime = System.currentTimeMillis()
        val result = try {
            engine.sprout(effectiveText, sproutConfig)
        } catch (e: Exception) {
            DebugLog.w("insight_sprout: engine execution failed - ${e.message}", e)
            return emptyResult(tp, "知识发芽执行失败：${e.message}\n\n💡 提示：发芽过程涉及 4 阶段 LLM 调用，可能因网络或超时失败，请稍后重试。")
        }

        if (result.markdownReport.isBlank() && result.seeds.isEmpty() && result.insights.isEmpty()) {
            return emptyResult(tp, "知识发芽未产生有效输出。请检查输入文本是否包含足够的信息密度（如观点、论述、案例等），纯数据或代码片段可能不适合发芽分析。")
        }

        val qualityScore = engine.getCachedQualityScore()?.overallScore ?: evaluateFallbackQuality(result)
        val processingTimeMs = System.currentTimeMillis() - startTime

        DebugLog.i("insight_sprout: completed phases=${result.completedPhases.size}/4 time=${processingTimeMs}ms quality=$qualityScore")

        val formattedReport = formatSproutResult(result, qualityScore, processingTimeMs, outputLength, preferredDomains)

        if (sproutCache.size >= CACHE_MAX_SIZE) {
            val oldestKey = sproutCache.keys.first()
            sproutCache.remove(oldestKey)
            DebugLog.d("insight_sprout: 缓存淘汰 key=$oldestKey")
        }
        sproutCache[cacheKey] = SproutCacheEntry(
            result = formattedReport,
            qualityScore = qualityScore,
            timestamp = System.currentTimeMillis(),
        )

        return ToolResult(toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.COMPLETED, output = formattedReport, endTime = System.currentTimeMillis())))
    }

    private fun extractKeyPassages(text: String): String {
        val paragraphs = text.split(Regex("\n\\s*\n")).filter { it.trim().length > 20 }
        if (paragraphs.size <= 3) return text.take(LONG_TEXT_THRESHOLD + 500)

        val scoredParagraphs = paragraphs.mapIndexed { index, para ->
            val score = calculateParagraphImportance(para, index, paragraphs.size)
            para to score
        }.sortedByDescending { it.second }

        val selected = StringBuilder()
        var currentLength = 0
        for ((para, _) in scoredParagraphs) {
            if (currentLength + para.length > LONG_TEXT_THRESHOLD + 500) break
            if (selected.isNotEmpty()) selected.appendLine().appendLine()
            selected.append(para.trim())
            currentLength += para.length
        }
        return selected.toString().ifBlank { text.take(LONG_TEXT_THRESHOLD + 500) }
    }

    private fun calculateParagraphImportance(para: String, index: Int, total: Int): Double {
        var score = 100.0
        val lower = para.lowercase()

        val indicatorKeywords = listOf(
            "因此", "所以", "结论", "总之", "综上", "可见",
            "关键", "核心", "重要", "本质", "根本",
            "但是", "然而", "不过", "相反", "反而",
            "例如", "比如", "具体来说", "换句话说",
            "我认为", "我的观点", "在我看来", "值得注意的是",
        )
        for (keyword in indicatorKeywords) {
            if (lower.contains(keyword)) score += 15.0
        }

        val questionMarks = para.count { it == '？' || it == '?' } +
            Regex("[?？]").findAll(para).count
        score += questionMarks * 5.0

        val positionBonus = when {
            index == 0 || index == total - 1 -> 20.0
            index < total / 3 || index > total * 2 / 3 -> 10.0
            else -> 0.0
        }
        score += positionBonus

        val density = para.filter { it.isLetterOrDigit() }.length.coerceAtLeast(1).toFloat()
        val ideaDensity = indicatorKeywords.count { lower.contains(it) }.toFloat() / (para.length.toFloat() / 100f)
        score += ideaDensity * 10.0

        if (para.length in 80..600) score += 10.0

        return score
    }

    private fun buildCacheKey(text: String, length: SproutOutputLength, domains: List<String>): String {
        val hash = (text.hashCode() xor length.hashCode() xor domains.hashCode()).toString(16)
        return "${text.length}_${length.name}_${domains.joinToString(",")}_$hash"
    }

    private fun formatSproutResult(
        result: top.hsyscn.opedrgent.insight.SproutResult,
        qualityScore: Float,
        processingTimeMs: Long,
        outputLength: SproutOutputLength,
        preferredDomains: List<String>,
    ): String {
        val sb = StringBuilder(4096)
        val phasesCompleted = result.completedPhases.size
        val totalPhases = 4

        sb.appendLine("🌱 **知识发芽完成**")
        sb.appendLine()
        sb.appendLine("已完成 **$phasesCompleted/$totalPhases** 阶段的处理，以下是您的发芽报告：")
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()

        if (result.markdownReport.isNotBlank()) {
            sb.append(result.markdownReport)
        } else {
            appendFallbackReport(sb, result)
        }

        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()

        val grade = when {
            qualityScore >= 85 -> "优秀"
            qualityScore >= 70 -> "良好"
            qualityScore >= 50 -> "一般"
            else -> "较差"
        }
        sb.appendLine("📊 **质量评分**: ${qualityScore.toInt()}/100 ($grade)")
        sb.appendLine()
        sb.appendLine("⏱️ **处理耗时**: ${formatDuration(processingTimeMs)}")
        sb.appendLine()

        sb.appendLine("💡 **后续建议**:")
        if (preferredDomains.isEmpty()) {
            sb.appendLine("- 对某个领域感兴趣？可以指定 `--domains=\"心理学,哲学\"` 深化分析")
        }
        if (outputLength != SproutOutputLength.LONG) {
            sb.appendLine("- 想要更长或更短的报告？调整 `--length=\"long\"` 或 `--length=\"short\"`")
        }
        sb.appendLine("- 复制全文或点击「继续追问」深入探讨某个感兴趣的洞察")
        sb.appendLine("- 尝试对发芽结果中的某个金句再次发芽，获得更深层的联想")

        return sb.toString()
    }

    private fun formatCachedResult(report: String, qualityScore: Float, fromCache: Boolean): String {
        if (!fromCache) return report
        val prefix = """
            |🌱 **知识发芽完成**（缓存命中）
            |
            |> ⚡ 本次结果来自近期缓存，相同文本无需重复分析
            |
            |---
            |
        """.trimMargin()
        val suffix = """
            |
            |---
            |
            |📊 **质量评分**: ${qualityScore.toInt()}/100 | 🔄 *缓存读取*
        """.trimMargin()
        return "$prefix$report$suffix"
    }

    private fun appendFallbackReport(sb: StringBuilder, result: top.hsyscn.opedrgent.insight.SproutResult) {
        if (result.seeds.isNotEmpty()) {
            sb.appendLine("## 🌱 种子概念")
            sb.appendLine()
            result.seeds.forEachIndexed { i, seed ->
                sb.appendLine("${i + 1}. **${seed.concept}**: ${seed.description}")
            }
            sb.appendLine()
        }

        if (result.connections.isNotEmpty()) {
            sb.appendLine("## 🔗 跨领域联结")
            sb.appendLine()
            result.connections.forEach { conn ->
                sb.appendLine("- **${conn.domain}**: ${conn.analogyOrCase}")
                sb.appendLine("  ${conn.analysis}")
            }
            sb.appendLine()
        }

        if (result.insights.isNotEmpty()) {
            sb.appendLine("## ✨ Aha 洞察")
            sb.appendLine()
            result.insights.forEach { insight ->
                sb.appendLine("> 「${insight.content}」")
            }
            sb.appendLine()
        }

        if (result.quotes.isNotEmpty()) {
            sb.appendLine("## 💡 金句回响")
            sb.appendLine()
            result.quotes.forEach { quote ->
                sb.appendLine("> 「${quote.originalQuote}」——*${quote.author}*《${quote.source}》")
                sb.appendLine()
                sb.appendLine("**延展思考**: ${quote.extension}")
            }
            sb.appendLine()
        }
    }

    private fun evaluateFallbackQuality(result: top.hsyscn.opedrgent.insight.SproutResult): Float {
        var score = 0f
        score += (result.seeds.size.coerceAtMost(6) * 12).coerceAtMost(30).toFloat()
        score += (result.connections.size.coerceAtMost(8) * 9).coerceAtMost(30).toFloat()
        score += (result.insights.size.coerceAtMost(4) * 14).coerceAtMost(28).toFloat()
        score += (result.quotes.size.coerceAtMost(3) * 8).coerceAtMost(12).toFloat()
        score += (result.completedPhases.size * 2).toFloat()
        return score.coerceIn(0f, 100f)
    }

    private fun formatDuration(ms: Long): String {
        return when {
            ms < 1000 -> "${ms}ms"
            ms < 60_000 -> String.format("%.1fs", ms / 1000.0)
            else -> {
                val sec = ms / 1000
                val min = sec / 60
                val remainSec = sec % 60
                "${min}m${remainSec}s"
            }
        }
    }

    override fun getTools(): Map<String, ToolBinding> {
        return mapOf(
            "insight_sprout" to ToolBinding(
                name = "insight_sprout",
                description = "知识发芽：对输入文本进行深度多维度分析，发芽衍生出结构化的洞察报告。" +
                    "参数中 text 为必填，length(domains/use_context) 为可选。" +
                    "适用于笔记深化、观点发散、跨领域联想等场景。",
                invoker = { tp, cfg, sp, ups -> executeInsightSprout(tp, cfg, sp, ups) },
            ),
        )
    }
}
