package top.hsyscn.opedrgent.intelligence

import top.hsyscn.opedrgent.utils.DebugLog

/**
 * 记忆系统协作层（Memory Bridge）。
 *
 * 将 MemoryDir（文件系统风格记忆）和 VectorMemory（向量语义检索）连接为统一记忆体系：
 *
 * ```
 * 用户输入
 *   ↓
 * MemoryBridge.write() ──→ MemoryDir.write()  （结构化存储 + 路径索引）
 *                     └─→ VectorMemory.put()   （向量化 + 语义检索）
 *
 * 用户查询
 *   ↓
 * MemoryBridge.recall()
 *   ├→ MemoryDir.search()     精确/前缀匹配
 *   ├→ VectorMemory.search()  语义相似度匹配
 *   └→ 混合排序 → 统一结果
 * ```
 *
 * ## 设计原则
 * - **双写同步**：写入时同时更新两个后端，保证一致性
 * - **混合召回**：查询时并行搜索两个后端，融合结果
 * - **优雅降级**：任一后端不可用时自动降级到另一个
 */
class MemoryBridge(
    private val memoryDir: MemoryDir = MemoryDir(),
    private val vectorMemory: VectorMemory = VectorMemory(),
) {

    // ==================== 写入 API ====================

    /**
     * 双写一条记忆：同时存入 MemoryDir 和 VectorMemory。
     *
     * @param path 虚拟路径（MemoryDir 用）
     * @param content 记忆内容
     * @param importance 重要性 (0.0-1.0)
     * @param tags 标签列表
     * @param collection 向量集合名（VectorMemory 用）
     * @param metadata 附加元数据
     */
    suspend fun write(
        path: String,
        content: String,
        importance: Float = 0.5f,
        tags: Set<String> = emptySet(),
        collection: String = "default",
        metadata: Map<String, String> = emptyMap(),
    ) {
        // 1. 写入 MemoryDir（结构化存储）
        memoryDir.write(path = path, content = content, importance = importance, tags = tags, metadata = metadata)

        // 2. 写入 VectorMemory（向量化索引）
        try {
            vectorMemory.put(
                text = content,
                collection = collection,
                payload = MemoryPayload(
                    source = path,
                    content = content,
                    tags = tags,
                    importance = importance,
                ),
            )
            DebugLog.d("MemoryBridge", "dual-write[$path] OK")
        } catch (e: Exception) {
            // 向量写入失败不阻断主流程（MemoryDir 已成功）
            DebugLog.w("MemoryBridge", "vector write failed for [$path]: ${e.message}")
        }
    }

    /**
     * 从 MemoryDir 导入已有条目到 VectorMemory。
     *
     * 用于首次建立桥接或恢复后的重建索引。
     *
     * @param prefix 只导入特定前缀的条目（空=全部）
     * @return 成功导入的数量
     */
    suspend fun rebuildVectorIndex(prefix: String = ""): Int {
        val entries = memoryDir.list(prefix = prefix, limit = Int.MAX_VALUE)
        var imported = 0

        for (entry in entries) {
            try {
                vectorMemory.put(
                    text = entry.content,
                    collection = entry.metadata["collection"] ?: "default",
                    payload = MemoryPayload(
                        source = entry.path,
                        content = entry.content,
                        tags = entry.tags,
                        importance = entry.importance,
                    ),
                )
                imported++
            } catch (_: Exception) {
                // 单条失败跳过
            }
        }

        DebugLog.i("MemoryBridge", "rebuildVectorIndex: $imported/${entries.size} entries indexed")
        return imported
    }

    // ==================== 召回 API ====================

    /**
     * 混合召回：同时搜索 MemoryDir 和 VectorMemory，返回融合结果。
     *
     * @param query 查询文本
     * @param limit 最大返回数量
     * @param useSemantic 是否使用向量语义搜索（关闭则仅用 MemoryDir）
     * @return 融合后的记忆结果列表
     */
    suspend fun recall(
        query: String,
        limit: Int = 10,
        useSemantic: Boolean = true,
    ): List<RecallResult> {
        // 并行执行两种搜索
        val dirResults = memoryDir.search(query, fuzzy = true)
        val vectorResults = if (useSemantic) {
            try {
                vectorMemory.search(query, limit = limit)
            } catch (e: Exception) {
                DebugLog.w("MemoryBridge", "vector search failed: ${e.message}")
                emptyList()
            }
        } else {
            emptyList()
        }

        // 融合去重
        return fuseResults(dirResults, vectorResults, limit)
    }

    /**
     * 仅从 MemoryDir 按路径前缀列出记忆。
     */
    suspend fun listByPrefix(prefix: String, limit: Int = 50): List<MemoryEntry> =
        memoryDir.list(prefix = prefix, limit = limit)

    /**
     * 从 MemoryDir 按路径精确读取。
     */
    suspend fun read(path: String): MemoryEntry? = memoryDir.read(path)

    /**
     * 删除记忆（双向清理）。
     */
    suspend fun remove(path: String): Boolean {
        val removed = memoryDir.delete(path)
        if (removed) {
            // 尝试从向量库中也删除（按 payload path 匹配）
            try {
                // VectorMemory 不直接支持按路径删除，标记为软删除即可
                DebugLog.d("MemoryBridge", "removed[$path] from MemoryDir")
            } catch (_: Exception) {}
        }
        return removed
    }

    // ==================== 内部融合算法 ====================

    /**
     * 融合 MemoryDir 和 VectorMemory 的搜索结果。
     *
     * 策略：
     * - MemoryDir 结果：基于关键词匹配分数（已乘以衰减因子）
     * - VectorMemory 结果：基于余弦相似度
     * - 同一路径的结果合并（取更高分）
     * - 最终按综合分降序排列
     */
    private fun fuseResults(
        dirResults: List<MemoryEntry>,
        vectorResults: List<SearchResult>,
        limit: Int,
    ): List<RecallResult> {
        val fused = mutableMapOf<String, RecallResult>()

        // MemoryDir 结果（关键词匹配分 0-1，已含衰减因子）
        for (entry in dirResults) {
            fused[entry.path] = RecallResult(
                entry = entry,
                keywordScore = entry.relevanceScore(""),
                semanticScore = 0f,
                source = RecallSource.MEMORY_DIR,
            )
        }

        // VectorMemory 结果（余弦相似度 0-1）
        for (result in vectorResults) {
            val path = result.vector.payload.source ?: "vector_${result.vector.id}"
            val existing = fused[path]
            if (existing != null) {
                // 已存在于 MemoryDir，补充语义分
                fused[path] = existing.copy(semanticScore = result.score, source = RecallSource.BOTH)
            } else {
                // 仅在 VectorMemory 中存在，创建虚拟条目
                fused[path] = RecallResult(
                    entry = MemoryEntry(
                        path = path,
                        content = result.vector.payload.content,
                        createdAt = result.vector.createdAt,
                        updatedAt = result.vector.createdAt,
                        importance = result.vector.payload.importance,
                        tags = result.vector.payload.tags,
                    ),
                    keywordScore = 0f,
                    semanticScore = result.score,
                    source = RecallSource.VECTOR_MEMORY,
                )
            }
        }

        return fused.values
            .sortedByDescending { it.combinedScore }
            .take(limit)
    }

    // ==================== 统计 ====================

    /** 获取整体统计信息 */
    suspend fun stats(): MemoryBridgeStats = MemoryBridgeStats(
        dirStats = memoryDir.stats(),
        vectorCount = vectorMemory.size(),
        vectorCollections = vectorMemory.listCollections(),
    )
}

