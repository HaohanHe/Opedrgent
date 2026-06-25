package top.hsyscn.opedrgent.note

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import top.hsyscn.opedrgent.settings.ApiSettings
import top.hsyscn.opedrgent.storage.HippocampusIndex
import top.hsyscn.opedrgent.utils.DebugLog
import java.util.concurrent.TimeUnit

/**
 * AI 发芽服务 — Opedrgent 核心特色功能（v2 叙事式）
 *
 * ## 设计理念
 *
 * 发芽报告不是结构化数据卡片，而是**叙事式文章**：
 * ```
 * 01. 一座王宫换来的大学          ← 编号标题（吸引眼球）
 * 🌱 种子                         ← 原文触发点（灰色，展示来源）
 * 因为你提到了洪堡兄弟...
 *
 * 1807年，普鲁士在耶拿战役中...     ← AI 展开叙述（加粗关键词、引用）
 * 💡 Aha 瞬间                     ← 高光金句
 * "最勇敢的投资，不是在顺顺..."
 * ```
 *
 * ## v1 → v2 升级
 *
 * - v1: 结构化 JSON（SproutReport）— 机器友好，人类读起来枯燥
 * - v2: 叙事式文章（SproutArticle）— 人类友好，像在读杂志专栏
 */
class SproutService(private val apiSettings: ApiSettings, private val hippocampus: HippocampusIndex? = null) {

