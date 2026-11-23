package com.jarvis.asistente

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date

data class Reply(
    val text: String?,
    val action: Action? = null,
    val listenAgain: Boolean = false,
    val silent: Boolean = false,
    val fromLlm: Boolean = false
)

/**
 * Decide qué hacer con lo que dice el usuario: primero intenta los comandos locales
 * (rápidos y sin internet) y, si no encajan, pregunta a la IA.
 */
class Brain(context: Context) {
    private val ctx = context.applicationContext
    private val prefs = Prefs(ctx)
    private val main = Handler(Looper.getMainLooper())
    private val history = ArrayList<Pair<String, String>>()
    private var pending: ((String) -> Parse)? = null
    var lastSpoken: String = ""

    fun process(input: String, cb: (Reply) -> Unit) {
        val text = input.trim()
        if (text.isEmpty()) {
            cb(Reply("No le he oído bien."))
            return
        }
        val p = pending
        if (p != null) {
            pending = null
            if (Commands.isCancel(text)) {
                cb(Reply("De acuerdo, cancelado."))
                return
            }
            handleParse(p(text), cb)
            return
        }
        val parsed = Commands.parse(text)
        if (parsed != null) {
            handleParse(parsed, cb)
            return
        }
        askLlm(text, cb)
    }

    fun remember(user: String, assistant: String) {
        if (assistant.isBlank()) return
        history.add(user to assistant)
        while (history.size > 10) history.removeAt(0)
    }

    private fun handleParse(parse: Parse, cb: (Reply) -> Unit) {
        when (parse) {
            is Parse.Say -> cb(Reply(parse.text.replace("{n}", prefs.userName), listenAgain = parse.listen))
            is Parse.Ask -> {
                pending = parse.next
                cb(Reply(parse.question, listenAgain = true))
            }
            is Parse.Do -> when (parse.action.type) {
                "repeat" -> cb(Reply(lastSpoken.ifBlank { "Aún no he dicho nada." }))
                "shutup" -> cb(Reply(null, silent = true))
                "briefing", "weather" -> {
                    val type = parse.action.type
                    Thread {
                        val text = if (type == "briefing") Briefing.morning(ctx) else (Briefing.weather(ctx) ?: "No he podido consultar el tiempo.")
                        main.post { cb(Reply(text)) }
                    }.start()
                }
                else -> cb(Reply(parse.say, action = parse.action))
            }
        }
    }

    private fun askLlm(text: String, cb: (Reply) -> Unit) {
        val key = prefs.apiKey
        if (key.isBlank()) {
            cb(Reply("No he entendido la orden. Si quiere que además pueda conversar con usted, añada una clave de API en mis ajustes."))
            return
        }
        val hist = ArrayList(history)
        val system = systemPrompt()
        val model = prefs.model
        Thread {
            val reply = try {
                parseLlm(LlmClient.ask(key, model, system, hist, text))
            } catch (e: Exception) {
                Reply("Lo siento, no he podido conectar con mi cerebro en la nube. ${LlmClient.friendly(e)}")
            }
            main.post { cb(reply) }
        }.start()
    }

