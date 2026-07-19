package top.hsyscn.opedrgent.model

import top.hsyscn.opedrgent.network.ToolExecutionStatus
import java.util.UUID

enum class SourceType {
    URL,
    TEXT,
}

enum class Role {
    SYSTEM,
    USER,
    ASSISTANT,
}

enum class ArtifactKind {
    SUMMARY,
    REPORT,
    NOTES,
}

data class Source(
    val id: String = UUID.randomUUID().toString(),
    val type: SourceType,
    val title: String?,
    val url: String?,
    val content: String,
    val includeInContext: Boolean,
    val createdAt: Long,
)

enum class MessageType {
    TEXT,
    INFO,
    CONFIG_UPDATE,
    ERROR,
    AUDIO,
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val content: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val messageType: MessageType = MessageType.TEXT,
    val toolParts: List<ToolPart> = emptyList(),
    val reasoningParts: List<ReasoningPart> = emptyList(),
    val questionPart: QuestionPart? = null,
    val toolCallId: String? = null,
    val apiToolCallsJson: String? = null,
    val parts: List<MessagePart> = emptyList(),
    val isUserAction: Boolean = false,
    /** 消息所属轮次。user 消息（非工具结果）开始新一轮，后续 assistant/system 消息继承该轮次。 */
    val roundIndex: Int = 0,
) {
    /** 从 parts 自动提取文本内容（向后兼容） */
    val textContent: String
        get() = if (parts.isNotEmpty()) {
            parts.filterIsInstance<MessagePart.Text>()
                .joinToString("") { it.content }
        } else content

    /** 从 parts 提取所有音频片段 */
    val audioClips: List<MessagePart.AudioClip>
        get() = parts.filterIsInstance<MessagePart.AudioClip>()

    /** 是否包含音频消息 */
    val hasAudio: Boolean
        get() = audioClips.isNotEmpty()
}

data class Artifact(
    val id: String = UUID.randomUUID().toString(),
    val kind: ArtifactKind,
    val content: String,
    val createdAt: Long,
)

data class ResearchSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val sources: List<Source>,
    val messages: List<ChatMessage>,
    val artifacts: List<Artifact>,
    val notes: String,
)

data class SessionSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
)

data class Skill(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val prompt: String,
    val createdAt: Long,
    val updatedAt: Long,
)

enum class MemoryType(val label: String) {
    USER("用户"),
    FEEDBACK("反馈"),
    PROJECT("项目"),
    REFERENCE("参考"),
    NOTE_SUMMARY("笔记"),
}

/**
 * 录音质量预设。
 *
 * - [LOW] 8kHz / 16bit / 单声道：文件最小，语音可分辨，但语音识别准确率可能下降。
 * - [NORMAL] 16kHz / 16bit / 单声道：语音识别最佳，文件适中。
 * - [HIGH] 44.1kHz / 16bit / 单声道：音质最好，文件最大。
 */
enum class RecordingQuality(val key: String, val sampleRate: Int, val displayName: String, val description: String) {
    LOW("low", 8000, "低质量（省空间）", "8kHz，语音可分辨，文件最小"),
    NORMAL("normal", 16000, "标准", "16kHz，语音识别最佳，推荐"),
    HIGH("high", 44100, "高质量", "44.1kHz，音质最好，文件较大");

    companion object {
        fun fromKey(key: String?): RecordingQuality = entries.find { it.key == key } ?: NORMAL
    }
}

data class MemoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val content: String,
    val type: MemoryType = MemoryType.USER,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * 消息的组成部分。每条消息由一个或多个 Part 组成。
 * 参考 KiloCode 的消息 Part 模型。
 */
sealed class MessagePart {
    /** 文本内容 */
    data class Text(
        val content: String,
        val ignored: Boolean = false,
    ) : MessagePart()

    /** 工具调用 */
    data class ToolCall(
        val toolName: String,
        val callId: String,
        val state: ToolState = ToolState(status = ToolStateType.PENDING),
        val input: Map<String, String> = emptyMap(),
        val output: String? = null,
    ) : MessagePart()

    /** 推理过程（思考链） */
    data class Reasoning(
        val content: String,
    ) : MessagePart()

    /** 步骤开始标记 */
    data class StepStart(
        val round: Int,
    ) : MessagePart()

