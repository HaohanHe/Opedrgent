package top.hsyscn.opedrgent.ui

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import top.hsyscn.opedrgent.note.SourceType
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import top.hsyscn.opedrgent.ui.theme.SpacingTokens
import top.hsyscn.opedrgent.ui.theme.customColors
import top.hsyscn.opedrgent.ui.theme.themeBgGray
import top.hsyscn.opedrgent.ui.theme.themeTextGrey
import top.hsyscn.opedrgent.ui.components.EmptyStateView
import top.hsyscn.opedrgent.ui.components.MarkdownText
import java.text.SimpleDateFormat
import java.util.*
import android.content.Intent
import android.net.Uri
import top.hsyscn.opedrgent.ui.theme.*

/**
 * 笔记列表页（Doubao 风格重设计）。
 *
 * 功能：
 * - 顶部标题栏 + 搜索/图谱入口
 * - 圆角搜索栏
 * - 类型筛选 Pill（全部/文本/会议/语音/AI）
 * - 置顶笔记分区（左侧强调色边框）
 * - 笔记卡片列表（白色卡片、阴影、圆角 16dp、类型 chip、摘要、元信息）
 * - 空状态引导
 * - 长按/更多菜单（删除/置顶/分享/发芽等）
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

    val pinnedNotes = remember(displayNotes) { displayNotes.filter { it.isPinned } }
    val unpinnedNotes = remember(displayNotes) { displayNotes.filter { !it.isPinned } }

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
        Box(modifier = outerModifier.background(themeBackgroundSecondary())) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // 顶部栏
                NoteHeader(
                    noteCount = noteCount.toInt(),
                    showBackButton = showBackButton,
                    onBack = onBack,
                    onSearchClick = { /* 搜索栏已在下方常驻，点击可聚焦（如需扩展可在此处理）*/ },
                    onGraphClick = onGraphClick,
                )

                // 搜索栏
                DoubaoSearchBar(
                    query = searchQuery,
                    onQueryChange = {
                        searchQuery = it
                        if (it.isEmpty()) {
                            isAiSearchActive = false
                            onClearAiSearch()
                        }
                    },
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
                    onClear = {
                        searchQuery = ""
                        isAiSearchActive = false
                        onClearAiSearch()
                    },
                    modifier = Modifier.padding(horizontal = SizeTokens.screenHorizontalPadding, vertical = SpacingTokens.xs),
                )

                // 搜索历史
                if (searchQuery.isEmpty() && searchHistory.isNotEmpty()) {
                    Column(modifier = Modifier.padding(horizontal = SizeTokens.screenHorizontalPadding, vertical = SpacingTokens.sm)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "搜索历史",
                                style = MaterialTheme.typography.labelSmall,
                                color = themeForegroundMuted(),
                            )
                            Spacer(Modifier.weight(1f))
                            TextButton(
                                onClick = onClearSearchHistory,
                                contentPadding = PaddingValues(),
                            ) {
                                Text("清除", style = MaterialTheme.typography.labelSmall, color = themeForegroundMuted())
                            }
                        }
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
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
                                            contentDescription = null,
                                            modifier = Modifier.size(SpacingTokens.lg),
                                        )
                                    },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = themeCardWhite(),
                                        labelColor = themeForegroundSecondary(),
                                    ),
                                    border = BorderStroke(SizeTokens.borderWidth, themeBackgroundMuted()),
                                )
                            }
                        }
                    }
                }

                // 类型筛选 Pill 行
                TypeFilterPills(
                    selectedType = selectedType,
                    onTypeSelected = { selectedType = it },
                    modifier = Modifier.padding(top = SpacingTokens.sm, bottom = SpacingTokens.md),
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

                // AI 搜索加载指示器
                if (isAiSearching) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = SpacingTokens.xl),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = themePrimary())
                            Spacer(Modifier.height(SpacingTokens.sm))
                            Text(
                                "AI 正在理解您的搜索意图...",
                                style = MaterialTheme.typography.bodySmall,
                                color = themeForegroundMuted(),
                            )
                        }
                    }
                }

                // 笔记列表
                if (displayNotes.isEmpty() && !isAiSearching) {
                    EmptyNoteState(onNewNote = onNewNote)
                } else if (!isAiSearching) {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = SizeTokens.screenHorizontalPadding, vertical = SpacingTokens.sm),
                        verticalArrangement = Arrangement.spacedBy(SpacingTokens.md),
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

                        // 置顶分区
                        if (pinnedNotes.isNotEmpty()) {
                            item(key = "pinned_header") {
                                Text(
                                    text = "置顶",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = themeForegroundMuted(),
                                    modifier = Modifier.padding(bottom = SpacingTokens.sm),
                                )
                            }
                            items(pinnedNotes, key = { "pinned_${it.id}" }) { note ->
                                val relevance = aiSearchResults.find { it.note.id == note.id }?.relevance
                                Column(modifier = Modifier.animateItem()) {
                                    NoteCard(
                                        note = note,
                                        relevance = relevance,
                                        isPinnedSection = true,
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
                            item(key = "pinned_spacer") {
                                Spacer(Modifier.height(SpacingTokens.sm))
                            }
                        }

                        // 普通笔记列表
                        items(unpinnedNotes, key = { it.id }) { note ->
                            val relevance = aiSearchResults.find { it.note.id == note.id }?.relevance
                            Column(modifier = Modifier.animateItem()) {
                                NoteCard(
                                    note = note,
                                    relevance = relevance,
                                    isPinnedSection = false,
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
                containerColor = themePrimary(),
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(SizeTokens.screenHorizontalPadding)
                    .size(SizeTokens.fabSize),
                shape = CircleShape,
            ) {
                Icon(Icons.Default.Add, "新建笔记", tint = MaterialTheme.colorScheme.onPrimary)
            }
        } // Box
    } // noteListContent lambda

    // 根据屏幕方向选择布局
    if (isLandscape) {
        Row(modifier = Modifier.fillMaxSize().background(themeBackgroundSecondary())) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                noteListContent(Modifier.fillMaxSize())
            }
            VerticalDivider(thickness = SizeTokens.borderWidth, color = themeBackgroundMuted())
            Box(modifier = Modifier.width(SizeTokens.previewPanelWidth).fillMaxHeight().background(themeBackgroundSecondary())) {
                if (previewNote != null) {
                    NotePreviewPanel(
                        note = previewNote!!,
                        onEdit = { onEditNote(previewNote!!.id) },
                        onSendToChat = { onSendToChat(previewNote!!.id) },
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("选择一条笔记预览", color = themeForegroundMuted())
                    }
                }
            }
        }
    } else {
        noteListContent(Modifier.fillMaxSize())
    }
}

