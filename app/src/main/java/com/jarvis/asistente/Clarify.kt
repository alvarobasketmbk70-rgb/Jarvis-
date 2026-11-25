package com.jarvis.asistente

/**
 * Antes de actuar, JANUS comprueba que la orden tiene lo necesario. Si falta algo
 * (qué música, a quién, a qué hora…), pregunta en lugar de adivinar.
 */
object Clarify {
    private val GENRES = listOf(
        "rock", "pop", "jazz", "blues", "reggaeton", "regueton", "trap", "rap", "hip hop", "clasica", "electronica", "techno",
        "house", "flamenco", "salsa", "bachata", "merengue", "cumbia", "indie", "metal", "punk", "funk", "soul", "r&b", "country",
        "reggae", "lofi", "lo-fi", "lo fi", "ochentera", "de los 80", "de los 90", "de los 2000", "ochentas", "noventas", "latina",
        "romantica", "baladas", "rumba", "k-pop", "kpop", "opera", "bandas sonoras", "disco", "dance", "urbana", "rancheras", "tango"
    )
    private val MOODS = listOf(
        "tranquil", "relaj", "para dormir", "para estudiar", "para concentrar", "para trabajar", "para entrenar", "para el gimnasio",
        "para correr", "animad", "alegre", "triste", "para conducir", "de fiesta", "para una fiesta", "motivador", "energia", "chill",
        "romantic", "para cocinar", "de fondo", "suave", "cañera", "canera", "movida", "bailar", "para bailar"
    )
    private val WHATEVER = Regex("^(lo que (tu )?quieras|lo que sea|lo que te parezca|me da igual|da igual|cualquiera|cualquier cosa|sorprendeme|elige tu|escoge tu|decide tu|tu mismo|algo|algo que me guste|lo que me guste|mi musica|mis canciones|mi mix|mi playlist)$")
    private val RESUME = Regex("^(lo de antes|lo que estaba sonando|la que estaba|lo que estaba|reanuda(la)?|continua|sigue|la misma|lo mismo|lo ultimo|la de antes|donde lo deje)$")

    const val MUSIC_Q = "¿Qué le apetece escuchar, {n}? Dígame un estilo, un artista, una canción o una lista; o «lo de antes» para reanudar."

    /** «pon música», «pon algo», «quiero escuchar música»… sin decir qué. */
    val VAGUE_MUSIC = Regex(
        "^(?:pon|ponme|reproduce|reproducir|toca|quiero|me apetece|necesito|escuchar|quiero escuchar|me apetece escuchar|vamos a escuchar|pon un poco de|ponme un poco de)" +
            "(?: un poco de| algo de)?(?: la| una| algo| un poco)? ?(?:musica|cancion|canciones|playlist|lista|lista de reproduccion|algo|tema|temazo|temazos)?(?: en spotify| de fondo| por favor)?$"
    )

    fun isVagueMusic(plain: String): Boolean =
        VAGUE_MUSIC.matches(plain) && Regex("musica|cancion|playlist|lista|algo|tema|spotify").containsMatchIn(plain)

    /** Estilo favorito del usuario, si se lo contó a JANUS («recuerda que me gusta el rock»). */
    fun likes(ctx: android.content.Context): String? {
        for (f in Memory.all(ctx).reversed()) {
            val t = TextUtil.stripAccents(f.lowercase(ES))
            if (!Regex("gusta|encanta|escucho|favorit|prefiero").containsMatchIn(t)) continue
            GENRES.firstOrNull { Regex("\\b" + Regex.escape(it) + "\\b").containsMatchIn(t) }?.let { return it }
        }
        return null
    }

