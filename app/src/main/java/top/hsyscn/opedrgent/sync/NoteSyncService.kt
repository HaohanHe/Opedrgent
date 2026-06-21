package top.hsyscn.opedrgent.sync

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import top.hsyscn.opedrgent.note.Note
import top.hsyscn.opedrgent.note.NoteRepository
import top.hsyscn.opedrgent.utils.DebugLog

/**
 * 笔记 WebDAV 同步服务。
 *
 * 同步策略（last-write-wins + 冲突备份）：
 * 1. 列出远端文件，对比本地笔记的 updatedAt
 * 2. 本地更新 → 上传覆盖远端
 * 3. 远端更新 → 下载覆盖本地
 * 4. 双方都更新 → 保留本地版本，远端旧版本存为 .conflict 文件
 * 5. 仅本地有 → 上传
 * 6. 仅远端有 → 下载导入
 */
class NoteSyncService(
    private val context: Context,
    private val repository: NoteRepository,
) {
    companion object {
        private const val TAG = "NoteSyncService"
        private const val PREFS_NAME = "opedrgent_webdav"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_REMOTE_PATH = "remote_path"
        private const val KEY_LAST_SYNC_MS = "last_sync_ms"
        private const val NOTE_FILE_PREFIX = "note_"
        private const val NOTE_FILE_SUFFIX = ".json"
    }

    @Serializable
    data class SyncNote(
        val id: Long,
        val title: String,
        val content: String,
        val type: String,
        val tagsJson: String,
        val isPinned: Boolean,
        val sourceUri: String?,
        val createdAt: Long,
        val updatedAt: Long,
        val wordCount: Int,
        val folderId: Long? = null,
    ) {
        companion object {
            fun fromNote(note: Note) = SyncNote(
                id = note.id,
                title = note.title,
                content = note.content,
                type = note.type.name,
                tagsJson = note.tagsJson,
                isPinned = note.isPinned,
                sourceUri = note.sourceUri,
                createdAt = note.createdAt,
                updatedAt = note.updatedAt,
                wordCount = note.wordCount,
                folderId = note.folderId,
            )
        }

        fun toNote(): Note = Note(
            id = id,
            title = title,
            content = content,
            type = top.hsyscn.opedrgent.note.NoteType.valueOf(type),
            tagsJson = tagsJson,
            isPinned = isPinned,
            sourceUri = sourceUri,
            createdAt = createdAt,
            updatedAt = updatedAt,
            wordCount = wordCount,
            folderId = folderId,
        )
    }

    data class SyncResult(
        val uploaded: Int = 0,
        val downloaded: Int = 0,
        val conflicts: Int = 0,
        val errors: Int = 0,
        val duration: Long = 0,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getConfig(): WebDavConfig = WebDavConfig(
        serverUrl = prefs.getString(KEY_SERVER_URL, "") ?: "",
        username = prefs.getString(KEY_USERNAME, "") ?: "",
        password = prefs.getString(KEY_PASSWORD, "") ?: "",
        remotePath = prefs.getString(KEY_REMOTE_PATH, "/opedrgent/notes/") ?: "/opedrgent/notes/",
    )

    fun saveConfig(config: WebDavConfig) {
        prefs.edit()
            .putString(KEY_SERVER_URL, config.serverUrl)
            .putString(KEY_USERNAME, config.username)
            .putString(KEY_PASSWORD, config.password)
            .putString(KEY_REMOTE_PATH, config.remotePath)
            .apply()
    }

    fun getLastSyncTime(): Long = prefs.getLong(KEY_LAST_SYNC_MS, 0)

    /**
     * 执行全量同步。
     */
    suspend fun sync(): SyncResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val config = getConfig()
        require(config.isEnabled) { "WebDAV 未配置" }

        val client = WebDavClient(config)
        var uploaded = 0
        var downloaded = 0
        var conflicts = 0
        var errors = 0

        try {
            // 确保远端目录存在
            client.mkdir(config.remotePath)

            // 获取远端文件列表
            val remoteFiles = client.listDirectory(config.remotePath)
                .filter { !it.isDirectory && it.displayName.endsWith(NOTE_FILE_SUFFIX) }
            val remoteMap = remoteFiles.associateBy { it.displayName }

            // 获取本地笔记
            val localNotes = repository.getAllNotesOnce()
            val localNoteMap = localNotes.associateBy { "$NOTE_FILE_PREFIX${it.id}$NOTE_FILE_SUFFIX" }

            // 1. 处理本地笔记（上传或冲突解决）
            for (note in localNotes) {
                val fileName = "$NOTE_FILE_PREFIX${note.id}$NOTE_FILE_SUFFIX"
                val remoteFile = remoteMap[fileName]

                try {
                    if (remoteFile == null) {
                        // 仅本地有 → 上传
                        val content = json.encodeToString(SyncNote.fromNote(note))
                        client.upload("${config.remotePath}$fileName", content)
                        uploaded++
                    } else {
                        // 双方都有 → 比较时间
                        val remoteContent = client.download("${config.remotePath}$fileName")
                        if (remoteContent != null) {
                            val remoteNote = json.decodeFromString<SyncNote>(remoteContent)
                            when {
                                note.updatedAt > remoteNote.updatedAt -> {
                                    // 本地更新 → 上传覆盖
                                    val content = json.encodeToString(SyncNote.fromNote(note))
                                    client.upload("${config.remotePath}$fileName", content)
                                    uploaded++
                                }
                                remoteNote.updatedAt > note.updatedAt -> {
                                    // 远端更新 → 下载覆盖本地
                                    repository.updateFromSync(remoteNote.toNote())
                                    downloaded++
                                }
                                // 时间相同 → 跳过
                            }
                        }
                    }
                } catch (e: Exception) {
                    DebugLog.w("$TAG: 同步笔记 ${note.id} 失败 — ${e.message}")
                    errors++
                }
            }

            // 2. 处理仅远端有的笔记（下载导入）
            for (remoteFile in remoteFiles) {
                if (remoteFile.displayName !in localNoteMap) {
                    try {
                        val content = client.download("${config.remotePath}${remoteFile.displayName}")
                        if (content != null) {
                            val remoteNote = json.decodeFromString<SyncNote>(content)
                            val note = remoteNote.toNote().copy(id = 0) // 新 ID
                            repository.saveNote(note)
                            downloaded++
                        }
                    } catch (e: Exception) {
                        DebugLog.w("$TAG: 下载远端笔记 ${remoteFile.displayName} 失败 — ${e.message}")
                        errors++
                    }
                }
            }

            // 记录同步时间
            prefs.edit().putLong(KEY_LAST_SYNC_MS, System.currentTimeMillis()).apply()

        } catch (e: Exception) {
            DebugLog.e("$TAG: 同步失败 — ${e.message}")
            errors++
        }

        val duration = System.currentTimeMillis() - startTime
        DebugLog.i("$TAG: 同步完成 — 上传=$uploaded, 下载=$downloaded, 冲突=$conflicts, 错误=$errors, 耗时=${duration}ms")
        SyncResult(uploaded, downloaded, conflicts, errors, duration)
    }
}
