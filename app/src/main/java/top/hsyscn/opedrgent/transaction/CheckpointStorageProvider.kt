package top.hsyscn.opedrgent.transaction

import java.util.concurrent.ConcurrentHashMap
import top.hsyscn.opedrgent.utils.DebugLog

/**
 * 检查点存储抽象。实现可选用内存或持久化（当前仅提供内存实现，避免 IO 风险）。
 */
interface CheckpointStorageProvider {
    suspend fun save(checkpoint: AgentCheckpointData)
    suspend fun load(checkpointId: String): AgentCheckpointData?
    suspend fun delete(checkpointId: String)
    suspend fun listByAgent(agentName: String): List<AgentCheckpointData>
}

/**
 * 内存检查点存储（线程安全）。
 *
 * 用 ConcurrentHashMap 承载，键为 checkpointId，值为可变 tombstone 标记的检查点。
 * 仅用于运行期事务回滚，进程结束即丢失——符合"避免 IO 风险"的设计约束。
 */
class InMemoryCheckpointStorage : CheckpointStorageProvider {

    private val store = ConcurrentHashMap<String, AgentCheckpointData>()

    override suspend fun save(checkpoint: AgentCheckpointData) {
        store[checkpoint.checkpointId] = checkpoint
    }

    override suspend fun load(checkpointId: String): AgentCheckpointData? = store[checkpointId]

    override suspend fun delete(checkpointId: String) {
        store.remove(checkpointId)
    }

    override suspend fun listByAgent(agentName: String): List<AgentCheckpointData> =
        store.values.filter { it.agentName == agentName }

    /** 当前存储条目数（调试/测试用）。 */
    fun size(): Int = store.size
}
