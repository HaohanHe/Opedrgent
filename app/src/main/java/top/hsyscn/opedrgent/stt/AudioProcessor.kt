package top.hsyscn.opedrgent.stt

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

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

data class WavHeader(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val dataOffset: Long,
    val dataSize: Int,
) {
    val byteRate: Int get() = sampleRate * channels * (bitsPerSample / 8)
    val blockAlign: Int get() = channels * (bitsPerSample / 8)
    val durationMs: Long get() = if (sampleRate > 0 && byteRate > 0) (dataSize.toLong() * 1000L / byteRate) else 0L
}

object AudioProcessor {

    private const val TAG = "AudioProcessor"

    const val TARGET_SAMPLE_RATE = 16000
    const val TARGET_CHANNELS = 1
    const val TARGET_BIT_DEPTH = 16
    const val DEFAULT_SEGMENT_LENGTH_MS = 30000L

    private const val WAV_RIFF_HEADER = 0x52494646
    private const val WAVE_FORMAT_PCM = 1
    private const val WAVE_FORMAT_IEEE_FLOAT = 3
    private const val WAVE_FORMAT_EXTENSIBLE = 0xFFFE

    private const val SILENCE_THRESHOLD_DB = -40.0
    private const val SILENCE_MIN_DURATION_MS = 200L
    private const val SEGMENT_OVERLAP_MS = 500L
    private const val MAX_SEGMENT_MS = 60000L

    private const val CODEC_TIMEOUT_US = 10_000L

    /**
     * 将 WAV 文件快速解码为归一化 FloatArray (16kHz mono)。
     *
     * 专为 ASR 引擎设计，跳过元数据提取，直接返回可用的浮点数组。
     * 内部使用线性插值重采样和声道混合。
     *
     * @param wavFile WAV 格式音频文件
     * @return 归一化 [-1.0, 1.0] 浮点数组，采样率 16000Hz；失败返回空数组
     */
    fun decodeWavToFloat(wavFile: File): FloatArray {
        return try {
            val pair = decodeFromFile(wavFile.absolutePath) ?: return FloatArray(0)
            pair.first
        } catch (e: Exception) {
            DebugLog.e(TAG, "decodeWavToFloat 失败: ${e.message}", e)
            FloatArray(0)
        }
    }

    // ==================== 元数据提取（保留原有签名，向后兼容）====================

    fun extractAudioFromVideo(context: Context, videoUri: Uri): ProcessedAudio? {
        return try {
            DebugLog.i(TAG, "开始从视频提取音频 URI=$videoUri")

            val extractor = MediaExtractor().apply { setDataSource(context, videoUri, null) }
            val trackResult = selectAudioTrack(extractor)
            if (trackResult == null) {
                DebugLog.w(TAG, "视频中未找到音频轨道")
                extractor.release()
                return null
            }

            val (trackIndex, format) = trackResult
            val durationUs = format.getLong(MediaFormat.KEY_DURATION, -1L)
            val durationMs = if (durationUs > 0) durationUs / 1000 else 0L

            val metadata = buildMetadataFromFormat(format, durationMs)
            extractor.release()

            DebugLog.i(TAG, "音频元数据提取完成 duration=${durationMs}ms sr=${metadata.sampleRate} ch=${metadata.channels}")

            ProcessedAudio(filePath = videoUri.toString(), metadata = metadata)
        } catch (e: SecurityException) {
            DebugLog.e(TAG, "权限不足，无法访问视频文件: ${e.message}", e)
            null
        } catch (e: IOException) {
            DebugLog.e(TAG, "IO异常，视频文件可能损坏或不存在: ${e.message}", e)
            null
        } catch (e: Exception) {
            DebugLog.e(TAG, "提取视频音频失败: ${e.message}", e)
            null
        }
    }

    fun extractAudioFromVideo(context: Context, videoPath: String): ProcessedAudio? {
        return extractAudioFromVideo(context, Uri.fromFile(File(videoPath)))
    }

    fun getAudioMetadata(context: Context, audioUri: Uri): AudioMetadata? {
        return try {
            val extractor = MediaExtractor().apply { setDataSource(context, audioUri, null) }
            val trackResult = selectAudioTrack(extractor)
            if (trackResult == null) {
                extractor.release()
                return null
            }

            val (_, format) = trackResult
            val durationUs = format.getLong(MediaFormat.KEY_DURATION, -1L)
            val durationMs = if (durationUs > 0) durationUs / 1000 else 0L
            val metadata = buildMetadataFromFormat(format, durationMs)
            extractor.release()
            metadata
        } catch (e: SecurityException) {
            DebugLog.e(TAG, "权限不足: ${e.message}")
            null
        } catch (e: Exception) {
            DebugLog.e(TAG, "获取音频元数据失败: ${e.message}", e)
            null
        }
    }

