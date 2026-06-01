package top.hsyscn.opedrgent.insight

data class SproutConnection(
    val domain: String,
    val analogyOrCase: String,
    val analysis: String,
    val unexpectedness: Float = 0f, // 反直觉程度 0-1
)
