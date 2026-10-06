package top.hsyscn.opedrgent.stt

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

class VocabularyStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 解析后的词表缓存：热路径每 200ms 调一次 applyVocabulary，避免每次都读 SP + JSON 解析（U48-12） */
    @Volatile private var cachedTerms: Map<String, String>? = null

    companion object {
        private const val PREFS_NAME = "vocabulary"
        private const val KEY_TERMS = "terms"
    }

    fun addTerm(term: String) {
        addTerm(term, term)
    }

    /**
     * 添加热词/同音纠错映射：识别结果中的 term 会被替换为 replacement。
     * 仅传 term 时 replacement==term，等价于词表增强（替换为自身，无副作用）。
     */
    fun addTerm(term: String, replacement: String) {
        val normalized = term.trim()
        if (normalized.isBlank()) return
        val normalizedReplacement = replacement.trim()
        val map = getTermsMap().toMutableMap()
        map[normalized] = normalizedReplacement.ifBlank { normalized }
        saveTermsMap(map)
    }

    fun removeTerm(term: String) {
        val map = getTermsMap().toMutableMap()
        map.remove(term.trim())
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
        cachedTerms?.let { return it }
        val jsonStr = prefs.getString(KEY_TERMS, null)
        val parsed = if (jsonStr == null) {
            emptyMap()
        } else try {
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
        cachedTerms = parsed
        return parsed
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
        cachedTerms = map.toMap()
    }
}
