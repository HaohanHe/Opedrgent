package top.hsyscn.opedrgent.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.storage.AgentActionHistoryStore
import top.hsyscn.opedrgent.tools.StepMobileAgentTool
import top.hsyscn.opedrgent.utils.DebugLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Mobile Agent 自动化编排器 -- 截图→分析→执行的完整闭环。
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
 * ## 与 [StepMobileAgentTool] 的关系
 * - [StepMobileAgentTool] 是单次分析工具（截图 → 动作计划），由 LLM 通过 tool call 调用
 * - 本编排器是**闭环自动化**：循环调用分析 + 执行，直到任务完成或达到最大轮次
 * - 编排器直接调用 [StepMobileAgentTool.analyzeTask] 获取结构化 [StepMobileAgentTool.AgentResult]，
 *   不再通过 Tool 接口解析格式化文本，避免 JSON 提取错误
 *
 * ## 历史持久化
 * 每次任务执行完成后自动写入 [AgentActionHistoryStore]，可用于：
 * - 审计与调试
 * - 保存为任务模板供后续回放
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
    private val historyStore = AgentActionHistoryStore(context)

    /** 是否已初始化屏幕捕获 */
    var isReady: Boolean = false; private set

    /** 当前任务状态 */
    data class TaskResult(
        val success: Boolean,
        val totalRounds: Int,
        val totalSteps: Int,
        val successfulSteps: Int,
        val finalScreenDescription: String? = null,
        val actionHistory: List<ActionRecord> = emptyList(),
        val fullReport: String = "",
        val finalScreenshotBase64: String? = null,
        val recordId: String? = null,
    )

    /** 单步动作记录（用于持久化）。 */
    data class ActionRecord(
        val round: Int,
        val step: Int,
        val action: String,
        val target: String,
        val detail: String,
        val success: Boolean,
        val message: String,
        val timestamp: Long,
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
     * 检查无障碍服务是否已启用（执行动作的前置条件）。
     */
    fun isAccessibilityEnabled(): Boolean = actionExecutor.isAccessibilityEnabled()

    /**
     * 获取 UI 节点树（若 AccessibilityService 已启用）。
     * 供 LLM 在分析截图时获得更精确的元素信息。
     */
    fun dumpUiTree(): String? {
        val service = OpedrgentAccessibilityService.instance ?: return null
        return service.dumpUiTree()
    }

    /**
     * 执行完整的自动化任务。
     *
     * @param apiKey 阶跃 API Key
     * @param task 任务描述
     * @param maxRounds 最大循环轮次 (默认 5)
     * @param currentApp 当前应用名（可选）
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

        val history = mutableListOf<ActionRecord>()
        var totalStepsExecuted = 0
        var successfulSteps = 0
        var lastScreenDesc: String? = null
        var actualRounds = 0
        val report = StringBuilder()
        val recordId = "task-${System.currentTimeMillis()}"

        report.appendLine("=== Mobile Agent 任务报告 ===")
        report.appendLine("任务: $task")
        report.appendLine("开始时间: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}")
        report.appendLine()

        for (round in 1..maxRounds) {
            actualRounds = round
            DebugLog.i(TAG, "--- 第 $round 轮 ---")

            // Step 1: 截取当前屏幕
            val screenshot = try {
                screenCapture.captureScreenshot()
            } catch (e: Exception) {
                DebugLog.e(TAG, "截图异常: ${e.message}", e)
                null
            }
            if (screenshot == null) {
                report.appendLine("[第${round}轮] 截图失败，终止任务")
                break
            }

            // Step 2: AI 分析屏幕并生成动作计划（直接调用 analyzeTask 获取结构化结果）
            val previousActions = if (history.isNotEmpty()) {
                history.joinToString("; ") { "R${it.round}S${it.step}:${it.action}" }
            } else null

            val analysisResult = try {
                agentTool.analyzeTask(
                    apiKey = apiKey,
                    task = task,
                    screenshotBase64 = screenshot,
                    currentApp = currentApp ?: OpedrgentAccessibilityService.instance?.getForegroundPackage(),
                    previousActions = previousActions,
                )
            } catch (e: Exception) {
                DebugLog.e(TAG, "Agent 分析异常: ${e.message}", e)
                report.appendLine("[第${round}轮] Agent 分析异常: ${e.message}")
                break
            }

            if (!analysisResult.success) {
                report.appendLine("[第${round}轮] Agent 分析失败: ${analysisResult.errorMessage}")
                break
            }

            // 从结构化结果直接提取动作计划，不再解析格式化文本
            val actions = actionExecutor.parseActionPlan(analysisResult.actionPlan)
            lastScreenDesc = analysisResult.screenDescription

            // 检查是否已完成任务
            if (actions.isEmpty() || isTaskComplete(analysisResult)) {
                report.appendLine("[第${round}轮] Agent 判定任务完成或无需进一步操作")
                if (lastScreenDesc != null) {
                    report.appendLine("  界面描述: $lastScreenDesc")
                }
                break
            }

            report.appendLine("--- 第 ${round} 轮: 计划执行 ${actions.size} 步 ---")
            if (lastScreenDesc != null) {
                report.appendLine("  界面描述: $lastScreenDesc")
            }

            // Step 3: 执行动作序列
            val execResult = actionExecutor.executePlan(
                actions = actions.take(MAX_STEPS_PER_ROUND),
                screenCapture = screenCapture,
            )

            totalStepsExecuted += execResult.executedSteps.size
            successfulSteps += execResult.executedSteps.count { it.success }

            // 记录本轮操作历史
            execResult.executedSteps.forEach { step ->
                val record = ActionRecord(
                    round = round,
                    step = step.step,
                    action = step.action,
                    target = "", // StepResult 不含 target，从原 actions 取
                    detail = step.detail,
                    success = step.success,
                    message = step.detail,
                    timestamp = System.currentTimeMillis(),
                )
                history.add(record)
                report.appendLine("  ${if (step.success) "[OK]" else "[FAIL]"} R${round}S${step.step}:${step.action} - ${step.detail}")
            }

            // 如果全部成功且 Agent 表示完成，结束
            if (execResult.success && isTaskComplete(analysisResult)) {
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
        report.appendLine("总轮次: $actualRounds")
        report.appendLine("总步骤: $totalStepsExecuted")
        report.appendLine("成功步骤: $successfulSteps")
        report.appendLine("成功率: ${if (totalStepsExecuted > 0) "${((successfulSteps.toDouble() / totalStepsExecuted) * 100).toInt()}%" else "N/A"}")
        if (lastScreenDesc != null) {
            report.appendLine("最终界面: $lastScreenDesc")
        }

        val taskSuccess = successfulSteps > 0 && successfulSteps >= totalStepsExecuted

        val result = TaskResult(
            success = taskSuccess,
            totalRounds = actualRounds,
            totalSteps = totalStepsExecuted,
            successfulSteps = successfulSteps,
            finalScreenDescription = lastScreenDesc,
            actionHistory = history.toList(),
            fullReport = report.toString(),
            finalScreenshotBase64 = finalScreenshot,
            recordId = recordId,
        )

        // 持久化到历史记录
        try {
            historyStore.save(
                AgentActionHistoryStore.TaskRecord(
                    id = recordId,
                    task = task,
                    createdAt = System.currentTimeMillis(),
                    success = taskSuccess,
                    totalRounds = actualRounds,
                    totalSteps = totalStepsExecuted,
                    successfulSteps = successfulSteps,
                    actions = history.map {
                        AgentActionHistoryStore.ActionRecord(
                            round = it.round,
                            step = it.step,
                            action = it.action,
                            target = it.target,
                            detail = it.detail,
                            success = it.success,
                            message = it.message,
                            timestamp = it.timestamp,
                        )
                    },
                    finalReport = report.toString(),
                    finalScreenDescription = lastScreenDesc,
                ),
            )
        } catch (e: Exception) {
            DebugLog.e(TAG, "保存历史失败: ${e.message}", e)
        }

        result
    }

    // ---- 内部辅助方法 ----

    /**
     * 判断 Agent 是否认为任务已完成。
     *
     * 基于 [StepMobileAgentTool.AgentResult] 的结构化字段判断，而非文本匹配，
     * 避免误判（如 "success" 出现在步骤描述中）。
     */
    private fun isTaskComplete(result: StepMobileAgentTool.AgentResult): Boolean {
        // actionPlan 为空或仅含 wait 动作 → 视为完成
        val plan = result.actionPlan
        if (plan.isNullOrBlank()) return true
        val actions = actionExecutor.parseActionPlan(plan)
        if (actions.isEmpty()) return true
        // 全部是 wait 动作 → 视为完成
        if (actions.all { it.action == "wait" }) return true
        // 检查 notes 字段是否包含明确的完成标记
        val raw = result.rawResponse?.lowercase() ?: ""
        val completionMarkers = listOf(
            "任务完成", "已完成", "操作完成",
            "task complete", "task completed", "no further action",
            "all done", "nothing more to do",
        )
        return completionMarkers.any { raw.contains(it) }
    }
}
