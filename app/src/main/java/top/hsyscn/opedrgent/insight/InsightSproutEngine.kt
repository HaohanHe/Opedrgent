package top.hsyscn.opedrgent.insight

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import top.hsyscn.opedrgent.utils.DebugLog
import java.text.SimpleDateFormat
import java.util.concurrent.ConcurrentHashMap
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
    private val webSearcher: ((String, Int) -> String)? = null,
) {

    private var _webSearcher: ((String, Int) -> String)? = webSearcher

    /**
     * 运行时设置联网搜索能力（用于 engine 已创建后注入搜索器）
     */
    fun setWebSearcher(searcher: (String, Int) -> String) {
        _webSearcher = searcher
    }

    /** 进度回调：当每个声音状态变化时触发 */
    var onVoiceProgress: ((SproutVoice, SproutVoiceStatus) -> Unit)? = null

    private val phaseCache = ConcurrentHashMap<String, Any?>()

    // 防止同一引擎实例被并发调用，避免 phaseCache 与内部状态被覆盖
    private val sproutMutex = Mutex()

    // ==================== 核心入口：思想空间发芽 ====================

    suspend fun sprout(
        inputText: String,
        config: SproutConfig = SproutConfig(),
        userContext: String? = null,
    ): SproutResult {
        sproutMutex.lock()
        try {
            return doSprout(inputText, config, userContext)
        } finally {
            sproutMutex.unlock()
        }
    }

    private suspend fun doSprout(
        inputText: String,
        config: SproutConfig,
        userContext: String?,
    ): SproutResult {
        phaseCache.clear()
        val startTime = System.currentTimeMillis()
        val inputSummary = inputText.take(80).let { if (inputText.length > 80) it + "..." else it }

        DebugLog.i("InsightSproutEngine: [ThinkingSpace] 开始发芽处理 inputLength=${inputText.length} summary=$inputSummary")

        val completedPhases = mutableSetOf<SproutPhase>()

        // ====== 阶段0: 种子提取（始终执行）======
        var seedsJson: String? = null
        runCatchingWithRecovery("种子提取", config.maxPhaseTimeoutSeconds.toLong() * 1000) {
            val effectiveContext = when {
                userContext != null -> userContext
                config.useContext -> ""
                else -> null
            }
            val prompt = SproutPromptBuilder.buildPhase1Prompt(
                inputText = inputText,
                context = effectiveContext,
            )
            llmCall(prompt)
        }.onSuccess { result ->
            seedsJson = result
            completedPhases.add(SproutPhase.SEED_EXTRACTION)
            DebugLog.i("InsightSproutEngine: [ThinkingSpace] 阶段0 种子提取完成")
        }

        val seeds = parseSeeds(seedsJson).also { phaseCache["seeds"] = it }
        val rawSeedsJson = seedsJson ?: "[]"

        // ====== 阶段1: 模板选择 ======
        val template = selectTemplate(rawSeedsJson, inputText, config)

        // ====== 阶段2: 多声音并发 ======
        val voiceStatuses = ConcurrentHashMap<SproutVoice, SproutVoiceStatus>()
        val voiceStatements = ConcurrentHashMap<SproutVoice, SproutVoiceStatement>()

        template.voices.forEach { voice ->
            voiceStatuses[voice] = SproutVoiceStatus.PENDING
        }

        // 并发执行所有声音
        coroutineScope {
            template.voices.map { voice ->
                async {
                    voiceStatuses[voice] = SproutVoiceStatus.SPEAKING
                    onVoiceProgress?.invoke(voice, SproutVoiceStatus.SPEAKING)

                    val voiceStartTime = System.currentTimeMillis()

                    runCatchingWithRecovery("${voice.displayName}发言", config.maxPhaseTimeoutSeconds.toLong() * 1000) {
                        val prompt = buildVoicePrompt(
                            voice = voice,
                            seedsJson = rawSeedsJson,
                            inputText = inputText,
                            existingStatements = voiceStatements.toMap(),
                        )
                        llmCall(prompt)
                    }.onSuccess { response ->
                        val statement = parseVoiceStatement(response, voice).copy(
                            processingMs = System.currentTimeMillis() - voiceStartTime,
                        )
                        voiceStatements[voice] = statement
                        voiceStatuses[voice] = SproutVoiceStatus.DONE
                        onVoiceProgress?.invoke(voice, SproutVoiceStatus.DONE)
                        DebugLog.i("InsightSproutEngine: [ThinkingSpace] ${voice.displayName} 发言完成")
                    }.onFailure { e ->
                        voiceStatuses[voice] = SproutVoiceStatus.SKIPPED
                        onVoiceProgress?.invoke(voice, SproutVoiceStatus.SKIPPED)
                        DebugLog.w("InsightSproutEngine: [ThinkingSpace] ${voice.displayName} 跳过: ${e.message}")
                    }
                }
            }.awaitAll()
        }

        // ====== 阶段2.5: 联网增强（如果有webSearcher且历史学家参与了）======
        var webEnhancedData = emptyList<SproutEnhancedConnection>()
        if (_webSearcher != null && template.voices.contains(SproutVoice.HISTORIAN) && config.enableWebSearch) {
            runCatchingWithRecovery("联网增强", 60_000L) {
                val seedKeywords = extractSearchKeywords(rawSeedsJson)
                require(seedKeywords.isNotEmpty()) { "无有效搜索关键词" }
                val searchResults = _webSearcher!!(seedKeywords.joinToString(" "), 5)
                require(searchResults.isNotBlank()) { "搜索结果为空" }
                val prompt = SproutPromptBuilder.buildPhase25WebEnhancePrompt(
                    seedsJson = rawSeedsJson,
                    searchResults = searchResults,
                    previousContext = "",
                )
                val response = llmCall(prompt)
                parseEnhancedConnections(response)
            }.onSuccess { result ->
                webEnhancedData = result
                completedPhases.add(SproutPhase.WEB_ENHANCE)
                DebugLog.i("InsightSproutEngine: [ThinkingSpace] 阶段2.5 联网增强完成 count=${result.size}")
            }.onFailure { e ->
                DebugLog.w("InsightSproutEngine: [ThinkingSpace] 阶段2.5 联网增强跳过: ${e.message}")
            }
        }

        // 将联网数据注入历史学家的发言上下文（如果历史学家已完成）
        if (webEnhancedData.isNotEmpty() && voiceStatements.containsKey(SproutVoice.HISTORIAN)) {
            val historianStmt = voiceStatements[SproutVoice.HISTORIAN]
            if (historianStmt != null) {
                val enhancedRefs = webEnhancedData.map { "[${it.domain}] ${it.reference}" }
                voiceStatements[SproutVoice.HISTORIAN] = historianStmt.copy(
                    references = historianStmt.references + enhancedRefs,
                )
            }
        }

        // ====== 阶段3: 综合所有声音 ======
        var synthesis: SproutSynthesis? = null
        if (voiceStatements.isNotEmpty()) {
            runCatchingWithRecovery("综合分析", config.maxPhaseTimeoutSeconds.toLong() * 1000) {
                synthesizeAll(voiceStatements.toMap(), rawSeedsJson, inputText)
            }.onSuccess { result ->
                synthesis = result
                completedPhases.add(SproutPhase.SHOCKING_INSIGHT)
                DebugLog.i("InsightSproutEngine: [ThinkingSpace] 阶段3 综合完成")
            }.onFailure { e ->
                DebugLog.w("InsightSproutEngine: [ThinkingSpace] 阶段3 综合跳过: ${e.message}")
            }
        }

        // ====== 阶段4: 可选的金句回响 ======
        var quotes = emptyList<SproutQuote>()
        val includeQuotes = config.includeQuotes
        if (includeQuotes && (synthesis != null || voiceStatements.isNotEmpty())) {
            runCatchingWithRecovery("金句回响", config.maxPhaseTimeoutSeconds.toLong() * 1000) {
                generateQuotes(synthesis, voiceStatements.toMap())
            }.onSuccess { result ->
                quotes = result
                completedPhases.add(SproutPhase.QUOTE_RESONANCE)
                DebugLog.i("InsightSproutEngine: [ThinkingSpace] 阶段4 金句完成 count=${result.size}")
            }.onFailure { e ->
                DebugLog.w("InsightSproutEngine: [ThinkingSpace] 阶段4 金句跳过: ${e.message}")
            }
        }

        val processingTimeMs = System.currentTimeMillis() - startTime

        DebugLog.i("InsightSproutEngine: [ThinkingSpace] 发芽完成 phases=${completedPhases.size}/5 voices=${voiceStatuses.values.count { it == SproutVoiceStatus.DONE }}/${template.voices.size} time=${processingTimeMs}ms")

        // ====== 构建 ThinkingSession ======
        val session = ThinkingSession(
            template = template,
            seeds = seeds,
            voiceStatements = voiceStatements.toMap(),
            voiceStatuses = voiceStatuses.toMap(),
            synthesis = synthesis,
            webEnhancedData = webEnhancedData,
            quotes = quotes,
            totalProcessingMs = processingTimeMs,
        )

        // ====== 向后兼容映射：ThinkingSession -> SproutResult 旧字段 ======
        val connections = buildLegacyConnections(session)
        val insights = buildLegacyInsights(session)
        val enhancedConnections = webEnhancedData.also { phaseCache["enhancedConnections"] = it }

        phaseCache["connections"] = connections
        phaseCache["insights"] = insights
        phaseCache["quotes"] = quotes

        val result = SproutResult(
            seeds = seeds,
            connections = connections,
            enhancedConnections = enhancedConnections,
            insights = insights,
            quotes = quotes,
            markdownReport = generateSessionReport(session),
            completedPhases = completedPhases.toSet(),
            inputText = inputText,
            processingTimeMs = processingTimeMs,
        )

        phaseCache["qualityScore"] = evaluateQuality(result)
        phaseCache["thinkingSession"] = session

        return result
    }

    /**
     * 向后兼容包装：保留原方法签名，内部委托给新架构
     */
    fun getThinkingSession(): ThinkingSession? =
        phaseCache["thinkingSession"] as? ThinkingSession

    // ==================== 新增方法：模板选择 ====================

    /**
     * 选择最佳分析模板（用户不可指定，完全由 LLM 根据内容自动决定）
     *
     * LLM 根据输入文本的长度、主题、复杂度等特征，
     * 从 4 种模板中选择最合适的一个。
     * 推荐失败时默认使用 PANORAMA 全景模式。
     */
    private suspend fun selectTemplate(
        seedsJson: String,
        inputText: String,
        config: SproutConfig,
    ): SproutTemplate {
        // 模板选择对用户透明：LLM 根据内容自动推荐
        return runCatchingWithRecovery("模板选择", 45_000L) {
            val prompt = """你是一位分析策略专家。根据以下信息，为本次分析选择最佳的分析模板。

## 输入文本摘要
${inputText.take(200)}

## 已提取的种子
$seedsJson

## 可用模板
${SproutTemplate.entries.joinToString("\n") { "- **${it.name}** (${it.displayName}): ${it.description}\n  参与声音: ${it.voices.joinToString(",") { it.displayName }}" }}

## 任务
根据输入内容的特征（长度、主题、复杂度、是否需要决策等），选择**一个最合适的模板**。
只需返回模板名称即可，例如：PANORAMA 或 DEBATE 或 TIME_TRAVEL 或 QUICK。

请只输出模板名称，不要其他文字。"""
            val response = llmCall(prompt).trim().uppercase()
            SproutTemplate.fromName(response) ?: SproutTemplate.PANORAMA
        }.getOrElse {
            DebugLog.w("InsightSproutEngine: [ThinkingSpace] 模板选择失败，使用默认全景模式: ${it.message}")
            SproutTemplate.PANORAMA
        }.also {
            DebugLog.i("InsightSproutEngine: [ThinkingSpace] 选定模板: ${it.displayName}")
        }
    }

    // ==================== 新增方法：声音 Prompt 构建 ====================

    /**
     * 为指定声音构建专属 prompt
     *
     * 每个声音看到相同的种子和输入文本，但角色设定不同。
     * 已完成的其他声音的发言也会被传入作为上下文参考，
     * 让后续声音可以引用或反驳前面的观点。
     */
    private fun buildVoicePrompt(
        voice: SproutVoice,
        seedsJson: String,
        inputText: String,
        existingStatements: Map<SproutVoice, SproutVoiceStatement>,
        webContext: String? = null,
    ): String {
        val sb = StringBuilder(2048)

        // 角色设定
        sb.appendLine(buildRolePrompt(voice))

        // 基础材料
        sb.appendLine("\n## 分析素材")
        sb.appendLine("\n### 用户原始输入")
        sb.appendLine(inputText.take(1500))
        sb.appendLine("\n### 已提取的核心种子")
        sb.appendLine(seedsJson)

        // 其他声音的已有发言（用于互动）
        if (existingStatements.isNotEmpty()) {
            sb.appendLine("\n### 其他声音的观点（供你参考或反驳）")
            existingStatements.forEach { (v, stmt) ->
                if (v != voice) {
                    sb.appendLine("- **${v.displayName}**: ${stmt.statement.take(200)}")
                    if (stmt.keyPoints.isNotEmpty()) {
                        sb.appendLine("  要点: ${stmt.keyPoints.joinToString("; ") { it.take(50) }}")
                    }
                }
            }
        }

        // 联网资料（如果有）
        if (!webContext.isNullOrBlank()) {
            sb.appendLine("\n### 参考资料（来自网络搜索）")
            sb.appendLine(webContext.take(800))
        }

        // 输出要求
        sb.appendLine("""
            |
            |## 你的任务
            |基于以上素材，以「${voice.description}」的视角进行分析。
            |${buildVoiceTaskInstruction(voice)}
            |
            |## 输出格式（严格遵守 JSON 格式）
            |```json
            |{
            |  "statement": "你的核心观点（3-6句话，有态度、有深度）",
            |  "keyPoints": ["要点1", "要点2", "要点3"],
            |  "references": ["参考资料1", "参考资料2"],
            |  "sentiment": ${voice.defaultSentiment},
            |  "tagline": "一句话标签"
            |}
            |```
            |
            |- `statement`: 你的核心发言内容，要有鲜明的立场和独特的角度
            |- `keyPoints`: 3-5个关键要点，每个不超过20字
            |- `references`: 你引用的来源（书籍/文章/事件/人物），如果没有可留空数组
            |- `sentiment`: 态度倾向（-1到1之间的小数，负数表示反对/质疑，正数表示支持/肯定，0表示中立）
            |- `tagline`: 一个词或短语概括你的核心立场
            |
            |请直接输出 JSON，不要添加其他说明文字。""".trimMargin())

        return sb.toString()
    }

    /**
     * 构建各声音的角色 prompt
     */
    private fun buildRolePrompt(voice: SproutVoice): String = when (voice) {
        SproutVoice.INSIGHT -> """你是一位洞察者，擅长发现反直觉的关联和意想不到的角度。

你的特质：
- 总能看到别人忽略的联系
- 善于将不同领域的概念桥接起来
- 提出的观点往往让人"恍然大悟"
- 不满足于表面现象，总是追问"为什么"

你的发言风格：犀利、意外、启发性强。"""
        SproutVoice.CRITIQUE -> """你是一位批判者，专门挑战假设、找出漏洞和盲点。

你的特质：
- 对任何观点都持怀疑态度
- 善于找出论证中的逻辑漏洞
- 会考虑最坏的情况和潜在风险
- 不怕提出不受欢迎的反面意见

你的发言风格：尖锐、严谨、建设性质疑。"""
        SproutVoice.SUPPORT -> """你是一位支持者，专注于肯定价值并找到可行的路径。

你的特质：
- 能在看似混乱的局面中找到积极因素
- 善于发现被忽视的优势和机会
- 提出具体可行的建议而非空泛鼓励
- 相信问题总有解决方案

你的发言风格：温暖、务实、有建设性。"""
        SproutVoice.HISTORIAN -> """你是一位历史学家，擅长古今中外的文明参照。

你的特质：
- 能将当前话题映射到历史上的相似情境
- 引用具体的历史事件、人物、著作作为参照
- 从历史纵深看问题，不局限于当下
- 中国史和世界史都能信手拈来

你的发言风格：厚重、博学、以史为鉴。引用必须注明具体出处。"""
        SproutVoice.FUTURIST -> """你是一位未来派思想家，擅长推演趋势和想象可能的后果。

你的特质：
- 从当前趋势推演未来可能的发展路径
- 考虑技术变革对社会的影响
- 敢于设想多种可能的未来场景
- 关注长期影响而非短期得失

你的发言风格：前瞻性、想象力丰富、多情景推演。"""
    }

    /**
     * 各声音的具体任务指令
     */
    private fun buildVoiceTaskInstruction(voice: SproutVoice): String = when (voice) {
        SproutVoice.INSIGHT -> """
给出 **1 个最有价值的反直觉洞察**。
这个洞察应该：
- 打破读者对这一话题的固有认知
- 用简洁有力的语言表达（像一句可以传播的金句）
- 有具体的论据支撑，不是空洞的断言"""
        SproutVoice.CRITIQUE -> """
找出 **1-2 个最大的漏洞或风险点**。
你的批判应该：
- 针对性明确，指出具体哪里有问题
- 不仅说"不对"，还要解释"为什么不对"
- 如果有可能，提出更好的替代思路"""
        SproutVoice.SUPPORT -> """
肯定 **1-2 个核心价值或可行方向**。
你的支持应该：
- 具体说明哪里有价值、为什么有价值
- 给出切实可行的下一步建议
- 避免空洞的赞美，每条支持都要有实质内容"""
        SproutVoice.HISTORIAN -> """
找到 **1-2 个历史镜像**。
你的历史参照应该：
- 引用具体的历史时期、事件或人物
- 说明历史案例与当前话题的相似之处
- 从历史中提炼对当下的启示
- 必须注明出处（如：《史记·货殖列传》、亚当·斯密《国富论》1776年）"""
        SproutVoice.FUTURIST -> """
推演 **1-2 种可能的未来场景**。
你的未来推演应该：
- 基于当前趋势进行合理外推
- 考虑至少一种乐观场景和一种需要警惕的场景
- 给出时间框架（短期/中期/长期）
- 提出应对建议"""
    }

    /** 各声音的默认 sentiment 值 */
    private val SproutVoice.defaultSentiment: Float
        get() = when (this) {
            SproutVoice.INSIGHT -> 0f
            SproutVoice.CRITIQUE -> -0.5f
            SproutVoice.SUPPORT -> 0.7f
            SproutVoice.HISTORIAN -> 0f
            SproutVoice.FUTURIST -> 0.2f
        }

    // ==================== 新增方法：声音发言解析 ====================

    /**
     * 解析单个声音的发言 JSON 为 SproutVoiceStatement
     */
    private fun parseVoiceStatement(rawResponse: String, voice: SproutVoice): SproutVoiceStatement {
        val cleaned = cleanJsonInput(rawResponse) ?: rawResponse.trim()
        return try {
            val statement = extractStringValue(cleaned, "statement").ifBlank {
                // fallback: 取整个响应的前300字作为 statement
                rawResponse.replace(Regex("""```(?:json)?\s*\n?"""), "").replace("```", "").trim().take(300)
            }
            val keyPoints = extractIntArray(cleaned, "keyPoints").ifEmpty {
                // fallback: 从 statement 中按句号分割取前几句作为 keyPoints
                statement.split(Regex("[。！？.!?\n]")).filter { it.trim().length in 5..30 }.take(3)
            }
            val references = extractIntArray(cleaned, "references")
            val sentiment = extractFloatValue(cleaned, "sentiment", voice.defaultSentiment).coerceIn(-1f, 1f)
            val tagline = extractStringValue(cleaned, "tagline").ifBlank {
                voice.displayName
            }

            SproutVoiceStatement(
                voice = voice,
                statement = statement.trim(),
                keyPoints = keyPoints.map { it.trim() }.filter { it.isNotEmpty() },
                references = references.map { it.trim() }.filter { it.isNotEmpty() },
                sentiment = sentiment,
                tagline = tagline.trim(),
            )
        } catch (e: Exception) {
            DebugLog.e("InsightSproutEngine: [ThinkingSpace] 解析${voice.displayName}发言异常，使用降级方案", e)
            SproutVoiceStatement(
                voice = voice,
                statement = rawResponse.replace(Regex("""```(?:json)?\s*\n?"""), "").replace("```", "").trim().take(500),
                tagline = voice.displayName,
            )
        }
    }

    // ==================== 新增方法：综合所有声音 ====================

    /**
     * 综合所有声音的发言，生成最终的综合结论
     *
     * 这不是简单拼接，而是有机综合：
     * - 找出共识点和分歧点
     * - 提炼核心发现
     * - 给出可行建议
     */
    private suspend fun synthesizeAll(
        statements: Map<SproutVoice, SproutVoiceStatement>,
        seedsJson: String,
        inputText: String,
    ): SproutSynthesis {
        val prompt = buildSynthesisPrompt(statements, seedsJson, inputText)
        val response = llmCall(prompt)
        return parseSynthesis(response)
    }

    /**
     * 构建 LLM 综合分析的 prompt
     */
    private fun buildSynthesisPrompt(
        statements: Map<SproutVoice, SproutVoiceStatement>,
        seedsJson: String,
        inputText: String,
    ): String {
        val sb = StringBuilder(2048)

        sb.appendLine("""你是一位综合分析师，擅长整合多方观点形成有深度的结论。

## 背景
以下是针对一段文本的多角度分析结果，每个声音代表一种独特的分析视角。

## 原始输入摘要
${inputText.take(300)}

## 核心种子
$seedsJson

## 各声音的分析结论""")

        statements.forEach { (voice, stmt) ->
            sb.appendLine("\n### ${voice.displayName} (${voice.iconChar})")
            sb.appendLine("**核心观点**: ${stmt.statement}")
            if (stmt.keyPoints.isNotEmpty()) {
                sb.appendLine("**要点**: ${stmt.keyPoints.joinToString(" | ")}")
            }
            if (stmt.tagline.isNotBlank()) {
                sb.appendLine("**标签**: ${stmt.tagline}")
            }
            sb.appendLine("**态度倾向**: ${sentimentLabel(stmt.sentiment)}")
        }

        sb.appendLine("""
            |
            |## 任务
            |基于以上所有声音的分析，生成一份有机的综合报告：
            |
            |1. **核心发现**（coreFinding）：一句话总结这次分析最重要的发现
            |2. **共识点**（consensus）：各声音之间达成一致的 2-3 个观点
            |3. **分歧点**（disagreements）：各声音之间存在争议或互补的 2-3 个点（这是最有价值的部分）
            |4. **行动建议**（recommendations）：基于全部分析给出的 2-3 条可行建议
            |5. **收尾问题**（closingQuestion）：一个发人深省的问题，留给用户继续思考
            |6. **深度评估**（depthAssessment）：评估这次分析的深度和质量（一句话）
            |
            |## 输出格式（严格遵守 JSON 格式）
            |```json
            |{
            |  "coreFinding": "一句话核心发现",
            |  "consensus": ["共识点1", "共识点2"],
            |  "disagreements": ["分歧点1", "分歧点2"],
            |  "recommendations": ["建议1", "建议2"],
            |  "closingQuestion": "发人深省的问题",
            |  "depthAssessment": "深度评估"
            |}
            |```
            |
            |请直接输出 JSON，不要添加其他说明文字。""".trimMargin())

        return sb.toString()
    }

    /**
     * 将 sentiment 数值转为中文标签
     */
    private fun sentimentLabel(value: Float): String = when {
        value >= 0.5f -> "正面支持"
        value > 0f -> "偏正面"
        value == 0f -> "中立"
        value > -0.5f -> "偏负面"
        else -> "强烈质疑"
    }

    /**
     * 解析综合结果 JSON
     */
    private fun parseSynthesis(rawResponse: String): SproutSynthesis {
        val cleaned = cleanJsonInput(rawResponse) ?: rawResponse.trim()
        return try {
            SproutSynthesis(
                coreFinding = extractStringValue(cleaned, "coreFinding")
                    .ifBlank { extractStringValue(cleaned, "finding") }
                    .ifBlank { "综合分析完成" },
                consensus = extractIntArray(cleaned, "consensus"),
                disagreements = extractIntArray(cleaned, "disagreements"),
                recommendations = extractIntArray(cleaned, "recommendations"),
                closingQuestion = extractStringValue(cleaned, "closingQuestion")
                    .ifBlank { extractStringValue(cleaned, "question") },
                depthAssessment = extractStringValue(cleaned, "depthAssessment")
                    .ifBlank { extractStringValue(cleaned, "assessment") },
            )
        } catch (e: Exception) {
            DebugLog.e("InsightSproutEngine: [ThinkingSpace] 解析综合结果异常", e)
            SproutSynthesis(coreFinding = "综合分析完成（降级模式）")
        }
    }

    // ==================== 新增方法：金句生成 ====================

    /**
     * 基于综合结果和各声音发言生成金句回响
     */
    private suspend fun generateQuotes(
        synthesis: SproutSynthesis?,
        statements: Map<SproutVoice, SproutVoiceStatement>,
    ): List<SproutQuote> {
        val prompt = buildQuotePrompt(synthesis, statements)
        val response = llmCall(prompt)
        return parseQuotes(response).also {
            DebugLog.i("InsightSproutEngine: [ThinkingSpace] 金句解析完成 count=${it.size}")
        }
    }

    /**
     * 构建金句生成的 prompt
     */
    private fun buildQuotePrompt(
        synthesis: SproutSynthesis?,
        statements: Map<SproutVoice, SproutVoiceStatement>,
    ): String {
        val sb = StringBuilder(2048)

        sb.appendLine("""你是一位博学家和文学评论家，精通人类知识库并能将个人观点与经典智慧桥接。

## 分析背景
以下是一次多角度深度分析的结果。""")

        synthesis?.let {
            sb.appendLine("\n### 核心发现")
            sb.appendLine(it.coreFinding)
            if (it.consensus.isNotEmpty()) {
                sb.appendLine("\n### 共识")
                it.consensus.forEach { s -> sb.appendLine("- $s") }
            }
            if (it.disagreements.isNotEmpty()) {
                sb.appendLine("\n### 分歧与碰撞")
                it.disagreements.forEach { d -> sb.appendLine("- $d") }
            }
        }

        if (statements.isNotEmpty()) {
            sb.appendLine("\n### 各声音精华")
            statements.forEach { (voice, stmt) ->
                sb.appendLine("- **${voice.displayName}**: ${stmt.statement.take(100)}")
            }
        }

        sb.appendLine("""

## 任务
生成 **1 条金句回响**，将上述分析与人类经典建立桥梁。

## 金句结构
1. **原文引用**：经典名言（注明出处和作者）
2. **延展思考**：结合当前话题的原创延展（2-3句话）

## 可引用来源
- 东方思想：老子《道德经》、庄子、《论语》、《孟子》
- 中国史学：司马迁《史记》、司马光《资治通鉴》
- 中国文学：苏轼、鲁迅、张爱玲
- 西方古典：柏拉图、亚里士多德、马基雅维利
- 近现代：马克思、韦伯、福柯
- 科学：费曼、道金斯
- 经济学：卡尼曼、塞勒
- 文学：加缪、卡夫卡、奥威尔
- 心理学：艾克曼

## 输出格式（严格遵守 JSON 格式）
```json
{
  "quotes": [
    {
      "originalQuote": "经典原句",
      "source": "出处",
      "author": "作者",
      "extension": "延展思考"
    }
  ]
}
```

请直接输出 JSON，不要添加其他说明文字。""")
        return sb.toString()
    }

    // ==================== 新增方法：新版 Markdown 报告 ====================

    /**
     * 基于 ThinkingSession 生成新版 Markdown 报告
     */
    private fun generateSessionReport(session: ThinkingSession): String {
        val sb = StringBuilder(4096)
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
        val qualityScore = (phaseCache["qualityScore"] as? SproutQualityScore)

        // 报告头部
        sb.appendLine("# 思想空间报告")
        sb.appendLine()
        sb.appendLine("> 模板: **${escapeMarkdown(session.template.displayName)}** | ")
        sb.appendLine("参与声音: ${session.template.voices.joinToString(", ") { "${it.iconChar}${it.displayName}" }}")
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()

        // 种子区域
        sb.appendLine("## 种子")
        sb.appendLine()
        if (session.seeds.isNotEmpty()) {
            sb.appendLine("| # | 核心概念 | 描述 | 关键词 |")
            sb.appendLine("|---|---------|------|--------|")
            session.seeds.forEachIndexed { index, seed ->
                val kw = seed.keywords.joinToString(", ")
                sb.appendLine("| ${index + 1} | **${escapeMarkdown(seed.concept)}** | ${escapeMarkdown(seed.description)} | ${escapeMarkdown(kw)} |")
            }
        } else {
            sb.appendLine("*（未成功提取种子）*")
        }
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()

        // 声音讨论区
        sb.appendLine("## 多维对话")
        sb.appendLine()
        session.orderedVoices.forEach { voice ->
            val status = session.voiceStatuses[voice] ?: SproutVoiceStatus.PENDING
            val stmt = session.voiceStatements[voice]

            sb.appendLine("### ${voice.iconChar} ${voice.displayName} [${status.name}]")
            sb.appendLine()
            sb.appendLine("*${voice.description}*")
            sb.appendLine()

            if (stmt != null) {
                sb.appendLine("> ${escapeMarkdown(stmt.statement)}")
                sb.appendLine()
                if (stmt.keyPoints.isNotEmpty()) {
                    sb.appendLine("**要点**:")
                    stmt.keyPoints.forEach { point ->
                        sb.appendLine("- $point")
                    }
                    sb.appendLine()
                }
                if (stmt.tagline.isNotBlank()) {
                    sb.appendLine("**标签**: `${escapeMarkdown(stmt.tagline)}`")
                    sb.appendLine()
                }
                if (stmt.references.isNotEmpty()) {
                    sb.appendLine("**参考资料**:")
                    stmt.references.forEach { ref ->
                        sb.appendLine("- $ref")
                    }
                    sb.appendLine()
                }
            } else {
                sb.appendLine("*（该声音未能参与讨论）*")
                sb.appendLine()
            }
            sb.appendLine("---")
            sb.appendLine()
        }

        // 综合结论区
        session.synthesis?.let { syn ->
            sb.appendLine("## 综合结论")
            sb.appendLine()
            sb.appendLine("> **${escapeMarkdown(syn.coreFinding)}**")
            sb.appendLine()

            if (syn.consensus.isNotEmpty()) {
                sb.appendLine("**共识**:")
                syn.consensus.forEach { c -> sb.appendLine("- $c") }
                sb.appendLine()
            }

            if (syn.disagreements.isNotEmpty()) {
                sb.appendLine("**分歧与碰撞**（最有价值的部分）:")
                syn.disagreements.forEach { d -> sb.appendLine("- $d") }
                sb.appendLine()
            }

            if (syn.recommendations.isNotEmpty()) {
                sb.appendLine("**行动建议**:")
                syn.recommendations.forEach { r -> sb.appendLine("- $r") }
                sb.appendLine()
            }

            if (syn.closingQuestion.isNotBlank()) {
                sb.appendLine("**留给你的思考**:")
                sb.appendLine("> ${escapeMarkdown(syn.closingQuestion)}")
                sb.appendLine()
            }

            if (syn.depthAssessment.isNotBlank()) {
                sb.appendLine("*${escapeMarkdown(syn.depthAssessment)}*")
                sb.appendLine()
            }
            sb.appendLine("---")
            sb.appendLine()
        }

        // 联网佐证
        if (session.webEnhancedData.isNotEmpty()) {
            sb.appendLine("## 联网佐证")
            sb.appendLine()
            session.webEnhancedData.forEach { ec ->
                sb.appendLine("### ${escapeMarkdown(ec.seedConcept)} [${escapeMarkdown(ec.domain)}]")
                sb.appendLine()
                sb.appendLine("- **参考资料**: ${escapeMarkdown(ec.reference)}")
                sb.appendLine("- **洞察**: ${escapeMarkdown(ec.insight)}")
                sb.appendLine("- 相关度: ${String.format("%.1f", ec.relevanceScore)}")
                sb.appendLine()
            }
            sb.appendLine("---")
            sb.appendLine()
        }

        // 金句回响
        if (session.quotes.isNotEmpty()) {
            sb.appendLine("## 金句回响")
            sb.appendLine()
            session.quotes.forEach { quote ->
                sb.appendLine("> 「${escapeMarkdown(quote.originalQuote)}」——*${escapeMarkdown(quote.author)}《${escapeMarkdown(quote.source)}》*")
                sb.appendLine()
                sb.appendLine("**延展思考**: ${escapeMarkdown(quote.extension)}")
                sb.appendLine()
            }
            sb.appendLine("---")
            sb.appendLine()
        }

        // 页脚
        qualityScore?.let { score ->
            sb.appendLine("**质量评分**: ${score.overallScore.toInt()}/100 (${score.grade()}) | ")
        }
        sb.appendLine("*报告时间: $timestamp | 声音完成: ${session.completedVoicesCount}/${session.totalVoicesCount} | 耗时: ${session.totalProcessingMs}ms*")

        val report = sb.toString()
        DebugLog.d("InsightSproutEngine: [ThinkingSession] Markdown 报告生成完毕 length=${report.length}")
        return report
    }

    /**
     * 向后兼容：旧版 generateMarkdownReport 方法签名保留
     * 内部委托给 generateSessionReport
     */
    @Suppress("unused")
    private fun generateMarkdownReport(
        inputText: String,
        seeds: List<SproutSeed>,
        connections: List<SproutConnection>,
        enhancedConnections: List<SproutEnhancedConnection>,
        insights: List<SproutInsight>,
        quotes: List<SproutQuote>,
        completedPhases: Set<SproutPhase>,
        processingTimeMs: Long,
    ): String {
        // 构造一个最小化的 ThinkingSession 用于报告生成
        val session = ThinkingSession(
            template = SproutTemplate.PANORAMA,
            seeds = seeds,
            synthesis = null,
            webEnhancedData = enhancedConnections,
            quotes = quotes,
            totalProcessingMs = processingTimeMs,
        )
        return generateSessionReport(session)
    }

    // ==================== 向后兼容映射方法 ====================

    /**
     * 从 ThinkingSession 映射回旧的 connections 列表
     * 策略：优先使用历史学家的发言构建连接，其次用洞察者的发言补充
     */
    private fun buildLegacyConnections(session: ThinkingSession): List<SproutConnection> {
        val results = mutableListOf<SproutConnection>()

        // 从历史学家发言中提取跨领域关联
        session.voiceStatements[SproutVoice.HISTORIAN]?.let { stmt ->
            stmt.references.forEachIndexed { idx, ref ->
                results.add(SproutConnection(
                    domain = if (idx == 0) "历史参照" else "文明参照",
                    analogyOrCase = ref.take(100),
                    analysis = stmt.statement.take(200),
                    unexpectedness = 0.65f,
                    historicalReference = ref,
                    sourceType = if (session.webEnhancedData.isNotEmpty()) "web_searched" else "llm_guess",
                ))
            }
        }

        // 从洞察者发言中补充
        session.voiceStatements[SproutVoice.INSIGHT]?.let { stmt ->
            if (results.isEmpty()) {
                results.add(SproutConnection(
                    domain = "跨领域洞察",
                    analogyOrCase = stmt.statement.take(100),
                    analysis = stmt.statement.take(300),
                    unexpectedness = 0.75f,
                    sourceType = "llm_guess",
                ))
            }
        }

        // 从综合结论的推荐中补充
        session.synthesis?.recommendations?.forEachIndexed { idx, rec ->
            if (results.size < 5) {
                results.add(SproutConnection(
                    domain = "综合建议",
                    analogyOrCase = rec.take(80),
                    analysis = rec,
                    unexpectedness = 0.55f,
                    sourceType = "llm_guess",
                ))
            }
        }

        return results.distinctBy { it.domain + it.analogyOrCase }.take(8)
    }

    /**
     * 从 ThinkingSession 映射回旧的 insights 列表
     * 策略：综合结论的核心发现 + 洞察者发言 + 分歧点
     */
    private fun buildLegacyInsights(session: ThinkingSession): List<SproutInsight> {
        val results = mutableListOf<SproutInsight>()

        // 核心发现作为第一条洞察
        session.synthesis?.coreFinding?.takeIf { it.isNotBlank() }?.let {
            results.add(SproutInsight(content = it, counterIntuitiveScore = 0.8f))
        }

        // 洞察者发言
        session.voiceStatements[SproutVoice.INSIGHT]?.let { stmt ->
            results.add(SproutInsight(
                content = stmt.statement,
                counterIntuitiveScore = 0.85f,
                tags = stmt.keyPoints,
            ))
        }

        // 分歧点作为额外的洞察（往往是最有价值的部分）
        session.synthesis?.disagreements?.forEach { disagreement ->
            if (results.size < 4) {
                results.add(SproutInsight(
                    content = disagreement,
                    counterIntuitiveScore = 0.9f,
                ))
            }
        }

        // 批判者的漏洞发现也很有价值
        session.voiceStatements[SproutVoice.CRITIQUE]?.let { stmt ->
            if (results.size < 4) {
                results.add(SproutInsight(
                    content = stmt.statement,
                    counterIntuitiveScore = 0.78f,
                    tags = listOf("批判视角"),
                ))
            }
        }

        return results.distinctBy { it.content }.take(4)
    }

    // ==================== 错误恢复机制（完整保留）====================

    private suspend fun <T> runCatchingWithRecovery(
        phaseName: String,
        timeoutMs: Long,
        block: suspend () -> T,
    ): Result<T> {
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

    // ==================== 质量评估（扩展覆盖新维度）====================

    fun evaluateQuality(result: SproutResult): SproutQualityScore {
        val seedCount = result.seeds.size
        val connectionCount = result.connections.size
        val insightCount = result.insights.size
        val quoteCount = result.quotes.size

        val avgUnexpectedness = if (result.connections.isNotEmpty()) {
            result.connections.map { it.unexpectedness }.average().toFloat().coerceIn(0f, 1f)
        } else 0f

        val totalPhases = result.completedPhases.size

        // 新增：检查是否有 ThinkingSession 数据来增强评估
        val session = phaseCache["thinkingSession"] as? ThinkingSession
        val voiceDiversityBonus = session?.let {
            val doneCount = it.completedVoicesCount
            val totalCount = it.totalVoicesCount
            if (totalCount > 0) (doneCount * 5).coerceAtMost(15) else 0
        } ?: 0

        val synthesisDepthBonus = session?.synthesis?.let { syn ->
            val depthScore = when {
                syn.disagreements.isNotEmpty() -> 10   // 有分歧说明分析深入
                syn.recommendations.isNotEmpty() -> 5  // 有建议说明实用
                else -> 0
            }
            depthScore
        } ?: 0

        val seedScore = (seedCount.coerceAtLeast(0) * 15).coerceAtMost(30)
        val connectionScore = (connectionCount.coerceAtLeast(0) * 12).coerceAtMost(36)
        val insightScore = (insightCount.coerceAtLeast(0) * 18).coerceAtMost(36)
        val quoteScore = (quoteCount.coerceAtLeast(1) * 10).coerceAtMost(10)
        val unexpectednessBonus = (avgUnexpectedness * 20).toInt()

        val overallScore = ((seedScore + connectionScore + insightScore + quoteScore +
            unexpectednessBonus + voiceDiversityBonus + synthesisDepthBonus)
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
            DebugLog.i("InsightSproutEngine: 质量评估 score=${it.overallScore} grade=${it.grade()} diversityBonus=$voiceDiversityBonus depthBonus=$synthesisDepthBonus")
        }
    }

    fun getCachedQualityScore(): SproutQualityScore? =
        phaseCache["qualityScore"] as? SproutQualityScore

    // ==================== JSON 解析核心（完整保留不动）====================

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

    // ==================== 各阶段解析（完整保留不动）====================

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
                val historicalReference = extractStringValue(obj, "historicalReference")
                val sourceType = extractStringValue(obj, "sourceType")
                    .ifBlank { "llm_guess" }

                if (domain.isNotBlank() && analogyOrCase.isNotBlank()) {
                    SproutConnection(
                        domain = domain.trim(),
                        analogyOrCase = analogyOrCase.trim(),
                        analysis = analysis.trim().ifBlank { analogyOrCase },
                        unexpectedness = unexpectedness.coerceIn(0f, 1f),
                        historicalReference = historicalReference.trim(),
                        sourceType = sourceType.trim(),
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

    // ==================== Phase 2.5 联网增强解析（完整保留不动）====================

    private fun parseEnhancedConnections(jsonStr: String?): List<SproutEnhancedConnection> {
        if (jsonStr.isNullOrBlank()) return emptyList()
        return try {
            val jsonObjects = extractJsonArray(jsonStr, "enhancedConnections")
            if (jsonObjects.isEmpty()) {
                DebugLog.w("InsightSproutEngine: parseEnhancedConnections 未找到 JSON 数组")
                return emptyList()
            }
            jsonObjects.mapNotNull { obj ->
                val seedConcept = extractStringValue(obj, "seedConcept")
                val domain = extractStringValue(obj, "domain")
                val reference = extractStringValue(obj, "reference")
                val insight = extractStringValue(obj, "insight")
                val relevanceScore = extractFloatValue(obj, "relevanceScore")

                if (seedConcept.isNotBlank() && reference.isNotBlank() && insight.isNotBlank()) {
                    SproutEnhancedConnection(
                        seedConcept = seedConcept.trim(),
                        domain = domain.trim().ifBlank { "综合" },
                        reference = reference.trim(),
                        insight = insight.trim(),
                        relevanceScore = relevanceScore.coerceIn(0f, 1f),
                    )
                } else null
            }.also {
                DebugLog.i("InsightSproutEngine: parseEnhancedConnections 解析成功 count=${it.size}")
            }
        } catch (e: Exception) {
            DebugLog.e("InsightSproutEngine: parseEnhancedConnections 异常", e)
            emptyList()
        }
    }

    // ==================== 搜索关键词提取（完整保留不动）====================

    /**
     * 从 Phase 1 的种子 JSON 中智能提取搜索关键词
     * 优先使用 concept 字段，其次 keywords，避免太泛的词
     */
    private fun extractSearchKeywords(seedJson: String): List<String> {
        val cleaned = cleanJsonInput(seedJson) ?: return emptyList()
        val keywords = mutableListOf<String>()

        // 提取所有 concept 字段
        val conceptPattern = Regex(""""concept"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        conceptPattern.findAll(cleaned).forEach { match ->
            val concept = unescapeJson(match.groupValues[1]).trim()
            if (concept.length in 2..20 && concept.none { it in "{}[]" }) {
                keywords.add(concept)
            }
        }

        // 如果 concept 不够，补充 keywords
        if (keywords.size < 3) {
            val kwPattern = Regex(""""keywords"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
            kwPattern.find(cleaned)?.let { match ->
                val content = match.groupValues[1]
                Regex(""""((?:[^"\\]|\\.)*)"""").findAll(content).forEach { kwMatch ->
                    val kw = unescapeJson(kwMatch.groupValues[1]).trim()
                    if (kw.length in 2..15 && kw !in keywords) {
                        keywords.add(kw)
                    }
                }
            }
        }

        // 过滤掉过于泛化的词
        val genericWords = setOf(
            "问题", "方法", "分析", "研究", "发展", "影响", "关系",
            "变化", "过程", "结果", "因素", "特点", "作用", "意义",
            "important", "analysis", "development", "research", "impact",
            "problem", "method", "process", "result", "factor",
        )

        return keywords
            .filter { it.lowercase() !in genericWords }
            .distinct()
            .take(5)
            .also {
                DebugLog.i("InsightSproutEngine: 提取搜索关键词: ${it.joinToString(", ")}")
            }
    }

    // ==================== 工具方法（完整保留不动）====================

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
