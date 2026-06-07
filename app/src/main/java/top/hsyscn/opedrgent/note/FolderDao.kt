package top.hsyscn.opedrgent.note

import android.content.ContentValues
import android.database.Cursor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 文件夹数据访问层（原生 SQLite 实现）。
 *
 * 所有操作通过 [FolderDatabase] 的 writableDatabase/readableDatabase 执行。
 */
class FolderDao(private val db: FolderDatabase) {

    private val _changeNotifier = MutableStateFlow(System.currentTimeMillis())
    val changeNotifier: Flow<Long> = _changeNotifier

    private fun notifyChange() { _changeNotifier.value = System.currentTimeMillis() }

    /** 查询所有未删除文件夹（按名称排序） */
    fun getAllFolders(): List<Folder> {
        val cursor = db.readableDatabase.query(
            FolderDatabase.TABLE_FOLDERS,
            null,
            "${FolderDatabase.COL_IS_DELETED} = 0",
            null, null, null,
            "${FolderDatabase.COL_NAME} ASC",
        )
        return cursor.use { c -> c.mapToList(db) }
    }

    /** 按父文件夹筛选（null=根目录） */
    fun getByParent(parentId: Long?): List<Folder> {
        val where = if (parentId == null)
            "${FolderDatabase.COL_IS_DELETED} = 0 AND ${FolderDatabase.COL_PARENT_ID} IS NULL"
        else
            "${FolderDatabase.COL_IS_DELETED} = 0 AND ${FolderDatabase.COL_PARENT_ID} = ?"
        val args = parentId?.let { arrayOf(it.toString()) } ?: emptyArray()

        val cursor = db.readableDatabase.query(
            FolderDatabase.TABLE_FOLDERS, null, where, args,
            null, null, "${FolderDatabase.COL_NAME} ASC",
        )
        return cursor.use { c -> c.mapToList(db) }
    }

    /** 搜索文件夹（名称模糊匹配） */
    fun searchFolders(query: String): List<Folder> {
        val likePattern = "%$query%"
        val cursor = db.readableDatabase.query(
            FolderDatabase.TABLE_FOLDERS, null,
            "${FolderDatabase.COL_IS_DELETED} = 0 AND ${FolderDatabase.COL_NAME} LIKE ?",
            arrayOf(likePattern),
            null, null, "${FolderDatabase.COL_NAME} ASC",
        )
        return cursor.use { c -> c.mapToList(db) }
    }

    /** 获取单个文件夹 */
    fun getById(id: Long): Folder? {
        val cursor = db.readableDatabase.query(
            FolderDatabase.TABLE_FOLDERS, null,
            "${FolderDatabase.COL_ID} = ? AND ${FolderDatabase.COL_IS_DELETED} = 0",
            arrayOf(id.toString()), null, null, null,
        )
        return cursor.use { c -> if (c.moveToFirst()) db.cursorToFolder(c) else null }
    }

    /** 文件夹总数 */
    fun countAll(): Long {
        var count = 0L
        db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM ${FolderDatabase.TABLE_FOLDERS} WHERE ${FolderDatabase.COL_IS_DELETED} = 0", null
        ).use { if (it.moveToFirst()) count = it.getLong(0) }
        return count
    }

    /** 插入或更新 */
    fun insertOrUpdate(folder: Folder): Long {
        val values = folderToContentValues(folder)
        return if (folder.id == 0L) {
            val id = db.writableDatabase.insert(FolderDatabase.TABLE_FOLDERS, null, values)
            notifyChange()
            id
        } else {
            db.writableDatabase.update(
                FolderDatabase.TABLE_FOLDERS, values,
                "${FolderDatabase.COL_ID} = ?", arrayOf(folder.id.toString()),
            )
            notifyChange()
            folder.id
        }
    }

    /** 软删除 */
    fun softDelete(id: Long) {
        val values = ContentValues().apply {
            put(FolderDatabase.COL_IS_DELETED, 1)
            put(FolderDatabase.COL_UPDATED_AT, System.currentTimeMillis())
        }
        db.writableDatabase.update(FolderDatabase.TABLE_FOLDERS, values,
            "${FolderDatabase.COL_ID} = ?", arrayOf(id.toString()))
        notifyChange()
    }

    /** 重命名 */
    fun rename(id: Long, newName: String) {
        val values = ContentValues().apply {
            put(FolderDatabase.COL_NAME, newName)
            put(FolderDatabase.COL_UPDATED_AT, System.currentTimeMillis())
        }
        db.writableDatabase.update(FolderDatabase.TABLE_FOLDERS, values,
            "${FolderDatabase.COL_ID} = ?", arrayOf(id.toString()))
        notifyChange()
    }

    /** 移动文件夹到新父目录 */
    fun moveToParent(id: Long, newParentId: Long?) {
        val values = ContentValues().apply {
            newParentId?.let { put(FolderDatabase.COL_PARENT_ID, it) } ?: putNull(FolderDatabase.COL_PARENT_ID)
            put(FolderDatabase.COL_UPDATED_AT, System.currentTimeMillis())
        }
        db.writableDatabase.update(FolderDatabase.TABLE_FOLDERS, values,
            "${FolderDatabase.COL_ID} = ?", arrayOf(id.toString()))
        notifyChange()
    }

    /** 检查文件夹名称是否已存在（在指定父目录下） */
    fun existsByName(name: String, parentId: Long?, excludeId: Long = 0): Boolean {
        val where = if (parentId == null)
            "${FolderDatabase.COL_IS_DELETED} = 0 AND ${FolderDatabase.COL_NAME} = ? AND ${FolderDatabase.COL_PARENT_ID} IS NULL AND ${FolderDatabase.COL_ID} != ?"
        else
            "${FolderDatabase.COL_IS_DELETED} = 0 AND ${FolderDatabase.COL_NAME} = ? AND ${FolderDatabase.COL_PARENT_ID} = ? AND ${FolderDatabase.COL_ID} != ?"
        val args = if (parentId == null)
            arrayOf(name, excludeId.toString())
        else
            arrayOf(name, parentId.toString(), excludeId.toString())

        var count = 0
        db.readableDatabase.query(
            FolderDatabase.TABLE_FOLDERS, null, where, args,
            null, null, null,
        ).use { c -> if (c.moveToFirst()) count = c.getInt(0) }
        return count > 0
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
