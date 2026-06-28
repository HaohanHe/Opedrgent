package top.hsyscn.opedrgent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.note.ArticleSection
import top.hsyscn.opedrgent.note.Note
import top.hsyscn.opedrgent.note.NoteRepository
import top.hsyscn.opedrgent.note.SproutArticle
import top.hsyscn.opedrgent.note.SproutService
import top.hsyscn.opedrgent.storage.SproutReportRecord
import top.hsyscn.opedrgent.storage.SproutReportStore
import top.hsyscn.opedrgent.ui.components.MarkdownText
import top.hsyscn.opedrgent.ui.components.LocalFeedbackController
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
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
    onBack: () -> Unit,
    onEditNote: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val feedback = LocalFeedbackController.current

    var article by remember { mutableStateOf(note.getSproutArticle()) }
    var isGenerating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var completedActions by remember { mutableStateOf<Set<Int>>(emptySet()) }
    // 历史发芽报告列表（从数据库加载）
    var historyReports by remember { mutableStateOf<List<SproutReportRecord>>(emptyList()) }

    // 加载历史发芽记录
    LaunchedEffect(note.id) {
        if (sproutReportStore != null) {
            historyReports = withContext(kotlinx.coroutines.Dispatchers.IO) {
                try { sproutReportStore.getByNoteId(note.id) } catch (_: Exception) { emptyList() }
            }
        }
    }

    // 首次进入自动触发发芽（如果没有历史记录且没有当前文章）
    LaunchedEffect(note.id) {
        if (article == null && !isGenerating && historyReports.isEmpty()) {
            isGenerating = true
            errorMessage = null
            try {
                val otherNotesContext = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try { repository.getRecentNotesIndex(5) } catch (e: Exception) { "" }
                }
                sproutService.sprout(note.content, otherNotesContext).fold(
                    onSuccess = { newArticle ->
                        article = newArticle
                        note.setSproutArticle(newArticle)
                        repository.saveNote(note)
                        // 持久化到发芽报告库
                        persistSprout(scope, sproutReportStore, note.id, note.title, newArticle)
                    },
                    onFailure = { e -> errorMessage = e.message ?: "生成失败" },
                )
            } finally { isGenerating = false }
        }
    }

    var showMenu by remember { mutableStateOf(false) }
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
                            Icon(Icons.Default.MoreVert, "更多操作")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("重新发芽") },
                                onClick = {
                                    showMenu = false
                                    doSprout(scope, sproutService, note, repository, sproutReportStore,
                                        onSuccess = { article = it },
                                        onFailure = { e -> errorMessage = e },
                                        onDone = {})
                                },
                                leadingIcon = { Icon(Icons.Default.Refresh, "重新发芽") },
                                enabled = !isGenerating,
                            )
                            DropdownMenuItem(
                                text = { Text("编辑笔记") },
                                onClick = { showMenu = false; onEditNote() },
                                leadingIcon = { Icon(Icons.Default.Edit, "编辑笔记") },
                            )
                            DropdownMenuItem(
                                text = { Text("复制报告") },
                                onClick = {
                                    showMenu = false
                                    val reportText = article?.toMarkdown() ?: ""
                                    if (reportText.isNotEmpty()) {
                                        clipboardManager.setText(AnnotatedString(reportText))
                                    }
                                },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, "复制报告") },
                                enabled = article != null,
                            )
                            DropdownMenuItem(
                                text = { Text("添加标签") },
                                onClick = {
                                    feedback.showFeedback(context.getString(R.string.msg_feature_under_development))
                                    showMenu = false
                                },
                                leadingIcon = { Icon(Icons.Default.Label, "添加标签") },
                            )
                            DropdownMenuItem(
                                text = { Text("复制全文") },
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val text = article?.toMarkdownText() ?: ""
                                    ClipData.newPlainText("发芽报告", text).let { clipboard.setPrimaryClip(it) }
                                    feedback.showFeedback(context.getString(R.string.msg_copied_to_clipboard))
                                    showMenu = false
                                },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, "复制全文") },
                            )
                            DropdownMenuItem(
                                text = { Text("导出 Markdown") },
                                onClick = {
                                    val text = article?.toMarkdownText() ?: ""
                                    val fileName = "发芽报告_${SimpleDateFormat("yyyy-MM-dd_HHmm").format(Date())}.md"
                                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                                    File(dir, fileName).writeText(text)
                                    feedback.showFeedback("已导出到下载目录")
                                    showMenu = false
                                },
                                leadingIcon = { Icon(Icons.Default.FileDownload, "导出 Markdown") },
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
            when {
                isGenerating && article == null -> SproutLoadingView()
                errorMessage != null && article == null -> SproutErrorView(message = errorMessage!!, onRetry = {
                    doSprout(scope, sproutService, note, repository, sproutReportStore,
                        onSuccess = { article = it },
                        onFailure = { e -> errorMessage = e },
                        onDone = { isGenerating = false })
                })
                article != null -> SproutArticleContent(
                    article = article!!,
                    completedActions = completedActions,
                    onActionToggle = { i -> completedActions = if (i in completedActions) completedActions - i else completedActions + i },
                    isRefreshing = isGenerating,
                    scrollState = scrollState,
                    note = note,
                    repository = repository,
                    sproutService = sproutService,
                    sproutReportStore = sproutReportStore,
                    onResprout = { seedContent ->
                        // 以当前发芽结果为种子，重新触发发芽
                        doSprout(scope, sproutService, note, repository, sproutReportStore,
                            onSuccess = { article = it },
                            onFailure = { e -> errorMessage = e },
                            onDone = {})
                    },
                )
                else -> SproutEmptyView(onGenerate = {
                    doSprout(scope, sproutService, note, repository, sproutReportStore,
                        onSuccess = { article = it },
                        onFailure = { e -> errorMessage = e },
                        onDone = { isGenerating = false })
                })
            }
        }
    }
}

