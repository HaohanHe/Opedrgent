@file:OptIn(ExperimentalMaterial3Api::class)

package top.hsyscn.opedrgent.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import top.hsyscn.opedrgent.model.Role
import top.hsyscn.opedrgent.model.MessageType
import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.settings.PROVIDER_PRESETS
import top.hsyscn.opedrgent.ui.theme.AccentBlue
import top.hsyscn.opedrgent.ui.theme.BarBg
import top.hsyscn.opedrgent.ui.theme.BgGray
import top.hsyscn.opedrgent.ui.theme.BubbleBlue
import top.hsyscn.opedrgent.ui.theme.CardWhite
import top.hsyscn.opedrgent.ui.theme.CitationBg
import top.hsyscn.opedrgent.ui.theme.GreenDot
import top.hsyscn.opedrgent.ui.theme.LightBlueBg
import top.hsyscn.opedrgent.ui.theme.TextDark
import top.hsyscn.opedrgent.ui.theme.TextGrey
import top.hsyscn.opedrgent.ui.theme.UserBubbleEnd
import top.hsyscn.opedrgent.ui.theme.UserBubbleStart
import top.hsyscn.opedrgent.ui.components.AIMessageCard
import top.hsyscn.opedrgent.ui.components.MarkdownText
import top.hsyscn.opedrgent.ui.components.SourceCitations
import top.hsyscn.opedrgent.ui.components.StreamingCard
import top.hsyscn.opedrgent.ui.components.QuestionCard
import top.hsyscn.opedrgent.ui.components.QuestionDock
import top.hsyscn.opedrgent.ui.components.ConfirmationDialog
import top.hsyscn.opedrgent.ui.components.ConfirmationRequest
import top.hsyscn.opedrgent.ui.components.UserBubble
import top.hsyscn.opedrgent.ui.components.SttProgressDialog
import top.hsyscn.opedrgent.ui.components.SttResultCard
import top.hsyscn.opedrgent.ui.components.InputMode
import top.hsyscn.opedrgent.ui.components.InputModeBar
import top.hsyscn.opedrgent.ui.components.RecordingState

import top.hsyscn.opedrgent.ui.components.MessageBodyInfo
import top.hsyscn.opedrgent.ui.components.MessageBodyConfigUpdate
import top.hsyscn.opedrgent.ui.components.MessageBodyError
import top.hsyscn.opedrgent.llm.LocalLlmEngine
import top.hsyscn.opedrgent.llm.LocalLlmState
import top.hsyscn.opedrgent.llm.ModelDownloadManager
import top.hsyscn.opedrgent.llm.AvailableLocalModels
import top.hsyscn.opedrgent.ui.components.ModelSelectorDialog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class MainTab { HISTORY, CHAT, SETTINGS }

