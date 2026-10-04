# Opedrgent 更新日志 / Release Notes

## 1.2.1（首次公开发布 / First public source release）

### 端侧引擎与原生工具 / On-device Engine & Native Tools

- **原生工具调用**：端侧会话接入 LiteRT-LM 受约束解码，新增 `LocalOpenApiToolAdapter`（ToolBinding→OpenApiTool）与 `LocalToolCatalog`；离线工具（TodoWrite、Recall、ActionItem、本地模型管理）由模型自主 tool_calls，不做关键词命中。  
  **Native tool calling**: On-device conversations now use LiteRT-LM constrained decoding via a new `LocalOpenApiToolAdapter` and `LocalToolCatalog`; offline tools (TodoWrite, Recall, ActionItem, local model management) are invoked by the model, with no keyword matching.
- **原生资源释放**：重置 / 卸载 / 重载前显式 `close()` 旧 Conversation，引擎会话强类型化，`enable_thinking` 改为布尔，视觉后端 GPU 不可用时回退 CPU。  
  **Native resource cleanup**: The previous Conversation is explicitly closed before reset/unload/reload, the engine conversation is strongly typed, `enable_thinking` is boolean, and the vision backend falls back to CPU when GPU is unavailable.
- **模型清单收敛**：移除无法匿名下载的条目，仅保留已实测可断点续传的三个模型；新增真机一键自检 androidTest。  
  **Model catalog**: Removed entries that are not anonymously downloadable, keeping only three verified resumable models; added an on-device one-tap smoke androidTest.
### 稳定性与健壮性 / Stability & Robustness

- **录音生命周期**：修复切换 Tab 导致录音中断或崩溃、`AudioRecord` 未释放；语音管线异常自恢复、退避溢出修正。  
  **Recording lifecycle**: Fixed recording interruption/crash on tab switching and `AudioRecord` leaks; the voice pipeline now self-recovers with corrected backoff.
- **状态与并发**：约 159 处 `state.value = copy` 改为原子 `update {}`；补齐多处流关闭、失败引擎 `close`；`ModelDownloadManager` 收敛为单例。  
  **State & concurrency**: Replaced ~159 non-atomic state writes with `update {}`, fixed missing stream/engine closes, and consolidated `ModelDownloadManager` into a singleton.
- **数据一致性**：删除笔记/反思时级联清理关联报告与行动项；海马、反思、知识图谱的查后写事务化并加唯一索引；修复面试计时器常驻。  
  **Data consistency**: Cascading cleanup on note/reflection deletion, transactional read-then-write with unique indexes, and a stuck interview-timer fix.
- **备份安全**：备份解包增加 Zip Slip 路径校验，失败时安全回滚；修正 WebDAV 明文传输。  
  **Backup safety**: Added Zip Slip canonical-path checks with rollback on failure, and fixed plaintext WebDAV.

### 安全与隐私 / Security & Privacy

- MCP 自定义请求头改为加密存储，调试日志对密钥掩码，声纹模板排除备份；`networkSecurityConfig` 仅对回环地址放行明文，敏感数据经 Android Keystore 加密。  
  MCP custom headers are encrypted, debug logs mask secrets, voiceprint templates are excluded from backups, cleartext is allowed only for loopback, and sensitive data uses the Android Keystore.

### 数据库升级 / Database Migrations

- Action、Sprout、GrowthReview、Folder、Cultivation 五个数据库的 `onUpgrade` 由“丢弃重建”改为保留用户数据的增量迁移（统一 `SqliteMigrations` 框架，含 Cultivation v1→v2 旧报告按同名列迁移）。  
  All five databases now upgrade incrementally (preserving user data) via a unified `SqliteMigrations` framework instead of dropping and recreating.

### 数值与算法 / Algorithm

- SGP4 状态缩放改用 WGS-72 地球半径 `6378.135`，消除近地目标约 1.9 m 的历元位置偏差；WGS-84 大地测量仍使用 `6378.137`。  
  SGP4 state scaling now uses the WGS-72 radius `6378.135`, removing a ~1.9 m near-Earth epoch bias; WGS-84 geodetic calculations still use `6378.137`.

### 测试 / Testing

- 单元测试扩充至 **28 个测试类 / 243 个用例**，全部通过：新增 Robolectric 测试（真实 SQLite 迁移、全双工状态机与权限门、模型就绪门控、备份安全失败与回滚）、故障注入测试，以及 6 组 TLE 的 SGP4 发布向量对照。  
  Expanded to **28 test classes / 243 cases, all passing**, adding Robolectric tests (real SQLite migrations, full-duplex state machine and permission gates, readiness gating, backup failure/rollback), fault-injection tests, and SGP4 published-vector checks across 6 TLEs.

### 开源治理 / Open-source Governance

- 项目许可证整体转为 **GPL-3.0**：卫星过境模块基于 SGP4/SDP4，代码表达移植自 GPL-3.0 的 Look4Sat（上游 PREDICT），已保留署名；新增 `THIRD_PARTY_NOTICES.md` 列明依赖协议，运行期下载的模型权重不在 GPL 授权范围内。  
  The project is now released entirely under **GPL-3.0**: the satellite module's SGP4/SDP4 implementation is ported from GPL-3.0 Look4Sat (upstream PREDICT) with attribution; added `THIRD_PARTY_NOTICES.md`, and runtime-downloaded model weights are outside the GPL grant.

### 修复 / Fixes

- **位置缓存刷新**：`EnvironmentProvider.getCurrentLocation` 改为 suspend 函数，优先使用 5 分钟内新缓存位置；API 30+ 使用 `LocationManager.getCurrentLocation`，低版本 fallback 到 `requestSingleUpdate`（8 秒超时），失败后降级到任意最后已知位置。解决切换地点后应用仍显示旧位置的问题。  
  **Location Cache Refresh**: `getCurrentLocation` now prefers fresh cached locations within 5 minutes, uses `LocationManager.getCurrentLocation` on API 30+, falls back to `requestSingleUpdate` on older versions with an 8s timeout, and finally falls back to stale locations if needed.
- **笔记操作表滚动**：`NoteActionBottomSheet` 内容区域增加 `verticalScroll`，修复屏幕较小时底部「删除」按钮被截断、无法滑动触达的问题。  
  **Note Action Sheet Scroll**: Added `verticalScroll` to `NoteActionBottomSheet` so the delete action remains reachable on small screens.
- **Release 构建修复**：修正 `backup_rules.xml` 与 `data_extraction_rules.xml` 中 SharedPreferences 路径带 `.xml` 后缀、以及 `include` 与 `exclude` 冲突导致的 lint 致命错误，确保 `./gradlew assembleRelease` 通过。  
  **Release Build Fix**: Fixed `backup_rules.xml` / `data_extraction_rules.xml` lint fatal errors caused by incorrect `.xml` suffixes and conflicting `include`/`exclude` rules.

### 其他 / Misc

- 版本号：`versionCode 4` / `versionName "1.2.1"`

---

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

**版本号 / Version**: 1.2.1  
**构建状态 / Build Status**: `./gradlew assembleRelease` 通过 / `BUILD SUCCESSFUL`
