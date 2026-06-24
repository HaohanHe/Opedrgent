package top.hsyscn.opedrgent.mcp.skills

import android.content.Context
import android.webkit.JavascriptInterface
import top.hsyscn.opedrgent.utils.DebugLog
import org.json.JSONObject

/**
 * JS Skill 回调桥接器 — WebView 与 Kotlin 之间的双向通信桥梁。
 *
 * 职责：
 * - 注入 JavaScript 接口对象到 WebView（@JavascriptInterface）
 * - 处理 JS 端调用的回调（postMessage / getResult）
 * - 将 JS 返回的结果转换为类型安全的 Kotlin 对象
 * - 管理 Skill 执行生命周期（超时、取消）
 *
 * 架构：
 * ```
 * JS Skill (WebView)
 *   │ postMessage(data)
 *   ▼
 * GalleryBridge (@JavascriptInterface "AndroidSkillHost")
 *   │ onMessage(json) → 解析 + 验证
 *   ▼
 * SkillWebViewExecutor.execute()
 *   │ ExecutionResult
 *   ▼
 * 调用方（Tool / EditorTeam）
 * ```
 */
class GalleryBridge(private val context: Context) {

    companion object {
        private const val TAG = "GalleryBridge"

        /** JS 接口对象名称（必须与 WebView addJavascriptInterface 一致） */
        const val INTERFACE_NAME = "AndroidSkillHost"
    }

    /**
     * 结果类型 — JS Skill 可以返回不同格式的结果。
     */
    enum class ResultType {
        /** 纯文本结果 */
        TEXT,

        /** HTML 片段（可渲染为富文本） */
        HTML,

        /** 图片数据（Base64 或 URL） */
        IMAGE,

        /** 完整 WebView 页面（需要在 WebView 中展示） */
        WEBVIEW,
    }

    /**
     * 结构化结果 — JS 回调返回的标准化数据。
     */
    data class BridgeResult(
        val type: ResultType,
        val content: String,
        val metadata: Map<String, String> = emptyMap(),
        val success: Boolean = true,
        val errorMessage: String? = null,
    )

    // ==================== 当前执行状态 ====================

    /** 当前正在执行的 Skill 名称 */
    @Volatile private var currentSkillName: String? = null

    /** 结果回调（由 Executor 设置） */
    private var resultCallback: ((BridgeResult) -> Unit)? = null

    /** 控制台日志收集 */
    private val consoleLogs = mutableListOf<String>()

    // ==================== JS 接口方法（@JavascriptInterface）====================

    /**
     * JS 端调用：发送消息/数据给宿主。
     *
     * 用法（JS 侧）：
     * ```js
     * AndroidSkillHost.postMessage(JSON.stringify({
     *   type: 'data',
     *   payload: { key: 'value' }
     * }));
     * ```
     */
    @JavascriptInterface
    fun postMessage(messageJson: String) {
        try {
            val json = JSONObject(messageJson)
            val type = json.optString("type", "data")
            DebugLog.d("$TAG: postMessage [$currentSkillName] type=$type")

            when (type) {
                "log" -> {
                    // JS console.log 桥接
                    val logMsg = json.optString("message", "")
                    consoleLogs.add(logMsg)
                    DebugLog.d("$TAG: [console] $logMsg")
                }
                "data" -> {
                    // 数据消息（暂存，不立即处理）
                    DebugLog.d("$TAG: data message received: ${json.optString("payload", "")}")
                }
                "progress" -> {
                    // 进度更新
                    val progress = json.optInt("progress", 0)
                    DebugLog.d("$TAG: progress: $progress%")
                }
                else -> {
                    DebugLog.w("$TAG: unknown message type: $type")
                }
            }
        } catch (e: Exception) {
            DebugLog.w("$TAG: postMessage 解析失败: ${e.message}")
        }
    }

    /**
     * JS 端调用：返回执行结果。
     *
     * 用法（JS 侧）：
     * ```js
     * AndroidSkillHost.getResult(JSON.stringify({
     *   type: 'text',
     *   content: '计算结果: 42'
     * }));
     * ```
     */
    @JavascriptInterface
    fun getResult(resultJson: String) {
        try {
            val json = JSONObject(resultJson)
            val result = parseResultJson(json)

            DebugLog.i("$TAG: getResult [$currentSkillName] type=${result.type} success=${result.success}")

            // 通知回调
            resultCallback?.invoke(result)
        } catch (e: Exception) {
            DebugLog.e("$TAG: getResult 解析失败: ${e.message}", e)
            resultCallback?.invoke(
                BridgeResult(
                    type = ResultType.TEXT,
                    content = "",
                    success = false,
                    errorMessage = "结果解析失败: ${e.message}",
                )
            )
        }
    }

