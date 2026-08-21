package top.hsyscn.opedrgent.insight

data class SproutInsight(
    val content: String,
    val counterIntuitiveScore: Float = 0f, // 反直觉程度 0-1
    val tags: List<String> = emptyList(),
)
