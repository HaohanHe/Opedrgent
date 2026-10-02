package top.hsyscn.opedrgent.storage

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import top.hsyscn.opedrgent.cultivation.store.ReflectionStore
import top.hsyscn.opedrgent.llm.AvailableLocalModels
import top.hsyscn.opedrgent.llm.ModelDownloadManager
import top.hsyscn.opedrgent.note.FolderDatabase
import top.hsyscn.opedrgent.note.KnowledgeGraphStore
import top.hsyscn.opedrgent.note.NoteDatabase
import top.hsyscn.opedrgent.stt.ModelManager
import java.io.File

/** 用户数据分类（彻底删除编排的冻结契约，UI 按此接线，不得改名）。 */
enum class DataCategory { NOTES, REFLECTIONS, RECORDINGS, DOWNLOADED_MODELS, MEMORY_INDEX }

/** 删除结果：实际清掉的分类、释放字节数、逐项错误（单项失败不阻断其余）。 */
data class DeletionResult(
    val deleted: Set<DataCategory>,
    val freedBytes: Long,
    val errors: List<String>,
)

/**
 * 本地用户数据彻底删除编排器。
 *
 * 只做"调用现有删除能力 + 补齐残留"，不重写各 Store 的删除实现；删除即本地不可恢复。
 * 各分类内部逐子步骤 runCatching，单项失败记入 errors 并继续其余步骤。
 *
 * 注意：必须在后台线程调用（内部 runBlocking 执行 suspend 存储操作），切勿在主线程调用，
 * 否则会阻塞主线程。释放字节数仅统计基于文件/目录的删除；数据库行级物理清除不报告字节数。
 */
class LocalDataDeleter(private val context: Context) {

    /** 删除单个分类。该分类的主存储清除未抛异常即视为 deleted。 */
    fun deleteCategory(category: DataCategory): DeletionResult {
        val errors = mutableListOf<String>()
        var freed = 0L
        var deleted = false
        runCatching { freed = perform(category, errors) }
            .onSuccess { deleted = true }
            .onFailure { errors += "${category.name}: ${it.message}" }
        return DeletionResult(
            deleted = if (deleted) setOf(category) else emptySet(),
            freedBytes = freed,
            errors = errors,
        )
    }

    /** 依次清除全部用户数据并汇总：某分类失败不阻断其余分类。 */
    fun deleteAllUserData(): DeletionResult {
        val deleted = mutableSetOf<DataCategory>()
        var freed = 0L
        val errors = mutableListOf<String>()
        for (category in DataCategory.values()) {
            runCatching { freed += perform(category, errors) }
                .onSuccess { deleted += category }
                .onFailure { errors += "${category.name}: ${it.message}" }
        }
        return DeletionResult(deleted = deleted, freedBytes = freed, errors = errors)
    }

    // ==================== 分类执行体 ====================

    /** 统一入口：在 IO 上执行某分类，返回该分类释放的文件字节数。 */
    private fun perform(category: DataCategory, errors: MutableList<String>): Long =
        runBlocking(Dispatchers.IO) {
            when (category) {
                DataCategory.NOTES -> deleteNotes(errors)
                DataCategory.REFLECTIONS -> { deleteReflections(errors); 0L }
                DataCategory.RECORDINGS -> deleteRecordings(errors)
                DataCategory.DOWNLOADED_MODELS -> deleteDownloadedModels(errors)
                DataCategory.MEMORY_INDEX -> { hippocampus().clearAll(); 0L }
            }
        }

    /** NOTES：笔记/文件夹数据库 + 知识图谱 + 关联海马索引 + 导出残留。 */
    private suspend fun deleteNotes(errors: MutableList<String>): Long {
        // 1) 笔记库与文件夹库物理清空：复用单例已打开连接，软删记录一并物理抹除，不可恢复。
        runCatching {
            NoteDatabase.getInstance(context).writableDatabase.delete(NoteDatabase.TABLE_NOTES, null, null)
        }.onFailure { errors += "NOTES/notes-db: ${it.message}" }
        runCatching {
            FolderDatabase.getInstance(context).writableDatabase.delete(FolderDatabase.TABLE_FOLDERS, null, null)
        }.onFailure { errors += "NOTES/folders-db: ${it.message}" }
        // 2) 知识图谱（节点/边/向量）复用既有清空。
        runCatching { KnowledgeGraphStore(context).clearAll() }
            .onFailure { errors += "NOTES/graph: ${it.message}" }
        // 3) 关联海马索引：笔记项 + 由笔记派生的洞察项。
        runCatching {
            hippocampus().deleteAllByType(SourceType.NOTE)
            hippocampus().deleteAllByType(SourceType.SPROUT)
        }.onFailure { errors += "NOTES/hippocampus: ${it.message}" }
        // 4) 导出残留（应用私有 Documents）。
        return runCatching { purgeNoteExports() }
            .onFailure { errors += "NOTES/exports: ${it.message}" }
            .getOrDefault(0L)
    }

