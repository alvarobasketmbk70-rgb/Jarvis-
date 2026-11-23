package com.jarvis.asistente

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.PlaybackParams
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.sin

/**
 * Voz de Jarvis. Usa la voz neuronal de Gemini en streaming (empieza a sonar en cuanto llega
 * el primer trozo de audio) con caché en disco; si falla, cae a la voz del sistema.
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

    init {
        NeuralVoice.init(appCtx)
        neuralKey()?.let { NeuralVoice.prewarm(it, prefs.neuralVoiceName, commonPhrases()) }
    }

    private fun commonPhrases(): List<String> {
        val n = prefs.userName
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val saludo = when (h) {
            in 6..12 -> "Buenos días"
            in 13..20 -> "Buenas tardes"
            else -> "Buenas noches"
        }
        return listOf(
            "¿Sí, $n?", "$saludo, $n. ¿En qué puedo ayudarle?", "Música en pausa.", "Reanudando la música.",
            "Siguiente canción.", "Canción anterior.", "De acuerdo.", "Es un placer.",
            "Linterna encendida.", "Linterna apagada.", "Volumen subido.", "Volumen bajado."
        )
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val r = tts.setLanguage(ES)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale("es"))
            tts.setSpeechRate(1.08f)
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
        if (key == null) {
            systemSpeak(clean, done)
            return
        }
        if (ready) tts.stop()
        neuralBusy = true
        am.requestAudioFocus(focus)
        val voice = prefs.neuralVoiceName
        Thread {
            var played = false
            val cached = NeuralVoice.cached(voice, clean)
            if (cached != null) {
                PcmPlayer(gen).apply { write(cached); finish() }
                played = true
            } else {
                val player = PcmPlayer(gen)
                val complete = NeuralVoice.stream(key, voice, clean) { chunk ->
                    if (gen != generation) false else { player.write(chunk); true }
                }
                if (player.bytes > 0) {
                    player.finish()
                    played = true
                    if (complete && gen == generation) NeuralVoice.store(voice, clean, player.all())
                } else {
                    player.release()
                }
            }
            if (gen != generation) return@Thread
            neuralBusy = false
            if (!played) {
                main.post { if (gen == generation) systemSpeak(clean, done) }
                return@Thread
            }
            am.abandonAudioFocusRequest(focus)
            if (done != null) main.post { done() }
        }.start()
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

    /** Reproduce PCM 16 bits mono 24 kHz a medida que llega. Se corta si cambia [generation]. */
    private inner class PcmPlayer(private val gen: Int) {
        private var track: AudioTrack? = null
        private val all = ByteArrayOutputStream()
        private var carry: Byte? = null
        private val fx: AiVoiceFx? = if (prefs.aiEffect) AiVoiceFx(NeuralVoice.SAMPLE_RATE) else null
        var bytes = 0
            private set

        private fun open(): AudioTrack? = try {
            val rate = NeuralVoice.SAMPLE_RATE
            val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
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
                .setBufferSizeInBytes(max(minBuf, 8192))
                .build()
                .also { t ->
                    val pitch = prefs.voicePitch
                    if (pitch in 0.5f..1.5f && pitch != 1f) {
                        try { t.playbackParams = PlaybackParams().setPitch(pitch).setSpeed(1f) } catch (_: Exception) {}
                    }
                    t.play()
                }
        } catch (e: Exception) {
            null
        }

        fun write(input: ByteArray) {
            if (gen != generation || input.isEmpty()) return
            var data = input
            carry?.let { c -> data = byteArrayOf(c) + input; carry = null }
            if (data.size % 2 == 1) {
                carry = data[data.size - 1]
                data = data.copyOf(data.size - 1)
            }
            if (data.isEmpty()) return
            val t = track ?: open()?.also { track = it } ?: return
            all.write(data, 0, data.size)
            fx?.let { data = it.apply(data) }
            var off = 0
            while (off < data.size && gen == generation) {
                val n = t.write(data, off, data.size - off)
                if (n <= 0) break
                off += n
            }
            bytes += data.size
        }

        fun all(): ByteArray = all.toByteArray()

        fun finish() {
            val t = track ?: return
            try {
                val frames = bytes / 2
                var waited = 0
                while (gen == generation && t.playbackHeadPosition < frames && waited < 60000) {
                    Thread.sleep(15); waited += 15
                }
                if (gen != generation) {
                    t.pause(); t.flush()
                } else {
                    t.stop()
                }
            } catch (_: Exception) {
            } finally {
                release()
            }
        }

        fun release() {
            try { track?.release() } catch (_: Exception) {}
            track = null
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

/**
 * Efecto «voz de IA»: un coro muy corto y un eco metálico mínimo que dan el timbre digital
 * característico del asistente de la película, sin que deje de sonar natural.
 */
class AiVoiceFx(private val rate: Int) {
    private val line = FloatArray((rate * 0.05).toInt() + 4)
    private var w = 0
    private var n = 0L
    private val comb = FloatArray((rate * 0.027).toInt())
    private var ci = 0
    private var lp = 0f

    private fun sample(x: Float): Float {
        line[w] = x
        val delay = rate * (0.012f + 0.0025f * sin(2.0 * Math.PI * 0.35 * n / rate).toFloat())
        var rp = w - delay
        while (rp < 0) rp += line.size
        val i0 = rp.toInt() % line.size
        val i1 = (i0 + 1) % line.size
        val frac = rp - rp.toInt()
        val chorus = line[i0] * (1 - frac) + line[i1] * frac
        val c = comb[ci]
        comb[ci] = x + c * 0.28f
        ci = (ci + 1) % comb.size
        w = (w + 1) % line.size
        n++
        lp += (c - lp) * 0.35f // suaviza el eco para que no chirríe
        return (x * 0.82f + chorus * 0.30f + lp * 0.16f).coerceIn(-1f, 1f)
    }

    fun apply(pcm: ByteArray): ByteArray {
        val out = ByteArray(pcm.size)
        var i = 0
        while (i + 1 < pcm.size) {
            val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort()
            val y = (sample(s / 32768f) * 32767f).toInt()
            out[i] = (y and 0xFF).toByte()
            out[i + 1] = ((y shr 8) and 0xFF).toByte()
            i += 2
        }
        return out
    }
}

/** Voz neuronal de Google Gemini (texto a voz) en streaming, con caché en memoria y en disco. */
object NeuralVoice {
    const val SAMPLE_RATE = 24000
    val VOICES = listOf(
        "Iapetus" to "Iapetus — nítida y refinada (la más parecida al Jarvis de la película)",
        "Charon" to "Charon — grave y serena",
        "Orus" to "Orus — firme y segura",
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
        "Eres J.A.R.V.I.S., una inteligencia artificial mayordomo de película de superhéroes. Voz masculina de tono medio, nítida, refinada y extremadamente serena, con aire de mayordomo inglés culto. Habla con precisión casi digital, con fluidez y buen ritmo, cortés y con un humor seco muy sutil. Nunca exagera las emociones. Español de España."
    private val MODELS = listOf("gemini-3.8-flash-tts", "gemini-3.1-flash-tts-preview")
    private val memory = object : LinkedHashMap<String, ByteArray>(48, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean = size > 40
    }
    @Volatile
    private var workingModel: String? = null
    @Volatile
    private var structuredOk = true
    private var dir: File? = null
    @Volatile
    private var prewarming = false

    fun init(ctx: Context) {
        if (dir == null) dir = File(ctx.cacheDir, "voz").apply { mkdirs() }
    }

    private fun id(voice: String, text: String): String {
        val md = MessageDigest.getInstance("SHA-1").digest("$voice|$STYLE|$text".toByteArray(Charsets.UTF_8))
        return md.joinToString("") { "%02x".format(it) }
    }

    fun cached(voice: String, text: String): ByteArray? {
        val k = id(voice, text)
        synchronized(memory) { memory[k]?.let { return it } }
        val f = dir?.let { File(it, "$k.pcm") } ?: return null
        if (!f.exists()) return null
        return try {
            f.readBytes().also { b -> synchronized(memory) { memory[k] = b } }
        } catch (e: Exception) {
            null
        }
    }

    fun store(voice: String, text: String, pcm: ByteArray) {
        if (pcm.isEmpty()) return
        val k = id(voice, text)
        synchronized(memory) { memory[k] = pcm }
        // En disco solo frases cortas (confirmaciones, saludos), que son las que se repiten.
        if (text.length <= 90) {
            try { dir?.let { File(it, "$k.pcm").writeBytes(pcm) } } catch (_: Exception) {}
        }
    }

    /** Genera en segundo plano las frases más habituales para que suenen al instante. */
    fun prewarm(key: String, voice: String, phrases: List<String>) {
        if (prewarming) return
        prewarming = true
        Thread {
            try {
                for (p in phrases) {
                    if (cached(voice, p) != null) continue
                    val out = ByteArrayOutputStream()
                    val ok = stream(key, voice, p) { out.write(it, 0, it.size); true }
                    if (!ok || out.size() == 0) break // sin red o límite de uso: lo dejamos para otro momento
                    store(voice, p, out.toByteArray())
                    Thread.sleep(1500)
                }
            } catch (_: Exception) {
            } finally {
                prewarming = false
            }
        }.start()
    }

    /**
     * Pide el audio en streaming y llama a [onChunk] con cada trozo de PCM según llega.
     * Devuelve true si el audio llegó completo. [onChunk] devuelve false para cancelar.
     */
    fun stream(key: String, voice: String, text: String, onChunk: (ByteArray) -> Boolean): Boolean {
        val models = workingModel?.let { listOf(it) } ?: MODELS
        for (m in models) {
            var delivered = false
            val wrapped: (ByteArray) -> Boolean = { delivered = true; onChunk(it) }
            try {
                if (request(key, m, voice, text, structuredOk, wrapped)) {
                    workingModel = m; return true
                }
            } catch (e: LlmClient.HttpError) {
                if (e.code == 400 && structuredOk && !delivered) {
                    try {
                        if (request(key, m, voice, text, false, wrapped)) {
                            structuredOk = false; workingModel = m; return true
                        }
                    } catch (_: Exception) {
                    }
                }
                if (e.code == 429) return false
            } catch (e: Exception) {
                return false
            }
            if (delivered) return false
        }
        return false
    }

    private fun request(
        key: String, model: String, voice: String, text: String, structured: Boolean,
        onChunk: (ByteArray) -> Boolean
    ): Boolean {
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
        val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse")
            .openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 8000
            c.readTimeout = 20000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.setRequestProperty("x-goog-api-key", key)
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code !in 200..299) {
                val err = c.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                throw LlmClient.HttpError(code, err.take(300))
            }
            var got = false
            c.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val json = line.substring(5).trim()
                    if (json.isEmpty() || json == "[DONE]") continue
                    val cands = JSONObject(json).optJSONArray("candidates") ?: continue
                    val parts = cands.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: continue
                    for (i in 0 until parts.length()) {
                        val p = parts.getJSONObject(i)
                        val inline = p.optJSONObject("inlineData") ?: p.optJSONObject("inline_data") ?: continue
                        val data = inline.optString("data", "")
                        if (data.isEmpty()) continue
                        got = true
                        if (!onChunk(Base64.decode(data, Base64.DEFAULT))) return true
                    }
                }
            }
            return got
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
            // Cierra la escucha antes tras el último silencio: respuesta más rápida.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 500L)
        }
}
