# Tasks - WebSearcher SearXNG 级别升级

## Phase 1: 基础设施（可并行）

- [x] Task 1: 创建 UserAgentPool - 随机UA轮换管理器
  - [x] 1.1: 创建 `network/UserAgentPool.kt`，定义UA模板池（Chrome/Firefox/Edge各5-10个最新版本）
  - [x] 1.2: 实现 `generate()` 方法随机选取OS+浏览器版本组合
  - [x] 1.3: 实现 `getFixedUa()` 方法为DDG返回固定UA（会话内一致）
  - [x] 1.4: 在WebSearcher中替换硬编码UA常量为UserAgentPool调用

- [x] Task 2: 创建 RateLimiter - 滑动窗口频率限制
  - [x] 2.1: 创建 `network/RateLimiter.kt`
  - [x] 2.2: 实现按域名分组的请求时间记录
  - [x] 2.3: 实现 allowRequest() 检查：突发窗口(20s/3次) + 长时窗口(10min/15次)
  - [x] 2.4: 在每个搜索引擎请求前调用rateLimiter.allowRequest(domain)

- [x] Task 3: 创建 EngineStatusManager - 引擎健康状态管理
  - [x] 3.1: 创建 `network/EngineStatusManager.kt`
  - [x] 3.2: 定义EngineStatus数据类(suspended/suspendUntil/consecutiveErrors/lastError)
  - [x] 3.3: 实现handleError(engineName, exception)分级处理逻辑
  - [x] 3.4: 实现isAvailable(engineName)检查暂停状态和自动恢复

## Phase 2: 核心重构

- [x] Task 4: 重构 WebSearcher 为并发架构
  - [x] 4.1: 新增 `searchAsync()` suspend函数，使用coroutineScope + async(Dispatchers.IO) 并发所有引擎
  - [x] 4.2: 保留原 `search()` 同步函数作为fallback（内部用runBlocking调用async版本）
  - [x] 4.3: 每个引擎调用前检查 EngineStatusManager.isAvailable()
  - [x] 4.4: 每个引擎异常时通知 EngineStatusManager.handleError()
  - [x] 4.5: Jina fallback保持不变（在所有主引擎失败后执行）

- [x] Task 5: 创建 SearchResultContainer - 结果去重合并
  - [x] 5.1: 创建 `network/SearchResultContainer.kt`
  - [x] 5.2: 扩展SearchResult数据类添加sourceEngines和score字段
  - [x] 5.3: 实现normalizeUrl()方法（移除www/https-http差异/anchor/#后内容）
  - [x] 5.4: 实现addResults(engineName, results)去重合并逻辑
  - [x] 5.5: 实现getSortedResults()按score降序排列
  - [x] 5.6: 在searchAsync()中使用SearchResultContainer收集并合并所有引擎结果

## Phase 3: 引擎修复

- [x] Task 6: 修复 Bing URL解码（base64url格式）
  - [x] 6.1: 扩展 extractBingUrl() 支持 `/ck/a?u=a1<base64>` 格式
  - [x] 6.2: 使用 Base64.getUrlDecoder() 解码
  - [x] 6.3: 处理padding和异常情况

- [x] Task 7: Baidu 切换 JSON API
  - [x] 7.1: 修改 searchBaidu() URL添加 `tn=json&ie=utf-8` 参数
  - [x] 7.2: 解析JSON响应的 feed.entry 数组
  - [x] 7.3: 提取 title/url/abs 字段（HTML实体解码）
  - [x] 7.4: 保留HTML解析路径作为fallback（JSON解析失败时降级）

- [x] Task 8: DDG vqd缓存增强
  - [x] 8.1: 当前vqdCache已是ConcurrentHashMap，确认TTL=3600秒正确
  - [x] 8.2: 添加vqd过期自动清理逻辑
  - [x] 8.3: CAPTCHA检测时标记DDG引擎暂停

## Phase 4: 集成与验证

- [x] Task 9: ToolExecutor适配新接口
  - [x] 9.1: 确认ToolExecutor对WebSearcher.search()的调用兼容（search()签名不变）

- [x] Task 10: 编译验证全量测试
  - [x] 10.1: `./gradlew assembleDebug` 编译通过
  - [x] 10.2: 无新增warning（仅Opt-in experimental marker，属已知问题）

# Task Dependencies
- Task 4 depends on Task 1, 2, 3 (基础设施先就绪)
- Task 5 depends on Task 4 (并发结果需要容器合并)
- Task 6, 7, 8 可并行于 Task 4, 5
- Task 9 depends on Task 4, 5, 6, 7, 8 (核心完成后集成)
- Task 10 depends on Task 9 (最后验证)
