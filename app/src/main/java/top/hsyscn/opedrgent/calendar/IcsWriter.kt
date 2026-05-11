package top.hsyscn.opedrgent.calendar

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

object IcsWriter {
    private val localZone: ZoneId = ZoneId.systemDefault()
    private val fmtLocal = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val fmtStampUtc = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneId.of("UTC"))

    fun toIcs(events: List<CalendarEventDraft>, prodId: String = "-//opedrgent//EN"): String {
        val lines = ArrayList<String>()
        lines += "BEGIN:VCALENDAR"
        lines += "VERSION:2.0"
        lines += "PRODID:$prodId"
        lines += "CALSCALE:GREGORIAN"
        lines += "METHOD:PUBLISH"
        lines += "X-WR-TIMEZONE:${localZone.id}"
        events.forEach { e ->
            val uid = UUID.randomUUID().toString()
            val start = ZonedDateTime.ofInstant(Instant.ofEpochMilli(e.startEpochMs), localZone)
            val end = ZonedDateTime.ofInstant(Instant.ofEpochMilli(e.endEpochMs), localZone)
            lines += "BEGIN:VEVENT"
            lines += "UID:$uid"
            lines += "DTSTAMP:${fmtStampUtc.format(Instant.now())}"
            lines += "DTSTART;TZID=${localZone.id}:${fmtLocal.format(start)}"
            lines += "DTEND;TZID=${localZone.id}:${fmtLocal.format(end)}"
            lines += "SUMMARY:${escape(e.title)}"
            if (!e.location.isNullOrBlank()) lines += "LOCATION:${escape(e.location)}"
            if (!e.description.isNullOrBlank()) lines += "DESCRIPTION:${escape(e.description)}"
            lines += "END:VEVENT"
        }
        lines += "END:VCALENDAR"
        return lines.joinToString("\r\n") + "\r\n"
    }

    private fun escape(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace(",", "\\,")
            .replace(";", "\\;")
    }
}