    /** Convierte la respuesta a «¿qué le apetece?» en la acción adecuada. */
    fun musicAnswer(raw: String, likes: String?): Parse {
        val u = Utt(raw)
        var p = u.plain
        var soft = u.soft
        if (p.isBlank()) return Parse.Ask(MUSIC_Q) { musicAnswer(it, likes) }
        if (RESUME.matches(p)) return Parse.Do(Action("media", mapOf("cmd" to "play")))
        if (WHATEVER.matches(p)) {
            val q = likes?.let { "$it mix" } ?: "Daily Mix 1"
            return Parse.Do(Action("spotify_play", mapOf("query" to q)), "Permítame elegir: le pongo ${if (likes != null) "algo de $likes" else "su mezcla diaria"}.")
        }
        // Quitar muletillas: «pues algo de rock», «ponme la de Queen»…
        val strip = Regex("^(?:pues |venga |vale |eh |mmm |bueno )*(?:pon(?:me)? |reproduce |quiero |me apetece |prefiero |mejor )?(?:escuchar )?(?:algo de |musica de |canciones de |un poco de |la cancion |el album |el disco |la playlist |la lista |la de |el de |lo de |una de |uno de |algo |musica )?")
        val m = strip.find(p)
        if (m != null && m.range.last + 1 < p.length) {
            soft = soft.substring(m.range.last + 1).trim(); p = p.substring(m.range.last + 1).trim()
        }
        soft = soft.replace(Regex("\\s+(en spotify|por favor)$"), "").trim()
        p = TextUtil.stripAccents(soft.lowercase(ES))
        if (soft.isBlank()) return Parse.Ask(MUSIC_Q) { musicAnswer(it, likes) }
        val isGenre = GENRES.any { p == it || p.startsWith("$it ") || p.endsWith(" $it") }
        val isMood = MOODS.any { p.contains(it) }
        val query = when {
            isGenre -> "$soft mix"
            isMood -> "música $soft"
            else -> soft
        }
        return Parse.Do(Action("spotify_play", mapOf("query" to query, "kind" to if (isGenre || isMood) "playlist" else "any")))
    }

    /** Qué falta en una acción y cómo preguntarlo. Null si está completa. */
    fun missing(a: Action, likes: String?): Parse.Ask? {
        fun ask(q: String, build: (String) -> Parse) = Parse.Ask(q, build)
        fun fill(k: String, v: String) = Parse.Do(Action(a.type, a.args + (k to v.trim())))
        fun answer(s: String) = s.trim().replace(Regex("^(a |al |con |para |que |pues |de )", RegexOption.IGNORE_CASE), "").trim()
        return when (a.type) {
            "spotify_play" -> if (a.arg("query").isBlank()) ask(MUSIC_Q) { musicAnswer(it, likes) } else null
            "call" -> if (a.arg("contact").isBlank()) ask("¿A quién llamo, {n}?") { fill("contact", answer(it)) } else null
            "whatsapp", "sms" -> when {
                a.arg("contact").isBlank() -> ask("¿A quién se lo envío?") { recheck(fill("contact", answer(it)), likes) }
                a.arg("message").isBlank() -> ask("¿Qué le digo a ${TextUtil.cap(a.arg("contact"))}?") { fill("message", TextUtil.cap(it.trim())) }
                else -> null
            }
            "open_app" -> if (a.arg("app").isBlank()) ask("¿Qué aplicación abro?") { fill("app", answer(it)) } else null
            "navigate" -> if (a.arg("destination").isBlank()) ask("¿A dónde quiere ir?") { fill("destination", answer(it)) } else null
            "maps_search" -> if (a.arg("query").isBlank()) ask("¿Qué busco cerca de usted?") { fill("query", answer(it)) } else null
            "youtube" -> if (a.arg("query").isBlank()) ask("¿Qué quiere ver en YouTube?") { fill("query", answer(it)) } else null
            "web_answer", "web_search" -> if (a.arg("query").isBlank()) ask("¿Qué quiere que busque?") { fill("query", it.trim()) } else null
            "translate" -> if (a.arg("text").isBlank()) ask("¿Qué frase traduzco?") { fill("text", it.trim()) } else null
            "alarm" -> if (a.arg("hour").isBlank()) ask("¿A qué hora pongo la alarma?") { ans ->
                Commands.parse("pon una alarma a las $ans") ?: Parse.Say("No he entendido la hora. Dígamelo de nuevo, por ejemplo «alarma a las siete y media».")
            } else null
            "timer" -> if ((a.arg("seconds").toIntOrNull() ?: 0) <= 0) ask("¿De cuánto tiempo pongo el temporizador?") { ans ->
                Commands.parse("temporizador de $ans") ?: Parse.Say("No he entendido la duración.")
            } else null
            else -> null
        }
    }

    /** Tras rellenar un dato, comprueba si aún falta otro. */
    private fun recheck(p: Parse, likes: String?): Parse =
        if (p is Parse.Do) missing(p.action, likes) ?: p else p
}
