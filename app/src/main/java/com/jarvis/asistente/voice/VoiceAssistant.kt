package com.jarvis.asistente.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import com.jarvis.asistente.MainActivity
import com.jarvis.asistente.RecognizerFactory

/*
 * Estas clases permiten elegir Janus como "Asistente digital" del sistema, de modo que
 * al mantener pulsado el botón de encendido (o el gesto de asistente) se abra Janus.
 */

class JarvisVoiceService : VoiceInteractionService()

class JarvisSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = JarvisSession(this)
}

class JarvisSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // Como Siri: el orbe aparece sobre la app actual en lugar de abrir Janus a pantalla completa.
        val i = Intent(context, com.jarvis.asistente.SummonActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        try {
            startAssistantActivity(i)
        } catch (e: Exception) {
            try {
                context.startActivity(i)
            } catch (_: Exception) {
            }
        }
        hide()
    }
}

/**
 * Android exige que un asistente declare un servicio de reconocimiento de voz.
 * En lugar de un servicio vacío (que rompería el dictado en otras apps), este
 * reenvía todo al reconocedor de Google.
 */
class JarvisRecognitionService : RecognitionService() {
    private var inner: SpeechRecognizer? = null

    override fun onStartListening(recognizerIntent: Intent?, listener: RecognitionService.Callback?) {
        val cb = listener ?: return
        inner?.destroy()
        val r = RecognizerFactory.create(this)
        if (r == null) {
            safe { cb.error(SpeechRecognizer.ERROR_CLIENT) }
            return
        }
        inner = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = safe { cb.readyForSpeech(params ?: Bundle()) }
            override fun onBeginningOfSpeech() = safe { cb.beginningOfSpeech() }
            override fun onRmsChanged(rmsdB: Float) = safe { cb.rmsChanged(rmsdB) }
            override fun onBufferReceived(buffer: ByteArray?) = safe { if (buffer != null) cb.bufferReceived(buffer) }
            override fun onEndOfSpeech() = safe { cb.endOfSpeech() }
            override fun onError(error: Int) = safe { cb.error(error) }
            override fun onResults(results: Bundle?) = safe { cb.results(results ?: Bundle()) }
            override fun onPartialResults(partialResults: Bundle?) = safe { cb.partialResults(partialResults ?: Bundle()) }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(recognizerIntent ?: RecognizerFactory.intent(this, true))
    }

    override fun onCancel(listener: RecognitionService.Callback?) {
        inner?.cancel()
    }

    override fun onStopListening(listener: RecognitionService.Callback?) {
        inner?.stopListening()
    }

    override fun onDestroy() {
        inner?.destroy()
        inner = null
        super.onDestroy()
    }

    private fun safe(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
        }
    }
}
