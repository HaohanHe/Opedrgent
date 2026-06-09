package top.hsyscn.opedrgent.intelligence

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.hsyscn.opedrgent.utils.DebugLog
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * 内存目录系统（Memory Directory）— 对标 Claude Code memdir。
 *
 * ## 设计理念（来自 Claude Code）
 *
 * 将 Agent 的记忆组织为**文件系统风格**的目录结构：
 * ```
 * memory:/
 * ├── conversations/          # 对话摘要
 * │   └── 2024-01-15.md       # 某日对话记忆
 * ├── facts/                  # 事实性知识
 * │   ├── user-preferences.md # 用户偏好
 * │   └── project-context.md  # 项目上下文
 * ├── tools/                  # 工具使用经验
 * │   └── frequently-used.md  # 常用工具记录
 * └── decisions/              # 决策记录
 *     └── architecture.md     # 架构决策
 * ```
 *
 * ## 核心特性
 * - **年龄衰减**：记忆随时间衰减，老记忆优先被压缩
 * - **路径检索**：支持前缀匹配的路径搜索
 * - **自动清理**：超过阈值的记忆自动归档或删除
 * - **线程安全**：所有操作都是协程安全的
 *
 * @see MemoryAge 记忆年龄计算
 * @see MemoryEntry 记忆条目
 */
class MemoryDir {

    companion object {
        /** 默认最大记忆条数 */
        const val DEFAULT_MAX_ENTRIES = 500

        /** 默认记忆保留天数（超过此时间的低重要性记忆会被清理） */
        const val DEFAULT_RETENTION_DAYS = 30

        /** 高重要性记忆的保留天数倍率 */
        const val IMPORTANT_MULTIPLIER = 3

        /** 半衰期常数（天）：重要性的半衰期 */
        const val HALF_LIFE_DAYS = 7.0
    }

    /** 内部存储：路径 → 记忆条目 */
    private val store = ConcurrentHashMap<String, MemoryEntry>()
    private val mutex = Mutex()

    // ==================== 核心操作 ====================

    /**
     * 写入/更新一条记忆。
     *
     * @param path 虚拟路径（如 "facts/user-preferences"）
     * @param content 记忆内容
     * @param importance 重要性 (0.0-1.0)
     * @param tags 标签列表
     * @param metadata 附加元数据
     */
    suspend fun write(
        path: String,
        content: String,
        importance: Float = 0.5f,
        tags: Set<String> = emptySet(),
        metadata: Map<String, String> = emptyMap(),
    ) {
        mutex.withLock {
            val existing = store[path]
            if (existing != null) {
                // 更新已有记忆：内容变化时刷新时间戳
                store[path] = existing.copy(
                    content = content,
                    updatedAt = System.currentTimeMillis(),
                    accessCount = existing.accessCount + 1,
                    importance = max(importance, existing.importance), // 取更高的重要性
                    tags = if (tags.isEmpty()) existing.tags else tags,
                    metadata = if (metadata.isEmpty()) existing.metadata else metadata,
                )
            } else {
                // 新记忆
                if (store.size >= DEFAULT_MAX_ENTRIES) {
                    // 容量满时，先清理最不重要的旧记忆
                    cleanup(1)
                }
                store[path] = MemoryEntry(
                    path = path,
                    content = content,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    importance = importance.coerceIn(0f, 1f),
                    tags = tags,
                    metadata = metadata,
                )
            }
            DebugLog.d("MemoryDir", "write[$path] (${content.take(30)}...)")
        }
    }

    /**
     * 读取一条记忆（同时更新访问计数和时间）。
     *
     * @param path 虚拟路径
     * @return 记忆条目，不存在则返回 null
     */
    suspend fun read(path: String): MemoryEntry? = mutex.withLock {
        val entry = store[path]?.let {
            it.copy(accessCount = it.accessCount + 1, lastAccessedAt = System.currentTimeMillis())
        }
        if (entry != null) store[path] = entry
        entry
    }

    /**
     * 删除一条记忆。
     *
     * @param path 虚拟路径
     * @return 是否成功删除
     */
    suspend fun delete(path: String): Boolean = mutex.withLock {
        store.remove(path) != null
    }

    /**
     * 检查路径是否存在。
     *
     * @param path 虚拟路径
     * @return 是否存在
     */
    fun exists(path: String): Boolean = store.containsKey(path)

    // ==================== 搜索与扫描 ====================

    /**
     * 按前缀搜索记忆（类似 ls 命令）。
     *
     * @param prefix 路径前缀（如 "conversations/" 返回所有对话记忆）
     * @param sortBy 排序方式
     * @param limit 最大返回数量
     * @return 匹配的记忆列表（已排序）
     */
    suspend fun list(
        prefix: String = "",
        sortBy: SortBy = SortBy.UPDATED_AT,
        limit: Int = 50,
    ): List<MemoryEntry> = mutex.withLock {
        store.values
            .filter { prefix.isEmpty() || it.path.startsWith(prefix) }
            .sortedWith(compareBy(sortBy.comparator))
            .reversed()
            .take(limit)
    }