@Composable
fun AppRoot(
    initialShareText: String? = null,
    initialAction: String? = null,
    onShareConsumed: () -> Unit = {},
    onActionConsumed: () -> Unit = {},
    vm: MainViewModel = viewModel(),
) {
    var selectedTab by rememberSaveable { mutableStateOf(MainTab.CHAT) }
    var selectedSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var subScreen by rememberSaveable { mutableStateOf<String?>(null) }

    val state by vm.state.collectAsStateCompat()

    LaunchedEffect(initialShareText) {
        val t = initialShareText?.trim().orEmpty()
        if (t.isNotEmpty()) {
            vm.handleIncomingShare(t)
            onShareConsumed()
        }
    }

    LaunchedEffect(initialAction) {
        val action = initialAction
        if (action != null) {
            when (action) {
                "meeting" -> subScreen = "meeting"
                "new_chat" -> {
                    vm.createSessionAndNavigate("新对话")
                    selectedTab = MainTab.CHAT
                }
            }
            onActionConsumed()
        }
    }

    LaunchedEffect(state.navigateToSessionId) {
        val id = state.navigateToSessionId
        if (!id.isNullOrBlank()) {
            selectedSessionId = id
            selectedTab = MainTab.CHAT
            vm.openSession(id)
            vm.consumeNavigation()
        }
    }

    LaunchedEffect(selectedSessionId) {
        val id = selectedSessionId
        if (id != null) {
            vm.openSession(id)
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        containerColor = BgGray,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (subScreen) {
                "memory" -> MemoryManagerScreen(vm = vm, onBack = { subScreen = null })
                "skills" -> SkillsScreen(vm = vm, onBack = { subScreen = null })
                "automations" -> top.hsyscn.opedrgent.ui.AutomationsScreen(onBack = { subScreen = null })
                "meeting" -> MeetingRecordScreen(
                    vm = vm,
                    onBack = { subScreen = null },
                    onSendToChat = { text ->
                        vm.sendUserMessage("请帮我总结以下会议内容：\n\n$text")
                        subScreen = null
                    },
                )
                "knowledge" -> KnowledgeBaseScreen(vm = vm, onBack = { subScreen = null })
                "export" -> ExportScreen(vm = vm, onBack = { subScreen = null })
                null -> {
                    when {
                        selectedTab == MainTab.SETTINGS -> SettingsScreen(
                            vm = vm,
                            onBack = { selectedTab = MainTab.CHAT },
                            toSkills = { subScreen = "skills" },
                            toAutomations = { subScreen = "automations" },
                            toMemory = { subScreen = "memory" },
                        )
                        selectedTab == MainTab.HISTORY || (selectedTab == MainTab.CHAT && selectedSessionId == null) -> SessionsScreen(
                            vm = vm,
                            onSelectSession = { id ->
                                selectedSessionId = id
                                selectedTab = MainTab.CHAT
                            },
                            onSearch = { selectedTab = MainTab.CHAT },
                        )
                        else -> SessionScreen(
                            vm = vm,
                            sessionId = selectedSessionId,
                            onOpenSettings = { selectedTab = MainTab.SETTINGS },
                            onOpenSubScreen = { subScreen = it },
                            onBack = { selectedSessionId = null },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SessionsScreen(
    vm: MainViewModel,
    onSelectSession: (String) -> Unit,
    onSearch: () -> Unit,
) {
    val state by vm.state.collectAsStateCompat()
    var createOpen by rememberSaveable { mutableStateOf(false) }
    var title by rememberSaveable { mutableStateOf("") }
    var q by rememberSaveable { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Opedrgent", fontWeight = FontWeight.Bold) },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { createOpen = true },
                containerColor = AccentBlue,
                contentColor = Color.White,
            ) {
                Icon(Icons.Default.Add, contentDescription = "new")
            }
        },
        containerColor = BgGray,
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = q,
                onValueChange = { q = it; vm.setSessionSearchQuery(it) },
                label = { Text("搜索") },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(11.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = Color(0xFFE4E4E4),
                    focusedBorderColor = AccentBlue,
                ),
            )
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.sessions, key = { it.id }) { s ->
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .fillMaxWidth(),
                        shape = RoundedCornerShape(11.dp),
                        colors = CardDefaults.cardColors(containerColor = CardWhite),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                        onClick = { onSelectSession(s.id) },
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(text = s.title, fontWeight = FontWeight.SemiBold, color = TextDark)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(text = formatTime(s.updatedAt), style = MaterialTheme.typography.bodySmall, color = TextGrey)
                        }
                    }
                }
            }
        }
    }

    if (createOpen) {
        AlertDialog(
            onDismissRequest = { createOpen = false },
            confirmButton = {
                Button(onClick = {
                    vm.createSessionAndNavigate(title)
                    title = ""
                    createOpen = false
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { createOpen = false }) { Text("取消") } },
            title = { Text("新建研究") },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
    }
}

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
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    val audioPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            scope.launch { snackbar.showSnackbar("未授予录音权限") }
        }
    }

    val recognizer = remember { mutableStateOf<SpeechRecognizer?>(null) }
    val listener = remember {
        object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                listening = false
                val msg = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "录音错误"
                    SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "权限不足"
                    SpeechRecognizer.ERROR_NETWORK -> "网络错误"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
                    SpeechRecognizer.ERROR_NO_MATCH -> "未识别到语音"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别引擎繁忙"
                    SpeechRecognizer.ERROR_SERVER -> "服务端错误"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "语音超时"
                    else -> "语音识别失败($error)"
                }
                scope.launch { snackbar.showSnackbar(msg) }
            }
            override fun onResults(results: android.os.Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = list?.firstOrNull().orEmpty()
                if (text.isNotBlank()) {
                    prompt = if (prompt.isBlank()) text else (prompt.trimEnd() + "\n" + text)
                } else {
                    scope.launch { snackbar.showSnackbar("未识别到语音") }
                }
                listening = false
            }
            override fun onPartialResults(partialResults: android.os.Bundle?) {}
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        }
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        if (!vm.isSttEnabled()) {
            recognizer.value = null
            onDispose { }
        } else {
            val sr = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
            if (sr == null) {
                recognizer.value = null
                onDispose { }
            } else {
                sr.setRecognitionListener(listener)
                recognizer.value = sr
                onDispose {
                    recognizer.value?.destroy()
                    recognizer.value = null
                }
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val mimeType = context.contentResolver.getType(uri)
            scope.launch {
                when {
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

        Column(modifier = Modifier.fillMaxSize()) {
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
                        Role.USER -> UserBubble(text = msg.content)
                        Role.ASSISTANT -> AIMessageCard(
                            message = msg,
                            onSpeak = { vm.toggleSpeak(msg.content) },
                            isSpeaking = state.isSpeaking,
                            clipboard = clipboard,
                        )
                        Role.SYSTEM -> {
                            when (msg.messageType) {
                                MessageType.INFO -> MessageBodyInfo(message = msg.content)
                                MessageType.CONFIG_UPDATE -> {
                                    val parts = msg.content.split("|")
                                    if (parts.size == 3) {
                                        MessageBodyConfigUpdate(
                                            configName = parts[0],
                                            oldValue = parts[1],
                                            newValue = parts[2],
                                        )
                                    }
                                }
                                MessageType.ERROR -> MessageBodyError(
                                    errorText = msg.content,
                                    snackbarHostState = snackbar,
                                )
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
                                if (prompt.isNotBlank()) {
                                    vm.sendUserMessage(prompt)
                                    prompt = ""
                                }
                            }),
                        )
                        // Camera button
                        IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Default.CameraAlt, contentDescription = "camera", tint = TextGrey, modifier = Modifier.size(18.dp))
                        }
                        // Microphone button
                        if (vm.isSttEnabled()) {
                            IconButton(onClick = {
                                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                                if (!granted) {
                                    audioPerm.launch(Manifest.permission.RECORD_AUDIO)
                                    return@IconButton
                                }
                                val sr = recognizer.value
                                if (sr == null) {
                                    scope.launch { snackbar.showSnackbar("设备不支持语音识别") }
                                    return@IconButton
                                }
                                if (!listening) {
                                    listening = true
                                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                                    sr.startListening(intent)
                                } else {
                                    listening = false
                                    sr.stopListening()
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
                    colors = CardDefaults.cardColors(containerColor = if (prompt.isNotBlank()) AccentBlue else Color(0xFFE0E0E0)),
                    modifier = Modifier
                        .size(37.dp)
                        .clip(CircleShape),
                    onClick = {
                        if (prompt.isNotBlank()) {
                            vm.sendUserMessage(prompt)
                            prompt = ""
                        }
                    },
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
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

        // More options bottom sheet (at Box level, as overlay)
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
                                    audioFilePicker.launch(arrayOf("audio/*", "video/*"))
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

                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }

        questionRequest?.let { request ->
            QuestionDock(
                request = request,
                onAnswer = { answers -> vm.respondToQuestion(answers) },
                onDismiss = { vm.respondToQuestion(emptyList()) },
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
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









@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit, toSkills: () -> Unit, toAutomations: () -> Unit, toMemory: () -> Unit) {
    var baseUrl by rememberSaveable { mutableStateOf(vm.getBaseUrl()) }
    var model by rememberSaveable { mutableStateOf(vm.getModel()) }
    var apiKey by rememberSaveable { mutableStateOf(vm.getApiKey() ?: "") }
    var ttsEnabled by rememberSaveable { mutableStateOf(vm.isTtsEnabled()) }
    var ttsAuto by rememberSaveable { mutableStateOf(vm.isTtsAutoSpeak()) }
    var ttsRate by rememberSaveable { mutableStateOf(vm.getTtsRate()) }
    var ttsPitch by rememberSaveable { mutableStateOf(vm.getTtsPitch()) }
    var ttsLocaleTag by rememberSaveable { mutableStateOf(vm.getTtsLocaleTag()) }
    var sttEnabled by rememberSaveable { mutableStateOf(vm.isSttEnabled()) }
    var bgRunning by rememberSaveable { mutableStateOf(vm.isBackgroundRunning()) }
    var locationEnabled by rememberSaveable { mutableStateOf(vm.isLocationEnabled()) }
    var debugMode by rememberSaveable { mutableStateOf(vm.isDebugMode()) }
    var deepThinkingEnabled by rememberSaveable { mutableStateOf(vm.isDeepThinking()) }
    var jinaApiKey by rememberSaveable { mutableStateOf(vm.getJinaApiKey() ?: "") }
    var showModelSelector by rememberSaveable { mutableStateOf(false) }
    var showMemoryWarning by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val localEngine = remember { LocalLlmEngine.getInstance(context) }
    val downloadManager = remember { ModelDownloadManager(context) }
    var isLocalMode by rememberSaveable { mutableStateOf(vm.isLocalModelEnabled()) }
    var localModelId by rememberSaveable { mutableStateOf(vm.getLocalModelId()) }
    var providerMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var modelMenuExpanded by rememberSaveable { mutableStateOf(false) }
    val state by vm.state.collectAsStateCompat()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    fun buildUserInferenceConfig(info: top.hsyscn.opedrgent.llm.LocalModelInfo): top.hsyscn.opedrgent.llm.LlmInferenceConfig {
        val base = AvailableLocalModels.buildInferenceConfig(info)
        val savedMax = vm.getMaxOutputTokens()
        return base.copy(
            temperature = vm.getLocalTemperature(),
            topK = vm.getLocalTopK(),
            topP = vm.getLocalTopP(),
            maxTokens = if (savedMax > 0) savedMax else base.maxTokens,
        )
    }
    val locationPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            locationEnabled = true
            vm.saveLocationEnabled(true)
            vm.refreshLocation()
        } else {
            locationEnabled = false
            scope.launch { snackbar.showSnackbar("未授予位置权限") }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbar) },
        containerColor = BgGray,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(12.dp)
                .padding(bottom = 100.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("模型供应商", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            if (isLocalMode) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(11.dp),
                    colors = CardDefaults.cardColors(containerColor = BubbleBlue.copy(alpha = 0.06f)),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.DeveloperBoard, contentDescription = null, tint = BubbleBlue, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("本地模式运行中", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = BubbleBlue)
                            Text("当前使用 Gemma 4 离线模型，API 配置已暂停", color = TextGrey, fontSize = 11.sp)
                        }
                    }
                }
            } else {
                Box {
                OutlinedTextField(
                    value = PROVIDER_PRESETS.firstOrNull { it.baseUrl == baseUrl }?.name ?: "自定义",
                    onValueChange = {},
                    label = { Text("供应商") },
                    modifier = Modifier.fillMaxWidth(),
                    readOnly = true,
                    trailingIcon = {
                        IconButton(onClick = { providerMenuExpanded = true }) {
                            Icon(Icons.Default.ArrowDropDown, contentDescription = "展开")
                        }
                    },
                )
                DropdownMenu(expanded = providerMenuExpanded, onDismissRequest = { providerMenuExpanded = false }) {
                    PROVIDER_PRESETS.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(preset.name) },
                            onClick = {
                                baseUrl = preset.baseUrl
                                if (preset.models.isNotEmpty()) model = preset.models.first()
                                providerMenuExpanded = false
                            },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Base URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Box {
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    trailingIcon = {
                        val currentPreset = PROVIDER_PRESETS.firstOrNull { it.baseUrl == baseUrl }
                        if (currentPreset != null && currentPreset.models.isNotEmpty()) {
                            IconButton(onClick = { modelMenuExpanded = true }) {
                                Icon(Icons.Default.ArrowDropDown, contentDescription = "展开模型")
                            }
                        }
                    },
                )
                val currentPreset = PROVIDER_PRESETS.firstOrNull { it.baseUrl == baseUrl }
                if (currentPreset != null && currentPreset.models.isNotEmpty()) {
                    DropdownMenu(expanded = modelMenuExpanded, onDismissRequest = { modelMenuExpanded = false }) {
                        currentPreset.models.forEach { m ->
                            DropdownMenuItem(
                                text = { Text(m) },
                                onClick = {
                                    model = m
                                    modelMenuExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            OutlinedTextField(
                value = jinaApiKey,
                onValueChange = { jinaApiKey = it },
                label = { Text("Jina API Key (可选)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            }

            HorizontalDivider()

            Text("记忆管理", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = toMemory,
                shape = RoundedCornerShape(11.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("记忆条目", fontWeight = FontWeight.SemiBold)
                        Text("共 ${state.memories.size} 条记忆", style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.Default.ArrowForward, contentDescription = "进入")
                }
            }

            HorizontalDivider()

            Text("语音", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(11.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "语音转文字", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Switch(checked = sttEnabled, onCheckedChange = { sttEnabled = it })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "TTS 朗读", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Switch(checked = ttsEnabled, onCheckedChange = { ttsEnabled = it })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "自动朗读回答", modifier = Modifier.weight(1f))
                        Switch(checked = ttsAuto, onCheckedChange = { ttsAuto = it }, enabled = ttsEnabled)
                    }
                    Text(text = "语速")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { ttsRate = 0.85f }, enabled = ttsEnabled, shape = RoundedCornerShape(11.dp)) { Text("慢") }
                        Button(onClick = { ttsRate = 1.0f }, enabled = ttsEnabled, shape = RoundedCornerShape(11.dp)) { Text("正常") }
                        Button(onClick = { ttsRate = 1.2f }, enabled = ttsEnabled, shape = RoundedCornerShape(11.dp)) { Text("快") }
                    }
                    Text(text = "语言")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { ttsLocaleTag = "zh-CN" }, enabled = ttsEnabled, shape = RoundedCornerShape(11.dp)) { Text("中文") }
                        Button(onClick = { ttsLocaleTag = "en-US" }, enabled = ttsEnabled, shape = RoundedCornerShape(11.dp)) { Text("英文") }
                    }
                }
            }

            HorizontalDivider()

            Text("后台运行", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(11.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "后台运行", fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "开启后应用将在后台持续运行，定时任务和自动化不会中断",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(checked = bgRunning, onCheckedChange = {
                            bgRunning = it
                            if (it) {
                                val activity = context as? Activity
                                if (activity != null) {
                                    val status = top.hsyscn.opedrgent.utils.BackgroundPermHelper.checkPermissions(context)
                                    if (!status.batteryOptOk) {
                                        top.hsyscn.opedrgent.utils.BackgroundPermHelper.requestBatteryOptimization(activity)
                                    }
                                    if (!status.autostartGranted) {
                                        top.hsyscn.opedrgent.utils.BackgroundPermHelper.requestAutostart(activity)
                                    }
                                }
                            }
                        })
                    }
                    if (!bgRunning) {
                        Text(
                            text = "提示：开启后建议同时允许自启动和关闭电池优化，否则系统可能在后台杀掉应用",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            HorizontalDivider()

            Text("位置与环境", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(11.dp)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "位置感知", fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "AI 可感知你的位置，用于本地天气/新闻/餐厅等回答",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(checked = locationEnabled, onCheckedChange = {
                            if (it) {
                                locationPermLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
                            } else {
                                locationEnabled = false
                                vm.saveLocationEnabled(false)
                            }
                        })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "深度思考", fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "启用后模型会展示推理思考过程（支持 thinking 的模型）",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = deepThinkingEnabled,
                            onCheckedChange = {
                                deepThinkingEnabled = it
                                vm.saveDeepThinking(it)
                            },
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "Debug 模式", fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "开启后在 logcat 中输出详细日志（标签: Opedrgent）",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(checked = debugMode, onCheckedChange = {
                            debugMode = it
                            vm.saveDebugMode(it)
                        })
                    }
                }
            }

            HorizontalDivider()

            Text("本地模型", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.DeveloperBoard, contentDescription = null, tint = BubbleBlue, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("本地模型 (Gemma 4 离线)", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = TextDark)
                    }

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = if (isLocalMode && localModelId != null) {
                            val info = AvailableLocalModels.findById(localModelId!!)
                            "当前使用: ${info?.displayName ?: localModelId}"
                        } else "下载 Gemma 4 模型到设备，完全离线运行，无需网络连接",
                        color = TextGrey,
                        fontSize = 13.sp,
                    )

                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Switch(
                            checked = isLocalMode,
                            onCheckedChange = { 
                                isLocalMode = it
                                vm.saveLocalModelEnabled(it)
                                if (it) {
                                    if (localModelId != null) {
                                        val info = AvailableLocalModels.findById(localModelId!!)
                                        if (info != null && localEngine.isModelDownloaded(info)) {
                                            val path = localEngine.getModelPath(info)
                                            if (path != null) {
                                                scope.launch {
                                                    val loaded = try {
                                                        localEngine.loadModel(path, info, buildUserInferenceConfig(info))
                                                    } catch (e: IllegalArgumentException) {
                                                        if (e.message?.contains("Insufficient memory") == true) {
                                                            showMemoryWarning = e.message
                                                            return@launch
                                                        } else {
                                                            throw e
                                                        }
                                                    }

                                                    if (loaded) {
                                                        snackbar.showSnackbar("已切换到离线模式: ${info.displayName}")
                                                    } else {
                                                        isLocalMode = false
                                                        vm.saveLocalModelEnabled(false)
                                                        val errorState = (localEngine.state as? LocalLlmState.Error)
                                                        val errorMsg = errorState?.message ?: "未知错误"
                                                        snackbar.showSnackbar("模型加载失败: $errorMsg")
                                                    }
                                                }
                                            } else {
                                                isLocalMode = false
                                                vm.saveLocalModelEnabled(false)
                                                scope.launch { snackbar.showSnackbar("模型文件不存在") }
                                            }
                                        } else {
                                            isLocalMode = false
                                            vm.saveLocalModelEnabled(false)
                                            scope.launch { snackbar.showSnackbar("请先下载模型") }
                                        }
                                    } else {
                                        isLocalMode = false
                                        vm.saveLocalModelEnabled(false)
                                        scope.launch { snackbar.showSnackbar("请先选择并下载模型") }
                                    }
                                } else {
                                    localEngine.unload()
                                    vm.saveLocalModelId(null)
                                    localModelId = null
                                    scope.launch { snackbar.showSnackbar("已切换到 API 模式") }
                                }
                            },
                            colors = SwitchDefaults.colors(checkedTrackColor = BubbleBlue),
                        )

                        OutlinedButton(
                            onClick = { showModelSelector = true },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp), tint = BubbleBlue)
                            Spacer(Modifier.width(6.dp))
                            Text("选择模型 / 下载", fontSize = 12.sp, color = BubbleBlue)
                        }
                    }

                    val currentInfo = localModelId?.let { AvailableLocalModels.findById(it) }
                    if (isLocalMode && currentInfo != null) {
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider(color = Color(0xFFEEEEEE))
                        Spacer(Modifier.height(10.dp))

                        var localTemp by rememberSaveable { mutableStateOf(vm.getLocalTemperature()) }
                        var localTopK by rememberSaveable { mutableStateOf(vm.getLocalTopK()) }
                        var localTopP by rememberSaveable { mutableStateOf(vm.getLocalTopP()) }
                        var localMaxTok by rememberSaveable { mutableStateOf(vm.getMaxOutputTokens()) }

                        Text("推理参数", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = TextDark)
                        Text("上下文: ${currentInfo.maxContextLength} tokens | 输出: ${if (localMaxTok > 0) localMaxTok else currentInfo.maxTokens} tokens", fontSize = 11.sp, color = TextGrey)

                        Spacer(Modifier.height(8.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Temperature", fontSize = 11.sp, color = TextGrey)
                                Slider(
                                    value = localTemp,
                                    onValueChange = { localTemp = it },
                                    valueRange = 0.01f..2.0f,
                                    steps = 39,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text("${String.format("%.2f", localTemp)}", fontSize = 10.sp, color = TextGrey)
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Top P", fontSize = 11.sp, color = TextGrey)
                                Slider(
                                    value = localTopP,
                                    onValueChange = { localTopP = it },
                                    valueRange = 0.1f..1.0f,
                                    steps = 17,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text("${String.format("%.2f", localTopP)}", fontSize = 10.sp, color = TextGrey)
                            }
                        }

                        Spacer(Modifier.height(6.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Top K: $localTopK", fontSize = 11.sp, color = TextGrey)
                                Slider(
                                    value = localTopK.toFloat(),
                                    onValueChange = { localTopK = it.toInt() },
                                    valueRange = 1f..128f,
                                    steps = 126,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            OutlinedTextField(
                                value = if (localMaxTok > 0) localMaxTok.toString() else "",
                                onValueChange = { localMaxTok = it.toIntOrNull() ?: 0 },
                                label = { Text("最大输出", fontSize = 10.sp) },
                                modifier = Modifier.width(100.dp),
                                singleLine = true,
                            )
                        }

                        Spacer(Modifier.height(6.dp))

                        OutlinedButton(
                            onClick = {
                                vm.saveLocalParams(localTemp, localTopK, localTopP, localMaxTok)
                                scope.launch { snackbar.showSnackbar("参数已保存，下次加载模型生效") }
                            },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(14.dp), tint = BubbleBlue)
                            Spacer(Modifier.width(4.dp))
                            Text("保存参数", fontSize = 11.sp, color = BubbleBlue)
                        }
                    }
                }
            }
Spacer(Modifier.height(12.dp))

            Button(
                onClick = {
                    vm.saveTts(
                        enabled = ttsEnabled,
                        autoSpeak = ttsAuto,
                        rate = ttsRate,
                        pitch = ttsPitch,
                        localeTag = ttsLocaleTag,
                        mimoEnabled = vm.isTtsMimoEnabled(),
                        mimoVoice = vm.getTtsMimoVoice(),
                    )
                    vm.saveSttEnabled(sttEnabled)
                    vm.saveBackgroundRunning(bgRunning)
                    vm.saveLocationEnabled(locationEnabled)
                    vm.saveDebugMode(debugMode)
                    vm.saveDeepThinking(deepThinkingEnabled)
                    vm.saveJinaApiKey(jinaApiKey.takeIf { it.isNotBlank() })
                    if (!isLocalMode) {
                        val ok = vm.saveSettings(baseUrl = baseUrl, apiKey = apiKey, model = model)
                        if (!ok) return@Button
                    }
                    onBack()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(11.dp),
            ) {
                Text(if (isLocalMode) "保存设置" else "保存")
            }
            Text(text = "API Key 留空表示不修改。", modifier = Modifier.padding(top = 4.dp))
            HorizontalDivider()
            Button(
                onClick = { vm.clearApiKey() },
                modifier = Modifier.fillMaxWidth(),
                enabled = vm.hasApiKey(),
                shape = RoundedCornerShape(11.dp),
            ) {
                Text("清除 API Key")
            }
            Button(onClick = toSkills, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(11.dp)) {
                Text("技能库")
            }
            Button(onClick = toAutomations, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(11.dp)) {
                Text("自动化/心跳")
            }
            if (vm.hasApiKey()) {
                Text("已保存 Key（不显示）。如需更换，重新填写并保存。")
            } else {
                Text("未设置 Key。")
            }
        }
    }

    // 本地模型选择对话框 (在 SettingsScreen 内部)
    if (showModelSelector) {
        ModelSelectorDialog(
            onDismiss = { showModelSelector = false },
            onSelectModel = { modelInfo ->
                scope.launch {
                    val path = localEngine.getModelPath(modelInfo)
                    if (path != null) {
                        val loaded = localEngine.loadModel(path, modelInfo, buildUserInferenceConfig(modelInfo))
                        if (loaded) {
                            vm.saveLocalModelEnabled(true)
                            vm.saveLocalModelId(modelInfo.id)
                            isLocalMode = true
                            localModelId = modelInfo.id
                            snackbar.showSnackbar("已加载 ${modelInfo.displayName}")
                        } else {
                            val errorState = (localEngine.state as? LocalLlmState.Error)
                            val errorMsg = errorState?.message ?: "未知错误"
                            snackbar.showSnackbar("加载失败: $errorMsg")
                        }
                    } else {
                        snackbar.showSnackbar("请先下载模型")
                    }
                }
            },
            downloadManager = downloadManager,
            localEngine = localEngine,
            currentModelId = localModelId,
        )
    }

    showMemoryWarning?.let { msg ->
        AlertDialog(
            onDismissRequest = { showMemoryWarning = null },
            title = { Text("内存不足警告") },
            text = { Text(msg ?: "可用内存不足，无法加载此模型。") },
            confirmButton = {
                Button(onClick = {
                    showMemoryWarning = null
                    scope.launch {
                        val info = localModelId?.let { AvailableLocalModels.findById(it) }
                        if (info != null) {
                            val path = localEngine.getModelPath(info)
                            if (path != null) {
                                val forceLoaded = localEngine.loadModel(path, info, buildUserInferenceConfig(info))
                                if (forceLoaded) {
                                    snackbar.showSnackbar("已切换到离线模式: ${info.displayName}")
                                } else {
                                    isLocalMode = false
                                    vm.saveLocalModelEnabled(false)
                                    val errorState = (localEngine.state as? LocalLlmState.Error)
                                    val errorMsg = errorState?.message ?: "未知错误"
                                    snackbar.showSnackbar("模型加载失败: $errorMsg")
                                }
                            }
                        }
                    }
                }) { Text("仍要尝试") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showMemoryWarning = null
                    isLocalMode = false
                    vm.saveLocalModelEnabled(false)
                }) { Text("取消") }
            },
        )
    }
}

@Composable
fun MemoryManagerScreen(vm: MainViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateCompat()
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var title by rememberSaveable { mutableStateOf("") }
    var content by rememberSaveable { mutableStateOf("") }
    var selectedType by rememberSaveable { mutableStateOf("USER") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("记忆管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        editingId = null
                        title = ""
                        content = ""
                        selectedType = "USER"
                        editorOpen = true
                    }) {
                        Icon(Icons.Default.Add, contentDescription = "add")
                    }
                },
            )
        },
        containerColor = BgGray,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.memories.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("暂无记忆条目", style = MaterialTheme.typography.bodyLarge)
                        Text("点击右上角 + 添加记忆，AI 会自动参考这些内容。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            items(state.memories, key = { it.id }) { entry ->
                Card(
                    modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth(),
                    shape = RoundedCornerShape(11.dp),
                    onClick = {
                        editingId = entry.id
                        title = entry.title
                        content = entry.content
                        selectedType = entry.type.name
                        editorOpen = true
                    },
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = entry.title.ifBlank { "（无标题）" }, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            AssistChip(
                                onClick = {},
                                label = { Text(entry.type.label, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.height(24.dp),
                            )
                            IconButton(onClick = { vm.deleteMemory(entry.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "删除")
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = entry.content.take(200) + if (entry.content.length > 200) "…" else "", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    if (editorOpen) {
        val types = top.hsyscn.opedrgent.model.MemoryType.entries
        AlertDialog(
            onDismissRequest = { editorOpen = false },
            confirmButton = {
                Button(onClick = {
                    val t = title.trim()
                    val c = content.trim()
                    if (t.isEmpty() || c.isEmpty()) return@Button
                    val id = editingId
                    val memType = runCatching { top.hsyscn.opedrgent.model.MemoryType.valueOf(selectedType) }.getOrDefault(top.hsyscn.opedrgent.model.MemoryType.USER)
                    if (id != null) {
                        vm.updateMemory(id, t, c, memType)
                    } else {
                        vm.addMemory(t, c, memType)
                    }
                    editorOpen = false
                }) { Text("保存") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (editingId != null) {
                        TextButton(onClick = {
                            vm.deleteMemory(editingId!!)
                            editorOpen = false
                        }) { Text("删除") }
                    }
                    TextButton(onClick = { editorOpen = false }) { Text("取消") }
                }
            },
            title = { Text(if (editingId == null) "新建记忆" else "编辑记忆") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        types.forEach { memType ->
                            FilterChip(
                                selected = selectedType == memType.name,
                                onClick = { selectedType = memType.name },
                                label = { Text(memType.label) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("标题（例如：用户偏好、项目背景）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        label = { Text("内容（AI 会自动参考）") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                    )
                }
            },
        )
    }
}

@Composable
fun SkillsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateCompat()
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var prompt by rememberSaveable { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("技能库") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        editingId = null
                        name = ""
                        prompt = ""
                        editorOpen = true
                    }) {
                        Icon(Icons.Default.Add, contentDescription = "add")
                    }
                },
            )
        },
        containerColor = BgGray,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.skills, key = { it.id }) { s ->
                Card(
                    modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth(),
                    shape = RoundedCornerShape(11.dp),
                    onClick = {
                        editingId = s.id
                        name = s.name
                        prompt = s.prompt
                        editorOpen = true
                    },
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(text = s.name, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(text = s.prompt.take(120) + if (s.prompt.length > 120) "…" else "")
                    }
                }
            }
        }
    }

    if (editorOpen) {
        val deleting = editingId != null
        AlertDialog(
            onDismissRequest = { editorOpen = false },
            confirmButton = {
                Button(onClick = {
                    vm.addOrUpdateSkill(id = editingId, name = name, prompt = prompt)
                    editorOpen = false
                }) {
                    Icon(Icons.Default.Save, contentDescription = "save")
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("保存")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (deleting) {
                        TextButton(onClick = {
                            val id = editingId
                            if (id != null) vm.deleteSkill(id)
                            editorOpen = false
                        }) { Text("删除") }
                    }
                    TextButton(onClick = { editorOpen = false }) { Text("取消") }
                }
            },
            title = { Text(if (editingId == null) "新建技能" else "编辑技能") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("名称") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        label = { Text("内容（会作为 User 消息发送）") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                    )
                }
            },
        )
    }
}









