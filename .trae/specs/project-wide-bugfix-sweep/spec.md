# Project-Wide Bugfix Sweep Spec

## Why
5 个并行 agent 对全项目进行了深度代码审计，发现 ~114 个问题（28 HIGH / 54 MEDIUM / 32 LOW）。高严重度问题包括连接泄漏、线程安全、WAV 文件头损坏、协程泄漏、JSON 解析崩溃等，直接影响稳定性和用户体验。

## What Changes
- 修复 28 个 HIGH 严重度问题（崩溃、数据丢失、资源泄漏、安全漏洞）
- 修复 54 个 MEDIUM 严重度问题（功能降级、竞态条件、性能瓶颈）
- 清理 LOW 严重度问题中的死代码和硬编码魔法数字

## Impact
- Affected specs: 全项目所有模块
- Affected code: LlmClient, ToolExecutor, SproutService, NoteEditorScreen, InterviewAgent, FullDuplexAudioEngine, VoiceConversationEngine, EditorTeamService, AudioProcessor, TtsPlayer, SherpaOnnxEngine, MimoAsrEngine, SkillWebViewExecutor, SmartCircuitBreaker, AdaptiveConcurrencyController, KnowledgeGraph, NoteDao, NoteDatabase, SproutReportStore 等 30+ 文件

## 问题分布

### 按模块分类

| 模块 | HIGH | MEDIUM | LOW | 合计 |
|------|------|--------|-----|------|
| 网络层 (LlmClient/CircuitBreaker/Concurrency) | 7 | 12 | 9 | 28 |
| 面试模式 (Interview/VoiceEngine/FullDuplex) | 6 | 8 | 3 | 17 |
| 编辑团队 (EditorTeam/EditorRole) | 3 | 9 | 6 | 18 |
| 笔记系统 (Note/Sprout/KnowledgeGraph) | 7 | 15 | 8 | 30 |
| STT/TTS/技能 (AsrManager/TtsPlayer/Skill) | 5 | 10 | 6 | 21 |

### 优先级分层

**P0 — 立即修复（崩溃/数据丢失/安全）**
1. AudioProcessor.saveAsWav WAV 头 "WAFE" 错误 → MiMO ASR 流式全部失败
2. VocabularyStore.applyVocabulary 无操作 → 词汇纠正系统完全失效
3. Sentiment.valueOf() 无 try-catch → 旧版发芽报告加载崩溃
4. LlmClient 连接泄漏 (streamMultimodal/streamMessages) → 连接池耗尽
5. SmartCircuitBreaker.allowRequest() 线程安全 → 熔断保护失效
6. interviewTranscript 并发访问 → ConcurrentModificationException
7. sendInterviewAnswer 状态重置丢失全双工状态 → UI 状态不一致
8. generateFinalReport 异常时语音引擎不停止 → 麦克风泄漏
9. DynamicRole.displayColor 负数取模 → ArrayIndexOutOfBoundsException
10. SkillWebViewExecutor JS 注入漏洞 → 安全风险
11. TtsPlayer 线程安全与资源泄漏 → OOM/状态不一致
12. ghostTextJob 内嵌 scope.launch 不受取消控制 → 协程泄漏
13. InsightSproutEngine.phaseCache 非线程安全 → ConcurrentModificationException
14. sproutBatch() 无并发控制 → API 限流/配额耗尽

**P1 — 尽快修复（功能降级/竞态/性能）**
- AdaptiveConcurrencyController 优先级队列死代码 + 超时未实现
- activeRequests 非原子递增/递减
- FullDuplexAudioEngine.engineScope 永不取消
- VoiceConversationEngine 孤立 CoroutineScope
- KnowledgeGraph IDF 恒为 1.0 → TF-IDF 退化为纯 TF
- safeAddColumn 吞掉所有异常
- 每次保存触发知识图谱全量计算
- AutoSproutWorker 不更新 note.sproutReportJson
- runCalendarTool 缺少 update action（已修复）
- MimoTtsClient 硬编码 api-key 认证头
- EditorTeamService.isCancelled 非线程安全
- WorkflowStorage 非线程安全
- evaluateCondition 运算符匹配顺序错误
- MimoAsrEngine.detectTrailingSilence 读取共享缓冲区不同步

**P2 — 后续清理（死代码/魔法数字/代码质量）**
- ToolExecutor 遗留方法
- LlmClient isDeepSeekV4 重复
- InterviewInputBar 死代码
- editDistance 重复定义
- 硬编码魔法数字统一提取为常量

## ADDED Requirements

### Requirement: 线程安全一致性
所有跨线程共享的可变状态 SHALL 使用 `@Volatile` + `AtomicBoolean`/`AtomicInteger`、`Mutex`、`synchronized` 或 `ConcurrentHashMap` 保护。

#### Scenario: 并发访问 interviewTranscript
- **WHEN** 多个协程同时读写 interviewTranscript
- **THEN** 不抛出 ConcurrentModificationException

### Requirement: 资源生命周期管理
所有 CoroutineScope、AudioTrack、OkHttp Response SHALL 与明确的生命周期绑定，确保释放时自动取消/关闭。

#### Scenario: 面试结束时资源释放
- **WHEN** 用户结束面试
- **THEN** 所有语音引擎协程被取消，麦克风停止，AudioTrack 释放

### Requirement: JSON 解析健壮性
所有从 LLM 响应解析 JSON 的代码 SHALL 使用 try-catch 包裹 enum.valueOf() 等可能抛异常的调用。

#### Scenario: LLM 返回非标准 sentiment
- **WHEN** LLM 返回 sentiment="positive"（小写）
- **THEN** 回退到 Sentiment.NEUTRAL，不崩溃

## MODIFIED Requirements
无（本次为纯 bugfix，不修改功能规格）

## REMOVED Requirements
无
