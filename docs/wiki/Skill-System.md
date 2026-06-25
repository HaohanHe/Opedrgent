# 技能系统 (Skill System)

## 概述

Opedrgent 的技能系统允许通过标准 SKILL.md 文件定义和加载技能，支持 JS 沙箱执行和文本技能两种类型。

---

## V2 技能标准 (SKILL.md)

### Frontmatter 元数据

```markdown
---
name: calculate-hash
description: 计算文本的 SHA-256/SHA-1/MD5 哈希值
version: 1.0.0
category: utility
require-secret: ALLOW
---
```

### 字段说明

| 字段 | 必填 | 说明 |
|------|:---:|------|
| name | 是 | 技能唯一标识 |
| description | 是 | 技能描述 |
| version | 否 | 版本号 |
| category | 否 | 分类（utility/productivity/analysis 等） |
| require-secret | 否 | 权限级别：ALLOW/ASK/DENY |

---

## JS 技能

### 执行流程

```
用户触发 -> LLM tool_calls: run_js
  -> SkillWebViewExecutor
    -> WebView 加载 scripts/index.html
      -> JavaScript 执行
        -> ai_edge_gallery_get_result() 回调
          -> 结果返回 LLM
```

### 内置 JS 技能

| 技能 | 分类 | 功能 |
|------|------|------|
| calculate-hash | utility | Web Crypto API (SHA-256/SHA-1/MD5) |
| mood-tracker-lite | productivity | 心情追踪 + localStorage CRUD + 仪表盘 |
| interactive-map | utility | 交互式地图 |
| qr-code | utility | 二维码生成 |
| virtual-piano | entertainment | 虚拟钢琴 |

### 文件结构

```
skills/calculate-hash/
├── SKILL.md              # Frontmatter 元数据
└── scripts/
    └── index.html        # JavaScript 实现
```

---

## 文本技能

文本技能是纯 Markdown 文件，定义了 LLM 的行为指令。

### 内置文本技能

| 技能 | 分类 | 功能 |
|------|------|------|
| critical-inquiry | analysis | 批判性探究 |
| insight-sprout | insight | 知识发芽触发 |
| insight-review | review | 洞察评审 |
| text-refine | writing | 文本精炼 |
| mimo-tts | audio | TTS 语音合成 |
| multi-agent-collaboration | agent | 多智能体协作 |

---

## 技能加载方式

### 1. URL 远程加载
从远程 URL 下载 SKILL.md 并解析

### 2. 本地文件导入
从设备存储导入 SKILL.md 文件

### 3. 手动创建
在 UI 中手动输入技能定义

---

## 权限控制 (RequireSecret)

### 三级授权

| 级别 | 说明 |
|------|------|
| ALLOW | 自动允许，无需用户确认 |
| ASK | 每次调用前询问用户 |
| DENY | 禁止调用 |

### 实现

`RequireSecretManager` 管理每个技能的权限状态，存储在 SharedPreferences 中。

---

## Skill 与工具的关系

技能可以通过 `ToolRegistry` 动态注册为工具：

```
SkillLoader 加载 SKILL.md
  -> SkillDefinition 解析
    -> ToolRegistry.register()
      -> 注册为可调用工具
        -> LLM 通过 tool_calls 调用
```

---

## GalleryBridge (JS 回调桥)

JS 技能通过 `GalleryBridge` 与 Kotlin 层通信：

```javascript
// JavaScript 端
ai_edge_gallery_get_result({
    type: "hash_result",
    data: { sha256: "..." }
});
```

```kotlin
// Kotlin 端 (GalleryBridge)
webView.addJavascriptInterface(object {
    @JavascriptInterface
    fun ai_edge_gallery_get_result(result: String) {
        // 处理 JS 返回的结果
    }
}, "GalleryBridge")
```
