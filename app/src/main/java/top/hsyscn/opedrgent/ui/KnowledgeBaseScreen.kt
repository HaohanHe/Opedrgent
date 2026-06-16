package top.hsyscn.opedrgent.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import top.hsyscn.opedrgent.storage.KbDocument
import top.hsyscn.opedrgent.storage.KnowledgeBase
import top.hsyscn.opedrgent.storage.KnowledgeBaseInfo
import top.hsyscn.opedrgent.storage.Visibility
import top.hsyscn.opedrgent.ui.theme.AccentBlue
import top.hsyscn.opedrgent.ui.theme.BgGray
import top.hsyscn.opedrgent.ui.theme.TextDark
import top.hsyscn.opedrgent.ui.theme.TextGrey
import top.hsyscn.opedrgent.ui.theme.DangerRed
import top.hsyscn.opedrgent.ui.theme.BorderLight
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
                            knowledgeBases.find { it.id == selectedKbId }?.name ?: "文档"
                        } else {
                            "知识库"
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
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (selectedKbId == null && knowledgeBases.isNotEmpty()) {
                        val (totalDocs, totalSize) = knowledgeBase.getGlobalStats()
                        Text(
                            text = "$totalDocs 篇 · ${formatFileSize(totalSize)}",
                            color = TextGrey,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    if (selectedKbId != null) {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.Default.Sort, contentDescription = "排序", tint = TextGrey)
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
        containerColor = BgGray,
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
                    Icon(imageVector = Icons.Default.Add, contentDescription = "添加文件", tint = Color.White)
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
                }) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateKbDialog = false }) { Text("取消") }
            },
            title = { Text("新建知识库") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = newDesc,
                        onValueChange = { newDesc = it },
                        label = { Text("描述（可选）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Visibility.entries.forEach { v ->
                            FilterChip(
                                selected = newVisibility == v,
                                onClick = { newVisibility = v },
                                label = { Text(v.label, fontSize = 12.sp) },
                            )
                        }
                    }
                    Text("封面颜色", style = MaterialTheme.typography.bodySmall, color = TextGrey)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        colors.forEach { color ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(android.graphics.Color.parseColor(color)),
                                modifier = Modifier
                                    .size(32.dp)
                                    .then(if (selectedColor == color) Modifier.padding(2.dp) else Modifier)
                                    .clip(RoundedCornerShape(6.dp))
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
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showEditKbDialog = false; editingKb = null }) { Text("取消") }
            },
            title = { Text("编辑知识库") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = editName, onValueChange = { editName = it }, label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = editDesc, onValueChange = { editDesc = it }, label = { Text("描述") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Visibility.entries.forEach { v ->
                            FilterChip(selected = editVis == v, onClick = { editVis = v }, label = { Text(v.label, fontSize = 12.sp) })
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
                }) { Text("删除", color = DangerRed) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteKbConfirm = false; kbToDelete = null }) { Text("取消") }
            },
            title = { Text("删除知识库") },
            text = { Text("确定要删除「${kbToDelete!!.name}」吗？其中的所有文档也将被删除。此操作不可撤销。") },
        )
    }

    // ================================================================
    // 长按上下文菜单
    // ================================================================
    DropdownMenu(expanded = showContextMenu, onDismissRequest = { showContextMenu = false }) {
        val kb = contextKb ?: return@DropdownMenu
        DropdownMenuItem(
            text = { Text("编辑") },
            leadingIcon = { Icon(Icons.Default.Create, contentDescription = null) },
            onClick = {
                editingKb = kb
                showEditKbDialog = true
                showContextMenu = false
            },
        )
        DropdownMenuItem(
            text = { Text("分享") },
            leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
            onClick = {
                scope.launch { snackbar.showSnackbar("分享功能开发中") }
                showContextMenu = false
            },
        )
        DropdownMenuItem(
            text = { Text("删除") },
            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
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
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        // 搜索栏
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            placeholder = { Text("搜索知识库...", color = TextGrey) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextGrey, modifier = Modifier.size(20.dp)) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = "清除", tint = TextGrey, modifier = Modifier.size(18.dp))
                    }
                }
            },
            shape = RoundedCornerShape(12.dp),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))

        val filteredKbs = if (searchQuery.isBlank()) {
            knowledgeBases
        } else {
            val q = searchQuery.lowercase()
            knowledgeBases.filter {
                it.name.contains(q, ignoreCase = true) || it.description.contains(q, ignoreCase = true)
            }
        }

        if (filteredKbs.isEmpty() && !searchQuery.isNotBlank()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.FolderSpecial, contentDescription = null, tint = TextGrey.copy(alpha = 0.4f), modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("暂无知识库", color = TextGrey, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("点击下方 + 新建你的第一个知识库", color = TextGrey.copy(alpha = 0.6f), fontSize = 12.sp)
                }
            }
        } else if (filteredKbs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("未找到匹配的知识库", color = TextGrey, fontSize = 14.sp)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
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
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.5f)),
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, BorderLight.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .height(140.dp)
                            .clickable(onClick = onCreateKb),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Surface(shape = CircleShape, color = BgGray, modifier = Modifier.size(44.dp)) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Add, contentDescription = null, tint = TextGrey, modifier = Modifier.size(24.dp))
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                Text("新建知识库", color = TextGrey, fontSize = 13.sp, fontWeight = FontWeight.Medium)
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
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .height(140.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // 彩色竖条 + 圆角色块
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = coverColor.copy(alpha = 0.15f),
                    modifier = Modifier.size(36.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Article, contentDescription = null, tint = coverColor, modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(info.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextDark, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(visibilityIcon, contentDescription = info.visibility.label, tint = TextGrey.copy(alpha = 0.7f), modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(info.visibility.label, color = TextGrey.copy(alpha = 0.7f), fontSize = 11.sp)
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Column {
                if (info.description.isNotBlank()) {
                    Text(info.description, color = TextGrey, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${info.documentCount} 篇", color = TextGrey, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    if (info.totalSizeBytes > 0) {
                        Text(" · ", color = TextGrey.copy(alpha = 0.5f), fontSize = 11.sp)
                        Text(formatFileSize(info.totalSizeBytes), color = TextGrey.copy(alpha = 0.7f), fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

// ============================================================
// 视图2: 文档列表视图
// ============================================================

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
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        // 搜索栏
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            placeholder = { Text("搜索文档内容...", color = TextGrey) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextGrey, modifier = Modifier.size(20.dp)) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = "清除", tint = TextGrey, modifier = Modifier.size(18.dp))
                    }
                }
            },
            shape = RoundedCornerShape(12.dp),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(10.dp))

        // 类型筛选 Chip 行
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            val filters = listOf(null to "全部", "pdf" to "PDF", "txt" to "文本", "jpg" to "图片", "docx" to "Docx")
            filters.forEach { (type, label) ->
                FilterChip(
                    selected = filterType == type,
                    onClick = { onFilterChange(type) },
                    label = { Text(label, fontSize = 12.sp) },
                )
            }
            Spacer(Modifier.weight(1f))
            Text("${documents.size}/${allCount}", color = TextGrey.copy(alpha = 0.6f), fontSize = 11.sp)
        }

        Spacer(Modifier.height(12.dp))

        // Loading
        AnimatedVisibility(visible = isLoading) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("正在导入...", color = TextGrey, fontSize = 13.sp)
            }
        }

        // 文档列表
        if (documents.isEmpty() && !isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Article, contentDescription = null, tint = TextGrey.copy(alpha = 0.4f), modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(text = if (allCount == 0) "暂无文档" else "未找到匹配文档", color = TextGrey, fontSize = 14.sp)
                    if (allCount == 0) {
                        Spacer(Modifier.height(4.dp))
                        Text("点击右下角 + 添加文件", color = TextGrey.copy(alpha = 0.6f), fontSize = 12.sp)
                    }
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(documents, key = { it.id }) { doc ->
                    DocumentCard(document = doc, onClick = { onDocClick(doc) }, onDelete = { onDelete(doc.id) })
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
        "jpg", "jpeg", "png", "bmp", "webp" -> Color(0xFF43A047)
        "docx", "doc" -> Color(0xFF1E88E5)
        else -> AccentBlue
    }

    val dateStr = remember(document.addedAtMs) {
        SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(document.addedAtMs))
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = iconTint.copy(alpha = 0.1f),
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    document.title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = TextDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Text(
                    "${document.fileType.uppercase()} · ${formatFileSize(document.fileSizeBytes)} · $dateStr",
                    color = TextGrey,
                    fontSize = 11.sp,
                    maxLines = 1,
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${document.contentLength} 字", color = TextGrey.copy(alpha = 0.7f), fontSize = 11.sp)

                    // 标签显示
                    if (document.tags.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        document.tags.take(3).forEach { tag ->
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = AccentBlue.copy(alpha = 0.08f),
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            ) {
                                Text(tag, color = AccentBlue, fontSize = 9.sp)
                            }
                            Spacer(Modifier.width(3.dp))
                        }
                        if (document.tags.size > 3) {
                            Text("+${document.tags.size - 3}", color = TextGrey.copy(alpha = 0.5f), fontSize = 9.sp)
                        }
                    }
                }
            }

            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Close, contentDescription = "删除", tint = TextGrey, modifier = Modifier.size(18.dp))
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
