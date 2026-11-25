package com.jarvis.asistente

import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.KeyEvent
import android.widget.Toast
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Abre apps de forma fiable aunque JANUS esté en segundo plano.
 * Android 14+ bloquea en silencio las aperturas desde un servicio: aquí se piden con el
 * permiso explícito de inicio en segundo plano, desde el servicio de accesibilidad si está
 * activo, y se comprueba que la app llegó de verdad a la pantalla (si no, se reintenta).
 */
object Launcher {
    private val main = Handler(Looper.getMainLooper())

    fun start(ctx: Context, intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val c: Context = JarvisAccessibilityService.instance ?: ctx.applicationContext
        val target = intent.`package` ?: intent.component?.packageName
        val ok = launch(c, intent)
        // Solo se comprueba lo que debe verse en pantalla (abrir una app). Una búsqueda de música,
        // por ejemplo, puede sonar sin que Spotify salga delante, y repetirla la reiniciaría.
        val visible = intent.action == Intent.ACTION_MAIN || intent.hasCategory(Intent.CATEGORY_LAUNCHER)
        if (ok && target != null && visible) verify(c, intent, target, 0)
        return ok
    }

    private fun resolvable(c: Context, i: Intent): Boolean = try {
        c.packageManager.resolveActivity(i, 0) != null
    } catch (_: Exception) {
        false
    }

    private fun launch(c: Context, i: Intent): Boolean {
        // El PendingIntent no avisa si no hay app que lo abra: solo se usa si se sabe que la hay.
        if (Build.VERSION.SDK_INT >= 34 && resolvable(c, i)) {
            try {
                val creator = ActivityOptions.makeBasic()
                    .setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                val pi = PendingIntent.getActivity(
                    c, (System.nanoTime() and 0xFFFF).toInt(), i,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT, creator.toBundle()
                )
                val sender = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                pi.send(c, 0, null, null, null, null, sender.toBundle())
                return true
            } catch (_: Exception) {
                // se intenta por la vía normal
            }
        }
        return try {
            c.startActivity(i); true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            Diag.log("Sin permiso para abrir ${i.`package` ?: i.action}: ${e.message}")
            false
        }
    }

    private fun canCheck(c: Context): Boolean {
        val acc = JarvisAccessibilityService.instance ?: return false
        val km = c.getSystemService(KeyguardManager::class.java)
        val pm = c.getSystemService(PowerManager::class.java)
        return acc.foregroundPackage() != null && pm.isInteractive && !km.isKeyguardLocked
    }

    /** Comprueba que la app abierta está en primer plano; si no, reintenta por otra vía. */
    private fun verify(c: Context, i: Intent, target: String, attempt: Int) {
        main.postDelayed({
            if (!canCheck(c)) return@postDelayed
            val fg = JarvisAccessibilityService.instance?.foregroundPackage() ?: return@postDelayed
            if (fg == target) return@postDelayed
            if (attempt == 0) {
                Diag.log("$target no apareció en pantalla (delante: $fg). Reintento.")
                try { c.startActivity(Intent(i).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                verify(c, i, target, 1)
            } else {
                Diag.log("Android bloqueó la apertura de $target en segundo plano.")
                Toast.makeText(
                    c.applicationContext,
                    "JANUS no pudo abrir la app: en ⚙ pulse el paso 4 y active «Mostrar ventanas emergentes en segundo plano».",
                    Toast.LENGTH_LONG
                ).show()
            }
        }, if (attempt == 0) 1800L else 1500L)
    }
}

/** Si JANUS está hablando: su voz cuenta como «música sonando» para Android. */
object SpeakState {
    @Volatile
    var speaking = false
}

/** Teclas multimedia con comprobación: si la música no hizo caso, se repite. */
object MediaControl {
    fun send(ctx: Context, cmd: String) {
        val am = ctx.getSystemService(AudioManager::class.java)
        val code = when (cmd) {
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        fun key(k: Int) {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, k))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, k))
        }
        Thread {
            // El micrófono acaba de soltar el audio: el reproductor necesita un instante.
            Thread.sleep(350)
            key(code)
            when (cmd) {
                // Nunca se usa la tecla «alternar» al pausar: si se equivoca, volvería a sonar.
                // Mientras JANUS habla no se comprueba (su propia voz parece música).
                "pause" -> {
                    var retries = 0
                    var waited = 0
                    while (retries < 3 && waited < 8000) {
                        Thread.sleep(400); waited += 400
                        if (SpeakState.speaking || waited < 700) continue
                        if (!am.isMusicActive) break
                        retries++
                        Diag.log("La música seguía sonando tras pausar: reintento $retries.")
                        key(KeyEvent.KEYCODE_MEDIA_PAUSE)
                        Thread.sleep(500); waited += 500
                    }
                }
                "play" -> {
                    var retries = 0
                    var waited = 0
                    while (retries < 2 && waited < 8000) {
                        Thread.sleep(400); waited += 400
                        if (SpeakState.speaking || waited < 1000) continue
                        if (am.isMusicActive) break
                        retries++
                        Diag.log("La música no arrancó: reintento $retries.")
                        key(if (retries == 1) KeyEvent.KEYCODE_MEDIA_PLAY else KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                        Thread.sleep(600); waited += 600
                    }
                }
            }
        }.start()
    }
}

