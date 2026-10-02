package top.hsyscn.opedrgent.cultivation.store

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import top.hsyscn.opedrgent.cultivation.model.ExemplarAction
import top.hsyscn.opedrgent.cultivation.model.ExemplarHighlight
import top.hsyscn.opedrgent.cultivation.model.ExemplarImprovement
import top.hsyscn.opedrgent.cultivation.model.ExemplarReport
import top.hsyscn.opedrgent.cultivation.model.FeedbackMode
import top.hsyscn.opedrgent.cultivation.model.FollowUp
import top.hsyscn.opedrgent.cultivation.model.FollowUpStatus
import top.hsyscn.opedrgent.cultivation.model.IssueMark
import top.hsyscn.opedrgent.cultivation.model.MirrorIssue
import top.hsyscn.opedrgent.cultivation.model.MirrorReport
import top.hsyscn.opedrgent.cultivation.model.MirrorRoute
import top.hsyscn.opedrgent.cultivation.model.PatternNote
import top.hsyscn.opedrgent.cultivation.model.ReflectionLens
import top.hsyscn.opedrgent.cultivation.model.StrengthNote

/**
 * 一条持久化的修炼会话记录（统一信封，P1）。
 *
 * 批判镜与榜样镜都落为同一种记录：[lens] 决定读哪一份报告，[critique] / [exemplar] 二者其一非空。
 * 标记 [marks] 与一句 [reflection] 对批判镜问题条目使用；[retained]=false 表示“仅本次对照不留存”，
 * 这类记录根本不会被插入（保留字段以便日后需要“会话结束后统一清理”时扩展）。
 */
data class ReflectionRecord(
    val id: Long = 0,
    val lens: ReflectionLens,
    val critique: MirrorReport? = null,
    val exemplar: ExemplarReport? = null,
    val marks: Map<Int, IssueMark> = emptyMap(),
    val reflection: String = "",
    val retained: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val route: MirrorRoute
        get() = critique?.route ?: exemplar?.route ?: MirrorRoute.ANALYZE

    val mode: FeedbackMode
        get() = critique?.mode ?: exemplar?.mode ?: FeedbackMode.STANDARD
}

/** 一条仍处于某状态的跟进及其所属会话，用于跨历史复评与状态回写。 */
data class OpenFollowUp(
    val recordId: Long,
    val lens: ReflectionLens,
    val follow: FollowUp,
)

/**
 * 修炼会话统一存储（批判镜 + 榜样镜）。全本地，不做任何上传。
 * 取代 v1 只承载批判镜的 CultivationReportStore。
 */
class ReflectionStore(context: Context) {

    private val db = CultivationDatabase.getInstance(context).writableDatabase

    /** 插入一条会话记录，返回行 id；retained=false 时直接不落库，返回 null。 */
    suspend fun insert(record: ReflectionRecord): Long? = withContext(Dispatchers.IO) {
        if (!record.retained) return@withContext null
        val report = record.critique ?: record.exemplar
        val payload = record.critique?.let { encodeCritique(it).toString() }
            ?: record.exemplar?.let { encodeExemplar(it).toString() }
            ?: "{}"
        val cv = ContentValues().apply {
            put(CultivationDatabase.RS_LENS, record.lens.name)
            put(CultivationDatabase.RS_ROUTE, record.route.name)
            put(CultivationDatabase.RS_MODE, record.mode.name)
            put(CultivationDatabase.RS_EXEMPLAR, record.exemplar?.exemplar ?: "")
            put(CultivationDatabase.RS_PAYLOAD_JSON, payload)
            put(CultivationDatabase.RS_MARKS_JSON, encodeMarks(record.marks))
            put(CultivationDatabase.RS_REFLECTION, record.reflection)
            put(CultivationDatabase.RS_RETAINED, if (record.retained) 1 else 0)
            put(CultivationDatabase.RS_CREATED_AT, (record.critique?.createdAt ?: record.exemplar?.createdAt ?: record.createdAt))
        }
        db.insert(CultivationDatabase.TABLE_REFLECTION, null, cv)
    }

    suspend fun getById(id: Long): ReflectionRecord? = withContext(Dispatchers.IO) {
        db.query(
            CultivationDatabase.TABLE_REFLECTION, null,
            "${CultivationDatabase.RS_ID}=?", arrayOf(id.toString()),
            null, null, null, "1",
        ).use { cursorToList(it).firstOrNull() }
    }

