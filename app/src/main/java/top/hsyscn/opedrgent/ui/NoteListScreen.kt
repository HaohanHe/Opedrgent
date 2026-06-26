package top.hsyscn.opedrgent.ui

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import top.hsyscn.opedrgent.note.Folder
import top.hsyscn.opedrgent.note.FolderRepository
import top.hsyscn.opedrgent.note.Note
import top.hsyscn.opedrgent.note.NoteRepository
import top.hsyscn.opedrgent.note.NoteType
import top.hsyscn.opedrgent.note.icon
import top.hsyscn.opedrgent.note.color
import top.hsyscn.opedrgent.note.displayName
import top.hsyscn.opedrgent.note.AiSearchResult
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.ui.text.style.TextOverflow
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import top.hsyscn.opedrgent.note.SourceType
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import top.hsyscn.opedrgent.ui.theme.SpacingTokens
import top.hsyscn.opedrgent.ui.theme.customColors
import top.hsyscn.opedrgent.ui.theme.themeBgGray
import top.hsyscn.opedrgent.ui.theme.themeTextGrey
import top.hsyscn.opedrgent.ui.components.EmptyStateView
import top.hsyscn.opedrgent.ui.components.MarkdownText

/**
 * 笔记列表页。
 *
 * 功能：
 * - 搜索栏（标题/内容模糊搜索）
 * - 类型筛选 Tab（全部/文本/会议/语音/链接/闪念/AI）
 * - 笔记卡片列表（置顶优先、时间倒序）
 * - 空状态引导
 * - 长按菜单（删除/置顶/分享）
 * - FAB 新建笔记
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NoteListScreen(
    repository: NoteRepository,
    folderRepository: FolderRepository,
    onNoteClick: (Long) -> Unit,
    onNewNote: () -> Unit,
    onBack: () -> Unit,
    onShareNote: (Long) -> Unit = {},
    onSproutNote: (Long) -> Unit = {},
    onGraphClick: () -> Unit = {},
    onSendToChat: (Long) -> Unit = {},
    onSendWithSkill: (Long, String) -> Unit = { _, _ -> },
    onEditNote: (Long) -> Unit = {},
    onAppendNote: (Long) -> Unit = {},
    onCorrectNote: (Long) -> Unit = {},
    onAddToKnowledgeBase: (Long) -> Unit = {},
    onAddTag: (Long) -> Unit = {},
    showBackButton: Boolean = true,
    aiSearchResults: List<AiSearchResult> = emptyList(),
    isAiSearching: Boolean = false,
    onAiSearch: (String) -> Unit = {},
    onClearAiSearch: () -> Unit = {},
    searchHistory: List<String> = emptyList(),
    onClearSearchHistory: () -> Unit = {},
    isLandscape: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var selectedNoteId by remember { mutableStateOf<Long?>(null) }
    var isAiSearchActive by remember { mutableStateOf(false) }
    var selectedType by remember { mutableStateOf<NoteType?>(null) }
    var selectedTag by remember { mutableStateOf<String?>(null) }
    var currentFolderId by remember { mutableStateOf<Long?>(null) }
    var showFolderDialog by remember { mutableStateOf(false) }
    var editingFolder by remember { mutableStateOf<Folder?>(null) }

    // 关联推荐
    var recommendedNotes by remember { mutableStateOf<List<Note>>(emptyList()) }
    var latestNoteTitle by remember { mutableStateOf("") }

    // 文件夹数据
    val folders by folderRepository.getByParent(currentFolderId).collectAsState(initial = emptyList())
    val allFolders by folderRepository.getAllFolders().collectAsState(initial = emptyList())

    // 笔记数据
    val notesFlow = remember(searchQuery, selectedType, selectedTag, currentFolderId, isAiSearchActive) {
        if (isAiSearchActive) {
            kotlinx.coroutines.flow.flowOf(emptyList())
        } else if (searchQuery.isNotBlank()) {
            repository.searchNotes(searchQuery.trim())
        } else if (selectedTag != null) {
            repository.getByTag(selectedTag!!)
        } else if (selectedType != null) {
            repository.getByType(selectedType!!)
        } else {
            repository.getByFolder(currentFolderId)
        }
    }
    val notes by notesFlow.collectAsState(initial = emptyList())
    val displayNotes = if (isAiSearchActive) aiSearchResults.map { it.note } else notes
    val noteCount by repository.countAll().collectAsState(initial = 0L)
    val allTags by repository.getAllTags().collectAsState(initial = emptyList())

    // 宽屏预览面板：选中的笔记
    var previewNote by remember { mutableStateOf<Note?>(null) }
    LaunchedEffect(selectedNoteId) {
        previewNote = selectedNoteId?.let { repository.getNoteById(it) }
    }

    // 加载关联推荐（基于最新笔记）
    LaunchedEffect(displayNotes) {
        if (displayNotes.isNotEmpty() && !isAiSearchActive) {
            val latest = displayNotes.first()
            latestNoteTitle = latest.title.ifBlank { "无标题" }
            recommendedNotes = repository.getLinkedNotesWithTitles(latest.id)
        } else {
            recommendedNotes = emptyList()
            latestNoteTitle = ""
        }
    }

    // 文件夹对话框
    if (showFolderDialog) {
        FolderDialog(
            folder = editingFolder,
            onDismiss = { showFolderDialog = false },
            onConfirm = { name ->
                scope.launch {
                    try {
                        if (editingFolder != null) {
                            folderRepository.renameFolder(editingFolder!!.id, name)
                        } else {
                            folderRepository.quickCreate(name, currentFolderId)
                        }
                        showFolderDialog = false
                    } catch (e: Exception) {
                        // 处理错误
                    }
                }
            },
        )
    }

    // 笔记列表内容（竖屏和横屏共用）
    val noteListContent: @Composable (Modifier) -> Unit = { outerModifier ->
        Box(modifier = outerModifier.background(themeBgGray())) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // 顶部栏
            TopAppBar(
                title = { Text("笔记", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)) },
                navigationIcon = {
                    if (showBackButton) {
                        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") }
                    }
                },
                actions = {
                    // 知识图谱入口
                    IconButton(onClick = onGraphClick) {
                        Icon(
                            Icons.Default.AccountTree,
                            "知识图谱",
                            tint = MaterialTheme.customColors.accentBlue,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Text(
                        text = "${noteCount.toInt()} 条",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = SpacingTokens.lg),
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = themeBgGray(),
                ),
            )

            // 搜索栏（使用 OutlinedTextField 替代 SearchBar，避免遮挡下方内容）
            OutlinedTextField(
                value = searchQuery,
                onValueChange = {
                    searchQuery = it
                    if (it.isEmpty()) {
                        isAiSearchActive = false
                        onClearAiSearch()
                    }
                },
                placeholder = { Text("搜索笔记...（支持自然语言）") },
                leadingIcon = { Icon(Icons.Default.Search, "搜索") },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = {
                            searchQuery = ""
                            isAiSearchActive = false
                            onClearAiSearch()
                        }) {
                            Icon(Icons.Default.Close, "清除")
                        }
                    }
                },
                singleLine = true,
                shape = ShapeTokens.mediumShape,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        val query = searchQuery.trim()
                        if (query.isNotBlank()) {
                            val nlKeywords = listOf("关于", "提到", "有关", "包含", "涉及", "讨论", "谈到", "说到")
                            if (nlKeywords.any { query.contains(it) }) {
                                isAiSearchActive = true
                                onAiSearch(query)
                            } else {
                                isAiSearchActive = false
                                onClearAiSearch()
                            }
                        }
                    },
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = SpacingTokens.lg),
            )

            // 搜索历史
            if (searchQuery.isEmpty() && searchHistory.isNotEmpty()) {
                Column(modifier = Modifier.padding(horizontal = SpacingTokens.lg, vertical = SpacingTokens.xs)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "搜索历史",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = onClearSearchHistory,
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Text("清除", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        searchHistory.forEach { historyItem ->
                            AssistChip(
                                onClick = {
                                    searchQuery = historyItem
                                    val nlKeywords = listOf("关于", "提到", "有关", "包含", "涉及", "讨论", "谈到", "说到")
                                    if (nlKeywords.any { historyItem.contains(it) }) {
                                        isAiSearchActive = true
                                        onAiSearch(historyItem)
                                    } else {
                                        isAiSearchActive = false
                                        onClearAiSearch()
                                    }
                                },
                                label = { Text(historyItem, style = MaterialTheme.typography.labelMedium) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.History,
                                        contentDescription = null, // 装饰性图标，标签已说明
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                            )
                        }
                    }
                }
            }

            // 类型筛选 Chip 行
            TypeFilterChips(
                selectedType = selectedType,
                onTypeSelected = { selectedType = it },
                noteCount = noteCount.toInt(),
            )

            // 标签筛选 Chip 行
            if (allTags.isNotEmpty()) {
                TagFilterChips(
                    tags = allTags,
                    selectedTag = selectedTag,
                    onTagSelected = { selectedTag = it },
                )
            }

            // 文件夹导航
            if (currentFolderId != null || folders.isNotEmpty()) {
                FolderNavigation(
                    currentFolderId = currentFolderId,
                    folders = folders,
                    allFolders = allFolders,
                    onFolderClick = { folderId -> currentFolderId = folderId },
                    onBackToRoot = { currentFolderId = null },
                    onCreateFolder = { showFolderDialog = true; editingFolder = null },
                    onRenameFolder = { folder -> showFolderDialog = true; editingFolder = folder },
                    onDeleteFolder = { folder ->
                        scope.launch {
                            folderRepository.deleteFolder(folder.id)
                            if (currentFolderId == folder.id) {
                                currentFolderId = null
                            }
                        }
                    },
                )
            }

            Spacer(Modifier.height(8.dp))

            // AI 搜索加载指示器
            if (isAiSearching) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = SpacingTokens.xl),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.customColors.accentBlue)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "AI 正在理解您的搜索意图...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // 笔记列表
            if (displayNotes.isEmpty() && !isAiSearching) {
                EmptyNoteState(onNewNote = onNewNote)
            } else if (!isAiSearching) {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 关联推荐区域
                    if (recommendedNotes.isNotEmpty() && !isAiSearchActive) {
                        item(key = "recommendation") {
                            RecommendationCard(
                                sourceTitle = latestNoteTitle,
                                recommendedNotes = recommendedNotes.take(3),
                                onNoteClick = onNoteClick,
                            )
                        }
                    }

                    items(displayNotes, key = { it.id }) { note ->
                        val relevance = aiSearchResults.find { it.note.id == note.id }?.relevance
                        Column(modifier = Modifier.animateItem()) {
                            NoteCard(
                                note = note,
                                relevance = relevance,
                                onClick = {
                                    if (isLandscape) selectedNoteId = note.id else onNoteClick(note.id)
                                },
                                onTogglePin = { scope.launch { repository.togglePin(note.id) } },
                                onDelete = { scope.launch { repository.deleteNote(note.id) } },
                                onShare = { onShareNote(note.id) },
                                onSprout = { onSproutNote(note.id) },
                                onSendToChat = { onSendToChat(note.id) },
                                onSendWithSkill = { skillId -> onSendWithSkill(note.id, skillId) },
                                onEdit = { onEditNote(note.id) },
                                onAppend = { onAppendNote(note.id) },
                                onCorrect = { onCorrectNote(note.id) },
                                onAddToKnowledgeBase = { onAddToKnowledgeBase(note.id) },
                                onAddTag = { onAddTag(note.id) },
                                linkCount = repository.getLinkCount(note.id),
                            )
                        }
                    }
                }
            }
        }

        // FAB
        FloatingActionButton(
            onClick = onNewNote,
            containerColor = MaterialTheme.customColors.accentBlue,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(SpacingTokens.xl)
                .size(56.dp),
            shape = CircleShape,
        ) {
            Icon(Icons.Default.Add, "新建笔记", tint = MaterialTheme.colorScheme.onPrimary)
        }
        } // Box
    } // noteListContent lambda

    // 根据屏幕方向选择布局
    if (isLandscape) {
        Row(modifier = Modifier.fillMaxSize().background(themeBgGray())) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                noteListContent(Modifier.fillMaxSize())
            }
            VerticalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Box(modifier = Modifier.width(400.dp).fillMaxHeight().background(themeBgGray())) {
                if (previewNote != null) {
                    NotePreviewPanel(
                        note = previewNote!!,
                        onEdit = { onEditNote(previewNote!!.id) },
                        onSendToChat = { onSendToChat(previewNote!!.id) },
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("选择一条笔记预览", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    } else {
        noteListContent(Modifier.fillMaxSize())
    }
}

/** 笔记预览面板（宽屏右侧） */
@Composable
private fun NotePreviewPanel(
    note: Note,
    onEdit: () -> Unit,
    onSendToChat: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(SpacingTokens.xl),
    ) {
        // 标题
        Text(
            text = note.title.ifBlank { "无标题" },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        // 元信息
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = note.type.displayName(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .background(note.type.color(), ShapeTokens.smallShape)
                    .padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xs),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(note.updatedAt)),
                style = MaterialTheme.typography.labelSmall,
                color = themeTextGrey(),
            )
        }
        // 标签
        val tagList = note.getTags()
        if (tagList.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (tag in tagList.take(5)) {
                    Text(
                        text = "#$tag",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.customColors.accentBlue,
                        modifier = Modifier
                            .background(MaterialTheme.customColors.accentBlue.copy(alpha = 0.1f), ShapeTokens.smallShape)
                            .padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xs),
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(16.dp))
        // 内容
        if (note.content.isNotBlank()) {
            MarkdownText(
                text = note.content,
                maxChars = 8000,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text("（空笔记）", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(24.dp))
        // 操作按钮
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onEdit,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.customColors.accentBlue),
                shape = ShapeTokens.smallShape,
            ) {
                Icon(Icons.Default.Edit, contentDescription = "编辑", modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("编辑")
            }
            OutlinedButton(
                onClick = onSendToChat,
                shape = ShapeTokens.smallShape,
            ) {
                Icon(Icons.Default.Chat, contentDescription = "发送到对话", modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("发到对话")
            }
        }
    }
}

