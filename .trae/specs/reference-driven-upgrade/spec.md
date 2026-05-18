# 参考项目驱动全面升级 Spec

## Why

经过对 `e:\proj\Opedrgent\参考\` 目录下11个顶级开源项目（Claude Code、Koog、SearXNG、Qdrant、MeiliSearch、Hermes Agent、OpenCode、KiloCode、ML-Intern、Open WebUI、MiMo-Skills）的深度研究，Opedrgent 当前对齐度如下：

| 模块 | 对齐度 | 参考目标 |
|------|--------|----------|
| 工具注册系统 | 40% | Koog @Tool注解 |
| Prompt架构 | 85% | Claude Code 静态/动态边界 |
| Agent循环 | 30% | Koog DSL图工作流 |
| 记忆系统 | 10% | Qdrant RAG |
| Skill系统 | 60% | Hermes Agent |

核心差距：**工具系统还是手写巨型when分支（700行ToolExecutor）**，而Koog用`@Tool`注解+反射自动注册只需50行。

## What Changes

### Phase 1: 工具系统升级（照Koog）
- **BREAKING**: ToolExecutor.kt 的 `execute()` 从巨型when分支重构为 `@Tool` 注解驱动
- 新建 `tools/` 包，每个工具独立文件
- 保留现有 `executeWebSearch()` 等方法逻辑，仅改调用路由方式
- `ToolCallParser` 输出格式不变（XML `<tool_call>` → ToolPart 指令）

### Phase 2: Prompt架构增强（照Claude Code）
- `PromptBuilder.kt` 增加静态/动态缓存边界标记
- 增强工具优先引导：prompt中明确"用X工具而非手动Y"
- 输出效率约束：between calls ≤25 words

### Phase 3: 搜索算法调优（照SearXNG）
- 验证并调优已实现的 `getSortedResults()` 分组打散效果
- 评分引擎权重: 加法 → 累积乘法
- 增加搜索来源多样性日志

### Phase 4: Agent循环升级（照Koog 图工作流）
- 从 `while(hasToolCalls)` 硬编码升级为 `AgentStrategy` DSL
- 保持向后兼容：新策略通过配置开关启用

### Phase 5: 记忆系统升级（照Qdrant/MeiliSearch）
- `MemoryStore` 升级为分层记忆：短期KV + 长期摘要 + RAG检索
- 新增对话记忆自动摘要（`ContextCompressor` 增强版已就绪）

### Phase 6: Skill系统增强（照Hermes）
- Skill定义支持子能力（类似MiMo TTS → synthesize/voicedesign/voiceclone）
- 新增 `Reasoning标签清理器`（处理模型输出中的`<thinking>`等干扰标签）

### Phase 7: UI/UX增强（照Open WebUI）
- 工具调用状态内联显示 `[✅ web_search 完成 (3.2s)]`
- 对话流中Markdown渲染增强

## Impact

- Affected specs: 叠加 `opedrgent-improvement/` + `websearcher-searxng-upgrade/` 的已有成果
- Affected code: `ToolExecutor.kt`(重构)、`PromptBuilder.kt`(增强)、`ToolCallParser.kt`(增强)、`MemoryStore.kt`(升级)
- 新建文件: `tools/*Tool.kt`(6个)、`agent/ResearchStrategy.kt`、`agent/HierarchicalMemory.kt`
- **不改变**: ToolPart/ToolState 模型、LLM通信层、MainViewModel 主循环接口
- 向后兼容：所有现有API调用方式保持不变

## ADDED Requirements

### Requirement: @Tool注解工具注册

系统 SHALL 使用Kotlin反射（`@Tool` + `@ToolDescription` 注解）自动注册工具，替代 hand-written when 分支。照 Koog 的 `ToolSet` + `ToolRegistry` 模式。

参考来源：[koog.md §2.1](e:\proj\Opedrgent\参考\_research\koog.md) — [AIAgent.kt §examples](e:\proj\Opedrgent\参考\koog-0.8.0\examples\simple-examples)

#### Scenario: 工具注册自动发现
- **WHEN** ToolRegistry初始化时
- **THEN** 系统 SHALL 扫描所有注册的ToolSet实例，反射收集每个 `@Tool` 注解的方法
- **AND** 将方法名和 `@LLMDescription` 中的描述注册为可用工具

#### Scenario: 工具路由（替代when分支）
- **WHEN** LLM返回 `<tool_call name="web_search">`
- **THEN** 系统 SHALL 通过 toolName 查找已注册工具并直接调用，而非进入 `when(toolName)` 53分支判断
- **AND** 工具SHALL拥有编译期类型安全（`suspend fun webSearch(query: String): List<SearchResult>`）

### Requirement: Prompt静态/动态边界

系统 SHALL 在系统提示词中增加明确的缓存边界标记 `__PROMPT_CACHE_BOUNDARY__`，将不随会话变化的内容标记为可缓存。

参考来源：[claude-code.md §1.1](e:\proj\Opedrgent\参考\_research\claude-code.md) — [prompts.ts L1-L914](e:\proj\Opedrgent\参考\claude\constants\prompts.ts)

#### Scenario: 缓存边界标记
- **WHEN** PromptBuilder.buildSystemPrompt() 生成提示词时
- **THEN** 静态部分（工具列表、通用规则、角色定义） SHALL 标记在 `PROMPT_CACHE_BOUNDARY` 之前
- **AND** 动态部分（上下文摘要、搜索结果、位置信息） SHALL 标记在边界之后
- **AND** PromptCache.cacheStatic() SHALL 仅缓存边界之前的内容

### Requirement: Reasoning标签自动清理

系统 SHALL 在工具调用前自动清理模型输出中的 reasoning/thinking 标签（`<thinking>`, `<think>`, `<reasoning>`, `<scratchpad>` 等），不将思考内容误解析为工具调用。

参考来源：[hermes-agent.md §2.2](e:\proj\Opedrgent\参考\_research\hermes-agent.md) — [cli.py reasoning清理](e:\proj\Opedrgent\参考\hermes-agent-2026.5.7\cli.py)

#### Scenario: 思考标签包围XML工具调用
- **WHEN** LLM输出包含 `<thinking>...</thinking>` 包裹的XML
- **THEN** ToolCallParser SHALL 先清理所有 reasoning 标签，再执行XML解析
- **AND** 不应因 `<thinking>` 标签导致 `TOOL_CALL_REGEX` 匹配失败

### Requirement: Agent图工作流（可选启用）

系统 SHALL 支持声明式 DSL 描述Agent行为图，替代硬编码 while 循环（通过配置开关启用，默认关闭保持兼容）。

参考来源：[koog.md §2.2](e:\proj\Opedrgent\参考\_research\koog.md) — [TripPlanning Agent.kt](e:\proj\Opedrgent\参考\koog-0.8.0\examples\trip-planning-example)

#### Scenario: 深度研究图策略
- **WHEN** Agent配置使用图策略模式时
- **THEN** 系统 SHALL 按 `nodeStart → search → analyze → generateReport → nodeFinish` 声明式流程执行
- **AND** 节点间SHALL有类型安全的数据传递

### Requirement: 搜索来源多样性日志

系统 SHALL 在返回搜索结果时附带来源多样性统计信息，帮助验证分组打散算法的效果。

#### Scenario: 来源多样性验证
- **WHEN** `getSortedResults()` 返回结果列表时
- **THEN** 系统 SHALL 在 DebugLog 中输出各来源（域名/引擎）的分布统计
- **AND** 格式 SHALL 为: `getSortedResults: sources=[baidu:3, bing:3, 360:2, sogou:2], total=10`

### Requirement: 对话记忆自动摘要注入

系统 SHALL 在连续对话中自动生成并注入历史摘要（复用已实现的 ContextCompressor 增强版），格式遵循 Claude Code 的 "distill key info" 模式。

参考来源：[claude-code.md §2.2](e:\proj\Opedrgent\参考\_research\claude-code.md)

#### Scenario: 长对话摘要注入
- **WHEN** 对话超过20轮时
- **THEN** 系统 SHALL 在prompt中注入 "[对话摘要]" 段，总结早期交互
- **AND** 摘要 SHALL 使用 `generateEnhancedSummary()` (ContextCompressor中已实现)

## MODIFIED Requirements

### Requirement: 引擎评分公式

系统 SHALL 修改 `addResults` 中的 `calculateInitialScore` 实现 SearXNG 风格的 **权重累积乘法 + 线性位置衰减**（替代当前指数衰减）。

参考来源：[searxng-deep.md §2](e:\proj\Opedrgent\参考\_research\searxng-deep.md) — [results.py calculate_score](e:\proj\Opedrgent\参考\searxng\searx\results.py)

#### Scenario: 多引擎交叉验证加分
- **WHEN** 同一个URL被 Baidu AND Bing AND 360 同时返回且排名前3
- **THEN** 合并结果的 score SHALL 显著高于仅被单个引擎返回的结果
- **AND** 计算公式 SHALL 为: `weight *= engine_weight_for_each_source * len(positions)`

### Requirement: 分组打散效果验证

系统 SHALL 在 `getSortedResults()` 中实现并验证 SearXNG 的分组打散效果：同一域名来源的结果不应连续超过3个。

参考来源：[searxng-deep.md §1](e:\proj\Opedrgent\参考\_research\searxng-deep.md) — [results.py merge_two_main_results](e:\proj\Opedrgent\参考\searxng\searx\results.py)

#### Scenario: 分组打散验证
- **WHEN** 合并后有 baidu × 6, bing × 4, 360 × 2 总12条结果
- **THEN** 排序后的结果前6条 SHALL 至少有3个不同来源
- **AND** 同一来源不应连续出现超过2次