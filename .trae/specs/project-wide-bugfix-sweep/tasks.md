# Tasks

## Phase 1: P0 — 崩溃/数据丢失/安全 (14 项)

- [x] Task 1: 修复 AudioProcessor.saveAsWav WAV 头 "WAFE" 错误
  - 文件: `stt/AudioProcessor.kt` 第 623-624 行
  - 修复: `0x57414645` → `0x57415645`
  - 验证: 保存 WAV 文件后用标准解析器验证头部

- [x] Task 2: 修复 VocabularyStore.applyVocabulary 无操作
  - 文件: `stt/VocabularyStore.kt` 第 46 行
  - 修复: `result.replace(term, term)` → `result.replace(term, correctTerm)` (需要词表存储正确写法)
  - 验证: 词汇纠正后结果与输入不同

- [x] Task 3: 修复 Sentiment.valueOf() 无 try-catch (SproutReport.fromJson)
  - 文件: `note/Note.kt` 第 206 行
  - 修复: 用 `try { Sentiment.valueOf(...) } catch (_: Exception) { Sentiment.NEUTRAL }` 包裹
  - 验证: 传入 "positive" 不崩溃，返回 NEUTRAL

- [x] Task 4: 修复 LlmClient 连接泄漏 (streamMultimodal + streamMessages)
  - 文件: `network/LlmClient.kt` 第 649-663 行、第 1030-1042 行
  - 修复: 添加 `finally { response.close() }` 或确保所有路径关闭 response
  - 验证: 长时间运行后连接池不泄漏

- [x] Task 5: 修复 SmartCircuitBreaker.allowRequest() 线程安全
  - 文件: `network/SmartCircuitBreaker.kt` 第 42-66 行
  - 修复: 使用 `synchronized(this)` 保护状态转换逻辑
  - 验证: 高并发下 HALF_OPEN 状态不超过 halfOpenMaxProbes

- [x] Task 6: 修复 interviewTranscript 并发访问
  - 文件: `ui/MainViewModel.kt` 第 5819 行
  - 修复: 改为 `Collections.synchronizedList(mutableListOf())` 或使用 Mutex
  - 验证: 多协程并发读写不抛 ConcurrentModificationException

- [x] Task 7: 修复 sendInterviewAnswer 状态重置丢失全双工状态
  - 文件: `ui/MainViewModel.kt` 第 6049-6056 行
  - 修复: 用 `currentUiState.copy(...)` 替代 `InterviewUiState(...)`
  - 验证: AI 思考期间 duplexState/isMuted 等状态不被重置

- [x] Task 8: 修复 generateFinalReport 异常时语音引擎不停止
  - 文件: `ui/MainViewModel.kt` 第 6087-6114 行
  - 修复: 将 `voiceEngine?.stopConversation()` 移到 `finally` 块
  - 验证: 报告生成异常后麦克风不继续录音

- [x] Task 9: 修复 DynamicRole.displayColor 负数取模
  - 文件: `mcp/editors/EditorRole.kt` 第 326 行
  - 修复: `name.hashCode() % size` → `(name.hashCode() and 0x7FFFFFFF) % size`
  - 验证: 任何角色名都不触发 ArrayIndexOutOfBoundsException

- [x] Task 10: 修复 SkillWebViewExecutor JS 注入漏洞
  - 文件: `mcp/skills/SkillWebViewExecutor.kt` 第 235-261 行
  - 修复: 对 paramsJson 做 JSON 转义后再拼接到 JS 代码中
  - 验证: 包含特殊字符的 params 不被解释为 JS 代码

- [x] Task 11: 修复 TtsPlayer 线程安全与资源泄漏
  - 文件: `tts/TtsPlayer.kt` 第 122-163 行
  - 修复: isSpeaking/isPaused 改为 AtomicBoolean；CoroutineScope 绑定生命周期
  - 验证: Activity 销毁后 TTS 协程自动取消

- [x] Task 12: 修复 ghostTextJob 内嵌 scope.launch 不受取消控制
  - 文件: `ui/NoteEditorScreen.kt` 第 1361-1385 行
  - 修复: 内层 scope.launch 改为在外层 launch 内直接执行（去掉嵌套 launch）
  - 验证: ghostTextJob.cancel() 后无残留 API 请求

- [x] Task 13: 修复 InsightSproutEngine.phaseCache 非线程安全
  - 文件: `insight/InsightSproutEngine.kt` 第 46 行
  - 修复: `mutableMapOf()` → `ConcurrentHashMap<String, Any?>()`
  - 验证: 4 阶段并发执行不抛 ConcurrentModificationException

- [x] Task 14: 修复 sproutBatch() 无并发控制
  - 文件: `note/SproutService.kt` 第 253-271 行
  - 修复: 使用 Semaphore(2) 限制并发数为 2
  - 验证: 50 篇笔记发芽时同时不超过 2 个 API 请求

## Phase 2: P1 — 功能降级/竞态/性能 (14 项)

- [x] Task 15: 修复 AdaptiveConcurrencyController 优先级队列 + 超时
  - 文件: `network/AdaptiveConcurrencyController.kt`
  - 修复: 实现 continuation.resume(true) 调用；tryAcquireWithTimeout 使用 withTimeoutOrNull

