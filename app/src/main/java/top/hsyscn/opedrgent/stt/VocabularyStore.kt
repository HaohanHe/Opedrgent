package top.hsyscn.opedrgent.stt

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
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
        val terms = getTermsSet().toMutableSet()
        terms.add(normalized)
        saveTermsSet(terms)
    }

    fun removeTerm(term: String) {
        val terms = getTermsSet().toMutableSet()
        terms.remove(term)
        saveTermsSet(terms)
    }

    fun listTerms(): List<String> {
        return getTermsSet().sorted()
    }

    fun search(query: String): List<String> {
        val q = query.trim()
        if (q.isBlank()) return listTerms()
        return listTerms().filter { it.contains(q, ignoreCase = true) }
    }

    fun applyVocabulary(text: String): String {
        var result = text
        for (term in listTerms()) {
            if (term.isBlank()) continue
            // Very naive replacement - just ensure the term is present
            // In real app, this would use pinyin/sound similarity
            result = result.replace(term, term)
        }
        return result
    }

    private fun getTermsSet(): Set<String> {
        val jsonStr = prefs.getString(KEY_TERMS, null) ?: return emptySet()
        return try {
            val array = JSONTokener(jsonStr).nextValue() as? JSONArray
            if (array != null) {
                (0 until array.length()).map { array.getString(it) }.toSet()
            } else {
                emptySet()
            }
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun saveTermsSet(terms: Set<String>) {
        val array = JSONArray()
        terms.sorted().forEach { array.put(it) }
        prefs.edit().putString(KEY_TERMS, array.toString()).apply()
    }
}
