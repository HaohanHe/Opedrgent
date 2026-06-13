package top.hsyscn.opedrgent.storage

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import top.hsyscn.opedrgent.MainActivity

/**
 * 统一通知工具类 -- 负责创建通知渠道和发送各类业务通知。
 *
 * 所有通知文案遵循"礼物感"原则：温暖自然，不使用机器味措辞。
 */
object NotificationHelper {

    // ---- 通知 ID 常量 ----
    const val NOTIF_ID_SPROUT = 1001
    const val NOTIF_ID_DIGEST = 1002
    const val NOTIF_ID_FEEDBACK = 1003
    const val NOTIF_ID_AUTO_SAVE = 1004

    // ---- 渠道 ID 常量 ----
    private const val CHANNEL_ID_SPROUT_COMPLETE = "sprout_complete"
    private const val CHANNEL_ID_DAILY_DIGEST = "daily_digest"
    private const val CHANNEL_ID_WARM_FEEDBACK = "warm_feedback"
    private const val CHANNEL_ID_AUTO_SAVE = "auto_save"

    /**
     * 确保所有通知渠道已创建（幂等，可重复调用）。
     * 建议在 Application.onCreate 或 MainActivity 启动时调用一次即可。
     */
    fun createAllChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channels = listOf(
            NotificationChannel(
                CHANNEL_ID_SPROUT_COMPLETE,
                "发芽报告",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "发芽报告生成完成时提醒"
                setShowBadge(true)
            },
            NotificationChannel(
                CHANNEL_ID_DAILY_DIGEST,
                "每日收获",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "每日内容回顾与收获摘要"
                setShowBadge(true)
            },
            NotificationChannel(
                CHANNEL_ID_WARM_FEEDBACK,
                "点评",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = "AI 对你的笔记进行温暖点评"
                setShowBadge(false)
            },
            NotificationChannel(
                CHANNEL_ID_AUTO_SAVE,
                "自动保存",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "笔记自动保存成功提示"
                setShowBadge(false)
            },
        )

        channels.forEach { channel ->
            manager.createNotificationChannel(channel)
        }
    }

    // ==================== 公开方法 ====================

    /**
     * 发送发芽报告完成通知。
     *
     * @param context   上下文
     * @param report    发芽报告记录，用于提取摘要展示
     */
    fun showSproutNotification(context: Context, report: SproutReportRecord) {
        createAllChannels(context)

        val summaryPreview = report.summary.take(50)
        val contentText = if (report.summary.length > 50) {
            "$summaryPreview...点击查看完整报告"
        } else {
            "$summaryPreview -- 点击查看完整报告"
        }

        val pendingIntent = buildLaunchPendingIntent(context)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_SPROUT_COMPLETE)
            .setSmallIcon(resolveSmallIcon(context))
            .setContentTitle("发芽报告已生成")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        notify(context, NOTIF_ID_SPROUT, builder.build())
    }

    /**
     * 发送每日收获摘要通知。
     *
     * @param context      上下文
     * @param noteCount    昨日记录的笔记条数
     * @param topSnippet   最热/最新一条内容的片段
     * @param sproutCount  新生成的发芽报告数量
     */
    fun showDailyDigestNotification(
        context: Context,
        noteCount: Int,
        topSnippet: String,
        sproutCount: Int,
        anniversarySnippet: String? = null,
        anniversaryDaysAgo: Int? = null,
    ) {
        createAllChannels(context)

        val snippetPart = topSnippet.take(30)
        val sproutPart = if (sproutCount > 0) " 有 $sproutCount 份新发芽报告" else ""
        var contentText = "昨天记录了 $noteCount 条内容。$snippetPart...$sproutPart"

        // 追加周年回顾信息
        if (anniversarySnippet != null && anniversaryDaysAgo != null) {
            contentText += "\n---\n${anniversaryDaysAgo}天前的今天：$anniversarySnippet"
        }

        val pendingIntent = buildLaunchPendingIntent(context)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_DAILY_DIGEST)
            .setSmallIcon(resolveSmallIcon(context))
            .setContentTitle("今日收获")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        notify(context, NOTIF_ID_DIGEST, builder.build())
    }

    /**
     * 发送 AI 温暖点评通知（纯展示型，无点击跳转）。
     *
     * @param context  上下文
     * @param feedback AI 点评文本
     */
    fun showWarmFeedback(context: Context, feedback: String) {
        createAllChannels(context)

        val contentText = feedback.take(50)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_WARM_FEEDBACK)
            .setSmallIcon(resolveSmallIcon(context))
            .setContentTitle("AI 点评")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(feedback))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)

        notify(context, NOTIF_ID_FEEDBACK, builder.build())
    }

    /**
     * 发送自动保存成功通知。
     *
     * @param context  上下文
     * @param noteId   被保存的笔记 ID
     * @param title    笔记标题
     * @param preview  内容预览文本
     */
    fun showAutoSaveNote(context: Context, noteId: Long, title: String, preview: String) {
        createAllChannels(context)

        val previewPart = preview.take(30)
        val contentText = "$title -- ${previewPart}..."

        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("open_note_id", noteId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            noteId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_AUTO_SAVE)
            .setSmallIcon(resolveSmallIcon(context))
            .setContentTitle("已自动保存")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        notify(context, NOTIF_ID_AUTO_SAVE, builder.build())
    }

    // ==================== 内部辅助方法 ====================

    /** 构建跳转到应用首页的 PendingIntent */
    private fun buildLaunchPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 解析小图标资源 ID。
     * 优先使用 R.drawable.ic_notification，不存在则回退到系统默认图标。
     */
    private fun resolveSmallIcon(context: Context): Int {
        return try {
            val resId = context.resources.getIdentifier(
                "ic_notification", "drawable", context.packageName,
            )
            if (resId != 0) resId else android.R.drawable.ic_dialog_info
        } catch (_: Exception) {
            android.R.drawable.ic_dialog_info
        }
    }

    /** 通过 NotificationManager 发送通知 */
    private fun notify(context: Context, id: Int, notification: android.app.Notification) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(id, notification)
    }
}
