# Opedrgent - 项目全景文档

> 生成日期：2026-05-10
> 代码库规模：50 个 Kotlin 源文件，约 15,000+ 行代码

***

## 一、产品设计理念

### 1.1 解决什么问题

Opedrgent 是一个 **Android 端 AI 研究助手**，核心目标是让用户通过自然语言对话完成深度网络研究。不同于传统聊天机器人，Opedrgent 的设计理念是：

1. **全过程可溯源** — 用户能看到 AI 在做什么（思考链、工具调用、来源引用），而非面对一个"转圈"的黑盒
2. **自主工具调用** — AI 能主动搜索网络、抓取网页、分析来源，形成研究报告
3. **跨会话记忆与进化** — 记忆持久化、技能学习、自动化定时任务，越用越聪明
4. **离线可用 + 隐私优先** — 本地优先存储，API Key 加密保存，支持本地模型（Ollama）

### 1.2 核心用户体验

```
用户提问 → AI 思考（可见推理链） → AI 调用工具（可见工具卡片） 
→ 搜索结果返回 → AI 综合分析 → 带引用标记的回答 → 可选：生成摘要/报告/日程
```

### 1.3 关键设计原则

| 原则              | 实现                                                          |
| --------------- | ----------------------------------------------------------- |
| **思考链可见**       | XML `<thinking>` 标签解析，折叠/展开显示推理过程                           |
| **工具调用透明**      | 每次工具调用展示为 Tool Card（状态：PENDING → RUNNING → COMPLETED/ERROR） |
| **来源可追溯**       | \[S1]\[S2] 引用标签，每个来源有标题+URL+内容                              |
| **防饱和攻击**       | 多源交叉验证，实体交换测试，时间模式检测，情绪极性分析                                 |
| **Prompt 注入防御** | 不可见 Unicode 检测，15 种威胁模式匹配，流式内容实时消毒                          |
| **渐进式交互**       | AI 可通过 `ask_user` 工具向用户提问（单选/多选），而非猜测                       |
| **自动进化**        | 从对话中提炼记忆和技能，支持定时自动化任务                                       |

***

## 二、技术架构总览

### 2.1 架构图（文本描述）

```
┌─────────────────────────────────────────────────────┐
│                    UI Layer (Compose)                 │
│  AppRoot.kt → SessionScreen → ChatList → ToolCards   │
│              → SettingsSheet → WebViewOverlay         │
└──────────────────────┬──────────────────────────────┘
                       │ StateFlow<UiState>
┌──────────────────────▼──────────────────────────────┐
│              MainViewModel (Agent Loop)               │
│  runModel() → streamLlm() → ToolCallParser           │
│            → ToolExecutor → store/session            │
└──────┬───────────────┬───────────────┬──────────────┘
       │               │               │
┌──────▼──────┐ ┌──────▼──────┐ ┌──────▼──────────────┐
│  LlmClient  │ │ToolExecutor │ │  PromptBuilder       │
│  SSE Stream │ │ web_search  │ │  + PromptSafety      │
│  + Vision   │ │ deep_resrch │ │  + PromptBlocks      │
│  + TTS API  │ │ read_url    │ │  + SourceValidator   │
└──────┬──────┘ └──────┬──────┘ └─────────────────────┘
       │               │
┌──────▼───────────────▼──────────────────────────────┐
│                 Infrastructure                       │
│  WebViewAgent  WebSearcher  SourceFetcher            │
│  (Bing/Browser) (DuckDuckGo) (OkHttp)                │
└─────────────────────────────────────────────────────┘
       │
┌──────▼──────────────────────────────────────────────┐
│              Storage & State                         │
│  ResearchStore  MemoryStore  SkillsStore             │
│  AutomationStore  EncryptedSharedPreferences         │
└─────────────────────────────────────────────────────┘
       │
┌──────▼──────────────────────────────────────────────┐
│            Advanced Modules (MCP + Agent)             │
│  McpClient  ToolPool  SkillSystem  PromptCache       │
│  MemoryManager  PermissionEngine  AgentLoopState     │
│  AutoEvolutionEngine  SystemPromptBuilder            │
│  ChromeDevToolsMcp  MultimodalClickEngine            │
└─────────────────────────────────────────────────────┘
```

### 2.2 技术栈

| 层级  | 技术                                                       |
| --- | -------------------------------------------------------- |
| 语言  | Kotlin 100%                                              |
| UI  | Jetpack Compose (Material 3)                             |
| 架构  | MVVM (AndroidViewModel + StateFlow)                      |
| 网络  | OkHttp 4.x (SSE Streaming, multipart)                    |
| 存储  | SharedPreferences + EncryptedSharedPreferences + JSON 文件 |
| 安全  | AndroidX Security Crypto (AES-256-GCM)                   |
| 浏览器 | Android WebView (Chromium 101)                           |
| 序列化 | org.json (JSONObject/JSONArray)                          |
| 并发  | Kotlin Coroutines (viewModelScope, Dispatchers.IO)       |
| 音频  | Android TTS (TextToSpeech)                               |
| PDF | Android PDF Renderer + 自定义 OCR                           |

