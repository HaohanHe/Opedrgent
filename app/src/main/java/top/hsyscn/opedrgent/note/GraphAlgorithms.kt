package top.hsyscn.opedrgent.note

import kotlin.math.abs

/**
 * 知识图谱图算法工具类。
 *
 * 提供社区检测、中心性计算等轻量实现，用于 NoteGraphScreen 的可视化增强。
 */
object GraphAlgorithms {

    /**
     * 使用标签传播算法检测社区。
     *
     * @param edges 无向边列表
     * @param weights 边权重映射（键为按字典序归一化的 Pair），缺省为 1.0
     * @param maxIterations 最大迭代次数
     * @return nodeId -> communityId 映射
     */
    fun detectCommunities(
        edges: List<KnowledgeGraph.GraphEdge>,
        weights: Map<Pair<String, String>, Float>? = null,
        maxIterations: Int = 15,
    ): Map<String, Int> {
        val nodeIds = (edges.map { it.sourceId } + edges.map { it.targetId }).distinct().sorted()
        if (nodeIds.isEmpty()) return emptyMap()

        val adjacency = mutableMapOf<String, MutableList<Pair<String, Float>>>()
        for (edge in edges) {
            val weight = weights?.get(canonical(edge.sourceId, edge.targetId)) ?: 1f
            adjacency.getOrPut(edge.sourceId) { mutableListOf() }.add(edge.targetId to weight)
            adjacency.getOrPut(edge.targetId) { mutableListOf() }.add(edge.sourceId to weight)
        }

        // 初始每个节点一个独立社区，保证确定性
        var communities = nodeIds.withIndex().associate { it.value to it.index }.toMutableMap()

        repeat(maxIterations) {
            var changed = false
            for (node in nodeIds) {
                val neighbors = adjacency[node] ?: continue
                val scores = mutableMapOf<Int, Double>()
                for ((neighbor, weight) in neighbors) {
                    val community = communities[neighbor] ?: continue
                    scores[community] = scores.getOrDefault(community, 0.0) + abs(weight)
                }
                if (scores.isEmpty()) continue
                // 取加权得分最高的社区，平局时取社区 ID 较小者，确保结果稳定
                val bestCommunity = scores.maxWithOrNull(
                    compareBy<Map.Entry<Int, Double>> { it.value }.thenBy { it.key },
                )?.key ?: continue
                if (communities[node] != bestCommunity) {
                    communities[node] = bestCommunity
                    changed = true
                }
            }
            if (!changed) return@repeat
        }

        // 压缩社区编号为 0..communityCount-1
        val uniqueCommunities = communities.values.distinct().sorted()
        val remap = uniqueCommunities.withIndex().associate { it.value to it.index }
        return communities.mapValues { remap[it.value] ?: 0 }
    }

    /**
     * 计算度中心性。
     *
     * @param nodes 节点 ID 列表
     * @param edges 无向边列表
     * @return nodeId -> [0,1] 中心性值
     */
    fun degreeCentrality(
        nodes: List<String>,
        edges: List<KnowledgeGraph.GraphEdge>,
    ): Map<String, Float> {
        val counts = mutableMapOf<String, Int>()
        for (edge in edges) {
            counts[edge.sourceId] = counts.getOrDefault(edge.sourceId, 0) + 1
            counts[edge.targetId] = counts.getOrDefault(edge.targetId, 0) + 1
        }
        val max = (nodes.size - 1).coerceAtLeast(1)
        return nodes.associateWith { (counts[it] ?: 0).toFloat() / max }
    }

    /**
     * 计算 PageRank（按无向图处理，每条边双向传播）。
     *
     * @param nodes 节点 ID 列表
     * @param edges 无向边列表
     * @param weights 边权重映射（键为按字典序归一化的 Pair），缺省为 1.0
     * @param alpha 阻尼系数
     * @param iterations 迭代次数
     * @return nodeId -> [0,1] 归一化 PageRank 值
     */
    fun pageRank(
        nodes: List<String>,
        edges: List<KnowledgeGraph.GraphEdge>,
        weights: Map<Pair<String, String>, Float>? = null,
        alpha: Float = 0.85f,
        iterations: Int = 30,
    ): Map<String, Float> {
        if (nodes.isEmpty()) return emptyMap()
        if (edges.isEmpty()) return nodes.associateWith { 0f }
        val nodeSet = nodes.toSet()

        val outNeighbors = mutableMapOf<String, MutableList<Pair<String, Float>>>()
        val inNeighbors = mutableMapOf<String, MutableList<Pair<String, Float>>>()
        for (edge in edges) {
            if (edge.sourceId !in nodeSet || edge.targetId !in nodeSet) continue
            val weight = (weights?.get(canonical(edge.sourceId, edge.targetId)) ?: 1f).coerceAtLeast(0f)
            outNeighbors.getOrPut(edge.sourceId) { mutableListOf() }.add(edge.targetId to weight)
            outNeighbors.getOrPut(edge.targetId) { mutableListOf() }.add(edge.sourceId to weight)
            inNeighbors.getOrPut(edge.targetId) { mutableListOf() }.add(edge.sourceId to weight)
            inNeighbors.getOrPut(edge.sourceId) { mutableListOf() }.add(edge.targetId to weight)
        }

        val n = nodes.size
        val init = 1f / n
        var ranks = nodes.associateWith { init }.toMutableMap()

        repeat(iterations) {
            val newRanks = mutableMapOf<String, Float>()
            for (node in nodes) {
                var rank = (1f - alpha) / n
                val incoming = inNeighbors[node] ?: emptyList()
                for ((source, weight) in incoming) {
                    val outgoing = outNeighbors[source] ?: emptyList()
                    val totalWeight = outgoing.sumOf { it.second.toDouble() }.toFloat()
                    if (totalWeight > 0f) {
                        rank += alpha * (ranks[source] ?: 0f) * weight / totalWeight
                    }
                }
                newRanks[node] = rank
            }
            ranks = newRanks
        }

        val maxRank = ranks.values.maxOrNull()?.coerceAtLeast(1e-9f) ?: 1f
        return ranks.mapValues { (_, value) -> value / maxRank }
    }

    private fun canonical(a: String, b: String): Pair<String, String> {
        return if (a < b) a to b else b to a
    }
}
