package top.hsyscn.opedrgent.utils

object StringUtils {
    private val NULL_PATTERN = Regex("""\bnull\b""")

    fun sanitizeJsonNull(text: String?): String {
        if (text == null || text.isBlank()) return ""
        return NULL_PATTERN.replace(text, "").replace(Regex("""\s{2,}"""), " ").trim()
    }

    fun sanitizeJsonNullKeepStructure(text: String?): String {
        if (text == null || text.isBlank()) return ""
        return text.replace(Regex("""(?<=\s)null(?=\s)"""), "").replace(Regex("""\bnull\b"""), "")
            .replace(Regex("""\s{2,}"""), " ").trim().let { if (it.isEmpty()) text else it }
    }
}

fun smartTruncate(text: String, maxLen: Int): String {
    if (text.length <= maxLen) return text
    val truncated = text.take(maxLen)
    val lastParagraph = truncated.lastIndexOf("\n\n")
    if (lastParagraph > maxLen * 0.6) return truncated.substring(0, lastParagraph)
    val lastSentence = maxOf(truncated.lastIndexOf("。"), truncated.lastIndexOf(". "), truncated.lastIndexOf("！"), truncated.lastIndexOf("？"))
    if (lastSentence > maxLen * 0.5) return truncated.substring(0, lastSentence + 1)
    val lastSpace = truncated.lastIndexOf(' ')
    if (lastSpace > maxLen * 0.7) return truncated.substring(0, lastSpace)
    return truncated
}