# Opedrgent 更新日志 / Release Notes

## 1.2sat（演示准备版 / Presentation Ready）

### 架构 / Architecture

- **MainViewModel 拆分**：新增 `RecorderStateManager` 与 `InterviewStateManager`，将录音状态与面试业务逻辑从主 ViewModel 抽离，减少约 500 行上帝类代码，UI 调用接口保持不变。
  **MainViewModel Refactor**: Extracted `RecorderStateManager` and `InterviewStateManager` to reduce god-class complexity while preserving UI APIs.

### 优化 / Improvements

- **网络参数集中配置**：新增 `NetworkConfig` / `SearchConfig`，将 HTTP 超时、搜索引擎权重、缓存大小等硬编码参数统一收口。
  **Centralized Network Config**: Consolidated HTTP timeouts, search engine weights, and cache tuning into `NetworkConfig` / `SearchConfig`.
- **UI 硬编码中文治理**：核心屏幕与 `MainViewModel` 用户可见标签改用 `stringResource` / `app.getString`，推进国际化与主题一致性。
  **Hardcoded Chinese Cleanup**: Replaced user-visible hardcoded Chinese strings with `stringResource` / `app.getString`.
- **空状态操作引导**：`KnowledgeBaseScreen` 搜索无结果增加"清除搜索"按钮；`HippocampusScreen` 空状态增加"去记笔记"跳转。
  **Empty-State Actions**: Added clear-search action to `KnowledgeBaseScreen` and go-to-notes action to `HippocampusScreen`.

### 修复 / Fixes

- **OOM 与 TransactionTooLarge 风险**：
  - 图片上传/本地模型输入改用 `inSampleSize` 下采样 + 最大边 896px 限制，避免超大图 OOM。
  - `EditorTeamScreen` 群聊消息列表与 `VocabularySettingsScreen` 词汇列表从 `rememberSaveable` 改为 `remember`，避免大对象序列化到 Bundle 导致崩溃。
  **OOM & TransactionTooLarge Fix**: Image decoding now uses downsampling; large lists no longer saved to `rememberSaveable`.

---

## 1.1sat

### 新增 / New

- **Ham 模式通联日志智能化**：录音转写后自动预填充卫星名称/频率/调制方式/QTH 网格，AI 仅补漏（信号报告、呼号等），对话框可编辑、可导出 ADIF/CSV。
  **Ham Mode Smart Contact Log**: Auto-fills satellite name/frequency/modulation/QTH from satellite DB after transcription; AI only fills gaps. Dialog supports editing and ADIF/CSV export.
- **Ham 模式本台呼号/QTH 网格设置**：新增 `STATION_CALLSIGN` / `MY_GRIDSQUARE` 两个 ADIF 规范字段，写入每条 QSO 记录。
  **Ham Mode Station Settings**: Added `STATION_CALLSIGN` and `MY_GRIDSQUARE` (ADIF 3.1 fields) to every QSO record.
- **thinking_budget 可配置**：用户可在设置中调整思维链 token 预算（默认 4096，范围 0-32768），影响所有 thinking 模型的推理长度。
  **Configurable thinking_budget**: User-adjustable thinking chain token budget (default 4096, range 0-32768) for all thinking models.

### 修复 / Fixes

- **ADIF 导出格式修正**：`PROGRAMID` 长度 8→9（实际 9 字符），头部裸文本加 `#` 前缀，字段尾部空格改换行分隔。
  **ADIF Export Fix**: `PROGRAMID` length 8→9, header plain text prefixed with `#`, field trailing spaces replaced with newlines.
- **WebView 内存泄露修复**：`MainViewModel.onCleared()` 新增 `toolExecutor.destroy()`，释放 WebViewAgent 及各工具内部资源。
  **WebView Leak Fix**: `MainViewModel.onCleared()` now calls `toolExecutor.destroy()` to release WebViewAgent and tool resources.
- **thinking 模型 reasoning 多轮丢失修复**：`chatCompletionsWithTools` 补 `reasoning_content` 解析与多轮回传，非流式工具调用路径也能展示思维链。
  **Reasoning Loss Fix**: `chatCompletionsWithTools` now parses and round-trips `reasoning_content`, showing reasoning in non-streaming tool calls.
- **通联日志对话框状态丢失修复**：编辑草稿独立于对话框生命周期，dismiss 后仍可重开继续编辑，必填字段（卫星/日期）高亮校验。
  **Contact Log Dialog Fix**: Edit draft survives dialog dismiss; dialog can be reopened. Required fields (satellite/date) show inline validation.
