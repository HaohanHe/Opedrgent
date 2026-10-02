package top.hsyscn.opedrgent.tools

import android.content.Context
import org.json.JSONObject
import top.hsyscn.opedrgent.llm.AvailableLocalModels
import top.hsyscn.opedrgent.llm.LocalModelUpdateChecker
import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.model.ToolStateType
import top.hsyscn.opedrgent.modelreadiness.ModelReadinessRepository
import top.hsyscn.opedrgent.modelreadiness.ReadyState
import top.hsyscn.opedrgent.network.ToolResult
import top.hsyscn.opedrgent.network.emptyResult
import java.io.File

/**
 * 本地模型管理工具 — 让模型通过 tool_calls 查询 / 切换端侧本地 LLM。
 *
 * 纯模型驱动：只提供工具名、中文描述与 JSON Schema，是否调用由 tool_calls 决定；
 * 不做任何关键词命中 / 意图判定。
 */
class LocalModelTool(context: Context) : ToolSet {

    private val repo = ModelReadinessRepository.getInstance(context.applicationContext)

    override fun getTools(): Map<String, ToolBinding> = mapOf(
        TOOL_LIST to ToolBinding(
            name = TOOL_LIST,
            description = """列出本机可用的端侧本地大模型：内置目录（名称、大小、能力）、哪些已下载、当前启用的是哪个，以及每个已下载模型占用的磁盘空间。
不修改任何状态。适合在用户询问"本地模型有哪些 / 现在用的哪个 / 占多少空间"时先查一次。""",
            parameters = JSONObject(
                """
                {
                    "type": "object",
                    "properties": {},
                    "required": []
                }
                """.trimIndent(),
            ),
            invoker = { tp, _, _, _ -> list(tp) },
        ),
        TOOL_SWITCH to ToolBinding(
            name = TOOL_SWITCH,
            description = """切换当前启用的端侧本地大模型。传入模型 id（由 local_model_list 返回）。
切换后就绪门控与本地推理随之更新；若该模型尚未下载，会返回提示需要先下载，不会自动下载。""",
            parameters = JSONObject(
                """
                {
                    "type": "object",
                    "properties": {
                        "id": {
                            "type": "string",
                            "description": "要切换启用的本地模型 id，必填（如 gemma-4-e2b-it）"
                        }
                    },
                    "required": ["id"]
                }
                """.trimIndent(),
            ),
            invoker = { tp, _, _, _ -> switchTo(tp) },
        ),
    )

    private suspend fun list(tp: ToolPart): ToolResult {
        val entries = repo.listLocalModelEntries()
        val downloadedIds = entries.filter { it.downloaded }.map { it.id }.toSet()
        val activeId = entries.firstOrNull { it.active }?.id

        val sb = StringBuilder()
        sb.appendLine("本机本地大模型目录（共 ${entries.size} 个）：")
        entries.forEach { e ->
            val info = AvailableLocalModels.findById(e.id)
            val caps = buildList {
                if (info?.supportsFunctionCalling == true) add("工具调用")
                if (info?.supportsImage == true) add("图像")
                if (info?.supportsAudio == true) add("音频")
                if (info?.supportsThinking == true) add("思考")
                if (info?.supportsSpecDec == true) add("SpecDec")
            }.joinToString("/").ifBlank { "纯文本" }
            val state = when {
                e.active && e.downloaded -> "启用中(已就绪)"
                e.active -> "启用中(未下载)"
                e.downloaded -> "已下载"
                else -> "未下载"
            }
            val sizeOnDiskMb = e.filePath?.let { File(it).length() }?.div(1024 * 1024)
            sb.appendLine("- [${e.id}] ${e.displayName}")
            sb.appendLine("    状态：$state | 标称大小：${e.sizeMb}MB | 能力：$caps")
            if (e.downloaded) {
                val integrity = if (e.verified) "已通过可信哈希" else "仅体积校验(无上游哈希)"
                sb.appendLine("    已用磁盘：${sizeOnDiskMb ?: 0}MB | 完整性：$integrity")
            }
        }
        sb.appendLine("当前启用：${activeId ?: "无"}")
        sb.appendLine("已下载：${if (downloadedIds.isEmpty()) "无" else downloadedIds.joinToString("、")}")

        // 纯本地更新提示（不触网、不强制、不自动下载）
        val hints = LocalModelUpdateChecker.findUpdates(downloadedIds)
        if (hints.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("本地更新提示（仅提示，不自动下载）：")
            hints.forEach { sb.appendLine("- ${it.message}") }
        }
        return success(tp, sb.toString().trim())
    }

    private suspend fun switchTo(tp: ToolPart): ToolResult {
        val id = tp.state.input["id"]?.trim().orEmpty()
        if (id.isBlank()) return emptyResult(tp, "缺少必填参数 id")
        if (AvailableLocalModels.findById(id) == null) {
            return emptyResult(tp, "未知模型 id：$id。请先调用 local_model_list 查看可用 id。")
        }
        val changed = repo.selectLlm(id)
        if (!changed) return emptyResult(tp, "切换失败：无法识别模型 id $id")

        val snap = repo.snapshot.value.llm
        val text = buildString {
            appendLine("已切换启用本地模型为：$id（${snap.displayName}）")
            when (snap.state) {
                ReadyState.READY -> appendLine("当前状态：已就绪，可直接用于本地推理。")
                ReadyState.DOWNLOADING -> appendLine("当前状态：正在下载中。")
                ReadyState.FAILED -> appendLine("当前状态：下载失败：${snap.error ?: "未知原因"}")
                ReadyState.NOT_PRESENT -> appendLine("当前状态：该模型尚未下载。如需使用请先在本地模型管理中开始下载（本工具不会自动下载）。")
            }
        }
        return success(tp, text.trim())
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
        private const val TOOL_LIST = "local_model_list"
        private const val TOOL_SWITCH = "local_model_switch"
    }
}
