package top.hsyscn.opedrgent.storage

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import top.hsyscn.opedrgent.utils.DebugLog
import top.hsyscn.opedrgent.interview.HippocampusMemory
import top.hsyscn.opedrgent.interview.InterviewConfig
import top.hsyscn.opedrgent.interview.InterviewType
import org.json.JSONArray
import org.json.JSONObject

/**
 * 面试会话持久化存储 — 跨会话记忆持久化层。
 *
 * ## 职责
 * 将 [HippocampusMemory] 的运行时状态（目标锚点、轮次记录、漂移报告）
 * 持久化到 SQLite，使得：
 * 1. 面试会话在 App 重启后可回顾
 * 2. 历史面试数据可检索（按类型/岗位/时间）
 * 3. 跨会话的注意力模式可分析（如某岗位长期漂移率高）
 *
 * ## 架构位置
 * ```
 * InterviewAgent.closeSession()
 *   -> HippocampusMemory.exportSessionState()
 *     -> HippocampusSessionStore.save()
 *       -> SQLite (hippocampus_sessions 表)
 * ```
 *
 * ## 与 HippocampusIndex 的关系
 * - [HippocampusIndex]: 轻量索引（title + summary + keywords），用于全局搜索
 * - [HippocampusSessionStore]: 完整会话数据（含每轮记录），用于详细回顾
 *
 * 关闭会话时两者都会写入：索引提供快速检索入口，会话表保存完整数据。
 */
class HippocampusSessionStore(context: Context) {

    companion object {
        private const val TAG = "HippocampusSessionStore"
    }

    private val db = HippocampusDatabase.getInstance(context).writableDatabase

    /**
     * 保存（或更新）一次面试会话的完整记录。
     *
     * @param sessionId 会话唯一标识
     * @param config 面试配置（用于提取类型/岗位/公司等元信息）
     * @param goalAnchor 目标锚点
     * @param report 漂移报告
     * @param startedAt 会话开始时间戳
     */
    fun save(
        sessionId: String,
        config: InterviewConfig,
        goalAnchor: HippocampusMemory.GoalAnchor,
        report: HippocampusMemory.DriftReport,
        startedAt: Long,
    ) = runBlocking(Dispatchers.IO) {
        try {
            val cv = ContentValues().apply {
                put(HippocampusDatabase.COL_SESSION_ID, sessionId)
                put(HippocampusDatabase.COL_INTERVIEW_TYPE, config.type.name)
                put(HippocampusDatabase.COL_POSITION, config.position)
                put(HippocampusDatabase.COL_COMPANY, config.company)
                put(HippocampusDatabase.COL_PRIMARY_GOAL, goalAnchor.primaryGoal)
                put(HippocampusDatabase.COL_KEY_TOPICS, goalAnchor.keyTopics.joinToString("\u0001"))
                put(HippocampusDatabase.COL_TOTAL_TURNS, report.totalTurns)
                put(HippocampusDatabase.COL_DRIFT_COUNT, report.driftCount)
                put(HippocampusDatabase.COL_DRIFT_RATE, report.driftRate)
                put(HippocampusDatabase.COL_MAX_DRIFT_LEVEL, report.maxDriftLevel.name)
                put(HippocampusDatabase.COL_AVG_RELEVANCE, report.averageRelevance)
                put(HippocampusDatabase.COL_TOPICS_COVERED, report.topicsCovered.joinToString("\u0001"))
                put(HippocampusDatabase.COL_TOPICS_MISSED, report.topicsMissed.joinToString("\u0001"))
                put(HippocampusDatabase.COL_INTERVENTION_COUNT, report.interventionCount)
                put(HippocampusDatabase.COL_SESSION_SUMMARY, report.summary)
                put(HippocampusDatabase.COL_TURN_RECORDS, serializeTurnRecords(report.turnRecords))
                put(HippocampusDatabase.COL_STARTED_AT, startedAt)
                put(HippocampusDatabase.COL_ENDED_AT, System.currentTimeMillis())
            }
            // INSERT OR REPLACE: 同一 sessionId 重做时覆盖旧记录
            db.insertWithOnConflict(
                HippocampusDatabase.TABLE_SESSIONS,
                null,
                cv,
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
            )
            DebugLog.i(TAG, "会话已持久化: $sessionId (轮次=${report.totalTurns}, 漂移率=${"%.1f".format(report.driftRate * 100)}%)")
        } catch (e: Exception) {
            DebugLog.e(TAG, "保存会话失败: ${e.message}", e)
        }
    }

