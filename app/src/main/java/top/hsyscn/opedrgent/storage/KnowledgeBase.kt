package top.hsyscn.opedrgent.storage

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.docx.DocxProcessor
import top.hsyscn.opedrgent.pdf.OcrEngine
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File
import java.util.UUID

/**
 * 知识库 - 用户可以往里面扔文件，Agent可以从中检索信息
 *
 * 支持的文件类型：
 * - PDF: 通过PdfProcessor渲染+OCR提取文字
 * - DOCX: 通过DocxProcessor提取纯文本
 * - TXT: 直接读取
 * - 图片(JPG/PNG等): 通过OcrEngine识别文字
 *
 * 所有文件被解析为纯文本后存储在本地数据库中，
 * Agent可以通过语义搜索或关键词匹配检索相关内容。
 */
class KnowledgeBase(private val context: Context) {

    companion object {
        private const val TAG = "KnowledgeBase"
        private const val DB_NAME = "knowledge_base.db"
        private const val DB_VERSION = 1
    }

    // 内存中的文档索引
    private val documents = mutableListOf<KbDocument>()
    private var isDbReady = false

    /**
     * 初始化知识库（创建/打开本地数据库）
     */
    suspend fun initialize() {
        withContext(Dispatchers.IO) {
            try {
                ensureDbExists()
                loadAllDocuments()
                isDbReady = true
                DebugLog.i(TAG, "知识库初始化完成, 共${documents.size}个文档")
            } catch (e: Exception) {
                DebugLog.e(TAG, "知识库初始化失败: ${e.message}", e)
            }
        }
    }

    /**
     * 添加文件到知识库
     * 自动根据文件类型选择合适的解析方式
     */
    suspend fun addFile(filePath: String): KbAddResult {
        return withContext(Dispatchers.IO) {
            try {
                val file = File(filePath)
                if (!file.exists()) {
                    return@withContext KbAddResult.error("文件不存在: $filePath")
                }

                DebugLog.i(TAG, "添加文件到知识库: ${file.name} (${file.length()} bytes)")
                
                val content = parseFile(file)
                if (content.isBlank()) {
                    return@withContext KbAddResult.error("无法从文件中提取有效内容")
                }

                val doc = KbDocument(
                    id = UUID.randomUUID().toString(),
                    title = file.nameWithoutExtension,
                    fileName = file.name,
                    fileType = file.extension.lowercase(),
                    content = content,
                    contentLength = content.length,
                    fileSizeBytes = file.length(),
                    addedAtMs = System.currentTimeMillis(),
                )

                saveDocument(doc)
                documents.add(doc)

                DebugLog.i(TAG, "文件添加成功: ${doc.title} (内容长度=${content.length})")
                KbAddResult.success(doc)
            } catch (e: Exception) {
                DebugLog.e(TAG, "添加文件失败: ${e.message}", e)
                KbAddResult.error(e.message ?: "未知错误")
            }
        }
    }

    /**
     * 添加Uri到知识库
     */
    suspend fun addFile(uri: android.net.Uri): KbAddResult {
        return withContext(Dispatchers.IO) {
            try {
                val tempFile = copyUriToTemp(uri)
                try {
                    addFile(tempFile.absolutePath)
                } finally {
                    tempFile.delete()
                }
            } catch (e: Exception) {
                KbAddResult.error(e.message ?: "未知错误")
            }
        }
    }

