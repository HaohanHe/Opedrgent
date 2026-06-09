@file:OptIn(ExperimentalMaterial3Api::class)

package top.hsyscn.opedrgent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import top.hsyscn.opedrgent.mcp.editors.EditorResult
import top.hsyscn.opedrgent.mcp.editors.EditorRole
import top.hsyscn.opedrgent.mcp.editors.EditorTeamService
import top.hsyscn.opedrgent.mcp.editors.ExecutionPlan
import top.hsyscn.opedrgent.mcp.editors.OutputPlatform
import top.hsyscn.opedrgent.mcp.editors.RoleInstance
import top.hsyscn.opedrgent.settings.ApiSettings
import top.hsyscn.opedrgent.ui.theme.AccentBlue
import top.hsyscn.opedrgent.ui.theme.BgGray
import top.hsyscn.opedrgent.ui.theme.CardWhite
import top.hsyscn.opedrgent.ui.theme.TextDark
import top.hsyscn.opedrgent.ui.theme.TextGrey
import top.hsyscn.opedrgent.ui.components.MarkdownText

private enum class EditorMode { PIPELINE, FREE }

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
    val service = remember { EditorTeamService(vm.apiSettings) }

    var mode by rememberSaveable { mutableStateOf(EditorMode.PIPELINE) }
    var pipelineInput by rememberSaveable { mutableStateOf(initialInput) }
    var selectedPlatform by rememberSaveable { mutableStateOf(OutputPlatform.WECHAT) }
    var styleReference by rememberSaveable { mutableStateOf("") }

    // 流水线状态
    var isRunningPipeline by remember { mutableStateOf(false) }
    var pipelineResults by remember { mutableStateOf<List<EditorResult>>(emptyList()) }
    var currentStepIndex by remember { mutableIntStateOf(-1) }
    var finalOutput by remember { mutableStateOf("") }
    var totalDuration by remember { mutableStateOf(0L) }

    // 规划阶段状态
    var isPlanning by remember { mutableStateOf(false) }
    var currentPlan by remember { mutableStateOf<ExecutionPlan?>(null) }
    var planReasoning by remember { mutableStateOf("") }

    // 自由模式状态
    var selectedRole by remember { mutableStateOf<RoleInstance?>(null) }
    var freeModeInput by remember { mutableStateOf(initialInput) }
    var freeModeResult by remember { mutableStateOf<EditorResult?>(null) }
    var isRunningFree by remember { mutableStateOf(false) }

    // 重新做某一步的临时状态
    var rerunningStepIndex by remember { mutableIntStateOf(-1) }

    DisposableEffect(Unit) {
        onDispose { service.cancel() }
    }

    fun showSnackbar(msg: String) {
        scope.launch { snackbarHostState.showSnackbar(msg) }
    }

    fun copyToClipboard(text: String, label: String = "内容") {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        showSnackbar("已复制到剪贴板")
    }

    // 执行流水线
    suspend fun runPipeline() {
        if (pipelineInput.isBlank()) {
            showSnackbar("请先输入你想写的内容")
            return
        }
        isRunningPipeline = true
        isPlanning = true  // 新增：进入规划阶段
        pipelineResults = emptyList()
        currentStepIndex = -1
        finalOutput = ""
        rerunningStepIndex = -1
        service.resetCancel()

        val result = service.planAndExecute(
            userInput = pipelineInput,
            targetPlatform = selectedPlatform,
            styleReference = styleReference,
            onPlanReady = { plan ->
                // 规划完成
                isPlanning = false
                currentPlan = plan
                planReasoning = plan.reasoning
            },
            onStepComplete = { roleInstance, output ->
                currentStepIndex = pipelineResults.size
                pipelineResults = pipelineResults + EditorResult(
                    role = roleInstance,  // 注意：role 现在是 RoleInstance
                    output = output,
                )
            },
        )

        pipelineResults = result.steps
        finalOutput = result.finalOutput
        totalDuration = result.totalDurationMs
        isRunningPipeline = false
        isPlanning = false
    }

    // 重新执行某一步
    suspend fun rerunStep(stepIndex: Int) {
        if (stepIndex < 0 || stepIndex >= pipelineResults.size) return
        val roleInstance = pipelineResults[stepIndex].role  // RoleInstance 类型
        rerunningStepIndex = stepIndex

        // 确定输入
        val stepInput = when {
            stepIndex == 0 -> pipelineInput
            else -> {
                pipelineResults.getOrNull(stepIndex - 1)?.output?.takeIf { it.isNotBlank() }
                    ?: pipelineInput
            }
        }

        val contextNotes = pipelineResults.take(stepIndex).mapNotNull { r ->
            if (r.isSuccess && r.role != roleInstance) "【${r.role.alias}】的输出：\n${r.output.take(2000)}" else null
        }.takeLast(3)

        // 使用 singleRoleConsult（统一支持预设角色和动态角色）
        val newResult = when (roleInstance) {
            is RoleInstance.Preset -> service.singleRoleConsult(role = roleInstance.role, input = stepInput)
            is RoleInstance.Dynamic -> service.singleRoleConsult(
                role = top.hsyscn.opedrgent.mcp.editors.EditorRole.WRITER,
                input = stepInput,
                dynamicSystemPrompt = roleInstance.dynamicRole.systemPrompt,
            )
        }

        val updated = pipelineResults.toMutableList()
        updated[stepIndex] = newResult
        pipelineResults = updated
        rerunningStepIndex = -1

        if (stepIndex < pipelineResults.size - 1) {
            showSnackbar("「${roleInstance.alias}」已重新完成，建议同时重新执行后续步骤")
        }
    }

    // 自由模式调用（支持预设角色和动态角色）
    suspend fun runFreeConsult(role: RoleInstance) {
        if (freeModeInput.isBlank()) {
            showSnackbar("请输入内容")
            return
        }
        isRunningFree = true
        freeModeResult = null

        val result = service.singleRoleConsult(
            role = (role as? RoleInstance.Preset)?.role ?: EditorRole.WRITER,
            input = freeModeInput,
            dynamicSystemPrompt = (role as? RoleInstance.Dynamic)?.dynamicRole?.systemPrompt,
        )
        freeModeResult = result
        isRunningFree = false
    }

    Scaffold(
        containerColor = BgGray,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("AI 编辑团", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = {
                        service.cancel()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CardWhite),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(BgGray),
        ) {
            // 模式切换 Tabs
            ModeToggleTabs(
                selectedMode = mode,
                onModeChange = { mode = it },
            )

            HorizontalDivider(color = Color(0xFFE0E0E0))

            when (mode) {
                EditorMode.PIPELINE -> PipelineModeContent(
                    pipelineInput = pipelineInput,
                    onInputChange = { pipelineInput = it },
                    selectedPlatform = selectedPlatform,
                    onPlatformChange = { selectedPlatform = it },
                    styleReference = styleReference,
                    onStyleChange = { styleReference = it },
                    isRunning = isRunningPipeline,
                    isPlanning = isPlanning,
                    planReasoning = planReasoning,
                    currentPlan = currentPlan,
                    results = pipelineResults,
                    currentStepIndex = currentStepIndex,
                    finalOutput = finalOutput,
                    totalDuration = totalDuration,
                    rerunningStepIndex = rerunningStepIndex,
                    onStartPipeline = { scope.launch { runPipeline() } },
                    onStopPipeline = { service.cancel() },
                    onRerunStep = { scope.launch { rerunStep(it) } },
                    onCopyText = { copyToClipboard(it) },
                    onSaveToNote = {
                        // 将最终输出保存为新笔记
                        scope.launch {
                            val noteId = vm.noteRepository.quickCreate(finalOutput, top.hsyscn.opedrgent.note.NoteType.TEXT)
                            showSnackbar("已保存为笔记 (ID: $noteId)")
                        }
                    },
                    onShare = {
                        // 系统分享
                        val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, finalOutput)
                        }
                        val chooser = android.content.Intent.createChooser(sendIntent, "分享文章")
                        context.startActivity(chooser)
                    },
                    modifier = Modifier.weight(1f),
                )

                EditorMode.FREE -> FreeModeContent(
                    roles = EditorRole.allRoles.map { RoleInstance.Preset(it) } +
                            (currentPlan?.steps?.map { it.role } ?: emptyList()),
                    selectedRole = selectedRole,
                    onRoleSelect = { selectedRole = it },
                    freeModeInput = freeModeInput,
                    onFreeInputChange = { freeModeInput = it },
                    freeModeResult = freeModeResult,
                    isRunningFree = isRunningFree,
                    onRunConsult = { role -> scope.launch { runFreeConsult(role) } },
                    onCopyText = { copyToClipboard(it) },
                    onBackToGrid = { selectedRole = null; freeModeResult = null },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// ==================== 模式切换 Tabs ====================

@Composable
private fun ModeToggleTabs(
    selectedMode: EditorMode,
    onModeChange: (EditorMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardWhite)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        EditorMode.entries.forEach { m ->
            val isSelected = selectedMode == m
            FilterChip(
                selected = isSelected,
                onClick = { onModeChange(m) },
                label = {
                    Text(
                        text = when (m) {
                            EditorMode.PIPELINE -> "完整流水线"
                            EditorMode.FREE -> "自由调用"
                        },
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 14.sp,
                    )
                },
                shape = RoundedCornerShape(20.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
    }
}

// ==================== 流水线模式 ====================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PipelineModeContent(
    pipelineInput: String,
    onInputChange: (String) -> Unit,
    selectedPlatform: OutputPlatform,
    onPlatformChange: (OutputPlatform) -> Unit,
    styleReference: String,
    onStyleChange: (String) -> Unit,
    isRunning: Boolean,
    isPlanning: Boolean,
    planReasoning: String,
    currentPlan: ExecutionPlan?,
    results: List<EditorResult>,
    currentStepIndex: Int,
    finalOutput: String,
    totalDuration: Long,
    rerunningStepIndex: Int,
    onStartPipeline: () -> Unit,
    onStopPipeline: () -> Unit,
    onRerunStep: (Int) -> Unit,
    onCopyText: (String) -> Unit,
    onSaveToNote: () -> Unit = {},
    onShare: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Spacer(Modifier.height(8.dp))

            // 输入区域
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = CardWhite),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "描述你想写什么...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextGrey,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    PipelineInputField(
                        value = pipelineInput,
                        onValueChange = onInputChange,
                        enabled = !isRunning,
                    )

                    Spacer(Modifier.height(12.dp))

                    // 目标平台选择
                    Text("目标平台", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OutputPlatform.entries.forEach { platform ->
                            FilterChip(
                                selected = selectedPlatform == platform,
                                onClick = { if (!isRunning) onPlatformChange(platform) },
                                label = { Text(platform.displayName, fontSize = 12.sp) },
                                shape = RoundedCornerShape(16.dp),
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // 开始/停止按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        if (isRunning) {
                            OutlinedButton(
                                onClick = onStopPipeline,
                                shape = RoundedCornerShape(20.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = Color(0xFFFFEBEE),
                                    contentColor = Color(0xFFE53935),
                                ),
                            ) {
                                Icon(Icons.Default.Stop, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("停止")
                            }
                            Text(
                                text = formatDuration(totalDuration),
                                color = TextGrey,
                                fontSize = 12.sp,
                                modifier = Modifier.align(Alignment.CenterVertically).padding(start = 12.dp),
                            )
                        } else {
                            Button(
                                onClick = onStartPipeline,
                                shape = RoundedCornerShape(20.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                            ) {
                                Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("开始创作")
                            }
                        }
                    }
                }
            }
        }

        // 规划中提示
        if (isPlanning) {
            item {
                Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = AccentBlue)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("AI 正在分析任务...", fontWeight = FontWeight.SemiBold)
                            Text("规划最优编辑流程", fontSize = 12.sp, color = TextGrey)
                        }
                    }
                }
            }
        }

        // 规划结果展示
        if (!isPlanning && currentPlan != null && results.isEmpty()) {
            item {
                PlanResultCard(
                    plan = currentPlan!!,
                    reasoning = planReasoning,
                )
            }
        }

        // 步骤进度指示器
        if (results.isNotEmpty() || isRunning) {
            item {
                PipelineStepIndicator(
                    steps = currentPlan?.steps ?: emptyList(),
                    results = results,
                    currentIndex = currentStepIndex,
                    isRunning = isRunning,
                    rerunningIndex = rerunningStepIndex,
                )
            }
        }

        // 各步骤结果（可折叠）
        itemsIndexed(results, key = { index, _ -> index }) { index, result ->
            StepResultCard(
                result = result,
                isRerunning = rerunningStepIndex == index,
                onRerun = { onRerunStep(index) },
                onCopy = { onCopyText(result.output) },
                enabled = !isRunning && rerunningStepIndex != index,
            )
        }

        // 最终输出
        if (finalOutput.isNotBlank()) {
            item {
                FinalOutputCard(
                    output = finalOutput,
                    platform = selectedPlatform,
                    duration = totalDuration,
                    onCopy = { onCopyText(finalOutput) },
                    onSaveToNote = onSaveToNote,
                    onShare = onShare,
                )
            }
        }

        // 运行中的加载提示
        if (isRunning && currentStepIndex >= 0) {
            val planSteps = currentPlan?.steps ?: emptyList()
            if (currentStepIndex < planSteps.size) {
                item {
                    val currentStep = planSteps[currentStepIndex]
                    val currentRoleInstance = currentStep.role
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(currentRoleInstance.displayColor).copy(alpha = 0.08f)),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.5.dp,
                                color = Color(currentRoleInstance.displayColor),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "${currentRoleInstance.icon} ${currentRoleInstance.alias} 正在工作中...",
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = "正在调用 AI 处理，请稍候...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextGrey,
                                )
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(100.dp)) }
    }
}