// ==================== 文章内容区 ====================

@Composable
private fun SproutArticleContent(
    article: SproutArticle,
    completedActions: Set<Int>,
    onActionToggle: (Int) -> Unit,
    isRefreshing: Boolean,
    scrollState: androidx.compose.foundation.ScrollState,
    note: Note,
    repository: NoteRepository,
    sproutService: SproutService,
    sproutReportStore: SproutReportStore?,
    onResprout: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val feedback = LocalFeedbackController.current
    val dateFormat = SimpleDateFormat("MM月dd日", Locale.getDefault())

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
            // 追加笔记
            FilledTonalButton(
                onClick = {
                    val reportText = article.toPlainText()
                    scope.launch {
                        withContext(kotlinx.coroutines.Dispatchers.IO) {
                            repository.quickCreate(reportText)
                        }
                        feedback.showFeedback("已追加为新笔记")
                    }
                },
            ) {
                Icon(Icons.Default.NoteAdd, "追加笔记", Modifier.size(16.dp))
                Spacer(Modifier.width(SpacingTokens.xs))
                Text("追加笔记")
            }

            // 分享
            OutlinedButton(
                onClick = {
                    val shareText = article.toShareText()
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shareText)
                        putExtra(Intent.EXTRA_SUBJECT, "发芽报告")
                    }
                    context.startActivity(Intent.createChooser(intent, "分享"))
                },
            ) {
                Icon(Icons.Default.Share, "分享", Modifier.size(16.dp))
                Spacer(Modifier.width(SpacingTokens.xs))
                Text("分享")
            }

            // 基于此再发芽
            OutlinedButton(
                onClick = {
                    val seedContent = article.toPlainText()
                    onResprout(seedContent)
                },
            ) {
                Icon(Icons.Default.AutoAwesome, "再发芽", Modifier.size(16.dp))
                Spacer(Modifier.width(SpacingTokens.xs))
                Text("再发芽")
            }
        }

        Spacer(Modifier.height(48.dp))
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
            border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.onSurface),
            color = Color.Transparent,
        ) {
            Text(
                text = "  发芽报告  ",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = SpacingTokens.md, vertical = SpacingTokens.xs),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Description, "日期", tint = themeSproutMetaText(), modifier = Modifier.size(14.dp))
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
            color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Medium, lineHeight = 24.sp,
        ))
    }
}

