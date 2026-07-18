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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalClipboardManager
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.core.net.toUri
import top.hsyscn.opedrgent.ui.components.dropContentTarget
import top.hsyscn.opedrgent.ui.components.isAtLeastMediumWidth
import top.hsyscn.opedrgent.ui.components.isExpandedWidth
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import kotlin.math.max
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.note.Note
import top.hsyscn.opedrgent.note.NoteRepository
import top.hsyscn.opedrgent.note.NoteType
import top.hsyscn.opedrgent.note.icon
import top.hsyscn.opedrgent.note.color
import top.hsyscn.opedrgent.note.displayName
import top.hsyscn.opedrgent.note.ParsedSummary
import top.hsyscn.opedrgent.note.parseAiSummary
import top.hsyscn.opedrgent.ui.theme.AccentPurple
import top.hsyscn.opedrgent.ui.theme.DangerRed
import top.hsyscn.opedrgent.ui.theme.ErrorBackground
import top.hsyscn.opedrgent.ui.theme.ErrorBorder
import top.hsyscn.opedrgent.ui.theme.DeleteConfirmRed
import top.hsyscn.opedrgent.ui.theme.ElevationTokens
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import top.hsyscn.opedrgent.ui.theme.SizeTokens
import top.hsyscn.opedrgent.ui.theme.SpacingTokens
import top.hsyscn.opedrgent.ui.theme.customColors
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
    /** 发芽服务（用于重新发芽旧格式报告） */
    sproutService: top.hsyscn.opedrgent.note.SproutService? = null,
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
    var isGenerating by remember { mutableStateOf(false) }
    val sproutMutex = remember { Mutex() }
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current  // 保留此警告，需要后续迁移

    // 未保存更改追踪：记录上次保存时的内容快照
    var lastSavedContentSnapshot by remember { mutableStateOf(initialContent) }
    var showUnsavedDialog by remember { mutableStateOf(false) }

    suspend fun save(showGraphInfo: Boolean = false): Long {
        if (isSaving) return -1
        isSaving = true
        return try {
            // 仅当内容真正发生变化时才刷新 updatedAt，避免浏览/无变更保存把“最后编辑时间”刷成当前时间。
            val contentChanged = content.text != lastSavedContentSnapshot
            val note = Note(
                id = noteId ?: 0,
                title = title, // 保持用户输入（含空），由 Repository 决定是否兜底
                content = content.text,
                type = noteType,
                wordCount = content.text.length,
                sourceUri = currentNote?.sourceUri,
                originalContent = currentNote?.originalContent,
                sourceUrl = currentNote?.sourceUrl ?: "",
                sourceType = currentNote?.sourceType ?: top.hsyscn.opedrgent.note.SourceType.MANUAL,
                spans = spansJson,
                // 保留不可编辑的字段，避免全列 UPDATE 覆盖成默认值
                summary = currentNote?.summary ?: "",
                folderId = currentNote?.folderId,
                isPinned = currentNote?.isPinned ?: false,
                isDeleted = currentNote?.isDeleted ?: false,
                createdAt = currentNote?.createdAt ?: System.currentTimeMillis(),
                // 内容未变时保留原 updatedAt；内容变化或新建时使用当前时间。
                updatedAt = if (contentChanged || currentNote == null) System.currentTimeMillis() else currentNote!!.updatedAt,
                sproutReportJson = currentNote?.sproutReportJson,
            )
            note.setTags(tags)
            val id = repository.saveNote(note)
            lastSavedAt = System.currentTimeMillis()
            // 同步保存快照，用于未保存更改检测
            lastSavedContentSnapshot = content.text
            onSaved(id)
            // 手动保存时显示知识图谱关联信息
            if (showGraphInfo && id > 0) {
                val newLinkCount = repository.getLinkCount(id)
                snackbarHostState.showSnackbar(
                    message = if (newLinkCount > 0) context.getString(R.string.note_editor_saved_with_links, newLinkCount) else context.getString(R.string.note_editor_saved),
                    duration = SnackbarDuration.Short
                )
            }
            id
        } finally {
            isSaving = false
        }
    }

    /** 保存并退出：退出编辑页时才触发一次 LLM 标题生成 */
    suspend fun saveAndExit() {
        val id = save()
        if (id > 0) {
            repository.finalizeTitle(id)
        }
        onBack()
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

    /**
     * 在光标位置插入拖入的文本。
     */
    fun insertDroppedText(text: String) {
        val cursorPos = content.selection.start.coerceIn(0, content.text.length)
        val newText = buildString {
            append(content.text.substring(0, cursorPos))
            append(text)
            append(content.text.substring(cursorPos))
        }
        val newCursor = (cursorPos + text.length).coerceAtMost(newText.length)
        content = TextFieldValue(newText, TextRange(newCursor))
    }

    /**
     * 保存拖入的图片到应用私有目录，并在光标位置插入 Markdown 图片链接。
     */
    suspend fun insertDroppedImage(uri: Uri) {
        try {
            val fileName = "dropped_${System.currentTimeMillis()}.jpg"
            val destDir = java.io.File(context.filesDir, "note_images").apply { mkdirs() }
            val destFile = java.io.File(destDir, fileName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            insertDroppedText(context.getString(R.string.note_editor_image_markdown, destFile.toUri()))
            snackbarHostState.showSnackbar(context.getString(R.string.note_editor_image_inserted))
        } catch (e: Exception) {
            snackbarHostState.showSnackbar(context.getString(R.string.note_editor_image_insert_failed, e.message ?: ""))
        }
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
                // 关键：同步快照为已加载内容，否则首次自动保存会误判为“内容已变”而刷新 updatedAt。
                lastSavedContentSnapshot = existing.content
            }
        }
    }

    // 自动保存：仅在编辑模式（非阅读模式）触发，避免浏览笔记时把 updatedAt 刷成当前时间。
    LaunchedEffect(title, content.text, spansJson) {
        if (!forceReadOnly && noteId != null && content.text.isNotEmpty()) {
            kotlinx.coroutines.delay(1000L)
            save()
        }
    }

    val wordCount = content.text.length
    val focusManager = LocalFocusManager.current
    val displayTitle = remember(title, content.text) {
        title.ifBlank { content.text.take(30).replace("\n", " ").ifBlank { context.getString(R.string.note_editor_title_placeholder) } }
    }

    if (forceReadOnly) {
        // ════════════════════════════════════════
        // 阅读模式（只读展示）— opedrgent 风格 Tab + 浮动底栏
        // ════════════════════════════════════════
        val tabOriginal = stringResource(R.string.note_editor_tab_original)
        val tabContent = stringResource(R.string.note_editor_tab_content)
        val tabSprout = stringResource(R.string.note_editor_tab_sprout)
        val tabAppend = stringResource(R.string.note_editor_tab_append)
        val tabTitles = listOf(tabOriginal, tabContent, tabSprout, tabAppend)

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
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                        IconButton(onClick = { showReaderMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_more))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { padding ->
            val contentMaxWidth = when {
                isExpandedWidth() -> 980.dp
                isAtLeastMediumWidth() -> 760.dp
                else -> Dp.Unspecified
            }
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter,
            ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .then(
                        if (contentMaxWidth != Dp.Unspecified) {
                            Modifier.widthIn(max = contentMaxWidth)
                        } else {
                            Modifier.fillMaxWidth()
                        }
                    )
                    .padding(top = padding.calculateTopPadding())
                    .background(MaterialTheme.colorScheme.background)
            ) {
                // 标签只读展示
                if (tags.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = SpacingTokens.lg, vertical = SpacingTokens.sm),
                        horizontalArrangement = Arrangement.spacedBy(SizeTokens.compactSpacing),
                    ) {
                        tags.forEach { tag ->
                            Surface(
                                color = MaterialTheme.customColors.accentOrange.copy(alpha = 0.1f),
                                shape = ShapeTokens.mediumShape,
                            ) {
                                Text(
                                    tag,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.customColors.accentOrange,
                                    modifier = Modifier.padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xs),
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
                    edgePadding = SpacingTokens.lg,
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.customColors.accentBlue,
                    divider = {},
                ) {
                    tabTitles.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = {
                                Text(
                                    title,
                                    style = if (selectedTab == index) {
                                        MaterialTheme.typography.titleSmall
                                    } else {
                                        MaterialTheme.typography.bodyMedium
                                    },
                                )
                            },
                            selectedContentColor = MaterialTheme.customColors.accentBlue,
                            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

                // 内容区域（Tab 切换）— Box 布局，meta info 浮动在底部
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
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
                                        .padding(horizontal = SpacingTokens.lg, vertical = SpacingTokens.md)
                                        .padding(bottom = SpacingTokens.xxl),
                                ) {
                                    // 来源链接卡片
                                    val url = currentNote?.sourceUrl
                                    if (!url.isNullOrEmpty()) {
                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(SpacingTokens.lg)
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
                                                modifier = Modifier.padding(SpacingTokens.lg),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Icon(
                                                    Icons.Default.Link,
                                                    contentDescription = stringResource(R.string.note_editor_source_link),
                                                    tint = MaterialTheme.customColors.accentBlue,
                                                )
                                                Spacer(modifier = Modifier.width(SpacingTokens.md))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        stringResource(R.string.note_editor_source_link),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = themeTextGrey(),
                                                    )
                                                    Text(
                                                        url,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = MaterialTheme.customColors.accentBlue,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                                Icon(
                                                    Icons.AutoMirrored.Filled.OpenInNew,
                                                    contentDescription = stringResource(R.string.cd_open_link),
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
                                                contentDescription = stringResource(R.string.cd_microphone),
                                                modifier = Modifier.size(SizeTokens.emptyStateIcon),
                                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                                            )
                                        },
                                        title = stringResource(R.string.note_editor_generating_sprout),
                                        subtitle = stringResource(R.string.note_editor_no_original_text),
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else {
                                    MarkdownPreview(
                                        content = content.text,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .verticalScroll(rememberScrollState())
                                            .padding(horizontal = SpacingTokens.lg, vertical = SpacingTokens.md)
                                            .padding(bottom = SpacingTokens.xxl),
                                    )
                                }
                            }
                        }
                        1 -> {
                            // 笔记内容（含智能总结子标签）
                            var selectedSubTab by remember { mutableIntStateOf(0) }
                            val subtabSummary = stringResource(R.string.note_editor_subtab_summary)
                            val subtabChapters = stringResource(R.string.note_editor_subtab_chapters)
                            val subtabQuotes = stringResource(R.string.note_editor_subtab_quotes)
                            val subtabTodos = stringResource(R.string.note_editor_subtab_todos)
                            val subTabTitles = listOf(subtabSummary, subtabChapters, subtabQuotes, subtabTodos)
                            var parsedSummary by remember { mutableStateOf(ParsedSummary()) }
                            LaunchedEffect(content.text) {
                                delay(400)
                                parsedSummary = parseAiSummary(content.text)
                            }

                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(bottom = SpacingTokens.xxl),
                            ) {
                                // 子标签行
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = SpacingTokens.md, vertical = SpacingTokens.xs),
                                    horizontalArrangement = Arrangement.spacedBy(SpacingTokens.xs),
                                ) {
                                    subTabTitles.forEachIndexed { index, title ->
                                        val selected = selectedSubTab == index
                                        Box(
                                            modifier = Modifier
                                                .clickable { selectedSubTab = index }
                                                .padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xs),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text(
                                                    title,
                                                    style = if (selected) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
                                                    color = if (selected) MaterialTheme.customColors.accentBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                                if (selected) {
                                                    Spacer(Modifier.height(SpacingTokens.xxs))
                                                    Box(
                                                        modifier = Modifier
                                                            .width(20.dp)
                                                            .height(SpacingTokens.xxs)
                                                            .background(MaterialTheme.customColors.accentBlue, CircleShape),
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
                                        .padding(horizontal = SpacingTokens.lg, vertical = SpacingTokens.md),
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
                                                    verticalArrangement = Arrangement.spacedBy(SizeTokens.sectionGapSm),
                                                ) {
                                                    parsedSummary.keyQuotes.forEach { quote ->
                                                        Surface(
                                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                                            shape = ShapeTokens.smallShape,
                                                            modifier = Modifier.fillMaxWidth(),
                                                        ) {
                                                            Row(
                                                                modifier = Modifier.padding(SpacingTokens.md),
                                                                verticalAlignment = Alignment.Top,
                                                            ) {
                                                                Text(
                                                        "\"",
                                                        style = MaterialTheme.typography.headlineLarge,
                                                        color = MaterialTheme.customColors.accentBlue,
                                                        modifier = Modifier.padding(end = SpacingTokens.sm),
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
                                                            contentDescription = stringResource(R.string.cd_quote),
                                                            modifier = Modifier.size(SizeTokens.emptyStateIcon),
                                                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                                                        )
                                                    },
                                                    title = stringResource(R.string.note_editor_no_key_quotes),
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
                                                    verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
                                                ) {
                                                    parsedSummary.actionItems.forEach { item ->
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            modifier = Modifier.fillMaxWidth(),
                                                        ) {
                                                            Text(
                                                                "\u2610",
                                                                style = MaterialTheme.typography.bodyLarge,
                                                                color = MaterialTheme.customColors.accentBlue,
                                                                modifier = Modifier.padding(end = SpacingTokens.sm),
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
                                                            contentDescription = stringResource(R.string.cd_todo),
                                                            modifier = Modifier.size(SizeTokens.emptyStateIcon),
                                                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                                                        )
                                                    },
                                                    title = stringResource(R.string.note_editor_no_action_items),
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
                            // 切换到发芽 tab 时刷新 currentNote（确保从 NoteSproutScreen 返回后数据最新）
                            LaunchedEffect(selectedTab) {
                                if (noteId != null && selectedTab == 2) {
                                    val refreshed = repository.getNoteById(noteId)
                                    if (refreshed != null) {
                                        currentNote = refreshed
                                    }
                                }
                            }
                            if (currentNote?.hasSproutReport() == true) {
                                val article = currentNote?.getSproutArticle()
                                val report = currentNote?.getSproutReport()
                                // 发芽 Tab 内容区：由外层 Column 提供 scroll
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                        .padding(horizontal = SpacingTokens.lg, vertical = SpacingTokens.md)
                                        .padding(bottom = SpacingTokens.xxl),
                                    verticalArrangement = Arrangement.spacedBy(SpacingTokens.lg),
                                ) {
                                    // === 整体摘要（始终置顶）===
                                    val topSummary = article?.summary ?: report?.summary
                                    if (!topSummary.isNullOrBlank()) {
                                        Surface(
                                            color = MaterialTheme.customColors.successGreen.copy(alpha = 0.08f),
                                            shape = ShapeTokens.mediumShape,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Column(modifier = Modifier.padding(SpacingTokens.md)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(
                                                        Icons.Default.Lightbulb,
                                                        contentDescription = stringResource(R.string.note_editor_core_insight),
                                                        tint = MaterialTheme.customColors.successGreen,
                                                        modifier = Modifier.size(SizeTokens.iconSm),
                                                    )
                                                    Spacer(Modifier.width(SizeTokens.compactSpacing))
                                                    Text(
                                                        stringResource(R.string.note_editor_core_insight),
                                                        style = MaterialTheme.typography.labelMedium,
                                                        color = MaterialTheme.customColors.successGreen,
                                                    )
                                                }
                                                Spacer(Modifier.height(SizeTokens.compactSpacing))
                                                Text(
                                                        topSummary,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                    )
                                            }
                                        }
                                    }

                                    // === 完整文章章节 ===
                                    if (article != null) {
                                        // 新版格式：显示完整文章（即使 articles 为空也显示 summary）
                                        if (article.articles.isNotEmpty()) {
                                            article.articles.forEachIndexed { idx, section ->
                                                Surface(
                                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                                    shape = ShapeTokens.mediumShape,
                                                    modifier = Modifier.fillMaxWidth(),
                                                ) {
                                                    Column(modifier = Modifier.padding(SpacingTokens.md)) {
                                                        // 章节标题 + 重要性标记
                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                            Text(
                                                                "${String.format("%02d", idx + 1)}. ${section.title}",
                                                                style = MaterialTheme.typography.titleMedium,
                                                                color = MaterialTheme.colorScheme.onSurface,
                                                                modifier = Modifier.weight(1f),
                                                            )
                                                            // 重要性圆点
                                                            repeat(section.importance.coerceIn(1, 5)) {
                                                                Box(
                                                                    modifier = Modifier
                                                                        .size(6.dp)
                                                                        .background(MaterialTheme.customColors.accentOrange, CircleShape)
                                                                )
                                                                if (section.importance.coerceIn(1, 5) > it) {
                                                                    Spacer(Modifier.width(3.dp))
                                                                }
                                                            }
                                                        }

                                                        Spacer(Modifier.height(SpacingTokens.sm))

                                                        // 种子引用
                                                        Surface(
                                                            color = MaterialTheme.customColors.sproutQuoteBg,
                                                            shape = ShapeTokens.smallShape,
                                                        ) {
                                                            Text(
                                                                stringResource(R.string.note_editor_seed_prefix, section.seed),
                                                                style = MaterialTheme.typography.bodySmall,
                                                                color = MaterialTheme.customColors.sproutSeedText,
                                                                modifier = Modifier.padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xs),
                                                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                                            )
                                                        }

                                                        Spacer(Modifier.height(SizeTokens.sectionGapSm))

                                                        // 正文（外层 Column 已提供 verticalScroll，此处不再嵌套）
                                                        MarkdownPreview(
                                                            content = section.body,
                                                            modifier = Modifier.fillMaxWidth(),
                                                        )

                                                        // 震惊瞬间金句
                                                        if (section.shockingMoment.isNotBlank()) {
                                                            Spacer(Modifier.height(SizeTokens.sectionGapSm))
                                                            Row(modifier = Modifier.fillMaxWidth()) {
                                                                Icon(
                                                                    Icons.Default.AutoAwesome,
                                                                    contentDescription = stringResource(R.string.note_editor_shocking_moment),
                                                                    tint = MaterialTheme.customColors.accentOrange,
                                                                    modifier = Modifier.size(SizeTokens.iconSm),
                                                                )
                                                                Spacer(Modifier.width(SizeTokens.compactSpacing))
                                                                Text(
                                                                    section.shockingMoment,
                                                                    style = MaterialTheme.typography.labelLarge,
                                                                    color = MaterialTheme.customColors.accentOrange,
                                                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } else if (report != null) {
                                        // 旧格式报告：提示用户重新发芽以获取完整内容
                                        Surface(
                                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                                            shape = ShapeTokens.mediumShape,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(SpacingTokens.lg),
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                            ) {
                                                Icon(
                                                    Icons.Default.Lightbulb,
                                                    contentDescription = stringResource(R.string.note_editor_legacy_sprout),
                                                    tint = MaterialTheme.customColors.accentOrange,
                                                    modifier = Modifier.size(32.dp),
                                                )
                                                Spacer(Modifier.height(SpacingTokens.md))
                                                Text(
                                                    stringResource(R.string.note_editor_legacy_detected),
                                                    style = MaterialTheme.typography.titleMedium,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                )
                                                Spacer(Modifier.height(SpacingTokens.sm))
                                                Text(
                                                    stringResource(R.string.note_editor_legacy_desc),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    textAlign = TextAlign.Center,
                                                )
                                                Spacer(Modifier.height(SpacingTokens.lg))
                                                Button(
                                                    onClick = {
                                                        scope.launch {
                                                            if (!sproutMutex.tryLock()) {
                                                                snackbarHostState.showSnackbar(context.getString(R.string.msg_sprout_in_progress))
                                                                return@launch
                                                            }
                                                            val note = currentNote
                                                            val service = sproutService
                                                            if (note == null || service == null) {
                                                                sproutMutex.unlock()
                                                                snackbarHostState.showSnackbar(context.getString(R.string.msg_sprout_unavailable))
                                                                return@launch
                                                            }
                                                            isGenerating = true
                                                            try {
                                                                val otherNotesCtx = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                                    try { repository.getRecentNotesIndex(5) } catch (_: Exception) { "" }
                                                                }
                                                                try {
                                                                    service.sprout(note.content, otherNotesCtx).fold(
                                                                        onSuccess = { newArticle ->
                                                                            note.setSproutArticle(newArticle)
                                                                            repository.saveNote(note)
                                                                            // 从数据库重新加载，触发 Compose 重组
                                                                            val refreshed = repository.getNoteById(note.id)
                                                                            if (refreshed != null) {
                                                                                currentNote = refreshed
                                                                            }
                                                                        },
                                                                        onFailure = { e ->
                                                                            snackbarHostState.showSnackbar(context.getString(R.string.msg_sprout_failed, e.message ?: ""))
                                                                        },
                                                                    )
                                                                } catch (e: Exception) {
                                                                    snackbarHostState.showSnackbar(context.getString(R.string.msg_sprout_failed, e.message ?: ""))
                                                                }
                                                            } finally {
                                                                isGenerating = false
                                                                sproutMutex.unlock()
                                                            }
                                                        }
                                                    },
                                                    enabled = !isGenerating,
                                                ) {
                                                    if (isGenerating) {
                                                        CircularProgressIndicator(
                                                            modifier = Modifier.size(SizeTokens.iconSm),
                                                            strokeWidth = SpacingTokens.xxs,
                                                            color = MaterialTheme.colorScheme.onPrimary,
                                                        )
                                                        Spacer(Modifier.width(SpacingTokens.sm))
                                                    }
                                                    Icon(Icons.Default.AutoAwesome, contentDescription = stringResource(R.string.note_editor_regenerate_sprout), Modifier.size(SizeTokens.iconSm))
                                                    Spacer(Modifier.width(SizeTokens.compactSpacing))
                                                    Text(stringResource(R.string.note_editor_regenerate_sprout))
                                                }
                                            }
                                        }
                                    }

                                    // === 行动建议 ===
                                    val allActionItems = article?.actionItems ?: report?.actionItems ?: emptyList()
                                    if (allActionItems.isNotEmpty()) {
                                        Surface(
                                            color = MaterialTheme.customColors.accentBlue.copy(alpha = 0.06f),
                                            shape = ShapeTokens.mediumShape,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Column(modifier = Modifier.padding(SpacingTokens.md)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(
                                                        Icons.Default.CheckCircle,
                                                        contentDescription = stringResource(R.string.note_editor_action_suggestion),
                                                        tint = MaterialTheme.customColors.accentBlue,
                                                        modifier = Modifier.size(SizeTokens.iconSm),
                                                    )
                                                    Spacer(Modifier.width(SizeTokens.compactSpacing))
                                                    Text(
                                                        stringResource(R.string.note_editor_action_suggestion),
                                                        style = MaterialTheme.typography.labelMedium,
                                                        color = MaterialTheme.customColors.accentBlue,
                                                    )
                                                }
                                                Spacer(Modifier.height(SpacingTokens.sm))
                                                allActionItems.forEachIndexed { index, item ->
                                                    Row(modifier = Modifier.padding(vertical = SpacingTokens.xxs)) {
                                                        Text(
                                                            "${index + 1}.",
                                                            modifier = Modifier.width(20.dp),
                                                            color = MaterialTheme.customColors.accentBlue,
                                                            style = MaterialTheme.typography.labelLarge,
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
                                            shape = ShapeTokens.mediumShape,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Column(modifier = Modifier.padding(SpacingTokens.md)) {
                                                Text(
                                                    stringResource(R.string.note_editor_related_concepts),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                                Spacer(Modifier.height(SpacingTokens.sm))
                                                FlowRow(
                                                    horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
                                                    verticalArrangement = Arrangement.spacedBy(SpacingTokens.xs),
                                                ) {
                                                    concepts.forEach { concept ->
                                                        Surface(
                                                            shape = ShapeTokens.largeShape,
                                                            color = MaterialTheme.customColors.sproutChipBg,
                                                        ) {
                                                            Text(
                                                                concept,
                                                                style = MaterialTheme.typography.labelMedium,
                                                                color = MaterialTheme.colorScheme.onSurface,
                                                                modifier = Modifier.padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xs),
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
                                            modifier = Modifier.fillMaxWidth().padding(top = SpacingTokens.sm),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            val modelDefault = stringResource(R.string.note_editor_model_default)
                                            Text(
                                                stringResource(R.string.note_editor_model_used, article.modelUsed.ifBlank { modelDefault }),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Text(
                                                stringResource(R.string.note_editor_reading_minutes, article.readingTimeMinutes.coerceAtLeast(1)),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            } else {
                                val generatingSprout = stringResource(R.string.note_editor_generating_sprout)
                                var sproutTitle by remember { mutableStateOf(generatingSprout) }
                                LaunchedEffect(Unit) {
                                    delay(2000L)
                                    sproutTitle = context.getString(R.string.note_editor_no_sprout)
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
                }

                } // end Box (content)

                // 元信息行 — 浮动在内容区域底部
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding(),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    tonalElevation = ElevationTokens.md,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = SpacingTokens.lg, vertical = SpacingTokens.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(noteType.icon(), contentDescription = stringResource(R.string.cd_note_type), tint = noteType.color(), modifier = Modifier.size(SizeTokens.iconXs))
                        Spacer(Modifier.width(SpacingTokens.xs))
                        Text(noteType.displayName(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                        Spacer(Modifier.width(SpacingTokens.md))
                        Text(stringResource(R.string.note_editor_word_count, wordCount), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                        if (lastSavedAt != null) {
                            Text(
                                stringResource(R.string.note_editor_saved_at, EditorUtils.formatTimeAgo(context, lastSavedAt!!)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.customColors.successGreen,
                                modifier = Modifier.padding(start = SpacingTokens.sm),
                            )
                        }
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
                        snackbarHostState.showSnackbar(context.getString(R.string.msg_copied_to_clipboard), duration = SnackbarDuration.Short)
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
                                scope.launch { saveAndExit() }
                            }
                        }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back)) }
                    },
                    actions = {
                        // 预览/编辑模式切换
                        val editLabel = stringResource(R.string.action_edit)
                        val previewLabel = stringResource(R.string.note_editor_preview)
                        IconButton(onClick = { isPreviewMode = !isPreviewMode }) {
                            Icon(
                                if (isPreviewMode) Icons.Default.Edit else Icons.Default.Visibility,
                                contentDescription = if (isPreviewMode) editLabel else previewLabel,
                                tint = MaterialTheme.customColors.accentBlue,
                            )
                        }

                        // 格式化工具栏切换
                        IconButton(onClick = { showFormatToolbar = !showFormatToolbar }) {
                            Icon(Icons.Default.FormatBold, contentDescription = stringResource(R.string.cd_format), tint = MaterialTheme.customColors.accentBlue)
                        }

                        // AI 操作按钮（只在编辑已有笔记时显示）
                        if (noteId != null) {
                            IconButton(onClick = { showAiMenu = true }) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = stringResource(R.string.note_editor_ai_action), tint = MaterialTheme.customColors.accentBlue)
                            }
                        }

                        TextButton(
                            onClick = { scope.launch { save(showGraphInfo = true) } },
                            enabled = !isSaving && content.text.isNotBlank(),
                        ) {
                            if (isSaving) CircularProgressIndicator(modifier = Modifier.size(SizeTokens.iconMd), strokeWidth = SpacingTokens.xxs, color = MaterialTheme.customColors.accentBlue)
                        else Text(stringResource(R.string.action_save), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.customColors.accentBlue)
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
                                Icon(Icons.Default.ChatBubbleOutline, contentDescription = stringResource(R.string.cd_discuss), modifier = Modifier.size(SizeTokens.iconSm), tint = MaterialTheme.customColors.accentBlue)
                                Spacer(Modifier.width(SpacingTokens.xs))
                                Text(stringResource(R.string.note_editor_discuss), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.customColors.accentBlue)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { padding ->
        val contentMaxWidth = when {
            isExpandedWidth() -> 980.dp
            isAtLeastMediumWidth() -> 760.dp
            else -> Dp.Unspecified
        }
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .then(
                    if (contentMaxWidth != Dp.Unspecified) {
                        Modifier.widthIn(max = contentMaxWidth)
                    } else {
                        Modifier.fillMaxWidth()
                    }
                )
                .padding(padding)
                .imePadding()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // 标题输入
            BasicTextField(
                value = title,
                onValueChange = { title = it },
                textStyle = MaterialTheme.typography.headlineLarge,
                singleLine = true,
                cursorBrush = SolidColor(MaterialTheme.customColors.accentBlue),
                decorationBox = { innerTextField ->
                    Box(modifier = Modifier.padding(horizontal = SpacingTokens.lg, vertical = SpacingTokens.md)) {
                        if (title.isEmpty()) {
                            Text(stringResource(R.string.note_editor_title_hint), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                        }
                        innerTextField()
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )

            // 标签输入区域
            Column(modifier = Modifier.padding(horizontal = SpacingTokens.lg)) {
                // 已添加的标签
                if (tags.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(SizeTokens.compactSpacing),
                    ) {
                        tags.forEach { tag ->
                            Surface(
                                color = MaterialTheme.customColors.accentOrange.copy(alpha = 0.1f),
                                shape = ShapeTokens.mediumShape,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xs),
                                ) {
                                    Text(tag, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.customColors.accentOrange)
                                    Spacer(Modifier.width(SpacingTokens.xs))
                                    IconButton(
                                        onClick = { removeTag(tag) },
                                        modifier = Modifier.size(SizeTokens.iconXs),
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_delete), modifier = Modifier.size(10.dp), tint = MaterialTheme.customColors.accentOrange)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(SpacingTokens.sm))
                }

                // 标签输入框
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.Label, contentDescription = stringResource(R.string.cd_tag), tint = MaterialTheme.customColors.accentOrange, modifier = Modifier.size(SizeTokens.iconSm))
                    Spacer(Modifier.width(SpacingTokens.sm))
                    BasicTextField(
                        value = tagInput,
                        onValueChange = { tagInput = it },
                        textStyle = MaterialTheme.typography.bodyMedium,
                        singleLine = true,
                        cursorBrush = SolidColor(MaterialTheme.customColors.accentOrange),
                        decorationBox = { innerTextField ->
                            Box {
                                if (tagInput.isEmpty()) {
                                    Text(stringResource(R.string.note_editor_tag_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
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
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_add), modifier = Modifier.size(SizeTokens.iconSm), tint = MaterialTheme.customColors.accentOrange)
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), modifier = Modifier.padding(vertical = SpacingTokens.sm))

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

            // 内容输入/预览（支持跨应用拖入文本/图片）
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .dropContentTarget(
                        onTextDropped = { insertDroppedText(it) },
                        onImageDropped = { scope.launch { insertDroppedImage(it) } },
                    ),
            ) {
                if (isPreviewMode) {
                // Markdown 预览模式
                MarkdownPreview(
                    content = content.text,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = SpacingTokens.lg, vertical = SpacingTokens.md),
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
                        .fillMaxSize()
                        .padding(horizontal = SpacingTokens.lg, vertical = SpacingTokens.md),
                    placeholder = stringResource(R.string.note_editor_format_hint),
                )
            } else {
                // Markdown 编辑模式
                BasicTextField(
                    value = content,
                    onValueChange = { content = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.customColors.accentBlue),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = SpacingTokens.lg, vertical = SpacingTokens.md)
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
                            val markdownPlaceholder = stringResource(R.string.note_editor_markdown_placeholder)
                            if (content.text.isEmpty()) {
                                Text(
                                    buildAnnotatedString {
                                        append(markdownPlaceholder)
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
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.30f),
                                    ),
                                    modifier = Modifier.offset(
                                        x = with(LocalDensity.current) {
                                            // 基于光标前文字的估算宽度偏移
                                            val paint = android.graphics.Paint().apply {
                                                textSize = MaterialTheme.typography.bodyLarge.fontSize.toPx()
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
                                    text = stringResource(R.string.note_editor_ghost_hint),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.customColors.accentBlue.copy(alpha = 0.5f),
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(end = SpacingTokens.xs, bottom = SpacingTokens.xs),
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
                                val completion = if (onRequestCompletion != null) {
                                    try { onRequestCompletion(contextText) } catch (_: Exception) { "" }
                                } else {
                                    EditorUtils.heuristicComplete(context, contextText)
                                }
                                ghostText = completion
                            }
                        }
                    }
                }
            }
            }

            Surface(
                modifier = Modifier.navigationBarsPadding(),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                tonalElevation = ElevationTokens.md,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = SpacingTokens.lg, vertical = SpacingTokens.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(noteType.icon(), contentDescription = stringResource(R.string.cd_note_type), tint = noteType.color(), modifier = Modifier.size(SizeTokens.iconSm))
                    Spacer(Modifier.width(SpacingTokens.xs))
                    Text(noteType.displayName(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    Spacer(Modifier.weight(1f))

                    Text(stringResource(R.string.note_editor_word_count, wordCount), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    if (lastSavedAt != null) {
                        Text(
                            stringResource(R.string.note_editor_saved_ago, EditorUtils.formatTimeAgo(context, lastSavedAt!!)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.customColors.successGreen,
                            modifier = Modifier.padding(start = SpacingTokens.sm),
                        )
                    }
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
                Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.xs)) {
                    AIActionButton(stringResource(R.string.ai_action_review), stringResource(R.string.ai_action_review_desc), "insight_review") {
                        showAiMenu = false
                        onSendWithSkill(noteId, "insight_review")
                    }
                    AIActionButton(stringResource(R.string.ai_action_inquiry), stringResource(R.string.ai_action_inquiry_desc), "critical_inquiry") {
                        showAiMenu = false
                        onSendWithSkill(noteId, "critical_inquiry")
                    }
                    AIActionButton(stringResource(R.string.ai_action_refine), stringResource(R.string.ai_action_refine_desc), "text_refine") {
                        showAiMenu = false
                        onSendWithSkill(noteId, "text_refine")
                    }
                    HorizontalDivider(color = themeDividerColor(), modifier = Modifier.padding(vertical = SpacingTokens.xs))
                    AIActionButton(stringResource(R.string.ai_action_editor_team), stringResource(R.string.ai_action_editor_team_desc), "editor_team") {
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
                    scope.launch { saveAndExit() }
                }) { Text(stringResource(R.string.note_editor_save_leave), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.customColors.accentBlue) }
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



