@file:OptIn(ExperimentalMaterial3Api::class)

package top.hsyscn.opedrgent.ui

import android.Manifest
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import top.hsyscn.opedrgent.llm.AvailableLocalModels
import top.hsyscn.opedrgent.llm.LocalLlmEngine
import top.hsyscn.opedrgent.llm.LocalLlmState
import top.hsyscn.opedrgent.llm.ModelDownloadManager
import top.hsyscn.opedrgent.settings.PROVIDER_PRESETS
import top.hsyscn.opedrgent.ui.components.ModelSelectorDialog
import top.hsyscn.opedrgent.ui.theme.BgGray
import top.hsyscn.opedrgent.ui.theme.BubbleBlue
import top.hsyscn.opedrgent.ui.theme.TextDark
import top.hsyscn.opedrgent.ui.theme.TextGrey
import top.hsyscn.opedrgent.storage.HippocampusIndex
import top.hsyscn.opedrgent.utils.BackgroundPermHelper
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit, toSkills: () -> Unit, toAutomations: () -> Unit, toMemory: () -> Unit, toNotes: () -> Unit, toHippocampus: () -> Unit = {}, hippocampus: HippocampusIndex? = null, showBackButton: Boolean = true, onInvisiblePartner: () -> Unit = {}) {
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
    var ttsDownloadOnly by rememberSaveable { mutableStateOf(vm.isTtsDownloadOnly()) }
    var ttsMimoEnabled by rememberSaveable { mutableStateOf(vm.isTtsMimoEnabled()) }
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
                    if (showBackButton) {
                        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "back") }
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

            Spacer(Modifier.height(12.dp))

            Text("笔记管理", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = toNotes,
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
                        Text("我的笔记", fontWeight = FontWeight.SemiBold)
                        Text("支持文本/语音/图片/链接/PDF笔记，带标签分类", style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.Default.ArrowForward, contentDescription = "进入")
                }
            }

            Spacer(Modifier.height(8.dp))

            // 无感伙伴模式入口
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onInvisiblePartner() },
                shape = RoundedCornerShape(11.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp), tint = BubbleBlue)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("无感伙伴模式", fontWeight = FontWeight.SemiBold)
                        Text("录音自动保存 / 智能发芽 / 每日收获", style = MaterialTheme.typography.bodySmall, color = TextGrey)
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = "进入")
                }
            }

            Spacer(Modifier.height(12.dp))

            // [降级] 海马体入口已移至底部"高级选项"折叠区，不再在一级设置列表显示
            // 原入口保留在页面底部的 advancedOptions 区域，深层路由 AppRoot.hipposcampus 仍可用

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
                    if (sttEnabled) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "识别引擎", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = sttEngine == "local",
                                    onClick = { sttEngine = "local" },
                                    label = { Text("本地 Sherpa", fontSize = 12.sp) },
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
                                text = "MiMo ASR 通过网络调用小米语音识别 API，需联网，支持中/英/日/韩等多语种",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFE67E22),
                            )
                        }
                        if (sttEngine == "local") {
                            Text(
                                text = "本地识别使用 Sherpa-ONNX 离线模型，无需联网，首次使用需下载模型",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextGrey,
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "TTS 朗读", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Switch(checked = ttsEnabled, onCheckedChange = { ttsEnabled = it })
                    }
                    if (ttsEnabled) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "TTS 引擎", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = !ttsMimoEnabled,
                                    onClick = { ttsMimoEnabled = false },
                                    label = { Text("系统 TTS", fontSize = 12.sp) },
                                    shape = RoundedCornerShape(20.dp),
                                )
                                FilterChip(
                                    selected = ttsMimoEnabled,
                                    onClick = { ttsMimoEnabled = true },
                                    label = { Text("MiMo TTS", fontSize = 12.sp) },
                                    shape = RoundedCornerShape(20.dp),
                                )
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "自动朗读回答", modifier = Modifier.weight(1f))
                        Switch(checked = ttsAuto, onCheckedChange = { ttsAuto = it }, enabled = ttsEnabled)
                    }
                    if (ttsEnabled && ttsAuto && ttsMimoEnabled) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "仅下载音频到本地", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Switch(
                                checked = ttsDownloadOnly,
                                onCheckedChange = { ttsDownloadOnly = it },
                            )
                        }
                        if (ttsDownloadOnly) {
                            Text(
                                text = "开启后自动朗读时仅保存音频文件，不自动播放",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextGrey,
                            )
                        } else {
                            Text(
                                text = "关闭后自动朗读时直接播放音频",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF4CAF50),
                            )
                        }
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

            // ── 高级选项（折叠区）──
            // 海马体等实验性功能入口降级至此，普通用户不显示，高级用户可展开访问
            var showAdvanced by rememberSaveable { mutableStateOf(false) }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(11.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ),
                onClick = { showAdvanced = !showAdvanced },
            ) {
                Row(
                    modifier = Modifier.padding(14.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("高级选项", fontWeight = FontWeight.Medium, fontSize = 13.sp, color = TextGrey, modifier = Modifier.weight(1f))
                    Icon(
                        imageVector = if (showAdvanced) Icons.Default.ArrowDropDown else Icons.Default.ArrowForward,
                        contentDescription = if (showAdvanced) "收起" else "展开",
                        modifier = Modifier.size(18.dp),
                        tint = TextGrey,
                    )
                }
                androidx.compose.animation.AnimatedVisibility(visible = showAdvanced) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // 海马体记忆入口（降级后仅在此折叠区内可见）
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = toHippocampus,
                            shape = RoundedCornerShape(9.dp),
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
                                    Text("海马体索引", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text("共 ${hippocampus?.count() ?: 0} 条索引条目 | 深层路由: hippocampus", style = MaterialTheme.typography.bodySmall, color = TextGrey)
                                }
                                Icon(Icons.Default.ArrowForward, contentDescription = "进入", modifier = Modifier.size(16.dp))
                            }
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
                        mimoEnabled = ttsMimoEnabled,
                        mimoVoice = vm.getTtsMimoVoice(),
                        downloadOnly = ttsDownloadOnly,
                    )
                    vm.saveSttEnabled(sttEnabled)
                    vm.saveSttEngine(sttEngine)
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
