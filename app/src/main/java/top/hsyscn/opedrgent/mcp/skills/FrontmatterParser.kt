package top.hsyscn.opedrgent.mcp.skills

object FrontmatterParser {

    private val FRONTMATTER_REGEX = Regex("^---\\n([\\s\\S]*?)\\n---\\n?[\\s\\S]*$")

    data class ParsedFrontmatter(
        val metadata: Map<String, String>,
        val body: String,
    )

    fun parse(content: String): ParsedFrontmatter {
        val match = FRONTMATTER_REGEX.find(content)
        if (match == null) {
            return ParsedFrontmatter(emptyMap(), content.trim())
        }

        val yamlContent = match.groupValues[1]
        val body = content.substring(match.range.last + 1).trim()

        val metadata = parseYamlSimple(yamlContent)

        return ParsedFrontmatter(metadata, body)
    }

    private fun parseYamlSimple(yamlContent: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (line in yamlContent.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val colonIndex = trimmed.indexOf(':')
            if (colonIndex > 0) {
                val key = trimmed.substring(0, colonIndex).trim()
                val value = trimmed.substring(colonIndex + 1).trim()
                    .removeSurrounding("\"", "'")
                    .removeSurrounding("[", "]")
                if (key.isNotEmpty()) {
                    result[key] = value
                }
            }
        }
        return result
    }

    fun toFrontmatterString(metadata: Map<String, String>, body: String): String {
        if (metadata.isEmpty()) return body
        val yamlLines = metadata.entries.joinToString("\n") { (k, v) -> "$k: $v" }
        return "---\n$yamlLines\n---\n\n$body"
    }
}