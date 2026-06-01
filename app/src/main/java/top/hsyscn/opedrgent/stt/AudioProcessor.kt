package top.hsyscn.opedrgent.stt

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File

data class AudioMetadata(
    val durationMs: Long = 0,
    val sampleRate: Int = 16000,
    val channels: Int = 1,
    val bitDepth: Int = 16,
    val format: String = "unknown",
    val fileSizeBytes: Long = 0,
)

data class ProcessedAudio(
    val filePath: String,
    val metadata: AudioMetadata,
    val segments: List<AudioSegment> = emptyList(),
)

data class AudioSegment(
    val filePath: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val index: Int,
)

object AudioProcessor {

    private const val TARGET_SAMPLE_RATE = 16000
    private const val TARGET_CHANNELS = 1
    private const val TARGET_BIT_DEPTH = 16
    const val DEFAULT_SEGMENT_LENGTH_MS = 30000L // 30 秒

    fun extractAudioFromVideo(context: Context, videoUri: Uri): ProcessedAudio? {
        return try {
            DebugLog.i("AudioProcessor: 开始从视频提取音频 URI=$videoUri")

            val extractor = MediaExtractor()
            extractor.setDataSource(context, videoUri, null)

            var audioTrackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    break
                }
            }

            if (audioTrackIndex == -1) {
                DebugLog.w("AudioProcessor: 视频中未找到音频轨道")
                extractor.release()
                null
            } else {
                val format = extractor.getTrackFormat(audioTrackIndex)
                val duration = format.getLong(MediaFormat.KEY_DURATION) / 1000 // 微秒 → 毫秒

                val metadata = AudioMetadata(
                    durationMs = duration,
                    sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE, TARGET_SAMPLE_RATE),
                    channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT, TARGET_CHANNELS),
                    format = format.getString(MediaFormat.KEY_MIME) ?: "audio/unknown",
                )

                extractor.release()

                DebugLog.i("AudioProcessor: 音频元数据提取完成 duration=${duration}ms")

                ProcessedAudio(
                    filePath = videoUri.toString(),
                    metadata = metadata,
                )
            }
        } catch (e: Exception) {
            DebugLog.e("AudioProcessor: 提取视频音频失败: ${e.message}", e)
            null
        }
    }

    fun extractAudioFromVideo(context: Context, videoPath: String): ProcessedAudio? {
        return extractAudioFromVideo(context, Uri.fromFile(File(videoPath)))
    }

    fun getAudioMetadata(context: Context, audioUri: Uri): AudioMetadata? {
        return try {
            val extractor = MediaExtractor()
            extractor.setDataSource(context, audioUri, null)

            var audioTrackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    break
                }
            }

            if (audioTrackIndex == -1) {
                extractor.release()
                null
            } else {
                val format = extractor.getTrackFormat(audioTrackIndex)
                val metadata = AudioMetadata(
                    durationMs = format.getLong(MediaFormat.KEY_DURATION) / 1000,
                    sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE, TARGET_SAMPLE_RATE),
                    channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT, TARGET_CHANNELS),
                    format = mime,
                )
                extractor.release()
                metadata
            }
        } catch (e: Exception) {
            DebugLog.e("AudioProcessor: 获取音频元数据失败: ${e.message}", e)
            null
        }
    }

    fun segmentAudio(durationMs: Long, segmentLengthMs: Long = DEFAULT_SEGMENT_LENGTH_MS): List<AudioSegment> {
        if (durationMs <= segmentLengthMs) {
            return listOf(AudioSegment("", 0, durationMs, 0))
        }

        val segments = mutableListOf<AudioSegment>()
        var currentTime = 0L
        var index = 0

        while (currentTime < durationMs) {
            val endTime = minOf(currentTime + segmentLengthMs, durationMs)
            segments.add(AudioSegment("", currentTime, endTime, index))
            currentTime = endTime
            index++
        }

        DebugLog.i("AudioProcessor: 音频分段完成 共${segments.size}段")
        return segments
    }

    fun validateAudioFile(context: Context, uri: Uri): Pair<Boolean, String?> {
        return try {
            val metadata = getAudioMetadata(context, uri)
            if (metadata == null) {
                Pair(false, "无法读取音频文件或格式不支持")
            } else if (metadata.durationMs == 0L) {
                Pair(false, "音频文件时长为 0 或无法获取时长")
            } else if (metadata.durationMs > 1800_000L) { // 30 分钟
                Pair(false, "音频时长超过 30 分钟限制 (${metadata.durationMs / 1000}秒)")
            } else {
                Pair(true, null)
            }
        } catch (e: Exception) {
            DebugLog.e("AudioProcessor: 验证音频文件失败: ${e.message}")
            Pair(false, "验证失败: ${e.message}")
        }
    }

    fun formatDuration(ms: Long): String {
        val seconds = ms / 1000
        val minutes = seconds / 60
        val secs = seconds % 60
        return "%d:%02d".format(minutes, secs)
    }
}
