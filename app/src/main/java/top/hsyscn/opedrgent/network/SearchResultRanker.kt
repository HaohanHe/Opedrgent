package top.hsyscn.opedrgent.network

/**
 * Post-retrieval ranking pipeline for aggregated search results.
 *
 * Extracted from WebSearcher.kt. Owns the [HybridRankingEngine] and exposes
 * two responsibilities:
 *  1. [filterAndSort] — apply relevance filtering (BM25 + keyword match)
 *     against the [SearchResultContainer] and return a sorted, limit-capped
 *     snapshot via [SearchResultContainer.getSortedResults].
 *  2. [rank] — apply the hybrid ranking fusion (recency + relevance +
 *     authority) and return the underlying [SearchResult] list.
 *
 * The orchestrator (WebSearcher) is responsible for calling [initialize]
 * with the query before invoking [filterAndSort] / [rank], so that the
 * underlying scoring state is primed.
 */
class SearchResultRanker {

    private val hybridRankingEngine: HybridRankingEngine by lazy { HybridRankingEngine() }

    /**
     * Prime ranking state for the given query. Must be called before
     * [filterAndSort] / [rank] for the scoring internals to be accurate.
     */
    fun initialize(query: String) {
        hybridRankingEngine.initialize(query)
    }

    /**
     * Filter the aggregated results inside [container] by BM25 score and
     * keyword-match count, then return a sorted snapshot capped at [limit].
     *
     * Defaults mirror the legacy WebSearcher thresholds: BM25 is relaxed
     * (`0.1`) so short Chinese queries are not over-filtered, and at least
     * 2 results are retained as a safety net.
     */
    fun filterAndSort(
        container: SearchResultContainer,
        limit: Int,
        minKeywordMatch: Int = SearchConstants.FILTER_MIN_KEYWORD_MATCH,
        minBm25Score: Double = SearchConstants.FILTER_MIN_BM25_SCORE,
        minResults: Int = SearchConstants.FILTER_MIN_RESULTS
    ): List<SearchResult> {
        container.filterRelevantResults(
            minKeywordMatch = minKeywordMatch,
            minBm25Score = minBm25Score,
            minResults = minResults
        )
        return container.getSortedResults(limit)
    }

    /**
     * Apply hybrid ranking (recency + relevance + authority fusion) and
     * return the underlying results, preserving the engine's ordering.
     * Returns the input unchanged if empty.
     */
    fun rank(results: List<SearchResult>, limit: Int): List<SearchResult> {
        if (results.isEmpty()) return results
        val ranked = hybridRankingEngine.rank(results, limit)
        return ranked.map { it.result }
    }
}