@Composable
private fun TypeFilterChips(
    selectedType: NoteType?,
    onTypeSelected: (NoteType?) -> Unit,
    noteCount: Int,
) {
    val types = listOf(null to "全部") + NoteType.entries.map { it to it.displayName() }
    val chipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.customColors.accentBlue,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
    )

    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = SpacingTokens.lg),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        types.forEach { (type, label) ->
            val isSelected = type == selectedType
            FilterChip(
                selected = isSelected,
                onClick = { onTypeSelected(type) },
                label = {
                    Text(label, style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal))
                },
                colors = chipColors,
                shape = ShapeTokens.largeShape,
                border = if (isSelected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
            )
        }
    }
}

@Composable
private fun TagFilterChips(
    tags: List<String>,
    selectedTag: String?,
    onTagSelected: (String?) -> Unit,
) {
    val chipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.customColors.accentOrange,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
    )

    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = SpacingTokens.lg, vertical = SpacingTokens.xs),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 全部标签按钮
        FilterChip(
            selected = selectedTag == null,
            onClick = { onTagSelected(null) },
            label = { Text("全部标签", style = MaterialTheme.typography.labelMedium) },
            colors = chipColors,
            shape = ShapeTokens.largeShape,
            border = if (selectedTag == null) null else BorderStroke(1.dp, MaterialTheme.customColors.accentOrange.copy(alpha = 0.3f)),
        )
        
        // 各个标签按钮
        tags.take(10).forEach { tag ->
            val isSelected = tag == selectedTag
            FilterChip(
                selected = isSelected,
                onClick = { onTagSelected(if (isSelected) null else tag) },
                label = { Text(tag, style = MaterialTheme.typography.labelMedium) },
                colors = chipColors,
                shape = ShapeTokens.largeShape,
                border = if (isSelected) null else BorderStroke(1.dp, MaterialTheme.customColors.accentOrange.copy(alpha = 0.3f)),
            )
        }
        
        // 如果标签超过10个，显示"+N"按钮
        if (tags.size > 10) {
            Text(
                "+${tags.size - 10}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, top = 8.dp),
            )
        }
    }
}

