# Tasks

- [ ] Task 1: 创建 HippocampusIndex 核心类
  - [ ] SubTask 1.1: 定义 `IndexedItem` 数据类（id, sourceType, sourceId, title, summary, keywords, createdAt, updatedAt）
  - [ ] SubTask 1.2: 实现 `HippocampusIndex` 类，基于 SQLite（复用 App 的数据库模式）
  - [ ] SubTask 1.3: 实现 `add/update/delete/query` 方法
  - [ ] SubTask 1.4: 实现 `query(keyword, limit)` 搜索方法（标题+摘要模糊匹配）

- [ ] Task 2: 各内容源接入自动索引
  - [ ] SubTask 2.1: NoteRepository — 笔记 CRUD 后调用 `hippocampus.upsertNote()`
  - [ ] SubTask 2.2: MainViewModel — AI 对话完成后调用 `hippocampus.upsertConversation()`
  - [ ] SubTask 2.3: RecordingTab — 录音转写完成后调用 `hippocampus.upsertRecording()`
  - [ ] SubTask 2.4: SproutService — 发芽报告生成后调用 `hippocampus.upsertSprout()`

- [ ] Task 3: SproutService 集成全局上下文
  - [ ] SubTask 3.1: SproutService 构造函数接收 `HippocampusIndex` 引用
  - [ ] SubTask 3.2: sprout() 方法中用 `hippocampus.query(noteContent提取的关键词, 5)` 替代当前的 `getRecentNotesContext`

- [ ] Task 4: 海马体管理界面
  - [ ] SubTask 4.1: 新建 `HippocampusScreen.kt` — 索引列表 + 搜索 + 类型筛选
  - [ ] SubTask 4.2: SettingsScreen 中添加"海马体记忆"入口
  - [ ] SubTask 4.3: AppRoot 中添加海马体路由

- [ ] Task 5: 编译验证

# Task Dependencies
- Task 2 依赖 Task 1
- Task 3 依赖 Task 1
- Task 4 依赖 Task 1
- Task 5 依赖所有 Task
