package top.hsyscn.opedrgent.calendar

data class CalendarEventDraft(
    val title: String,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val description: String?,
    val location: String?,
)

