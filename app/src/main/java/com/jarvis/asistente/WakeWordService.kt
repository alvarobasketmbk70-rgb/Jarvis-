package com.jarvis.asistente

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer

/**
 * Modo manos libres: escucha continuamente y reacciona cuando oye «Jarvis…».
 * Funciona con el reconocedor de Google en bucle, así que consume batería y datos.
 */
class WakeWordService : Service() {

    companion object {
        private const val CHANNEL = "jarvis_wake"
        private const val NOTIF_ID = 7
        private const val ACTION_STOP = "com.jarvis.asistente.STOP_WAKE"
        private val WAKE = Regex("\\b(jarvis|yarvis|jarbis|yarbis|jervis|charvis|harvis|jarviz)\\b", RegexOption.IGNORE_CASE)

        @Volatile
        var instance: WakeWordService? = null
            private set

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
    private var recognizer: SpeechRecognizer? = null
    private var busy = false
    private var paused = false
    private var awaiting = false
    private var listening = false
    private var destroyed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        speaker = Speaker(this)
        brain = Brain(this)
        executor = ActionExecutor(this)
        if (!goForeground()) {
            stopSelf(); return
        }
        instance = this
        main.postDelayed({ listen() }, 800)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            prefs.wakeWord = false
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        instance = null
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        speaker.shutdown()
        super.onDestroy()
    }

    private fun goForeground(): Boolean = try {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Escucha de «Jarvis»", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle("Jarvis está atento")
            .setContentText("Diga «Jarvis» seguido de una orden")
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
            } else {
                main.postDelayed({ listen() }, 600)
            }
        }
    }

    private fun listen() {
        if (destroyed || busy || paused || listening) return
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
            r.startListening(RecognizerFactory.intent(this, false))
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
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onResults(results: Bundle?) {
            listening = false
            if (destroyed || paused) return
            onHeard(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf())
        }

        override fun onError(error: Int) {
            listening = false
            if (destroyed || paused) return
            when (error) {
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> recreate(1500)
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                    main.postDelayed({ listen() }, 5000)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> stopSelf()
                else -> {
                    awaiting = false
                    main.postDelayed({ listen() }, 300)
                }
            }
        }
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
        val cmd: String = command ?: run { listen(); return }
        busy = true
        if (cmd.isEmpty()) {
            speaker.speak("¿Sí, ${prefs.userName}?") {
                busy = false
                awaiting = true
                listen()
            }
            return
        }
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
        speaker.speak(text) { done(follow) }
    }

    private fun done(follow: Boolean) {
        busy = false
        awaiting = follow
        listen()
    }
}
