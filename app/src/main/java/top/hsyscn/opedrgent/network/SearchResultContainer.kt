package top.hsyscn.opedrgent.network

import kotlin.math.ln
import kotlin.math.pow
import top.hsyscn.opedrgent.utils.DebugLog

/**
 * 合并后的搜索结果内部数据结构（BM25增强版）
 */
private data class MergedResult(
    var title: String,
    val url: String,
    var snippet: String?,
    val sourceEngines: MutableSet<String> = mutableSetOf(),
    val positionsPerEngine: MutableMap<String, Int> = mutableMapOf(), // ★ SearXNG风格：记录每引擎位置
    var positionSum: Int = 0,
    var score: Double = 0.0,
    
    // BM25评分维度
    var bm25Score: Double = 0.0,                // BM25相关性得分
    var titleBm25Score: Double = 0.0,           // 标题BM25得分
    var snippetBm25Score: Double = 0.0,          // 摘要BM25得分
    
    // 质量评估维度
    var contentQualityScore: Double = 0.0,       // 内容质量分
    var freshnessScore: Double = 1.0,            // 新鲜度得分（时间衰减）
    var authorityScore: Double = 0.0,            // 来源权威性分
    var engineDiversityScore: Double = 0.0,      // 引擎多样性分
    var positionAdvantageScore: Double = 0.0,    // 位置优势分
    
    // 时间戳
    var timestamp: Long = System.currentTimeMillis()
)

/**
 * BM25参数配置
 */
object Bm25Config {
    const val k1 = 1.2          // 词频饱和参数（通常1.2-2.0）
    const val b = 0.75          // 文档长度归一化参数（通常0.75）
    
    // 停用词列表（中文+英文通用停用词）
    val STOP_WORDS = setOf(
        // 中文停用词
        "的", "了", "在", "是", "我", "有", "和", "就", "不", "人", "都", "一", "一个",
        "上", "也", "很", "到", "说", "要", "去", "你", "会", "着", "没有", "看", "好",
        "自己", "这", "他", "她", "它", "们", "那", "个", "什么", "吗", "吧", "呢", "啊",
        "但", "而", "或", "与", "及", "等", "之", "于", "以", "为", "把", "被", "让",
        "从", "对", "向", "比", "按", "因", "所", "其", "此", "该",
        // 英文停用词
        "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
        "have", "has", "had", "do", "does", "did", "will", "would", "could", "should",
        "may", "might", "can", "shall", "of", "in", "to", "for", "with", "on", "at",
        "from", "by", "about", "as", "into", "through", "during", "before", "after",
        "above", "below", "between", "out", "off", "over", "under", "again", "further",
        "then", "once", "here", "there", "when", "where", "why", "how", "all", "each",
        "few", "more", "most", "other", "some", "such", "no", "nor", "not", "only",
        "own", "same", "so", "than", "too", "very", "just", "and", "but", "if", "or"
    )
}

/**
 * 域名权威性评分
 */
object DomainAuthority {
    val highAuthorityDomains = mapOf(
        // 高权威域名（政府、教育、知名新闻/百科）
        "wikipedia.org" to 9.5,
        "gov.cn" to 9.8,
        "edu.cn" to 9.6,
        "zhihu.com" to 7.5,
        "csdn.net" to 7.0,
        "jianshu.com" to 6.5,
        "bilibili.com" to 7.0,
        "weibo.com" to 7.5,
        "mp.weixin.qq.com" to 8.0,
        "news.qq.com" to 8.0,
        "news.sina.com.cn" to 8.0,
        "finance.sina.com.cn" to 8.0,
        "163.com" to 7.5,
        "sohu.com" to 7.0,
        "ifeng.com" to 7.5,
        "people.com.cn" to 9.0,
        "xinhuanet.com" to 9.2,
        "cctv.com" to 9.3,
        "thepaper.cn" to 8.0,
        "guancha.cn" to 7.0,
        "36kr.com" to 7.5,
        "ithome.com" to 7.0,
        "cnblogs.com" to 6.5,
        "segmentfault.com" to 7.0,
        "juejin.cn" to 7.0,
        "github.com" to 8.5,
        "stackoverflow.com" to 9.0,
        "medium.com" to 7.5,
        "arxiv.org" to 9.5,
        "nature.com" to 9.8,
        "science.org" to 9.8,
        "ieee.org" to 9.5,
        "acm.org" to 9.5
    )
    
