@file:OptIn(ExperimentalMaterial3Api::class)

package top.hsyscn.opedrgent.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.hsyscn.opedrgent.interview.InterviewConfig
import top.hsyscn.opedrgent.interview.InterviewPhase
import top.hsyscn.opedrgent.interview.InterviewReport
import top.hsyscn.opedrgent.interview.InterviewType
import top.hsyscn.opedrgent.interview.DifficultyLevel
import top.hsyscn.opedrgent.interview.DialogueTurn
import top.hsyscn.opedrgent.interview.CoachFeedback
import top.hsyscn.opedrgent.interview.AnalysisResult
import top.hsyscn.opedrgent.interview.Verdict
import top.hsyscn.opedrgent.interview.EvaluationDimension
import top.hsyscn.opedrgent.interview.FullDuplexAudioEngine
import top.hsyscn.opedrgent.ui.theme.AccentBlue
import top.hsyscn.opedrgent.ui.theme.BgGray
import top.hsyscn.opedrgent.ui.theme.CardWhite
import top.hsyscn.opedrgent.ui.theme.TextDark
import top.hsyscn.opedrgent.ui.theme.TextGrey
import top.hsyscn.opedrgent.ui.theme.UserBubbleStart
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ==================== 主入口 ====================

/**
 * 面试模式主入口，根据当前阶段展示不同子界面。
 */
@Composable
fun InterviewScreen(
    vm: MainViewModel,
    onBack: () -> Unit,
) {
    val interviewState by vm.interviewState.collectAsStateCompat()

    when (interviewState.phase) {
        InterviewPhase.SETUP -> InterviewSetupScreen(
            onStart = { config -> vm.startInterview(config) },
            onBack = onBack,
        )
        InterviewPhase.PREPARING -> InterviewPreparingScreen(
            analysisResult = interviewState.analysisResult,
            config = interviewState.config,
        )
        InterviewPhase.IN_PROGRESS,
        InterviewPhase.EVALUATING -> InterviewSessionScreen(
            vm = vm,
            onEnd = { vm.endInterview() },
            onBack = {
                vm.resetInterview()
                onBack()
            },
        )
        InterviewPhase.COMPLETED -> InterviewReportScreen(
            vm = vm,
            onRestart = { vm.resetInterview() },
            onBack = {
                vm.resetInterview()
                onBack()
            },
            onSaveToNote = { vm.saveInterviewReportToNote() },
        )
    }
}

// ==================== 设置界面 ====================

/**
 * 面试设置界面 — 选择模式、填写信息、配置难度。
 */
