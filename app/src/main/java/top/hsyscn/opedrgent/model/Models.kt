package top.hsyscn.opedrgent.model

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
) {
    /** 从 parts 自动提取文本内容（向后兼容） */
    val textContent: String
        get() = if (parts.isNotEmpty()) {
            parts.filterIsInstance<MessagePart.Text>()
                .joinToString("") { it.content }
        } else content
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
}

enum class ToolStateType { PENDING, RUNNING, COMPLETED, ERROR, SOURCE_ADDED }

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
