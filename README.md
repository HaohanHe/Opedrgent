# Opedrgent

Android 端 AI 智能助手应用，基于 Jetpack Compose + Kotlin 构建。

## 功能特性

### 核心 AI 能力
- **多模型对话**：支持接入多种 LLM API（OpenAI 兼容接口），支持流式输出 + Thinking Mode
- **本地模型推理**：集成 LiteRT-LM（v0.12.0+），支持 Gemma 4 等模型的端侧推理，GPU/NPU 加速
- **工具调用 (Tool Call)**：可扩展的 Tool 架构，LLM 可动态调用搜索、URL 读取、JS 执行、Intent、日历等 15+ 工具
- **深度研究**：多步搜索 + 网页阅读 + 自动总结 + 混合排序的研究工作流（HybridRankingEngine）

### 语音与音频
- **语音识别（STT）**：多引擎架构 — Sherpa-ONNX（离线，Paraformer/SenseVoice）+ MiMO ASR + Android SpeechRecognizer，自动降级链
- **全双工语音通话**：Interview Mode — AudioRecord || AudioTrack + 硬件 AEC + VAD + BargeIn，5 状态有限状态机
- **会议转录**：说话人分离 + 多人语音转文字 + MeetingTranscriber 持久化
- **TTS 语音合成**：MiMO TTS 客户端 + 本地 TtsPlayer 播放器

### 知识与记忆
- **知识发芽（Insight Sprout）**：四阶段 AI 洞察生成引擎 — 种子提取 -> 跨领域关联 -> AHA 洞察 -> 金句回响
- **海马记忆系统（Hippocampus Memory）**：目标锚定 + 漂移检测 + 注意力注入，解决长对话 AI 注意力分散问题
- **三层记忆架构**：MemoryDir（内存，TTL 过期） <-> MemoryBridge（双写同步） <-> VectorMemory（SQLite 持久化，余弦相似度检索）
- **笔记系统**：完整的 CRUD 笔记管理 + 文件夹分类 + 知识图谱（KnowledgeGraph）+ 笔记发芽/分享/图谱可视化
- **知识库**：KnowledgeBase 文档管理与检索

### 浏览器与自动化
- **浏览器接管（WebView Agent）**：自动化引擎，支持网页内容抓取、搜索、截图、多模态交互点击
- **自动化工作流（Automation）**：AutomationWorker + AutomationStore，可配置的定时/触发任务
- **App Widget**：桌面小组件快捷入口
- **KeepAlive Service**：后台保活服务

### Skill 系统（V2）
- **SKILL.md 标准 Frontmatter**：name / description / version / category / require-secret 完整元数据
- **JS Skill 沙箱执行**：run_js 工具 -> SkillWebViewExecutor -> ai_edge_gallery_get_result() 回调
- **Native Intent**：run_intent 工具 -> 6 种 Intent 类型（邮件/短信/日历/URL/分享/电话）
- **三种导入方式**：URL 远程加载、本地文件导入、手动创建
- **内置 JS Skills**：calculate-hash（哈希计算）、mood-tracker-lite（心情追踪+仪表盘）
- **内置 Text Skills**：critical-inquiry、insight-sprout、insight-review、text-refine、mimo-tts、multi-agent-collaboration
- **RequireSecret 三级授权**：ALLOW / ASK / DENY per-skill 权限控制

### 编辑组系统（Editor Team）
- **多角色编辑管道**：EditorTeamService + EditorRole 定义
- **Skill 深度集成**：EditorTeamSkillAdapter + SkillModelUnifier

### 日历与文档
- **日历集成**：CalendarHelper + IcsWriter（ICS 日历文件读写）
- **PDF 处理**：PdfProcessor + OcrEngine（ML Kit 中英文 OCR）
- **DOCX 处理**：DocxProcessor（Word 文档处理）

### 数据与网络
- **智能网络层**：SmartCircuitBreaker（熔断）+ RateLimiter（限流）+ MultiLevelCacheManager（多级缓存）+ AdaptiveConcurrencyController（自适应并发）
- **搜索增强**：HybridRankingEngine（混合排序）+ SemanticScorer（语义评分）+ DynamicAuthorityScorer（动态权威评分）+ FreshnessCalculator（新鲜度）+ ResultDeduplicator（去重）
- **安全**：TlsFingerprintManager + UserAgentPool + ToolCallGuardrail + PromptSafety

