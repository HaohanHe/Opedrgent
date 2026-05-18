# Tasks - Phase 2: UX 修复 + 并发安全 + 性能优化

## P1 高优先级（直接影响用户体验）

- [x] **Task 1: 流式输出 Markdown 格式修复**
  - [x] 阅读 `AppRoot.kt` 找到 `StreamingCard` 组件
  - [x] 将 `Text()` 替换为 `MarkdownText(text, maxChars = 900)`
  - [x] 编译验证

- [x] **Task 2: 按钮 Loading 状态保护**
  - [x] 阅读 `AppRoot.kt` 找到"帮我写"、"发送"等按钮
  - [x] 添加 `state.loading || state.streaming` 状态检查
  - [x] 按钮在 loading/streaming 时禁用（`enabled = !loading && !streaming`）
  - [x] 编译验证

- [x] **Task 3: GlobalScope 内存泄漏修复**
  - [x] 阅读 `MainViewModel.kt` 找到所有 `GlobalScope.launch`
  - [x] 替换为 `viewModelScope.launch` 或 `currentCoroutineContext()`
  - [x] 编译验证

- [x] **Task 4: cancelled 线程安全修复**
  - [x] 阅读 `MainViewModel.kt` 找到 `cancelled` 布尔变量
  - [x] 将 `private var cancelled = false` 改为 `private val cancelled = AtomicBoolean(false)`
  - [x] 所有读写替换为 `cancelled.get()` / `cancelled.set(true)`
  - [x] 编译验证

## P2 中优先级

- [x] **Task 5: 导出方法主线程 IO 修复**
  - [x] 导出方法使用 `suspend` + `Dispatchers.IO`
  - [x] 编译验证

- [x] **Task 6: 搜索去重锁**
  - [x] 在 `MainViewModel` 添加 `searchLock = ReentrantLock()`
  - [x] 防止同一主题并发重复搜索
  - [x] 编译验证

- [x] **Task 7: locationDetail JSON 解析修复**
  - [x] `EnvironmentProvider.reverseGeocode()` 解析 JSON 提取结构化地址
  - [x] `locationDetail` 字段正确填充（city/district/town/state/country）
  - [x] 编译验证

## Task Dependencies

- Task 1, 2, 3, 4 可并行（不同文件）
- Task 5, 6, 7 可并行于 Task 1-4
