package top.hsyscn.opedrgent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import top.hsyscn.opedrgent.MainActivity
import top.hsyscn.opedrgent.utils.DebugLog

/**
 * 悬浮窗服务 -- 手机内录时显示小浮窗，包含计时器、暂停/停止按钮。
 * 用户可在录制期间切换到其他应用，浮窗始终保持可见。
 */
class FloatingWindowService : Service() {

    companion object {
        private const val TAG = "FloatingWindow"
        private const val CHANNEL_ID = "floating_window_channel"
        private const val NOTIFICATION_ID = 2002

        @Volatile
        var isRunning = false

        /** 外部回调：暂停/恢复 */
        var onPauseResume: (() -> Unit)? = null

        /** 外部回调：停止录制 */
        var onStop: (() -> Unit)? = null

        /** 外部回调：关闭悬浮窗（不触发停止） */
        var onDismiss: (() -> Unit)? = null

        private var instance: FloatingWindowService? = null

        fun updateTimer(text: String) {
            instance?.timerView?.post { instance?.timerView?.text = text }
        }

        fun updatePauseState(isPaused: Boolean) {
            instance?.let { svc ->
                svc.pauseBtn?.post {
                    svc.pauseBtn?.setImageResource(
                        if (isPaused) android.R.drawable.ic_media_play
                        else android.R.drawable.ic_media_pause
                    )
                }
            }
        }

        fun start(context: Context) {
            val intent = Intent(context, FloatingWindowService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingWindowService::class.java))
        }
    }

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var timerView: TextView? = null
    private var pauseBtn: ImageView? = null
    private var isPaused = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning = true
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        createFloatingWindow()
        DebugLog.i(TAG, "FloatingWindowService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        instance = null
        try {
            windowManager?.removeView(floatingView)
        } catch (_: Exception) {}
        floatingView = null
        DebugLog.i(TAG, "FloatingWindowService destroyed")
    }

    private fun createFloatingWindow() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 20
            y = 200
        }

        // 构建悬浮窗 UI
        floatingView = createFloatingView()

        try {
            windowManager?.addView(floatingView, params)
            // 设置拖拽
            setupDrag(floatingView!!, params)
        } catch (e: Exception) {
            DebugLog.e(TAG, "Failed to add floating window: ${e.message}", e)
            stopSelf()
        }
    }

    private fun createFloatingView(): View {
        val dp = resources.displayMetrics.density

        // 根容器
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xF0222222.toInt())
                cornerRadius = 16 * dp
            }
        }

        // 计时器
        timerView = TextView(this).apply {
            text = "00:00"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
        }
        root.addView(timerView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = (4 * dp).toInt() })

        // 按钮行
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        // 暂停/恢复按钮
        pauseBtn = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_media_pause)
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            setOnClickListener {
                isPaused = !isPaused
                updatePauseState(isPaused)
                onPauseResume?.invoke()
            }
        }
        btnRow.addView(pauseBtn, LinearLayout.LayoutParams(
            (36 * dp).toInt(), (36 * dp).toInt()
        ).apply { rightMargin = (8 * dp).toInt() })

        // 停止按钮
        val stopBtn = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            setColorFilter(0xFFE53935.toInt())
            setOnClickListener {
                onStop?.invoke()
            }
        }
        btnRow.addView(stopBtn, LinearLayout.LayoutParams(
            (36 * dp).toInt(), (36 * dp).toInt()
        ))

        root.addView(btnRow)
        return root
    }

    private fun setupDrag(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (dx * dx + dy * dy > 25) isDragging = true
                    if (isDragging) {
                        // 拖拽时相对于屏幕右上角
                        params.x = initialX - dx.toInt()
                        params.y = initialY + dy.toInt()
                        try {
                            windowManager?.updateViewLayout(view, params)
                        } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        view.performClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "录制悬浮窗",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "内录控制悬浮窗通知"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Opedrgent 录制中")
            .setContentText("点击返回应用")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
