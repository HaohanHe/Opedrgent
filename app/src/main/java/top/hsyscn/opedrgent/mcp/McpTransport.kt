@file:Suppress("DEPRECATION")
package top.hsyscn.opedrgent.mcp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.ByteString
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

interface McpTransport {
    suspend fun connect()
    suspend fun send(message: String)
    suspend fun receive(): String?
    suspend fun disconnect()
    val isConnected: Boolean
}

class StdioTransport(
    private val command: List<String>,
) : McpTransport {

    private var process: Process? = null
    private var scope: CoroutineScope? = null
    private var _isConnected = false

    override val isConnected: Boolean get() = _isConnected

    override suspend fun connect() {
        withContext(Dispatchers.IO) {
            val pb = ProcessBuilder(command)
            pb.redirectErrorStream(true)
            process = pb.start()

            _isConnected = true
            DebugLog.i("StdioTransport: connected to ${command.joinToString(" ")}")
        }
    }

    override suspend fun send(message: String) {
        withContext(Dispatchers.IO) {
            process?.outputStream?.write("$message\n".toByteArray())
            process?.outputStream?.flush()
        }
    }

    override suspend fun receive(): String? {
        return withContext(Dispatchers.IO) {
            try {
                val reader = process?.inputStream?.bufferedReader()
                reader?.readLine()
            } catch (e: Exception) {
                DebugLog.e("StdioTransport.receive: ${e.message}")
                null
            }
        }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            _isConnected = false
            process?.destroy()
            process = null
        }
    }
}

class SseTransport(
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
) : McpTransport {

    private var eventSourceCall: Call? = null
    private var postClient: OkHttpClient = client.newBuilder().build()
    private val messageChannel = Channel<String>(Channel.UNLIMITED)
    private var sessionId: String? = null
    private var _isConnected = false

    override val isConnected: Boolean get() = _isConnected

    override suspend fun connect() {
        withContext(Dispatchers.IO) {
            val requestBuilder = Request.Builder()
                .url(url)
                .get()

            headers.forEach { (key, value) ->
                requestBuilder.addHeader(key, value)
            }

            requestBuilder.addHeader("Accept", "text/event-stream")
            requestBuilder.addHeader("Cache-Control", "no-cache")

            val request = requestBuilder.build()

            eventSourceCall = client.newCall(request)

            eventSourceCall?.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    DebugLog.e("SseTransport.connect failure: ${e.message}")
                    _isConnected = false
                    messageChannel.close(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!response.isSuccessful) {
                        DebugLog.e("SseTransport.connect: HTTP ${response.code}")
                        _isConnected = false
                        messageChannel.close(IOException("HTTP ${response.code}"))
                        return
                    }

                    _isConnected = true
                    val body = response.body ?: run {
                        messageChannel.close(IOException("Empty body"))
                        return
                    }

                    val source = body.source()
                    try {
                        while (!source.exhausted()) {
                            val line = source.readUtf8LineStrict() ?: break

                            if (line.startsWith("data: ")) {
                                val data = line.removePrefix("data: ").trim()
                                if (data == "[DONE]") continue

                                if (line.startsWith("endpoint: ")) {
                                    sessionId = line.removePrefix("endpoint: ").trim()
                                    DebugLog.i("SseTransport: got session endpoint: $sessionId")
                                } else {
                                    try {
                                        messageChannel.trySend(data)
                                    } catch (e: Exception) {
                                        DebugLog.w("SseTransport: channel send failed: ${e.message}")
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        DebugLog.e("SseTransport: read error: ${e.message}")
                        messageChannel.close(e)
                    } finally {
                        _isConnected = false
                    }
                }
            })

            delay(500)
            DebugLog.i("SseTransport: connected to $url")
        }
    }

    override suspend fun send(message: String) {
        withContext(Dispatchers.IO) {
            val targetUrl = sessionId ?: url

            val body = RequestBody.create(
                "application/json".toMediaType(),
                message,
            )

            val requestBuilder = Request.Builder()
                .url(targetUrl)
                .post(body)

            headers.forEach { (key, value) ->
                requestBuilder.addHeader(key, value)
            }

            val request = requestBuilder.build()

            try {
                val response = postClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    DebugLog.w("SseTransport.send: HTTP ${response.code}")
                }
                response.body?.close()
            } catch (e: Exception) {
                DebugLog.e("SseTransport.send: ${e.message}")
            }
        }
    }

    override suspend fun receive(): String? {
        return try {
            messageChannel.receive()
        } catch (e: Exception) {
            DebugLog.e("SseTransport.receive: ${e.message}")
            null
        }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            _isConnected = false
            eventSourceCall?.cancel()
            eventSourceCall = null
            messageChannel.close()
        }
    }
}

class HttpTransport(
    private val baseUrl: String,
    private val headers: Map<String, String> = emptyMap(),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
) : McpTransport {

    private var _isConnected = false
    private val messageQueue = mutableListOf<String>()
    private var queueIndex = 0

    override val isConnected: Boolean get() = _isConnected

    override suspend fun connect() {
        withContext(Dispatchers.IO) {
            val requestBuilder = Request.Builder()
                .url("$baseUrl/")
                .get()

            headers.forEach { (key, value) ->
                requestBuilder.addHeader(key, value)
            }

            val request = requestBuilder.build()

            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    _isConnected = true
                    DebugLog.i("HttpTransport: connected to $baseUrl")
                } else {
                    DebugLog.e("HttpTransport: HTTP ${response.code}")
                }
                response.body?.close()
            } catch (e: Exception) {
                DebugLog.e("HttpTransport.connect: ${e.message}")
            }
        }
    }

    override suspend fun send(message: String) {
        withContext(Dispatchers.IO) {
            val body = RequestBody.create(
                "application/json".toMediaType(),
                message,
            )

            val requestBuilder = Request.Builder()
                .url(baseUrl)
                .post(body)

            headers.forEach { (key, value) ->
                requestBuilder.addHeader(key, value)
            }

            val request = requestBuilder.build()

            try {
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                if (responseBody != null) {
                    synchronized(messageQueue) {
                        messageQueue.add(responseBody)
                    }
                }

                if (!response.isSuccessful) {
                    DebugLog.w("HttpTransport.send: HTTP ${response.code}")
                }
            } catch (e: Exception) {
                DebugLog.e("HttpTransport.send: ${e.message}")
            }
        }
    }

    override suspend fun receive(): String? {
        return synchronized(messageQueue) {
            if (queueIndex < messageQueue.size) {
                val msg = messageQueue[queueIndex]
                queueIndex++
                msg
            } else {
                null
            }
        }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            _isConnected = false
            synchronized(messageQueue) {
                messageQueue.clear()
                queueIndex = 0
            }
        }
    }
}
