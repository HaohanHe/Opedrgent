# Phase 2: UX 修复 + 并发安全 + 性能优化

## Why

P0 止血修复完成后，代码审计发现多个影响用户体验和稳定性的高优先级问题。本 spec 聚焦于最直接可感知的体验问题和并发安全问题。

## 问题列表

### P1 高优先级（直接影响用户体验）

| # | 问题 | 根因 | 修复方案 |
|---|------|------|----------|
| 1 | 流式输出无 Markdown 格式 | StreamingCard 使用 `Text()` 而非 `MarkdownText()` | 替换为 `MarkdownText(text, maxChars=8000)` |
| 2 | 按钮缺少 loading 保护 | "帮我写"、"发送"等按钮未检查 `state.loading` | 添加 loading/streaming 状态检查 |
| 3 | GlobalScope 内存泄漏 | `streamLlm` 中使用 `GlobalScope.launch` | 改用 `currentCoroutineContext()` 传播取消 |
| 4 | cancelled 线程不安全 | 普通 Boolean 跨线程读写 | 改为 `AtomicBoolean` |

### P2 中优先级

| # | 问题 | 修复方案 |
|---|------|----------|
| 5 | 导出方法主线程 IO | 改为 suspend + `Dispatchers.IO` |
| 6 | 搜索无去重 | 添加搜索锁防止并发重复搜索 |
| 7 | locationDetail 始终为 null | 解析 reverseGeocode JSON 提取结构化地址 |

## Impact

- **Affected files**: AppRoot.kt, MainViewModel.kt, EnvironmentProvider.kt
- **Breaking changes**: 无
