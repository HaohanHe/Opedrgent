@file:OptIn(ExperimentalMaterial3Api::class)

package top.hsyscn.opedrgent.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.provider.CalendarContract
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import android.webkit.WebSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import top.hsyscn.opedrgent.model.Role
import top.hsyscn.opedrgent.model.Skill
import top.hsyscn.opedrgent.model.MemoryEntry
import top.hsyscn.opedrgent.model.QuestionPart
import top.hsyscn.opedrgent.model.QuestionOption
import top.hsyscn.opedrgent.model.ReasoningPart
import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.model.ToolStateType
import top.hsyscn.opedrgent.calendar.CalendarEventDraft
import top.hsyscn.opedrgent.network.WebResearchMode
import top.hsyscn.opedrgent.settings.PROVIDER_PRESETS
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AppRoot(
    initialShareText: String? = null,
    onShareConsumed: () -> Unit = {},
    vm: MainViewModel = viewModel(),
) {
    val nav = rememberNavController()
    val state by vm.state.collectAsStateCompat()

    LaunchedEffect(initialShareText) {
        val t = initialShareText?.trim().orEmpty()
        if (t.isNotEmpty()) {
            vm.handleIncomingShare(t)
            onShareConsumed()
        }
    }

    LaunchedEffect(state.navigateToSessionId) {
        val id = state.navigateToSessionId
        if (!id.isNullOrBlank()) {
            nav.navigate("session/$id")
            vm.consumeNavigation()
        }
    }

    LaunchedEffect(state.openWebUrl) {
        val u = state.openWebUrl
        if (!u.isNullOrBlank()) {
            val encoded = Uri.encode(u)
            nav.navigate("web/$encoded")
            vm.consumeOpenWebUrl()
        }
    }

    LaunchedEffect(state.openBrowserUrl) {
        val u = state.openBrowserUrl
        if (!u.isNullOrBlank()) {
            val encoded = Uri.encode(u)
            nav.navigate("browser/$encoded")
            vm.consumeOpenBrowserUrl()
        }
    }

    NavHost(navController = nav, startDestination = "sessions") {
        composable("sessions") { SessionsScreen(vm = vm, toSettings = { nav.navigate("settings") }, toSession = { nav.navigate("session/$it") }) }
        composable(
            route = "session/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id").orEmpty()
            SessionScreen(vm = vm, sessionId = id, onBack = { nav.popBackStack() }, toSettings = { nav.navigate("settings") })
        }
        composable("settings") {
            SettingsScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
                toSkills = { nav.navigate("skills") },
                toAutomations = { nav.navigate("automations") },
                toMemory = { nav.navigate("memory") },
            )
        }
        composable("skills") { SkillsScreen(vm = vm, onBack = { nav.popBackStack() }) }
        composable("memory") { MemoryManagerScreen(vm = vm, onBack = { nav.popBackStack() }) }
        composable("automations") { AutomationsScreen(onBack = { nav.popBackStack() }) }
        composable(
            route = "web/{url}",
            arguments = listOf(navArgument("url") { type = NavType.StringType }),
        ) { backStackEntry ->
            val url = Uri.decode(backStackEntry.arguments?.getString("url").orEmpty())
            WebScreen(url = url, onBack = { nav.popBackStack() })
        }
        composable(
            route = "browser/{url}",
            arguments = listOf(navArgument("url") { type = NavType.StringType }),
        ) { backStackEntry ->
            val url = Uri.decode(backStackEntry.arguments?.getString("url").orEmpty())
            BrowserScreen(
                url = url,
                onBack = { nav.popBackStack() },
                onSaveSource = { vm.addUrlSource(it) },
            )
        }
    }
}

