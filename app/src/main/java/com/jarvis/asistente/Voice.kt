package com.jarvis.asistente

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Voz de Jarvis (texto a voz) con atenuación de la música mientras habla. */
class Speaker(context: Context, private val onReady: (() -> Unit)? = null) : TextToSpeech.OnInitListener {
    private val appCtx = context.applicationContext
    private val prefs = Prefs(appCtx)
    private val main = Handler(Looper.getMainLooper())
    private val callbacks = ConcurrentHashMap<String, () -> Unit>()
    private val am = appCtx.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs)
        .build()
    @Volatile
    private var ready = false
    private var appliedVoice: String? = null
    private val tts: TextToSpeech = TextToSpeech(appCtx, this)

    val isSpeaking: Boolean get() = ready && tts.isSpeaking

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val r = tts.setLanguage(ES)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale("es"))
            tts.setSpeechRate(1.05f)
            tts.setPitch(0.92f)
            tts.setAudioAttributes(attrs)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = finished(utteranceId, true)
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = finished(utteranceId, true)
                override fun onError(utteranceId: String?, errorCode: Int) = finished(utteranceId, true)
                override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId, false)
            })
            ready = true
        }
        main.post { onReady?.invoke() }
    }

    private fun finished(id: String?, fire: Boolean) {
        am.abandonAudioFocusRequest(focus)
        if (id == null) return
        val cb = callbacks.remove(id) ?: return
        if (fire) main.post { cb() }
    }

    private fun applyVoice() {
        val wanted = prefs.voiceName
        if (wanted == appliedVoice) return
        appliedVoice = wanted
        try {
            if (wanted.isBlank()) {
                tts.setLanguage(ES)
            } else {
                tts.voices?.firstOrNull { it.name == wanted }?.let { tts.setVoice(it) }
            }
        } catch (_: Exception) {
        }
    }

    fun speak(text: String, done: (() -> Unit)? = null) {
        if (!ready || text.isBlank()) {
            if (done != null) main.post { done() }
            return
        }
        applyVoice()
        val id = UUID.randomUUID().toString()
        if (done != null) callbacks[id] = done
        am.requestAudioFocus(focus)
        val clean = text.replace(Regex("[*#_`]"), "")
        if (tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) finished(id, true)
    }

    fun stop() {
        if (ready) tts.stop()
        am.abandonAudioFocusRequest(focus)
    }

    fun shutdown() {
        callbacks.clear()
        try {
            tts.stop(); tts.shutdown()
        } catch (_: Exception) {
        }
        am.abandonAudioFocusRequest(focus)
    }
}

/** Crea el reconocedor de voz, usando siempre el de Google (nunca el propio de Jarvis). */
object RecognizerFactory {
    fun findService(ctx: Context): ComponentName? {
        val list = ctx.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
        val others = list.filter { it.serviceInfo.packageName != ctx.packageName }
        val pick = others.firstOrNull { it.serviceInfo.packageName == "com.google.android.googlequicksearchbox" }
            ?: others.firstOrNull { it.serviceInfo.packageName == "com.google.android.tts" }
            ?: others.firstOrNull { it.serviceInfo.packageName.startsWith("com.google") }
            ?: others.firstOrNull()
        return pick?.let { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
    }

    fun create(ctx: Context): SpeechRecognizer? = try {
        val comp = findService(ctx)
        when {
            comp != null -> SpeechRecognizer.createSpeechRecognizer(ctx, comp)
            SpeechRecognizer.isRecognitionAvailable(ctx) -> SpeechRecognizer.createSpeechRecognizer(ctx)
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    fun intent(ctx: Context, partial: Boolean): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-ES")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partial)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        }
}
