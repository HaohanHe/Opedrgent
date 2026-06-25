# 发芽系统 (Insight Sprout)

## 概述

发芽是 Opedrgent 的核心特色功能——将用户笔记通过 AI 分析，生成叙事式洞察文章。不是结构化的要点列表，而是像专栏文章一样有深度、有温度的分析。

---

## 四阶段洞察引擎

### 阶段一：种子提取 (SproutSeed)
从笔记原文中提取触发洞察的关键片段

### 阶段二：跨领域关联 (SproutConnection)
将种子与知识库中的其他内容建立联系

### 阶段三：AHA 洞察 (SproutInsight)
生成突破性的洞察和新视角

### 阶段四：金句回响 (SproutQuote)
提炼最有力量的一句话

---

## 三层渐进式上下文注入

### 设计理念

像婚恋软件一样分层：
- **第一层（标签）**：只给关键词，让 AI 知道"你有什么背景知识"
- **第二层（索引）**：标题 + 一句话概要，让 AI 知道"大概是什么"
- **第三层（联网搜索）**：工具调用验证关键事实

### 实现

```kotlin
// SproutService.sprout()

// 第一层：标签 — 海马体关键词聚合
val allKeywords = relatedItems
    .flatMap { it.keywords.split(",").map(String::trim) }
    .filter { it.length >= 2 }
    .distinct()
    .take(10)
prompt += "\n\n## 知识库标签\n${allKeywords.joinToString("、")}"

// 第二层：索引 — 标题 + 一句话概要
val indexLines = relatedItems.joinToString("\n") {
    "- [${it.sourceType.label}] ${it.title}：${it.summary.take(80)}"
}
prompt += "\n\n## 相关知识索引\n$indexLines"

// 其他笔记索引
if (otherNotesContext.isNotBlank()) {
    prompt += "\n\n## 其他笔记索引\n$otherNotesContext"
}

// 第三层：联网搜索（工具指令）
prompt += "\n\n## 重要：联网搜索验证\n..."
```

---

## 叙事式文章输出

### 数据结构

```kotlin
data class SproutArticle(
    val generatedAt: Long,
    val modelUsed: String,
    val summary: String,           // 整体摘要
    val articles: List<ArticleSection>,  // 文章列表
    val actionItems: List<String>, // 行动建议
    val relatedConcepts: List<String>,
    val sentiment: Sentiment,
    val readingTimeMinutes: Int,
)

data class ArticleSection(
    val title: String,      // 编号标题（如"01. 一座王宫换来的大学"）
    val seed: String,       // 种子 — 原文触发点
    val body: String,       // 正文 — AI 生成的完整分析
    val ahaMoment: String,  // Aha 瞬间 — 金句引用
    val importance: Int,    // 重要性 1-5
)
```

### 存储

发芽报告以 JSON 格式存储在笔记的 `sproutReportJson` 字段中，通过 `NoteDao` 的全列 UPDATE 持久化到 SQLite。

---

## JSON 解析加固

### 问题

LLM 偶尔在文章正文中输出未转义的双引号（如 `"未来次数"`），导致标准 JSON 解析失败。

### 解决方案

1. **标准解析优先**：先尝试 `JSONObject` 解析
2. **正则兜底**：失败时用正则提取，title/seed/body 用 `[\s\S]*?` 非贪婪匹配
3. **stripThinkingTags**：移除 Qwen3.5 的 `<think>` 标签
4. **max_tokens = 32768**：确保完整输出不被截断

---

## LLM 参数配置

```kotlin
val jsonBody = JSONObject().apply {
    put("model", modelId)
    put("max_tokens", 32768)
    put("temperature", 1.0)
    put("top_p", 0.95)
    put("top_k", 20)
    put("presence_penalty", 1.5)
    put("messages", JSONArray().apply {
        put(JSONObject().apply {
            put("role", "system")
            put("content", "你是顶级知识管理顾问...")
        })
        put(JSONObject().apply {
            put("role", "user")
            put("content", prompt)
        })
    })
}
```

---

## 发芽数据持久化

### 问题

自动保存触发时，`save()` 函数构造 Note 对象漏掉 `sproutReportJson` 字段，导致全列 UPDATE 覆盖 DB 为 null。

### 解决

在 `NoteEditorScreen.save()` 中补全所有不可编辑字段：

```kotlin
val note = Note(
    // ... 可编辑字段 ...
    sproutReportJson = currentNote?.sproutReportJson,  // 保留发芽数据
    summary = currentNote?.summary,
    folderId = currentNote?.folderId,
    isPinned = currentNote?.isPinned,
    isDeleted = currentNote?.isDeleted,
    createdAt = currentNote?.createdAt,
)
```
