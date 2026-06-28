# 核心性能优化 Spec

## Why

多 Agent 代码走读发现 Opedrgent 在本地联网查询、深度研究、知识库检索等场景下存在明显性能瓶颈：工具调用串行执行导致多工具任务整体耗时等于各工具耗时之和；HybridRankingEngine 的 MMR 重排序为 O(n²) 复杂度，结果数多时排序延迟显著；AdaptiveConcurrencyController 使用全局+引擎双重信号量，高并发下易产生死锁与排队；SearchCacheManager 使用 `@Synchronized` 锁住整个方法；HippocampusIndex 批量导入时逐条插入且缺少索引。本 spec 针对这些高优先级瓶颈进行集中优化，提升应用响应速度与并发能力。

## What Changes

- **工具执行并发化**：将 `ToolExecutor` 中多个 tool_call 的串行执行改为协程并发执行，并为每个工具设置独立超时与结构化错误返回。
- **搜索排序优化**：将 `HybridRankingEngine.rerankWithMMR` 改为仅对 Top-K 结果执行 MMR，降低复杂度。
- **并发控制器简化**：移除 `AdaptiveConcurrencyController` 中全局信号量与引擎信号量的双重锁定，改为单一动态限流机制。
- **搜索缓存无锁化**：将 `SearchCacheManager` 的 `LinkedHashMap + @Synchronized` 替换为 `ConcurrentHashMap` 实现，减少锁竞争。
- **海马索引批量写入与索引优化**：`HippocampusIndex` 支持批量插入，并为高频查询字段添加 SQLite 索引。

## Impact

- **Affected specs**: dumb-pipe-search-agent, websearcher-searxng-upgrade, global-hippocampus-memory
- **Affected code**:
  - `network/ToolExecutor.kt`
  - `tools/DeepResearchTool.kt`
  - `tools/WebSearchTool.kt`
  - `network/HybridRankingEngine.kt`
  - `network/AdaptiveConcurrencyController.kt`
  - `network/SearchCacheManager.kt`
  - `storage/HippocampusIndex.kt`
  - `storage/HippocampusDatabase.kt`
  - `utils/ToolCallGuardrail.kt`
  - `utils/ToolCallParser.kt`

## ADDED Requirements

### Requirement: 并发工具执行
The system SHALL execute multiple tool calls within a single LLM response concurrently rather than sequentially.

#### Scenario: Success case
- **WHEN** LLM returns three tool calls (`web_search`, `read_url`, `run_calendar`)
- **THEN** all three tools start in parallel and the total wait time is dominated by the slowest tool, not the sum

### Requirement: 独立工具超时
The system SHALL isolate each tool call with its own timeout so that one slow/failing tool does not abort the entire task.

#### Scenario: Partial timeout
- **WHEN** `read_url` exceeds its per-tool timeout while `web_search` succeeds
- **THEN** `read_url` returns a structured `PARTIAL_TIMEOUT` result and `web_search` result is still available to the LLM

### Requirement: Top-K MMR 排序
The system SHALL apply MMR reranking only to a bounded Top-K subset of search results.

#### Scenario: Large result set
- **WHEN** a search returns 100 results
- **THEN** ranking completes in near-linear time by MMR-reranking only the top 20 candidates

### Requirement: 单一并发限流
The system SHALL use a single per-engine concurrency limit instead of nested global + engine semaphores.

#### Scenario: High concurrency
- **WHEN** many search requests arrive simultaneously
- **THEN** requests queue per-engine without deadlock and throughput scales with the configured limit

### Requirement: 无锁搜索缓存
The system SHALL access the search cache without coarse-grained synchronization.

#### Scenario: Concurrent cache reads
- **WHEN** multiple coroutines read the same cache key concurrently
- **THEN** they do not block each other on a single monitor

### Requirement: 批量海马索引写入
The system SHALL batch insert hippocampus index entries and index frequently queried columns.

#### Scenario: Bulk import
- **WHEN** importing 500 notes
- **THEN** index entries are inserted in batches and queries by `source_type` use an index

## MODIFIED Requirements

### Requirement: Tool Call Guardrail
The existing guardrail SHALL treat per-tool failures as partial errors instead of halting the whole session when multiple tools fail.

### Requirement: Tool Call Parser
The parser SHALL return a structured parse error instead of `null` when tool call format is invalid, so the LLM does not retry blindly.

## REMOVED Requirements

None.
