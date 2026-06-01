# Checklist

## 语音转文字引擎 (Speech-to-Text Engine) - 基于 Sherpa-ONNX

### 数据模型与接口
- [x] SttResult 数据模型包含所有必要字段（文本、置信度、分段信息、时间戳、处理时长）
- [x] SpeechEngine 接口定义清晰，支持 Sherpa-ONNX 和 Android SpeechRecognizer 两种实现
- [x] SttConfig 配置类支持模型选择策略、语言设置、流式/非流式模式配置

### Sherpa-ONNX 核心引擎
- [x] build.gradle.kts 已添加 Sherpa-ONNX SDK 依赖（com.k2fsa.sherpa:sherpa-onnx:1.10.16）
- [x] NDK abiFilters 配置正确（arm64-v8a, armeabi-v7a）
- [x] SherpaOnnxEngine 正确封装 Sherpa-ONNX Java API（OfflineRecognizer）
- [x] SherpaOnnxEngine 支持文件模式（音频文件转文字）
- [x] SherpaOnnxEngine 支持实时流式模式（麦克风输入，使用 StreamingRecognizer）
- [x] SherpaOnnxEngine 能从 assets 或本地缓存目录加载 ONNX 模型
- [x] SherpaOnnxEngine 实现了正确的资源释放（close() 方法）
- [x] ModelManager 能检测设备 RAM 并推荐合适模型
- [x] ModelManager 支持从 GitHub Releases 下载模型并提供进度回调
- [x] ModelManager 实现了本地缓存管理（版本检查、旧模型清理）
- [x] ModelManager 支持用户手动切换模型

### 音频预处理模块
- [x] AudioProcessor 能使用 MediaExtractor/MediaCodec 提取视频中的音频轨道
- [x] AudioProcessor 能将 MP3/AAC/OGG/FLAC 等格式转换为 PCM 16kHz mono 16bit WAV
- [x] AudioProcessor 对长音频进行自动分段（每段 ≤ 30 秒）
- [x] AudioProcessor 使用静音检测优化分段切分点
- [x] AudioProcessor 能验证音频格式并提取元数据（采样率、通道数、时长）

### 降级方案（Android SpeechRecognizer）
- [x] AndroidSpeechRecognizer 正确封装 android.speech.SpeechRecognizer API
- [x] AndroidSpeechRecognizer 检测 GMS 可用性（GoogleApiAvailability）
- [x] AndroidSpeechRecognizer 仅在有 GMS 设备上启用
- [x] 引擎选择逻辑：优先 Sherpa-ONNX → 降级 Android SpeechRecognizer → 提示用户

### 工具集成
- [x] SpeechToTextTool 正确处理文件 URI 参数（视频/音频）
- [x] SpeechToTextTool 调用完整的 AudioProcessor → SpeechEngine 管线
- [x] SpeechToTextTool 返回正确的 SttResult 作为 ToolResult
- [x] speech_to_text 工具已注册到 ToolRegistry
- [x] 工具 Prompt 清晰描述了使用方式和参数

## 知识发芽引擎 (Insight Sprout Engine)

### 数据模型与阶段定义
- [x] SproutResult 数据模型完整（种子列表、关联点、洞察、金句、Markdown 输出）
- [x] SproutPhase 定义了四个阶段枚举（SEED_EXTRACTION, CROSS_DOMAIN, AHA_INSIGHT, QUOTE_RESONANCE）
- [x] SproutSeed 子数据类结构清晰（概念名称、描述、关键词）
- [x] SproutConnection 子数据类结构清晰（领域名称、类比/案例、分析解读）
- [x] SproutInsight 子数据类结构清晰（洞察内容、反直觉程度评分、启发性标签）
- [x] SproutQuote 子数据类结构清晰（原文引用、出处/作者、延展思考）

### 渐进式 Prompt 构建器
- [x] Phase 1 Prompt 能有效提取种子（2-3 个核心概念、观点、情感）
- [x] Phase 2 Prompt 要求跨领域关联 ≥3 个不同领域
- [x] Phase 2 Prompt 要求每个领域提供类比/案例和分析
- [x] Phase 3 Prompt 能生成 Aha 洞察（反直觉发现、启发性框架）
- [x] Phase 4 Prompt 能生成金句回响（经典引用 + 原创延展）
- [x] SproutPromptBuilder 支持注入用户上下文（记忆、会话、来源）
- [x] SproutPromptBuilder 支持配置输出长度（短/中/长三档）
- [x] SproutPromptBuilder 支持指定偏好领域

### 发芽引擎核心逻辑
- [x] InsightSproutEngine 按顺序执行四阶段 LLM 调用
- [x] InsightSproutEngine 每个阶段传入累积上下文
- [x] InsightSproutEngine 正确聚合各阶段结果为 SproutResult
- [x] InsightSproutEngine 输出符合要求的结构化 Markdown 格式
- [x] InsightSproutEngine 错误处理完善（某阶段失败时跳过并继续）
- [x] InsightSproutEngine 超时控制有效（单阶段 30s，总超时 120s）
- [x] KeywordTrigger 能识别中英文触发关键词（发芽、生发、insight、sprout...）
- [x] KeywordTrigger 返回置信度分数
- [x] KeywordTrigger 支持模糊匹配

