package top.hsyscn.opedrgent.insight

/**
 * 发芽输出长度控制
 */
enum class SproutOutputLength {
    SHORT,   // 精简模式：只输出核心结论
    MEDIUM,  // 标准模式：完整分析过程 + 结论
    LONG,    // 详细模式：包含所有中间步骤和详细论证
}

data class SproutQuote(
    val originalQuote: String,
    val source: String,
    val author: String,
    val extension: String,
)

data class SproutResult(
    val seeds: List<SproutSeed> = emptyList(),
    val connections: List<SproutConnection> = emptyList(),
    val enhancedConnections: List<SproutEnhancedConnection> = emptyList(), // Phase 2.5 联网增强关联
    val insights: List<SproutInsight> = emptyList(),
    val quotes: List<SproutQuote> = emptyList(),
    val markdownReport: String = "",
    val completedPhases: Set<SproutPhase> = emptySet(),
    val inputText: String = "",
    val processingTimeMs: Long = 0,
)

data class SproutConfig(
    val outputLength: SproutOutputLength = SproutOutputLength.MEDIUM,
    val preferredDomains: List<String> = emptyList(),
    val useContext: Boolean = false,
    val maxPhaseTimeoutSeconds: Int = 90,
    val totalTimeoutSeconds: Int = 600,
    val enableWebSearch: Boolean = false, // 是否启用联网搜索增强（Phase 2.5）
    val includeQuotes: Boolean = true, // 是否生成金句回响
)

/**
 * Phase 2.5 联网增强关联
 * 由网络搜索结果驱动的跨领域关联，带有真实的参考来源
 */
data class SproutEnhancedConnection(
    val seedConcept: String,
    val domain: String,
    val reference: String,       // 具体的参考资料（书籍/文章/事件/人物，注明来源）
    val insight: String,         // 基于此资料的原创性洞察
    val relevanceScore: Float = 0f, // 与原种子的相关度 0-1
)
