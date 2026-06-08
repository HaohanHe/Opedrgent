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
import androidx.compose.ui.graphics.Color
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
import top.hsyscn.opedrgent.ui.theme.AccentBlue
import java.text.SimpleDateFormat
import java.util.*

/**
 * 笔记列表页（参考得到大脑笔记列表设计）。
 *
 * 功能：
 * - 搜索栏（标题/内容模糊搜索）
 * - 类型筛选 Tab（全部/文本/会议/语音/链接/闪念/AI）
 * - 笔记卡片列表（置顶优先、时间倒序）
 * - 空状态引导
 * - 长按菜单（删除/置顶/分享）
 * - FAB 新建笔记
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    showBackButton: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
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
    val notesFlow = remember(searchQuery, selectedType, selectedTag, currentFolderId) {
        if (searchQuery.isNotBlank()) {
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
    val noteCount by repository.countAll().collectAsState(initial = 0L)
    val allTags by repository.getAllTags().collectAsState(initial = emptyList())

    // 加载关联推荐（基于最新笔记）
    LaunchedEffect(notes) {
        if (notes.isNotEmpty()) {
            val latest = notes.first()
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

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // 顶部栏
            TopAppBar(
                title = { Text("笔记", fontWeight = FontWeight.Bold) },
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
                            tint = AccentBlue,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Text(
                        text = "${noteCount.toInt()} 条",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 16.dp),
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )

            // 搜索栏
            SearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onSearch = { /* 已实时搜索 */ },
                active = false,
                onActiveChange = {},
                placeholder = { Text("搜索笔记...") },
                leadingIcon = { Icon(Icons.Default.Search, "搜索") },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, "清除")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(48.dp),
            ) {}

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

            // 笔记列表
            if (notes.isEmpty()) {
                EmptyNoteState(onNewNote = onNewNote)
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 关联推荐区域
                    if (recommendedNotes.isNotEmpty()) {
                        item(key = "recommendation") {
                            RecommendationCard(
                                sourceTitle = latestNoteTitle,
                                recommendedNotes = recommendedNotes.take(3),
                                onNoteClick = onNoteClick,
                            )
                        }
                    }

                    items(notes, key = { it.id }) { note ->
                        NoteCard(
                            note = note,
                            onClick = { onNoteClick(note.id) },
                            onTogglePin = { scope.launch { repository.togglePin(note.id) } },
                            onDelete = { scope.launch { repository.deleteNote(note.id) } },
                            onShare = { onShareNote(note.id) },
                            onSprout = { onSproutNote(note.id) },
                            onSendToChat = { onSendToChat(note.id) },
                            onSendWithSkill = { skillId -> onSendWithSkill(note.id, skillId) },
                            linkCount = repository.getLinkCount(note.id),
                        )
                    }
                }
            }
        }

        // FAB
        FloatingActionButton(
            onClick = onNewNote,
            containerColor = AccentBlue,
            contentColor = Color.White,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
                .size(56.dp),
            shape = CircleShape,
        ) {
            Icon(Icons.Default.Add, "新建笔记", tint = Color.White)
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
        selectedContainerColor = AccentBlue,
        selectedLabelColor = Color.White,
    )

    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        types.forEach { (type, label) ->
            val isSelected = type == selectedType
            FilterChip(
                selected = isSelected,
                onClick = { onTypeSelected(type) },
                label = {
                    Text(label, fontSize = 13.sp, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal)
                },
                colors = chipColors,
                shape = RoundedCornerShape(20.dp),
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
        selectedContainerColor = Color(0xFFE67E22),
        selectedLabelColor = Color.White,
    )

    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 全部标签按钮
        FilterChip(
            selected = selectedTag == null,
            onClick = { onTagSelected(null) },
            label = { Text("全部标签", fontSize = 12.sp) },
            colors = chipColors,
            shape = RoundedCornerShape(16.dp),
            border = if (selectedTag == null) null else BorderStroke(1.dp, Color(0xFFE67E22).copy(alpha = 0.3f)),
        )
        
        // 各个标签按钮
        tags.take(10).forEach { tag ->
            val isSelected = tag == selectedTag
            FilterChip(
                selected = isSelected,
                onClick = { onTagSelected(if (isSelected) null else tag) },
                label = { Text(tag, fontSize = 12.sp) },
                colors = chipColors,
                shape = RoundedCornerShape(16.dp),
                border = if (isSelected) null else BorderStroke(1.dp, Color(0xFFE67E22).copy(alpha = 0.3f)),
            )
        }
        
        // 如果标签超过10个，显示"+N"按钮
        if (tags.size > 10) {
            Text(
                "+${tags.size - 10}",
                fontSize = 12.sp,
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

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
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
                        Text(" > ", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        folder.name,
                        fontSize = 12.sp,
                        color = if (index == currentPath.lastIndex) AccentBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { onFolderClick(folder.id) },
                    )
                }
            } else {
                Icon(Icons.Default.Folder, "文件夹", tint = AccentBlue, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("文件夹", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.weight(1f))

            // 新建文件夹按钮
            IconButton(onClick = onCreateFolder, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.CreateNewFolder, "新建文件夹", modifier = Modifier.size(16.dp), tint = AccentBlue)
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
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        modifier = Modifier.height(60.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Folder, null, tint = AccentBlue, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                folder.name,
                fontSize = 12.sp,
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
    linkCount: Int = 0,
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // 第一行：类型图标 + 标题 + 置顶 + 时间
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 类型图标
                Surface(
                    color = note.type.color().copy(alpha = 0.1f),
                    shape = CircleShape,
                    modifier = Modifier.size(28.dp),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(note.type.icon(), contentDescription = null, tint = note.type.color(), modifier = Modifier.size(16.dp))
                    }
                }

                Spacer(Modifier.width(10.dp))

                // 标题
                Text(
                    text = note.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )

                // 置顶标记
                if (note.isPinned) {
                    Icon(Icons.Default.PushPin, "置顶", tint = AccentBlue, modifier = Modifier.size(16.dp))
                }

                // 关联数标记
                if (linkCount > 0) {
                    Spacer(Modifier.width(4.dp))
                    Surface(
                        color = AccentBlue.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Hub,
                                "关联",
                                tint = AccentBlue,
                                modifier = Modifier.size(12.dp),
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(
                                "$linkCount",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = AccentBlue,
                            )
                        }
                    }
                }

                // 更多菜单
                Box {
                    IconButton({ showMenu = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.MoreVert, "更多", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (note.isPinned) "取消置顶" else "置顶") },
                            onClick = { showMenu = false; onTogglePin() },
                            leadingIcon = { Icon(Icons.Default.PushPin, null) },
                        )
                        DropdownMenuItem(
                            text = { Text("🌱 发芽") },
                            onClick = { showMenu = false; onSprout() },
                            leadingIcon = { Icon(Icons.Default.AutoAwesome, null, tint = Color(0xFF4CAF50)) },
                        )
                        DropdownMenuItem(
                            text = { Text("发送到 AI 分析") },
                            onClick = { showMenu = false; onSendToChat() },
                            leadingIcon = { Icon(Icons.Default.ChatBubbleOutline, null, tint = AccentBlue) },
                        )
                        DropdownMenuItem(
                            text = { Text("💡 点评亮点") },
                            leadingIcon = { Icon(Icons.Default.AutoAwesome, null) },
                            onClick = {
                                showMenu = false
                                onSendWithSkill("insight_review")
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("🔍 深度拷问") },
                            leadingIcon = { Icon(Icons.Default.Search, null) },
                            onClick = {
                                showMenu = false
                                onSendWithSkill("critical_inquiry")
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("✨ 润色优化") },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = {
                                showMenu = false
                                onSendWithSkill("text_refine")
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("分享") },
                            onClick = { showMenu = false; onShare() },
                            leadingIcon = { Icon(Icons.Default.Share, null) },
                        )
                        DropdownMenuItem(
                            text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                            onClick = { showMenu = false; onDelete() },
                            leadingIcon = { Icon(Icons.Default.Delete, null) },
                        )
                    }
                }

                // 时间
                Text(
                    text = formatNoteTime(note.updatedAt),
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
                        Surface(color = AccentBlue.copy(alpha = 0.08f), shape = RoundedCornerShape(10.dp)) {
                            Text(tag, fontSize = 11.sp, color = AccentBlue, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                        }
                    }
                    if (tags.size > 3) {
                        Text("+${tags.size - 3}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecommendationCard(
    sourceTitle: String,
    recommendedNotes: List<Note>,
    onNoteClick: (Long) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // 标题行
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "🔗 发现关联",
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
                        modifier = Modifier.padding(vertical = 4.dp),
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
                            Icon(note.type.icon(), contentDescription = null, tint = note.type.color(), modifier = Modifier.size(14.dp))
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
                        contentDescription = null,
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
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.Edit,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "还没有笔记",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "点击右下角 + 创建第一条笔记，\n或从 AI 对话中保存精彩内容",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onNewNote, shape = RoundedCornerShape(12.dp)) {
            Icon(Icons.Default.Edit, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("写一条笔记")
        }
    }
}

// ==================== 工具函数 ====================

private fun formatNoteTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
    return sdf.format(Date(timestamp))
}