    // ==================== 核心解码功能 ====================

    /**
     * 将音频文件解码为 PCM 16bit 16kHz mono FloatArray。
     *
     * 支持格式：
     * - WAV (RIFF/PCM)：直接读取 + 重采样/混音
     * - 原始 PCM 文件：按指定参数读取
     * - 其他编码格式 (MP3/AAC/M4A/OGG 等)：通过 MediaCodec 硬件/软件解码
     *
     * @param context Android Context
     * @param audioUri 音频文件 URI (content:// 或 file://)
     * @return Pair(归一化 FloatArray[-1,1], AudioMetadata)，失败返回 null
     */
    fun decodeToPcm(context: Context, audioUri: Uri): Pair<FloatArray, AudioMetadata>? {
        return try {
            DebugLog.i(TAG, "decodeToPcm 开始 URI=$audioUri")

            val scheme = audioUri.scheme?.lowercase(Locale.getDefault())
            when {
                scheme == "file" -> {
                    val filePath = audioUri.path ?: return null
                    decodeFromFile(filePath)
                }
                else -> {
                    decodeViaMediaCodec(context, audioUri)
                }
            }
        } catch (e: SecurityException) {
            DebugLog.e(TAG, "decodeToPcm 权限不足: ${e.message}", e)
            null
        } catch (e: OutOfMemoryError) {
            DebugLog.e(TAG, "decodeToPcm 内存不足，文件可能过大", e as Throwable?)
            System.gc()
            null
        } catch (e: Exception) {
            DebugLog.e(TAG, "decodeToPcm 解码失败: ${e.message}", e)
            null
        }
    }

    /**
     * 将视频中的音频轨道完整解码为 PCM 16bit 16kHz mono FloatArray。
     *
     * 使用 MediaExtractor 提取音频轨道 + MediaCodec 解码为 PCM，
     * 最终输出统一为 [TARGET_SAMPLE_RATE] Hz 单声道归一化浮点数组。
     *
     * @param context Android Context
     * @param videoUri 视频文件 URI
     * @return Pair(FloatArray, AudioMetadata)，失败返回 null
     */
    fun decodeVideoAudioToPcm(context: Context, videoUri: Uri): Pair<FloatArray, AudioMetadata>? {
        return try {
            DebugLog.i(TAG, "decodeVideoAudioToPcm 开始 URI=$videoUri")
            decodeViaMediaCodec(context, videoUri)
        } catch (e: SecurityException) {
            DebugLog.e(TAG, "decodeVideoAudioToPcm 权限不足: ${e.message}", e)
            null
        } catch (e: OutOfMemoryError) {
            DebugLog.e(TAG, "decodeVideoAudioToPcm 内存不足", e as Throwable?)
            System.gc()
            null
        } catch (e: Exception) {
            DebugLog.e(TAG, "decodeVideoAudioToPcm 失败: ${e.message}", e)
            null
        }
    }

    // ==================== WAV 文件解析与读写 ====================

