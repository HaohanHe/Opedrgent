package top.hsyscn.opedrgent.interview

import top.hsyscn.opedrgent.utils.DebugLog

/**
 * 海马体记忆系统 — 对话注意力锚定层。
 *
 * ## 问题
 *
 * 豆包/飞书的电话模式存在一个致命缺陷：AI 在长对话中注意力会漂移。
 * 面试聊着聊着变成闲聊，答辩问着问着跑题。
 *
 * **根因**：LLM 上下文窗口是 FIFO 队列，早期目标信息被后续对话冲刷，
 * AI "忘了" 自己在干什么。
 *
 * ## 解决方案
 *
 * 模仿大脑海马体(Hippocampus)的四项核心功能：
 *
 * 1. **目标锚定 (Goal Anchor)** — 锁定对话目标，永不丢失
 * 2. **漂移检测 (Drift Detection)** — 每轮检测是否偏离主题
 * 3. **注意力提醒 (Attention Reminder)** — 漂移时注入提醒，拉回正轨
 * 4. **上下文保护 (Context Protection)** — 关键信息不被窗口冲刷
 *
 * ## 架构位置
 *
 * ┌─────────────┐    注入注意力上下文     ┌─────────────┐
 * │ Hippocampus │ ──────────────────→   │   LLM API    │
 * │  Memory     │ ←── 检测漂移 ──────── │              │
 * │             │                       │              │
 * ├ Goal Anchor │                       │ (普通调用)    │
 * ├ Drift Detect│                       │ (无注意力管理)│
 * ├ Attention   │                       │              │
 * └ Context Prot│                       └─────────────┘
 *
 * 没有 Hippocampus: LLM 直接调用 -> 容易跑偏 [X]
 * 有 Hippocampus:  LLM 调用前先过海马体 -> 始终聚焦 [OK]
 *
 * ## 使用方式
 *
 * ```kotlin
 * val hippo = HippocampusMemory(config)
 * hippo.anchorGoal()
 *
 * // 每轮对话调用:
 * val attentionContext = hippo.prepareTurnContext(
 *     turnIndex = 3,
 *     userMessage = "我之前做过一个电商项目...",
 *     lastAiResponse = "请详细说说这个项目的技术架构...",
 * )
 *
 * // 将 attentionContext 注入到 LLM messages 中
 * messages.add(system(attentionContext))
 * messages.add(user(userMessage))
 * val response = llm.chat(messages)
 *
 * // 对话结束后检查漂移报告
 * val driftReport = hippo.getDriftReport()
 * ```
 */
