# Tasks

## 阶段一：语音转文字引擎 (Speech-to-Text Engine) - 基于 Sherpa-ONNX

- [x] Task 1: 创建 STT 数据模型与接口定义
  - [x] 1.1 定义 `SttResult.kt` 数据类（识别文本、置信度、分段信息、时间戳、处理时长）
  - [x] 1.2 定义 `SpeechEngine.kt` 接口（Sherpa-ONNX 和 Android SpeechRecognizer 的统一抽象）
  - [x] 1.3 定义 `SttConfig.kt` 配置类（模型选择策略、语言设置、流式/非流式模式）

- [x] Task 2: 实现 Sherpa-ONNX 核心引擎
  - [x] 2.1 添加 Sherpa-ONNX SDK 依赖到 build.gradle.kts（implementation("com.k2fsa.sherpa:sherpa-onnx:1.10.16")）
  - [x] 2.2 实现 `SherpaOnnxEngine.kt`
    - 封装 Sherpa-ONNX Java API（创建 OfflineRecognizer 实例）
    - 支持文件模式（音频文件转文字）和实时流式模式（麦克风输入，使用 OnlineRecognizer/StreamingRecognizer）
    - 加载 ONNX 模型文件（从 assets 或本地缓存目录）
    - 错误处理与资源释放（close() 方法）
  - [x] 2.3 实现 `ModelManager.kt`（模型下载与管理）
    - 检测设备 RAM，自动推荐合适模型（Paraformer/SenseVoice/FunASR-Nano）
    - 首次使用时从 GitHub Releases 下载模型（提供下载进度回调）
    - 模型本地缓存管理（检查已下载模型版本、清理旧模型）
    - 支持用户在设置中手动切换模型

- [x] Task 3: 实现音频预处理模块
  - [x] 3.1 实现 `AudioProcessor.kt`
    - 使用 Android MediaExtractor/MediaCodec 提取视频中的音频轨道
    - 音频格式转换（MP3/AAC/OGG/FLAC 等 → PCM 16kHz mono 16bit WAV）
    - 长音频自动分段（每段 ≤ 30 秒，避免内存溢出；使用静音检测优化切分点）
    - 音频格式验证与元数据提取（采样率、通道数、时长等）

- [x] Task 4: 实现降级方案（Android SpeechRecognizer）
  - [x] 4.1 实现 `AndroidSpeechRecognizer.kt`
    - 封装 android.speech.SpeechRecognizer API
    - 检测 GMS 可用性（GoogleApiAvailability.isGooglePlayServicesAvailable()）
    - 仅在有 GMS 设备上启用，否则返回不可用状态
    - 处理权限请求和错误回调
  - [x] 4.2 实现引擎选择与降级逻辑
    - 优先使用 Sherpa-ONNX（如果模型已下载）
    - Sherpa-ONNX 不可用时尝试 Android SpeechRecognizer
    - 两者都不可用时提示用户

- [x] Task 5: 创建 STT 工具集成
  - [x] 5.1 在 `tools/` 目录下创建 `SpeechToTextTool.kt`
    - 实现 Tool 接口
    - 处理文件 URI 参数（视频/音频）→ AudioProcessor → SpeechEngine 管线
    - 返回 SttResult 作为 ToolResult
    - 支持异步处理（长文件可能需要较长时间）
  - [x] 5.2 在 `ToolRegistry.kt` 中注册 `speech_to_text` 工具
  - [x] 5.3 编写工具 Prompt（`tools/prompts/SpeechToTextToolPrompt.kt`）

## 阶段二：知识发芽引擎 (Insight Sprout Engine)

- [x] Task 6: 创建发芽引擎数据模型与阶段定义
  - [x] 6.1 定义 `SproutResult.kt` 数据类（种子列表、关联点列表、Aha 洞察列表、金句回响列表、完整 Markdown 输出字符串）
  - [x] 6.2 定义 `SproutPhase.kt` 枚举（SEED_EXTRACTION, CROSS_DOMAIN, AHA_INSIGHT, QUOTE_RESONANCE）
  - [x] 6.3 定义子数据类：
    - `SproutSeed.kt`（概念名称、描述、相关关键词）
    - `SproutConnection.kt`（领域名称、类比/案例、分析解读）
    - `SproutInsight.kt`（洞察内容、反直觉程度评分、启发性标签）
    - `SproutQuote.kt`（原文引用、出处/作者、延展思考）

