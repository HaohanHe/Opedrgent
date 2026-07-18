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
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.mcp.editors.DiscussionMessage
import top.hsyscn.opedrgent.mcp.editors.DynamicRole
import top.hsyscn.opedrgent.mcp.editors.EditorRole
import top.hsyscn.opedrgent.mcp.editors.EditorTeamService
import top.hsyscn.opedrgent.mcp.editors.RoleInstance
import top.hsyscn.opedrgent.note.NoteType
import top.hsyscn.opedrgent.ui.components.MarkdownText
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import top.hsyscn.opedrgent.ui.theme.SizeTokens
import top.hsyscn.opedrgent.ui.theme.SpacingTokens
import top.hsyscn.opedrgent.ui.theme.customColors
import top.hsyscn.opedrgent.ui.theme.themeBgGray
import top.hsyscn.opedrgent.ui.theme.themeCardWhite
import top.hsyscn.opedrgent.ui.theme.themeDividerColor
import top.hsyscn.opedrgent.ui.theme.themeTextDark
import top.hsyscn.opedrgent.ui.theme.themeTextGrey

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
    val service = remember { EditorTeamService(vm.apiSettings, context = context) }
    val listState = rememberLazyListState()

    // 群聊状态
    // ★ 使用 remember 而非 rememberSaveable：消息列表可能很大，保存到 Bundle 会导致
    // TransactionTooLargeException；进程重建时由业务层重新加载即可。
    var messages by remember { mutableStateOf<List<GroupChatMessage>>(emptyList()) }
    var inputText by remember { mutableStateOf(initialInput) }
    var isProcessing by remember { mutableStateOf(false) }
    var currentSpeakingAlias by remember { mutableStateOf("") } // 当前正在发言的角色
    var pipelineIndex by rememberSaveable { mutableIntStateOf(0) }
    var pipelineMenuExpanded by remember { mutableStateOf(false) }
    val selectedPipeline = if (pipelineIndex == 0) EditorRole.defaultPipeline else EditorRole.quickPolishPipeline

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

    fun copyToClipboard(text: String, label: String = context.getString(R.string.editor_team_nei_rong)) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        showSnackbar(context.getString(R.string.editor_team_yi_fu_zhi_dao_jian_tie_ban))
    }

    /** 发送消息并触发群聊讨论 */
    suspend fun sendMessage(text: String) {
        if (text.isBlank() || isProcessing) return

        // 添加用户消息
        val userMsg = GroupChatMessage(
            id = "msg_${System.currentTimeMillis()}",
            roleAlias = context.getString(R.string.editor_team_ni),
            roleIcon = "",
            roleColor = 0xFF1449E2,
            content = text.trim(),
            timestamp = System.currentTimeMillis(),
            isUser = true,
        )
        messages = messages + userMsg
        inputText = ""
        isProcessing = true

        val history = messages
            .filter { !it.isUser && !it.isError && it.content.isNotBlank() }
            .mapIndexed { idx, msg ->
                val role = RoleInstance.Dynamic(
                    DynamicRole(
                        name = msg.roleName.ifBlank { msg.roleAlias },
                        alias = msg.roleAlias,
                        icon = msg.roleIcon,
                        systemPrompt = "",
                    )
                )
                DiscussionMessage(role = role, content = msg.content, order = idx)
            }

        try {
            val result = service.groupDiscussionStreaming(
                userInput = text.trim(),
                roles = selectedPipeline,
                discussionHistory = history,
                onRoleStart = { alias ->
                    currentSpeakingAlias = alias
                    val role = selectedPipeline.firstOrNull { it.alias == alias }
                    val placeholder = GroupChatMessage(
                        id = "stream_${System.currentTimeMillis()}_$alias",
                        roleAlias = alias,
                        roleIcon = role?.icon ?: "?",
                        roleColor = role?.color ?: 0xFF999999L,
                        roleName = role?.displayName ?: alias,
                        content = "",
                        timestamp = System.currentTimeMillis(),
                        isUser = false,
                        isStreaming = true,
                    )
                    messages = messages + placeholder
                },
                onRoleChunk = { alias, chunk ->
                    messages = messages.map { msg ->
                        if (msg.isStreaming && msg.roleAlias == alias) {
                            msg.copy(content = msg.content + chunk)
                        } else msg
                    }
                },
                onRoleComplete = { alias, fullText ->
                    messages = messages.map { msg ->
                        if (msg.isStreaming && msg.roleAlias == alias) {
                            msg.copy(content = fullText.ifBlank { msg.content }, isStreaming = false)
                        } else msg
                    }
                    currentSpeakingAlias = ""
                },
                onEachMessage = { discussionMsg ->
                    if (discussionMsg.isFinalDraft) {
                        val lastAiIdx = messages.indexOfLast { !it.isUser && !it.isError }
                        if (lastAiIdx >= 0) {
                            messages = messages.mapIndexed { idx, msg ->
                                if (idx == lastAiIdx) msg.copy(isFinalDraft = true) else msg
                            }
                        }
                    }
                },
            )

            if (result.finalDraft.isNotBlank() && messages.none { it.isFinalDraft }) {
                val existingIdx = messages.indexOfLast { !it.isUser && it.content == result.finalDraft }
                if (existingIdx >= 0) {
                    messages = messages.mapIndexed { idx, msg ->
                        if (idx == existingIdx) msg.copy(isFinalDraft = true) else msg
                    }
                } else {
                    val editorRole = selectedPipeline.lastOrNull()
                    val finalMsg = GroupChatMessage(
                        id = "final_${System.currentTimeMillis()}",
                        roleAlias = editorRole?.alias ?: context.getString(R.string.editor_team_zhu_bian),
                        roleIcon = editorRole?.icon ?: "E",
                        roleColor = editorRole?.color ?: 0xFF27AE60,
                        roleName = editorRole?.displayName ?: context.getString(R.string.editor_team_zhu_bian),
                        content = result.finalDraft,
                        timestamp = System.currentTimeMillis(),
                        isUser = false,
                        isFinalDraft = true,
                    )
                    messages = messages + finalMsg
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            showSnackbar(context.getString(R.string.editor_team_yi_ting_zhi))
            messages = messages.map { if (it.isStreaming) it.copy(isStreaming = false) else it }
        } catch (e: Exception) {
            val errorMsg = GroupChatMessage(
                id = "error_${System.currentTimeMillis()}",
                roleAlias = context.getString(R.string.editor_team_xi_tong),
                roleIcon = "!",
                roleColor = 0xFFE74C3C,
                content = context.getString(R.string.editor_team_chu_cuo_1, e.message ?: context.getString(R.string.editor_team_wei_zhi_cuo_wu)),
                timestamp = System.currentTimeMillis(),
                isUser = false,
                isError = true,
            )
            messages = messages + errorMsg
        } finally {
            isProcessing = false
            currentSpeakingAlias = ""
            messages = messages.map { if (it.isStreaming) it.copy(isStreaming = false) else it }
        }
    }

    // 参与讨论的角色列表（用于顶部展示）
    val participants = selectedPipeline

    Scaffold(
        containerColor = themeBgGray(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.editor_team_xie_zuo_mo_shi),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        if (messages.any { !it.isUser }) {
                            Spacer(Modifier.width(SpacingTokens.xs))
                            Text(
                                text = stringResource(R.string.editor_team_1_ren_tao_lun_zhong, participants.size),
                                color = themeTextGrey(),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        service.cancel()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    Box {
                        TextButton(
                            onClick = { pipelineMenuExpanded = true },
                            enabled = !isProcessing,
                        ) {
                            Text(
                                if (pipelineIndex == 0) stringResource(R.string.editor_team_wan_zheng_tao_lun) else stringResource(R.string.editor_team_kuai_su_run_se),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        DropdownMenu(
                            expanded = pipelineMenuExpanded,
                            onDismissRequest = { pipelineMenuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.editor_team_wan_zheng_tao_lun_5_ren)) },
                                onClick = {
                                    pipelineIndex = 0
                                    pipelineMenuExpanded = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.editor_team_kuai_su_run_se_4_ren)) },
                                onClick = {
                                    pipelineIndex = 1
                                    pipelineMenuExpanded = false
                                },
                            )
                        }
                    }
                    TextButton(
                        onClick = {
                            messages = emptyList()
                            inputText = ""
                        },
                        enabled = messages.isNotEmpty() && !isProcessing,
                    ) {
                        Text(stringResource(R.string.editor_team_xin_jian), style = MaterialTheme.typography.labelMedium)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = themeCardWhite()),
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
                        .background(themeCardWhite())
                        .padding(horizontal = SpacingTokens.md, vertical = SpacingTokens.sm),
                )
                HorizontalDivider(color = themeDividerColor())
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
                        .padding(horizontal = SpacingTokens.sm),
                    verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
                    contentPadding = PaddingValues(vertical = SpacingTokens.sm),
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
                                        showSnackbar(context.getString(R.string.editor_team_yi_bao_cun_wei_bi_ji_1, noteId))
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
                    val hasStreamingContent = messages.any { it.isStreaming && it.content.isNotEmpty() }
                    if (isProcessing && currentSpeakingAlias.isNotBlank() && !hasStreamingContent) {
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
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.md),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        roles.forEach { role ->
            val isSpeaking = role.alias == speakingAlias
            Box(
                modifier = Modifier
                    .size(SpacingTokens.xxl)
                    .background(
                        Color(role.color).copy(alpha = if (isSpeaking) 0.25f else 0.08f),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(role.icon, style = MaterialTheme.typography.titleSmall)
            }
        }
        Spacer(Modifier.weight(1f))
        if (speakingAlias.isNotBlank()) {
            Text(
                text = stringResource(R.string.editor_team_1_zheng_zai_shuo, speakingAlias),
                style = MaterialTheme.typography.labelSmall,
                color = roles.firstOrNull { it.alias == speakingAlias }?.let { Color(it.color) } ?: MaterialTheme.colorScheme.outline,
            )
        } else {
            Text(
                text = stringResource(R.string.editor_team_1_wei_bian_ji_yi_jiu_wei, roles.size),
                style = MaterialTheme.typography.labelSmall,
                color = themeTextGrey(),
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
            .padding(SpacingTokens.xl)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(SpacingTokens.xxl))

        Text(
            text = stringResource(R.string.editor_team_xie_zuo_mo_shi),
            style = MaterialTheme.typography.displaySmall,
            color = themeTextDark(),
        )
        Spacer(Modifier.height(SpacingTokens.xs))
        Text(
            text = stringResource(R.string.editor_team_ba_ni_de_xiang_fa_reng_jin_lai),
            style = MaterialTheme.typography.bodyMedium,
            color = themeTextGrey(),
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(SpacingTokens.xl))

        // 群成员预览
        Text(text = stringResource(R.string.editor_team_ben_ci_tao_lun_zhen_rong), style = MaterialTheme.typography.bodySmall, color = themeTextGrey())
        Spacer(Modifier.height(SpacingTokens.sm))
        Row(
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.md),
            modifier = Modifier.padding(horizontal = SpacingTokens.lg),
        ) {
            participants.forEach { role ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(SizeTokens.featureIconBg)
                            .background(Color(role.color).copy(alpha = 0.1f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(role.icon, style = MaterialTheme.typography.titleLarge)
                    }
                    Spacer(Modifier.height(SpacingTokens.xs))
                    Text(
                        text = role.alias,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(role.color),
                    )
                }
            }
        }

        Spacer(Modifier.height(SpacingTokens.xl))

        // 快捷场景
        WritingQuickScenarios(onSelect = { scenario ->
            welcomeInput = scenario
            onSend(scenario)
        })

        Spacer(Modifier.height(SpacingTokens.xl))

        // 输入区
        Card(
            shape = ShapeTokens.largeShape,
            colors = CardDefaults.cardColors(containerColor = themeCardWhite()),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(SpacingTokens.md)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 90.dp, max = 180.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow, ShapeTokens.smallShape)
                        .padding(SpacingTokens.md),
                ) {
                    if (welcomeInput.isEmpty()) {
                        Text(
                            text = stringResource(R.string.editor_team_ba_ni_xiang_xie_de_dong_xi),
                            color = themeTextGrey().copy(alpha = 0.5f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    BasicTextField(
                        value = welcomeInput,
                        onValueChange = { welcomeInput = it },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = themeTextDark()),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Spacer(Modifier.height(SpacingTokens.sm))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(
                        onClick = { onSend(welcomeInput) },
                        shape = ShapeTokens.extraLargeShape,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.customColors.accentBlue),
                        enabled = welcomeInput.isNotBlank(),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.action_send), modifier = Modifier.size(SpacingTokens.lg))
                        Spacer(Modifier.width(SpacingTokens.xs))
                        Text(stringResource(R.string.editor_team_kaishi_tao_lun))
                    }
                }
            }
        }

        Spacer(Modifier.height(SpacingTokens.xl))
        Text(
            text = stringResource(R.string.editor_team_wen_cai_jiang_fu_ze),
            style = MaterialTheme.typography.labelSmall,
            color = themeTextGrey().copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(SpacingTokens.xxl))
    }
}

