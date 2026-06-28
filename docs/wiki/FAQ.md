# Opedrgent Q&A / 常见问题解答

> 本文档整理用户在使用、构建和开发 Opedrgent 过程中最常见的问题。如果这里没有你要找的答案，请查看 [Wiki 首页](Home.md) 或 [使用说明书](User-Manual.md)。

---

## 目录 / Table of Contents

1. [安装与运行](#安装与运行)
2. [API 与模型](#api-与模型)
3. [语音与录音](#语音与录音)
4. [笔记与知识库](#笔记与知识库)
5. [知识发芽](#知识发芽)
6. [技能系统](#技能系统)
7. [健康数据](#健康数据)
8. [日历与权限](#日历与权限)
9. [返回键与导航](#返回键与导航)
10. [性能与耗电](#性能与耗电)
11. [构建与开发](#构建与开发)

---

## 安装与运行

### Q1: 最低支持什么 Android 版本？

**A:** Opedrgent 最低支持 Android 8.0（API 26）。由于使用了 Jetpack Compose、Health Connect、MediaProjection 等现代 API，建议在 Android 10（API 29）及以上系统使用以获得完整功能。

### Q2: 为什么安装后无法联网？

**A:** 请检查：
1. `AndroidManifest.xml` 中已声明 `INTERNET` 权限（默认已包含）。
2. 系统是否限制了该应用的后台数据或 Wi-Fi/移动数据权限。
3. 如果你处于需要代理的网络环境，请在系统或应用层配置 HTTP 代理。

### Q3: 首次启动闪退怎么办？

**A:** 常见原因：
- 未授予必要权限（尤其是 `RECORD_AUDIO`、`FOREGROUND_SERVICE`）。
- Sherpa-ONNX AAR 未正确放入 `app/libs/` 目录。
- 使用了系统 JDK 25+ 导致构建产物不兼容（开发端）。

建议：清除应用数据后重新启动，并逐一授予权限。

---

## API 与模型

### Q4: 支持哪些 LLM API？

**A:** Opedrgent 使用 OpenAI 兼容协议，只要 API 提供 `/chat/completions` 标准接口即可接入。已测试适配：
- OpenAI
- Anthropic Messages API（通过兼容层）
- 自托管 vLLM / Ollama / LM Studio
- 各类国内 OpenAI 兼容中转服务

### Q5: 如何配置 API Key？

**A:** 进入「设置」->「API 设置」，填写：
- **Base URL**：如 `https://api.openai.com/v1`
- **API Key**：你的密钥
- **Model**：如 `gpt-4o`、`claude-3-5-sonnet` 等

支持配置多个 Provider Preset，方便快速切换。

### Q6: 本地模型如何加载？

**A:** 需要满足以下条件：
1. 下载 LiteRT-LM（TensorFlow Lite Runtime for Large Models）v0.12.0+ 模型文件。
2. 将模型放入应用可访问的存储目录。
3. 在「设置」->「本地模型」中选择模型路径并加载。
4. 设备需支持 GPU/NPU 加速以获得较好性能（可选）。

### Q7: AI 回复特别慢或超时怎么办？

**A:** 请检查：
- 网络连接是否稳定。
- API 服务商是否限速或排队。
- 模型参数 `max_tokens` 是否设置过大。
- 是否开启了需要多步工具调用的深度研究，这类任务本身耗时较长。

---

## 语音与录音

### Q8: 语音识别支持哪些引擎？

**A:** 多引擎架构，按优先级自动降级：
1. **Sherpa-ONNX**（离线，支持 Paraformer / SenseVoice）
2. **MiMO ASR**
3. **Android SpeechRecognizer**（系统自带，需联网）

可在设置中选择默认引擎或启用自动降级链。

### Q9: 录音时为什么不能同时播放语音？

**A:** 普通录音会占用麦克风，与 TTS 播放存在资源冲突。如需“边说边听”的全双工体验，请使用 **面试模式（Interview Mode）**，该模式使用硬件 AEC 回声消除并独立管理 AudioRecord/AudioTrack。

### Q10: 录音中按返回键为什么不直接退出？

**A:** 为了防止误触导致录音丢失，录音或暂停状态下按返回键会弹出确认对话框。确认停止后才会释放录音资源，之后再次按返回键会按正常导航逻辑返回首页。

### Q11: 会议转录的说话人分离准确吗？

**A:** 说话人分离依赖 Speaker Diarizer 模型，准确率受以下因素影响：
- 录音质量（噪音、回声、音量）
- 说话人音色差异
- 多人同时说话的重叠程度

建议在安静环境下录音，并保持说话人间有明显停顿。

---

## 笔记与知识库

### Q12: 笔记支持哪些格式导入/导出？

**A:** 
- **导入**：文本、Markdown、PDF、DOCX、图片（OCR）
- **导出**：文本、Markdown、DOCX

### Q13: 知识库和笔记有什么区别？

**A:** 
- **笔记**：面向用户创作的短内容，支持编辑、文件夹、知识图谱、发芽。
- **知识库**：面向长期参考文档（如 PDF、论文、手册），主要用于 AI 检索引用。

两者都会被海马记忆系统自动索引。

### Q14: 首页的统计数字是真实的吗？

**A:** 是真实数据。首页三块统计卡片分别来自：
- 今日新增笔记数：`NoteRepository.countToday()`
- 知识库文档总数：`KnowledgeBase.getGlobalStats()`
- AI 会话数：当前会话列表长度

---

## 知识发芽

### Q15: 什么是知识发芽？

**A:** 知识发芽（Insight Sprout）是 Opedrgent 的四阶段 AI 洞察引擎：
1. **Seed** 种子提取
2. **Connection** 跨域关联
3. **Insight** AHA 洞察
4. **Quote** 金句回响

它会从你的笔记或输入内容中挖掘潜在关联，生成新的见解。

### Q16: 发芽结果不满意怎么办？

**A:** 可尝试：
- 确保输入内容足够丰富（太短的内容难以产生高质量洞察）。
- 检查海马记忆中是否有相关背景知识，发芽会利用已有记忆做关联。
- 调整发芽的上下文注入层级（标签/索引/详情）。

---

## 技能系统

### Q17: 什么是 SKILL.md？

**A:** SKILL.md 是 Opedrgent V2 技能的标准元数据文件，包含 name、description、version、category、require-secret 等 frontmatter，以及技能执行逻辑（JS 或 prompt 模板）。

### Q18: 如何导入第三方技能？

**A:** 三种方式：
1. **URL 远程加载**：输入 SKILL.md 或打包的 ZIP 地址。
2. **本地文件导入**：从文件管理器选择技能文件。
3. **手动创建**：在应用内直接编辑 SKILL.md 内容。

### Q19: 技能运行前为什么要确认？

**A:** 每个技能可声明 `require-secret` 权限级别：
- `ALLOW`：直接运行
- `ASK`：每次运行前弹窗确认（保护用户隐私与安全）
- `DENY`：禁止运行

---

## 健康数据

### Q20: 为什么已授权健康权限，设置里还显示“未授予全部健康数据权限”？

**A:** 这是因为 Health Connect 的权限回调只返回本次发生变化的权限。如果部分权限之前已授予，回调结果不包含它们，导致 `granted.containsAll(PERMISSIONS)` 判断失败。

**解决方式**：应用已修复此问题，会在回调后通过 `HealthConnectHelper.hasAllPermissions()` 查询完整的已授权权限列表。

### Q21: 健康数据如何进入 AI 对话？

**A:** 授权后，`MainViewModel.buildSystemPrompt()` 会调用 `HealthConnectHelper.getHealthSummaryForPrompt()`，将今日步数、心率、睡眠、卡路里等摘要自动注入到 system prompt 中，AI 即可基于这些数据回答。

### Q22: 不想共享健康数据怎么办？

**A:** 在「设置」中关闭「运动健康」开关，或在 Health Connect 应用中撤销 Opedrgent 的数据访问权限。

---

## 日历与权限

### Q23: 日历工具能做什么？

**A:** `run_calendar` 工具支持：
- 查询日历事件
- 创建事件
- 更新事件
- 删除事件
- 导出 ICS 文件

所有操作通过系统 ContentProvider 直接执行。

### Q24: 为什么有些权限无法申请？

**A:** 请检查：
- Android 版本是否支持该权限（如 Health Connect 需要 Android 9+）。
- 是否被系统设置中的“不再询问”拒绝。
- 是否在特殊权限设置中允许了“显示悬浮窗”、“修改系统设置”等。

---

## 返回键与导航

### Q25: 为什么按返回键不直接退出应用？

**A:** Opedrgent 的返回键逻辑遵循现代 Android 应用惯例：
- 在子页面中按返回：返回上一级。
- 在非首页 Tab 按返回：回到首页。
- 在首页按返回：第一次提示“再按一次返回键退出”，2 秒内再次按返回才真正退出。

### Q26: 录音中返回的确认弹窗可以关闭吗？

**A:** 目前无法关闭。这是为了防止误触导致录音内容丢失。确认停止录音后，再次按返回即可正常导航。

---

## 性能与耗电

### Q27: 为什么应用在后台耗电较快？

**A:** 以下功能会增加后台耗电：
- KeepAliveService 后台保活
- 自动化工作流
- 模型下载服务
- 录音前台服务

如果不需要，可在设置中关闭相关功能，或在系统电池优化中将 Opedrgent 设为“未优化”以允许必要后台任务。

### Q28: 本地模型推理卡顿怎么办？

**A:** 
- 确保设备支持 GPU/NPU 加速并已启用。
- 选择更小的模型（如 Gemma 2B 而非 7B）。
- 降低 `max_tokens` 和上下文长度。
- 避免在高温环境下长时间运行，防止 CPU 降频。

---

## 构建与开发

### Q29: 为什么 `./gradlew assembleDebug` 报错 `JavaVersion.parse("25")`？

**A:** 系统 JDK 25+ 与 Gradle 8.x 不兼容。必须使用 Android Studio 内置 JBR（Java 21）。

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
./gradlew assembleDebug
```

### Q30: Sherpa-ONNX AAR 在哪里下载？

**A:** 请访问 [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 官方 Release 页面，下载对应版本的 AAR 文件并放入 `Opedrgent/app/libs/` 目录。

### Q31: 可以在模拟器上运行吗？

**A:** 可以，但以下功能受限：
- 语音识别/录音需要麦克风支持。
- Health Connect 需要模拟器安装 Health Connect 测试 APK。
- GPU/NPU 加速在模拟器上不可用或性能较差。

### Q32: 如何参与贡献？

**A:** 欢迎提交 Issue 和 PR。请遵守项目中的 `AGENTS.md` 规范， especially：
- 不使用硬编码颜色/尺寸/字符串。
- 所有 UI 属性使用主题 token。
- 使用手动 DI，不引入 Hilt/Koin。

---

## 还有问题？

- 查看完整功能说明：[使用说明书](User-Manual.md)
- 查看架构设计：[架构设计](Architecture.md)
- 查看构建细节：[构建指南](Building.md)
- 查看项目主页：[README](../../README.md)
