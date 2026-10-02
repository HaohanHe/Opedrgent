package top.hsyscn.opedrgent.tools

import android.content.Context
import org.json.JSONObject
import top.hsyscn.opedrgent.action.ActionItem
import top.hsyscn.opedrgent.action.ActionKind
import top.hsyscn.opedrgent.action.ActionStatus
import top.hsyscn.opedrgent.action.ActionStore
import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.model.ToolStateType
import top.hsyscn.opedrgent.network.ToolResult
import top.hsyscn.opedrgent.network.emptyResult

/**
 * 行动项工具 — 让模型把"要做的事"直接写入行动项库（本地持久化）。
 *
 * ## 模型驱动
 * 本类只提供工具名、中文描述与 JSON Schema，由模型 tool_calls 决定是否调用；
 * 不做任何关键词命中 / 意图判定。
 *
 * ## 两个工具
 * - [TOOL_CREATE]：新建一条行动项，创建后返回新 id 与当前未完成清单；
 * - [TOOL_UPDATE]：按 id 更新行动项状态（open/done/deferred）。
 */
class ActionItemTool(private val context: Context) : ToolSet {

    private val store: ActionStore by lazy { ActionStore.getInstance(context) }

    override fun getTools(): Map<String, ToolBinding> = mapOf(
        TOOL_CREATE to ToolBinding(
            name = TOOL_CREATE,
            description = """新建一条行动项（要做的事），写入本地行动项库并返回新 id 与当前未完成清单。
适用于：对话中用户明确答应要做、或双方商定下一步行动时，由你主动记录下来，便于后续回看与完成闭环。
kind 说明：saying=替代说法（下次换一种表达方式）；next_step=可执行下一步；general=一般行动（默认）。
source* 系列可选，用于记录这条行动来自哪里（如某次录音、笔记或一次复盘），便于回看时定位情境。""",
            parameters = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "title": {
                            "type": "string",
                            "description": "行动项一句话标题，说清要做什么，必填"
                        },
                        "kind": {
                            "type": "string",
                            "enum": ["saying", "next_step", "general"],
                            "description": "行动类别：saying=替代说法；next_step=可执行下一步；general=一般行动。默认 general"
                        },
                        "sourceTypeLabel": {
                            "type": "string",
                            "description": "可选，来源类型，如 录音 / 笔记 / 洞察 / 复盘"
                        },
                        "sourceTitle": {
                            "type": "string",
                            "description": "可选，来源情境标题"
                        },
                        "sourceSnippet": {
                            "type": "string",
                            "description": "可选，来源情境摘录原文，便于回看"
                        },
                        "sourceReflectionId": {
                            "type": "integer",
                            "description": "可选，对应的批判镜/榜样镜复盘记录 id；非复盘来源不传或传 0"
                        }
                    },
                    "required": ["title"]
                }
            """),
            invoker = { tp, _, _, _ -> create(tp) },
        ),
        TOOL_UPDATE to ToolBinding(
            name = TOOL_UPDATE,
            description = """更新一条行动项的状态：把它标记为进行中（open）、已完成（done）或搁置（deferred）。
需要传入行动项 id（由 action_item_create 返回，或来自行动项清单）。更新后返回该行动项最新内容。""",
            parameters = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "id": {
                            "type": "integer",
                            "description": "要更新的行动项 id，必填"
                        },
                        "status": {
                            "type": "string",
                            "enum": ["open", "done", "deferred"],
                            "description": "目标状态：open=进行中；done=已完成；deferred=搁置。必填"
                        }
                    },
                    "required": ["id", "status"]
                }
            """),
            invoker = { tp, _, _, _ -> update(tp) },
        ),
    )

    /** 新建行动项。 */
    private suspend fun create(tp: ToolPart): ToolResult {
        val input = tp.state.input
        val title = input["title"]?.trim().orEmpty()
        if (title.isBlank()) return emptyResult(tp, "缺少必填参数 title")

        val kind = ActionKind.fromName(input["kind"])
        val item = ActionItem(
            title = title,
            kind = kind,
            sourceTypeLabel = input["sourceTypeLabel"].orEmpty(),
            sourceTitle = input["sourceTitle"].orEmpty(),
            sourceSnippet = input["sourceSnippet"].orEmpty(),
            sourceReflectionId = input["sourceReflectionId"]?.toLongOrNull() ?: 0L,
        )
        val newId = store.upsert(item)
        val openList = store.listOpen()

        val text = buildString {
            appendLine("已创建行动项（id=$newId，类别=${kind.label()}）：$title")
            appendLine("当前未完成行动项共 ${openList.size} 条：")
            openList.take(20).forEachIndexed { i, a ->
                appendLine("${i + 1}. [id=${a.id}][${a.kind.label()}] ${a.title}")
            }
        }
        return success(tp, text.trim())
    }

    /** 更新行动项状态。 */
    private suspend fun update(tp: ToolPart): ToolResult {
        val input = tp.state.input
        val id = input["id"]?.toLongOrNull() ?: return emptyResult(tp, "缺少必填参数 id，或 id 不是数字")
        val statusRaw = input["status"]?.trim().orEmpty()
        if (statusRaw.isBlank()) return emptyResult(tp, "缺少必填参数 status")
        val status = ActionStatus.fromName(statusRaw)

        store.updateStatus(id, status)
        val updated = store.listAll().firstOrNull { it.id == id }
            ?: return emptyResult(tp, "未找到 id=$id 的行动项，可能已被删除")

        val text = buildString {
            appendLine("已更新行动项 id=$id：")
            appendLine("- 标题：${updated.title}")
            appendLine("- 类别：${updated.kind.label()}")
            appendLine("- 状态：${updated.status.label()}")
            if (updated.sourceTitle.isNotBlank()) appendLine("- 来源：${updated.sourceTypeLabel.ifBlank { "未知来源" }} / ${updated.sourceTitle}")
        }
        return success(tp, text.trim())
    }

    private fun ActionKind.label(): String = when (this) {
        ActionKind.SAYING -> "替代说法"
        ActionKind.NEXT_STEP -> "下一步"
        ActionKind.GENERAL -> "一般行动"
    }

    private fun ActionStatus.label(): String = when (this) {
        ActionStatus.OPEN -> "进行中"
        ActionStatus.DONE -> "已完成"
        ActionStatus.DEFERRED -> "搁置"
    }

    private fun success(tp: ToolPart, text: String): ToolResult = ToolResult(
        toolPart = tp.copy(
            state = tp.state.copy(
                status = ToolStateType.COMPLETED,
                output = text,
                endTime = System.currentTimeMillis(),
            ),
        ),
    )

    companion object {
        private const val TOOL_CREATE = "action_item_create"
        private const val TOOL_UPDATE = "action_item_update"
    }
}