    private fun parseLlm(raw: String): Reply {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return Reply(raw.trim().ifBlank { "Disculpe, no tengo respuesta." }, fromLlm = true)
        return try {
            val o = JSONObject(raw.substring(start, end + 1))
            val say = o.optString("say", "").trim()
            val listen = o.optBoolean("listen", false)
            var action: Action? = null
            val a = o.optJSONObject("action")
            if (a != null) {
                val type = a.optString("type", "")
                if (type.isNotBlank()) {
                    val args = HashMap<String, String>()
                    val keys = a.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (k != "type") args[k] = a.opt(k)?.toString() ?: ""
                    }
                    action = Action(type, args)
                }
            }
            Reply(say.ifBlank { null }, action, listen, fromLlm = true)
        } catch (e: Exception) {
            Reply(raw.trim(), fromLlm = true)
        }
    }

    private fun systemPrompt(): String {
        val now = SimpleDateFormat("EEEE d 'de' MMMM 'de' yyyy, HH:mm", ES).format(Date())
        val name = prefs.userName
        return """
Te llamas JANUS (se pronuncia «Yanus»): la inteligencia artificial personal de $name, al estilo del mayordomo digital de las películas de superhéroes. Eres impecablemente educado y formal, de una eficacia absoluta y con un humor seco, muy británico y sutil: ironía elegante, nunca payasadas. Tratas siempre de usted y llamas a tu usuario "$name". Confirmas las órdenes con frases como «Como desee, $name», «Ya está hecho» o «Considérelo hecho». Puedes permitirte algún comentario ingenioso breve, pero sin perder la compostura. Vives en su móvil Android. Si te preguntan tu nombre, eres JANUS; nunca digas que eres Jarvis, Siri, Gemini ni Claude.
Hablas siempre en español de España. Tus respuestas se leen en voz alta y deben ser rápidas: normalmente una frase, máximo dos. Sin markdown, sin listas, sin emojis y sin URLs.
Fecha y hora actuales: $now.

Responde SIEMPRE con un único objeto JSON, sin texto alrededor, con este formato:
{"say": "lo que dirás en voz alta", "action": null, "listen": false}
- "action": null si solo conversas. Si el usuario pide hacer algo en el móvil, usa UNA de las acciones de abajo.
- "listen": true si haces una pregunta y esperas su respuesta.

Acciones disponibles:
{"type":"open_app","app":"nombre de la app"}
{"type":"call","contact":"nombre del contacto o número"}
{"type":"whatsapp","contact":"nombre","message":"texto del mensaje"}
{"type":"sms","contact":"nombre","message":"texto"}
{"type":"media","cmd":"play|pause|next|previous"}
{"type":"spotify_play","query":"canción, artista, álbum o playlist"}   (busca y le da al play él mismo)
{"type":"agent","app":"nombre de la app (opcional)","goal":"objetivo concreto"}   (para tareas de varios pasos dentro de una app: buscar algo, darle a me gusta, poner una lista, cambiar un ajuste… JANUS mira la pantalla y toca lo necesario)
{"type":"youtube","query":"lo que buscar"}
{"type":"alarm","hour":"7","minute":"30"}
{"type":"timer","seconds":"300"}
{"type":"flashlight","on":"true|false"}
{"type":"volume","cmd":"up|down|mute|unmute|max"} o {"type":"volume","level":"0-100"}
{"type":"web_search","query":"..."}
{"type":"navigate","destination":"dirección o lugar"}
{"type":"maps_search","query":"farmacia"}
{"type":"click","text":"texto del botón visible en pantalla"}
{"type":"scroll","direction":"down|up|left|right"}
{"type":"global","cmd":"back|home|recents|notifications|quick_settings|screenshot|lock"}
{"type":"type_text","text":"texto a escribir en el campo activo"}
{"type":"read_screen"}
{"type":"time"} {"type":"date"} {"type":"battery"} {"type":"wifi"} {"type":"bluetooth"}

Si piden algo que no puedes hacer con estas acciones, dilo con elegancia y propone una alternativa. No inventes datos en tiempo real (tiempo, noticias, resultados deportivos): para eso usa web_search.
""".trimIndent()
    }
}

/** Cliente mínimo para Google Gemini o Anthropic Claude (según la clave). */
object LlmClient {
    class HttpError(val code: Int, body: String) : Exception(body)

    fun ask(key: String, model: String, system: String, history: List<Pair<String, String>>, user: String): String =
        if (key.startsWith("sk-ant-")) claude(key, model, system, history, user)
        else gemini(key, model, system, history, user)

    private fun part(text: String) = JSONArray().put(JSONObject().put("text", text))

    @Volatile
    private var geminiModel: String? = null
    @Volatile
    private var thinkingOk = true
    private val FAST_MODELS = listOf("gemini-flash-latest", "gemini-3.5-flash-lite", "gemini-flash-lite-latest")

