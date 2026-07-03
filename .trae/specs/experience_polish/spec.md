# Opedrgent - 体验完善与防呆设计 PRD

## Overview
- **Summary**: 对 Opedrgent 应用进行深度体验完善，重点优化发芽动画、防呆设计、对话交互、错误处理等关键用户体验环节，确保应用稳定、流畅、用户友好。
- **Purpose**: 解决用户反馈的动画突兀、重复触发、状态不一致等问题，提升整体产品质感和用户信任度。
- **Target Users**: 所有使用 Opedrgent 的用户，特别是首次使用者和频繁使用发芽功能的用户。

## Goals
- 发芽动画流畅自然，状态切换无突兀感
- 关键操作具备防呆设计，防止误操作和重复触发
- 对话流程状态管理清晰，无数据丢失风险
- 错误处理友好，用户能明确了解问题并知道如何解决
- 首次引导体验完整，能帮助用户快速上手核心功能

## Non-Goals (Out of Scope)
- 不涉及新功能开发或架构重构
- 不修改后端 API 接口
- 不调整数据库 schema
- 不优化性能（除非影响体验）

## Background & Context
- 项目已完成核心功能开发（对话、笔记、知识图谱、发芽）
- 已有 OnboardingScreen 但体验待完善
- 发芽功能存在动画过渡生硬、状态管理问题
- 知识图谱存在事务边界问题
- 对话流程存在工具确认队列和状态同步问题

## Functional Requirements
- **FR-1**: 发芽页面状态切换具备平滑动画过渡（加载、空、错误、成功）
- **FR-2**: 发芽操作具备并发控制，防止重复触发
- **FR-3**: 关键操作（删除、清空、导出）具备二次确认或防呆机制
- **FR-4**: 首次引导支持滑动手势、丰富动画、完整内容覆盖核心功能
- **FR-5**: 工具调用确认流程安全可靠，无状态不一致风险
- **FR-6**: 知识图谱操作具备事务保障，数据一致性有保障

## Non-Functional Requirements
- **NFR-1**: 动画过渡时间 200-300ms，视觉流畅无卡顿
- **NFR-2**: 防呆设计应在用户操作前给予明确提示
- **NFR-3**: 错误提示应清晰、友好、可操作
- **NFR-4**: 状态管理应符合 Android MVVM 最佳实践
- **NFR-5**: 代码改动应遵循单一职责原则，不引入不必要复杂度

## Constraints
- **Technical**: Android Compose, Kotlin, MVVM 架构
- **Dependencies**: 依赖现有组件和服务，不引入新第三方库
- **Scope**: 限于已存在的功能模块优化

## Assumptions
- 用户使用 Android 设备
- 编译环境后续提供，当前仅进行代码修改
- 现有基础组件（Feedback、Navigation）正常工作

## Acceptance Criteria

### AC-1: 发芽页面动画过渡
- **Given**: 用户进入发芽页面，当前状态为"空"
- **When**: 点击"开始发芽"按钮
- **Then**: 页面从"空"状态平滑淡入到"加载中"状态，无跳变
- **Verification**: `human-judgment`

### AC-2: 发芽加载状态反馈
- **Given**: 发芽正在进行中
- **When**: 观察页面状态
- **Then**: 显示清晰的加载动画和"正在发芽"文字提示
- **Verification**: `human-judgment`

### AC-3: 发芽防并发
- **Given**: 用户快速连续点击"发芽"按钮
- **When**: 第一次点击后立即点击第二次
- **Then**: 第二次点击无效，按钮保持禁用状态
- **Verification**: `programmatic`

### AC-4: 追加笔记防呆
- **Given**: 用户点击"追加笔记"按钮
- **When**: 操作正在进行中再次点击
- **Then**: 按钮禁用，操作完成后恢复
- **Verification**: `programmatic`

### AC-5: 导出 Markdown 防呆
- **Given**: 用户点击"导出 Markdown"
- **When**: 内容为空或权限不足
- **Then**: 给出友好提示，不崩溃
- **Verification**: `human-judgment`

### AC-6: 首次引导滑动手势
- **Given**: 用户在引导页
- **When**: 左右滑动屏幕
- **Then**: 页面平滑切换到上一页/下一页
- **Verification**: `human-judgment`

### AC-7: 首次引导动画效果
- **Given**: 用户进入新的引导页
- **When**: 观察页面元素
- **Then**: 图标有弹跳入场动画，标题和副标题依次淡入
- **Verification**: `human-judgment`

### AC-8: 工具确认流程
- **Given**: AI 调用高危工具
- **When**: 用户点击确认按钮
- **Then**: 确认状态正确更新，无重复确认问题
- **Verification**: `programmatic`

### AC-9: 知识图谱事务保障
- **Given**: 调用 rebuildFromNotes 重建图谱
- **When**: 重建过程中发生异常
- **Then**: 数据回滚，不留下脏数据
- **Verification**: `programmatic`

### AC-10: 引导完成过渡
- **Given**: 用户完成引导点击"进入"
- **When**: 观察页面切换
- **Then**: 引导页平滑淡出，主界面淡入
- **Verification**: `human-judgment`

## Open Questions
- [ ] 是否需要添加发芽结果的分享功能？
- [ ] 是否需要添加引导页的进度保存（中途退出后下次继续）？
- [ ] 是否需要添加网络超时状态的专门提示？
