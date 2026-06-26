package top.hsyscn.opedrgent.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.hsyscn.opedrgent.settings.ApiConfig
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class TtsPlayer(
    private val context: Context,
    private val apiSettings: top.hsyscn.opedrgent.settings.ApiSettings,
) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready: Boolean = false
    private var pendingLocale: Locale = Locale.CHINA
    private val isSpeaking = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var lastText: String = ""
    private var lastLocaleTag: String = "zh-CN"
    private var lastRate: Float = 1.0f
    private var lastPitch: Float = 1.0f
    private var lastMimoVoice: String = "冰糖"
    private var currentPlayerJob: Job? = null
    private val playerCancelled = AtomicBoolean(false)

    init {
        tts = TextToSpeech(appContext, this)
    }

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts?.language = pendingLocale
        }
    }

    fun speak(
        text: String,
        localeTag: String,
        rate: Float,
        pitch: Float,
        mimoVoice: String = "冰糖",
        downloadOnly: Boolean = false,
        forceLocal: Boolean = false,
        style: MimoTtsClient.StyleControl? = null,
    ) {
        val t = text.trim()
        if (t.isEmpty() || t == "null") return

        stop()

        lastText = t
        lastLocaleTag = localeTag
        lastRate = rate
        lastPitch = pitch
        lastMimoVoice = mimoVoice

        val useStepAudio = !forceLocal && apiSettings.getTtsEngine() == "stepaudio" && apiSettings.hasApiKey()
        val useMimo = !forceLocal && apiSettings.isTtsMimoEnabled() && apiSettings.hasApiKey()

        when {
            useStepAudio -> speakWithStepAudio(t, mimoVoice, downloadOnly)
            useMimo -> speakWithMimo(t, mimoVoice, downloadOnly, style)
            else -> {
                if (downloadOnly) {
                    DebugLog.w("TtsPlayer: downloadOnly 模式仅支持云端 TTS，已忽略")
                    isSpeaking.set(false)
                    return
                }
                speakWithAndroid(t, localeTag, rate, pitch)
            }
        }
    }

    private fun speakWithAndroid(t: String, localeTag: String, rate: Float, pitch: Float) {
        val engine = tts ?: return
        val locale = runCatching { Locale.forLanguageTag(localeTag) }.getOrNull() ?: Locale.CHINA
        pendingLocale = locale
        if (ready) {
            engine.language = locale
        }
        engine.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
        engine.setPitch(pitch.coerceIn(0.5f, 2.0f))

        val utteranceId = UUID.randomUUID().toString()
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(uttId: String?) {}
            override fun onDone(uttId: String?) {
                if (uttId == utteranceId) {
                    isSpeaking.set(false)
                    isPaused.set(false)
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(uttId: String?) {
                if (uttId == utteranceId) {
                    isSpeaking.set(false)
                    isPaused.set(false)
                }
            }
        })
        engine.speak(t, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        isSpeaking.set(true)
        isPaused.set(false)
    }

    private fun speakWithMimo(text: String, voiceId: String, downloadOnly: Boolean = false, style: MimoTtsClient.StyleControl? = null) {
        currentPlayerJob = scope.launch {
            playerCancelled.set(false)
            isSpeaking.set(true)
            isPaused.set(false)

            try {
                val apiKey = apiSettings.getApiKey()
                    ?: throw IllegalStateException("API密钥未设置，请在设置中填写API密钥")

                if (apiKey.isBlank()) {
                    DebugLog.w("MimoTts: API key is blank, falling back to Android TTS")
                    if (!downloadOnly) speakWithAndroid(text, lastLocaleTag, lastRate, lastPitch)
                    return@launch
                }

                val pcmBytes = if (style != null) {
                    MimoTtsClient.synthesizeAdvanced(
                        apiKey,
                        MimoTtsClient.SynthesizeRequest(text = text, voiceId = voiceId, style = style),
                    ).audioData
                } else {
                    MimoTtsClient.synthesize(apiKey, text, voiceId)
                }
                if (playerCancelled.get()) return@launch

                if (pcmBytes == null || pcmBytes.isEmpty()) {
                    DebugLog.w("MimoTts: synthesize returned null/empty, falling back to Android TTS")
                    if (!downloadOnly) speakWithAndroid(text, lastLocaleTag, lastRate, lastPitch)
                    return@launch
                }

                if (downloadOnly) {
                    // 下载到本地不播放
                    saveAudioToLocal(pcmBytes, text)
                    isSpeaking.set(false)
                    isPaused.set(false)
                } else {
                    withContext(Dispatchers.IO) {
                        playPcmAudio(pcmBytes)
                    }
                }
            } catch (e: Exception) {
                DebugLog.e("MimoTts speak error: ${e.message}", e)
                if (!playerCancelled.get() && !downloadOnly) {
                    speakWithAndroid(text, lastLocaleTag, lastRate, lastPitch)
                }
            }
        }
    }

    /**
     * 使用阶跃星辰 StepAudio 2.5 TTS 合成并播放。
     *
     * 核心差异化能力（MiMo 完全没有的）：
     * - Global Context：instruction 参数定义整段基调
     * - Inline Context：文本中 () 插入句内指令，不朗读只控制情绪
     * - Zero-shot 音色复刻：3秒参考音频克隆任意音色
     */
    private fun speakWithStepAudio(text: String, voiceId: String, downloadOnly: Boolean = false) {
        currentPlayerJob = scope.launch {
            playerCancelled.set(false)
            isSpeaking.set(true)
            isPaused.set(false)

            try {
                val apiKey = apiSettings.getApiKey()
                    ?: throw IllegalStateException("API密钥未设置")

                if (apiKey.isBlank()) {
                    DebugLog.w("StepAudioTts: API key is blank, falling back to Android TTS")
                    if (!downloadOnly) speakWithAndroid(text, lastLocaleTag, lastRate, lastPitch)
                    return@launch
                }

                val pcmBytes = StepAudioTtsClient.synthesize(apiKey, text, voiceId)
                if (playerCancelled.get()) return@launch

                if (pcmBytes == null || pcmBytes.isEmpty()) {
                    DebugLog.w("StepAudioTts: synthesize returned null/empty, falling back to Android TTS")
                    if (!downloadOnly) speakWithAndroid(text, lastLocaleTag, lastRate, lastPitch)
                    return@launch
                }

                if (downloadOnly) {
                    saveAudioToLocal(pcmBytes, text)
                    isSpeaking.set(false)
                    isPaused.set(false)
                } else {
                    withContext(Dispatchers.IO) {
                        playPcmAudio(pcmBytes)
                    }
                }
            } catch (e: Exception) {
                DebugLog.e("StepAudioTts speak error: ${e.message}", e)
                if (!playerCancelled.get() && !downloadOnly) {
                    speakWithAndroid(text, lastLocaleTag, lastRate, lastPitch)
                }
            }
        }
    }

    /**
     * 将 MiMO TTS 合成的音频保存到本地文件系统。
     * 存储路径：/data/data/<pkg>/files/tts_audio/YYYYMMDD_HHmmss.wav
     */
    private fun saveAudioToLocal(wavBytes: ByteArray, text: String) {
        try {
            val ttsDir = java.io.File(appContext.filesDir, "tts_audio")
            if (!ttsDir.exists()) ttsDir.mkdirs()

            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.CHINA).format(java.util.Date())
            // 用文本前 20 字符作为文件名的一部分（避免文件名过长）
            val safeName = text.take(20).replace(Regex("[^\\w\\u4e00-\\u9fff]"), "_")
            val fileName = "${timestamp}_${safeName}.wav"
            val outFile = java.io.File(ttsDir, fileName)

            outFile.writeBytes(wavBytes)
            DebugLog.i("TtsPlayer: 音频已保存到 ${outFile.absolutePath} (${wavBytes.size / 1024}KB)")
        } catch (e: Exception) {
            DebugLog.e("TtsPlayer: 保存音频失败: ${e.message}", e)
        }
    }

    private fun playPcmAudio(wavBytes: ByteArray) {
        val parsed = parseWav(wavBytes)
        val pcmBytes = parsed?.pcmData ?: (tryStripWavHeader(wavBytes) ?: wavBytes)
        val sampleRate = parsed?.sampleRate ?: 24000
        val channelConfig = if (parsed?.channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize.coerceAtLeast(pcmBytes.size))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        try {
            audioTrack.play()
            audioTrack.write(pcmBytes, 0, pcmBytes.size)
            audioTrack.stop()
        } finally {
            audioTrack.release()
            isSpeaking.set(false)
            isPaused.set(false)
        }
    }

    private data class WavInfo(val pcmData: ByteArray, val sampleRate: Int, val channels: Int)

    private fun parseWav(wavBytes: ByteArray): WavInfo? {
        return try {
            if (wavBytes.size < 44) return null
            val stream = ByteArrayInputStream(wavBytes)
            val dis = DataInputStream(stream)

            val riff = ByteArray(4); dis.readFully(riff)
            if (String(riff) != "RIFF") return null
            dis.readFully(ByteArray(4))
            val wave = ByteArray(4); dis.readFully(wave)
            if (String(wave) != "WAVE") return null

            var sampleRate = 24000
            var channels = 1
            var bitsPerSample = 16
            var pcmData: ByteArray? = null

            while (dis.available() >= 8) {
                val chunkId = ByteArray(4); dis.readFully(chunkId)
                val chunkSize = Integer.reverseBytes(dis.readInt())
                val id = String(chunkId)
                if (id == "fmt " && chunkSize >= 16) {
                    val formatCode = dis.readUnsignedShort()
                    channels = dis.readUnsignedShort()
                    sampleRate = Integer.reverseBytes(dis.readInt())
                    dis.readInt()
                    dis.readUnsignedShort()
                    bitsPerSample = dis.readUnsignedShort()
                    if (chunkSize > 16) dis.skipBytes(chunkSize - 16)
                } else if (id == "data") {
                    pcmData = ByteArray(chunkSize)
                    dis.readFully(pcmData)
                } else {
                    if (chunkSize > 0 && chunkSize <= dis.available()) dis.skipBytes(chunkSize)
                }
            }
            if (pcmData != null) WavInfo(pcmData, sampleRate, channels) else null
        } catch (e: Exception) {
            DebugLog.w("parseWav error: ${e.message}")
            null
        }
    }

    private fun tryStripWavHeader(wavBytes: ByteArray): ByteArray? {
        return try {
            if (wavBytes.size < 44) return null
            val stream = ByteArrayInputStream(wavBytes)
            val dis = DataInputStream(stream)

            val riff = ByteArray(4)
            dis.readFully(riff)
            if (String(riff) != "RIFF") return null

            dis.readFully(ByteArray(4))

            val wave = ByteArray(4)
            dis.readFully(wave)
            if (String(wave) != "WAVE") return null

            while (dis.available() >= 8) {
                val chunkId = ByteArray(4)
                dis.readFully(chunkId)
                val chunkSize = Integer.reverseBytes(dis.readInt())
                if (String(chunkId) == "data") {
                    val dataOffset = wavBytes.size - dis.available()
                    val pcmData = ByteArray(chunkSize)
                    System.arraycopy(wavBytes, dataOffset, pcmData, 0, chunkSize)
                    return pcmData
                } else {
                    if (chunkSize > 0 && chunkSize <= dis.available()) {
                        dis.skipBytes(chunkSize)
                    }
                }
            }
            null
        } catch (e: Exception) {
            DebugLog.w("tryStripWavHeader error: ${e.message}")
            null
        }
    }

    fun pause() {
        stop()
    }

    fun resume() {
        if (lastText.isNotEmpty()) {
            speak(lastText, lastLocaleTag, lastRate, lastPitch, lastMimoVoice)
        }
    }

    fun stop() {
        currentPlayerJob?.cancel()
        playerCancelled.set(true)
        tts?.stop()
        isSpeaking.set(false)
        isPaused.set(false)
    }

    fun isCurrentlySpeaking(): Boolean = isSpeaking.get()
    fun isCurrentlyPaused(): Boolean = isPaused.get()

    fun shutdown() {
        stop()
        scope.cancel()
        tts?.shutdown()
        tts = null
    }
}