@Composable
private fun InterviewSetupScreen(
    onStart: (InterviewConfig) -> Unit,
    onBack: () -> Unit,
) {
    var selectedType by remember { mutableStateOf<InterviewType?>(null) }
    var companyName by rememberSaveable { mutableStateOf("") }
    var position by rememberSaveable { mutableStateOf("") }
    var materials by rememberSaveable { mutableStateOf("") }
    var customInstructions by rememberSaveable { mutableStateOf("") }
    var difficulty by remember { mutableFloatStateOf(5f) }
    var maxQuestions by remember { mutableIntStateOf(8) }
    var enableCoach by rememberSaveable { mutableStateOf(true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("面试模式", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.White,
                ),
            )
        },
        containerColor = BgGray,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 16.dp,
                vertical = 12.dp,
            ),
        ) {
            // ── 模式选择 ──
            item {
                Text(
                    text = "选择面试模式",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = TextDark,
                )
            }

            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // 求职面试卡片
                    ModeSelectionCard(
                        icon = Icons.Default.Work,
                        title = "求职面试",
                        description = "模拟真实面试\n根据公司/岗位定制",
                        isSelected = selectedType == InterviewType.JOB_INTERVIEW,
                        onClick = { selectedType = InterviewType.JOB_INTERVIEW },
                        modifier = Modifier.weight(1f),
                    )
                    // 论文答辩卡片
                    ModeSelectionCard(
                        icon = Icons.Default.School,
                        title = "论文答辩",
                        description = "模拟学术答辩\n挑战研究方法与结论",
                        isSelected = selectedType == InterviewType.THESIS_DEFENSE,
                        onClick = { selectedType = InterviewType.THESIS_DEFENSE },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // ── 基本信息 ──
            item {
                Text(
                    text = "基本信息",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = TextDark,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            item {
                OutlinedTextField(
                    value = companyName,
                    onValueChange = { companyName = it },
                    label = {
                        Text(
                            if (selectedType == InterviewType.THESIS_DEFENSE)
                                "学校/机构（可选）"
                            else
                                "公司名称（可选）"
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(11.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = Color(0xFFE4E4E4),
                        focusedBorderColor = AccentBlue,
                    ),
                )
            }

            item {
                OutlinedTextField(
                    value = position,
                    onValueChange = { position = it },
                    label = {
                        Text(
                            if (selectedType == InterviewType.THESIS_DEFENSE)
                                "论文题目/研究方向"
                            else
                                "岗位名称"
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(11.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = Color(0xFFE4E4E4),
                        focusedBorderColor = AccentBlue,
                    ),
                )
            }

            // ── 补充材料 ──
            item {
                Text(
                    text = "补充材料（可选）",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = TextDark,
                )
            }

            item {
                OutlinedTextField(
                    value = materials,
                    onValueChange = { materials = it },
                    label = {
                        Text(
                            if (selectedType == InterviewType.THESIS_DEFENSE)
                                "论文摘要/研究内容/简历"
                            else
                                "简历内容"
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp),
                    shape = RoundedCornerShape(11.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = Color(0xFFE4E4E4),
                        focusedBorderColor = AccentBlue,
                    ),
                )
            }

            item {
                OutlinedTextField(
                    value = customInstructions,
                    onValueChange = { customInstructions = it },
                    label = { Text("自定义要求（可选）") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp),
                    shape = RoundedCornerShape(11.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = Color(0xFFE4E4E4),
                        focusedBorderColor = AccentBlue,
                    ),
                )
            }

            // ── 难度设置 ──
            item {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "难度等级",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            color = TextDark,
                        )
                        Text(
                            text = "${difficultyToInt(difficulty)}/10",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = AccentBlue,
                        )
                    }
                    Slider(
                        value = difficulty,
                        onValueChange = { difficulty = it },
                        valueRange = 1f..10f,
                        steps = 8,
                        colors = SliderDefaults.colors(
                            thumbColor = AccentBlue,
                            activeTrackColor = AccentBlue,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("简单", fontSize = 12.sp, color = TextGrey)
                        Text("困难", fontSize = 12.sp, color = TextGrey)
                    }
                }
            }

            // ── 题目数量 ──
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "问题数量",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = TextDark,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { if (maxQuestions > 3) maxQuestions-- },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Text("−", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AccentBlue)
                        }
                        Text(
                            text = "$maxQuestions",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = AccentBlue,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                        IconButton(
                            onClick = { if (maxQuestions < 15) maxQuestions++ },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Text("+", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AccentBlue)
                        }
                    }
                }
            }

            // ── 教练反馈开关 ──
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "启用教练反馈",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            color = TextDark,
                        )
                        Text(
                            text = "每轮回答后显示改进建议",
                            fontSize = 13.sp,
                            color = TextGrey,
                        )
                    }
                    // 开关（简化版）
                    Box(
                        modifier = Modifier
                            .size(48.dp, 28.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (enableCoach) AccentBlue else Color.Gray.copy(alpha = 0.3f))
                            .clickable { enableCoach = !enableCoach },
                        contentAlignment = Alignment.Center,
                    ) {
                        // 开关指示器
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(Color.White)
                                .padding(2.dp),
                        )
                    }
                }
            }

            // ── 开始按钮 ──
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        val config = InterviewConfig(
                            type = selectedType ?: InterviewType.JOB_INTERVIEW,
                            company = companyName.trim(),
                            position = position.trim(),
                            difficulty = difficultyFromInt(difficultyToInt(difficulty)),
                            questionCount = maxQuestions,
                            materials = materials.trim(),
                            customInstructions = customInstructions.trim(),
                            enableCoach = enableCoach,
                        )
                        onStart(config)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentBlue,
                        contentColor = Color.White,
                    ),
                    enabled = selectedType != null,
                ) {
                    Text(
                        text = "开始面试",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
                )
            }

            // 底部留白
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

/** 将浮点数难度转换为整数 */
private fun difficultyToInt(value: Float): Int = value.toInt()

/** 从整数获取 DifficultyLevel 枚举 */
private fun difficultyFromInt(level: Int): DifficultyLevel = when {
    level <= 3 -> DifficultyLevel.EASY
    level <= 6 -> DifficultyLevel.NORMAL
    level <= 8 -> DifficultyLevel.HARD
    else -> DifficultyLevel.EXPERT
}