    /**
     * JS 端调用：报告错误。
     *
     * 用法（JS 侧）：
     * ```js
         * AndroidSkillHost.onError(JSON.stringify({
     *   message: '计算溢出',
     *   stack: 'Error at calc.js:10'
     * }));
     * ```
     */
    @JavascriptInterface
    fun onError(errorJson: String) {
        try {
            val json = JSONObject(errorJson)
            val message = json.optString("message", "未知错误")
            val stack = json.optString("stack", "")

            DebugLog.e("$TAG: onError [$currentSkillName]: $message\n$stack")

            resultCallback?.invoke(
                BridgeResult(
                    type = ResultType.TEXT,
                    content = "",
                    success = false,
                    errorMessage = message,
                    metadata = mapOf("stack" to stack),
                )
            )
        } catch (e: Exception) {
            DebugLog.e("$TAG: onError 解析失败: ${e.message}")
        }
    }

    /**
     * JS 端调用：通知就绪（Skill 加载完成）。
     */
    @JavascriptInterface
    fun onReady() {
        DebugLog.i("$TAG: onReady [$currentSkillName] Skill 已加载完成")
    }

    // ==================== 内部方法 ====================

    /**
     * 解析 JSON 结果为结构化 BridgeResult。
     */
    private fun parseResultJson(json: JSONObject): BridgeResult {
        val typeName = json.optString("type", "text").uppercase()
        val type = parseResultType(typeName)
        val content = json.optString("content", "")
        val success = json.optBoolean("success", true)

        // 提取元数据
        val metadata = mutableMapOf<String, String>()
        if (json.has("metadata")) {
            val metaObj = json.getJSONObject("metadata")
            for (key in metaObj.keys()) {
                metadata[key] = metaObj.getString(key)
            }
        }

        return BridgeResult(
            type = type,
            content = content,
            metadata = metadata,
            success = success,
            errorMessage = if (success) null else json.optString("error", "未知错误"),
        )
    }

    /**
     * 解析结果类型字符串为枚举值。
     */
    private fun parseResultType(typeName: String): ResultType {
        return when (typeName) {
            "TEXT" -> ResultType.TEXT
            "HTML" -> ResultType.HTML
            "IMAGE" -> ResultType.IMAGE
            "WEBVIEW" -> ResultType.WEBVIEW
            else -> {
                DebugLog.w("$TAG: 未知结果类型 '$typeName'，默认使用 TEXT")
                ResultType.TEXT
            }
        }
    }

    /**
     * 根据 ResultType 格式化输出内容。
     */
    fun formatOutput(result: BridgeResult): String {
        return when (result.type) {
            ResultType.TEXT -> result.content
            ResultType.HTML -> "[HTML 内容]\n${result.content}"
            ResultType.IMAGE -> "[图片: ${result.metadata["url"] ?: "Base64 (${result.content.length} bytes)"}]"
            ResultType.WEBVIEW -> "[WebView 页面，请在浏览器中查看]\n${result.content.take(200)}..."
        }
    }

    // ==================== 生命周期管理 ====================

    /**
     * 开始新一轮执行（重置状态）。
     */
    fun beginExecution(skillName: String, callback: (BridgeResult) -> Unit) {
        currentSkillName = skillName
        resultCallback = callback
        consoleLogs.clear()
        DebugLog.i("$TAG: beginExecution [$skillName]")
    }

    /**
     * 结束当前执行（清理状态）。
     */
    fun endExecution() {
        DebugLog.i("$TAG: endExecution [$currentSkillName], collected ${consoleLogs.size} console logs")
        currentSkillName = null
        resultCallback = null
    }

    /**
     * 获取收集到的控制台日志。
     */
    fun getConsoleLogs(): List<String> = consoleLogs.toList()

    /**
     * 检查是否有正在进行的执行。
     */
    fun isExecuting(): Boolean = currentSkillName != null

    /**
     * 获取当前执行的 Skill 名称。
     */
    fun getCurrentSkillName(): String? = currentSkillName
}