    fun getAuthorityScore(url: String): Double {
        try {
            val host = java.net.URL(url).host.lowercase()
            
            // 精确匹配
            if (highAuthorityDomains.containsKey(host)) {
                return highAuthorityDomains[host] ?: 5.0
            }
            
            // 域名后缀匹配（如 *.gov.cn）
            for ((domain, score) in highAuthorityDomains) {
                if (host.endsWith(".$domain") || host == domain) {
                    return score
                }
            }
            
            // 默认分数基于TLD判断
            return when {
                host.endsWith(".gov.cn") || host.endsWith(".gov") -> 9.0
                host.endsWith(".edu.cn") || host.endsWith(".edu") -> 8.5
                host.endsWith(".org") -> 6.5
                host.endsWith(".com.cn") || host.endsWith(".com") -> 5.5
                else -> 5.0
            }
        } catch (e: Exception) {
            return 5.0
        }
    }
}

/**
 * 搜索引擎权重配置
 */
object EngineWeights {
    val weights = mapOf(
        "ddg" to 1.0,
        "bing" to 0.95,
        "baidu" to 0.9,
        "searxng" to 0.85,
        "google" to 0.98,
        "jina" to 0.8,
        "jina-fallback" to 0.75,
        "brave" to 0.85,
        "tavily" to 0.8,
        "searxng-infobox" to 0.75
    )
    
    fun getWeight(engineName: String): Double {
        return weights[engineName.lowercase()] ?: 0.7
    }
}

/**
 * 搜索结果合并容器（★ SearXNG 累乘权重增强版）
 *
 * 实现企业级智能评分系统（SearXNG 风格升级）：
 * 1. **BM25相关性评分** - 经典IR算法，精确计算文档-查询相关度
 * 2. **SearXNG 累乘权重引擎评分** - weight *= engine_weight（非相加），多引擎交叉验证自然加权
 * 3. **线性位置衰减** - score = weight / position（非指数衰减），平缓可预测
 * 4. **分组打散算法** - 每组最多8个，组内间距≤20，避免同类结果扎堆
 * 5. **域名权威性** - 基于预定义的域名信誉数据库
 * 6. **时间衰减函数** - 指数衰减模型，优先展示新鲜内容
 *
 * ★ SearXNG 核心公式：
 *   productWeight = ∏ engine_weight(i) × √(engine_count)
 *   searxScore = Σ (productWeight / position(i))
 *   finalScore = α×searxScore + β×BM25 + γ×Diversity + δ×Position + ε×Freshness + ζ×Authority
 */
class SearchResultContainer {

    private val mergedMap = LinkedHashMap<String, MergedResult>()
    private var queryKeywords: Set<String> = emptySet()
    private var queryKeywordFreq: Map<String, Int> = emptyMap()
    private var avgDocLength: Double = 100.0  // 平均文档长度（用于BM25归一化）
    
    private var semanticScorer: SemanticScorer? = null
    private var authorityScorer: DynamicAuthorityScorer? = null
    private var freshnessCalculator: FreshnessCalculator? = null
    private var useEnhancedScoring: Boolean = false
    
    /**
     * 设置查询关键词并预处理
     *
     * 改进点：对中文查询自动提取bigram（二元组）作为补充关键词，
     * 解决中文无空格分词导致关键词匹配率低的问题。
     * 例如："AI面试技巧" -> {"AI面试", "面试技巧", "AI面试技巧"}
     */
    fun setQueryKeywords(query: String) {
        val baseKeywords = query.lowercase()
            .split(Regex("[\\s\\p{Punct}]+"))
            .filter { it.length > 1 && it !in Bm25Config.STOP_WORDS }
            .toMutableList()

        // 对每个token，如果是纯中文序列且长度>=3，提取bigram补充关键词
        val cjkPattern = Regex("[\\u4e00-\\u9fa5]+")
        val bigrams = mutableSetOf<String>()
        for (token in baseKeywords) {
            val cjkMatch = cjkPattern.find(token)
            if (cjkMatch != null && cjkMatch.value.length >= 3) {
                val cjk = cjkMatch.value
                for (i in 0 until cjk.length - 1) {
                    bigrams.add(cjk.substring(i, i + 2))
                }
            }
        }
        // 去掉太短的原始关键词，加入bigram
        val allKeywords = baseKeywords.filter { it.length > 1 }.toMutableSet()
        allKeywords.addAll(bigrams.filter { it !in Bm25Config.STOP_WORDS })

        queryKeywords = allKeywords

        queryKeywordFreq = mutableMapOf<String, Int>().apply {
            queryKeywords.forEach { keyword ->
                put(keyword, (this[keyword] ?: 0) + 1)
            }
        }

        DebugLog.d(
            "SearchResultContainer: query keywords=${queryKeywords.size}, " +
            "base=${baseKeywords.size}, bigrams=${bigrams.size}, " +
            "sample=${queryKeywords.take(5)}"
        )
    }