    /** 步骤结束标记 */
    data class StepFinish(
        val tokensUsed: Int = 0,
        val cost: Double = 0.0,
    ) : MessagePart()

    /** 压缩标记 */
    data class Compaction(
        val summary: String,
        val auto: Boolean = true,
        val tailStartId: String? = null,  // 标记 tail 起点（对标 KiloCode tail_start_id）
    ) : MessagePart()

    /** 错误信息 */
    data class Error(
        val message: String,
        val recoverable: Boolean = true,
    ) : MessagePart()

    /** 附加工具状态（流式输出中的临时状态） */
    data class StreamingState(
        val text: String = "",
        val reasoning: String = "",
        val phase: String = "",
    ) : MessagePart()

    /** 音频片段（对标 Gallery ChatHistory AudioMessageProto） */
    data class AudioClip(
        val filePath: String,
        val sampleRate: Int = 16000,
        val durationMs: Long = 0L,
        val transcript: String = "",
        val format: AudioFormat = AudioFormat.M4A,
    ) : MessagePart()
}

/** 音频文件格式 */
enum class AudioFormat(val mimeType: String, val extension: String) {
    M4A("audio/mp4", ".m4a"),
    MP3("audio/mpeg", ".mp3"),
    WAV("audio/wav", ".wav"),
    OGG("audio/ogg", ".ogg"),
    AAC("audio/aac", ".aac"),
}

enum class ToolStateType { PENDING, RUNNING, COMPLETED, ERROR, SOURCE_ADDED, PARTIAL_TIMEOUT }

data class ToolState(
    val status: ToolStateType,
    val input: Map<String, String> = emptyMap(),
    val output: String? = null,
    val error: String? = null,
    val startTime: Long = 0L,
    val endTime: Long = 0L,
)

data class ToolPart(
    val id: String = UUID.randomUUID().toString(),
    val tool: String,
    val state: ToolState,
    val questionPart: QuestionPart? = null,
)

data class QuestionPart(
    val id: String = UUID.randomUUID().toString(),
    val prompt: String,
    val multiSelect: Boolean = false,
    val options: List<QuestionOption> = emptyList(),
    val answer: String? = null,
)

data class QuestionOption(
    val value: String,
    val label: String,
)

data class ReasoningPart(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val startTime: Long = System.currentTimeMillis(),
    val endTime: Long = 0L,
)

/**
 * Agent 循环工具调用记录（用于 guardrail 快照与恢复）
 */
data class ToolCallRecord(
    val toolName: String,
    val normalizedArgs: String,
    val argsHash: String,
    val resultHash: String,
    val status: ToolExecutionStatus,
    val timestampMs: Long = System.currentTimeMillis(),
)

/**
 * Agent 循环 Guardrail 快照，支持中断后恢复状态。
 */
data class GuardrailSnapshot(
    val consecutiveFailures: Int,
    val toolFailureCounts: Map<String, Int>,
    val recentToolCalls: List<ToolCallRecord>,
)

/**
 * Agent 循环检查点，用于在 MAX_ROUNDS、guardrail、取消或进程死亡后恢复研究。
 */
data class ResearchCheckpoint(
    val sessionId: String,
    val round: Int,
    val accumulatedText: String,
    val accumulatedReasoning: String,
    val toolMessages: List<ChatMessage>,
    val sources: List<Source>,
    val guardrailSnapshot: GuardrailSnapshot,
    val haltReason: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

enum class MediaType { IMAGE, AUDIO, VIDEO }

sealed class MultimodalContent {
    data class Text(val text: String) : MultimodalContent()
    data class ImageUrl(val url: String) : MultimodalContent()
    data class ImageBase64(val base64: String, val mimeType: String = "image/png") : MultimodalContent()
    data class AudioUrl(val url: String) : MultimodalContent()
    data class AudioBase64(val base64: String, val mimeType: String = "audio/wav") : MultimodalContent()
    data class VideoUrl(val url: String, val fps: Int = 2, val mediaResolution: String = "default") : MultimodalContent()
    data class VideoBase64(val base64: String, val mimeType: String = "video/mp4", val fps: Int = 2, val mediaResolution: String = "default") : MultimodalContent()
}

data class MultimodalMessage(
    val role: Role,
    val content: List<MultimodalContent>,
    val createdAt: Long = System.currentTimeMillis(),
)
