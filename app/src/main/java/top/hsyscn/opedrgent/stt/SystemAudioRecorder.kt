package top.hsyscn.opedrgent.stt

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import top.hsyscn.opedrgent.utils.CrashReporter
import top.hsyscn.opedrgent.utils.DebugLog

class SystemAudioRecorder(private val context: Context) {
    private var audioRecord: AudioRecord? = null
    private var isRecording = false

    fun startRecording(mediaProjection: MediaProjection): AudioRecord? {
        if (android.os.Build.VERSION.SDK_INT < 29) {
            DebugLog.w("SystemAudioRecorder", "AudioPlaybackCaptureConfiguration requires API 29+")
            return null
        }

        return try {
            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val recorder = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .setEncoding(audioFormat)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()

            if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                recorder.startRecording()
                audioRecord = recorder
                isRecording = true
                DebugLog.i("SystemAudioRecorder", "System audio recording started")
                recorder
            } else {
                DebugLog.e("SystemAudioRecorder", "AudioRecord initialization failed")
                recorder.release()
                null
            }
        } catch (e: Exception) {
            // 构建/启动失败：记录技术信息，返回 null 让调用方回到未录音安全态（不残留录音中标志）
            CrashReporter.logError("SystemAudioRecorder", "Failed to start system audio recording", e)
            DebugLog.e("SystemAudioRecorder", "Failed to start system audio recording: ${e.message}", e)
            audioRecord = null
            isRecording = false
            null
        }
    }

    fun stopRecording() {
        isRecording = false
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            CrashReporter.logWarn("SystemAudioRecorder", "stop() during stopRecording failed: ${e.message}")
        }
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            CrashReporter.logWarn("SystemAudioRecorder", "release() during stopRecording failed: ${e.message}")
        }
        audioRecord = null
        DebugLog.i("SystemAudioRecorder", "System audio recording stopped")
    }

    fun isRecording(): Boolean = isRecording
}
