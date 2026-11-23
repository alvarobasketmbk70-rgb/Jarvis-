package com.jarvis.asistente

import android.Manifest
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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import java.io.File

/**
 * «Oye Janus», como «Oye Siri»:
 *  - Un detector local (Vosk, sin internet) escucha de forma continua y silenciosa: no reinicia
 *    el micrófono, no hace «bip» y no interrumpe la música ni los audios que esté escuchando.
 *  - Solo cuando oye su nombre aparece el orbe y se usa el reconocedor de Google, una vez,
 *    para entender la orden con precisión.
 */
class WakeWordService : Service() {

    companion object {
        private const val CHANNEL = "jarvis_wake"
        private const val NOTIF_ID = 7
        private const val ACTION_STOP = "com.jarvis.asistente.STOP_WAKE"
        const val ACTION_SUMMON = "com.jarvis.asistente.SUMMON"
        private const val MODEL_ASSET = "model-es"
        private const val MODEL_VERSION = "vosk-model-small-es-0.42"

        /** Formas inequívocas del nombre, y formas dudosas que solo valen tras «oye»/«hola». */
        private val WAKE = Regex(
            "\\b(janus|yanus|llanus|ianus|yannus|janos)\\b|" +
                "\\b(oye|hola|hey|eh)\\s+(llanos|llano|yanos|yano|ianos|jano|hanos|anos|ya nos|lanus|jonás|jonas|yanes|llanes)\\b",
            RegexOption.IGNORE_CASE
        )

        /**
         * Invoca a Janus al estilo Siri (orbe flotante sobre la app actual). Devuelve false
         * si no es posible y hay que abrir la app completa.
         */
        fun summon(ctx: Context): Boolean {
            if (!android.provider.Settings.canDrawOverlays(ctx)) return false
            if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
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

        /** true mientras la pantalla principal de Janus está visible (ella usa el micrófono). */
        @Volatile
        var appInForeground = false

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, WakeWordService::class.java))
            } catch (_: Exception) {
            }
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

    // Detector local de «Janus»
    private var wakeService: SpeechService? = null
    private var wakeRecognizer: Recognizer? = null

    // Reconocedor de Google para la orden
    private var recognizer: SpeechRecognizer? = null
    private var commandListening = false

    private var busy = false
    private var inInteraction = false
    private var paused = false
    private var screenOff = false
    private var destroyed = false
    private var receiverOn = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOff = true
                    stopWake()
                    if (!busy) endInteraction()
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOff = false
                    main.postDelayed({ startWake() }, 400)
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
        speaker.onVoiceError = { reason -> if (overlay.isShown) overlay.setText("⚠ Sin voz: $reason") }
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            prefs.wakeWord = false
            stopSelf()
        } else if (intent?.action == ACTION_SUMMON) {
            summonNow()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        instance = null
        main.removeCallbacksAndMessages(null)
        if (receiverOn) {
            try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        }
        stopWake()
        recognizer?.destroy()
        recognizer = null
        BeepMuter.unmuteNow(this)
        speaker.shutdown()
        overlay.hide()
        super.onDestroy()
    }

    private fun goForeground(): Boolean = try {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Escucha de «Oye Janus»", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle("Janus está atento")
            .setContentText("Diga «Oye Janus» seguido de una orden")
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

    // ---------------- Modelo local ----------------
    private fun loadModel() {
        sharedModel?.let { main.post { startWake() }; return }
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
                    // Sin detector local no hay «Oye Janus», pero el botón de encendido y el atajo siguen funcionando.
                    if (!prefs.wakeWord) stopSelf()
                    return@post
                }
                sharedModel = m
                startWake()
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

    // ---------------- Detector «Oye Janus» ----------------
    private fun canWake(): Boolean =
        prefs.wakeWord && !destroyed && !paused && !appInForeground && !screenOff && !busy && !inInteraction &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startWake() {
        if (wakeService != null || !canWake()) return
        val model = sharedModel ?: return
        try {
            val rec = Recognizer(model, 16000f)
            val svc = SpeechService(rec, 16000f)
            wakeRecognizer = rec
            wakeService = svc
            BeepMuter.unmuteNow(this)
            svc.startListening(wakeListener)
        } catch (e: Exception) {
            stopWake()
            main.postDelayed({ startWake() }, 3000)
        }
    }

    private fun stopWake() {
        val svc = wakeService
        wakeService = null
        try { svc?.stop() } catch (_: Exception) {}
        try { svc?.shutdown() } catch (_: Exception) {}
        try { wakeRecognizer?.close() } catch (_: Exception) {}
        wakeRecognizer = null
    }

    private val wakeListener = object : org.vosk.android.RecognitionListener {
        override fun onPartialResult(hypothesis: String?) = check(hypothesis, "partial")
        override fun onResult(hypothesis: String?) = check(hypothesis, "text")
        override fun onFinalResult(hypothesis: String?) = check(hypothesis, "text")
        override fun onError(exception: Exception?) {
            stopWake()
            main.postDelayed({ startWake() }, 2000)
        }
        override fun onTimeout() {
            stopWake()
            main.postDelayed({ startWake() }, 500)
        }

        private fun check(json: String?, field: String) {
            if (json == null || wakeService == null) return
            val text = try { JSONObject(json).optString(field, "") } catch (_: Exception) { "" }
            if (text.isNotBlank() && WAKE.containsMatchIn(text)) onWake()
        }
    }

    private fun onWake() {
        stopWake()
        beginInteraction()
    }

    // ---------------- Interacción (como Siri) ----------------
    /** Muestra el orbe y escucha una orden directamente (sin necesidad de decir el nombre). */
    fun summonNow() {
        main.post {
            if (destroyed) return@post
            speaker.stop()
            stopWake()
            stopCommand()
            busy = false
            beginInteraction()
        }
    }

    private fun beginInteraction() {
        inInteraction = true
        overlay.show(OrbView.State.LISTENING, "")
        // Pequeña pausa para que el micrófono pase del detector local a Google sin cortes.
        main.postDelayed({ startCommand() }, 200)
    }

    private fun endInteraction() {
        stopCommand()
        inInteraction = false
        busy = false
        BeepMuter.unmuteNow(this)
        overlay.hide()
        if (!prefs.wakeWord) {
            main.postDelayed({ if (!inInteraction) stopSelf() }, 800)
        } else {
            main.postDelayed({ startWake() }, 500)
        }
    }

    private fun cancelInteraction() {
        speaker.stop()
        endInteraction()
    }

    private fun startCommand() {
        if (destroyed || !inInteraction || commandListening) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            endInteraction(); return
        }
        val r = recognizer ?: RecognizerFactory.create(this)?.also {
            it.setRecognitionListener(commandListener)
            recognizer = it
        }
        if (r == null) {
            overlay.setText("No encuentro el reconocedor de voz de Google.")
            main.postDelayed({ endInteraction() }, 2500)
            return
        }
        commandListening = true
        overlay.show(OrbView.State.LISTENING)
        try {
            BeepMuter.mute(this)
            r.startListening(RecognizerFactory.intent(this, true))
        } catch (e: Exception) {
            commandListening = false
            recognizer?.destroy(); recognizer = null
            endInteraction()
        }
    }

    private fun stopCommand() {
        if (commandListening) {
            commandListening = false
            try { recognizer?.cancel() } catch (_: Exception) {}
        }
    }

    private val commandListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            BeepMuter.unmuteSoon(this@WakeWordService, 800)
        }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            if (overlay.isShown) overlay.setLevel(rmsdB)
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            BeepMuter.mute(this@WakeWordService)
            if (overlay.isShown) overlay.show(OrbView.State.THINKING)
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            if (!commandListening || busy) return
            val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
            if (t.isNotBlank()) overlay.setText(t)
            // Órdenes sencillas: se ejecutan sin esperar al final de la frase.
            if (Commands.instant(t) != null) runCommand(t.trim())
        }

        override fun onResults(results: Bundle?) {
            if (!commandListening) return
            commandListening = false
            BeepMuter.unmuteSoon(this@WakeWordService)
            val cmd = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            // Si el usuario repitió el nombre («Oye Janus, pon música»), se quita.
            val clean = cmd.replace(WAKE, "").trim(' ', ',', '.', '!', '?', '¿', '¡')
            if (clean.isEmpty()) endInteraction() else runCommand(clean)
        }

        override fun onError(error: Int) {
            if (!commandListening) return
            commandListening = false
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
                recognizer?.destroy(); recognizer = null
            }
            endInteraction()
        }
    }

    private fun runCommand(cmd: String) {
        if (busy) return
        stopCommand()
        BeepMuter.unmuteSoon(this)
        busy = true
        overlay.show(OrbView.State.THINKING, cmd)
        brain.process(cmd) { reply -> handleReply(cmd, reply) }
    }

    private fun handleReply(userText: String, reply: Reply) {
        if (reply.silent) {
            speaker.stop(); done(false); return
        }
        val a = reply.action
        val text = if (a != null) ActionExecutor.choose(reply, executor.execute(a)) else reply.text ?: ""
        brain.remember(userText, text)
        brain.lastSpoken = text
        val follow = reply.listenAgain || (prefs.conversationMode && reply.fromLlm && a == null)
        if (text.isBlank()) {
            done(follow); return
        }
        overlay.show(OrbView.State.SPEAKING, text)
        speaker.speak(text) { done(follow) }
    }

    private fun done(follow: Boolean) {
        busy = false
        if (follow && inInteraction) {
            overlay.show(OrbView.State.LISTENING, "")
            main.postDelayed({ startCommand() }, 150)
        } else {
            main.postDelayed({ if (!busy) endInteraction() }, 500)
        }
    }

    /** La app principal pausa este servicio mientras está en pantalla. */
    fun setPaused(p: Boolean) {
        main.post {
            paused = p
            if (p) {
                stopWake()
                if (inInteraction) {
                    speaker.stop()
                    endInteraction()
                }
            } else {
                main.postDelayed({ startWake() }, 600)
            }
        }
    }
}