### 2.3 核心设计模式

- **Agent Loop** — 类 AutoGPT 的 ReAct 循环（思考→行动→观察→重复）
- **XML Tool Calling** — 自定义 XML 标签协议（适用于不支持原生 function calling 的 LLM）
- **Provider Pattern** — MemoryProvider, ToolProvider 抽象接口
- **Strategy Pattern** — 搜索方法路由（DDG/WebView/MCP/Screenshot）
- **Chain of Responsibility** — 多层降级（DDG → WebView → MCP → Screenshot）
- **Observer Pattern** — StateFlow 驱动的 UI 响应式更新
- **Builder Pattern** — SystemPromptBuilder 流式 API

***

## 三、功能模块目录

### 3.1 UI 层 (`ui/`)

| 文件                                                                                                               | 行数    | 职责                                                                          |
| ---------------------------------------------------------------------------------------------------------------- | ----- | --------------------------------------------------------------------------- |
| [AppRoot.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/ui/AppRoot.kt)                     | \~800 | 应用主入口 Compose UI：会话列表、聊天界面、设置面板、WebView 浏览器覆盖层、操作栏（搜索/摘要/报告/导出/分享）          |
| [MainViewModel.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/ui/MainViewModel.kt)         | 1412  | **核心** — Agent Loop 实现、工具调用循环、流式响应处理、会话管理、记忆/技能 CRUD、进化建议、自动化建议、日程提取、PDF 导入 |
| [AutomationsScreen.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/ui/AutomationsScreen.kt) | \~200 | 自动化任务管理界面                                                                   |
| [FlowExt.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/ui/FlowExt.kt)                     | \~50  | StateFlow 扩展工具                                                              |
| [theme/Theme.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/ui/theme/Theme.kt)             | \~40  | Material 3 主题配置                                                             |
| [theme/Color.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/ui/theme/Color.kt)             | \~30  | 颜色常量                                                                        |
| [theme/Type.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/ui/theme/Type.kt)               | \~30  | 字体排版                                                                        |

**UiState 核心字段**：

- `current: ResearchSession?` — 当前会话
- `streamingText / streamingReasoning / streamingToolParts` — 实时流式显示
- `activeQuestion: QuestionPart?` — 待用户回答的问题
- `isStreaming / loading` — 状态标记
- `evolutionSuggestion / automationSuggestion / calendarSuggestion` — AI 建议弹窗

### 3.2 网络层 (`network/`)

| 文件                                                                                                                    | 行数    | 职责                                                                                                   |
| --------------------------------------------------------------------------------------------------------------------- | ----- | ---------------------------------------------------------------------------------------------------- |
| [LlmClient.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/network/LlmClient.kt)                 | 340   | LLM API 客户端：chatCompletions（同步）、streamChatCompletions（SSE 流式）、multimodalChat（视觉/音频/视频）、visionChat 包装 |
| [ToolExecutor.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/network/ToolExecutor.kt)           | 391   | **工具执行引擎** — 路由 web\_search/open\_browser/deep\_research/read\_url/question/generate\_report         |
| [WebViewAgent.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/network/WebViewAgent.kt)           | \~500 | WebView 浏览器自动化：Bing 搜索、URL 抓取、MCP JS 注入、截图多模态点击                                                      |
| [WebSearcher.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/network/WebSearcher.kt)             | \~100 | DuckDuckGo HTML 搜索 + 备用 Bing JSON API                                                                |
| [SourceFetcher.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/network/SourceFetcher.kt)         | \~150 | OkHttp GET 抓取 + Jsoup HTML 解析                                                                        |
| [WebResearchRouter.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/network/WebResearchRouter.kt) | \~200 | 研究路由分发（AUTO/NATIVE/PROVIDER/BROWSER 模式）                                                              |
| [WebResearchTypes.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/network/WebResearchTypes.kt)   | \~50  | 研究相关类型定义                                                                                             |
| [HttpClients.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/network/HttpClients.kt)             | \~40  | OkHttpClient 单例配置                                                                                    |

**工具执行策略（web\_search 降级链）**：

```
DDG 搜索 → 成功 → 抓取网页正文 → 返回结果
         → 失败 → WebView Bing 搜索 → 抓取 → 返回
               → 失败 → 返回空结果
```

### 3.3 工具/解析层 (`utils/`)

| 文件                                                                                                                        | 行数    | 职责                                                                                                                              |
| ------------------------------------------------------------------------------------------------------------------------- | ----- | ------------------------------------------------------------------------------------------------------------------------------- |
| [ToolCallParser.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/utils/ToolCallParser.kt)             | \~300 | XML 工具调用解析器：parseChunk（流式增量）、parseToolCall（完整解析）、parseToolCallFromComplete（返回 List<ToolPart>）、stripAllTags、extractThinkingParts |
| [PromptBuilder.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/utils/PromptBuilder.kt)               | 409   | **系统提示词构建器** — 11 个模块：身份、环境、工具协议、角色定义、任务指南、允许动作、沟通语调、输出效率、记忆上下文、防饱和攻击、来源引用                                                      |
| [PromptSafety.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/utils/PromptSafety.kt)                 | \~200 | 输入消毒：不可见 Unicode 检测、15 种注入模式匹配、流式消毒状态机、外部内容指令隔离                                                                                 |
| [PromptBlocks.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/utils/PromptBlocks.kt)                 | \~80  | 不可信内容包装器（wrapUntrustedBlock）                                                                                                    |
| [SourceValidator.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/utils/SourceValidator.kt)           | \~100 | 来源质量分析：独立性核查、时间模式检测、情绪极性分析                                                                                                      |
| [DebugLog.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/utils/DebugLog.kt)                         | \~30  | 调试日志工具                                                                                                                          |
| [BackgroundPermHelper.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/utils/BackgroundPermHelper.kt) | \~50  | 后台运行权限助手                                                                                                                        |

