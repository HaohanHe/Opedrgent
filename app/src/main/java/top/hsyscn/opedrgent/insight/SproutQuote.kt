package top.hsyscn.opedrgent.insight

data class SproutQuote(
    val originalQuote: String,
    val source: String,
    val author: String,
    val extension: String,
)

data class SproutResult(
    val seeds: List<SproutSeed> = emptyList(),
    val connections: List<SproutConnection> = emptyList(),
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
    val maxPhaseTimeoutSeconds: Int = 30,
    val totalTimeoutSeconds: Int = 120,
)
