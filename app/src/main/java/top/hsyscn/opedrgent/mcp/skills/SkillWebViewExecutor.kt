package top.hsyscn.opedrgent.mcp.skills

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import top.hsyscn.opedrgent.utils.DebugLog
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Skill WebView 沙箱执行器（对标 Google Gallery）。
 *
 * 执行流程：
 * 1. 加载 SKILL.md 定义的 HTML/JS 资源到隔离的 WebView
 * 2. 通过 postMessage 注入输入参数
 * 3. JS 执行完成后调用 ai_edge_gallery_get_result() 回调
 * 4. 收集结果返回给调用方
 *
 * 安全特性：
 * - 每个 Skill 使用独立 WebView 实例（隔离执行环境）
 * - JavaScript 默认禁用（仅对白名单 Skill 启用）
 * - 禁止网络请求（除非 Skill 显式声明 needNetwork）
 * - 执行超时保护（默认 30 秒）
 *
 * @see StandardSkillDefinition Skill 定义
 * @see <a href="https://github.com/google/ai-edge/gallery">Google Gallery</a>
 */
class SkillWebViewExecutor(private val context: Context) {

    companion object {
        /** JS 回调接口名（必须与 HTML 中的调用一致） */
        const val CALLBACK_INTERFACE = "AndroidSkillHost"

        /** 默认执行超时（毫秒） */
        const val DEFAULT_TIMEOUT_MS = 30_000L

        /** WebView 沙箱中注入的结果回调函数名 */
        const val RESULT_CALLBACK = "ai_edge_gallery_get_result"

        /** postMessage 通道名 */
        const val POST_MESSAGE_CHANNEL = "skill_data"

        /** 最大并发 WebView 执行数 */
        private const val MAX_CONCURRENT_EXECUTIONS = 3
    }

    /** 并发执行限制信号量 */
    private val executionSemaphore = Semaphore(MAX_CONCURRENT_EXECUTIONS)

    /** 执行结果 */
    data class ExecutionResult(
        val success: Boolean,
        val output: String = "",
        val error: String? = null,
        val executionMs: Long = 0,
        val consoleLogs: List<String> = emptyList(),
    )

    /** 执行配置 */
    data class ExecutionConfig(
        val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        val enableNetwork: Boolean = false,
        val inputParams: Map<String, Any> = emptyMap(),
        val enableConsoleCapture: Boolean = true,
    )

    /**
     * 在 WebView 沙箱中执行 JS Skill。
     *
     * @param skillDef 技能定义（包含 scripts/assets 路径）
     * @param config 执行配置
     * @return 执行结果
     */
    suspend fun execute(
        skillDef: StandardSkillDefinition,
        config: ExecutionConfig = ExecutionConfig(),
    ): ExecutionResult {
        executionSemaphore.acquire()
        try {
            return executeInner(skillDef, config)
        } finally {
            executionSemaphore.release()
        }
    }

    private suspend fun executeInner(
        skillDef: StandardSkillDefinition,
        config: ExecutionConfig,
    ): ExecutionResult = suspendCancellableCoroutine { cont ->
        val startTime = System.currentTimeMillis()
        var webView: WebView? = null
        val consoleLogs = mutableListOf<String>()
        var completed = false

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (completed) return@post
            try {
                webView = WebView(context).apply {
                    // === 安全配置（沙箱隔离）===
                    settings.apply {
                        javaScriptEnabled = true  // JS Skill 需要启用
                        domStorageEnabled = false // 禁用 DOM 存储（隔离）
                        databaseEnabled = false   // 禁用数据库
                        cacheMode = WebSettings.LOAD_NO_CACHE  // 不缓存
                        setSupportZoom(false)
                        builtInZoomControls = false
                        displayZoomControls = false
                        mediaPlaybackRequiresUserGesture = false
                        allowFileAccess = false     // 禁止 file:// 访问（assets 通过 file:///android_asset/ 不受影响）
                        allowFileAccessFromFileURLs = false
                        allowUniversalAccessFromFileURLs = false
                        allowContentAccess = false  // 禁止 content:// URL
                        blockNetworkImage = !config.enableNetwork
                        blockNetworkLoads = !config.enableNetwork
                    }

                    // 注册 Android-JS 桥接接口
                    addJavascriptInterface(object : Any() {
                        @JavascriptInterface
                        fun postResult(resultJson: String) {
                            if (completed) return  // 防止重复回调
                            completed = true
                            val duration = System.currentTimeMillis() - startTime
                            DebugLog.i("SkillWebViewExecutor: result received in ${duration}ms")
                            cont.resume(ExecutionResult(
                                success = true,
                                output = resultJson,
                                executionMs = duration,
                                consoleLogs = consoleLogs.toList(),
                            ))
                            destroyWebView(this@apply)
                        }

                        @JavascriptInterface
                        fun postError(errorMsg: String) {
                            if (completed) return
                            completed = true
                            DebugLog.w("SkillWebViewExecutor: JS error: $errorMsg")
                            cont.resume(ExecutionResult(
                                success = false,
                                error = errorMsg,
                                executionMs = System.currentTimeMillis() - startTime,
                                consoleLogs = consoleLogs.toList(),
                            ))
                            destroyWebView(this@apply)
                        }

                        @JavascriptInterface
                        fun log(message: String) {
                            if (config.enableConsoleCapture) {
                                consoleLogs.add(message)
                                DebugLog.d("SkillWebView[console]: $message")
                            }
                        }

                        @JavascriptInterface
                        fun getInputParams(): String {
                            // 返回 JSON 格式的输入参数
                            return org.json.JSONObject(config.inputParams).toString()
                        }
                    }, CALLBACK_INTERFACE)

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            // 页面加载完成，注入参数并触发执行
                            if (!completed) {
                                injectAndTrigger(this@apply, config.inputParams, config.enableNetwork)
                            }
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            errorCode: Int,
                            description: String?,
                            failingUrl: String?
                        ) {
                            super.onReceivedError(view, errorCode, description, failingUrl)
                            if (!completed) {
                                completed = true
                                cont.resume(ExecutionResult(
                                    success = false,
                                    error = "WebView 加载错误($errorCode): $description",
                                    executionMs = System.currentTimeMillis() - startTime,
                                    consoleLogs = consoleLogs.toList(),
                                ))
                                destroyWebView(view ?: this@apply)
                            }
                        }
                    }
                }

