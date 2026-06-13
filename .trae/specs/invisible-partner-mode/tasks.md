# Tasks: 无感伙伴模式

## Phase 0: 海马体升级（MiMo 洞察融入）✅ DONE
- [x] Task 0.1: HippocampusIndex 增加 Scope 层级和新鲜度

## Phase 1: 基础设施（推送 + 定时任务）✅ DONE
- [x] Task 1.1: NotificationHelper.kt — 统一通知工具类
- [x] Task 1.2: AutoSproutWorker.kt — WorkManager 自动发芽定时任务
- [x] Task 1.3: DailyDigestNotifier.kt + DailyDigestWorker — 每日摘要推送

## Phase 2: 零交互录音链路 ✅ DONE
- [x] Task 2.1: RecordingTab 录音结束流程重构（自动保存+通知）

## Phase 3: 温暖点评 ✅ DONE
- [x] Task 3.1: WarmFeedbackService.kt — LLM 点评生成服务
- [x] Task 3.2: 集成点评到笔记保存流程（MainViewModel warmFeedbackState）

## Phase 4: 设置面板 + 海马体降级 ✅ DONE
- [x] Task 4.1: InvisiblePartnerSettings.kt — 设置面板 Composable + DataStore
- [x] Task 4.2: SettingsScreen 集成无感模式入口
- [x] Task 4.3: HippocampusScreen 入口降级到高级选项折叠区

## Phase 5: SproutService 批量能力 ✅ DONE
- [x] Task 5.1: SproutService.sproutBatch() 批量并发发芽

## Phase 6: 编译验证 ✅ DONE
- [x] Task 6.1: BUILD SUCCESSFUL

---

## Phase 7: 发芽报告后续操作（P0）

- [ ] Task 7.1: NoteSproutScreen 底部操作栏
  - 在发芽报告展示区域底部新增操作按钮行
  - "追加笔记"：调用 MainViewModel.createNoteFromText(发芽报告全文) 保存为新笔记
  - "分享"：使用 Android Intent.ACTION_SEND 分享发芽文本（ShareSheet）
  - "基于此再发芽"：将当前发芽结果作为 noteContent 重新调 sprout()
  - 使用 Row + IconButton/FilledTonalButton 布局，风格与现有 UI 一致

- [ ] Task 7.2: 三点菜单扩展
  - 在 NoteSproutScreen 的现有 DropdownMenu 中添加新选项：
    - "添加标签"（暂用 Toast 提示"标签功能开发中"）
    - "复制全文"（ClipboardManager 复制发芽报告 Markdown 文本）
    - "导出 Markdown"（保存为 .md 文件到 Download 目录）
  - 参考 AppRoot 中现有 DropdownMenu 的写法

## Phase 8: 点评上下文感知（P0）

- [ ] Task 8.1: WarmFeedbackService 升级 — 关联历史记忆
  - 新增构造参数 `hippocampus: HippocampusIndex? = null`
  - generateFeedback() 内部逻辑变更：
    1. 如果 hippocampus 不为 null → 调用 hippocampus.getRecentByScope(scope, limit=3)
    2. 对历史记录做关键词重叠检测（取当前笔记关键词 ∩ 历史笔记关键词）
    3. 重叠度 > 30% 则判定为"有关联"
    4. 构造 contextHint 字符串："用户之前也提到过类似的：[摘要片段]"
    5. 将 contextHint 追加到 user message 中（在正文之后）
  - 如果 hippampus 为 null 或无关联历史 → 保持原有行为不变

- [ ] Task 8.2: MainViewModel 传递 HippocampusIndex 给 WarmFeedbackService
  - 修改 warmFeedbackService 的初始化：传入 vm.hippocampus
  - 确保 HippocampusIndex 在 ViewModel 初始化时已可用

## Phase 9: 多人格模式框架（P1）

- [ ] Task 9.1: PartnerPersona 枚举 + DataStore 持久化
  - 新建或附加到 InvisiblePartnerSettings 的 DataStore：
    ```kotlin
    enum class PartnerPersona(val label: String) {
        LIFE("生活记录"),      // 温暖/叙事/周年回顾
        WORK("效率工作"),      // 点评关闭/结构化/待办汇总
        CREATIVE("创作灵感"), // 连接型点评/灵感展开/素材聚类
    }
    ```
  - key_partner_persona DataStore key，默认值 LIFE

- [ ] Task 9.2: InvisiblePartnerSettings 添加模式选择器
  - 在设置面板顶部添加 SingleChoice 组件（类似 Radio Group）
  - 三个选项卡片：生活记录 / 效率工作 / 创作灵感
  - 每个选项有简短描述（一句话说明适合谁）
  - 切换时：
    - Work 模式 → 自动关闭温暖点评开关（禁用不可改）
    - Creative 模式 → 开启点评但切换 prompt 模板
    - Life 模式 → 全部功能默认开启

- [ ] Task 9.3: WarmFeedbackService 多 prompt 支持
  - 新增三套 system prompt 常量：
    - PROMPT_LIFE：现有的引用原文式温暖点评
    - PROMPT_WORK：（空字符串，Work 模式不生成点评）
    - PROMPT_CREATIVE：连接型点评 prompt（重点在于发现和历史的关联）
  - generateFeedback() 根据 persona 参数选择不同 prompt
  - persona 从 DataStore 读取（通过构造函数传入或方法参数传入）

## Phase 10: 周年回顾推送（P1）

- [ ] Task 10.1: DailyDigestNotifier 增加周年检查逻辑
  - 在 buildAndSend() 中新增步骤：
    1. 取 HippocampusIndex.getAll()
    2. 对每条记录计算：是否是 N 年前同月同日（忽略年份）
    3. 筛选 N ∈ {1, 2, 3} 的匹配项
    4. 取最近的一条作为"周年纪念"
  - 如果有周年纪念 → 在 digest 数据中增加 anniversarySnippet 字段
  - NotificationHelper.showDailyDigestNotification() 支持可选的 anniversary 参数
  - 有周年纪念时通知内容追加一行："---\nN 年前的今天：[原文片段]"

## Phase 11: 最终编译验证

- [ ] Task 11.1: 全量编译验证 (`./gradlew assembleDebug`)

---

# Task Dependencies (Phase 7-11)

- [Task 7.1] 无依赖（NoteSproutScreen 已存在）
- [Task 7.2] 依赖 [Task 7.1]（同一文件）
- [Task 8.1] 依赖 WarmFeedbackService（已有）+ HippocampusIndex（已有）
- [Task 8.2] 依赖 [Task 8.1]
- [Task 9.1] 无依赖，可与 Phase 7 并行
- [Task 9.2] 依赖 [Task 9.1]
- [Task 9.3] 依赖 [Task 9.1] + [Task 8.1]
- [Task 10.1] 依赖 DailyDigestNotifier（已有）+ HippocampusIndex（已有）
- [Task 11.1] 所有代码 task 完成后
