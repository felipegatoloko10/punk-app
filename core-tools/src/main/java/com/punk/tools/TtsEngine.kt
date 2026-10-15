package com.punk.tools

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

object TtsEngine {

    private var tts: TextToSpeech? = null
    private var isInitialized = false

    private suspend fun getEngine(ctx: Context): TextToSpeech = withContext(Dispatchers.Main) {
        if (tts != null && isInitialized) return@withContext tts!!

        suspendCancellableCoroutine { cont ->
            tts = TextToSpeech(ctx.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    isInitialized = true
                    try {
                        tts?.language = Locale("pt", "BR")
                    } catch (_: Exception) {}
                    if (cont.isActive) cont.resume(tts!!)
                } else {
                    isInitialized = false
                    if (cont.isActive) cont.resume(tts!!)
                }
            }
        }
    }

    suspend fun speak(ctx: Context, text: String, pitch: Float = 1.0f, rate: Float = 1.0f): Boolean {
        val engine = getEngine(ctx)
        if (!isInitialized) return false

        return suspendCancellableCoroutine { cont ->
            val uttId = UUID.randomUUID().toString()
            engine.setPitch(pitch.coerceIn(0.5f, 2.0f))
            engine.setSpeechRate(rate.coerceIn(0.5f, 2.0f))

            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == uttId && cont.isActive) cont.resume(true)
                }
                override fun onError(utteranceId: String?) {
                    if (utteranceId == uttId && cont.isActive) cont.resume(false)
                }
            })

            val params = Bundle()
            val res = engine.speak(text, TextToSpeech.QUEUE_ADD, params, uttId)
            if (res != TextToSpeech.SUCCESS && cont.isActive) {
                cont.resume(false)
            }
        }
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (_: Exception) {}
    }
}
