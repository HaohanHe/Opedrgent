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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
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
import androidx.compose.ui.text.style.TextOverflow
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.automation.AutomationKind
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import top.hsyscn.opedrgent.model.MessageType
import top.hsyscn.opedrgent.model.Role
import top.hsyscn.opedrgent.stt.StreamingRecognitionState
import top.hsyscn.opedrgent.ui.state.AsrUiEvent
import top.hsyscn.opedrgent.ui.state.SttProgressState
import top.hsyscn.opedrgent.ui.state.SttUiState
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
import top.hsyscn.opedrgent.ui.components.isAtLeastMediumWidth
import top.hsyscn.opedrgent.ui.components.isExpandedWidth
import top.hsyscn.opedrgent.ui.theme.themeBarBg
import top.hsyscn.opedrgent.ui.theme.themeBgGray
import top.hsyscn.opedrgent.ui.theme.themeCardBackground
import top.hsyscn.opedrgent.ui.theme.themeCardWhite
import top.hsyscn.opedrgent.ui.theme.themeDividerColor
import top.hsyscn.opedrgent.ui.theme.themeSurfaceElevated
import top.hsyscn.opedrgent.ui.theme.themeTextDark
import top.hsyscn.opedrgent.ui.theme.themeTextGrey
import top.hsyscn.opedrgent.ui.theme.ElevationTokens
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
    // 录音状态来自 ViewModel，跨页面导航保持不中断（切到笔记/设置再切回，录音仍在继续）
    val listening by vm.asrListening.collectAsStateCompat()
    var actionSheetOpen by rememberSaveable { mutableStateOf(false) }
    var showScopeSheet by rememberSaveable { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var reachedTop by rememberSaveable(state.current?.id) { mutableStateOf(false) }
    var scrollAnchor by rememberSaveable(state.current?.id) { mutableStateOf<Pair<String, Int>?>(null) }
    var lastBottomMessageId by rememberSaveable(state.current?.id) { mutableStateOf<String?>(null) }
    var pendingDeleteMessageId by rememberSaveable { mutableStateOf<String?>(null) }

    val audioPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            scope.launch { snackbar.showSnackbar(context.getString(R.string.msg_audio_permission_required)) }
        }
    }

    // 统一 ASR 流式识别：job 由 ViewModel 持有，切页面不中断。这里仅收集识别结果事件更新输入框。
    LaunchedEffect(vm) {
        vm.asrEvent.collect { event ->
            when (event) {
                is AsrUiEvent.FinalText -> {
                    prompt = if (prompt.isBlank()) event.text else (prompt.trimEnd() + "\n" + event.text)
                }
                is AsrUiEvent.EmptyResult -> {
                    snackbar.showSnackbar(context.getString(R.string.msg_asr_no_speech))
                }
                is AsrUiEvent.Error -> {
                    snackbar.showSnackbar(event.message)
                }
            }
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
                            vm.sendUserMessageWithImage(context.getString(R.string.msg_analyze_image), uri)
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

        val contentMaxWidth = when {
            isExpandedWidth() -> SizeTokens.sessionContentMaxWidthExpanded
            isAtLeastMediumWidth() -> SizeTokens.sessionContentMaxWidthMedium
            else -> Dp.Unspecified
        }
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .consumeWindowInsets(WindowInsets.ime)
                .then(
                    if (contentMaxWidth != Dp.Unspecified) {
                        Modifier.widthIn(max = contentMaxWidth)
                    } else {
                        Modifier
                    }
                ),
        ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SpacingTokens.lg, vertical = SpacingTokens.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.closeSession(); onBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back), tint = themeTextDark())
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
                    .shadow(ElevationTokens.md, CircleShape)
                    .clip(CircleShape)
                    .size(SizeTokens.quickActionIcon),
                onClick = { actionSheetOpen = true },
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(Icons.Default.MoreHoriz, contentDescription = stringResource(R.string.cd_more), tint = themeTextDark(), modifier = Modifier.size(SizeTokens.iconSm))
                }
            }
            Spacer(modifier = Modifier.width(SpacingTokens.sm))
            Card(
                shape = CircleShape,
                colors = CardDefaults.cardColors(containerColor = themeBarBg()),
                modifier = Modifier
                    .shadow(ElevationTokens.md, CircleShape)
                    .clip(CircleShape)
                    .size(SizeTokens.quickActionIcon),
                onClick = onOpenSettings,
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.cd_settings), tint = themeTextDark(), modifier = Modifier.size(SizeTokens.iconSm))
                }
            }
        }

        if (session == null) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.session_select_prompt), color = themeTextGrey())
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
                                onUndo = { pendingDeleteMessageId = msg.id },
                            )
                            Role.ASSISTANT -> AIMessageCard(
                                message = msg,
                                onSpeak = { vm.toggleSpeak(msg.textContent) },
                                isSpeaking = state.isSpeaking,
                                clipboard = clipboard,
                                onUndo = { pendingDeleteMessageId = msg.id },
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
                                    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.clipboard_label_sprout_result), sproutResult ?: ""))
                                    scope.launch { snackbar.showSnackbar(context.getString(R.string.msg_copied_to_clipboard)) }
                                },
                                onContinueChat = {
                                    // 可以添加继续对话的逻辑，比如询问关于发芽结果的问题
                                },
                                onDismiss = {
                                    vm.dismissSproutResult()
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
                        .padding(horizontal = SpacingTokens.xxl, vertical = SpacingTokens.xs)
                        .fillMaxWidth(),
                    shape = ShapeTokens.smallShape,
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true),
                ) {
                    Box(
                        modifier = Modifier
                            .size(SizeTokens.iconSm)
                            .background(MaterialTheme.colorScheme.primary, ShapeTokens.extraSmallShape),
                    )
                    Spacer(modifier = Modifier.width(SpacingTokens.sm))
                    Text(
                        stringResource(R.string.session_stop_reply),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // Input bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                    .padding(start = SpacingTokens.md, end = SpacingTokens.md, top = SpacingTokens.xs),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
            ) {
                // Scope selector
                Surface(
                    onClick = { showScopeSheet = true },
                    shape = ShapeTokens.mediumShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                    modifier = Modifier.height(SizeTokens.searchBarHeight),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = SpacingTokens.sm),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = stringResource(R.string.cd_search_scope),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(SizeTokens.iconXs),
                        )
                        Spacer(modifier = Modifier.width(SpacingTokens.xs))
                        Text(
                            text = stringResource(state.searchScope.labelRes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                // "+" button for more options
                Card(
                    shape = CircleShape,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .size(SizeTokens.quickActionIcon)
                        .clip(CircleShape),
                    onClick = { showMoreOptionsSheet = true },
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = stringResource(R.string.cd_more),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(SizeTokens.iconSm),
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
                                .heightIn(min = SizeTokens.searchBarHeight, max = 120.dp),
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
                            Icon(Icons.Default.CameraAlt, contentDescription = stringResource(R.string.cd_attach_file), tint = themeTextGrey(), modifier = Modifier.size(SizeTokens.iconSm))
                        }
                        // Microphone button
                        if (vm.isSttEnabled()) {
                            IconButton(onClick = {
                                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                                if (!granted) {
                                    audioPerm.launch(Manifest.permission.RECORD_AUDIO)
                                    return@IconButton
                                }
                                // 录音 job 由 ViewModel 持有，跨页面保持；这里仅切换开关。
                                vm.toggleStreamingAsr()
                            }) {
                                Icon(
                                    Icons.Default.Mic,
                                    contentDescription = stringResource(R.string.cd_voice_input),
                                    tint = if (listening) MaterialTheme.colorScheme.primary else themeTextGrey(),
                                    modifier = Modifier.size(SizeTokens.iconSm),
                                )
                            }
                        }
                    } // closes Row

                        // 快捷指令补全：输入 / 时显示命令列表
                        val slashMatches = remember(prompt) {
                            top.hsyscn.opedrgent.utils.SlashCommands.filterByPrefix(prompt.trim())
                        }
                        val slashDropdownWidth = if (isExpandedWidth()) 360.dp else 280.dp
                        DropdownMenu(
                            expanded = slashMatches.isNotEmpty(),
                            onDismissRequest = { /* 输入变化时自动更新 */ },
                            modifier = Modifier.width(slashDropdownWidth),
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
                        .size(SizeTokens.quickActionIcon)
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
                                modifier = Modifier.size(SizeTokens.iconSm),
                                strokeWidth = SizeTokens.iconXs,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = stringResource(R.string.cd_send),
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(SizeTokens.iconSm),
                            )
                        }
                    }
                }
            }
        }

        // 删除消息二次确认
        if (pendingDeleteMessageId != null) {
            AlertDialog(
                onDismissRequest = { pendingDeleteMessageId = null },
                title = { Text(stringResource(R.string.session_delete_message_title)) },
                text = { Text(stringResource(R.string.session_delete_message_confirm)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val id = pendingDeleteMessageId
                            pendingDeleteMessageId = null
                            if (id != null) {
                                vm.deleteMessage(id)
                                scope.launch {
                                    snackbar.showSnackbar(context.getString(R.string.session_delete_message_toast))
                                }
                            }
                        },
                    ) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDeleteMessageId = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
            )
        }

        // 高危工具操作确认
        val toolConfirmation by vm.pendingToolConfirmation.collectAsStateCompat()
        toolConfirmation?.let { confirmation ->
            AlertDialog(
                onDismissRequest = { vm.resolveToolConfirmation(false) },
                title = { Text(stringResource(R.string.tool_confirmation_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
                        Text(confirmation.action, style = MaterialTheme.typography.titleSmall)
                        Text(confirmation.detail)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { vm.resolveToolConfirmation(true) }) {
                        Text(stringResource(R.string.tool_confirmation_allow))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { vm.resolveToolConfirmation(false) }) {
                        Text(stringResource(R.string.tool_confirmation_deny))
                    }
                },
            )
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
                            text = stringResource(R.string.session_more_ways),
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
                                    contentDescription = stringResource(R.string.cd_photo_or_image),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(SizeTokens.iconXl),
                                )
                                Spacer(modifier = Modifier.width(SpacingTokens.md))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.session_photo_image),
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        text = stringResource(R.string.session_photo_image_desc),
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
                                    contentDescription = stringResource(R.string.cd_knowledge_base),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(SizeTokens.iconXl),
                                )
                                Spacer(modifier = Modifier.width(SpacingTokens.md))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.cd_knowledge_base),
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        text = stringResource(R.string.session_kb_desc),
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
                                        text = stringResource(R.string.session_ai_editor_team),
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        text = stringResource(R.string.session_ai_editor_team_desc),
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
                        text = stringResource(R.string.session_select_scope),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = SpacingTokens.lg),
                    )
                    val scopeDescAll = stringResource(R.string.scope_desc_all)
                    val scopeDescNotes = stringResource(R.string.scope_desc_notes)
                    val scopeDescWeb = stringResource(R.string.scope_desc_web)
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
                                    text = stringResource(scopeEntry.labelRes),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                val description = when (scopeEntry) {
                                    SearchScope.ALL -> scopeDescAll
                                    SearchScope.MY_NOTES -> scopeDescNotes
                                    SearchScope.WEB_ONLY -> scopeDescWeb
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
            val decodingText = stringResource(R.string.stt_decoding)
            val recognizingText = stringResource(R.string.stt_recognizing)
            val phaseText = when (sttUiState) {
                is SttUiState.DecodingAudio -> decodingText
                is SttUiState.Recognizing -> {
                    val r = sttUiState as SttUiState.Recognizing
                    if (r.totalSegments > 0) context.getString(R.string.stt_recognizing_progress, r.currentSegment, r.totalSegments)
                    else recognizingText
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
                    text = stringResource(R.string.session_actions),
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
                    ) { Text(stringResource(R.string.session_export_chat)) }
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
                    ) { Text(stringResource(R.string.session_export_context)) }
                }

                Button(
                    onClick = {
                        actionSheetOpen = false
                        onOpenSubScreen("export")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = ShapeTokens.mediumShape,
                    colors = ButtonDefaults.buttonColors(containerColor = themeCardBackground(), contentColor = themeTextDark()),
                ) { Text(stringResource(R.string.session_export_center)) }

                Spacer(modifier = Modifier.height(SpacingTokens.md))
            }
        }
    }

    // 自动化建议对话框
    state.automationSuggestion?.let { suggestion ->
        val typeHeartbeat = stringResource(R.string.automation_type_heartbeat)
        val typePrompt = stringResource(R.string.automation_type_prompt)
        AlertDialog(
            onDismissRequest = { vm.dismissAutomationSuggestion() },
            title = { Text(stringResource(R.string.automation_suggestion_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
                    Text(stringResource(R.string.automation_suggestion_desc))
                    Text(stringResource(R.string.automation_label_name, suggestion.name), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.automation_label_interval, suggestion.intervalMinutes))
                    Text(stringResource(R.string.automation_label_type, if (suggestion.kind == AutomationKind.HEARTBEAT_NOTES) typeHeartbeat else typePrompt))
                    if (suggestion.kind == AutomationKind.RUN_PROMPT && !suggestion.prompt.isNullOrBlank()) {
                        Text("Prompt:", style = MaterialTheme.typography.titleSmall)
                        Text(
                            suggestion.prompt,
                            style = MaterialTheme.typography.bodySmall,
                            color = themeTextGrey(),
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { vm.acceptAutomationSuggestion() }) { Text(stringResource(R.string.action_accept)) }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissAutomationSuggestion() }) { Text(stringResource(R.string.action_ignore)) }
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


