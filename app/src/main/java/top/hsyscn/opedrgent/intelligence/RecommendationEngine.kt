package top.hsyscn.opedrgent.intelligence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.note.Note
import top.hsyscn.opedrgent.note.NoteRepository
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 推荐类型枚举。
 */
enum class RecommendationType {
    RELATED_NOTE,       // 关联笔记
    ACTION_SUGGESTION,  // 操作建议
    CONTEXTUAL_TIP,     // 上下文提示
    DAILY_REVIEW,       // 每日回顾
    WEEKLY_REPORT,      // 周报
}

/**
 * 建议的操作类型。
 */
enum class SuggestedAction {
    SPROUT_LATEST_NOTE,   // 对最新笔记发芽
    REVIEW_WEEKLY,         // 生成本周总结
    ORGANIZE_NOTES,        // 整理笔记到知识库
    TRY_EDITOR_TEAM,       // 试试 AI 编辑团
    BACKUP_DATA,           // 备份数据
    SET_REMINDER,           // 设置提醒
    CONNECT_KB,            // 关联知识库
}

/**
 * 提示分类（时间感知）。
 */
enum class TipCategory {
    MORNING_GREETING,     // 早安提示
    AFTERNOON_ENERGY,     // 下午提神
    EVENING_REFLECTION,   // 晚间回顾
    WEEKEND_DEEP_DIVE,    // 周末深挖
    STREAK_CELEBRATION,    // 连续打卡庆祝
    INACTIVE_RETURN,       // 回归提醒
}

/**
 * 推荐基类 — 抽象类，所有推荐类型的统一接口。
 *
 * 设计理念：主动感知用户需求，在合适的时机推送有用的建议。
 */
abstract class Recommendation(
    open val id: String = java.util.UUID.randomUUID().toString(),
    val type: RecommendationType,
    open val title: String,
    open val description: String,
    open val priority: Int = 0,
    open val actionText: String = "查看",
    open val createdAt: Long = System.currentTimeMillis(),
) {

    /**
     * 基于笔记关联的推荐 — 推荐用户查看/操作某条笔记。
     */
    data class NoteRecommendation(
        val noteId: Long,
        val reason: String,
        override val id: String = java.util.UUID.randomUUID().toString(),
        override val title: String,
        override val description: String,
        override val priority: Int = 0,
        override val actionText: String = "查看",
        override val createdAt: Long = System.currentTimeMillis(),
    ) : Recommendation(id = id, type = RecommendationType.RELATED_NOTE, title = title, description = description, priority = priority, actionText = actionText, createdAt = createdAt)

    /**
     * 基于行为的主动建议 — 引导用户执行某个操作。
     */
    data class ActionSuggestion(
        val actionType: SuggestedAction,
        val targetId: String? = null,
        override val id: String = java.util.UUID.randomUUID().toString(),
        override val title: String,
        override val description: String,
        override val priority: Int = 0,
        override val actionText: String = "去试试",
        override val createdAt: Long = System.currentTimeMillis(),
    ) : Recommendation(id = id, type = RecommendationType.ACTION_SUGGESTION, title = title, description = description, priority = priority, actionText = actionText, createdAt = createdAt)

    /**
     * 时间感知推荐 — 根据当前时间和使用模式给出上下文提示。
     */
    data class ContextualTip(
        val tipCategory: TipCategory,
        override val id: String = java.util.UUID.randomUUID().toString(),
        override val title: String,
        override val description: String,
        override val priority: Int = 0,
        override val actionText: String = "知道了",
        override val createdAt: Long = System.currentTimeMillis(),
    ) : Recommendation(id = id, type = RecommendationType.CONTEXTUAL_TIP, title = title, description = description, priority = priority, actionText = actionText, createdAt = createdAt)
}

/**
 * 每日回顾数据。
 */
data class DailyReview(
    val date: String,
    val notesCreated: Int,
    val aiChats: Int,
    val recordings: Int,
    val topNote: String?,
    val insight: String?,
)

/**
 * 周报数据。
 */
data class WeeklyReport(
    val weekStart: String,
    val weekEnd: String,
    val totalNotes: Int,
    val totalAiChats: Int,
    val activeDays: Int,
    val topTags: List<String>,
    val summary: String,
)

