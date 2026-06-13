# 全局海马体记忆系统 Spec

## Why
当前 App 的记忆是碎片化的：笔记在 NoteRepository，对话在 ChatStore，录音转写在录音模块，发芽报告在 SproutService，各自独立、互不联通。用户希望整个 App 拥有一个统一的"海马体"——一个跨所有内容源的全局索引，让任何功能都能查询和引用全 App 的知识。

## What Changes
- 新建 `HippocampusIndex` — 全局内存索引，覆盖笔记/对话/录音转写/发芽报告/用户记忆
- 新建 `HippocampusScreen` — 设置中可查看全局索引的 UI（搜索/浏览/管理）
- 每个内容源变更时自动同步到索引
- 提供查询 API 供 SproutService 等功能使用

## Impact
- Affected specs: speech-to-text-and-insight-sprout（发芽功能已使用笔记上下文）
- Affected code: `storage/`, `note/`, `ui/SettingsScreen.kt`, `note/SproutService.kt`

## ADDED Requirements

### Requirement: 全局内容索引
系统 SHALL 提供一个统一的内存索引 `HippocampusIndex`，覆盖所有内容源。

#### Scenario: 笔记变更自动索引
- **WHEN** 用户创建或编辑笔记
- **THEN** 该笔记的标题+内容摘要自动写入全局索引，type=NOTE

#### Scenario: 对话变更自动索引
- **WHEN** AI 对话产生新消息
- **THEN** 该会话的标题+最新摘要自动写入全局索引，type=CONVERSATION

#### Scenario: 录音转写自动索引
- **WHEN** 用户完成一段录音转写
- **THEN** 转写文本摘要自动写入全局索引，type=RECORDING

#### Scenario: 发芽报告自动索引
- **WHEN** 发芽报告生成完成
- **THEN** 报告摘要自动写入全局索引，type=SPROUT

### Requirement: 全局搜索查询
系统 SHALL 提供跨所有内容源的统一搜索。

#### Scenario: 按关键词搜索
- **WHEN** 用户在海马体界面输入关键词
- **THEN** 返回所有匹配的内容条目，按相关性排序，显示来源类型和时间

### Requirement: 供其他功能查询
系统 SHALL 暴露查询 API 供 SproutService 等功能使用。

#### Scenario: 发芽时查询相关笔记
- **WHEN** SproutService 执行发芽分析
- **THEN** 可通过 `hippocampus.query(keyword, limit)` 获取相关笔记/对话/录音摘要作为上下文

### Requirement: 海马体管理界面
系统 SHALL 在设置中提供海马体管理页面。

#### Scenario: 查看索引列表
- **WHEN** 用户进入设置 → 海马体记忆
- **THEN** 显示所有索引条目（按时间倒序），支持搜索和按类型筛选

#### Scenario: 删除索引条目
- **WHEN** 用户左滑或长按某条索引
- **THEN** 可删除该条目（不影响原始内容）

## MODIFIED Requirements

### Requirement: SproutService 发芽上下文
SproutService 的发芽分析 SHALL 优先使用 HippocampusIndex 的查询结果作为上下文，而非仅使用最近笔记。

#### Scenario: 发芽时注入全局上下文
- **WHEN** 发芽分析启动
- **THEN** 从 HippocampusIndex 搜索与当前笔记相关的条目（笔记+对话+录音），注入到 LLM prompt

## REMoved Requirements
- 无