### 工具集成
- [x] InsightSproutTool 接受 text 参数和可选配置（length, domains, use_context）
- [x] InsightSproutTool 正确调用 InsightSproutEngine
- [x] InsightSproutTool 返回 Markdown 格式的 ToolResult
- [x] insight_sprout 工具已注册到 ToolRegistry
- [x] 工具 Prompt 描述了发芽的概念和使用方式

## UI 集成与系统适配

### MainViewModel 扩展
- [x] MainViewModel 包含 STT 相关 StateFlow 状态（sttProgress, sttResult, sttError）
- [x] MainViewModel 包含发芽相关 StateFlow 状态（sproutingState, sproutResult）
- [x] startSpeechToText 方法正确启动转录流程
- [x] startRealtimeSpeechRecognition 方法启动实时录音识别
- [x] triggerInsightSprout 方法正确触发发芽流程
- [x] copyToClipboard 方法能将结果复制到系统剪贴板
- [x] sendSttResultToLlm 方法能将 STT 结果发送给 AI 处理
- [x] checkModelDownloaded 方法检查模型是否已下载
- [x] downloadModel 方法返回下载进度 Flow
- [x] getRecommendedModel 方法根据设备 RAM 推荐模型

### UI 组件
- [x] AudioPickerDialog 能过滤并选择音频/视频文件
- [x] AudioPickerDialog 显示选中文件信息（文件名、大小、时长）
- [x] SttProgressDialog 显示当前处理阶段和进度条
- [x] SttProgressDialog 包含取消按钮
- [x] SttResultCard 展示转录结果并提供复制/发送按钮
- [x] SttResultCard 显示统计信息（时长、字数、置信度）
- [x] SproutResultView 正确渲染 Markdown 格式的发芽报告
- [x] SproutResultView 展示四个阶段（可折叠/展开）
- [x] SproutResultView 提供「复制全文」和「继续追问」按钮
- [x] 聊天界面集成了语音转文字入口按钮（🎤）
- [x] 聊天界面集成了发芽入口按钮/触发机制（🌱）

### 系统提示词与构建配置
- [x] 系统提示词包含发芽能力说明和触发规则
- [x] PlatformContext 或能力声明包含 hasStt 标记
- [x] 工具表和工具详情包含新工具的描述
- [x] build.gradle.kts 配置正确（Sherpa-ONNX 依赖 + NDK abiFilters）
- [x] AndroidManifest.xml 权限配置完整（RECORD_AUDIO、文件读取等）

## 集成测试场景

### 语音转文字测试
- [x] 首次使用：提示下载模型 → 下载完成 → 上传音频文件 → 成功转录 → 结果可复制 → 可发送给 AI
- [x] 非首次使用：直接上传音频文件 → 成功转录 → 结果展示
- [x] 视频文件上传：自动提取音频轨道 → 成功转录 → 结果展示
- [x] 实时录音：点击麦克风按钮 → 开始说话 → 实时显示识别文本 → 停止后返回完整结果
- [x] 低端设备（<4GB RAM）：自动选择轻量模型（FunASR-Nano INT8）→ 成功转录
- [x] 无 GMS 设备：降级到 Sherpa-ONNX 本地引擎正常工作（或提示下载模型）
- [x] 有 GMS 设备且 Sherpa-ONNX 不可用：降级到 Android SpeechRecognizer

### 知识发芽测试
- [x] 用户输入「帮我对这段话发芽」+ 文本 → 触发发芽引擎 → 返回结构化报告
- [x] 用户输入「生发一下这个想法」→ 触发发芽引擎 → 返回结构化报告
- [x] 发芽报告包含 🌱 种子、🔗 跨域联结、✨ Aha 瞬间、💡 金句回响 四个部分
- [x] 发芽报告跨域关联 ≥3 个不同领域
- [x] LLM 自主调用 insight_sprout 工具场景正常工作
- [x] 发芽结果质量符合要求（参考 spec 中的「免费工具偏好」示例）

## 文件完整性验证

- [x] STT 模块 7 个文件全部创建（SttResult, SpeechEngine, SttConfig, SherpaOnnxEngine, ModelManager, AudioProcessor, AndroidSpeechRecognizer）
- [x] Insight 模块 8 个文件全部创建（SproutPhase, SproutSeed, SproutConnection, SproutInsight, SproutQuote, SproutPromptBuilder, KeywordTrigger, InsightSproutEngine）
- [x] Tools 模块 4 个文件全部创建（SpeechToTextTool, SpeechToTextToolPrompt, InsightSproutTool, InsightSproutToolPrompt）
- [x] UI 组件 4 个文件全部创建（AudioPickerDialog, SttProgressDialog, SttResultCard, SproutResultView）
- [x] 修改文件 4 个全部更新（build.gradle.kts, PromptBuilder.kt, AndroidManifest.xml, MainViewModel.kt）
- [x] Import 包路径修复完成（InsightSproutTool.kt 的 import 从 engine 改为 insight）

**总计：27 个文件（23 新增 + 4 修改），100% 完成 ✅**
