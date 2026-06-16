package top.hsyscn.opedrgent.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 阶跃星辰 知识库 (Vector Store) 客户端
 *
 * 基于 StepFun API 的 RAG 能力：
 * - Vector Store CRUD: POST /v1/vector_stores
 * - 文件上传: POST /v1/files/upload (purpose=retrieval)
 * - 检索: 在 chat completions 中使用 type=retrieval 工具自动触发
 *
 * ## API 端点
 * - Base URL: https://api.stepfun.com/v1
 * - 认证: Bearer token (apiKey)
 *
 * ## 使用场景
 * - 将本地知识库文档同步到阶跃云端向量存储
 * - 利用阶跃的 embedding + retrieval 实现高质量 RAG
 * - 与本地 SQLite 知识库形成双轨：本地全文检索 + 云端语义检索
 */
object StepVectorStoreClient {

    private const val TAG = "StepVectorStore"
    private const val BASE_URL = "https://api.stepfun.com/v1"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    // ---- 数据模型 ----

    data class VectorStoreInfo(
        val id: String,
        val name: String,
        val fileCount: Int = 0,
        val createdAt: Long = 0,
        val status: String = "completed",
    )

    data class FileInfo(
        val id: String,
        val filename: String,
        val purpose: String,
        val sizeBytes: Long = 0,
        val status: String = "processed",
    )

    data class RagResult(
        val success: Boolean,
        val vectorStoreId: String? = null,
        val fileId: String? = null,
        val message: String = "",
    )

    // ---- Vector Store 操作 ----