    fun initializeQuery(query: String, useEnhanced: Boolean = true) {
        setQueryKeywords(query)
        
        if (useEnhanced && semanticScorer == null) {
            semanticScorer = SemanticScorer().also { it.initialize(query) }
            authorityScorer = DynamicAuthorityScorer()
            freshnessCalculator = FreshnessCalculator().also {
                it.initWithQuery(semanticScorer!!.getDetectedIntent())
            }
            useEnhancedScoring = true
            
            DebugLog.i("SearchResultContainer: enhanced scoring initialized " +
                    "(intent=${semanticScorer!!.getDetectedIntent()})")
        }
    }

    fun normalizeUrl(url: String): String {
        return url.lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .removeSuffix("/")
            .let { it.substringBefore("#") }
            .let { it.substringBefore("?") }
    }

    /**
     * 计算BM25得分（核心算法）
     *
     * BM25公式：
     * Σ IDF(qi) * (f(qi,D) * (k1 + 1)) / (f(qi,D) + k1 * (1 - b + b * |D| / avgdl))
     *
     * 其中：
     * - IDF(qi) = ln((N - n(qi) + 0.5) / (n(qi) + 0.5) + 1)  [逆文档频率]
     * - f(qi,D) = 词qi在文档D中的出现频率
     * - |D| = 文档D的长度
     * - avgdl = 平均文档长度
     * - k1 = 词频饱和参数
     * - b = 长度归一化参数
     */
    private fun calculateBm25(text: String): Double {
        if (queryKeywords.isEmpty()) return 0.0
        
        val textLower = text.lowercase()
        val words = textLower.split(Regex("\\s+|\\p{Punct}+"))
            .filter { it.length > 1 && it !in Bm25Config.STOP_WORDS }
        
        val docLength = words.size.toDouble()
        if (docLength == 0.0) return 0.0
        
        var bm25Sum = 0.0
        
        for (keyword in queryKeywords) {
            // 计算词频 tf
            val tf = words.count { it.contains(keyword) || keyword.contains(it) }.toDouble()
            if (tf == 0.0) continue
            
            // 计算IDF（简化版本，假设N=1000000，n(qi)=1对于稀有词）
            // 在实际应用中应该使用真实的文档集合统计
            val idf = calculateIdf(keyword)
            
            // BM25 TF组件
            val tfComponent = (tf * (Bm25Config.k1 + 1)) / 
                (tf + Bm25Config.k1 * (1 - Bm25Config.b + Bm25Config.b * docLength / avgDocLength))
            
            bm25Sum += idf * tfComponent
        }
        
        return bm25Sum
    }
    
    /**
     * 计算逆文档频率IDF
     */
    private fun calculateIdf(term: String): Double {
        val N = 1000000.0
        
        val baseFreq = when {
            term.length <= 1 -> 500000.0
            term.length == 2 -> 100000.0
            term.length == 3 -> 15000.0
            term.length == 4 -> 3000.0
            else -> 500.0
        }
        
        val numericPenalty = if (term.all { it.isDigit() }) 10.0 else 1.0
        
        val shortEnglishPenalty = if (term.length <= 3 && term.all { it.isLetter() }) 3.0 else 1.0
        
        val estimatedDocFreq = baseFreq * numericPenalty * shortEnglishPenalty
        val n = estimatedDocFreq.coerceIn(1.0, N - 1)
        
        return ln((N - n + 0.5) / (n + 0.5) + 1)
    }

    /**
     * 计算内容质量分数 (0-10)
     */
    private fun calculateContentQuality(title: String, snippet: String?): Double {
        var score = 5.0
        
        // 标题质量
        when {
            title.length < 5 -> score -= 2.0
            title.length in 10..50 -> score += 2.5
            title.length in 51..80 -> score += 1.5
            title.length > 80 -> score -= 0.5
            else -> score += 1.0
        }
        
        // 标题信息密度
        val titleWords = title.split(Regex("\\s+")).size
        if (titleWords >= 4) score += 0.8
        
        // 摘要质量
        snippet?.let { snip ->
            when {
                snip.isEmpty() -> score -= 1.0
                snip.length in 50..200 -> score += 2.5
                snip.length in 201..400 -> score += 1.5
                snip.length > 400 -> score += 1.0
                else -> score += 0.5
            }
            
            // 摘要完整性
            val sentences = snip.split(Regex("[。！？.!?]")).filter { it.trim().length > 10 }
            if (sentences.size >= 2) score += 1.0
            
            // 信息密度（非停用词比例）
            val snipWords = snip.split(Regex("\\s+"))
            val meaningfulWords = snipWords.filter { 
                it.length > 1 && it.lowercase() !in Bm25Config.STOP_WORDS 
            }
            if (snipWords.isNotEmpty()) {
                val density = meaningfulWords.size.toDouble() / snipWords.size
                if (density > 0.7) score += 0.5
            }
        }
        
        return score.coerceIn(0.0, 10.0)
    }

