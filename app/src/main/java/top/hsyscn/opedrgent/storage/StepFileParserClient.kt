package top.hsyscn.opedrgent.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Request
import org.json.JSONObject
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 阶跃星辰 文件解析 (File Parser) 客户端。
 *
 * 基于 StepFun File API:
 * - 上传: POST https://api.stepfun.com/v1/files/upload
 * - 获取内容: GET https://api.stepfun.com/v1/files/{file_id}/content
 * - 查询状态: GET https://api.stepfun.com/v1/files/{file_id}
 *
 * ## Intent 系统 (2025-05 更新)
 * 阶跃文件 API 使用 intent 区分用途（替代旧 purpose）:
 * - `file-extract`: 文档内容纯文本提取（PDF/DOCX/TXT → 纯文本）
 * - `retrieval-text`: 知识库 RAG 存储（替代已废弃的 retrieval，2025-05-15 下线）
 *
 * ## 支持格式 (来自官方文档)
 * 纯文本: .txt, .md | PDF: .pdf | Word: .doc, .docx | Excel: .xls, .xlsx
 * PPT: .ppt, .pptx | CSV: .csv | HTML/XML: .html, .htm, .xml
 *
 * ## 限制
 * - 单文件最大 64MB
 * - 账户最多存储 1000 个文件
 * - 仅支持纯文本内容提取（不支持图片/扫描件中的文字识别）
 */
object StepFileParserClient {

    private const val TAG = "StepFileParser"
    private const val BASE_URL = "https://api.stepfun.com/v1"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    // ---- Intent 枚举 ----

    /**
     * 文件处理意图 — 替代旧的 purpose 参数。
     */
    enum class FileIntent(val value: String) {
        /** 纯文本提取 — 解析文档并返回文本内容 */
        FILE_EXTRACT("file-extract"),
        /** 知识库 RAG 存储 — 自动 embedding 并存入向量存储（2025-05 后替代 retrieval） */
        RETRIEVAL_TEXT("retrieval-text"),
    }

    // ---- 数据模型 ----

    data class ParseResult(
        val success: Boolean,
        val fileId: String? = null,
        val extractedText: String? = null, // 通过 getFileContent() 获取的解析后文本
        val status: String = "", // processed / pending / error
        val message: String = "",
    )

    data class FileInfo(
        val id: String,
        val filename: String,
        val purpose: String,
        val sizeBytes: Long,
        val status: String,
        val createdAt: Long = 0,
    )

    // ---- 核心方法 ----

    /**
     * 上传文件并指定处理意图。
     *
     * @param apiKey 阶跃 API Key
     * @param filePath 本地文件路径
     * @param intent 处理意图（默认 file-extract 纯文本提取）
     * @return 解析结果，包含 fileId。需再调用 getFileContent() 获取实际文本。
     */
    suspend fun uploadFile(
        apiKey: String,
        filePath: String,
        intent: FileIntent = FileIntent.FILE_EXTRACT,
    ): ParseResult = withContext(Dispatchers.IO) {
        try {
            val file = File(filePath)
            if (!file.exists()) {
                return@withContext ParseResult(false, message = "文件不存在: $filePath")
            }
            if (file.length() > 64 * 1024 * 1024) {
                return@withContext ParseResult(false, message = "文件超过 64MB 限制 (${file.length()} bytes)")
            }

            val mediaType = detectMediaType(file.extension)

            // Multipart 上传
            val fileBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", file.name,
                    file.asRequestBody(mediaType.toMediaType())
                )
                .addFormDataPart("purpose", intent.value)
                .build()

            val request = Request.Builder()
                .url("$BASE_URL/files/upload")
                .post(fileBody)
                .header("Authorization", "Bearer $apiKey")
                .build()

            DebugLog.i(TAG, "上传文件: ${file.name} (${file.length()} bytes), intent=${intent.value}")

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                DebugLog.e(TAG, "文件上传失败 (${response.code}): $body")
                return@withContext ParseResult(false, message = "HTTP ${response.code}: ${extractError(body)}")
            }

