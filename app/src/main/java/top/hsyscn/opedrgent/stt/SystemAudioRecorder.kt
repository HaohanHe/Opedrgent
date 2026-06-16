package top.hsyscn.opedrgent.stt

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
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
            DebugLog.e("SystemAudioRecorder", "Failed to start system audio recording: ${e.message}", e)
            null
        }
    }

    fun stopRecording() {
        isRecording = false
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
        DebugLog.i("SystemAudioRecorder", "System audio recording stopped")
    }

    fun isRecording(): Boolean = isRecording
}