fun formatTime(ms: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
}

fun shareFile(context: android.content.Context, packageName: String, file: File, mime: String = "text/markdown") {
    val uri = FileProvider.getUriForFile(context, "$packageName.fileprovider", file)
    val resolver = context.packageManager
    val intent = Intent(Intent.ACTION_SEND)
        .setType(mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    intent.clipData = android.content.ClipData.newUri(context.contentResolver, "file", uri)
    val chooser = Intent.createChooser(intent, "分享")
    val ok = chooser.resolveActivity(resolver) != null
    if (ok) {
        runCatching { context.startActivity(chooser) }
    } else {
        Toast.makeText(context, "未找到可分享应用", Toast.LENGTH_SHORT).show()
    }
}

fun openCalendarInsert(context: android.content.Context, e: top.hsyscn.opedrgent.calendar.CalendarEventDraft) {
    val intent = Intent(Intent.ACTION_INSERT)
        .setData(CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.startEpochMs)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, e.endEpochMs)
        .putExtra(CalendarContract.Events.TITLE, e.title)
        .putExtra(CalendarContract.Events.EVENT_LOCATION, e.location)
        .putExtra(CalendarContract.Events.DESCRIPTION, e.description)
    val ok = intent.resolveActivity(context.packageManager) != null
    if (ok) {
        runCatching { context.startActivity(intent) }
    } else {
        Toast.makeText(context, "未找到日历应用", Toast.LENGTH_SHORT).show()
    }
}
