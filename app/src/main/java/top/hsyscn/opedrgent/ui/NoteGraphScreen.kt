package top.hsyscn.opedrgent.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.note.GraphAlgorithms
import top.hsyscn.opedrgent.note.KnowledgeGraph
import top.hsyscn.opedrgent.note.Note
import top.hsyscn.opedrgent.note.NoteRepository
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import top.hsyscn.opedrgent.ui.theme.SizeTokens
import top.hsyscn.opedrgent.ui.theme.SpacingTokens
import top.hsyscn.opedrgent.ui.theme.customColors
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 视图模式 */
private enum class GraphViewMode(@StringRes val labelRes: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    GRAPH(R.string.graph_view_graph, Icons.Default.AccountTree),
    TIMELINE(R.string.graph_view_timeline, Icons.Default.Timeline),
}

/**
 * 知识图谱可视化页面。
 *
 * 功能：
 * - 显示知识图谱统计（总笔记数、总关联数、孤立笔记数）
 * - 力导向图可视化（Canvas 绘制节点和连线）
 * - 节点按社区着色、按中心性调整大小
 * - 点击连线显示关系类型与关联原因
 * - 按关系类型筛选边
 * - 时间线视图（按创建时间分组展示笔记）
 * - 点击节点跳转到笔记编辑、长按节点显示笔记预览
 * - 搜索框：输入查询文本，高亮最相关的笔记
 * - 视图模式切换（图谱/时间线）
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
    var viewMode by remember { mutableStateOf(GraphViewMode.GRAPH) }
    var selectedRelationType by remember { mutableStateOf<String?>(null) }
    var selectedEdgeDetail by remember { mutableStateOf<KnowledgeGraph.GraphEdgeDetail?>(null) }
    val scope = rememberCoroutineScope()
    var refreshTrigger by remember { mutableStateOf(0) }
    var rebuilding by remember { mutableStateOf(false) }
    val cdRebuildGraph = stringResource(R.string.cd_rebuild_graph)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    // 获取图谱数据
    val stats = remember(refreshTrigger) { repository.getKnowledgeStats() }
    val edgeDetails = remember(refreshTrigger) { repository.getAllGraphEdgeDetails() }
    val allEdges = remember(edgeDetails) { edgeDetails.toGraphEdges().distinct() }
    val visibleEdgeDetails = remember(edgeDetails, selectedRelationType) {
        if (selectedRelationType == null) {
            edgeDetails
        } else {
            edgeDetails.filter { it.relationType == selectedRelationType }
        }
    }
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
    val nodeIds = remember(nodes) { nodes.map { it.id } }

    // 图算法：社区检测 + 中心性
    val edgeWeights = remember(edgeDetails) { edgeDetails.toEdgeWeights() }
    val communities = remember(allEdges, edgeWeights) {
        GraphAlgorithms.detectCommunities(allEdges, edgeWeights)
    }
    val centrality = remember(nodeIds, allEdges, edgeWeights) {
        GraphAlgorithms.pageRank(nodeIds, allEdges, edgeWeights)
    }

    // 力导向布局计算
    val layout = remember(nodes, allEdges) { computeForceLayout(nodes, allEdges) }

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

    // 社区颜色板（主题感知）
    val communityColors = MaterialTheme.colorScheme.run {
        listOf(
            primary,
            secondary,
            tertiary,
            error,
            surfaceVariant,
            inversePrimary,
            primaryContainer,
            secondaryContainer,
            tertiaryContainer,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.title_knowledge_graph),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    // 重建图谱按钮
                    IconButton(
                        onClick = {
                            if (!rebuilding) {
                                rebuilding = true
                                scope.launch {
                                    repository.rebuildKnowledgeGraph()
                                    refreshTrigger++
                                    rebuilding = false
                                }
                            }
                        },
                        enabled = !rebuilding,
                    ) {
                        if (rebuilding) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(SizeTokens.iconXl),
                                strokeWidth = SizeTokens.borderWidth,
                            )
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = cdRebuildGraph)
                        }
                    }
                    // 视图模式切换
                    val nextMode = if (viewMode == GraphViewMode.GRAPH) GraphViewMode.TIMELINE else GraphViewMode.GRAPH
                    IconButton(onClick = { viewMode = nextMode }) {
                        Icon(
                            nextMode.icon,
                            contentDescription = stringResource(R.string.graph_switch_to, stringResource(nextMode.labelRes)),
                        )
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
                placeholder = { Text(stringResource(R.string.graph_search_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, stringResource(R.string.action_search)) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = ""; highlightNoteIds = emptySet() }) {
                            Icon(Icons.Default.Close, stringResource(R.string.action_close))
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SpacingTokens.lg)
                    .height(SizeTokens.searchBarHeight),
            ) {}

            Spacer(Modifier.height(SpacingTokens.sm))

            // 关系类型筛选
            RelationFilterChips(
                selectedType = selectedRelationType,
                onTypeSelected = { selectedRelationType = it },
            )

            Spacer(Modifier.height(SpacingTokens.sm))

            // 图谱/时间线可视化区域
            if (nodes.isEmpty()) {
                EmptyGraphState()
            } else {
                when (viewMode) {
                    GraphViewMode.GRAPH -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .weight(1f)
                                .transformable(state = transformableState),
                        ) {
                            GraphCanvas(
                                nodes = nodes,
                                edgeDetails = visibleEdgeDetails,
                                layout = layout,
                                communityMap = communities,
                                centralityMap = centrality,
                                communityColors = communityColors,
                                highlightNoteIds = highlightNoteIds,
                                scale = scale,
                                offset = offset,
                                onNodeClick = { node -> onNoteClick(node.noteId) },
                                onNodeLongClick = { node ->
                                    showPreview = allNotes.find { it.id == node.noteId }
                                },
                                onEdgeClick = { detail -> selectedEdgeDetail = detail },
                            )

                            // 搜索结果提示
                            if (highlightNoteIds.isNotEmpty()) {
                                Surface(
                                    color = MaterialTheme.customColors.accentBlue,
                                    shape = ShapeTokens.largeShape,
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(top = SpacingTokens.sm),
                                ) {
                                    Text(
                                        stringResource(R.string.graph_found_notes, highlightNoteIds.size),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.padding(horizontal = SpacingTokens.md, vertical = SpacingTokens.sm),
                                    )
                                }
                            }
                        }
                    }
                    GraphViewMode.TIMELINE -> {
                        TimelineView(
                            notes = allNotes,
                            highlightNoteIds = highlightNoteIds,
                            onNoteClick = onNoteClick,
                            onNoteLongClick = { note -> showPreview = note },
                            modifier = Modifier.fillMaxSize().weight(1f),
                        )
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

    // 边详情 BottomSheet
    selectedEdgeDetail?.let { edge ->
        ModalBottomSheet(
            onDismissRequest = { selectedEdgeDetail = null },
            sheetState = sheetState,
        ) {
            EdgeDetailSheet(edge = edge, nodes = nodes)
        }
    }
}

@Composable
private fun StatsRow(stats: top.hsyscn.opedrgent.note.KnowledgeGraph.GraphStats) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SpacingTokens.lg, vertical = SpacingTokens.sm),
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.md),
    ) {
        StatCard(
            title = "总笔记",
            value = "${stats.totalNotes}",
            icon = Icons.Default.Description,
            color = MaterialTheme.customColors.accentBlue,
            modifier = Modifier.weight(1f),
        )
        StatCard(
            title = "总关联",
            value = "${stats.totalLinks}",
            icon = Icons.Default.Hub,
            color = MaterialTheme.customColors.successGreen,
            modifier = Modifier.weight(1f),
        )
        StatCard(
            title = "孤立笔记",
            value = "${stats.isolatedNotes}",
            icon = Icons.Default.PersonOff,
            color = MaterialTheme.customColors.accentOrange,
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
        shape = ShapeTokens.mediumShape,
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.08f)),
    ) {
        Column(
            modifier = Modifier.padding(SpacingTokens.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(SizeTokens.iconLg))
            Spacer(Modifier.height(SpacingTokens.xs))
            Text(value, style = MaterialTheme.typography.titleLarge, color = color)
            Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RelationFilterChips(
    selectedType: String?,
    onTypeSelected: (String?) -> Unit,
) {
    val filters = listOf(
        null to R.string.graph_filter_all,
        KnowledgeGraph.REL_SEMANTIC_SIMILAR to R.string.graph_rel_semantic_similar,
        KnowledgeGraph.REL_SHARED_KEYWORD to R.string.graph_rel_shared_keyword,
        KnowledgeGraph.REL_SHARED_ENTITY to R.string.graph_rel_shared_entity,
        KnowledgeGraph.REL_TEMPORAL_CLOSE to R.string.graph_rel_temporal_close,
        KnowledgeGraph.REL_CITES to R.string.graph_rel_cites,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = SpacingTokens.lg),
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
    ) {
        filters.forEach { (type, labelRes) ->
            FilterChip(
                selected = selectedType == type,
                onClick = { onTypeSelected(type) },
                label = { Text(stringResource(labelRes)) },
            )
        }
    }
}

@Composable
private fun GraphCanvas(
    nodes: List<GraphNode>,
    edgeDetails: List<KnowledgeGraph.GraphEdgeDetail>,
    layout: Map<String, Offset>,
    communityMap: Map<String, Int>,
    centralityMap: Map<String, Float>,
    communityColors: List<Color>,
    highlightNoteIds: Set<String>,
    scale: Float,
    offset: Offset,
    onNodeClick: (GraphNode) -> Unit,
    onNodeLongClick: (GraphNode) -> Unit,
    onEdgeClick: (KnowledgeGraph.GraphEdgeDetail) -> Unit,
) {
    val accentBlue = MaterialTheme.customColors.accentBlue
    val outlineColor = MaterialTheme.colorScheme.outline
    val surfaceColor = MaterialTheme.colorScheme.surface
    val labelColor = MaterialTheme.colorScheme.onSurface
    val labelStyle = MaterialTheme.typography.labelSmall

    val baseNodeSize = SizeTokens.iconMd
    val maxExtraSize = SizeTokens.iconLg
    val edgeHitThreshold = SizeTokens.iconXs
    val edgeHitThresholdPx = with(LocalDensity.current) { edgeHitThreshold.toPx() }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(nodes, edgeDetails) {
                detectTapGestures(
                    onTap = { tapOffset ->
                        val transformedOffset = (tapOffset - offset) / scale

                        // 优先判断节点点击
                        val nodeRadiusPx = { node: GraphNode ->
                            val centrality = centralityMap[node.id] ?: 0f
                            baseNodeSize.toPx() + centrality * maxExtraSize.toPx()
                        }
                        val hitNode = nodes.find { node ->
                            val pos = layout[node.id] ?: return@find false
                            val radius = nodeRadiusPx(node)
                            val dx = transformedOffset.x - pos.x
                            val dy = transformedOffset.y - pos.y
                            dx * dx + dy * dy < radius * radius
                        }
                        if (hitNode != null) {
                            onNodeClick(hitNode)
                            return@detectTapGestures
                        }

                        // 判断边点击
                        val hitThresholdUnscaled = edgeHitThresholdPx / scale
                        val hitDetail = edgeDetails.minByOrNull { detail ->
                            val a = layout[detail.sourceId]
                            val b = layout[detail.targetId]
                            if (a == null || b == null) Float.MAX_VALUE
                            else distanceToSegment(transformedOffset, a, b)
                        }
                        hitDetail?.let { detail ->
                            val a = layout[detail.sourceId]
                            val b = layout[detail.targetId]
                            if (a != null && b != null) {
                                val dist = distanceToSegment(transformedOffset, a, b)
                                if (dist < hitThresholdUnscaled) {
                                    onEdgeClick(detail)
                                }
                            }
                        }
                    },
                    onLongPress = { tapOffset ->
                        val transformedOffset = (tapOffset - offset) / scale
                        val hitNode = nodes.find { node ->
                            val pos = layout[node.id] ?: return@find false
                            val centrality = centralityMap[node.id] ?: 0f
                            val radius = baseNodeSize.toPx() + centrality * maxExtraSize.toPx()
                            val dx = transformedOffset.x - pos.x
                            val dy = transformedOffset.y - pos.y
                            dx * dx + dy * dy < radius * radius
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

        val baseSizePx = baseNodeSize.toPx()
        val maxExtraPx = maxExtraSize.toPx()
        val labelTextSizePx = labelStyle.fontSize.toPx()
        val nodeMap = nodes.associateBy { it.id }

        // 绘制连线
        for (detail in edgeDetails) {
            val sourcePos = layout[detail.sourceId] ?: continue
            val targetPos = layout[detail.targetId] ?: continue

            val isHighlighted = detail.sourceId in highlightNoteIds || detail.targetId in highlightNoteIds
            val strokeWidth = if (isHighlighted) 3f else 1.5f
            val color = if (isHighlighted) accentBlue else outlineColor.copy(alpha = 0.6f)
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
            val centrality = centralityMap[node.id] ?: 0f
            val nodeRadius = baseSizePx + centrality * maxExtraPx
            val radius = nodeRadius * scale

            val isHighlighted = node.id in highlightNoteIds
            val communityId = communityMap[node.id]
            val nodeColor = when {
                isHighlighted -> accentBlue
                communityId != null -> communityColors[communityId % communityColors.size]
                else -> outlineColor.copy(alpha = 0.5f)
            }

            // 外圈光晕（高亮时）
            if (isHighlighted) {
                drawCircle(
                    color = accentBlue.copy(alpha = 0.2f),
                    radius = radius + SpacingTokens.sm.toPx() * scale,
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
                color = surfaceColor,
                radius = radius,
                center = transformedPos,
                style = Stroke(width = SizeTokens.borderWidth.toPx() * scale),
            )

            // 节点标签
            val paint = android.graphics.Paint().apply {
                textSize = labelTextSizePx * scale
                color = labelColor.toArgb()
                textAlign = android.graphics.Paint.Align.CENTER
                isFakeBoldText = true
                isAntiAlias = true
            }
            drawContext.canvas.nativeCanvas.drawText(
                node.label.take(12),
                transformedPos.x,
                transformedPos.y + radius + labelTextSizePx * scale + SpacingTokens.xs.toPx() * scale,
                paint,
            )
        }
    }
}

@Composable
private fun EdgeDetailSheet(
    edge: KnowledgeGraph.GraphEdgeDetail,
    nodes: List<GraphNode>,
) {
    val sourceLabel = remember(edge.sourceId, nodes) {
        nodes.find { it.id == edge.sourceId }?.label ?: edge.sourceId
    }
    val targetLabel = remember(edge.targetId, nodes) {
        nodes.find { it.id == edge.targetId }?.label ?: edge.targetId
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(SpacingTokens.lg)
            .padding(bottom = SpacingTokens.xxl),
    ) {
        Text(
            stringResource(R.string.graph_edge_detail_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(SpacingTokens.md))
        Text(
            stringResource(R.string.graph_edge_relation_type, relationTypeLabel(edge.relationType)),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(SpacingTokens.xs))
        Text(
            stringResource(R.string.graph_edge_reason, edge.reason),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(SpacingTokens.xs))
        Text(
            stringResource(R.string.graph_edge_weight, edge.weight),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(SpacingTokens.md))
        Text(
            "$sourceLabel ↔ $targetLabel",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun relationTypeLabel(type: String): String = when (type) {
    KnowledgeGraph.REL_SEMANTIC_SIMILAR -> stringResource(R.string.graph_rel_semantic_similar)
    KnowledgeGraph.REL_SHARED_KEYWORD -> stringResource(R.string.graph_rel_shared_keyword)
    KnowledgeGraph.REL_SHARED_ENTITY -> stringResource(R.string.graph_rel_shared_entity)
    KnowledgeGraph.REL_TEMPORAL_CLOSE -> stringResource(R.string.graph_rel_temporal_close)
    KnowledgeGraph.REL_CITES -> stringResource(R.string.graph_rel_cites)
    else -> type
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
                note.title.ifBlank { stringResource(R.string.note_editor_title_placeholder) },
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
                )
                Spacer(Modifier.height(SpacingTokens.sm))
                val tags = note.getTags()
                if (tags.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.xs)) {
                        tags.take(5).forEach { tag ->
                            Surface(
                                color = MaterialTheme.customColors.accentBlue.copy(alpha = 0.08f),
                                shape = ShapeTokens.smallShape,
                            ) {
                                Text(
                                    tag,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.customColors.accentBlue,
                                    modifier = Modifier.padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xxs),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onEdit) {
                Text(stringResource(R.string.action_edit))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        },
    )
}

@Composable
private fun EmptyGraphState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(SpacingTokens.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.AccountTree,
            contentDescription = null,
            modifier = Modifier.size(SizeTokens.emptyStateIcon),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
        )
        Spacer(Modifier.height(SpacingTokens.lg))
        Text(
            stringResource(R.string.graph_empty_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(SpacingTokens.sm))
        Text(
            stringResource(R.string.graph_empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}

// ==================== 时间线视图 ====================

/**
 * 时间线视图 — 按创建时间分组展示笔记。
 *
 * 按年月分组，每组内按创建时间倒序排列。
 * 支持搜索高亮和长按预览。
 */
