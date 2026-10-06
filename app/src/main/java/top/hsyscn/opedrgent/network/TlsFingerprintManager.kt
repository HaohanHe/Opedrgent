package top.hsyscn.opedrgent.network

import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import top.hsyscn.opedrgent.utils.DebugLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * TLS指纹配置文件 - 模拟不同浏览器的TLS特征
 */
enum class TlsProfile(
    val displayName: String,
    val tlsVersions: List<String>,
    val cipherSuites: List<String>?,
    val description: String
) {
    // 此前 4 个"浏览器" profile 的 tlsVersions/cipherSuites 完全一致，指纹随机化为空操作（U35-08）；
    // 合并为 Modern（默认，启用 TLSv1.2/1.3）与 Compatible（仅 TLSv1.2，老旧服务器回退）两类。
    MODERN(
        "Modern",
        listOf("TLSv1.2", "TLSv1.3"),
        null,
        "现代 TLS 配置（默认，启用 TLSv1.3）"
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
    
    @Volatile private var currentProfile: TlsProfile = TlsProfile.MODERN
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
        val weights = doubleArrayOf(0.95, 0.05)  // 95% Modern / 5% Compatible
        
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
        currentProfile = TlsProfile.MODERN
        sessionStartTime = System.currentTimeMillis()
        requestCount = 0
        clientCache.clear()
    }
}
