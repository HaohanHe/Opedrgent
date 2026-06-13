package top.hsyscn.opedrgent.storage

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import top.hsyscn.opedrgent.ui.PartnerPersona
import java.util.Calendar

/**
 * 多人格模式自动检测器。
 *
 * 基于时间、内容关键词、日历事件三维信号，自动推断当前最合适的 PartnerPersona。
 * 每次调用实时计算，不依赖持久化状态。
 */
object PersonaDetector {

    /**
     * 基于多维信号自动推断当前应使用的 Persona。
     *
     * @param context 用于查询日历权限和内容
     * @param recentItems 最近的索引条目（用于内容分析）
     * @param currentContent 当前正在输入/录音的内容文本
     * @return 推断出的最佳 Persona
     */
    fun detect(
        context: Context,
        recentItems: List<IndexedItem> = emptyList(),
        currentContent: String? = null,
    ): PartnerPersona {
        val scores = mutableMapOf<PartnerPersona, Float>(
            PartnerPersona.LIFE to 0f,
            PartnerPersona.WORK to 0f,
            PartnerPersona.CREATIVE to 0f,
        )

        // 1. 时间信号 (权重: 30%)
        addTimeSignal(scores)

        // 2. 内容信号 (权重: 40%) -- 分析最近记录的关键词
        if (recentItems.isNotEmpty() || !currentContent.isNullOrBlank()) {
            addContentSignal(scores, recentItems, currentContent)
        }

        // 3. 日历信号 (权重: 30%) -- 检查日历中是否有会议
        addCalendarSignal(context, scores)

        // 返回得分最高的
        return scores.maxByOrNull { it.value }?.key ?: PartnerPersona.LIFE
    }

    /**
     * 生成检测原因说明，用于 UI 展示给用户。
     *
     * @return 人类可读的原因描述，如 "工作时段 + 检测到会议关键词"
     */
    fun explainReason(
        context: Context,
        recentItems: List<IndexedItem> = emptyList(),
        currentContent: String? = null,
    ): String {
        val reasons = mutableListOf<String>()

        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val isWeekend = (dayOfWeek == Calendar.SUNDAY || dayOfWeek == Calendar.SATURDAY)
        val isWorkHours = (hour in 9..18) && !isWeekend
        val isEvening = (hour in 19..23)
        val isLateNight = (hour in 0..6)

        when {
            isWorkHours -> reasons.add("工作时段")
            isEvening -> reasons.add("晚间休闲")
            isLateNight -> reasons.add("深夜时光")
            isWeekend -> reasons.add("周末时间")
            else -> reasons.add("日间休息")
        }

        // 内容信号分析
        if (recentItems.isNotEmpty() || !currentContent.isNullOrBlank()) {
            val allTexts = recentItems.map { it.title + " " + it.summary }.toMutableList()
            if (!currentContent.isNullOrBlank()) allTexts.add(currentContent)
            val combinedText = allTexts.joinToString(" ").lowercase()

            val workKeywords = listOf(
                "会议", "开会", "待办", "deadline", "项目", "需求", "上线",
                "客户", "老板", "同事", "汇报", "ppt", "文档", "评审",
                "schedule", "meeting", "task", "plan", "report", "kpi",
                "周报", "日报", "迭代", "部署", "测试", "bug", "feature",
                "产品", "运营", "推广", "预算", "审批", "合同",
            )
            val lifeKeywords = listOf(
                "家人", "朋友", "爸妈", "孩子", "宝宝", "老公", "老婆",
                "吃饭", "做饭", "买菜", "运动", "跑步", "健身",
                "电影", "音乐", "旅行", "旅游", "逛街", "购物",
                "生日", "节日", "聚会", "聚餐", "周末", "放假",
                "心情", "开心", "难过", "累", "休息", "睡觉",
                "love", "family", "dinner", "lunch", "weekend",
            )
            val creativeKeywords = listOf(
                "想法", "灵感", "创意", "构思", "写作", "文章",
                "故事", "小说", "诗歌", "设计", "画", "摄影",
                "思考", "反思", "洞察", "观点", "理论",
                "如果", "假设", "想象", "可能", "也许",
                "idea", "think", "write", "create", "design",
                "为什么", "怎么", "意味着", "本质", "底层",
            )

            val workCount = workKeywords.count { it.lowercase() in combinedText }
            val lifeCount = lifeKeywords.count { it.lowercase() in combinedText }
            val creativeCount = creativeKeywords.count { it.lowercase() in combinedText }

            when {
                workCount > 0 && workCount >= lifeCount && workCount >= creativeCount ->
                    reasons.add("检测到工作关键词")
                lifeCount > 0 && lifeCount >= workCount && lifeCount >= creativeCount ->
                    reasons.add("检测到生活关键词")
                creativeCount > 0 ->
                    reasons.add("检测到创作关键词")
            }
        }

        // 日历信号
        try {
            val hasPermission = ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_CALENDAR
            ) == PackageManager.PERMISSION_GRANTED

            if (hasPermission) {
                val now = System.currentTimeMillis()
                val twoHoursLater = now + 2 * 60 * 60 * 1000L

                val cursor = context.contentResolver.query(
                    CalendarContract.Events.CONTENT_URI,
                    arrayOf(CalendarContract.Events.TITLE),
                    "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?",
                    arrayOf(now.toString(), twoHoursLater.toString()),
                    null,
                )

                if (cursor != null && cursor.count > 0) {
                    reasons.add("即将有日程安排")
                }
                cursor?.close()
            }
        } catch (_: Exception) {
            // 日历查询失败不影响其他信号
        }

