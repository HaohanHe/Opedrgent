package top.hsyscn.opedrgent.stt

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.intelligence.MemoryBridge
import top.hsyscn.opedrgent.note.Note
import top.hsyscn.opedrgent.note.NoteRepository
import top.hsyscn.opedrgent.utils.DebugLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会议转录结果持久化桥接器 — 解决"录音→转写→存笔记"链路断裂问题。
 *
 * 职责：
 * 1. 将 MeetingTranscriptResult 自动保存为 Note（笔记系统）
 * 2. 将全文写入 MemoryBridge（记忆系统，支持语义检索）
 * 3. 生成结构化摘要元数据
 *
 * 使用方式：
 * ```kotlin
 * val bridge = MeetingTranscriptBridge(context, noteRepository, memoryBridge)
 * val noteId = bridge.saveTranscriptResult(transcriptResult, audioFileName)
 * ```
 *
 * @param context Android Context
 * @param noteRepository 笔记仓库（用于持久化笔记）
 * @param memoryBridge 记忆桥接器（用于语义检索）
 */
class MeetingTranscriptBridge(
    private val context: Context,
    private val noteRepository: NoteRepository,
    private val memoryBridge: MemoryBridge? = null, // 可选：记忆系统可能未初始化
) {

    companion object {
        private const val TAG = "MeetingTranscriptBridge"
        
        /** 笔记分类标签 */
        const val NOTE_CATEGORY_MEETING = "meeting_transcript"
        
        /** 记忆系统路径前缀 */
        const val MEMORY_PATH_PREFIX = "/meetings/"
    }

    /**
     * 保存会议转录结果到笔记系统和记忆系统。
     *
     * @param transcript 转录结果（来自 MeetingTranscriber 或 AsrManager）
     * @param audioFileName 原始音频文件名（用于生成笔记标题）
     * @return 保存成功返回笔记 ID，失败返回 null
     */
    suspend fun saveTranscriptResult(
        transcript: MeetingTranscriptResult,
        audioFileName: String = "录音_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}",
    ): Long? = withContext(Dispatchers.IO) {
        try {
            if (transcript.error != null || transcript.fullText.isBlank()) {
                DebugLog.w(TAG, "转录结果无效，跳过保存: error=${transcript.error}, textLength=${transcript.fullText.length}")
                return@withContext null
            }

            // 1. 构建笔记内容（Markdown 格式）
            val noteContent = buildNoteContent(transcript, audioFileName)

            // 2. 创建 Note 对象并保存
            val note = Note(
                title = generateNoteTitle(audioFileName, transcript),
                content = noteContent,
                category = NOTE_CATEGORY_MEETING,
                tags = mutableSetOf("会议转写", "语音识别").apply {
                    if (transcript.hasDiarization) add("说话人分离")
                    transcript.speakers.forEach { add("说话人:$it") }
                },
                metadata = mutableMapOf(
                    "sourceType" to "stt",
                    "audioFile" to audioFileName,
                    "durationMs" to transcript.durationMs.toString(),
                    "engineType" to "asr",
                    "segmentCount" to transcript.segments.size.toString(),
                    "hasDiarization" to transcript.hasDiarization.toString(),
                    "speakerCount" to transcript.speakers.size.toString(),
                    "transcribedAt" to System.currentTimeMillis().toString(),
                ),
            )

            // 3. 保存到笔记系统
            val noteId = noteRepository.saveNote(note)
            DebugLog.i(TAG, "笔记已保存: id=$noteId, title=${note.title}, textLength=${transcript.fullText.length}")

            // 4. 写入记忆系统（可选，失败不阻断）
            memoryBridge?.let { bridge ->
                try {
                    writeToMemory(bridge, transcript, audioFileName, noteId)
                } catch (e: Exception) {
                    DebugLog.w(TAG, "写入记忆系统失败（非致命）: ${e.message}")
                }
            }

            noteId
        } catch (e: Exception) {
            DebugLog.e(TAG, "保存转录结果失败: ${e.message}", e)
            null
        }
    }

    /**
     * 批量保存多个转录结果。
     *
     * @param transcripts 转录结果列表
     * @return 成功保存的 (noteId, audioFileName) 对列表
     */
    suspend fun saveBatch(
        transcripts: List<Pair<MeetingTranscriptResult, String>>,
    ): List<Pair<Long, String>> = withContext(Dispatchers.IO) {
        transcripts.mapNotNull { (transcript, fileName) ->
            val noteId = saveTranscriptResult(transcript, fileName)
            noteId?.let { Pair(it, fileName) }
        }.also {
            DebugLog.i(TAG, "批量保存完成: ${it.size}/${transcripts.size} 个成功")
        }
    }

    // ==================== 内部实现 ====================

    /**
     * 构建笔记 Markdown 内容。
     *
     * 格式：
     * # 会议标题
     *
     * ## 元信息
     * - 时长：xx 分钟
     * - 说话人：x 人
     * - 转录时间：...
     *
     * ## 全文内容
     *
     * ### Speaker_0 [00:00 - 01:23]
     * 文本内容...
     *
     * ### Speaker_0 [01:23 - 02:45]
     * 文本内容...
     */
    private fun buildNoteContent(
        transcript: MeetingTranscriptResult,
        audioFileName: String,
    ): String {
        val lines = mutableListOf<String>()

        // 标题已在 Note.title 中设置，这里从正文开始

        lines.add("## 元信息")
        lines.add("")
        lines.add("- **音频文件**: $audioFileName")
        lines.add("- **时长**: ${formatDuration(transcript.durationMs)}")
        lines.add("- **说话人数量**: ${transcript.speakers.size}")
        lines.add("- **段落数量**: ${transcript.segments.size}")
        lines.add("- **是否包含说话人分离**: ${if (transcript.hasDiarization) "是" else "否"}")
        lines.add("- **转录时间**: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
        lines.add("")

        lines.add("## 全文内容")
        lines.add("")

        if (transcript.segments.isEmpty()) {
            // 无分段信息，输出纯文本
            lines.add(transcript.fullText)
        } else {
            // 按段落输出（带时间戳和说话人标签）
            for ((index, segment) in transcript.segments.withIndex()) {
                val startTime = formatTimestamp(segment.startTimeMs)
                val endTime = formatTimestamp(segment.endTimeMs)
                lines.add("### ${segment.speakerLabel} [$startTime - $endTime]")
                lines.add("")
                lines.add(segment.text)
                lines.add("")
                
                // 段落间添加分隔线（除最后一段外）
                if (index < segments.lastIndex) {
                    lines.add("---")
                    lines.add("")
                }
            }
        }

        return lines.joinToString("\n")
    }

    /**
     * 生成笔记标题。
     *
     * 格式："会议转写 - 音频文件名" 或 "会议转写 - yyyyMMdd_HHmmss"
     */
    private fun generateNoteTitle(
        audioFileName: String,
        transcript: MeetingTranscriptResult,
    ): String {
        val baseName = audioFileName.removeSuffix(".wav")
            .removeSuffix(".mp3")
            .removeSuffix(".m4a")
            .removeSuffix(".pcm")

        return "会议转写 - $baseName"
    }

    /**
     * 写入记忆系统（MemoryBridge）。
     *
     * 路径格式：/meetings/yyyy/MM/dd/音频文件名
     * 内容：全文 + 结构化元数据
     */
    private suspend fun writeToMemory(
        bridge: MemoryBridge,
        transcript: MeetingTranscriptResult,
        audioFileName: String,
        noteId: Long,
    ) {
        val datePath = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date())
        val safeFileName = audioFileName.replace(Regex("[^a-zA-Z0-9_\\-\\u4e00-\\u9fa5]"), "_")
        val memoryPath = "$MEMORY_PATH_PREFIX$datePath/$safeFileName"

        // 构建记忆内容（精简版，用于语义检索）
        val memoryContent = buildString {
            appendLine("# ${generateNoteTitle(audioFileName, transcript)}")
            appendLine()
            appendLine("**摘要**: ${transcript.fullText.take(200)}...")
            appendLine()
            appendLine("**完整文本**:")
            appendLine(transcript.fullText)
            appendLine()
            appendLine("**元数据**:")
            appendLine("- 时长: ${formatDuration(transcript.durationMs)}")
            appendLine("- 说话人: ${transcript.speakers.joinToString(", ")}")
            appendLine("- 笔记ID: $noteId")
        }

        bridge.write(
            path = memoryPath,
            content = memoryContent,
            importance = 0.7f, // 会议记录重要性较高
            tags = setOf("meeting", "transcript", "stt") + transcript.speakers.map { "speaker:$it" },
            collection = "meetings",
            metadata = mapOf(
                "noteId" to noteId.toString(),
                "audioFile" to audioFileName,
                "durationMs" to transcript.durationMs.toString(),
                "hasDiarization" to transcript.hasDiarization.toString(),
            ),
        )

        DebugLog.i(TAG, "已写入记忆系统: path=$memoryPath, contentLength=${memoryContent.length}")
    }

    /**
     * 格式化时长（毫秒 → 可读字符串）。
     */
    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) "${minutes}分${seconds}秒" else "${seconds}秒"
    }

    /**
     * 格式化时间戳（毫秒 → HH:mm:ss）。
     */
    private fun formatTimestamp(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }
}