- [x] Task 7: 实现渐进式 Prompt 构建器
  - [x] 7.1 实现 `SproutPromptBuilder.kt`
    - **Phase 1 Prompt**：种子提取指令
      - 角色：认知科学分析师
      - 任务：从输入文本中识别 2-3 个核心概念、关键观点、情感倾向、潜在主题
      - 输出格式：结构化 JSON 或固定格式文本
    - **Phase 2 Prompt**：跨领域关联指令
      - 角色：跨学科研究专家
      - 任务：将 Phase 1 的种子映射到 ≥3 个不同领域（历史/科学/哲学/心理学/经济学/文学/生物学等），每个领域提供一个类比或案例，并分析其内在联系
      - 要求：避免陈词滥调，寻找反直觉的关联
      - 输出格式：结构化 Markdown 列表
    - **Phase 3 Prompt**：Aha 洞察生成指令
      - 角色：深度思考者 + 行为经济学家
      - 任务：基于前两阶段结果，生成 1-2 条原创的、反直觉的 Aha 洞察
      - 要求：简洁有力，像「金句」一样可传播
      - 参考：「免费是最昂贵的价格」这类表达方式
    - **Phase 4 Prompt**：金句回响指令
      - 角色：博学家 + 文学评论家
      - 任务：关联经典著作/名人名言（如加缪、维特根斯坦、艾克曼等），生成 1 条金句回响（含出处 + 原创延展思考）
      - 要求：桥接个人观点与人类知识库，产生共鸣感
    - **通用能力**：
      - 支持注入用户上下文（记忆块、历史会话摘要、研究来源标题列表）
      - 支持配置输出长度（短/中/长三档）
      - 支持指定偏好领域（如用户对心理学感兴趣，则增加心理学关联权重）

- [x] Task 8: 实现发芽引擎核心逻辑
  - [x] 8.1 实现 `InsightSproutEngine.kt`
    - 接收原始文本 + 可选配置（输出长度、偏好领域、是否注入上下文）
    - 按顺序执行四阶段 LLM 调用（每个阶段独立调用 LLM，传入累积上下文）
    - 聚合各阶段结果为 SproutResult 对象
    - 格式化为结构化 Markdown 字符串
    - 错误处理：某阶段失败时跳过并继续后续阶段，最终报告哪些阶段成功
    - 超时控制：单个阶段 LLM 调用超时 30 秒，总超时 120 秒
  - [x] 8.2 实现 `KeywordTrigger.kt`
    - 定义触发关键词列表（中英文）：发芽、生发、insight、sprout、深化、联想、发芽报告、知识发芽、灵感激发...
    - 实现检测方法 `detect(text: String): Pair<Boolean, Double>` （是否触发, 置信度分数）
    - 支持模糊匹配（如「帮我**生发**一下」「来个**发芽**报告」）

- [x] Task 9: 创建发芽工具集成
  - [x] 9.1 在 `tools/` 目录下创建 `InsightSproutTool.kt`
    - 实现 Tool 接口
    - 接受参数：text（必需）、length（可选：short/medium/long）、domains（可选：偏好领域列表）、use_context（可选：是否注入用户上下文）
    - 调用 InsightSproutEngine
    - 返回 SproutResult 的 Markdown 格式作为 ToolResult
  - [x] 9.2 在 `ToolRegistry.kt` 中注册 `insight_sprout` 工具
  - [x] 9.3 编写工具 Prompt（`tools/prompts/InsightSproutToolPrompt.kt`）

## 阶段三：UI 集成与系统适配

