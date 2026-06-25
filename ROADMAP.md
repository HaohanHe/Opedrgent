# Opedrgent 项目路线图 (Roadmap)

> **项目定位**: Android 端 AI 知识工作站 — 集对话、语音、搜索、笔记、知识图谱、面试、自动化、健康数据、日历管理于一体的个人 AI 助手
>
> **当前版本**: v2.5 — 阶跃星辰(StepFun)全平台集成版 + 设备数据集成
>
> **构建状态**: `./gradlew assembleDebug` 通过 (Java 21 / Android Studio JBR)

---

## 一、系统架构总览

### 1.1 分层架构

```
┌─────────────────────────────────────────────────────────────┐
│                    UI Layer (Compose)                        │
│  SessionScreen | InterviewScreen | KnowledgeBaseScreen      │
│  SettingsScreen | Note*Screen | EditorTeamScreen            │
│  HomeDashboard | Export | Automations | Recording           │
├─────────────────────────────────────────────────────────────┤
│                  ViewModel Layer                             │
│              MainViewModel (核心调度)                         │
├──────────────────┬──────────────────────────────────────────┤
│   Tool Layer     │          Network Layer                    │
│   ToolExecutor   │  LlmClient (双协议: OpenAI + Anthropic)   │
│   ├─ 内置工具    │  ├─ Chat Completions (+ JSON Mode)        │
│   │  web_search  │  ├─ Messages API (Anthropic兼容)          │
│   │  read_url    │  └─ Step Plan 推理参数                     │
│   │  run_intent  │  StepSearchTool → POST /v1/search         │
│   │  run_calendar│  WebViewAgent (浏览器自动化)               │
│   │  health_read │                                          │
│   │  speech_to_text                                            │
│   │  mimo_tts    │                                          │
│   ├─ 阶跃工具集  │  HttpClients (OkHttp池)                   │
│   │  step_search │  ├─ RateLimiter (令牌桶)                  │
│   │  step_rag    │  ├─ SmartCircuitBreaker (熔断)            │
│   │  step_image_edit │ ├─ MultiLevelCacheManager (L1+L2)    │
│   │  step_image_gen  │ └─ AdaptiveConcurrencyController     │
│   │  step_mobile_agent                                        │
│   └─ Skill工具  │                                          │
├──────────────────┼──────────────────────────────────────────┤
│   STT Layer      │          TTS Layer                       │
│   AsrManager     │  TtsPlayer (三引擎路由)                   │
│   ├─ SherpaONNX  │  1. System TTS (Android原生)              │
│   ├─ StepAudio   │  2. StepAudio TTS (Global+Inline) ★      │
│   └─ MiMo ASR    │  3. MiMo TTS (本地推理)                  │
│                  │                                          │
│   Interview Mode │  StepRealtimeClient (WebSocket) ★         │
│   ├─ VoiceConversationEngine v3                              │
│   ├─ FullDuplexAudioEngine (AEC/VAD/BargeIn/5-state FSM)    │
│   └─ HippocampusMemory (目标锚定+漂移检测)                   │
├──────────────────┴──────────────────────────────────────────┤
│                Storage & Memory Layer                        │
│  ┌─ 本地持久化 ──────────────────────────────────────────┐   │
│  │ KnowledgeBase (SQLite全文检索)                          │   │
│  │ HippocampusIndex (SQLite全局索引, 关键词+LIKE匹配)      │   │
│  │ MemoryStore (SharedPreferences, 用户记忆+笔记同步)      │   │
│  │ Note/Folder (Room DAO)                                  │   │
│  │ KnowledgeGraph (笔记关系图)                              │   │
│  └────────────────────────────────────────────────────────┘   │
│  ┌─ 云端扩展 (StepFun) ──────────────────────────────────┐   │
│  │ StepVectorStoreClient (向量存储 RAG)                   │   │
│  │ StepFileParserClient (文件解析 retrieval-text)          │   │
│  └────────────────────────────────────────────────────────┘   │
├─────────────────────────────────────────────────────────────┤
│                 Insight & Agent Layer                        │
│  InsightSproutEngine (4阶段知识发芽)                          │
│  AgentSwarm (LLM自主调度) + MultiAgentOrchestrator (编排)     │
│  EditorTeamService (编辑团队管线)                             │
│  McpManager / AgentService (MCP协议层)                       │
│  NoteSyncService (WebDAV云同步)                               │
└─────────────────────────────────────────────────────────────┘
```

### 1.2 阶跃星辰(StepFun) 接入矩阵

