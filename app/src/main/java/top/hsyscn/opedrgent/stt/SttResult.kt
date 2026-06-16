package top.hsyscn.opedrgent.stt

/**
 * 说话人调色板 — 为每个说话人分配一致的视觉颜色。
 *
 * 参考得到大脑的 sentence-index 圆形颜色编码方案：
 *   - 每个说话人分配唯一 RGB 颜色
 *   - 同一说话人的所有句子使用相同颜色
 *   - 预设一组高辨识度的柔和色彩
 */
object SpeakerColorPalette {

    /** 预设说话人颜色 (RGB int)，参考得到大脑配色风格 */
    private val PRESET_COLORS = listOf(
        0xFF8CCFEB.toInt(), // 蓝 rgb(140, 207, 235)
        0xFFEAB37E.toInt(), // 橙 rgb(234, 179, 126)
        0xFFDFC979.toInt(), // 黄绿 rgb(223, 201, 121)
        0xFFA0C4FF.toInt(), // 浅蓝 rgb(160, 196, 255)
        0xFFB4E197.toInt(), // 粉绿 rgb(180, 225, 151)
        0xFFF5A962.toInt(), // 金橙 rgb(245, 169, 98)
        0xFF9BC4F2.toInt(), // 天蓝 rgb(155, 196, 242)
        0xFFD4A574.toInt(), // 珊瑚 rgb(212, 165, 116)
        0xFF8ED1A7.toInt(), // 薄荷 rgb(142, 209, 167)
        0xFFC9A0DC.toInt(), // 薰衣草 rgb(201, 160, 220)
    )

    /** 已分配的颜色缓存: speakerId -> colorInt */
    private val assignedColors = mutableMapOf<String, Int>()

    /** 下一个可用预设颜色的索引 */
    private var nextColorIndex = 0

    /**
     * 获取指定说话人的颜色。
     * 同一 speakerId 始终返回相同颜色；新说话人按顺序分配预设色。
     */
    fun getColor(speakerId: String): Int {
        return assignedColors.getOrPut(speakerId) {
            val color = PRESET_COLORS[nextColorIndex % PRESET_COLORS.size]
            nextColorIndex++
            color
        }
    }

    /** 重置所有已分配的颜色（新会话时调用） */
    fun reset() {
        assignedColors.clear()
        nextColorIndex = 0
    }

    /**
     * 获取说话人显示名称。
     * 将 "Speaker_0", "speaker-1" 等原始 ID 转换为 "说话人1", "说话人2" 格式（参考得到大脑）。
     */
    fun formatSpeakerName(speakerId: String?): String {
        if (speakerId.isNullOrBlank()) return "未知"
        // 提取数字: Speaker_0 -> 0, speaker-1 -> 1
        val num = Regex("(\\d+)").find(speakerId)?.groupValues?.get(1)?.toIntOrNull()
        return if (num != null) "说话人${num + 1}" else speakerId
    }
}

/** 时间格式化工具 */
object TranscriptTimeFormatter {

