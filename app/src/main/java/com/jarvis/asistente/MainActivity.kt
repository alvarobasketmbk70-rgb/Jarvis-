package com.jarvis.asistente

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Calendar

class MainActivity : Activity() {

    companion object {
        const val EXTRA_LISTEN = "listen"
        private const val REQ_PERMS = 10
        private const val BG = 0xFF070B14.toInt()
        private const val ACCENT = 0xFFFFB547.toInt()
        private const val IDLE_HINT = "Toque el orbe para hablar"
    }

    private lateinit var orb: OrbView
    private lateinit var status: TextView
    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText

    private lateinit var prefs: Prefs
    private lateinit var brain: Brain
    private lateinit var executor: ActionExecutor
    private lateinit var speaker: Speaker
    private var recognizer: SpeechRecognizer? = null
    private val main = Handler(Looper.getMainLooper())

    private var listening = false
    private var resumed = false
    private var greeted = false
    private var listenOnResume = false
    private var lastVoiceError: String? = null

    // ---------------- Ciclo de vida ----------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BeepMuter.restoreAfterCrash(this)
        buildUi()
        prefs = Prefs(this)
        brain = Brain(this)
        executor = ActionExecutor(this)
        val quick = wantsListen(intent)
        greeted = quick
        listenOnResume = quick
        speaker = Speaker(this) { onTtsReady() }
        speaker.onVoiceError = { reason ->
            if (reason != lastVoiceError) {
                lastVoiceError = reason
                addBubble("⚠ No he podido hablar: $reason", false)
            }
        }
        requestNeededPermissions()
        if (prefs.apiKey.isBlank()) {
            addBubble("Consejo: en ⚙ puede añadir una clave de IA gratuita para conversar conmigo y activar la accesibilidad para que pueda tocar la pantalla por usted.", false)
        }
        if (!LocalVoice.isReady) watchVoice(addBubble("Preparando la voz de JANUS… (solo tarda la primera vez)", false))
        if (prefs.wakeWord && hasMic()) WakeWordService.start(this)
        if (prefs.wakeWord && !android.provider.Settings.canDrawOverlays(this)) {
            addBubble("Para que aparezca sobre cualquier app al decir «Oye Janus», toque aquí y active «Mostrar sobre otras apps» para Janus.", false)
                .setOnClickListener {
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
                    } catch (_: Exception) {
                    }
                }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (wantsListen(intent)) {
            if (resumed) startListening() else listenOnResume = true
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        WakeWordService.appInForeground = true
        WakeWordService.instance?.setPaused(true)
        if (listenOnResume && hasMic()) {
            listenOnResume = false
            main.postDelayed({ startListening() }, 250)
        }
    }

