# 架构设计

## 分层架构

```
┌─────────────────────────────────────────────────────────────┐
│                    UI Layer (Compose)                        │
│  SessionScreen | InterviewScreen | KnowledgeBaseScreen      │
│  SettingsScreen | Note*Screen | EditorTeamScreen            │
│  HomeDashboard | Export | Automations | Recording           │
├─────────────────────────────────────────────────────────────┤
│                  ViewModel Layer                             │
│              MainViewModel (核心调度)                         │
├──────────────────┬──────────────────────────────────────────┤
│   Tool Layer     │          Network Layer                    │
│   ToolExecutor   │  LlmClient (双协议: OpenAI + Anthropic)   │
│   ├─ 内置工具    │  ├─ Chat Completions (+ JSON Mode)        │
│   │  web_search  │  ├─ Messages API (Anthropic兼容)          │
│   │  read_url    │  └─ Step Plan 推理参数                     │
│   │  run_intent  │  WebViewAgent (浏览器自动化)               │
│   │  run_calendar│                                          │
│   │  health_read │  HttpClients (OkHttp池)                   │
│   │  speech_to_text  ├─ RateLimiter (令牌桶)                 │
│   │  mimo_tts    │  ├─ SmartCircuitBreaker (熔断)            │
│   ├─ 阶跃工具集  │  ├─ MultiLevelCacheManager (L1+L2)       │
│   │  step_search │  └─ AdaptiveConcurrencyController         │
│   │  step_rag    │                                          │
│   │  step_image_edit                                         │
│   │  step_mobile_agent                                       │
│   └─ Skill工具   │                                          │
├──────────────────┼──────────────────────────────────────────┤
│   STT Layer      │          TTS Layer                       │
│   AsrManager     │  TtsPlayer (三引擎路由)                   │
│   ├─ SherpaONNX  │  1. System TTS (Android原生)              │
│   ├─ StepAudio   │  2. StepAudio TTS (Global+Inline)        │
│   └─ MiMo ASR    │  3. MiMo TTS (本地推理)                  │
├──────────────────┴──────────────────────────────────────────┤
│                Storage & Memory Layer                        │
│  HippocampusIndex (SQLite全局索引, 关键词+LIKE匹配)          │
│  MemoryStore (SharedPreferences, 用户记忆+笔记同步)          │
│  Note/Folder (Room DAO)                                     │
│  KnowledgeGraph (笔记关系图)                                 │
│  KnowledgeBase (SQLite全文检索)                              │
├─────────────────────────────────────────────────────────────┤
│                 Insight & Agent Layer                        │
│  InsightSproutEngine (4阶段知识发芽)                         │
│  AgentSwarm + MultiAgentOrchestrator (多智能体编排)          │
│  EditorTeamService (编辑团队管线)                            │
│  SproutService (笔记发芽服务)                                │
└─────────────────────────────────────────────────────────────┘
```

---

## 核心数据流

### 对话流程

```
用户输入 -> MainViewModel.runModel()
  -> LlmClient.chat() / streamChat()
    -> API 请求 (OpenAI Compatible / Anthropic Messages)
      -> 流式 SSE 响应
        -> Tool Call 解析
          -> ToolExecutor.execute()
            -> 具体工具执行
              -> 结果返回 LLM
                -> 流式输出到 UI
```

### 工具调用流程

```
LLM 返回 tool_calls
  -> ToolExecutor.execute(toolName, params)
    -> 匹配已注册工具
      -> 工具.execute(params)
        -> 返回结果字符串
          -> 注入对话上下文
            -> LLM 继续生成
```

### 健康数据流

```
SettingsScreen (开关)
  -> ACTIVITY_RECOGNITION 权限请求
    -> Health Connect 权限请求 (7项读取权限)
      -> 权限授予 -> healthEnabled = true
        -> MainViewModel.buildSystemPrompt()
          -> HealthConnectHelper.getHealthSummaryForPrompt()
            -> 今日摘要注入 system prompt
        -> LLM 也可调用 health_read 工具获取详细数据
```

### 日历操作流

```
用户: "明天下午3点开会"
  -> LLM 判断需要创建日程
    -> tool_calls: run_calendar(action="create", title="开会", start_time="明天下午3点")
      -> RunCalendarTool.handleCreate()
        -> parseTimeToEpoch("明天下午3点") -> 时间戳
          -> CalendarHelper.createEvent() -> ContentResolver.insert()
            -> 写入系统日历
              -> 返回成功信息
```

---

## 关键设计决策

### 1. 手动 DI (无 Hilt/Koin)
- 保持全项目一致性
- 所有依赖通过构造函数注入
- ViewModelFactory 模式

### 2. 双协议 LLM
- Chat Completions (OpenAI 兼容) — 主要协议
- Messages (Anthropic 兼容) — 阶跃星辰等模型

### 3. 多引擎 STT 降级链
- Sherpa-ONNX (离线) -> MiMO ASR -> Android SpeechRecognizer
- 自动检测可用性，无缝切换

### 4. 三层渐进式上下文注入
- 标签层：海马体关键词聚合（几乎不占上下文）
- 索引层：标题 + 一句话概要（轻量）
- 联网搜索：工具调用验证关键事实
