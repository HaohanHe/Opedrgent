@file:OptIn(ExperimentalMaterial3Api::class)

package top.hsyscn.opedrgent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.mcp.editors.EditorTeamService
import top.hsyscn.opedrgent.mcp.editors.ExecutionPlan
import top.hsyscn.opedrgent.mcp.editors.RoleInstance
import top.hsyscn.opedrgent.network.SearchConfig
import top.hsyscn.opedrgent.network.SearchResult
import top.hsyscn.opedrgent.network.WebSearcher
import top.hsyscn.opedrgent.note.NoteType
import top.hsyscn.opedrgent.utils.DebugLog
import top.hsyscn.opedrgent.ui.theme.AccentBlue
import top.hsyscn.opedrgent.ui.theme.BgGray
import top.hsyscn.opedrgent.ui.theme.CardWhite
import top.hsyscn.opedrgent.ui.theme.TextDark
import top.hsyscn.opedrgent.ui.theme.TextGrey
import top.hsyscn.opedrgent.ui.components.MarkdownText

// ==================== 数据模型 ====================

/** 对话消息 */
data class TeamMessage(
    val id: String,
    val roleAlias: String,
    val roleIcon: String,
    val roleColor: Long,
    val content: String,
    val timestamp: Long,
    val isUser: Boolean,
    val isStreaming: Boolean = false,
    val isDocument: Boolean = false,
    val documentWordCount: Int = 0,
    val documentTitle: String = "",
    val isError: Boolean = false,
)

/** 搜索结果（右侧面板） */
data class TeamSearchResult(
    val query: String,
    val source: String,
    val results: List<SearchItem>,
)

data class SearchItem(
    val title: String,
    val url: String,
    val snippet: String,
    val sourceName: String,
)

// ==================== 主界面 ====================

