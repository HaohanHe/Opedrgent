# 语音转文字 + 知识发芽引擎 Spec

## Why

当前 Opedrgent 项目具备强大的 LLM 研究能力和工具系统，但缺少两个关键能力：

1. **多模态输入缺失**：无法处理语音和视频内容，用户只能通过文字输入，限制了使用场景（会议记录、课堂笔记、灵感速记等）
2. **知识深度加工不足**：缺乏类似得到大脑的「发芽」能力——从用户输入中自动提取种子、跨领域关联、生成 Aha 洞察，无法将碎片化想法转化为结构化知识体系

## What Changes

### 功能一：语音/视频转文字引擎 (SpeechToText Engine)

- **双引擎架构**：
  - **内置引擎**：基于 Android `SpeechRecognizer` API + 设备本地识别（支持离线，无需网络）
  - **云端引擎**：Google Cloud Speech-to-Text API（高精度，支持 120+ 语言、说话人分离、标点恢复；中国大陆不可用，需自动降级）

- **媒体处理管线**：
  - 视频文件上传 → FFmpeg/MediaExtractor 提取音频轨道 → 音频转文字
  - 音频文件上传 → 直接转文字
  - 实时录音 → 流式识别（内置引擎）

- **输出与集成**：
  - 识别结果可复制到剪贴板
  - 一键将结果发送给 LLM 处理（作为上下文或新对话起点）
  - 支持长时间音频分段处理（>60秒自动切分）

### 功能二：知识发芽引擎 (Insight Sprout Engine)

- **触发机制**：
  - 关键词检测：当用户消息包含「发芽」「生发」「发芽报告」「insight」「sprout」等词汇时自动触发
  - 工具调用触发：LLM 可主动调用 `insight_sprout` 工具对任意文本执行发芽操作

- **渐进式 Prompt 架构**（参考得到大脑的发芽流程）：
  - **Phase 1 - 种子提取 (Seed Extraction)**：从输入文本中识别核心概念、关键观点、情感倾向
  - **Phase 2 - 跨领域关联 (Cross-Domain Connection)**：将种子映射到历史、科学、哲学、心理学、经济学等领域的类比和案例
  - **Phase 3 - Aha 洞察生成 (Aha Insight Generation)**：生成启发性金句、反直觉认知、深度思考框架
  - **Phase 4 - 金句回响 (Quote Resonance)**：关联经典著作/名人名言，建立与人类知识库的桥梁

- **输出格式**（Markdown 结构化）：
  ```
  🌱 种子：[核心观点提取]
  ✨ Aha 瞬间：[启发式洞察]
  🔗 跨领域联结：[历史/科学/哲学等多角度分析]
  💡 金句回响：[经典引用 + 个人思考延展]
  ```

- **Skill 集成**：注册为 MCP Skill，LLM 可自主决定何时调用

## Impact

- Affected specs: 无（全新功能）
- Affected code:
  - **新增模块**：
    - `stt/` - 语音转文字引擎包
      - `SpeechEngine.kt` - 引擎抽象接口
      - `LocalSpeechRecognizer.kt` - Android 内置识别器封装
      - `GoogleCloudSttClient.kt` - Google Cloud STT API 客户端
      - `AudioProcessor.kt` - 音频/视频预处理（格式转换、分段）
      - `SttResult.kt` - 识别结果数据模型
    - `insight/` - 知识发芽引擎包
      - `InsightSproutEngine.kt` - 发芽引擎核心
      - `SproutPhase.kt` - 渐进式阶段定义
      - `SproutPromptBuilder.kt` - 各阶段 Prompt 构建
      - `SproutResult.kt` - 发芽结果数据模型
  - **修改模块**：
    - `tools/ToolRegistry.kt` - 注册新工具 `speech_to_text`, `insight_sprout`
    - `utils/PromptBuilder.kt` - 在系统提示词中加入发芽能力声明和触发规则
    - `ui/MainViewModel.kt` - 添加 STT 和发芽的状态管理与方法
    - `ui/components/` - 新增 UI 组件（音频选择器、发芽结果展示）
    - `AndroidManifest.xml` - 添加录音权限（已有 RECORD_AUDIO）
    - `build.gradle.kts` - 可能需要添加依赖（如 Google Cloud STT 客户端库，可选）

