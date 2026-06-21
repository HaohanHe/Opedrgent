package top.hsyscn.opedrgent.agent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 屏幕截图工具 — 为 Mobile Agent 提供当前屏幕图像。
 *
 * 使用 MediaProjection API 截取全屏（需用户授权）。
 * 授权后可持续截屏，支持单次/连续模式。
 *
 * ## 使用流程
 * 1. 调用 requestPermission() 启动系统授权弹窗
 * 2. 用户同意后获得 resultCode + data Intent
 * 3. 调用 startProjection(resultCode, data) 初始化投影
 * 4. 调用 captureScreenshot() 获取 base64 编码的屏幕截图
 * 5. 用完后调用 stopProjection() 释放资源
 *
 * ## 权限要求
 * AndroidManifest: 无需额外权限（MediaProjection 是运行时授权）
 */
class ScreenCaptureManager(private val context: Context) {

    companion object {
        private const val TAG = "ScreenCapture"
        const val SCREENCAPTURE_REQUEST_CODE = 1001
        const val VIRTUAL_DISPLAY_NAME = "Opedrgent-ScreenCapture"

        /** 默认截图 DPI */
        const val DEFAULT_DPI = 320

        /** 截图最大宽度 (限制内存占用) */
        const val MAX_WIDTH = 1920

        /** 截图最大高度 */
        const val MAX_HEIGHT = 1080
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var isCapturing = false

    /**
     * 创建 MediaProjection 授权 Intent。
     *
     * 需要在 Activity.onActivityResult 中处理结果，
     * 然后将 resultCode 和 data 传给 startProjection()。
     */
    fun createProjectionIntent(activity: Activity): Intent {
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as? MediaProjectionManager
            ?: throw IllegalStateException("MediaProjectionService 不可用")
        return manager.createScreenCaptureIntent()
    }

    /**
     * 启动屏幕投影（用户授权后调用）。
     *
     * @param resultCode Activity.RESULT_OK
     * @param data 授权返回的 Intent data
     */
    fun startProjection(resultCode: Int, data: Intent?): Boolean {
        try {
            stopProjection() // 先释放旧的

            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as? MediaProjectionManager ?: return false

            val intentData = data ?: return false
            mediaProjection = manager.getMediaProjection(resultCode, intentData)
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    DebugLog.w(TAG, "MediaProjection 已停止")
                    cleanup()
                }
            }, null)

            // 获取屏幕尺寸
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val display = wm.defaultDisplay
            val metrics = DisplayMetrics()
            display.getRealMetrics(metrics)

            val width = metrics.widthPixels.coerceAtMost(MAX_WIDTH)
            val height = metrics.heightPixels.coerceAtMost(MAX_HEIGHT)
            val density = metrics.densityDpi

            // 创建 ImageReader
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

            // 创建 VirtualDisplay
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null
            )

            isCapturing = true
            DebugLog.i(TAG, "屏幕投影已启动: ${width}x${height} @${density}dpi")
            return true
        } catch (e: Exception) {
            DebugLog.e(TAG, "启动投影失败: ${e.message}", e)
            return false
        }
    }

    /**
     * 截取当前屏幕。
     *
     * 返回 PNG 格式的 Base64 编码字符串。
     * 如果投影未启动，返回 null。
     */
    suspend fun captureScreenshot(): String? {
        val reader = imageReader ?: run {
            DebugLog.w(TAG, "ImageReader 未初始化，请先启动投影")
            return null
        }
        if (!isCapturing) {
            DebugLog.w(TAG, "投影未处于活动状态")
            return null
        }

        val image = try {
            // 获取最新帧
            suspendCancellableCoroutine<Image?> { cont ->
                val listener = ImageReader.OnImageAvailableListener { readerRef ->
                    val img = readerRef.acquireLatestImage()
                    if (img != null) {
                        cont.resume(img)
                    }
                }
                reader.setOnImageAvailableListener(listener, null)

                Thread {
                    Thread.sleep(500)
                    val img = reader.acquireLatestImage()
                    if (img != null && !cont.isCompleted) {
                        cont.resume(img)
                    } else if (!cont.isCompleted) {
                        reader.setOnImageAvailableListener(null, null)
                        cont.resume(null)
                    }
                }.start()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e(TAG, "获取图像异常: ${e.message}", e)
            return null
        }

        if (image == null) {
            DebugLog.w(TAG, "无法获取屏幕图像")
            return null
        }

        val bitmap = try {
            imageToBitmap(image)
        } finally {
            image.close()
        }

        if (bitmap == null) {
            DebugLog.w(TAG, "图像转换失败")
            return null
        }

        return try {
            val base64 = bitmapToBase64(bitmap!!)
            DebugLog.i(TAG, "截图成功: ${bitmap.width}x${bitmap.height}, base64长度=${base64.length}")
            base64
        } catch (e: Exception) {
            DebugLog.e(TAG, "编码异常: ${e.message}", e)
            null
        }
    }

    /**
     * 快速截图（同步版本，在主线程可用时使用）。
     *
     * 通过 WindowManager 获取应用自身窗口的截图（无需 MediaProjection）。
     * 仅能截取本 App 的窗口，不能截取其他 App 或状态栏。
     */
    fun captureAppWindow(): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // PixelCopy 方式 — 需要 View/Window 引用
                // 此处作为降级方案，实际需要从 UI 层传入根视图
                DebugLog.w(TAG, "captureAppWindow 需要从 UI 层传入根 View，请使用 captureScreenshot()")
                null
            } else {
                null
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "窗口截图异常: ${e.message}", e)
            null
        }
    }

    /**
     * 停止屏幕投影并释放所有资源。
     */
    fun stopProjection() {
        isCapturing = false
        cleanup()
        DebugLog.i(TAG, "屏幕投影已停止并释放资源")
    }

    /** 投影是否正在运行 */
    fun isActive(): Boolean = isCapturing && mediaProjection != null

    // ---- 内部方法 ----

    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            // 裁剪到实际尺寸
            if (rowPadding > 0) {
                Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
            } else {
                bitmap
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "Image→Bitmap 转换失败: ${e.message}", e)
            null
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
        val bytes = stream.toByteArray()
        bitmap.recycle()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun cleanup() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null
        } catch (_: Exception) {}
        try {
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
            imageReader = null
        } catch (_: Exception) {}
        try {
            mediaProjection?.stop()
            mediaProjection = null
        } catch (_: Exception) {}
    }
}