## 技术栈

| 组件 | 技术 |
|------|------|
| UI 框架 | Jetpack Compose + Material3 + WindowSizeClass |
| 语言 | Kotlin (JVM Toolchain 21) |
| 最低 SDK | API 26 (Android 8.0) |
| 目标 SDK | API 35 |
| 网络 | OkHttp + Jsoup |
| 本地存储 | DataStore Preferences + Room + SQLite + EncryptedSecurity |
| OCR | Google ML Kit (中英文) |
| 语音识别 | Sherpa-ONNX + MiMO ASR + Android SpeechRecognizer |
| 本地 LLM | LiteRT-LM (v0.12.0+) + TFLite + GPU/NPU |
| 协程 | Kotlin Coroutines + Flow |
| 序列化 | kotlinx.serialization (JSON) |
| 后台任务 | WorkManager + ForegroundService |
| 依赖注入 | 手动 DI（无 Hilt/Koin） |

## 项目结构

```
app/src/main/java/top/hsyscn/opedrgent/
├── MainActivity.kt              # 应用入口（singleTask + SEND Intent）
│
├── ui/                          # Compose UI 层
│   ├── AppRoot.kt               # 主界面导航入口（BottomNav + Routes）
│   ├── MainViewModel.kt         # 核心 ViewModel（状态管理中心）
│   ├── SessionScreen.kt         # AI 对话主界面
│   ├── ChatTab.kt               # 对话标签页
│   ├── InterviewScreen.kt       # 面试模式 UI
│   ├── EditorTeamScreen.kt      # 编辑组界面
│   ├── HomeDashboardScreen.kt   # 首页仪表盘
│   ├── KnowledgeBaseScreen.kt   # 知识库界面
│   ├── HippocampusScreen.kt     # 海马记忆管理界面
│   ├── SettingsScreen.kt        # 设置页
│   ├── ExportScreen.kt          # 数据导出页
│   ├── AutomationsScreen.kt     # 自动化工作流管理
│   ├── InvisiblePartnerSettings.kt  # 隐形伙伴设置
│   ├── ImportFileScreen.kt      # 文件导入页
│   ├── MeetingRecordScreen.kt   # 会议记录页
│   ├── RecordingTab.kt          # 录音标签页
│   ├── Note*.kt                 # 笔记相关（列表/编辑/分享/图谱/发芽）
│   ├── components/              # 共享 Composable 组件
│   │   ├── ChatComponents.kt    # 聊天消息组件
│   │   ├── MarkdownRenderer.kt  # Markdown 渲染器
│   │   ├── StreamingComponents.kt  # 流式输出组件
│   │   ├── HoldToDictate.kt     # 按住录音组件
│   │   ├── TextAndVoiceInput.kt # 文本+语音输入栏
│   │   ├── InputModeBar.kt      # 输入模式切换栏
│   │   ├── QuestionDock.kt      # 面试问题 dock
│   │   ├── RecordingCard.kt     # 录音卡片
│   │   ├── SttResultCard.kt     # STT 结果卡片
│   │   ├── ModelSelectorDialog.kt    # 模型选择对话框
│   │   ├── ModelDownloadDialog.kt    # 模型下载对话框
│   │   └── ...                  # 其他对话框/卡片组件
│   └── theme/                   # Material3 主题（Color/Type/Theme）
│
├── network/                     # 网络层
│   ├── LlmClient.kt             # LLM API 客户端（流式/非流式）
│   ├── WebViewAgent.kt          # WebView 自动化引擎
│   ├── ToolExecutor.kt          # 工具执行调度中心
│   ├── SourceFetcher.kt         # URL 内容抓取
│   ├── WebSearcher.kt           # Web 搜索引擎
│   ├── WebResearchRouter.kt     # 深度研究路由
│   ├── HttpClients.kt           # OkHttp 客户端管理
│   ├── SmartCircuitBreaker.kt  # 智能熔断器
│   ├── RateLimiter.kt           # 限流器
│   ├── MultiLevelCacheManager.kt  # 多级缓存管理
│   ├── HybridRankingEngine.kt  # 混合排序引擎
│   ├── SemanticScorer.kt        # 语义评分器
│   ├── AdaptiveConcurrencyController.kt  # 自适应并发控制
│   ├── TlsFingerprintManager.kt # TLS 指纹管理
│   └── ...                      # 其他网络辅助类
│
├── tools/                       # 工具定义（Tool Call 实现）
│   ├── ToolRegistry.kt          # 动态工具注册表
│   ├── ToolAnnotations.kt       # 工具注解定义
│   ├── RunJsTool.kt             # run_js — JS Skill 沙箱执行
│   ├── RunIntentTool.kt         # run_intent — Android Intent 调用
│   ├── RunCalendarTool.kt       # run_calendar — 日历操作
│   ├── ReadUrlTool.kt           # read_url — URL 内容读取
│   ├── WebSearchTool.kt         # web_search — 网页搜索
│   ├── InsightSproutTool.kt     # insight_sprout — 知识发芽
│   ├── DeepResearchTool.kt      # deep_research — 深度研究
│   ├── SpeechToTextTool.kt      # speech_to_text — 语音转文字
│   ├── MimoTtsTool.kt           # mimo_tts — TTS 语音合成
│   ├── GenerateReportTool.kt    # generate_report — 报告生成
│   ├── ReverseGeocodeTool.kt    # reverse_geocode — 逆地理编码
│   ├── OpenBrowserTool.kt       # open_browser — 打开浏览器
│   └── prompts/                 # 各工具的 Prompt 模板
│
├── stt/                         # 语音识别模块
│   ├── AsrManager.kt            # ASR 管理器（统一入口）
│   ├── SpeechEngine.kt          # 语音引擎接口
│   ├── SherpaOnnxEngine.kt      # Sherpa-ONNX 引擎实现
│   ├── MimoAsrEngine.kt         # MiMO ASR 引擎实现
│   ├── AndroidSpeechRecognizer.kt  # 系统自带语音识别
│   ├── MeetingTranscriber.kt    # 会议转录器
│   ├── AudioProcessor.kt        # 音频预处理
│   ├── ModelManager.kt          # STT 模型管理
│   ├── SttConfig.kt             # STT 配置
│   └── SttResult.kt             # STT 结果数据类
│
├── interview/                   # 面试模式（核心差异化功能）
│   ├── InterviewAgent.kt        # 面试智能体（meta-prompt 决策）
│   ├── InterviewMode.kt         # 面试模式状态定义
│   ├── VoiceConversationEngine.kt  # v3 全双工语音引擎
│   ├── FullDuplexAudioEngine.kt    # 底层音频引擎（Record/Track + AEC）
│   └── HippocampusMemory.kt     # 海马记忆（目标锚定+漂移检测）
│
├── insight/                     # 知识发芽引擎
│   ├── InsightSproutEngine.kt   # 发芽引擎主控
│   ├── SproutSeed.kt            # 阶段1: 种子提取
│   ├── SproutConnection.kt      # 阶段2: 跨领域关联
│   ├── SproutInsight.kt         # 阶段3: AHA 洞察
│   ├── SproutQuote.kt           # 阶段4: 金句回响
│   ├── SproutPhase.kt           # 阶段调度
│   ├── SproutPromptBuilder.kt   # Prompt 构建
│   ├── SproutVoiceStatement.kt  # 语音陈述提取
│   └── KeywordTrigger.kt        # 关键词触发器
│
├── intelligence/                # 记忆与智能系统
│   ├── VectorMemory.kt          # 向量记忆（SQLite + 余弦相似度）
│   ├── MemoryBridge.kt          # 记忆桥接（双写同步）
│   ├── MemoryDir.kt             # 内存记忆目录
│   ├── PersistenceLayer.kt      # 持久化抽象层
│   ├── SqlitePersistence.kt     # SQLite 持久化实现
│   ├── RecommendationEngine.kt  # 推荐引擎
│   ├── FeaturePipeline.kt       # 特征管道（Agent 拦截链）
│   ├── TokenBudgetMonitor.kt    # Token 预算监控
│   ├── PushNotificationHelper.kt # 推送通知助手
│   └── UserBehaviorTracker.kt   # 用户行为追踪
│
├── mcp/                         # MCP / Skill 系统
│   ├── skills/                  # V2 Skills
│   │   ├── SkillLoader.kt       # 动态加载器
│   │   ├── SkillDefinition.kt   # 标准定义（SKILL.md frontmatter）
│   │   ├── SkillCurator.kt      # 技能策展
│   │   ├── CuratorService.kt    # 策展服务
│   │   ├── SkillWebViewExecutor.kt  # JS 沙箱执行器
│   │   ├── SkillSystem.kt       # V1 Legacy 技能系统（@Deprecated）
│   │   └── GalleryBridge.kt     # JS 回调桥接
│   ├── editors/                 # 编辑组
│   │   ├── EditorTeamService.kt # 编辑管道执行
│   │   ├── EditorRole.kt        # 角色定义
│   │   ├── EditorTeamSkillAdapter.kt  # Skill 适配器
│   │   └── SkillModelUnifier.kt # 模型统一
│   └── tools/
│       └── ToolRegistry.kt      # Skill 动态工具注册
│
├── agent/                       # 多智能体协调
│   ├── AgentSwarm.kt            # 智能体集群
│   ├── MultiAgentOrchestrator.kt # 多智能体编排器
│   └── ResearchState.kt         # 研究状态管理
│
├── note/                        # 笔记系统
│   ├── Note.kt                  # 笔记实体
│   ├── NoteDao.kt               # Room DAO
│   ├── NoteDatabase.kt          # Room Database
│   ├── NoteRepository.kt        # 仓库层
│   ├── Folder.kt                # 文件夹实体
│   ├── FolderDao.kt             # 文件夹 DAO
│   ├── FolderDatabase.kt        # 文件夹数据库
│   ├── FolderRepository.kt      # 文件夹仓库
│   ├── KnowledgeGraph.kt        # 知识图谱
│   └── SproutService.kt         # 笔记发芽服务
│
├── storage/                     # 数据存储
│   ├── MemoryStore.kt           # 记忆存储
│   ├── KnowledgeBase.kt         # 知识库
│   ├── KnowledgeBaseModel.kt    # 知识库模型
│   ├── KbDocument.kt            # 知识库文档
│   ├── HippocampusIndex.kt      # 海马索引
│   ├── HippocampusDatabase.kt   # 海马数据库
│   ├── SproutReportStore.kt     # 发芽报告存储
│   ├── ResearchStore.kt         # 研究结果存储
│   ├── SkillsStore.kt           # 技能存储
│   ├── PersonaDetector.kt       # 人格检测
│   ├── NotificationHelper.kt    # 通知助手
│   └── WarmFeedbackService.kt   # 温暖反馈服务
│
├── llm/                         # 本地模型推理
│   ├── LocalLlmEngine.kt        # 本地 LLM 引擎
│   ├── ModelConfig.kt           # 模型配置
│   ├── LocalModelConfig.kt      # 本地模型配置
│   └── ModelDownloadManager.kt  # 模型下载管理
│
├── tts/                         # 语音合成
│   ├── TtsPlayer.kt             # TTS 播放器
│   └── MimoTtsClient.kt         # MiMO TTS 客户端
│
├── calendar/                    # 日历模块
│   ├── CalendarHelper.kt        # 日历操作帮助类
│   ├── CalendarModels.kt        # 日历数据模型
│   └── IcsWriter.kt            # ICS 文件写入
│
├── pdf/                         # PDF 处理
│   ├── PdfProcessor.kt          # PDF 解析处理
│   └── OcrEngine.kt            # OCR 引擎（ML Kit）
│
├── docx/                        # DOCX 处理
│   └── DocxProcessor.kt        # Word 文档处理器
│
├── automation/                  # 自动化工作流
│   ├── AutomationWorker.kt      # 工作流执行器
│   ├── AutomationStore.kt       # 工作流存储
│   └── AutomationModels.kt      # 工作流数据模型
│
├── service/                     # 系统服务
│   ├── KeepAliveService.kt      # 后台保活服务
│   ├── ModelDownloadService.kt  # 模型下载前台服务
│   ├── DailyDigestNotifier.kt   # 每日摘要通知
│   └── AutoSproutWorker.kt      # 自动发芽 Worker
│
├── widget/                      # App Widget
│   └── OpedrgentWidget.kt      # 桌面小组件
│
├── security/                   # 安全模块（参考 AGENTS.md）
├── settings/                   # 设置
│   └── ApiSettings.kt          # API 配置管理
├── env/                        # 环境检测
│   └── EnvironmentProvider.kt  # 环境提供者
├── tool/                       # 工具基础
│   └── Tool.kt                 # 工具接口定义
├── model/                      # 数据模型
│   └── Models.kt               # 通用数据模型
└── utils/                      # 工具类
    ├── DebugLog.kt             # 调试日志
    ├── ContextCompressor.kt    # 上下文压缩
    ├── PromptBuilder.kt        # Prompt 构建
    ├── ToolCallParser.kt       # Tool Call 解析
    ├── ToolCallGuardrail.kt    # Tool Call 安全护栏
    ├── StreamingMessageBuffer.kt  # 流式消息缓冲
    ├── PromptCache.kt          # Prompt 缓存
    ├── PromptSafety.kt         # Prompt 安全检查
    ├── PromptBlocks.kt         # Prompt 块组装
    ├── PromptSection.kt        # Prompt 分区
    ├── ToolPrompts.kt          # 工具 Prompt 集合
    ├── ContextFileLoader.kt    # 上下文文件加载
    ├── ModelInfo.kt            # 模型信息
    ├── StringUtils.kt          # 字符串工具
    ├── PlatformContext.kt      # 平台上下文
    ├── SourceValidator.kt      # 来源验证
    └── BackgroundPermHelper.kt # 后台权限帮助
```

