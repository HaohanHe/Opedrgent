package top.hsyscn.opedrgent.intelligence

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.hsyscn.opedrgent.utils.DebugLog

/**
 * Feature Pipeline — 可插拔的拦截器链（对标 Koog AIAgentPipeline）。
 *
 * ## 架构模式（责任链 + 观察者混合）
 *
 * 每个 Feature 是一个独立的模块，可以：
 * - **拦截**请求（beforeExecute）：修改输入、添加上下文、提前返回
 * - **观察**结果（afterExecute）：记录指标、触发副作用、修改输出
 * - **响应**生命周期事件（onStart/onComplete/onError）
 *
 * ## 内置 Features（参考 Koog + Claude Code）
 *
 * | Feature | 功能 | 优先级 |
 * |---------|------|--------|
 * | TokenMonitor | Token 预算监控 | 100 |
 * | ContextCompressor | 上下文自动压缩 | 200 |
 * | FailClosedValidator | 参数安全校验 | 50 |
 * | TaskTracker | 任务追踪和分类 | 150 |
 * | MemoryWriter | 自动写入记忆目录 | 300 |
 * | BuddyMood | 伙伴情绪更新 | 400 |
 * | CostTracker | 成本追踪 | 250 |
 * | ApprovalCheck | 工具调用审批 | 60 |
 */

/**
 * Agent 执行上下文 — 在 Pipeline 中传递的共享状态。
 */
data class AgentContext(
    val sessionId: String,
    val userInput: String,
    var systemPrompt: String,
    var messages: MutableList<Any>,  // ChatMessage 列表（用 Any 避免循环依赖）
    val metadata: MutableMap<String, Any> = mutableMapOf(),
    var skipped: Boolean = false,      // 设为 true 则跳过实际执行
    var earlyResponse: String? = null, // 提前返回的响应
    var toolCallsAllowed: Boolean = true,
    var maxTokens: Int = 8192,
) {
    operator fun <T> get(key: String): T? = metadata[key] as? T
    operator fun <T> set(key: String, value: T) { metadata[key] = value!! }
}

/**
 * Feature 执行结果。
 */
enum class FeatureResult {
    /** 继续执行下一个 Feature / 正常执行 Agent */
    CONTINUE,
    /** 跳过剩余 Features，直接执行 Agent */
    SKIP_TO_AGENT,
    /** 跳过 Agent 执行，使用 earlyResponse */
    SKIP_AGENT,
    /** 中止整个流程 */
    ABORT,
}

/**
 * Feature 接口（对标 Koog AIAgentFeature）。
 *
 * 实现此接口来创建可插拔的功能模块。
 */
interface AgentFeature {
    /** Feature 名称（唯一标识） */
    val name: String

    /** 显示名称 */
    val displayName: String

    /** 描述 */
    val description: String

    /** 优先级（数字越小越先执行 before，越大越先执行 after） */
    val priority: Int

    /** 是否启用 */
    var enabled: Boolean

    /**
     * 在 Agent 执行前调用。
     *
     * 返回 CONTINUE 继续流水线，
     * 返回 SKIP_AGENT 跳过 Agent 执行（需设置 context.earlyResponse），
     * 返回 ABORT 中止。
     */
    suspend fun beforeExecute(context: AgentContext): FeatureResult

    /**
     * 在 Agent 执行后调用。
     *
     * 无论成功失败都会调用（除非 before 返回了 ABORT）。
     */
    suspend fun afterExecute(context: AgentContext, result: Any?, error: Throwable?): FeatureResult

    /**
     * Agent 开始时调用（会话级别）。
     */
    suspend fun onStart(context: AgentContext) {}

    /**
     * Agent 完成时调用（会话级别，无论成功失败）。
     */
    suspend fun onComplete(context: AgentContext) {}

    /**
     * 出错时调用。
     */
    suspend fun onError(context: AgentContext, error: Throwable) {}
}

/**
 * 抽象基类，简化 Feature 实现。
 */
abstract class BaseAgentFeature(
    override val name: String,
    override val displayName: String,
    override val description: String,
    override val priority: Int = 100,
) : AgentFeature {

    private var _enabled: Boolean = true
    override var enabled: Boolean
        get() = _enabled
        set(value) { _enabled = value }

    override suspend fun beforeExecute(context: AgentContext): FeatureResult = FeatureResult.CONTINUE
    override suspend fun afterExecute(context: AgentContext, result: Any?, error: Throwable?): FeatureResult = FeatureResult.CONTINUE
}

/**
 * Feature Pipeline — 管理和执行 Feature 链。
 */
class FeaturePipeline {
    private val features = sortedSetOf<AgentFeature>(compareByDescending { it.priority })
    private val mutex = Mutex()
    private var isExecuting = false

    companion object {
        /** 创建预配置的标准 Pipeline（预装核心 Feature 链） */
        fun standard(): FeaturePipeline {
            return FeaturePipeline()
        }
    }

    // ==================== 注册 API ====================

    /**
     * 安装一个 Feature。
     */
    suspend fun install(feature: AgentFeature) = mutex.withLock {
        features.add(feature)
        DebugLog.i("FeaturePipeline: installed [${feature.name}] (priority=${feature.priority})")
    }

