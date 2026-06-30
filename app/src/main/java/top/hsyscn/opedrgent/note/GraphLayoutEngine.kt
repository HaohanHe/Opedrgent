package top.hsyscn.opedrgent.note

import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import top.hsyscn.opedrgent.ui.theme.SizeTokens
import top.hsyscn.opedrgent.ui.theme.SpacingTokens
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 知识图谱可视化中的节点数据类。
 *
 * @param id 节点唯一标识（通常为笔记 ID 字符串）
 * @param label 节点显示标签
 * @param linkCount 节点关联数（用于 UI 展示，不参与布局计算）
 * @param noteId 对应笔记 ID
 */
data class GraphNode(
    val id: String,
    val label: String,
    val linkCount: Int,
    val noteId: Long,
)

/**
 * 力导向布局计算结果。
 *
 * @param positions 节点 ID 到屏幕坐标（px）的映射；坐标系原点为 Canvas 中心
 * @param bounds 布局包围盒（px），包含节点半径
 */
data class GraphLayout(
    val positions: Map<String, Offset>,
    val bounds: RectF,
)

/**
 * 知识图谱力导向布局引擎。
 *
 * 将 [NoteGraphScreen] 中的布局计算逻辑独立出来，使 UI 层只负责渲染与交互。
 * 本引擎负责：
 * - 圆盘内随机初始化（避免节点排成花圈）
 * - 斥力、弹簧引力、向心力迭代
 * - 硬碰撞检测，防止节点重叠
 * - 边界约束与收敛检测
 * - 布局包围盒计算
 */
object GraphLayoutEngine {

    /**
     * 计算力导向布局。
     *
     * @param nodes 节点列表
     * @param edges 无向边列表
     * @param centrality 节点中心性映射 [0,1]
     * @param density Compose 密度对象，用于 dp->px 转换
     * @param canvasSize 画布尺寸（px），当前仅用于限制初始圆盘半径；可为 null
     * @param iterations 最大迭代次数
     * @return 布局结果，包含位置映射与包围盒
     */
    fun computeLayout(
        nodes: List<GraphNode>,
        edges: List<KnowledgeGraph.GraphEdge>,
        centrality: Map<String, Float>,
        density: Density,
        canvasSize: androidx.compose.ui.geometry.Size? = null,
        iterations: Int = 120,
    ): GraphLayout {
        val positions = computeForceLayout(nodes, edges, centrality, density, canvasSize, iterations)
        val bounds = computeLayoutBounds(positions, nodes, centrality, density)
        return GraphLayout(positions, bounds)
    }

    /**
     * 计算节点在 px 中的半径。
     *
     * @param nodeId 节点 ID
     * @param centrality 中心性映射
     * @param density Compose 密度
     * @return 节点半径（px）
     */
    fun nodeRadiusPx(
        nodeId: String,
        centrality: Map<String, Float>,
        density: Density,
    ): Float {
        val basePx = with(density) { SizeTokens.graphNodeBaseRadius.toPx() }
        val extraPx = with(density) { SizeTokens.graphNodeMaxExtraRadius.toPx() }
        val c = centrality[nodeId]?.coerceIn(0f, 1f) ?: 0f
        return basePx + sqrt(c) * extraPx
    }

    /**
     * 计算点到线段的距离。
     */
    fun distanceToSegment(point: Offset, a: Offset, b: Offset): Float {
        val ab = b - a
        val ap = point - a
        val len2 = ab.x * ab.x + ab.y * ab.y
        val t = if (len2 == 0f) 0f else ((ap.x * ab.x + ap.y * ab.y) / len2).coerceIn(0f, 1f)
        val projection = Offset(a.x + t * ab.x, a.y + t * ab.y)
        val d = point - projection
        return sqrt(d.x * d.x + d.y * d.y)
    }

