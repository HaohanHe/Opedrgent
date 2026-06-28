package top.hsyscn.opedrgent.note

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*

/**
 * 笔记数据模型。
 *
 * 支持多种笔记类型：
 * - TEXT: 普通文本/Markdown 笔记
 * - ASR: 语音转文字笔记
 * - MEETING: 会议录音转录笔记
 * - LINK: 链接收藏笔记
 * - QUICK: 快速闪念笔记
 * - AI_CHAT: AI 对话保存为笔记
 */
data class Note(
    val id: Long = 0,

    /** 笔记标题（可为空，自动从内容提取首行） */
    var title: String,

    /** 笔记内容（Markdown 格式） */
    var content: String,

    /** 纯文本摘要（用于列表展示，前 200 字） */
    var summary: String = "",

    /** 笔记类型 */
    var type: NoteType = NoteType.TEXT,

    /** 所属文件夹 ID，null 表示根目录 */
    var folderId: Long? = null,

    /** 标签列表（JSON 序列化的字符串数组） */
    var tagsJson: String = "[]",

    /** 是否置顶 */
    var isPinned: Boolean = false,

    /** 是否已删除（软删除） */
    var isDeleted: Boolean = false,

    /** 关联的来源（如会议录音文件路径、链接 URL 等） */
    var sourceUri: String? = null,

    /** 创建时间戳（毫秒） */
    val createdAt: Long = System.currentTimeMillis(),

    /** 最后修改时间戳（毫秒） */
    var updatedAt: Long = System.currentTimeMillis(),

    /** 字数统计 */
    var wordCount: Int = 0,

    /** AI 发芽报告（JSON 格式存储结构化分析结果） */
    var sproutReportJson: String? = null,

    /** 原文内容（如果是链接/文档类笔记，保存原始文本） */
    var originalContent: String? = null,

    /** 来源 URL（链接笔记使用） */
    var sourceUrl: String = "",

    /** 笔记来源类型：手动创建 / ASR转录 / AI生成 / 链接提取 */
    var sourceType: SourceType = SourceType.MANUAL,

    /** 富文本格式信息（JSON array of SpanRepresentation），空字符串表示纯文本 */
    var spans: String = "",
) {
    /** 获取标签列表 */
    fun getTags(): List<String> {
        return try {
            val arr = org.json.JSONArray(tagsJson)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) { emptyList() }
    }

    /** 设置标签列表 */
    fun setTags(tags: List<String>) {
        tagsJson = try {
            org.json.JSONArray(tags).toString()
        } catch (_: Exception) { "[]" }
    }

    /** 获取 AI 发芽报告（旧版结构化） */
    fun getSproutReport(): SproutReport? {
        return sproutReportJson?.let { SproutReport.fromJson(it) }
    }

    /** 设置 AI 发芽报告（旧版结构化） */
    fun setSproutReport(report: SproutReport?) {
        sproutReportJson = report?.toJson()
    }

    /** 获取 AI 发芽文章（新版叙事式） */
    fun getSproutArticle(): SproutArticle? {
        return sproutReportJson?.let { SproutArticle.fromJson(it) }
    }

    /** 设置 AI 发芽文章（新版叙事式） */
    fun setSproutArticle(article: SproutArticle?) {
        sproutReportJson = article?.toJson()
    }

    /** 是否有发芽报告（任一版本） */
    fun hasSproutReport(): Boolean = !sproutReportJson.isNullOrBlank()
}

enum class NoteType {
    TEXT,       // 普通文本/Markdown
    ASR,        // 语音转文字
    MEETING,    // 会议录音转录
    LINK,       // 链接收藏
    QUICK,      // 快速闪念
    AI_CHAT,    // AI 对话保存
    IMAGE,      // 图片笔记
    PDF,        // PDF文档笔记
    AUDIO,      // 音频笔记
    BOOK,       // 电子书笔记
}

