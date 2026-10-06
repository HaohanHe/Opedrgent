package top.hsyscn.opedrgent.tools

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.json.JSONObject
import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.model.ToolStateType
import top.hsyscn.opedrgent.network.ToolResult
import top.hsyscn.opedrgent.settings.ApiConfig

/**
 * TodoWrite 工具 - 结构化任务跟踪（借鉴 Kilo Code 的 Todo 系统）
 *
 * LLM 可调用此工具来创建和管理结构化任务列表。
 * 适用于 3 步以上的复杂任务，让 LLM 主动拆解任务、报告进度。
 *
 * 移动端适配：限制最多 10 个活跃任务，完成的自动折叠。
 */
class TodoWriteTool(private val context: Context) : ToolSet {

    data class TodoItem(
        val content: String,
        val status: String = "pending",
        val priority: String = "medium",
    )

    private val gson = Gson()

    override fun getTools(): Map<String, ToolBinding> {
        return mapOf(
            "todowrite" to ToolBinding(
                name = "todowrite",
                description = "创建或更新任务列表来跟踪多步骤工作进度。todos 为完整任务数组，每次调用会整体替换现有任务列表。",
                parameters = JSONObject("""
                    {
                        "type": "object",
                        "properties": {
                            "todos": {
                                "type": "array",
                                "description": "完整的任务列表（整体覆盖现有任务，最多 10 项）。每项含 content/status/priority。",
                                "items": {
                                    "type": "object",
                                    "properties": {
                                        "content": {
                                            "type": "string",
                                            "description": "任务内容，必填且不可为空"
                                        },
                                        "status": {
                                            "type": "string",
                                            "enum": ["pending", "in_progress", "completed", "cancelled"],
                                            "description": "任务状态，默认 pending"
                                        },
                                        "priority": {
                                            "type": "string",
                                            "enum": ["high", "medium", "low"],
                                            "description": "优先级，默认 medium"
                                        }
                                    },
                                    "required": ["content"]
                                }
                            }
                        },
                        "required": ["todos"]
                    }
                """),
                invoker = ::executeTodoWrite,
            )
        )
    }

    private suspend fun executeTodoWrite(
        tp: ToolPart,
        config: ApiConfig,
        systemPrompt: String,
        cancelled: Boolean,
    ): ToolResult {
        val todosJson = tp.state.input["todos"] ?: return errorResult(tp, "缺少 todos 参数")

        val todos: List<TodoItem> = try {
            val listType = object : TypeToken<List<Map<String, String>>>() {}.type
            val rawList: List<Map<String, String>> = gson.fromJson(todosJson, listType)
            // 过滤掉 content 为空白的项，避免保存空任务。
            rawList.mapNotNull { map ->
                val content = map["content"]?.trim().orEmpty()
                if (content.isBlank()) return@mapNotNull null
                TodoItem(
                    content = content,
                    status = map["status"] ?: "pending",
                    priority = map["priority"] ?: "medium",
                )
            }
        } catch (e: Exception) {
            return errorResult(tp, "无法解析 todos JSON: ${e.message}")
        }

        // 空列表不得静默覆盖已持久化任务：直接报错提示，避免误清空。
        if (todos.isEmpty()) {
            return errorResult(
                tp,
                "todos 为空：未提供任何含有效 content 的任务。为避免误清空现有任务列表，本次未保存。如需结束任务，请将其 status 标记为 completed/cancelled。",
            )
        }

        if (todos.size > 10) {
            return errorResult(tp, "任务列表最多支持 10 个任务，当前有 ${todos.size} 个")
        }

        val validStatuses = setOf("pending", "in_progress", "completed", "cancelled")
        val invalidItem = todos.find { it.status !in validStatuses }
        if (invalidItem != null) {
            return errorResult(tp, "无效状态 '${invalidItem.status}'，只支持 pending/in_progress/completed/cancelled")
        }

        val pending = todos.count { it.status == "pending" }
        val inProgress = todos.count { it.status == "in_progress" }
        val completed = todos.count { it.status == "completed" }
        val total = todos.size

        val taskView = buildString {
            appendLine("任务进度 ($completed/$total 已完成)")
            appendLine()
            for ((index, item) in todos.withIndex()) {
                val icon = when (item.status) {
                    "completed" -> "[完成]"
                    "in_progress" -> "[进行中]"
                    "cancelled" -> "[取消]"
                    else -> "[待办]"
                }
                val priorityMark = when (item.priority) {
                    "high" -> "!"
                    "low" -> "."
                    else -> ""
                }
                appendLine("$icon ${index + 1}. $priorityMark ${item.content}")
            }
            if (pending == 0 && inProgress == 0 && completed > 0) {
                appendLine()
                appendLine("所有任务已完成!")
            }
        }

        saveTodos(todos)

        return ToolResult(
            toolPart = tp.copy(state = tp.state.copy(
                status = ToolStateType.COMPLETED,
                output = taskView.trim(),
                endTime = System.currentTimeMillis(),
            ))
        )
    }

    private fun errorResult(tp: ToolPart, message: String): ToolResult {
        return ToolResult(
            toolPart = tp.copy(state = tp.state.copy(
                status = ToolStateType.ERROR,
                error = message,
                endTime = System.currentTimeMillis(),
            ))
        )
    }

    private fun saveTodos(todos: List<TodoItem>) {
        val prefs = context.getSharedPreferences("opedrgent_todos", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("todos_json", gson.toJson(todos))
            .apply()
    }

    companion object {
        fun loadTodos(context: Context): List<TodoItem> {
            val prefs = context.getSharedPreferences("opedrgent_todos", Context.MODE_PRIVATE)
            val json = prefs.getString("todos_json", null) ?: return emptyList()
            return try {
                val listType = object : TypeToken<List<Map<String, String>>>() {}.type
                val rawList: List<Map<String, String>> = Gson().fromJson(json, listType)
                rawList.map { map ->
                    TodoItem(
                        content = map["content"] ?: "",
                        status = map["status"] ?: "pending",
                        priority = map["priority"] ?: "medium",
                    )
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
