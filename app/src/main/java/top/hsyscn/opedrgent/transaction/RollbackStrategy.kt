package top.hsyscn.opedrgent.transaction

/**
 * 回滚策略（对标 Koog 事务回滚语义）。
 *
 * - [DEFAULT]: 尽力而为（best-effort），补偿工具失败不中断整体回滚，仅记录警告。
 * - [MESSAGE_HISTORY_ONLY]: 仅回滚消息历史，不执行任何补偿工具（适合纯只读工具链）。
 * - [STRICT]: 严格模式，任何补偿工具失败都抛出异常，确保强一致或显式失败。
 */
enum class RollbackStrategy {
    DEFAULT,
    MESSAGE_HISTORY_ONLY,
    STRICT,
}