@Composable
private fun NoteHeader(
    noteCount: Int,
    showBackButton: Boolean,
    onBack: () -> Unit,
    onSearchClick: () -> Unit,
    onGraphClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (showBackButton) SpacingTokens.xs else SizeTokens.screenHorizontalPadding, end = SpacingTokens.lg, top = SpacingTokens.lg, bottom = SpacingTokens.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBackButton) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.Default.ArrowBack,
                    "返回",
                    tint = themeForeground(),
                    modifier = Modifier.size(SizeTokens.iconLg),
                )
            }
        }
        Text(
            text = "笔记",
            style = MaterialTheme.typography.headlineLarge,
            color = themeForeground(),
            modifier = Modifier.weight(1f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.xs)) {
            IconButton(onClick = onSearchClick) {
                Icon(
                    Icons.Default.Search,
                    "搜索",
                    tint = themeForegroundSecondary(),
                    modifier = Modifier.size(SizeTokens.iconLg),
                )
            }
            IconButton(onClick = onGraphClick) {
                Icon(
                    Icons.Default.AccountTree,
                    "知识图谱",
                    tint = themeForegroundSecondary(),
                    modifier = Modifier.size(SizeTokens.iconLg),
                )
            }
        }
    }
}

@Composable
private fun DoubaoSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }

    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = themeForeground(),
        ),
        cursorBrush = SolidColor(themePrimary()),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(
            onSearch = {
                onSearch()
                focusManager.clearFocus()
            },
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(SizeTokens.searchBarHeight)
            .focusRequester(focusRequester),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(themeBackgroundMuted(), ShapeTokens.pillShape)
                    .padding(horizontal = SizeTokens.contentPaddingMd),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = themeForegroundMuted(),
                    modifier = Modifier.size(SizeTokens.iconMd),
                )
                Spacer(Modifier.width(SpacingTokens.sm))
                Box(modifier = Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            "搜索笔记…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = themeForegroundMuted(),
                        )
                    }
                    innerTextField()
                }
                if (query.isNotEmpty()) {
                    Spacer(Modifier.width(SpacingTokens.xs))
                    IconButton(onClick = onClear, modifier = Modifier.size(SpacingTokens.xl)) {
                        Icon(
                            Icons.Default.Close,
                            "清除",
                            tint = themeForegroundMuted(),
                            modifier = Modifier.size(SizeTokens.iconMd),
                        )
                    }
                }
            }
        },
    )
}

