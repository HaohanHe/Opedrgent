# Opedrgent Prompt 架构改进规范

## Why

当前 Opedrgent 的提示词系统存在以下问题：
1. System prompt 无缓存机制，每次请求都重新发送全部内容
2. Tool prompt 与业务代码紧耦合，缺乏标准化接口
3. 无记忆系统，每次会话从零开始
4. 无 Skill 外部化机制，心跳/自动化逻辑硬编码

基于对 Claude Code 的深度研究，其提示词架构提供了一套成熟的解决方案。

## What Changes

### Phase 3.1: Prompt 缓存分段

1. **引入 `PROMPT_CACHE_BOUNDARY` 标记**
   - 将 System prompt 分为静态部分和动态部分
   - 静态部分可跨用户缓存，降低 API 成本

2. **节段注册机制**
   - `PromptSection` 接口，支持缓存/非缓存两种模式
   - `resolvePromptSections()` 统一解析，命中缓存的节段不重算

### Phase 3.2: Tool Prompt 标准化

3. **工具接口规范化**
   - 每个工具定义 `prompt.ts`，包含 description + 使用指南
   - 统一注入到 ToolExecutor，不在 ViewModel 中拼接字符串

4. **权限配置外部化**
   - 沙箱配置、安全规则写入工具 prompt
   - 运行时通过 PermissionConfig 读取

### Phase 3.3: 记忆系统分层

5. **引入 CLAUDE.md 机制**
   - 项目根目录的 `CLAUDE.md` 作为项目级指令
   - 工具操作文件时，沿目录树向上查找并加载

6. **会话记忆扩展**
   - 利用现有 SQLite 会话存储
   - 按类型（User/Project/Reference）组织记忆

### Phase 3.4: Skill 外部化

7. **心跳自动化改为 Skill**
   - `/research` 心跳逻辑抽取为 Markdown Skill
   - 用户可自定义触发条件

## Impact

- **Affected specs**: `opedrgent-improvement` (Phase 3)
- **Affected code**: MainViewModel.kt, ToolExecutor.kt, AppRoot.kt
- **Breaking changes**: Tool prompt 格式变更，旧格式需迁移

## ADDED Requirements

### Requirement: Prompt 缓存分段

#### Scenario: 首次会话启动
- **WHEN** 用户启动应用发起首次请求
- **THEN** 静态 prompt 部分使用 `scope: 'global'` 缓存

#### Scenario: 后续请求
- **WHEN** 用户继续会话
- **THEN** 动态部分（记忆、上下文）单独更新，静态部分复用缓存

### Requirement: Tool Prompt 外部化

#### Scenario: 搜索工具执行
- **WHEN** ToolExecutor 调用搜索工具
- **THEN** 从 `SearchTool/prompt.ts` 读取提示词，而非硬编码字符串

### Requirement: CLAUDE.md 加载

#### Scenario: 用户编辑项目文件
- **WHEN** 用户执行 Edit 操作
- **THEN** 系统沿目录向上查找并加载 `CLAUDE.md`，注入上下文

## MODIFIED Requirements

### Requirement: 会话持久化
**原要求**: 会话仅存储消息历史
**新要求**: 会话同时存储结构化记忆，支持按类型检索

## REMOVED Requirements

### Requirement: 心跳硬编码
**Reason**: 心跳逻辑应外部化为 Skill，由用户配置触发条件
**Migration**: 将现有心跳代码迁移至 `skills/research.md`

## 实施优先级

| 优先级 | 任务 | 依赖 |
|--------|------|------|
| P1 | Prompt 分段 + 节段注册 | Phase 1-2 完成 |
| P1 | Tool Prompt 外部化 | P1 |
| P2 | CLAUDE.md 加载 | P1 |
| P2 | 会话记忆结构化 | P1 |
| P3 | Skill 外部化 | P2 |

## 参考来源

- Claude Code 提示词架构: `参考/claude/prompt-architecture.md`
- 核心实现: `src/constants/prompts.ts`, `src/utils/attachments.ts`