    /**
     * 查询所有历史会话（按结束时间倒序）。
     */
    fun getAll(limit: Int = 100): List<SessionSummary> = runBlocking(Dispatchers.IO) {
        val cursor = db.query(
            HippocampusDatabase.TABLE_SESSIONS, null, null, null, null, null,
            "${HippocampusDatabase.COL_ENDED_AT} DESC", limit.toString(),
        )
        cursorToList(cursor)
    }

    /**
     * 按面试类型筛选历史会话。
     */
    fun getByType(type: InterviewType, limit: Int = 50): List<SessionSummary> = runBlocking(Dispatchers.IO) {
        val cursor = db.query(
            HippocampusDatabase.TABLE_SESSIONS, null,
            "${HippocampusDatabase.COL_INTERVIEW_TYPE}=?", arrayOf(type.name),
            null, null, "${HippocampusDatabase.COL_ENDED_AT} DESC", limit.toString(),
        )
        cursorToList(cursor)
    }

    /**
     * 关键词搜索历史会话（在目标/岗位/公司/摘要中匹配）。
     */
    fun search(keyword: String, limit: Int = 30): List<SessionSummary> = runBlocking(Dispatchers.IO) {
        val pattern = "%$keyword%"
        val sql = """SELECT * FROM ${HippocampusDatabase.TABLE_SESSIONS}
            WHERE ${HippocampusDatabase.COL_PRIMARY_GOAL} LIKE ?
            OR ${HippocampusDatabase.COL_POSITION} LIKE ?
            OR ${HippocampusDatabase.COL_COMPANY} LIKE ?
            OR ${HippocampusDatabase.COL_SESSION_SUMMARY} LIKE ?
            OR ${HippocampusDatabase.COL_KEY_TOPICS} LIKE ?
            ORDER BY ${HippocampusDatabase.COL_ENDED_AT} DESC LIMIT ?"""
        val cursor = db.rawQuery(sql, arrayOf(pattern, pattern, pattern, pattern, pattern, limit.toString()))
        cursorToList(cursor)
    }

    /**
     * 获取单条会话详情（含完整轮次记录）。
     */
    fun getById(sessionId: String): SessionDetail? = runBlocking(Dispatchers.IO) {
        val cursor = db.query(
            HippocampusDatabase.TABLE_SESSIONS, null,
            "${HippocampusDatabase.COL_SESSION_ID}=?", arrayOf(sessionId),
            null, null, null, "1",
        )
        val summary = cursorToList(cursor).firstOrNull() ?: return@runBlocking null
        // 二次查询拿完整 turn_records（summary 已包含，直接复用）
        toDetail(summary)
    }

    /**
     * 删除指定会话。
     */
    fun delete(sessionId: String) = runBlocking(Dispatchers.IO) {
        db.delete(
            HippocampusDatabase.TABLE_SESSIONS,
            "${HippocampusDatabase.COL_SESSION_ID}=?",
            arrayOf(sessionId),
        )
    }

