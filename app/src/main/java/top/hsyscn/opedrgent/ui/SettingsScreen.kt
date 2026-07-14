@file:OptIn(ExperimentalMaterial3Api::class)

package top.hsyscn.opedrgent.ui

import top.hsyscn.opedrgent.ui.theme.AccentBlue
import top.hsyscn.opedrgent.ui.theme.InterviewDarkBg
import top.hsyscn.opedrgent.ui.theme.WarningColor
import top.hsyscn.opedrgent.ui.theme.customColors
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.health.connect.client.HealthConnectClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Satellite
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import top.hsyscn.opedrgent.R
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.hsyscn.opedrgent.llm.AvailableLocalModels
import top.hsyscn.opedrgent.llm.LocalLlmEngine
import top.hsyscn.opedrgent.llm.LocalLlmState
import top.hsyscn.opedrgent.llm.ModelDownloadManager
import top.hsyscn.opedrgent.stt.ModelManager
import top.hsyscn.opedrgent.stt.ModelManager.DownloadProgress
import top.hsyscn.opedrgent.stt.ModelType
import top.hsyscn.opedrgent.service.SttDownloadService
import top.hsyscn.opedrgent.settings.PROVIDER_PRESETS
import top.hsyscn.opedrgent.network.ModelFetcher
import top.hsyscn.opedrgent.ui.components.ModelSelectorDialog
import top.hsyscn.opedrgent.ui.components.SttModelDownloadDialog
import top.hsyscn.opedrgent.ui.components.isAtLeastMediumWidth
import top.hsyscn.opedrgent.ui.components.isLandscape
import top.hsyscn.opedrgent.ui.components.isExpandedWidth
import top.hsyscn.opedrgent.ui.theme.BubbleBlue
import top.hsyscn.opedrgent.ui.theme.AccentOrange
import top.hsyscn.opedrgent.ui.theme.InterviewPurple
import top.hsyscn.opedrgent.ui.theme.SuccessGreen
import top.hsyscn.opedrgent.storage.HippocampusIndex
import top.hsyscn.opedrgent.utils.BackgroundPermHelper
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import top.hsyscn.opedrgent.ui.theme.SpacingTokens
import top.hsyscn.opedrgent.ui.theme.themeBgGray
import top.hsyscn.opedrgent.ui.theme.*
import top.hsyscn.opedrgent.utils.LocaleHelper
import androidx.compose.ui.res.painterResource

private const val BadgeBgAlpha = 0.15f

private enum class SettingsSection(val titleRes: Int, val icon: ImageVector) {
    MODEL(R.string.settings_section_model, Icons.Default.DeveloperBoard),
    VOICE(R.string.settings_section_voice, Icons.Default.Mic),
    MEMORY(R.string.settings_section_memory, Icons.Default.Memory),
    FEATURES(R.string.settings_section_features, Icons.Default.AutoAwesome),
    IMPORT_EXPORT(R.string.settings_section_import_export, Icons.Default.ImportExport),
    ABOUT(R.string.settings_section_about, Icons.Default.Info),
}