    /**
     * 解析 RIFF/WAV 文件头，提取采样率、通道数、位深度等参数。
     *
     * 支持：
     * - 标准 PCM 格式 (format=1)
     * - IEEE Float 格式 (format=3)
     * - Extensible 格式 (format=0xFFFE)
     *
     * @param inputStream 已打开的输入流（调用方负责关闭）
     * @return [WavHeader] 解析成功；文件格式非法返回 null
     */
    fun parseWavHeader(inputStream: java.io.InputStream): WavHeader? {
        return try {
            val dis = DataInputStream(BufferedInputStream(inputStream, 1024))
            val riffId = dis.readInt()
            if (riffId != WAV_RIFF_HEADER) {
                DebugLog.w(TAG, "parseWavHeader: 非 RIFF 文件 (0x${Integer.toHexString(riffId)})")
                return null
            }

            dis.readInt()
            val waveId = dis.readInt()
            if (waveId != 0x57415645) {
                DebugLog.w(TAG, "parseWavHeader: RIFF 但非 WAVE 容器")
                return null
            }

            var sampleRate = 0
            var channels = 0
            var bitsPerSample = 16
            var dataOffset = -1L
            var dataSize = 0

            while (true) {
                val chunkId = dis.readInt()
                val chunkSize = dis.readInt() and 0xFFFFFFFFL.toInt()
                when (chunkId) {
                    0x666D7420 -> {
                        val audioFormat = dis.readShort().toInt() and 0xFFFF
                        channels = dis.readShort().toInt() and 0xFFFF
                        sampleRate = dis.readInt()
                        dis.readInt()
                        dis.readShort()
                        bitsPerSample = dis.readShort().toInt() and 0xFFFF

                        if (audioFormat == WAVE_FORMAT_EXTENSIBLE && chunkSize >= 40) {
                            dis.readShort()
                            bitsPerSample = dis.readShort().toInt() and 0xFFFF
                            dis.skipBytes(8)
                        }

                        val remaining = chunkSize - 16
                        if (remaining > 0) dis.skipBytes(remaining.coerceAtMost(remaining))
                    }
                    0x64617461 -> {
                        dataOffset = (dis as java.io.InputStream).let { ins ->
                            val totalRead = 12 + 8 + chunkSize + 8
                            totalRead - chunkSize
                        }.toLong()
                        dataSize = chunkSize
                        break
                    }
                    else -> {
                        if (chunkSize > 0) dis.skipBytes(chunkSize.coerceAtMost(chunkSize))
                    }
                }
            }

            if (dataOffset < 0 || sampleRate <= 0 || channels <= 0) {
                DebugLog.w(TAG, "parseWavHeader: WAV 文件结构不完整 sr=$sampleRate ch=$channels dataOffset=$dataOffset")
                return null
            }

            WavHeader(sampleRate, channels, bitsPerSample, dataOffset, dataSize)
        } catch (e: EOFException) {
            DebugLog.w(TAG, "parseWavHeader: 文件过早结束")
            null
        } catch (e: IOException) {
            DebugLog.e(TAG, "parseWavHeader: IO错误 ${e.message}", e)
            null
        } catch (e: Exception) {
            DebugLog.e(TAG, "parseWavHeader: 解析异常 ${e.message}", e)
            null
        }
    }

