package top.hsyscn.opedrgent.calendar

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.core.content.ContextCompat
import java.util.Calendar
import java.util.TimeZone

/**
 * 系统日历直接读写工具。
 *
 * 通过 ContentProvider 直接操作 Android 系统日历，无需弹出 Intent 编辑器。
 * 支持创建/更新/删除事件、查询指定时间范围的事件。
 */
object CalendarHelper {

    /** 日历读写权限组合 */
    private val CALENDAR_PERMISSIONS = arrayOf(
        android.Manifest.permission.READ_CALENDAR,
        android.Manifest.permission.WRITE_CALENDAR,
    )

    /**
     * 检查是否有日历读写权限。
     * @return true 表示两个权限都已授予
     */
    fun hasPermission(context: Context): Boolean {
        return CALENDAR_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 获取用户所有日历账户列表。
     * @return List<Pair<calendarId, displayName>>
     */
    fun getCalendars(context: Context): List<Pair<Long, String>> {
        if (!hasPermission(context)) return emptyList()

        val result = mutableListOf<Pair<Long, String>>()
        val uri = Calendars.CONTENT_URI
        // 仅查询本地可写入的日历，避免同步日历权限问题
        val projection = arrayOf(
            Calendars._ID,
            Calendars.CALENDAR_DISPLAY_NAME,
            Calendars.CALENDAR_ACCESS_LEVEL,
        )
        val selection = "${Calendars.CALENDAR_ACCESS_LEVEL} >= ?"
        val selectionArgs = arrayOf(Calendars.CAL_ACCESS_OWNER.toString())

        try {
            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(Calendars._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(Calendars.CALENDAR_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    result.add(Pair(cursor.getLong(idIdx), cursor.getString(nameIdx)))
                }
            }
        } catch (e: SecurityException) {
            // 权限被拒绝时静默返回空列表
        } catch (e: Exception) {
            // 其他异常静默处理
        }

        return result
    }

    /**
     * 获取默认（第一个）日历 ID。
     * @return 日历 ID，无可用日历时返回 null
     */
    fun getDefaultCalendarId(context: Context): Long? {
        return getCalendars(context).firstOrNull()?.first
    }

    /**
     * 创建日历事件（直接写入系统日历，不弹 Intent）。
     * @param draft 事件草稿
     * @param calendarId 目标日历 ID（null 则使用主日历）
     * @return 创建成功的事件 ID（Long），或 null 表示失败
     */
    fun createEvent(context: Context, draft: CalendarEventDraft, calendarId: Long? = null): Long? {
        if (!hasPermission(context)) return null

        val targetCalendarId = calendarId ?: getDefaultCalendarId(context) ?: return null

        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, targetCalendarId)
            put(Events.TITLE, draft.title)
            put(Events.DTSTART, draft.startEpochMs)
            put(Events.DTEND, draft.endEpochMs)
            put(Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            if (!draft.description.isNullOrBlank()) {
                put(Events.DESCRIPTION, draft.description)
            }
            if (!draft.location.isNullOrBlank()) {
                put(Events.EVENT_LOCATION, draft.location)
            }
        }

        return try {
            val uri = context.contentResolver.insert(Events.CONTENT_URI, values)
            uri?.lastPathSegment?.toLongOrNull()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 更新日历事件。
     * @param eventId 事件 ID
     * @param draft 更新后的草稿数据
     * @return 是否成功
     */
    fun updateEvent(context: Context, eventId: Long, draft: CalendarEventDraft): Boolean {
        if (!hasPermission(context)) return false

        val values = ContentValues().apply {
            put(Events.TITLE, draft.title)
            put(Events.DTSTART, draft.startEpochMs)
            put(Events.DTEND, draft.endEpochMs)
            put(Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            put(Events.DESCRIPTION, draft.description ?: "")
            put(Events.EVENT_LOCATION, draft.location ?: "")
        }

        val uri = ContentUris.withAppendedId(Events.CONTENT_URI, eventId)
        return try {
            val rows = context.contentResolver.update(uri, values, null, null)
            rows > 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 删除日历事件。
     * @param eventId 事件 ID
     * @return 是否成功
     */
    fun deleteEvent(context: Context, eventId: Long): Boolean {
        if (!hasPermission(context)) return false

        val uri = ContentUris.withAppendedId(Events.CONTENT_URI, eventId)
        return try {
            val rows = context.contentResolver.delete(uri, null, null)
            rows > 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 查询指定时间范围内的事件。
     * @param startMs 开始时间（epoch ms）
     * @param endMs 结束时间（epoch ms）
     * @return List<CalendarEventInfo>
     */
    fun queryEvents(context: Context, startMs: Long, endMs: Long): List<CalendarEventInfo> {
        if (!hasPermission(context)) return emptyList()

        val result = mutableListOf<CalendarEventInfo>()

        // 投影字段：_ID, TITLE, DTSTART, DTEND, DESCRIPTION, LOCATION, CALENDAR_ID
        val projection = arrayOf(
            Events._ID,
            Events.TITLE,
            Events.DTSTART,
            Events.DTEND,
            Events.DESCRIPTION,
            "eventLocation",
            Events.CALENDAR_ID,
        )
        // WHERE: DTSTART < endMs AND DTEND > startMs （包含与查询区间有重叠的事件）
        val selection = "${Events.DTSTART} < ? AND ${Events.DTEND} > ?"
        val selectionArgs = arrayOf(endMs.toString(), startMs.toString())
        val sortOrder = "${Events.DTSTART} ASC"

        try {
            context.contentResolver.query(Events.CONTENT_URI, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(Events._ID)
                val titleIdx = cursor.getColumnIndexOrThrow(Events.TITLE)
                val startIdx = cursor.getColumnIndexOrThrow(Events.DTSTART)
                val endIdx = cursor.getColumnIndexOrThrow(Events.DTEND)
                val descIdx = cursor.getColumnIndexOrThrow(Events.DESCRIPTION)
                val locIdx = cursor.getColumnIndexOrThrow("eventLocation")
                val calIdIdx = cursor.getColumnIndexOrThrow(Events.CALENDAR_ID)

                while (cursor.moveToNext()) {
                    val calId = cursor.getLong(calIdIdx)
                    result.add(CalendarEventInfo(
                        id = cursor.getLong(idIdx),
                        title = cursor.getString(titleIdx) ?: "",
                        startMs = cursor.getLong(startIdx),
                        endMs = cursor.getLong(endIdx),
                        description = cursor.getString(descIdx),
                        location = cursor.getString(locIdx),
                        calendarName = getCalendarName(context, calId),
                    ))
                }
            }
        } catch (e: SecurityException) {
            return emptyList()
        } catch (e: Exception) {
            return emptyList()
        }

        return result
    }

    /**
     * 按 ID 查询单个事件。
     * @param eventId 事件 ID
     * @return 事件信息，不存在返回 null
     */
    fun queryEventById(context: Context, eventId: Long): CalendarEventInfo? {
        if (!hasPermission(context)) return null

        val projection = arrayOf(
            Events._ID,
            Events.TITLE,
            Events.DTSTART,
            Events.DTEND,
            Events.DESCRIPTION,
            "eventLocation",
            Events.CALENDAR_ID,
        )
        val uri = ContentUris.withAppendedId(Events.CONTENT_URI, eventId)
        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val calId = cursor.getLong(cursor.getColumnIndexOrThrow(Events.CALENDAR_ID))
                    CalendarEventInfo(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow(Events._ID)),
                        title = cursor.getString(cursor.getColumnIndexOrThrow(Events.TITLE)) ?: "",
                        startMs = cursor.getLong(cursor.getColumnIndexOrThrow(Events.DTSTART)),
                        endMs = cursor.getLong(cursor.getColumnIndexOrThrow(Events.DTEND)),
                        description = cursor.getString(cursor.getColumnIndexOrThrow(Events.DESCRIPTION)),
                        location = cursor.getString(cursor.getColumnIndexOrThrow("eventLocation")),
                        calendarName = getCalendarName(context, calId),
                    )
                } else null
            }
        } catch (_: Exception) { null }
    }

    /**
     * 查询今天的事件。
     */
    fun queryTodayEvents(context: Context): List<CalendarEventInfo> {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val startOfDay = cal.timeInMillis
        val endOfDay = startOfDay + 24 * 60 * 60 * 1000L
        return queryEvents(context, startOfDay, endOfDay)
    }

    /**
     * 查询明天的事件。
     */
    fun queryTomorrowEvents(context: Context): List<CalendarEventInfo> {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val startOfDay = cal.timeInMillis
        val endOfDay = startOfDay + 24 * 60 * 60 * 1000L
        return queryEvents(context, startOfDay, endOfDay)
    }

    /**
     * 根据 calendarId 获取日历显示名称。
     */
    private fun getCalendarName(context: Context, calendarId: Long): String? {
        val projection = arrayOf(Calendars.CALENDAR_DISPLAY_NAME)
        val selection = "${Calendars._ID} = ?"
        val selectionArgs = arrayOf(calendarId.toString())

        return try {
            context.contentResolver.query(
                Calendars.CONTENT_URI, projection, selection, selectionArgs, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(cursor.getColumnIndexOrThrow(Calendars.CALENDAR_DISPLAY_NAME))
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * 日历事件信息（查询结果）。
 */
data class CalendarEventInfo(
    val id: Long,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val description: String?,
    val location: String?,
    val calendarName: String?,
)
