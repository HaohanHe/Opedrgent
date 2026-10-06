package top.hsyscn.opedrgent.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import top.hsyscn.opedrgent.utils.DebugLog
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 引擎状态数据类（增强版）
 */
data class EngineStatus(
    val suspended: Boolean = false,
    val suspendUntil: Long = 0L,
    val consecutiveErrors: Int = 0,
    val lastError: String? = null,
    val lastSuccessTime: Long? = null,
    
    // 新增统计字段
    val totalRequests: Int = 0,
    val totalSuccesses: Int = 0,
    val totalFailures: Int = 0,
    val averageResponseTimeMs: Double = 0.0,
    val lastResponseTimeMs: Long = 0L,
    
    // 恢复相关
    val inRecoveryMode: Boolean = false,
    val recoveryAttempts: Int = 0,
    val maxRecoveryAttempts: Int = 3
)

/**
 * 错误类型枚举
 */
enum class ErrorType {
    TRANSIENT,      // 临时错误（超时、网络波动）
    RATE_LIMIT,     // 速率限制
    FORBIDDEN,      // 禁止访问（403）
    CAPTCHA,        // 验证码/挑战
    PERMANENT,      // 永久性错误（DNS失败、SSL错误）
    UNKNOWN         // 未知错误
}

/**
 * 引擎状态管理器（已废弃）
 * 
 * ⚠️ 此类已废弃，请使用 [SmartCircuitBreaker] 和 [CircuitBreakerManager] 替代。
 * 
 * 新版本提供以下增强功能：
 * - 5状态机 (CLOSED → OPEN → HALF_OPEN → RECOVERING → CLOSED)
 * - 健康检查探针 (HTTP HEAD 请求验证)
 * - 指数退避策略 (30s → 60s → 120s → max 30min)
 * - 滑动窗口成功率统计
 * - 更精细的错误分类 (通过 ErrorClassifier)
 * 
 * 迁移指南：
 * - isAvailable() → CircuitBreakerManager.getOrCreate(name).allowRequest()
 * - handleError() → CircuitBreakerManager.getOrCreate(name).recordFailure(exception)
 * - recordSuccess() → CircuitBreakerManager.getOrCreate(name).recordSuccess(responseTime)
 * - getStatus() → CircuitBreakerManager.getOrCreate(name).getStateInfo()
 * 
 * 计划移除版本: 2.0.0
 */
@Deprecated(
    message = "Use SmartCircuitBreaker and CircuitBreakerManager instead. " +
             "This class will be removed in version 2.0.0",
    level = DeprecationLevel.WARNING,
    replaceWith = ReplaceWith("CircuitBreakerManager")
)
object EngineStatusManager {

    private val statusMap = ConcurrentHashMap<String, EngineStatus>()
    
    // 统计计数器
    private val successCounters = ConcurrentHashMap<String, AtomicInteger>()
    private val failureCounters = ConcurrentHashMap<String, AtomicInteger>()
    private val responseTimeAccumulators = ConcurrentHashMap<String, AtomicLong>()
    
    // 健康检查协程
    @Volatile
    private var healthCheckJob: Job? = null
    
    /**
     * 初始化健康检查定时任务
     */
    @Deprecated(
        message = "Health check is now built into SmartCircuitBreaker. " +
                 "This method will be removed in version 2.0.0",
        level = DeprecationLevel.WARNING
    )
    fun startHealthCheck(scope: CoroutineScope) {
        // 死代码已切除：引擎暂停/恢复状态机由 SmartCircuitBreaker 承担，这里不再启动任何轮询。
        DebugLog.d("EngineStatusManager: startHealthCheck deprecated, no-op")
    }
    
    /**
     * 停止健康检查
     */
    @Deprecated(
        message = "Health check is now built into SmartCircuitBreaker. " +
                 "This method will be removed in version 2.0.0",
        level = DeprecationLevel.WARNING
    )
    fun stopHealthCheck() {
        healthCheckJob?.cancel()
        healthCheckJob = null
        DebugLog.i("EngineStatusManager: health check stopped")
    }
    
    @Deprecated(
        message = "Use CircuitBreakerManager.getOrCreate(engineName).allowRequest() instead",
        replaceWith = ReplaceWith("CircuitBreakerManager.getOrCreate(engineName).allowRequest()")
    )
    fun isAvailable(engineName: String): Boolean {
        return try {
            val breaker = CircuitBreakerManager.getOrCreate(engineName)
            breaker.allowRequest()
        } catch (e: Exception) {
            val status = statusMap[engineName] ?: return true
            if (!status.suspended) return true
            val now = System.currentTimeMillis()
            status.suspendUntil < now
        }
    }

    @Deprecated(
        message = "Use CircuitBreakerManager.getOrCreate(engineName).recordFailure(error) instead",
        replaceWith = ReplaceWith("CircuitBreakerManager.getOrCreate(engineName).recordFailure(error)")
    )
    fun handleError(engineName: String, error: Exception) {
        // 已废弃：调用方请直接使用 CircuitBreakerManager.getOrCreate(engineName).recordFailure(error)
    }

