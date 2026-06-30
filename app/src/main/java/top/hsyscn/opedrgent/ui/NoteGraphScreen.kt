package top.hsyscn.opedrgent.ui

import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import android.graphics.RectF
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.note.GraphAlgorithms
import top.hsyscn.opedrgent.note.GraphLayout
import top.hsyscn.opedrgent.note.GraphLayoutEngine
import top.hsyscn.opedrgent.note.GraphNode
import top.hsyscn.opedrgent.note.KnowledgeGraph
import top.hsyscn.opedrgent.note.Note
import top.hsyscn.opedrgent.note.NoteRepository
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import top.hsyscn.opedrgent.ui.theme.SizeTokens
import top.hsyscn.opedrgent.ui.theme.SpacingTokens
import top.hsyscn.opedrgent.ui.theme.customColors

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
    var hideOrphans by remember { mutableStateOf(true) }
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
    val connectedNodeIds = remember(allEdges) {
        allEdges.flatMap { listOf(it.sourceId, it.targetId) }.toSet()
    }
    val displayNodes = remember(nodes, connectedNodeIds, hideOrphans) {
        when {
            !hideOrphans -> nodes
            connectedNodeIds.isNotEmpty() -> nodes.filter { it.id in connectedNodeIds }
            else -> nodes
        }
    }
    val displayNodeIds = remember(displayNodes) { displayNodes.map { it.id } }
    val orphanNodeIds = remember(nodes, connectedNodeIds) {
        nodes.map { it.id }.filter { it !in connectedNodeIds }.toSet()
    }

    // 图算法：社区检测 + 中心性
    val edgeWeights = remember(edgeDetails) { edgeDetails.toEdgeWeights() }
    val communities = remember(allEdges, edgeWeights) {
        GraphAlgorithms.detectCommunities(allEdges, edgeWeights)
    }
    val centrality = remember(displayNodeIds, allEdges, edgeWeights) {
        if (allEdges.isEmpty()) {
            GraphAlgorithms.degreeCentrality(displayNodeIds, allEdges)
        } else {
            GraphAlgorithms.pageRank(displayNodeIds, allEdges, edgeWeights)
        }
    }

    // 力导向布局计算（放到后台线程，避免主线程阻塞）
    val density = LocalDensity.current
    val layout by produceState(
        initialValue = GraphLayout(emptyMap(), RectF()),
        key1 = displayNodes,
        key2 = allEdges,
        key3 = centrality,
    ) {
        value = withContext(Dispatchers.Default) {
            GraphLayoutEngine.computeLayout(displayNodes, allEdges, centrality, density)
        }
    }
    val positions = layout.positions

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

    // 缩放和平移状态（使用动画实现双击聚焦）
    var targetScale by remember { mutableFloatStateOf(1f) }
    var targetOffset by remember { mutableStateOf(Offset.Zero) }
    val animatedScale = remember { Animatable(1f) }
    val animatedOffsetX = remember { Animatable(0f) }
    val animatedOffsetY = remember { Animatable(0f) }

    LaunchedEffect(targetScale, targetOffset) {
        launch { animatedScale.animateTo(targetScale, tween(300)) }
        launch { animatedOffsetX.animateTo(targetOffset.x, tween(300)) }
        launch { animatedOffsetY.animateTo(targetOffset.y, tween(300)) }
    }

    val currentScale = animatedScale.value
    val currentOffset = Offset(animatedOffsetX.value, animatedOffsetY.value)

    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (currentScale * zoomChange).coerceIn(0.2f, 4f)
        targetScale = newScale
        targetOffset = currentOffset + panChange
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
            // 统计卡片（用笔记数量兜底，避免知识图谱未重建时全显示 0）
            val displayStats = remember(stats, allNotes) {
                if (stats.totalNotes == 0 && allNotes.isNotEmpty()) {
                    KnowledgeGraph.GraphStats(
                        totalNotes = allNotes.size,
                        totalLinks = 0,
                        isolatedNotes = allNotes.size,
                        avgLinksPerNote = 0f,
                    )
                } else {
                    stats
                }
            }
            StatsRow(displayStats)

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
                hideOrphans = hideOrphans,
                onHideOrphansChange = { hideOrphans = it },
            )

            Spacer(Modifier.height(SpacingTokens.sm))

            // 图谱/时间线可视化区域
            if (nodes.isEmpty()) {
                EmptyGraphState()
            } else {
                when (viewMode) {
                    GraphViewMode.GRAPH -> {
                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxSize()
                                .weight(1f)
                                .transformable(state = transformableState),
                        ) {
                            val canvasWidth = constraints.maxWidth.toFloat()
                            val canvasHeight = constraints.maxHeight.toFloat()
                            var initialFitDone by remember(displayNodes, allEdges) { mutableStateOf(false) }

                            // 布局计算完成后，自动缩放使所有节点可见
                            LaunchedEffect(positions, canvasWidth, canvasHeight) {
                                if (!initialFitDone && positions.isNotEmpty() && canvasWidth > 0 && canvasHeight > 0) {
                                    val bounds = layout.bounds
                                    val paddingPx = with(density) { SpacingTokens.xl.toPx() }
                                    val contentWidth = bounds.width() + paddingPx * 2
                                    val contentHeight = bounds.height() + paddingPx * 2
                                    val scaleX = canvasWidth / contentWidth
                                    val scaleY = canvasHeight / contentHeight
                                    val fitScale = kotlin.math.min(scaleX, scaleY).coerceIn(0.3f, 1.2f)
                                    targetScale = fitScale
                                    targetOffset = Offset(
                                        -bounds.centerX() * fitScale,
                                        -bounds.centerY() * fitScale,
                                    )
                                    initialFitDone = true
                                }
                            }

                            GraphCanvas(
                                nodes = displayNodes,
                                edgeDetails = visibleEdgeDetails,
                                layout = positions,
                                communityMap = communities,
                                centralityMap = centrality,
                                communityColors = communityColors,
                                orphanNodeIds = orphanNodeIds,
                                highlightNoteIds = highlightNoteIds,
                                scale = currentScale,
                                offset = currentOffset,
                                onNodeClick = { node -> onNoteClick(node.noteId) },
                                onNodeLongClick = { node ->
                                    showPreview = allNotes.find { it.id == node.noteId }
                                },
                                onEdgeClick = { detail -> selectedEdgeDetail = detail },
                                onFocusNode = { node ->
                                    val pos = positions[node.id] ?: return@GraphCanvas
                                    targetScale = 2.5f.coerceIn(0.2f, 4f)
                                    targetOffset = Offset(
                                        -pos.x * targetScale,
                                        -pos.y * targetScale,
                                    )
                                },
                                onResetView = {
                                    targetScale = 1f
                                    targetOffset = Offset.Zero
                                },
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
            title = stringResource(R.string.graph_stat_total_notes),
            value = "${stats.totalNotes}",
            icon = Icons.Default.Description,
            color = MaterialTheme.customColors.accentBlue,
            modifier = Modifier.weight(1f),
        )
        StatCard(
            title = stringResource(R.string.graph_stat_total_links),
            value = "${stats.totalLinks}",
            icon = Icons.Default.Hub,
            color = MaterialTheme.customColors.successGreen,
            modifier = Modifier.weight(1f),
        )
        StatCard(
            title = stringResource(R.string.graph_stat_isolated_notes),
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
    hideOrphans: Boolean,
    onHideOrphansChange: (Boolean) -> Unit,
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
        FilterChip(
            selected = hideOrphans,
            onClick = { onHideOrphansChange(!hideOrphans) },
            label = { Text(stringResource(if (hideOrphans) R.string.graph_hide_orphans else R.string.graph_show_orphans)) },
            leadingIcon = {
                Icon(
                    if (hideOrphans) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = null,
                    modifier = Modifier.size(SizeTokens.iconMd),
                )
            },
        )
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
    orphanNodeIds: Set<String>,
    highlightNoteIds: Set<String>,
    scale: Float,
    offset: Offset,
    onNodeClick: (GraphNode) -> Unit,
    onNodeLongClick: (GraphNode) -> Unit,
    onEdgeClick: (KnowledgeGraph.GraphEdgeDetail) -> Unit,
    onFocusNode: (GraphNode) -> Unit,
    onResetView: () -> Unit,
) {
    val positions = layout
    val accentBlue = MaterialTheme.customColors.accentBlue
    val outlineColor = MaterialTheme.colorScheme.outline
    val surfaceColor = MaterialTheme.colorScheme.surface
    val labelColor = MaterialTheme.colorScheme.onSurface
    val labelStyle = MaterialTheme.typography.labelSmall
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val edgeHitThreshold = SizeTokens.iconXs
    val edgeHitThresholdPx = with(density) { edgeHitThreshold.toPx() }

    // 节点半径计算：使用 sqrt 压缩中心性差异，避免个别节点过大；孤立节点只使用基础半径
    fun nodeRadiusPx(node: GraphNode): Float {
        if (node.id in orphanNodeIds) {
            return with(density) { SizeTokens.graphNodeBaseRadius.toPx() }
        }
        return GraphLayoutEngine.nodeRadiusPx(node.id, centralityMap, density)
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(nodes, edgeDetails, scale, offset) {
                detectTapGestures(
                    onDoubleTap = { tapOffset ->
                        val transformedOffset = (tapOffset - offset) / scale
                        val hitNode = nodes.find { node ->
                            val pos = positions[node.id] ?: return@find false
                            val radius = nodeRadiusPx(node)
                            val dx = transformedOffset.x - pos.x
                            val dy = transformedOffset.y - pos.y
                            dx * dx + dy * dy < radius * radius
                        }
                        if (hitNode != null) {
                            onFocusNode(hitNode)
                        } else {
                            onResetView()
                        }
                    },
                    onTap = { tapOffset ->
                        val transformedOffset = (tapOffset - offset) / scale

                        // 优先判断节点点击
                        val hitNode = nodes.find { node ->
                            val pos = positions[node.id] ?: return@find false
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
                            val a = positions[detail.sourceId]
                            val b = positions[detail.targetId]
                            if (a == null || b == null) Float.MAX_VALUE
                            else GraphLayoutEngine.distanceToSegment(transformedOffset, a, b)
                        }
                        hitDetail?.let { detail ->
                            val a = positions[detail.sourceId]
                            val b = positions[detail.targetId]
                            if (a != null && b != null) {
                                val dist = GraphLayoutEngine.distanceToSegment(transformedOffset, a, b)
                                if (dist < hitThresholdUnscaled) {
                                    onEdgeClick(detail)
                                }
                            }
                        }
                    },
                    onLongPress = { tapOffset ->
                        val transformedOffset = (tapOffset - offset) / scale
                        val hitNode = nodes.find { node ->
                            val pos = positions[node.id] ?: return@find false
                            val radius = nodeRadiusPx(node)
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

        val baseSizePx = with(density) { SizeTokens.graphNodeBaseRadius.toPx() }
        val maxExtraPx = with(density) { SizeTokens.graphNodeMaxExtraRadius.toPx() }
        val labelTextSizePx = labelStyle.fontSize.toPx()

        // 绘制连线
        for (detail in edgeDetails) {
            val sourcePos = positions[detail.sourceId] ?: continue
            val targetPos = positions[detail.targetId] ?: continue

            val isHighlighted = detail.sourceId in highlightNoteIds || detail.targetId in highlightNoteIds
            val strokeWidth = if (isHighlighted) 2.5f else 1f
            val color = if (isHighlighted) accentBlue else outlineColor.copy(alpha = 0.5f)
            val alpha = if (isHighlighted) 1f else 0.4f

            drawLine(
                color = color,
                start = transformPos(sourcePos),
                end = transformPos(targetPos),
                strokeWidth = strokeWidth * scale.coerceAtLeast(0.5f),
                alpha = alpha,
            )
        }

        // 按中心性排序，优先绘制重要节点（标签也优先）
        val sortedNodes = nodes.sortedByDescending { centralityMap[it.id] ?: 0f }

        // 节点标签防重叠：记录已占矩形区域
        val drawnRects = mutableListOf<android.graphics.RectF>()
        val labelPaddingPx = with(density) { SpacingTokens.xs.toPx() }

        // 预计算中心性分位阈值，避免在循环中重复排序
        val sortedCentralities = nodes.map { centralityMap[it.id] ?: 0f }.sortedDescending()
        val top10Threshold = if (sortedCentralities.isEmpty()) 0f else sortedCentralities[(kotlin.math.ceil(nodes.size * 0.1f).toInt().coerceAtLeast(1).coerceAtMost(nodes.size)) - 1]
        val top30Threshold = if (sortedCentralities.isEmpty()) 0f else sortedCentralities[(kotlin.math.ceil(nodes.size * 0.3f).toInt().coerceAtLeast(1).coerceAtMost(nodes.size)) - 1]

        for (node in sortedNodes) {
            val pos = positions[node.id] ?: continue
            val transformedPos = transformPos(pos)
            val centrality = centralityMap[node.id]?.coerceIn(0f, 1f) ?: 0f
            val isOrphan = node.id in orphanNodeIds
            val nodeRadius = if (isOrphan) {
                baseSizePx
            } else {
                baseSizePx + kotlin.math.sqrt(centrality) * maxExtraPx
            }
            val radius = nodeRadius * scale

            val isHighlighted = node.id in highlightNoteIds
            val communityId = communityMap[node.id]
            val nodeColor = when {
                isHighlighted -> accentBlue
                isOrphan -> outlineColor.copy(alpha = 0.35f)
                communityId != null -> communityColors[communityId % communityColors.size]
                else -> outlineColor.copy(alpha = 0.5f)
            }

            // 外圈光晕（高亮时）
            if (isHighlighted) {
                drawCircle(
                    color = accentBlue.copy(alpha = 0.25f),
                    radius = radius + with(density) { SpacingTokens.sm.toPx() } * scale,
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
                style = Stroke(width = with(density) { SizeTokens.borderWidth.toPx() } * scale.coerceAtLeast(0.5f)),
            )

            // 节点标签密度控制：按缩放级别与中心性分层显示
            val showLabel = when {
                isHighlighted -> true
                scale <= 1.0f -> centrality > 0f && centrality >= top10Threshold
                scale <= 1.5f -> centrality >= top30Threshold
                else -> true
            }
            if (!showLabel) continue

            val maxChars = when {
                scale > 1.5f -> 16
                scale > 0.9f -> 12
                else -> 8
            }
            val raw = node.label.take(maxChars)
            val displayLabel = if (node.label.length > maxChars) "$raw…" else raw

            val style = labelStyle.copy(fontSize = labelStyle.fontSize * scale.coerceAtLeast(0.6f))
            val textLayout = textMeasurer.measure(AnnotatedString(displayLabel), style)
            val w = textLayout.size.width.toFloat()
            val h = textLayout.size.height.toFloat()
            val topLeft = Offset(
                x = transformedPos.x - w / 2,
                y = transformedPos.y + radius + labelPaddingPx,
            )

            val rect = android.graphics.RectF(
                topLeft.x - labelPaddingPx,
                topLeft.y - labelPaddingPx / 2,
                topLeft.x + w + labelPaddingPx,
                topLeft.y + h + labelPaddingPx / 2,
            )

            // 贪心遮挡剔除：与已绘标签碰撞则不绘制
            if (drawnRects.any { android.graphics.RectF.intersects(it, rect) }) continue
            drawnRects += rect

            // 标签半透明背景
            drawRect(
                color = surfaceColor.copy(alpha = 0.75f),
                topLeft = Offset(rect.left, rect.top),
                size = androidx.compose.ui.geometry.Size(rect.width(), rect.height()),
            )
            drawContext.canvas.nativeCanvas.drawText(
                displayLabel,
                topLeft.x,
                topLeft.y + h,
                android.graphics.Paint().apply {
                    textSize = style.fontSize.toPx()
                    color = (if (isHighlighted) accentBlue else labelColor).toArgb()
                    isAntiAlias = true
                    isFakeBoldText = true
                },
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
