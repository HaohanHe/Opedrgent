# Checklist

- [x] ToolExecutor 中多个 tool_call 并发执行，且每个工具调用有独立超时
- [x] 工具超时时返回结构化 `PARTIAL_TIMEOUT`/`TOOL_ERROR` 结果，不影响其他工具结果
- [x] ToolCallGuardrail 不再因单个/多个工具失败而直接 HALT 整个会话
- [x] ToolCallParser 解析失败时返回结构化错误而非 `null`
- [x] HybridRankingEngine 的 MMR 仅对 Top-K（默认 20）候选执行，避免 O(n²)
- [x] WebResearchRouter 调用 `ranker.rank()` 对搜索结果进行排序
- [x] AdaptiveConcurrencyController 不再使用全局+引擎双重信号量嵌套
- [x] 高并发搜索请求能够按引擎并发限制排队，无死锁风险
- [x] SearchCacheManager 使用无锁/细粒度锁实现，并发读不阻塞
- [x] SearchCacheManager 仍保留 LRU 淘汰策略
- [x] HippocampusIndex 支持批量插入接口
- [x] 笔记批量导入等场景使用批量插入
- [x] HippocampusDatabase 对 `source_type` 等高频查询字段建立索引
- [x] `./gradlew assembleDebug` 构建成功
- [x] 无新增 runBlocking 或主线程同步 IO