/** 快捷场景卡片 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WritingQuickScenarios(onSelect: (String) -> Unit) {
    val scenarios = listOf(
        Triple(stringResource(R.string.editor_team_xie_yi_pian_wen_zhang), stringResource(R.string.editor_team_shu_ru_zhu_ti_huo_su_cai), "W"),
        Triple(stringResource(R.string.editor_team_run_se_zhe_duan_hua), stringResource(R.string.editor_team_yi_you_nei_rong_xu_yao_da_mo), "P"),
        Triple(stringResource(R.string.editor_team_bang_wo_shen_yi_shen), stringResource(R.string.editor_team_jian_cha_luo_ji_he_shi_shi), "C"),
        Triple(stringResource(R.string.editor_team_huan_ge_feng_ge_xie), stringResource(R.string.editor_team_gai_xie_cheng_qi_ta_diao_xing), "S"),
    )

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
    ) {
        scenarios.forEach { (title, desc, icon) ->
            Card(
                onClick = { onSelect(title) },
                shape = ShapeTokens.mediumShape,
                colors = CardDefaults.cardColors(containerColor = themeCardWhite()),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = SpacingTokens.md, vertical = SpacingTokens.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(icon, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(SpacingTokens.sm))
                    Column {
                        Text(title, style = MaterialTheme.typography.titleSmall)
                        Text(desc, style = MaterialTheme.typography.labelSmall, color = themeTextGrey())
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
                .background(
                    MaterialTheme.customColors.accentBlue.copy(alpha = 0.08f),
                    RoundedCornerShape(
                        topStart = ShapeTokens.large,
                        topEnd = ShapeTokens.large,
                        bottomEnd = ShapeTokens.extraSmall,
                        bottomStart = ShapeTokens.large,
                    ),
                )
                .padding(SpacingTokens.md),
        ) {
            Text(text = content, style = MaterialTheme.typography.bodyMedium, color = themeTextDark())
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
                .size(SizeTokens.featureIconBg)
                .background(bubbleColor.copy(alpha = 0.1f), CircleShape)
                .padding(SpacingTokens.xxs),
            contentAlignment = Alignment.Center,
        ) {
            Text(icon, style = MaterialTheme.typography.titleMedium)
        }

        Spacer(Modifier.width(SpacingTokens.sm))

        // 气泡主体
        Column(modifier = Modifier.weight(1f)) {
            // 名字标签
            Text(
                text = name.ifBlank { alias },
                style = MaterialTheme.typography.labelSmall,
                color = bubbleColor,
            )
            Spacer(Modifier.height(SpacingTokens.xxs))

            // 内容气泡
            Box(
                modifier = Modifier
                    .background(
                        bubbleColor.copy(alpha = 0.05f),
                        RoundedCornerShape(
                            topStart = ShapeTokens.large,
                            topEnd = ShapeTokens.large,
                            bottomEnd = ShapeTokens.large,
                            bottomStart = ShapeTokens.extraSmall,
                        ),
                    )
                    .padding(SpacingTokens.md),
            ) {
                Column {
                    val displayText = when {
                        isStreaming && content.isNotEmpty() -> "$content▍"
                        content.isEmpty() -> if (isStreaming) "..." else stringResource(R.string.editor_team_wu_nei_rong)
                        else -> content
                    }
                    MarkdownText(
                        text = displayText,
                        maxChars = 4000,
                    )
                    // 单条复制按钮
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = SpacingTokens.xs),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        IconButton(
                            onClick = onCopy,
                            modifier = Modifier.size(SpacingTokens.xl),
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = stringResource(R.string.cd_copy),
                                modifier = Modifier.size(SpacingTokens.sm),
                                tint = themeTextGrey(),
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

    val finalDraftColor = MaterialTheme.customColors.successGreen

    Card(
        shape = ShapeTokens.largeShape,
        colors = CardDefaults.cardColors(
            containerColor = finalDraftColor.copy(alpha = 0.05f),
        ),
    ) {
        Column(modifier = Modifier.padding(SpacingTokens.md)) {
            // 头部
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(SizeTokens.iconXl)
                        .background(finalDraftColor.copy(alpha = 0.15f), ShapeTokens.extraSmallShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("E", style = MaterialTheme.typography.titleSmall)
                }
                Spacer(Modifier.width(SpacingTokens.sm))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.editor_team_zhu_bian_ding_gao),
                        style = MaterialTheme.typography.titleSmall,
                        color = finalDraftColor,
                    )
                    Text(
                        text = stringResource(R.string.editor_team_1_zi_zong_he_ge_wei_yi_jian, content.length),
                        style = MaterialTheme.typography.labelSmall,
                        color = themeTextGrey(),
                    )
                }
                Text(if (expanded) "▼" else "▶", style = MaterialTheme.typography.labelSmall, color = themeTextGrey())
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    HorizontalDivider(
                        color = finalDraftColor.copy(alpha = 0.1f),
                        modifier = Modifier.padding(vertical = SpacingTokens.sm),
                    )
                    MarkdownText(text = content, maxChars = 5000)

                    // 操作按钮
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = SpacingTokens.sm),
                        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm, Alignment.End),
                    ) {
                        OutlinedButton(
                            onClick = onSaveToNote,
                            shape = ShapeTokens.largeShape,
                        ) {
                            Icon(Icons.AutoMirrored.Filled.NoteAdd, contentDescription = stringResource(R.string.cd_save), modifier = Modifier.size(SpacingTokens.md))
                            Spacer(Modifier.width(SpacingTokens.xs))
                            Text(stringResource(R.string.editor_team_cun_wei_bi_ji), style = MaterialTheme.typography.labelMedium)
                        }
                        Button(
                            onClick = onCopy,
                            shape = ShapeTokens.largeShape,
                            colors = ButtonDefaults.buttonColors(containerColor = finalDraftColor),
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.cd_copy), modifier = Modifier.size(SpacingTokens.md))
                            Spacer(Modifier.width(SpacingTokens.xs))
                            Text(stringResource(R.string.editor_team_fu_zhi_quan_wen), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary)
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
        shape = ShapeTokens.mediumShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.customColors.errorBackground),
    ) {
        Row(modifier = Modifier.padding(SpacingTokens.md), verticalAlignment = Alignment.CenterVertically) {
            Text("!", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.customColors.dangerRed)
            Spacer(Modifier.width(SpacingTokens.sm))
            Text(content, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.customColors.dangerRed)
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
    val roleColor = role?.let { Color(it.color) } ?: MaterialTheme.colorScheme.outline

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = SizeTokens.quickActionIcon, top = SpacingTokens.xs),
    ) {
        // 跳动的三个点
        Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.xxs)) {
            repeat(3) { index ->
                Box(
                    modifier = Modifier
                        .size(SpacingTokens.xxs)
                        .background(roleColor, CircleShape)
                )
            }
        }
        Spacer(Modifier.width(SpacingTokens.sm))
        Text(
            text = stringResource(R.string.editor_team_1_zheng_zai_si_kao, alias),
            style = MaterialTheme.typography.bodySmall,
            color = themeTextGrey(),
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
        shape = RoundedCornerShape(topStart = ShapeTokens.extraLarge, topEnd = ShapeTokens.extraLarge),
        colors = CardDefaults.cardColors(containerColor = themeCardWhite()),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(SpacingTokens.sm)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = SizeTokens.quickActionIcon, max = 110.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(ShapeTokens.extraLarge))
                        .padding(horizontal = SpacingTokens.lg, vertical = SpacingTokens.sm),
                ) {
                    if (inputText.isEmpty()) {
                        Text(
                            text = stringResource(R.string.editor_team_ba_ni_de_xiang_fa_huo_cao_gao),
                            color = MaterialTheme.colorScheme.outline,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    BasicTextField(
                        value = inputText,
                        onValueChange = onInputChange,
                        enabled = !isProcessing,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = themeTextDark()),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.width(SpacingTokens.sm))
                if (isProcessing) {
                    OutlinedButton(
                        onClick = onStop,
                        shape = CircleShape,
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.customColors.dangerRed,
                        ),
                        modifier = Modifier.size(SizeTokens.quickActionIcon),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.cd_stop), modifier = Modifier.size(SizeTokens.iconMd))
                    }
                } else {
                    Button(
                        onClick = { onSend(inputText) },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.customColors.accentBlue),
                        enabled = isEnabled,
                        modifier = Modifier.size(SizeTokens.quickActionIcon),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.action_send), modifier = Modifier.size(SizeTokens.iconMd))
                    }
                }
            }
            Spacer(Modifier.height(SpacingTokens.xs))
            Text(
                text = if (isProcessing) stringResource(R.string.editor_team_dian_ji_hong_se_an_niu_ting_zhi_tao_lun) else stringResource(R.string.editor_team_enter_fa_song),
                style = MaterialTheme.typography.labelSmall,
                color = themeTextGrey().copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}
