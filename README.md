# Opedrgent

[中文](#中文) ｜ [日本語](#日本語) ｜ [English](#english)

---

## 中文

Opedrgent 是一个跑在 Android 手机本地的 AI Agent 应用，用 Kotlin + Jetpack Compose 写成。它的核心立场是「端侧优先」：LLM 可以走云端 OpenAI 兼容接口，也可以用 LiteRT-LM 在手机上本地跑；ASR 默认用 Sherpa-ONNX 离线模型，TTS、Embedding、笔记、记忆全部存在本机 SQLite，不强制依赖任何云服务。除了通用聊天和工具调用，还做了面向业余无线电的 Ham 模式（卫星过境预测、ADIF 日志）和面试/会议场景的全双工语音引擎。

### 核心功能

**对话与模型**
- 多模型聊天：接入 OpenAI 兼容 LLM API，支持流式输出和 Thinking Mode。
- 端侧推理：集成 LiteRT-LM（v0.12.0+），支持 Gemma 等模型在手机上跑，GPU/NPU 加速。
- 工具调用：可扩展 Tool 架构，LLM 动态调用搜索、URL 读取、JS 执行、Intent 派发、日历等 15+ 工具。
- 深度研究：多步搜索 + 网页阅读 + 自动总结 + 混合排序（HybridRankingEngine）。

**语音与音频**
- 语音识别：多引擎架构，Sherpa-ONNX（离线 Paraformer/SenseVoice）+ MiMO ASR + 系统 SpeechRecognizer，自动降级链。
- 全双工通话：Interview Mode 用 AudioRecord/AudioTrack + 硬件 AEC + VAD + BargeIn，五状态状态机。
- 会议转录：说话人分离 + 多人语音转写 + MeetingTranscriber 持久化。
- TTS：MiMO TTS 客户端 + 本地 TtsPlayer。

**知识与记忆**
- Insight Sprout：四阶段洞察引擎（种子提取→跨域关联→AHA 洞察→金句回响），三层渐进式上下文注入。
- 海马记忆：SQLite 全局索引，关键词提取 + LIKE 模糊匹配，自动索引笔记/对话/录音/发芽；面试模式下做目标锚定和漂移检测。
- 笔记系统：CRUD + 文件夹分类 + KnowledgeGraph 图谱可视化。
- 知识库：文档管理与检索。

**浏览器与自动化**
- WebView Agent：网页抓取、搜索、截图、多模态点击。
- 自动化工作流：AutomationWorker + AutomationStore，可配置定时/触发任务。
- 桌面小组件和后台保活服务。

**Ham 模式（业余卫星通联）**
- 卫星过境预测：SGP4/SDP4 完整轨道传播（J2/J3/J4 摄动、BSTAR 大气阻力、GMST 修正、深空 SDP4），粗扫+精扫两阶段。
- 多源 TLE：Celestrak → AMSAT → 旧 URL 三级级联，24 小时本地缓存。
- 内置 15 颗业余星数据库（ISS、SO-50、AO-91/92、FO-29、CAS-3H、RS-44 等），含上下行频率、调制方式、最低仰角。
- 智能通联日志：转写后从星表自动预填，AI 只补 RST/呼号/结果，导出 ADIF 3.1.4 / CSV，兼容 QRZLOG、LoTW、ClubLog。

**Skill 系统（V2）**
- SKILL.md 标准 frontmatter（name/description/version/category/require-secret）。
- JS Skill 沙箱执行（run_js → SkillWebViewExecutor → ai_edge_gallery_get_result 回调）。
- run_intent 派发六种系统 Intent（邮件/短信/日历/URL/分享/电话）。
- 三种导入方式：URL 远程加载、本地文件、手动新建。
- 内置 JS Skills：calculate-hash、mood-tracker-lite；内置 Text Skills：critical-inquiry、insight-sprout、insight-review、text-refine、mimo-tts、multi-agent-collaboration。
- RequireSecret 三级授权：ALLOW / ASK / DENY。

**日历、健康与文档**
- 日历 CRUD：通过 ContentProvider 直接读写系统日历，支持自然语言时间解析。
- Health Connect：读取步数/心率/卡路里/距离/睡眠，今日摘要自动注入 system prompt。
- PDF 处理：PdfProcessor + ML Kit 中英文 OCR。
- DOCX 处理：DocxProcessor。

**网络与安全**
- SmartCircuitBreaker 熔断 + RateLimiter 限流 + MultiLevelCacheManager 多级缓存 + AdaptiveConcurrencyController 自适应并发。
- 搜索侧 HybridRankingEngine + SemanticScorer + DynamicAuthorityScorer + FreshnessCalculator + ResultDeduplicator。
- TlsFingerprintManager + UserAgentPool + ToolCallGuardrail + PromptSafety。

### 技术栈

| 组件 | 选型 |
|------|------|
| UI | Jetpack Compose + Material3 + WindowSizeClass |
| 语言 | Kotlin（JVM Toolchain 21） |
| minSdk / targetSdk | 26（Android 8.0）/ 35 |
| 网络 | OkHttp + Jsoup |
| 本地存储 | DataStore Preferences + SQLite + EncryptedSecurity |
| OCR | Google ML Kit（中文+英文） |
| 语音识别 | Sherpa-ONNX + MiMO ASR + Android SpeechRecognizer |
| 端侧 LLM | LiteRT-LM（v0.12.0+）+ TFLite + GPU/NPU |
| 并发 | Kotlin Coroutines + Flow |
| 序列化 | kotlinx.serialization |
| 后台任务 | WorkManager + ForegroundService |
| 依赖注入 | 手写 DI（不用 Hilt/Koin） |

### 目录结构

```
app/src/main/java/top/hsyscn/opedrgent/
├── MainActivity.kt              # 应用入口（singleTask + SEND Intent）
├── ui/                          # Compose UI 层（会话、面试、首页、知识库、海马、设置等）
├── network/                     # LLM 客户端、WebViewAgent、工具派发、搜索、网络熔断/缓存
├── tools/                       # Tool Call 实现（run_js / run_intent / run_calendar / web_search ...）
│   └── satellite/               # SGP4/SDP4 轨道力学（移植自 Look4Sat）
├── stt/                         # ASR 多引擎管理、会议转写、音频预处理
├── interview/                   # 面试模式：全双工语音引擎、目标锚定、漂移检测
├── insight/                     # Insight Sprout 四阶段引擎
├── intelligence/                # 向量记忆、记忆桥、推荐、token 预算监控
├── mcp/                         # Skill 系统 V2、编辑组、动态工具注册
├── agent/                       # 多智能体编排
├── note/                        # 笔记与知识图谱（Room DAO）
├── storage/                     # 知识库、海马索引、报告/研究/Skill 存储
├── llm/                         # 端侧 LLM 引擎、模型下载管理
├── tts/                         # TTS 播放与 MiMO 客户端
├── calendar/                    # 日历操作与 ICS 写入
├── health/                      # Health Connect 读取
├── pdf/  docx/                  # PDF + OCR、DOCX 处理
├── automation/                  # 定时/触发工作流
├── service/                     # 保活、模型下载、每日摘要通知
├── widget/                      # 桌面小组件
└── utils/                       # Prompt 构建、工具调用解析、上下文压缩等
```

### 构建环境

| 项目 | 要求 |
|------|------|
| JDK | Java 21（必须用 Android Studio 自带 JBR） |
| Gradle | 8.x（Wrapper 管理） |
| SDK | compileSdk 36 / minSdk 26 / targetSdk 35 |
| NDK | arm64-v8a |
| IDE | 推荐最新稳定版 Android Studio |

### 构建

```bash
cd Opedrgent
# Windows 下把 JAVA_HOME 指到 Android Studio 自带 JBR
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
./gradlew assembleDebug
```

注意：系统 JDK 25+ 与 Gradle 8.x 不兼容，必须用 Android Studio 内置 JBR（Java 21）。Sherpa-ONNX 的 AAR 需要手动放到 `app/libs/`，仓库里目前用 stub 编译。

### 文档

- [使用说明书](docs/wiki/User-Manual.md)
- [常见问题 FAQ](docs/wiki/FAQ.md)
- [Wiki 首页](docs/wiki/Home.md)
- [构建指南](docs/wiki/Building.md)
- [隐私说明](PRIVACY.md)、[第三方声明](THIRD_PARTY_NOTICES.md)、[路线图](ROADMAP.md)

### 许可证

MIT，见 [LICENSE](LICENSE)。

---

## 日本語

Opedrgent は Android スマホ上でローカルに動く AI エージェントアプリです。Kotlin と Jetpack Compose で書かれています。「端末優先」を立場としており、LLM は OpenAI 互換のクラウド API でも、LiteRT-LM で端末内ローカル実行でも選べます。ASR はデフォルトで Sherpa-ONNX のオフラインモデルを使い、TTS、Embedding、ノート、メモリはすべて端末内 SQLite に保存され、特定のクラウドサービスに依存しません。汎用のチャットやツール呼び出しに加え、アマチュア無線向けの Ham モード（衛星通過予測、ADIF ログ）や、面接・会議向けの全二重音声エンジンを実装しています。

### 主要な機能

**チャットとモデル**
- マルチモデルチャット：OpenAI 互換 LLM API に接続し、ストリーミング出力と Thinking Mode に対応。
- 端末内推論：LiteRT-LM（v0.12.0+）を統合し、Gemma などのモデルをスマホ上で GPU/NPU 加速実行。
- ツール呼び出し：拡張可能な Tool アーキテクチャで、検索、URL 読み込み、JS 実行、Intent 発行、カレンダーなど 15 以上のツールを LLM が動的に呼び出し。
- ディープリサーチ：多段検索 + Web 読解 + 自動要約 + ハイブリッドランキング（HybridRankingEngine）。

**音声とオーディオ**
- 音声認識：マルチエンジン構成。Sherpa-ONNX（オフライン Paraformer/SenseVoice）+ MiMO ASR + システム SpeechRecognizer の自動フォールバックチェーン。
- 全二重通話：Interview Mode は AudioRecord/AudioTrack + ハードウェア AEC + VAD + BargeIn、5 状態のステートマシン。
- 会議転記：話者分離 + 多者同時認識 + MeetingTranscriber による永続化。
- TTS：MiMO TTS クライアント + ローカル TtsPlayer。

**知識とメモリ**
- Insight Sprout：4 段階インサイトエンジン（種抽出→領域横断関連付け→AHA インサイト→金句エコー）、3 層の段階的コンテキスト注入。
- 海馬メモリ：SQLite グローバルインデックス、キーワード抽出 + LIKE 曖昧一致。ノート・会話・録音・スプラウトを自動索引。面接モードでは目標アンカーとドリフト検出を行う。
- ノートシステム：CRUD + フォルダ分類 + KnowledgeGraph 可視化。
- ナレッジベース：ドキュメント管理と検索。

**ブラウザと自動化**
- WebView Agent：Web スクレイピング、検索、スクリーンショット、マルチモーダルクリック。
- 自動化ワークフロー：AutomationWorker + AutomationStore、定時/トリガータスクを設定可能。
- ホーム画面ウィジェットと常駐サービス。

**Ham モード（アマチュア衛星通信）**
- 衛星通過予測：SGP4/SDP4 完全軌道伝播（J2/J3/J4 摂動、BSTAR 大気抵抗、GMST 補正、深宇宙 SDP4）、粗掃引 + 精査引の 2 段階アルゴリズム。
- マルチソース TLE：Celestrak → AMSAT → 旧 URL の 3 段カスケード、24 時間ローカルキャッシュ。
- 15 基のアマチュア衛星データベース内蔵（ISS、SO-50、AO-91/92、FO-29、CAS-3H、RS-44 など）、アップリンク/ダウンリンク周波数、変調、最低仰角を含む。
- 通信ログ自動記入：転写後に衛星データベースから自動プリフィルし、AI は RST/コールサイン/結果だけを補完。ADIF 3.1.4 / CSV エクスポート対応、QRZLOG、LoTW、ClubLog 互換。

**Skill システム（V2）**
- SKILL.md 標準フロントマター（name/description/version/category/require-secret）。
- JS Skill サンドボックス実行（run_js → SkillWebViewExecutor → ai_edge_gallery_get_result コールバック）。
- run_intent で 6 種類のシステム Intent（メール/SMS/カレンダー/URL/共有/電話）を発行。
- 3 種類のインポート方式：URL リモート読み込み、ローカルファイル、手動作成。
- 内蔵 JS Skills：calculate-hash、mood-tracker-lite。内蔵 Text Skills：critical-inquiry、insight-sprout、insight-review、text-refine、mimo-tts、multi-agent-collaboration。
- RequireSecret 3 段階認可：ALLOW / ASK / DENY。

**カレンダー、ヘルス、ドキュメント**
- カレンダー CRUD：ContentProvider 経由でシステムカレンダーを直接読み書き、自然言語の時間解析に対応。
- Health Connect：歩数/心拍/カロリー/距離/睡眠を読み取り、本日のサマリを system prompt に自動注入。
- PDF 処理：PdfProcessor + ML Kit 中国語/英語 OCR。
- DOCX 処理：DocxProcessor。

**ネットワークとセキュリティ**
- SmartCircuitBreaker（サーキットブレーカー）+ RateLimiter（レート制限）+ MultiLevelCacheManager（多段キャッシュ）+ AdaptiveConcurrencyController（適応並行制御）。
- 検索側は HybridRankingEngine + SemanticScorer + DynamicAuthorityScorer + FreshnessCalculator + ResultDeduplicator。
- TlsFingerprintManager + UserAgentPool + ToolCallGuardrail + PromptSafety。

### 技術スタック

| コンポーネント | 選定 |
|------|------|
| UI | Jetpack Compose + Material3 + WindowSizeClass |
| 言語 | Kotlin（JVM Toolchain 21） |
| minSdk / targetSdk | 26（Android 8.0）/ 35 |
| ネットワーク | OkHttp + Jsoup |
| ローカル保存 | DataStore Preferences + SQLite + EncryptedSecurity |
| OCR | Google ML Kit（中国語+英語） |
| 音声認識 | Sherpa-ONNX + MiMO ASR + Android SpeechRecognizer |
| 端末内 LLM | LiteRT-LM（v0.12.0+）+ TFLite + GPU/NPU |
| 並行処理 | Kotlin Coroutines + Flow |
| シリアライズ | kotlinx.serialization |
| バックグラウンドタスク | WorkManager + ForegroundService |
| DI | 手書き DI（Hilt/Koin 不使用） |

### ディレクトリ構成

```
app/src/main/java/top/hsyscn/opedrgent/
├── MainActivity.kt              # アプリエントリ（singleTask + SEND Intent）
├── ui/                          # Compose UI 層（チャット、面接、ホーム、KB、海馬、設定など）
├── network/                     # LLM クライアント、WebViewAgent、ツール分派、検索、ブレーカー/キャッシュ
├── tools/                       # Tool Call 実装（run_js / run_intent / run_calendar / web_search ...）
│   └── satellite/               # SGP4/SDP4 軌道計算（Look4Sat から移植）
├── stt/                         # ASR マルチエンジン、会議転記、音声前処理
├── interview/                   # 面接モード：全二重音声、目標アンカー、ドリフト検出
├── insight/                     # Insight Sprout 4 段階エンジン
├── intelligence/                # ベクトルメモリ、メモリブリッジ、推薦、トークン予算監視
├── mcp/                         # Skill V2、編集チーム、動的ツール登録
├── agent/                       # マルチエージェントオーケストレーション
├── note/                        # ノートと知識グラフ（Room DAO）
├── storage/                     # KB、海馬インデックス、レポート/研究/Skill ストア
├── llm/                         # 端末内 LLM エンジン、モデルダウンロード管理
├── tts/                         # TTS プレイヤーと MiMO クライアント
├── calendar/                    # カレンダー操作と ICS 書き出し
├── health/                      # Health Connect 読み取り
├── pdf/  docx/                  # PDF + OCR、DOCX 処理
├── automation/                  # 定時/トリガーワークフロー
├── service/                     # 常駐、モデルダウンロード、デイリー要約通知
├── widget/                      # ホーム画面ウィジェット
└── utils/                       # プロンプト構築、ツールコール解析、コンテキスト圧縮など
```

### ビルド環境

| 項目 | 要件 |
|------|------|
| JDK | Java 21（Android Studio 同梱 JBR 必須） |
| Gradle | 8.x（Wrapper 管理） |
| SDK | compileSdk 36 / minSdk 26 / targetSdk 35 |
| NDK | arm64-v8a |
| IDE | 最新安定版 Android Studio 推奨 |

### ビルド

```bash
cd Opedrgent
# Windows では JAVA_HOME を Android Studio 同梱 JBR に向ける
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
./gradlew assembleDebug
```

注意：システム JDK 25 以上は Gradle 8.x と非互換です。Android Studio 同梱の JBR（Java 21）を必ず使ってください。Sherpa-ONNX の AAR は手動で `app/libs/` に配置する必要があり、リポジトリでは現在スタブでコンパイルしています。

### ドキュメント

- [使用マニュアル](docs/wiki/User-Manual.md)
- [FAQ](docs/wiki/FAQ.md)
- [Wiki ホーム](docs/wiki/Home.md)
- [ビルドガイド](docs/wiki/Building.md)
- [プライバシー](PRIVACY.md)、[サードパーティ表示](THIRD_PARTY_NOTICES.md)、[ロードマップ](ROADMAP.md)

### ライセンス

MIT ライセンスです。詳細は [LICENSE](LICENSE) を参照してください。

---

## English

Opedrgent is an on-device AI agent app for Android, written in Kotlin and Jetpack Compose. It is built around a local-first stance: the LLM can be a cloud OpenAI-compatible endpoint or run on the phone via LiteRT-LM. ASR defaults to offline Sherpa-ONNX models, and TTS, embeddings, notes, and memory all live in on-device SQLite, with no hard dependency on any cloud service. Beyond general chat and tool calling, it includes a Ham mode for amateur satellite work (pass prediction, ADIF logging) and a full-duplex voice engine for interview and meeting scenarios.

### Features

**Chat and models**
- Multi-model chat against any OpenAI-compatible LLM API, with streaming output and Thinking Mode.
- On-device inference via LiteRT-LM (v0.12.0+) for Gemma and other models, with GPU/NPU acceleration.
- Extensible tool calling: the LLM dynamically invokes 15+ tools covering search, URL fetch, JS execution, Intent dispatch, calendar, and more.
- Deep research: multi-step search plus web reading plus auto-summarization with hybrid ranking (HybridRankingEngine).

**Voice and audio**
- Multi-engine STT: Sherpa-ONNX offline (Paraformer/SenseVoice) plus MiMO ASR plus the system SpeechRecognizer, with an automatic fallback chain.
- Full-duplex Interview Mode: AudioRecord/AudioTrack with hardware AEC, VAD, and BargeIn, driven by a five-state finite state machine.
- Meeting transcription: speaker diarization, multi-party speech-to-text, and MeetingTranscriber persistence.
- TTS: MiMO TTS client plus a local TtsPlayer.

**Knowledge and memory**
- Insight Sprout: a four-stage insight engine (seed extraction, cross-domain association, AHA insight, golden quote echo) with three-layer progressive context injection.
- Hippocampus memory: a SQLite global index with keyword extraction and LIKE fuzzy matching, auto-indexing notes, conversations, recordings, and sprouts. In interview mode it anchors goals and detects drift.
- Note system: full CRUD, folder classification, and KnowledgeGraph visualization.
- Knowledge base: document management and retrieval.

**Browser and automation**
- WebView Agent: web scraping, search, screenshots, and multimodal clicks.
- Automation workflows: AutomationWorker plus AutomationStore for scheduled or triggered tasks.
- Home-screen widget and a background keep-alive service.

**Ham mode (amateur satellite)**
- Pass prediction with full SGP4/SDP4 propagation (J2/J3/J4 perturbations, BSTAR drag, GMST correction, deep-space SDP4), using a coarse-scan plus fine-scan two-stage algorithm.
- Multi-source TLE fetching: Celestrak, then AMSAT, then a legacy URL, with 24-hour local caching.
- A built-in database of 15 amateur satellites (ISS, SO-50, AO-91/92, FO-29, CAS-3H, RS-44, and others), including uplink/downlink frequencies, modulation, and minimum elevation.
- Smart contact log: after transcription the satellite DB pre-fills name, frequency, and modulation; AI only fills gaps like RST, callsign, and result. Exports ADIF 3.1.4 and CSV, compatible with QRZLOG, LoTW, and ClubLog.

**Skill system (V2)**
- Standard SKILL.md frontmatter (name, description, version, category, require-secret).
- JS Skill sandbox execution (run_js to SkillWebViewExecutor to ai_edge_gallery_get_result callback).
- run_intent dispatches six system Intent types (email, SMS, calendar, URL, share, phone).
- Three import methods: remote URL load, local file, manual creation.
- Built-in JS skills: calculate-hash, mood-tracker-lite. Built-in text skills: critical-inquiry, insight-sprout, insight-review, text-refine, mimo-tts, multi-agent-collaboration.
- RequireSecret three-tier permission control: ALLOW, ASK, DENY.

**Calendar, health, and documents**
- Calendar CRUD directly through the ContentProvider, with natural-language time parsing.
- Health Connect integration: reads steps, heart rate, calories, distance, and sleep, and injects today's summary into the system prompt.
- PDF processing with PdfProcessor and ML Kit Chinese plus English OCR.
- DOCX processing with DocxProcessor.

**Network and security**
- SmartCircuitBreaker, RateLimiter, MultiLevelCacheManager, and AdaptiveConcurrencyController.
- Search side: HybridRankingEngine, SemanticScorer, DynamicAuthorityScorer, FreshnessCalculator, ResultDeduplicator.
- TlsFingerprintManager, UserAgentPool, ToolCallGuardrail, PromptSafety.

### Tech stack

| Component | Choice |
|-----------|--------|
| UI | Jetpack Compose + Material3 + WindowSizeClass |
| Language | Kotlin (JVM Toolchain 21) |
| minSdk / targetSdk | 26 (Android 8.0) / 35 |
| Network | OkHttp + Jsoup |
| Local storage | DataStore Preferences + SQLite + EncryptedSecurity |
| OCR | Google ML Kit (Chinese + English) |
| Speech recognition | Sherpa-ONNX + MiMO ASR + Android SpeechRecognizer |
| On-device LLM | LiteRT-LM (v0.12.0+) + TFLite + GPU/NPU |
| Concurrency | Kotlin Coroutines + Flow |
| Serialization | kotlinx.serialization |
| Background tasks | WorkManager + ForegroundService |
| DI | Manual DI (no Hilt/Koin) |

### Project layout

```
app/src/main/java/top/hsyscn/opedrgent/
├── MainActivity.kt              # Entry (singleTask + SEND Intent)
├── ui/                          # Compose UI (chat, interview, home, KB, hippocampus, settings, ...)
├── network/                     # LLM client, WebViewAgent, tool dispatch, search, breaker/cache
├── tools/                       # Tool Call implementations (run_js / run_intent / run_calendar / web_search ...)
│   └── satellite/               # SGP4/SDP4 orbital mechanics (ported from Look4Sat)
├── stt/                         # Multi-engine ASR, meeting transcription, audio preprocessing
├── interview/                   # Interview mode: full-duplex audio, goal anchor, drift detection
├── insight/                     # Insight Sprout four-stage engine
├── intelligence/                # Vector memory, memory bridge, recommendations, token budget monitor
├── mcp/                         # Skill V2, editor team, dynamic tool registration
├── agent/                       # Multi-agent orchestration
├── note/                        # Notes and knowledge graph (Room DAO)
├── storage/                     # KB, hippocampus index, report/research/skill stores
├── llm/                         # On-device LLM engine, model download manager
├── tts/                         # TTS player and MiMO client
├── calendar/                    # Calendar ops and ICS writer
├── health/                      # Health Connect reads
├── pdf/  docx/                  # PDF + OCR, DOCX processing
├── automation/                  # Scheduled/triggered workflows
├── service/                     # Keep-alive, model download, daily digest
├── widget/                      # Home-screen widget
└── utils/                       # Prompt building, tool call parsing, context compression, ...
```

### Build requirements

| Item | Requirement |
|------|-------------|
| JDK | Java 21 (must use the Android Studio bundled JBR) |
| Gradle | 8.x (managed via Wrapper) |
| SDK | compileSdk 36 / minSdk 26 / targetSdk 35 |
| NDK | arm64-v8a |
| IDE | Latest stable Android Studio recommended |

### Build

```bash
cd Opedrgent
# On Windows, point JAVA_HOME at the Android Studio bundled JBR
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
./gradlew assembleDebug
```

Note: system JDK 25 or newer is incompatible with Gradle 8.x. You must use the Android Studio bundled JBR (Java 21). The Sherpa-ONNX AAR must be placed manually in `app/libs/`; the repo currently compiles against a stub.

### Documentation

- [User manual](docs/wiki/User-Manual.md)
- [FAQ](docs/wiki/FAQ.md)
- [Wiki home](docs/wiki/Home.md)
- [Build guide](docs/wiki/Building.md)
- [Privacy](PRIVACY.md), [Third-party notices](THIRD_PARTY_NOTICES.md), [Roadmap](ROADMAP.md)

### License

MIT, see [LICENSE](LICENSE).