    /**
     * 将毫秒数格式化为 HH:MM:SS 格式（参考得到大脑 sentence-starttime）。
     */
    fun formatMsToHMS(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    /**
     * 将秒数格式化为 HH:MM:SS 格式。
     */
    fun formatSecToHMS(sec: Long): String {
        val hours = sec / 3600
        val minutes = (sec % 3600) / 60
        val seconds = sec % 60
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    /** 格式化时长为可读字符串: "X分Y秒" 或 "X小时Y分" */
    fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return when {
            hours > 0 -> "${hours}小时${minutes}分"
            minutes > 0 -> "${minutes}分${seconds}秒"
            else -> "${seconds}秒"
        }
    }
}

data class SttResult(
    val text: String,
    val confidence: Float = 0f,
    val segments: List<SttSegment> = emptyList(),
    val durationMs: Long = 0,
    val processingTimeMs: Long = 0,
    val engineType: EngineType = EngineType.SHERPA_ONNX,
    val modelUsed: String = "",
    val error: String? = null,
)

data class SttSegment(
    val text: String,
    val startTimeMs: Long = 0,
    val endTimeMs: Long = 0,
    val confidence: Float = 0f,
)

enum class EngineType {
    SHERPA_ONNX,
    ANDROID_SPEECH_RECOGNIZER,
    MIMO_ASR,
    STEP_AUDIO_ASR,   // 阶跃星辰 StepAudio 2.5 云端 ASR
}

// ==================== 会议转录结果（UI 层使用）====================

/**
 * 会议转录的完整结果，供 MeetingRecordScreen UI 渲染。
 */
data class MeetingTranscriptResult(
    val segments: List<MeetingSegment> = emptyList(),
    val fullText: String = "",
    val durationMs: Long = 0,
    val hasDiarization: Boolean = false,
    val speakers: Set<String> = emptySet(),
    val error: String? = null,
    /** 音频文件路径（用于播放器回放） */
    val audioFilePath: String? = null,
    /** 结构化智能总结（可为空） */
    val smartSummary: SmartSummary? = null,
)

/**
 * 单个转录段落，带说话人标签和颜色编码（参考得到大脑 sentence-item 数据模型）。
 *
 * 对应 DOM 结构:
 *   <div class="sentence-item sentence-item--clickable">
 *     <div class="flex items-center gap-[10px]">
 *       <span class="sentence-index" style="background-color: rgb(R,G,B);">N</span>
 *       <span class="sentence-speaker">说话人X</span>
 *       <span class="sentence-starttime">HH:MM:SS</span>
 *     </div>
 *     <div class="sentence-content">transcript text...</div>
 *   </div>
 */
data class MeetingSegment(
    val text: String,
    val startTimeMs: Long = 0,
    val endTimeMs: Long = 0,
    val speakerLabel: String = "Speaker_0",
    /** 说话人索引号（用于圆形 badge 显示，同色说话人编号一致） */
    val speakerIndex: Int = 0,
    /** 说话人颜色 (ARGB int)，由 SpeakerColorPalette 分配 */
    val speakerColor: Int = 0xFF8CCFEB.toInt(),
)

/**
 * 结构化智能总结数据模型（参考得到大脑 智能总结 Tab 内容结构）。
 *
 * 包含:
 *   - 录音信息 (元数据)
 *   - 录音总结 (多级标题+列表)
 *   - 章节概要 (时间戳链接+段落摘要)
 *   - 金句精选 (引用+分类标签)
 *   - 待办事项 (任务分配)
 */
data class SmartSummary(
    /** 录音信息 */
    val metaInfo: MetaInfo,
    /** 录音总结段落列表 */
    val summarySections: List<SummarySection>,
    /** 章节概要（带时间戳的章节列表） */
    val chapters: List<ChapterItem>,
    /** 金句精选 */
    val quotes: List<QuoteItem>,
    /** 待办事项 */
    val actionItems: List<ActionItem>,
) {
    data class MetaInfo(
        val duration: String = "",          // 如 "约 0小时 19分钟"
        val participantCount: Int = 0,      // 参与人数
        val contentType: String = "",       // 如 "面试对话"
    )

    data class SummarySection(
        val title: String,                  // 如 "候选人自我介绍与背景说明"
        val content: List<String>,         // 段落内容列表
    )

    data class ChapterItem(
        val timestampSec: Long,            // 章节起始时间（秒）
        val timestampFormatted: String,    // 如 "00:00:29"
        val title: String,                 // 如 "候选人自我介绍与背景说明"
        val summary: String,               // 段落摘要
    )

    data class QuoteItem(
        val text: String,                  // 引用原文
        val category: String,              // 分类标签: 战略洞见/思考启发/方法技巧
    )

    data class ActionItem(
        val assignee: String,              // 负责人
        val task: String,                  // 任务描述
    )
}
