package top.hsyscn.opedrgent.storage

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import top.hsyscn.opedrgent.model.MemoryEntry
import top.hsyscn.opedrgent.model.MemoryType

class MemoryStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("opedrgent_memory", Context.MODE_PRIVATE)
    private val lock = Any()

    companion object {
        private const val KEY_IDS = "memory_ids"
        private const val KEY_LEGACY = "entries"
        private const val PREFIX = "memory_"
    }

    /** 迁移旧版全量 JSON 到分键存储（一次性）。 */
    private fun migrateIfNeeded() {
        val legacy = prefs.getString(KEY_LEGACY, null) ?: return
        if (legacy.isBlank()) {
            prefs.edit().remove(KEY_LEGACY).apply()
            return
        }
        val arr = runCatching { JSONArray(legacy) }.getOrNull() ?: run {
            prefs.edit().remove(KEY_LEGACY).apply()
            return
        }
        val ids = mutableListOf<String>()
        val editor = prefs.edit()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val entry = parseEntry(o) ?: continue
            editor.putString(entryKey(entry.id), serializeEntry(entry).toString())
            ids.add(entry.id)
        }
        editor.putString(KEY_IDS, JSONArray(ids).toString())
        editor.remove(KEY_LEGACY)
        editor.apply()
    }

    private fun entryKey(id: String) = PREFIX + id

    private fun loadIds(): MutableList<String> {
        val raw = prefs.getString(KEY_IDS, null) ?: return mutableListOf()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return mutableListOf()
        val ids = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            arr.optString(i).takeIf { it.isNotBlank() }?.let { ids.add(it) }
        }
        return ids
    }

    private fun saveIds(ids: List<String>, editor: SharedPreferences.Editor) {
        editor.putString(KEY_IDS, JSONArray(ids).toString())
    }

    private fun loadEntry(id: String): MemoryEntry? {
        val raw = prefs.getString(entryKey(id), null) ?: return null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        return parseEntry(o)
    }

    private fun parseEntry(o: JSONObject): MemoryEntry? {
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
        return MemoryEntry(
            id = id,
            title = o.optString("title", ""),
            content = o.optString("content", ""),
            type = runCatching { MemoryType.valueOf(o.optString("type", "USER")) }.getOrDefault(MemoryType.USER),
            createdAt = o.optLong("createdAt", 0L),
            updatedAt = o.optLong("updatedAt", 0L),
        )
    }

    private fun serializeEntry(e: MemoryEntry): JSONObject {
        return JSONObject().apply {
            put("id", e.id)
            put("title", e.title)
            put("content", e.content)
            put("type", e.type.name)
            put("createdAt", e.createdAt)
            put("updatedAt", e.updatedAt)
        }
    }

    fun list(): List<MemoryEntry> {
        return synchronized(lock) {
            migrateIfNeeded()
            loadIds().mapNotNull { loadEntry(it) }.sortedByDescending { it.updatedAt }
        }
    }

    fun add(title: String, content: String, type: MemoryType = MemoryType.USER): MemoryEntry {
        val entry = MemoryEntry(title = title.trim(), content = content.trim(), type = type)
        synchronized(lock) {
            migrateIfNeeded()
            val ids = loadIds()
            ids.add(entry.id)
            prefs.edit()
                .putString(entryKey(entry.id), serializeEntry(entry).toString())
                .putString(KEY_IDS, JSONArray(ids).toString())
                .apply()
        }
        return entry
    }

    fun update(id: String, title: String, content: String, type: MemoryType = MemoryType.USER) {
        synchronized(lock) {
            migrateIfNeeded()
            val existing = loadEntry(id) ?: return
            val updated = existing.copy(
                title = title.trim(),
                content = content.trim(),
                type = type,
                updatedAt = System.currentTimeMillis(),
            )
            prefs.edit()
                .putString(entryKey(updated.id), serializeEntry(updated).toString())
                .apply()
        }
    }

    fun delete(id: String) {
        synchronized(lock) {
            migrateIfNeeded()
            val ids = loadIds()
            val removed = ids.removeAll { it == id }
            if (!removed) return
            prefs.edit()
                .remove(entryKey(id))
                .putString(KEY_IDS, JSONArray(ids).toString())
                .apply()
        }
    }

    fun getMemoryBlock(): String {
        val entries = list().filter { it.type != MemoryType.NOTE_SUMMARY }
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

    // ==================== 笔记记忆方法 ====================

    /** 添加笔记记忆（id 格式为 "note_$noteId" 便于精确查找/删除） */
    fun addNoteMemory(noteId: Long, title: String, summary: String): MemoryEntry {
        val noteIdStr = "note_$noteId"
        val entry = MemoryEntry(
            id = noteIdStr,
            title = title.trim(),
            content = summary.trim(),
            type = MemoryType.NOTE_SUMMARY,
        )
        synchronized(lock) {
            migrateIfNeeded()
            val ids = loadIds()
            ids.removeAll { it == noteIdStr }
            ids.add(entry.id)
            prefs.edit()
                .putString(entryKey(entry.id), serializeEntry(entry).toString())
                .putString(KEY_IDS, JSONArray(ids).toString())
                .apply()
        }
        return entry
    }

    /** 删除指定笔记的记忆 */
    fun removeNoteMemory(noteId: Long) {
        delete("note_$noteId")
    }

    /** 获取所有笔记记忆 */
    fun getNoteMemories(): List<MemoryEntry> {
        return list().filter { it.type == MemoryType.NOTE_SUMMARY }
    }
}
