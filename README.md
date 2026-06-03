# Opedrgent

Android 端 AI 智能助手应用，基于 Jetpack Compose + Kotlin 构建。

## 功能特性

- **多模型对话**：支持接入多种 LLM API（OpenAI 兼容接口），支持流式输出
- **本地模型推理**：集成 LiteRT-LM，支持 Gemma 等模型的端侧推理
- **语音识别（STT）**：集成 Sherpa-ONNX 离线语音识别，支持 Paraformer / SenseVoice 等模型
- **OCR 识别**：基于 Google ML Kit 的中英文 OCR，支持 PDF 文字提取
- **浏览器接管**：WebView 自动化引擎，支持网页内容抓取、搜索、截图、多模态交互点击
- **知识发芽**：四阶段 AI 洞察生成引擎（种子提取 → 跨领域关联 → AHA 洞察 → 金句回响）
- **会议转录**：支持说话人分离 + 多人语音转文字
- **深度研究**：多步搜索、网页阅读、自动总结的研究工作流
- **工具系统**：可扩展的 Tool Call 架构，支持搜索、URL 读取、文件处理等
- **会话管理**：完整的会话历史、搜索、收藏功能
- **记忆系统**：持久化记忆存储，支持跨会话上下文
- **TTS 语音合成**：文本转语音输出

## 技术栈

| 组件 | 技术 |
|------|------|
| UI 框架 | Jetpack Compose + Material3 |
| 语言 | Kotlin |
| 最低 SDK | API 26 (Android 8.0) |
| 目标 SDK | API 35 |
| 网络 | OkHttp |
| 本地存储 | DataStore + Room |
| OCR | Google ML Kit |
| 语音识别 | Sherpa-ONNX (stub) |
| 本地 LLM | LiteRT-LM + TFLite |
| 协程 | Kotlin Coroutines + Flow |

## 项目结构

```
app/src/main/java/top/hsyscn/opedrgent/
├── ui/                  # Compose UI 层
│   ├── AppRoot.kt       # 主界面入口
│   ├── MainViewModel.kt # 核心 ViewModel
│   └── components/      # UI 组件
├── network/             # 网络层
│   ├── LlmClient.kt     # LLM API 客户端
│   ├── WebViewAgent.kt  # WebView 自动化引擎
│   └── ToolExecutor.kt  # 工具执行器
├── stt/                 # 语音识别模块
├── insight/             # 知识发芽引擎
├── tools/               # 工具定义
├── storage/             # 数据存储
├── llm/                 # 本地模型推理
├── agent/               # 多智能体协调
├── pdf/                 # PDF 处理 + OCR
└── utils/               # 工具类
```

## 构建

```bash
./gradlew assembleDebug
```

## 注意事项

- Sherpa-ONNX AAR 需手动下载放入 `app/libs/` 目录（当前使用 stub 编译）
- 部分功能需要网络连接（LLM API 调用、搜索等）
- 浏览器接管功能需要用户授权确认
