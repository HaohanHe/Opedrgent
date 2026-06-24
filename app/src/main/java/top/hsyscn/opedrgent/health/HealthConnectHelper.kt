package top.hsyscn.opedrgent.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import top.hsyscn.opedrgent.utils.DebugLog
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
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
    )

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
     * 打开 Health Connect 应用设置页（用于授权或安装）。
     */
    fun openHealthConnectSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:com.google.android.apps.healthdata")
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
    suspend fun getTodaySummary(context: Context): String? {
        val client = HealthConnectClient.getOrCreate(context) ?: return null
        return try {
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
                    append(String.format("\n- 距离: %.2f km", km))
                }
                if (calories != null) {
                    val kcal = calories.inKilocalories
                    append(String.format("\n- 消耗: %.0f 千卡", kcal))
                }
                if (avgHr != null) {
                    append("\n- 心率: ${avgHr.toLong()} bpm")
                    if (minHr != null && maxHr != null) {
                        append(" (${minHr.toLong()}-${maxHr.toLong()})")
                    }
                }
            }.also {
                DebugLog.d("HealthConnectHelper: today summary = $it")
            }
        } catch (e: Exception) {
            DebugLog.e("HealthConnectHelper: getTodaySummary failed: ${e.message}")
            null
        }
    }

    /**
     * 获取最近 N 天的步数统计。
     * @return 格式化的步数列表字符串，失败返回 null
     */
    suspend fun getRecentSteps(context: Context, days: Int = 7): String? {
        val client = HealthConnectClient.getOrCreate(context) ?: return null
        return try {
            val now = ZonedDateTime.now()
            val startTime = now.toLocalDate().minusDays(days.toLong()).atStartOfDay(now.zone).toInstant()
            val endTime = now.toInstant()

            val response = client.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(startTime, endTime),
                )
            )

            val totalSteps = response[StepsRecord.COUNT_TOTAL] ?: 0L
            val avgSteps = if (days > 0) totalSteps / days else 0L

            "最近${days}天: 总步数 $totalSteps, 日均 $avgSteps 步"
        } catch (e: Exception) {
            DebugLog.e("HealthConnectHelper: getRecentSteps failed: ${e.message}")
            null
        }
    }

    /**
     * 获取最近的睡眠数据。
     * @return 格式化的睡眠摘要，失败返回 null
     */
    suspend fun getRecentSleep(context: Context): String? {
        val client = HealthConnectClient.getOrCreate(context) ?: return null
        return try {
            val now = ZonedDateTime.now()
            val startTime = now.toLocalDate().minusDays(1).atStartOfDay(now.zone).toInstant()
            val endTime = now.toInstant()

            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startTime, endTime),
                )
            )

            if (response.records.isEmpty()) return "最近一天无睡眠记录"

            val session = response.records.first()
            val startTimeStr = session.startTime.atZone(now.zone).toLocalTime().toString().take(5)
            val endTimeStr = session.endTime.atZone(now.zone).toLocalTime().toString().take(5)
            val duration = java.time.Duration.between(session.startTime, session.endTime)
            val hours = duration.toHours()
            val minutes = duration.toMinutes() % 60

            "昨晚睡眠: ${startTimeStr}-${endTimeStr} (${hours}小时${minutes}分钟)"
        } catch (e: Exception) {
            DebugLog.e("HealthConnectHelper: getRecentSleep failed: ${e.message}")
            null
        }
    }

    /**
     * 获取综合健康摘要（用于注入 AI prompt）。
     * 包含今日步数、心率、最近睡眠等。
     */
    suspend fun getHealthSummaryForPrompt(context: Context): String? {
        if (getAvailability(context) != HealthConnectAvailability.Available) return null

        val parts = mutableListOf<String>()

        getTodaySummary(context)?.let { parts.add(it) }
        getRecentSleep(context)?.let { parts.add(it) }

        return if (parts.isNotEmpty()) {
            parts.joinToString("\n\n")
        } else {
            null
        }
    }
}

enum class HealthConnectAvailability {
    Available,
    NeedsUpdate,
    NotSupported,
}
