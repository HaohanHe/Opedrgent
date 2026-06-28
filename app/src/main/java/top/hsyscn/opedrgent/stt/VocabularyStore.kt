package top.hsyscn.opedrgent.stt

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

class VocabularyStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "vocabulary"
        private const val KEY_TERMS = "terms"
    }

    fun addTerm(term: String) {
        val normalized = term.trim()
        if (normalized.isBlank()) return
        val map = getTermsMap().toMutableMap()
        map[normalized] = normalized
        saveTermsMap(map)
    }

    fun removeTerm(term: String) {
        val map = getTermsMap().toMutableMap()
        map.remove(term)
        saveTermsMap(map)
    }

    fun listTerms(): List<String> {
        return getTermsMap().keys.sorted()
    }

    fun search(query: String): List<String> {
        val q = query.trim()
        if (q.isBlank()) return listTerms()
        return listTerms().filter { it.contains(q, ignoreCase = true) }
    }

    fun applyVocabulary(text: String): String {
        var result = text
        for ((term, replacement) in getTermsMap()) {
            if (term.isBlank()) continue
            result = result.replace(term, replacement, ignoreCase = true)
        }
        return result
    }

    private fun getTermsMap(): Map<String, String> {
        val jsonStr = prefs.getString(KEY_TERMS, null) ?: return emptyMap()
        return try {
            val array = JSONTokener(jsonStr).nextValue() as? JSONArray
            if (array != null) {
                val map = mutableMapOf<String, String>()
                for (i in 0 until array.length()) {
                    val item = array.get(i)
                    if (item is JSONObject) {
                        val t = item.optString("term", "")
                        val r = item.optString("replacement", t)
                        if (t.isNotBlank()) map[t] = r
                    } else if (item is String && item.isNotBlank()) {
                        map[item] = item
                    }
                }
                map
            } else {
                emptyMap()
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveTermsMap(map: Map<String, String>) {
        val array = JSONArray()
        map.keys.sorted().forEach { key ->
            val obj = JSONObject()
            obj.put("term", key)
            obj.put("replacement", map[key])
            array.put(obj)
        }
        prefs.edit().putString(KEY_TERMS, array.toString()).apply()
    }
}
