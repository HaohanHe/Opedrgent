package top.hsyscn.opedrgent.action

/**
 * 行动项（ActionItem）数据层契约。
 *
 * 定位：把"镜鉴 / 复盘 / 模型工具"产生的"要做的事"收敛到一张独立清单，与批判镜报告内嵌的
 * FollowUp 并存——FollowUp 仍服务于报告展示与复评，ActionItem 服务于跨场景的待办回看与完成闭环。
 *
 * 本包只做数据层与持久化，不包含任何 Compose 界面。
 */

/** 行动项生命周期状态。与 FollowUpStatus 不同：行动项支持"搁置（DEFERRED）"。 */
enum class ActionStatus {
    OPEN,
    DONE,
    DEFERRED;

    companion object {
        /** 容忍模型传入 open/done/deferred 等写法，未知值回退 [OPEN]。 */
        fun fromName(raw: String?): ActionStatus =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: OPEN
    }
}

/** 行动项类别。 */
enum class ActionKind {
    /** 替代说法：下次遇到类似情境时换一种表达方式。 */
    SAYING,

    /** 可执行下一步：近期能直接落地的动作。 */
    NEXT_STEP,

    /** 一般行动。 */
    GENERAL;

    companion object {
        /** 容忍模型传入 saying / next_step / general 等写法，未知值回退 [GENERAL]。 */
        fun fromName(raw: String?): ActionKind {
            val normalized = raw?.trim()?.lowercase()?.replace("-", "_").orEmpty()
            return when (normalized) {
                "saying" -> SAYING
                "next_step", "nextstep" -> NEXT_STEP
                "general" -> GENERAL
                else -> entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: GENERAL
            }
        }
    }
}

/**
 * 一条行动项。
 *
 * @param id 自增主键，新建时为 0
 * @param title 行动项一句话标题
 * @param kind 类别，默认 [ActionKind.GENERAL]
 * @param status 生命周期状态，默认 [ActionStatus.OPEN]
 * @param sourceTypeLabel 来源类型，如 录音 / 笔记 / 洞察 / 复盘
 * @param sourceTitle 来源情境标题
 * @param sourceSnippet 来源情境摘录，便于回看
 * @param sourceReflectionId 对应批判镜/榜样镜记录 id，0 表示非镜鉴来源
 * @param createdAt 创建时间戳
 * @param completedAt 完成时间戳，未完成时为 0
 */
data class ActionItem(
    val id: Long = 0,
    val title: String,
    val kind: ActionKind = ActionKind.GENERAL,
    val status: ActionStatus = ActionStatus.OPEN,
    val sourceTypeLabel: String = "",
    val sourceTitle: String = "",
    val sourceSnippet: String = "",
    val sourceReflectionId: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long = 0L,
)