// ── 模式选择卡片 ──

@Composable
private fun ModeSelectionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) AccentBlue.copy(alpha = 0.08f) else CardWhite,
        ),
        border = if (isSelected) {
            androidx.compose.foundation.BorderStroke(2.dp, AccentBlue)
        } else {
            androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE4E4E4))
        },
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) AccentBlue else AccentBlue.copy(alpha = 0.1f)
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isSelected) Color.White else AccentBlue,
                    modifier = Modifier.size(24.dp),
                )
            }
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = TextDark,
            )
            Text(
                text = description,
                fontSize = 12.sp,
                color = TextGrey,
                textAlign = TextAlign.Center,
                lineHeight = 16.sp,
            )
        }
    }
}

// ==================== 准备中界面 ====================

/**
 * AI 分析材料中界面。
 */
@Composable
private fun InterviewPreparingScreen(
    analysisResult: AnalysisResult?,
    config: InterviewConfig?,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgGray),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            // 加载动画
            val infiniteTransition = rememberInfiniteTransition(label = "preparing")
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(800),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "prep_alpha",
            )

            CircularProgressIndicator(
                color = AccentBlue,
                modifier = Modifier.size(56.dp),
                strokeWidth = 4.dp,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "AI 正在分析您的材料...",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = TextDark,
            )

            // 显示分析结果（如果有）
            if (analysisResult != null && analysisResult.keyPoints.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = CardWhite),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "📋 材料分析结果",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = TextDark,
                        )

                        // 关键点
                        if (analysisResult.keyPoints.isNotEmpty()) {
                            Text(
                                text = "关键信息",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = AccentBlue,
                            )
                            analysisResult.keyPoints.take(5).forEach { point ->
                                Text(
                                    text = "• $point",
                                    fontSize = 13.sp,
                                    color = TextDark,
                                    lineHeight = 18.sp,
                                )
                            })
                        }

                        // 建议提问方向
                        if (analysisResult.suggestedQuestions.isNotEmpty()) {
                            HorizontalDivider(color = Color(0xFFF0F0F0))
                            Text(
                                text = "💡 建议关注方向",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = AccentBlue,
                            )
                            analysisResult.suggestedQuestions.take(3).forEach { q ->
                                Text(
                                    text = "• $q",
                                    fontSize = 13.sp,
                                    color = TextDark,
                                    lineHeight = 18.sp,
                                )
                            })
                        }

                        // 风险点
                        if (analysisResult.riskAreas.isNotEmpty()) {
                            HorizontalDivider(color = Color(0xFFF0F0F0))
                            Text(
                                text = "⚠️ 可能被深挖的点",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = Color(0xFFF57C00),
                            )
                            analysisResult.riskAreas.take(3).forEach { risk ->
                                Text(
                                    text = "• $risk",
                                    fontSize = 13.sp,
                                    color = TextDark,
                                    lineHeight = 18.sp,
                                )
                            })
                        }
                    }
                }
            }

            Text(
                text = "即将开始面试...",
                fontSize = 13.sp,
                color = TextGrey,
            )
        }
    }
}

// ==================== 面试进行界面 ====================

/**
 * 面试进行中界面 — 全双工通话模式。
 *
 * 底部操作栏包含：静音按钮 / 通话时长 / 题目进度 / 结束按钮
 * 实时状态文字根据 DuplexState 切换，插话时显示闪烁提示
 */
