# Tasks

- [x] Task 1: 统计并建立「硬编码样式清单」
  - 用 Grep 输出 `ui/` 与 `ui/components/` 中所有 `Color(0xFF...)`、`Color.Gray/White/Black`、裸 `dp`/`sp`、`contentDescription = null` 的位置
  - 验证: 得到带文件路径和行号的清单

- [x] Task 2: 替换 `ui/` 包内硬编码颜色
  - 文件: `ChatTab.kt`, `SessionScreen.kt`, `InterviewScreen.kt`, `NoteEditorScreen.kt`, `NoteSproutScreen.kt`, `HomeDashboardScreen.kt`, `KnowledgeBaseScreen.kt`, 等
  - 验证: 重新 grep 后，`ui/` 包内无业务硬编码颜色

- [x] Task 3: 替换 `ui/components/` 包内硬编码颜色
  - 文件: `ChatComponents.kt`, `MarkdownRenderer.kt`, `MessageBody*.kt`, `InputModeBar.kt`, `RecordingCard.kt`, `SttResultCard.kt`, 等
  - 验证: 重新 grep 后，`ui/components/` 包内无业务硬编码颜色

- [x] Task 4: 统一 `fontSize` / `lineHeight` / `fontWeight` 到 Typography token
  - 文件: 涉及直接写 `fontSize = xx.sp` / `fontWeight = xxx` 的 UI 文件
  - 验证: 搜索 `fontSize = [0-9]+\.sp` 仅出现在 `Type.kt`

- [x] Task 5: 统一间距与圆角到 token
  - 文件: 涉及 `Modifier.padding(6.dp)`、`RoundedCornerShape(11.dp)` 等
  - 验证: 搜索 `padding\([0-9]+\.dp\)` / `RoundedCornerShape\([0-9]+\.dp\)` 数量减少 80% 以上

- [x] Task 6: 补齐 Icon 的 `contentDescription`
  - 文件: 所有可交互 Icon
  - 验证: 搜索 `contentDescription = null` 仅保留在装饰性图标，且带注释说明

- [x] Task 7: 深色模式跑通核心流程
  - 在开发者选项强制深色，验证聊天、笔记列表、笔记编辑、发芽、面试、设置页无白底白字或黑底黑字
  - 验证: 截图/视觉检查通过

# Task Dependencies
- Task 2-6 均依赖 `lock-design-system` 完成（Shape/Spacing/Color token 就绪）
- Task 2 与 Task 3 可并行
- Task 4、Task 5、Task 6 可并行
- Task 7 依赖 Task 2-6
