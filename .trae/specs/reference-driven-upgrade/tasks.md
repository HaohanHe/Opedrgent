# 参考项目驱动升级 - 任务列表

## Phase 1: 工具系统 `@Tool` 注解化（Koog模式）

- [x] **Task 1: 创建 Tool 注解体系**
  - [x] 创建 `tools/ToolAnnotations.kt`：定义 `@Tool`, `@ToolDescription`, `ToolSet` 接口
  - [x] 仿照 Koog: [@Tool注解](e:\proj\Opedrgent\参考\koog-0.8.0\agents\agents-tools\src\main\kotlin\ai\koog\agents\tools\Tool.kt)
  - [x] 仿照 Koog: [WebSearchAgent 示例](e:\proj\Opedrgent\参考\koog-0.8.0\examples\simple-examples)

- [x] **Task 2: 逐个迁移工具到独立 ToolSet 类**
  - [x] 创建 `tools/WebSearchTool.kt`：迁移 `executeWebSearch()` 逻辑
  - [x] 创建 `tools/OpenBrowserTool.kt`：迁移 `executeWebBrowser()` 逻辑
  - [x] 创建 `tools/DeepResearchTool.kt`：迁移 `executeDeepResearch()` 逻辑
  - [x] 创建 `tools/ReadUrlTool.kt`：迁移 `executeReadUrl()` 逻辑
  - [x] 创建 `tools/GenerateReportTool.kt`：迁移 `executeGenerate()` 逻辑
  - [x] 创建 `tools/MimoTtsTool.kt`：迁移 `executeMimoTts()` 逻辑
  - [x] 创建 `tools/ReverseGeocodeTool.kt`：迁移 `reverseGeocode()` 逻辑
  - 写代码时每个工具类单独读取 `ToolExecutor.kt` 中对应方法的完整实现，**先照抄不改逻辑**，只改函数签名和注解

- [x] **Task 3: 创建 ToolRegistry + 适配 ToolExecutor**
  - [x] 创建 `tools/ToolRegistry.kt`：反射扫描 `@Tool` 方法，toolName → KFunction 路由
  - [x] 修改 `ToolExecutor.kt`：`execute()` 从 when 分支改为 `registry.invoke(toolName, params)`
  - [x] 保留 PermissionEngine 权限检查层（在 registry.invoke 之前）
  - [x] **保留所有现有方法体**（不删旧代码，加 `@Deprecated` 标记以便回退）

- [x] **Task 4: 编译验证 + 回归测试**
  - [x] 运行 `gradlew assembleDebug` 确认编译通过
  - [x] 验证 web_search 工具调用结果与重构前一致（通过DebugLog对比）
  - [x] 验证 mimo_tts 工具调用结果与重构前一致
  - [x] 验证 PermissionEngine 权限检查仍在生效

## Phase 2: Prompt架构增强（Claude Code模式）

- [x] **Task 5: Prompt静态/动态边界**
  - [x] 修改 `PromptBuilder.kt`：增加 `buildStaticPrompt()` 方法输出**可缓存**内容
  - [x] 修改 `PromptBuilder.kt`：增加 `buildDynamicPrompt()` 方法输出**会话级**内容
  - [x] 仿照 Claude Code: [prompts.ts getSimpleIntroSection](e:\proj\Opedrgent\参考\claude\constants\prompts.ts)
  - [x] 在 `PromptCache.kt` 中仅缓存 `buildStaticPrompt()` 结果
  - [x] 编译验证

- [x] **Task 6: 工具优先引导强化**
  - [x] 在 PromptBuilder 工具列表段增加引导：
    - "To read URLs use read_url instead of manually fetching"
    - "To search the web use web_search instead of browsing"
  - [x] 仿照 Claude Code: "Read files with FileRead instead of cat/head/tail"
  - [x] 编译验证

