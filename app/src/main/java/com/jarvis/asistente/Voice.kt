package com.jarvis.asistente

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * Voz de Jarvis. Usa la voz neuronal de Gemini si hay clave de Gemini y está activada;
 * si falla (sin internet, sin clave, límite de uso) cae a la voz del sistema.
 */
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
    @Volatile
    private var generation = 0
    @Volatile
    private var neuralBusy = false
    private var appliedVoice: String? = null
    private val tts: TextToSpeech = TextToSpeech(appCtx, this)

    val isSpeaking: Boolean get() = neuralBusy || (ready && tts.isSpeaking)

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

    private fun neuralKey(): String? {
        val k = prefs.apiKey
        return if (prefs.neuralVoice && k.isNotBlank() && !k.startsWith("sk-ant-")) k else null
    }

    fun speak(text: String, done: (() -> Unit)? = null) {
        val clean = text.replace(Regex("[*#_`]"), "").trim()
        if (clean.isEmpty()) {
            if (done != null) main.post { done() }
            return
        }
        val gen = ++generation
        val key = neuralKey()
        if (key != null) {
            if (ready) tts.stop()
            neuralBusy = true
            am.requestAudioFocus(focus)
            val voice = prefs.neuralVoiceName
            Thread {
                val pcm = NeuralVoice.synth(key, voice, clean)
                if (gen != generation) return@Thread
                if (pcm == null) {
                    neuralBusy = false
                    main.post { if (gen == generation) systemSpeak(clean, done) }
                    return@Thread
                }
                play(pcm, gen)
                if (gen == generation) {
                    neuralBusy = false
                    am.abandonAudioFocusRequest(focus)
                    if (done != null) main.post { done() }
                }
            }.start()
            return
        }
        systemSpeak(clean, done)
    }

    private fun systemSpeak(text: String, done: (() -> Unit)?) {
        if (!ready) {
            if (done != null) main.post { done() }
            return
        }
        applyVoice()
        val id = UUID.randomUUID().toString()
        if (done != null) callbacks[id] = done
        am.requestAudioFocus(focus)
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) finished(id, true)
    }

    /** Reproduce PCM 16 bits mono 24 kHz. Se interrumpe si cambia [generation]. */
    private fun play(pcm: ByteArray, gen: Int) {
        val rate = NeuralVoice.SAMPLE_RATE
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(max(minBuf, 16384))
                .build()
        } catch (e: Exception) {
            return
        }
        try {
            track.play()
            var off = 0
            while (off < pcm.size && gen == generation) {
                val n = track.write(pcm, off, min(8192, pcm.size - off))
                if (n <= 0) break
                off += n
            }
            val frames = pcm.size / 2
            var waited = 0
            while (gen == generation && track.playbackHeadPosition < frames && waited < 30000) {
                Thread.sleep(20); waited += 20
            }
            if (gen != generation) {
                track.pause(); track.flush()
            } else {
                track.stop()
            }
        } catch (_: Exception) {
        } finally {
            try { track.release() } catch (_: Exception) {}
        }
    }

    fun stop() {
        generation++
        neuralBusy = false
        if (ready) tts.stop()
        am.abandonAudioFocusRequest(focus)
    }

    fun shutdown() {
        generation++
        neuralBusy = false
        callbacks.clear()
        try {
            tts.stop(); tts.shutdown()
        } catch (_: Exception) {
        }
        am.abandonAudioFocusRequest(focus)
    }
}

/** Voz neuronal de Google Gemini (texto a voz), con caché de frases repetidas. */
object NeuralVoice {
    const val SAMPLE_RATE = 24000
    val VOICES = listOf(
        "Charon" to "Charon — grave y serena (recomendada)",
        "Orus" to "Orus — firme y segura",
        "Iapetus" to "Iapetus — clara y elegante",
        "Algenib" to "Algenib — rasgada, madura",
        "Alnilam" to "Alnilam — firme, de locutor",
        "Rasalgethi" to "Rasalgethi — culta e informativa",
        "Schedar" to "Schedar — equilibrada",
        "Fenrir" to "Fenrir — enérgica",
        "Puck" to "Puck — alegre",
        "Kore" to "Kore — femenina, firme",
        "Aoede" to "Aoede — femenina, suave"
    )
    private const val STYLE =
        "Habla como J.A.R.V.I.S., el mayordomo digital de Iron Man: voz masculina grave, serena y elegante, " +
            "con un sutil toque de ironía británica. Ritmo ágil, dicción impecable, español de España, cercano pero formal."
    private val MODELS = listOf("gemini-3.8-flash-tts", "gemini-3.1-flash-tts-preview")
    private val cache = object : LinkedHashMap<String, ByteArray>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean = size > 30
    }
    @Volatile
    private var workingModel: String? = null

    fun synth(key: String, voice: String, text: String): ByteArray? {
        val ck = "$voice|$text"
        synchronized(cache) { cache[ck]?.let { return it } }
        val models = workingModel?.let { listOf(it) } ?: MODELS
        for (m in models) {
            val pcm = try {
                request(key, m, voice, text, structured = true)
            } catch (e: LlmClient.HttpError) {
                if (e.code == 400) {
                    try { request(key, m, voice, text, structured = false) } catch (_: Exception) { null }
                } else null
            } catch (e: Exception) {
                null
            }
            if (pcm != null && pcm.isNotEmpty()) {
                workingModel = m
                synchronized(cache) { cache[ck] = pcm }
                return pcm
            }
        }
        return null
    }

    private fun request(key: String, model: String, voice: String, text: String, structured: Boolean): ByteArray? {
        val part = if (structured) {
            JSONObject().put("text", text).put("speech_metadata", JSONObject().put("style", STYLE))
        } else {
            JSONObject().put("text", "$STYLE\nLee exactamente este texto: $text")
        }
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(part))))
            .put(
                "generationConfig", JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put(
                        "speechConfig", JSONObject()
                            .put("languageCode", "es-ES")
                            .put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice)))
                    )
            )
        val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10000
            c.readTimeout = 30000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.setRequestProperty("x-goog-api-key", key)
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val res = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw LlmClient.HttpError(code, res.take(300))
            val parts = JSONObject(res).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                val inline = p.optJSONObject("inlineData") ?: p.optJSONObject("inline_data") ?: continue
                val data = inline.optString("data", "")
                if (data.isNotEmpty()) return Base64.decode(data, Base64.DEFAULT)
            }
            return null
        } finally {
            c.disconnect()
        }
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