    /**
     * 卸载一个 Feature。
     */
    suspend fun uninstall(name: String) = mutex.withLock {
        features.removeIf { it.name == name }
        DebugLog.i("FeaturePipeline: uninstalled [$name]")
    }

    /**
     * 启用/禁用 Feature。
     */
    fun setEnabled(name: String, enabled: Boolean) {
        features.find { it.name == name }?.enabled = enabled
    }

    /** 获取已安装的 Features */
    fun getFeatures(): List<AgentFeature> = features.toList()

    /** 获取启用的 Features */
    fun getEnabledFeatures(): List<AgentFeature> = features.filter { it.enabled }

    // ==================== 执行 API ====================

    /**
     * 执行完整的 Pipeline（before → agent → after）。
     *
     * @param context Agent 执行上下文
     * @param agentBlock 实际的 Agent 执行逻辑
     * @return Agent 执行结果（可能被 Feature 修改或替换）
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> execute(
        context: AgentContext,
        agentBlock: suspend (AgentContext) -> T,
    ): T = mutex.withLock {
        isExecuting = true
        try {
            // Phase 1: Before hooks（按优先级升序）
            for (feature in features.filter { it.enabled }.sortedBy { it.priority }) {
                val result = feature.beforeExecute(context)
                when (result) {
                    FeatureResult.ABORT -> {
                        DebugLog.w("FeaturePipeline: ABORT by [${feature.name}]")
                        throw PipelineAbortedException(feature.name)
                    }
                    FeatureResult.SKIP_AGENT -> {
                        DebugLog.i("FeaturePipeline: SKIP_AGENT by [${feature.name}]")
                        return@withLock context.earlyResponse as T
                            ?: throw IllegalStateException("Feature ${feature.name} set SKIP_AGENT but no earlyResponse")
                    }
                    FeatureResult.SKIP_TO_AGENT -> {
                        DebugLog.d("FeaturePipeline: SKIP_TO_AGENT by [${feature.name}]")
                        break  // 跳过剩余 before，直接执行 agent
                    }
                    FeatureResult.CONTINUE -> { /* continue */ }
                }
                if (context.skipped) break
            }

            // Phase 2: Execute Agent
            val result = try {
                agentBlock(context)
            } catch (e: Exception) {
                // Phase 2b: Error hooks
                for (feature in features.filter { it.enabled }.sortedByDescending { it.priority }) {
                    feature.onError(context, e)
                }
                throw e
            }

            // Phase 3: After hooks（按优先级降序）
            for (feature in features.filter { it.enabled }.sortedByDescending { it.priority }) {
                feature.afterExecute(context, result, null)
            }

            result
        } finally {
            isExecuting = false
        }
    }

    /**
     * 触发会话开始事件。
     */
    suspend fun notifyStart(context: AgentContext) {
        for (feature in features.filter { it.enabled }) {
            feature.onStart(context)
        }
    }

    /**
     * 触发会话结束事件。
     */
    suspend fun notifyComplete(context: AgentContext) {
        for (feature in features.filter { it.enabled }) {
            feature.onComplete(context)
        }
    }

    /** 是否正在执行 */
    fun isRunning(): Boolean = isExecuting

    /** Feature 数量 */
    fun size(): Int = features.size
}

/** Pipeline 中止异常 */
class PipelineAbortedException(val featureName: String) : RuntimeException("Pipeline aborted by feature: $featureName")

// ==================== 内置 Feature 实现 ====================

/**
 * 成本追踪 Feature — 追踪 Token 使用量和估算成本。
 */
class CostTrackerFeature : BaseAgentFeature(
    name = "cost_tracker",
    displayName = "成本追踪",
    description = "追踪每次交互的 Token 使用量和成本",
    priority = 250,
) {
    private var totalTokensUsed: Long = 0
    private var totalRequests: Int = 0
    private val sessionCosts = mutableListOf<SessionCost>()

    data class SessionCost(
        val sessionId: String,
        val inputTokens: Int,
        val outputTokens: Int,
        val estimatedCostUsd: Double,
        val timestamp: Long = System.currentTimeMillis(),
    )

    fun recordCost(sessionId: String, inputTokens: Int, outputTokens: Int) {
        // 简化定价：$2/1M input tokens, $6/1M output tokens
        val cost = inputTokens * 2e-6 + outputTokens * 6e-6
        totalTokensUsed += (inputTokens + outputTokens).toLong()
        totalRequests++
        sessionCosts.add(SessionCost(sessionId, inputTokens, outputTokens, cost))
    }

    fun getTotalTokens(): Long = totalTokensUsed
    fun getTotalCost(): Double = sessionCosts.sumOf { it.estimatedCostUsd }
    fun getRequestCount(): Int = totalRequests

    fun getRecentSessions(n: Int = 10): List<SessionCost> = sessionCosts.takeLast(n)

    override suspend fun afterExecute(context: AgentContext, result: Any?, error: Throwable?): FeatureResult {
        // 从 context.metadata 中读取 token 信息（由上层填充）
        val inputTokens = context["input_tokens"] as? Int ?: 0
        val outputTokens = context["output_tokens"] as? Int ?: 0
        if (inputTokens > 0 || outputTokens > 0) {
            recordCost(context.sessionId, inputTokens, outputTokens)
        }
        return FeatureResult.CONTINUE
    }
}
