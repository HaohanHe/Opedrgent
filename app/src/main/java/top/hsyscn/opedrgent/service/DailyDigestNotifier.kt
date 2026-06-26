package top.hsyscn.opedrgent.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import top.hsyscn.opedrgent.storage.HippocampusIndex
import top.hsyscn.opedrgent.storage.NotificationHelper
import top.hsyscn.opedrgent.storage.SproutReportStore
import top.hsyscn.opedrgent.storage.SourceType
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 每日收获摘要推送 -- 汇总昨日的笔记、对话、发芽数据，通过通知展示。
 *
 * 纯本地聚合逻辑，不调用 LLM。通知文案使用"昨日收获"措辞，温暖自然。
 */
object DailyDigestNotifier {

    private const val WORK_NAME = "daily_digest_notifier"

    data class DigestData(
        val noteCount: Int,
        val topSnippet: String,
        val sproutCount: Int,
        val conversationCount: Int,
        val anniversarySnippet: String? = null,
        val anniversaryDaysAgo: Int? = null,
    )

    /**
     * 构建并推送每日摘要通知。
     *
     * @param context      上下文
     * @param index        海马体索引实例
     * @param reportStore  发芽报告存储实例
     */
    suspend fun buildAndSend(context: Context, index: HippocampusIndex, reportStore: SproutReportStore) {
        // 1. 计算昨天的时间范围（00:00 ~ 23:59:59）
        val yesterdayStart = getYesterdayStart()
        val yesterdayEnd = getTodayStart()

        // 2. 从海马体索引查询昨天的所有记录
        val allItems = index.getAll().filter { it.createdAt in yesterdayStart until yesterdayEnd }

        // 3. 按来源类型统计数量
        val noteCount = allItems.count {
            it.sourceType == SourceType.NOTE
        }
        val conversationCount = allItems.count {
            it.sourceType == SourceType.CONVERSATION
        }

        // 4. 取 summary 最长的一条作为 topSnippet（截取前 30 字）
        val topItem = allItems.maxByOrNull { it.summary.length }
        val topSnippet = topItem?.summary?.take(30) ?: ""

        // 5. 查询最近 24 小时内的发芽报告数
        val oneDayAgo = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        val sproutCount = reportStore.getAll().count { it.createdAt >= oneDayAgo }

        // 6. 周年回顾检查：查找 N 年前同月同日的记录（N=1,2,3）
        val anniversary = findAnniversary(index)

        // 7. 推送通知
        val digest = DigestData(
            noteCount, topSnippet, sproutCount, conversationCount,
            anniversarySnippet = anniversary?.second,
            anniversaryDaysAgo = anniversary?.first,
        )
        NotificationHelper.showDailyDigestNotification(
            context,
            digest.noteCount,
            digest.topSnippet,
            digest.sproutCount,
            digest.anniversarySnippet,
            digest.anniversaryDaysAgo,
        )
    }

    /**
     * 定时调度 -- 每天早上指定小时执行一次。
     *
     * 使用 PeriodicWorkRequest + WorkManager，最小间隔为 15 分钟（WorkManager 限制），
     * 实际触发时间可能在设定时间的 +/-15 分钟窗口内浮动。
     *
     * @param context 上下文
     * @param hour    触发小时（24 小时制），默认早上 8 点
     */
    fun schedule(context: Context, hour: Int = 8) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // 计算 delay 使首次执行接近目标时间
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            timeInMillis = now.timeInMillis
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(now)) add(Calendar.DAY_OF_MONTH, 1)
        }
        val initialDelay = target.timeInMillis - now.timeInMillis

        val request = PeriodicWorkRequestBuilder<DailyDigestWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /**
     * 取消定时调度。
     */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    // ==================== 内部辅助方法 ====================

    /** 获取今天 00:00:00 的 epoch milliseconds */
    private fun getTodayStart(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    /** 获取昨天 00:00:00 的 epoch milliseconds */
    private fun getYesterdayStart(): Long {
        return Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, -1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    /**
     * 查找 N 年前同月同日的记录（N=1,2,3）。
     * 返回 Pair(天数前, 原文摘要)? 或 null（未找到匹配记录）。
     * 容差设为 +/- 1 天，处理闰年 2 月 29 日和时区偏移问题。
     */
    private suspend fun findAnniversary(index: HippocampusIndex): Pair<Int, String>? {
        val cal = Calendar.getInstance()
        val todayMonth = cal.get(Calendar.MONTH)
        val todayDay = cal.get(Calendar.DAY_OF_MONTH)

        val allItems = index.getAll()

        for (yearsBack in listOf(1, 2, 3)) {
            // 找 N 年前同月同日 +/- 1 天容差
            val match = allItems.find { item ->
                val itemCal = Calendar.getInstance().apply { timeInMillis = item.createdAt }
                val monthMatch = itemCal.get(Calendar.MONTH) == todayMonth
                val dayAbsDiff = kotlin.math.abs(itemCal.get(Calendar.DAY_OF_MONTH) - todayDay)
                monthMatch && dayAbsDiff <= 1
            }

            if (match != null) {
                return Pair(yearsBack * 365, match.summary.take(40))
            }
        }
        return null
    }
}

/**
 * 每日摘要 Worker -- 由 WorkManager 在定时触发时执行。
 *
 * doWork 中重新构造 HippocampusIndex 和 SproutReportStore 实例（因为 Worker 运行在独立进程）。
 */
class DailyDigestWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val index = HippocampusIndex(applicationContext)
            val reportStore = SproutReportStore(applicationContext)
            DailyDigestNotifier.buildAndSend(applicationContext, index, reportStore)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