### 3.4 模型层 (`model/`)

| 文件                                                                                            | 行数    | 职责                                                                                                                                                                                                                                           |
| --------------------------------------------------------------------------------------------- | ----- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| [Models.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/model/Models.kt) | \~200 | 所有数据模型：ResearchSession, ChatMessage(Role), Source(SourceType), ToolPart, ToolState(ToolStateType), ReasoningPart, QuestionPart, MemoryEntry(MemoryType), Skill, Artifact(ArtifactKind), MultimodalContent, MultimodalMessage, SessionSummary |

**关键枚举**：

- `Role`: SYSTEM / USER / ASSISTANT
- `ToolStateType`: PENDING / RUNNING / COMPLETED / ERROR
- `SourceType`: URL / TEXT
- `MemoryType`: USER / FEEDBACK / SYSTEM
- `ArtifactKind`: SUMMARY / REPORT / NOTES
- `WebResearchMode`: AUTO / NATIVE / PROVIDER / BROWSER

### 3.5 设置层 (`settings/`)

| 文件                                                                                                         | 行数  | 职责                                                                                                             |
| ---------------------------------------------------------------------------------------------------------- | --- | -------------------------------------------------------------------------------------------------------------- |
| [ApiSettings.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/settings/ApiSettings.kt) | 183 | 19 个 LLM 提供商预设（MiMo/OpenAI/Anthropic/Gemini/DeepSeek/通义千问/Moonshot 等），加密 API Key 存储（AES-256-GCM），Base URL 安全校验 |

**安全策略**：

- API Key → EncryptedSharedPreferences
- HTTPS 强制（本地 localhost 除外）
- 禁止私有 IP/内网地址
- 19 个预配置提供商

### 3.6 存储层 (`storage/`)

| 文件                                                                                                            | 行数    | 职责                                                |
| ------------------------------------------------------------------------------------------------------------- | ----- | ------------------------------------------------- |
| [ResearchStore.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/storage/ResearchStore.kt) | \~300 | 会话持久化（JSON 文件）：创建/读取/更新会话、消息管理、来源管理、产物管理、Notes 更新 |
| [MemoryStore.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/storage/MemoryStore.kt)     | \~100 | SharedPreferences + 文件混合的记忆存储                     |
| [SkillsStore.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/storage/SkillsStore.kt)     | \~100 | 技能存储与查询                                           |

### 3.7 服务层 (`service/`)

| 文件                                                                                                                  | 行数   | 职责                        |
| ------------------------------------------------------------------------------------------------------------------- | ---- | ------------------------- |
| [KeepAliveService.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/service/KeepAliveService.kt) | \~80 | WakeLock 前台服务，屏幕关闭后保持后台运行 |

### 3.8 代理核心层 (`agent/`)

| 文件                                                                                                                      | 行数    | 职责                                                                                                                                                                                                     |
| ----------------------------------------------------------------------------------------------------------------------- | ----- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| [MemoryManager.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/agent/MemoryManager.kt)             | \~250 | **多 Provider 记忆编排** — MemoryProvider 生命周期：initialize → prefetch → syncTurn → onTurnStart → onSessionEnd → onMemoryWrite → shutdown。内置 BuiltinMemoryProvider 提供 memory\_save/load/delete/list 工具 Schema |
| [AgentLoopState.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/agent/AgentLoopState.kt)           | \~150 | 代理回合追踪：TurnInfo（推理提取、工具错误记录）、AgentLoopState（maxTurns=30、摘要生成）                                                                                                                                          |
| [SystemPromptBuilder.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/agent/SystemPromptBuilder.kt) | \~200 | **优先级组装器**（Claude 风格）— OVERRIDE > AGENT > CUSTOM > DEFAULT。包含 AgentDefinition、appendToDefault 机制、memory/skills/tools/env 默认段落                                                                          |

### 3.9 权限层 (`agent/permission/`)

| 文件                                                                                                                           | 行数    | 职责                                                                                                                                                 |
| ---------------------------------------------------------------------------------------------------------------------------- | ----- | -------------------------------------------------------------------------------------------------------------------------------------------------- |
| [PermissionEngine.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/agent/permission/PermissionEngine.kt) | \~200 | Claude 风格权限引擎：PermissionRule（SETTINGS/CLI\_ARG/COMMAND/SESSION/DEFAULT 来源）、5 种模式（DEFAULT/ACCEPT\_EDITS/PLAN/BYPASS/YOLO）、DenialTracker（3 次成功后自动允许） |

