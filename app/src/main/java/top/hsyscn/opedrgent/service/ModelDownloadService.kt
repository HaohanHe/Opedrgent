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
import top.hsyscn.opedrgent.llm.DownloadProgress
import top.hsyscn.opedrgent.llm.DownloadStatus
import top.hsyscn.opedrgent.utils.DebugLog
import java.text.DecimalFormat

class ModelDownloadService : Service() {

    companion object {
        const val CHANNEL_ID = "model_download_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_PAUSE = "top.hsyscn.opedrgent.model_download.PAUSE"
        const val ACTION_RESUME = "top.hsyscn.opedrgent.model_download.RESUME"
        const val ACTION_CANCEL = "top.hsyscn.opedrgent.model_download.CANCEL"
        const val ACTION_OPEN_APP = "top.hsyscn.opedrgent.model_download.OPEN_APP"

        private var currentProgress: DownloadProgress? = null
        private var isPaused = false

        fun start(context: Context, modelName: String) {
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                putExtra("model_name", modelName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ModelDownloadService::class.java)
            context.stopService(intent)
        }

        fun updateProgress(context: Context, progress: DownloadProgress) {
            currentProgress = progress
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_UPDATE_PROGRESS
                putExtra("progress_status", progress.status.name)
                putExtra("progress_downloaded", progress.downloadedBytes)
                putExtra("progress_total", progress.totalBytes)
                putExtra("progress_speed", progress.speedBytesPerSec)
                putExtra("progress_model_id", progress.modelId)
                putExtra("progress_error", progress.error)
            }
            context.startService(intent)
        }

