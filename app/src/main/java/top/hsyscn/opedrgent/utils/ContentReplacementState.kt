package top.hsyscn.opedrgent.utils

import top.hsyscn.opedrgent.model.ChatMessage
import top.hsyscn.opedrgent.model.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 工具结果内容替换状态 — 三态压缩的核心。
 *
 * 对标 Claude Code toolResultStorage.ts ContentReplacementState。
 *
 * 三态语义：
 * - mustReapply: tool_use_id 在 replacements Map 中 → 纯 Map 查找重应用（byte-identical，cache 命中基石）
 * - frozen: tool_use_id 在 seenIds 但不在 replacements → 永不替换（尊重历史决策）
 * - fresh: tool_use_id 不在 seenIds 也不在 replacements → 可做新替换决策
 *
 * 不变性约束（cache-stable 基石）：
 * - seenIds 中的 id 一旦加入，永不移除
 * - replacements 中的 id→preview 映射一旦建立，永不改变
 * - 同一 tool_use_id 跨轮的"是否替换"决策必须永远一致
 */
data class ContentReplacementState(
    val seenIds: MutableSet<String> = mutableSetOf(),
    val replacements: MutableMap<String, String> = mutableMapOf(),
) {
    companion object {
        fun create(): ContentReplacementState = ContentReplacementState()
    }
}

object ContentReplacement {
    private const val TAG = "ContentReplacement"
    private const val PERSISTED_OUTPUT_TAG = "<persisted-output>"
    private const val PERSISTED_OUTPUT_CLOSING_TAG = "</persisted-output>"
    private const val PREVIEW_SIZE_BYTES = 2000
    private const val PER_MESSAGE_BUDGET_LIMIT = 50_000  // 单轮工具结果总预算（字符数）
    private const val MIN_CANDIDATE_SIZE = 500  // 小于此值不替换（不值得持久化）

    /** 候选工具结果 */
    data class ToolResultCandidate(
        val toolUseId: String,
        val content: String,
        val size: Int,
        val messageIndex: Int,
    )

    /** 三态分区结果 */
    data class CandidatePartition(
        val mustReapply: List<ToolResultCandidateWithReplacement>,
        val frozen: List<ToolResultCandidate>,
        val fresh: List<ToolResultCandidate>,
    )

    data class ToolResultCandidateWithReplacement(
        val candidate: ToolResultCandidate,
        val replacement: String,
    )

    /** enforcement 返回结果 */
    data class BudgetEnforceResult(
        val messages: List<ChatMessage>,
        val newlyReplaced: Int = 0,
        val reapplied: Int = 0,
    )

    /**
     * 克隆状态（子 agent fork 用）。
     * String 不可变，浅拷贝等价深拷贝。
     */
    fun cloneContentReplacementState(source: ContentReplacementState): ContentReplacementState {
        return ContentReplacementState(
            seenIds = source.seenIds.toMutableSet(),
            replacements = source.replacements.toMutableMap(),
        )
    }

    /**
     * 从消息历史重建状态（resume 用）。
     * 所有候选 ID 标记为 seen（冻结），避免 resume 后做发散决策。
     *
     * P2-2 修复：增加 [cacheDir] 参数，扫描磁盘上持久化的 tool-results 目录下的 .txt 文件重建
     * [ContentReplacementState.replacements] Map。跨进程重启后，磁盘文件仍存在但 replacements
     * 为空会导致 mustReapply 三态退化为 frozen（仅 seenIds）。重建后 mustReapply 可正确重应用预览，
     * 保证 byte-identical 替换，维系 prompt cache 命中基石。
     *
     * 文件 I/O 通过 withContext(Dispatchers.IO) 切到 IO 线程，避免阻塞调用方。
     *
     * @param cacheDir 持久化工具结果的目录（Android Context.cacheDir）；为 null 时跳过磁盘扫描
     */
    suspend fun reconstructContentReplacementState(
        messages: List<ChatMessage>,
        cacheDir: File? = null,
    ): ContentReplacementState = withContext(Dispatchers.IO) {
        val state = ContentReplacementState.create()
        val candidates = collectCandidatesByTurn(messages).flatten()
        for (c in candidates) {
            state.seenIds.add(c.toolUseId)
        }
        // 扫描磁盘上持久化的工具结果文件，重建 replacements Map
        if (cacheDir != null) {
            val dir = File(cacheDir, "tool-results")
            val files = runCatching {
                dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            }.getOrNull() ?: emptyArray()
            for (file in files) {
                val toolUseId = file.name.removeSuffix(".txt")
                val content = runCatching { file.readText() }.getOrNull() ?: continue
                val preview = buildPreviewFromContent(content, file.absolutePath)
                state.replacements[toolUseId] = preview
                state.seenIds.add(toolUseId)
            }
            if (files.isNotEmpty()) {
                DebugLog.i("$TAG: reconstruct rebuilt ${files.size} replacements from disk")
            }
        }
        state
    }

