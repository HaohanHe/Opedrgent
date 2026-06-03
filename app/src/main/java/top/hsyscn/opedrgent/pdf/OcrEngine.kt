package top.hsyscn.opedrgent.pdf

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.OptIn
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 通用OCR引擎 - 支持任意图片的文字识别（不仅限于PDF）
 * 
 * 使用 Google ML Kit Text Recognition API：
 * - 中文识别：ChineseTextRecognizerOptions（内置中文模型）
 * - 英文/拉丁文：默认 TextRecognizer
 * - 完全离线运行，无需网络
 */
class OcrEngine(private val context: Context) {

    companion object {
        private const val TAG = "OcrEngine"
        private const val MAX_IMAGE_WIDTH = 1920
    }

    // 中文文本识别器
    private val chineseRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    // 默认文本识别器（英文/拉丁文）
    private val defaultRecognizer by lazy {
        TextRecognition.getClient()
    }

    /**
     * 从Bitmap进行OCR识别
     * 自动缩放大图以提升性能和准确率
     */
    suspend fun recognizeFromBitmap(bitmap: Bitmap, preferChinese: Boolean = true): OcrResult {
        return withContext(Dispatchers.Default) {
            try {
                val startTimeMs = System.currentTimeMillis()
                DebugLog.i(TAG, "开始OCR识别: ${bitmap.width}x${bitmap.height}")

                val scaledBitmap = scaleBitmapIfNeeded(bitmap)
                val image = InputImage.fromBitmap(scaledBitmap, 0)

                val recognizer = if (preferChinese) chineseRecognizer else defaultRecognizer
                val result = recognizer.process(image).await()

                val textBlocks = result.textBlocks
                val fullText = StringBuilder()
                val lines = mutableListOf<OcrLine>()

                for (block in textBlocks) {
                    val blockText = block.text
                    if (fullText.isNotEmpty()) fullText.append("\n")
                    fullText.append(blockText)

                    for (line in block.lines) {
                        lines.add(
                            OcrLine(
                                text = line.text,
                                confidence = line.confidence?.toFloat() ?: 1f,
                                boundingBox = line.boundingBox?.let { rect ->
                                    BoundingBox(
                                        left = rect.left.toFloat(),
                                        top = rect.top.toFloat(),
                                        right = rect.right.toFloat(),
                                        bottom = rect.bottom.toFloat(),
                                    )
                                },
                            )
                        )
                    }
                }

                val processingTimeMs = System.currentTimeMillis() - startTimeMs
                DebugLog.i(TAG, "OCR完成: ${lines.size}行文字, 耗时=${processingTimeMs}ms")

                OcrResult(
                    text = fullText.toString().trim(),
                    lines = lines.toList(),
                    language = if (preferChinese) "zh" else "en",
                    processingTimeMs = processingTimeMs,
                )

            } catch (e: Exception) {
                DebugLog.e(TAG, "OCR识别失败: ${e.message}", e)
                OcrResult(text = "", lines = emptyList(), error = e.message)
            }
        }
    }

    /**
     * 从图片文件路径进行OCR识别
     * 支持：jpg、jpeg、png、bmp、webp 等格式
     */
    suspend fun recognizeFromFile(filePath: String, preferChinese: Boolean = true): OcrResult {
        return withContext(Dispatchers.IO) {
            try {
                val bitmap = android.graphics.BitmapFactory.decodeFile(File(filePath).absolutePath)
                if (bitmap == null) {
                    OcrResult(text = "", lines = emptyList(), error = "无法解码图片文件")
                } else {
                    recognizeFromBitmap(bitmap, preferChinese)
                }
            } catch (e: Exception) {
                DebugLog.e(TAG, "文件OCR识别失败: ${e.message}", e)
                OcrResult(text = "", lines = emptyList(), error = e.message)
            }
        }
    }

    /**
     * 从Uri进行OCR识别
     */
    suspend fun recognizeFromUri(uri: Uri, preferChinese: Boolean = true): OcrResult {
        return withContext(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: throw IOException("无法打开Uri: $uri")

                val tempFile = File(context.cacheDir, "ocr_temp_${System.currentTimeMillis()}.jpg")
                try {
                    FileOutputStream(tempFile).use { output ->
                        inputStream.use { input ->
                            input.copyTo(output)
                        }
                    }
                    recognizeFromFile(tempFile.absolutePath, preferChinese)
                } finally {
                    tempFile.delete()
                }
            } catch (e: Exception) {
                DebugLog.e(TAG, "Uri OCR识别失败: ${e.message}", e)
                OcrResult(text = "", lines = emptyList(), error = e.message)
            }
        }
    }

    /**
     * 缩放Bitmap以优化性能
     * ML Kit对大图片处理较慢，超过MAX_IMAGE_WIDTH时自动缩小
     */
    private fun scaleBitmapIfNeeded(bitmap: Bitmap): Bitmap {
        if (bitmap.width <= MAX_IMAGE_WIDTH) return bitmap

        val scale = MAX_IMAGE_WIDTH.toFloat() / bitmap.width.toFloat()
        val newWidth = MAX_IMAGE_WIDTH
        val newHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)

        DebugLog.d(TAG, "缩放图片: ${bitmap.width}x${bitmap.height} -> ${newWidth}x${newHeight}")
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    fun close() {
        // ML Kit 的recognizer不需要手动关闭
    }
}

/**
 * OCR识别结果
 */
data class OcrResult(
    val text: String,
    val lines: List<OcrLine>,
    val language: String = "zh",
    val processingTimeMs: Long = 0,
    val error: String? = null,
) {
    val isSuccess: Boolean get() = error == null && text.isNotEmpty()
}

/**
 * OCR识别的每一行文字及其位置信息
 */
data class OcrLine(
    val text: String,
    val confidence: Float,
    val boundingBox: BoundingBox? = null,
)

/**
 * 文字边界框坐标
 */
data class BoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)
