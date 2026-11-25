package com.jarvis.asistente

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Pantalla invisible que solo hace de puente: invoca el orbe flotante de Janus sobre la
 * app actual (estilo Siri) y se cierra al momento. Si no hay permiso de superposición,
 * abre la app completa.
 */
class SummonActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val act = intent?.action
        if (act == Intent.ACTION_VOICE_COMMAND || act == android.speech.RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE) {
            Diag.init(this)
            Diag.log("Llamado desde el botón de los auriculares")
        }
        if (!WakeWordService.summon(this)) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_LISTEN, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
