package com.jarvis.asistente

import kotlin.random.Random

/** Forma de hablar de JANUS: mayordomo digital, formal y con ironía británica. */
object Personality {
    private val confirmations = listOf(
        "Como desee, %s.", "Enseguida, %s.", "Ya está hecho, %s.", "A sus órdenes.",
        "Por supuesto, %s.", "Considérelo hecho.", "Faltaría más, %s."
    )
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

    /** Añade una confirmación de mayordomo a las órdenes cumplidas («Como desee, señor. Abriendo WhatsApp.»). */
    fun confirm(text: String, name: String): String {
        if (text.isBlank()) return text
        if (Random.nextInt(100) >= 70) return text
        return pick(confirmations, name) + " " + text
    }
}
