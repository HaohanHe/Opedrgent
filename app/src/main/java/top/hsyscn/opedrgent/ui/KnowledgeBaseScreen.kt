package top.hsyscn.opedrgent.ui

import top.hsyscn.opedrgent.ui.theme.InterviewPurple
import top.hsyscn.opedrgent.ui.theme.InterviewDarkBg
import top.hsyscn.opedrgent.ui.theme.WarningColor
import top.hsyscn.opedrgent.ui.theme.SuccessGreen
import top.hsyscn.opedrgent.ui.theme.AccentOrange
import top.hsyscn.opedrgent.ui.theme.customColors
import top.hsyscn.opedrgent.ui.theme.SpacingTokens
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import top.hsyscn.opedrgent.R
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.storage.KbDocument
import top.hsyscn.opedrgent.storage.KnowledgeBase
import top.hsyscn.opedrgent.storage.KnowledgeBaseInfo
import top.hsyscn.opedrgent.storage.Visibility
import top.hsyscn.opedrgent.ui.theme.AccentBlue
import top.hsyscn.opedrgent.ui.theme.DangerRed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.hsyscn.opedrgent.ui.theme.themeBgGray
import top.hsyscn.opedrgent.ui.theme.themeBorderLight
import top.hsyscn.opedrgent.ui.theme.themeTextDark
import top.hsyscn.opedrgent.ui.theme.themeTextGrey
import top.hsyscn.opedrgent.ui.components.EmptyStateView

// ============================================================
// 排序方式
// ============================================================

private enum class SortBy(val label: String) { TIME("时间"), NAME("名称"), SIZE("大小") }

