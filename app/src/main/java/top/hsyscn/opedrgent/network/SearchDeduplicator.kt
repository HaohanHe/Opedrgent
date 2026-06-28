package top.hsyscn.opedrgent.network

import top.hsyscn.opedrgent.utils.DebugLog

/**
 * URL/content deduplication for merged search results.
 *
 * Extracted from WebSearcher.kt. Wraps [ResultDeduplicator] and emits the
 * before/after diagnostic log line that the resilient search path expects.
 */
class SearchDeduplicator {

    private val deduplicator = ResultDeduplicator()

    /**
     * Deduplicate [results] by URL and content fingerprint. Empty inputs
     * are returned unchanged. Otherwise the before/after counts are logged
     * via [DebugLog] for observability.
     */
    fun deduplicate(results: List<SearchResult>): List<SearchResult> {
        if (results.isEmpty()) return results
        val before = results.size
        val out = deduplicator.deduplicate(results)
        val removed = before - out.size
        DebugLog.i("WebSearcher dedup: $before -> ${out.size} ($removed removed)")
        return out
    }
}