/**
 * Registro de las últimas órdenes: qué oyó JANUS, qué decidió y qué pasó.
 * Se ve en ⚙ → «Registro de órdenes», para saber exactamente dónde falla algo.
 */
object Diag {
    private const val MAX = 80
    private val lines = ArrayList<String>()
    private var ctx: Context? = null
    private val fmt = SimpleDateFormat("dd/MM HH:mm:ss", ES)

    fun init(c: Context) {
        if (ctx != null) return
        ctx = c.applicationContext
        try {
            val a = JSONArray(sp()?.getString("log", "[]") ?: "[]")
            synchronized(lines) { for (i in 0 until a.length()) lines.add(a.getString(i)) }
        } catch (_: Exception) {
        }
    }

    private fun sp() = ctx?.getSharedPreferences("jarvis_diag", Context.MODE_PRIVATE)

    fun log(s: String) {
        val line = fmt.format(Date()) + "  " + s.replace('\n', ' ').take(300)
        synchronized(lines) {
            lines.add(line)
            while (lines.size > MAX) lines.removeAt(0)
            val a = JSONArray(); lines.forEach { a.put(it) }
            sp()?.edit()?.putString("log", a.toString())?.apply()
        }
    }

    fun text(): String = synchronized(lines) { if (lines.isEmpty()) "Aún no hay órdenes registradas." else lines.reversed().joinToString("\n") }

    fun clear() = synchronized(lines) {
        lines.clear()
        sp()?.edit()?.remove("log")?.apply()
    }
}

/**
 * La IA a veces se inventa nombres de acción o de campo («play_music», «song»…).
 * Aquí se traducen a las acciones que JANUS sabe ejecutar, para que la orden no se pierda.
 */
object ActionFix {
    private val TYPES = mapOf(
        "play_music" to "spotify_play", "music" to "spotify_play", "spotify" to "spotify_play", "play_spotify" to "spotify_play",
        "spotify_search" to "spotify_play", "play_song" to "spotify_play", "play_playlist" to "spotify_play", "playlist" to "spotify_play",
        "play_artist" to "spotify_play", "play_album" to "spotify_play", "song" to "spotify_play",
        "open" to "open_app", "launch" to "open_app", "open_application" to "open_app", "app" to "open_app", "launch_app" to "open_app",
        "phone_call" to "call", "call_contact" to "call", "dial" to "call", "phone" to "call",
        "send_whatsapp" to "whatsapp", "whatsapp_message" to "whatsapp", "send_message" to "whatsapp", "message" to "whatsapp",
        "send_sms" to "sms", "text_message" to "sms",
        "set_alarm" to "alarm", "alarm_clock" to "alarm", "set_timer" to "timer", "countdown" to "timer",
        "torch" to "flashlight", "flash" to "flashlight", "linterna" to "flashlight",
        "set_volume" to "volume", "volume_up" to "volume", "volume_down" to "volume",
        "directions" to "navigate", "navigation" to "navigate", "maps" to "maps_search", "map" to "maps_search",
        "search" to "web_answer", "google" to "web_answer", "web" to "web_answer", "answer" to "web_answer", "lookup" to "web_answer",
        "youtube_search" to "youtube", "play_video" to "youtube", "video" to "youtube",
        "tap" to "click", "press" to "click", "swipe" to "scroll", "type" to "type_text", "write" to "type_text",
        "back" to "global", "home" to "global", "screenshot" to "global", "lock" to "global",
        "translation" to "translate", "translator" to "interpreter", "interpret" to "interpreter",
        "memorize" to "remember", "save_memory" to "remember",
        "pause" to "media", "resume" to "media", "play" to "media", "next" to "media", "skip" to "media",
        "previous" to "media", "stop_music" to "media", "pause_music" to "media", "next_song" to "media", "previous_song" to "media"
    )
    private val MEDIA = mapOf(
        "pause" to "pause", "stop" to "pause", "stop_music" to "pause", "pause_music" to "pause", "pausa" to "pause", "parar" to "pause",
        "play" to "play", "resume" to "play", "reanudar" to "play", "continue" to "play", "start" to "play",
        "next" to "next", "skip" to "next", "next_song" to "next", "siguiente" to "next", "forward" to "next",
        "previous" to "previous", "prev" to "previous", "back" to "previous", "previous_song" to "previous", "anterior" to "previous",
        "toggle" to "playpause", "play_pause" to "playpause"
    )

