package top.hsyscn.opedrgent.note

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import top.hsyscn.opedrgent.network.HttpClients
import top.hsyscn.opedrgent.settings.ApiConfig
import top.hsyscn.opedrgent.settings.ApiSettings
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.IOException
import kotlin.math.abs
import kotlin.math.ln
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

    /** 批量将文本编码为浮点向量 */
    suspend fun embedBatch(texts: List<String>): List<FloatArray> = texts.map { embed(it) }

    /** 当前提供器是否可用 */
    fun isAvailable(): Boolean

    /** 向量维度 */
    fun dimension(): Int
}

/**
 * 本地 Embedding 提供器。
 *
 * 基于 LocalTokenizer 分词 + LocalEntityExtractor 提取实体，
 * 计算 512 维 TF-IDF 加权哈希向量，并进行 L2 归一化。
 * 不依赖网络，始终可用，适合离线场景与隐私敏感场景。
 */
class LocalEmbeddingProvider(private val store: KnowledgeGraphStore) : EmbeddingProvider {

    companion object {
        private const val TAG = "LocalEmbeddingProvider"
        private const val DIMENSION = 512
    }

    override fun providerName(): String = "local-tfidf"

    override fun isAvailable(): Boolean = true

    override fun dimension(): Int = DIMENSION

    override suspend fun embed(text: String): FloatArray =
        embedBatch(listOf(text)).first()

    override suspend fun embedBatch(texts: List<String>): List<FloatArray> = withContext(Dispatchers.Default) {
        if (texts.isEmpty()) {
            return@withContext emptyList<FloatArray>()
        }

        data class DocFeatures(
            val allFeatures: List<String>,
            val titleFeatures: List<String>?,
        )

        val docs = texts.map { text ->
            if (text.isBlank()) {
                DocFeatures(emptyList(), null)
            } else {
                val tokens = LocalTokenizer.tokenize(text)
                val entities = LocalEntityExtractor.extractEntities(text).map { it.name }
                val allFeatures = (tokens + entities)
                    .filter { it.length >= 2 && it !in LocalTokenizer.stopWords }

                val titleFeatures = if ('\n' in text) {
                    val firstLine = text.substringBefore('\n')
                    val titleTokens = LocalTokenizer.tokenize(firstLine)
                    val titleEntities = LocalEntityExtractor.extractEntities(firstLine).map { it.name }
                    (titleTokens + titleEntities)
                        .filter { it.length >= 2 && it !in LocalTokenizer.stopWords }
                } else {
                    null
                }

                DocFeatures(allFeatures, titleFeatures)
            }
        }

        val allFeaturesInBatch = docs.fold(mutableSetOf<String>()) { acc, doc ->
            acc.addAll(doc.allFeatures)
            doc.titleFeatures?.let { acc.addAll(it) }
            acc
        }

        val existingNodes = store.getAllNodes()
        val existingNodeCount = existingNodes.size
        val df = mutableMapOf<String, Int>()

        // 用已有图节点的关键词估算全局文档频率
        if (allFeaturesInBatch.isNotEmpty()) {
            for (node in existingNodes) {
                val nodeKeywords = node.keywords
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .toSet()
                for (feature in nodeKeywords.intersect(allFeaturesInBatch)) {
                    df[feature] = (df[feature] ?: 0) + 1
                }
            }

            // 用已有实体关联补充文档频率
            for (entity in store.getAllEntities()) {
                if (entity.name in allFeaturesInBatch) {
                    val nodeCount = store.getNodesForEntity(entity.id).size
                    df[entity.name] = (df[entity.name] ?: 0) + nodeCount
                }
            }
        }

        // 加上当前 batch 自身的文档频率
        for (doc in docs) {
            val present = mutableSetOf<String>()
            present.addAll(doc.allFeatures)
            doc.titleFeatures?.let { present.addAll(it) }
            for (feature in present) {
                df[feature] = (df[feature] ?: 0) + 1
            }
        }

        val totalDocs = existingNodeCount + texts.size
        val idf = df.mapValues { ln(totalDocs.toDouble() / it.value).toFloat() + 1f }

        val result = docs.map { doc ->
            if (doc.allFeatures.isEmpty() && doc.titleFeatures.isNullOrEmpty()) {
                FloatArray(DIMENSION) { 0f }
            } else {
                val tf = mutableMapOf<String, Int>()
                for (feature in doc.allFeatures) {
                    tf[feature] = (tf[feature] ?: 0) + 1
                }
                // 标题行权重 3x：allFeatures 已统计一次，再额外补充 2 次
                doc.titleFeatures?.let { title ->
                    for (feature in title) {
                        tf[feature] = (tf[feature] ?: 0) + 2
                    }
                }

                val vector = FloatArray(DIMENSION) { 0f }
                for ((feature, count) in tf) {
                    val idx = abs(feature.hashCode()) % DIMENSION
                    vector[idx] += count * (idf[feature] ?: 0f)
                }
                normalizeL2(vector)
                vector
            }
        }

        val totalFeatures = docs.sumOf { it.allFeatures.size + (it.titleFeatures?.size ?: 0) }
        DebugLog.d(TAG, "embedded ${texts.size} texts, $totalFeatures features into $DIMENSION dims")
        result
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
        private const val BATCH_CHUNK_SIZE = 32
        private const val MAX_RETRIES = 3
    }

