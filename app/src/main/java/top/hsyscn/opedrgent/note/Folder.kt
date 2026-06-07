package top.hsyscn.opedrgent.note

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*

/**
 * 文件夹数据模型（参考得到大脑笔记本系统设计）。
 *
 * 支持：
 * - 创建/重命名/删除文件夹
 * - 文件夹层级（通过 parentId 支持嵌套）
 * - 笔记移动到文件夹
 */
data class Folder(
    val id: Long = 0,
    
    /** 文件夹名称 */
    var name: String,
    
    /** 父文件夹 ID，null 表示根目录 */
    var parentId: Long? = null,
    
    /** 创建时间戳（毫秒） */
    val createdAt: Long = System.currentTimeMillis(),
    
    /** 最后修改时间戳（毫秒） */
    var updatedAt: Long = System.currentTimeMillis(),
    
    /** 是否已删除（软删除） */
    var isDeleted: Boolean = false,
) {
    companion object {
        /** 根目录 ID */
        const val ROOT_FOLDER_ID = 0L
        
        /** 默认文件夹颜色 */
        val DEFAULT_COLOR = Color(0xFF4A90D9)
    }
}

fun Folder.icon(): ImageVector = Icons.Default.Folder

fun Folder.color(): Color = Folder.DEFAULT_COLOR

fun Folder.displayName(): String = name