### 3.10 MCP 协议层 (`mcp/`)

| 文件                                                                                                      | 行数    | 职责                                                                                                     |
| ------------------------------------------------------------------------------------------------------- | ----- | ------------------------------------------------------------------------------------------------------ |
| [McpTypes.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/mcp/McpTypes.kt)         | \~150 | MCP JSON-RPC 2.0 类型定义：Request/Response/Notification、Ping、Initialize、ToolsList/Call                     |
| [McpTransport.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/mcp/McpTransport.kt) | \~300 | 三种传输层：StdioTransport（进程通信）、SseTransport（Server-Sent Events）、HttpTransport（标准 HTTP）                     |
| [McpClient.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/mcp/McpClient.kt)       | \~350 | **MCP 客户端** — 错误分类（McpAuthError/McpSessionExpiredError/McpToolCallError）、自动重连、工具超时（100s）、描述截断（2048 字符） |
| [McpServer.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/mcp/McpServer.kt)       | \~200 | 内置 MCP 服务器：注册本地工具，对外暴露 tools/list 和 tools/call                                                         |
| [ToolPool.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/mcp/ToolPool.kt)         | \~250 | **动态工具池** — ToolProvider 接口，支持 MCP 工具和本地工具的混合注册与发现                                                     |

### 3.11 MCP Chrome DevTools (`mcp/chrome/`)

| 文件                                                                                                                       | 行数    | 职责                                                                                                                                |
| ------------------------------------------------------------------------------------------------------------------------ | ----- | --------------------------------------------------------------------------------------------------------------------------------- |
| [ChromeDevToolsMcp.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/mcp/chrome/ChromeDevToolsMcp.kt) | \~300 | CDP (Chrome DevTools Protocol) WebSocket 集成：Page.navigate、Page.captureScreenshot、DOM 遍历、Input.dispatchMouseEvent、Runtime.evaluate |

### 3.12 MCP 技能系统 (`mcp/skills/`)

| 文件                                                                                                           | 行数    | 职责                    |
| ------------------------------------------------------------------------------------------------------------ | ----- | --------------------- |
| [SkillSystem.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/mcp/skills/SkillSystem.kt) | \~150 | 技能索引与发现：基于对话上下文推荐相关技能 |

### 3.13 MCP 缓存 (`mcp/cache/`)

| 文件                                                                                                          | 行数    | 职责                                  |
| ----------------------------------------------------------------------------------------------------------- | ----- | ----------------------------------- |
| [PromptCache.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/mcp/cache/PromptCache.kt) | \~150 | 语义相似度 Prompt 缓存：减少重复的系统提示词 Token 消耗 |

### 3.14 MCP 进化引擎 (`mcp/evolution/`)

| 文件                                                                                                                              | 行数    | 职责                           |
| ------------------------------------------------------------------------------------------------------------------------------- | ----- | ---------------------------- |
| [AutoEvolutionEngine.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/mcp/evolution/AutoEvolutionEngine.kt) | \~200 | 自动进化引擎：从对话历史中提取记忆和技能，周期性自我优化 |

### 3.15 MCP 多模态 (`mcp/multimodal/`)

| 文件                                                                                                                                   | 行数    | 职责                                                      |
| ------------------------------------------------------------------------------------------------------------------------------------ | ----- | ------------------------------------------------------- |
| [MultimodalClickEngine.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/mcp/multimodal/MultimodalClickEngine.kt) | \~200 | **截图→LLM→点击** 循环：对网页截图，送 LLM 分析，识别可点击元素坐标，虚拟点击，重复最多 N 轮 |

### 3.16 其他模块

