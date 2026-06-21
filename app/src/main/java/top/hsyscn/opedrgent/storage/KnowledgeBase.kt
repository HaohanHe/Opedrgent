package top.hsyscn.opedrgent.storage

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.docx.DocxProcessor
import top.hsyscn.opedrgent.pdf.OcrEngine
import top.hsyscn.opedrgent.storage.StepFileParserClient
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// ============================================================
// SQLite 数据库（原生实现，与 NoteDatabase 模式一致）
// ============================================================

private const val DB_NAME = "knowledge_base.db"
private const val DB_VERSION = 2

private const val TABLE_KB = "knowledge_bases"
private const val TABLE_DOCS = "kb_documents"

// knowledge_bases 列
private const val KB_ID = "id"
private const val KB_NAME = "name"
private const val KB_DESC = "description"
private const val KB_VISIBILITY = "visibility"
private const val KB_COVER_COLOR = "cover_color"
private const val KB_CREATED_AT = "created_at"
private const val KB_UPDATED_AT = "updated_at"

// kb_documents 列
private const val DOC_ID = "id"
private const val DOC_KB_ID = "kb_id"
private const val DOC_TITLE = "title"
private const val DOC_FILE_NAME = "file_name"
private const val DOC_FILE_TYPE = "file_type"
private const val DOC_FILE_SIZE = "file_size"
private const val DOC_CONTENT_LENGTH = "content_length"
private const val DOC_CONTENT = "content"
private const val DOC_TAGS = "tags_json"
private const val DOC_SOURCE_URI = "source_uri"
private const val DOC_ADDED_AT = "added_at"
// v2 新增列 (增量同步)
private const val DOC_SOURCE_LAST_MODIFIED = "source_last_modified"
private const val DOC_SOURCE_SIZE = "source_size"
private const val DOC_CONTENT_HASH = "content_hash"
private const val DOC_CLOUD_FILE_ID = "cloud_file_id"
private const val DOC_SYNC_STATUS = "sync_status"
private const val DOC_LAST_SYNCED_AT = "last_synced_at"

class KbDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_KB (
                $KB_ID TEXT PRIMARY KEY,
                $KB_NAME TEXT NOT NULL,
                $KB_DESC TEXT DEFAULT '',
                $KB_VISIBILITY TEXT NOT NULL DEFAULT 'PRIVATE',
                $KB_COVER_COLOR TEXT DEFAULT '#4A90D9',
                $KB_CREATED_AT INTEGER NOT NULL,
                $KB_UPDATED_AT INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_DOCS (
                $DOC_ID TEXT PRIMARY KEY,
                $DOC_KB_ID TEXT NOT NULL,
                $DOC_TITLE TEXT NOT NULL,
                $DOC_FILE_NAME TEXT NOT NULL,
                $DOC_FILE_TYPE TEXT NOT NULL,
                $DOC_FILE_SIZE INTEGER NOT NULL DEFAULT 0,
                $DOC_CONTENT_LENGTH INTEGER NOT NULL DEFAULT 0,
                $DOC_CONTENT TEXT DEFAULT '',
                $DOC_TAGS TEXT DEFAULT '[]',
                $DOC_SOURCE_URI TEXT,
                $DOC_ADDED_AT INTEGER NOT NULL,
                $DOC_SOURCE_LAST_MODIFIED INTEGER NOT NULL DEFAULT 0,
                $DOC_SOURCE_SIZE INTEGER NOT NULL DEFAULT 0,
                $DOC_CONTENT_HASH TEXT DEFAULT '',
                $DOC_CLOUD_FILE_ID TEXT,
                $DOC_SYNC_STATUS TEXT NOT NULL DEFAULT 'LOCAL_ONLY',
                $DOC_LAST_SYNCED_AT INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY($DOC_KB_ID) REFERENCES $TABLE_KB($KB_ID) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_docs_kb_id ON $TABLE_DOCS($DOC_KB_ID)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_docs_type ON $TABLE_DOCS($DOC_FILE_TYPE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_docs_sync_status ON $TABLE_DOCS($DOC_SYNC_STATUS)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // v1 -> v2: 增量同步所需列
            db.execSQL("ALTER TABLE $TABLE_DOCS ADD COLUMN $DOC_SOURCE_LAST_MODIFIED INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $TABLE_DOCS ADD COLUMN $DOC_SOURCE_SIZE INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $TABLE_DOCS ADD COLUMN $DOC_CONTENT_HASH TEXT DEFAULT ''")
            db.execSQL("ALTER TABLE $TABLE_DOCS ADD COLUMN $DOC_CLOUD_FILE_ID TEXT")
            db.execSQL("ALTER TABLE $TABLE_DOCS ADD COLUMN $DOC_SYNC_STATUS TEXT NOT NULL DEFAULT 'LOCAL_ONLY'")
            db.execSQL("ALTER TABLE $TABLE_DOCS ADD COLUMN $DOC_LAST_SYNCED_AT INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_docs_sync_status ON $TABLE_DOCS($DOC_SYNC_STATUS)")
            DebugLog.i("KbDatabase", "知识库数据库升级 v1 -> v2 (增量同步字段)")
        }
    }
}

