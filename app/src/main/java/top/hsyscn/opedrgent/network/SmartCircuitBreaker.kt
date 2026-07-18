package top.hsyscn.opedrgent.network

import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import top.hsyscn.opedrgent.utils.DebugLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

enum class CircuitState {
    CLOSED,
    OPEN,
    HALF_OPEN,
    RECOVERING
}

data class CircuitBreakerConfig(
    val failureThreshold: Int = 5,
    val baseBackoffMs: Long = 30_000L,
    val maxBackoffMs: Long = 1_800_000L,
    val backoffFactor: Double = 2.0,
    val healthCheckTimeoutMs: Long = 5_000L,
    val halfOpenMaxProbes: Int = 3
)

class SmartCircuitBreaker(
    private val engineName: String,
    private val config: CircuitBreakerConfig = CircuitBreakerConfig(),
    private val httpClient: OkHttpClient = HttpClients.default
) {
    @Volatile var state: CircuitState = CircuitState.CLOSED
        private set

    @Volatile private var consecutiveFailures = 0
    @Volatile private var openSince: Long = 0
    @Volatile private var currentBackoffMs: Long = config.baseBackoffMs
    @Volatile private var halfOpenProbeCount = 0
    @Volatile private var lastHealthCheckTime: Long = 0

    private val recentResults = ConcurrentLinkedDeque<Boolean>()

    fun allowRequest(): Boolean {
        return synchronized(this) {
            when (state) {
                CircuitState.CLOSED -> true
                CircuitState.OPEN -> {
                    val elapsed = System.currentTimeMillis() - openSince
                    if (elapsed >= currentBackoffMs) {
                        state = CircuitState.HALF_OPEN
                        halfOpenProbeCount = 0
                        DebugLog.i("CircuitBreaker[$engineName] OPEN → HALF_OPEN (backoff ${currentBackoffMs}ms elapsed)")
                        true
                    } else {
                        false
                    }
                }
                CircuitState.HALF_OPEN -> {
                    if (halfOpenProbeCount < config.halfOpenMaxProbes) {
                        true
                    } else {
                        DebugLog.w("CircuitBreaker[$engineName] HALF_OPEN 拒绝请求，probeCount 已达上限 $halfOpenProbeCount")
                        false
                    }
                }
                CircuitState.RECOVERING -> true
            }
        }
    }

    fun recordSuccess(responseTimeMs: Long = 0) {
        recentResults.addLast(true)
        trimWindow()
        consecutiveFailures = 0
        when (state) {
            CircuitState.HALF_OPEN -> {
                halfOpenProbeCount++
                if (halfOpenProbeCount >= config.halfOpenMaxProbes) {
                    state = CircuitState.RECOVERING
                    DebugLog.i("CircuitBreaker[$engineName] HALF_OPEN → RECOVERING (probes=$halfOpenProbeCount)")
                }
            }
            CircuitState.RECOVERING -> {
                state = CircuitState.CLOSED
                currentBackoffMs = config.baseBackoffMs
                halfOpenProbeCount = 0
                openSince = 0
                DebugLog.i("CircuitBreaker[$engineName] RECOVERING → CLOSED (恢复完成)")
            }
            else -> {}
        }
        DebugLog.d("CircuitBreaker[$engineName] recordSuccess state=${state} responseTime=${responseTimeMs}ms")
    }

    fun recordFailure(error: Exception? = null) {
        recentResults.addLast(false)
        trimWindow()
        when (state) {
            CircuitState.CLOSED -> {
                consecutiveFailures++
                if (consecutiveFailures >= config.failureThreshold) {
                    openSince = System.currentTimeMillis()
                    currentBackoffMs = config.baseBackoffMs
                    state = CircuitState.OPEN
                    DebugLog.w("CircuitBreaker[$engineName] CLOSED → OPEN (连续失败 $consecutiveFailures 次)")
                } else {
                    DebugLog.w("CircuitBreaker[$engineName] 连续失败 $consecutiveFailures/${config.failureThreshold}")
                }
            }
            CircuitState.HALF_OPEN -> {
                currentBackoffMs = minOf((currentBackoffMs * config.backoffFactor).toLong(), config.maxBackoffMs)
                openSince = System.currentTimeMillis()
                state = CircuitState.OPEN
                halfOpenProbeCount = 0
                DebugLog.w("CircuitBreaker[$engineName] HALF_OPEN → OPEN (试探失败, backoff 升至 ${currentBackoffMs}ms)")
            }
            CircuitState.RECOVERING -> {
                openSince = System.currentTimeMillis()
                state = CircuitState.OPEN
                DebugLog.w("CircuitBreaker[$engineName] RECOVERING → OPEN (恢复期间再次失败)")
            }
            CircuitState.OPEN -> {}
        }
        if (error != null) {
            DebugLog.e("CircuitBreaker[$engineName] recordFailure: ${error.message}", error)
        }
    }

    suspend fun performHealthCheck(): Boolean {
        val url = getHealthCheckUrl(engineName) ?: return true.also {
            DebugLog.d("CircuitBreaker[$engineName] 健康检查跳过（未知引擎）")
        }
        val now = System.currentTimeMillis()
        if (now - lastHealthCheckTime < config.healthCheckTimeoutMs) {
            DebugLog.d("CircuitBreaker[$engineName] 健康检查冷却中，跳过")
            return true
        }
        lastHealthCheckTime = now
        return try {
            withTimeout(config.healthCheckTimeoutMs) {
                val request = Request.Builder().url(url).head().build()
                val response = httpClient.newCall(request).execute()
                val success = response.isSuccessful
                response.close()
                if (success && state == CircuitState.OPEN) {
                    state = CircuitState.HALF_OPEN
                    halfOpenProbeCount = 0
                    DebugLog.i("CircuitBreaker[$engineName] 健康检查成功 OPEN → HALF_OPEN")
                }
                DebugLog.d("CircuitBreaker[$engineName] 健康检查结果: $success ($url)")
                success
            }
        } catch (e: Exception) {
            DebugLog.w("CircuitBreaker[$engineName] 健康检查异常: ${e.message}")
            false
        }
    }

    fun getStateInfo(): Map<String, Any> {
        val successCount = recentResults.count { it }
        val totalSize = recentResults.size
        val failureCount = totalSize - successCount
        val successRate = if (totalSize > 0) successCount.toDouble() / totalSize else 1.0
        return mapOf(
            "engine" to engineName,
            "state" to state.name,
            "consecutiveFailures" to consecutiveFailures,
            "openSince" to openSince,
            "currentBackoffMs" to currentBackoffMs,
            "halfOpenProbeCount" to halfOpenProbeCount,
            "windowTotal" to totalSize,
            "windowSuccesses" to successCount,
            "windowFailures" to failureCount,
            "successRate" to String.format("%.2f%%", successRate * 100),
            "lastHealthCheckTime" to lastHealthCheckTime
        )
    }

    fun reset() {
        state = CircuitState.CLOSED
        consecutiveFailures = 0
        openSince = 0
        currentBackoffMs = config.baseBackoffMs
        halfOpenProbeCount = 0
        lastHealthCheckTime = 0
        recentResults.clear()
        DebugLog.i("CircuitBreaker[$engineName] 已重置")
    }

    private fun trimWindow() {
        while (recentResults.size > MAX_WINDOW_SIZE) {
            recentResults.pollFirst()
        }
    }

    companion object {
        private const val MAX_WINDOW_SIZE = 100

        private fun getHealthCheckUrl(engine: String): String? = when (engine.lowercase()) {
            "ddg", "duckduckgo" -> "https://html.duckduckgo.com/"
            "bing" -> "https://www.bing.com/"
            "baidu" -> "https://www.baidu.com/"
            "searxng", "searx" -> SEARXNG_BASE_URL.ifBlank { null }
            else -> null
        }
    }
}

object CircuitBreakerManager {

    private val breakers = ConcurrentHashMap<String, SmartCircuitBreaker>()

    fun getOrCreate(engineName: String): SmartCircuitBreaker {
        return breakers.getOrPut(engineName) {
            SmartCircuitBreaker(engineName).also {
                DebugLog.i("CircuitBreakerManager 创建熔断器: $engineName")
            }
        }
    }

    fun getAllStatus(): Map<String, Map<String, Any>> {
        return breakers.mapValues { it.value.getStateInfo() }
    }

    fun resetAll() {
        breakers.values.forEach { it.reset() }
        DebugLog.i("CircuitBreakerManager 所有熔断器已重置")
    }

    fun reset(engineName: String) {
        breakers[engineName]?.reset()
    }
}