| 能力域 | API 类型 | 客户端文件 | 工具注册 | 状态 |
|--------|----------|-----------|---------|------|
| LLM 对话 | Chat Completions | LlmClient.kt | 内置 | 已接入 |
| LLM 对话 | Messages (Anthropic) | LlmClient.kt streamMessages() | 内置 | 已接入 |
| JSON Mode | response_format | LlmClient.kt jsonMode 参数 | 内置 | 已接入 |
| 实时语音 | WebSocket | StepRealtimeClient.kt | InterviewMode | 已接入 |
| ASR 语音转文字 | SSE 流式 | StepAudioAsrEngine.kt | AsrManager | 已接入 |
| TTS 文字转语音 | REST | StepAudioTtsClient.kt | TtsPlayer | 已接入 |
| 原生搜索 | POST /v1/search | StepSearchTool.kt | step_search | 已接入 |
| 知识库 RAG | Vector Stores | StepVectorStoreClient.kt | step_rag | 已接入 |
| 图像编辑 | multipart /v1/images/edits | StepImageEditTool.kt | step_image_edit | 已接入 |
| 图生图 | JSON /v1/images/image2image | StepImageEditTool.kt | step_image_to_image | 已接入 |
| 图片生成 | JSON /v1/images/generations | StepImageGenTool.kt | (隐式) | 已接入 |
| 视频理解 | multimodal chat | LlmClient.kt | 内置 | 已接入 |
| 文件解析 | File Upload + Content | StepFileParserClient.kt | KB回退 | 已接入 |
| 手机操作Agent | multimodal chat | StepMobileAgentTool.kt | step_mobile_agent | 已接入 |
| MCP 协议 | stdio/stream | McpManager.kt | AgentService | 已接入 |

---

## 二、已完成里程碑

### Phase 0 — 基础框架
- [x] Compose UI 全套界面 (Session/Chat/Interview/Note/KnowledgeBase/Settings)
- [x] LlmClient 多模型支持 (OpenAI 兼容协议, 流式SSE)
- [x] Tool Registry 动态工具注册系统
- [x] 记忆系统 (HippocampusIndex + MemoryStore)
- [x] 笔记系统 CRUD + 知识图谱
- [x] Insight Sprout 4阶段知识发芽引擎
- [x] 编辑团队 (EditorTeam) 多角色协作

### Phase 1 — 语音与面试模式
- [x] 多引擎 STT (SherpaONNX + Android SpeechRecognizer + MiMo)
- [x] 全双工音频引擎 (AEC/VAD/BargeIn/5状态FSM)
- [x] Hippocampus 记忆 (目标锚定 + 漂移检测)
- [x] InterviewAgent 元提示词 + LLM无关决策
- [x] 模型下载管理器 (GitHub + GitCode镜像回退)

### Phase 2 — 阶跃星辰全平台集成 (当前)
- [x] 双协议 LLM (Chat Completions + Messages/Anthropic)
- [x] JSON Mode 强制结构化输出
- [x] StepAudio 2.5 Realtime WebSocket 实时语音
- [x] StepAudio 2.5 ASR 云端语音转文字
- [x] StepAudio 2.5 TTS 云端文字转语音 (Global+Inline)
- [x] 原生搜索 API (/v1/search research/general)
- [x] 知识库 RAG (Vector Stores + retrieval-text)
- [x] 图像编辑 + 图生图 + 图片生成 (三端点全覆盖)
- [x] 视频理解 (step-1o-turbo-vision 多模态)
- [x] 文件解析 (file-extract/retrieval-text intent)
- [x] 手机操作 Agent (step-3.7-flash-mobile-agent)
- [x] MCP 协议支持层
- [x] 搜索查询净化 (停用词去重截断)

### Phase 2.5 — 设备数据集成 (最新完成)
- [x] **Health Connect 健康数据**: 步数/心率/卡路里/距离/睡眠读取，今日摘要自动注入 system prompt
- [x] **日历 CRUD**: 通过 ContentProvider 直接创建/查询/修改/删除系统日历事件，支持自然语言时间解析
- [x] **发芽三层渐进上下文**: 标签层(海马体关键词) -> 索引层(标题+一句话概要) -> 联网搜索验证
- [x] **发芽 JSON 解析加固**: 正则兜底支持未转义双引号，max_tokens 提升到 32768
- [x] **笔记发芽数据持久化**: 修复自动保存覆盖 sproutReportJson 的 bug，重启后发芽数据不丢失

---

## 三、下一步计划 (Roadmap)

### Phase 3 — 智能体深化与自动化 [近期]

