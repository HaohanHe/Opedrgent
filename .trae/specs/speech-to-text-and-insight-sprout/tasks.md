# Tasks

## 阶段一：语音转文字引擎 (Speech-to-Text Engine)

- [ ] Task 1: 创建 STT 数据模型与接口定义
  - [ ] 1.1 定义 `SttResult.kt` 数据类（识别文本、置信度、说话人标记、时间戳、分段信息）
  - [ ] 1.2 定义 `SpeechEngine.kt` 接口（本地/云端引擎的统一抽象）
  - [ ] 1.3 定义 `SttConfig.kt` 配置类（引擎选择、语言、是否启用云端）

- [ ] Task 2: 实现本地语音识别器（Android SpeechRecognizer 封装）
  - [ ] 2.1 实现 `LocalSpeechRecognizer.kt`
    - 封装 `android.speech.SpeechRecognizer` API
    - 支持文件模式（音频文件转文字）和实时流式模式（麦克风输入）
    - 处理权限请求（RECORD_AUDIO 已在 Manifest 中声明）
    - 错误处理与回调管理
  - [ ] 2.2 实现音频预处理 `AudioProcessor.kt`
    - 使用 Android MediaExtractor/MediaCodec 提取视频中的音频轨道
    - 音频格式转换（统一转为 PCM 16kHz mono WAV）
    - 长音频自动分段（每段 ≤ 55 秒，避免 SpeechRecognizer 超时）
    - 支持的输入格式检测与验证

- [ ] Task 3: 实现谷歌云端 STT 客户端（可选增强）
  - [ ] 3.1 实现 `GoogleCloudSttClient.kt`
    - 封装 Google Cloud Speech-to-Text REST API
    - 支持 long-running recognize 操作（适用于长音频）
    - 配置 API Key / Service Account 认证
    - 说话人分离（speaker_diarization）支持
    - 自动标点恢复
  - [ ] 3.2 实现网络可用性检测与自动降级逻辑
    - 检测 Google Services 可用性
    - 超时/错误时自动降级到本地引擎
    - 用户提示信息

- [ ] Task 4: 创建 STT 工具集成
  - [ ] 4.1 在 `tools/` 目录下创建 `SpeechToTextTool.kt`
    - 实现 Tool 接口
    - 处理文件 URI 参数（视频/音频）
    - 调用 AudioProcessor → SpeechEngine 管线
    - 返回 SttResult 作为 ToolResult
  - [ ] 4.2 在 `ToolRegistry.kt` 中注册 `speech_to_text` 工具
  - [ ] 4.3 编写工具 Prompt（`tools/prompts/SpeechToTextToolPrompt.kt`）

## 阶段二：知识发芽引擎 (Insight Sprout Engine)

- [ ] Task 5: 创建发芽引擎数据模型与阶段定义
  - [ ] 5.1 定义 `SproutResult.kt` 数据类（种子列表、关联点、Aha 洞察、金句回响、完整 Markdown 输出）
  - [ ] 5.2 定义 `SproutPhase.kt` 枚举/密封类（SEED_EXTRACTION, CROSS_DOMAIN, AHA_INSIGHT, QUOTE_RESONANCE）
  - [ ] 5.3 定义 `SproutSeed.kt`、`SproutConnection.kt`、`SproutInsight.kt`、`SproutQuote.kt` 子数据类

- [ ] Task 6: 实现渐进式 Prompt 构建器
  - [ ] 6.1 实现 `SproutPromptBuilder.kt`
    - Phase 1 Prompt：种子提取指令（提取核心概念、关键观点、情感倾向、潜在主题）
    - Phase 2 Prompt：跨领域关联指令（要求从历史、科学、哲学、心理学、经济学、文学等 ≥3 个领域建立类比和联结）
    - Phase 3 Prompt：Aha 洞察生成指令（生成反直觉发现、启发性框架、深度思考角度）
    - Phase 4 Prompt：金句回响指令（关联经典著作/名人名言，生成原创延展思考）
    - 支持注入用户上下文（记忆、历史会话、来源材料）

