package top.hsyscn.opedrgent.tools

import android.content.Context
import top.hsyscn.opedrgent.model.Role
import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.model.ToolStateType
import top.hsyscn.opedrgent.network.ToolResult
import top.hsyscn.opedrgent.settings.ApiConfig
import top.hsyscn.opedrgent.storage.ResearchStore

/**
 * Recall 工具 - 跨会话记忆（借鉴 Kilo Code 的 Recall 系统）
 *
 * LLM 可调用此工具来搜索和读取历史会话记录。
 * 解决移动端 Agent "无记忆"的核心体验缺陷。
 */
class RecallTool(private val context: Context) : ToolSet {

    override fun getTools(): Map<String, ToolBinding> {
        return mapOf(
            "recall" to ToolBinding(
                name = "recall",
                description = "搜索或读取历史对话记录，实现跨会话记忆",
                invoker = ::executeRecall,
            )
        )
    }

    private suspend fun executeRecall(
        tp: ToolPart,
        config: ApiConfig,
        systemPrompt: String,
        cancelled: Boolean,
    ): ToolResult {
        val mode = tp.state.input["mode"] ?: "search"

        return when (mode) {
            "search" -> executeSearch(tp)
            "read" -> executeRead(tp)
            else -> errorResult(tp, "无效模式 '$mode'，只支持 search 或 read")
        }
    }

    private fun executeSearch(tp: ToolPart): ToolResult {
        val query = tp.state.input["query"]
        val limit = (tp.state.input["limit"]?.toIntOrNull() ?: 10).coerceIn(1, 20)

        val store = ResearchStore(context)
        val allSessions = store.listSessions()

        // 搜索匹配（标题匹配）
        val matched = if (query.isNullOrBlank()) {
            allSessions.take(limit)
        } else {
            allSessions.filter { session ->
                session.title.contains(query, ignoreCase = true)
            }.take(limit)
        }

        if (matched.isEmpty()) {
            return successResult(tp, if (query.isNullOrBlank()) "没有找到历史对话记录" else "未找到包含 '$query' 的历史对话")
        }

        val result = buildString {
            appendLine("找到 ${matched.size} 个历史对话：")
            appendLine()
            for (session in matched) {
                appendLine("- ${session.title}")
                appendLine("  ID: ${session.id}")
                appendLine("  更新时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(session.updatedAt)}")
                appendLine()
            }
            appendLine("使用 recall 工具的 read 模式 + session_id 参数可以读取完整对话记录")
        }

        return successResult(tp, result.trim())
    }

    private fun executeRead(tp: ToolPart): ToolResult {
        val sessionId = tp.state.input["session_id"] ?: return errorResult(tp, "read 模式需要提供 session_id 参数")

        val store = ResearchStore(context)
        val session = store.getSession(sessionId) ?: return errorResult(tp, "未找到会话 ID: $sessionId")

        val result = buildString {
            appendLine("对话记录: ${session.title}")
            appendLine("会话 ID: ${session.id}")
            appendLine("创建时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(session.createdAt)}")
            appendLine()

            // 限制消息数量，避免塞满上下文窗口
            val messages = session.messages.takeLast(20)
            val skipped = session.messages.size - messages.size
            if (skipped > 0) {
                appendLine("（跳过 $skipped 条早期消息，显示最近 ${messages.size} 条）")
                appendLine()
            }

            for (msg in messages) {
                when (msg.role) {
                    Role.USER -> {
                        appendLine("## 用户")
                        appendLine(msg.textContent.take(500))
                        appendLine()
                    }
                    Role.ASSISTANT -> {
                        appendLine("## 助手")
                        appendLine(msg.textContent.take(1000))
                        appendLine()
                    }
                    else -> {}
                }
            }

            // 显示来源
            if (session.sources.isNotEmpty()) {
                appendLine("## 参考来源")
                for (source in session.sources) {
                    appendLine("- ${source.title}: ${source.url}")
                }
            }
        }

        return successResult(tp, result.trim())
    }

    private fun successResult(tp: ToolPart, output: String): ToolResult {
        return ToolResult(
            toolPart = tp.copy(state = tp.state.copy(
                status = ToolStateType.COMPLETED,
                output = output,
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
}
