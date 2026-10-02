package top.hsyscn.opedrgent.interview

import top.hsyscn.opedrgent.utils.DebugLog
import java.util.Collections

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
        /** 停用词表 — 过滤目标关键词中的功能词 */
        private val STOPWORDS = setOf(
            "的", "了", "是", "在", "和", "与", "或", "也", "都", "就",
            "等", "中", "上", "下", "对", "为", "从", "到", "把", "被",
            "但是", "然而", "因此", "所以", "而且", "以及", "或者", "还是",
            "不是", "就是", "这个", "那个", "一个", "一些", "可以", "应该",
            "需要", "已经", "正在", "什么", "怎么", "为什么", "进行", "完成",
            "the", "a", "an", "is", "are", "was", "were", "be", "been",
            "and", "or", "but", "in", "on", "at", "to", "for", "of",
            "with", "by", "from", "about", "into", "through", "during",
        )
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

    @Volatile
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

    private val turnHistory = Collections.synchronizedList(mutableListOf<TurnRecord>())

    /**
     * 记录一轮对话（跑题判定已交由模型完成）。
     *
     * 设计变更：是否跑题必须由模型结合完整转写与既定目标判断，
     * 本类不再做关键词命中 / 禁止词触发 / Jaccard 相似度等本地规则判定，
     * 也不再据此下发"警告/立即纠正"式干预。这里仅如实记录本轮对话，
     * 供结束后的轮次日志与漂移报告使用。
     *
     * @param turnIndex 当前轮次（从0开始）
     * @param userMessage 用户消息
     * @param aiResponse AI 上一轮回复
     * @return 中性记录结果（不再据此触发干预）
     */
    fun detectDrift(turnIndex: Int, userMessage: String, aiResponse: String): DriftResult {
        // 无目标锚定时不记录
        goalAnchor ?: return DriftResult(false, DriftLevel.NONE, "", "", 1.0f)

        // 仅记录轮次，不做关键词/词表命中式判定
        val result = DriftResult(
            isDrifting = false,
            driftLevel = DriftLevel.NONE,
            driftReason = "跑题判定由模型结合完整对话与目标完成",
            suggestedCorrection = "",
            relevanceScore = 1.0f,
        )
        turnHistory.add(TurnRecord(turnIndex, userMessage, aiResponse, result))

        DebugLog.d(TAG, "记录第${turnIndex}轮对话（漂移判定交由模型）")
        return result
    }

    // ==================== 注意力提醒 ====================

    /**
     * 准备本轮对话的注意力上下文（持续把目标带给模型）。
     *
     * 这是海马体的**核心输出**——一段注入到 LLM system 提示中的文本，
     * 让 AI 在每轮回复时都保持对目标的感知，避免长对话后目标被上下文窗口冲刷。
     *
     * 与旧实现的区别：不再根据本地关键词/相似度把对话切成 MILD/MODERATE/SEVERE/OFF_TOPIC
     * 并下发"注意/警告/必须立即纠正"式硬干预。是否跑题、是否拉回、何时拉回，
     * 全部交由模型结合完整转写与目标自行判断；这里只给出"保持目标、温和拉回"的行为要求。
     *
     * @param turnIndex 当前轮次
     * @param userMessage 用户消息
     * @param lastAiResponse AI 上一轮回复
     * @return 注入到 LLM system role 的注意力上下文文本
     */
    fun prepareTurnContext(turnIndex: Int, userMessage: String, lastAiResponse: String): String {
        val anchor = goalAnchor ?: return ""

        // 基础锚定信息（自然语言风格，随轮次变化），持续携带目标
        val baseAnchor = when {
            turnIndex == 0 -> "当前任务：${anchor.primaryGoal}。需要覆盖的重点：${anchor.keyTopics.take(5).joinToString("、")}。"
            turnIndex <= 2 -> "（第${turnIndex + 1}轮）记住核心目标：${anchor.primaryGoal}"
            else -> "目标回顾：${anchor.primaryGoal}"
        }

        // 把"是否跑题、是否拉回"的判断交给模型：只在明显偏离时自然承接再引回一次，
        // 轻度偏离不打断，不生硬纠正，不编造。
        val guidance = """
            |
            |【目标保持】请始终围绕上述目标自然推进。若你结合完整对话判断参与者已明显跑题，
            |请先承接他刚刚说的内容，再用一句自然的话把话题引回目标；只在明显跑题时这样做一次，
            |轻度偏离不要打断对方，也不要生硬地纠正。不要编造参与者没有提到的信息。
        """.trimMargin()

        // 上下文保护：每隔几轮重新注入关键信息（仅供模型参考，不打断对话）
        val protectedCtx = getProtectedContext(turnIndex)?.let { "\n关键信息回顾：$it" } ?: ""

        return baseAnchor + guidance + protectedCtx
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

    // ==================== 跨会话持久化 ====================

    /**
     * 会话快照 — 用于持久化到长期存储。
     *
     * 包含目标锚点 + 漂移报告 + 起止时间，足以在 App 重启后完整回顾本次会话。
     */
    data class SessionSnapshot(
        val goalAnchor: GoalAnchor,
        val driftReport: DriftReport,
        val startedAt: Long,
        val endedAt: Long,
    )

    /**
     * 导出当前会话快照供持久化。
     *
     * 应在 [closeSession] / [reset] 之前调用，否则数据会被清空。
     * 如果目标未锚定或无轮次记录，返回 null。
     */
    fun exportSessionSnapshot(): SessionSnapshot? {
        val anchor = goalAnchor ?: return null
        val report = getDriftReport()
        if (report.totalTurns == 0) return null
        return SessionSnapshot(
            goalAnchor = anchor,
            driftReport = report,
            startedAt = anchor.anchoredAt,
            endedAt = System.currentTimeMillis(),
        )
    }
}
