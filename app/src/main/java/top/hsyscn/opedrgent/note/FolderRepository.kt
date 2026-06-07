package top.hsyscn.opedrgent.note

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * 文件夹仓库：统一数据访问层。
 *
 * 对外提供简洁的 CRUD API + Flow 响应式更新通知。
 * 内部通过 [FolderDao] 操作原生 SQLite。
 */
class FolderRepository(context: Context) {

    private val database = FolderDatabase.getInstance(context)
    private val dao = FolderDao(database)

    // 响应式变更通知
    private val _changeTrigger = MutableStateFlow(0L)

    /** 所有文件夹（按名称排序） */
    fun getAllFolders(): Flow<List<Folder>> = _changeTrigger.map { dao.getAllFolders() }

    /** 按父文件夹筛选 */
    fun getByParent(parentId: Long? = null): Flow<List<Folder>> = _changeTrigger.map { dao.getByParent(parentId) }

    /** 搜索文件夹（名称模糊匹配） */
    fun searchFolders(query: String): Flow<List<Folder>> = _changeTrigger.map { dao.searchFolders(query) }

    /** 获取单个文件夹 */
    suspend fun getFolderById(id: Long): Folder? = dao.getById(id)

    /** 文件夹总数 */
    fun countAll(): Flow<Long> = _changeTrigger.map { dao.countAll() }

    /** 创建或更新文件夹 */
    suspend fun saveFolder(folder: Folder): Long {
        val id = dao.insertOrUpdate(folder)
        _changeTrigger.value = System.currentTimeMillis()
        return id
    }

    /** 快速创建文件夹（只需名称） */
    suspend fun quickCreate(name: String, parentId: Long? = null): Long {
        // 检查名称是否已存在
        if (dao.existsByName(name, parentId)) {
            throw IllegalArgumentException("文件夹名称已存在")
        }
        val folder = Folder(name = name, parentId = parentId)
        return saveFolder(folder)
    }

    /** 软删除 */
    suspend fun deleteFolder(id: Long) { dao.softDelete(id); _changeTrigger.value = System.currentTimeMillis() }

    /** 重命名 */
    suspend fun renameFolder(id: Long, newName: String) {
        val folder = dao.getById(id) ?: return
        // 检查新名称是否已存在
        if (dao.existsByName(newName, folder.parentId, id)) {
            throw IllegalArgumentException("文件夹名称已存在")
        }
        dao.rename(id, newName)
        _changeTrigger.value = System.currentTimeMillis()
    }

    /** 移动文件夹到新父目录 */
    suspend fun moveToParent(id: Long, newParentId: Long?) {
        dao.moveToParent(id, newParentId)
        _changeTrigger.value = System.currentTimeMillis()
    }

    /** 检查文件夹名称是否已存在 */
    suspend fun existsByName(name: String, parentId: Long?, excludeId: Long = 0): Boolean = dao.existsByName(name, parentId, excludeId)
}
