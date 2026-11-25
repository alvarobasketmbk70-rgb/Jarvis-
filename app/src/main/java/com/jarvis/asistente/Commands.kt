package com.jarvis.asistente

/** Una acción que Janus puede ejecutar en el móvil. */
data class Action(val type: String, val args: Map<String, String> = emptyMap()) {
    fun arg(k: String): String = args[k]?.trim() ?: ""
}

/** Resultado de interpretar una frase. */
sealed class Parse {
    class Do(val action: Action, val say: String? = null) : Parse()
    class Ask(val question: String, val next: (String) -> Parse) : Parse()
    class Say(val text: String, val listen: Boolean = false) : Parse()
}

/**
 * Frase preparada: [soft] conserva tildes (para mensajes y nombres) y [plain] es la misma
 * cadena sin tildes y con idéntica longitud, para que los índices de las expresiones
 * regulares sirvan en ambas.
 */
class Utt(raw: String) {
    val soft: String
    val plain: String

    init {
        var s = raw.lowercase(ES)
            .replace(Regex("[¿¡?!.,;:\"“”«»()]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val prefix = Regex(
            "^((oye|hola|hey|eh|vale)\\s+)?((janus|yanus|llanus|ianus|yanos|llanos|janos|ianos|yannus|jano)\\s+)?" +
                "((por favor|porfa|puedes|podr[ií]as|me puedes|me podr[ií]as|quiero que|necesito que)\\s+)*"
        )
        s = s.replaceFirst(prefix, "")
        s = s.replace(Regex("\\s+por favor$"), "").trim()
        soft = s
        plain = TextUtil.stripAccents(s)
    }

    fun g(m: MatchResult, i: Int): String {
        val gr = m.groups[i] ?: return ""
        return soft.substring(gr.range.first, gr.range.last + 1).trim()
    }
}

object Commands {
    private val WAKE_ONLY = Regex("^(oye |hola |hey )?(janus|yanus|llanus|ianus|yanos|llanos|janos|ianos|yannus|jano)$")
    private val CANCEL = Regex("^(no|nada|nada mas|cancela|cancelar|cancelalo|olvidalo|dejalo|da igual|no importa|eso es todo|adios|hasta luego|nada gracias|no gracias)$")
    private val THANKS = Regex("^(gracias|muchas gracias|gracias (janus|yanus|llanus)|genial gracias|perfecto gracias|perfecto|genial)$")
    private val BRIEFING = Regex("^(buenos dias|buen dia|buenas tardes|buenas|informe|dame el informe|informe del dia|resumen del dia|que tengo hoy|como pinta el dia|como viene el dia|empecemos)( janus| yanus| llanus| jano| senor)?$")
    private val WEATHER = Regex("que tiempo hace|tiempo va a hacer|el tiempo (de )?hoy|va a llover|llovera|que temperatura|hace frio|hace calor|parte meteorologico|tiempo que hace")
    private val SHUT = Regex("^(callate|calla|silencio|para de hablar|basta|cierra el pico)$")
    private val REPEAT = Regex("^(repite|repitelo|repite eso|que has dicho|puedes repetir|como dices)$")

    /**
     * Órdenes sin parámetros que se pueden ejecutar en cuanto aparecen en el reconocimiento
     * parcial, sin esperar a que termine la frase (así responde al instante).
     */
    fun instant(raw: String): Action? {
        val p = parse(raw) as? Parse.Do ?: return null
        val a = p.action
        return when (a.type) {
            "flashlight", "time", "date", "battery" -> a
            "media" -> if (a.arg("cmd") in setOf("pause", "next", "previous")) a else null
            else -> null
        }
    }

    fun isCancel(raw: String): Boolean = CANCEL.matches(Utt(raw).plain)

    private fun act(type: String, vararg kv: Pair<String, String>) = Parse.Do(Action(type, mapOf(*kv)))

