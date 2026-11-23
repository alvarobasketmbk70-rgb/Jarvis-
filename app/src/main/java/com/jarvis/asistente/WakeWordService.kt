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

/**
 * Modo «Oye Jarvis»: escucha en segundo plano mientras la pantalla está encendida y,
 * al oír «Jarvis», muestra el orbe flotante sobre cualquier app y atiende la orden.
 */
class WakeWordService : Service() {

    companion object {
        private const val CHANNEL = "jarvis_wake"
        private const val NOTIF_ID = 7
        private const val ACTION_STOP = "com.jarvis.asistente.STOP_WAKE"
        const val ACTION_SUMMON = "com.jarvis.asistente.SUMMON"

        /**
         * Invoca a Jarvis al estilo Siri (orbe flotante sobre la app actual). Devuelve false
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
        private val WAKE = Regex("\\b(jarvis|yarvis|jarbis|yarbis|jervis|charvis|harvis|jarviz|chavis)\\b", RegexOption.IGNORE_CASE)

        @Volatile
        var instance: WakeWordService? = null
            private set

        /** true mientras la pantalla principal de Jarvis está visible (ella usa el micrófono). */
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
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var speaker: Speaker
    private lateinit var brain: Brain
    private lateinit var executor: ActionExecutor
    private lateinit var overlay: OverlayOrb
    private var recognizer: SpeechRecognizer? = null
    private var busy = false
    private var paused = false
    private var screenOff = false
    private var awaiting = false
    private var listening = false
    private var destroyed = false
    private var receiverOn = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOff = true
                    recognizer?.cancel()
                    listening = false
                    BeepMuter.unmuteNow(this@WakeWordService)
                    if (!busy) overlay.hide()
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOff = false
                    main.postDelayed({ listen() }, 500)
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
        main.postDelayed({ listen() }, 800)
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
        recognizer?.destroy()
        recognizer = null
        BeepMuter.unmuteNow(this)
        speaker.shutdown()
        overlay.hide()
        super.onDestroy()
    }

    private fun goForeground(): Boolean = try {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Escucha de «Oye Jarvis»", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle("Jarvis está atento")
            .setContentText("Diga «Oye Jarvis» seguido de una orden")
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

    /** La app principal pausa este servicio mientras está en pantalla. */
    fun setPaused(p: Boolean) {
        main.post {
            paused = p
            if (p) {
                recognizer?.cancel()
                listening = false
                BeepMuter.unmuteNow(this)
                overlay.hide()
            } else {
                main.postDelayed({ listen() }, 600)
            }
        }
    }

    /** Muestra el orbe y escucha una orden directamente (sin necesidad de decir «Jarvis»). */
    fun summonNow() {
        main.post {
            if (destroyed) return@post
            speaker.stop()
            recognizer?.cancel()
            listening = false
            busy = false
            awaiting = true
            overlay.show(OrbView.State.LISTENING, "")
            main.postDelayed({ listen() }, 150)
        }
    }

    private fun cancelInteraction() {
        speaker.stop()
        recognizer?.cancel()
        listening = false
        BeepMuter.unmuteNow(this)
        busy = false
        awaiting = false
        overlay.hide()
        main.postDelayed({ listen() }, 400)
    }

    private fun listen() {
        if (destroyed || busy || paused || appInForeground || screenOff || listening) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return
        }
        val r = recognizer ?: RecognizerFactory.create(this)?.also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        if (r == null) {
            stopSelf(); return
        }
        listening = true
        try {
            BeepMuter.mute(this)
            r.startListening(RecognizerFactory.intent(this, true))
        } catch (e: Exception) {
            recreate(1500)
        }
    }

    private fun recreate(delay: Long) {
        recognizer?.destroy()
        recognizer = null
        listening = false
        main.postDelayed({ listen() }, delay)
    }

    private val listener = object : RecognitionListener {
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
            if (awaiting && overlay.isShown) overlay.show(OrbView.State.THINKING)
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            if (!listening || busy) return
            val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
            if (awaiting) {
                if (t.isNotBlank()) overlay.setText(t)
                if (Commands.instant(t) != null) runEarly(t.trim())
                return
            }
            val m = WAKE.find(t) ?: return
            val rest = t.substring(m.range.last + 1).trim(' ', ',', '.', '!', '?', '¿', '¡')
            // En cuanto se oye «Jarvis» aparece el orbe, sin esperar al final de la frase.
            overlay.show(OrbView.State.LISTENING, rest)
            if (rest.isNotEmpty() && Commands.instant(rest) != null) runEarly(rest)
        }

        override fun onResults(results: Bundle?) {
            if (!listening) return
            listening = false
            BeepMuter.unmuteSoon(this@WakeWordService)
            if (destroyed || paused) return
            onHeard(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf())
        }

        override fun onError(error: Int) {
            if (!listening) return
            listening = false
            BeepMuter.unmuteSoon(this@WakeWordService)
            if (destroyed || paused) return
            if (awaiting) {
                awaiting = false
                overlay.hide()
                if (!prefs.wakeWord) {
                    stopSelf(); return
                }
            } else if (!busy) {
                overlay.hide()
            }
            when (error) {
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> recreate(1500)
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                    main.postDelayed({ listen() }, 5000)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> stopSelf()
                else -> main.postDelayed({ listen() }, 250)
            }
        }
    }

    /** Ejecuta una orden sencilla nada más reconocerla, cortando la escucha. */
    private fun runEarly(cmd: String) {
        listening = false
        awaiting = false
        recognizer?.cancel()
        BeepMuter.unmuteSoon(this)
        busy = true
        overlay.show(OrbView.State.THINKING, cmd)
        brain.process(cmd) { reply -> handleReply(cmd, reply) }
    }

    private fun onHeard(list: List<String>) {
        var command: String? = null
        if (awaiting) {
            awaiting = false
            command = list.firstOrNull()?.trim()
        } else {
            for (t in list) {
                val m = WAKE.find(t) ?: continue
                command = t.substring(m.range.last + 1).trim(' ', ',', '.', '!', '?', '¿', '¡')
                break
            }
        }
        val cmd: String = command ?: run {
            overlay.hide()
            listen()
            return
        }
        busy = true
        if (cmd.isEmpty()) {
            val q = "¿Sí, ${prefs.userName}?"
            overlay.show(OrbView.State.SPEAKING, q)
            speaker.speak(q) {
                busy = false
                awaiting = true
                overlay.show(OrbView.State.LISTENING, "")
                listen()
            }
            return
        }
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
        awaiting = follow
        if (!follow && !prefs.wakeWord) {
            main.postDelayed({ if (!busy && !awaiting) stopSelf() }, 800)
        }
        if (follow) overlay.show(OrbView.State.LISTENING, "") else main.postDelayed({ if (!busy && !awaiting) overlay.hide() }, 600)
        listen()
    }
}
