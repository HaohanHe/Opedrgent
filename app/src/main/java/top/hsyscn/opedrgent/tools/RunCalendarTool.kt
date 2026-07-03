package top.hsyscn.opedrgent.tools

import android.content.Context
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.hsyscn.opedrgent.calendar.CalendarEventDraft
import top.hsyscn.opedrgent.calendar.CalendarEventInfo
import top.hsyscn.opedrgent.calendar.CalendarHelper
import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.model.ToolStateType
import top.hsyscn.opedrgent.network.ToolResult
import top.hsyscn.opedrgent.network.emptyResult
import top.hsyscn.opedrgent.utils.DebugLog
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * run_calendar 工具 — 让 LLM 通过 function calling 直接读写用户系统日历。
 *
 * 与 RunIntentTool 的 create_calendar_event（仅弹 Intent 编辑器）不同，
 * 本工具通过 ContentProvider 直接写入系统日历，无需用户二次确认。
 *
 * ## 支持的操作
 * - **create**: 创建日历事件（直接写入）
 * - **query_today**: 查询今天的事件
 * - **query_tomorrow**: 查询明天的事件
 * - **query_week**: 查询本周事件
 * - **update**: 修改指定事件（支持部分更新，未提供的字段保持不变）
 * - **delete**: 删除指定事件
 *
 * @param context Android Context
 */