/**
 * 笔记来源类型 — Opedrgent 特色：追踪笔记的创建方式
 */
enum class SourceType {
    MANUAL,     // 用户手动创建
    ASR,        // 语音识别自动生成
    AI_GENERATED, // AI 根据提示生成
    LINK_EXTRACT, // 链接内容自动提取
    DOCUMENT_IMPORT, // 文档导入
    MEETING_TRANSCRIPT, // 会议转录
}

/**
 * AI 发芽报告 — Opedrgent 核心特色功能
 *
 * 将原始笔记内容通过 AI 分析，生成结构化洞察报告，包含：
 * - 核心观点提炼
 * - 关键要点列表
 * - 震惊瞬间（高亮重要发现）
 * - 行动建议
 * - 相关概念链接
 */
data class SproutReport(
    /** 报告生成时间 */
    val generatedAt: Long = System.currentTimeMillis(),
    /** 使用的 AI 模型 */
    val modelUsed: String = "",
    /** 核心观点摘要（1-2句话） */
    val summary: String = "",
    /** 关键要点列表（3-5条） */
    val keyPoints: List<String> = emptyList(),
    /** 震惊瞬间 — 高亮的重要发现或洞察 */
    val shockingMoments: List<ShockingMoment> = emptyList(),
    /** 行动建议 */
    val actionItems: List<String> = emptyList(),
    /** 相关概念/标签 */
    val relatedConcepts: List<String> = emptyList(),
    /** 情感倾向分析 */
    val sentiment: Sentiment = Sentiment.NEUTRAL,
    /** 阅读时间估计（分钟） */
    val readingTimeMinutes: Int = 0,
) {
    fun toJson(): String {
        return try {
            val json = org.json.JSONObject().apply {
                put("generatedAt", generatedAt)
                put("modelUsed", modelUsed)
                put("summary", summary)
                put("keyPoints", org.json.JSONArray(keyPoints))
                put("ahaMoments", org.json.JSONArray(shockingMoments.map { it.toJson() }))
                put("actionItems", org.json.JSONArray(actionItems))
                put("relatedConcepts", org.json.JSONArray(relatedConcepts))
                put("sentiment", sentiment.name)
                put("readingTimeMinutes", readingTimeMinutes)
            }
            json.toString()
        } catch (_: Exception) { "{}" }
    }

    companion object {
        fun fromJson(jsonStr: String): SproutReport? {
            return try {
                val json = org.json.JSONObject(jsonStr)
                SproutReport(
                    generatedAt = json.optLong("generatedAt", System.currentTimeMillis()),
                    modelUsed = json.optString("modelUsed", ""),
                    summary = json.optString("summary", ""),
                    keyPoints = json.optJSONArray("keyPoints")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    } ?: emptyList(),
                    shockingMoments = json.optJSONArray("ahaMoments")?.let { arr ->
                        (0 until arr.length()).mapNotNull {
                            ShockingMoment.fromJson(arr.getJSONObject(it).toString())
                        }
                    } ?: emptyList(),
                    actionItems = json.optJSONArray("actionItems")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    } ?: emptyList(),
                    relatedConcepts = json.optJSONArray("relatedConcepts")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    } ?: emptyList(),
                    sentiment = try { Sentiment.valueOf(json.optString("sentiment", "NEUTRAL")) } catch (_: Exception) { Sentiment.NEUTRAL },
                    readingTimeMinutes = json.optInt("readingTimeMinutes", 0),
                )
            } catch (_: Exception) { null }
        }
    }
}

/**
 * 震惊瞬间 — 笔记中的高光时刻
 */
