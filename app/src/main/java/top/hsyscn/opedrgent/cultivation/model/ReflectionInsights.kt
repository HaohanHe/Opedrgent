package top.hsyscn.opedrgent.cultivation.model

import top.hsyscn.opedrgent.cultivation.store.ReflectionRecord

/** 某条优势特质被跨次复盘观察到的次数与一条示例原句。 */
data class TraitFreq(
    val trait: String,
    val count: Int,
    val example: String,
)

/** 某个长期模式被跨次复盘提到的次数。 */
data class PatternFreq(
    val pattern: String,
    val count: Int,
)

/**
 * 成长全景的确定性聚合（P2）。
 *
 * 立场：这里只对模型已经产出的结构化字段（优势特质 / 模式 / 跟进状态）做计数与归并，
 * 属于字符串级统计，不对用户语义做任何新判断——不做关键词命中、不做语义聚类、不贴标签。
 * 特质 / 模式只有在归一化后文本完全一致时才合并；措辞不同就如实分别呈现，把“是否同一种特质”
 * 的判断留给模型在下次复盘中结合长期上下文完成。随记录增多，真正稳定的优势与模式会自然浮现，
 * 呼应“跨时间、跨场景拼出全景信息、让长期优势显影”的目标。
 */
data class ReflectionInsights(
    val totalSessions: Int,
    val critiqueCount: Int,
    val exemplarCount: Int,
    val traitFreqs: List<TraitFreq>,
    val patternFreqs: List<PatternFreq>,
    val followUpTotal: Int,
    val followUpDone: Int,
    val followUpOpen: Int,
    val followUpDropped: Int,
    val activeDays: Int,
    val firstAt: Long,
    val lastAt: Long,
) {
    /** 跟进完成率：已做到 / 全部跟进；无跟进时为 0。 */
    val followUpDoneRate: Float
        get() = if (followUpTotal == 0) 0f else followUpDone.toFloat() / followUpTotal

    companion object {
        val EMPTY = ReflectionInsights(0, 0, 0, emptyList(), emptyList(), 0, 0, 0, 0, 0, 0L, 0L)

        private const val TOP_N = 6
        private const val DAY_MS = 86_400_000L

        fun from(records: List<ReflectionRecord>): ReflectionInsights {
            if (records.isEmpty()) return EMPTY

            val critiqueCount = records.count { it.lens == ReflectionLens.CRITIQUE }
            val exemplarCount = records.count { it.lens == ReflectionLens.EXEMPLAR }

            // 优势特质归并：归一化文本一致才计数，保留首个非空逐字原句作示例
            val traitMap = LinkedHashMap<String, TraitAgg>()
            val patternMap = LinkedHashMap<String, Int>()
            var fuTotal = 0; var fuDone = 0; var fuOpen = 0; var fuDropped = 0
            val daySet = HashSet<Long>()
            var firstAt = Long.MAX_VALUE
            var lastAt = 0L

            // 历史是新→旧，反向遍历使 firstAt/示例更稳定；计数不受方向影响
            for (r in records.asReversed()) {
                daySet += r.createdAt / DAY_MS
                if (r.createdAt < firstAt) firstAt = r.createdAt
                if (r.createdAt > lastAt) lastAt = r.createdAt

                r.critique?.let { c ->
                    c.strengths.forEach { s ->
                        val key = normalize(s.trait)
                        if (key.isNotBlank()) {
                            val agg = traitMap.getOrPut(key) { TraitAgg(s.trait.trim(), 0, "") }
                            agg.count += 1
                            if (agg.example.isBlank() && s.quote.isNotBlank()) agg.example = s.quote
                        }
                    }
                    c.patterns.forEach { p ->
                        val key = normalize(p.pattern)
                        if (key.isNotBlank()) patternMap[key] = (patternMap[key] ?: 0) + 1
                    }
                }
                val followUps = r.critique?.followUps ?: r.exemplar?.followUps ?: emptyList()
                followUps.forEach { f ->
                    if (f.text.isBlank()) return@forEach
                    fuTotal += 1
                    when (f.status) {
                        FollowUpStatus.DONE -> fuDone += 1
                        FollowUpStatus.OPEN -> fuOpen += 1
                        FollowUpStatus.DROPPED -> fuDropped += 1
                    }
                }
            }

            val traitFreqs = traitMap.values
                .sortedWith(compareByDescending<TraitAgg> { it.count }.thenBy { it.display })
                .take(TOP_N)
                .map { TraitFreq(it.display, it.count, it.example) }
            val patternFreqs = patternMap.entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(TOP_N)
                .map { PatternFreq(it.key, it.value) }

            return ReflectionInsights(
                totalSessions = records.size,
                critiqueCount = critiqueCount,
                exemplarCount = exemplarCount,
                traitFreqs = traitFreqs,
                patternFreqs = patternFreqs,
                followUpTotal = fuTotal,
                followUpDone = fuDone,
                followUpOpen = fuOpen,
                followUpDropped = fuDropped,
                activeDays = daySet.size,
                firstAt = if (firstAt == Long.MAX_VALUE) 0L else firstAt,
                lastAt = lastAt,
            )
        }

        /** 仅做空白与句末标点级归一化，不做任何语义归并。 */
        private fun normalize(s: String): String =
            s.trim().trimEnd('。', '，', '.', '！', '!', '？', '?', '；', ';', ' ').lowercase()

        private class TraitAgg(var display: String, var count: Int, var example: String)
    }
}
