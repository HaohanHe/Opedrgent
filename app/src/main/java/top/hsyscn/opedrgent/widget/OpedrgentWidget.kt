package top.hsyscn.opedrgent.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import top.hsyscn.opedrgent.MainActivity
import top.hsyscn.opedrgent.R

class OpedrgentWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_opedrgent)

            // Main app entry
            val mainIntent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val mainPending = PendingIntent.getActivity(
                context, 0, mainIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_main_button, mainPending)

            // Recording shortcut
            val recordIntent = Intent(context, MainActivity::class.java).apply {
                action = "top.hsyscn.opedrgent.ACTION_MEETING_RECORD"
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val recordPending = PendingIntent.getActivity(
                context, 1, recordIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_record_button, recordPending)

            // AI chat shortcut
            val chatIntent = Intent(context, MainActivity::class.java).apply {
                action = "top.hsyscn.opedrgent.ACTION_NEW_CHAT"
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val chatPending = PendingIntent.getActivity(
                context, 2, chatIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_chat_button, chatPending)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