    @Deprecated(
        message = "Use CircuitBreakerManager.getOrCreate(engineName).recordSuccess(responseTimeMs) instead",
        replaceWith = ReplaceWith("CircuitBreakerManager.getOrCreate(engineName).recordSuccess(responseTimeMs)")
    )
    fun recordSuccess(engineName: String, responseTimeMs: Long = 0L) {
        try {
            CircuitBreakerManager.getOrCreate(engineName).recordSuccess(responseTimeMs)
        } catch (e: Exception) { /* ignore */ }

        val current = statusMap[engineName]
        
        // 更新成功计数
        successCounters.getOrPut(engineName) { AtomicInteger(0) }.incrementAndGet()
        
        // 更新响应时间统计
        if (responseTimeMs > 0) {
            val accumulator = responseTimeAccumulators.getOrPut(engineName) { AtomicLong(0) }
            accumulator.addAndGet(responseTimeMs)
        }
        
        // 如果在恢复模式中成功，完全恢复
        val newStatus = if (current?.inRecoveryMode == true) {
            DebugLog.i("EngineStatusManager: $engineName fully recovered from recovery mode")
            EngineStatus(
                suspended = false,
                suspendUntil = 0L,
                consecutiveErrors = 0,
                lastError = null,
                lastSuccessTime = System.currentTimeMillis(),
                totalRequests = current.totalRequests + 1,
                totalSuccesses = current.totalSuccesses + 1,
                averageResponseTimeMs = calculateAverageResponseTime(engineName),
                lastResponseTimeMs = responseTimeMs,
                inRecoveryMode = false,
                recoveryAttempts = 0
            )
        } else {
            EngineStatus(
                suspended = false,
                suspendUntil = 0L,
                consecutiveErrors = 0,
                lastError = null,
                lastSuccessTime = System.currentTimeMillis(),
                totalRequests = (current?.totalRequests ?: 0) + 1,
                totalSuccesses = (current?.totalSuccesses ?: 0) + 1,
                averageResponseTimeMs = calculateAverageResponseTime(engineName),
                lastResponseTimeMs = responseTimeMs
            )
        }
        
        statusMap[engineName] = newStatus
    }
    
    /**
     * 计算平均响应时间
     */
    private fun calculateAverageResponseTime(engineName: String): Double {
        val successes = successCounters[engineName]?.get() ?: return 0.0
        val totalTime = responseTimeAccumulators[engineName]?.get() ?: return 0.0
        
        return if (successes > 0) totalTime.toDouble() / successes else 0.0
    }

    @Deprecated(
        message = "Use CircuitBreakerManager.getOrCreate(engineName).getStateInfo() instead",
        replaceWith = ReplaceWith("CircuitBreakerManager.getOrCreate(engineName).getStateInfo()")
    )
    fun getStatus(engineName: String): EngineStatus {
        return statusMap[engineName] ?: EngineStatus()
    }
    
    /**
     * 获取所有引擎的状态摘要（用于调试）
     */
    @Deprecated(
        message = "Use CircuitBreakerManager.getAllStateInfo() instead for new system status. " +
                 "This method will be removed in version 2.0.0",
        level = DeprecationLevel.WARNING
    )
    fun getAllStatusSummary(): Map<String, Any> {
        val legacySummary = statusMap.mapValues { (_, status) ->
            mapOf(
                "available" to !status.suspended,
                "successRate" to if (status.totalRequests > 0) 
                    "%.1f".format(status.totalSuccesses.toDouble() / status.totalRequests * 100) + "%" 
                    else "N/A",
                "avgResponseMs" to "%.0f".format(status.averageResponseTimeMs),
                "lastError" to (status.lastError ?: "none"),
                "inRecovery" to status.inRecoveryMode
            )
        }
        
        try {
            val circuitBreakerInfo = CircuitBreakerManager.getAllStatus()
            return legacySummary + ("circuitBreaker" to circuitBreakerInfo)
        } catch (e: Exception) {
            return legacySummary
        }
    }
    
    /**
     * 手动重置引擎状态（用于测试或管理员操作）
     */
    @Deprecated(
        message = "Use CircuitBreakerManager.reset(engineName) instead for new system reset. " +
                 "This method will be removed in version 2.0.0",
        replaceWith = ReplaceWith("CircuitBreakerManager.reset(engineName)")
    )
    fun resetEngine(engineName: String) {
        try {
            CircuitBreakerManager.reset(engineName)
        } catch (e: Exception) { /* ignore */ }
        
        statusMap.remove(engineName)
        successCounters.remove(engineName)?.set(0)
        failureCounters.remove(engineName)?.set(0)
        responseTimeAccumulators.remove(engineName)?.set(0)
        DebugLog.i("EngineStatusManager: $engineName manually reset")
    }
}
