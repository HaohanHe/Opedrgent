# Opedrgent Wiki

**Opedrgent** 是一款开源 Android AI 知识工作站，基于 Jetpack Compose 和 Kotlin 构建。集对话、语音、搜索、笔记、知识图谱、面试、自动化、健康数据、日历管理于一体。

## 核心特性

| 特性 | 说明 |
|------|------|
| 多模型对话 | 支持 OpenAI 兼容协议，流式输出 + Thinking Mode |
| 本地推理 | LiteRT-LM 端侧推理，GPU/NPU 加速 |
| 工具调用 | 20+ 工具：搜索、日历、健康、JS 执行、Intent 等 |
| 语音能力 | 多引擎 STT + 全双工语音通话 + 会议转录 + TTS |
| 知识发芽 | 四阶段 AI 洞察引擎，三层渐进式上下文注入 |
| 海马记忆 | SQLite 全局索引，自动索引笔记/对话/录音/发芽 |
| 日历 CRUD | 直接操作系统日历事件，支持自然语言时间解析 |
| 健康数据 | Health Connect 集成，步数/心率/睡眠自动注入 |
| 技能系统 | V2 SKILL.md 标准，JS 沙箱执行，动态加载 |
| 深度研究 | 多步搜索 + 网页阅读 + 混合排序研究工作流 |

## 快速导航

- [[功能特性]] — 完整功能列表与详细说明
- [[架构设计]] — 分层架构、数据流、核心模块关系
- [[工具系统]] — 20+ 工具的注册、调用、Prompt 设计
- [[记忆系统]] — 海马体索引、记忆存储、上下文注入
- [[发芽系统]] — 四阶段洞察引擎、三层渐进上下文
- [[技能系统]] — V2 SKILL.md 标准、JS 沙箱、动态加载
- [[构建指南]] — 环境要求、构建步骤、常见问题

## 技术栈

| 层级 | 技术 |
|------|------|
| UI | Jetpack Compose + Material3 |
| 语言 | Kotlin (JVM Toolchain 21) |
| 网络 | OkHttp + Jsoup |
| 存储 | Room (SQLite) + SharedPreferences + DataStore |
| 语音 | Sherpa-ONNX + MiMO ASR + Android SpeechRecognizer |
| TTS | MiMO TTS + StepAudio TTS + Android System TTS |
| OCR | Google ML Kit (中文+英文) |
| LLM | OpenAI Compatible + Anthropic Messages |
| 构建 | Gradle 8.x + Kotlin DSL |

## 项目仓库

- **GitHub**: [HaohanHe/Opedrgent](https://github.com/HaohanHe/Opedrgent)
- **License**: MIT