    private fun cleanAnswer(s: String) =
        s.trim().replace(Regex("^(a|al|para) ", RegexOption.IGNORE_CASE), "").trim()

    fun parse(raw: String): Parse? {
        val u = Utt(raw)
        val p = u.plain
        if (p.isEmpty()) return null
        if (WAKE_ONLY.matches(p)) return Parse.Say("¿Sí, {n}?", listen = true)
        if (CANCEL.matches(p)) return Parse.Say(Personality.okay("{n}"))
        if (THANKS.matches(p)) return Parse.Say(Personality.thanks("{n}"))
        if (BRIEFING.matches(p)) return act("briefing")
        if (WEATHER.containsMatchIn(p)) return act("weather")
        if (SHUT.matches(p)) return act("shutup")
        if (REPEAT.matches(p)) return act("repeat")
        if (Regex("(ajustes|configuracion|opciones) de (janus|yanus|llanus)|configurar? (janus|yanus|llanus)").containsMatchIn(p)) return act("jarvis_settings")

        LangCommands.parse(u)?.let { return it }
        parseMessage(u)?.let { return it }
        parseCall(u)?.let { return it }
        parseMusic(u)?.let { return it }
        parseVolume(p)?.let { return it }
        parseTimerAlarm(p)?.let { return it }
        parseDevice(p)?.let { return it }
        parseScreen(u)?.let { return it }
        parseSearch(u)?.let { return it }
        parseOpen(u)?.let { return it }
        return null
    }

    // ---------- Mensajes (WhatsApp / SMS) ----------
    private val MSG = Regex(
        "^(manda|mandale|mandar|envia|enviale|enviar|escribe|escribele|escribir|dile)(?: un| una)?" +
            "( mensaje de texto| mensaje| whatsapp| wasap| guasap| wassap| watsap| sms)?" +
            "(?: de whatsapp| por whatsapp| por sms| de texto)?( a| al)?(?: (.*))?$"
    )
    private val SEP = Regex(" (diciendole|diciendo|que diga|que ponga|que dice|con el texto|con el mensaje|que) ")

    private fun parseMessage(u: Utt): Parse? {
        val m = MSG.find(u.plain) ?: return null
        val verb = m.groupValues[1]
        val channel = m.groups[2]?.value
        val marker = m.groups[3]?.value
        if (channel == null && marker == null && verb !in setOf("dile", "escribele", "mandale", "enviale")) return null
        val type = if (channel != null && (channel.contains("sms") || channel.contains("texto"))) "sms" else "whatsapp"
        val range = m.groups[4]?.range
        val restPlain = if (range != null) u.plain.substring(range.first, range.last + 1) else ""
        val restSoft = if (range != null) u.soft.substring(range.first, range.last + 1) else ""
        var contact = restSoft.trim()
        var message = ""
        val s = SEP.find(restPlain)
        if (s != null) {
            contact = restSoft.substring(0, s.range.first).trim()
            message = restSoft.substring(s.range.last + 1).trim()
            if (s.groupValues[1].startsWith("diciendo")) message = message.removePrefix("que ").trim()
        }
        return buildMsg(type, contact, message)
    }

    private fun buildMsg(type: String, contact: String, message: String): Parse = when {
        contact.isBlank() -> Parse.Ask("¿A quién se lo envío?") { c -> buildMsg(type, cleanAnswer(c), message) }
        message.isBlank() -> Parse.Ask("¿Qué le digo a ${TextUtil.cap(contact)}?") { t -> buildMsg(type, contact, t.trim()) }
        else -> Parse.Do(Action(type, mapOf("contact" to contact, "message" to TextUtil.cap(message))))
    }

    // ---------- Llamadas ----------
    private val CALL = Regex("^(?:llama|llamar|llamale|telefonea|marca|haz una llamada|hacer una llamada|realiza una llamada)(?: a| al)?(?: (.+))?$")