// ============================================================
// 主入口：多知识库管理系统
// ============================================================

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun KnowledgeBaseScreen(
    vm: MainViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val knowledgeBase = remember { KnowledgeBase(context) }

    // ---- 状态 ----
    var knowledgeBases by remember { mutableStateOf<List<KnowledgeBaseInfo>>(emptyList()) }
    var selectedKbId by remember { mutableStateOf<String?>(null) }  // null = 知识库列表视图
    var documents by remember { mutableStateOf<List<KbDocument>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var isSyncing by remember { mutableStateOf(false) }

    // 文档筛选 & 排序
    var filterType by remember { mutableStateOf<String?>(null) }  // null=全部
    var sortBy by remember { mutableStateOf(SortBy.TIME) }
    var showSortMenu by remember { mutableStateOf(false) }

    // 对话框状态
    var showCreateKbDialog by remember { mutableStateOf(false) }
    var showEditKbDialog by remember { mutableStateOf(false) }
    var editingKb by remember { mutableStateOf<KnowledgeBaseInfo?>(null) }
    var showDeleteKbConfirm by remember { mutableStateOf(false) }
    var kbToDelete by remember { mutableStateOf<KnowledgeBaseInfo?>(null) }

    // 长按菜单
    var contextKb by remember { mutableStateOf<KnowledgeBaseInfo?>(null) }
    var showContextMenu by remember { mutableStateOf(false) }

    // ---- 文件选择器 ----
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val targetKbId = selectedKbId ?: "default"
            isLoading = true
            scope.launch {
                try {
                    val result = knowledgeBase.addFile(uri, targetKbId)
                    if (result.success) {
                        refreshDocuments(knowledgeBase, targetKbId) { documents = it }
                        refreshKnowledgeBases(knowledgeBase) { knowledgeBases = it }
                        snackbar.showSnackbar("已添加: ${result.document?.title}")
                    } else {
                        snackbar.showSnackbar("添加失败: ${result.error}")
                    }
                } catch (e: Exception) {
                    snackbar.showSnackbar("添加失败: ${e.message}")
                } finally {
                    isLoading = false
                }
            }
        }
    }

    // ---- 初始化 ----
    LaunchedEffect(Unit) {
        knowledgeBase.initialize()
        refreshKnowledgeBases(knowledgeBase) { knowledgeBases = it }
    }

    // 当选中知识库变化时，刷新文档列表
    LaunchedEffect(selectedKbId) {
        if (selectedKbId != null) {
            refreshDocuments(knowledgeBase, selectedKbId!!) { documents = it }
        }
    }

    // ---- 数据过滤 ----
    val filteredDocs = remember(documents, searchQuery, filterType, sortBy) {
        var result = documents

        // 搜索过滤
        if (searchQuery.isNotBlank()) {
            val q = searchQuery.lowercase()
            result = result.filter {
                it.title.contains(q, ignoreCase = true) ||
                    it.fileName.contains(q, ignoreCase = true) ||
                    it.content.contains(q, ignoreCase = true)
            }
        }

        // 类型过滤
        if (filterType != null) {
            result = result.filter { it.fileType == filterType }
        }

        // 排序
        result = when (sortBy) {
            SortBy.TIME -> result.sortedByDescending { it.addedAtMs }
            SortBy.NAME -> result.sortedBy { it.title.lowercase() }
            SortBy.SIZE -> result.sortedByDescending { it.fileSizeBytes }
        }

        result
    }

    // ================================================================
    // 主布局：根据 selectedKbId 切换视图
    // ================================================================

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (selectedKbId != null) {
                            knowledgeBases.find { it.id == selectedKbId }?.name ?: stringResource(R.string.kb_documents)
                        } else {
                            stringResource(R.string.kb_title)
                        },
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectedKbId != null) {
                            selectedKbId = null
                            searchQuery = ""
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (selectedKbId == null && knowledgeBases.isNotEmpty()) {
                        val (totalDocs, totalSize) = knowledgeBase.getGlobalStats()
                        Text(
                            text = "$totalDocs 篇 · ${formatFileSize(totalSize)}",
                            color = themeTextGrey(),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(end = SpacingTokens.sm),
                        )
                        // 增量同步按钮: 扫描源文件变更 + 云端向量存储同步
                        IconButton(
                            onClick = {
                                if (isSyncing) return@IconButton
                                isSyncing = true
                                scope.launch {
                                    vm.syncKnowledgeBase()
                                    // 同步完成后刷新列表
                                    knowledgeBases = withContext(Dispatchers.IO) {
                                        knowledgeBase.getAllKnowledgeBases()
                                    }
                                    isSyncing = false
                                    snackbar.showSnackbar("知识库同步已完成")
                                }
                            },
                            enabled = !isSyncing,
                        ) {
                            if (isSyncing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = themeTextGrey(),
                                )
                            } else {
                                Icon(Icons.Default.Sync, contentDescription = "增量同步", tint = themeTextGrey())
                            }
                        }
                    }
                    if (selectedKbId != null) {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.Default.Sort, contentDescription = "排序", tint = themeTextGrey())
                        }
                        DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                            SortBy.entries.forEach { sort ->
                                DropdownMenuItem(
                                    text = { Text(sort.label) },
                                    onClick = { sortBy = sort; showSortMenu = false },
                                )
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbar) },
        containerColor = themeBgGray(),
        floatingActionButton = {
            if (selectedKbId != null) {
                FloatingActionButton(
                    onClick = {
                        filePicker.launch(arrayOf(
                            "application/pdf",
                            "text/plain",
                            "text/markdown",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            "image/*",
                        ))
                    },
                    containerColor = AccentBlue,
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = "添加文件", tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        if (selectedKbId == null) {
            // ========== 知识库列表视图（卡片网格）==========
            KbGridView(
                modifier = Modifier.padding(padding),
                knowledgeBases = knowledgeBases,
                searchQuery = searchQuery,
                onSearchChange = { searchQuery = it },
                onKbClick = { kb -> selectedKbId = kb.id },
                onCreateKb = { showCreateKbDialog = true },
                onKbLongClick = { kb ->
                    contextKb = kb
                    showContextMenu = true
                },
            )
        } else {
            // ========== 文档列表视图 ==========
            DocumentListView(
                modifier = Modifier.padding(padding),
                documents = filteredDocs,
                allCount = documents.size,
                searchQuery = searchQuery,
                onSearchChange = { searchQuery = it },
                filterType = filterType,
                onFilterChange = { filterType = it },
                isLoading = isLoading,
                onDelete = { docId ->
                    scope.launch {
                        knowledgeBase.deleteDocument(docId)
                        refreshDocuments(knowledgeBase, selectedKbId!!) { documents = it }
                        refreshKnowledgeBases(knowledgeBase) { knowledgeBases = it }
                    }
                },
                onDocClick = { doc ->
                    // 打开文档详情（后续可扩展为内置预览或 LLM 摘要）
                    scope.launch { snackbar.showSnackbar("文档: ${doc.title} (${doc.fileType}, ${doc.fileSizeBytes}字节)") }
                },
            )
        }
    }

    // ================================================================
    // 弹窗：新建知识库
    // ================================================================
    if (showCreateKbDialog) {
        var newName by remember { mutableStateOf("") }
        var newDesc by remember { mutableStateOf("") }
        var newVisibility by remember { mutableStateOf(Visibility.PRIVATE) }
        val colors = listOf("#4A90D9", "#43A047", "#E53935", "#FB8C00", "#8E24AA", "#00ACC1")
        var selectedColor by remember { mutableStateOf(colors.random()) }

        AlertDialog(
            onDismissRequest = { showCreateKbDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    val name = newName.trim()
                    if (name.isEmpty()) return@TextButton
                    scope.launch {
                        knowledgeBase.createKnowledgeBase(name, newDesc, newVisibility, selectedColor)
                        refreshKnowledgeBases(knowledgeBase) { knowledgeBases = it }
                        snackbar.showSnackbar("知识库「$name」创建成功")
                        showCreateKbDialog = false
                    }
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateKbDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            },
            title = { Text(stringResource(R.string.kb_new)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.md)) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text(stringResource(R.string.kb_name_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = newDesc,
                        onValueChange = { newDesc = it },
                        label = { Text(stringResource(R.string.kb_desc_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Visibility.entries.forEach { v ->
                            FilterChip(
                                selected = newVisibility == v,
                                onClick = { newVisibility = v },
                                label = { Text(v.label, style = MaterialTheme.typography.bodySmall) },
                            )
                        }
                    }
                    Text("封面颜色", style = MaterialTheme.typography.bodySmall, color = themeTextGrey())
                    Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
                        colors.forEach { color ->
                            Surface(
                                shape = ShapeTokens.smallShape,
                                color = Color(android.graphics.Color.parseColor(color)),
                                modifier = Modifier
                                    .size(32.dp)
                                    .then(if (selectedColor == color) Modifier.padding(SpacingTokens.xxs) else Modifier)
                                    .clip(ShapeTokens.smallShape)
                                    .clickable { selectedColor = color },
                            ) {}
                        }
                    }
                }
            },
        )
    }

    // ================================================================
    // 弹窗：编辑知识库
    // ================================================================
    if (showEditKbDialog && editingKb != null) {
        val kb = editingKb!!
        var editName by remember(kb.id) { mutableStateOf(kb.name) }
        var editDesc by remember(kb.id) { mutableStateOf(kb.description) }
        var editVis by remember(kb.id) { mutableStateOf(kb.visibility) }

        AlertDialog(
            onDismissRequest = { showEditKbDialog = false; editingKb = null },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        knowledgeBase.updateKnowledgeBase(kb.id, editName, editDesc, editVis)
                        refreshKnowledgeBases(knowledgeBase) { knowledgeBases = it }
                        snackbar.showSnackbar("知识库已更新")
                        showEditKbDialog = false
                        editingKb = null
                    }
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showEditKbDialog = false; editingKb = null }) { Text(stringResource(R.string.action_cancel)) }
            },
            title = { Text(stringResource(R.string.kb_edit_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.md)) {
                    OutlinedTextField(value = editName, onValueChange = { editName = it }, label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = editDesc, onValueChange = { editDesc = it }, label = { Text("描述") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Visibility.entries.forEach { v ->
                            FilterChip(selected = editVis == v, onClick = { editVis = v }, label = { Text(v.label, style = MaterialTheme.typography.bodySmall) })
                        }
                    }
                }
            },
        )
    }

    // ================================================================
    // 弹窗：删除确认
    // ================================================================
    if (showDeleteKbConfirm && kbToDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteKbConfirm = false; kbToDelete = null },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val kb = kbToDelete!!
                        knowledgeBase.deleteKnowledgeBase(kb.id)
                        refreshKnowledgeBases(knowledgeBase) { knowledgeBases = it }
                        snackbar.showSnackbar("已删除: ${kb.name}")
                        showDeleteKbConfirm = false
                        kbToDelete = null
                    }
                }) { Text(stringResource(R.string.action_delete), color = DangerRed) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteKbConfirm = false; kbToDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            },
            title = { Text(stringResource(R.string.kb_delete_title)) },
            text = { Text("确定要删除「${kbToDelete!!.name}」吗？其中的所有文档也将被删除。此操作不可撤销。") },
        )
    }

    // ================================================================
    // 长按上下文菜单
    // ================================================================
    DropdownMenu(expanded = showContextMenu, onDismissRequest = { showContextMenu = false }) {
        val kb = contextKb ?: return@DropdownMenu
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_edit)) },
            leadingIcon = { Icon(Icons.Default.Create, contentDescription = "图标") },
            onClick = {
                editingKb = kb
                showEditKbDialog = true
                showContextMenu = false
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_share)) },
            leadingIcon = { Icon(Icons.Default.Share, contentDescription = "图标") },
            onClick = {
                scope.launch { snackbar.showSnackbar(context.getString(R.string.kb_share_developing)) }
                showContextMenu = false
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_delete)) },
            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = "图标") },
            onClick = {
                kbToDelete = kb
                showDeleteKbConfirm = true
                showContextMenu = false
            },
        )
    }
}

