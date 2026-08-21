package top.hsyscn.opedrgent.network

import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class WebSearchResult(
    val title: String = "",
    val url: String = "",
    val snippet: String = "",
)

data class WebFetchResult(
    val title: String,
    val url: String,
    val text: String,
    val screenshotBase64: String? = null,
)

enum class WebAgentMethod {
    QUERY_SEARCH,
    WEB_MCP,
    SCREENSHOT_MULTIMODAL,
}

class WebViewAgent(context: Context) {
    private val appContext = context.applicationContext

    private var webView: WebView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isInitialized = false

    data class JsBridgeResult(val result: String?)

    @Suppress("unused")
    private inner class JsBridge {
        val lastResult = AtomicReference<JsBridgeResult>(null)

        @JavascriptInterface
        fun postResult(json: String) {
            lastResult.set(JsBridgeResult(json))
            DebugLog.d("WebViewAgent JS bridge received: ${json.take(200)}")
        }
    }

    private val jsBridge = JsBridge()

    @Suppress("unused")
    private inner class LoadingState {
        var pageLoaded = false
        var networkIdle = false
        var title = ""
        var text = ""
    }

    suspend fun ensureInitialized() = withContext(Dispatchers.Main) {
        if (isInitialized && webView != null) return@withContext
        webView?.destroy()
        webView = WebView(appContext).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                loadWithOverviewMode = true
                useWideViewPort = true
            }
            addJavascriptInterface(jsBridge, "OpedrgentBridge")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    val s = view?.tag as? LoadingState
                    if (s != null) s.pageLoaded = true
                }
                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError) {
                    DebugLog.w("WebViewAgent SSL error: ${error.primaryError} for ${error.url}, cancelling")
                    handler.cancel()
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: String, lineNumber: Int, sourceID: String) {
                    DebugLog.d("WebView console: $message at line $lineNumber in $sourceID")
                }
            }
            tag = LoadingState()
        }
        isInitialized = true
        DebugLog.i("WebViewAgent initialized")
    }

    suspend fun searchQuery(
        query: String,
        maxResults: Int = 5,
        timeoutMs: Long = 20000L,
    ): List<WebSearchResult> = withContext(Dispatchers.IO) {
        ensureInitialized()
        val deferred = CompletableDeferred<List<WebSearchResult>>()
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "https://cn.bing.com/search?q=$encodedQuery&count=$maxResults"

        DebugLog.i("WebViewAgent.searchQuery: $query → bing")

        mainHandler.post {
            webView?.loadUrl(searchUrl)
        }

        var attempts = 0
        val maxAttempts = (timeoutMs / 1000).toInt().coerceAtLeast(5)

        while (!deferred.isCompleted && attempts < maxAttempts) {
            delay(1000)
            attempts++
            mainHandler.post {
                webView?.evaluateJavascript("""
                    (function() {
                        var results = [];
                        var items = document.querySelectorAll('.b_algo, .b_ans, li[class*=b_algo]');
                        for (var i = 0; i < Math.min(items.length, $maxResults); i++) {
                            var item = items[i];
                            var aEl = item.querySelector('h2 a') || item.querySelector('a[href^="http"]');
                            var title = '';
                            var url = '';
                            if (aEl) {
                                title = aEl.textContent.trim();
                                url = aEl.href || '';
                            } else {
                                var h2 = item.querySelector('h2');
                                if (h2) title = h2.textContent.trim();
                            }
                            var snippetEl = item.querySelector('.b_caption p, .b_snippet, .b_algoSlug, p');
                            var snippet = snippetEl ? snippetEl.textContent.trim() : '';
                            if (title && url && url.indexOf('bing.com/search') === -1) {
                                results.push({ title: title, url: url, snippet: snippet });
                            }
                        }
                        OpedrgentBridge.postResult(JSON.stringify(results));
                    })()
                """.trimIndent()) { _ -> }
            }

            delay(500)
            val raw = jsBridge.lastResult.getAndSet(null)?.result
            if (raw != null && raw != "null" && raw != "[]") {
                try {
                    val parsed = org.json.JSONArray(raw)
                    val list = mutableListOf<WebSearchResult>()
                    for (i in 0 until parsed.length()) {
                        val obj = parsed.getJSONObject(i)
                        val title = obj.optString("title", "").trim()
                        val url = obj.optString("url", "").trim()
                        val snippet = obj.optString("snippet", "").trim()
                        if (title.isNotEmpty()) {
                            list.add(WebSearchResult(title = title, url = url, snippet = snippet))
                        }
                    }
                    if (list.isNotEmpty()) {
                        DebugLog.i("WebViewAgent.searchQuery: ${list.size} results")
                        deferred.complete(list)
                    }
                } catch (_: Exception) {}
            }
        }

        if (!deferred.isCompleted) {
            DebugLog.w("WebViewAgent.searchQuery: timeout after ${attempts}s")
            deferred.complete(emptyList())
        }
        deferred.await()
    }

    suspend fun fetchUrl(
        url: String,
        timeoutMs: Long = 25000L,
    ): WebFetchResult? = withContext(Dispatchers.IO) {
        ensureInitialized()
        val deferred = CompletableDeferred<WebFetchResult?>()
        val state = (webView?.tag as? LoadingState) ?: LoadingState().also { webView?.tag = it }
        state.pageLoaded = false
        state.title = ""
        state.text = ""

        DebugLog.i("WebViewAgent.fetchUrl: $url")

        withContext(Dispatchers.Main) {
            webView?.loadUrl(url)
        }

        val startTime = System.currentTimeMillis()
        val maxWait = timeoutMs - 2000

        while (!deferred.isCompleted && (System.currentTimeMillis() - startTime) < maxWait) {
            if (state.pageLoaded) {
                delay(2000)

                val captured = CompletableDeferred<String>()
                val titleRef = CompletableDeferred<String>()
                withContext(Dispatchers.Main) {
                    webView?.evaluateJavascript("(function(){ return document.title; })()") { r ->
                        titleRef.complete(r?.trim('"').orEmpty())
                    }
                    webView?.evaluateJavascript("""
                        (function() {
                            var el = document.body;
                            if (!el) return '';
                            var clone = el.cloneNode(true);
                            var navs = clone.querySelectorAll('nav, header, footer, aside, script, style, noscript');
                            navs.forEach(function(n){ n.remove(); });
                            var text = clone.innerText || clone.textContent || '';
                            return text.replace(/\s+/g, ' ').trim();
                        })()
                    """.trimIndent()) { r ->
                        captured.complete(r?.trim('"').orEmpty())
                    }
                }

                val text = captured.await()
                val title = titleRef.await()

                if (text.isNotEmpty() && text.length > 100) {
                    DebugLog.i("WebViewAgent.fetchUrl: got ${text.length} chars after page load")
                    deferred.complete(WebFetchResult(title = title.ifEmpty { url }, url = url, text = text))
                    break
                }
            }
            delay(500)
        }

        if (!deferred.isCompleted) {
            DebugLog.w("WebViewAgent.fetchUrl: timeout, returning partial ${state.text.length} chars")
            if (state.text.isNotEmpty()) {
                deferred.complete(WebFetchResult(title = state.title.ifEmpty { url }, url = url, text = state.text))
            } else {
                deferred.complete(null)
            }
        }
        deferred.await()
    }

    suspend fun executeMcpScript(
        url: String,
        script: String,
        timeoutMs: Long = 30000L,
    ): String? = withContext(Dispatchers.IO) {
        ensureInitialized()
        val deferred = CompletableDeferred<String?>()

        DebugLog.i("WebViewAgent.executeMcpScript: $url")

        mainHandler.post { webView?.loadUrl(url) }

        var attempts = 0
        val maxAttempts = (timeoutMs / 1500).toInt().coerceAtLeast(10)

        while (!deferred.isCompleted && attempts < maxAttempts) {
            delay(1500)
            attempts++
            mainHandler.post {
                webView?.evaluateJavascript(script) { result ->
                    if (result != null && result != "null" && result != "undefined") {
                        val cleaned = result.trim('"').ifEmpty { result }
                        if (cleaned.length > 2) {
                            DebugLog.i("WebViewAgent.executeMcpScript: result=${cleaned.take(200)}")
                            deferred.complete(cleaned)
                        }
                    }
                }
            }
            delay(500)
        }

        if (!deferred.isCompleted) {
            DebugLog.w("WebViewAgent.executeMcpScript: timeout after ${attempts * 1.5}s")
            deferred.complete(null)
        }
        deferred.await()
    }

    suspend fun takeScreenshot(): String? = withContext(Dispatchers.Main) {
        ensureInitialized()
        val deferred = CompletableDeferred<String?>()
        val latch = CountDownLatch(1)
        var bitmapRef: Bitmap? = null

        webView?.setPictureListener { _, picture ->
            if (picture != null) {
                try {
                    bitmapRef = Bitmap.createBitmap(picture.width, picture.height, Bitmap.Config.ARGB_8888)
                    bitmapRef.let { canvas -> picture.draw(android.graphics.Canvas(canvas)) }
                } catch (_: Exception) {}
            }
            latch.countDown()
        }

        webView?.invalidate()
        latch.await(3, TimeUnit.SECONDS)
        webView?.setPictureListener(null)

        val bitmap = bitmapRef
        if (bitmap == null) {
            DebugLog.w("WebViewAgent.takeScreenshot: bitmap is null, trying draw cache")
            try {
                val wv = webView ?: return@withContext null
                val bmp = Bitmap.createBitmap(wv.width, wv.height, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = android.graphics.Canvas(bmp)
                    wv.draw(canvas)
                    val stream = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.PNG, 80, stream)
                    val base64 = android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
                    DebugLog.i("WebViewAgent.takeScreenshot: ${base64.length} chars base64 (drawCache)")
                    deferred.complete(base64)
                } finally {
                    if (!bmp.isRecycled) bmp.recycle()
                }
            } catch (e: Exception) {
                DebugLog.e("WebViewAgent.takeScreenshot failed: ${e.message}", e)
                deferred.complete(null)
            }
        } else {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 80, stream)
            val base64 = android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
            bitmap.recycle()
            DebugLog.i("WebViewAgent.takeScreenshot: ${base64.length} chars base64")
            deferred.complete(base64)
        }
        deferred.await()
    }

    suspend fun multimodalClick(
        query: String,
        url: String,
        llm: LlmClient,
        config: top.hsyscn.opedrgent.settings.ApiConfig,
        systemPrompt: String,
        maxRounds: Int = 5,
    ): String = withContext(Dispatchers.IO) {
        ensureInitialized()
        val sb = StringBuilder()
        sb.appendLine("[多模态虚拟点击] 目标：$query")
        sb.appendLine("[多模态虚拟点击] 起始URL：$url")

        mainHandler.post { webView?.loadUrl(url) }
        delay(3000)

        for (round in 1..maxRounds) {
            sb.appendLine("\n--- 第 $round 轮 ---")

            val screenshot = takeScreenshot()
            if (screenshot == null) {
                sb.appendLine("截图失败，停止")
                break
            }
            sb.appendLine("截图已获取 (${screenshot.length} chars base64)")

            val prompt = buildString {
                appendLine("你正在通过截图操作一个网页。当前任务：$query")
                appendLine("请分析截图并决定下一步操作。")
                appendLine("可用操作：")
                append("- CLICK(x,y) — 点击坐标（相对位置 0-1000）")
                append("- SCROLL_DOWN — 向下滚动")
                append("- SCROLL_UP — 向上滚动")
                append("- TYPE(text) — 在输入框输入文本")
                append("- EXTRACT_TEXT — 提取页面关键文字")
                append("- DONE — 任务完成")
                append("\n请只输出一个操作指令，格式如 CLICK(500,200)")
            }

            val messages = listOf(
                top.hsyscn.opedrgent.model.ChatMessage(
                    role = top.hsyscn.opedrgent.model.Role.USER,
                    content = "data:image/png;base64,$screenshot\n\n$prompt",
                    createdAt = System.currentTimeMillis(),
                ),
            )

            val action = runCatching {
                llm.chatCompletions(config = config, system = systemPrompt, messages = messages)
            }.getOrNull()?.trim().orEmpty()

            sb.appendLine("LLM 指令：$action")

            when {
                action.startsWith("CLICK(", true) -> {
                    val coords = Regex("""\((\d+)[,\s]+(\d+)\)""").find(action)?.groupValues
                    if (coords != null && coords.size >= 3) {
                        val x = coords[1].toFloat() / 1000f * (webView?.width?.toFloat() ?: 1080f)
                        val y = coords[2].toFloat() / 1000f * (webView?.height?.toFloat() ?: 1920f)
                        mainHandler.post {
                            webView?.dispatchTouchEvent(
                                android.view.MotionEvent.obtain(
                                    System.currentTimeMillis(),
                                    System.currentTimeMillis(),
                                    android.view.MotionEvent.ACTION_DOWN,
                                    x, y, 0,
                                ),
                            )
                            webView?.dispatchTouchEvent(
                                android.view.MotionEvent.obtain(
                                    System.currentTimeMillis(),
                                    System.currentTimeMillis(),
                                    android.view.MotionEvent.ACTION_UP,
                                    x, y, 0,
                                ),
                            )
                        }
                        sb.appendLine("执行点击：($x, $y)")
                    }
                }
                action.startsWith("SCROLL_DOWN", true) -> {
                    mainHandler.post { webView?.scrollBy(0, 800) }
                    sb.appendLine("执行向下滚动")
                }
                action.startsWith("SCROLL_UP", true) -> {
                    mainHandler.post { webView?.scrollBy(0, -800) }
                    sb.appendLine("执行向上滚动")
                }
                action.startsWith("TYPE(", true) -> {
                    val text = action.removePrefix("type ").removePrefix("TYPE ")
                        .removePrefix("(").removeSuffix(")").trim()
                    mainHandler.post {
                        webView?.evaluateJavascript("document.activeElement.value += '$text';") {}
                    }
                    sb.appendLine("执行输入：$text")
                }
                action.startsWith("EXTRACT_TEXT", true) -> {
                    val textRef = AtomicReference("")
                    mainHandler.post {
                        webView?.evaluateJavascript("""
                            (document.body ? document.body.innerText.substring(0, 3000) : '')
                        """) { r -> textRef.set(r?.trim('"').orEmpty()) }
                    }
                    delay(1000)
                    sb.appendLine("提取文本：${textRef.get().take(500)}")
                }
                action.startsWith("DONE", true) || action.startsWith("完成", true) -> {
                    sb.appendLine("LLM 判定任务完成")
                    break
                }
                else -> {
                    sb.appendLine("无法识别的指令，尝试提取页面文本")
                    val textRef = AtomicReference("")
                    mainHandler.post {
                        webView?.evaluateJavascript("""
                            (document.body ? document.body.innerText.substring(0, 3000) : '')
                        """) { r -> textRef.set(r?.trim('"').orEmpty()) }
                    }
                    delay(1000)
                    sb.appendLine("页面文本：${textRef.get().take(500)}")
                }
            }

            delay(2000)
        }

        sb.toString()
    }

    // ── CDP 等价能力层 ────────────────────────────────────────────

    /** 底层 JS 执行：注入代码，返回原始字符串结果 */
    private suspend fun evalJs(script: String): String? = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<String?>()
        webView?.evaluateJavascript(script) { r ->
            val cleaned = r?.trim('"')?.replace("\\\"", "\"")
                ?.replace("\\n", "\n")?.replace("\\/", "/")
            deferred.complete(if (cleaned == "null" || cleaned == "undefined") null else cleaned)
        }
        withTimeoutOrNull(10000L) { deferred.await() }
    }

    /**
     * 导航到 URL 并等待页面加载完成
     * 对标 CDP Page.navigate + Page.loadEventFired
     */
    suspend fun navigate(url: String, waitMs: Long = 2000L): Boolean {
        ensureInitialized()
        val state = (webView?.tag as? LoadingState) ?: LoadingState().also { webView?.tag = it }
        state.pageLoaded = false
        withContext(Dispatchers.Main) { webView?.loadUrl(url) }
        val deadline = System.currentTimeMillis() + 30000L
        while (!state.pageLoaded && System.currentTimeMillis() < deadline) {
            delay(200)
        }
        if (waitMs > 0) delay(waitMs)
        return state.pageLoaded
    }

    /**
     * DOM 查询：返回匹配元素的文本/属性/HTML
     * 对标 CDP DOM.querySelector + DOM.getOuterHTML
     */
    suspend fun querySelector(selector: String): Map<String, String>? {
        val json = evalJs("""
            (function(){
                var el = document.querySelector('$selector');
                if (!el) return null;
                return JSON.stringify({
                    text: (el.innerText||'').substring(0,2000),
                    html: el.outerHTML.substring(0,5000),
                    tag: el.tagName,
                    id: el.id||'',
                    className: el.className||'',
                    href: el.href||'',
                    src: el.src||'',
                    value: el.value||'',
                    type: el.type||'',
                    name: el.name||'',
                    checked: el.checked ? 'true' : 'false',
                    rect: JSON.stringify(el.getBoundingClientRect())
                });
            })()
        """.trimIndent()) ?: return null
        return try {
            org.json.JSONObject(json).let { obj ->
                obj.keys().asSequence().associateWith { obj.optString(it) }
            }
        } catch (_: Exception) { null }
    }

    /**
     * DOM 批量查询：返回所有匹配元素的基本信息
     * 对标 CDP DOM.querySelectorAll
     */
    suspend fun querySelectorAll(selector: String, limit: Int = 50): List<Map<String, String>> {
        val json = evalJs("""
            (function(){
                var els = document.querySelectorAll('$selector');
                var out = [];
                for (var i = 0; i < Math.min(els.length, $limit); i++) {
                    var el = els[i];
                    out.push({
                        text: (el.innerText||'').substring(0,500),
                        tag: el.tagName,
                        id: el.id||'',
                        className: el.className||'',
                        href: el.href||'',
                        value: el.value||'',
                        index: i
                    });
                }
                return JSON.stringify(out);
            })()
        """.trimIndent()) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                obj.keys().asSequence().associateWith { obj.optString(it) }
            }
        } catch (_: Exception) { emptyList() }
    }

    /**
     * 点击元素（CSS 选择器）
     * 对标 CDP Input.dispatchMouseEvent
     */
    suspend fun click(selector: String): Boolean {
        val result = evalJs("""
            (function(){
                var el = document.querySelector('$selector');
                if (!el) return 'not_found';
                el.scrollIntoView({behavior:'smooth', block:'center'});
                el.click();
                return 'clicked';
            })()
        """.trimIndent())
        return result == "clicked"
    }

    /**
     * 填写表单字段
     * 对标 CDP Runtime.evaluate + Input.dispatchKeyEvent
     */
    suspend fun type(selector: String, text: String, clear: Boolean = true): Boolean {
        val escaped = text.replace("'", "\\'").replace("\n", "\\n")
        val result = evalJs("""
            (function(){
                var el = document.querySelector('$selector');
                if (!el) return 'not_found';
                el.focus();
                ${if (clear) "el.value = '';" else ""}
                el.value += '$escaped';
                el.dispatchEvent(new Event('input', {bubbles:true}));
                el.dispatchEvent(new Event('change', {bubbles:true}));
                return 'typed';
            })()
        """.trimIndent())
        return result == "typed"
    }

    /**
     * 提交表单
     * 对标 CDP Runtime.evaluate
     */
    suspend fun submitForm(selector: String = "form"): Boolean {
        val result = evalJs("""
            (function(){
                var el = document.querySelector('$selector');
                if (!el) return 'not_found';
                if (el.tagName === 'FORM') { el.submit(); return 'submitted'; }
                var form = el.closest('form');
                if (form) { form.submit(); return 'submitted'; }
                return 'no_form';
            })()
        """.trimIndent())
        return result == "submitted"
    }

    /**
     * 选择下拉框选项
     */
    suspend fun selectOption(selector: String, value: String): Boolean {
        val escaped = value.replace("'", "\\'")
        val result = evalJs("""
            (function(){
                var el = document.querySelector('$selector');
                if (!el || el.tagName !== 'SELECT') return 'not_found';
                el.value = '$escaped';
                el.dispatchEvent(new Event('change', {bubbles:true}));
                return 'selected';
            })()
        """.trimIndent())
        return result == "selected"
    }

    /**
     * 勾选/取消勾选复选框
     */
    suspend fun check(selector: String, checked: Boolean = true): Boolean {
        val result = evalJs("""
            (function(){
                var el = document.querySelector('$selector');
                if (!el) return 'not_found';
                el.checked = $checked;
                el.dispatchEvent(new Event('change', {bubbles:true}));
                return 'done';
            })()
        """.trimIndent())
        return result == "done"
    }

    /**
     * 滚动页面
     * 对标 CDP Input.dispatchMouseEvent (scroll)
     */
    suspend fun scroll(direction: String = "down", amount: Int = 800): Boolean {
        val dy = if (direction.equals("up", true)) -amount else amount
        evalJs("window.scrollBy(0, $dy);")
        return true
    }

    /**
     * 等待元素出现（轮询模式）
     * 对标 CDP DOM.setDocumentContent + MutationObserver
     */
    suspend fun waitForSelector(
        selector: String,
        timeoutMs: Long = 10000L,
        pollIntervalMs: Long = 300L,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val exists = evalJs("""
                (function(){ return document.querySelector('$selector') ? 'yes' : 'no'; })()
            """.trimIndent())
            if (exists == "yes") return true
            delay(pollIntervalMs)
        }
        DebugLog.w("WebViewAgent.waitForSelector: timeout for $selector")
        return false
    }

    /**
     * 等待页面导航完成
     */
    suspend fun waitForNavigation(timeoutMs: Long = 15000L): Boolean {
        val state = (webView?.tag as? LoadingState) ?: return false
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!state.pageLoaded && System.currentTimeMillis() < deadline) {
            delay(200)
        }
        delay(500)
        return state.pageLoaded
    }

    /**
     * 获取当前页面信息
     * 对标 CDP Page.getFrameTree + Page.getResourceTree
     */
    suspend fun getPageInfo(): Map<String, String> {
        val url = evalJs("(function(){return location.href;})()") ?: ""
        val title = evalJs("(function(){return document.title;})()") ?: ""
        val readyState = evalJs("(function(){return document.readyState;})()") ?: ""
        return mapOf("url" to url, "title" to title, "readyState" to readyState)
    }

    /**
     * 获取 Cookie
     * 对标 CDP Network.getCookies
     */
    suspend fun getCookies(): String {
        return evalJs("(function(){return document.cookie;})()") ?: ""
    }

    /**
     * 获取 localStorage
     * 对标 CDP DOMStorage.getDOMStorageItems
     */
    suspend fun getLocalStorage(key: String? = null): String? {
        return if (key != null) {
            evalJs("(function(){return localStorage.getItem('$key');})()")
        } else {
            evalJs("""
                (function(){
                    var out = {};
                    for (var i = 0; i < localStorage.length; i++) {
                        var k = localStorage.key(i);
                        out[k] = localStorage.getItem(k);
                    }
                    return JSON.stringify(out);
                })()
            """.trimIndent())
        }
    }

    /**
     * 设置 localStorage
     */
    suspend fun setLocalStorage(key: String, value: String): Boolean {
        val ek = key.replace("'", "\\'")
        val ev = value.replace("'", "\\'")
        evalJs("localStorage.setItem('$ek','$ev');")
        return true
    }

    /**
     * 执行任意 JS 并返回结构化结果
     * 对标 CDP Runtime.evaluate
     */
    suspend fun evaluate(expression: String): String? = evalJs(expression)

    /**
     * 获取页面完整文本内容（去噪后）
     * 对标 CDP DOM.getDocument + 内容提取
     */
    suspend fun getPageText(maxLength: Int = 5000): String {
        return evalJs("""
            (function(){
                var body = document.body;
                if (!body) return '';
                var clone = body.cloneNode(true);
                clone.querySelectorAll('script,style,noscript,nav,footer,header,aside').forEach(function(n){n.remove()});
                return (clone.innerText||'').substring(0, $maxLength);
            })()
        """.trimIndent()) ?: ""
    }

    /**
     * 获取元素的截图（指定区域）
     * 对标 CDP Page.captureScreenshot (clip)
     */
    suspend fun screenshotElement(selector: String): String? {
        return evalJs("""
            (function(){
                var el = document.querySelector('$selector');
                if (!el) return null;
                var rect = el.getBoundingClientRect();
                return JSON.stringify({x:rect.x, y:rect.y, w:rect.width, h:rect.height, top:rect.top, left:rect.left});
            })()
        """.trimIndent())
    }

    /**
     * 获取网络请求日志（需要配合 WebViewClient 拦截）
     * 对标 CDP Network.requestWillBeSent
     */
    private val interceptedRequests = mutableListOf<String>()
    fun getInterceptedRequests(): List<String> = interceptedRequests.toList()

    // ── 智能快照 (Smart Snapshot) ───────────────────────────────
    // 对标 agent-browser snapshot: 扫描所有可交互元素，生成 @e1 @e2 编号引用
    // AI 只需说 "click @3" 而不用猜 CSS 选择器

    data class SnapshotElement(
        val ref: Int,           // @e1, @e2 ...
        val tag: String,        // button, a, input, select, textarea, ...
        val text: String,       // 可见文本 (截断)
        val type: String,       // input type / role
        val placeholder: String,
        val href: String,
        val name: String,
        val value: String,
        val enabled: Boolean,
        val rect: String,       // 位置信息
    )

    /**
     * 智能快照：扫描页面所有可交互元素，生成编号列表
     * 对标 agent-browser / Stagehand 的 snapshot 机制
     *
     * 返回格式：List<SnapshotElement>，每个元素有 @ref 编号
     * AI 用 @ref 号来指定操作目标，无需知道 CSS 选择器
     */
    suspend fun smartSnapshot(maxElements: Int = 100): List<SnapshotElement> {
        val json = evalJs("""
            (function(){
                var sels = 'a,button,input,select,textarea,[role="button"],[role="link"],[role="tab"],[role="menuitem"],[role="option"],[onclick],[tabindex]:not([tabindex="-1"]),summary,details,label[for]';
                var els = document.querySelectorAll(sels);
                var out = [];
                var idx = 0;
                for (var i = 0; i < els.length && idx < $maxElements; i++) {
                    var el = els[i];
                    var rect = el.getBoundingClientRect();
                    // 跳过不可见元素
                    if (rect.width < 1 || rect.height < 1) continue;
                    if (el.offsetParent === null && el.tagName !== 'BODY') continue;
                    var style = getComputedStyle(el);
                    if (style.display === 'none' || style.visibility === 'hidden') continue;
                    idx++;
                    var text = '';
                    if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT') {
                        text = el.value || el.placeholder || '';
                    } else {
                        text = (el.innerText || el.textContent || el.getAttribute('aria-label') || '').substring(0, 120);
                    }
                    out.push({
                        ref: idx,
                        tag: el.tagName.toLowerCase(),
                        text: text.replace(/\\n/g,' ').trim(),
                        type: el.type || el.getAttribute('role') || '',
                        placeholder: el.placeholder || '',
                        href: el.href ? el.href.substring(0, 200) : '',
                        name: el.name || el.id || '',
                        value: (el.value || '').substring(0, 100),
                        enabled: !el.disabled,
                        rect: Math.round(rect.x)+','+Math.round(rect.y)+','+Math.round(rect.width)+'x'+Math.round(rect.height)
                    });
                }
                return JSON.stringify(out);
            })()
        """.trimIndent()) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                SnapshotElement(
                    ref = obj.optInt("ref"),
                    tag = obj.optString("tag"),
                    text = obj.optString("text"),
                    type = obj.optString("type"),
                    placeholder = obj.optString("placeholder"),
                    href = obj.optString("href"),
                    name = obj.optString("name"),
                    value = obj.optString("value"),
                    enabled = obj.optBoolean("enabled", true),
                    rect = obj.optString("rect"),
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    /**
     * 用 @ref 编号点击元素（智能快照配套）
     * 比 CSS 选择器更稳定：AI 只需说 click @3
     */
    suspend fun clickRef(ref: Int): Boolean {
        val result = evalJs("""
            (function(){
                var sels = 'a,button,input,select,textarea,[role="button"],[role="link"],[role="tab"],[role="menuitem"],[role="option"],[onclick],[tabindex]:not([tabindex="-1"]),summary,details,label[for]';
                var els = document.querySelectorAll(sels);
                var idx = 0;
                for (var i = 0; i < els.length; i++) {
                    var el = els[i];
                    var rect = el.getBoundingClientRect();
                    if (rect.width < 1 || rect.height < 1) continue;
                    if (el.offsetParent === null && el.tagName !== 'BODY') continue;
                    var style = getComputedStyle(el);
                    if (style.display === 'none' || style.visibility === 'hidden') continue;
                    idx++;
                    if (idx === $ref) {
                        el.scrollIntoView({behavior:'smooth', block:'center'});
                        el.focus();
                        el.click();
                        return 'clicked_' + el.tagName;
                    }
                }
                return 'not_found';
            })()
        """.trimIndent())
        return result?.startsWith("clicked") == true
    }

    /**
     * 用 @ref 编号填写表单（智能快照配套）
     */
    suspend fun fillRef(ref: Int, text: String): Boolean {
        val escaped = text.replace("'", "\\'").replace("\n", "\\n")
        val result = evalJs("""
            (function(){
                var sels = 'input,textarea,select,[contenteditable="true"]';
                var els = document.querySelectorAll(sels);
                var idx = 0;
                for (var i = 0; i < els.length; i++) {
                    var el = els[i];
                    var rect = el.getBoundingClientRect();
                    if (rect.width < 1 || rect.height < 1) continue;
                    if (el.offsetParent === null) continue;
                    idx++;
                    if (idx === $ref) {
                        el.focus();
                        el.value = '$escaped';
                        el.dispatchEvent(new Event('input', {bubbles:true}));
                        el.dispatchEvent(new Event('change', {bubbles:true}));
                        return 'filled';
                    }
                }
                return 'not_found';
            })()
        """.trimIndent())
        return result == "filled"
    }

    /**
     * 将快照格式化为 LLM 可读的文本
     * 给 Agent 的 system prompt 用
     */
    fun formatSnapshotForLLM(elements: List<SnapshotElement>): String {
        if (elements.isEmpty()) return "(页面无可交互元素)"
        val sb = StringBuilder("页面可交互元素列表：\n")
        for (el in elements) {
            sb.append("@e${el.ref} [${el.tag}")
            if (el.type.isNotEmpty()) sb.append("/${el.type}")
            sb.append("]")
            if (el.text.isNotEmpty()) sb.append(" \"${el.text.take(60)}\"")
            if (el.placeholder.isNotEmpty()) sb.append(" placeholder=\"${el.placeholder}\"")
            if (el.href.isNotEmpty()) sb.append(" -> ${el.href.take(80)}")
            if (!el.enabled) sb.append(" (disabled)")
            sb.append('\n')
        }
        return sb.toString()
    }

    // ── WebMCP 检测与调用 ───────────────────────────────────────
    // 对标 Google WebMCP 标准 (W3C, Chrome 146+)
    // 网站通过 navigator.modelContext.registerTool() 暴露结构化工具

    data class WebMcpTool(
        val name: String,
        val description: String,
        val inputSchema: String, // JSON Schema 字符串
    )

    /**
     * 检测页面是否支持 WebMCP
     * 返回注册的工具列表（空列表 = 不支持或无工具）
     */
    suspend fun detectWebMcp(): List<WebMcpTool> {
        val json = evalJs("""
            (function(){
                if (!navigator.modelContext && !navigator.modelContextTesting) return '[]';
                var api = navigator.modelContextTesting || navigator.modelContext;
                if (!api.listTools) return '[]';
                var tools = api.listTools();
                if (!tools || !tools.length) return '[]';
                return JSON.stringify(tools.map(function(t){
                    return {
                        name: t.name || '',
                        description: t.description || '',
                        inputSchema: JSON.stringify(t.inputSchema || {})
                    };
                }));
            })()
        """.trimIndent()) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                WebMcpTool(
                    name = obj.optString("name"),
                    description = obj.optString("description"),
                    inputSchema = obj.optString("inputSchema"),
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    /**
     * 调用 WebMCP 工具
     * @param toolName 工具名称
     * @param params JSON 参数字符串
     * @return 执行结果（JSON 字符串）
     */
    suspend fun executeWebMcpTool(toolName: String, params: String): String? {
        val escapedName = toolName.replace("'", "\\'")
        val escapedParams = params.replace("\\", "\\\\").replace("'", "\\'")
        return evalJs("""
            (async function(){
                var api = navigator.modelContextTesting || navigator.modelContext;
                if (!api || !api.executeTool) return JSON.stringify({error:'WebMCP not available'});
                try {
                    var result = await api.executeTool('$escapedName', '$escapedParams');
                    if (typeof result === 'string') return result;
                    return JSON.stringify(result);
                } catch(e) {
                    return JSON.stringify({error: e.message || String(e)});
                }
            })()
        """.trimIndent())
    }

    /**
     * 将 WebMCP 工具列表格式化为 LLM 可读文本
     */
    fun formatWebMcpToolsForLLM(tools: List<WebMcpTool>): String {
        if (tools.isEmpty()) return "(页面未注册 WebMCP 工具)"
        val sb = StringBuilder("WebMCP 可用工具：\n")
        for (t in tools) {
            sb.append("- ${t.name}: ${t.description}\n")
            sb.append("  参数: ${t.inputSchema.take(200)}\n")
        }
        return sb.toString()
    }

    // ── Agent 调度层 (三优先级回退) ──────────────────────────────
    // WebMCP 结构化调用 > 智能快照 + DOM操作 > 截图 + LLM视觉

    /**
     * Agent 统一入口：自动选择最优交互方式
     *
     * 优先级：
     * 1. WebMCP：网站暴露了结构化工具 → 直接调函数（省89% token）
     * 2. 智能快照：页面有可交互元素 → 给 LLM 元素列表 + @ref 编号
     * 3. 截图：以上都不行 → 截图让 LLM 看图决策
     *
     * @return AgentContext 包含页面当前状态的结构化描述
     */
    data class AgentContext(
        val mode: String,           // "webmcp" | "aom" | "snapshot" | "visual"
        val pageInfo: Map<String, String>,
        val webMcpTools: List<WebMcpTool> = emptyList(),
        val aomTree: String = "",                        // 可访问性树文本
        val aomMap: Map<Int, String> = emptyMap(),       // @a ref -> selector 映射
        val snapshotElements: List<SnapshotElement> = emptyList(),
        val screenshotBase64: String? = null,
        val pageText: String = "",
    )

    suspend fun agentObserve(): AgentContext {
        val pageInfo = getPageInfo()

        // 优先级 1：检测 WebMCP（网站暴露结构化工具 → 直接调函数）
        val webMcpTools = detectWebMcp()
        if (webMcpTools.isNotEmpty()) {
            DebugLog.i("WebViewAgent.agentObserve: WebMCP mode (${webMcpTools.size} tools)")
            return AgentContext(
                mode = "webmcp",
                pageInfo = pageInfo,
                webMcpTools = webMcpTools,
                pageText = getPageText(2000),
            )
        }

        // 优先级 2：可访问性树 (AOM) — Browser-Use 方案，89.1% 成功率
        val (aomTree, aomMap) = accessibilityTreeWithMap()
        if (aomTree.isNotEmpty() && aomTree != "(empty page)") {
            DebugLog.i("WebViewAgent.agentObserve: AOM mode (${aomMap.size} elements)")
            return AgentContext(
                mode = "aom",
                pageInfo = pageInfo,
                aomTree = aomTree,
                aomMap = aomMap,
                pageText = getPageText(2000),
            )
        }

        // 优先级 3：智能快照 (DOM-based fallback)
        val snapshot = smartSnapshot()
        if (snapshot.isNotEmpty()) {
            DebugLog.i("WebViewAgent.agentObserve: snapshot mode (${snapshot.size} elements)")
            return AgentContext(
                mode = "snapshot",
                pageInfo = pageInfo,
                snapshotElements = snapshot,
                pageText = getPageText(2000),
            )
        }

        // 优先级 4：截图兜底
        DebugLog.i("WebViewAgent.agentObserve: visual mode (fallback)")
        return AgentContext(
            mode = "visual",
            pageInfo = pageInfo,
            screenshotBase64 = takeScreenshot(),
            pageText = getPageText(2000),
        )
    }

    /**
     * Agent 统一执行入口
     * 根据 observe 结果自动选择最优执行方式
     *
     * @param action 动作描述，格式：
     *   WebMCP: "CALL(toolName, {jsonParams})"
     *   Snapshot: "CLICK @3" / "FILL @5 text" / "SELECT @7 option"
     *   Visual: 由 LLM 决策（返回截图让 LLM 看）
     */
    suspend fun agentAct(action: String, ctx: AgentContext): String {
        // WebMCP 模式：CALL(toolName, {jsonParams})
        if (action.startsWith("CALL(", ignoreCase = true)) {
            val inner = action.substringAfter("CALL(").substringBeforeLast(")")
            val comma = inner.indexOf(",")
            if (comma > 0) {
                val toolName = inner.substring(0, comma).trim()
                val params = inner.substring(comma + 1).trim()
                val result = executeWebMcpTool(toolName, params)
                return "WEBMCP_RESULT: ${result?.take(2000) ?: "null"}"
            }
            return "WEBMCP_ERROR: invalid format, use CALL(toolName, {json})"
        }

        // AOM 模式：CLICK @a3 / FILL @a5 text
        val aomClick = Regex("CLICK\\s+@a(\\d+)", RegexOption.IGNORE_CASE).find(action)
        if (aomClick != null) {
            val ref = aomClick.groupValues[1].toInt()
            val ok = clickAomRef(ref, ctx.aomMap)
            return "CLICK @a$ref: ${if (ok) "ok" else "not_found"}"
        }
        val aomFill = Regex("FILL\\s+@a(\\d+)\\s+(.+)", RegexOption.IGNORE_CASE).find(action)
        if (aomFill != null) {
            val ref = aomFill.groupValues[1].toInt()
            val text = aomFill.groupValues[2]
            val ok = fillAomRef(ref, text, ctx.aomMap)
            return "FILL @a$ref: ${if (ok) "ok" else "not_found"}"
        }

        // Snapshot 模式：CLICK @ref
        val clickMatch = Regex("CLICK\\s+@(\\d+)", RegexOption.IGNORE_CASE).find(action)
        if (clickMatch != null) {
            val ref = clickMatch.groupValues[1].toInt()
            val ok = clickRef(ref)
            return "CLICK @$ref: ${if (ok) "ok" else "not_found"}"
        }

        // Snapshot 模式：FILL @ref text
        val fillMatch = Regex("FILL\\s+@(\\d+)\\s+(.+)", RegexOption.IGNORE_CASE).find(action)
        if (fillMatch != null) {
            val ref = fillMatch.groupValues[1].toInt()
            val text = fillMatch.groupValues[2]
            val ok = fillRef(ref, text)
            return "FILL @$ref: ${if (ok) "ok" else "not_found"}"
        }

        // Snapshot 模式：NAVIGATE url
        val navMatch = Regex("NAVIGATE\\s+(\\S+)", RegexOption.IGNORE_CASE).find(action)
        if (navMatch != null) {
            val url = navMatch.groupValues[1]
            val ok = navigate(url)
            return "NAVIGATE: ${if (ok) "ok" else "timeout"}"
        }

        // Snapshot 模式：SCROLL down/up
        if (action.startsWith("SCROLL", ignoreCase = true)) {
            val dir = if (action.contains("up", true)) "up" else "down"
            scroll(dir)
            return "SCROLL $dir: ok"
        }

        // Snapshot 模式：EXTRACT selector
        val extractMatch = Regex("EXTRACT\\s+(\\S+)", RegexOption.IGNORE_CASE).find(action)
        if (extractMatch != null) {
            val target = extractMatch.groupValues[1]
            val text = if (target == "page" || target == "body") getPageText()
            else querySelector(target)?.get("text") ?: "not_found"
            return "EXTRACT: ${text.take(1000)}"
        }

        // 回退：让 LLM 看截图决策
        return "VISUAL: action='$action', use screenshot to decide next step"
    }

    // ── 可访问性树快照 (Accessibility Tree / AOM) ────────────────
    // 对标 Browser-Use: 89.1% WebVoyager 成功率的核心方案
    // 只保留语义节点(角色+标签+文本)，过滤掉 div/span 等纯布局噪音
    // 比 raw DOM 干净 10 倍，比截图快 35 倍

    data class AomNode(
        val role: String,       // ARIA role: button, link, textbox, heading, ...
        val name: String,       // accessible name (文本/aria-label/placeholder)
        val tag: String,        // 原始 HTML tag
        val ref: Int,           // @a1, @a2 ... 编号
        val state: String,      // focused, checked, expanded, disabled, ...
        val level: Int,         // heading level (h1=1, h2=2, ...)
        val children: List<AomNode> = emptyList(),
    )

    /**
     * 可访问性树快照：提取页面的语义结构
     * 对标 Browser-Use 的 DOM Distillation 方案
     *
     * 只提取有语义意义的节点：
     * - ARIA role (button, link, textbox, heading, list, ...)
     * - 交互元素 (a, button, input, select, textarea, ...)
     * - 内容元素 (h1-h6, p, li, img[alt], ...)
     *
     * 过滤掉：无 role 的 div/span/svg 等纯布局容器
     *
     * 输出格式：缩进文本树，每行一个节点
     * AI 用 @ref 编号指定操作目标
     */
    suspend fun accessibilityTree(maxDepth: Int = 15, maxNodes: Int = 120): String {
        return evalJs("""
            (function(){
                var idx = 0;
                var lines = [];
                function walk(el, depth) {
                    if (idx >= $maxNodes || depth > $maxDepth) return;
                    var role = el.getAttribute('role') || '';
                    var tag = el.tagName ? el.tagName.toLowerCase() : '';
                    // 确定是否有语义
                    var interactive = 'a button input select textarea details summary'.indexOf(tag) >= 0;
                    var hasRole = !!role;
                    var heading = /^h[1-6]$/.test(tag);
                    var img = tag === 'img' && el.alt;
                    var list = 'ul ol'.indexOf(tag) >= 0;
                    var li = tag === 'li';
                    var table = tag === 'table';
                    var semantic = interactive || hasRole || heading || img || list || li || table;
                    if (!semantic) {
                        // 递归子节点但不输出当前节点
                        for (var c = el.firstElementChild; c; c = c.nextElementSibling) walk(c, depth);
                        return;
                    }
                    idx++;
                    // 获取 accessible name
                    var name = el.getAttribute('aria-label')
                        || el.getAttribute('aria-labelledby')
                        || el.title
                        || el.alt
                        || el.placeholder
                        || '';
                    if (!name && (tag !== 'input' && tag !== 'select' && tag !== 'textarea')) {
                        name = (el.innerText || el.textContent || '').trim().substring(0, 80);
                    }
                    name = name.replace(/\\n/g,' ').trim();
                    // 状态
                    var states = [];
                    if (el.disabled) states.push('disabled');
                    if (el.checked) states.push('checked');
                    if (el.getAttribute('aria-expanded') === 'true') states.push('expanded');
                    if (el.getAttribute('aria-selected') === 'true') states.push('selected');
                    if (document.activeElement === el) states.push('focused');
                    var state = states.length ? ' [' + states.join(',') + ']' : '';
                    // heading level
                    var level = heading ? parseInt(tag[1]) : 0;
                    var prefix = '';
                    for (var d = 0; d < depth; d++) prefix += '  ';
                    var displayRole = role || (heading ? 'heading' : (tag === 'a' ? 'link' : (tag === 'img' ? 'img' : tag)));
                    var line = prefix + '@a' + idx + ' ' + displayRole;
                    if (level) line += '(level=' + level + ')';
                    line += ' "' + name + '"';
                    if (state) line += state;
                    if (tag === 'a' && el.href) line += ' -> ' + el.href.substring(0, 80);
                    if ((tag === 'input' || tag === 'textarea') && el.type) line += ' type=' + el.type;
                    if (tag === 'select') {
                        var opts = [];
                        for (var o = 0; o < Math.min(el.options.length, 5); o++) opts.push(el.options[o].text);
                        line += ' options=[' + opts.join(',') + ']';
                    }
                    lines.push(line);
                    // 递归子节点
                    for (var c = el.firstElementChild; c; c = c.nextElementSibling) walk(c, depth + 1);
                }
                walk(document.body, 0);
                return lines.join('\\n');
            })()
        """.trimIndent()) ?: "(empty page)"
    }

    /**
     * 可访问性树快照 + 所有节点的 @ref 映射表
     * 返回 (aomText, refToSelectorMap) 二元组
     * 用于 agentAct 的 @ref 解析
     */
    suspend fun accessibilityTreeWithMap(maxNodes: Int = 120): Pair<String, Map<Int, String>> {
        val json = evalJs("""
            (function(){
                var idx = 0;
                var lines = [];
                var map = {};
                var sels = 'a,button,input,select,textarea,details,summary,[role],[tabindex]';
                function walk(el, depth) {
                    if (idx >= $maxNodes || depth > 15) return;
                    var role = el.getAttribute('role') || '';
                    var tag = el.tagName ? el.tagName.toLowerCase() : '';
                    var interactive = 'a button input select textarea details summary'.indexOf(tag) >= 0;
                    var hasRole = !!role;
                    var heading = /^h[1-6]$/.test(tag);
                    var img = tag === 'img' && el.alt;
                    var semantic = interactive || hasRole || heading || img || tag === 'li' || tag === 'table';
                    if (!semantic) {
                        for (var c = el.firstElementChild; c; c = c.nextElementSibling) walk(c, depth);
                        return;
                    }
                    idx++;
                    // 生成唯一选择器
                    var sel = '';
                    if (el.id) sel = '#' + CSS.escape(el.id);
                    else if (el.name && (tag === 'input' || tag === 'select' || tag === 'textarea'))
                        sel = tag + '[name="' + el.name + '"]';
                    else {
                        // nth-child 链
                        var path = [];
                        var cur = el;
                        while (cur && cur !== document.body) {
                            var p = cur.parentElement;
                            if (!p) break;
                            var ch = p.children;
                            var nth = 0;
                            for (var i = 0; i < ch.length; i++) { if (ch[i] === cur) { nth = i + 1; break; } }
                            path.unshift(cur.tagName.toLowerCase() + ':nth-child(' + nth + ')');
                            cur = p;
                        }
                        sel = path.join(' > ');
                    }
                    map['' + idx] = sel;
                    // 获取 accessible name
                    var name = el.getAttribute('aria-label') || el.title || el.alt || el.placeholder || '';
                    if (!name && tag !== 'input' && tag !== 'select' && tag !== 'textarea')
                        name = (el.innerText || el.textContent || '').trim().substring(0, 80);
                    name = name.replace(/\\n/g,' ').trim();
                    var states = [];
                    if (el.disabled) states.push('disabled');
                    if (el.checked) states.push('checked');
                    if (document.activeElement === el) states.push('focused');
                    var state = states.length ? ' [' + states.join(',') + ']' : '';
                    var displayRole = role || (heading ? 'heading' : tag);
                    var line = '@a' + idx + ' ' + displayRole + ' "' + name + '"' + state;
                    lines.push(line);
                    for (var c = el.firstElementChild; c; c = c.nextElementSibling) walk(c, depth + 1);
                }
                walk(document.body, 0);
                return JSON.stringify({tree: lines.join('\\n'), map: map});
            })()
        """.trimIndent()) ?: return "(empty page)" to emptyMap()
        return try {
            val obj = org.json.JSONObject(json)
            val tree = obj.optString("tree")
            val mapObj = obj.optJSONObject("map") ?: org.json.JSONObject()
            val map = mutableMapOf<Int, String>()
            for (key in mapObj.keys()) {
                map[key.toIntOrNull() ?: continue] = mapObj.optString(key)
            }
            tree to map
        } catch (_: Exception) { "(parse error)" to emptyMap() }
    }

    /**
     * 用 AOM @ref 编号点击（可访问性树配套）
     */
    suspend fun clickAomRef(ref: Int, aomMap: Map<Int, String>): Boolean {
        val selector = aomMap[ref] ?: return false
        return click(selector)
    }

    /**
     * 用 AOM @ref 编号填写（可访问性树配套）
     */
    suspend fun fillAomRef(ref: Int, text: String, aomMap: Map<Int, String>): Boolean {
        val selector = aomMap[ref] ?: return false
        return type(selector, text)
    }

    /**
     * 将 AOM 树格式化给 LLM：比智能快照更精简、更有语义
     */
    fun formatAomForLLM(aomTree: String): String {
        return "页面可访问性树 (Accessibility Tree)：\n$aomTree"
    }

    // ── 动作缓存 (Action Cache) ──────────────────────────────────
    // 对标 Stagehand auto-cache: 相同自然语言指令不重复调 LLM
    // 第一次: LLM 解析 → 执行 → 记录映射
    // 第二次: 直接用缓存的选择器执行（~50ms, $0）

    private val actionCache = mutableMapOf<String, ActionCacheEntry>()

    data class ActionCacheEntry(
        val action: String,         // "click", "fill", "navigate"
        val selector: String,       // 缓存的选择器/URL
        val value: String = "",     // 填写的值（fill 时用）
        val timestamp: Long = System.currentTimeMillis(),
        val hitCount: Int = 0,      // 命中次数
    )

    /**
     * 查询缓存：如果指令之前成功执行过，直接返回缓存的动作
     * @return 缓存的 ActionCacheEntry，或 null（未命中）
     */
    fun getCachedAction(instruction: String): ActionCacheEntry? {
        val key = normalizeActionKey(instruction)
        val entry = actionCache[key] ?: return null
        // 缓存 24 小时过期
        if (System.currentTimeMillis() - entry.timestamp > 24 * 3600_000) {
            actionCache.remove(key)
            return null
        }
        return entry.copy(hitCount = entry.hitCount + 1)
    }

    /**
     * 记录成功的动作到缓存
     */
    fun cacheAction(instruction: String, action: String, selector: String, value: String = "") {
        val key = normalizeActionKey(instruction)
        actionCache[key] = ActionCacheEntry(action, selector, value)
        DebugLog.d("WebViewAgent: cached action '${action}' for '$key'")
    }

    /**
     * 清除动作缓存（页面跳转后可能失效）
     */
    fun clearActionCache() {
        actionCache.clear()
    }

    /**
     * 带缓存的智能执行：
     * 1. 先查缓存 → 命中直接执行（~50ms）
     * 2. 缓存未命中 → 调 LLM → 执行 → 记录缓存
     */
    suspend fun actWithCache(instruction: String, aomMap: Map<Int, String>): String {
        // 1. 查缓存
        val cached = getCachedAction(instruction)
        if (cached != null) {
            DebugLog.i("WebViewAgent.actWithCache: cache HIT for '$instruction'")
            return when (cached.action) {
                "click" -> {
                    val ok = click(cached.selector)
                    "CLICK(cached): ${if (ok) "ok" else "selector_changed"}"
                }
                "fill" -> {
                    val ok = type(cached.selector, cached.value)
                    "FILL(cached): ${if (ok) "ok" else "selector_changed"}"
                }
                "navigate" -> {
                    val ok = navigate(cached.selector)
                    "NAVIGATE(cached): ${if (ok) "ok" else "timeout"}"
                }
                else -> "UNKNOWN(cached)"
            }
        }

        // 2. 缓存未命中 → 返回 null 让调用方走 LLM 路径
        return "CACHE_MISS: need LLM to parse '$instruction'"
    }

    private fun normalizeActionKey(instruction: String): String {
        return instruction.lowercase()
            .replace(Regex("\\s+"), " ")
            .replace(Regex("[\"'.,;:!?]"), "")
            .trim()
            .take(100)
    }

    // ── 结构化数据提取 (Structured Extraction) ───────────────────
    // 对标 Stagehand extract(instruction, schema)
    // AI 描述想要的数据，直接提取为结构化 JSON

    /**
     * 结构化提取：从页面提取指定数据
     * @param instruction 自然语言描述，如 "提取所有商品的名称和价格"
     * @param fieldNames 要提取的字段名列表
     * @return 提取结果的 JSON 字符串
     */
    suspend fun extractStructured(instruction: String, fieldNames: List<String>): String {
        val fieldsJs = fieldNames.joinToString(",") { "\"$it\": \"string\"" }
        val escaped = instruction.replace("'", "\\'")
        return evalJs("""
            (function(){
                // 策略1：从表格提取
                var tables = document.querySelectorAll('table');
                if (tables.length > 0) {
                    var results = [];
                    for (var t = 0; t < tables.length; t++) {
                        var rows = tables[t].querySelectorAll('tr');
                        var headers = [];
                        var headerRow = rows[0];
                        if (headerRow) {
                            var ths = headerRow.querySelectorAll('th,td');
                            for (var h = 0; h < ths.length; h++) headers.push(ths[h].innerText.trim());
                        }
                        for (var r = 1; r < rows.length && results.length < 50; r++) {
                            var cells = rows[r].querySelectorAll('td');
                            var row = {};
                            for (var c = 0; c < cells.length; c++) {
                                var key = headers[c] || 'col' + c;
                                row[key] = cells[c].innerText.trim();
                            }
                            if (Object.keys(row).length > 0) results.push(row);
                        }
                    }
                    if (results.length > 0) return JSON.stringify({source:'table', data:results});
                }
                // 策略2：从列表提取
                var lists = document.querySelectorAll('ul,ol');
                if (lists.length > 0) {
                    var items = [];
                    var lis = lists[0].querySelectorAll('li');
                    for (var i = 0; i < Math.min(lis.length, 50); i++) {
                        items.push({text: lis[i].innerText.trim().substring(0, 200)});
                    }
                    if (items.length > 0) return JSON.stringify({source:'list', data:items});
                }
                // 策略3：通用文本提取
                var body = document.body.innerText.substring(0, 5000);
                return JSON.stringify({source:'text', raw: body});
            })()
        """.trimIndent()) ?: "{\"error\":\"extraction_failed\"}"
    }

    /**
     * 智能提取：自动检测页面数据结构并提取
     * 不需要指定 schema，自动识别列表、表格、卡片等模式
     */
    suspend fun extractAuto(maxItems: Int = 30): String {
        return evalJs("""
            (function(){
                // 检测重复结构模式（卡片、列表项、表格行）
                var candidates = [];
                // 1. 表格
                var table = document.querySelector('table');
                if (table) {
                    var rows = table.querySelectorAll('tr');
                    var headers = [];
                    var firstRow = rows[0];
                    if (firstRow) {
                        var ths = firstRow.querySelectorAll('th,td');
                        for (var h = 0; h < ths.length; h++) headers.push(ths[h].innerText.trim());
                    }
                    var data = [];
                    for (var r = 1; r < rows.length && data.length < $maxItems; r++) {
                        var cells = rows[r].querySelectorAll('td');
                        var row = {};
                        for (var c = 0; c < cells.length; c++) {
                            var key = headers[c] || ('col' + c);
                            row[key] = cells[c].innerText.trim().substring(0, 200);
                        }
                        if (Object.keys(row).length > 0) data.push(row);
                    }
                    if (data.length > 0) return JSON.stringify({type:'table', count:data.length, data:data});
                }
                // 2. 列表
                var items = document.querySelectorAll('li,article,[role="listitem"],.item,.card,.result');
                if (items.length >= 3) {
                    var data = [];
                    for (var i = 0; i < Math.min(items.length, $maxItems); i++) {
                        var el = items[i];
                        data.push({
                            text: el.innerText.trim().substring(0, 300),
                            links: Array.from(el.querySelectorAll('a')).map(function(a){return {text:a.innerText.trim(),href:a.href}}).slice(0,3)
                        });
                    }
                    return JSON.stringify({type:'list', count:data.length, data:data});
                }
                // 3. 降级：提取页面纯文本
                return JSON.stringify({type:'text', content: document.body.innerText.substring(0, 5000)});
            })()
        """.trimIndent()) ?: "{\"type\":\"error\"}"
    }

    // ── 自动化操作链 ────────────────────────────────────────────

    /**
     * 执行自动化操作链：依次执行多个操作指令
     * 指令格式：
     *   NAVIGATE(url)       - 导航到URL
     *   CLICK(selector)     - 点击元素
     *   TYPE(selector,text) - 填写表单
     *   WAIT(selector)      - 等待元素出现
     *   SCROLL(direction)   - 滚动
     *   EXTRACT(selector)   - 提取文本
     *   SCREENSHOT          - 截图
     *   EVAL(js)            - 执行JS
     */
    suspend fun executeActionChain(actions: List<String>): List<String> {
        val results = mutableListOf<String>()
        for (action in actions) {
            val result = when {
                action.startsWith("NAVIGATE(", true) -> {
                    val url = action.substringAfter("(").substringBefore(")")
                    val ok = navigate(url)
                    "NAVIGATE: ${if (ok) "ok" else "timeout"}"
                }
                action.startsWith("CLICK(", true) -> {
                    val sel = action.substringAfter("(").substringBefore(")")
                    val ok = click(sel)
                    "CLICK($sel): ${if (ok) "ok" else "not_found"}"
                }
                action.startsWith("TYPE(", true) -> {
                    val parts = action.substringAfter("(").substringBeforeLast(")")
                    val comma = parts.indexOf(",")
                    if (comma > 0) {
                        val sel = parts.substring(0, comma).trim()
                        val text = parts.substring(comma + 1).trim()
                        val ok = type(sel, text)
                        "TYPE($sel): ${if (ok) "ok" else "not_found"}"
                    } else "TYPE: invalid format"
                }
                action.startsWith("WAIT(", true) -> {
                    val sel = action.substringAfter("(").substringBefore(")")
                    val ok = waitForSelector(sel)
                    "WAIT($sel): ${if (ok) "found" else "timeout"}"
                }
                action.startsWith("SCROLL(", true) -> {
                    val dir = action.substringAfter("(").substringBefore(")")
                    scroll(dir)
                    "SCROLL($dir): ok"
                }
                action.startsWith("EXTRACT(", true) -> {
                    val sel = action.substringAfter("(").substringBefore(")")
                    val text = if (sel == "body" || sel == "page") {
                        getPageText()
                    } else {
                        querySelector(sel)?.get("text") ?: "not_found"
                    }
                    "EXTRACT($sel): ${text.take(500)}"
                }
                action.equals("SCREENSHOT", true) -> {
                    val b64 = takeScreenshot()
                    "SCREENSHOT: ${if (b64 != null) "${b64.length} chars" else "failed"}"
                }
                action.startsWith("EVAL(", true) -> {
                    val js = action.substringAfter("EVAL(").substringBeforeLast(")")
                    val r = evaluate(js)
                    "EVAL: ${r?.take(500) ?: "null"}"
                }
                else -> "UNKNOWN: $action"
            }
            results.add(result)
            DebugLog.d("WebViewAgent.actionChain: $result")
            delay(500) // 操作间隔，避免过快
        }
        return results
    }

    fun destroy() {
        mainHandler.post {
            interceptedRequests.clear()
            webView?.stopLoading()
            webView?.removeJavascriptInterface("OpedrgentBridge")
            webView?.destroy()
            webView = null
            isInitialized = false
        }
        DebugLog.i("WebViewAgent destroyed")
    }
}