- [x] Task 10: 扩展 MainViewModel 支持 STT 和发芽功能
  - [x] 10.1 添加 STT 相关 StateFlow 状态：
    - `sttProgress: StateFlow<SttProgressState>` （IDLE/DOWNLOADING_MODEL/EXTRACTING_AUDIO/RECOGNIZING/DONE/ERROR）
    - `sttResult: StateFlow<SttResult?>`
    - `sttError: StateFlow<String?>`
  - [x] 10.2 添加发芽相关 StateFlow 状态：
    - `sproutingState: StateFlow<SproutingState>` （IDLE/PHASE1/PHASE2/PHASE3/PHASE4/DONE/ERROR）
    - `sproutResult: StateFlow<String?>` （Markdown 格式的发芽报告）
  - [x] 10.3 实现核心方法：
    - `startSpeechToText(uri: Uri)` ：启动转录流程
    - `startRealtimeSpeechRecognition()` ：启动实时录音识别
    - `triggerInsightSprout(text: String, config: SproutConfig?)` ：触发发芽
    - `copyToClipboard(text: String)` ：复制到剪贴板
    - `sendSttResultToLlm()` ：将 STT 结果发送给 AI 分析
  - [x] 10.4 实现模型管理方法：
    - `checkModelDownloaded(): Boolean`：检查模型是否已下载
    - `downloadModel(modelType: ModelType): Flow<Float>`：下载模型（返回进度 0f-1f）
    - `getRecommendedModel(): ModelType`：根据设备 RAM 推荐模型

- [x] Task 11: 创建 UI 组件
  - [x] 11.1 创建 `AudioPickerDialog.kt`
    - 文件选择器（使用 ActivityResultContracts.GetContent()）
    - 过滤 MIME 类型：audio/*, video/*
    - 显示选中文件信息（文件名、大小、时长预估）
  - [x] 11.2 创建 `SttProgressDialog.kt`
    - 显示当前阶段（下载模型 / 提取音频 / 分段 / 识别中）
    - 进度条动画
    - 取消按钮
  - [x] 11.3 创建 `SttResultCard.kt`
    - 显示完整转录文本（支持滚动）
    - 「复制」按钮（调用 copyToClipboard）
    - 「发送给 AI 分析」按钮（调用 sendSttResultToLlm）
    - 显示统计信息（时长、字数、置信度）
  - [x] 11.4 创建 `SproutResultView.kt`
    - Markdown 渲染器显示发芽报告（复用现有 MarkdownRenderer 组件）
    - 四个阶段的可视化展示（折叠/展开）
    - 「复制全文」按钮
    - 「继续追问」按钮（将发芽结果作为新对话起点）
  - [x] 11.5 在聊天界面集成入口：
    - 输入框旁添加 🎤 按钮（弹出选项：上传音频/视频、实时录音）
    - 输入框旁添加 🌱 按钮（对当前对话上下文执行发芽）
    - 或者在工具栏/菜单中添加这两个入口

- [x] Task 12: 扩展系统提示词与构建配置
  - [x] 12.1 修改 `PromptBuilder.kt`：
    - 在 buildBaseRulesSection() 末尾追加「知识发芽能力」段落
    - 在 buildCapabilityDeclarations() 中添加 STT 能力声明
  - [x] 12.2 修改 `build.gradle.kts`：
    - 添加 Sherpa-ONNX 依赖
    - 配置 NDK abiFilters（arm64-v8a, armeabi-v7a）
  - [x] 12.3 更新 `AndroidManifest.xml`（如需要）：
    - 确认 RECORD_AUDIO 权限存在（已有）
    - 添加文件读取权限（如果需要访问外部存储的音视频文件）
    - 添加 queries 声明（查询 GMS 可选）
  - [x] 12.4 更新工具表和工具详情（包含新工具的描述）

# Task Dependencies

- [Task 2] depends on [Task 1] （数据模型先行）
- [Task 3] depends on [Task 1] （共享数据类型定义）
- [Task 4] depends on [Task 1] （实现相同接口）
- [Task 5] depends on [Task 1, Task 2, Task 3, Task 4] （依赖所有引擎实现就绪）
- [Task 7] depends on [Task 6] （数据模型先行）
- [Task 8] depends on [Task 6, Task 7] （依赖模型和 Prompt 构建）
- [Task 9] depends on [Task 6, Task 7, Task 8] （依赖引擎核心就绪）
- [Task 10] depends on [Task 5, Task 9] （依赖工具注册完成）
- [Task 11] depends on [Task 10] （依赖 ViewModel 状态就绪）
- [Task 12] depends on [Task 5, Task 9] （依赖工具定义稳定）

**可并行执行的独立任务组**：
- Group A: [Task 1], [Task 6] （基础数据模型，无依赖）
- Group B: [Task 2], [Task 3], [Task 7] （实现层，分别属于不同功能模块）
- Group C: [Task 4] （降级方案，可与 Task 2/3 并行）
