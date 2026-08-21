package top.hsyscn.opedrgent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.note.ArticleSection
import top.hsyscn.opedrgent.note.Note
import top.hsyscn.opedrgent.note.NoteRepository
import top.hsyscn.opedrgent.note.SproutArticle
import top.hsyscn.opedrgent.note.SproutService
import top.hsyscn.opedrgent.utils.DebugLog
import top.hsyscn.opedrgent.storage.SproutReportRecord
import top.hsyscn.opedrgent.storage.SproutReportStore
import top.hsyscn.opedrgent.ui.components.MarkdownText
import top.hsyscn.opedrgent.ui.components.DownloadQuotes
import top.hsyscn.opedrgent.ui.components.LocalFeedbackController
import top.hsyscn.opedrgent.ui.theme.ElevationTokens
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import top.hsyscn.opedrgent.ui.theme.SizeTokens
import top.hsyscn.opedrgent.ui.theme.SpacingTokens
import top.hsyscn.opedrgent.ui.theme.customColors
import top.hsyscn.opedrgent.ui.theme.themeDividerColor
import top.hsyscn.opedrgent.ui.theme.themeSproutBackground
import top.hsyscn.opedrgent.ui.theme.themeSproutChipBg
import top.hsyscn.opedrgent.ui.theme.themeSproutDivider
import top.hsyscn.opedrgent.ui.theme.themeSproutMetaText
import top.hsyscn.opedrgent.ui.theme.themeSproutQuoteBg
import top.hsyscn.opedrgent.ui.theme.themeSproutSeedText
import top.hsyscn.opedrgent.ui.theme.themeSproutSummaryEnd
import top.hsyscn.opedrgent.ui.theme.themeSproutSummaryStart
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 笔记发芽报告页 — Opedrgent 核心特色（v2 叙事式）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteSproutScreen(
    note: Note,
    repository: NoteRepository,
    sproutService: SproutService,
    sproutReportStore: SproutReportStore? = null,
    sproutScope: kotlinx.coroutines.CoroutineScope? = null,
    onBack: () -> Unit,
    onEditNote: () -> Unit = {},
) {
    val pageScope = rememberCoroutineScope()
    // 优先使用 ViewModel scope（后台执行，退出不中断），否则用页面 scope
    val effectiveScope = sproutScope ?: pageScope
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val feedback = LocalFeedbackController.current

    var article by remember { mutableStateOf(note.getSproutArticle()) }
    var isGenerating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    // 已勾选行动建议：使用内容哈希而非索引，避免重新发芽后勾选错位
    var completedActionHashes by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }
    val completedActions = remember(completedActionHashes) { completedActionHashes }
    val toggleAction: (String) -> Unit = { actionHash ->
        completedActionHashes = if (actionHash in completedActionHashes) completedActionHashes - actionHash else completedActionHashes + actionHash
    }
    // 历史发芽报告列表（从数据库加载）
    var historyReports by remember { mutableStateOf<List<SproutReportRecord>>(emptyList()) }
    // 防止同一进入周期内重复自动触发（使用 rememberSaveable 防止配置变化后重复触发）
    var hasAutoTriggered by rememberSaveable { mutableStateOf(false) }
    // 防止发芽被重复触发
    val sproutMutex = remember { Mutex() }
    // 导出状态（提升到顶层，与下拉菜单同步）
    var isExporting by remember { mutableStateOf(false) }
    // 追加笔记状态
    var isAppending by remember { mutableStateOf(false) }
    val appendMutex = remember { Mutex() }

    // 加载历史发芽记录，并在无历史且无当前文章时自动触发一次发芽
    LaunchedEffect(note.id) {
        val history = if (sproutReportStore != null) {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                try { sproutReportStore.getByNoteId(note.id) } catch (_: Exception) { emptyList() }
            }
        } else emptyList()
        historyReports = history
        if (!hasAutoTriggered && article == null && history.isEmpty()) {
            hasAutoTriggered = true
            doSprout(
                context = context,
                scope = effectiveScope,
                mutex = sproutMutex,
                service = sproutService,
                repository = repository,
                note = note,
                sproutReportStore = sproutReportStore,
                setGenerating = { isGenerating = it },
                setError = { errorMessage = it },
                onSuccess = { article = it },
            )
        }
    }

    // 统一错误提示：无论是首次发芽还是重新发芽失败都给出反馈，避免静默失败
    LaunchedEffect(errorMessage) {
        val msg = errorMessage
        if (!msg.isNullOrBlank()) {
            feedback.showFeedback(msg)
            errorMessage = null
        }
    }

    var showMenu by remember { mutableStateOf(false) }
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_sprout_report)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    // 阅读模式：操作全部收进三点菜单
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, stringResource(R.string.sprout_geng_duo_cao_zuo))
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.note_editor_regenerate_sprout)) },
                                onClick = {
                                    showMenu = false
                                    doSprout(
                                        context = context,
                                        scope = effectiveScope,
                                        mutex = sproutMutex,
                                        service = sproutService,
                                        repository = repository,
                                        note = note,
                                        sproutReportStore = sproutReportStore,
                                        setGenerating = { isGenerating = it },
                                        setError = { errorMessage = it },
                                        onSuccess = { article = it },
                                    )
                                },
                                leadingIcon = { Icon(Icons.Default.Refresh, stringResource(R.string.note_editor_regenerate_sprout)) },
                                enabled = !isGenerating,
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.note_editor_edit)) },
                                onClick = { showMenu = false; onEditNote() },
                                leadingIcon = { Icon(Icons.Default.Edit, stringResource(R.string.note_editor_edit)) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sprout_copy_report)) },
                                onClick = {
                                    showMenu = false
                                    val reportText = article?.toMarkdown(context) ?: ""
                                    if (reportText.isNotEmpty()) {
                                        clipboardManager.setText(AnnotatedString(reportText))
                                    }
                                },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, stringResource(R.string.sprout_copy_report)) },
                                enabled = article != null,
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.note_action_add_tag)) },
                                onClick = {
                                    feedback.showFeedback(context.getString(R.string.msg_feature_under_development))
                                    showMenu = false
                                },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, stringResource(R.string.note_action_add_tag)) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sprout_action_copy_full)) },
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val text = article?.toMarkdownText(context) ?: ""
                                    ClipData.newPlainText(context.getString(R.string.sprout_report_title), text).let { clipboard.setPrimaryClip(it) }
                                    feedback.showFeedback(context.getString(R.string.msg_copied_to_clipboard))
                                    showMenu = false
                                },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, stringResource(R.string.sprout_action_copy_full)) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sprout_dao_chu_markdown)) },
                                onClick = {
                                    showMenu = false
                                    val text = article?.toMarkdownText(context) ?: ""
                                    if (text.isBlank()) {
                                        feedback.showFeedback(context.getString(R.string.sprout_mei_you_ke_dao_chu_de_nei_rong))
                                        return@DropdownMenuItem
                                    }
                                    // Android 10+ 直接写外部存储需要 MANAGE_EXTERNAL_STORAGE 或 Scoped Storage；
                                    // 这里先检查权限并捕获异常，避免崩溃。
                                    val canWriteLegacy = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                                        ContextCompat.checkSelfPermission(
                                            context,
                                            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                    if (!canWriteLegacy && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                        feedback.showFeedback(context.getString(R.string.sprout_android_11_qing_shou_yu_suo))
                                        return@DropdownMenuItem
                                    }
                                    isExporting = true
                                    effectiveScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                        try {
                                            val prefix = context.getString(R.string.note_sprout_bao_gao_wen_jian_qian_zhui)
                                            val fileName = "${prefix}_${SimpleDateFormat("yyyy-MM-dd_HHmm").format(Date())}.md"
                                            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                                            File(dir, fileName).writeText(text)
                                            feedback.showFeedback(context.getString(R.string.sprout_yi_dao_chu_dao_xia_zai_mu_lu_1, fileName))
                                        } catch (e: Exception) {
                                            feedback.showFeedback(context.getString(R.string.note_action_export_failed, e.message ?: ""))
                                        } finally {
                                            isExporting = false
                                        }
                                    }
                                },
                                enabled = !isExporting && article != null,
                                leadingIcon = { Icon(Icons.Default.FileDownload, stringResource(R.string.sprout_dao_chu_markdown)) },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(themeSproutBackground())) {
            val uiState = when {
                isGenerating && article == null -> SproutScreenState.Loading
                isGenerating && article != null -> SproutScreenState.Refreshing(article!!)
                errorMessage != null && article == null -> SproutScreenState.Error(errorMessage!!)
                errorMessage != null && article != null -> SproutScreenState.ErrorWithArticle(errorMessage!!, article!!)
                article != null -> SproutScreenState.Success(article!!)
                else -> SproutScreenState.Empty
            }
            AnimatedContent(
                targetState = uiState,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(220)) },
                modifier = Modifier.fillMaxSize(),
                label = "sprout_state",
            ) { state ->
                when (state) {
                    SproutScreenState.Loading -> SproutLoadingView()
                    is SproutScreenState.Error -> SproutErrorView(message = state.message, onRetry = {
                        doSprout(
                            context = context,
                            scope = effectiveScope,
                            mutex = sproutMutex,
                            service = sproutService,
                            repository = repository,
                            note = note,
                            sproutReportStore = sproutReportStore,
                            setGenerating = { isGenerating = it },
                            setError = { errorMessage = it },
                            onSuccess = { article = it },
                        )
                    })
                    is SproutScreenState.Success -> SproutArticleContent(
                        article = state.article,
                        completedActions = completedActions,
                        onActionToggle = toggleAction,
                        isRefreshing = false,
                        isExporting = isExporting,
                        onExportStateChange = { isExporting = it },
                        isAppending = isAppending,
                        onAppendStateChange = { isAppending = it },
                        appendMutex = appendMutex,
                        scrollState = scrollState,
                        note = note,
                        repository = repository,
                        sproutService = sproutService,
                        sproutReportStore = sproutReportStore,
                        onResprout = { seedContent ->
                            doSprout(
                                context = context,
                                scope = effectiveScope,
                                mutex = sproutMutex,
                                service = sproutService,
                                repository = repository,
                                note = note,
                                sproutReportStore = sproutReportStore,
                                seedContent = seedContent,
                                setGenerating = { isGenerating = it },
                                setError = { errorMessage = it },
                                onSuccess = { article = it },
                            )
                        },
                    )
                    is SproutScreenState.Refreshing -> SproutArticleContent(
                        article = state.article,
                        completedActions = completedActions,
                        onActionToggle = toggleAction,
                        isRefreshing = true,
                        isExporting = isExporting,
                        onExportStateChange = { isExporting = it },
                        isAppending = isAppending,
                        onAppendStateChange = { isAppending = it },
                        appendMutex = appendMutex,
                        scrollState = scrollState,
                        note = note,
                        repository = repository,
                        sproutService = sproutService,
                        sproutReportStore = sproutReportStore,
                        onResprout = {},
                    )
                    is SproutScreenState.ErrorWithArticle -> SproutArticleContent(
                        article = state.article,
                        completedActions = completedActions,
                        onActionToggle = toggleAction,
                        isRefreshing = false,
                        isExporting = isExporting,
                        onExportStateChange = { isExporting = it },
                        isAppending = isAppending,
                        onAppendStateChange = { isAppending = it },
                        appendMutex = appendMutex,
                        scrollState = scrollState,
                        note = note,
                        repository = repository,
                        sproutService = sproutService,
                        sproutReportStore = sproutReportStore,
                        errorMessage = state.message,
                        onResprout = { seedContent ->
                            doSprout(
                                context = context,
                                scope = effectiveScope,
                                mutex = sproutMutex,
                                service = sproutService,
                                repository = repository,
                                note = note,
                                sproutReportStore = sproutReportStore,
                                seedContent = seedContent,
                                setGenerating = { isGenerating = it },
                                setError = { errorMessage = it },
                                onSuccess = { article = it },
                            )
                        },
                    )
                    SproutScreenState.Empty -> SproutEmptyView(onGenerate = {
                        doSprout(
                            context = context,
                            scope = effectiveScope,
                            mutex = sproutMutex,
                            service = sproutService,
                            repository = repository,
                            note = note,
                            sproutReportStore = sproutReportStore,
                            setGenerating = { isGenerating = it },
                            setError = { errorMessage = it },
                            onSuccess = { article = it },
                        )
                    })
                }
            }
        }
    }
}