    private class EmbeddingRetryException(message: String) : Exception(message)

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

    override suspend fun embedBatch(texts: List<String>): List<FloatArray> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) {
            return@withContext emptyList<FloatArray>()
        }

        val config = apiConfig ?: throw IllegalStateException("Cloud embedding API config is null")
        val model = resolveModel(config.model)
        val url = buildEmbeddingUrl(config.baseUrl)
        val backoffMs = listOf(500L, 1000L, 2000L)

        // 空白文本保持与 embed(text) 一致：直接返回零向量
        val placeholders = mutableMapOf<Int, FloatArray>()
        val nonBlankIndexed = texts.mapIndexedNotNull { index, text ->
            if (text.isBlank()) {
                placeholders[index] = FloatArray(cachedDimension) { 0f }
                null
            } else {
                index to text
            }
        }

        val nonBlankEmbeddings = mutableListOf<FloatArray>()
        for (chunk in nonBlankIndexed.chunked(BATCH_CHUNK_SIZE)) {
            val chunkTexts = chunk.map { it.second }
            val embeddings = embedChunk(chunkTexts, config, model, url, backoffMs)
            nonBlankEmbeddings.addAll(embeddings)
        }

        if (nonBlankEmbeddings.size != nonBlankIndexed.size) {
            throw IllegalStateException(
                "Embedding batch size mismatch: expected ${nonBlankIndexed.size}, got ${nonBlankEmbeddings.size}"
            )
        }

        val result = mutableListOf<FloatArray>()
        var nonBlankPos = 0
        for (i in texts.indices) {
            if (i in placeholders) {
                result.add(placeholders.getValue(i))
            } else {
                result.add(nonBlankEmbeddings[nonBlankPos++])
            }
        }
        result
    }

    private suspend fun embedChunk(
        texts: List<String>,
        config: ApiConfig,
        model: String,
        url: String,
        backoffMs: List<Long>,
    ): List<FloatArray> {
        val body = JSONObject().apply {
            put("input", JSONArray(texts))
            put("model", model)
        }.toString()

        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .applyAuth(config.apiKey)
            .build()

        repeat(MAX_RETRIES) { attempt ->
            try {
                DebugLog.i(TAG, "embedBatch chunk ${texts.size} → $url model=$model attempt=${attempt + 1}")

                http.newCall(request).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val msg = runCatching {
                            JSONObject(raw).optJSONObject("error")?.optString("message")
                                ?: JSONObject(raw).optString("message").takeIf { it.isNotBlank() }
                        }.getOrNull()
                        if (response.code in 500..599 || response.code == 429) {
                            throw EmbeddingRetryException(
                                msg?.takeIf { it.isNotBlank() }
                                    ?: "Embedding request failed: HTTP ${response.code}"
                            )
                        }
                        throw IllegalStateException(
                            msg?.takeIf { it.isNotBlank() } ?: "Embedding request failed: HTTP ${response.code}"
                        )
                    }

                    val root = JSONObject(raw)
                    val data = root.optJSONArray("data")
                        ?: throw IllegalStateException("Embedding response missing data array")
                    if (data.length() != texts.size) {
                        throw IllegalStateException(
                            "Embedding response size mismatch: expected ${texts.size}, got ${data.length()}"
                        )
                    }

                    val indexed = mutableListOf<Pair<Int, FloatArray>>()
                    for (i in 0 until data.length()) {
                        val obj = data.getJSONObject(i)
                        val index = obj.optInt("index", i)
                        val embeddingArray = obj.optJSONArray("embedding")
                            ?: throw IllegalStateException("Embedding response missing embedding array")
                        val vector = FloatArray(embeddingArray.length()) { j ->
                            embeddingArray.getDouble(j).toFloat()
                        }
                        cachedDimension = vector.size
                        indexed.add(index to vector)
                    }

                    return indexed.sortedBy { it.first }.map { it.second }
                }
            } catch (e: IOException) {
                if (attempt == MAX_RETRIES - 1) throw e
                delay(backoffMs[attempt])
            } catch (e: EmbeddingRetryException) {
                if (attempt == MAX_RETRIES - 1) throw IllegalStateException(e.message)
                delay(backoffMs[attempt])
            }
        }

        throw IllegalStateException("Embedding chunk failed after $MAX_RETRIES retries")
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

    override suspend fun embedBatch(texts: List<String>): List<FloatArray> = try {
        primary.embedBatch(texts)
    } catch (e: Exception) {
        DebugLog.w(TAG, "cloud embedding batch failed, falling back to local: ${e.message}")
        fallback.embedBatch(texts)
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