@Composable
private fun PlanResultCard(
    plan: ExecutionPlan,
    reasoning: String,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("执行计划", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.width(8.dp))
                Text("${plan.steps.size} 个步骤", fontSize = 12.sp, color = AccentBlue)
            }
            if (reasoning.isNotBlank()) {
                Text(reasoning, fontSize = 12.sp, color = TextGrey, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(8.dp))
            plan.steps.forEachIndexed { index, step ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color(step.role.displayColor)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(step.role.icon, fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${index + 1}. ${step.role.name}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    if (step.instruction.isNotBlank()) {
                        Text(
                            text = " - ${step.instruction.take(30)}...",
                            fontSize = 11.sp,
                            color = TextGrey,
                        )
                    }
                }
            }
        }
    }
}

// ==================== 自由模式 ====================

@Composable
private fun FreeModeContent(
    roles: List<RoleInstance>,
    selectedRole: RoleInstance?,
    onRoleSelect: (RoleInstance) -> Unit,
    freeModeInput: String,
    onFreeInputChange: (String) -> Unit,
    freeModeResult: EditorResult?,
    isRunningFree: Boolean,
    onRunConsult: (RoleInstance) -> Unit,
    onCopyText: (String) -> Unit,
    onBackToGrid: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selectedRole == null) {
        // 角色选择网格
        RoleSelectionGrid(
            roles = roles,
            onSelect = onRoleSelect,
            modifier = modifier.padding(12.dp),
        )
    } else {
        // 角色详情 + 输入 + 结果
        RoleConsultView(
            role = selectedRole,
            inputValue = freeModeInput,
            onInputChange = onFreeInputChange,
            result = freeModeResult,
            isRunning = isRunningFree,
            onRun = { onRunConsult(selectedRole) },
            onCopy = { onCopyText },
            onBack = onBackToGrid,
            modifier = modifier.padding(horizontal = 12.dp),
        )
    }
}

