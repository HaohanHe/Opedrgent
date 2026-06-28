# Checklist

- [x] `ChatMessage` 模型支持轮次索引或等价的 round 分组能力
- [x] 数据源提供按 round 范围查询消息的方法
- [x] 保存新消息时正确计算 roundIndex
- [x] MainViewModel 维护当前会话的分页状态（visibleRounds、hasMoreOlderRounds、isLoadingOlderRounds）
- [x] 打开会话时默认只加载最近 10 轮
- [x] 向上滑动到顶部触发加载更早 10 轮
- [x] 加载过程中顶部显示加载指示器
- [x] 加载完成后滚动位置不跳变
- [x] 所有历史加载完毕后顶部显示“已加载全部历史”提示
- [x] 切换会话时重置分页状态
- [x] 新消息发送后不影响已加载历史
- [x] `./gradlew assembleDebug` 构建成功