@Composable
fun EditorTeamScreen(
    vm: MainViewModel,
    initialInput: String = "",
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val service = remember { EditorTeamService(vm.apiSettings) }
    val webSearcher = remember { WebSearcher() }
    val listState = rememberLazyListState()

    // 对话状态
    var messages by rememberSaveable { mutableStateOf<List<TeamMessage>>(emptyList()) }
    var inputText by remember { mutableStateOf(initialInput) }
    var isProcessing by remember { mutableStateOf(false) }

    // 右侧搜索面板状态
    var showSearchPanel by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<TeamSearchResult?>(null) }
    var isSearching by remember { mutableStateOf(false) }

    // 流水线内部状态（用于 planAndExecute 回调）
    var currentPlan by remember { mutableStateOf<ExecutionPlan?>(null) }
    var currentStepIndex by remember { mutableIntStateOf(-1) }
    var finalOutput by remember { mutableStateOf("") }
    var totalDuration by remember { mutableLongStateOf(0L) }

    DisposableEffect(Unit) {
        onDispose { service.cancel() }
    }

    // 自动滚动到最新消息
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    fun showSnackbar(msg: String) {
        scope.launch { snackbarHostState.showSnackbar(msg) }
    }

    fun copyToClipboard(text: String, label: String = "内容") {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        showSnackbar("已复制到剪贴板")
    }

    /** 发送用户消息并触发 AI 处理流程 */
    suspend fun sendMessage(text: String) {
        if (text.isBlank() || isProcessing) return

        // 1. 添加用户消息
        val userMsg = TeamMessage(
            id = "msg_${System.currentTimeMillis()}",
            roleAlias = "你",
            roleIcon = "",
            roleColor = 0xFF1449E2,
            content = text.trim(),
            timestamp = System.currentTimeMillis(),
            isUser = true,
        )
        messages = messages + userMsg
        inputText = ""
        isProcessing = true

        // 重置流水线状态
        currentPlan = null
        currentStepIndex = -1
        finalOutput = ""
        totalDuration = 0L
        service.resetCancel()

        try {
            val result = withContext(Dispatchers.IO) {
                service.planAndExecute(
                    userInput = text.trim(),
                    onPlanReady = { plan ->
                        // 规划完成后，添加一条"规划中"的系统消息
                        currentPlan = plan
                        val planningMsg = TeamMessage(
                            id = "plan_${System.currentTimeMillis()}",
                            roleAlias = "总编",
                            roleIcon = "\u2604",
                            roleColor = 0xFF4A90D9,
                            content = buildString {
                                appendLine("已制定创作计划，共 **${plan.steps.size}** 个步骤：\n")
                                plan.steps.forEachIndexed { idx, step ->
                                    appendLine("${idx + 1}. ${step.role.icon} **${step.role.alias}** -- ${step.role.name}")
                                }
                                if (plan.reasoning.isNotBlank()) {
                                    appendLine("\n> ${plan.reasoning}")
                                }
                            },
                            timestamp = System.currentTimeMillis(),
                            isUser = false,
                        )
                        messages = messages + planningMsg
                    },
                    onStepComplete = { roleInstance, output ->
                        // 每步完成，添加该角色的回复消息
                        val isLastStep = currentPlan?.steps?.lastIndex == currentStepIndex
                        val stepMsg = TeamMessage(
                            id = "step_${System.currentTimeMillis()}_${currentStepIndex}",
                            roleAlias = roleInstance.alias,
                            roleIcon = roleInstance.icon,
                            roleColor = roleInstance.displayColor,
                            content = output,
                            timestamp = System.currentTimeMillis(),
                            isUser = false,
                            isDocument = isLastStep && output.length > 200,
                            documentWordCount = output.length,
                            documentTitle = extractTitle(output),
                        )
                        messages = messages + stepMsg
                        currentStepIndex++
                    },
                )
            }

            // 正常完成时更新最终输出
            if (!service.isCancelled && result.finalOutput.isNotBlank()) {
                finalOutput = result.finalOutput
                totalDuration = result.totalDurationMs

                // 如果最终输出还没有作为文档消息展示，补充一条
                val hasDocMsg = messages.any { it.isDocument }
                if (!hasDocMsg && result.finalOutput.length > 100) {
                    val docMsg = TeamMessage(
                        id = "final_doc_${System.currentTimeMillis()}",
                        roleAlias = "输出",
                        roleIcon = "\uD83D\uDCDD",
                        roleColor = 0xFF16A085,
                        content = result.finalOutput,
                        timestamp = System.currentTimeMillis(),
                        isUser = false,
                        isDocument = true,
                        documentWordCount = result.finalOutput.length,
                        documentTitle = extractTitle(result.finalOutput),
                    )
                    messages = messages + docMsg
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            DebugLog.w("EditorTeamScreen: 执行被用户取消")
            showSnackbar("已停止执行")
        } catch (e: Exception) {
            DebugLog.e("EditorTeamScreen: 执行异常: ${e.message}", e)
            val errorMsg = TeamMessage(
                id = "error_${System.currentTimeMillis()}",
                roleAlias = "系统",
                roleIcon = "!",
                roleColor = 0xFFE74C3C,
                content = "执行出错：${e.message ?: "未知错误"}",
                timestamp = System.currentTimeMillis(),
                isUser = false,
                isError = true,
            )
            messages = messages + errorMsg
            showSnackbar("执行异常: ${e.message}")
        } finally {
            isProcessing = false
        }
    }

    /** 触发搜索并在右侧面板显示结果 */
    suspend fun performSearch(query: String) {
        isSearching = true
        showSearchPanel = true
        try {
            val results = withContext(Dispatchers.IO) {
                webSearcher.searchWithResilience(query, SearchConfig(), limit = 8)
            }
            searchResults = TeamSearchResult(
                query = query,
                source = "全网搜索",
                results = results.map { item ->
                    SearchItem(
                        title = item.title,
                        url = item.url,
                        snippet = item.snippet ?: "",
                        sourceName = item.sourceEngines.firstOrNull() ?: "未知来源",
                    )
                },
            )
        } catch (e: Exception) {
            DebugLog.e("EditorTeamScreen: 搜索失败: ${e.message}", e)
            searchResults = TeamSearchResult(
                query = query,
                source = "全网搜索",
                results = emptyList(),
            )
        } finally {
            isSearching = false
        }
    }

    Scaffold(
        containerColor = BgGray,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("AI 编辑团", fontWeight = FontWeight.Bold)
                        if (messages.isNotEmpty()) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "(${messages.count { !it.isUser }})",
                                color = TextGrey,
                                fontSize = 12.sp,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        service.cancel()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 新建对话
                    TextButton(
                        onClick = {
                            messages = emptyList()
                            currentPlan = null
                            searchResults = null
                            showSearchPanel = false
                            finalOutput = ""
                            inputText = ""
                        },
                        enabled = messages.isNotEmpty(),
                    ) {
                        Text("新建对话", fontSize = 13.sp)
                    }
                    // 历史记录按钮（预留）
                    IconButton(onClick = { /* TODO: 打开历史 */ }) {
                        Icon(Icons.Default.History, contentDescription = "历史", tint = TextGrey)
                    }
                    // 切换搜索面板
                    IconButton(onClick = {
                        if (searchResults != null) {
                            showSearchPanel = !showSearchPanel
                        } else {
                            scope.launch { performSearch(inputText.ifBlank { "写作技巧" }) }
                        }
                    }) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "搜索",
                            tint = if (showSearchPanel) AccentBlue else TextGrey,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CardWhite),
            )
        },
    ) { padding ->
        Row(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            // ==================== 左侧主区域 ====================
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                // 对话消息列表
                if (messages.isEmpty()) {
                    // 空状态欢迎页
                    WelcomeArea(
                        onSend = { text ->
                            scope.launch { sendMessage(text) }
                        },
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        itemsIndexed(messages, key = { _, msg -> msg.id }) { index, message ->
                            when {
                                message.isUser -> UserMessageBubble(content = message.content)
                                message.isDocument -> DocumentOutputCard(
                                    message = message,
                                    duration = totalDuration,
                                    onCopy = { copyToClipboard(message.content) },
                                    onSaveToNote = {
                                        scope.launch {
                                            val noteId = vm.noteRepository.quickCreate(
                                                message.content,
                                                NoteType.TEXT,
                                            )
                                            showSnackbar("已保存为笔记 (ID: $noteId)")
                                        }
                                    },
                                    onShare = {
                                        val sendIntent = android.content.Intent(
                                            android.content.Intent.ACTION_SEND,
                                        ).apply {
                                            type = "text/plain"
                                            putExtra(
                                                android.content.Intent.EXTRA_TEXT,
                                                message.content,
                                            )
                                        }
                                        val chooser = android.content.Intent.createChooser(
                                            sendIntent,
                                            "分享文章",
                                        )
                                        context.startActivity(chooser)
                                    },
                                )
                                message.isError -> ErrorMessageBubble(content = message.content)
                                else -> AiRoleMessage(
                                    alias = message.roleAlias,
                                    icon = message.roleIcon,
                                    color = message.roleColor,
                                    content = message.content,
                                    isStreaming = message.isStreaming,
                                    onCopy = { copyToClipboard(message.content) },
                                )
                            }
                        }

                        // AI 处理中的加载指示
                        if (isProcessing && currentPlan != null && currentStepIndex >= 0) {
                            val planSteps = currentPlan?.steps ?: emptyList()
                            if (currentStepIndex < planSteps.size) {
                                item {
                                    val currentStep = planSteps[currentStepIndex]
                                    val roleInstance = currentStep.role
                                    Card(
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = Color(roleInstance.displayColor).copy(alpha = 0.06f),
                                        ),
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(16.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(22.dp),
                                                strokeWidth = 2.5.dp,
                                                color = Color(roleInstance.displayColor),
                                            )
                                            Spacer(Modifier.width(12.dp))
                                            Column {
                                                Text(
                                                    text = "${roleInstance.icon} ${roleInstance.alias} 正在工作中...",
                                                    fontWeight = FontWeight.Medium,
                                                    fontSize = 14.sp,
                                                )
                                                Text(
                                                    text = "正在调用 AI 处理，请稍候...",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = TextGrey,
                                                    fontSize = 12.sp,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 规划中提示
                        if (isProcessing && currentPlan == null) {
                            item {
                                Card(
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = Color(0xFFE3F2FD),
                                    ),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            color = AccentBlue,
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Column {
                                            Text(
                                                "AI 正在分析任务...",
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 14.sp,
                                            )
                                            Text(
                                                "规划最优编辑流程",
                                                fontSize = 12.sp,
                                                color = TextGrey,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        item { Spacer(Modifier.height(80.dp)) }
                    }
                }

                // 底部输入区
                BottomInputBar(
                    inputText = inputText,
                    onInputChange = { inputText = it },
                    onSend = {
                        scope.launch { sendMessage(it) }
                    },
                    onStop = {
                        service.cancel()
                        isProcessing = false
                    },
                    isProcessing = isProcessing,
                    isEnabled = inputText.isNotBlank() && !isProcessing,
                )
            }

            // ==================== 右侧搜索面板 ====================
            AnimatedVisibility(
                visible = showSearchPanel,
                enter = slideInHorizontally(initialOffsetX = { it / 3 }),
                exit = slideOutHorizontally(targetOffsetX = { it / 3 }),
            ) {
                SearchSidePanel(
                    results = searchResults,
                    isLoading = isSearching,
                    onClose = { showSearchPanel = false },
                    modifier = Modifier
                        .width(320.dp)
                        .fillMaxHeight(),
                )
            }
        }
    }
}

// ==================== 子组件 ====================

/** 欢迎空状态区域 */
@Composable
private fun WelcomeArea(
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var welcomeInput by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))

        // 标题区域
        Text(
            text = "AI 编辑团",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = TextDark,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "描述你想创作什么，AI 自动调度编辑团队完成",
            fontSize = 14.sp,
            color = TextGrey,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(32.dp))

        // 快捷场景卡片
        QuickScenarioCards(onSelect = { scenario ->
            welcomeInput = scenario
            onSend(scenario)
        })

        Spacer(Modifier.height(32.dp))

        // 输入框
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CardWhite),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp, max = 200.dp)
                        .background(Color(0xFFF8F8F8), RoundedCornerShape(10.dp))
                        .padding(12.dp),
                ) {
                    if (welcomeInput.isEmpty()) {
                        Text(
                            text = "描述你的创作需求...\n例如：写一篇关于远程工作效率的文章",
                            color = Color(0xFFBDBDBD),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    BasicTextField(
                        value = welcomeInput,
                        onValueChange = { welcomeInput = it },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextDark),
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(
                        onClick = { onSend(welcomeInput) },
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                        enabled = welcomeInput.isNotBlank(),
                    ) {
                        Icon(Icons.Default.Send, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("开始创作")
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // 能力说明
        Text(
            text = "AI 编辑团会自动：分析需求 > 制定计划 > 调度角色 > 输出成果",
            fontSize = 12.sp,
            color = Color(0xFFBDBDBD),
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(40.dp))
    }
}

/** 快捷场景选择卡片 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickScenarioCards(onSelect: (String) -> Unit) {
    val scenarios = listOf(
        Triple("写一篇文章", "输入主题或素材，生成完整文章", "\u270D\uFE0F"),
        Triple("润色草稿", "已有内容需要打磨优化", "\u2728"),
        Triple("整理笔记", "散乱内容整理为知识体系", "\uD83D\uDCE6"),
        Triple("选题策划", "从灵感中提炼可写选题", "\uD83D\uDCA1"),
    )

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        scenarios.forEach { (title, desc, icon) ->
            Card(
                onClick = { onSelect(title) },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = CardWhite),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(icon, fontSize = 18.sp)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(desc, fontSize = 10.sp, color = TextGrey)
                    }
                }
            }
        }
    }
}

/** 用户消息气泡 */
@Composable
private fun UserMessageBubble(content: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.82f)
                .background(AccentBlue.copy(alpha = 0.08f), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 0.dp, bottomStart = 16.dp))
                .padding(12.dp),
        ) {
            Text(
                text = content,
                fontSize = 14.sp,
                color = TextDark,
                lineHeight = 20.sp,
            )
        }
    }
}

/** AI 角色消息（带头像和名字） */
@Composable
private fun AiRoleMessage(
    alias: String,
    icon: String,
    color: Long,
    content: String,
    isStreaming: Boolean,
    onCopy: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 角色头部
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .background(Color(color).copy(alpha = 0.1f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(icon, fontSize = 15.sp)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = alias,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = Color(color),
                )
                if (isStreaming) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = Color(color),
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = onCopy,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        "复制",
                        modifier = Modifier.size(14.dp),
                        tint = TextGrey,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Markdown 内容
            MarkdownText(
                text = content.ifEmpty { "（处理中...）" },
                maxChars = 4000,
            )
        }
    }
}

/** 错误消息气泡 */
@Composable
private fun ErrorMessageBubble(content: String) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("\u26A0", fontSize = 18.sp, color = Color(0xFFE53935))
            Spacer(Modifier.width(8.dp))
            Text(content, fontSize = 13.sp, color = Color(0xFFC62828))
        }
    }
}

