package top.hsyscn.opedrgent.agent

import java.util.concurrent.ConcurrentHashMap

/**
 * AgentStorage — 跨 Agent 的类型安全键值存储
 *
 * 借鉴 Koog 框架的 AIAgentStorage 设计：
 * - 类型安全的 StorageKey<T>，编译期保证类型正确
 * - 并发安全（ConcurrentHashMap）
 * - 支持深度拷贝（用于快照和回滚）
 * - Agent 间通过 storage 共享中间状态，避免通过消息传递大段文本
 *
 * 使用示例：
 * ```kotlin
 * val searchResultsKey = StorageKey<List<String>>("search_results")
 * storage.set(searchResultsKey, listOf("result1", "result2"))
 * val results = storage.get(searchResultsKey) // List<String>?
 * ```
 */
class AgentStorage {

    private val data = ConcurrentHashMap<String, Any>()

    /**
     * 存入值。Key 的 name 作为存储键，value 作为值。
     */
    fun <T> set(key: StorageKey<T>, value: T) {
        data[key.name] = value as Any
    }

    /**
     * 读取值。类型安全，返回 T?。
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: StorageKey<T>): T? {
        return data[key.name] as? T
    }

    /**
     * 读取值，不存在时抛异常。
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> getValue(key: StorageKey<T>): T {
        return data[key.name] as? T
            ?: throw NoSuchElementException("Storage key '${key.name}' not found")
    }

    /**
     * 读取值，不存在时返回默认值。
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> getOrDefault(key: StorageKey<T>, default: T): T {
        return (data[key.name] as? T) ?: default
    }

    /**
     * 检查 key 是否存在。
     */
    fun contains(key: StorageKey<*>): Boolean {
        return data.containsKey(key.name)
    }

    /**
     * 移除指定 key。
     */
    fun remove(key: StorageKey<*>) {
        data.remove(key.name)
    }

    /**
     * 清空所有存储。
     */
    fun clear() {
        data.clear()
    }

    /**
     * 当前存储条目数。
     */
    fun size(): Int = data.size

    /**
     * 获取所有 key 名称（用于调试）。
     */
    fun keys(): Set<String> = data.keys.toSet()

    /**
     * 深度拷贝（快照当前状态，用于回滚或传递给子 Agent）。
     */
    fun copy(): AgentStorage {
        val snapshot = AgentStorage()
        snapshot.data.putAll(data)
        return snapshot
    }

    /**
     * 从另一个 storage 合并数据（不覆盖已存在的 key）。
     */
    fun mergeFrom(other: AgentStorage) {
        for ((key, value) in other.data) {
            data.putIfAbsent(key, value)
        }
    }

    /**
     * 转为 Map（用于调试和日志）。
     */
    fun toMap(): Map<String, Any> = data.toMap()

    override fun toString(): String {
        return "AgentStorage(keys=${data.keys}, size=${data.size})"
    }
}

/**
 * 类型安全的存储键。
 *
 * @param name 键名（在 storage 中唯一标识）
 * @param T 存储值的类型
 */
data class StorageKey<T>(val name: String) {
    init {
        require(name.isNotBlank()) { "StorageKey name must not be blank" }
    }
}

/**
 * 便捷扩展：为 AgentOutput 创建 StorageKey。
 */
fun AgentOutput.toStorageKey(): StorageKey<String> = StorageKey("agent_output:$agentName")