## ADDED Requirements

### Requirement: 双引擎语音转文字系统

The system SHALL provide a dual-engine speech-to-text capability with automatic fallback:

1. **本地优先策略**：默认使用 Android 内置 SpeechRecognizer，无需网络即可工作
2. **云端增强模式**：当设备支持 Google Services 且网络可用时，可选择使用 Google Cloud STT 获得更高精度
3. **自动降级**：云端引擎失败时自动回退到本地引擎，并提示用户
4. **多媒体支持**：
   - 支持常见音频格式：MP3, M4A, WAV, AAC, OGG, FLAC
   - 支持常见视频格式：MP4, MKV, AVI, MOV（自动提取音频轨道）
   - 单次处理最大时长：30 分钟（超出时提示分段或截取前 30 分钟）
5. **实时流式识别**：支持麦克风实时录音转文字（仅本地引擎）

#### Scenario: 用户上传会议录音文件

- **WHEN** 用户选择一个 15 分钟的 M4A 格式会议录音文件
- **THEN** 系统：
  1. 显示处理进度（音频解码 → 分段 → 识别）
  2. 使用本地引擎进行离线识别（或云端引擎如果可用）
  3. 返回完整转录文本，包含说话人分离标记（云端引擎）
  4. 提供「复制」按钮和「发送给 AI 分析」按钮

#### Scenario: 用户上传教学视频

- **WHEN** 用户选择一个 MP4 视频文件
- **THEN** 系统：
  1. 自动提取音频轨道
  2. 执行语音识别流程
  3. 返回视频的完整字幕文本

#### Scenario: 中国大陆用户使用云端引擎

- **WHEN** 用户在中国大陆网络环境下尝试使用 Google Cloud STT
- **THEN** 系统：
  1. 检测到连接超时或服务不可用
  2. 自动降级到本地引擎
  3. 提示用户：「谷歌语音识别不可用，已切换到本地识别模式」

### Requirement: 知识发芽引擎

The system SHALL provide an insight generation capability that transforms user input into structured cross-domain knowledge:

1. **关键词触发**：系统监控用户输入，当检测到发芽相关关键词时自动建议执行发芽操作
2. **工具化调用**：LLM 可通过 `insight_sprout` 工具主动对任意文本执行发芽
3. **四阶段渐进式处理**：
   - Phase 1: 提取核心种子（概念、观点、情感）
   - Phase 2: 跨领域关联（≥3 个不同领域）
   - Phase 3: 生成 Aha 洞察（反直觉发现）
   - Phase 4: 金句回响（经典引用桥接）
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
  2. 执行四阶段处理流程
  3. 返回结构化 Markdown 格式的发芽报告
  4. 用户可复制结果或继续追问深化

#### Scenario: LLM 自主判断需要发芽

- **WHEN** LLM 在对话中发现用户表达了一个值得深化的观点或独特见解
- **THEN** LLM 可主动调用 `insight_sprout` 工具对该观点进行发芽，丰富回答内容

#### Scenario: 发芽结果质量验证

- **GIVEN** 一段关于「免费工具偏好」的用户笔记
- **WHEN** 执行发芽操作
- **THEN** 输出应包含：
  1. 🌱 种子：提取出「零价格效应」「行为经济学陷阱」等核心概念
  2. 🔗 跨领域联结：关联蜜蜂群体智能、Linux 开源运动、TTS 情感局限等 ≥3 个领域
  3. ✨ Aha 瞬间：生成类似「免费是最昂贵的价格」的金句
  4. 💡 金句回响：引用加缪/维特根斯坦/艾克曼等经典论述

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
- `speech_to_text`: 语音/视频转文字
- `insight_sprout`: 知识发芽

## REMOVED Requirements

无