    /** 最近会话；[lens] 非空时只取该透镜，默认两镜都取，新记录在前。 */
    suspend fun listRecent(limit: Int = 50, lens: ReflectionLens? = null): List<ReflectionRecord> =
        withContext(Dispatchers.IO) {
            val where = lens?.let { "${CultivationDatabase.RS_LENS}=?" }
            val args = lens?.let { arrayOf(it.name) }
            db.query(
                CultivationDatabase.TABLE_REFLECTION, null, where, args, null, null,
                "${CultivationDatabase.RS_CREATED_AT} DESC", limit.toString(),
            ).use { cursorToList(it) }
        }

    /** 更新某条记录中第 issueIndex 条问题的用户标记（批判镜 N5）。 */
    suspend fun updateMark(id: Long, issueIndex: Int, mark: IssueMark) = withContext(Dispatchers.IO) {
        // 读整条 marks → 改 map → 整列回写，必须包事务，否则快速连续标记会丢更新。
        // 事务内只做 DB 操作、全程同线程，故内联查询而非调用 suspend 版 getById。
        db.beginTransaction()
        try {
            val current = db.query(
                CultivationDatabase.TABLE_REFLECTION, null,
                "${CultivationDatabase.RS_ID}=?", arrayOf(id.toString()),
                null, null, null, "1",
            ).use { cursorToList(it).firstOrNull()?.marks.orEmpty().toMutableMap() }
            current[issueIndex] = mark
            val cv = ContentValues().apply { put(CultivationDatabase.RS_MARKS_JSON, encodeMarks(current)) }
            db.update(CultivationDatabase.TABLE_REFLECTION, cv,
                "${CultivationDatabase.RS_ID}=?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    suspend fun updateReflection(id: Long, reflection: String) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply { put(CultivationDatabase.RS_REFLECTION, reflection.take(1000)) }
        db.update(CultivationDatabase.TABLE_REFLECTION, cv, "${CultivationDatabase.RS_ID}=?", arrayOf(id.toString()))
    }

    /** 回写某条会话里指定跟进的状态（待办 / 已做到 / 不再跟进），只改 payload，不触碰原文。 */
    suspend fun updateFollowUpStatus(id: Long, followUpId: String, status: FollowUpStatus) =
        withContext(Dispatchers.IO) {
            // 读整条 record → 改 followUps → 整列 payload 回写，包事务防并发丢更新。
            // 事务内只做 DB 操作、全程同线程，故内联查询而非调用 suspend 版 getById。
            db.beginTransaction()
            try {
                val record = db.query(
                    CultivationDatabase.TABLE_REFLECTION, null,
                    "${CultivationDatabase.RS_ID}=?", arrayOf(id.toString()),
                    null, null, null, "1",
                ).use { cursorToList(it).firstOrNull() }
                val payload = record?.let { r ->
                    val critique = r.critique
                    val exemplar = r.exemplar
                    when {
                        critique != null -> encodeCritique(
                            critique.copy(followUps = critique.followUps.map {
                                if (it.id == followUpId) it.copy(status = status) else it
                            }),
                        ).toString()
                        exemplar != null -> encodeExemplar(
                            exemplar.copy(followUps = exemplar.followUps.map {
                                if (it.id == followUpId) it.copy(status = status) else it
                            }),
                        ).toString()
                        else -> null
                    }
                }
                if (payload != null) {
                    val cv = ContentValues().apply { put(CultivationDatabase.RS_PAYLOAD_JSON, payload) }
                    db.update(CultivationDatabase.TABLE_REFLECTION, cv,
                        "${CultivationDatabase.RS_ID}=?", arrayOf(id.toString()))
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

    /**
     * 跨历史收集仍未完成（OPEN）的跟进，新记录在前，供下次复盘时交给模型自主复评。
     * 工程只负责取清单，是否做到由模型结合当次转写判断，不做关键词命中。
     */
    suspend fun openFollowUps(limit: Int = 8): List<OpenFollowUp> = withContext(Dispatchers.IO) {
        buildList {
            for (record in listRecent(200)) {
                val items = record.critique?.followUps ?: record.exemplar?.followUps ?: continue
                items.filter { it.status == FollowUpStatus.OPEN && it.text.isNotBlank() }
                    .forEach { add(OpenFollowUp(record.id, record.lens, it)) }
                if (size >= limit) return@buildList
            }
        }
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        db.delete(CultivationDatabase.TABLE_REFLECTION, "${CultivationDatabase.RS_ID}=?", arrayOf(id.toString()))
    }

    /** 修炼数据独立清除入口：删除即本地不可恢复。 */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        db.delete(CultivationDatabase.TABLE_REFLECTION, null, null)
    }

    private fun cursorToList(cursor: Cursor): List<ReflectionRecord> {
        val items = mutableListOf<ReflectionRecord>()
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getLong(it.getColumnIndexOrThrow(CultivationDatabase.RS_ID))
                val lens = ReflectionLens.fromName(it.getString(it.getColumnIndexOrThrow(CultivationDatabase.RS_LENS)))
                val payload = it.getString(it.getColumnIndexOrThrow(CultivationDatabase.RS_PAYLOAD_JSON)) ?: "{}"
                val marks = decodeMarks(it.getString(it.getColumnIndexOrThrow(CultivationDatabase.RS_MARKS_JSON)))
                val reflection = it.getString(it.getColumnIndexOrThrow(CultivationDatabase.RS_REFLECTION)) ?: ""
                val retained = it.getInt(it.getColumnIndexOrThrow(CultivationDatabase.RS_RETAINED)) == 1
                val createdAt = it.getLong(it.getColumnIndexOrThrow(CultivationDatabase.RS_CREATED_AT))
                val record = if (lens == ReflectionLens.EXEMPLAR) {
                    ReflectionRecord(
                        id = id, lens = lens, exemplar = decodeExemplar(payload),
                        marks = marks, reflection = reflection, retained = retained, createdAt = createdAt,
                    )
                } else {
                    ReflectionRecord(
                        id = id, lens = lens, critique = decodeCritique(payload),
                        marks = marks, reflection = reflection, retained = retained, createdAt = createdAt,
                    )
                }
                items.add(record)
            }
        }
        return items
    }

    // ===== 批判镜编解码 =====

    private fun encodeCritique(r: MirrorReport): JSONObject = JSONObject()
        .put("sessionId", r.sessionId)
        .put("transcriptId", r.transcriptId)
        .put("route", r.route.name)
        .put("overall", r.overall)
        .put("issues", JSONArray().apply {
            r.issues.forEach { i ->
                put(JSONObject().put("quote", i.quote).put("baselineRef", i.baselineRef)
                    .put("impact", i.impact).put("alternative", i.alternative)
                    .put("dimension", i.dimension)
                    // referenceName 仅认知镜按需非空；言行镜恒为空，统一随信封落库/读出。
                    .put("referenceName", i.referenceName))
            }
        })
        .put("nextStep", r.nextStep)
        .put("support", r.support)
        .put("helpResources", r.helpResources)
        .put("strengths", JSONArray().apply {
            r.strengths.forEach { s ->
                put(JSONObject().put("quote", s.quote).put("trait", s.trait).put("note", s.note))
            }
        })
        .put("patterns", JSONArray().apply {
            r.patterns.forEach { p ->
                put(JSONObject().put("pattern", p.pattern).put("note", p.note).put("refs", JSONArray(p.refs)))
            }
        })
        .put("followUps", encodeFollowUps(r.followUps))
        .put("mode", r.mode.name)
        .put("modelUsed", r.modelUsed)
        .put("backend", r.backend)
        .put("createdAt", r.createdAt)
        .put("rawResponse", r.rawResponse)

    private fun decodeCritique(raw: String?): MirrorReport = runCatching {
        val o = JSONObject(raw ?: "{}")
        MirrorReport(
            sessionId = o.optString("sessionId", ""),
            transcriptId = o.optString("transcriptId", ""),
            route = MirrorRoute.fromName(o.optString("route")),
            overall = o.optString("overall", ""),
            issues = o.optJSONArray("issues").mapObjects { jo ->
                MirrorIssue(
                    quote = jo.optString("quote", ""),
                    baselineRef = jo.optString("baselineRef", ""),
                    impact = jo.optString("impact", ""),
                    alternative = jo.optString("alternative", ""),
                    dimension = jo.optString("dimension", ""),
                    referenceName = jo.optString("referenceName", ""),
                )
            },
            nextStep = o.optString("nextStep", ""),
            support = o.optString("support", ""),
            helpResources = o.optString("helpResources", ""),
            strengths = o.optJSONArray("strengths").mapObjects { jo ->
                StrengthNote(
                    quote = jo.optString("quote", ""),
                    trait = jo.optString("trait", ""),
                    note = jo.optString("note", ""),
                )
            },
            patterns = decodePatterns(o.optJSONArray("patterns")),
            followUps = decodeFollowUps(o.optJSONArray("followUps")),
            mode = FeedbackMode.fromName(o.optString("mode")),
            modelUsed = o.optString("modelUsed", ""),
            backend = o.optString("backend", ""),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            rawResponse = o.optString("rawResponse", ""),
        )
    }.getOrElse { MirrorReport("", "", MirrorRoute.ANALYZE, "", emptyList(), "") }

    // ===== 榜样镜编解码 =====

    private fun encodeExemplar(r: ExemplarReport): JSONObject = JSONObject()
        .put("exemplar", r.exemplar)
        .put("situation", r.situation)
        .put("actions", JSONArray().apply {
            r.actions.forEach { a -> put(JSONObject().put("action", a.action).put("rationale", a.rationale)) }
        })
        .put("highlights", JSONArray().apply {
            r.highlights.forEach { h -> put(JSONObject().put("quote", h.quote).put("point", h.point)) }
        })
        .put("improvements", JSONArray().apply {
            r.improvements.forEach { m ->
                put(JSONObject().put("observation", m.observation).put("suggestion", m.suggestion))
            }
        })
        .put("takeaway", r.takeaway)
        .put("route", r.route.name)
        .put("support", r.support)
        .put("helpResources", r.helpResources)
        .put("followUps", encodeFollowUps(r.followUps))
        .put("mode", r.mode.name)
        .put("modelUsed", r.modelUsed)
        .put("backend", r.backend)
        .put("createdAt", r.createdAt)
        .put("rawResponse", r.rawResponse)

    private fun decodeExemplar(raw: String?): ExemplarReport = runCatching {
        val o = JSONObject(raw ?: "{}")
        ExemplarReport(
            exemplar = o.optString("exemplar", ""),
            situation = o.optString("situation", ""),
            actions = o.optJSONArray("actions").mapObjects { jo ->
                ExemplarAction(jo.optString("action", ""), jo.optString("rationale", ""))
            },
            highlights = o.optJSONArray("highlights").mapObjects { jo ->
                ExemplarHighlight(jo.optString("quote", ""), jo.optString("point", ""))
            },
            improvements = o.optJSONArray("improvements").mapObjects { jo ->
                ExemplarImprovement(jo.optString("observation", ""), jo.optString("suggestion", ""))
            },
            takeaway = o.optString("takeaway", ""),
            route = MirrorRoute.fromName(o.optString("route")),
            support = o.optString("support", ""),
            helpResources = o.optString("helpResources", ""),
            followUps = decodeFollowUps(o.optJSONArray("followUps")),
            mode = FeedbackMode.fromName(o.optString("mode")),
            modelUsed = o.optString("modelUsed", ""),
            backend = o.optString("backend", ""),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            rawResponse = o.optString("rawResponse", ""),
        )
    }.getOrElse { ExemplarReport("", "", emptyList(), emptyList(), emptyList(), "") }

    // ===== 共享片段 =====

    private fun encodeFollowUps(items: List<FollowUp>): JSONArray = JSONArray().apply {
        items.forEach { f ->
            put(JSONObject().put("text", f.text).put("id", f.id).put("status", f.status.name))
        }
    }

    private fun decodeFollowUps(arr: JSONArray?): List<FollowUp> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { i ->
            when {
                arr.opt(i) is String -> FollowUp(arr.getString(i))
                else -> {
                    val o = arr.optJSONObject(i) ?: return@map FollowUp("")
                    FollowUp(
                        text = o.optString("text", ""),
                        id = o.optString("id", ""),
                        status = FollowUpStatus.fromName(o.optString("status")),
                    )
                }
            }
        }.filter { it.text.isNotBlank() }
    }

    private fun decodePatterns(arr: JSONArray?): List<PatternNote> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            when {
                arr.opt(i) is String -> PatternNote(arr.getString(i))
                else -> {
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val refs = o.optJSONArray("refs")?.let { ra ->
                        (0 until ra.length()).map { ra.optString(it) }.filter { s -> s.isNotBlank() }
                    } ?: emptyList()
                    PatternNote(o.optString("pattern", ""), o.optString("note", ""), refs)
                }
            }
        }.filter { it.pattern.isNotBlank() }
    }

    private fun encodeMarks(marks: Map<Int, IssueMark>): String {
        val o = JSONObject()
        marks.forEach { (idx, mark) -> o.put(idx.toString(), mark.name) }
        return o.toString()
    }

    private fun decodeMarks(raw: String?): Map<Int, IssueMark> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            val out = mutableMapOf<Int, IssueMark>()
            o.keys().forEach { k -> k.toIntOrNull()?.let { idx -> out[idx] = IssueMark.fromName(o.optString(k)) } }
            out
        }.getOrDefault(emptyMap())
    }

    /** 把 JSON 数组逐元素映射为强类型对象列表，元素不是对象时跳过。 */
    private inline fun <T> JSONArray?.mapObjects(map: (JSONObject) -> T): List<T> =
        this?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(map) } } ?: emptyList()
}
