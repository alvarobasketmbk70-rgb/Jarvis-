package com.jarvis.asistente

import kotlin.random.Random

/** Forma de hablar de JANUS: mayordomo digital, formal y con ironía británica. */
object Personality {
    private val confirmations = listOf(
        "Entendido, %s.", "Como desee, %s.", "Como desee.", "Enseguida, %s.", "A sus órdenes, %s.", "A sus órdenes.",
        "Por supuesto, %s.", "Considérelo hecho.", "Faltaría más, %s.", "De inmediato, %s.", "Muy bien, %s.",
        "Ahora mismo, %s.", "Cómo no, %s.", "Hecho, %s."
    )
    /** Para tareas que llevan unos segundos (antes de empezar). */
    private val acks = listOf(
        "Entendido, %s. Un momento.", "Enseguida, %s.", "Como desee, %s. Me pongo con ello.", "A sus órdenes. Deme un segundo.",
        "Por supuesto, %s. Un instante.", "De inmediato, %s."
    )
    private val CONFIRM_START = Regex(
        "^(entendido|como desee|enseguida|a sus ordenes|por supuesto|considerelo|faltaria mas|de inmediato|muy bien|ahora mismo|como no|hecho|ya esta|claro|perfecto)",
        RegexOption.IGNORE_CASE
    )
    @Volatile
    private var lastPick = ""

    /** Elige una frase al azar sin repetir la última. */
    private fun fresh(list: List<String>, name: String): String {
        var s: String
        var tries = 0
        do { s = pick(list, name); tries++ } while (s == lastPick && tries < 5)
        lastPick = s
        return s
    }

    /** Frase breve al empezar una tarea larga («Entendido, señor. Un momento.»). */
    fun ack(name: String) = fresh(acks, name)
    private val thanks = listOf(
        "Para eso estoy, %s.", "Siempre a su servicio.", "Es un placer, %s. Como siempre.",
        "No hay de qué. Al fin y al cabo, no tengo nada mejor que hacer."
    )
    private val okays = listOf("Como desee, %s.", "Entendido.", "Muy bien, %s.")

    private fun pick(list: List<String>, name: String) = list[Random.nextInt(list.size)].format(name)

    private val greetings = listOf(
        "¿Sí, %s? ¿Qué desea?", "Dígame, %s.", "A su servicio, %s. ¿Qué necesita?", "¿En qué puedo ayudarle, %s?",
        "Le escucho, %s.", "Aquí estoy, %s. ¿Qué se le ofrece?", "¿Qué puedo hacer por usted, %s?"
    )

    /** Respuesta a «Hola Janus» a secas: saluda según la hora la primera vez y pregunta qué desea. */
    fun greeting(name: String): String {
        val now = System.currentTimeMillis()
        if (now - lastGreetingTime > 3 * 3600_000L) {
            lastGreetingTime = now
            val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            val hello = when (h) { in 6..13 -> "Buenos días"; in 14..20 -> "Buenas tardes"; else -> "Buenas noches" }
            return "$hello, $name. ¿Qué desea?"
        }
        return pick(greetings, name)
    }

    @Volatile
    private var lastGreetingTime = 0L

    fun thanks(name: String) = pick(thanks, name)
    fun okay(name: String) = pick(okays, name)

    /**
     * Confirma SIEMPRE las órdenes cumplidas, como un mayordomo: «Entendido, señor. Abriendo WhatsApp.».
     * Si el texto ya empieza confirmando, se deja tal cual.
     */
    fun confirm(text: String, name: String): String {
        val t = text.trim()
        if (t.isNotEmpty() && CONFIRM_START.containsMatchIn(TextUtil.stripAccents(t))) return t
        val c = fresh(confirmations, name)
        return if (t.isEmpty()) c else "$c $t"
    }
}
