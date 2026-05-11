package top.hsyscn.opedrgent.agent

import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.utils.DebugLog
import top.hsyscn.opedrgent.utils.ToolCallParser

data class ToolError(
    val turn: Int,
    val toolName: String,
    val arguments: String,
    val error: String,
    val toolResult: String = "",
)

data class TurnInfo(
    val turnNumber: Int,
    val userMessage: String,
    val reasoning: String? = null,
    val toolCalls: List<ToolPart> = emptyList(),
    val toolErrors: MutableList<ToolError> = mutableListOf(),
    val finalResponse: String? = null,
    val success: Boolean = true,
)

class AgentLoopState(
    val maxTurns: Int = 30,
    val taskId: String = java.util.UUID.randomUUID().toString(),
) {

    val turns = mutableListOf<TurnInfo>()
    val errors = mutableListOf<ToolError>()
    val reasoningPerTurn = mutableListOf<String?>()
    var finishedNaturally = false
    var turnsUsed = 0
    var isCancelled = false

    fun recordTurn(turn: TurnInfo) {
        turns.add(turn)
        turnsUsed = turns.size
        reasoningPerTurn.add(turn.reasoning)
        if (turn.toolErrors.isNotEmpty()) {
            errors.addAll(turn.toolErrors)
        }
    }

    fun recordToolError(turn: Int, toolName: String, arguments: String, error: String, toolResult: String = "") {
        val te = ToolError(turn, toolName, arguments, error, toolResult)
        errors.add(te)
        if (turns.isNotEmpty()) {
            turns.last().toolErrors.add(te)
        }
    }

    fun getSummary(): String {
        if (turns.isEmpty()) return "No turns executed"
        return buildString {
            appendLine("Agent Loop Summary:")
            appendLine("  Turns: $turnsUsed/$maxTurns (${if (finishedNaturally) "finished naturally" else "hit max"})")
            appendLine("  Errors: ${errors.size}")
            if (errors.isNotEmpty()) {
                appendLine("  Error details:")
                errors.forEach { e ->
                    appendLine("    Turn ${e.turn}: ${e.toolName} — ${e.error}")
                }
            }
        }
    }

    fun extractReasoningFromContent(content: String): String? {
        val parts = ToolCallParser.extractThinkingParts(content)
        return parts.firstOrNull()?.text?.takeIf { it.isNotBlank() }
    }

    companion object {
        fun extractReasoningFromMultipleFormats(content: String, reasoningContent: String? = null): String? {
            if (!reasoningContent.isNullOrBlank()) return reasoningContent.trim()
            val parts = ToolCallParser.extractThinkingParts(content)
            return parts.firstOrNull()?.text?.takeIf { it.isNotBlank() }
        }
    }
}