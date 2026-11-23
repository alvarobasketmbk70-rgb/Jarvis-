package com.jarvis.asistente

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Sonidos tipo holograma, sintetizados al momento (no hay archivos de audio). */
object Chime {
    private const val RATE = 44100

    /** Notas (frecuencia, inicio en ms) de cada sonido. */
    private val START = listOf(880.0 to 0, 1318.5 to 70, 1760.0 to 140, 2637.0 to 200)
    private val END = listOf(1760.0 to 0, 1174.7 to 80)

    private fun render(notes: List<Pair<Double, Int>>, lengthMs: Int, gain: Float): ShortArray {
        val n = RATE * lengthMs / 1000
        val out = FloatArray(n)
        for ((f, startMs) in notes) {
            val s0 = RATE * startMs / 1000
            for (i in s0 until n) {
                val t = (i - s0).toDouble() / RATE
                val env = exp(-t * 14.0) * (1 - exp(-t * 900.0))
                // Nota principal + armónico + una copia ligeramente desafinada que da el brillo «holográfico».
                val v = sin(2 * PI * f * t) + 0.35 * sin(2 * PI * f * 2.0 * t) + 0.25 * sin(2 * PI * (f * 1.006) * t)
                out[i] += (v * env).toFloat()
            }
        }
        // Eco corto y suave
        val d = RATE * 45 / 1000
        for (i in d until n) out[i] += out[i - d] * 0.3f
        val max = out.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-3f)
        return ShortArray(n) { (out[it] / max * gain * 32767f).toInt().toShort() }
    }

    private val startPcm by lazy { render(START, 420, 0.35f) }
    private val endPcm by lazy { render(END, 300, 0.25f) }

    private fun play(pcm: ShortArray) {
        Thread {
            try {
                val t = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder().setSampleRate(RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                    )
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.size * 2)
                    .build()
                t.write(pcm, 0, pcm.size)
                t.play()
                Thread.sleep((pcm.size * 1000L / RATE) + 60)
                t.release()
            } catch (_: Exception) {
            }
        }.start()
    }

    /** Sonido al empezar a escuchar. Devuelve cuánto dura (para abrir el micrófono después). */
    fun start(): Long { play(startPcm); return 380 }
    fun end() = play(endPcm)
}
