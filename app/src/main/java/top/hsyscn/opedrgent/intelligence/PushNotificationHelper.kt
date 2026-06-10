package top.hsyscn.opedrgent.intelligence

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import top.hsyscn.opedrgent.R

/**
 * 格式化推荐内容 — 用于 App 内推荐卡片展示。
 *
 * 将 [Recommendation] 数据模型转换为 UI 友好的格式化数据，
 * 包含标题、正文、图标 emoji、颜色和操作按钮文案。
 */
data class FormattedRecommendation(
    val title: String,
    val body: String,
    val icon: String,       // emoji 图标
    val color: Long,        // ARGB 颜色值
    val actionLabel: String,
)

/**
 * 推送通知辅助类 — 负责推荐内容的格式化和可选的系统通知发送。
 *
 * ## 设计原则
 * - **主要用途**：App 内推荐卡片（非系统通知）
 * - **次要用途**：本地系统通知（需要用户授权通知权限）
 * - 所有文本均为中文
 */
class PushNotificationHelper(private val context: Context) {

    companion object {
        /** 通知渠道 ID */
        const val CHANNEL_ID_RECOMMENDATION = "opedrgent_recommendations"
        const val CHANNEL_ID_DAILY = "opedrgent_daily_review"

        /** 通知渠道名称 */
        private const val CHANNEL_NAME_RECOMMENDATION = "智能推荐"
        private const val CHANNEL_NAME_DAILY = "每日回顾"

        /** 各推荐类型对应的视觉样式 */
        private val TYPE_COLORS = mapOf(
            RecommendationType.RELATED_NOTE to 0xFF2B68DEL,     // 蓝色 — 关联笔记
            RecommendationType.ACTION_SUGGESTION to 0xFFE67E22L, // 橙色 — 操作建议
            RecommendationType.CONTEXTUAL_TIP to 0xFF4A9A8AL,   // 青绿 — 上下文提示
            RecommendationType.DAILY_REVIEW to 0xFF9B59B6L,     // 紫色 — 每日回顾
            RecommendationType.WEEKLY_REPORT to 0xFF1ABC9CL,    // 绿色 — 周报
        )

        private val TYPE_ICONS = mapOf(
            RecommendationType.RELATED_NOTE to "",
            RecommendationType.ACTION_SUGGESTION to "",
            RecommendationType.CONTEXTUAL_TIP to "",
            RecommendationType.DAILY_REVIEW to "",
            RecommendationType.WEEKLY_REPORT to "",
        )
    }

    /**
     * 将推荐对象格式化为 UI 可直接使用的结构化数据。
     *
     * @param rec 推荐对象
     * @return 格式化后的推荐内容，包含图标、颜色等 UI 属性
     */
    fun formatRecommendation(rec: Recommendation): FormattedRecommendation {
        val color = TYPE_COLORS[rec.type] ?: 0xFF2B68DEL
        val icon = TYPE_ICONS[rec.type] ?: ""

        return when (rec) {
            is Recommendation.NoteRecommendation -> FormattedRecommendation(
                title = rec.title,
                body = "${rec.reason}\n${rec.description}",
                icon = icon,
                color = color,
                actionLabel = rec.actionText,
            )
            is Recommendation.ActionSuggestion -> {
                val actionDesc = when (rec.actionType) {
                    SuggestedAction.SPROUT_LATEST_NOTE -> "让 AI 帮你深度分析这条笔记"
                    SuggestedAction.REVIEW_WEEKLY -> "回顾本周的思考和收获"
                    SuggestedAction.ORGANIZE_NOTES -> "将笔记归类到知识库"
                    SuggestedAction.TRY_EDITOR_TEAM -> "体验点评/拷问/润色技能"
                    SuggestedAction.BACKUP_DATA -> "导出备份你的数据"
                    SuggestedAction.SET_REMINDER -> "设置定时提醒不错过重要事项"
                    SuggestedAction.CONNECT_KB -> "关联知识库文档增强 AI 能力"
                }
                FormattedRecommendation(
                    title = rec.title,
                    body = if (rec.description.isNotBlank()) rec.description else actionDesc,
                    icon = icon,
                    color = color,
                    actionLabel = rec.actionText,
                )
            }
            is Recommendation.ContextualTip -> FormattedRecommendation(
                title = rec.title,
                body = rec.description,
                icon = icon,
                color = color,
                actionLabel = rec.actionText,
            )
        }
    }

    /**
     * 发送本地系统通知。
     *
     * 注意：需要用户在系统设置中授予通知权限。
     *
     * @param title 通知标题
     * @param body 通知正文
     * @param channelId 通知渠道 ID
     */
    fun sendLocalNotification(title: String, body: String, channelId: String = CHANNEL_ID_RECOMMENDATION) {
        ensureChannelsExist()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 使用唯一的通知 ID（基于时间戳避免重复）
        val notificationId = (System.currentTimeMillis() % Integer.MAX_VALUE).toInt()

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(notificationId, notification)
    }

    /**
     * 发送每日回顾通知。
     */
    fun sendDailyReviewNotification(dailyReview: top.hsyscn.opedrgent.intelligence.DailyReview) {
        val title = "今日回顾 — ${dailyReview.date}"
        val body = buildString {
            append("今日记录 ${dailyReview.notesCreated} 条笔记")
            if (dailyReview.aiChats > 0) append("，AI 对话 ${dailyReview.aiChats} 次")
            if (dailyReview.recordings > 0) append("，录音 ${dailyReview.recordings} 次")
            append(".")
            dailyReview.insight?.let { append("\n$it") }
        }
        sendLocalNotification(title, body, CHANNEL_ID_DAILY)
    }

    /**
     * 确保通知渠道已创建（Android 8.0+ 要求）。
     */
    private fun ensureChannelsExist() {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (notificationManager.getNotificationChannel(CHANNEL_ID_RECOMMENDATION) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID_RECOMMENDATION,
                CHANNEL_NAME_RECOMMENDATION,
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Opedrgent 智能推荐通知" }
            notificationManager.createNotificationChannel(channel)
        }

        if (notificationManager.getNotificationChannel(CHANNEL_ID_DAILY) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID_DAILY,
                CHANNEL_NAME_DAILY,
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "每日回顾总结通知" }
            notificationManager.createNotificationChannel(channel)
        }
    }
}
