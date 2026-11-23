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
 * Voz de JANUS. Es la ÚNICA voz: la neuronal de Gemini, en streaming y con caché.
 * No hay voz de respaldo; si no se puede generar, se avisa del motivo con [onVoiceError].
 */
class Speaker(context: Context, onReady: (() -> Unit)? = null) {
    private val appCtx = context.applicationContext
    private val prefs = Prefs(appCtx)
    private val main = Handler(Looper.getMainLooper())
    private val am = appCtx.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs)
        .build()
    @Volatile
    private var generation = 0
    @Volatile
    private var neuralBusy = false
    @Volatile
    private var pitch = 1.0f

    /** Se llama (en el hilo principal) con un motivo legible cuando la voz no se puede generar. */
    var onVoiceError: ((String) -> Unit)? = null

    val isSpeaking: Boolean get() = neuralBusy

    init {
        LocalVoice.prepareAsync(appCtx)
        NeuralVoice.init(appCtx)
        main.post { onReady?.invoke() }
    }

    private fun neuralKey(): String? {
        val k = prefs.apiKey
        return if (k.isNotBlank() && !k.startsWith("sk-ant-")) k else null
    }

    private fun fail(reason: String, done: (() -> Unit)?) {
        main.post {
            onVoiceError?.invoke(reason)
            done?.invoke()
        }
    }

    fun speak(text: String, done: (() -> Unit)? = null) {
        BeepMuter.unmuteNow(appCtx)
        val clean = text.replace(Regex("[*#_`]"), "")
            .replace(Regex("J\\.?A\\.?N\\.?U\\.?S\\.?|\\bjanus\\b", RegexOption.IGNORE_CASE), "Yanus")
            .trim()
        if (clean.isEmpty()) {
            if (done != null) main.post { done() }
            return
        }
        val gen = ++generation
        val elevenKey = prefs.elevenKey.takeIf { it.isNotBlank() }
        neuralBusy = true
        am.requestAudioFocus(focus)
        Thread {
            var played = false
            var reason: String? = null
            if (elevenKey != null) {
                pitch = 1.0f
                val voice = "eleven-" + ElevenVoice.VOICE_ID
                val cached = NeuralVoice.cached(voice, clean)
                if (cached != null) {
                    PcmPlayer(gen, NeuralVoice.SAMPLE_RATE).apply { write(cached); finish() }
                    played = true
                } else {
                    val player = PcmPlayer(gen, NeuralVoice.SAMPLE_RATE)
                    val complete = ElevenVoice.stream(elevenKey, clean) { chunk ->
                        if (gen != generation) false else { player.write(chunk); true }
                    }
                    if (player.bytes > 0) {
                        player.finish(); played = true
                        if (complete && gen == generation) NeuralVoice.store(voice, clean, player.all())
                    } else {
                        player.release()
                        reason = ElevenVoice.friendlyError()
                    }
                }
            }
            if (!played && gen == generation) {
                // Voz de JANUS incluida en la app: gratis, sin internet.
                pitch = prefs.voicePitch
                var player: PcmPlayer? = null
                for (sentence in LocalVoice.sentences(clean)) {
                    if (gen != generation) break
                    val r = LocalVoice.synth(appCtx, sentence) ?: break
                    val p = player ?: PcmPlayer(gen, r.second).also { player = it }
                    p.write(r.first)
                }
                val p = player
                if (p != null && p.bytes > 0) {
                    p.finish(); played = true
                } else {
                    p?.release()
                    if (reason == null) reason = "la voz incluida no se pudo cargar (${LocalVoice.lastError ?: "desconocido"})."
                }
            }
            if (gen != generation) return@Thread
            neuralBusy = false
            am.abandonAudioFocusRequest(focus)
            if (!played) {
                fail(reason ?: "no he podido hablar.", done)
                return@Thread
            }
            if (done != null) main.post { done() }
        }.start()
    }

    /** Reproduce PCM 16 bits mono 24 kHz a medida que llega. Se corta si cambia [generation]. */
    private inner class PcmPlayer(private val gen: Int, private val rate: Int) {
        private var track: AudioTrack? = null
        private val all = ByteArrayOutputStream()
        private var carry: Byte? = null
        private val fx: AiVoiceFx? = if (prefs.aiEffect) AiVoiceFx(rate) else null
        var bytes = 0
            private set

        private fun open(): AudioTrack? = try {
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
                // Búfer de ~2 s: mientras suena una frase se genera la siguiente, sin cortes.
                .setBufferSizeInBytes(max(minBuf, rate * 4))
                .build()
                .also { t ->
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
        am.abandonAudioFocusRequest(focus)
    }

    fun shutdown() {
        stop()
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
        "Iapetus" to "Iapetus — nítida y refinada (la voz de JANUS)",
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
        "Eres JANUS, una inteligencia artificial con aire de mayordomo digital de película. Voz masculina de tono medio, nítida, refinada y extremadamente serena, con aire de mayordomo inglés culto. Habla con precisión casi digital, con fluidez y buen ritmo, cortés y con un humor seco muy sutil. Nunca exagera las emociones. Español de España."
    private val MODELS = listOf(
        "gemini-3.8-flash-tts", "gemini-3.8-flash-lite-tts", "gemini-3.1-flash-tts-preview",
        "gemini-2.5-flash-preview-tts", "gemini-2.5-flash-tts"
    )

    /** Modelo + forma de pedirlo (en streaming o no, con estilo estructurado o en el texto). */
    private data class Combo(val model: String, val streaming: Boolean, val structured: Boolean)

    @Volatile
    private var working: Combo? = null
    @Volatile
    var lastError: String? = null
        private set
    @Volatile
    private var lastCode = 0
    private val memory = object : LinkedHashMap<String, ByteArray>(48, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean = size > 40
    }
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

    fun friendlyError(): String {
        val e = lastError ?: "no se recibió audio"
        return when {
            lastCode == 429 -> "se ha alcanzado el límite de uso gratuito de la clave de Gemini. Espere un rato o revise su cuota en aistudio.google.com."
            lastCode == 401 || lastCode == 403 || e.contains("API_KEY", true) -> "la clave de Gemini no es válida. Revísela en ⚙."
            e.contains("UnknownHost", true) || e.contains("timeout", true) -> "no hay conexión a internet."
            else -> "no he podido generar la voz ($e)."
        }
    }

    /**
     * Pide el audio y llama a [onChunk] con cada trozo de PCM según llega.
     * Prueba varios modelos y formas hasta dar con una que funcione con la clave, y la recuerda.
     * Devuelve true si el audio llegó completo. [onChunk] devuelve false para cancelar.
     */
    fun stream(key: String, voice: String, text: String, onChunk: (ByteArray) -> Boolean): Boolean {
        val known = working
        val combos = if (known != null) listOf(known) else MODELS.flatMap { m ->
            listOf(Combo(m, true, true), Combo(m, false, true), Combo(m, true, false), Combo(m, false, false))
        }
        for (c in combos) {
            var delivered = false
            val wrapped: (ByteArray) -> Boolean = { delivered = true; onChunk(it) }
            try {
                if (request(key, c, voice, text, wrapped)) {
                    working = c; lastError = null; lastCode = 0
                    return true
                }
                lastError = "${c.model}: respuesta sin audio"
            } catch (e: LlmClient.HttpError) {
                lastCode = e.code
                lastError = "${c.model}: error ${e.code} ${e.message?.take(160) ?: ""}"
                if (e.code == 429 || e.code == 401 || e.code == 403 || e.message?.contains("API_KEY") == true) return false
            } catch (e: Exception) {
                lastCode = 0
                lastError = "${c.model}: ${e.javaClass.simpleName} ${e.message ?: ""}"
                if (e is java.net.UnknownHostException) return false
            }
            if (delivered) return false
        }
        // Si la combinación que funcionaba deja de hacerlo, la próxima vez se vuelven a probar todas.
        if (known != null) working = null
        return false
    }

    private fun request(
        key: String, c: Combo, voice: String, text: String,
        onChunk: (ByteArray) -> Boolean
    ): Boolean {
        val model = c.model
        val part = if (c.structured) {
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
        val url = if (c.streaming) "https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse"
        else "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 25000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("x-goog-api-key", key)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                throw LlmClient.HttpError(code, err.take(300))
            }
            if (!c.streaming) {
                val res = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val parts = JSONObject(res).optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts") ?: return false
                var got = false
                for (i in 0 until parts.length()) {
                    val p = parts.getJSONObject(i)
                    val inline = p.optJSONObject("inlineData") ?: p.optJSONObject("inline_data") ?: continue
                    val data = inline.optString("data", "")
                    if (data.isEmpty()) continue
                    got = true
                    if (!onChunk(Base64.decode(data, Base64.DEFAULT))) return true
                }
                return got
            }
            var got = false
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
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
            conn.disconnect()
        }
    }
}

/**
 * Voz de ElevenLabs: «Daniel», masculina, británica y con autoridad; la más parecida al
 * asistente de la película. Se recibe en PCM 24 kHz, igual que la de Gemini.
 */
object ElevenVoice {
    const val VOICE_ID = "onwK4e9ZLuTAKqWW03F9" // Daniel
    private const val MODEL = "eleven_flash_v2_5"
    @Volatile
    var lastError: String? = null
        private set
    @Volatile
    private var lastCode = 0

    fun friendlyError(): String {
        val e = lastError ?: "no se recibió audio"
        return when {
            lastCode == 401 -> "la clave de ElevenLabs no es válida. Revísela en ⚙."
            lastCode == 402 || e.contains("quota", true) -> "se han agotado los caracteres gratuitos de ElevenLabs este mes."
            lastCode == 429 -> "ElevenLabs está saturado o ha alcanzado el límite. Inténtelo en un momento."
            e.contains("UnknownHost", true) || e.contains("timeout", true) -> "no hay conexión a internet."
            else -> "no he podido generar la voz ($e)."
        }
    }

    /** Pide el audio en streaming; [onChunk] recibe PCM según llega. True si llegó completo. */
    fun stream(key: String, text: String, onChunk: (ByteArray) -> Boolean): Boolean {
        val body = JSONObject()
            .put("text", text)
            .put("model_id", MODEL)
            .put("language_code", "es")
            .put(
                "voice_settings", JSONObject()
                    .put("stability", 0.65)
                    .put("similarity_boost", 0.8)
                    .put("style", 0.15)
                    .put("use_speaker_boost", true)
            )
        val conn = URL("https://api.elevenlabs.io/v1/text-to-speech/$VOICE_ID/stream?output_format=pcm_24000")
            .openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 20000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "audio/pcm")
            conn.setRequestProperty("xi-api-key", key)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                lastCode = code
                lastError = "error $code " + (conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }?.take(160) ?: "")
                return false
            }
            var got = false
            val buf = ByteArray(4096)
            conn.inputStream.use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    got = true
                    if (!onChunk(buf.copyOf(n))) return true
                }
            }
            if (got) { lastError = null; lastCode = 0 } else lastError = "respuesta sin audio"
            return got
        } catch (e: Exception) {
            lastCode = 0
            lastError = "${e.javaClass.simpleName} ${e.message ?: ""}"
            return false
        } finally {
            conn.disconnect()
        }
    }
}

/** Crea el reconocedor de voz, usando siempre el de Google (nunca el propio de Janus). */
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