class RunCalendarTool(
    private val context: Context,
    private val requestConfirmation: suspend (ToolConfirmation) -> Boolean = { true },
) : ToolSet {

    companion object {
        private const val DEFAULT_EVENT_DURATION_MS = 3600_000L
        private const val MIN_EVENT_DURATION_MS = 60_000L
        private const val ONE_WEEK_MS = 7 * 24 * 60 * 60 * 1000L
        private const val MAX_DESCRIPTION_PREVIEW_LENGTH = 50
        private val WRITE_ACTIONS = setOf("create", "update", "delete")
    }

    private fun buildConfirmationDetail(action: String, params: JSONObject): String {
        return when (action) {
            "create" -> "标题: ${params.optString("title", "")}\n时间: ${params.optString("start_time", "")} ~ ${params.optString("end_time", "")}"
            "update" -> "事件 ID: ${params.optString("event_id", "")}\n新标题: ${params.optString("title", "")}"
            "delete" -> "事件 ID: ${params.optString("event_id", "")}"
            else -> "操作: $action"
        }
    }

    override fun getTools(): Map<String, ToolBinding> = mapOf(
        "run_calendar" to ToolBinding(
            name = "run_calendar",
            description = "管理用户日历：创建/查询/修改/删除日历事件。当用户提到'安排''预约''提醒''日程''会议''计划''改时间''取消''推迟'等与时间相关的内容时调用此工具。",
            invoker = { toolPart, _, _, _ -> execute(toolPart) },
        ),
    )

    /**
     * 执行日历操作。
     * @param toolPart 包含 action/title/start_time 等参数的 ToolPart
     * @return 操作结果的文字描述（给 LLM 回应用户）
     */
    private suspend fun execute(toolPart: ToolPart): ToolResult {
        val input = toolPart.state.input
        DebugLog.i("RunCalendarTool: 执行日历操作 — input=${input.toString().take(200)}")

        return try {
            // input 可能是 String 或 Map<String, String>
            val paramsJson = JSONObject(input as Map<*, *>).toString()
            val params = JSONObject(paramsJson)
            val action = params.optString("action", "")

            if (action.isBlank()) {
                return emptyResult(toolPart, "缺少 action 参数，请指定要执行的操作类型 (create/query_today/query_tomorrow/query_week/delete)")
            }

            // 写操作需要用户确认
            if (action in WRITE_ACTIONS) {
                val confirmed = requestConfirmation(
                    ToolConfirmation(
                        toolName = "run_calendar",
                        action = "直接修改系统日历: $action",
                        detail = buildConfirmationDetail(action, params),
                    )
                )
                if (!confirmed) {
                    return emptyResult(toolPart, "用户拒绝了日历 $action 操作")
                }
            }

            // 权限检查
            if (!CalendarHelper.hasPermission(context)) {
                return emptyResult(
                    toolPart,
                    "缺少日历读写权限。请在系统设置中授予应用'日历'访问权限后重试。",
                )
            }

            val result = dispatchAction(action, params)

            ToolResult(
                toolPart = toolPart.copy(
                    state = toolPart.state.copy(
                        status = ToolStateType.COMPLETED,
                        output = result,
                        endTime = System.currentTimeMillis(),
                    ),
                ),
            )
        } catch (e: Exception) {
            DebugLog.e("RunCalendarTool 异常: ${e.message}", e)
            emptyResult(toolPart, "日历操作异常: ${e.message}")
        }
    }

    /**
     * 根据 action 类型分发到具体的日历操作。
     */
    private fun dispatchAction(action: String, params: JSONObject): String {
        return when (action) {
            "create" -> handleCreate(params)
            "query_today" -> handleQueryToday()
            "query_tomorrow" -> handleQueryTomorrow()
            "query_week" -> handleQueryWeek()
            "update" -> handleUpdate(params)
            "delete" -> handleDelete(params)
            else -> "[失败] 不支持的日历操作: $action。支持的操作: create, query_today, query_tomorrow, query_week, update, delete"
        }
    }

    // ==================== 各操作处理器 ====================

    /** 创建日历事件 */
    private fun handleCreate(params: JSONObject): String {
        val title = params.optString("title", "").trim()
        if (title.isBlank()) {
            return "[失败] 创建事件失败：缺少标题 (title)"
        }

        val startTimeStr = params.optString("start_time", "").trim()
        val endTimeStr = params.optString("end_time", "").trim()
        val description = params.optString("description", "").trim().takeIf { it.isNotBlank() }
        val location = params.optString("location", "").trim().takeIf { it.isNotBlank() }

        // 解析开始时间
        val startMs = parseTimeToEpoch(startTimeStr.ifBlank { "现在" })
        // 解析结束时间：如果未提供则默认 1 小时后
        val endMs = if (endTimeStr.isNotBlank()) {
            parseTimeToEpoch(endTimeStr, startMs)
        } else {
            startMs + DEFAULT_EVENT_DURATION_MS
        }

        val finalEndMs = maxOf(endMs, startMs + MIN_EVENT_DURATION_MS)

        val draft = CalendarEventDraft(
            title = title,
            startEpochMs = startMs,
            endEpochMs = finalEndMs,
            description = description,
            location = location,
        )

        val eventId = CalendarHelper.createEvent(context, draft)
        return if (eventId != null) {
            val timeLabel = formatTimeRange(startMs, finalEndMs)
            "[成功] 已创建日历事件「$title」(ID: $eventId)\n时间: $timeLabel${if (location != null) "\n地点: $location" else ""}${if (description != null) "\n备注: $description" else ""}"
        } else {
            "[失败] 创建日历事件失败，请检查是否有可用的日历账户"
        }
    }

    /** 查询今天的事件 */
    private fun handleQueryToday(): String {
        val events = CalendarHelper.queryTodayEvents(context)
        return formatEventList(events, "今天")
    }

    /** 查询明天的事件 */
    private fun handleQueryTomorrow(): String {
        val events = CalendarHelper.queryTomorrowEvents(context)
        return formatEventList(events, "明天")
    }

    /** 查询本周的事件 */
    private fun handleQueryWeek(): String {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val weekStart = cal.timeInMillis
        val weekEnd = weekStart + ONE_WEEK_MS

        val events = CalendarHelper.queryEvents(context, weekStart, weekEnd)
        return formatEventList(events, "本周")
    }

    /** 修改指定事件（部分更新，未提供的字段保持不变） */
    private fun handleUpdate(params: JSONObject): String {
        val eventIdStr = params.optString("event_id", "").trim()
        val eventId = eventIdStr.toLongOrNull()
        if (eventId == null || eventId <= 0) {
            return "[失败] 修改事件失败：缺少或无效的事件 ID (event_id)"
        }

        // 先查询现有事件，用于部分更新
        val existing = CalendarHelper.queryEventById(context, eventId)
            ?: return "[失败] 事件不存在 (ID: $eventId)"

        val title = params.optString("title", "").trim().ifBlank { existing.title }
        val startTimeStr = params.optString("start_time", "").trim()
        val endTimeStr = params.optString("end_time", "").trim()
        val description = params.optString("description", "").trim().ifBlank { existing.description }
        val location = params.optString("location", "").trim().ifBlank { existing.location }

        val startMs = if (startTimeStr.isNotBlank()) parseTimeToEpoch(startTimeStr) else existing.startMs
        val endMs = if (endTimeStr.isNotBlank()) {
            parseTimeToEpoch(endTimeStr, startMs)
        } else {
            existing.endMs
        }
        val finalEndMs = maxOf(endMs, startMs + MIN_EVENT_DURATION_MS)

        val draft = CalendarEventDraft(
            title = title,
            startEpochMs = startMs,
            endEpochMs = finalEndMs,
            description = description?.takeIf { it.isNotBlank() },
            location = location?.takeIf { it.isNotBlank() },
        )

        val success = CalendarHelper.updateEvent(context, eventId, draft)
        return if (success) {
            val timeLabel = formatTimeRange(startMs, finalEndMs)
            "[成功] 已修改事件「$title」(ID: $eventId)\n时间: $timeLabel${if (!location.isNullOrBlank()) "\n地点: $location" else ""}${if (!description.isNullOrBlank()) "\n备注: $description" else ""}"
        } else {
            "[失败] 修改事件失败"
        }
    }

    /** 删除指定事件 */
    private fun handleDelete(params: JSONObject): String {
        val eventIdStr = params.optString("event_id", "").trim()
        val eventId = eventIdStr.toLongOrNull()
        if (eventId == null || eventId <= 0) {
            return "[失败] 删除事件失败：缺少或无效的事件 ID (event_id)"
        }

        val success = CalendarHelper.deleteEvent(context, eventId)
        return if (success) {
            "[成功] 已删除事件 (ID: $eventId)"
        } else {
            "[失败] 删除事件失败，可能事件不存在或已被删除"
        }
    }

    // ==================== 格式化辅助方法 ====================

    /** 将事件列表格式化为人类可读文本 */
    private fun formatEventList(events: List<CalendarEventInfo>, periodLabel: String): String {
        if (events.isEmpty()) {
            return "$periodLabel 没有日程安排。"
        }

        val sb = StringBuilder("$periodLabel 共 ${events.size} 个日程:\n")
        events.forEachIndexed { index, event ->
            sb.append("${index + 1}. ${event.title}")
            sb.append("\n   时间: ${formatTimeRange(event.startMs, event.endMs)}")
            if (!event.location.isNullOrBlank()) {
                sb.append("\n   地点: ${event.location}")
            }
            if (!event.description.isNullOrBlank()) {
                sb.append("\n   备注: ${event.description.take(MAX_DESCRIPTION_PREVIEW_LENGTH)}${if (event.description.length > MAX_DESCRIPTION_PREVIEW_LENGTH) "..." else ""}")
            }
            if (!event.calendarName.isNullOrBlank()) {
                sb.append("\n   日历: ${event.calendarName}")
            }
            sb.append("\n   ID: ${event.id}\n")
        }
        return sb.toString().trimEnd()
    }

    /** 格式化时间范围 */
    private fun formatTimeRange(startMs: Long, endMs: Long): String {
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val startDate = Date(startMs)
        val endDate = Date(endMs)
        // 如果是同一天，只显示一次日期
        val startStr = fmt.format(startDate)
        val endFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val endStr = endFmt.format(endDate)
        return "$startStr ~ $endStr"
    }

    // ==================== 时间解析 ====================

    /**
     * 将自然语言时间字符串解析为 epoch milliseconds。
     * 支持绝对格式和相对关键词。
     * @param timeStr 时间描述字符串
     * @param defaultStart 基准时间（默认为当前时间）
     * @return 对应的 epoch ms
     */
    private fun parseTimeToEpoch(timeStr: String, defaultStart: Long = System.currentTimeMillis()): Long {
        val trimmed = timeStr.trim()

        // 绝对时间格式 yyyy-MM-dd HH:mm
        val absFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        try {
            absFormat.parse(trimmed)?.time?.let { return it }
        } catch (_: Exception) {}

        // 相对时间关键词
        val lower = trimmed.lowercase()
        val cal = Calendar.getInstance().apply { timeInMillis = defaultStart }

        when {
            "现在" in lower || "马上" in lower -> return defaultStart
            "一小时后" in lower || "1小时后" in lower -> {
                cal.add(Calendar.HOUR, 1)
                return cal.timeInMillis
            }
            "半小时后" in lower || "30分钟后" in lower -> {
                cal.add(Calendar.MINUTE, 30)
                return cal.timeInMillis
            }
            "明天" in lower -> {
                cal.add(Calendar.DAY_OF_MONTH, 1)
                return parseTimeOfDay(lower, cal)
            }
            "后天" in lower -> {
                cal.add(Calendar.DAY_OF_MONTH, 2)
                return parseTimeOfDay(lower, cal)
            }
            "下周" in lower -> {
                cal.add(Calendar.WEEK_OF_YEAR, 1)
                return parseTimeOfDay(lower, cal)
            }
            "下周一" in lower -> {
                cal.add(Calendar.WEEK_OF_YEAR, 1)
                cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                return parseTimeOfDay(lower, cal)
            }
            "下午" in lower && "点" !in lower && ":" !in lower -> {
                cal.set(Calendar.HOUR_OF_DAY, 15)
                cal.set(Calendar.MINUTE, 0)
                return parseTimeOfDay(lower, cal)
            }
            "上午" in lower && "点" !in lower && ":" !in lower -> {
                cal.set(Calendar.HOUR_OF_DAY, 10)
                cal.set(Calendar.MINUTE, 0)
                return parseTimeOfDay(lower, cal)
            }
        }

        // 最后尝试从文本中提取具体时间
        return parseTimeOfDay(trimmed, cal)
    }

    /** 从文本中提取小时:分钟并设置到 cal 上 */
    private fun parseTimeOfDay(text: String, baseCal: Calendar): Long {
        // 匹配 "3点" "15:00" "三点半" "3:30" 等
        val regex = Regex("""(\d{1,2})[:：点](\d{0,2})?""")
        regex.find(text)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return@let
            val minute = match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
            baseCal.set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            baseCal.set(Calendar.MINUTE, minute.coerceIn(0, 59))
            baseCal.set(Calendar.SECOND, 0)
            baseCal.set(Calendar.MILLISECOND, 0)
        }
        return baseCal.timeInMillis
    }
}
