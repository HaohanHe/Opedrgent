package top.hsyscn.opedrgent.utils

import top.hsyscn.opedrgent.model.ChatMessage
import top.hsyscn.opedrgent.model.MessagePart
import top.hsyscn.opedrgent.model.Role
import kotlin.math.min

/**
 * 压缩策略枚举（对标 Koog HistoryCompressionStrategy）
 */
enum class CompressionStrategy {
    /** 压缩全部历史（默认） */
    WHOLE_HISTORY,
    /** 仅压缩超过 N 轮的旧历史 */
    OLD_TURNS_ONLY,
    /** 仅压缩工具输出 */
    TOOL_OUTPUTS_ONLY,
    /** 仅保留最近 N 轮，其余丢弃 */
    RECENT_ONLY,
}

/**
 * 压缩执行器接口。
 *
 * 每种具体策略实现该接口，以单一职责的方式封装特定的压缩算法。
 */
interface CompressionExecutor {
    suspend fun compress(
        messages: List<ChatMessage>,
        systemPrompt: String,
        maxTokens: Int,
        keepTurns: Int,
        generateFn: (suspend (String, List<ChatMessage>) -> String)?,
    ): CompressResult
}

/**
 * 全历史 TLDR 压缩（默认策略）。
 *
 * 对旧 turn 生成 TLDR 摘要，对保留轮进行工具输出剪枝。
 */
class WholeHistoryCompression : CompressionExecutor {
    override suspend fun compress(
        messages: List<ChatMessage>,
        systemPrompt: String,
        maxTokens: Int,
        keepTurns: Int,
        generateFn: (suspend (String, List<ChatMessage>) -> String)?,
    ): CompressResult {
        val sysTokens = TokenEstimator.estimateTokens(systemPrompt)
        val turns = MessageHistoryManager.splitIntoTurns(messages)
        val keepCount = keepTurns.coerceAtMost(turns.size)
        val keepTurnsList = turns.takeLast(keepCount)
        val compactTurns = turns.dropLast(keepCount)

        val tldrGenerator = TldrGenerator()
        val tldr = if (compactTurns.isNotEmpty() && generateFn != null) {
            try {
                tldrGenerator.generateTldr(compactTurns, generateFn)
            } catch (e: Exception) {
                DebugLog.w("WholeHistoryCompression: LLM TLDR failed, fallback: ${e.message}")
                null
            }
        } else null

        val tldrText = tldr?.toDisplayText()

        val prunedKeep = keepTurnsList.flatMap { turn ->
            turn.map { msg -> MessagePruner.pruneToolOutput(msg) }
        }

        val compactedMessage = if (tldrText != null) {
            ChatMessage(
                role = Role.SYSTEM,
                parts = listOf(MessagePart.Compaction(tldrText, auto = true)),
                content = tldrText,
            )
        } else null

        val resultMessages = listOfNotNull(compactedMessage) + prunedKeep
        val resultTokens = sysTokens + resultMessages.sumOf { TokenEstimator.estimateMessageTokens(it) }

        DebugLog.d(
            "WholeHistoryCompression: $resultTokens/$maxTokens (${String.format("%.0f%%", resultTokens.toFloat() / maxTokens.coerceAtLeast(1) * 100)}) " +
            "(turns=${turns.size}, kept=$keepCount, tldr=${tldr?.keyFacts?.size ?: 0} facts)"
        )

        return CompressResult(
            messages = resultMessages,
            summary = tldrText,
            tokenCount = resultTokens,
            usageRatio = resultTokens.toFloat() / maxTokens.coerceAtLeast(1),
        )
    }
}

/**
 * 仅压缩旧轮策略。
 *
 * 对旧 turn 生成 TLDR 摘要，保留最近轮完整不剪枝。
 */
