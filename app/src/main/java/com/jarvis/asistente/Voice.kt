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
/** Voces masculinas de Google (los nombres de voz no indican el sexo). */
private val MALE_VOICES = listOf(
    "en-gb-x-rjs", "en-gb-x-gbd", "en-gb-x-gbb", "fr-fr-x-frd", "fr-fr-x-vlf", "de-de-x-deb", "de-de-x-deg",
    "it-it-x-itd", "it-it-x-itc", "pt-pt-x-pmj", "pt-br-x-ptd", "ja-jp-x-jad", "ko-kr-x-kod", "ru-ru-x-ruc",
    "zh-cn-x-ccd", "nl-nl-x-dma", "pl-pl-x-bmg", "tr-tr-x-tmc"
)

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
        set(v) { field = v; SpeakState.speaking = v }
    @Volatile
    private var pitch = 1.0f

    /** Se llama (en el hilo principal) con un motivo legible cuando la voz no se puede generar. */
    var onVoiceError: ((String) -> Unit)? = null

    val isSpeaking: Boolean get() = neuralBusy

    /** Voz del sistema, solo para leer traducciones en otros idiomas con acento nativo. */
    private var sysTts: TextToSpeech? = null
    @Volatile
    private var sysReady = false

    init {
        LocalVoice.prepareAsync(appCtx)
        NeuralVoice.init(appCtx)
        try {
            sysTts = TextToSpeech(appCtx) { st -> sysReady = st == TextToSpeech.SUCCESS }
        } catch (_: Exception) {
        }
        main.post { onReady?.invoke() }
    }

    /** Locale para un código de idioma; el inglés, británico (como el mayordomo de la película). */
    private fun localeFor(lang: String): Locale = when (lang) {
        "en" -> Locale.UK
        "pt" -> Locale("pt", "PT")
        "zh" -> Locale.SIMPLIFIED_CHINESE
        "fr" -> Locale.FRANCE
        "de" -> Locale.GERMANY
        "it" -> Locale.ITALY
        else -> Locale.forLanguageTag(lang)
    }

    /** Lee [text] con la voz del sistema en [lang]. Bloquea hasta terminar. False si no hay voz para ese idioma. */
    private fun speakSystem(gen: Int, text: String, lang: String): Boolean {
        val t = sysTts ?: return false
        var waited = 0
        while (!sysReady && waited < 2500) { Thread.sleep(50); waited += 50 }
        if (!sysReady) return false
        return try {
            var loc = localeFor(lang)
            var res = t.setLanguage(loc)
            if (res < 0 && loc.country.isNotEmpty()) { loc = Locale(loc.language); res = t.setLanguage(loc) }
            if (res < 0) return false
            // Preferir una voz masculina instalada en el móvil (sin internet si es posible).
            try {
                val voices = t.voices?.filter { it.locale.language == loc.language && !it.features.contains("notInstalled") }.orEmpty()
                val male = voices.firstOrNull { v ->
                    val n = v.name.lowercase()
                    n.contains("male") && !n.contains("female") || MALE_VOICES.any { n.contains(it) }
                }
                (male ?: voices.firstOrNull { !it.isNetworkConnectionRequired && it.locale.country == loc.country })?.let { t.voice = it }
            } catch (_: Exception) {
            }
            t.setAudioAttributes(attrs)
            t.setPitch(0.92f)
            t.setSpeechRate(1.0f)
            val id = UUID.randomUUID().toString()
            val latch = java.util.concurrent.CountDownLatch(1)
            var ok = true
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { if (utteranceId == id) latch.countDown() }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { if (utteranceId == id) { ok = false; latch.countDown() } }
                override fun onStop(utteranceId: String?, interrupted: Boolean) { if (utteranceId == id) latch.countDown() }
            })
            if (t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) return false
            var total = 0
            while (!latch.await(100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                total += 100
                if (gen != generation) { t.stop(); return true }
                if (total > 60000) { t.stop(); break }
            }
            ok
        } catch (_: Exception) {
            false
        }
    }

    /** Lee [text] en [lang]: el español con la voz de JANUS; el resto con voz nativa de ese idioma. */
    fun speakIn(text: String, lang: String, done: (() -> Unit)? = null) {
        val l = Languages.base(lang)
        if (l == "es" || l.isBlank()) { speak(text, done); return }
        prepareOutput()
        val clean = text.replace(Regex("[*#_`]"), "").trim()
        if (clean.isEmpty()) { if (done != null) main.post { done() }; return }
        val gen = ++generation
        val elevenKey = prefs.elevenKey.takeIf { it.isNotBlank() }
        neuralBusy = true
        am.requestAudioFocus(focus)
        Thread {
            HeadsetMic.waitForStereo()
            var played = false
            if (elevenKey != null) {
                // La voz de ElevenLabs es multilingüe: la misma voz de JANUS, hablando el otro idioma.
                pitch = 1.0f
                val player = PcmPlayer(gen, NeuralVoice.SAMPLE_RATE)
                ElevenVoice.stream(elevenKey, clean, l) { chunk -> if (gen != generation) false else { player.write(chunk); true } }
                if (player.bytes > 0) { finishOrReplay(player, gen); played = true } else player.release()
            }
            if (!played && gen == generation) played = speakSystem(gen, clean, l)
            if (gen != generation) return@Thread
            neuralBusy = false
            am.abandonAudioFocusRequest(focus)
            if (!played) {
                // Sin voz para ese idioma: se lee con la voz de JANUS para no quedarse callado.
                main.post {
                    onVoiceError?.invoke("No hay voz instalada para el ${Languages.name(l)}: instale «Servicios de voz de Google» y descargue ese idioma.")
                    speak(clean, done)
                }
                return@Thread
            }
            if (done != null) main.post { done() }
        }.start()
    }

    private fun neuralKey(): String? {
        val k = prefs.apiKey
        return if (k.isNotBlank() && !k.startsWith("sk-ant-")) k else null
    }

    private fun fail(reason: String, done: (() -> Unit)?) {
        Diag.log("Voz falló: $reason")
        main.post {
            onVoiceError?.invoke(reason)
            done?.invoke()
        }
    }

    /**
     * Que la voz se oiga siempre: suelta el micrófono de los auriculares (si no, la voz iría por
     * el canal de llamada), devuelve el sonido silenciado por el «bip» y, si el volumen multimedia
     * está a cero, lo sube un poco (JANUS habla por ese volumen).
     */
    private fun prepareOutput() {
        if (HeadsetMic.isActive) HeadsetMic.stop(appCtx)
        BeepMuter.unmuteNow(appCtx)
        try {
            val s = AudioManager.STREAM_MUSIC
            if (am.isStreamMute(s)) Diag.log("Voz: el sonido multimedia está silenciado")
            else if (am.getStreamVolume(s) == 0) {
                am.setStreamVolume(s, max(1, am.getStreamMaxVolume(s) / 3), 0)
                Diag.log("Voz: el volumen multimedia estaba a 0; lo subo para que se me oiga")
            }
            if (am.mode == AudioManager.MODE_IN_COMMUNICATION) Diag.log("Voz: el audio está en modo llamada")
        } catch (_: Exception) {
        }
    }

    /** Salida buena para la voz: auriculares (estéreo) o altavoz; nunca el auricular de llamada. */
    private fun bestOutput(): android.media.AudioDeviceInfo? {
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val order = mutableListOf(
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET, android.media.AudioDeviceInfo.TYPE_USB_HEADSET
        )
        if (android.os.Build.VERSION.SDK_INT >= 31) order.add(1, android.media.AudioDeviceInfo.TYPE_BLE_HEADSET)
        order.add(android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        for (t in order) outs.firstOrNull { it.type == t }?.let { return it }
        return null
    }

    /** Si la voz se atascó en una salida que no suena, se repite por el altavoz del móvil. */
    private fun finishOrReplay(p: PcmPlayer, gen: Int, rate: Int = p.rate) {
        p.finish()
        if (p.stalled && gen == generation) {
            Diag.log("Repito la voz por el altavoz del móvil")
            PcmPlayer(gen, rate, forceSpeaker = true).apply { write(p.all()); finish() }
        }
    }

    fun speak(text: String, done: (() -> Unit)? = null) {
        prepareOutput()
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
            HeadsetMic.waitForStereo()
            var played = false
            var reason: String? = null
            if (elevenKey != null) {
                pitch = 1.0f
                val voice = "eleven-" + ElevenVoice.VOICE_ID
                val cached = NeuralVoice.cached(voice, clean)
                if (cached != null) {
                    PcmPlayer(gen, NeuralVoice.SAMPLE_RATE).also { it.write(cached); finishOrReplay(it, gen, NeuralVoice.SAMPLE_RATE) }
                    played = true
                } else {
                    val player = PcmPlayer(gen, NeuralVoice.SAMPLE_RATE)
                    val complete = ElevenVoice.stream(elevenKey, clean) { chunk ->
                        if (gen != generation) false else { player.write(chunk); true }
                    }
                    if (player.bytes > 0) {
                        finishOrReplay(player, gen, NeuralVoice.SAMPLE_RATE); played = true
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
                    finishOrReplay(p, gen); played = true
                } else {
                    p?.release()
                    val detail = p?.openError
                        ?: LocalVoice.lastError
                        ?: if (gen != generation) "interrumpida" else "estado del motor: ${LocalVoice.state}"
                    reason = (reason?.let { "$it Y además, " } ?: "") + "la voz incluida falló: $detail."
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
    private inner class PcmPlayer(private val gen: Int, val rate: Int, private val forceSpeaker: Boolean = false) {
        private var track: AudioTrack? = null
        /** La salida no avanza (p. ej. auriculares Bluetooth guardados en su estuche): no se oye nada. */
        @Volatile
        var stalled = false
            private set
        private var lastHead = -1
        private var lastMove = 0L

        /** ¿Sigue sonando? Si la posición no se mueve en 2 s, la salida está atascada. */
        private fun moving(t: AudioTrack): Boolean {
            val h = try { t.playbackHeadPosition } catch (_: Exception) { return false }
            val now = android.os.SystemClock.uptimeMillis()
            if (h != lastHead || lastMove == 0L) { lastHead = h; lastMove = now }
            if (now - lastMove > 2000) {
                if (!stalled) {
                    stalled = true
                    val d = try { t.routedDevice?.type } catch (_: Exception) { null }
                    Diag.log("Voz atascada: la salida de audio (dispositivo $d) no reproduce")
                }
                return false
            }
            return true
        }
        private val all = ByteArrayOutputStream()
        private var carry: Byte? = null
        private val fx: AiVoiceFx? = if (prefs.aiEffect) AiVoiceFx(rate) else null
        var bytes = 0
            private set

        var openError: String? = null

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
                    val out = if (forceSpeaker) am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                        .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } else bestOutput()
                    out?.let { d -> try { t.preferredDevice = d } catch (_: Exception) {} }
                    if (pitch in 0.5f..1.5f && pitch != 1f) {
                        try { t.playbackParams = PlaybackParams().setPitch(pitch).setSpeed(1f) } catch (_: Exception) {}
                    }
                    t.play()
                    main.postDelayed({
                        val d = try { t.routedDevice } catch (_: Exception) { null }
                        Diag.log("Voz por: " + when (d?.type) {
                            android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "altavoz del móvil"
                            android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "auricular de llamada (se oye muy bajo)"
                            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "auriculares Bluetooth"
                            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "auriculares Bluetooth en modo llamada"
                            null -> "desconocido"
                            else -> "dispositivo ${d.type}"
                        } + ", volumen ${am.getStreamVolume(AudioManager.STREAM_MUSIC)}/${am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}")
                    }, 300)
                }
        } catch (e: Exception) {
            openError = "no se pudo abrir la salida de audio (${e.javaClass.simpleName}: ${e.message ?: ""}, frecuencia=$rate)"
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
            all.write(data, 0, data.size)
            if (stalled) return // se guarda para repetirlo por el altavoz
            val t = track ?: open()?.also { track = it } ?: return
            fx?.let { data = it.apply(data) }
            var off = 0
            // Sin bloquear: si la salida se atasca, no se queda colgado para siempre.
            while (off < data.size && gen == generation) {
                val n = t.write(data, off, data.size - off, AudioTrack.WRITE_NON_BLOCKING)
                if (n < 0) break
                if (n == 0) {
                    if (!moving(t)) break
                    Thread.sleep(10)
                } else {
                    off += n
                }
            }
            bytes += data.size
        }

        fun all(): ByteArray = all.toByteArray()

        fun finish() {
            val t = track ?: return
            try {
                val frames = bytes / 2
                var waited = 0
                while (gen == generation && t.playbackHeadPosition < frames && waited < 60000 && moving(t)) {
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
        try { sysTts?.stop() } catch (_: Exception) {}
        am.abandonAudioFocusRequest(focus)
    }

    fun shutdown() {
        stop()
        try { sysTts?.shutdown() } catch (_: Exception) {}
        sysTts = null
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
    fun stream(key: String, text: String, lang: String = "es", onChunk: (ByteArray) -> Boolean): Boolean {
        val body = JSONObject()
            .put("text", text)
            .put("model_id", MODEL)
            .put("language_code", lang)
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