- [x] **Task 7: 输出效率约束**
  - [x] 在 PromptBuilder Output Rules 段增加数字锚点：between calls reasoning ≤ 25 words
  - [x] 仿照 Claude Code: output efficiency guidelines
  - [x] 编译验证

## Phase 3: 搜索算法调优（SearXNG模式）

- [x] **Task 8: 来源多样性日志**
  - [x] 在 `SearchResultContainer.getSortedResults()` 增加 DebugLog：
    - 输出格式: `sources=[baidu:3, bing:3, ...], total=N`
    - 输出每个来源的域名/引擎分布
  - [x] 编译验证

- [x] **Task 9: 验证分组打散效果**
  - [x] 在 `SearchResultContainer.getSortedResults()` 后增加断言检查
  - [x] 验证同一域名前缀连续不超过3次
  - [x] 如果分组失败（全来自同一来源），降级为按分数排序

## Phase 4: Agent循环 DSL（Koog图工作流模式）

- [x] **Task 10: 创建 ResearchStrategy DSL**
  - [x] 创建 `agent/ResearchStrategy.kt`：`strategy { nodeStart → search → analyze → report }`
  - [x] 仿照 Koog: [Agent.kt DSL](e:\proj\Opedrgent\参考\koog-0.8.0\examples\trip-planning-example)
  - [x] 实现 `singleRunStrategy()` 和 `deepResearchStrategy()` 两个预设
  - [x] 通过 `agentConfig.strategyMode` 开关启用（默认关闭 = 保持现有while循环）

## Phase 5: 记忆系统增强（Qdrant模式设计）

- [x] **Task 11: 分层记忆设计**
  - [x] 创建 `agent/HierarchicalMemory.kt`：
    - Layer 1: 短期 KV（当前 MemoryStore 逻辑）
    - Layer 2: 长期摘要（当前 ContextCompressor 摘要逻辑，未来对接 Qdrant）
    - Layer 3: RAG 检索接口（留空，等待服务器部署 Qdrant）
  - [x] 修改 `ContextCompressor.generateEnhancedSummary()` 调用新摘要层
  - [x] 编译验证

## Phase 6: Skill系统增强（Hermes模式）

- [x] **Task 12: Reasoning标签清理器**
  - [x] 在 `ToolCallParser.kt` 增加 `cleanReasoningTags()` 预处理：
    - 清理标签: `<thinking>`, `<think>`, `<reasoning>`, `<thought>`, `<scratchpad>`
  - [x] 仿照 Hermes: [cli.py strip_reasoning_tags](e:\proj\Opedrgent\参考\hermes-agent-2026.5.7\cli.py)
  - [x] 编译验证

- [x] **Task 13: Skill子能力支持**
  - [x] 在 `SkillSystem.kt` 增加子能力结构：
    - MiMo TTS: synthesize / voicedesign / voiceclone
  - [x] 增强 `BuiltinSkillLoader.kt` 支持嵌套子技能
  - [x] 编译验证

## Phase 7: 最终编译验证

- [x] **Task 14: 全量编译 + 回归测试**
  - [x] 运行 `gradlew assembleDebug` 确认所有变更编译通过
  - [x] 检查 deprecation warnings（应为旧 ToolExecutor 方法标记引起）
  - [x] 运行所有已有测试（如有）
  - [x] 生成测试版 APK

# Task Dependencies

```
Phase 1 (工具注解) ──→ Phase 2 (Prompt增强) ──→ Phase 3 (搜索调优)
                                      ↓
Phase 4 (Agent DSL) ←── 独立可并行
Phase 5 (分层记忆) ←── 独立可并行
Phase 6 (Skill增强) ←── 独立可并行
                                      ↓
                                Phase 7 (全量验证)
```

- Task 1-3 是Phase 1核心，**必须顺序执行**: Task1 → Task2 → Task3
- Task 4 依赖 Task 1-3 完成
- Task 5-9 可与 Task 1-4 并行（不同文件，无冲突）
- Task 10-13 完全独立，可并行执行
- Task 14 依赖所有前序任务完成