    override fun onPause() {
        resumed = false
        stopListening()
        WakeWordService.appInForeground = false
        BeepMuter.unmuteNow(this)
        if (WakeWordService.instance == null && prefs.wakeWord && hasMic()) WakeWordService.start(this)
        WakeWordService.instance?.setPaused(false)
        super.onPause()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        speaker.shutdown()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun watchVoice(bubble: TextView, waited: Int = 0) {
        main.postDelayed({
            when {
                LocalVoice.isReady -> bubble.text = "Voz de JANUS lista."
                LocalVoice.state.startsWith("error") ->
                    bubble.text = "⚠ No se pudo cargar la voz de JANUS: ${LocalVoice.state.removePrefix("error: ")}"
                waited > 120 -> bubble.text = "La voz está tardando más de lo normal en prepararse…"
                else -> watchVoice(bubble, waited + 1)
            }
        }, 500)
    }

    private fun wantsListen(i: Intent?): Boolean =
        i != null && (i.getBooleanExtra(EXTRA_LISTEN, false) ||
            i.action == Intent.ACTION_ASSIST || i.action == Intent.ACTION_VOICE_COMMAND)

    private fun onTtsReady() {
        if (greeted) return
        greeted = true
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val saludo = when (h) {
            in 6..12 -> "Buenos días"
            in 13..20 -> "Buenas tardes"
            else -> "Buenas noches"
        }
        val g = "$saludo, ${prefs.userName}. ¿En qué puedo ayudarle?"
        addBubble(g, false)
        say(g) { if (resumed && hasMic()) startListening() else idle() }
    }

    // ---------------- Permisos ----------------
    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestNeededPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) {
            if (hasMic()) {
                if (prefs.wakeWord) WakeWordService.start(this)
                if (!listening && !speaker.isSpeaking) main.postDelayed({ if (resumed) startListening() }, 300)
            } else {
                status.text = "Necesito el micrófono para escucharle."
            }
        }
    }

    // ---------------- Reconocimiento de voz ----------------
    private fun ensureRecognizer(): SpeechRecognizer? {
        recognizer?.let { return it }
        val r = RecognizerFactory.create(this)
        if (r == null) {
            status.text = "No encuentro un reconocedor de voz. Instale o actualice la app de Google."
            return null
        }
        r.setRecognitionListener(listener)
        recognizer = r
        return r
    }

    private fun startListening() {
        if (listening) return
        if (!hasMic()) {
            requestNeededPermissions(); return
        }
        speaker.stop()
        WakeWordService.instance?.setPaused(true)
        val r = ensureRecognizer() ?: return
        listening = true
        orb.state = OrbView.State.LISTENING
        status.text = "Escuchando…"
        // Como Siri: pausa la música mientras escucha y suena el aviso de holograma.
        InteractionFocus.hold(this)
        val chime = Chime.start()
        // Con auriculares Bluetooth, escuchar por su micrófono (como Siri con los AirPods).
        val headset = HeadsetMic.start(this)
        main.postDelayed({
            if (!listening) return@postDelayed
            try {
                BeepMuter.mute(this)
                r.startListening(RecognizerFactory.intent(this, true))
            } catch (e: Exception) {
                listening = false
                recognizer?.destroy(); recognizer = null
                idle("No he podido usar el micrófono.")
            }
        }, chime + if (headset) 700 else 0)
    }

    private fun stopListening() {
        if (!listening) return
        listening = false
        recognizer?.cancel()
        BeepMuter.unmuteSoon(this)
        idle()
    }

    private fun idle(msg: String = IDLE_HINT) {
        HeadsetMic.stop(this)
        if (orb.state != OrbView.State.IDLE) Chime.end()
        InteractionFocus.release(this)
        orb.state = OrbView.State.IDLE
        status.text = msg
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            status.text = "Escuchando…"
            BeepMuter.unmuteSoon(this@MainActivity, 800)
        }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) { orb.setLevel(rmsdB) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            BeepMuter.mute(this@MainActivity)
            orb.state = OrbView.State.THINKING
            status.text = "Procesando…"
        }

        override fun onError(error: Int) {
            if (!listening) return
            listening = false
            BeepMuter.unmuteSoon(this@MainActivity)
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> idle("No le he oído. $IDLE_HINT.")
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                    idle("Sin conexión para reconocer la voz.")
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> idle("Falta el permiso de micrófono.")
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> {
                    recognizer?.destroy(); recognizer = null
                    idle("El reconocedor estaba ocupado. Inténtelo de nuevo.")
                }
                else -> idle("Error de reconocimiento ($error).")
            }
        }

        override fun onResults(results: Bundle?) {
            if (!listening) return
            listening = false
            BeepMuter.unmuteSoon(this@MainActivity)
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            if (text.isEmpty()) idle("No le he entendido.") else handleInput(text)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (t.isNullOrBlank()) return
            status.text = t
            // Órdenes sencillas («pausa la música», «qué hora es»): se ejecutan sin esperar al final.
            if (listening && brain.interpLang == null && Commands.instant(t) != null) {
                listening = false
                recognizer?.cancel()
                BeepMuter.unmuteSoon(this@MainActivity)
                handleInput(t)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ---------------- Lógica ----------------
    private fun handleInput(text: String) {
        HeadsetMic.stop(this) // la respuesta, en estéreo
        addBubble(text, true)
        orb.state = OrbView.State.THINKING
        status.text = "Pensando…"
        brain.process(text) { reply -> handleReply(text, reply) }
    }

    private fun handleReply(userText: String, reply: Reply) {
        if (reply.silent) {
            speaker.stop(); idle(); return
        }
        val action = reply.action
        if (InteractionFocus.touchesMedia(action)) InteractionFocus.release(this)
        if (action == null) {
            val t = reply.text ?: "…"
            val follow = reply.listenAgain || (prefs.conversationMode && reply.fromLlm)
            finishTurn(userText, t, follow, reply.lang)
            return
        }
        if (ScreenAgent.handles(action)) {
            // Tareas de varios pasos: JANUS se aparta y maneja la app él mismo.
            val ack = reply.text?.takeIf { it.isNotBlank() }?.let { Personality.confirm(it, prefs.userName) } ?: Personality.ack(prefs.userName)
            status.text = ack
            moveTaskToBack(true)
            // Primero confirma; cuando termina de hablar, se pone con la tarea.
            speaker.speak(ack) {
                ScreenAgent.run(this, action, { p -> status.text = p }) { result ->
                    finishTurn(userText, result, false)
                }
            }
            return
        }
        if (action.type in ActionExecutor.SCREEN_TYPES) {
            // Las órdenes de pantalla actúan sobre la app de detrás: Janus se aparta primero.
            status.text = "Ejecutando…"
            moveTaskToBack(true)
            main.postDelayed({
                val r = executor.execute(action)
                finishTurn(userText, ActionExecutor.choose(reply, r, prefs.userName), false)
            }, 700)
            return
        }
        val r = executor.execute(action)
        finishTurn(userText, ActionExecutor.choose(reply, r, prefs.userName), reply.listenAgain)
    }

    private fun finishTurn(userText: String, text: String, listenAfter: Boolean, lang: String = "es") {
        brain.remember(userText, text)
        if (text.isBlank()) {
            if (listenAfter && brain.interpLang != null && resumed) startListening() else idle()
            return
        }
        addBubble(text, false)
        say(text, lang) {
            if (listenAfter && resumed) startListening() else idle()
        }
    }

    private fun say(text: String, lang: String = "es", done: () -> Unit) {
        orb.state = OrbView.State.SPEAKING
        status.text = brain.interpLang?.let { "Intérprete · ${Languages.name(it)}" } ?: "Hablando…"
        brain.lastSpoken = text
        speaker.speakIn(text, lang, done)
    }

    private fun onOrbTap() {
        when {
            listening -> stopListening()
            speaker.isSpeaking -> { speaker.stop(); idle() }
            else -> startListening()
        }
    }

    private fun sendTyped() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        stopListening()
        speaker.stop()
        handleInput(t)
    }

    // ---------------- Interfaz ----------------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int, strokeColor: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (strokeColor != null) setStroke(dp(1), strokeColor)
    }

    private fun buildUi() {
        window.statusBarColor = BG
        window.navigationBarColor = BG
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(16), dp(28), dp(16), dp(12))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "J.A.N.U.S."
            setTextColor(ACCENT)
            textSize = 22f
            typeface = Typeface.create("sans-serif-light", Typeface.BOLD)
            letterSpacing = 0.3f
        }
        top.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val gear = TextView(this).apply {
            text = "⚙"
            textSize = 26f
            setTextColor(ACCENT)
            setPadding(dp(12), dp(4), dp(4), dp(4))
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        top.addView(gear)
        root.addView(top)

        orb = OrbView(this).apply {
            isClickable = true
            setOnClickListener { onOrbTap() }
        }
        root.addView(orb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)))

        status = TextView(this).apply {
            text = IDLE_HINT
            setTextColor(0xFFE8D9BE.toInt())
            textSize = 13f
            isAllCaps = true
            letterSpacing = 0.18f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(status)

        scroll = ScrollView(this)
        chat = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(chat)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        input = EditText(this).apply {
            hint = "Escriba una orden…"
            setHintTextColor(0xFF4A6572.toInt())
            setTextColor(Color.WHITE)
            background = rounded(0xFF111827.toInt(), 22, 0x55FFB547)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            setSingleLine(true)
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEND) {
                    sendTyped(); true
                } else false
            }
        }
        bar.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val send = TextView(this).apply {
            text = "➤"
            textSize = 22f
            setTextColor(ACCENT)
            setPadding(dp(14), dp(6), dp(6), dp(6))
            setOnClickListener { sendTyped() }
        }
        bar.addView(send)
        root.addView(bar)
        setContentView(root)
    }

    private fun addBubble(text: String, fromUser: Boolean): TextView {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(if (fromUser) Color.WHITE else 0xFFF4E6CC.toInt())
            background = if (fromUser) rounded(0xFF1A2333.toInt(), 16) else rounded(0xFF0C111C.toInt(), 16, 0x44FFB547)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
            gravity = if (fromUser) Gravity.END else Gravity.START
            if (fromUser) leftMargin = dp(48) else rightMargin = dp(48)
        }
        chat.addView(tv, lp)
        while (chat.childCount > 40) chat.removeViewAt(0)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        return tv
    }
}
