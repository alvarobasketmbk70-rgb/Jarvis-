package com.jarvis.asistente

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/**
 * Voz de JANUS incluida en la app: neuronal, masculina, en español de España.
 * Gratis, sin claves, sin límites y sin internet (sherpa-onnx + Piper).
 */
object LocalVoice {
    private const val ASSET = "tts-es"
    private const val VERSION = "vits-piper-es_ES-davefx-medium-1"
    private val lock = Any()
    @Volatile
    private var tts: OfflineTts? = null
    @Volatile
    var lastError: String? = null
        private set

    fun prepareAsync(ctx: Context) {
        if (tts != null) return
        val app = ctx.applicationContext
        Thread { ensure(app) }.start()
    }

    private fun copyAssets(ctx: Context, path: String, dest: File) {
        val list = ctx.assets.list(path) ?: emptyArray()
        if (list.isEmpty()) {
            dest.parentFile?.mkdirs()
            ctx.assets.open(path).use { input -> dest.outputStream().use { input.copyTo(it) } }
            return
        }
        dest.mkdirs()
        for (name in list) copyAssets(ctx, "$path/$name", File(dest, name))
    }

    private fun ensure(ctx: Context): OfflineTts? {
        tts?.let { return it }
        synchronized(lock) {
            tts?.let { return it }
            return try {
                val dir = File(ctx.filesDir, ASSET)
                val marker = File(dir, ".version")
                if (!marker.exists() || marker.readText() != VERSION) {
                    dir.deleteRecursively()
                    copyAssets(ctx, ASSET, dir)
                    marker.writeText(VERSION)
                }
                val onnx = dir.listFiles()?.firstOrNull { it.name.endsWith(".onnx") }
                    ?: throw IllegalStateException("falta el modelo de voz")
                val vits = OfflineTtsVitsModelConfig(
                    model = onnx.absolutePath,
                    tokens = File(dir, "tokens.txt").absolutePath,
                    dataDir = File(dir, "espeak-ng-data").absolutePath
                )
                val config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(vits = vits, numThreads = 2, debug = false, provider = "cpu")
                )
                OfflineTts(config = config).also { tts = it; lastError = null }
            } catch (e: Throwable) {
                lastError = e.message ?: e.javaClass.simpleName
                null
            }
        }
    }

    /** Devuelve (muestras PCM 16 bits, frecuencia) o null si falla. */
    fun synth(ctx: Context, text: String, speed: Float = 1.0f): Pair<ByteArray, Int>? {
        val engine = ensure(ctx) ?: return null
        return try {
            val audio = synchronized(lock) { engine.generate(text = text, sid = 0, speed = speed) }
            val f = audio.samples
            val out = ByteArray(f.size * 2)
            for (i in f.indices) {
                val v = (f[i].coerceIn(-1f, 1f) * 32767f).toInt()
                out[i * 2] = (v and 0xFF).toByte()
                out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }
            Pair(out, audio.sampleRate)
        } catch (e: Throwable) {
            lastError = e.message ?: e.javaClass.simpleName
            null
        }
    }

    /** Parte el texto en frases para empezar a hablar en cuanto la primera está lista. */
    fun sentences(text: String): List<String> =
        text.split(Regex("(?<=[.!?;:])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
}
