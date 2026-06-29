package top.hsyscn.opedrgent.transaction

import top.hsyscn.opedrgent.agent.AgentStorage
import top.hsyscn.opedrgent.model.ChatMessage

/**
 * 单次工具调用的可回放记录（事务回滚专用，与 model.ToolCallRecord 区分）。
 *
 * @param toolName 工具名称（如 "run_calendar"）
 * @param input 原工具入参（键值化），补偿提取器据此判断是否可补偿
 * @param output 原工具输出文本（补偿提取器可从中解析副作用产物 ID，如 event_id）
 * @param toolUseId 工具调用唯一标识（对应 LLM tool_call id）
 * @param succeeded 原调用是否成功（失败的调用无需补偿）
 * @param timestamp 调用时间戳（ms）
 */
data class ToolCallRecord(
    val toolName: String,
    val input: Map<String, Any>,
    val output: String?,
    val toolUseId: String,
    val succeeded: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Agent 执行事务检查点（对标 Koog checkpoint + tombstone）。
 *
 * 检查点在事务开始时创建，记录消息历史与共享存储快照；
 * 事务正常终结后打上 [tombstone]（墓碑），墓碑后不可再回滚。
 *
 * @param checkpointId 检查点唯一标识
 * @param agentName 所属 Agent 名称
 * @param messageHistorySnapshot 检查点创建时的消息历史快照（用于回滚时计算 diff）
 * @param storageSnapshot 共享存储快照（AgentStorage.copy() 的产物）
 * @param toolCalls 事务期间已执行的工具调用记录（LIFO 补偿依据）
 * @param createdAt 创建时间戳（ms）
 * @param tombstone 是否已终结（墓碑标记），true 表示事务已提交/中止，禁止回滚
 */
data class AgentCheckpointData(
    val checkpointId: String,
    val agentName: String,
    val messageHistorySnapshot: List<ChatMessage>,
    val storageSnapshot: AgentStorage,
    val toolCalls: List<ToolCallRecord> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    var tombstone: Boolean = false,
)
