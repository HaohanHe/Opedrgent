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
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
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
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
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
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
import top.hsyscn.opedrgent.ui.theme.*
import top.hsyscn.opedrgent.ui.components.EmptyStateView

private val doubaoRadius = 16.dp

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
                        snackbar.showSnackbar(context.getString(R.string.kb_yi_tian_jia_1, result.document?.title))
                    } else {
                        snackbar.showSnackbar(context.getString(R.string.kb_tian_jia_shi_bai_1, result.error))
                    }
                } catch (e: Exception) {
                    snackbar.showSnackbar(context.getString(R.string.kb_tian_jia_shi_bai_1, e.message))
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
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = if (selectedKbId != null) {
                            knowledgeBases.find { it.id == selectedKbId }?.name ?: stringResource(R.string.kb_documents)
                        } else {
                            stringResource(R.string.kb_title)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = themeForeground(),
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back), tint = themeForeground())
                    }
                },
                actions = {
                    if (selectedKbId == null && knowledgeBases.isNotEmpty()) {
                        // 增量同步按钮
                        IconButton(
                            onClick = {
                                if (isSyncing) return@IconButton
                                isSyncing = true
                                scope.launch {
                                    vm.syncKnowledgeBase()
                                    knowledgeBases = withContext(Dispatchers.IO) {
                                        knowledgeBase.getAllKnowledgeBases()
                                    }
                                    isSyncing = false
                                    snackbar.showSnackbar(context.getString(R.string.kb_zhi_shi_ku_tong_bu_yi_wan))
                                }
                            },
                            enabled = !isSyncing,
                            modifier = Modifier.size(32.dp),
                        ) {
                            if (isSyncing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = themePrimary(),
                                )
                            } else {
                                Icon(Icons.Default.Sync, contentDescription = stringResource(R.string.kb_tong_bu), tint = themeForegroundMuted(), modifier = Modifier.size(20.dp))
                            }
                        }
                        Spacer(Modifier.width(SpacingTokens.sm))
                        // 新建知识库按钮（圆形主色）
                        FilledIconButton(
                            onClick = { showCreateKbDialog = true },
                            modifier = Modifier.size(32.dp),
                            shape = CircleShape,
                        ) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.kb_new), modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(SpacingTokens.sm))
                    }
                    if (selectedKbId != null) {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.kb_sort_label), tint = themeForegroundMuted())
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = themeBackgroundSecondary(),
                    scrolledContainerColor = themeBackgroundSecondary(),
                ),
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbar) },
        containerColor = themeBackgroundSecondary(),
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
                    containerColor = themePrimary(),
                    shape = CircleShape,
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = stringResource(R.string.kb_tian_jia_wen_jian), tint = MaterialTheme.colorScheme.onPrimary)
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
                onImport = {
                    filePicker.launch(arrayOf(
                        "application/pdf",
                        "text/plain",
                        "text/markdown",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "image/*",
                    ))
                },
                onDelete = { docId ->
                    scope.launch {
                        knowledgeBase.deleteDocument(docId)
                        refreshDocuments(knowledgeBase, selectedKbId!!) { documents = it }
                        refreshKnowledgeBases(knowledgeBase) { knowledgeBases = it }
                    }
                },
                onDocClick = { doc ->
                    // 打开文档详情（后续可扩展为内置预览或 LLM 摘要）
                    scope.launch { snackbar.showSnackbar(context.getString(R.string.kb_wen_dang_1_2_3, doc.title, doc.fileType, doc.fileSizeBytes)) }
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
        val colors = listOf("#0065FD", "#22C55E", "#EF4444", "#F59E0B", "#8E24AA", "#00ACC1")
        var selectedColor by remember { mutableStateOf(colors.random()) }
        var nameError by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showCreateKbDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    val name = newName.trim()
                    if (name.isEmpty()) {
                        nameError = true
                        return@TextButton
                    }
                    scope.launch {
                        knowledgeBase.createKnowledgeBase(name, newDesc, newVisibility, selectedColor)
                        refreshKnowledgeBases(knowledgeBase) { knowledgeBases = it }
                        snackbar.showSnackbar(context.getString(R.string.kb_zhi_shi_ku_1_chuang_jian, name))
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
                        onValueChange = { newName = it; nameError = false },
                        label = { Text(stringResource(R.string.kb_name_label)) },
                        singleLine = true,
                        isError = nameError,
                        supportingText = {
                            if (nameError) {
                                Text(
                                    text = stringResource(R.string.kb_ming_cheng_bu_neng_wei_kong),
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                                )
                            }
                        },
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
                    Text(stringResource(R.string.kb_cover_color), style = MaterialTheme.typography.bodySmall, color = themeForegroundMuted())
                    Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
                        colors.forEach { color ->
                            Surface(
                                shape = CircleShape,
                                color = Color(android.graphics.Color.parseColor(color)),
                                modifier = Modifier
                                    .size(32.dp)
                                    .then(if (selectedColor == color) Modifier.padding(SpacingTokens.xxs) else Modifier)
                                    .clip(CircleShape)
                                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.action_select)) { selectedColor = color },
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
                        snackbar.showSnackbar(context.getString(R.string.kb_zhi_shi_ku_yi_geng_xin))
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
                    OutlinedTextField(value = editName, onValueChange = { editName = it }, label = { Text(stringResource(R.string.kb_name_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = editDesc, onValueChange = { editDesc = it }, label = { Text(stringResource(R.string.kb_desc_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
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
                        snackbar.showSnackbar(context.getString(R.string.kb_yi_shan_chu_1, kb.name))
                        showDeleteKbConfirm = false
                        kbToDelete = null
                    }
                }) { Text(stringResource(R.string.action_delete), color = DangerRed) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteKbConfirm = false; kbToDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            },
            title = { Text(stringResource(R.string.kb_delete_title)) },
            text = { Text(stringResource(R.string.kb_que_ding_yao_shan_chu_1_ma_qi, kbToDelete!!.name)) },
        )
    }

    // ================================================================
    // 长按上下文菜单
    // ================================================================
    DropdownMenu(expanded = showContextMenu, onDismissRequest = { showContextMenu = false }) {
        val kb = contextKb ?: return@DropdownMenu
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_edit)) },
            leadingIcon = { Icon(Icons.Default.Create, contentDescription = null) },
            onClick = {
                editingKb = kb
                showEditKbDialog = true
                showContextMenu = false
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_share)) },
            leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
            onClick = {
                scope.launch { snackbar.showSnackbar(context.getString(R.string.kb_share_developing)) }
                showContextMenu = false
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_delete)) },
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
    Column(modifier = modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        // 搜索栏
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            placeholder = { Text(stringResource(R.string.kb_sou_suo_zhi_shi_ku), color = themeForegroundMuted()) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = themeForegroundMuted(), modifier = Modifier.size(18.dp)) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.kb_qing_chu), tint = themeForegroundMuted(), modifier = Modifier.size(18.dp))
                    }
                }
            },
            shape = RoundedCornerShape(percent = 50),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedContainerColor = themeBackgroundMuted(),
                unfocusedContainerColor = themeBackgroundMuted(),
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
            ),
        )

        Spacer(Modifier.height(12.dp))

        val filteredKbs = if (searchQuery.isBlank()) {
            knowledgeBases
        } else {
            val q = searchQuery.lowercase()
            knowledgeBases.filter {
                it.name.contains(q, ignoreCase = true) || it.description.contains(q, ignoreCase = true)
            }
        }

        // 统计信息
        val totalDocs = filteredKbs.sumOf { it.documentCount }
        Text(
            text = stringResource(R.string.kb_gong_1_ge_zhi_shi_ku_2_pian, filteredKbs.size, totalDocs),
            style = MaterialTheme.typography.labelMedium,
            color = themeForegroundMuted(),
            modifier = Modifier.padding(bottom = 16.dp),
        )

        if (filteredKbs.isEmpty() && !searchQuery.isNotBlank()) {
            EmptyStateView(
                icon = {
                    Icon(
                        Icons.Default.FolderSpecial,
                        contentDescription = null,
                        tint = themeForegroundMuted().copy(alpha = 0.4f),
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
                        tint = themeForegroundMuted().copy(alpha = 0.4f),
                        modifier = Modifier.size(64.dp),
                    )
                },
                title = stringResource(R.string.kb_no_match),
                modifier = Modifier.fillMaxSize(),
            )
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
                    NewKbCard(onClick = onCreateKb)
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
    val coverColor = try { Color(android.graphics.Color.parseColor(info.coverColor)) } catch (_: Exception) { themePrimary() }

    val visibilityIcon: ImageVector = when (info.visibility) {
        Visibility.PRIVATE -> Icons.Default.Lock
        Visibility.PUBLIC -> Icons.Default.Cloud
        Visibility.TEAM -> Icons.Default.Group
    }

    Card(
        shape = RoundedCornerShape(doubaoRadius),
        colors = CardDefaults.cardColors(containerColor = themeCardWhite()),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .height(132.dp)
            .semantics(mergeDescendants = true) {}
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Row(Modifier.fillMaxSize()) {
            // 左侧 4dp 色条
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(coverColor),
            )
            Box(Modifier.weight(1f)) {
                Column(Modifier.fillMaxSize().padding(12.dp)) {
                    // 彩色圆角图标背景
                    Surface(
                        shape = CircleShape,
                        color = coverColor.copy(alpha = 0.12f),
                        modifier = Modifier.size(32.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null, tint = coverColor, modifier = Modifier.size(16.dp))
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        info.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = themeForeground(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(R.string.kb_1_pian_wen_dang, info.documentCount) + if (info.totalSizeBytes > 0) " · ${formatFileSize(info.totalSizeBytes)}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = themeForegroundMuted(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                // 可见性图标右上角
                Icon(
                    visibilityIcon,
                    contentDescription = info.visibility.label,
                    tint = themeForegroundMuted(),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(14.dp),
                )
            }
        }
    }
}

// ============================================================
// 新建知识库卡片（虚线边框）
// ============================================================

@Composable
private fun NewKbCard(
    onClick: () -> Unit,
) {
    val borderColor = themeBorder()
    val mutedColor = themeForegroundMuted()
    Box(
        modifier = Modifier
            .height(132.dp)
            .clip(RoundedCornerShape(doubaoRadius))
            .background(themeCardWhite())
            .drawBehind {
                drawRoundRect(
                    color = borderColor,
                    style = Stroke(
                        width = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f),
                    ),
                    cornerRadius = CornerRadius(doubaoRadius.toPx()),
                )
            }
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.action_add), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier.size(40.dp),
                contentAlignment = Alignment.Center,
            ) {
                // 虚线圆环
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBehind {
                            drawCircle(
                                color = mutedColor,
                                style = Stroke(
                                    width = 1.5.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f), 0f),
                                ),
                            )
                        },
                )
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.cd_new),
                    tint = themeForegroundMuted(),
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.kb_new),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = themeForegroundMuted(),
            )
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
    onImport: () -> Unit,
    onDelete: (String) -> Unit,
    onDocClick: (KbDocument) -> Unit,
) {
    Column(modifier = modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        // 搜索栏
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            placeholder = { Text(stringResource(R.string.kb_search_doc_hint), color = themeForegroundMuted()) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = themeForegroundMuted(), modifier = Modifier.size(18.dp)) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.kb_qing_chu), tint = themeForegroundMuted(), modifier = Modifier.size(18.dp))
                    }
                }
            },
            shape = RoundedCornerShape(percent = 50),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedContainerColor = themeBackgroundMuted(),
                unfocusedContainerColor = themeBackgroundMuted(),
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
            ),
        )

        Spacer(Modifier.height(SpacingTokens.md))

        // 类型筛选 Chip 行
        Row(
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            val filters = listOf(null to stringResource(R.string.graph_filter_all), "pdf" to "PDF", "txt" to stringResource(R.string.kb_filter_text), "jpg" to stringResource(R.string.kb_filter_image), "docx" to "Docx")
            filters.forEach { (type, label) ->
                FilterChip(
                    selected = filterType == type,
                    onClick = { onFilterChange(type) },
                    label = { Text(label, style = MaterialTheme.typography.bodySmall) },
                )
            }
            Spacer(Modifier.weight(1f))
            Text("${documents.size}/$allCount", color = themeForegroundMuted().copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
        }

        Spacer(Modifier.height(SpacingTokens.md))

        // Loading
        AnimatedVisibility(visible = isLoading) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(SpacingTokens.sm)) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = themePrimary())
                Spacer(Modifier.width(SpacingTokens.sm))
                Text(stringResource(R.string.import_file_zheng_zai_dao_ru), color = themeForegroundMuted(), style = MaterialTheme.typography.bodyMedium)
            }
        }

        // 文档列表
        if (documents.isEmpty() && !isLoading) {
            EmptyStateView(
                icon = {
                    Icon(
                        Icons.AutoMirrored.Filled.Article,
                        contentDescription = null,
                        tint = themeForegroundMuted().copy(alpha = 0.4f),
                        modifier = Modifier.size(64.dp),
                    )
                },
                title = stringResource(R.string.kb_no_documents),
                subtitle = if (allCount == 0) stringResource(R.string.kb_add_file_hint) else "",
                actionLabel = if (allCount == 0) "添加文件" else null,
                onAction = if (allCount == 0) onImport else null,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
        else -> Icons.AutoMirrored.Filled.TextSnippet
    }

    val iconTint = when (document.fileType) {
        "pdf" -> DangerRed
        "jpg", "jpeg", "png", "bmp", "webp" -> SuccessGreen
        "docx", "doc" -> themePrimary()
        else -> themePrimary()
    }

    val dateStr = remember(document.addedAtMs) {
        SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(document.addedAtMs))
    }

    Card(
        shape = RoundedCornerShape(doubaoRadius),
        colors = CardDefaults.cardColors(containerColor = themeCardWhite()),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = ShapeTokens.smallShape,
                color = iconTint.copy(alpha = 0.1f),
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
                }
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    document.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = themeForeground(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Text(
                    "${document.fileType.uppercase()} · ${formatFileSize(document.fileSizeBytes)}",
                    color = themeForegroundMuted(),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )

                Text(
                    stringResource(R.string.kb_lai_yuan_1_2, document.fileName, dateStr),
                    color = themeForegroundMuted().copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_delete), tint = themeForegroundMuted(), modifier = Modifier.size(18.dp))
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
