# Tasks

## Phase 1: P0 崩溃/ANR 修复（12 项）

- [x] Task 1: 修复 VoiceConversationEngine engineScope 生命周期 bug
  - 文件: `interview/VoiceConversationEngine.kt`
  - 问题: `engineScope.cancel()` 后 scope 不可恢复，引擎只能用一次
  - 修复: 在 `startFullDuplex()` 开头重建 `engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)`
  - 验证: start → stop → start 不崩溃

- [x] Task 2: 修复 FullDuplexAudioEngine engineScope 生命周期 bug
  - 文件: `interview/FullDuplexAudioEngine.kt`
  - 问题: 同 Task 1，`disconnect()` 取消 scope 后 `connect()` 无法重新 launch
  - 修复: 在 `connect()` 开头重建 `engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)`
  - 验证: connect → disconnect → connect 不崩溃

- [x] Task 3: 将 HippocampusIndex 全量 runBlocking 改为 suspend (已由 performance-optimization-main spec 完成)
  - 文件: `storage/HippocampusIndex.kt`
  - 问题: 所有方法用 `runBlocking(Dispatchers.IO)`，从 Main 调用会 ANR
  - 修复: 所有 `runBlocking` 方法改为 `suspend`，调用方加 `suspend` 或在协程中调用
  - 验证: 编译通过，无 runBlocking

- [x] Task 4: 将 HippocampusSessionStore 全量 runBlocking 改为 suspend
  - 文件: `storage/HippocampusSessionStore.kt`
  - 问题: 同 Task 3
  - 修复: 同 Task 3
  - 验证: 编译通过，无 runBlocking

- [x] Task 5: 修复 InterviewAgent.activeHippocampus 线程安全 + sessionId 泄漏
  - 文件: `interview/InterviewAgent.kt`, `interview/VoiceConversationEngine.kt`
  - 问题: 普通 Map 无同步；VoiceConversationEngine 中两次 `currentTimeMillis()` 返回不同值导致 sessionId 不一致
  - 修复: Map 改为 ConcurrentHashMap；sessionId 提取为局部变量只生成一次
  - 验证: 编译通过，sessionId 一致

- [x] Task 6: 修复 HippocampusMemory.turnHistory 无同步
  - 文件: `interview/HippocampusMemory.kt`
  - 问题: `mutableListOf<TurnRecord>()` 被多线程并发访问
  - 修复: 改为 `Collections.synchronizedList(mutableListOf())`
  - 验证: 编译通过

- [x] Task 7: 修复 SourceFetcher Thread.sleep 阻塞
  - 文件: `network/SourceFetcher.kt`
  - 问题: `Thread.sleep()` 阻塞 OkHttp 调度线程
  - 修复: 改为 `kotlinx.coroutines.delay()`（需将方法改为 suspend）
  - 验证: 编译通过，无 Thread.sleep

- [x] Task 8: 修复 AdaptiveConcurrencyController shouldDecreaseLimits 死代码
  - 文件: `network/AdaptiveConcurrencyController.kt`
  - 问题: `shouldDecreaseLimits()` 从未被调用，并发数只增不减
  - 修复: 在 `maybeAdjustLimits()` 中调用 `shouldDecreaseLimits()` 并实现减少逻辑
  - 验证: 并发数在高错误率时会减少

- [x] Task 9: 修复 StreamingComponents 50ms 轮询循环
  - 文件: `ui/components/StreamingComponents.kt`
  - 问题: 50ms 轮询循环持续消耗 CPU
  - 修复: 替换为 StateFlow 驱动的状态更新
  - 验证: 流式响应期间 CPU 占用降低

- [x] Task 10: 清理 WebSearcher 废弃方法中的 runBlocking
  - 文件: `network/WebSearcher.kt`
  - 问题: 废弃方法含 `runBlocking`，ANR 风险
  - 修复: 删除所有 @Deprecated 标记的废弃方法
  - 验证: 编译通过，无 runBlocking

- [x] Task 11: 修复 FeaturePipeline sortedSetOf 同优先级 Feature 被丢弃
  - 文件: `intelligence/FeaturePipeline.kt`
  - 问题: `sortedSetOf(compareByDescending { it.priority })` 同优先级时比较返回 0，第二个被丢弃
  - 修复: 改为 `mutableListOf` + `sortedByDescending`，比较器加 name 作为 tiebreaker
  - 验证: 同优先级 Feature 不被丢弃

