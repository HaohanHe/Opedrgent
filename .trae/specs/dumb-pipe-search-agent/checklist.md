# Checklist

- [x] `sanitizeQuery` 不再拆分短语、删除停用词、截断中文查询
- [x] `"吉利跨时代人才跃迁计划 2026 招聘"` 能作为整体短语被搜索引擎处理
- [x] WebSearchTool 支持浅搜索（仅标题摘要）和 LLM 选择后的深抓
- [x] ReadUrlTool 有独立 15 秒超时
- [x] WebSearchTool 内单个 URL 抓取有 10-15 秒独立超时
- [x] 工具超时返回 `PARTIAL_TIMEOUT` 状态和已获取片段
- [x] AgentService 中工具失败被结构化为 SUCCESS / PARTIAL_TIMEOUT / TIMEOUT / RATE_LIMIT / FATAL_ERROR
- [x] 只有 FATAL_ERROR 终止 Agent 循环
- [x] 系统提示明确告知 LLM"单个工具失败不代表研究结束"
- [x] ToolCallGuardrail 支持工具级 block、Agent 级 halt、会话级 halt 三层决策
- [x] Guardrail 能区分瞬态错误和真实失败
- [x] Guardrail 触发时先进入 LLM 反思轮
- [x] 每轮结束后 LoopContext 关键状态被持久化到 ResearchStore
- [x] 会话续作时加载检查点并注入续作上下文
- [x] 系统不再替 LLM 预过滤来源权威性（仅过滤非法/恶意链接）
- [x] assembleDebug 编译通过