@Composable
private fun InterviewSessionScreen(
    vm: MainViewModel,
    onEnd: () -> Unit,
    onBack: () -> Unit,
) {
    val interviewState by vm.interviewState.collectAsStateCompat()
    val messages = interviewState.messages
    val config = interviewState.config
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 全双工状态
    val duplexState = interviewState.duplexState
    val isMuted = interviewState.isMuted

    // 输入状态（文字备选模式）
    var inputText by rememberSaveable { mutableStateOf("") }
    var showTextInput by rememberSaveable { mutableStateOf(false) }
    var showEndDialog by rememberSaveable { mutableStateOf(false) }

    // 插话指示器闪烁状态
    val infiniteTransition = rememberInfiniteTransition(label = "bargein_blink")
    val bargeInAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            tween(400),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bargein_alpha",
    )
    val showBargeInIndicator = duplexState != null && interviewState.bargeInDetected

    // 自动滚动到底部
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            delay(100)
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = when (config?.type) {
                                InterviewType.THESIS_DEFENSE -> "论文答辩"
                                InterviewType.SCENARIO -> "自定义场景"
                                else -> "求职面试"
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                        )
                        // 面试进度
                        if (config != null && interviewState.questionCount > 0) {
                            Text(
                                text = "第 ${interviewState.questionCount}/${config.questionCount} 题",
                                fontSize = 12.sp,
                                color = TextGrey,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 计时器
                    InterviewTimer(elapsedSeconds = interviewState.elapsedSeconds)

                    // 结束按钮
                    TextButton(
                        onClick = { showEndDialog = true },
                    ) {
                        Text("结束", color = Color(0xFFE53935))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.White,
                ),
            )
        },
        containerColor = BgGray,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            // ── 进度条 ──
            if (config != null && interviewState.questionCount > 0) {
                LinearProgressIndicator(
                    progress = { interviewState.questionCount.toFloat() / config.questionCount },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = AccentBlue,
                    trackColor = AccentBlue.copy(alpha = 0.1f),
                )
            }

            // ── 对话区域 ──
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
            ) {
                items(messages, key = { it.timestamp.toString() + it.role }) { msg ->
                    InterviewBubble(message = msg)
                }

                // 教练反馈折叠区
                val coachFeedback = interviewState.coachFeedback
                if (coachFeedback != null && config?.enableRealtimeFeedback == true) {
                    item {
                        CoachFeedbackCard(feedback = coachFeedback)
                    }
                }

                // AI 思考中动画
                if (interviewState.phase == InterviewPhase.EVALUATING) {
                    item {
                        ThinkingIndicator()
                    }
                }
            }

            // ── 全双工状态指示条 ──
            DuplexStatusBar(
                duplexState = duplexState,
                isMuted = isMuted,
                bargeInDetected = showBargeInIndicator,
                bargeInAlpha = bargeInAlpha,
            )

            // ── 全双工控制面板（底部操作栏）──
            if (showTextInput) {
                // 文字输入备选模式
                DuplexTextInputBar(
                    inputText = inputText,
                    onInputChange = { inputText = it },
                    onSend = {
                        if (inputText.isNotBlank()) {
                            vm.sendInterviewAnswer(inputText.trim())
                            inputText = ""
                        }
                    },
                    onCloseTextInput = { showTextInput = false },
                )
            } else {
                // 默认全双工控制面板
                DuplexControlPanel(
                    isMuted = isMuted,
                    elapsedSeconds = interviewState.elapsedSeconds,
                    questionCount = interviewState.questionCount,
                    totalQuestions = config?.questionCount ?: 8,
                    duplexState = duplexState,
                    onToggleMute = { vm.toggleInterviewMute() },
                    onEnd = { showEndDialog = true },
                    onShowTextInput = { showTextInput = true },
                )
            }
        }
    }

    // ── 结束确认对话框 ──
    if (showEndDialog) {
        AlertDialog(
            onDismissRequest = { showEndDialog = false },
            title = { Text("结束面试") },
            text = {
                Text(
                    if (interviewState.questionCount < 3)
                        "面试刚开始不久，确定要结束吗？"
                    else
                        "已回答 ${interviewState.questionCount} 个问题，确定结束并生成报告？"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showEndDialog = false
                        onEnd()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                ) {
                    Text("结束面试")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEndDialog = false }) {
                    Text("继续面试")
                }
            },
        )
    }
}

// ── 教练反馈卡片 ──

@Composable
private fun CoachFeedbackCard(feedback: CoachFeedback) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1)), // 浅黄色背景
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .clickable { expanded = !expanded },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("✨ ", fontSize = 14.sp)
                Text(
                    text = feedback.quickFeedback.ifBlank { "教练建议" },
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    color = TextDark,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (expanded) "▼" else "▶",
                    fontSize = 12.sp,
                    color = TextGrey,
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    HorizontalDivider(color = Color(0xFFFFE082))

                    // 维度评分
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        ScoreBadge("逻辑", feedback.logicScore)
                        ScoreBadge("表达", feedback.clarityScore)
                        ScoreBadge("自信", feedback.confidenceScore)
                    }

                    // STAR 法则说明
                    if (feedback.starUsage.isNotBlank()) {
                        Text(
                            text = "📌 STAR法则: ${feedback.starUsage}",
                            fontSize = 12.sp,
                            color = TextDark.copy(alpha = 0.7f),
                        )
                    }

                    // 详细建议
                    if (feedback.detailedFeedback.isNotBlank()) {
                        Text(
                            text = feedback.detailedFeedback,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            color = TextDark,
                        )
                    }
                }
            }
        }
    }
}

