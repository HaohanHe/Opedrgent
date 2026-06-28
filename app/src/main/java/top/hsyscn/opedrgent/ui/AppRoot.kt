@file:OptIn(ExperimentalMaterial3Api::class)

package top.hsyscn.opedrgent.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import android.content.pm.PackageManager
import android.provider.CalendarContract

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Note
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import top.hsyscn.opedrgent.model.Role
import top.hsyscn.opedrgent.model.MessageType
import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.settings.PROVIDER_PRESETS
import top.hsyscn.opedrgent.utils.DebugLog
import top.hsyscn.opedrgent.ui.theme.customColors
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import top.hsyscn.opedrgent.ui.theme.SpacingTokens
import top.hsyscn.opedrgent.ui.components.MarkdownText
import top.hsyscn.opedrgent.ui.components.StreamingCard
import top.hsyscn.opedrgent.ui.components.QuestionCard
import top.hsyscn.opedrgent.ui.components.QuestionDock
import top.hsyscn.opedrgent.ui.components.ConfirmationDialog
import top.hsyscn.opedrgent.ui.components.ConfirmationRequest
import top.hsyscn.opedrgent.ui.components.FeedbackController
import top.hsyscn.opedrgent.ui.components.LocalFeedbackController
import top.hsyscn.opedrgent.ui.components.UserBubble
import top.hsyscn.opedrgent.ui.components.SttProgressDialog
import top.hsyscn.opedrgent.ui.components.SttResultCard
import androidx.compose.ui.res.stringResource
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.ui.components.InputMode
import top.hsyscn.opedrgent.ui.components.InputModeBar
import top.hsyscn.opedrgent.ui.components.RecordingState

import top.hsyscn.opedrgent.ui.components.MessageBodyInfo
import top.hsyscn.opedrgent.ui.components.MessageBodyConfigUpdate
import top.hsyscn.opedrgent.ui.components.MessageBodyError
import top.hsyscn.opedrgent.llm.LocalLlmEngine
import top.hsyscn.opedrgent.llm.LocalLlmState
import top.hsyscn.opedrgent.llm.ModelDownloadManager
import top.hsyscn.opedrgent.note.NoteType
import top.hsyscn.opedrgent.storage.HippocampusIndex
import top.hsyscn.opedrgent.llm.AvailableLocalModels
import top.hsyscn.opedrgent.ui.components.ModelSelectorDialog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val appTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

@Composable
fun BadgeDot(
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    if (visible) {
        Box(
            modifier = modifier
                .size(SpacingTokens.sm)
                .background(MaterialTheme.customColors.dangerRed, CircleShape)
        )
    }
}

enum class MainTab { HOME, NOTES, RECORDING, AI, SETTINGS }

