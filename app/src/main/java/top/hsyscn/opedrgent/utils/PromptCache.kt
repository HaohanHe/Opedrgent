package top.hsyscn.opedrgent.utils

import top.hsyscn.opedrgent.env.EnvironmentInfo
import top.hsyscn.opedrgent.model.ResearchSession
import top.hsyscn.opedrgent.settings.ApiSettings
import top.hsyscn.opedrgent.storage.MemoryStore

object PromptCache {
    private val globalCache = mutableMapOf<String, String>()
    private val sessionCache = mutableMapOf<String, CachedEntry?>()
    private const val DEFAULT_TTL_MS = 5 * 60 * 1000L
    private const val STATIC_TTL_MS = 60 * 60 * 1000L

    private const val STATIC_PROMPT_KEY = "system_static_prompt"

    data class CachedEntry(
        val value: String,
        val computedAt: Long = System.currentTimeMillis(),
        val ttlMs: Long = DEFAULT_TTL_MS,
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() - computedAt > ttlMs
    }

    fun getOrComputeStaticPrompt(compute: () -> String): String {
        globalCache[STATIC_PROMPT_KEY]?.let { return it }
        val value = compute()
        globalCache[STATIC_PROMPT_KEY] = value
        return value
    }

    fun clearStaticPrompt() {
        globalCache.remove(STATIC_PROMPT_KEY)
    }

    fun getGlobal(key: String): String? = globalCache[key]

    fun getOrComputeGlobal(key: String, compute: () -> String): String {
        globalCache[key]?.let { return it }
        val value = compute()
        globalCache[key] = value
        return value
    }

    fun getSessionCached(key: String): String? {
        val entry = sessionCache[key] ?: return null
        return if (entry.isExpired()) null else entry.value
    }

    fun getOrComputeSession(key: String, compute: () -> String?): String? {
        val existing = sessionCache[key]
        if (existing != null && !existing.isExpired()) {
            return existing.value
        }
        val value = compute() ?: return null
        sessionCache[key] = CachedEntry(value)
        return value
    }

    fun putSession(key: String, value: String, ttlMs: Long = DEFAULT_TTL_MS) {
        sessionCache[key] = CachedEntry(value, ttlMs = ttlMs)
    }

    fun clearSession() {
        sessionCache.clear()
    }

    fun clearAll() {
        globalCache.clear()
        sessionCache.clear()
    }

    fun getStats(): Map<String, Int> = mapOf(
        "global_cache_size" to globalCache.size,
        "session_cache_size" to sessionCache.size,
    )
}