        fun updateStatus(context: Context, modelId: String, status: DownloadStatus, error: String? = null) {
            val prev = currentProgress
            currentProgress = DownloadProgress(
                modelId = modelId,
                status = status,
                downloadedBytes = prev?.downloadedBytes ?: 0,
                totalBytes = prev?.totalBytes ?: 0,
                speedBytesPerSec = 0,
                error = error,
            )
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_UPDATE_PROGRESS
                putExtra("progress_status", status.name)
                putExtra("progress_error", error)
                putExtra("progress_model_id", modelId)
            }
            context.startService(intent)
        }

        fun setPaused(paused: Boolean) {
            isPaused = paused
        }

        private const val ACTION_UPDATE_PROGRESS = "top.hsyscn.opedrgent.model_download.UPDATE_PROGRESS"

        private val speedFormat = DecimalFormat("#.#")
    }

    private var modelName: String = ""
    private var notificationManager: NotificationManager? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        notificationManager = getSystemService(NotificationManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra("model_name")?.let { modelName = it }

        when (intent?.action) {
            ACTION_PAUSE -> {
                isPaused = true
                updateNotification()
                return START_NOT_STICKY
            }
            ACTION_RESUME -> {
                isPaused = false
                updateNotification()
                return START_NOT_STICKY
            }
            ACTION_CANCEL -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_PROGRESS -> {
                parseAndUpdateProgress(intent)
                updateNotification()
                val status = currentProgress?.status
                if (status == DownloadStatus.COMPLETED || status == DownloadStatus.FAILED || status == DownloadStatus.CANCELLED) {
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
        }

        startForeground(NOTIFICATION_ID, buildDownloadingNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
        currentProgress = null
        isPaused = false
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "模型下载",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "显示模型下载进度"
                setShowBadge(true)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun parseAndUpdateProgress(intent: Intent) {
        val statusStr = intent.getStringExtra("progress_status") ?: return
        val status = try { DownloadStatus.valueOf(statusStr) } catch (_: Exception) { return }
        val prev = currentProgress
        currentProgress = DownloadProgress(
            modelId = intent.getStringExtra("progress_model_id") ?: prev?.modelId ?: "",
            status = status,
            downloadedBytes = intent.getLongExtra("progress_downloaded", prev?.downloadedBytes ?: 0L),
            totalBytes = intent.getLongExtra("progress_total", prev?.totalBytes ?: 0L),
            speedBytesPerSec = intent.getLongExtra("progress_speed", 0L),
            error = intent.getStringExtra("progress_error"),
            filePath = prev?.filePath,
        )
    }

    private fun updateNotification() {
        val progress = currentProgress
        if (progress == null) {
            notificationManager?.notify(NOTIFICATION_ID, buildIdleNotification())
            return
        }
        when (progress.status) {
            DownloadStatus.COMPLETED -> notificationManager?.notify(NOTIFICATION_ID, buildCompletedNotification(progress))
            DownloadStatus.FAILED -> notificationManager?.notify(NOTIFICATION_ID, buildFailedNotification(progress))
            DownloadStatus.CANCELLED -> notificationManager?.notify(NOTIFICATION_ID, buildCancelledNotification())
            else -> notificationManager?.notify(NOTIFICATION_ID, buildProgressNotification(progress))
        }
    }

    private fun buildIdleNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Opedrgent 模型下载")
            .setContentText("准备下载...")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun buildDownloadingNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val pauseIntent = PendingIntent.getService(
            this, 1, Intent(this, ModelDownloadService::class.java).apply { action = ACTION_PAUSE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancelIntent = PendingIntent.getService(
            this, 2, Intent(this, ModelDownloadService::class.java).apply { action = ACTION_CANCEL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val largeIcon = BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setLargeIcon(largeIcon)
            .setContentTitle("Opedrgent")
            .setContentText(if (modelName.isNotEmpty()) "正在下载: $modelName" else "正在下载...")
            .setContentIntent(pendingIntent)
            .setProgress(0, 0, true)
            .addAction(android.R.drawable.ic_media_pause, "暂停", pauseIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "取消", cancelIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    private fun buildProgressNotification(progress: DownloadProgress): Notification {
        val displayName = getModelDisplayName(progress.modelId)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val largeIcon = BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)

        val percent = progress.progressPercent.toInt()
        val speedText = formatSpeed(progress.speedBytesPerSec)
        val sizeText = "${formatSize(progress.downloadedBytes)} / ${formatSize(progress.totalBytes)}"
        val contentText = buildString {
            append("正在下载: $displayName\n")
            append("$sizeText  $speedText")
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setLargeIcon(largeIcon)
            .setContentTitle("Opedrgent")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setContentIntent(pendingIntent)
            .setProgress(100, percent, false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        if (isPaused) {
            val resumeIntent = PendingIntent.getService(
                this, 3, Intent(this, ModelDownloadService::class.java).apply { action = ACTION_RESUME },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val cancelIntent = PendingIntent.getService(
                this, 2, Intent(this, ModelDownloadService::class.java).apply { action = ACTION_CANCEL },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(android.R.drawable.ic_media_play, "继续", resumeIntent)
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "取消", cancelIntent)
            builder.setSubText("已暂停")
        } else {
            val pauseIntent = PendingIntent.getService(
                this, 1, Intent(this, ModelDownloadService::class.java).apply { action = ACTION_PAUSE },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val cancelIntent = PendingIntent.getService(
                this, 2, Intent(this, ModelDownloadService::class.java).apply { action = ACTION_CANCEL },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(android.R.drawable.ic_media_pause, "暂停", pauseIntent)
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "取消", cancelIntent)
        }

        builder.setOngoing(true)
        return builder.build()
    }

    private fun buildCompletedNotification(progress: DownloadProgress): Notification {
        val displayName = getModelDisplayName(progress.modelId)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val largeIcon = BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setLargeIcon(largeIcon)
            .setContentTitle("模型下载完成")
            .setContentText("$displayName 已下载完成 (${formatSize(progress.downloadedBytes)})")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$displayName 已下载完成\n大小: ${formatSize(progress.downloadedBytes)}"))
            .setContentIntent(pendingIntent)
            .setProgress(0, 0, false)
            .setAutoCancel(true)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
    }

    private fun buildFailedNotification(progress: DownloadProgress): Notification {
        val displayName = getModelDisplayName(progress.modelId)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val largeIcon = BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
        val errorMsg = progress.error ?: "未知错误"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setLargeIcon(largeIcon)
            .setContentTitle("模型下载失败")
            .setContentText("$displayName 下载失败: $errorMsg")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$displayName 下载失败\n原因: $errorMsg"))
            .setContentIntent(pendingIntent)
            .setProgress(0, 0, false)
            .setAutoCancel(true)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
    }

    private fun buildCancelledNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val largeIcon = BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setLargeIcon(largeIcon)
            .setContentTitle("模型下载已取消")
            .setContentText("下载已被用户取消")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun getModelDisplayName(modelId: String): String {
        return try {
            Class.forName("top.hsyscn.opedrgent.llm.AvailableLocalModels")
                .getMethod("findById", String::class.java)
                .invoke(null, modelId)?.let { info ->
                    info::class.java.getDeclaredField("displayName").get(info) as? String
                } ?: modelId
        } catch (_: Exception) {
            modelId
        }
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        return if (bytesPerSec <= 0) "" else {
            val mbps = bytesPerSec / (1024f * 1024f)
            "${speedFormat.format(mbps)} MB/s"
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> {
                val mb = bytes / (1024f * 1024f)
                "${DecimalFormat("#,##0.#").format(mb)} MB"
            }
        }
    }
}
