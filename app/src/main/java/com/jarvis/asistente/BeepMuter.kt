package com.jarvis.asistente

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * El reconocedor de Google hace «bip» cada vez que abre y cierra el micrófono.
 * Silenciamos un instante los canales por los que suena y los restauramos al acabar.
 * Los canales silenciados se guardan para recuperarlos aunque la app se cierre de golpe.
 */
object BeepMuter {
    private val main = Handler(Looper.getMainLooper())
    private val muted = LinkedHashSet<Int>()
    private var unmuteTask: Runnable? = null

    private fun am(ctx: Context) = ctx.getSystemService(AudioManager::class.java)

    private fun save(ctx: Context) {
        ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit()
            .putString("muted_streams", muted.joinToString(",")).apply()
    }

    /** Silencia antes de abrir el micrófono. */
    fun mute(ctx: Context) {
        unmuteTask?.let { main.removeCallbacks(it) }
        unmuteTask = null
        val am = am(ctx)
        val streams = mutableListOf(AudioManager.STREAM_SYSTEM, AudioManager.STREAM_NOTIFICATION)
        // La música solo se silencia si no está sonando nada (para no cortar Spotify).
        if (!am.isMusicActive) streams.add(AudioManager.STREAM_MUSIC)
        for (s in streams) {
            if (s in muted) continue
            try {
                if (!am.isStreamMute(s)) {
                    am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0)
                    muted.add(s)
                }
            } catch (_: Exception) {
                // Algunos móviles no dejan tocar ciertos canales (No molestar): se ignora.
            }
        }
        save(ctx)
    }

    /**
     * Tras un pequeño margen devuelve solo la música. Los sonidos del sistema y de
     * notificaciones siguen en silencio mientras Jarvis esté escuchando, que es por donde
     * suele sonar el «bip» del micrófono; se restauran con [unmuteNow].
     */
    fun unmuteSoon(ctx: Context, delayMs: Long = 400) {
        unmuteTask?.let { main.removeCallbacks(it) }
        val appCtx = ctx.applicationContext
        val task = Runnable { unmuteMusic(appCtx) }
        unmuteTask = task
        main.postDelayed(task, delayMs)
    }

    private fun unmuteMusic(ctx: Context) {
        unmuteTask = null
        if (AudioManager.STREAM_MUSIC !in muted) return
        try { am(ctx).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0) } catch (_: Exception) {}
        muted.remove(AudioManager.STREAM_MUSIC)
        save(ctx)
    }

    fun unmuteNow(ctx: Context) {
        unmuteTask?.let { main.removeCallbacks(it) }
        unmuteTask = null
        if (muted.isEmpty()) return
        val am = am(ctx)
        for (s in muted) {
            try { am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0) } catch (_: Exception) {}
        }
        muted.clear()
        save(ctx)
    }

    /** Si la app se cerró con canales silenciados, los devuelve a la normalidad. */
    fun restoreAfterCrash(ctx: Context) {
        if (muted.isNotEmpty()) return
        val saved = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getString("muted_streams", "") ?: ""
        if (saved.isBlank()) return
        val am = am(ctx)
        for (p in saved.split(",")) {
            val s = p.trim().toIntOrNull() ?: continue
            try { am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0) } catch (_: Exception) {}
        }
        ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().putString("muted_streams", "").apply()
    }
}
