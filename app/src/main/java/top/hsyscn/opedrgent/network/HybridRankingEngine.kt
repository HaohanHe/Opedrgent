package top.hsyscn.opedrgent.network

import top.hsyscn.opedrgent.utils.DebugLog
import java.util.regex.Pattern

data class RankingWeights(
    val bm25: Double = 0.25,
    val semantic: Double = 0.20,
    val authority: Double = 0.15,
    val freshness: Double = 0.15,
    val diversity: Double = 0.10,
    val position: Double = 0.15
)

data class RankingConfig(
    val weights: RankingWeights = RankingWeights(),
    val useMMR: Boolean = true,
    val mmrLambda: Double = 0.7,
    val mmrTopK: Int = 20,
    val normalizeScores: Boolean = true
)

data class RankedResult(
    val result: SearchResult,
    val bm25Score: Double,
    val semanticScore: SemanticScore?,
    val authorityScore: AuthorityScore?,
    val freshnessScore: FreshnessScore?,
    val hybridScore: Double,
    val diversityScore: Double,
    val positionScore: Double
)

class HybridRankingEngine(
    private val config: RankingConfig = RankingConfig()
) {

    companion object {
        private const val TAG = "HybridRankingEngine"

        private val CHINESE_WORD_PATTERN = Pattern.compile("[\\u4e00-\\u9fa5]{2,4}")
        private val ENGLISH_WORD_PATTERN = Pattern.compile("[a-zA-Z][a-zA-Z0-9_-]{1,20}")

        private val STOP_WORDS = setOf(
            "的", "了", "是", "在", "这", "那", "有", "和", "与", "或",
            "但", "而", "也", "就", "都", "很", "被", "把", "让", "给",
            "从", "到", "对", "向", "为", "以", "及", "等", "中", "上",
            "下", "不", "没", "能", "可", "要", "会", "应", "该", "已"
        )

        private val ENGINE_WEIGHT_MAP: Map<String, Double> = mapOf(
            "baidu" to 1.2, "bing" to 1.3, "ddg" to 1.0,
            "sogou" to 0.9, "360" to 0.8, "yandex" to 0.7,
            "jina" to 1.1, "brave" to 1.1, "tavily" to 1.0,
            "searxng" to 1.4, "google" to 1.3,
            "unknown" to 0.8, "jina-fallback" to 0.7
        )
    }

    private val semanticScorer = SemanticScorer()
    private val authorityScorer = DynamicAuthorityScorer()
    private val freshnessCalculator = FreshnessCalculator()

    fun initialize(query: String) {
        semanticScorer.initialize(query)
        freshnessCalculator.initWithQuery(semanticScorer.getDetectedIntent())
        DebugLog.d("[$TAG] initialized with query='$query', intent=${semanticScorer.getDetectedIntent()}")
    }

    fun rank(results: List<SearchResult>, limit: Int = 10): List<RankedResult> {
        if (results.isEmpty()) {
            DebugLog.w("[$TAG] rank called with empty results")
            return emptyList()
        }

        val totalResults = results.size
        DebugLog.d("[$TAG] ranking ${results.size} results, limit=$limit")

        val rankedList = results.mapIndexed { index, result ->
            calculateHybridScore(result, index, totalResults)
        }

        val sortedByScore = rankedList.sortedByDescending { it.hybridScore }
        DebugLog.d("[$TAG] sorted by hybridScore, top3=[${sortedByScore.take(3).joinToString { "%.3f".format(it.hybridScore) }}]")

        val finalResults = if (config.useMMR && sortedByScore.size > 1) {
            rerankWithMMR(sortedByScore, limit, config.mmrTopK)
        } else {
            sortedByScore.take(limit)
        }

        DebugLog.i("[$TAG] ranking complete: ${results.size} in → ${finalResults.size} out, MMR=${config.useMMR}")
        return finalResults
    }

    fun rerankWithMMR(
        rankedResults: List<RankedResult>,
        limit: Int = 10,
        topK: Int = config.mmrTopK
    ): List<RankedResult> {
        if (rankedResults.size <= 1) return rankedResults.take(limit)

        val candidates = rankedResults.take(topK.coerceAtLeast(1))
        val selected = mutableListOf<RankedResult>()
        val remaining = candidates.toMutableList()

        selected.add(remaining.removeAt(0))

        while (selected.size < limit && remaining.isNotEmpty()) {
            var bestCandidate: RankedResult? = null
            var bestMmrScore = Double.NEGATIVE_INFINITY

            for (candidate in remaining) {
                val maxSimilarity = selected.maxOf { existing ->
                    calculateTextSimilarity(candidate.result.title, existing.result.title)
                }

                val mmrScore = config.mmrLambda * candidate.hybridScore -
                        (1 - config.mmrLambda) * maxSimilarity

                if (mmrScore > bestMmrScore) {
                    bestMmrScore = mmrScore
                    bestCandidate = candidate
                }
            }

            bestCandidate?.let {
                selected.add(it)
                remaining.remove(it)
            }
        }

        DebugLog.d("[$TAG] MMR reranking done: selected=${selected.size}, lambda=${config.mmrLambda}")
        return selected
    }

    fun getWeightsForIntent(intent: QueryIntent): RankingWeights {
        return when (intent) {
            QueryIntent.INFORMATIONAL -> RankingWeights(
                bm25 = 0.25, semantic = 0.25,
                authority = 0.15, freshness = 0.15,
                diversity = 0.10, position = 0.10
            )
            QueryIntent.NAVIGATIONAL -> RankingWeights(
                bm25 = 0.20, semantic = 0.15,
                authority = 0.25, freshness = 0.05,
                diversity = 0.05, position = 0.30
            )
            QueryIntent.TRANSACTIONAL -> RankingWeights(
                bm25 = 0.15, semantic = 0.15,
                authority = 0.20, freshness = 0.20,
                diversity = 0.10, position = 0.20
            )
        }
    }

    private fun calculateHybridScore(result: SearchResult, originalPosition: Int, totalResults: Int): RankedResult {
        val bm25Score = result.score

        val semanticScore = semanticScorer.calculateScore(
            title = result.title,
            snippet = result.snippet
        )

        val authorityScore = authorityScorer.calculate(
            url = result.url,
            title = result.title,
            snippet = result.snippet
        )

        val freshnessScore = freshnessCalculator.calculate(
            url = result.url,
            snippet = result.snippet
        )

        val engineWeight = calculateEngineWeight(result.sourceEngines)
        val positionScore = calculatePositionScore(originalPosition, totalResults)
        val diversityScore = 1.0

        val rawHybridScore =
            bm25Score * config.weights.bm25 +
            semanticScore.combinedScore * config.weights.semantic +
            authorityScore.finalScore * config.weights.authority +
            freshnessScore.adjustedScore * config.weights.freshness +
            diversityScore * config.weights.diversity +
            positionScore * config.weights.position

        val engineAdjustedScore = rawHybridScore * engineWeight

        val finalScore = if (config.normalizeScores) {
            sigmoid(engineAdjustedScore)
        } else {
            engineAdjustedScore.coerceIn(0.0, 1.0)
        }

        return RankedResult(
            result = result,
            bm25Score = bm25Score,
            semanticScore = semanticScore,
            authorityScore = authorityScore,
            freshnessScore = freshnessScore,
            hybridScore = finalScore,
            diversityScore = diversityScore,
            positionScore = positionScore
        ).also {
            DebugLog.d("[$TAG] hybridScore=${"%.4f".format(finalScore)} | " +
                    "bm25=${"%.3f".format(bm25Score)} sem=${"%.3f".format(semanticScore.combinedScore)} " +
                    "auth=${"%.3f".format(authorityScore.finalScore)} fresh=${"%.3f".format(freshnessScore.adjustedScore)} " +
                    "pos=${"%.3f".format(positionScore)} engW=${"%.2f".format(engineWeight)} | ${result.title.take(40)}")
        }
    }

    private fun calculateTextSimilarity(title1: String, title2: String): Double {
        val words1 = extractKeywords(title1).toSet()
        val words2 = extractKeywords(title2).toSet()
        if (words1.isEmpty() || words2.isEmpty()) return 0.0

        val intersection = words1.intersect(words2).size
        val union = words1.union(words2).size

        return intersection.toDouble() / union.toDouble()
    }

    private fun extractKeywords(text: String): List<String> {
        if (text.isBlank()) return emptyList()

        val words = mutableListOf<String>()

        val chineseMatcher = CHINESE_WORD_PATTERN.matcher(text)
        while (chineseMatcher.find()) {
            val word = chineseMatcher.group()
            if (word !in STOP_WORDS && word.length >= 2) {
                words.add(word)
            }
        }

        val englishMatcher = ENGLISH_WORD_PATTERN.matcher(text)
        while (englishMatcher.find()) {
            val word = englishMatcher.group().lowercase()
            if (word !in STOP_WORDS && word.length >= 2) {
                words.add(word)
            }
        }

        return words.distinct()
    }

    /**
     * SearXNG 风格累乘引擎权重：所有引擎权重连乘，再乘以引擎数量的平方根。
     * 多引擎交叉验证的结果自然获得更高权重。
     *
     * 公式：productWeight = ∏ engine_weight(i) × √(engine_count)
     */
    private fun calculateEngineWeight(sourceEngines: Set<String>): Double {
        if (sourceEngines.isEmpty()) return 0.8

        var productWeight = 1.0
        for (engine in sourceEngines) {
            productWeight *= ENGINE_WEIGHT_MAP[engine] ?: 0.8
        }
        // 引擎数量放大：多引擎交叉验证 = 高可信度
        productWeight *= Math.sqrt(sourceEngines.size.toDouble())

        return productWeight
    }

    /**
     * SearXNG 风格线性位置衰减：score = base / position
     * 比对数衰减更平缓，避免过度惩罚后排结果。
     */
    private fun calculatePositionScore(position: Int, totalResults: Int): Double {
        val effectivePos = (position + 1).coerceAtLeast(1)
        return (10.0 / effectivePos).coerceIn(0.0, 10.0)
    }

    private fun sigmoid(x: Double): Double {
        return (1.0 / (1.0 + Math.exp(-x))).coerceIn(0.001, 0.999)
    }

    private fun normalizeScore(score: Double, min: Double, max: Double): Double {
        return if (max <= min) score else ((score - min) / (max - min)).coerceIn(0.0, 1.0)
    }
}