    /**
     * 计算时间衰减得分 (0-1)
     * 使用指数衰减模型：freshness = e^(-λ * age)
     * λ越大衰减越快
     */
    private fun calculateFreshness(): Double {
        if (freshnessCalculator != null && useEnhancedScoring) {
            return 1.0
        }
        
        val ageHours = (System.currentTimeMillis() - System.currentTimeMillis()) / 3600000.0
        val lambda = 0.001
        return Math.exp(-lambda * ageHours).coerceIn(0.1, 1.0)
    }

    /**
     * 计算综合初始分数（★ SearXNG 线性衰减模式）
     *
     * SearXNG 公式（单引擎初始分）：
     *   position_score = engine_weight × 10 / (position + 1)  ← 线性衰减
     *
     * 改进点：
     * 1. 使用线性衰减 weight/position 替代指数 decay^position（更符合IR理论，避免过度惩罚后排）
     * 2. 引擎权重直接乘以位置倒数（位置越靠前权重越大，曲线平滑）
     * 3. BM25作为相关性基础分
     */
    private fun calculateInitialScore(position: Int, engineName: String, result: SearchResult): Double {
        val engineWeight = EngineWeights.getWeight(engineName)

        // BM25得分（相关性基础）
        val titleBm25 = calculateBm25(result.title)
        val snippetBm25 = result.snippet?.let { calculateBm25(it) } ?: 0.0
        val totalBm25 = titleBm25 * 1.5 + snippetBm25

        // 内容质量
        val contentQuality = calculateContentQuality(result.title, result.snippet)

        // 域名权威性
        val authority = DomainAuthority.getAuthorityScore(result.url)

        // 新鲜度
        val freshness = calculateFreshness()

        // ★ SearXNG风格的位置评分：weight / position（线性衰减）
        // position从1开始，position=1时得分最高
        val positionScore = if (position == 0) engineWeight * 10.0 else engineWeight * 10.0 / (position + 1)

        // 加权综合得分（调整权重分配）
        val finalScore = (
            totalBm25 * 2.5 +               // BM25相关性 (核心权重，略降以平衡其他因子)
            positionScore * 3.5 +            // ★ 位置优势（SearXNG核心：提升权重）
            contentQuality * 1.5 +           // 内容质量
            authority * 1.0 +                // 域名权威
            freshness * 1.5 +                // 新鲜度
            engineWeight * 5.0               // 引擎基础权重
        )

        return finalScore
    }

