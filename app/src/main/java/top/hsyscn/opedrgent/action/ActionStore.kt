package top.hsyscn.opedrgent.action

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.cultivation.model.FollowUp

/**
 * 行动项存储：全本地读写，不做任何上传。
 *
 * 幂等约定：[importFromReflection] 对同一 [sourceReflectionId] 重复调用不会产生重复行动项——
 * 按 (sourceReflectionId, title) 去重，标题已存在的跟进直接跳过。
 */
class ActionStore private constructor(context: Context) {

    private val db = ActionDatabase.getInstance(context).writableDatabase

    /** 全部行动项，新创建在前。 */
    suspend fun listAll(): List<ActionItem> = withContext(Dispatchers.IO) {
        db.query(
            ActionDatabase.TABLE_ACTION, null, null, null, null, null,
            "${ActionDatabase.AC_CREATED_AT} DESC",
        ).use { cursorToList(it) }
    }

    /** 仅未完成（OPEN）的行动项，新创建在前。 */
    suspend fun listOpen(): List<ActionItem> = withContext(Dispatchers.IO) {
        db.query(
            ActionDatabase.TABLE_ACTION, null,
            "${ActionDatabase.AC_STATUS}=?", arrayOf(ActionStatus.OPEN.name),
            null, null, "${ActionDatabase.AC_CREATED_AT} DESC",
        ).use { cursorToList(it) }
    }

    /**
     * 新建或更新一条行动项。[ActionItem.id] 大于 0 时按主键更新并原样返回；
     * 否则插入新行并返回自增 id。
     */
    suspend fun upsert(item: ActionItem): Long = withContext(Dispatchers.IO) {
        if (item.id > 0) {
            val cv = toValues(item).apply { put(ActionDatabase.AC_ID, item.id) }
            db.update(ActionDatabase.TABLE_ACTION, cv,
                "${ActionDatabase.AC_ID}=?", arrayOf(item.id.toString()))
            return@withContext item.id
        }
        db.insertWithOnConflict(
            ActionDatabase.TABLE_ACTION, null, toValues(item),
            android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    /** 更新行动项状态；置为 DONE 时记录完成时间，其余状态清空完成时间。 */
    suspend fun updateStatus(id: Long, status: ActionStatus) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put(ActionDatabase.AC_STATUS, status.name)
            put(ActionDatabase.AC_COMPLETED_AT, if (status == ActionStatus.DONE) now else 0L)
        }
        db.update(ActionDatabase.TABLE_ACTION, cv,
            "${ActionDatabase.AC_ID}=?", arrayOf(id.toString()))
    }

    /**
     * 把一次复盘（批判镜 / 榜样镜）产出的 followUps 导入行动库。
     *
     * 映射口径：FollowUp 在提示契约里是"近期能直接做的可执行跟进"，故一律落为
     * [ActionKind.NEXT_STEP]；"替代说法"类内容结构化存在于 MirrorReport.issues[].alternative，
     * 不在 followUps 列表内，FollowUp 本身也不带类别字段——这里不做任何文本猜测 / 关键词判定。
     *
     * @param reflectionId 复盘记录 id，作为来源与去重键
     * @param followUps 该报告内嵌的跟进列表
     */
    suspend fun importFromReflection(
        reflectionId: Long,
        sourceTypeLabel: String,
        sourceTitle: String,
        sourceSnippet: String,
        followUps: List<FollowUp>,
    ) = withContext(Dispatchers.IO) {
        if (reflectionId <= 0) return@withContext
        val existing = mutableSetOf<String>()
        db.query(
            ActionDatabase.TABLE_ACTION, arrayOf(ActionDatabase.AC_TITLE),
            "${ActionDatabase.AC_SOURCE_REFLECTION_ID}=?", arrayOf(reflectionId.toString()),
            null, null, null,
        ).use { c ->
            while (c.moveToNext()) existing += c.getString(0)
        }
        val now = System.currentTimeMillis()
        followUps.forEach { fu ->
            val title = fu.text.trim()
            if (title.isBlank() || title in existing) return@forEach
            val cv = ContentValues().apply {
                put(ActionDatabase.AC_TITLE, title)
                put(ActionDatabase.AC_KIND, ActionKind.NEXT_STEP.name)
                put(ActionDatabase.AC_STATUS, ActionStatus.OPEN.name)
                put(ActionDatabase.AC_SOURCE_TYPE_LABEL, sourceTypeLabel)
                put(ActionDatabase.AC_SOURCE_TITLE, sourceTitle)
                put(ActionDatabase.AC_SOURCE_SNIPPET, sourceSnippet)
                put(ActionDatabase.AC_SOURCE_REFLECTION_ID, reflectionId)
                put(ActionDatabase.AC_CREATED_AT, now)
                put(ActionDatabase.AC_COMPLETED_AT, 0L)
            }
            db.insertWithOnConflict(
                ActionDatabase.TABLE_ACTION, null, cv,
                android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE,
            )
            existing += title
        }
    }

    private fun toValues(item: ActionItem): ContentValues = ContentValues().apply {
        put(ActionDatabase.AC_TITLE, item.title)
        put(ActionDatabase.AC_KIND, item.kind.name)
        put(ActionDatabase.AC_STATUS, item.status.name)
        put(ActionDatabase.AC_SOURCE_TYPE_LABEL, item.sourceTypeLabel)
        put(ActionDatabase.AC_SOURCE_TITLE, item.sourceTitle)
        put(ActionDatabase.AC_SOURCE_SNIPPET, item.sourceSnippet)
        put(ActionDatabase.AC_SOURCE_REFLECTION_ID, item.sourceReflectionId)
        put(ActionDatabase.AC_CREATED_AT, item.createdAt)
        put(ActionDatabase.AC_COMPLETED_AT, item.completedAt)
    }

    private fun cursorToList(cursor: Cursor): List<ActionItem> {
        val items = mutableListOf<ActionItem>()
        cursor.use {
            while (it.moveToNext()) {
                items += ActionItem(
                    id = it.getLong(it.getColumnIndexOrThrow(ActionDatabase.AC_ID)),
                    title = it.getString(it.getColumnIndexOrThrow(ActionDatabase.AC_TITLE)) ?: "",
                    kind = ActionKind.fromName(it.getString(it.getColumnIndexOrThrow(ActionDatabase.AC_KIND))),
                    status = ActionStatus.fromName(it.getString(it.getColumnIndexOrThrow(ActionDatabase.AC_STATUS))),
                    sourceTypeLabel = it.getString(it.getColumnIndexOrThrow(ActionDatabase.AC_SOURCE_TYPE_LABEL)) ?: "",
                    sourceTitle = it.getString(it.getColumnIndexOrThrow(ActionDatabase.AC_SOURCE_TITLE)) ?: "",
                    sourceSnippet = it.getString(it.getColumnIndexOrThrow(ActionDatabase.AC_SOURCE_SNIPPET)) ?: "",
                    sourceReflectionId = it.getLong(it.getColumnIndexOrThrow(ActionDatabase.AC_SOURCE_REFLECTION_ID)),
                    createdAt = it.getLong(it.getColumnIndexOrThrow(ActionDatabase.AC_CREATED_AT)),
                    completedAt = it.getLong(it.getColumnIndexOrThrow(ActionDatabase.AC_COMPLETED_AT)),
                )
            }
        }
        return items
    }

    companion object {
        @Volatile
        private var instance: ActionStore? = null

        fun getInstance(context: Context): ActionStore =
            instance ?: synchronized(this) {
                instance ?: ActionStore(context).also { instance = it }
            }
    }
}
