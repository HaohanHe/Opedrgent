package top.hsyscn.opedrgent.network

import java.util.concurrent.ConcurrentHashMap

/**
 * 基于滑动窗口的请求速率限制器（单例）
 *
 * 维护每个域名的请求时间戳列表，通过双窗口策略防止滥用：
 * - 短时突发窗口：20 秒内最多 3 次请求
 * - 长时总量窗口：10 分钟内最多 15 次请求
 *
 * 线程安全，适合多协程/线程并发调用。
 */
object RateLimiter {

    // ---------- 可配置参数 ----------

    /** 短时突发窗口时长（毫秒），默认 20_000 */
    @Volatile var burstWindowMs: Long = 20_000L

    /** 短时突发窗口最大请求数，默认 3 */
    @Volatile var burstMaxRequests: Int = 3

    /** 长时总量窗口时长（毫秒），默认 600_000（10 分钟） */
    @Volatile var longWindowMs: Long = 600_000L

    /** 长时总量窗口最大请求数，默认 15 */
    @Volatile var longMaxRequests: Int = 15

    /** 旧记录清理阈值（毫秒），默认 600_000（10 分钟，必须 >= longWindowMs 否则长窗口限流失效） */
    @Volatile var cleanupThresholdMs: Long = 600_000L

    // ---------- 内部存储 ----------

    /**
     * 域名 -> 该域名所有请求的时间戳列表（毫秒级 epoch）
     * 使用 ConcurrentHashMap 保证线程安全；各域名的 MutableList 通过 synchronized 块保护。
     */
    private val requestTimestamps: ConcurrentHashMap<String, MutableList<Long>> =
        ConcurrentHashMap()

    // ---------- 公开方法 ----------

    /**
     * 判断当前时刻是否允许向 [domain] 发起请求。
     *
     * @param domain 目标域名（或任意用于分组的 key）
     * @return `true` 表示允许请求（已自动记录本次时间戳）；`false` 表示被限流
     */
    fun allowRequest(domain: String): Boolean {
        val now = System.currentTimeMillis()

        // 获取或初始化该域名的时间戳列表
        val timestamps = requestTimestamps.getOrPut(domain) { mutableListOf() }

        return synchronized(timestamps) {
            // 1. 清理过旧记录（释放内存）
            cleanupStaleEntries(timestamps, now)

            // 2. 双窗口检查
            val burstCount = countInWindow(timestamps, now, burstWindowMs)
            if (burstCount >= burstMaxRequests) {
                return@synchronized false // 突发窗口超限
            }

            val longCount = countInWindow(timestamps, now, longWindowMs)
            if (longCount >= longMaxRequests) {
                return@synchronized false // 长时窗口超限
            }

            // 3. 记录本次请求
            timestamps.add(now)
            true
        }
    }

    /**
     * 清除指定域名的所有记录（测试或重置用）
     */
    fun reset(domain: String) {
        requestTimestamps.remove(domain)
    }

    /**
     * 清除所有域名的记录（测试或重置用）
     */
    fun resetAll() {
        requestTimestamps.clear()
    }

    // ---------- 内部辅助方法 ----------

    /**
     * 清理 [cleanupThresholdMs] 之前的旧条目，避免列表无限增长。
     */
    private fun cleanupStaleEntries(timestamps: MutableList<Long>, now: Long) {
        val cutoff = now - cleanupThresholdMs
        if (timestamps.isNotEmpty() && timestamps.first() < cutoff) {
            timestamps.removeAll { it < cutoff }
        }
    }

    /**
     * 统计 [windowMs] 时间窗口内的请求数量。
     *
     * @param timestamps 已排序的时间戳列表
     * @param now 当前时间戳
     * @param windowMs 窗口大小（毫秒）
     * @return 窗口内的请求数
     */
    private fun countInWindow(
        timestamps: List<Long>,
        now: Long,
        windowMs: Long
    ): Int {
        val windowStart = now - windowMs
        // 时间戳基本有序（按插入顺序），从后往前找更高效
        var count = 0
        for (i in timestamps.lastIndex downTo 0) {
            if (timestamps[i] >= windowStart) {
                count++
            } else {
                break
            }
        }
        return count
    }
}
