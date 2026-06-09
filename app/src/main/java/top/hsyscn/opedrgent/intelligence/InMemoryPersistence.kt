package top.hsyscn.opedrgent.intelligence

import java.util.concurrent.ConcurrentHashMap

/**
 * 内存持久化实现（原有行为）。
 *
 * 使用 ConcurrentHashMap 存储，保持与原 VectorMemory 相同的行为。
 * 适用于：
 * - 单元测试
 * - 开发调试
 * - 不需要持久化的场景
 *
 * ## 特点
 * - 线程安全（ConcurrentHashMap）
 * - 无 I/O 开销，性能最优
 * - App 重启后数据丢失
 */
class InMemoryPersistence : PersistenceLayer {

    /** 内存存储：ID → MemoryVector */
    private val store = ConcurrentHashMap<String, MemoryVector>()

    /** 集合索引：集合名 → ID集合 */
    private val collectionIndex = ConcurrentHashMap<String, MutableSet<String>>()

    override fun initialize() {
        // 内存实现无需初始化操作
    }

    override fun close() {
        // 清空内存
        store.clear()
        collectionIndex.clear()
    }

    override fun save(vector: MemoryVector): Boolean {
        store[vector.id] = vector
        collectionIndex.getOrPut(vector.collection) { ConcurrentHashMap.newKeySet() }.add(vector.id)
        return true
    }

    override fun saveBatch(vectors: List<MemoryVector>): Int {
        var count = 0
        for (vector in vectors) {
            if (save(vector)) count++
        }
        return count
    }

    override fun getById(id: String): MemoryVector? = store[id]

    override fun getAll(): List<MemoryVector> = store.values.toList()

    override fun deleteById(id: String): Boolean {
        val removed = store.remove(id)
        if (removed != null) {
            collectionIndex[removed.collection]?.remove(id)
            return true
        }
        return false
    }

    override fun deleteByCollection(collection: String): Int {
        val ids = collectionIndex[collection]?.toSet() ?: emptySet()
        var count = 0
        for (id in ids) {
            if (store.remove(id) != null) count++
        }
        collectionIndex.remove(collection)
        return count
    }

    override fun clearAll(): Boolean {
        store.clear()
        collectionIndex.clear()
        return true
    }

    override fun count(): Int = store.size

    override fun getCollections(): Set<String> = collectionIndex.keys.toSet()
}
