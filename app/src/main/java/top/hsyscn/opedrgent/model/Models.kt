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

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val content: String,
    val createdAt: Long,
    val toolParts: List<ToolPart> = emptyList(),
    val reasoningParts: List<ReasoningPart> = emptyList(),
    val questionPart: QuestionPart? = null,
    val toolCallId: String? = null,
    val apiToolCallsJson: String? = null,
)

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
}

data class MemoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val content: String,
    val type: MemoryType = MemoryType.USER,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

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