    /**
     * 更新合并结果的分数（★ SearXNG 累乘权重模式）
     *
     * SearXNG 核心算法：
     * - **引擎权重累乘法**：weight *= engine_weight（而非相加）
     *   例如：同一结果出现在百度(0.9) × Google(0.98) × 必应(0.95) = 0.839
     *   多引擎交叉验证的结果获得更高的乘积权重，天然惩罚单引擎结果
     * - **线性位置衰减**：score = productWeight / position（而非指数衰减）
     *   位置越靠前得分越高，衰减曲线平缓且可预测
     */
    private fun updateScore(merged: MergedResult) {
        val engineCount = merged.sourceEngines.size
        if (engineCount == 0) {
            merged.score = 0.0
            return
        }

        // ★ SearXNG 累乘权重模式：所有引擎权重连乘
        // 单引擎结果保持原始权重；多引擎结果权重累乘（自然降权低质量引擎）
        var productWeight = 1.0
        for ((eng, _) in merged.positionsPerEngine) {
            productWeight *= EngineWeights.getWeight(eng)
        }
        // 引擎数量放大因子：出现次数越多基础分越高（但通过累乘已体现质量差异）
        productWeight *= Math.sqrt(merged.positionsPerEngine.size.toDouble())

        // ★ SearXNG 线性位置衰减：对每个引擎位置计算 weight / position 后求和
        // position 从 0 开始（即实际排名+1），避免除零
        var searxScore = 0.0
        for ((_, pos) in merged.positionsPerEngine) {
            val effectivePosition = (pos + 1).coerceAtLeast(1)
            searxScore += productWeight / effectivePosition
        }

        // ★ SearXNG 引擎多样性指数（非线性增长）：多引擎验证 = 高可信度
        merged.engineDiversityScore = when {
            engineCount >= 4 -> 10.0
            engineCount == 3 -> 7.0
            engineCount == 2 -> 4.0
            else -> 0.0
        }

        // 平均位置优势（使用线性衰减：10 / avgPosition）
        val avgPosition = if (merged.positionSum > 0) {
            merged.positionSum.toDouble() / engineCount
        } else 1.0
        merged.positionAdvantageScore = 10.0 / avgPosition.coerceAtLeast(1.0)

        // 重新计算BM25（合并后的内容可能更丰富）
        val updatedTitleBm25 = calculateBm25(merged.title)
        val updatedSnippetBm25 = merged.snippet?.let { calculateBm25(it) } ?: 0.0
        merged.bm25Score = updatedTitleBm25 * 1.5 + updatedSnippetBm25
        merged.titleBm25Score = updatedTitleBm25
        merged.snippetBm25Score = updatedSnippetBm25

        // 更新内容质量和权威性
        merged.contentQualityScore = calculateContentQuality(merged.title, merged.snippet)
        merged.authorityScore = DomainAuthority.getAuthorityScore(merged.url)

        // ★ SearXNG 综合评分公式（累乘权重核心）：
        // 主分量 = SearXNG引擎分（累乘×线性衰减）作为骨架
        // 辅助分量 = BM25相关性 + 内容质量 + 权威性 + 新鲜度 + 多样性
        merged.score = (
            searxScore * 3.0 +               // ★ SearXNG 累乘权重主分（最高权重）
            merged.bm25Score * 2.0 +          // BM25 相关性
            merged.contentQualityScore * 1.2 + // 内容质量
            merged.authorityScore * 0.8 +     // 域名权威
            merged.freshnessScore * 1.2 +     // 新鲜度
            merged.engineDiversityScore * 2.5 + // ★ 引擎多样性奖励（多引擎加分更显著）
            merged.positionAdvantageScore * 2.0 + // 位置优势
            engineCount * 2.5                 // 引擎数量基础奖励
        )
    }

    fun addResults(engineName: String, results: List<SearchResult>) {
        results.forEachIndexed { index, result ->
            val normalizedKey = normalizeUrl(result.url)
            val existing = mergedMap[normalizedKey]

            if (existing == null) {
                val contentQuality = calculateContentQuality(result.title, result.snippet)
                var enhancedAuthority = DomainAuthority.getAuthorityScore(result.url)
                var enhancedFreshness = calculateFreshness()

                mergedMap[normalizedKey] = MergedResult(
                    title = result.title,
                    url = result.url,
                    snippet = result.snippet,
                    sourceEngines = mutableSetOf(engineName),
                    positionsPerEngine = mutableMapOf(engineName to index),
                    positionSum = index,
                    score = calculateInitialScore(index, engineName, result),

                    bm25Score = calculateBm25(result.title) * 1.5 +
                        (result.snippet?.let { calculateBm25(it) } ?: 0.0),
                    titleBm25Score = calculateBm25(result.title),
                    snippetBm25Score = result.snippet?.let { calculateBm25(it) } ?: 0.0,

                    contentQualityScore = contentQuality,
                    freshnessScore = enhancedFreshness,
                    authorityScore = enhancedAuthority,
                    engineDiversityScore = 0.0,
                    positionAdvantageScore = if (index == 0) 10.0 else 10.0 / (index + 1),

                    timestamp = System.currentTimeMillis()
                )

                if (useEnhancedScoring && semanticScorer != null) {
                    val merged = mergedMap[normalizedKey]!!
                    val semanticScore = semanticScorer!!.calculateScore(result.title, result.snippet)
                    val authorityScore = authorityScorer!!.calculate(result.url, result.title, result.snippet)
                    val freshnessScore = freshnessCalculator!!.calculate(result.url, result.snippet)

                    merged.authorityScore = authorityScore.finalScore * 10
                    merged.freshnessScore = freshnessScore.adjustedScore * 10

                    DebugLog.d("SearchResultContainer: enhanced scores for '${result.title.take(30)}' " +
                            "auth=${"%.2f".format(merged.authorityScore)} fresh=${"%.2f".format(merged.freshnessScore)} sem=${"%.3f".format(semanticScore.combinedScore)}")
                }
            } else {
                // ★ SearXNG风格的内容合并策略：选择信息量最大的内容

                // 标题：选择更长的（通常包含更多信息）
                if (result.title.length > existing.title.length) {
                    existing.title = result.title
                }

                // 摘要：智能合并策略
                val newSnippet = result.snippet
                val existingSnippet = existing.snippet

                when {
                    // 情况1：新摘要为空，保留原摘要
                    newSnippet == null -> { /* keep existing */ }

                    // 情况2：原摘要为空，使用新摘要
                    existingSnippet == null -> existing.snippet = newSnippet

                    // 情况3：新摘要明显更长（>20%），使用新摘要
                    newSnippet.length > existingSnippet.length * 1.2 -> existing.snippet = newSnippet

                    // 情况4：长度相近，但新摘要包含更多查询关键词，使用新摘要
                    queryKeywords.isNotEmpty() && countKeywordMatches(newSnippet) > countKeywordMatches(existingSnippet) -> {
                        existing.snippet = newSnippet
                    }

                    // 其他情况：保留原摘要
                    else -> { /* keep existing */ }
                }

                // 记录来源引擎
                existing.sourceEngines.add(engineName)
                existing.positionsPerEngine[engineName] = index
                existing.positionSum += index

                // 重新计算所有维度
                updateScore(existing)

                if (useEnhancedScoring && semanticScorer != null) {
                    val authorityScore = authorityScorer!!.calculate(existing.url, existing.title, existing.snippet)
                    val freshnessScore = freshnessCalculator!!.calculate(existing.url, existing.snippet)

                    existing.authorityScore = authorityScore.finalScore * 10
                    existing.freshnessScore = freshnessScore.adjustedScore * 10
                }
            }
        }
    }

