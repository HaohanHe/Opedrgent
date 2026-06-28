# Checklist

## Phase 1: P0 验证

- [x] Task 1: AudioProcessor.saveAsWav 输出的 WAV 文件头 4 字节偏移为 "WAVE" (0x57415645)
- [x] Task 2: VocabularyStore.applyVocabulary("你好") 输入含词表词汇时输出不同
- [x] Task 3: SproutReport.fromJson 含 sentiment="positive" 不抛异常，返回 NEUTRAL
- [x] Task 4: LlmClient streamMultimodal/streamMessages 异常路径 response 已关闭
- [x] Task 5: SmartCircuitBreaker.allowRequest() 在 HALF_OPEN 状态下不超过 halfOpenMaxProbes
- [x] Task 6: interviewTranscript 多协程并发 add/get 不抛 ConcurrentModificationException
- [x] Task 7: sendInterviewAnswer 后 duplexState/isMuted 不被重置为默认值
- [x] Task 8: generateFinalReport 抛异常后 voiceEngine 已停止
- [x] Task 9: DynamicRole("任何角色名").displayColor 不抛 ArrayIndexOutOfBoundsException
- [x] Task 10: SkillWebViewExecutor 注入含 `"` `</script>` 的 params 不执行恶意代码
- [x] Task 11: TtsPlayer.isSpeaking 使用 AtomicBoolean；speakWithMimo 的 scope 可被 cancel
- [x] Task 12: ghostTextJob.cancel() 后无残留的 onRequestCompletion API 请求
- [x] Task 13: InsightSproutEngine.phaseCache 使用 ConcurrentHashMap
- [x] Task 14: sproutBatch 并发数不超过 2（通过 Semaphore 或其他机制）

## Phase 2: P1 验证

- [x] Task 15: AdaptiveConcurrencyController.enqueueAndWait 的 continuation 会被 resume；tryAcquireWithTimeout 超时后返回 false
- [x] Task 16: activeRequests 使用 AtomicInteger，incrementAndGet/decrementAndGet
- [x] Task 17: FullDuplexAudioEngine.disconnect() 调用 engineScope.cancel()
- [x] Task 18: VoiceConversationEngine 内部无独立 CoroutineScope(Dispatchers.IO) 创建
- [x] Task 19: KnowledgeGraph IDF 不全为 1.0（至少对高频词 IDF < 1.0）
- [x] Task 20: safeAddColumn 只 catch "duplicate column" 相关异常
- [x] Task 21: saveNote 中 linkNote 有防抖或延迟机制（非每次保存立即执行）
- [x] Task 22: AutoSproutWorker 发芽完成后 note.sproutReportJson 已更新
- [x] Task 23: MimoTtsClient 根据 API Key 前缀选择正确的认证头
- [x] Task 24: EditorTeamService.isCancelled 使用 AtomicBoolean
- [x] Task 25: WorkflowStorage 使用 ConcurrentHashMap
- [x] Task 26: evaluateCondition("output_length >= 1000") 当 output_length=1000 时返回 true
- [x] Task 27: MimoAsrEngine.detectTrailingSilence 在 synchronized(audioBuffer) 中读取
- [x] Task 28: LlmClient.streamMessages 日志输出状态码数字而非 Response.toString()

## Phase 3: P2 验证

- [x] Task 29: ToolExecutor 中无 executeOpenBrowser/executeQuestion/executeReverseGeocode/executeGenerate 方法
- [x] Task 30: LlmClient 中无 isDeepSeekV4 方法
- [x] Task 31: InterviewScreen.kt 中无 InterviewInputBar composable
- [x] Task 32: editDistance/levenshteinDistance 只保留一份实现
- [x] Task 33: RunCalendarTool/LlmClient/SmartCircuitBreaker 中无硬编码时间常量

## 编译验证

- [x] `./gradlew compileDebugKotlin` BUILD SUCCESSFUL
- [x] 无新增 warning
