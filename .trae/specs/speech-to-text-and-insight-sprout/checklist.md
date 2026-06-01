# Checklist

## 语音转文字引擎 (Speech-to-Text Engine)

- [ ] SttResult 数据模型包含所有必要字段（文本、置信度、说话人、时间戳、分段）
- [ ] SpeechEngine 接口定义清晰，支持本地和云端两种实现
- [ ] LocalSpeechRecognizer 正确封装 Android SpeechRecognizer API
- [ ] LocalSpeechRecognizer 支持文件模式和实时流式模式
- [ ] AudioProcessor 能正确提取视频中的音频轨道
- [ ] AudioProcessor 能将各种音频格式转换为 PCM 16kHz mono WAV
- [ ] AudioProcessor 对长音频进行自动分段（每段 ≤ 55 秒）
- [ ] GoogleCloudSttClient 封装了 Google Cloud Speech-to-Text REST API
- [ ] GoogleCloudSttClient 支持 long-running recognize 操作
- [ ] 网络不可用时自动降级到本地引擎并提示用户
- [ ] SpeechToTextTool 正确处理文件 URI 参数
- [ ] SpeechToTextTool 调用完整的 AudioProcessor → SpeechEngine 管线
- [ ] speech_to_text 工具已注册到 ToolRegistry
- [ ] 工具 Prompt 清晰描述了使用方式和参数

## 知识发芽引擎 (Insight Sprout Engine)

- [ ] SproutResult 数据模型完整（种子、关联、洞察、金句、Markdown 输出）
- [ ] SproutPhase 定义了四个阶段枚举/密封类
- [ ] 子数据类（SproutSeed, SproutConnection, SproutInsight, SproutQuote）结构清晰
- [ ] SproutPromptBuilder 的 Phase 1 Prompt 能有效提取种子
- [ ] SproutPromptBuilder 的 Phase 2 Prompt 要求跨领域关联 ≥3 个领域
- [ ] SproutPromptBuilder 的 Phase 3 Prompt 能生成 Aha 洞察
- [ ] SproutPromptBuilder 的 Phase 4 Prompt 能生成金句回响
- [ ] SproutPromptBuilder 支持注入用户上下文（记忆、会话、来源）
- [ ] InsightSproutEngine 按顺序执行四阶段 LLM 调用
- [ ] InsightSproutEngine 正确聚合各阶段结果为 SproutResult
- [ ] InsightSproutEngine 输出符合要求的结构化 Markdown 格式
- [ ] 关键词触发检测器能识别「发芽」「生发」等中英文关键词
- [ ] InsightSproutTool 接受文本参数和可选配置
- [ ] InsightSproutTool 正确调用 InsightSproutEngine
- [ ] insight_sprout 工具已注册到 ToolRegistry
- [ ] 工具 Prompt 描述了发芽的概念和使用方式

## UI 集成与系统适配

- [ ] MainViewModel 包含 STT 相关状态和处理方法
- [ ] MainViewModel 包含发芽相关状态和处理方法
- [ ] startSpeechToText 方法正确启动转录流程
- [ ] triggerInsightSprout 方法正确触发发芽流程
- [ ] copyToClipboard 方法能将结果复制到系统剪贴板
- [ ] sendToLlm 方法能将 STT 结果发送给 AI 处理
- [ ] AudioPickerDialog 能过滤并选择视频/音频文件
- [ ] SttProgressDialog 显示正确的处理进度
- [ ] SttResultCard 展示转录结果并提供复制/发送按钮
- [ ] SproutResultView 正确渲染 Markdown 格式的发芽报告
- [ ] 聊天界面集成了语音转文字入口按钮
- [ ] 聊天界面集成了发芽入口按钮/触发机制
- [ ] 系统提示词包含发芽能力说明和触发规则
- [ ] PlatformContext 或能力声明包含 hasStt 标记
- [ ] 工具表和工具详情包含新工具的描述

## 集成测试场景

- [ ] 上传音频文件 → 成功转录 → 结果可复制 → 可发送给 AI
- [ ] 上传视频文件 → 自动提取音频 → 成功转录 → 结果展示
- [ ] 用户输入「帮我对这段话发芽」→ 触发发芽引擎 → 返回结构化报告
- [ ] 发芽报告包含 🌱 种子、🔗 跨域联结、✨ Aha 瞬间、💡 金句回响 四个部分
- [ ] 中国大陆网络环境下 Google Cloud STT 降级到本地引擎正常工作
- [ ] 长音频（>5 分钟）分段处理正常完成
