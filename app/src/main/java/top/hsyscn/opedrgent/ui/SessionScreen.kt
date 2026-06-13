@file:OptIn(ExperimentalMaterial3Api::class)

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import top.hsyscn.opedrgent.model.MessageType
import top.hsyscn.opedrgent.model.Role
import top.hsyscn.opedrgent.stt.StreamingRecognitionState
import top.hsyscn.opedrgent.ui.SttProgressState
import top.hsyscn.opedrgent.ui.SttUiState
import top.hsyscn.opedrgent.ui.theme.AccentBlue
import top.hsyscn.opedrgent.ui.theme.BarBg
import top.hsyscn.opedrgent.ui.theme.BgGray
import top.hsyscn.opedrgent.ui.theme.CardWhite
import top.hsyscn.opedrgent.ui.theme.GreenDot
import top.hsyscn.opedrgent.ui.theme.LightBlueBg
import top.hsyscn.opedrgent.ui.theme.TextDark
import top.hsyscn.opedrgent.ui.theme.TextGrey
import top.hsyscn.opedrgent.ui.components.AIMessageCard
import top.hsyscn.opedrgent.ui.components.ConfirmationDialog
import top.hsyscn.opedrgent.ui.components.InputMode
import top.hsyscn.opedrgent.ui.components.InputModeBar
import top.hsyscn.opedrgent.ui.components.SearchScopeChips
import top.hsyscn.opedrgent.ui.components.SearchScope
import top.hsyscn.opedrgent.ui.components.MessageBodyConfigUpdate
import top.hsyscn.opedrgent.ui.components.MessageBodyError
import top.hsyscn.opedrgent.ui.components.MessageBodyInfo
import top.hsyscn.opedrgent.ui.components.QuestionCard
import top.hsyscn.opedrgent.ui.components.QuestionDock
import top.hsyscn.opedrgent.ui.components.SproutResultView
import top.hsyscn.opedrgent.ui.components.SttProgressDialog
import top.hsyscn.opedrgent.ui.components.SttResultCard
import top.hsyscn.opedrgent.ui.components.StreamingCard
import top.hsyscn.opedrgent.ui.components.UserBubble

