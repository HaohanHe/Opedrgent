package top.hsyscn.opedrgent.stt

import android.net.Uri
import kotlinx.coroutines.flow.Flow

interface SpeechEngine {
    val engineType: EngineType
    val isAvailable: Boolean
    
    suspend fun recognizeFile(uri: Uri): SttResult
    suspend fun recognizeFile(filePath: String): SttResult
    fun startStreamingRecognition(): Flow<StreamingRecognitionState>
    fun stopStreamingRecognition()
    
    fun close()
}

sealed class StreamingRecognitionState {
    data class Recognizing(val partialText: String) : StreamingRecognitionState()
    data class FinalResult(val text: String) : StreamingRecognitionState()
    data class Error(val message: String) : StreamingRecognitionState()
    object Listening : StreamingRecognitionState()
    object Stopped : StreamingRecognitionState()
}