- [x] Task 12: 修复 FeaturePipeline getFeatures/setEnabled 未加锁
  - 文件: `intelligence/FeaturePipeline.kt`
  - 问题: `getFeatures()`/`getEnabledFeatures()`/`setEnabled()` 直接读写 `features` 未加 mutex
  - 修复: 用 `mutex.withLock` 保护这些方法
  - 验证: 并发调用不崩溃

## Phase 2: P0 线程安全修复（3 项）

- [x] Task 13: 修复 SkillWebViewExecutor 线程安全
  - 文件: `mcp/skills/SkillWebViewExecutor.kt`
  - 问题: WebView 访问无同步
  - 修复: 同步 WebView 操作
  - 验证: 并发访问不崩溃

- [x] Task 14: 修复 KnowledgeGraph 线程安全
  - 文件: `note/KnowledgeGraph.kt`
  - 问题: 并发访问数据结构无同步
  - 修复: 同步或改为 ConcurrentHashMap
  - 验证: 并发访问不崩溃

- [x] Task 15: 修复 MemoryStore 线程安全
  - 文件: `storage/MemoryStore.kt`
  - 问题: 并发访问
  - 修复: 确认 synchronized 覆盖所有公开方法
  - 验证: 并发访问不崩溃

## Phase 3: P1 功能修复（14 项）

- [x] Task 16: 修复 HippocampusMemory 0.5f.toInt() 计分 bug
  - 文件: `interview/HippocampusMemory.kt` 第 281 行
  - 问题: `0.5f.toInt()` 永远等于 0，部分匹配计分失效
  - 修复: 改为浮点累计 `matchedScore += 0.5f`，最终比较时再取整
  - 验证: 部分匹配对分数有贡献

- [x] Task 17: 修复 InterviewAgent detectDrift 双重调用
  - 文件: `interview/InterviewAgent.kt` 第 564 + 612 行
  - 问题: `prepareTurnContext` 内部已调 `detectDrift`，line 612 又调一次，turnHistory 双倍记录
  - 修复: 删除 line 612 的重复 `detectDrift` 调用
  - 验证: 每轮 turnHistory 只记录一次

- [x] Task 18: 修复 InterviewAgent lastAiResponse role 判断错误
  - 文件: `interview/InterviewAgent.kt` 第 563 行
  - 问题: 查找 `role == "assistant" || role == "ai"`，但实际 role 是 `"interviewer"`/`"candidate"`
  - 修复: 改为 `role == "interviewer"`
  - 验证: lastAiResponse 正确获取 AI 的提问

- [x] Task 19: 修复 HippocampusMemory anchorGoal 无 @Volatile
  - 文件: `interview/HippocampusMemory.kt` 第 94 行
  - 问题: `goalAnchor` 是 `var` 无 `@Volatile`，跨线程不可见
  - 修复: 加 `@Volatile`
  - 验证: 编译通过

- [x] Task 20: 修复 FullDuplexAudioEngine VAD 状态机字段无同步
  - 文件: `interview/FullDuplexAudioEngine.kt` 第 153-156 行
  - 问题: `vadSilenceStartMs`/`vadIsSpeechActive`/`currentSpeechBuffer`/`leadingSilenceFrameCount` 普通 var 被多线程访问
  - 修复: 用 `@Volatile` 或 `synchronized` 保护
  - 验证: 编译通过

- [x] Task 21: 修复 VoiceConversationEngine aiSpeakLegacy StyleControl 丢弃
  - 文件: `interview/VoiceConversationEngine.kt` 第 932-946 行
  - 问题: StyleControl 创建后未传给 `ttsPlayer.speak()`
  - 修复: 将 style 参数传入 speak 调用
  - 验证: TTS 风格控制生效

- [x] Task 22: 修复 FullDuplexAudioEngine partialTextListeners 死监听器
  - 文件: `interview/FullDuplexAudioEngine.kt`
  - 问题: partialTextListeners 注册接口存在但从未被通知
  - 修复: 在 ASR 产生 partial text 时调用 `partialTextListeners.forEach { it(text) }`，或删除整个死系统
  - 验证: 注册的监听器能收到回调，或死代码已删除

