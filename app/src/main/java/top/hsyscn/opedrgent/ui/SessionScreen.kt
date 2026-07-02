@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package top.hsyscn.opedrgent.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.automation.AutomationKind
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import top.hsyscn.opedrgent.model.MessageType
import top.hsyscn.opedrgent.model.Role
import top.hsyscn.opedrgent.stt.StreamingRecognitionState
import top.hsyscn.opedrgent.ui.SttProgressState
import top.hsyscn.opedrgent.ui.SttUiState
import top.hsyscn.opedrgent.ui.components.AIMessageCard
import top.hsyscn.opedrgent.ui.components.ConfirmationDialog


import top.hsyscn.opedrgent.ui.components.MessageBodyConfigUpdate
import top.hsyscn.opedrgent.ui.components.MessageBodyError
import top.hsyscn.opedrgent.ui.components.MessageBodyInfo
import top.hsyscn.opedrgent.ui.components.QuestionCard
import top.hsyscn.opedrgent.ui.components.QuestionDock
import top.hsyscn.opedrgent.ui.components.SearchScope
import top.hsyscn.opedrgent.ui.components.SproutResultView
import top.hsyscn.opedrgent.ui.components.SttProgressDialog
import top.hsyscn.opedrgent.ui.components.SttResultCard
import top.hsyscn.opedrgent.ui.components.StreamingCard
import top.hsyscn.opedrgent.ui.components.UserBubble
import top.hsyscn.opedrgent.ui.theme.themeBarBg
import top.hsyscn.opedrgent.ui.theme.themeBgGray
import top.hsyscn.opedrgent.ui.theme.themeCardBackground
import top.hsyscn.opedrgent.ui.theme.themeCardWhite
import top.hsyscn.opedrgent.ui.theme.themeDividerColor
import top.hsyscn.opedrgent.ui.theme.themeSurfaceElevated
import top.hsyscn.opedrgent.ui.theme.themeTextDark
import top.hsyscn.opedrgent.ui.theme.themeTextGrey
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import top.hsyscn.opedrgent.ui.theme.SizeTokens
import top.hsyscn.opedrgent.ui.theme.SpacingTokens

