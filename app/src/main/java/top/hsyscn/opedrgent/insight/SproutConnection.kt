package top.hsyscn.opedrgent.insight

data class SproutConnection(
    val domain: String,
    val analogyOrCase: String,
    val analysis: String,
    val unexpectedness: Float = 0f, // 反直觉程度 0-1
    val historicalReference: String = "", // 具体的历史/文化出处
    val sourceType: String = "llm_guess", // llm_guess | web_searched | user_provided
)
