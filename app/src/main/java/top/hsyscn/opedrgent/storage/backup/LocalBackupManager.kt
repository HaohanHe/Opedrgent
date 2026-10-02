package top.hsyscn.opedrgent.storage.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.StatFs
import org.json.JSONArray
import org.json.JSONObject
import top.hsyscn.opedrgent.llm.ModelDownloadManager
import top.hsyscn.opedrgent.stt.ModelManager
import top.hsyscn.opedrgent.stt.ModelType
import top.hsyscn.opedrgent.utils.CrashReporter
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本地备份/恢复核心（全离线，不触网）。
 *
 * 归档为标准 zip：
 *  - manifest.json          : formatVersion / appVersionCode / appVersionName / createdAt / includeModels / entries
 *  - databases/<原名>.db3    : 每个数据库物理文件（wal_checkpoint(FULL) 后仅主库）
 *  - preferences/settings.json: 明文偏好 opedrgent_settings（已剔除敏感键，绝不读取 opedrgent_secure）
 *  - models/...             : 仅 includeModels=true 时追加（只读访问器复用，不触发下载）
 *
 * 设计约束见冻结 API，签名不得改动。
 */
data class BackupEntry(
    val path: String,
    val sizeBytes: Long,
    val sha256: String,
    val component: String,
)

data class BackupManifest(
    val formatVersion: Int,
    val appVersionCode: Int,
    val appVersionName: String,
    val createdAt: Long,
    val includeModels: Boolean,
    val entries: List<BackupEntry>,
)

data class BackupResult(
    val ok: Boolean,
    val archivePath: String,
    val sizeBytes: Long,
    val components: List<String>,
    val message: String = "",
)

enum class RestoreCode {
    OK,
    CORRUPTED,
    UNSUPPORTED_VERSION,
    SHA_MISMATCH,
    INSUFFICIENT_STORAGE,
    IO_ERROR,
}

data class RestoreResult(
    val ok: Boolean,
    val code: RestoreCode,
    val message: String,
    val restoredDatabases: List<String>,
    val restoredPrefKeys: Int,
    val requiresRestart: Boolean,
)

class LocalBackupManager(private val appContext: Context) {

    private val context: Context get() = appContext.applicationContext

    suspend fun backupTo(
        out: OutputStream,
        includeModels: Boolean = false,
        progress: (Float) -> Unit = {},
    ): BackupManifest = withContext(Dispatchers.IO) {
        val entries = mutableListOf<BackupEntry>()
        val components = linkedSetOf<String>()
        val created = System.currentTimeMillis()
        val (vc, vn) = appVersion()

        // 最外层兜底：写入/SAF 流拷贝异常先记录（技术信息），再上抛给调用方决定如何清理半成品流
        try {
        ZipOutputStream(out).use { zip ->
            // ---- 1. 数据库 ----
            val dbNames = context.databaseList().filter { it.endsWith(".db") }
            for (name in dbNames) {
                val dbFile = dbFile(name)
                if (!dbFile.exists()) continue
                checkpointWal(dbFile)
                val archiveName = "databases/" + name.removeSuffix(".db") + ".db3"
                val entry = putFileEntry(zip, archiveName, dbFile, "database") { copied, total ->
                    if (total > 0) progress(copied.toFloat() / total.toFloat() * 0.9f)
                }
                entries += entry
                components += "database:$name"
            }

            // ---- 2. 设置（明文偏好，剔除敏感键，绝不读取 secure）----
            val settingsJson = exportSettingsJson()
            val settingsBytes = settingsJson.toString().toByteArray(Charsets.UTF_8)
            val settingsEntry = putBytesEntry(zip, "preferences/settings.json", settingsBytes, "preferences")
            entries += settingsEntry
            components += "preferences"

            // ---- 3. 模型（可选，只读复用，不下载）----
            if (includeModels) {
                addModels(zip, entries, components)
            }

            // ---- manifest ----
            val manifest = BackupManifest(
                formatVersion = FORMAT_VERSION,
                appVersionCode = vc,
                appVersionName = vn,
                createdAt = created,
                includeModels = includeModels,
                entries = entries,
            )
            val manifestBytes = manifestToJson(manifest).toString().toByteArray(Charsets.UTF_8)
            putBytesEntry(zip, MANIFEST_ENTRY, manifestBytes, "manifest")
            progress(1f)
            manifest
        }
        } catch (e: Exception) {
            CrashReporter.logError(TAG, "backupTo write failed", e)
            throw e
        }
    }

