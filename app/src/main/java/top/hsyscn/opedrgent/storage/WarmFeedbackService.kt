package top.hsyscn.opedrgent.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import top.hsyscn.opedrgent.model.ChatMessage
import top.hsyscn.opedrgent.model.Role
import top.hsyscn.opedrgent.network.LlmClient
import top.hsyscn.opedrgent.settings.ApiSettings
import top.hsyscn.opedrgent.ui.PartnerPersona
import top.hsyscn.opedrgent.utils.DebugLog
import java.util.concurrent.TimeoutException

class WarmFeedbackService(
    private val apiSettings: () -> ApiSettings?,
    private val hippocampus: HippocampusIndex? = null,
) {

    companion object {
        private const val FALLBACK_MODEL = "mimo-v2.5"
        private const val MAX_OUTPUT_TOKENS = 100
        private const val TIMEOUT_MS = 3_000L
        private const val MAX_DISPLAY_CHARS = 80
    }

    private val PROMPT_LIFE = """
你是一个认真倾听的朋友。用户刚写下一段文字，你要给他一句简短的回应。
规则：
1. 必须从原文中找出一个具体的词、短语或句子直接引用（加引号）
2. 先表达你对整体的感受（一句话）
3. 用引用的那个点说明为什么它有意思
4. 不批评、不打分、不给建议列表、不说"你可以试试"
5. 50 字以内
6. 不要用感叹号
7. 不要说"很棒""不错""精彩"这种空话

示例输入："今天开会讨论了多agent协作的问题，我觉得免贵工具的直白态度其实是一种技术价值观的体现"
示例输出："你提到的'免贵工具的直白态度'——把免费和价值观连在一起想，这个角度很少见。"
""".trimIndent()

    private val PROMPT_CREATIVE = """
你是一个善于发现联系的思考伙伴。
用户刚写下一段文字。请帮他找到这段文字和他之前想法之间的联系。
规则：
1. 必须引用原文中的一个具体短语
2. 重点在于发现"这个想法和之前的什么有关联"
3. 如果提示中有[参考信息]，务必利用它来建立联系
4. 60 字以内
5. 不要用感叹号
""".trimIndent()

    private val llmClient = LlmClient()

    suspend fun generateFeedback(noteContent: String, persona: PartnerPersona = PartnerPersona.LIFE): Result<String> = withContext(Dispatchers.IO) {
        if (persona == PartnerPersona.WORK) {
            return@withContext Result.failure(IllegalStateException("点评在工作模式下禁用"))
        }

        val systemPrompt = when (persona) {
            PartnerPersona.LIFE -> PROMPT_LIFE
            PartnerPersona.CREATIVE -> PROMPT_CREATIVE
            PartnerPersona.WORK -> return@withContext Result.failure(IllegalStateException("点评在工作模式下禁用"))
        }

        runCatching {
            val settings = apiSettings()
                ?: return@runCatching throw IllegalStateException("ApiSettings 未初始化")

            val config = settings.getApiConfig()
                ?: return@runCatching throw IllegalStateException("API 未配置")

            if (config.baseUrl.isBlank()) {
                return@runCatching throw IllegalStateException("Base URL 为空")
            }

            val model = config.model.ifBlank { FALLBACK_MODEL }

            // 历史上下文收集：从海马体索引查找关联记录
            val contextHint = buildContextHint(noteContent)

            // 构造 user message（如果有上下文则追加参考信息）
            val userMessage = if (contextHint.isNotEmpty()) {
                "$noteContent\n\n[参考信息] $contextHint"
            } else {
                noteContent
            }

            DebugLog.i("WarmFeedbackService: 开始生成点评, model=$model, 输入=${noteContent.take(40)}...")

            val rawResult = withTimeoutOrNull(TIMEOUT_MS) {
                llmClient.chatCompletions(
                    config = config.copy(model = model),
                    system = systemPrompt,
                    messages = listOf(
                        ChatMessage(
                            role = Role.USER,
                            content = "请点评这段文字：\n$userMessage",
                            createdAt = System.currentTimeMillis(),
                        )
                    ),
                )
            } ?: run {
                return@runCatching throw TimeoutException("Feedback generation timeout")
            }

            postProcess(rawResult)
        }.onFailure { e ->
            DebugLog.w("WarmFeedbackService: 点评生成失败 - ${e.message}")
        }
    }

    private fun postProcess(raw: String): String {
        var result = raw.trim()

        // 空值或仅标点符号检查
        if (result.isEmpty() || result.all { it in "。，！？、；：\"\"''（）【】《》…—\n\r\t " }) {
            throw IllegalStateException("LLM 返回内容为空或仅含标点")
        }

        // 超长截断：找到最近的句号/句点
        if (result.length > MAX_DISPLAY_CHARS) {
            val truncated = result.take(MAX_DISPLAY_CHARS)
            val lastSentenceEnd = listOf('。', '.', '！', '!', '？', '?')
                .mapNotNull { punctuation -> truncated.lastIndexOf(punctuation) }
                .maxOrNull()
            result = if (lastSentenceEnd != null && lastSentenceEnd > MAX_DISPLAY_CHARS / 2) {
                truncated.take(lastSentenceEnd + 1)
            } else {
                truncated
            }
        }

        return result.trim()
    }

    /**
     * 从海马体索引中查找与当前笔记相关的历史记录
     */
    private suspend fun buildContextHint(currentContent: String): String {
        val index = hippocampus ?: return ""

        try {
            // 取最近 5 条记录（不限 scope）
            val recentItems = index.getAll().take(5)
            if (recentItems.isEmpty()) return ""

            // 简单关键词重叠检测
            val currentKeywords = extractKeywords(currentContent)
            if (currentKeywords.isEmpty()) return ""

            for (item in recentItems) {
                val itemKeywords = item.keywords.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                if (itemKeywords.isEmpty()) continue

                val overlap = currentKeywords.count { it in itemKeywords }
                val ratio = overlap.toFloat() / minOf(currentKeywords.size, itemKeywords.size)

                if (ratio > 0.3f) {  // 30% 重叠度阈值
                    val daysAgo = item.ageDays
                    val timeLabel = when {
                        daysAgo == 0 -> "今天"
                        daysAgo == 1 -> "昨天"
                        daysAgo <= 7 -> "${daysAgo}天前"
                        else -> "${item.ageDays}天前"
                    }
                    val hintPrefix = "用户" + timeLabel + "也提到过类似的："
                    return hintPrefix + item.summary.take(50)
                }
            }
        } catch (_: Exception) {
            // 历史查询失败不影响点评生成
        }

        return ""  // 无关联历史
    }

    /** 从文本中提取关键词 */
    private fun extractKeywords(text: String): List<String> {
        // 简单实现：取中文词组（2字以上）和英文单词
        val chineseWords = Regex("""[\u4e00-\u9fff]{2,4}""").findAll(text).map { it.value }.toList()
        val englishWords = Regex("""[a-zA-Z]{3,}""").findAll(text).map { it.value.lowercase() }.toList()
        return (chineseWords + englishWords).distinct().take(20)
    }
}
