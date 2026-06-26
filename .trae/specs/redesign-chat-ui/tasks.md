# Tasks

- [x] Task 1: 拆分 `ChatComponents.kt` 为独立文件
  - 新建 `UserBubble.kt`、`AiBubble.kt`、`MessageHeader.kt`
  - 验证: 原 `ChatComponents.kt` 行数下降，编译通过

- [x] Task 2: 实现自适应气泡宽度
  - 文件: `UserBubble.kt`、`AiBubble.kt`
  - 实现: 使用 `fillMaxWidth(fraction = 0.75f)` 或窗口大小类别
  - 验证: 手机/平板/折叠屏上气泡宽度合理

- [x] Task 3: 为 AI 消息添加头像与名字
  - 文件: `AiBubble.kt` / `MessageHeader.kt`
  - 验证: 每条 AI 消息顶部显示头像 + 名字，用户消息无头像

- [x] Task 4: 新增 `ThinkingCard` 组件
  - 文件: `ui/components/ThinkingCard.kt`
  - 实现: 展示思考过程，可展开/折叠，带渐变闪烁指示器
  - 验证: 面试/聊天中的 thinking 状态使用该卡片

- [x] Task 5: 新增 `CitationPill` 与 `CodeBlock` 组件
  - 文件: `ui/components/CitationPill.kt`, `ui/components/CodeBlock.kt`
  - 验证: Markdown 中的引用和代码块按新样式渲染，代码块带复制

- [x] Task 6: 升级 `MarkdownRenderer` 的视觉层级
  - 文件: `ui/components/MarkdownRenderer.kt`
  - 验证: 标题、列表、引用、代码块、行内代码有区分度

- [x] Task 7: 替换 `ChatTab` 空状态为 `EmptyStateView`
  - 文件: `ui/ChatTab.kt`
  - 验证: 横屏未选会话时显示插画 + 提示 + 新建按钮

- [x] Task 8: 为消息列表添加进入/退出动画
  - 文件: `SessionScreen.kt` 消息列表部分
  - 验证: 新消息滑入，删除消息滑出

# Task Dependencies
- Task 2-6 依赖 Task 1
- Task 8 依赖 Task 1
- Task 7 可独立执行
- 本 spec 整体依赖 `cleanup-hardcoded-styles`（颜色/间距/圆角已收敛）
