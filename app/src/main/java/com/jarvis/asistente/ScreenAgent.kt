package com.jarvis.asistente

import android.content.Context
import android.content.Intent
import android.app.SearchManager
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import org.json.JSONObject

/**
 * Modo agente: JANUS maneja las apps como lo haría una persona. Mira la pantalla, decide qué
 * tocar con ayuda de la IA, lo hace y repite hasta conseguir el objetivo.
 */
object ScreenAgent {
    private val main = Handler(Looper.getMainLooper())
    private const val MAX_STEPS = 10

    /** Acciones que se resuelven en varios pasos y en segundo plano. */
    fun handles(a: Action?): Boolean = a != null && a.type in setOf("agent", "spotify_play")

    fun run(ctx: Context, a: Action, onProgress: (String) -> Unit, onDone: (String) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            val result = try {
                when (a.type) {
                    "spotify_play" -> spotify(app, a.arg("query"), a.arg("kind"), onProgress)
                    else -> agent(app, a.arg("goal"), a.arg("app").ifBlank { null }, onProgress)
                }
            } catch (e: Exception) {
                "No he podido completarlo: ${e.message ?: "error inesperado"}."
            }
            main.post { onDone(result) }
        }.start()
    }

    private fun progress(cb: (String) -> Unit, s: String) = main.post { cb(s) }

    private fun musicPlaying(ctx: Context) = ctx.getSystemService(AudioManager::class.java).isMusicActive

    // ---------- Spotify: buscar y darle al play ----------
    private fun spotify(ctx: Context, query: String, kind: String, onProgress: (String) -> Unit): String {
        val spotify = ActionExecutor.SPOTIFY
        if (!Apps.isInstalled(ctx, spotify)) return "Spotify no está instalado."
        if (query.isBlank()) return "¿Qué quiere que ponga?"
        progress(onProgress, "Buscando «$query» en Spotify…")
        // Que termine de sonar la voz de JANUS: su voz también cuenta como «música sonando».
        var w = 0
        while (SpeakState.speaking && w < 4000) { Thread.sleep(100); w += 100 }
        Thread.sleep(300)
        val wasPlaying = musicPlaying(ctx)
        val search = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .setPackage(spotify)
            .putExtra(SearchManager.QUERY, query)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!Launcher.start(ctx, search)) {
            Launcher.start(
                ctx, Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(query))).setPackage(spotify)
            )
        }
        // ¿Ha empezado a sonar sola?
        for (i in 0 until 10) {
            Thread.sleep(400)
            if (!wasPlaying && musicPlaying(ctx)) return "Reproduciendo $query."
        }
        val svc = JarvisAccessibilityService.instance
            ?: return "He buscado $query en Spotify. Para que pueda darle al play yo mismo, active mi servicio de accesibilidad."
        // Si no, que el agente termine el trabajo en la pantalla de Spotify.
        val what = if (kind == "playlist") "una lista de reproducción (playlist) que encaje con «$query»; evita canciones sueltas"
        else "exactamente «$query»: si es una canción, esa canción; si es un artista, el artista; si es una lista, esa lista"
        val r = agent(ctx, "En Spotify, reproduce $what. Si ya aparecen resultados, elige el que mejor coincida (no simplemente el primero) y asegúrate de que empiece a sonar.", null, onProgress)
        if (musicPlaying(ctx)) return "Reproduciendo $query."
        if (svc.clickPlay()) {
            Thread.sleep(800)
            if (musicPlaying(ctx)) return "Reproduciendo $query."
        }
        return r
    }

    // ---------- Agente general ----------
    private val SYSTEM = """
Eres el módulo de control de pantalla de JANUS, un asistente de voz en un móvil Android. Recibes un objetivo y la descripción de la pantalla actual: elementos numerados [n] que se pueden tocar o escribir, y textos visibles (·).
Elige UNA acción cada vez y responde SOLO con un objeto JSON:
{"action":"click","id":n,"say":"breve descripción"}
{"action":"type","id":n,"text":"texto a escribir","say":"..."}
{"action":"enter","say":"..."}   (pulsa Intro/Buscar en el teclado)
{"action":"scroll","direction":"down|up|left|right","say":"..."}
{"action":"back","say":"..."}
{"action":"wait","say":"..."}    (si la pantalla está cargando)
{"action":"done","say":"frase corta para decir al usuario, de mayordomo formal"}
{"action":"fail","say":"explicación breve"}
Reglas: avanza lo más rápido posible hacia el objetivo; si ya se cumple, responde done. Para buscar: toca la lupa o el campo de búsqueda, escribe y pulsa enter. Nunca pagues, compres, borres, publiques ni envíes mensajes salvo que el objetivo lo pida expresamente. Si algo requiere contraseña o confirmación delicada, responde fail.
""".trimIndent()

    private fun agent(ctx: Context, goal: String, app: String?, onProgress: (String) -> Unit): String {
        val svc = JarvisAccessibilityService.instance
            ?: return "Para manejar las apps por usted necesito que active mi servicio de accesibilidad en ajustes."
        val prefs = Prefs(ctx)
        val key = prefs.apiKey
        val groq = prefs.groqKey
        if (key.isBlank() && groq.isBlank()) return "Para hacer tareas de varios pasos necesito una clave de Groq o de Gemini en ajustes."
        if (goal.isBlank()) return "¿Qué quiere que haga?"
        if (app != null) {
            val found = Apps.find(ctx, app) ?: return "No encuentro la aplicación $app."
            progress(onProgress, "Abriendo ${found.first}…")
            Launcher.start(ctx, found.second)
            Thread.sleep(2200)
        }
        val history = ArrayList<String>()
        for (step in 0 until MAX_STEPS) {
            val snap = svc.snapshot()
            val prompt = "Objetivo: $goal\nPasos ya hechos: ${if (history.isEmpty()) "ninguno" else history.joinToString("; ")}\n" +
                "App en pantalla: ${snap.pkg}\nPantalla:\n${snap.text}"
            val raw = try {
                if (groq.isNotBlank()) Groq.chat(groq, SYSTEM, emptyList(), prompt) else LlmClient.ask(key, "", SYSTEM, emptyList(), prompt)
            } catch (e: Exception) {
                if (groq.isNotBlank() && key.isNotBlank()) LlmClient.ask(key, "", SYSTEM, emptyList(), prompt) else throw e
            }
            val o = try {
                JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1))
            } catch (e: Exception) {
                return "No he entendido la pantalla. Inténtelo de nuevo."
            }
            val act = o.optString("action")
            val say = o.optString("say", "")
            if (say.isNotBlank() && act != "done" && act != "fail") progress(onProgress, say)
            val node = o.optInt("id", -1).let { if (it in snap.nodes.indices) snap.nodes[it] else null }
            when (act) {
                "done" -> return say.ifBlank { "Hecho." }
                "fail" -> return say.ifBlank { "No he podido completarlo." }
                "click" -> if (node == null || !svc.click(node)) history.add("intento fallido de pulsar ${o.optInt("id")}") else history.add("pulsado [${o.optInt("id")}] $say")
                "type" -> if (node == null || !svc.setText(node, o.optString("text"))) history.add("no se pudo escribir") else history.add("escrito «${o.optString("text")}»")
                "enter" -> { svc.pressEnter(); history.add("pulsado Intro") }
                "scroll" -> { svc.scroll(o.optString("direction", "down")); history.add("desplazado ${o.optString("direction")}") }
                "back" -> { svc.global("back"); history.add("atrás") }
                else -> history.add("esperando")
            }
            Thread.sleep(if (act == "type") 900 else 1300)
        }
        return "Lo he intentado, pero no he conseguido completarlo del todo."
    }
}
