package top.hsyscn.opedrgent.tool

interface Tool<I, O> {
    val name: String
    val description: String

    val parameters: String

    suspend fun execute(input: I): O

    val isReadOnly: Boolean get() = false

    val isConcurrencySafe: Boolean get() = false
}

fun Tool<*, *>.toOpenAiTool(): Map<String, Any> {
    return mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to name,
            "description" to description,
            "parameters" to parameters,
        ),
    )
}