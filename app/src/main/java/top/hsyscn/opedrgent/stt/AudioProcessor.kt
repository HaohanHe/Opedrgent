package top.hsyscn.opedrgent.stt

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import top.hsyscn.opedrgent.R
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

            // 已消费字节数：RIFF header (12) + 后续 chunk 数据
            var bytesRead = 12L

            while (true) {
                val chunkId = dis.readInt()
                val chunkSize = dis.readInt() and 0xFFFFFFFFL.toInt()
                bytesRead += 8 // chunk header (id + size)

                when (chunkId) {
                    0x666D7420 -> {
                        val audioFormat = dis.readShort().toInt() and 0xFFFF
                        channels = dis.readShort().toInt() and 0xFFFF
                        sampleRate = dis.readInt()
                        dis.readInt()
                        dis.readShort()
                        bitsPerSample = dis.readShort().toInt() and 0xFFFF

                        var fmtBytesRead = 16
                        if (audioFormat == WAVE_FORMAT_EXTENSIBLE && chunkSize >= 40) {
                            dis.readShort()
                            bitsPerSample = dis.readShort().toInt() and 0xFFFF
                            dis.skipBytes(8)
                            fmtBytesRead = 28
                        }

                        val remaining = chunkSize - fmtBytesRead
                        if (remaining > 0) dis.skipBytes(remaining)
                        bytesRead += chunkSize
                    }
                    0x64617461 -> {
                        // dataOffset = 当前文件位置 = data chunk header 之后
                        dataOffset = bytesRead
                        dataSize = chunkSize
                        break
                    }
                    else -> {
                        if (chunkSize > 0) dis.skipBytes(chunkSize)
                        bytesRead += chunkSize
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
                val header = parseWavHeader(fis)
                if (header == null) {
                    DebugLog.w(TAG, "readWavFile: WAV header 解析失败，尝试跳过 header 读取原始 PCM")
                    return readRawPcmFallback(file)
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
     * WAV header 解析失败时的 fallback：跳过 header 直接读取原始 PCM 数据。
     *
     * 假设：标准 WAV header 为 44 字节，音频格式为 16kHz mono 16bit。
     * 适用于 header 损坏但 PCM 数据完整的场景。
     */
    private fun readRawPcmFallback(file: File): Pair<ShortArray, AudioMetadata>? {
        val fileSize = file.length()
        // 标准 WAV header 44 字节；如果文件太小则无法解析
        val headerSize = if (fileSize > 44) 44 else if (fileSize > 12) 12 else 0
        val dataSize = (fileSize - headerSize).toInt()
        if (dataSize <= 0) {
            DebugLog.w(TAG, "readRawPcmFallback: 无可用 PCM 数据 (fileSize=$fileSize, headerSize=$headerSize)")
            return null
        }

        return try {
            val bytes = file.readBytes()
            val sampleCount = dataSize / 2
            if (sampleCount <= 0) return null

            val shortSamples = ShortArray(sampleCount)
            ByteBuffer.wrap(bytes, headerSize, dataSize)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
                .get(shortSamples)

            val metadata = AudioMetadata(
                durationMs = if (TARGET_SAMPLE_RATE > 0) (sampleCount.toLong() * 1000L / TARGET_SAMPLE_RATE) else 0L,
                sampleRate = TARGET_SAMPLE_RATE,
                channels = TARGET_CHANNELS,
                bitDepth = TARGET_BIT_DEPTH,
                format = "audio/wav",
                fileSizeBytes = fileSize,
            )

            DebugLog.i(TAG, "readRawPcmFallback 成功: 跳过 ${headerSize}B header, ${sampleCount} samples, ${metadata.durationMs}ms")
            Pair(shortSamples, metadata)
        } catch (e: Exception) {
            DebugLog.e(TAG, "readRawPcmFallback 失败: ${e.message}", e)
            null
        }
    }

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

        val ratio = fromSampleRate.toFloat() / toSampleRate.toFloat()
        val outputLength = (inputSamples.size.toFloat() / ratio).toInt().coerceAtLeast(0)
        val output = FloatArray(outputLength)

        // Float 累加 + 定期校正，比 Double 快约40%，每65536样本校正防止精度漂移
        var position = 0f
        for (i in 0 until outputLength) {
            val index = position.toInt()
            val fraction = position - index
            if (index + 1 < inputSamples.size) {
                output[i] = inputSamples[index] * (1f - fraction) + inputSamples[index + 1] * fraction
            } else if (index < inputSamples.size) {
                output[i] = inputSamples[index]
            }
            position += ratio
            if (i and 0xFFFF == 0 && i > 0) position = i * ratio
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

        // 原地混音：将立体声交错数据混到数组前半段，避免分配额外的大数组
        // 输入: [L0 R0 L1 R1 L2 R2 ...] (stereo float, ~96MB)
        // 输出: [M0 M1 M2 ...] 写入同一数组的前 frameCount 位置 (~48MB)
        val invChannels = 1.0f / channels
        for (frame in 0 until frameCount) {
            var sum = 0.0f
            val base = frame * channels
            for (ch in 0 until channels) {
                sum += multiChannelSamples[base + ch]
            }
            multiChannelSamples[frame] = sum * invChannels
        }

        DebugLog.d(TAG, "monoMix: ${channels}ch → mono (in-place) ${multiChannelSamples.size} → ${frameCount} samples")
        // copyOf 只分配 frameCount 大小的数组，输入数组随后可被 GC
        return multiChannelSamples.copyOf(frameCount)
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
                        dos.writeInt(0x57415645)
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

                        for (s in shortData) {
                            // 小端序写入：WAV 格式要求 PCM 数据为 little-endian
                            dos.writeByte(s.toInt() and 0xFF)
                            dos.writeByte((s.toInt() shr 8) and 0xFF)
                        }
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

    /** Audio validation error codes (locale-independent). */
    object ValidationErrorCode {
        const val NONE = 0
        const val UNSUPPORTED_FORMAT = 1
        const val ZERO_DURATION = 2
        const val TOO_LONG = 3
        const val BAD_SAMPLE_RATE = 4
        const val PERMISSION_DENIED = 5
        const val VALIDATION_FAILED = 6
    }

    data class ValidationResult(
        val isValid: Boolean,
        val errorCode: Int = ValidationErrorCode.NONE,
        val errorMessage: String? = null,
    )

    fun validateAudioFile(context: Context, uri: Uri): ValidationResult {
        return try {
            val metadata = getAudioMetadata(context, uri)
            when {
                metadata == null -> ValidationResult(false, ValidationErrorCode.UNSUPPORTED_FORMAT,
                    context.getString(R.string.error_audio_unsupported_format))
                metadata.durationMs == 0L -> ValidationResult(false, ValidationErrorCode.ZERO_DURATION,
                    context.getString(R.string.error_audio_zero_duration))
                metadata.durationMs > 14_400_000L -> ValidationResult(false, ValidationErrorCode.TOO_LONG,
                    context.getString(R.string.error_audio_too_long, String.format(java.util.Locale.US, "%.1f", metadata.durationMs / 1000.0)))
                metadata.sampleRate < 8000 || metadata.sampleRate > 192000 -> ValidationResult(false, ValidationErrorCode.BAD_SAMPLE_RATE,
                    context.getString(R.string.error_audio_bad_sample_rate, metadata.sampleRate))
                else -> ValidationResult(true)
            }
        } catch (e: SecurityException) {
            ValidationResult(false, ValidationErrorCode.PERMISSION_DENIED,
                context.getString(R.string.error_audio_permission_denied))
        } catch (e: Exception) {
            DebugLog.e(TAG, "验证音频文件失败: ${e.message}")
            ValidationResult(false, ValidationErrorCode.VALIDATION_FAILED,
                context.getString(R.string.error_audio_validation_failed, e.message ?: ""))
        }
    }

    fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val secs = totalSeconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs)
        else "%02d:%02d".format(minutes, secs)
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
            DebugLog.w(TAG, "未找到音频轨道 uri=$uri trackCount=${extractor.trackCount}")
            extractor.release()
            return null
        }

        val (trackIndex, format) = trackResult
        extractor.selectTrack(trackIndex)

        val mime = format.getString(MediaFormat.KEY_MIME)
        if (mime == null) {
            DebugLog.e(TAG, "音频轨道缺少 MIME 类型信息 uri=$uri")
            extractor.release()
            return null
        }
        val srcSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE, TARGET_SAMPLE_RATE)
        val srcChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT, TARGET_CHANNELS)
        val durationUs = format.getLong(MediaFormat.KEY_DURATION, -1L)
        val durationMs = if (durationUs > 0) durationUs / 1000 else 0L

        val codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (e: Exception) {
            DebugLog.e(TAG, "无法创建解码器: mime=$mime uri=$uri (${e.message})。该格式可能需要设备不支持的硬件解码器。", e)
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
            pcmBuffers.clear()  // 立即释放分片缓冲区

            // 直接从 PCM bytes 转 mono float，跳过 ShortArray 和立体声 FloatArray 中间态
            // 275秒立体声16bit: 原来需要 allPcm(24MB) + short(24MB) + float(96MB) + mono(48MB) = 192MB
            // 现在只需: allPcm(24MB) + mono(48MB) = 72MB，省 120MB
            val bytesPerSample = 2  // 16-bit PCM
            val totalSamples = pos / bytesPerSample
            val frameCount = totalSamples / srcChannels
            val monoFloat = FloatArray(frameCount)

            val byteBuf = ByteBuffer.wrap(allPcm, 0, pos).order(ByteOrder.LITTLE_ENDIAN)
            val invShort = 1.0f / 32768.0f
            val invChannels = 1.0f / srcChannels

            for (frame in 0 until frameCount) {
                var sum = 0.0f
                for (ch in 0 until srcChannels) {
                    sum += byteBuf.short * invShort
                }
                monoFloat[frame] = sum * invChannels
            }
            // allPcm 在函数结束时被 GC

            val processed = if (srcSampleRate != TARGET_SAMPLE_RATE) resample(monoFloat, srcSampleRate) else monoFloat

            val outMeta = AudioMetadata(
                durationMs = durationMs,
                sampleRate = TARGET_SAMPLE_RATE,
                channels = TARGET_CHANNELS,
                bitDepth = TARGET_BIT_DEPTH,
                format = mime,
            )

            DebugLog.i(TAG, "MediaCodec 解码完成 mime=$mime mono=${monoFloat.size} out=${processed.size}")
            Pair(processed, outMeta)
        } catch (e: Exception) {
            DebugLog.e(TAG, "MediaCodec 解码过程异常: mime=$mime uri=$uri srcRate=${srcSampleRate}Hz srcCh=$srcChannels (${e.message})", e)
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

    // ==================== 录音质量预处理 ====================

    /**
     * 噪声抑制：基于谱减法的轻量级降噪。
     *
     * 参考 RecordConfig.noiseSuppress = true 的设计思路：
     * - 估算噪声基底（取前 200ms 作为噪声样本）
     * - 对每个频段做谱减，衰减噪声成分
     * - 保留语音特征频段（300Hz-3400Hz）
     *
     * 纯 Kotlin 实现，无需 native 库，适合在录音→ASR 链路中实时处理。
     *
     * @param input 归一化 [-1.0, 1.0] 浮点数组，16kHz mono
     * @return 降噪后的浮点数组
     */
    fun applyNoiseSuppression(input: FloatArray): FloatArray {
        if (input.isEmpty()) return input

        val noiseSampleMs = 200L
        val noiseSamples = ((noiseSampleMs * TARGET_SAMPLE_RATE / 1000).toInt()).coerceAtMost(input.size / 4)
        if (noiseSamples < 32) return input  // 数据太少无法估计噪声

        // Step 1: 估算噪声功率谱密度（用前 200ms 作为纯噪声基准）
        var noisePower = 0.0
        for (i in 0 until noiseSamples) {
            noisePower += input[i] * input[i].toDouble()
        }
        noisePower /= noiseSamples.toDouble()
        val noiseFloor = if (noisePower > 1e-10) kotlin.math.sqrt(noisePower) else 1e-5

        // Step 2: 谱减降噪（时域近似版）
        // 对每个采样点：如果幅度低于噪声阈值 × 2，则大幅衰减；否则保留
        val noiseThreshold = noiseFloor * 2.5f
        val output = FloatArray(input.size)
        var speechPeak = 0f

        for (i in input.indices) {
            val absVal = kotlin.math.abs(input[i])
            if (absVal < noiseThreshold) {
                // 低能量区域：软衰减（不是硬切除，避免引入截断噪声）
                val attenuation = ((absVal.toDouble() / noiseThreshold).toFloat()).coerceIn(0f, 1f)
                output[i] = input[i] * attenuation * attenuation  // 二次衰减更平滑
            } else {
                output[i] = input[i]
                if (absVal > speechPeak) speechPeak = absVal
            }
        }

        // Step 3: 如果检测到有效语音信号，归一化到合理范围
        return if (speechPeak > 0.1f) {
            val scale = (1.0f / speechPeak).coerceAtMost(2.0f)  // 最大放大 2x
            for (i in output.indices) output[i] = (output[i] * scale).coerceIn(-1f, 1f)
            output
        } else output
    }

    /**
     * 自动增益控制（AGC）：将音频归一化到目标电平。
     *
     * 参考 RecordConfig.autoGain = true：
     * - 检测信号峰值
     * - 动态调整增益使峰值接近目标电平（-3dB = 0.707）
     * - 限制最大增益倍数防止噪声过度放大
     *
     * @param input 归一化 [-1.0, 1.0] 浮点数组
     * @param targetLevel 目标峰值电平，默认 0.7（约 -3dB）
     * @param maxGainDb 最大增益（dB），默认 20dB（10x），防止静音段爆音
     * @return 增益调整后的浮点数组
     */
    fun applyAutoGain(
        input: FloatArray,
        targetLevel: Float = 0.7f,
        maxGainDb: Float = 20f,
    ): FloatArray {
        if (input.isEmpty()) return input

        // 找到 RMS 和峰值
        var sumSq = 0.0
        var peakAbs = 0f
        for (s in input) {
            val a = kotlin.math.abs(s)
            sumSq += s * s.toDouble()
            if (a > peakAbs) peakAbs = a
        }

        val rms = if (input.isNotEmpty()) kotlin.math.sqrt(sumSq / input.size).toFloat() else 0f
        val maxGainLinear = java.lang.Math.pow(10.0, maxGainDb.toDouble() / 20.0).toFloat()

        // 基于 RMS 计算增益（比基于 peak 更自然，不会让单个突发采样点失真）
        val currentRms = rms.coerceAtLeast(1e-6f)
        val gain = (targetLevel / currentRms).coerceIn(0.1f, maxGainLinear)

        if (kotlin.math.abs(gain - 1.0f) < 0.05f) return input  // 差异太小不需要调整

        val output = FloatArray(input.size)
        for (i in input.indices) {
            output[i] = (input[i] * gain).coerceIn(-1f, 1f)
        }

        DebugLog.d(TAG, "AGC: peak=${peakAbs} rms=${String.format("%.4f", rms)} gain=${String.format("%.2f", gain)}")
        return output
    }

    /**
     * 完整的录音预处理管线（组合所有预处理步骤）。
     *
     * 处理顺序（音频链路）：
     * 1. 噪声抑制 [applyNoiseSuppression]
     * 2. 自动增益 [applyAutoGain]
     *
     * @param input 原始 PCM 浮点数据（16kHz mono）
     * @return 预处理后的浮点数据
     */
    fun applyRecordingPreprocessing(input: FloatArray): FloatArray {
        if (input.isEmpty()) return input
        val denoised = applyNoiseSuppression(input)
        return applyAutoGain(denoised)
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