/** 文档输出卡片（带字数统计和操作按钮） */
@Composable
private fun DocumentOutputCard(
    message: TeamMessage,
    duration: Long,
    onCopy: () -> Unit,
    onSaveToNote: () -> Unit,
    onShare: () -> Unit,
) {
    var expanded by rememberSaveable("${message.id}_doc_expanded") { mutableStateOf(true) }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = AccentBlue.copy(alpha = 0.04f),
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // 卡片头部
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 文档图标
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(AccentBlue.copy(alpha = 0.12f), RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("\uD83D\uDCDD", fontSize = 14.sp)
                }
                Spacer(Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = message.documentTitle.ifBlank { "创作成果" },
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp),
                    ) {
                        Text(
                            text = "${message.documentWordCount} 字",
                            fontSize = 11.sp,
                            color = AccentBlue,
                            fontWeight = FontWeight.Medium,
                        )
                        if (duration > 0) {
                            Text(
                                text = " \u00B7 ${formatDuration(duration)}",
                                fontSize = 11.sp,
                                color = TextGrey,
                            )
                        }
                    }
                }

                Text(if (expanded) "▼" else "▶", fontSize = 10.sp, color = TextGrey)
            }

            // 可展开的内容
            AnimatedVisibility(visible = expanded) {
                Column {
                    HorizontalDivider(
                        color = AccentBlue.copy(alpha = 0.1f),
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    MarkdownText(
                        text = message.content,
                        maxChars = 5000,
                    )

                    // 操作按钮行
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        OutlinedButton(
                            onClick = onSaveToNote,
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Icon(Icons.Default.NoteAdd, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("存为笔记", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = onShare,
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Icon(Icons.Default.Share, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("分享", fontSize = 12.sp)
                        }
                        Button(
                            onClick = onCopy,
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                        ) {
                            Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("复制全文", fontSize = 12.sp, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

/** 底部输入栏 */
@Composable
private fun BottomInputBar(
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    isProcessing: Boolean,
    isEnabled: Boolean,
) {
    Card(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
            ) {
                // 输入框
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp, max = 120.dp)
                        .background(Color(0xFFF5F5F5), RoundedCornerShape(22.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    if (inputText.isEmpty()) {
                        Text(
                            text = "描述你想创作什么...",
                            color = Color(0xFFBDBDBD),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    BasicTextField(
                        value = inputText,
                        onValueChange = onInputChange,
                        enabled = !isProcessing,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextDark),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.width(8.dp))

                // 发送/停止按钮
                if (isProcessing) {
                    OutlinedButton(
                        onClick = onStop,
                        shape = CircleShape,
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color(0xFFFFEBEE),
                            contentColor = Color(0xFFE53935),
                        ),
                        modifier = Modifier.size(44.dp),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Icon(Icons.Default.Stop, null, modifier = Modifier.size(18.dp))
                    }
                } else {
                    Button(
                        onClick = { onSend(inputText) },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                        enabled = isEnabled,
                        modifier = Modifier.size(44.dp),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Icon(Icons.Default.Send, null, modifier = Modifier.size(18.dp))
                    }
                }
            }

            // 工具栏提示行
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (isProcessing) "正在处理中..." else "按 Enter 发送",
                    fontSize = 11.sp,
                    color = Color(0xFFCCCCCC),
                )
                if (isProcessing) {
                    Text(
                        text = "点击红色按钮可停止",
                        fontSize = 11.sp,
                        color = Color(0xFFE57373),
                    )
                }
            }
        }
    }
}

/** 右侧搜索面板 */
@Composable
private fun SearchSidePanel(
    results: TeamSearchResult?,
    isLoading: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(CardWhite)
            .padding(12.dp),
    ) {
        // 面板头部
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = results?.source ?: "搜索",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(Icons.Default.Close, "关闭", modifier = Modifier.size(18.dp))
            }
        }

        HorizontalDivider(color = Color(0xFFEEEEEE), modifier = Modifier.padding(vertical = 8.dp))

        // 加载中
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), color = AccentBlue)
            }
            return@Column
        }

        // 无结果
        if (results == null || results.results.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("暂无搜索结果", color = TextGrey, fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("尝试换个关键词", color = Color(0xFFCCCCCC), fontSize = 11.sp)
                }
            }
            return@Column
        }

        // 搜索关键词标签
        Text(
            text = "\"${results.query}\"",
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            color = AccentBlue,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Text(
            text = "${results.results.size} 条结果",
            fontSize = 11.sp,
            color = TextGrey,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        // 结果列表
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(results.results) { index, item ->
                SearchResultCard(item = item, index = index + 1)
            }
        }
    }
}

/** 单条搜索结果卡片 */
@Composable
private fun SearchResultCard(item: SearchItem, index: Int) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = BgGray),
        modifier = Modifier.clickable { /* TODO: 打开链接 */ },
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$index.",
                    fontSize = 11.sp,
                    color = TextGrey,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(20.dp),
                )
                Text(
                    text = item.title,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            if (item.snippet.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.snippet,
                    fontSize = 11.sp,
                    color = TextGrey,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 16.sp,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = item.sourceName,
                fontSize = 10.sp,
                color = Color(0xFFAAAAAA),
            )
        }
    }
}

// ==================== 工具函数 ====================

/** 从文本中提取标题（取第一行非空内容或前30字） */
private fun extractTitle(text: String): String {
    val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
    return lines.firstOrNull()?.take(30) ?: ""
}

private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    return if (seconds < 60) "${seconds}秒"
    else {
        val min = seconds / 60
        val sec = seconds % 60
        "${min}分${sec}秒"
    }
}