- [x] Task 23: 修复 VoiceConversationEngine sessionId 时间戳不一致 (已由 Task 5 一起修复)
  - 文件: `interview/VoiceConversationEngine.kt` 第 230-237 行
  - 问题: 两次 `currentTimeMillis()` 返回不同值，InterviewAgent 注册的 sessionId 与本地不一致
  - 修复: 提取为局部变量 `val sid = "voice_${System.currentTimeMillis()}"`
  - 验证: sessionId 一致

- [x] Task 24: 修复 CuratorService.markSkillStale 逻辑 bug
  - 文件: `mcp/skills/CuratorService.kt`
  - 问题: markSkillStale 逻辑错误
  - 修复: 读取文件理解上下文后修复
  - 验证: 编译通过

- [x] Task 25: 修复 NoteDatabase Migration 不完整
  - 文件: `note/NoteDatabase.kt`
  - 问题: Migration 缺失某些版本路径
  - 修复: 补齐 migration 逻辑
  - 验证: 编译通过

- [x] Task 26: 修复 LlmClient 连接池管理
  - 文件: `network/LlmClient.kt`
  - 问题: 连接池配置不合理
  - 修复: 优化连接池参数
  - 验证: 编译通过

- [x] Task 27: 修复 MultiLevelCacheManager 无 LRU 淘汰
  - 文件: `network/MultiLevelCacheManager.kt`
  - 问题: L1 内存缓存无 LRU 淘汰，可能 OOM
  - 修复: 使用 LruCache 或 LinkedHashMap 实现 LRU
  - 验证: 缓存超过容量时自动淘汰最旧条目

- [x] Task 28: 接入 EditorTeamSkillAdapter
  - 文件: `mcp/editors/EditorTeamService.kt`, `mcp/editors/EditorTeamSkillAdapter.kt`
  - 问题: SkillAdapter 写了但从未被调用
  - 修复: 在 planPipeline 中调用 `skillAdapter.getSkillBasedRoles()` 合并到可用角色列表
  - 验证: Skill 可以作为角色参与讨论

- [x] Task 29: 修复 HippocampusMemory Jaccard 相似度系统性偏低
  - 文件: `interview/HippocampusMemory.kt` 第 292-296 行
  - 问题: goalChars（短）与 responseChars（长）取并集，union 被响应字符主导，jaccard 恒小
  - 修复: 改用 TF-IDF 或加权 Jaccard，或对 goalChars 提取关键词后比较
  - 验证: 完全切题的回答漂移分数合理

## Phase 4: 架构重构（4 项）

- [x] Task 30: 拆分 WebSearcher.kt 2168 行 God Class
  - 文件: `network/WebSearcher.kt`
  - 拆分为:
    - `SearchDispatcher.kt` — 搜索调度，多引擎分发
    - `SearchResultRanker.kt` — 结果排名（整合 HybridRankingEngine 调用）
    - `SearchCacheManager.kt` — 搜索缓存管理
    - `SearchDeduplicator.kt` — 结果去重
    - `SearchConfig.kt` — 搜索相关配置常量
  - 保留 WebSearcher.kt 作为门面（Facade），委托给上述组件
  - 验证: 编译通过，搜索功能正常

- [x] Task 31: 重写编辑团队为群聊模式
  - 文件: `mcp/editors/EditorTeamService.kt`, `ui/EditorTeamScreen.kt`
  - 改造内容:
    - groupDiscussion 改为流式输出（使用 streamChat 而非 chatCompletions）
    - 每个角色能看到所有前序角色的完整发言（非 takeLast(3) 摘要）
    - 角色 prompt 增加 @角色名 引用能力
    - "正在发言"指示器：在角色开始前通过 onStart 回调通知 UI
    - 多轮对话：用户可在讨论结束后继续追问
    - 角色选择：用户可在 UI 上选角色组合
  - 验证: 流式显示、角色互引、多轮对话、指示器生效