            val json = JSONObject(body)
            val fileId = json.optString("id", "").ifBlank { null }
            val status = json.optString("status", "processed")

            ParseResult(
                success = true,
                fileId = fileId,
                status = status,
                message = "文件 ${file.name} 上传成功 -> fileId=$fileId, status=$status",
            )
        } catch (e: Exception) {
            DebugLog.e(TAG, "文件上传异常: ${e.message}", e)
            ParseResult(false, message = "异常: ${e.message}")
        }
    }

    /**
     * 上传 + 自动获取解析文本（一步完成）。
     *
     * 对于首次解析的文件，服务端可能需要异步处理。
     * 此方法会轮询状态直到 processed，然后获取文本内容。
     *
     * 流程: upload → waitForProcessed → getContent
     */
    suspend fun uploadAndExtract(
        apiKey: String,
        filePath: String,
        maxWaitSeconds: Int = 60,
    ): ParseResult = withContext(Dispatchers.IO) {
        // 第1步：上传
        val uploadResult = uploadFile(apiKey, filePath, FileIntent.FILE_EXTRACT)
        if (!uploadResult.success || uploadResult.fileId == null) {
            return@withContext uploadResult
        }

        val fileId = uploadResult.fileId!!

        // 第2步：等待处理完成（首次上传可能需要异步处理）
        if (uploadResult.status != "processed") {
            val ready = waitForProcessed(apiKey, fileId, maxWaitSeconds)
            if (!ready) {
                return@withContext ParseResult(
                    success = true,
                    fileId = fileId,
                    status = "pending",
                    message = "文件上传成功但解析未在 ${maxWaitSeconds}s 内完成，请稍后调用 getFileContent()",
                )
            }
        }

        // 第3步：获取解析后的文本内容
        val content = getFileContent(apiKey, fileId)

        ParseResult(
            success = true,
            fileId = fileId,
            extractedText = content,
            status = "processed",
            message = if (content != null) "解析完成 (${content.length} 字符)"
                       else "文件已处理但无法获取文本内容",
        )
    }

    /**
     * 获取已上传文件的解析后文本内容。
     *
     * 端点: GET /v1/files/{file_id}/content
     *
     * 返回文件解析后的纯文本内容。
     * 仅当文件状态为 processed 时可用。
     */
    suspend fun getFileContent(apiKey: String, fileId: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$BASE_URL/files/$fileId/content")
                    .get()
                    .header("Authorization", "Bearer $apiKey")
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string()

                if (!response.isSuccessful || body.isNullOrBlank()) {
                    DebugLog.w(TAG, "获取文件内容失败 (${response.code}), fileId=$fileId")
                    return@withContext null
                }

                // 尝试解析 JSON 响应（有些实现返回 {"content": "..."}）
                val text = try {
                    val json = JSONObject(body)
                    json.optString("content", "").ifBlank { body }
                } catch (_: Exception) {
                    body // 直接返回原始文本
                }

                DebugLog.i(TAG, "获取文件内容成功: fileId=$fileId, length=${text.length}")
                text
            } catch (e: Exception) {
                DebugLog.e(TAG, "获取文件内容异常: ${e.message}", e)
                null
            }
        }

    /**
     * 轮询等待文件处理完成。
     *
     * 首次上传的文件可能需要异步解析，此方法每 2 秒检查一次状态，
     * 直到变为 processed 或超时。
     */
    private suspend fun waitForProcessed(
        apiKey: String,
        fileId: String,
        maxWaitSeconds: Int,
    ): Boolean = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val timeoutMs = maxWaitSeconds * 1000L

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val info = getFileInfo(apiKey, fileId) ?: return@withContext false
            when (info.status) {
                "processed" -> return@withContext true
                "error" -> return@withContext false
                else -> {
                    // pending / processing — 继续等待
                    kotlinx.coroutines.delay(2000)
                }
            }
        }
        false
    }

    /**
     * 查询已上传文件的信息和状态。
     */
    suspend fun getFileInfo(apiKey: String, fileId: String): FileInfo? =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$BASE_URL/files/$fileId")
                    .get()
                    .header("Authorization", "Bearer $apiKey")
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""

                if (!response.isSuccessful) return@withContext null

                val json = JSONObject(body)
                FileInfo(
                    id = json.getString("id"),
                    filename = json.optString("filename", ""),
                    purpose = json.optString("purpose", ""),
                    sizeBytes = json.optLong("size", 0),
                    status = json.optString("status", "unknown"),
                    createdAt = json.optLong("created_at", 0),
                )
            } catch (e: Exception) {
                DebugLog.e(TAG, "查询文件信息异常: ${e.message}", e)
                null
            }
        }

    /**
     * 删除已上传的文件。
     */
    suspend fun deleteFile(apiKey: String, fileId: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$BASE_URL/files/$fileId")
                    .delete()
                    .header("Authorization", "Bearer $apiKey")
                    .build()

                client.newCall(request).execute().isSuccessful
            } catch (e: Exception) {
                DebugLog.e(TAG, "删除文件异常: ${e.message}", e)
                false
            }
        }

    /**
     * 列出已上传的文件。
     */
    suspend fun listFiles(apiKey: String, purpose: String? = null, limit: Int = 100): List<FileInfo> =
        withContext(Dispatchers.IO) {
            try {
                var urlStr = "$BASE_URL/files?limit=$limit"
                if (!purpose.isNullOrBlank()) urlStr += "&purpose=$purpose"

                val request = Request.Builder()
                    .url(urlStr)
                    .get()
                    .header("Authorization", "Bearer $apiKey")
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) return@withContext emptyList()

                val json = JSONObject(body)
                val arr = json.optJSONArray("data") ?: return@withContext emptyList()

                val list = mutableListOf<FileInfo>()
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    list.add(FileInfo(
                        id = item.getString("id"),
                        filename = item.optString("filename", ""),
                        purpose = item.optString("purpose", ""),
                        sizeBytes = item.optLong("size", 0),
                        status = item.optString("status", "unknown"),
                        createdAt = item.optLong("created_at", 0),
                    ))
                }
                list
            } catch (e: Exception) {
                DebugLog.e(TAG, "列出文件异常: ${e.message}", e)
                emptyList()
            }
        }

    // ---- 辅助方法 ----

    /**
     * 根据文件扩展名检测 MIME 类型。
     *
     * 完全覆盖阶跃支持的格式:
     * .txt, .md, .pdf, .doc, .docx, .xls, .xlsx, .ppt, .pptx, .csv, .html, .htm, .xml
     */
    fun detectMediaType(extension: String): String = when (extension.lowercase()) {
        "pdf" -> "application/pdf"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "doc" -> "application/msword"
        "txt", "text" -> "text/plain"
        "md" -> "text/markdown"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "xls" -> "application/vnd.ms-excel"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "ppt" -> "application/vnd.ms-powerpoint"
        "csv" -> "text/csv"
        "html", "htm" -> "text/html"
        "xml" -> "application/xml"
        "json" -> "application/json"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "mp4" -> "video/mp4"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        else -> "application/octet-stream"
    }

    private fun extractError(body: String): String {
        return try {
            val json = JSONObject(body)
            json.optJSONObject("error")?.optString("message")
                ?: json.optString("error", body.take(200))
        } catch (_: Exception) { body.take(200) }
    }

    /**
     * 验证 API Key 是否可用。
     */
    suspend fun validateApiKey(apiKey: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$BASE_URL/files?limit=1")
                    .get()
                    .header("Authorization", "Bearer $apiKey")
                    .build()

                client.newCall(request).execute().isSuccessful || client.newCall(request).execute().code == 404
            } catch (_: Exception) { false }
        }
}
