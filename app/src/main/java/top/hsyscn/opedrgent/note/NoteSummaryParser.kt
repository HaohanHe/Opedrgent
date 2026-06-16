package top.hsyscn.opedrgent.note

data class ParsedSummary(
    val smartSummary: String = "",
    val chapterOutline: String = "",
    val keyQuotes: List<String> = emptyList(),
    val actionItems: List<String> = emptyList(),
)

fun parseAiSummary(rawText: String): ParsedSummary {
    if (rawText.isBlank()) return ParsedSummary()

    val markers = listOf(
        "【智能总结】" to "smartSummary",
        "【章节概要】" to "chapterOutline",
        "【金句精选】" to "keyQuotes",
        "【待办事项】" to "actionItems",
    )

    val positions = markers.mapNotNull { (marker, key) ->
        val idx = rawText.indexOf(marker)
        if (idx >= 0) Triple(idx, marker.length, key) else null
    }.sortedBy { it.first }

    if (positions.isEmpty()) {
        return ParsedSummary(smartSummary = rawText.trim())
    }

    val result = mutableMapOf<String, String>()
    for (i in positions.indices) {
        val start = positions[i].first + positions[i].second
        val end = if (i + 1 < positions.size) positions[i + 1].first else rawText.length
        result[positions[i].third] = rawText.substring(start, end).trim()
    }

    fun extractListItems(text: String): List<String> {
        return text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map {
                it.removePrefix("-").trim()
                    .removePrefix("•").trim()
                    .removePrefix("*").trim()
                    .removePrefix("1.").trim()
                    .removePrefix("2.").trim()
                    .removePrefix("3.").trim()
                    .removePrefix("4.").trim()
                    .removePrefix("5.").trim()
            }
            .filter { it.isNotBlank() }
    }

    return ParsedSummary(
        smartSummary = result["smartSummary"] ?: "",
        chapterOutline = result["chapterOutline"] ?: "",
        keyQuotes = extractListItems(result["keyQuotes"] ?: ""),
        actionItems = extractListItems(result["actionItems"] ?: ""),
    )
}
