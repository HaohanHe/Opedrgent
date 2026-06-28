# Tasks

- [x] Task 1: 建立统一反馈入口
  - 新建 `ui/components/FeedbackHost.kt` 或扩展现有 `SnackbarHostState`
  - 实现: `showFeedback(message, actionLabel?, action?)` 统一方法
  - 验证: 24 处 Toast 调用可逐一代换

- [x] Task 2: 替换 `ui/` 中的 Toast 为 Snackbar
  - 文件: `AppRoot.kt`, `InterviewScreen.kt`, `MainViewModel.kt`, `NoteEditorScreen.kt`, `NoteSproutScreen.kt`, `NoteShareScreen.kt`
  - 验证: `Toast.makeText` 在 `ui/` 包内归零

- [x] Task 3: 替换 `ui/components/` 中的 Toast 为 Snackbar
  - 文件: `ChatComponents.kt`, `NoteActionBottomSheet.kt`
  - 验证: `ui/components/` 包内无 Toast

- [x] Task 4: 为核心组件添加 `@Preview`
  - 文件: `UserBubble.kt`/`AiBubble.kt`（如已拆）、`EmptyStateView.kt`、`HoldToDictate.kt`、`SttResultCard.kt`
  - 验证: IDE 能渲染浅色/深色预览

- [x] Task 5: 升级 `EmptyStateView`
  - 文件: `ui/components/EmptyStateView.kt`
  - 实现: 支持插画、标题、描述、主按钮；适配深色模式
  - 验证: 在笔记列表、聊天未选会话、知识库空页中使用

- [x] Task 6: 为列表添加进入/退出/重排动画
  - 文件: `NoteListScreen.kt`, `KnowledgeBaseScreen.kt`, `SessionScreen.kt` 消息列表
  - 验证: 新增/删除/排序时有平滑动画

- [x] Task 7: 升级 `HoldToDictate` 反馈
  - 文件: `ui/components/HoldToDictate.kt`
  - 实现: 按下震动、环形进度、释放提示音/震动
  - 验证: 语音输入有明确按压态和结束反馈

- [x] Task 8: 升级流式输出光标动画
  - 文件: `ui/components/StreamingComponents.kt`
  - 实现: 脉冲闪烁的竖线光标，平滑追加文本
  - 验证: AI 流式回复时可见且不过度刺眼

# Task Dependencies
- Task 2 依赖 Task 1
- Task 3 依赖 Task 1
- Task 4-8 互相独立，可并行
- 本 spec 整体依赖 `cleanup-hardcoded-styles`（颜色/间距已收敛）
