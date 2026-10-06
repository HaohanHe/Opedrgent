package top.hsyscn.opedrgent.network

import top.hsyscn.opedrgent.utils.DebugLog
import java.util.regex.Pattern

/**
 * 融合权重。
 *
 * 注意：进入融合的每个分量都必须先归一到 [0,1] 再加权，否则不同量程的分量
 * 会互相淹没。relevance 为 SearchResultContainer 产出的复合分（量程约 0~100），
 * 在 rank() 内对本批结果做 min-max 归一后使用。authority / freshness 已由容器
 * 折叠进该复合分，此处不再重复计分。
 */
data class RankingWeights(
    val relevance: Double = 0.55,  // 容器复合分（已批量归一到 [0,1]，含 bm25/位置/权威/新鲜度）
    val semantic: Double = 0.25,  // 词法重叠分 [0,1]
    val position: Double = 0.20   // 原始位置分（已归一到 [0,1]）
)

data class RankingConfig(
    val weights: RankingWeights = RankingWeights(),
    val useMMR: Boolean = true,
    val mmrLambda: Double = 0.7,
    val mmrTopK: Int = 20
)

data class RankedResult(
    val result: SearchResult,
    val compositeScore: Double,
    val semanticScore: SemanticScore?,
    val authorityScore: AuthorityScore?,
    val freshnessScore: FreshnessScore?,
    val hybridScore: Double,
    val positionScore: Double
)

class HybridRankingEngine(
    private val config: RankingConfig = RankingConfig()
) {

    companion object {
        private const val TAG = "HybridRankingEngine"

        private val CHINESE_WORD_PATTERN = Pattern.compile("[\u4e00-\u9fa5]{2,4}")
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

        DebugLog.d("[$TAG] ranking ${results.size} results, limit=$limit")

        // ★ 把容器复合分（量程约 0~100，含 bm25/位置/权威/新鲜度/引擎多样性）
        //   在本批结果内 min-max 归一到 [0,1]，使各分量同量程可加权。
        //   旧实现直接把这个 10~100 量级的复合分当成 BM25 以 0.25 权重融合，
        //   再过 sigmoid 导致头尾都被钳到 0.999、排序失效（U33-1）。
        val minScore = results.minOf { it.score }
        val maxScore = results.maxOf { it.score }
        val scoreRange = maxScore - minScore
        fun normalizeComposite(score: Double): Double =
            if (scoreRange <= 0.0) 1.0 else (score - minScore) / scoreRange

        val rankedList = results.mapIndexed { index, result ->
            calculateHybridScore(result, index, normalizeComposite(result.score))
        }

        val sortedByScore = rankedList.sortedByDescending { it.hybridScore }
        DebugLog.d("[$TAG] sorted by hybridScore, top3=[${sortedByScore.take(3).joinToString { "%.3f".format(it.hybridScore) }}]")

        val finalResults = if (config.useMMR && sortedByScore.size > 1) {
            rerankWithMMR(sortedByScore, limit, config.mmrTopK)
        } else {
            sortedByScore.take(limit)
        }

        DebugLog.i("[$TAG] ranking complete: ${results.size} in -> ${finalResults.size} out, MMR=${config.useMMR}")
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

    private fun calculateHybridScore(
        result: SearchResult,
        originalPosition: Int,
        relevanceNorm: Double
    ): RankedResult {
        val semanticScore = semanticScorer.calculateScore(
            title = result.title,
            snippet = result.snippet
        )

        // authority / freshness 已由 SearchResultContainer 折叠进 result.score，
        // 这里仅保留诊断记录，不再计入融合加权，避免双重计分（U33-1）。
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
        val positionScore = calculatePositionScore(originalPosition)

        // 三个分量均为 [0,1] 且权重和为 1.0，线性加权后天然落在 [0,1]，
        // 不再过 sigmoid（旧 sigmoid 在大输入下饱和钳顶，丢失区分度）。
        val baseScore =
            relevanceNorm * config.weights.relevance +
            semanticScore.combinedScore * config.weights.semantic +
            positionScore * config.weights.position

        // 引擎交叉验证作为相对乘子保留（仅影响排序，不做 [0,1] 截断以免再次饱和）
        val hybridScore = (baseScore * engineWeight).coerceAtLeast(0.0)

        return RankedResult(
            result = result,
            compositeScore = result.score,
            semanticScore = semanticScore,
            authorityScore = authorityScore,
            freshnessScore = freshnessScore,
            hybridScore = hybridScore,
            positionScore = positionScore
        ).also {
            DebugLog.d("[$TAG] hybridScore=${"%.4f".format(hybridScore)} | " +
                    "relevance=${"%.3f".format(relevanceNorm)} sem=${"%.3f".format(semanticScore.combinedScore)} " +
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
     * 位置分归一到 [0,1]：首位=1.0，随位置线性衰减 1/(pos+1)。
     * 与其它 [0,1] 分量同量程（旧实现返回 10/(pos+1)∈[0,10]，与 [0,1] 分量错配）。
     */
    private fun calculatePositionScore(position: Int): Double {
        val effectivePos = (position + 1).coerceAtLeast(1)
        return (1.0 / effectivePos).coerceIn(0.0, 1.0)
    }
}
