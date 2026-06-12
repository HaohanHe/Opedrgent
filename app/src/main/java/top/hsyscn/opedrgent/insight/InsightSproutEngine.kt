package top.hsyscn.opedrgent.insight

import kotlinx.coroutines.withTimeoutOrNull
import top.hsyscn.opedrgent.utils.DebugLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SproutQualityScore(
    val seedCount: Int,
    val connectionCount: Int,
    val insightCount: Int,
    val quoteCount: Int,
    val avgConnectionUnexpectedness: Float,
    val totalPhasesCompleted: Int,
    val overallScore: Float,
) {
    fun grade(): String = when {
        overallScore >= 85 -> "优秀"
        overallScore >= 70 -> "良好"
        overallScore >= 50 -> "一般"
        else -> "较差"
    }
}

class InsightSproutEngine(
    private val llmCall: suspend (prompt: String) -> String,
) {

    private val phaseCache = mutableMapOf<String, Any?>()

    suspend fun sprout(
        inputText: String,
        config: SproutConfig = SproutConfig(),
        userContext: String? = null,
    ): SproutResult {
        phaseCache.clear()
        val startTime = System.currentTimeMillis()
        val inputSummary = inputText.take(80).let { if (inputText.length > 80) it + "..." else it }

        DebugLog.i("InsightSproutEngine: 开始发芽处理 inputLength=${inputText.length} summary=$inputSummary")

        val completedPhases = mutableSetOf<SproutPhase>()
        var allContext = StringBuilder()

        val effectiveContext = when {
            userContext != null -> userContext
            config.useContext -> ""
            else -> null
        }

        // Phase 1: 种子提取
        var phase1Result: String? = null
        runCatchingWithRecovery("Phase1-种子提取", config.maxPhaseTimeoutSeconds.toLong() * 1000) {
            val prompt = SproutPromptBuilder.buildPhase1Prompt(
                inputText = inputText,
                context = effectiveContext,
            )
            val response = llmCall(prompt)
            allContext.appendLine("=== Phase 1: 种子提取 ===").appendLine(response)
            response
        }.onSuccess { result ->
            phase1Result = result
            completedPhases.add(SproutPhase.SEED_EXTRACTION)
            DebugLog.i("InsightSproutEngine: Phase 1 完成")
        }

        // Phase 2: 跨领域关联
        var phase2Result: String? = null
        runCatchingWithRecovery("Phase2-跨领域关联", config.maxPhaseTimeoutSeconds.toLong() * 1000) {
            val prompt = SproutPromptBuilder.buildPhase2Prompt(
                seedsJson = phase1Result ?: "[]",
                previousContext = allContext.toString(),
            )
            val response = llmCall(prompt)
            allContext.appendLine("=== Phase 2: 跨领域关联 ===").appendLine(response)
            response
        }.onSuccess { result ->
            phase2Result = result
            completedPhases.add(SproutPhase.CROSS_DOMAIN)
            DebugLog.i("InsightSproutEngine: Phase 2 完成")
        }

        // Phase 3: Aha 洞察生成
        var phase3Result: String? = null
        runCatchingWithRecovery("Phase3-Aha洞察", config.maxPhaseTimeoutSeconds.toLong() * 1000) {
            val prompt = SproutPromptBuilder.buildPhase3Prompt(
                seedsAndConnections = "${phase1Result ?: ""}\n${phase2Result ?: ""}",
                previousContext = allContext.toString(),
            )
            val response = llmCall(prompt)
            allContext.appendLine("=== Phase 3: Aha 洞察 ===").appendLine(response)
            response
        }.onSuccess { result ->
            phase3Result = result
            completedPhases.add(SproutPhase.AHA_INSIGHT)
            DebugLog.i("InsightSproutEngine: Phase 3 完成")
        }

        // Phase 4: 金句回响
        var phase4Result: String? = null
        runCatchingWithRecovery("Phase4-金句回响", config.maxPhaseTimeoutSeconds.toLong() * 1000) {
            val prompt = SproutPromptBuilder.buildPhase4Prompt(
                allPreviousContext = allContext.toString(),
                preferredDomains = config.preferredDomains.ifEmpty { null },
            )
            val response = llmCall(prompt)
            allContext.appendLine("=== Phase 4: 金句回响 ===").appendLine(response)
            response
        }.onSuccess { result ->
            phase4Result = result
            completedPhases.add(SproutPhase.QUOTE_RESONANCE)
            DebugLog.i("InsightSproutEngine: Phase 4 完成")
        }

        val processingTimeMs = System.currentTimeMillis() - startTime

        DebugLog.i("InsightSproutEngine: 发芽完成 phases=${completedPhases.size}/4 time=${processingTimeMs}ms")

        val seeds = parseSeeds(phase1Result).also { phaseCache["seeds"] = it }
        val connections = parseConnections(phase2Result).also { phaseCache["connections"] = it }
        val insights = parseInsights(phase3Result).also { phaseCache["insights"] = it }
        val quotes = parseQuotes(phase4Result).also { phaseCache["quotes"] = it }

        val result = SproutResult(
            seeds = seeds,
            connections = connections,
            insights = insights,
            quotes = quotes,
            markdownReport = generateMarkdownReport(
                inputText = inputText,
                seeds = seeds,
                connections = connections,
                insights = insights,
                quotes = quotes,
                completedPhases = completedPhases.toSet(),
                processingTimeMs = processingTimeMs,
            ),
            completedPhases = completedPhases.toSet(),
            inputText = inputText,
            processingTimeMs = processingTimeMs,
        )

        phaseCache["qualityScore"] = evaluateQuality(result)

        releaseLargeStrings(allContext.toString())

        return result
    }

    private suspend fun runCatchingWithRecovery(
        phaseName: String,
        timeoutMs: Long,
        block: suspend () -> String,
    ): Result<String> {
        return try {
            val result = withTimeoutOrNull(timeoutMs) { block() }
            if (result != null) Result.success(result) else {
                DebugLog.w("InsightSproutEngine: $phaseName 超时 (${timeoutMs}ms)")
                Result.failure(TimeoutException("$phaseName 超时"))
            }
        } catch (e: Exception) {
            DebugLog.w("InsightSproutEngine: $phaseName 异常: ${e.message}")
            Result.failure(e)
        }
    }

    private class TimeoutException(message: String) : Exception(message)

    fun evaluateQuality(result: SproutResult): SproutQualityScore {
        val seedCount = result.seeds.size
        val connectionCount = result.connections.size
        val insightCount = result.insights.size
        val quoteCount = result.quotes.size

        val avgUnexpectedness = if (result.connections.isNotEmpty()) {
            result.connections.map { it.unexpectedness }.average().toFloat().coerceIn(0f, 1f)
        } else 0f

        val totalPhases = result.completedPhases.size

        val seedScore = (seedCount.coerceAtLeast(0) * 15).coerceAtMost(30)
        val connectionScore = (connectionCount.coerceAtLeast(0) * 12).coerceAtMost(36)
        val insightScore = (insightCount.coerceAtLeast(0) * 18).coerceAtMost(36)
        val quoteScore = (quoteCount.coerceAtLeast(1) * 10).coerceAtMost(10)
        val unexpectednessBonus = (avgUnexpectedness * 20).toInt()

        val overallScore = ((seedScore + connectionScore + insightScore + quoteScore + unexpectednessBonus)
            .coerceIn(0, 100)).toFloat()

        return SproutQualityScore(
            seedCount = seedCount,
            connectionCount = connectionCount,
            insightCount = insightCount,
            quoteCount = quoteCount,
            avgConnectionUnexpectedness = avgUnexpectedness,
            totalPhasesCompleted = totalPhases,
            overallScore = overallScore,
        ).also {
            DebugLog.i("InsightSproutEngine: 质量评估 score=${it.overallScore} grade=${it.grade()}")
        }
    }

    fun getCachedQualityScore(): SproutQualityScore? =
        phaseCache["qualityScore"] as? SproutQualityScore

    // ==================== JSON 解析核心 ====================

    private fun cleanJsonInput(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var text = raw.trim()

        val codeBlockPattern = Regex("""```(?:json)?\s*\n?(.*?)\n?\s*```""", RegexOption.DOT_MATCHES_ALL)
        val match = codeBlockPattern.find(text)
        if (match != null) {
            text = match.groupValues[1].trim()
            DebugLog.d("InsightSproutEngine: 从 markdown 代码块中提取 JSON")
        }

        val jsonStart = text.indexOf('{')
        val jsonEnd = text.lastIndexOf('}')
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            text = text.substring(jsonStart, jsonEnd + 1).trim()
        }

        return text.ifBlank { raw.trim() }
    }

    private fun extractJsonArray(jsonStr: String, arrayKey: String): List<String> {
        val cleaned = cleanJsonInput(jsonStr) ?: return emptyList()

        val arrayRegex = Regex(""""$arrayKey"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
        val match = arrayRegex.find(cleaned)
        if (match != null) {
            val arrayContent = match.groupValues[1]
            return splitJsonObjects(arrayContent)
        }

        val bracketStart = cleaned.indexOf('[')
        val bracketEnd = cleaned.lastIndexOf(']')
        if (bracketStart >= 0 && bracketEnd > bracketStart) {
            val content = cleaned.substring(bracketStart + 1, bracketEnd).trim()
            if (content.isNotEmpty()) return splitJsonObjects(content)
        }

        DebugLog.w("InsightSproutEngine: 未找到 $arrayKey 数组，尝试正则降级提取")
        return emptyList()
    }

    private fun splitJsonObjects(content: String): List<String> {
        val objects = mutableListOf<String>()
        var depth = 0
        var current = StringBuilder()
        var inString = false
        var escapeNext = false
        var i = 0

        while (i < content.length) {
            val ch = content[i]

            if (escapeNext) {
                current.append(ch)
                escapeNext = false
                i++
                continue
            }

            if (ch == '\\' && inString) {
                current.append(ch)
                escapeNext = true
                i++
                continue
            }

            if (ch == '"') {
                inString = !inString
                current.append(ch)
                i++
                continue
            }

            if (!inString) {
                when (ch) {
                    '{' -> depth++
                    '}' -> depth--
                }
            }

            current.append(ch)

            if (!inString && depth == 0 && ch == '}') {
                val objStr = current.toString().trim()
                if (objStr.startsWith("{")) objects.add(objStr)
                current = StringBuilder()
            }

            i++
        }

        val remaining = current.toString().trim()
        if (remaining.isNotEmpty() && remaining.startsWith("{")) {
            objects.add(remaining)
        }

        return objects
    }

    private fun extractStringValue(obj: String, key: String): String {
        val patterns = listOf(
            Regex(""""$key"\s*:\s*"((?:[^"\\]|\\.)*)""""),
            Regex(""""$key"\s*:\s*'((?:[^'\\]|\\.)*)'"""),
        )
        for (pattern in patterns) {
            pattern.find(obj)?.let { return unescapeJson(it.groupValues[1]) }
        }
        return ""
    }

    private fun extractFloatValue(obj: String, key: String, default: Float = 0f): Float {
        val pattern = Regex(""""$key"\s*:\s*(\d+\.?\d*)""")
        return pattern.find(obj)?.groupValues?.get(1)?.toFloatOrNull() ?: default
    }

    private fun extractIntArray(obj: String, key: String): List<String> {
        val arrayMatch = Regex(""""$key"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(obj)
            ?: return emptyList()
        val content = arrayMatch.groupValues[1]
        return Regex(""""((?:[^"\\]|\\.)*)"""").findAll(content).map {
            unescapeJson(it.groupValues[1])
        }.filter { it.isNotEmpty() }.toList()
    }

    private fun unescapeJson(s: String): String {
        return s.replace("\\\"", "\"")
            .replace("\\'", "'")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\\", "\\")
    }

    // ==================== 各阶段解析 ====================

    private fun parseSeeds(jsonStr: String?): List<SproutSeed> {
        if (jsonStr.isNullOrBlank()) return emptyList()
        return try {
            val jsonObjects = extractJsonArray(jsonStr, "seeds")
            if (jsonObjects.isEmpty()) {
                DebugLog.w("InsightSproutEngine: parseSeeds 未找到 JSON 数组，尝试宽松匹配")
                return parseSeedsFallback(jsonStr)
            }
            jsonObjects.mapNotNull { obj ->
                val concept = extractStringValue(obj, "concept")
                    .ifBlank { extractStringValue(obj, "name") }
                    .ifBlank { extractStringValue(obj, "title") }
                val description = extractStringValue(obj, "description")
                    .ifBlank { extractStringValue(obj, "desc") }
                    .ifBlank { extractStringValue(obj, "summary") }
                val keywords = extractIntArray(obj, "keywords")
                    .ifEmpty { extractIntArray(obj, "tags") }
                val relevance = extractFloatValue(obj, "relevanceScore")
                    .coerceAtLeast(extractFloatValue(obj, "score"))

                if (concept.isNotBlank()) {
                    SproutSeed(
                        concept = concept.trim(),
                        description = description.trim().ifBlank { concept },
                        keywords = keywords.map { it.trim() }.filter { it.isNotEmpty() },
                        relevanceScore = relevance.coerceIn(0f, 1f),
                    )
                } else null
            }.also {
                DebugLog.i("InsightSproutEngine: parseSeeds 解析成功 count=${it.size}")
                DebugLog.json("seeds", it.toString())
            }
        } catch (e: Exception) {
            DebugLog.e("InsightSproutEngine: parseSeeds 异常，降级到正则提取", e)
            parseSeedsFallback(jsonStr)
        }
    }

    private fun parseSeedsFallback(text: String): List<SproutSeed> {
        val results = mutableListOf<SproutSeed>()

        val conceptPatterns = listOf(
            Regex("""(?:概念|concept|名称|name|title)["\s:：]+([^\n\r\{\[]{2,30}?)(?:[,\"\n\r]|$)""", RegexOption.IGNORE_CASE),
            Regex("""\*\*(.+?)\*\*[：:]\s*(.{5,100})"""),
        )

        for (pattern in conceptPatterns) {
            pattern.findAll(text).forEach { match ->
                val concept = match.groupValues[1].trim().removeSurrounding("\"")
                val desc = if (match.groupValues.size > 2) match.groupValues[2].trim().removeSurrounding("\"") else ""
                if (concept.length in 2..30 && concept.none { it in "{}[]" }) {
                    results.add(SproutSeed(concept = concept, description = desc))
                }
            }
            if (results.isNotEmpty()) break
        }

        if (results.isEmpty()) {
            val lines = text.lines().filter { it.isNotBlank() && it.length in 5..200 && !it.trimStart().startsWith("{") && !it.trimStart().startsWith("[") }
            lines.take(5).forEach { line ->
                val clean = line.trim().removePrefix("- ").removePrefix("* ").removePrefix("#").trim()
                if (clean.contains(Regex("[：:]"))) {
                    val parts = clean.split(Regex("[：:]"), limit = 2)
                    if (parts[0].length in 2..30) {
                        results.add(SproutSeed(concept = parts[0].trim(), description = parts.getOrNull(1)?.trim().orEmpty()))
                    }
                }
            }
        }

        return results.distinctBy { it.concept }.take(6)
    }

    private fun parseConnections(jsonStr: String?): List<SproutConnection> {
        if (jsonStr.isNullOrBlank()) return emptyList()
        return try {
            val jsonObjects = extractJsonArray(jsonStr, "connections")
            if (jsonObjects.isEmpty()) {
                DebugLog.w("InsightSproutEngine: parseConnections 未找到 JSON 数组，尝试宽松匹配")
                return parseConnectionsFallback(jsonStr)
            }
            jsonObjects.mapNotNull { obj ->
                val domain = extractStringValue(obj, "domain")
                    .ifBlank { extractStringValue(obj, "field") }
                    .ifBlank { extractStringValue(obj, "area") }
                val analogyOrCase = extractStringValue(obj, "analogyOrCase")
                    .ifBlank { extractStringValue(obj, "analogy") }
                    .ifBlank { extractStringValue(obj, "case") }
                    .ifBlank { extractStringValue(obj, "example") }
                val analysis = extractStringValue(obj, "analysis")
                    .ifBlank { extractStringValue(obj, "interpretation") }
                val unexpectedness = extractFloatValue(obj, "unexpectedness")
                    .coerceAtLeast(extractFloatValue(obj, "surprise"))

                if (domain.isNotBlank() && analogyOrCase.isNotBlank()) {
                    SproutConnection(
                        domain = domain.trim(),
                        analogyOrCase = analogyOrCase.trim(),
                        analysis = analysis.trim().ifBlank { analogyOrCase },
                        unexpectedness = unexpectedness.coerceIn(0f, 1f),
                    )
                } else null
            }.also {
                DebugLog.i("InsightSproutEngine: parseConnections 解析成功 count=${it.size}")
                DebugLog.json("connections", it.toString())
            }
        } catch (e: Exception) {
            DebugLog.e("InsightSproutEngine: parseConnections 异常，降级到正则提取", e)
            parseConnectionsFallback(jsonStr)
        }
    }

    private fun parseConnectionsFallback(text: String): List<SproutConnection> {
        val results = mutableListOf<SproutConnection>()
        val domainPattern = Regex("""(?:领域|domain|field|area)[\"'\s:：]*([^\n\r\{\[\]]{2,20}?)(?:[,\"\n\r]|$)""", RegexOption.IGNORE_CASE)
        val domains = domainPattern.findAll(text).map { it.groupValues[1].trim().removeSurrounding("\"") }.distinct().toList()

        domains.forEach { domain ->
            val idx = text.indexOf(domain)
            if (idx >= 0) {
                val afterDomain = text.substringAfter(domain, "").take(500)
                val caseMatch = Regex("""(?:类比|案例|analogy|case|example)[\"'\s:：]+(.{10,300})""", RegexOption.IGNORE_CASE).find(afterDomain)
                val analysisMatch = Regex("""(?:分析|解读|analysis)[\"'\s:：]+(.{20,500})""", RegexOption.IGNORE_CASE).find(afterDomain)

                results.add(SproutConnection(
                    domain = domain,
                    analogyOrCase = caseMatch?.groupValues?.get(1)?.trim()?.removeSurrounding("\"").orEmpty().ifBlank { "$domain 相关案例" },
                    analysis = analysisMatch?.groupValues?.get(1)?.trim()?.removeSurrounding("\"").orEmpty().ifBlank { "关于 $domain 的跨领域分析" },
                ))
            }
        }

        if (results.isEmpty()) {
            val sections = text.split(Regex("(?=#{1,3}\\s)|(?=\\d+\\.\\s)")).filter { it.isNotBlank() && it.length > 30 }
            sections.forEach { section ->
                val firstLine = section.lines().firstOrNull().orEmpty().trim()
                if (firstLine.length in 2..40 && !firstLine.startsWith("{")) {
                    results.add(SproutConnection(
                        domain = firstLine.removePrefix("#").removePrefix("*").trim(),
                        analogyOrCase = section.lines().drop(1).take(3).joinToString(" ").trim().ifBlank { "相关案例" },
                        analysis = section.lines().drop(4).take(5).joinToString(" ").trim().ifBlank { "深度分析" },
                    ))
                }
            }
        }

        return results.distinctBy { it.domain }.take(8)
    }

    private fun parseInsights(jsonStr: String?): List<SproutInsight> {
        if (jsonStr.isNullOrBlank()) return emptyList()
        return try {
            val jsonObjects = extractJsonArray(jsonStr, "insights")
            if (jsonObjects.isEmpty()) {
                DebugLog.w("InsightSproutEngine: parseInsights 未找到 JSON 数组，尝试宽松匹配")
                return parseInsightsFallback(jsonStr)
            }
            jsonObjects.mapNotNull { obj ->
                val content = extractStringValue(obj, "content")
                    .ifBlank { extractStringValue(obj, "insight") }
                    .ifBlank { extractStringValue(obj, "text") }
                val counterIntuitiveScore = extractFloatValue(obj, "counterIntuitiveScore")
                    .coerceAtLeast(extractFloatValue(obj, "score"))
                val tags = extractIntArray(obj, "tags")

                if (content.isNotBlank()) {
                    SproutInsight(
                        content = content.trim(),
                        counterIntuitiveScore = counterIntuitiveScore.coerceIn(0f, 1f),
                        tags = tags.map { it.trim() }.filter { it.isNotEmpty() },
                    )
                } else null
            }.also {
                DebugLog.i("InsightSproutEngine: parseInsights 解析成功 count=${it.size}")
                DebugLog.json("insights", it.toString())
            }
        } catch (e: Exception) {
            DebugLog.e("InsightSproutEngine: parseInsights 异常，降级到正则提取", e)
            parseInsightsFallback(jsonStr)
        }
    }

    private fun parseInsightsFallback(text: String): List<SproutInsight> {
        val results = mutableListOf<SproutInsight>()

        val quotePatterns = listOf(
            Regex("""[「""](.{10,80}?)[」""]"""),
            Regex(""">([^>\n]{10,80})"""),
            Regex("""\*\*(.{10,80})\*\*"""),
        )

        for (pattern in quotePatterns) {
            pattern.findAll(text).forEach { match ->
                val content = match.groupValues[1].trim()
                if (content.length in 10..80 && !content.contains(Regex("[{}\\[\\]]"))) {
                    results.add(SproutInsight(content = content))
                }
            }
            if (results.isNotEmpty()) break
        }

        if (results.isEmpty()) {
            val sentences = text.split(Regex("[。！？.!?\n]")).filter { it.length in 10..60 }
            sentences.distinct().take(3).forEach { s ->
                results.add(SproutInsight(content = s.trim()))
            }
        }

        return results.distinctBy { it.content }.take(4)
    }

    private fun parseQuotes(jsonStr: String?): List<SproutQuote> {
        if (jsonStr.isNullOrBlank()) return emptyList()
        return try {
            val jsonObjects = extractJsonArray(jsonStr, "quotes")
            if (jsonObjects.isEmpty()) {
                DebugLog.w("InsightSproutEngine: parseQuotes 未找到 JSON 数组，尝试宽松匹配")
                return parseQuotesFallback(jsonStr)
            }
            jsonObjects.mapNotNull { obj ->
                val originalQuote = extractStringValue(obj, "originalQuote")
                    .ifBlank { extractStringValue(obj, "quote") }
                    .ifBlank { extractStringValue(obj, "text") }
                val source = extractStringValue(obj, "source")
                    .ifBlank { extractStringValue(obj, "book") }
                    .ifBlank { extractStringValue(obj, "work") }
                val author = extractStringValue(obj, "author")
                    .ifBlank { extractStringValue(obj, "writer") }
                val extension = extractStringValue(obj, "extension")
                    .ifBlank { extractStringValue(obj, "thinking") }
                    .ifBlank { extractStringValue(obj, "comment") }

                if (originalQuote.isNotBlank()) {
                    SproutQuote(
                        originalQuote = originalQuote.trim(),
                        source = source.trim().ifBlank { "未知出处" },
                        author = author.trim().ifBlank { "佚名" },
                        extension = extension.trim().ifBlank { "——基于此引用的延展思考" },
                    )
                } else null
            }.also {
                DebugLog.i("InsightSproutEngine: parseQuotes 解析成功 count=${it.size}")
                DebugLog.json("quotes", it.toString())
            }
        } catch (e: Exception) {
            DebugLog.e("InsightSproutEngine: parseQuotes 异常，降级到正则提取", e)
            parseQuotesFallback(jsonStr)
        }
    }

    private fun parseQuotesFallback(text: String): List<SproutQuote> {
        val results = mutableListOf<SproutQuote>()

        val quotePattern = Regex("""[「""](.{10,150}?)[」""][—–-\s]*(.+?)《(.+?)》""")
        quotePattern.findAll(text).forEach { match ->
            results.add(SproutQuote(
                originalQuote = match.groupValues[1].trim(),
                author = match.groupValues[2].trim().ifBlank { "佚名" },
                source = match.groupValues[3].trim().ifBlank { "未知出处" },
                extension = "——基于此引用的延展思考",
            ))
        }

        if (results.isEmpty()) {
            val dashPattern = Regex("""[「""](.{10,150}?)[」""][—–-]+(.+)""")
            dashPattern.findAll(text).forEach { match ->
                results.add(SproutQuote(
                    originalQuote = match.groupValues[1].trim(),
                    author = "未知",
                    source = "未知出处",
                    extension = match.groupValues[2].trim(),
                ))
            }
        }

        return results.distinctBy { it.originalQuote }.take(3)
    }

    // ==================== Markdown 报告生成 ====================

    private fun generateMarkdownReport(
        inputText: String,
        seeds: List<SproutSeed>,
        connections: List<SproutConnection>,
        insights: List<SproutInsight>,
        quotes: List<SproutQuote>,
        completedPhases: Set<SproutPhase>,
        processingTimeMs: Long,
    ): String {
        val sb = StringBuilder(2048)
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
        val inputSummary = inputText.take(60).let { if (inputText.length > 60) it + "..." else it }
        val qualityScore = (phaseCache["qualityScore"] as? SproutQualityScore)

        sb.appendLine("# 知识发芽报告")
        sb.appendLine()
        sb.appendLine("> 基于「${escapeMarkdown(inputSummary)}」的深度分析")
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()

        // 种子区域
        sb.appendLine("## 种子")
        sb.appendLine()
        if (seeds.isNotEmpty()) {
            sb.appendLine("| # | 核心概念 | 描述 | 关键词 |")
            sb.appendLine("|---|---------|------|--------|")
            seeds.forEachIndexed { index, seed ->
                val kw = seed.keywords.joinToString(", ")
                sb.appendLine("| ${index + 1} | **${escapeMarkdown(seed.concept)}** | ${escapeMarkdown(seed.description)} | ${escapeMarkdown(kw)} |")
            }
        } else {
            sb.appendLine("*（本阶段未成功提取种子）*")
        }
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()

        // Aha 瞬间
        sb.appendLine("## Aha 瞬间")
        sb.appendLine()
        if (insights.isNotEmpty()) {
            insights.forEach { insight ->
                sb.appendLine("> 「${escapeMarkdown(insight.content)}」")
                sb.appendLine()
                if (insight.tags.isNotEmpty()) {
                    sb.appendLine("*${insight.tags.joinToString(" · ") { escapeMarkdown(it) }}*")
                    sb.appendLine()
                }
            }
        } else {
            sb.appendLine("*（本阶段未生成洞察）*")
        }
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()

        // 跨领域联结
        sb.appendLine("## 跨领域联结")
        sb.appendLine()
        if (connections.isNotEmpty()) {
            connections.forEach { conn ->
                sb.appendLine("### ${escapeMarkdown(conn.domain)}")
                sb.appendLine()
                sb.appendLine("- **类比/案例**: ${escapeMarkdown(conn.analogyOrCase)}")
                sb.appendLine("- **分析**: ${escapeMarkdown(conn.analysis)}")
                sb.appendLine("- 反直觉度: ${starRating(conn.unexpectedness)} (${String.format("%.1f", conn.unexpectedness)})")
                sb.appendLine()
            }
        } else {
            sb.appendLine("*（本阶段未建立跨领域联结）*")
        }
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()

        // 金句回响
        sb.appendLine("## 金句回响")
        sb.appendLine()
        if (quotes.isNotEmpty()) {
            quotes.forEach { quote ->
                sb.appendLine("> 「${escapeMarkdown(quote.originalQuote)}」——*${escapeMarkdown(quote.author)}《${escapeMarkdown(quote.source)}》*")
                sb.appendLine()
                sb.appendLine("**延展思考**: ${escapeMarkdown(quote.extension)}")
                sb.appendLine()
            }
        } else {
            sb.appendLine("*（本阶段未生成金句回响）*")
        }
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()

        // 页脚
        qualityScore?.let { score ->
            sb.appendLine("**质量评分**: ${score.overallScore.toInt()}/100 (${score.grade()}) | ")
        }
        sb.appendLine("*报告生成时间: $timestamp | 发芽完成阶段: ${completedPhases.size}/4 | 耗时: ${processingTimeMs}ms*")

        val report = sb.toString()
        DebugLog.d("InsightSproutEngine: Markdown 报告生成完毕 length=${report.length}")
        return report
    }

    private fun escapeMarkdown(text: String): String {
        return text.replace("\\", "\\\\")
            .replace("|", "\\|")
            .replace("*", "\\*")
            .replace("`", "\\`")
            .replace("_", "\\_")
            .replace("#", "\\#")
    }

    private fun starRating(score: Float): String {
        val fullStars = (score * 5).toInt().coerceIn(0, 5)
        val emptyStars = 5 - fullStars
        return "[*]".repeat(fullStars) + "[ ]".repeat(emptyStars)
    }

    private fun releaseLargeStrings(vararg strings: String?) {
        strings.filterNotNull().forEach { _ ->
        }
        System.gc()
    }
}
