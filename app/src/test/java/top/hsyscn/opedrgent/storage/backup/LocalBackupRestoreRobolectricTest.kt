package top.hsyscn.opedrgent.storage.backup

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 本地备份「安全失败」路径在 Robolectric 下的验证（[LocalBackupManager.inspect] 与
 * [LocalBackupManager.restoreFrom] 的故障注入）。
 *
 * 历史记录指出：归档解析入口 inspect(InputStream) 本身是纯 JVM 逻辑，但构造
 * LocalBackupManager(context) 需要 Android Context，纯 JVM 无法实例化；Robolectric 可实例化。
 * 本类覆盖：截断/非 zip 字节、缺 manifest、校验和不符、版本不符、缺条目、空间前置失败、
 * 以及 apply 失败后的快照回滚与 tempZip/rollback 目录清理。
 *
 * 不覆盖：真实数据库 WAL checkpoint、SharedPreferences 真实迁移写入、StatFs 精确容量、
 * NPU/模型文件落地——这些需真机或更重的集成环境。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocalBackupRestoreRobolectricTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var manager: LocalBackupManager

    @Before
    fun setUp() {
        manager = LocalBackupManager.getInstance(ctx)
    }

    @After
    fun tearDown() {
        // 清理 cacheDir 中可能残留的临时归档/回滚目录
        ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    /**
     * Robolectric 的 ShadowStatFs 默认可用块数为 0，会让 restoreFrom 的空间预检
     * 一律误判为 INSUFFICIENT_STORAGE。这里通过反射把 ShadowStatFs 的块数字段
     * 调到极大，以便「成功路径/回滚路径」用例越过空间预检；
     * 而「空间不足」用例不调用本方法，保持默认小容量以触发 INSUFFICIENT_STORAGE。
     */
    private fun givePlentyOfDisk() {
        runCatching {
            org.robolectric.shadows.ShadowStatFs.registerStats(
                ctx.filesDir, 100_000_000, 100_000_000, 100_000_000,
            )
            org.robolectric.shadows.ShadowStatFs.registerStats(
                ctx.filesDir.absolutePath, 100_000_000, 100_000_000, 100_000_000,
            )
        }
    }

    // ---------- 归档构造工具 ----------

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun entry(path: String, bytes: ByteArray, component: String = "database") =
        BackupEntry(path = path, sizeBytes = bytes.size.toLong(), sha256 = sha256(bytes), component = component)

    private fun manifestJson(
        formatVersion: Int = 1,
        appVersionCode: Int = 4,
        entries: List<BackupEntry>,
    ): JSONObject = JSONObject().apply {
        put("formatVersion", formatVersion)
        put("appVersionCode", appVersionCode)
        put("appVersionName", "1.2.1")
        put("createdAt", 1_700_000_000_000L)
        put("includeModels", false)
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().apply {
                put("path", e.path)
                put("sizeBytes", e.sizeBytes)
                put("sha256", e.sha256)
                put("component", e.component)
            })
        }
        put("entries", arr)
    }

    /** 写一个含指定文件 + manifest.json 的内存 zip。 */
    private fun writeZip(manifest: JSONObject, files: List<Pair<String, ByteArray>>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            files.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return bos.toByteArray()
    }

    private fun tempArtifacts(): List<String> =
        ctx.cacheDir.list()?.toList().orEmpty()

    // ---------- inspect(InputStream) ----------

    @Test
    fun `inspect parses a valid manifest`() {
        val files = listOf("databases/seed.db3" to "HELLO".toByteArray())
        val entries = listOf(entry("databases/seed.db3", "HELLO".toByteArray()))
        val zip = writeZip(manifestJson(entries = entries), files)

        val manifest = manager.inspect(ByteArrayInputStream(zip))

        assertEquals(1, manifest.formatVersion)
        assertEquals(1, manifest.entries.size)
        assertEquals("databases/seed.db3", manifest.entries.first().path)
    }

    @Test
    fun `inspect rejects non-zip bytes`() {
        val garbage = "this is definitely not a zip archive".toByteArray()
        var thrown: IOException? = null
        try {
            manager.inspect(ByteArrayInputStream(garbage))
        } catch (e: IOException) {
            thrown = e
        }
        assertTrue("非 zip 字节应抛 IOException: $thrown", thrown != null)
    }

    @Test
    fun `inspect rejects zip without manifest entry`() {
        // 只有数据条目、没有 manifest.json
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            zip.putNextEntry(ZipEntry("databases/seed.db3"))
            zip.write("HELLO".toByteArray())
            zip.closeEntry()
        }
        var thrown: IOException? = null
        try {
            manager.inspect(ByteArrayInputStream(bos.toByteArray()))
        } catch (e: IOException) {
            thrown = e
        }
        assertTrue(thrown != null)
        assertTrue("应提示缺少 manifest: ${thrown?.message}",
            thrown?.message?.contains("manifest") == true)
    }

    // ---------- restoreFrom 故障注入 ----------

    @Test
    fun `restoreFrom unreadable input yields IO_ERROR and leaves no temp zip`() = runTest {
        val bad = object : InputStream() {
            override fun read(): Int = throw IOException("disk exploded")
        }
        val result = manager.restoreFrom(bad)
        assertEquals(RestoreCode.IO_ERROR, result.code)
        assertTrue(result.message.contains("无法读取归档"))
        assertTrue("失败后不应残留临时 zip: ${tempArtifacts()}",
            tempArtifacts().none { it.startsWith("restore_") })
    }

    @Test
    fun `restoreFrom non-zip bytes yields CORRUPTED`() = runTest {
        val result = manager.restoreFrom(ByteArrayInputStream("garbage-bytes".toByteArray()))
        assertEquals(RestoreCode.CORRUPTED, result.code)
        assertTrue(tempArtifacts().none { it.startsWith("restore_") })
    }

    @Test
    fun `restoreFrom zip without manifest yields CORRUPTED`() = runTest {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            zip.putNextEntry(ZipEntry("databases/x.db3"))
            zip.write("x".toByteArray()); zip.closeEntry()
        }
        val result = manager.restoreFrom(ByteArrayInputStream(bos.toByteArray()))
        assertEquals(RestoreCode.CORRUPTED, result.code)
    }

    @Test
    fun `restoreFrom wrong formatVersion yields UNSUPPORTED_VERSION`() = runTest {
        val entries = listOf(entry("databases/x.db3", "x".toByteArray()))
        val zip = writeZip(
            manifestJson(formatVersion = 99, entries = entries),
            listOf("databases/x.db3" to "x".toByteArray()),
        )
        val result = manager.restoreFrom(ByteArrayInputStream(zip))
        assertEquals(RestoreCode.UNSUPPORTED_VERSION, result.code)
        assertTrue(result.message.contains("归档版本"))
        assertTrue(tempArtifacts().none { it.startsWith("restore_") })
    }

    @Test
    fun `restoreFrom downgraded appVersionCode yields UNSUPPORTED_VERSION unless allowDowngrade`() = runTest {
        // 用 -1 保证 < 任意 curVc（Robolectric 下 packageManager versionCode 可能为 0/1）
        val entries = listOf(entry("databases/x.db3", "x".toByteArray()))
        val zip = writeZip(
            manifestJson(appVersionCode = -1, entries = entries),
            listOf("databases/x.db3" to "x".toByteArray()),
        )
        val blocked = manager.restoreFrom(ByteArrayInputStream(zip), allowDowngrade = false)
        assertEquals(RestoreCode.UNSUPPORTED_VERSION, blocked.code)
        assertTrue(blocked.message.contains("旧版本") || blocked.message.contains("allowDowngrade"))
    }

    @Test
    fun `restoreFrom sha mismatch yields SHA_MISMATCH`() = runTest {
        val bytes = "CONTENT".toByteArray()
        // 声明的 sha256 与真实内容不符
        val badEntry = BackupEntry("databases/x.db3", bytes.size.toLong(), "deadbeefdeadbeef", "database")
        val zip = writeZip(
            manifestJson(entries = listOf(badEntry)),
            listOf("databases/x.db3" to bytes),
        )
        val result = manager.restoreFrom(ByteArrayInputStream(zip))
        assertEquals(RestoreCode.SHA_MISMATCH, result.code)
        assertTrue(tempArtifacts().none { it.startsWith("restore_") })
    }

    @Test
    fun `restoreFrom with default tiny disk yields INSUFFICIENT_STORAGE and cleans tempZip`() = runTest {
        // 不调用 givePlentyOfDisk：ShadowStatFs 默认可用块≈0，合法归档在空间预检即被安全拒绝
        val entries = listOf(entry("databases/x.db3", "x".toByteArray()))
        val zip = writeZip(
            manifestJson(entries = entries),
            listOf("databases/x.db3" to "x".toByteArray()),
        )
        val result = manager.restoreFrom(ByteArrayInputStream(zip))
        assertEquals(RestoreCode.INSUFFICIENT_STORAGE, result.code)
        // 空间不足也是安全失败：临时 zip 必须清理
        assertTrue(tempArtifacts().none { it.startsWith("restore_") })
    }

    @Test
    fun `restoreFrom missing declared entry yields CORRUPTED`() = runTest {
        // manifest 声明了 databases/x.db3，但 zip 里根本没有该条目
        val declared = entry("databases/x.db3", "x".toByteArray())
        val zip = writeZip(
            manifestJson(entries = listOf(declared)),
            // 故意不放 databases/x.db3
            emptyList(),
        )
        val result = manager.restoreFrom(ByteArrayInputStream(zip))
        assertEquals(RestoreCode.CORRUPTED, result.code)
        assertTrue(result.message.contains("缺少条目"))
    }

    @Test
    fun `restoreFrom apply failure rolls back snapshot and restores original db`() = runTest {
        givePlentyOfDisk()
        // 1. 种子数据库，内容 ORIGINAL
        val seed = ctx.getDatabasePath("seed.db")
        seed.parentFile!!.mkdirs()
        seed.writeBytes("ORIGINAL".toByteArray())

        // 2. 条目1：会把 seed.db 覆盖成 TAMPERED
        val tampered = "TAMPERED".toByteArray()
        val e1 = entry("databases/seed.db3", tampered)
        // 3. 条目2：Zip Slip 穿越，apply 时抛 IOException 触发回滚
        val slipBytes = "x".toByteArray()
        val e2 = entry("databases/../../evil.db3", slipBytes)

        val zip = writeZip(
            manifestJson(entries = listOf(e1, e2)),
            listOf(
                "databases/seed.db3" to tampered,
                "databases/../../evil.db3" to slipBytes,
            ),
        )

        val result = manager.restoreFrom(ByteArrayInputStream(zip))

        // apply 阶段在写入 seed.db 后、处理穿越条目时失败 -> 整体回滚
        assertEquals(RestoreCode.IO_ERROR, result.code)
        assertTrue(result.message.contains("已回滚"))
        // 回滚断言：种子库内容被恢复，而非停留在 TAMPERED
        assertEquals("ORIGINAL", seed.readText())
        // 临时 zip 与回滚目录都应清理
        assertTrue("不应残留 restore_ 临时包: ${tempArtifacts()}",
            tempArtifacts().none { it.startsWith("restore_") })
        assertTrue("不应残留 rollback_ 目录: ${tempArtifacts()}",
            tempArtifacts().none { it.startsWith("rollback_") })
    }
}
