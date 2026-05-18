package top.hsyscn.opedrgent.network

import kotlinx.coroutines.delay
import kotlin.random.Random
import top.hsyscn.opedrgent.utils.DebugLog
import java.util.concurrent.atomic.AtomicLong

/**
 * 人类行为时序模拟器
 *
 * 模拟真实用户的浏览行为模式：
 * 1. 阅读时间模拟 - 根据内容长度计算合理的"阅读"时间
 * 2. 思考时间 - 搜索后的短暂停顿
 * 3. 打字速度 - 模拟人类输入的节奏变化
 * 4. 随机微延迟 - 避免完美的机器行为特征
 */
object HumanBehaviorSimulator {
    
    // 统计计数器
    private val totalRequests = AtomicLong(0)
    private val totalDelayMs = AtomicLong(0)
    
    // 用户状态追踪
    private var lastActionTime: Long = System.currentTimeMillis()
    private var sessionStartTime: Long = System.currentTimeMillis()
    private var actionsInCurrentSession: Int = 0
    
    /**
     * 模拟搜索前的思考时间
     * 
     * 人类在输入搜索词后通常会有短暂的停顿，
     * 这个停顿时间因人而异，通常在200-800ms之间
     */
    suspend fun simulatePreSearchDelay() {
        val baseDelay = Random.nextLong(150, 600)  // 基础延迟
        
        // 根据会话时长调整（长时间使用后反应变慢）
        val sessionAgeMinutes = (System.currentTimeMillis() - sessionStartTime) / 60_000L
        val fatigueFactor = if (sessionAgeMinutes > 30) {
            1.0 + (sessionAgeMinutes - 30) / 100.0  // 每30分钟增加1%疲劳
        } else {
            1.0
        }
        
        // 根据连续操作次数调整（快速操作后可能需要休息）
        val rapidActionPenalty = when {
            actionsInCurrentSession > 20 -> 300L   // 连续操作太多次，增加休息
            actionsInCurrentSession > 10 -> 100L
            else -> 0L
        }
        
        val finalDelay = (baseDelay * fatigueFactor + rapidActionPenalty).toLong()
        
        DebugLog.d("HumanBehaviorSimulator: pre-search delay=${finalDelay}ms")
        
        if (finalDelay > 50) {
            delay(finalDelay)
            recordDelay(finalDelay)
        }
    }
    
    /**
     * 模拟结果页面阅读时间
     * 
     * 人类阅读速度约200-400词/分钟（中文）或250-500词/分钟（英文）
     * 模拟用户扫描搜索结果的行为
     */
    suspend fun simulateReadingTime(contentLength: Int, resultCount: Int = 5) {
        if (contentLength <= 0 && resultCount <= 0) return
        
        // 基础阅读时间估算
        val wordsPerMinute = Random.nextInt(200, 450)  // 阅读速度范围
        val estimatedReadSeconds = when {
            contentLength > 0 -> {
                // 中文字符：约2-3字符=1个词
                val estimatedWords = contentLength / 3.0
                (estimatedWords / wordsPerMinute * 60).toLong()
            }
            else -> {
                // 仅基于结果数量估算
                resultCount * 2L  // 每个结果约2秒扫描
            }
        }
        
        // 人类不会完整阅读所有内容，通常是扫描式阅读
        // 扫描系数：实际阅读时间约为完整阅读的30-70%
        val scanRatio = Random.nextDouble(0.25, 0.65)
        val actualDelay = (estimatedReadSeconds * scanRatio * 1000).toLong()
        
        // 加入随机性（有时用户会跳过、回看等）
        val randomVariation = Random.nextLong(-200, 500).coerceAtLeast(0)
        val finalDelay = (actualDelay + randomVariation).coerceIn(200, 5000)  // 限制范围
        
        DebugLog.d(
            "HumanBehaviorSimulator: reading time=${finalDelay}ms " +
            "(content=${contentLength}chars, results=$resultCount)"
        )
        
        if (finalDelay > 100) {
            delay(finalDelay)
            recordDelay(finalDelay)
        }
    }
    
    /**
     * 模拟点击间隔
     * 
     * 两次点击之间的间隔应该看起来自然
     */
    suspend fun simulateClickInterval(isFirstClick: Boolean = false) {
        val baseInterval = when {
            isFirstClick -> Random.nextLong(100, 400)      // 第一次点击较快
            actionsInCurrentSession % 5 == 0 -> {
                // 每5次操作可能有较长的停顿（思考）
                Random.nextLong(500, 1200)
            }
            else -> Random.nextLong(80, 350)              // 正常点击间隔
        }
        
        // 偶尔加入"犹豫"（模拟用户在选择）
        val hesitation = if (Random.nextDouble() < 0.15) {
            Random.nextLong(300, 900)
        } else {
            0L
        }
        
        val finalInterval = (baseInterval + hesitation).coerceIn(50, 1500)
        
        if (finalInterval > 50) {
            delay(finalInterval)
            recordDelay(finalInterval)
        }
    }
    