- [ ] Task 7: 实现发芽引擎核心逻辑
  - [ ] 7.1 实现 `InsightSproutEngine.kt`
    - 接收原始文本 + 可选上下文
    - 按顺序执行四阶段 LLM 调用
    - 聚合各阶段结果为 SproutResult
    - 格式化为结构化 Markdown 输出
    - 错误处理与重试机制
  - [ ] 7.2 实现关键词触发检测器
    - 检测「发芽」「生发」「insight」「sprout」「深化」「联想」等中英文关键词
    - 返回触发置信度分数

- [ ] Task 8: 创建发芽工具集成
  - [ ] 8.1 在 `tools/` 目录下创建 `InsightSproutTool.kt`
    - 实现 Tool 接口
    - 接受文本参数 + 可选配置（阶段选择、领域偏好、输出长度）
    - 调用 InsightSproutEngine
    - 返回 SproutResult 的 Markdown 格式作为 ToolResult
  - [ ] 8.2 在 `ToolRegistry.kt` 中注册 `insight_sprout` 工具
  - [ ] 8.3 编写工具 Prompt（`tools/prompts/InsightSproutToolPrompt.kt`）

## 阶段三：UI 集成与系统适配

- [ ] Task 9: 扩展 MainViewModel 支持 STT 和发芽功能
  - [ ] 9.1 添加 STT 相关状态（处理进度、结果、错误）
  - [ ] 9.2 添加发芽相关状态（触发状态、发芽结果展示）
  - [ ] 9.3 实现 `startSpeechToText(uri: Uri)` 方法
  - [ ] 9.4 实现 `triggerInsightSprout(text: String)` 方法
  - [ ] 9.5 实现 `copyToClipboard(text: String)` 方法
  - [ ] 9.6 实现 `sendToLlm(text: String)` 方法（将 STT 结果发送给 AI）

- [ ] Task 10: 创建 UI 组件
  - [ ] 10.1 创建 `AudioPickerDialog.kt`（文件选择器，过滤视频/音频格式）
  - [ ] 10.2 创建 `SttProgressDialog.kt`（转录进度显示）
  - [ ] 10.3 创建 `SttResultCard.kt`（结果显示组件，含复制/发送按钮）
  - [ ] 10.4 创建 `SproutResultView.kt`（发芽报告展示组件，Markdown 渲染）
  - [ ] 10.5 在聊天界面集成入口按钮（🎤 语音/视频转文字，🌱 发芽）

- [ ] Task 11: 扩展系统提示词与能力声明
  - [ ] 11.1 修改 `PromptBuilder.kt`：在 buildBaseRulesSection() 中添加发芽能力说明
  - [ ] 11.2 修改 `PlatformContext.kt` 或相关能力声明：添加 hasStt 能力标记
  - [ ] 11.3 更新工具表和工具详情（包含新工具的描述）

# Task Dependencies

- [Task 2] depends on [Task 1] （数据模型先行）
- [Task 3] depends on [Task 1] （共享接口定义）
- [Task 4] depends on [Task 1, Task 2, Task 3] （依赖引擎实现）
- [Task 6] depends on [Task 5] （数据模型先行）
- [Task 7] depends on [Task 5, Task 6] （依赖模型和 Prompt）
- [Task 8] depends on [Task 5, Task 6, Task 7] （依赖引擎实现）
- [Task 9] depends on [Task 4, Task 8] （依赖工具就绪）
- [Task 10] depends on [Task 9] （依赖 ViewModel）
- [Task 11] depends on [Task 4, Task 8] （依赖工具注册完成）

**可并行执行的独立任务组**：
- Group A: [Task 1], [Task 5] （基础数据模型，无依赖）
- Group B: [Task 2], [Task 6] （实现层，分别属于不同功能）
- Group C: [Task 3] （可选云端引擎，可与 Task 2 并行）