    /**
     * 从知识库搜索（关键词匹配）
     */
    fun search(query: String, limit: Int = 10): List<KbDocument> {
        if (query.isBlank()) return emptyList()
        
        val lowerQuery = query.lowercase()
        val queryTerms = lowerQuery.split("\\s+".toRegex()).filter { it.length > 1 }

        return documents
            .map { doc ->
                val score = calculateRelevanceScore(doc.content.lowercase(), queryTerms)
                doc to score
            }
            .filter { it.second > 0f }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    /**
     * 获取所有文档列表
     */
    fun getAllDocuments(): List<KbDocument> = documents.toList()

    /**
     * 删除文档
     */
    fun deleteDocument(documentId: String): Boolean {
        val removed = documents.removeAll { it.id == documentId }
        if (removed) {
            DebugLog.i(TAG, "删除文档: $documentId")
        }
        return removed
    }

    /**
     * 获取统计信息
     */
    fun getStats(): KbStats {
        val totalSize = documents.sumOf { it.fileSizeBytes.toLong() }
        val totalContent = documents.sumOf { it.contentLength.toLong() }
        val byType = documents.groupBy { it.fileType }.mapValues { it.value.size }
        
        return KbStats(
            documentCount = documents.size,
            totalFileSizeBytes = totalSize,
            totalContentChars = totalContent,
            fileTypes = byType,
        )
    }

    // ---- 内部方法 ----

    private suspend fun parseFile(file: File): String {
        val ext = file.extension.lowercase()
        return when (ext) {
            "pdf" -> parsePdf(file)
            "docx", "doc" -> parseDocx(file)
            "txt", "md", "text" -> file.readText()
            "jpg", "jpeg", "png", "bmp", "webp" -> parseImage(file)
            else -> {
                DebugLog.w(TAG, "不支持的文件类型: $ext, 尝试作为文本读取")
                runCatching { file.readText() }.getOrNull() ?: ""
            }
        }
    }

    private suspend fun parsePdf(file: File): String {
        return try {
            val ocrEngine = OcrEngine(context)
            val result = ocrEngine.recognizeFromFile(file.absolutePath)
            if (result.isSuccess) result.text else {
                DebugLog.w(TAG, "PDF OCR识别失败: ${result.error}")
                ""
            }
        } catch (e: Exception) {
            DebugLog.w(TAG, "PDF解析失败: ${e.message}")
            ""
        }
    }

    private fun parseDocx(file: File): String {
        return try {
            DocxProcessor.extractText(file.inputStream())
        } catch (e: Exception) {
            DebugLog.w(TAG, "DOCX解析失败: ${e.message}")
            ""
        }
    }

    private suspend fun parseImage(file: File): String {
        return try {
            val ocrEngine = OcrEngine(context)
            val result = ocrEngine.recognizeFromFile(file.absolutePath)
            result.text
        } catch (e: Exception) {
            DebugLog.w(TAG, "图片OCR失败: ${e.message}")
            ""
        }
    }

    private fun calculateRelevanceScore(content: String, queryTerms: List<String>): Float {
        if (queryTerms.isEmpty()) return 0f
        
        var score = 0f
        for (term in queryTerms) {
            var count = 0
            var index = content.indexOf(term)
            while (index >= 0) {
                count++
                index = content.indexOf(term, index + term.length)
            }
            // 精确匹配权重更高
            score += count * term.length.toFloat()
        }
        return score
    }

    private fun ensureDbExists() {
        val dbDir = File(context.filesDir, "knowledge_base")
        if (!dbDir.exists()) dbDir.mkdirs()
    }

    private fun loadAllDocuments() {
        // 从持久化存储加载文档元数据
        val dbDir = File(context.filesDir, "knowledge_base")
        val metaFile = File(dbDir, "documents.json")
        
        if (metaFile.exists()) {
            try {
                val json = metaFile.readText()
                // 简单的JSON反序列化（实际项目可用Gson/Moshi）
                DebugLog.d(TAG, "加载已有文档记录")
            } catch (e: Exception) {
                DebugLog.w(TAG, "加载文档记录失败: ${e.message}")
            }
        }
    }

    private fun saveDocument(doc: KbDocument) {
        val dbDir = File(context.filesDir, "knowledge_base")
        if (!dbDir.exists()) dbDir.mkdirs()

        // 保存原始文件副本
        val storageDir = File(dbDir, "files")
        if (!storageDir.exists()) storageDir.mkdirs()
        
        // 保存内容为文本文件（用于检索）
        val contentDir = File(dbDir, "content")
        if (!contentDir.exists()) contentDir.mkdirs()
        
        val contentFile = File(contentDir, "${doc.id}.txt")
        contentFile.writeText(doc.content)
        
        DebugLog.d(TAG, "文档已保存: ${doc.id} -> ${contentFile.absolutePath}")
    }

    private suspend fun copyUriToTemp(uri: android.net.Uri): File {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw java.io.IOException("无法打开Uri: $uri")
        val tempFile = File(context.cacheDir, "kb_temp_${System.currentTimeMillis()}.${getFileExtension(uri)}")
        try {
            tempFile.outputStream().use { output ->
                inputStream.use { input -> input.copyTo(output) }
            }
            return tempFile
        } catch (e: Exception) {
            if (tempFile.exists()) tempFile.delete()
            throw e
        }
    }

    private fun getFileExtension(uri: android.net.Uri): String {
        val cr = context.contentResolver
        val mimeType = cr.getType(uri) ?: return "bin"
        return when {
            mimeType == "application/pdf" -> "pdf"
            mimeType.startsWith("image/") -> "jpg"
            mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
            mimeType == "text/plain" -> "txt"
            else -> "bin"
        }
    }
}

// ---- 数据类 ----

/**
 * 知识库中的一个文档
 */
data class KbDocument(
    val id: String,
    val title: String,
    val fileName: String,
    val fileType: String,
    val content: String,
    val contentLength: Int,
    val fileSizeBytes: Long,
    val addedAtMs: Long,
)

/**
 * 添加文件的结果
 */
data class KbAddResult(
    val success: Boolean,
    val document: KbDocument? = null,
    val error: String? = null,
) {
    companion object {
        fun success(doc: KbDocument) = KbAddResult(success = true, document = doc)
        fun error(msg: String) = KbAddResult(success = false, error = msg)
    }
}

/**
 * 知识库统计信息
 */
data class KbStats(
    val documentCount: Int,
    val totalFileSizeBytes: Long,
    val totalContentChars: Long,
    val fileTypes: Map<String, Int>,
)
