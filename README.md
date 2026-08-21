# Opedrgent

An open-source Android AI assistant built with Jetpack Compose and Kotlin.
基于 Jetpack Compose 和 Kotlin 构建的开源 Android AI 助手应用。

---

## Features / 功能特性

### Core AI Capabilities / 核心 AI 能力

- **Multi-Model Chat** — Supports multiple LLM APIs (OpenAI-compatible), streaming output + Thinking Mode
  **多模型对话** — 支持接入多种 LLM API（OpenAI 兼容接口），支持流式输出 + Thinking Mode
- **On-Device Inference** — LiteRT-LM (v0.12.0+) integration, Gemma 4 support, GPU/NPU acceleration
  **本地模型推理** — 集成 LiteRT-LM（v0.12.0+），支持 Gemma 4 等模型的端侧推理，GPU/NPU 加速
- **Tool Calling (Tool Call)** — Extensible tool architecture, LLM dynamically invokes 15+ tools: search, URL fetch, JS execution, Intent dispatch, calendar, etc.
  **工具调用 (Tool Call)** — 可扩展的 Tool 架构，LLM 可动态调用搜索、URL 读取、JS 执行、Intent、日历等 15+ 工具
- **Deep Research** — Multi-step search + web reading + auto-summarization + hybrid ranking workflow (HybridRankingEngine)
  **深度研究** — 多步搜索 + 网页阅读 + 自动总结 + 混合排序的研究工作流（HybridRankingEngine）

### Voice & Audio / 语音与音频

- **Speech-to-Text (STT)** — Multi-engine architecture: Sherpa-ONNX (offline, Paraformer/SenseVoice) + MiMO ASR + Android SpeechRecognizer, auto-fallback chain
  **语音识别（STT）** — 多引擎架构：Sherpa-ONNX（离线，Paraformer/SenseVoice）+ MiMO ASR + Android SpeechRecognizer，自动降级链
- **Full-Duplex Voice Call** — Interview Mode: AudioRecord || AudioTrack + hardware AEC + VAD + BargeIn, 5-state FSM
  **全双工语音通话** — Interview Mode：AudioRecord || AudioTrack + 硬件 AEC + VAD + BargeIn，5 状态有限状态机
- **Meeting Transcription** — Speaker diarization + multi-party speech-to-text + MeetingTranscriber persistence
  **会议转录** — 说话人分离 + 多人语音转文字 + MeetingTranscriber 持久化
- **TTS Synthesis** — MiMO TTS client + local TtsPlayer
  **TTS 语音合成** — MiMO TTS 客户端 + 本地 TtsPlayer 播放器

### Knowledge & Memory / 知识与记忆

- **Insight Sprout (Knowledge Sprout)** — 4-stage AI insight engine: Seed Extraction -> Cross-Domain Association -> AHA Insight -> Golden Quote Echo, with 3-layer progressive context injection (tags -> index -> detail)
  **知识发芽（Insight Sprout）** — 四阶段 AI 洞察生成引擎：种子提取 -> 跨领域关联 -> AHA 洞察 -> 金句回响，三层渐进式上下文注入（标签 -> 索引 -> 详情）
- **Hippocampus Memory System** — SQLite-based global index with keyword extraction + LIKE fuzzy matching, auto-indexes notes/conversations/recordings/sprouts; goal anchoring + drift detection for interview mode
  **海马记忆系统（Hippocampus Memory）** — SQLite 全局索引，关键词提取 + LIKE 模糊匹配，自动索引笔记/对话/录音/发芽；面试模式下目标锚定 + 漂移检测
- **Note System** — Full CRUD note management + folder classification + KnowledgeGraph + sprout/share/graph visualization
  **笔记系统** — 完整的 CRUD 笔记管理 + 文件夹分类 + 知识图谱（KnowledgeGraph）+ 笔记发芽/分享/图谱可视化
- **Knowledge Base** — KnowledgeBase document management and retrieval
  **知识库** — KnowledgeBase 文档管理与检索

### Browser & Automation / 浏览器与自动化

- **WebView Agent (Browser Automation)** — Automation engine for web content scraping, searching, screenshots, multimodal interaction clicks
  **浏览器接管（WebView Agent）** — 自动化引擎，支持网页内容抓取、搜索、截图、多模态交互点击