    /**
     * 全文搜索记忆内容。
     *
     * @param query 搜索关键词
     * @param fuzzy 是否模糊匹配（包含子串即可）
     * @return 匹配的记忆列表（按相关度排序）
     */
    suspend fun search(query: String, fuzzy: Boolean = true): List<MemoryEntry> =
        mutex.withLock {
            if (query.isBlank()) return@withLock emptyList()

            val lowerQuery = query.lowercase()
            store.values.filter { entry ->
                if (fuzzy) {
                    entry.content.lowercase().contains(lowerQuery) ||
                            entry.path.lowercase().contains(lowerQuery) ||
                            entry.tags.any { it.lowercase().contains(lowerQuery) }
                } else {
                    entry.content.contains(query) || entry.path.contains(query)
                }
            }.sortedByDescending { it.relevanceScore(query) }
        }

    /**
     * 按标签筛选记忆。
     *
     * @param tag 标签名
     * @return包含该标签的记忆列表（按重要性降序）
     */
    suspend fun findByTag(tag: String): List<MemoryEntry> = mutex.withLock {
        store.values.filter { tag in it.tags }
            .sortedByDescending { it.importance }
    }

    // ==================== 年龄与衰减 ====================

    /**
     * 获取所有记忆的当前年龄快照。
     *
     * @return 每条记忆的年龄信息列表
     */
    suspend fun getAgeSnapshot(): List<MemoryAgeInfo> = mutex.withLock {
        store.values.map { entry ->
            MemoryAgeInfo(
                path = entry.path,
                ageDays = MemoryAge.ageDays(entry.updatedAt),
                decayedImportance = MemoryAge.decayedImportance(
                    entry.importance,
                    entry.updatedAt,
                ),
                shouldRetain = MemoryAge.shouldRetain(
                    entry.importance,
                    entry.updatedAt,
                    DEFAULT_RETENTION_DAYS,
                ),
            )
        }
    }

    /**
     * 执行衰减清理：移除过期且低重要性的记忆。
     *
     * @param forceCount 强制清理的数量（即使未过期也清理最不重要的）
     * @return 清理的路径列表
     */
    suspend fun cleanup(forceCount: Int = 0): List<String> = mutex.withLock {
        val toRemove = mutableListOf<Pair<String, Float>>()

        for ((path, entry) in store) {
            val age = MemoryAge.ageDays(entry.updatedAt)
            val retentionDays = (DEFAULT_RETENTION_DAYS * IMPORTANT_MULTIPLIER * entry.importance).toInt()

            if (age > retentionDays || entry.decayedImportance < 0.05f) {
                toRemove.add(path to entry.decayedImportance)
            }
        }

        // 按衰减后重要性排序（最低的先删）
        toRemove.sortBy { it.second }

        // 如果需要强制清理额外数量
        if (forceCount > 0 && toRemove.size < forceCount) {
            val additional = store.entries
                .filter { (path, _) -> path !in toRemove.map { it.first } }
                .map { (path, entry) -> path to entry.decayedImportance }
                .sortedBy { it.second }
                .take(forceCount - toRemove.size)
            toRemove.addAll(additional)
        }

        val removed = toRemove.take(max(forceCount, toRemove.size)).map { it.first }
        for (path in removed) {
            store.remove(path)
        }

        if (removed.isNotEmpty()) {
            DebugLog.i("MemoryDir", "cleaned ${removed.size} entries: ${removed.take(3)}...")
        }
        removed
    }

    /**
     * 压缩旧记忆：将多条低活跃度的记忆合并为摘要。
     *
     * @param prefix 要压缩的路径前缀
     * @param targetCount 压缩后的目标条数
     * @return 被压缩的条目数量
     */
    suspend fun compress(prefix: String, targetCount: Int = 5): Int = mutex.withLock {
        val entries = store.values
            .filter { it.path.startsWith(prefix) }
            .sortedBy { it.decayedImportance }

        if (entries.size <= targetCount) return@withLock 0

        val toCompress = entries.drop(targetCount)
        val summaryContent = buildString {
            appendLine("## ${prefix} 记忆摘要 (${toCompress.size} 条已压缩)")
            appendLine()
            for (entry in toCompress) {
                appendLine("- **${entry.path}**: ${entry.content.take(100)}")
            }
        }

        // 删除被压缩的条目
        for (entry in toCompress) {
            store.remove(entry.path)
        }

        // 写入摘要
        val summaryPath = "${prefix.trimEnd('/')}/_compressed_summary"
        store[summaryPath] = MemoryEntry(
            path = summaryPath,
            content = summaryContent,
            createdAt = toCompress.minOfOrNull { it.createdAt } ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            importance = 0.3f,
            tags = setOf("compressed", "auto-generated"),
            metadata = mapOf("original_count" to toCompress.size.toString()),
        )

        DebugLog.i("MemoryDir", "compressed ${toCompress.size} entries under '$prefix' → $summaryPath")
        toCompress.size
    }