// ==================== 子组件 ====================

@Composable
private fun PipelineInputField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
) {
    var text by remember { mutableStateOf(value) }
    LaunchedEffect(value) { if (text != value) text = value }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 100.dp, max = 200.dp)
            .background(Color(0xFFF8F8F8), RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        if (text.isEmpty()) {
            Text(
                text = "描述你想写什么... 可以是灵感、笔记片段、选题想法、草稿等任何内容",
                color = Color(0xFFBDBDBD),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        androidx.compose.foundation.text.BasicTextField(
            value = text,
            onValueChange = {
                text = it
                onValueChange(it)
            },
            enabled = enabled,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextDark),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun PipelineStepIndicator(
    steps: List<PlanStep>,
    results: List<EditorResult>,
    currentIndex: Int,
    isRunning: Boolean,
    rerunningIndex: Int,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("创作流水线", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                steps.forEachIndexed { index, step ->
                    val roleInstance = step.role
                    val result = results.find { it.role == roleInstance }
                    val isCompleted = result != null && result.isSuccess
                    val isCurrent = index == currentIndex && isRunning
                    val isRerunning = index == rerunningIndex

                    StepIndicatorNode(
                        icon = roleInstance.icon,
                        alias = roleInstance.alias,
                        color = roleInstance.displayColor,
                        isCompleted = isCompleted,
                        isCurrent = isCurrent || isRerunning,
                        isPending = !isCompleted && !isCurrent && index > currentIndex,
                        showError = result != null && !result.isSuccess,
                    )

                    if (index < steps.lastIndex) {
                        StepConnector(
                            isPassed = isCompleted || index < currentIndex,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StepIndicatorNode(
    icon: String,
    alias: String,
    color: Long,
    isCompleted: Boolean,
    isCurrent: Boolean,
    isPending: Boolean,
    showError: Boolean,
) {
    val bgColor = when {
        showError -> Color(0xFFFFEBEE)
        isCompleted -> Color(color).copy(alpha = 0.12f)
        isCurrent -> Color(color).copy(alpha = 0.2f)
        else -> Color(0xFFF0F0F0)
    }
    val contentColor = when {
        showError -> Color(0xFFE53935)
        isCompleted -> Color(color)
        isCurrent -> Color(color)
        else -> Color(0xFFBDBDBD)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(52.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(bgColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (isCurrent && !showError) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.5.dp,
                    color = contentColor,
                )
            } else {
                Text(text = icon, fontSize = 18.sp)
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = alias,
            fontSize = 10.sp,
            color = contentColor,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
        Text(
            text = when {
                showError -> "\u274C"
                isCompleted -> "\u2705"
                isCurrent -> "\u23F3"
                else -> "\u2610"
            },
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun StepConnector(isPassed: Boolean) {
    Column(
        modifier = Modifier
            .width(16.dp)
            .padding(top = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HorizontalDivider(
            color = if (isPassed) AccentBlue.copy(alpha = 0.4f) else Color(0xFFE0E0E0),
            thickness = 2.dp,
        )
    }
}

@Composable
private fun StepResultCard(
    result: EditorResult,
    isRerunning: Boolean,
    onRerun: () -> Unit,
    onCopy: () -> Unit,
    enabled: Boolean,
) {
    val role = result.role
    var expanded by rememberSaveable("${role.alias}_expanded") { mutableStateOf(true) }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 卡片头部
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isRerunning) { expanded = !expanded }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(Color(role.displayColor).copy(alpha = 0.1f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = role.icon, fontSize = 16.sp)
                }
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${role.icon} ${role.alias}",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = role.name,
                            fontSize = 11.sp,
                            color = TextGrey,
                        )
                    }
                    if (!result.isSuccess) {
                        Text(
                            text = result.error ?: "执行失败",
                            fontSize = 11.sp,
                            color = Color(0xFFE53935),
                        )
                    } else {
                        Text(
                            text = "${result.output.length} 字 · ${result.durationMs}ms",
                            fontSize = 11.sp,
                            color = TextGrey,
                        )
                    }
                }

                // 展开/折叠图标
                Text(
                    text = if (expanded) "▼" else "▶",
                    fontSize = 10.sp,
                    color = TextGrey,
                )
            }

            // 可展开的结果内容
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically(),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    HorizontalDivider(color = Color(0xFFF0F0F0))
                    if (isRerunning) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = Color(role.displayColor),
                            )
                        }
                    } else {
                        MarkdownText(
                            text = result.output.ifEmpty { "（无输出）" },
                            maxChars = 3000,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }

                    // 操作按钮行
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        if (result.isSuccess && enabled) {
                            TextButton(onClick = onRerun) {
                                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(2.dp))
                                Text("重做这步", fontSize = 12.sp)
                            }
                        }
                        if (result.isSuccess) {
                            TextButton(onClick = onCopy) {
                                Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(2.dp))
                                Text("复制", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FinalOutputCard(
    output: String,
    platform: OutputPlatform,
    duration: Long,
    onCopy: () -> Unit,
    onSaveToNote: () -> Unit = {},
    onShare: () -> Unit = {},
) {
    var expanded by rememberSaveable("final_output_expanded") { mutableStateOf(true) }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = AccentBlue.copy(alpha = 0.04f),
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, AccentBlue.copy(alpha = 0.2f)),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("\uD83C\uDFAF 最终文章", fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                FilterChip(
                    selected = false,
                    onClick = {},
                    label = { Text(platform.displayName, fontSize = 11.sp, color = AccentBlue) },
                    shape = RoundedCornerShape(12.dp),
                )
                Text(
                    text = "${formatDuration(duration)}",
                    fontSize = 11.sp,
                    color = TextGrey,
                    modifier = Modifier.padding(start = 6.dp),
                )
                Text(if (expanded) "▼" else "▶", fontSize = 10.sp, color = TextGrey)
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    HorizontalDivider(
                        color = AccentBlue.copy(alpha = 0.1f),
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    MarkdownText(
                        text = output,
                        maxChars = 5000,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        OutlinedButton(onClick = onSaveToNote, shape = RoundedCornerShape(16.dp)) {
                            Icon(Icons.Default.NoteAdd, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("存为笔记", fontSize = 12.sp)
                        }
                        OutlinedButton(onClick = onShare, shape = RoundedCornerShape(16.dp)) {
                            Icon(Icons.Default.Share, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("分享", fontSize = 12.sp)
                        }
                        Button(
                            onClick = onCopy,
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                        ) {
                            Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("复制全文", fontSize = 12.sp, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoleSelectionGrid(
    roles: List<RoleInstance>,
    onSelect: (RoleInstance) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                text = "选择一位编辑",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                text = "点击角色卡片，输入内容后获取专业意见",
                style = MaterialTheme.typography.bodySmall,
                color = TextGrey,
            )
            Spacer(Modifier.height(12.dp))
        }

        items(roles.chunked(2)) { rowRoles ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rowRoles.forEach { role ->
                    RoleCard(
                        role = role,
                        onClick = { onSelect(role) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // 奇数个时补空位
                if (rowRoles.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }

        item { Spacer(Modifier.height(80.dp)) }
    }
}

@Composable
private fun RoleCard(
    role: RoleInstance,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(Color(role.displayColor).copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = role.icon, fontSize = 24.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = role.alias,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
            )
            Text(
                text = role.name,
                fontSize = 11.sp,
                color = TextGrey,
            )
            Spacer(Modifier.height(4.dp))
            val roleDesc = when (role) {
                is RoleInstance.Preset -> role.role.description
                is RoleInstance.Dynamic -> role.dynamicRole.description.ifBlank { role.dynamicRole.inputHint }
            }
            if (roleDesc.isNotBlank()) {
                Text(
                    text = roleDesc,
                    fontSize = 10.sp,
                    color = Color(0xFF999999),
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun RoleConsultView(
    role: RoleInstance,
    inputValue: String,
    onInputChange: (String) -> Unit,
    result: EditorResult?,
    isRunning: Boolean,
    onRun: () -> Unit,
    onCopy: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf(inputValue) }
    LaunchedEffect(inputValue) { if (text != inputValue) text = inputValue }

    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        // 返回按钮
        OutlinedButton(
            onClick = onBack,
            shape = RoundedCornerShape(20.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("返回选人", fontSize = 13.sp)
        }

        Spacer(Modifier.height(8.dp))

        // 角色信息卡
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color(role.displayColor).copy(alpha = 0.06f)),
        ) {
            Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(Color(role.displayColor).copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = role.icon, fontSize = 22.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    val roleTitle = when (role) {
                        is RoleInstance.Preset -> "${role.role.alias} · ${role.role.displayName}"
                        is RoleInstance.Dynamic -> "${role.dynamicRole.alias} · ${role.dynamicRole.name}"
                    }
                    Text(roleTitle, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    val roleDesc = when (role) {
                        is RoleInstance.Preset -> role.role.description
                        is RoleInstance.Dynamic -> role.dynamicRole.description.ifBlank { "动态生成角色" }
                    }
                    Text(roleDesc, fontSize = 12.sp, color = TextGrey)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 输入区域
        Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = CardWhite)) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("输入内容", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 300.dp)
                        .background(Color(0xFFF8F8F8), RoundedCornerShape(10.dp))
                        .padding(12.dp),
                ) {
                    if (text.isEmpty()) {
                        Text(
                            text = "输入你想让 ${role.alias} 处理的内容...",
                            color = Color(0xFFBDBDBD),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    androidx.compose.foundation.text.BasicTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            onInputChange(it)
                        },
                        enabled = !isRunning,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextDark),
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (isRunning) {
                        OutlinedButton(
                            onClick = {},
                            shape = RoundedCornerShape(20.dp),
                            enabled = false,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = Color(role.displayColor),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("处理中...")
                        }
                    } else {
                        Button(
                            onClick = onRun,
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(role.color),
                            ),
                            enabled = text.isNotBlank(),
                        ) {
                            Text("${role.alias} 开始工作")
                        }
                    }
                }
            }
        }

        // 结果展示
        result?.let { res ->
            Spacer(Modifier.height(12.dp))
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = CardWhite),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (res.isSuccess) "\u2705 ${role.alias} 的结果" else "\u274C 失败",
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        if (res.isSuccess) {
                            IconButton(onClick = { onCopy(res.output) }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.ContentCopy, "复制", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    if (!res.isSuccess) {
                        Text(res.error ?: "未知错误", color = Color(0xFFE53935), fontSize = 13.sp)
                    } else {
                        HorizontalDivider(color = Color(0xFFF0F0F0))
                        MarkdownText(
                            text = res.output,
                            maxChars = 5000,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(80.dp))
    }
}

// ==================== 工具函数 ====================

private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    return if (seconds < 60) "${seconds}秒"
    else {
        val min = seconds / 60
        val sec = seconds % 60
        "${min}分${sec}秒"
    }
}
