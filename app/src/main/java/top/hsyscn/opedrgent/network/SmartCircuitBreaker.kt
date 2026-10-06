package top.hsyscn.opedrgent.network

import kotlinx.coroutines.CancellationException
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
    @Volatile
    var state: CircuitState = CircuitState.CLOSED
        private set

    // 以下计数器全部由 synchronized(this) 保护（与 allowRequest/recordSuccess/recordFailure 同一监视器），
    // 不再使用 @Volatile + 裸自增（read-modify-write 非原子，多协程并发会丢更新）。
    private var consecutiveFailures = 0
    private var openSince: Long = 0
    private var currentBackoffMs: Long = config.baseBackoffMs

    /** HALF_OPEN 中已准入但尚未完成（在途）的 probe 数量，用于限制并发探测上限。 */
    private var halfOpenProbeInFlight = 0

    /** 当前 HALF_OPEN 窗口内已成功的 probe 数量，达到 halfOpenMaxProbes 即转入 RECOVERING。 */
    private var halfOpenSuccessCount = 0

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
                        halfOpenProbeInFlight = 0
                        halfOpenSuccessCount = 0
                        DebugLog.i("CircuitBreaker[$engineName] OPEN → HALF_OPEN (backoff ${currentBackoffMs}ms elapsed)")
                        true
                    } else {
                        false
                    }
                }
                CircuitState.HALF_OPEN -> {
                    if (halfOpenProbeInFlight < config.halfOpenMaxProbes) {
                        // 准入即预约一个在途 probe 名额（在 recordSuccess/recordFailure 中释放），
                        // 避免 N 个并发请求同时通过 0<N 的检查而打满刚恢复的引擎。
                        halfOpenProbeInFlight++
                        true
                    } else {
                        DebugLog.w("CircuitBreaker[$engineName] HALF_OPEN 拒绝请求，in-flight probe 已达上限 $halfOpenProbeInFlight")
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
        synchronized(this) {
            consecutiveFailures = 0
            when (state) {
                CircuitState.HALF_OPEN -> {
                    halfOpenProbeInFlight = (halfOpenProbeInFlight - 1).coerceAtLeast(0)
                    halfOpenSuccessCount++
                    if (halfOpenSuccessCount >= config.halfOpenMaxProbes) {
                        state = CircuitState.RECOVERING
                        halfOpenProbeInFlight = 0
                        halfOpenSuccessCount = 0
                        DebugLog.i("CircuitBreaker[$engineName] HALF_OPEN → RECOVERING (probes=$halfOpenSuccessCount)")
                    }
                }
                CircuitState.RECOVERING -> {
                    state = CircuitState.CLOSED
                    currentBackoffMs = config.baseBackoffMs
                    halfOpenProbeInFlight = 0
                    halfOpenSuccessCount = 0
                    openSince = 0
                    DebugLog.i("CircuitBreaker[$engineName] RECOVERING → CLOSED (恢复完成)")
                }
                else -> {}
            }
        }
        DebugLog.d("CircuitBreaker[$engineName] recordSuccess state=${state} responseTime=${responseTimeMs}ms")
    }

    fun recordFailure(error: Exception? = null) {
        recentResults.addLast(false)
        trimWindow()
        synchronized(this) {
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
                    halfOpenProbeInFlight = 0
                    halfOpenSuccessCount = 0
                    DebugLog.w("CircuitBreaker[$engineName] HALF_OPEN → OPEN (试探失败, backoff 升至 ${currentBackoffMs}ms)")
                }
                CircuitState.RECOVERING -> {
                    openSince = System.currentTimeMillis()
                    state = CircuitState.OPEN
                    DebugLog.w("CircuitBreaker[$engineName] RECOVERING → OPEN (恢复期间再次失败)")
                }
                CircuitState.OPEN -> {}
            }
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
                if (success) {
                    synchronized(this) {
                        if (state == CircuitState.OPEN) {
                            state = CircuitState.HALF_OPEN
                            halfOpenProbeInFlight = 0
                            halfOpenSuccessCount = 0
                            DebugLog.i("CircuitBreaker[$engineName] 健康检查成功 OPEN → HALF_OPEN")
                        }
                    }
                }
                DebugLog.d("CircuitBreaker[$engineName] 健康检查结果: $success ($url)")
                success
            }
        } catch (e: CancellationException) {
            // 结构化并取取消必须向上传播，不得吞掉
            throw e
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
        val snapshot = synchronized(this) {
            intArrayOf(consecutiveFailures, halfOpenProbeInFlight, halfOpenSuccessCount) to
                longArrayOf(openSince, currentBackoffMs)
        }
        return mapOf(
            "engine" to engineName,
            "state" to state.name,
            "consecutiveFailures" to snapshot.first[0],
            "openSince" to snapshot.second[0],
            "currentBackoffMs" to snapshot.second[1],
            "halfOpenProbeCount" to snapshot.first[1],
            "halfOpenSuccessCount" to snapshot.first[2],
            "windowTotal" to totalSize,
            "windowSuccesses" to successCount,
            "windowFailures" to failureCount,
            "successRate" to String.format("%.2f%%", successRate * 100),
            "lastHealthCheckTime" to lastHealthCheckTime
        )
    }

    fun reset() {
        synchronized(this) {
            state = CircuitState.CLOSED
            consecutiveFailures = 0
            openSince = 0
            currentBackoffMs = config.baseBackoffMs
            halfOpenProbeInFlight = 0
            halfOpenSuccessCount = 0
            lastHealthCheckTime = 0
            recentResults.clear()
        }
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
