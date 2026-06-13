package top.hsyscn.opedrgent.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.note.ArticleSection
import top.hsyscn.opedrgent.note.Note
import top.hsyscn.opedrgent.note.NoteRepository
import top.hsyscn.opedrgent.note.SproutArticle
import top.hsyscn.opedrgent.note.SproutService
import top.hsyscn.opedrgent.ui.components.MarkdownText
import top.hsyscn.opedrgent.ui.theme.AccentBlue
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
    onBack: () -> Unit,
    onEditNote: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var article by remember { mutableStateOf(note.getSproutArticle()) }
    var isGenerating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var completedActions by remember { mutableStateOf<Set<Int>>(emptySet()) }

    // 首次进入自动触发发芽
    LaunchedEffect(note.id) {
        if (article == null && !isGenerating) {
            isGenerating = true
            errorMessage = null
            try {
                // 获取其他笔记作为上下文
                val otherNotesContext = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try { repository.getRecentNotesContext(5) } catch (e: Exception) { "" }
                }
                sproutService.sprout(note.content, otherNotesContext).fold(
                    onSuccess = { newArticle ->
                        article = newArticle
                        note.setSproutArticle(newArticle)
                        repository.saveNote(note)
                    },
                    onFailure = { e -> errorMessage = e.message ?: "生成失败" },
                )
            } finally { isGenerating = false }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("发芽报告") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            isGenerating = true; errorMessage = null
                            try {
                                sproutService.sprout(note.content).fold(
                                    onSuccess = { article = it; note.setSproutArticle(it); repository.saveNote(note) },
                                    onFailure = { e -> errorMessage = e.message ?: "生成失败" },
                                )
                            } finally { isGenerating = false }
                        }
                    }, enabled = !isGenerating) {
                        Icon(Icons.Default.Refresh, "重新发芽",
                            modifier = if (isGenerating) Modifier.scale(0.8f) else Modifier)
                    }
                    IconButton(onClick = onEditNote) { Icon(Icons.Default.Edit, "编辑笔记") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(Color(0xFFFAFAF5))) {
            when {
                isGenerating && article == null -> SproutLoadingView()
                errorMessage != null && article == null -> SproutErrorView(message = errorMessage!!, onRetry = {
                    scope.launch {
                        isGenerating = true; errorMessage = null
                        try {
                            sproutService.sprout(note.content).fold(
                                onSuccess = { article = it; note.setSproutArticle(it); repository.saveNote(note) },
                                onFailure = { e -> errorMessage = e.message ?: "生成失败" },
                            )
                        } finally { isGenerating = false }
                    }
                })
                article != null -> SproutArticleContent(
                    article = article!!,
                    completedActions = completedActions,
                    onActionToggle = { i -> completedActions = if (i in completedActions) completedActions - i else completedActions + i },
                    isRefreshing = isGenerating,
                    scrollState = scrollState,
                )
                else -> SproutEmptyView(onGenerate = {
                    scope.launch {
                        isGenerating = true; errorMessage = null
                        try {
                            sproutService.sprout(note.content).fold(
                                onSuccess = { article = it; note.setSproutArticle(it); repository.saveNote(note) },
                                onFailure = { e -> errorMessage = e.message ?: "生成失败" },
                            )
                        } finally { isGenerating = false }
                    }
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
) {
    val dateFormat = SimpleDateFormat("MM月dd日", Locale.getDefault())

    Column(
        Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Spacer(Modifier.height(8.dp))
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
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = Color(0xFF4CAF50))
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
            shape = RoundedCornerShape(4.dp),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF333333)),
            color = Color.Transparent,
        ) {
            Text(
                text = "  发芽报告  ",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp,
                ),
                color = Color(0xFF222222),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Description, null, tint = Color(0xFF888888), modifier = Modifier.size(14.dp))
            Text(dateStr, style = MaterialTheme.typography.labelSmall, color = Color(0xFF888888))
        }
    }
}

// ==================== 摘要块 ====================

@Composable
private fun SummaryBlock(summary: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.linearGradient(listOf(Color(0xFF667eea), Color(0xFF764ba2))))
            .padding(18.dp),
    ) {
        Text(summary, style = MaterialTheme.typography.bodyLarge.copy(
            color = Color.White, fontWeight = FontWeight.Medium, lineHeight = 24.sp,
        ))
    }
}

// ==================== 单篇文章卡片 ====================