class OldTurnsOnlyCompression : CompressionExecutor {
    override suspend fun compress(
        messages: List<ChatMessage>,
        systemPrompt: String,
        maxTokens: Int,
        keepTurns: Int,
        generateFn: (suspend (String, List<ChatMessage>) -> String)?,
    ): CompressResult {
        val sysTokens = TokenEstimator.estimateTokens(systemPrompt)
        val turns = MessageHistoryManager.splitIntoTurns(messages)
        val keepCount = keepTurns.coerceAtMost(turns.size)
        val compactTurns = turns.dropLast(keepCount)
        val keepTurnsList = turns.takeLast(keepCount)

        val tldrGenerator = TldrGenerator()
        val tldr = if (compactTurns.isNotEmpty() && generateFn != null) {
            tldrGenerator.generateTldr(compactTurns, generateFn)
        } else null

        val compactedMessage = tldr?.toDisplayText()?.let { text ->
            ChatMessage(role = Role.SYSTEM, parts = listOf(MessagePart.Compaction(text, auto = true)), content = text)
        }

        val resultMessages = listOfNotNull(compactedMessage) + keepTurnsList.flatten()
        val resultTokens = sysTokens + resultMessages.sumOf { TokenEstimator.estimateMessageTokens(it) }

        return CompressResult(
            messages = resultMessages,
            summary = tldr?.toDisplayText(),
            tokenCount = resultTokens,
            usageRatio = resultTokens.toFloat() / maxTokens.coerceAtLeast(1),
        )
    }
}

/**
 * 仅压缩工具输出策略。
 *
 * 保留对话文本完整，仅对工具输出进行剪枝。
 */
class ToolOutputsOnlyCompression : CompressionExecutor {
    override suspend fun compress(
        messages: List<ChatMessage>,
        systemPrompt: String,
        maxTokens: Int,
        keepTurns: Int,
        generateFn: (suspend (String, List<ChatMessage>) -> String)?,
    ): CompressResult {
        val sysTokens = TokenEstimator.estimateTokens(systemPrompt)
        val pruned = messages.map { MessagePruner.pruneToolOutput(it) }
        val tokens = sysTokens + pruned.sumOf { TokenEstimator.estimateMessageTokens(it) }
        return CompressResult(
            messages = pruned,
            summary = null,
            tokenCount = tokens,
            usageRatio = tokens.toFloat() / maxTokens.coerceAtLeast(1),
        )
    }
}

/**
 * 仅保留最近 N 轮策略。
 *
 * 其余旧对话完全丢弃。
 */
class RecentOnlyCompression : CompressionExecutor {
    override suspend fun compress(
        messages: List<ChatMessage>,
        systemPrompt: String,
        maxTokens: Int,
        keepTurns: Int,
        generateFn: (suspend (String, List<ChatMessage>) -> String)?,
    ): CompressResult {
        val sysTokens = TokenEstimator.estimateTokens(systemPrompt)
        val turns = MessageHistoryManager.splitIntoTurns(messages)
        val kept = turns.takeLast(keepTurns.coerceAtMost(turns.size)).flatten()
        val tokens = sysTokens + kept.sumOf { TokenEstimator.estimateMessageTokens(it) }
        val droppedCount = turns.size - min(keepTurns, turns.size)
        val summary = if (droppedCount > 0) "[已丢弃 $droppedCount 轮旧对话]" else null
        return CompressResult(
            messages = kept,
            summary = summary,
            tokenCount = tokens,
            usageRatio = tokens.toFloat() / maxTokens.coerceAtLeast(1),
        )
    }
}

/**
 * 压缩执行器工厂。
 *
 * 根据 [CompressionStrategy] 枚举创建对应的策略执行器实例。
 */
object CompressionExecutorFactory {
    fun create(strategy: CompressionStrategy): CompressionExecutor {
        return when (strategy) {
            CompressionStrategy.WHOLE_HISTORY -> WholeHistoryCompression()
            CompressionStrategy.OLD_TURNS_ONLY -> OldTurnsOnlyCompression()
            CompressionStrategy.TOOL_OUTPUTS_ONLY -> ToolOutputsOnlyCompression()
            CompressionStrategy.RECENT_ONLY -> RecentOnlyCompression()
        }
    }
}
