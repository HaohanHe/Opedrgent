# 动效与反馈升级 Spec

## Why
当前 UI 缺少统一的动效与反馈机制：页面切换生硬、列表出现无动画、24 处 Toast 分散在 8 个文件、空状态简陋、语音输入 hold-to-talk 缺少震动/音效层级。这些问题让产品显得「不跟手」「不高级」。

## What Changes
- 把所有 `Toast.makeText` 收敛到统一的 Snackbar 反馈，支持 undo/重试。
- 为核心组件添加 `@Preview`（浅色/深色双模式）。
- 列表项添加 `animateItemPlacement` / `AnimatedVisibility` 进入退出动画。
- 空状态统一使用 `EmptyStateView`，并补充插画与行动按钮。
- 语音输入 Hold-to-Dictate 增加震动反馈、环形录音动画、释放提示。
- 聊天流式输出增加打字光标脉冲动画。

## Impact
- Affected code: `ui/MainViewModel.kt`（反馈入口）、`ui/components/HoldToDictate.kt`、`ui/components/StreamingComponents.kt`、`ui/components/EmptyStateView.kt`、各列表屏幕。
- New files: `ui/components/FeedbackHost.kt` 或类似统一反馈组件。

## ADDED Requirements
### Requirement: 统一 Snackbar 反馈
The system SHALL surface short-lived feedback via Snackbar instead of scattered Toast calls.

#### Scenario: 复制成功
- **WHEN** 用户长按复制一条消息
- **THEN** 底部弹出 Snackbar「已复制」，并显示撤销按钮

### Requirement: 列表动画
The system SHALL animate list items when they appear, disappear, or reorder.

#### Scenario: 删除一条笔记
- **WHEN** 用户删除笔记列表中的某一项
- **THEN** 该项以动画滑出，而不是瞬间消失

### Requirement: 语音输入反馈
The system SHALL provide haptic and visual feedback during hold-to-dictate.

#### Scenario: 按住说话
- **WHEN** 用户按住麦克风按钮
- **THEN** 按钮放大 + 环形进度动画 + 轻微震动；松开后给出成功/失败 Snackbar

## MODIFIED Requirements
### Requirement: 空状态展示
All empty screens SHALL use `EmptyStateView` with an illustration, headline, description, and optional action.

## REMOVED Requirements
### Requirement: 分散的 Toast 调用
**Reason**: Toast 无法 undo、样式不可控、与深色模式/品牌色不一致。
**Migration**: 用 SnackbarHost + 统一入口替换。
