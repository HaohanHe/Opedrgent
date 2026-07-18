package top.hsyscn.opedrgent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.note.FolderRepository
import top.hsyscn.opedrgent.note.Note
import top.hsyscn.opedrgent.note.NoteRepository
import top.hsyscn.opedrgent.ui.components.isAtLeastMediumWidth
import top.hsyscn.opedrgent.ui.components.isExpandedWidth
import top.hsyscn.opedrgent.ui.theme.*
import top.hsyscn.opedrgent.utils.UserDisplayNameHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val noteTimeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

/**
 * 首页仪表盘（Doubao 风格重设计）。
 *
 * 布局：
 * - 透明 TopBar + 可滚动内容
 * - 问候语 + 头像
 * - 搜索栏
 * - AI 助手卡片
 * - 统计行（3 列）
 * - 功能发现网格（2x2）
 * - 快捷操作行
 * - 最近笔记列表
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeDashboardScreen(
    vm: MainViewModel,
    repository: NoteRepository,
    folderRepository: FolderRepository,
    onNewNote: () -> Unit,
    onNoteClick: (Long) -> Unit,
    onSendToChat: (Long) -> Unit = {},
    onSendWithSkill: (Long, String) -> Unit = { _, _ -> },
    onOpenSubScreen: (String) -> Unit = {},
    onNavigateToAi: () -> Unit,
    onNavigateToNotes: () -> Unit,
    onOpenEditorTeam: () -> Unit = {},
    onNavigateToRecording: () -> Unit = {},
    onNavigateToKnowledge: () -> Unit = {},
    onNavigateToInterview: () -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
) {
    var recentNotes by remember { mutableStateOf<List<Note>>(emptyList()) }
    var todayCount by remember { mutableStateOf(0) }
    var kbCount by remember { mutableStateOf(0) }
    val aiCount = vm.sessionCount

    LaunchedEffect(Unit) {
        launch { recentNotes = repository.getRecentNotes(3) }
        launch { kbCount = vm.kbDocumentCount }
    }

    LaunchedEffect(repository) {
        repository.getAllNotes().collect { notes ->
            recentNotes = notes.take(3)
        }
    }

    LaunchedEffect(vm) {
        vm.todayNoteCount.collect { todayCount = it }
    }

    Scaffold(
        containerColor = themeBackgroundSecondary(),
    ) { innerPadding ->
        // 响应式布局：Expanded 宽度使用双栏，其余宽度单列并限制最大宽度
        val contentMaxWidth = when {
            isExpandedWidth() -> 1200.dp
            isAtLeastMediumWidth() -> 840.dp
            else -> Dp.Unspecified
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (isExpandedWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = contentMaxWidth)
                        .padding(horizontal = SizeTokens.screenHorizontalPadding),
                    horizontalArrangement = Arrangement.spacedBy(SpacingTokens.xl),
                ) {
                    // 左栏：核心入口（问候、搜索、AI 助手、统计）
                    LazyColumn(
                        modifier = Modifier
                            .weight(0.55f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(SpacingTokens.lg),
                    ) {
                        item { GreetingHeader(onAvatarClick = { /* TODO: profile */ }) }
                        item { SearchBar(onClick = onNavigateToSearch) }
                        item { AiAssistantCard(onTap = onNavigateToAi) }
                        item {
                            StatsRow(
                                todayCount = todayCount,
                                kbCount = kbCount,
                                aiCount = aiCount,
                                onNotesClick = onNavigateToNotes,
                                onKnowledgeClick = onNavigateToKnowledge,
                                onAiClick = onNavigateToAi,
                            )
                        }
                        item { Spacer(modifier = Modifier.height(SpacingTokens.xl)) }
                    }

                    // 右栏：功能发现、快捷操作、最近笔记
                    LazyColumn(
                        modifier = Modifier
                            .weight(0.45f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(SpacingTokens.lg),
                    ) {
                        item {
                            FeatureDiscoveryGrid(
                                onInterview = onNavigateToInterview,
                                onEditorTeam = onOpenEditorTeam,
                                onVoiceNotes = onNavigateToRecording,
                                onSprout = onNavigateToNotes,
                            )
                        }
                        item {
                            QuickActionsRow(
                                onNewNote = onNewNote,
                                onVoiceRecord = onNavigateToRecording,
                                onImportFile = { onOpenSubScreen("import") },
                                onInsight = onNavigateToKnowledge,
                            )
                        }
                        item {
                            RecentNotesSection(
                                notes = recentNotes,
                                onNoteClick = onNoteClick,
                                onViewAll = onNavigateToNotes,
                            )
                        }
                        item { Spacer(modifier = Modifier.height(SpacingTokens.xl)) }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (contentMaxWidth != Dp.Unspecified) {
                                Modifier.widthIn(max = contentMaxWidth)
                            } else {
                                Modifier
                            }
                        )
                        .padding(horizontal = SizeTokens.screenHorizontalPadding),
                    verticalArrangement = Arrangement.spacedBy(SpacingTokens.lg),
                ) {
                    item { GreetingHeader(onAvatarClick = { /* TODO: profile */ }) }
                    item { SearchBar(onClick = onNavigateToSearch) }
                    item { AiAssistantCard(onTap = onNavigateToAi) }
                    item {
                        StatsRow(
                            todayCount = todayCount,
                            kbCount = kbCount,
                            aiCount = aiCount,
                            onNotesClick = onNavigateToNotes,
                            onKnowledgeClick = onNavigateToKnowledge,
                            onAiClick = onNavigateToAi,
                        )
                    }
                    item {
                        FeatureDiscoveryGrid(
                            onInterview = onNavigateToInterview,
                            onEditorTeam = onOpenEditorTeam,
                            onVoiceNotes = onNavigateToRecording,
                            onSprout = onNavigateToNotes,
                        )
                    }
                    item {
                        QuickActionsRow(
                            onNewNote = onNewNote,
                            onVoiceRecord = onNavigateToRecording,
                            onImportFile = { onOpenSubScreen("import") },
                            onInsight = onNavigateToKnowledge,
                        )
                    }
                    item {
                        RecentNotesSection(
                            notes = recentNotes,
                            onNoteClick = onNoteClick,
                            onViewAll = onNavigateToNotes,
                        )
                    }
                    item { Spacer(modifier = Modifier.height(SpacingTokens.xl)) }
                }
            }
        }
    }
}

