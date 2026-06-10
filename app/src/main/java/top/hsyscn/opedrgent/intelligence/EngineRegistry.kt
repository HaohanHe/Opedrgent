package top.hsyscn.opedrgent.intelligence

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import top.hsyscn.opedrgent.utils.DebugLog

/**
 * 可插拔搜索引擎注册表（对标 SearXNG Engine Registry）。
 *
 * ## 设计理念（来自 SearXNG）
 *
 * SearXNG 的核心创新是将**搜索引擎抽象为可插拔模块**：
 * - 每个 Engine 独立实现请求构造和结果解析
 * - Engine Manager 并行调度多个引擎
 * - Result Merger 合并去重多个来源的结果
 * - User Preferences 影响最终排序
 *
 * 本实现适配 Android/Kotlin 环境：
 * - 协程并行搜索（coroutineScope + async）
 * - 结果评分融合（对标 SearXNG 累乘权重）
 * - 内容哈希去重（URL + 内容指纹）
 * - 引擎健康检查和自动降级
 */

/** 搜索结果条目 */
data class SearchResultItem(
    val title: String,
    val url: String,
    val content: String,
    val engine: String,           // 来源引擎名
    val score: Float = 0f,       // 引擎原始分数
    val fusedScore: Float = 0f,  // 融合后分数
    val position: Int = 0,       // 在该引擎结果中的位置
    val metadata: Map<String, String> = emptyMap(),
) {
    /** 内容哈希（用于去重） */
    val contentHash: Int get() = (title + url).hashCode()
}

/** 搜索引擎定义 */
data class SearchEngine(
    val name: String,                    // 唯一标识
    val displayName: String,              // 展示名
    val description: String = "",
    val category: EngineCategory = EngineCategory.GENERAL,
    var weight: Float = 1.0f,            // 引擎权重（融合时使用）
    val timeoutMs: Long = 10_000L,        // 超时时间
    var enabled: Boolean = true,          // 是否启用
    val supportsSafeSearch: Boolean = false,
    val maxResults: Int = 10,             // 单引擎最大返回数
)

/** 引擎分类 */
enum class EngineCategory(val displayName: String) {
    GENERAL("通用"),
    ACADEMIC("学术"),
    CODE("代码"),
    NEWS("新闻"),
    IMAGE("图片"),
    VIDEO("视频"),
    KNOWLEDGE("知识库"),
    LOCAL("本地"),
}

/** 搜索查询 */
data class SearchQuery(
    val text: String,
    val language: String = "zh",
    val safeSearch: Boolean = false,
    val page: Int = 1,
    val pageSize: Int = 20,
    val categories: Set<EngineCategory> = emptySet(),  // 限制搜索的分类
    val engines: Set<String>? = null,                   // 指定使用的引擎（null=全部启用）
)

/** 搜索响应 */
data class SearchResponse(
    val query: SearchQuery,
    val results: List<SearchResultItem>,
    val enginesUsed: List<String>,
    val totalTimeMs: Long,
    val engineTimings: Map<String, Long>,  // engine name →耗时(ms)
    val totalCount: Int,
    val didYouMean: String? = null,
)

/** 搜索引擎接口 */
interface SearchEngineExecutor {
    /** 引擎定义 */
    val engine: SearchEngine

    /**
     * 执行搜索。
     *
     * @param query 搜索查询
     * @return 该引擎的结果列表（空列表表示无结果或出错）
     */
    suspend fun search(query: SearchQuery): List<SearchResultItem>

    /** 健康检查 */
    suspend fun healthCheck(): Boolean
}

/**
 * 基于 Lambda 的简易搜索引擎（快速注册）。
 */
class LambdaSearchEngine(
    override val engine: SearchEngine,
    private val executor: suspend (SearchQuery) -> List<SearchResultItem>,
) : SearchEngineExecutor {

    override suspend fun search(query: SearchQuery): List<SearchResultItem> {
        return try {
            executor(query).take(engine.maxResults).mapIndexed { idx, item ->
                item.copy(engine = engine.name, position = idx)
            }
        } catch (e: Exception) {
            DebugLog.w("EngineRegistry: search error on [${engine.name}]: ${e.message}")
            emptyList()
        }
    }

    override suspend fun healthCheck(): Boolean = try {
        search(SearchQuery("test")).isNotEmpty()
    } catch (_: Exception) {
        false
    }
}

/**
 * 搜索引擎注册表。
 */
class EngineRegistry {
    private val engines = mutableMapOf<String, SearchEngineExecutor>()
    private val mutex = Mutex()

    // ==================== 注册 API ====================

    /**
     * 注册一个搜索引擎。
     */
    suspend fun register(executor: SearchEngineExecutor) = mutex.withLock {
        engines[executor.engine.name] = executor
        DebugLog.i("EngineRegistry: registered [${executor.engine.name}] weight=${executor.engine.weight}")
    }

    /**
     * 快速注册 Lambda 引擎。
     */
    suspend fun registerLambda(
        name: String,
        displayName: String,
        weight: Float = 1.0f,
        category: EngineCategory = EngineCategory.GENERAL,
        executor: suspend (SearchQuery) -> List<SearchResultItem>,
    ) {
        register(LambdaSearchEngine(
            engine = SearchEngine(name = name, displayName = displayName, weight = weight, category = category),
            executor = executor,
        ))
    }

    /**
     * 注销引擎。
     */
    suspend fun unregister(name: String) = mutex.withLock {
        engines.remove(name)
        DebugLog.i("EngineRegistry: unregistered [$name]")
    }

    /** 启用/禁用 */
    fun setEnabled(name: String, enabled: Boolean) {
        engines[name]?.engine?.enabled = enabled
    }

