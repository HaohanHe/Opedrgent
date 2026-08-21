package top.hsyscn.opedrgent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import top.hsyscn.opedrgent.MainActivity
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.utils.DebugLog

/**
 * MediaProjection 前台服务 -- Android 14+ 要求在调用 getMediaProjection() 之前
 * 必须启动 FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION 类型的前台服务，否则抛出
 * SecurityException。
 *
 * 流程：RecordingTab 拿到用户授权后 -> start(context, resultCode, data) ->
 * onStartCommand -> startForeground(MEDIA_PROJECTION) -> getMediaProjection() ->
 * 通过 onReady 回调将 MediaProjection 传回 UI 层。
 */
class MediaProjectionService : Service() {

    companion object {
        private const val TAG = "MediaProjectionService"
        private const val CHANNEL_ID = "media_projection_channel"
        private const val NOTIFICATION_ID = 2001
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        @Volatile
        var mediaProjection: MediaProjection? = null

        /** MediaProjection 获取成功时回调（主线程）。 */
        var onReady: ((MediaProjection) -> Unit)? = null

        /** MediaProjection 获取失败时回调（主线程），参数为错误描述。 */
        var onError: ((String) -> Unit)? = null

        /**
         * 启动前台服务并获取 MediaProjection。
         * 调用前请先设置 [onReady]（必要时设置 [onError]）。
         */
        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, MediaProjectionService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** 停止服务并清理静态引用。 */
        fun stop(context: Context) {
            context.stopService(Intent(context, MediaProjectionService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        // 关键：必须先 startForeground，再调用 getMediaProjection，否则 Android 14+ 抛 SecurityException
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        if (data == null) {
            DebugLog.e(TAG, "onStartCommand: result data is null")
            onError?.invoke(getString(R.string.notif_media_projection_error_no_data))
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = manager.getMediaProjection(resultCode, data)
            if (projection != null) {
                mediaProjection = projection
                DebugLog.i(TAG, "MediaProjection obtained successfully")
                onReady?.invoke(projection)
            } else {
                DebugLog.e(TAG, "getMediaProjection returned null")
                onError?.invoke(getString(R.string.notif_media_projection_error_no_permission))
                stopSelf()
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "getMediaProjection failed: ${e.message}", e)
            onError?.invoke(getString(R.string.notif_media_projection_error_failed, e.message ?: ""))
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
        mediaProjection = null
        DebugLog.i(TAG, "Service destroyed")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_media_projection_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notif_channel_media_projection_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(getString(R.string.notif_media_projection_title))
            .setContentText(getString(R.string.notif_media_projection_text))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
