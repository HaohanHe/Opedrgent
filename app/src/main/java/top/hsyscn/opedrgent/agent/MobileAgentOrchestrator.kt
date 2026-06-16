package top.hsyscn.opedrgent.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.hsyscn.opedrgent.settings.ApiConfig
import top.hsyscn.opedrgent.tools.StepMobileAgentTool
import top.hsyscn.opedrgent.utils.DebugLog

/**
 * Mobile Agent 自动化编排器 — 截图→分析→执行的完整闭环。
 *
 * ## 工作流程
 * ```
 * ┌──────────┐     ┌──────────────────┐     ┌──────────────┐
 * │  截屏     │ ──> │  Mobile Agent    │ ──> │  动作执行器   │
 * │ Capture  │     │  AI 分析         │     │  Executor    │
 * └──────────┘     │ (step-3.7-flash- │     └──────┬───────┘
 *       ↑          │  mobile-agent)   │            │
 *       │          └──────────────────┘            │ 截屏确认
 *       └────────────────── 返回结果 <─────────────┘
 *                         ↓
 *                   [任务完成 / 继续下一步]
 * ```
 *
 * ## 使用示例
 * ```kotlin
 * val orchestrator = MobileAgentOrchestrator(context)
 *
 * // 1. 启动屏幕捕获
 * orchestrator.startCapture(resultCode, data)
 *
 * // 2. 执行任务
 * val result = orchestrator.executeTask(
 *     apiKey = "...",
 *     task = "打开设置并关闭蓝牙",
 *     maxRounds = 5,
 * )
 *
 * // 3. 查看结果
 * println(result.fullReport)
 *
 * // 4. 清理
 * orchestrator.stop()
 * ```
 */
