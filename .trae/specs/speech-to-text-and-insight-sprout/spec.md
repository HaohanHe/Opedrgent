# 语音转文字 + 知识发芽引擎 Spec

## Why

当前 Opedrgent 项目具备强大的 LLM 研究能力和工具系统，但缺少两个关键能力：

1. **多模态输入缺失**：无法处理语音和视频内容，用户只能通过文字输入，限制了使用场景（会议记录、课堂笔记、灵感速记等）
2. **知识深度加工不足**：缺乏类似得到大脑的「发芽」能力——从用户输入中自动提取种子、跨领域关联、生成 Aha 洞察，无法将碎片化想法转化为结构化知识体系

## What Changes

### 功能一：语音/视频转文字引擎 (SpeechToText Engine)

- **核心引擎：Sherpa-ONNX + Paraformer/SenseVoice**（推荐方案 ⭐）：
  - **为什么选择 Sherpa-ONNX**：
    - 开源 Apache 2.0 许可证，商用友好
    - 完全离线运行，无需网络，保护隐私
    - 支持 Android 原生 SDK（Java/Kotlin 接口，JNI 调用 C++ 后端）
    - 月访问量 4.9 亿次，社区活跃度高（10.8k GitHub Stars）
    - 支持多种 SOTA 模型：
      - **Paraformer**（达摩院）：非自回归架构，推理速度提升 3 倍，AISHELL-1 CER 1.95%
      - **SenseVoice-Small**：多语言轻量，INT8 量化后仅 **8MB**，适合低端设备
      - **FunASR-Nano**：数千万小时数据训练，支持 **7 种方言、26 种口音**
      - **Zipformer**：移动设备专用优化模型
    - 2025 年新增四川话等方言专用模型
    - 中文识别精度远超 Whisper（CER 1.95% vs 5.14%）

  - **模型选择策略**（根据设备性能自动选择）：
    - 高端设备（≥6GB RAM）：Paraformer 大模型（~220MB，精度最高）
    - 中端设备（4-6GB RAM）：SenseVoice-Small（~40MB，平衡精度与速度）
    - 低端设备（<4GB RAM）：FunASR-Nano INT8（~20MB，极致轻量）
    - 用户可在设置中手动切换模型

- **辅助引擎：Android SpeechRecognizer**（降级方案）：
  - 仅在具备 GMS 的设备上可用（Pixel、海外版三星等）
  - 中国大陆设备（华为EMUI、小米MIUI、OPPO ColorOS 等）通常无 GMS，不可用
  - 作为有 GMS 设备的备选方案，提供云端增强能力
  - **注意**：Android 13+ 仅 24 个 locale 支持离线，中文离线质量差（准确率下降 40%+）

- **媒体处理管线**：
  - 视频文件上传 → Android MediaExtractor 提取音频轨道 → PCM 16kHz mono WAV → Sherpa-ONNX 识别
  - 音频文件上传 → FFmpeg/libsox 格式转换（如需）→ PCM 16kHz mono WAV → Sherpa-ONNX 识别
  - 实时录音 → AudioRecord 采集 PCM 数据 → Sherpa-ONNX 流式识别（支持 SenseVoice 流式模式）
  - 长音频自动分段（每段 ≤ 30 秒，避免内存溢出），分段结果自动合并

- **输出与集成**：
  - 识别结果可复制到剪贴板
  - 一键将结果发送给 LLM 处理（作为上下文或新对话起点）
  - 支持时间戳对齐（可选，用于字幕生成场景）
  - 标点自动恢复（Paraformer/SenseVoice 内置标点恢复能力）

### 功能二：知识发芽引擎 (Insight Sprout Engine)

- **触发机制**：
  - 关键词检测：当用户消息包含「发芽」「生发」「发芽报告」「insight」「sprout」「深化」「联想」等词汇时自动触发
  - 工具调用触发：LLM 可主动调用 `insight_sprout` 工具对任意文本执行发芽操作

- **渐进式 Prompt 架构**（参考得到大脑的发芽流程）：
  - **Phase 1 - 种子提取 (Seed Extraction)**：从输入文本中识别核心概念、关键观点、情感倾向、潜在主题
  - **Phase 2 - 跨领域关联 (Cross-Domain Connection)**：将种子映射到历史、科学、哲学、心理学、经济学、文学、生物学等领域的类比和案例（要求 ≥3 个不同领域）
  - **Phase 3 - Aha 洞察生成 (Aha Insight Generation)**：生成启发性金句、反直觉认知、深度思考框架、行为经济学解读
  - **Phase 4 - 金句回响 (Quote Resonance)**：关联经典著作/名人名言（加缪、维特根斯坦、艾克曼等），建立与人类知识库的桥梁，生成原创延展思考

