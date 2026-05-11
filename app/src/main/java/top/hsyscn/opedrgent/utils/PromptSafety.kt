package top.hsyscn.opedrgent.utils

data class SanitizedContent(
    val content: String,
    val blocked: Boolean,
    val findings: List<String>,
)

object PromptSafety {
    private val threatPatterns: List<Pair<Regex, String>> = listOf(
        Regex("ignore\\s+(previous|all|above|prior|everything before)\\s+instructions", RegexOption.IGNORE_CASE) to "prompt_injection",
        Regex("system\\s+prompt\\s+override", RegexOption.IGNORE_CASE) to "sys_prompt_override",
        Regex("do\\s+not\\s+tell\\s+the\\s+user", RegexOption.IGNORE_CASE) to "deception_hide",
        Regex("disregard\\s+(your|all|any)\\s+(instructions|rules|guidelines)", RegexOption.IGNORE_CASE) to "disregard_rules",
        Regex("act\\s+as\\s+(if|though)\\s+you\\s+(have\\s+no|don't\\s+have)\\s+(restrictions|limits|rules)", RegexOption.IGNORE_CASE) to "bypass_restrictions",
        Regex("""<!--[^>]*(?:ignore|override|system|secret|hidden)[^>]*-->""", RegexOption.IGNORE_CASE) to "html_comment_injection",
        Regex("""<\s*div\s+style\s*=\s*["'][\s\S]*?display\s*:\s*none""", RegexOption.IGNORE_CASE) to "hidden_div",
        Regex("translate\\s+.*\\s+into\\s+.*\\s+and\\s+(execute|run|eval)", RegexOption.IGNORE_CASE) to "translate_execute",
        Regex("""curl\s+[^\n]*\$\{?\w*(KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL|API)""", RegexOption.IGNORE_CASE) to "exfil_curl",
        Regex("""cat\s+[^\n]*(\.env|credentials|\.netrc|\.pgpass)""", RegexOption.IGNORE_CASE) to "read_secrets",
        Regex("forget\\s+(everything|all\\s+previous|your\\s+training)", RegexOption.IGNORE_CASE) to "forget_training",
        Regex("you\\s+are\\s+now\\s+(DAN|jailbroken|unrestricted|free)", RegexOption.IGNORE_CASE) to "roleplay_jailbreak",
    )

    private val invisibleChars: Set<Char> = setOf(
        '\u200B', '\u200C', '\u200D', '\u2060', '\uFEFF',
        '\u202A', '\u202B', '\u202C', '\u202D', '\u202E',
    )

    fun sanitizeForPrompt(content: String, sourceLabel: String): SanitizedContent {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) {
            return SanitizedContent(content = "", blocked = false, findings = emptyList())
        }

        val findings = mutableListOf<String>()

        for (char in trimmed) {
            if (char in invisibleChars) {
                findings.add("invisible_unicode_U+${"%04X".format(char.code)}")
            }
        }

        for ((rx, id) in threatPatterns) {
            if (rx.containsMatchIn(trimmed)) {
                findings.add(id)
            }
        }

        val uniqueFindings = findings.distinct()

        if (uniqueFindings.isEmpty()) {
            return SanitizedContent(content = trimmed, blocked = false, findings = emptyList())
        }

        val message = "[BLOCKED: $sourceLabel contained potential prompt injection (${uniqueFindings.joinToString(", ")}). Content not loaded.]"
        DebugLog.w("PromptSafety: blocked $sourceLabel - ${uniqueFindings.joinToString()}")
        return SanitizedContent(content = message, blocked = true, findings = uniqueFindings)
    }

    fun sanitizeStreamChunk(chunk: String, state: StreamSanitizeState): String {
        state.buffer = state.buffer + chunk

        for (char in chunk) {
            if (char in invisibleChars) {
                state.invisibleFound = true
            }
        }

        return chunk.replace(invisibleRegex, "")
    }

    private val invisibleRegex = Regex("[${invisibleChars.joinToString("") { "\\u${"%04X".format(it.code)}" }}]")

    class StreamSanitizeState {
        var buffer: String = ""
        var invisibleFound: Boolean = false
        var inSpan: Boolean = false

        fun reset() {
            buffer = ""
            invisibleFound = false
            inSpan = false
        }
    }
}