// ==================== 文章内容区 ====================

@Composable
private fun SproutArticleContent(
    article: SproutArticle,
    completedActions: Set<String>,
    onActionToggle: (String) -> Unit,
    isRefreshing: Boolean,
    isExporting: Boolean,
    onExportStateChange: (Boolean) -> Unit,
    isAppending: Boolean,
    onAppendStateChange: (Boolean) -> Unit,
    appendMutex: Mutex,
    scrollState: androidx.compose.foundation.ScrollState,
    note: Note,
    repository: NoteRepository,
    sproutService: SproutService,
    sproutReportStore: SproutReportStore?,
    errorMessage: String? = null,
    onResprout: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val feedback = LocalFeedbackController.current
    val dateFormat = SimpleDateFormat(context.getString(R.string.note_sprout_ri_qi_ge_shi), Locale.getDefault())

    LaunchedEffect(errorMessage) {
        if (!errorMessage.isNullOrBlank()) {
            feedback.showFeedback(errorMessage)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = SpacingTokens.xl),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.xl),
    ) {
        Spacer(Modifier.height(SpacingTokens.sm))
        ReportHeader(dateFormat.format(Date(article.generatedAt)), article.modelUsed)

        if (article.summary.isNotEmpty()) {
            SummaryBlock(article.summary)
        }

        article.articles.forEachIndexed { index, section ->
            ArticleCard(section = section, index = index)
        }

        if (article.actionItems.isNotEmpty()) {
            ActionSection(items = article.actionItems, completed = completedActions, onToggle = onActionToggle)
        }

        if (article.relatedConcepts.isNotEmpty()) {
            ConceptsSection(concepts = article.relatedConcepts)
        }

        AnimatedVisibility(visible = isRefreshing) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = MaterialTheme.customColors.successGreen)
        }

        // 底部操作栏：仅在发芽报告存在时显示
        HorizontalDivider(modifier = Modifier.padding(vertical = SpacingTokens.sm))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.md, Alignment.End),
        ) {
            // 追加笔记（使用 mutex 防止并发）
            FilledTonalButton(
                onClick = {
                    scope.launch {
                        if (!appendMutex.tryLock()) {
                            feedback.showFeedback(context.getString(R.string.sprout_cao_zuo_zheng_zai_jin_xing))
                            return@launch
                        }
                        val reportText = article.toPlainText(context)
                        onAppendStateChange(true)
                        try {
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                repository.quickCreate(reportText)
                            }
                            feedback.showFeedback(context.getString(R.string.sprout_yi_zhui_jia_wei_xin_bi_ji))
                        } catch (e: Exception) {
                            feedback.showFeedback(context.getString(R.string.sprout_zhui_jia_shi_bai_1, e.message ?: ""))
                        } finally {
                            onAppendStateChange(false)
                            appendMutex.unlock()
                        }
                    }
                },
                enabled = !isAppending,
            ) {
                Icon(Icons.AutoMirrored.Filled.NoteAdd, stringResource(R.string.note_action_append), Modifier.size(SizeTokens.iconSm))
                Spacer(Modifier.width(SpacingTokens.xs))
                Text(stringResource(R.string.note_action_append))
            }

            // 分享
            OutlinedButton(
                onClick = {
                    val shareText = article.toShareText(context)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shareText)
                        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.sprout_report_title))
                    }
                    context.startActivity(Intent.createChooser(intent, context.getString(R.string.action_share)))
                },
            ) {
                Icon(Icons.Default.Share, stringResource(R.string.action_share), Modifier.size(SizeTokens.iconSm))
                Spacer(Modifier.width(SpacingTokens.xs))
                Text(stringResource(R.string.action_share))
            }

            // 基于此再发芽
            OutlinedButton(
                onClick = {
                    val seedContent = article.toPlainText(context)
                    onResprout(seedContent)
                },
                enabled = !isRefreshing,
            ) {
                Icon(Icons.Default.AutoAwesome, stringResource(R.string.sprout_zai_fa_ya), Modifier.size(SizeTokens.iconSm))
                Spacer(Modifier.width(SpacingTokens.xs))
                Text(stringResource(R.string.sprout_zai_fa_ya))
            }
        }

        Spacer(Modifier.height(SizeTokens.sectionIcon))
    }
}

