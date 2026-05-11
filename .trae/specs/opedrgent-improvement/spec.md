# Opedrgent 项目改进规范

## Why

根据日志分析和参考项目（Claude Code、Hermes、KiloCode、OpenCode）研究，Opedrgent 存在多个严重架构问题需要系统性修复，同时需要建立规范的需求变更流程，避免"一意孤行"式开发。

## 核心问题诊断

### 🔴 P0 致命问题（来自 log.log 分析）

| 问题 | 根因 | 影响 |
|------|------|------|
| **Round counter 非单调重置** | `runModel` 中上下文管理逻辑错误，messages/tokens 同步下降 | Agent 无法完成多轮研究，陷入重复搜索 |
| **搜索基础设施选型错误** | DDG/Bing 在国内无法访问，HTTP 搜索层形同虚设 | 100% 回退 WebView，内容质量差 |
| **WebView URL 抓取 889 chars 截断** | WebView buffer 硬编码或 DOM ready 判断过早 | 所有银行/金融类站点内容不可用 |

### 🟠 P1 高风险问题（来自日志审计）

| 问题 | 风险 |
|------|------|
| System prompt 以 Debug 级别输出 | 核心逻辑外泄 |
| HTTPS 页面加载 HTTP 资源 | 中间人攻击风险 |
| 主线程阻塞 46 帧 | 用户体验差 |
| 搜索并发无队列/去重 | 同一主题多个变体重复搜索 |

### 🟡 架构改进方向（来自参考项目研究）

| 改进项 | 来源 | 说明 |
|--------|------|------|
| Prompt 缓存分段 | Claude Code | 静态/动态边界标记，减少 token 消耗 |
| Tool 接口标准化 | Claude Code | buildTool 工厂模式，统一的权限检查 |
| 记忆分层 | Claude Code | MEMORY.md(长期)/会话记忆/项目 CLAUDE.md |
| Skill 外部化 | OpenCode/KiloCode | 心跳自动化 prompt 改为 Markdown Skill |
| Effect 框架局部采用 | KiloCode | Layer/InstanceState 模式替代手写单例 |
| MCP 集成完善 | Claude Code | 多传输协议支持，连接缓存+重连 |

## What Changes

### Phase 1: 止血修复（P0 问题）

1. **修复 Round counter 重置 bug**
   - 定位 `runModel` 中上下文截断逻辑
   - 添加日志验证修复效果

2. **搜索后端切换为国内可用源**
   - 替换 DuckDuckGo 为百度/Bing 中国 API
   - 添加搜索失败快速反馈机制

3. **修复 WebView 889 chars 截断**
   - 等待 networkidle 而非仅依赖 DOMContentLoaded
   - 动态 buffer 扩展

### Phase 2: 安全加固（P1 问题）

4. **生产环境日志安全**
   - Debug 日志添加开关，默认关闭
   - System prompt 不在生产日志输出

5. **WebView 安全加固**
   - 强制 HTTPS 加载
   - 禁用 mixed content

### Phase 3: 架构演进（参考项目借鉴）

6. **Prompt 缓存分段** - 采用 Claude Code 的 SYSTEM_PROMPT_DYNAMIC_BOUNDARY
7. **Tool 接口标准化** - 参考 Claude Code 的 buildTool 工厂模式
8. **记忆系统分层** - 参考 Claude Code 的 MEMORY.md / CLAUDE.md 分层

## Impact

- **Affected specs**: 所有现有 spec
- **Affected code**: MainViewModel.kt, WebSearcher.kt, WebViewAgent.kt, AppRoot.kt
- **Breaking changes**: 搜索后端切换可能影响现有搜索相关功能

## 规范变更流程

1. **任何新功能/修改** 必须先创建 spec.md
2. **多 agent 评审** - 重大变更需经 catgirl-coder + code-reviewer 评审
3. **编译验证** - 每次变更后必须 `assembleDebug` 通过
4. **参考项目** - 优先借鉴 Claude Code/OpenCode 的成熟设计

## 后续任务

见 `tasks.md`