data class ShockingMoment(
    /** 原文引用 */
    val quote: String,
    /** AI 解读/点评 */
    val insight: String,
    /** 重要性等级 1-5 */
    val importance: Int = 3,
    /** 在原文中的大致位置（字符偏移） */
    val position: Int = 0,
) {
    fun toJson(): String {
        return org.json.JSONObject().apply {
            put("quote", quote)
            put("insight", insight)
            put("importance", importance)
            put("position", position)
        }.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): ShockingMoment? {
            return try {
                val json = org.json.JSONObject(jsonStr)
                ShockingMoment(
                    quote = json.optString("quote", ""),
                    insight = json.optString("insight", ""),
                    importance = json.optInt("importance", 3),
                    position = json.optInt("position", 0),
                )
            } catch (_: Exception) { null }
        }
    }
}

enum class Sentiment {
    POSITIVE,   // 积极
    NEUTRAL,    // 中性
    NEGATIVE,   // 消极
    MIXED,      // 混合
}

/**
 * 叙事式发芽文章 — Opedrgent 核心特色
 *
 * ## 设计理念（从 3 张引导图学习）
 *
 * 发芽报告不是结构化数据，而是**叙事式文章**：
 * ```
 * 01. 一座王宫换来的大学          ← 编号标题
 * 🌱 种子                         ← 原文触发点（灰色）
 * 因为你提到了洪堡兄弟...           ← 哪段笔记触发了这个洞察
 *
 * 1807年，普鲁士在耶拿战役中...     ← AI 展开叙述（加粗关键词）
 * 💡 震惊瞬间                     ← 高光金句
 * "最勇敢的投资，不是在顺顺..."      ← 最有洞察力的那句话
 * ```
 *
 * ## 与旧版 SproutReport 的区别
 *
 * | 维度 | 旧版 SproutReport | 新版 SproutArticle |
 * |------|-------------------|--------------------|
 * | 格式 | 结构化 JSON 字段 | Markdown 叙事文章 |
 * | 内容 | 要点列表 | 完整文章（种子+正文+震惊瞬间） |
 * | 可读性 | 机器友好 | 人类友好 |
 * | 视觉 | 卡片堆叠 | 文章流式阅读 |
 */
data class SproutArticle(
    /** 报告生成时间 */
    val generatedAt: Long = System.currentTimeMillis(),
    /** 使用的 AI 模型 */
    val modelUsed: String = "",
    /** 整体摘要（1-2句话，用于列表预览） */
    val summary: String = "",
    /** 发芽文章列表 — 每篇是一个独立洞察 */
    val articles: List<ArticleSection> = emptyList(),
    /** 行动建议（可勾选） */
    val actionItems: List<String> = emptyList(),
    /** 相关概念标签 */
    val relatedConcepts: List<String> = emptyList(),
    /** 情感倾向 */
    val sentiment: Sentiment = Sentiment.NEUTRAL,
    /** 阅读时间估计（分钟） */
    val readingTimeMinutes: Int = 0,
) {
    fun toJson(): String {
        return try {
            val json = org.json.JSONObject().apply {
                put("generatedAt", generatedAt)
                put("modelUsed", modelUsed)
                put("summary", summary)
                put("articles", org.json.JSONArray(articles.map { it.toJson() }))
                put("actionItems", org.json.JSONArray(actionItems))
                put("relatedConcepts", org.json.JSONArray(relatedConcepts))
                put("sentiment", sentiment.name)
                put("readingTimeMinutes", readingTimeMinutes)
            }
            json.toString()
        } catch (_: Exception) { "{}" }
    }

    companion object {
        fun fromJson(jsonStr: String): SproutArticle? {
            return try {
                val json = org.json.JSONObject(jsonStr)
                SproutArticle(
                    generatedAt = json.optLong("generatedAt", System.currentTimeMillis()),
                    modelUsed = json.optString("modelUsed", ""),
                    summary = json.optString("summary", ""),
                    articles = json.optJSONArray("articles")?.let { arr ->
                        (0 until arr.length()).mapNotNull { i ->
                            // toJson() 存储为字符串数组，用 getString 读取
                            ArticleSection.fromJson(arr.getString(i))
                        }
                    } ?: emptyList(),
                    actionItems = json.optJSONArray("actionItems")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    } ?: emptyList(),
                    relatedConcepts = json.optJSONArray("relatedConcepts")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    } ?: emptyList(),
                    sentiment = try { Sentiment.valueOf(json.optString("sentiment", "NEUTRAL")) } catch (_: Exception) { Sentiment.NEUTRAL },
                    readingTimeMinutes = json.optInt("readingTimeMinutes", 0),
                )
            } catch (_: Exception) { null }
        }
    }
}