#### 3.1 Mobile Agent 自动化闭环
- [x] **截图→分析→执行** 全自动链路: StepMobileAgentTool 输出动作 → 自动调用 RunIntentTool/WebViewAgent 执行
- [x] Accessibility Service 集成: 无障碍服务获取 UI 节点树，提升 mobile agent 准确率
- [x] 操作历史记录与回放: 记录每步操作，支持任务模板化复用

#### 3.2 知识库增强
- [x] **混合检索**: 关键词(本地BM25) + 向量语义(云端RAG) 融合排序
- [x] 知识库增量同步: 监控文件变化，自动重新解析并更新向量存储
- [x] 跨会话记忆持久化: InterviewMode 的 Hippocampus 数据写入长期存储
- [x] 知识图谱可视化增强: 节点关系动态布局 + 时间线视图

#### 3.3 多模态能力完善
- [x] **图片理解集成**: 利用 step-1o-turbo-vision 的图像理解能力做 OCR/图表分析/截图问答
- [x] 音频后处理: ASR 结果自动标点、分段、说话人分离(diaryzation)
- [x] 视频摘要: 上传视频 URL → 提取关键帧 → 生成结构化摘要

### Phase 4 — 协作与生态 [中期]

#### 4.1 Skill 生态完善
- [x] Skill Marketplace: 从远程 URL 动态加载 SKILL.md 定义的技能
- [x] Skill 沙箱安全加固: WebView JS 执行的资源/CPU/网络限制
- [x] Skill 版本管理与热更新: 不重启 App 即可更新技能定义

#### 4.2 多智能体编排
- [x] AgentSwarm 任务分解: 复杂任务自动拆分为子任务分配给专业 Agent
- [x] Agent 间通信协议: 统一的消息格式和上下文传递机制
- [x] Research Agent 深度研究: WebResearchRouter + HybridRanking 的完整链路

#### 4.3 数据导出与同步
- [x] Markdown/PDF/HTML 多格式导出优化
- [x] 笔记云同步: 支持第三方云盘/WebDAV
- [x] 会话归档与搜索: 历史对话的全文检索

### Phase 5 — 性能与体验优化 [持续]

#### 5.1 性能
- [x] LLM 响应缓存: 相似 Query 复用缓存结果 (PromptCache 增强)
- [x] 并发请求合并: 同时多个 tool call 合并为批量请求
- [x] 离线模式降级: 网络不可用时切换到纯本地模式 (LocalLlmEngine)

#### 5.2 用户体验
- [x] 快捷指令 (Slash Commands): `/search` `/rag` `/interview` `/export` `/orchestrate` 等
- [x] 自定义工作流 (Automations): 用户可编程的触发器-动作链
- [x] 深色/浅色主题跟随系统
- [x] 平板适配: 大屏布局优化

---

## 四（续）、Phase 6 — 质量与生态成熟 [下一步]

### 6.1 云同步体验完善
- [x] WebDAV 设置 UI: 服务器地址/用户名/密码/远端路径配置 + 连接测试 + 手动同步
- [ ] 自动同步: 笔记变更后延迟自动同步（WorkManager 定时触发）
- [ ] 同步冲突解决 UI: 冲突时展示双方差异，让用户选择保留版本
- [ ] 增量同步优化: 仅同步 updatedAt 变化的笔记，避免全量遍历

### 6.2 多智能体体验增强
- [x] Orchestrator 模式接入: `/orchestrate` 指令，预设角色池（研究者/分析师/编辑者）
- [ ] Agent 执行过程可视化: 实时展示每个 Agent 的思考过程和工具调用
- [ ] Agent 自定义角色: 用户可自定义 Agent 角色和 system prompt
- [ ] Agent 执行历史: 保存多 Agent 协作的完整日志，支持回放

### 6.3 测试与质量保障
- [ ] 核心模块单元测试: ToolCallParser / HybridRankingEngine / WebDavClient / SlashCommands
- [ ] ViewModel 集成测试: runModel / runSwarm / runOrchestration 关键路径
- [ ] LLM 响应 Mock 框架: 离线测试 LLM 交互流程
- [ ] CI 集成: GitHub Actions 或 GitLab CI 自动构建验证

### 6.4 AgentService 渐进迁移
- [ ] AgentService 功能对齐: 确保 AgentService 路径覆盖 runModel 的全部能力
- [ ] A/B 切换测试: `useAgentService = true` 路径的端到端验证
- [ ] 移除旧路径: 验证通过后清理 MainViewModel 中的直调逻辑