@Composable
private fun ArticleCard(section: ArticleSection, index: Int) {
    Card(
        shape = RoundedCornerShape(0.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(bottom = 16.dp)) {
            Text(
                text = section.title.ifEmpty { "${String.format("%02d", index + 1)}. 未命名洞察" },
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                ),
                color = Color(0xFF1a1a1a),
            )

            Spacer(Modifier.height(16.dp))

            if (section.seed.isNotEmpty()) {
                SeedBlock(seed = section.seed)
                Spacer(Modifier.height(16.dp))
            }

            MarkdownText(text = section.body, maxChars = 2000)

            Spacer(Modifier.height(16.dp))

            if (section.ahaMoment.isNotEmpty()) {
                AhaBlock(moment = section.ahaMoment, importance = section.importance)
            }

            HorizontalDivider(color = Color(0xFFE0DCC), thickness = 1.dp)
        }
    }
}

// ==================== 种子块 ====================

@Composable
private fun SeedBlock(seed: String) {
    Row(Modifier.padding(start = 4.dp)) {
        Text(text = "  ", fontSize = 14.sp)
        Text(
            text = seed,
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF777777),
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
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
    Text(text, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp, color = Color(0xFF333333)))
}

@Composable
private fun BoldText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold, lineHeight = 26.sp, color = Color(0xFF1a1a1a)))
}

@Composable
private fun QuoteBlock(text: String) {
    Box(Modifier.fillMaxWidth().background(Color(0xFFF5F0E8)).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium.copy(color = Color(0xFF555555), lineHeight = 22.sp))
    }
}

// ==================== Aha 瞬间块 ====================

@Composable
private fun AhaBlock(moment: String, importance: Int) {
    Row(Modifier.padding(start = 4.dp)) {
        Text(text = "  ", fontSize = 15.sp)
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(importance) { Text("●", color = Color(0xFFFFA000), fontSize = 10.sp) }
            }
            Spacer(Modifier.height(4.dp))
            val displayText = "\"$moment\""
            Text(
                text = displayText,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Medium,
                    lineHeight = 24.sp,
                    color = Color(0xFFE65100),
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
            Text("行动建议", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (items.isNotEmpty()) Text("${completed.size}/${items.size}", style = MaterialTheme.typography.labelMedium, color = AccentBlue)
        }
        if (items.isNotEmpty()) {
            LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().padding(vertical = 8.dp), color = Color(0xFF4CAF50), trackColor = Color(0xFFE8E8E8))
        }
        items.forEachIndexed { idx, item ->
            val done = idx in completed
            Row(
                Modifier.fillMaxWidth().clickable { onToggle(idx) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(if (done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null,
                    tint = if (done) Color(0xFF4CAF50) else Color(0xFFBBBBBB))
                Text(item, style = MaterialTheme.typography.bodyMedium,
                    color = if (done) Color(0xFF999999) else Color(0xFF333333),
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
        Text("相关概念", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        // 简化：用 Column + Row 模拟流式布局，避免自定义 Layout 的复杂度
        var rowContent by remember { mutableStateOf(listOf<String>()) }
        var rows by remember { mutableStateOf(mutableListOf<List<String>>()) }

        concepts.forEach { concept ->
            val newRow = rowContent + concept
            rowContent = newRow
        }
        // 直接用简单的 Wrap 样式展示
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.wrapContentHeight()) {
            concepts.forEach {
                SuggestionChip(onClick = {}, label = { Text(it) },
                    colors = SuggestionChipDefaults.suggestionChipColors(containerColor = Color(0xFFF0EAD6)))
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
        Box(Modifier.size(80.dp).scale(scale).background(Color(0xFF4CAF50).copy(alpha = 0.15f), shape = CircleShape), contentAlignment = Alignment.Center) {
            Text("*", fontSize = 40.sp)
        }
        Spacer(Modifier.height(24.dp))
        Text("正在发芽...", style = MaterialTheme.typography.titleMedium)
        Text("AI 正在深度分析你的笔记", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SproutErrorView(message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.ErrorOutline, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp)); Text("发芽失败", style = MaterialTheme.typography.titleMedium)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
        Spacer(Modifier.height(24.dp)); Button(onClick = onRetry) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text("重试") }
    }
}

@Composable
private fun SproutEmptyView(onGenerate: () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("*", fontSize = 64.sp); Spacer(Modifier.height(16.dp))
        Text("还没有发芽报告", style = MaterialTheme.typography.titleMedium)
        Text("让 AI 帮你深度分析这篇笔记", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp)); Button(onClick = onGenerate) { Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text("开始发芽") }
    }
}