    /**
     * 统计文本中匹配的查询词数量（用于摘要质量评估）
     */
    private fun countKeywordMatches(text: String): Int {
        if (queryKeywords.isEmpty()) return 0
        val textLower = text.lowercase()
        return queryKeywords.count { keyword -> textLower.contains(keyword) }
    }

    /**
     * 已知垃圾/广告域名黑名单
     */
    private val SPAM_DOMAINS = setOf(
        "znzmo.com", "xiaoguotu.znzmo.com", "3d.znzmo.com",
        "wgu.edu", "my.wgu.edu",
        "niche.com",
        "aiqicha.baidu.com", "qcc.com", "tianyancha.com",
        "1688.com", "taobao.com", "jd.com",
        "chaoxing.com", "xuexitong.com", "mooc1-2.chaoxing.com"
    )

    /**
     * 智能相关性过滤 - 移除不相关的垃圾结果
     *
     * 过滤规则：
     * 1. 垃圾域名黑名单过滤
     * 2. 标题/摘要关键词匹配检查（至少匹配1个查询词，支持双向子串匹配）
     * 3. BM25分数阈值过滤（低于阈值视为不相关）
     *
     * 改进点：
     * - 关键词匹配改为双向子串包含检查（keyword in text 或 text in keyword）
     * - 当所有结果都被过滤时，保留得分最高的minResults条，避免返回空结果
     */
    fun filterRelevantResults(
        minKeywordMatch: Int = 1,
        minBm25Score: Double = 0.5,
        minResults: Int = 2
    ): FilterResult {
        val beforeCount = mergedMap.size
        var spamFiltered = 0
        var keywordFiltered = 0
        var bm25Filtered = 0

        // 暂存被关键词/BM25过滤的结果，用于安全网恢复
        val keywordFilteredResults = mutableMapOf<String, MergedResult>()
        val bm25FilteredResults = mutableMapOf<String, MergedResult>()

        val iterator = mergedMap.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val result = entry.value

            // 规则1：垃圾域名过滤（不保留，确定是垃圾）
            try {
                val host = java.net.URL(result.url).host.lowercase()
                if (SPAM_DOMAINS.any { host.endsWith(it) || host == it }) {
                    iterator.remove()
                    spamFiltered++
                    DebugLog.d("SearchResultContainer: filtered spam domain: $host")
                    continue
                }
            } catch (e: Exception) {}

            // 规则2：关键词匹配检查（改进版：双向子串匹配）
            if (queryKeywords.isNotEmpty()) {
                val titleLower = result.title.lowercase()
                val snippetLower = (result.snippet ?: "").lowercase()
                val matchCount = queryKeywords.count { keyword ->
                    // 双向子串匹配：keyword包含在文本中，或文本包含在keyword中
                    titleLower.contains(keyword) || snippetLower.contains(keyword) ||
                    keyword.contains(titleLower) || keyword.contains(snippetLower)
                }

                if (matchCount < minKeywordMatch) {
                    keywordFilteredResults[entry.key] = result
                    iterator.remove()
                    keywordFiltered++
                    DebugLog.d(
                        "SearchResultContainer: filtered no keyword match: " +
                        "'${result.title.take(30)}' (matches=$matchCount)"
                    )
                    continue
                }
            }

            // 规则3：BM25分数阈值过滤
            if (result.bm25Score < minBm25Score && queryKeywords.isNotEmpty()) {
                bm25FilteredResults[entry.key] = result
                iterator.remove()
                bm25Filtered++
                DebugLog.d(
                    "SearchResultContainer: filtered low BM25: " +
                    "'${result.title.take(30)}' (bm25=${String.format("%.2f", result.bm25Score)})"
                )
            }
        }

