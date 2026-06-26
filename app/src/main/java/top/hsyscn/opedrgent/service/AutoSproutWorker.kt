package top.hsyscn.opedrgent.service

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.note.Note
import top.hsyscn.opedrgent.note.SproutArticle
import top.hsyscn.opedrgent.note.NoteDatabase
import top.hsyscn.opedrgent.note.SproutService
import top.hsyscn.opedrgent.settings.ApiSettings
import top.hsyscn.opedrgent.storage.SproutReportRecord
import top.hsyscn.opedrgent.storage.SproutReportStore
import top.hsyscn.opedrgent.utils.DebugLog
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 自动发芽定时任务 -- WorkManager 周期性 Worker。
 *
 * 每天在指定时间（默认 23:00）自动为当天新笔记执行 AI 发芽分析。
 * 遵循两阶段时序设计：
 * - 夜间静默阶段：本 Worker 只负责发芽 + 存库，不推送通知
 * - 次日晨间：由 DailyDigestWorker 负责汇总推送通知
 *
 * ## 依赖获取方式
 * Worker 无法使用 Hilt/Dagger 注入，通过构造函数直接创建依赖实例：
 * - ApiSettings(context) -- 读取 API 配置
 * - SproutService(apiSettings) -- 发芽引擎（无海马体上下文）
 * - SproutReportStore(context) -- 报告持久化
 */
