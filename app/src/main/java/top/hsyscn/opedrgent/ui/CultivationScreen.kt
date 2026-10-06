@file:OptIn(ExperimentalMaterial3Api::class)

package top.hsyscn.opedrgent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.cultivation.model.PersonaProfile
import top.hsyscn.opedrgent.cultivation.model.ExemplarReport
import top.hsyscn.opedrgent.cultivation.model.FeedbackMode
import top.hsyscn.opedrgent.cultivation.model.FollowUp
import top.hsyscn.opedrgent.cultivation.model.FollowUpStatus
import top.hsyscn.opedrgent.cultivation.model.IssueMark
import top.hsyscn.opedrgent.cultivation.model.MirrorRoute
import top.hsyscn.opedrgent.cultivation.model.ReflectionInsights
import top.hsyscn.opedrgent.cultivation.model.ReflectionLens
import top.hsyscn.opedrgent.cultivation.model.TrendInsights
import top.hsyscn.opedrgent.cultivation.model.PersonaTrait
import top.hsyscn.opedrgent.cultivation.model.PersonaTraitStatus
import top.hsyscn.opedrgent.cultivation.store.ReflectionRecord
import top.hsyscn.opedrgent.ui.components.TrendCard
import top.hsyscn.opedrgent.ui.components.ModelRequiredCard
import top.hsyscn.opedrgent.modelreadiness.ModelKind
import top.hsyscn.opedrgent.modelreadiness.ModelReadinessRepository
import top.hsyscn.opedrgent.modelreadiness.ReadyState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import top.hsyscn.opedrgent.ui.state.CultivationStateManager
import top.hsyscn.opedrgent.ui.state.MirrorHandoff
import top.hsyscn.opedrgent.ui.state.ReflectionLocator
import top.hsyscn.opedrgent.ui.theme.ShapeTokens
import top.hsyscn.opedrgent.ui.theme.SpacingTokens
import top.hsyscn.opedrgent.ui.theme.themeBgGray
import top.hsyscn.opedrgent.ui.theme.themeTextDark
import top.hsyscn.opedrgent.ui.theme.themeTextGrey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class CultivationTab { MIRROR, EXEMPLAR, BASELINE, HISTORY }