    /** 调整权重 */
    fun setWeight(name: String, weight: Float) {
        engines[name]?.engine?.weight = weight.coerceIn(0f, 5f)
    }

    // ==================== 查询 API ====================

    /**
     * 多引擎并行搜索（核心方法）。
     *
     * 流程：
     * 1. 选择启用的目标引擎
     * 2. 并行发送查询（async + withTimeoutOrNull）
     * 3. 收集各引擎结果
     * 4. 融合评分 + 去重
     * 5. 排序截断返回
     */
    suspend fun search(query: SearchQuery): SearchResponse {
        val startTime = System.currentTimeMillis()

        val targetEngines = mutex.withLock {
            engines.values.filter { exec ->
                val eng = exec.engine
                eng.enabled && (query.engines == null || eng.name in query.engines) &&
                (query.categories.isEmpty() || eng.category in query.categories)
            }
        }

        if (targetEngines.isEmpty()) {
            return SearchResponse(query, emptyList(), emptyList(), 0, emptyMap(), 0)
        }

        // 并行搜索
        val engineResults = coroutineScope {
            targetEngines.mapAsync { executor ->
                val eng = executor.engine
                val engineStart = System.currentTimeMillis()

                try {
                    val results = withTimeoutOrNull(eng.timeoutMs) {
                        executor.search(query)
                    } ?: run {
                        DebugLog.w("EngineRegistry: timeout [${eng.name}]")
                        emptyList()
                    }

                    eng.name to Pair(results, System.currentTimeMillis() - engineStart)
                } catch (e: Exception) {
                    DebugLog.w("EngineRegistry: error [${eng.name}]: ${e.message}")
                    eng.name to Pair(emptyList<SearchResultItem>(), System.currentTimeMillis() - engineStart)
                }
            }.toMap()
        }

        // 融合评分
        val fusedResults = fuseResults(engineResults, query.pageSize)

        val totalTime = System.currentTimeMillis() - startTime
        val timings = engineResults.mapValues { it.value.second }
        val usedEngines = engineResults.keys.toList()

        return SearchResponse(
            query = query,
            results = fusedResults,
            enginesUsed = usedEngines,
            totalTimeMs = totalTime,
            engineTimings = timings,
            totalCount = fusedResults.size,
        )
    }

    /**
     * 单引擎搜索（调试用）。
     */
    suspend fun searchSingle(engineName: String, query: SearchQuery): List<SearchResultItem> {
        val executor = engines[engineName] ?: return emptyList()
        return executor.search(query)
    }

    // ==================== 融合算法（对标 SearXNG）====================

    /**
     * 多引擎结果融合。
     *
     * 算法（简化版 SearXNG 累乘权重）：
     * 1. 每个结果的初始分数 = engine_weight / position（位置衰减）
     * 2. 同一 URL 出现在多个引擎 → 分数累加（多源放大）
     * 3. 按融合分数降序排列
     * 4. 内容哈希去重
     */
    private fun fuseResults(
        engineResults: Map<String, Pair<List<SearchResultItem>, Long>>,
        limit: Int,
    ): List<SearchResultItem> {
        val scoreMap = mutableMapOf<Int, SearchResultItem>()  // contentHash → best result

        for ((engineName, pair) in engineResults) {
            val (results, _) = pair
            val weight = engines[engineName]?.engine?.weight ?: 1.0f

            for (result in results) {
                val hash = result.contentHash

                // 位置衰减分数
                val positionScore = if (result.position > 0) {
                    weight / (result.position + 1).toFloat()
                } else weight

                // 累乘因子：多源出现放大
                val existing = scoreMap[hash]
                if (existing != null) {
                    // 已经在其他引擎出现过，累加分数
                    val newScore = existing.fusedScore + positionScore * 1.5f  // 多源加分
                    scoreMap[hash] = existing.copy(fusedScore = newScore)
                } else {
                    scoreMap[hash] = result.copy(fusedScore = positionScore)
                }
            }
        }

        return scoreMap.values
            .sortedByDescending { it.fusedScore }
            .take(limit)
    }

    // ==================== 管理API ====================

    fun listEngines(): List<SearchEngine> = engines.values.map { it.engine }
    fun getEnabledEngines(): List<SearchEngine> = engines.values.filter { it.engine.enabled }.map { it.engine }
    fun size(): Int = engines.size

    /**
     * 批量健康检查。
     */
    suspend fun healthCheckAll(): Map<String, Boolean> = coroutineScope {
        engines.map { (name, executor) ->
            async { name to executor.healthCheck() }
        }.associate { it.await() }
    }

    /**
     * 统计信息。
     */
    fun stats(): EngineRegistryStats = EngineRegistryStats(
        total = engines.size,
        enabled = engines.values.count { it.engine.enabled },
        disabled = engines.values.count { !it.engine.enabled },
        byCategory = engines.values.groupingBy { it.engine.category }.eachCount(),
    )
}

/** 统计信息 */
data class EngineRegistryStats(
    val total: Int,
    val enabled: Int,
    val disabled: Int,
    val byCategory: Map<EngineCategory, Int>,
) {
    fun toDisplayText(): String = """EngineRegistry:
  | Total: $total ($enabled enabled, $disabled disabled)
  | By category: ${byCategory.entries.joinToString { "${it.key.displayName}=${it.value}" }}""".trimMargin()
}

// ==================== 辅助扩展 ====================

/** 并行 map（带并发控制）*/
private suspend fun <T, R> Collection<T>.mapAsync(transform: suspend (T) -> R): List<R> =
    coroutineScope { map { async { transform(it) } }.awaitAll() }