@Composable
fun SessionScreen(
    vm: MainViewModel,
    sessionId: String?,
    onOpenSettings: () -> Unit,
    onOpenSubScreen: (String) -> Unit,
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val state by vm.state.collectAsStateCompat()

    var prompt by rememberSaveable { mutableStateOf("") }
    var listening by rememberSaveable { mutableStateOf(false) }
    var actionSheetOpen by rememberSaveable { mutableStateOf(false) }
    var isSending by remember { mutableStateOf(false) }  // 发送中的加载状态
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    val audioPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            scope.launch { snackbar.showSnackbar("未授予录音权限") }
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
                        // 图片发送到聊天让 AI 分析
                        val session = vm.state.value.current
                        if (session != null) {
                            vm.sendUserMessage("请分析这张图片的内容：\n[图片已选择，URI: $uri]")
                        } else {
                            snackbar.showSnackbar("请先创建一个对话")
                        }
                    }
                    mimeType?.startsWith("audio/") == true || mimeType?.startsWith("video/") == true -> {
                        // 音频/视频走 ASR 识别
                        vm.startSpeechToText(uri)
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

    Box(modifier = Modifier.fillMaxSize().background(BgGray)) {
        var showMoreOptionsSheet by rememberSaveable { mutableStateOf(false) }

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight().widthIn(max = 720.dp)) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.closeSession(); onBack() }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "back", tint = TextDark)
            }
            Text(
                text = session?.title ?: "Opedrgent",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = TextDark,
                modifier = Modifier.weight(1f),
            )
            Card(
                shape = CircleShape,
                colors = CardDefaults.cardColors(containerColor = BarBg),
                modifier = Modifier
                    .shadow(4.dp, CircleShape)
                    .clip(CircleShape)
                    .size(36.dp),
                onClick = { actionSheetOpen = true },
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(Icons.Default.MoreHoriz, contentDescription = "more", tint = TextDark, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Card(
                shape = CircleShape,
                colors = CardDefaults.cardColors(containerColor = BarBg),
                modifier = Modifier
                    .shadow(4.dp, CircleShape)
                    .clip(CircleShape)
                    .size(36.dp),
                onClick = onOpenSettings,
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(Icons.Default.Settings, contentDescription = "settings", tint = TextDark, modifier = Modifier.size(18.dp))
                }
            }
        }

        // AI 助手状态指示器条
        AiStatusBar(isStreaming = state.isStreaming)

        if (session == null) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("选择一个会话开始对话", color = TextGrey)
            }
        } else {
            val msgCount = session.messages.size + if (state.isStreaming) 1 else 0
            LaunchedEffect(msgCount, state.streamingText.length) {
                if (msgCount > 0) {
                    kotlinx.coroutines.delay(100)
                    listState.animateScrollToItem(msgCount - 1)
                }
            }

            // 发芽结果视图 - 自然显示在聊天中
            val sproutResult by vm.sproutResult.collectAsStateCompat()
            val sproutingState by vm.sproutingState.collectAsStateCompat()

            // Messages
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(session.messages, key = { it.id }) { msg ->
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

                if (state.isStreaming && state.streamingSessionId == session.id) {
                    item(key = "_streaming") {
                        StreamingCard(
                            text = state.streamingText,
                            reasoning = state.streamingReasoning,
                            toolParts = state.streamingToolParts,
                            phase = state.streamingPhase,
                        )
                    }
                }

                if (state.activeQuestion != null) {
                    item(key = "_question") {
                        QuestionCard(
                            question = state.activeQuestion!!,
                            onAnswer = { vm.answerQuestion(it) },
                            onDismiss = { vm.dismissQuestion() },
                        )
                    }
                }

                // 发芽结果视图 - 自然显示在聊天中
                if (sproutResult != null || sproutingState != SproutingState.IDLE) {
                    item(key = "_sprout") {
                        top.hsyscn.opedrgent.ui.components.SproutResultView(
                            markdownReport = sproutResult ?: "",
                            sproutingState = sproutingState,
                            onCopy = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("发芽结果", sproutResult ?: ""))
                                scope.launch { snackbar.showSnackbar("已复制到剪贴板") }
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

            // Stop responding button
            AnimatedVisibility(visible = state.isStreaming) {
                OutlinedButton(
                    onClick = { vm.stopGeneration() },
                    modifier = Modifier
                        .padding(horizontal = 66.dp, vertical = 4.dp)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(7.dp),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = LightBlueBg),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true),
                ) {
                    Box(
                        modifier = Modifier
                            .size(17.dp)
                            .background(AccentBlue, RoundedCornerShape(2.dp)),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("停止回复", color = AccentBlue, fontWeight = FontWeight.Medium)
                }
            }

            // Input mode bar
            var inputMode by rememberSaveable { mutableStateOf(InputMode.CHAT) }
            InputModeBar(
                currentMode = inputMode,
                onModeChange = { inputMode = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )

            // Search scope chips
            SearchScopeChips(
                currentScope = state.searchScope,
                onScopeChange = { vm.setSearchScope(it) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            )

            // Input bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 100.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // "+" button for more options
                Card(
                    shape = CircleShape,
                    colors = CardDefaults.cardColors(containerColor = AccentBlue),
                    modifier = Modifier
                        .size(37.dp)
                        .clip(CircleShape),
                    onClick = { showMoreOptionsSheet = true },
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "more",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(11.dp)),
                    shape = RoundedCornerShape(11.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFAFAFA)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = prompt,
                            onValueChange = { prompt = it },
                            placeholder = { Text("输入消息...", color = TextGrey) },
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                            ),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = {
                                if (!isSending && prompt.isNotBlank()) {
                                    isSending = true
                                    vm.sendUserMessage(prompt)
                                    prompt = ""
                                    scope.launch {
                                        kotlinx.coroutines.delay(500)
                                        isSending = false
                                    }
                                }
                            }),
                        )
                        // Camera/Files button
                        IconButton(onClick = { filePicker.launch(arrayOf("image/*", "audio/*", "video/*", "application/pdf")) }) {
                            Icon(Icons.Default.CameraAlt, contentDescription = "attach file", tint = TextGrey, modifier = Modifier.size(18.dp))
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
                                    contentDescription = "stt",
                                    tint = if (listening) AccentBlue else TextGrey,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }

                // Send button
                Card(
                    shape = CircleShape,
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            isSending -> Color(0xFFBDBDBD)  // 发送中：灰色
                            prompt.isNotBlank() -> AccentBlue
                            else -> Color(0xFFE0E0E0)
                        },
                    ),
                    modifier = Modifier
                        .size(37.dp)
                        .clip(CircleShape),
                    onClick = {
                        if (!isSending && prompt.isNotBlank()) {
                            isSending = true
                            vm.sendUserMessage(prompt)
                            prompt = ""
                            // 流式输出开始后重置发送状态
                            scope.launch {
                                kotlinx.coroutines.delay(500)
                                isSending = false
                            }
                        }
                    },
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        if (isSending) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color.White,
                            )
                        } else {
                            Icon(
                                Icons.Default.ArrowForward,
                                contentDescription = "send",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp),
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
                            .padding(16.dp),
                    ) {
                        Text(
                            text = "更多方式",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 16.dp),
                        )

                        // Import audio/video
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    showMoreOptionsSheet = false
                                    if (vm.isSttEnabled()) {
                                        audioFilePicker.launch(arrayOf("audio/*", "video/*"))
                                    } else {
                                        scope.launch { snackbar.showSnackbar("请先在设置中开启语音转文字") }
                                    }
                                },
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5)),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.AudioFile,
                                    contentDescription = null,
                                    tint = AccentBlue,
                                    modifier = Modifier.size(24.dp),
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "导入音视频",
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        text = "支持 MP3、WAV、M4A、AMR、AAC、AVI、MOV 等格式",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextGrey,
                                    )
                                }
                            }
                        }

                        // Paste link
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    showMoreOptionsSheet = false
                                    // TODO: Implement paste link functionality
                                    scope.launch { snackbar.showSnackbar("粘贴链接功能开发中") }
                                },
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5)),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.Link,
                                    contentDescription = null,
                                    tint = AccentBlue,
                                    modifier = Modifier.size(24.dp),
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "粘贴链接",
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        text = "公众号/抖音/B站/小红书等，一键总结",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextGrey,
                                    )
                                }
                            }
                        }

                        // Photo/Image
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    showMoreOptionsSheet = false
                                    filePicker.launch(arrayOf("image/*"))
                                },
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5)),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.CameraAlt,
                                    contentDescription = null,
                                    tint = AccentBlue,
                                    modifier = Modifier.size(24.dp),
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "拍照/图片",
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        text = "白板记录/课堂笔记/日常饮食，秒变笔记",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextGrey,
                                    )
                                }
                            }
                        }

                        // Meeting recording
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    showMoreOptionsSheet = false
                                    onOpenSubScreen("meeting")
                                },
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5)),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.Mic,
                                    contentDescription = null,
                                    tint = AccentBlue,
                                    modifier = Modifier.size(24.dp),
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "会议录音",
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        text = "实时录音 + 说话人分离 + AI 总结",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextGrey,
                                    )
                                }
                            }
                        }

                        // Knowledge base
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    showMoreOptionsSheet = false
                                    onOpenSubScreen("knowledge")
                                },
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5)),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.Save,
                                    contentDescription = null,
                                    tint = AccentBlue,
                                    modifier = Modifier.size(24.dp),
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "知识库",
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        text = "导入文档/PDF/图片，AI 可检索引用",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextGrey,
                                    )
                                }
                            }
                        }

                        // AI Editor Team
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    showMoreOptionsSheet = false
                                    onOpenSubScreen("editorTeam")
                                },
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5)),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "\uD83E\uDDD1\u200D\uD83C\uDFA8",
                                    fontSize = 24.sp,
                                    modifier = Modifier.padding(end = 4.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "AI 编辑团",
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        text = "AI 动态规划编辑团队",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextGrey,
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
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
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 80.dp), // 避让底部 InputModeBar
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
                    .padding(horizontal = 16.dp, vertical = 8.dp),
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
                    .padding(horizontal = 12.dp, vertical = 110.dp),
            )
        }
    }

    if (actionSheetOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { actionSheetOpen = false },
            sheetState = sheetState,
            containerColor = Color.White,
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "会话操作",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            scope.launch {
                                val f = vm.exportMarkdown()
                                if (f != null) shareFile(context, vm.getPackageNameForShare(context), f) else snackbar.showSnackbar("导出失败")
                            }
                            actionSheetOpen = false
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(11.dp),
                    ) { Text("导出会话") }
                    Button(
                        onClick = {
                            scope.launch {
                                val f = vm.exportContextZip()
                                if (f != null) shareFile(context, vm.getPackageNameForShare(context), f) else snackbar.showSnackbar("导出失败")
                            }
                            actionSheetOpen = false
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(11.dp),
                    ) { Text("导出上下文") }
                }

                Button(
                    onClick = {
                        actionSheetOpen = false
                        onOpenSubScreen("export")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(11.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF5F5F5), contentColor = TextDark),
                ) { Text("完整导出中心") }

                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

/**
 * AI 助手状态指示器条。
 * 显示在 SessionScreen 顶部栏下方，增强 AI 在线感。
 */
@Composable
private fun AiStatusBar(isStreaming: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardWhite)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // AI 头像
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(AccentBlue),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.ChatBubble,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(14.sp.value.dp.coerceAtLeast(14.dp)),
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        // 状态文字
        Text(
            text = if (isStreaming) "思考中..." else "我在",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = AccentBlue,
        )

        // 在线指示灯
        Box(
            modifier = Modifier
                .padding(start = 6.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(GreenDot.copy(alpha = pulseAlpha)),
        )
    }
}