class HippocampusMemory(
    private val config: InterviewConfig,
) {

    companion object {
        private const val TAG = "HippocampusMemory"
        /** 快照间隔：每 N 轮做一次关键信息快照 */
        const val SNAPSHOT_INTERVAL = 3
        /** 关键词提取最大数量 */
        private const val MAX_KEYWORDS = 8
        /** 最大关键话题数量 */
        private const val MAX_KEY_TOPICS = 15
    }

    // ==================== 目标锚定 ====================

    /**
     * 目标锚点 — 对话的核心目标，永不丢失。
     *
     * 由 InterviewConfig 自动生成，包含：
     * - primaryGoal: 主要目标（一句话）
     * - keyTopics: 必须覆盖的关键话题列表
     * - forbiddenTopics: 禁止偏离的话题
     * - successCriteria: 判定成功的标准
     */
    data class GoalAnchor(
        val primaryGoal: String,                    // 一句话目标："评估候选人后端工程能力"
        val keyTopics: List<String>,                 // 必须覆盖的话题
        val forbiddenTopics: List<String> = emptyList(), // 禁止话题
        val successCriteria: List<String> = emptyList(),  // 成功标准
        val anchoredAt: Long = System.currentTimeMillis(),
    )

    private var goalAnchor: GoalAnchor? = null

    /**
     * 锚定目标 — 从 config 提取并锁定对话目标。
     *
     * 只在对话开始时调用一次，之后不可更改（除非 reset）。
     */
    fun anchorGoal(): GoalAnchor {
        val scenario = config.getEffectiveScenarioDescription()
        val primaryGoal = buildString {
            when (config.type) {
                InterviewType.JOB_INTERVIEW -> append("评估候选人在「${config.position}」岗位上的综合能力")
                InterviewType.THESIS_DEFENSE -> append("评审候选人的「${config.position}」答辩质量")
                InterviewType.SCENARIO -> append("完成「${scenario}」场景的目标任务")
                InterviewType.CUSTOM -> append(scenario)
            }
            if (config.company.isNotBlank()) {
                append("（${config.company}）")
            }
        }

        // 从材料中提取关键话题
        val keyTopics = extractKeyTopicsFromMaterials()

        // 禁止话题（通用 + 场景特定）
        val forbiddenTopics = listOf(
            "闲聊", "天气", "吃饭", "周末安排", "个人隐私",
            "与面试/答辩无关的个人话题",
        )

        val anchor = GoalAnchor(
            primaryGoal = primaryGoal,
            keyTopics = keyTopics,
            forbiddenTopics = forbiddenTopics,
            successCriteria = listOf(
                "覆盖所有关键话题",
                "保持专业角色定位",
                "给出有价值的反馈或评估",
            ),
        )
        goalAnchor = anchor
        DebugLog.i(TAG, "目标已锚定: ${anchor.primaryGoal}")
        return anchor
    }

    /**
     * 从材料中提取关键话题。
     */
    private fun extractKeyTopicsFromMaterials(): List<String> {
        val topics = mutableListOf<String>()

        // 从岗位/职位提取
        if (config.position.isNotBlank()) {
            topics.add(config.position)
            // 分解岗位关键词
            config.position.split(Regex("[\\s、,，]")).filter { it.length > 1 }.forEach { topics.add(it) }
        }

        // 从公司提取
        if (config.company.isNotBlank()) {
            topics.add(config.company)
        }

        // 从材料文本提取高频词（简单分词）
        val materialsText = config.getMaterialsText()
        if (materialsText.isNotEmpty()) {
            // 提取材料中的关键技术/技能关键词
            val techKeywords = extractKeywords(materialsText, MAX_KEYWORDS)
            topics.addAll(techKeywords)
        }

        // 用户自定义的评估维度也是关键话题
        config.evalDimensions?.forEach { topics.add(it) }

        return topics.distinct().take(MAX_KEY_TOPICS)
    }

    /**
     * 简单的关键词提取（基于词频统计）。
     *
     * @param text 输入文本
     * @param maxCount 最大返回数量
     * @return 关键词列表
     */
    private fun extractKeywords(text: String, maxCount: Int): List<String> {
        // 简单分词：按中文常见分隔符切分
        val delimiterRegex = "[\\s\\n\\r、，。！？；：\"\"''（）【】《》/|\\\\]".toRegex()
        val words = text.split(delimiterRegex)
            .filter { it.length >= 2 }  // 至少2个字符
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        // 统计词频
        val frequency = mutableMapOf<String, Int>()
        words.forEach { word ->
            frequency[word] = frequency.getOrDefault(word, 0) + 1
        }

        // 按频次排序并返回 Top-N
        return frequency.entries
            .sortedByDescending { it.value }
            .take(maxCount)
            .map { it.key }
    }

    // ==================== 漂移检测 ====================

    /**
     * 漂移检测结果。
     */
    data class DriftResult(
        val isDrifting: Boolean,          // 是否正在漂移
        val driftLevel: DriftLevel,       // 漂移等级
        val driftReason: String,          // 为什么判定为漂移
        val suggestedCorrection: String,  // 建议的纠正方向
        val relevanceScore: Float,        // 与目标的关联度 (0-1)
    )

    /**
     * 漂移等级。
     */
    enum class DriftLevel {
        NONE,           // 无漂移，完全聚焦
        MILD,           // 轻微偏移（可以自然拉回）
        MODERATE,       // 明显偏移（需要提醒）
        SEVERE,         // 严重跑偏（必须纠正）
        OFF_TOPIC,      // 完全离题（紧急干预）
    }

    /**
     * 单轮漂移记录。
     */
    data class TurnRecord(
        val turnIndex: Int,
        val userMessage: String,
        val aiResponse: String,
        val driftResult: DriftResult,
        val timestamp: Long = System.currentTimeMillis(),
    )

    private val turnHistory = mutableListOf<TurnRecord>()

    /**
     * 检测当前轮次是否存在主题漂移。
     *
     * 检测策略（多维度融合）：
     * 1. **关键词匹配**：用户回答和 AI 回复是否包含关键话题词
     * 2. **语义相关性**：使用简单的 TF-IDF / Jaccard 相似度估算
     * 3. **历史趋势**：连续 N 轮低相关度 → 判定为持续漂移
     * 4. **禁止词触发**：命中 forbiddenTopics → 立即标记严重漂移
     *
     * @param turnIndex 当前轮次（从0开始）
     * @param userMessage 用户消息
     * @param aiResponse AI 上一轮回复
     * @return 漂移检测结果
     */
    fun detectDrift(turnIndex: Int, userMessage: String, aiResponse: String): DriftResult {
        val anchor = goalAnchor ?: return DriftResult(false, DriftLevel.NONE, "", "", 1.0f)
        val combinedText = "$userMessage $aiResponse".lowercase()

        // ===== 维度1：禁止词触发（最高优先级）=====
        for (forbidden in anchor.forbiddenTopics) {
            if (combinedText.contains(forbidden)) {
                DebugLog.w(TAG, "检测到禁止话题关键词: $forbidden")
                return DriftResult(
                    isDrifting = true,
                    driftLevel = DriftLevel.SEVERE,
                    driftReason = "检测到禁止话题关键词: 「$forbidden」",
                    suggestedCorrection = "立即回到${anchor.primaryGoal}的正轨",
                    relevanceScore = 0.1f,
                )
            }
        }

        // ===== 维度2：关键词匹配得分 =====
        var matchedTopics = 0
        val matchedTopicNames = mutableListOf<String>()
        for (topic in anchor.keyTopics) {
            if (combinedText.contains(topic.lowercase())) {
                matchedTopics++
                matchedTopicNames.add(topic)
            }
        }
        val keywordScore = if (anchor.keyTopics.isNotEmpty()) {
            matchedTopics.toFloat() / anchor.keyTopics.size.toFloat()
        } else 1.0f

        // ===== 维度3：Jaccard 文本相似度（与目标的字面重叠）=====
        val goalWords = anchor.primaryGoal.split(Regex("\\s+|[，。、！？]")).filter { it.length > 1 }.toSet()
        val responseWords = combinedText.split(Regex("\\s+|[，。、！？:：]")).filter { it.length > 1 }.toSet()
        val jaccardScore = if (goalWords.isNotEmpty()) {
            goalWords.intersect(responseWords).size.toFloat() / goalWords.union(responseWords).size.toFloat()
        } else 1.0f

        // ===== 维度4：历史趋势（连续低分检测）=====
        val recentScores = turnHistory.takeLast(3).map { it.driftResult.relevanceScore }
        val trendDeclining = recentScores.size >= 3 &&
            recentScores[0] > recentScores[1] && recentScores[1] > recentScores[2]

        // ===== 综合评分 =====
        val finalScore = keywordScore * 0.5f + jaccardScore * 0.3f +
            (if (trendDeclining) 0.0f else 0.2f) // 趋势惩罚

        // ===== 判定漂移等级 =====
        val driftLevel = when {
            finalScore >= 0.7f -> DriftLevel.NONE
            finalScore >= 0.5f -> DriftLevel.MILD
            finalScore >= 0.3f -> DriftLevel.MODERATE
            finalScore >= 0.1f -> DriftLevel.SEVERE
            else -> DriftLevel.OFF_TOPIC
        }

        val result = DriftResult(
            isDrifting = driftLevel != DriftLevel.NONE,
            driftLevel = driftLevel,
            driftReason = when (driftLevel) {
                DriftLevel.NONE -> "对话聚焦良好"
                DriftLevel.MILD -> "轻微偏移，已覆盖话题: ${matchedTopicNames.joinToString("/")}"
                DriftLevel.MODERATE -> "明显偏离，关键词覆盖率仅 ${"%.0f".format(keywordScore * 100)}%"
                DriftLevel.SEVERE -> "严重跑偏，几乎未触及任何关键话题"
                DriftLevel.OFF_TOPIC -> "完全离题"
            },
            suggestedCorrection = if (matchedTopicNames.isNotEmpty())
                "建议回到以下话题: ${matchedTopicNames.first()}"
            else
                "建议重新聚焦于: ${anchor.primaryGoal}",
            relevanceScore = finalScore,
        )

        // 记录历史
        turnHistory.add(TurnRecord(turnIndex, userMessage, aiResponse, result))

        if (result.isDrifting) {
            DebugLog.d(TAG, "第${turnIndex}轮检测到漂移 [${result.driftLevel.name}]: ${result.driftReason}")
        }

        return result
    }

    // ==================== 注意力提醒 ====================

    /**
     * 准备本轮对话的注意力上下文。
     *
     * 这是海马体的**核心输出**——一段注入到 LLM system prompt 中的文本，
     * 让 AI 在每轮回复时都保持对目标的感知。
     *
     * 根据漂移检测结果动态调整：
     * - 无漂移 → 轻量提醒（1-2句）
     * - 轻微漂移 → 温和引导（提示当前进度）
     * - 明显漂移 → 强力纠偏（明确指出偏离 + 建议回归方向）
     * - 严重漂移 → 紧急干预（重申目标 + 要求立即回到正轨）
     *
     * @param turnIndex 当前轮次
     * @param userMessage 用户消息
     * @param lastAiResponse AI 上一轮回复
     * @return 注入到 LLM system role 的注意力上下文文本
     */
    fun prepareTurnContext(turnIndex: Int, userMessage: String, lastAiResponse: String): String {
        val anchor = goalAnchor ?: return ""
        val drift = detectDrift(turnIndex, userMessage, lastAiResponse)

        // 基础锚定信息（每次都注入，但措辞随轮次变化）
        val baseAnchor = when {
            turnIndex == 0 -> """
                |【对话目标】你正在进行: ${anchor.primaryGoal}
                |【必须覆盖】${anchor.keyTopics.joinToString("、")}
                |【禁止】不要偏离到与上述目标无关的话题。
            """.trimMargin()
            turnIndex <= 2 -> """【提醒】当前目标: ${anchor.primaryGoal}（第${turnIndex + 1}轮）"""
            else -> """【锚定】${anchor.primaryGoal}"""
        }

        // 根据漂移等级动态调整提醒强度
        val driftReminder = when (drift.driftLevel) {
            DriftLevel.NONE -> ""  // 无漂移，不额外打扰
            DriftLevel.MILD -> """
                |
                |[注意] 对话有轻微偏移趋势。
                |当前已覆盖: ${extractMatchedTopics(drift)}。
                |请自然地将对话引回主线。
            """.trimMargin()
            DriftLevel.MODERATE -> """
                |
                |[注意力提醒] 对话正在偏离核心目标！
                |原因: ${drift.driftReason}
                |建议: ${drift.suggestedCorrection}
                |请在下一轮回复中将话题拉回正轨。
            """.trimMargin()
            DriftLevel.SEVERE -> """
                |
                |[紧急纠偏] 对话已严重跑偏！
                |原因: ${drift.driftReason}
                |你的任务是: ${anchor.primaryGoal}
                |必须覆盖: ${anchor.keyTopics.joinToString("、")}
                |禁止: ${anchor.forbiddenTopics.joinToString("、")}
                |请立即停止当前话题，回到面试/答辩的正轨上来！
            """.trimMargin()
            DriftLevel.OFF_TOPIC -> """
                |
                |[严重警告] 对话已完全离题！
                |立即停止一切无关对话。
                |回到目标: ${anchor.primaryGoal}
                |下一句话必须是针对候选人的专业提问或评价。
            """.trimMargin()
        }

        // 上下文保护：每隔几轮重新注入关键信息
        val protectedCtx = getProtectedContext(turnIndex)?.let {
            "\n|【关键信息回顾】$it\n|" } ?: ""

        return baseAnchor + driftReminder + protectedCtx
    }

    /**
     * 提取漂移结果中已匹配的话题名称。
     */
    private fun extractMatchedTopics(drift: DriftResult): String {
        // 从历史记录中获取最近一轮的匹配话题
        val lastRecord = turnHistory.lastOrNull() ?: return "无"
        val anchor = goalAnchor ?: return "无"
        val combinedText = "${lastRecord.userMessage} ${lastRecord.aiResponse}".lowercase()

        return anchor.keyTopics.filter { combinedText.contains(it.lowercase()) }.joinToString("/")
    }

    // ==================== 上下文保护 ====================

    /**
     * 关键信息快照 — 防止被上下文窗口冲刷的重要信息。
     *
     * 每隔 N 轮或在检测到信息可能丢失时，
     * 海马体会将关键信息重新注入到 prompt 中。
     */
    data class CriticalSnapshot(
        val turnIndex: Int,
        val keyFindings: List<String>,       // 已发现的关键信息（如候选人的亮点）
        val coveredTopics: Set<String>,      // 已覆盖的话题
        val pendingTopics: Set<String>,      // 尚未覆盖的话题
        val userMentionedFacts: List<String>,// 用户提到的事实（简历中的关键点）
        val redFlags: List<String>,          // 发现的风险点
        val timestamp: Long,
    )

    private var lastSnapshot: CriticalSnapshot? = null

    /**
     * 更新关键信息快照。
     *
     * 从最近的对话中提取重要信息，防止被上下文窗口冲刷。
     */
    fun updateCriticalSnapshot(turnIndex: Int, conversationSummary: String): CriticalSnapshot {
        val anchor = goalAnchor ?: return CriticalSnapshot(
            turnIndex = turnIndex,
            keyFindings = emptyList(),
            coveredTopics = emptySet(),
            pendingTopics = emptySet(),
            userMentionedFacts = emptyList(),
            redFlags = emptyList(),
            timestamp = System.currentTimeMillis(),
        )

        // 从历史记录中分析已覆盖的话题
        val allText = turnHistory.joinToString(" ") { "${it.userMessage} ${it.aiResponse}" }.lowercase()
        val coveredTopics = anchor.keyTopics.filter { allText.contains(it.lowercase()) }.toSet()
        val pendingTopics = anchor.keyTopics.filter { !coveredTopics.contains(it) }.toSet()

        // 提取用户提到的事实（简单实现：从最近几轮中提取较长句子）
        val userMentionedFacts = turnHistory.takeLast(5)
            .flatMap { listOf(it.userMessage, it.aiResponse) }
            .filter { it.length > 20 && it.length < 200 }
            .take(5)

        // 检测风险点（包含负面词汇的句子）
        val negativePatterns = listOf("不会", "不懂", "不清楚", "没做过", "不熟悉", "没接触过")
        val redFlags = turnHistory.flatMap { record ->
            negativePatterns.mapNotNull { pattern ->
                val text = "${record.userMessage} ${record.aiResponse}"
                if (text.contains(pattern)) "发现知识盲区: $pattern" else null
            }
        }.distinct().take(3)

        val snapshot = CriticalSnapshot(
            turnIndex = turnIndex,
            keyFindings = if (conversationSummary.isNotBlank()) listOf(conversationSummary) else emptyList(),
            coveredTopics = coveredTopics,
            pendingTopics = pendingTopics,
            userMentionedFacts = userMentionedFacts,
            redFlags = redFlags,
            timestamp = System.currentTimeMillis(),
        )

        lastSnapshot = snapshot
        DebugLog.d(TAG, "更新快照 #${turnIndex}: 已覆盖${coveredTopics.size}/${anchor.keyTopics.size}个话题")

        return snapshot
    }

    /**
     * 获取需要重新注入的关键信息。
     *
     * 如果距离上次快照已经超过 SNAPSHOT_INTERVAL 轮，
     * 返回关键信息摘要供重新注入。
     */
    fun getProtectedContext(turnIndex: Int): String? {
        val snapshot = lastSnapshot ?: return null

        // 检查是否需要重新注入（超过间隔轮次）
        if (turnIndex - snapshot.turnIndex < SNAPSHOT_INTERVAL) {
            return null
        }

        // 构建保护性上下文文本
        return buildString {
            appendLine("对话进度回顾（第${snapshot.turnIndex + 1}轮快照）：")

            if (snapshot.coveredTopics.isNotEmpty()) {
                appendLine("- 已覆盖: ${snapshot.coveredTopics.joinToString("、")}")
            }

            if (snapshot.pendingTopics.isNotEmpty()) {
                appendLine("- 待覆盖: ${snapshot.pendingTopics.joinToString("、")}")
            }

            if (snapshot.redFlags.isNotEmpty()) {
                appendLine("- 发现的风险点: ${snapshot.redFlags.joinToString("；")}")
            }

            if (snapshot.userMentionedFacts.isNotEmpty()) {
                appendLine("- 候选人提及: ${snapshot.userMentionedFacts.take(3).joinToString("；")}")
            }
        }
    }

    // ==================== 报告与诊断 ====================

    /**
     * 漂移报告 — 对话结束后的完整注意力分析报告。
     */
    data class DriftReport(
        val totalTurns: Int,
        val driftCount: Int,              // 发生漂移的轮次数
        val driftRate: Float,             // 漂移率 (driftCount/totalTurns)
        val maxDriftLevel: DriftLevel,    // 最高漂移等级
        val averageRelevance: Float,      // 平均关联度
        val turnRecords: List<TurnRecord>,
        val topicsCovered: Set<String>,   // 实际覆盖的话题
        val topicsMissed: Set<String>,    // 未覆盖的话题
        val interventionCount: Int,       // 干预次数
        val summary: String,              // 文字总结
    )

    /**
     * 生成完整的漂移报告。
     */
    fun getDriftReport(): DriftReport {
        val anchor = goalAnchor
        val totalTurns = turnHistory.size

        if (totalTurns == 0 || anchor == null) {
            return DriftReport(
                totalTurns = 0,
                driftCount = 0,
                driftRate = 0f,
                maxDriftLevel = DriftLevel.NONE,
                averageRelevance = 1.0f,
                turnRecords = emptyList(),
                topicsCovered = emptySet(),
                topicsMissed = emptySet(),
                interventionCount = 0,
                summary = "无对话数据",
            )
        }

        // 统计漂移情况
        val driftingRecords = turnHistory.filter { it.driftResult.isDrifting }
        val driftCount = driftingRecords.size
        val driftRate = if (totalTurns > 0) driftCount.toFloat() / totalTurns.toFloat() else 0f

        // 最高漂移等级
        val maxDriftLevel = turnHistory
            .map { it.driftResult.driftLevel }
            .maxByOrNull { it.ordinal } ?: DriftLevel.NONE

        // 平均关联度
        val averageRelevance = if (turnHistory.isNotEmpty()) {
            turnHistory.map { it.driftResult.relevanceScore }.average().toFloat()
        } else 1.0f

        // 干预次数（MODERATE 及以上算干预）
        val interventionCount = turnHistory.count {
            it.driftResult.driftLevel.ordinal >= DriftLevel.MODERATE.ordinal
        }

        // 分析覆盖的话题
        val allText = turnHistory.joinToString(" ") { "${it.userMessage} ${it.aiResponse}" }.lowercase()
        val topicsCovered = anchor.keyTopics.filter { allText.contains(it.lowercase()) }.toSet()
        val topicsMissed = anchor.keyTopics.filter { !topicsCovered.contains(it) }.toSet()

        // 生成文字总结
        val summary = buildString {
            appendLine("共 $totalTurns 轮对话，其中 $driftCount 轮发生注意力漂移（漂移率 %.1f%%）。".format(driftRate * 100))

            when {
                driftRate < 0.2f -> appendLine("对话整体聚焦良好，AI 始终保持在目标轨道上。")
                driftRate < 0.5f -> appendLine("存在轻微漂移，但总体可控。建议关注后续对话的聚焦程度。")
                else -> appendLine("注意力漂移较严重，AI 多次偏离主题。建议优化提示词或增加锚定强度。")
            }

            appendLine("最高漂移等级: ${maxDriftLevel.name}")

            if (topicsCovered.isNotEmpty()) {
                appendLine("已覆盖话题: ${topicsCovered.joinToString("、")}")
            }

            if (topicsMissed.isNotEmpty()) {
                appendLine("未覆盖话题: ${topicsMissed.joinToString("、")}")
            }

            if (interventionCount > 0) {
                appendLine("系统进行了 $interventionCount 次注意力干预。")
            }
        }

        val report = DriftReport(
            totalTurns = totalTurns,
            driftCount = driftCount,
            driftRate = driftRate,
            maxDriftLevel = maxDriftLevel,
            averageRelevance = averageRelevance,
            turnRecords = turnHistory.toList(),
            topicsCovered = topicsCovered,
            topicsMissed = topicsMissed,
            interventionCount = interventionCount,
            summary = summary,
        )

        DebugLog.i(TAG, "漂移报告生成完成: ${report.summary}")

        return report
    }

    // ==================== 生命周期 ====================

    /**
     * 重置所有状态（新对话开始时调用）。
     */
    fun reset() {
        goalAnchor = null
        turnHistory.clear()
        lastSnapshot = null
        DebugLog.i(TAG, "海马体记忆系统已重置")
    }

    /**
     * 获取当前目标锚点（如果已设置）。
     */
    fun getCurrentGoal(): GoalAnchor? = goalAnchor

    /**
     * 获取当前轮次历史记录数。
     */
    fun getTurnCount(): Int = turnHistory.size
}