// ==================== Sections ====================

/** 顶部问候语 + 头像 */
@Composable
private fun GreetingHeader(onAvatarClick: () -> Unit) {
    val context = LocalContext.current
    val greeting = rememberGreeting()
    val defaultName = stringResource(R.string.home_tan_suo_zhe)
    val displayName = remember(defaultName) { UserDisplayNameHelper.getDisplayName(context) ?: defaultName }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 0.dp, bottom = SpacingTokens.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column {
            Text(
                text = greeting,
                style = MaterialTheme.typography.headlineLarge,
                color = themeForeground(),
            )
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = themeForegroundMuted(),
                modifier = Modifier.padding(top = SpacingTokens.xxs),
            )
        }

        Box(
            modifier = Modifier
                .size(SpacingTokens.xxl)
                .clip(CircleShape)
                .background(themePrimarySubtle())
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.cd_settings), onClick = onAvatarClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = stringResource(R.string.home_yong_hu_tou_xiang),
                tint = themePrimary(),
                modifier = Modifier.size(SizeTokens.iconMd),
            )
        }
    }
}

/** 圆角搜索栏 */
@Composable
private fun SearchBar(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(percent = 50))
            .background(themeBackgroundMuted())
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.action_search), onClick = onClick)
            .padding(horizontal = SpacingTokens.lg, vertical = SizeTokens.sectionGapSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SizeTokens.sectionGapSm),
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = themeForegroundMuted(),
            modifier = Modifier.size(SizeTokens.iconMd),
        )
        Text(
            text = stringResource(R.string.home_sou_suo_bi_ji_dui_hua_zhi_shi),
            style = MaterialTheme.typography.bodyMedium,
            color = themeForegroundMuted(),
        )
    }
}

