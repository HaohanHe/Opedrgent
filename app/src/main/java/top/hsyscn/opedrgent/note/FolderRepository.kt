package top.hsyscn.opedrgent.note

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.util.concurrent.atomic.AtomicLong

/**
 * 文件夹仓库：统一数据访问层。
 *
 * 对外提供简洁的 CRUD API + Flow 响应式更新通知。
 * 内部通过 [FolderDao] 操作原生 SQLite。
 */
class FolderRepository(context: Context) {

    private val database = FolderDatabase.getInstance(context)
    private val dao = FolderDao(database)
    // 笔记库句柄：文件夹软删除时级联重挂该文件夹下的笔记，避免孤儿（跨库无单事务，best-effort）
    private val noteDao = NoteDao(NoteDatabase.getInstance(context))

    // 响应式变更通知：单调递增计数器，避免同毫秒连续写被 StateFlow 去重漏刷
    private val _changeTrigger = MutableStateFlow(0L)
    private val changeCounter = AtomicLong(0L)

    private fun bumpChange() {
        _changeTrigger.value = changeCounter.incrementAndGet()
    }

    /** 所有文件夹（按名称排序） */
    fun getAllFolders(): Flow<List<Folder>> = _changeTrigger
        .map { dao.getAllFolders() }
        .flowOn(Dispatchers.IO)
        .conflate()

    /** 按父文件夹筛选 */
    fun getByParent(parentId: Long? = null): Flow<List<Folder>> = _changeTrigger
        .map { dao.getByParent(parentId) }
        .flowOn(Dispatchers.IO)
        .conflate()

    /** 搜索文件夹（名称模糊匹配） */
    fun searchFolders(query: String): Flow<List<Folder>> = _changeTrigger
        .map { dao.searchFolders(query) }
        .flowOn(Dispatchers.IO)
        .conflate()

    /** 获取单个文件夹 */
    suspend fun getFolderById(id: Long): Folder? = dao.getById(id)

    /** 文件夹总数 */
    fun countAll(): Flow<Long> = _changeTrigger
        .map { dao.countAll() }
        .flowOn(Dispatchers.IO)
        .conflate()

    /** 创建或更新文件夹 */
    suspend fun saveFolder(folder: Folder): Long {
        val id = dao.insertOrUpdate(folder)
        bumpChange()
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

    /**
     * 软删除文件夹（级联）：
     * - dao.softDelete 同库事务内把直属子文件夹上移一层；
     * - 再把该文件夹下的直属笔记重挂到根目录（folder_id=NULL），避免从根目录/文件夹导航均不可见的孤儿笔记。
     */
    suspend fun deleteFolder(id: Long) {
        dao.softDelete(id)
        noteDao.reparentNotes(id, null)
        bumpChange()
    }

    /** 重命名 */
    suspend fun renameFolder(id: Long, newName: String) {
        val folder = dao.getById(id) ?: return
        // 检查新名称是否已存在
        if (dao.existsByName(newName, folder.parentId, id)) {
            throw IllegalArgumentException("文件夹名称已存在")
        }
        dao.rename(id, newName)
        bumpChange()
    }

    /** 移动文件夹到新父目录 */
    suspend fun moveToParent(id: Long, newParentId: Long?) {
        dao.moveToParent(id, newParentId)
        bumpChange()
    }

    /** 检查文件夹名称是否已存在 */
    suspend fun existsByName(name: String, parentId: Long?, excludeId: Long = 0): Boolean = dao.existsByName(name, parentId, excludeId)
}