- **Automation Workflows** — AutomationWorker + AutomationStore for configurable scheduled/triggered tasks
  **自动化工作流（Automation）** — AutomationWorker + AutomationStore，可配置的定时/触发任务
- **App Widget** — Home screen widget quick entry
  **App Widget** — 桌面小组件快捷入口
- **KeepAlive Service** — Background keep-alive service
  **KeepAlive Service** — 后台保活服务

### Ham Mode (Satellite Communication) / Ham 模式（业余卫星通联）

- **Satellite Pass Prediction** — SGP4/SDP4 orbital propagation (full J2/J3/J4 perturbations, BSTAR atmospheric drag, GMST correction, deep-space SDP4), coarse-scan + fine-scan two-stage algorithm
  **卫星过境预测** — SGP4/SDP4 完整轨道传播（J2/J3/J4 摄动、BSTAR 大气阻力、GMST 修正、深空 SDP4），粗扫+精扫二阶策略
- **Multi-Source TLE Fallback** — Celestrak → AMSAT → legacy URL cascading TLE fetching with 24-hour local caching
  **多源 TLE Fallback** — Celestrak → AMSAT → 旧 URL 三级级联 TLE 获取，24 小时本地缓存
- **Built-in Satellite Database** — 16 amateur satellites (ISS, SO-50, AO-91/92, FO-29, CAS-3H, RS-44, etc.) with uplink/downlink frequencies, modulation modes, and min elevation angles
  **内置卫星数据库** — 16 颗业余卫星（ISS、SO-50、AO-91/92、FO-29、CAS-3H、RS-44 等），含上下行频率、调制方式、最低仰角
- **Active Satellite Search** — Fetch all active satellite TLEs from Celestrak, queryable by name or NORAD ID for non-preset satellites
  **活跃卫星搜索** — 从 Celestrak 拉取全部活跃卫星 TLE，支持按名称或 NORAD ID 搜索预置列表外的卫星
- **Smart Contact Log** — Post-transcription auto-fill from satellite DB (name/frequency/modulation), AI fills gaps only (RST, callsign, result), editable dialog with validation, ADIF/CSV export
  **智能通联日志** — 转写后从卫星数据库自动预填充（名称/频率/调制），AI 仅补漏（信号报告/呼号/结果），对话框可编辑校验，导出 ADIF/CSV
- **ADIF 3.1.4 Export** — Standard ADIF format compatible with QRZLOG / LoTW / ClubLog, with configurable station callsign and QTH gridsquare
  **ADIF 3.1.4 导出** — 标准 ADIF 格式，兼容 QRZLOG / LoTW / ClubLog，可配置本台呼号和 QTH 网格
- **Configurable thinking_budget** — User-adjustable thinking chain token budget for all thinking models
  **可配置 thinking_budget** — 用户可调整所有 thinking 模型的思维链 token 预算

### Skill System (V2) / 技能系统（V2）

- **SKILL.md Standard Frontmatter** — Complete metadata: name / description / version / category / require-secret
  **SKILL.md 标准 Frontmatter** — name / description / version / category / require-secret 完整元数据
- **JS Skill Sandbox Execution** — run_js tool -> SkillWebViewExecutor -> ai_edge_gallery_get_result() callback
  **JS Skill 沙箱执行** — run_js 工具 -> SkillWebViewExecutor -> ai_edge_gallery_get_result() 回调
- **Native Intent Dispatch** — run_intent tool -> 6 Intent types (email/SMS/calendar/URL/share/phone)
  **Native Intent 调用** — run_intent 工具 -> 6 种 Intent 类型（邮件/短信/日历/URL/分享/电话）
- **Three Import Methods** — URL remote loading, local file import, manual creation
  **三种导入方式** — URL 远程加载、本地文件导入、手动创建
- **Built-in JS Skills** — calculate-hash (hash calculator), mood-tracker-lite (mood tracker + dashboard)
  **内置 JS Skills** — calculate-hash（哈希计算）、mood-tracker-lite（心情追踪+仪表盘）
- **Built-in Text Skills** — critical-inquiry, insight-sprout, insight-review, text-refine, mimo-tts, multi-agent-collaboration
  **内置 Text Skills** — critical-inquiry、insight-sprout、insight-review、text-refine、mimo-tts、 multi-agent-collaboration
- **RequireSecret 3-Tier Auth** — ALLOW / ASK / DENY per-skill permission control
  **RequireSecret 三级授权** — ALLOW / ASK / DENY per-skill 权限控制

