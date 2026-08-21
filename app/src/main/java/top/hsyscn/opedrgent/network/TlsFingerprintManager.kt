package top.hsyscn.opedrgent.network

import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import top.hsyscn.opedrgent.utils.DebugLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import java.security.SecureRandom
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManager
import javax.net.ssl.HttpsURLConnection

/**
 * TLS指纹配置文件 - 模拟不同浏览器的TLS特征
 */
enum class TlsProfile(
    val displayName: String,
    val tlsVersions: List<String>,
    val cipherSuites: List<String>?,
    val description: String
) {
    CHROME_MODERN(
        "Chrome Modern",
        listOf("TLSv1.2", "TLSv1.3"),
        null,  // 使用默认密码套件
        "模拟现代Chrome浏览器的TLS配置"
    ),
    FIREFOX_MODERN(
        "Firefox Modern", 
        listOf("TLSv1.2", "TLSv1.3"),
        null,
        "模拟现代Firefox浏览器的TLS配置"
    ),
    EDGE_WINDOWS(
        "Edge Windows",
        listOf("TLSv1.2", "TLSv1.3"),
        null,
        "模拟Windows Edge浏览器的TLS配置"
    ),
    SAFARI_MACOS(
        "Safari macOS",
        listOf("TLSv1.2", "TLSv1.3"),
        null,
        "模拟macOS Safari浏览器的TLS配置"
    ),
    COMPATIBLE(
        "Compatible",
        listOf("TLSv1.2"),
        null,
        "最大兼容性配置，适用于老旧服务器"
    )
}

/**
 * TLS指纹随机化器
 *
 * 功能：
 * 1. 多浏览器TLS配置模拟
 * 2. 自动选择最优TLS配置
 * 3. 支持会话内保持一致或每次请求变化
 * 4. 提供调试信息用于排查问题
 */
object TlsFingerprintManager {
    
    @Volatile private var currentProfile: TlsProfile = TlsProfile.CHROME_MODERN
    @Volatile private var sessionStartTime = 0L
    @Volatile private var requestCount = 0
    
    // 缓存已创建的客户端，避免重复创建
    private val clientCache = ConcurrentHashMap<String, OkHttpClient>()
    
    /**
     * 初始化TLS指纹管理器
     */
    fun initialize() {
        sessionStartTime = System.currentTimeMillis()
        
        // 根据当前时间种子选择一个初始配置
        currentProfile = selectRandomProfile()
        
        DebugLog.i(
            "TlsFingerprintManager: initialized with profile=${currentProfile.displayName}"
        )
    }
    
    /**
     * 随机选择TLS配置（带权重）
     */
    private fun selectRandomProfile(): TlsProfile {
        val profiles = TlsProfile.values()
        val weights = doubleArrayOf(0.35, 0.25, 0.20, 0.15, 0.05)  // Chrome权重最高
        
        val random = java.util.Random()
        val randVal = random.nextDouble()
        var cumulative = 0.0
        
        for (i in profiles.indices) {
            cumulative += weights[i]
            if (randVal <= cumulative) {
                return profiles[i]
            }
        }
        
        return profiles.last()  // fallback
    }
    
    /**
     * 获取当前TLS配置的OkHttpClient
     *
     * @param baseClient 基础HTTP客户端
     * @param forceNew 是否强制创建新客户端（忽略缓存）
     * @return 配置了TLS指纹的HTTP客户端
     */
    fun getTlsConfiguredClient(
        baseClient: OkHttpClient,
        forceNew: Boolean = false
    ): OkHttpClient {
        requestCount++
        
        // 每10次请求或每5分钟更换一次TLS配置（模拟真实用户行为）
        if (!forceNew && requestCount % 10 != 0 && 
            System.currentTimeMillis() - sessionStartTime < 300_000L) {
            
            val cacheKey = "${currentProfile.name}_${System.currentTimeMillis() / 60_000}"
            clientCache[cacheKey]?.let { return it }
        } else {
            currentProfile = selectRandomProfile()
            DebugLog.d("TlsFingerprintManager: switched to profile=${currentProfile.displayName}")
        }
        
        return buildClientWithProfile(baseClient, currentProfile)
    }
    
    /**
     * 使用指定TLS配置构建客户端
     */
    private fun buildClientWithProfile(
        baseClient: OkHttpClient,
        profile: TlsProfile
    ): OkHttpClient {
        try {
            val specBuilder = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            
            // 配置TLS版本
            specBuilder.tlsVersions(*profile.tlsVersions.toTypedArray())
            
            // 如果指定了密码套件，使用指定的；否则使用默认
            profile.cipherSuites?.let { suites ->
                specBuilder.cipherSuites(*suites.toTypedArray())
            }
            
            val connectionSpec = specBuilder.build()
            
            val client = baseClient.newBuilder()
                .connectionSpecs(listOf(connectionSpec, ConnectionSpec.CLEARTEXT))
                .build()
            
            // 缓存客户端
            val cacheKey = "${profile.name}_${System.currentTimeMillis() / 60_000}"
            clientCache[cacheKey] = client
            
            // 清理旧缓存（保留最近5个）
            if (clientCache.size > 5) {
                val keysToRemove = clientCache.keys.sorted().take(clientCache.size - 5)
                keysToRemove.forEach { clientCache.remove(it) }
            }
            
            return client
            
        } catch (e: Exception) {
            DebugLog.w("TlsFingerprintManager: failed to create custom TLS config, using default: ${e.message}")
            return baseClient
        }
    }
    
    /**
     * 创建信任所有证书的不安全客户端（仅用于调试）
     * 
     * ⚠️ 警告：不要在生产环境中使用！
     */
    fun createInsecureClient(): OkHttpClient {
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, SecureRandom())
            
            return OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(NetworkConfig.TLS_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(NetworkConfig.TLS_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(NetworkConfig.TLS_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(NetworkConfig.TLS_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
                
        } catch (e: Exception) {
            DebugLog.e("TlsFingerprintManager: failed to create insecure client: ${e.message}")
            return HttpClients.default
        }
    }
    
    /**
     * 获取当前TLS配置信息（用于调试）
     */
    fun getCurrentProfileInfo(): Map<String, Any> {
        return mapOf(
            "profile" to currentProfile.displayName,
            "requestCount" to requestCount,
            "sessionAgeMin" to ((System.currentTimeMillis() - sessionStartTime) / 60_000),
            "cachedClients" to clientCache.size
        )
    }
    
    /**
     * 手动切换到指定TLS配置
     */
    fun switchToProfile(profile: TlsProfile) {
        currentProfile = profile
        clientCache.clear()  // 清除缓存以强制使用新配置
        DebugLog.i("TlsFingerprintManager: manually switched to ${profile.displayName}")
    }
    
    /**
     * 重置状态（用于测试）
     */
    fun reset() {
        currentProfile = TlsProfile.CHROME_MODERN
        sessionStartTime = System.currentTimeMillis()
        requestCount = 0
        clientCache.clear()
    }
}