    /**
     * 统计会话总数。
     */
    fun count(): Int = runBlocking(Dispatchers.IO) {
        val cursor = db.rawQuery("SELECT COUNT(*) FROM ${HippocampusDatabase.TABLE_SESSIONS}", null)
        cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    // ==================== 序列化 ====================

    /**
     * 将轮次记录列表序列化为 JSON 字符串。
     */
    private fun serializeTurnRecords(records: List<HippocampusMemory.TurnRecord>): String {
        val arr = JSONArray()
        for (r in records) {
            val obj = JSONObject().apply {
                put("turnIndex", r.turnIndex)
                put("userMessage", r.userMessage)
                put("aiResponse", r.aiResponse)
                put("timestamp", r.timestamp)
                put("driftLevel", r.driftResult.driftLevel.name)
                put("isDrifting", r.driftResult.isDrifting)
                put("relevanceScore", r.driftResult.relevanceScore.toDouble())
                put("driftReason", r.driftResult.driftReason)
                put("suggestedCorrection", r.driftResult.suggestedCorrection)
            }
            arr.put(obj)
        }
        return arr.toString()
    }

    /**
     * 反序列化轮次记录。
     */
    private fun deserializeTurnRecords(json: String): List<HippocampusMemory.TurnRecord> {
        val records = mutableListOf<HippocampusMemory.TurnRecord>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val driftLevel = runCatching {
                    HippocampusMemory.DriftLevel.valueOf(obj.optString("driftLevel", "NONE"))
                }.getOrDefault(HippocampusMemory.DriftLevel.NONE)
                val drift = HippocampusMemory.DriftResult(
                    isDrifting = obj.optBoolean("isDrifting", false),
                    driftLevel = driftLevel,
                    driftReason = obj.optString("driftReason", ""),
                    suggestedCorrection = obj.optString("suggestedCorrection", ""),
                    relevanceScore = obj.optDouble("relevanceScore", 1.0).toFloat(),
                )
                records.add(HippocampusMemory.TurnRecord(
                    turnIndex = obj.optInt("turnIndex", 0),
                    userMessage = obj.optString("userMessage", ""),
                    aiResponse = obj.optString("aiResponse", ""),
                    driftResult = drift,
                    timestamp = obj.optLong("timestamp", 0),
                ))
            }
        } catch (e: Exception) {
            DebugLog.w(TAG, "反序列化轮次记录失败: ${e.message}")
        }
        return records
    }

    // ==================== 数据类 ====================

    /** 会话摘要（列表展示用，不含完整轮次记录） */
    data class SessionSummary(
        val sessionId: String,
        val interviewType: String,
        val position: String,
        val company: String,
        val primaryGoal: String,
        val keyTopics: List<String>,
        val totalTurns: Int,
        val driftCount: Int,
        val driftRate: Float,
        val maxDriftLevel: String,
        val averageRelevance: Float,
        val topicsCovered: List<String>,
        val topicsMissed: List<String>,
        val interventionCount: Int,
        val summary: String,
        val turnRecordsJson: String,
        val startedAt: Long,
        val endedAt: Long,
    )

    /** 会话详情（含反序列化后的完整轮次记录） */
    data class SessionDetail(
        val summary: SessionSummary,
        val turnRecords: List<HippocampusMemory.TurnRecord>,
    )

    // ==================== Cursor 映射 ====================

    private fun cursorToList(cursor: Cursor): List<SessionSummary> {
        val items = mutableListOf<SessionSummary>()
        cursor.use {
            while (it.moveToNext()) {
                items.add(SessionSummary(
                    sessionId = it.getString(it.getColumnIndexOrThrow(HippocampusDatabase.COL_SESSION_ID)),
                    interviewType = it.getString(it.getColumnIndexOrThrow(HippocampusDatabase.COL_INTERVIEW_TYPE)),
                    position = it.getString(it.getColumnIndexOrThrow(HippocampusDatabase.COL_POSITION)),
                    company = it.getString(it.getColumnIndexOrThrow(HippocampusDatabase.COL_COMPANY)),
                    primaryGoal = it.getString(it.getColumnIndexOrThrow(HippocampusDatabase.COL_PRIMARY_GOAL)),
                    keyTopics = it.getString(it.getColumnIndexOrThrow(HippocampusDatabase.COL_KEY_TOPICS))
                        .split("\u0001").filter { s -> s.isNotEmpty() },
                    totalTurns = it.getInt(it.getColumnIndexOrThrow(HippocampusDatabase.COL_TOTAL_TURNS)),
                    driftCount = it.getInt(it.getColumnIndexOrThrow(HippocampusDatabase.COL_DRIFT_COUNT)),
                    driftRate = it.getFloat(it.getColumnIndexOrThrow(HippocampusDatabase.COL_DRIFT_RATE)),
                    maxDriftLevel = it.getString(it.getColumnIndexOrThrow(HippocampusDatabase.COL_MAX_DRIFT_LEVEL)),
                    averageRelevance = it.getFloat(it.getColumnIndexOrThrow(HippocampusDatabase.COL_AVG_RELEVANCE)),
                    topicsCovered = it.getString(it.getColumnIndexOrThrow(HippocampusDatabase.COL_TOPICS_COVERED))
                        .split("\u0001").filter { s -> s.isNotEmpty() },
                    topicsMissed = it.getString(it.getColumnIndexOrThrow(HippocampusDatabase.COL_TOPICS_MISSED))
                        .split("\u0001").filter { s -> s.isNotEmpty() },
                    interventionCount = it.getInt(it.getColumnIndexOrThrow(HippocampusDatabase.COL_INTERVENTION_COUNT)),
                    summary = it.getString(it.getColumnIndexOrThrow(HippocampusDatabase.COL_SESSION_SUMMARY)),
                    turnRecordsJson = it.getString(it.getColumnIndexOrThrow(HippocampusDatabase.COL_TURN_RECORDS)),
                    startedAt = it.getLong(it.getColumnIndexOrThrow(HippocampusDatabase.COL_STARTED_AT)),
                    endedAt = it.getLong(it.getColumnIndexOrThrow(HippocampusDatabase.COL_ENDED_AT)),
                ))
            }
        }
        return items
    }

    private fun toDetail(summary: SessionSummary): SessionDetail {
        return SessionDetail(
            summary = summary,
            turnRecords = deserializeTurnRecords(summary.turnRecordsJson),
        )
    }
}
