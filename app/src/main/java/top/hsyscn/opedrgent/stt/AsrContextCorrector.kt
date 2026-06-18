package top.hsyscn.opedrgent.stt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * ASR context corrector:
 * Sends ASR output along with recent conversation context to an LLM
 * to correct homophone errors and contextually incorrect words.
 *
 * Design principles:
 * - Only corrects obvious homophone/near-homophone errors, does not change user intent
 * - Preserves original interjections and colloquial expressions
 * - Returns original text on failure
 * - Latency target under 500ms (uses lightweight model)
 */
class AsrContextCorrector(
    private val baseUrl: String,
    private val apiKey: String?,
    private val model: String,
) {

    /**
     * Correct ASR output text
     *
     * @param rawText ASR raw output
     * @param recentContext recent N rounds of conversation summary (for context)
     * @return corrected text, returns rawText on failure
     */
    suspend fun correct(
        rawText: String,
        recentContext: List<String> = emptyList(),
    ): String = withContext(Dispatchers.IO) {
        // Short text not corrected (too short for context reference)
        if (rawText.length < 4) return@withContext rawText

        try {
            val contextBlock = if (recentContext.isNotEmpty()) {
                recentContext.takeLast(5).joinToString("\n") { "  $it" }
            } else {
                "(no historical context)"
            }

            val prompt = buildString {
                append("You are a speech recognition post-processor. The user's speech has been transcribed by an ASR engine, ")
                append("but there may be recognition errors due to homophones or near-homophones.\n\n")
                append("Based on the conversation context, correct obvious homophone/near-homophone errors in the text.\n")
                append("Rules:\n")
                append("1. Only correct characters that are genuinely wrong; do not change user intent\n")
                append("2. Preserve interjections and colloquial expressions (e.g. um, ah, that thing, etc.)\n")
                append("3. If the text has no obvious errors, return it as-is\n")
                append("4. Return only the corrected text, do not add any explanation\n\n")
                append("## Recent conversation context\n$contextBlock\n\n")
                append("## Speech recognition result\n$rawText\n\n")
                append("## Corrected text\n")
            }

            val result = callLlm(prompt)
            // Safety check: result should not differ too much from original (possible LLM hallucination)
            if (result != null && result.isNotBlank() && isReasonableCorrection(rawText, result)) {
                result.trim()
            } else {
                rawText
            }
        } catch (e: Exception) {
            rawText
        }
    }

    /**
     * Verify correction is reasonable (character changes not exceeding 30%)
     */
    private fun isReasonableCorrection(original: String, corrected: String): Boolean {
        val maxLen = maxOf(original.length, corrected.length)
        if (maxLen == 0) return true
        val diff = levenshteinDistance(original, corrected)
        return diff.toFloat() / maxLen <= 0.3f
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[s1.length][s2.length]
    }

    private fun callLlm(prompt: String): String? {
        val url = URL("${baseUrl.trimEnd('/')}/chat/completions")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            apiKey?.let { setRequestProperty("Authorization", "Bearer $it") }
            connectTimeout = 3000
            readTimeout = 5000
            doOutput = true
        }

        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
            put("max_tokens", 256)
            put("temperature", 0.1)
        }

        OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

        return if (conn.responseCode == 200) {
            val resp = JSONObject(conn.inputStream.bufferedReader().readText())
            resp.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        } else {
            null
        }
    }
}