    private fun first(args: Map<String, String>, vararg keys: String): String =
        keys.firstNotNullOfOrNull { k -> args[k]?.takeIf { it.isNotBlank() && it != "null" } } ?: ""

    fun normalize(a: Action): Action {
        val raw = a.type.trim().lowercase().replace('-', '_').replace(' ', '_')
        val type = TYPES[raw] ?: raw
        val args = HashMap(a.args)
        when (type) {
            "spotify_play" -> args["query"] = first(a.args, "query", "song", "track", "artist", "playlist", "album", "name", "q", "search", "title")
            "open_app" -> args["app"] = first(a.args, "app", "name", "application", "app_name", "package", "target")
            "call" -> args["contact"] = first(a.args, "contact", "name", "number", "to", "phone", "who")
            "whatsapp", "sms" -> {
                args["contact"] = first(a.args, "contact", "to", "name", "recipient", "who", "number")
                args["message"] = first(a.args, "message", "text", "body", "msg", "content")
            }
            "media" -> args["cmd"] = MEDIA[first(a.args, "cmd", "command", "action").lowercase()] ?: MEDIA[raw] ?: first(a.args, "cmd").ifBlank { "playpause" }
            "volume" -> {
                if (args["cmd"].isNullOrBlank()) args["cmd"] = when (raw) { "volume_up" -> "up"; "volume_down" -> "down"; else -> first(a.args, "direction", "command") }
                if (args["level"].isNullOrBlank()) first(a.args, "value", "percent", "percentage").takeIf { it.isNotBlank() }?.let { args["level"] = it.filter(Char::isDigit) }
            }
            "timer" -> if (args["seconds"].isNullOrBlank()) {
                val min = first(a.args, "minutes", "mins").toDoubleOrNull()
                val dur = first(a.args, "duration", "time", "length").filter(Char::isDigit).toIntOrNull()
                args["seconds"] = (min?.let { (it * 60).toInt() } ?: dur ?: 0).toString()
            }
            "alarm" -> if (args["hour"].isNullOrBlank()) {
                Regex("(\\d{1,2})[:.h ]?(\\d{2})?").find(first(a.args, "time", "at", "when"))?.let { m ->
                    args["hour"] = m.groupValues[1]; args["minute"] = m.groupValues[2].ifBlank { "0" }
                }
            }
            "flashlight" -> {
                val v = first(a.args, "on", "state", "enable", "value").lowercase()
                args["on"] = if (v in setOf("false", "off", "apagar", "apaga", "0", "no")) "false" else "true"
            }
            "web_answer", "web_search", "youtube", "maps_search" -> args["query"] = first(a.args, "query", "q", "search", "question", "text", "topic")
            "navigate" -> args["destination"] = first(a.args, "destination", "to", "place", "address", "query", "location")
            "global" -> if (args["cmd"].isNullOrBlank()) args["cmd"] = if (raw in setOf("back", "home", "screenshot", "lock")) raw else first(a.args, "command", "action")
            "click" -> args["text"] = first(a.args, "text", "button", "label", "target", "name")
            "type_text" -> args["text"] = first(a.args, "text", "value", "content", "message")
        }
        if (type != a.type) Diag.log("Acción de la IA «${a.type}» corregida a «$type»")
        return Action(type, args)
    }
}