- **网络超时分层配置**：`READ_TIMEOUT` 600→60s 用于常规请求，LLM 请求改用 `HttpClients.streaming`（5min readTimeout + 10min callTimeout）。
  **Network Timeout Layering**: `READ_TIMEOUT` 600→60s for normal requests; LLM requests now use `HttpClients.streaming` (5min readTimeout + 10min callTimeout).
- **SatellitePassTool 清理**：删除 `errorResult` 死代码，补 list/search 分支 DebugLog，两处 `HttpURLConnection` 改为 `HttpClients.longRunning`。
  **SatellitePassTool Cleanup**: Removed dead `errorResult`, added list/search DebugLog, replaced two `HttpURLConnection` with `HttpClients.longRunning`.
- **ToolExecutor 错误 @Deprecated 标记移除**：`unknownTool` 是有效 fallback，非废弃方法，移除错误注解。
  **ToolExecutor Fix**: Removed incorrect `@Deprecated` on `unknownTool` (valid fallback, not deprecated).
- **requestContactLog 时区修正**：通联日期改用 UTC 时区，与 ADIF 规范一致。
  **requestContactLog Timezone Fix**: Contact date now uses UTC, matching ADIF spec.
- **通联结果枚举约束**：新增 `normalizeResult()` 将任意输入归一化为 OK/PARTIAL/NO，兼容中英文同义词。
  **Contact Result Enum**: Added `normalizeResult()` to normalize any input to OK/PARTIAL/NO, with Chinese/English synonym support.

---

## 1.0.0

### 新增 / New

- **Interview 访谈模式**：支持全双工语音对话、硬件 AEC、VAD 与打断检测。  
  **Interview Mode**: Full-duplex voice conversation with hardware AEC, VAD, and barge-in detection.
- **V2 Skills 系统**：支持动态加载 SKILL.md 技能、JS 沙箱执行与动态工具注册。  
  **V2 Skill System**: Dynamic SKILL.md loading, JS sandbox execution, and dynamic tool registration.
- **本地大模型 LiteRT-LM**：集成 Google 端侧推理框架，支持 Gemma 4 等模型。  
  **Local LLM LiteRT-LM**: Integrated Google's on-device inference framework, supporting Gemma 4 and related models.
- **笔记与知识图谱**：笔记 CRUD、图谱关系、知识萌发（Insight Sprout）四阶段洞察。  
  **Notes & Knowledge Graph**: Note CRUD, graph relationships, and four-stage Insight Sprout.
- **首页小组件**：Opedrgent Widget 快捷入口。  
  **Home Widget**: Quick-access Opedrgent Widget.

### 优化 / Improvements

- **核心性能优化**：工具调用改为并发执行，MMR 重排序引入 Top-K 截断，缓存改为无锁 ConcurrentHashMap。  
  **Core Performance**: Concurrent tool execution, Top-K truncated MMR reranking, lock-free ConcurrentHashMap cache.
- **聊天历史分页**：默认显示最近 10 轮对话，上滑时按块加载更早记录。  
  **Chat History Pagination**: Defaults to the latest 10 rounds, loading older rounds in chunks on scroll-up.
- **返回键与首页交互**：重写 BackHandler，子页面返回上一层、非首页 Tab 返回首页、首页双击退出；录音状态返回时弹确认框；首页内容上移。  
  **Back Navigation & Home UI**: Reworked BackHandler with nested back, home-tab fallback, double-tap exit, recording back confirmation, and raised home content.
- "探索者" 文案现在优先读取应用昵称或系统设备名。  
  The "Explorer" greeting now reads the in-app nickname or system device name first.

### 文档 / Documentation

- 新增用户手册（`docs/wiki/User-Manual.md`）、FAQ（`docs/wiki/FAQ.md`），README 增加文档入口链接。  
  Added User Manual (`docs/wiki/User-Manual.md`), FAQ (`docs/wiki/FAQ.md`), and documentation links in README.

### 修复 / Fixes

- 修复 Release 构建中 Sherpa-ONNX 类重复定义导致的 R8 打包失败，移除本地 7 个 Stub 类。  
  Fixed Release build R8 failure caused by duplicate Sherpa-ONNX classes; removed 7 local stub classes.
- 修复 `values-en` / `values-ja` 中存在但默认 `values` 缺失的 3 个翻译字符串（`settings_voice_memo`、`settings_meeting`、`settings_classroom`）。  
  Fixed 3 missing default-locale strings (`settings_voice_memo`, `settings_meeting`, `settings_classroom`) that existed only in `values-en` / `values-ja`.

---

**版本号 / Version**: 1.2sat  
**构建状态 / Build Status**: `./gradlew assembleDebug` 通过 / `BUILD SUCCESSFUL`
