# 工具系统

## 概述

Opedrgent 的工具系统允许 LLM 在对话过程中动态调用外部能力。工具通过两个通道被 LLM 发现：

1. **Function Calling Schema** — API 请求中的 `tools` 数组，包含 name/description/parameters
2. **System Prompt 文本** — `ToolPrompts.kt` 中的详细使用说明和调用示例

---

## 内置工具列表

| 工具名 | 文件 | 功能 | System Prompt |
|--------|------|------|:---:|
| `web_search` | WebSearchTool.kt | 网页搜索 | 有 |
| `read_url` | ReadUrlTool.kt | URL 内容获取 | 有 |
| `run_intent` | RunIntentTool.kt | Android Intent 调度 (6种) | 有 |
| `run_calendar` | RunCalendarTool.kt | 日历 CRUD (创建/查询/修改/删除) | 有 |
| `health_read` | HealthTool.kt | Health Connect 健康数据读取 | 有 |
| `run_js` | RunJsTool.kt | JS Skill 沙箱执行 | 有 |
| `speech_to_text` | SpeechToTextTool.kt | 语音转文字 | 有 |
| `mimo_tts` | MimoTtsTool.kt | TTS 语音合成 | 有 |
| `insight_sprout` | InsightSproutTool.kt | 知识发芽 | 有 |
| `deep_research` | DeepResearchTool.kt | 深度研究 | 有 |
| `generate_report` | GenerateReportTool.kt | 报告生成 | 有 |
| `reverse_geocode` | ReverseGeocodeTool.kt | 坐标转地址 | 有 |
| `open_browser` | OpenBrowserTool.kt | 打开浏览器 | 有 |
| `recall` | RecallTool.kt | 记忆召回 | 有 |

### 阶跃星辰专属工具

| 工具名 | 功能 |
|--------|------|
| `step_search` | 阶跃原生搜索 API |
| `step_rag` | 知识库 RAG 检索 |
| `step_image_edit` | 图像编辑 + 图生图 |
| `step_image_to_image` | 图生图 |
| `step_mobile_agent` | 手机操作 Agent |
| `step_vision` | 图像理解 |
| `step_video_summary` | 视频摘要 |

---

## 工具注册机制

### ToolExecutor

所有工具在 `ToolExecutor` 构造函数中注册：

```kotlin
// network/ToolExecutor.kt
init {
    register(WebSearchTool(context))
    register(ReadUrlTool(context))
    register(RunIntentTool(context))
    register(RunCalendarTool(context))  // 日历 CRUD
    register(HealthTool(context))       // 健康数据
    // ...
}
```

### Tool 接口

每个工具实现 `Tool` 接口：

```kotlin
// tool/Tool.kt
interface Tool {
    val binding: ToolBinding  // name + description + parameters
    suspend fun execute(params: Any): Any
}
```

---

## 工具 Prompt 设计

### 双通道发现机制

| 通道 | 位置 | 作用 |
|------|------|------|
| Function Calling Schema | `ToolExecutor.getToolDefinitions()` | API 层面的工具定义 |
| System Prompt 文本 | `ToolPrompts.getAllToolPrompts()` | 详细使用说明和调用示例 |

### Prompt 结构

每个工具的 prompt 包含：
1. 工具名称和描述
2. 使用场景（什么时候该调用）
3. 参数说明
4. 调用示例（JSON 格式）

### 示例：run_calendar 的 Prompt

```
## run_calendar - 日历日程管理

读取、创建、修改、删除用户的系统日历事件。

**五种操作：**
1. create - 创建日程
2. query_today - 查询今天的日程
3. query_tomorrow - 查询明天的日程
4. query_week - 查询本周日程
5. update - 修改指定日程（部分更新）
6. delete - 删除指定日程

**调用示例：**
{"action": "create", "title": "团队周会", "start_time": "明天下午3点", "location": "3号会议室"}
{"action": "query_today"}
{"action": "update", "event_id": 42, "start_time": "后天下午2点"}
```

---

## 日历工具详解

### 自然语言时间解析

`RunCalendarTool.parseTimeToEpoch()` 支持：
- "明天下午3点" -> 次日 15:00
- "一小时后" -> 当前时间 + 1h
- "下周一" -> 下周一 00:00
- "2026-06-25 15:00" -> 精确时间

### 部分更新 (Update)

修改日程时，只更新 LLM 提供的字段，未提供的字段保持不变：
1. 先通过 `CalendarHelper.queryEventById()` 查询现有事件
2. 合并提供的字段与现有字段
3. 调用 `CalendarHelper.updateEvent()` 更新

---

## 健康工具详解

### 三种查询类型

| query_type | 返回数据 |
|------------|---------|
| `summary` | 今日运动摘要（步数/距离/卡路里/心率） |
| `steps` | 最近 N 天步数统计（默认7天） |
| `sleep` | 最近睡眠数据 |

### System Prompt 自动注入

开启运动健康后，每次对话 system prompt 自动包含：

```
# 用户运动健康数据
今日运动数据:
- 步数: 8432
- 距离: 5.23 km
- 消耗: 320 千卡
- 心率: 72 bpm (58-112)

昨晚睡眠: 23:15-07:30 (8小时15分钟)
```