// ==================== 报告头部 ====================

@Composable
private fun ReportHeader(dateStr: String, modelName: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = ShapeTokens.extraSmallShape,
            border = androidx.compose.foundation.BorderStroke(SizeTokens.borderWidth, MaterialTheme.colorScheme.onSurface),
            color = Color.Transparent,
        ) {
            Text(
                text = stringResource(R.string.sprout_fa_ya_bao_gao),
                style = MaterialTheme.typography.headlineLarge.copy(fontFamily = FontFamily.Serif),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = SpacingTokens.md, vertical = SpacingTokens.xs),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Description, stringResource(R.string.sprout_ri_qi), tint = themeSproutMetaText(), modifier = Modifier.size(SizeTokens.iconXs))
            Text(dateStr, style = MaterialTheme.typography.labelSmall, color = themeSproutMetaText())
        }
    }
}

// ==================== 摘要块 ====================

@Composable
private fun SummaryBlock(summary: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.linearGradient(listOf(themeSproutSummaryStart(), themeSproutSummaryEnd())))
            .padding(SpacingTokens.lg + SpacingTokens.xs),
    ) {
        Text(summary, style = MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onPrimary,
        ))
    }
}

// ==================== 单篇文章卡片 ====================

@Composable
private fun ArticleCard(section: ArticleSection, index: Int) {
    Card(
        shape = RectangleShape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = ElevationTokens.none),
    ) {
        Column(Modifier.padding(bottom = SpacingTokens.lg)) {
            Text(
                text = section.title.ifEmpty { stringResource(R.string.sprout_index_1_wei_ming_ming_dong_cha, index + 1) },
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontFamily = FontFamily.Serif,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(Modifier.height(SpacingTokens.lg))

            if (section.seed.isNotEmpty()) {
                SeedBlock(seed = section.seed)
                Spacer(Modifier.height(SpacingTokens.lg))
            }

            MarkdownText(text = section.body, maxChars = 2000)

            Spacer(Modifier.height(SpacingTokens.lg))

            if (section.shockingMoment.isNotEmpty()) {
                ShockingBlock(moment = section.shockingMoment, importance = section.importance)
            }

            HorizontalDivider(color = themeSproutDivider(), thickness = SizeTokens.dividerThickness)
        }
    }
}

