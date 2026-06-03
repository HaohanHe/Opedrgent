package top.hsyscn.opedrgent.stt

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import com.google.android.gms.common.GoogleApiAvailability
import top.hsyscn.opedrgent.utils.DebugLog

class AndroidSpeechRecognizer(
    private val context: Context,
    private val config: SttConfig = SttConfig(),
) : SpeechEngine {

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
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
        if (!isAvailable) {
            trySend(StreamingRecognitionState.Error("Android SpeechRecognizer 不可用"))
            close()
            return@callbackFlow
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)

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
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                DebugLog.i("AndroidSTT: 识别结果: ${text.take(100)}...")
                trySendBlocking(StreamingRecognitionState.FinalResult(text))
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                trySendBlocking(StreamingRecognitionState.Recognizing(text))
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }

        speechRecognizer?.setRecognitionListener(listener)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, when (config.language) {
                SttLanguage.CHINESE -> "zh-CN"
                SttLanguage.ENGLISH -> "en-US"
                SttLanguage.AUTO -> ""
            })
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        isListening = true
        speechRecognizer?.startListening(intent)

        awaitClose {
            stopStreamingRecognition()
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
        try {
            speechRecognizer?.destroy()
            speechRecognizer = null
            isListening = false
            DebugLog.i("AndroidSTT: 资源已释放")
        } catch (e: Exception) {
            DebugLog.e("AndroidSTT: 关闭时出错: ${e.message}")
        }
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
