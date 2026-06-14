package top.hsyscn.opedrgent.insight

/**
 * 单个声音的一次"发言"
 *
 * 在思想空间中，每个声音会针对用户的输入产生一段分析文字。
 * 这段发言包含该声音的核心观点、论据、以及与其他声音的潜在互动点。
 */
data class SproutVoiceStatement(
    /** 发言的是哪个声音 */
    val voice: SproutVoice,

    /** 发言的核心内容（2-5句话，有态度、有观点） */
    val statement: String,

    /** 这个声音的关键论点/要点（用于UI展示摘要） */
    val keyPoints: List<String> = emptyList(),

    /** 这个声音引用了什么资料（历史文献/网络来源/经典著作等） */
    val references: List<String> = emptyList(),

    /** 这个声音的态度倾向（-1=强烈反对, 0=中立, 1=强烈支持） */
    val sentiment: Float = 0f,

    /** 这个声音认为最重要的一个词/短语（用于标签展示） */
    val tagline: String = "",

    /** 发言耗时(ms) */
    val processingMs: Long = 0,
)

/**
 * 综合结论 —— 所有声音发言后的最终综合
 *
 * 这是整个思考空间的输出核心。不是简单拼接各声音的发言，
 * 而是一个有机的综合：找出共识、突出分歧、给出方向。
 */
data class SproutSynthesis(
    /** 一句话总结整个分析的核心发现 */
    val coreFinding: String,

    /** 各声音之间的共识点 */
    val consensus: List<String> = emptyList(),

    /** 各声音之间的分歧点（这才是最有价值的部分） */
    val disagreements: List<String> = emptyList(),

    /** 基于所有分析给出的可行建议/下一步行动 */
    val recommendations: List<String> = emptyList(),

    /** 一个发人深省的收尾问题（留给用户继续思考） */
    val closingQuestion: String = "",

    /** 整体评估：这次分析的深度和质量 */
    val depthAssessment: String = "",
)

/**
 * 思想空间会话 —— 一次完整的发芽过程的所有数据
 */
data class ThinkingSession(
    /** 使用的模板 */
    val template: SproutTemplate,

    /** 种子提取结果（原始概念列表） */
    val seeds: List<SproutSeed> = emptyList(),

    /** 各声音的发言（key=voice, value=statement） */
    val voiceStatements: Map<SproutVoice, SproutVoiceStatement> = emptyMap(),

    /** 各声音的状态（用于UI展示进度） */
    val voiceStatuses: Map<SproutVoice, SproutVoiceStatus> = emptyMap(),

    /** 最终综合 */
    val synthesis: SproutSynthesis? = null,

    /** 联网搜索增强的结果（如果有） */
    val webEnhancedData: List<SproutEnhancedConnection> = emptyList(),

    /** 经典引用（如果有） */
    val quotes: List<SproutQuote> = emptyList(),

    /** 总耗时 */
    val totalProcessingMs: Long = 0,
) {
    /** 完成的声音数量 */
    val completedVoicesCount: Int
        get() = voiceStatuses.values.count { it == SproutVoiceStatus.DONE }

    /** 总声音数量 */
    val totalVoicesCount: Int
        get() = template.voices.size

    /** 完成百分比 */
    val progressPercent: Int
        get() = if (totalVoicesCount == 0) 100
            else (completedVoicesCount * 100 / totalVoicesCount).coerceIn(0, 100)

    /**
     * 获取按模板顺序排列的声音列表（用于UI顺序展示）
     */
    val orderedVoices: List<SproutVoice>
        get() = template.voices

    /**
     * 检查某个声音是否已完成
     */
    fun isVoiceDone(voice: SproutVoice): Boolean =
        voiceStatuses[voice] == SproutVoiceStatus.DONE

    /**
     * 获取某个声音的发言内容
     */
    fun getStatement(voice: SproutVoice): SproutVoiceStatement? =
        voiceStatements[voice]
}
