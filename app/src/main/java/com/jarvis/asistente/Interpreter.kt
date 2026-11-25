package com.jarvis.asistente

import android.content.Context
import org.json.JSONArray

/** Idiomas que JANUS sabe nombrar (sin tildes, como llegan en [Utt.plain]). */
object Languages {
    private val BY_NAME = linkedMapOf(
        "ingles" to "en", "americano" to "en", "britanico" to "en",
        "frances" to "fr", "aleman" to "de", "italiano" to "it", "portugues" to "pt", "brasileno" to "pt",
        "chino" to "zh", "mandarin" to "zh", "japones" to "ja", "coreano" to "ko", "ruso" to "ru",
        "arabe" to "ar", "holandes" to "nl", "neerlandes" to "nl", "catalan" to "ca", "gallego" to "gl",
        "euskera" to "eu", "vasco" to "eu", "polaco" to "pl", "turco" to "tr", "griego" to "el",
        "sueco" to "sv", "noruego" to "no", "danes" to "da", "finlandes" to "fi", "hindi" to "hi",
        "rumano" to "ro", "ucraniano" to "uk", "checo" to "cs", "hungaro" to "hu", "hebreo" to "he",
        "tailandes" to "th", "vietnamita" to "vi", "indonesio" to "id", "espanol" to "es", "castellano" to "es"
    )
    private val NAMES = mapOf(
        "en" to "inglés", "fr" to "francés", "de" to "alemán", "it" to "italiano", "pt" to "portugués",
        "zh" to "chino", "ja" to "japonés", "ko" to "coreano", "ru" to "ruso", "ar" to "árabe", "nl" to "neerlandés",
        "ca" to "catalán", "gl" to "gallego", "eu" to "euskera", "pl" to "polaco", "tr" to "turco", "el" to "griego",
        "sv" to "sueco", "no" to "noruego", "da" to "danés", "fi" to "finés", "hi" to "hindi", "ro" to "rumano",
        "uk" to "ucraniano", "cs" to "checo", "hu" to "húngaro", "he" to "hebreo", "th" to "tailandés",
        "vi" to "vietnamita", "id" to "indonesio", "es" to "español"
    )
    /** Nombre del idioma en inglés, para el texto de sistema del traductor. */
    private val EN = mapOf(
        "en" to "English", "fr" to "French", "de" to "German", "it" to "Italian", "pt" to "Portuguese",
        "zh" to "Mandarin Chinese", "ja" to "Japanese", "ko" to "Korean", "ru" to "Russian", "ar" to "Arabic",
        "nl" to "Dutch", "ca" to "Catalan", "gl" to "Galician", "eu" to "Basque", "pl" to "Polish", "tr" to "Turkish",
        "el" to "Greek", "sv" to "Swedish", "no" to "Norwegian", "da" to "Danish", "fi" to "Finnish", "hi" to "Hindi",
        "ro" to "Romanian", "uk" to "Ukrainian", "cs" to "Czech", "hu" to "Hungarian", "he" to "Hebrew", "th" to "Thai",
        "vi" to "Vietnamese", "id" to "Indonesian", "es" to "Spanish (Spain)"
    )

    fun code(word: String): String? {
        val w = TextUtil.stripAccents(word.lowercase(ES).trim())
        BY_NAME[w]?.let { return it }
        // «el inglés», «inglés británico»…
        return w.split(" ").firstNotNullOfOrNull { BY_NAME[it] }
    }

    fun name(code: String): String = NAMES[base(code)] ?: code
    fun english(code: String): String = EN[base(code)] ?: code
    fun base(code: String): String = code.lowercase().substringBefore('-').substringBefore('_')
    val pattern: String get() = BY_NAME.keys.joinToString("|")
}

/**
 * Órdenes de traducción y de memoria. Se comprueban antes que el resto de [Commands]
 * para que «traduce llama a mi madre al inglés» no se convierta en una llamada.
 */
