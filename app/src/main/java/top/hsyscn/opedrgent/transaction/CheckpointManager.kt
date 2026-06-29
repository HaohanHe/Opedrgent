package top.hsyscn.opedrgent.transaction

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.hsyscn.opedrgent.agent.AgentStorage
import top.hsyscn.opedrgent.model.ChatMessage
import top.hsyscn.opedrgent.utils.DebugLog

/**
 * 检查点管理器：创建 / 标记墓碑 / 终结判定 / 消息历史 diff。
 *
 * 检查点生命周期：
 * 1. [createCheckpoint]：事务开始时快照消息历史与存储，返回 checkpointId。
 * 2. 事务期间累积 [ToolCallRecord]（由调用方追加，回滚时作为 LIFO 补偿依据）。
 * 3. [markTombstone]：事务正常终结（成功提交）后打墓碑，禁止后续回滚。
 * 4. [RollbackExecutor.rollback]：事务失败时基于检查点回滚。
 */
class CheckpointManager(private val storage: CheckpointStorageProvider) {

    companion object {
        private const val TAG = "CheckpointManager"
    }

    /** 按 checkpointId 分区的协程互斥锁，保证 load-modify-save 序列的原子性（并发 append/replace 安全）。 */
    private val mutexes = ConcurrentHashMap<String, Mutex>()
    private fun mutexFor(id: String): Mutex = mutexes.computeIfAbsent(id) { Mutex() }

    /**
     * 创建并保存检查点。
     *
     * @param agentName Agent 名称
     * @param messages 当前消息历史（将浅拷贝为快照）
     * @param storage 共享存储（将 copy() 为快照）
     * @param toolCalls 事务期间已执行的工具调用记录（初始通常为空）
     * @return 新建的 checkpointId
     */
    suspend fun createCheckpoint(
        agentName: String,
        messages: List<ChatMessage>,
        storage: AgentStorage,
        toolCalls: List<ToolCallRecord> = emptyList(),
    ): String {
        val checkpointId = "cp-${UUID.randomUUID()}"
        // ChatMessage 是 data class，parts/toolParts 均为不可变 List，浅拷贝已足够；
        // 此处用 toList() 取快照避免外部可变列表后续修改污染检查点。
        val messageSnapshot = messages.toList()
        val storageSnapshot = storage.copy()
        val checkpoint = AgentCheckpointData(
            checkpointId = checkpointId,
            agentName = agentName,
            messageHistorySnapshot = messageSnapshot,
            storageSnapshot = storageSnapshot,
            toolCalls = toolCalls.toList(),
        )
        this.storage.save(checkpoint)
        DebugLog.i(TAG, "checkpoint created: id=$checkpointId, agent=$agentName, msgs=${messageSnapshot.size}, tools=${toolCalls.size}")
        return checkpointId
    }

    /** 读取检查点（不删除）。 */
    suspend fun getCheckpoint(checkpointId: String): AgentCheckpointData? = storage.load(checkpointId)

    /**
     * 追加工具调用记录到已有检查点（事务期间累积补偿依据）。
     * 若检查点不存在或已墓碑则忽略。协程安全（按 checkpointId 加 Mutex）。
     */
    suspend fun appendToolCall(checkpointId: String, record: ToolCallRecord) {
        mutexFor(checkpointId).withLock {
            val cp = storage.load(checkpointId) ?: run {
                DebugLog.w(TAG, "appendToolCall: checkpoint $checkpointId not found, skip")
                return@withLock
            }
            if (cp.tombstone) {
                DebugLog.w(TAG, "appendToolCall: checkpoint $checkpointId already tombstoned, skip")
                return@withLock
            }
            val updated = cp.copy(toolCalls = cp.toolCalls + record)
            storage.save(updated)
        }
    }

    /**
     * 整批替换检查点的工具调用记录。适合并行执行场景：工具全部跑完后一次性写入，
     * 避免并发 append 的开销。若检查点不存在或已墓碑则忽略。协程安全。
     */
    suspend fun replaceToolCalls(checkpointId: String, records: List<ToolCallRecord>) {
        mutexFor(checkpointId).withLock {
            val cp = storage.load(checkpointId) ?: run {
                DebugLog.w(TAG, "replaceToolCalls: checkpoint $checkpointId not found, skip")
                return@withLock
            }
            if (cp.tombstone) {
                DebugLog.w(TAG, "replaceToolCalls: checkpoint $checkpointId already tombstoned, skip")
                return@withLock
            }
            val updated = cp.copy(toolCalls = records.toList())
            storage.save(updated)
            DebugLog.d(TAG, "replaceToolCalls: $checkpointId updated with ${records.size} records")
        }
    }

    /** 标记检查点为已终结（墓碑），之后不可回滚。幂等。 */
    suspend fun markTombstone(checkpointId: String) {
        val cp = storage.load(checkpointId) ?: run {
            DebugLog.w(TAG, "markTombstone: checkpoint $checkpointId not found, skip")
            return
        }
        if (cp.tombstone) {
            DebugLog.d(TAG, "markTombstone: $checkpointId already tombstoned (idempotent)")
            return
        }
        cp.tombstone = true
        storage.save(cp)
        DebugLog.i(TAG, "tombstone marked: $checkpointId")
    }

    /** 检查点是否已终结（墓碑或不存在均视为不可回滚）。 */
    suspend fun isTerminated(checkpointId: String): Boolean {
        val cp = storage.load(checkpointId) ?: return true
        return cp.tombstone
    }

    /** 删除检查点（事务彻底结束后清理）。 */
    suspend fun deleteCheckpoint(checkpointId: String) {
        storage.delete(checkpointId)
    }

    /**
     * 计算消息历史 diff：返回 [after] 中相对 [before] 多出来的消息（即回滚时需移除的部分）。
     *
     * 严格前缀匹配——找到 [before] 与 [after] 的最长公共前缀，前缀之后的消息均为"新增"。
     * 若 [before] 不是 [after] 的干净前缀（前段被篡改），则以公共前缀长度为准，
     * 公共前缀之后的所有消息都视为待移除，保证回滚后状态确定。
     *
     * @return 新增消息列表（after 中超出公共前缀的部分），顺序与 after 一致
     */
    internal fun messageHistoryDiff(before: List<ChatMessage>, after: List<ChatMessage>): List<ChatMessage> {
        if (before.isEmpty()) return after.toList()
        val commonLen = commonPrefixLength(before, after)
        return after.drop(commonLen)
    }

    /**
     * 计算回滚后应保留的消息列表（公共前缀）。
     * 与 [messageHistoryDiff] 互补：保留前缀，移除新增后缀。
     */
    internal fun retainedAfterRollback(before: List<ChatMessage>, after: List<ChatMessage>): List<ChatMessage> {
        if (before.isEmpty()) return emptyList()
        val commonLen = commonPrefixLength(before, after)
        return after.take(commonLen)
    }

    /**
     * 最长公共前缀长度。按消息身份（id + role + toolCallId）逐条比对，
     * 而非全字段相等——回滚期间新增的 assistant/tool 消息不会改变已有消息身份。
     */
    private fun commonPrefixLength(before: List<ChatMessage>, after: List<ChatMessage>): Int {
        val minLen = minOf(before.size, after.size)
        var i = 0
        while (i < minLen && sameIdentity(before[i], after[i])) {
            i++
        }
        return i
    }

    private fun sameIdentity(a: ChatMessage, b: ChatMessage): Boolean {
        // 用稳定身份字段比对：id 是 UUID，role 与 toolCallId 共同定位工具结果消息
        return a.id == b.id && a.role == b.role && a.toolCallId == b.toolCallId
    }
}