@Composable
private fun SessionsScreen(
    vm: MainViewModel,
    toSettings: () -> Unit,
    toSession: (String) -> Unit,
) {
    val state by vm.state.collectAsStateCompat()
    var createOpen by rememberSaveable { mutableStateOf(false) }
    var title by rememberSaveable { mutableStateOf("") }
    var q by rememberSaveable { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("opedrgent") },
                actions = {
                    IconButton(onClick = toSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "settings")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { createOpen = true }) {
                Icon(Icons.Default.Add, contentDescription = "new")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = q,
                onValueChange = { q = it; vm.setSessionSearchQuery(it) },
                label = { Text("搜索") },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
                singleLine = true,
            )
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.sessions, key = { it.id }) { s ->
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .fillMaxWidth(),
                        colors = CardDefaults.cardColors(),
                        onClick = { toSession(s.id) },
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(text = s.title, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(text = formatTime(s.updatedAt))
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
                    vm.createSession(title)
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
@OptIn(ExperimentalMaterial3Api::class)
private fun SessionScreen(
    vm: MainViewModel,
    sessionId: String,
    onBack: () -> Unit,
    toSettings: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val state by vm.state.collectAsStateCompat()

    var prompt by rememberSaveable { mutableStateOf("") }
    var listening by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(0) }
    var actionSheetOpen by rememberSaveable { mutableStateOf(false) }
    var addUrlOpen by rememberSaveable { mutableStateOf(false) }
    var url by rememberSaveable { mutableStateOf("") }
    var addTextOpen by rememberSaveable { mutableStateOf(false) }
    var textTitle by rememberSaveable { mutableStateOf("") }
    var textBody by rememberSaveable { mutableStateOf("") }
    var skillsOpen by rememberSaveable { mutableStateOf(false) }
    var webSearchOpen by rememberSaveable { mutableStateOf(false) }
    var webQuery by rememberSaveable { mutableStateOf("") }
    var webMode by rememberSaveable { mutableStateOf(WebResearchMode.AUTO.name) }
    var webLlmDecides by rememberSaveable { mutableStateOf(true) }
    var webUnattended by rememberSaveable { mutableStateOf(true) }
    var webAllowBrowser by rememberSaveable { mutableStateOf(false) }
    var pendingPdfUri by rememberSaveable { mutableStateOf<String?>(null) }
    var pdfModeOpen by rememberSaveable { mutableStateOf(false) }
    var pendingDocxUri by rememberSaveable { mutableStateOf<String?>(null) }
    var docxModeOpen by rememberSaveable { mutableStateOf(false) }
    var editNotesOpen by rememberSaveable { mutableStateOf(false) }
    var editNotesText by rememberSaveable { mutableStateOf("") }

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

    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingPdfUri = uri.toString()
            pdfModeOpen = true
        }
    }

    val docxPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingDocxUri = uri.toString()
            docxModeOpen = true
        }
    }

    LaunchedEffect(sessionId) {
        vm.openSession(sessionId)
    }

    LaunchedEffect(state.error) {
        val e = state.error
        if (!e.isNullOrBlank()) {
            snackbar.showSnackbar(e)
            vm.clearError()
        }
    }

    val session = state.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(session?.title ?: "研究") },
                navigationIcon = {
                    IconButton(onClick = {
                        vm.closeSession()
                        onBack()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "back")
                    }
                },
                actions = {
                    IconButton(onClick = { actionSheetOpen = true }) {
                        Icon(Icons.Default.MoreHoriz, contentDescription = "actions")
                    }
                    IconButton(onClick = toSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "settings")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (state.loading) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            if (session == null) {
                Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("会话不存在")
                }
                return@Scaffold
            }

            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("对话") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("笔记") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("来源") })
                Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("产物") })
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (tab) {
                    0 -> {
                        if (session.messages.isEmpty() && !state.isStreaming) {
                            item { Text("从提问开始。也可以先添加来源。") }
                        } else {
                            items(session.messages, key = { it.id }) { m ->
                                MessageCard(
                                    message = m,
                                    onSpeak = if (vm.isTtsEnabled()) {{ vm.toggleSpeak(m.content) }} else null,
                                    isSpeaking = state.isSpeaking,
                                    clipboard = clipboard,
                                )
                            }
                            if (state.isStreaming) {
                                item {
                                    StreamingCard(
                                        text = state.streamingText,
                                        reasoning = state.streamingReasoning,
                                        toolParts = state.streamingToolParts,
                                    )
                                }
                            }
                            if (state.activeQuestion != null) {
                                item {
                                    QuestionCard(
                                        question = state.activeQuestion!!,
                                        onAnswer = { vm.answerQuestion(it) },
                                        onDismiss = { vm.dismissQuestion() },
                                    )
                                }
                            }
                        }
                    }
                    1 -> {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Button(onClick = { vm.generateSessionNotes() }, enabled = vm.hasApiKey(), modifier = Modifier.weight(1f)) { Text("更新") }
                                Button(onClick = {
                                    editNotesText = session.notes
                                    editNotesOpen = true
                                }, modifier = Modifier.weight(1f)) { Text("编辑") }
                            }
                        }
                        item {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(text = session.notes.trim().ifEmpty { "暂无笔记。点击“更新”生成，或“编辑”手动写。"})
                                }
                            }
                        }
                    }
                    2 -> {
                        val enabledCount = session.sources.count { it.includeInContext }
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("用于回答：$enabledCount / ${session.sources.size}")
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (state.contextTokenCount > 0) {
                                        Text(
                                            text = "≈ ${state.contextTokenCount} tokens",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    TextButton(onClick = { vm.refreshContextTokenCount() }) { Text("刷新") }
                                }
                            }
                        }
                        if (session.sources.isEmpty()) {
                            item { Text("还没有来源。建议先添加 URL / PDF / 文本。") }
                        } else {
                            items(session.sources, key = { it.id }) { s ->
                                val idx = session.sources.indexOfFirst { it.id == s.id } + 1
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(text = "S$idx", fontWeight = FontWeight.SemiBold)
                                            Spacer(Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(text = s.title ?: s.url ?: "来源", fontWeight = FontWeight.SemiBold)
                                                if (!s.url.isNullOrBlank()) Text(text = s.url)
                                            }
                                            Switch(checked = s.includeInContext, onCheckedChange = { vm.setSourceIncluded(s.id, it) })
                                        }
                                        ExpandableText(text = s.content.trim().ifEmpty { "（空）" }, maxChars = 420)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            if (!s.url.isNullOrBlank()) {
                                                TextButton(onClick = { vm.openBrowser(s.url) }) { Text("打开") }
                                            }
                                            TextButton(onClick = { clipboard.setText(AnnotatedString(s.content)) }) { Text("复制") }
                                            Spacer(Modifier.weight(1f))
                                            TextButton(onClick = { vm.removeSource(s.id) }, colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        val artifacts = session.artifacts.filterNot { it.kind == top.hsyscn.opedrgent.model.ArtifactKind.NOTES }
                        if (artifacts.isEmpty()) {
                            item { Text("暂无产物。可以生成摘要/报告。") }
                        } else {
                            items(artifacts, key = { it.id }) { a ->
                                val title = when (a.kind) {
                                    top.hsyscn.opedrgent.model.ArtifactKind.SUMMARY -> "摘要"
                                    top.hsyscn.opedrgent.model.ArtifactKind.REPORT -> "报告"
                                    top.hsyscn.opedrgent.model.ArtifactKind.NOTES -> "笔记"
                                }
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(text = title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                            TextButton(onClick = { clipboard.setText(AnnotatedString(a.content)) }) { Text("复制") }
                                        }
                                        MarkdownText(text = a.content, maxChars = 900)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = state.deepThinkingEnabled,
                    onClick = { vm.toggleDeepThinking() },
                    label = { Text("快速") },
                    leadingIcon = if (state.deepThinkingEnabled) {{ Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.padding(2.dp)) }} else null,
                )
                FilterChip(
                    selected = state.deepResearchEnabled,
                    onClick = { vm.saveDeepResearch(!state.deepResearchEnabled) },
                    label = { Text("深度研究") },
                    leadingIcon = if (state.deepResearchEnabled) {{ Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.padding(2.dp)) }} else null,
                )
                Button(onClick = { vm.generateReport() }, enabled = vm.hasApiKey() && !state.isStreaming && !state.loading) { Text("帮我写") }
                Button(onClick = { actionSheetOpen = true }) { Text("更多") }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("提问/指令") },
                    modifier = Modifier.weight(1f),
                )
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
                            tint = if (listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                Button(
                    onClick = {
                        if (state.isStreaming) {
                            vm.stopGeneration()
                            return@Button
                        }
                        val p = prompt.trim()
                        when {
                            p.startsWith("/summary") -> vm.generateSummary()
                            p.startsWith("/report") -> vm.generateReport()
                            p.startsWith("/export") -> {
                                scope.launch {
                                    val f = vm.exportMarkdown()
                                    if (f != null) shareFile(context, vm.getPackageNameForShare(context), f) else snackbar.showSnackbar("导出失败")
                                }
                            }
                            p.startsWith("/url ") -> vm.addUrlSource(p.removePrefix("/url").trim())
                            p == "/skills" -> vm.listSkillsAsMessage()
                            p.startsWith("/skill ") -> vm.runSkillByName(p.removePrefix("/skill").trim())
                            else -> vm.sendUserMessage(p)
                        }
                        prompt = ""
                    },
                    enabled = state.isStreaming || (prompt.isNotBlank() && !state.loading),
                ) {
                    if (state.isStreaming) {
                        Icon(Icons.Default.FlashOn, contentDescription = "stop", tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(4.dp))
                        Text("停止", color = MaterialTheme.colorScheme.error)
                    } else {
                        Text("发送")
                    }
                }
            }
        }
    }

    if (addUrlOpen) {
        AlertDialog(
            onDismissRequest = { addUrlOpen = false },
            confirmButton = {
                Button(onClick = {
                    vm.addUrlSource(url)
                    url = ""
                    addUrlOpen = false
                }) { Text("添加") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { vm.openBrowser(url); addUrlOpen = false }) { Text("打开") }
                    TextButton(onClick = { addUrlOpen = false }) { Text("取消") }
                }
            },
            title = { Text("添加 URL") },
            text = {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
    }

    if (addTextOpen) {
        AlertDialog(
            onDismissRequest = { addTextOpen = false },
            confirmButton = {
                Button(onClick = {
                    vm.addTextSource(textTitle.takeIf { it.isNotBlank() }, textBody)
                    textTitle = ""
                    textBody = ""
                    addTextOpen = false
                }) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { addTextOpen = false }) { Text("取消") } },
            title = { Text("添加文本") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = textTitle,
                        onValueChange = { textTitle = it },
                        label = { Text("标题（可选）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = textBody,
                        onValueChange = { textBody = it },
                        label = { Text("正文") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                    )
                }
            },
        )
    }

    if (editNotesOpen) {
        AlertDialog(
            onDismissRequest = { editNotesOpen = false },
            confirmButton = {
                Button(onClick = {
                    vm.updateNotesManually(editNotesText)
                    editNotesOpen = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editNotesOpen = false }) { Text("取消") } },
            title = { Text("编辑笔记") },
            text = {
                OutlinedTextField(
                    value = editNotesText,
                    onValueChange = { editNotesText = it },
                    label = { Text("Notes") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 6,
                )
            },
        )
    }

    if (skillsOpen) {
        SkillsPickerDialog(
            skills = state.skills,
            onRun = { id -> vm.runSkill(id); skillsOpen = false },
            onList = { vm.listSkillsAsMessage(); skillsOpen = false },
            onDismiss = { skillsOpen = false },
        )
    }

    if (actionSheetOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { actionSheetOpen = false },
            sheetState = sheetState,
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("添加来源", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { addUrlOpen = true; actionSheetOpen = false }, modifier = Modifier.weight(1f)) { Text("URL") }
                    Button(onClick = { addTextOpen = true; actionSheetOpen = false }, modifier = Modifier.weight(1f)) { Text("文本") }
                    Button(onClick = { pdfPicker.launch(arrayOf("application/pdf")); actionSheetOpen = false }, modifier = Modifier.weight(1f)) { Text("PDF") }
                    Button(onClick = { docxPicker.launch(arrayOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document")); actionSheetOpen = false }, modifier = Modifier.weight(1f)) { Text("Word") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { webSearchOpen = true; actionSheetOpen = false }, enabled = vm.hasApiKey(), modifier = Modifier.weight(1f)) { Text("联网查询") }
                    Button(onClick = { vm.openBrowser("https://duckduckgo.com"); actionSheetOpen = false }, modifier = Modifier.weight(1f)) { Text("浏览器") }
                }
                HorizontalDivider()
                Text("生成", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { vm.generateSummary(); actionSheetOpen = false }, enabled = vm.hasApiKey() && !state.isStreaming && !state.loading, modifier = Modifier.weight(1f)) { Text("摘要") }
                    Button(onClick = { vm.generateReport(); actionSheetOpen = false }, enabled = vm.hasApiKey() && !state.isStreaming && !state.loading, modifier = Modifier.weight(1f)) { Text("报告") }
                    Button(onClick = { skillsOpen = true; actionSheetOpen = false }, modifier = Modifier.weight(1f)) { Text("技能") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { vm.generateSessionNotes(); actionSheetOpen = false }, enabled = vm.hasApiKey() && !state.isStreaming && !state.loading, modifier = Modifier.weight(1f)) { Text("整理笔记") }
                    Button(onClick = { vm.suggestEvolution(); actionSheetOpen = false }, enabled = vm.hasApiKey() && !state.isStreaming && !state.loading, modifier = Modifier.weight(1f)) { Text("进化") }
                }
                HorizontalDivider()
                Text("计划", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { vm.suggestAutomation(); actionSheetOpen = false }, enabled = vm.hasApiKey() && !state.isStreaming && !state.loading, modifier = Modifier.weight(1f)) { Text("心跳/自动化") }
                    Button(onClick = { vm.suggestCalendar(); actionSheetOpen = false }, enabled = vm.hasApiKey() && !state.isStreaming && !state.loading, modifier = Modifier.weight(1f)) { Text("日程" ) }
                }
                HorizontalDivider()
                Text("导出", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = {
                        scope.launch {
                            val f = vm.exportMarkdown()
                            if (f != null) shareFile(context, vm.getPackageNameForShare(context), f) else snackbar.showSnackbar("导出失败")
                        }
                        actionSheetOpen = false
                    }, modifier = Modifier.weight(1f)) { Text("会话") }
                    Button(onClick = {
                        scope.launch {
                            val f = vm.exportChatMarkdown()
                            if (f != null) shareFile(context, vm.getPackageNameForShare(context), f) else snackbar.showSnackbar("导出失败")
                        }
                        actionSheetOpen = false
                    }, modifier = Modifier.weight(1f)) { Text("聊天") }
                    Button(onClick = {
                        scope.launch {
                            val f = vm.exportContextMarkdown()
                            if (f != null) shareFile(context, vm.getPackageNameForShare(context), f) else snackbar.showSnackbar("导出失败")
                        }
                        actionSheetOpen = false
                    }, modifier = Modifier.weight(1f)) { Text("上下文") }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }

    if (webSearchOpen) {
        AlertDialog(
            onDismissRequest = { webSearchOpen = false },
            confirmButton = {
                Button(onClick = {
                    val mode = runCatching { WebResearchMode.valueOf(webMode) }.getOrNull() ?: WebResearchMode.AUTO
                    val unattended = webUnattended || webLlmDecides
                    val allowBrowser = if (unattended) false else webAllowBrowser
                    val selectedMode = if (unattended && mode == WebResearchMode.BROWSER) WebResearchMode.PROVIDER else mode
                    vm.webResearch(
                        query = webQuery,
                        mode = selectedMode,
                        llmDecides = webLlmDecides,
                        unattended = unattended,
                        allowBrowser = allowBrowser,
                    )
                    webQuery = ""
                    webSearchOpen = false
                }, enabled = !state.isStreaming && !state.loading) { Text("查询") }
            },
            dismissButton = { TextButton(onClick = { webSearchOpen = false }) { Text("取消") } },
            title = { Text("联网查询") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = webQuery,
                        onValueChange = { webQuery = it },
                        label = { Text("关键词") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "LLM 选择路由", modifier = Modifier.weight(1f))
                        Switch(checked = webLlmDecides, onCheckedChange = { webLlmDecides = it })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "无人值守", modifier = Modifier.weight(1f))
                        Switch(checked = webUnattended, onCheckedChange = { webUnattended = it }, enabled = !webLlmDecides)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { webMode = WebResearchMode.AUTO.name }, modifier = Modifier.weight(1f)) { Text("Auto") }
                        Button(onClick = { webMode = WebResearchMode.PROVIDER.name }, modifier = Modifier.weight(1f)) { Text("Provider") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { webMode = WebResearchMode.NATIVE.name }, modifier = Modifier.weight(1f)) { Text("Native") }
                        Button(onClick = { webMode = WebResearchMode.BROWSER.name }, modifier = Modifier.weight(1f), enabled = !webUnattended && !webLlmDecides) { Text("Browser") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "允许浏览器兜底（交互）", modifier = Modifier.weight(1f))
                        Switch(checked = webAllowBrowser, onCheckedChange = { webAllowBrowser = it }, enabled = !webUnattended && !webLlmDecides)
                    }
                    Text(text = "无人值守模式会禁用浏览器通道（避免登录/验证码/弹窗）。")
                }
            },
        )
    }

    if (pdfModeOpen) {
        val u = pendingPdfUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (u != null) {
            AlertDialog(
                onDismissRequest = { pdfModeOpen = false; pendingPdfUri = null },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.importPdfOcr(u); pdfModeOpen = false; pendingPdfUri = null }) { Text("OCR") }
                        Button(onClick = { vm.importPdfVision(u); pdfModeOpen = false; pendingPdfUri = null }) { Text("图片给模型") }
                    }
                },
                dismissButton = { TextButton(onClick = { pdfModeOpen = false; pendingPdfUri = null }) { Text("取消") } },
                title = { Text("PDF 处理方式") },
                text = { Text("OCR：转成文本来源；图片给模型：把前几页转成图片走多模态（需要支持图片输入的模型）。") },
            )
        }
    }

    if (docxModeOpen) {
        val u = pendingDocxUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (u != null) {
            AlertDialog(
                onDismissRequest = { docxModeOpen = false; pendingDocxUri = null },
                confirmButton = {
                    Button(onClick = { vm.importDocx(u); docxModeOpen = false; pendingDocxUri = null }) { Text("导入") }
                },
                dismissButton = { TextButton(onClick = { docxModeOpen = false; pendingDocxUri = null }) { Text("取消") } },
                title = { Text("导入 Word 文档") },
                text = { Text("将 Word 文档（.docx）解析为文本来源。") },
            )
        }
    }

    val evo = state.evolutionSuggestion
    if (evo != null) {
        AlertDialog(
            onDismissRequest = { vm.dismissEvolution() },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.acceptEvolutionMemory() }) { Text("存记忆") }
                    Button(onClick = { vm.acceptEvolutionSkill() }) { Text("存技能") }
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissEvolution() }) { Text("关闭") }
            },
            title = { Text("进化建议") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("记忆：", fontWeight = FontWeight.SemiBold)
                    Text(evo.memory.ifBlank { "（空）" })
                    Text("技能：${evo.skillName}", fontWeight = FontWeight.SemiBold)
                    Text(evo.skillPrompt.ifBlank { "（空）" })
                }
            },
        )
    }

    val auto = state.automationSuggestion
    if (auto != null) {
        AlertDialog(
            onDismissRequest = { vm.dismissAutomationSuggestion() },
            confirmButton = {
                Button(onClick = { vm.acceptAutomationSuggestion() }) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissAutomationSuggestion() }) { Text("取消") }
            },
            title = { Text("自动化建议") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("名称：${auto.name}")
                    Text("周期：${auto.intervalMinutes} 分钟")
                    Text("类型：${auto.kind.name}")
                    if (!auto.prompt.isNullOrBlank()) {
                        Text("Prompt：")
                        Text(auto.prompt)
                    }
                }
            },
        )
    }

    val cal = state.calendarSuggestion
    if (cal != null) {
        AlertDialog(
            onDismissRequest = { vm.dismissCalendarSuggestion() },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val file = vm.exportCalendarIcs(cal.events)
                        shareFile(context, vm.getPackageNameForShare(context), file, mime = "text/calendar")
                        vm.dismissCalendarSuggestion()
                    }) { Text("导出 ICS") }
                    Button(onClick = {
                        cal.events.firstOrNull()?.let { openCalendarInsert(context, it) }
                        vm.dismissCalendarSuggestion()
                    }) { Text("加到日历") }
                }
            },
            dismissButton = { TextButton(onClick = { vm.dismissCalendarSuggestion() }) { Text("取消") } },
            title = { Text("日程建议") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("最多展示前 5 条；Outlook/飞书可通过系统日历同步或导入 ICS。")
                    cal.events.forEachIndexed { idx, e ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(text = "${idx + 1}. ${e.title}", fontWeight = FontWeight.SemiBold)
                                Text(text = formatRange(e.startEpochMs, e.endEpochMs))
                                if (!e.location.isNullOrBlank()) Text(text = e.location)
                            }
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun MarkdownTable(tableLines: List<String>) {
    if (tableLines.size < 2) return
    val sepIdx = tableLines.indexOfFirst { line ->
        Regex("""\|[\s\-:\|]+\|""").matches(line.trim())
    }
    if (sepIdx < 1) return

    val headerLine = tableLines[sepIdx - 1]
    val headers = parseTableRow(headerLine)
    val aligns = parseAlignments(tableLines.getOrNull(sepIdx) ?: "")
    val rows = tableLines.drop(sepIdx + 1).filter { it.trim().isNotEmpty() && it.trim().startsWith("|") }
        .map { parseTableRow(it) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            TableRow(headers, aligns, isHeader = true)
            if (sepIdx + 1 < tableLines.size && tableLines.drop(sepIdx + 1).any { it.trim().isNotEmpty() }) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            }
            rows.forEach { cells ->
                TableRow(cells, aligns, isHeader = false)
            }
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, aligns: List<String>, isHeader: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cells.forEachIndexed { idx, cell ->
            val align = aligns.getOrNull(idx) ?: "left"
            val textAlign = when (align) {
                "center" -> androidx.compose.ui.text.style.TextAlign.Center
                "right" -> androidx.compose.ui.text.style.TextAlign.Right
                else -> androidx.compose.ui.text.style.TextAlign.Start
            }
            Text(
                text = cell.trim(),
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 3.dp),
                style = if (isHeader) MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        else MaterialTheme.typography.bodySmall,
                textAlign = textAlign,
                color = if (isHeader) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (idx < cells.lastIndex) {
                Spacer(modifier = Modifier.width(1.dp).height(16.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)))
            }
        }
    }
}