@Composable
fun SettingsScreen(
    vm: MainViewModel,
    onBack: () -> Unit,
    toSkills: () -> Unit,
    toAutomations: () -> Unit,
    toMemory: () -> Unit,
    toNotes: () -> Unit,
    toHippocampus: () -> Unit = {},
    toVocabulary: () -> Unit = {},
    toVoiceprint: () -> Unit = {},
    toExport: () -> Unit = {},
    hippocampus: HippocampusIndex? = null,
    showBackButton: Boolean = true,
    onInvisiblePartner: () -> Unit = {},
    toOpenSource: () -> Unit = {},
) {
    var baseUrl by rememberSaveable { mutableStateOf(vm.getBaseUrl()) }
    var model by rememberSaveable { mutableStateOf(vm.getModel()) }
    var apiKey by rememberSaveable { mutableStateOf(vm.getApiKey() ?: "") }
    var ttsEnabled by rememberSaveable { mutableStateOf(vm.isTtsEnabled()) }
    var ttsAuto by rememberSaveable { mutableStateOf(vm.isTtsAutoSpeak()) }
    var ttsRate by rememberSaveable { mutableStateOf(vm.getTtsRate()) }
    var ttsPitch by rememberSaveable { mutableStateOf(vm.getTtsPitch()) }
    var ttsLocaleTag by rememberSaveable { mutableStateOf(vm.getTtsLocaleTag()) }
    var sttEnabled by rememberSaveable { mutableStateOf(vm.isSttEnabled()) }
    var sttEngine by rememberSaveable { mutableStateOf(vm.getSttEngine()) }
    var sttStreamingMode by rememberSaveable { mutableStateOf(vm.getSttStreamingMode()) }
    var hrEnabled by rememberSaveable { mutableStateOf(vm.isHrEnabled()) }
    var segmentEnabled by rememberSaveable { mutableStateOf(vm.isSegmentEnabled()) }
    val hippocampusCount by produceState(0, hippocampus) {
        value = hippocampus?.count() ?: 0
    }
    var ttsDownloadOnly by rememberSaveable { mutableStateOf(vm.isTtsDownloadOnly()) }
    var ttsMimoEnabled by rememberSaveable { mutableStateOf(vm.isTtsMimoEnabled()) }
    var ttsEngine by rememberSaveable { mutableStateOf(vm.getTtsEngine()) }
    var bgRunning by rememberSaveable { mutableStateOf(vm.isBackgroundRunning()) }
    var locationEnabled by rememberSaveable { mutableStateOf(vm.isLocationEnabled()) }
    var hamModeEnabled by rememberSaveable { mutableStateOf(vm.isHamModeEnabled()) }
    var debugMode by rememberSaveable { mutableStateOf(vm.isDebugMode()) }
    var calendarEnabled by rememberSaveable { mutableStateOf(vm.isCalendarEnabled()) }
    var healthEnabled by rememberSaveable { mutableStateOf(vm.isHealthEnabled()) }
    var themeMode by rememberSaveable { mutableStateOf(vm.getThemeMode()) }
    var dynamicColorEnabled by rememberSaveable { mutableStateOf(vm.isDynamicColorEnabled()) }
    var webSearchEnabled by rememberSaveable { mutableStateOf(vm.isWebSearchEnabled()) }
    var webSearchSource by rememberSaveable { mutableStateOf(vm.getWebSearchSource()) }
    var deepThinkingEnabled by rememberSaveable { mutableStateOf(vm.isDeepThinking()) }
    var autoGenerateNoteTitle by rememberSaveable { mutableStateOf(vm.isAutoGenerateNoteTitle()) }
    var jinaApiKey by rememberSaveable { mutableStateOf(vm.getJinaApiKey() ?: "") }
    var tavilyApiKey by rememberSaveable { mutableStateOf(vm.getTavilyApiKey() ?: "") }
    var braveApiKey by rememberSaveable { mutableStateOf(vm.getBraveApiKey() ?: "") }
    var searchProviderOrder by rememberSaveable { mutableStateOf(vm.getSearchProviderOrder()) }
    var showModelSelector by rememberSaveable { mutableStateOf(false) }
    var showMemoryWarning by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val localEngine = remember { LocalLlmEngine.getInstance(context) }
    val downloadManager = remember { ModelDownloadManager(context) }
    var isLocalMode by rememberSaveable { mutableStateOf(vm.isLocalModelEnabled()) }
    var localModelId by rememberSaveable { mutableStateOf(vm.getLocalModelId()) }
    var providerMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var modelMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var fetchedModels by remember { mutableStateOf<List<String>?>(null) }
    var isFetchingModels by remember { mutableStateOf(false) }
    val memoryCount by remember { derivedStateOf { vm.state.value.memories.size } }
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
    val calendarPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            calendarEnabled = true
            vm.saveCalendarEnabled(true)
        } else {
            calendarEnabled = false
            scope.launch { snackbar.showSnackbar("未授予日历权限") }
        }
    }
    val healthPermLauncher = rememberLauncherForActivityResult(
        androidx.health.connect.client.PermissionController.createRequestPermissionResultContract()
    ) { result ->
        scope.launch {
            // Health Connect 授权弹窗返回的是本次发生变化的权限集合，
            // 已经授予的权限不会包含在 result 中，因此必须重新查询已授权集合。
            val granted = try {
                HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()
            } catch (_: Exception) {
                result
            }
            if (granted.containsAll(top.hsyscn.opedrgent.health.HealthConnectHelper.PERMISSIONS)) {
                healthEnabled = true
                vm.saveHealthEnabled(true)
            } else {
                healthEnabled = false
                vm.saveHealthEnabled(false)
                snackbar.showSnackbar("未授予全部健康数据权限")
            }
        }
    }
    val activityRecognitionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            healthPermLauncher.launch(top.hsyscn.opedrgent.health.HealthConnectHelper.PERMISSIONS)
        } else {
            healthEnabled = false
            vm.saveHealthEnabled(false)
            scope.launch { snackbar.showSnackbar("未授予活动识别权限") }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_settings), style = MaterialTheme.typography.headlineLarge) },
                navigationIcon = {
                    if (showBackButton) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back)) }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbar) },
        containerColor = themeBackgroundSecondary(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        val scrollState = rememberScrollState()
        val sectionOffsets = remember { mutableStateMapOf<SettingsSection, Int>() }
        var selectedSection by rememberSaveable { mutableStateOf(SettingsSection.MODEL) }

        @Composable
        fun settingsContent() {
            // ── 模型配置 ──
            Box(modifier = Modifier.onGloballyPositioned { coordinates ->
                sectionOffsets[SettingsSection.MODEL] = coordinates.positionInParent().y.toInt()
            }) {
                SettingGroup(title = stringResource(R.string.settings_section_model)) {
                Column(
                    modifier = Modifier.padding(horizontal = SpacingTokens.lg, vertical = SpacingTokens.md),
                    verticalArrangement = Arrangement.spacedBy(SpacingTokens.md),
                ) {
                    if (isLocalMode) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.DeveloperBoard, contentDescription = null, tint = BubbleBlue, modifier = Modifier.size(SizeTokens.iconMd))
                            Spacer(Modifier.width(SpacingTokens.sm))
                            Column {
                                Text(stringResource(R.string.msg_local_mode_running), style = MaterialTheme.typography.titleSmall, color = BubbleBlue)
                                Text(stringResource(R.string.msg_local_mode_desc), color = themeTextGrey(), style = MaterialTheme.typography.labelSmall)
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
                            isError = baseUrl.isNotBlank() && !baseUrl.startsWith("http"),
                            supportingText = {
                                if (baseUrl.isNotBlank() && !baseUrl.startsWith("http")) {
                                    Text(
                                        text = "URL 需以 http:// 或 https:// 开头",
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                                    )
                                }
                            },
                        )

                        Box {
                            OutlinedTextField(
                                value = model,
                                onValueChange = { model = it },
                                label = { Text("Model") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                trailingIcon = {
                                    Row {
                                        IconButton(
                                            onClick = {
                                                if (!isFetchingModels) {
                                                    isFetchingModels = true
                                                    scope.launch {
                                                        val result = ModelFetcher.fetchModels(baseUrl, apiKey)
                                                        fetchedModels = result
                                                        isFetchingModels = false
                                                        modelMenuExpanded = result != null
                                                        if (result == null) {
                                                            snackbar.showSnackbar("获取模型列表失败 — 请检查 API Key 是否正确，或查看日志详情")
                                                        }
                                                    }
                                                }
                                            },
                                            enabled = !isFetchingModels,
                                        ) {
                                            if (isFetchingModels) {
                                                CircularProgressIndicator(modifier = Modifier.size(SizeTokens.iconMd), strokeWidth = SpacingTokens.xxs)
                                            } else {
                                                Icon(Icons.Default.Refresh, contentDescription = "从 API 获取模型列表")
                                            }
                                        }
                                        IconButton(onClick = { modelMenuExpanded = true }) {
                                            Icon(Icons.Default.ArrowDropDown, contentDescription = "展开模型")
                                        }
                                    }
                                },
                            )
                            val currentPreset = PROVIDER_PRESETS.firstOrNull { it.baseUrl == baseUrl }
                            val presetModels = currentPreset?.models ?: emptyList()
                            val allModels = if (fetchedModels != null) {
                                (presetModels + fetchedModels!!).distinct()
                            } else {
                                presetModels
                            }
                            if (allModels.isNotEmpty()) {
                                DropdownMenu(expanded = modelMenuExpanded, onDismissRequest = { modelMenuExpanded = false }) {
                                    if (presetModels.isNotEmpty()) {
                                        DropdownMenuItem(
                                            text = { Text("预置模型", style = MaterialTheme.typography.labelMedium, color = themeTextGrey()) },
                                            onClick = {},
                                            enabled = false,
                                        )
                                        presetModels.forEach { m ->
                                            DropdownMenuItem(
                                                text = { Text(m) },
                                                onClick = {
                                                    model = m
                                                    modelMenuExpanded = false
                                                },
                                            )
                                        }
                                    }
                                    val apiOnlyModels = fetchedModels?.filter { it !in presetModels } ?: emptyList()
                                    if (apiOnlyModels.isNotEmpty()) {
                                        if (presetModels.isNotEmpty()) {
                                            HorizontalDivider(modifier = Modifier.padding(vertical = SpacingTokens.xs))
                                        }
                                        DropdownMenuItem(
                                            text = { Text("API 可用模型", style = MaterialTheme.typography.labelMedium, color = themeTextGrey()) },
                                            onClick = {},
                                            enabled = false,
                                        )
                                        apiOnlyModels.forEach { m ->
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

                    HorizontalDivider(color = themeBorder())

                    // 本地模型
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.DeveloperBoard, contentDescription = null, tint = BubbleBlue, modifier = Modifier.size(SizeTokens.iconLg))
                        Spacer(Modifier.width(SpacingTokens.md))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_local_model_title), style = MaterialTheme.typography.titleSmall, color = themeTextDark())
                            Text(
                                text = if (isLocalMode && localModelId != null) {
                                    val info = AvailableLocalModels.findById(localModelId!!)
                                    "当前使用: ${info?.displayName ?: localModelId}"
                                } else stringResource(R.string.settings_local_model_offline_desc),
                                color = themeTextGrey(),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
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
                                            scope.launch { snackbar.showSnackbar(context.getString(R.string.msg_download_model_first)) }
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
                    }

                    OutlinedButton(
                        onClick = { showModelSelector = true },
                        shape = ShapeTokens.smallShape,
                        contentPadding = PaddingValues(horizontal = SizeTokens.buttonHorizontalMd, vertical = SizeTokens.compactSpacing),
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(SpacingTokens.lg), tint = BubbleBlue)
                        Spacer(Modifier.width(SpacingTokens.sm))
                        Text(stringResource(R.string.settings_select_model), style = MaterialTheme.typography.bodySmall, color = BubbleBlue)
                    }

                    val currentInfo = localModelId?.let { AvailableLocalModels.findById(it) }
                    if (isLocalMode && currentInfo != null) {
                        var localTemp by rememberSaveable { mutableStateOf(vm.getLocalTemperature()) }
                        var localTopK by rememberSaveable { mutableStateOf(vm.getLocalTopK()) }
                        var localTopP by rememberSaveable { mutableStateOf(vm.getLocalTopP()) }
                        var localMaxTok by rememberSaveable { mutableStateOf(vm.getMaxOutputTokens()) }

                        Text(stringResource(R.string.settings_inference_params), style = MaterialTheme.typography.titleSmall, color = themeTextDark())
                        Text("上下文: ${currentInfo.maxContextLength} tokens | 输出: ${if (localMaxTok > 0) localMaxTok else currentInfo.maxTokens} tokens", style = MaterialTheme.typography.labelSmall, color = themeTextGrey())

                        Spacer(Modifier.height(SpacingTokens.sm))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SpacingTokens.md)) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Temperature", style = MaterialTheme.typography.labelSmall, color = themeTextGrey())
                                Slider(
                                    value = localTemp,
                                    onValueChange = { localTemp = it },
                                    valueRange = 0.01f..2.0f,
                                    steps = 39,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text("${String.format("%.2f", localTemp)}", style = MaterialTheme.typography.labelSmall, color = themeTextGrey())
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Top P", style = MaterialTheme.typography.labelSmall, color = themeTextGrey())
                                Slider(
                                    value = localTopP,
                                    onValueChange = { localTopP = it },
                                    valueRange = 0.1f..1.0f,
                                    steps = 17,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text("${String.format("%.2f", localTopP)}", style = MaterialTheme.typography.labelSmall, color = themeTextGrey())
                            }
                        }

                        Spacer(Modifier.height(SpacingTokens.sm))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SpacingTokens.md), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Top K: $localTopK", style = MaterialTheme.typography.labelSmall, color = themeTextGrey())
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
                                label = { Text("最大输出", style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.width(SizeTokens.textFieldWidthSm),
                                singleLine = true,
                            )
                        }

                        Spacer(Modifier.height(SpacingTokens.sm))

                        OutlinedButton(
                            onClick = {
                                vm.saveLocalParams(localTemp, localTopK, localTopP, localMaxTok)
                                scope.launch { snackbar.showSnackbar(context.getString(R.string.msg_params_saved)) }
                            },
                            shape = ShapeTokens.smallShape,
                            contentPadding = PaddingValues(horizontal = SizeTokens.buttonHorizontalSm, vertical = SpacingTokens.xs),
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(SizeTokens.iconXs), tint = BubbleBlue)
                            Spacer(Modifier.width(SpacingTokens.xs))
                            Text(stringResource(R.string.msg_save_params), style = MaterialTheme.typography.labelSmall, color = BubbleBlue)
                        }
                    }
                }
            }
            }

            // ── 语音设置 ──
            Box(modifier = Modifier.onGloballyPositioned { coordinates ->
                sectionOffsets[SettingsSection.VOICE] = coordinates.positionInParent().y.toInt()
            }) {
                SettingGroup(title = stringResource(R.string.settings_section_voice)) {
                Column(
                    modifier = Modifier.padding(horizontal = SpacingTokens.lg, vertical = SpacingTokens.md),
                    verticalArrangement = Arrangement.spacedBy(SpacingTokens.md),
                ) {
                    // STT
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = stringResource(R.string.settings_stt_label), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Switch(checked = sttEnabled, onCheckedChange = { sttEnabled = it })
                    }
                    if (sttEnabled) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = stringResource(R.string.settings_stt_engine), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                            ) {
                                FilterChip(
                                    selected = sttEngine == "local",
                                    onClick = { sttEngine = "local" },
                                    label = { Text(stringResource(R.string.settings_local_sherpa), style = MaterialTheme.typography.bodySmall) },
                                    shape = ShapeTokens.largeShape,
                                )
                                FilterChip(
                                    selected = sttEngine == "stepaudio",
                                    onClick = { sttEngine = "stepaudio" },
                                    label = { Text("StepAudio", style = MaterialTheme.typography.bodySmall) },
                                    shape = ShapeTokens.largeShape,
                                )
                                FilterChip(
                                    selected = sttEngine == "mimo",
                                    onClick = { sttEngine = "mimo" },
                                    label = { Text("MiMo ASR", style = MaterialTheme.typography.bodySmall) },
                                    shape = ShapeTokens.largeShape,
                                )
                            }
                        }
                        if (sttEngine == "mimo") {
                            Text(
                                text = stringResource(R.string.settings_mimo_asr_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = AccentOrange,
                            )
                        }
                        if (sttEngine == "stepaudio") {
                            Text(
                                text = stringResource(R.string.settings_stepaudio_asr_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = InterviewPurple,
                            )
                        }
                        if (sttEngine == "local") {
                            Text(
                                text = stringResource(R.string.settings_local_stt_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = themeTextGrey(),
                            )
                        }

                        val isStreamingModel = vm.getSelectedLocalModel() == ModelType.STREAMING_PARAFORMER.name
                        HorizontalDivider(color = themeBorder())
                        Text(
                            text = "识别模式",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (isStreamingModel) {
                            sttStreamingMode = "streaming"
                            Text(
                                text = "Paraformer 流式模型：实时流式识别（文本可修正）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = "同音字矫正", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = "根据拼音规则自动修正识别结果中的同音错字",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = themeTextGrey(),
                                    )
                                }
                                Switch(checked = hrEnabled, onCheckedChange = { hrEnabled = it; vm.saveHrEnabled(it) })
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = "分段识别", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = "长音频自动分段处理，提升识别速度（推荐开启）",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = themeTextGrey(),
                                    )
                                }
                                Switch(checked = segmentEnabled, onCheckedChange = { segmentEnabled = it; vm.saveSegmentEnabled(it) })
                            }
                            val hrDownloaded = remember { mutableStateOf(ModelManager.isHrDownloaded(context)) }
                            if (!hrDownloaded.value) {
                                var hrDownloading by remember { mutableStateOf(false) }
                                var hrProgress by remember { mutableStateOf(0f) }
                                var hrError by remember { mutableStateOf<String?>(null) }
                                Spacer(modifier = Modifier.height(SizeTokens.compactSpacing))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "同音字资源未下载",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                        Text(
                                            text = "需从 GitHub 下载 (~5MB)，连不上可跳过",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = themeTextGrey(),
                                        )
                                    }
                                    if (hrDownloading) {
                                        CircularProgressIndicator(progress = { hrProgress }, modifier = Modifier.size(SpacingTokens.xl), strokeWidth = SpacingTokens.xxs)
                                    } else {
                                        TextButton(onClick = {
                                            hrDownloading = true
                                            hrError = null
                                            scope.launch {
                                                ModelManager.downloadHrResources(context).collect { progress ->
                                                    when (progress) {
                                                        is DownloadProgress.Downloading -> hrProgress = progress.progress
                                                        is DownloadProgress.Complete -> {
                                                            hrDownloading = false
                                                            hrDownloaded.value = true
                                                        }
                                                        is DownloadProgress.Error -> {
                                                            hrDownloading = false
                                                            hrError = progress.message
                                                        }
                                                        else -> {}
                                                    }
                                                }
                                            }
                                        }) {
                                            Text("下载", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                                hrError?.let {
                                    Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        } else {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                            ) {
                                FilterChip(
                                    selected = sttStreamingMode == "pseudo",
                                    onClick = { sttStreamingMode = "pseudo" },
                                    label = { Text("伪流式（实时显示）", style = MaterialTheme.typography.bodySmall) },
                                    shape = ShapeTokens.largeShape,
                                )
                                FilterChip(
                                    selected = sttStreamingMode == "batch",
                                    onClick = { sttStreamingMode = "batch" },
                                    label = { Text("录制后识别（更准确）", style = MaterialTheme.typography.bodySmall) },
                                    shape = ShapeTokens.largeShape,
                                )
                            }
                            Text(
                                text = "非流式模型下，录制后识别可获得更准确的结果",
                                style = MaterialTheme.typography.bodySmall,
                                color = themeTextGrey(),
                            )
                        }

                        if (sttEngine == "local") {
                            val sttModelManager = remember { ModelManager }
                            val scope = rememberCoroutineScope()
                            var downloadingModel by rememberSaveable { mutableStateOf<ModelType?>(null) }
                            var downloadProgress by rememberSaveable { mutableStateOf(0f) }
                            var downloadStatusText by rememberSaveable { mutableStateOf("") }
                            var showDeleteConfirm by rememberSaveable { mutableStateOf<ModelType?>(null) }

                            var sttDialogVisible by rememberSaveable { mutableStateOf(false) }
                            var sttDialogModelName by rememberSaveable { mutableStateOf("") }
                            var sttDialogModelDesc by rememberSaveable { mutableStateOf("") }
                            var sttDialogPercent by rememberSaveable { mutableStateOf(0) }
                            var sttDialogDownloadedMb by rememberSaveable { mutableStateOf(0) }
                            var sttDialogTotalMb by rememberSaveable { mutableStateOf(0) }
                            var sttDialogSpeed by rememberSaveable { mutableStateOf("") }
                            var sttDialogStatus by rememberSaveable { mutableStateOf("idle") }
                            var sttDialogStatusDetail by rememberSaveable { mutableStateOf("") }

                            HorizontalDivider(color = themeBorder())
                            Text(
                                text = stringResource(R.string.settings_local_models_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )

                            val downloadedModels = sttModelManager.AVAILABLE_MODELS.filter {
                                sttModelManager.isModelDownloaded(context, it.type)
                            }
                            if (downloadedModels.size > 1) {
                                var selectedLocalModel by rememberSaveable { mutableStateOf(vm.getSelectedLocalModel()) }
                                Text(
                                    text = "当前使用的模型",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(SizeTokens.compactSpacing),
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                ) {
                                    FilterChip(
                                        selected = selectedLocalModel.isBlank(),
                                        onClick = {
                                            selectedLocalModel = ""
                                            vm.saveSelectedLocalModel("")
                                        },
                                        label = { Text("自动", style = MaterialTheme.typography.labelSmall) },
                                        shape = ShapeTokens.largeShape,
                                    )
                                    downloadedModels.forEach { modelInfo ->
                                        val label = when (modelInfo.type) {
                                            ModelType.PARAFORMER -> "Paraformer"
                                            ModelType.SENSE_VOICE_SMALL -> "SenseVoice"
                                            ModelType.FUNASR_NANO_INT8 -> "FunASR Nano"
                                            ModelType.STREAMING_PARAFORMER -> "Paraformer 流式"
                                            else -> modelInfo.type.name
                                        }
                                        FilterChip(
                                            selected = selectedLocalModel == modelInfo.type.name,
                                            onClick = {
                                                selectedLocalModel = modelInfo.type.name
                                                vm.saveSelectedLocalModel(modelInfo.type.name)
                                            },
                                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                            shape = ShapeTokens.largeShape,
                                        )
                                    }
                                }
                            }

                            sttModelManager.AVAILABLE_MODELS.forEach { modelInfo ->
                                val isDownloaded = sttModelManager.isModelDownloaded(context, modelInfo.type)
                                val isDownloading = downloadingModel == modelInfo.type
                                val sizeStr = if (modelInfo.sizeBytes < 1024 * 1024)
                                    "${modelInfo.sizeBytes / 1024}KB"
                                else
                                    "${modelInfo.sizeBytes / (1024 * 1024)}MB"

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = SizeTokens.compactSpacing),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f, fill = false)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = when (modelInfo.type) {
                                                    ModelType.PARAFORMER -> "Paraformer"
                                                    ModelType.SENSE_VOICE_SMALL -> "SenseVoice"
                                                    ModelType.FUNASR_NANO_INT8 -> "FunASR Nano"
                                                    ModelType.STREAMING_PARAFORMER -> "Paraformer (流式)"
                                                    else -> modelInfo.modelName
                                                },
                                                style = MaterialTheme.typography.labelLarge,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Spacer(Modifier.width(SpacingTokens.sm))
                                            if (isDownloaded) {
                                                Surface(
                                                    shape = ShapeTokens.extraSmallShape,
                                                    color = SuccessGreen.copy(alpha = BadgeBgAlpha),
                                                ) {
                                                    Text(
                                                        text = "已下载",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = SuccessGreen,
                                                        modifier = Modifier.padding(horizontal = SizeTokens.badgeHorizontalPadding, vertical = SizeTokens.badgeVerticalPadding),
                                                    )
                                                }
                                            } else if (modelInfo.type == sttModelManager.getRecommendedModel(context)) {
                                                Surface(
                                                    shape = ShapeTokens.extraSmallShape,
                                                    color = MaterialTheme.colorScheme.primary.copy(alpha = BadgeBgAlpha),
                                                ) {
                                                    Text(
                                                        text = "推荐",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.padding(horizontal = SizeTokens.badgeHorizontalPadding, vertical = SizeTokens.badgeVerticalPadding),
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            text = "$sizeStr | ${modelInfo.minRamMB}MB RAM",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = themeTextGrey(),
                                        )

                                        if (isDownloading && downloadProgress > 0f) {
                                            LinearProgressIndicator(
                                                progress = { downloadProgress },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(SizeTokens.progressTrackHeight)
                                                    .padding(top = SpacingTokens.xs),
                                                color = MaterialTheme.colorScheme.primary,
                                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                            )
                                            Text(
                                                text = downloadStatusText,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }

                                    if (isDownloading) {
                                        TextButton(onClick = { downloadingModel = null }) {
                                            Text("取消", style = MaterialTheme.typography.bodySmall)
                                        }
                                    } else if (isDownloaded) {
                                        IconButton(
                                            onClick = { showDeleteConfirm = modelInfo.type },
                                            modifier = Modifier.size(SpacingTokens.xxl),
                                        ) {
                                            Icon(
                                                Icons.Outlined.DeleteOutline,
                                                contentDescription = "删除模型",
                                                modifier = Modifier.size(SpacingTokens.lg),
                                                tint = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    } else {
                                        FilledTonalButton(
                                            contentPadding = PaddingValues(horizontal = SpacingTokens.md, vertical = SpacingTokens.xs),
                                            onClick = {
                                                downloadingModel = modelInfo.type
                                                downloadProgress = 0f
                                                downloadStatusText = context.getString(R.string.settings_preparing)

                                                sttDialogModelName = when (modelInfo.type) {
                                                    ModelType.PARAFORMER -> "Paraformer"
                                                    ModelType.SENSE_VOICE_SMALL -> "SenseVoice"
                                                    ModelType.FUNASR_NANO_INT8 -> "FunASR Nano"
                                                    ModelType.STREAMING_PARAFORMER -> "Paraformer (流式)"
                                                    else -> modelInfo.modelName
                                                }
                                                sttDialogModelDesc = "本地离线语音识别模型"
                                                sttDialogPercent = 0
                                                sttDialogDownloadedMb = 0
                                                sttDialogTotalMb = (modelInfo.sizeBytes / (1024 * 1024)).toInt()
                                                sttDialogSpeed = ""
                                                sttDialogStatus = "downloading"
                                                sttDialogStatusDetail = ""
                                                sttDialogVisible = true

                                                SttDownloadService.start(context, sttDialogModelName)

                                                scope.launch {
                                                    sttModelManager.downloadModel(context, modelInfo.type)
                                                        .collect { progress ->
                                                            when (progress) {
                                                                is ModelManager.DownloadProgress.Downloading -> {
                                                                    downloadProgress = progress.progress
                                                                    downloadStatusText = "下载中 ${(progress.progress * 100).toInt()}%"
                                                                    sttDialogPercent = (progress.progress * 100).toInt()
                                                                    sttDialogDownloadedMb = ((progress.progress * sttDialogTotalMb).toInt())
                                                                    sttDialogStatus = "downloading"
                                                                    SttDownloadService.updateProgress(
                                                                        context, sttDialogModelName,
                                                                        sttDialogPercent, sttDialogDownloadedMb, sttDialogTotalMb, 0L,
                                                                    )
                                                                }
                                                                is ModelManager.DownloadProgress.SourceSwitch -> {
                                                                    downloadStatusText = "切换源: ${progress.sourceName} (${progress.current}/${progress.total})"
                                                                    sttDialogStatus = "sourceSwitch"
                                                                    sttDialogStatusDetail = "切换到 ${progress.sourceName} (${progress.current}/${progress.total})"
                                                                }
                                                                is ModelManager.DownloadProgress.Error -> {
                                                                    downloadingModel = null
                                                                    downloadStatusText = "失败: ${progress.message}"
                                                                    sttDialogStatus = "error"
                                                                    sttDialogStatusDetail = progress.message
                                                                    SttDownloadService.fail(context, sttDialogModelName, progress.message)
                                                                }
                                                                is ModelManager.DownloadProgress.Complete -> {
                                                                    downloadingModel = null
                                                                    downloadStatusText = ""
                                                                    sttDialogVisible = false
                                                                    SttDownloadService.complete(context, sttDialogModelName)
                                                                }
                                                            }
                                                        }
                                                    downloadingModel = null
                                                    SttDownloadService.stop(context)
                                                }
                                            },
                                        ) {
                                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(SizeTokens.iconXs))
                                            Spacer(Modifier.width(SpacingTokens.xs))
                                            Text(stringResource(R.string.action_download), style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }

                            if (showDeleteConfirm != null) {
                                AlertDialog(
                                    onDismissRequest = { showDeleteConfirm = null },
                                    title = { Text(stringResource(R.string.msg_delete_model)) },
                                    text = { Text(stringResource(R.string.msg_delete_model_confirm)) },
                                    confirmButton = {
                                        TextButton(onClick = {
                                            sttModelManager.clearModelCache(context, showDeleteConfirm!!)
                                            showDeleteConfirm = null
                                        }) {
                                            Text(stringResource(R.string.msg_confirm_delete), color = MaterialTheme.colorScheme.error)
                                        }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showDeleteConfirm = null }) {
                                            Text(stringResource(R.string.action_cancel))
                                        }
                                    },
                                )
                            }

                            if (sttDialogVisible) {
                                SttModelDownloadDialog(
                                    modelName = sttDialogModelName,
                                    modelDescription = sttDialogModelDesc,
                                    percent = sttDialogPercent,
                                    downloadedMb = sttDialogDownloadedMb,
                                    totalMb = sttDialogTotalMb,
                                    speedText = sttDialogSpeed,
                                    status = sttDialogStatus,
                                    statusDetail = sttDialogStatusDetail,
                                    onDismiss = { sttDialogVisible = false },
                                    onCancel = {
                                        sttDialogVisible = false
                                        downloadingModel = null
                                        SttDownloadService.stop(context)
                                    },
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = themeBorder())

                    // TTS
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = stringResource(R.string.settings_tts_section), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Switch(checked = ttsEnabled, onCheckedChange = { ttsEnabled = it })
                    }
                    if (ttsEnabled) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = stringResource(R.string.settings_tts_engine), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                            ) {
                                FilterChip(
                                    selected = ttsEngine == "system",
                                    onClick = { ttsEngine = "system" },
                                    label = { Text("系统 TTS", style = MaterialTheme.typography.bodySmall) },
                                    shape = ShapeTokens.largeShape,
                                )
                                FilterChip(
                                    selected = ttsEngine == "stepaudio",
                                    onClick = { ttsEngine = "stepaudio" },
                                    label = { Text("StepAudio TTS", style = MaterialTheme.typography.bodySmall) },
                                    shape = ShapeTokens.largeShape,
                                )
                                FilterChip(
                                    selected = ttsEngine == "mimo",
                                    onClick = { ttsEngine = "mimo" },
                                    label = { Text("MiMo TTS", style = MaterialTheme.typography.bodySmall) },
                                    shape = ShapeTokens.largeShape,
                                )
                            }
                        }
                        if (ttsEngine == "stepaudio") {
                            Text(
                                text = stringResource(R.string.settings_stepaudio_tts_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = InterviewPurple,
                            )
                        }
                        if (ttsEngine == "mimo") {
                            Text(
                                text = stringResource(R.string.settings_mimo_tts_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = AccentOrange,
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = stringResource(R.string.settings_auto_read), modifier = Modifier.weight(1f))
                        Switch(checked = ttsAuto, onCheckedChange = { ttsAuto = it }, enabled = ttsEnabled)
                    }
                    if (ttsEnabled && ttsAuto && ttsEngine != "system") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = stringResource(R.string.settings_download_audio_only), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Switch(
                                checked = ttsDownloadOnly,
                                onCheckedChange = { ttsDownloadOnly = it },
                            )
                        }
                        if (ttsDownloadOnly) {
                            Text(
                                text = stringResource(R.string.settings_download_audio_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = themeTextGrey(),
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.settings_play_audio_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = SuccessGreen,
                            )
                        }
                    }
                    Text(text = stringResource(R.string.settings_speed))
                    Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
                        Button(onClick = { ttsRate = 0.85f }, enabled = ttsEnabled, shape = ShapeTokens.smallShape) { Text(stringResource(R.string.settings_speed_slow)) }
                        Button(onClick = { ttsRate = 1.0f }, enabled = ttsEnabled, shape = ShapeTokens.smallShape) { Text(stringResource(R.string.settings_speed_normal)) }
                        Button(onClick = { ttsRate = 1.2f }, enabled = ttsEnabled, shape = ShapeTokens.smallShape) { Text(stringResource(R.string.settings_speed_fast)) }
                    }
                    Text(text = stringResource(R.string.settings_language))
                    Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
                        Button(onClick = { ttsLocaleTag = "zh-CN" }, enabled = ttsEnabled, shape = ShapeTokens.smallShape) { Text(stringResource(R.string.settings_chinese)) }
                        Button(onClick = { ttsLocaleTag = "en-US" }, enabled = ttsEnabled, shape = ShapeTokens.smallShape) { Text(stringResource(R.string.settings_english)) }
                    }

                    HorizontalDivider(color = themeBorder())

                    // 声纹识别
                    SettingNavigationRow(
                        title = stringResource(R.string.settings_voiceprint_section),
                        subtitle = stringResource(R.string.settings_voiceprint_desc),
                        icon = Icons.Default.Fingerprint,
                        iconTint = InterviewPurple,
                        onClick = toVoiceprint,
                        showDivider = false,
                    )

                    HorizontalDivider(color = themeBorder())

                    // 录音时长限制
                    Text("录音时长限制", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = stringResource(R.string.settings_duration_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = themeTextGrey(),
                    )
                    listOf("录音" to "RECORDING", "手机内录" to "INTERNAL").forEach { (label, modeKey) ->
                        var expanded by rememberSaveable { mutableStateOf(false) }
                        val currentHours = rememberSaveable { mutableStateOf(vm.getRecordingMaxHours(modeKey)) }
                        val hoursOptions = listOf(0, 1, 2, 3, 5, 8, 12, 24)
                        val hoursLabels = listOf("无限制", "1小时", "2小时", "3小时", "5小时", "8小时", "12小时", "24小时")
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(text = label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Box {
                                OutlinedButton(
                                    onClick = { expanded = true },
                                    shape = ShapeTokens.smallShape,
                                    contentPadding = PaddingValues(horizontal = SpacingTokens.md, vertical = SpacingTokens.xs),
                                ) {
                                    Text(
                                        text = if (currentHours.value == 0) "无限制" else "${currentHours.value}小时",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(SizeTokens.iconMd))
                                }
                                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                    hoursOptions.forEachIndexed { index, h ->
                                        DropdownMenuItem(
                                            text = { Text(hoursLabels[index]) },
                                            onClick = {
                                                currentHours.value = h
                                                vm.saveRecordingMaxHours(modeKey, h)
                                                expanded = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            }

            // ── 知识与记忆 ──
            Box(modifier = Modifier.onGloballyPositioned { coordinates ->
                sectionOffsets[SettingsSection.MEMORY] = coordinates.positionInParent().y.toInt()
            }) {
                SettingGroup(title = stringResource(R.string.settings_section_memory)) {
                SettingNavigationRow(
                    title = stringResource(R.string.settings_memory_entries),
                    subtitle = stringResource(R.string.settings_memory_count, memoryCount),
                    icon = Icons.Default.Memory,
                    iconTint = AccentBlue,
                    onClick = toMemory,
                )
                SettingNavigationRow(
                    title = stringResource(R.string.settings_my_notes),
                    subtitle = stringResource(R.string.settings_notes_desc),
                    icon = Icons.Default.Book,
                    iconTint = SuccessGreen,
                    onClick = toNotes,
                )
                SettingNavigationRow(
                    title = stringResource(R.string.settings_hippocampus_index),
                    subtitle = "共 $hippocampusCount 条索引条目",
                    icon = Icons.Default.Psychology,
                    iconTint = InterviewPurple,
                    onClick = toHippocampus,
                )
                SettingSwitchRow(
                    title = stringResource(R.string.settings_auto_title),
                    subtitle = stringResource(R.string.settings_auto_title_desc),
                    checked = autoGenerateNoteTitle,
                    onCheckedChange = {
                        autoGenerateNoteTitle = it
                        vm.saveAutoGenerateNoteTitle(it)
                    },
                    showDivider = false,
                )
            }
            }

            // ── 功能 ──
            Box(modifier = Modifier.onGloballyPositioned { coordinates ->
                sectionOffsets[SettingsSection.FEATURES] = coordinates.positionInParent().y.toInt()
            }) {
                SettingGroup(title = stringResource(R.string.settings_section_features)) {
                Column(
                    modifier = Modifier.padding(horizontal = SpacingTokens.lg, vertical = SpacingTokens.md),
                    verticalArrangement = Arrangement.spacedBy(SpacingTokens.md),
                ) {
                    // 语言设置
                    var appLanguage by rememberSaveable { mutableStateOf(vm.getAppLanguage()) }
                    Text(text = stringResource(R.string.settings_language), style = MaterialTheme.typography.titleSmall.copy(color = themeTextDark()))
                    val languageOptions = listOf(
                        "system" to stringResource(R.string.language_system),
                        "zh" to stringResource(R.string.language_chinese),
                        "en" to stringResource(R.string.language_english),
                        "ja" to stringResource(R.string.language_japanese),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        languageOptions.forEach { (tag, label) ->
                            FilterChip(
                                selected = appLanguage == tag,
                                onClick = {
                                    if (appLanguage == tag) return@FilterChip
                                    appLanguage = tag
                                    vm.saveAppLanguage(tag)
                                    LocaleHelper.setLocale(context, tag)
                                    (context as? Activity)?.let { activity ->
                                        activity.recreate()
                                    }
                                },
                                label = { Text(label, style = MaterialTheme.typography.bodyMedium) },
                            )
                        }
                    }

                    HorizontalDivider(color = themeBorder())

                    // 主题设置
                    Text(text = "外观主题", style = MaterialTheme.typography.titleSmall.copy(color = themeTextDark()))
                    val themeOptions = listOf(
                        "system" to "跟随系统",
                        "light" to "浅色",
                        "dark" to "深色",
                    )
                    val themeIcons = mapOf(
                        "system" to Icons.Default.SettingsBrightness,
                        "light" to Icons.Default.LightMode,
                        "dark" to Icons.Default.DarkMode,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        themeOptions.forEach { (value, label) ->
                            FilterChip(
                                selected = themeMode == value,
                                onClick = {
                                    if (themeMode == value) return@FilterChip
                                    themeMode = value
                                    vm.saveThemeMode(value)
                                    (context as? Activity)?.recreate()
                                },
                                label = { Text(label, style = MaterialTheme.typography.bodyMedium) },
                                leadingIcon = {
                                    themeIcons[value]?.let {
                                        Icon(it, contentDescription = null, modifier = Modifier.size(SpacingTokens.lg))
                                    }
                                },
                            )
                        }
                    }
                    SettingSwitchRow(
                        title = "动态主题",
                        subtitle = "使用系统壁纸颜色（Android 12+）",
                        checked = dynamicColorEnabled,
                        onCheckedChange = {
                            dynamicColorEnabled = it
                            vm.saveDynamicColorEnabled(it)
                            (context as? Activity)?.recreate()
                        },
                        showDivider = false,
                    )

                    HorizontalDivider(color = themeBorder())

                    // 编辑器模式
                    var editorMode by rememberSaveable { mutableStateOf(vm.getEditorMode()) }
                    Text(text = "编辑器模式", style = MaterialTheme.typography.titleSmall.copy(color = themeTextDark()))
                    Text(
                        text = "笔记编辑器的默认输入模式",
                        style = MaterialTheme.typography.bodySmall,
                        color = themeTextGrey(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
                        FilterChip(
                            selected = editorMode == "richtext",
                            onClick = {
                                editorMode = "richtext"
                                vm.saveEditorMode("richtext")
                            },
                            label = { Text("富文本（推荐）", style = MaterialTheme.typography.bodyMedium) },
                        )
                        FilterChip(
                            selected = editorMode == "markdown",
                            onClick = {
                                editorMode = "markdown"
                                vm.saveEditorMode("markdown")
                            },
                            label = { Text("Markdown", style = MaterialTheme.typography.bodyMedium) },
                        )
                    }
                    Text(
                        text = if (editorMode == "richtext") "Notally 风格，选中文字即可加格式" else "适合开发者，手写语法",
                        style = MaterialTheme.typography.bodySmall,
                        color = themeTextGrey(),
                    )

                    HorizontalDivider(color = themeBorder())

                    // 后台运行
                    SettingSwitchRow(
                        title = stringResource(R.string.settings_bg_run_label),
                        subtitle = stringResource(R.string.settings_bg_run_desc),
                        checked = bgRunning,
                        onCheckedChange = {
                            bgRunning = it
                            if (it) {
                                val activity = context as? Activity
                                if (activity != null) {
                                    val status = BackgroundPermHelper.checkPermissions(context)
                                    if (!status.batteryOptOk) {
                                        BackgroundPermHelper.requestBatteryOptimization(activity)
                                    }
                                    if (!status.autostartGranted) {
                                        BackgroundPermHelper.requestAutostart(activity)
                                    }
                                }
                            }
                        },
                    )
                    if (!bgRunning) {
                        Text(
                            text = stringResource(R.string.settings_bg_run_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = themeForegroundMuted(),
                        )
                    }

                    HorizontalDivider(color = themeBorder())

                    // 位置与环境
                    SettingSwitchRow(
                        title = stringResource(R.string.settings_location),
                        subtitle = stringResource(R.string.settings_location_desc),
                        checked = locationEnabled,
                        onCheckedChange = {
                            if (it) {
                                locationPermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                            } else {
                                locationEnabled = false
                                vm.saveLocationEnabled(false)
                            }
                        },
                    )
                    // ★ Ham 模式：业余卫星通联辅助（需要位置感知）
                    SettingSwitchRow(
                        title = "Ham 模式（业余卫星）",
                        subtitle = if (hamModeEnabled) "已开启 — 自动启用位置感知以计算卫星过境" else "开启后可预测卫星过境、生成通联日志",
                        checked = hamModeEnabled,
                        icon = Icons.Default.Satellite,
                        onCheckedChange = {
                            if (it) {
                                // Ham 模式开启时，必须同时启用位置感知
                                hamModeEnabled = true
                                locationEnabled = true
                                vm.saveHamModeEnabled(true)
                                vm.saveLocationEnabled(true)
                                locationPermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                            } else {
                                hamModeEnabled = false
                                vm.saveHamModeEnabled(false)
                            }
                        },
                    )
                    SettingSwitchRow(
                        title = stringResource(R.string.settings_deep_thinking),
                        subtitle = stringResource(R.string.settings_deep_thinking_desc),
                        checked = deepThinkingEnabled,
                        onCheckedChange = {
                            deepThinkingEnabled = it
                            vm.saveDeepThinking(it)
                        },
                    )
                    SettingSwitchRow(
                        title = stringResource(R.string.settings_debug_mode),
                        subtitle = stringResource(R.string.settings_debug_mode_desc),
                        checked = debugMode,
                        onCheckedChange = {
                            debugMode = it
                            vm.saveDebugMode(it)
                        },
                    )
                    SettingSwitchRow(
                        title = stringResource(R.string.settings_calendar),
                        subtitle = stringResource(R.string.settings_calendar_desc),
                        checked = calendarEnabled,
                        onCheckedChange = {
                            if (it) {
                                calendarPermLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.READ_CALENDAR,
                                        Manifest.permission.WRITE_CALENDAR,
                                    )
                                )
                            } else {
                                calendarEnabled = false
                                vm.saveCalendarEnabled(false)
                            }
                        },
                    )
                    SettingSwitchRow(
                        title = stringResource(R.string.settings_health),
                        subtitle = stringResource(R.string.settings_health_desc),
                        checked = healthEnabled,
                        onCheckedChange = {
                            if (it) {
                                val availability = top.hsyscn.opedrgent.health.HealthConnectHelper.getAvailability(context)
                                when (availability) {
                                    top.hsyscn.opedrgent.health.HealthConnectAvailability.Available -> {
                                        activityRecognitionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                                    }
                                    top.hsyscn.opedrgent.health.HealthConnectAvailability.NeedsUpdate -> {
                                        scope.launch { snackbar.showSnackbar("请先更新 Health Connect 应用") }
                                        top.hsyscn.opedrgent.health.HealthConnectHelper.openHealthConnectSettings(context)
                                    }
                                    else -> {
                                        scope.launch { snackbar.showSnackbar("设备不支持 Health Connect") }
                                    }
                                }
                            } else {
                                healthEnabled = false
                                vm.saveHealthEnabled(false)
                            }
                        },
                        showDivider = false,
                    )

                    HorizontalDivider(color = themeBorder())

                    // 联网查询
                    SettingSwitchRow(
                        title = stringResource(R.string.settings_web_search_label),
                        subtitle = stringResource(R.string.settings_web_search_desc),
                        checked = webSearchEnabled,
                        onCheckedChange = {
                            webSearchEnabled = it
                            vm.saveWebSearchEnabled(it)
                        },
                    )
                    if (webSearchEnabled) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = stringResource(R.string.settings_search_engine), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                            ) {
                                FilterChip(
                                    selected = webSearchSource == "own",
                                    onClick = { webSearchSource = "own"; vm.saveWebSearchSource("own") },
                                    label = { Text(stringResource(R.string.settings_own_engine), style = MaterialTheme.typography.bodySmall) },
                                    shape = ShapeTokens.largeShape,
                                )
                                FilterChip(
                                    selected = webSearchSource == "provider",
                                    onClick = { webSearchSource = "provider"; vm.saveWebSearchSource("provider") },
                                    label = { Text(stringResource(R.string.settings_provider_builtin), style = MaterialTheme.typography.bodySmall) },
                                    shape = ShapeTokens.largeShape,
                                )
                                FilterChip(
                                    selected = webSearchSource == "external",
                                    onClick = { webSearchSource = "external"; vm.saveWebSearchSource("external") },
                                    label = { Text(stringResource(R.string.settings_external_api), style = MaterialTheme.typography.bodySmall) },
                                    shape = ShapeTokens.largeShape,
                                )
                            }
                        }
                        if (webSearchSource == "own") {
                            Text(
                                text = stringResource(R.string.settings_own_engine_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = themeTextGrey(),
                            )
                        }
                        if (webSearchSource == "provider") {
                            Text(
                                text = stringResource(R.string.settings_provider_builtin_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = AccentOrange,
                            )
                        }
                        if (webSearchSource == "external") {
                            Text(
                                text = stringResource(R.string.settings_external_api_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = themeTextGrey(),
                            )
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.settings_web_search_off_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = SuccessGreen,
                        )
                    }

                    if (webSearchEnabled && webSearchSource == "external") {
                        HorizontalDivider(color = themeBorder())
                        Text(
                            text = "外挂搜索 API 配置",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        OutlinedTextField(
                            value = tavilyApiKey,
                            onValueChange = { tavilyApiKey = it },
                            label = { Text("Tavily API Key（AI专用搜索，免费1000次/月）") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = braveApiKey,
                            onValueChange = { braveApiKey = it },
                            label = { Text("Brave Search API Key") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = searchProviderOrder,
                            onValueChange = { searchProviderOrder = it },
                            label = { Text("搜索优先级（如 tavily,brave,bing,baidu,ddg）") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }

                    HorizontalDivider(color = themeBorder())

                    // OCR 模型管理
                    Text(stringResource(R.string.settings_ocr_section), style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = stringResource(R.string.settings_ocr_enhanced), style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = stringResource(R.string.settings_ocr_enhanced_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = themeTextGrey(),
                            )
                        }
                    }
                    val ocrModelManager = remember { top.hsyscn.opedrgent.ocr.OcrModelManager }
                    val ocrModel = ocrModelManager.PP_OCR_V6
                    val ocrDownloaded = remember { mutableStateOf(ocrModelManager.isModelDownloaded(context, ocrModel.id)) }
                    val ocrDownloading = remember { mutableStateOf(false) }
                    val ocrProgress = remember { mutableStateOf(0f) }
                    val ocrStatusText = remember { mutableStateOf("") }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = ocrModel.displayName,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (ocrDownloaded.value) {
                            Surface(shape = ShapeTokens.extraSmallShape, color = SuccessGreen.copy(alpha = BadgeBgAlpha)) {
                                Text(stringResource(R.string.settings_model_downloaded), style = MaterialTheme.typography.labelSmall, color = SuccessGreen, modifier = Modifier.padding(horizontal = SizeTokens.badgeHorizontalPadding, vertical = SizeTokens.badgeVerticalPadding))
                            }
                            Spacer(Modifier.width(SpacingTokens.sm))
                            TextButton(onClick = {
                                ocrModelManager.clearModelCache(context, ocrModel.id)
                                ocrDownloaded.value = false
                            }) { Text(stringResource(R.string.settings_delete_ocr), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        } else if (ocrDownloading.value) {
                            LinearProgressIndicator(progress = { ocrProgress.value }, modifier = Modifier.weight(1f).height(SizeTokens.progressTrackHeight))
                            Spacer(Modifier.width(SpacingTokens.sm))
                            Text(ocrStatusText.value, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        } else {
                            FilledTonalButton(
                                contentPadding = PaddingValues(horizontal = SpacingTokens.md, vertical = SpacingTokens.xs),
                                onClick = {
                                    ocrDownloading.value = true
                                    scope.launch {
                                        ocrModelManager.downloadModel(context, ocrModel.id).collect { progress ->
                                            when (progress) {
                                                is top.hsyscn.opedrgent.ocr.OcrDownloadProgress.Downloading -> {
                                                    ocrProgress.value = progress.progress
                                                    ocrStatusText.value = "下载中 ${(progress.progress * 100).toInt()}%"
                                                }
                                                is top.hsyscn.opedrgent.ocr.OcrDownloadProgress.SourceSwitch -> {
                                                    ocrStatusText.value = "准备下载..."
                                                }
                                                is top.hsyscn.opedrgent.ocr.OcrDownloadProgress.Complete -> {
                                                    ocrDownloaded.value = true
                                                    ocrDownloading.value = false
                                                    ocrStatusText.value = ""
                                                }
                                                is top.hsyscn.opedrgent.ocr.OcrDownloadProgress.Error -> {
                                                    ocrDownloading.value = false
                                                    ocrStatusText.value = "失败: ${progress.message}"
                                                }
                                            }
                                        }
                                    }
                                },
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(SizeTokens.iconXs))
                                Spacer(Modifier.width(SpacingTokens.xs))
                                Text(stringResource(R.string.action_download), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    HorizontalDivider(color = themeBorder())

                    // 技能库 & 自动化
                    SettingNavigationRow(
                        title = stringResource(R.string.title_skills_library),
                        icon = Icons.Default.AutoAwesome,
                        iconTint = AccentOrange,
                        onClick = toSkills,
                    )
                    SettingNavigationRow(
                        title = stringResource(R.string.title_automations),
                        icon = Icons.Default.Schedule,
                        iconTint = BubbleBlue,
                        onClick = toAutomations,
                        showDivider = false,
                    )
                }
            }
            }

            // ── 导入导出 ──
            Box(modifier = Modifier.onGloballyPositioned { coordinates ->
                sectionOffsets[SettingsSection.IMPORT_EXPORT] = coordinates.positionInParent().y.toInt()
            }) {
                SettingGroup(title = stringResource(R.string.settings_section_import_export)) {
                Column(
                    modifier = Modifier.padding(horizontal = SpacingTokens.lg, vertical = SpacingTokens.md),
                    verticalArrangement = Arrangement.spacedBy(SpacingTokens.md),
                ) {
                    var syncServerUrl by rememberSaveable { mutableStateOf(vm.getSyncConfig().serverUrl) }
                    var syncUsername by rememberSaveable { mutableStateOf(vm.getSyncConfig().username) }
                    var syncPassword by rememberSaveable { mutableStateOf(vm.getSyncConfig().password) }
                    var syncRemotePath by rememberSaveable { mutableStateOf(vm.getSyncConfig().remotePath) }
                    val isSyncing by vm.isSyncing.collectAsState()
                    val syncResult by vm.syncState.collectAsState()

                    Text("云同步（WebDAV）", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "通过 WebDAV 同步笔记到私有云（坚果云、NextCloud 等）",
                        style = MaterialTheme.typography.bodySmall,
                        color = themeTextGrey(),
                    )
                    OutlinedTextField(
                        value = syncServerUrl,
                        onValueChange = { syncServerUrl = it },
                        label = { Text("服务器地址") },
                        placeholder = { Text("https://dav.example.com") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
                        OutlinedTextField(
                            value = syncUsername,
                            onValueChange = { syncUsername = it },
                            label = { Text("用户名") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = syncPassword,
                            onValueChange = { syncPassword = it },
                            label = { Text("密码") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                    }
                    OutlinedTextField(
                        value = syncRemotePath,
                        onValueChange = { syncRemotePath = it },
                        label = { Text("远端路径") },
                        placeholder = { Text("/opedrgent/notes/") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
                        FilledTonalButton(
                            onClick = {
                                val config = top.hsyscn.opedrgent.sync.WebDavConfig(
                                    serverUrl = syncServerUrl.trim(),
                                    username = syncUsername.trim(),
                                    password = syncPassword.trim(),
                                    remotePath = syncRemotePath.ifBlank { "/opedrgent/notes/" },
                                )
                                vm.saveSyncConfig(config)
                                scope.launch {
                                    val ok = vm.testSyncConnection()
                                    snackbar.showSnackbar(if (ok) "连接成功" else "连接失败，请检查配置")
                                }
                            },
                            enabled = syncServerUrl.isNotBlank() && !isSyncing,
                            shape = ShapeTokens.smallShape,
                        ) {
                            Text("测试连接", style = MaterialTheme.typography.bodyMedium)
                        }
                        Button(
                            onClick = {
                                val config = top.hsyscn.opedrgent.sync.WebDavConfig(
                                    serverUrl = syncServerUrl.trim(),
                                    username = syncUsername.trim(),
                                    password = syncPassword.trim(),
                                    remotePath = syncRemotePath.ifBlank { "/opedrgent/notes/" },
                                )
                                vm.saveSyncConfig(config)
                                vm.runSync()
                            },
                            enabled = syncServerUrl.isNotBlank() && !isSyncing,
                            shape = ShapeTokens.smallShape,
                        ) {
                            if (isSyncing) {
                                CircularProgressIndicator(modifier = Modifier.size(SpacingTokens.lg), strokeWidth = SpacingTokens.xxs)
                                Spacer(Modifier.width(SpacingTokens.sm))
                                Text("同步中...", style = MaterialTheme.typography.bodyMedium)
                            } else {
                                Text("立即同步", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }

                    syncResult?.let { result ->
                        val lastSync = vm.getLastSyncTime()
                        val timeStr = if (lastSync > 0) {
                            val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                            sdf.format(java.util.Date(lastSync))
                        } else "从未"
                        Text(
                            text = buildString {
                                append("上次同步: $timeStr")
                                if (result.errors > 0) {
                                    append(" | 错误: ${result.errors}")
                                } else {
                                    append(" | 上传: ${result.uploaded} 下载: ${result.downloaded} 耗时: ${result.duration}ms")
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (result.errors > 0) MaterialTheme.colorScheme.error else themeTextGrey(),
                        )
                    } ?: run {
                        val lastSync = vm.getLastSyncTime()
                        if (lastSync > 0) {
                            val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                            Text(
                                text = "上次同步: ${sdf.format(java.util.Date(lastSync))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = themeTextGrey(),
                            )
                        }
                    }

                    HorizontalDivider(color = themeBorder())

                    SettingNavigationRow(
                        title = "导出与分享",
                        subtitle = "导出笔记、对话记录为多种格式",
                        icon = Icons.Default.ImportExport,
                        iconTint = SuccessGreen,
                        onClick = toExport,
                        showDivider = false,
                    )
                }
            }
            }

            // ── 关于 ──
            Box(modifier = Modifier.onGloballyPositioned { coordinates ->
                sectionOffsets[SettingsSection.ABOUT] = coordinates.positionInParent().y.toInt()
            }) {
                SettingGroup(title = stringResource(R.string.settings_section_about)) {
                Column(
                    modifier = Modifier.padding(SpacingTokens.lg),
                    verticalArrangement = Arrangement.spacedBy(SpacingTokens.md),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                            contentDescription = null,
                            modifier = Modifier.size(SizeTokens.sectionIcon),
                        )
                        Spacer(modifier = Modifier.width(SpacingTokens.md))
                        Column {
                            Text(text = "Opedrgent", style = MaterialTheme.typography.headlineLarge, color = themeTextDark())
                            Text(
                                text = stringResource(R.string.about_version, "1.0.0"),
                                style = MaterialTheme.typography.bodySmall,
                                color = themeTextGrey()
                            )
                        }
                    }

                    Text(
                        text = "hsyscn.top",
                        color = BubbleBlue,
                        modifier = Modifier.clickable(role = Role.Button, onClickLabel = stringResource(R.string.cd_open_link)) {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://hsyscn.top")))
                        }
                    )
                    Text(
                        text = "BI4MIB.CN",
                        color = BubbleBlue,
                        modifier = Modifier.clickable(role = Role.Button, onClickLabel = stringResource(R.string.cd_open_link)) {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://bi4mib.cn")))
                        }
                    )
                    Text(
                        text = stringResource(R.string.about_visit_project),
                        color = BubbleBlue,
                        modifier = Modifier.clickable(role = Role.Button, onClickLabel = stringResource(R.string.cd_open_link)) {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/HaohanHe/Opedrgent")))
                        }
                    )

                    HorizontalDivider(color = themeBorder())

                    Text(
                        text = stringResource(R.string.about_open_source),
                        style = MaterialTheme.typography.titleSmall.copy(color = themeTextDark())
                    )
                    Text(
                        text = stringResource(R.string.about_open_source_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = themeTextGrey()
                    )

                    SettingNavigationRow(
                        title = stringResource(R.string.about_view_open_source),
                        icon = Icons.Default.Info,
                        iconTint = themeForegroundMuted(),
                        onClick = toOpenSource,
                        showDivider = false,
                    )
                }
            }
            }

            // ── 底部操作 ──
            Button(
                onClick = {
                    vm.saveTts(
                        enabled = ttsEnabled,
                        autoSpeak = ttsAuto,
                        rate = ttsRate,
                        pitch = ttsPitch,
                        localeTag = ttsLocaleTag,
                        mimoEnabled = ttsMimoEnabled,
                        mimoVoice = vm.getTtsMimoVoice(),
                        downloadOnly = ttsDownloadOnly,
                    )
                    vm.saveTtsEngine(ttsEngine)
                    vm.saveSttEnabled(sttEnabled)
                    vm.saveSttEngine(sttEngine)
                    vm.saveSttStreamingMode(sttStreamingMode)
                    vm.saveBackgroundRunning(bgRunning)
                    vm.saveLocationEnabled(locationEnabled)
                    vm.saveHamModeEnabled(hamModeEnabled)
                    vm.saveDebugMode(debugMode)
                    vm.saveDeepThinking(deepThinkingEnabled)
                    vm.saveCalendarEnabled(calendarEnabled)
                    vm.saveHealthEnabled(healthEnabled)
                    vm.saveJinaApiKey(jinaApiKey.takeIf { it.isNotBlank() })
                    vm.saveTavilyApiKey(tavilyApiKey.takeIf { it.isNotBlank() })
                    vm.saveBraveApiKey(braveApiKey.takeIf { it.isNotBlank() })
                    vm.saveSearchProviderOrder(searchProviderOrder.takeIf { it.isNotBlank() } ?: "bing,baidu,jina")
                    if (!isLocalMode) {
                        val ok = vm.saveSettings(baseUrl = baseUrl, apiKey = apiKey, model = model)
                        if (!ok) return@Button
                    }
                    onBack()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = ShapeTokens.smallShape,
            ) {
                Text(if (isLocalMode) stringResource(R.string.settings_save_settings) else stringResource(R.string.action_save))
            }
            Text(text = stringResource(R.string.settings_api_key_hint), modifier = Modifier.padding(top = SpacingTokens.xs), color = themeForegroundMuted())
            Button(
                onClick = { vm.clearApiKey() },
                modifier = Modifier.fillMaxWidth(),
                enabled = vm.hasApiKey(),
                shape = ShapeTokens.smallShape,
            ) {
                Text(stringResource(R.string.settings_clear_api_key))
            }
            if (vm.hasApiKey()) {
                Text(stringResource(R.string.settings_saved_key_hint), color = themeForegroundMuted())
            } else {
                Text(stringResource(R.string.settings_not_set_key), color = themeForegroundMuted())
            }
        }

        if (isLandscape() && isAtLeastMediumWidth()) {
            // 横屏/大屏：左侧章节导航 + 右侧内容，占满剩余空间并限制最大宽度。
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                Column(
                    modifier = Modifier
                        .width(SizeTokens.settingsDrawerWidth)
                        .fillMaxHeight()
                        .padding(vertical = SpacingTokens.lg, horizontal = SpacingTokens.md)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = stringResource(R.string.title_settings),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(start = SpacingTokens.sm, bottom = SpacingTokens.lg),
                    )
                    SettingsSection.entries.forEach { section ->
                        val selected = section == selectedSection
                        NavigationDrawerItem(
                            icon = { Icon(section.icon, contentDescription = null, modifier = Modifier.size(SizeTokens.iconMd)) },
                            label = { Text(stringResource(section.titleRes)) },
                            selected = selected,
                            onClick = {
                                selectedSection = section
                                scope.launch {
                                    val offset = sectionOffsets[section] ?: 0
                                    scrollState.animateScrollTo(offset.coerceAtLeast(0))
                                }
                            },
                            modifier = Modifier.padding(bottom = SpacingTokens.xs),
                        )
                    }
                }
                VerticalDivider(
                    modifier = Modifier.fillMaxHeight(),
                    thickness = SizeTokens.thinDividerThickness,
                    color = themeBorder(),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(scrollState),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    val contentMaxWidth = if (isExpandedWidth()) 840.dp else 640.dp
                    Column(modifier = Modifier.widthIn(max = contentMaxWidth)) {
                        settingsContent()
                    }
                }
            }
        } else {
            val settingsMaxWidth = if (isAtLeastMediumWidth()) 720.dp else Dp.Unspecified
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    modifier = Modifier
                        .then(
                            if (settingsMaxWidth != Dp.Unspecified) {
                                Modifier.fillMaxHeight().widthIn(max = settingsMaxWidth)
                            } else {
                                Modifier.fillMaxSize()
                            }
                        )
                        .verticalScroll(scrollState)
                        .padding(horizontal = SpacingTokens.lg, vertical = SpacingTokens.lg),
                    verticalArrangement = Arrangement.spacedBy(SpacingTokens.xl),
                ) {
                    settingsContent()
                }
            }
        }
    }

    // 本地模型选择对话框
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
                        snackbar.showSnackbar(context.getString(R.string.msg_download_model_first))
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
            title = { Text(stringResource(R.string.msg_memory_insufficient)) },
            text = { Text(msg ?: stringResource(R.string.msg_memory_insufficient_desc)) },
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
                }) { Text(stringResource(R.string.msg_still_try)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showMemoryWarning = null
                    isLocalMode = false
                    vm.saveLocalModelEnabled(false)
                }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun SettingGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            color = themeForegroundMuted(),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .padding(start = SpacingTokens.xs, bottom = SpacingTokens.sm)
                .semantics { heading() },
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = ShapeTokens.mediumShape,
            colors = CardDefaults.cardColors(containerColor = themeCardWhite()),
            elevation = CardDefaults.cardElevation(defaultElevation = ElevationTokens.sm),
        ) {
            Column {
                content()
            }
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    subtitle: String? = null,
    showDivider: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val clickable = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .then(clickable)
                .height(SizeTokens.settingRowHeight)
                .padding(horizontal = SpacingTokens.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(SpacingTokens.xxl)
                        .background(themeBackgroundSecondary(), shape = ShapeTokens.iconShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(SizeTokens.iconMd))
                }
                Spacer(Modifier.width(SpacingTokens.md))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = themeForeground(),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        color = themeForegroundMuted(),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing?.invoke()
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = if (icon != null) SizeTokens.settingDividerInset else SpacingTokens.lg),
                color = themeBorder(),
                thickness = SizeTokens.thinDividerThickness,
            )
        }
    }
}

@Composable
private fun SettingNavigationRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    subtitle: String? = null,
    value: String? = null,
    onClick: () -> Unit,
    showDivider: Boolean = true,
) {
    SettingRow(
        title = title,
        modifier = modifier,
        icon = icon,
        iconTint = iconTint,
        subtitle = subtitle,
        showDivider = showDivider,
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (value != null) {
                Text(
                    text = value,
                    color = themeForegroundMuted(),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )
                Spacer(Modifier.width(SpacingTokens.xs))
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = themeForegroundMuted(),
                modifier = Modifier.size(SizeTokens.iconLg),
            )
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    subtitle: String? = null,
    enabled: Boolean = true,
    showDivider: Boolean = true,
) {
    SettingRow(
        title = title,
        modifier = modifier,
        icon = icon,
        iconTint = iconTint,
        subtitle = subtitle,
        showDivider = showDivider,
        onClick = { if (enabled) onCheckedChange(!checked) },
    ) {
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
