# Release Draft — Opedrgent

This file is a working draft. It does not create a tag or a GitHub Release; it only records the proposed metadata and notes to paste into the GitHub release page after review.

## Repository metadata

### Suggested repository description (one line)

An on-device, local-first AI agent app for Android written in Kotlin and Jetpack Compose, with optional OpenAI-compatible cloud LLM endpoints.

日本語 / 中文参考文案略 — keep the GitHub description in English for discoverability.

### Suggested repository topics

```
android, kotlin, jetpack-compose, android-application, ai-agent, on-device-ai,
local-first, llm, speech-recognition, sherpa-onnx, litert, gemma, notes-app,
knowledge-graph, coroutines, ambient-computing
```

## Proposed release

- Suggested git tag: `v1.2.1`
- Target ref: the current default branch head (do not create here; create on GitHub after review)
- versionCode / versionName: `4` / `1.2.1` (per `app/build.gradle.kts` and `CHANGELOG.md`)
- This is the first public source release; the notes below summarize the state of the tree at 1.2.1, with the patch-level changes since 1.2.0 called out separately.

### Suggested release title

Opedrgent v1.2.1

---

## Release notes (English)

Opedrgent v1.2.1 is the first public source release of an on-device, local-first AI agent app for Android, written in Kotlin and Jetpack Compose. The LLM can be an OpenAI-compatible cloud endpoint or run on the device via LiteRT-LM; speech recognition defaults to offline Sherpa-ONNX models, and notes, memory, and embeddings are stored in on-device SQLite with no hard dependency on any cloud service.

Included in this release:

- Multi-model chat against OpenAI-compatible endpoints, with streaming output and Thinking Mode, plus on-device inference via LiteRT-LM for supported models.
- An extensible tool-calling layer (search, URL fetch, JS execution, Intent dispatch, calendar, and more) and a multi-step deep-research workflow with hybrid ranking.
- Multi-engine speech recognition (Sherpa-ONNX offline, MiMO ASR, system SpeechRecognizer) with an automatic fallback chain, a full-duplex Interview Mode with hardware AEC / VAD / barge-in, meeting transcription, and TTS playback.
- A note system with folders and a knowledge graph, an Insight Sprout four-stage insight engine, and a SQLite-backed Hippocampus memory index.
- A Ham mode for amateur satellite work: SGP4/SDP4 pass prediction, multi-source TLE fetching, a built-in amateur satellite database, and ADIF 3.1.4 / CSV log export.
- A V2 Skill system (SKILL.md frontmatter, JS sandbox execution, dynamic tool registration), calendar and Health Connect integration, and PDF / DOCX processing with ML Kit OCR.

Changes in 1.2.1 since 1.2.0 (see `CHANGELOG.md`):

- Location cache refresh: `getCurrentLocation` prefers a fresh cached fix within five minutes, uses `LocationManager.getCurrentLocation` on API 30+, and falls back gracefully on older versions, fixing stale location after switching places.
- Note action sheet scroll: the note action bottom sheet is now vertically scrollable so the delete action stays reachable on small screens.
- Release build fix: corrected `backup_rules.xml` / `data_extraction_rules.xml` lint fatal errors so `./gradlew assembleRelease` passes.

Build and install:

- Requires JDK 21 (the Android Studio bundled JBR), compileSdk 36 / minSdk 26 / targetSdk 35, and an arm64-v8a device. Build with `./gradlew assembleDebug`; see the README quick start and `docs/wiki/Building.md`.
- The first build needs access to Maven Central and JitPack (Sherpa-ONNX is resolved from JitPack automatically).

Notes:

- Source code is licensed under the GNU General Public License v3.0; third-party dependencies and runtime-downloaded model weights (e.g. Gemma, Sherpa-ONNX speech models) are governed by their own licenses and are not covered by the GPL-3.0 grant. See `THIRD_PARTY_NOTICES.md`.
- Prebuilt APK artifacts are not attached to this release yet.

## Release notes (中文)

Opedrgent v1.2.1 是首次公开发布的源码版本。它是一款跑在 Android 手机上、端侧优先的 AI Agent 应用，使用 Kotlin 与 Jetpack Compose 编写。LLM 可接入 OpenAI 兼容的云端接口，也可通过 LiteRT-LM 在本机运行；语音识别默认使用 Sherpa-ONNX 离线模型，笔记、记忆与向量检索均保存在本机 SQLite，不强制依赖任何云服务。

本版本包含：

- 多模型对话：接入 OpenAI 兼容接口，支持流式输出与 Thinking Mode，并可通过 LiteRT-LM 在端侧运行受支持的模型。
- 可扩展工具调用层（搜索、URL 读取、JS 执行、Intent 派发、日历等），以及带混合排序的多步深度研究流程。
- 多引擎语音识别（Sherpa-ONNX 离线、MiMO ASR、系统 SpeechRecognizer，带自动降级链）、带硬件 AEC / VAD / 打断的全双工面试模式、会议转录与 TTS 播放。
- 带文件夹与知识图谱的笔记系统、四阶段 Insight Sprout 洞察引擎，以及基于 SQLite 的海马记忆索引。
- 面向业余无线电的 Ham 模式：SGP4/SDP4 过境预测、多源 TLE 获取、内置业余卫星数据库，以及 ADIF 3.1.4 / CSV 通联日志导出。
- V2 技能系统（SKILL.md frontmatter、JS 沙箱执行、动态工具注册）、日历与 Health Connect 集成，以及带 ML Kit OCR 的 PDF / DOCX 处理。

自 1.2.0 以来 1.2.1 的改动（详见 `CHANGELOG.md`）：

- 位置缓存刷新：`getCurrentLocation` 优先使用 5 分钟内的新缓存位置，API 30+ 改用 `LocationManager.getCurrentLocation`，低版本平滑降级，修复切换地点后仍显示旧位置的问题。
- 笔记操作表滚动：笔记操作底部弹窗内容区改为可纵向滚动，小屏下「删除」按钮仍可触达。
- Release 构建修复：修正 `backup_rules.xml` / `data_extraction_rules.xml` 的 lint 致命错误，使 `./gradlew assembleRelease` 通过。

构建与安装：

- 需要 JDK 21（Android Studio 自带 JBR）、compileSdk 36 / minSdk 26 / targetSdk 35，以及 arm64-v8a 设备。使用 `./gradlew assembleDebug` 构建；步骤见 README 快速开始与 `docs/wiki/Building.md`。
- 首次构建需联网访问 Maven Central 与 JitPack（Sherpa-ONNX 由 JitPack 自动解析）。

说明：

- 源码以 GNU General Public License v3.0 发布；第三方依赖与运行期下载的模型权重（如 Gemma、Sherpa-ONNX 语音模型）遵循各自协议，不在 GPL-3.0 授权范围内，详见 `THIRD_PARTY_NOTICES.md`。
- 本 Release 暂未附带预构建 APK 产物。