    // ==================== 统计与导出 ====================

    /** 总记忆条数 */
    fun size(): Int = store.size

    /** 获取所有路径 */
    fun paths(): Set<String> = store.keys.toSet()

    /**
     * 导出为可序列化的 Map（用于持久化）。
     *
     * @return 路径到记忆条目的映射
     */
    suspend fun exportMap(): Map<String, MemoryEntry> = mutex.withLock {
        store.toMap()
    }

    /**
     * 从 Map 导入（用于恢复持久化数据）。
     *
     * @param data 要导入的数据映射
     */
    suspend fun importMap(data: Map<String, MemoryEntry>) = mutex.withLock {
        store.clear()
        store.putAll(data)
        DebugLog.i("MemoryDir", "imported ${data.size} entries")
    }

    /**
     * 获取统计信息。
     *
     * @return MemoryDir 统计数据
     */
    suspend fun stats(): MemoryDirStats = mutex.withLock {
        val now = System.currentTimeMillis()
        MemoryDirStats(
            totalEntries = store.size,
            totalSizeBytes = store.values.sumOf { it.content.length },
            byTag = store.values.flatMap { it.tags }.groupingBy { it }.eachCount(),
            byPrefix = store.keys
                .groupBy { it.substringBefore('/') }
                .mapValues { it.value.size },
            avgAgeDays = if (store.isNotEmpty()) {
                store.values.map { MemoryAge.ageDays(it.updatedAt) }.average()
            } else 0.0,
            highImportanceCount = store.values.count { it.importance >= 0.7f },
            expiredCount = store.values.count { !MemoryAge.shouldRetain(it.importance, it.updatedAt, DEFAULT_RETENTION_DAYS) },
        )
    }

    // ==================== 内部辅助 ====================

    /**
     * 排序方式枚举。
     */
    enum class SortBy(val comparator: Comparator<MemoryEntry>) {
        /** 按最后更新时间排序 */
        UPDATED_AT(compareBy { it.updatedAt }),
        /** 按创建时间排序 */
        CREATED_AT(compareBy { it.createdAt }),
        /** 按重要性排序 */
        IMPORTANCE(compareBy { it.importance }),
        /** 按访问次数排序 */
        ACCESS_COUNT(compareBy { it.accessCount }),
        /** 按路径字典序排序 */
        PATH(compareBy { it.path }),
        /** 按年龄排序（越老的排前面） */
        AGE(compareBy { it.updatedAt }),
    }
}

// ==================== 数据类 ====================

/**
 * 记忆条目（对标 Claude Code MemoryEntry）。
 *
 * 每条记忆代表一个虚拟文件，包含内容、元数据和访问统计。
 *
 * @property path 虚拟文件路径（如 "facts/user-preferences"）
 * @property content 记忆内容（Markdown 格式）
 * @property createdAt 创建时间戳（毫秒）
 * @property updatedAt 最后更新时间戳（毫秒）
 * @property importance 重要性权重 (0.0-1.0)，越高越不容易被清理
 * @property tags 标签集合，用于分类和检索
 * @property metadata 附加键值对元数据
 * @property accessCount 累计被读取次数
 * @property lastAccessedAt 最后一次访问时间戳
 */
data class MemoryEntry(
    val path: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
    val importance: Float = 0.5f,
    val tags: Set<String> = emptySet(),
    val metadata: Map<String, String> = emptyMap(),
    val accessCount: Int = 0,
    val lastAccessedAt: Long = 0L,
) {
    /** 衰减后的重要性（随时间降低） */
    val decayedImportance: Float get() = MemoryAge.decayedImportance(importance, updatedAt)

    /** 记忆年龄（天） */
    val ageDays: Double get() = MemoryAge.ageDays(updatedAt)

    /**
     * 与搜索查询的相关度分数。
     *
     * @param query 搜索关键词
     * @return 相关度分数 (0.0-1.0)，已乘以衰减因子
     */
    fun relevanceScore(query: String): Float {
        var score = 0f
        if (content.contains(query)) score += 0.5f
        if (path.contains(query)) score += 0.3f
        if (tags.any { it.contains(query) }) score += 0.2f
        return score * decayedImportance
    }
}

