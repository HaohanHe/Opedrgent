package top.hsyscn.opedrgent.note

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 文件夹数据库（原生 SQLite 实现）。
 *
 * 不依赖 Room/kapt，避免注解处理器兼容性问题。
 */
class FolderDatabase(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {

    companion object {
        private const val DATABASE_NAME = "opedrgent_folders.db"
        private const val DATABASE_VERSION = 1
        const val TABLE_FOLDERS = "folders"

        // 列名
        const val COL_ID = "id"
        const val COL_NAME = "name"
        const val COL_PARENT_ID = "parent_id"
        const val COL_CREATED_AT = "created_at"
        const val COL_UPDATED_AT = "updated_at"
        const val COL_IS_DELETED = "is_deleted"

        // 单例
        @Volatile
        private var INSTANCE: FolderDatabase? = null

        fun getInstance(ctx: Context): FolderDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: FolderDatabase(ctx.applicationContext).also { INSTANCE = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_FOLDERS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_NAME TEXT NOT NULL,
                $COL_PARENT_ID INTEGER,
                $COL_CREATED_AT INTEGER NOT NULL,
                $COL_UPDATED_AT INTEGER NOT NULL,
                $COL_IS_DELETED INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())

        // 索引
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_folders_parent ON $TABLE_FOLDERS($COL_PARENT_ID)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_folders_name ON $TABLE_FOLDERS($COL_NAME)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 未来版本升级时处理数据迁移
    }

    /** 从 Cursor 构建 Folder 对象 */
    internal fun cursorToFolder(cursor: Cursor): Folder {
        return Folder(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
            name = cursor.getString(cursor.getColumnIndexOrThrow(COL_NAME)) ?: "",
            parentId = cursor.getColumnIndexOrThrow(COL_PARENT_ID).let { idx -> if (cursor.isNull(idx)) null else cursor.getLong(idx) },
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_CREATED_AT)),
            updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_UPDATED_AT)),
            isDeleted = cursor.getInt(cursor.getColumnIndexOrThrow(COL_IS_DELETED)) == 1,
        )
    }
}
