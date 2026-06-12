package top.hsyscn.opedrgent.intelligence

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

/**
 * 用户行为事件枚举 — 追踪用户在 Opedrgent 中的所有关键操作。
 *
 * 这些事件是智能推荐引擎的数据基础。
 */
enum class BehaviorEvent {
    NOTE_CREATED,         // 创建笔记
    NOTE_EDITED,          // 编辑笔记
    NOTE_SPROUTED,        // 发芽分析
    NOTE_SENT_TO_AI,      // 发送到 AI
    AI_CHAT_CREATED,      // 创建 AI 对话
    AI_MESSAGE_SENT,      // 发送 AI 消息
    RECORDING_STARTED,    // 开始录音
    RECORDING_FINISHED,   // 录音结束
    FILE_IMPORTED,        // 导入文件
    KB_DOCUMENT_ADDED,    // 知识库添加文档
    SKILL_USED,           // 使用技能（点评/拷问/润色）
    APP_OPENED,           // 打开应用
    TAB_SWITCHED,         // 切换 Tab
    SEARCH_PERFORMED,     // 执行搜索
}

/**
 * 单条行为记录。
 */
data class BehaviorRecord(
    val id: String = UUID.randomUUID().toString(),
    val event: BehaviorEvent,
    val timestamp: Long = System.currentTimeMillis(),
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * 行为数据库 — 原生 SQLite 实现，与 NoteDatabase 风格一致。
 *
 * 存储最近 30 天的用户行为记录，支持查询、统计和分析。
 */
private class BehaviorDatabase(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {

    companion object {
        private const val DATABASE_NAME = "opedrgent_behavior.db"
        private const val DATABASE_VERSION = 1
        const val TABLE_BEHAVIOR = "behavior"

        const val COL_ID = "id"
        const val COL_EVENT = "event"
        const val COL_TIMESTAMP = "timestamp"
        const val COL_METADATA = "metadata"

        @Volatile
        private var INSTANCE: BehaviorDatabase? = null

        fun getInstance(ctx: Context): BehaviorDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: BehaviorDatabase(ctx.applicationContext).also { INSTANCE = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_BEHAVIOR (
                $COL_ID TEXT PRIMARY KEY,
                $COL_EVENT TEXT NOT NULL,
                $COL_TIMESTAMP INTEGER NOT NULL,
                $COL_METADATA TEXT DEFAULT '{}'
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_behavior_event ON $TABLE_BEHAVIOR($COL_EVENT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_behavior_timestamp ON $TABLE_BEHAVIOR($COL_TIMESTAMP DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 未来版本升级时处理数据迁移
    }

    internal fun cursorToRecord(cursor: Cursor): BehaviorRecord {
        val metaJson = cursor.getString(cursor.getColumnIndexOrThrow(COL_METADATA)) ?: "{}"
        val metadata = try {
            val json = org.json.JSONObject(metaJson)
            val map = mutableMapOf<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = json.optString(key, "")
            }
            map
        } catch (_: Exception) { emptyMap() }

        return BehaviorRecord(
            id = cursor.getString(cursor.getColumnIndexOrThrow(COL_ID)) ?: UUID.randomUUID().toString(),
            event = try {
                BehaviorEvent.valueOf(cursor.getString(cursor.getColumnIndexOrThrow(COL_EVENT)) ?: "APP_OPENED")
            } catch (_: Exception) { BehaviorEvent.APP_OPENED },
            timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COL_TIMESTAMP)),
            metadata = metadata,
        )
    }
}

/**
 * 用户行为追踪器 — 智能推荐引擎的核心数据层。
 *
 * 职责：
 * - 记录用户在 App 中的所有关键行为事件
 * - 提供行为统计分析 API（频率、连续天数、最常用功能等）
 * - 数据保留最近 30 天，自动清理过期记录
 * - 为推荐引擎提供输入数据
 */
class UserBehaviorTracker(private val context: Context) {

    private val database = BehaviorDatabase.getInstance(context)
    private val prefs = context.getSharedPreferences("behavior_tracker_prefs", Context.MODE_PRIVATE)

    /**
     * 记录一条行为事件。
     *
     * @param event 行为类型
     * @param metadata 额外信息（如 noteId、query 等）
     */
    fun track(event: BehaviorEvent, metadata: Map<String, String> = emptyMap()) {
        val record = BehaviorRecord(event = event, metadata = metadata)
        val writableDb = database.writableDatabase
        val metaJson = try {
            org.json.JSONObject(metadata).toString()
        } catch (_: Exception) { "{}" }

        writableDb.execSQL(
            "INSERT OR IGNORE INTO ${BehaviorDatabase.TABLE_BEHAVIOR} (${BehaviorDatabase.COL_ID}, ${BehaviorDatabase.COL_EVENT}, ${BehaviorDatabase.COL_TIMESTAMP}, ${BehaviorDatabase.COL_METADATA}) VALUES (?, ?, ?, ?)",
            arrayOf<String?>(record.id, record.event.name, record.timestamp.toString(), metaJson),
        )

        // 更新今日活跃标记
        val todayKey = "active_${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(java.util.Date())}"
        prefs.edit().putBoolean(todayKey, true).apply()

        // 更新最后活跃时间
        prefs.edit().putLong("last_active", System.currentTimeMillis()).apply()

        // 定期清理超过 30 天的旧记录（每天最多执行一次）
        val lastCleanup = prefs.getLong("last_cleanup_time", 0L)
        val now = System.currentTimeMillis()
        if (now - lastCleanup > 24L * 3600_000) {
            prefs.edit().putLong("last_cleanup_time", now).apply()
            cleanupOldRecords()
        }
    }

    /**
     * 获取最近 N 小时内的行为事件。
     */
    fun getRecentEvents(hours: Int = 24): List<BehaviorRecord> {
        val since = System.currentTimeMillis() - hours * 3600_000L
        val readableDb = database.readableDatabase
        val cursor = readableDb.query(
            BehaviorDatabase.TABLE_BEHAVIOR,
            null,
            "${BehaviorDatabase.COL_TIMESTAMP} >= ?",
            arrayOf(since.toString()),
            "${BehaviorDatabase.COL_TIMESTAMP} DESC",
            null,
            null,
            "200",
        )
        return useCursor(cursor) { c ->
            val records = mutableListOf<BehaviorRecord>()
            while (c.moveToNext()) {
                records.add(database.cursorToRecord(c))
            }
            records
        }
    }

    /**
     * 获取指定事件类型在最近 N 小时内的出现次数。
     */
    fun getEventCount(event: BehaviorEvent, sinceHours: Int): Int {
        val since = System.currentTimeMillis() - sinceHours * 3600_000L
        val readableDb = database.readableDatabase
        val cursor = readableDb.query(
            BehaviorDatabase.TABLE_BEHAVIOR,
            arrayOf("COUNT(*)"),
            "${BehaviorDatabase.COL_EVENT} = ? AND ${BehaviorDatabase.COL_TIMESTAMP} >= ?",
            arrayOf(event.name, since.toString()),
            null, null, null,
        )
        return useCursor(cursor) { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /**
     * 获取今天活跃的小时数（按整点计算）。
     */
    fun getActiveHoursToday(): Int {
        val todayStart = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).parse(
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(java.util.Date())
        )?.time ?: System.currentTimeMillis()
        val readableDb = database.readableDatabase
        val cursor = readableDb.query(
            BehaviorDatabase.TABLE_BEHAVIOR,
            arrayOf("DISTINCT (CAST(${BehaviorDatabase.COL_TIMESTAMP} / 3600000 AS INTEGER))"),
            "${BehaviorDatabase.COL_TIMESTAMP} >= ?",
            arrayOf(todayStart.toString()),
            null, null, null,
        )
        return useCursor(cursor) { c ->
            var count = 0
            while (c.moveToNext()) count++
            count
        }
    }

    /**
     * 统计过去 N 天内有多少天有活跃记录。
     */
    fun countActiveDaysSince(days: Int): Int {
        val since = System.currentTimeMillis() - days.toLong() * 24 * 3600_000
        val readableDb = database.readableDatabase
        val cursor = readableDb.query(
            BehaviorDatabase.TABLE_BEHAVIOR,
            arrayOf("DISTINCT strftime('%Y-%m-%d', ${BehaviorDatabase.COL_TIMESTAMP} / 1000, 'unixepoch', 'localtime')"),
            "${BehaviorDatabase.COL_TIMESTAMP} >= ?",
            arrayOf(since.toString()),
            null, null, null,
        )
        return useCursor(cursor) { c ->
            var count = 0
            while (c.moveToNext()) count++
            count.coerceAtMost(days)
        }
    }

    /**
     * 获取连续使用天数。
     */
    fun getStreakDays(): Int {
        var streak = 0
        val cal = java.util.Calendar.getInstance(java.util.Locale.CHINA)
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)

        for (i in 0..365) {
            val dateStr = sdf.format(cal.time)
            if (prefs.getBoolean("active_$dateStr", false)) {
                streak++
                cal.add(java.util.Calendar.DAY_OF_MONTH, -1)
            } else {
                break
            }
        }
        return streak
    }

    /**
     * 获取最常用的功能（基于最近 7 天的事件统计）。
     */
    fun getMostUsedFeature(): String {
        val events = getRecentEvents(hours = 24 * 7)
        if (events.isEmpty()) return "未使用"

        val counts = mutableMapOf<String, Int>()
        for (event in events) {
            counts[event.event.name] = (counts[event.event.name] ?: 0) + 1
        }

        val featureMap = mapOf(
            "NOTE_CREATED" to "创建笔记",
            "NOTE_EDITED" to "编辑笔记",
            "NOTE_SPROUTED" to "发芽分析",
            "NOTE_SENT_TO_AI" to "发送到AI",
            "AI_CHAT_CREATED" to "AI对话",
            "AI_MESSAGE_SENT" to "AI消息",
            "RECORDING_STARTED" to "录音",
            "RECORDING_FINISHED" to "录音完成",
            "FILE_IMPORTED" to "导入文件",
            "KB_DOCUMENT_ADDED" to "知识库文档",
            "SKILL_USED" to "使用技能",
            "SEARCH_PERFORMED" to "搜索",
        )

        val topEvent = counts.maxByOrNull { it.value }?.key ?: return "未使用"
        return featureMap[topEvent] ?: topEvent
    }

    /**
     * 获取最后活跃时间戳。
     */
    fun getLastActiveTime(): Long {
        return prefs.getLong("last_active", 0L)
    }

    /**
     * 清除所有历史行为记录（用于隐私清除或重置）。
     */
    fun clearHistory() {
        database.writableDatabase.delete(BehaviorDatabase.TABLE_BEHAVIOR, null, null)

        // 清除活跃日期记录
        val edit = prefs.edit()
        val allKeys = prefs.all.keys.filter { it.startsWith("active_") }
        for (key in allKeys) {
            edit.remove(key)
        }
        edit.remove("last_active")
        edit.apply()
    }

    /**
     * 清理超过 30 天的旧记录。
     */
    private fun cleanupOldRecords() {
        val threshold = System.currentTimeMillis() - 30L * 24 * 3600_000
        database.writableDatabase.delete(
            BehaviorDatabase.TABLE_BEHAVIOR,
            "${BehaviorDatabase.COL_TIMESTAMP} < ?",
            arrayOf(threshold.toString()),
        )
    }

    private inline fun <T> useCursor(cursor: Cursor, block: (Cursor) -> T): T {
        try {
            return block(cursor)
        } finally {
            cursor.close()
        }
    }
}