private fun parseTableRow(line: String): List<String> {
    return line.split("|").filter { it.isNotEmpty() }.map { it.trim() }
}

private fun parseAlignments(sepLine: String): List<String> {
    return sepLine.split("|").filter { it.isNotEmpty() }.map { cell ->
        val t = cell.trim()
        when {
            t.startsWith(":") && t.endsWith(":") -> "center"
            t.endsWith(":") -> "right"
            else -> "left"
        }
    }
}

private val TABLE_LINE_PATTERN = Regex("""^\s*\|""")

@Composable
private fun MarkdownText(text: String, maxChars: Int) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    val t = text.trim()
    val show = if (!expanded && t.length > maxChars) t.take(maxChars) + "…" else t

    val isCodeBlock = { line: String -> line.startsWith("```") }
    val isHeading = { line: String -> line.startsWith("#") }
    val isBullet = { line: String -> line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") }
    val isTableRow = { line: String -> TABLE_LINE_PATTERN.matches(line.trim()) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val lines = show.split("\n")
        var inCodeBlock = false
        val codeBlockLines = mutableListOf<String>()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]

            if (isCodeBlock(line)) {
                if (inCodeBlock) {
                    val code = codeBlockLines.joinToString("\n")
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    ) {
                        Text(
                            text = code,
                            modifier = Modifier.padding(8.dp),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    codeBlockLines.clear()
                    inCodeBlock = false
                } else {
                    inCodeBlock = true
                }
                i++
                continue
            }

            if (inCodeBlock) {
                codeBlockLines.add(line)
                i++
                continue
            }

            if (isTableRow(line)) {
                val tableLines = mutableListOf<String>()
                while (i < lines.size && isTableRow(lines[i])) {
                    tableLines.add(lines[i])
                    i++
                }
                MarkdownTable(tableLines)
                continue
            }

            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                Spacer(Modifier.height(4.dp))
                i++
                continue
            }

            when {
                isHeading(trimmed) -> {
                    val level = trimmed.takeWhile { it == '#' }.length
                    val headingText = trimmed.removePrefix("#".repeat(level)).trim()
                    val style = when (level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }
                    Text(text = headingText, fontWeight = FontWeight.Bold, style = style)
                }
                isBullet(trimmed) -> {
                    val bulletText = trimmed.removePrefix("- ").removePrefix("* ").trim()
                    Text(buildAnnotatedString {
                        append("• ")
                        appendMarkdownInline(bulletText)
                    })
                }
                trimmed.startsWith("[S") && trimmed.length < 10 -> {
                    Text(
                        text = trimmed,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                else -> {
                    Text(buildAnnotatedString { appendMarkdownInline(trimmed) })
                }
            }
            i++
        }
        if (inCodeBlock && codeBlockLines.isNotEmpty()) {
            val code = codeBlockLines.joinToString("\n")
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            ) {
                Text(
                    text = code,
                    modifier = Modifier.padding(8.dp),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (t.length > maxChars) {
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "展开") }
        }
    }
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendMarkdownInline(text: String) {
    val boldPattern = Regex("""\*\*(.+?)\*\*""")
    val italicPattern = Regex("""\*(.+?)\*""")
    val codePattern = Regex("""`(.+?)`""")
    val citationPattern = Regex("""\[S\d+]""")

    var remaining = text
    while (remaining.isNotEmpty()) {
        val firstBold = boldPattern.find(remaining)
        val firstItalic = italicPattern.find(remaining)
        val firstCode = codePattern.find(remaining)
        val firstCitation = citationPattern.find(remaining)

        val first = listOfNotNull(firstBold, firstItalic, firstCode, firstCitation).minByOrNull { it.range.first }

        if (first == null) {
            append(remaining)
            break
        }

        if (first.range.first > 0) {
            append(remaining.substring(0, first.range.first))
        }

        when (first) {
            firstBold -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(first.groupValues[1])
                }
                remaining = remaining.substring(first.range.last + 1)
            }
            firstItalic -> {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(first.groupValues[1])
                }
                remaining = remaining.substring(first.range.last + 1)
            }
            firstCode -> {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                    append(first.groupValues[1])
                }
                remaining = remaining.substring(first.range.last + 1)
            }
            firstCitation -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color(0xFF1976D2))) {
                    append(first.value)
                }
                remaining = remaining.substring(first.range.last + 1)
            }
            else -> {
                append(remaining)
                break
            }
        }
    }
}

