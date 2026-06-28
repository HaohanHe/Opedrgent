# Doubao 风格 UI 迁移 Spec

## Why
当前 app 的视觉风格偏灰暗、品牌感弱。豆包风格设计提供了更鲜亮的蓝色主色、纯白背景、更大圆角和更精致的阴影体系，能显著提升产品的现代感和友好度。

## What Changes
- 更新核心色板到豆包色值（primary `#0065fd`、background `#ffffff`、foreground `#0e1115` 等）
- 增大圆角 token（large 16→20dp、medium 12→16dp、small 8→12dp）
- 更新暗色模式色值为偏蓝黑体系（`#0e1115` 背景）
- 更新 MaterialTheme colorScheme 与 CustomColors 到新色值
- 重塑首页（HomeDashboardScreen）为豆包风格：问候语 + 搜索 + AI 助手卡 + 统计行 + 功能发现 grid + 快捷操作 + 最近笔记
- 重塑会话列表页（ChatTab/SessionsScreen）为纯卡片列表 + FAB
- 重塑设置页（SettingsScreen）为 iOS 风格分组列表 + inset divider
- 重塑笔记列表页（NoteListScreen）为卡片 + 类型 Pill 筛选
- 重塑知识库页（KnowledgeBaseScreen）为 2 列卡片网格 + 左彩色边框
- 重塑笔记编辑页（NoteEditorScreen）顶部元信息行 + Tab 切换 + 底部工具栏
- 聊天气泡更新为白色卡片 + 阴影风格（AI 侧）
- 不含录音界面（recording）迁移

## Impact
- Affected code: `ui/theme/Color.kt`, `ui/theme/Theme.kt`, `ui/theme/Shape.kt`, `ui/theme/CustomColors.kt`, `ui/HomeDashboardScreen.kt`, `ui/ChatTab.kt`, `ui/SettingsScreen.kt`, `ui/NoteListScreen.kt`, `ui/KnowledgeBaseScreen.kt`, `ui/NoteEditorScreen.kt`, `ui/components/UserBubble.kt`, `ui/components/AiBubble.kt`
- Token 层变更自动传播到所有使用主题 token 的组件

## ADDED Requirements

### Requirement: Doubao 色板
系统 SHALL 使用 `#0065fd` 作为 primary 色，`#ffffff` 作为浅色背景，`#0e1115` 作为浅色前景文字，`#f9f9fa` 作为次要背景。

#### Scenario: 浅色模式
- WHEN 应用运行在浅色模式
- THEN primary 为 `#0065fd`，背景为 `#ffffff`，卡片为 `#ffffff`，主文字为 `#0e1115`

#### Scenario: 深色模式
- WHEN 应用运行在深色模式
- THEN 背景为 `#0e1115`，卡片为 `#1a1e23`，主文字为 `#eff1f4`，primary 保持 `#0065fd`

### Requirement: 圆角体系
系统 SHALL 使用更大的圆角值：small=12dp、medium=16dp、large=20dp、extraLarge=28dp。

### Requirement: 首页布局
首页 SHALL 显示：顶部问候语 + 搜索栏 + AI 助手卡片（蓝色渐变 header）+ 三列统计行 + 2x2 功能发现 grid + 四列快捷操作 + 最近笔记卡片列表。

### Requirement: 设置页 iOS 风格
设置页 SHALL 使用分组卡片 + inset divider + 右侧 chevron 的 iOS 风格布局。
