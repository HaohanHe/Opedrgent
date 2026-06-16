package top.hsyscn.opedrgent.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import top.hsyscn.opedrgent.note.Note
import top.hsyscn.opedrgent.note.NoteRepository
import top.hsyscn.opedrgent.ui.theme.AccentBlue
import kotlin.math.cos
import kotlin.math.sin

/**
 * 知识图谱可视化页面。
 *
 * 功能：
 * - 显示知识图谱统计（总笔记数、总关联数、孤立笔记数）
 * - 力导向图可视化（Canvas 绘制节点和连线）
 * - 点击节点跳转到笔记编辑
 * - 长按节点显示笔记预览
 * - 搜索框：输入查询文本，高亮最相关的笔记
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteGraphScreen(
    repository: NoteRepository,
    onNoteClick: (Long) -> Unit,
    onBack: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var highlightNoteIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showPreview by remember { mutableStateOf<Note?>(null) }

    // 获取图谱数据
    val stats = remember { repository.getKnowledgeStats() }
    val edges = remember { repository.getAllGraphEdges() }
    val allNotes by repository.getAllNotes().collectAsState(initial = emptyList())

    // 构建节点列表
    val nodes = remember(allNotes) {
        allNotes.map { note ->
            GraphNode(
                id = note.id.toString(),
                label = note.title.ifBlank { note.content.take(15).replace("\n", " ") },
                linkCount = repository.getLinkCount(note.id),
                noteId = note.id,
            )
        }
    }

    // 力导向布局计算
    val layoutState = remember(nodes, edges) {
        mutableStateOf(computeForceLayout(nodes, edges))
    }

    // 搜索触发高亮
    LaunchedEffect(searchQuery) {
        if (searchQuery.isNotBlank() && searchQuery.length >= 2) {
            delay(300) // 防抖
            val results = repository.searchByRelevance(searchQuery, 10)
            highlightNoteIds = results.map { it.first }.toSet()
        } else {
            highlightNoteIds = emptySet()
        }
    }

    // 缩放和平移状态
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(0.3f, 3f)
        offset += panChange
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("知识图谱", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
        ) {
            // 统计卡片
            StatsRow(stats)

            // 搜索框
            SearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onSearch = {},
                active = false,
                onActiveChange = {},
                placeholder = { Text("搜索相关笔记...") },
                leadingIcon = { Icon(Icons.Default.Search, "搜索") },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = ""; highlightNoteIds = emptySet() }) {
                            Icon(Icons.Default.Close, "清除")
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(48.dp),
            ) {}

            Spacer(Modifier.height(8.dp))

            // 图谱可视化区域
            if (nodes.isEmpty()) {
                EmptyGraphState()
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                        .transformable(state = transformableState),
                ) {
                    GraphCanvas(
                        nodes = nodes,
                        edges = edges,
                        layout = layoutState.value,
                        highlightNoteIds = highlightNoteIds,
                        scale = scale,
                        offset = offset,
                        onNodeClick = { node ->
                            onNoteClick(node.noteId)
                        },
                        onNodeLongClick = { node ->
                            val noteId = node.noteId
                            showPreview = allNotes.find { it.id == noteId }
                        },
                    )

                    // 搜索结果提示
                    if (highlightNoteIds.isNotEmpty()) {
                        Surface(
                            color = AccentBlue,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 8.dp),
                        ) {
                            Text(
                                "找到 ${highlightNoteIds.size} 个相关笔记",
                                color = Color.White,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    // 笔记预览弹窗
    showPreview?.let { note ->
        NotePreviewDialog(
            note = note,
            onDismiss = { showPreview = null },
            onEdit = {
                showPreview = null
                onNoteClick(note.id)
            },
        )
    }
}

@Composable
private fun StatsRow(stats: top.hsyscn.opedrgent.note.KnowledgeGraph.GraphStats) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatCard(
            title = "总笔记",
            value = "${stats.totalNotes}",
            icon = Icons.Default.Description,
            color = AccentBlue,
            modifier = Modifier.weight(1f),
        )
        StatCard(
            title = "总关联",
            value = "${stats.totalLinks}",
            icon = Icons.Default.Hub,
            color = Color(0xFF4CAF50),
            modifier = Modifier.weight(1f),
        )
        StatCard(
            title = "孤立笔记",
            value = "${stats.isolatedNotes}",
            icon = Icons.Default.PersonOff,
            color = Color(0xFFE67E22),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatCard(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.08f)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(4.dp))
            Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
            Text(title, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GraphCanvas(
    nodes: List<GraphNode>,
    edges: List<top.hsyscn.opedrgent.note.KnowledgeGraph.GraphEdge>,
    layout: Map<String, Offset>,
    highlightNoteIds: Set<String>,
    scale: Float,
    offset: Offset,
    onNodeClick: (GraphNode) -> Unit,
    onNodeLongClick: (GraphNode) -> Unit,
) {
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(nodes) {
                detectTapGestures(
                    onTap = { tapOffset ->
                        // 查找点击的节点
                        val transformedOffset = (tapOffset - offset) / scale
                        val hitNode = nodes.find { node ->
                            val pos = layout[node.id] ?: return@find false
                            val nodeRadius = 12f + node.linkCount * 3f
                            val dx = transformedOffset.x - pos.x
                            val dy = transformedOffset.y - pos.y
                            dx * dx + dy * dy < nodeRadius * nodeRadius
                        }
                        if (hitNode != null) onNodeClick(hitNode)
                    },
                    onLongPress = { tapOffset ->
                        val transformedOffset = (tapOffset - offset) / scale
                        val hitNode = nodes.find { node ->
                            val pos = layout[node.id] ?: return@find false
                            val nodeRadius = 12f + node.linkCount * 3f
                            val dx = transformedOffset.x - pos.x
                            val dy = transformedOffset.y - pos.y
                            dx * dx + dy * dy < nodeRadius * nodeRadius
                        }
                        if (hitNode != null) onNodeLongClick(hitNode)
                    },
                )
            },
    ) {
        // 计算缩放后的坐标变换
        val centerX = size.width / 2
        val centerY = size.height / 2

        fun transformPos(pos: Offset): Offset {
            return Offset(
                x = pos.x * scale + offset.x + centerX,
                y = pos.y * scale + offset.y + centerY,
            )
        }

        // 绘制连线
        val nodeMap = nodes.associateBy { it.id }
        for (edge in edges) {
            val sourcePos = layout[edge.sourceId] ?: continue
            val targetPos = layout[edge.targetId] ?: continue
            val sourceNode = nodeMap[edge.sourceId]
            val targetNode = nodeMap[edge.targetId]

            val isHighlighted = edge.sourceId in highlightNoteIds || edge.targetId in highlightNoteIds

            val strokeWidth = if (isHighlighted) 3f else 1.5f
            val color = if (isHighlighted) AccentBlue else Color(0xFFCCCCCC).copy(alpha = 0.6f)
            val alpha = if (isHighlighted) 1f else 0.5f

            drawLine(
                color = color,
                start = transformPos(sourcePos),
                end = transformPos(targetPos),
                strokeWidth = strokeWidth * scale,
                alpha = alpha,
            )
        }

        // 绘制节点
        for (node in nodes) {
            val pos = layout[node.id] ?: continue
            val transformedPos = transformPos(pos)
            val baseRadius = 10f + node.linkCount * 3f
            val radius = baseRadius * scale

            val isHighlighted = node.id in highlightNoteIds
            val nodeColor = when {
                isHighlighted -> AccentBlue
                node.linkCount == 0 -> Color(0xFFE0E0E0)
                else -> Color(0xFF4CAF50)
            }

            // 外圈光晕（高亮时）
            if (isHighlighted) {
                drawCircle(
                    color = AccentBlue.copy(alpha = 0.2f),
                    radius = radius + 8f * scale,
                    center = transformedPos,
                )
            }

            // 节点圆形
            drawCircle(
                color = nodeColor,
                radius = radius,
                center = transformedPos,
            )

            // 节点边框
            drawCircle(
                color = Color.White,
                radius = radius,
                center = transformedPos,
                style = Stroke(width = 2f * scale),
            )

            // 节点标签
            val paint = android.graphics.Paint().apply {
                textSize = 11f * scale
                color = android.graphics.Color.DKGRAY
                textAlign = android.graphics.Paint.Align.CENTER
                isFakeBoldText = true
                isAntiAlias = true
            }
            drawContext.canvas.nativeCanvas.drawText(
                node.label.take(12),
                transformedPos.x,
                transformedPos.y + radius + 14f * scale,
                paint,
            )
        }
    }
}

@Composable
private fun NotePreviewDialog(
    note: Note,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                note.title.ifBlank { "无标题" },
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column {
                Text(
                    note.content.take(500),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 10,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 22.sp,
                )
                Spacer(Modifier.height(8.dp))
                val tags = note.getTags()
                if (tags.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        tags.take(5).forEach { tag ->
                            Surface(
                                color = AccentBlue.copy(alpha = 0.08f),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Text(
                                    tag,
                                    fontSize = 11.sp,
                                    color = AccentBlue,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onEdit) {
                Text("编辑")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}

@Composable
private fun EmptyGraphState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.AccountTree,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "知识图谱为空",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "创建更多笔记后，系统会自动建立关联",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}

// ==================== 数据模型 ====================

data class GraphNode(
    val id: String,
    val label: String,
    val linkCount: Int,
    val noteId: Long,
)

// ==================== 力导向布局 ====================

/**
 * 简单的力导向图布局算法。
 * 使用弹簧模型：节点间有斥力，边有引力。
 */
