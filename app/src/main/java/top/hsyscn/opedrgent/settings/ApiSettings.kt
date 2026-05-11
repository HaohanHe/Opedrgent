package top.hsyscn.opedrgent.settings

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.net.URI

data class ApiConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
)

data class ProviderPreset(
    val name: String,
    val baseUrl: String,
    val models: List<String>,
)

val PROVIDER_PRESETS = listOf(
    ProviderPreset("小米 MiMo (Token Plan 中国)", "https://token-plan-cn.xiaomimimo.com/v1", listOf("mimo-v2.5-pro", "mimo-v2.5", "mimo-v2-flash", "mimo-v2-pro", "mimo-v2-omni")),
    ProviderPreset("小米 MiMo (Token Plan 新加坡)", "https://token-plan-sgp.xiaomimimo.com/v1", listOf("mimo-v2.5-pro", "mimo-v2.5", "mimo-v2-flash", "mimo-v2-pro", "mimo-v2-omni")),
    ProviderPreset("小米 MiMo (Token Plan 欧洲)", "https://token-plan-ams.xiaomimimo.com/v1", listOf("mimo-v2.5-pro", "mimo-v2.5", "mimo-v2-flash", "mimo-v2-pro", "mimo-v2-omni")),
    ProviderPreset("小米 MiMo (按量付费)", "https://api.xiaomimimo.com/v1", listOf("mimo-v2.5-pro", "mimo-v2.5", "mimo-v2-flash", "mimo-v2-pro", "mimo-v2-omni")),
    ProviderPreset("OpenAI", "https://api.openai.com/v1", listOf("gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "o3-mini", "gpt-4.1-mini", "gpt-4.1-nano")),
    ProviderPreset("Anthropic", "https://api.anthropic.com/v1", listOf("claude-sonnet-4-20250514", "claude-3-5-haiku-20241022", "claude-opus-4-20250514")),
    ProviderPreset("Google Gemini", "https://generativelanguage.googleapis.com/v1beta", listOf("gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.0-flash")),
    ProviderPreset("DeepSeek", "https://api.deepseek.com/v1", listOf("deepseek-chat", "deepseek-reasoner")),
    ProviderPreset("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", listOf("qwen-plus", "qwen-turbo", "qwen-max", "qwen-long")),
    ProviderPreset("月之暗面 Moonshot", "https://api.moonshot.cn/v1", listOf("moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k")),
    ProviderPreset("智谱 AI", "https://open.bigmodel.cn/api/paas/v4", listOf("glm-4-plus", "glm-4-flash", "glm-4-long")),
    ProviderPreset("零一万物", "https://api.lingyiwanwu.com/v1", listOf("yi-large", "yi-medium", "yi-spark")),
    ProviderPreset("SiliconFlow", "https://api.siliconflow.com/v1", listOf("deepseek-ai/DeepSeek-V3", "Qwen/Qwen2.5-72B-Instruct", "THUDM/glm-4-9b-chat")),
    ProviderPreset("Groq", "https://api.groq.com/openai/v1", listOf("llama-3.3-70b-versatile", "mixtral-8x7b-32768", "gemma2-9b-it")),
    ProviderPreset("Ollama (本地)", "http://localhost:11434/v1", listOf("llama3", "qwen2.5", "deepseek-v2.5", "gemma2")),
    ProviderPreset("LM Studio (本地)", "http://localhost:1234/v1", listOf("local-model")),
    ProviderPreset("OpenRouter", "https://openrouter.ai/api/v1", listOf("anthropic/claude-sonnet-4", "openai/gpt-4o", "google/gemini-2.5-flash", "deepseek/deepseek-chat")),
    ProviderPreset("火山引擎", "https://ark.cn-beijing.volces.com/api/v3", listOf("doubao-pro-32k", "doubao-lite-32k")),
)

class ApiSettings(private val context: Context) {
    private val prefs = context.getSharedPreferences("opedrgent_settings", Context.MODE_PRIVATE)
    private val securePrefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "opedrgent_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun getApiConfig(): ApiConfig? {
        val apiKey = securePrefs.getString("apiKey", null)?.trim().orEmpty()
        if (apiKey.isEmpty()) return null
        val baseUrl = prefs.getString("baseUrl", "https://api.openai.com/v1")!!.trim()
        val model = prefs.getString("model", "gpt-4o-mini")!!.trim()
        return ApiConfig(baseUrl = baseUrl, apiKey = apiKey, model = model)
    }

    fun getBaseUrl(): String = prefs.getString("baseUrl", "https://api.openai.com/v1")!!.trim()
    fun getModel(): String = prefs.getString("model", "gpt-4o-mini")!!.trim()
    fun hasApiKey(): Boolean = !securePrefs.getString("apiKey", null).isNullOrBlank()
    fun getMemory(): String = prefs.getString("memory", "")!!.trim()
    fun getLastSessionId(): String? = prefs.getString("lastSessionId", null)?.trim()?.takeIf { it.isNotBlank() }
    fun isTtsEnabled(): Boolean = prefs.getBoolean("ttsEnabled", false)
    fun isTtsAutoSpeak(): Boolean = prefs.getBoolean("ttsAutoSpeak", false)
    fun getTtsRate(): Float = prefs.getFloat("ttsRate", 1.0f)
    fun getTtsPitch(): Float = prefs.getFloat("ttsPitch", 1.0f)
    fun getTtsLocaleTag(): String = prefs.getString("ttsLocaleTag", "zh-CN")!!.trim()
    fun isSttEnabled(): Boolean = prefs.getBoolean("sttEnabled", true)
    fun isBackgroundRunning(): Boolean = prefs.getBoolean("backgroundRunning", false)
    fun isLocationEnabled(): Boolean = prefs.getBoolean("locationEnabled", false)
    fun isDebugMode(): Boolean = prefs.getBoolean("debugMode", false)
    fun isDeepThinking(): Boolean = prefs.getBoolean("deepThinking", true)
    fun isProviderWebSearchEnabled(): Boolean = prefs.getBoolean("providerWebSearchEnabled", true)
    fun getLastLocation(): String? = prefs.getString("lastLocation", null)?.trim()?.takeIf { it.isNotBlank() }

    fun save(baseUrl: String, apiKey: String?, model: String) {
        val normalizedBaseUrl = normalizeBaseUrl(baseUrl.trim())
        if (!isSafeBaseUrl(normalizedBaseUrl)) {
            throw IllegalArgumentException("Base URL 不安全：仅支持 https（本地 http 仅限 localhost）")
        }
        prefs.edit()
            .putString("baseUrl", normalizedBaseUrl)
            .putString("model", model.trim())
            .apply()
        val key = apiKey?.trim().orEmpty()
        if (key.isNotEmpty()) {
            securePrefs.edit().putString("apiKey", key).apply()
        }
    }

    fun saveMemory(memory: String) {
        prefs.edit().putString("memory", memory.take(2000)).apply()
    }

    fun saveTts(
        enabled: Boolean,
        autoSpeak: Boolean,
        rate: Float,
        pitch: Float,
        localeTag: String,
    ) {
        prefs.edit()
            .putBoolean("ttsEnabled", enabled)
            .putBoolean("ttsAutoSpeak", autoSpeak)
            .putFloat("ttsRate", rate)
            .putFloat("ttsPitch", pitch)
            .putString("ttsLocaleTag", localeTag.trim())
            .apply()
    }

    fun saveSttEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("sttEnabled", enabled).apply()
    }

    fun saveBackgroundRunning(enabled: Boolean) {
        prefs.edit().putBoolean("backgroundRunning", enabled).apply()
    }

    fun saveLocationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("locationEnabled", enabled).apply()
    }

    fun saveDebugMode(enabled: Boolean) {
        prefs.edit().putBoolean("debugMode", enabled).apply()
    }

    fun saveDeepThinking(enabled: Boolean) {
        prefs.edit().putBoolean("deepThinking", enabled).apply()
    }

    fun saveProviderWebSearchEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("providerWebSearchEnabled", enabled).apply()
    }

    fun isDeepResearch(): Boolean = prefs.getBoolean("deepResearch", false)

    fun saveDeepResearch(enabled: Boolean) {
        prefs.edit().putBoolean("deepResearch", enabled).apply()
    }

    fun saveLastLocation(location: String) {
        prefs.edit().putString("lastLocation", location).apply()
    }

    fun setLastSessionId(id: String?) {
        prefs.edit().putString("lastSessionId", id).apply()
    }

    fun clearApiKey() {
        securePrefs.edit().remove("apiKey").apply()
    }

    private fun normalizeBaseUrl(url: String): String {
        val u = url.trim().trimEnd('/')
        return u
    }

    private fun isSafeBaseUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        val host = uri.host?.lowercase() ?: return false
        if (scheme == "http") {
            return host == "localhost" || host == "127.0.0.1" || host == "::1"
        }
        if (scheme != "https") return false
        if (host == "localhost" || host.endsWith(".local")) return false
        if (host == "127.0.0.1" || host == "::1") return false
        val ipv4 = Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$")
        if (ipv4.matches(host)) {
            val parts = host.split(".").mapNotNull { it.toIntOrNull() }
            if (parts.size != 4) return false
            val a = parts[0]
            val b = parts[1]
            if (a == 10) return false
            if (a == 127) return false
            if (a == 192 && b == 168) return false
            if (a == 172 && b in 16..31) return false
            if (a == 169 && b == 254) return false
        }
        if (host.startsWith("fe80:") || host.startsWith("fc") || host.startsWith("fd")) return false
        return true
    }
}