/** 小评分徽章 */
@Composable
private fun ScoreBadge(label: String, score: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "%.1f".format(score),
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            color = if (score >= 7f) Color(0xFF2E7D32) else if (score >= 5f) AccentBlue else Color(0xFFF57C00),
        )
        Text(
            text = label,
            fontSize = 10.sp,
            color = TextGrey,
        )
    }
}

// ── 对话气泡 ──

@Composable
private fun InterviewBubble(message: DialogueTurn) {
    val isInterviewer = message.role == "interviewer"

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isInterviewer) Arrangement.Start else Arrangement.End,
    ) {
        if (isInterviewer) {
            // 面试官头像
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(AccentBlue),
                contentAlignment = Alignment.Center,
            ) {
                Text("AI", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        // 问题分类标签
        if (isInterviewer && message.questionCategory != null && message.questionCategory != "追问" && message.questionCategory != "结束") {
            Column {
                // 分类标签
                Text(
                    text = message.questionCategory!!,
                    fontSize = 10.sp,
                    color = Color.White,
                    modifier = Modifier
                        .background(AccentBlue, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
        }

        // 消息气泡
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = if (isInterviewer) 4.dp else 16.dp,
                        topEnd = if (isInterviewer) 16.dp else 4.dp,
                        bottomStart = 16.dp,
                        bottomEnd = 16.dp,
                    )
                )
                .background(
                    if (isInterviewer) CardWhite else Brush.linearGradient(
                        colors = listOf(UserBubbleStart, UserBubbleStart)
                    )
                )
                .shadow(
                    elevation = if (isInterviewer) 1.dp else 2.dp,
                    shape = RoundedCornerShape(16.dp),
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = message.content,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = if (isInterviewer) TextDark else Color.White,
            )
        }

        if (!isInterviewer) {
            Spacer(modifier = Modifier.width(8.dp))
            // 用户头像
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(UserBubbleStart),
                contentAlignment = Alignment.Center,
            ) {
                Text("我", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ── 思考中指示器 ──

@Composable
private fun ThinkingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "thinking")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot_alpha",
    )

    Row(
        modifier = Modifier.padding(start = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(AccentBlue),
            contentAlignment = Alignment.Center,
        ) {
            Text("AI", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(CardWhite)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(3) { i ->
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(AccentBlue.copy(alpha = alpha - i * 0.15f)),
                )
            }
        }
    }
}

// ── 输入栏 ──

@Composable
private fun InterviewInputBar(
    inputText: String,
    onInputChange: (String) -> Unit,
    isListening: Boolean,
    isSpeaking: Boolean,
    useVoice: Boolean,
    onToggleVoiceMode: () -> Unit,
    onSendText: () -> Unit,
    onToggleVoiceInput: () -> Unit,
    onStopSpeaking: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            // 输入模式切换
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // 文字输入模式
                    FilledTonalButton(
                        onClick = { if (useVoice) onToggleVoiceMode() },
                        modifier = Modifier.height(30.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = if (!useVoice) AccentBlue.copy(alpha = 0.12f) else Color.Transparent,
                        ),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
                    ) {
                        Icon(
                            Icons.Default.Description,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = if (!useVoice) AccentBlue else TextGrey,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("文字", fontSize = 12.sp, color = if (!useVoice) AccentBlue else TextGrey)
                    }

                    // 语音输入模式
                    FilledTonalButton(
                        onClick = { if (!useVoice) onToggleVoiceMode() },
                        modifier = Modifier.height(30.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = if (useVoice) AccentBlue.copy(alpha = 0.12f) else Color.Transparent,
                        ),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
                    ) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = if (useVoice) AccentBlue else TextGrey,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("语音", fontSize = 12.sp, color = if (useVoice) AccentBlue else TextGrey)
                    }
                }

                // 语音播放中显示停止按钮
                if (isSpeaking) {
                    IconButton(
                        onClick = onStopSpeaking,
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = "停止播放",
                            tint = Color(0xFFE53935),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 输入区域
            if (useVoice) {
                // 语音输入模式
                VoiceInputArea(
                    isListening = isListening,
                    onToggleListening = onToggleVoiceInput,
                )
            } else {
                // 文字输入模式
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = onInputChange,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("输入你的回答...", fontSize = 14.sp) },
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = Color(0xFFE4E4E4),
                            focusedBorderColor = AccentBlue,
                        ),
                        maxLines = 4,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = onSendText,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(AccentBlue),
                        enabled = inputText.isNotBlank(),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "发送",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

// ── 语音输入区域 ──

@Composable
private fun VoiceInputArea(
    isListening: Boolean,
    onToggleListening: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isListening) Color(0xFFFEE2E2) else Color(0xFFF5F5F5)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(
                onClick = onToggleListening,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (isListening) Color(0xFFE53935) else AccentBlue),
            ) {
                Icon(
                    if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
            Text(
                text = if (isListening) "录音中... 点击停止" else "点击开始语音输入",
                fontSize = 12.sp,
                color = if (isListening) Color(0xFFE53935) else TextGrey,
            )
        }
    }
}

// ── 全双工状态指示条 ──

/**
 * 根据全双工引擎状态显示实时状态文字。
 * 插话（BargeIn）发生时闪烁提示。
 */
@Composable
private fun DuplexStatusBar(
    duplexState: FullDuplexAudioEngine.DuplexState?,
    isMuted: Boolean,
    bargeInDetected: Boolean,
    bargeInAlpha: Float,
) {
    val statusText = when {
        bargeInDetected -> "🗣️ 您打断了 AI"
        isMuted -> "🔇 已静音"
        duplexState == null -> "📡 连接中..."
        duplexState == FullDuplexAudioEngine.DuplexState.AI_SPEAKING -> "AI 说话中..."
        duplexState == FullDuplexAudioEngine.DuplexState.LISTENING -> "正在听您说..."
        duplexState == FullDuplexAudioEngine.DuplexState.CONNECTED -> "✅ 已连接"
        duplexState == FullDuplexAudioEngine.DuplexState.IDLE -> "⏸️ 待机中"
        duplexState == FullDuplexAudioEngine.DuplexState.MUTED -> "🔇 已静音"
        else -> ""
    }

    val statusColor = when {
        bargeInDetected -> Color(0xFFFF9800) // 橙色闪烁
        isMuted || duplexState == FullDuplexAudioEngine.DuplexState.MUTED -> Color(0xFFE53935)
        duplexState == FullDuplexAudioEngine.DuplexState.AI_SPEAKING -> AccentBlue
        duplexState == FullDuplexAudioEngine.DuplexState.LISTENING -> Color(0xFF4CAF50)
        else -> TextGrey
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = statusText,
            fontSize = 12.sp,
            color = statusColor.copy(alpha = if (bargeInDetected) bargeInAlpha else 1f),
            fontWeight = if (bargeInDetected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

// ── 全双工控制面板（主模式） ──

/**
 * 全双工通话底部控制面板：
 * [静音按钮]  [通话时长 / 题目进度]  [结束按钮]
 */
@Composable
private fun DuplexControlPanel(
    isMuted: Boolean,
    elapsedSeconds: Int,
    questionCount: Int,
    totalQuestions: Int,
    duplexState: FullDuplexAudioEngine.DuplexState?,
    onToggleMute: () -> Unit,
    onEnd: () -> Unit,
    onShowTextInput: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 第一行：静音 | 时长/进度 | 结束
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                // ── 静音按钮 ──
                FilledTonalButton(
                    onClick = onToggleMute,
                    shape = CircleShape,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (isMuted) Color(0xFFE53935) else Color(0xFFF5F5F5),
                        contentColor = if (isMuted) Color.White else TextDark,
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
                ) {
                    Icon(
                        imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = if (isMuted) "取消静音" : "静音",
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isMuted) "已静音" else "静音",
                        fontSize = 12.sp,
                    )
                }

                // ── 通话时长 + 进度 ──
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // 格式化时长 MM:SS / 总时长
                    val minutes = elapsedSeconds / 60
                    val seconds = elapsedSeconds % 60
                    Text(
                        text = String.format(Locale.getDefault(), "%02d:%02d / --:--", minutes, seconds),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextDark,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                    // 题目进度
                    Text(
                        text = "第 $questionCount / $totalQuestions 题",
                        fontSize = 11.sp,
                        color = TextGrey,
                    )
                }

                // ── 结束按钮 ──
                FilledTonalButton(
                    onClick = onEnd,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color(0xFFFFEBEE),
                        contentColor = Color(0xFFE53935),
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "结束面试",
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("结束", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 第二行：切换到文字输入备选
            TextButton(
                onClick = onShowTextInput,
                modifier = Modifier.padding(vertical = 0.dp),
            ) {
                Text(
                    "改用文字输入 →",
                    fontSize = 12.sp,
                    color = TextGrey,
                )
            }
        }
    }
}

// ── 文字输入备选栏 ──

/**
 * 当用户不想用语音时，展示精简的文字输入面板。
 */
@Composable
private fun DuplexTextInputBar(
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onCloseTextInput: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            // 关闭文字输入提示
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onCloseTextInput) {
                    Text("← 返回语音模式", fontSize = 12.sp, color = AccentBlue)
                }
            }

            // 文字输入行
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入你的回答...", fontSize = 14.sp) },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = Color(0xFFE4E4E4),
                        focusedBorderColor = AccentBlue,
                    ),
                    maxLines = 4,
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = onSend,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(AccentBlue),
                    enabled = inputText.isNotBlank(),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "发送",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

// ── 计时器组件 ──

@Composable
private fun InterviewTimer(elapsedSeconds: Int) {
    val minutes = elapsedSeconds / 60
    val seconds = elapsedSeconds % 60
    Text(
        text = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds),
        fontSize = 13.sp,
        color = TextGrey,
        modifier = Modifier.padding(end = 8.dp),
        fontWeight = FontWeight.Medium,
    )
}