// ==================== 种子块 ====================

@Composable
private fun SeedBlock(seed: String) {
    Row(Modifier.padding(start = SpacingTokens.xs)) {
        Text(text = "  ", style = MaterialTheme.typography.bodyMedium)
        Text(
            text = seed,
            style = MaterialTheme.typography.bodyMedium,
            color = themeSproutSeedText(),
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
            modifier = Modifier.weight(1f),
        )
    }
}

// ==================== 正文渲染（轻量 Markdown）====================

@Composable
private fun ArticleBody(body: String) {
    val lines = body.lines().filter { it.isNotBlank() }
    Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
        lines.forEach { line ->
            when {
                line.startsWith("> ") -> QuoteBlock(line.removePrefix("> "))
                line.startsWith("**") && line.endsWith("**") ->
                    BoldText(line.removePrefix("**").removeSuffix("**"))
                else -> PlainText(line)
            }
        }
    }
}

@Composable
private fun PlainText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface))
}

@Composable
private fun BoldText(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurface))
}

@Composable
private fun QuoteBlock(text: String) {
    Box(Modifier.fillMaxWidth().background(themeSproutQuoteBg()).padding(horizontal = SizeTokens.contentPaddingMd, vertical = SizeTokens.sectionGapSm)) {
        Text(text, style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
    }
}

// ==================== 震惊瞬间块 ====================

@Composable
private fun ShockingBlock(moment: String, importance: Int) {
    Row(Modifier.padding(start = SpacingTokens.xs)) {
        Text(text = "  ", style = MaterialTheme.typography.bodyLarge)
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.xxs)) {
                repeat(importance) { Text("●", color = MaterialTheme.customColors.accentOrange, style = MaterialTheme.typography.labelSmall) }
            }
            Spacer(Modifier.height(SpacingTokens.xs))
            val displayText = "\"$moment\""
            Text(
                text = displayText,
                style = MaterialTheme.typography.titleMedium.copy(
                    color = MaterialTheme.customColors.accentOrange,
                    textDecoration = TextDecoration.Underline,
                ),
            )
        }
    }
}

