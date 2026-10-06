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

    // 收集所有标记出现位置（含重复标记），避免 indexOf 只命中首个而把后续内容吞并
    val positions = markers.flatMap { (marker, key) ->
        val hits = mutableListOf<Triple<Int, Int, String>>()
        var cursor = 0
        while (true) {
            val found = rawText.indexOf(marker, cursor)
            if (found < 0) break
            hits.add(Triple(found, marker.length, key))
            cursor = found + marker.length
        }
        hits
    }.sortedBy { it.first }

    if (positions.isEmpty()) {
        return ParsedSummary(smartSummary = rawText.trim())
    }

    val result = mutableMapOf<String, String>()
    for (i in positions.indices) {
        val start = positions[i].first + positions[i].second
        val end = if (i + 1 < positions.size) positions[i + 1].first else rawText.length
        val slice = rawText.substring(start, end).trim()
        val key = positions[i].third
        result[key] = if (result[key].isNullOrBlank()) slice else (result[key] + "\n" + slice)
    }

    fun extractListItems(text: String): List<String> {
        return text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map {
                it.removePrefix("-").trim()
                    .removePrefix("•").trim()
                    .removePrefix("*").trim()
                    .replace(Regex("^\\d+[.、)]\\s*"), "") // 通用剥离 1./1、/1) 等任意编号前缀
                    .trim()
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