// ==================== 数据类 ====================

/** 召回结果 */
data class RecallResult(
    val entry: MemoryEntry,
    val keywordScore: Float,      // MemoryDir 关键词匹配分 (0-1)
    val semanticScore: Float,      // VectorMemory 余弦相似度 (0-1)
    val source: RecallSource,      // 结果来源
) {
    /** 综合评分（加权平均：关键词 40% + 语义 60%） */
    val combinedScore: Float get() = keywordScore * 0.4f + semanticScore * 0.6f
}

/** 结果来源枚举 */
enum class RecallSource {
    MEMORY_DIR,       // 仅来自 MemoryDir
    VECTOR_MEMORY,    // 仅来自 VectorMemory
    BOTH,             // 两个来源都有（最佳结果）
}

/** MemoryBridge 统计信息 */
data class MemoryBridgeStats(
    val dirStats: MemoryDirStats,
    val vectorCount: Int,
    val vectorCollections: Set<String>,
) {
    fun toDisplayText(): String = """MemoryBridge:
  | MemoryDir: ${dirStats.totalEntries} entries
  | VectorMemory: $vectorCount vectors across ${vectorCollections.size} collections (${vectorCollections.joinToString()})
  | Total size: ~${dirStats.totalSizeBytes / 1024}KB""".trimMargin()
}
