# Checklist - Phase 2: UX 修复 + 并发安全 + 性能优化

## P1 高优先级

- [x] 1.1 `StreamingCard` 使用 `MarkdownText(text, maxChars = 900)` 替代 `Text()`
- [x] 1.2 Markdown 渲染在流式输出中正常工作
- [x] 1.3 "帮我写"按钮在 `state.loading` 时禁用
- [x] 1.4 "发送"按钮在 `state.streaming` 时禁用
- [x] 1.5 所有 `GlobalScope.launch` 已替换为 `viewModelScope.launch` 或 `currentCoroutineContext()`
- [x] 1.6 `cancelled` 使用 `AtomicBoolean` 线程安全
- [x] 1.7 编译通过无新增错误

## P2 中优先级

- [x] 2.1 导出方法使用 `suspend` + `Dispatchers.IO`
- [x] 2.2 搜索去重锁使用 `ReentrantLock()` 防止并发重复搜索
- [x] 2.3 `reverseGeocode` JSON 解析正确填充 `locationDetail`
- [x] 2.4 全量编译 `assembleDebug` 通过
