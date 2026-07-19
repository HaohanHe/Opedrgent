package top.hsyscn.opedrgent.ui

import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.livedata.observeAsState
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
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
import top.hsyscn.opedrgent.ui.theme.graphLabel

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

    // 获取图谱数据：DB 查询切到 IO 线程，避免主线程卡顿
    var stats by remember { mutableStateOf(KnowledgeGraph.GraphStats(0, 0, 0, 0f)) }
    var edgeDetails by remember { mutableStateOf<List<KnowledgeGraph.GraphEdgeDetail>>(emptyList()) }
    var linkCounts by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }
    val allNotes by repository.getAllNotes().collectAsState(initial = emptyList())

    LaunchedEffect(refreshTrigger, allNotes) {
        val newStats = withContext(Dispatchers.IO) { repository.getKnowledgeStats() }
        val newEdgeDetails = withContext(Dispatchers.IO) { repository.getAllGraphEdgeDetails() }
        val newLinkCounts = withContext(Dispatchers.IO) {
            allNotes.associate { it.id to repository.getLinkCount(it.id) }
        }
        stats = newStats
        edgeDetails = newEdgeDetails
        linkCounts = newLinkCounts
    }

    val allEdges = remember(edgeDetails) { edgeDetails.toGraphEdges().distinct() }
    val visibleEdgeDetails = remember(edgeDetails, selectedRelationType) {
        if (selectedRelationType == null) {
            edgeDetails
        } else {
            edgeDetails.filter { it.relationType == selectedRelationType }
        }
    }

    // 构建节点列表
    val nodes = remember(allNotes, linkCounts) {
        allNotes.map { note ->
            GraphNode(
                id = note.id.toString(),
                label = note.title.ifBlank { note.content.take(15).replace("\n", " ") },
                linkCount = linkCounts[note.id] ?: 0,
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

    // 搜索触发高亮（语义搜索含 embedding 计算，切到 IO 线程）
    LaunchedEffect(searchQuery) {
        if (searchQuery.isNotBlank() && searchQuery.length >= 2) {
            delay(300) // 防抖
            val results = withContext(Dispatchers.IO) { repository.searchByRelevance(searchQuery, 10) }
            highlightNoteIds = results.map { it.first }.toSet()
        } else {
            highlightNoteIds = emptySet()
        }
    }

    // 监听后台建边 Worker：当从“运行/排队”变为全部完成时，自动刷新图谱
    val context = LocalContext.current
    val workManager = remember { WorkManager.getInstance(context) }
    val workInfos by workManager.getWorkInfosByTagLiveData("graph_link").observeAsState(initial = emptyList())
    var hadRunningWork by remember { mutableStateOf(false) }
    LaunchedEffect(workInfos) {
        val hasRunning = workInfos.any {
            it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
        }
        if (hadRunningWork && !hasRunning) {
            refreshTrigger++
        }
        hadRunningWork = hasRunning
    }

    // 缩放和平移状态（Animatable 直接驱动，避免 target/animated 双轨竞争）
    val animatedScale = remember { Animatable(1f) }
    val animatedOffsetX = remember { Animatable(0f) }
    val animatedOffsetY = remember { Animatable(0f) }

    suspend fun animateGraph(scale: Float, offset: Offset) {
        coroutineScope {
            launch { animatedScale.animateTo(scale, tween(300)) }
            launch { animatedOffsetX.animateTo(offset.x, tween(300)) }
            launch { animatedOffsetY.animateTo(offset.y, tween(300)) }
        }
    }

    suspend fun snapGraph(scale: Float, offset: Offset) {
        coroutineScope {
            launch { animatedScale.snapTo(scale) }
            launch { animatedOffsetX.snapTo(offset.x) }
            launch { animatedOffsetY.snapTo(offset.y) }
        }
    }

    val currentScale = animatedScale.value
    val currentOffset = Offset(animatedOffsetX.value, animatedOffsetY.value)

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
                inputField = {
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.graph_search_hint)) },
                        leadingIcon = { Icon(Icons.Default.Search, stringResource(R.string.action_search)) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = ""; highlightNoteIds = emptySet() }) {
                                    Icon(Icons.Default.Close, stringResource(R.string.action_close))
                                }
                            }
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedBorderColor = MaterialTheme.colorScheme.outline,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        ),
                    )
                },
                expanded = false,
                onExpandedChange = {},
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
                                .pointerInput(Unit) {
                                    detectTransformGestures { centroid, pan, zoom, _ ->
                                        val oldScale = animatedScale.value
                                        val newScale = (oldScale * zoom).coerceIn(0.2f, 4f)
                                        val oldOffset = Offset(animatedOffsetX.value, animatedOffsetY.value)
                                        // 以手势中心点为中心缩放：保持 centroid 在屏幕上的位置不变
                                        val newOffset = centroid - (centroid - oldOffset) * (newScale / oldScale) + pan
                                        scope.launch { snapGraph(newScale, newOffset) }
                                    }
                                },
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
                                    val fitOffset = Offset(
                                        -bounds.centerX() * fitScale,
                                        -bounds.centerY() * fitScale,
                                    )
                                    animateGraph(fitScale, fitOffset)
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
                                    val focusScale = 2.5f.coerceIn(0.2f, 4f)
                                    scope.launch {
                                        animateGraph(focusScale, Offset(-pos.x * focusScale, -pos.y * focusScale))
                                    }
                                },
                                onResetView = {
                                    scope.launch { animateGraph(1f, Offset.Zero) }
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
    val labelStyle = MaterialTheme.typography.graphLabel
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    // 一次性把 dp 转成 px，避免 draw 帧内反复查询 Density
    val baseSizePx = with(density) { SizeTokens.graphNodeBaseRadius.toPx() }
    val maxExtraPx = with(density) { SizeTokens.graphNodeMaxExtraRadius.toPx() }
    val labelPaddingPx = with(density) { SpacingTokens.xs.toPx() }
    val borderWidthPx = with(density) { SizeTokens.borderWidth.toPx() }
    val glowExtraPx = with(density) { SpacingTokens.sm.toPx() }
    val edgeHitThresholdPx = with(density) { SizeTokens.iconXs.toPx() }

    // 节点半径缓存（按笔记 ID）
    val radiusMap = remember(nodes, centralityMap, orphanNodeIds) {
        nodes.associate { node ->
            val c = centralityMap[node.id]?.coerceIn(0f, 1f) ?: 0f
            val r = if (node.id in orphanNodeIds) baseSizePx else baseSizePx + kotlin.math.sqrt(c) * maxExtraPx
            node.id to r
        }
    }

    // 按中心性排序，优先绘制重要节点（标签也优先）
    val sortedNodes = remember(nodes, centralityMap) {
        nodes.sortedByDescending { centralityMap[it.id] ?: 0f }
    }

    // 预计算中心性分位阈值
    val (top10Threshold, top30Threshold) = remember(nodes, centralityMap) {
        val sorted = nodes.map { centralityMap[it.id] ?: 0f }.sortedDescending()
        if (sorted.isEmpty()) {
            0f to 0f
        } else {
            val idx10 = kotlin.math.ceil(nodes.size * 0.1f).toInt().coerceAtLeast(1).coerceAtMost(nodes.size) - 1
            val idx30 = kotlin.math.ceil(nodes.size * 0.3f).toInt().coerceAtLeast(1).coerceAtMost(nodes.size) - 1
            sorted[idx10] to sorted[idx30]
        }
    }

    // 标签文本测量缓存：标签固定屏幕大小，不按图谱缩放比例放大
    val labelLayouts = remember(sortedNodes, labelStyle) {
        sortedNodes.associate { node ->
            val maxChars = when {
                scale > 1.5f -> 10
                scale > 0.9f -> 7
                else -> 5
            }
            val raw = node.label.take(maxChars)
            val displayLabel = if (node.label.length > maxChars) "$raw…" else raw
            node.id to textMeasurer.measure(AnnotatedString(displayLabel), labelStyle)
        }
    }

    // 复用 Paint，避免每个标签都 new Paint
    val labelPaint = remember(accentBlue, labelColor) {
        android.graphics.Paint().apply {
            isAntiAlias = true
            isFakeBoldText = true
        }
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(nodes, edgeDetails, scale, offset, radiusMap) {
                detectTapGestures(
                    onDoubleTap = { tapOffset ->
                        val transformedOffset = (tapOffset - offset) / scale
                        val hitNode = nodes.find { node ->
                            val pos = positions[node.id] ?: return@find false
                            val radius = radiusMap[node.id] ?: return@find false
                            val dx = transformedOffset.x - pos.x
                            val dy = transformedOffset.y - pos.y
                            dx * dx + dy * dy < radius * radius
                        }
                        if (hitNode != null) onFocusNode(hitNode) else onResetView()
                    },
                    onTap = { tapOffset ->
                        val transformedOffset = (tapOffset - offset) / scale

                        val hitNode = nodes.find { node ->
                            val pos = positions[node.id] ?: return@find false
                            val radius = radiusMap[node.id] ?: return@find false
                            val dx = transformedOffset.x - pos.x
                            val dy = transformedOffset.y - pos.y
                            dx * dx + dy * dy < radius * radius
                        }
                        if (hitNode != null) {
                            onNodeClick(hitNode)
                            return@detectTapGestures
                        }

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
                                if (dist < hitThresholdUnscaled) onEdgeClick(detail)
                            }
                        }
                    },
                    onLongPress = { tapOffset ->
                        val transformedOffset = (tapOffset - offset) / scale
                        val hitNode = nodes.find { node ->
                            val pos = positions[node.id] ?: return@find false
                            val radius = radiusMap[node.id] ?: return@find false
                            val dx = transformedOffset.x - pos.x
                            val dy = transformedOffset.y - pos.y
                            dx * dx + dy * dy < radius * radius
                        }
                        if (hitNode != null) onNodeLongClick(hitNode)
                    },
                )
            },
    ) {
        val width = size.width
        val height = size.height
        val centerX = width / 2
        val centerY = height / 2

        fun transformPos(pos: Offset): Offset = Offset(
            pos.x * scale + offset.x + centerX,
            pos.y * scale + offset.y + centerY,
        )

        // viewport 裁剪：世界坐标系下的可见范围
        val maxRadius = radiusMap.values.maxOrNull() ?: baseSizePx
        val margin = maxRadius * 2f
        val worldLeft = (-offset.x - centerX) / scale - margin
        val worldTop = (-offset.y - centerY) / scale - margin
        val worldRight = worldLeft + width / scale + margin * 2f
        val worldBottom = worldTop + height / scale + margin * 2f

        fun isVisibleWorld(x: Float, y: Float, radius: Float): Boolean {
            return x + radius >= worldLeft && x - radius <= worldRight &&
                y + radius >= worldTop && y - radius <= worldBottom
        }

        // 绘制连线（带 viewport 裁剪）
        val edgeStrokeScale = scale.coerceAtLeast(0.5f)
        for (detail in edgeDetails) {
            val sourcePos = positions[detail.sourceId] ?: continue
            val targetPos = positions[detail.targetId] ?: continue
            if (!isVisibleWorld(sourcePos.x, sourcePos.y, maxRadius) &&
                !isVisibleWorld(targetPos.x, targetPos.y, maxRadius)
            ) {
                continue
            }

            val isHighlighted = detail.sourceId in highlightNoteIds || detail.targetId in highlightNoteIds
            val strokeWidth = if (isHighlighted) 2.5f else 1f
            val color = if (isHighlighted) accentBlue else outlineColor.copy(alpha = 0.5f)
            val alpha = if (isHighlighted) 1f else 0.4f

            drawLine(
                color = color,
                start = transformPos(sourcePos),
                end = transformPos(targetPos),
                strokeWidth = strokeWidth * edgeStrokeScale,
                alpha = alpha,
            )
        }

        // 节点标签防重叠：记录已占矩形区域
        val drawnRects = mutableListOf<android.graphics.RectF>()
        val scaledBorderWidth = borderWidthPx * edgeStrokeScale

        for (node in sortedNodes) {
            val pos = positions[node.id] ?: continue
            val nodeRadius = radiusMap[node.id] ?: continue
            if (!isVisibleWorld(pos.x, pos.y, nodeRadius)) continue

            val transformedPos = transformPos(pos)
            val centrality = centralityMap[node.id]?.coerceIn(0f, 1f) ?: 0f
            val isOrphan = node.id in orphanNodeIds
            val isHighlighted = node.id in highlightNoteIds
            val radius = nodeRadius * scale

            val communityId = communityMap[node.id]
            val nodeColor = when {
                isHighlighted -> accentBlue
                isOrphan -> outlineColor.copy(alpha = 0.35f)
                communityId != null -> communityColors[communityId % communityColors.size]
                else -> outlineColor.copy(alpha = 0.5f)
            }

            if (isHighlighted) {
                drawCircle(
                    color = accentBlue.copy(alpha = 0.25f),
                    radius = radius + glowExtraPx * scale,
                    center = transformedPos,
                )
            }

            drawCircle(color = nodeColor, radius = radius, center = transformedPos)
            drawCircle(
                color = surfaceColor,
                radius = radius,
                center = transformedPos,
                style = Stroke(width = scaledBorderWidth),
            )

            // 节点标签密度控制
            val showLabel = when {
                isHighlighted -> true
                scale <= 1.0f -> centrality > 0f && centrality >= top10Threshold
                scale <= 1.5f -> centrality >= top30Threshold
                else -> true
            }
            if (!showLabel) continue

            val textLayout = labelLayouts[node.id] ?: continue
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

            // 贪心遮挡剔除
            if (drawnRects.any { android.graphics.RectF.intersects(it, rect) }) continue
            drawnRects += rect

            // 标签半透明背景
            drawRect(
                color = surfaceColor.copy(alpha = 0.75f),
                topLeft = Offset(rect.left, rect.top),
                size = androidx.compose.ui.geometry.Size(rect.width(), rect.height()),
            )

            labelPaint.textSize = labelStyle.fontSize.toPx()
            labelPaint.color = (if (isHighlighted) accentBlue else labelColor).toArgb()
            drawContext.canvas.nativeCanvas.drawText(
                textLayout.layoutInput.text.text,
                topLeft.x,
                topLeft.y + h,
                labelPaint,
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
    val context = LocalContext.current
    val grouped = remember(notes) {
        val cal = java.util.Calendar.getInstance()
        notes.sortedByDescending { it.createdAt }
            .groupBy { note ->
                cal.timeInMillis = note.createdAt
                val year = cal.get(java.util.Calendar.YEAR)
                val month = cal.get(java.util.Calendar.MONTH) + 1
                context.getString(R.string.note_graph_1_nian_2_yue, year, month)
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
                            .size(SizeTokens.statusDotSize)
                        .background(MaterialTheme.customColors.accentBlue, shape = CircleShape)
                    )
                    Spacer(Modifier.width(SpacingTokens.md))
                    Text(
                        monthLabel,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(SpacingTokens.sm))
                    Text(
                        stringResource(R.string.invisible_partner_1_pian, monthNotes.size),
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
    val context = LocalContext.current
    val cal = remember { java.util.Calendar.getInstance() }
    val dateStr = remember(note.createdAt) {
        cal.timeInMillis = note.createdAt
        val m = cal.get(java.util.Calendar.MONTH) + 1
        val d = cal.get(java.util.Calendar.DAY_OF_MONTH)
        val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val min = cal.get(java.util.Calendar.MINUTE)
        context.getString(R.string.note_graph_1_yue_2_ri_3_shi_4_fen, m, d, h, min)
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
                modifier = Modifier.width(SizeTokens.timelineColumnWidth),
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
                    .width(SizeTokens.borderWidthLg)
                    .height(SizeTokens.timelineLineHeight)
                    .background(MaterialTheme.customColors.accentBlue.copy(alpha = 0.3f))
            )

            Spacer(Modifier.width(SpacingTokens.md))

            // 笔记内容
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    note.title.ifBlank { stringResource(R.string.note_editor_title_placeholder) },
                    style = MaterialTheme.typography.titleSmall,
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
