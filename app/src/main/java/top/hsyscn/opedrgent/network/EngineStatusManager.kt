package top.hsyscn.opedrgent.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
        if (healthCheckJob != null) return
        
        healthCheckJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    delay(60_000L) // 每分钟检查一次
                    performHealthChecks()
                } catch (e: Exception) {
                    DebugLog.w("EngineStatusManager health check error: ${e.message}")
                }
            }
        }
        
        DebugLog.i("EngineStatusManager: health check started")
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
    
    /**
     * 对所有暂停的引擎执行健康检查
     */
    private suspend fun performHealthChecks() {
        val now = System.currentTimeMillis()
        
        statusMap.entries
            .filter { it.value.suspended && it.value.suspendUntil < now }
            .forEach { (engineName, status) ->
                if (status.inRecoveryMode && status.recoveryAttempts >= status.maxRecoveryAttempts) {
                    // 超过最大重试次数，延长暂停时间
                    extendSuspension(engineName, status)
                } else {
                    // 尝试恢复
                    attemptRecovery(engineName)
                }
            }
    }
    
    /**
     * 延长暂停时间（指数退避）
     */
    private fun extendSuspension(engineName: String, currentStatus: EngineStatus) {
        val baseDelay = when (classifyError(currentStatus.lastError ?: "")) {
            ErrorType.CAPTCHA -> 300_000L   // 5分钟
            ErrorType.RATE_LIMIT -> 120_000L // 2分钟
            ErrorType.FORBIDDEN -> 180_000L   // 3分钟
            else -> 60_000L                   // 1分钟
        }
        
        // 指数退避：每次延长2倍，最大1小时
        val attempts = currentStatus.recoveryAttempts.coerceAtLeast(1)
        val extendedDelay = (baseDelay * Math.pow(2.0, attempts.toDouble())).toLong()
            .coerceAtMost(3_600_000L)
        
        val newSuspendUntil = System.currentTimeMillis() + extendedDelay
        
        statusMap[engineName] = currentStatus.copy(
            suspendUntil = newSuspendUntil,
            recoveryAttempts = 0,  // 重置重试计数
            inRecoveryMode = false
        )
        
        DebugLog.w("EngineStatusManager: $engineName suspension extended by ${extendedDelay / 1000}s")
    }
    
    /**
     * 尝试恢复暂停的引擎
     */
    private fun attemptRecovery(engineName: String) {
        val current = statusMap[engineName] ?: return
        
        statusMap[engineName] = current.copy(
            inRecoveryMode = true,
            recoveryAttempts = current.recoveryAttempts + 1
        )
        
        DebugLog.i("EngineStatusManager: attempting recovery #$${current.recoveryAttempts + 1} for $engineName")
        
        // 标记为可用的短暂时间窗口（30秒），让搜索引擎尝试调用
        statusMap[engineName] = (statusMap[engineName] ?: current).copy(
            suspended = false,
            suspendUntil = System.currentTimeMillis() + 30_000L
        )
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

    /**
     * 分类错误类型
     */
    private fun classifyError(errorMessage: String): ErrorType {
        return when {
            errorMessage.contains("CAPTCHA", ignoreCase = true) ||
                errorMessage.contains("captcha", ignoreCase = true) ||
                errorMessage.contains("challenge", ignoreCase = true) -> ErrorType.CAPTCHA
            
            errorMessage.contains("429") || 
                errorMessage.contains("Too Many Requests") ||
                errorMessage.contains("rate limit", ignoreCase = true) -> ErrorType.RATE_LIMIT
            
            errorMessage.contains("403") || 
                errorMessage.contains("Forbidden") -> ErrorType.FORBIDDEN
            
            errorMessage.contains("timeout", ignoreCase = true) ||
                errorMessage.contains("SocketTimeout") ||
                errorMessage.contains("ConnectTimeout") -> ErrorType.TRANSIENT
            
            errorMessage.contains("SSL") ||
                errorMessage.contains("certificate") ||
                errorMessage.contains("UnknownHost") -> ErrorType.PERMANENT
            
            else -> ErrorType.UNKNOWN
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
