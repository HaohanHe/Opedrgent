package top.hsyscn.opedrgent.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import top.hsyscn.opedrgent.utils.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime

/**
 * Health Connect 运动健康数据读取工具。
 *
 * 读取步数、心率、睡眠、卡路里消耗等运动健康数据。
 * 需要用户在系统设置中安装 Health Connect 应用并授权。
 */
object HealthConnectHelper {

    /** 所需的 Health Connect 权限 */
    val PERMISSIONS = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
    )

    // ===== Prompt 摘要缓存：避免每轮 agent 循环都查 Health Connect =====
    private const val CACHE_TTL_MS = 5 * 60 * 1000L // 5 分钟
    private var cachedSummary: String? = null
    private var cacheTimeMs: Long = 0L

    private fun getCachedSummary(): String? {
        val now = System.currentTimeMillis()
        return if (now - cacheTimeMs < CACHE_TTL_MS) cachedSummary else null
    }

    private fun putCachedSummary(summary: String?) {
        cachedSummary = summary
        cacheTimeMs = System.currentTimeMillis()
    }

    /** 使缓存失效（权限变更/设置切换时调用） */
    fun invalidateCache() {
        cachedSummary = null
        cacheTimeMs = 0L
    }

    /**
     * 检查 Health Connect 是否可用。
     * @return [AvailabilityStatus]
     */
    fun getAvailability(context: Context): HealthConnectAvailability {
        val status = HealthConnectClient.getSdkStatus(context)
        return when (status) {
            HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.Available
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthConnectAvailability.NeedsUpdate
            else -> HealthConnectAvailability.NotSupported
        }
    }

    /**
     * 检查是否已授予全部所需的 Health Connect 权限。
     */
    suspend fun hasAllPermissions(context: Context): Boolean {
        if (getAvailability(context) != HealthConnectAvailability.Available) return false
        return try {
            HealthConnectClient.getOrCreate(context)
                .permissionController
                .getGrantedPermissions()
                .containsAll(PERMISSIONS)
        } catch (e: Exception) {
            DebugLog.e("HealthConnectHelper: check permissions failed: ${e.message}")
            false
        }
    }

    /**
     * 打开 Health Connect 应用设置页（用于授权或管理权限）。
     * 优先使用官方 ACTION_HEALTH_CONNECT_SETTINGS，回退到应用详情页。
     */
    fun openHealthConnectSettings(context: Context) {
        // 优先使用 Health Connect 官方设置 Intent（Android 14+ 框架版 / Android 13 APK 版均支持）
        val officialIntent = Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(officialIntent)
            return
        } catch (e: Exception) {
            DebugLog.w("HealthConnectHelper: official settings intent failed, trying app info: ${e.message}")
        }
        // 回退：打开 Health Connect 应用详情页
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:com.google.android.apps.healthdata")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            DebugLog.e("HealthConnectHelper: failed to open settings: ${e.message}")
        }
    }

    /**
     * 获取今日摘要数据（步数、心率、距离、卡路里）。
     * @return 格式化的摘要字符串，失败返回 null
     */
    suspend fun getTodaySummary(context: Context): String? = withContext(Dispatchers.IO) {
        val client = HealthConnectClient.getOrCreate(context)
        val now = ZonedDateTime.now()
        val startOfDay = now.toLocalDate().atStartOfDay(now.zone).toInstant()
        val endOfDay = now.toInstant()

        // 聚合查询今日数据
        val response = client.aggregate(
            AggregateRequest(
                metrics = setOf(
                    StepsRecord.COUNT_TOTAL,
                    HeartRateRecord.BPM_AVG,
                    HeartRateRecord.BPM_MIN,
                    HeartRateRecord.BPM_MAX,
                    TotalCaloriesBurnedRecord.ENERGY_TOTAL,
                    DistanceRecord.DISTANCE_TOTAL,
                ),
                timeRangeFilter = TimeRangeFilter.between(startOfDay, endOfDay),
            )
        )

        val steps = response[StepsRecord.COUNT_TOTAL] ?: 0L
        val avgHr = response[HeartRateRecord.BPM_AVG]
        val minHr = response[HeartRateRecord.BPM_MIN]
        val maxHr = response[HeartRateRecord.BPM_MAX]
        val calories = response[TotalCaloriesBurnedRecord.ENERGY_TOTAL]
        val distance = response[DistanceRecord.DISTANCE_TOTAL]

        buildString {
            append("今日运动数据:")
            append("\n- 步数: $steps")
            if (distance != null) {
                val km = distance.inKilometers
                append(String.format(java.util.Locale.US, "\n- 距离: %.2f km", km))
            }
            if (calories != null) {
                val kcal = calories.inKilocalories
                append(String.format(java.util.Locale.US, "\n- 消耗: %.0f 千卡", kcal))
            }
            if (avgHr != null) {
                append("\n- 心率: $avgHr bpm")
                if (minHr != null && maxHr != null) {
                    append(" ($minHr-$maxHr)")
                }
            }
        }.also {
            DebugLog.d("HealthConnectHelper: today summary = $it")
        }
    }

    /**
     * 获取最近 N 天的步数统计。
     * @return 格式化的步数列表字符串，失败返回 null
     */
    suspend fun getRecentSteps(context: Context, days: Int = 7): String? = withContext(Dispatchers.IO) {
        val client = HealthConnectClient.getOrCreate(context)
        val now = ZonedDateTime.now()
        val safeDays = days.coerceIn(1, 30)
        val startTime = now.toLocalDate().minusDays(safeDays.toLong()).atStartOfDay(now.zone).toInstant()
        val endTime = now.toInstant()

        val response = client.aggregate(
            AggregateRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(startTime, endTime),
            )
        )

        val totalSteps = response[StepsRecord.COUNT_TOTAL] ?: 0L
        val avgSteps = totalSteps / safeDays

        "最近${safeDays}天: 总步数 $totalSteps, 日均 $avgSteps 步"
    }

    /**
     * 获取最近的睡眠数据。
     * @return 格式化的睡眠摘要，失败返回 null
     */
    suspend fun getRecentSleep(context: Context): String? = withContext(Dispatchers.IO) {
        val client = HealthConnectClient.getOrCreate(context)
        val now = ZonedDateTime.now()
        // 查询过去 36 小时，取最长的睡眠段（昨晚睡眠）
        val startTime = now.toLocalDate().minusDays(1).atStartOfDay(now.zone).toInstant()
        val endTime = now.toInstant()

        val response = client.readRecords(
            ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startTime, endTime),
            )
        )

        if (response.records.isEmpty()) return@withContext "最近一天无睡眠记录"

        // 取时长最长的 session（通常是昨晚的主睡眠）
        val session = response.records.maxByOrNull {
            java.time.Duration.between(it.startTime, it.endTime).toMillis()
        } ?: response.records.first()

        val startTimeStr = session.startTime.atZone(now.zone).toLocalTime().toString().take(5)
        val endTimeStr = session.endTime.atZone(now.zone).toLocalTime().toString().take(5)
        val duration = java.time.Duration.between(session.startTime, session.endTime)
        val hours = duration.toHours()
        val minutes = duration.toMinutes() % 60

        "昨晚睡眠: ${startTimeStr}-${endTimeStr} (${hours}小时${minutes}分钟)"
    }

    /**
     * 获取综合健康摘要（用于注入 AI prompt）。
     * 包含今日步数、心率、最近睡眠等。
     * 结果缓存 5 分钟，避免每轮 agent 循环都查询 Health Connect。
     * 两个子查询并行执行，单个失败不影响另一个。
     */
    suspend fun getHealthSummaryForPrompt(context: Context): String? {
        getCachedSummary()?.let { return it }

        if (getAvailability(context) != HealthConnectAvailability.Available) return null

        val parts = mutableListOf<String>()

        // 并行查询今日摘要和睡眠，互不阻塞
        kotlinx.coroutines.coroutineScope {
            val todayDeferred = async(Dispatchers.IO) {
                runCatching { getTodaySummary(context) }.getOrNull()
            }
            val sleepDeferred = async(Dispatchers.IO) {
                runCatching { getRecentSleep(context) }.getOrNull()
            }

            todayDeferred.await()?.let { parts.add(it) }
            sleepDeferred.await()?.let { parts.add(it) }
        }

        val summary = if (parts.isNotEmpty()) {
            parts.joinToString("\n\n")
        } else {
            null
        }
        putCachedSummary(summary)
        return summary
    }
}

enum class HealthConnectAvailability {
    Available,
    NeedsUpdate,
    NotSupported,
}
