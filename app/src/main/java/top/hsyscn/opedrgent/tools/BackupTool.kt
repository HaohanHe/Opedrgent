package top.hsyscn.opedrgent.tools

import android.content.Context
import org.json.JSONObject
import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.model.ToolStateType
import top.hsyscn.opedrgent.network.ToolResult
import top.hsyscn.opedrgent.network.emptyResult
import top.hsyscn.opedrgent.storage.backup.LocalBackupManager
import java.io.File

/**
 * 本地备份/恢复工具 — 全离线，不触网。
 *
 * 模型驱动：只提供工具名、中文描述与 JSON Schema，由模型 tool_calls 决定调用；
 * 不做任何关键词命中 / 意图判定。
 *
 * - backup_create ：把全部本地数据库 + 明文设置（剔除敏感键）打成 zip 存到 filesDir/backups。
 * - backup_restore：从指定归档（默认取最新一份）恢复数据库与设置，成功后需重启应用。
 */
class BackupTool(private val context: Context) : ToolSet {

    private val manager: LocalBackupManager by lazy { LocalBackupManager.getInstance(context) }

    override fun getTools(): Map<String, ToolBinding> = mapOf(
        TOOL_CREATE to ToolBinding(
            name = TOOL_CREATE,
            description = """创建一份本地完整备份（全离线、不上传）。
包含：全部本地数据库（笔记/文件夹/知识图谱/海马索引/修炼/行动项/知识库/发芽报告）与应用设置（已自动剔除 key/token/secret/password/credential 等敏感键，绝不包含加密凭据）。
includeModels=true 时额外打包已下载的本地模型文件（体积大、较慢），默认 false 不打包。
返回归档路径、大小与所含组件。""",
            parameters = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "includeModels": {
                            "type": "boolean",
                            "description": "是否同时打包已下载的本地模型文件。默认 false（模型可重新下载，体积大）。"
                        }
                    }
                }
            """),
            invoker = { tp, _, _, _ -> create(tp) },
        ),
        TOOL_RESTORE to ToolBinding(
            name = TOOL_RESTORE,
            description = """从一份本地备份归档恢复数据库与设置（全离线）。
会先自动把当前数据快照到回滚区，校验归档完整性（版本/大小/SHA-256），全部通过后才覆盖；任一步失败自动回滚。
成功后需要重启应用才能生效。
archivePath 可选：不传则默认取 filesDir/backups 下最新的一份归档。
allowDowngrade 可选：备份来自旧版本 app 时默认拒绝，置 true 强制恢复。""",
            parameters = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "archivePath": {
                            "type": "string",
                            "description": "备份归档的绝对路径。可选；不传则自动使用 backups 目录下最新一份 .zip。"
                        },
                        "allowDowngrade": {
                            "type": "boolean",
                            "description": "是否允许恢复来自旧版本 app 的备份。默认 false。"
                        }
                    }
                }
            """),
            invoker = { tp, _, _, _ -> restore(tp) },
        ),
    )

    private suspend fun create(tp: ToolPart): ToolResult {
        val includeModels = tp.state.input["includeModels"].toBooleanStrictLenient()
        val r = manager.createLocal(includeModels)
        if (!r.ok) return emptyResult(tp, "备份失败：${r.message}")
        val text = buildString {
            appendLine("本地备份已创建：")
            appendLine("- 归档路径：${r.archivePath}")
            appendLine("- 大小：${r.sizeBytes / 1024} KB")
            appendLine("- 组件：${r.components.joinToString(", ")}")
        }
        return success(tp, text.trim())
    }

    private suspend fun restore(tp: ToolPart): ToolResult {
        val input = tp.state.input
        val allowDowngrade = input["allowDowngrade"].toBooleanStrictLenient()

        val archive = resolveArchive(input["archivePath"])
            ?: return emptyResult(tp, "未找到可用的备份归档：请通过 archivePath 指定路径，或先调用 backup_create 创建备份。")

        val r = archive.inputStream().use { stream ->
            manager.restoreFrom(stream, allowDowngrade = allowDowngrade)
        }

        if (!r.ok) return emptyResult(tp, "恢复失败[${r.code}]：${r.message}")
        val text = buildString {
            appendLine("恢复成功：")
            appendLine("- 数据库：${r.restoredDatabases.joinToString(", ").ifBlank { "（无）" }}")
            appendLine("- 恢复设置项：${r.restoredPrefKeys} 项")
            appendLine("- 是否需要重启：${if (r.requiresRestart) "是（请重启应用后生效）" else "否"}")
            if (r.message.isNotBlank()) appendLine("- 说明：${r.message}")
        }
        return success(tp, text.trim())
    }

    private fun resolveArchive(explicit: String?): File? {
        if (!explicit.isNullOrBlank()) {
            val f = File(explicit)
            if (f.exists() && f.isFile) return f
        }
        val dir = File(context.filesDir, "backups")
        return dir.listFiles { it -> it.isFile && it.name.endsWith(".zip") }
            ?.maxByOrNull { it.lastModified() }
    }

    /** 宽松解析布尔：接受 true/1/yes，其余视为 false。 */
    private fun String?.toBooleanStrictLenient(): Boolean =
        this?.trim()?.equals("true", ignoreCase = true) == true ||
            this?.trim() == "1" ||
            this?.trim()?.equals("yes", ignoreCase = true) == true

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
        private const val TOOL_CREATE = "backup_create"
        private const val TOOL_RESTORE = "backup_restore"
    }
}
