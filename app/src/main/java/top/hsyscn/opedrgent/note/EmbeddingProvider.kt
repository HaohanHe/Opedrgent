package top.hsyscn.opedrgent.note

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import top.hsyscn.opedrgent.network.HttpClients
import top.hsyscn.opedrgent.settings.ApiConfig
import top.hsyscn.opedrgent.settings.ApiSettings
import top.hsyscn.opedrgent.utils.DebugLog
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 知识图谱 Embedding 提供器抽象层。
 *
 * 该接口屏蔽本地哈希 Embedding 与云端 API Embedding 的差异，
 * 供笔记检索、关联推荐等模块统一调用。
 */
interface EmbeddingProvider {
    /** 提供器标识，如 "local" / "cloud" */
    fun providerName(): String

    /** 将文本编码为浮点向量 */
    suspend fun embed(text: String): FloatArray

    /** 当前提供器是否可用 */
    fun isAvailable(): Boolean

    /** 向量维度 */
    fun dimension(): Int
}

/**
 * 本地 Embedding 提供器。
 *
 * 基于 LocalTokenizer 分词 + LocalEntityExtractor 提取实体，
 * 将词/实体哈希到固定 512 维，并进行 L2 归一化。
 * 不依赖网络，始终可用，适合离线场景与隐私敏感场景。
 */
class LocalEmbeddingProvider(private val store: KnowledgeGraphStore) : EmbeddingProvider {

    companion object {
        private const val TAG = "LocalEmbeddingProvider"
        private const val DIMENSION = 512
    }

    override fun providerName(): String = "local"

    override fun isAvailable(): Boolean = true

    override fun dimension(): Int = DIMENSION

    override suspend fun embed(text: String): FloatArray = withContext(Dispatchers.Default) {
        if (text.isBlank()) {
            return@withContext FloatArray(DIMENSION) { 0f }
        }

        val tokens = LocalTokenizer.tokenize(text)
            .filter { it.length >= 2 && it !in LocalTokenizer.stopWords }
        val entities = LocalEntityExtractor.extractEntities(text)
            .map { it.name }
            .filter { it.length >= 2 }
        val features = (tokens + entities).distinct()

        val vector = FloatArray(DIMENSION) { 0f }
        for (feature in features) {
            val idx = abs(feature.hashCode()) % DIMENSION
            vector[idx] += 1f
        }

        normalizeL2(vector)
        DebugLog.d(TAG, "embedded ${features.size} features into $DIMENSION dims")
        vector
    }

    private fun normalizeL2(vector: FloatArray) {
        val norm = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
        if (norm > 0f) {
            for (i in vector.indices) {
                vector[i] /= norm
            }
        }
    }
}

/**
 * 云端 Embedding 提供器。
 *
 * 调用 OpenAI 兼容格式的 /v1/embeddings 接口，
 * 返回向量长度作为实际维度（默认 1536）。
 */
class CloudEmbeddingProvider(
    private val apiConfig: ApiConfig?,
    private val http: OkHttpClient = HttpClients.default,
) : EmbeddingProvider {

    companion object {
        private const val TAG = "CloudEmbeddingProvider"
        private const val DEFAULT_DIMENSION = 1536
        private const val DEFAULT_MODEL = "text-embedding-3-small"
    }

    private var cachedDimension: Int = DEFAULT_DIMENSION

    override fun providerName(): String = "cloud"

    override fun isAvailable(): Boolean =
        apiConfig != null && apiConfig.apiKey.isNotBlank()

    override fun dimension(): Int = cachedDimension

    override suspend fun embed(text: String): FloatArray = withContext(Dispatchers.IO) {
        val config = apiConfig ?: throw IllegalStateException("Cloud embedding API config is null")
        if (text.isBlank()) {
            return@withContext FloatArray(cachedDimension) { 0f }
        }

        val model = resolveModel(config.model)
        val url = buildEmbeddingUrl(config.baseUrl)
        val body = JSONObject().apply {
            put("input", text)
            put("model", model)
        }.toString()

        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .applyAuth(config.apiKey)
            .build()

        DebugLog.i(TAG, "embed → $url model=$model")

        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val msg = runCatching {
                    JSONObject(raw).optJSONObject("error")?.optString("message")
                        ?: JSONObject(raw).optString("message").takeIf { it.isNotBlank() }
                }.getOrNull()
                throw IllegalStateException(
                    msg?.takeIf { it.isNotBlank() } ?: "Embedding request failed: HTTP ${response.code}"
                )
            }

            val root = JSONObject(raw)
            val data = root.optJSONArray("data")
                ?: throw IllegalStateException("Embedding response missing data array")
            if (data.length() == 0) {
                throw IllegalStateException("Embedding response data array is empty")
            }

            val embeddingArray = data.getJSONObject(0).optJSONArray("embedding")
                ?: throw IllegalStateException("Embedding response missing embedding array")

            val result = FloatArray(embeddingArray.length()) { i ->
                embeddingArray.getDouble(i).toFloat()
            }
            cachedDimension = result.size
            result
        }
    }

    private fun resolveModel(model: String): String {
        return if (model.contains("embedding", ignoreCase = true)) model else DEFAULT_MODEL
    }

    private fun buildEmbeddingUrl(baseUrl: String): String {
        val base = baseUrl.trimEnd('/')
        return if (base.endsWith("/v1") || base.endsWith("/v2") || base.endsWith("/v3")
            || base.endsWith("/openai")
        ) {
            "$base/embeddings"
        } else {
            "$base/v1/embeddings"
        }
    }

    private fun Request.Builder.applyAuth(apiKey: String): Request.Builder {
        return when {
            apiKey.startsWith("tp-") -> header("api-key", apiKey)
            apiKey.startsWith("AIza") -> header("x-goog-api-key", apiKey)
            else -> header("Authorization", "Bearer $apiKey")
        }
    }
}

/**
 * 云 -> 本地 Embedding 回退提供器。
 *
 * 优先使用云端能力，失败时自动降级到本地 Embedding，
 * 保证知识图谱在弱网/无 key 场景下仍可正常工作。
 */
class FallbackEmbeddingProvider(
    private val primary: CloudEmbeddingProvider,
    private val fallback: LocalEmbeddingProvider,
) : EmbeddingProvider {

    companion object {
        private const val TAG = "FallbackEmbeddingProvider"
    }

    override fun providerName(): String = "cloud-fallback"

    override fun isAvailable(): Boolean = true

    override fun dimension(): Int = fallback.dimension()

    override suspend fun embed(text: String): FloatArray = try {
        primary.embed(text)
    } catch (e: Exception) {
        DebugLog.w(TAG, "cloud embedding failed, falling back to local: ${e.message}")
        fallback.embed(text)
    }
}

/**
 * Embedding 提供器工厂。
 *
 * 根据设置决定使用本地能力还是云端能力，云端不可用时自动降级。
 */
object EmbeddingProviderFactory {

    fun create(
        context: Context,
        apiSettings: ApiSettings,
        store: KnowledgeGraphStore,
    ): EmbeddingProvider {
        return if (apiSettings.isLocalModelEnabled()) {
            LocalEmbeddingProvider(store)
        } else {
            val cloud = CloudEmbeddingProvider(apiSettings.getApiConfig(), HttpClients.default)
            val local = LocalEmbeddingProvider(store)
            if (cloud.isAvailable()) {
                FallbackEmbeddingProvider(primary = cloud, fallback = local)
            } else {
                local
            }
        }
    }
}