## 构建环境要求

| 项目 | 要求 |
|------|------|
| JDK | Java 21（必须使用 Android Studio 内置 JBR） |
| Gradle | 8.x（通过 Wrapper 管理） |
| SDK | compileSdk 35, minSdk 26, targetSdk 35 |
| NDK | arm64-v8a + armeabi-v7a |
| IDE | Android Studio (推荐最新稳定版) |

## 构建

```bash
# 进入项目目录
cd Opedrgent

# 设置 JAVA_HOME（Windows，使用 Android Studio JBR）
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"

# 编译 Debug APK
./gradlew assembleDebug
```

**注意**：
- 系统 JDK 25+ 与 Gradle 8.x 不兼容，**必须**使用 Android Studio 内置 JBR（Java 21）
- Sherpa-ONNX AAR 需手动下载放入 `app/libs/` 目录（当前使用 stub 编译）
- 如需代理访问网络：`$env:HTTP_PROXY="http://127.0.0.1:7897"`

## 核心架构

### Interview Mode（面试模式）

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

### Memory System（三层记忆）

```
MemoryDir (in-memory index, TTL-based expiry)
  <-> MemoryBridge (dual-write sync + hybrid recall)
    <- -> VectorMemory (SQLite persistent storage, cosine similarity search)
      <- -> SqlitePersistence (raw SQLite operations)
```