@Composable
private fun FolderNavigation(
    currentFolderId: Long?,
    folders: List<Folder>,
    allFolders: List<Folder>,
    onFolderClick: (Long) -> Unit,
    onBackToRoot: () -> Unit,
    onCreateFolder: () -> Unit,
    onRenameFolder: (Folder) -> Unit,
    onDeleteFolder: (Folder) -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(horizontal = SpacingTokens.lg, vertical = SpacingTokens.xs)) {
        // 文件夹导航面包屑
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 返回根目录按钮
            if (currentFolderId != null) {
                IconButton(onClick = onBackToRoot, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.ArrowBack, "返回根目录", modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(4.dp))
                
                // 显示当前路径
                val currentPath = buildList {
                    var folderId: Long? = currentFolderId
                    while (folderId != null) {
                        val folder = allFolders.find { it.id == folderId }
                        if (folder != null) {
                            add(0, folder)
                            folderId = folder.parentId
                        } else break
                    }
                }
                
                currentPath.forEachIndexed { index, folder ->
                    if (index > 0) {
                        Text(" > ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        folder.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (index == currentPath.lastIndex) MaterialTheme.customColors.accentBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { onFolderClick(folder.id) },
                    )
                }
            } else {
                Icon(Icons.Default.Folder, "文件夹", tint = MaterialTheme.customColors.accentBlue, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("文件夹", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.weight(1f))

            // 新建文件夹按钮
            IconButton(onClick = onCreateFolder, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.CreateNewFolder, "新建文件夹", modifier = Modifier.size(16.dp), tint = MaterialTheme.customColors.accentBlue)
            }
        }

        // 文件夹网格
        if (folders.isNotEmpty()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.heightIn(max = 120.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                gridItems(folders) { folder ->
                    FolderItem(
                        folder = folder,
                        onClick = { onFolderClick(folder.id) },
                        onRename = { onRenameFolder(folder) },
                        onDelete = { onDeleteFolder(folder) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderItem(
    folder: Folder,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        shape = ShapeTokens.smallShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        modifier = Modifier.height(60.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = SpacingTokens.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Folder, null, tint = MaterialTheme.customColors.accentBlue, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                folder.name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Box {
                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(20.dp)) {
                    Icon(Icons.Default.MoreVert, "更多", modifier = Modifier.size(14.dp))
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        onClick = { showMenu = false; onRename() },
                        leadingIcon = { Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp)) },
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        onClick = { showMenu = false; onDelete() },
                        leadingIcon = { Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderDialog(
    folder: Folder?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(folder?.name ?: "") }
    var isError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (folder != null) "重命名文件夹" else "新建文件夹") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        isError = it.isBlank() || it.length > 50
                    },
                    label = { Text("文件夹名称") },
                    isError = isError,
                    supportingText = {
                        if (isError) {
                            Text("名称不能为空且不能超过50个字符", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank() && name.length <= 50,
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteCard(
    note: Note,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit = {},
    onSprout: () -> Unit = {},
    onSendToChat: () -> Unit = {},
    onSendWithSkill: (String) -> Unit = { _ -> },
    onEdit: () -> Unit = {},
    onAppend: () -> Unit = {},
    onCorrect: () -> Unit = {},
    onAddToKnowledgeBase: () -> Unit = {},
    onAddTag: () -> Unit = {},
    linkCount: Int = 0,
    relevance: Int? = null,
) {
    val sheetState = rememberModalBottomSheetState()
    var showSheet by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        shape = ShapeTokens.mediumShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(SpacingTokens.md)) {
            // 第一行：类型图标 + 标题 + 置顶 + 时间
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 类型图标
                Surface(
                    color = note.type.color().copy(alpha = 0.1f),
                    shape = CircleShape,
                    modifier = Modifier.size(28.dp),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(note.type.icon(), contentDescription = "笔记类型", tint = note.type.color(), modifier = Modifier.size(16.dp))
                    }
                }

                Spacer(Modifier.width(10.dp))

                // 标题
                Text(
                    text = note.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )

                // 置顶标记
                if (note.isPinned) {
                    Icon(Icons.Default.PushPin, "置顶", tint = MaterialTheme.customColors.accentBlue, modifier = Modifier.size(16.dp))
                }

                // 关联数标记
                if (linkCount > 0) {
                    Spacer(Modifier.width(4.dp))
                    Surface(
                        color = MaterialTheme.customColors.accentBlue.copy(alpha = 0.1f),
                        shape = ShapeTokens.smallShape,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xxs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Hub,
                                "关联",
                                tint = MaterialTheme.customColors.accentBlue,
                                modifier = Modifier.size(12.dp),
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(
                                "$linkCount",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.customColors.accentBlue,
                            )
                        }
                    }
                }

                // 更多菜单
                IconButton({ showSheet = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.MoreVert, "更多", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // AI 相关度标签
                if (relevance != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        shape = ShapeTokens.extraSmallShape,
                    ) {
                        Text(
                                "相关度 ${relevance}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = SpacingTokens.xs, vertical = SpacingTokens.xxs),
                            )
                    }
                    Spacer(Modifier.width(4.dp))
                }

                // 来源类型标签
                val sourceLabel = when (note.sourceType) {
                    SourceType.ASR, SourceType.MEETING_TRANSCRIPT -> "录音"
                    SourceType.LINK_EXTRACT -> "链接"
                    SourceType.MANUAL -> "手动"
                    SourceType.AI_GENERATED -> "AI生成"
                    else -> null
                }
                if (sourceLabel != null) {
                    Surface(
                        color = MaterialTheme.customColors.accentBlue.copy(alpha = 0.1f),
                        shape = ShapeTokens.extraSmallShape,
                    ) {
                        Text(
                                sourceLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.customColors.accentBlue,
                                modifier = Modifier.padding(horizontal = SpacingTokens.xs, vertical = SpacingTokens.xxs),
                            )
                    }
                    Spacer(Modifier.width(4.dp))
                }

                // 时间
                Text(
                    text = noteListFormatTime(note.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))

            // 摘要预览
            if (note.summary.isNotEmpty()) {
                Text(
                    text = note.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    lineHeight = 18.sp,
                )
            }

            // 标签
            val tags = note.getTags()
            if (tags.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    tags.take(3).forEach { tag ->
                        Surface(color = MaterialTheme.customColors.accentBlue.copy(alpha = 0.08f), shape = ShapeTokens.smallShape) {
                            Text(tag, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.accentBlue, modifier = Modifier.padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xxs))
                        }
                    }
                    if (tags.size > 3) {
                        Text("+${tags.size - 3}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // 来源链接卡片
            if (note.sourceUrl.isNotEmpty()) {
                val ctx = LocalContext.current
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clickable {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(note.sourceUrl))
                            runCatching { ctx.startActivity(intent) }
                        },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    shape = ShapeTokens.smallShape,
                ) {
                    Row(
                        modifier = Modifier.padding(SpacingTokens.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Link,
                            contentDescription = "来源链接",
                            tint = MaterialTheme.customColors.accentBlue,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = note.sourceUrl,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.customColors.accentBlue,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "来源链接",
                                style = MaterialTheme.typography.labelSmall,
                                color = themeTextGrey(),
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.OpenInNew,
                            contentDescription = "打开链接",
                            tint = themeTextGrey(),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }

    if (showSheet) {
        top.hsyscn.opedrgent.ui.components.NoteActionBottomSheet(
            note = note,
            sheetState = sheetState,
            onDismiss = { showSheet = false },
            onEdit = onEdit,
            onShare = onShare,
            onAppend = onAppend,
            onCorrect = onCorrect,
            onSprout = onSprout,
            onAddToKnowledgeBase = onAddToKnowledgeBase,
            onAddTag = onAddTag,
            onDelete = onDelete,
            onTogglePin = onTogglePin,
            onSendToChat = onSendToChat,
        )
    }
}

@Composable
private fun RecommendationCard(
    sourceTitle: String,
    recommendedNotes: List<Note>,
    onNoteClick: (Long) -> Unit,
) {
    Card(
        shape = ShapeTokens.mediumShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(SpacingTokens.md)) {
            // 标题行
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "发现关联",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "与「$sourceTitle」相关",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                )
            }

            Spacer(Modifier.height(10.dp))

            // 关联笔记列表
            recommendedNotes.forEachIndexed { index, note ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = SpacingTokens.xs),
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.1f),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNoteClick(note.id) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 类型图标
                    Surface(
                        color = note.type.color().copy(alpha = 0.1f),
                        shape = CircleShape,
                        modifier = Modifier.size(24.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(note.type.icon(), contentDescription = null, // 装饰性类型图标
                                tint = note.type.color(), modifier = Modifier.size(14.dp))
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = note.title.ifBlank { "无标题" },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                        )
                        if (note.summary.isNotEmpty()) {
                            Text(
                                text = note.summary,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f),
                                maxLines = 1,
                            )
                        }
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null, // 装饰性导航图标
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.4f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyNoteState(onNewNote: () -> Unit) {
    EmptyStateView(
        icon = {
            Icon(
                Icons.Default.Edit,
                contentDescription = null, // 空状态装饰性图标
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
            )
        },
        title = "还没有笔记",
        subtitle = "点击右下角 + 创建第一条笔记，\n或从 AI 对话中保存精彩内容",
        actionLabel = "写一条笔记",
        onAction = onNewNote,
        modifier = Modifier.fillMaxSize().padding(SpacingTokens.xxl),
    )
}

// ==================== 工具函数 ====================

private fun noteListFormatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
    return sdf.format(Date(timestamp))
}