- [x] Task 32: 重写 AGENTS.md
  - 文件: `AGENTS.md`
  - 改造内容:
    - 删除 8 个不存在的 intelligence 文件描述
    - 补齐实际记忆架构（MemoryStore + HippocampusIndex + HippocampusSessionStore + HippocampusMemory）
    - 删除 SkillModelUnifier 引用
    - 更新 intelligence/ 目录实际内容（TokenBudgetMonitor + FeaturePipeline）
    - 更新编辑团队架构描述
  - 验证: 文档与代码一致

- [x] Task 33: 提取硬编码参数到统一配置层
  - 文件: 新建 `network/NetworkConfig.kt` 或 `utils/AppConfig.kt`
  - 提取内容:
    - 超时值（connect/read/write timeout）
    - 重试次数
    - 缓冲区大小
    - API URL（MiMO ASR/TTS URL 统一）
    - 评分权重（HybridRankingEngine 权重）
    - 并发限制参数
    - VAD 阈值
  - 验证: 编译通过，无硬编码魔法数字

## Phase 5: P2 死代码清理（8 项）

- [x] Task 34: 删除 VoiceConversationEngine waitingForUserInput 死变量
  - 文件: `interview/VoiceConversationEngine.kt`
  - 修复: 删除 `waitingForUserInput` 变量及所有赋值

- [x] Task 35: 删除 FullDuplexAudioEngine withContextCompat 死抽象
  - 文件: `interview/FullDuplexAudioEngine.kt`
  - 修复: 删除 `withContextCompat` 函数，调用处直接用 `withContext`

- [x] Task 36: 删除 FeaturePipeline standard() 空工厂
  - 文件: `intelligence/FeaturePipeline.kt`
  - 修复: 删除 `standard()` 方法

- [x] Task 37: 合并重复 editDistance / emptyResult / smartTruncate 定义
  - 文件: 多文件
  - 修复: 统一到 utils 工具类，删除重复定义

- [x] Task 38: 统一 MiMO API URL 到配置层
  - 文件: `stt/MimoAsrEngine.kt`, `tts/MimoTtsClient.kt`
  - 修复: 提取到 ApiConfig 或 NetworkConfig

- [x] Task 39: 删除 fullCreationPipeline（与 defaultPipeline 完全相同）
  - 文件: `mcp/editors/EditorRole.kt`
  - 修复: 删除 `fullCreationPipeline`，引用处改为 `defaultPipeline`

- [x] Task 40: 修复 DifficultyLevel.CUSTOM 硬编码 level=5
  - 文件: `interview/InterviewMode.kt`
  - 修复: 改为可接受外部参数的 level 值，或标记为 deprecated

- [x] Task 41: 清理 InterviewInputBar 残留引用
  - 文件: `ui/InterviewScreen.kt`
  - 修复: 搜索并删除所有对已删除 InterviewInputBar 的引用

## Phase 6: 编译验证

- [x] Task 42: 全量编译验证
  - 命令: `$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew assembleDebug`
  - 验证: BUILD SUCCESSFUL，0 errors

# Task Dependencies

- Task 1, 2 互相独立，可并行
- Task 3, 4 互相独立，可并行（但调用方需同步修改）
- Task 5 依赖 Task 6（turnHistory 同步先修）
- Task 7 依赖 Task 3/4（suspend 改造完成后才能改调用方）
- Task 8-15 互相独立，可并行
- Task 16-23 互相独立，可并行
- Task 24-29 互相独立，可并行
- Task 30 依赖 Task 10（先清理废弃方法再拆分）
- Task 31 依赖 Task 28（先接入 SkillAdapter 再重写群聊）
- Task 32 可独立执行
- Task 33 可独立执行
- Task 34-41 互相独立，可并行
- Task 42 依赖所有前置任务完成

# 并行执行策略

- Batch 1: Tasks 1,2,3,4,5,6 (P0 引擎生命周期 + 持久化层)
- Batch 2: Tasks 7,8,9,10,11,12,13,14,15 (P0 线程安全 + 性能)
- Batch 3: Tasks 16-23 (P1 面试/海马体修复)
- Batch 4: Tasks 24-29 (P1 其他修复)
- Batch 5: Tasks 30,31,32,33 (架构重构)
- Batch 6: Tasks 34-41 (P2 死代码清理)
- Batch 7: Task 42 (编译验证)
