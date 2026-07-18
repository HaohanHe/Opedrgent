package top.hsyscn.opedrgent.ui.theme

import androidx.compose.ui.unit.dp

/**
 * 统一尺寸 token（图标、组件尺寸）。
 */
object SizeTokens {
    // 图标尺寸
    val iconXs = 14.dp
    val iconSm = 16.dp
    val iconMd = 18.dp
    val iconLg = 20.dp
    val iconXl = 28.dp

    // 组件尺寸
    val sectionIcon = 48.dp
    val settingRowHeight = 52.dp
    val settingDividerInset = 56.dp
    val featureIconBg = 40.dp
    val quickActionIcon = 44.dp
    val searchBarHeight = 44.dp
    val emptyStateIcon = 64.dp
    val emptyStateIconBoxHeight = 120.dp
    val fabSize = 56.dp
    val folderItemHeight = 60.dp
    val folderGridMaxHeight = 120.dp
    val previewPanelWidth = 400.dp
    val settingsDrawerWidth = 220.dp
    val textFieldWidthSm = 100.dp

    // 对话框/弹窗尺寸
    val dialogMinWidth = 300.dp

    // 输入框尺寸
    val textFieldMinHeight = 48.dp
    val textFieldMaxHeight = 120.dp

    // 展开内容区最大高度
    val expandedContentMaxHeight = 400.dp

    // 响应式内容最大宽度
    val expandedContentMaxWidth = 1200.dp
    val mediumContentMaxWidth = 840.dp
    val sessionContentMaxWidthExpanded = 900.dp
    val sessionContentMaxWidthMedium = 760.dp
    val interviewContentMaxWidthExpanded = 840.dp
    val interviewContentMaxWidthMedium = 640.dp
    val interviewTranscriptMaxWidthExpanded = 900.dp
    val interviewTranscriptMaxWidthMedium = 720.dp
    val interviewResultMaxWidthExpanded = 900.dp
    val interviewResultMaxWidthMedium = 720.dp
    val noteEditorMaxWidthExpanded = 980.dp
    val noteEditorMaxWidthMedium = 760.dp
    val chatSessionListWidth = 320.dp

    // 列表最大高度（用于内嵌可滚动区域，避免占满整屏）
    val citationListMaxHeight = 320.dp

    // 知识图谱节点尺寸
    val graphNodeBaseRadius = 5.dp
    val graphNodeMaxExtraRadius = 3.dp

    // 紧凑间距/微调
    val compactSpacing = 6.dp
    val sectionGapSm = 10.dp
    val contentPaddingMd = 14.dp
    val screenHorizontalPadding = 20.dp

    // 厚度
    val dividerThickness = 1.dp
    val thinDividerThickness = 0.5.dp
    val borderWidth = 1.dp
    val borderWidthMd = 1.5.dp
    val borderWidthLg = 2.dp
    val progressTrackHeight = 3.dp

    // 徽章
    val badgeHorizontalPadding = 5.dp
    val badgeVerticalPadding = 1.dp

    // 按钮内边距
    val buttonHorizontalMd = 14.dp
    val buttonHorizontalSm = 10.dp
}

/**
 * 统一阴影 token。
 */
object ElevationTokens {
    val none = 0.dp
    val sm = 1.dp
    val md = 3.dp
    val lg = 6.dp
    val xl = 8.dp
}
