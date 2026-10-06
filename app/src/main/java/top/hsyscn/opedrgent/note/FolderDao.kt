package top.hsyscn.opedrgent.note

import android.content.ContentValues
import android.database.Cursor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 文件夹数据访问层（原生 SQLite 实现）。
 *
 * 所有操作通过 [FolderDatabase] 的 writableDatabase/readableDatabase 执行。
 */
class FolderDao(private val db: FolderDatabase) {

    // 变更通知由 FolderRepository._changeTrigger 驱动，DAO 层不再维护无消费方的 StateFlow。

    /** 查询所有未删除文件夹（按名称排序） */
    suspend fun getAllFolders(): List<Folder> = withContext(Dispatchers.IO) {
        val cursor = db.readableDatabase.query(
            FolderDatabase.TABLE_FOLDERS,
            null,
            "${FolderDatabase.COL_IS_DELETED} = 0",
            null, null, null,
            "${FolderDatabase.COL_NAME} ASC",
        )
        cursor.use { c -> c.mapToList(db) }
    }

    /** 按父文件夹筛选（null=根目录） */
    suspend fun getByParent(parentId: Long?): List<Folder> = withContext(Dispatchers.IO) {
        val where = if (parentId == null)
            "${FolderDatabase.COL_IS_DELETED} = 0 AND ${FolderDatabase.COL_PARENT_ID} IS NULL"
        else
            "${FolderDatabase.COL_IS_DELETED} = 0 AND ${FolderDatabase.COL_PARENT_ID} = ?"
        val args = parentId?.let { arrayOf(it.toString()) } ?: emptyArray()

        val cursor = db.readableDatabase.query(
            FolderDatabase.TABLE_FOLDERS, null, where, args,
            null, null, "${FolderDatabase.COL_NAME} ASC",
        )
        cursor.use { c -> c.mapToList(db) }
    }

    /** 搜索文件夹（名称模糊匹配） */
    suspend fun searchFolders(query: String): List<Folder> = withContext(Dispatchers.IO) {
        val likePattern = "%$query%"
        val cursor = db.readableDatabase.query(
            FolderDatabase.TABLE_FOLDERS, null,
            "${FolderDatabase.COL_IS_DELETED} = 0 AND ${FolderDatabase.COL_NAME} LIKE ?",
            arrayOf(likePattern),
            null, null, "${FolderDatabase.COL_NAME} ASC",
        )
        cursor.use { c -> c.mapToList(db) }
    }

    /** 获取单个文件夹 */
    suspend fun getById(id: Long): Folder? = withContext(Dispatchers.IO) {
        val cursor = db.readableDatabase.query(
            FolderDatabase.TABLE_FOLDERS, null,
            "${FolderDatabase.COL_ID} = ? AND ${FolderDatabase.COL_IS_DELETED} = 0",
            arrayOf(id.toString()), null, null, null,
        )
        cursor.use { c -> if (c.moveToFirst()) db.cursorToFolder(c) else null }
    }