// ==================== 报告界面 ====================

/**
 * 面试报告界面 — 展示评分、优点、不足、各题评估。
 */
@Composable
private fun InterviewReportScreen(
    vm: MainViewModel,
    onRestart: () -> Unit,
    onBack: () -> Unit,
    onSaveToNote: () -> Unit,
) {
    val interviewState by vm.interviewState.collectAsStateCompat()
    val report = interviewState.report
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("面试报告", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.White,
                ),
            )
        },
        containerColor = BgGray,
    ) { padding ->
        if (report == null) {
            // 报告生成中
            Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator(color = AccentBlue)
                    Text("正在生成面试报告...", color = TextGrey)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // ── 总分 + 判定 ──
                item {
                    ScoreCard(report = report)
                }

                // ── 总体评价 ──
                if (report.summary.isNotBlank()) {
                    item {
                        SummaryCard(summary = report.summary)
                    }
                }

                // ── 各维度详细评分（雷达图替代）──
                if (report.dimensions.isNotEmpty()) {
                    item {
                        DimensionsChart(dimensions = report.dimensions)
                    }
                }

                // ── 优点 ──
                if (report.strengths.isNotEmpty()) {
                    item {
                        EvaluationSection(
                            title = "✅ 优势",
                            iconColor = Color(0xFF2E7D32),
                            items = report.strengths,
                        )
                    }
                }

                // ── 不足 ──
                if (report.weaknesses.isNotEmpty()) {
                    item {
                        EvaluationSection(
                            title = "⚠️ 待改进",
                            iconColor = Color(0xFFE53935),
                            items = report.weaknesses,
                        )
                    }
                }

                // ── 建议 ──
                if (report.recommendations.isNotEmpty()) {
                    item {
                        EvaluationSection(
                            title = "💡 改进建议",
                            iconColor = AccentBlue,
                            items = report.recommendations,
                        )
                    }
                }

                // ── 对话记录（可折叠）──
                if (report.transcript.isNotEmpty()) {
                    item {
                        TranscriptViewer(transcript = report.transcript)
                    }
                }

                // ── 操作按钮 ──
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // 保存到笔记
                        OutlinedButton(
                            onClick = {
                                onSaveToNote()
                                Toast.makeText(context, "已保存到笔记", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(
                                Icons.Default.Description,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("保存笔记")
                        }

                        // 重新面试
                        Button(
                            onClick = onRestart,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("重新面试")
                        }
                    }
                }

                // 底部留白
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }
}

// ── 评分卡片 ──

@Composable
private fun ScoreCard(report: InterviewReport) {
    val verdictColor = when (report.verdict) {
        Verdict.PASS -> Color(0xFF2E7D32)
        Verdict.CONDITIONAL_PASS -> Color(0xFFF57C00)
        Verdict.FAIL -> Color(0xFFE53935)
    }

    val verdictLabel = when (report.verdict) {
        Verdict.PASS -> "通过 ✅"
        Verdict.CONDITIONAL_PASS -> "有条件通过 ⚠️"
        Verdict.FAIL -> "未通过 ❌"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 判定大字体
            Text(
                text = verdictLabel,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = verdictColor,
            )
            Spacer(modifier = Modifier.height(8.dp))
            // 分数
            Text(
                text = "${report.overallScore.toInt()} 分",
                fontSize = 36.sp,
                fontWeight = FontWeight.ExtraBold,
                color = TextDark,
            )
            Spacer(modifier = Modifier.height(4.dp))
            // 评级描述
            val gradeDesc = when {
                report.overallScore >= 85 -> "优秀"
                report.overallScore >= 70 -> "良好"
                report.overallScore >= 55 -> "一般"
                report.overallScore >= 40 -> "较差"
                else -> "需加强"
            }
            Text(
                text = gradeDesc,
                fontSize = 14.sp,
                color = TextGrey,
            )
        }
    }
}