### Editor Team System / 编辑组系统

- **Multi-Role Editing Pipeline** — EditorTeamService + EditorRole definitions
  **多角色编辑管道** — EditorTeamService + EditorRole 定义
- **Skill Deep Integration** — EditorTeamSkillAdapter + SkillModelUnifier
  **Skill 深度集成** — EditorTeamSkillAdapter + SkillModelUnifier

### Calendar, Health & Documents / 日历、健康与文档

- **Calendar CRUD** — CalendarHelper + RunCalendarTool: create/query/update/delete system calendar events directly via ContentProvider (no user confirmation needed), supports natural language time parsing
  **日历 CRUD** — CalendarHelper + RunCalendarTool：通过 ContentProvider 直接创建/查询/修改/删除系统日历事件（无需用户确认），支持自然语言时间解析
- **Health Connect Integration** — HealthConnectHelper + HealthTool: read steps/heart rate/calories/distance/sleep from Health Connect, auto-inject today's summary into system prompt
  **Health Connect 健康数据集成** — HealthConnectHelper + HealthTool：从 Health Connect 读取步数/心率/卡路里/距离/睡眠，今日摘要自动注入 system prompt
- **PDF Processing** — PdfProcessor + OcrEngine (ML Kit Chinese+English OCR)
  **PDF 处理** — PdfProcessor + OcrEngine（ML Kit 中英文 OCR）
- **DOCX Processing** — DocxProcessor (Word document processing)
  **DOCX 处理** — DocxProcessor（Word 文档处理）

### Data & Network / 数据与网络

- **Smart Network Layer** — SmartCircuitBreaker (circuit breaking) + RateLimiter (rate limiting) + MultiLevelCacheManager (multi-level cache) + AdaptiveConcurrencyController (adaptive concurrency)
  **智能网络层** — SmartCircuitBreaker（熔断）+ RateLimiter（限流）+ MultiLevelCacheManager（多级缓存）+ AdaptiveConcurrencyController（自适应并发）
- **Search Enhancement** — HybridRankingEngine (hybrid ranking) + SemanticScorer (semantic scoring) + DynamicAuthorityScorer (dynamic authority scoring) + FreshnessCalculator (freshness) + ResultDeduplicator (deduplication)
  **搜索增强** — HybridRankingEngine（混合排序）+ SemanticScorer（语义评分）+ DynamicAuthorityScorer（动态权威评分）+ FreshnessCalculator（新鲜度）+ ResultDeduplicator（去重）
- **Security** — TlsFingerprintManager + UserAgentPool + ToolCallGuardrail + PromptSafety
  **安全** — TlsFingerprintManager + UserAgentPool + ToolCallGuardrail + PromptSafety

---

## Tech Stack / 技术栈

| Component | Technology |
|-----------|------------|
| UI Framework | Jetpack Compose + Material3 + WindowSizeClass |
| Language | Kotlin (JVM Toolchain 21) |
| Min SDK | API 26 (Android 8.0) |
| Target SDK | API 35 |
| Network | OkHttp + Jsoup |
| Local Storage | DataStore Preferences + Room + SQLite + EncryptedSecurity |
| OCR | Google ML Kit (Chinese + English) |
| Speech Recognition | Sherpa-ONNX + MiMO ASR + Android SpeechRecognizer |
| On-Device LLM | LiteRT-LM (v0.12.0+) + TFLite + GPU/NPU |
| Concurrency | Kotlin Coroutines + Flow |
| Serialization | kotlinx.serialization (JSON) |
| Background Tasks | WorkManager + ForegroundService |
| DI | Manual DI (no Hilt/Koin) |

## Project Structure / 项目结构

