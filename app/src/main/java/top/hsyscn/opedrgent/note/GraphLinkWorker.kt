package top.hsyscn.opedrgent.note

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import top.hsyscn.opedrgent.settings.ApiSettings
import top.hsyscn.opedrgent.utils.DebugLog

/**
 * 知识图谱建边 Worker。
 *
 * 在笔记保存后，通过 WorkManager 提交持久化任务，在后台调用 [KnowledgeGraph.linkNote]
 * 计算并保存笔记间的关联关系。即使 App 被系统回收，任务仍可在后台恢复执行。
 */
class GraphLinkWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "GraphLinkWorker"
        private const val WORK_NAME_PREFIX = "graph_link_"
        private const val KEY_NOTE_ID = "note_id"

        /**
         * 提交一条笔记的建边任务。
         *
         * 只传递 [noteId]，不传递内容。WorkManager 的 [androidx.work.Data] 有 10KB 大小限制，
         * 大笔记内容直接序列化会触发 `Data cannot occupy more than 10240 bytes` 崩溃。
         * Worker 内部通过 [NoteDatabase] 重新读取笔记内容。
         *
         * 使用 [ExistingWorkPolicy.KEEP] 避免连续保存同一笔记时产生重复任务。
         */
        fun enqueue(context: Context, noteId: Long) {
            val input = workDataOf(
                KEY_NOTE_ID to noteId.toString(),
            )
            val request = OneTimeWorkRequestBuilder<GraphLinkWorker>()
                .setInputData(input)
                .addTag("graph_link")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "$WORK_NAME_PREFIX$noteId",
                ExistingWorkPolicy.KEEP,
                request,
            )
            DebugLog.d(TAG, "enqueued link work for note $noteId")
        }
    }

    override suspend fun doWork(): Result {
        val noteIdStr = inputData.getString(KEY_NOTE_ID)
        if (noteIdStr.isNullOrBlank()) {
            return Result.failure()
        }
        val noteId = noteIdStr.toLongOrNull() ?: return Result.failure()

        return try {
            val ctx = applicationContext
            val note = NoteDao(NoteDatabase.getInstance(ctx)).getById(noteId)
            if (note == null) {
                DebugLog.w(TAG, "note $noteId not found, skip linking")
                return Result.failure()
            }
            val content = buildString {
                if (note.title.isNotBlank()) append(note.title).append(" ")
                append(note.content)
            }
            if (content.isBlank()) {
                DebugLog.w(TAG, "note $noteId has blank content, skip linking")
                return Result.success()
            }
            val graphStore = KnowledgeGraphStore(ctx)
            val apiSettings = ApiSettings(ctx)
            val provider = EmbeddingProviderFactory.create(ctx, apiSettings, graphStore)
            val knowledgeGraph = KnowledgeGraph(ctx, graphStore, provider)
            knowledgeGraph.linkNote(noteIdStr, content)
            DebugLog.d(TAG, "link success for note $noteId")
            Result.success()
        } catch (e: Exception) {
            DebugLog.e(TAG, "link failed for note $noteId: ${e.message}", e)
            // 允许 WorkManager 按默认退避策略重试
            Result.retry()
        }
    }
}
