package top.hsyscn.opedrgent.mcp.skills

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import top.hsyscn.opedrgent.utils.DebugLog

/**
 * Gallery Bridge — WebView 与 Android Host 之间的标准化桥接层。
 *
 * ## 对标 Google AI Edge Gallery 的 JS 回调机制
 *
 * Gallery 标准要求 JS Skill 通过以下全局函数与宿主 App 通信：
 * - `ai_edge_gallery_get_result(data, secret?)` — 返回执行结果（必须）
 * - `ai_edge_gallery_error(message)` — 报告错误（可选）
 * - `ai_edge_gallery_progress(percent, message)` — 上报进度（可选）
 *
 * 本 Bridge 负责将这三个接口注入到 WebView 中，并收集回调数据。
 *
 * ## 结果格式标准
 *
 * ### 成功结果（JSON）
 * ```json
 * {
 *   "result": "文本结果或 Markdown",
 *   "image": { "base64": "..." },
 *   "webview": { "url": "...", "aspectRatio": 1.333 }
 * }
 * ```
 *
 * ### 错误结果
 * ```json
 * {
 *   "error": "错误描述信息"
 * }
 * ```
 *
 * ## 使用方式
 * ```
 * val bridge = GalleryBridge(context)
 * bridge.injectInto(webView) { result -> handleResult(result) }
 * // WebView 加载完成后 JS 自动调用 ai_edge_gallery_get_result()
 * ```
 *
 * @see SkillWebViewExecutor 底层执行器（本 Bridge 是其上层封装）
 */
class GalleryBridge(private val context: Context) {

    companion object {
        private const val TAG = "GalleryBridge"

        /** JS 回调接口名（必须与 HTML 中的调用一致） */
        const val BRIDGE_INTERFACE = "AndroidSkillHost"

        /** 标准：结果回调函数名 */
        const val CALLBACK_GET_RESULT = "ai_edge_gallery_get_result"

        /** 标准：错误回调函数名 */
        const val CALLBACK_ERROR = "ai_edge_gallery_error"

        /** 扩展：进度回调函数名 */
        const val CALLBACK_PROGRESS = "ai_edge_gallery_progress"
    }

    /**
     * 回调事件类型（密封类，覆盖所有可能的 JS → Native 通信）。
     */
    sealed class BridgeEvent {
        /**
         * Skill 执行成功，返回结果。
         *
         * @param rawData JS 返回的原始 JSON 字符串
         * @param parsedResult 解析后的结构化结果
         */
        data class Result(
            val rawData: String,
            val parsedResult: GalleryResult,
        ) : BridgeEvent()

        /**
         * Skill 执行出错。
         *
         * @param message 错误信息
         * @param code 可选的错误码
         */
        data class Error(
            val message: String,
            val code: Int? = null,
        ) : BridgeEvent()

        /**
         * Skill 执行进度更新。
         *
         * @param percent 进度百分比（0-100）
         * @param message 进度描述文字
         */
        data class Progress(
            val percent: Int,
            val message: String = "",
        ) : BridgeEvent()
    }

    /**
     * 标准化的 Skill 结果结构。
     *
     * 支持 Gallery 规定的三种返回类型：
     * - **文本/Markdown**：result 字段
     * - **HTML**：html 字段
     * - **图片**：image.base64 字段
     * - **交互视图**：webview 字段
     */
    data class GalleryResult(
        /** 文本 / Markdown 结果 */
        val result: String = "",
        /** HTML 格式结果（用于富文本渲染） */
        val html: String = "",
        /** 图片（Base64 编码） */
        val image: ImageData? = null,
        /** 交互视图（嵌入聊天中的 WebView） */
        val webview: WebviewData? = null,
        /** 原始 JSON（调试用） */
        val rawJson: String = "",
    ) {
        /** 是否有有效内容 */
        val hasContent: Boolean get() =
            result.isNotBlank() || html.isNotBlank() || image != null || webview != null

        /** 结果类型判断 */
        val resultType: ResultType get() = when {
            html.isNotBlank() -> ResultType.HTML
            image != null -> ResultType.IMAGE
            webview != null -> ResultType.WEBVIEW
            else -> ResultType.TEXT
        }
    }

    /** 结果类型枚举 */
    enum class ResultType { TEXT, HTML, IMAGE, WEBVIEW }

    /** 图片数据 */
    data class ImageData(
        val base64: String,
        val mimeType: String = "image/png",
        val altText: String = "",
    )

    /** Webview 数据 */
    data class WebviewData(
        val url: String,
        val aspectRatio: Float = 1.333f,
    )

