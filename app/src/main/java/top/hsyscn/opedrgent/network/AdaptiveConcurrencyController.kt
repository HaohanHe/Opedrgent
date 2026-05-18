package top.hsyscn.opedrgent.network

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.suspendCancellableCoroutine
import top.hsyscn.opedrgent.utils.DebugLog
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
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
    private val globalSemaphore = Semaphore(config.globalMaxConcurrent)
    private val globalPermits = AtomicLong(config.globalMaxConcurrent.toLong())
    private val engineSemaphores = ConcurrentHashMap<String, Semaphore>()
    private val enginePermits = ConcurrentHashMap<String, AtomicLong>()
    private val priorityQueues = EnumMap<Priority, LinkedList<SuspendedRequest>>(Priority::class.java).apply {
        Priority.entries.forEach { put(it, LinkedList()) }
    }
    private val stats = ConcurrentHashMap<String, AtomicLong>().apply {
        put("processed", AtomicLong(0))
        put("rejected", AtomicLong(0))
        put("totalAttempts", AtomicLong(0))
        put("consecutiveFailures", AtomicLong(0))
    }

    @Volatile
    private var activeRequests = 0
    private val successTracker = ConcurrentLinkedDeque<Boolean>()
    @Volatile
    private var requestCountSinceAdjustment = 0

    init {
        DebugLog.d(TAG, "AdaptiveConcurrencyController initialized with config: $config")
    }

    suspend fun <T> withGlobalAccess(
        priority: Priority = Priority.NORMAL,
        block: suspend () -> T
    ): T? {
        val timeoutMs = when (priority) {
            Priority.HIGH -> config.highPriorityWaitTimeoutMs
            Priority.NORMAL -> config.normalWaitTimeoutMs
            Priority.LOW -> config.lowWaitTimeoutMs
            Priority.BACKGROUND -> config.backgroundWaitTimeoutMs
        }

        val acquired = acquireWithPriority(globalSemaphore, priority, timeoutMs, "global")
        if (!acquired) {
            stats["rejected"]?.incrementAndGet()
            DebugLog.w(TAG, "Global access rejected for priority=$priority, timeout=${timeoutMs}ms")
            return null
        }

        activeRequests++
        stats["totalAttempts"]?.incrementAndGet()

        return try {
            val result = block()
            recordRequest(true)
            result
        } catch (e: Exception) {
            recordRequest(false)
            DebugLog.e(TAG, "Global access execution error: ${e.message}", e)
            throw e
        } finally {
            activeRequests--
            globalSemaphore.release()
            globalPermits.incrementAndGet()
            requestCountSinceAdjustment++
            if (requestCountSinceAdjustment >= config.adjustmentInterval) {
                adjustEngineLimits()
                requestCountSinceAdjustment = 0
            }
        }
    }

    suspend fun <T> withEngineAccess(
        engineName: String,
        priority: Priority = Priority.NORMAL,
        block: suspend () -> T
    ): T? {
        val engineSemaphore = getEngineSemaphore(engineName)

        val globalResult = withGlobalAccess(priority) {
            val engineTimeoutMs = when (priority) {
                Priority.HIGH -> config.highPriorityWaitTimeoutMs
                Priority.NORMAL -> config.normalWaitTimeoutMs
                Priority.LOW -> config.lowWaitTimeoutMs
                Priority.BACKGROUND -> config.backgroundWaitTimeoutMs
            }

            val engineAcquired = acquireWithPriority(engineSemaphore, priority, engineTimeoutMs, engineName)
            if (!engineAcquired) {
                stats["rejected"]?.incrementAndGet()
                DebugLog.w(TAG, "Engine[$engineName] access rejected for priority=$priority")
                return@withGlobalAccess null as T?
            }

            try {
                val result = block()
                recordRequest(true)
                result
            } catch (e: Exception) {
                recordRequest(false)
                DebugLog.e(TAG, "Engine[$engineName] execution error: ${e.message}", e)
                throw e
            } finally {
                engineSemaphore.release()
            }
        }

        if (globalResult == null) {
            return null
        }

        @Suppress("UNCHECKED_CAST")
        return globalResult as T?
    }

    fun getEngineSemaphore(engineName: String): Semaphore {
        return engineSemaphores.getOrPut(engineName) {
            DebugLog.d(TAG, "Creating new semaphore for engine: $engineName")
            enginePermits[engineName] = AtomicLong(config.perEngineMaxConcurrent.toLong())
            Semaphore(config.perEngineMaxConcurrent)
        }
    }

    fun adjustEngineLimits() {
        val successRate = calculateSuccessRate()
        val currentActive = activeRequests
        val currentPerEngine = config.perEngineMaxConcurrent

        DebugLog.i(TAG, "adjustEngineLimits - successRate=$successRate, activeRequests=$currentActive, perEngineLimit=$currentPerEngine")

        when {
            successRate > 0.9 && currentActive >= (config.globalMaxConcurrent * 0.8) && shouldIncreaseLimits() -> {
                val newLimit = minOf(currentPerEngine + 1, config.maxPerEngineLimit)
                if (newLimit != currentPerEngine) {
                    updateAllEngineSemaphores(newLimit)
                    DebugLog.i(TAG, "Increased per-engine limit to $newLimit (success rate high)")
                }
            }
            successRate < 0.7 || getConsecutiveFailures() > 10 -> {
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
            "activeRequests" to activeRequests,
            "globalAvailablePermits" to globalPermits.get(),
            "totalProcessed" to (stats["processed"]?.get() ?: 0L) as Any,
            "totalRejected" to (stats["rejected"]?.get() ?: 0L) as Any,
            "successRate" to calculateSuccessRate(),
            "engineSemaphores" to enginePermits.mapValues { it.value.get() },
            "queueSizes" to priorityQueues.mapValues { it.value.size },
            "consecutiveFailures" to (stats["consecutiveFailures"]?.get() ?: 0L),
            "requestCountSinceAdjustment" to requestCountSinceAdjustment
        )
    }

    fun reset() {
        activeRequests = 0
        requestCountSinceAdjustment = 0
        successTracker.clear()
        priorityQueues.values.forEach { it.clear() }
        stats.values.forEach { it.set(0) }
        engineSemaphores.clear()
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
        return try {
            semaphore.acquire()
            DebugLog.d(TAG, "HIGH priority acquired by $requester")
            true
        } catch (e: Exception) {
            DebugLog.w(TAG, "HIGH priority acquisition failed for $requester: ${e.message}")
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

        val acquired = suspendCancellableCoroutine<Boolean> { continuation ->
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

            GlobalScope.launch {
                kotlinx.coroutines.delay(timeoutMs)
                if (continuation.isActive) {
                    priorityQueues[priority]?.removeIf { it.id == requestId }
                    continuation.resume(false)
                }
            }
        }

        if (acquired) {
            try {
                semaphore.acquire()
                return true
            } catch (e: Exception) {
                return false
            }
        }

        return false
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
        val currentLimit = enginePermits.values.firstOrNull()?.get()?.toInt()
            ?: config.perEngineMaxConcurrent
        return currentLimit < config.maxPerEngineLimit
    }

    private fun shouldDecreaseLimits(): Boolean {
        val currentLimit = enginePermits.values.firstOrNull()?.get()?.toInt()
            ?: config.perEngineMaxConcurrent
        return currentLimit > config.minPerEngineLimit
    }

    private fun getConsecutiveFailures(): Long {
        return stats["consecutiveFailures"]?.get() ?: 0L
    }

    private fun updateAllEngineSemaphores(newLimit: Int) {
        engineSemaphores.forEach { (name, oldSemaphore) ->
            val used = config.perEngineMaxConcurrent - (enginePermits[name]?.get()?.toInt() ?: config.perEngineMaxConcurrent)
            val newAvailable = maxOf(newLimit - used, 0)
            engineSemaphores[name] = Semaphore(newAvailable)
            enginePermits[name] = AtomicLong(newAvailable.toLong())
            DebugLog.d(TAG, "Updated engine[$name] semaphore permits to available=$newAvailable (limit=$newLimit)")
        }
    }

    companion object {
        private const val TAG = "AdaptiveConcurrencyController"
        private const val MAX_TRACKER_SIZE = 50
    }
}

private suspend fun Semaphore.tryAcquire(timeoutMs: Long): Boolean {
    return try {
        this.acquire()
        true
    } catch (e: Exception) {
        false
    }
}