@Composable
private fun ExpandableText(text: String, maxChars: Int) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    val t = text.trim()
    val show = if (!expanded && t.length > maxChars) t.take(maxChars) + "…" else t
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(show)
        if (t.length > maxChars) {
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "展开") }
        }
    }
}

@Composable
private fun SkillsPickerDialog(
    skills: List<Skill>,
    onRun: (String) -> Unit,
    onList: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onList) { Text("列出 /skills") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        title = { Text("选择技能") },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
            ) {
                items(skills, key = { it.id }) { s ->
                    Card(onClick = { onRun(s.id) }, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(s.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Icon(Icons.Default.PlayArrow, contentDescription = "run")
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit, toSkills: () -> Unit, toAutomations: () -> Unit, toMemory: () -> Unit) {
    var baseUrl by rememberSaveable { mutableStateOf(vm.getBaseUrl()) }
    var model by rememberSaveable { mutableStateOf(vm.getModel()) }
    var apiKey by rememberSaveable { mutableStateOf("") }
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
    var providerMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var modelMenuExpanded by rememberSaveable { mutableStateOf(false) }
    val state by vm.state.collectAsStateCompat()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
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
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(12.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("模型供应商", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
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

            HorizontalDivider()

            Text("记忆管理", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = toMemory,
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
            Card(modifier = Modifier.fillMaxWidth()) {
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
                        Button(onClick = { ttsRate = 0.85f }, enabled = ttsEnabled) { Text("慢") }
                        Button(onClick = { ttsRate = 1.0f }, enabled = ttsEnabled) { Text("正常") }
                        Button(onClick = { ttsRate = 1.2f }, enabled = ttsEnabled) { Text("快") }
                    }
                    Text(text = "语言")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { ttsLocaleTag = "zh-CN" }, enabled = ttsEnabled) { Text("中文") }
                        Button(onClick = { ttsLocaleTag = "en-US" }, enabled = ttsEnabled) { Text("英文") }
                    }
                }
            }

            HorizontalDivider()

            Text("后台运行", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            Card(modifier = Modifier.fillMaxWidth()) {
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
            Card(modifier = Modifier.fillMaxWidth()) {
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

            Button(
                onClick = {
                    vm.saveTts(
                        enabled = ttsEnabled,
                        autoSpeak = ttsAuto,
                        rate = ttsRate,
                        pitch = ttsPitch,
                        localeTag = ttsLocaleTag,
                    )
                    vm.saveSttEnabled(sttEnabled)
                    vm.saveBackgroundRunning(bgRunning)
                    vm.saveLocationEnabled(locationEnabled)
                    vm.saveDebugMode(debugMode)
                    vm.saveDeepThinking(deepThinkingEnabled)
                    val ok = vm.saveSettings(baseUrl = baseUrl, apiKey = apiKey, model = model)
                    if (!ok) return@Button
                    onBack()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("保存")
            }
            Text(text = "API Key 留空表示不修改。", modifier = Modifier.padding(top = 4.dp))
            HorizontalDivider()
            Button(
                onClick = { vm.clearApiKey() },
                modifier = Modifier.fillMaxWidth(),
                enabled = vm.hasApiKey(),
            ) {
                Text("清除 API Key")
            }
            Button(onClick = toSkills, modifier = Modifier.fillMaxWidth()) {
                Text("技能库")
            }
            Button(onClick = toAutomations, modifier = Modifier.fillMaxWidth()) {
                Text("自动化/心跳")
            }
            if (vm.hasApiKey()) {
                Text("已保存 Key（不显示）。如需更换，重新填写并保存。")
            } else {
                Text("未设置 Key。")
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
}

private fun shareFile(context: android.content.Context, packageName: String, file: File, mime: String = "text/markdown") {
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

private fun formatRange(startMs: Long, endMs: Long): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return "${fmt.format(Date(startMs))} - ${fmt.format(Date(endMs))}"
}

private fun openCalendarInsert(context: android.content.Context, e: CalendarEventDraft) {
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

@Composable
private fun MemoryManagerScreen(vm: MainViewModel, onBack: () -> Unit) {
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
private fun SkillsScreen(vm: MainViewModel, onBack: () -> Unit) {
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
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.skills, key = { it.id }) { s ->
                Card(
                    modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth(),
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

@Composable
private fun WebScreen(url: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            webViewRef.value?.stopLoading()
            webViewRef.value?.destroy()
            webViewRef.value = null
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("网页") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "back")
                    }
                },
            )
        },
    ) { padding ->
        AndroidView(
            modifier = Modifier.padding(padding).fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    webViewRef.value = this
                    hardenWebView(this)
                    settings.javaScriptEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                            val u = request?.url?.toString().orEmpty()
                            if (u.startsWith("http://") || u.startsWith("https://")) return false
                            Toast.makeText(context, "已拦截非 http(s) 链接", Toast.LENGTH_SHORT).show()
                            return true
                        }
                    }
                    loadUrl(url)
                }
            },
            update = { v ->
                if (v.url != url) v.loadUrl(url)
            },
        )
    }
}

@Composable
private fun BrowserScreen(
    url: String,
    onBack: () -> Unit,
    onSaveSource: (String) -> Unit,
) {
    var input by rememberSaveable { mutableStateOf(url) }
    var current by rememberSaveable { mutableStateOf(url) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    val webViewHolder = remember { mutableStateOf<WebView?>(null) }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            webViewHolder.value?.stopLoading()
            webViewHolder.value?.destroy()
            webViewHolder.value = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("浏览器") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "back")
                    }
                },
                actions = {
                    TextButton(onClick = { if (current.isNotBlank()) onSaveSource(current) }) { Text("保存来源") }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("URL") },
                modifier = Modifier.padding(12.dp).fillMaxWidth(),
                singleLine = true,
            )
            Row(
                modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = { current = input.trim() }, enabled = input.isNotBlank()) { Text("打开") }
                Text(text = if (progress in 1..99) "$progress%" else "", modifier = Modifier.weight(1f))
                IconButton(onClick = { webViewHolder.value?.goBack() }, enabled = canGoBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "back")
                }
                IconButton(onClick = { webViewHolder.value?.goForward() }, enabled = canGoForward) {
                    Icon(Icons.Default.ArrowForward, contentDescription = "forward")
                }
                IconButton(onClick = { webViewHolder.value?.reload() }, enabled = current.isNotBlank()) {
                    Icon(Icons.Default.Refresh, contentDescription = "refresh")
                }
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        webViewHolder.value = this
                        hardenWebView(this)
                        settings.javaScriptEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                                val u = request?.url?.toString().orEmpty()
                                if (u.startsWith("http://") || u.startsWith("https://")) return false
                                Toast.makeText(context, "已拦截非 http(s) 链接", Toast.LENGTH_SHORT).show()
                                return true
                            }
                            override fun onPageFinished(view: WebView?, url: String?) {
                                current = url ?: current
                                input = current
                                canGoBack = this@apply.canGoBack()
                                canGoForward = this@apply.canGoForward()
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                            }
                        }
                        loadUrl(current)
                    }
                },
                update = { v ->
                    if (v.url != current && current.isNotBlank()) {
                        v.loadUrl(current)
                    }
                    canGoBack = v.canGoBack()
                    canGoForward = v.canGoForward()
                },
            )
        }
    }
}