@Composable
private fun TimelineView(
    notes: List<Note>,
    highlightNoteIds: Set<String>,
    onNoteClick: (Long) -> Unit,
    onNoteLongClick: (Note) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 按年月分组
    val grouped = remember(notes) {
        val cal = java.util.Calendar.getInstance()
        notes.sortedByDescending { it.createdAt }
            .groupBy { note ->
                cal.timeInMillis = note.createdAt
                val year = cal.get(java.util.Calendar.YEAR)
                val month = cal.get(java.util.Calendar.MONTH) + 1
                "$year 年 ${month} 月"
            }
    }

    val scrollState = remember { androidx.compose.foundation.lazy.LazyListState() }

    LazyColumn(
        state = scrollState,
        modifier = modifier.padding(horizontal = SpacingTokens.lg),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
        contentPadding = PaddingValues(bottom = SpacingTokens.xxl),
    ) {
        grouped.forEach { (monthLabel, monthNotes) ->
            // 月份标题
            item(key = "header_$monthLabel") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = SpacingTokens.md, bottom = SpacingTokens.xs),
                ) {
                    // 时间线圆点
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(MaterialTheme.customColors.accentBlue, shape = CircleShape)
                    )
                    Spacer(Modifier.width(SpacingTokens.md))
                    Text(
                        monthLabel,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(SpacingTokens.sm))
                    Text(
                        "${monthNotes.size} 篇",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }

            // 该月的笔记卡片
            monthNotes.forEachIndexed { idx, note ->
                item(key = "note_${note.id}") {
                    val isHighlighted = highlightNoteIds.isEmpty() || note.id.toString() in highlightNoteIds
                    TimelineNoteCard(
                        note = note,
                        isHighlighted = isHighlighted,
                        onClick = { onNoteClick(note.id) },
                        onLongClick = { onNoteLongClick(note) },
                    )
                }
            }
        }
    }
}