    suspend fun createLocal(includeModels: Boolean = false): BackupResult = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, BACKUP_DIR)
        val file = File(dir, "opedrgent_backup_${System.currentTimeMillis()}.zip")
        runCatching {
            dir.mkdirs()
            val manifest = file.outputStream().use { backupTo(it, includeModels) { } }
            BackupResult(
                ok = true,
                archivePath = file.absolutePath,
                sizeBytes = file.length(),
                components = manifest.entries.map { it.component }.distinct(),
            )
        }.getOrElse { e ->
            // 技术信息记录（不含用户内容/密钥），再清理半成品归档，避免损坏文件被误当有效备份
            CrashReporter.logError(TAG, "createLocal failed", e)
            DebugLog.e(TAG, "createLocal failed: ${e.message}", e)
            runCatching { if (file.exists()) file.delete() }
            BackupResult(false, "", 0, emptyList(), e.message ?: "备份失败")
        }
    }

    /**
     * 仅读取归档中的 manifest.json。损坏/版本不符时抛出异常（由调用方兜底）。
     */
    fun inspect(input: InputStream): BackupManifest {
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == MANIFEST_ENTRY) {
                    val text = zip.readBytes().toString(Charsets.UTF_8)
                    return manifestFromJson(JSONObject(text))
                }
                entry = zip.nextEntry
            }
        }
        throw java.io.IOException("归档中缺少 manifest.json")
    }

    suspend fun restoreFrom(
        input: InputStream,
        progress: (Float) -> Unit = {},
        allowDowngrade: Boolean = false,
    ): RestoreResult = withContext(Dispatchers.IO) {
        // 1. 落到临时 zip（随机读）
        val tempZip = File(context.cacheDir, "restore_${System.currentTimeMillis()}.zip")
        runCatching { tempZip.outputStream().use { os -> input.copyTo(os) } }.onFailure {
            tempZip.delete()
            return@withContext RestoreResult(false, RestoreCode.IO_ERROR, "无法读取归档: ${it.message}", emptyList(), 0, false)
        }

        // 2. 解析 + 版本校验
        val manifest = try {
            ZipFile(tempZip).use { zf ->
                val e = zf.getEntry(MANIFEST_ENTRY)
                    ?: throw java.io.IOException("缺少 manifest.json")
                manifestFromJson(JSONObject(zf.getInputStream(e).bufferedReader().use { it.readText() }))
            }
        } catch (e: Exception) {
            tempZip.delete()
            return@withContext RestoreResult(false, RestoreCode.CORRUPTED, "归档损坏: ${e.message}", emptyList(), 0, false)
        }

        if (manifest.formatVersion != FORMAT_VERSION) {
            tempZip.delete()
            return@withContext RestoreResult(
                false, RestoreCode.UNSUPPORTED_VERSION,
                "不支持的归档版本: ${manifest.formatVersion}（当前 $FORMAT_VERSION）",
                emptyList(), 0, false,
            )
        }
        val (curVc, _) = appVersion()
        if (!allowDowngrade && manifest.appVersionCode < curVc) {
            tempZip.delete()
            return@withContext RestoreResult(
                false, RestoreCode.UNSUPPORTED_VERSION,
                "备份来自旧版本 app(${manifest.appVersionCode})，需 allowDowngrade=true 强制恢复",
                emptyList(), 0, false,
            )
        }

        // 3. 逐条校验大小 + sha256
        val verifyErrors = verifyEntries(tempZip, manifest)
        if (verifyErrors != null) {
            tempZip.delete()
            return@withContext verifyErrors
        }

        // 4. 预估剩余空间
        val needed = manifest.entries
            .filter { it.path.startsWith("databases/") || it.path == "preferences/settings.json" }
            .sumOf { it.sizeBytes }
        val free = runCatching { StatFs(context.filesDir.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)
        if (needed > free) {
            tempZip.delete()
            return@withContext RestoreResult(
                false, RestoreCode.INSUFFICIENT_STORAGE,
                "剩余空间不足：需要 ${needed / 1024 / 1024}MB，可用 ${free / 1024 / 1024}MB",
                emptyList(), 0, false,
            )
        }

        // 5. 全过，开始动手：先快照回滚点
        val rollbackDir = File(context.cacheDir, "rollback_${System.currentTimeMillis()}")
        rollbackDir.mkdirs()
        val rollbackOk = snapshotCurrent(rollbackDir)

        var result: RestoreResult
        try {
            result = applyRestore(tempZip, manifest, progress)
        } catch (e: Exception) {
            CrashReporter.logError(TAG, "restore apply failed, rolling back", e)
            DebugLog.e(TAG, "restore apply failed, rolling back: ${e.message}", e)
            if (rollbackOk) restoreSnapshot(rollbackDir)
            result = RestoreResult(false, RestoreCode.IO_ERROR, "恢复失败已回滚: ${e.message}", emptyList(), 0, false)
        }

        tempZip.delete()
        rollbackDir.deleteRecursively()
        result
    }

    // ===================== 内部实现 =====================

    private fun appVersion(): Pair<Int, String> {
        return runCatching {
            val pm = context.packageManager
            val pi = pm.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pi.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION") pi.versionCode
            }
            code to (pi.versionName ?: "")
        }.getOrDefault(0 to "")
    }

    private fun dbFile(name: String): File = context.getDatabasePath(name)

    /** 对主库做 WAL checkpoint，尽量把 -wal 合并进主库，失败兜底。 */
    private fun checkpointWal(dbFile: File) {
        runCatching {
            SQLiteDatabase.openDatabase(
                dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE,
            ).use { db ->
                db.execSQL("PRAGMA wal_checkpoint(FULL)")
            }
        }
    }

    private fun exportSettingsJson(): JSONObject {
        val prefs = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        val out = JSONObject()
        prefs.all.forEach { (k, v) ->
            if (isSensitiveKey(k)) return@forEach
            when (v) {
                is Boolean -> out.put(k, v)
                is Int -> out.put(k, v)
                is Long -> out.put(k, v)
                is Float -> out.put(k, v)
                is Double -> out.put(k, v)
                is String -> out.put(k, v)
                else -> v?.let { out.put(k, it.toString()) }
            }
        }
        return out
    }

    private fun isSensitiveKey(key: String): Boolean {
        val lower = key.lowercase()
        return SENSITIVE_HITS.any { lower.contains(it) }
    }

    private fun putFileEntry(
        zip: ZipOutputStream,
        archiveName: String,
        file: File,
        component: String,
        onProgress: (Long, Long) -> Unit,
    ): BackupEntry {
        val digest = MessageDigest.getInstance("SHA-256")
        zip.putNextEntry(ZipEntry(archiveName))
        var total = 0L
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val r = input.read(buf)
                if (r == -1) break
                zip.write(buf, 0, r)
                digest.update(buf, 0, r)
                total += r
                onProgress(total, file.length())
            }
        }
        zip.closeEntry()
        return BackupEntry(
            path = archiveName,
            sizeBytes = total,
            sha256 = digest.digest().toHex(),
            component = component,
        )
    }

    private fun putBytesEntry(
        zip: ZipOutputStream,
        archiveName: String,
        bytes: ByteArray,
        component: String,
    ): BackupEntry {
        zip.putNextEntry(ZipEntry(archiveName))
        zip.write(bytes)
        zip.closeEntry()
        return BackupEntry(
            path = archiveName,
            sizeBytes = bytes.size.toLong(),
            sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).toHex(),
            component = component,
        )
    }

    private fun addModels(
        zip: ZipOutputStream,
        entries: MutableList<BackupEntry>,
        components: MutableSet<String>,
    ) {
        // LLM 模型（只读复用 ModelDownloadManager，不触发下载）
        runCatching {
            val mgr = ModelDownloadManager.getInstance(context)
            mgr.getDownloadedModels().forEach { info ->
                val f = mgr.getModelFile(info.id) ?: return@forEach
                if (!f.exists()) return@forEach
                entries += putFileEntry(zip, "models/llm/${f.name}", f, "model") { _, _ -> }
            }
        }.onFailure { DebugLog.w(TAG, "备份 LLM 模型跳过: ${it.message}") }

        // STT 模型（object ModelManager.getModelPath 返回目录）
        runCatching {
            ModelType.entries.forEach { type ->
                val dir = ModelManager.getModelPath(context, type) ?: return@forEach
                if (!dir.exists()) return@forEach
                dir.walkTopDown().filter { it.isFile }.forEach { f ->
                    val rel = "models/stt/${type.name}/${f.relativeTo(dir).path.replace("\\", "/")}"
                    entries += putFileEntry(zip, rel, f, "model") { _, _ -> }
                }
            }
        }.onFailure { DebugLog.w(TAG, "备份 STT 模型跳过: ${it.message}") }

        if (entries.any { it.component == "model" }) components += "models"
    }

    private fun verifyEntries(zip: File, manifest: BackupManifest): RestoreResult? {
        ZipFile(zip).use { zf ->
            for (entry in manifest.entries) {
                val ze = zf.getEntry(entry.path)
                    ?: return RestoreResult(
                        false, RestoreCode.CORRUPTED,
                        "归档缺少条目: ${entry.path}", emptyList(), 0, false,
                    )
                if (ze.size != entry.sizeBytes) {
                    return RestoreResult(
                        false, RestoreCode.CORRUPTED,
                        "条目大小不符: ${entry.path}", emptyList(), 0, false,
                    )
                }
                val digest = MessageDigest.getInstance("SHA-256")
                zf.getInputStream(ze).use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val r = input.read(buf)
                        if (r == -1) break
                        digest.update(buf, 0, r)
                    }
                }
                val actual = digest.digest().toHex()
                if (!actual.equals(entry.sha256, ignoreCase = true)) {
                    return RestoreResult(
                        false, RestoreCode.SHA_MISMATCH,
                        "条目校验和不符: ${entry.path}", emptyList(), 0, false,
                    )
                }
            }
        }
        return null
    }

    private fun snapshotCurrent(rollbackDir: File): Boolean {
        return runCatching {
            val dbSnapshot = File(rollbackDir, "databases")
            dbSnapshot.mkdirs()
            context.databaseList().filter { it.endsWith(".db") }.forEach { name ->
                val src = dbFile(name)
                if (src.exists()) src.copyTo(File(dbSnapshot, name), overwrite = true)
            }
            val settingsJson = exportSettingsJson()
            File(rollbackDir, "settings.json").writeText(
                settingsJson.toString(), Charsets.UTF_8,
            )
            true
        }.getOrElse {
            DebugLog.e(TAG, "快照失败: ${it.message}", it)
            false
        }
    }

    private fun restoreSnapshot(rollbackDir: File) {
        runCatching {
            File(rollbackDir, "databases").listFiles()?.forEach { f ->
                val target = dbFile(f.name)
                f.copyTo(target, overwrite = true)
                File(target.parentFile, f.name + "-wal").delete()
                File(target.parentFile, f.name + "-shm").delete()
            }
            File(rollbackDir, "settings.json").takeIf { it.exists() }?.let {
                writeSettingsFromJson(JSONObject(it.readText()))
            }
        }
    }

    private fun applyRestore(
        zip: File,
        manifest: BackupManifest,
        progress: (Float) -> Unit,
    ): RestoreResult {
        // 先关闭所有 holder 单例，释放对库文件的句柄
        closeAllHolders()

        val restored = mutableListOf<String>()
        var prefKeys = 0

        ZipFile(zip).use { zf ->
            val total = manifest.entries.size.coerceAtLeast(1)
            var done = 0
            for (entry in manifest.entries) {
                val ze = zf.getEntry(entry.path) ?: continue
                when {
                    entry.path.startsWith("databases/") -> {
                        val dbName = entry.path.removePrefix("databases/")
                            .removeSuffix(".db3") + ".db"
                        val target = dbFile(dbName)
                        // Zip Slip 防护：canonical 化后必须仍位于 app 私有 databases 根目录内，
                        // 拒绝 ../ 穿越 / 符号链接逃逸；不满足直接抛错使整体恢复失败并回滚。
                        val dbRoot = target.parentFile
                            ?: throw java.io.IOException("无法定位 databases 目录")
                        val canonicalRoot = dbRoot.canonicalFile
                        val canonicalTarget = try {
                            target.canonicalFile
                        } catch (e: Exception) {
                            target.absoluteFile
                        }
                        if (!canonicalTarget.path.startsWith(canonicalRoot.path + File.separator)) {
                            throw java.io.IOException("非法归档路径(穿越): ${entry.path}")
                        }
                        File(target.parentFile, "$dbName-wal").delete()
                        File(target.parentFile, "$dbName-shm").delete()
                        target.parentFile?.mkdirs()
                        zf.getInputStream(ze).use { osIn ->
                            target.outputStream().use { osOut -> osIn.copyTo(osOut) }
                        }
                        restored += dbName
                    }
                    entry.path == "preferences/settings.json" -> {
                        val text = zf.getInputStream(ze).bufferedReader().use { it.readText() }
                        prefKeys = writeSettingsFromJson(JSONObject(text))
                    }
                    // models/... 本次恢复不落地（可重新下载，体积大）
                }
                done++
                progress(done.toFloat() / total.toFloat())
            }
        }

        // 替换完成后再次复位 holder，使下次访问用新库
        closeAllHolders()

        return RestoreResult(
            ok = true,
            code = RestoreCode.OK,
            message = "成功恢复 ${restored.size} 个数据库、$prefKeys 项设置，建议重启应用",
            restoredDatabases = restored,
            restoredPrefKeys = prefKeys,
            requiresRestart = true,
        )
    }

    private fun closeAllHolders() {
        runCatching { top.hsyscn.opedrgent.action.ActionDatabase.closeAndReset() }
        runCatching { top.hsyscn.opedrgent.cultivation.store.CultivationDatabase.closeAndReset() }
        runCatching { top.hsyscn.opedrgent.note.FolderDatabase.closeAndReset() }
        runCatching { top.hsyscn.opedrgent.note.NoteDatabase.closeAndReset() }
        runCatching { top.hsyscn.opedrgent.note.KnowledgeGraphDatabase.closeAndReset() }
        runCatching { top.hsyscn.opedrgent.storage.HippocampusDatabase.closeAndReset() }
        runCatching { top.hsyscn.opedrgent.storage.KbDatabase.closeAndReset() }
        runCatching { top.hsyscn.opedrgent.storage.SproutReportDatabase.closeAndReset() }
        runCatching { top.hsyscn.opedrgent.cultivation.store.GrowthReviewDatabase.closeAndReset() }
    }

    private fun writeSettingsFromJson(json: JSONObject): Int {
        val prefs = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        val ed = prefs.edit()
        var count = 0
        json.keys().forEach { k ->
            if (isSensitiveKey(k)) return@forEach
            when (val v = json.get(k)) {
                is Boolean -> ed.putBoolean(k, v)
                is Int -> ed.putInt(k, v)
                is Long -> ed.putLong(k, v)
                is Float -> ed.putFloat(k, v)
                is Double -> ed.putFloat(k, v.toFloat())
                is String -> ed.putString(k, v)
                else -> ed.putString(k, v.toString())
            }
            count++
        }
        ed.apply()
        return count
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    private fun manifestToJson(m: BackupManifest): JSONObject = JSONObject().apply {
        put("formatVersion", m.formatVersion)
        put("appVersionCode", m.appVersionCode)
        put("appVersionName", m.appVersionName)
        put("createdAt", m.createdAt)
        put("includeModels", m.includeModels)
        val arr = JSONArray()
        m.entries.forEach { e ->
            arr.put(JSONObject().apply {
                put("path", e.path)
                put("sizeBytes", e.sizeBytes)
                put("sha256", e.sha256)
                put("component", e.component)
            })
        }
        put("entries", arr)
    }

    private fun manifestFromJson(o: JSONObject): BackupManifest {
        val arr = o.optJSONArray("entries") ?: JSONArray()
        val entries = mutableListOf<BackupEntry>()
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            entries += BackupEntry(
                path = e.getString("path"),
                sizeBytes = e.optLong("sizeBytes", 0L),
                sha256 = e.optString("sha256", ""),
                component = e.optString("component", ""),
            )
        }
        return BackupManifest(
            formatVersion = o.getInt("formatVersion"),
            appVersionCode = o.optInt("appVersionCode", 0),
            appVersionName = o.optString("appVersionName", ""),
            createdAt = o.optLong("createdAt", 0L),
            includeModels = o.optBoolean("includeModels", false),
            entries = entries,
        )
    }

    companion object {
        const val FORMAT_VERSION = 1
        private const val TAG = "LocalBackupManager"
        private const val MANIFEST_ENTRY = "manifest.json"
        private const val BACKUP_DIR = "backups"
        private const val SETTINGS_PREFS = "opedrgent_settings"
        private val SENSITIVE_HITS =
            listOf("key", "token", "secret", "password", "credential")

        fun getInstance(context: Context): LocalBackupManager =
            LocalBackupManager(context.applicationContext)
    }
}