@Composable
fun AppRoot(
    initialShareText: String? = null,
    initialAction: String? = null,
    onShareConsumed: () -> Unit = {},
    onActionConsumed: () -> Unit = {},
    vm: MainViewModel = viewModel(),
) {
    var selectedTab by rememberSaveable { mutableStateOf(MainTab.HOME) }
    var selectedSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var subScreen by rememberSaveable { mutableStateOf<String?>(null) }
    var editorTeamInitialInput by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val hippocampus = remember { HippocampusIndex(context) }

    // Inject hippocampus index into MainViewModel
    LaunchedEffect(Unit) {
        vm.hippocampus = hippocampus
        vm.refreshPendingCounts()
    }

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
                "meeting" -> {
                    selectedTab = MainTab.RECORDING
                }
                "new_chat" -> {
                    vm.createSessionAndNavigate("新对话")
                    selectedTab = MainTab.AI
                }
            }
            onActionConsumed()
        }
    }

    LaunchedEffect(state.navigateToSessionId) {
        val id = state.navigateToSessionId
        if (!id.isNullOrBlank()) {
            selectedSessionId = id
            selectedTab = MainTab.AI
            vm.openSession(id)
            vm.consumeNavigation()
        }
    }

    // ★ P4-2 修复：消费 openBrowserUrl，真正打开系统浏览器
    val browserContext = LocalContext.current
    LaunchedEffect(state.openBrowserUrl) {
        val url = state.openBrowserUrl
        if (!url.isNullOrBlank()) {
            try {
                browserContext.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
            } catch (e: Exception) {
                DebugLog.e("openBrowserUrl: failed to open $url: ${e.message}")
            }
            vm.consumeOpenBrowserUrl()
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val feedbackController = remember(snackbarHostState, scope) {
        FeedbackController(snackbarHostState, scope)
    }

    // 全局返回键处理：子页面 -> 返回上一层；非首页 Tab -> 回到首页；首页 -> 双击退出
    var lastBackPressedTime by remember { mutableLongStateOf(0L) }
    BackHandler {
        when {
            subScreen != null -> {
                subScreen = when {
                    subScreen?.startsWith("noteEditor_") == true -> "notes"
                    subScreen?.startsWith("noteReader_") == true -> "notes"
                    subScreen?.startsWith("noteShare_") == true -> "notes"
                    subScreen?.startsWith("noteSprout_") == true -> "notes"
                    subScreen == "noteGraph" -> "notes"
                    else -> null
                }
            }
            selectedTab != MainTab.HOME -> {
                selectedTab = MainTab.HOME
            }
            else -> {
                val now = System.currentTimeMillis()
                if (now - lastBackPressedTime > 2000L) {
                    lastBackPressedTime = now
                    scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.exit_press_again)) }
                } else {
                    (context as? Activity)?.finish()
                }
            }
        }
    }

    // 观察 ViewModel 的通用反馈消息，统一以 Snackbar 展示（替代 ViewModel 内 Toast）
    LaunchedEffect(Unit) {
        vm.feedbackMessage.collect { msg ->
            if (!msg.isNullOrBlank()) {
                snackbarHostState.showSnackbar(msg)
                vm.consumeFeedback()
            }
        }
    }

    val activity = LocalContext.current as? android.app.Activity
    @OptIn(androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi::class)
    val windowSizeClass = activity?.let { calculateWindowSizeClass(it) }
    val isLandscape = windowSizeClass?.widthSizeClass != WindowWidthSizeClass.Compact

    CompositionLocalProvider(LocalFeedbackController provides feedbackController) {
    Row(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (isLandscape) {
            NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                val navLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                val navSelectedLabelColor = MaterialTheme.colorScheme.primary
                NavigationRailItem(
                    icon = { Icon(Icons.Default.Home, contentDescription = stringResource(R.string.tab_home)) },
                    label = { Text(stringResource(R.string.tab_home), color = if (selectedTab == MainTab.HOME) navSelectedLabelColor else navLabelColor) },
                    selected = selectedTab == MainTab.HOME,
                    onClick = { selectedTab = MainTab.HOME; subScreen = null },
                )
                NavigationRailItem(
                    icon = { Icon(Icons.Default.Note, contentDescription = stringResource(R.string.tab_notes)) },
                    label = { Text(stringResource(R.string.tab_notes), color = if (selectedTab == MainTab.NOTES) navSelectedLabelColor else navLabelColor) },
                    selected = selectedTab == MainTab.NOTES,
                    onClick = { selectedTab = MainTab.NOTES; subScreen = null },
                )
                NavigationRailItem(
                    icon = { Icon(Icons.Default.Mic, contentDescription = stringResource(R.string.tab_recording)) },
                    label = { Text(stringResource(R.string.tab_recording), color = if (selectedTab == MainTab.RECORDING) navSelectedLabelColor else navLabelColor) },
                    selected = selectedTab == MainTab.RECORDING,
                    onClick = { selectedTab = MainTab.RECORDING; subScreen = null },
                )
                NavigationRailItem(
                    icon = {
                        Box(modifier = Modifier.size(SpacingTokens.xl), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Chat, contentDescription = stringResource(R.string.tab_ai))
                            BadgeDot(
                                visible = state.pendingMessageCount > 0,
                                modifier = Modifier.align(Alignment.TopEnd).offset(x = SpacingTokens.xs, y = -SpacingTokens.xs)
                            )
                        }
                    },
                    label = { Text(stringResource(R.string.tab_ai), color = if (selectedTab == MainTab.AI) navSelectedLabelColor else navLabelColor) },
                    selected = selectedTab == MainTab.AI,
                    onClick = { selectedTab = MainTab.AI; subScreen = null },
                )
                NavigationRailItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.tab_settings)) },
                    label = { Text(stringResource(R.string.tab_settings), color = if (selectedTab == MainTab.SETTINGS) navSelectedLabelColor else navLabelColor) },
                    selected = selectedTab == MainTab.SETTINGS,
                    onClick = { selectedTab = MainTab.SETTINGS; subScreen = null },
                )
            }
        }
        Scaffold(
            modifier = Modifier.weight(1f),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (!isLandscape) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                        windowInsets = WindowInsets(0, 0, 0, 0)
                    ) {
                        val navLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        val navSelectedLabelColor = MaterialTheme.colorScheme.primary
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.Home, contentDescription = stringResource(R.string.tab_home)) },
                            label = { Text(stringResource(R.string.tab_home), color = if (selectedTab == MainTab.HOME) navSelectedLabelColor else navLabelColor) },
                            selected = selectedTab == MainTab.HOME,
                            onClick = { selectedTab = MainTab.HOME; subScreen = null },
                        )
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.Note, contentDescription = stringResource(R.string.tab_notes)) },
                            label = { Text(stringResource(R.string.tab_notes), color = if (selectedTab == MainTab.NOTES) navSelectedLabelColor else navLabelColor) },
                            selected = selectedTab == MainTab.NOTES,
                            onClick = { selectedTab = MainTab.NOTES; subScreen = null },
                        )
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.Mic, contentDescription = stringResource(R.string.tab_recording)) },
                            label = { Text(stringResource(R.string.tab_recording), color = if (selectedTab == MainTab.RECORDING) navSelectedLabelColor else navLabelColor) },
                            selected = selectedTab == MainTab.RECORDING,
                            onClick = { selectedTab = MainTab.RECORDING; subScreen = null },
                        )
                        NavigationBarItem(
                            icon = {
                                Box(modifier = Modifier.size(SpacingTokens.xl), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Chat, contentDescription = stringResource(R.string.tab_ai))
                                    BadgeDot(
                                        visible = state.pendingMessageCount > 0,
                                        modifier = Modifier.align(Alignment.TopEnd).offset(x = SpacingTokens.xs, y = -SpacingTokens.xs)
                                    )
                                }
                            },
                            label = { Text(stringResource(R.string.tab_ai), color = if (selectedTab == MainTab.AI) navSelectedLabelColor else navLabelColor) },
                            selected = selectedTab == MainTab.AI,
                            onClick = { selectedTab = MainTab.AI; subScreen = null },
                        )
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.tab_settings)) },
                            label = { Text(stringResource(R.string.tab_settings), color = if (selectedTab == MainTab.SETTINGS) navSelectedLabelColor else navLabelColor) },
                            selected = selectedTab == MainTab.SETTINGS,
                            onClick = { selectedTab = MainTab.SETTINGS; subScreen = null },
                        )
                    }
                }
            },
        ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (subScreen) {
                "memory" -> MemoryManagerScreen(vm = vm, onBack = { subScreen = null })
                "hippocampus" -> HippocampusScreen(hippocampus = hippocampus, onBack = { subScreen = null })
                "opensource" -> OpenSourceScreen(onBack = { subScreen = null })
                "skills" -> SkillsScreen(vm = vm, onBack = { subScreen = null })
                "vocabulary" -> VocabularySettingsScreen(onBack = { subScreen = null })
                "automations" -> top.hsyscn.opedrgent.ui.AutomationsScreen(onBack = { subScreen = null })
                "meeting" -> MeetingRecordScreen(
                    vm = vm,
                    onBack = { subScreen = null },
                    onSendToChat = { text ->
                        val structuredPrompt = buildString {
                            appendLine("请对以下内容进行结构化总结，按以下四个部分输出，每部分用【标记】开头：")
                            appendLine("【智能总结】用2-3句话概括核心内容")
                            appendLine("【章节概要】按主题分段，每段配小标题")
                            appendLine("【金句精选】提取3-5句关键原话")
                            appendLine("【待办事项】列出所有需要后续跟进的事项")
                            appendLine()
                            appendLine("内容如下：")
                            append(text)
                        }
                        vm.sendUserMessage(structuredPrompt)
                        selectedTab = MainTab.AI
                        subScreen = null
                    },
                )
                "knowledge" -> KnowledgeBaseScreen(vm = vm, onBack = { subScreen = null })
                "interview" -> InterviewScreen(
                    vm = vm,
                    onBack = { subScreen = null },
                )
                // 修复：添加导入文件功能处理（原来点击"导入文件"按钮无响应）
                "import" -> ImportFileScreen(
                    vm = vm,
                    onBack = { subScreen = null },
                    onImportSuccess = { noteId ->
                        // 导入成功后跳转到笔记编辑器
                        subScreen = "noteEditor_$noteId"
                    },
                )
                "export" -> ExportScreen(vm = vm, onBack = { subScreen = null })
                "voiceprint" -> VoiceprintSettingsScreen(
                    onBack = { subScreen = null },
                    onAddVoiceprint = { subScreen = "voiceprintEnroll" },
                )
                "voiceprintEnroll" -> VoiceprintEnrollmentScreen(
                    onBack = { subScreen = "voiceprint" },
                    onEnrollmentComplete = { subScreen = "voiceprint" },
                )
                "editorTeam" -> EditorTeamScreen(
                    vm = vm,
                    initialInput = editorTeamInitialInput,
                    onBack = { subScreen = null },
                )
                "notes" -> NoteListScreen(
                    repository = vm.noteRepository,
                    folderRepository = vm.folderRepository,
                    onNoteClick = { noteId -> subScreen = "noteReader_$noteId" },
                    onNewNote = { subScreen = "noteEditor_new" },
                    onBack = { subScreen = null },
                    onShareNote = { noteId -> subScreen = "noteShare_$noteId" },
                    onSproutNote = { noteId -> subScreen = "noteSprout_$noteId" },
                    onGraphClick = { subScreen = "noteGraph" },
                    onSendToChat = { noteId ->
                        vm.sendNoteToChat(noteId)
                        selectedTab = MainTab.AI
                        subScreen = null
                    },
                    onSendWithSkill = { noteId, skillId ->
                        vm.sendNoteWithSkill(noteId, skillId)
                        selectedTab = MainTab.AI
                        subScreen = null
                    },
                    onEditNote = { noteId -> subScreen = "noteEditor_$noteId" },
                    onAppendNote = { noteId -> subScreen = "noteEditor_$noteId" },
                    onCorrectNote = { noteId ->
                        vm.correctNote(noteId)
                        selectedTab = MainTab.AI
                        subScreen = null
                    },
                    onAddToKnowledgeBase = { noteId -> vm.addNoteToKnowledgeBase(noteId) },
                    onAddTag = { noteId -> subScreen = "noteEditor_$noteId" },
                    aiSearchResults = vm.state.value.aiSearchResults,
                    isAiSearching = vm.state.value.isAiSearching,
                    onAiSearch = { vm.aiSearch(it) },
                    onClearAiSearch = { vm.clearAiSearch() },
                    searchHistory = vm.getSearchHistory(),
                    onClearSearchHistory = { vm.clearSearchHistory() },
                    isLandscape = isLandscape,
                )
                "noteGraph" -> NoteGraphScreen(
                    repository = vm.noteRepository,
                    onNoteClick = { noteId -> subScreen = "noteReader_$noteId" },
                    onBack = { subScreen = "notes" },
                )
                // 统一 NoteEditor 路由：从 subScreen 解析 initialType（替代原来6个重复分支）
                in listOf("noteEditor_new", "noteEditor_new_text", "noteEditor_new_quick", "noteEditor_new_link", "noteEditor_new_image", "noteEditor_new_pdf") -> {
                    val initialType = when (subScreen) {
                        "noteEditor_new_text" -> top.hsyscn.opedrgent.note.NoteType.TEXT
                        "noteEditor_new_quick" -> top.hsyscn.opedrgent.note.NoteType.QUICK
                        "noteEditor_new_link" -> top.hsyscn.opedrgent.note.NoteType.LINK
                        "noteEditor_new_image" -> top.hsyscn.opedrgent.note.NoteType.IMAGE
                        "noteEditor_new_pdf" -> top.hsyscn.opedrgent.note.NoteType.PDF
                        else -> null
                    }
                    NoteEditorScreen(
                        repository = vm.noteRepository,
                        initialType = initialType ?: NoteType.TEXT,
                        onSaved = { noteId -> subScreen = "noteEditor_$noteId" },
                        onSendToChat = { noteId ->
                            vm.sendNoteToChat(noteId)
                            selectedTab = MainTab.AI
                            subScreen = null
                        },
                        onSendWithSkill = { noteId, skillId ->
                            vm.sendNoteWithSkill(noteId, skillId)
                            selectedTab = MainTab.AI
                            subScreen = null
                        },
                        onSproutNote = { noteId -> subScreen = "noteSprout_$noteId" },
                        onOpenEditorTeam = { input ->
                            editorTeamInitialInput = input
                            subScreen = "editorTeam"
                        },
                        onBack = { subScreen = "notes" },
                        onCorrectNote = { noteId ->
                            vm.correctNote(noteId)
                            selectedTab = MainTab.AI
                            subScreen = null
                        },
                        onAddToKnowledgeBase = { noteId -> vm.addNoteToKnowledgeBase(noteId) },
                        onAddTag = { noteId -> /* 已在编辑器内处理 */ },
                        onAppendNote = { noteId -> /* 已在编辑器内处理 */ },
                        editorMode = vm.getEditorMode(),
                        sproutService = top.hsyscn.opedrgent.note.SproutService(vm.apiSettings, hippocampus),
                    )
                }
                null -> {
                    when (selectedTab) {
                        MainTab.HOME -> HomeDashboardScreen(
                            vm = vm,
                            repository = vm.noteRepository,
                            folderRepository = vm.folderRepository,
                            onNewNote = { subScreen = "noteEditor_new" },
                            onNoteClick = { noteId -> subScreen = "noteReader_$noteId" },
                            onSendToChat = { noteId ->
                                vm.sendNoteToChat(noteId)
                                selectedTab = MainTab.AI
                            },
                            onSendWithSkill = { noteId, skillId ->
                                vm.sendNoteWithSkill(noteId, skillId)
                                selectedTab = MainTab.AI
                            },
                            onOpenSubScreen = { subScreen = it },
                            onNavigateToAi = {
                                selectedTab = MainTab.AI
                                if (vm.state.value.current == null) {
                                    vm.createSessionAndNavigate("新对话")
                                }
                            },
                            onNavigateToNotes = { selectedTab = MainTab.NOTES },
                            // 修复：传递推荐卡片回调，确保用户点击推荐卡片能正确跳转
                            onOpenEditorTeam = {
                                editorTeamInitialInput = ""
                                subScreen = "editorTeam"
                            },
                            onNavigateToRecording = {
                                selectedTab = MainTab.RECORDING
                            },
                            onNavigateToKnowledge = {
                                subScreen = "knowledge"
                            },
                            // 面试模式导航回调
                            onNavigateToInterview = {
                                subScreen = "interview"
                            },
                            onNavigateToSearch = { selectedTab = MainTab.NOTES },
                        )
                        MainTab.NOTES -> NoteListScreen(
                            repository = vm.noteRepository,
                            folderRepository = vm.folderRepository,
                            onNoteClick = { noteId -> subScreen = "noteReader_$noteId" },
                            onNewNote = { subScreen = "noteEditor_new" },
                            onBack = {},
                            onShareNote = { noteId -> subScreen = "noteShare_$noteId" },
                            onSproutNote = { noteId -> subScreen = "noteSprout_$noteId" },
                            onGraphClick = { subScreen = "noteGraph" },
                            onSendToChat = { noteId ->
                                vm.sendNoteToChat(noteId)
                                selectedTab = MainTab.AI
                            },
                            onSendWithSkill = { noteId, skillId ->
                                vm.sendNoteWithSkill(noteId, skillId)
                                selectedTab = MainTab.AI
                            },
                            onEditNote = { noteId -> subScreen = "noteEditor_$noteId" },
                            onAppendNote = { noteId -> subScreen = "noteEditor_$noteId" },
                            onCorrectNote = { noteId ->
                                vm.correctNote(noteId)
                                selectedTab = MainTab.AI
                            },
                            onAddToKnowledgeBase = { noteId -> vm.addNoteToKnowledgeBase(noteId) },
                            onAddTag = { noteId -> subScreen = "noteEditor_$noteId" },
                            showBackButton = false,
                            aiSearchResults = vm.state.value.aiSearchResults,
                            isAiSearching = vm.state.value.isAiSearching,
                            onAiSearch = { vm.aiSearch(it) },
                            onClearAiSearch = { vm.clearAiSearch() },
                            searchHistory = vm.getSearchHistory(),
                            onClearSearchHistory = { vm.clearSearchHistory() },
                            isLandscape = isLandscape,
                        )
                        MainTab.RECORDING -> RecordingTab(
                            vm = vm,
                            onOpenSubScreen = { subScreen = it },
                            onNavigateToNotes = { selectedTab = MainTab.NOTES },
                        )
                        MainTab.AI -> ChatTab(
                            vm = vm,
                            selectedSessionId = selectedSessionId,
                            onSessionSelected = { id ->
                                selectedSessionId = id
                            },
                            onSessionDeselected = { selectedSessionId = null },
                            onOpenSubScreen = { subScreen = it },
                            isLandscape = isLandscape,
                        )
                        MainTab.SETTINGS -> SettingsScreen(
                            vm = vm,
                            onBack = {},
                            toSkills = { subScreen = "skills" },
                            toAutomations = { subScreen = "automations" },
                            toMemory = { subScreen = "memory" },
                            toNotes = { subScreen = "notes" },
                            toHippocampus = { subScreen = "hippocampus" },
                            toVoiceprint = { subScreen = "voiceprint" },
                            toExport = { subScreen = "export" },
                            hippocampus = hippocampus,
                            showBackButton = false,
                            toOpenSource = { subScreen = "opensource" },
                        )
                    }
                }
                else -> {
                    when {
                        subScreen?.startsWith("noteReader_") == true -> {
                            val noteIdStr = (subScreen ?: "").removePrefix("noteReader_")
                            val noteId = noteIdStr.toLongOrNull()
                            if (noteId != null) {
                                NoteEditorScreen(
                                    repository = vm.noteRepository,
                                    noteId = noteId,
                                    forceReadOnly = true,
                                    onBack = { subScreen = "notes" },
                                    onEdit = { subScreen = "noteEditor_$noteId" },
                                    onSendToChat = { id ->
                                        vm.sendNoteToChat(id)
                                        subScreen = "chat"
                                    },
                                    onSendWithSkill = { id, skill ->
                                        vm.sendNoteWithSkill(id, skill)
                                        subScreen = "noteSprout_$id"
                                    },
                                    onSproutNote = { id -> subScreen = "noteSprout_$id" },
                                    onOpenEditorTeam = { content ->
                                        editorTeamInitialInput = content
                                        subScreen = "editorTeam"
                                    },
                                    onSaved = { id -> subScreen = "noteReader_$id" },
                                    onCorrectNote = { id ->
                                        vm.correctNote(id)
                                        selectedTab = MainTab.AI
                                        subScreen = null
                                    },
                                    onAddToKnowledgeBase = { id -> vm.addNoteToKnowledgeBase(id) },
                                    onAddTag = { id -> subScreen = "noteEditor_$id" },
                                    onAppendNote = { id -> subScreen = "noteEditor_$id" },
                                    sproutService = top.hsyscn.opedrgent.note.SproutService(vm.apiSettings, hippocampus),
                                )
                            }
                        }
                        subScreen?.startsWith("noteEditor_") == true -> {
                            val noteIdStr = (subScreen ?: "").removePrefix("noteEditor_")
                            val noteId = noteIdStr.toLongOrNull()
                            NoteEditorScreen(
                                repository = vm.noteRepository,
                                noteId = noteId,
                                onSaved = { /* 保持在编辑器 */ },
                                onSendToChat = { nid ->
                                    vm.sendNoteToChat(nid)
                                    selectedTab = MainTab.AI
                                    subScreen = null
                                },
                                onSendWithSkill = { nid, skillId ->
                                    vm.sendNoteWithSkill(nid, skillId)
                                    selectedTab = MainTab.AI
                                    subScreen = null
                                },
                                onSproutNote = { nid -> subScreen = "noteSprout_$nid" },
                                onOpenEditorTeam = { input ->
                                    editorTeamInitialInput = input
                                    subScreen = "editorTeam"
                                },
                                onBack = { subScreen = "notes" },
                                onCorrectNote = { nid ->
                                    vm.correctNote(nid)
                                    selectedTab = MainTab.AI
                                    subScreen = null
                                },
                                onAddToKnowledgeBase = { nid -> vm.addNoteToKnowledgeBase(nid) },
                                onAddTag = { nid -> /* 已在编辑器内处理 */ },
                                onAppendNote = { nid -> /* 已在编辑器内处理 */ },
                                editorMode = vm.getEditorMode(),
                                sproutService = top.hsyscn.opedrgent.note.SproutService(vm.apiSettings, hippocampus),
                            )
                        }
                        subScreen?.startsWith("noteShare_") == true -> {
                            val noteIdStr = (subScreen ?: "").removePrefix("noteShare_")
                            val noteId = noteIdStr.toLongOrNull()
                            if (noteId != null) {
                                val aiConvertedContent by vm.aiConvertedContent.collectAsState()
                                val isConverting by vm.isConverting.collectAsState()
                                NoteShareScreen(
                                    repository = vm.noteRepository,
                                    noteId = noteId,
                                    onBack = {
                                        vm.clearConvertedContent()
                                        subScreen = "notes"
                                    },
                                    aiConvertedContent = aiConvertedContent,
                                    isConverting = isConverting,
                                    onConvert = { style -> vm.convertNoteStyle(noteId, style) },
                                    onClearConversion = { vm.clearConvertedContent() },
                                )
                            }
                        }
                        subScreen?.startsWith("noteSprout_") == true -> {
                            val noteIdStr = (subScreen ?: "").removePrefix("noteSprout_")
                            val noteId = noteIdStr.toLongOrNull()
                            if (noteId != null) {
                                var loadedNote by remember { mutableStateOf<top.hsyscn.opedrgent.note.Note?>(null) }
                                LaunchedEffect(noteId) {
                                    loadedNote = vm.noteRepository.getNoteById(noteId)
                                }
                                loadedNote?.let { note ->
                                    NoteSproutScreen(
                                        note = note,
                                        repository = vm.noteRepository,
                                        sproutService = top.hsyscn.opedrgent.note.SproutService(vm.apiSettings, hippocampus),
                                        sproutReportStore = vm.sproutReportStore,
                                        onBack = { subScreen = "notes" },
                                        onEditNote = { subScreen = "noteEditor_$noteId" },
                                    )
                                }
                            }
                        }
                    }
                }
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
                title = { Text("Opedrgent", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { createOpen = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Default.Add, contentDescription = "new")
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = q,
                onValueChange = { q = it; vm.setSessionSearchQuery(it) },
                label = { Text(stringResource(R.string.sessions_search_hint)) },
                modifier = Modifier.padding(horizontal = SpacingTokens.md, vertical = SpacingTokens.sm).fillMaxWidth(),
                singleLine = true,
                shape = ShapeTokens.mediumShape,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                ),
            )
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (q.isNotBlank() && state.messageSearchResults.isNotEmpty()) {
                    // 消息级搜索结果
                    item {
                        Text(
                            text = "在 ${state.sessions.size} 个会话中找到 ${state.messageSearchResults.size} 条消息",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = SpacingTokens.lg, vertical = SpacingTokens.xs),
                        )
                    }
                    items(state.messageSearchResults, key = { it.messageId }) { result ->
                        Card(
                            modifier = Modifier
                                .padding(horizontal = SpacingTokens.md, vertical = SpacingTokens.xs)
                                .fillMaxWidth(),
                            shape = ShapeTokens.mediumShape,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                            onClick = { onSelectSession(result.sessionId) },
                        ) {
                            Column(modifier = Modifier.padding(SpacingTokens.md)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = result.sessionTitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = if (result.role == Role.USER) "你" else "AI",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (result.role == Role.USER) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                                Spacer(modifier = Modifier.height(SpacingTokens.xs))
                                Text(
                                    text = result.matchSnippet,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(modifier = Modifier.height(SpacingTokens.xxs))
                                Text(
                                    text = formatTime(result.timestamp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                } else {
                    // 普通会话列表（无搜索或无消息匹配时）
                    items(state.sessions, key = { it.id }) { s ->
                        Card(
                            modifier = Modifier
                                .padding(horizontal = SpacingTokens.md, vertical = SpacingTokens.sm)
                                .fillMaxWidth(),
                            shape = ShapeTokens.mediumShape,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                            onClick = { onSelectSession(s.id) },
                        ) {
                            Column(modifier = Modifier.padding(SpacingTokens.md)) {
                                Text(text = s.title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                                Spacer(modifier = Modifier.height(SpacingTokens.sm))
                                Text(text = formatTime(s.updatedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
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
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = { TextButton(onClick = { createOpen = false }) { Text(stringResource(R.string.action_cancel)) } },
            title = { Text("新建研究") },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.sessions_title_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
    }
}

// SessionScreen + AiStatusBar 已提取到 SessionScreen.kt（原 ~880 行）

// SettingsScreen 已提取到 SettingsScreen.kt（原 ~744 行）

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
                title = { Text(stringResource(R.string.title_memory_management)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.cd_back))
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
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_add))
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
        ) {
            if (state.memories.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(SpacingTokens.xxl),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
                    ) {
                        Text(stringResource(R.string.memory_empty), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.memory_empty_hint), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            items(state.memories, key = { it.id }) { entry ->
                Card(
                    modifier = Modifier.padding(horizontal = SpacingTokens.md).fillMaxWidth(),
                    shape = ShapeTokens.mediumShape,
                    onClick = {
                        editingId = entry.id
                        title = entry.title
                        content = entry.content
                        selectedType = entry.type.name
                        editorOpen = true
                    },
                ) {
                    Column(modifier = Modifier.padding(SpacingTokens.md)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = entry.title.ifBlank { stringResource(R.string.memory_no_title) }, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            AssistChip(
                                onClick = {},
                                label = { Text(entry.type.label, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.height(SpacingTokens.xl),
                            )
                            IconButton(onClick = { vm.deleteMemory(entry.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete))
                            }
                        }
                        Spacer(modifier = Modifier.height(SpacingTokens.xs))
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
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
                    if (editingId != null) {
                        TextButton(onClick = {
                            vm.deleteMemory(editingId ?: return@TextButton)
                            editorOpen = false
                        }) { Text(stringResource(R.string.action_delete)) }
                    }
                    TextButton(onClick = { editorOpen = false }) { Text(stringResource(R.string.action_cancel)) }
                }
            },
            title = { Text(if (editingId == null) stringResource(R.string.memory_new) else stringResource(R.string.memory_edit)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
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
                        label = { Text(stringResource(R.string.memory_title_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        label = { Text(stringResource(R.string.memory_content_hint)) },
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
    val context = LocalContext.current
    val state by vm.state.collectAsStateCompat()
    val scope = rememberCoroutineScope()

    // ── 旧版技能（兼容保留）──
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var prompt by rememberSaveable { mutableStateOf("") }

    // ── Gallery 标准导入功能 ──
    var showImportMenu by remember { mutableStateOf(false) }          // FAB 展开的导入选项菜单
    var showUrlImportDialog by remember { mutableStateOf(false) }     // URL 导入对话框
    var urlInput by rememberSaveable { mutableStateOf("") }            // URL 输入框内容
    var isImporting by remember { mutableStateOf(false) }             // 导入中加载状态
    var importMessage by remember { mutableStateOf<String?>(null) }   // 导入结果消息
    var gallerySkills by remember { mutableStateOf<List<top.hsyscn.opedrgent.mcp.skills.StandardSkillDefinition>>(emptyList()) } // Gallery 技能列表

    // ── 本地文件选择器 ──
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        isImporting = true
        importMessage = null
        scope.launch {
            val result = vm.importSkillFromFile(context, uri)
            isImporting = false
            importMessage = if (result.isSuccess) {
                vm.refreshGallerySkills() // 刷新列表
                "导入成功：${result.getOrNull()?.skillName}"
            } else {
                "导入失败：${result.exceptionOrNull()?.message}"
            }
        }
    }

    // ── 初始加载 Gallery 技能列表 ──
    LaunchedEffect(Unit) {
        vm.refreshGallerySkills()
        gallerySkills = vm.gallerySkills
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_skills_library)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    // 统计信息角标
                    Text(
                        text = "${state.skills.size + gallerySkills.size} 项",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = SpacingTokens.sm),
                    )
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            // Gallery 标准的多功能 FAB：展开为三个导入入口
            Column(horizontalAlignment = Alignment.End) {
                // 展开动画：导入选项按钮组
                AnimatedVisibility(visible = showImportMenu, enter = fadeIn(), exit = fadeOut()) {
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
                        modifier = Modifier.padding(bottom = SpacingTokens.md)
                    ) {
                        // ① 从 URL 加载
                        ImportFabOption(
                            icon = Icons.Default.Link,
                            label = stringResource(R.string.skills_import_from_url),
                            onClick = {
                                showImportMenu = false
                                urlInput = ""
                                importMessage = null
                                showUrlImportDialog = true
                            },
                        )
                        // ② 从本地文件导入
                        ImportFabOption(
                            icon = Icons.Default.AttachFile,
                            label = stringResource(R.string.skills_import_from_file),
                            onClick = {
                                showImportMenu = false
                                filePickerLauncher.launch("application/octet-stream")
                            },
                        )
                        // ③ 手动创建（原有功能）
                        ImportFabOption(
                            icon = Icons.Default.Add,
                            label = stringResource(R.string.skills_create_manual),
                            onClick = {
                                showImportMenu = false
                                editingId = null
                                name = ""
                                prompt = ""
                                editorOpen = true
                            },
                        )
                    }
                }

                // 主 FAB 按钮
                FloatingActionButton(
                    onClick = { showImportMenu = !showImportMenu },
                    containerColor = MaterialTheme.customColors.accentBlue,
                ) {
                    Icon(
                        imageVector = if (showImportMenu) Icons.Default.Close else Icons.Default.Add,
                        contentDescription = if (showImportMenu) stringResource(R.string.action_collapse) else stringResource(R.string.action_expand),
                    )
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
        ) {
            // ════════════════════════════════════
            // 第一区：Gallery 标准技能（来自 SkillLoader）
            // ════════════════════════════════════
            if (gallerySkills.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = stringResource(R.string.skills_gallery_title),
                        subtitle = stringResource(R.string.skills_gallery_subtitle),
                        count = gallerySkills.size,
                    )
                }
                items(gallerySkills, key = { it.skillName + "_gallery" }) { skill ->
                    GallerySkillCard(
                        skill = skill,
                        onClick = {
                            // 点击 Gallery Skill：将指令作为用户消息发送到当前会话
                            vm.runGallerySkill(skill)
                        },
                        onToggleEnabled = { enabled ->
                            vm.toggleGallerySkill(skill.skillName, enabled)
                            // 刷新本地状态
                            gallerySkills = gallerySkills.map {
                                if (it.skillName == skill.skillName) it.copy(isEnabled = enabled) else it
                            }
                        },
                        onDelete = {
                            if (!skill.isBuiltIn) {
                                scope.launch {
                                    vm.deleteGallerySkill(skill.skillName)
                                    gallerySkills = gallerySkills.filter { it.skillName != skill.skillName }
                                }
                            }
                        },
                    )
                }

                // 分隔线
                item {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = SpacingTokens.lg, vertical = SpacingTokens.sm),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    )
                }
            }

            // ════════════════════════════════════
            // 第二区：旧版自定义技能（兼容保留）
            // ════════════════════════════════════
            if (state.skills.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = stringResource(R.string.skills_custom_title),
                        subtitle = stringResource(R.string.skills_custom_subtitle),
                        count = state.skills.size,
                    )
                }
                items(state.skills, key = { it.id }) { s ->
                    Card(
                        modifier = Modifier.padding(horizontal = SpacingTokens.md).fillMaxWidth(),
                        shape = ShapeTokens.mediumShape,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        onClick = {
                            editingId = s.id
                            name = s.name
                            prompt = s.prompt
                            editorOpen = true
                        },
                    ) {
                        Column(modifier = Modifier.padding(SpacingTokens.md)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = s.name,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(modifier = Modifier.width(SpacingTokens.sm))
                                // 旧版标识标签
                                AssistChip(
                                    onClick = {},
                                    label = { Text("Legacy", style = MaterialTheme.typography.labelSmall) },
                                    border = null,
                                    colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
                                        containerColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                                        labelColor = MaterialTheme.colorScheme.outline,
                                    ),
                                )
                            }
                            Spacer(modifier = Modifier.height(SpacingTokens.sm))
                            Text(
                                text = s.prompt.take(120) + if (s.prompt.length > 120) "…" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                        }
                    }
                }
            }

            // ════════════════════════════════════
            // 空状态提示
            // ════════════════════════════════════
            if (state.skills.isEmpty() && gallerySkills.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = SpacingTokens.xxl),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.DeveloperBoard,
                                contentDescription = null, // decorative empty-state illustration
                                modifier = Modifier.size(SpacingTokens.xxl * 2),
                                tint = MaterialTheme.colorScheme.outline,
                            )
                            Spacer(modifier = Modifier.height(SpacingTokens.lg))
                            Text(text = stringResource(R.string.skills_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                            Spacer(modifier = Modifier.height(SpacingTokens.sm))
                            Text(
                                text = stringResource(R.string.skills_empty_hint),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(horizontal = SpacingTokens.xxl),
                            )
                        }
                    }
                }
            }
        }
    }

    // ════════════════════════════════════════════════
    // URL 导入对话框（Gallery 标准）
    // ════════════════════════════════════════════════
    if (showUrlImportDialog) {
        AlertDialog(
            onDismissRequest = { 
                if (!isImporting) showUrlImportDialog = false 
            },
            confirmButton = {
                Button(
                    onClick = {
                        val url = urlInput.trim()
                        if (url.isBlank()) {
                            importMessage = "请输入 SKILL.md 的 URL"
                            return@Button
                        }
                        isImporting = true
                        importMessage = null
                        scope.launch {
                            val result = vm.importSkillFromUrl(url)
                            isImporting = false
                            importMessage = if (result.isSuccess) {
                                vm.refreshGallerySkills()
                                gallerySkills = vm.gallerySkills
                                // 延迟关闭对话框
                                kotlinx.coroutines.delay(800)
                                showUrlImportDialog = false
                                "导入成功：${result.getOrNull()?.skillName}"
                            } else {
                                "导入失败：${result.exceptionOrNull()?.message}"
                            }
                        }
                    },
                    enabled = !isImporting,
                ) {
                    if (isImporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(SpacingTokens.md),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.width(SpacingTokens.sm))
                    }
                    Text(stringResource(R.string.action_download))
                }
            },
            dismissButton = {
                TextButton(onClick = { if (!isImporting) showUrlImportDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            title = { Text("从 URL 导入 Skill") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
                    Text(
                        text = stringResource(R.string.skills_url_import_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        label = { Text("SKILL.md URL") },
                        placeholder = { Text("https://example.com/skill/SKILL.md") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    )
                    // 导入状态/结果消息
                    importMessage?.let { msg ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (msg.startsWith("导入成功")) MaterialTheme.customColors.successGreen else MaterialTheme.customColors.dangerRed,
                        )
                    }
                }
            },
        )
    }

    // ════════════════════════════════════════════════
    // 旧版编辑对话框（兼容保留）
    // ════════════════════════════════════════════════
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
                    Spacer(modifier = Modifier.width(SpacingTokens.xs))
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
                    if (deleting) {
                        TextButton(onClick = {
                            val id = editingId
                            if (id != null) vm.deleteSkill(id)
                            editorOpen = false
                        }) { Text(stringResource(R.string.action_delete)) }
                    }
                    TextButton(onClick = { editorOpen = false }) { Text(stringResource(R.string.action_cancel)) }
                }
            },
            title = { Text(if (editingId == null) stringResource(R.string.skills_new_custom) else stringResource(R.string.skills_edit_custom)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.skills_name_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        label = { Text(stringResource(R.string.skills_content_hint)) },
                        modifier = Modifier.fillMaxSize(),
                        minLines = 4,
                    )
                }
            },
        )
    }
}