object LangCommands {
    private val L get() = Languages.pattern
    private val START by lazy {
        listOf(
            Regex("^(?:activa |pon |inicia |empieza |abre |entra en )?(?:el |en )?modo (?:traductor|traduccion|interprete)(?: (?:al|a|en|con|del espanol al|de espanol a|para))? ?(?:el )?($L)?$"),
            Regex("^(?:traduce|traduceme|traducir|ve traduciendo|vete traduciendo)(?: todo)? lo que (?:diga|digamos|oigas|escuches|hablemos|me digan|me diga|diga el|diga ella|diga el senor|digan|te diga)(?: .*?)?(?: (?:al|a|en|del|desde el) ($L))?$"),
            Regex("^(?:hazme de|haz de|se mi|actua de|actua como|necesito un|quiero un) (?:traductor|interprete)(?: (?:al|a|en|con|de|del) ($L))?$"),
            Regex("^(?:quiero|necesito|voy a) hablar (?:en|con alguien en|con alguien que habla|con una persona que habla|con un) ($L)$"),
            Regex("^(?:traduccion simultanea|traduccion en tiempo real|traduce en tiempo real|traduccion en directo)(?: (?:al|a|en|con) ($L))?$")
        )
    }
    private val ONE_SHOT by lazy {
        listOf(
            Regex("^(?:como se dice|como digo|como se pronuncia) (.+?) en ($L)$"),
            Regex("^(?:traduce|traduceme|traducir|traduzca|dime)(?: la frase| esto| esta frase)? (.+?) (?:al|a|en) ($L)$"),
            Regex("^(?:dime|di) en ($L) (.+)$")
        )
    }
    private val STOP = Regex(
        "(?:fin|final) del? (?:modo )?(?:traductor|interprete|traduccion)|" +
            "(?:para|desactiva|quita|cierra|apaga|sal del|salir del|termina|acaba|deten|deja) (?:el |de )?(?:modo )?(?:traductor|interprete|traduccion|traducir)|" +
            "deja de traducir|ya no traduzcas|no traduzcas mas|ya esta bien de traducir|^(?:para|basta|ya esta|stop|terminamos|salir|sal)$"
    )

    private val REMEMBER = Regex(
        "^(?:(?:recuerda|recuerdes|recuerde|memoriza)(?: en tu memoria| en la memoria)?(?: que)?|" +
            "(?:apunta|anota|guarda)(?: en tu memoria| en la memoria)? que|(?:apunta|anota|guarda) en (?:tu|la) memoria(?: que)?|" +
            "acuerdate de que|no te olvides de que|no olvides que) (.+)$"
    )
    private val RECALL = Regex("^(?:que (?:sabes|recuerdas|tienes apuntado|tienes anotado|tienes guardado)(?: de mi| sobre mi| en (?:la|tu) memoria)?|que te he (?:dicho|pedido) que recuerdes|que hay en tu memoria|tu memoria)$")
    private val FORGET_ALL = Regex("^(?:olvida|borra|vacia|limpia) (?:todo lo que sabes(?: de mi)?|tu memoria|la memoria|todos los recuerdos|todo lo que (?:te he dicho|recuerdas)|todo)$")
    private val FORGET = Regex("^(?:olvida|borra de tu memoria|olvidate de)(?: que)? (.+)$")

    fun isStop(raw: String): Boolean = STOP.containsMatchIn(Utt(raw).plain)

