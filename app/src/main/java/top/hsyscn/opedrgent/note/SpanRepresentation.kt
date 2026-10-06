package top.hsyscn.opedrgent.note

import org.json.JSONArray
import org.json.JSONObject
import top.hsyscn.opedrgent.utils.DebugLog

data class SpanRepresentation(
    var bold: Boolean = false,
    var italic: Boolean = false,
    var monospace: Boolean = false,
    var strikethrough: Boolean = false,
    var link: Boolean = false,
    var start: Int = 0,
    var end: Int = 0,
) {
    fun isNotUseless() = bold || italic || monospace || strikethrough || link

    companion object {
        fun toJson(spans: List<SpanRepresentation>): String {
            val arr = JSONArray()
            spans.forEach { s ->
                arr.put(JSONObject().apply {
                    put("b", s.bold)
                    put("i", s.italic)
                    put("m", s.monospace)
                    put("s", s.strikethrough)
                    put("l", s.link)
                    put("a", s.start)
                    put("e", s.end)
                })
            }
            return arr.toString()
        }

        fun fromJson(json: String): List<SpanRepresentation> {
            if (json.isBlank()) return emptyList()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i)
                    if (o == null) {
                        DebugLog.w("SpanRepresentation", "skip non-object span at index $i")
                        null
                    } else {
                        SpanRepresentation(
                            bold = o.optBoolean("b", false),
                            italic = o.optBoolean("i", false),
                            monospace = o.optBoolean("m", false),
                            strikethrough = o.optBoolean("s", false),
                            link = o.optBoolean("l", false),
                            start = o.optInt("a", 0),
                            end = o.optInt("e", 0),
                        )
                    }
                }
            } catch (e: Exception) {
                DebugLog.w("SpanRepresentation", "decode spans failed: ${e.message}")
                emptyList()
            }
        }
    }
}
