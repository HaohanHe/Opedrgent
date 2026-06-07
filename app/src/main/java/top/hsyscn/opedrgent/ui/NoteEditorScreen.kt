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
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf(TextFieldValue(initialContent)) }
    var noteType by remember { mutableStateOf(initialType) }
    var isSaving by remember { mutableStateOf(false) }
    var lastSavedAt by remember { mutableStateOf<Long?>(null) }
    var tags by remember { mutableStateOf<List<String>>(emptyList()) }
    var tagInput by remember { mutableStateOf("") }

    suspend fun save() {
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
            onSaved(id)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (noteId == null) "新建笔记" else "编辑笔记") },
                navigationIcon = {
                    IconButton(onClick = {
                        scope.launch { save() }
                        onBack()
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                actions = {
                    TextButton(
                        onClick = { scope.launch { save() } },
                        enabled = !isSaving && content.text.isNotBlank(),
                    ) {
                        if (isSaving) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = AccentBlue)
                        else Text("保存", fontWeight = FontWeight.SemiBold, color = AccentBlue)
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
                                    append("> 引用")
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            )
                        }
                        innerTextField()
                    }
                },
            )

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