    /**
     * 对消息列表应用工具结果预算 enforcement。
     *
     * @param state 跨轮持有的状态（会被原地更新）
     * @param cacheDir 持久化工具结果的目录（Android Context.cacheDir）
     * @return BudgetEnforceResult 含应用替换后的消息列表
     */
    suspend fun enforceToolResultBudget(
        messages: List<ChatMessage>,
        state: ContentReplacementState,
        cacheDir: File,
    ): BudgetEnforceResult {
        val candidatesByTurn = collectCandidatesByTurn(messages)
        val replacementMap = mutableMapOf<String, String>()
        val toPersist = mutableListOf<ToolResultCandidate>()
        var reappliedCount = 0

        for (candidates in candidatesByTurn) {
            val partition = partitionByPriorDecision(candidates, state)

            // mustReapply：纯 Map 查找
            partition.mustReapply.forEach { c ->
                replacementMap[c.candidate.toolUseId] = c.replacement
            }
            reappliedCount += partition.mustReapply.size

            // fresh 为空 = 之前处理过的轮次，仅 re-apply
            if (partition.fresh.isEmpty()) {
                candidates.forEach { state.seenIds.add(it.toolUseId) }
                continue
            }

            val frozenSize = partition.frozen.sumOf { it.size }
            val freshSize = partition.fresh.sumOf { it.size }

            // 超预算时选最大的 fresh 候选替换
            val selected = if (frozenSize + freshSize > PER_MESSAGE_BUDGET_LIMIT) {
                selectFreshToReplace(partition.fresh, frozenSize, PER_MESSAGE_BUDGET_LIMIT)
            } else emptyList()

            // cache-stable 原子性：未选中的同步标记 seen
            val selectedIds = selected.map { it.toolUseId }.toSet()
            candidates
                .filter { it.toolUseId !in selectedIds }
                .forEach { state.seenIds.add(it.toolUseId) }

            if (selected.isNotEmpty()) {
                toPersist.addAll(selected)
            }
        }

        if (replacementMap.isEmpty() && toPersist.isEmpty()) {
            return BudgetEnforceResult(messages)
        }

        // fresh：持久化所有选中的候选（顺序执行，避免并发文件 I/O 复杂度）
        var newlyReplacedCount = 0
        for (candidate in toPersist) {
            val replacement = persistAndBuildPreview(candidate, cacheDir)
            // cache-stable 原子性：await 后同步 set seen + replacement
            state.seenIds.add(candidate.toolUseId)
            if (replacement != null) {
                replacementMap[candidate.toolUseId] = replacement
                state.replacements[candidate.toolUseId] = replacement
                newlyReplacedCount++
            }
            // 失败时仅 seen，视作 frozen
        }

        if (newlyReplacedCount > 0 || reappliedCount > 0) {
            DebugLog.d(
                "$TAG: persisted=$newlyReplacedCount, reapplied=$reappliedCount, " +
                "candidates=${candidatesByTurn.sumOf { it.size }}"
            )
        }

        return BudgetEnforceResult(
            messages = replaceToolResultContents(messages, replacementMap),
            newlyReplaced = newlyReplacedCount,
            reapplied = reappliedCount,
        )
    }

    /** 按历史决策分区候选 */
    private fun partitionByPriorDecision(
        candidates: List<ToolResultCandidate>,
        state: ContentReplacementState,
    ): CandidatePartition {
        val mustReapply = mutableListOf<ToolResultCandidateWithReplacement>()
        val frozen = mutableListOf<ToolResultCandidate>()
        val fresh = mutableListOf<ToolResultCandidate>()

        for (c in candidates) {
            val replacement = state.replacements[c.toolUseId]
            when {
                replacement != null -> mustReapply.add(ToolResultCandidateWithReplacement(c, replacement))
                c.toolUseId in state.seenIds -> frozen.add(c)
                else -> fresh.add(c)
            }
        }
        return CandidatePartition(mustReapply, frozen, fresh)
    }

    /** 选最大的 fresh 候选替换，直到总量降到预算内 */
    private fun selectFreshToReplace(
        fresh: List<ToolResultCandidate>,
        frozenSize: Int,
        limit: Int,
    ): List<ToolResultCandidate> {
        val sorted = fresh.sortedByDescending { it.size }
        val selected = mutableListOf<ToolResultCandidate>()
        var remaining = frozenSize + fresh.sumOf { it.size }
        for (c in sorted) {
            if (remaining <= limit) break
            if (c.size < MIN_CANDIDATE_SIZE) continue  // 太小不值得持久化
            selected.add(c)
            remaining -= c.size
        }
        return selected
    }

