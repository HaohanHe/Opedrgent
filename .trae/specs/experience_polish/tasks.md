# Opedrgent - 体验完善与防呆设计实现计划

## [x] Task 1: 发芽页面状态动画过渡优化
- **Priority**: high
- **Depends On**: None
- **Description**: 
  - 使用 AnimatedContent 包裹发芽页面四个状态（Loading/Empty/Error/Success）
  - 添加淡入淡出过渡动画（220ms）
  - 创建 SproutUiState 状态类统一管理状态
- **Acceptance Criteria Addressed**: AC-1, AC-2
- **Test Requirements**:
  - `human-judgment` TR-1.1: 状态切换时有平滑淡入淡出效果，无跳变
  - `human-judgment` TR-1.2: 加载状态显示清晰的动画和文字提示

## [x] Task 2: 发芽操作防并发控制
- **Priority**: high
- **Depends On**: None
- **Description**: 
  - 在 NoteSproutScreen 和 NoteEditorScreen 中使用 Mutex 防止重复触发
  - 按钮 enabled 状态绑定 isGenerating
  - 添加 hasAutoTriggered（使用 rememberSaveable）防止配置变化后重复自动触发
- **Acceptance Criteria Addressed**: AC-3
- **Test Requirements**:
  - `programmatic` TR-2.1: 快速连续点击发芽按钮，仅第一次有效
  - `human-judgment` TR-2.2: 生成过程中按钮显示禁用状态

## [x] Task 3: 追加笔记与导出防呆设计
- **Priority**: high
- **Depends On**: None
- **Description**: 
  - 追加笔记：添加 isAppending 状态，按钮禁用 + try/catch 异常反馈
  - 导出 Markdown：空内容检查、Android 版本权限预检、异常捕获
- **Acceptance Criteria Addressed**: AC-4, AC-5
- **Test Requirements**:
  - `programmatic` TR-3.1: 追加笔记过程中重复点击无效
  - `human-judgment` TR-3.2: 空内容时导出给出友好提示
  - `human-judgment` TR-3.3: 权限不足时导出给出友好提示

## [x] Task 4: 首次引导体验增强
- **Priority**: high
- **Depends On**: None
- **Description**: 
  - 添加左右滑动手势切换页面
  - 图标弹跳入场动画（Spring 物理引擎）
  - 标题和副标题依次淡入
  - 背景渐变层随页面切换
  - 圆形指示器缩放动画
  - 添加发芽功能介绍页（第4页）
  - 上一页按钮
- **Acceptance Criteria Addressed**: AC-6, AC-7
- **Test Requirements**:
  - `human-judgment` TR-4.1: 左右滑动能平滑切换页面
  - `human-judgment` TR-4.2: 图标有弹跳入场效果
  - `human-judgment` TR-4.3: 标题和副标题依次出现
  - `human-judgment` TR-4.4: 页面指示器有缩放动画

## [x] Task 5: 工具确认流程加固
- **Priority**: high
- **Depends On**: None
- **Description**: 
  - resolveToolConfirmation 添加 !isCompleted 判断防止重复 complete
  - ToolExecutor 默认确认回调改为拒绝，强制接入确认流程
- **Acceptance Criteria Addressed**: AC-8
- **Test Requirements**:
  - `programmatic` TR-5.1: 多次调用 resolveToolConfirmation 不会抛异常
  - `programmatic` TR-5.2: 未接入确认流程时工具调用被拒绝

## [x] Task 6: 知识图谱事务保障
- **Priority**: high
- **Depends On**: None
- **Description**: 
  - 添加 graphMutex 串行化 linkNote/removeNote/clear/rebuildFromNotes
  - rebuildFromNotes 改为原子事务（清空+写入在同一事务内）
  - doLinkNote 边写入与 trimLinks 包裹在同一事务
  - upsertEntities 支持 useTransaction 参数
- **Acceptance Criteria Addressed**: AC-9
- **Test Requirements**:
  - `programmatic` TR-6.1: rebuildFromNotes 异常时数据回滚
  - `programmatic` TR-6.2: 多协程并发调用不产生数据不一致

## [x] Task 7: 引导完成过渡动画
- **Priority**: medium
- **Depends On**: Task 4
- **Description**: 
  - 在 AppRoot 中添加引导完成淡出动画（300ms）
  - 添加加载状态避免首次启动闪烁主界面
- **Acceptance Criteria Addressed**: AC-10
- **Test Requirements**:
  - `human-judgment` TR-7.1: 点击"进入"后引导页平滑淡出
  - `human-judgment` TR-7.2: 首次启动无主界面闪烁

## [x] Task 8: 行动建议状态持久化
- **Priority**: medium
- **Depends On**: Task 1
- **Description**: 
  - 行动建议勾选状态使用 rememberSaveable 持久化
  - 配置变化或进程重建后勾选记录不丢失
- **Acceptance Criteria Addressed**: AC-3
- **Test Requirements**:
  - `human-judgment` TR-8.1: 旋转屏幕后行动建议勾选状态保持不变