/**
 * 时间线中的笔记卡片。
 */
@Composable
private fun TimelineNoteCard(
    note: Note,
    isHighlighted: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val cal = remember { java.util.Calendar.getInstance() }
    val dateStr = remember(note.createdAt) {
        cal.timeInMillis = note.createdAt
        val m = cal.get(java.util.Calendar.MONTH) + 1
        val d = cal.get(java.util.Calendar.DAY_OF_MONTH)
        val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val min = cal.get(java.util.Calendar.MINUTE)
        "${m}月${d}日 %02d:%02d".format(h, min)
    }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = { onLongClick() })
            },
        colors = CardDefaults.cardColors(
            containerColor = if (isHighlighted) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SpacingTokens.md),
            verticalAlignment = Alignment.Top,
        ) {
            // 左侧时间标签
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(56.dp),
            ) {
                Text(
                    dateStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.customColors.accentBlue,
                    maxLines = 2,
                )
            }

            // 竖线
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(40.dp)
                    .background(MaterialTheme.customColors.accentBlue.copy(alpha = 0.3f))
            )

            Spacer(Modifier.width(SpacingTokens.md))

            // 笔记内容
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    note.title.ifBlank { stringResource(R.string.note_editor_title_placeholder) },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (note.content.isNotBlank()) {
                    Text(
                        note.content.take(100).replace("\n", " "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
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
                val dist = maxOf(minDistance, sqrt(dx * dx + dy * dy))
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
                val dist = sqrt(dx * dx + dy * dy)
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

// ==================== 工具函数 ====================

private fun List<KnowledgeGraph.GraphEdgeDetail>.toGraphEdges(): List<KnowledgeGraph.GraphEdge> {
    return map { KnowledgeGraph.GraphEdge(it.sourceId, it.targetId) }.distinct()
}

private fun List<KnowledgeGraph.GraphEdgeDetail>.toEdgeWeights(): Map<Pair<String, String>, Float> {
    return associate {
        val a = it.sourceId
        val b = it.targetId
        (if (a < b) a to b else b to a) to it.weight
    }
}

private fun distanceToSegment(point: Offset, a: Offset, b: Offset): Float {
    val ab = b - a
    val ap = point - a
    val len2 = ab.x * ab.x + ab.y * ab.y
    val t = if (len2 == 0f) 0f else ((ap.x * ab.x + ap.y * ab.y) / len2).coerceIn(0f, 1f)
    val projection = Offset(a.x + t * ab.x, a.y + t * ab.y)
    val d = point - projection
    return sqrt(d.x * d.x + d.y * d.y)
}
