package com.jarvis.asistente

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Como Siri: mientras JANUS escucha y responde, la música se pausa (y se reanuda sola al
 * terminar), así el micrófono solo capta su voz.
 */
object InteractionFocus {
    private var request: AudioFocusRequest? = null

    fun hold(ctx: Context) {
        if (request != null) return
        val am = ctx.getSystemService(AudioManager::class.java)
        val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { }
            .build()
        try {
            am.requestAudioFocus(r)
            request = r
        } catch (_: Exception) {
        }
    }

    fun release(ctx: Context) {
        val r = request ?: return
        request = null
        try { ctx.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(r) } catch (_: Exception) {}
    }

    /** Órdenes que controlan la música: hay que soltar el audio antes de ejecutarlas. */
    fun touchesMedia(a: Action?): Boolean = a != null && a.type in setOf("media", "spotify_play", "youtube", "volume", "agent")
}
