package top.hsyscn.opedrgent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
import top.hsyscn.opedrgent.note.ParsedSummary
import top.hsyscn.opedrgent.note.parseAiSummary
import top.hsyscn.opedrgent.ui.theme.AccentBlue
import top.hsyscn.opedrgent.ui.theme.AccentOrange
import top.hsyscn.opedrgent.ui.theme.AccentPurple
import top.hsyscn.opedrgent.ui.theme.TextPrimary
import top.hsyscn.opedrgent.ui.theme.DangerRed
import top.hsyscn.opedrgent.ui.theme.ErrorBackground
import top.hsyscn.opedrgent.ui.theme.ErrorBorder
import top.hsyscn.opedrgent.ui.theme.DeleteConfirmRed
import top.hsyscn.opedrgent.ui.theme.SuccessGreen
import top.hsyscn.opedrgent.ui.theme.SproutQuoteBg
import top.hsyscn.opedrgent.ui.theme.SproutSeedText
import top.hsyscn.opedrgent.ui.theme.SproutChipBg
import top.hsyscn.opedrgent.ui.components.AudioPlayer
import top.hsyscn.opedrgent.ui.components.EmptyStateView
import top.hsyscn.opedrgent.ui.components.SproutEmptyIllustration
import java.util.*
import top.hsyscn.opedrgent.ui.theme.themeCardBackground
import top.hsyscn.opedrgent.ui.theme.themeDividerColor
import top.hsyscn.opedrgent.ui.theme.themeTextGrey
import androidx.compose.ui.res.stringResource
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.ui.editor.components.AIActionButton
import top.hsyscn.opedrgent.ui.editor.components.MarkdownFormatToolbar
import top.hsyscn.opedrgent.ui.editor.preview.MarkdownPreview
import top.hsyscn.opedrgent.ui.editor.tabs.EditorAdditionalNotesTab
import top.hsyscn.opedrgent.ui.editor.utils.EditorUtils

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
    onSproutNote: (Long) -> Unit = { _ -> },
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
    /** 编辑器模式："richtext" 或 "markdown" */
    editorMode: String = "richtext",
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
    var spansJson by remember { mutableStateOf("") }

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
    val clipboardManager = LocalClipboardManager.current  // 保留此警告，需要后续迁移

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
                spans = spansJson,
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
        content = EditorUtils.insertFormatting(content, prefix, suffix)
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
                spansJson = existing.spans
            }
        }
    }

    LaunchedEffect(title, content.text, spansJson) {
        if (noteId != null && content.text.isNotEmpty()) {
            kotlinx.coroutines.delay(1000L)
            save()
        }
    }

    val wordCount = content.text.length
    val focusManager = LocalFocusManager.current
    val displayTitle = remember(title, content.text) {
        title.ifBlank { content.text.take(30).replace("\n", " ").ifBlank { "无标题" } }
    }

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
                PrimaryScrollableTabRow(
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
                                            colors = CardDefaults.cardColors(containerColor = themeCardBackground()),
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
                                                        color = themeTextGrey(),
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
                                                    Icons.AutoMirrored.Filled.OpenInNew,
                                                    contentDescription = "打开链接",
                                                    tint = themeTextGrey(),
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
                            var parsedSummary by remember { mutableStateOf(ParsedSummary()) }
                            LaunchedEffect(content.text) {
                                delay(400)
                                parsedSummary = parseAiSummary(content.text)
                            }

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
                                "保存于 ${EditorUtils.formatTimeAgo(lastSavedAt!!)}",
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
                onSprout = { onSproutNote(noteId) },
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
                title = { Text(stringResource(R.string.note_editor_delete_title)) },
                text = { Text(stringResource(R.string.note_editor_delete_confirm)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDeleteDialog = false
                            scope.launch { repository.deleteNote(noteId) }
                            onBack()
                        },
                    ) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.action_cancel)) }
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
                    title = { Text(if (noteId == null) stringResource(R.string.note_editor_new) else stringResource(R.string.note_editor_edit)) },
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
                        }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back)) }
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
                        else Text(stringResource(R.string.action_save), fontWeight = FontWeight.SemiBold, color = AccentBlue)
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
                                Text(stringResource(R.string.note_editor_discuss), fontWeight = FontWeight.SemiBold, color = AccentBlue)
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
                    Icon(Icons.AutoMirrored.Filled.Label, "标签", tint = AccentOrange, modifier = Modifier.size(16.dp))
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

            // 格式化工具栏（仅 Markdown 模式显示）
            if (showFormatToolbar && !isPreviewMode && editorMode == "markdown") {
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
            } else if (editorMode == "richtext") {
                // 富文本编辑模式（Notally 风格）
                RichTextEditor(
                    initialText = content.text,
                    initialSpans = spansJson,
                    onTextChange = { newText ->
                        content = TextFieldValue(newText, TextRange(newText.length))
                    },
                    onSpansChange = { newSpans ->
                        spansJson = newSpans
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    placeholder = "开始书写...\n\n选中文字即可加粗、斜体、代码等格式",
                )
            } else {
                // Markdown 编辑模式
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
                            val keyCode = keyEvent.nativeKeyEvent.keyCode
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
                                        try { onRequestCompletion(contextText) } catch (_: Exception) { "" }
                                    } else {
                                        EditorUtils.heuristicComplete(contextText)
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
                            "已保存 ${EditorUtils.formatTimeAgo(lastSavedAt!!)}",
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
            title = { Text(stringResource(R.string.note_editor_ai_action)) },
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
                    HorizontalDivider(color = themeDividerColor(), modifier = Modifier.padding(vertical = 4.dp))
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
            title = { Text(stringResource(R.string.note_editor_unsaved_title)) },
            text = {
                Text(
                    stringResource(R.string.note_editor_unsaved_desc),
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
                }) { Text(stringResource(R.string.note_editor_save_leave), fontWeight = FontWeight.SemiBold, color = AccentBlue) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showUnsavedDialog = false
                    onBack()
                }) { Text(stringResource(R.string.note_editor_no_save)) }
            },
        )
    }
}