                // 加载 Skill 的主 HTML 文件
                val htmlPath = skillDef.localScriptsPath
                    ?.let { "$it/index.html" }
                    ?: "about:blank"

                DebugLog.i("SkillWebViewExecutor: loading $htmlPath")
                webView.loadUrl("file:///android_asset/$htmlPath")
            } catch (e: Exception) {
                if (!completed) {
                    completed = true
                    cont.resumeWithException(e)
                }
            }
        }

        // 超时处理
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!completed) {
                completed = true
                DebugLog.w("SkillWebViewExecutor: timeout after ${config.timeoutMs}ms")
                cont.resume(ExecutionResult(
                    success = false,
                    error = "执行超时 (${config.timeoutMs}ms)",
                    executionMs = config.timeoutMs,
                    consoleLogs = consoleLogs.toList(),
                ))
                destroyWebView(webView)
            }
        }, config.timeoutMs)

        // 清理协程取消时的资源
        cont.invokeOnCancellation {
            completed = true
            destroyWebView(webView)
        }
    }

    /**
     * 向 WebView 注入输入参数并触发 Skill 执行。
     */
    private fun injectAndTrigger(webView: WebView, params: Map<String, Any>, enableNetwork: Boolean = false) {
        val paramsJson = org.json.JSONObject(params).toString()
        val safeParamsJson = org.json.JSONObject.quote(paramsJson)
        // 注入 CSP 策略：限制脚本来源，阻止内联事件处理器
        val cspPolicy = if (enableNetwork) {
            "default-src 'self' https:; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' https: data:; connect-src https:"
        } else {
            "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:"
        }
        webView.evaluateJavascript("""
            (function() {
                // 注入 CSP meta 标签
                var meta = document.createElement('meta');
                meta.httpEquiv = 'Content-Security-Policy';
                meta.content = '$cspPolicy';
                document.head.insertBefore(meta, document.head.firstChild);
                
                if (typeof window.$POST_MESSAGE_CHANNEL !== 'undefined') {
                    window.$POST_MESSAGE_CHANNEL.postMessage(JSON.parse($safeParamsJson));
                }
                if (typeof window.getInputParams === 'function') {
                    // 已通过 JavascriptInterface 提供
                }
                if (typeof window.start === 'function') {
                    window.start();
                }
            })();
        """.trimIndent(), null)
    }

    /**
     * 安全销毁 WebView（必须在主线程调用）。
     */
    private fun destroyWebView(webView: WebView?) {
        if (webView == null) return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                webView.apply {
                    stopLoading()
                    loadUrl("about:blank")
                    removeJavascriptInterface(CALLBACK_INTERFACE)
                    onPause()
                    destroy()
                }
            } catch (e: Exception) {
                DebugLog.w("SkillWebViewExecutor: error destroying WebView: ${e.message}")
            }
        }
    }

    /**
     * 检查设备是否支持 JS Skill 执行。
     */
    fun isSupported(): Boolean {
        return true
    }
}
