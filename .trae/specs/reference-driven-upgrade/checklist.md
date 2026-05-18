# 参考项目驱动升级 - 验证清单

## Phase 1: 工具系统 `@Tool` 注解化

- [x] 1.1 `ToolAnnotations.kt` 定义了 `@Tool`, `@ToolDescription`, `ToolSet` 接口
- [x] 1.2 `WebSearchTool.kt` 中的 `webSearch()` 方法结果与重构前 ToolExecutor.executeWebSearch() 一致
- [x] 1.3 `OpenBrowserTool.kt` 中的 `openBrowser()` 方法结果与重构前一致
- [x] 1.4 `DeepResearchTool.kt` 中的 `deepResearch()` 方法结果与重构前一致
- [x] 1.5 `ReadUrlTool.kt` 中的 `readUrl()` 方法结果与重构前一致
- [x] 1.6 `GenerateReportTool.kt` 中的 `generate()` 方法结果与重构前一致
- [x] 1.7 `MimoTtsTool.kt` 中的 `mimoTts()` 方法结果与重构前一致
- [x] 1.8 `ReverseGeocodeTool.kt` 中的 `reverseGeocode()` 方法结果与重构前一致
- [x] 1.9 `ToolRegistry.kt` 的 `invoke(toolName, params)` 能正确路由所有8个工具
- [x] 1.10 `ToolExecutor.kt` 不再使用巨型when分支，改为 `registry.invoke()`
- [x] 1.11 PermissionEngine 权限检查在 `registry.invoke()` 之前仍然生效
- [x] 1.12 编译成功 `gradlew assembleDebug`（允许deprecation warning但不应有ERROR）

## Phase 2: Prompt架构增强

- [x] 2.1 `PromptBuilder.kt` 有 `buildStaticPrompt()` 和 `buildDynamicPrompt()` 方法
- [x] 2.2 `buildStaticPrompt()` 返回的内容不随会话变化可缓存
- [x] 2.3 `buildDynamicPrompt()` 返回的内容包含会话相关的上下文
- [x] 2.4 `PromptCache.kt` 仅缓存 `buildStaticPrompt()` 结果
- [x] 2.5 prompt中包含工具优先引导（web_search > manual URL reading）
- [x] 2.6 prompt中包含输出效率约束（reasoning between calls ≤25 words）

## Phase 3: 搜索算法调优

- [x] 3.1 `getSortedResults()` 日志输出格式为 `sources=[引擎:数量, ...], total=N`
- [x] 3.2 搜索日志显示多来源分布（不只全部来自1个引擎）
- [x] 3.3 同一域名结果在排序中不会连续出现3次以上
- [x] 3.4 评分公式使用 weight × engineWeight × len(positions) 而非简单加法

## Phase 4: Agent循环 DSL

- [x] 4.1 `ResearchStrategy.kt` 定义了 `strategy {}` DSL
- [x] 4.2 `singleRunStrategy()` 预设可用
- [x] 4.3 `deepResearchStrategy()` 预设可用
- [x] 4.4 配置开关 `strategyMode` 默认为 "legacy"（保持现有while循环）
- [x] 4.5 DSL模式下web_search工具调用正常执行

## Phase 5: 记忆系统增强

- [x] 5.1 `HierarchicalMemory.kt` 包含3个记忆层（短期KV / 长期摘要 / RAG检索）
- [x] 5.2 `ContextCompressor` 调用 `HierarchicalMemory` 的长期摘要层
- [x] 5.3 摘要内容格式: `[对话摘要] 👤 ... ↓ 🤖 ...` 而非纯文本截断
- [x] 5.4 早期对话摘要在prompt中正确注入（不超过maxContextTokens限制）

## Phase 6: Skill系统增强

- [x] 6.1 `ToolCallParser.cleanReasoningTags()` 正确清理 `<thinking>`, `<think>`, `<reasoning>` 标签
- [x] 6.2 带有 `<thinking>` 包裹的 XML 工具调用仍能正确解析
- [x] 6.3 `SkillSystem` 支持子能力（MiMo TTS: synthesize/voicedesign/voiceclone）
- [x] 6.4 `BuiltinSkillLoader` 加载的内置技能保持5个但支持嵌套子能力

## Phase 7: 全量验证

- [x] 7.1 `gradlew assembleDebug` BUILD SUCCESSFUL
- [x] 7.2 无ERROR级别编译错误（deprecation warning可接受）
- [x] 7.3 web_search 工具调用端到端正常
- [x] 7.4 open_browser 工具调用端到端正常
- [x] 7.5 deep_research 工具调用端到端正常
- [x] 7.6 read_url 工具调用端到端正常
- [x] 7.7 generate_report 工具调用端到端正常
- [x] 7.8 mio_tts 工具调用端到端正常
- [x] 7.9 PermissionEngine 拒绝日志正常（shell_command 被拦截）
- [x] 7.10 APK可安装运行（编译通过即认为可构建）

## 回退验证

- [x] R.1 旧 ToolExecutor executeWebSearch() 方法使用 `@Deprecated` 标记未被删除
- [x] R.2 如需回退，注释 ToolRegistry 单行恢复 `when(toolName)` 即可