| 文件                                                                                                                                | 职责                                    |
| --------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------- |
| [automation/AutomationWorker.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/automation/AutomationWorker.kt) | 定时自动化任务执行                             |
| [automation/AutomationStore.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/automation/AutomationStore.kt)   | 自动化任务持久化                              |
| [automation/AutomationModels.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/automation/AutomationModels.kt) | 自动化任务类型（HEARTBEAT\_NOTES/RUN\_PROMPT） |
| [calendar/IcsWriter.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/calendar/IcsWriter.kt)                   | iCalendar 导出                          |
| [calendar/CalendarModels.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/calendar/CalendarModels.kt)         | 日程数据模型                                |
| [pdf/PdfProcessor.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/pdf/PdfProcessor.kt)                       | PDF 渲染 + OCR + Vision 导入              |
| [tts/TtsPlayer.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/tts/TtsPlayer.kt)                             | Android TTS 语音播报                      |
| [env/EnvironmentProvider.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/env/EnvironmentProvider.kt)         | 环境信息（时间、位置、设备信息）                      |
| [MainActivity.kt](file:///e:/proj/Opedrgent/app/src/main/java/top/hsyscn/opedrgent/MainActivity.kt)                               | Android Activity 入口                   |

***

## 四、核心操作逻辑

### 4.1 消息发送与工具调用循环（runModel）

```
sendUserMessage(text)
  ↓ store.addMessage(USER)
  ↓ runModel(sessionId)
  ↓
┌─ for (round in 0 until 10) ─────────────────────────┐
│                                                       │
│  1. buildSystemPrompt(session)                        │
│     → PromptBuilder.buildSystemPrompt()               │
│       - 身份定义 + 环境信息 + 工具协议                 │
│       - 角色定义 + 任务指南 + 允许动作                 │
│       - 记忆上下文 + 来源引用 + 防御指令               │
│                                                       │
│  2. messages = session.messages + toolMessages        │
│     .takeLast(20)  ← 上下文窗口限制                   │
│                                                       │
│  3. streamLlm(config, system, messages)               │
│     → SSE 流式接收                                    │
│     → 实时更新 UI (streamingText/Reasoning)           │
│     → 完成后返回 StreamResult(content)                │
│                                                       │
│  4. ToolCallParser.parseToolCall(content)             │
│     → 解析 <tool_call> XML                            │
│     → 提取 toolName + params                         │
│                                                       │
│  5. 如果没有 tool call → break 循环                   │
│     如果是 question → 等待用户输入 → return           │
│     其他 → toolExecutor.execute(tp)                   │
│                                                       │
│  6. 将工具结果包装为 <tool_result> 消息               │
│     → toolMessages += [ASSISTANT, USER(toolResult)]   │
│     → 继续下一轮循环                                  │
│                                                       │
│  7. 循环结束 → store.addMessage(ASSISTANT, final)     │
│     → 更新 UI                                        │
└───────────────────────────────────────────────────────┘
```

### 4.2 XML 工具调用解析机制（ToolCallParser）

LLM 使用自定义 XML 格式嵌入工具调用（适用于不支持原生 function calling 的模型）：

```xml
<tool_call name="web_search">
<parameter name="query">最新AI发展趋势</parameter>
<parameter name="method">ddg</parameter>
</tool_call>
```

**解析流程**：

- **流式阶段**：`parseChunk()` 逐字符检测 `<tool_call>` 标签开始，判断是否在工具调用块内
- **完成阶段**：`parseToolCall()` 用正则提取 name 和 parameters；`parseToolCallFromComplete()` 返回 `List<ToolPart>`
- **清理**：`stripAllTags()` 移除 `<tool_call>`, `<thinking>`, `<ask_user>` 等标签，保留纯文本
- **思考链**：`extractThinkingParts()` 提取所有 `<thinking>` 块，返回 `List<ReasoningPart>`

**支持的标签**：`<tool_call>`, `<thinking>`, `<ask_user>`, `<question>`, `<final_answer>`

### 4.3 SSE 流式响应处理（LlmClient.streamChatCompletions）

```
POST /v1/chat/completions  { stream: true }
  ↓
OkHttp Callback.onResponse()
  ↓ 逐行读取 SSE data:
  ↓ "data: {"choices":[{"delta":{"content":"..."}}]}"
  ↓ onDelta(StreamDelta(content=..., reasoning=...))
  ↓ 实时追加到 StringBuilder
  ↓ Main thread 更新 StateFlow → UI 重绘
  ↓
"data: [DONE]" → onDone(fullContent)
  ↓
MainViewModel.streamLlm() ← suspendCancellableCoroutine
  ↓ Continuation.resume(StreamResult)
```

### 4.4 工具执行与回退路径（ToolExecutor.execute）

```
execute(ToolPart, config, system, useProviderSearch)
  ↓ route by tool name
  ↓
  "web_search"
    → method=ddg   → WebSearcher.searchDuckDuckGo()
                    → SourceFetcher.fetchUrl() x N
                    → 失败 → WebViewAgent.fetchUrl() 降级
    → method=webview → WebViewAgent.searchQuery() + fetchUrl()
    → method=mcp   → WebViewAgent.executeMcpScript() → 失败 → webview
    → method=screenshot → WebViewAgent.multimodalClick()
    → 默认        → 优先 DDG，失败降级 WebView
  
  "open_browser" → 返回 openBrowserUrl → UI 打开 WebView
  
  "deep_research" → DDG 搜索 → 抓取 → LLM 二次总结
  
  "read_url" → SourceFetcher → 失败 → WebViewAgent 降级
  
  "question" → 返回 COMPLETED → MainViewModel 设置 activeQuestion
  
  "generate_report/summary" → COMPLETED（系统在循环后处理）
```

### 4.5 WebView 浏览器自动化（WebViewAgent）

提供三种研究方法：

1. **querySearch**（`searchQuery(query, maxResults)`）
   - 加载 `https://www.bing.com/search?q=...`
   - 注入 JS 提取 `.b_algo` 结果（标题+URL+摘要）
   - 通过 `@JavascriptInterface` 回传结果
2. **MCP JS 注入**（`executeMcpScript(url, script)`）
   - 加载目标 URL
   - 执行自定义 JavaScript 代码
   - 结果通过 `OpedrgentBridge.postResult()` 回传
3. **截图多模态**（`multimodalClick(query, url, llm, config, system, maxRounds)`）
   - 打开页面 → 截图 → Base64 编码
   - 送 LLM Vision API 分析 → 识别可点击元素坐标
   - 虚拟点击 → 重复最多 maxRounds 轮

### 4.6 思考链展示机制

LLM 可在回复中嵌入 `<thinking>推理过程...</thinking>` 标签：

- `ToolCallParser.extractThinkingParts()` 提取
- UI 以折叠卡片形式展示（点击展开）
- 支持流式增量显示（思考文字实时出现）

### 4.7 MCP 协议通信流程

```
McpClient.initialize()
  ↓ JSON-RPC: {"method":"initialize","params":{...}}
  ↓ 选择 Transport: Stdio/Sse/Http
  ↓
McpClient.listTools()
  ↓ JSON-RPC: {"method":"tools/list"}
  ↓ 合并到 ToolPool
  ↓
McpClient.callTool(name, args, timeout=100s)
  ↓ JSON-RPC: {"method":"tools/call","params":{"name":...,"arguments":...}}
  ↓ 返回 McpToolResult
  ↓ 检测 session expired (-32001) → auto-reconnect → retry
  ↓ 检测 auth error (401) → throw McpAuthError
```

### 4.8 记忆管理生命周期（MemoryManager）

```
MemoryManager
  ├─ BuiltinMemoryProvider (最多 1 个外部 Provider)
  │
  ├─ initialize() → prefetch() → 加载持久化记忆
  ├─ syncTurn(turnInfo) → 回合同步
  ├─ onTurnStart() → 注入记忆上下文到 Prompt
  ├─ onSessionEnd() → 会话结束处理
  ├─ onMemoryWrite(entry) → 写入记忆
  └─ shutdown() → 清理资源
```

记忆在 System Prompt 中以 `<memory-context>` fence 块注入：

```
# Memory Context
<memory-context>
... 持久化记忆内容 ...
</memory-context>
```

### 4.9 权限引擎工作流（PermissionEngine）

```
checkPermission(toolName, args)
  ↓
  检查 PermissionMode:
  ├─ YOLO/BYPASS → 一律允许
  ├─ PLAN → 一律拒绝
  └─ DEFAULT/ACCEPT_EDITS → 查规则表
      ↓
      规则来源优先级:
      OVERRIDE > COMMAND > SETTINGS > SESSION > DEFAULT
      ↓
      DenialTracker:
      ├─ 同一工具 3 次成功 → 自动允许后续
      ├─ 频繁拒绝 → 提升到用户确认
      └─ 跟踪拒绝模式
```

5 种 PermissionMode（对应 Claude Code）：

- **DEFAULT** — 标准规则匹配
- **ACCEPT\_EDITS** — 自动接受编辑操作
- **PLAN** — 只读模式，拒绝所有工具调用
- **BYPASS** — 临时跳过权限检查
- **YOLO** — 完全信任模式

### 4.10 系统提示词组装（SystemPromptBuilder）

SystemPromptBuilder（agent/ 下的高级版本）采用优先级组装：

```
组装优先级:
  OVERRIDE > AGENT > CUSTOM > DEFAULT

AgentDefinition:
  - name, description, instructions
  - appendToDefault: Boolean (是否追加到默认 Prompt)
  - tools, skills, mcpServers 描述

默认 Prompt 块:
  - defaultSystemPrompts() → 通用角色定义
  - memoryGuidance() → 记忆使用指导
  - skillsGuidance() → 技能调用说明
  - toolsGuidance() → 工具列表和使用方法
  - environmentBlock() → 环境信息
```

当前实际使用：`MainViewModel` 调用 `PromptBuilder.buildSystemPrompt()`（utils/ 下版本），组装 11 个模块的系统提示词。

***

## 五、参考项目对比分析

| 特性                 | Hermes                   | Kilo Code         | OpenCode          | Claude Code        | **Opedrgent**                                        |
| ------------------ | ------------------------ | ----------------- | ----------------- | ------------------ | ---------------------------------------------------- |
| 平台                 | Python CLI               | VS Code Extension | VS Code Extension | Claude Desktop/CLI | **Android App**                                      |
| Agent Loop         | ReAct                    | Tool-calling      | Tool-calling      | Tool-calling       | **ReAct (XML)**                                      |
| Tool Calling       | Native function          | Native function   | Native function   | MCP + native       | **XML + MCP**                                        |
| Memory             | Multi-Provider lifecycle | Session only      | Session only      | Project memory     | **Multi-Provider + skills**                          |
| Permissions        | Simple allow/deny        | Settings-based    | CLI args          | 5-mode engine      | **5-mode + DenialTracker**                           |
| System Prompt      | Section builder          | Static            | Static            | Priority assembly  | **Priority assembly**                                |
| Streaming          | Text only                | Text only         | Text only         | Text + thinking    | **Text + thinking + tool cards**                     |
| Auto Evolution     | Memory extraction        | None              | None              | None               | **Memory + Skill + Automation**                      |
| Input Safety       | Basic                    | None              | None              | None               | **15 patterns + invisible chars + stream sanitizer** |
| Browser Automation | None                     | None              | Playwright        | CDP                | **WebView + MCP JS + Multimodal Click**              |

**Opedrgent 从各项目的学习**：

- **Hermes** → MemoryManager 生命周期模式（initialize/prefetch/syncTurn/onSessionEnd）
- **Claude Code** → PermissionEngine（5 种模式，YOLO/BYPASS/PLAN），SystemPromptBuilder 优先级组装
- **Kilo Code** → IDE 集成模式参考（WebView 替代 VS Code）
- **OpenCode** → MCP 协议完整实现参考

***

## 六、当前已知问题

### 🔴 P0：工具调用从未触发

**现象**：

```
runModel: no tool call found, breaking loop at round 0
// 每次 LLM 返回 162-190 字符纯文本，无 <tool_call> XML
```

**根因分析**：

1. 当前配置的 LLM 为 `mimo-v2-flash`（小米 MiMo），该模型可能不理解 XML 格式的工具调用协议
2. API 请求中**未使用 OpenAI 原生 function calling 格式**（未传 `tools`/`tool_choice` 字段）
3. 完全依赖 System Prompt 中的文本描述来引导模型输出 XML，但 `mimo-v2-flash` 似乎忽略了这些指令

**需修复方向**：

- 方案 A：在 `LlmClient` 中添加原生 function calling 支持（传 `tools` JSON Schema + `tool_choice: "auto"`）
- 方案 B：强化 System Prompt 中的 XML 输出指令（更明确的格式要求 + few-shot 示例）
- 方案 C：检测模型是否支持 function calling，不支持则强化 XML Prompt
- 方案 D：换用支持 function calling 的模型（如 `mimo-v2.5-pro`）

### 🟡 P2：WebView destroyed 警告

```
cr_AwContents: Application attempted to call on a destroyed WebView
```

按返回键时 WebView 已销毁但仍收到 loadingStateChanged 回调。需要在 `WebViewAgent.destroy()` 中增加更完善的生命周期管理。

### 🟡 P2：对话历史固定截断 20 条

`messages.takeLast(20)` 硬编码，无自适应压缩。长对话会丢失上下文。

### 🟢 P3：高级 Agent 模块未集成

`agent/MemoryManager.kt`、`agent/AgentLoopState.kt`、`agent/SystemPromptBuilder.kt`、`agent/permission/PermissionEngine.kt` 已编写但**未被 MainViewModel 实际调用**，仍使用 utils/ 下的旧版本。

### 🟢 P3：MCP 高级模块未集成

`McpClient.kt`、`ToolPool.kt`、`SkillSystem.kt`、`PromptCache.kt`、`AutoEvolutionEngine.kt`、`ChromeDevToolsMcp.kt`、`MultimodalClickEngine.kt` 等模块已编写但**未接入主流程**。

***

## 七、数据流全景

```
┌──────────────┐
│  用户输入     │ (文字 / 语音 / PDF / URL)
└──────┬───────┘
       ↓
┌──────────────────────────────────────────────────────┐
│ MainViewModel.sendUserMessage(text)                   │
│  ↓ store.addMessage(USER, text)                       │
│  ↓ runModel(sessionId)                                │
└──────────────────────┬───────────────────────────────┘
                       ↓
┌──────────────────────────────────────────────────────┐
│ Agent Loop (for round in 0..9)                        │
│  ┌─────────────────────────────────────────────────┐ │
│  │ 1. buildSystemPrompt()                           │ │
│  │    ├─ Identity + Environment + Tool Protocol    │ │
│  │    ├─ Role + Task Guide + Actions               │ │
│  │    ├─ Memory Context + Source References        │ │
│  │    └─ Defense (Anti-Saturation + Injection)     │ │
│  ├─────────────────────────────────────────────────┤ │
│  │ 2. streamLlm(config, system, messages[..20])    │ │
│  │    ├─ LlmClient.streamChatCompletions()          │ │
│  │    │   ├─ POST {stream:true, messages:[...]}    │ │
│  │    │   ├─ SSE line-by-line parse                │ │
│  │    │   ├─ onDelta → StateFlow → UI recompose    │ │
│  │    │   └─ onDone → Continuation.resume()        │ │
│  │    └─ Return StreamResult(content, reasoning)   │ │
│  ├─────────────────────────────────────────────────┤ │
│  │ 3. ToolCallParser.parseToolCall(content)         │ │
│  │    ├─ Regex: <tool_call name="...">              │ │
│  │    ├─ Extract: parameters (name=value)          │ │
│  │    └─ Extract: <thinking> blocks                │ │
│  ├─────────────────────────────────────────────────┤ │
│  │ 4. if no tool_call → break loop                  │ │
│  │    if question → set activeQuestion → return     │ │
│  │    else → toolExecutor.execute()                 │ │
│  │      ├─ web_search → DDG/WebView/MCP/Screenshot │ │
│  │      ├─ deep_research → search+fetch+LLM summary│ │
│  │      ├─ read_url → SourceFetcher → WebView      │ │
│  │      └─ open_browser → openBrowserUrl           │ │
│  ├─────────────────────────────────────────────────┤ │
│  │ 5. Wrap result as <tool_result> message          │ │
│  │    toolMessages += [ASSISTANT, USER(toolResult)] │ │
│  │    Continue loop (back to step 1)                │ │
│  └─────────────────────────────────────────────────┘ │
└──────────────────────┬───────────────────────────────┘
                       ↓
┌──────────────────────────────────────────────────────┐
│ Finalize                                              │
│  ├─ store.addMessage(ASSISTANT, cleanContent)         │
│  ├─ if artifact: store.addArtifact()                  │
│  ├─ update UiState (streaming → done)                 │
│  ├─ if TTS enabled: tts.speak(content)                │
│  └─ refreshSessions()                                 │
└──────────────────────┬───────────────────────────────┘
                       ↓
┌──────────────────────────────────────────────────────┐
│ Post-processing (user-initiated)                      │
│  ├─ generateSummary()     → LLM 生成摘要              │
│  ├─ generateReport()      → LLM 生成报告              │
│  ├─ suggestEvolution()    → 提炼记忆+技能             │
│  ├─ suggestAutomation()   → 提取定时任务              │
│  ├─ suggestCalendar()     → 提取日程                  │
│  └─ exportMarkdown()      → 导出 MD 文件              │
└──────────────────────────────────────────────────────┘
```

***

## 附录：完整文件索引

```
app/src/main/java/top/hsyscn/opedrgent/
├── MainActivity.kt                      # Android 入口
├── ui/
│   ├── MainViewModel.kt                 # ★ 核心：Agent Loop + 会话管理
│   ├── AppRoot.kt                       # ★ Compose UI 入口
│   ├── AutomationsScreen.kt             # 自动化管理界面
│   ├── FlowExt.kt                       # Flow 扩展
│   └── theme/{Theme,Color,Type}.kt      # Material 3 主题
├── network/
│   ├── LlmClient.kt                     # ★ LLM API (SSE + Multimodal)
│   ├── ToolExecutor.kt                  # ★ 工具执行引擎
│   ├── WebViewAgent.kt                  # ★ WebView 浏览器自动化
│   ├── WebSearcher.kt                   # DuckDuckGo 搜索
│   ├── SourceFetcher.kt                 # OkHttp 网页抓取
│   ├── WebResearchRouter.kt             # 研究路由分发
│   ├── WebResearchTypes.kt              # 研究类型定义
│   └── HttpClients.kt                   # OkHttp 配置
├── utils/
│   ├── ToolCallParser.kt                # ★ XML 工具调用解析
│   ├── PromptBuilder.kt                 # ★ 系统提示词构建器
│   ├── PromptSafety.kt                  # 输入消毒/注入防御
│   ├── PromptBlocks.kt                  # 不可信内容包装
│   ├── SourceValidator.kt               # 来源质量分析
│   ├── DebugLog.kt                      # 调试日志
│   └── BackgroundPermHelper.kt          # 后台权限
├── model/
│   └── Models.kt                        # ★ 全部数据模型
├── settings/
│   └── ApiSettings.kt                   # ★ API 配置 + 19 提供商
├── storage/
│   ├── ResearchStore.kt                 # 会话持久化
│   ├── MemoryStore.kt                   # 记忆存储
│   └── SkillsStore.kt                   # 技能存储
├── service/
│   └── KeepAliveService.kt              # WakeLock 后台服务
├── agent/                               # [未集成] 高级 Agent 模块
│   ├── MemoryManager.kt                 # 多 Provider 记忆编排
│   ├── AgentLoopState.kt                # 回合追踪状态
│   ├── SystemPromptBuilder.kt           # 优先级组装器
│   └── permission/
│       └── PermissionEngine.kt          # 5 模式权限引擎
├── mcp/                                 # [部分未集成] MCP 协议栈
│   ├── McpClient.kt                     # MCP 客户端
│   ├── McpTransport.kt                  # 传输层 (Stdio/SSE/HTTP)
│   ├── McpServer.kt                     # 内置 MCP 服务器
│   ├── McpTypes.kt                      # JSON-RPC 类型
│   ├── ToolPool.kt                      # 动态工具池
│   ├── chrome/
│   │   └── ChromeDevToolsMcp.kt         # CDP WebSocket
│   ├── skills/
│   │   └── SkillSystem.kt               # 技能索引
│   ├── cache/
│   │   └── PromptCache.kt               # 语义缓存
│   ├── evolution/
│   │   └── AutoEvolutionEngine.kt       # 自动进化
│   └── multimodal/
│       └── MultimodalClickEngine.kt     # 截图→LLM→点击
├── automation/
│   ├── AutomationWorker.kt              # 定时任务执行
│   ├── AutomationStore.kt               # 任务持久化
│   └── AutomationModels.kt              # 任务类型
├── calendar/
│   ├── IcsWriter.kt                     # iCal 导出
│   └── CalendarModels.kt                # 日程模型
├── pdf/
│   └── PdfProcessor.kt                  # PDF OCR + Vision
├── tts/
│   └── TtsPlayer.kt                     # Android TTS
└── env/
    └── EnvironmentProvider.kt           # 位置/时间/设备
```

**图例**：★ 核心文件 | \[未集成] 已编写但未接入主流程

***

> 本文档由自动代码库调研生成，覆盖全部 50 个 Kotlin 源文件、约 15,000 行代码。