// ════════════════════════════════════════════════════════════
// SkillsScreen 子组件 — Gallery 标准辅助 UI
// ════════════════════════════════════════════════════════════

/** 区块标题 */
@Composable
private fun SectionHeader(title: String, subtitle: String, count: Int) {
    Row(
        modifier = Modifier.padding(horizontal = SpacingTokens.lg, vertical = SpacingTokens.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(SpacingTokens.xxs))
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            text = "$count",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.customColors.accentBlue,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** FAB 展开选项按钮 */
@Composable
private fun ImportFabOption(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(ShapeTokens.extraLargeShape)
            .background(MaterialTheme.customColors.accentBlue.copy(alpha = 0.9f))
            .clickable(onClick = onClick)
            .padding(horizontal = SpacingTokens.md, vertical = SpacingTokens.sm),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(SpacingTokens.md)) // decorative, label describes action
        Spacer(modifier = Modifier.width(SpacingTokens.sm))
        Text(text = label, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

/** Gallery 技能卡片 — 显示完整的 StandardSkillDefinition 信息 */
@Composable
private fun GallerySkillCard(
    skill: top.hsyscn.opedrgent.mcp.skills.StandardSkillDefinition,
    onClick: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.padding(horizontal = SpacingTokens.md).fillMaxWidth(),
        shape = ShapeTokens.mediumShape,
        colors = CardDefaults.cardColors(
            containerColor = if (skill.isEnabled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
        ),
        onClick = onClick,
    ) {
        Column(modifier = Modifier.padding(SpacingTokens.md)) {
            // 第一行：名称 + 标签组
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = skill.metadata.name,
                    fontWeight = FontWeight.SemiBold,
                    color = if (skill.isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                // 内置标签
                if (skill.isBuiltIn) {
                    AssistChip(
                        onClick = {},
                        label = { Text("内置", style = MaterialTheme.typography.labelSmall) },
                        border = null,
                        colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.customColors.accentBlue.copy(alpha = 0.12f),
                            labelColor = MaterialTheme.customColors.accentBlue,
                        ),
                    )
                    Spacer(modifier = Modifier.width(SpacingTokens.xs))
                }
                // 需要 Secret 标签
                if (skill.needsSecret) {
                    AssistChip(
                        onClick = {},
                        label = { Text("API Key", style = MaterialTheme.typography.labelSmall) },
                        border = null,
                        colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.customColors.chipWarningBg, // 浅橙
                            labelColor = MaterialTheme.customColors.chipWarningText,
                        ),
                    )
                }
                // JS Skill 标识
                if (skill.localScriptsPath != null) {
                    AssistChip(
                        onClick = {},
                        label = { Text("JS", style = MaterialTheme.typography.labelSmall) },
                        border = null,
                        colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.customColors.chipSuccessBg, // 浅绿
                            labelColor = MaterialTheme.customColors.chipSuccessText,
                        ),
                    )
                }
            }

            Spacer(modifier = Modifier.height(SpacingTokens.sm))

            // 描述文本
            Text(
                text = skill.metadata.description,
                style = MaterialTheme.typography.bodySmall,
                color = if (skill.isEnabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )

            Spacer(modifier = Modifier.height(SpacingTokens.sm))

            // 底部操作栏：分类 + 启用开关 + 删除
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // 分类标签
                Text(
                    text = skill.metadata.category.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), ShapeTokens.extraSmallShape)
                        .padding(horizontal = SpacingTokens.xs, vertical = SpacingTokens.xxs),
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 启用/禁用开关
                    Switch(
                        checked = skill.isEnabled,
                        onCheckedChange = onToggleEnabled,
                        thumbContent = {
                            if (skill.isEnabled) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null, // decorative switch-on indicator
                                    modifier = Modifier.size(SpacingTokens.md),
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.customColors.accentBlue,
                        ),
                    )

                    // 非内置技能显示删除按钮
                    if (!skill.isBuiltIn) {
                        Spacer(modifier = Modifier.width(SpacingTokens.sm))
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.size(SpacingTokens.xxl),
                        ) {
                            Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.action_delete),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(SpacingTokens.lg),
                        )
                        }
                    }
                }
            }
        }
    }
}




fun formatTime(ms: Long): String {
    return appTimeFormat.format(Date(ms))
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
        Toast.makeText(context, context.getString(R.string.msg_no_share_app), Toast.LENGTH_SHORT).show()
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
        Toast.makeText(context, context.getString(R.string.msg_no_calendar_app), Toast.LENGTH_SHORT).show()
    }
}