// ── 总体评价卡片 ──

@Composable
private fun SummaryCard(summary: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "总体评价",
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = TextDark,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = summary,
                fontSize = 14.sp,
                lineHeight = 22.sp,
                color = TextDark.copy(alpha = 0.8f),
            )
        }
    }
}

// ── 维度图表（柱状图） ──

@Composable
private fun DimensionsChart(dimensions: List<EvaluationDimension>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "📊 各维度评分",
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = TextDark,
            )
            Spacer(modifier = Modifier.height(12.dp))

            dimensions.forEach { dim ->
                DimensionBar(dimension = dim)
                if (dim != dimensions.last()) {
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
private fun DimensionBar(dimension: EvaluationDimension) {
    val percentage = (dimension.score / dimension.maxScore).coerceIn(0f, 1f)
    val barColor = when {
        dimension.score >= 8f -> Color(0xFF2E7D32)
        dimension.score >= 6f -> AccentBlue
        dimension.score >= 4f -> Color(0xFFF57C00)
        else -> Color(0xFFE53935)
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = dimension.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = TextDark,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${dimension.score.toInt()}/${dimension.maxScore.toInt()}",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = barColor,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFFF0F0F0)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(percentage)
                    .height(8.dp)
                    .background(barColor)
            )
        }

        if (dimension.feedback.isNotBlank()) {
            Text(
                text = dimension.feedback,
                fontSize = 12.sp,
                color = TextGrey,
                lineHeight = 16.sp,
            )
        }
    }
}

