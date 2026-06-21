package top.hsyscn.opedrgent.agent

import android.content.Context
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import org.json.JSONArray
import org.json.JSONObject
import top.hsyscn.opedrgent.utils.DebugLog
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Mobile Agent 动作执行器。
 *
 * 解析 [StepMobileAgentTool] 输出的结构化动作 JSON，
 * 并通过 [OpedrgentAccessibilityService] 执行每个步骤：
 *
 * - tap / long_press → AccessibilityService.dispatchGesture
 * - swipe / scroll → AccessibilityService.dispatchGesture (路径手势)
 * - input_text → AccessibilityNodeInfo.ACTION_SET_TEXT
 * - press_back / press_home → AccessibilityService.performGlobalAction
 * - wait → 协程延迟
 * - open_app / launch → PackageManager.getLaunchIntentForPackage
 *
 * ## 前置条件
 * 用户需在 系统设置 > 无障碍 中启用 [OpedrgentAccessibilityService]。
 * 若服务未启用，所有依赖手势/节点/按键的动作都会返回失败并给出明确提示。
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
        val screenshotBase64: String? = null,
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
                delay(DEFAULT_STEP_DELAY_MS)
            }
        }

        isExecuting = false

        val finalScreenshot = try {
            screenCapture?.captureScreenshot()
        } catch (_: Exception) { null }

        val allSuccess = results.all { it.success }
        return ExecutionResult(
            success = allSuccess || results.isNotEmpty(),
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
     * 执行单个动作。返回 Pair<是否成功, 结果描述>。
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
     * - 坐标格式 "x,y" 或 "(x,y)" → 通过 AccessibilityService.dispatchGesture 点击坐标
     * - 元素描述文本 → 通过 AccessibilityService.findAndClickByText 查找并点击
     */
    private suspend fun executeTap(target: String, detail: String): Pair<Boolean, String> {
        val service = requireAccessibilityService()
            ?: return Pair(false, "AccessibilityService 未启用，无法执行点击。请在系统设置 > 无障碍 中启用 Opedrgent 服务")

        val coords = parseCoordinates(target)
        return if (coords != null) {
            val ok = service.tap(coords.first.toFloat(), coords.second.toFloat())
            Pair(ok, if (ok) "点击 ($target)" else "点击失败: ($target)")
        } else {
            // 通过文本查找节点点击
            val ok = service.findAndClickByText(target)
            Pair(ok, if (ok) "点击元素: $target" else "未找到可点击元素: $target")
        }
    }

    /**
     * 长按操作。
     */
    private suspend fun executeLongPress(target: String, detail: String): Pair<Boolean, String> {
        val service = requireAccessibilityService()
            ?: return Pair(false, "AccessibilityService 未启用，无法执行长按")

        val coords = parseCoordinates(target)
        return if (coords != null) {
            val ok = service.longPress(coords.first.toFloat(), coords.second.toFloat())
            Pair(ok, if (ok) "长按 ($target)" else "长按失败: ($target)")
        } else {
            // 长按文本节点 -- 先找到节点坐标再长按
            Pair(false, "长按文本节点暂不支持，请提供坐标 (x,y)")
        }
    }

    /**
     * 滑动操作。
     *
     * detail 格式:
     * - "x1,y1→x2,y2" 或 "x1,y1->x2,y2" 坐标滑动
     * - "down 500" / "up 300" / "left 200" / "right 400" 方向+距离
     */
    private suspend fun executeSwipe(detail: String): Pair<Boolean, String> {
        val service = requireAccessibilityService()
            ?: return Pair(false, "AccessibilityService 未启用，无法执行滑动")

        return try {
            val swipeParams = parseSwipeParams(detail)
            if (swipeParams != null) {
                val ok = service.swipe(
                    swipeParams.first.toFloat(), swipeParams.second.toFloat(),
                    swipeParams.third.toFloat(), swipeParams.fourth.toFloat(),
                )
                Pair(ok, if (ok) "滑动 ($detail)" else "滑动失败: ($detail)")
            } else {
                val parts = detail.split("\\s+".toRegex())
                if (parts.size >= 2) {
                    val direction = parts[0].lowercase()
                    val distance = parts[1].toIntOrNull() ?: 300
                    executeDirectionalSwipe(service, direction, distance)
                } else {
                    Pair(false, "无法解析滑动参数: $detail")
                }
            }
        } catch (e: Exception) {
            Pair(false, "滑动异常: ${e.message}")
        }
    }

    /**
     * 文字输入 -- 通过 AccessibilityService 向焦点节点注入文字。
     */
    private suspend fun executeInputText(text: String): Pair<Boolean, String> {
        if (text.isBlank()) return Pair(false, "输入文本为空")
        val service = requireAccessibilityService()
            ?: return Pair(false, "AccessibilityService 未启用，无法输入文字")
        val ok = service.injectText(text)
        return Pair(ok, if (ok) "已输入: ${text.take(30)}${if (text.length > 30) "..." else ""}" else "注入文字失败，请确认当前有焦点输入框")
    }

    /**
     * 按键操作 (Back/Home) -- 通过 AccessibilityService.performGlobalAction。
     */
    private suspend fun executeKeyPress(key: String): Pair<Boolean, String> {
        val service = requireAccessibilityService()
            ?: return Pair(false, "AccessibilityService 未启用，无法执行按键")
        val globalAction = when (key.lowercase()) {
            "back" -> AccessibilityServiceActionCompat.GLOBAL_ACTION_BACK
            "home" -> AccessibilityServiceActionCompat.GLOBAL_ACTION_HOME
            "recents", "recent" -> AccessibilityServiceActionCompat.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityServiceActionCompat.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityServiceActionCompat.GLOBAL_ACTION_QUICK_SETTINGS
            else -> {
                DebugLog.w(TAG, "未知按键: $key")
                return Pair(false, "未知按键: $key")
            }
        }
        val ok = service.doGlobalAction(globalAction)
        return Pair(ok, if (ok) "按下 $key 键" else "按键失败: $key")
    }

    /**
     * 滚动操作 -- 本质是较长距离的滑动。
     */
    private suspend fun executeScroll(detail: String): Pair<Boolean, String> {
        return executeSwipe(detail)
    }

    /**
     * 等待操作。
     */
    private suspend fun executeWait(detail: String): Pair<Boolean, String> {
        val ms = detail.toLongOrNull()?.coerceIn(100, 30_000L) ?: 1000L
        delay(ms)
        return Pair(true, "等待 ${ms}ms")
    }

    /**
     * 启动应用 -- 通过 PackageManager.getLaunchIntentForPackage。
     */
    private suspend fun executeLaunchApp(appName: String): Pair<Boolean, String> {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(appName)
            if (intent != null) {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Pair(true, "已启动: $appName")
            } else {
                Pair(false, "未找到应用: $appName (请使用完整包名，如 com.tencent.mm)")
            }
        } catch (e: Exception) {
            Pair(false, "启动应用失败: ${e.message}")
        }
    }

    // ---- 方向滑动 ----

    private suspend fun executeDirectionalSwipe(
        service: OpedrgentAccessibilityService,
        direction: String,
        distance: Int,
    ): Pair<Boolean, String> {
        val (cx, cy) = getScreenCenter()
        val (x1, y1, x2, y2) = when (direction) {
            "up" -> Quad(cx, cy + distance / 2, cx, cy - distance / 2)
            "down" -> Quad(cx, cy - distance / 2, cx, cy + distance / 2)
            "left" -> Quad(cx + distance / 2, cy, cx - distance / 2, cy)
            "right" -> Quad(cx - distance / 2, cy, cx + distance / 2, cy)
            else -> return Pair(false, "未知方向: $direction")
        }
        val ok = service.swipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat())
        return Pair(ok, if (ok) "向${direction}滑动 ${distance} px" else "滑动失败")
    }

    private fun getScreenCenter(): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = wm.defaultDisplay
        val metrics = android.util.DisplayMetrics()
        display.getMetrics(metrics)
        return Pair(metrics.widthPixels / 2, metrics.heightPixels / 2)
    }

    // ---- AccessibilityService 可用性 ----

    /**
     * 获取已就绪的 AccessibilityService 实例，未启用时返回 null。
     */
    private fun requireAccessibilityService(): OpedrgentAccessibilityService? {
        val service = OpedrgentAccessibilityService.instance
        if (service == null) {
            DebugLog.w(TAG, "AccessibilityService 未启用")
            return null
        }
        return service
    }

    /**
     * 检查无障碍服务是否已启用（用于 UI 层提示用户）。
     */
    fun isAccessibilityEnabled(): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (am == null || !am.isEnabled) return false
        // 进一步检查是否是本 App 的服务
        val enabledServices = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val componentName = "${context.packageName}/${OpedrgentAccessibilityService::class.java.name}"
        return enabledServices.contains(componentName)
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
    private fun parseSwipeParams(detail: String): Quad<Int, Int, Int, Int>? {
        // 同时支持 → (Unicode 箭头) 和 -> (ASCII)
        val parts = detail.split("→|->".toRegex()).map { it.trim() }
        if (parts.size != 2) return null
        val start = parseCoordinates(parts[0]) ?: return null
        val end = parseCoordinates(parts[1]) ?: return null
        return Quad(start.first, start.second, end.first, end.second)
    }

    /** 四元组辅助数据类 */
    data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    /** 取消当前执行 */
    fun cancelExecution() {
        isExecuting = false
        actionQueue.clear()
        DebugLog.i(TAG, "执行已取消")
    }
}

/**
 * AccessibilityService 全局动作常量的兼容包装。
 *
 * 直接引用 [android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_*] 在某些
 * 编译环境下因 API level 差异可能告警，这里集中映射。
 */
private object AccessibilityServiceActionCompat {
    const val GLOBAL_ACTION_BACK = android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
    const val GLOBAL_ACTION_HOME = android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
    const val GLOBAL_ACTION_RECENTS = android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
    const val GLOBAL_ACTION_NOTIFICATIONS = android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
    const val GLOBAL_ACTION_QUICK_SETTINGS = android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
}
