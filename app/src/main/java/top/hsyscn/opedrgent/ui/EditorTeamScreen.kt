@file:OptIn(ExperimentalMaterial3Api::class)

package top.hsyscn.opedrgent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Send
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
import kotlinx.coroutines.launch
import top.hsyscn.opedrgent.mcp.editors.EditorRole
import top.hsyscn.opedrgent.mcp.editors.EditorTeamService
import top.hsyscn.opedrgent.mcp.editors.RoleInstance
import top.hsyscn.opedrgent.note.NoteType
import top.hsyscn.opedrgent.ui.theme.AccentBlue
import top.hsyscn.opedrgent.ui.theme.BgGray
import top.hsyscn.opedrgent.ui.theme.CardWhite
import top.hsyscn.opedrgent.ui.theme.TextDark
import top.hsyscn.opedrgent.ui.theme.TextGrey
import top.hsyscn.opedrgent.ui.components.MarkdownText

// ==================== 数据模型 ====================

/** 群聊消息 */
data class GroupChatMessage(
    val id: String,
    val roleAlias: String,
    val roleIcon: String,
    val roleColor: Long,
    val roleName: String = "",
    val content: String,
    val timestamp: Long,
    val isUser: Boolean,
    val isStreaming: Boolean = false,
    val isFinalDraft: Boolean = false,  // 主编最终定稿
    val isError: Boolean = false,
)

// ==================== 主界面：写作模式（群聊式） ====================

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
    val listState = rememberLazyListState()

    // 群聊状态
    var messages by rememberSaveable { mutableStateOf<List<GroupChatMessage>>(emptyList()) }
    var inputText by remember { mutableStateOf(initialInput) }
    var isProcessing by remember { mutableStateOf(false) }
    var currentSpeakingAlias by remember { mutableStateOf("") } // 当前正在发言的角色

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

    /** 发送消息并触发群聊讨论 */
    suspend fun sendMessage(text: String) {
        if (text.isBlank() || isProcessing) return

        // 添加用户消息
        val userMsg = GroupChatMessage(
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

        try {
            val result = service.groupDiscussion(
                userInput = text.trim(),
                roles = EditorRole.defaultPipeline,
                onEachMessage = { discussionMsg: top.hsyscn.opedrgent.mcp.editors.DiscussionMessage ->
                    val msg = GroupChatMessage(
                        id = "ai_${System.currentTimeMillis()}_${discussionMsg.order}",
                        roleAlias = discussionMsg.role.alias,
                        roleIcon = discussionMsg.role.icon,
                        roleColor = discussionMsg.role.displayColor,
                        roleName = discussionMsg.role.name,
                        content = discussionMsg.content,
                        timestamp = System.currentTimeMillis(),
                        isUser = false,
                        isFinalDraft = discussionMsg.isFinalDraft,
                    )
                    messages = messages + msg
                    currentSpeakingAlias = ""
                },
            )

            // 如果没有通过回调添加消息（异常路径），补充最终稿
            if (result.finalDraft.isNotBlank() && messages.none { it.isFinalDraft }) {
                val finalMsg = GroupChatMessage(
                    id = "final_${System.currentTimeMillis()}",
                    roleAlias = "主编",
                    roleIcon = "E",
                    roleColor = 0xFF27AE60,
                    roleName = "总编主编",
                    content = result.finalDraft,
                    timestamp = System.currentTimeMillis(),
                    isUser = false,
                    isFinalDraft = true,
                )
                messages = messages + finalMsg
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            showSnackbar("已停止")
        } catch (e: Exception) {
            val errorMsg = GroupChatMessage(
                id = "error_${System.currentTimeMillis()}",
                roleAlias = "系统",
                roleIcon = "!",
                roleColor = 0xFFE74C3C,
                content = "出错：${e.message ?: "未知错误"}",
                timestamp = System.currentTimeMillis(),
                isUser = false,
                isError = true,
            )
            messages = messages + errorMsg
        } finally {
            isProcessing = false
            currentSpeakingAlias = ""
        }
    }

    // 参与讨论的角色列表（用于顶部展示）
    val participants = EditorRole.defaultPipeline

    Scaffold(
        containerColor = BgGray,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("写作模式", fontWeight = FontWeight.Bold)
                        if (messages.any { !it.isUser }) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "(${participants.size}人讨论中)",
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
                    TextButton(
                        onClick = {
                            messages = emptyList()
                            inputText = ""
                        },
                        enabled = messages.isNotEmpty(),
                    ) {
                        Text("新建", fontSize = 13.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CardWhite),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            // 群成员头像条（讨论开始后显示）
            if (isProcessing || messages.any { !it.isUser }) {
                ParticipantBar(
                    roles = participants,
                    speakingAlias = currentSpeakingAlias,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CardWhite)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
                HorizontalDivider(color = Color(0xFFEEEEEE))
            }

            // 消息列表
            if (messages.isEmpty()) {
                WritingWelcomeArea(
                    onSend = { text ->
                        scope.launch { sendMessage(text) }
                    },
                    participants = participants,
                    modifier = Modifier.weight(1f),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    itemsIndexed(messages, key = { _, msg -> msg.id }) { _, message ->
                        when {
                            message.isUser -> UserBubble(content = message.content)
                            message.isFinalDraft -> FinalDraftCard(
                                content = message.content,
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
                            )
                            message.isError -> ErrorBubble(content = message.content)
                            else -> AgentBubble(
                                alias = message.roleAlias,
                                icon = message.roleIcon,
                                color = message.roleColor,
                                name = message.roleName,
                                content = message.content,
                                isStreaming = message.isStreaming,
                                onCopy = { copyToClipboard(message.content) },
                            )
                        }
                    }

                    // 正在输入指示器
                    if (isProcessing && currentSpeakingAlias.isNotBlank()) {
                        item {
                            TypingIndicator(alias = currentSpeakingAlias, participants = participants)
                        }
                    }

                    item { Spacer(Modifier.height(70.dp)) }
                }
            }

            // 底部输入栏
            ChatInputBar(
                inputText = inputText,
                onInputChange = { inputText = it },
                onSend = {
                    scope.launch { sendMessage(it) }
                },
                onStop = {
                    service.cancel()
                    isProcessing = false
                    currentSpeakingAlias = ""
                },
                isProcessing = isProcessing,
                isEnabled = inputText.isNotBlank() && !isProcessing,
            )
        }
    }
}

// ==================== 子组件 ====================

/** 群成员头像条 — 显示参与讨论的所有角色 */
@Composable
private fun ParticipantBar(
    roles: List<EditorRole>,
    speakingAlias: String,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        roles.forEach { role ->
            val isSpeaking = role.alias == speakingAlias
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(
                        Color(role.color).copy(alpha = if (isSpeaking) 0.25f else 0.08f),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(role.icon, fontSize = 15.sp)
            }
        }
        Spacer(Modifier.weight(1f))
        if (speakingAlias.isNotBlank()) {
            Text(
                text = "$speakingAlias 正在说...",
                fontSize = 11.sp,
                color = Color(roles.firstOrNull { it.alias == speakingAlias }?.color ?: 0xFF999999L),
                fontWeight = FontWeight.Medium,
            )
        } else {
            Text(
                text = "${roles.size} 位编辑已就位",
                fontSize = 11.sp,
                color = TextGrey,
            )
        }
    }
}

