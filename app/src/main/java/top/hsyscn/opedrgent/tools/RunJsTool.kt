package top.hsyscn.opedrgent.tools

import android.content.Context
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.model.ToolStateType
import top.hsyscn.opedrgent.mcp.skills.SkillLoader
import top.hsyscn.opedrgent.mcp.skills.SkillWebViewExecutor
import top.hsyscn.opedrgent.mcp.skills.StandardSkillDefinition
import top.hsyscn.opedrgent.network.ToolResult
import top.hsyscn.opedrgent.utils.DebugLog
import org.json.JSONObject

/**
 * run_js 工具 — 对标 Google Gallery 的 JS Skill 执行机制。
 *
 * 当 LLM 决定调用某个 JS Skill 时，通过此工具在 WebView 沙箱中执行
 * 该 Skill 的 scripts/index.html，并收集 ai_edge_gallery_get_result() 回调结果。
 *
 * ## Gallery 标准调用流程
 * 1. LLM 收到用户请求，匹配到某个 Skill 的 name/description
 * 2. LLM 调用 run_js 工具，传入 script name 和 data JSON
 * 3. 本工具加载 Skill 的 HTML 到隔离 WebView
 * 4. JS 执行完毕后通过 ai_edge_gallery_get_result() 返回结果
 * 5. 结果回传给 LLM 继续对话
 *
 * @param context Android Context
 * @param skillLoader 技能加载器（查找 Skill 定义和资源）
 */
class RunJsTool(
    private val context: Context,
    private val skillLoader: SkillLoader,
) : ToolSet {

    private val webViewExecutor = SkillWebViewExecutor(context)

    override fun getTools(): Map<String, ToolBinding> = mapOf(
        "run_js" to ToolBinding(
            name = "run_js",
            description = "在 WebView 沙箱中执行 JavaScript Skill。用于运行需要自定义逻辑的技能，如计算哈希、生成二维码、查询 API 等。参数：script_name（HTML 文件名，默认 index.html）、data（JSON 字符串格式的输入参数）。",
            invoker = { toolPart, _, _, _ -> execute(toolPart) },
        ),
    )

    /**
     * 执行 JS Skill。
     *
     * 从 toolPart.state.input 解析参数：
     * - script_name: 要执行的 HTML 文件名（默认 index.html）
     * - data: 传递给 JS 的 JSON 字符串参数
     *
     * 执行流程：
     * 1. 解析输入参数，确定目标 Skill 和脚本
     * 2. 通过 SkillWebViewExecutor 在隔离 WebView 中执行
     * 3. 收集执行结果（含 result/error/image/webview）
     * 4. 构造 ToolResult 返回
     */
    private suspend fun execute(toolPart: ToolPart): ToolResult {
        val input = toolPart.state.input
        DebugLog.i("RunJsTool: 执行 JS Skill — input=${input.take(200)}")

        return try {
            // 解析 LLM 传入的参数
            val args = JSONObject(input)
            val scriptName = args.optString("script_name", "index.html")
            val dataStr = args.optString("data", "{}")

            // 从 scriptName 反推 Skill 名称（scripts 属于某个 skill 目录）
            // 格式：assets/skills/{skill-name}/scripts/{scriptName}
            // 这里我们通过 SkillLoader 查找包含该脚本的 Skill
            val skillDef = findSkillByScript(scriptName)

            if (skillDef == null) {
                return emptyResult(toolPart, "未找到包含脚本 '$scriptName' 的 Skill。请确认 Skill 已正确安装。")
            }

            // 如果 Skill 需要 Secret，获取并传递
            val secret = if (skillDef.needsSecret) {
                skillLoader.getSecret(skillDef.skillName)
            } else null

            // 构建 WebView 执行配置
            val config = SkillWebViewExecutor.ExecutionConfig(
                timeoutMs = 30_000L,
                enableNetwork = true, // 允许 JS 发起网络请求（如 fetch API）
                inputParams = buildMap {
                    put("data", dataStr)
                    if (secret != null) put("secret", secret)
                },
                enableConsoleCapture = true,
            )

            // 在 WebView 沙箱中执行
            val result = webViewExecutor.execute(skillDef, config)

            if (result.success) {
                // 解析 JS 返回的 JSON 结果
                val resultJson = org.json.JSONObject(result.output)

                val outputText = resultJson.optString("result", "")
                val errorMsg = resultJson.optString("error", "")

                // 检查是否有 image 返回
                val imageBase64 = resultJson.optJSONObject("image")?.optString("base64")

                // 检查是否有 webview 返回
                val webviewUrl = resultJson.optJSONObject("webview")?.optString("url")
                val webviewAspect = resultJson.optJSONObject("webview")?.optDouble("aspectRatio")?.toFloat()

                val responseText = buildString {
                    if (outputText.isNotBlank()) append(outputText)
                    if (errorMsg.isNotBlank()) {
                        if (isNotBlank()) append("\n\n")
                        append("[警告] JS 执行警告: $errorMsg")
                    }
                    if (imageBase64 != null) {
                        if (isNotBlank()) append("\n\n")
                        append("[图片已生成 — base64 长度: ${imageBase64.length}]")
                    }
                    if (webviewUrl != null) {
                        if (isNotBlank()) append("\n\n")
                        append("[交互视图已生成: $webviewUrl" +
                            (if (webviewAspect != null) ", 宽高比: $webviewAspect" else "") + "]")
                    }
                    if (result.consoleLogs.isNotEmpty()) {
                        append("\n\n--- 控制台日志 ---\n")
                        result.consoleLogs.forEach { append("  $it\n") }
                    }
                }

                ToolResult(
                    toolPart = toolPart.copy(
                        state = toolPart.state.copy(
                            status = ToolStateType.COMPLETED,
                            output = responseText,
                            endTime = System.currentTimeMillis(),
                        ),
                    ),
                )
            } else {
                emptyResult(toolPart, "JS 执行失败 (${result.executionMs}ms): ${result.error}")
            }
        } catch (e: Exception) {
            DebugLog.e("RunJsTool 异常: ${e.message}", e)
            emptyResult(toolPart, "JS 执行异常: ${e.message}")
        }
    }

    /**
     * 根据脚本文件名查找对应的 Skill 定义。
     *
     * 查找策略：
     * 1. 遍历所有已加载的 Skill
     * 2. 检查 localScriptsPath 是否包含该脚本
     * 3. 对于内置 Skill，检查 assets/skills/{name}/scripts/ 目录
     */
    private suspend fun findSkillByScript(scriptName: String): StandardSkillDefinition? {
        // 先从已导入/内置的所有 Skill 中查找
        val allSkills = skillLoader.loadAllSkills()
        return allSkills.find { skill ->
            // 有明确 localScriptsPath 的 Skill
            if (skill.localScriptsPath != null) return@find true

            // 内置 Skill：检查 assets 路径下是否存在 scripts 目录
            if (skill.isBuiltIn) {
                try {
                    val path = "skills/${skill.skillName}/scripts/$scriptName"
                    context.assets.open(path).use { true }
                } catch (_: Exception) {
                    false
                }
            } else false
        }
    }

    private fun emptyResult(tp: ToolPart, msg: String): ToolResult {
        return ToolResult(
            toolPart = tp.copy(
                state = tp.state.copy(
                    status = ToolStateType.ERROR,
                    error = msg,
                    endTime = System.currentTimeMillis(),
                ),
            ),
        )
    }
}