    private fun parseCall(u: Utt): Parse? {
        val m = CALL.find(u.plain) ?: return null
        val who = u.g(m, 1).replace(Regex(" (por telefono|al movil|al fijo)$"), "").trim()
        return callOrAsk(who)
    }

    private fun callOrAsk(who: String): Parse =
        if (who.isBlank()) Parse.Ask("¿A quién llamo?") { c -> callOrAsk(cleanAnswer(c)) }
        else act("call", "contact" to who)

    // ---------- Música ----------
    private val SPOTIFY_EN = Regex("^(?:pon|ponme|reproduce|reproducir|toca|quiero escuchar|escuchar|busca|play)(?: la cancion| el album| el disco| la playlist| la lista| musica de| canciones de| algo de)? (.+?) en spotify$")
    private val SPOTIFY_HINT = Regex("^(?:pon|ponme|reproduce|quiero escuchar|toca)(?: algo de| musica de| canciones de| la cancion| el album| el disco| la playlist| la lista de reproduccion) (.+)$")
    private val PAUSE1 = Regex("^(pausa|pausar|stop|pon pausa|pon en pausa)( .*)?$")
    private val PAUSE2 = Regex("^(para|parar|deten|detener|quita|apaga|corta)( la| el)? (musica|cancion|spotify|reproduccion|audio)$")
    private val NEXT = Regex("^(siguiente|la siguiente|otra|otra cancion|salta|pasa)$|(siguiente|proxima) (cancion|tema|pista)|(pasa|salta|cambia)( de| la| esta)? (cancion|tema|pista)")
    private val PREV = Regex("^(anterior|la anterior|pon la anterior|vuelve a la anterior)$|(cancion|tema|pista) anterior")
    private val PLAY = Regex("^(reanuda|reanudar|continua|continuar|sigue|seguir|play|dale al play|quita la pausa|vuelve a poner)( la| el)?( musica| cancion| spotify| reproduccion)?$")

    private fun parseMusic(u: Utt): Parse? {
        val p = u.plain
        // «Pon música» sin decir cuál: JANUS preguntará qué le apetece (ver Clarify).
        if (Clarify.isVagueMusic(p)) return act("spotify_play", "query" to "")
        SPOTIFY_EN.find(p)?.let { return act("spotify_play", "query" to u.g(it, 1)) }
        SPOTIFY_HINT.find(p)?.let { return act("spotify_play", "query" to u.g(it, 1)) }
        if (PAUSE1.matches(p) || PAUSE2.matches(p)) return act("media", "cmd" to "pause")
        if (NEXT.containsMatchIn(p)) return act("media", "cmd" to "next")
        if (PREV.containsMatchIn(p)) return act("media", "cmd" to "previous")
        if (PLAY.matches(p)) return act("media", "cmd" to "play")
        return null
    }

    // ---------- Volumen ----------
    private fun parseVolume(p: String): Parse? {
        val muteOnly = Regex("^(silencia|mutea|quita el sonido|pon el sonido|activa el sonido)( el movil| el telefono| la musica)?$")
        if (!p.contains("volumen") && !muteOnly.matches(p)) return null
        Regex("(?:al|a) (\\d{1,3})(?: por ciento|%)?").find(p)?.let { return act("volume", "level" to it.groupValues[1]) }
        val cmd = when {
            Regex("maximo|tope|todo").containsMatchIn(p) -> "max"
            Regex("minimo").containsMatchIn(p) -> "min"
            Regex("\\b(sube|subir|subele|aumenta|aumentar|mas)\\b").containsMatchIn(p) -> "up"
            Regex("\\b(baja|bajar|bajale|reduce|reducir|disminuye|menos)\\b").containsMatchIn(p) -> "down"
            Regex("\\b(pon el sonido|activa)\\b").containsMatchIn(p) -> "unmute"
            Regex("\\b(silencia|quita|mutea|sin)\\b").containsMatchIn(p) -> "mute"
            else -> "up"
        }
        return act("volume", "cmd" to cmd)
    }

