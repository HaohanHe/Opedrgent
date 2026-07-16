@file:OptIn(ExperimentalMaterial3Api::class)

package top.hsyscn.opedrgent.ui

import top.hsyscn.opedrgent.ui.theme.WarningColor
import top.hsyscn.opedrgent.ui.theme.SuccessGreen
import top.hsyscn.opedrgent.ui.theme.AccentOrange
import top.hsyscn.opedrgent.ui.theme.customColors
import top.hsyscn.opedrgent.ui.theme.SpacingTokens
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import top.hsyscn.opedrgent.ui.theme.themeErrorBackground
import top.hsyscn.opedrgent.ui.theme.InterviewDarkBg
import top.hsyscn.opedrgent.ui.theme.InterviewPurple
import top.hsyscn.opedrgent.ui.theme.InterviewSurface
import top.hsyscn.opedrgent.ui.theme.InterviewTextMuted
import top.hsyscn.opedrgent.ui.theme.InterviewInputBg
import top.hsyscn.opedrgent.ui.theme.InterviewBorder
import top.hsyscn.opedrgent.ui.theme.InterviewDisabledText
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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.interview.InterviewConfig
import top.hsyscn.opedrgent.interview.InterviewPhase
import top.hsyscn.opedrgent.interview.InterviewReport
import top.hsyscn.opedrgent.interview.InterviewType
import top.hsyscn.opedrgent.interview.DifficultyLevel
import top.hsyscn.opedrgent.interview.DialogueTurn
import top.hsyscn.opedrgent.interview.MaterialEntry
import top.hsyscn.opedrgent.interview.CoachFeedback
import top.hsyscn.opedrgent.interview.AnalysisResult
import top.hsyscn.opedrgent.interview.Verdict
import top.hsyscn.opedrgent.interview.EvaluationDimension
import top.hsyscn.opedrgent.interview.FullDuplexAudioEngine
import top.hsyscn.opedrgent.ui.theme.AccentBlue
import top.hsyscn.opedrgent.ui.theme.UserBubbleStart
import top.hsyscn.opedrgent.ui.collectAsStateCompat
import top.hsyscn.opedrgent.ui.components.LocalFeedbackController
import top.hsyscn.opedrgent.ui.components.isAtLeastMediumWidth
import top.hsyscn.opedrgent.ui.components.isExpandedWidth
import top.hsyscn.opedrgent.ui.components.rememberWindowSizeInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.hsyscn.opedrgent.ui.theme.themeBgGray
import top.hsyscn.opedrgent.ui.theme.themeBorderLight
import top.hsyscn.opedrgent.ui.theme.themeCardWhite
import top.hsyscn.opedrgent.ui.theme.themeDividerColor
import top.hsyscn.opedrgent.ui.theme.themeTextDark
import top.hsyscn.opedrgent.ui.theme.themeTextGrey
import top.hsyscn.opedrgent.ui.theme.WarningBg

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
 * 面试设置界面 — 选择模式后直接开始对话，LLM通过对话收集信息。
 */
