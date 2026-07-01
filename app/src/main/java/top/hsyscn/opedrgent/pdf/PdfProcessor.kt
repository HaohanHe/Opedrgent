package top.hsyscn.opedrgent.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

object PdfProcessor {
    suspend fun renderPages(
        context: Context,
        uri: Uri,
        maxPages: Int = 6,
        scale: Float = 2f,
    ): List<Bitmap> {
        return withContext(Dispatchers.IO) {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext emptyList()
            pfd.use { fd ->
                runCatching {
                    PdfRenderer(fd).use { renderer ->
                        val count = minOf(renderer.pageCount, maxPages)
                        val bitmaps = ArrayList<Bitmap>(count)
                        for (i in 0 until count) {
                            renderer.openPage(i).use { page ->
                                val width = (page.width * scale).toInt().coerceAtLeast(1)
                                val height = (page.height * scale).toInt().coerceAtLeast(1)
                                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                bitmaps.add(bitmap)
                            }
                        }
                        bitmaps
                    }
                }.getOrElse { emptyList() }
            }
        }
    }

    suspend fun ocr(bitmaps: List<Bitmap>): String {
        return withContext(Dispatchers.Default) {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                val texts = ArrayList<String>()
                for (bmp in bitmaps) {
                    val image = InputImage.fromBitmap(bmp, 0)
                    val result = Tasks.await(recognizer.process(image))
                    val t = result.text.trim().orEmpty()
                    if (t.isNotBlank()) texts.add(t)
                }
                texts.joinToString("\n\n")
            } finally {
                recognizer.close()
            }
        }
    }

    fun toBase64Png(bitmap: Bitmap, maxSide: Int = 1600): String {
        val scaled = if (bitmap.width <= maxSide && bitmap.height <= maxSide) {
            bitmap
        } else {
            val ratio = maxOf(bitmap.width, bitmap.height).toFloat() / maxSide.toFloat()
            val w = (bitmap.width / ratio).toInt().coerceAtLeast(1)
            val h = (bitmap.height / ratio).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, w, h, true)
        }
        val os = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 100, os)
        val bytes = os.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
