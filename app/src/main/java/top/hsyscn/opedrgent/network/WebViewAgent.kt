package top.hsyscn.opedrgent.network

import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
            withContext(Dispatchers.Default) { Thread.sleep(1000) }
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

            withContext(Dispatchers.Default) { Thread.sleep(500) }
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
                withContext(Dispatchers.Default) { Thread.sleep(2000) }

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
                        captured.complete(r?.trim('"').orEmpty() ?: "")
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
            withContext(Dispatchers.Default) { Thread.sleep(500) }
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
            withContext(Dispatchers.Default) { Thread.sleep(1500) }
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
            withContext(Dispatchers.Default) { Thread.sleep(500) }
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
                    val canvas = android.graphics.Canvas(bitmapRef!!)
                    picture.draw(canvas)
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
        withContext(Dispatchers.Default) { Thread.sleep(3000) }

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
                    withContext(Dispatchers.Default) { Thread.sleep(1000) }
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
                    withContext(Dispatchers.Default) { Thread.sleep(1000) }
                    sb.appendLine("页面文本：${textRef.get().take(500)}")
                }
            }

            withContext(Dispatchers.Default) { Thread.sleep(2000) }
        }

        sb.toString()
    }

    fun destroy() {
        mainHandler.post {
            webView?.stopLoading()
            webView?.removeJavascriptInterface("OpedrgentBridge")
            webView?.destroy()
            webView = null
            isInitialized = false
        }
        DebugLog.i("WebViewAgent destroyed")
    }
}