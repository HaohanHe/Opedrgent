# Checklist - Opedrgent P0 止血修复验证

## 搜索后端修复验证

- [x] WebSearcher.kt 编译通过
- [x] 搜索使用国内可用 API（百度+Bing中国双引擎）
- [x] 搜索超时优化生效（8秒而非15秒）
- [x] 移除 DDG（中国不可用）
- [x] ToolExecutor.kt 和 WebResearchRouter.kt 已更新调用

## WebView 修复验证

- [x] WebViewAgent.kt 编译通过
- [x] fetchUrl 等待 onPageFinished 后再提取内容
- [x] 移除 8000 char 硬截断
- [x] 移除 nav/header/footer/aside 元素
- [x] WebChromeClient 添加 onConsoleMessage 回调
- [x] MIXED_CONTENT 改为 NEVER_ALLOW（安全加固）

## 通用验证

- [x] `.\gradlew.bat assembleDebug --no-daemon` BUILD SUCCESSFUL
- [x] 无新增 lint/typecheck 错误
- [x] .gitignore 包含 参考/ 目录
