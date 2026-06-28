# 聊天历史分页加载 Spec

## Why

当前 `SessionScreen` 一次性持有并渲染会话的全部消息历史。当对话轮数增长时，LazyColumn 的组合范围、动画计算和内存占用都会线性增加，导致滑动卡顿、响应变慢。本 spec 引入按轮（round）分页加载机制：默认只展示最近 10 轮，用户向上滑动到顶部时按区块加载更早的 10 轮，从而降低初始组合压力和内存占用。

## What Changes

- **消息模型扩展**：为 `ChatMessage` 增加轮次索引或按轮次分组能力，便于按 round 分页。
- **数据层分页查询**：在会话数据源（Repository/DAO）中新增按轮次范围查询消息的方法。
- **MainViewModel 状态管理**：为当前会话增加 `visibleRounds`、`hasMoreOlderRounds`、`isLoadingOlderRounds` 等分页状态。
- **SessionScreen UI 改造**：保留 `LazyColumn + key`，但只渲染已加载轮次的消息；到达列表顶部时触发加载更早轮次。
- **加载交互**：顶部显示加载指示器或骨架屏，加载完成后新消息区块自然插入顶部，保持滚动位置不跳变。

## Impact

- **Affected specs**: optimize-core-performance, redesign-chat-ui
- **Affected code**:
  - `model/ChatMessage.kt`
  - `model/Models.kt`
  - `storage/HippocampusSessionStore.kt`
  - `storage/MemoryStore.kt`（如会话存储在此）
  - `note/NoteRepository.kt`
  - `ui/MainViewModel.kt`
  - `ui/SessionScreen.kt`
  - `ui/components/AiBubble.kt`
  - `ui/components/UserBubble.kt`

## ADDED Requirements

### Requirement: 按轮次分页加载
The system SHALL display only the most recent 10 rounds of a session initially and load older rounds in chunks when the user scrolls up.

#### Scenario: 初始加载
- **WHEN** 用户打开一个包含 50 轮对话的会话
- **THEN** 默认只渲染最近 10 轮（约 20 条消息）

#### Scenario: 向上滑动加载更早
- **WHEN** 用户滑动到列表顶部
- **THEN** 系统加载并插入更早的 10 轮消息，保持当前滚动位置不跳变

### Requirement: 轮次定义
The system SHALL define one "round" as a single user turn and its corresponding assistant response(s).

#### Scenario: 单轮结构
- **GIVEN** 用户发送一条消息，AI 回复一条文本 + 一条错误信息
- **THEN** 这三条消息属于同一轮

### Requirement: 加载状态反馈
The system SHALL show a loading indicator at the top of the message list while older rounds are being fetched.

#### Scenario: 加载中
- **WHEN** 用户滑动到顶部且系统正在查询更早轮次
- **THEN** 顶部显示一个小的 CircularProgressIndicator 或骨架项

### Requirement: 无更多历史提示
The system SHALL clearly indicate when all historical rounds have been loaded.

#### Scenario: 到达最早消息
- **WHEN** 用户已经加载到会话的第一轮
- **THEN** 顶部显示“已加载全部历史”或类似提示，不再触发加载

## MODIFIED Requirements

### Requirement: SessionScreen 消息列表
The existing `SessionScreen` message list SHALL continue to use `LazyColumn` with stable keys, but only render messages from currently loaded rounds instead of the entire session history.

### Requirement: MainViewModel 会话状态
`MainViewModel` SHALL track pagination state per session (visible rounds, total rounds, loading flag) alongside existing streaming and question state.

## REMOVED Requirements

None.
