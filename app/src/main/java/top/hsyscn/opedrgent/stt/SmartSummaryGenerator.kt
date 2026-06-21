package top.hsyscn.opedrgent.stt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import top.hsyscn.opedrgent.model.ChatMessage
import top.hsyscn.opedrgent.model.Role
import top.hsyscn.opedrgent.network.LlmClient
import top.hsyscn.opedrgent.settings.ApiConfig
import top.hsyscn.opedrgent.utils.DebugLog

/**
 * 智能总结生成器 — 将会议转录文本通过 LLM 生成结构化智能总结。
 *
 * 输出格式对齐得到大脑「智能总结」Tab 的 5 层结构:
 *   1. 录音信息 (MetaInfo) — 时长/参与人数/内容类型
 *   2. 录音总结 (SummarySection) — 多级标题 + 段落列表
 *   3. 章节概要 (ChapterItem) — 时间戳链接 + 章节摘要
 *   4. 金句精选 (QuoteItem) — 引用原文 + 分类标签
 *   5. 待办事项 (ActionItem) — 负责人 + 任务描述
 *
 * 使用方式:
 * ```kotlin
 * val generator = SmartSummaryGenerator(llmClient)
 * val summary = generator.generate(transcriptResult, apiConfig)
 * ```
 */
class SmartSummaryGenerator(
    private val llmClient: LlmClient,
) {

    /**
     * 根据转录结果生成结构化智能总结。
     *
     * @param transcript 转录结果（含分段和说话人信息）
     * @param apiConfig LLM API 配置
     * @return SmartSummary 对象（失败时返回 null）
     */
    suspend fun generate(
        transcript: MeetingTranscriptResult,
        apiConfig: ApiConfig,
    ): SmartSummary? = withContext(Dispatchers.IO) {
        try {
            if (transcript.fullText.isBlank()) {
                DebugLog.w(TAG, "转录文本为空，跳过总结")
                return@withContext null
            }

            val prompt = buildPrompt(transcript)
            val response = llmClient.chatCompletions(
                config = apiConfig,
                system = SYSTEM_PROMPT,
                messages = listOf(
                    ChatMessage(role = Role.USER, content = prompt),
                ),
            )

            if (response.isBlank()) {
                DebugLog.w(TAG, "LLM 返回空响应")
                return@withContext null
            }

            parseSmartSummary(response, transcript)
        } catch (e: Exception) {
            DebugLog.e(TAG, "智能总结生成失败: ${e.message}", e)
            null
        }
    }

    // ================================================================
    // Prompt 构建
    // ================================================================

    private fun buildPrompt(transcript: MeetingTranscriptResult): String {
        val sb = StringBuilder()
        sb.appendLine("请对以下录音转录内容生成智能总结。\n")

        // 元数据上下文
        sb.appendLine("## 录音元数据")
        sb.appendLine("- 总时长: ${TranscriptTimeFormatter.formatDuration(transcript.durationMs)}")
        sb.appendLine("- 总段数: ${transcript.segments.size}")
        sb.appendLine("- 参与说话人: ${transcript.speakers.joinToString(", ")}")
        sb.appendLine()

        // 转录正文（截断过长文本避免 token 超限）
        val maxChars = 8000
        val bodyText = if (transcript.fullText.length > maxChars) {
            transcript.fullText.take(maxChars) + "\n...(文本已截断)"
        } else {
            transcript.fullText
        }
        sb.appendLine("## 转录正文")
        sb.appendLine(bodyText)

        return sb.toString()
    }

    // ================================================================
    // JSON 解析 → SmartSummary 数据模型
    // ================================================================

    /**
     * 将 LLM 返回的 JSON 文本解析为 [SmartSummary]。
     *
     * 兼容两种输出格式:
     * 1. 纯 JSON 对象（LLM 直接返回 JSON）
     * 2. Markdown 代码块包裹的 JSON（```json ... ```）
     */
    private fun parseSmartSummary(rawResponse: String, transcript: MeetingTranscriptResult): SmartSummary? {
        return runCatching {
            val jsonStr = extractJson(rawResponse)
            val json = JSONObject(jsonStr)

            // MetaInfo
            val metaInfo = json.optJSONObject("metaInfo")?.let { obj ->
                SmartSummary.MetaInfo(
                    duration = obj.optString("duration", TranscriptTimeFormatter.formatDuration(transcript.durationMs)),
                    participantCount = optInt(obj, "participantCount", transcript.speakers.size),
                    contentType = obj.optString("contentType", "录音笔记"),
                )
            } ?: SmartSummary.MetaInfo(
                duration = TranscriptTimeFormatter.formatDuration(transcript.durationMs),
                participantCount = transcript.speakers.size,
                contentType = "录音笔记",
            )

            // SummarySections
            val sectionsJson = json.optJSONArray("summarySections") ?: JSONArray()
            val summarySections = (0 until sectionsJson.length()).map { i ->
                val sec = sectionsJson.getJSONObject(i)
                val contentArr = sec.optJSONArray("content") ?: JSONArray()
                SmartSummary.SummarySection(
                    title = sec.optString("title", ""),
                    content = (0 until contentArr.length()).map { contentArr.getString(it) },
                )
            }

            // Chapters
            val chaptersJson = json.optJSONArray("chapters") ?: JSONArray()
            val chapters = (0 until chaptersJson.length()).map { i ->
                val ch = chaptersJson.getJSONObject(i)
                SmartSummary.ChapterItem(
                    timestampSec = ch.optLong("timestampSec", 0L),
                    timestampFormatted = ch.optString("timestampFormatted", "00:00:00"),
                    title = ch.optString("title", ""),
                    summary = ch.optString("summary", ""),
                )
            }

            // Quotes
            val quotesJson = json.optJSONArray("quotes") ?: JSONArray()
            val quotes = (0 until quotesJson.length()).map { i ->
                val q = quotesJson.getJSONObject(i)
                SmartSummary.QuoteItem(
                    text = q.optString("text", ""),
                    category = q.optString("category", ""),
                )
            }

            // ActionItems
            val actionsJson = json.optJSONArray("actionItems") ?: JSONArray()
            val actionItems = (0 until actionsJson.length()).map { i ->
                val a = actionsJson.getJSONObject(i)
                SmartSummary.ActionItem(
                    assignee = a.optString("assignee", ""),
                    task = a.optString("task", ""),
                )
            }

            SmartSummary(
                metaInfo = metaInfo,
                summarySections = summarySections,
                chapters = chapters,
                quotes = quotes,
                actionItems = actionItems,
            )
        }.onFailure { e ->
            DebugLog.e(TAG, "JSON 解析失败: ${e.message}\n原始响应前200字: ${rawResponse.take(200)}", e)
        }.getOrNull()
    }

    /** 从 LLM 响应中提取 JSON 字符串（处理 markdown 代码块包裹） */
    private fun extractJson(raw: String): String {
        var text = raw.trim()

        // 移除 ```json ... ``` 包裹
        val jsonBlockStart = "```json"
        val jsonBlockEnd = "```"
        val startIdx = text.indexOf(jsonBlockStart)
        if (startIdx >= 0) {
            val contentStart = startIdx + jsonBlockStart.length
            val endIdx = text.indexOf(jsonBlockEnd, contentStart)
            if (endIdx > contentStart) {
                text = text.substring(contentStart, endIdx).trim()
            }
        } else {
            // 尝试普通 ``` 包裹
            val codeStart = text.indexOf("```")
            if (codeStart >= 0) {
                val contentStart = codeStart + 3
                val endIdx = text.indexOf("```", contentStart)
                if (endIdx > contentStart) {
                    text = text.substring(contentStart, endIdx).trim()
                }
            }
        }

        return text
    }

    private fun optInt(obj: JSONObject, key: String, fallback: Int): Int {
        return try { obj.getInt(key) } catch (_: Exception) { fallback }
    }

    companion object {
        private const val TAG = "SmartSummaryGen"

        /**
         * System Prompt — 要求 LLM 输出严格 JSON 格式的智能总结。
         *
         * 设计原则:
         * - 输出纯 JSON，不包含任何解释性文字
         * - 结构完全对齐 SmartSummary 数据模型
         * - 各字段提供清晰的填写指南
         */
        internal const val SYSTEM_PROMPT = """你是一个专业的会议录音智能总结引擎。你的任务是将用户提供的录音转录文本分析后，输出严格符合以下 JSON 格式的智能总结。

## 输出要求
1. 只输出一个 JSON 对象，不要任何其他文字、解释或 Markdown 标记（除了代码块）
2. 所有字段必须使用中文
3. 时间戳格式统一为 HH:MM:SS
4. 章节概要的时间戳应与转录内容中的实际时间点对应

## JSON Schema
{
  "metaInfo": {
    "duration": "如 '约19分钟'",
    "participantCount": 3,
    "contentType": "根据转录内容语义判断类型，如：工作会议 / 培训讲座 / 面试对话 / 人物访谈 / 课堂笔记 / 项目复盘 / 产品评审 / 日常对话 / 头脑风暴 / 述职汇报 / 客户沟通 / 技术分享 等"
  },
  "summarySections": [
    {
      "title": "一级标题（如总体概述）",
      "content": ["段落1", "段落2", "..."]
    },
    {
      "title": "二级标题（如具体议题讨论）",
      "content": ["段落1", "..."]
    }
  ],
  "chapters": [
    {
      "timestampSec": 29,
      "timestampFormatted": "00:00:29",
      "title": "章节标题",
      "summary": "该章节的核心内容摘要（1-2句话）"
    }
  ],
  "quotes": [
    {
      "text": "引用原文（保留原话）",
      "category": "分类标签：战略洞见 / 思考启发 / 方法技巧 / 关键数据 / 情感表达 / 幽默金句"
    }
  ],
  "actionItems": [
    {
      "assignee": "负责人（如 '主持人' / '张三' 或 '待定'）",
      "task": "具体任务描述"
    }
  ]
}

## 总结质量标准
- metaInfo: 准确反映录音的基本属性
- summarySections: 按 2-4 个主题维度组织，每个 section 包含 1-3 个段落
- chapters: 3-8 个关键时间节点，每个对应一个话题转折点
- quotes: 3-6 条最有价值的原话引用，覆盖不同分类
- actionItems: 提取明确的行动项，无则返回空数组"""
    }
}
