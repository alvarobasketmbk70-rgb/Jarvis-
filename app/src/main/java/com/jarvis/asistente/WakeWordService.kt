package com.jarvis.asistente

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * JANUS siempre disponible, como Siri:
 *  - Un único micrófono continuo (con cancelación de eco y supresión de ruido) que nunca se corta.
 *  - Un detector local (Vosk, sin internet) espera «Oye Janus», también con la pantalla apagada.
 *  - Al oírlo graba la orden hasta que usted deja de hablar y envía el audio a Gemini, que lo
 *    entiende y responde a la vez (como un chat de voz). Las órdenes simples son instantáneas.
 *  - Con el móvil bloqueado enciende la pantalla y aparece sobre el bloqueo; pide desbloquear
 *    solo para lo que lo necesita (abrir apps, enviar mensajes…).
 */
class WakeWordService : Service() {

    companion object {
        private const val CHANNEL = "jarvis_wake"
        private const val NOTIF_ID = 7
        private const val ACTION_STOP = "com.jarvis.asistente.STOP_WAKE"
        const val ACTION_SUMMON = "com.jarvis.asistente.SUMMON"
        private const val MODEL_ASSET = "model-es"
        private const val MODEL_VERSION = "vosk-model-small-es-0.42"
        private const val RATE = 16000

        /** Formas inequívocas del nombre, y formas dudosas que solo valen tras «oye»/«hola». */
        val WAKE = Regex(
            "\\b(janus|yanus|llanus|ianus|yannus|janos)\\b|" +
                "\\b(oye|hola|hey|eh)\\s+(llanos|llano|yanos|yano|ianos|jano|hanos|anos|ya nos|lanus|jonás|jonas|yanes|llanes)\\b",
            RegexOption.IGNORE_CASE
        )

        /** Tareas que necesitan el móvil desbloqueado. */
        private val NEEDS_UNLOCK = setOf(
            "open_app", "whatsapp", "sms", "spotify_play", "youtube", "web_search", "navigate", "maps_search",
            "agent", "click", "scroll", "global", "type_text", "read_screen", "jarvis_settings", "wifi", "bluetooth",
            "airplane", "alarms_open"
        )

        fun summon(ctx: Context): Boolean {
            if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
            if (!android.provider.Settings.canDrawOverlays(ctx)) return false
            instance?.let { it.summonNow(); return true }
            return try {
                ctx.startForegroundService(Intent(ctx, WakeWordService::class.java).setAction(ACTION_SUMMON))
                true
            } catch (_: Exception) {
                false
            }
        }

        @Volatile
        var instance: WakeWordService? = null
            private set

        /** true mientras la pantalla principal de JANUS está visible (ella usa el micrófono). */
        @Volatile
        var appInForeground = false

        fun start(ctx: Context) {
            try { ctx.startForegroundService(Intent(ctx, WakeWordService::class.java)) } catch (_: Exception) {}
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, WakeWordService::class.java))
        }

        @Volatile
        private var sharedModel: Model? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var speaker: Speaker
    private lateinit var brain: Brain
    private lateinit var executor: ActionExecutor
    private lateinit var overlay: OverlayOrb
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var loop: VoiceLoop? = null
    private var inInteraction = false
    private var busy = false
    private var paused = false
    private var screenOff = false
    private var destroyed = false
    private var receiverOn = false
    private var commandWanted = false

    // Último estado mostrado (para el panel de bloqueo cuando se abre)
    private var lastState = OrbView.State.LISTENING
    private var lastMsg = ""

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOff = true
                    if (!prefs.listenScreenOff && !inInteraction) stopLoop()
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOff = false
                    main.postDelayed({ ensureLoop() }, 300)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        BeepMuter.restoreAfterCrash(this)
        prefs = Prefs(this)
        speaker = Speaker(this)
        speaker.onVoiceError = { reason -> uiText("⚠ Sin voz: $reason") }
        brain = Brain(this)
        executor = ActionExecutor(this)
        overlay = OverlayOrb(this)
        overlay.onTap = { cancelInteraction() }
        if (!goForeground()) {
            stopSelf(); return
        }
        instance = this
        screenOff = !getSystemService(PowerManager::class.java).isInteractive
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenReceiver, filter)
        receiverOn = true
        loadModel()
        main.postDelayed(watchdog, 20_000)
        main.postDelayed(headsetCheck, 3000)
    }

    /** Vigilante: cada 20 s comprueba que el micrófono sigue vivo y, si no, lo reinicia. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (destroyed) return
            val l = loop
            if (l != null && !l.isAlive) loop = null
            // Si algo se quedó colgado más de 35 s sin avanzar, se reinicia la conversación.
            if (inInteraction && !speaker.isSpeaking && System.currentTimeMillis() - lastActivity > 35_000) {
                speaker.stop()
                endInteraction()
            }
            if (!inInteraction) ensureLoop()
            main.postDelayed(this, 10_000)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            prefs.wakeWord = false
            stopSelf()
        } else if (intent?.action == ACTION_SUMMON) {
            summonNow()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        instance = null
        main.removeCallbacksAndMessages(null)
        if (receiverOn) {
            try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        }
        stopLoop()
        HeadsetMic.stop(this)
        InteractionFocus.release(this)
        speaker.shutdown()
        overlay.hide()
        LockActivity.current?.finish()
        super.onDestroy()
    }

    private fun goForeground(): Boolean = try {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Escucha de «Oye Janus»", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle("JANUS está atento")
            .setContentText("Diga «Oye Janus» en cualquier momento")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_jarvis), "Detener", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIF_ID, n)
        true
    } catch (e: Exception) {
        false
    }

    // ---------------- Interfaz (orbe flotante o panel sobre el bloqueo) ----------------
    private fun isLocked(): Boolean =
        getSystemService(KeyguardManager::class.java).isKeyguardLocked || !getSystemService(PowerManager::class.java).isInteractive

    @Volatile
    private var lastActivity = 0L

    private fun uiShow(state: OrbView.State, msg: String? = null) {
        lastActivity = System.currentTimeMillis()
        lastState = state
        if (msg != null) lastMsg = msg
        LockActivity.current?.show(state, msg)
        if (!isLocked()) overlay.show(state, msg)
    }

    private fun uiText(s: String) {
        lastActivity = System.currentTimeMillis()
        lastMsg = s
        LockActivity.current?.setText(s)
        if (overlay.isShown) overlay.setText(s)
    }

    private fun uiLevel(db: Float) {
        LockActivity.current?.setLevel(db)
        if (overlay.isShown) overlay.setLevel(db)
    }

    private fun uiHide() {
        overlay.hide()
        LockActivity.current?.finish()
    }

    /** Lo llama el panel de bloqueo al abrirse. */
    fun syncUi() {
        main.post { LockActivity.current?.show(lastState, lastMsg) }
    }

    fun cancelFromUi() {
        main.post { cancelInteraction() }
    }

    private fun showLockPanel() {
        if (LockActivity.current != null) return
        try {
            startActivity(
                Intent(this, LockActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        } catch (_: Exception) {
        }
    }

    // ---------------- Modelo local ----------------
    private fun loadModel() {
        if (sharedModel != null) { main.post { ensureLoop() }; return }
        Thread {
            val m = try {
                val dir = File(filesDir, MODEL_ASSET)
                val marker = File(dir, ".version")
                if (!marker.exists() || marker.readText() != MODEL_VERSION) {
                    dir.deleteRecursively()
                    copyAssets(MODEL_ASSET, dir)
                    marker.writeText(MODEL_VERSION)
                }
                Model(dir.absolutePath)
            } catch (e: Throwable) {
                null
            }
            main.post {
                if (m == null) {
                    if (!prefs.wakeWord) stopSelf()
                    return@post
                }
                sharedModel = m
                ensureLoop()
            }
        }.start()
    }

    private fun copyAssets(path: String, dest: File) {
        val list = assets.list(path) ?: emptyArray()
        if (list.isEmpty()) {
            dest.parentFile?.mkdirs()
            assets.open(path).use { input -> dest.outputStream().use { input.copyTo(it) } }
            return
        }
        dest.mkdirs()
        for (name in list) copyAssets("$path/$name", File(dest, name))
    }

    // ---------------- Micrófono continuo ----------------
    /** true mientras «Oye Janus» se escucha por el micrófono de los auriculares. */
    private var headsetWake = false

    /**
     * Con auriculares Bluetooth puestos, «Oye Janus» se escucha por su micrófono (el móvil puede
     * estar en el bolsillo). Mientras suena música se vuelve al micrófono del móvil, porque el
     * enlace de micrófono pondría la música con calidad de llamada.
     */
    private val headsetCheck = object : Runnable {
        override fun run() {
            if (destroyed) return
            try { updateHeadsetWake() } catch (_: Exception) {}
            main.postDelayed(this, 2000)
        }
    }

    private fun updateHeadsetWake() {
        if (inInteraction || busy) return
        val l = loop
        val am = getSystemService(android.media.AudioManager::class.java)
        val want = l != null && prefs.headsetWake && !speaker.isSpeaking && !am.isMusicActive && HeadsetMic.btAvailable(this)
        if (want && !headsetWake) {
            if (HeadsetMic.start(this)) {
                headsetWake = true
                l?.setWakeDevice(HeadsetMic.btInput(this))
                Diag.log("«Oye Janus» por el micrófono de los auriculares")
            }
        } else if (!want && headsetWake) {
            headsetWake = false
            l?.setWakeDevice(null)
            HeadsetMic.stop(this)
            Diag.log("«Oye Janus» por el micrófono del móvil" + if (am.isMusicActive) " (suena música)" else "")
        }
    }

    private fun shouldListen(): Boolean =
        !destroyed && !paused && !appInForeground &&
            (prefs.wakeWord || inInteraction) &&
            (!screenOff || prefs.listenScreenOff || inInteraction) &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun ensureLoop() {
        if (loop != null || !shouldListen()) return
        val model = sharedModel ?: return
        val l = VoiceLoop(model)
        loop = l
        try {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "janus:escucha").apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) {
        }
        l.start()
        if (commandWanted) {
            commandWanted = false
            l.startCommand(preRoll = false, ignoreMs = 450)
        }
    }

    private fun stopLoop() {
        val l = loop ?: return
        loop = null
        l.running = false
        try { l.join(700) } catch (_: Exception) {}
        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    private inner class VoiceLoop(private val model: Model) : Thread("janus-voz") {
        @Volatile
        var running = true
        @Volatile
        private var reqCommand = false
        @Volatile
        private var reqPreRoll = false
        @Volatile
        private var reqIgnore = 0
        @Volatile
        private var reqWake = false

        @Volatile
        private var reqHeadset = false

        /** Micrófono preferido para oír «Oye Janus» (los auriculares) o null para el del móvil. */
        @Volatile
        private var wakeDevice: android.media.AudioDeviceInfo? = null
        @Volatile
        private var wakeDeviceDirty = false

        fun setWakeDevice(d: android.media.AudioDeviceInfo?) { wakeDevice = d; wakeDeviceDirty = true }

        fun startCommand(preRoll: Boolean, ignoreMs: Int, headset: Boolean = false) {
            reqPreRoll = preRoll; reqIgnore = ignoreMs; reqHeadset = headset; reqCommand = true
        }

        fun toWake() { reqWake = true }

        private fun strip(t: String) = t.replace(WAKE, "").trim(' ', ',', '.', '!', '?', '¿', '¡')

        override fun run() {
            val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            var rec: AudioRecord? = null
            for (src in listOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
                try {
                    val r = AudioRecord(src, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, RATE))
                    if (r.state == AudioRecord.STATE_INITIALIZED) { rec = r; break }
                    r.release()
                } catch (_: Exception) {
                }
            }
            val r = rec
            if (r == null) {
                main.postDelayed({ if (loop === this) { loop = null; ensureLoop() } }, 3000)
                return
            }
            val aec = try { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(r.audioSessionId)?.apply { enabled = true } else null } catch (_: Exception) { null }
            val ns = try { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(r.audioSessionId)?.apply { enabled = true } else null } catch (_: Exception) { null }
            var wakeRec: Recognizer? = null
            var cmdRec: Recognizer? = null
            val frame = ShortArray(RATE / 10) // 100 ms
            val ring = ShortArray(RATE * 2)    // últimos 2 s (por si la orden va pegada a «Oye Janus»)
            var ringPos = 0
            val cmd = ByteArrayOutputStream()
            var mode = 0 // 0 = esperar nombre, 1 = grabar orden, 2 = en espera
            var noise = 400.0
            var speech = false
            var speechRun = 0
            var silence = 0
            var frames = 0
            var ignoreFrames = 0
            var hadPreRoll = false
            var speechTotal = 0
            // Para la orden se usa el micrófono «de reconocimiento de voz», que no distorsiona la voz
            // como el de llamadas (con auriculares Bluetooth se mantiene el de llamadas, que es el suyo).
            var cmdAr: AudioRecord? = null
            var cur: AudioRecord = r
            fun closeCmd() {
                val a = cmdAr ?: return
                cmdAr = null
                try { a.stop() } catch (_: Exception) {}
                a.release()
                cur = r
                try { r.startRecording() } catch (_: Exception) {}
            }
            var levelTick = 0

            try {
                val wr = Recognizer(model, RATE.toFloat())
                wakeRec = wr
                r.startRecording()
                while (running) {
                    val n = cur.read(frame, 0, frame.size)
                    if (n < 0) break
                    if (n == 0) continue

                    if (reqWake) { reqWake = false; mode = 0; closeCmd(); try { wr.reset() } catch (_: Exception) {} }
                    if (wakeDeviceDirty) {
                        wakeDeviceDirty = false
                        try { r.preferredDevice = wakeDevice } catch (_: Exception) {}
                        noise = 400.0
                    }
                    if (reqCommand) {
                        reqCommand = false
                        mode = 1
                        cmd.reset()
                        hadPreRoll = reqPreRoll
                        if (reqPreRoll) {
                            // Incluye los últimos 1,5 s: la orden puede ir pegada a «Oye Janus».
                            val pre = RATE * 3 / 2
                            for (k in 0 until pre) {
                                val v = ring[(ringPos - pre + k + ring.size * 2) % ring.size].toInt()
                                cmd.write(v and 0xFF); cmd.write((v shr 8) and 0xFF)
                            }
                        }
                        try { cmdRec?.close() } catch (_: Exception) {}
                        cmdRec = Recognizer(model, RATE.toFloat())
                        speech = false; speechRun = 0; silence = 0; frames = 0
                        ignoreFrames = reqIgnore / 100
                        speechTotal = 0
                        val svc = this@WakeWordService
                        // Auriculares Bluetooth: se espera a que su micrófono esté conectado de verdad y se graba de él.
                        val useHeadset = reqHeadset && cmdAr == null && HeadsetMic.awaitReady(svc)
                        if (reqHeadset && !useHeadset) HeadsetMic.stop(svc)
                        if (useHeadset) {
                            try {
                                val a = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, RATE))
                                if (a.state == AudioRecord.STATE_INITIALIZED) {
                                    HeadsetMic.btInput(svc)?.let { a.preferredDevice = it }
                                    try { r.stop() } catch (_: Exception) {}
                                    a.startRecording()
                                    cmdAr = a
                                    cur = a
                                } else a.release()
                            } catch (_: Exception) {
                            }
                        }
                        if (!useHeadset && cmdAr == null) {
                            try {
                                val a = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, RATE))
                                if (a.state == AudioRecord.STATE_INITIALIZED) {
                                    // Con auriculares de cable, su micrófono (más cerca de la boca).
                                    HeadsetMic.wiredMic(svc)?.let { a.preferredDevice = it }
                                    try { r.stop() } catch (_: Exception) {}
                                    a.startRecording()
                                    cmdAr = a
                                    cur = a
                                } else a.release()
                            } catch (_: Exception) {
                            }
                        }
                    }

                    for (i in 0 until n) { ring[ringPos] = frame[i]; ringPos = (ringPos + 1) % ring.size }
                    var sum = 0.0
                    for (i in 0 until n) { val v = frame[i].toDouble(); sum += v * v }
                    val rms = sqrt(sum / n)

                    if (mode == 0) {
                        if (rms < noise * 3) noise = noise * 0.97 + rms * 0.03
                        val final = wr.acceptWaveForm(frame, n)
                        val json = if (final) wr.result else wr.partialResult
                        val text = try { JSONObject(json).optString(if (final) "text" else "partial", "") } catch (_: Exception) { "" }
                        if (text.isNotBlank() && WAKE.containsMatchIn(text)) {
                            mode = 2
                            val rest = strip(text)
                            wr.reset()
                            if (rest.isNotEmpty() && brain.interpLang == null && Commands.instant(rest) != null) main.post { if (loop === this) onInstant(rest) }
                            else main.post { if (loop === this) onWake() }
                        }
                    } else if (mode == 1) {
                        frames++
                        for (i in 0 until n) { val v = frame[i].toInt(); cmd.write(v and 0xFF); cmd.write((v shr 8) and 0xFF) }
                        if (++levelTick % 2 == 0) {
                            val db = ((20 * log10(rms + 1) - 40) / 4).toFloat()
                            main.post { uiLevel(db) }
                        }
                        val c = cmdRec ?: continue
                        c.acceptWaveForm(frame, n)
                        val partial = try { JSONObject(c.partialResult).optString("partial", "") } catch (_: Exception) { "" }
                        if (partial.isNotBlank()) {
                            val p = strip(partial)
                            if (p.isNotEmpty() && brain.interpLang == null && Commands.instant(p) != null) {
                                mode = 2
                                closeCmd()
                                main.post { if (loop === this) onInstant(p) }
                                continue
                            }
                        }
                        val thresh = max(noise * 2.5, 500.0)
                        if (frames > ignoreFrames) {
                            if (rms > thresh) {
                                speechTotal++
                                speechRun++
                                if (speechRun >= 2) speech = true
                                silence = 0
                            } else {
                                speechRun = 0
                                if (speech) silence++ else if (rms < noise * 3) noise = noise * 0.97 + rms * 0.03
                            }
                        }
                        // Si aún ha dicho poco («llama a…»), se le da más margen para pensar.
                        val endSilence = if (speechTotal < 12) 11 else 7
                        val finish = (speech && silence >= endSilence) || frames >= 150 || (!speech && hadPreRoll && frames >= 25)
                        if (finish) {
                            mode = 2
                            closeCmd()
                            val text = try { JSONObject(c.finalResult).optString("text", "") } catch (_: Exception) { "" }
                            val pcm = cmd.toByteArray()
                            val local = strip(text)
                            main.post { if (loop === this) onCommandAudio(pcm, local) }
                        } else if (!speech && frames >= 60) {
                            mode = 2
                            closeCmd()
                            main.post { if (loop === this) onNoSpeech() }
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { cmdAr?.stop() } catch (_: Exception) {}
                try { cmdAr?.release() } catch (_: Exception) {}
                // Si el micrófono se cortó (otra app lo usó, una llamada…), se vuelve a abrir solo.
                if (running) main.postDelayed({ if (loop === this) { loop = null; ensureLoop() } }, 2000)
                try { r.stop() } catch (_: Exception) {}
                r.release()
                try { aec?.release() } catch (_: Exception) {}
                try { ns?.release() } catch (_: Exception) {}
                try { wakeRec?.close() } catch (_: Exception) {}
                try { cmdRec?.close() } catch (_: Exception) {}
            }
        }
    }

    // ---------------- Interacción ----------------
    private fun onWake() {
        if (busy) return
        beginInteraction(fromWake = true)
    }

    fun summonNow() {
        main.post {
            if (!destroyed) {
                speaker.stop()
                busy = false
                beginInteraction(fromWake = false)
            }
        }
    }

    private fun beginInteraction(fromWake: Boolean) {
        inInteraction = true
        InteractionFocus.hold(this) // pausa la música mientras escucha
        if (isLocked()) showLockPanel()
        uiShow(OrbView.State.LISTENING, "")
        val chime = Chime.start()
        val headset = HeadsetMic.start(this) // micrófono de los auriculares, si hay
        ensureLoop()
        val l = loop
        if (l == null) commandWanted = true
        // Si «Oye Janus» ya se oyó por los auriculares, lo dicho justo después también sirve.
        else l.startCommand(preRoll = fromWake && (!headset || headsetWake), ignoreMs = chime.toInt() + if (headset) 150 else 100, headset = headset)
    }

    /** Silencios seguidos en modo intérprete (se sigue escuchando un rato sin palabra de activación). */
    private var interpSilences = 0

    private fun onNoSpeech() {
        if (brain.interpLang != null && inInteraction && interpSilences++ < 8) {
            done(true); return
        }
        interpSilences = 0
        endInteraction()
    }

    private fun onInstant(text: String) {
        HeadsetMic.stop(this)
        if (!inInteraction) {
            inInteraction = true
            if (isLocked()) showLockPanel()
        }
        busy = true
        uiShow(OrbView.State.THINKING, text)
        brain.process(text) { reply -> handleReply(text, reply) }
    }

    private fun onCommandAudio(pcm: ByteArray, localText: String) {
        HeadsetMic.stop(this) // la respuesta suena en estéreo por los auriculares
        busy = true
        uiShow(OrbView.State.THINKING, if (localText.isNotBlank()) localText else "…")
        brain.processAudio(pcm, localText) { heard, reply ->
            if (heard.isBlank()) {
                val err = brain.lastAudioError
                brain.lastAudioError = ""
                if (err.isNotBlank()) sayThen("No he podido entenderle. $err") { done(brain.interpLang != null) }
                else if (brain.interpLang != null) onNoSpeech()
                else endInteraction()
            } else {
                interpSilences = 0
                uiText(heard)
                handleReply(heard, reply)
            }
        }
    }

    private fun sayThen(text: String, lang: String = "es", then: () -> Unit) {
        uiShow(OrbView.State.SPEAKING, text)
        speaker.speakIn(text, lang) { then() }
    }

    private fun handleReply(userText: String, reply: Reply) {
        if (reply.silent) {
            speaker.stop(); done(false); return
        }
        val a = reply.action
        if (a != null && a.type in NEEDS_UNLOCK && isLocked()) {
            val lock = LockActivity.current
            if (lock == null) {
                sayThen("Para eso necesito que desbloquee el móvil, ${prefs.userName}.") { done(false) }
                return
            }
            uiShow(OrbView.State.SPEAKING, "Desbloquee para continuar")
            lock.requestUnlock { ok ->
                main.post {
                    if (ok) {
                        LockActivity.current?.finish()
                        main.postDelayed({ execute(userText, reply) }, 600)
                    } else {
                        sayThen("Como desee, lo dejamos para luego.") { done(false) }
                    }
                }
            }
            return
        }
        execute(userText, reply)
    }

    private fun execute(userText: String, reply: Reply) {
        val a = reply.action
        if (InteractionFocus.touchesMedia(a)) InteractionFocus.release(this)
        if (a != null && ScreenAgent.handles(a)) {
            uiShow(OrbView.State.THINKING, reply.text ?: "En ello…")
            ScreenAgent.run(this, a, { p -> uiText(p) }) { result ->
                brain.remember(userText, result)
                brain.lastSpoken = result
                sayThen(result) { done(false) }
            }
            return
        }
        val text = if (a != null) ActionExecutor.choose(reply, executor.execute(a), prefs.userName) else reply.text ?: ""
        brain.remember(userText, text)
        brain.lastSpoken = text
        val follow = reply.listenAgain || (prefs.conversationMode && reply.fromLlm && a == null)
        if (text.isBlank()) {
            done(follow); return
        }
        sayThen(text, reply.lang) { done(follow) }
    }

    private fun done(follow: Boolean) {
        busy = false
        if (follow && inInteraction) {
            uiShow(OrbView.State.LISTENING, "")
            val headset = HeadsetMic.start(this)
            ensureLoop()
            loop?.startCommand(preRoll = false, ignoreMs = if (headset) 150 else 250, headset = headset)
        } else {
            main.postDelayed({ if (!busy) endInteraction() }, 500)
        }
    }

    private fun endInteraction() {
        HeadsetMic.stop(this)
        if (headsetWake) { headsetWake = false; loop?.setWakeDevice(null) }
        if (inInteraction) Chime.end()
        InteractionFocus.release(this)
        inInteraction = false
        busy = false
        commandWanted = false
        uiHide()
        loop?.toWake()
        if (!prefs.wakeWord) {
            main.postDelayed({ if (!inInteraction) stopSelf() }, 800)
        } else if (!shouldListen()) {
            stopLoop()
        }
    }

    private fun cancelInteraction() {
        speaker.stop()
        endInteraction()
    }

    /** La app principal pausa este servicio mientras está en pantalla (ella usa el micrófono). */
    fun setPaused(p: Boolean) {
        main.post {
            paused = p
            if (p) {
                if (inInteraction) {
                    speaker.stop()
                    endInteraction()
                }
                stopLoop()
            } else {
                main.postDelayed({ ensureLoop() }, 600)
            }
        }
    }
}