- **输出格式**（Markdown 结构化）：
  ```markdown
  🌱 **种子**：[核心观点提取，2-3 个关键概念]
  
  ✨ **Aha 瞬间**：[启发式洞察，1-2 条反直觉发现]
  
  🔗 **跨领域联结**：
  - [领域 1]：[类比/案例 + 分析]
  - [领域 2]：[类比/案例 + 分析]
  - [领域 3]：[类比/案例 + 分析]
  
  💡 **金句回响**：「[经典引用]」——[出处] + [个人思考延展]
  ```

- **Skill 集成**：注册为 MCP Skill，LLM 可自主决定何时调用

## Impact

- Affected specs: 无（全新功能）
- Affected code:
  - **新增模块**：
    - `stt/` - 语音转文字引擎包
      - `SpeechEngine.kt` - 引擎抽象接口
      - `SherpaOnnxEngine.kt` - Sherpa-ONNX 引擎封装（核心实现）
      - `AndroidSpeechRecognizer.kt` - Android 内置识别器封装（降级方案）
      - `AudioProcessor.kt` - 音频/视频预处理（MediaExtractor 提取、格式转换、分段）
      - `SttResult.kt` - 识别结果数据模型（文本、置信度、分段信息、时间戳）
      - `ModelManager.kt` - 模型下载与管理（首次使用时下载 ONNX 模型到本地）
    - `insight/` - 知识发芽引擎包
      - `InsightSproutEngine.kt` - 发芽引擎核心
      - `SproutPhase.kt` - 渐进式阶段定义（枚举/密封类）
      - `SproutPromptBuilder.kt` - 各阶段 Prompt 构建（四阶段独立模板）
      - `SproutResult.kt` - 发芽结果数据模型（种子列表、关联点、洞察、金句、完整 Markdown）
      - `KeywordTrigger.kt` - 关键词触发检测器
  - **修改模块**：
    - `tools/ToolRegistry.kt` - 注册新工具 `speech_to_text`, `insight_sprout`
    - `tools/prompts/` - 新增工具 Prompt 文件
    - `utils/PromptBuilder.kt` - 在系统提示词中加入发芽能力声明和触发规则
    - `ui/MainViewModel.kt` - 添加 STT 和发芽的状态管理与方法
    - `ui/components/` - 新增 UI 组件（音频选择器、转录进度、发芽结果展示）
    - `build.gradle.kts` - 添加 Sherpa-ONNX Android SDK 依赖
    - `AndroidManifest.xml` - 添加必要权限（RECORD_AUDIO 已有，可能需添加文件读取权限）

## ADDED Requirements

### Requirement: 基于 Sherpa-ONNX 的离线语音转文字系统

The system SHALL provide a high-quality offline speech-to-text capability powered by Sherpa-ONNX:

1. **核心引擎**：默认使用 Sherpa-ONNX + Paraformer/SenseVoice 模型，完全离线运行
2. **智能模型选择**：根据设备 RAM 自动选择合适模型（高端→Paraformer，中端→SenseVoice，低端→FunASR-Nano INT8）
3. **模型按需下载**：首次使用时从 CDN/GitHub 下载 ONNX 模型（~20-220MB），缓存到本地存储；支持用户手动切换模型
4. **多媒体支持**：
   - 支持常见音频格式：MP3, M4A, WAV, AAC, OGG, FLAC, AMR
   - 支持常见视频格式：MP4, MKV, AVI, MOV, WebM（自动提取音频轨道）
   - 单次处理最大时长：30 分钟（超出时提示截取前 30 分钟或分段处理）
5. **长音频处理**：自动分段（每段 ≤ 30 秒），分段并行或顺序识别，结果自动合并
6. **实时流式识别**：支持麦克风实时录音转文字（使用 SenseVoice 流式模式，延迟 <500ms）
7. **标点恢复**：自动添加标点符号（模型内置能力）
8. **降级机制**：如果 Sherpa-ONNX 初始化失败（极罕见），尝试使用 Android SpeechRecognizer（仅限有 GMS 设备）；两者都不可用时提示用户安装模型或检查设备兼容性

#### Scenario: 用户上传会议录音文件（首次使用）

- **WHEN** 用户首次选择一个 15 分钟的 M4A 格式会议录音文件
- **THEN** 系统：
  1. 检测到 Sherpa-ONNX 模型未下载，提示用户下载（显示模型大小：SenseVoice-Small ~40MB）
  2. 用户确认后，后台下载模型并显示进度
  3. 下载完成后，显示处理进度（音频解码 → 分段 → 识别）
  4. 使用 SenseVoice-Small 模型进行离线识别
  5. 返回完整转录文本（含标点）
  6. 提供「复制」按钮和「发送给 AI 分析」按钮

#### Scenario: 用户上传教学视频

- **WHEN** 用户选择一个 MP4 视频文件
- **THEN** 系统：
  1. 使用 MediaExtractor 自动提取音频轨道
  2. 转换为 PCM 16kHz mono WAV 格式
  3. 执行 Sherpa-ONNX 语音识别流程
  4. 返回视频的完整字幕文本（含时间戳）

#### Scenario: 实时录音转文字