```
app/src/main/java/top/hsyscn/opedrgent/
├── MainActivity.kt              # App entry (singleTask + SEND Intent)
│                               # 应用入口（singleTask + SEND Intent）
│
├── ui/                          # Compose UI layer
│   ├── AppRoot.kt               # Main navigation entry (BottomNav + Routes)
│   ├── MainViewModel.kt         # Core ViewModel (state management center)
│   ├── SessionScreen.kt         # AI chat main screen
│   ├── ChatTab.kt               # Chat tab page
│   ├── InterviewScreen.kt       # Interview mode UI
│   ├── EditorTeamScreen.kt      # Editor team screen
│   ├── HomeDashboardScreen.kt   # Home dashboard
│   ├── KnowledgeBaseScreen.kt   # Knowledge base screen
│   ├── HippocampusScreen.kt     # Hippocampus memory management screen
│   ├── SettingsScreen.kt        # Settings page
│   ├── ExportScreen.kt          # Data export page
│   ├── AutomationsScreen.kt     # Automation workflow management
│   ├── InvisiblePartnerSettings.kt  # Invisible partner settings
│   ├── ImportFileScreen.kt      # File import page
│   ├── MeetingRecordScreen.kt   # Meeting records page
│   ├── RecordingTab.kt          # Recording tab
│   ├── Note*.kt                 # Note screens (list/editor/share/graph/sprout)
│   ├── components/              # Shared Composable components
│   │   ├── ChatComponents.kt    # Chat message components
│   │   ├── MarkdownRenderer.kt  # Markdown renderer
│   │   ├── StreamingComponents.kt  # Streaming output components
│   │   ├── HoldToDictate.kt     # Press-and-hold dictation component
│   │   ├── TextAndVoiceInput.kt # Text + voice input bar
│   │   ├── InputModeBar.kt      # Input mode switcher bar
│   │   ├── QuestionDock.kt      # Interview question dock
│   │   ├── RecordingCard.kt     # Recording card
│   │   ├── SttResultCard.kt     # STT result card
│   │   ├── ModelSelectorDialog.kt    # Model selector dialog
│   │   ├── ModelDownloadDialog.kt    # Model download dialog
│   │   └── ...                  # Other dialogs/cards
│   └── theme/                   # Material3 theme (Color/Type/Theme)
│
├── network/                     # Network layer
│   ├── LlmClient.kt             # LLM API client (streaming/non-streaming)
│   ├── WebViewAgent.kt          # WebView automation engine
│   ├── ToolExecutor.kt          # Tool dispatch hub
│   ├── SourceFetcher.kt         # URL content fetching
│   ├── WebSearcher.kt           # Web search engine
│   ├── WebResearchRouter.kt     # Deep research router
│   ├── HttpClients.kt           # OkHttp client management
│   ├── SmartCircuitBreaker.kt  # Smart circuit breaker
│   ├── RateLimiter.kt           # Rate limiter
│   ├── MultiLevelCacheManager.kt  # Multi-level cache manager
│   ├── HybridRankingEngine.kt  # Hybrid ranking engine
│   ├── SemanticScorer.kt        # Semantic scorer
│   ├── AdaptiveConcurrencyController.kt  # Adaptive concurrency controller
│   ├── TlsFingerprintManager.kt # TLS fingerprint manager
│   └── ...                      # Other network utilities
│
├── tools/                       # Tool Call implementations
│   ├── ToolRegistry.kt          # Dynamic tool registry
│   ├── ToolAnnotations.kt       # Tool annotation definitions
│   ├── RunJsTool.kt             # run_js - JS Skill sandbox execution
│   ├── RunIntentTool.kt         # run_intent - Android Intent dispatch
│   ├── RunCalendarTool.kt       # run_calendar - Calendar CRUD operations
│   ├── HealthTool.kt            # health_read - Health Connect data reading
│   ├── ReadUrlTool.kt           # read_url - URL content fetching
│   ├── WebSearchTool.kt         # web_search - Web search
│   ├── InsightSproutTool.kt     # insight_sprout - Knowledge sprout
│   ├── DeepResearchTool.kt      # deep_research - Deep research
│   ├── SpeechToTextTool.kt      # speech_to_text - Speech to text
│   ├── MimoTtsTool.kt           # mimo_tts - TTS synthesis
│   ├── GenerateReportTool.kt    # generate_report - Report generation
│   ├── ReverseGeocodeTool.kt    # reverse_geocode - Reverse geocoding
│   ├── OpenBrowserTool.kt       # open_browser - Open browser
│   ├── SatellitePassTool.kt     # satellite_pass - Satellite pass prediction (Ham mode)
│   └── prompts/                 # Per-tool prompt templates
│
├── tools/satellite/             # SGP4/SDP4 orbital mechanics (ported from Look4Sat)
│   ├── Constants.kt             # Physical constants (J2/J3/J4, GMST, Earth radius, etc.)
│   ├── OrbitalMath.kt           # Math helpers (kepler equation, rotations, coordinate transforms)
│   ├── OrbitalData.kt           # TLE parsing to orbital elements
│   ├── NearEarthObject.kt       # SGP4 propagation for near-earth orbits
│   ├── DeepSpaceObject.kt       # SDP4 propagation for deep-space orbits
│   ├── OrbitalObject.kt         # Unified orbital state interface
│   ├── GeoPos.kt                # Geodetic position handling
│   └── OrbitalPos.kt            # Orbital position output

├── stt/                         # Speech-to-text module
│   ├── AsrManager.kt            # ASR manager (unified entry point)
│   ├── SpeechEngine.kt          # Speech engine interface
│   ├── SherpaOnnxEngine.kt      # Sherpa-ONNX engine implementation
│   ├── MimoAsrEngine.kt         # MiMO ASR engine implementation
│   ├── AndroidSpeechRecognizer.kt  # System built-in speech recognizer
│   ├── MeetingTranscriber.kt    # Meeting transcriber
│   ├── AudioProcessor.kt        # Audio preprocessing
│   ├── ModelManager.kt          # STT model manager
│   ├── SttConfig.kt             # STT configuration
│   └── SttResult.kt             # STT result data class
│
├── interview/                   # Interview mode (core differentiator)
│   ├── InterviewAgent.kt        # Interview agent (meta-prompt decision)
│   ├── InterviewMode.kt         # Interview mode state definitions
│   ├── VoiceConversationEngine.kt  # v3 full-duplex voice engine
│   ├── FullDuplexAudioEngine.kt    # Low-level audio engine (Record/Track + AEC)
│   └── HippocampusMemory.kt     # Hippocampus memory (goal anchor + drift detection)
│
├── insight/                     # Knowledge sprout engine
│   ├── InsightSproutEngine.kt   # Sprout engine orchestrator
│   ├── SproutSeed.kt            # Stage 1: Seed extraction
│   ├── SproutConnection.kt      # Stage 2: Cross-domain association
│   ├── SproutInsight.kt         # Stage 3: AHA insight
│   ├── SproutQuote.kt           # Stage 4: Golden quote echo
│   ├── SproutPhase.kt           # Stage scheduling
│   ├── SproutPromptBuilder.kt   # Prompt builder
│   ├── SproutVoiceStatement.kt  # Voice statement extraction
│   └── KeywordTrigger.kt        # Keyword trigger
│
├── intelligence/                # Memory & intelligence system
│   ├── VectorMemory.kt          # Vector memory (SQLite + cosine similarity)
│   ├── MemoryBridge.kt          # Memory bridge (dual-write sync)
│   ├── MemoryDir.kt             # In-memory memory directory
│   ├── PersistenceLayer.kt      # Persistence abstraction layer
│   ├── SqlitePersistence.kt     # SQLite persistence implementation
│   ├── RecommendationEngine.kt  # Recommendation engine
│   ├── FeaturePipeline.kt       # Feature pipeline (agent interceptor chain)
│   ├── TokenBudgetMonitor.kt    # Token budget monitor
│   ├── PushNotificationHelper.kt # Push notification helper
│   └── UserBehaviorTracker.kt   # User behavior tracker
│
├── mcp/                         # MCP / Skill system
│   ├── skills/                  # V2 Skills
│   │   ├── SkillLoader.kt       # Dynamic loader
│   │   ├── SkillDefinition.kt   # Standard definition (SKILL.md frontmatter)
│   │   ├── SkillCurator.kt      # Skill curation
│   │   ├── CuratorService.kt    # Curation service
│   │   ├── SkillWebViewExecutor.kt  # JS sandbox executor
│   │   ├── SkillSystem.kt       # V1 Legacy skill system (@Deprecated)
│   │   └── GalleryBridge.kt     # JS callback bridge
│   ├── editors/                 # Editor team
│   │   ├── EditorTeamService.kt # Editing pipeline execution
│   │   ├── EditorRole.kt        # Role definitions
│   │   ├── EditorTeamSkillAdapter.kt  # Skill adapter
│   │   └── SkillModelUnifier.kt # Model unifier
│   └── tools/
│       └── ToolRegistry.kt      # Skill-driven dynamic tool registration
│
├── agent/                       # Multi-agent coordination
│   ├── AgentSwarm.kt            # Agent swarm
│   ├── MultiAgentOrchestrator.kt # Multi-agent orchestrator
│   └── ResearchState.kt         # Research state management
│
├── note/                        # Note system
│   ├── Note.kt                  # Note entity
│   ├── NoteDao.kt               # Room DAO
│   ├── NoteDatabase.kt          # Room Database
│   ├── NoteRepository.kt        # Repository layer
│   ├── Folder.kt                # Folder entity
│   ├── FolderDao.kt             # Folder DAO
│   ├── FolderDatabase.kt        # Folder database
│   ├── FolderRepository.kt      # Folder repository
│   ├── KnowledgeGraph.kt        # Knowledge graph
│   └── SproutService.kt         # Note sprout service
│
├── storage/                     # Data persistence
│   ├── MemoryStore.kt           # Memory store
│   ├── KnowledgeBase.kt         # Knowledge base
│   ├── KnowledgeBaseModel.kt    # Knowledge base model
│   ├── KbDocument.kt            # Knowledge base document
│   ├── HippocampusIndex.kt      # Hippocampus index
│   ├── HippocampusDatabase.kt   # Hippocampus database
│   ├── SproutReportStore.kt     # Sprout report store
│   ├── ResearchStore.kt         # Research result store
│   ├── SkillsStore.kt           # Skill store
│   ├── PersonaDetector.kt       # Persona detector
│   ├── NotificationHelper.kt    # Notification helper
│   └── WarmFeedbackService.kt   # Warm feedback service
│
├── llm/                         # On-device model inference
│   ├── LocalLlmEngine.kt        # Local LLM engine
│   ├── ModelConfig.kt           # Model configuration
│   ├── LocalModelConfig.kt      # Local model config
│   └── ModelDownloadManager.kt  # Model download manager
│
├── tts/                         # Text-to-speech
│   ├── TtsPlayer.kt             # TTS player
│   └── MimoTtsClient.kt         # MiMO TTS client
│
├── calendar/                    # Calendar module
│   ├── CalendarHelper.kt        # Calendar operation helper
│   ├── CalendarModels.kt        # Calendar data models
│   └── IcsWriter.kt            # ICS file writer
│
├── health/                      # Health Connect module
│   └── HealthConnectHelper.kt   # Health Connect helper (steps/heart rate/sleep)
│
├── pdf/                         # PDF processing
│   ├── PdfProcessor.kt          # PDF parsing/processing
│   └── OcrEngine.kt            # OCR engine (ML Kit)
│
├── docx/                        # DOCX processing
│   └── DocxProcessor.kt        # Word document processor
│
├── automation/                  # Automation workflows
│   ├── AutomationWorker.kt      # Workflow executor
│   ├── AutomationStore.kt       # Workflow storage
│   └── AutomationModels.kt      # Workflow data models
│
├── service/                     # System services
│   ├── KeepAliveService.kt      # Background keep-alive service
│   ├── ModelDownloadService.kt  # Model download foreground service
│   ├── DailyDigestNotifier.kt   # Daily digest notification
│   └── AutoSproutWorker.kt      # Auto-sprout Worker
│
├── widget/                      # App Widget
│   └── OpedrgentWidget.kt      # Home screen widget
│
├── security/                   # Security module (see AGENTS.md)
├── settings/                   # Settings
│   └── ApiSettings.kt          # API configuration management
├── env/                        # Environment detection
│   └── EnvironmentProvider.kt  # Environment provider
├── tool/                       # Tool base
│   └── Tool.kt                 # Tool interface definition
├── model/                      # Data models
│   └── Models.kt               # Common data models
└── utils/                      # Utilities
    ├── DebugLog.kt             # Debug logging
    ├── ContextCompressor.kt    # Context compression
    ├── PromptBuilder.kt        # Prompt building
    ├── ToolCallParser.kt       # Tool call parsing
    ├── ToolCallGuardrail.kt    # Tool call safety guardrail
    ├── StreamingMessageBuffer.kt  # Streaming message buffer
    ├── PromptCache.kt          # Prompt caching
    ├── PromptSafety.kt         # Prompt safety check
    ├── PromptBlocks.kt         # Prompt block assembly
    ├── PromptSection.kt        # Prompt sectioning
    ├── ToolPrompts.kt          # Tool prompt collection
    ├── ContextFileLoader.kt    # Context file loader
    ├── ModelInfo.kt            # Model info
    ├── StringUtils.kt          # String utilities
    ├── PlatformContext.kt      # Platform context
    ├── SourceValidator.kt      # Source validation
    └── BackgroundPermHelper.kt # Background permission helper
```