### Skill 数据流

```
用户(SkillsScreen) -> 导入(SKILL.md) -> SkillLoader(解析存储)
    -> LLM对话(tool_calls) -> ToolExecutor(run_js/run_intent/calendar/search...)
        -> SkillWebViewExecutor / Android Intent / CalendarHelper
            -> ai_edge_gallery_get_result() 回调
                -> 结果返回 LLM -> 流式输出给用户
```

## Assets 结构

```
assets/skills/
├── calculate-hash/              # JS Skill: 哈希计算器
│   ├── SKILL.md                 #   Frontmatter 元数据
│   └── scripts/index.html       #   Web Crypto API 实现
├── mood-tracker-lite/           # JS Skill: 心情追踪器
│   ├── SKILL.md                 #   Frontmatter 元数据
│   ├── scripts/index.html       #   CRUD + 趋势分析
│   └── assets/dashboard.html    #   交互式仪表盘
├── critical-inquiry.md          # Text Skill: 批判性探究
├── insight-sprout.md            # Text Skill: 知识发芽
├── insight-review.md            # Text Skill: 洞察评审
├── text-refine.md               # Text Skill: 文本精炼
├── mimo-tts.md                  # Text Skill: TTS 语音
└── multi-agent-collaboration.md # Text Skill: 多智能体协作
```

## 注意事项

- Sherpa-ONNX AAR 需手动下载放入 `app/libs/` 目录（当前使用 stub 编译）
- 部分功能需要网络连接（LLM API 调用、搜索等）
- 浏览器接管功能需要用户授权确认
- Prompt 输出严格不含 emoji
- `参考/` 目录为只读参考资料，不属于活跃项目构建范围