class MobileAgentOrchestrator(
    private val context: Context,
) {

    companion object {
        private const val TAG = "MobileAgentOrchestrator"

        /** 默认最大轮次 (防止无限循环) */
        const val DEFAULT_MAX_ROUNDS = 5

        /** 单轮最大步数 */
        const val MAX_STEPS_PER_ROUND = 8
    }

    private val screenCapture = ScreenCaptureManager(context)
    private val actionExecutor = ActionExecutor(context)
    private val agentTool = StepMobileAgentTool(context)

    /** 是否已初始化屏幕捕获 */
    var isReady: Boolean = false; private set

    /** 当前任务状态 */
    data class TaskResult(
        val success: Boolean,
        val totalRounds: Int,
        val totalSteps: Int,
        val successfulSteps: Int,
        val finalScreenDescription: String? = null,
        val actionHistory: List<String> = emptyList(),
        val fullReport: String = "",
        val finalScreenshotBase64: String? = null,
    )

    /**
     * 初始化屏幕捕获。
     *
     * 必须在 executeTask() 之前调用。
     * 需要从 Activity.onActivityResult 获取授权数据。
     */
    fun startCapture(resultCode: Int, data: android.content.Intent?): Boolean {
        isReady = screenCapture.startProjection(resultCode, data)
        return isReady
    }

    /**
     * 停止并释放所有资源。
     */
    fun stop() {
        screenCapture.stopProjection()
        actionExecutor.cancelExecution()
        isReady = false
        DebugLog.i(TAG, "编排器已停止")
    }

    /**
     * 执行完整的自动化任务。
     *
     * @param apiKey 阶跃 API Key
     * @param 任务描述
     * @param maxRounds 最大循环轮次 (默认 5)
     * @param onRoundComplete 每轮完成回调
     * @return 任务执行结果
     */
    suspend fun executeTask(
        apiKey: String,
        task: String,
        maxRounds: Int = DEFAULT_MAX_ROUNDS,
        currentApp: String? = null,
        onRoundComplete: ((Int, TaskResult) -> Unit)? = null,
    ): TaskResult = withContext(Dispatchers.IO) {

        if (!isReady) return@withContext TaskResult(
            success = false,
            totalRounds = 0,
            totalSteps = 0,
            successfulSteps = 0,
            fullReport = "错误: 屏幕捕获未初始化，请先调用 startCapture()",
        )

        val config = ApiConfig(
            baseUrl = "https://api.stepfun.com/v1",
            apiKey = apiKey,
            model = StepMobileAgentTool.MODEL_MOBILE_AGENT,
        )
        val history = mutableListOf<String>()
        var totalStepsExecuted = 0
        var successfulSteps = 0
        var lastScreenDesc: String? = null
        val report = StringBuilder()

        report.appendLine("=== Mobile Agent 任务报告 ===")
        report.appendLine("任务: $task")
        report.appendLine("开始时间: ${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())}")
        report.appendLine()

        for (round in 1..maxRounds) {
            DebugLog.i(TAG, "--- 第 $round 轮 ---")

            // Step 1: 截取当前屏幕
            val screenshot = screenCapture.captureScreenshot()
            if (screenshot == null) {
                report.appendLine("[第${round}轮] 截图失败，终止任务")
                break
            }

            // Step 2: AI 分析屏幕并生成动作计划
            val previousActions = if (history.isNotEmpty()) history.joinToString("; ") else null
            val agentInput = JSONObject().apply {
                put("task", task)
                put("screenshot_base64", screenshot)
                if (currentApp != null) put("current_app", currentApp)
                if (previousActions != null) put("previous_actions", previousActions)
            }.toString()

            // 直接调用 agent 的内部分析逻辑
            val toolPart = createMockToolPart(agentInput)
            val analysisResult = agentTool.getTools()["step_mobile_agent"]?.invoker?.invoke(
                toolPart, config, "", false
            ) ?: run {
                report.appendLine("[第${round}轮] Agent 分析失败")
                break
            }

            val output = analysisResult.toolPart.state.output
            val isError = analysisResult.toolPart.state.status == top.hsyscn.opedrgent.model.ToolStateType.ERROR

            if (isError || output.isNullOrBlank()) {
                report.appendLine("[第${round}轮] Agent 返回错误: ${analysisResult.toolPart.state.error}")
                break
            }

            // 从输出中提取动作计划
            val actions = extractActionPlanFromOutput(output)

            // 检查是否已完成任务
            if (actions.isEmpty() || isTaskComplete(output)) {
                report.appendLine("[第${round}轮] Agent 判定任务完成或无需进一步操作")
                lastScreenDesc = extractScreenDescription(output)
                break
            }

            report.appendLine("--- 第 ${round} 轮: 计划执行 ${actions.size} 步 ---")

            // Step 3: 执行动作序列
            val execResult = actionExecutor.executePlan(
                actions = actions.take(MAX_STEPS_PER_ROUND),
                screenCapture = screenCapture,
            )

            totalStepsExecuted += execResult.executedSteps.size
            successfulSteps += execResult.executedSteps.count { it.success }

            // 记录本轮操作历史
            execResult.executedSteps.forEach { step ->
                val entry = "R${round}S${step.step}:${step.action}(${step.detail})"
                history.add(entry)
                report.appendLine("  ${if (step.success) "[OK]" else "[FAIL]"} $entry")
            }

            // 如果全部成功且 Agent 表示完成，结束
            if (execResult.success && isTaskComplete(output)) {
                lastScreenDesc = extractScreenDescription(output)
                break
            }

            // 回调通知
            val roundResult = TaskResult(
                success = execResult.success,
                totalRounds = round,
                totalSteps = totalStepsExecuted,
                successfulSteps = successfulSteps,
                finalScreenDescription = lastScreenDesc,
                actionHistory = history.toList(),
            )
            onRoundComplete?.invoke(round, roundResult)
        }

        // 最终截图
        val finalScreenshot = try { screenCapture.captureScreenshot() } catch (_: Exception) { null }

        // 构建最终报告
        report.appendLine()
        report.appendLine("=== 执行摘要 ===")
        report.appendLine("总轮次: ${history.fold(0) { acc, s -> maxOf(acc, s.substringAfter("R").substringBefore(":").toIntOrNull() ?: 0) }}")
        report.appendLine("总步骤: $totalStepsExecuted")
        report.appendLine("成功步骤: $successfulSteps")
        report.appendLine("成功率: ${if (totalStepsExecuted > 0) "${((successfulSteps.toDouble() / totalStepsExecuted) * 100).toInt()}%" else "N/A"}")
        if (lastScreenDesc != null) {
            report.appendLine("最终界面: $lastScreenDesc")
        }

        TaskResult(
            success = successfulSteps > 0 && (successfulSteps == totalStepsExecuted || totalStepsExecuted > 0),
            totalRounds = history.fold(0) { acc, s -> maxOf(acc, s.substringAfter("R").substringBefore(":").toIntOrNull() ?: 0) },
            totalSteps = totalStepsExecuted,
            successfulSteps = successfulSteps,
            finalScreenDescription = lastScreenDesc,
            actionHistory = history.toList(),
            fullReport = report.toString(),
            finalScreenshotBase64 = finalScreenshot,
        )
    }

    // ---- 内部辅助方法 ----

    /**
     * 从 Agent 输出文本中提取动作计划 JSON。
     */
    private fun extractActionPlanFromOutput(output: String): List<ActionExecutor.AgentAction> {
        // 尝试从输出中提取 JSON 数组
        val jsonStart = output.indexOf('[')
        val jsonEnd = output.lastIndexOf(']')
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            val jsonStr = output.substring(jsonStart, jsonEnd + 1)
            return actionExecutor.parseActionPlan(jsonStr)
        }

        // 尝试提取整个 JSON 对象中的 action_plan 字段
        val objStart = output.indexOf('{')
        val objEnd = output.lastIndexOf('}')
        if (objStart >= 0 && objEnd > objStart) {
            return try {
                val obj = JSONObject(output.substring(objStart, objEnd + 1))
                val jsonArray = obj.optJSONArray("action_plan")
                if (jsonArray != null) {
                    actionExecutor.parseActionPlan(jsonArray.toString())
                } else {
                    val planStr = obj.optString("action_plan", "")
                    if (planStr.startsWith("[")) {
                        actionExecutor.parseActionPlan(planStr)
                    } else emptyList()
                }
            } catch (_: Exception) { emptyList()
            }
        }
        return emptyList()
    }

    /**
     * 从输出中提取界面描述。
     */
    private fun extractScreenDescription(output: String): String? {
        return try {
            val objStart = output.indexOf('{')
            val objEnd = output.lastIndexOf('}')
            if (objStart >= 0 && objEnd > objStart) {
                val obj = JSONObject(output.substring(objStart, objEnd + 1))
                obj.optString("screen_description", "").ifBlank { null }
            } else null
        } catch (_: Exception) { null }
    }

    /**
     * 判断 Agent 是否认为任务已完成。
     */
    private fun isTaskComplete(output: String): Boolean {
        val lower = output.lowercase()
        return lower.contains("任务完成") ||
               lower.contains("已完成") ||
               lower.contains("操作完成") ||
               lower.contains("success") ||
               lower.contains("done") ||
               lower.contains("action_plan 为空") ||
               lower.contains("no further action")
    }

    /**
     * 创建用于调用 Tool 的模拟 ToolPart。
     */
    private fun createMockToolPart(input: String): top.hsyscn.opedrgent.model.ToolPart {
        return top.hsyscn.opedrgent.model.ToolPart(
            id = "mobile-agent-${System.currentTimeMillis()}",
            tool = "step_mobile_agent",
            state = top.hsyscn.opedrgent.model.ToolState(
                status = top.hsyscn.opedrgent.model.ToolStateType.RUNNING,
                input = mapOf("input" to input),
                startTime = System.currentTimeMillis(),
            ),
        )
    }
}