// ==================== 单篇文章卡片 ====================

@Composable
private fun ArticleCard(section: ArticleSection, index: Int) {
    Card(
        shape = RectangleShape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(bottom = SpacingTokens.lg)) {
            Text(
                text = section.title.ifEmpty { "${String.format("%02d", index + 1)}. 未命名洞察" },
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
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

            HorizontalDivider(color = themeSproutDivider(), thickness = 1.dp)
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
            lineHeight = 20.sp,
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
    Text(text, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp, color = MaterialTheme.colorScheme.onSurface))
}

@Composable
private fun BoldText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold, lineHeight = 26.sp, color = MaterialTheme.colorScheme.onSurface))
}

@Composable
private fun QuoteBlock(text: String) {
    Box(Modifier.fillMaxWidth().background(themeSproutQuoteBg()).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 22.sp))
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
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Medium,
                    lineHeight = 24.sp,
                    color = MaterialTheme.customColors.accentOrange,
                    textDecoration = TextDecoration.Underline,
                ),
            )
        }
    }
}

// ==================== 行动建议 ====================

@Composable
private fun ActionSection(items: List<String>, completed: Set<Int>, onToggle: (Int) -> Unit) {
    val progress = if (items.isEmpty()) 0f else completed.size.toFloat() / items.size

    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("行动建议", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
            if (items.isNotEmpty()) Text("${completed.size}/${items.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.customColors.accentBlue)
        }
        if (items.isNotEmpty()) {
            LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().padding(vertical = SpacingTokens.sm), color = MaterialTheme.customColors.successGreen, trackColor = themeDividerColor())
        }
        items.forEachIndexed { idx, item ->
            val done = idx in completed
            Row(
                Modifier.fillMaxWidth().clickable { onToggle(idx) }.padding(vertical = SpacingTokens.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(if (done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = if (done) "已完成" else "未完成",
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
        Text("相关概念", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
        Spacer(Modifier.height(SpacingTokens.sm))
        // 简化：用 Column + Row 模拟流式布局，避免自定义 Layout 的复杂度
        var rowContent by remember { mutableStateOf(listOf<String>()) }
        var rows by remember { mutableStateOf(mutableListOf<List<String>>()) }

        concepts.forEach { concept ->
            val newRow = rowContent + concept
            rowContent = newRow
        }
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
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        val infiniteTransition = rememberInfiniteTransition(label = "sprout")
        val scale by infiniteTransition.animateFloat(initialValue = 1f, targetValue = 1.25f,
            animationSpec = infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "s")
        Box(Modifier.size(80.dp).scale(scale).background(MaterialTheme.customColors.successGreen.copy(alpha = 0.15f), shape = CircleShape), contentAlignment = Alignment.Center) {
            Text("*", style = MaterialTheme.typography.displayLarge)
        }
        Spacer(Modifier.height(SpacingTokens.xl))
        Text("正在发芽...", style = MaterialTheme.typography.titleMedium)
        Text("AI 正在深度分析你的笔记", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SproutErrorView(message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.ErrorOutline, "错误", Modifier.size(64.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(SpacingTokens.lg)); Text("发芽失败", style = MaterialTheme.typography.titleMedium)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = SpacingTokens.xxl))
        Spacer(Modifier.height(SpacingTokens.xl)); Button(onClick = onRetry) { Icon(Icons.Default.Refresh, "重试"); Spacer(Modifier.width(SpacingTokens.sm)); Text("重试") }
    }
}

@Composable
private fun SproutEmptyView(onGenerate: () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("*", style = MaterialTheme.typography.displayLarge); Spacer(Modifier.height(SpacingTokens.lg))
        Text("还没有发芽报告", style = MaterialTheme.typography.titleMedium)
        Text("让 AI 帮你深度分析这篇笔记", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(SpacingTokens.xl)); Button(onClick = onGenerate) { Icon(Icons.Default.AutoAwesome, "开始发芽"); Spacer(Modifier.width(SpacingTokens.sm)); Text("开始发芽") }
    }
}

// ==================== 发芽辅助函数 ====================

/**
 * 统一执行发芽操作：调用 API -> 保存笔记 -> 持久化报告 -> 回调
 */
private fun doSprout(
    scope: kotlinx.coroutines.CoroutineScope,
    service: SproutService,
    note: Note,
    repository: NoteRepository,
    store: SproutReportStore?,
    onSuccess: (SproutArticle) -> Unit,
    onFailure: (String) -> Unit,
    onDone: () -> Unit,
) {
    scope.launch {
        // Use a local flag since onDone might not set isGenerating
        try {
            service.sprout(note.content).fold(
                onSuccess = { article ->
                    note.setSproutArticle(article)
                    repository.saveNote(note)
                    persistSprout(scope, store, note.id, note.title, article)
                    onSuccess(article)
                },
                onFailure = { e -> onFailure(e.message ?: "生成失败") },
            )
        } finally { onDone() }
    }
}

/**
 * 将发芽结果持久化到 SproutReportStore（非阻塞）
 */
private fun persistSprout(
    scope: kotlinx.coroutines.CoroutineScope,
    store: SproutReportStore?,
    noteId: Long,
    noteTitle: String,
    article: SproutArticle,
) {
    if (store == null) return
    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            store.insert(SproutReportRecord(
                sourceNoteId = noteId,
                sourceTitle = noteTitle,
                markdownReport = article.toMarkdown(),
                summary = article.summary,
                modelUsed = article.modelUsed,
                createdAt = article.generatedAt,
                wordCount = article.markdownReport().length,
            ))
        }
    }
}

/** 将 SproutArticle 转为纯 Markdown 文本（用于存储） */
private fun SproutArticle.toMarkdown(): String = buildString {
    appendLine("# 发芽报告")
    appendLine()
    if (summary.isNotEmpty()) appendLine("> $summary").appendLine()
    articles.forEachIndexed { idx, section ->
        appendLine("## ${section.title}")
        if (section.seed.isNotEmpty()) appendLine("*${section.seed}*").appendLine()
        appendLine(section.body).appendLine()
        if (section.shockingMoment.isNotEmpty()) appendLine("**震惊瞬间:** ${section.shockingMoment}").appendLine()
    }
    if (actionItems.isNotEmpty()) {
        appendLine("## 行动建议")
        actionItems.forEach { appendLine("- [ ] $it") }
        appendLine()
    }
}

/** SproutArticle 的 markdown 报告（兼容旧字段名） */
private fun SproutArticle.markdownReport(): String = toMarkdown()

/** 将 SproutArticle 转为纯文本（用于追加笔记、再发芽等场景） */
private fun SproutArticle.toPlainText(): String = buildString {
    if (summary.isNotEmpty()) appendLine(summary).appendLine()
    articles.forEach { section ->
        if (section.title.isNotEmpty()) appendLine("## ${section.title}")
        appendLine(section.body)
        if (section.shockingMoment.isNotEmpty()) appendLine("震惊瞬间: ${section.shockingMoment}")
    }
    if (actionItems.isNotEmpty()) {
        appendLine("行动建议:")
        actionItems.forEach { appendLine("- $it") }
    }
}

/** 将 SproutArticle 转为分享文本（精简版，适合分享给他人） */
private fun SproutArticle.toShareText(): String = buildString {
    appendLine("【发芽报告】")
    if (summary.isNotEmpty()) appendLine(summary)
    articles.forEach { section ->
        appendLine("${section.title}: ${section.body.take(200)}")
    }
}

/** 将 SproutArticle 转为完整 Markdown 文本（用于复制/导出） */
private fun SproutArticle.toMarkdownText(): String = toMarkdown()
