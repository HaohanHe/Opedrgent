# Tasks - Opedrgent P0 止血修复

## Phase 1: Round Counter 重置 Bug 修复

- [x] Task 1: 定位 runModel 中 round counter 重置根因
  - SubTask 1.1: 阅读 MainViewModel.kt 的 runModel 函数，找到 round/messages/tokens 同步下降的代码位置
  - SubTask 1.2: 添加详细日志验证问题复现条件
  - SubTask 1.3: 修复上下文截断逻辑

- [x] Task 2: 验证 Round counter 修复
  - SubTask 2.1: 构建并安装测试
  - SubTask 2.2: 复现"吉利跃迁计划"查询，确认 round 单调递增

## Phase 2: 搜索后端切换

- [x] Task 3: 替换搜索后端为国内可用源
  - SubTask 3.1: ~~评估百度搜索 API 或 Bing 中国 API~~ → 确认使用百度+Bing中国双引擎
  - SubTask 3.2: 修改 WebSearcher.kt 的 search 函数
  - SubTask 3.3: 实现搜索失败快速反馈（2次失败后告知用户）

- [x] Task 4: 添加搜索超时优化
  - SubTask 4.1: 将超时缩短至 8秒
  - SubTask 4.2: 移除 DDG（中国不可用），实现超时后立即切换备选

## Phase 3: WebView 889 chars 截断修复

- [x] Task 5: 修复 WebView 内容提取
  - SubTask 5.1: 阅读 WebViewAgent.kt 的 fetchUrl 函数
  - SubTask 5.2: 修改为等待 onPageFinished 后再提取内容
  - SubTask 5.3: 移除 8000 char 硬截断，移除 nav/header/footer/aside 元素

## Task Dependencies

- Task 2 依赖 Task 1
- Task 4 依赖 Task 3
- Task 5 可并行于 Task 1-4
