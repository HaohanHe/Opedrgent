package top.hsyscn.opedrgent.network

class ResultDeduplicator {
    
    companion object {
        private val TRACKING_PARAMS = setOf(
            "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
            "fbclid", "gclid", "msclkid", "twclid", "ref",
            "spm", "from", "isappinstalled", "sharekey"
        )
        
        private const val TITLE_SIMILARITY_THRESHOLD = 0.75
        private const val URL_SIMILARITY_THRESHOLD = 0.85
    }
    
    fun deduplicate(results: List<SearchResult>): List<SearchResult> {
        if (results.size <= 1) return results
        
        val normalized = results.map { normalize(it) }
        val unique = mutableListOf<NormalizedResult>()
        
        for (current in normalized) {
            val duplicate = unique.findExisting(current)
            if (duplicate != null) {
                duplicate.mergeFrom(current)
            } else {
                unique.add(current)
            }
        }
        
        return unique.map { it.toSearchResult() }
    }
    
    private fun normalize(result: SearchResult): NormalizedResult {
        return NormalizedResult(
            normalizedUrl = normalizeUrl(result.url),
            title = result.title,
            snippet = result.snippet,
            sourceEngines = mutableSetOf(*(result.sourceEngines?.toTypedArray() ?: emptyArray())),
            bestScore = result.score ?: 0.0,
            bestSnippetLength = result.snippet?.length ?: 0
        )
    }
    
    private fun normalizeUrl(url: String): String {
        try {
            val parsed = java.net.URL(url)
            val query = if (parsed.query != null) {
                val params = parsed.query.split("&")
                    .filterNot { param ->
                        val key = param.substringBefore("=").lowercase()
                        key in TRACKING_PARAMS || key.isEmpty()
                    }
                    .joinToString("&")
                if (params.isEmpty()) null else params
            } else {
                null
            }
            
            return java.net.URI(
                parsed.protocol,
                null,
                parsed.host,
                if (parsed.port > 0) parsed.port else -1,
                parsed.path,
                query,
                parsed.ref
            ).toString().removeSuffix("/").lowercase()
        } catch (_: Exception) {
            return url.lowercase()
        }
    }
    
    private fun titleSimilarity(a: String, b: String): Double {
        val wordsA = extractWords(a).toSet()
        val wordsB = extractWords(b).toSet()
        if (wordsA.isEmpty() || wordsB.isEmpty()) return 0.0
        val intersection = wordsA.intersect(wordsB).size.toDouble()
        val union = wordsA.union(wordsB).size.toDouble()
        return intersection / union
    }
    
    private fun extractWords(text: String): List<String> {
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\u4e00-\\u9fa5]"), " ")
            .split("\\s+".toRegex())
            .filter { it.length >= 2 }
    }
    
    private data class NormalizedResult(
        var normalizedUrl: String,
        var title: String,
        var snippet: String?,
        var sourceEngines: MutableSet<String>,
        var bestScore: Double,
        var bestSnippetLength: Int
    ) {
        fun toSearchResult() = SearchResult(
            title = title,
            url = normalizedUrl,
            snippet = snippet,
            sourceEngines = sourceEngines.toSet(),
            score = bestScore
        )
        
        fun mergeFrom(other: NormalizedResult) {
            sourceEngines.addAll(other.sourceEngines)
            if (other.bestScore > bestScore) {
                bestScore = other.bestScore
                title = other.title
                snippet = other.snippet
            } else if ((other.snippet?.length ?: 0) > bestSnippetLength) {
                snippet = other.snippet
                bestSnippetLength = other.snippet?.length ?: 0
            }
        }
    }
    
    private fun MutableList<NormalizedResult>.findExisting(candidate: NormalizedResult): NormalizedResult? {
        for (existing in this) {
            if (existing.normalizedUrl == candidate.normalizedUrl) return existing
            if (titleSimilarity(existing.title, candidate.title) > TITLE_SIMILARITY_THRESHOLD 
                && urlDomainMatch(existing.normalizedUrl, candidate.normalizedUrl)) return existing
        }
        return null
    }
    
    private fun urlDomainMatch(url1: String, url2: String): Boolean {
        try {
            val d1 = java.net.URL(url1).host.removePrefix("www.").removePrefix("m.").removePrefix("mobile.")
            val d2 = java.net.URL(url2).host.removePrefix("www.").removePrefix("m.").removePrefix("mobile.")
            return d1 == d2 || d1.endsWith(".$d2") || d2.endsWith(".$d1")
        } catch (_: Exception) { return false }
    }
}
