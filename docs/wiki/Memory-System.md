# 记忆系统

## 概述

Opedrgent 的记忆系统由两个核心组件构成，模拟人类大脑的选择性记忆和快速检索能力：

1. **HippocampusIndex（海马体索引）** — SQLite 全局索引，自动索引所有内容来源
2. **MemoryStore（记忆存储）** — SharedPreferences 用户显式记忆

---

## 海马体索引 (HippocampusIndex)

### 设计理念

模拟大脑海马体的功能：
- **选择性记忆**：不是所有信息都值得索引
- **快速检索**：基于关键词的模糊匹配
- **跨会话持久**：SQLite 持久化，重启不丢失

### 数据结构

```kotlin
data class IndexedItem(
    val id: String,           // UUID
    val sourceType: SourceType,  // 来源类型
    val sourceId: String,     // 来源对象 ID
    val title: String,        // 标题
    val summary: String,      // 摘要（前500字）
    val keywords: String,     // 逗号分隔的关键词（最多20个）
    val scope: MemoryScope,   // GLOBAL / PROJECT / SESSION
    val createdAt: Long,
    val updatedAt: Long,
)
```

### 来源类型

| SourceType | 写入时机 | 作用域 |
|------------|---------|--------|
| NOTE | 笔记创建/更新时 | PROJECT |
| CONVERSATION | AI 对话完成时 | SESSION |
| RECORDING | 录音转录完成时 | SESSION |
| SPROUT | 发芽报告生成后 | PROJECT |
| INTERVIEW | 面试会话关闭时 | SESSION |
| USER_PREFERENCES | 设置变更时 | GLOBAL |

### 检索机制

```kotlin
fun query(keyword: String, limit: Int = 10): List<IndexedItem>
```

- SQL `LIKE '%keyword%'` 模糊匹配 `title`、`summary`、`keywords` 三个字段
- 按 `created_at DESC` 排序
- 支持按 `sourceType` 过滤

### 关键词提取

`extractKeywords()` 方法：
1. 按标点分割文本，取长度 >= 2 的词
2. 对长度 > 4 的中文片段提取 2-gram
3. 按词频降序取前 20 个关键词
4. 逗号分隔存入 `keywords` 字段

---

## 记忆存储 (MemoryStore)

### 数据结构

```kotlin
data class MemoryEntry(
    val id: String,
    val title: String,
    val content: String,
    val type: MemoryType,  // USER / FEEDBACK / PROJECT / REFERENCE / NOTE_SUMMARY
    val createdAt: Long,
    val updatedAt: Long,
)
```

### 存储方式

- 底层：Android SharedPreferences
- 每条记录序列化为 JSON，以 `memory_{id}` 为 key
- 所有 ID 索引存在 `memory_ids` key 中

### 与海马体的关系

| 维度 | MemoryStore | HippocampusIndex |
|------|-------------|------------------|
| 存储后端 | SharedPreferences | SQLite |
| 写入方式 | 手动 + 笔记自动同步 | 全自动 |
| 检索能力 | 全量遍历 | SQL LIKE 模糊匹配 |
| 注入 prompt | `# 记忆` 段 | `[对话历史记忆]` 段 |

笔记保存时双写两者，但 HippocampusIndex 的检索能力更强。

---

## 上下文注入

### Prompt 注入位置

在 `PromptBuilder.buildMemorySection()` 中：

```
# 记忆
[用户手动添加的记忆]

[笔记记忆]
[MemoryStore 中的笔记摘要]

[对话历史记忆]
[HippocampusIndex 中的最近记录]
```

### 发芽中的三层渐进注入

在 `SproutService.sprout()` 中：

```
第一层：知识库标签（海马体关键词聚合）
  -> 让 AI 快速感知知识背景（几乎不占上下文）

第二层：相关知识索引（标题 + 一句话概要）
  -> 海马体相关条目 + 其他笔记索引

第三层：联网搜索验证（工具调用）
  -> AI 主动搜索验证关键事实
```

---

## 海马体在面试模式中的应用

### HippocampusMemory

面试模式下的实时注意力管理组件：

- **目标锚定**：设定面试主目标，防止跑题
- **漂移检测**：基于关键词匹配 + Jaccard 相似度
- **注意力注入**：每轮对话生成上下文片段注入 LLM

### 漂移检测算法

```kotlin
fun detectDrift(userInput: String): DriftResult
```

1. 字符级 Jaccard 相似度
2. 关键词重叠度
3. 历史趋势分析
4. 返回漂移等级和建议干预措施
