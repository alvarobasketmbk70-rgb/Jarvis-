package com.jarvis.asistente

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Groq: IA gratuita y extremadamente rápida. Transcribe la voz (Whisper) en décimas de
 * segundo y responde con modelos Llama. Es el cerebro principal si hay clave de Groq.
 */
object Groq {
    private const val BASE = "https://api.groq.com/openai/v1"
    private val CHAT_MODELS = listOf(
        "llama-3.3-70b-versatile", "meta-llama/llama-4-scout-17b-16e-instruct", "openai/gpt-oss-20b", "llama-3.1-8b-instant"
    )
    private val STT_MODELS = listOf("whisper-large-v3-turbo", "whisper-large-v3")
    @Volatile
    private var chatModel: String? = null
    @Volatile
    private var sttModel: String? = null

    private fun transient(code: Int) = code == 429 || code in 500..504

    private fun open(path: String, key: String, timeoutMs: Int): HttpURLConnection =
        (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 6000
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Authorization", "Bearer $key")
        }

    private fun read(c: HttpURLConnection): String {
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        if (code !in 200..299) throw LlmClient.HttpError(code, text.take(300), "Groq")
        return text
    }

    /** Voz → texto en español. */
    fun transcribe(key: String, wav: ByteArray, vocabulary: String = "", language: String? = "es"): String {
        val models = (listOfNotNull(sttModel) + STT_MODELS).distinct()
        var last: Exception? = null
        for (m in models) {
            val boundary = "janus" + System.nanoTime()
            val c = open("/audio/transcriptions", key, 12000)
            try {
                c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                val body = ByteArrayOutputStream()
                fun field(name: String, value: String) {
                    body.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
                }
                field("model", m)
                if (language != null) field("language", language)
                field("response_format", "json")
                field("temperature", "0")
                if (language == "es") field("prompt", ("Órdenes para el asistente JANUS (se pronuncia Yanus): Spotify, WhatsApp, llamar, poner música. " + vocabulary).take(800))
                body.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"voz.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
                body.write(wav)
                body.write("\r\n--$boundary--\r\n".toByteArray())
                c.outputStream.use { it.write(body.toByteArray()) }
                val text = JSONObject(read(c)).optString("text", "").trim()
                sttModel = m
                return text
            } catch (e: LlmClient.HttpError) {
                last = e
                if (e.code != 404 && e.code != 400 && !transient(e.code)) throw e
            } finally {
                c.disconnect()
            }
        }
        throw last ?: LlmClient.HttpError(503, "sin modelo de voz")
    }

    /** Conversación con respuesta en JSON (mismo formato que Gemini). */
    fun chat(key: String, system: String, history: List<Pair<String, String>>, user: String): String {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        for ((u, a) in history) {
            messages.put(JSONObject().put("role", "user").put("content", u))
            messages.put(JSONObject().put("role", "assistant").put("content", a))
        }
        messages.put(JSONObject().put("role", "user").put("content", user))
        val models = (listOfNotNull(chatModel) + CHAT_MODELS).distinct()
        var last: Exception? = null
        for (m in models) {
            val c = open("/chat/completions", key, 15000)
            try {
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                val body = JSONObject()
                    .put("model", m)
                    .put("messages", messages)
                    .put("temperature", 0.5)
                    .put("max_tokens", 400)
                    .put("response_format", JSONObject().put("type", "json_object"))
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val res = JSONObject(read(c))
                val text = res.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "")
                chatModel = m
                return text
            } catch (e: LlmClient.HttpError) {
                last = e
                if (e.code != 404 && e.code != 400 && !transient(e.code)) throw e
            } finally {
                c.disconnect()
            }
        }
        throw last ?: LlmClient.HttpError(503, "sin modelo")
    }
}
