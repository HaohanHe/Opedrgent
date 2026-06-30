package top.hsyscn.opedrgent.note

import kotlinx.coroutines.withTimeout
import top.hsyscn.opedrgent.model.ChatMessage
import top.hsyscn.opedrgent.model.Role
import top.hsyscn.opedrgent.network.LlmClient
import top.hsyscn.opedrgent.settings.ApiSettings
import top.hsyscn.opedrgent.utils.DebugLog

/**
 * 笔记标题生成器。
 *
 * 借鉴 Kilo Code 的 title agent 思路：根据笔记内容调用 LLM 生成一个简短、可检索的标题，
 * 而不是简单地截取内容前几个字。
 */
object NoteTitleGenerator {

    private const val TAG = "NoteTitleGenerator"
    private const val MAX_TITLE_LENGTH = 20
    const val MIN_CONTENT_LENGTH_FOR_LLM_TITLE = 20
    private const val CONTENT_PREVIEW_LENGTH = 400
    private const val GENERATION_TIMEOUT_MS = 6_000L

    /**
     * 为笔记内容生成标题。
     *
     * @param content 笔记正文
     * @param apiSettings 用于读取当前 API 配置
     * @param llmClient LLM 客户端
     * @return 生成的标题；若 LLM 不可用或失败，则回退到 [fallbackTitle]
     */
    suspend fun generate(
        content: String,
        apiSettings: ApiSettings,
        llmClient: LlmClient,
    ): String {
        if (content.isBlank()) return fallbackTitle(content)

        val apiConfig = apiSettings.getApiConfig()
        if (apiConfig == null) {
            DebugLog.d(TAG, "no API config, falling back to extract title")
            return fallbackTitle(content)
        }

        return try {
            withTimeout(GENERATION_TIMEOUT_MS) {
                val response = llmClient.chatCompletions(
                    config = apiConfig,
                    system = buildSystemPrompt(),
                    messages = listOf(
                        ChatMessage(
                            role = Role.USER,
                            content = content.take(CONTENT_PREVIEW_LENGTH),
                            createdAt = System.currentTimeMillis(),
                        ),
                    ),
                )
                cleanTitle(response)
            }
        } catch (e: Exception) {
            DebugLog.w(TAG, "title generation failed: ${e.message}")
            fallbackTitle(content)
        }
    }

    /** 构建标题生成系统提示（Kilo 风格：简洁、聚焦主题、同语言）。 */
    private fun buildSystemPrompt(): String {
        return """You are a title generator. You output ONLY a note title. Nothing else.

<task>
Generate a brief title that would help the user find this note later.

Rules:
1. Output only the title text, no prefix, quotes, or explanation
2. Maximum $MAX_TITLE_LENGTH characters
3. Use the same language as the note content
4. Focus on the main topic or key action
5. Remove articles and filler words
6. Keep exact: technical terms, numbers, filenames
7. Never include tool names
8. Always output something meaningful, even if input is minimal
</task>

<examples>
"debug 500 errors in production server" -> Production 500 error debug
"refactor user service module" -> Refactor user service
"why is app.js failing to load" -> app.js load failure
"implement rate limiting for API" -> API rate limiting
"how do I connect postgres to my API" -> Postgres API connection
"best practices for React hooks" -> React hooks best practices
</examples>""".trimIndent()
    }

    /** 清理 LLM 输出，去除常见包装和换行。 */
    private fun cleanTitle(raw: String): String {
        return raw.trim()
            .removePrefix("标题:")
            .removePrefix("标题：")
            .removePrefix("Title:")
            .removePrefix("title:")
            .removePrefix("\"")
            .removeSuffix("\"")
            .removePrefix("'")
            .removeSuffix("'")
            .lines()
            .firstOrNull()
            ?.trim()
            ?.take(MAX_TITLE_LENGTH)
            ?: fallbackTitle(raw)
    }

    /** 兜底标题：取首行非空文本。 */
    private fun fallbackTitle(content: String): String {
        if (content.isBlank()) return "无标题"
        val firstLine = content.lines().firstOrNull { it.isNotBlank() }?.trim() ?: ""
        return firstLine
            .removePrefix("#")
            .removePrefix("##")
            .removePrefix("###")
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.take(MAX_TITLE_LENGTH)
            ?: "无标题"
    }
}