        return if (reasons.size <= 1) reasons.firstOrNull() ?: "默认模式"
        else reasons.joinToString(" + ")
    }

    /**
     * 时间信号：工作时间倾向 Work，晚间/周末倾向 Life/Creative
     */
    private fun addTimeSignal(scores: MutableMap<PartnerPersona, Float>) {
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)  // 1=周日, 7=周六

        val isWeekend = (dayOfWeek == Calendar.SUNDAY || dayOfWeek == Calendar.SATURDAY)
        val isWorkHours = (hour in 9..18) && !isWeekend
        val isEvening = (hour in 19..23)
        val isLateNight = (hour in 0..6)

        when {
            isWorkHours -> {
                scores[PartnerPersona.WORK] = scores[PartnerPersona.WORK]!! + 35f
            }
            isEvening -> {
                scores[PartnerPersona.LIFE] = scores[PartnerPersona.LIFE]!! + 25f
                scores[PartnerPersona.CREATIVE] = scores[PartnerPersona.CREATIVE]!! + 15f
            }
            isLateNight -> {
                scores[PartnerPersona.LIFE] = scores[PartnerPersona.LIFE]!! + 20f
                scores[PartnerPersona.CREATIVE] = scores[PartnerPersona.CREATIVE]!! + 25f  // 夜间创作欲强
            }
            isWeekend -> {
                scores[PartnerPersona.LIFE] = scores[PartnerPersona.LIFE]!! + 30f
                scores[PartnerPersona.CREATIVE] = scores[PartnerPersona.CREATIVE]!! + 10f
            }
            else -> {  // 白天非工作时间（如午休）
                scores[PartnerPersona.LIFE] = scores[PartnerPersona.LIFE]!! + 15f
            }
        }
    }

    /**
     * 内容信号：分析关键词判断内容类型
     */
    private fun addContentSignal(
        scores: MutableMap<PartnerPersona, Float>,
        recentItems: List<IndexedItem>,
        currentContent: String?,
    ) {
        // 合并所有文本
        val allTexts = recentItems.map { it.title + " " + it.summary }.toMutableList()
        if (!currentContent.isNullOrBlank()) allTexts.add(currentContent)
        val combinedText = allTexts.joinToString(" ")

        // 工作关键词库
        val workKeywords = listOf(
            "会议", "开会", "待办", "deadline", "项目", "需求", "上线",
            "客户", "老板", "同事", "汇报", "ppt", "文档", "评审",
            "schedule", "meeting", "task", "plan", "report", "kpi",
            "周报", "日报", "迭代", "部署", "测试", "bug", "feature",
            "产品", "运营", "推广", "预算", "审批", "合同",
        )

        // 生活关键词库
        val lifeKeywords = listOf(
            "家人", "朋友", "爸妈", "孩子", "宝宝", "老公", "老婆",
            "吃饭", "做饭", "买菜", "运动", "跑步", "健身",
            "电影", "音乐", "旅行", "旅游", "逛街", "购物",
            "生日", "节日", "聚会", "聚餐", "周末", "放假",
            "心情", "开心", "难过", "累", "休息", "睡觉",
            "love", "family", "dinner", "lunch", "weekend",
        )

        // 创作关键词库
        val creativeKeywords = listOf(
            "想法", "灵感", "创意", "构思", "写作", "文章",
            "故事", "小说", "诗歌", "设计", "画", "摄影",
            "思考", "反思", "洞察", "观点", "理论",
            "如果", "假设", "想象", "可能", "也许",
            "idea", "think", "write", "create", "design",
            "为什么", "怎么", "意味着", "本质", "底层",
        )

        val textLower = combinedText.lowercase()

        val workScore = workKeywords.count { it.lowercase() in textLower } * 8f
        val lifeScore = lifeKeywords.count { it.lowercase() in textLower } * 8f
        val creativeScore = creativeKeywords.count { it.lowercase() in textLower } * 8f

        scores[PartnerPersona.WORK] = scores[PartnerPersona.WORK]!! + workScore
        scores[PartnerPersona.LIFE] = scores[PartnerPersona.LIFE]!! + lifeScore
        scores[PartnerPersona.CREATIVE] = scores[PartnerPersona.CREATIVE]!! + creativeScore
    }

    /**
     * 日历信号：检查未来 2 小时内是否有会议
     */
    private fun addCalendarSignal(context: Context, scores: MutableMap<PartnerPersona, Float>) {
        try {
            // 尝试查询日历（如果没有权限则静默跳过）
            val hasPermission = ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_CALENDAR
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasPermission) return

            val now = System.currentTimeMillis()
            val twoHoursLater = now + 2 * 60 * 60 * 1000L

            val cursor = context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events.TITLE),
                "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?",
                arrayOf(now.toString(), twoHoursLater.toString()),
                null,
            ) ?: return

            val eventCount = cursor.count
            cursor.close()

            if (eventCount > 0) {
                scores[PartnerPersona.WORK] = scores[PartnerPersona.WORK]!! + 30f
            }
        } catch (_: Exception) {
            // 日历查询失败不影响其他信号
        }
    }
}
