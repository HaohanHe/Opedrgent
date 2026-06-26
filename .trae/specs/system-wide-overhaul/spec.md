# 全项目系统改造 Spec

## Why

全项目代码审计发现 20 个 P0（崩溃/ANR/数据丢失）、106 个 P1（功能降级/竞态/性能）、90+ 个 P2（代码质量/死代码）问题。涉及记忆系统幻影文档、WebSearcher 2168 行 God Class、MainViewModel 5716 行神类、音频引擎只能用一次、全量 runBlocking 阻塞等根本性架构缺陷。必须系统性修复才能继续添加新功能。

## What Changes

### P0 崩溃/ANR 修复（12 项）
- 修复 VoiceConversationEngine + FullDuplexAudioEngine 的 engineScope 生命周期 bug（取消后不可恢复）
- 将 HippocampusIndex + HippocampusSessionStore 的全量 `runBlocking` 改为 `suspend`
- 修复 InterviewAgent.activeHippocampus 线程安全 + sessionId 时间戳不一致泄漏
- 修复 HippocampusMemory.turnHistory 无同步
- 修复 SkillWebViewExecutor / KnowledgeGraph / MemoryStore 线程安全
- 修复 SourceFetcher `Thread.sleep` 阻塞 OkHttp 调度线程
- 修复 AdaptiveConcurrencyController `shouldDecreaseLimits` 死代码（并发数只增不减）
- 修复 StreamingComponents 50ms 轮询循环（性能杀手）
- 清理 WebSearcher 废弃方法中的 `runBlocking`

### 架构重构（4 项）
- **BREAKING** 拆分 WebSearcher.kt 2168 行 God Class 为 6+ 个独立组件
- 重写编辑团队为真正的群聊模式（流式输出、多轮对话、角色互引、"正在发言"指示器）
- 重写 AGENTS.md（删除 8 个不存在的 intelligence 文件描述，补齐实际架构）
- 提取 49+ 处硬编码参数到统一配置层

### P1 功能修复（14 项）
- 修复 HippocampusMemory `0.5f.toInt() == 0` 计分 bug
- 修复 InterviewAgent detectDrift 双重调用（漂移报告翻倍）
- 修复 InterviewAgent `lastAiResponse` role 判断错误
- 修复 HippocampusMemory `anchorGoal` 无 @Volatile
- 修复 FeaturePipeline `sortedSetOf` 同优先级 Feature 被丢弃
- 修复 FeaturePipeline `getFeatures()` 未加锁
- 修复 FullDuplexAudioEngine VAD 状态机字段无同步
- 修复 VoiceConversationEngine `aiSpeakLegacy` StyleControl 构造后丢弃
- 修复 FullDuplexAudioEngine `partialTextListeners` 死监听器系统
- 接入 EditorTeamSkillAdapter（写了但从未调用）
- 修复 CuratorService.markSkillStale 逻辑 bug
- 修复 NoteDatabase Migration 不完整
- 修复 LlmClient 连接池管理
- 修复 MultiLevelCacheManager 无 LRU 淘汰

### P2 死代码清理（8 项）
- 删除 VoiceConversationEngine `waitingForUserInput` 死变量
- 删除 FullDuplexAudioEngine `withContextCompat` 死抽象
- 删除 FeaturePipeline `standard()` 空工厂
- 合并重复 `editDistance` / `emptyResult` / `smartTruncate` 定义
- 统一 MiMO API URL 到配置层
- 删除 `fullCreationPipeline`（与 defaultPipeline 完全相同）
- 修复 `DifficultyLevel.CUSTOM` 硬编码 level=5
- 清理 `InterviewInputBar` 残留引用

## Impact

- Affected specs: project-wide-bugfix-sweep (已完成，本轮在其基础上深入)
- Affected code:
  - `intelligence/` (2 文件)
  - `interview/` (5 文件)
  - `network/` (15 文件)
  - `stt/` (8 文件)
  - `tts/` (2 文件)
  - `note/` (10 文件)
  - `storage/` (4 文件)
  - `mcp/editors/` (3 文件)
  - `mcp/skills/` (7 文件)
  - `ui/` (20+ 文件，含 MainViewModel 拆分)
  - `service/` (4 文件)
  - `utils/` (15 文件)
  - `AGENTS.md`

## ADDED Requirements

### Requirement: 音频引擎可重用生命周期
The system SHALL allow VoiceConversationEngine and FullDuplexAudioEngine to be started, stopped, and restarted without crashing. engineScope SHALL be recreated on each start call.

#### Scenario: Stop and restart voice engine
- **WHEN** user starts a voice interview, stops it, then starts again
- **THEN** engine creates a new CoroutineScope and functions normally

### Requirement: 非阻塞持久化层
The system SHALL perform all SQLite operations via suspend functions, never blocking the calling thread with runBlocking.

#### Scenario: HippocampusIndex called from Main thread
- **WHEN** any HippocampusIndex method is called
- **THEN** the calling thread is not blocked and no ANR occurs

### Requirement: 编辑团队群聊模式
The system SHALL provide a true group-chat experience where multiple editor roles can see each other's full messages, reference and reply to specific roles, and support multi-turn user conversations with streaming output.

#### Scenario: Multi-role discussion with references
- **WHEN** user sends a writing request in editor team mode
- **THEN** each role streams its response in real-time, can reference previous roles' messages, and the user can continue the conversation after the initial round

### Requirement: 统一配置层
The system SHALL centralize all hardcoded timeouts, retry counts, buffer sizes, API URLs, and scoring weights into a single configuration layer.

#### Scenario: Change API timeout
- **WHEN** developer needs to change a timeout value
- **THEN** the value is found in one configuration file, not scattered across 49+ locations

## MODIFIED Requirements

### Requirement: 记忆系统架构
AGENTS.md describes a 3-layer memory system (MemoryDir ↔ MemoryBridge ↔ VectorMemory ↔ SqlitePersistence) that does not exist. The actual memory system consists of MemoryStore (SharedPreferences) + HippocampusIndex (SQLite) + HippocampusSessionStore (SQLite) + HippocampusMemory (in-memory). Documentation SHALL be updated to reflect reality.

### Requirement: WebSearcher 架构
WebSearcher.kt (2168 lines) SHALL be decomposed into focused components: SearchDispatcher, ResultRanker, CacheManager, Deduplicator, AuthorityScorer, FreshnessCalculator. Each component SHALL have a single responsibility and be independently testable.

## REMOVED Requirements

### Requirement: 3-layer Memory System (MemoryDir/MemoryBridge/VectorMemory/SqlitePersistence)
**Reason**: These 8 files were never implemented or have been completely deleted. AGENTS.md documentation is phantom.
**Migration**: Update AGENTS.md to describe actual memory architecture. No code migration needed.

### Requirement: SkillModelUnifier
**Reason**: Referenced in AGENTS.md but file does not exist. Never implemented.
**Migration**: Remove from AGENTS.md. If model unification is needed in the future, create a new spec.
