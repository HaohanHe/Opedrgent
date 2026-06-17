package top.hsyscn.opedrgent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.text.style.TextOverflow
import kotlin.math.max
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.hsyscn.opedrgent.note.Note
import top.hsyscn.opedrgent.note.NoteRepository
import top.hsyscn.opedrgent.note.NoteType
import top.hsyscn.opedrgent.note.icon
import top.hsyscn.opedrgent.note.color
import top.hsyscn.opedrgent.note.displayName
import top.hsyscn.opedrgent.note.parseAiSummary
import top.hsyscn.opedrgent.ui.theme.AccentBlue
import top.hsyscn.opedrgent.ui.theme.TextGrey
import top.hsyscn.opedrgent.ui.theme.AccentPurple
import top.hsyscn.opedrgent.ui.theme.TextPrimary
import top.hsyscn.opedrgent.ui.theme.SurfaceLight
import top.hsyscn.opedrgent.ui.theme.CardBackground
import top.hsyscn.opedrgent.ui.theme.SurfaceElevated
import top.hsyscn.opedrgent.ui.theme.BorderLight
import top.hsyscn.opedrgent.ui.theme.DisabledColor
import top.hsyscn.opedrgent.ui.theme.DangerRed
import top.hsyscn.opedrgent.ui.theme.ErrorBackground
import top.hsyscn.opedrgent.ui.theme.ErrorBorder
import top.hsyscn.opedrgent.ui.theme.DeleteConfirmRed
import top.hsyscn.opedrgent.ui.theme.AccentOrange
import top.hsyscn.opedrgent.ui.theme.SuccessGreen
import top.hsyscn.opedrgent.ui.theme.WarningBg
import top.hsyscn.opedrgent.ui.theme.WarningColor
import top.hsyscn.opedrgent.ui.theme.InputBorder
import top.hsyscn.opedrgent.ui.theme.DividerColor
import top.hsyscn.opedrgent.ui.theme.SproutQuoteBg
import top.hsyscn.opedrgent.ui.theme.SproutSeedText
import top.hsyscn.opedrgent.ui.theme.SproutChipBg
import top.hsyscn.opedrgent.ui.components.AudioPlayer
import top.hsyscn.opedrgent.ui.components.EmptyStateView
import top.hsyscn.opedrgent.ui.components.SproutEmptyIllustration
import top.hsyscn.opedrgent.ui.components.BalloonEmptyIllustration
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
    onCorrectNote: (Long) -> Unit = {},
    onAddToKnowledgeBase: (Long) -> Unit = {},
    onAddTag: (Long) -> Unit = {},
    onAppendNote: (Long) -> Unit = {},
    /** 智能补全请求回调：传入当前上下文文本，返回补全建议（异步）。未提供时使用本地启发式补全。 */
    onRequestCompletion: (suspend (String) -> String)? = null,
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

    // Ghost Text AI 补全
    var ghostText by remember { mutableStateOf("") }           // 预测的补全文字
    var isGhostTextActive by remember { mutableStateOf(false) } // 是否正在显示 ghost text
    var ghostTextJob by remember { mutableStateOf<Job?>(null) } // 补全请求的协程 Job（用于取消）

    // 阅读模式专用状态
    var showReaderMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(1) }
    var currentNote by remember { mutableStateOf<Note?>(null) }
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
                sourceUri = currentNote?.sourceUri,
                originalContent = currentNote?.originalContent,
                sourceUrl = currentNote?.sourceUrl ?: "",
                sourceType = currentNote?.sourceType ?: top.hsyscn.opedrgent.note.SourceType.MANUAL,
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
                currentNote = existing
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
        // 阅读模式（只读展示）— Dedao Brain 风格 Tab + 浮动底栏
        // ════════════════════════════════════════
        val tabTitles = listOf("原文", "笔记内容", "发芽", "追加笔记")

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
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
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
                                color = AccentOrange.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text(
                                    tag,
                                    fontSize = 12.sp,
                                    color = AccentOrange,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }

                // 音频播放器（仅当笔记关联音频文件时显示）
                if (noteType == NoteType.MEETING || noteType == NoteType.ASR || noteType == NoteType.AUDIO) {
                    val audioUri = currentNote?.sourceUri
                    if (!audioUri.isNullOrBlank()) {
                        AudioPlayer(audioUri = audioUri)
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                // Tab 导航
                ScrollableTabRow(
                    selectedTabIndex = selectedTab,
                    edgePadding = 16.dp,
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = AccentBlue,
                    divider = {},
                ) {
                    tabTitles.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = {
                                Text(
                                    title,
                                    fontSize = 14.sp,
                                    fontWeight = if (selectedTab == index) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            },
                            selectedContentColor = AccentBlue,
                            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

                // 内容区域（Tab 切换）
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    when (selectedTab) {
                        0 -> {
                            // 原文
                            val original = if (noteType == NoteType.LINK) {
                                currentNote?.originalContent
                            } else {
                                currentNote?.originalContent
                            }
                            if (!original.isNullOrBlank()) {
                                // 原文 Tab 内容区：由外层 Column 提供 scroll，MarkdownPreview 内部不再嵌套
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                        .padding(horizontal = 16.dp, vertical = 12.dp)
                                        .padding(bottom = 80.dp),
                                ) {
                                    // 来源链接卡片
                                    val url = currentNote?.sourceUrl
                                    if (!url.isNullOrEmpty()) {
                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(16.dp)
                                                .clickable {
                                                    val intent = android.content.Intent(
                                                        android.content.Intent.ACTION_VIEW,
                                                        android.net.Uri.parse(url),
                                                    )
                                                    runCatching { context.startActivity(intent) }
                                                },
                                            colors = CardDefaults.cardColors(containerColor = CardBackground),
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(16.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Icon(
                                                    Icons.Default.Link,
                                                    contentDescription = null,
                                                    tint = AccentBlue,
                                                )
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        "来源链接",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = TextGrey,
                                                    )
                                                    Text(
                                                        url,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = AccentBlue,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                                Icon(
                                                    Icons.Default.OpenInNew,
                                                    contentDescription = "打开链接",
                                                    tint = TextGrey,
                                                )
                                            }
                                        }
                                    }
                                    MarkdownPreview(
                                        content = original,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            } else {
                                if (noteType == NoteType.ASR || noteType == NoteType.MEETING) {
                                    EmptyStateView(
                                        icon = {
                                            Icon(
                                                Icons.Default.Mic,
                                                contentDescription = null,
                                                modifier = Modifier.size(72.dp),
                                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                                            )
                                        },
                                        title = "正在汲取养分..",
                                        subtitle = "暂无原始转录文本",
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else {
                                    MarkdownPreview(
                                        content = content.text,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .verticalScroll(rememberScrollState())
                                            .padding(horizontal = 16.dp, vertical = 12.dp)
                                            .padding(bottom = 80.dp),
                                    )
                                }
                            }
                        }
                        1 -> {
                            // 笔记内容（含智能总结子标签）
                            var selectedSubTab by remember { mutableIntStateOf(0) }
                            val subTabTitles = listOf("智能总结", "章节概要", "金句精选", "待办事项")
                            val parsedSummary = remember(content.text) { parseAiSummary(content.text) }

                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(bottom = 80.dp),
                            ) {
                                // 子标签行
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    subTabTitles.forEachIndexed { index, title ->
                                        val selected = selectedSubTab == index
                                        Box(
                                            modifier = Modifier
                                                .clickable { selectedSubTab = index }
                                                .padding(horizontal = 8.dp, vertical = 6.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text(
                                                    title,
                                                    fontSize = 13.sp,
                                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                                    color = if (selected) AccentBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                                if (selected) {
                                                    Spacer(Modifier.height(2.dp))
                                                    Box(
                                                        modifier = Modifier
                                                            .width(20.dp)
                                                            .height(2.dp)
                                                            .background(AccentBlue, RoundedCornerShape(1.dp)),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                ) {
                                    when (selectedSubTab) {
                                        0 -> {
                                            val text = parsedSummary.smartSummary.ifBlank { content.text }
                                            MarkdownPreview(
                                                content = text,
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .verticalScroll(rememberScrollState()),
                                            )
                                        }
                                        1 -> {
                                            val text = parsedSummary.chapterOutline.ifBlank { parsedSummary.smartSummary.ifBlank { content.text } }
                                            MarkdownPreview(
                                                content = text,
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .verticalScroll(rememberScrollState()),
                                            )
                                        }
                                        2 -> {
                                            if (parsedSummary.keyQuotes.isNotEmpty()) {
                                                Column(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .verticalScroll(rememberScrollState()),
                                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                                ) {
                                                    parsedSummary.keyQuotes.forEach { quote ->
                                                        Surface(
                                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                                            shape = RoundedCornerShape(8.dp),
                                                            modifier = Modifier.fillMaxWidth(),
                                                        ) {
                                                            Row(
                                                                modifier = Modifier.padding(12.dp),
                                                                verticalAlignment = Alignment.Top,
                                                            ) {
                                                                Text(
                                                                    "\"",
                                                                    fontSize = 18.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    color = AccentBlue,
                                                                    modifier = Modifier.padding(end = 8.dp),
                                                                )
                                                                Text(
                                                                    quote,
                                                                    style = MaterialTheme.typography.bodyMedium,
                                                                    color = MaterialTheme.colorScheme.onSurface,
                                                                    modifier = Modifier.weight(1f),
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            } else {
                                                EmptyStateView(
                                                    icon = {
                                                        Icon(
                                                            Icons.Default.FormatQuote,
                                                            contentDescription = null,
                                                            modifier = Modifier.size(56.dp),
                                                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                                                        )
                                                    },
                                                    title = "暂无金句精选",
                                                    subtitle = "",
                                                    modifier = Modifier.fillMaxSize(),
                                                )
                                            }
                                        }
                                        3 -> {
                                            if (parsedSummary.actionItems.isNotEmpty()) {
                                                Column(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .verticalScroll(rememberScrollState()),
                                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                                ) {
                                                    parsedSummary.actionItems.forEach { item ->
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            modifier = Modifier.fillMaxWidth(),
                                                        ) {
                                                            Text(
                                                                "\u2610",
                                                                fontSize = 16.sp,
                                                                color = AccentBlue,
                                                                modifier = Modifier.padding(end = 10.dp),
                                                            )
                                                            Text(
                                                                item,
                                                                style = MaterialTheme.typography.bodyMedium,
                                                                color = MaterialTheme.colorScheme.onSurface,
                                                                modifier = Modifier.weight(1f),
                                                            )
                                                        }
                                                    }
                                                }
                                            } else {
                                                EmptyStateView(
                                                    icon = {
                                                        Icon(
                                                            Icons.Default.CheckCircle,
                                                            contentDescription = null,
                                                            modifier = Modifier.size(56.dp),
                                                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                                                        )
                                                    },
                                                    title = "暂无待办事项",
                                                    subtitle = "",
                                                    modifier = Modifier.fillMaxSize(),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        2 -> {
                            // 发芽 — 展示完整发芽报告
                            if (currentNote?.hasSproutReport() == true) {
                                val article = currentNote?.getSproutArticle()
                                val report = currentNote?.getSproutReport()
                                // 发芽 Tab 内容区：由外层 Column 提供 scroll
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                        .padding(horizontal = 16.dp, vertical = 12.dp)
                                        .padding(bottom = 80.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    // === 整体摘要（始终置顶）===
                                    val topSummary = article?.summary ?: report?.summary
                                    if (!topSummary.isNullOrBlank()) {
                                        Surface(
                                            color = SuccessGreen.copy(alpha = 0.08f),
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Column(modifier = Modifier.padding(14.dp)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(
                                                        Icons.Default.Lightbulb,
                                                        contentDescription = null,
                                                        tint = SuccessGreen,
                                                        modifier = Modifier.size(16.dp),
                                                    )
                                                    Spacer(Modifier.width(6.dp))
                                                    Text(
                                                        "核心洞察",
                                                        style = MaterialTheme.typography.labelMedium,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = SuccessGreen,
                                                    )
                                                }
                                                Spacer(Modifier.height(6.dp))
                                                Text(
                                                    topSummary,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    lineHeight = 20.sp,
                                                )
                                            }
                                        }
                                    }

                                    // === 完整文章章节 ===
                                    if (article != null && article.articles.isNotEmpty()) {
                                        article.articles.forEachIndexed { idx, section ->
                                            Surface(
                                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                                shape = RoundedCornerShape(12.dp),
                                                modifier = Modifier.fillMaxWidth(),
                                            ) {
                                                Column(modifier = Modifier.padding(14.dp)) {
                                                    // 章节标题 + 重要性标记
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Text(
                                                            "${String.format("%02d", idx + 1)}. ${section.title}",
                                                            style = MaterialTheme.typography.titleMedium,
                                                            fontWeight = FontWeight.Bold,
                                                            color = MaterialTheme.colorScheme.onSurface,
                                                            modifier = Modifier.weight(1f),
                                                        )
                                                        // 重要性圆点
                                                        repeat(section.importance.coerceIn(1, 5)) {
                                                            Box(
                                                                modifier = Modifier
                                                                    .size(6.dp)
                                                                    .background(AccentOrange, CircleShape)
                                                            )
                                                            if (section.importance.coerceIn(1, 5) > it) {
                                                                Spacer(Modifier.width(3.dp))
                                                            }
                                                        }
                                                    }

                                                    Spacer(Modifier.height(8.dp))

                                                    // 种子引用
                                                    Surface(
                                                        color = SproutQuoteBg,
                                                        shape = RoundedCornerShape(6.dp),
                                                    ) {
                                                        Text(
                                                            "种子：${section.seed}",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = SproutSeedText,
                                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                                        )
                                                    }

                                                    Spacer(Modifier.height(10.dp))

                                                    // 正文
                                                    MarkdownPreview(
                                                        content = section.body,
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .verticalScroll(rememberScrollState()),
                                                    )

                                                    // Aha 金句
                                                    if (section.ahaMoment.isNotBlank()) {
                                                        Spacer(Modifier.height(10.dp))
                                                        Row(modifier = Modifier.fillMaxWidth()) {
                                                            Icon(
                                                                Icons.Default.AutoAwesome,
                                                                contentDescription = null,
                                                                tint = AccentOrange,
                                                                modifier = Modifier.size(16.dp),
                                                            )
                                                            Spacer(Modifier.width(6.dp))
                                                            Text(
                                                                section.ahaMoment,
                                                                style = MaterialTheme.typography.bodyMedium,
                                                                color = AccentOrange,
                                                                fontWeight = FontWeight.Medium,
                                                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } else if (report != null) {
                                        // 兼容旧格式报告：关键要点
                                        if (report.keyPoints.isNotEmpty()) {
                                            Surface(
                                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                                shape = RoundedCornerShape(12.dp),
                                                modifier = Modifier.fillMaxWidth(),
                                            ) {
                                                Column(modifier = Modifier.padding(14.dp)) {
                                                    Text(
                                                        "关键要点",
                                                        style = MaterialTheme.typography.titleSmall,
                                                        fontWeight = FontWeight.SemiBold,
                                                    )
                                                    Spacer(Modifier.height(8.dp))
                                                    report.keyPoints.forEach { point ->
                                                        Row(modifier = Modifier.padding(vertical = 3.dp)) {
                                                            Text(
                                                                "\u2022",
                                                                modifier = Modifier.padding(end = 6.dp),
                                                            )
                                                            Text(point, style = MaterialTheme.typography.bodyMedium)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // === 行动建议 ===
                                    val allActionItems = article?.actionItems ?: report?.actionItems ?: emptyList()
                                    if (allActionItems.isNotEmpty()) {
                                        Surface(
                                            color = AccentBlue.copy(alpha = 0.06f),
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Column(modifier = Modifier.padding(14.dp)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(
                                                        Icons.Default.CheckCircle,
                                                        contentDescription = null,
                                                        tint = AccentBlue,
                                                        modifier = Modifier.size(16.dp),
                                                    )
                                                    Spacer(Modifier.width(6.dp))
                                                    Text(
                                                        "行动建议",
                                                        style = MaterialTheme.typography.labelMedium,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = AccentBlue,
                                                    )
                                                }
                                                Spacer(Modifier.height(8.dp))
                                                allActionItems.forEachIndexed { index, item ->
                                                    Row(modifier = Modifier.padding(vertical = 3.dp)) {
                                                        Text(
                                                            "${index + 1}.",
                                                            modifier = Modifier.width(20.dp),
                                                            color = MaterialTheme.colorScheme.primary,
                                                            fontWeight = FontWeight.Medium,
                                                        )
                                                        Text(item, style = MaterialTheme.typography.bodyMedium)
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // === 相关概念标签 ===
                                    val concepts = article?.relatedConcepts
                                    if (!concepts.isNullOrEmpty()) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Column(modifier = Modifier.padding(14.dp)) {
                                                Text(
                                                    "相关概念",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                                Spacer(Modifier.height(8.dp))
                                                FlowRow(
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                                ) {
                                                    concepts.forEach { concept ->
                                                        Surface(
                                                            shape = RoundedCornerShape(16.dp),
                                                            color = SproutChipBg,
                                                        ) {
                                                            Text(
                                                                concept,
                                                                fontSize = 12.sp,
                                                                color = TextPrimary,
                                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // === 元信息 ===
                                    if (article != null) {
                                        HorizontalDivider()
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Text(
                                                "模型: ${article.modelUsed.ifBlank { "默认" }}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Text(
                                                "阅读约 ${article.readingTimeMinutes.coerceAtLeast(1)} 分钟",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            } else {
                                var sproutTitle by remember { mutableStateOf("正在汲取养分..") }
                                LaunchedEffect(Unit) {
                                    delay(2000L)
                                    sproutTitle = "暂无发芽"
                                }
                                EmptyStateView(
                                    icon = { SproutEmptyIllustration() },
                                    title = sproutTitle,
                                    subtitle = "",
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        3 -> {
                            // 追加笔记 — 可编辑的笔记列表
                            EditorAdditionalNotesTab()
                        }
                    }

                    // 浮动底部操作栏 - 三个核心AI能力
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 2.dp,
                        shadowElevation = 6.dp,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .fillMaxWidth()
                            .height(64.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            // 润色
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable {
                                    onSendWithSkill(noteId ?: return@clickable, "text_refine")
                                },
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = Color(0xFFFF9800).copy(alpha = 0.12f),
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.fillMaxSize(),
                                    ) {
                                        Icon(
                                            Icons.Default.AutoFixHigh,
                                            contentDescription = "润色",
                                            tint = Color(0xFFFF9800),
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                                Spacer(Modifier.height(3.dp))
                                Text("润色", fontSize = 11.sp, color = Color(0xFFFF9800))
                            }

                            // 拷问
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable {
                                    onSendWithSkill(noteId ?: return@clickable, "critical_inquiry")
                                },
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = Color(0xFFF57C00).copy(alpha = 0.12f),
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.fillMaxSize(),
                                    ) {
                                        Icon(
                                            Icons.Default.Psychology,
                                            contentDescription = "拷问",
                                            tint = Color(0xFFF57C00),
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                                Spacer(Modifier.height(3.dp))
                                Text("拷问", fontSize = 11.sp, color = Color(0xFFF57C00))
                            }

                            // 发芽
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable {
                                    onSendWithSkill(noteId ?: return@clickable, "insight_sprout")
                                },
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = SuccessGreen.copy(alpha = 0.12f),
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.fillMaxSize(),
                                    ) {
                                        Icon(
                                            Icons.Default.AutoAwesome,
                                            contentDescription = "发芽",
                                            tint = SuccessGreen,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                                Spacer(Modifier.height(3.dp))
                                Text("发芽", fontSize = 11.sp, color = SuccessGreen)
                            }
                        }
                    }
                }

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
                                color = SuccessGreen,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            }
        }

        // 底部操作菜单
        if (showReaderMenu && noteId != null) {
            val menuNote = currentNote ?: Note(
                id = noteId,
                title = title,
                content = content.text,
                type = noteType,
                updatedAt = lastSavedAt ?: System.currentTimeMillis(),
            ).apply { setTags(tags) }

            val sheetState = rememberModalBottomSheetState()
            top.hsyscn.opedrgent.ui.components.NoteActionBottomSheet(
                note = menuNote,
                sheetState = sheetState,
                onDismiss = { showReaderMenu = false },
                onEdit = {
                    if (onEdit != {}) onEdit() else onBack()
                },
                onShare = {
                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(content.text))
                    scope.launch {
                        snackbarHostState.showSnackbar("已复制到剪贴板", duration = SnackbarDuration.Short)
                    }
                },
                onAppend = { onAppendNote(noteId) },
                onCorrect = { onCorrectNote(noteId) },
                onSprout = { onSendWithSkill(noteId, "insight_sprout") },
                onAddToKnowledgeBase = { onAddToKnowledgeBase(noteId) },
                onAddTag = { onAddTag(noteId) },
                onDelete = { showDeleteDialog = true },
                onTogglePin = {
                    scope.launch { repository.togglePin(noteId) }
                },
                onSendToChat = { onSendToChat(noteId) },
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
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
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
                                color = AccentOrange.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                ) {
                                    Text(tag, fontSize = 12.sp, color = AccentOrange)
                                    Spacer(Modifier.width(4.dp))
                                    IconButton(
                                        onClick = { removeTag(tag) },
                                        modifier = Modifier.size(14.dp),
                                    ) {
                                        Icon(Icons.Default.Close, "删除", modifier = Modifier.size(10.dp), tint = AccentOrange)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // 标签输入框
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Label, "标签", tint = AccentOrange, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = tagInput,
                        onValueChange = { tagInput = it },
                        textStyle = TextStyle(fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface),
                        singleLine = true,
                        cursorBrush = SolidColor(AccentOrange),
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
                            Icon(Icons.Default.Add, "添加", modifier = Modifier.size(16.dp), tint = AccentOrange)
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
                        .verticalScroll(rememberScrollState())
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
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .onPreviewKeyEvent { keyEvent ->
                            val keyCode = keyEvent.nativeKeyEvent?.keyCode ?: 0
                            when {
                                keyCode == android.view.KeyEvent.KEYCODE_TAB -> {
                                    if (isGhostTextActive && ghostText.isNotEmpty()) {
                                        // 接受 ghost text：将 ghost text 插入到光标位置
                                        val cursorPos = content.selection.start
                                        val newText = content.text.substring(0, cursorPos) + ghostText +
                                                content.text.substring(cursorPos)
                                        content = TextFieldValue(newText, TextRange(cursorPos + ghostText.length))
                                        isGhostTextActive = false
                                        ghostText = ""
                                        true // 消费事件
                                    } else false
                                }
                                keyCode == android.view.KeyEvent.KEYCODE_ESCAPE -> {
                                    if (isGhostTextActive) {
                                        isGhostTextActive = false
                                        ghostText = ""
                                        true
                                    } else false
                                }
                                else -> false
                            }
                        },
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
                            // Ghost Text AI 补全：光标后显示灰色预测文字
                            if (isGhostTextActive && ghostText.isNotEmpty()) {
                                val cursorPosition = content.selection.start
                                val textBeforeCursor = content.text.take(cursorPosition)
                                Text(
                                    text = ghostText,
                                    style = TextStyle(
                                        fontSize = 16.sp,
                                        lineHeight = 26.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.30f),
                                    ),
                                    modifier = Modifier.offset(
                                        x = with(LocalDensity.current) {
                                            // 基于光标前文字的估算宽度偏移
                                            val paint = android.graphics.Paint().apply {
                                                textSize = 16.sp.toPx()
                                                typeface = android.graphics.Typeface.MONOSPACE
                                            }
                                            paint.measureText(textBeforeCursor).toDp()
                                        },
                                    ),
                                )
                            }
                            // Tab 键接受提示（底部小字提示）
                            if (isGhostTextActive && ghostText.isNotEmpty()) {
                                Text(
                                    text = "Tab 接受 | Esc 取消",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AccentBlue.copy(alpha = 0.5f),
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(end = 4.dp, bottom = 4.dp),
                                )
                            }
                        }
                    },
                )

                // Ghost Text AI 补全触发逻辑
                LaunchedEffect(content.text) {
                    ghostTextJob?.cancel()
                    isGhostTextActive = false
                    ghostText = ""

                    // 只在有足够内容时触发（至少10个字符，且光标不在开头）
                    if (content.text.length >= 10 && content.selection.start > 5) {
                        ghostTextJob = launch {
                            delay(1500L) // 等待用户停止输入 1.5 秒

                            // 取光标所在行的最后 100 个字符作为上下文
                            val cursorPos = content.selection.start
                            val contextStart = max(0, cursorPos - 100)
                            val contextText = content.text.substring(contextStart, cursorPos)

                            // 检测是否在句子中间（以句号/换行/列表符结尾则不补全）
                            val lastChar = contextText.lastOrNull()?.toString() ?: ""
                            val shouldComplete = lastChar !in listOf("\n", "。", "！", "？", ".", "!", "?", ":", "：")

                            if (shouldComplete && contextText.trim().length > 3) {
                                isGhostTextActive = true
                                // 优先使用 LLM 补全回调，否则使用本地启发式补全
                                scope.launch {
                                    val completion = if (onRequestCompletion != null) {
                                        try { onRequestCompletion!!(contextText) } catch (_: Exception) { "" }
                                    } else {
                                        heuristicComplete(contextText)
                                    }
                                    ghostText = completion
                                }
                            }
                        }
                    }
                }

            }
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
                            color = SuccessGreen,
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
                    HorizontalDivider(color = DividerColor, modifier = Modifier.padding(vertical = 4.dp))
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
    // 注意：不在此处添加 verticalScroll，由调用方按需添加，避免双重嵌套崩溃
    Column(modifier = modifier) {
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

// ================================================================
// 追加笔记 Tab — 笔记编辑器内置的追加笔记功能
// ================================================================

/** 单条编辑器内笔记 */
private data class EditorNote(
    val id: String,
    val content: String,
    val createdAtMs: Long = System.currentTimeMillis(),
)

@Composable
private fun EditorAdditionalNotesTab() {
    var notes by remember { mutableStateOf(listOf<EditorNote>()) }
    var isEditing by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        if (notes.isEmpty() && !isEditing) {
            // 可操作的空状态
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 40.dp),
            ) {
                Text("暂无追加笔记", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                Text(
                    "在此处添加对笔记内容的补充和批注",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(16.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = SurfaceLight,
                    onClick = { isEditing = true },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        Icon(Icons.Default.Add, null, tint = AccentPurple, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("添加笔记", color = AccentPurple, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    }
                }
            }
        } else {
            // 笔记列表
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                itemsIndexed(notes) { _, note ->
                    EditorNoteItemCard(
                        note = note,
                        onEdit = {
                            editingId = note.id
                            inputText = note.content
                            isEditing = true
                        },
                        onDelete = { notes = notes.filter { it.id != note.id } },
                    )
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }

        // 输入区域
        if (isEditing) {
            HorizontalDivider()
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = SurfaceElevated,
                modifier = Modifier.padding(12.dp).fillMaxWidth(),
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (editingId != null) "编辑笔记" else "新建笔记",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { isEditing = false; inputText = ""; editingId = null }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, "关闭", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { Text("记录你的想法...", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp, max = 120.dp),
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentPurple,
                            unfocusedBorderColor = BorderLight,
                        ),
                        shape = RoundedCornerShape(8.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.End) {
                        Surface(shape = RoundedCornerShape(8.dp), color = Color.Transparent, onClick = { isEditing = false; inputText = ""; editingId = null }) {
                            Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (inputText.isNotBlank()) AccentPurple else DisabledColor,
                            onClick = {
                                if (inputText.isNotBlank()) {
                                    if (editingId != null) {
                                        notes = notes.map { if (it.id == editingId) it.copy(content = inputText.trim()) else it }
                                    } else {
                                        notes = notes + EditorNote(System.nanoTime().toString(), inputText.trim())
                                    }
                                    isEditing = false
                                    inputText = ""
                                    editingId = null
                                }
                            },
                        ) {
                            Text(
                                text = if (editingId != null) "保存" else "添加",
                                color = Color.White,
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        } else if (notes.isNotEmpty()) {
            // 浮动添加按钮
            Surface(
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, BorderLight),
                onClick = { isEditing = true },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Icon(Icons.Default.Add, null, tint = AccentPurple, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("追加笔记...", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                }
            }
        }
    }
}

/** 编辑器内单条笔记卡片 */
@Composable
private fun EditorNoteItemCard(note: EditorNote, onEdit: () -> Unit, onDelete: () -> Unit) {
    var showConfirm by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = SurfaceElevated,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = AccentPurple,
                    modifier = Modifier.size(20.dp),
                ) {}
                Spacer(Modifier.width(8.dp))
                Text(formatTimeAgo(note.createdAtMs), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onEdit, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Edit, "编辑", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                }
                IconButton(onClick = { showConfirm = true }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Delete, "删除", tint = DangerRed, modifier = Modifier.size(14.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(note.content, fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurface)

            if (showConfirm) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = ErrorBackground,
                    border = BorderStroke(1.dp, ErrorBorder),
                    modifier = Modifier.padding(top = 6.dp).fillMaxWidth(),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text("删除这条笔记?", color = DeleteConfirmRed, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Surface(shape = RoundedCornerShape(4.dp), color = Color.Transparent, onClick = { showConfirm = false }) {
                            Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                        }
                        Surface(shape = RoundedCornerShape(4.dp), color = DeleteConfirmRed, onClick = { onDelete(); showConfirm = false }) {
                            Text("删除", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                        }
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

/**
 * 本地启发式文本补全（LLM 未接入时的 fallback）。
 *
 * 基于常见中文写作模式提供简单补全建议：
 * - 常见动词后接宾语
 * - 列表项自动补全下一项前缀
 * - 标点后的常见接续词
 */
private fun heuristicComplete(context: String): String {
    val trimmed = context.trimEnd()
    if (trimmed.isEmpty()) return ""

    // 列表模式检测：如果当前行以数字/符号开头，提示下一项
    if (Regex("""^(\d+[\.\、]|\-|\*)\s+""").containsMatchIn(trimmed.split("\n").lastOrNull() ?: "")) {
        val lines = trimmed.split("\n")
        val lastLine = lines.lastOrNull() ?: ""
        val match = Regex("""^(\d+[\.\、]|\-|\*)\s+""").find(lastLine)
        if (match != null) {
            val prefix = match.value
            // 数字列表递增
            val numMatch = Regex("""^(\d+)""").find(prefix)
            if (numMatch != null) {
                val nextNum = (numMatch.value.toInt() + 1).toString()
                return prefix.replaceFirst(Regex("""^\d+"""), nextNum) + " "
            }
            return prefix
        }
    }

    // 常见句尾补全
    return when {
        trimmed.endsWith("首先") -> "，其次"
        trimmed.endsWith("其次") -> "，再次"
        trimmed.endsWith("再次") -> "，最后"
        trimmed.endsWith("一方面") -> "，另一方面"
        trimmed.endsWith("例如") -> "，"
        trimmed.endsWith("包括") -> "："
        trimmed.endsWith("因为") -> "，所以"
        trimmed.endsWith("虽然") -> "，但是"
        trimmed.endsWith("不仅") -> "，而且"
        trimmed.endsWith("总") -> "结"
        trimmed.endsWith("具") -> "体来说"
        else -> ""
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