@Composable
fun CultivationScreen(
    manager: CultivationStateManager,
    onBack: () -> Unit,
    onManageLocalModel: () -> Unit = {},
    onOpenGrowth: () -> Unit = {},
) {
    val state by manager.state.collectAsStateCompat()
    val snackbarHostState = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableStateOf(CultivationTab.MIRROR) }
    val context = LocalContext.current
    val readinessRepo = remember { ModelReadinessRepository.getInstance(context) }
    LaunchedEffect(Unit) { readinessRepo.refresh() }
    val readinessSnapshot by readinessRepo.snapshot.collectAsStateWithLifecycle()

    // 跨页移交：录音 / 笔记 / 洞察详情页由用户主动点「送入批判镜」后在此消费。
    // 只把转写预载进待分析框并小字标注来源，不自动开始分析；无移交内容则保持原样。
    var handoffSourceLabel by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        MirrorHandoff.consume()?.let { payload ->
            manager.loadTranscript(payload.transcript)
            handoffSourceLabel = buildString {
                append(context.getString(R.string.handoff_source_prefix))
                append(when (payload.sourceType) {
                    MirrorHandoff.SourceType.RECORDING -> context.getString(R.string.handoff_source_recording)
                    MirrorHandoff.SourceType.NOTE -> context.getString(R.string.handoff_source_note)
                    MirrorHandoff.SourceType.INSIGHT -> context.getString(R.string.handoff_source_insight)
                })
                if (payload.sourceTitle.isNotBlank()) {
                    append(" · ")
                    append(payload.sourceTitle)
                }
            }
        }
    }

    // 行动项「查看复盘」：定位到该复盘。本阶段切到历史区即可，不做精确滚动。
    LaunchedEffect(Unit) {
        if (ReflectionLocator.consume() != null) {
            tab = CultivationTab.HISTORY
        }
    }

    // 从设置加载完模型返回时，自动重新探测端侧就绪状态，不让用户手动找刷新
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) manager.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.error, state.info) {
        val msg = state.error ?: state.info
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            manager.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = themeBgGray(),
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = SpacingTokens.lg,
                vertical = SpacingTokens.sm,
            ),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.lg),
        ) {
            item {
                CultivationHeader(
                    tab = tab,
                    onBack = onBack,
                    // 认知镜不使用理想人格基准：MIRROR 且选了认知透镜时隐藏基准入口
                    showBaseline = !(tab == CultivationTab.MIRROR && state.lens == ReflectionLens.COGNITIVE),
                    onOpenBaseline = {
                        tab = CultivationTab.BASELINE
                    },
                    onOpenHistory = {
                        manager.refresh()
                        tab = CultivationTab.HISTORY
                    },
                    onBackToMirror = { tab = CultivationTab.MIRROR },
                )
            }

            if (tab == CultivationTab.MIRROR || tab == CultivationTab.EXEMPLAR) {
                item {
                    IosSegmented(
                        options = listOf(
                            stringResource(R.string.cultivation_tab_mirror),
                            stringResource(R.string.cultivation_tab_exemplar),
                        ),
                        selectedIndex = if (tab == CultivationTab.MIRROR) 0 else 1,
                        onSelect = {
                            tab = if (it == 0) CultivationTab.MIRROR else CultivationTab.EXEMPLAR
                        },
                    )
                }
            }

            // 端侧未就绪是“第一步”而非报错：两镜共用，先引导把模型跑起来
            // 端侧未就绪：统一 snapshot(llm) 门控，替换旧提示；云端 opt-in 路由不变
            if (!state.useCloud && readinessSnapshot.llm.state != ReadyState.READY) {
                item {
                    ModelRequiredCard(
                        status = readinessSnapshot.llm,
                        online = readinessSnapshot.online,
                        onStart = { readinessRepo.startRecommended(ModelKind.LLM) },
                        onPause = { readinessRepo.pause(ModelKind.LLM) },
                        onResume = { readinessRepo.resume(ModelKind.LLM) },
                        onCancel = { readinessRepo.cancel(ModelKind.LLM) },
                        onRetry = { readinessRepo.retry(ModelKind.LLM) },
                    )
                }
            }

            when (tab) {
                CultivationTab.MIRROR -> mirrorItems(manager, state, readinessSnapshot.llm.state == ReadyState.READY, handoffSourceLabel)
                CultivationTab.BASELINE -> personaItems(state.personaProfile)
                CultivationTab.HISTORY -> historyItems(manager, state.history, onOpenGrowth)
                CultivationTab.EXEMPLAR -> exemplarItems(manager, state)
            }
        }
    }
}

// ==================== 通用 Apple HIG 组件 ====================

/** 大标题导航行：返回 + 标题 + 右侧入口（主透镜下进入基准 / 历史，子页下返回）。 */
@Composable
private fun CultivationHeader(
    tab: CultivationTab,
    onBack: () -> Unit,
    onOpenBaseline: () -> Unit,
    onOpenHistory: () -> Unit,
    onBackToMirror: () -> Unit,
    showBaseline: Boolean = true,
) {
    val titleRes = when (tab) {
        CultivationTab.BASELINE -> R.string.cultivation_tab_baseline
        CultivationTab.HISTORY -> R.string.cultivation_history_title
        else -> R.string.cultivation_title
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
        }
        Text(
            stringResource(titleRes),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        when (tab) {
            CultivationTab.MIRROR, CultivationTab.EXEMPLAR -> {
                if (showBaseline) {
                    IconButton(onClick = onOpenBaseline) {
                        Icon(Icons.Filled.Person, contentDescription = stringResource(R.string.cultivation_tab_baseline))
                    }
                }
                IconButton(onClick = onOpenHistory) {
                    Icon(Icons.Filled.History, contentDescription = stringResource(R.string.cultivation_tab_history))
                }
            }
            else -> TextButton(onClick = onBackToMirror) {
                Text(stringResource(R.string.cultivation_dismiss))
            }
        }
    }
}

/** iOS 分段控件：浅灰轨道 + 选中段白色浮起，等分铺满。 */
@Composable
private fun IosSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(9.dp))
            .padding(3.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                Box(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
                        .clickable { onSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** inset grouped 分组容器：圆角卡片，内部用发丝线分区。 */
@Composable
private fun IosGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = ShapeTokens.mediumShape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            Modifier.padding(vertical = SpacingTokens.xs),
            content = content,
        )
    }
}