/** 写作模式欢迎页 */
@Composable
private fun WritingWelcomeArea(
    onSend: (String) -> Unit,
    participants: List<EditorRole>,
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
        Spacer(Modifier.height(30.dp))

        Text(
            text = "写作模式",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = TextDark,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "把你的想法扔进来，几位编辑会在群里讨论，最后给你一版定稿",
            fontSize = 13.sp,
            color = TextGrey,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(28.dp))

        // 群成员预览
        Text(text = "本次讨论阵容", fontSize = 12.sp, color = TextGrey)
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            participants.forEach { role ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(Color(role.color).copy(alpha = 0.1f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(role.icon, fontSize = 20.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = role.alias,
                        fontSize = 10.sp,
                        color = Color(role.color),
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // 快捷场景
        WritingQuickScenarios(onSelect = { scenario ->
            welcomeInput = scenario
            onSend(scenario)
        })

        Spacer(Modifier.height(24.dp))

        // 输入区
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CardWhite),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 90.dp, max = 180.dp)
                        .background(Color(0xFFF8F8F8), RoundedCornerShape(10.dp))
                        .padding(12.dp),
                ) {
                    if (welcomeInput.isEmpty()) {
                        Text(
                            text = "把你想写的东西贴进来，或者描述一下你想写什么...\n比如：帮我写一段关于AI改变教育的文字",
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
                Spacer(Modifier.height(10.dp))
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
                        Text("开始讨论")
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            text = "文采匠负责润色 / 历史学家拉古论今 / 技术审查找漏洞 / 逻辑侦探查论证 / 主编综合定稿",
            fontSize = 11.sp,
            color = Color(0xFFCCCCCC),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(30.dp))
    }
}

/** 快捷场景卡片 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WritingQuickScenarios(onSelect: (String) -> Unit) {
    val scenarios = listOf(
        Triple("写一篇文章", "输入主题或素材", "W"),
        Triple("润色这段话", "已有内容需要打磨", "P"),
        Triple("帮我审一审", "检查逻辑和事实问题", "C"),
        Triple("换个风格写", "改写成其他调性", "S"),
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
                    Text(icon, fontSize = 17.sp)
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

/** 用户消息气泡（右侧） */
@Composable
private fun UserBubble(content: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.78f)
                .background(AccentBlue.copy(alpha = 0.08f), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 4.dp, bottomStart = 16.dp))
                .padding(12.dp),
        ) {
            Text(text = content, fontSize = 14.sp, color = TextDark, lineHeight = 20.sp)
        }
    }
}

