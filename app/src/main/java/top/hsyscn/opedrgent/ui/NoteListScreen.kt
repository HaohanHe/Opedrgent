package top.hsyscn.opedrgent.ui

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
    onNoteClick: (Long) -> Unit,
    onNewNote: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf<NoteType?>(null) }

    // 数据源
    val notesFlow = remember(searchQuery, selectedType) {
        if (searchQuery.isNotBlank()) {
            repository.searchNotes(searchQuery.trim())
        } else if (selectedType != null) {
            repository.getByType(selectedType!!)
        } else {
            repository.getAllNotes()
        }
    }
    val notes by notesFlow.collectAsState(initial = emptyList())
    val noteCount by repository.countAll().collectAsState(initial = 0L)

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // 顶部栏
            TopAppBar(
                title = { Text("笔记", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") }
                },
                actions = {
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

            Spacer(Modifier.height(8.dp))

            // 笔记列表
            if (notes.isEmpty()) {
                EmptyNoteState(onNewNote = onNewNote)
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(notes, key = { it.id }) { note ->
                        NoteCard(
                            note = note,
                            onClick = { onNoteClick(note.id) },
                            onTogglePin = { scope.launch { repository.togglePin(note.id) } },
                            onDelete = { scope.launch { repository.deleteNote(note.id) } },
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
private fun NoteCard(
    note: Note,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
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