    /**
     * 创建云端向量存储。
     *
     * @param apiKey 阶跃 API Key
     * @param name 向量存储名称（建议与本地知识库名称对应）
     * @return VectorStoreInfo 或 null
     */
    suspend fun createStore(apiKey: String, name: String): VectorStoreInfo? =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("name", name)
                }.toString()

                val request = Request.Builder()
                    .url("$BASE_URL/vector_stores")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    DebugLog.e(TAG, "创建向量存储失败 (${response.code}): $responseBody")
                    return@withContext null
                }

                val json = JSONObject(responseBody)
                val vs = json.optJSONObject("vector_store") ?: json
                VectorStoreInfo(
                    id = vs.getString("id"),
                    name = vs.optString("name", name),
                    fileCount = vs.optInt("file_counts", 0),
                    createdAt = vs.optLong("created_at", 0L),
                    status = vs.optString("status", "completed"),
                )
            } catch (e: Exception) {
                DebugLog.e(TAG, "创建向量存储异常: ${e.message}", e)
                null
            }
        }

    /**
     * 查询向量存储列表。
     */
    suspend fun listStores(apiKey: String): List<VectorStoreInfo> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$BASE_URL/vector_stores?limit=100")
                    .get()
                    .header("Authorization", "Bearer $apiKey")
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    DebugLog.e(TAG, "查询向量存储列表失败: $body")
                    return@withContext emptyList<VectorStoreInfo>()
                }

                val json = JSONObject(body)
                val arr = json.optJSONArray("data") ?: return@withContext emptyList<VectorStoreInfo>()
                val list = mutableListOf<VectorStoreInfo>()
                for (i in 0 until arr.length()) {
                    val vs = arr.getJSONObject(i)
                    list.add(
                        VectorStoreInfo(
                            id = vs.getString("id"),
                            name = vs.optString("name", ""),
                            fileCount = vs.optJSONObject("file_counts")?.optInt("total", 0) ?: 0,
                            createdAt = vs.optLong("created_at", 0L),
                            status = vs.optString("status", "completed"),
                        )
                    )
                }
                list
            } catch (e: Exception) {
                DebugLog.e(TAG, "查询向量存储列表异常: ${e.message}", e)
                emptyList()
            }
        }

    /**
     * 删除向量存储。
     */
    suspend fun deleteStore(apiKey: String, storeId: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$BASE_URL/vector_stores/$storeId")
                    .delete()
                    .header("Authorization", "Bearer $apiKey")
                    .build()

                val response = client.newCall(request).execute()
                response.isSuccessful || response.code == 204
            } catch (e: Exception) {
                DebugLog.e(TAG, "删除向量存储异常: ${e.message}", e)
                false
            }
        }

    // ---- 文件上传 (purpose=retrieval) ----

    /**
     * 上传文件到阶跃云端，用于 RAG 检索。
     *
     * 支持格式: PDF, TXT, MD, DOCX 等（与 StepFun 文件解析 API 一致）
     *
     * @param apiKey 阶跃 API Key
     * @param filePath 本地文件路径
     * @param storeId 目标向量存储 ID（可选，上传后需手动关联）
     * @return 上传结果
     */
    suspend fun uploadFileForRetrieval(
        apiKey: String,
        filePath: String,
        storeId: String? = null,
    ): RagResult = withContext(Dispatchers.IO) {
        try {
            val file = File(filePath)
            if (!file.exists()) {
                return@withContext RagResult(false, message = "文件不存在: $filePath")
            }

            val mediaType = when (file.extension.lowercase()) {
                "pdf" -> "application/pdf"
                "txt", "text" -> "text/plain"
                "md" -> "text/markdown"
                "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                "doc" -> "application/msword"
                "json" -> "application/json"
                "csv" -> "text/csv"
                "html" -> "text/html"
                else -> "application/octet-stream"
            }

            // Multipart 上传
            val fileBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", file.name,
                    file.asRequestBody(mediaType.toMediaType())
                )
                .addFormDataPart("purpose", "retrieval-text")
                .build()

            val request = Request.Builder()
                .url("$BASE_URL/files/upload")
                .post(fileBody)
                .header("Authorization", "Bearer $apiKey")

            val response = client.newCall(request.build()).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                DebugLog.e(TAG, "文件上传失败 (${response.code}): $responseBody")
                return@withContext RagResult(false, message = "上传失败: HTTP ${response.code}")
            }

            val json = JSONObject(responseBody)
            val fileId = json.optString("id", "") ?: ""

            // 如果指定了 storeId，将文件关联到向量存储
            if (!storeId.isNullOrBlank() && fileId.isNotBlank()) {
                associateFile(apiKey, storeId, fileId)
            }

            RagResult(
                success = true,
                fileId = fileId,
                vectorStoreId = storeId,
                message = "文件上传成功: ${file.name} -> fileId=$fileId",
            )
        } catch (e: Exception) {
            DebugLog.e(TAG, "文件上传异常: ${e.message}", e)
            RagResult(false, message = "上传异常: ${e.message}")
        }
    }

    /**
     * 将已上传文件关联到向量存储。
     */
    private suspend fun associateFile(
        apiKey: String,
        storeId: String,
        fileId: String,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("file_id", fileId)
            }.toString()

            val request = Request.Builder()
                .url("$BASE_URL/vector_stores/$storeId/files")
                .post(body.toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Bearer $apiKey")
                .build()

            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            DebugLog.e(TAG, "关联文件到向量存储异常: ${e.message}", e)
            false
        }
    }

    // ---- 检索工具定义 ----

    /**
     * 生成 retrieval 类型工具定义，用于在 chat completions 中注册为自动检索工具。
     *
     * LLM 调用此工具时，阶跃服务端会自动从关联的向量存储中检索相关内容并返回。
     *
     * @param storeIds 要检索的向量存储 ID 列表
     * @return ToolDefinition 格式的 JSON
     */
    fun buildRetrievalToolDefinition(storeIds: List<String>): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "step_knowledge_retrieval")
                put("description", "从阶跃云端知识库中检索相关文档内容。当用户问题涉及已导入的知识库文档时使用此工具。支持语义搜索，能理解问题的意图并返回最相关的文档片段。")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("query", JSONObject().apply {
                            put("type", "string")
                            put("description", "用户的搜索查询或问题")
                        })
                    })
                    put("required", JSONArray().put("query"))
                })
                // StepFun 特有：指定向量存储
                put("tool_type", "retrieval")
                if (storeIds.isNotEmpty()) {
                    put("vector_store_ids", org.json.JSONArray(storeIds))
                }
            })
        }
    }

    // ---- 验证 ----

    /**
     * 验证 API Key 是否有效（通过查询向量存储列表）。
     */
    suspend fun validateApiKey(apiKey: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$BASE_URL/vector_stores?limit=1")
                    .get()
                    .header("Authorization", "Bearer $apiKey")
                    .build()

                val response = client.newCall(request).execute()
                response.isSuccessful || response.code == 404 // 404 也说明认证通过只是没有数据
            } catch (_: Exception) {
                false
            }
        }
}