    /**
     * 按 turn 分组提取候选工具结果。
     * Opedrgent 适配：工具结果是独立 ChatMessage（toolCallId != null），
     * "组"映射为"一轮内所有工具结果消息"——两个 assistant 边界之间。
     */
    private fun collectCandidatesByTurn(messages: List<ChatMessage>): List<List<ToolResultCandidate>> {
        val groups = mutableListOf<MutableList<ToolResultCandidate>>()
        var current = mutableListOf<ToolResultCandidate>()

        for ((index, msg) in messages.withIndex()) {
            when {
                msg.toolCallId != null -> {
                    val content = msg.content
                    if (content.isBlank()) continue
                    if (isContentAlreadyCompacted(content)) continue
                    current.add(
                        ToolResultCandidate(
                            toolUseId = msg.toolCallId,
                            content = content,
                            size = content.length,
                            messageIndex = index,
                        )
                    )
                }
                msg.role == Role.ASSISTANT -> {
                    // assistant 边界：flush 当前组
                    if (current.isNotEmpty()) {
                        groups.add(current)
                        current = mutableListOf()
                    }
                }
            }
        }
        if (current.isNotEmpty()) {
            groups.add(current)
        }

        return groups
    }

    private fun isContentAlreadyCompacted(content: String): Boolean {
        return content.startsWith(PERSISTED_OUTPUT_TAG)
    }

    /** 返回新消息列表，替换 tool_use_id 在 replacementMap 中的工具结果内容 */
    private fun replaceToolResultContents(
        messages: List<ChatMessage>,
        replacementMap: Map<String, String>,
    ): List<ChatMessage> {
        if (replacementMap.isEmpty()) return messages
        return messages.map { msg ->
            val replacement = msg.toolCallId?.let { replacementMap[it] }
            if (replacement != null) {
                msg.copy(content = replacement)
            } else {
                msg
            }
        }
    }

    /** 持久化工具结果到磁盘 + 生成预览消息 */
    private suspend fun persistAndBuildPreview(
        candidate: ToolResultCandidate,
        cacheDir: File,
    ): String? = withContext(Dispatchers.IO) {
        try {
            val dir = File(cacheDir, "tool-results").apply { mkdirs() }
            val file = File(dir, "${candidate.toolUseId}.txt")

            // 已存在则跳过（tool_use_id 唯一，内容确定）
            if (!file.exists()) {
                file.writeText(candidate.content)
            }

            buildPreviewFromContent(candidate.content, file.absolutePath)
        } catch (e: Exception) {
            DebugLog.w("$TAG persist failed for ${candidate.toolUseId}: ${e.message}")
            null
        }
    }

    /**
     * 根据原始工具结果内容构建 persisted-output 预览字符串。
     *
     * 抽取自 [persistAndBuildPreview]，供 [reconstructContentReplacementState] 重建
     * replacements Map 时复用，保证磁盘恢复与首次持久化生成的预览 byte-identical。
     */
    private fun buildPreviewFromContent(content: String, filePath: String): String {
        val (preview, hasMore) = generatePreview(content, PREVIEW_SIZE_BYTES)
        return buildString {
            appendLine(PERSISTED_OUTPUT_TAG)
            appendLine("Output too large (${formatFileSize(content.length)}). Full output saved to: $filePath")
            appendLine()
            appendLine("Preview (first ${formatFileSize(PREVIEW_SIZE_BYTES)}):")
            append(preview)
            appendLine(if (hasMore) "\n..." else "")
            appendLine(PERSISTED_OUTPUT_CLOSING_TAG)
        }
    }

    private fun generatePreview(content: String, maxBytes: Int): Pair<String, Boolean> {
        if (content.length <= maxBytes) return content to false
        val truncated = content.take(maxBytes)
        val lastNewline = truncated.lastIndexOf('\n')
        val cutPoint = if (lastNewline > maxBytes * 0.5) lastNewline else maxBytes
        return content.take(cutPoint) to true
    }

    private fun formatFileSize(bytes: Int): String = when {
        bytes >= 1024 * 1024 -> "%.1fMB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "%.1fKB".format(bytes / 1024.0)
        else -> "${bytes}B"
    }

    /** 判断内容是否已被 ContentReplacement 压缩（供 pruneToolOutput 跳过用） */
    fun isPersistedOutput(content: String): Boolean = content.startsWith(PERSISTED_OUTPUT_TAG)
}
