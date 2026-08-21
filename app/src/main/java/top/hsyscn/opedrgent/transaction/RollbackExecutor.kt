package top.hsyscn.opedrgent.transaction

import top.hsyscn.opedrgent.model.ChatMessage
import top.hsyscn.opedrgent.network.ToolExecutor
import top.hsyscn.opedrgent.settings.ApiConfig
import top.hsyscn.opedrgent.utils.DebugLog

/**
 * 单次补偿工具的执行结果。
 *
 * @param toolName 被补偿的原工具名
 * @param succeeded 补偿是否成功
 * @param errorMessage 失败时的错误信息（成功为 null）
 */
data class CompensationResult(
    val toolName: String,
    val succeeded: Boolean,
    val errorMessage: String? = null,
)

/**
 * 回滚整体结果。
 *
 * @param success 整体回滚是否成功（STRICT 下任何补偿失败都置 false 并抛异常；
 *   DEFAULT 下尽力而为，消息历史回滚完成即视为 true）
 * @param rolledBackMessages 回滚后保留的消息列表（已移除检查点之后新增的消息）；
 *   调用方应以此替换当前消息历史
 * @param compensationResults 各补偿工具的执行结果（LIFO 顺序）
 * @param reason 附加说明（如 AlreadyTerminated / CheckpointNotFound / 各策略处理摘要）
 */
data class RollbackResult(
    val success: Boolean,
    val rolledBackMessages: List<ChatMessage>,
    val compensationResults: List<CompensationResult>,
    val reason: String,
)

/**
 * 回滚执行器：基于检查点执行 Saga 风格的 LIFO 补偿 + 消息历史回滚。
 *
 * 流程：
 * 1. 加载检查点，校验存在性与墓碑状态。
 * 2. 按 [RollbackStrategy] 分支：
 *    - MESSAGE_HISTORY_ONLY：跳过补偿，仅回滚消息历史。
 *    - DEFAULT / STRICT：反向（LIFO）执行补偿工具，失败按策略处理。
 * 3. 用 [CheckpointManager.messageHistoryDiff] 计算需移除的消息，保留公共前缀。
 * 4. 标记墓碑（事务终结），返回回滚后的消息列表。
 *
 * 线程安全：依赖 CheckpointManager 与 ToolExecutor 的内部并发安全保证；
 * 补偿工具按 LIFO 顺序串行执行，避免并发副作用。
 */