// ==================== 行动建议 ====================

@Composable
private fun ActionSection(items: List<String>, completed: Set<String>, onToggle: (String) -> Unit) {
    val completedCount = items.count { it.hashCode().toString() in completed }
    val progress = if (items.isEmpty()) 0f else completedCount.toFloat() / items.size

    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.note_editor_action_suggestion), style = MaterialTheme.typography.titleMedium)
            if (items.isNotEmpty()) Text("${completedCount}/${items.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.customColors.accentBlue)
        }
        if (items.isNotEmpty()) {
            LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().padding(vertical = SpacingTokens.sm), color = MaterialTheme.customColors.successGreen, trackColor = themeDividerColor())
        }
        items.forEach { item ->
            val itemHash = item.hashCode().toString()
            val done = itemHash in completed
            Row(
                Modifier.fillMaxWidth().clickable { onToggle(itemHash) }.padding(vertical = SpacingTokens.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SizeTokens.sectionGapSm),
            ) {
                Icon(if (done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = if (done) stringResource(R.string.state_completed) else stringResource(R.string.sprout_wei_wan_cheng),
                    tint = if (done) MaterialTheme.customColors.successGreen else MaterialTheme.colorScheme.outline)
                Text(item, style = MaterialTheme.typography.bodyMedium,
                    color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                    modifier = Modifier.weight(1f))
            }
        }
    }
}

