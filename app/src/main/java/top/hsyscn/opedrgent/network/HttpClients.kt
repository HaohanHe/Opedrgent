package top.hsyscn.opedrgent.network

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import top.hsyscn.opedrgent.utils.DebugLog
import java.util.concurrent.TimeUnit

object HttpClients {
    
    /**
     * 连接池配置
     */
    private val connectionPool = ConnectionPool(
        maxIdleConnections = 10,           // 最大空闲连接数
        keepAliveDuration = 5,            // Keep-Alive时间（分钟）
        TimeUnit.MINUTES
    )
    
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
            
            // 超时配置
            .connectTimeout(15, TimeUnit.SECONDS)      // 连接超时：15秒
            .readTimeout(30, TimeUnit.SECONDS)          // 读取超时：30秒
            .writeTimeout(30, TimeUnit.SECONDS)         // 写入超时：30秒
            .callTimeout(60, TimeUnit.SECONDS)          // 总调用超时：60秒
            
            // 协议支持
            .protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
            
            // 重试配置
            .retryOnConnectionFailure(true)
            
            // DNS缓存
            // .dns(okhttp3.dns.Dns.SYSTEM)
            
            // Cookie管理（可选）
            // .cookieJar(CookieManager())
            
            // 拦截器（日志、缓存等）
            .addInterceptor(RequestLoggingInterceptor())
            
            .build()
            .also {
                DebugLog.i(
                    "HttpClients: initialized with connectionPool (maxIdle=10, keepAlive=5min)"
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
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }
    
    /**
     * 慢速请求客户端（用于大文件/复杂查询）
     */
    val longTimeout: OkHttpClient by lazy {
        default.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
    }
    
    /**
     * 流式响应专用客户端（用于SSE流）
     */
    val streaming: OkHttpClient by lazy {
        default.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)       // 5分钟读取超时（防止永久挂起）
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES)      // 10分钟总超时
            .build()
    }

    /**
     * 长时间运行客户端（用于TTS/ASR/工具执行等）
     */
    val longRunning: OkHttpClient by lazy {
        default.newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)       // 5分钟读取超时
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES)      // 10分钟总超时
            .build()
    }

    /**
     * 下载客户端（用于大文件下载）
     */
    val download: OkHttpClient by lazy {
        default.newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)      // 10分钟读取超时
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.MINUTES)      // 30分钟总超时
            .build()
    }

    /**
     * 获取自定义超时的客户端
     */
    fun getClientWithTimeout(
        connectSeconds: Long = 15,
        readSeconds: Long = 30,
        writeSeconds: Long = 30,
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
                "maxIdleConnections" to 10,
                "keepAliveDurationSec" to 5
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
            DebugLog.d(
                ">>> ${request.method} ${request.url.host}${request.url.encodedPath}" +
                if (request.url.query != null) "?${request.url.query}" else ""
            )
            
            request.headers.forEach { header ->
                if (!header.first.equals("Authorization", ignoreCase = true) && 
                    !header.first.equals("Cookie", ignoreCase = true)) {
                    DebugLog.d("    ${header.first}: ${header.second.take(50)}")
                } else {
                    DebugLog.d("    ${header.first}: [REDACTED]")
                }
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
}
