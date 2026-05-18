# WebSearcher SearXNG 级别升级 Spec

## Why
当前 WebSearcher.kt 存在以下核心问题：
1. **串行执行**引擎搜索（DDG→Bing→Baidu 逐个尝试），最坏延迟30s+，而SearXNG并行所有引擎仅需3-5s
2. **无结果去重合并**，第一个有结果的引擎直接返回5条低质量结果；SearXNG多引擎去重合并后可达15-25条高质量结果
3. **User-Agent硬编码**3个常量，易被搜索引擎批量识别封禁；SearXNG随机生成UA+TLS指纹随机化
4. **Bing URL解码不完整**仅处理`?q=`格式，遗漏base64url编码的`/ck/a?u=`格式
5. **Baidu使用HTML抓取**脆弱选择器，百度改版即失效；SearXNG用JSON API (`tn=json`) 稳定可靠
6. **无异常分级处理**，CAPTCHA/限流/403统一返回空列表，无暂停保护机制

## What Changes
- 重构 `WebSearcher.kt` 为基于协程的**并发多引擎聚合**架构
- 新增 `SearchResultContainer` 实现URL hash去重 + 多引擎评分排序
- 新增 `UserAgentPool` 实现随机UA轮换（DDG固定UA保证vqd一致性）
- 修复 Bing base64 URL 解码（`/ck/a?u=a1[base64]`格式）
- Baidu 切换为 JSON API 模式（`tn=json`）
- 新增 `EngineStatusManager` 分级异常处理（CAPTCHA暂停/限流冷却/自动恢复）
- 新增 `RateLimiter` 滑动窗口频率限制
- 参考并借鉴 SearXNG 开源代码（AGPL-3.0）的反爬策略和容错机制

## Impact
- Affected code:
  - `network/WebSearcher.kt` - 核心重构
  - `network/ToolExecutor.kt` - 调用方适配（如有接口变更）
  - `ui/MainViewModel.kt` - search调用可能需要suspend适配
  - 新增文件: `network/SearchEngine.kt`, `network/SearchResultContainer.kt`, `network/UserAgentPool.kt`, `network/EngineStatusManager.kt`, `network/RateLimiter.kt`

## ADDED Requirements

### Requirement: 并发多引擎搜索
系统 SHALL 使用 Kotlin 协程并发发起所有配置引擎的搜索请求，而非串行等待。
- **WHEN** 用户发起搜索查询时
- **THEN** 系统 SHALL 同时向 DDG、Bing、Baidu、Jina 等所有已启用引擎发起请求
- **AND** 响应时间 SHALL 等于最慢引擎的耗时（约3-8s），而非所有引擎耗时之和（10-30s）

#### Scenario: 部分引擎失败
- **WHEN** DDG返回CAPTCHA但Bing正常返回结果
- **THEN** 系统 SHALL 返回Bing的结果，而非空列表
- **AND** DDG引擎 SHALL 被标记为暂停状态（默认5分钟）

### Requirement: 结果去重与合并
系统 SHALL 对多引擎搜索结果进行URL级别的去重与质量评分合并。
- **WHEN** 多个引擎返回相同URL的结果时
- **THEN** 系统 SHALL 仅保留一份，但标记来源引擎数量（多引擎命中=高可信度）
- **AND** 结果按评分排序（来源数×2 - 平均位置权重）

#### Scenario: DDG和Bing都返回同一Wikipedia链接
- **WHEN** DDG第1位和Bing第3位都返回 "zh.wikipedia.org/wiki/xxx"
- **THEN** 合并后的结果中该链接仅出现一次
- **AND** 其 sourceEngines 包含 ["ddg", "bing"]
- **AND** 其评分高于仅单引擎命中的结果

### Requirement: User-Agent随机轮换
系统 SHALL 维护User-Agent池并为每次搜索请求随机选择。
- **WHEN** 发起任何搜索引擎请求时
- **THEN** User-Agent SHALL 从预定义的真实浏览器UA模板池中随机选取
- **EXCEPT** DuckDuckGo引擎在单次会话内使用固定UA（保证vqd token一致性）
- **AND** UA模板 SHALL 定期更新以匹配当前主流浏览器版本

### Requirement: Bing完整URL解码
系统 SHALL 正确解析Bing搜索结果中的所有URL格式。
- **WHEN** Bing返回 `bing.com/url?q=<encoded>` 格式链接
- **THEN** 解码为真实目标URL
- **WHEN** Bing返回 `bing.com/ck/a?u=a1<base64url>` 格式链接（新版格式）
- **THEN** Base64 URL解码后还原真实目标URL

### Requirement: Baidu JSON API模式
系统 SHALL 使用Baidu JSON API替代HTML抓取。
- **WHEN** 发起Baidu搜索时
- **THEN** 请求参数包含 `tn=json`
- **AND** 响应通过JSON结构化解析（feed.entry数组），而非Jsoup CSS选择器
- **AND** HTML实体正确解码（`&amp;` → `&`等）

### Requirement: 引擎状态管理与自动恢复
系统 SHALL 跟踪每个搜索引擎的健康状态并在异常时自动暂停/恢复。
- **WHEN** 引擎返回HTTP 403/CAPTCHA响应
- **THEN** 该引擎被暂停 N 秒（CAPTCHA: 300s, 429: 60s, 403: 120s）
- **WHEN** 暂停期过后再次调用该引擎
- **THEN** 尝试恢复，成功则清除暂停状态，失败则重新计时

### Requirement: 请求频率限制
系统 SHALL 实现滑动窗口频率限制防止触发目标网站反爬。
- **WHEN** 对同一域名发起请求时
- **THEN** 两次请求间隔不少于配置的最小间隔（默认2秒）
- **AND** 突发允许最多3次连续请求（之后强制等待冷却期）

## MODIFIED Requirements

### Requirement: WebSearcher.search() 主入口
现有 `search()` 函数 SHALL 从串行for循环改为并发协程聚合模式。
- 保持函数签名兼容（同步版本保留作为fallback）
- 新增 `searchAsync()` suspend函数供协程环境调用
- 缓存策略保持不变（ConcurrentHashMap + TTL）

### Requirement: null字符串全链路过滤
已有 StringUtils.sanitizeJsonNull() 继续应用于所有搜索引擎输出。
- 所有SearchResult的title、snippet字段在构建时经过sanitize
- 工具输出（ToolExecutor）同样应用sanitize

## REMOVED Requirements
（无）