class AutoSproutWorker(
    context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "AutoSproutWorker"
        const val WORK_NAME = "auto_sprout_worker"
        const val DEFAULT_HOUR = 23 // 默认晚上 11 点执行

        /**
         * 注册周期任务（在设置开关打开时调用）。
         *
         * @param context   应用上下文
         * @param hour      每天执行的小时（0-23），默认 23
         * @param maxCount  每次最多处理几篇笔记，默认 3
         */
        fun schedule(context: Context, hour: Int = DEFAULT_HOUR, maxCount: Int = 3) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED) // 需要网络调 LLM
                .setRequiresBatteryNotLow(true)              // 电量不低时执行
                .build()

            val workRequest = PeriodicWorkRequestBuilder<AutoSproutWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInputData(workDataOf("max_count" to maxCount))
                .setInitialDelay(calculateInitialDelay(hour), TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest,
            )
        }

        /** 取消周期任务（在设置开关关闭时调用） */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        /**
         * 计算到今天 [targetHour] 的毫秒延迟。
         * 如果当前时间已过今天的 targetHour，则延迟到明天。
         */
        private fun calculateInitialDelay(targetHour: Int): Long {
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, targetHour)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (target.before(now)) {
                target.add(Calendar.DAY_OF_MONTH, 1)
            }
            return target.timeInMillis - now.timeInMillis
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            DebugLog.i(TAG, "自动发芽任务开始执行")

            // 1. 获取配置参数
            val maxCount = inputData.getInt("max_count", 3)

            // 2. 初始化依赖（Worker 环境无法注入，直接构造）
            val appContext = applicationContext
            val apiSettings = ApiSettings(appContext)
            val apiKey = apiSettings.getApiKey()
            if (apiKey.isNullOrBlank()) {
                DebugLog.w(TAG, "API Key 未配置，跳过自动发芽")
                return@withContext Result.success()
            }
            val sproutService = SproutService(apiSettings)
            val reportStore = SproutReportStore(appContext)

            // 3. 获取当天新笔记（未删除、未发芽）
            val todayStart = getTodayStartTimestamp()
            val newNotes = getUnsproutedNotes(todayStart, maxCount, reportStore)

            if (newNotes.isEmpty()) {
                DebugLog.i(TAG, "没有需要发芽的新笔记")
                return@withContext Result.success()
            }

            DebugLog.i(TAG, "发现 ${newNotes.size} 篇新笔记待发芽")

            // 4. 批量发芽
            val results = sproutService.sproutBatch(newNotes)

            // 5. 处理结果：存库（夜间静默，不推送通知）
            var successCount = 0
            var failCount = 0
            results.forEachIndexed { index, result ->
                val note = newNotes[index]
                result.fold(
                    onSuccess = { article ->
                        val markdownReport = article.toMarkdown()
                        reportStore.insert(
                            SproutReportRecord(
                                sourceNoteId = note.id,
                                sourceTitle = note.title.ifBlank { note.content.take(100) },
                                markdownReport = markdownReport,
                                summary = article.summary,
                                modelUsed = article.modelUsed,
                                createdAt = System.currentTimeMillis(),
                                wordCount = markdownReport.length,
                            )
                        )
                        note.setSproutArticle(article)
                        val cv = ContentValues().apply {
                            put(NoteDatabase.COL_SPROUT_REPORT_JSON, note.sproutReportJson)
                            put(NoteDatabase.COL_UPDATED_AT, System.currentTimeMillis())
                        }
                        NoteDatabase.getInstance(appContext).writableDatabase.update(
                            NoteDatabase.TABLE_NOTES, cv,
                            "${NoteDatabase.COL_ID} = ?", arrayOf(note.id.toString()),
                        )
                        successCount++
                        DebugLog.i(TAG, "笔记 #${note.id} 发芽成功: ${article.summary.take(40)}...")
                    },
                    onFailure = { e ->
                        failCount++
                        DebugLog.e(TAG, "笔记 #${note.id} 发芽失败: ${e.message}", e)
                    },
                )
            }

            DebugLog.i(TAG, "自动发芽完成: 成功 $successCount, 失败 $failCount")

            // 6. 全部失败且有网络问题 -> 重试；部分成功或全部成功 -> 完成
            return@withContext when {
                successCount > 0 -> Result.success()           // 至少有成果，不算失败
                failCount > 0 && isRecoverable(failCount) -> Result.retry() // 可恢复错误，稍后重试
                else -> Result.failure()                       // 不可恢复的错误
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "自动发芽任务异常: ${e.message}", e)
            // 网络相关异常可重试
            return@withContext if (isNetworkError(e)) Result.retry() else Result.failure()
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 获取当天 00:00:00.000 的时间戳（毫秒）。
     */
    private fun getTodayStartTimestamp(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    /**
     * 获取当天新创建且尚未发芽的笔记列表。
     *
     * 筛选条件：
     * - createdAt >= [todayStart]（当天新建）
     * - isDeleted == 0（未软删除）
     * - source_note_id 不在 SproutReportStore 中（未发芽过）
     *
     * @param todayStart  今天零点时间戳
     * @param maxCount    最大返回数量
     * @param reportStore 用于排除已发芽的笔记
     */
    private suspend fun getUnsproutedNotes(
        todayStart: Long,
        maxCount: Int,
        reportStore: SproutReportStore,
    ): List<Note> {
        return try {
            val db = NoteDatabase.getInstance(applicationContext).readableDatabase

            // 查询当天未删除的笔记，按创建时间倒序取 maxCount 条
            val cursor = db.query(
                NoteDatabase.TABLE_NOTES,
                null,
                "${NoteDatabase.COL_IS_DELETED} = 0 AND ${NoteDatabase.COL_CREATED_AT} >= ?",
                arrayOf(todayStart.toString()),
                null, null,
                "${NoteDatabase.COL_CREATED_AT} DESC",
                maxCount.toString(),
            )

            val allCandidates = mutableListOf<Note>()
            cursor.use { c ->
                while (c.moveToNext()) {
                    allCandidates.add(NoteDatabase.getInstance(applicationContext).cursorToNote(c))
                }
            }

            // 过滤掉已有发芽报告的笔记（两阶段设计：避免重复发芽）
            allCandidates.filter { note ->
                val existingReports = reportStore.getByNoteId(note.id, limit = 1)
                existingReports.isEmpty()
            }
        } catch (e: SQLiteException) {
            DebugLog.e(TAG, "查询新笔记失败: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * 判断失败数是否属于可重试范围。
     * 如果所有笔记都失败了，可能是临时性网络/服务问题，值得重试。
     */
    private fun isRecoverable(failCount: Int): Boolean = true

    /**
     * 判断异常是否为网络相关可恢复错误。
     */
    private fun isNetworkError(e: Exception): Boolean {
        val msg = e.message ?: ""
        return msg.contains("timeout", ignoreCase = true) ||
                msg.contains("network", ignoreCase = true) ||
                msg.contains("socket", ignoreCase = true) ||
                msg.contains("connection", ignoreCase = true) ||
                msg.contains("UnknownHost", ignoreCase = true) ||
                e is java.net.SocketTimeoutException ||
                e is java.net.UnknownHostException ||
                e is javax.net.ssl.SSLException
    }
}

/**
 * 将 SproutArticle 序列化为 Markdown 文本，用于存储到 SproutReportStore。
 */
private fun SproutArticle.toMarkdown(): String = buildString {
    appendLine("# $summary")
    appendLine()
    articles.forEachIndexed { index, article ->
        appendLine("## ${article.title}")
        appendLine()
        appendLine("> ${article.seed}")
        appendLine()
        appendLine(article.body)
        appendLine()
        appendLine("**${article.shockingMoment}**")
        appendLine()
    }
    if (actionItems.isNotEmpty()) {
        appendLine("### 行动建议")
        actionItems.forEach { appendLine("- $it") }
        appendLine()
    }
    if (relatedConcepts.isNotEmpty()) {
        appendLine("### 相关概念")
        relatedConcepts.forEach { appendLine("- $it") }
    }
}
