package top.hsyscn.opedrgent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(
    repository: NoteRepository,
    noteId: Long? = null,
    initialType: NoteType = NoteType.TEXT,
    initialContent: String = "",
    onSaved: (Long) -> Unit = {},
    onSendToChat: (Long) -> Unit = {},
    onSendWithSkill: (Long, String) -> Unit = { _, _ -> },
    onOpenEditorTeam: (String) -> Unit = { _ -> },
    onBack: () -> Unit,
    forceReadOnly: Boolean = false,
    onEdit: () -> Unit = {},  // 阅读模式专用：点击编辑时跳转到编辑器
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf(TextFieldValue(initialContent)) }
    var noteType by remember { mutableStateOf(initialType) }
    var isSaving by remember { mutableStateOf(false) }
    var lastSavedAt by remember { mutableStateOf<Long?>(null) }
    var tags by remember { mutableStateOf<List<String>>(emptyList()) }
    var tagInput by remember { mutableStateOf("") }
    var isPreviewMode by remember { mutableStateOf(false) }
    var showFormatToolbar by remember { mutableStateOf(true) }
    var showAiMenu by remember { mutableStateOf(false) }

    // 阅读模式专用状态
    var showReaderMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    // 未保存更改追踪：记录上次保存时的内容快照
    var lastSavedContentSnapshot by remember { mutableStateOf(initialContent) }
    var showUnsavedDialog by remember { mutableStateOf(false) }

    suspend fun save(showGraphInfo: Boolean = false) {
        if (isSaving) return
        isSaving = true
        try {
            val note = Note(
                id = noteId ?: 0,
                title = title.ifBlank { content.text.take(30).replace("\n", " ") },
                content = content.text,
                type = noteType,
                wordCount = content.text.length,
            )
            note.setTags(tags)
            val id = repository.saveNote(note)
            lastSavedAt = System.currentTimeMillis()
            // 同步保存快照，用于未保存更改检测
            lastSavedContentSnapshot = content.text
            onSaved(id)
            // 手动保存时显示知识图谱关联信息
            if (showGraphInfo && id > 0) {
                val newLinkCount = repository.knowledgeGraph.getLinkCount(id.toString())
                snackbarHostState.showSnackbar(
                    message = if (newLinkCount > 0) "已保存并发现 $newLinkCount 个关联" else "笔记已保存",
                    duration = SnackbarDuration.Short
                )
            }
        } finally {
            isSaving = false
        }
    }

    fun addTag() {
        val tag = tagInput.trim()
        if (tag.isNotEmpty() && !tags.contains(tag) && tags.size < 200 && tag.length <= 40) {
            tags = tags + tag
            tagInput = ""
        }
    }

    fun removeTag(tagToRemove: String) {
        tags = tags.filter { it != tagToRemove }
    }

    fun insertFormatting(prefix: String, suffix: String = "") {
        val selection = content.selection
        val text = content.text
        val selectedText = text.substring(selection.start, selection.end)
        val newText = text.substring(0, selection.start) + prefix + selectedText + suffix + text.substring(selection.end)
        content = TextFieldValue(newText, TextRange(selection.start + prefix.length + selectedText.length + suffix.length))
    }

    LaunchedEffect(noteId) {
        if (noteId != null) {
            val existing = repository.getNoteById(noteId)
            if (existing != null) {
                title = existing.title
                content = TextFieldValue(existing.content, TextRange(existing.content.length))
                noteType = existing.type
                tags = existing.getTags()
                lastSavedAt = existing.updatedAt
            }
        }
    }

    LaunchedEffect(title, content.text) {
        if (noteId != null && content.text.isNotEmpty()) {
            kotlinx.coroutines.delay(1000L)
            save()
        }
    }

    val wordCount = content.text.length
    val focusManager = LocalFocusManager.current
    val displayTitle = title.ifBlank { content.text.take(30).replace("\n", " ").ifBlank { "无标题" } }

    if (forceReadOnly) {
        // ════════════════════════════════════════
        // 阅读模式（只读展示）
        // ════════════════════════════════════════
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            displayTitle,
                            maxLines = 1,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showReaderMenu = true }) {
                            Icon(Icons.Default.MoreVert, "更多操作")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                // 标签只读展示
                if (tags.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        tags.forEach { tag ->
                            Surface(
                                color = Color(0xFFE67E22).copy(alpha = 0.1f),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text(
                                    tag,
                                    fontSize = 12.sp,
                                    color = Color(0xFFE67E22),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                // 内容区域：Markdown 渲染
                MarkdownPreview(
                    content = content.text,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )

                // 元信息行
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    tonalElevation = 2.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(noteType.icon(), null, tint = noteType.color(), modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(noteType.displayName(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                        Spacer(Modifier.width(12.dp))
                        Text("$wordCount 字", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                        if (lastSavedAt != null) {
                            Text(
                                "保存于 ${formatTimeAgo(lastSavedAt!!)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF4CAF50),
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }

                // 底部操作栏
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        // 编辑按钮
                        ReaderActionButton(
                            icon = Icons.Default.Edit,
                            label = "编辑",
                            onClick = { if (onEdit != {}) onEdit() else onBack() },
                        )
                        // 讨论按钮
                        ReaderActionButton(
                            icon = Icons.Default.ChatBubbleOutline,
                            label = "讨论",
                            onClick = { onSendToChat(noteId ?: return@ReaderActionButton) },
                        )
                        // 发芽按钮
                        ReaderActionButton(
                            icon = Icons.Default.AutoAwesome,
                            label = "发芽",
                            onClick = { onSendWithSkill(noteId ?: return@ReaderActionButton, "insight_sprout") },
                        )
                    }
                }
            }
        }

        // 三点菜单
        DropdownMenu(
            expanded = showReaderMenu,
            onDismissRequest = { showReaderMenu = false },
        ) {
            DropdownMenuItem(
                text = { Text("编辑") },
                leadingIcon = { Icon(Icons.Default.Edit, null) },
                onClick = {
                    showReaderMenu = false
                    if (onEdit != {}) onEdit() else onBack()
                },
            )
            DropdownMenuItem(
                text = { Text("发芽分析") },
                leadingIcon = { Icon(Icons.Default.AutoAwesome, null) },
                onClick = {
                    showReaderMenu = false
                    onSendWithSkill(noteId ?: return@DropdownMenuItem, "insight_sprout")
                },
            )
            DropdownMenuItem(
                text = { Text("在对话中讨论") },
                leadingIcon = { Icon(Icons.Default.ChatBubbleOutline, null) },
                onClick = {
                    showReaderMenu = false
                    onSendToChat(noteId ?: return@DropdownMenuItem)
                },
            )
            DropdownMenuItem(
                text = { Text("分享") },
                leadingIcon = { Icon(Icons.Default.Share, null) },
                onClick = {
                    showReaderMenu = false
                    // 复制内容到剪贴板作为简易分享
                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(content.text))
                    scope.launch {
                        snackbarHostState.showSnackbar("已复制到剪贴板", duration = SnackbarDuration.Short)
                    }
                },
            )
            DropdownMenuItem(
                text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                onClick = {
                    showReaderMenu = false
                    showDeleteDialog = true
                },
            )
        }

        // 删除确认对话框
        if (showDeleteDialog && noteId != null) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { Text("删除笔记") },
                text = { Text("确定要删除这条笔记吗？此操作不可撤销。") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDeleteDialog = false
                            scope.launch { repository.deleteNote(noteId) }
                            onBack()
                        },
                    ) { Text("删除", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
                },
            )
        }
    } else {
        // ════════════════════════════════════════
        // 编辑模式（原有逻辑不变）
        // ════════════════════════════════════════
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = { Text(if (noteId == null) "新建笔记" else "编辑笔记") },
                    navigationIcon = {
                        IconButton(onClick = {
                            // 检测是否有未保存的更改
                            val hasUnsavedChanges = content.text != lastSavedContentSnapshot &&
                                content.text.isNotBlank() &&
                                content.text != initialContent
                            if (hasUnsavedChanges) {
                                showUnsavedDialog = true
                            } else {
                                scope.launch { save() }
                                onBack()
                            }
                        }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                    },
                    actions = {
                        // 预览/编辑模式切换
                        IconButton(onClick = { isPreviewMode = !isPreviewMode }) {
                            Icon(
                                if (isPreviewMode) Icons.Default.Edit else Icons.Default.Visibility,
                                if (isPreviewMode) "编辑" else "预览",
                                tint = AccentBlue,
                            )
                        }

                        // 格式化工具栏切换
                        IconButton(onClick = { showFormatToolbar = !showFormatToolbar }) {
                            Icon(Icons.Default.FormatBold, "格式化", tint = AccentBlue)
                        }

                        // AI 操作按钮（只在编辑已有笔记时显示）
                        if (noteId != null) {
                            IconButton(onClick = { showAiMenu = true }) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = "AI 操作", tint = AccentBlue)
                            }
                        }

                        TextButton(
                            onClick = { scope.launch { save(showGraphInfo = true) } },
                            enabled = !isSaving && content.text.isNotBlank(),
                        ) {
                            if (isSaving) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = AccentBlue)
                            else Text("保存", fontWeight = FontWeight.SemiBold, color = AccentBlue)
                        }

                        // 在聊天中讨论按钮
                        if (noteId != null) {
                            TextButton(
                                onClick = {
                                    scope.launch { save() }
                                    onSendToChat(noteId)
                                },
                                enabled = content.text.isNotBlank(),
                            ) {
                                Icon(Icons.Default.ChatBubbleOutline, null, modifier = Modifier.size(16.dp), tint = AccentBlue)
                                Spacer(Modifier.width(4.dp))
                                Text("讨论", fontWeight = FontWeight.SemiBold, color = AccentBlue)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
        ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // 标题输入
            BasicTextField(
                value = title,
                onValueChange = { title = it },
                textStyle = TextStyle(
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                singleLine = true,
                cursorBrush = SolidColor(AccentBlue),
                decorationBox = { innerTextField ->
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        if (title.isEmpty()) {
                            Text("标题（可选）", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                        }
                        innerTextField()
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )

            // 标签输入区域
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                // 已添加的标签
                if (tags.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        tags.forEach { tag ->
                            Surface(
                                color = Color(0xFFE67E22).copy(alpha = 0.1f),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                ) {
                                    Text(tag, fontSize = 12.sp, color = Color(0xFFE67E22))
                                    Spacer(Modifier.width(4.dp))
                                    IconButton(
                                        onClick = { removeTag(tag) },
                                        modifier = Modifier.size(14.dp),
                                    ) {
                                        Icon(Icons.Default.Close, "删除", modifier = Modifier.size(10.dp), tint = Color(0xFFE67E22))
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // 标签输入框
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Label, "标签", tint = Color(0xFFE67E22), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = tagInput,
                        onValueChange = { tagInput = it },
                        textStyle = TextStyle(fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface),
                        singleLine = true,
                        cursorBrush = SolidColor(Color(0xFFE67E22)),
                        decorationBox = { innerTextField ->
                            Box {
                                if (tagInput.isEmpty()) {
                                    Text("添加标签（最多200个，每个40字符）", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                                }
                                innerTextField()
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { addTag() }),
                        modifier = Modifier.weight(1f),
                    )
                    if (tagInput.isNotEmpty()) {
                        IconButton(onClick = { addTag() }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Add, "添加", modifier = Modifier.size(16.dp), tint = Color(0xFFE67E22))
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), modifier = Modifier.padding(vertical = 8.dp))

            // 格式化工具栏
            if (showFormatToolbar && !isPreviewMode) {
                MarkdownFormatToolbar(
                    onBold = { insertFormatting("**", "**") },
                    onItalic = { insertFormatting("*", "*") },
                    onCode = { insertFormatting("`", "`") },
                    onHeading1 = { insertFormatting("# ") },
                    onHeading2 = { insertFormatting("## ") },
                    onHeading3 = { insertFormatting("### ") },
                    onBulletList = { insertFormatting("- ") },
                    onNumberedList = { insertFormatting("1. ") },
                    onQuote = { insertFormatting("> ") },
                    onLink = { insertFormatting("[", "](url)") },
                    onImage = { insertFormatting("![alt](", ")") },
                )
            }

            // 内容输入/预览
            if (isPreviewMode) {
                // Markdown 预览模式
                MarkdownPreview(
                    content = content.text,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            } else {
                // 编辑模式
                BasicTextField(
                    value = content,
                    onValueChange = { content = it },
                    textStyle = TextStyle(
                        fontSize = 16.sp,
                        lineHeight = 26.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(AccentBlue),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    decorationBox = { innerTextField ->
                        Box {
                            if (content.text.isEmpty()) {
                                Text(
                                    buildAnnotatedString {
                                        append("开始书写...\n\n")
                                        append("支持 Markdown 格式：\n")
                                        append("# 标题\n")
                                        append("**加粗** *斜体* `代码`\n")
                                        append("- 无序列表\n")
                                        append("1. 有序列表\n")
                                        append("> 引用\n")
                                        append("![图片](url)\n")
                                        append("[链接](url)")
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                )
                            }
                            innerTextField()
                        }
                    },
                )
            }

            // 底部状态栏
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                tonalElevation = 2.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(noteType.icon(), null, tint = noteType.color(), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(noteType.displayName(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    Spacer(Modifier.weight(1f))

                    Text("$wordCount 字", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    if (lastSavedAt != null) {
                        Text(
                            "已保存 ${formatTimeAgo(lastSavedAt!!)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF4CAF50),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
        }  // end else (编辑模式)
    }

    // AI 操作弹窗（编辑模式专用）
    if (showAiMenu && noteId != null) {
        AlertDialog(
            onDismissRequest = { showAiMenu = false },
            title = { Text("AI 操作") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AIActionButton("点评", "识别亮点，正向强化", "insight_review") {
                        showAiMenu = false
                        onSendWithSkill(noteId, "insight_review")
                    }
                    AIActionButton("拷问", "深度追问，挑战逻辑", "critical_inquiry") {
                        showAiMenu = false
                        onSendWithSkill(noteId, "critical_inquiry")
                    }
                    AIActionButton("润色", "优化表达，提升质量", "text_refine") {
                        showAiMenu = false
                        onSendWithSkill(noteId, "text_refine")
                    }
                    HorizontalDivider(color = Color(0xFFE0E0E0), modifier = Modifier.padding(vertical = 4.dp))
                    AIActionButton("AI 编辑团", "8人编辑团协作创作", "editor_team") {
                        showAiMenu = false
                        scope.launch { save() }
                        onOpenEditorTeam(content.text)
                    }
                }
            },
            confirmButton = {},
        )
    }

    // 未保存更改确认对话框
    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text("未保存的更改") },
            text = {
                Text(
                    "您有尚未保存的内容。是否要在离开前保存？",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showUnsavedDialog = false
                    scope.launch {
                        save(showGraphInfo = true)
                        onBack()
                    }
                }) { Text("保存并离开", fontWeight = FontWeight.SemiBold, color = AccentBlue) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showUnsavedDialog = false
                    onBack()
                }) { Text("不保存") }
            },
        )
    }
}

@Composable
private fun AIActionButton(label: String, description: String, skillId: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(Modifier.height(2.dp))
                Text(description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.AutoAwesome, null, tint = AccentBlue, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun MarkdownFormatToolbar(
    onBold: () -> Unit,
    onItalic: () -> Unit,
    onCode: () -> Unit,
    onHeading1: () -> Unit,
    onHeading2: () -> Unit,
    onHeading3: () -> Unit,
    onBulletList: () -> Unit,
    onNumberedList: () -> Unit,
    onQuote: () -> Unit,
    onLink: () -> Unit,
    onImage: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        tonalElevation = 2.dp,
    ) {
        Column {
            // 第一行：基础格式
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FormatButton(icon = Icons.Default.FormatBold, onClick = onBold, description = "加粗")
                FormatButton(icon = Icons.Default.FormatItalic, onClick = onItalic, description = "斜体")
                FormatButton(icon = Icons.Default.Code, onClick = onCode, description = "代码")
                FormatButton(icon = Icons.Default.FormatListBulleted, onClick = onBulletList, description = "无序列表")
                FormatButton(icon = Icons.Default.FormatListNumbered, onClick = onNumberedList, description = "有序列表")
                FormatButton(icon = Icons.Default.FormatQuote, onClick = onQuote, description = "引用")
                FormatButton(icon = Icons.Default.Link, onClick = onLink, description = "链接")
                FormatButton(icon = Icons.Default.Image, onClick = onImage, description = "图片")
            }
            
            // 第二行：标题
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FormatButton(icon = Icons.Default.Title, onClick = onHeading1, description = "标题1", text = "H1")
                FormatButton(icon = Icons.Default.Title, onClick = onHeading2, description = "标题2", text = "H2")
                FormatButton(icon = Icons.Default.Title, onClick = onHeading3, description = "标题3", text = "H3")
            }
        }
    }
}

@Composable
private fun FormatButton(
    icon: ImageVector,
    onClick: () -> Unit,
    description: String,
    text: String? = null,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(36.dp),
    ) {
        if (text != null) {
            Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        } else {
            Icon(icon, description, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun MarkdownPreview(
    content: String,
    modifier: Modifier = Modifier,
) {
    // 简单的 Markdown 预览（实际项目中可以使用专业的 Markdown 渲染库）
    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        content.split("\n").forEach { line ->
            when {
                line.startsWith("# ") -> Text(
                    text = line.removePrefix("# "),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                line.startsWith("## ") -> Text(
                    text = line.removePrefix("## "),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
                line.startsWith("### ") -> Text(
                    text = line.removePrefix("### "),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                line.startsWith("- ") -> Text(
                    text = "• ${line.removePrefix("- ")}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
                line.startsWith("> ") -> Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.padding(vertical = 2.dp),
                ) {
                    Text(
                        text = line.removePrefix("> "),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                else -> Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

private fun formatTimeAgo(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000L -> "刚刚"
        diff < 3600_000L -> "${diff / 60_000}分钟前"
        diff < 86400_000L -> "${diff / 3600_000}小时前"
        else -> "${diff / 86400_000}天前"
    }
}

/** 阅读模式底部操作按钮 */
@Composable
private fun ReaderActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = AccentBlue.copy(alpha = 0.1f),
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = AccentBlue,
                modifier = Modifier.padding(10.dp).size(22.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
