package top.hsyscn.opedrgent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import top.hsyscn.opedrgent.MainActivity
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.utils.DebugLog
import java.text.DecimalFormat

/**
 * STT 模型下载前台服务 — 与 ModelDownloadService 并列，使用独立 NotificationChannel/ID，
 * 下载期间在通知栏显示实时进度。
 */
class SttDownloadService : Service() {

    companion object {
        private const val TAG = "SttDownloadService"
        private const val CHANNEL_ID = "stt_model_download_channel"
        private const val NOTIFICATION_ID = 1002
        private const val ACTION_UPDATE = "top.hsyscn.opedrgent.stt_download.UPDATE"
        private const val ACTION_CANCEL = "top.hsyscn.opedrgent.stt_download.CANCEL"
        private const val ACTION_COMPLETE = "top.hsyscn.opedrgent.stt_download.COMPLETE"
        private const val ACTION_FAIL = "top.hsyscn.opedrgent.stt_download.FAIL"

        private val speedFormat = DecimalFormat("#.#")

        fun start(context: Context, modelName: String) {
            val intent = Intent(context, SttDownloadService::class.java).apply {
                putExtra("model_name", modelName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun updateProgress(context: Context, modelName: String, percent: Int, downloadedMb: Int, totalMb: Int, speedBytes: Long) {
            val intent = Intent(context, SttDownloadService::class.java).apply {
                action = ACTION_UPDATE
                putExtra("model_name", modelName)
                putExtra("percent", percent)
                putExtra("downloaded_mb", downloadedMb)
                putExtra("total_mb", totalMb)
                putExtra("speed_bytes", speedBytes)
            }
            context.startService(intent)
        }

        fun complete(context: Context, modelName: String) {
            val intent = Intent(context, SttDownloadService::class.java).apply {
                action = ACTION_COMPLETE
                putExtra("model_name", modelName)
            }
            context.startService(intent)
        }

        fun fail(context: Context, modelName: String, error: String) {
            val intent = Intent(context, SttDownloadService::class.java).apply {
                action = ACTION_FAIL
                putExtra("model_name", modelName)
                putExtra("error", error)
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SttDownloadService::class.java))
        }
    }

    private var modelName: String = ""

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra("model_name")?.let { modelName = it }

        when (intent?.action) {
            ACTION_UPDATE -> {
                val percent = intent.getIntExtra("percent", 0)
                val downloadedMb = intent.getIntExtra("downloaded_mb", 0)
                val totalMb = intent.getIntExtra("total_mb", 0)
                val speedBytes = intent.getLongExtra("speed_bytes", 0L)
                updateNotification(percent, downloadedMb, totalMb, speedBytes)
                return START_NOT_STICKY
            }
            ACTION_COMPLETE -> {
                showCompletedNotification()
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_FAIL -> {
                val error = intent.getStringExtra("error") ?: getString(R.string.notif_unknown_error)
                showFailedNotification(error)
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CANCEL -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        startForeground(NOTIFICATION_ID, buildInitialNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_stt_download_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notif_channel_stt_download_desc)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun pendingIntent(): PendingIntent {
        return PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancelPendingIntent(): PendingIntent {
        return PendingIntent.getService(
            this, 10, Intent(this, SttDownloadService::class.java).apply { action = ACTION_CANCEL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildInitialNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
            .setContentTitle(getString(R.string.notif_model_download_app_title))
            .setContentText(getString(R.string.notif_stt_downloading_named, modelName))
            .setContentIntent(pendingIntent())
            .setProgress(0, 0, true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notif_action_cancel), cancelPendingIntent())
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    private fun updateNotification(percent: Int, downloadedMb: Int, totalMb: Int, speedBytes: Long) {
        val speedText = if (speedBytes > 0) {
            val mbps = speedBytes / (1024f * 1024f)
            "${speedFormat.format(mbps)} MB/s"
        } else ""

        val contentText = buildString {
            append(getString(R.string.notif_stt_download_progress, modelName))
            if (totalMb > 0) append("\n${downloadedMb}MB / ${totalMb}MB")
            if (speedText.isNotEmpty()) append("  $speedText")
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
            .setContentTitle(getString(R.string.notif_model_download_app_title))
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setContentIntent(pendingIntent())
            .setProgress(100, percent, false)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notif_action_cancel), cancelPendingIntent())
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()

        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
    }

    private fun showCompletedNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
            .setContentTitle(getString(R.string.notif_stt_download_complete_title))
            .setContentText(getString(R.string.notif_stt_download_complete_text, modelName))
            .setContentIntent(pendingIntent())
            .setProgress(0, 0, false)
            .setAutoCancel(true)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()

        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
    }

    private fun showFailedNotification(error: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
            .setContentTitle(getString(R.string.notif_stt_download_failed_title))
            .setContentText(getString(R.string.notif_stt_download_failed_text, modelName, error))
            .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.notif_stt_download_failed_big, modelName, error)))
            .setContentIntent(pendingIntent())
            .setProgress(0, 0, false)
            .setAutoCancel(true)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()

        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
    }
}