    // ---------- Temporizadores y alarmas ----------
    private fun parseTimerAlarm(p: String): Parse? {
        if (Regex("temporizador|cuenta atras|cronometro|avisame (en|dentro de)|recuerdame (en|dentro de)").containsMatchIn(p)) {
            return timerOrAsk(TimeParse.duration(p))
        }
        if (Regex("alarma|despiertame|despertador").containsMatchIn(p)) {
            if (Regex("(quita|borra|elimina|cancela|desactiva|apaga|muestra|ensena|ver|abre)( la| las| una| mis)? alarmas?").containsMatchIn(p)) {
                return act("alarms_open")
            }
            if (Regex("dentro de|\\ben \\d+ (minutos?|horas?)").containsMatchIn(p)) {
                val d = TimeParse.duration(p)
                if (d > 0) return act("timer", "seconds" to d.toString())
            }
            return alarmOrAsk(TimeParse.time(p))
        }
        return null
    }

    private fun timerOrAsk(seconds: Int): Parse =
        if (seconds > 0) act("timer", "seconds" to seconds.toString())
        else Parse.Ask("¿De cuánto tiempo pongo el temporizador?") { a ->
            val d = TimeParse.duration(TextUtil.norm(a))
            if (d > 0) act("timer", "seconds" to d.toString()) else Parse.Say("No he entendido la duración.")
        }

    private fun alarmOrAsk(t: Pair<Int, Int>?): Parse =
        if (t != null) act("alarm", "hour" to t.first.toString(), "minute" to t.second.toString())
        else Parse.Ask("¿A qué hora pongo la alarma?") { a ->
            val tt = TimeParse.time(TextUtil.norm(a))
            if (tt != null) act("alarm", "hour" to tt.first.toString(), "minute" to tt.second.toString())
            else Parse.Say("No he entendido la hora.")
        }

    // ---------- Móvil ----------
    private fun parseDevice(p: String): Parse? {
        if (Regex("linterna|el flash|la luz del movil").containsMatchIn(p)) {
            val off = Regex("\\b(apaga|apagar|desactiva|quita)\\b").containsMatchIn(p)
            return act("flashlight", "on" to (!off).toString())
        }
        if (Regex("que hora es|^la hora$|dime la hora|que horas son|tienes hora|^hora$").containsMatchIn(p)) return act("time")
        if (Regex("que dia es|que fecha|fecha de hoy|a que dia estamos|que dia estamos|en que dia estamos").containsMatchIn(p)) return act("date")
        if (Regex("^(cuanta |que |la )?bateria|nivel de bateria|cuanta bateria|bateria (me )?queda|cuanta carga").containsMatchIn(p)) return act("battery")
        if (Regex("\\b(wifi|wi fi)\\b").containsMatchIn(p)) return act("wifi")
        if (Regex("bluetooth|blutu").containsMatchIn(p)) return act("bluetooth")
        if (Regex("modo avion").containsMatchIn(p)) return act("airplane")
        return null
    }

    // ---------- Control de pantalla (accesibilidad) ----------
    private val CLICK = Regex("^(?:pulsa|pulsar|toca|tocar|presiona|presionar|haz clic|haz click|clic|click|clica|dale|selecciona|elige|pincha)(?: en| a| al| sobre| el boton| el| la| los| las)* (.+)$")
    private val TYPE = Regex("^(?:escribe|teclea|escribir|introduce|pon el texto)(?: el texto| esto)? (.+)$")

