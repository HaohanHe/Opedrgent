package top.hsyscn.opedrgent.network

import kotlinx.coroutines.CancellableContinuation
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
    val continuation: CancellableContinuation<Boolean>
)

class AdaptiveConcurrencyController(
    private val config: ConcurrencyConfig = ConcurrencyConfig()
) {
    private val engineSemaphores = ConcurrentHashMap<String, Semaphore>()
    private val engineMaxPermits = ConcurrentHashMap<String, AtomicInteger>()
    private val engineUsedPermits = ConcurrentHashMap<String, AtomicInteger>()
    private val priorityQueues = EnumMap<Priority, LinkedList<SuspendedRequest>>(Priority::class.java).apply {
        Priority.entries.forEach { put(it, LinkedList()) }
    }
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
        } catch (e: Exception) {
            recordRequest(false)
            DebugLog.e(TAG, "Engine[$engineName] execution error: ${e.message}", e)
            throw e
        } finally {
            activeRequests.decrementAndGet()
            engineUsed.decrementAndGet()
            engineSemaphore.release()
            val count = requestCountSinceAdjustment.incrementAndGet()
            if (count >= config.adjustmentInterval) {
                adjustEngineLimits()
                requestCountSinceAdjustment.set(0)
            }
        }
    }

    fun getEngineSemaphore(engineName: String): Semaphore {
        return engineSemaphores.getOrPut(engineName) {
            DebugLog.d(TAG, "Creating new semaphore for engine: $engineName")
            engineMaxPermits[engineName] = AtomicInteger(config.perEngineMaxConcurrent)
            engineUsedPermits[engineName] = AtomicInteger(0)
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
        priorityQueues.values.forEach { it.clear() }
        stats.values.forEach { it.set(0) }
        engineSemaphores.clear()
        engineMaxPermits.clear()
        engineUsedPermits.clear()
        DebugLog.w(TAG, "AdaptiveConcurrencyController reset")
    }

    private suspend fun acquireWithPriority(
        semaphore: Semaphore,
        priority: Priority,
        timeoutMs: Long,
        requester: String
    ): Boolean {
        return if (priority == Priority.HIGH) {
            tryAcquireWithTimeout(semaphore, timeoutMs, requester)
        } else {
            acquireWithQueue(semaphore, priority, timeoutMs, requester)
        }
    }

    private suspend fun tryAcquireWithTimeout(
        semaphore: Semaphore,
        timeoutMs: Long,
        requester: String
    ): Boolean {
        return withTimeoutOrNull(timeoutMs) {
            semaphore.acquire()
            DebugLog.d(TAG, "HIGH priority acquired by $requester")
            true
        } ?: run {
            DebugLog.w(TAG, "HIGH priority acquisition timed out for $requester after ${timeoutMs}ms")
            false
        }
    }

    private suspend fun acquireWithQueue(
        semaphore: Semaphore,
        priority: Priority,
        timeoutMs: Long,
        requester: String
    ): Boolean {
        val hasHigherPriorityWaiting = hasHigherPriorityInQueue(priority)

        if (hasHigherPriorityWaiting) {
            return enqueueAndWait(semaphore, priority, timeoutMs, requester)
        }

        return try {
            semaphore.acquire()
            true
        } catch (e: Exception) {
            DebugLog.w(TAG, "Acquisition failed for $requester (priority=$priority): ${e.message}")
            false
        }
    }

    private suspend fun enqueueAndWait(
        semaphore: Semaphore,
        priority: Priority,
        timeoutMs: Long,
        requester: String
    ): Boolean {
        val requestId = UUID.randomUUID().toString()
        val submittedAt = System.currentTimeMillis()

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                val request = SuspendedRequest(
                    id = requestId,
                    priority = priority,
                    requester = requester,
                    submittedAt = submittedAt,
                    continuation = continuation
                )

                priorityQueues[priority]?.add(request)

                continuation.invokeOnCancellation {
                    priorityQueues[priority]?.removeIf { it.id == requestId }
                }
            }
        }?.let { resumed ->
            if (resumed) {
                try {
                    semaphore.acquire()
                    true
                } catch (e: Exception) {
                    false
                }
            } else {
                false
            }
        } ?: false
    }

    private fun hasHigherPriorityInQueue(currentPriority: Priority): Boolean {
        return Priority.entries
            .filter { it.ordinal < currentPriority.ordinal }
            .any { priorityQueues[it]?.isNotEmpty() == true }
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