    /**
     * 模拟页面滚动/浏览行为
     * 
     * 用户在页面上滚动时的不规则停顿
     */
    suspend fun simulateScrollBehavior(scrollAmount: Int = 3) {
        for (i in 0 until scrollAmount.coerceAtMost(8)) {
            // 每次滚动的微小停顿
            val scrollPause = Random.nextLong(30, 150)
            delay(scrollPause)
            
            // 偶尔停下来"阅读"
            if (Random.nextDouble() < 0.35) {
                val readingPause = Random.nextLong(200, 700)
                delay(readingPause)
                recordDelay(readingPause)
            }
        }
    }
    
    /**
     * 获取智能延迟时间（通用方法）
     * 
     * 根据上下文返回合适的延迟时间
     */
    fun getSmartDelay(context: String): Long {
        return when (context.lowercase()) {
            "search_input" -> Random.nextLong(100, 500)
            "result_scan" -> Random.nextLong(300, 1000)
            "link_click" -> Random.nextLong(200, 600)
            "page_load_wait" -> Random.nextLong(500, 1500)
            "form_submit" -> Random.nextLong(150, 400)
            "navigation" -> Random.nextLong(200, 800)
            "idle_think" -> Random.nextLong(1000, 3000)
            else -> Random.nextLong(50, 300)
        }
    }
    
    /**
     * 记录延迟统计
     */
    private fun recordDelay(delayMs: Long) {
        totalRequests.incrementAndGet()
        totalDelayMs.addAndGet(delayMs)
        lastActionTime = System.currentTimeMillis()
        actionsInCurrentSession++
        
        // 重置会话计数器（如果距离上次操作超过5分钟）
        if (System.currentTimeMillis() - lastActionTime > 300_000) {
            actionsInCurrentSession = 0
            sessionStartTime = System.currentTimeMillis()
        }
    }
    
    /**
     * 获取行为统计信息
     */
    fun getStatistics(): Map<String, Any> {
        val totalReqs = totalRequests.get()
        val totalDelay = totalDelayMs.get()
        val sessionDurationMin = (System.currentTimeMillis() - sessionStartTime) / 60_000L
        
        return mapOf(
            "totalRequests" to totalReqs,
            "totalDelaySeconds" to "%.1f".format(totalDelay / 1000.0),
            "averageDelayMs" to if (totalReqs > 0) totalDelay / totalReqs else 0,
            "sessionDurationMin" to sessionDurationMin,
            "actionsThisSession" to actionsInCurrentSession
        )
    }
    
    /**
     * 重置状态
     */
    fun reset() {
        totalRequests.set(0)
        totalDelayMs.set(0)
        sessionStartTime = System.currentTimeMillis()
        lastActionTime = System.currentTimeMillis()
        actionsInCurrentSession = 0
    }
}

/**
 * 请求调度器 - 控制并发请求的时序
 */
object RequestScheduler {
    // 最小请求间隔（毫秒）
    private const val MIN_REQUEST_INTERVAL_MS = 500L
    
    // 最大并发请求数
    private const val MAX_CONCURRENT_REQUESTS = 3
    
    // 当前活跃请求数
    private val activeRequests = java.util.concurrent.atomic.AtomicInteger(0)
    
    // 上次请求时间
    @Volatile
    private var lastRequestTime: Long = 0L
    
    // 请求队列
    private val requestQueue = mutableListOf<Runnable>()
    private val queueLock = Any()
    
    /**
     * 等待合适的发送时机
     */
    suspend fun waitForSlot(): Boolean {
        // 等待并发槽位
        while (activeRequests.get() >= MAX_CONCURRENT_REQUESTS) {
            delay(Random.nextLong(50, 200))
        }
        
        // 确保最小请求间隔
        val now = System.currentTimeMillis()
        val timeSinceLastRequest = now - lastRequestTime
        if (timeSinceLastRequest < MIN_REQUEST_INTERVAL_MS) {
            val waitTime = MIN_REQUEST_INTERVAL_MS - timeSinceLastRequest + 
                Random.nextLong(0, 200)  // 加入随机性
            
            if (waitTime > 0) {
                delay(waitTime)
            }
        }
        
        activeRequests.incrementAndGet()
        lastRequestTime = System.currentTimeMillis()
        
        return true
    }
    
    /**
     * 释放请求槽位
     */
    fun releaseSlot() {
        activeRequests.decrementAndGet()
    }
    
    /**
     * 获取当前负载状态
     */
    fun getLoadStatus(): Map<String, Int> {
        return mapOf(
            "activeRequests" to activeRequests.get(),
            "maxConcurrent" to MAX_CONCURRENT_REQUESTS,
            "queueSize" to synchronized(queueLock) { requestQueue.size },
            "utilizationPercent" to ((activeRequests.get() * 100) / MAX_CONCURRENT_REQUESTS)
        )
    }
}