- [x] Task 16: 修复 activeRequests/requestCountSinceAdjustment 非原子操作
  - 文件: `network/AdaptiveConcurrencyController.kt` 第 61/64/88/100/103 行
  - 修复: 改为 AtomicInteger

- [x] Task 17: 修复 FullDuplexAudioEngine.engineScope 永不取消
  - 文件: `interview/FullDuplexAudioEngine.kt` 第 172 行
  - 修复: disconnect() 中调用 engineScope.cancel()

- [x] Task 18: 修复 VoiceConversationEngine 孤立 CoroutineScope
  - 文件: `interview/VoiceConversationEngine.kt` 第 579/714/832/922 行
  - 修复: 所有 CoroutineScope(Dispatchers.IO) 改为使用引擎内部 scope

- [x] Task 19: 修复 KnowledgeGraph IDF 恒为 1.0
  - 文件: `note/KnowledgeGraph.kt` 第 212 行
  - 修复: 实现真实 IDF 计算 (log(N/df))

- [x] Task 20: 修复 safeAddColumn 吞掉所有异常
  - 文件: `note/NoteDatabase.kt` 第 100-106 行
  - 修复: 只 catch 包含 "duplicate column" 的 SQLiteException

- [x] Task 21: 优化保存时知识图谱计算（防抖/延迟）
  - 文件: `note/NoteRepository.kt` 第 94-106 行
  - 修复: linkNote 改为延迟执行或防抖（保存后 5 秒无新保存才触发）

- [x] Task 22: 修复 AutoSproutWorker 不更新 note.sproutReportJson
  - 文件: `service/AutoSproutWorker.kt` 第 130-159 行
  - 修复: 发芽完成后同时更新 note.sproutReportJson

- [x] Task 23: 修复 MimoTtsClient 硬编码 api-key 认证头
  - 文件: `tts/MimoTtsClient.kt` 第 128-132 行
  - 修复: 复用 MimoAsrEngine.buildAuthHeader() 的逻辑

- [x] Task 24: 修复 EditorTeamService.isCancelled 非线程安全
  - 文件: `mcp/editors/EditorTeamService.kt` 第 82 行
  - 修复: 改为 AtomicBoolean

- [x] Task 25: 修复 WorkflowStorage 非线程安全
  - 文件: `mcp/editors/EditorRole.kt` 第 429-451 行
  - 修复: 改为 ConcurrentHashMap

- [x] Task 26: 修复 evaluateCondition 运算符匹配顺序错误
  - 文件: `mcp/editors/EditorTeamService.kt` 第 530-534 行
  - 修复: 先检查 >= 和 <=，再检查 > 和 <

- [x] Task 27: 修复 MimoAsrEngine.detectTrailingSilence 读取共享缓冲区不同步
  - 文件: `stt/MimoAsrEngine.kt` 第 466-501 行
  - 修复: 在 synchronized(audioBuffer) 中读取

- [x] Task 28: 修复 LlmClient.streamMessages 中字符串插值错误
  - 文件: `network/LlmClient.kt` 第 1036 行
  - 修复: `$response.code` → `${response.code}`

## Phase 3: P2 — 死代码清理/代码质量 (5 项)

- [x] Task 29: 清理 ToolExecutor 遗留方法 (executeOpenBrowser/executeQuestion 等)
  - 文件: `network/ToolExecutor.kt` 第 329-377 行

- [x] Task 30: 删除 LlmClient.isDeepSeekV4 重复方法
  - 文件: `network/LlmClient.kt` 第 121-123 行

- [x] Task 31: 删除 InterviewInputBar 死代码
  - 文件: `ui/InterviewScreen.kt` 第 1191-1324 行

- [x] Task 32: 合并 editDistance 重复定义
  - 文件: `mcp/editors/EditorTeamService.kt` 第 853 行 + `EditorRole.kt` 第 389 行

- [x] Task 33: 提取硬编码魔法数字为命名常量
  - 文件: RunCalendarTool.kt, LlmClient.kt, SmartCircuitBreaker.kt

# Task Dependencies

- Task 1-14 互相独立，可并行执行
- Task 15-28 互相独立，可并行执行，但建议在 Phase 1 完成后进行
- Task 29-33 互相独立，可在任意时间执行
- Task 15 (AdaptiveConcurrency) 的优先级队列修复需要先确认 Task 16 (AtomicInteger) 完成

# 并行执行策略

- Agent 1: Tasks 1-3 (STT/笔记 JSON)
- Agent 2: Tasks 4-5, 28 (网络层连接/熔断器)
- Agent 3: Tasks 6-8 (面试模式)
- Agent 4: Tasks 9, 24-26 (编辑团队)
- Agent 5: Tasks 10-11 (技能/TTS 安全)
- Agent 6: Tasks 12-14, 19-22 (笔记系统/发芽)
- Agent 7: Tasks 15-18 (并发控制器/音频引擎)
- Agent 8: Tasks 23, 27 (MiMO TTS/ASR)

# 执行记录

- 第一轮 (8 agent 并行): Agent 4, 5 成功；Agent 1,2,3,6,7,8 结果丢失
- 第二轮 (8 agent 并行): Agent 1,2,3,6,7,8 再次结果丢失
- 第三轮 (4 agent 并行): 全部 4 个 agent 成功，33/33 任务完成
- 编译验证: BUILD SUCCESSFUL (0 errors, 0 warnings)
