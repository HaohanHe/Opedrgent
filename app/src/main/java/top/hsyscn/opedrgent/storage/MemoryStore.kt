package top.hsyscn.opedrgent.storage

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import top.hsyscn.opedrgent.model.MemoryEntry
import top.hsyscn.opedrgent.model.MemoryType
import java.util.UUID

class MemoryStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("opedrgent_memory", Context.MODE_PRIVATE)

    fun list(): List<MemoryEntry> {
        val raw = prefs.getString("entries", null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<MemoryEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                MemoryEntry(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    title = o.optString("title", ""),
                    content = o.optString("content", ""),
                    type = runCatching { MemoryType.valueOf(o.optString("type", "USER")) }.getOrDefault(MemoryType.USER),
                    createdAt = o.optLong("createdAt", 0L),
                    updatedAt = o.optLong("updatedAt", 0L),
                ),
            )
        }
        return out.sortedByDescending { it.updatedAt }
    }

    fun add(title: String, content: String, type: MemoryType = MemoryType.USER): MemoryEntry {
        val entry = MemoryEntry(title = title.trim(), content = content.trim(), type = type)
        val list = list().toMutableList()
        list.add(entry)
        saveAll(list)
        return entry
    }

    fun update(id: String, title: String, content: String, type: MemoryType = MemoryType.USER) {
        val list = list().toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        list[idx] = list[idx].copy(
            title = title.trim(),
            content = content.trim(),
            type = type,
            updatedAt = System.currentTimeMillis(),
        )
        saveAll(list)
    }

    fun delete(id: String) {
        val list = list().toMutableList()
        list.removeAll { it.id == id }
        saveAll(list)
    }

    fun getMemoryBlock(): String {
        val entries = list()
        if (entries.isEmpty()) return ""
        return entries.joinToString(separator = "\n\n") { e ->
            val typeLabel = e.type.label
            if (e.title.isNotBlank()) {
                "[$typeLabel|${e.title}] ${e.content}"
            } else {
                "[$typeLabel] ${e.content}"
            }
        }
    }

    fun getByType(type: MemoryType): List<MemoryEntry> {
        return list().filter { it.type == type }
    }

    private fun saveAll(list: List<MemoryEntry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(
                JSONObject().apply {
                    put("id", e.id)
                    put("title", e.title)
                    put("content", e.content)
                    put("type", e.type.name)
                    put("createdAt", e.createdAt)
                    put("updatedAt", e.updatedAt)
                },
            )
        }
        prefs.edit().putString("entries", arr.toString()).apply()
    }
}