    /**
     * 将 Bridge 接口注入到指定 WebView。
     *
     * 注入后，JS 代码可通过以下方式通信：
     * ```javascript
     * // 返回结果
     * AndroidSkillHost.postResult(JSON.stringify({ result: "hello" }));
     *
     * // 报告错误
     * AndroidSkillHost.postError("Something went wrong");
     *
     * // 上报进度
     * AndroidSkillHost.postProgress(50, "Processing...");
     * ```
     *
     * @param webView 目标 WebView
     * @param onEvent 事件回调（Result / Error / Progress）
     * @param secretValue 可选的 Secret 值（传递给需要 secret 的 Skill）
     */
    fun injectInto(
        webView: WebView,
        onEvent: (BridgeEvent) -> Unit,
        secretValue: String? = null,
    ) {
        webView.addJavascriptInterface(object : Any() {

            /**
             * JS 调用：postResult(resultJson)
             * 对应 Gallery 标准的 ai_edge_gallery_get_result() 返回路径。
             */
            @JavascriptInterface
            fun postResult(resultJson: String) {
                DebugLog.i("$TAG: postResult received (${resultJson.take(100)}...)")

                try {
                    val jsonObj = JSONObject(resultJson)
                    val parsed = parseGalleryResult(jsonObj, resultJson)

                    onEvent(BridgeEvent.Result(rawData = resultJson, parsedResult = parsed))
                } catch (e: Exception) {
                    DebugLog.w("$TAG: 解析结果 JSON 失败，使用原始文本: ${e.message}")
                    // 降级：如果解析失败，把整个字符串作为纯文本结果
                    onEvent(BridgeEvent.Result(
                        rawData = resultJson,
                        parsedResult = GalleryResult(result = resultJson, rawJson = resultJson),
                    ))
                }
            }

            /**
             * JS 调用：postError(message)
             * 对应 Gallery 标准的 ai_edge_gallery_error()。
             */
            @JavascriptInterface
            fun postError(message: String) {
                DebugLog.w("$TAG: postError: $message")
                onEvent(BridgeEvent.Error(message = message))
            }

            /**
             * JS 调用：postProgress(percent, message?)
             * 对应扩展的 ai_edge_gallery_progress()。
             *
             * @param percentInt 进度百分比（整数 0-100）
             * @param message 可选的进度描述
             */
            @JavascriptInterface
            fun postProgress(percentInt: Int, message: String? = null) {
                val percent = percentInt.coerceIn(0, 100)
                DebugLog.d("$TAG: postProgress: $percent% ${message.orEmpty()}")
                onEvent(BridgeEvent.Progress(percent = percent, message = message ?: ""))
            }

            /**
             * JS 调用：getSecret()
             * 返回宿主注入的 Secret 值（仅对 require-secret: true 的 Skill 有效）。
             *
             * @return Secret 字符串，无则返回空字符串
             */
            @JavascriptInterface
            fun getSecret(): String {
                val secret = secretValue ?: ""
                if (secret.isNotEmpty()) {
                    DebugLog.d("$TAG: getSecret: 返回 Secret（长度: ${secret.length}）")
                } else {
                    DebugLog.w("$TAG: getSecret: 无可用 Secret")
                }
                return secret
            }

            /**
             * JS 调用：getInputParams()
             * 返回输入参数 JSON（兼容旧版 SkillWebViewExecutor 接口）。
             */
            @JavascriptInterface
            fun getInputParams(): String {
                return "{}"  // 由外部通过 evaluateJavascript 单独注入
            }

            /**
             * JS 调用：log(message)
             * 控制台日志桥接（开发调试用）。
             */
            @JavascriptInterface
            fun log(message: String) {
                DebugLog.d("$TAG[JS]: $message")
            }

        }, BRIDGE_INTERFACE)

        DebugLog.d("$TAG: Bridge 接口已注入到 WebView ($BRIDGE_INTERFACE)")
    }

