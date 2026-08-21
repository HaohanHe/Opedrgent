package top.hsyscn.opedrgent.pdf

import top.hsyscn.opedrgent.R

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 通用OCR引擎 - 支持 ML Kit 和 PP-OCRv6 双引擎
 * 
 * ML Kit（默认）：Google 离线识别，开箱即用，无需下载
 * PP-OCRv6（可选）：百度 PaddleOCR，需额外下载模型，精度更高
 */
class OcrEngine(private val context: Context) {

    companion object {
        private const val TAG = "OcrEngine"
        private const val MAX_IMAGE_WIDTH = 1920
    }

    /** OCR 引擎类型 */
    enum class EngineType { ML_KIT, PP_OCR_V6 }

    // 中文文本识别器（ML Kit）
    private val chineseRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    // 默认文本识别器（ML Kit 英文/拉丁文）
    private val defaultRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    // PP-OCRv6 引擎（懒加载，首次识别时才初始化模型）
    private val paddleEngine by lazy {
        top.hsyscn.opedrgent.ocr.PaddleOcrEngine(context)
    }
    private var paddleEngineLoaded = false

    /** 确保 PP-OCRv6 模型已加载（延迟到首次使用） */
    private fun ensurePaddleEngineLoaded(): Boolean {
        if (!paddleEngineLoaded) {
            paddleEngineLoaded = paddleEngine.loadModel()
        }
        return paddleEngineLoaded
    }

    /**
     * 获取当前推荐的引擎类型
     * 如果 PP-OCRv6 模型已下载则推荐 PP-OCRv6，否则推荐 ML Kit
     */
    fun getRecommendedEngine(): EngineType {
        return if (top.hsyscn.opedrgent.ocr.OcrModelManager.isModelDownloaded(context, "pp_ocrv6_medium")) {
            EngineType.PP_OCR_V6
        } else {
            EngineType.ML_KIT
        }
    }

    /**
     * 从Bitmap进行OCR识别
     * @param engine 指定引擎类型，null 则使用推荐引擎
     */
    suspend fun recognizeFromBitmap(
        bitmap: Bitmap,
        preferChinese: Boolean = true,
        engine: EngineType? = null,
    ): OcrResult {
        val selectedEngine = engine ?: getRecommendedEngine()

        // PP-OCRv6 引擎
        if (selectedEngine == EngineType.PP_OCR_V6) {
            return recognizeWithPaddle(bitmap)
        }

        // ML Kit 引擎（默认）
        return recognizeWithMlKit(bitmap, preferChinese)
    }

    private suspend fun recognizeWithPaddle(bitmap: Bitmap): OcrResult {
        return withContext(Dispatchers.Default) {
            try {
                if (!ensurePaddleEngineLoaded()) {
                    DebugLog.w(TAG, "PP-OCRv6 模型加载失败，回退到 ML Kit")
                    return@withContext recognizeWithMlKit(bitmap, preferChinese = true)
                }

                val startTime = System.currentTimeMillis()
                DebugLog.i(TAG, "使用 PP-OCRv6 引擎: ${bitmap.width}x${bitmap.height}")

                val scaledBitmap = scaleBitmapIfNeeded(bitmap)
                val text = try {
                    paddleEngine.recognize(scaledBitmap)
                } finally {
                    if (scaledBitmap !== bitmap) scaledBitmap.recycle()
                }

                val processingTimeMs = System.currentTimeMillis() - startTime
                DebugLog.i(TAG, "PP-OCRv6 完成: ${text.length}字, 耗时=${processingTimeMs}ms")

                OcrResult(
                    text = text,
                    lines = if (text.isNotEmpty()) listOf(OcrLine(text = text, confidence = 0.9f)) else emptyList(),
                    language = "zh",
                    processingTimeMs = processingTimeMs,
                    engineUsed = "PP-OCRv6",
                )
            } catch (e: Exception) {
                DebugLog.e(TAG, "PP-OCRv6 识别失败，回退到 ML Kit: ${e.message}", e)
                recognizeWithMlKit(bitmap, preferChinese = true)
            }
        }
    }

    private suspend fun recognizeWithMlKit(bitmap: Bitmap, preferChinese: Boolean): OcrResult {
        return withContext(Dispatchers.Default) {
            try {
                val startTimeMs = System.currentTimeMillis()
                DebugLog.i(TAG, "使用 ML Kit 引擎: ${bitmap.width}x${bitmap.height}")

                val scaledBitmap = scaleBitmapIfNeeded(bitmap)
                val image = InputImage.fromBitmap(scaledBitmap, 0)

                val recognizer = if (preferChinese) chineseRecognizer else defaultRecognizer
                val result = recognizer.process(image).await()
                if (scaledBitmap !== bitmap) scaledBitmap.recycle()

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
                                confidence = 1f,
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
                    engineUsed = "ML Kit",
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
                    OcrResult(text = "", lines = emptyList(), error = context.getString(R.string.error_cannot_decode_image))
                } else {
                    try {
                        recognizeFromBitmap(bitmap, preferChinese)
                    } finally {
                        bitmap.recycle()
                    }
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
                    ?: throw IOException(context.getString(R.string.error_cannot_open_uri_generic, uri.toString()))

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
    val engineUsed: String = "ML Kit",
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

