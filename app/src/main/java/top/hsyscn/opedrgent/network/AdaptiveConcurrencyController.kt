package top.hsyscn.opedrgent.network

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.suspendCancellableCoroutine
import top.hsyscn.opedrgent.utils.DebugLog
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

enum class Priority {
    HIGH,
    NORMAL,
    LOW,
    BACKGROUND
}

data class ConcurrencyConfig(
    val globalMaxConcurrent: Int = 10,
    val perEngineMaxConcurrent: Int = 4,
    val highPriorityWaitTimeoutMs: Long = 5000L,
    val normalWaitTimeoutMs: Long = 10_000L,
    val lowWaitTimeoutMs: Long = 15_000L,
    val backgroundWaitTimeoutMs: Long = 30_000L,
    val adjustmentInterval: Int = 50,
    val minPerEngineLimit: Int = 2,
    val maxPerEngineLimit: Int = 8
)

data class SuspendedRequest(
    val id: String,
    val priority: Priority,
    val requester: String,
    val submittedAt: Long,
    val continuation: CancellableContinuation<Unit>
)

class AdaptiveConcurrencyController(
    private val config: ConcurrencyConfig = ConcurrencyConfig()
) {
    private val engineSemaphores = ConcurrentHashMap<String, Semaphore>()
    private val engineMaxPermits = ConcurrentHashMap<String, AtomicInteger>()
    private val engineUsedPermits = ConcurrentHashMap<String, AtomicInteger>()

    // 按优先级分层的等待队列。结构变更由 queueLock 保护；队列内条目一旦被 pump 取走即代表已获许可。
    private val priorityQueues = EnumMap<Priority, LinkedList<SuspendedRequest>>(Priority::class.java).apply {
        Priority.entries.forEach { put(it, LinkedList()) }
    }
    private val queueLock = Any()

    private val stats = ConcurrentHashMap<String, AtomicLong>().apply {
        put("processed", AtomicLong(0))
        put("rejected", AtomicLong(0))
        put("totalAttempts", AtomicLong(0))
        put("consecutiveFailures", AtomicLong(0))
    }

    private val activeRequests = AtomicInteger(0)
    private val successTracker = ConcurrentLinkedDeque<Boolean>()
    private val requestCountSinceAdjustment = AtomicInteger(0)

    init {
        DebugLog.d(TAG, "AdaptiveConcurrencyController initialized with config: $config")
    }

    suspend fun <T> withEngineAccess(
        engineName: String,
        priority: Priority = Priority.NORMAL,
        block: suspend () -> T
    ): T? {
        val engineSemaphore = getEngineSemaphore(engineName)
        val engineMax = engineMaxPermits[engineName]!!
        val engineUsed = engineUsedPermits[engineName]!!

        val timeoutMs = when (priority) {
            Priority.HIGH -> config.highPriorityWaitTimeoutMs
            Priority.NORMAL -> config.normalWaitTimeoutMs
            Priority.LOW -> config.lowWaitTimeoutMs
            Priority.BACKGROUND -> config.backgroundWaitTimeoutMs
        }

        val acquired = acquireWithPriority(engineSemaphore, priority, timeoutMs, engineName)
        if (!acquired) {
            stats["rejected"]?.incrementAndGet()
            DebugLog.w(TAG, "Engine[$engineName] access rejected for priority=$priority, timeout=${timeoutMs}ms")
            return null
        }

        if (engineUsed.incrementAndGet() > engineMax.get()) {
            engineUsed.decrementAndGet()
            engineSemaphore.release()
            pumpQueuedWaiters(engineSemaphore)
            stats["rejected"]?.incrementAndGet()
            DebugLog.w(TAG, "Engine[$engineName] access rejected due to dynamic limit=${engineMax.get()}")
            return null
        }

        activeRequests.incrementAndGet()
        stats["totalAttempts"]?.incrementAndGet()

        return try {
            val result = block()
            recordRequest(true)
            result
        } catch (e: CancellationException) {
            // 结构化并发取消向上传播，不吞
            recordRequest(false)
            throw e
        } catch (e: Exception) {
            recordRequest(false)
            DebugLog.e(TAG, "Engine[$engineName] execution error: ${e.message}", e)
            throw e
        } finally {
            activeRequests.decrementAndGet()
            engineUsed.decrementAndGet()
            engineSemaphore.release()
            // 许可归还后，按优先级唤醒等待者，避免队列中的高优先级请求饿死
            pumpQueuedWaiters(engineSemaphore)
            val count = requestCountSinceAdjustment.incrementAndGet()
            if (count >= config.adjustmentInterval) {
                adjustEngineLimits()
                requestCountSinceAdjustment.set(0)
            }
        }
    }

    fun getEngineSemaphore(engineName: String): Semaphore {
        // computeIfAbsent 在 ConcurrentHashMap 层面原子，避免 getOrPut 竞态产生孤儿 Semaphore
        return engineSemaphores.computeIfAbsent(engineName) {
            DebugLog.d(TAG, "Creating new semaphore for engine: $engineName")
            engineMaxPermits.putIfAbsent(engineName, AtomicInteger(config.perEngineMaxConcurrent))
            engineUsedPermits.putIfAbsent(engineName, AtomicInteger(0))
            // 创建一次后不再替换；实际并发上限通过 engineMaxPermits/engineUsedPermits 动态控制
            Semaphore(config.maxPerEngineLimit)
        }
    }

    fun adjustEngineLimits() {
        val successRate = calculateSuccessRate()
        val currentActive = activeRequests.get()
        val currentPerEngine = engineMaxPermits.values.firstOrNull()?.get()
            ?: config.perEngineMaxConcurrent

        DebugLog.i(TAG, "adjustEngineLimits - successRate=$successRate, activeRequests=$currentActive, perEngineLimit=$currentPerEngine")

        when {
            successRate > 0.9 && currentActive >= (config.globalMaxConcurrent * 0.8) && shouldIncreaseLimits() -> {
                val newLimit = minOf(currentPerEngine + 1, config.maxPerEngineLimit)
                if (newLimit != currentPerEngine) {
                    updateAllEngineSemaphores(newLimit)
                    DebugLog.i(TAG, "Increased per-engine limit to $newLimit (success rate high)")
                }
            }
            (successRate < 0.7 || getConsecutiveFailures() > 10) && shouldDecreaseLimits() -> {
                val newLimit = maxOf(currentPerEngine - 1, config.minPerEngineLimit)
                if (newLimit != currentPerEngine) {
                    updateAllEngineSemaphores(newLimit)
                    DebugLog.w(TAG, "Decreased per-engine limit to $newLimit (success rate low or consecutive failures)")
                    stats["consecutiveFailures"]?.set(0)
                }
            }
        }
    }

    fun getStats(): Map<String, Any> {
        return mapOf(
            "activeRequests" to activeRequests.get(),
            "totalProcessed" to (stats["processed"]?.get() ?: 0L) as Any,
            "totalRejected" to (stats["rejected"]?.get() ?: 0L) as Any,
            "successRate" to calculateSuccessRate(),
            "engineSemaphores" to engineMaxPermits.mapValues { it.value.get() },
            "engineUsed" to engineUsedPermits.mapValues { it.value.get() },
            "queueSizes" to priorityQueues.mapValues { it.value.size },
            "consecutiveFailures" to (stats["consecutiveFailures"]?.get() ?: 0L),
            "requestCountSinceAdjustment" to requestCountSinceAdjustment.get()
        )
    }

    fun reset() {
        activeRequests.set(0)
        requestCountSinceAdjustment.set(0)
        successTracker.clear()
        synchronized(queueLock) {
            priorityQueues.values.forEach { it.clear() }
        }
        stats.values.forEach { it.set(0) }
        engineSemaphores.clear()
        engineMaxPermits.clear()
        engineUsedPermits.clear()
        DebugLog.w(TAG, "AdaptiveConcurrencyController reset")
    }

    /**
     * 按优先级获取一个许可。
     *
     * 语义：
     * - 若当前没有任何排队等待者，直接 tryAcquire 一个空闲许可（快路径）。
     * - 一旦存在排队等待者，新到请求一律入队，不允许插队抢许可；
     *   许可释放时由 [pumpQueuedWaiters] 按 HIGH→BACKGROUND 顺序唤醒队首，
     *   从而让高优先级请求真正优先于已排队的低优先级请求。
     *
     * 返回 true 表示已持有一个许可（调用方负责 release）；false 表示超时未获得。
     */
    private suspend fun acquireWithPriority(
        semaphore: Semaphore,
        priority: Priority,
        timeoutMs: Long,
        requester: String
    ): Boolean {
        val requestId = UUID.randomUUID().toString()
        val submittedAt = System.currentTimeMillis()

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val request = SuspendedRequest(requestId, priority, requester, submittedAt, continuation)
                val fastAcquired = synchronized(queueLock) {
                    if (!hasAnyQueuedWaiter() && semaphore.tryAcquire()) {
                        true
                    } else {
                        priorityQueues[priority]?.add(request)
                        false
                    }
                }
                if (fastAcquired) {
                    // 快路径已拿到许可，直接恢复
                    continuation.resume(Unit)
                } else {
                    continuation.invokeOnCancellation {
                        // 超时或外部取消：从队列移除；入队期间未占用许可，无需 release
                        synchronized(queueLock) {
                            priorityQueues[priority]?.removeIf { it.id == requestId }
                        }
                    }
                }
            }
        } != null
    }

    /**
     * 许可归还后调用：从高优先级到低优先级依次取队首，tryAcquire 一个刚归还的许可并恢复其协程。
     * 没有空闲许可时立即停止（tryAcquire 返回 false），避免无意义自旋。
     */
    private fun pumpQueuedWaiters(semaphore: Semaphore) {
        synchronized(queueLock) {
            for (p in Priority.entries) {
                val q = priorityQueues[p] ?: continue
                while (q.isNotEmpty()) {
                    val head = q.peek() ?: break
                    if (semaphore.tryAcquire()) {
                        q.poll()
                        head.continuation.resume(Unit)
                    } else {
                        return
                    }
                }
            }
        }
    }

    private fun hasAnyQueuedWaiter(): Boolean {
        return Priority.entries.any { priorityQueues[it]?.isNotEmpty() == true }
    }

    private fun recordRequest(success: Boolean) {
        successTracker.addLast(success)
        if (successTracker.size > MAX_TRACKER_SIZE) {
            successTracker.removeFirst()
        }

        stats["processed"]?.incrementAndGet()

        if (success) {
            stats["consecutiveFailures"]?.set(0)
        } else {
            stats["consecutiveFailures"]?.incrementAndGet()
        }
    }

    private fun calculateSuccessRate(): Double {
        if (successTracker.isEmpty()) return 1.0
        val successes = successTracker.count { it }.toDouble()
        return successes / successTracker.size
    }

    private fun shouldIncreaseLimits(): Boolean {
        val currentLimit = engineMaxPermits.values.firstOrNull()?.get()
            ?: config.perEngineMaxConcurrent
        return currentLimit < config.maxPerEngineLimit
    }

    private fun shouldDecreaseLimits(): Boolean {
        val currentLimit = engineMaxPermits.values.firstOrNull()?.get()
            ?: config.perEngineMaxConcurrent
        return currentLimit > config.minPerEngineLimit
    }

    private fun getConsecutiveFailures(): Long {
        return stats["consecutiveFailures"]?.get() ?: 0L
    }

    private fun updateAllEngineSemaphores(newLimit: Int) {
        engineMaxPermits.forEach { (name, max) ->
            max.set(newLimit)
            DebugLog.d(TAG, "Updated engine[$name] dynamic limit to $newLimit")
        }
    }

    companion object {
        private const val TAG = "AdaptiveConcurrencyController"
        private const val MAX_TRACKER_SIZE = 50
    }
}