/** AI 助手卡片 */
@Composable
private fun AiAssistantCard(onTap: () -> Unit) {
    Card(
        shape = ShapeTokens.largeShape,
        colors = CardDefaults.cardColors(containerColor = themeCardWhite()),
        elevation = CardDefaults.cardElevation(defaultElevation = ElevationTokens.sm),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            // Header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(themePrimary(), themePrimaryRing()),
                        )
                    )
                    .padding(horizontal = SizeTokens.screenHorizontalPadding, vertical = SpacingTokens.md),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(SizeTokens.iconLg),
                    )
                    Text(
                        text = stringResource(R.string.home_ai_zhi_neng_zhu_shou),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }

            // Body
            Column(
                modifier = Modifier.padding(SizeTokens.screenHorizontalPadding),
            ) {
                Text(
                    text = stringResource(R.string.home_wo_shi_ni_de_ai_zhu_shou_ke),
                    style = MaterialTheme.typography.bodyMedium,
                    color = themeForegroundSecondary(),
                    modifier = Modifier.padding(bottom = SpacingTokens.lg),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(percent = 50))
                        .background(themeBackgroundMuted())
                        .clickable(role = Role.Button, onClickLabel = stringResource(R.string.action_start), onClick = onTap)
                        .padding(start = SpacingTokens.lg, end = SpacingTokens.md, top = SizeTokens.sectionGapSm, bottom = SizeTokens.sectionGapSm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
                ) {
                    Text(
                        text = stringResource(R.string.home_shu_ru_ni_de_wen_ti),
                        style = MaterialTheme.typography.bodyMedium,
                        color = themeForegroundMuted(),
                        modifier = Modifier.weight(1f),
                    )

                    Box(
                        modifier = Modifier
                            .size(SpacingTokens.xxl)
                            .clip(CircleShape)
                            .background(themePrimary())
                            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.action_start), onClick = onTap),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.action_send),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(SpacingTokens.lg),
                        )
                    }
                }
            }
        }
    }
}

/** 统计行（3 列） */
@Composable
private fun StatsRow(
    todayCount: Int,
    kbCount: Int,
    aiCount: Int,
    onNotesClick: () -> Unit,
    onKnowledgeClick: () -> Unit,
    onAiClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
    ) {
        StatCard(
            title = stringResource(R.string.home_jin_ri_xin_zeng),
            value = todayCount.toString(),
            icon = Icons.Default.Add,
            iconBg = themePrimarySubtle(),
            iconTint = themePrimary(),
            onClick = onNotesClick,
            modifier = Modifier.weight(1f),
        )
        StatCard(
            title = stringResource(R.string.cd_knowledge_base),
            value = kbCount.toString(),
            icon = Icons.Default.Book,
            iconBg = themeChipSuccessBg(),
            iconTint = themeSuccess(),
            onClick = onKnowledgeClick,
            modifier = Modifier.weight(1f),
        )
        StatCard(
            title = stringResource(R.string.home_ai_dui_hua),
            value = aiCount.toString(),
            icon = Icons.Default.ChatBubble,
            iconBg = themeWarningBg(),
            iconTint = themeWarning(),
            onClick = onAiClick,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatCard(
    title: String,
    value: String,
    icon: ImageVector,
    iconBg: Color,
    iconTint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        shape = ShapeTokens.mediumShape,
        colors = CardDefaults.cardColors(containerColor = themeCardWhite()),
        elevation = CardDefaults.cardElevation(defaultElevation = ElevationTokens.sm),
        modifier = modifier.clickable(role = Role.Button, onClickLabel = stringResource(R.string.cd_enter), onClick = onClick),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SizeTokens.contentPaddingMd, vertical = SpacingTokens.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
        ) {
            Box(
                modifier = Modifier
                    .size(SizeTokens.iconXl)
                    .clip(CircleShape)
                    .background(iconBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(SizeTokens.iconXs),
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = themeForegroundMuted(),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.displaySmall,
                color = themeForeground(),
            )
        }
    }
}

/** 功能发现 2x2 网格 */
@Composable
private fun FeatureDiscoveryGrid(
    onInterview: () -> Unit,
    onEditorTeam: () -> Unit,
    onVoiceNotes: () -> Unit,
    onSprout: () -> Unit,
) {
    Column {
        Text(
            text = stringResource(R.string.home_fa_xian_xin_gong_neng),
            style = MaterialTheme.typography.titleMedium,
            color = themeForeground(),
            modifier = Modifier
                .padding(bottom = SpacingTokens.md)
                .semantics { heading() },
        )

        Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.md)) {
            Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.md)) {
                FeatureCard(
                    title = stringResource(R.string.title_interview_mode),
                    subtitle = stringResource(R.string.home_ai_qu_dong_zhi_neng_mian_shi),
                    icon = Icons.Default.Mic,
                    gradient = themeGradientInterview(),
                    onClick = onInterview,
                    modifier = Modifier.weight(1f),
                )
                FeatureCard(
                    title = stringResource(R.string.home_bian_ji_tuan_dui),
                    subtitle = stringResource(R.string.home_duo_jue_se_xie_tong_bian_ji),
                    icon = Icons.Default.Groups,
                    gradient = themeGradientEditor(),
                    onClick = onEditorTeam,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.md)) {
                FeatureCard(
                    title = stringResource(R.string.home_yu_yin_bi_ji),
                    subtitle = stringResource(R.string.home_yu_yin_zhuai_wen_zi_ji_lu),
                    icon = Icons.Default.Mic,
                    gradient = themeGradientVoice(),
                    onClick = onVoiceNotes,
                    modifier = Modifier.weight(1f),
                )
                FeatureCard(
                    title = stringResource(R.string.sprout_title),
                    subtitle = stringResource(R.string.home_fa_xian_zhi_shi_guan_lian),
                    icon = Icons.Default.Spa,
                    gradient = themeGradientSprout(),
                    onClick = onSprout,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun FeatureCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    gradient: List<Color>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        shape = ShapeTokens.mediumShape,
        colors = CardDefaults.cardColors(containerColor = themeCardWhite()),
        elevation = CardDefaults.cardElevation(defaultElevation = ElevationTokens.sm),
        modifier = modifier.clickable(role = Role.Button, onClickLabel = stringResource(R.string.cd_enter), onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SizeTokens.contentPaddingMd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.md),
        ) {
            Box(
                modifier = Modifier
                    .size(SizeTokens.featureIconBg)
                    .clip(ShapeTokens.iconShape)
                    .background(Brush.linearGradient(gradient)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(SizeTokens.iconLg),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = themeForeground(),
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = themeForegroundMuted(),
                )
            }
        }
    }
}