@Composable
private fun InterviewSetupScreen(
    onStart: (InterviewConfig) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_interview_mode), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = themeBgGray(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        val contentMaxWidth = when {
            isExpandedWidth() -> 840.dp
            isAtLeastMediumWidth() -> 640.dp
            else -> Dp.Unspecified
        }
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (contentMaxWidth != Dp.Unspecified) {
                            Modifier.widthIn(max = contentMaxWidth)
                        } else {
                            Modifier
                        }
                    )
                    .padding(horizontal = SpacingTokens.lg, vertical = SpacingTokens.md),
                verticalArrangement = Arrangement.spacedBy(SpacingTokens.lg),
            ) {
            Text(
                text = "选择面试模式，直接开始对话",
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium,
                color = themeTextDark(),
            )

            Text(
                text = "AI会通过对话了解你的需求，无需提前填写信息",
                style = MaterialTheme.typography.bodyMedium,
                color = themeTextGrey(),
            )

            // ===== 全双工语音模型配置横幅 =====
            RealtimeVoiceBanner(
                onStartEngine = { apiKey, model, voice ->
                    onStart(
                        InterviewConfig(
                            type = InterviewType.JOB_INTERVIEW, // 默认，用户后续可切换
                            enableVoiceConversation = true,
                        ).apply { stepApiKey = apiKey; stepModel = model; stepVoice = voice }
                    )
                },
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.md),
                modifier = Modifier.fillMaxWidth(),
            ) {
                ModeSelectionCard(
                    icon = Icons.Default.Work,
                    title = "求职面试",
                    description = "模拟真实面试\n根据公司/岗位定制",
                    isSelected = false,
                    onClick = { onStart(InterviewConfig(type = InterviewType.JOB_INTERVIEW)) },
                    modifier = Modifier.weight(1f),
                )
                ModeSelectionCard(
                    icon = Icons.Default.School,
                    title = "论文答辩",
                    description = "模拟学术答辩\n挑战研究方法与结论",
                    isSelected = false,
                    onClick = { onStart(InterviewConfig(type = InterviewType.THESIS_DEFENSE)) },
                    modifier = Modifier.weight(1f),
                )
            }

            // 自定义场景入口
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = ShapeTokens.mediumShape,
                colors = CardDefaults.cardColors(containerColor = themeCardWhite()),
            ) {
                Column(modifier = Modifier.padding(SpacingTokens.lg)) {
                    Text(
                        text = "其他场景",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyLarge,
                        color = themeTextDark(),
                    )
                    Spacer(Modifier.height(SpacingTokens.sm))
                    Text(
                        text = "投资路演、英语口语、销售谈判... 任何对话场景都可以",
                        style = MaterialTheme.typography.bodyMedium,
                        color = themeTextGrey(),
                    )
                    Spacer(Modifier.height(SpacingTokens.md))
                    Button(
                        onClick = { onStart(InterviewConfig(type = InterviewType.CUSTOM)) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = ShapeTokens.smallShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentBlue,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Text("开始自定义场景", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            }
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

// ── 全双工语音引擎配置横幅 ──

/**
 * 面试模式顶部的全双工语音引擎配置横幅。
 *
 * 展示：
 * - 功能说明卡片
 * - API Key 输入框
 * - 模型 ID 输入框（默认 stepaudio-2.5-realtime，可改为任意支持全双工的实时语音模型）
 * - 音色 ID 输入框（默认 linjiajiejie，可改为对应模型支持的任意音色）
 * - "开始实时语音面试" 按钮
 */
@Composable
private fun RealtimeVoiceBanner(
    onStartEngine: (apiKey: String, model: String, voice: String) -> Unit,
) {
    var showConfig by rememberSaveable { mutableStateOf(false) }
    var apiKey by rememberSaveable { mutableStateOf("") }
    // 默认模型/音色仅作为示例占位，用户可自由输入任意兼容的实时语音模型与音色
    var modelId by rememberSaveable { mutableStateOf("stepaudio-2.5-realtime") }
    var voiceId by rememberSaveable { mutableStateOf("linjiajiejie") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = ShapeTokens.mediumShape,
        colors = CardDefaults.cardColors(
            containerColor = InterviewDarkBg,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(SpacingTokens.lg),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.md),
        ) {
            // 标题行：功能说明 + 支持标签
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.md),
            ) {
                Text(
                    text = stringResource(R.string.interview_full_duplex_title),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                )

                Spacer(Modifier.weight(1f))

                // 已支持标签
                Text(
                    text = stringResource(R.string.interview_full_duplex_supported),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    letterSpacing = 2.sp,
                    modifier = Modifier
                        .background(
                            color = InterviewPurple,
                            shape = ShapeTokens.extraSmallShape,
                        )
                        .padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xs),
                )
            }

            // 核心说明
            Text(
                text = stringResource(R.string.interview_full_duplex_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = InterviewTextMuted,
            )

            if (!showConfig) {
                // 收起状态：显示配置入口按钮
                Button(
                    onClick = { showConfig = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = ShapeTokens.smallShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = InterviewSurface,
                        contentColor = InterviewTextMuted,
                    ),
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(SpacingTokens.sm))
                    Text(stringResource(R.string.interview_full_duplex_config), style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                // 展开状态：API Key + 模型/音色自由输入
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(stringResource(R.string.interview_api_key_label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    placeholder = { Text("sk-...", style = MaterialTheme.typography.bodySmall, color = InterviewDisabledText) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = ShapeTokens.smallShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = InterviewPurple,
                        unfocusedBorderColor = InterviewBorder,
                        cursorColor = InterviewPurple,
                        focusedTextColor = MaterialTheme.colorScheme.onPrimary,
                        unfocusedTextColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )

                // 模型 + 音色 自由输入行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
                ) {
                    OutlinedTextField(
                        value = modelId,
                        onValueChange = { modelId = it },
                        label = { Text(stringResource(R.string.interview_model_label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        placeholder = { Text("stepaudio-2.5-realtime", style = MaterialTheme.typography.bodySmall, color = InterviewDisabledText) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = ShapeTokens.smallShape,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = InterviewPurple,
                            unfocusedBorderColor = InterviewBorder,
                            cursorColor = InterviewPurple,
                            focusedTextColor = MaterialTheme.colorScheme.onPrimary,
                            unfocusedTextColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )

                    OutlinedTextField(
                        value = voiceId,
                        onValueChange = { voiceId = it },
                        label = { Text(stringResource(R.string.interview_voice_label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        placeholder = { Text("linjiajiejie", style = MaterialTheme.typography.bodySmall, color = InterviewDisabledText) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = ShapeTokens.smallShape,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = InterviewPurple,
                            unfocusedBorderColor = InterviewBorder,
                            cursorColor = InterviewPurple,
                            focusedTextColor = MaterialTheme.colorScheme.onPrimary,
                            unfocusedTextColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }

                // 启动按钮
                val canStart = apiKey.isNotBlank() && modelId.isNotBlank() && voiceId.isNotBlank()
                Button(
                    onClick = {
                        if (canStart) {
                            onStartEngine(apiKey.trim(), modelId.trim(), voiceId.trim())
                        }
                    },
                    enabled = canStart,
                    modifier = Modifier.fillMaxWidth(),
                    shape = ShapeTokens.smallShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (canStart) InterviewPurple else InterviewBorder,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = InterviewInputBg,
                        disabledContentColor = InterviewDisabledText,
                    ),
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(SpacingTokens.sm))
                    Text(
                        text = if (canStart) stringResource(R.string.interview_full_duplex_start) else stringResource(R.string.interview_full_duplex_input_key),
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
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
        shape = ShapeTokens.mediumShape,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) AccentBlue.copy(alpha = 0.08f) else themeCardWhite(),
        ),
        border = if (isSelected) {
            androidx.compose.foundation.BorderStroke(2.dp, AccentBlue)
        } else {
            androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SpacingTokens.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
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
                    tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else AccentBlue,
                    modifier = Modifier.size(24.dp),
                )
            }
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleSmall,
                color = themeTextDark(),
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = themeTextGrey(),
                textAlign = TextAlign.Center,
                
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
            .background(themeBgGray()),
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

            Spacer(modifier = Modifier.height(SpacingTokens.sm))

            Text(
                text = "AI 正在分析您的材料...",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = themeTextDark(),
            )

            // 显示分析结果（如果有）
            if (analysisResult != null && analysisResult.keyPoints.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = ShapeTokens.mediumShape,
                    colors = CardDefaults.cardColors(containerColor = themeCardWhite()),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(SpacingTokens.lg),
                        verticalArrangement = Arrangement.spacedBy(SpacingTokens.md),
                    ) {
                        Text(
                            text = "材料分析结果",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall,
                            color = themeTextDark(),
                        )

                        // 关键点
                        if (analysisResult.keyPoints.isNotEmpty()) {
                            Text(
                                text = "关键信息",
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium,
                                color = AccentBlue,
                            )
                            analysisResult.keyPoints.take(5).forEach { point ->
                                Text(
                                    text = "• $point",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = themeTextDark(),
                                    
                                )
                            }
                        }

                        // 建议提问方向
                        if (analysisResult.suggestedQuestions.isNotEmpty()) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Text(
                                text = "建议关注方向",
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium,
                                color = AccentBlue,
                            )
                            analysisResult.suggestedQuestions.take(3).forEach { q ->
                                Text(
                                    text = "• $q",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = themeTextDark(),
                                    
                                )
                            }
                        }

                        // 风险点
                        if (analysisResult.riskAreas.isNotEmpty()) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Text(
                                text = "可能被深挖的点",
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium,
                                color = WarningColor,
                            )
                            analysisResult.riskAreas.take(3).forEach { risk ->
                                Text(
                                    text = "• $risk",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = themeTextDark(),
                                    
                                )
                            }
                        }
                    }
                }
            }

            Text(
                text = "即将开始面试...",
                style = MaterialTheme.typography.bodyMedium,
                color = themeTextGrey(),
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
                            style = MaterialTheme.typography.titleMedium,
                        )
                        // 面试进度
                        if (config != null && interviewState.questionCount > 0) {
                            Text(
                                text = "第 ${interviewState.questionCount}/${config.questionCount} 题",
                                style = MaterialTheme.typography.bodySmall,
                                color = themeTextGrey(),
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    // 计时器
                    InterviewTimer(elapsedSeconds = interviewState.elapsedSeconds)

                    // 结束按钮
                    TextButton(
                        onClick = { showEndDialog = true },
                    ) {
                        Text("结束", color = MaterialTheme.colorScheme.error)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = themeBgGray(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        val contentMaxWidth = when {
            isExpandedWidth() -> 900.dp
            isAtLeastMediumWidth() -> 720.dp
            else -> Dp.Unspecified
        }
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (contentMaxWidth != Dp.Unspecified) {
                            Modifier.widthIn(max = contentMaxWidth)
                        } else {
                            Modifier
                        }
                    ),
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
                    .padding(horizontal = SpacingTokens.md),
                verticalArrangement = Arrangement.spacedBy(SpacingTokens.md),
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
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
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
            .padding(horizontal = SpacingTokens.md),
        shape = ShapeTokens.mediumShape,
        colors = CardDefaults.cardColors(containerColor = WarningBg), // 浅黄色背景
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier
                .padding(SpacingTokens.md)
                .clickable { expanded = !expanded },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(" ", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = feedback.quickFeedback.ifBlank { "教练建议" },
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodyLarge,
                    color = themeTextDark(),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (expanded) "▼" else "▶",
                    style = MaterialTheme.typography.bodySmall,
                    color = themeTextGrey(),
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = SpacingTokens.sm),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    HorizontalDivider(color = WarningColor)

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
                    val starScore = feedback.scores["STAR法则"] ?: feedback.scores["star"] ?: 0f
                    if (starScore > 0f) {
                        Text(
                            text = "STAR法则评分: %.1f/10".format(starScore),
                            style = MaterialTheme.typography.bodySmall,
                            color = themeTextDark().copy(alpha = 0.7f),
                        )
                    }

                    // 详细建议
                    if (feedback.detailedFeedback.isNotBlank()) {
                        Text(
                            text = feedback.detailedFeedback,
                            style = MaterialTheme.typography.bodyMedium,
                            
                            color = themeTextDark(),
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
            style = MaterialTheme.typography.bodyLarge,
            color = if (score >= 7f) MaterialTheme.customColors.chipSuccessText else if (score >= 5f) AccentBlue else WarningColor,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = themeTextGrey(),
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
                Text("AI", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(SpacingTokens.sm))
        }

        // 问题分类标签
        val category = message.questionCategory
        if (isInterviewer && category != null && category != "追问" && category != "结束") {
            Column {
                // 分类标签
                Text(
                    text = category,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .background(AccentBlue, ShapeTokens.smallShape)
                        .padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xs),
                )
                Spacer(modifier = Modifier.height(SpacingTokens.xs))
            }
        }

        // 消息气泡：根据窗口宽度动态限制最大宽度
        val bubbleWidthFraction = when (rememberWindowSizeInfo().widthSizeClass) {
            WindowWidthSizeClass.Expanded -> 0.45f
            WindowWidthSizeClass.Medium -> 0.55f
            else -> 0.75f
        }
        BoxWithConstraints {
            val maxBubbleWidth = maxWidth * bubbleWidthFraction
            Box(
                modifier = Modifier
                    .widthIn(max = maxBubbleWidth)
                    .clip(
                        RoundedCornerShape(
                            topStart = if (isInterviewer) ShapeTokens.extraSmall else ShapeTokens.large,
                            topEnd = if (isInterviewer) ShapeTokens.large else ShapeTokens.extraSmall,
                            bottomStart = ShapeTokens.large,
                            bottomEnd = ShapeTokens.large,
                        )
                    )
                    .background(
                        if (isInterviewer) androidx.compose.ui.graphics.SolidColor(themeCardWhite()) else Brush.linearGradient(
                            colors = listOf(UserBubbleStart, UserBubbleStart)
                        )
                    )
                    .shadow(
                        elevation = if (isInterviewer) 1.dp else 2.dp,
                        shape = ShapeTokens.largeShape,
                    )
                    .padding(horizontal = SpacingTokens.md, vertical = SpacingTokens.sm),
            ) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isInterviewer) themeTextDark() else MaterialTheme.colorScheme.onPrimary,
                )
            }
        }

        if (!isInterviewer) {
            Spacer(modifier = Modifier.width(SpacingTokens.sm))
            // 用户头像
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(UserBubbleStart),
                contentAlignment = Alignment.Center,
            ) {
                Text("我", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
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
            Text("AI", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.width(SpacingTokens.sm))
        Row(
            modifier = Modifier
                .clip(ShapeTokens.largeShape)
                .background(themeCardWhite())
                .padding(horizontal = SpacingTokens.lg, vertical = SpacingTokens.md),
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
        bargeInDetected -> "您打断了 AI"
        isMuted -> "已静音"
        duplexState == null -> "连接中..."
        duplexState == FullDuplexAudioEngine.DuplexState.AI_SPEAKING -> "AI 说话中..."
        duplexState == FullDuplexAudioEngine.DuplexState.LISTENING -> "正在听您说..."
        duplexState == FullDuplexAudioEngine.DuplexState.CONNECTED -> "已连接"
        duplexState == FullDuplexAudioEngine.DuplexState.IDLE -> "待机中"
        duplexState == FullDuplexAudioEngine.DuplexState.MUTED -> "已静音"
        else -> ""
    }

    val statusColor = when {
        bargeInDetected -> WarningColor // 橙色闪烁
        isMuted || duplexState == FullDuplexAudioEngine.DuplexState.MUTED -> MaterialTheme.colorScheme.error
        duplexState == FullDuplexAudioEngine.DuplexState.AI_SPEAKING -> AccentBlue
        duplexState == FullDuplexAudioEngine.DuplexState.LISTENING -> SuccessGreen
        else -> themeTextGrey()
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = SpacingTokens.lg, vertical = SpacingTokens.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodySmall,
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
        shape = RoundedCornerShape(topStart = ShapeTokens.large, topEnd = ShapeTokens.large),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = SpacingTokens.lg, vertical = SpacingTokens.md),
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
                        containerColor = if (isMuted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.surfaceContainerLow,
                        contentColor = if (isMuted) MaterialTheme.colorScheme.onPrimary else themeTextDark(),
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
                ) {
                    Icon(
                        imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = if (isMuted) "取消静音" else "静音",
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(SpacingTokens.xs))
                    Text(
                        text = if (isMuted) "已静音" else "静音",
                        style = MaterialTheme.typography.bodySmall,
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
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = themeTextDark(),
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                    // 题目进度
                    Text(
                        text = "第 $questionCount / $totalQuestions 题",
                        style = MaterialTheme.typography.labelSmall,
                        color = themeTextGrey(),
                    )
                }

                // ── 结束按钮 ──
                FilledTonalButton(
                    onClick = onEnd,
                    shape = ShapeTokens.mediumShape,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = themeErrorBackground(),
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "结束面试",
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(SpacingTokens.xs))
                    Text("结束", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }
            }

            Spacer(modifier = Modifier.height(SpacingTokens.sm))

            // 第二行：切换到文字输入备选
            TextButton(
                onClick = onShowTextInput,
                modifier = Modifier.padding(vertical = 0.dp),
            ) {
                Text(
                    "改用文字输入 →",
                    style = MaterialTheme.typography.bodySmall,
                    color = themeTextGrey(),
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
        shape = RoundedCornerShape(topStart = ShapeTokens.large, topEnd = ShapeTokens.large),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = SpacingTokens.md, vertical = SpacingTokens.sm),
        ) {
            // 关闭文字输入提示
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onCloseTextInput) {
                    Text("← 返回语音模式", style = MaterialTheme.typography.bodySmall, color = AccentBlue)
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
                    placeholder = { Text("输入你的回答...", style = MaterialTheme.typography.bodyLarge) },
                    shape = ShapeTokens.mediumShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        focusedBorderColor = AccentBlue,
                    ),
                    maxLines = 4,
                )
                Spacer(modifier = Modifier.width(SpacingTokens.sm))
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
                        tint = MaterialTheme.colorScheme.onPrimary,
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
        style = MaterialTheme.typography.bodyMedium,
        color = themeTextGrey(),
        modifier = Modifier.padding(end = SpacingTokens.sm),
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
    val feedback = LocalFeedbackController.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_interview_report), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = themeBgGray(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        val contentMaxWidth = when {
            isExpandedWidth() -> 900.dp
            isAtLeastMediumWidth() -> 720.dp
            else -> Dp.Unspecified
        }
        val reportInnerModifier = if (contentMaxWidth != Dp.Unspecified) {
            Modifier.widthIn(max = contentMaxWidth)
        } else {
            Modifier
        }
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentAlignment = if (report == null) Alignment.Center else Alignment.TopCenter,
        ) {
            if (report == null) {
                // 报告生成中
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(reportInnerModifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(SpacingTokens.lg),
                    ) {
                        CircularProgressIndicator(color = AccentBlue)
                        Text("正在生成面试报告...", color = themeTextGrey())
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(reportInnerModifier),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(SpacingTokens.lg),
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
                            title = "优势",
                            iconColor = MaterialTheme.customColors.chipSuccessText,
                            items = report.strengths,
                        )
                    }
                }

                // ── 不足 ──
                if (report.weaknesses.isNotEmpty()) {
                    item {
                        EvaluationSection(
                            title = "待改进",
                            iconColor = MaterialTheme.colorScheme.error,
                            items = report.weaknesses,
                        )
                    }
                }

                // ── 建议 ──
                if (report.recommendations.isNotEmpty()) {
                    item {
                        EvaluationSection(
                            title = "改进建议",
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
                    Spacer(modifier = Modifier.height(SpacingTokens.sm))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.md),
                    ) {
                        // 保存到笔记
                        OutlinedButton(
                            onClick = {
                                onSaveToNote()
                                feedback.showFeedback("已保存到笔记")
                            },
                            modifier = Modifier.weight(1f),
                            shape = ShapeTokens.mediumShape,
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
                            shape = ShapeTokens.mediumShape,
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
                item { Spacer(modifier = Modifier.height(SpacingTokens.lg)) }
            }
        }
    }
}
}

// ── 评分卡片 ──

@Composable
private fun ScoreCard(report: InterviewReport) {
    val verdictColor = when (report.verdict) {
        Verdict.PASS -> MaterialTheme.customColors.chipSuccessText
        Verdict.CONDITIONAL_PASS -> WarningColor
        Verdict.FAIL -> MaterialTheme.colorScheme.error
    }

    val verdictLabel = when (report.verdict) {
        Verdict.PASS -> "通过"
        Verdict.CONDITIONAL_PASS -> "有条件通过"
        Verdict.FAIL -> "未通过"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = ShapeTokens.largeShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SpacingTokens.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 判定大字体
            Text(
                text = verdictLabel,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = verdictColor,
            )
            Spacer(modifier = Modifier.height(SpacingTokens.sm))
            // 分数
            Text(
                text = "${report.overallScore.toInt()} 分",
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.ExtraBold,
                color = themeTextDark(),
            )
            Spacer(modifier = Modifier.height(SpacingTokens.xs))
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
                style = MaterialTheme.typography.bodyLarge,
                color = themeTextGrey(),
            )
        }
    }
}

// ── 总体评价卡片 ──

@Composable
private fun SummaryCard(summary: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = ShapeTokens.mediumShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(SpacingTokens.lg)) {
            Text(
                text = "总体评价",
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall,
                color = themeTextDark(),
            )
            Spacer(modifier = Modifier.height(SpacingTokens.sm))
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyLarge,
                
                color = themeTextDark().copy(alpha = 0.8f),
            )
        }
    }
}

// ── 维度图表（柱状图） ──

@Composable
private fun DimensionsChart(dimensions: List<EvaluationDimension>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = ShapeTokens.mediumShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(SpacingTokens.lg)) {
            Text(
                text = "各维度评分",
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall,
                color = themeTextDark(),
            )
            Spacer(modifier = Modifier.height(SpacingTokens.md))

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
        dimension.score >= 8f -> MaterialTheme.customColors.chipSuccessText
        dimension.score >= 6f -> AccentBlue
        dimension.score >= 4f -> WarningColor
        else -> MaterialTheme.colorScheme.error
    }

    Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = dimension.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = themeTextDark(),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${dimension.score.toInt()}/${dimension.maxScore.toInt()}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = barColor,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(SpacingTokens.sm)
                .clip(ShapeTokens.extraSmallShape)
                .background(MaterialTheme.colorScheme.outlineVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(percentage)
                    .height(SpacingTokens.sm)
                    .background(barColor)
            )
        }

        if (dimension.feedback.isNotBlank()) {
            Text(
                text = dimension.feedback,
                style = MaterialTheme.typography.bodySmall,
                color = themeTextGrey(),
                
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
        shape = ShapeTokens.mediumShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(SpacingTokens.lg)) {
            Text(
                text = title,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall,
                color = themeTextDark(),
            )
            Spacer(modifier = Modifier.height(SpacingTokens.sm))
            items.forEachIndexed { index, item ->
                Row(
                    modifier = Modifier.padding(vertical = SpacingTokens.xs),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodyLarge,
                        color = iconColor,
                        modifier = Modifier.padding(top = SpacingTokens.xxs),
                    )
                    Spacer(modifier = Modifier.width(SpacingTokens.sm))
                    Text(
                        text = item,
                        style = MaterialTheme.typography.bodyLarge,
                        
                        color = themeTextDark().copy(alpha = 0.8f),
                        modifier = Modifier.weight(1f),
                    )
                }
                if (index < items.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = SpacingTokens.xs),
                        color = themeDividerColor(),
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
        shape = ShapeTokens.mediumShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(SpacingTokens.lg)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "完整对话记录 (${transcript.size} 条)",
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleSmall,
                    color = themeTextDark(),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AccentBlue,
                )
            }

            AnimatedVisibility(visible = expanded) {
                Spacer(modifier = Modifier.height(SpacingTokens.md))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp),
                ) {
                    items(transcript, key = { it.timestamp.toString() + it.role }) { turn ->
                        Row(
                            modifier = Modifier.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = if (turn.role == "interviewer") "  " else "  ",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (turn.role == "interviewer") "[面试官]" else "[我]",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (turn.role == "interviewer") AccentBlue else UserBubbleStart,
                                )
                                Text(
                                    text = turn.content,
                                    style = MaterialTheme.typography.bodyMedium,
                                    
                                    color = themeTextDark(),
                                )
                            }
                        }
                        if (turn != transcript.last()) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}