/**
 * 单篇发芽文章 — 对应一个编号段落（01/02/03...）
 *
 * 三段式结构：
 * 1. 🌱 种子：原文中触发这段洞察的片段
 * 2. 正文：AI 展开叙述的完整分析（Markdown 格式）
 * 3. 💡 震惊瞬间：最有力的一句金句
 */
data class ArticleSection(
    /** 编号标题（如"01. 一座王宫换来的大学"） */
    val title: String,
    /** 🌱 种子 — 原文触发点（用户笔记中的原始内容） */
    val seed: String,
    /** 正文 — AI 生成的完整分析（支持 Markdown） */
    val body: String,
    /** 💡 震惊瞬间 — 金句引用 */
    val shockingMoment: String,
    /** 重要性 1-5 */
    val importance: Int = 3,
) {
    fun toJson(): String {
        return org.json.JSONObject().apply {
            put("title", title)
            put("seed", seed)
            put("body", body)
            put("ahaMoment", shockingMoment)
            put("importance", importance)
        }.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): ArticleSection? {
            return try {
                val json = org.json.JSONObject(jsonStr)
                ArticleSection(
                    title = json.optString("title", ""),
                    seed = json.optString("seed", ""),
                    body = json.optString("body", ""),
                    shockingMoment = json.optString("ahaMoment", ""),
                    importance = json.optInt("importance", 3).coerceIn(1, 5),
                )
            } catch (_: Exception) { null }
        }
    }
}

fun NoteType.icon(): ImageVector = when (this) {
    NoteType.TEXT -> Icons.Default.Notes
    NoteType.ASR -> Icons.Default.Mic
    NoteType.MEETING -> Icons.Default.Groups
    NoteType.LINK -> Icons.Default.Link
    NoteType.QUICK -> Icons.Default.Bolt
    NoteType.AI_CHAT -> Icons.Default.AutoAwesome
    NoteType.IMAGE -> Icons.Default.Image
    NoteType.PDF -> Icons.Default.PictureAsPdf
    NoteType.AUDIO -> Icons.Default.Headphones
    NoteType.BOOK -> Icons.Default.MenuBook
}

fun NoteType.color(): Color = when (this) {
    NoteType.TEXT -> Color(0xFF4A90D9)
    NoteType.ASR -> Color(0xFFE67E22)
    NoteType.MEETING -> Color(0xFF9B59B6)
    NoteType.LINK -> Color(0xFF3498DB)
    NoteType.QUICK -> Color(0xFFF39C12)
    NoteType.AI_CHAT -> Color(0xFF1ABC9C)
    NoteType.IMAGE -> Color(0xFF2ECC71)
    NoteType.PDF -> Color(0xFFE74C3C)
    NoteType.AUDIO -> Color(0xFF9B59B6)
    NoteType.BOOK -> Color(0xFF34495E)
}

fun NoteType.displayName(): String = when (this) {
    NoteType.TEXT -> "文本"
    NoteType.ASR -> "语音"
    NoteType.MEETING -> "会议"
    NoteType.LINK -> "链接"
    NoteType.QUICK -> "闪念"
    NoteType.AI_CHAT -> "AI"
    NoteType.IMAGE -> "图片"
    NoteType.PDF -> "PDF"
    NoteType.AUDIO -> "音频"
    NoteType.BOOK -> "电子书"
}