/** 快捷操作行 */
@Composable
private fun QuickActionsRow(
    onNewNote: () -> Unit,
    onVoiceRecord: () -> Unit,
    onImportFile: () -> Unit,
    onInsight: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.xl, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QuickActionItem(
            icon = Icons.Default.EditNote,
            iconTint = themePrimary(),
            label = stringResource(R.string.home_xin_bi_ji),
            onClick = onNewNote,
        )
        QuickActionItem(
            icon = Icons.Default.RadioButtonChecked,
            iconTint = themeError(),
            label = stringResource(R.string.home_lu_yin),
            onClick = onVoiceRecord,
        )
        QuickActionItem(
            icon = Icons.Default.Upload,
            iconTint = themeSuccess(),
            label = stringResource(R.string.home_dao_ru),
            onClick = onImportFile,
        )
        QuickActionItem(
            icon = Icons.Default.Lightbulb,
            iconTint = themePrimaryRing(),
            label = stringResource(R.string.home_dong_cha),
            onClick = onInsight,
        )
    }
}

@Composable
private fun QuickActionItem(
    icon: ImageVector,
    iconTint: Color,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(SizeTokens.quickActionIcon)
                .clip(CircleShape)
                .background(themeBackgroundMuted()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = iconTint,
                modifier = Modifier.size(SizeTokens.iconLg),
            )
        }
        Spacer(modifier = Modifier.height(SizeTokens.compactSpacing))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = themeForegroundMuted(),
        )
    }
}