    private fun computeForceLayout(
        nodes: List<GraphNode>,
        edges: List<KnowledgeGraph.GraphEdge>,
        centrality: Map<String, Float>,
        density: Density,
        canvasSize: androidx.compose.ui.geometry.Size? = null,
        iterations: Int,
    ): Map<String, Offset> {
        if (nodes.isEmpty()) return emptyMap()

        val nodeIds = nodes.map { it.id }
        val n = nodes.size
        val idToIndex = nodeIds.withIndex().associate { it.value to it.index }

        // 节点半径（px），用于碰撞检测
        val basePx = with(density) { SizeTokens.graphNodeBaseRadius.toPx() }
        val extraPx = with(density) { SizeTokens.graphNodeMaxExtraRadius.toPx() }
        val radii = FloatArray(n) { i ->
            val c = centrality[nodeIds[i]]?.coerceIn(0f, 1f) ?: 0f
            basePx + sqrt(c) * extraPx
        }

        // 1. 圆盘内随机初始化（避免花圈）
        val rng = kotlin.random.Random(0xACE)
        val initRadius = min(
            max(160f, sqrt(n.toFloat()) * 45f),
            canvasSize?.let { min(it.width, it.height) * 0.45f } ?: Float.MAX_VALUE,
        )

        val posX = FloatArray(n)
        val posY = FloatArray(n)
        for (i in 0 until n) {
            val r = sqrt(rng.nextFloat()) * initRadius
            val theta = rng.nextFloat() * 2f * kotlin.math.PI
            posX[i] = (r * cos(theta)).toFloat()
            posY[i] = (r * sin(theta)).toFloat()
        }

        val velX = FloatArray(n)
        val velY = FloatArray(n)
        val forceX = FloatArray(n)
        val forceY = FloatArray(n)

        // 唯一边集合（避免双向重复计算），转换为索引对
        val edgeSet = edges.mapNotNull {
            val a = idToIndex[it.sourceId] ?: return@mapNotNull null
            val b = idToIndex[it.targetId] ?: return@mapNotNull null
            if (a < b) a to b else b to a
        }.toSet()

        val area = kotlin.math.PI * initRadius * initRadius
        val repulsion = (area / n * 0.5f).toFloat().coerceIn(10_000f, 120_000f)
        val springStrength = 0.02f
        val idealLength = initRadius * 0.18f
        val centerGravity = 0.04f
        val damping = 0.85f
        val timeStep = 0.45f
        val maxVelocity = 100f
        val stopThreshold = 1.2f
        val paddingPx = with(density) { SpacingTokens.sm.toPx() }
        // 斥力截断距离：距离过远时斥力极小，直接忽略以减少 O(N^2) 计算
        val repulsionCutoff = initRadius * 2.5f
        val repulsionCutoffSq = repulsionCutoff * repulsionCutoff

        for (iter in 0 until iterations) {
            // 清零力
            for (i in 0 until n) {
                forceX[i] = 0f
                forceY[i] = 0f
            }

            // 斥力（所有节点对之间）
            for (i in 0 until n) {
                var fxi = forceX[i]
                var fyi = forceY[i]
                for (j in i + 1 until n) {
                    val dx = posX[i] - posX[j]
                    val dy = posY[i] - posY[j]
                    val distSq = dx * dx + dy * dy
                    if (distSq > repulsionCutoffSq || distSq < 0.001f) continue
                    val dist = sqrt(distSq).coerceAtLeast(1f)
                    val force = repulsion / distSq
                    val fx = (dx / dist) * force
                    val fy = (dy / dist) * force
                    fxi += fx
                    fyi += fy
                    forceX[j] -= fx
                    forceY[j] -= fy
                }
                forceX[i] = fxi
                forceY[i] = fyi
            }

            // 弹簧引力（带理想边长）
            for ((a, b) in edgeSet) {
                val dx = posX[b] - posX[a]
                val dy = posY[b] - posY[a]
                val distSq = dx * dx + dy * dy
                val dist = sqrt(distSq).coerceAtLeast(1f)
                val force = (dist - idealLength) * springStrength
                val fx = (dx / dist) * force
                val fy = (dy / dist) * force
                forceX[a] += fx
                forceY[a] += fy
                forceX[b] -= fx
                forceY[b] -= fy
            }

            // 向心力
            for (i in 0 until n) {
                forceX[i] -= posX[i] * centerGravity
                forceY[i] -= posY[i] * centerGravity
            }

            // 更新速度与位置
            var maxMove = 0f
            for (i in 0 until n) {
                val vx = (velX[i] + forceX[i]) * damping
                val vy = (velY[i] + forceY[i]) * damping
                val speed = sqrt(vx * vx + vy * vy)
                val scale = if (speed > maxVelocity) maxVelocity / speed else 1f
                val cvx = vx * scale
                val cvy = vy * scale
                velX[i] = cvx
                velY[i] = cvy
                val dx = cvx * timeStep
                val dy = cvy * timeStep
                maxMove = max(maxMove, sqrt(dx * dx + dy * dy))
                posX[i] += dx
                posY[i] += dy
            }

            // 硬碰撞：防止节点重叠
            for (i in 0 until n) {
                val rai = radii[i] + paddingPx
                for (j in i + 1 until n) {
                    val dx = posX[i] - posX[j]
                    val dy = posY[i] - posY[j]
                    val distSq = dx * dx + dy * dy
                    val minDist = rai + radii[j]
                    if (distSq >= minDist * minDist || distSq < 0.001f) continue
                    val dist = sqrt(distSq).coerceAtLeast(1f)
                    val overlap = (minDist - dist) / 2f
                    val nx = dx / dist
                    val ny = dy / dist
                    posX[i] += nx * overlap
                    posY[i] += ny * overlap
                    posX[j] -= nx * overlap
                    posY[j] -= ny * overlap
                }
            }

            // 边界约束：限制在初始圆盘内
            for (i in 0 until n) {
                val x = posX[i]
                val y = posY[i]
                val len = sqrt(x * x + y * y)
                if (len > initRadius) {
                    val ratio = initRadius / len
                    posX[i] = x * ratio
                    posY[i] = y * ratio
                }
            }

            // 收敛检测
            if (maxMove < stopThreshold && iter > 30) {
                break
            }
        }

        return nodeIds.withIndex().associate { (index, id) ->
            id to Offset(posX[index], posY[index])
        }
    }

    private fun computeLayoutBounds(
        layout: Map<String, Offset>,
        nodes: List<GraphNode>,
        centrality: Map<String, Float>,
        density: Density,
    ): RectF {
        if (layout.isEmpty()) return RectF()
        val basePx = with(density) { SizeTokens.graphNodeBaseRadius.toPx() }
        val extraPx = with(density) { SizeTokens.graphNodeMaxExtraRadius.toPx() }
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (node in nodes) {
            val pos = layout[node.id] ?: continue
            val c = centrality[node.id]?.coerceIn(0f, 1f) ?: 0f
            val radius = basePx + sqrt(c) * extraPx
            minX = min(minX, pos.x - radius)
            minY = min(minY, pos.y - radius)
            maxX = max(maxX, pos.x + radius)
            maxY = max(maxY, pos.y + radius)
        }
        return RectF(minX, minY, maxX, maxY)
    }
}
