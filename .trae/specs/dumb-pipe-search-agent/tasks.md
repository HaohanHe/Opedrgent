# Tasks

- [x] Task 1: 简化 WebSearchTool 查询预处理
  - [x] 1.1: 审查并大幅简化 `sanitizeQuery`，移除停用词删除、单字过滤、中文 20 字截断
  - [x] 1.2: 保留查询原始空格结构，尊重 LLM 的短语分词
  - [x] 1.3: 添加测试用例验证 `"吉利跨时代人才跃迁计划 2026 招聘"` 不会被拆碎
  - [x] 1.4: 编译验证

- [x] Task 2: 实现标题摘要优先的研究流程
  - [x] 2.1: 修改 `WebSearchTool.executeWebSearch` 支持只返回标题+摘要（浅搜索模式）
  - [x] 2.2: 新增/扩展工具参数让 LLM 可选择是否进入深抓阶段
  - [x] 2.3: 实现 LLM 选择 URL 后的批量深抓（按 LLM 点菜抓取）
  - [x] 2.4: 编译验证

- [x] Task 3: 工具超时返回部分结果
  - [x] 3.1: 给 `ReadUrlTool.executeReadUrl` 添加 15 秒独立超时
  - [x] 3.2: 给 `WebSearchTool` 内单个 URL 抓取添加 10-15 秒独立超时
  - [x] 3.3: 超时后返回已获取片段 + `PARTIAL_TIMEOUT` 状态
  - [x] 3.4: 编译验证

- [x] Task 4: AgentService 工具失败语义化
  - [x] 4.1: 将工具执行异常转换为结构化状态：`SUCCESS` / `PARTIAL_TIMEOUT` / `TIMEOUT` / `RATE_LIMIT` / `FATAL_ERROR`
  - [x] 4.2: 只有 `FATAL_ERROR` 终止循环，其他状态作为观察消息返回
  - [x] 4.3: 更新系统提示，告知 LLM"单个工具失败不代表研究结束"
  - [x] 4.4: 编译验证

- [x] Task 5: 分层 Guardrail + 软劝导
  - [x] 5.1: 重构 `ToolCallGuardrail` 为三层：工具级 block、Agent 级 halt、会话级 halt
  - [x] 5.2: 添加失败分类：瞬态错误 vs 真实失败
  - [x] 5.3: Guardrail 触发时先进入 LLM 反思轮，不改策略再硬停
  - [x] 5.4: 编译验证

- [x] Task 6: 研究检查点持久化
  - [x] 6.1: 定义 `ResearchCheckpoint` 数据模型
  - [x] 6.2: 每轮结束后把 LoopContext 关键状态写入 ResearchStore
  - [x] 6.3: 会话续作时加载检查点并注入续作上下文到 system prompt
  - [x] 6.4: 编译验证

- [x] Task 7: 全量编译验证 assembleDebug

# Task Dependencies
- Task 2 depends on Task 1
- Task 4 depends on Task 3
- Task 5 depends on Task 4
- Task 6 can run in parallel with Task 5
- Task 7 depends on all previous tasks
