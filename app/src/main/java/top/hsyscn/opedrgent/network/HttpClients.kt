package top.hsyscn.opedrgent.network

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import top.hsyscn.opedrgent.cloud.CloudRateLimitInterceptor
import top.hsyscn.opedrgent.utils.DebugLog
import java.util.concurrent.TimeUnit

object HttpClients {
    
    /**
     * 连接池配置
     */
    private val connectionPool = ConnectionPool(
        maxIdleConnections = NetworkConfig.MAX_IDLE_CONNECTIONS,
        keepAliveDuration = 5,
        TimeUnit.MINUTES
    )

    /**
     * 调度器配置
     */
    private val dispatcher = Dispatcher().apply {
        maxRequests = NetworkConfig.MAX_REQUESTS
        maxRequestsPerHost = NetworkConfig.MAX_REQUESTS_PER_HOST
    }
    
    /**
     * 默认HTTP客户端（优化版）
     * 
     * 配置特点：
     * - 连接池复用，减少TCP握手开销
     * - 合理的超时设置
     * - 自动重试机制
     * - 压缩支持
     */
    val default: OkHttpClient by lazy {
        // 初始化TLS指纹管理器
        TlsFingerprintManager.initialize()
        
        OkHttpClient.Builder()
            // 连接池配置
            .connectionPool(connectionPool)
            
            // 调度器配置
            .dispatcher(dispatcher)
            
            // 超时配置
            .connectTimeout(NetworkConfig.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(NetworkConfig.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(NetworkConfig.WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(NetworkConfig.DEFAULT_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)          // 总调用超时：60秒
            
            // 协议支持
            .protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
            
            // 重试配置
            .retryOnConnectionFailure(true)
            
            // DNS缓存
            // .dns(okhttp3.dns.Dns.SYSTEM)
            
            // Cookie管理（可选）
            // .cookieJar(CookieManager())
            
            // 429 限流退避重试（所有派生客户端自动继承）
            .addInterceptor(CloudRateLimitInterceptor())

            // 拦截器（日志、缓存等）
            .addInterceptor(RequestLoggingInterceptor())
            
            .build()
            .also {
                DebugLog.i(
                    "HttpClients: initialized with connectionPool (maxIdle=${NetworkConfig.MAX_IDLE_CONNECTIONS}, keepAlive=5min), dispatcher (maxRequests=${NetworkConfig.MAX_REQUESTS}, maxRequestsPerHost=${NetworkConfig.MAX_REQUESTS_PER_HOST})"
                )
            }
    }
    
    /**
     * 获取配置了随机TLS指纹的客户端
     */
    fun getTlsClient(forceNew: Boolean = false): OkHttpClient {
        return TlsFingerprintManager.getTlsConfiguredClient(default, forceNew)
    }
    
    /**
     * 快速请求客户端（用于低延迟API调用）
     */
    val quickTimeout: OkHttpClient by lazy {
        default.newBuilder()
            .connectTimeout(NetworkConfig.QUICK_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(NetworkConfig.QUICK_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(NetworkConfig.QUICK_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(NetworkConfig.QUICK_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }
    
    /**
     * 慢速请求客户端（用于大文件/复杂查询）
     */
    val longTimeout: OkHttpClient by lazy {
        default.newBuilder()
            .connectTimeout(NetworkConfig.LONG_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(NetworkConfig.LONG_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(NetworkConfig.LONG_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(NetworkConfig.LONG_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }
    
    /**
     * 流式响应专用客户端（用于SSE流）
     */
    val streaming: OkHttpClient by lazy {
        default.newBuilder()
            .connectTimeout(NetworkConfig.STREAMING_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(NetworkConfig.STREAMING_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)       // 5分钟读取超时（防止永久挂起）
            .writeTimeout(NetworkConfig.STREAMING_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(NetworkConfig.STREAMING_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)      // 10分钟总超时
            .build()
    }

    /**
     * 长时间运行客户端（用于TTS/ASR/工具执行等）
     */
    val longRunning: OkHttpClient by lazy {
        default.newBuilder()
            .connectTimeout(NetworkConfig.LONG_RUNNING_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(NetworkConfig.LONG_RUNNING_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)       // 5分钟读取超时
            .writeTimeout(NetworkConfig.LONG_RUNNING_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(NetworkConfig.LONG_RUNNING_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)      // 10分钟总超时
            .build()
    }

    /**
     * 下载客户端（用于大文件下载）
     *
     * 注意：模型文件可达数 GB，在移动网络/慢速连接上可能需要数小时，
     * 因此不设置总调用超时，读取超时也放宽到 30 分钟，避免大模型下载被中途掐断。
     */
    val download: OkHttpClient by lazy {
        default.newBuilder()
            .connectTimeout(NetworkConfig.DOWNLOAD_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(NetworkConfig.DOWNLOAD_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)      // 30分钟读取超时，容忍长时间无数据
            .writeTimeout(NetworkConfig.DOWNLOAD_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // 不设置 callTimeout，允许数小时的大文件下载
            .build()
    }

    /**
     * 获取自定义超时的客户端
     */
    fun getClientWithTimeout(
        connectSeconds: Long = NetworkConfig.CONNECT_TIMEOUT_SECONDS,
        readSeconds: Long = NetworkConfig.READ_TIMEOUT_SECONDS,
        writeSeconds: Long = NetworkConfig.WRITE_TIMEOUT_SECONDS,
        callSeconds: Long = 60
    ): OkHttpClient {
        return default.newBuilder()
            .connectTimeout(connectSeconds, TimeUnit.SECONDS)
            .readTimeout(readSeconds, TimeUnit.SECONDS)
            .writeTimeout(writeSeconds, TimeUnit.SECONDS)
            .callTimeout(callSeconds, TimeUnit.SECONDS)
            .build()
    }
    
    /**
     * 获取性能统计信息
     */
    fun getPerformanceStats(): Map<String, Any> {
        return mapOf(
            "connectionPool" to mapOf(
                "maxIdleConnections" to NetworkConfig.MAX_IDLE_CONNECTIONS,
                "keepAliveDurationSec" to 5
            ),
            "dispatcher" to mapOf(
                "maxRequests" to NetworkConfig.MAX_REQUESTS,
                "maxRequestsPerHost" to NetworkConfig.MAX_REQUESTS_PER_HOST
            ),
            "tlsProfile" to TlsFingerprintManager.getCurrentProfileInfo(),
            "cacheStats" to emptyMap<String, Any>()  // WebSearcher cache stats available via WebSearcher instance
        )
    }
}

/**
 * 请求日志拦截器
 * 用于调试和监控网络请求
 */
class RequestLoggingInterceptor : okhttp3.Interceptor {
    
    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        
        val startTime = System.nanoTime()
        
        // 记录请求信息（仅DEBUG级别）
        if (DebugLog.isDebugEnabled()) {
            val rawQuery = request.url.query
            DebugLog.d(
                ">>> ${request.method} ${request.url.host}${request.url.encodedPath}" +
                if (rawQuery != null) "?${redactQuery(rawQuery)}" else ""
            )
            
            request.headers.forEach { header ->
                DebugLog.d("    ${header.first}: ${redactHeader(header.first, header.second)}")
            }
        }
        
        val response = chain.proceed(request)
        
        val endTime = System.nanoTime()
        val durationMs = (endTime - startTime) / 1_000_000
        
        // 记录响应信息
        if (DebugLog.isEnabled()) {
            val logLevel = when {
                response.code >= 500 -> "e"
                response.code >= 400 -> "w"
                else -> "d"
            }
            
            when (logLevel) {
                "e" -> DebugLog.e(
                    "<<< ${response.code} ${durationMs}ms " +
                    "${request.url.host} - ${response.message}"
                )
                "w" -> DebugLog.w(
                    "<<< ${response.code} ${durationMs}ms " +
                    "${request.url.host} - ${response.message}"
                )
                else -> DebugLog.d(
                    "<<< ${response.code} ${durationMs}ms " +
                    "${request.url.host} (${response.body?.contentLength()} bytes)"
                )
            }
        }
        
        return response
    }

    /**
     * 敏感请求头名单（字段名匹配，忽略大小写）。命中则掩码，不打印完整凭据。
     * 仅用于日志脱敏，不影响实际发出的请求头。
     */
    private val sensitiveHeaders = setOf(
        "authorization",
        "proxy-authorization",
        "cookie",
        "x-goog-api-key",
        "api-key",
        "x-api-key",
    )

    /** URL query 中需要掩码的参数名片段（归一化为小写后子串匹配）。 */
    private val sensitiveQueryKeys = setOf(
        "key", "api_key", "apikey", "token", "secret",
    )

    private fun redactHeader(name: String, value: String): String {
        if (sensitiveHeaders.any { name.equals(it, ignoreCase = true) }) {
            return maskSecret(value)
        }
        return value.take(50)
    }

    private fun maskSecret(value: String): String {
        if (value.length <= 8) return "****"
        return value.take(4) + "****" + value.takeLast(4)
    }

    private fun redactQuery(query: String): String {
        // 解析 query 参数名，逐项掩码敏感值后再拼接；不改动实际请求 URL。
        return query.split("&").joinToString("&") { pair ->
            val idx = pair.indexOf('=')
            if (idx < 0) return@joinToString pair
            val k = pair.substring(0, idx)
            val v = pair.substring(idx + 1)
            val norm = k.lowercase().replace('-', '_')
            val masked = sensitiveQueryKeys.any { norm.contains(it) }
            if (masked) "$k=" + maskSecret(v) else pair
        }
    }
}
