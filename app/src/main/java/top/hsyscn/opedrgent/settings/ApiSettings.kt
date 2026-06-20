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
    ProviderPreset("Google Gemma 4 (AI Studio)", "https://generativelanguage.googleapis.com/v1beta/openai", listOf("gemma-4-31b-it", "gemma-4-12b-it", "gemma-4-4b-it")),
    ProviderPreset("Google Gemini", "https://generativelanguage.googleapis.com/v1beta", listOf("gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.0-flash")),
    ProviderPreset("小米 MiMo (Token Plan 中国)", "https://token-plan-cn.xiaomimimo.com/v1", listOf("mimo-v2.5-pro", "mimo-v2.5")),
    ProviderPreset("小米 MiMo (Token Plan 新加坡)", "https://token-plan-sgp.xiaomimimo.com/v1", listOf("mimo-v2.5-pro", "mimo-v2.5")),
    ProviderPreset("小米 MiMo (Token Plan 欧洲)", "https://token-plan-ams.xiaomimimo.com/v1", listOf("mimo-v2.5-pro", "mimo-v2.5")),
    ProviderPreset("小米 MiMo (按量付费)", "https://api.xiaomimimo.com/v1", listOf("mimo-v2.5-pro", "mimo-v2.5")),
    ProviderPreset("OpenAI", "https://api.openai.com/v1", listOf("gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "o3-mini", "gpt-4.1-mini", "gpt-4.1-nano")),
    ProviderPreset("Anthropic", "https://api.anthropic.com/v1", listOf("claude-sonnet-4-20250514", "claude-3-5-haiku-20241022", "claude-opus-4-20250514")),
    ProviderPreset("DeepSeek", "https://api.deepseek.com", listOf("deepseek-v4-pro", "deepseek-v4-flash", "deepseek-chat", "deepseek-reasoner")),
    ProviderPreset("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", listOf("qwen-plus", "qwen-turbo", "qwen-max", "qwen-long")),
    ProviderPreset("月之暗面 Moonshot", "https://api.moonshot.cn/v1", listOf("moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k")),
    ProviderPreset("智谱 AI", "https://open.bigmodel.cn/api/paas/v4", listOf("glm-4-plus", "glm-4-flash", "glm-4-long")),
    ProviderPreset("零一万物", "https://api.lingyiwanwu.com/v1", listOf("yi-large", "yi-medium", "yi-spark")),
    ProviderPreset("SiliconFlow", "https://api.siliconflow.com/v1", listOf("deepseek-ai/DeepSeek-V3", "Qwen/Qwen2.5-72B-Instruct", "THUDM/glm-4-9b-chat")),
    ProviderPreset("Groq (Gemma 4)", "https://api.groq.com/openai/v1", listOf("gemma-4-31b-it", "gemma-4-12b-it", "gemma-4-4b-it", "llama-3.3-70b-versatile", "mixtral-8x7b-32768")),
    ProviderPreset("Ollama (本地)", "http://localhost:11434/v1", listOf("llama3", "qwen2.5", "deepseek-v2.5", "gemma4")),
    ProviderPreset("LM Studio (本地)", "http://localhost:1234/v1", listOf("local-model")),
    ProviderPreset("OpenRouter", "https://openrouter.ai/api/v1", listOf("anthropic/claude-sonnet-4", "openai/gpt-4o", "google/gemini-2.5-flash", "deepseek/deepseek-v4-pro", "deepseek/deepseek-v4-flash", "google/gemma-4-31b-it")),
    ProviderPreset("火山引擎", "https://ark.cn-beijing.volces.com/api/v3", listOf("doubao-pro-32k", "doubao-lite-32k")),
    ProviderPreset("阶跃星辰 Step Plan", "https://api.stepfun.com/step_plan/v1", listOf("step-3.7-flash", "step-3.5-flash", "step-3.5-flash-2603", "step-router-v1")),
    ProviderPreset("阶跃星辰 Messages (Anthropic)", "https://api.stepfun.com/step_plan", listOf("step-3.7-flash", "step-3.5-flash", "step-3.5-flash-2603", "step-router-v1")),
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

    fun getApiKey(): String? = securePrefs.getString("apiKey", null)?.trim()?.takeIf { it.isNotBlank() }

    fun getBaseUrl(): String = prefs.getString("baseUrl", "https://api.openai.com/v1")!!.trim()
    fun getModel(): String = prefs.getString("model", "gpt-4o-mini")!!.trim()
    fun hasApiKey(): Boolean = !securePrefs.getString("apiKey", null).isNullOrBlank()
    fun getMemory(): String = prefs.getString("memory", "")!!.trim()
    fun getLastSessionId(): String? = prefs.getString("lastSessionId", null)?.trim()?.takeIf { it.isNotBlank() }
    fun isTtsEnabled(): Boolean = prefs.getBoolean("ttsEnabled", false)
    fun isTtsAutoSpeak(): Boolean = prefs.getBoolean("ttsAutoSpeak", false)
    fun isTtsDownloadOnly(): Boolean = prefs.getBoolean("ttsDownloadOnly", false)  // 下载到本地不自动播放
    fun isTtsMimoEnabled(): Boolean = prefs.getBoolean("ttsMimoEnabled", false)
    /** TTS 引擎选择：system(系统) / mimo(MiMo) / stepaudio(阶跃 StepAudio) */
    fun getTtsEngine(): String {
        // 向后兼容：旧版用布尔值
        val engine = prefs.getString("ttsEngine", null)
        if (engine != null) return engine
        // 旧版迁移
        return if (prefs.getBoolean("ttsMimoEnabled", false)) "mimo" else "system"
    }
    fun saveTtsEngine(engine: String) {
        prefs.edit().putString("ttsEngine", engine).apply()
    }
    fun getTtsMimoVoice(): String = prefs.getString("ttsMimoVoice", "冰糖") ?: "冰糖"
    fun getTtsRate(): Float = prefs.getFloat("ttsRate", 1.0f)
    fun getTtsPitch(): Float = prefs.getFloat("ttsPitch", 1.0f)
    fun getTtsLocaleTag(): String = prefs.getString("ttsLocaleTag", "zh-CN")!!.trim()
    fun isSttEnabled(): Boolean = prefs.getBoolean("sttEnabled", true)
    fun getSttEngine(): String = prefs.getString("sttEngine", "local") ?: "local"
    fun saveSttEngine(engine: String) {
        prefs.edit().putString("sttEngine", engine).apply()
    }
    /** STT 识别模式: "pseudo" = 伪流式实时显示, "batch" = 录制后识别 */
    fun getSttStreamingMode(): String = prefs.getString("sttStreamingMode", "pseudo") ?: "pseudo"
    fun saveSttStreamingMode(mode: String) {
        prefs.edit().putString("sttStreamingMode", mode).apply()
    }
    fun isBackgroundRunning(): Boolean = prefs.getBoolean("backgroundRunning", false)
    fun isLocationEnabled(): Boolean = prefs.getBoolean("locationEnabled", false)
    fun isDebugMode(): Boolean = prefs.getBoolean("debugMode", false)
    fun isDeepThinking(): Boolean = prefs.getBoolean("deepThinking", true)
    fun isProviderWebSearchEnabled(): Boolean = prefs.getBoolean("providerWebSearchEnabled", true)

    /** 联网查询总开关：关闭后所有网络搜索功能禁用 */
    fun isWebSearchEnabled(): Boolean = prefs.getBoolean("webSearchEnabled", true)

    /** 联网查询来源选择："own" = Opedrgent 自有搜索引擎, "provider" = 模型厂商内置搜索 */
    fun getWebSearchSource(): String = prefs.getString("webSearchSource", "own") ?: "own"
    fun saveWebSearchSource(source: String) {
        prefs.edit().putString("webSearchSource", source).apply()
    }
    fun saveWebSearchEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("webSearchEnabled", enabled).apply()
    }

    fun getLastLocation(): String? = prefs.getString("lastLocation", null)?.trim()?.takeIf { it.isNotBlank() }

    fun getJinaApiKey(): String? = securePrefs.getString("jinaApiKey", null)?.trim()?.takeIf { it.isNotBlank() }
    fun getSearxngBaseUrl(): String? = prefs.getString("searxngBaseUrl", null)?.trim()?.takeIf { it.isNotBlank() }
    fun getBraveApiKey(): String? = securePrefs.getString("braveApiKey", null)?.trim()?.takeIf { it.isNotBlank() }
    fun getTavilyApiKey(): String? = securePrefs.getString("tavilyApiKey", null)?.trim()?.takeIf { it.isNotBlank() }
    fun getFirecrawlApiKey(): String? = securePrefs.getString("firecrawlApiKey", null)?.trim()?.takeIf { it.isNotBlank() }
    fun getMimoApiKey(): String? = securePrefs.getString("mimoApiKey", null)?.trim()?.takeIf { it.isNotBlank() }
    fun getSearchProviderOrder(): String = prefs.getString("searchProviderOrder", "bing,baidu,jina") ?: "bing,baidu,jina"

    fun saveJinaApiKey(key: String?) {
        if (key.isNullOrBlank()) {
            securePrefs.edit().remove("jinaApiKey").apply()
        } else {
            securePrefs.edit().putString("jinaApiKey", key.trim()).apply()
        }
    }

    fun saveSearxngBaseUrl(url: String?) {
        if (url.isNullOrBlank()) {
            prefs.edit().remove("searxngBaseUrl").apply()
        } else {
            prefs.edit().putString("searxngBaseUrl", url.trim().trimEnd('/')).apply()
        }
    }

    fun saveBraveApiKey(key: String?) {
        if (key.isNullOrBlank()) {
            securePrefs.edit().remove("braveApiKey").apply()
        } else {
            securePrefs.edit().putString("braveApiKey", key.trim()).apply()
        }
    }

    fun saveTavilyApiKey(key: String?) {
        if (key.isNullOrBlank()) {
            securePrefs.edit().remove("tavilyApiKey").apply()
        } else {
            securePrefs.edit().putString("tavilyApiKey", key.trim()).apply()
        }
    }

    fun saveFirecrawlApiKey(key: String?) {
        if (key.isNullOrBlank()) {
            securePrefs.edit().remove("firecrawlApiKey").apply()
        } else {
            securePrefs.edit().putString("firecrawlApiKey", key.trim()).apply()
        }
    }

    fun saveMimoApiKey(key: String?) {
        if (key.isNullOrBlank()) {
            securePrefs.edit().remove("mimoApiKey").apply()
        } else {
            securePrefs.edit().putString("mimoApiKey", key.trim()).apply()
        }
    }

    fun saveSearchProviderOrder(order: String) {
        prefs.edit().putString("searchProviderOrder", order).apply()
    }

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
        mimoEnabled: Boolean = false,
        mimoVoice: String = "冰糖",
        downloadOnly: Boolean = false,
    ) {
        prefs.edit()
            .putBoolean("ttsEnabled", enabled)
            .putBoolean("ttsAutoSpeak", autoSpeak)
            .putBoolean("ttsDownloadOnly", downloadOnly)
            .putBoolean("ttsMimoEnabled", mimoEnabled)
            .putString("ttsMimoVoice", mimoVoice)
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

    fun getAppLanguage(): String = prefs.getString("appLanguage", "system") ?: "system"
    fun saveAppLanguage(lang: String) {
        prefs.edit().putString("appLanguage", lang).apply()
    }

    fun isDeepResearch(): Boolean = prefs.getBoolean("deepResearch", false)

    fun saveDeepResearch(enabled: Boolean) {
        prefs.edit().putBoolean("deepResearch", enabled).apply()
    }

    fun saveLastLocation(location: String) {
        prefs.edit().putString("lastLocation", location).apply()
    }

    fun clearLocationCache() {
        prefs.edit().remove("lastLocation").remove("lastLocationDetail").apply()
    }

    fun getLastLocationDetail(): String? = prefs.getString("lastLocationDetail", null)?.trim()?.takeIf { it.isNotBlank() }

    fun saveLastLocationDetail(detail: String) {
        prefs.edit().putString("lastLocationDetail", detail).apply()
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

    fun isLocalModelEnabled(): Boolean = prefs.getBoolean("localModelEnabled", false)

    fun getEditorMode(): String = prefs.getString("editorMode", "richtext") ?: "richtext"
    fun saveEditorMode(mode: String) {
        prefs.edit().putString("editorMode", mode).apply()
    }

    /** 用户选择的本地 ASR 模型类型（空字符串 = 自动选择） */
    fun getSelectedLocalModel(): String = prefs.getString("selectedLocalModel", "") ?: ""
    fun saveSelectedLocalModel(model: String) {
        prefs.edit().putString("selectedLocalModel", model).apply()
    }

    fun getLocalModelId(): String? = prefs.getString("localModelId", null)?.trim()?.takeIf { it.isNotBlank() }

    fun getLocalTemperature(): Float = prefs.getFloat("localTemperature", 0.7f)
    fun getLocalTopK(): Int = prefs.getInt("localTopK", 64)
    fun getLocalTopP(): Float = prefs.getFloat("localTopP", 0.95f)
    fun getMaxOutputTokens(): Int = prefs.getInt("localMaxTokens", 0)

    fun saveLocalModelEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("localModelEnabled", enabled).apply()
    }

    fun saveLocalModelId(modelId: String?) {
        if (modelId.isNullOrBlank()) {
            prefs.edit().remove("localModelId").apply()
        } else {
            prefs.edit().putString("localModelId", modelId.trim()).apply()
        }
    }

    fun saveLocalParams(temperature: Float, topK: Int, topP: Float, maxTokens: Int) {
        prefs.edit()
            .putFloat("localTemperature", temperature)
            .putInt("localTopK", topK)
            .putFloat("localTopP", topP)
            .putInt("localMaxTokens", maxTokens)
            .apply()
    }

    // ==================== 录音时长设置 ====================

    /** 获取录音模式最大时长（小时），0 表示无限制 */
    fun getRecordingMaxHours(mode: String): Int {
        return prefs.getInt("recordingMaxHours_$mode", 0)  // 默认 0 = 无限制
    }

    /** 保存录音模式最大时长（小时），0 表示无限制 */
    fun saveRecordingMaxHours(mode: String, hours: Int) {
        prefs.edit().putInt("recordingMaxHours_$mode", hours.coerceIn(0, 24)).apply()
    }

    /** 获取所有模式的最大时长 */
    fun getAllRecordingMaxHours(): Map<String, Int> {
        val modes = listOf("VOICE_MEMO", "MEETING", "INTERNAL", "CLASSROOM")
        return modes.associateWith { getRecordingMaxHours(it) }
    }
}