## Build Environment / 构建环境要求

| Item | Requirement |
|------|-------------|
| JDK | Java 21 (must use Android Studio bundled JBR) |
| Gradle | 8.x (managed via Wrapper) |
| SDK | compileSdk 35, minSdk 26, targetSdk 35 |
| NDK | arm64-v8a + armeabi-v7a |
| IDE | Android Studio (latest stable recommended) |

## Build / 构建

```bash
# Enter project directory / 进入项目目录
cd Opedrgent

# Set JAVA_HOME (Windows, use Android Studio JBR)
# 设置 JAVA_HOME（Windows，使用 Android Studio JBR）
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"

# Build Debug APK / 编译 Debug APK
./gradlew assembleDebug
```

**Notes / 注意事项**：

- System JDK 25+ is incompatible with Gradle 8.x -- **must** use Android Studio bundled JBR (Java 21)
  系统 JDK 25+ 与 Gradle 8.x 不兼容，**必须**使用 Android Studio 内置 JBR（Java 21）
- Sherpa-ONNX AAR must be manually placed in `app/libs/` directory (currently uses stub for compilation)
  Sherpa-ONNX AAR 需手动下载放入 `app/libs/` 目录（当前使用 stub 编译）


## Architecture / 核心架构

### Interview Mode / 面试模式

