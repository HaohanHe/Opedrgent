package top.hsyscn.opedrgent.agent

class ShortTermMemory {
    private val store = mutableMapOf<String, String>()

    fun put(key: String, value: String) {
        store[key] = value
    }

    fun get(key: String): String? = store[key]

    fun clear() {
        store.clear()
    }
}

class LongTermMemory {
    private val summaries = mutableListOf<MemorySummary>()

    data class MemorySummary(
        val sessionId: String,
        val summary: String,
        val timestamp: Long,
        val topics: List<String>,
    )

    fun addSummary(sessionId: String, summary: String, topics: List<String>) {
        summaries.add(MemorySummary(sessionId, summary, System.currentTimeMillis(), topics))
    }

    fun getRecentSummaries(limit: Int = 5): List<MemorySummary> {
        return summaries.takeLast(limit)
    }

    fun getSummariesByTopic(topic: String): List<MemorySummary> {
        return summaries.filter { it.topics.any { t -> t.contains(topic, ignoreCase = true) } }
    }
}

interface RagRetriever {
    suspend fun search(query: String, limit: Int): List<RagResult>

    data class RagResult(
        val content: String,
        val score: Float,
        val metadata: Map<String, String>,
    )
}

class HierarchicalMemory {
    val shortTerm = ShortTermMemory()
    val longTerm = LongTermMemory()
    var ragRetriever: RagRetriever? = null

    suspend fun retrieve(query: String, limit: Int = 5): String {
        val parts = mutableListOf<String>()

        val shortResult = shortTerm.get(query)
        if (shortResult != null) parts.add("[短期记忆] $shortResult")

        val summaries = longTerm.getRecentSummaries(limit)
        if (summaries.isNotEmpty()) {
            parts.add("[历史摘要]\n" + summaries.joinToString("\n") { s -> "- ${s.summary.take(200)}" })
        }

        ragRetriever?.let { retriever ->
            val ragResults = retriever.search(query, limit)
            if (ragResults.isNotEmpty()) {
                parts.add("[语义检索]\n" + ragResults.joinToString("\n") { r -> "- ${r.content.take(200)} (相关性: ${r.score})" })
            }
        }

        return parts.joinToString("\n\n")
    }
}