/** 分组内一行，统一 iOS 行内边距与最小触达高度。 */
@Composable
private fun IosRow(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(horizontal = SpacingTokens.lg, vertical = SpacingTokens.sm),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.xs),
        content = content,
    )
}

/** 发丝级分隔线，左侧留出与行文字对齐的缩进。 */
@Composable
private fun Hairline(startIndent: androidx.compose.ui.unit.Dp = SpacingTokens.lg) {
    Box(
        Modifier
            .padding(start = startIndent)
            .fillMaxWidth()
            .height(0.8.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/** 分组上方的灰色小节标题。 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        Modifier
            .fillMaxWidth()
            .padding(start = SpacingTokens.xs, end = SpacingTokens.xs, bottom = SpacingTokens.xs),
        style = MaterialTheme.typography.labelMedium,
        color = themeTextGrey(),
    )
}

/** 填充式、无描边输入框，统一圆角与底色。 */
@Composable
private fun FilledField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = label,
        singleLine = singleLine,
        minLines = minLines,
        shape = ShapeTokens.smallShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
            disabledBorderColor = Color.Transparent,
            focusedLabelColor = MaterialTheme.colorScheme.primary,
            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            cursorColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

/** 全宽主行动按钮：50dp 高、圆角 12、loading 时转菊花。 */
@Composable
private fun PrimaryButton(
    text: String,
    loadingText: String,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        shape = ShapeTokens.smallShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
        ),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.height(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.width(SpacingTokens.sm))
            Text(loadingText)
        } else {
            Text(text, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** 药丸式单选项：选中浅蓝底蓝字，未选白底发丝边灰字。 */
@Composable
private fun PillChoice(selected: Boolean, onClick: () -> Unit, label: String) {
    Box(
        Modifier
            .heightIn(min = 36.dp)
            .clip(ShapeTokens.pillShape)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            )
            .then(
                if (selected) Modifier
                else Modifier.border(
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ShapeTokens.pillShape,
                ),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = SpacingTokens.md, vertical = SpacingTokens.xs),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

/** 逐字引用块：左侧主色竖条 + 斜体原文。 */
@Composable
private fun QuoteBlock(quote: String) {
    if (quote.isBlank()) return
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .padding(top = SpacingTokens.xxs)
                .width(3.dp)
                .heightIn(min = 18.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), ShapeTokens.tagShape),
        )
        Spacer(Modifier.width(SpacingTokens.sm))
        Text(
            "“$quote”",
            fontStyle = FontStyle.Italic,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 状态路由小药丸（分析 / 支持 / 危机）。 */
@Composable
private fun RoutePill(route: MirrorRoute) {
    val (label, color) = when (route) {
        MirrorRoute.ANALYZE -> stringResource(R.string.cultivation_route_analyze) to MaterialTheme.colorScheme.primary
        MirrorRoute.SUPPORT -> stringResource(R.string.cultivation_route_support) to MaterialTheme.colorScheme.tertiary
        MirrorRoute.CRISIS -> stringResource(R.string.cultivation_route_crisis) to MaterialTheme.colorScheme.error
    }
    Box(
        Modifier
            .background(color.copy(alpha = 0.14f), ShapeTokens.pillShape)
            .padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xxs),
    ) {
        Text(label, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** 未达质量门 / 危机提示块。 */
@Composable
private fun NoticeCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = ShapeTokens.mediumShape,
        color = MaterialTheme.colorScheme.errorContainer,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.padding(SpacingTokens.lg), verticalArrangement = Arrangement.spacedBy(SpacingTokens.xs), content = content)
    }
}

/** 端侧模型未就绪引导：柔和主色底（区别于错误），讲清隐私承诺并直达加载入口。 */
@Composable
private fun LocalModelNeededCard(onManage: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = ShapeTokens.mediumShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 0.dp,
    ) {
        Column(
            Modifier.padding(SpacingTokens.lg),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
        ) {
            Text(
                stringResource(R.string.cultivation_local_needed_title),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                stringResource(R.string.cultivation_local_needed_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
            )
            Button(
                onClick = onManage,
                shape = ShapeTokens.smallShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = SpacingTokens.lg,
                    vertical = SpacingTokens.sm,
                ),
            ) {
                Text(stringResource(R.string.cultivation_local_manage), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ==================== 批判镜 ====================

private fun androidx.compose.foundation.lazy.LazyListScope.mirrorItems(
    manager: CultivationStateManager,
    state: CultivationStateManager.CultivationUiState,
    llmReady: Boolean,
    handoffSourceLabel: String? = null,
) {
    item {
        SectionLabel(stringResource(R.string.cultivation_lens_label))
        IosGroup {
            IosRow {
                IosSegmented(
                    options = listOf(stringResource(R.string.cultivation_lens_critique), stringResource(R.string.cultivation_lens_cognitive)),
                    selectedIndex = if (state.lens == ReflectionLens.COGNITIVE) 1 else 0,
                    onSelect = {
                        manager.setLens(if (it == 1) ReflectionLens.COGNITIVE else ReflectionLens.CRITIQUE)
                    },
                )
                if (state.lens == ReflectionLens.COGNITIVE) {
                    Text(
                        stringResource(R.string.cultivation_cognitive_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = themeTextGrey(),
                    )
                }
            }
        }
    }

    item {
        SectionLabel(stringResource(R.string.cultivation_backend_label))
        IosGroup {
            IosRow {
                IosSegmented(
                    options = listOf(
                        stringResource(R.string.cultivation_backend_local),
                        stringResource(R.string.cultivation_backend_cloud),
                    ),
                    selectedIndex = if (state.useCloud) 1 else 0,
                    onSelect = { manager.setUseCloud(it == 1) },
                )
                Text(
                    text = if (state.useCloud) {
                        stringResource(R.string.cultivation_cloud_warn)
                    } else {
                        stringResource(
                            R.string.cultivation_local_status,
                            state.localModelId ?: stringResource(R.string.cultivation_local_not_ready),
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = themeTextGrey(),
                )
            }
            Hairline()
            IosRow {
                Text(
                    stringResource(R.string.cultivation_mode_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                IosSegmented(
                    options = listOf(
                        stringResource(R.string.cultivation_mode_standard),
                        stringResource(R.string.cultivation_mode_gentle),
                    ),
                    selectedIndex = if (state.mode == FeedbackMode.GENTLE) 1 else 0,
                    onSelect = { manager.setMode(if (it == 1) FeedbackMode.GENTLE else FeedbackMode.STANDARD) },
                )
            }
        }
    }

    item {
        SectionLabel(stringResource(R.string.cultivation_transcript_hint))
        FilledField(
            value = state.transcript,
            onValueChange = manager::setTranscript,
            modifier = Modifier.heightIn(min = 200.dp),
            label = { Text(stringResource(R.string.cultivation_transcript_hint)) },
        )
        // 跨页移交来源的克制小字标注（仅展示来源，不触发任何分析）
        if (!handoffSourceLabel.isNullOrBlank()) {
            Spacer(Modifier.height(SpacingTokens.xs))
            Text(
                handoffSourceLabel,
                style = MaterialTheme.typography.bodySmall,
                color = themeTextGrey(),
            )
        }
    }

    item {
        PrimaryButton(
            text = stringResource(R.string.cultivation_start),
            loadingText = stringResource(R.string.cultivation_analyzing),
            loading = state.progressOn(state.lens),
            enabled = !state.isBusy && state.transcript.isNotBlank() &&
                (state.useCloud || llmReady),
            onClick = manager::analyze,
        )
    }

    state.blockedOn(state.lens)?.let { blocked ->
        item {
            NoticeCard {
                Text(stringResource(R.string.cultivation_below_bar), fontWeight = FontWeight.SemiBold)
                blocked.reasons.forEach { v -> Text("· $v", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }

    state.result?.let { record ->
        item { CritiqueReportCard(manager = manager, record = record) }
    }
}

@Composable
private fun CritiqueReportCard(manager: CultivationStateManager, record: ReflectionRecord) {
    val report = record.critique ?: return
    // 认知镜与言行镜共用本卡：仅在标签与参考名展示上区分，不做任何判定。
    val isCognitive = record.lens == ReflectionLens.COGNITIVE
    SectionLabel(stringResource(R.string.cultivation_title))
    IosGroup {
        IosRow {
            RoutePill(report.route)
            if (report.overall.isNotBlank()) {
                Text(report.overall, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            }
        }

        if (report.route == MirrorRoute.ANALYZE) {
            if (report.issues.isNotEmpty()) {
                Hairline()
                report.issues.forEachIndexed { index, issue ->
                    if (index > 0) Hairline()
                    IosRow {
                        QuoteBlock(issue.quote)
                        if (isCognitive && issue.referenceName.isNotBlank()) {
                            // 仅中性小字呈现参考偏差名，不渲染成标签/判定
                            Text(
                                stringResource(R.string.cultivation_cognitive_ref, issue.referenceName),
                                style = MaterialTheme.typography.bodySmall,
                                color = themeTextGrey(),
                            )
                        }
                        if (issue.baselineRef.isNotBlank()) {
                            Text(
                                stringResource(R.string.cultivation_ref, issue.baselineRef),
                                style = MaterialTheme.typography.bodySmall,
                                color = themeTextGrey(),
                            )
                        }
                        if (issue.impact.isNotBlank()) {
                            Text(issue.impact, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (issue.alternative.isNotBlank()) {
                            Text(
                                if (isCognitive) stringResource(R.string.cultivation_alternative_view, issue.alternative)
                                else stringResource(R.string.cultivation_alternative, issue.alternative),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (record.id > 0) {
                            Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.xs)) {
                                MarkPill(record.id, index, IssueMark.ACCEPTED, R.string.cultivation_mark_accept, record.marks[index], manager)
                                MarkPill(record.id, index, IssueMark.IMPROVED, R.string.cultivation_mark_improved, record.marks[index], manager)
                                MarkPill(record.id, index, IssueMark.DISAGREED, R.string.cultivation_mark_disagree, record.marks[index], manager)
                            }
                        }
                    }
                }
            }

            if (report.nextStep.isNotBlank()) {
                Hairline()
                IosRow {
                    Text(
                        stringResource(R.string.cultivation_next_step, report.nextStep),
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            if (report.strengths.isNotEmpty()) {
                Hairline()
                IosRow {
                    Text(stringResource(R.string.cultivation_strengths), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    report.strengths.forEach { s ->
                        Column(Modifier.padding(start = SpacingTokens.xs)) {
                            if (s.quote.isNotBlank()) QuoteBlock(s.quote)
                            if (s.trait.isNotBlank()) {
                                Text(s.trait, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            }
                            if (s.note.isNotBlank()) {
                                Text(s.note, style = MaterialTheme.typography.bodySmall, color = themeTextGrey())
                            }
                        }
                    }
                }
            }

            if (report.patterns.isNotEmpty()) {
                Hairline()
                IosRow {
                    Text(stringResource(R.string.cultivation_patterns), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    report.patterns.forEach { p ->
                        Text("· ${p.pattern}", style = MaterialTheme.typography.bodySmall, color = themeTextGrey())
                    }
                }
            }

            if (report.followUps.isNotEmpty()) {
                Hairline()
                IosRow {
                    Text(stringResource(R.string.cultivation_followups), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    report.followUps.forEach { f -> FollowUpRow(record.id, f, manager) }
                }
            }
        }

        if (report.support.isNotBlank()) {
            Hairline()
            IosRow { Text(report.support, color = MaterialTheme.colorScheme.onSurface) }
        }
        if (report.route == MirrorRoute.CRISIS && report.helpResources.isNotBlank()) {
            Hairline()
            IosRow {
                Surface(
                    Modifier.fillMaxWidth(),
                    shape = ShapeTokens.smallShape,
                    color = MaterialTheme.colorScheme.errorContainer,
                    tonalElevation = 0.dp,
                ) {
                    Text(report.helpResources, Modifier.padding(SpacingTokens.md), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun MarkPill(
    recordId: Long,
    index: Int,
    mark: IssueMark,
    labelRes: Int,
    current: IssueMark?,
    manager: CultivationStateManager,
) {
    PillChoice(
        selected = current == mark,
        onClick = { manager.markIssue(recordId, index, mark) },
        label = stringResource(labelRes),
    )
}

@Composable
private fun FollowUpRow(recordId: Long, follow: FollowUp, manager: CultivationStateManager) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.xs),
    ) {
        Text("· ${follow.text}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.xs)) {
            PillChoice(follow.status == FollowUpStatus.OPEN, { manager.setFollowUpStatus(recordId, follow.id, FollowUpStatus.OPEN) }, stringResource(R.string.cultivation_followup_open))
            PillChoice(follow.status == FollowUpStatus.DONE, { manager.setFollowUpStatus(recordId, follow.id, FollowUpStatus.DONE) }, stringResource(R.string.cultivation_followup_done))
            PillChoice(follow.status == FollowUpStatus.DROPPED, { manager.setFollowUpStatus(recordId, follow.id, FollowUpStatus.DROPPED) }, stringResource(R.string.cultivation_followup_drop))
        }
    }
}

// ==================== 人格画像（只读，模型自动维护） ====================

private fun androidx.compose.foundation.lazy.LazyListScope.personaItems(
    persona: PersonaProfile?,
) {
    item {
        Text(
            stringResource(R.string.cultivation_persona_intro),
            style = MaterialTheme.typography.bodySmall,
            color = themeTextGrey(),
        )
    }

    if (persona == null || persona.isEmpty()) {
        item {
            IosGroup {
                IosRow {
                    Text(
                        stringResource(R.string.cultivation_persona_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = themeTextGrey(),
                    )
                }
            }
        }
        return
    }

    item { PersonaSectionCard(stringResource(R.string.cultivation_persona_actual), persona.actualSelf) }
    item { PersonaSectionCard(stringResource(R.string.cultivation_persona_aspired), persona.aspiredSelf) }

    if (persona.openQuestions.isNotEmpty()) {
        item {
            IosGroup {
                IosRow {
                    Text(
                        stringResource(R.string.cultivation_persona_open),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                persona.openQuestions.forEach { q ->
                    Hairline(startIndent = 0.dp)
                    IosRow {
                        Text(
                            "· $q",
                            style = MaterialTheme.typography.bodySmall,
                            color = themeTextGrey(),
                        )
                    }
                }
            }
        }
    }
}

/** 画像中某一层（现实自我 / 理想自我）的只读卡片。 */
@Composable
private fun PersonaSectionCard(title: String, traits: List<PersonaTrait>) {
    IosGroup {
        IosRow {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        val active = traits.filter { it.status == PersonaTraitStatus.ACTIVE }
        if (active.isEmpty()) {
            Hairline(startIndent = 0.dp)
            IosRow {
                Text(
                    stringResource(R.string.cultivation_persona_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = themeTextGrey(),
                )
            }
        } else {
            active.forEach { t ->
                Hairline(startIndent = 0.dp)
                IosRow {
                    Column {
                        Text(
                            "· ${t.text}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (t.evidence.isNotBlank()) {
                            Text(
                                stringResource(R.string.cultivation_persona_evidence, t.evidence),
                                style = MaterialTheme.typography.bodySmall,
                                color = themeTextGrey(),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==================== 历史轨迹 ====================

private fun androidx.compose.foundation.lazy.LazyListScope.historyItems(
    manager: CultivationStateManager,
    history: List<ReflectionRecord>,
    onOpenGrowth: () -> Unit,
) {
    // 成长回顾入口
    item {
        IosGroup {
            IosRow {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenGrowth),
                ) {
                    Text(
                        stringResource(R.string.growth_review_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = themeTextDark(),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(R.string.growth_review_enter),
                        style = MaterialTheme.typography.bodySmall,
                        color = themeTextGrey(),
                    )
                }
            }
        }
    }

    // 行为维度长期趋势图（InsightsCard 之前）
    item {
        TrendCard(
            insights = TrendInsights.from(history),
            modifier = Modifier.padding(horizontal = SpacingTokens.lg, vertical = SpacingTokens.sm),
        )
    }

    item {
        if (history.isNotEmpty()) InsightsCard(ReflectionInsights.from(history))
    }
    if (history.isEmpty()) {
        item { Text(stringResource(R.string.cultivation_history_empty), color = themeTextGrey()) }
    }
    item {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.cultivation_history_title),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = manager::clearAll) { Text(stringResource(R.string.cultivation_clear_all)) }
        }
    }
    items(history, key = { it.id }) { record ->
        val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
        IosGroup {
            IosRow {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        fmt.format(Date(record.createdAt)),
                        style = MaterialTheme.typography.labelMedium,
                        color = themeTextGrey(),
                        modifier = Modifier.weight(1f),
                    )
                    RoutePill(record.route)
                    IconButton(onClick = { manager.deleteReport(record.id) }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.cultivation_delete_report))
                    }
                }
                val summary = record.critique?.overall?.take(160)
                    ?: record.exemplar?.let { e ->
                        stringResource(R.string.cultivation_exemplar_bracketed_name, e.exemplar) + e.takeaway.ifBlank { e.situation }.take(150)
                    }.orEmpty()
                Text(summary, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun InsightsCard(insights: ReflectionInsights) {
    IosGroup {
        IosRow {
            Text(stringResource(R.string.cultivation_insights_title), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(
                stringResource(
                    R.string.cultivation_insights_summary,
                    insights.totalSessions,
                    insights.activeDays,
                    (insights.followUpDoneRate * 100).toInt(),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = themeTextGrey(),
            )
        }
        if (insights.traitFreqs.isNotEmpty()) {
            Hairline()
            IosRow {
                Text(stringResource(R.string.cultivation_insights_traits), fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                insights.traitFreqs.forEach { t ->
                    Text("· ${t.trait} ×${t.count}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (insights.patternFreqs.isNotEmpty()) {
            Hairline()
            IosRow {
                Text(stringResource(R.string.cultivation_insights_patterns), fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                insights.patternFreqs.forEach { p ->
                    Text("· ${p.pattern} ×${p.count}", style = MaterialTheme.typography.bodySmall, color = themeTextGrey())
                }
            }
        }
    }
}

// ==================== 榜样镜 ====================

private fun androidx.compose.foundation.lazy.LazyListScope.exemplarItems(
    manager: CultivationStateManager,
    state: CultivationStateManager.CultivationUiState,
) {
    item {
        SectionLabel(stringResource(R.string.cultivation_tab_exemplar))
        IosGroup {
            IosRow {
                // 榜样名输入 + 添加按钮
                var inputName by remember { mutableStateOf("") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledField(
                        value = inputName,
                        onValueChange = { inputName = it },
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.cultivation_exemplar_add_hint)) },
                        singleLine = true,
                    )
                    Spacer(Modifier.width(SpacingTokens.sm))
                    TextButton(onClick = {
                        val name = inputName.trim()
                        if (name.isNotEmpty()) {
                            manager.addExemplar(name)
                            inputName = ""
                        }
                    }) { Text(stringResource(R.string.action_add)) }
                }

                // 已选榜样 chips
                if (state.exemplarNames.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
                        state.exemplarNames.forEach { name ->
                            AssistChip(
                                onClick = { manager.removeExemplar(name) },
                                label = { Text(name) },
                                colors = AssistChipDefaults.assistChipColors(),
                            )
                        }
                    }
                }

                // 建议榜样 chips
                Text(
                    stringResource(R.string.cultivation_exemplar_try),
                    style = MaterialTheme.typography.bodySmall,
                    color = themeTextGrey(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
                    listOf(
                        stringResource(R.string.cultivation_exemplar_suggestion_1),
                        stringResource(R.string.cultivation_exemplar_suggestion_2),
                        stringResource(R.string.cultivation_exemplar_suggestion_3),
                        stringResource(R.string.cultivation_exemplar_suggestion_4),
                        stringResource(R.string.cultivation_exemplar_suggestion_5),
                        stringResource(R.string.cultivation_exemplar_suggestion_6),
                        stringResource(R.string.cultivation_exemplar_suggestion_7),
                    ).forEach { suggestion ->
                        AssistChip(
                            onClick = { manager.addExemplar(suggestion) },
                            label = { Text(suggestion) },
                            colors = AssistChipDefaults.assistChipColors(),
                        )
                    }
                }
            }
            Hairline(startIndent = 0.dp)
            IosRow {
                FilledField(
                    value = state.exemplarWhy,
                    onValueChange = manager::setExemplarWhy,
                    label = { Text(stringResource(R.string.cultivation_exemplar_why_hint)) },
                    minLines = 2,
                )
            }
            Hairline(startIndent = 0.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .padding(horizontal = SpacingTokens.lg, vertical = SpacingTokens.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.cultivation_retain_exemplar),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(R.string.cultivation_exemplar_shared_transcript),
                        style = MaterialTheme.typography.bodySmall,
                        color = themeTextGrey(),
                    )
                }
                Switch(
                    checked = !state.retainExemplar,
                    onCheckedChange = { manager.setRetainExemplar(!it) },
                )
            }
        }
    }

    item {
        PrimaryButton(
            text = stringResource(R.string.cultivation_exemplar_start),
            loadingText = stringResource(R.string.cultivation_exemplar_analyzing),
            loading = state.progressOn(ReflectionLens.EXEMPLAR),
            enabled = !state.isBusy && state.transcript.isNotBlank() && state.exemplarNames.isNotEmpty(),
            onClick = manager::analyzeExemplar,
        )
    }

    state.blockedOn(ReflectionLens.EXEMPLAR)?.let { blocked ->
        item {
            NoticeCard {
                Text(stringResource(R.string.cultivation_below_bar), fontWeight = FontWeight.SemiBold)
                blocked.reasons.forEach { v -> Text("· $v", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }

    // 多榜样结果：统一声明 + 逐张渲染
    if (state.exemplarResults.isNotEmpty()) {
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = ShapeTokens.smallShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 0.dp,
            ) {
                Text(
                    stringResource(R.string.cultivation_exemplar_disclaimer),
                    modifier = Modifier.padding(SpacingTokens.md),
                    style = MaterialTheme.typography.bodySmall,
                    color = themeTextGrey(),
                )
            }
        }
        items(state.exemplarResults, key = { it.id }) { rec ->
            rec.exemplar?.let { report ->
                ExemplarReportCard(manager, report, rec.id)
            }
        }
    }
}

@Composable
private fun ExemplarReportCard(manager: CultivationStateManager, report: ExemplarReport, recordId: Long) {
    SectionLabel(stringResource(R.string.cultivation_tab_exemplar))
    IosGroup {
        if (report.situation.isNotBlank()) {
            IosRow {
                Text(report.situation, color = themeTextGrey(), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (report.actions.isNotEmpty()) {
            Hairline()
            IosRow {
                Text(stringResource(R.string.cultivation_exemplar_actions), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                report.actions.forEach { a ->
                    Column(Modifier.padding(start = SpacingTokens.xs)) {
                        Text("· ${a.action}", color = MaterialTheme.colorScheme.onSurface)
                        if (a.rationale.isNotBlank()) {
                            Text(a.rationale, style = MaterialTheme.typography.bodySmall, color = themeTextGrey())
                        }
                    }
                }
            }
        }
        if (report.highlights.isNotEmpty()) {
            Hairline()
            IosRow {
                Text(stringResource(R.string.cultivation_exemplar_highlights), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                report.highlights.forEach { h ->
                    Column(Modifier.padding(start = SpacingTokens.xs)) {
                        if (h.quote.isNotBlank()) QuoteBlock(h.quote)
                        if (h.point.isNotBlank()) {
                            Text(h.point, style = MaterialTheme.typography.bodySmall, color = themeTextGrey())
                        }
                    }
                }
            }
        }
        if (report.improvements.isNotEmpty()) {
            Hairline()
            IosRow {
                Text(stringResource(R.string.cultivation_exemplar_improvements), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                report.improvements.forEach { p ->
                    Column(Modifier.padding(start = SpacingTokens.xs)) {
                        if (p.observation.isNotBlank()) Text("· ${p.observation}", color = MaterialTheme.colorScheme.onSurface)
                        if (p.suggestion.isNotBlank()) {
                            Text(
                                stringResource(R.string.cultivation_alternative, p.suggestion),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
        if (report.takeaway.isNotBlank()) {
            Hairline()
            IosRow {
                Surface(
                    Modifier.fillMaxWidth(),
                    shape = ShapeTokens.smallShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 0.dp,
                ) {
                    Text(
                        stringResource(R.string.cultivation_exemplar_takeaway, report.takeaway),
                        Modifier.padding(SpacingTokens.md),
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        if (report.followUps.isNotEmpty()) {
            Hairline()
            IosRow {
                Text(stringResource(R.string.cultivation_followups), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                report.followUps.forEach { f -> FollowUpRow(recordId, f, manager) }
            }
        }
        if (report.support.isNotBlank()) {
            Hairline()
            IosRow { Text(report.support, color = MaterialTheme.colorScheme.onSurface) }
        }
        if (report.route == MirrorRoute.CRISIS && report.helpResources.isNotBlank()) {
            Hairline()
            IosRow {
                Surface(
                    Modifier.fillMaxWidth(),
                    shape = ShapeTokens.smallShape,
                    color = MaterialTheme.colorScheme.errorContainer,
                    tonalElevation = 0.dp,
                ) {
                    Text(report.helpResources, Modifier.padding(SpacingTokens.md), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
