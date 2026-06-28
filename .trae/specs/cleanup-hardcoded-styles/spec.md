# 清理硬编码样式 Spec

## Why
扫描发现 `ui/` 层存在 1368 处硬编码颜色、裸 `dp`/`sp` 或 `contentDescription = null`，`ui/components/` 也有 267 处。这些硬编码导致：深色模式失效、视觉不统一、维护困难、无障碍支持薄弱。

## What Changes
- 用设计系统 token 替换所有 `Color(0xFF...)`、`Color.Gray/White/Black` 等硬编码颜色。
- 用 `SpacingTokens` / `ShapeTokens` 替换裸 `Modifier.padding(...)`、`RoundedCornerShape(...)`。
- 用 `MaterialTheme.typography.xxx` 替换直接 `fontSize = xx.sp` + `fontWeight` 组合。
- 为所有可交互 Icon 补齐 `contentDescription`；装饰性图标显式标记并加注释。
- **不改动**业务逻辑和组件 API；只改样式实现。

## Impact
- Affected code: 约 57 个 `ui/` 文件 + 23 个 `ui/components/` 文件。
- Key files: `ChatTab.kt`, `SessionScreen.kt`, `NoteEditorScreen.kt`, `NoteSproutScreen.kt`, `ChatComponents.kt`, `MarkdownRenderer.kt`, `MessageBody*.kt`, `ModelSelectorDialog.kt` 等。

## ADDED Requirements
### Requirement: 无硬编码颜色
The system SHALL not use raw `Color(...)` values in UI code except for theme token definitions.

#### Scenario: 编译时检查
- **WHEN** 在 `ui/` 与 `ui/components/` 包内搜索 `Color\(0xFF` 与 `Color\.(Gray|Black|White)`
- **THEN** 结果仅出现在 `ui/theme/` 包内

### Requirement: 图标可访问性
The system SHALL provide meaningful content descriptions for all interactive icons.

#### Scenario: TalkBack 遍历
- **WHEN** 用户开启 TalkBack 并浏览会话页
- **THEN** 每个按钮/图标都有语音标签，无 "未加标签的按钮"

## MODIFIED Requirements
### Requirement: 统一使用主题 token
All UI code SHALL use `MaterialTheme.colorScheme`, `MaterialTheme.typography`, `SpacingTokens`, or `ShapeTokens` for styling.

## REMOVED Requirements
None.
