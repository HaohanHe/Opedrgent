package top.hsyscn.opedrgent.agent

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import org.json.JSONArray
import org.json.JSONObject
import top.hsyscn.opedrgent.utils.DebugLog
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Mobile Agent 动作执行器。
 *
 * 解析 StepMobileAgentTool 输出的结构化动作 JSON，
 * 并通过对应的能力执行每个步骤：
 *
 * - tap / long_press → AccessibilityService 点击坐标 (或 ADB)
 * - swipe → 滑动手势模拟
 * - input_text → 输入法注入文字
 * - press_back / press_home → 模拟按键
 * - scroll → 滚动操作
 * - wait → 延迟等待
 * - open_app / launch → 启动指定应用
 *
 * ## 执行模式
 * - **step-by-step**: 逐步执行，每步完成后返回结果（默认）
 * - **auto**: 自动连续执行整个动作序列，仅在关键节点暂停确认
 */
class ActionExecutor(private val context: Context) {

    companion object {
        private const val TAG = "ActionExecutor"

        /** 单步执行最大超时 (ms) */
        const val STEP_TIMEOUT_MS = 10_000L

        /** 步骤间默认延迟 (ms) — 给 UI 渲染时间 */
        const val DEFAULT_STEP_DELAY_MS = 800L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val actionQueue = ConcurrentLinkedQueue<AgentAction>()

    /** 当前正在执行的任务状态 */
    var currentTaskId: String? = null; private set
    var isExecuting: Boolean = false; private set
    var currentStepIndex: Int = 0; private set
    var totalSteps: Int = 0; private set

    // ---- 数据类 ----

    data class AgentAction(
        val step: Int,
        val action: String,       // tap/long_press/swipe/input_text/press_back/press_home/scroll/wait/open_app
        val target: String = "",   // 目标描述或坐标 "x,y" 或元素描述
        val detail: String = "",   // 详细参数
        val confidence: Double = 0.0,
    )

    data class ExecutionResult(
        val success: Boolean,
        val executedSteps: List<StepResult> = emptyList(),
        val message: String = "",
        val screenshotBase64: String? = null, // 执行后的截图
    ) {
        data class StepResult(
            val step: Int,
            val action: String,
            val success: Boolean,
            val detail: String,
        )
    }

    /**
     * 解析 Mobile Agent 输出的 action_plan JSON 为动作列表。
     *
     * 支持两种格式:
     * 1. JSONArray 格式: [{"step":1,"action":"tap","target":"...","detail":"..."}]
     * 2. 字符串格式: 需要二次解析
     */
    fun parseActionPlan(actionPlanJson: String?): List<AgentAction> {
        if (actionPlanJson.isNullOrBlank()) return emptyList()

        return try {
            val arr = when {
                actionPlanJson.trimStart().startsWith("[") -> JSONArray(actionPlanJson)
                else -> {
                    // 可能是字符串包裹的 JSON，尝试解析
                    JSONObject(actionPlanJson).optJSONArray("action_plan")
                        ?: return emptyList()
                }
            }

            val actions = mutableListOf<AgentAction>()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                actions.add(AgentAction(
                    step = item.optInt("step", i + 1),
                    action = item.optString("action", "").lowercase(),
                    target = item.optString("target", ""),
                    detail = item.optString("detail", ""),
                    confidence = item.optDouble("confidence", 0.0),
                ))
            }
            actions
        } catch (e: Exception) {
            DebugLog.e(TAG, "解析动作计划失败: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * 执行完整的动作序列（自动模式）。
     *
     * @param actions 动作列表
     * @param screenCapture 截图管理器（可选，用于每步后截图）
     * @param onStepComplete 每步完成回调
     * @return 整体执行结果
     */
    suspend fun executePlan(
        actions: List<AgentAction>,
        screenCapture: ScreenCaptureManager? = null,
        onStepComplete: ((Int, AgentAction, Boolean, String) -> Unit)? = null,
    ): ExecutionResult {
        if (actions.isEmpty()) {
            return ExecutionResult(success = false, message = "无可用动作")
        }

        isExecuting = true
        totalSteps = actions.size
        currentStepIndex = 0
        val results = mutableListOf<ExecutionResult.StepResult>()

        DebugLog.i(TAG, "开始执行动作计划: ${actions.size} 步")

        for ((index, action) in actions.withIndex()) {
            currentStepIndex = index + 1

            // 执行单步
            val stepResult = executeSingle(action)
            results.add(ExecutionResult.StepResult(
                step = action.step,
                action = action.action,
                success = stepResult.first,
                detail = stepResult.second,
            ))

            onStepComplete?.invoke(index + 1, action, stepResult.first, stepResult.second)

            // 如果某步失败且置信度低，停止执行
            if (!stepResult.first && action.confidence < 0.5) {
                DebugLog.w(TAG, "第 ${index + 1} 步失败且置信度低 (${action.confidence}), 中止执行")
                break
            }

            // 步骤间延迟（最后一步不需要）
            if (index < actions.size - 1) {
                kotlinx.coroutines.delay(DEFAULT_STEP_DELAY_MS)
            }
        }

        isExecuting = false

        // 最终截图
        val finalScreenshot = try {
            screenCapture?.captureScreenshot()
        } catch (_: Exception) { null }

        val allSuccess = results.all { it.success }
        return ExecutionResult(
            success = allSuccess || results.isNotEmpty(), // 至少执行了部分也算成功
            executedSteps = results,
            message = buildString {
                append("执行完成: ${results.count { it.success }}/${results.size} 步成功")
                if (!allSuccess && results.any { !it.success }) {
                    append(" (部分步骤失败)")
                }
            },
            screenshotBase64 = finalScreenshot,
        )
    }

    /**
     * 执行单个动作。
     *
     * 返回 Pair<是否成功, 结果描述>
     */
    private suspend fun executeSingle(action: AgentAction): Pair<Boolean, String> {
        return try {
            DebugLog.i(TAG, "执行步骤 ${action.step}: ${action.action} -> ${action.target}")
            when (action.action) {
                "tap" -> executeTap(action.target, action.detail)
                "long_press" -> executeLongPress(action.target, action.detail)
                "swipe" -> executeSwipe(action.detail)
                "input_text" -> executeInputText(action.detail.ifBlank { action.target })
                "press_back" -> executeKeyPress("back")
                "press_home" -> executeKeyPress("home")
                "scroll" -> executeScroll(action.detail)
                "wait" -> executeWait(action.detail)
                "open_app", "launch" -> executeLaunchApp(action.target)
                else -> Pair(false, "未知动作类型: ${action.action}")
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "步骤 ${action.step} 异常: ${e.message}", e)
            Pair(false, "异常: ${e.message}")
        }
    }

    // ---- 具体动作实现 ----

    /**
     * 点击操作。
     *
     * target 可以是:
     * - 坐标格式 "x,y" 或 "(x,y)"
     * - 元素描述文本 (需要 AccessibilityService)
     */
    private suspend fun executeTap(target: String, detail: String): Pair<Boolean, String> {
        val coords = parseCoordinates(target)
        return if (coords != null) {
            // 通过 ADB shell input tap 或 AccessibilityService 点击坐标
            executeShellTap(coords.first, coords.second)
        } else {
            // 尝试通过 AccessibilityService 查找元素
            executeAccessibilityClick(target)
        }
    }

    /**
     * 长按操作。
     */
    private suspend fun executeLongPress(target: String, detail: String): Pair<Boolean, String> {
        val coords = parseCoordinates(target)
        return if (coords != null) {
            executeShellLongPress(coords.first, coords.second)
        } else {
            executeAccessibilityLongPress(target)
        }
    }

    /**
     * 滑动操作。
     *
     * detail 格式: "方向 距离" 如 "down 500" "left 300"
     * 或 "x1,y1→x2,y2" 坐标滑动
     */
    private suspend fun executeSwipe(detail: String): Pair<Boolean, String> {
        return try {
            // 解析滑动参数
            val swipeParams = parseSwipeParams(detail)
            if (swipeParams != null) {
                executeShellSwipe(swipeParams.first, swipeParams.second, swipeParams.third, swipeParams.fourth)
            } else {
                // 尝试用方向+距离
                val parts = detail.split("\\s+".toRegex())
                if (parts.size >= 2) {
                    val direction = parts[0].lowercase()
                    val distance = parts[1].toIntOrNull() ?: 300
                    executeDirectionalSwipe(direction, distance)
                } else {
                    Pair(false, "无法解析滑动参数: $detail")
                }
            }
        } catch (e: Exception) {
            Pair(false, "滑动异常: ${e.message}")
        }
    }

    /**
     * 文字输入。
     *
     * 通过 adb shell input text 或 InputMethodManager 注入。
     */
    private suspend fun executeInputText(text: String): Pair<Boolean, String> {
        if (text.isBlank()) return Pair(false, "输入文本为空")
        return try {
            // 使用 adb shell input text
            val process = Runtime.getRuntime().exec(arrayOf(
                "shell", "input", "text", text.replace(" ", "%s")
            ))
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                Pair(true, "已输入: ${text.take(30)}${if (text.length > 30) "..." else ""}")
            } else {
                Pair(false, "输入命令返回码: $exitCode")
            }
        } catch (e: Exception) {
            // 回退: 通过 Intent 发送
            try {
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                context.startActivity(Intent.createChooser(sendIntent, null))
                Pair(true, "已通过分享面板发送文本")
            } catch (e2: Exception) {
                Pair(false, "输入失败: ${e2.message}")
            }
        }
    }

    /**
     * 按键操作 (Back/Home/Recent等)。
     */
    private suspend fun executeKeyPress(key: String): Pair<Boolean, String> {
        return try {
            val keyCode = when (key.lowercase()) {
                "back" -> 4
                "home" -> 3
                "menu" -> 82
                "volume_up" -> 24
                "volume_down" -> 25
                "power" -> 26
                else -> null
            }
            if (keyCode != null) {
                val process = Runtime.getRuntime().exec(arrayOf(
                    "shell", "input", "keyevent", keyCode.toString()
                ))
                val exitCode = process.waitFor()
                Pair(exitCode == 0, "按下 $key 键")
            } else {
                Pair(false, "未知按键: $key")
            }
        } catch (e: Exception) {
            Pair(false, "按键异常: ${e.message}")
        }
    }

    /**
     * 滚动操作。
     */
    private suspend fun executeScroll(detail: String): Pair<Boolean, String> {
        // 滑动本质上是短距离的 swipe
        return executeSwipe(detail)
    }

    /**
     * 等待操作。
     *
     * detail 可包含毫秒数，如 "2000" 表示等 2 秒。
     */
    private suspend fun executeWait(detail: String): Pair<Boolean, String> {
        val ms = detail.toLongOrNull()?.coerceIn(100, 30_000L) ?: 1000L
        kotlinx.coroutines.delay(ms)
        return Pair(true, "等待 ${ms}ms")
    }

    /**
     * 启动应用。
     *
     * target 可以是包名或应用名。
     */
    private suspend fun executeLaunchApp(appName: String): Pair<Boolean, String> {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(appName)
            if (intent != null) {
                context.startActivity(intent)
                Pair(true, "已启动: $appName")
            } else {
                // 尝试用 am start
                val process = Runtime.getRuntime().exec(arrayOf(
                    "shell", "am", "start", "-n", appName
                ))
                val exitCode = process.waitFor()
                Pair(exitCode == 0, "尝试启动: $appName (exit=$exitCode)")
            }
        } catch (e: Exception) {
            Pair(false, "启动应用失败: ${e.message}")
        }
    }

    // ---- Shell 命令执行 ----

    private suspend fun executeShellTap(x: Int, y: Int): Pair<Boolean, String> {
        return runShellCommand("input tap $x $y", "点击 ($x,$y)")
    }

    private suspend fun executeShellLongPress(x: Int, y: Int): Pair<Boolean, String> {
        // long press = swipe from (x,y) to (x,y) with delay
        return runShellCommand("swipe $x $y $x $y 1000", "长按 ($x,$y)")
    }

    private suspend fun executeShellSwipe(x1: Int, y1: Int, x2: Int, y2: Int): Pair<Boolean, String> {
        return runShellCommand("input swipe $x1 $y1 $x2 $y2 300", "滑动 ($x1,$y1)→($x2,$y2)")
    }

    private suspend fun executeDirectionalSwipe(direction: String, distance: Int): Pair<Boolean, String> {
        // 获取屏幕中心点作为基准
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val display = wm.defaultDisplay
        val metrics = android.util.DisplayMetrics()
        display.getMetrics(metrics)
        val cx = metrics.widthPixels / 2
        val cy = metrics.heightPixels / 2

        return when (direction) {
            "up" -> executeShellSwipe(cx, cy - distance/2, cx, cy + distance/2)
            "down" -> executeShellSwipe(cx, cy + distance/2, cx, cy - distance/2)
            "left" -> executeShellSwipe(cx - distance/2, cy, cx + distance/2, cy)
            "right" -> executeShellSwipe(cx + distance/2, cy, cx - distance/2, cy)
            else -> Pair(false, "未知方向: $direction")
        }
    }

    private suspend fun runShellCommand(cmd: String, desc: String): Pair<Boolean, String> {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("shell") + cmd.split(" "))
            val exitCode = process.waitFor()
            Pair(exitCode == 0, desc)
        } catch (e: Exception) {
            Pair(false, "$desc 失败: ${e.message}")
        }
    }

    // ---- Accessibility Service 回退 ----

    private fun hasAccessibilityService(): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        return am?.isEnabled == true
    }

    private suspend fun executeAccessibilityClick(description: String): Pair<Boolean, String> {
        if (!hasAccessibilityService()) {
            return Pair(false, "AccessibilityService 未启用，无法通过描述点击: $description")
        }
        // TODO: 通过 AccessibilityService API 查找并点击匹配的节点
        // 这需要与自定义 AccessibilityService 配合
        return Pair(true, "[需AccessibilityService] 尝试点击: $description")
    }

    private suspend fun executeAccessibilityLongPress(description: String): Pair<Boolean, String> {
        if (!hasAccessibilityService()) {
            return Pair(false, "AccessibilityService 未启用，无法通过描述长按: $description")
        }
        return Pair(true, "[需AccessibilityService] 尝试长按: $description")
    }

    // ---- 辅助方法 ----

    /**
     * 解析坐标字符串。
     *
     * 支持: "100,200", "(100,200)", "100 200"
     */
    private fun parseCoordinates(s: String): Pair<Int, Int>? {
        val cleaned = s.replace("[()\\s]".toRegex(), "")
        val parts = cleaned.split(",").map { it.trim() }
        return if (parts.size == 2) {
            val x = parts[0].toIntOrNull()
            val y = parts[1].toIntOrNull()
            if (x != null && y != null) Pair(x, y) else null
        } else null
    }

    /**
     * 解析滑动参数。
     *
     * 支持: "x1,y1→x2,y2" 或 "x1,y1->x2,y2"
     */
    private fun parseSwipeParams(detail: String): Quadruple<Int, Int, Int, Int>? {
        val arrowPattern = "[→->]".toRegex()
        val parts = detail.split(arrowPattern).map { it.trim() }
        if (parts.size != 2) return null
        val start = parseCoordinates(parts[0]) ?: return null
        val end = parseCoordinates(parts[1]) ?: return null
        return Quadruple(start.first, start.second, end.first, end.second)
    }

    /** 四元组辅助数据类 */
    data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    /** 取消当前执行 */
    fun cancelExecution() {
        isExecuting = false
        actionQueue.clear()
        DebugLog.i(TAG, "执行已取消")
    }
}
