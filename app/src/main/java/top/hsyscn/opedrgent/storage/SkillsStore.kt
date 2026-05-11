package top.hsyscn.opedrgent.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import top.hsyscn.opedrgent.model.Skill

class SkillsStore(context: Context) {
    private val prefs = context.getSharedPreferences("opedrgent_skills", Context.MODE_PRIVATE)
    private val lock = Any()

    fun list(): List<Skill> {
        synchronized(lock) {
            val raw = prefs.getString("skills", null) ?: return defaultSkills()
            val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return defaultSkills()
            val skills = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                parse(o)
            }
            return skills.sortedBy { it.name.lowercase() }
        }
    }

    fun saveAll(skills: List<Skill>) {
        synchronized(lock) {
            val arr = JSONArray()
            skills.forEach { arr.put(serialize(it)) }
            prefs.edit().putString("skills", arr.toString()).apply()
        }
    }

    fun upsert(skill: Skill) {
        val all = list().toMutableList()
        val idx = all.indexOfFirst { it.id == skill.id }
        if (idx >= 0) all[idx] = skill else all.add(skill)
        saveAll(all)
    }

    fun delete(skillId: String) {
        val all = list().filterNot { it.id == skillId }
        saveAll(all)
    }

    fun findByName(name: String): Skill? {
        val n = name.trim()
        if (n.isEmpty()) return null
        return list().firstOrNull { it.name.equals(n, ignoreCase = true) }
    }

    private fun parse(o: JSONObject): Skill? {
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
        val name = o.optString("name").trim().takeIf { it.isNotBlank() } ?: return null
        val prompt = o.optString("prompt")
        val createdAt = o.optLong("createdAt", 0L)
        val updatedAt = o.optLong("updatedAt", createdAt)
        return Skill(id = id, name = name, prompt = prompt, createdAt = createdAt, updatedAt = updatedAt)
    }

    private fun serialize(skill: Skill): JSONObject {
        return JSONObject()
            .put("id", skill.id)
            .put("name", skill.name)
            .put("prompt", skill.prompt)
            .put("createdAt", skill.createdAt)
            .put("updatedAt", skill.updatedAt)
    }

    private fun defaultSkills(): List<Skill> {
        val now = System.currentTimeMillis()
        return listOf(
            Skill(name = "摘要", prompt = "基于当前来源与对话，生成一份简洁摘要，并用 [S1]/[S2] 标注引用。", createdAt = now, updatedAt = now),
            Skill(name = "要点", prompt = "基于当前来源，列出 10 条要点（每条不超过 20 字），并用 [S1]/[S2] 标注引用。", createdAt = now, updatedAt = now),
            Skill(name = "结论", prompt = "基于当前来源与对话，给出 3 条结论与 3 条不确定点，并标注引用。", createdAt = now, updatedAt = now),
        )
    }
}