- **WHEN** 用户点击麦克风按钮开始说话
- **THEN** 系统：
  1. 使用 AudioRecord 实时采集音频
  2. 每 500ms 或检测到静音段时，将音频数据送入 Sherpa-ONNX 流式识别
  3. 实时显示识别文本（延迟 <500ms）
  4. 用户停止录音后，返回完整转录结果

#### Scenario: 低端设备自动选择轻量模型

- **GIVEN** 设备 RAM 为 3GB（低端 Android 手机）
- **WHEN** 用户启动语音转文字功能
- **THEN** 系统：
  1. 自动选择 FunASR-Nano INT8 模型（~20MB）
  2. 不提示用户下载大模型
  3. 识别精度略低但可接受（CER ~4-5%）

### Requirement: 知识发芽引擎

The system SHALL provide an insight generation capability that transforms user input into structured cross-domain knowledge:

1. **关键词触发**：系统监控用户输入，当检测到发芽相关关键词时自动建议执行发芽操作
2. **工具化调用**：LLM 可通过 `insight_sprout` 工具主动对任意文本执行发芽
3. **四阶段渐进式处理**：
   - Phase 1: 提取核心种子（概念、观点、情感，2-3 个）
   - Phase 2: 跨领域关联（≥3 个不同领域，每个领域含类比/案例/分析）
   - Phase 3: 生成 Aha 洞察（反直觉发现、启发性框架，1-2 条）
   - Phase 4: 金句回响（经典引用桥接 + 原创延展，1 条）
4. **输出质量要求**：
   - 每个发芽结果至少包含 3 个跨领域关联点
   - 至少 1 条原创 Aha 洞察
   - 至少 1 条金句回响（含出处）
   - 总长度 500-2000 字（中文）
5. **上下文感知**：发芽时可引用用户的记忆、历史会话、研究来源

#### Scenario: 用户请求对笔记执行发芽

- **WHEN** 用户输入：「帮我对这段话发芽」或「生发一下这个想法」并提供一段文本
- **THEN** 系统：
  1. 触发 InsightSproutEngine
  2. 执行四阶段处理流程（依次调用 LLM 四次，每次传入上一阶段结果作为上下文）
  3. 返回结构化 Markdown 格式的发芽报告
  4. 用户可复制结果或继续追问深化

#### Scenario: LLM 自主判断需要发芽

- **WHEN** LLM 在对话中发现用户表达了一个值得深化的观点或独特见解
- **THEN** LLM 可主动调用 `insight_sprout` 工具对该观点进行发芽，丰富回答内容

#### Scenario: 发芽结果质量验证

- **GIVEN** 一段关于「免费工具偏好」的用户笔记
- **WHEN** 执行发芽操作
- **THEN** 输出应包含：
  1. 🌱 种子：提取出「零价格效应」「行为经济学陷阱」「非理性偏好」等核心概念
  2. 🔗 跨领域联结：关联蜜蜂群体智能（分布式决策）、Linux 开源运动（21岁学生的业余项目）、TTS 情感局限（文字的情感黑洞）、多智能体效率悖论（协作复杂度）等 ≥3 个领域
  3. ✨ Aha 瞬间：生成类似「免费是最昂贵的价格——它让你付出的不是金钱，而是选择的标准和时间的尊严」的金句
  4. 💡 金句回响：引用加缪《堕落》（语言是误解的源头）、维特根斯坦《逻辑哲学论》（凡是不可说的必须保持沉默）、保罗·艾克曼（情感是连续的多维谱系）等经典论述

## MODIFIED Requirements

### Requirement: 系统提示词扩展

修改 `PromptBuilder.buildBaseRulesSection()`，新增以下内容：

```
## 知识发芽能力

当用户提及「发芽」「生发」「insight」「sprout」「深化」「联想」等词汇时，
或当你认为用户的观点值得深度挖掘时，使用 insight_sprout 工具执行知识发芽。

发芽是将碎片化想法转化为结构化知识的四阶段过程：
1. 种子提取 → 2. 跨领域关联 → 3. Aha 洞察 → 4. 金句回响

你也可以在回答中自然地融入跨领域思考和金句，提升回答的深度。
```

### Requirement: 工具注册扩展

在 `ToolRegistry` 初始化时注册两个新工具：
- `speech_to_text`: 语音/视频转文字（基于 Sherpa-ONNX 离线引擎）
- `insight_sprout`: 知识发芽（四阶段渐进式 LLM 处理）

### Requirement: 构建依赖扩展

修改 `build.gradle.kts`，添加 Sherpa-ONNX Android SDK 依赖：

```kotlin
// Sherpa-ONNX: 高性能离线语音识别框架 (Apache 2.0)
implementation("com.k2fsa.sherpa:sherpa-onnx:1.10.16")
```

同时需要在 `android` 配置块中添加：

```kotlin
ndk {
    abiFilters += listOf("arm64-v8a", "armeabi-v7a")
}
```

## REMOVED Requirements

无
