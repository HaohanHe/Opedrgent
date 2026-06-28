package top.hsyscn.opedrgent.service

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import top.hsyscn.opedrgent.MainActivity
import top.hsyscn.opedrgent.utils.DebugLog

/**
 * 录音前台服务 -- 保证麦克风录制在息屏/切后台时继续运行。
 *
 * 启动后会：
 * 1. 以 foregroundServiceType=microphone 提升为前台服务
 * 2. 持有 PARTIAL_WAKE_LOCK 防止 CPU 休眠
 * 3. 显示持续通知表明录音进行中
 */
class RecordingForegroundService : Service() {

    companion object {
        private const val TAG = "RecordingFgService"
        private const val CHANNEL_ID = "recording_channel"
        private const val NOTIFICATION_ID = 3001
        private const val ACTION_START = "top.hsyscn.opedrgent.action.START_RECORDING_FG"
        private const val ACTION_STOP = "top.hsyscn.opedrgent.action.STOP_RECORDING_FG"
        private const val ACTION_UPDATE_TIMER = "top.hsyscn.opedrgent.action.UPDATE_RECORDING_TIMER"
        private const val EXTRA_TIMER_TEXT = "extra_timer_text"
        private const val EXTRA_MODE = "extra_mode"

        private var wakeLock: PowerManager.WakeLock? = null

        fun start(context: Context, mode: String = "录音") {
            val intent = Intent(context, RecordingForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MODE, mode)
            }

            // Android 14+ 限制：从后台启动 microphone 类型前台服务会被系统拒绝。
            // 如果检测到应用不在前台，先通过 Intent 拉起 MainActivity，再由其启动服务。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !isAppInForeground(context)) {
                DebugLog.w(TAG, "App is not in foreground; launching MainActivity before starting recording service")
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("start_recording", true)
                    putExtra("recording_mode", mode)
                }
                context.startActivity(launchIntent)
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        private fun isAppInForeground(context: Context): Boolean {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val tasks = am.getRunningTasks(1)
            return tasks.firstOrNull()?.topActivity?.packageName == context.packageName
        }

        fun updateTimer(context: Context, timerText: String) {
            val intent = Intent(context, RecordingForegroundService::class.java).apply {
                action = ACTION_UPDATE_TIMER
                putExtra(EXTRA_TIMER_TEXT, timerText)
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, RecordingForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        private fun acquireWakeLock(context: Context) {
            if (wakeLock?.isHeld == true) return
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Opedrgent:RecordingForeground",
            ).apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L) // 6 hours max recording
            }
            DebugLog.i(TAG, "WakeLock acquired (PARTIAL_WAKE_LOCK, 6h)")
        }

        private fun releaseWakeLock() {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                wakeLock = null
                DebugLog.i(TAG, "WakeLock released")
            }
        }
    }

    private var currentTimerText = "00:00"
    private var currentMode = "录音"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        DebugLog.i(TAG, "RecordingForegroundService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_TIMER -> {
                currentTimerText = intent.getStringExtra(EXTRA_TIMER_TEXT) ?: currentTimerText
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIFICATION_ID, buildNotification())
                return START_STICKY
            }
        }

        currentMode = intent?.getStringExtra(EXTRA_MODE) ?: "录音"
        acquireWakeLock(this)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (e: SecurityException) {
            DebugLog.e(TAG, "Failed to start foreground recording service", e)
            showErrorNotification("录音启动失败，请从应用内重新开始录音")
            releaseWakeLock()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        DebugLog.i(TAG, "RecordingForegroundService destroyed")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "录音服务",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "录音进行中，息屏后继续录制"
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
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RecordingForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("${currentMode}中")
            .setContentText("正在录制 $currentTimerText | 点击返回应用")
            .setSubText("Opedrgent")
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "停止", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun showErrorNotification(message: String) {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("录音服务异常")
            .setContentText(message)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID + 1, notification)
    }
}