    companion object {
        private const val TAG = "SproutService"
        private const val DEFAULT_MODEL = "mimo-v2.5"

        /**
         * 叙事式发芽提示词 — 产出 SproutArticle 格式
         *
         * 核心设计：让 AI 像《经济学人》专栏作家一样写分析文章，
         * 每篇文章有独立的编号标题、种子引用、正文展开和金句收尾。
         */
        private val SPROUT_PROMPT_NARRATIVE = """
你是一位顶级知识管理顾问兼深度内容分析师。请对以下笔记进行"发芽"处理——将其转化为一系列引人入胜的洞察文章。

【用户笔记】
%s

## 输出格式要求（严格 JSON）

{
  "summary": "整份报告的一句话灵魂概括",
  "articles": [
    {
      "title": "01. 吸引眼球的编号标题（概括这个洞察的核心）",
      "seed": "种子：从用户笔记中摘取触发这段分析的原文片段（50-100字）",
      "body": "正文：用**加粗**强调关键概念，用> 引用重要数据。写一篇300-500字的深度分析，像专栏文章一样流畅。要有论点、论据、案例。不要用列表形式，要写成连贯的叙述。",
      "ahaMoment": "Aha 瞬间：这段分析中最有力的一句金句（20-40字），让人读了会'啊！原来如此'的感觉",
      "importance": 5
    }
  ],
  "actionItems": ["具体可执行的行动建议"],
  "relatedConcepts": ["相关概念/领域标签"],
  "sentiment": "POSITIVE|NEUTRAL|NEGATIVE|MIXED",
  "readingTimeMinutes": 3
}

## 写作风格指南

1. **标题要抓人**：像公众号爆款标题一样，但不要标题党。如"01. 为什么'富'只能排第二？"
2. **种子要精准**：明确指出是笔记的哪段内容触发了这个洞察，让用户看到 AI 的推理路径
3. **正文要有深度**：
   - 不要泛泛而谈，要深入到具体案例和数据
   - 用类比帮助理解抽象概念
   - 适当使用反问引发思考
   - 加粗关键术语（**关键概念**）
   - 重要数据用引用块（> 数据说明）
4. **Aha 要震撼**：每篇只有一个 Aha，必须是全文最精华的那句话
5. **生成 2-4 篇文章**，覆盖笔记的不同维度
6. **总字数控制在 1500-2500 字**

只输出 JSON，不要任何其他文字。
""".trimIndent()
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // ==================== 公开 API ====================

    /**
     * 为笔记生成叙事式发芽报告。
     *
     * 发芽是一次性调用，但单次调用内使用三层渐进式上下文注入：
     * 1. 标签层：海马体关键词聚合，让 AI 快速感知知识背景（几乎不占上下文）
     * 2. 索引层：海马体相关条目 + 其他笔记，只给标题和一句话概要（轻量）
     * 3. 联网搜索提示：要求 AI 验证关键事实
     *
     * @param noteContent 笔记内容
     * @param otherNotesContext 其他笔记的轻量索引（标题 + 一句话概要）
     * @param modelId 使用的模型 ID
     * @return [SproutArticle] 叙事式发芽报告
     */
    suspend fun sprout(
        noteContent: String,
        otherNotesContext: String = "",
        modelId: String = apiSettings.getModel() ?: DEFAULT_MODEL,
    ): Result<SproutArticle> = withContext(Dispatchers.IO) {
        try {
            var prompt = SPROUT_PROMPT_NARRATIVE.format(noteContent.take(8000))

            val searchKeywords = noteContent.take(200)
                .split(Regex("[\\s,，。、；：！？\\n]+"))
                .filter { it.length >= 2 }
                .take(3)
            val relatedItems = if (hippocampus != null && searchKeywords.isNotEmpty()) {
                searchKeywords.flatMap { hippocampus.query(it, 3) }
                    .distinctBy { it.id }
                    .take(5)
            } else emptyList()

            // 第一层：标签 — 从海马体索引中聚合关键词，让 AI 快速感知知识背景
            val allKeywords = relatedItems
                .flatMap { it.keywords.split(",").map(String::trim) }
                .filter { it.length >= 2 }
                .distinct()
                .take(10)
            if (allKeywords.isNotEmpty()) {
                prompt += "\n\n## 知识库标签\n${allKeywords.joinToString("、")}"
            }

            // 第二层：索引 — 海马体相关条目 + 其他笔记，只给标题和一句话概要
            if (relatedItems.isNotEmpty()) {
                val indexLines = relatedItems.joinToString("\n") {
                    "- [${it.sourceType.label}] ${it.title}：${it.summary.take(80)}"
                }
                prompt += "\n\n## 相关知识索引\n$indexLines"
            }
            if (otherNotesContext.isNotBlank()) {
                prompt += "\n\n## 其他笔记索引\n$otherNotesContext"
            }

            prompt += "\n\n## 重要：联网搜索验证\n在分析过程中，请主动使用联网搜索工具验证关键事实、查找相关案例和数据。搜索至少2个关键词，但不超过3次搜索。"

            val apiKey = apiSettings.getApiKey()
                ?: return@withContext Result.failure(IllegalStateException("API Key 未设置"))

            val baseUrl = apiSettings.getBaseUrl().removeSuffix("/")

            val jsonBody = JSONObject().apply {
                put("model", modelId)
                put("max_tokens", 32768)
                put("temperature", 1.0)
                put("top_p", 0.95)
                put("top_k", 20)
                put("presence_penalty", 1.5)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", "你是顶级知识管理顾问+深度内容分析师。你的写作风格类似《经济学人》中文版专栏——专业但不晦涩，深刻但不做作。必须严格输出 JSON 格式。")
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
            }

            val url = if (baseUrl.endsWith("/v1") || baseUrl.endsWith("/v2") || baseUrl.endsWith("/v3")) {
                "$baseUrl/chat/completions"
            } else {
                "$baseUrl/v1/chat/completions"
            }

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val error = "HTTP ${response.code}"
                    DebugLog.e(TAG, "发芽请求失败: $error")
                    return@withContext Result.failure(RuntimeException(error))
                }

                val body = response.body?.string() ?: return@withContext Result.failure(
                    RuntimeException("空响应")
                )

                parseNarrativeResponse(body, modelId)
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "发芽失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 流式生成（包装为 Flow 接口）。
     */
    fun sproutStreaming(
        noteContent: String,
        modelId: String = apiSettings.getModel() ?: DEFAULT_MODEL,
    ): Flow<SproutProgress> = flow {
        emit(SproutProgress.Loading)
        try {
            val result = sprout(noteContent, modelId = modelId)
            result.fold(
                onSuccess = { emit(SproutProgress.ArticleSuccess(it)) },
                onFailure = { emit(SproutProgress.Error(it.message ?: "未知错误")) },
            )
        } catch (e: Exception) {
            emit(SproutProgress.Error(e.message ?: "未知错误"))
        }
    }.flowOn(Dispatchers.IO)

    /** 重新生成（覆盖旧报告） */
    suspend fun resprout(note: Note): Result<SproutArticle> = sprout(note.content)

    /** 批量发芽 */
    suspend fun batchSprout(notes: List<Note>): Map<Long, Result<SproutArticle>> {
        val results = mutableMapOf<Long, Result<SproutArticle>>()
        for (note in notes) {
            results[note.id] = sprout(note.content)
            kotlinx.coroutines.delay(500)
        }
        return results
    }

    /**
     * 批量发芽 — 对多篇笔记并发执行发芽分析
     * 并发数限制为 2（控制 token 消耗）
     */
    suspend fun sproutBatch(
        notes: List<Note>,
        otherNotesContext: String = "",
    ): List<Result<SproutArticle>> {
        if (notes.isEmpty()) return emptyList()
        return coroutineScope {
            notes.map<Note, Deferred<Result<SproutArticle>>> { note ->
                async<Result<SproutArticle>> {
                    try {
                        sprout(note.content, otherNotesContext)
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                }
            }.awaitAll()
        }
    }

    // ==================== 解析器 ====================

    private fun parseNarrativeResponse(responseBody: String, modelUsed: String): Result<SproutArticle> {
        return try {
            val json = JSONObject(responseBody)
            val choices = json.optJSONArray("choices")
                ?: return Result.failure(RuntimeException("响应格式错误：无 choices"))

            if (choices.length() == 0) return Result.failure(RuntimeException("响应为空"))

            var content = choices.getJSONObject(0)
                .getJSONObject("message")
                .optString("content", "")

            content = stripThinkingTags(content)

            DebugLog.d(TAG, "LLM 原始响应 (${content.length} 字符): ${content.take(300)}")

            val jsonStr = extractJsonFromMarkdown(content) ?: content.trim()
            DebugLog.d(TAG, "提取的 JSON (${jsonStr.length} 字符): ${jsonStr.take(300)}")

            val articleResult = extractSproutArticle(jsonStr)
            if (articleResult.isSuccess) {
                val article = articleResult.getOrThrow().copy(modelUsed = modelUsed)
                DebugLog.i(TAG, "叙事式发芽成功: ${article.summary.take(50)}... (${article.articles.size}篇)")
                Result.success(article)
            } else {
                DebugLog.e(TAG, "发芽解析失败: ${articleResult.exceptionOrNull()?.message}")
                articleResult
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "解析发芽响应失败: ${e.message}")
            Result.failure(e)
        }
    }

    private fun extractJsonFromMarkdown(content: String): String? {
        val regex = Regex("```(?:json)?\\s*([\\s\\S]*?)```")
        return regex.find(content)?.groupValues?.get(1)?.trim()
    }

    private fun stripThinkingTags(content: String): String {
        return content.replace(Regex("<think>[\\s\\S]*?</think>\\s*"), "").trim()
    }

    /**
     * 修复 LLM 返回的 JSON 字符串值内的实际换行符。
     * LLM 常在 body/seed 等长文本字段中输出真正的换行，
     * 这在 JSON 字符串值内是非法的，导致 "Unterminated string" 或 "Unterminated array" 解析失败。
     */
    private fun fixJsonString(raw: String): String {
        val sb = StringBuilder(raw.length)
        var inString = false
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && inString) {
                sb.append(c)
                if (i + 1 < raw.length) {
                    sb.append(raw[i + 1])
                    i += 2
                    continue
                }
            } else if (c == '"') {
                inString = !inString
                sb.append(c)
            } else if (inString) {
                when (c) {
                    '\n' -> sb.append("\\n")
                    '\r' -> { /* 跳过 */ }
                    '\t' -> sb.append("\\t")
                    else -> sb.append(c)
                }
            } else {
                sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    /**
     * 从 LLM 输出中提取 JSON 对象。
     * 如果 fixJsonString 后仍解析失败，尝试按字段逐块提取。
     */
    private fun extractSproutArticle(jsonStr: String): Result<SproutArticle> {
        // 第一次尝试：标准 JSON 解析
        val fixed = fixJsonString(jsonStr)
        val tryParse = runCatching {
            val obj = JSONObject(fixed)
            parseSproutJson(obj)
        }
        if (tryParse.isSuccess) return tryParse

        // 第二次尝试：用正则逐字段提取（支持多篇文章）
        DebugLog.w("SproutService: standard JSON parse failed: ${tryParse.exceptionOrNull()?.message}")
        DebugLog.d("SproutService: raw JSON (${fixed.length} 字符): ${fixed.take(500)}")
        return try {
            val summary = Regex("\"summary\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*?)\"").find(fixed)?.groupValues?.get(1) ?: ""

            // 提取所有文章块：匹配 { "title": "...", "seed": "...", "body": "...", "ahaMoment": "..." }
            // title/seed/body 用 [\s\S]*? 非贪婪匹配，允许字段值内出现未转义双引号
            // （LLM 偶尔会在 body 中输出 "未来次数" 这样的未转义引号，导致标准 JSON 解析失败）。
            // 各字段以"下一字段名"作为终止锚点，避免在未转义引号处提前截断。
            val articleSections = mutableListOf<ArticleSection>()
            val articlePattern = Regex(
                "\\{\\s*\"title\"\\s*:\\s*\"([\\s\\S]*?)\"\\s*,\\s*\"seed\"\\s*:\\s*\"([\\s\\S]*?)\"\\s*,\\s*\"body\"\\s*:\\s*\"([\\s\\S]*?)\"\\s*,\\s*\"ahaMoment\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*?)\"(?:\\s*,\\s*\"importance\"\\s*:\\s*(\\d+))?\\s*\\}",
                RegexOption.DOT_MATCHES_ALL
            )
            for (match in articlePattern.findAll(fixed)) {
                val title = match.groupValues[1].replace("\\\"", "\"")
                val seed = match.groupValues[2].replace("\\n", "\n").replace("\\\"", "\"")
                val body = match.groupValues[3].replace("\\n", "\n").replace("\\\"", "\"")
                val ahaMoment = match.groupValues[4].replace("\\\"", "\"")
                val importance = match.groupValues[5].toIntOrNull()?.coerceIn(1, 5) ?: 3
                if (title.isNotBlank() || body.isNotBlank()) {
                    articleSections.add(ArticleSection(
                        title = title, seed = seed, body = body,
                        ahaMoment = ahaMoment, importance = importance,
                    ))
                }
            }

            if (articleSections.isEmpty()) {
                // 最后兜底：只提取 title 和 body
                val title = Regex("\"title\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*?)\"").find(fixed)?.groupValues?.get(1) ?: ""
                val body = Regex("\"body\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*?)\"", RegexOption.DOT_MATCHES_ALL).find(fixed)?.groupValues?.get(1) ?: ""
                if (title.isNotBlank() || body.isNotBlank()) {
                    articleSections.add(ArticleSection(
                        title = title.replace("\\\"", "\""),
                        seed = "", body = body.replace("\\n", "\n").replace("\\\"", "\""),
                        ahaMoment = "",
                    ))
                }
            }

            DebugLog.i("SproutService: regex extracted ${articleSections.size} articles")
            if (articleSections.isEmpty()) {
                DebugLog.w("SproutService: regex extraction produced 0 articles, raw content: ${fixed.take(500)}")
                return Result.failure(RuntimeException("无法从 LLM 响应中提取文章内容"))
            }
            val article = SproutArticle(
                generatedAt = System.currentTimeMillis(),
                modelUsed = "",
                summary = summary.replace("\\n", "\n").replace("\\\"", "\""),
                articles = articleSections
            )
            Result.success(article)
        } catch (e: Exception) {
            Result.failure(RuntimeException("JSON 解析失败: ${e.message}"))
        }
    }

    private fun parseSproutJson(obj: JSONObject): SproutArticle {
        val articles = obj.optJSONArray("articles")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                try {
                    val secObj = arr.getJSONObject(i)
                    ArticleSection(
                        title = secObj.optString("title", ""),
                        seed = secObj.optString("seed", ""),
                        body = secObj.optString("body", ""),
                        ahaMoment = secObj.optString("ahaMoment", ""),
                        importance = secObj.optInt("importance", 3).coerceIn(1, 5),
                    )
                } catch (_: Exception) { null }
            }
        } ?: emptyList()
        if (articles.isEmpty()) {
            throw RuntimeException("articles 数组为空或解析失败")
        }
        return SproutArticle(
            generatedAt = System.currentTimeMillis(),
            modelUsed = "",
            summary = obj.optString("summary", ""),
            articles = articles
        )
    }
}

/**
 * 发芽进度状态（v2 支持叙事式文章）
 */
sealed class SproutProgress {
    object Loading : SproutProgress()
    data class ArticleSuccess(val article: SproutArticle) : SproutProgress()
    data class Error(val message: String) : SproutProgress()
}