/** 最近笔记区域 */
@Composable
private fun RecentNotesSection(
    notes: List<Note>,
    onNoteClick: (Long) -> Unit,
    onViewAll: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.home_zui_jin_bi_ji),
                style = MaterialTheme.typography.titleMedium,
                color = themeForeground(),
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.msg_body_view_all),
                style = MaterialTheme.typography.bodySmall,
                color = themePrimary(),
                modifier = Modifier.clickable(role = Role.Button, onClickLabel = stringResource(R.string.cd_enter), onClick = onViewAll),
            )
        }

        Spacer(modifier = Modifier.height(SpacingTokens.md))

        Card(
            shape = ShapeTokens.mediumShape,
            colors = CardDefaults.cardColors(containerColor = themeCardWhite()),
            elevation = CardDefaults.cardElevation(defaultElevation = ElevationTokens.sm),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (notes.isEmpty()) {
                EmptyRecentNotes(onNavigateToAi = {})
            } else {
                notes.forEachIndexed { index, note ->
                    RecentNoteRow(
                        note = note,
                        dotColor = when (index % 3) {
                            0 -> themePrimary()
                            1 -> themeSuccess()
                            else -> themeWarning()
                        },
                        onClick = { onNoteClick(note.id) },
                    )
                    if (index < notes.lastIndex) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = SpacingTokens.lg)
                                .height(SizeTokens.dividerThickness)
                                .background(themeBorder()),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentNoteRow(
    note: Note,
    dotColor: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.cd_enter), onClick = onClick)
            .padding(horizontal = SpacingTokens.lg, vertical = SizeTokens.contentPaddingMd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SizeTokens.sectionGapSm),
    ) {
        Box(
            modifier = Modifier
                .size(SpacingTokens.sm)
                .clip(CircleShape)
                .background(dotColor),
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = note.title.ifBlank { stringResource(R.string.note_editor_title_placeholder) },
                    style = MaterialTheme.typography.titleSmall,
                    color = themeForeground(),
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(SpacingTokens.sm))
                Text(
                    text = formatNoteTime(note.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = themeForegroundMuted(),
                )
            }
            if (note.summary.isNotEmpty()) {
                Text(
                    text = note.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = themeForegroundMuted(),
                    maxLines = 1,
                    modifier = Modifier.padding(top = SizeTokens.progressTrackHeight),
                )
            }
        }
    }
}

/** 空最近笔记状态 */
@Composable
private fun EmptyRecentNotes(
    onNavigateToAi: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = SpacingTokens.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.home_hai_mei_you_bi_ji),
            style = MaterialTheme.typography.titleMedium,
            color = themeForegroundMuted(),
        )
        Spacer(modifier = Modifier.height(SpacingTokens.sm))
        Text(
            text = stringResource(R.string.home_dian_ji_xia_fang_kuai_jie_cao),
            style = MaterialTheme.typography.bodyMedium,
            color = themeForegroundMuted(),
        )
        Spacer(modifier = Modifier.height(SpacingTokens.lg))

        Card(
            shape = ShapeTokens.mediumShape,
            colors = CardDefaults.cardColors(containerColor = themePrimarySubtle()),
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.action_start)) { onNavigateToAi() },
        ) {
            Row(
                modifier = Modifier.padding(SpacingTokens.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(SizeTokens.featureIconBg)
                        .clip(CircleShape)
                        .background(themePrimary().copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.ChatBubble,
                        contentDescription = null,
                        tint = themePrimary(),
                        modifier = Modifier.size(SizeTokens.iconLg),
                    )
                }
                Spacer(modifier = Modifier.width(SpacingTokens.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.home_shi_shi_ai_zhi_neng_zhu_shou),
                        style = MaterialTheme.typography.labelLarge,
                        color = themeForeground(),
                    )
                    Text(
                        text = stringResource(R.string.home_shu_ru_xiang_fa_rang_ai_bang),
                        style = MaterialTheme.typography.bodySmall,
                        color = themeForegroundMuted(),
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = themePrimary(),
                    modifier = Modifier.size(SizeTokens.iconLg),
                )
            }
        }
    }
}

// ==================== Helpers ====================

/** 根据时间返回问候语 */
@Composable
private fun rememberGreeting(): String {
    val hour = remember {
        val cal = java.util.Calendar.getInstance()
        cal.get(java.util.Calendar.HOUR_OF_DAY)
    }
    return when {
        hour < 6 -> stringResource(R.string.home_ye_shen_le)
        hour < 12 -> stringResource(R.string.home_zao_shang_hao)
        hour < 14 -> stringResource(R.string.home_zhong_wu_hao)
        hour < 18 -> stringResource(R.string.home_xia_wu_hao)
        else -> stringResource(R.string.home_wan_shang_hao)
    }
}

/** 格式化笔记时间显示 */
internal fun formatNoteTime(timestamp: Long): String {
    return noteTimeFormat.format(Date(timestamp))
}
