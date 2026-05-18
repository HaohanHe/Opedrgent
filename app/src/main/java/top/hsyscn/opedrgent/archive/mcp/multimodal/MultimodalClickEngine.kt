package top.hsyscn.opedrgent.mcp.multimodal

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class ClickTarget(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val confidence: Double,
    val label: String? = null,
    val elementDescription: String? = null,
)

@Serializable
data class MultimodalAction(
    val type: ActionType,
    val target: ClickTarget? = null,
    val text: String? = null,
    val direction: ScrollDirection? = null,
    val amount: Int = 0,
)

enum class ActionType {
    CLICK,
    TYPE,
    SCROLL,
    WAIT,
    SWIPE,
}

enum class ScrollDirection {
    UP,
    DOWN,
    LEFT,
    RIGHT,
}

@Serializable
data class MultimodalConfig(
    val maxRetries: Int = 3,
    val clickTolerancePx: Int = 10,
    val screenshotQuality: Int = 80,
    val maxActionChainLength: Int = 10,
    val enableOcrFallback: Boolean = true,
    val minConfidenceThreshold: Double = 0.6,
    val elementDetectionTimeoutMs: Long = 5000L,
)

class MultimodalClickEngine(
    private val config: MultimodalConfig = MultimodalConfig(),
) {

    private val actionHistory = mutableListOf<MultimodalAction>()
    private val screenshotCache = ConcurrentHashMap<String, ByteArray>()
    private var lastScreenshotTime = 0L

    suspend fun analyzeAndClick(
        screenshot: Bitmap,
        instruction: String,
        onExecute: suspend (MultimodalAction) -> Boolean,
    ): ClickResult {
        DebugLog.i("MultimodalClickEngine: analyzing instruction: $instruction")

        return withContext(Dispatchers.Default) {
            try {
                val targets = detectClickTargets(screenshot, instruction)
                
                if (targets.isEmpty()) {
                    DebugLog.w("MultimodalClickEngine: no click targets found")
                    return@withContext ClickResult(
                        success = false,
                        error = "No clickable elements found for: $instruction",
                    )
                }

                val bestTarget = targets.maxByOrNull { it.confidence }!!

                DebugLog.i("MultimodalClickEngine: best target at (${bestTarget.x}, ${bestTarget.y}) confidence=${"%.2f".format(bestTarget.confidence)}")

                val action = MultimodalAction(
                    type = ActionType.CLICK,
                    target = bestTarget,
                )

                val executed = onExecute(action)

                if (executed) {
                    recordAction(action)
                    
                    ClickResult(
                        success = true,
                        target = bestTarget,
                        actionsTaken = listOf(action),
                    )
                } else {
                    retryWithAlternativeTargets(targets, instruction, onExecute)
                }
            } catch (e: Exception) {
                DebugLog.e("MultimodalClickEngine.error: ${e.message}")
                ClickResult(
                    success = false,
                    error = "Analysis failed: ${e.message}",
                )
            }
        }
    }

    suspend fun executeActionChain(
        initialScreenshot: Bitmap,
        instructions: List<String>,
        onExecute: suspend (MultimodalAction) -> Bitmap?,
    ): ChainExecutionResult {
        DebugLog.i("MultimodalClickEngine: executing action chain of ${instructions.size} steps")

        var currentScreenshot = initialScreenshot
        val executedActions = mutableListOf<MultimodalAction>()
        val results = mutableListOf<ClickResult>()

        for ((index, instruction) in instructions.withIndex()) {
            if (executedActions.size >= config.maxActionChainLength) {
                DebugLog.w("MultimodalClickEngine: max chain length reached")
                break
            }

            DebugLog.d("MultimodalClickEngine: step ${index + 1}/${instructions.size}: $instruction")

            val result = analyzeAndClick(currentScreenshot, instruction) { action ->
                val executed = onExecute(action)

                if (executed != null && action.type == ActionType.CLICK) {
                    delay(300)
                    val newScreenshot = onExecute(MultimodalAction(type = ActionType.WAIT))
                    if (newScreenshot != null) {
                        currentScreenshot = newScreenshot
                    }
                }

                executed != null
            }

            executedActions.add(result.actionsTaken.lastOrNull() ?: continue)
            results.add(result)

            if (!result.success) {
                DebugLog.w("MultimodalClickEngine: step $index failed, stopping chain")
                break
            }

            delay(200)
        }

        return ChainExecutionResult(
            success = results.all { it.success },
            totalSteps = instructions.size,
            completedSteps = executedActions.size,
            actionsTaken = executedActions.toList(),
            stepResults = results,
        )
    }

    private suspend fun detectClickTargets(screenshot: Bitmap, instruction: String): List<ClickTarget> {
        return withContext(Dispatchers.IO) {
            val targets = mutableListOf<ClickTarget>()
            
            val base64Screenshot = bitmapToBase64(screenshot, config.screenshotQuality)
            
            val prompt = """Analyze this screenshot and identify the best clickable element(s) for this instruction: "$instruction"

Return a JSON array of click targets. Each target should have:
- x, y: center coordinates of the clickable area
- width, height: size of the clickable area  
- confidence: 0.0 to 1.0 how confident you are this is correct
- label: what this element appears to be (button, link, input field, etc.)
- elementDescription: brief description of the element

Focus on:
1. Buttons or interactive elements matching the instruction
2. Links or navigation items
3. Input fields or form controls
4. Icons that match the described functionality

Only include high-confidence targets (>${config.minConfidenceThreshold}). Sort by confidence descending."""

            try {
                val analysisResult = callVisionApi(base64Screenshot, prompt)
                
                parseClickTargets(analysisResult)?.let { parsed ->
                    targets.addAll(parsed.filter { it.confidence >= config.minConfidenceThreshold })
                }
                
                DebugLog.i("MultimodalClickEngine: detected ${targets.size} potential targets")
                
                if (targets.isEmpty() && config.enableOcrFallback) {
                    val ocrTargets = performOcrFallback(screenshot, instruction)
                    targets.addAll(ocrTargets)
                }
            } catch (e: Exception) {
                DebugLog.e("MultimodalClickEngine.detectTargets: ${e.message}")
            }

            targets.sortedByDescending { it.confidence }
        }
    }

    private suspend fun retryWithAlternativeTargets(
        targets: List<ClickTarget>,
        instruction: String,
        onExecute: suspend (MultimodalAction) -> Boolean,
    ): ClickResult {
        val alternatives = targets.filter { it != targets.maxByOrNull { it.confidence } }

        for ((retry, target) in alternatives.take(config.maxRetries - 1).withIndex()) {
            DebugLog.i("MultimodalClickEngine: retry ${retry + 1} with alternative target (${target.x}, ${target.y})")

            val action = MultimodalAction(
                type = ActionType.CLICK,
                target = target,
            )

            delay(500)

            val executed = onExecute(action)

            if (executed) {
                recordAction(action)
                
                return ClickResult(
                    success = true,
                    target = target,
                    actionsTaken = listOf(action),
                    retries = retry + 1,
                )
            }
        }

        return ClickResult(
            success = false,
            error = "All ${targets.size} targets failed after retries",
            attemptedTargets = targets,
        )
    }

    private suspend fun callVisionApi(imageBase64: String, prompt: String): String {
    throw UnsupportedOperationException(
        "MultimodalClickEngine.callVisionApi: Vision API not implemented. " +
        "Use a vision-capable LLM provider for image understanding."
    )
}

    private fun parseClickTargets(response: String): List<ClickTarget>? {
        return try {
            val json = org.json.JSONArray(response)
            val targets = mutableListOf<ClickTarget>()

            for (i in 0 until json.length()) {
                val obj = json.getJSONObject(i)
                targets.add(ClickTarget(
                    x = obj.getInt("x"),
                    y = obj.getInt("y"),
                    width = obj.optInt("width", 50),
                    height = obj.optInt("height", 50),
                    confidence = obj.getDouble("confidence").coerceIn(0.0, 1.0),
                    label = obj.optString("label", null),
                    elementDescription = obj.optString("elementDescription", null),
                ))
            }

            targets
        } catch (e: Exception) {
            DebugLog.w("MultimodalClickEngine.parseTargets: ${e.message}")
            null
        }
    }

    private suspend fun performOcrFallback(screenshot: Bitmap, instruction: String): List<ClickTarget> {
        throw UnsupportedOperationException(
            "MultimodalClickEngine.performOcrFallback: ML Kit OCR not implemented. " +
            "Add ML Kit dependency and implement text recognition."
        )
    }

    private fun bitmapToBase64(bitmap: Bitmap, quality: Int): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, quality, stream)
        val bytes = stream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun recordAction(action: MultimodalAction) {
        synchronized(actionHistory) {
            actionHistory.add(action)
            
            if (actionHistory.size > 100) {
                actionHistory.removeAt(0)
            }
        }
    }

    fun getActionHistory(): List<MultimodalAction> {
        return synchronized(actionHistory) { actionHistory.toList() }
    }

    fun clearHistory() {
        synchronized(actionHistory) { actionHistory.clear() }
        screenshotCache.clear()
    }

    @Serializable
    data class ClickResult(
        val success: Boolean,
        val target: ClickTarget? = null,
        val actionsTaken: List<MultimodalAction> = emptyList(),
        val error: String? = null,
        val retries: Int = 0,
        val attemptedTargets: List<ClickTarget> = emptyList(),
    )

    @Serializable
    data class ChainExecutionResult(
        val success: Boolean,
        val totalSteps: Int,
        val completedSteps: Int,
        val actionsTaken: List<MultimodalAction>,
        val stepResults: List<ClickResult>,
    )

    companion object {
        private var instance: MultimodalClickEngine? = null

        fun getInstance(config: MultimodalConfig = MultimodalConfig()): MultimodalClickEngine {
            if (instance == null) {
                instance = MultimodalClickEngine(config)
            }
            return instance!!
        }

        fun createGlobal(config: MultimodalConfig = MultimodalConfig()): MultimodalClickEngine {
            val engine = MultimodalClickEngine(config)
            instance = engine
            return engine
        }
    }
}
