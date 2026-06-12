package top.hsyscn.opedrgent.storage

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.docx.DocxProcessor
import top.hsyscn.opedrgent.pdf.OcrEngine
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// ============================================================
// SQLite 数据库（原生实现，与 NoteDatabase 模式一致）
// ============================================================

private const val DB_NAME = "knowledge_base.db"
private const val DB_VERSION = 1

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
                FOREIGN KEY($DOC_KB_ID) REFERENCES $TABLE_KB($KB_ID) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_docs_kb_id ON $TABLE_DOCS($DOC_KB_ID)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_docs_type ON $TABLE_DOCS($DOC_FILE_TYPE)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 未来版本升级逻辑
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
        getKnowledgeBaseById(id)!!
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

    suspend fun addFile(uri: android.net.Uri, kbId: String = "default"): KbAddResult {
        return withContext(Dispatchers.IO) {
            try {
                val tempFile = copyUriToTemp(uri)
                try {
                    addFileInternal(tempFile, kbId, uri.toString())
                } finally {
                    tempFile.delete()
                }
            } catch (e: Exception) {
                DebugLog.e(TAG, "添加文件失败: ${e.message}", e)
                KbAddResult.error(e.message ?: "未知错误")
            }
        }
    }

    suspend fun addFile(filePath: String, kbId: String = "default"): KbAddResult {
        return withContext(Dispatchers.IO) {
            try {
                val file = File(filePath)
                if (!file.exists()) return@withContext KbAddResult.error("文件不存在: $filePath")
                addFileInternal(file, kbId, null)
            } catch (e: Exception) {
                DebugLog.e(TAG, "添加文件失败: ${e.message}", e)
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



    fun deleteDocument(documentId: String): Boolean {
        return db.delete(TABLE_DOCS, "$DOC_ID=?", arrayOf(documentId)) > 0
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

    private suspend fun addFileInternal(file: File, kbId: String, sourceUri: String?): KbAddResult {
        val content = parseFile(file)
        if (content.isBlank()) return KbAddResult.error("无法从文件中提取有效内容")

        val doc = KbDocument(
            title = file.nameWithoutExtension,
            fileName = file.name,
            fileType = file.extension.lowercase(),
            fileSizeBytes = file.length(),
            contentLength = content.length,
            addedAtMs = System.currentTimeMillis(),
            content = content,
            knowledgeBaseId = kbId,
            sourceUri = sourceUri,
        )

        saveDocument(doc)

        DebugLog.i(TAG, "文件添加成功: ${doc.title} (${doc.fileType}, ${content.length}字)")
        return KbAddResult.success(doc)
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
        })
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
        )
    }
}