private data class FilterPill(val type: NoteType?, val label: String)

private val filterPills = listOf(
    FilterPill(null, "全部"),
    FilterPill(NoteType.TEXT, "文本"),
    FilterPill(NoteType.MEETING, "会议"),
    FilterPill(NoteType.ASR, "语音"),
    FilterPill(NoteType.AI_CHAT, "AI"),
)

@Composable
private fun TypeFilterPills(
    selectedType: NoteType?,
    onTypeSelected: (NoteType?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = SizeTokens.screenHorizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
    ) {
        filterPills.forEach { pill ->
            val isSelected = pill.type == selectedType
            val bgColor = if (isSelected) themePrimary() else themeBackgroundMuted()
            val textColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else themeForegroundSecondary()

            Box(
                modifier = Modifier
                    .clip(ShapeTokens.pillShape)
                    .background(bgColor)
                    .clickable { onTypeSelected(pill.type) }
                    .padding(horizontal = SizeTokens.contentPaddingMd, vertical = SizeTokens.compactSpacing),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    pill.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = textColor,
                )
            }
        }
    }
}

@Composable
private fun TagFilterChips(
    tags: List<String>,
    selectedTag: String?,
    onTagSelected: (String?) -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = SizeTokens.screenHorizontalPadding, vertical = SpacingTokens.sm),
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
    ) {
        FilterChip(
            selected = selectedTag == null,
            onClick = { onTagSelected(null) },
            label = { Text("全部标签", style = MaterialTheme.typography.labelMedium) },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = themePrimary(),
                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                containerColor = themeBackgroundMuted(),
                labelColor = themeForegroundSecondary(),
            ),
            shape = ShapeTokens.pillShape,
            border = if (selectedTag == null) null else BorderStroke(SizeTokens.borderWidth, themeBackgroundMuted()),
        )

        tags.take(10).forEach { tag ->
            val isSelected = tag == selectedTag
            FilterChip(
                selected = isSelected,
                onClick = { onTagSelected(if (isSelected) null else tag) },
                label = { Text(tag, style = MaterialTheme.typography.labelMedium) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = themePrimary(),
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    containerColor = themeBackgroundMuted(),
                    labelColor = themeForegroundSecondary(),
                ),
                shape = ShapeTokens.pillShape,
                border = if (isSelected) null else BorderStroke(SizeTokens.borderWidth, themeBackgroundMuted()),
            )
        }

        if (tags.size > 10) {
            Text(
                "+${tags.size - 10}",
                style = MaterialTheme.typography.labelMedium,
                color = themeForegroundMuted(),
                modifier = Modifier.padding(start = SpacingTokens.sm, top = SpacingTokens.sm),
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
    Column(modifier = Modifier.padding(horizontal = SizeTokens.screenHorizontalPadding, vertical = SpacingTokens.sm)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (currentFolderId != null) {
                IconButton(onClick = onBackToRoot, modifier = Modifier.size(SpacingTokens.xl)) {
                    Icon(Icons.Default.ArrowBack, "返回根目录", modifier = Modifier.size(SpacingTokens.lg), tint = themePrimary())
                }
                Spacer(Modifier.width(SpacingTokens.xs))

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
                        Text(" > ", style = MaterialTheme.typography.labelMedium, color = themeForegroundMuted())
                    }
                    Text(
                        folder.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (index == currentPath.lastIndex) themePrimary() else themeForegroundMuted(),
                        modifier = Modifier.clickable { onFolderClick(folder.id) },
                    )
                }
            } else {
                Icon(Icons.Default.Folder, "文件夹", tint = themePrimary(), modifier = Modifier.size(SpacingTokens.lg))
                Spacer(Modifier.width(SpacingTokens.xs))
                Text("文件夹", style = MaterialTheme.typography.labelMedium, color = themeForegroundMuted())
            }

            Spacer(Modifier.weight(1f))

            IconButton(onClick = onCreateFolder, modifier = Modifier.size(SpacingTokens.xl)) {
                Icon(Icons.Default.CreateNewFolder, "新建文件夹", modifier = Modifier.size(SpacingTokens.lg), tint = themePrimary())
            }
        }

        if (folders.isNotEmpty()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.heightIn(max = SizeTokens.folderGridMaxHeight),
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
                verticalArrangement = Arrangement.spacedBy(SpacingTokens.xs),
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
        colors = CardDefaults.cardColors(containerColor = themeBackgroundMuted().copy(alpha = 0.6f)),
        modifier = Modifier.height(SizeTokens.folderItemHeight),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = SpacingTokens.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Folder, null, tint = themePrimary(), modifier = Modifier.size(SizeTokens.iconLg))
            Spacer(Modifier.width(SizeTokens.compactSpacing))
            Text(
                folder.name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Box {
                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(SizeTokens.iconLg)) {
                    Icon(Icons.Default.MoreVert, "更多", modifier = Modifier.size(SizeTokens.iconXs))
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        onClick = { showMenu = false; onRename() },
                        leadingIcon = { Icon(Icons.Default.Edit, null, modifier = Modifier.size(SpacingTokens.lg)) },
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        onClick = { showMenu = false; onDelete() },
                        leadingIcon = { Icon(Icons.Default.Delete, null, modifier = Modifier.size(SpacingTokens.lg)) },
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
        Text(
            text = note.title.ifBlank { "无标题" },
            style = MaterialTheme.typography.headlineLarge,
            color = themeForeground(),
        )
        Spacer(Modifier.height(SpacingTokens.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val chipColors = note.type.chipColors()
            Text(
                text = note.type.displayName(),
                style = MaterialTheme.typography.labelSmall,
                color = chipColors.second,
                modifier = Modifier
                    .background(chipColors.first, ShapeTokens.smallShape)
                    .padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xs),
            )
            Spacer(Modifier.width(SpacingTokens.sm))
            Text(
                text = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(note.updatedAt)),
                style = MaterialTheme.typography.labelSmall,
                color = themeForegroundMuted(),
            )
        }
        val tagList = note.getTags()
        if (tagList.isNotEmpty()) {
            Spacer(Modifier.height(SpacingTokens.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(SizeTokens.compactSpacing)) {
                for (tag in tagList.take(5)) {
                    Text(
                        text = "#$tag",
                        style = MaterialTheme.typography.labelSmall,
                        color = themePrimary(),
                        modifier = Modifier
                            .background(themePrimary().copy(alpha = 0.1f), ShapeTokens.smallShape)
                            .padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xs),
                    )
                }
            }
        }
        Spacer(Modifier.height(SpacingTokens.lg))
        HorizontalDivider(color = themeBackgroundMuted())
        Spacer(Modifier.height(SpacingTokens.lg))
        if (note.content.isNotBlank()) {
            MarkdownText(
                text = note.content,
                maxChars = 8000,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text("（空笔记）", color = themeForegroundMuted(), style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(SpacingTokens.xl))
        Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
            Button(
                onClick = onEdit,
                colors = ButtonDefaults.buttonColors(containerColor = themePrimary()),
                shape = ShapeTokens.smallShape,
            ) {
                Icon(Icons.Default.Edit, contentDescription = "编辑", modifier = Modifier.size(SpacingTokens.lg))
                Spacer(Modifier.width(SpacingTokens.xs))
                Text("编辑")
            }
            OutlinedButton(
                onClick = onSendToChat,
                shape = ShapeTokens.smallShape,
            ) {
                Icon(Icons.Default.Chat, contentDescription = "发送到对话", modifier = Modifier.size(SpacingTokens.lg))
                Spacer(Modifier.width(SpacingTokens.xs))
                Text("发到对话")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    isPinnedSection: Boolean = false,
) {
    val sheetState = rememberModalBottomSheetState()
    var showSheet by remember { mutableStateOf(false) }
    val chipColors = note.type.chipColors()
    val ctx = LocalContext.current
    val primaryColor = themePrimary()

    Card(
        shape = ShapeTokens.mediumShape,
        colors = CardDefaults.cardColors(containerColor = themeCardWhite()),
        elevation = CardDefaults.cardElevation(defaultElevation = ElevationTokens.sm),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isPinnedSection) {
                    Modifier.drawBehind {
                        drawRect(
                            color = primaryColor,
                            topLeft = androidx.compose.ui.geometry.Offset(0f, 0f),
                            size = androidx.compose.ui.geometry.Size(SpacingTokens.xs.toPx(), size.height),
                        )
                    }
                } else Modifier
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { showSheet = true },
                )
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SpacingTokens.lg),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(ElevationTokens.none),
            ) {
                // 类型 chip
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(ShapeTokens.pillShape)
                            .background(chipColors.first)
                            .padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xxs),
                    ) {
                        Text(
                            note.type.displayName(),
                            style = MaterialTheme.typography.labelSmall,
                            color = chipColors.second,
                        )
                    }

                    if (relevance != null) {
                        Spacer(Modifier.width(SpacingTokens.sm))
                        Text(
                            "相关度 ${relevance}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = themePrimary(),
                            modifier = Modifier
                                .background(themePrimary().copy(alpha = 0.12f), ShapeTokens.tagShape)
                                .padding(horizontal = SizeTokens.compactSpacing, vertical = SpacingTokens.xxs),
                        )
                    }
                }

                Spacer(Modifier.height(SpacingTokens.sm))

                // 标题
                Text(
                    text = note.title.ifBlank { "无标题" },
                    style = MaterialTheme.typography.titleSmall,
                    color = themeForeground(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                // 摘要
                if (note.summary.isNotEmpty()) {
                    Spacer(Modifier.height(SpacingTokens.xs))
                    Text(
                        text = note.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = themeForegroundMuted(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // 标签
                val tags = note.getTags()
                if (tags.isNotEmpty()) {
                    Spacer(Modifier.height(SpacingTokens.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.xs)) {
                        tags.take(3).forEach { tag ->
                            Box(
                                modifier = Modifier
                                    .clip(ShapeTokens.tagShape)
                                    .background(themePrimary().copy(alpha = 0.08f))
                                    .padding(horizontal = SizeTokens.compactSpacing, vertical = SpacingTokens.xxs),
                            ) {
                                Text(
                                    tag,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = themePrimary(),
                                )
                            }
                        }
                        if (tags.size > 3) {
                            Text("+${tags.size - 3}", style = MaterialTheme.typography.labelSmall, color = themeForegroundMuted())
                        }
                    }
                }

                // 来源链接卡片
                if (note.sourceUrl.isNotEmpty()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = SizeTokens.sectionGapSm)
                            .clickable {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(note.sourceUrl))
                                runCatching { ctx.startActivity(intent) }
                            },
                        colors = CardDefaults.cardColors(containerColor = themeBackgroundMuted().copy(alpha = 0.5f)),
                        shape = ShapeTokens.iconShape,
                        elevation = CardDefaults.cardElevation(defaultElevation = ElevationTokens.none),
                    ) {
                        Row(
                            modifier = Modifier.padding(SpacingTokens.md),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Link,
                                contentDescription = "来源链接",
                                tint = themePrimary(),
                                modifier = Modifier.size(SizeTokens.iconMd),
                            )
                            Spacer(modifier = Modifier.width(SpacingTokens.sm))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = note.sourceUrl,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = themePrimary(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.OpenInNew,
                                contentDescription = "打开链接",
                                tint = themeForegroundMuted(),
                                modifier = Modifier.size(SpacingTokens.lg),
                            )
                        }
                    }
                }

                // 元信息行
                Spacer(Modifier.height(SizeTokens.sectionGapSm))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
                ) {
                    Text(
                        relativeTime(note.updatedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = themeForegroundMuted(),
                    )
                    Text("·", style = MaterialTheme.typography.labelSmall, color = themeBackgroundMuted())
                    Text(
                        noteMetaText(note),
                        style = MaterialTheme.typography.labelSmall,
                        color = themeForegroundMuted(),
                    )
                    if (linkCount > 0) {
                        Text("·", style = MaterialTheme.typography.labelSmall, color = themeBackgroundMuted())
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Hub,
                                "关联",
                                tint = themePrimary(),
                                modifier = Modifier.size(SpacingTokens.md),
                            )
                            Spacer(Modifier.width(SpacingTokens.xxs))
                            Text(
                                "$linkCount",
                                style = MaterialTheme.typography.labelSmall,
                                color = themePrimary(),
                            )
                        }
                    }
                }
            }

            // 右侧 Chevron / 置顶标记
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.padding(start = SpacingTokens.sm),
            ) {
                if (note.isPinned) {
                    Icon(
                        Icons.Default.PushPin,
                        "置顶",
                        tint = themePrimary(),
                        modifier = Modifier.size(SpacingTokens.lg),
                    )
                }
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = themeForegroundMuted(),
                    modifier = Modifier.size(SizeTokens.iconMd),
                )
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
            containerColor = themePrimary().copy(alpha = 0.08f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = ElevationTokens.none),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(SpacingTokens.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "发现关联",
                    style = MaterialTheme.typography.titleSmall,
                    color = themeForeground(),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "与「$sourceTitle」相关",
                    style = MaterialTheme.typography.labelSmall,
                    color = themeForegroundMuted(),
                )
            }

            Spacer(Modifier.height(SizeTokens.sectionGapSm))

            recommendedNotes.forEachIndexed { index, note ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = SpacingTokens.sm),
                        color = themeBackgroundMuted(),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNoteClick(note.id) }
                        .padding(vertical = SizeTokens.compactSpacing),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val chipColors = note.type.chipColors()
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(chipColors.first)
                            .size(SpacingTokens.xl),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            note.type.icon(),
                            contentDescription = null,
                            tint = chipColors.second,
                            modifier = Modifier.size(SizeTokens.iconXs),
                        )
                    }
                    Spacer(Modifier.width(SizeTokens.sectionGapSm))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = note.title.ifBlank { "无标题" },
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            color = themeForeground(),
                        )
                        if (note.summary.isNotEmpty()) {
                            Text(
                                text = note.summary,
                                style = MaterialTheme.typography.labelSmall,
                                color = themeForegroundMuted(),
                                maxLines = 1,
                            )
                        }
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = themeForegroundMuted(),
                        modifier = Modifier.size(SizeTokens.iconMd),
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
                contentDescription = null,
                modifier = Modifier.size(SizeTokens.emptyStateIcon),
                tint = themeForegroundMuted().copy(alpha = 0.4f),
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

private fun noteMetaText(note: Note): String {
    return when (note.sourceType) {
        SourceType.ASR, SourceType.MEETING_TRANSCRIPT -> "来自录音"
        else -> {
            val count = if (note.wordCount > 0) note.wordCount else note.content.length
            when {
                count >= 10000 -> "${count / 10000}万${(count % 10000) / 1000}千字"
                count >= 1000 -> "${count / 1000},${String.format("%03d", count % 1000)} 字"
                else -> "$count 字"
            }
        }
    }
}

private fun noteListFormatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
    return sdf.format(Date(timestamp))
}

private fun relativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val oneMinute = 60_000L
    val oneHour = 60 * oneMinute
    val oneDay = 24 * oneHour

    return when {
        diff < oneHour -> {
            val minutes = (diff / oneMinute).coerceAtLeast(1)
            "${minutes}分钟前"
        }
        diff < oneDay -> "${diff / oneHour}小时前"
        diff < 2 * oneDay -> "昨天"
        diff < 7 * oneDay -> "${diff / oneDay}天前"
        else -> noteListFormatTime(timestamp)
    }
}

@Composable
private fun NoteType.chipColors(): Pair<Color, Color> {
    return noteTypeContainerColor(this) to noteTypeContentColor(this)
}