class RollbackExecutor(
    private val checkpointManager: CheckpointManager,
    private val rollbackRegistry: RollbackToolRegistry,
    private val toolExecutor: ToolExecutor,
) {

    companion object {
        private const val TAG = "RollbackExecutor"
    }

    /**
     * 执行回滚。
     *
     * @param checkpointId 检查点 ID
     * @param currentMessages 当前消息历史（回滚后缀的基准）
     * @param strategy 回滚策略
     * @param apiConfig 补偿工具执行所需的 ApiConfig（补偿工具如 run_calendar 本地操作，
     *   但 ToolExecutor.executeToolByName 签名要求传入）；为 null 时降级为仅回滚消息历史
     * @return 回滚结果
     */
    suspend fun rollback(
        checkpointId: String,
        currentMessages: List<ChatMessage>,
        strategy: RollbackStrategy = RollbackStrategy.DEFAULT,
        apiConfig: ApiConfig? = null,
    ): RollbackResult {
        val checkpoint = checkpointManager.getCheckpoint(checkpointId)
        if (checkpoint == null) {
            DebugLog.w(TAG, "rollback: checkpoint $checkpointId not found")
            return RollbackResult(
                success = false,
                rolledBackMessages = currentMessages,
                compensationResults = emptyList(),
                reason = "CheckpointNotFound: $checkpointId",
            )
        }
        if (checkpoint.tombstone) {
            DebugLog.w(TAG, "rollback: checkpoint $checkpointId already terminated (tombstone)")
            return RollbackResult(
                success = false,
                rolledBackMessages = currentMessages,
                compensationResults = emptyList(),
                reason = "AlreadyTerminated: $checkpointId",
            )
        }

        val before = checkpoint.messageHistorySnapshot
        val toolCalls = checkpoint.toolCalls

        // ---- 补偿阶段 ----
        val compensationResults = mutableListOf<CompensationResult>()
        val runCompensation = strategy != RollbackStrategy.MESSAGE_HISTORY_ONLY && toolCalls.isNotEmpty()

        if (runCompensation) {
            if (apiConfig == null) {
                DebugLog.w(TAG, "rollback: apiConfig null, compensation degraded to message-history-only")
            } else {
                // LIFO：后执行的先补偿
                for (record in toolCalls.asReversed()) {
                    val result = compensateOne(record, strategy, apiConfig)
                    compensationResults.add(result)
                    if (!result.succeeded && strategy == RollbackStrategy.STRICT) {
                        val msg = "STRICT rollback aborted: compensation failed for ${record.toolName}: ${result.errorMessage}"
                        DebugLog.e(TAG, msg)
                        // STRICT：标记墓碑后抛异常，确保显式失败
                        checkpointManager.markTombstone(checkpointId)
                        throw RollbackException(msg, compensationResults)
                    }
                }
            }
        }

        // ---- 消息历史回滚阶段 ----
        val removed = checkpointManager.messageHistoryDiff(before, currentMessages)
        val retained = checkpointManager.retainedAfterRollback(before, currentMessages)
        DebugLog.i(
            TAG,
            "message rollback: before=${before.size}, current=${currentMessages.size}, removed=${removed.size}, retained=${retained.size}",
        )

        // ---- 终结事务 ----
        checkpointManager.markTombstone(checkpointId)

        val successCount = compensationResults.count { it.succeeded }
        val failCount = compensationResults.size - successCount
        val reason = buildString {
            append("rollback done: ")
            append("compensations=${compensationResults.size}(ok=$successCount, fail=$failCount)")
            append(", messagesRemoved=${removed.size}")
            if (strategy == RollbackStrategy.MESSAGE_HISTORY_ONLY) append(", strategy=MESSAGE_HISTORY_ONLY")
            if (!runCompensation) append(", compensationSkipped")
        }
        DebugLog.i(TAG, reason)

        return RollbackResult(
            success = failCount == 0,
            rolledBackMessages = retained,
            compensationResults = compensationResults,
            reason = reason,
        )
    }

    /**
     * 执行单次补偿。失败时按 DEFAULT 容错（记录并继续），STRICT 由调用方判断抛异常。
     */
    private suspend fun compensateOne(
        record: ToolCallRecord,
        strategy: RollbackStrategy,
        apiConfig: ApiConfig,
    ): CompensationResult {
        // 仅补偿成功执行的原调用（失败的调用没有副作用需要撤销）
        if (!record.succeeded) {
            DebugLog.d(TAG, "compensate: skip ${record.toolName} (original call failed, no side effect)")
            return CompensationResult(toolName = record.toolName, succeeded = true, errorMessage = null)
        }

        val mapping = rollbackRegistry.lookup(record.toolName)
        if (mapping == null) {
            DebugLog.d(TAG, "compensate: no mapping for ${record.toolName}, skip (read-only or unregistered)")
            return CompensationResult(toolName = record.toolName, succeeded = true, errorMessage = null)
        }

        val compensationArgs: Map<String, Any>? = try {
            mapping.inputExtractor(record.input, record.output)
        } catch (e: Exception) {
            DebugLog.e(TAG, "compensate: inputExtractor threw for ${record.toolName}: ${e.message}", e)
            return CompensationResult(
                toolName = record.toolName,
                succeeded = false,
                errorMessage = "inputExtractor error: ${e.message}",
            )
        }

        if (compensationArgs == null) {
            DebugLog.d(TAG, "compensate: extractor returned null for ${record.toolName}, not compensable")
            // 提取器主动返回 null 表示该具体调用不可补偿（如 run_calendar 非 create action）
            return CompensationResult(toolName = record.toolName, succeeded = true, errorMessage = null)
        }

        val stringArgs = compensationArgsToStringMap(compensationArgs)
        DebugLog.i(TAG, "compensate: ${record.toolName} -> ${mapping.rollbackToolName} args=$stringArgs")

        return try {
            val output = toolExecutor.executeToolByName(
                toolName = mapping.rollbackToolName,
                args = stringArgs,
                config = apiConfig,
            )
            val ok = isCompensationOutputSuccessful(output)
            if (ok) {
                DebugLog.i(TAG, "compensate OK: ${record.toolName} -> ${output.take(120)}")
                CompensationResult(toolName = record.toolName, succeeded = true, errorMessage = null)
            } else {
                DebugLog.w(TAG, "compensate FAILED: ${record.toolName} -> ${output.take(200)}")
                CompensationResult(
                    toolName = record.toolName,
                    succeeded = false,
                    errorMessage = output.take(300),
                )
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "compensate exception: ${record.toolName}: ${e.message}", e)
            CompensationResult(
                toolName = record.toolName,
                succeeded = false,
                errorMessage = e.message,
            )
        }
    }

    /** 将 Map<String, Any> 转为 ToolExecutor.executeToolByName 所需的 Map<String, String>。 */
    private fun compensationArgsToStringMap(args: Map<String, Any>): Map<String, String> {
        return args.mapValues { (_, v) -> v.toString() }
    }

    /**
     * 启发式判断补偿工具输出是否成功。
     * run_calendar delete 成功输出形如 "[成功] 已删除事件 (ID: ...)"，失败为 "[失败]..."。
     */
    private fun isCompensationOutputSuccessful(output: String): Boolean {
        if (output.isBlank()) return false
        if (output.startsWith("[失败]")) return false
        if (output.startsWith("工具执行失败")) return false
        if (output.contains("工具执行超时")) return false
        return true
    }
}

/**
 * STRICT 策略下补偿失败抛出的异常（携带已完成补偿结果，便于诊断）。
 */
class RollbackException(
    message: String,
    val partialResults: List<CompensationResult>,
) : RuntimeException(message)