    /** Usa el modelo más rápido disponible (Flash-Lite) con el razonamiento al mínimo. */
    private fun gemini(key: String, model: String, system: String, history: List<Pair<String, String>>, user: String): String {
        val models = if (model.isNotBlank()) listOf(model) else geminiModel?.let { listOf(it) } ?: FAST_MODELS
        var last: Exception? = null
        for (m in models) {
            try {
                val r = geminiCall(key, m, system, history, user, thinkingOk)
                if (model.isBlank()) geminiModel = m
                return r
            } catch (e: HttpError) {
                last = e
                if (e.code == 400 && thinkingOk) {
                    try {
                        val r = geminiCall(key, m, system, history, user, false)
                        thinkingOk = false
                        if (model.isBlank()) geminiModel = m
                        return r
                    } catch (e2: HttpError) {
                        last = e2
                        if (e2.code != 404) throw e2
                    }
                } else if (e.code != 404) {
                    throw e
                }
            }
        }
        throw last ?: HttpError(404, "sin modelo")
    }

    private fun geminiCall(
        key: String, m: String, system: String, history: List<Pair<String, String>>, user: String, minimalThinking: Boolean
    ): String {
        val contents = JSONArray()
        for ((u, a) in history) {
            contents.put(JSONObject().put("role", "user").put("parts", part(u)))
            contents.put(JSONObject().put("role", "model").put("parts", part(a)))
        }
        contents.put(JSONObject().put("role", "user").put("parts", part(user)))
        val gen = JSONObject()
            .put("responseMimeType", "application/json")
            .put("maxOutputTokens", 400)
        if (minimalThinking) gen.put("thinkingConfig", JSONObject().put("thinkingLevel", "minimal"))
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", part(system)))
            .put("contents", contents)
            .put("generationConfig", gen)
        val res = post(
            "https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent",
            body.toString(),
            mapOf("x-goog-api-key" to key)
        )
        val parts = JSONObject(res).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val pt = parts.getJSONObject(i)
            if (!pt.optBoolean("thought", false)) sb.append(pt.optString("text", ""))
        }
        return sb.toString()
    }

    private fun claude(key: String, model: String, system: String, history: List<Pair<String, String>>, user: String): String {
        val messages = JSONArray()
        for ((u, a) in history) {
            messages.put(JSONObject().put("role", "user").put("content", u))
            messages.put(JSONObject().put("role", "assistant").put("content", a))
        }
        messages.put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject()
            .put("model", model.ifBlank { "claude-haiku-5-5" })
            .put("max_tokens", 300)
            .put("system", system)
            .put("messages", messages)
        val res = post(
            "https://api.anthropic.com/v1/messages",
            body.toString(),
            mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01")
        )
        val content = JSONObject(res).getJSONArray("content")
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val c = content.getJSONObject(i)
            if (c.optString("type") == "text") sb.append(c.optString("text", ""))
        }
        return sb.toString()
    }

    private fun post(url: String, body: String, headers: Map<String, String>): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 8000
            c.readTimeout = 25000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            for ((k, v) in headers) c.setRequestProperty(k, v)
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw HttpError(code, text.take(400))
            return text
        } finally {
            c.disconnect()
        }
    }

    fun friendly(e: Exception): String = when (e) {
        is HttpError -> when {
            e.message?.contains("API_KEY", ignoreCase = true) == true -> "La clave de API no es válida."
            e.code == 401 || e.code == 403 -> "La clave de API no es válida."
            e.code == 404 -> "El modelo configurado no existe. Deje el campo de modelo vacío en ajustes."
            e.code == 429 -> "He superado el límite de uso de la API. Inténtelo en un momento."
            e.code == 400 -> "La petición no es válida; revise el modelo en ajustes."
            else -> "Error ${e.code}."
        }
        is UnknownHostException -> "No hay conexión a internet."
        is SocketTimeoutException -> "El servidor ha tardado demasiado."
        else -> ""
    }
}
