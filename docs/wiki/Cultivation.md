# 个人修炼模式（cultivation 包）

个人言行修炼批判镜：用户录制本人真实语音，端侧 ASR 转写后，由本地模型对照用户自定义的“理想人格行为基准”，反讨好但不对抗地指出言行差距、给出可直接使用的替代说法，并长期追踪。全程默认本地，云端必须由用户显式开关。

本页说明第二步原生固化的 `cultivation` 包骨架；第一步的低成本验证见内置技能 `app/src/main/assets/skills/self-mirror/SKILL.md`，风格对照评测见 `docs/eval/self-mirror-style-eval`。

## 设计原则：模型为大，工程供具

- 如何组织分析、聚焦哪几个问题、走哪条路由，全部由模型结合完整语境决定；工程不写死分析步骤、输出模板与问题数量，也不建任何关键词/敏感词表。
- 工程只承担无语义的机械职责：存取数据、调用模型、字符串级事实核验、按模型给出的路由做分流。
- MVP 不打数值分；危机状态由模型在完整语境中判断，工程只做独立旁路的机械路由，宁枉勿纵。

## 包结构

```
cultivation/
├── model/
│   └── CultivationModels.kt      # VirtueBaseline/VirtueDimension、MirrorReport/MirrorIssue、
│                                 # MirrorRoute(ANALYZE/SUPPORT/CRISIS)、FeedbackMode、IssueMark
├── store/
│   ├── CultivationDatabase.kt    # cultivation.db：virtue_baseline / mirror_report 两表，原生 SQLiteOpenHelper
│   ├── VirtueBaselineStore.kt    # 基准版本管理，同一时刻仅一条活跃（需求卡 N1）
│   └── CultivationReportStore.kt # 报告与用户标记持久化、独立清除入口（需求卡 N5）
├── mirror/
│   ├── MirrorPromptBuilder.kt    # 元原则提示 + 轻量 JSON 输出协议；与 self-mirror 技能同源
│   ├── MirrorAnalyzer.kt         # 一次生成 + 结构化解析；含 Local/Remote 两个 MirrorLlmBackend
│   └── AntiSycophancyGuard.kt    # N4 双保险：模型自审 + 确定性字符串核验（verify 引用真实存在、有替代行动）
└── engine/
    └── CultivationEngine.kt      # 轻量编排：取基准/历史 → 分析 → 双保险 → 至多重做一次 → 落库
```

## 数据流

1. 上层在录音链路完成本人声道分离（N2）后，得到本人转写文本。
2. `CultivationEngine.analyze(backend, transcript, ...)` 读取活跃基准（或使用传入基准）。
3. `MirrorAnalyzer` 用 `MirrorPromptBuilder` 组装提示，经 `MirrorLlmBackend` 生成：
   - `LocalMirrorBackend` 走 `LocalLlmEngine`（LiteRT-LM，默认，不出设备）；
   - `RemoteMirrorBackend` 走 `LlmClient.chatCompletions`，仅在用户显式选择云端时构造。
4. 模型返回路由与结构化 JSON，解析为 `MirrorReport`。
5. `AntiSycophancyGuard`：
   - ANALYZE：逐条核验引用原句是否逐字出现在转写、是否给出替代行动；
   - SUPPORT/CRISIS：核验支持性回应与求助资源是否齐备，且 CRISIS 不得继续挑错。
6. 未过质量门时，先模型自审、再带事实性违规清单重生成一次；两次仍不过则返回 `success=false`，不展示低质反馈。
7. 通过后写入 `mirror_report`，供报告卡与长期轨迹使用。

## 已实现

- 领域模型、本地存储、提示与解析、端/云双后端、反讨好双保险、引擎质量门；
- 状态管理 `ui/state/CultivationStateManager.kt`（对齐 SproutStateManager，单一 UiState + viewModelScope）；
- 界面 `ui/CultivationScreen.kt`：批判镜（后端/风格选择、转写输入、报告卡、问题标记）、人格基准编辑、修炼轨迹三段式；
- 手动 DI：在 `MainViewModel` 持有 `CultivationStateManager`（不引入 Hilt/Koin）；
- 导航与入口：`AppRoot` 注册二级路由 `cultivation`，首页“发现新功能”网格新增“言行修炼”入口；
- 文案走 strings.xml，已补 values（中）/values-en（英）/values-ja（日）。

## 后续接线

- 与录音/ASR 主链路、说话人分离打通（N2）：录音完成后可直接调用 `CultivationStateManager.loadTranscript(text)` 带入本人转写并跳转，当前版本支持手动粘贴；
- P1 榜样镜子（ExemplarMirror），基于本地向量检索，另行立项。

## 规约

手动 DI、中文注释；提示与日志不使用 emoji；修炼数据默认只走本地存储与端侧模型；commit 格式 `type: 中文描述`。