// ==================== 相关概念（简化版 FlowRow）====================

@Composable
private fun ConceptsSection(concepts: List<String>) {
    Column {
        Text(stringResource(R.string.note_editor_related_concepts), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(SpacingTokens.sm))
        // 直接用简单的 Wrap 样式展示
        Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm), modifier = Modifier.wrapContentHeight()) {
            concepts.forEach {
                SuggestionChip(onClick = {}, label = { Text(it) },
                    colors = SuggestionChipDefaults.suggestionChipColors(containerColor = themeSproutChipBg()))
            }
        }
    }
}

// ==================== 状态视图 ====================

@Composable
private fun SproutLoadingView() {
    val context = LocalContext.current
    val infiniteTransition = rememberInfiniteTransition(label = "sprout_loading")
    val sproutProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sprout_progress",
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse_alpha",
    )

    val successColor = MaterialTheme.customColors.successGreen
    val seedColor = MaterialTheme.customColors.sproutSeedText

    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(SizeTokens.sproutAnimationCanvasSize)
                .drawBehind {
                    val centerX = size.width / 2f
                    val groundY = size.height * 0.72f

                    drawCircle(
                        color = successColor.copy(alpha = pulseAlpha * 0.15f),
                        radius = size.width * 0.45f,
                        center = Offset(centerX, groundY),
                    )

                    val seedY = groundY - SizeTokens.sproutSeedYOffset.toPx()
                    drawOval(
                        color = seedColor.copy(alpha = 0.6f),
                        topLeft = Offset(centerX - SizeTokens.sproutSeedWidth.toPx() / 2, seedY - SizeTokens.sproutSeedHeight.toPx() / 2),
                        size = androidx.compose.ui.geometry.Size(SizeTokens.sproutSeedWidth.toPx(), SizeTokens.sproutSeedHeight.toPx()),
                    )

                    val stemHeight = size.height * 0.45f * sproutProgress
                    if (stemHeight > 0f) {
                        val stemPath = Path().apply {
                            moveTo(centerX, groundY)
                            val swayX = kotlin.math.sin(sproutProgress * Math.PI * 2).toFloat() * SizeTokens.sproutSwayAmplitude.toPx()
                            quadraticTo(
                                centerX + swayX,
                                groundY - stemHeight * 0.5f,
                                centerX + swayX * 0.5f,
                                groundY - stemHeight,
                            )
                        }
                        drawPath(
                            path = stemPath,
                            color = successColor,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = SizeTokens.sproutStemWidth.toPx(),
                                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                            ),
                        )

                        if (sproutProgress > 0.35f) {
                            val leafProgress = (sproutProgress - 0.35f) / 0.65f
                            val leafSize = SizeTokens.sproutLeafSize.toPx() * leafProgress.coerceIn(0f, 1f)
                            val leafY = groundY - stemHeight * 0.7f
                            val swayX = kotlin.math.sin(sproutProgress * Math.PI * 2).toFloat() * SizeTokens.sproutSwayAmplitude.toPx()
                            drawOval(
                                color = successColor.copy(alpha = 0.85f),
                                topLeft = Offset(centerX + swayX * 0.5f - leafSize * 0.7f, leafY - leafSize * 0.3f),
                                size = androidx.compose.ui.geometry.Size(leafSize, leafSize * 0.6f),
                                alpha = 1f,
                            )
                            drawOval(
                                color = successColor.copy(alpha = 0.85f),
                                topLeft = Offset(centerX + swayX * 0.5f + leafSize * 0.1f, leafY - leafSize * 0.1f),
                                size = androidx.compose.ui.geometry.Size(leafSize, leafSize * 0.6f),
                                alpha = 1f,
                            )
                        }
                    }
                },
        )

        Spacer(Modifier.height(SpacingTokens.xl))
        Text(
            stringResource(R.string.sprout_loading_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(SpacingTokens.sm))
        Text(
            stringResource(R.string.sprout_loading_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(SpacingTokens.xl))

        // 名言轮播 — 跟下载弹窗一样的逻辑
        var shuffledIndices by remember { mutableStateOf(DownloadQuotes.getQuotes(context).indices.shuffled()) }
        var quotePointer by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(10_000L)
                quotePointer++
                if (quotePointer >= shuffledIndices.size) {
                    shuffledIndices = DownloadQuotes.getQuotes(context).indices.shuffled()
                    quotePointer = 0
                }
            }
        }
        val currentQuote = DownloadQuotes.getQuotes(context)[shuffledIndices[quotePointer]]
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = SpacingTokens.xl),
            shape = ShapeTokens.mediumShape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Crossfade(
                targetState = currentQuote,
                animationSpec = tween(durationMillis = 500),
                label = "sprout_quote_crossfade",
            ) { q ->
                Column(
                    modifier = Modifier.fillMaxWidth().padding(SpacingTokens.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = q.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    if (q.author.isNotBlank()) {
                        Spacer(Modifier.height(SpacingTokens.sm))
                        Text(
                            text = "-- ${q.author}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SproutErrorView(message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.ErrorOutline, stringResource(R.string.msg_body_cd_error), Modifier.size(SizeTokens.emptyStateIcon), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(SpacingTokens.lg)); Text(stringResource(R.string.sprout_error_title), style = MaterialTheme.typography.titleMedium)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = SpacingTokens.xxl))
        Spacer(Modifier.height(SpacingTokens.xl)); Button(onClick = onRetry) { Icon(Icons.Default.Refresh, stringResource(R.string.action_retry)); Spacer(Modifier.width(SpacingTokens.sm)); Text(stringResource(R.string.action_retry)) }
    }
}

@Composable
private fun SproutEmptyView(onGenerate: () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("*", style = MaterialTheme.typography.displayLarge); Spacer(Modifier.height(SpacingTokens.lg))
        Text(stringResource(R.string.sprout_empty_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.sprout_empty_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(SpacingTokens.xl)); Button(onClick = onGenerate) { Icon(Icons.Default.AutoAwesome, stringResource(R.string.sprout_start)); Spacer(Modifier.width(SpacingTokens.sm)); Text(stringResource(R.string.sprout_start)) }
    }
}

// ==================== UI 状态 ====================

/** 发芽页面状态机，用于 AnimatedContent 做状态间淡入淡出过渡 */
private sealed class SproutScreenState {
    data object Loading : SproutScreenState()
    data object Empty : SproutScreenState()
    data class Error(val message: String) : SproutScreenState()
    data class Success(val article: SproutArticle) : SproutScreenState()
    data class Refreshing(val article: SproutArticle) : SproutScreenState()
    data class ErrorWithArticle(val message: String, val article: SproutArticle) : SproutScreenState()
}

// ==================== 发芽辅助函数 ====================

/**
 * 统一执行发芽操作：调用 API -> 保存笔记 -> 持久化报告 -> 回调。
 * 使用 [mutex] 保证同一页面内不会并发执行多次发芽，避免重复请求与状态错乱。
 */
private fun doSprout(
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    mutex: Mutex,
    service: SproutService,
    note: Note,
    repository: NoteRepository,
    sproutReportStore: SproutReportStore?,
    seedContent: String? = null,
    setGenerating: (Boolean) -> Unit,
    setError: (String?) -> Unit,
    onSuccess: (SproutArticle) -> Unit,
) {
    scope.launch {
        if (!mutex.tryLock()) {
            setError(context.getString(R.string.note_sprout_fa_ya_zheng_zai_jin_xing))
            return@launch
        }
        setGenerating(true)
        setError(null)
        try {
            val otherNotesContext = withContext(kotlinx.coroutines.Dispatchers.IO) {
                try { repository.getRecentNotesIndex(5) } catch (_: Exception) { "" }
            }
            runCatching {
                service.sprout(seedContent ?: note.content, otherNotesContext)
            }.onSuccess { result ->
                result.fold(
                    onSuccess = { article ->
                        withContext(kotlinx.coroutines.Dispatchers.IO) {
                            note.setSproutArticle(article)
                            repository.saveNote(note)
                            persistSprout(context, sproutReportStore, note.id, note.title, article)
                        }
                        onSuccess(article)
                    },
                    onFailure = { e -> setError(e.message ?: context.getString(R.string.note_sprout_sheng_cheng_shi_bai)) },
                )
            }.onFailure { e ->
                setError(e.message ?: context.getString(R.string.note_sprout_fa_ya_shi_bai))
            }
        } finally {
            setGenerating(false)
            mutex.unlock()
        }
    }
}

/**
 * 将发芽结果持久化到 SproutReportStore（同步执行，在 mutex 保护内调用）
 */
private suspend fun persistSprout(
    context: Context,
    store: SproutReportStore?,
    noteId: Long,
    noteTitle: String,
    article: SproutArticle,
) {
    if (store == null) return
    runCatching {
        store.insert(SproutReportRecord(
            sourceNoteId = noteId,
            sourceTitle = noteTitle,
            markdownReport = article.toMarkdown(context),
            summary = article.summary,
            modelUsed = article.modelUsed,
            createdAt = article.generatedAt,
            wordCount = article.markdownReport(context).length,
        ))
    }.onFailure { e ->
        DebugLog.w("NoteSproutScreen", "persistSprout failed: ${e.message}")
    }
}

/** 将 SproutArticle 转为纯 Markdown 文本（用于存储） */
private fun SproutArticle.toMarkdown(context: Context): String = buildString {
    val reportTitle = context.getString(R.string.sprout_report_title)
    appendLine(context.getString(R.string.note_sprout_markdown_title, reportTitle))
    appendLine()
    if (summary.isNotEmpty()) appendLine("> $summary").appendLine()
    articles.forEachIndexed { idx, section ->
        appendLine("## ${section.title}")
        if (section.seed.isNotEmpty()) appendLine("*${section.seed}*").appendLine()
        appendLine(section.body).appendLine()
        if (section.shockingMoment.isNotEmpty()) {
            appendLine(context.getString(R.string.note_sprout_section_shocking_moment, section.shockingMoment)).appendLine()
        }
    }
    if (actionItems.isNotEmpty()) {
        appendLine(context.getString(R.string.note_sprout_action_items_title))
        actionItems.forEach { appendLine("- [ ] $it") }
        appendLine()
    }
}

/** SproutArticle 的 markdown 报告（兼容旧字段名） */
private fun SproutArticle.markdownReport(context: Context): String = toMarkdown(context)

/** 将 SproutArticle 转为纯文本（用于追加笔记、再发芽等场景） */
private fun SproutArticle.toPlainText(context: Context): String = buildString {
    if (summary.isNotEmpty()) appendLine(summary).appendLine()
    articles.forEach { section ->
        if (section.title.isNotEmpty()) appendLine("## ${section.title}")
        appendLine(section.body)
        if (section.shockingMoment.isNotEmpty()) {
            appendLine(context.getString(R.string.note_sprout_plain_shocking_moment, section.shockingMoment))
        }
    }
    if (actionItems.isNotEmpty()) {
        appendLine(context.getString(R.string.note_sprout_plain_action_items_title))
        actionItems.forEach { appendLine("- $it") }
    }
}

/** 将 SproutArticle 转为分享文本（精简版，适合分享给他人） */
private fun SproutArticle.toShareText(context: Context): String = buildString {
    val reportTitle = context.getString(R.string.sprout_report_title)
    appendLine(context.getString(R.string.note_sprout_share_title, reportTitle))
    if (summary.isNotEmpty()) appendLine(summary)
    articles.forEach { section ->
        appendLine("${section.title}: ${section.body.take(200)}")
    }
}

/** 将 SproutArticle 转为完整 Markdown 文本（用于复制/导出） */
private fun SproutArticle.toMarkdownText(context: Context): String = toMarkdown(context)