    /** REFLECTIONS：ReflectionStore.clearAll + 海马 deleteAllCultivation（同 CultivationStateManager 口径）。 */
    private suspend fun deleteReflections(errors: MutableList<String>) {
        runCatching { ReflectionStore(context).clearAll() }
            .onFailure { errors += "REFLECTIONS/store: ${it.message}" }
        runCatching { hippocampus().deleteAllCultivation() }
            .onFailure { errors += "REFLECTIONS/hippocampus: ${it.message}" }
    }

    /** RECORDINGS：录音/会议临时音频文件 + 海马录音索引。 */
    private suspend fun deleteRecordings(errors: MutableList<String>): Long {
        val freed = runCatching { purgeRecordingCache() }
            .onFailure { errors += "RECORDINGS/audio: ${it.message}" }
            .getOrDefault(0L)
        runCatching { hippocampus().deleteAllByType(SourceType.RECORDING) }
            .onFailure { errors += "RECORDINGS/index: ${it.message}" }
        return freed
    }

    /** DOWNLOADED_MODELS：逐个删除本地 LLM 模型（含 .tmp）+ 清空 STT 模型目录。 */
    private fun deleteDownloadedModels(errors: MutableList<String>): Long {
        var freed = 0L
        val mdm = ModelDownloadManager(context)
        for (model in AvailableLocalModels.MODELS) {
            runCatching {
                freed += (mdm.getModelFile(model.id)?.length() ?: 0L) + mdm.getPartialBytes(model.id)
                mdm.deleteModel(model.id)
            }.onFailure { errors += "MODEL/${model.id}: ${it.message}" }
        }
        // STT 模型目录整体清空（clearModelCache 传入 null = 全部方言/声学模型）。
        runCatching {
            freed += File(context.filesDir, "stt_models").sizeRecursively()
            ModelManager.clearModelCache(context, null)
        }.onFailure { errors += "MODEL/stt: ${it.message}" }
        return freed
    }

    // ==================== 残留清理工具 ====================

    private fun hippocampus(): HippocampusIndex = HippocampusIndex(context)

    private fun File.sizeRecursively(): Long =
        walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** 删除笔记导出残留：opedrgent-export-* 归档目录，以及外部 Documents 根目录的单篇 .txt/.md。 */
    private fun purgeNoteExports(): Long {
        val externalDocs = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        var freed = 0L
        // 归档目录：外部 Documents 与内部 filesDir 回退路径都扫。
        listOfNotNull(externalDocs, context.filesDir).toSet().forEach { base ->
            base.listFiles()?.forEach { entry ->
                if (entry.isDirectory && entry.name.startsWith("opedrgent-export-")) {
                    freed += entry.sizeRecursively()
                    entry.deleteRecursively()
                }
            }
        }
        // 单篇导出 .txt/.md 只在外部 Documents 删除，内部 filesDir 根目录不扫以免误删其它文件。
        externalDocs?.listFiles()?.forEach { f ->
            if (f.isFile && (f.name.endsWith(".txt") || f.name.endsWith(".md"))) {
                freed += f.length()
                f.delete()
            }
        }
        return freed
    }

    /** 删除 cacheDir 下录音/会议临时音频（recording_* / meeting_* 的 .pcm/.wav/.m4a）。 */
    private fun purgeRecordingCache(): Long {
        val cache = context.cacheDir
        if (!cache.exists()) return 0L
        var freed = 0L
        cache.listFiles()?.forEach { f ->
            val name = f.name
            val isAudio = name.endsWith(".pcm") || name.endsWith(".wav") || name.endsWith(".m4a")
            if (f.isFile && isAudio &&
                (name.startsWith("recording_") || name.startsWith("meeting_"))
            ) {
                freed += f.length()
                f.delete()
            }
        }
        return freed
    }

    companion object {
        @Volatile
        private var instance: LocalDataDeleter? = null

        fun getInstance(context: Context): LocalDataDeleter =
            instance ?: synchronized(this) {
                instance ?: LocalDataDeleter(context.applicationContext).also { instance = it }
            }
    }
}
