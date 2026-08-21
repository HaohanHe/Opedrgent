package top.hsyscn.opedrgent.insight

data class SproutSeed(
    val concept: String,
    val description: String,
    val keywords: List<String> = emptyList(),
    val relevanceScore: Float = 0f,
)