// ── 评估列表 ──

@Composable
private fun EvaluationSection(
    title: String,
    iconColor: Color,
    items: List<String>,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = TextDark,
            )
            Spacer(modifier = Modifier.height(8.dp))
            items.forEachIndexed { index, item ->
                Row(
                    modifier = Modifier.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = "•",
                        fontSize = 14.sp,
                        color = iconColor,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = item,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = TextDark.copy(alpha = 0.8f),
                        modifier = Modifier.weight(1f),
                    )
                }
                if (index < items.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = Color(0xFFF0F0F0),
                    )
                }
            }
        }
    }
}

// ── 对话记录查看器 ──

@Composable
private fun TranscriptViewer(transcript: List<DialogueTurn>) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "📝 完整对话记录 (${transcript.size} 条)",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = TextDark,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (expanded) "收起" else "展开",
                    fontSize = 13.sp,
                    color = AccentBlue,
                )
            }

            AnimatedVisibility(visible = expanded) {
                Spacer(modifier = Modifier.height(12.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp),
                ) {
                    items(transcript, key = it.timestamp.toString() + it.role) { turn ->
                        Row(
                            modifier = Modifier.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = if (turn.role == "interviewer") "👤 " else "🗣️ ",
                                fontSize = 13.sp,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (turn.role == "interviewer") "[面试官]" else "[我]",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (turn.role == "interviewer") AccentBlue else UserBubbleStart,
                                )
                                Text(
                                    text = turn.content,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                    color = TextDark,
                                )
                            }
                        }
                        if (turn != transcript.last()) {
                            HorizontalDivider(color = Color(0xFFF5F5F5))
                        }
                    }
                }
            }
        }
    }
}