    fun parse(u: Utt): Parse? {
        val p = u.plain
        for (r in START) {
            val m = r.find(p) ?: continue
            val word = m.groupValues.getOrNull(1).orEmpty()
            val code = if (word.isBlank()) null else Languages.code(word)
            return startWith(code)
        }
        for ((i, r) in ONE_SHOT.withIndex()) {
            val m = r.find(p) ?: continue
            val (textGroup, langGroup) = if (i == 2) 2 to 1 else 1 to 2
            val code = Languages.code(m.groupValues[langGroup]) ?: continue
            val text = u.g(m, textGroup).removeSurrounding("\"").trim()
            if (text.isBlank()) continue
            return Parse.Do(Action("translate", mapOf("text" to text, "lang" to code)))
        }
        if (RECALL.matches(p)) return Parse.Do(Action("recall"))
        if (FORGET_ALL.matches(p)) return Parse.Do(Action("forget", mapOf("all" to "true")))
        FORGET.find(p)?.let { m -> if (!p.startsWith("olvidalo")) return Parse.Do(Action("forget", mapOf("fact" to u.g(m, 1)))) }
        // «recuérdame…» es un recordatorio, no un dato para la memoria.
        if (!p.startsWith("recuerdame")) REMEMBER.find(p)?.let { m ->
            val fact = u.g(m, 1)
            if (fact.split(" ").size >= 2) return Parse.Do(Action("remember", mapOf("fact" to fact)))
        }
        return null
    }

    private fun startWith(code: String?): Parse =
        if (code != null && code != "es") Parse.Do(Action("interpreter", mapOf("lang" to code)))
        else Parse.Ask("¿A qué idioma traduzco, {n}?") { ans ->
            val c = Languages.code(Utt(ans).plain)
            if (c == null || c == "es") Parse.Say("No conozco ese idioma. Dígame, por ejemplo, «modo traductor al inglés».")
            else Parse.Do(Action("interpreter", mapOf("lang" to c)))
        }
}

/** Memoria a largo plazo: datos que el usuario pide recordar. Persisten entre sesiones. */
object Memory {
    private const val KEY = "memories"
    private const val MAX = 40

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    fun all(ctx: Context): List<String> {
        val raw = sp(ctx).getString(KEY, null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(ctx: Context, items: List<String>) {
        val a = JSONArray()
        items.takeLast(MAX).forEach { a.put(it) }
        sp(ctx).edit().putString(KEY, a.toString()).apply()
    }

    fun add(ctx: Context, fact: String) {
        val f = TextUtil.cap(fact.trim().trimEnd('.'))
        if (f.isBlank()) return
        val items = all(ctx).filterNot { it.equals(f, ignoreCase = true) } + f
        save(ctx, items)
    }

    /** Borra los recuerdos que comparten palabras con [query]. Devuelve cuántos borró. */
    fun forget(ctx: Context, query: String): Int {
        val words = TextUtil.stripAccents(query.lowercase(ES)).split(Regex("\\W+")).filter { it.length > 3 }.toSet()
        if (words.isEmpty()) return 0
        val items = all(ctx)
        val keep = items.filterNot { item ->
            val iw = TextUtil.stripAccents(item.lowercase(ES)).split(Regex("\\W+")).toSet()
            words.count { it in iw } >= maxOf(1, words.size / 2)
        }
        save(ctx, keep)
        return items.size - keep.size
    }

    fun clear(ctx: Context) = sp(ctx).edit().remove(KEY).apply()

    /** Para el texto de sistema de la IA. */
    fun prompt(ctx: Context): String {
        val items = all(ctx)
        if (items.isEmpty()) return ""
        return "\nDatos que tu usuario te pidió recordar (escritos tal como él los dijo, en primera persona): " +
            items.joinToString("; ") + ". Úsalos con naturalidad cuando vengan al caso.\n"
    }

    /** «mi coche es rojo» → «su coche es rojo», para leerlo en voz alta. */
    fun toSecondPerson(s: String): String {
        val swaps = mapOf(
            "mi" to "su", "mis" to "sus", "me" to "le", "yo" to "usted", "conmigo" to "con usted",
            "soy" to "es", "estoy" to "está", "tengo" to "tiene", "vivo" to "vive", "trabajo" to "trabaja",
            "prefiero" to "prefiere", "odio" to "odia", "quiero" to "quiere", "suelo" to "suele", "mio" to "suyo", "mía" to "suya"
        )
        return s.split(" ").joinToString(" ") { w ->
            val lower = w.lowercase(ES)
            swaps[lower]?.let { r -> if (w.firstOrNull()?.isUpperCase() == true) TextUtil.cap(r) else r } ?: w
        }
    }
}