    /**
     * 生成注入到 WebView 的 JS 桥接初始化脚本。
     *
     * 此脚本在 WebView 页面加载完成后执行，为 JS 侧提供
     * 兼容 Gallery 标准的全局函数包装：
     *
     * ```javascript
     * window.ai_edge_gallery_get_result = function(data, secret) {
     *     AndroidSkillHost.postResult(JSON.stringify({...}));
     * };
     * window.ai_edge_gallery_error = function(msg) {
     *     AndroidSkillHost.postError(msg);
     * };
     * window.ai_edge_gallery_progress = function(pct, msg) {
     *     AndroidSkillHost.postProgress(pct, msg);
     * };
     * ```
     *
     * @return JavaScript 代码字符串
     */
    fun generateBridgeBootstrapScript(): String {
        return """
            (function() {
                'use strict';
                
                // ── 标准：结果回调 ──
                if (typeof window.$CALLBACK_GET_RESULT === 'undefined') {
                    window.$CALLBACK_GET_RESULT = function(data, secret) {
                        try {
                            var resultObj = {};
                            if (typeof data === 'string') {
                                try { resultObj = JSON.parse(data); } catch(e) { resultObj = { result: data }; }
                            } else if (typeof data === 'object') {
                                resultObj = data;
                            } else {
                                resultObj = { result: String(data) };
                            }
                            
                            // 如果有 secret 参数，附加到结果中（不暴露给 LLM）
                            if (secret && typeof secret === 'string') {
                                resultObj._hasSecret = true;
                            }
                            
                            $BRIDGE_INTERFACE.postResult(JSON.stringify(resultObj));
                        } catch(e) {
                            $BRIDGE_INTERFACE.postError('GalleryBridge callback error: ' + e.message);
                        }
                    };
                }
                
                // ── 标准：错误回调 ──
                if (typeof window.$CALLBACK_ERROR === 'undefined') {
                    window.$CALLBACK_ERROR = function(message) {
                        $BRIDGE_INTERFACE.postError(String(message || 'Unknown error'));
                    };
                }
                
                // ── 扩展：进度回调 ──
                if (typeof window.$CALLBACK_PROGRESS === 'undefined') {
                    window.$CALLBACK_PROGRESS = function(percent, message) {
                        var pct = parseInt(percent, 10);
                        if (isNaN(pct)) pct = 0;
                        pct = Math.max(0, Math.min(100, pct));
                        $BRIDGE_INTERFACE.postProgress(pct, String(message || ''));
                    };
                }
                
                console.log('[GalleryBridge] 桥接层已初始化');
            })();
        """.trimIndent()
            .replace("\$CALLBACK_GET_RESULT", CALLBACK_GET_RESULT)
            .replace("\$CALLBACK_ERROR", CALLBACK_ERROR)
            .replace("\$CALLBACK_PROGRESS", CALLBACK_PROGRESS)
            .replace("\$BRIDGE_INTERFACE", BRIDGE_INTERFACE)
    }

    // ==================== 内部解析方法 ====================

    /**
     * 将 JSONObject 解析为标准化的 [GalleryResult]。
     *
     * 按照 Gallery 规范提取以下字段：
     * - `result` / `text` → 文本内容
     * - `html` → HTML 富文本
     * - `image` / `image.base64` → 图片
     * - `webview` / `webview.url` → 交互视图
     * - `error` → 错误信息（如果有 error 字段，优先当 Error 处理）
     */
    internal fun parseGalleryResult(json: JSONObject, rawJson: String): GalleryResult {
        // 先检查是否是错误响应
        val errorMsg = json.optString("error", "").ifBlank { json.optString("errorMessage", "") }
        if (errorMsg.isNotBlank()) {
            return GalleryResult(rawJson = rawJson)
        }

        // 提取文本结果（兼容 result / text 两种字段名）
        val textResult = json.optString("result", "").ifBlank { json.optString("text", "") }

        // 提取 HTML 结果
        val htmlResult = json.optString("html", "")

        // 提取图片数据
        val imageData = json.optJSONObject("image")?.let { imgObj ->
            val base64 = imgObj.optString("base64", "")
                .ifBlank { imgObj.optString("data", "") }
            if (base64.isNotBlank()) {
                ImageData(
                    base64 = base64,
                    mimeType = imgObj.optString("mimeType", "image/png")
                        .ifBlank { imgObj.optString("type", "image/png") },
                    altText = imgObj.optString("alt", ""),
                )
            } else null
        }

        // 提取 Webview 数据
        val webviewData = json.optJSONObject("webview")?.let { wvObj ->
            val url = wvObj.optString("url", "")
            if (url.isNotBlank()) {
                WebviewData(
                    url = url,
                    aspectRatio = wvObj.optDouble("aspectRatio", 1.333).toFloat(),
                )
            } else null
        }

        return GalleryResult(
            result = textResult,
            html = htmlResult,
            image = imageData,
            webview = webviewData,
            rawJson = rawJson,
        )
    }

    /**
     * 将 [GalleryResult] 序列化为人类可读的摘要文本。
     *
     * 用于 ToolResult 的 output 字段展示。
     */
    fun formatResultForDisplay(result: GalleryResult): String {
        return buildString {
            when (result.resultType) {
                GalleryResult.ResultType.TEXT -> {
                    if (result.result.isNotBlank()) append(result.result)
                }
                GalleryResult.ResultType.HTML -> {
                    append("[HTML 内容已生成 — ${result.html.length} 字符]")
                }
                GalleryResult.ResultType.IMAGE -> {
                    val img = result.image!!
                    append("[图片已生成 — ${img.base64.length} 字符 Base64, 类型: ${img.mimeType}]")
                    if (img.altText.isNotBlank()) append(" (${img.altText})")
                }
                GalleryResult.ResultType.WEBVIEW -> {
                    val wv = result.webview!!
                    append("[交互视图已生成: ${wv.url}")
                    if (wv.aspectRatio != 1.333f) append(", 宽高比: ${wv.aspectRatio}")
                }
            }
        }.trim()
    }
}