/**
 * 推荐引擎 — 智能推荐系统的核心层。
 *
 * ## 推荐规则体系
 *
 * ### 时间规则
 * - 早上 7-9 点 → 问候 + 今日计划建议
 * - 晚上 8-10 点 → 回顾 + 整理建议
 *
 * ### 行为规则
 * - 连续 3 天使用 → 庆祝提示
 * - 7 天未使用 → 回归欢迎
 *
 * ### 内容规则
 * - 有未发芽的新笔记 → 推荐发芽
 * - 笔记数 > 20 且无知识库 → 建议整理
 *
 * ### 频率规则
 * - 每周五下午 → 周报提醒
 * - 每月1日 → 月度总结
 *
 * 所有推荐均为**同步计算**（不调 LLM），保持快速响应 < 100ms。
 */
class RecommendationEngine(
    private val behaviorTracker: UserBehaviorTracker,
    private val noteRepository: NoteRepository,
) {

    companion object {
        /** 最大推荐数量 */
        const val MAX_RECOMMENDATIONS = 5
    }

    /**
     * 生成当前推荐列表（首页调用）。
     *
     * 结合：时间 + 行为频率 + 最近活动 + 笔记数据
     * 返回按优先级排序的推荐列表，最多 [MAX_RECOMMENDATIONS] 条。
     */
    suspend fun generateRecommendations(): List<Recommendation> = withContext(Dispatchers.IO) {
        val recommendations = mutableListOf<Recommendation>()

        // 1. 时间感知规则
        addTimeBasedRecommendations(recommendations)

        // 2. 行为规则（连续天数、回归等）
        addBehaviorBasedRecommendations(recommendations)

        // 3. 内容规则（未发芽笔记、整理建议等）
        awaitContentBasedRecommendations(recommendations)

        // 4. 频率规则（周报、月总结）
        addFrequencyBasedRecommendations(recommendations)

        // 按优先级排序并截取
        recommendations.sortedByDescending { it.priority }.take(MAX_RECOMMENDATIONS)
    }

    /**
     * 生成每日回顾内容。
     */
    suspend fun generateDailyReview(): DailyReview? = withContext(Dispatchers.IO) {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
        val calStart = Calendar.getInstance(Locale.CHINA).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val dayStartMs = calStart.timeInMillis

        val notesCount = behaviorTracker.getEventCount(BehaviorEvent.NOTE_CREATED, sinceHours = 24)
                + behaviorTracker.getEventCount(BehaviorEvent.NOTE_EDITED, sinceHours = 24)
        val aiChatsCount = behaviorTracker.getEventCount(BehaviorEvent.AI_MESSAGE_SENT, sinceHours = 24)
        val recordingsCount = behaviorTracker.getEventCount(BehaviorEvent.RECORDING_FINISHED, sinceHours = 24)

        // 获取最近创建的一条笔记标题作为"今日亮点"
        val recentNotes = try { noteRepository.getRecentNotes(1) } catch (_: Exception) { emptyList() }
        val topNoteTitle = recentNotes.firstOrNull()?.title?.takeIf { it.isNotBlank() }

        // 生成洞察文字（基于行为统计）
        val insight = buildDailyInsight(notesCount, aiChatsCount, recordingsCount)

        if (notesCount == 0 && aiChatsCount == 0 && recordingsCount == 0) null
        else DailyReview(
            date = todayStr,
            notesCreated = notesCount,
            aiChats = aiChatsCount,
            recordings = recordingsCount,
            topNote = topNoteTitle,
            insight = insight,
        )
    }

    /**
     * 生成周报。
     */
    suspend fun generateWeeklyReport(): WeeklyReport? = withContext(Dispatchers.IO) {
        val now = Calendar.getInstance(Locale.CHINA)
        val weekEnd = SimpleDateFormat("MM-dd", Locale.CHINA).format(now.time)
        now.add(Calendar.DAY_OF_MONTH, -6)
        val weekStart = SimpleDateFormat("MM-dd", Locale.CHINA).format(now.time)

        val totalNotes = behaviorTracker.getEventCount(BehaviorEvent.NOTE_CREATED, sinceHours = 24 * 7)
        val totalAiChats = behaviorTracker.getEventCount(BehaviorEvent.AI_MESSAGE_SENT, sinceHours = 24 * 7)
        val activeDays = behaviorTracker.countActiveDaysSince(7)

        // 获取热门标签
        val topTags = try {
            noteRepository.getAllTags().firstOrNull() ?: emptyList()
        } catch (_: Exception) { emptyList() }

        val summary = buildWeeklySummary(totalNotes, totalAiChats, activeDays)

        if (totalNotes == 0 && totalAiChats == 0) null
        else WeeklyReport(
            weekStart = weekStart,
            weekEnd = weekEnd,
            totalNotes = totalNotes,
            totalAiChats = totalAiChats,
            activeDays = activeDays,
            topTags = topTags.take(5),
            summary = summary,
        )
    }

    // ==================== 私有规则实现 ====================

    private fun addTimeBasedRecommendations(out: MutableList<Recommendation>) {
        val cal = Calendar.getInstance(Locale.CHINA)
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val isWeekend = dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY

        when {
            hour in 7..9 -> {
                out.add(Recommendation.ContextualTip(
                    tipCategory = TipCategory.MORNING_GREETING,
                    title = "早上好",
                    description = "新的一天开始了！今天有什么想记录或探索的吗？",
                    priority = 5,
                    actionText = "开始记录",
                ))
            }
            hour in 14..16 -> {
                out.add(Recommendation.ContextualTip(
                    tipCategory = TipCategory.AFTERNOON_ENERGY,
                    title = "下午好",
                    description = "午后时光，适合整理一下上午的想法。看看有没有需要发芽的笔记？",
                    priority = 4,
                ))
            }
            hour in 20..22 -> {
                out.add(Recommendation.ContextualTip(
                    tipCategory = TipCategory.EVENING_REFLECTION,
                    title = "晚间回顾",
                    description = "一天即将结束，花一分钟回顾今天的收获吧。",
                    priority = 6,
                    actionText = "查看今日回顾",
                ))
            }
        }

        if (isWeekend) {
            out.add(Recommendation.ContextualTip(
                tipCategory = TipCategory.WEEKEND_DEEP_DIVE,
                title = "周末深挖",
                description = "周末适合深度思考——找一条重要笔记，让 AI 帮你深度发芽分析。",
                priority = 3,
            ))
        }
    }

    private fun addBehaviorBasedRecommendations(out: MutableList<Recommendation>) {
        val streak = behaviorTracker.getStreakDays()
        val lastActive = behaviorTracker.getLastActiveTime()

        // 连续打卡庆祝：3天、7天、14天、30天
        when (streak) {
            3 -> {
                out.add(Recommendation.ContextualTip(
                    tipCategory = TipCategory.STREAK_CELEBRATION,
                    title = "连续使用 3 天",
                    description = "你已经连续使用了 3 天！保持记录的习惯，AI 会越来越懂你。",
                    priority = 9,
                    actionText = "太棒了",
                ))
            }
            7 -> {
                out.add(Recommendation.ContextualTip(
                    tipCategory = TipCategory.STREAK_CELEBRATION,
                    title = "一周不间断",
                    description = "连续 7 天使用！你已经养成了记录的好习惯，继续加油！",
                    priority = 10,
                    actionText = "感谢陪伴",
                ))
            }
            14 -> {
                out.add(Recommendation.ContextualTip(
                    tipCategory = TipCategory.STREAK_CELEBRATION,
                    title = "两周坚持",
                    description = "14 天不间断！你的数据正在变得越来越有价值。",
                    priority = 10,
                    actionText = "继续保持",
                ))
            }
            30 -> {
                out.add(Recommendation.ContextualTip(
                    tipCategory = TipCategory.STREAK_CELEBRATION,
                    title = "满月纪念",
                    description = "整整一个月！你是真正的记录达人。Opedrgent 已经很了解你了。",
                    priority = 11,
                    actionText = "领取成就",
                ))
            }
        }

        // 回归提醒：超过 7 天未使用
        val inactiveDays = ((System.currentTimeMillis() - lastActive) / (24 * 3600_000L)).toInt()
        if (inactiveDays >= 7 && inactiveDays <= 30) {
            out.add(Recommendation.ContextualTip(
                tipCategory = TipCategory.INACTIVE_RETURN,
                title = "好久不见",
                description = "距离上次使用已经 ${inactiveDays} 天了。回来继续记录吧，AI 还在等你。",
                priority = 12,
                actionText = "我回来了",
            ))
        }
    }

    private suspend fun awaitContentBasedRecommendations(out: MutableList<Recommendation>) {
        // 检查是否有未发芽的新笔记
        val recentNotes = try { noteRepository.getRecentNotes(10) } catch (_: Exception) { emptyList() }
        val unsproutedNotes = recentNotes.filter { !it.hasSproutReport() }
        if (unsproutedNotes.isNotEmpty()) {
            val latestUnsprouted = unsproutedNotes.first()
            out.add(Recommendation.ActionSuggestion(
                actionType = SuggestedAction.SPROUT_LATEST_NOTE,
                targetId = latestUnsprouted.id.toString(),
                title = "笔记待发芽",
                description = "「${latestUnsprouted.title.ifBlank { latestUnsprouted.content.take(20) }}」还没有发芽分析，让它生长出更多洞察？",
                priority = 7,
                actionText = "立即发芽",
            ))
        }

        // 笔记较多但可能没有整理到知识库
        val totalNotes = try {
            noteRepository.countAll().firstOrNull() ?: 0L
        } catch (_: Exception) { 0L }
        if (totalNotes >= 20) {
            out.add(Recommendation.ActionSuggestion(
                actionType = SuggestedAction.ORGANIZE_NOTES,
                title = "整理笔记",
                description = "你已有 ${totalNotes} 条笔记了，考虑整理到知识库中方便后续检索和关联。",
                priority = 2,
                actionText = "去整理",
            ))
        }

        // 如果经常用 AI 对话但没用过技能
        val aiMsgCount = behaviorTracker.getEventCount(BehaviorEvent.AI_MESSAGE_SENT, sinceHours = 168)
        val skillUsedCount = behaviorTracker.getEventCount(BehaviorEvent.SKILL_USED, sinceHours = 168)
        if (aiMsgCount >= 5 && skillUsedCount == 0) {
            out.add(Recommendation.ActionSuggestion(
                actionType = SuggestedAction.TRY_EDITOR_TEAM,
                title = "试试 AI 技能",
                description = "你经常和 AI 对话，试试「点评」「拷问」「润色」技能，让 AI 更精准地帮你处理内容。",
                priority = 3,
                actionText = "体验技能",
            ))
        }
    }

    private fun addFrequencyBasedRecommendations(out: MutableList<Recommendation>) {
        val cal = Calendar.getInstance(Locale.CHINA)
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
        val dayOfWeek = cal.get(Calendar.DAY_OF_MONTH)

        // 周五下午 → 周报提醒
        if (cal.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY && hour in 14..18) {
            out.add(Recommendation.ActionSuggestion(
                actionType = SuggestedAction.REVIEW_WEEKLY,
                title = "本周回顾",
                description = "周五了！生成本周的使用报告，回顾一周的思考和收获。",
                priority = 6,
                actionText = "生成周报",
            ))
        }

        // 每月1日 → 月度总结暗示
        if (dayOfMonth == 1) {
            out.add(Recommendation.ActionSuggestion(
                actionType = SuggestedAction.BACKUP_DATA,
                title = "新月备份",
                description = "月初了，建议备份一下你的笔记和数据，开启新的记录周期。",
                priority = 4,
                actionText = "备份数据",
            ))
        }
    }

    private fun buildDailyInsight(notes: Int, chats: Int, recordings: Int): String {
        return buildString {
            when {
                notes >= 5 -> append("今天非常活跃！创建了 $notes 条笔记，")
                notes >= 2 -> append("今天记录了不少内容，")
                else -> append("今天还在起步阶段，")
            }
            if (chats >= 3) append("和 AI 进行了 $chats 轮对话，")
            if (recordings >= 1) append("还有录音记录。")
            append("保持记录，AI 会越来越懂你。")
        }
    }

    private fun buildWeeklySummary(notes: Int, chats: Int, days: Int): String {
        return buildString {
            append("本周共创建 $notes 条笔记，")
            if (chats > 0) append("与 AI 对话 $chats 次，")
            append("活跃约 $days 天。")
            if (notes >= 10) append("你的知识库正在稳步增长！")
            else if (notes >= 5) append("保持节奏，每周都在进步。")
            else append("多记录一些想法，让 AI 更好地理解你。")
        }
    }
}
