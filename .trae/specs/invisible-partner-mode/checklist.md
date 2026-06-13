# Checklist: 无感伙伴模式

## 基础设施

- [ ] NotificationHelper 创建了 4 个通知渠道（sprout_complete / daily_digest / warm_feedback / auto_save）
- [ ] 每条通知都有正确的点击跳转 PendingIntent
- [ ] AutoSproutWorker 继承 Worker 并在 doWork() 中实现完整发芽流程
- [ ] AutoSproutWorker 发芽结果写入 SproutReportStore 并推送通知
- [ ] DailyDigestWorker 从 HippocampusIndex 查询昨日数据并聚合
- [ ] DailyDigestWorker 推送的通知包含笔记数+摘要+未读发节数

## 零交互录音

- [ ] RecordingTab 录音转写完成后自动调用 createNoteFromText（无需用户点保存）
- [ ] 原"保存笔记"按钮变为"编辑"功能
- [ ] 自动保存后显示通知（可点击编辑/删除）
- [ ] 用户仍可手动修改自动保存的笔记标题和内容

## 温暖点评

- [ ] WarmFeedbackService.generateFeedback() 使用 LlmClient 调用轻量 prompt
- [ ] 点评 prompt 包含：先肯定 + 挑亮点 + <50字 + 不批评的规则
- [ ] 点评生成有 3 秒超时保护，不阻塞主流程
- [ ] 笔记保存后展示点评 snackbar（替代原 toast）
- [ ] 发芽完成后也触发点评

## 设置面板

- [ ] InvisiblePartnerSettings 展示 4 个开关项（录音自动保存/自动发芽/每日推送/温暖点评）
- [ ] 自动发芽和每日推送支持时间选择器
- [ ] 设置使用 DataStore 持久化
- [ ] 开关变化时动态注册/注销 WorkManager 任务
- [ ] SettingsScreen 有"无感伙伴模式"一级入口卡片
- [ ] 海马体入口从设置一级页面移除或降级

## 批量发芽

- [ ] SproutService.sproutBatch() 接受笔记列表并返回结果列表
- [ ] sproutBatch 内部并发限制为 2
- [ ] AutoSproutWorker 使用 sproutBatch 而非循环单条调用

## 编译与走查

- [ ] `./gradlew assembleDebug` 编译通过
- [ ] 录音 → 停止 → 转写完成 → 自动存笔记（0 次额外点击）
- [ ] 笔记保存后看到点评 snackbar 而非 "已保存"
- [ ] 设置中可以开关所有自动化功能
- [ ] 关闭所有开关后 App 行为与改造前完全一致（无副作用）