// ============================================================
// 视图1: 知识库卡片网格
// ============================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KbGridView(
    modifier: Modifier = Modifier,
    knowledgeBases: List<KnowledgeBaseInfo>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onKbClick: (KnowledgeBaseInfo) -> Unit,
    onCreateKb: () -> Unit,
    onKbLongClick: (KnowledgeBaseInfo) -> Unit,
) {
    Column(modifier = modifier.fillMaxSize().padding(SpacingTokens.lg)) {
        // 搜索栏
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            placeholder = { Text("搜索知识库...", color = themeTextGrey()) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "图标", tint = themeTextGrey(), modifier = Modifier.size(20.dp)) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = "清除", tint = themeTextGrey(), modifier = Modifier.size(18.dp))
                    }
                }
            },
            shape = ShapeTokens.mediumShape,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(SpacingTokens.lg))

        val filteredKbs = if (searchQuery.isBlank()) {
            knowledgeBases
        } else {
            val q = searchQuery.lowercase()
            knowledgeBases.filter {
                it.name.contains(q, ignoreCase = true) || it.description.contains(q, ignoreCase = true)
            }
        }

        if (filteredKbs.isEmpty() && !searchQuery.isNotBlank()) {
            EmptyStateView(
                icon = {
                    Icon(
                        Icons.Default.FolderSpecial,
                        contentDescription = null,
                        tint = themeTextGrey().copy(alpha = 0.4f),
                        modifier = Modifier.size(64.dp),
                    )
                },
                title = stringResource(R.string.kb_empty),
                subtitle = stringResource(R.string.kb_empty_hint),
                actionLabel = stringResource(R.string.kb_new),
                onAction = onCreateKb,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (filteredKbs.isEmpty()) {
            EmptyStateView(
                icon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = themeTextGrey().copy(alpha = 0.4f),
                        modifier = Modifier.size(64.dp),
                    )
                },
                title = "未找到匹配的知识库",
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.md),
                verticalArrangement = Arrangement.spacedBy(SpacingTokens.md),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(filteredKbs, key = { it.id }) { kb ->
                    KbCard(
                        info = kb,
                        onClick = { onKbClick(kb) },
                        onLongClick = { onKbLongClick(kb) },
                    )
                }
                // 新建按钮卡片
                item {
                    Card(
                        shape = ShapeTokens.largeShape,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)),
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, themeBorderLight().copy(alpha = 0.5f)),
                        modifier = Modifier
                            .height(140.dp)
                            .clickable(onClick = onCreateKb),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Surface(shape = CircleShape, color = themeBgGray(), modifier = Modifier.size(44.dp)) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Add, contentDescription = "图标", tint = themeTextGrey(), modifier = Modifier.size(24.dp))
                                    }
                                }
                                Spacer(Modifier.height(SpacingTokens.sm))
                                Text(stringResource(R.string.kb_new), color = themeTextGrey(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================
// 知识库卡片组件
// ============================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KbCard(
    info: KnowledgeBaseInfo,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val coverColor = try { Color(android.graphics.Color.parseColor(info.coverColor)) } catch (_: Exception) { AccentBlue }

    val visibilityIcon: ImageVector = when (info.visibility) {
        Visibility.PRIVATE -> Icons.Default.Lock
        Visibility.PUBLIC -> Icons.Default.Cloud
        Visibility.TEAM -> Icons.Default.Group
    }

    Card(
        shape = ShapeTokens.largeShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .height(140.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Column(Modifier.fillMaxSize().padding(SpacingTokens.md)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // 彩色竖条 + 圆角色块
                Surface(
                    shape = ShapeTokens.smallShape,
                    color = coverColor.copy(alpha = 0.15f),
                    modifier = Modifier.size(36.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Article, contentDescription = "图标", tint = coverColor, modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(Modifier.width(SpacingTokens.md))
                Column(Modifier.weight(1f)) {
                    Text(info.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge, color = themeTextDark(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(visibilityIcon, contentDescription = info.visibility.label, tint = themeTextGrey().copy(alpha = 0.7f), modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(SpacingTokens.xxs))
                        Text(info.visibility.label, color = themeTextGrey().copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Column {
                if (info.description.isNotBlank()) {
                    Text(info.description, color = themeTextGrey(), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(SpacingTokens.xs))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${info.documentCount} 篇", color = themeTextGrey(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
                    if (info.totalSizeBytes > 0) {
                        Text(" · ", color = themeTextGrey().copy(alpha = 0.5f), style = MaterialTheme.typography.labelSmall)
                        Text(formatFileSize(info.totalSizeBytes), color = themeTextGrey().copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

// ============================================================
// 视图2: 文档列表视图
// ============================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocumentListView(
    modifier: Modifier = Modifier,
    documents: List<KbDocument>,
    allCount: Int,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    filterType: String?,
    onFilterChange: (String?) -> Unit,
    isLoading: Boolean,
    onDelete: (String) -> Unit,
    onDocClick: (KbDocument) -> Unit,
) {
    Column(modifier = modifier.fillMaxSize().padding(SpacingTokens.lg)) {
        // 搜索栏
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            placeholder = { Text(stringResource(R.string.kb_search_doc_hint), color = themeTextGrey()) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "图标", tint = themeTextGrey(), modifier = Modifier.size(20.dp)) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = "清除", tint = themeTextGrey(), modifier = Modifier.size(18.dp))
                    }
                }
            },
            shape = ShapeTokens.mediumShape,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(SpacingTokens.md))

        // 类型筛选 Chip 行
        Row(
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            val filters = listOf(null to "全部", "pdf" to "PDF", "txt" to "文本", "jpg" to "图片", "docx" to "Docx")
            filters.forEach { (type, label) ->
                FilterChip(
                    selected = filterType == type,
                    onClick = { onFilterChange(type) },
                    label = { Text(label, style = MaterialTheme.typography.bodySmall) },
                )
            }
            Spacer(Modifier.weight(1f))
            Text("${documents.size}/${allCount}", color = themeTextGrey().copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
        }

        Spacer(Modifier.height(SpacingTokens.md))

        // Loading
        AnimatedVisibility(visible = isLoading) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(SpacingTokens.sm)) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(SpacingTokens.sm))
                Text("正在导入...", color = themeTextGrey(), style = MaterialTheme.typography.bodyMedium)
            }
        }

        // 文档列表
        if (documents.isEmpty() && !isLoading) {
            EmptyStateView(
                icon = {
                    Icon(
                        Icons.Default.Article,
                        contentDescription = null,
                        tint = themeTextGrey().copy(alpha = 0.4f),
                        modifier = Modifier.size(64.dp),
                    )
                },
                title = stringResource(R.string.kb_no_documents),
                subtitle = if (allCount == 0) stringResource(R.string.kb_add_file_hint) else "",
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
                items(documents, key = { it.id }) { doc ->
                    Column(modifier = Modifier.animateItem()) {
                        DocumentCard(document = doc, onClick = { onDocClick(doc) }, onDelete = { onDelete(doc.id) })
                    }
                }
            }
        }
    }
}

// ============================================================
// 文档卡片组件
// ============================================================

@Composable
private fun DocumentCard(
    document: KbDocument,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val icon = when (document.fileType) {
        "pdf" -> Icons.Default.PictureAsPdf
        "jpg", "jpeg", "png", "bmp", "webp" -> Icons.Default.Image
        "docx", "doc" -> Icons.Default.Description
        else -> Icons.Default.TextSnippet
    }

    val iconTint = when (document.fileType) {
        "pdf" -> DangerRed
        "jpg", "jpeg", "png", "bmp", "webp" -> SuccessGreen
        "docx", "doc" -> AccentBlue
        else -> AccentBlue
    }

    val dateStr = remember(document.addedAtMs) {
        SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(document.addedAtMs))
    }

    Card(
        shape = ShapeTokens.mediumShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(SpacingTokens.md).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = ShapeTokens.smallShape,
                color = iconTint.copy(alpha = 0.1f),
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = "图标", tint = iconTint, modifier = Modifier.size(22.dp))
                }
            }

            Spacer(Modifier.width(SpacingTokens.md))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    document.title,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyLarge,
                    color = themeTextDark(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Text(
                    "${document.fileType.uppercase()} · ${formatFileSize(document.fileSizeBytes)} · $dateStr",
                    color = themeTextGrey(),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${document.contentLength} 字", color = themeTextGrey().copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)

                    // 标签显示
                    if (document.tags.isNotEmpty()) {
                        Spacer(Modifier.width(SpacingTokens.sm))
                        document.tags.take(3).forEach { tag ->
                            Surface(
                                shape = ShapeTokens.extraSmallShape,
                                color = AccentBlue.copy(alpha = 0.08f),
                                modifier = Modifier.padding(horizontal = SpacingTokens.xs, vertical = SpacingTokens.xxs),
                            ) {
                                Text(tag, color = AccentBlue, style = MaterialTheme.typography.labelSmall)
                            }
                            Spacer(Modifier.width(SpacingTokens.xxs))
                        }
                        if (document.tags.size > 3) {
                            Text("+${document.tags.size - 3}", color = themeTextGrey().copy(alpha = 0.5f), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Close, contentDescription = "删除", tint = themeTextGrey(), modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ============================================================
// 工具函数
// ============================================================

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}

private suspend fun refreshDocuments(kb: KnowledgeBase, kbId: String, onUpdate: (List<KbDocument>) -> Unit) {
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        onUpdate(kb.getDocumentsByKnowledgeBase(kbId))
    }
}

private suspend fun refreshKnowledgeBases(kb: KnowledgeBase, onUpdate: (List<KnowledgeBaseInfo>) -> Unit) {
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        onUpdate(kb.getAllKnowledgeBases())
    }
}