    private fun parseScreen(u: Utt): Parse? {
        val p = u.plain
        fun g(cmd: String) = act("global", "cmd" to cmd)
        if (Regex("^(atras|vuelve atras|ve atras|volver|vuelve|retrocede|regresa|ir atras)$").matches(p)) return g("back")
        if (Regex("^(inicio|ve al inicio|ve a inicio|vete al inicio|pantalla de inicio|ir al inicio|ir a inicio|al inicio|escritorio|pantalla principal|sal|salir|sal de aqui)$").matches(p)) return g("home")
        if (Regex("^((abre|muestra|ensena) )?(las )?(aplicaciones |apps )?recientes$|^multitarea$").matches(p)) return g("recents")
        if (Regex("^((abre|muestra|baja|ensena|despliega|ver)( la barra de| las| el panel de)? )?notificaciones$").matches(p)) return g("notifications")
        if (Regex("ajustes rapidos|panel rapido|centro de control").containsMatchIn(p)) return g("quick_settings")
        if (Regex("captura de pantalla|haz una captura|pantallazo|captura la pantalla|^captura$").containsMatchIn(p)) return g("screenshot")
        if (Regex("^(bloquea|apaga)( el| la)? (movil|telefono|pantalla)$").matches(p)) return g("lock")

        if (Regex("^(baja|bajar|baja mas|desplazate( hacia)? abajo|desplaza( hacia)? abajo|desliza( hacia)? abajo|scroll( abajo)?|sigue bajando|mas abajo|abajo)$").matches(p)) return act("scroll", "direction" to "down")
        if (Regex("^(sube|subir|sube mas|desplazate( hacia)? arriba|desplaza( hacia)? arriba|desliza( hacia)? arriba|scroll arriba|mas arriba|arriba)$").matches(p)) return act("scroll", "direction" to "up")
        if (Regex("^(desliza|pasa|desplaza|desplazate)( a la| hacia la)? izquierda$").matches(p)) return act("scroll", "direction" to "left")
        if (Regex("^(desliza|pasa|desplaza|desplazate)( a la| hacia la)? derecha$").matches(p)) return act("scroll", "direction" to "right")

        if (Regex("(lee|leeme|lee me|que pone|que hay|que dice|describe|leer)( en)?( la| mi)? pantalla|^(lee esto|que pone aqui|leelo|leeme esto)$").containsMatchIn(p)) return act("read_screen")
        CLICK.find(p)?.let { return act("click", "text" to u.g(it, 1)) }
        TYPE.find(p)?.let { return act("type_text", "text" to u.g(it, 1)) }
        return null
    }

    // ---------- Búsquedas y navegación ----------
    private val YT = Regex("^(?:pon|ponme|busca|buscame|reproduce|quiero ver|abre)(?: el video de| videos de| un video de)? (.+?) en youtube$")
    private val YT2 = Regex("^(?:busca en youtube|pon en youtube|youtube) (.+)$")
    private val NAV = Regex("^(?:llevame|como llego|como voy|como se va|navega|navegar|ruta|indicaciones|direcciones|guiame)(?: a| al| hasta| hacia| para ir a| para llegar a| para)? (.+)$")
    private val NEAR = Regex("^(?:busca|buscame|donde hay|encuentra|hay) (?:un |una |algun |alguna )?(.+?) (?:cerca|cerca de mi|cerca de aqui|por aqui|por la zona)$")
    private val WEB = Regex("^(?:busca|buscame|busca en google|busca en internet|googlea|buscar)(?: en google| en internet| informacion sobre| informacion de| sobre)? (.+)$")

    private fun parseSearch(u: Utt): Parse? {
        val p = u.plain
        YT.find(p)?.let { return act("youtube", "query" to u.g(it, 1)) }
        YT2.find(p)?.let { return act("youtube", "query" to u.g(it, 1)) }
        NAV.find(p)?.let { return act("navigate", "destination" to u.g(it, 1)) }
        NEAR.find(p)?.let { return act("maps_search", "query" to u.g(it, 1)) }
        if (Regex("^(abre|busca en|buscame en) (google|el navegador|chrome)").containsMatchIn(p)) {
            WEB.find(p)?.let { return act("web_search", "query" to u.g(it, 1)) }
        }
        WEB.find(p)?.let { return act("web_answer", "query" to u.g(it, 1)) }
        return null
    }

