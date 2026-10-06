package top.hsyscn.opedrgent.stt

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import com.google.android.gms.common.GoogleApiAvailability
import top.hsyscn.opedrgent.utils.DebugLog

class AndroidSpeechRecognizer(
    private val context: Context,
    private val config: SttConfig = SttConfig(),
) : SpeechEngine {

    private var speechRecognizer: SpeechRecognizer? = null
    @Volatile
    private var isListening = false

    /**
     * 会话序号。每次 startStreamingRecognition 自增；awaitClose 中仅当序号未变时才真正释放，
     * 避免"旧 collect 的取消回调"误杀新一次订阅创建的识别器（重入覆盖防护）。
     */
    private var sessionId = 0

    private val gmsAvailable by lazy { checkGmsAvailability() }

    override val engineType = EngineType.ANDROID_SPEECH_RECOGNIZER

    override val isAvailable: Boolean
        get() = gmsAvailable && SpeechRecognizer.isRecognitionAvailable(context)

    override suspend fun recognizeFile(uri: android.net.Uri): SttResult {
        if (!isAvailable) {
            return SttResult(
                text = "[Android SpeechRecognizer 不可用 - 设备可能缺少 Google 服务]",
                engineType = EngineType.ANDROID_SPEECH_RECOGNIZER,
            )
        }

        return try {
            DebugLog.i("AndroidSTT: 尝试使用 Android SpeechRecognizer 处理文件")
            SttResult(
                text = "[Android SpeechRecognizer 文件模式待完整实现 - 仅支持实时录音模式]",
                engineType = EngineType.ANDROID_SPEECH_RECOGNIZER,
            )
        } catch (e: Exception) {
            DebugLog.e("AndroidSTT: 识别失败: ${e.message}", e)
            SttResult(
                text = "",
                engineType = EngineType.ANDROID_SPEECH_RECOGNIZER,
            )
        }
    }

    override suspend fun recognizeFile(filePath: String): SttResult {
        return recognizeFile(android.net.Uri.parse(filePath))
    }

    override fun startStreamingRecognition(): Flow<StreamingRecognitionState> = callbackFlow {
        // 显式持有 ProducerScope，避免在匿名 RecognitionListener 内 close() 与
        // SpeechEngine.close()（本类的成员）产生接收者歧义，确保关闭的是 channel 而非引擎。
        val scope = this
        if (!isAvailable) {
            trySend(StreamingRecognitionState.Error("Android SpeechRecognizer 不可用"))
            scope.close()
            return@callbackFlow
        }

        // 重入防护：进入新会话前先彻底释放上一次的识别器实例（U47-03）。
        // 旧 collect 的 awaitClose 会因 sessionId 已变更而跳过释放，恰好一次。
        val mySession = synchronized(this@AndroidSpeechRecognizer) { ++sessionId }
        releaseRecognizer()

        // AOSP 契约：createSpeechRecognizer / setRecognitionListener / startListening 必须在主线程调用（U47-04）。
        withContext(Dispatchers.Main.immediate) {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer = recognizer

            val listener = object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    DebugLog.d("AndroidSTT: 准备就绪")
                    trySendBlocking(StreamingRecognitionState.Listening)
                }

                override fun onBeginningOfSpeech() {
                    DebugLog.d("AndroidSTT: 开始检测到语音")
                }

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    DebugLog.d("AndroidSTT: 语音结束")
                }

                override fun onError(error: Int) {
                    val errorMessage = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "音频错误"
                        SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "权限不足"
                        SpeechRecognizer.ERROR_NETWORK -> "网络错误"
                        SpeechRecognizer.ERROR_NO_MATCH -> "无匹配结果"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙"
                        SpeechRecognizer.ERROR_SERVER -> "服务器错误"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "语音超时"
                        else -> "未知错误($error)"
                    }
                    DebugLog.w("AndroidSTT: 错误 $error - $errorMessage")
                    trySendBlocking(StreamingRecognitionState.Error(errorMessage))
                    // P0-3：发送 Error 后关闭 channel，collector 的 finally 才能走到 destroy，
                    // 否则流不关闭 → collect 不返回 → SpeechRecognizer 句柄泄漏。
                    scope.close()
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    DebugLog.i("AndroidSTT: 识别结果: ${text.take(100)}...")
                    trySendBlocking(StreamingRecognitionState.FinalResult(text))
                    // P0-3：发送 FinalResult 后关闭 channel。
                    // 调用方收到 FinalResult 后只置 UI=Done、不取消 sttJob，
                    // 只有 channel 关闭使 collect 返回，finally 中的 close()/destroy() 才会执行。
                    scope.close()
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    trySendBlocking(StreamingRecognitionState.Recognizing(text))
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            }

            recognizer.setRecognitionListener(listener)

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, when (config.language) {
                    SttLanguage.CHINESE -> "zh-CN"
                    SttLanguage.ENGLISH -> "en-US"
                    // AUTO 传空串非合法 BCP-47 tag，部分 ROM 可能报错（U47-04 真机验证项）
                    SttLanguage.AUTO -> ""
                })
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }

            isListening = true
            recognizer.startListening(intent)
        }

        awaitClose {
            // P0-4：流因任意原因终止（onResults/onError 主动 close、collector 取消、切 Tab、生命周期取消）
            // 时，除 stopListening 外必须 destroy 识别器，释放系统 RecognitionService 连接。
            // 仅当仍是当前会话时释放，避免旧订阅的取消误杀新实例。
            if (mySession == sessionId) {
                releaseRecognizer()
            }
        }
    }

    override fun stopStreamingRecognition() {
        if (isListening && speechRecognizer != null) {
            try {
                speechRecognizer?.stopListening()
                DebugLog.i("AndroidSTT: 已停止监听")
            } catch (e: Exception) {
                DebugLog.w("AndroidSTT: 停止监听出错: ${e.message}")
            }
            isListening = false
        }
    }

    override fun close() {
        releaseRecognizer()
    }

    /**
     * 彻底释放识别器：stopListening + destroy + 置空。
     * synchronized + 字段置空保证所有终结路径（正常/错误/取消/重入/外部 close）恰好一次 destroy。
     * 线程亲和（destroy 需主线程）受 ROM 差异影响，需真机确认；此处保证 destroy 一定被调用。
     */
    @Synchronized
    private fun releaseRecognizer() {
        if (isListening) {
            try {
                speechRecognizer?.stopListening()
            } catch (e: Exception) {
                DebugLog.w("AndroidSTT: 停止监听出错: ${e.message}")
            }
        }
        try {
            speechRecognizer?.destroy()
            DebugLog.i("AndroidSTT: SpeechRecognizer 已 destroy")
        } catch (e: Exception) {
            DebugLog.e("AndroidSTT: destroy 时出错: ${e.message}")
        }
        speechRecognizer = null
        isListening = false
    }

    private fun checkGmsAvailability(): Boolean {
        return try {
            val availability = GoogleApiAvailability.getInstance()
            val result = availability.isGooglePlayServicesAvailable(context)
            result == com.google.android.gms.common.ConnectionResult.SUCCESS
        } catch (e: Exception) {
            DebugLog.w("AndroidSTT: GMS 可用性检查失败: ${e.message}")
            false
        }
    }
}