### 6.5 用户体验打磨
- [ ] 首次使用引导 (Onboarding): API Key 配置向导 + 功能亮点介绍
- [ ] 笔记列表性能优化: 大量笔记时的 LazyList 渲染优化
- [ ] 错误消息优化: 网络/模型/API 错误的用户友好提示和操作建议
- [ ] 国际化完善: 英文/日文界面的全覆盖翻译

---

## 四、技术债务与已知问题

| 问题 | 影响 | 计划修复 |
|------|------|----------|
| 手动 DI (无 Hilt/Koin) | 新增依赖注入繁琐 | 保持现状 (项目规则: 手动DI一致性) |
| Sherpa-ONNX AAR 需手动放置 | 新开发者上手成本高 | 文档说明即可 |
| 单元测试覆盖不足 | 回归风险 | Phase 5 补充核心模块测试 |
| `!!` 非空断言警告 | 运行时 NPE 风险 | 逐步替换为安全调用 |

---

## 五、项目规则

### 5.1 编码规范
- 语言: Kotlin (主) + 少量 XML (布局资源/Manifest)
- UI 框架: Jetpack Compose (Material3)
- 注释语言: 中文 (代码注释与用户可见文本一致)
- **禁止在 prompt/log 文本中使用 emoji**
- **依赖注入**: 手动 DI，不使用 Hilt/Koin/Dagger（保持全项目一致性）

### 5.2 构建规则
- JDK: Java 21 (`jvmToolchain(21)`), 使用 Android Studio JBR
- 系统 JDK 25+ 与 Gradle 8.x 不兼容
- NDK ABI: 仅 `arm64-v8a` 和 `armeabi-v7a`
- Sherpa-ONNX AAR: 手动放置于 `app/libs/`
- 构建命令: `$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; ./gradlew assembleDebug`
- 无独立 lint/typecheck 命令，验证通过 Gradle build

### 5.3 目录规范
- `Opedrgent/` — 主项目源码 (唯一构建目标)
- `gallery/` — Google AI Edge Gallery 参考 (不参与构建)
- `参考/` — 只读参考代码库 (opencode/meilisearch/qdrant 等, 严禁修改)
- `base_apk_extracted/`, `base_decompiled/` — APK 逆向工程产物

### 5.4 Git 规范
- 分支策略: main (开发) → origin/master (远端)
- Commit message 格式: `type: 中文简短描述`
  - `feat:` 新功能
  - `fix:` Bug 修复
  - `refactor:` 重构 (不改行为)
  - `docs:` 文档变更
- **禁止**: `git push --force`, `git reset --hard`, `git clean -f`
- 敏感文件 (.env, credentials.json) **绝不提交**

### 5.3 API 设计原则
- 所有外部 API 调用必须通过 OkHttp client (统一超时/重试/拦截器)
- 流式响应统一使用 SSE 解析 (Server-Sent Events)
- 工具调用遵循 ToolSet/ToolBinding 接口规范
- 错误处理: 网络异常捕获 + 用户友好的错误消息 + DebugLog 详细日志
- 向后兼容: 设置项变更必须有迁移逻辑 (如 TTS boolean → enum)

### 5.4 安全规则
- API Key 存储使用 DataStore/EncryptedSharedPreferences
- RequireSecretManager 三级授权: ALLOW/ASK/DENY per-skill
- WebView JS 沙箱执行隔离 (SkillWebViewExecutor)
- 文件上传前校验大小 (单文件 ≤64MB) 和类型白名单

---

## 六、技术栈清单

| 层级 | 技术 | 用途 |
|------|------|------|
| UI | Jetpack Compose + Material3 | 声明式UI |
| 异步 | Kotlin Coroutines + Flow | 并发与响应式 |
| 网络 | OkHttp 4.x | HTTP客户端池 |
| 本地DB | Room (SQLite) | 笔记/知识库/配置持久化 |
| 全局索引 | HippocampusIndex (SQLite + LIKE) | 跨会话记忆索引 |
| 用户记忆 | MemoryStore (SharedPreferences) | 用户手动记忆+笔记同步 |
| PDF处理 | ML Kit (中文+英文OCR) | 文档识别 |
| 语音STT | Sherpa-ONNX (本地) + StepAudio (云端) + MiMo | 多引擎ASR |
| 语音TTS | Android System + StepAudio (云端) + MiMo | 多引擎TTS |
| 实时语音 | WebSocket (OkHttp) | StepAudio Realtime |
| LLM | OpenAI Compatible + Anthropic Messages | 多协议LLM |
| 技能系统 | WebView + JavaScript Bridge | V2 Skills 动态加载 |
| 依赖注入 | 手动 Constructor Injection | 全项目一致性 |
| 构建 | Gradle 8.x + Kotlin DSL | Android 构建 |
