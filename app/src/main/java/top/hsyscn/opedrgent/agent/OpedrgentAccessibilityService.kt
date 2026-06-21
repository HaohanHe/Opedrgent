package top.hsyscn.opedrgent.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import top.hsyscn.opedrgent.utils.DebugLog
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Opedrgent 无障碍服务 -- Mobile Agent 的执行底座。
 *
 * ## 能力
 * - **手势派发**: tap / long_press / swipe 通过 dispatchGesture 实现，无需 root
 * - **全局按键**: BACK / HOME / RECENTS 通过 performGlobalAction 实现
 * - **节点查找**: 按文本/content-description 查找可点击节点并点击
 * - **文字注入**: 通过 ACTION_SET_TEXT 向焦点节点注入文字
 * - **UI 树导出**: dumpUiTree() 返回当前界面可见节点的 JSON，供 Mobile Agent 分析
 *
 * ## 启用方式
 * 用户需在 系统设置 > 无障碍 中启用本服务。
 * 启用后 [instance] 即可被 [ActionExecutor] 等组件访问。
 *
 * ## 安全
 * 服务仅响应本 App 内部调用，不监听任何外部事件。
 * onAccessibilityEvent 仅做最小日志记录，不存储用户其他 App 的内容。
 */
class OpedrgentAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "OpedrgentA11y"

        /** 当前活跃的服务实例（启用后由 onServiceConnected 设置）。 */
        @Volatile
        var instance: OpedrgentAccessibilityService? = null
            private set

        /** 无障碍服务是否已启用且就绪。 */
        fun isAvailable(): Boolean = instance != null

        /** 长按持续时间 (ms)。 */
        private const val LONG_PRESS_DURATION_MS = 1000L

        /** 滑动手势持续时间 (ms)。 */
        private const val SWIPE_DURATION_MS = 300L
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        DebugLog.i(TAG, "无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 仅记录事件类型，不处理 -- 避免存储用户其他 App 的内容
        if (event != null) {
            DebugLog.d(TAG, "a11y event: type=${event.eventType}, pkg=${event.packageName}")
        }
    }

    override fun onInterrupt() {
        DebugLog.w(TAG, "无障碍服务被中断")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        DebugLog.i(TAG, "无障碍服务已解绑")
        return super.onUnbind(intent)
    }

    // ==================== 手势派发 ====================

    /**
     * 在指定坐标点击。
     */
    suspend fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureAndWait(gesture)
    }

    /**
     * 在指定坐标长按。
     */
    suspend fun longPress(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, LONG_PRESS_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureAndWait(gesture)
    }

    /**
     * 从 (x1,y1) 滑动到 (x2,y2)。
     */
    suspend fun swipe(
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        durationMs: Long = SWIPE_DURATION_MS,
    ): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureAndWait(gesture)
    }

    /**
     * 派发手势并等待完成。
     */
    private suspend fun dispatchGestureAndWait(gesture: GestureDescription): Boolean {
        return suspendCancellableCoroutine { cont ->
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gesture: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gesture: GestureDescription?) {
                    DebugLog.w(TAG, "手势被取消")
                    if (cont.isActive) cont.resume(false)
                }
            }
            val dispatched = dispatchGesture(gesture, callback, null)
            if (!dispatched) {
                if (cont.isActive) cont.resume(false)
                return@suspendCancellableCoroutine
            }
        }
    }

    // ==================== 全局按键 ====================

    /**
     * 执行全局动作 (BACK / HOME / RECENTS)。
     */
    fun doGlobalAction(action: Int): Boolean {
        val supported = action in setOf(
            GLOBAL_ACTION_BACK,
            GLOBAL_ACTION_HOME,
            GLOBAL_ACTION_RECENTS,
            GLOBAL_ACTION_NOTIFICATIONS,
            GLOBAL_ACTION_QUICK_SETTINGS,
            GLOBAL_ACTION_POWER_DIALOG,
            GLOBAL_ACTION_TAKE_SCREENSHOT,
        )
        if (!supported) {
            DebugLog.w(TAG, "不支持的全局动作: $action")
            return false
        }
        val result = performGlobalAction(action)
        DebugLog.i(TAG, "performGlobalAction($action) = $result")
        return result
    }

    // ==================== 节点查找与操作 ====================

    /**
     * 按文本或 content-description 查找节点并点击。
     *
     * @param query 查找文本 (支持模糊匹配)
     * @param clickParentIfNotClickable 若节点本身不可点击，是否点击其最近的可点击祖先
     * @return 是否找到并点击成功
     */
    fun findAndClickByText(query: String, clickParentIfNotClickable: Boolean = true): Boolean {
        val root = rootInActiveWindow ?: run {
            DebugLog.w(TAG, "rootInActiveWindow 为空")
            return false
        }
        val node = findNodeByText(root, query) ?: run {
            DebugLog.i(TAG, "未找到文本匹配节点: $query")
            return false
        }
        return clickNode(node, clickParentIfNotClickable)
    }

    /**
     * 查找节点 -- 遍历整棵树，匹配 text / contentDescription。
     */
    private fun findNodeByText(root: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        val lowerQuery = query.lowercase()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = node.text?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            if (text.contains(lowerQuery) || desc.contains(lowerQuery)) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    /**
     * 点击节点 -- 优先 ACTION_CLICK，否则向上找可点击祖先，最后回退到坐标点击。
     */
    private fun clickNode(node: AccessibilityNodeInfo, clickParentIfNotClickable: Boolean): Boolean {
        if (node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        if (clickParentIfNotClickable) {
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) {
                    return parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                parent = parent.parent
            }
        }
        // 回退: 用节点中心坐标派发点击手势
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val cx = rect.exactCenterX()
        val cy = rect.exactCenterY()
        DebugLog.i(TAG, "节点不可点击，回退到坐标点击: ($cx, $cy)")
        val path = Path().apply { moveTo(cx, cy) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * 向当前焦点节点注入文字。
     */
    fun injectText(text: String): Boolean {
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: run {
            DebugLog.w(TAG, "无焦点输入框，无法注入文字")
            return false
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val result = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        DebugLog.i(TAG, "injectText(${text.take(30)}...) = $result")
        return result
    }

    // ==================== UI 树导出 ====================

    /**
     * 导出当前界面可见节点树为 JSON，供 Mobile Agent 分析。
     *
     * 仅导出可见、有文本/描述/可点击属性的节点，避免输出过大。
     *
     * @param maxNodes 最大节点数 (防止超大 UI 树)
     * @return JSON 数组字符串，每个元素包含 {text, desc, class, bounds, clickable, focusable}
     */
    fun dumpUiTree(maxNodes: Int = 200): String {
        val root = rootInActiveWindow ?: return "[]"
        val nodes = JSONArray()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var count = 0
        while (queue.isNotEmpty() && count < maxNodes) {
            val node = queue.removeFirst()
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            // 仅保留有意义的节点
            if (text.isNotBlank() || desc.isNotBlank() || node.isClickable || node.isFocusable) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                // 仅保留屏幕内可见节点
                if (rect.width() > 0 && rect.height() > 0) {
                    val bounds = JSONObject()
                    bounds.put("left", rect.left)
                    bounds.put("top", rect.top)
                    bounds.put("right", rect.right)
                    bounds.put("bottom", rect.bottom)
                    bounds.put("centerX", rect.exactCenterX().toInt())
                    bounds.put("centerY", rect.exactCenterY().toInt())

                    val obj = JSONObject()
                    obj.put("text", text)
                    obj.put("desc", desc)
                    obj.put("class", node.className?.toString() ?: "")
                    obj.put("bounds", bounds)
                    obj.put("clickable", node.isClickable)
                    obj.put("focusable", node.isFocusable)
                    obj.put("scrollable", node.isScrollable)
                    nodes.put(obj)
                    count++
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return nodes.toString()
    }

    /**
     * 获取屏幕尺寸（通过根节点 bounds 推断）。
     */
    fun getScreenBounds(): Rect? {
        val root = rootInActiveWindow ?: return null
        val rect = Rect()
        root.getBoundsInScreen(rect)
        return rect
    }

    /**
     * 获取当前前台应用包名。
     */
    fun getForegroundPackage(): String? {
        return rootInActiveWindow?.packageName?.toString()
    }
}