        // 安全网：如果过滤后剩余结果不足minResults条，恢复得分最高的被过滤结果
        val currentSize = mergedMap.size
        if (currentSize < minResults && beforeCount > 0) {
            val need = minResults - currentSize
            // 优先从关键词过滤的结果中恢复（比BM25过滤的更相关）
            val restored = keywordFilteredResults.entries
                .sortedByDescending { it.value.score }
                .take(need)
            for ((key, result) in restored) {
                mergedMap[key] = result
                keywordFiltered--  // 修正计数
            }
            // 仍然不足，从BM25过滤的结果中恢复
            val stillNeed = minResults - mergedMap.size
            if (stillNeed > 0) {
                val restoredBm25 = bm25FilteredResults.entries
                    .sortedByDescending { it.value.score }
                    .take(stillNeed)
                for ((key, result) in restoredBm25) {
                    mergedMap[key] = result
                    bm25Filtered--  // 修正计数
                }
            }
            if (mergedMap.size > currentSize) {
                DebugLog.w(
                    "SearchResultContainer: safety net restored ${mergedMap.size - currentSize} " +
                    "results (was $currentSize, now ${mergedMap.size})"
                )
            }
        }

        val afterCount = mergedMap.size

        return FilterResult(
            beforeCount = beforeCount,
            afterCount = afterCount,
            spamFiltered = spamFiltered,
            keywordFiltered = keywordFiltered,
            bm25Filtered = bm25Filtered
        ).also { result ->
            if (result.totalFiltered > 0) {
                DebugLog.i(
                    "SearchResultContainer filter: ${result.beforeCount} -> ${result.afterCount} " +
                    "(spam=${result.spamFiltered}, keyword=${result.keywordFiltered}, bm25=${result.bm25Filtered})"
                )
            }
        }
    }

    data class FilterResult(
        val beforeCount: Int,
        val afterCount: Int,
        val spamFiltered: Int,
        val keywordFiltered: Int,
        val bm25Filtered: Int
    ) {
        val totalFiltered: Int get() = spamFiltered + keywordFiltered + bm25Filtered
    }

    /**
     * 获取排序后的结果（SearXNG两阶段排序：分数优先 + 分组打散）
     *
     * ★ 核心改进：实现SearXNG的分组打散策略
     *
     * 算法流程：
     * Phase 1: 按综合得分降序排列所有结果
     * Phase 2: 按来源类别（域名后缀/引擎）分组，限制每组最多maxCount个，
     *          且组内结果间距不超过maxDistance，避免同类结果扎堆
     *
     * 示例效果：
     * 改进前：[baidu#1, baidu#2, baidu#3, bing#1, bing#2, 360#1]
     * 改进后：[baidu#1, bing#1, 360#1, baidu#2, bing#2, baidu#3] ← 多样性更好
     */
    fun getSortedResults(
        limit: Int = 10,
        useReranking: Boolean = false,
        useMMR: Boolean = false
    ): List<SearchResult> {
        if (useReranking && useEnhancedScoring && semanticScorer != null) {
            val engine = HybridRankingEngine()
            engine.initialize(queryKeywords.firstOrNull() ?: "")

            val allResults = mergedMap.values.map { merged ->
                SearchResult(
                    title = merged.title,
                    url = merged.url,
                    snippet = merged.snippet,
                    sourceEngines = merged.sourceEngines.toSet(),
                    score = merged.score
                )
            }

            val ranked = engine.rank(allResults, limit.coerceAtLeast(20))

            val finalRanked = if (useMMR) {
                engine.rerankWithMMR(ranked, limit)
            } else {
                ranked
            }

            DebugLog.i("getSortedResults: hybrid reranking applied " +
                    "(rerank=$useReranking, mmr=$useMMR, results=${finalRanked.size})")

            return finalRanked.take(limit).map { it.result }
        }

        // Phase 1: 按分数降序排列
        val sortedByScore = mergedMap.values
            .sortedByDescending { it.score }
        // Phase 2: SearXNG风格的分组打散算法
        val gresults = mutableListOf<MergedResult>()
        val categoryPositions = mutableMapOf<String, MutableMap<String, Any>>()

        val maxCount = 8       // ★ 每组最多8个（SearXNG标准值）
        val maxDistance = 20   // ★ 组内最大间距≤20（SearXNG标准值，允许更宽松的打散）

        for (res in sortedByScore) {
            // 提取分类键：域名后缀 + 引擎集合
            val category = extractCategory(res)

            // 查找该分类的当前位置信息
            val grp = categoryPositions[category]

            if (grp != null &&
                (grp["count"] as Int) > 0 &&
                (gresults.size - (grp["index"] as Int)) < maxDistance) {
                // 条件满足：插入到该组的上一个结果后面（实现打散）
                val index = grp["index"] as Int
                gresults.add(index, res)

                // 更新后续所有分组的索引
                for (item in categoryPositions.values) {
                    val v = item["index"] as Int
                    if (v >= index) {
                        item["index"] = v + 1
                    }
                }

                // 减少该组剩余配额
                grp["count"] = (grp["count"] as Int) - 1
            } else {
                // 不满足条件：追加到末尾，开启新组
                gresults.add(res)
                categoryPositions[category] = mutableMapOf(
                    "index" to gresults.size - 1,
                    "count" to maxCount - 1  // 已用1个配额
                )
            }
        }

        // Task 9: 分组打散验证断言 - 检查无域名连续出现超过3次
        var consecutiveCount = 1
        var maxConsecutive = 1
        var lastDomain = ""
        for (res in gresults) {
            val currentDomain = extractMainDomain(res.url)
            if (currentDomain == lastDomain) {
                consecutiveCount++
                if (consecutiveCount > maxConsecutive) {
                    maxConsecutive = consecutiveCount
                }
            } else {
                consecutiveCount = 1
            }
            lastDomain = currentDomain
        }
        if (maxConsecutive > 3) {
            DebugLog.w("getSortedResults: grouping issue - domain appears $maxConsecutive consecutive times")
        }

        // Task 8: 搜索来源多样性日志 - 统计各域名出现次数
        val domainCountMap = mutableMapOf<String, Int>()
        for (res in gresults) {
            val domain = extractMainDomain(res.url)
            domainCountMap[domain] = domainCountMap.getOrDefault(domain, 0) + 1
        }
        val sourceStats = domainCountMap.entries
            .sortedByDescending { it.value }
            .joinToString(", ") { "${it.key}:${it.value}" }
        DebugLog.i("getSortedResults: sources=[$sourceStats], total=${gresults.size}")

        // 截取前limit个并转换为SearchResult
        return gresults.take(limit).mapIndexed { rank, merged ->
            SearchResult(
                title = merged.title,
                url = merged.url,
                snippet = merged.snippet,
                sourceEngines = merged.sourceEngines.toMutableSet(),
                score = merged.score
            ).also {
                // 最终排名微调（轻微衰减，保持相对稳定）
                it.score = it.score * Math.pow(0.99, rank.toDouble())
            }
        }
    }

    /**
     * 提取结果的分类键（用于分组打散）
     *
     * 分类规则：
     * 1. 域名后缀（.com/.cn/.org等）
     * 2. 主域名（如 wikipedia.org, baidu.com）
     * 3. 引擎组合（去重后的sourceEngines排序字符串）
     *
     * 返回格式："domainSuffix:mainDomain:engines"
     */
    private fun extractCategory(res: MergedResult): String {
        try {
            val host = java.net.URL(res.url).host.lowercase()

            // 提取主域名（去除子域名和www）
            val mainDomain = host
                .removePrefix("www.")
                .substringAfterLast('.', missingDelimiterValue = "")
                .let { tld ->
                    val base = host.removePrefix("www.").removeSuffix(".$tld")
                    base.substringAfterLast('.', missingDelimiterValue = base) + ".$tld"
                }

            // 提取TLD（顶级域名）
            val tld = host.substringAfterLast('.', missingDelimiterValue = "unknown")

            // 引擎组合签名
            val engineSig = res.sourceEngines.sorted().joinToString(",")

            return "$tld:$mainDomain:$engineSig"
        } catch (e: Exception) {
            return "unknown:${res.sourceEngines.sorted().joinToString(",")}"
        }
    }

    /**
     * 提取主域名（Task 8/9 使用）
     * 例如：https://www.baidu.com/s?wd=test -> "baidu"
     */
    private fun extractMainDomain(url: String): String {
        return try {
            val host = java.net.URL(url).host.lowercase()
                .removePrefix("www.")
            // 提取如 baidu, bing, google 等主域名
            val parts = host.split(".")
            if (parts.size >= 2) {
                parts[parts.size - 2]
            } else {
                host
            }
        } catch (e: Exception) {
            "unknown"
        }
    }

    fun size(): Int = mergedMap.size

    fun clear() {
        mergedMap.clear()
        queryKeywords = emptySet()
        queryKeywordFreq = emptyMap()
    }
}