/**
 * 记忆年龄信息（用于展示和决策）。
 *
 * @property path 记忆路径
 * @property ageDays 年龄（天）
 * @property decayedImportance 衰减后的重要性
 * @property shouldRetain 是否应该保留
 */
data class MemoryAgeInfo(
    val path: String,
    val ageDays: Double,
    val decayedImportance: Float,
    val shouldRetain: Boolean,
)

/**
 * MemoryDir 统计信息。
 *
 * @property totalEntries 总条目数
 * @property totalSizeBytes 内容总大小（字节）
 * @property byTag 按标签分组的计数
 * @property byPrefix 按路径前缀分组的计数
 * @property avgAgeDays 平均年龄（天）
 * @property highImportanceCount 高重要性条目数
 * @property expiredCount 已过期条目数
 */
data class MemoryDirStats(
    val totalEntries: Int,
    val totalSizeBytes: Int,
    val byTag: Map<String, Int>,
    val byPrefix: Map<String, Int>,
    val avgAgeDays: Double,
    val highImportanceCount: Int,
    val expiredCount: Int,
) {
    /**
     * 格式化为可读文本。
     *
     * @return 格式化后的统计信息字符串
     */
    fun toDisplayText(): String = """MemoryDir Stats:
  | Total: $totalEntries entries (${totalSizeBytes / 1024}KB)
  | Avg age: ${String.format("%.1f", avgAgeDays)} days
  | High importance: $highImportanceCount
  | Expired: $expiredCount
  | By prefix: ${byPrefix.entries.joinToString { "${it.key}=${it.value}" }}
  | By tag: ${byTag.entries.sortedByDescending { it.value }.take(5).joinToString { "${it.key}(${it.value})" }}""".trimMargin()
}

// ==================== 记忆年龄算法（对标 Claude Code memoryAge.ts）====================

/**
 * 记忆年龄计算和衰减算法。
 *
 * ## 核心公式
 * ```
 * decayed_importance = initial_importance * e^(-ln(2) * age / half_life)
 * ```
 *
 * 即：每经过一个半衰期 [MemoryDir.HALF_LIFE_DAYS]，重要性减半。
 *
 * ## 使用示例
 * ```kotlin
 * val age = MemoryAge.ageDays(entry.updatedAt)
 * val decayed = MemoryAge.decayedImportance(0.8f, entry.updatedAt)
 * val keep = MemoryAge.shouldRetain(0.6f, entry.updatedAt, 30)
 * ```
 */
object MemoryAge {

    /** 一天的毫秒数 */
    private const val MS_PER_DAY = 24 * 60 * 60 * 1000L

    /**
     * 计算记忆年龄（天）。
     *
     * @param timestampMs 时间戳（毫秒）
     * @return 经过的时间（天），最小为 0.0
     */
    fun ageDays(timestampMs: Long): Double {
        val ageMs = System.currentTimeMillis() - timestampMs
        return (ageMs.toFloat() / MS_PER_DAY).coerceAtLeast(0.0).toDouble()
    }

    /**
     * 计算衰减后的重要性。
     *
     * 使用指数衰减模型：I(t) = I0 * e^(-λt)
     * 其中 λ = ln(2) / half_life
     *
     * @param initialImportance 初始重要性 (0.0-1.0)
     * @param timestampMs 时间戳（毫秒）
     * @return 衰减后的重要性 (0.0-1.0)
     */
    fun decayedImportance(initialImportance: Float, timestampMs: Long): Float {
        val ageDays = ageDays(timestampMs)
        val lambda = ln(2.0) / MemoryDir.HALF_LIFE_DAYS
        val decayFactor = exp(-lambda * ageDays).toFloat()
        return (initialImportance * decayFactor).coerceIn(0f, 1f)
    }

    /**
     * 判断记忆是否应该保留。
     *
     * 规则：
     * - 高重要性(>0.8)：始终保留
     * - 中高重要性(0.6-0.8)：2倍基础保留期
     * - 中等重要性(0.4-0.6)：标准保留期
     * - 低重要性(<0.5)：半保留期
     *
     * @param importance 初始重要性
     * @param timestampMs 时间戳（毫秒）
     * @param baseRetentionDays 基础保留天数
     * @return 是否应该保留该记忆
     */
    fun shouldRetain(importance: Float, timestampMs: Long, baseRetentionDays: Int): Boolean {
        if (importance > 0.8f) return true  // 高重要性永远保留

        val ageDays = ageDays(timestampMs)
        val effectiveRetention = when {
            importance > 0.6f -> baseRetentionDays * 2   // 中高重要性：2倍保留期
            importance > 0.4f -> baseRetentionDays         // 中等：标准保留期
            else -> (baseRetentionDays * 0.5).toInt()      // 低重要性：半保留期
        }

        return ageDays < effectiveRetention
    }
}
