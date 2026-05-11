package top.hsyscn.opedrgent.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

class TtsPlayer(context: Context) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready: Boolean = false
    private var pendingLocale: Locale = Locale.CHINA
    private var isSpeaking: Boolean = false
    private var isPaused: Boolean = false
    private var lastText: String = ""
    private var lastLocaleTag: String = "zh-CN"
    private var lastRate: Float = 1.0f
    private var lastPitch: Float = 1.0f

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
    ) {
        val t = text.trim()
        if (t.isEmpty()) return
        val engine = tts ?: return
        val locale = runCatching { Locale.forLanguageTag(localeTag) }.getOrNull() ?: Locale.CHINA
        pendingLocale = locale
        if (ready) {
            engine.language = locale
        }
        engine.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
        engine.setPitch(pitch.coerceIn(0.5f, 2.0f))

        lastText = t
        lastLocaleTag = localeTag
        lastRate = rate
        lastPitch = pitch

        val utteranceId = UUID.randomUUID().toString()
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(uttId: String?) {}
            override fun onDone(uttId: String?) {
                if (uttId == utteranceId) {
                    isSpeaking = false
                    isPaused = false
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(uttId: String?) {
                if (uttId == utteranceId) {
                    isSpeaking = false
                    isPaused = false
                }
            }
        })
        engine.speak(t, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        isSpeaking = true
        isPaused = false
    }

    fun pause() {
        if (isSpeaking && !isPaused) {
            tts?.stop()
            isPaused = true
        }
    }

    fun resume() {
        if (isPaused && lastText.isNotEmpty()) {
            val engine = tts ?: return
            val locale = runCatching { Locale.forLanguageTag(lastLocaleTag) }.getOrNull() ?: Locale.CHINA
            pendingLocale = locale
            if (ready) engine.language = locale
            engine.setSpeechRate(lastRate.coerceIn(0.5f, 2.0f))
            engine.setPitch(lastPitch.coerceIn(0.5f, 2.0f))

            val utteranceId = UUID.randomUUID().toString()
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(uttId: String?) {}
                override fun onDone(uttId: String?) {
                    if (uttId == utteranceId) {
                        isSpeaking = false
                        isPaused = false
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(uttId: String?) {
                    if (uttId == utteranceId) {
                        isSpeaking = false
                        isPaused = false
                    }
                }
            })
            engine.speak(lastText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            isSpeaking = true
            isPaused = false
        }
    }

    fun stop() {
        tts?.stop()
        isSpeaking = false
        isPaused = false
    }

    fun isCurrentlySpeaking(): Boolean = isSpeaking
    fun isCurrentlyPaused(): Boolean = isPaused

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isSpeaking = false
        isPaused = false
    }
}