```
InterviewScreen (UI)
  -> MainViewModel.interviewState
    -> InterviewAgent (meta-prompt, LLM-agnostic decision)
      -> HippocampusMemory (goal anchor + drift detection + attention injection)
        -> VoiceConversationEngine v3
          -> FullDuplexAudioEngine (AudioRecord || AudioTrack + hardware AEC)
              VAD (RMS energy, 800ms timeout, 200f threshold)
              BargeIn (user interruption detection)
              DuplexState state machine (5 states)
```

### Memory System / 记忆系统

```
HippocampusIndex (SQLite global index)
  - Auto-indexes: notes, conversations, recordings, sprouts, interviews
  - Keyword extraction + LIKE fuzzy matching
  - Three scopes: GLOBAL / PROJECT / SESSION
MemoryStore (SharedPreferences)
  - User manual memories + note summary sync
  - Injected into LLM system prompt as user profile
```

### Health Data Flow / 健康数据流

```
SettingsScreen (toggle) -> ACTIVITY_RECOGNITION permission
  -> Health Connect permissions (steps/heart rate/sleep/calories)
    -> MainViewModel.buildSystemPrompt()
      -> HealthConnectHelper.getHealthSummaryForPrompt()
        -> Auto-inject today's summary into system prompt
    -> LLM can also call health_read tool for detailed data
```