    /** 文件夹总数 */
    suspend fun countAll(): Long = withContext(Dispatchers.IO) {
        var count = 0L
        db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM ${FolderDatabase.TABLE_FOLDERS} WHERE ${FolderDatabase.COL_IS_DELETED} = 0", null
        ).use { if (it.moveToFirst()) count = it.getLong(0) }
        count
    }

    /** 插入或更新 */
    suspend fun insertOrUpdate(folder: Folder): Long = withContext(Dispatchers.IO) {
        val values = folderToContentValues(folder)
        if (folder.id == 0L) {
            val id = db.writableDatabase.insert(FolderDatabase.TABLE_FOLDERS, null, values)
            id
        } else {
            db.writableDatabase.update(
                FolderDatabase.TABLE_FOLDERS, values,
                "${FolderDatabase.COL_ID} = ?", arrayOf(folder.id.toString()),
            )
            folder.id
        }
    }

    /**
     * 软删除文件夹（级联）：
     * 1) 直属子文件夹的 parentId 上移到被删文件夹的父目录，避免子树失去父节点从导航消失；
     * 2) 软删除自身。
     * 笔记的重挂由 FolderRepository 编排 NoteDao.reparentNotes 完成（跨库无单事务）。
     */
    suspend fun softDelete(id: Long) = withContext(Dispatchers.IO) {
        val parentId = getById(id)?.parentId
        val dbw = db.writableDatabase
        dbw.beginTransaction()
        try {
            // 子文件夹上移一层
            val childValues = ContentValues().apply {
                parentId?.let { put(FolderDatabase.COL_PARENT_ID, it) } ?: putNull(FolderDatabase.COL_PARENT_ID)
            }
            dbw.update(
                FolderDatabase.TABLE_FOLDERS, childValues,
                "${FolderDatabase.COL_PARENT_ID} = ? AND ${FolderDatabase.COL_IS_DELETED} = 0",
                arrayOf(id.toString()),
            )
            // 软删除自身
            val selfValues = ContentValues().apply {
                put(FolderDatabase.COL_IS_DELETED, 1)
                put(FolderDatabase.COL_UPDATED_AT, System.currentTimeMillis())
            }
            dbw.update(FolderDatabase.TABLE_FOLDERS, selfValues,
                "${FolderDatabase.COL_ID} = ?", arrayOf(id.toString()))
            dbw.setTransactionSuccessful()
        } finally {
            dbw.endTransaction()
        }
    }

    /** 重命名 */
    suspend fun rename(id: Long, newName: String) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(FolderDatabase.COL_NAME, newName)
            put(FolderDatabase.COL_UPDATED_AT, System.currentTimeMillis())
        }
        db.writableDatabase.update(FolderDatabase.TABLE_FOLDERS, values,
            "${FolderDatabase.COL_ID} = ?", arrayOf(id.toString()))
    }

    /** 移动文件夹到新父目录 */
    suspend fun moveToParent(id: Long, newParentId: Long?) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            newParentId?.let { put(FolderDatabase.COL_PARENT_ID, it) } ?: putNull(FolderDatabase.COL_PARENT_ID)
            put(FolderDatabase.COL_UPDATED_AT, System.currentTimeMillis())
        }
        db.writableDatabase.update(FolderDatabase.TABLE_FOLDERS, values,
            "${FolderDatabase.COL_ID} = ?", arrayOf(id.toString()))
    }

    /** 检查文件夹名称是否已存在（在指定父目录下） */
    suspend fun existsByName(name: String, parentId: Long?, excludeId: Long = 0): Boolean = withContext(Dispatchers.IO) {
        val where = if (parentId == null)
            "${FolderDatabase.COL_IS_DELETED} = 0 AND ${FolderDatabase.COL_NAME} = ? AND ${FolderDatabase.COL_PARENT_ID} IS NULL AND ${FolderDatabase.COL_ID} != ?"
        else
            "${FolderDatabase.COL_IS_DELETED} = 0 AND ${FolderDatabase.COL_NAME} = ? AND ${FolderDatabase.COL_PARENT_ID} = ? AND ${FolderDatabase.COL_ID} != ?"
        val args = if (parentId == null)
            arrayOf(name, excludeId.toString())
        else
            arrayOf(name, parentId.toString(), excludeId.toString())

        // 存在性查询：仅判断是否有匹配行。旧实现 projection=null 后取列0（id）当计数，
        // 仅靠自增 id>=1 侥幸正确；改为投影 id 列并直接看是否有行。
        db.readableDatabase.query(
            FolderDatabase.TABLE_FOLDERS, arrayOf(FolderDatabase.COL_ID), where, args,
            null, null, null,
        ).use { it.moveToFirst() }
    }

    // ==================== 内部工具 ====================

    private fun folderToContentValues(folder: Folder): ContentValues = ContentValues().apply {
        if (folder.id > 0) put(FolderDatabase.COL_ID, folder.id)
        put(FolderDatabase.COL_NAME, folder.name)
        folder.parentId?.let { put(FolderDatabase.COL_PARENT_ID, it) } ?: putNull(FolderDatabase.COL_PARENT_ID)
        put(FolderDatabase.COL_CREATED_AT, folder.createdAt)
        put(FolderDatabase.COL_UPDATED_AT, System.currentTimeMillis())
        put(FolderDatabase.COL_IS_DELETED, if (folder.isDeleted) 1 else 0)
    }

    private fun Cursor.mapToList(database: FolderDatabase): List<Folder> {
        val result = mutableListOf<Folder>()
        while (moveToNext()) result.add(database.cursorToFolder(this))
        return result
    }
}
