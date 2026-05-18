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