// ============================================================
// 知识库管理器（多知识库 + 文档 CRUD + 搜索）
// ============================================================

/**
 * 知识库 - 多知识库管理系统
 *
 * 支持功能：
 * - 创建/编辑/删除多个知识库
 * - 向指定知识库添加文件（PDF/DOCX/TXT/图片）
 * - 全文搜索文档
 * - 统计信息
 */
class KnowledgeBase(private val context: Context) {

    companion object {
        private const val TAG = "KnowledgeBase"
    }

    private val db: SQLiteDatabase by lazy { KbDatabase(context).writableDatabase }

    // ---- 初始化 ----

    suspend fun initialize() {
        withContext(Dispatchers.IO) {
            try {
                ensureDefaultKb()
                DebugLog.i(TAG, "知识库系统初始化完成")
            } catch (e: Exception) {
                DebugLog.e(TAG, "知识库初始化失败: ${e.message}", e)
            }
        }
    }

    // ---- 知识库 CRUD ----

    suspend fun createKnowledgeBase(
        name: String,
        description: String = "",
        visibility: Visibility = Visibility.PRIVATE,
        coverColor: String = "#4A90D9",
    ): KnowledgeBaseInfo = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        db.insertOrThrow(TABLE_KB, null, android.content.ContentValues().apply {
            put(KB_ID, id)
            put(KB_NAME, name)
            put(KB_DESC, description)
            put(KB_VISIBILITY, visibility.name)
            put(KB_COVER_COLOR, coverColor)
            put(KB_CREATED_AT, now)
            put(KB_UPDATED_AT, now)
        })
        getKnowledgeBaseById(id) ?: throw IllegalStateException("知识库创建后无法回查: id=$id")
    }

    fun getAllKnowledgeBases(): List<KnowledgeBaseInfo> {
        val list = mutableListOf<KnowledgeBaseInfo>()
        val c = db.query(
            TABLE_KB, null, null, null, null, null, "$KB_UPDATED_AT DESC"
        )
        c.use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursorToKbInfo(cursor))
            }
        }
        return list
    }

    fun getKnowledgeBaseById(id: String): KnowledgeBaseInfo? {
        db.query(TABLE_KB, null, "$KB_ID=?", arrayOf(id), null, null, null).use { cursor ->
            return if (cursor.moveToFirst()) cursorToKbInfo(cursor) else null
        }
    }

    suspend fun updateKnowledgeBase(
        id: String,
        name: String? = null,
        description: String? = null,
        visibility: Visibility? = null,
        coverColor: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val values = android.content.ContentValues()
        name?.let { values.put(KB_NAME, it) }
        description?.let { values.put(KB_DESC, it) }
        visibility?.let { values.put(KB_VISIBILITY, it.name) }
        coverColor?.let { values.put(KB_COVER_COLOR, it) }
        values.put(KB_UPDATED_AT, System.currentTimeMillis())
        db.update(TABLE_KB, values, "$KB_ID=?", arrayOf(id)) > 0
    }

    suspend fun deleteKnowledgeBase(id: String): Boolean = withContext(Dispatchers.IO) {
        db.delete(TABLE_DOCS, "$DOC_KB_ID=?", arrayOf(id))
        db.delete(TABLE_KB, "$KB_ID=?", arrayOf(id)) > 0
    }

    // ---- 文档操作 ----

    suspend fun addFile(uri: android.net.Uri, kbId: String = "default", cloudApiKey: String? = null): KbAddResult {
        return withContext(Dispatchers.IO) {
            try {
                val tempFile = copyUriToTemp(uri)
                try {
                    addFileInternal(tempFile, kbId, uri.toString(), cloudApiKey)
                } finally {
                    tempFile.delete()
                }
            } catch (e: Exception) {
                DebugLog.e(TAG, "添加文件失败: ${e.message}", e)
                KbAddResult.error(e.message ?: "未知错误")
            }
        }
    }

    suspend fun addFile(filePath: String, kbId: String = "default", cloudApiKey: String? = null): KbAddResult {
        return withContext(Dispatchers.IO) {
            try {
                val file = File(filePath)
                if (!file.exists()) return@withContext KbAddResult.error("文件不存在: $filePath")
                addFileInternal(file, kbId, null, cloudApiKey)
            } catch (e: Exception) {
                DebugLog.e(TAG, "添加文件失败: ${e.message}", e)
                KbAddResult.error(e.message ?: "未知错误")
            }
        }
    }

    suspend fun addTextDocument(
        title: String,
        content: String,
        kbId: String = "default",
    ): KbAddResult {
        return withContext(Dispatchers.IO) {
            try {
                ensureDefaultKb()
                val doc = KbDocument(
                    title = title.ifBlank { "未命名文档" },
                    fileName = "$title.txt",
                    fileType = "txt",
                    fileSizeBytes = content.toByteArray().size.toLong(),
                    contentLength = content.length,
                    addedAtMs = System.currentTimeMillis(),
                    content = content,
                    knowledgeBaseId = kbId,
                    sourceUri = null,
                )
                saveDocument(doc)
                DebugLog.i(TAG, "文本文档添加成功: ${doc.title}")
                KbAddResult.success(doc)
            } catch (e: Exception) {
                DebugLog.e(TAG, "添加文本文档失败: ${e.message}", e)
                KbAddResult.error(e.message ?: "未知错误")
            }
        }
    }

    fun getDocumentsByKnowledgeBase(kbId: String): List<KbDocument> {
        val list = mutableListOf<KbDocument>()
        db.query(
            TABLE_DOCS, null, "$DOC_KB_ID=?", arrayOf(kbId), null, null, "$DOC_ADDED_AT DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursorToDoc(cursor))
            }
        }
        return list
    }

    fun getAllDocuments(): List<KbDocument> {
        val list = mutableListOf<KbDocument>()
        db.query(TABLE_DOCS, null, null, null, null, null, "$DOC_ADDED_AT DESC").use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursorToDoc(cursor))
            }
        }
        return list
    }

    /**
     * 获取所有文档的轻量搜索副本（供 HybridSearchEngine BM25 索引使用）。
     * 仅返回 id + title + content，不包含完整元数据。
     */
    fun getAllDocumentsForSearch(): List<SearchableDoc> {
        val list = mutableListOf<SearchableDoc>()
        db.query(TABLE_DOCS, arrayOf(DOC_ID, DOC_TITLE, DOC_CONTENT),
                null, null, null, null, null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(SearchableDoc(
                    id = cursor.getString(0) ?: "",
                    title = cursor.getString(1) ?: "",
                    content = cursor.getString(2) ?: "",
                ))
            }
        }
        return list
    }

    /** 搜索用轻量文档（BM25 索引不需要完整 KbDocument） */
    data class SearchableDoc(
        val id: String,
        val title: String,
        val content: String,
    )



    fun deleteDocument(documentId: String): Boolean {
        return db.delete(TABLE_DOCS, "$DOC_ID=?", arrayOf(documentId)) > 0
    }

    // ---- 增量同步支持 ----

    /**
     * 检查文档源文件是否已变更 (基于 lastModified + size 快速检测)。
     *
     * @return true 表示源文件已修改, 需要重新解析
     */
    fun isSourceFileChanged(doc: KbDocument): Boolean {
        val sourcePath = doc.sourceUri ?: return false
        // 仅支持本地文件路径 (content:// URI 无法直接检测)
        if (sourcePath.startsWith("content://")) return false
        val file = File(sourcePath)
        if (!file.exists()) return false
        return file.lastModified() != doc.sourceLastModified || file.length() != doc.sourceSize
    }

    /**
     * 重新解析文档的源文件并更新内容。
     *
     * 增量同步核心方法: 当源文件变更时, 重新解析并更新数据库。
     * 若内容哈希变化, 自动标记为 PENDING 等待云端同步。
     *
     * @param docId 文档 ID
     * @param cloudApiKey 云端解析回退用的 API Key (可选)
     * @return ReparseResult
     */
    suspend fun reparseDocument(docId: String, cloudApiKey: String? = null): ReparseResult {
        return withContext(Dispatchers.IO) {
            val doc = getDocumentById(docId)
                ?: return@withContext ReparseResult(false, "文档不存在: $docId")

            val sourcePath = doc.sourceUri
            if (sourcePath.isNullOrBlank() || sourcePath.startsWith("content://")) {
                return@withContext ReparseResult(false, "文档无本地源文件路径, 无法重新解析")
            }

            val file = File(sourcePath)
            if (!file.exists()) {
                return@withContext ReparseResult(false, "源文件不存在: $sourcePath")
            }

            var newContent = parseFile(file)
            // 本地解析失败时尝试云端回退
            if (newContent.isBlank() && !cloudApiKey.isNullOrBlank()) {
                DebugLog.i(TAG, "重新解析: 本地失败, 尝试云端: ${file.name}")
                newContent = parseFileWithCloud(file, cloudApiKey)
            }

            if (newContent.isBlank()) {
                return@withContext ReparseResult(false, "重新解析返回空内容: ${file.name}")
            }

            val newHash = computeContentHash(newContent)
            val contentChanged = newHash != doc.contentHash

            updateDocumentContent(
                docId = docId,
                newContent = newContent,
                newContentHash = newHash,
                newSourceLastModified = file.lastModified(),
                newSourceSize = file.length(),
            )

            DebugLog.i(TAG, "文档重新解析完成: ${doc.title} (内容${if (contentChanged) "已变更" else "未变更"})")
            ReparseResult(
                success = true,
                contentChanged = contentChanged,
                newContentLength = newContent.length,
            )
        }
    }

    /** 重新解析结果 */
    data class ReparseResult(
        val success: Boolean,
        val message: String = "",
        val contentChanged: Boolean = false,
        val newContentLength: Int = 0,
    )

    /**
     * 扫描所有有本地源文件的文档, 检测哪些需要重新解析。
     *
     * @return 需要重新解析的文档列表
     */
    fun scanForChangedDocuments(): List<KbDocument> {
        val allDocs = getAllDocuments()
        return allDocs.filter { isSourceFileChanged(it) }
    }

    // ---- 统计 ----

    fun getGlobalStats(): Pair<Int, Long> {
        db.rawQuery("SELECT COUNT(*), COALESCE(SUM($DOC_FILE_SIZE), 0) FROM $TABLE_DOCS", null).use { c ->
            if (c.moveToFirst()) {
                return Pair(c.getInt(0), c.getLong(1))
            }
        }
        return Pair(0, 0L)
    }

    fun getStats(): KbStats {
        val docs = getAllDocuments()
        return KbStats(
            documentCount = docs.size,
            totalFileSizeBytes = docs.sumOf { it.fileSizeBytes },
            totalContentChars = docs.sumOf { it.contentLength.toLong() },
            fileTypes = docs.groupBy { it.fileType }.mapValues { it.value.size },
        )
    }

    // ---- 内部方法 ----

    private suspend fun addFileInternal(file: File, kbId: String, sourceUri: String?, cloudApiKey: String? = null): KbAddResult {
        var content = parseFile(file)

        // 本地解析失败时，尝试阶跃云端解析作为回退
        if (content.isBlank() && !cloudApiKey.isNullOrBlank()) {
            DebugLog.i(TAG, "本地解析失败，尝试阶跃云端解析: ${file.name}")
            content = parseFileWithCloud(file, cloudApiKey)
        }

        if (content.isBlank()) return KbAddResult.error("无法从文件中提取有效内容")

        val contentHash = computeContentHash(content)
        val doc = KbDocument(
            title = file.nameWithoutExtension,
            fileName = file.name,
            fileType = file.extension.lowercase(),
            fileSizeBytes = file.length(),
            contentLength = content.length,
            addedAtMs = System.currentTimeMillis(),
            content = content,
            knowledgeBaseId = kbId,
            sourceUri = sourceUri ?: file.absolutePath,
            sourceLastModified = file.lastModified(),
            sourceSize = file.length(),
            contentHash = contentHash,
            syncStatus = SyncStatus.LOCAL_ONLY,
        )

        saveDocument(doc)

        DebugLog.i(TAG, "文件添加成功: ${doc.title} (${doc.fileType}, ${content.length}字, hash=$contentHash)")
        return KbAddResult.success(doc)
    }

    /**
     * 计算内容的 SHA-256 哈希 (取前 16 字符作为指纹, 足够区分内容变更)。
     */
    private fun computeContentHash(content: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray())
            digest.joinToString("") { "%02x".format(it) }.take(16)
        } catch (e: Exception) {
            DebugLog.w(TAG, "计算内容哈希失败: ${e.message}")
            ""
        }
    }

    private fun saveDocument(doc: KbDocument) {
        db.insertOrThrow(TABLE_DOCS, null, android.content.ContentValues().apply {
            put(DOC_ID, doc.id)
            put(DOC_KB_ID, doc.knowledgeBaseId)
            put(DOC_TITLE, doc.title)
            put(DOC_FILE_NAME, doc.fileName)
            put(DOC_FILE_TYPE, doc.fileType)
            put(DOC_FILE_SIZE, doc.fileSizeBytes)
            put(DOC_CONTENT_LENGTH, doc.contentLength)
            put(DOC_CONTENT, doc.content)
            put(DOC_TAGS, Json.encodeToString(doc.tags))
            put(DOC_SOURCE_URI, doc.sourceUri)
            put(DOC_ADDED_AT, doc.addedAtMs)
            put(DOC_SOURCE_LAST_MODIFIED, doc.sourceLastModified)
            put(DOC_SOURCE_SIZE, doc.sourceSize)
            put(DOC_CONTENT_HASH, doc.contentHash)
            put(DOC_CLOUD_FILE_ID, doc.cloudFileId)
            put(DOC_SYNC_STATUS, doc.syncStatus.name)
            put(DOC_LAST_SYNCED_AT, doc.lastSyncedAt)
        })
    }

    /**
     * 更新文档内容 (增量同步重新解析后调用)。
     * 仅更新内容相关字段, 保留 id/kbId/sourceUri 等。
     */
    fun updateDocumentContent(
        docId: String,
        newContent: String,
        newContentHash: String,
        newSourceLastModified: Long,
        newSourceSize: Long,
    ): Boolean {
        val values = android.content.ContentValues().apply {
            put(DOC_CONTENT, newContent)
            put(DOC_CONTENT_LENGTH, newContent.length)
            put(DOC_CONTENT_HASH, newContentHash)
            put(DOC_SOURCE_LAST_MODIFIED, newSourceLastModified)
            put(DOC_SOURCE_SIZE, newSourceSize)
            // 内容变更后标记为待同步
            put(DOC_SYNC_STATUS, SyncStatus.PENDING.name)
        }
        return db.update(TABLE_DOCS, values, "$DOC_ID=?", arrayOf(docId)) > 0
    }

    /**
     * 更新文档云端同步状态。
     */
    fun updateSyncStatus(
        docId: String,
        status: SyncStatus,
        cloudFileId: String? = null,
    ): Boolean {
        val values = android.content.ContentValues().apply {
            put(DOC_SYNC_STATUS, status.name)
            if (cloudFileId != null) put(DOC_CLOUD_FILE_ID, cloudFileId)
            if (status == SyncStatus.SYNCED) put(DOC_LAST_SYNCED_AT, System.currentTimeMillis())
        }
        return db.update(TABLE_DOCS, values, "$DOC_ID=?", arrayOf(docId)) > 0
    }

    /**
     * 获取需要同步的文档 (PENDING 或 FAILED 状态)。
     */
    fun getDocumentsNeedingSync(): List<KbDocument> {
        val list = mutableListOf<KbDocument>()
        db.query(
            TABLE_DOCS, null,
            "$DOC_SYNC_STATUS IN (?, ?)",
            arrayOf(SyncStatus.PENDING.name, SyncStatus.FAILED.name),
            null, null, "$DOC_ADDED_AT ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursorToDoc(cursor))
            }
        }
        return list
    }

    /**
     * 根据 ID 获取单个文档。
     */
    fun getDocumentById(docId: String): KbDocument? {
        db.query(TABLE_DOCS, null, "$DOC_ID=?", arrayOf(docId), null, null, null).use { cursor ->
            return if (cursor.moveToFirst()) cursorToDoc(cursor) else null
        }
    }

    private suspend fun parseFile(file: File): String {
        val ext = file.extension.lowercase()
        return when (ext) {
            "pdf" -> parsePdf(file)
            "docx", "doc" -> parseDocx(file)
            "txt", "md", "text" -> file.readText()
            "jpg", "jpeg", "png", "bmp", "webp" -> parseImage(file)
            else -> runCatching { file.readText() }.getOrNull() ?: ""
        }
    }

    private suspend fun parsePdf(file: File): String {
        return try {
            val ocrEngine = OcrEngine(context)
            val result = ocrEngine.recognizeFromFile(file.absolutePath)
            if (result.isSuccess) result.text else ""
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

    /**
     * 阶跃云端文件解析回退。
     *
     * 当本地解析器（PDF OCR / DOCX 提取等）失败或返回空结果时，
     * 通过 StepFun File API 上传文件进行云端纯文本提取。
     *
     * 使用 file-extract intent: 上传 → 等待处理 → 获取文本内容 (一步完成)
     */
    private suspend fun parseFileWithCloud(file: File, apiKey: String): String {
        return try {
            val result = StepFileParserClient.uploadAndExtract(
                apiKey = apiKey,
                filePath = file.absolutePath,
            )
            if (result.success && !result.extractedText.isNullOrBlank()) {
                DebugLog.i(TAG, "阶跃云端解析成功: ${file.name} (${result.extractedText.length} 字符)")
                result.extractedText.orEmpty()
            } else if (result.success) {
                // 上传成功但文本未就绪，返回占位信息
                DebugLog.i(TAG, "阶跃云端上传成功(待处理): ${file.name} -> fileId=${result.fileId}")
                "[已上传至阶跃云端解析中] fileId=${result.fileId} (${file.name}, ${file.length()} bytes)"
            } else {
                DebugLog.w(TAG, "阶跃云端解析失败: ${result.message}")
                ""
            }
        } catch (e: Exception) {
            DebugLog.w(TAG, "阶跃云端解析异常: ${e.message}")
            ""
        }
    }

    private fun copyUriToTemp(uri: android.net.Uri): File {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw java.io.IOException("无法打开Uri: $uri")
        val tempFile = File(context.cacheDir, "kb_temp_${System.currentTimeMillis()}.${getFileExtension(uri)}")
        try {
            tempFile.outputStream().use { output -> inputStream.use { input -> input.copyTo(output) } }
            return tempFile
        } catch (e: Exception) {
            if (tempFile.exists()) tempFile.delete()
            throw e
        }
    }

    private fun getFileExtension(uri: android.net.Uri): String {
        val mimeType = context.contentResolver.getType(uri) ?: return "bin"
        return when {
            mimeType == "application/pdf" -> "pdf"
            mimeType.startsWith("image/") -> "jpg"
            mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
            mimeType == "text/plain" -> "txt"
            else -> "bin"
        }
    }

    private fun ensureDefaultKb() {
        val existing = getKnowledgeBaseById("default")
        if (existing == null) {
            db.insertOrThrow(TABLE_KB, null, android.content.ContentValues().apply {
                put(KB_ID, "default")
                put(KB_NAME, "我的知识库")
                put(KB_DESC, "默认知识库，存放所有导入的文档")
                put(KB_VISIBILITY, Visibility.PRIVATE.name)
                put(KB_COVER_COLOR, "#4A90D9")
                val now = System.currentTimeMillis()
                put(KB_CREATED_AT, now)
                put(KB_UPDATED_AT, now)
            })
            DebugLog.i(TAG, "已创建默认知识库")
        }
    }

    // ---- Cursor 映射 ----

    private fun cursorToKbInfo(c: Cursor): KnowledgeBaseInfo {
        val id = c.getString(c.getColumnIndexOrThrow(KB_ID))

        // 计算文档数和总大小
        var docCount = 0
        var totalSize = 0L
        db.rawQuery("SELECT COUNT(*), COALESCE(SUM($DOC_FILE_SIZE),0) FROM $TABLE_DOCS WHERE $DOC_KB_ID=?", arrayOf(id)).use { dc ->
            if (dc.moveToFirst()) {
                docCount = dc.getInt(0)
                totalSize = dc.getLong(1)
            }
        }

        return KnowledgeBaseInfo(
            id = id,
            name = c.getString(c.getColumnIndexOrThrow(KB_NAME)),
            description = c.getString(c.getColumnIndexOrThrow(KB_DESC)),
            visibility = runCatching { Visibility.valueOf(c.getString(c.getColumnIndexOrThrow(KB_VISIBILITY))) }.getOrDefault(Visibility.PRIVATE),
            documentCount = docCount,
            totalSizeBytes = totalSize,
            createdAtMs = c.getLong(c.getColumnIndexOrThrow(KB_CREATED_AT)),
            updatedAtMs = c.getLong(c.getColumnIndexOrThrow(KB_UPDATED_AT)),
            coverColor = c.getString(c.getColumnIndexOrThrow(KB_COVER_COLOR)),
        )
    }

    private fun cursorToDoc(c: Cursor): KbDocument {
        val tagsJson = c.getString(c.getColumnIndexOrThrow(DOC_TAGS))
        val tags: List<String> = try {
            Json.decodeFromString<List<String>>(tagsJson)
        } catch (_: Exception) { emptyList() }

        // v2 新增列可能不存在 (旧数据库未升级时), 使用 getColumnIndex 安全读取 (-1 表示列不存在)
        fun longCol(name: String): Long {
            val idx = c.getColumnIndex(name)
            return if (idx >= 0) c.getLong(idx) else 0L
        }
        fun stringCol(name: String): String? {
            val idx = c.getColumnIndex(name)
            return if (idx >= 0) c.getString(idx) else null
        }

        val syncStatusStr = stringCol(DOC_SYNC_STATUS) ?: SyncStatus.LOCAL_ONLY.name
        val syncStatus = runCatching { SyncStatus.valueOf(syncStatusStr) }.getOrDefault(SyncStatus.LOCAL_ONLY)

        return KbDocument(
            id = c.getString(c.getColumnIndexOrThrow(DOC_ID)),
            title = c.getString(c.getColumnIndexOrThrow(DOC_TITLE)),
            fileName = c.getString(c.getColumnIndexOrThrow(DOC_FILE_NAME)),
            fileType = c.getString(c.getColumnIndexOrThrow(DOC_FILE_TYPE)),
            fileSizeBytes = c.getLong(c.getColumnIndexOrThrow(DOC_FILE_SIZE)),
            contentLength = c.getInt(c.getColumnIndexOrThrow(DOC_CONTENT_LENGTH)),
            content = c.getString(c.getColumnIndexOrThrow(DOC_CONTENT)),
            knowledgeBaseId = c.getString(c.getColumnIndexOrThrow(DOC_KB_ID)),
            tags = tags,
            sourceUri = c.getString(c.getColumnIndexOrThrow(DOC_SOURCE_URI)),
            addedAtMs = c.getLong(c.getColumnIndexOrThrow(DOC_ADDED_AT)),
            sourceLastModified = longCol(DOC_SOURCE_LAST_MODIFIED),
            sourceSize = longCol(DOC_SOURCE_SIZE),
            contentHash = stringCol(DOC_CONTENT_HASH) ?: "",
            cloudFileId = stringCol(DOC_CLOUD_FILE_ID),
            syncStatus = syncStatus,
            lastSyncedAt = longCol(DOC_LAST_SYNCED_AT),
        )
    }
}