private fun computeForceLayout(
    nodes: List<GraphNode>,
    edges: List<top.hsyscn.opedrgent.note.KnowledgeGraph.GraphEdge>,
    iterations: Int = 80,
): Map<String, Offset> {
    if (nodes.isEmpty()) return emptyMap()

    val nodeIds = nodes.map { it.id }
    val nodeIndex = nodeIds.withIndex().associate { (i, id) -> id to i }

    // 构建邻接表
    val adjacency = mutableMapOf<String, MutableSet<String>>()
    for (edge in edges) {
        adjacency.getOrPut(edge.sourceId) { mutableSetOf() }.add(edge.targetId)
        adjacency.getOrPut(edge.targetId) { mutableSetOf() }.add(edge.sourceId)
    }

    // 初始位置：圆形布局
    val positions = mutableMapOf<String, Offset>()
    val n = nodes.size
    val radius = 120f + n * 5f
    for ((i, node) in nodes.withIndex()) {
        val angle = 2 * Math.PI * i / n
        positions[node.id] = Offset(
            x = (radius * cos(angle)).toFloat(),
            y = (radius * sin(angle)).toFloat(),
        )
    }

    // 力导向迭代
    val repulsionForce = 8000f
    val attractionForce = 0.005f
    val damping = 0.9f
    val minDistance = 50f

    var velocities = mutableMapOf<String, Offset>()

    for (iter in 0 until iterations) {
        val forces = mutableMapOf<String, Offset>()

        // 初始化力为零
        for (id in nodeIds) {
            forces[id] = Offset.Zero
        }

        // 斥力（所有节点对之间）
        for (i in nodeIds.indices) {
            for (j in i + 1 until nodeIds.size) {
                val posI = positions[nodeIds[i]]!!
                val posJ = positions[nodeIds[j]]!!
                val dx = posI.x - posJ.x
                val dy = posI.y - posJ.y
                val dist = maxOf(minDistance, kotlin.math.sqrt(dx * dx + dy * dy))
                val force = repulsionForce / (dist * dist)
                val fx = (dx / dist) * force
                val fy = (dy / dist) * force

                forces[nodeIds[i]] = Offset(
                    forces[nodeIds[i]]!!.x + fx,
                    forces[nodeIds[i]]!!.y + fy,
                )
                forces[nodeIds[j]] = Offset(
                    forces[nodeIds[j]]!!.x - fx,
                    forces[nodeIds[j]]!!.y - fy,
                )
            }
        }

        // 引力（有边的节点之间）
        for ((id, neighbors) in adjacency) {
            val pos = positions[id] ?: continue
            for (neighborId in neighbors) {
                val neighborPos = positions[neighborId] ?: continue
                val dx = neighborPos.x - pos.x
                val dy = neighborPos.y - pos.y
                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                val force = attractionForce * dist
                val fx = (dx / maxOf(dist, 1f)) * force
                val fy = (dy / maxOf(dist, 1f)) * force

                forces[id] = Offset(
                    forces[id]!!.x + fx,
                    forces[id]!!.y + fy,
                )
            }
        }

        // 中心引力（防止节点飘太远）
        for (id in nodeIds) {
            val pos = positions[id] ?: continue
            val centerForce = 0.001f
            forces[id] = Offset(
                forces[id]!!.x - pos.x * centerForce,
                forces[id]!!.y - pos.y * centerForce,
            )
        }

        // 更新速度和位置
        for (id in nodeIds) {
            val vel = velocities.getOrDefault(id, Offset.Zero)
            val newVel = Offset(
                x = (vel.x + forces[id]!!.x) * damping,
                y = (vel.y + forces[id]!!.y) * damping,
            )
            velocities[id] = newVel
            positions[id] = Offset(
                x = positions[id]!!.x + newVel.x,
                y = positions[id]!!.y + newVel.y,
            )
        }
    }

    return positions
}
