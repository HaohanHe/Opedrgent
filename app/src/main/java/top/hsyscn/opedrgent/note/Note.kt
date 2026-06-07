package top.hsyscn.opedrgent.note

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*

/**
 * 笔记数据模型（参考得到大脑笔记系统设计）。
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
