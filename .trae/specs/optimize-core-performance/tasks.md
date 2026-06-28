# Tasks

- [x] Task 1: 工具执行并发化与独立超时
  - [x] SubTask 1.1: 修改 `network/ToolExecutor.kt`，将多个 tool_call 的串行执行改为 `coroutineScope { async {} }` 并发执行
  - [x] SubTask 1.2: 为每个工具调用添加独立 `withTimeout`，超时后返回结构化错误结果
  - [x] SubTask 1.3: 更新 `utils/ToolCallGuardrail.kt`，将部分工具失败从 `SESSION_HALT` 改为 `PARTIAL_ERROR`
  - [x] SubTask 1.4: 更新 `utils/ToolCallParser.kt`，解析失败返回结构化错误而非 `null`
  - [x] SubTask 1.5: 运行 `./gradlew :app:compileDebugKotlin` 验证编译

- [x] Task 2: 搜索排序 MMR 优化
  - [x] SubTask 2.1: 修改 `network/HybridRankingEngine.kt`，在 `rerankWithMMR` 前先取 Top-K（默认 20）候选
  - [x] SubTask 2.2: 仅对 Top-K 候选执行 MMR，避免 O(n²) 全量比较
  - [x] SubTask 2.3: 检查 `network/WebResearchRouter.kt` 是否调用 `ranker.rank()`，如未调用则补上
  - [x] SubTask 2.4: 运行编译验证

- [x] Task 3: 并发控制器简化
  - [x] SubTask 3.1: 修改 `network/AdaptiveConcurrencyController.kt`，移除 `withGlobalAccess` 与 `withEngineAccess` 的嵌套双重信号量
  - [x] SubTask 3.2: 保留单一引擎级信号量作为并发控制
  - [x] SubTask 3.3: 更新 `network/WebSearcher.kt` 中对 `withEngineAccess` 的调用（如签名变化）
  - [x] SubTask 3.4: 运行编译验证

- [x] Task 4: 搜索缓存无锁化
  - [x] SubTask 4.1: 修改 `network/SearchCacheManager.kt`，将 `LinkedHashMap + @Synchronized` 替换为 `ConcurrentHashMap`
  - [x] SubTask 4.2: 保留 LRU 淘汰语义（可使用 `ConcurrentLinkedDeque` 或分段锁实现）
  - [x] SubTask 4.3: 运行编译验证

- [x] Task 5: 海马索引批量写入与索引优化
  - [x] SubTask 5.1: 修改 `storage/HippocampusIndex.kt`，新增 `insertBatch(entries)` 批量插入接口
  - [x] SubTask 5.2: 在批量导入场景（如笔记批量导入）使用批量插入
  - [x] SubTask 5.3: 修改 `storage/HippocampusDatabase.kt`，为 `source_type` 等高频字段添加索引
  - [x] SubTask 5.4: 运行编译验证

# Task Dependencies

- Task 2 可依赖 Task 3/4 并行执行，但与 Task 1 无依赖
- Task 5 可独立执行
- 所有 Task 已完成，已统一运行 `./gradlew assembleDebug`