### Skill Data Flow / Skill 数据流

```
User(SkillsScreen) -> Import(SKILL.md) -> SkillLoader(parse & store)
    -> LLM chat(tool_calls) -> ToolExecutor(run_js/run_intent/calendar/search...)
        -> SkillWebViewExecutor / Android Intent / CalendarHelper
            -> ai_edge_gallery_get_result() callback
                -> Result returned to LLM -> Stream output to user
用户(SkillsScreen) -> 导入(SKILL.md) -> SkillLoader(解析存储)
    -> LLM对话(tool_calls) -> ToolExecutor(run_js/run_intent/calendar/search...)
        -> SkillWebViewExecutor / Android Intent / CalendarHelper
            -> ai_edge_gallery_get_result() 回调
                -> 结果返回 LLM -> 流式输出给用户
```

## Assets Structure / Assets 结构

```
assets/
├── skills/
│   ├── calculate-hash/              # JS Skill: Hash calculator / 哈希计算器
│   ├── mood-tracker-lite/           # JS Skill: Mood tracker / 心情追踪器
│   ├── ...
│   └── multi-agent-collaboration.md # Text Skill: Multi-agent collaboration / 多智能体协作
└── ham_satellites.json              # Amateur satellite database (16 entries) / 业余卫星数据库（16 颗）
```

## Documentation / 文档

- [使用说明书 / User Manual](docs/wiki/User-Manual.md) — 面向最终用户的完整使用指南
- [常见问题 / FAQ](docs/wiki/FAQ.md) — 安装、使用、构建过程中的常见问题解答
- [Wiki 首页](docs/wiki/Home.md) — 技术架构与模块导航
- [构建指南](docs/wiki/Building.md) — 环境要求与编译步骤