    /**
     * 读取 WAV 文件并返回原始 PCM ShortArray + 元数据。
     *
     * 不做重采样/混音，返回文件的原始数据。如需标准输出请配合 [resample]、[monoMix] 使用。
     *
     * @param filePath WAV 文件绝对路径
     * @return Pair(ShortArray, AudioMetadata)；文件损坏/格式不支持返回 null
     */
    fun readWavFile(filePath: String): Pair<ShortArray, AudioMetadata>? {
        val file = File(filePath)
        if (!file.exists()) {
            DebugLog.w(TAG, "readWavFile: 文件不存在 $filePath")
            return null
        }
        if (!file.canRead()) {
            DebugLog.w(TAG, "readWavFile: 无读取权限 $filePath")
            return null
        }

        return try {
            FileInputStream(filePath).use { fis ->
                val header = parseWavHeader(fis) ?: run {
                    DebugLog.w(TAG, "readWavFile: WAV header 解析失败")
                    return null
                }

                val expectedBytes = header.dataSize
                val samplesCount = expectedBytes / (header.bitsPerSample / 8)

                if (samplesCount <= 0 || samplesCount > Int.MAX_VALUE / 2) {
                    DebugLog.w(TAG, "readWavFile: 数据大小异常 samples=$samplesCount bytes=$expectedBytes")
                    return null
                }

                val rawPcm = ByteArray(expectedBytes)
                var offset = 0
                while (offset < expectedBytes) {
                    val read = fis.read(rawPcm, offset, expectedBytes - offset)
                    if (read == -1) break
                    offset += read
                }

                val shortSamples = ShortArray(offset / 2)
                ByteBuffer.wrap(rawPcm, 0, offset).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shortSamples)

                val metadata = AudioMetadata(
                    durationMs = header.durationMs,
                    sampleRate = header.sampleRate,
                    channels = header.channels,
                    bitDepth = header.bitsPerSample,
                    format = "audio/wav",
                    fileSizeBytes = file.length(),
                )

                DebugLog.i(TAG, "readWavFile 成功 samples=${shortSamples.size} sr=${header.sampleRate} ch=${header.channels} bits=${header.bitsPerSample}")
                Pair(shortSamples, metadata)
            }
        } catch (e: OutOfMemoryError) {
            DebugLog.e(TAG, "readWavFile 内存不足，文件过大 (${file.length()} bytes)", e as Throwable?)
            System.gc()
            null
        } catch (e: Exception) {
            DebugLog.e(TAG, "readWavFile 读取失败: ${e.message}", e)
            null
        }
    }

    // ==================== 格式转换工具 ====================

    /**
     * 将 ShortArray (PCM 16bit 有符号整数) 转换为 FloatArray (归一化 [-1.0, 1.0])。
     *
     * 转换公式：f = s / 32768.0
     *
     * @param shortArray 输入 PCM 16bit 样本数组
     * @return 归一化浮点数组，长度与输入相同
     */
    fun shortArrayToFloatArray(shortArray: ShortArray): FloatArray {
        val result = FloatArray(shortArray.size)
        val invScale = 1.0f / 32768.0f
        for (i in shortArray.indices) {
            result[i] = shortArray[i] * invScale
        }
        return result
    }

    /**
     * 将 FloatArray (归一化 [-1.0, 1.0]) 转换回 ShortArray (PCM 16bit)。
     *
     * @param floatArray 归一化浮点样本数组
     * @return PCM 16bit 有符号整数数组
     */
    fun floatArrayToShortArray(floatArray: FloatArray): ShortArray {
        val result = ShortArray(floatArray.size)
        for (i in floatArray.indices) {
            val sample = (floatArray[i] * 32767.0f).coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat())
            result[i] = sample.toInt().toShort()
        }
        return result
    }

    /**
     * 重采样：将任意采样率的音频转换为目标采样率（默认 [TARGET_SAMPLE_RATE]=16000Hz）。
     *
     * 使用线性插值算法，在 Android 设备上性能良好且质量可接受。
     * 当源采样率与目标相同时直接返回原数组的副本引用优化。
     *
     * @param inputSamples 输入浮点样本数组
     * @param fromSampleRate 源采样率 (Hz)
     * @param toSampleRate 目标采样率 (Hz)，默认 16000
     * @return 重采样后的浮点数组
     */
    fun resample(inputSamples: FloatArray, fromSampleRate: Int, toSampleRate: Int = TARGET_SAMPLE_RATE): FloatArray {
        if (fromSampleRate == toSampleRate) return inputSamples
        if (inputSamples.isEmpty()) return FloatArray(0)

        val ratio = fromSampleRate.toDouble() / toSampleRate.toDouble()
        val outputLength = ((inputSamples.size.toDouble() / ratio)).toInt().coerceAtLeast(0)
        val output = FloatArray(outputLength)

        for (i in 0 until outputLength) {
            val position = i.toDouble() * ratio
            val index = position.toInt()
            val fraction = position - index.toDouble()

            if (index + 1 < inputSamples.size) {
                output[i] = (inputSamples[index] * (1.0 - fraction) + inputSamples[index + 1] * fraction).toFloat()
            } else if (index < inputSamples.size) {
                output[i] = inputSamples[index]
            }
        }

        DebugLog.d(TAG, "resample: ${fromSampleRate}Hz → ${toSampleRate}Hz ${inputSamples.size} → ${output.size} samples")
        return output
    }

    /**
     * 多声道转单声道：将交错排列的多声道样本取平均值混合为单声道。
     *
     * 输入布局假设为 interleaved: [L0, R0, L1, R1, ...]
     * 对于单声道输入直接返回副本。
     *
     * @param multiChannelSamples 多声道交错浮点样本数组
     * @param channels 声道数 (1=mono, 2=stereo, ...)
     * @return 单声道浮点数组
     */
    fun monoMix(multiChannelSamples: FloatArray, channels: Int): FloatArray {
        if (channels <= 1) return multiChannelSamples.copyOf()
        if (multiChannelSamples.isEmpty()) return FloatArray(0)

        val frameCount = multiChannelSamples.size / channels
        val output = FloatArray(frameCount)

        val invChannels = 1.0f / channels
        for (frame in 0 until frameCount) {
            var sum = 0.0f
            val base = frame * channels
            for (ch in 0 until channels) {
                sum += multiChannelSamples[base + ch]
            }
            output[frame] = sum * invChannels
        }

        DebugLog.d(TAG, "monoMix: ${channels}ch → mono ${multiChannelSamples.size} → ${output.size} samples")
        return output
    }

    // ==================== 增强的分段策略 ====================

    /**
     * 基于静音检测的智能分段策略。
     *
     * 相比固定时间切分：
     * - 在静音区域寻找自然切分点，避免截断语音
     * - 每段不超过 [MAX_SEGMENT_MS]，适配模型最大接受长度
     * - 段间重叠 [SEGMENT_OVERLAP_MS]，减少边界信息丢失
     *
     * 当无法获取样本数据时退化为固定时长切分（向后兼容）。
     *
     * @param durationMs 音频总时长 (ms)
     * @param segmentLengthMs 目标段长 (ms)，默认 [DEFAULT_SEGMENT_LENGTH_MS]
     * @param samples 可选的 PCM 浮点样本数据，用于静音检测
     * @param sampleRate 样本数据的采样率
     * @return 分段列表
     */
    fun segmentAudio(
        durationMs: Long,
        segmentLengthMs: Long = DEFAULT_SEGMENT_LENGTH_MS,
        samples: FloatArray? = null,
        sampleRate: Int = TARGET_SAMPLE_RATE,
    ): List<AudioSegment> {
        if (durationMs <= 0) return emptyList()

        if (durationMs <= segmentLengthMs) {
            return listOf(AudioSegment("", 0, durationMs, 0))
        }

        if (samples != null && samples.isNotEmpty() && sampleRate > 0) {
            val smartSegments = segmentBySilenceDetection(durationMs, segmentLengthMs, samples, sampleRate)
            if (smartSegments.isNotEmpty()) {
                DebugLog.i(TAG, "智能分段完成 共${smartSegments.size}段 (基于静音检测)")
                return smartSegments
            }
        }

        return segmentWithOverlap(durationMs, segmentLengthMs).also {
            DebugLog.i(TAG, "重叠分段完成 共${it.size}段 (固定时长)")
        }
    }

    /** 向后兼容的原始签名 */
    fun segmentAudio(durationMs: Long, segmentLengthMs: Long = DEFAULT_SEGMENT_LENGTH_MS): List<AudioSegment> {
        return segmentAudio(durationMs, segmentLengthMs, null, TARGET_SAMPLE_RATE)
    }

    // ==================== 文件保存 ====================

    /**
     * 将归一化 FloatArray 保存为标准 RIFF/WAV 文件。
     *
     * 用于调试导出或中间结果持久化。输出格式：
     * - PCM 16-bit little-endian
     * - 采样率/通道数来自 [AudioMetadata]
     *
     * @param samples 归一化 [-1.0, 1.0] 浮点样本
     * @param metadata 音频元数据（采样率、通道数等）
     * @param outputPath 输出文件绝对路径
     * @return true 写入成功；磁盘空间不足/IO 错误返回 false
     */
    fun saveAsWav(samples: FloatArray, metadata: AudioMetadata, outputPath: String): Boolean {
        val outFile = File(outputPath)
        val parentDir = outFile.parentFile
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs()
        }

        if (!checkDiskSpace(parentDir ?: File("."), samples.size * 2L + 44L)) {
            DebugLog.e(TAG, "saveAsWav: 磁盘空间不足")
            return false
        }

        return try {
            val shortData = floatArrayToShortArray(samples)
            val pcmDataSize = shortData.size * 2
            val fileSize = 36 + pcmDataSize

            FileOutputStream(outputPath).use { fos ->
                BufferedOutputStream(fos, 8192).use { bos ->
                    DataOutputStream(bos).use { dos ->
                        dos.writeInt(WAV_RIFF_HEADER)
                        dos.writeInt(fileSize)
                        dos.writeInt(0x57414645)
                        dos.writeInt(0x20746D66)
                        dos.writeInt(16)
                        dos.writeShort(WAVE_FORMAT_PCM)
                        dos.writeShort(metadata.channels)
                        dos.writeInt(metadata.sampleRate)
                        dos.writeInt(metadata.sampleRate * metadata.channels * (metadata.bitDepth / 8))
                        dos.writeShort(metadata.channels * (metadata.bitDepth / 8))
                        dos.writeShort(metadata.bitDepth)
                        dos.writeInt(0x61746164)
                        dos.writeInt(pcmDataSize)

                        for (s in shortData) dos.writeShort(s.toInt())
                    }
                }
            }

            DebugLog.i(TAG, "saveAsWav 成功 path=$outputPath size=${outFile.length()}bytes samples=${samples.size}")
            true
        } catch (e: IOException) {
            DebugLog.e(TAG, "saveAsWav 写入失败: ${e.message}", e)
            false
        } catch (e: Exception) {
            DebugLog.e(TAG, "saveAsWav 异常: ${e.message}", e)
            false
        }
    }

    // ==================== 验证与工具方法（保留原有签名）====================

    fun validateAudioFile(context: Context, uri: Uri): Pair<Boolean, String?> {
        return try {
            val metadata = getAudioMetadata(context, uri)
            when {
                metadata == null -> Pair(false, "无法读取音频文件或格式不支持。支持 MP3/AAC/WAV/M4A/OGG 及常见视频封装中的音频轨道。")
                metadata.durationMs == 0L -> Pair(false, "音频文件时长为 0 或无法获取时长，文件可能损坏。")
                metadata.durationMs > 1800_000L -> Pair(false, "音频时长超过 30 分钟限制 (${String.format(Locale.US, "%.1f", metadata.durationMs / 1000.0)}秒)。建议先裁剪再处理。")
                metadata.sampleRate < 8000 || metadata.sampleRate > 192000 -> Pair(false, "采样率异常 (${metadata.sampleRate}Hz)，文件可能损坏。")
                else -> Pair(true, null)
            }
        } catch (e: SecurityException) {
            Pair(false, "无权访问该文件，请检查存储权限设置。")
        } catch (e: Exception) {
            DebugLog.e(TAG, "验证音频文件失败: ${e.message}")
            Pair(false, "验证失败: ${e.message}")
        }
    }

    fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val secs = totalSeconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs)
        else "%d:%02d".format(minutes, secs)
    }

    // ==================== 内部实现 ====================

    private data class TrackSelection(val index: Int, val format: MediaFormat)

    private fun selectAudioTrack(extractor: MediaExtractor): TrackSelection? {
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) return TrackSelection(i, fmt)
        }
        return null
    }

    private fun buildMetadataFromFormat(format: MediaFormat, durationMs: Long): AudioMetadata {
        return AudioMetadata(
            durationMs = durationMs,
            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE, TARGET_SAMPLE_RATE),
            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT, TARGET_CHANNELS),
            bitDepth = TARGET_BIT_DEPTH,
            format = format.getString(MediaFormat.KEY_MIME) ?: "audio/unknown",
        )
    }

    private fun decodeFromFile(filePath: String): Pair<FloatArray, AudioMetadata>? {
        val lowerPath = filePath.lowercase(Locale.getDefault())
        return when {
            lowerPath.endsWith(".wav") || lowerPath.endsWith(".wave") -> decodeWavFile(filePath)
            lowerPath.endsWith(".pcm") || lowerPath.endsWith(".raw") -> decodeRawPcmFile(filePath)
            else -> {
                DebugLog.w(TAG, "decodeFromFile: 未识别的扩展名，尝试通过 MediaCodec 解码: $filePath")
                decodeViaMediaCodec(null, Uri.fromFile(File(filePath)))
            }
        }
    }

    private fun decodeWavFile(filePath: String): Pair<FloatArray, AudioMetadata>? {
        val pair = readWavFile(filePath) ?: return null
        val (rawShorts, meta) = pair

        val floatSamples = shortArrayToFloatArray(rawShorts)
        val processed = processToTarget(floatSamples, meta.sampleRate, meta.channels)

        val outMeta = meta.copy(
            sampleRate = TARGET_SAMPLE_RATE,
            channels = TARGET_CHANNELS,
            durationMs = if (TARGET_SAMPLE_RATE > 0) (processed.size.toLong() * 1000L / TARGET_SAMPLE_RATE) else 0L,
        )

        DebugLog.i(TAG, "decodeWavFile 完成: ${rawShorts.size} shorts → ${processed.size} floats")
        return Pair(processed, outMeta)
    }

    private fun decodeRawPcmFile(filePath: String): Pair<FloatArray, AudioMetadata>? {
        val file = File(filePath)
        if (!file.canRead()) {
            DebugLog.w(TAG, "decodeRawPcmFile: 无法读取 $filePath")
            return null
        }

        return try {
            val rawBytes = file.readBytes()
            val sampleCount = rawBytes.size / 2
            if (sampleCount <= 0) return null

            val shortSamples = ShortArray(sampleCount)
            ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shortSamples)
            val floatSamples = shortArrayToFloatArray(shortSamples)
            val processed = resample(floatSamples, TARGET_SAMPLE_RATE)

            val meta = AudioMetadata(
                durationMs = if (TARGET_SAMPLE_RATE > 0) (processed.size.toLong() * 1000L / TARGET_SAMPLE_RATE) else 0L,
                sampleRate = TARGET_SAMPLE_RATE,
                channels = TARGET_CHANNELS,
                bitDepth = TARGET_BIT_DEPTH,
                format = "audio/raw-pcm",
                fileSizeBytes = file.length(),
            )
            Pair(processed, meta)
        } catch (e: OutOfMemoryError) {
            DebugLog.e(TAG, "decodeRawPcmFile 内存不足", e as Throwable?)
            System.gc()
            null
        } catch (e: Exception) {
            DebugLog.e(TAG, "decodeRawPcmFile 失败: ${e.message}", e)
            null
        }
    }

    private fun decodeViaMediaCodec(context: Context?, uri: Uri): Pair<FloatArray, AudioMetadata>? {
        val extractor = try {
            MediaExtractor().apply {
                if (context != null) setDataSource(context, uri, null)
                else setDataSource(uri.toString())
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "MediaExtractor 初始化失败: ${e.message}", e)
            return null
        }

        val trackResult = selectAudioTrack(extractor)
        if (trackResult == null) {
            DebugLog.w(TAG, "未找到音频轨道")
            extractor.release()
            return null
        }

        val (trackIndex, format) = trackResult
        extractor.selectTrack(trackIndex)

        val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
        val srcSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE, TARGET_SAMPLE_RATE)
        val srcChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT, TARGET_CHANNELS)
        val durationUs = format.getLong(MediaFormat.KEY_DURATION, -1L)
        val durationMs = if (durationUs > 0) durationUs / 1000 else 0L

        val codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (e: Exception) {
            DebugLog.e(TAG, "不支持的编解码器: $mime。建议使用 WAV (PCM) 或 MP3 格式。", e)
            extractor.release()
            return null
        }

        return try {
            codec.configure(format, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            val pcmBuffers = mutableListOf<ByteArray>()
            var eosInput = false
            var eosOutput = false
            var totalPcmSize = 0

            while (!eosOutput) {
                if (!eosInput) {
                    val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                            ?: continue
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosInput = true
                        } else {
                            val presentationTimeUs = extractor.sampleTime
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, presentationTimeUs, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
                when (outputIndex) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {}
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {}
                    else -> {
                        if (outputIndex >= 0) {
                            if (bufferInfo.size > 0) {
                                val outputBuffer = codec.getOutputBuffer(outputIndex)
                                if (outputBuffer != null && outputBuffer.hasRemaining()) {
                                    val pcmChunk = ByteArray(bufferInfo.size)
                                    outputBuffer.get(pcmChunk)
                                    pcmBuffers.add(pcmChunk)
                                    totalPcmSize += bufferInfo.size
                                }
                            }
                            codec.releaseOutputBuffer(outputIndex, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                eosOutput = true
                            }
                        }
                    }
                }
            }

            val allPcm = ByteArray(totalPcmSize)
            var pos = 0
            for (chunk in pcmBuffers) {
                System.arraycopy(chunk, 0, allPcm, pos, chunk.size)
                pos += chunk.size
            }

            val shortSamples = ShortArray(pos / 2)
            ByteBuffer.wrap(allPcm, 0, pos).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shortSamples)
            val floatSamples = shortArrayToFloatArray(shortSamples)
            val processed = processToTarget(floatSamples, srcSampleRate, srcChannels)

            val outMeta = AudioMetadata(
                durationMs = durationMs,
                sampleRate = TARGET_SAMPLE_RATE,
                channels = TARGET_CHANNELS,
                bitDepth = TARGET_BIT_DEPTH,
                format = mime,
            )

            DebugLog.i(TAG, "MediaCodec 解码完成 mime=$mime raw=${shortSamples.size} out=${processed.size}")
            Pair(processed, outMeta)
        } catch (e: Exception) {
            DebugLog.e(TAG, "MediaCodec 解码过程异常: ${e.message}", e)
            null
        } finally {
            try { codec.stop(); codec.release() } catch (_: Exception) {}
            extractor.release()
        }
    }

    private fun processToTarget(samples: FloatArray, srcSampleRate: Int, srcChannels: Int): FloatArray {
        val mixed = if (srcChannels > 1) monoMix(samples, srcChannels) else samples
        return if (srcSampleRate != TARGET_SAMPLE_RATE) resample(mixed, srcSampleRate) else mixed
    }

    private fun segmentBySilenceDetection(
        durationMs: Long,
        maxSegmentMs: Long,
        samples: FloatArray,
        sampleRate: Int,
    ): List<AudioSegment> {
        val windowMs = 20L
        val windowSize = (sampleRate * windowMs / 1000).coerceAtLeast(16).toInt()
        val hopSize = windowSize / 2
        val silenceThresholdDb = SILENCE_THRESHOLD_DB
        val minSilenceFrames = (SILENCE_MIN_DURATION_MS * sampleRate / 1000 / hopSize).coerceAtLeast(2).toInt()

        val energyDb = computeEnergyDb(samples, windowSize, hopSize)
        val isSilent = energyDb.map { it < silenceThresholdDb }.toBooleanArray()

        val silenceRegions = findSilenceRegions(isSilent, minSilenceFrames)
        if (silenceRegions.isEmpty()) return segmentWithOverlap(durationMs, maxSegmentMs)

        val segments = mutableListOf<AudioSegment>()
        var currentStart = 0L
        var segIdx = 0

        for ((silenceStartFrame, silenceEndFrame) in silenceRegions) {
            val silenceCenterMs = ((silenceStartFrame + silenceEndFrame) / 2 * hopSize * 1000L / sampleRate)
            val proposedEnd = silenceCenterMs + SEGMENT_OVERLAP_MS / 2

            if (proposedEnd - currentStart >= maxSegmentMs * 0.5) {
                segments.add(AudioSegment("", currentStart, proposedEnd.coerceAtMost(durationMs), segIdx++))
                currentStart = silenceCenterMs - SEGMENT_OVERLAP_MS / 2
            }
        }

        if (currentStart < durationMs) {
            segments.add(AudioSegment("", currentStart, durationMs, segIdx))
        }

        return mergeAndConstrainSegments(segments, durationMs, maxSegmentMs)
    }

    private fun computeEnergyDb(samples: FloatArray, windowSize: Int, hopSize: Int): DoubleArray {
        val numFrames = (samples.size - windowSize) / hopSize + 1
        val frames = numFrames.coerceAtLeast(1)
        val energy = DoubleArray(frames)

        for (i in 0 until frames) {
            val start = i * hopSize
            val end = (start + windowSize).coerceAtMost(samples.size)
            var sumSq = 0.0
            for (j in start until end) {
                val s = samples[j]
                sumSq += s * s
            }
            val rms = if (end > start) kotlin.math.sqrt(sumSq / (end - start)) else 0.0
            energy[i] = if (rms > 1e-8) 20.0 * kotlin.math.log10(rms) else -96.0
        }
        return energy
    }

    private fun findSilenceRegions(isSilent: BooleanArray, minSilenceFrames: Int): List<Pair<Int, Int>> {
        val regions = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i < isSilent.size) {
            if (isSilent[i]) {
                val start = i
                while (i < isSilent.size && isSilent[i]) i++
                if (i - start >= minSilenceFrames) {
                    regions.add(Pair(start, i))
                }
            } else {
                i++
            }
        }
        return regions
    }

    private fun segmentWithOverlap(durationMs: Long, segmentLengthMs: Long): List<AudioSegment> {
        val effectiveLen = minOf(segmentLengthMs, MAX_SEGMENT_MS)
        val segments = mutableListOf<AudioSegment>()
        var currentPos = 0L
        var index = 0

        while (currentPos < durationMs) {
            val endPos = minOf(currentPos + effectiveLen, durationMs)
            segments.add(AudioSegment("", currentPos, endPos, index++))
            val nextStart = endPos - SEGMENT_OVERLAP_MS
            currentPos = if (nextStart > currentPos && nextStart < durationMs) nextStart else endPos
        }

        return segments
    }

    private fun mergeAndConstrainSegments(
        segments: MutableList<AudioSegment>,
        durationMs: Long,
        maxLen: Long,
    ): List<AudioSegment> {
        if (segments.isEmpty()) return listOf(AudioSegment("", 0, durationMs, 0))

        val merged = mutableListOf<AudioSegment>()
        var current = segments[0]

        for (i in 1 until segments.size) {
            val next = segments[i]
            val combinedEnd = next.endTimeMs
            if (combinedEnd - current.startTimeMs <= maxLen) {
                current = current.copy(endTimeMs = combinedEnd)
            } else {
                merged.add(current)
                current = next.copy(index = merged.size)
            }
        }
        merged.add(current.copy(index = merged.size))

        merged.forEachIndexed { idx, seg ->
            merged[idx] = seg.copy(startTimeMs = seg.startTimeMs.coerceAtLeast(0), endTimeMs = seg.endTimeMs.coerceAtMost(durationMs), index = idx)
        }

        return merged.filter { it.endTimeMs > it.startTimeMs }
    }

    private fun checkDiskSpace(directory: File, requiredBytes: Long): Boolean {
        return try {
            val freeSpace = directory.usableSpace
            freeSpace >= requiredBytes * 2
        } catch (e: Exception) {
            DebugLog.w(TAG, "checkDiskSpace 检测失败，继续尝试写入: ${e.message}")
            true
        }
    }
}
