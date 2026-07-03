# Opedrgent - 体验完善与防呆设计验证清单

## 发芽页面动画与防呆
- [x] 发芽页面四个状态（Loading/Empty/Error/Success）切换时有平滑淡入淡出动画
- [x] 加载状态显示清晰的动画和"正在发芽"文字提示
- [x] 发芽按钮在生成过程中禁用，防止重复触发
- [x] 配置变化后不会重复自动触发发芽
- [x] 追加笔记按钮在操作过程中禁用
- [x] 导出 Markdown 有空内容检查
- [x] 导出 Markdown 有 Android 版本权限预检
- [x] 行动建议勾选状态使用 rememberSaveable 持久化

## 首次引导体验
- [x] 支持左右滑动手势切换页面
- [x] 图标有弹跳入场动画
- [x] 标题和副标题依次淡入
- [x] 背景渐变层随页面切换
- [x] 圆形指示器有缩放动画
- [x] 包含发芽功能介绍页（第4页）
- [x] 非第一页时显示上一页按钮
- [x] 引导完成后有淡出过渡动画
- [x] 首次启动无主界面闪烁

## 对话流程与工具确认
- [x] resolveToolConfirmation 添加 !isCompleted 判断
- [x] ToolExecutor 默认确认回调改为拒绝
- [x] 多次调用 resolveToolConfirmation 不会抛异常

## 知识图谱事务保障
- [x] 添加 graphMutex 串行化图谱操作
- [x] rebuildFromNotes 在同一事务内完成清空+写入
- [x] doLinkNote 边写入与 trimLinks 包裹在同一事务
- [x] upsertEntities 支持 useTransaction 参数