/** Agent 消息气泡（左侧，带头像）— 群聊核心组件 */
@Composable
private fun AgentBubble(
    alias: String,
    icon: String,
    color: Long,
    name: String,
    content: String,
    isStreaming: Boolean,
    onCopy: () -> Unit,
) {
    val bubbleColor = Color(color)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        // 头像
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(bubbleColor.copy(alpha = 0.1f), CircleShape)
                .padding(2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(icon, fontSize = 17.sp)
        }

        Spacer(Modifier.width(8.dp))

        // 气泡主体
        Column(modifier = Modifier.weight(1f)) {
            // 名字标签
            Text(
                text = name.ifBlank { alias },
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = bubbleColor,
            )
            Spacer(Modifier.height(3.dp))

            // 内容气泡
            Box(
                modifier = Modifier
                    .background(bubbleColor.copy(alpha = 0.05f), RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 4.dp))
                    .padding(12.dp),
            ) {
                Column {
                    MarkdownText(
                        text = content.ifEmpty { if (isStreaming) "..." else "(无内容)" },
                        maxChars = 4000,
                    )
                    // 单条复制按钮
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        IconButton(
                            onClick = onCopy,
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                "复制",
                                modifier = Modifier.size(12.dp),
                                tint = TextGrey,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 最终定稿卡片（主编专用，特殊样式突出显示） */
@Composable
private fun FinalDraftCard(
    content: String,
    onCopy: () -> Unit,
    onSaveToNote: () -> Unit,
) {
    var expanded by rememberSaveable("final_draft_expanded") { mutableStateOf(true) }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF27AE60).copy(alpha = 0.05f),
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // 头部
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(Color(0xFF27AE60).copy(alpha = 0.15f), RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("E", fontSize = 14.sp)
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "主编定稿",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = Color(0xFF27AE60),
                    )
                    Text(
                        text = "${content.length} 字 -- 综合各位意见后的最终版本",
                        fontSize = 11.sp,
                        color = TextGrey,
                    )
                }
                Text(if (expanded) "▼" else "▶", fontSize = 10.sp, color = TextGrey)
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    HorizontalDivider(
                        color = Color(0xFF27AE60).copy(alpha = 0.1f),
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    MarkdownText(text = content, maxChars = 5000)

                    // 操作按钮
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
                        Button(
                            onClick = onCopy,
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF27AE60)),
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

/** 错误提示 */
@Composable
private fun ErrorBubble(content: String) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("!", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE53935))
            Spacer(Modifier.width(8.dp))
            Text(content, fontSize = 13.sp, color = Color(0xFFC62828))
        }
    }
}

/** 正在输入指示器（某某正在打字...） */
@Composable
private fun TypingIndicator(
    alias: String,
    participants: List<EditorRole>,
) {
    val role = participants.firstOrNull { it.alias == alias }
    val roleColor = Color(role?.color ?: 0xFF999999)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 42.dp, top = 4.dp),
    ) {
        // 跳动的三个点
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(3) { index ->
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .background(roleColor, CircleShape)
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "$alias 正在思考...",
            fontSize = 12.sp,
            color = TextGrey,
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
        )
    }
}

/** 底部输入栏 */
@Composable
private fun ChatInputBar(
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
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 42.dp, max = 110.dp)
                        .background(Color(0xFFF5F5F5), RoundedCornerShape(22.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    if (inputText.isEmpty()) {
                        Text(
                            text = "把你的想法或草稿贴进来...",
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
                if (isProcessing) {
                    OutlinedButton(
                        onClick = onStop,
                        shape = CircleShape,
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color(0xFFFFEBEE),
                            contentColor = Color(0xFFE53935),
                        ),
                        modifier = Modifier.size(42.dp),
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
                        modifier = Modifier.size(42.dp),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Icon(Icons.Default.Send, null, modifier = Modifier.size(18.dp))
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (isProcessing) "点击红色按钮停止讨论" else "Enter 发送",
                fontSize = 10.sp,
                color = Color(0xFFCCCCCC),
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}
