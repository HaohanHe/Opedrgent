package top.hsyscn.opedrgent.utils

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import top.hsyscn.opedrgent.action.ActionDatabase
import top.hsyscn.opedrgent.cultivation.store.CultivationDatabase
import top.hsyscn.opedrgent.cultivation.store.GrowthReviewDatabase
import top.hsyscn.opedrgent.note.FolderDatabase
import top.hsyscn.opedrgent.storage.SproutReportDatabase

/**
 * R27 数据库增量迁移权威验证（Robolectric + 真实 SQLite）。
 *
 * 仅用于隔离副本验证：覆盖
 *  - 全新安装（五库 onCreate 后表/列齐全、user_version 正确）；
 *  - Cultivation 旧库 v1 -> v2 升级（mirror_report 数据映射迁入 reflection_session、废弃表 DROP、
 *    列不对应时跳过不臆造、user_version=2）；
 *  - 幂等（重复 open 不报错、不重复插入、不丢数据）；
 *  - 事务回滚（onUpgrade 中途抛异常 -> 框架整体回滚，user_version 不变、无半建表）；
 *  - 四库 VERSION=1 全新安装 + runUpgrade 空 map 安全步进。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class R27MigrationRobolectricTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    private fun dbFile(name: String) = ctx.getDatabasePath(name)

    private fun resetSingletons() {
        CultivationDatabase.closeAndReset()
        ActionDatabase.closeAndReset()
        GrowthReviewDatabase.closeAndReset()
    }

    @Before
    fun setUp() = resetSingletons()

    @After
    fun tearDown() = resetSingletons()

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean =
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(table),
        ).use { it.moveToFirst() }

    private fun countRows(db: SQLiteDatabase, table: String): Long =
        db.rawQuery("SELECT COUNT(*) FROM \"$table\"", null).use {
            it.moveToFirst(); it.getLong(0)
        }

    /** 手工构造一个 v1 的 cultivation.db：含可与 reflection_session 列对应的 mirror_report 样例行。 */
    private fun buildV1CultivationDb(mirrorUnrelated: Boolean): Long {
        val f = dbFile("cultivation.db")
        f.parentFile!!.mkdirs()
        if (f.exists()) f.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(f, null)
        db.version = 1 // 旧库版本
        if (mirrorUnrelated) {
            // 列完全不对应的废弃镜像表：迁移应跳过、不臆造任何行。
            db.execSQL("CREATE TABLE mirror_report (foo TEXT, bar INTEGER, baz REAL)")
            return 0
        }
        db.execSQL(
            """
            CREATE TABLE mirror_report (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                lens TEXT NOT NULL DEFAULT 'CRITIQUE',
                route TEXT NOT NULL DEFAULT 'ANALYZE',
                mode TEXT NOT NULL DEFAULT 'STANDARD',
                exemplar TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                marks_json TEXT NOT NULL,
                reflection TEXT NOT NULL,
                retained INTEGER NOT NULL DEFAULT 1,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "INSERT INTO mirror_report (lens, route, mode, exemplar, payload_json, marks_json, reflection, retained, created_at) " +
                "VALUES ('CRITIQUE','ANALYZE','STANDARD','','{\"k\":1}','{}','old reflection text',1,111)",
        )
        db.execSQL(
            "INSERT INTO mirror_report (lens, route, mode, exemplar, payload_json, marks_json, reflection, retained, created_at) " +
                "VALUES ('EXEMPLAR','SYNTHESIZE','DEEP','wiseone','{\"k\":2}','{\"m\":true}','second row',0,222)",
        )
        db.close()
        return 2
    }

    // ---------- Cultivation：旧库 v1 -> v2 升级（重点） ----------

    @Test
    fun v1ToV2_migratesMatchingColumns_andDropsMirrorReport() {
        val expected = buildV1CultivationDb(mirrorUnrelated = false)

        val helper = CultivationDatabase.getInstance(ctx) // VERSION=2
        val db = helper.readableDatabase // 触发 onUpgrade(1,2)

        // ① 版本号推进到 2
        assertEquals(2, db.version)
        // ② 两张当前表被 CREATE IF NOT EXISTS 建出
        assertTrue(tableExists(db, "reflection_session"))
        assertTrue(tableExists(db, "virtue_baseline"))
        // ③ 废弃 mirror_report 被 DROP
        assertFalse(tableExists(db, "mirror_report"))
        // ④ 样例行按同名列迁入 reflection_session
        assertEquals(expected, countRows(db, "reflection_session"))
        // ⑤ 列对应正确、内容保留
        db.rawQuery(
            "SELECT lens, route, reflection, payload_json, created_at FROM reflection_session WHERE created_at=111",
            null,
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("CRITIQUE", c.getString(0))
            assertEquals("ANALYZE", c.getString(1))
            assertEquals("old reflection text", c.getString(2))
            assertEquals("{\"k\":1}", c.getString(3))
            assertEquals(111L, c.getLong(4))
        }
        db.rawQuery(
            "SELECT retained, exemplar FROM reflection_session WHERE created_at=222",
            null,
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0L, c.getLong(0))
            assertEquals("wiseone", c.getString(1))
        }
        helper.close()
    }

    @Test
    fun v1ToV2_unrelatedMirrorReport_isSkipped_notFabricated() {
        buildV1CultivationDb(mirrorUnrelated = true)

        val helper = CultivationDatabase.getInstance(ctx)
        val db = helper.readableDatabase

        assertEquals(2, db.version)
        assertTrue(tableExists(db, "reflection_session"))
        // 无意义映射：不臆造任何行
        assertEquals(0L, countRows(db, "reflection_session"))
        // 即使不可映射，废弃表仍被 DROP
        assertFalse(tableExists(db, "mirror_report"))
        helper.close()
    }

    @Test
    fun reopen_v2_isIdempotent_noDuplicate_noLoss() {
        buildV1CultivationDb(mirrorUnrelated = false)

        val h1 = CultivationDatabase.getInstance(ctx)
        val n1 = countRows(h1.readableDatabase, "reflection_session")
        h1.close()
        CultivationDatabase.closeAndReset()

        // 库已在 v2，再次 open：onUpgrade 不应再触发
        val h2 = CultivationDatabase.getInstance(ctx)
        val db2 = h2.readableDatabase
        assertEquals(2, db2.version)
        val n2 = countRows(db2, "reflection_session")
        assertEquals(n1, n2) // 不重复插入、不丢数据
        assertFalse(tableExists(db2, "mirror_report"))
        h2.close()
    }

    // ---------- 事务回滚：框架把 onUpgrade 整体包在事务里 ----------

    private class FailingUpgradeHelper(context: Context) :
        SQLiteOpenHelper(context, "rollback_probe.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE t(a)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("CREATE TABLE half_done(x)") // 第一步成功
            error("injected failure")             // 第二步必然失败
        }
    }

    @Test
    fun onUpgrade_failure_rollsBack_entireTransaction() {
        val f = dbFile("rollback_probe.db")
        if (f.exists()) f.delete()
        // 准备一个 v1 基线库
        SQLiteDatabase.openOrCreateDatabase(f, null).use { v1 ->
            v1.version = 1
            v1.execSQL("CREATE TABLE seed(a)")
            v1.execSQL("INSERT INTO seed VALUES (42)")
        }

        val helper = FailingUpgradeHelper(ctx)
        var threw = false
        try {
            helper.writableDatabase // 触发 onUpgrade(1,2)
        } catch (e: RuntimeException) {
            threw = true
        }
        helper.close()
        assertTrue("onUpgrade 应向外抛出注入的异常", threw)

        // 以只读方式重新检查落盘状态：事务整体回滚
        SQLiteDatabase.openDatabase(
            f.path, null, SQLiteDatabase.OPEN_READWRITE,
        ).use { chk ->
            assertEquals(1, chk.version)          // user_version 仍为旧值
            assertFalse(tableExists(chk, "half_done")) // 半建的表被回滚
            assertTrue(tableExists(chk, "seed"))      // 升级前数据完好
            chk.rawQuery("SELECT a FROM seed", null).use {
                it.moveToFirst(); assertEquals(42L, it.getLong(0))
            }
        }
    }

    // ---------- 四库 VERSION=1：全新安装 ----------

    @Test
    fun freshInstall_fourVersion1Databases() {
        val a = ActionDatabase.getInstance(ctx).readableDatabase
        assertEquals(1, a.version)
        assertTrue(tableExists(a, "action_item"))
        a.close()
        ActionDatabase.closeAndReset()

        val g = GrowthReviewDatabase.getInstance(ctx).readableDatabase
        assertEquals(1, g.version)
        assertTrue(tableExists(g, "growth_review"))
        g.close()
        GrowthReviewDatabase.closeAndReset()

        FolderDatabase(ctx).apply {
            assertEquals(1, readableDatabase.version)
            assertTrue(tableExists(readableDatabase, "folders"))
            close()
        }

        SproutReportDatabase(ctx).apply {
            assertEquals(1, readableDatabase.version)
            assertTrue(tableExists(readableDatabase, "sprout_reports"))
            close()
        }
    }

    // ---------- runUpgrade 空 map 安全步进（oldVersion<newVersion） ----------

    @Test
    fun runUpgrade_emptyMap_stepsSafely_noDrop_noError() {
        val f = dbFile("step_probe.db")
        if (f.exists()) f.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(f, null)
        db.version = 1
        db.execSQL("CREATE TABLE keep(a)")
        db.execSQL("INSERT INTO keep VALUES (42)")

        // 空 steps map：从 1 步进至 3，无对应步骤应直接跳过、不报错、不 DROP
        SqliteMigrations.runUpgrade(db, 1, 3, emptyMap())

        assertTrue(tableExists(db, "keep"))
        db.rawQuery("SELECT a FROM keep", null).use {
            it.moveToFirst(); assertEquals(42L, it.getLong(0))
        }
        db.close()
    }
}
