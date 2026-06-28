# 聊天页 UI 重塑 Spec

## Why
当前聊天页视觉还停留在基础消息气泡阶段：气泡最大宽度写死 280dp、AI 无头像/无标识、用户气泡硬编码蓝渐变、长对话难以区分说话人；引用、代码块、思考过程缺少视觉层级。重塑后可显著提升「高级感」和可读性。

## What Changes
- 消息气泡宽度改为屏幕比例（~75%），适配手机/平板/折叠屏。
- 为 AI 消息增加头像/标识与名字，用户消息保持右对齐但使用品牌色。
- 拆分 `ChatComponents.kt`：独立 `UserBubble`、`AiBubble`、`MessageHeader`、`CitationPill`、`CodeBlock`、`ThinkingCard`。
- 在 MarkdownRenderer 中给引用、代码块、加粗、列表加视觉层级。
- 为流式输出/思考过程增加「思考中」卡片与打字光标。
- 统一空状态：未选会话时使用 `EmptyStateView`。

## Impact
- Affected code: `ui/ChatTab.kt`, `ui/SessionScreen.kt`, `ui/components/ChatComponents.kt`, `ui/components/MarkdownRenderer.kt`, `ui/components/MessageBody*.kt`。
- New files: `ui/components/UserBubble.kt`, `ui/components/AiBubble.kt`, `ui/components/ThinkingCard.kt`, `ui/components/CitationPill.kt`, `ui/components/CodeBlock.kt`。

## ADDED Requirements
### Requirement: 气泡自适应宽度
The system SHALL render message bubbles with a max width relative to screen width instead of a fixed 280dp.

#### Scenario: 平板横屏
- **WHEN** 用户在 10 寸平板上打开会话
- **THEN** 气泡占据约 75% 屏幕宽度，两侧不再留巨大白边

### Requirement: AI 消息有清晰身份
The system SHALL show an AI avatar and name above each AI message.

#### Scenario: 长对话
- **WHEN** 用户滚动查看多轮对话
- **THEN** 能一眼区分哪些是 AI 回复、哪些是用户发送

### Requirement: 富文本消息分层渲染
The system SHALL render quotes, code blocks, inline code, and lists with distinct background/shape/typography.

#### Scenario: AI 回复含代码
- **WHEN** AI 返回包含代码块的 Markdown
- **THEN** 代码块显示为独立卡片，带复制按钮和等宽字体

## MODIFIED Requirements
### Requirement: 消息气泡样式
User and AI bubbles SHALL use theme-aware colors instead of hardcoded gradients and `Color.White`.

## REMOVED Requirements
### Requirement: 固定 280dp 气泡宽度
**Reason**: 在大屏设备上体验极差。
**Migration**: 改为 `fillMaxWidth(fraction = 0.75f)` 或基于窗口大小类别的自适应值。
