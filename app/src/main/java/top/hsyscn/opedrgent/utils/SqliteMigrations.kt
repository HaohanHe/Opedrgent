package top.hsyscn.opedrgent.utils

import android.database.sqlite.SQLiteDatabase

/**
 * SQLite 增量迁移辅助。
 *
 * 定位：统一各 SQLiteOpenHelper 的 onUpgrade 写法——按 oldVersion 顺序执行增量步骤
 * （CREATE TABLE IF NOT EXISTS / ALTER TABLE ADD COLUMN），不 DROP 用户数据表。
 *
 * 说明：框架在调用 onUpgrade 时已将整个升级过程包在事务内，onUpgrade 抛异常即回滚，
 * 因此迁移步骤只需执行 DDL/DML，无需自行再开事务。
 *
 * 安全：表名/列名均来自各库内部常量，非用户输入，可安全内插到 SQL。
 */
object SqliteMigrations {

    /** 表是否存在。 */
    fun tableExists(db: SQLiteDatabase, table: String): Boolean {
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(table),
        ).use { c -> return c.moveToFirst() }
    }

    /** 列是否存在（PRAGMA table_info）。 */
    fun columnExists(db: SQLiteDatabase, table: String, column: String): Boolean {
        db.rawQuery("PRAGMA table_info(${quoteIdent(table)})", null).use { c ->
            val nameIdx = c.getColumnIndexOrThrow("name")
            while (c.moveToNext()) {
                if (c.getString(nameIdx) == column) return true
            }
            return false
        }
    }

    /** 缺列则 ALTER TABLE ADD COLUMN；已存在则跳过。 */
    fun addColumnIfMissing(
        db: SQLiteDatabase,
        table: String,
        column: String,
        columnDef: String,
    ) {
        if (!columnExists(db, table, column)) {
            db.execSQL("ALTER TABLE ${quoteIdent(table)} ADD COLUMN ${quoteIdent(column)} $columnDef")
        }
    }

    /** 表不存在则执行建表 SQL（createSql 应使用 CREATE TABLE IF NOT EXISTS）。 */
    fun ensureTable(db: SQLiteDatabase, table: String, createSql: String) {
        if (!tableExists(db, table)) db.execSQL(createSql)
    }

    /**
     * 按版本顺序执行增量迁移。
     *
     * @param steps 键为「从该版本升级」的起始版本，值为对应的迁移动作；
     *              未提供步骤的版本号直接跳过（不做任何破坏性操作）。
     */
    fun runUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int,
        steps: Map<Int, (SQLiteDatabase) -> Unit>,
    ) {
        var version = oldVersion
        while (version < newVersion) {
            steps[version]?.invoke(db)
            version++
        }
    }

    private fun quoteIdent(ident: String): String = "\"${ident.replace("\"", "\"\"")}\""
}
