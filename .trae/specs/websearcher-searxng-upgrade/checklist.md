# Checklist - WebSearcher SearXNG 级别升级

## Phase 1: 基础设施
- [x] UserAgentPool.kt 创建完成，包含UA模板池和随机生成逻辑
- [x] UserAgentPool.getFixedUa() 为DDG返回会话内固定UA
- [x] WebSearcher中所有硬编码UA常量已替换为UserAgentPool调用
- [x] RateLimiter.kt 创建完成，实现滑动窗口频率限制
- [x] 每个搜索引擎请求前调用RateLimiter.allowRequest()
- [x] EngineStatusManager.kt 创建完成，支持暂停/恢复/错误分级
- [x] CAPTCHA异常触发300s暂停，429触发60s，403触发120s
- [x] 暂停期过后自动尝试恢复

## Phase 2: 核心并发重构
- [x] searchAsync() suspend函数使用coroutineScope并发所有引擎
- [x] 原search()同步函数保留兼容（runBlocking包装）
- [x] 引擎调用前检查isAvailable()状态
- [x] 引擎异常时通知handleError()
- [x] SearchResultContainer创建完成
- [x] SearchResult扩展包含sourceEngines和score字段
- [x] URL归一化处理正确（www/https-http/anchor）
- [x] 多引擎结果去重合并工作正常
- [x] 结果按score降序排列

## Phase 3: 引擎修复
- [x] Bing base64url格式URL（/ck/a?u=a1）正确解码
- [x] Baidu JSON API模式正常工作（tn=json）
- [x] Baidu HTML解析作为fallback保留
- [x] DDG vqd缓存TTL=3600秒，过期自动清理
- [x] DDG CAPTCHA检测触发引擎暂停

## Phase 4: 集成验证
- [x] ToolExecutor对WebSearcher调用无破坏性变更
- [x] ./gradlew assembleDebug 编译通过
- [x] 所有新增StringUtils.sanitizeJsonNull()覆盖搜索引擎输出
