@file:OptIn(ExperimentalMaterial3Api::class)

package top.hsyscn.opedrgent.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Fingerprint
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import top.hsyscn.opedrgent.R
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import top.hsyscn.opedrgent.llm.AvailableLocalModels
import top.hsyscn.opedrgent.llm.LocalLlmEngine
import top.hsyscn.opedrgent.llm.LocalLlmState
import top.hsyscn.opedrgent.llm.ModelDownloadManager
import top.hsyscn.opedrgent.stt.ModelManager
import top.hsyscn.opedrgent.stt.ModelType
import top.hsyscn.opedrgent.service.SttDownloadService
import top.hsyscn.opedrgent.settings.PROVIDER_PRESETS
import top.hsyscn.opedrgent.ui.components.ModelSelectorDialog
import top.hsyscn.opedrgent.ui.components.SttModelDownloadDialog
import top.hsyscn.opedrgent.ui.theme.BubbleBlue
import top.hsyscn.opedrgent.ui.theme.AccentOrange
import top.hsyscn.opedrgent.ui.theme.InterviewPurple
import top.hsyscn.opedrgent.ui.theme.SuccessGreen
import top.hsyscn.opedrgent.storage.HippocampusIndex
import top.hsyscn.opedrgent.utils.BackgroundPermHelper
import top.hsyscn.opedrgent.ui.theme.themeBgGray
import top.hsyscn.opedrgent.ui.theme.themeDividerColor
import top.hsyscn.opedrgent.ui.theme.themeTextDark
import top.hsyscn.opedrgent.ui.theme.themeTextGrey
import top.hsyscn.opedrgent.utils.LocaleHelper
import androidx.compose.ui.res.painterResource
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit, toSkills: () -> Unit, toAutomations: () -> Unit, toMemory: () -> Unit, toNotes: () -> Unit, toHippocampus: () -> Unit = {}, toVocabulary: () -> Unit = {}, toVoiceprint: () -> Unit = {}, hippocampus: HippocampusIndex? = null, showBackButton: Boolean = true, onInvisiblePartner: () -> Unit = {}, toOpenSource: () -> Unit = {}) {
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
    var ttsDownloadOnly by rememberSaveable { mutableStateOf(vm.isTtsDownloadOnly()) }
    var ttsMimoEnabled by rememberSaveable { mutableStateOf(vm.isTtsMimoEnabled()) }
    var ttsEngine by rememberSaveable { mutableStateOf(vm.getTtsEngine()) }
    var bgRunning by rememberSaveable { mutableStateOf(vm.isBackgroundRunning()) }
    var locationEnabled by rememberSaveable { mutableStateOf(vm.isLocationEnabled()) }
    var debugMode by rememberSaveable { mutableStateOf(vm.isDebugMode()) }
    var webSearchEnabled by rememberSaveable { mutableStateOf(vm.isWebSearchEnabled()) }
    var webSearchSource by rememberSaveable { mutableStateOf(vm.getWebSearchSource()) }
    var deepThinkingEnabled by rememberSaveable { mutableStateOf(vm.isDeepThinking()) }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_settings), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    if (showBackButton) {
                        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.cd_back)) }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbar) },
        containerColor = themeBgGray(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        item {
            // ── 语言设置 ──
            var appLanguage by rememberSaveable { mutableStateOf(vm.getAppLanguage()) }

            Card(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = stringResource(R.string.settings_language), fontWeight = FontWeight.Bold, color = themeTextDark())
                    Spacer(modifier = Modifier.height(10.dp))
                    
                    val languageOptions = listOf(
                        "system" to stringResource(R.string.language_system),
                        "zh" to stringResource(R.string.language_chinese),
                        "en" to stringResource(R.string.language_english),
                        "ja" to stringResource(R.string.language_japanese),
                    )
                    
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        languageOptions.forEach { (tag, label) ->
                            FilterChip(
                                selected = appLanguage == tag,
                                onClick = {
                                    if (appLanguage == tag) return@FilterChip
                                    appLanguage = tag
                                    vm.saveAppLanguage(tag)
                                    LocaleHelper.setLocale(context, tag)
                                    // 仅在语言真正改变时才 recreate，避免无谓卡顿
                                    (context as? Activity)?.let { activity ->
                                        activity.recreate()
                                    }
                                },
                                label = { Text(label, fontSize = 13.sp) },
                            )
                        }
                    }
                }
            }
        }

        item {
            HorizontalDivider()

            Text(stringResource(R.string.settings_model_provider), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            if (isLocalMode) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = BubbleBlue.copy(alpha = 0.06f)),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.DeveloperBoard, contentDescription = null, tint = BubbleBlue, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(stringResource(R.string.msg_local_mode_running), fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = BubbleBlue)
                            Text(stringResource(R.string.msg_local_mode_desc), color = themeTextGrey(), fontSize = 11.sp)
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
        }

        item {
            HorizontalDivider()

            Text(stringResource(R.string.settings_memory_management), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = toMemory,
                shape = RoundedCornerShape(14.dp),
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
                        Text(stringResource(R.string.settings_memory_entries), fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.settings_memory_count, memoryCount), style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.Default.ArrowForward, contentDescription = stringResource(R.string.cd_enter))
                }
            }
        }

        item {
            Spacer(Modifier.height(12.dp))

            // 编辑器模式设置
            var editorMode by rememberSaveable { mutableStateOf(vm.getEditorMode()) }

            Text("编辑器模式", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "笔记编辑器的默认输入模式",
                        style = MaterialTheme.typography.bodySmall,
                        color = themeTextGrey(),
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = editorMode == "richtext",
                            onClick = {
                                editorMode = "richtext"
                                vm.saveEditorMode("richtext")
                            },
                            label = { Text("富文本（推荐）", fontSize = 13.sp) },
                        )
                        FilterChip(
                            selected = editorMode == "markdown",
                            onClick = {
                                editorMode = "markdown"
                                vm.saveEditorMode("markdown")
                            },
                            label = { Text("Markdown", fontSize = 13.sp) },
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        if (editorMode == "richtext") "Notally 风格，选中文字即可加格式" else "适合开发者，手写语法",
                        style = MaterialTheme.typography.bodySmall,
                        color = themeTextGrey(),
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(12.dp))

            Text(stringResource(R.string.settings_voice), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = stringResource(R.string.settings_stt_label), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Switch(checked = sttEnabled, onCheckedChange = { sttEnabled = it })
                    }
                    if (sttEnabled) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = stringResource(R.string.settings_stt_engine), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                            ) {
                                FilterChip(
                                    selected = sttEngine == "local",
                                    onClick = { sttEngine = "local" },
                                    label = { Text(stringResource(R.string.settings_local_sherpa), fontSize = 12.sp) },
                                    shape = RoundedCornerShape(20.dp),
                                )
                                FilterChip(
                                    selected = sttEngine == "stepaudio",
                                    onClick = { sttEngine = "stepaudio" },
                                    label = { Text("StepAudio", fontSize = 12.sp) },
                                    shape = RoundedCornerShape(20.dp),
                                )
                                FilterChip(
                                    selected = sttEngine == "mimo",
                                    onClick = { sttEngine = "mimo" },
                                    label = { Text("MiMo ASR", fontSize = 12.sp) },
                                    shape = RoundedCornerShape(20.dp),
                                )
                            }
                        }
                        if (sttEngine == "mimo") {
                            Text(
                                text = stringResource(R.string.settings_mimo_asr_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFE67E22),
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

                        // 识别模式选择（伪流式 vs 录制后识别）
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Text(
                            text = "识别模式",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        ) {
                            FilterChip(
                                selected = sttStreamingMode == "pseudo",
                                onClick = { sttStreamingMode = "pseudo" },
                                label = { Text("伪流式（实时显示）", fontSize = 12.sp) },
                                shape = RoundedCornerShape(20.dp),
                            )
                            FilterChip(
                                selected = sttStreamingMode == "batch",
                                onClick = { sttStreamingMode = "batch" },
                                label = { Text("录制后识别（更准确）", fontSize = 12.sp) },
                                shape = RoundedCornerShape(20.dp),
                            )
                        }
                        Text(
                            text = "非流式模型下，录制后识别可获得更准确的结果",
                            style = MaterialTheme.typography.bodySmall,
                            color = themeTextGrey(),
                        )

                        // ★ 本地语音模型下载管理
                        if (sttEngine == "local") {
                            val sttModelManager = remember { ModelManager }
                            val scope = rememberCoroutineScope()
                            var downloadingModel by rememberSaveable { mutableStateOf<ModelType?>(null) }
                            var downloadProgress by rememberSaveable { mutableStateOf(0f) }
                            var downloadStatusText by rememberSaveable { mutableStateOf("") }
                            var showDeleteConfirm by rememberSaveable { mutableStateOf<ModelType?>(null) }

                            // STT 下载弹窗状态
                            var sttDialogVisible by rememberSaveable { mutableStateOf(false) }
                            var sttDialogModelName by rememberSaveable { mutableStateOf("") }
                            var sttDialogModelDesc by rememberSaveable { mutableStateOf("") }
                            var sttDialogPercent by rememberSaveable { mutableStateOf(0) }
                            var sttDialogDownloadedMb by rememberSaveable { mutableStateOf(0) }
                            var sttDialogTotalMb by rememberSaveable { mutableStateOf(0) }
                            var sttDialogSpeed by rememberSaveable { mutableStateOf("") }
                            var sttDialogStatus by rememberSaveable { mutableStateOf("idle") }
                            var sttDialogStatusDetail by rememberSaveable { mutableStateOf("") }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            Text(
                                text = stringResource(R.string.settings_local_models_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )

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
                                        .padding(vertical = 6.dp),
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
                                                fontWeight = FontWeight.Medium,
                                                fontSize = 13.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            // 已下载标签 / 推荐标签
                                            if (isDownloaded) {
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = SuccessGreen.copy(alpha = 0.15f),
                                                ) {
                                                    Text(
                                                        text = "已下载",
                                                        fontSize = 10.sp,
                                                        color = SuccessGreen,
                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                                    )
                                                }
                                            } else if (modelInfo.type == sttModelManager.getRecommendedModel(context)) {
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                                ) {
                                                    Text(
                                                        text = "推荐",
                                                        fontSize = 10.sp,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            text = "$sizeStr | ${modelInfo.minRamMB}MB RAM",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = themeTextGrey(),
                                            fontSize = 11.sp,
                                        )

                                        // 下载进度条
                                        if (isDownloading && downloadProgress > 0f) {
                                            LinearProgressIndicator(
                                                progress = { downloadProgress },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(3.dp)
                                                    .padding(top = 4.dp),
                                                color = MaterialTheme.colorScheme.primary,
                                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                            )
                                            Text(
                                                text = downloadStatusText,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }

                                    // 操作按钮
                                    if (isDownloading) {
                                        TextButton(onClick = { downloadingModel = null }) {
                                            Text("取消", fontSize = 12.sp)
                                        }
                                    } else if (isDownloaded) {
                                        IconButton(
                                            onClick = { showDeleteConfirm = modelInfo.type },
                                            modifier = Modifier.size(32.dp),
                                        ) {
                                            Icon(
                                                Icons.Outlined.DeleteOutline,
                                                contentDescription = "删除模型",
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    } else {
                                        FilledTonalButton(
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                            onClick = {
                                                downloadingModel = modelInfo.type
                                                downloadProgress = 0f
                                                downloadStatusText = context.getString(R.string.settings_preparing)

                                                // 弹窗 + 通知栏
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
                                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.sp.value.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text(stringResource(R.string.action_download), fontSize = 12.sp)
                                        }
                                    }
                                }
                            }

                            // 删除确认弹窗
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

                            // STT 模型下载弹窗
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = stringResource(R.string.settings_tts_section), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Switch(checked = ttsEnabled, onCheckedChange = { ttsEnabled = it })
                    }
                    if (ttsEnabled) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = stringResource(R.string.settings_tts_engine), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                            ) {
                                FilterChip(
                                    selected = ttsEngine == "system",
                                    onClick = { ttsEngine = "system" },
                                    label = { Text("系统 TTS", fontSize = 12.sp) },
                                    shape = RoundedCornerShape(20.dp),
                                )
                                FilterChip(
                                    selected = ttsEngine == "stepaudio",
                                    onClick = { ttsEngine = "stepaudio" },
                                    label = { Text("StepAudio TTS", fontSize = 12.sp) },
                                    shape = RoundedCornerShape(20.dp),
                                )
                                FilterChip(
                                    selected = ttsEngine == "mimo",
                                    onClick = { ttsEngine = "mimo" },
                                    label = { Text("MiMo TTS", fontSize = 12.sp) },
                                    shape = RoundedCornerShape(20.dp),
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
                                color = Color(0xFFE67E22),
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { ttsRate = 0.85f }, enabled = ttsEnabled, shape = RoundedCornerShape(11.dp)) { Text(stringResource(R.string.settings_speed_slow)) }
                        Button(onClick = { ttsRate = 1.0f }, enabled = ttsEnabled, shape = RoundedCornerShape(11.dp)) { Text(stringResource(R.string.settings_speed_normal)) }
                        Button(onClick = { ttsRate = 1.2f }, enabled = ttsEnabled, shape = RoundedCornerShape(11.dp)) { Text(stringResource(R.string.settings_speed_fast)) }
                    }
                    Text(text = stringResource(R.string.settings_language))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { ttsLocaleTag = "zh-CN" }, enabled = ttsEnabled, shape = RoundedCornerShape(11.dp)) { Text(stringResource(R.string.settings_chinese)) }
                        Button(onClick = { ttsLocaleTag = "en-US" }, enabled = ttsEnabled, shape = RoundedCornerShape(11.dp)) { Text(stringResource(R.string.settings_english)) }
                    }
                }
            }
        }

        item {
            // 录音时长限制
            Spacer(Modifier.height(12.dp))
            Text("录音时长限制", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.settings_duration_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = themeTextGrey(),
                    )
                    listOf("语音速记" to "VOICE_MEMO", "多人会议" to "MEETING", "手机内录" to "INTERNAL", "课堂录音" to "CLASSROOM").forEach { (label, modeKey) ->
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
                                    shape = RoundedCornerShape(11.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                ) {
                                    Text(
                                        text = if (currentHours.value == 0) "无限制" else "${currentHours.value}小时",
                                        fontSize = 13.sp,
                                    )
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
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

        item {
            // OCR 模型管理
            HorizontalDivider()
            Text(stringResource(R.string.settings_ocr_section), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "PP-OCRv6 增强识别", fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "百度第六代 OCR 模型，中英文识别精度更高（需下载 77MB）",
                                style = MaterialTheme.typography.bodySmall,
                                color = themeTextGrey(),
                            )
                        }
                    }
                    // OCR 模型下载状态
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
                            fontSize = 13.sp,
                        )
                        if (ocrDownloaded.value) {
                            Surface(shape = RoundedCornerShape(4.dp), color = SuccessGreen.copy(alpha = 0.15f)) {
                                Text(stringResource(R.string.settings_model_downloaded), fontSize = 10.sp, color = SuccessGreen, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                            TextButton(onClick = {
                                ocrModelManager.clearModelCache(context, ocrModel.id)
                                ocrDownloaded.value = false
                            }) { Text("删除", fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                        } else if (ocrDownloading.value) {
                            LinearProgressIndicator(progress = { ocrProgress.value }, modifier = Modifier.weight(1f).height(3.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(ocrStatusText.value, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        } else {
                            FilledTonalButton(
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
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
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.sp.value.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.action_download), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        item {
            HorizontalDivider()

            Text(stringResource(R.string.settings_web_search_section), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = stringResource(R.string.settings_web_search_label), fontWeight = FontWeight.SemiBold)
                            Text(
                                text = stringResource(R.string.settings_web_search_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = webSearchEnabled, onCheckedChange = { webSearchEnabled = it; vm.saveWebSearchEnabled(it) })
                    }
                    if (webSearchEnabled) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = stringResource(R.string.settings_search_engine), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                            ) {
                                FilterChip(
                                    selected = webSearchSource == "own",
                                    onClick = { webSearchSource = "own"; vm.saveWebSearchSource("own") },
                                    label = { Text(stringResource(R.string.settings_own_engine), fontSize = 12.sp) },
                                    shape = RoundedCornerShape(20.dp),
                                )
                                FilterChip(
                                    selected = webSearchSource == "provider",
                                    onClick = { webSearchSource = "provider"; vm.saveWebSearchSource("provider") },
                                    label = { Text(stringResource(R.string.settings_provider_builtin), fontSize = 12.sp) },
                                    shape = RoundedCornerShape(20.dp),
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
                                color = Color(0xFFE67E22),
                            )
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.settings_web_search_off_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = SuccessGreen,
                        )
                    }
                }
            }
        }

        item {
            HorizontalDivider()

            Text(stringResource(R.string.settings_bg_run_section), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = stringResource(R.string.settings_bg_run_label), fontWeight = FontWeight.SemiBold)
                            Text(
                                text = stringResource(R.string.settings_bg_run_desc),
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
                            text = stringResource(R.string.settings_bg_run_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            HorizontalDivider()

            Text(stringResource(R.string.settings_location_env_section), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = stringResource(R.string.settings_location), fontWeight = FontWeight.SemiBold)
                            Text(
                                text = stringResource(R.string.settings_location_desc),
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
                            Text(text = stringResource(R.string.settings_deep_thinking), fontWeight = FontWeight.SemiBold)
                            Text(
                                text = stringResource(R.string.settings_deep_thinking_desc),
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
                            Text(text = stringResource(R.string.settings_debug_mode), fontWeight = FontWeight.SemiBold)
                            Text(
                                text = stringResource(R.string.settings_debug_mode_desc),
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
        }

        item {
            HorizontalDivider()

            Text("本地模型", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.DeveloperBoard, contentDescription = null, tint = BubbleBlue, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.settings_local_model_title), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = themeTextDark())
                    }

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = if (isLocalMode && localModelId != null) {
                            val info = AvailableLocalModels.findById(localModelId!!)
                            "当前使用: ${info?.displayName ?: localModelId}"
                        } else stringResource(R.string.settings_local_model_offline_desc),
                        color = themeTextGrey(),
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

                        OutlinedButton(
                            onClick = { showModelSelector = true },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp), tint = BubbleBlue)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.settings_select_model), fontSize = 12.sp, color = BubbleBlue)
                        }
                    }

                    val currentInfo = localModelId?.let { AvailableLocalModels.findById(it) }
                    if (isLocalMode && currentInfo != null) {
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider(color = themeDividerColor())
                        Spacer(Modifier.height(10.dp))

                        var localTemp by rememberSaveable { mutableStateOf(vm.getLocalTemperature()) }
                        var localTopK by rememberSaveable { mutableStateOf(vm.getLocalTopK()) }
                        var localTopP by rememberSaveable { mutableStateOf(vm.getLocalTopP()) }
                        var localMaxTok by rememberSaveable { mutableStateOf(vm.getMaxOutputTokens()) }

                        Text(stringResource(R.string.settings_inference_params), fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = themeTextDark())
                        Text("上下文: ${currentInfo.maxContextLength} tokens | 输出: ${if (localMaxTok > 0) localMaxTok else currentInfo.maxTokens} tokens", fontSize = 11.sp, color = themeTextGrey())

                        Spacer(Modifier.height(8.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Temperature", fontSize = 11.sp, color = themeTextGrey())
                                Slider(
                                    value = localTemp,
                                    onValueChange = { localTemp = it },
                                    valueRange = 0.01f..2.0f,
                                    steps = 39,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text("${String.format("%.2f", localTemp)}", fontSize = 10.sp, color = themeTextGrey())
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Top P", fontSize = 11.sp, color = themeTextGrey())
                                Slider(
                                    value = localTopP,
                                    onValueChange = { localTopP = it },
                                    valueRange = 0.1f..1.0f,
                                    steps = 17,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text("${String.format("%.2f", localTopP)}", fontSize = 10.sp, color = themeTextGrey())
                            }
                        }

                        Spacer(Modifier.height(6.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Top K: $localTopK", fontSize = 11.sp, color = themeTextGrey())
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
                                scope.launch { snackbar.showSnackbar(context.getString(R.string.msg_params_saved)) }
                            },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(14.dp), tint = BubbleBlue)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.msg_save_params), fontSize = 11.sp, color = BubbleBlue)
                        }
                    }
                }
            }
        }

        item {
Spacer(Modifier.height(12.dp))

            // ── 高级选项（折叠区）──
            // 海马体等实验性功能入口降级至此，普通用户不显示，高级用户可展开访问
            var showAdvanced by rememberSaveable { mutableStateOf(false) }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ),
                onClick = { showAdvanced = !showAdvanced },
            ) {
                Row(
                    modifier = Modifier.padding(14.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.settings_advanced_options), fontWeight = FontWeight.Medium, fontSize = 13.sp, color = themeTextGrey(), modifier = Modifier.weight(1f))
                    Icon(
                        imageVector = if (showAdvanced) Icons.Default.ArrowDropDown else Icons.Default.ArrowForward,
                        contentDescription = if (showAdvanced) stringResource(R.string.action_collapse) else stringResource(R.string.action_expand),
                        modifier = Modifier.size(18.dp),
                        tint = themeTextGrey(),
                    )
                }
                androidx.compose.animation.AnimatedVisibility(visible = showAdvanced) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // 海马体记忆入口（降级后仅在此折叠区内可见）
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = toHippocampus,
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                contentColor = MaterialTheme.colorScheme.onSurface
                            ),
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.settings_hippocampus_index), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text("共 ${hippocampus?.count() ?: 0} 条索引条目 | 深层路由: hippocampus", style = MaterialTheme.typography.bodySmall, color = themeTextGrey())
                                }
                                Icon(Icons.Default.ArrowForward, contentDescription = "进入", modifier = Modifier.size(16.dp))
                            }
                        }

                        // 搜索引擎 API Key（高级）
                        if (webSearchEnabled && webSearchSource == "own") {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            Text(
                                text = "搜索引擎 API Key（可选，配置后搜索更稳定）",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
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
                                label = { Text("搜索优先级（如 bing,baidu,ddg,tavily,brave）") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                        }
                    }
                }
            }
        }

        item {
Spacer(Modifier.height(12.dp))

            // ── 关于 ──
            HorizontalDivider()
            Card(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = stringResource(R.string.settings_about), fontWeight = FontWeight.Bold, color = themeTextDark())
                    Spacer(modifier = Modifier.height(10.dp))
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(text = "Opedrgent", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = themeTextDark())
                            Text(
                                text = stringResource(R.string.about_version, "1.0.0"),
                                style = MaterialTheme.typography.bodySmall,
                                color = themeTextGrey()
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Text(
                        text = "hsyscn.top",
                        color = BubbleBlue,
                        modifier = Modifier.clickable {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://hsyscn.top")))
                        }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "BI4MIB.CN",
                        color = BubbleBlue,
                        modifier = Modifier.clickable {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://bi4mib.cn")))
                        }
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Text(
                        text = stringResource(R.string.about_visit_project),
                        color = BubbleBlue,
                        modifier = Modifier.clickable {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/HaohanHe/Opedrgent")))
                        }
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = stringResource(R.string.about_open_source),
                        fontWeight = FontWeight.Bold,
                        color = themeTextDark()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.about_open_source_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = themeTextGrey()
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // 跳转到独立开源声明页
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = toOpenSource,
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.about_view_open_source),
                                modifier = Modifier.weight(1f),
                                fontWeight = FontWeight.SemiBold,
                                color = themeTextDark(),
                            )
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = themeTextGrey())
                        }
                    }
                }
            }
        }

        item {
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
                    vm.saveDebugMode(debugMode)
                    vm.saveDeepThinking(deepThinkingEnabled)
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
                Text(stringResource(R.string.title_skills_library))
            }
            Button(onClick = toAutomations, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(11.dp)) {
                Text(stringResource(R.string.title_automations))
            }
            if (vm.hasApiKey()) {
                Text(stringResource(R.string.settings_saved_key_hint))
            } else {
                Text(stringResource(R.string.settings_not_set_key))
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
                        snackbar.showSnackbar(context.getString(R.string.msg_download_model_first))
                    }
                }
            },
            downloadManager = downloadManager,
            localEngine = localEngine,
            currentModelId = localModelId,
        )
        } // item - settings content
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
