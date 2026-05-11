package top.hsyscn.opedrgent.mcp.chrome

import android.app.Activity
import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import top.hsyscn.opedrgent.mcp.*
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class CdpDomNode(
    val nodeId: Int,
    val parentId: Int?,
    val nodeType: Int,
    val nodeName: String,
    val attributes: Map<String, String> = emptyMap(),
    val value: String? = null,
    val children: List<CdpDomNode> = emptyList(),
    val boundingBox: CdpRect? = null,
)

data class CdpRect(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)

data class CdpExecutionContext(
    val id: Int,
    val origin: String,
    val name: String?,
)

class ChromeDevToolsMcp(
    private val debugPort: Int = 9222,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build(),
) : McpToolHandler {

    override val name: String = "chrome_devtools"
    override val description: String = "Chrome DevTools Protocol integration for browser automation"
    
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("action", buildJsonObject {
                put("type", "string")
                put("enum", JsonArray(listOf(
                    JsonPrimitive("navigate"),
                    JsonPrimitive("screenshot"),
                    JsonPrimitive("click"),
                    JsonPrimitive("type"),
                    JsonPrimitive("evaluate"),
                    JsonPrimitive("get_dom"),
                    JsonPrimitive("get_elements"),
                    JsonPrimitive("scroll"),
                )))
                put("description", "Action to perform")
            })
            put("url", buildJsonObject {
                put("type", "string")
                put("description", "URL to navigate to (for navigate action)")
            })
            put("selector", buildJsonObject {
                put("type", "string")
                put("description", "CSS selector or element description (for click/type/get_elements)")
            })
            put("text", buildJsonObject {
                put("type", "string")
                put("description", "Text to type (for type action)")
            })
            put("script", buildJsonObject {
                put("type", "string")
                put("description", "JavaScript to evaluate (for evaluate action)")
            })
            put("x", buildJsonObject {
                put("type", "number")
                put("description", "X coordinate for click/scroll")
            })
            put("y", buildJsonObject {
                put("type", "number")
                put("description", "Y coordinate for click/scroll")
            })
            put("direction", buildJsonObject {
                put("type", "string")
                put("enum", JsonArray(listOf(JsonPrimitive("up"), JsonPrimitive("down"), JsonPrimitive("left"), JsonPrimitive("right"))))
                put("description", "Scroll direction")
            })
            put("amount", buildJsonObject {
                put("type", "number")
                put("description", "Scroll amount in pixels")
            })
        })
        put("required", JsonArray(listOf(JsonPrimitive("action"))))
    }

    private var websocketUrl: String? = null
    private var webSocket: WebSocket? = null
    private var messageId = 0
    private val pendingResponses = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()

    private suspend fun connectToDebugger(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("http://localhost:$debugPort/json")
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: return@withContext false

                val tabs = org.json.JSONArray(body)
                if (tabs.length() == 0) {
                    DebugLog.w("ChromeDevToolsMcp: no tabs found")
                    return@withContext false
                }

                val firstTab = tabs.getJSONObject(0)
                websocketUrl = firstTab.getString("webSocketDebuggerUrl")

                DebugLog.i("ChromeDevToolsMcp: connected to debugger at port $debugPort")

                connectWebSocket()

                true
            } catch (e: Exception) {
                DebugLog.e("ChromeDevToolsMcp.connectToDebugger: ${e.message}")
                false
            }
        }
    }

    private fun connectWebSocket() {
        val url = websocketUrl ?: return

        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                DebugLog.i("ChromeDevToolsMcp: WebSocket opened")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val id = json.optInt("id", -1)

                    if (id > 0) {
                        val deferred = pendingResponses[id]
                        deferred?.complete(json)
                    }
                } catch (e: Exception) {
                    DebugLog.w("ChromeDevToolsMcp.onMessage: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                DebugLog.e("ChromeDevToolsMcp.onFailure: ${t.message}")
                pendingResponses.values.forEach { deferred -> 
                    deferred.completeExceptionally(t) 
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                DebugLog.i("ChromeDevToolsMcp: WebSocket closed")
            }
        })
    }

    private suspend fun sendCdpCommand(method: String, params: JSONObject? = null): JSONObject {
        if (websocketUrl == null || webSocket == null) {
            val connected = connectToDebugger()
            if (!connected) throw RuntimeException("Cannot connect to Chrome DevTools")
        }

        val id = ++messageId
        val command = JSONObject().apply {
            put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }

        val deferred = CompletableDeferred<JSONObject>()
        pendingResponses[id] = deferred

        webSocket?.send(command.toString())

        return withTimeout(15_000L) {
            deferred.await()
        }.also {
            pendingResponses.remove(id)
        }
    }

    override suspend fun execute(arguments: JsonObject): JsonElement {
        val action = arguments["action"]?.toString()?.trim('"') ?: ""

        return when (action) {
            "navigate" -> navigate(arguments)
            "screenshot" -> takeScreenshot()
            "click" -> clickElement(arguments)
            "type" -> typeText(arguments)
            "evaluate" -> evaluateScript(arguments)
            "get_dom" -> getDomTree()
            "get_elements" -> getElements(arguments)
            "scroll" -> scrollPage(arguments)
            else -> buildJsonObject {
                put("error", "Unknown action: $action")
            }
        }
    }

    private suspend fun navigate(arguments: JsonObject): JsonElement {
        val url = arguments["url"]?.toString()?.trim('"') ?: ""

        if (url.isEmpty()) {
            return buildJsonObject { put("error", "URL required for navigate") }
        }

        try {
            val params = JSONObject().put("url", url)
            sendCdpCommand("Page.navigate", params)

            delay(2000)

            return buildJsonObject {
                put("success", true)
                put("url", url)
            }
        } catch (e: Exception) {
            return buildJsonObject { put("error", "Navigate failed: ${e.message}") }
        }
    }

    private suspend fun takeScreenshot(): JsonElement {
        try {
            sendCdpCommand("Page.enable")
            
            val params = JSONObject().apply {
                put("format", "png")
                put("fromSurface", true)
            }

            val result = sendCdpCommand("Page.captureScreenshot", params)
            val base64Data = result.optString("data", "")

            return buildJsonObject {
                put("success", true)
                put("image", base64Data)
                put("format", "png")
            }
        } catch (e: Exception) {
            return buildJsonObject { put("error", "Screenshot failed: ${e.message}") }
        }
    }

    private suspend fun clickElement(arguments: JsonObject): JsonElement {
        val selector = arguments["selector"]?.toString()?.trim('"') ?: ""
        val x = arguments["x"]?.toString()?.toDoubleOrNull()
        val y = arguments["y"]?.toString()?.toDoubleOrNull()

        return try {
            if (x != null && y != null) {
                val params = JSONObject().apply {
                    put("type", "mousePressed")
                    put("x", x)
                    put("y", y)
                    put("button", "left")
                    put("clickCount", 1)
                }
                sendCdpCommand("Input.dispatchMouseEvent", params)

                val releaseParams = JSONObject().apply {
                    put("type", "mouseReleased")
                    put("x", x)
                    put("y", y)
                    put("button", "left")
                    put("clickCount", 1)
                }
                sendCdpCommand("Input.dispatchMouseEvent", releaseParams)

                buildJsonObject {
                    put("success", true)
                    put("x", x)
                    put("y", y)
                }
            } else if (selector.isNotEmpty()) {
                val script = """
                    (function() {
                        var el = document.querySelector('$selector');
                        if (!el) return null;
                        var rect = el.getBoundingClientRect();
                        return {x: rect.x + rect.width/2, y: rect.y + rect.height/2};
                    })()
                """.trimIndent()

                val evalParams = JSONObject().put("expression", script)
                val evalResult = sendCdpCommand("Runtime.evaluate", evalParams)
                val resultObj = evalResult.optJSONObject("result")
                val valueStr = resultObj?.optString("result", "{}")
                val value = JSONObject(valueStr ?: "{}")

                val clickX = value.optDouble("x", 0.0)
                val clickY = value.optDouble("y", 0.0)

                val params = JSONObject().apply {
                    put("type", "mousePressed")
                    put("x", clickX)
                    put("y", clickY)
                    put("button", "left")
                    put("clickCount", 1)
                }
                sendCdpCommand("Input.dispatchMouseEvent", params)

                val releaseParams = JSONObject().apply {
                    put("type", "mouseReleased")
                    put("x", clickX)
                    put("y", clickY)
                    put("button", "left")
                    put("clickCount", 1)
                }
                sendCdpCommand("Input.dispatchMouseEvent", releaseParams)

                buildJsonObject {
                    put("success", true)
                    put("selector", selector)
                    put("x", clickX)
                    put("y", clickY)
                }
            } else {
                buildJsonObject { put("error", "Selector or coordinates required") }
            }
        } catch (e: Exception) {
            buildJsonObject { put("error", "Click failed: ${e.message}") }
        }
    }

    private suspend fun typeText(arguments: JsonObject): JsonElement {
        val text = arguments["text"]?.toString()?.trim('"') ?: ""
        val selector = arguments["selector"]?.toString()?.trim('"') ?: ""

        if (text.isEmpty()) {
            return buildJsonObject { put("error", "Text required for type") }
        }

        return try {
            if (selector.isNotEmpty()) {
                val script = """
                    (function() {
                        var el = document.querySelector('$selector');
                        if (el) el.focus();
                        return !!el;
                    })()
                """.trimIndent()
                
                val evalParams = JSONObject().put("expression", script)
                sendCdpCommand("Runtime.evaluate", evalParams)
            }

            for (char in text) {
                val params = JSONObject().apply {
                    put("type", "keyDown")
                    put("text", char.toString())
                }
                sendCdpCommand("Input.dispatchKeyEvent", params)

                val upParams = JSONObject().apply {
                    put("type", "keyUp")
                    put("text", char.toString())
                }
                sendCdpCommand("Input.dispatchKeyEvent", upParams)

                delay(50)
            }

            buildJsonObject {
                put("success", true)
                put("text", text)
            }
        } catch (e: Exception) {
            buildJsonObject { put("error", "Type failed: ${e.message}") }
        }
    }

    private suspend fun evaluateScript(arguments: JsonObject): JsonElement {
        val script = arguments["script"]?.toString()?.trim('"') ?: ""

        if (script.isEmpty()) {
            return buildJsonObject { put("error", "Script required for evaluate") }
        }

        return try {
            val params = JSONObject().put("expression", script)
            val result = sendCdpCommand("Runtime.evaluate", params)

            val resultObj = result.optJSONObject("result")
            val value = resultObj?.optString("result", "")

            buildJsonObject {
                put("success", true)
                put("result", value)
            }
        } catch (e: Exception) {
            buildJsonObject { put("error", "Evaluate failed: ${e.message}") }
        }
    }

    private suspend fun getDomTree(): JsonElement {
        return try {
            val params = JSONObject().put("depth", -1)
            val result = sendCdpCommand("DOM.getDocument", params)

            val root = result.optJSONObject("root")
            
            buildJsonObject {
                put("success", true)
                put("dom", root.toString())
            }
        } catch (e: Exception) {
            buildJsonObject { put("error", "Get DOM failed: ${e.message}") }
        }
    }

    private suspend fun getElements(arguments: JsonObject): JsonElement {
        val selector = arguments["selector"]?.toString()?.trim('"') ?: "*"

        return try {
            val escapedSelector = selector.replace("'", "\\'")
            val script = """
                (function() {
                    var elements = document.querySelectorAll('$escapedSelector');
                    var results = [];
                    for (var i = 0; i < Math.min(elements.length, 20); i++) {
                        var el = elements[i];
                        var rect = el.getBoundingClientRect();
                        results.push({
                            tag: el.tagName.toLowerCase(),
                            id: el.id || null,
                            class: el.className || null,
                            text: el.textContent.trim().substring(0, 100),
                            x: Math.round(rect.x),
                            y: Math.round(rect.y),
                            width: Math.round(rect.width),
                            height: Math.round(rect.height)
                        });
                    }
                    return results;
                })()
            """.trimIndent()

            val params = JSONObject().put("expression", script)
            val result = sendCdpCommand("Runtime.evaluate", params)

            val resultObj = result.optJSONObject("result")
            val value = resultObj?.optString("result", "[]")

            buildJsonObject {
                put("success", true)
                put("elements", value)
            }
        } catch (e: Exception) {
            buildJsonObject { put("error", "Get elements failed: ${e.message}") }
        }
    }

    private suspend fun scrollPage(arguments: JsonObject): JsonElement {
        val direction = arguments["direction"]?.toString()?.trim('"') ?: "down"
        val amount = arguments["amount"]?.toString()?.toIntOrNull() ?: 300

        return try {
            val (x, y) = when (direction) {
                "up" -> Pair(0, -amount)
                "down" -> Pair(0, amount)
                "left" -> Pair(-amount, 0)
                "right" -> Pair(amount, 0)
                else -> Pair(0, amount)
            }

            val params = JSONObject().apply {
                put("type", "mouseWheel")
                put("x", 400)
                put("y", 300)
                put("deltaX", x)
                put("deltaY", y)
            }
            sendCdpCommand("Input.dispatchMouseEvent", params)

            buildJsonObject {
                put("success", true)
                put("direction", direction)
                put("amount", amount)
            }
        } catch (e: Exception) {
            buildJsonObject { put("error", "Scroll failed: ${e.message}") }
        }
    }

    fun disconnect() {
        webSocket?.close(1000, "Normal closure")
        webSocket = null
        websocketUrl = null
        pendingResponses.values.forEach { deferred -> 
            (deferred as? kotlinx.coroutines.CompletableDeferred<*>)?.cancel()
        }
        pendingResponses.clear()
        DebugLog.i("ChromeDevToolsMcp: disconnected")
    }

    companion object {
        suspend fun create(debugPort: Int = 9222): ChromeDevToolsMcp {
            val cdp = ChromeDevToolsMcp(debugPort)
            cdp.connectToDebugger()
            return cdp
        }
    }
}
