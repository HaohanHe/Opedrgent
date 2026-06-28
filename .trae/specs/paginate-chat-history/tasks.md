# Tasks

- [x] Task 1: 消息模型与数据源分页支持
  - [x] SubTask 1.1: 检查 `ChatMessage` 模型，确认是否已有轮次/顺序字段；如无，新增 `roundIndex: Int` 或类似字段
  - [x] SubTask 1.2: 在会话数据源（Repository/DAO/Store）中新增按 round 范围查询消息的方法，如 `getMessagesByRounds(sessionId, startRound, endRound)`
  - [x] SubTask 1.3: 在保存消息时正确计算并写入 roundIndex
  - [x] SubTask 1.4: 运行 `./gradlew :app:compileDebugKotlin` 验证编译

- [x] Task 2: MainViewModel 分页状态管理
  - [x] SubTask 2.1: 在当前会话状态中添加 `visibleRounds: Int = 10`、`hasMoreOlderRounds: Boolean = true`、`isLoadingOlderRounds: Boolean = false`
  - [x] SubTask 2.2: 实现 `loadMoreRounds(sessionId: String, count: Int = 10)` 方法，异步加载更早轮次并追加到当前消息列表
  - [x] SubTask 2.3: 切换会话时重置分页状态
  - [x] SubTask 2.4: 运行编译验证

- [x] Task 3: SessionScreen 分页 UI
  - [x] SubTask 3.1: 修改 `SessionScreen.kt` 的 `LazyColumn`，使用分页后的消息列表
  - [x] SubTask 3.2: 在 `LazyColumn` 顶部添加加载指示器 item，当 `isLoadingOlderRounds` 为 true 时显示
  - [x] SubTask 3.3: 通过 `LazyListState` 监听到达顶部事件，触发 `loadMoreRounds`
  - [x] SubTask 3.4: 加载新数据后保持滚动位置（记录第一个可见 item 的 key/index 并恢复）
  - [x] SubTask 3.5: 当 `hasMoreOlderRounds` 为 false 时，顶部显示“已加载全部历史”提示
  - [x] SubTask 3.6: 运行编译验证

- [x] Task 4: 最终构建与回归验证
  - [x] SubTask 4.1: 运行 `./gradlew assembleDebug`
  - [x] SubTask 4.2: 检查聊天发送新消息、切换会话、加载历史是否正常工作

# Task Dependencies

- Task 2 依赖 Task 1
- Task 3 依赖 Task 2
- Task 4 依赖 Task 3