private fun hardenWebView(w: WebView) {
    val s = w.settings
    s.allowFileAccess = false
    s.allowContentAccess = false
    s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        s.safeBrowsingEnabled = true
    }
    s.javaScriptCanOpenWindowsAutomatically = false
    s.setSupportMultipleWindows(false)
    w.isHapticFeedbackEnabled = false
}

@Composable
private fun MessageCard(
    message: top.hsyscn.opedrgent.model.ChatMessage,
    onSpeak: (() -> Unit)?,
    isSpeaking: Boolean,
    clipboard: androidx.compose.ui.platform.ClipboardManager,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when (message.role) {
                        Role.USER -> "我"
                        Role.ASSISTANT -> "助手"
                        Role.SYSTEM -> "系统"
                    },
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (message.role == Role.ASSISTANT && onSpeak != null) {
                    IconButton(onClick = onSpeak) {
                        Icon(
                            if (isSpeaking) Icons.Default.FlashOn else Icons.Default.VolumeUp,
                            contentDescription = "tts",
                            tint = if (isSpeaking) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                TextButton(onClick = { clipboard.setText(AnnotatedString(message.content)) }) { Text("复制") }
            }
            if (message.reasoningParts.isNotEmpty()) {
                ThinkingSection(parts = message.reasoningParts)
            }
            if (message.toolParts.isNotEmpty()) {
                message.toolParts.forEach { tp ->
                    ToolCard(toolPart = tp)
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
            if (message.questionPart != null) {
                QuestionCard(
                    question = message.questionPart!!,
                    onAnswer = {},
                    onDismiss = {},
                    readonly = true,
                )
            }
            MarkdownText(text = message.content, maxChars = 900)
        }
    }
}

@Composable
private fun StreamingCard(
    text: String,
    reasoning: String,
    toolParts: List<ToolPart>,
) {
    var animatedText by remember(text) { mutableStateOf(text) }
    val displayText = remember(animatedText) { animatedText.trimEnd() }
    val animating = remember { mutableStateOf(false) }

    LaunchedEffect(text) {
        if (text.length > animatedText.length) {
            animating.value = true
            val newChars = text.substring(animatedText.length)
            for (ch in newChars) {
                animatedText += ch
                val delay = when {
                    ch == '\n' -> 30L
                    ch in listOf('.', '。', '!', '！', '?', '？', ',', '，', '、') -> 40L
                    ch == ' ' -> 5L
                    else -> (20L..50L).random()
                }
                kotlinx.coroutines.delay(delay)
            }
            animating.value = false
        } else if (text.length < animatedText.length) {
            animatedText = text
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("助手", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (animating.value) {
                    CircularProgressIndicator(modifier = Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp)
                }
            }
            if (reasoning.isNotEmpty()) {
                ThinkingSection(parts = listOf(ReasoningPart(text = reasoning)))
            }
            if (toolParts.isNotEmpty()) {
                toolParts.forEach { tp ->
                    ToolCard(toolPart = tp)
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
            if (displayText.isNotEmpty()) {
                Text(
                    text = displayText,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ThinkingSection(parts: List<ReasoningPart>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val combined = parts.joinToString("\n") { it.text }
    Column {
        TextButton(onClick = { expanded = !expanded }) {
            Icon(Icons.Default.AutoAwesome, contentDescription = "thinking", modifier = Modifier.height(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(if (expanded) "收起思考过程" else "查看思考过程", style = MaterialTheme.typography.bodySmall)
        }
        if (expanded) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ),
            ) {
                Text(
                    text = combined,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ToolCard(toolPart: ToolPart) {
    var expanded by rememberSaveable { mutableStateOf(toolPart.state.status == ToolStateType.RUNNING) }
    val statusIcon = when (toolPart.state.status) {
        ToolStateType.PENDING -> "\u23F3"
        ToolStateType.RUNNING -> "\uD83D\uDD04"
        ToolStateType.COMPLETED -> "\u2705"
        ToolStateType.ERROR -> "\u274C"
        ToolStateType.SOURCE_ADDED -> "\uD83D\uDCCE"
    }
    val statusColor = when (toolPart.state.status) {
        ToolStateType.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
        ToolStateType.RUNNING -> MaterialTheme.colorScheme.primary
        ToolStateType.COMPLETED -> MaterialTheme.colorScheme.tertiary
        ToolStateType.ERROR -> MaterialTheme.colorScheme.error
        ToolStateType.SOURCE_ADDED -> MaterialTheme.colorScheme.secondary
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        ),
        onClick = { expanded = !expanded },
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = statusIcon)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = toolPart.tool,
                    fontWeight = FontWeight.SemiBold,
                    color = statusColor,
                    modifier = Modifier.weight(1f),
                )
                if (toolPart.state.status == ToolStateType.RUNNING) {
                    CircularProgressIndicator(modifier = Modifier.height(14.dp).width(14.dp), strokeWidth = 2.dp)
                }
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                if (toolPart.state.input.isNotEmpty()) {
                    Text("参数：${toolPart.state.input.entries.joinToString(", ") { "${it.key}=${it.value}" }}",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (!toolPart.state.output.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = toolPart.state.output!!.take(500) + if (toolPart.state.output!!.length > 500) "…" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (!toolPart.state.error.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(text = "错误：${toolPart.state.error}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun QuestionCard(
    question: QuestionPart,
    onAnswer: (String) -> Unit,
    onDismiss: () -> Unit,
    readonly: Boolean = false,
) {
    var selected by rememberSaveable { mutableStateOf(setOf<String>()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = question.prompt.ifEmpty { "请选择：" }, fontWeight = FontWeight.SemiBold)
            if (question.multiSelect) {
                Text("（多选）", style = MaterialTheme.typography.bodySmall)
            }
            question.options.forEach { opt ->
                FilterChip(
                    selected = opt.value in selected,
                    onClick = {
                        if (readonly) return@FilterChip
                        if (question.multiSelect) {
                            selected = if (opt.value in selected) selected - opt.value else selected + opt.value
                        } else {
                            selected = setOf(opt.value)
                        }
                    },
                    label = { Text(opt.label) },
                )
            }
            if (!readonly) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val answer = if (question.multiSelect) selected.joinToString(",") else selected.firstOrNull() ?: ""
                            if (answer.isNotBlank()) onAnswer(answer)
                        },
                        enabled = selected.isNotEmpty(),
                    ) { Text("确认") }
                    TextButton(onClick = onDismiss) { Text("跳过") }
                }
            }
            if (readonly && question.answer != null) {
                Text("回答：${question.answer}", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
