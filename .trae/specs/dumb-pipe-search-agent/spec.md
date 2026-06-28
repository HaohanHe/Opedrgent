# Dumb-Pipe Search & Agent 工具层重构 Spec

## Why
当前搜索与 Agent 工具层过度"聪明"：系统替 LLM 拆分关键词、过滤小网站、按来源权威性打分、嚼碎查询后只喂"精选"结果。这导致 LLM 拿到的是已经被破坏语义的信息，无法像真人调研员一样基于原始标题、摘要、全文自己判断该信什么、不信什么。用户抱怨"查一半停了""只查到吉利汽车""权威来源挑好了剩下不管"，根因都是工具层替 LLM 做了太多决定。

## What Changes
- 工具层从 smart filter 改为 dumb pipe：少加工、少过滤、多传递
- 移除/削弱 `sanitizeQuery` 对查询词的过度处理，尊重 LLM 原始查询和短语边界
- 第一轮搜索只返回大量标题+摘要，由 LLM 决定深抓哪些 URL
- 工具超时不再终止 Agent 循环，而是返回结构化部分结果让 LLM 继续
- Guardrail 从"一刀切 HALT"改为软劝导 + 分层决策
- Agent 中间状态持久化到 ResearchStore，支持断点续研
- 系统不再替 LLM 判断来源权威性，只过滤明显非法/恶意链接

## Impact
- Affected specs: websearcher-searxng-upgrade, opedrgent-improvement
- Affected code:
  - `tools/WebSearchTool.kt`
  - `tools/ReadUrlTool.kt`
  - `agent/AgentService.kt`
  - `utils/ToolCallGuardrail.kt`
  - `storage/ResearchStore.kt`
  - `network/WebSearcher.kt`
  - `network/SourceFetcher.kt`

## ADDED Requirements

### Requirement: 工具层做 Dumb Pipe
系统 SHALL 最小化对查询词和搜索结果的预处理，把原始、完整、多样的信息交给 LLM 判断。

#### Scenario: LLM 发送原始查询
- **WHEN** LLM 调用 `web_search` 并传入 `query="吉利跨时代人才跃迁计划 2026 招聘"`
- **THEN** 系统 SHALL 优先将该字符串整体或按 LLM 意图分块交给搜索引擎
- **AND** 不应拆分为 `吉利 / 跨时代 / 人才 / 跃迁 / 计划 / 2026 / 招聘` 独立搜索
- **AND** 不应删除查询中的停用词或空格分隔的短语

### Requirement: 标题摘要优先的深抓策略
系统 SHALL 先向 LLM 返回大量标题与摘要，由 LLM 选择要深入阅读的 URL，再执行全文抓取。

#### Scenario: 深度研究
- **WHEN** Agent 进入研究阶段
- **THEN** 系统 SHALL 搜索 30-80 条结果
- **AND** 只把标题、摘要、URL、时间返回给 LLM
- **AND** LLM SHALL 从中选择 5-10 个 URL 供系统深抓
- **AND** 系统 SHALL 仅抓取 LLM 选中的 URL

### Requirement: 工具超时返回部分结果
系统 SHALL 在工具调用超时时返回已获取的部分内容和超时状态，而不是终止整个研究循环。

#### Scenario: read_url 超时
- **WHEN** `read_url` 抓取网页超过 15 秒
- **THEN** 系统 SHALL 返回已抓取到的片段（如有）
- **AND** 返回状态 `PARTIAL_TIMEOUT`
- **AND** Agent 循环 SHALL 继续执行，不把超时视为终止事件

### Requirement: 分层 Guardrail
系统 SHALL 把 Guardrail 的硬 HALT 拆分为工具级 block、Agent 级 halt、会话级 halt，并给 LLM 反思机会。

#### Scenario: 单工具反复失败
- **WHEN** 某个工具连续失败
- **THEN** 系统 SHALL 先 block 该工具本轮调用
- **AND** LLM 仍可调用其他工具
- **AND** 只有全局失败模式出现时才触发会话级 halt

#### Scenario: Guardrail 触发
- **WHEN** Guardrail 检测到重复模式
- **THEN** 系统 SHALL 先要求 LLM 进入一次无工具调用的反思轮
- **AND** LLM 说明将如何调整策略
- **AND** 只有 LLM 拒绝调整或重复违规时才硬停止

### Requirement: 研究检查点持久化
系统 SHALL 在每一轮结束后把 LoopContext 关键状态写入 ResearchStore，支持断点续研。

#### Scenario: 研究被强制截断
- **WHEN** 研究因 MAX_ROUNDS、用户取消、guardrail 或进程被杀而中断
- **THEN** 系统 SHALL 保留已积累的 messages、工具结果、source 列表、当前 round、guardrail 快照
- **AND** 下次继续同一会话时，系统 SHALL 加载检查点并注入续作上下文

## MODIFIED Requirements

### Requirement: WebSearchTool 查询处理
现有 `sanitizeQuery` 对查询词的停用词删除、中文截断、单字过滤 SHALL 大幅削弱或移除。
- 保留基础清理：去除首尾空白、折叠连续空格
- 移除：按空格拆词后删除单字词、删除多字停用词、中文 20 字截断
- 如果查询被 LLM 用空格分好词，应尊重空格作为短语边界

### Requirement: AgentService 工具失败处理
现有 AgentService 中工具异常被 catch 成 `"工具执行失败: ${e.message}"` 后直接喂给 LLM 并累计 Guardrail 失败次数。
- 改为结构化失败状态
- 只有 FATAL_ERROR 才终止循环
- TIMEOUT / RATE_LIMIT / PARTIAL 等 SHALL 作为观察消息返回并允许继续

### Requirement: ToolCallGuardrail 决策逻辑
现有 Guardrail 以 `result.startsWith("工具执行失败")` 判断失败。
- 改为基于失败分类判断：瞬态网络错误、5XX、DNS、4XX、解析错误、权限错误等
- 瞬态错误不计入"重复无意义调用"计数
- 连续相同参数调用才触发 doom loop 检测

## REMOVED Requirements

### Requirement: 系统替 LLM 判断来源权威性
**Reason**: LLM 有能力自己判断来源可信度，系统预过滤会漏掉小网站、论坛、自媒体中的高价值信息。
**Migration**: 来源信息原样交给 LLM，由 LLM 在最终回答中标注可信度。系统仅做安全过滤（非法/恶意链接）。