@Composable
fun SessionScreen(
    vm: MainViewModel,
    sessionId: String?,
    onOpenSettings: () -> Unit,
    onOpenSubScreen: (String) -> Unit,
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val state by vm.state.collectAsStateCompat()

    var prompt by rememberSaveable { mutableStateOf("") }
    var listening by rememberSaveable { mutableStateOf(false) }
    var actionSheetOpen by rememberSaveable { mutableStateOf(false) }
    var showScopeSheet by rememberSaveable { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var reachedTop by remember(state.current?.id) { mutableStateOf(false) }
    var scrollAnchor by remember(state.current?.id) { mutableStateOf<Pair<String, Int>?>(null) }
    var lastBottomMessageId by remember(state.current?.id) { mutableStateOf<String?>(null) }

    val audioPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            scope.launch { snackbar.showSnackbar(context.getString(R.string.msg_audio_permission_required)) }
        }
    }

    // 统一 ASR 流式识别：使用 AsrManager 根据用户设置选择引擎
    var streamingJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            streamingJob?.cancel()
            vm.stopUnifiedStreamingAsr()
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val mimeType = context.contentResolver.getType(uri)
            scope.launch {
                when {
                    mimeType?.startsWith("image/") == true -> {
                        // 图片附加到消息让 AI 分析 (通过多模态 API 实际发送图片)
                        val session = vm.state.value.current
                        if (session != null) {
                            vm.sendUserMessageWithImage("请分析这张图片的内容", uri)
                        } else {
                            snackbar.showSnackbar(context.getString(R.string.msg_create_session_first))
                        }
                    }
                    mimeType?.startsWith("audio/") == true -> {
                        // 音频走 ASR 识别
                        vm.startSpeechToText(uri)
                    }
                    mimeType?.startsWith("video/") == true -> {
                        // 视频优先走视频摘要（利用多模态视觉理解），同时提供语音转文字选项
                        vm.sendVideoForSummary(uri)
                    }
                    mimeType == "application/pdf" -> vm.importPdfOcr(uri)
                    mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> vm.importDocx(uri)
                    else -> vm.importFile(uri)
                }
            }
        }
    }

    // Audio file picker for STT
    val audioFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: Exception) {}
            vm.startSpeechToText(uri)
        }
    }

    LaunchedEffect(sessionId) {
        if (sessionId != null) vm.openSession(sessionId)
    }

    LaunchedEffect(state.error) {
        val e = state.error
        if (!e.isNullOrBlank()) {
            snackbar.showSnackbar(e)
            vm.clearError()
        }
    }

    val questionRequest by vm.questionRequest.collectAsState()
    val confirmationRequest by vm.confirmationRequest.collectAsState()

    val session = state.current

    Box(modifier = Modifier.fillMaxSize().background(themeBgGray())) {
        var showMoreOptionsSheet by rememberSaveable { mutableStateOf(false) }

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight().widthIn(max = 720.dp)) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SpacingTokens.lg, vertical = SpacingTokens.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.closeSession(); onBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = themeTextDark())
            }
            Text(
                text = session?.title ?: "Opedrgent",
                style = MaterialTheme.typography.titleMedium,
                color = themeTextDark(),
                modifier = Modifier.weight(1f),
            )
            Card(
                shape = CircleShape,
                colors = CardDefaults.cardColors(containerColor = themeBarBg()),
                modifier = Modifier
                    .shadow(4.dp, CircleShape)
                    .clip(CircleShape)
                    .size(SpacingTokens.xxl),
                onClick = { actionSheetOpen = true },
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(Icons.Default.MoreHoriz, contentDescription = "更多选项", tint = themeTextDark(), modifier = Modifier.size(SpacingTokens.md))
                }
            }
            Spacer(modifier = Modifier.width(SpacingTokens.sm))
            Card(
                shape = CircleShape,
                colors = CardDefaults.cardColors(containerColor = themeBarBg()),
                modifier = Modifier
                    .shadow(4.dp, CircleShape)
                    .clip(CircleShape)
                    .size(SpacingTokens.xxl),
                onClick = onOpenSettings,
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(Icons.Default.Settings, contentDescription = "设置", tint = themeTextDark(), modifier = Modifier.size(SpacingTokens.md))
                }
            }
        }

        if (session == null) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("选择一个会话开始对话", color = themeTextGrey())
            }
        } else {
            // 发芽结果视图 - 自然显示在聊天中
            val sproutResult by vm.sproutResult.collectAsStateCompat()
            val sproutingState by vm.sproutingState.collectAsStateCompat()

            val totalListItems = 1 + session.messages.size +
                (if (state.isStreaming && state.streamingSessionId == session.id) 1 else 0) +
                (if (state.activeQuestion != null) 1 else 0) +
                (if (sproutResult != null || sproutingState != SproutingState.IDLE) 1 else 0)

            LaunchedEffect(totalListItems, state.streamingText.length) {
                val currentLastMessageId = session.messages.lastOrNull()?.id
                val shouldScroll = (state.isStreaming && state.streamingSessionId == session.id) ||
                    currentLastMessageId != lastBottomMessageId
                if (shouldScroll && totalListItems > 0) {
                    lastBottomMessageId = currentLastMessageId
                    kotlinx.coroutines.delay(100)
                    listState.animateScrollToItem(totalListItems - 1)
                }
            }

            // 到达顶部自动加载更早消息
            LaunchedEffect(session.id, listState) {
                snapshotFlow { listState.firstVisibleItemIndex }
                    .distinctUntilChanged()
                    .collect { index ->
                        if (index == 0) {
                            if (!reachedTop && vm.state.value.hasMoreOlderRounds && !vm.state.value.isLoadingOlderRounds) {
                                reachedTop = true
                                vm.loadMoreRounds(session.id)
                            }
                        } else {
                            reachedTop = false
                        }
                    }
            }

            // 加载旧消息后恢复滚动锚点，避免列表跳动
            LaunchedEffect(state.isLoadingOlderRounds, session.id) {
                if (state.isLoadingOlderRounds) {
                    val first = listState.layoutInfo.visibleItemsInfo.firstOrNull()
                    if (first != null && first.key != "_loading_header") {
                        scrollAnchor = (first.key as String) to listState.firstVisibleItemScrollOffset
                    }
                } else {
                    val anchor = scrollAnchor
                    val messages = session.messages
                    if (anchor != null) {
                        val newIndex = 1 + messages.indexOfFirst { it.id == anchor.first }
                        if (newIndex > 0) {
                            listState.scrollToItem(newIndex, anchor.second)
                        }
                    }
                    scrollAnchor = null
                }
            }

            // Messages
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = SpacingTokens.md),
                verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
            ) {
                item(key = "_loading_header") {
                    if (state.isLoadingOlderRounds || !state.hasMoreOlderRounds) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = SpacingTokens.md),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (state.isLoadingOlderRounds) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(SizeTokens.iconMd),
                                    strokeWidth = 2.dp,
                                )
                            } else if (!state.hasMoreOlderRounds) {
                                Text(
                                    text = stringResource(R.string.chat_history_all_loaded),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = themeTextGrey(),
                                )
                            }
                        }
                    }
                }

                items(session.messages, key = { it.id }) { msg ->
                    Column(modifier = Modifier.animateItem()) {
                        when (msg.role) {
                            Role.USER -> UserBubble(
                                text = msg.textContent,
                                clipboard = clipboard,
                                onUndo = { vm.deleteMessage(msg.id) },
                            )
                            Role.ASSISTANT -> AIMessageCard(
                                message = msg,
                                onSpeak = { vm.toggleSpeak(msg.textContent) },
                                isSpeaking = state.isSpeaking,
                                clipboard = clipboard,
                                onUndo = { vm.deleteMessage(msg.id) },
                            )
                            Role.SYSTEM -> {
                                when (msg.messageType) {
                                    MessageType.INFO -> MessageBodyInfo(message = msg.textContent)
                                    MessageType.CONFIG_UPDATE -> {
                                        val cfgParts = msg.textContent.split("|")
                                        if (cfgParts.size == 3) {
                                            MessageBodyConfigUpdate(
                                                configName = cfgParts[0],
                                                oldValue = cfgParts[1],
                                                newValue = cfgParts[2],
                                            )
                                        }
                                    }
                                    MessageType.ERROR -> MessageBodyError(
                                        errorText = msg.textContent,
                                        snackbarHostState = snackbar,
                                    )
                                    MessageType.AUDIO -> {}
                                    MessageType.TEXT -> {}
                                }
                            }
                        }
                    }
                }

                if (state.isStreaming && state.streamingSessionId == session.id) {
                    item(key = "_streaming") {
                        Column(modifier = Modifier.animateItem()) {
                            StreamingCard(
                                text = state.streamingText,
                                reasoning = state.streamingReasoning,
                                toolParts = state.streamingToolParts,
                                phase = state.streamingPhase,
                            )
                        }
                    }
                }

                if (state.activeQuestion != null) {
                    item(key = "_question") {
                        Column(modifier = Modifier.animateItem()) {
                            QuestionCard(
                                question = state.activeQuestion!!,
                                onAnswer = { vm.answerQuestion(it) },
                                onDismiss = { vm.dismissQuestion() },
                            )
                        }
                    }
                }

                // 发芽结果视图 - 自然显示在聊天中
                if (sproutResult != null || sproutingState != SproutingState.IDLE) {
                    item(key = "_sprout") {
                        Column(modifier = Modifier.animateItem()) {
                            top.hsyscn.opedrgent.ui.components.SproutResultView(
                                markdownReport = sproutResult ?: "",
                                sproutingState = sproutingState,
                                onCopy = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("发芽结果", sproutResult ?: ""))
                                    scope.launch { snackbar.showSnackbar(context.getString(R.string.msg_copied_to_clipboard)) }
                                },
                                onContinueChat = {
                                    // 可以添加继续对话的逻辑，比如询问关于发芽结果的问题
                                },
                                onDismiss = {
                                    vm._sproutResult.value = null
                                    vm._sproutingState.value = SproutingState.IDLE
                                },
                            )
                        }
                    }
                }
            }

            // Stop responding
            AnimatedVisibility(visible = state.isStreaming) {
                OutlinedButton(
                    onClick = { vm.stopGeneration() },
                    modifier = Modifier
                        .padding(horizontal = 66.dp, vertical = SpacingTokens.xs)
                        .fillMaxWidth(),
                    shape = ShapeTokens.smallShape,
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true),
                ) {
                    Box(
                        modifier = Modifier
                            .size(SpacingTokens.md)
                            .background(MaterialTheme.colorScheme.primary, ShapeTokens.extraSmallShape),
                    )
                    Spacer(modifier = Modifier.width(SpacingTokens.sm))
                    Text("停止回复", style = MaterialTheme.typography.labelLarge.copy(color = MaterialTheme.colorScheme.primary))
                }
            }

            // Input bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = SpacingTokens.md, end = SpacingTokens.md, bottom = SpacingTokens.md, top = SpacingTokens.xs),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
            ) {
                // Scope selector
                Surface(
                    onClick = { showScopeSheet = true },
                    shape = ShapeTokens.mediumShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                    modifier = Modifier.height(37.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = SpacingTokens.sm),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "搜索范围",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(SpacingTokens.sm),
                        )
                        Spacer(modifier = Modifier.width(SpacingTokens.xs))
                        Text(
                            text = state.searchScope.label,
                            style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.primary),
                        )
                    }
                }

                // "+" button for more options
                Card(
                    shape = CircleShape,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .size(37.dp)
                        .clip(CircleShape),
                    onClick = { showMoreOptionsSheet = true },
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "更多选项",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(SpacingTokens.md),
                        )
                    }
                }

                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clip(ShapeTokens.largeShape),
                    shape = ShapeTokens.largeShape,
                    colors = CardDefaults.cardColors(containerColor = themeSurfaceElevated()),
                ) {
                    Box {
                        Row(
                            modifier = Modifier.padding(horizontal = SpacingTokens.md, vertical = SpacingTokens.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                        OutlinedTextField(
                            value = prompt,
                            onValueChange = { prompt = it },
                            placeholder = { Text(stringResource(R.string.msg_type_message), color = themeTextGrey()) },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 37.dp, max = 120.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                                unfocusedBorderColor = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                                focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                            ),
                            maxLines = 5,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                            keyboardActions = KeyboardActions(onSend = {
                                if (!state.isStreaming && prompt.isNotBlank()) {
                                    val text = prompt
                                    prompt = ""
                                    vm.sendUserMessage(text)
                                }
                            }),
                        )
                        // Camera/Files button
                        IconButton(onClick = { filePicker.launch(arrayOf("image/*", "audio/*", "video/*", "application/pdf")) }) {
                            Icon(Icons.Default.CameraAlt, contentDescription = "附加文件", tint = themeTextGrey(), modifier = Modifier.size(SpacingTokens.md))
                        }
                        // Microphone button
                        if (vm.isSttEnabled()) {
                            IconButton(onClick = {
                                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                                if (!granted) {
                                    audioPerm.launch(Manifest.permission.RECORD_AUDIO)
                                    return@IconButton
                                }
                                if (!listening) {
                                    listening = true
                                    streamingJob = scope.launch {
                                        try {
                                            val flow = vm.startUnifiedStreamingAsr()
                                            flow.collect { state ->
                                                when (state) {
                                                    is StreamingRecognitionState.FinalResult -> {
                                                        if (state.text.isNotBlank()) {
                                                            prompt = if (prompt.isBlank()) state.text else (prompt.trimEnd() + "\n" + state.text)
                                                        } else {
                                                            snackbar.showSnackbar("未识别到语音")
                                                        }
                                                        listening = false
                                                    }
                                                    is StreamingRecognitionState.Error -> {
                                                        snackbar.showSnackbar(state.message)
                                                        listening = false
                                                    }
                                                    else -> {}
                                                }
                                            }
                                        } catch (e: Exception) {
                                            snackbar.showSnackbar("语音识别失败: ${e.message}")
                                            listening = false
                                        }
                                    }
                                } else {
                                    listening = false
                                    streamingJob?.cancel()
                                    vm.stopUnifiedStreamingAsr()
                                }
                            }) {
                                Icon(
                                    Icons.Default.Mic,
                                    contentDescription = "语音输入",
                                    tint = if (listening) MaterialTheme.colorScheme.primary else themeTextGrey(),
                                    modifier = Modifier.size(SpacingTokens.md),
                                )
                            }
                        }
                    } // closes Row

                        // 快捷指令补全：输入 / 时显示命令列表
                        val slashMatches = remember(prompt) {
                            top.hsyscn.opedrgent.utils.SlashCommands.filterByPrefix(prompt.trim())
                        }
                        DropdownMenu(
                            expanded = slashMatches.isNotEmpty(),
                            onDismissRequest = { /* 输入变化时自动更新 */ },
                            modifier = Modifier.width(280.dp),
                        ) {
                            slashMatches.take(6).forEach { cmd ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                cmd.usage,
                                                style = MaterialTheme.typography.titleSmall,
                                            )
                                            Text(
                                                cmd.description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = themeTextGrey(),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    },
                                    onClick = {
                                        prompt = "/${cmd.name} "
                                    },
                                )
                            }
                        }
                    } // closes Box
                } // closes Card

                // Send button
                Card(
                    shape = CircleShape,
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            state.isStreaming -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)  // 流式中：禁用态
                            prompt.isNotBlank() -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.outline
                        },
                    ),
                    modifier = Modifier
                        .size(37.dp)
                        .clip(CircleShape),
                    onClick = {
                        if (!state.isStreaming && prompt.isNotBlank()) {
                            val text = prompt
                            prompt = ""
                            vm.sendUserMessage(text)
                        }
                    },
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        if (state.isStreaming) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(SpacingTokens.md),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "发送",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(SpacingTokens.md),
                            )
                        }
                    }
                }
            }
        }

        // More options bottom sheet (at Box level, as overlay)
        } // inner centered Box
        if (showMoreOptionsSheet) {
                ModalBottomSheet(
                    onDismissRequest = { showMoreOptionsSheet = false },
                    sheetState = rememberModalBottomSheetState(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(SpacingTokens.lg),
                    ) {
                        Text(
                            text = "更多方式",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = SpacingTokens.lg),
                        )

                        // Photo/Image
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = SpacingTokens.xs)
                                .clickable(role = SemanticsRole.Button, onClickLabel = stringResource(R.string.action_select)) {
                                    showMoreOptionsSheet = false
                                    filePicker.launch(arrayOf("image/*"))
                                },
                            colors = CardDefaults.cardColors(containerColor = themeCardBackground()),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(SpacingTokens.lg),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.CameraAlt,
                                    contentDescription = "拍照或图片",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(SpacingTokens.xl),
                                )
                                Spacer(modifier = Modifier.width(SpacingTokens.md))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "拍照/图片",
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        text = "白板记录/课堂笔记/日常饮食，秒变笔记",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = themeTextGrey(),
                                    )
                                }
                            }
                        }

                        // Knowledge base
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = SpacingTokens.xs)
                                .clickable(role = SemanticsRole.Button, onClickLabel = stringResource(R.string.cd_enter)) {
                                    showMoreOptionsSheet = false
                                    onOpenSubScreen("knowledge")
                                },
                            colors = CardDefaults.cardColors(containerColor = themeCardBackground()),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(SpacingTokens.lg),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.Save,
                                    contentDescription = "知识库",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(SpacingTokens.xl),
                                )
                                Spacer(modifier = Modifier.width(SpacingTokens.md))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "知识库",
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        text = "导入文档/PDF/图片，AI 可检索引用",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = themeTextGrey(),
                                    )
                                }
                            }
                        }

                        // AI Editor Team
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = SpacingTokens.xs)
                                .clickable(role = SemanticsRole.Button, onClickLabel = stringResource(R.string.cd_enter)) {
                                    showMoreOptionsSheet = false
                                    onOpenSubScreen("editorTeam")
                                },
                            colors = CardDefaults.cardColors(containerColor = themeCardBackground()),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(SpacingTokens.lg),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "\uD83E\uDDD1\u200D\uD83C\uDFA8",
                                    style = MaterialTheme.typography.headlineSmall,
                                    modifier = Modifier.padding(end = SpacingTokens.xs),
                                )
                                Spacer(Modifier.width(SpacingTokens.sm))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "AI 编辑团",
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        text = "AI 动态规划编辑团队",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = themeTextGrey(),
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(SpacingTokens.lg))
                    }
                }
            }

        // Scope selector bottom sheet
        if (showScopeSheet) {
            ModalBottomSheet(
                onDismissRequest = { showScopeSheet = false },
                sheetState = rememberModalBottomSheetState(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(SpacingTokens.lg),
                ) {
                    Text(
                        text = "选择数据源范围",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = SpacingTokens.lg),
                    )
                    SearchScope.entries.forEach { scopeEntry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(role = SemanticsRole.Button, onClickLabel = stringResource(R.string.action_select)) {
                                    vm.setSearchScope(scopeEntry)
                                    showScopeSheet = false
                                }
                                .padding(vertical = SpacingTokens.md),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = scopeEntry == state.searchScope,
                                onClick = null,
                            )
                            Spacer(modifier = Modifier.width(SpacingTokens.md))
                            Column {
                                Text(
                                    text = scopeEntry.label,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                val description = when (scopeEntry) {
                                    SearchScope.ALL -> "笔记、知识库和全网搜索"
                                    SearchScope.MY_NOTES -> "仅搜索我的笔记和知识库"
                                    SearchScope.WEB_ONLY -> "仅使用全网搜索引擎"
                                }
                                Text(
                                    text = description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = themeTextGrey(),
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(SpacingTokens.lg))
                }
            }
        }
        }

        // 选择题浮层：锚定在输入框上方，避免遮挡
        questionRequest?.let { request ->
            QuestionDock(
                request = request,
                onAnswer = { answers -> vm.respondToQuestion(answers) },
                onDismiss = { vm.respondToQuestion(emptyList()) },
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = SpacingTokens.lg)
                    .padding(bottom = 80.dp),
            )
        }

        confirmationRequest?.let { request ->
            ConfirmationDialog(
                request = request,
                onConfirm = { selectedOption -> vm.respondToConfirmation(selectedOption) },
                onDismiss = { vm.respondToConfirmation(null) },
                onTimeout = { vm.respondToConfirmation(null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = SpacingTokens.lg, vertical = SpacingTokens.sm),
            )
        }

        // STT Progress Dialog
        val sttProgress by vm.sttProgress.collectAsState()
        val sttUiState by vm.sttUiState.collectAsState()
        val sttResult by vm.sttResult.collectAsState()
        val sttError by vm.sttError.collectAsState()

        LaunchedEffect(sttError) {
            val e = sttError
            if (!e.isNullOrBlank()) {
                snackbar.showSnackbar(e)
            }
        }

        if (sttProgress != SttProgressState.IDLE && sttProgress != SttProgressState.DONE) {
            val downloadProg = (sttUiState as? SttUiState.DownloadingModel)?.progress
            val phaseText = when (sttUiState) {
                is SttUiState.DecodingAudio -> "正在解码音频..."
                is SttUiState.Recognizing -> {
                    val r = sttUiState as SttUiState.Recognizing
                    if (r.totalSegments > 0) "正在识别语音... ${r.currentSegment}/${r.totalSegments}"
                    else "正在识别语音..."
                }
                else -> null
            }
            SttProgressDialog(
                progressState = sttProgress,
                downloadProgress = downloadProg,
                currentPhase = phaseText,
                onCancel = { vm.cancelStt() },
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center),
            )
        }

        // STT Result Card — floating above input bar
        sttResult?.let { result ->
            SttResultCard(
                result = result,
                error = null,
                onCopy = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(result.text))
                },
                onSendToLlm = { vm.sendSttResultToLlm() },
                onDismiss = { vm.clearSttResult() },
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = SpacingTokens.md, vertical = 110.dp),
            )
        }
    }

    if (actionSheetOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { actionSheetOpen = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.padding(SpacingTokens.md), verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
                Text(
                    text = "会话操作",
                    style = MaterialTheme.typography.titleMedium,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            scope.launch {
                                val f = vm.exportMarkdown()
                                if (f != null) shareFile(context, vm.getPackageNameForShare(context), f) else snackbar.showSnackbar(context.getString(R.string.msg_export_failed))
                            }
                            actionSheetOpen = false
                        },
                        modifier = Modifier.weight(1f),
                        shape = ShapeTokens.mediumShape,
                    ) { Text("导出会话") }
                    Button(
                        onClick = {
                            scope.launch {
                                val f = vm.exportContextZip()
                                if (f != null) shareFile(context, vm.getPackageNameForShare(context), f) else snackbar.showSnackbar(context.getString(R.string.msg_export_failed))
                            }
                            actionSheetOpen = false
                        },
                        modifier = Modifier.weight(1f),
                        shape = ShapeTokens.mediumShape,
                    ) { Text("导出上下文") }
                }

                Button(
                    onClick = {
                        actionSheetOpen = false
                        onOpenSubScreen("export")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = ShapeTokens.mediumShape,
                    colors = ButtonDefaults.buttonColors(containerColor = themeCardBackground(), contentColor = themeTextDark()),
                ) { Text("完整导出中心") }

                Spacer(modifier = Modifier.height(SpacingTokens.md))
            }
        }
    }

    // 自动化建议对话框
    state.automationSuggestion?.let { suggestion ->
        AlertDialog(
            onDismissRequest = { vm.dismissAutomationSuggestion() },
            title = { Text("自动化建议") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
                    Text("AI 基于当前会话建议了一个定时任务：")
                    Text("名称: ${suggestion.name}", fontWeight = FontWeight.SemiBold)
                    Text("周期: 每 ${suggestion.intervalMinutes} 分钟")
                    Text("类型: ${if (suggestion.kind == AutomationKind.HEARTBEAT_NOTES) "心跳整理" else "定时 Prompt"}")
                    if (suggestion.kind == AutomationKind.RUN_PROMPT && !suggestion.prompt.isNullOrBlank()) {
                        Text("Prompt:", style = MaterialTheme.typography.titleSmall)
                        Text(suggestion.prompt, style = MaterialTheme.typography.bodySmall.copy(color = themeTextGrey()))
                    }
                }
            },
            confirmButton = {
                Button(onClick = { vm.acceptAutomationSuggestion() }) { Text("接受") }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissAutomationSuggestion() }) { Text("忽略") }
            },
        )
    }
}

/** 验证字符串是否为有效 URL */
private fun isValidUrl(text: String): Boolean {
    val trimmed = text.trim()
    return trimmed.startsWith("http://") || trimmed.startsWith("https://") ||
            trimmed.startsWith("www.") || trimmed.matches(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.*"))
}


