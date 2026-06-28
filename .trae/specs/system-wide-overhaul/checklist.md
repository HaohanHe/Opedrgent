# Checklist

## Phase 1: P0 崩溃/ANR 修复验证

- [x] Task 1: VoiceConversationEngine.startFullDuplex() 中 engineScope 被重建，stop→start 不崩溃
- [x] Task 2: FullDuplexAudioEngine.connect() 中 engineScope 被重建，disconnect→connect 不崩溃
- [x] Task 3: HippocampusIndex 中无 runBlocking，所有方法为 suspend
- [x] Task 4: HippocampusSessionStore 中无 runBlocking，所有方法为 suspend
- [x] Task 5: InterviewAgent.activeHippocampus 使用 ConcurrentHashMap；sessionId 只生成一次
- [x] Task 6: HippocampusMemory.turnHistory 使用 synchronizedList
- [x] Task 7: SourceFetcher 中无 Thread.sleep，改用 delay
- [x] Task 8: AdaptiveConcurrencyController.maybeAdjustLimits() 调用 shouldDecreaseLimits()
- [x] Task 9: StreamingComponents 无 50ms 轮询循环，改用 snapshotFlow + debounce
- [x] Task 10: WebSearcher 中无 @Deprecated 方法，无 runBlocking
- [x] Task 11: FeaturePipeline 同优先级 Feature 不被丢弃（mutableListOf + sortedByDescending）
- [x] Task 12: FeaturePipeline getFeatures/setEnabled 在 mutex 保护下

## Phase 2: P0 线程安全验证

- [x] Task 13: SkillWebViewExecutor WebView 操作通过 Handler(Looper.getMainLooper()).post 同步
- [x] Task 14: KnowledgeGraph 使用 ConcurrentHashMap + synchronized(this) 跨多 map 操作
- [x] Task 15: MemoryStore 所有公开方法在 synchronized 保护下

## Phase 3: P1 功能修复验证

- [x] Task 16: HippocampusMemory 部分匹配对分数有贡献（float accumulator matchedScore）
- [x] Task 17: InterviewAgent 每轮 detectDrift 只调用一次
- [x] Task 18: InterviewAgent lastAiResponse 正确获取 role="interviewer" 的消息
- [x] Task 19: HippocampusMemory.goalAnchor 有 @Volatile 注解
- [x] Task 20: FullDuplexAudioEngine VAD 字段有 @Volatile 或 synchronized 保护
- [x] Task 21: VoiceConversationEngine aiSpeakLegacy 的 StyleControl 传给 ttsPlayer.speak(style=)
- [x] Task 22: FullDuplexAudioEngine partialTextListeners 死系统已删除
- [x] Task 23: VoiceConversationEngine sessionId 只生成一次，InterviewAgent 与本地一致
- [x] Task 24: CuratorService.markSkillStale 不再更新 lastUsedAt
- [x] Task 25: NoteDatabase 增量 while-loop migration 模式
- [x] Task 26: LlmClient 连接池参数合理（HttpClients 统一管理）
- [x] Task 27: MultiLevelCacheManager L1 缓存使用 android.util.LruCache(10MB)
- [x] Task 28: EditorTeamService.planPipeline 调用 skillAdapter.getSkillBasedRoles()
- [x] Task 29: HippocampusMemory Jaccard 改为关键词级匹配 + STOPWORDS 停用词表

## Phase 4: 架构重构验证

- [x] Task 30: WebSearcher.kt 从 2168 行降至 1761 行，拆分出 5 个独立组件（SearchConfig 110行 + SearchCacheManager 138行 + SearchResultRanker 58行 + SearchDeduplicator 24行 + NetworkConfig 10行），公开 API 不变
- [x] Task 31: 编辑团队支持流式输出（streamChatCompletions）、角色互引（@角色名）、多轮对话（discussionHistory）、"正在发言"指示器（onRoleStart）
- [x] Task 32: AGENTS.md 删除 8 个幻影 intelligence 文件描述，补充 HippocampusSessionStore，删除 SkillModelUnifier
- [x] Task 33: 超时/重试/缓冲区/API URL 等参数集中在 NetworkConfig.kt + ApiSettings.MIMO_API_URL

## Phase 5: P2 死代码清理验证

- [x] Task 34: VoiceConversationEngine 无 waitingForUserInput 变量（grep 确认 0 匹配）
- [x] Task 35: FullDuplexAudioEngine 无 withContextCompat 函数（grep 确认 0 匹配）
- [x] Task 36: FeaturePipeline 无 standard() 方法
- [x] Task 37: editDistance/emptyResult/smartTruncate 统一到 utils 工具类（ToolExecutor.kt + StringUtils.kt）
- [x] Task 38: MiMO API URL 统一到 ApiSettings.MIMO_API_URL，MimoAsrEngine/MimoTtsClient 引用
- [x] Task 39: EditorRole 无 fullCreationPipeline（grep 确认 0 匹配）
- [x] Task 40: DifficultyLevel.CUSTOM 改为 InterviewConfig.customDifficultyLevel 可接受外部参数
- [x] Task 41: InterviewScreen 无 InterviewInputBar 引用（grep 确认 0 匹配）

## Phase 6: 编译验证

- [x] Task 42: `./gradlew assembleDebug` BUILD SUCCESSFUL in 17s, 0 errors
- [x] 无新增 warning