    // ---------- Abrir aplicaciones ----------
    private val OPEN = Regex("^(?:abre|abrir|abreme|lanza|inicia|ejecuta|arranca|entra en|entra a|accede a)(?: la aplicacion de| la aplicacion| la app de| la app| aplicacion| app| el| la| los| las| mi| mis)? (.+)$")

    private fun parseOpen(u: Utt): Parse? {
        val m = OPEN.find(u.plain) ?: return null
        return act("open_app", "app" to u.g(m, 1))
    }
}

/** Interpreta horas y duraciones dichas en español (texto ya normalizado). */
object TimeParse {
    private val WORDS = mapOf(
        "una" to 1, "uno" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4, "cinco" to 5, "seis" to 6,
        "siete" to 7, "ocho" to 8, "nueve" to 9, "diez" to 10, "once" to 11, "doce" to 12
    )

    fun time(input: String): Pair<Int, Int>? {
        var s = input
        for ((w, n) in WORDS) s = s.replace(Regex("\\b(las|la) $w\\b"), "$1 $n")
        var h: Int
        var mi = 0
        val m1 = Regex("\\b(\\d{1,2})[: h](\\d{2})\\b").find(s)
        val m3 = Regex("\\b(\\d{1,2}) y (\\d{1,2})\\b").find(s)
        if (m1 != null) {
            h = m1.groupValues[1].toInt(); mi = m1.groupValues[2].toInt()
        } else if (m3 != null) {
            h = m3.groupValues[1].toInt(); mi = m3.groupValues[2].toInt()
        } else {
            val m2 = Regex("\\b(?:las|la|a) (\\d{1,2})\\b").find(s) ?: Regex("\\b(\\d{1,2})\\b").find(s) ?: return null
            h = m2.groupValues[1].toInt()
            when {
                s.contains("y media") -> mi = 30
                s.contains("y cuarto") -> mi = 15
                s.contains("menos cuarto") -> { mi = 45; h -= 1 }
            }
        }
        if (s.contains("tarde") && h in 1..11) h += 12
        if (s.contains("noche")) {
            if (h in 7..11) h += 12
            if (h == 12) h = 0
        }
        if (h < 0) h = 23
        if (h !in 0..23 || mi !in 0..59) return null
        return Pair(h, mi)
    }

    private fun num(w: String): Double = when (w) {
        "un", "una", "uno" -> 1.0
        "media", "medio" -> 0.5
        "dos" -> 2.0; "tres" -> 3.0; "cuatro" -> 4.0; "cinco" -> 5.0; "seis" -> 6.0; "siete" -> 7.0
        "ocho" -> 8.0; "nueve" -> 9.0; "diez" -> 10.0; "quince" -> 15.0; "veinte" -> 20.0
        "treinta" -> 30.0; "cuarenta" -> 40.0; "cincuenta" -> 50.0
        else -> w.toDoubleOrNull() ?: 0.0
    }

    fun duration(p: String): Int {
        var total = 0
        val n = "(\\d+|un|una|uno|dos|tres|cuatro|cinco|seis|siete|ocho|nueve|diez|quince|veinte|treinta|cuarenta|cincuenta|media|medio)"
        Regex("\\b$n\\s*(horas?|minutos?|mins?|segundos?|segs?)\\b").findAll(p).forEach { m ->
            val v = num(m.groupValues[1])
            val unit = m.groupValues[2]
            total += when {
                unit.startsWith("h") -> (v * 3600).toInt()
                unit.startsWith("m") -> (v * 60).toInt()
                else -> v.toInt()
            }
        }
        if (Regex("\\bhoras? y media\\b").containsMatchIn(p)) total += 1800
        else if (Regex("\\bminutos? y medio\\b").containsMatchIn(p)) total += 30
        return total
    }
}
